"""Viewer-to-CLI projection and cross-file search never need a running JVM."""

import copy
import json
import stat
import uuid
from urllib import request

import pytest

from tracelens_cli import capture_tools, captures
from tracelens_cli.viewer_snapshots import ViewerSnapshotStore


TRACE = "a" * 32
SEGMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
CLASS = "example.FeatureService"


def evidence(method="retrieve", *, path="/features", status=200):
    row = {"service": "app", "traceId": TRACE, "serviceInstanceId": "instance-1",
           "segmentId": SEGMENT, "httpMethod": "GET", "path": path + "?token=SECRET_QUERY",
           "httpStatus": status, "status": "COMPLETED", "durationMs": 12,
           "user": "SECRET_USER", "contextParams": {"secret": "SECRET_CONTEXT"},
           "source": "SECRET_SOURCE", "httpCalls": [{
               "peer": "https://user:SECRET_PASSWORD@ems.example?token=SECRET_TOKEN",
               "httpMethod": "GET", "path": "/data?token=SECRET_HTTP", "status": "ERROR",
               "httpStatus": 500, "message": "SECRET_HTTP_MESSAGE"}],
           "events": [
               {"seq": 1, "type": "METHOD_ENTER", "className": CLASS, "methodName": method,
                "nanoTime": 1, "thread": "SECRET_THREAD", "sourceLine": "SECRET_SOURCE_LINE"},
               {"seq": 2, "type": "EXCEPTION", "className": CLASS, "methodName": method,
                "nanoTime": 2, "thread": "SECRET_THREAD",
                "attributes": {"exceptionType": "example.LookupException", "message": "SECRET_EXCEPTION"}},
               {"seq": 3, "type": "METHOD_EXIT", "className": CLASS, "methodName": method,
                "nanoTime": 3, "thread": "SECRET_THREAD"},
           ], "eventCount": 3}
    return {"name": "Before", "traceId": TRACE, "capturedAt": 1234567,
            "partial": False, "warnings": [],
            "request": {"traceId": TRACE, "label": "GET /features", "services": ["app"]},
            "journey": {"traceId": TRACE, "segments": [{key: row[key] for key in
                        ("service", "serviceInstanceId", "segmentId", "traceId")}]},
            "segments": [row]}


@pytest.fixture
def stores(tmp_path, monkeypatch):
    def no_network(*_args, **_kwargs):
        pytest.fail("Saved evidence must never contact live services")
    monkeypatch.setattr(request, "urlopen", no_network)
    monkeypatch.setattr(request, "build_opener", no_network)
    return ViewerSnapshotStore(tmp_path / "viewer", "a" * 64), captures.CaptureStore(tmp_path / "cli")


def test_projection_reuses_exact_allowlist_and_preserves_original_time(stores):
    viewer, _ = stores
    snapshot = viewer.save(evidence())
    original = copy.deepcopy(snapshot)
    projected = capture_tools.project_viewer_snapshot(snapshot)
    assert snapshot == original
    assert projected["captureId"] == snapshot["id"]
    assert projected["createdAt"] == "1970-01-01T00:20:34.567000+00:00"
    assert projected["segments"][0] == captures._project_segment("app", snapshot["segments"][0], captures.MAX_EVENTS)
    assert "SECRET" not in json.dumps(projected)
    assert projected["segments"][0]["request"]["path"] == "/features"
    assert projected["segments"][0]["methods"] == [{"className": CLASS, "methodName": "retrieve", "count": 1}]
    captures._validate_capture(projected)


def test_explicit_conversion_is_distinct_private_durable_and_never_mutates_pin(stores):
    viewer, cli = stores
    snapshot = viewer.save(evidence())
    assert not cli.root.exists()
    result = capture_tools.export_to_capture_store(viewer, cli, snapshot["id"], "Candidate", "build-2")
    assert result["saved"] is True and result["sourceSnapshotId"] == snapshot["id"]
    capture_id = result["capture"]["captureId"]
    assert capture_id != snapshot["id"]
    saved = captures.CaptureStore(cli.root).get(capture_id)
    assert saved["label"] == "Candidate" and saved["revision"] == "build-2"
    assert viewer.get(snapshot["id"]) == snapshot
    assert "SECRET" not in json.dumps(saved)
    assert stat.S_IMODE((cli.root / (capture_id + ".json")).stat().st_mode) == 0o600
    again = capture_tools.export_to_capture_store(viewer, cli, snapshot["id"])
    assert again["capture"]["captureId"] != capture_id
    assert cli.list()["count"] == 2


def test_comparison_uses_selected_files_without_publishing_and_says_no_longer_observed(stores):
    viewer, cli = stores
    first = viewer.save(evidence())
    second = viewer.save({**evidence("lookup", path="/other", status=500), "name": "After"})
    result = capture_tools.compare_saved_snapshots(viewer, first["id"], second["id"])
    assert result["methods"]["added"][0]["methodName"] == "lookup"
    assert result["methods"]["noLongerObserved"][0]["methodName"] == "retrieve"
    assert result["outcomes"]["totals"]["added"] == 1
    assert "request_paths_or_methods_differ" in result["warnings"]
    assert result["sourceSnapshots"]["before"]["snapshotId"] == first["id"]
    assert "SECRET" not in json.dumps(result)
    assert not cli.root.exists()
    assert viewer.list()["count"] == 2


def test_snapshot_uncertainty_is_retained_without_raw_warning_messages(stores):
    viewer, _ = stores
    raw = evidence()
    raw.update(partial=True, warnings=["SECRET_EXCEPTION from upstream"])
    raw["journey"]["segments"].append({
        "service": "missing", "serviceInstanceId": "instance", "segmentId": str(uuid.uuid4())})
    projected = capture_tools.project_viewer_snapshot(viewer.save(raw))
    assert projected["partial"]
    assert {"viewer_snapshot_partial", "viewer_snapshot_warnings_present", "viewer_snapshot_missing_exports"} <= set(projected["warnings"])
    assert "SECRET" not in json.dumps(projected)


@pytest.mark.parametrize("value", ["../../outside", "/tmp/raw.json", "Upper-ID", None, {}])
def test_comparison_and_export_do_not_accept_paths_or_substitute_missing_files(stores, value):
    viewer, cli = stores
    first = viewer.save(evidence())
    with pytest.raises(ValueError):
        capture_tools.compare_saved_snapshots(viewer, first["id"], value)
    with pytest.raises(ValueError):
        capture_tools.export_to_capture_store(viewer, cli, value)
    assert not cli.root.exists()


@pytest.mark.parametrize("field,value", [("service", "service/invalid"), ("serviceInstanceId", ""),
                                        ("segmentId", "legacy-segment")])
def test_unsupported_identity_is_not_invented_or_silently_exported(stores, field, value):
    viewer, cli = stores
    raw = evidence()
    raw["segments"][0][field] = raw["journey"]["segments"][0][field] = value
    saved = viewer.save(raw)
    with pytest.raises(ValueError, match="identities are unsupported"):
        capture_tools.export_to_capture_store(viewer, cli, saved["id"])
    assert not cli.root.exists()


def test_input_validation_precedes_store_access(stores):
    class NoRead:
        def get(self, _):
            pytest.fail("Bad label/revision must be rejected before reading")
    for label, revision in [("", None), ("x" * 81, None), ("ok", "../bad revision")]:
        with pytest.raises(ValueError):
            capture_tools.export_to_capture_store(NoRead(), stores[1], str(uuid.uuid4()), label, revision)


def test_search_finds_both_formats_offline_with_safe_identity_and_duration(stores):
    viewer, cli = stores
    pin = viewer.save(evidence())
    saved = capture_tools.export_to_capture_store(viewer, cli, pin["id"])
    result = capture_tools.search_saved_evidence(viewer, cli, "FeatureService", category="method")
    assert result["returned"] == result["matched"] == 2
    assert result["recordsScanned"] == 2
    assert {row["storage"] for row in result["results"]} == {"viewer", "capture"}
    assert result["results"][0]["snapshotId"] == pin["id"]
    assert result["results"][1]["captureId"] == saved["capture"]["captureId"]
    assert all(row["methodName"] == "retrieve" and row["request"]["durationMs"] == 12 for row in result["results"])
    assert "SECRET" not in json.dumps(result)
    assert capture_tools.search_saved_evidence(viewer, cli, "lookupexception", "exception")["returned"] == 2
    assert capture_tools.search_saved_evidence(viewer, cli, "ems.example", "destination")["returned"] == 2
    assert capture_tools.search_saved_evidence(viewer, cli, "Feature", "class")["returned"] == 2
    assert capture_tools.search_saved_evidence(viewer, cli, "SECRET")["returned"] == 0
    assert capture_tools.search_saved_evidence(viewer, cli, ".*")["returned"] == 0
    assert viewer.list()["count"] == cli.list()["count"] == 1


@pytest.mark.parametrize("boundary_kind,system,operation", [
    ("database", "clickhouse", "executeQuery"),
    ("database", "dynamodb", "getItem"),
    ("cache", "redis", "hget"),
    ("external", "s3", "getObject"),
])
@pytest.mark.parametrize("status", ["ATTEMPTED", "SUBMITTED", "OBSERVED", "ERROR"])
def test_search_retains_destinationless_systems_and_operations_in_both_saved_formats(
        stores, boundary_kind, system, operation, status):
    viewer, cli = stores
    raw = evidence()
    raw["segments"][0]["events"].append({
        "seq": 4, "type": "EXTERNAL_CALL", "className": "example.Client", "methodName": operation,
        "thread": "SECRET_THREAD", "attributes": {
            "boundaryKind": boundary_kind, "system": system, "direction": "outbound",
            "operation": operation, "status": status, "evidenceKind": "client-boundary",
            "sql": "SECRET_SQL", "key": "SECRET_KEY", "value": "SECRET_VALUE",
            "payload": "SECRET_PAYLOAD"}})
    raw["segments"][0]["eventCount"] = 4
    pin = viewer.save(raw)
    original = copy.deepcopy(pin)
    exported = capture_tools.export_to_capture_store(viewer, cli, pin["id"])
    reloaded_cli = captures.CaptureStore(cli.root)
    before = reloaded_cli.get(exported["capture"]["captureId"])

    for query in (system.upper(), operation.upper()):
        for category in ("destination", None):
            result = capture_tools.search_saved_evidence(viewer, reloaded_cli, query, category)
            assert result["returned"] == result["matched"] == 2
            assert {row["storage"] for row in result["results"]} == {"viewer", "capture"}
            assert all(row["category"] == "destination" for row in result["results"])
            for row in result["results"]:
                assert row["boundaryKind"] == boundary_kind
                assert row["system"] == system and row["operation"] == operation
                assert row["destination"] is None
                assert row["status"] == status and row["evidenceKind"] == "client-boundary"
                assert row["count"] == 1
            assert not result["limited"]
            assert len(captures._bytes(result)) <= capture_tools.MAX_SEARCH_BYTES
            assert "SECRET" not in json.dumps(result)
    assert capture_tools.search_saved_evidence(viewer, reloaded_cli, "SECRET", "destination")["returned"] == 0
    assert viewer.get(pin["id"]) == original
    assert reloaded_cli.get(exported["capture"]["captureId"]) == before
    assert viewer.list()["count"] == reloaded_cli.list()["count"] == 1


def test_dependency_search_accepts_only_explicit_system_or_operation_without_guessing_from_code(stores):
    viewer, cli = stores
    raw = evidence()
    raw["segments"][0]["events"] = [
        {"seq": 1, "type": "METHOD_ENTER", "className": "example.RedisClickHouseClient",
         "methodName": "queryRedis", "thread": "worker"},
        {"seq": 2, "type": "METHOD_EXIT", "className": "example.RedisClickHouseClient",
         "methodName": "queryRedis", "thread": "worker"},
        {"seq": 3, "type": "EXTERNAL_CALL", "attributes": {
            "boundaryKind": "database", "operation": "executeQuery", "evidenceKind": "client-boundary"}},
        {"seq": 4, "type": "EXTERNAL_CALL", "attributes": {
            "boundaryKind": "cache", "system": "explicit-cache", "evidenceKind": "client-boundary"}},
        {"seq": 5, "type": "EXTERNAL_CALL", "className": "example.RedisClickHouseClient", "attributes": {
            "boundaryKind": "cache", "system": "SECRET invalid system", "operation": "SECRET invalid operation",
            "evidenceKind": "client-boundary"}},
    ]
    raw["segments"][0]["eventCount"] = 5
    pin = viewer.save(raw)
    capture_tools.export_to_capture_store(viewer, cli, pin["id"])
    operation_results = capture_tools.search_saved_evidence(viewer, cli, "executequery", "destination")
    assert operation_results["returned"] == 2
    assert all(row["system"] is None and row["destination"] is None
               and row["operation"] == "executeQuery" for row in operation_results["results"])
    system_results = capture_tools.search_saved_evidence(viewer, cli, "explicit-cache", "destination")
    assert system_results["returned"] == 2
    assert all(row["system"] == "explicit-cache" and row["destination"] is None
               and row["operation"] is None for row in system_results["results"])
    for query in ("Redis", "ClickHouse", "SECRET"):
        assert capture_tools.search_saved_evidence(viewer, cli, query, "destination")["returned"] == 0
    assert capture_tools.search_saved_evidence(viewer, cli, "Redis", "method")["returned"] == 2


def test_search_result_and_global_event_limits_are_explicit(stores, monkeypatch):
    viewer, cli = stores
    viewer.save(evidence())
    viewer.save(evidence("other"))
    result = capture_tools.search_saved_evidence(viewer, cli, "example", limit=1)
    assert result["returned"] == 1 and result["matched"] > 1 and result["limited"]
    monkeypatch.setattr(capture_tools, "MAX_SEARCH_EVENTS", 1)
    result = capture_tools.search_saved_evidence(viewer, cli, "example")
    assert result["limited"] and result["eventsProcessed"] == 1
    assert "viewer_event_scan_limit" in result["warnings"]
    assert result["recordsScanned"] == 1


@pytest.mark.parametrize("query,category,limit", [
    ("", None, 50), ("  ", None, 50), ("x" * 129, None, 50), ("\n", None, 50),
    (None, None, 50), ("ok", "source", 50), ("ok", None, 0), ("ok", None, 101),
    ("ok", None, True),
])
def test_search_rejects_unbounded_or_unsupported_inputs_before_io(query, category, limit):
    with pytest.raises(ValueError):
        capture_tools.search_saved_evidence(object(), object(), query, category, limit)


def test_search_is_read_only_on_empty_stores_and_reports_unavailable_evidence(stores):
    viewer, cli = stores
    result = capture_tools.search_saved_evidence(viewer, cli, "example")
    assert result["returned"] == 0 and result["recordsScanned"] == 0
    assert not viewer.root.exists() and not cli.root.exists()
    raw = evidence()
    raw["segments"][0]["segmentId"] = raw["journey"]["segments"][0]["segmentId"] = "legacy"
    viewer.save(raw)
    result = capture_tools.search_saved_evidence(viewer, cli, "example")
    assert result["limited"] and result["returned"] == 0
    assert result["warnings"] == ["viewer_record_unavailable_or_unsupported"]


def test_cli_only_search_works_without_a_viewer_store(stores):
    viewer, cli = stores
    pin = viewer.save(evidence())
    capture_tools.export_to_capture_store(viewer, cli, pin["id"])
    result = capture_tools.search_saved_evidence(None, cli, "Feature", "method")
    assert result["returned"] == result["recordsScanned"] == 1
    assert result["results"][0]["storage"] == "capture"
    assert not result["limited"]
