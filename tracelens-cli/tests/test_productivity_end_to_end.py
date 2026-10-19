"""Two real loopback debug endpoints -> CLI capture -> offline MCP comparison."""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time

from tracelens_cli.cli import main
from tracelens_cli.mcp_server import TraceLensMcpServer


@contextmanager
def debug_service(state):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            state["reads"].append(self.path)
            if self.path == "/tracelens/traces" or self.path.startswith("/tracelens/find-by-trace-id/"):
                payload = {"traces": [{key: value for key, value in state["segment"].items() if key != "events"}]}
            elif self.path.startswith("/tracelens/export/") and self.path.endswith("?evict=false"):
                payload = state["segment"]
            else:
                self.send_error(404)
                return
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def segment(service, candidate=False):
    events = [
        {"seq": 1, "type": "METHOD_ENTER", "methodName": "readOptimized" if candidate else "readLegacy"},
        {"seq": 2, "type": "LINE", "line": 21 if candidate else 20},
        {"seq": 3, "type": "METHOD_EXIT", "methodName": "readOptimized" if candidate else "readLegacy"},
    ]
    for event in events:
        event.update(className="com.example.FeatureService", thread="worker", nanoTime=event["seq"] * 1000)
    child = service == "ems"
    return {"traceId": ("c" if candidate else "a") * 32,
            "segmentId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" if child else "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "serviceInstanceId": "restarted" if candidate else "original", "entryChannel": "HTTP",
            "spanId": ("2" if child else "1") * 16, "parentSpanId": "1" * 16 if child else None,
            "startedAtMillis": int(time.time() * 1000), "status": "COMPLETED", "httpMethod": "GET",
            "path": "/features" if child else "/api/v2/features", "httpStatus": 500 if candidate and child else 200,
            "durationMs": 12, "events": events, "eventCount": len(events), "truncated": False,
            "user": "not-for-snapshot@example.invalid", "contextParams": {"private": "do-not-save"}}


def test_public_workflow_survives_service_restart_and_is_offline_for_compare(tmp_path, capsys):
    app = {"segment": segment("app"), "reads": []}
    ems = {"segment": segment("ems"), "reads": []}
    store = tmp_path / "captures"
    with debug_service(app) as app_url, debug_service(ems) as ems_url:
        registry = tmp_path / "services.json"
        registry.write_text(json.dumps({"services": [{"name": "app", "url": app_url},
                                                     {"name": "ems", "url": ems_url}]}))
        common = ["--services", str(registry), "--capture-dir", str(store), "--service", "app"]
        for name, revision in (("before", "build-1"), ("after", "build-2")):
            assert main(common + ["capture", "save", name, "--revision", revision,
                                  "--path", "/api/v2/features", "--method", "GET"]) == 0
            saved = json.loads(capsys.readouterr().out)
            assert saved["saved"] and saved["capture"]["segmentCount"] == 2
            app["segment"] = segment("app", candidate=True)
            ems["segment"] = segment("ems", candidate=True)
        assert all("evict=false" in path for state in (app, ems) for path in state["reads"] if "/export/" in path)

    # Both fixture services are stopped. New reader processes need no live API.
    assert main(["--capture-dir", str(store), "capture", "compare", "before", "after"]) == 0
    comparison = json.loads(capsys.readouterr().out)
    assert {row["methodName"] for row in comparison["methods"]["added"]} == {"readOptimized"}
    assert {row["methodName"] for row in comparison["methods"]["noLongerObserved"]} == {"readLegacy"}
    assert any(row["httpStatus"] == 500 for row in comparison["outcomes"]["added"])
    mcp = TraceLensMcpServer("http://127.0.0.1:1", None, capture_dir=str(store))
    result = mcp._dispatch_tool("compare_request_captures", {"before": "before", "after": "after"})
    assert not result["isError"]
    assert result["structuredContent"] == comparison
    assert mcp.tool_list_request_captures()["structuredContent"]["count"] == 2
    files = list(store.glob("*.json"))
    assert len(files) == 2
    assert all("not-for-snapshot" not in path.read_text() and "do-not-save" not in path.read_text() for path in files)
