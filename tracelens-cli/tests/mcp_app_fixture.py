"""Manual browser QA host for the actual bundled, sandboxed MCP Apps resource.

Run: python3 tracelens-cli/tests/mcp_app_fixture.py [--port 0]
The URL printed on startup is loopback-only. No business services are contacted;
all tool results come from the existing deterministic synthetic fixture rows.
This is an emulated host, not a claim that a particular MCP client supports Apps.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tracelens_cli.mcp_app import app_resource
from tracelens_cli.services import join_trace
from tracelens_cli.visualization import build_visualization
from ui_fixture import FEATURE_ERROR_TRACE, FEATURE_OK_TRACE, fixture_rows


def fixture_models():
    rows = fixture_rows(now_ms=1_800_000_000_000)

    def segments(trace_id):
        selected = []
        for service, values in rows.items():
            for row in values:
                if row["traceId"] != trace_id:
                    continue
                tagged = copy.deepcopy(row)
                tagged["service"] = service
                tagged["segmentKey"] = json.dumps(
                    [service, row["serviceInstanceId"], row["segmentId"]], separators=(",", ":"))
                selected.append(tagged)
        return selected

    full = segments(FEATURE_OK_TRACE)
    partial = [row for row in full if row["service"] == "cb-app"]
    unsafe = copy.deepcopy(full)
    unsafe[0]["path"] = '<img src=x onerror="window.__fixtureXss=true">'
    unsafe[0]["attributes"]["path"] = unsafe[0]["path"]
    return {
        "full": build_visualization(join_trace(FEATURE_OK_TRACE, full, [])),
        "partial": build_visualization(join_trace(FEATURE_OK_TRACE, partial, [
            {"service": "cb-ems", "error": "Synthetic fixture: service evidence unavailable"}])),
        "error": build_visualization(join_trace(FEATURE_ERROR_TRACE, segments(FEATURE_ERROR_TRACE), [])),
        "empty": build_visualization(join_trace(FEATURE_OK_TRACE, [], [])),
        "unsafe": build_visualization(join_trace(FEATURE_OK_TRACE, unsafe, [])),
    }


HOST_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TraceLens MCP Apps manual QA fixture</title>
<style>
:root { color-scheme: dark; font: 14px/1.5 system-ui,sans-serif; background:#0d1117; color:#e6edf3; }
body { max-width:1300px; margin:0 auto; padding:20px; }
h1 { font-size:21px; margin:0 0 6px; } p { margin:5px 0 12px; color:#a4afbf; }
nav { display:flex; gap:8px; flex-wrap:wrap; margin:12px 0; }
button { background:#1b2637; color:#e6edf3; border:1px solid #42556f; border-radius:5px; padding:7px 11px; cursor:pointer; }
button:focus-visible { outline:2px solid #58a6ff; outline-offset:3px; }
button[aria-pressed=true] { border-color:#58a6ff; color:#79baff; }
#host-status { font-family:ui-monospace,monospace; color:#9fca91; }
#protocol-log { white-space:pre-wrap; overflow-wrap:anywhere; max-height:170px; overflow:auto; color:#a4afbf; font:11px/1.5 ui-monospace,monospace; }
iframe { display:block; width:100%; height:850px; border:1px solid #42556f; border-radius:8px; background:#101827; }
</style></head><body>
<h1>MCP Apps sandbox fixture</h1>
<p>Actual bundled request-flow resource inside a sandboxed iframe. Synthetic retained evidence only; no business services or MCP client connection.</p>
<nav aria-label="Fixture controls">
<button type="button" data-result="full" aria-pressed="true">Full request</button>
<button type="button" data-result="partial" aria-pressed="false">Partial result</button>
<button type="button" data-result="error" aria-pressed="false">HTTP error</button>
<button type="button" data-result="empty" aria-pressed="false">Empty result</button>
<button type="button" data-result="unsafe" aria-pressed="false">Inert HTML label</button>
<button type="button" id="theme-toggle">Use light theme</button>
<button type="button" id="malformed">Malformed result</button>
<button type="button" id="cancel">Cancel notification</button>
</nav>
<p id="host-status" role="status">Waiting for the sandboxed app to initialize…</p>
<iframe id="app-frame" title="TraceLens MCP Apps request-flow resource" sandbox="allow-scripts"></iframe>
<details><summary>Host protocol log</summary><pre id="protocol-log"></pre></details>
<script id="fixture-data" type="application/json">__FIXTURE_MODELS__</script>
<script>
"use strict";
const models = JSON.parse(document.getElementById("fixture-data").textContent);
const frame = document.getElementById("app-frame");
const status = document.getElementById("host-status");
const log = document.getElementById("protocol-log");
let ready = false, theme = "dark", current = "full";
function record(text) { log.textContent = (log.textContent + text + "\\n").slice(-10000); }
function post(message) {
  // allow-scripts without allow-same-origin gives the app an opaque origin.
  frame.contentWindow.postMessage({jsonrpc:"2.0", ...message}, "*");
  record("host → " + (message.method || ("response " + message.id)));
}
function sendResult(name) {
  current = name;
  document.querySelectorAll("[data-result]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.result === name)));
  if (!ready) return;
  post({method:"ui/notifications/tool-result", params:{
    content:[{type:"text", text:"Synthetic manual-QA result: " + name}], structuredContent:models[name], isError:false
  }});
  status.textContent = "Bridge ready · " + name + " result sent · " + theme + " theme · synthetic data only";
}
window.addEventListener("message", event => {
  if (event.source !== frame.contentWindow || !event.data || event.data.jsonrpc !== "2.0") return;
  const message = event.data;
  record("app → " + (message.method || ("response " + message.id)));
  if (message.method === "ui/initialize") {
    post({id:message.id, result:{protocolVersion:"2026-01-26", hostInfo:{name:"TraceLens manual QA host", version:"1.0.0"},
      hostCapabilities:{}, hostContext:{theme}}});
  } else if (message.method === "ui/notifications/initialized") {
    ready = true;
    sendResult(current);
  } else if (message.method === "ui/notifications/size-changed") {
    const height = Number(message.params && message.params.height);
    if (Number.isFinite(height)) frame.style.height = Math.min(1500, Math.max(250, height + 2)) + "px";
  } else if (message.id !== undefined && message.method) {
    post({id:message.id, error:{code:-32601, message:"Manual QA host supports only UI initialization"}});
  }
});
document.querySelectorAll("[data-result]").forEach(button => button.addEventListener("click", () => sendResult(button.dataset.result)));
document.getElementById("theme-toggle").addEventListener("click", event => {
  theme = theme === "dark" ? "light" : "dark";
  event.currentTarget.textContent = theme === "dark" ? "Use light theme" : "Use dark theme";
  if (ready) post({method:"ui/notifications/host-context-changed", params:{theme}});
  status.textContent = "Bridge ready · " + current + " result · " + theme + " theme · synthetic data only";
});
document.getElementById("malformed").addEventListener("click", () => {
  if (ready) post({method:"ui/notifications/tool-result", params:{structuredContent:{schemaVersion:999}, isError:false}});
  status.textContent = "Malformed result sent; the app should explain the text fallback.";
});
document.getElementById("cancel").addEventListener("click", () => {
  if (ready) post({method:"ui/notifications/tool-cancelled", params:{}});
  status.textContent = "Cancellation notification sent; no request was replayed.";
});
frame.src = "/app";
</script></body></html>
"""


def make_fixture_server(port=0):
    encoded = json.dumps(fixture_models(), ensure_ascii=True).replace("<", "\\u003c")
    host = HOST_HTML.replace("__FIXTURE_MODELS__", encoded).encode("utf-8")
    app = app_resource()["text"].encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/":
                payload, status = host, 200
            elif path == "/app":
                payload, status = app, 200
            else:
                payload, status = b"Fixture route not found", 404
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    server = make_fixture_server(args.port)
    print(f"http://127.0.0.1:{server.server_port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
