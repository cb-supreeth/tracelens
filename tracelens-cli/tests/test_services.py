import copy
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from tracelens_cli import cli, server
from tracelens_cli.client import DebugApiError
from tracelens_cli.mcp_server import TraceLensMcpServer
from tracelens_cli.services import Federation, Service, join_trace, load_services


TRACE_ID = "1234567890abcdef1234567890abcdef"
APP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
EMS_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"


def segment(service, segment_id, span, parent=None):
    return {"traceId": TRACE_ID, "segmentId": segment_id, "spanId": span,
            "parentSpanId": parent, "applicationId": service, "applicationVersion": "1.0",
            "serviceInstanceId": service + "-instance", "lensId": "auto:GET /features",
            "user": "alice_42", "entryChannel": "HTTP", "status": "COMPLETED",
            "eventCount": 1, "approxSizeBytes": 100, "startedAtMillis": int(time.time() * 1000),
            "lastActivityMillis": int(time.time() * 1000), "durationMs": 12,
            "httpMethod": "GET", "path": "/features", "attributes": {}, "startNanos": 0,
            "events": [{"type": "LINE", "className": "com.example.Foo", "line": 2,
                        "nanoTime": 1, "epochMillis": 1, "thread": "main", "seq": 1}],
            "httpCalls": []}


@contextmanager
def fake_api(rows):
    requests = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            route = urllib.parse.urlsplit(self.path).path
            if route == "/tracelens/summary":
                result = {"traceCount": len(rows), "totalEvents": len(rows)}
            elif route == "/tracelens/traces" or route.startswith("/tracelens/find-by-trace-id/"):
                result = {"traces": [{k: v for k, v in row.items() if k not in ("events", "httpCalls")} for row in rows]}
            elif route.startswith("/tracelens/export/"):
                selected = [r for r in rows if r["segmentId"] == route.rsplit("/", 1)[1]]
                if not selected:
                    self.send_error(404)
                    return
                result = selected[0]
            else:
                self.send_error(404)
                return
            body = json.dumps(result).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_POST(self):
            pytest.fail("read-only discovery must never mutate an upstream")
        def log_message(self, *args):
            pass
    api = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=api.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{api.server_port}", requests
    finally:
        api.shutdown()
        api.server_close()
        thread.join()


@pytest.fixture
def paired(tmp_path):
    app = segment("cb-app", APP_ID, "1111111111111111")
    ems = segment("cb-ems", EMS_ID, "3333333333333333", "2222222222222222")
    app["httpCalls"] = [{"traceId": TRACE_ID, "spanId": "2222222222222222", "parentSpanId": app["spanId"],
                         "httpMethod": "GET", "path": "/features", "peer": "ems:8082", "status": "COMPLETED",
                         "httpStatus": 200, "durationNanos": 9000000, "propagationStatus": "injected"}]
    roots = []
    for name in ("cb-app", "cb-ems"):
        root = tmp_path / name
        java = root / "src/main/java/com/example/Foo.java"
        java.parent.mkdir(parents=True)
        java.write_text("package com.example;\n// " + name + " source\n")
        roots.append(str(root))
    with fake_api([app]) as (app_url, app_requests), fake_api([ems]) as (ems_url, ems_requests):
        services = [Service("cb-app", app_url, roots[0]), Service("cb-ems", ems_url, roots[1])]
        yield Federation(services), services, [app, ems], app_requests + ems_requests, (app_requests, ems_requests)


def test_two_services_join_and_read_only_exports(paired):
    federation, services, rows, _, request_lists = paired
    result = federation.get_distributed_trace(TRACE_ID)
    assert len(result["segments"]) == 2
    assert len(result["links"]) == 1
    assert result["httpCalls"][0]["downstreamState"] == "observed"
    assert result["completeness"] == "observed-only"
    assert result["partial"] is False
    assert all("events" not in row for row in result["segments"])
    for requests in request_lists:
        exports = [path for path in requests if "/export/" in path]
        assert exports and all(path.endswith("?evict=false") for path in exports)


def test_search_by_service_method_path_user_and_time(paired):
    federation = paired[0]
    result = federation.traces(service="cb-app", method="get", path="feat", user="alice", since_minutes=1)
    assert result["count"] == 1
    assert result["traces"][0]["service"] == "cb-app"
    assert federation.traces(method="POST")["count"] == 0
    assert federation.traces(user="nobody")["count"] == 0
    with pytest.raises(ValueError, match="unknown configured service"):
        federation.traces(service="http://malicious")


@pytest.mark.parametrize("metadata,partial,limited", [
    ({"partial": True}, True, False),
    ({"limited": True}, False, True),
    ({"errors": [{"error": "SECRET_UPSTREAM_BODY"}]}, True, False),
    ({"partial": True, "limited": True}, True, True),
    ({"partial": False, "limited": False, "errors": []}, False, False),
])
def test_summary_incompleteness_survives_merging_and_filtering(metadata, partial, limited):
    federation = Federation([Service("app", "http://app.invalid"), Service("ems", "http://ems.invalid")])
    class Client:
        def __init__(self, rows, extra=None):
            self.doc = {"traces": rows, **(extra or {})}
        def traces(self):
            return copy.deepcopy(self.doc)
    federation.clients["app"] = Client([segment("app", APP_ID, "1111111111111111")])
    federation.clients["ems"] = Client([], metadata)
    for filters, count in [({}, 1), ({"method": "POST"}, 0)]:
        result = federation.traces(**filters)
        assert result["partial"] is partial
        assert result["limited"] is limited
        assert result["count"] == count
        assert set(result) == {"traces", "count", "errors", "partial", "limited", "federated"}
        assert result["errors"] == []  # No arbitrary upstream error body is exposed.
        assert "SECRET_UPSTREAM_BODY" not in json.dumps(result)
    # Unqueried services do not contaminate an explicitly selected scope.
    selected = federation.traces(service="app")
    assert selected["partial"] is selected["limited"] is False


def test_local_summary_truncation_remains_limited_without_claiming_upstream_count():
    federation = Federation([Service("app", "http://app.invalid")])
    class Client:
        def traces(self):
            return {"traces": [segment("app", APP_ID, "1111111111111111"),
                               segment("app", EMS_ID, "2222222222222222")],
                    "count": 1000, "limited": False}
    federation.clients["app"] = Client()
    result = federation.traces(limit=1)
    assert result["limited"] is True
    assert result["partial"] is False
    assert result["count"] == len(result["traces"]) == 1


def test_offline_service_is_explicit_and_downstream_remains_incomplete(paired):
    federation = paired[0]
    class Offline:
        def find_by_trace_id(self, _):
            raise DebugApiError("offline")
        def traces(self):
            raise DebugApiError("offline")
    federation.clients["cb-ems"] = Offline()
    result = federation.get_distributed_trace(TRACE_ID)
    assert result["partial"] is True
    assert result["errors"] == [{"service": "cb-ems", "error": "offline"}]
    assert result["httpCalls"][0]["downstreamState"] == "not-observed"
    assert "Downstream not observed" in result["warnings"][0]
    assert federation.traces()["partial"] is True


def test_segment_collision_requires_service_and_instance_checked(paired):
    federation, _, rows, _, _ = paired
    rows[1]["segmentId"] = APP_ID
    with pytest.raises(DebugApiError, match="ambiguous"):
        federation.export(APP_ID)
    result = federation.export(APP_ID, service="cb-ems", instance="cb-ems-instance")
    assert result["applicationId"] == "cb-ems"
    with pytest.raises(DebugApiError, match="instance changed"):
        federation.export(APP_ID, service="cb-ems", instance="previous-instance")
    assert len({r["segmentKey"] for r in federation.traces()["traces"]}) == 2


def test_mcp_federation_and_correct_source(paired):
    _, services, _, _, _ = paired
    mcp = TraceLensMcpServer("http://unused", None, services)
    search = mcp._dispatch_tool("find_distributed_traces", {"path": "features", "service": "cb-app"})
    assert json.loads(search["content"][0]["text"])["count"] == 1
    joined = mcp._dispatch_tool("get_distributed_trace", {"trace_id": TRACE_ID})
    assert len(json.loads(joined["content"][0]["text"])["links"]) == 1
    source = mcp.tool_get_flow(EMS_ID, service="cb-ems", instance="cb-ems-instance")
    assert "// cb-ems source" in source["content"][0]["text"]
    assert "// cb-app source" not in source["content"][0]["text"]
    ambiguous = mcp._dispatch_tool("get_flow", {"id": "auto:GET /features"})
    assert ambiguous["isError"] is True


def test_single_service_new_mcp_tools(paired):
    _, services, _, _, _ = paired
    mcp = TraceLensMcpServer(services[0].url, services[0].source_root)
    doc = json.loads(mcp.tool_get_distributed_trace(TRACE_ID)["content"][0]["text"])
    assert len(doc["segments"]) == 1
    assert doc["segments"][0]["service"] == "default"
    assert doc["partial"] is True  # downstream exists but is not configured in this mode


@contextmanager
def ui(services=None, upstream="http://unused", root=None):
    app = server.make_server(0, upstream, root, services)
    thread = threading.Thread(target=app.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{app.server_port}"
    finally:
        app.shutdown()
        app.server_close()
        thread.join()


def get(url):
    with urllib.request.urlopen(url) as response:
        return json.load(response)


def test_ui_routes_join_source_and_disable_mutation(paired):
    _, services, _, _, _ = paired
    with ui(services) as url:
        assert get(url + "/api/summary")["readOnly"]
        assert get(url + "/api/traces")["count"] == 2
        assert get(url + "/api/distributed-traces?service=cb-ems&method=GET&path=features")["count"] == 1
        assert len(get(url + "/api/distributed-trace/" + TRACE_ID)["links"]) == 1
        trace = get(url + "/api/render-data/" + EMS_ID + "?service=cb-ems&instance=cb-ems-instance")
        assert trace["events"][0]["sourceLine"] == "// cb-ems source"
        with pytest.raises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(urllib.request.Request(url + "/api/reset", method="POST"))
        assert caught.value.code == 405
        with pytest.raises(urllib.error.HTTPError) as caught:
            get(url + "/api/export/" + EMS_ID + "?service=http://evil")
        assert caught.value.code == 400


def test_single_ui_exports_never_evict(paired):
    _, services, _, _, request_lists = paired
    with ui(upstream=services[0].url, root=services[0].source_root) as url:
        get(url + "/api/render-data/" + APP_ID)
        get(url + "/api/export/" + APP_ID + "?evict=true")
    assert all(path.endswith("?evict=false") for path in request_lists[0] if "/export/" in path)


def test_missing_source_root_never_uses_other_checkout(paired, monkeypatch):
    _, services, _, _, _ = paired
    monkeypatch.setattr(server.source, "source_line", lambda *args: pytest.fail("no configured source root"))
    without_root = [Service(services[0].name, services[0].url)]
    with ui(without_root) as url:
        trace = get(url + "/api/render-data/" + APP_ID + "?service=cb-app")
        assert "sourceLine" not in trace["events"][0]


def test_registry_validation_and_cli(paired, tmp_path, capsys):
    _, services, _, _, _ = paired
    config = tmp_path / "services.json"
    config.write_text(json.dumps({"services": [{"name": s.name, "url": s.url, "sourceRoot": s.source_root} for s in services]}))
    assert load_services(str(config)) == services
    assert cli.main(["--services", str(config), "find-distributed", "--method", "GET"]) == 0
    assert json.loads(capsys.readouterr().out)["count"] == 2
    assert cli.main(["--services", str(config), "get-distributed", TRACE_ID]) == 0
    assert len(json.loads(capsys.readouterr().out)["links"]) == 1
    assert cli.main(["--services", str(config), "reset"]) == 1
    assert "single-service" in capsys.readouterr().err


@pytest.mark.parametrize("rows", [[], [{"name": "bad name", "url": "http://localhost"}],
    [{"name": "a", "url": "file:///etc/passwd"}], [{"name": "a", "url": "http://user:pass@localhost"}],
    [{"name": "a", "url": "http://localhost?url=evil"}], [{"name": "a", "url": "http://localhost:bad"}],
    [{"name": "a", "url": "http://localhost", "sourceRoot": "../other"}],
    [{"name": "a", "url": "http://localhost"}] * 2])
def test_invalid_registry_rejected(tmp_path, rows):
    path = tmp_path / "bad.json"
    path.write_text(json.dumps({"services": rows}))
    with pytest.raises(ValueError):
        load_services(str(path))


def test_duplicate_normalized_upstream_rejected(tmp_path):
    path = tmp_path / "duplicate.json"
    path.write_text(json.dumps({"services": [
        {"name": "one", "url": "http://localhost"},
        {"name": "two", "url": "http://LOCALHOST:80/"}]}))
    with pytest.raises(ValueError, match="duplicate upstream"):
        load_services(str(path))


def test_missing_and_truncated_evidence_is_never_complete():
    empty = join_trace(TRACE_ID, [], [])
    assert empty["partial"] is True
    assert empty["completeness"] == "not-observed"
    row = segment("cb-app", APP_ID, "1111111111111111")
    row.update(service="cb-app", segmentKey="app", httpCallsTruncated=True)
    truncated = join_trace(TRACE_ID, [row], [])
    assert truncated["partial"] is True
    assert "HTTP call capture truncated" in truncated["warnings"][0]
    row["httpCalls"] = [{"spanId": f"{index:016x}"} for index in range(5001)]
    limited = join_trace(TRACE_ID, [row], [])
    assert len(limited["httpCalls"]) == 5000
    assert any("display limit reached" in warning for warning in limited["warnings"])


def test_legacy_endpoint_fallback_and_no_fallback_on_failure(paired):
    federation = paired[0]
    client = federation.clients["cb-ems"]
    def legacy(_):
        raise DebugApiError("old agent", 404)
    client.find_by_trace_id = legacy
    assert len(federation.get_distributed_trace(TRACE_ID)["segments"]) == 2
    def failure(_):
        raise DebugApiError("upstream failure", 500)
    client.find_by_trace_id = failure
    result = federation.get_distributed_trace(TRACE_ID)
    assert len(result["segments"]) == 1
    assert result["errors"][0]["error"] == "upstream failure"


def test_tail_reports_both_segments_under_same_trace(monkeypatch, capsys):
    class Client:
        def traces(self):
            return {"traces": [dict(segment("app", APP_ID, "one"), service="app"),
                               dict(segment("ems", EMS_ID, "two"), service="ems")]}
    def interrupt(_):
        raise KeyboardInterrupt
    monkeypatch.setattr(cli.time, "sleep", interrupt)
    assert cli._tail(Client(), 1) == 0
    output = capsys.readouterr().out
    assert "service=app" in output and "service=ems" in output


def test_duplicate_call_span_does_not_invent_causal_link(paired):
    federation, _, rows, _, _ = paired
    rows[0]["httpCalls"].append(copy.deepcopy(rows[0]["httpCalls"][0]))
    result = federation.get_distributed_trace(TRACE_ID)
    assert result["links"] == []
    assert any("Ambiguous parent" in warning for warning in result["warnings"])
    assert result["partial"] is True
