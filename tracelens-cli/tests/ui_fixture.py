"""Deterministic fake-agent data for exercising the real browser UI.

Run from tracelens-cli: uv run python tests/ui_fixture.py [--port 0]
This file is not collected by pytest. It never calls a real service or reads an
application checkout; sourceLine values below are synthetic fixture text.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.parse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# Also support a plain Python invocation from outside the package directory.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tracelens_cli.server import make_server
from tracelens_cli.services import Service


FEATURE_OK_TRACE = "11111111111111111111111111111111"
FEATURE_ERROR_TRACE = "22222222222222222222222222222222"
AUTH_TRACE = "33333333333333333333333333333333"
OLD_TRACE = "44444444444444444444444444444444"
JOB_TRACE = "55555555555555555555555555555555"
MALICIOUS_LABEL = '<img src=x onerror="window.__tracelensFixtureXss=true">'


def _segment(service, trace_id, segment_id, span, now, *, age_seconds=60,
             duration=12, path="/api/v2/features", status=200, parent=None,
             label=None, channel="HTTP"):
    started = now - age_seconds * 1000
    attributes = {"siteId": "42", "serviceInstanceId": service + "-fixture-instance"}
    if channel == "HTTP":
        attributes.update(httpMethod="GET", path=path, routeTemplate=path, httpStatus=str(status))
    if status >= 500:
        attributes["filterException"] = "java.lang.IllegalStateException"
    events = [
        {"seq": 1, "type": "METHOD_ENTER", "className": "com.example.fixture.FeatureService",
         "methodName": "listFeatures", "thread": "fixture-http-1", "nanoTime": 1000000,
         "epochMillis": started},
        {"seq": 2, "type": "LINE", "className": "com.example.fixture.FeatureService",
         "methodName": "listFeatures", "line": 12, "thread": "fixture-http-1",
         "nanoTime": 1500000, "epochMillis": started + 1,
         "sourceLine": "return featureRepository.findAll(); // synthetic QA fixture"},
        {"seq": 3, "type": "METHOD_EXIT", "className": "com.example.fixture.FeatureService",
         "methodName": "listFeatures", "thread": "fixture-http-1", "nanoTime": 1000000 + duration * 1000000,
         "epochMillis": started + duration},
    ]
    if status >= 500:
        events.insert(2, {"seq": 3, "type": "EXCEPTION", "className": "com.example.fixture.FeatureService",
                          "methodName": "listFeatures", "thread": "fixture-http-1",
                          "nanoTime": 2000000, "epochMillis": started + 2,
                          "message": "Synthetic downstream unavailable", "attributes": {
                              "exceptionType": "java.lang.IllegalStateException"}})
        events[-1]["seq"] = 4
    row = {"schemaVersion": 2, "traceId": trace_id, "segmentId": segment_id,
           "spanId": span, "parentSpanId": parent, "applicationId": service,
           "applicationVersion": "BROWSER-QA", "serviceInstanceId": service + "-fixture-instance",
           "lensId": label or ("auto:GET " + path if channel == "HTTP" else "scheduled:fixture-renewal"),
           "user": "alice_42" if service == "cb-app" else "apicall_42",
           "siteId": "42", "entryChannel": channel, "status": "COMPLETED",
           "startedAt": started, "startedAtMillis": started,
           "endedAt": started + duration, "lastActivityMillis": started + duration,
           "startNanos": 1000000, "durationMs": duration, "eventCount": len(events),
           "approxSizeBytes": 1536, "truncated": False, "httpCallsTruncated": False,
           "attributes": attributes, "events": events, "httpCalls": [], "httpCallCount": 0}
    if channel == "HTTP":
        row.update(httpMethod="GET", path=path, routeTemplate=path, httpStatus=str(status))
    if status >= 500:
        row["filterException"] = attributes["filterException"]
    return row


def fixture_rows(now_ms: int | None = None):
    """Seven segments, five logical flows; IDs are stable and timestamps are recent."""
    now = int(time.time() * 1000) if now_ms is None else now_ms
    app_ok = _segment("cb-app", FEATURE_OK_TRACE, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
                      "1111111111111111", now, age_seconds=60, duration=25)
    ems_ok = _segment("cb-ems", FEATURE_OK_TRACE, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1",
                      "1111111111111113", now, age_seconds=60, duration=12,
                      path="/features", parent="1111111111111112")
    app_error = _segment("cb-app", FEATURE_ERROR_TRACE, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
                         "2222222222222221", now, age_seconds=120, duration=100, label=MALICIOUS_LABEL)
    ems_error = _segment("cb-ems", FEATURE_ERROR_TRACE, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2",
                         "2222222222222223", now, age_seconds=120, duration=25,
                         path="/features", status=503, parent="2222222222222222")
    for app, child, call_span in [(app_ok, ems_ok, "1111111111111112"),
                                  (app_error, ems_error, "2222222222222222")]:
        app["httpCalls"] = [{"traceId": app["traceId"], "spanId": call_span, "parentSpanId": app["spanId"],
                             "httpMethod": "GET", "path": "/features", "peer": "cb-ems-fixture:8082",
                             "httpStatus": child["httpStatus"],
                             "status": "ERROR" if int(child["httpStatus"]) >= 400 else "COMPLETED",
                             "durationNanos": str(child["durationMs"] * 1000000),
                             "startedAt": str(app["startedAt"]), "endedAt": str(child["endedAt"]),
                             "propagationStatus": "INJECTED"}]
        app["httpCallCount"] = 1
    unauthorized = _segment("cb-app", AUTH_TRACE, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3",
                            "3333333333333331", now, age_seconds=30, duration=12,
                            path="/api/v2/subscriptions", status=401)
    unauthorized["user"] = "anonymous"
    old = _segment("cb-app", OLD_TRACE, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4",
                   "4444444444444441", now, age_seconds=2 * 60 * 60, duration=25)
    job = _segment("cb-ems", JOB_TRACE, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5",
                   "5555555555555551", now, age_seconds=15, duration=100, channel="SCHEDULER")
    job["user"] = "system"
    return {"cb-app": [app_ok, app_error, unauthorized, old], "cb-ems": [ems_ok, ems_error, job]}


@contextmanager
def fake_api(service: str, rows: list[dict]):
    """Serve immutable, non-destructive fake agent reads; record requests for QA."""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            parsed = urllib.parse.urlsplit(self.path)
            route = urllib.parse.unquote(parsed.path)
            if route == "/tracelens/summary":
                result = {"applicationId": service, "applicationVersion": "BROWSER-QA",
                          "serviceInstanceId": service + "-fixture-instance", "mode": "standalone",
                          "recordingEnabled": True, "agentActive": True,
                          "distributedTracingEnabled": True, "traceCount": len(rows),
                          "totalEvents": sum(row["eventCount"] for row in rows),
                          "instrumentedClasses": 1, "failedClasses": 0}
            elif route == "/tracelens/metadata":
                result = {"applicationId": service, "applicationVersion": "BROWSER-QA", "capturePayloads": False}
            elif route == "/tracelens/traces" or route.startswith("/tracelens/find-by-trace-id/"):
                selected = rows
                if route.startswith("/tracelens/find-by-trace-id/"):
                    trace_id = route[len("/tracelens/find-by-trace-id/"):]
                    selected = [row for row in rows if row["traceId"] == trace_id]
                summaries = [{key: value for key, value in row.items() if key not in ("events", "httpCalls")}
                             for row in selected]
                result = {"count": len(summaries), "traces": summaries}
            elif route.startswith("/tracelens/export/"):
                segment_id = route[len("/tracelens/export/"):]
                selected = [row for row in rows if row["segmentId"] == segment_id]
                if not selected:
                    self._json({"error": "fixture segment not found"}, 404)
                    return
                # Never mutate rows or remove events, irrespective of the query string.
                result = selected[0]
            else:
                self._json({"error": "fixture endpoint not found"}, 404)
                return
            self._json(result)

        def do_POST(self):
            self._json({"error": "browser QA fixture is read-only"}, 405)

        def _json(self, value, status=200):
            body = json.dumps(value).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    api = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=lambda: api.serve_forever(poll_interval=0.05), daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{api.server_port}", requests
    finally:
        api.shutdown()
        api.server_close()
        worker.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0, help="loopback viewer port; default chooses an unused port")
    args = parser.parse_args()
    rows = fixture_rows()
    with fake_api("cb-app", rows["cb-app"]) as (app_url, _), fake_api("cb-ems", rows["cb-ems"]) as (ems_url, _):
        services = [Service("cb-app", app_url), Service("cb-ems", ems_url)]
        viewer = make_server(args.port, app_url, None, services)
        print(f"http://127.0.0.1:{viewer.server_port}", flush=True)
        try:
            viewer.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            viewer.server_close()


if __name__ == "__main__":
    main()
