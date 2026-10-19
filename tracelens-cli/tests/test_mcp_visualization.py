"""Hybrid surfaces share a read-only model; the Apps transport never fetches data."""

import copy
import json
from pathlib import Path
import shutil
import subprocess

import pytest

from tracelens_cli import cli
from tracelens_cli.client import DebugApiError
from tracelens_cli.mcp_app import APP_MIME_TYPE, APP_RESOURCE_URI, app_resource, validate_viewer_url
from tracelens_cli.mcp_server import TraceLensMcpServer
from tracelens_cli.services import Service
from tracelens_cli.visualization import build_visualization


TRACE_ID = "1234567890abcdef1234567890abcdef"


def _segment(segment_id, span, parent=None):
    return {"traceId": TRACE_ID, "segmentId": segment_id, "spanId": span,
            "parentSpanId": parent, "serviceInstanceId": "runtime-1", "lensId": "GET /orders",
            "status": "COMPLETED", "entryChannel": "HTTP", "durationMs": 8,
            "httpMethod": "GET", "path": "/orders", "events": [], "eventCount": 0,
            "httpCalls": []}


class ReadOnlyClient:
    def __init__(self, rows):
        self.rows = rows
        self.exports = []

    def find_by_trace_id(self, trace_id):
        assert trace_id == TRACE_ID
        return {"traces": copy.deepcopy(self.rows)}

    def export(self, segment_id, evict):
        self.exports.append((segment_id, evict))
        assert evict is False
        return copy.deepcopy(next(row for row in self.rows if row["segmentId"] == segment_id))


def _server(viewer_url=None):
    server = TraceLensMcpServer("http://unused", None,
                               [Service("app", "http://unused-app"), Service("ems", "http://unused-ems")],
                               viewer_url=viewer_url)
    parent = _segment("segment-app", "1111111111111111")
    child = _segment("segment-ems", "3333333333333333", "2222222222222222")
    parent["httpCalls"] = [{"spanId": "2222222222222222", "httpMethod": "GET",
                            "path": "/orders", "peer": "ems", "durationNanos": 6000000,
                            "status": "COMPLETED", "httpStatus": 200}]
    server.discovery.clients = {"app": ReadOnlyClient([parent]), "ems": ReadOnlyClient([child])}
    return server


def _rpc(server, method, params=None):
    return server.handle_message({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}})


def _initialize(server, version="2025-06-18", apps=False):
    caps = {"extensions": {"io.modelcontextprotocol/ui": {"mimeTypes": [APP_MIME_TYPE]}}} if apps else {}
    return _rpc(server, "initialize", {"protocolVersion": version, "capabilities": caps})


def test_data_and_render_tools_share_the_exact_canonical_graph_and_read_only_exports():
    server = _server()
    _initialize(server, apps=True)
    expected = build_visualization(server.discovery.get_distributed_trace(TRACE_ID))
    data = server.tool_get_trace_visualization(TRACE_ID)
    shown = server.tool_show_trace_visualization(TRACE_ID)
    assert data["structuredContent"] == shown["structuredContent"] == expected
    assert data["content"] == shown["content"]
    assert data["isError"] is False
    assert "_meta" not in data
    assert shown["_meta"] == {"ui": {"resourceUri": APP_RESOURCE_URI}}
    text = data["content"][0]["text"]
    assert "```mermaid\nflowchart LR" in text
    assert "#trace=" + TRACE_ID in text
    assert "cross-JVM clocks are not synchronized" in text
    assert "get_flow" in text
    assert all(evict is False for client in server.discovery.clients.values() for _, evict in client.exports)


def test_apps_metadata_only_on_render_tool_and_only_when_negotiated():
    server = _server()
    for apps in (False, True):
        result = _initialize(server, apps=apps)["result"]
        assert result["protocolVersion"] == "2025-06-18"
        tools = {tool["name"]: tool for tool in _rpc(server, "tools/list")["result"]["tools"]}
        with_ui = [name for name, tool in tools.items() if "ui" in tool.get("_meta", {})]
        assert with_ui == (["show_trace_visualization"] if apps else [])
        for name in ("get_trace_visualization", "show_trace_visualization"):
            assert tools[name]["annotations"]["readOnlyHint"] is True
            assert tools[name]["inputSchema"]["additionalProperties"] is False
            assert set(tools[name]["inputSchema"]["properties"]) == {"trace_id"}


def test_legacy_protocol_preserves_text_fallback_and_existing_tools():
    server = _server()
    initialized = _initialize(server, "2024-11-05", apps=True)["result"]
    assert initialized["protocolVersion"] == "2024-11-05"
    result = _rpc(server, "tools/call", {"name": "show_trace_visualization", "arguments": {"trace_id": TRACE_ID}})["result"]
    assert "structuredContent" not in result
    assert "_meta" not in result
    assert "```mermaid" in result["content"][0]["text"]
    names = {tool["name"] for tool in _rpc(server, "tools/list")["result"]["tools"]}
    assert {"find_actions", "get_action", "list_flows", "get_flow", "get_flow_summary", "get_flow_mermaid",
            "search_flows", "flow_status", "find_distributed_traces", "get_distributed_trace"} <= names
    assert _initialize(server, "2099-01-01")["result"]["protocolVersion"] == "2025-06-18"


def test_resource_is_static_known_uri_only_and_self_contained():
    server = _server()
    resources = _rpc(server, "resources/list")["result"]["resources"]
    assert len(resources) == 1 and resources[0]["uri"] == APP_RESOURCE_URI
    result = _rpc(server, "resources/read", {"uri": APP_RESOURCE_URI})["result"]["contents"][0]
    assert result["mimeType"] == APP_MIME_TYPE
    assert result["_meta"]["ui"]["csp"] == {"connectDomains": [], "resourceDomains": [], "frameDomains": []}
    html = result["text"]
    assert html.startswith("<!doctype html>")
    assert "TraceLensGraph" in html and "ui/initialize" in html
    assert "connect-src 'none'" in html
    assert "<script src=" not in html and "<link " not in html
    assert TRACE_ID not in html
    server.tool_get_trace_visualization(TRACE_ID)
    assert app_resource()["text"] == html  # No per-request data interpolated into HTML.
    for uri in ("ui://tracelens/../../mcp_server.py", "file:///etc/passwd", APP_RESOURCE_URI + "?file=../x", None):
        assert _rpc(server, "resources/read", {"uri": uri})["error"]["code"] == -32002


def test_invalid_ids_and_upstream_errors_do_not_kill_mcp():
    server = _server()
    for value in ("../x", "http://evil/", "x\n```", "", 42, None):
        result = server._dispatch_tool("get_trace_visualization", {"trace_id": value})
        assert result["isError"] is True
        assert "structuredContent" not in result
    result = server._dispatch_tool("get_trace_visualization", {"trace_id": TRACE_ID, "viewer_url": "http://evil"})
    assert result["isError"] is True
    server.discovery.get_distributed_trace = lambda _: (_ for _ in ()).throw(DebugApiError("offline"))
    assert server._dispatch_tool("show_trace_visualization", {"trace_id": TRACE_ID})["isError"] is True
    assert _rpc(server, "ping")["result"] == {}


def test_missing_or_partial_evidence_stays_explicit_without_invented_nodes():
    server = _server()
    server.discovery.get_distributed_trace = lambda _: {
        "traceId": TRACE_ID, "segments": [], "httpCalls": [], "links": [],
        "partial": True, "completeness": "not-observed", "warnings": ["Trace may have been evicted"],
        "errors": [{"service": "ems", "error": "offline"}]}
    result = server.tool_get_trace_visualization(TRACE_ID)
    assert result["structuredContent"]["nodes"] == []
    assert result["structuredContent"]["partial"] is True
    assert "PARTIAL" in result["content"][0]["text"]
    assert "evicted" in result["content"][0]["text"]


@pytest.mark.parametrize("url", ["javascript:alert(1)", "file:///tmp", "http://user:pass@localhost:7777",
                                     "http://localhost:7777/view", "http://localhost:7777?url=x",
                                     "http://localhost:7777#x", "//localhost:7777", "http://host:0",
                                     "http://host:99999", "http://host:", "http://host\\@evil",
                                     "http://local\nhost:7777", "https://bad%2fhost"])
def test_viewer_url_is_a_fixed_operator_owned_http_origin(url):
    with pytest.raises(ValueError):
        TraceLensMcpServer("http://unused", None, viewer_url=url)


def test_viewer_url_is_optional_and_does_not_change_graph_data():
    assert validate_viewer_url(None) is None
    assert validate_viewer_url("https://viewer.example") == "https://viewer.example/"
    assert validate_viewer_url("http://[::1]:7777/") == "http://[::1]:7777/"
    local = _server().tool_get_trace_visualization(TRACE_ID)
    linked = _server("http://localhost:7777").tool_get_trace_visualization(TRACE_ID)
    assert local["structuredContent"] == linked["structuredContent"]
    assert "http://localhost:7777/#trace=" + TRACE_ID in linked["content"][0]["text"]


def test_cli_passes_viewer_url_only_from_operator_configuration(monkeypatch):
    calls = []
    monkeypatch.setattr("tracelens_cli.mcp_server.serve", lambda *args: calls.append(args) or 0)
    assert cli.main(["--url", "http://localhost:7778", "mcp", "--viewer-url", "http://localhost:7777"]) == 0
    assert calls == [("http://localhost:7778", None, None, "http://localhost:7777", None)]


def test_malformed_rpc_parameters_return_errors_without_reading_assets():
    server = _server()
    assert server.handle_message([])["error"]["code"] == -32600
    assert server.handle_message({"id": 3, "method": "resources/read", "params": []})["error"]["code"] == -32602
    assert _rpc(server, "initialize", {"capabilities": "bad"})["error"]["code"] == -32602
    assert _rpc(server, "tools/call", {"name": [], "arguments": {}})["result"]["isError"] is True
    assert _rpc(server, "tools/call", {"name": "get_trace_visualization", "arguments": []})["result"]["isError"] is True


def test_browser_bridge_validates_parent_handshake_data_and_teardown():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node is required for the offline browser-bridge protocol test")
    bridge = Path(__file__).parents[1] / "tracelens_cli/web/mcp-app.js"
    script = r"""
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
function make() {
  const sent = [], listeners = new Map(), timers = new Map(), frames = new Map(), renders = [];
  let cleaned = 0, disconnected = 0, next = 1;
  const parent = {postMessage(message, origin) { sent.push({message, origin}); }};
  const status = {textContent: ''}, graph = {replaceChildren() {}};
  const window = {parent, addEventListener(name, fn) {listeners.set(name, fn);}, removeEventListener(name) {listeners.delete(name);},
    TraceLensGraph: {render(container, model, options) {renders.push(model); return () => cleaned++;}}};
  const document = {body: {scrollHeight: 420}, documentElement: {clientWidth: 720, dataset: {}},
    getElementById(id) {return id === 'app-status' ? status : graph;}};
  const context = {window, document, Set, JSON, ResizeObserver: class {observe() {} disconnect() {disconnected++;}},
    setTimeout(fn) {const n = next++; timers.set(n, fn); return n;}, clearTimeout(n) {timers.delete(n);},
    requestAnimationFrame(fn) {const n = next++; frames.set(n, fn); return n;}, cancelAnimationFrame(n) {frames.delete(n);}};
  vm.runInNewContext(fs.readFileSync(process.argv[1], 'utf8'), context);
  const emit = (data, source=parent, origin='https://host.example') => listeners.get('message')?.({data, source, origin});
  const init = () => emit({jsonrpc:'2.0', id:'tracelens-ui-initialize', result:{protocolVersion:'2026-01-26', hostInfo:{name:'fixture',version:'1'},hostCapabilities:{},hostContext:{theme:'light'}}});
  return {sent,listeners,timers,frames,renders,status,document,emit,init,cleaned:()=>cleaned,disconnected:()=>disconnected};
}
const model = {schemaVersion:1,kind:'request-flow',traceId:'trace-1',nodes:[{id:'n1',kind:'segment'}],edges:[],roots:['n1'],warnings:[],partial:false};
const view = make();
assert.equal(view.sent[0].message.method, 'ui/initialize');
assert.equal(view.sent[0].message.params.protocolVersion, '2026-01-26');
assert.equal(view.sent[0].message.params.appInfo.name, 'TraceLens request flow');
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{structuredContent:model}});
assert.equal(view.renders.length, 0);
view.init();
assert.equal(view.sent[1].message.method, 'ui/notifications/initialized');
assert.equal(view.document.documentElement.dataset.theme, 'light');
assert.equal(view.timers.size, 0);
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{structuredContent:model}}, {}, 'https://host.example');
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{structuredContent:model}}, undefined, 'https://evil.example');
assert.equal(view.renders.length, 0);
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{structuredContent:model}});
assert.equal(view.renders.length, 1);
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{structuredContent:{...model,edges:[{id:'bad',from:'n1',to:'missing',kind:'outbound'}]}}});
assert.equal(view.renders.length, 1);
assert.equal(view.cleaned(), 1);
assert.match(view.status.textContent, /supported request graph/);
view.emit({jsonrpc:'2.0',method:'ui/notifications/tool-result',params:{isError:true}});
assert.match(view.status.textContent, /lookup failed/);
for (const fn of view.frames.values()) fn();
assert.ok(view.sent.some(x => x.message.method === 'ui/notifications/size-changed'));
view.emit({jsonrpc:'2.0',id:3,method:'tools/call',params:{name:'reset'}});
assert.equal(view.sent.at(-1).message.error.code, -32601);
view.emit({jsonrpc:'2.0',id:4,method:'ui/resource-teardown',params:{}});
assert.equal(view.sent.at(-1).message.id, 4);
assert.equal(view.listeners.size, 0);
assert.equal(view.disconnected(), 1);
assert.ok(!view.sent.some(x => ['tools/call','resources/read','ui/open-link','ui/message'].includes(x.message.method)));
const timed = make(); [...timed.timers.values()][0]();
assert.match(timed.status.textContent, /did not respond/);
assert.equal(timed.listeners.size, 0);
const unsupported = make();
unsupported.emit({jsonrpc:'2.0',id:'tracelens-ui-initialize',result:{protocolVersion:'bad',hostInfo:{},hostCapabilities:{}}});
assert.match(unsupported.status.textContent, /does not support/);
assert.equal(unsupported.listeners.size, 0);
"""
    result = subprocess.run([node, "-e", script, str(bridge)], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
