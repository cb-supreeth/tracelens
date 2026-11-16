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
  POST /api/segment-execution -> bounded execution model of the supplied trace only
  GET|POST /api/snapshots     -> explicit private viewer-file snapshots (no upstream reads)
  GET|DELETE /api/snapshots/{id} -> immutable evidence lookup / explicit deletion
  POST /api/snapshots/{id}/rename -> change only its human-readable name
  GET /api/guided-investigation -> exact-path running capture readiness
  GET /api/source-location -> existing current checkout coordinate + editor URLs
  GET /api/capture-tools -> bounded private saved-evidence metadata
  POST /api/capture-tools/compare|search -> offline saved-evidence readers
  POST /api/capture-tools/project -> explicit minimized CLI/MCP copy
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

from . import __version__, source
from .client import DebugApiError
from .captures import CaptureStore
from .workflow import default_capture_dir
from .error_groups import build_error_groups, validate_filters
from .execution import DEFAULT_MAX_NODES, VIEWER_MODEL_MAX_BYTES, build_segment_execution, validate_max_nodes
from .services import Federation, Service
from .visualization import build_visualization
from .viewer_snapshots import MAX_BYTES as SNAPSHOT_MAX_BYTES, SnapshotError, ViewerSnapshotStore, default_snapshot_dir, strict_json

WEB_DIR = Path(__file__).parent / "web"
SEGMENT_EXECUTION_REQUEST_MAX_BYTES = 5 * 1024 * 1024
SEGMENT_EXECUTION_READ_TIMEOUT_SECONDS = 5


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
    server_version = f"tracelens-bridge/{__version__}"

    # injected by make_server
    upstream: str = "http://localhost:8082"
    source_root: str | None = None
    federation: Federation | None = None
    discovery: Federation
    viewer_context: dict[str, Any]
    snapshots: ViewerSnapshotStore
    captures: CaptureStore

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
        if path in ("/api/guided-investigation", "/api/source-location", "/api/capture-tools"):
            self._workflow_read(path)
        elif path == "/api/snapshots" or path.startswith("/api/snapshots/"):
            self._snapshots("GET")
        elif path == "/" or path == "/index.html":
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
        path = self.path.split("?", 1)[0]
        if path.startswith("/api/capture-tools/"):
            self._capture_tools()
            return
        if path == "/api/snapshots" or path.startswith("/api/snapshots/"):
            self._snapshots("POST")
            return
        if path == "/api/segment-execution":
            # This is pure computation, not an agent mutation. In particular,
            # browser pins must never be replaced by a fresh upstream export.
            self._segment_execution()
            return
        if self.federation:
            self._send_json({"error": "multi-service UI is read-only; use a single-service client for mutations"}, 405)
            return
        if path in ("/api/enable", "/api/disable", "/api/reset"):
            self._proxy("POST", path[len("/api"):])
        elif path.startswith("/api/reset/"):
            self._proxy("POST", "/reset/" + path[len("/api/reset/"):])
        else:
            self._send_json({"error": "not found"}, 404)

    # ---- internals ----

    def do_DELETE(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/snapshots" or path.startswith("/api/snapshots/"):
            self._snapshots("DELETE")
        else:
            self._send_json({"error": "not found"}, 404)

    def _snapshot_origin(self, mutation):
        hosts = self.headers.get_all("Host", [])
        origins = self.headers.get_all("Origin", [])
        port = self.server.server_address[1]
        allowed = {f"127.0.0.1:{port}", f"localhost:{port}"}
        if port == 80:
            allowed.update(("127.0.0.1", "localhost"))
        # Checking both Host and Origin prevents DNS rebinding and drive-by web
        # writes to the loopback listener. No wildcard hosts or CORS are enabled.
        if (len(hosts) != 1 or hosts[0] not in allowed or len(origins) > 1
                or (mutation and len(origins) != 1)
                or (origins and origins[0] != "http://" + hosts[0])
                or self.headers.get("Sec-Fetch-Site") == "cross-site"):
            raise SnapshotError("Snapshot access requires this viewer's local origin.", 403)

    def _snapshot_body(self, max_bytes=SNAPSHOT_MAX_BYTES):
        lengths = self.headers.get_all("Content-Length", [])
        content_types = self.headers.get_all("Content-Type", [])
        encodings = self.headers.get_all("Content-Encoding", [])
        if (len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit()
                or self.headers.get_all("Transfer-Encoding") or len(content_types) != 1
                or self.headers.get_content_type() != "application/json"
                or (self.headers.get_content_charset("utf-8") or "").lower() not in ("utf-8", "utf8")
                or len(encodings) > 1 or (encodings and encodings[0].lower() != "identity")):
            raise SnapshotError("Expected UTF-8 application/json with one valid Content-Length.")
        decimal_length = lengths[0].lstrip("0") or "0"
        if (len(decimal_length) > len(str(max_bytes))
                or int(decimal_length) > max_bytes):
            raise SnapshotError("Request exceeds the byte limit; nothing was saved.", 413)
        length = int(decimal_length)
        if not length:
            raise SnapshotError("Snapshot request body is required.")
        previous_timeout = self.connection.gettimeout()
        self.connection.settimeout(SEGMENT_EXECUTION_READ_TIMEOUT_SECONDS)
        try:
            raw = self.rfile.read(length)
        finally:
            self.connection.settimeout(previous_timeout)
        if len(raw) != length:
            raise SnapshotError("Incomplete snapshot request body.")
        try:
            return strict_json(raw)
        except (ValueError, TypeError, UnicodeError, RecursionError, OverflowError):
            raise SnapshotError("Invalid snapshot JSON; nothing was saved.") from None

    def _workflow_read(self, path):
        """Local, bounded readers. No source path or connection supplied by callers."""
        self.close_connection = True
        try:
            self._snapshot_origin(False)
            lengths = self.headers.get_all("Content-Length", [])
            if (self.headers.get_all("Transfer-Encoding") or self.headers.get_all("Content-Encoding")
                    or len(lengths) > 1 or (lengths and lengths != ["0"])):
                raise ValueError("Workflow reads do not accept a request body.")
            parsed = urllib.parse.urlsplit(self.path)
            values = urllib.parse.parse_qs(parsed.query, keep_blank_values=True, max_num_fields=8)
            if any(len(rows) != 1 for rows in values.values()):
                raise ValueError("Supply each parameter once.")
            params = {key: rows[0] for key, rows in values.items()}
            if path == "/api/guided-investigation":
                from .guided_investigation import diagnose_request
                if set(params) - {"path", "method", "service", "user", "site"} or not params.get("path"):
                    raise ValueError("Supply path and optional method, service, user and site.")
                result = diagnose_request(self.discovery, **params)
            elif path == "/api/source-location":
                if set(params) - {"className", "line", "service"} or not {"className", "line"} <= params.keys():
                    raise ValueError("Supply className, line and the configured service.")
                if not params["line"].isascii() or not params["line"].isdigit() or len(params["line"]) > 7:
                    raise ValueError("Invalid source line.")
                if self.federation:
                    name = params.get("service")
                    if not name:
                        raise ValueError("Choose a configured service for source navigation.")
                    root = self.federation.source_root(name)
                else:
                    if params.get("service") and params["service"] not in self.discovery.services:
                        raise ValueError("Unknown configured service.")
                    root = self.source_root or os.environ.get("TRACELENS_SOURCE_ROOT") or str(source.DEFAULT_SOURCE_ROOT)
                if not root:
                    raise ValueError("No local source root is configured for this service.")
                result = source.source_location(params["className"], int(params["line"]), root)
                if not result:
                    self._send_json({"error": "Source location is unavailable in the configured checkout."}, 404)
                    return
            else:
                if params:
                    raise ValueError("Capture tools listing does not accept query parameters.")
                snapshots = self.snapshots.list()
                captures = self.captures.list()
                result = {"snapshots": snapshots["snapshots"], "captures": captures["captures"],
                          "warnings": snapshots.get("warnings", []) + captures.get("warnings", [])}
            self._send_json(result, 200)
        except SnapshotError as exc:
            self._send_json({"error": str(exc)}, exc.status)
        except (ValueError, KeyError, TypeError) as exc:
            self._send_json({"error": str(exc) if isinstance(exc, ValueError) else "Invalid local workflow parameters."}, 400)
        except (OSError, DebugApiError):
            self._send_json({"error": "Local evidence or configured service is unavailable."}, 503)

    def _capture_tools(self):
        self.close_connection = True
        try:
            from .capture_tools import compare_saved_snapshots, export_to_capture_store, search_saved_evidence
            self._snapshot_origin(True)
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.query or parsed.fragment:
                raise SnapshotError("Capture tools do not accept query parameters.")
            doc = self._snapshot_body(16 * 1024)
            if not isinstance(doc, dict):
                raise SnapshotError("Expected a capture tools request object.")
            if parsed.path == "/api/capture-tools/compare" and set(doc) == {"before", "after"}:
                result = compare_saved_snapshots(self.snapshots, doc["before"], doc["after"])
            elif parsed.path == "/api/capture-tools/project" and "snapshotId" in doc and not set(doc) - {"snapshotId", "label", "revision"}:
                result = export_to_capture_store(self.snapshots, self.captures, doc["snapshotId"],
                                                label=doc.get("label"), revision=doc.get("revision"))
            elif parsed.path == "/api/capture-tools/search" and "query" in doc and not set(doc) - {"query", "category", "limit"}:
                result = search_saved_evidence(self.snapshots, self.captures, **doc)
            else:
                raise SnapshotError("Invalid capture tools route or fields.")
            self._send_json(result, 200)
        except SnapshotError as exc:
            self._send_json({"error": str(exc)}, exc.status)
        except ValueError as exc:
            self._send_json({"error": str(exc)}, 400)
        except (TypeError, KeyError, RecursionError, OverflowError):
            self._send_json({"error": "Saved evidence is invalid or unsupported; no live data was substituted."}, 409)
        except OSError:
            self._send_json({"error": "Private evidence storage is unavailable; existing evidence was not replaced."}, 503)

    def _snapshots(self, method):
        # Never leave an unread/rejected request body on a reusable connection.
        self.close_connection = True
        try:
            self._snapshot_origin(method != "GET")
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.query or parsed.fragment:
                raise SnapshotError("Snapshot routes do not accept query parameters.", 404)
            suffix = parsed.path[len("/api/snapshots"):]
            if method == "GET":
                lengths = self.headers.get_all("Content-Length", [])
                if (self.headers.get_all("Transfer-Encoding") or len(lengths) > 1
                        or (lengths and lengths != ["0"])):
                    raise SnapshotError("Snapshot reads do not accept a request body.")
                result = self.snapshots.list() if not suffix else self.snapshots.get(suffix[1:])
            elif method == "DELETE":
                lengths = self.headers.get_all("Content-Length", [])
                if (self.headers.get_all("Transfer-Encoding") or self.headers.get_all("Content-Encoding")
                        or len(lengths) > 1 or (lengths and lengths != ["0"])):
                    raise SnapshotError("Snapshot deletion does not accept a request body.")
                result = self.snapshots.delete(suffix[1:])
            else:
                doc = self._snapshot_body()
                if not isinstance(doc, dict):
                    raise SnapshotError("Expected a snapshot request object.")
                if not suffix and set(doc) == {"snapshot"}:
                    result = self.snapshots.save(doc["snapshot"])
                elif suffix.endswith("/rename") and set(doc) == {"name"}:
                    result = self.snapshots.rename(suffix[1:-len("/rename")], doc["name"])
                else:
                    raise SnapshotError("Invalid snapshot route or request fields.", 400)
            self._send_json(result, 201 if method == "POST" and not suffix else 200)
        except SnapshotError as exc:
            self._send_json({"error": str(exc)}, exc.status)
        except (ValueError, TypeError, RecursionError, OverflowError):
            self._send_json({"error": "Snapshot data or private storage is invalid; existing snapshots were not replaced."}, 409)
        except OSError:
            # Paths, source text, credentials and upstream errors must not leak.
            self._send_json({"error": "Snapshot storage is unavailable. Check its private directory permissions and free space."}, 503)

    def _segment_execution(self):
        # Close even on rejection: an unread or ambiguously framed body must not
        # become another request on this connection.
        self.close_connection = True
        lengths = self.headers.get_all("Content-Length", [])
        content_types = self.headers.get_all("Content-Type", [])
        if (len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit()
                or self.headers.get_all("Transfer-Encoding")
                or len(content_types) != 1
                or self.headers.get_content_type() != "application/json"
                or self.headers.get_content_charset("utf-8") not in ("utf-8", "utf8")
                or self.headers.get("Content-Encoding", "identity").lower() != "identity"):
            self._send_json({"error": "expected UTF-8 application/json with one valid Content-Length"}, 400)
            return
        # Avoid parsing arbitrarily long decimal strings while permitting the
        # leading zeroes allowed by HTTP's Content-Length grammar.
        decimal_length = lengths[0].lstrip("0") or "0"
        if (len(decimal_length) > len(str(SEGMENT_EXECUTION_REQUEST_MAX_BYTES))
                or int(decimal_length) > SEGMENT_EXECUTION_REQUEST_MAX_BYTES):
            self._send_json({"error": "segment-execution request exceeds 5 MiB"}, 413)
            return
        length = int(decimal_length)
        if length == 0:
            self._send_json({"error": "segment-execution request body is required"}, 400)
            return

        def reject_constant(_value):
            raise ValueError("non-JSON numeric constant")

        def unique_object(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError("duplicate JSON key")
                result[key] = value
            return result

        try:
            previous_timeout = self.connection.gettimeout()
            self.connection.settimeout(SEGMENT_EXECUTION_READ_TIMEOUT_SECONDS)
            try:
                body = self.rfile.read(length)
            finally:
                self.connection.settimeout(previous_timeout)
            if len(body) != length:
                raise ValueError("incomplete body")
            request = json.loads(body.decode("utf-8"), parse_constant=reject_constant,
                                 object_pairs_hook=unique_object)
            if (not isinstance(request, dict) or set(request) - {"trace", "maxNodes"}
                    or not isinstance(request.get("trace"), dict)):
                raise ValueError("expected a trace object and optional maxNodes")
            max_nodes = request.get("maxNodes", DEFAULT_MAX_NODES)
            validate_max_nodes(max_nodes)
            model = build_segment_execution(request["trace"], max_nodes=max_nodes,
                                            max_model_bytes=VIEWER_MODEL_MAX_BYTES)
        except (OSError, ValueError, TypeError, RecursionError, OverflowError):
            # Do not echo uploaded trace fields, parser excerpts or exception
            # text: saved evidence can contain private payloads.
            self._send_json({"error": "invalid segment-execution request; supply a trace object and optional maxNodes integer (1-500)"}, 400)
            return
        self._send_json(model, 200)

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
        if self.path.startswith(("/api/snapshots", "/api/capture-tools", "/api/guided-investigation", "/api/source-location")):
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)


def make_server(port: int, upstream: str, source_root: str | None,
                services: list[Service] | None = None,
                snapshot_dir: str | Path | None = None,
                capture_dir: str | Path | None = None) -> ThreadingHTTPServer:
    discovery = Federation(services or [Service("default", upstream, source_root)])
    viewer_context = _viewer_context(upstream, source_root, services)
    handler = type("TraceLensHandler", (_Handler,), {
        "upstream": upstream,
        "source_root": source_root,
        "federation": discovery if services is not None else None,
        "discovery": discovery,
        "viewer_context": viewer_context,
        "snapshots": ViewerSnapshotStore(snapshot_dir or default_snapshot_dir(), viewer_context["namespace"]),
        "captures": CaptureStore(capture_dir or default_capture_dir()),
    })
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    return server


def serve(port: int, upstream: str, source_root: str | None,
          services: list[Service] | None = None,
          snapshot_dir: str | Path | None = None,
          capture_dir: str | Path | None = None) -> int:
    server = make_server(port, upstream, source_root, services, snapshot_dir, capture_dir)
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
