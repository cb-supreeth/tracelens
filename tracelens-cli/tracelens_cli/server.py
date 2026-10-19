"""tracelens serve: the local bridge — serves the SPA and proxies the in-service
/tracelens/* debug API, so the browser never talks to the service directly.
Explicit exports, source drill-down and browser pins can load full segment events.

  GET /                      -> the SPA
  GET /api/summary           -> proxied store/agent status
  GET /api/viewer-context    -> opaque browser-storage namespace for this configuration
  GET /api/traces            -> proxied trace list
  GET /api/error-groups      -> grouped observed errors from retained summaries
  GET /api/export/{id}       -> non-destructive raw export
  GET /api/render-data/{id}  -> export + source lines resolved from the local checkout
  GET /api/distributed-trace/{id} -> joined journey + canonical request-flow model
  GET /api/trace-visualization/{id} -> canonical request-flow model only
  POST /api/enable|disable|reset[/{id}] -> proxied
"""

from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import threading
import urllib.error
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import source
from .client import DebugApiError
from .error_groups import build_error_groups, validate_filters
from .services import Federation, Service
from .visualization import build_visualization

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


def _viewer_context(upstream: str, source_root: str | None,
                    services: list[Service] | None) -> dict[str, Any]:
    """Identify configured data/source mappings without exposing them to the browser.

    JVM instance IDs and fetched metadata are deliberately excluded, so saved views
    and local snapshots remain available after a service or viewer restart.
    """
    mode = "federated" if services is not None else "single"
    configured = services if services is not None else [Service(
        "local", upstream, source_root or os.environ.get("TRACELENS_SOURCE_ROOT") or str(source.DEFAULT_SOURCE_ROOT))]
    mappings = []
    for item in sorted(configured, key=lambda item: item.name):
        parsed = urllib.parse.urlsplit(item.url)
        endpoint = [parsed.scheme.lower(), (parsed.hostname or "").lower(),
                    parsed.port or (443 if parsed.scheme.lower() == "https" else 80),
                    parsed.path.rstrip("/"), parsed.query, parsed.fragment,
                    parsed.username, parsed.password]
        root = str(Path(item.source_root).resolve()) if item.source_root else None
        mappings.append([item.name, endpoint, root])
    canonical = json.dumps({"mode": mode, "mappings": mappings}, sort_keys=True,
                           separators=(",", ":"), ensure_ascii=True)
    return {"namespace": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
            "mode": mode, "services": [item[0] for item in mappings]}


class _Handler(BaseHTTPRequestHandler):
    server_version = "tracelens-bridge/0.1"

    # injected by make_server
    upstream: str = "http://localhost:8082"
    source_root: str | None = None
    federation: Federation | None = None
    discovery: Federation
    viewer_context: dict[str, Any]

    def log_message(self, fmt, *args):  # quiet by default
        pass

    def do_GET(self):
        try:
            self._get()
        except (ValueError, KeyError) as exc:
            self._send_json({"error": str(exc)}, 400)
        except (DebugApiError, OSError) as exc:
            self._send_json({"error": str(exc)}, 502)

    def _get(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        service = query.get("service", [None])[0]
        instance = query.get("instance", [None])[0]
        if path == "/" or path == "/index.html":
            self._serve_static("index.html")
        elif path.startswith("/static/"):
            self._serve_static(path[len("/static/"):])
        elif path == "/api/viewer-context":
            self._send_json(self.viewer_context, 200)
        elif path == "/api/summary":
            if self.federation:
                self._send_json(self.federation.summary(), 200)
            else:
                self._proxy("GET", "/summary")
        elif path == "/api/traces":
            if self.federation:
                self._send_json(self.federation.traces(), 200)
            else:
                self._proxy("GET", "/traces")
        elif path == "/api/error-groups":
            # Filter after loading complete request context, not at the service
            # boundary: a successful root may have a failing downstream segment.
            params = urllib.parse.parse_qs(parsed.query, keep_blank_values=True, max_num_fields=32)
            if any(len(values) != 1 for values in params.values()):
                raise ValueError("error filters must be supplied once each")
            filters = validate_filters({name: values[0] for name, values in params.items()})
            doc = self.discovery.traces(limit=1000)
            self._send_json(build_error_groups(doc, filters), 200)
        elif path == "/api/distributed-traces":
            filters = {name: query[name][0] for name in ("service", "method", "path", "user") if name in query}
            if "since_minutes" in query:
                filters["since_minutes"] = float(query["since_minutes"][0])
            if "limit" in query:
                filters["limit"] = int(query["limit"][0])
            self._send_json(self.discovery.traces(**filters), 200)
        elif path.startswith("/api/distributed-trace/"):
            journey = self.discovery.get_distributed_trace(
                urllib.parse.unquote(path[len("/api/distributed-trace/"):]))
            # One upstream read supplies both the inspector and the canonical
            # bounded graph returned through MCP. No second export is needed.
            self._send_json({**journey, "visualization": build_visualization(journey)}, 200)
        elif path.startswith("/api/trace-visualization/"):
            journey = self.discovery.get_distributed_trace(
                urllib.parse.unquote(path[len("/api/trace-visualization/"):]))
            self._send_json(build_visualization(journey), 200)
        elif path.startswith("/api/export/"):
            segment_id = urllib.parse.unquote(path[len("/api/export/"):])
            if self.federation:
                self._send_json(self.federation.export(segment_id, service=service, instance=instance), 200)
            else:
                self._proxy("GET", "/export/" + urllib.parse.quote(segment_id, safe="") + "?evict=false")
        elif path.startswith("/api/render-data/"):
            segment_id = urllib.parse.unquote(path[len("/api/render-data/"):])
            if self.federation:
                trace = self.federation.export(segment_id, service=service, instance=instance)
                root = self.federation.source_root(trace["service"])
                self._send_json(render_data(trace, root) if root else trace, 200)
            else:
                self._render_data(urllib.parse.quote(segment_id, safe=""))
        else:
            self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        if self.federation:
            self._send_json({"error": "multi-service UI is read-only; use a single-service client for mutations"}, 405)
            return
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
        if WEB_DIR.resolve() not in path.parents or not path.is_file():
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
        url = f"{self.upstream}/tracelens/export/{trace_id}?evict=false"
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


def make_server(port: int, upstream: str, source_root: str | None,
                services: list[Service] | None = None) -> ThreadingHTTPServer:
    discovery = Federation(services or [Service("default", upstream, source_root)])
    handler = type("TraceLensHandler", (_Handler,), {
        "upstream": upstream,
        "source_root": source_root,
        "federation": discovery if services is not None else None,
        "discovery": discovery,
        "viewer_context": _viewer_context(upstream, source_root, services),
    })
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    return server


def serve(port: int, upstream: str, source_root: str | None,
          services: list[Service] | None = None) -> int:
    server = make_server(port, upstream, source_root, services)
    print(f"tracelens UI:  http://127.0.0.1:{port}")
    print("services:      " + ", ".join(s.name for s in services) if services else f"proxying:      {upstream}/tracelens/*")
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
