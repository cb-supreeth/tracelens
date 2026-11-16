import json
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from tracelens_cli import __version__, server


def _trace_with_line():
    return {
        "traceId": "t-1", "segmentId": "t-1", "lensId": "flow", "user": "u",
        "entryChannel": "HTTP", "status": "COMPLETED", "startedAt": 1000,
        "startNanos": 0, "eventCount": 2, "truncated": False, "attributes": {},
        "events": [
            {"seq": 1, "type": "LINE", "className": "com.chargebee.ems.Foo",
             "line": 2, "thread": "main", "nanoTime": 0, "epochMillis": 1000},
            {"seq": 2, "type": "LINE", "className": "com.chargebee.ems.Missing",
             "line": 9, "thread": "main", "nanoTime": 1, "epochMillis": 1000},
        ],
    }


def test_enrich_events_adds_source_lines(tmp_path: Path):
    src = tmp_path / "src" / "main" / "java" / "com" / "chargebee" / "ems"
    src.mkdir(parents=True)
    (src / "Foo.java").write_text("package com.chargebee.ems;\nclass Foo {\n}\n")

    enriched = server.enrich_events(_trace_with_line(), str(tmp_path))

    assert enriched[0]["sourceLine"] == "class Foo {"
    assert "sourceLine" not in enriched[1]  # unresolvable class -> omitted


class _StubUpstream(BaseHTTPRequestHandler):
    """A fake /tracelens/* upstream for the proxy smoke test."""

    def do_GET(self):
        if self.path == "/tracelens/summary":
            body = json.dumps({"recordingEnabled": True, "traceCount": 3}).encode()
        elif self.path.startswith("/tracelens/export/"):
            body = json.dumps(_trace_with_line()).encode()
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


@pytest.fixture()
def bridge(tmp_path: Path):
    src = tmp_path / "src" / "main" / "java" / "com" / "chargebee" / "ems"
    src.mkdir(parents=True)
    (src / "Foo.java").write_text("package x;\nclass Foo {\n}\n")

    upstream = ThreadingHTTPServer(("127.0.0.1", 0), _StubUpstream)
    upstream_port = upstream.server_address[1]
    bridge_server = server.make_server(0, f"http://127.0.0.1:{upstream_port}", str(tmp_path))
    bridge_port = bridge_server.server_address[1]

    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    threading.Thread(target=bridge_server.serve_forever, daemon=True).start()
    yield bridge_port
    bridge_server.shutdown()
    upstream.shutdown()


def _get(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=5) as r:
        return json.loads(r.read().decode())


def test_proxy_summary(bridge):
    doc = _get(bridge, "/api/summary")
    assert doc["recordingEnabled"] is True
    assert doc["traceCount"] == 3


def test_render_data_enriches(bridge):
    doc = _get(bridge, "/api/render-data/t-1")
    assert doc["traceId"] == "t-1"
    assert doc["events"][0]["sourceLine"] == "class Foo {"


def test_spa_served(bridge):
    with urllib.request.urlopen(f"http://127.0.0.1:{bridge}/", timeout=5) as r:
        assert r.headers["Server"].split()[0] == f"tracelens-bridge/{__version__}"
        html = r.read().decode()
    assert "TraceLens" in html
    assert "/static/app.js" in html


def test_static_path_traversal_blocked(bridge):
    # http.server normalizes ../ before our handler; a crafted un-normalized path
    # must still not escape WEB_DIR
    import http.client
    conn = http.client.HTTPConnection("127.0.0.1", bridge, timeout=5)
    conn.request("GET", "/static/../server.py")
    resp = conn.getresponse()
    resp.read()
    assert resp.status == 404
