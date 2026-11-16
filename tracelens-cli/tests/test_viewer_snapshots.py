"""Full UI pins persist independently of JVM retention and browser profiles."""

import copy
from concurrent.futures import ThreadPoolExecutor
from email.message import Message
import io
import json
import os
import http.client
from pathlib import Path
import stat
import threading
from types import SimpleNamespace
import uuid

import pytest

from tracelens_cli import server, viewer_snapshots as snapshots
from tracelens_cli.services import Service


NAMESPACE = "a" * 64
TRACE = "0123456789abcdef" * 2


def evidence():
    def segment(service):
        instance, sid = service + "-instance", service + "-segment"
        return {"service": service, "serviceInstanceId": instance, "segmentId": sid,
                "segmentKey": json.dumps([service, instance, sid]), "traceId": TRACE,
                "entryChannel": "HTTP", "status": "COMPLETED"}
    rows = [segment("cb-app"), segment("cb-ems")]
    nodes = [{**row, "id": "n" + str(i), "kind": "segment"} for i, row in enumerate(rows)]
    nodes += [{"id": "db", "kind": "dependency-call", "boundaryKind": "database-route", "destination": "TEST_MASTER"},
              {"id": "sqs", "kind": "dependency-call", "boundaryKind": "messaging", "system": "sqs", "status": "SUBMITTED"}]
    graph = {"schemaVersion": 1, "kind": "request-flow", "traceId": TRACE,
             "nodes": nodes, "edges": [{"from": "n0", "to": "n1"}, {"from": "n1", "to": "db"}, {"from": "n1", "to": "sqs"}],
             "roots": ["n0"]}
    return {"name": "Demo: cb-app → EMS → DB + SQS", "traceId": TRACE, "capturedAt": 1234567,
            "partial": False, "warnings": [],
            "request": {"traceId": TRACE, "label": "POST /api/v2/features", "services": ["cb-app", "cb-ems"], "segments": rows},
            "journey": {"traceId": TRACE, "segments": rows, "links": [], "httpCalls": [], "visualization": graph},
            "segments": [{**row, "events": [{"type": "LINE", "seq": 1, "sourceLine": "original saved source"}]} for row in rows]}


@pytest.fixture
def store(tmp_path):
    return snapshots.ViewerSnapshotStore(tmp_path / "pins", NAMESPACE)


def test_preserves_all_evidence_and_original_capture_time_across_restart_and_no_io(store, monkeypatch):
    def no_io(*args, **kwargs):
        raise AssertionError("snapshots must never fetch upstream or rebuild evidence")
    monkeypatch.setattr(server.urllib.request, "urlopen", no_io)
    monkeypatch.setattr(server, "build_visualization", no_io)
    monkeypatch.setattr(server.source, "source_line", no_io)
    original = evidence()
    saved = store.save(original)
    assert saved["id"] == str(uuid.UUID(saved["id"]))
    assert saved["capturedAt"] == original["capturedAt"]
    assert saved["savedAt"] > saved["capturedAt"]
    assert saved["journey"] == original["journey"]
    assert saved["segments"] == original["segments"]
    original["segments"][0]["events"][0]["sourceLine"] = "changed source"
    restarted = snapshots.ViewerSnapshotStore(store.root.parent, NAMESPACE)
    assert restarted.get(saved["id"]) == saved
    listing = restarted.list()
    assert listing["count"] == 1
    assert listing["limits"] == snapshots.LIMITS
    assert listing["snapshots"][0]["segmentCount"] == 2
    assert "segments" not in listing["snapshots"][0]["request"]
    assert listing["totalBytes"] == (store.root / (saved["id"] + ".json")).stat().st_size
    assert "original saved source" not in json.dumps(listing)


def test_scheduler_list_metadata_survives_round_trip_without_raw_attributes(store):
    original = evidence()
    job = {"channel": "SCHEDULER", "outcome": "skipped", "jobType": "compute_billing_metrics", "jobId": "42",
           "jobOutcome": "SKIPPED", "jobAuditStatus": "IGNORED", "jobFailureType": None, "scheduler": "chargebee"}
    original["request"].update(job, attributes={"payload": "SECRET_JOB_PAYLOAD"})
    saved = store.save(original)
    listing = store.list()["snapshots"][0]["request"]
    assert all(listing[key] == value for key, value in job.items())
    assert "attributes" not in listing and "SECRET_JOB_PAYLOAD" not in json.dumps(listing)
    assert store.get(saved["id"])["request"]["jobOutcome"] == "SKIPPED"


def test_new_saves_never_overwrite_even_with_client_id(store):
    original = evidence()
    original["id"] = str(uuid.uuid4())
    first, second = store.save(original), store.save(original)
    assert first["id"] != second["id"] != original["id"]
    assert store.list()["count"] == 2
    renamed = store.rename(first["id"], "  Demo renamed  ")
    assert renamed["name"] == "Demo renamed"
    assert store.get(first["id"]) == {**first, "name": "Demo renamed"}
    assert store.get(second["id"]) == second
    assert store.delete(first["id"]) == {"id": first["id"], "deleted": True}
    with pytest.raises(snapshots.SnapshotError, match="not found"):
        store.get(first["id"])
    assert store.list()["count"] == 1


def test_directories_and_files_private_and_uncreated_until_save(store):
    assert store.list()["snapshots"] == []
    assert not store.root.exists()
    saved = store.save(evidence())
    assert stat.S_IMODE(store.root.stat().st_mode) == 0o700
    assert stat.S_IMODE((store.root / (saved["id"] + ".json")).stat().st_mode) == 0o600
    assert stat.S_IMODE((store.root / ".lock").stat().st_mode) == 0o600


def test_namespace_changes_with_source_or_service_configuration_but_not_order_or_viewer_port(tmp_path):
    services = [Service("app", "http://localhost:7778", "/work/app"), Service("ems", "http://localhost:7779", "/work/ems")]
    namespace = server._viewer_context("unused", None, services)["namespace"]
    assert server._viewer_context("other-unused", None, list(reversed(services)))["namespace"] == namespace
    changed = server._viewer_context("unused", None, [Service("app", "http://localhost:7778", "/work/other")])["namespace"]
    assert changed != namespace
    first = snapshots.ViewerSnapshotStore(tmp_path / "pins", namespace)
    saved = first.save(evidence())
    other = snapshots.ViewerSnapshotStore(tmp_path / "pins", changed)
    assert other.list()["snapshots"] == []
    with pytest.raises(snapshots.SnapshotError) as exc:
        other.get(saved["id"])
    assert exc.value.status == 404


def test_partial_capture_retains_graph_node_without_missing_export(store):
    doc = evidence()
    doc["segments"].pop()
    doc["partial"] = True
    doc["warnings"] = ["EMS source export was unavailable; original graph retained."]
    assert store.save(doc)["journey"] == doc["journey"]


def test_legacy_snapshot_without_canonical_graph_can_be_saved_without_rebuilding(store):
    doc = evidence()
    del doc["journey"]["visualization"]
    saved = store.save(doc)
    assert "visualization" not in store.get(saved["id"])["journey"]


@pytest.mark.parametrize("change", [
    lambda doc: doc.update(name=""),
    lambda doc: doc.update(name="x" * 101),
    lambda doc: doc.update(capturedAt=-1),
    lambda doc: doc.update(capturedAt=True),
    lambda doc: doc.update(capturedAt=float("nan")),
    lambda doc: doc.update(segments=[]),
    lambda doc: doc.update(warnings=[{}]),
    lambda doc: doc["segments"][0].update(service="wrong-service"),
    lambda doc: doc["segments"][0].update(traceId="wrong-trace"),
    lambda doc: doc["segments"][0].update(events=[None]),
    lambda doc: doc["segments"].append(doc["segments"][0]),
    lambda doc: doc["request"].update(traceId="wrong-trace"),
    lambda doc: doc["journey"].update(traceId="wrong-trace"),
    lambda doc: doc["journey"]["visualization"].update(traceId="wrong-trace"),
    lambda doc: doc["journey"]["visualization"]["nodes"][0].update(segmentId="wrong-segment"),
    lambda doc: doc["journey"]["visualization"]["edges"][0].update(to="unknown"),
    lambda doc: doc["journey"]["visualization"].update(roots=["unknown"]),
])
def test_rejects_invalid_or_conflicting_evidence_without_writing(store, change):
    doc = evidence()
    change(doc)
    with pytest.raises(snapshots.SnapshotError):
        store.save(doc)
    assert store.list()["count"] == 0


@pytest.mark.parametrize("value", ["../outside", "%2e%2e%2foutside", "/tmp/file", "", "x", str(uuid.uuid4()).upper()])
def test_ids_are_never_paths(store, value):
    for action in (lambda: store.get(value), lambda: store.rename(value, "name"), lambda: store.delete(value)):
        with pytest.raises(snapshots.SnapshotError):
            action()


def test_size_count_and_total_budgets_never_evict_existing_evidence(store, monkeypatch):
    first = store.save(evidence())
    monkeypatch.setattr(snapshots, "MAX_COUNT", 1)
    with pytest.raises(snapshots.SnapshotError, match="20 snapshots"):
        store.save(evidence())
    assert store.get(first["id"]) == first
    monkeypatch.setattr(snapshots, "MAX_COUNT", 20)
    monkeypatch.setattr(snapshots, "MAX_TOTAL_BYTES", store.list()["totalBytes"] + 100)
    with pytest.raises(snapshots.SnapshotError, match="20 MiB"):
        store.save(evidence())
    monkeypatch.setattr(snapshots, "MAX_BYTES", 100)
    with pytest.raises(snapshots.SnapshotError, match="5 MiB"):
        store.save(evidence())


def test_concurrent_store_objects_share_quota_lock(store, monkeypatch):
    monkeypatch.setattr(snapshots, "MAX_COUNT", 3)
    def save(_):
        sibling = snapshots.ViewerSnapshotStore(store.root.parent, NAMESPACE)
        try:
            return sibling.save(evidence())["id"]
        except snapshots.SnapshotError:
            return None
    with ThreadPoolExecutor(max_workers=8) as workers:
        ids = list(workers.map(save, range(12)))
    assert len([value for value in ids if value]) == 3
    assert store.list()["count"] == 3


def test_failed_atomic_publish_preserves_existing_files_and_cleans_own_temporary(store, monkeypatch):
    first = store.save(evidence())
    def fail(*args, **kwargs):
        raise OSError("fake disk failure with private path")
    monkeypatch.setattr(os, "link", fail)
    with pytest.raises(OSError):
        store.save(evidence())
    assert store.get(first["id"]) == first
    assert store.list()["count"] == 1
    assert not list(store.root.glob(".pending-*"))


def test_interrupted_files_count_toward_quota_without_silent_cleanup(store, monkeypatch):
    store.save(evidence())
    pending = store.root / (".pending-" + str(uuid.uuid4()))
    pending.write_bytes(b"interrupted")
    pending.chmod(0o600)
    monkeypatch.setattr(snapshots, "MAX_COUNT", 2)
    with pytest.raises(snapshots.SnapshotError, match="20 snapshots"):
        store.save(evidence())
    assert store.list()["warnings"]
    assert pending.read_bytes() == b"interrupted"


def test_corrupt_file_returns_error_never_live_fallback(store):
    saved = store.save(evidence())
    path = store.root / (saved["id"] + ".json")
    path.write_bytes(b'{"credential":"do-not-echo",')
    with pytest.raises(snapshots.SnapshotError, match="corrupt") as exc:
        store.get(saved["id"])
    assert "do-not-echo" not in str(exc.value)


def test_rejects_symlink_files_namespace_and_ancestor(store, tmp_path):
    saved = store.save(evidence())
    original = store.root / (saved["id"] + ".json")
    alias = store.root / (str(uuid.uuid4()) + ".json")
    alias.symlink_to(original)
    with pytest.raises(OSError):
        store.get(alias.stem)
    linked_root = tmp_path / "linked-root"
    linked_root.symlink_to(store.root.parent, target_is_directory=True)
    with pytest.raises(OSError):
        snapshots.ViewerSnapshotStore(linked_root, NAMESPACE).save(evidence())
    other_namespace = store.root.parent / ("b" * 64)
    other_namespace.symlink_to(store.root, target_is_directory=True)
    with pytest.raises(OSError):
        snapshots.ViewerSnapshotStore(store.root.parent, "b" * 64).list()
    assert store.get(saved["id"]) == saved


def test_rejects_outside_hardlinks_and_public_permissions(store, tmp_path):
    saved = store.save(evidence())
    path = store.root / (saved["id"] + ".json")
    outside = tmp_path / "outside"
    os.link(path, outside)
    with pytest.raises(ValueError, match="private"):
        store.get(saved["id"])
    outside.unlink()
    path.chmod(0o644)
    with pytest.raises(ValueError, match="private"):
        store.get(saved["id"])


class Body(io.BytesIO):
    def __init__(self, value):
        super().__init__(value)
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        return super().read(size)


def api(store, method="GET", path="/api/snapshots", doc=None, raw=None, headers=None):
    raw = raw if raw is not None else json.dumps(doc).encode() if doc is not None else b""
    handler = server._Handler.__new__(server._Handler)
    handler.path = path
    handler.snapshots = store
    handler.server = SimpleNamespace(server_address=("127.0.0.1", 7781))
    handler.headers = Message()
    standard = [("Host", "127.0.0.1:7781")]
    if method != "GET":
        standard += [("Origin", "http://127.0.0.1:7781"), ("Content-Length", str(len(raw)))]
    if method == "POST":
        standard += [("Content-Type", "application/json")]
    for name, value in headers if headers is not None else standard:
        handler.headers[name] = value
    handler.connection = SimpleNamespace(gettimeout=lambda: None, settimeout=lambda _: None)
    handler.rfile = Body(raw)
    response = []
    handler._send_json = lambda doc, status: response.append((status, doc))
    handler._snapshots(method)
    assert len(response) == 1
    return *response[0], handler


def test_api_create_list_read_rename_delete_and_missing_are_offline(store, monkeypatch):
    monkeypatch.setattr(server.urllib.request, "urlopen", lambda *a, **k: pytest.fail("upstream read"))
    status, saved, _ = api(store, "POST", doc={"snapshot": evidence()})
    assert status == 201
    path = "/api/snapshots/" + saved["id"]
    assert api(store)[1]["snapshots"][0]["id"] == saved["id"]
    assert api(store, path=path)[:2] == (200, saved)
    assert api(store, "POST", path + "/rename", {"name": "new name"})[1]["name"] == "new name"
    assert api(store, "DELETE", path)[1]["deleted"] is True
    status, doc, _ = api(store, path=path)
    assert status == 404
    assert "no live data" in doc["error"]


@pytest.mark.parametrize("headers", [
    [], [("Host", "attacker.invalid:7781")], [("Host", "localhost:7782")],
    [("Host", "127.0.0.1:7781"), ("Host", "127.0.0.1:7781")],
    [("Host", "127.0.0.1:7781"), ("Origin", "https://attacker.invalid")],
    [("Host", "127.0.0.1:7781"), ("Origin", "http://127.0.0.1:7781"), ("Sec-Fetch-Site", "cross-site")],
])
def test_local_host_and_same_origin_protect_read_and_mutation(store, headers):
    for method in ("GET", "POST", "DELETE"):
        status, _, handler = api(store, method, headers=headers)
        assert status == 403
        assert not handler.rfile.read_sizes


def test_mutation_requires_origin_but_direct_local_read_does_not(store):
    headers = [("Host", "127.0.0.1:7781")]
    assert api(store, headers=headers)[0] == 200
    assert api(store, "POST", headers=headers)[0] == 403


@pytest.mark.parametrize("extra", [
    [("Content-Length", "2"), ("Content-Length", "2"), ("Content-Type", "application/json")],
    [("Content-Length", "2"), ("Content-Type", "text/plain")],
    [("Content-Length", "2"), ("Content-Type", "application/json"), ("Transfer-Encoding", "chunked")],
    [("Content-Length", "2"), ("Content-Type", "application/json"), ("Content-Encoding", "gzip")],
    [("Content-Length", "2"), ("Content-Type", "application/json; charset=latin-1")],
    [("Content-Length", "-2"), ("Content-Type", "application/json")],
    [("Content-Length", "٢"), ("Content-Type", "application/json")],
])
def test_mutation_rejects_ambiguous_framing_before_read(store, extra):
    status, _, handler = api(store, "POST", raw=b"{}", headers=[
        ("Host", "127.0.0.1:7781"), ("Origin", "http://127.0.0.1:7781"), *extra])
    assert status == 400
    assert not handler.rfile.read_sizes
    assert handler.close_connection is True


@pytest.mark.parametrize("raw", [b"", b"invalid-private-token", b"\xff", b'{"snapshot":{},"snapshot":{}}',
                                b'{"snapshot":NaN}', b'[' * 2000 + b']' * 2000])
def test_mutation_rejects_malformed_json_without_echoing_values(store, raw):
    status, doc, _ = api(store, "POST", raw=raw)
    assert status == 400
    assert "invalid-private-token" not in doc["error"]


def test_oversized_body_rejected_without_read_and_storage_errors_do_not_leak_paths(store, monkeypatch):
    status, _, handler = api(store, "POST", headers=[
        ("Host", "127.0.0.1:7781"), ("Origin", "http://127.0.0.1:7781"),
        ("Content-Length", "9" * 5000), ("Content-Type", "application/json")])
    assert status == 413
    assert not handler.rfile.read_sizes
    def fail():
        raise OSError("private filesystem path or secret")
    monkeypatch.setattr(store, "list", fail)
    status, doc, _ = api(store)
    assert status == 503
    assert "secret" not in doc["error"]


def test_real_http_restarted_viewer_reads_saved_snapshot_without_upstream_io(tmp_path, monkeypatch):
    def no_io(*args, **kwargs):
        pytest.fail("A saved snapshot must not fetch upstream or source files")
    monkeypatch.setattr(server.urllib.request, "urlopen", no_io)
    monkeypatch.setattr(server.source, "source_line", no_io)
    monkeypatch.setattr(server, "build_visualization", no_io)
    root = tmp_path / "viewer-files"
    def start():
        viewer = server.make_server(0, "http://upstream.invalid:7779", "/original/source", snapshot_dir=root)
        thread = threading.Thread(target=viewer.serve_forever, daemon=True)
        thread.start()
        return viewer, thread
    def request(viewer, method, path, body=None, host=None, origin=None):
        port = viewer.server_address[1]
        host = host or f"127.0.0.1:{port}"
        headers = {"Host": host}
        if body is not None:
            headers.update({"Content-Type": "application/json", "Origin": origin or "http://" + host})
            body = json.dumps(body)
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            assert response.getheader("Cache-Control") == "no-store"
            assert response.getheader("X-Content-Type-Options") == "nosniff"
            assert response.getheader("Access-Control-Allow-Origin") is None
            return response.status, json.load(response)
        finally:
            connection.close()
    viewer, thread = start()
    try:
        status, saved = request(viewer, "POST", "/api/snapshots", {"snapshot": evidence()})
        assert status == 201
    finally:
        viewer.shutdown()
        viewer.server_close()
        thread.join()
    # New server, different port/browser connection, same configuration/root.
    viewer, thread = start()
    try:
        assert request(viewer, "GET", "/api/snapshots/" + saved["id"]) == (200, saved)
        assert request(viewer, "GET", "/api/snapshots")[1]["count"] == 1
        assert request(viewer, "GET", "/api/snapshots", host="attacker.invalid")[0] == 403
        assert request(viewer, "POST", "/api/snapshots", {"snapshot": evidence()}, origin="https://attacker.invalid")[0] == 403
    finally:
        viewer.shutdown()
        viewer.server_close()
        thread.join()


def test_serve_cli_forwards_explicit_snapshot_directory(monkeypatch):
    from tracelens_cli.cli import main
    calls = []
    monkeypatch.setattr(server, "serve", lambda *args, **kwargs: calls.append((args, kwargs)) or 0)
    assert main(["--url", "http://localhost:7778", "serve", "--port", "7781", "--snapshot-dir", "/private/tmp/viewer-pins"]) == 0
    assert calls == [((7781, "http://localhost:7778", None, None, "/private/tmp/viewer-pins"), {"capture_dir": None})]
