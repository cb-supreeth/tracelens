"""tracelens serve: the local bridge — serves the SPA and proxies the in-service
/tracelens/* debug API, so the browser never talks to the service directly (and never
holds megabytes of raw trace). FlowLens's bridge pattern, minus the backend.

  GET /                      -> the SPA
  GET /api/summary           -> proxied store/agent status
  GET /api/traces            -> proxied trace list
  GET /api/export/{id}       -> proxied raw export (destructive per server config!)
  GET /api/render-data/{id}  -> export + source lines resolved from the local checkout
  POST /api/enable|disable|reset[/{id}] -> proxied
"""

from __future__ import annotations

import json
import mimetypes
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import source

WEB_DIR = Path(__file__).parent / "web"


def enrich_events(trace: dict[str, Any], source_root: str | None) -> list[dict[str, Any]]:
    """Export events + resolved source lines (server-side, reusing source.py)."""
    enriched = []
    for event in trace.get("events", []):
        e = dict(event)
        if e.get("type") == "LINE" and e.get("className") and e.get("line", -1) >= 1:
            line = source.source_line(e["className"], e["line"], source_root)
            if line:
                e["sourceLine"] = line
        enriched.append(e)
    return enriched


def render_data(trace: dict[str, Any], source_root: str | None) -> dict[str, Any]:
    doc = dict(trace)
    doc["events"] = enrich_events(trace, source_root)
    return doc


class _Handler(BaseHTTPRequestHandler):
    server_version = "tracelens-bridge/0.1"

    # injected by make_server
    upstream: str = "http://localhost:8082"
    source_root: str | None = None

    def log_message(self, fmt, *args):  # quiet by default
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/" or path == "/index.html":
            self._serve_static("index.html")
        elif path.startswith("/static/"):
            self._serve_static(path[len("/static/"):])
        elif path == "/api/summary":
            self._proxy("GET", "/summary")
        elif path == "/api/traces":
            self._proxy("GET", "/traces")
        elif path.startswith("/api/export/"):
            self._proxy("GET", "/export/" + path[len("/api/export/"):])
        elif path.startswith("/api/render-data/"):
            self._render_data(path[len("/api/render-data/"):])
        else:
            self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path in ("/api/enable", "/api/disable", "/api/reset"):
            self._proxy("POST", path[len("/api"):])
        elif path.startswith("/api/reset/"):
            self._proxy("POST", "/reset/" + path[len("/api/reset/"):])
        else:
            self._send_json({"error": "not found"}, 404)

    # ---- internals ----

    def _serve_static(self, name: str):
        path = (WEB_DIR / name).resolve()
        if not str(path).startswith(str(WEB_DIR.resolve())) or not path.is_file():
            self._send_json({"error": "not found"}, 404)
            return
        content_type = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self, method: str, upstream_path: str):
        url = f"{self.upstream}/tracelens{upstream_path}"
        try:
            req = urllib.request.Request(url, method=method)
            with urllib.request.urlopen(req, timeout=10) as resp:
                body = resp.read()
                self.send_response(resp.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as e:
            self._send_json({"error": f"upstream {e.code}", "body": e.read()[:300].decode("utf-8", "replace")},
                            e.code)
        except urllib.error.URLError as e:
            self._send_json({"error": f"upstream unreachable: {e.reason}"}, 502)

    def _render_data(self, trace_id: str):
        url = f"{self.upstream}/tracelens/export/{trace_id}"
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                trace = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            self._send_json({"error": f"upstream {e.code}"}, e.code)
            return
        except urllib.error.URLError as e:
            self._send_json({"error": f"upstream unreachable: {e.reason}"}, 502)
            return
        self._send_json(render_data(trace, self.source_root), 200)

    def _send_json(self, doc: Any, status: int):
        body = json.dumps(doc).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def make_server(port: int, upstream: str, source_root: str | None) -> ThreadingHTTPServer:
    handler = type("TraceLensHandler", (_Handler,), {
        "upstream": upstream,
        "source_root": source_root,
    })
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    return server


def serve(port: int, upstream: str, source_root: str | None) -> int:
    server = make_server(port, upstream, source_root)
    print(f"tracelens UI:  http://127.0.0.1:{port}")
    print(f"proxying:      {upstream}/tracelens/*")
    print(f"sources from:  {source_root or source.DEFAULT_SOURCE_ROOT}")
    print("Ctrl-C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        threading.Thread(target=server.shutdown, daemon=True).start()
        server.server_close()
    return 0
