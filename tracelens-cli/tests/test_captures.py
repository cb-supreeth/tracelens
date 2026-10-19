"""Explicit local captures: privacy, read-only fetches, durability and uncertainty."""

import copy
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import stat
import threading
from types import SimpleNamespace
import uuid

import pytest

from tracelens_cli import captures
from tracelens_cli.captures import CaptureStore, build_capture, compare_captures
from tracelens_cli.client import DebugApiError, TraceLensClient


TRACE = "a" * 32
SEGMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
CHILD = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
CLASS = "com.example.FeatureService"


def event(seq, kind, method=None, **values):
    result = {"seq": seq, "type": kind, "className": CLASS, "thread": "private-user-thread",
              "nanoTime": seq * 1_000_000, "attributes": {}}
    if method:
        result["methodName"] = method
    result.update(values)
    return result


def segment(**values):
    events = [event(1, "METHOD_ENTER", "retrieve"), event(2, "LINE", line=42),
              event(3, "METHOD_ENTER", "lookup"), event(4, "METHOD_EXIT", "lookup"),
              event(5, "METHOD_EXIT", "retrieve")]
    return {"traceId": TRACE, "segmentId": SEGMENT, "serviceInstanceId": "instance-1",
            "spanId": "b" * 16, "parentSpanId": None, "status": "COMPLETED",
            "httpMethod": "GET", "path": "/features?token=secret-query", "httpStatus": 200,
            "durationMs": 12, "user": "secret-user", "contextParams": {"auth": "secret-context"},
            "events": events, "eventCount": len(events), "httpCalls": [], **values}


class Client:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []
        self.summary_error = None
        self.export_error = None
        self.export_transform = lambda row: row

    def find_by_trace_id(self, trace_id):
        self.calls.append(("find", trace_id))
        if self.summary_error:
            raise self.summary_error
        return {"traces": [{k: v for k, v in row.items() if k != "events"} for row in self.rows]}

    def traces(self):
        self.calls.append(("traces",))
        return {"traces": self.rows}

    def export(self, segment_id, evict=None):
        self.calls.append(("export", segment_id, evict))
        if self.export_error:
            raise self.export_error
        return self.export_transform(copy.deepcopy(next(row for row in self.rows if row["segmentId"] == segment_id)))


def discovery(*clients):
    return SimpleNamespace(services={name: object() for name, _ in clients}, clients=dict(clients))


def capture(row=None, **values):
    return build_capture(discovery(("app", Client([row or segment()]))), TRACE, "baseline", "build-1", **values)


def test_projection_counts_lines_nesting_and_excludes_runtime_private_data():
    row = segment()
    row["events"].insert(4, event(5, "EXCEPTION", "retrieve", attributes={
        "exceptionType": "java.lang.IllegalArgumentException", "message": "secret-exception"}))
    row["events"].pop()
    row["eventCount"] = len(row["events"])
    row["source"] = "secret-source"
    result = capture(row)
    payload = json.dumps(result)
    for secret in ("secret-user", "secret-context", "secret-query", "secret-exception", "secret-source", "private-user-thread"):
        assert secret not in payload
    data = result["segments"][0]
    assert data["request"]["path"] == "/features"
    assert data["methods"] == [{"className": CLASS, "methodName": "lookup", "count": 1},
                                {"className": CLASS, "methodName": "retrieve", "count": 1}]
    assert data["lines"] == [{"className": CLASS, "line": 42, "count": 1}]
    assert data["exceptions"] == [{"exceptionType": "java.lang.IllegalArgumentException", "count": 1}]
    assert data["nesting"] == [{"parentClass": CLASS, "parentMethod": "retrieve",
                                "className": CLASS, "methodName": "lookup", "count": 1}]
    captures._validate_capture(result)


@pytest.mark.parametrize("trace,label,revision", [("../escape", "ok", None), ("x", "", None),
    ("x", "x" * 81, None), ("x", "\nname", None), ("x", " ", None), ("x", "ok", "bad revision"),
    ("x", "ok", ""), (None, "ok", None), ({}, "ok", None)])
def test_invalid_input_rejected_before_discovery(trace, label, revision):
    with pytest.raises(ValueError):
        build_capture(object(), trace, label, revision)


def test_exact_service_instance_and_read_only_exports():
    first, second = Client([segment()]), Client([segment(segmentId=CHILD, parentSpanId="c" * 16)])
    before = copy.deepcopy(first.rows)
    result = build_capture(discovery(("app", first), ("ems", second)), TRACE, "before")
    assert len(result["segments"]) == 2
    assert first.rows == before
    assert all(call[2] is False for client in (first, second) for call in client.calls if call[0] == "export")
    second.export_transform = lambda row: {**row, "serviceInstanceId": "new-instance"}
    partial = build_capture(discovery(("app", first), ("ems", second)), TRACE, "before")
    assert partial["partial"]
    assert "ems:segment_unavailable_or_invalid" in partial["warnings"]
    assert len(partial["segments"]) == 1


def test_trace_or_segment_identity_changes_are_not_captured():
    for key, value in (("segmentId", CHILD), ("traceId", "wrong"), ("serviceInstanceId", None)):
        client = Client([segment()])
        client.export_transform = lambda row: {**row, key: value}
        with pytest.raises(ValueError, match="No retained"):
            build_capture(discovery(("app", client)), TRACE, "before")


def test_fallback_missing_trace_or_missing_instance():
    client = Client([segment()])
    client.summary_error = DebugApiError("private error body", 404)
    result = build_capture(discovery(("app", client)), TRACE, "before")
    assert ("traces",) in client.calls and "private error" not in json.dumps(result)
    with pytest.raises(ValueError, match="No retained"):
        build_capture(discovery(("app", Client([segment(serviceInstanceId=None)]))), TRACE, "before")


def test_dependencies_only_keep_sanitized_boundary_evidence():
    row = segment()
    row["events"] += [event(6, "EXTERNAL_CALL", attributes={"boundaryKind": "messaging", "system": "kafka",
        "direction": "publish", "destination": "topic.features", "operation": "send", "status": "SUBMITTED",
        "evidenceKind": "client-boundary", "payload": "secret-payload", "errorType": "java.io.IOException"}),
        event(7, "DB_ROUTE", attributes={"route": "https://name:password@db.test/route?token=secret"})]
    row["eventCount"] = len(row["events"])
    row["httpCalls"] = [{"peer": "https://name:password@ems.test?token=secret", "httpMethod": "GET",
                         "path": "/features?token=secret", "httpStatus": 500, "status": "ERROR",
                         "errorType": "java.net.SocketException", "message": "secret-message"}]
    result = capture(row)
    payload = json.dumps(result)
    for value in ("password", "token=", "secret-payload", "secret-message"):
        assert value not in payload
    assert {d["boundaryKind"] for d in result["segments"][0]["dependencies"]} == {"messaging", "database-route", "http"}
    captures._validate_capture(result)


def test_active_truncated_gapped_and_missing_root_have_visible_uncertainty():
    row = segment(status="ACTIVE", truncated=True, parentSpanId="c" * 16)
    row["events"][2]["seq"] = 100
    result = capture(row)
    assert result["partial"]
    assert "root_missing_or_ambiguous" in result["warnings"]
    warnings = result["segments"][0]["warnings"]
    assert {"active_snapshot", "agent_segment_truncated", "sequence_missing_gap_or_reordering"} <= set(warnings)
    assert any("silent global event drops" in text for text in result["limitations"])


def test_queue_parent_segment_does_not_become_a_false_root():
    result = capture(segment(parentSegmentId=CHILD))
    assert result["segments"][0]["parentSegmentId"] == CHILD
    assert result["partial"] and "root_missing_or_ambiguous" in result["warnings"]
    captures._validate_capture(result)


def test_huge_duration_integer_is_omitted_without_float_overflow():
    result = capture(segment(durationMs=10 ** 400))
    assert result["segments"][0]["request"]["durationMs"] is None
    captures._validate_capture(result)


def test_segment_and_event_limits_are_visible(monkeypatch):
    monkeypatch.setattr(captures, "MAX_SEGMENTS", 1)
    monkeypatch.setattr(captures, "MAX_EVENTS", 2)
    client = Client([segment(), segment(segmentId=CHILD)])
    result = build_capture(discovery(("app", client)), TRACE, "limited")
    assert result["partial"] and "segment_limit" in result["warnings"]
    assert result["segments"][0]["eventsProcessed"] == 2
    assert "event_processing_limit" in result["segments"][0]["warnings"]
    assert len([c for c in client.calls if c[0] == "export"]) == 1


def test_real_transport_has_bounded_reads_explicit_evict_and_no_error_body(monkeypatch):
    calls = []
    documents = [{"traces": [segment()]}, segment()]
    class Response(io.BytesIO):
        def read(self, size=-1):
            assert 0 < size <= captures.MAX_API_BYTES + 1
            return super().read(size)
    class Opener:
        def open(self, request, timeout):
            calls.append((request.full_url, request.method, timeout))
            return Response(json.dumps(documents.pop(0)).encode())
    monkeypatch.setattr(captures.urllib.request, "build_opener", lambda *args: Opener())
    client = TraceLensClient("http://configured.test", timeout=10)
    result = build_capture(discovery(("app", client)), TRACE, "bounded")
    assert result["segments"]
    assert calls[-1] == (f"http://configured.test/tracelens/export/{SEGMENT}?evict=false", "GET", 5.0)
    monkeypatch.setattr(captures, "MAX_API_BYTES", 3)
    documents[:] = [{"traces": [segment()]}]
    with pytest.raises(ValueError, match="No retained"):
        build_capture(discovery(("app", client)), TRACE, "too-large")


def test_store_round_trip_private_atomic_non_overwriting_and_no_read_creation(tmp_path):
    root = tmp_path / "captures"
    store = CaptureStore(root)
    assert store.list()["captures"] == [] and not root.exists()
    with pytest.raises(ValueError, match="not found"):
        store.get(str(uuid.uuid4()))
    assert not root.exists()
    result = capture()
    meta = store.save(result)
    assert meta["captureId"] == result["captureId"] and meta["segmentCount"] == 1
    assert store.get(result["captureId"]) == result
    assert store.list()["count"] == 1
    assert stat.S_IMODE(root.stat().st_mode) == 0o700
    filename = root / (result["captureId"] + ".json")
    assert stat.S_IMODE(filename.stat().st_mode) == 0o600
    with pytest.raises(ValueError, match="already exists"):
        store.save(result)
    assert store.get(result["captureId"]) == result
    assert not list(root.glob(".pending-*"))


def test_store_labels_can_repeat_and_compare_works_offline_after_restart(tmp_path):
    store = CaptureStore(tmp_path / "captures")
    first, second = capture(), capture()
    assert first["label"] == second["label"]
    for result in (first, second):
        store.save(result)
    reopened = CaptureStore(tmp_path / "captures")
    comparison = compare_captures(reopened.get(first["captureId"]), reopened.get(second["captureId"]))
    assert comparison["methods"]["returned"] == 0
    assert reopened.list()["count"] == 2


@pytest.mark.parametrize("capture_id", ["../elsewhere", "x.json", "A" * 32, "/tmp/file", None, {}])
def test_store_rejects_path_traversal_before_io(tmp_path, capture_id):
    with pytest.raises(ValueError, match="UUID"):
        CaptureStore(tmp_path / "not-created").get(capture_id)
    assert not (tmp_path / "not-created").exists()


def test_store_rejects_symlink_root_files_parents_and_hardlinks(tmp_path):
    real = tmp_path / "real"
    real.mkdir(mode=0o700)
    link = tmp_path / "link"
    link.symlink_to(real, target_is_directory=True)
    for root in (link, link / "nested"):
        with pytest.raises(OSError):
            CaptureStore(root).save(capture())
    store = CaptureStore(real)
    result = capture()
    outside = tmp_path / "outside"
    outside.write_text("not a capture")
    filename = real / (result["captureId"] + ".json")
    filename.symlink_to(outside)
    with pytest.raises(OSError):
        store.get(result["captureId"])
    filename.unlink()
    store.save(result)
    os.link(filename, tmp_path / "hardlink")
    with pytest.raises(ValueError, match="private"):
        store.get(result["captureId"])
    assert outside.read_text() == "not a capture"


def test_store_rejects_public_directory_or_file(tmp_path):
    root = tmp_path / "public"
    root.mkdir(mode=0o755)
    with pytest.raises(ValueError, match="0700"):
        CaptureStore(root).save(capture())
    root.chmod(0o700)
    store = CaptureStore(root)
    result = capture()
    store.save(result)
    (root / (result["captureId"] + ".json")).chmod(0o644)
    with pytest.raises(ValueError, match="private"):
        store.get(result["captureId"])


def test_fifo_lock_rejected_without_blocking_readers(tmp_path):
    root = tmp_path / "captures"
    root.mkdir(mode=0o700)
    os.mkfifo(root / ".lock", mode=0o600)
    store = CaptureStore(root)
    with pytest.raises(ValueError, match="private"):
        store.list()
    with pytest.raises(ValueError, match="private"):
        store.get(str(uuid.uuid4()))


def test_store_enforces_capture_and_total_quotas_without_deleting(tmp_path, monkeypatch):
    store = CaptureStore(tmp_path / "captures")
    first = capture()
    store.save(first)
    monkeypatch.setattr(captures, "MAX_CAPTURES", 1)
    with pytest.raises(ValueError, match="limit reached"):
        store.save(capture())
    assert store.get(first["captureId"]) == first
    monkeypatch.setattr(captures, "MAX_CAPTURES", 20)
    monkeypatch.setattr(captures, "MAX_STORE_BYTES", 1)
    with pytest.raises(ValueError, match="storage limit"):
        store.save(capture())
    assert len(list((tmp_path / "captures").glob("*.json"))) == 1


def test_interrupted_atomic_publication_remains_readable_and_consumes_quota(tmp_path, monkeypatch):
    root = tmp_path / "captures"
    store = CaptureStore(root)
    result = capture()
    store.save(result)
    filename = root / (result["captureId"] + ".json")
    pending = root / (".pending-" + str(uuid.uuid4()))
    os.link(filename, pending)  # Exact state after publish, before pending unlink.
    assert filename.stat().st_nlink == 2
    assert store.get(result["captureId"]) == result
    listing = store.list()
    assert listing["count"] == 1 and listing["pendingFiles"] == 1
    assert listing["totalBytes"] == filename.stat().st_size * 2
    assert listing["warnings"] and pending.exists()  # Reads never clean up.
    store.save(capture())  # Crash must not make the entire store unusable.
    monkeypatch.setattr(captures, "MAX_STORE_BYTES", listing["totalBytes"])
    with pytest.raises(ValueError, match="storage limit"):
        store.save(capture())


def test_unpublished_pending_files_count_toward_quota(tmp_path, monkeypatch):
    root = tmp_path / "captures"
    root.mkdir(mode=0o700)
    pending = root / (".pending-" + str(uuid.uuid4()))
    pending.write_bytes(b"unfinished")
    pending.chmod(0o600)
    store = CaptureStore(root)
    assert store.list()["pendingBytes"] == len(b"unfinished")
    monkeypatch.setattr(captures, "MAX_CAPTURES", 1)
    with pytest.raises(ValueError, match="limit reached"):
        store.save(capture())
    assert pending.read_bytes() == b"unfinished"


def test_concurrent_saves_do_not_exceed_quota(tmp_path, monkeypatch):
    monkeypatch.setattr(captures, "MAX_CAPTURES", 3)
    # Force all six writers into the first-time lock creation together. On
    # macOS, nonexclusive O_CREAT|O_NOFOLLOW races otherwise intermittently
    # return ENOENT even though the lock exists and the directory fd is valid.
    ready = threading.Barrier(6)
    original_open = os.open
    def simultaneous_create(path, flags, *args, **kwargs):
        if path == ".lock" and flags & os.O_CREAT:
            ready.wait(timeout=5)
        return original_open(path, flags, *args, **kwargs)
    monkeypatch.setattr(captures.os, "open", simultaneous_create)
    store = CaptureStore(tmp_path / "captures")
    values = [capture() for _ in range(6)]
    def save(value):
        try:
            return store.save(value)
        except ValueError:
            return None
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(save, values))
    assert sum(result is not None for result in results) == 3
    assert store.list()["count"] == 3


def test_store_rejects_raw_unknown_fields_malformed_and_modified_identity(tmp_path):
    store = CaptureStore(tmp_path / "captures")
    result = capture()
    bad = copy.deepcopy(result)
    bad["source"] = "private source"
    with pytest.raises(ValueError, match="schema"):
        store.save(bad)
    assert not (tmp_path / "captures").exists()
    store.save(result)
    name = tmp_path / "captures" / (result["captureId"] + ".json")
    bad = copy.deepcopy(result)
    bad["segments"][0]["request"]["user"] = "private user"
    name.write_text(json.dumps(bad))
    with pytest.raises(ValueError, match="invalid"):
        store.get(result["captureId"])
    bad = copy.deepcopy(result)
    bad["captureId"] = str(uuid.uuid4())
    name.write_text(json.dumps(bad))
    with pytest.raises(ValueError, match="identity"):
        store.get(result["captureId"])


def test_compare_reports_observed_deltas_not_removed_code_or_benchmark():
    before = capture()
    changed = segment()
    changed["events"][1]["line"] = 43
    changed["events"][2]["methodName"] = "fetch"
    changed["events"][3]["methodName"] = "fetch"
    changed["httpStatus"] = 500
    changed["durationMs"] = 2
    after = capture(changed)
    after["revision"] = "build-2"
    result = compare_captures(before, after)
    assert result["methods"]["added"][0]["methodName"] == "fetch"
    assert result["methods"]["noLongerObserved"][0]["methodName"] == "lookup"
    assert result["lines"]["added"][0]["line"] == 43
    assert result["lines"]["noLongerObserved"][0]["line"] == 42
    assert result["outcomes"]["added"][0]["httpStatus"] == 500
    assert "removed" not in result["methods"] and "speedup" not in result
    assert "scope_and_inputs_not_verified" in result["warnings"]
    assert any("line shifts are not code deletion" in line for line in result["limitations"])
    assert any("not a performance benchmark" in line for line in result["limitations"])


def test_compare_counts_repeated_methods_and_does_not_compare_instance_ids():
    before, after = capture(), capture(segment(serviceInstanceId="restarted-instance"))
    after["segments"][0]["methods"][0]["count"] += 2
    result = compare_captures(before, after)
    assert result["methods"]["totals"] == {"added": 0, "noLongerObserved": 0, "countChanged": 1}
    changed = result["methods"]["countChanged"][0]
    assert changed["beforeCount"] == 1 and changed["afterCount"] == 3


def test_comparison_output_is_bounded_and_omissions_counted():
    before, after = capture(), capture()
    first, second = before["segments"][0], after["segments"][0]
    for data, prefix in ((first, "Before"), (second, "After")):
        data["methods"] = [{"className": "com.example." + prefix + "X" * 220,
                            "methodName": "method" + str(i), "count": 1} for i in range(2000)]
        data["lines"] = [{"className": "com.example." + prefix + "X" * 220, "line": i + 1, "count": 1} for i in range(10000)]
    result = compare_captures(before, after)
    assert len(captures._bytes(result)) <= captures.MAX_COMPARISON_BYTES
    assert result["limited"]
    assert result["methods"]["totals"]["added"] == 2000
    assert result["lines"]["totals"]["added"] == 10000
    assert result["lines"]["omitted"] == sum(result["lines"]["totals"].values()) - result["lines"]["returned"]


def test_comparison_warning_metadata_is_bounded_at_maximum_segment_and_label_sizes():
    before, after = capture(), capture()
    codes = ["warning" + "x" * 150 + str(index).zfill(3) for index in range(256)]
    for model in (before, after):
        model["label"] = "界" * 80
        model["warnings"] = codes
        model["partial"] = True
        template = model["segments"][0]
        model["segments"] = [copy.deepcopy(template) for _ in range(captures.MAX_SEGMENTS)]
        for row in model["segments"]:
            row["segmentId"] = str(uuid.uuid4())
            row["service"] = "s" * 64
            row["request"]["path"] = "/" + "p" * 511
            row["warnings"] = codes
            row["partial"] = True
    result = compare_captures(before, after)
    assert len(captures._bytes(result)) <= captures.MAX_COMPARISON_BYTES
    assert result["limited"]
    assert result["evidenceWarnings"]["before"]["warningsOmitted"] == 248
    assert result["evidenceWarnings"]["after"]["segments"][0]["warningsOmitted"] == 252


def test_partial_and_different_scenario_comparisons_warn():
    before, after = capture(), capture(segment(path="/different", status="ACTIVE"))
    result = compare_captures(before, after)
    assert result["partial"]
    assert {"request_paths_or_methods_differ", "partial_capture_evidence"} <= set(result["warnings"])
    assert result["evidenceWarnings"]["before"]["segments"] == []
    evidence = result["evidenceWarnings"]["after"]["segments"][0]
    assert evidence["service"] == "app" and evidence["segmentId"] == SEGMENT
    assert "active_snapshot" in evidence["warnings"]
