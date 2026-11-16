"""Read-only diagnosis and offline saved-evidence CLI/MCP entrypoints."""

import copy
import json
from types import SimpleNamespace
from urllib import request

import pytest

from tracelens_cli import capture_tools, captures, cli, guided_investigation
from tracelens_cli.mcp_server import TraceLensMcpServer


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def forbidden(*_args, **_kwargs):
        pytest.fail("entrypoint fixture must not access a real debug service")
    monkeypatch.setattr(request, "urlopen", forbidden)
    monkeypatch.setattr(request, "build_opener", forbidden)


def server(tmp_path):
    return TraceLensMcpServer("http://unused", None, capture_dir=str(tmp_path / "captures"))


def saved_capture(tmp_path):
    trace, segment = "a" * 32, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    row = {"traceId": trace, "segmentId": segment, "serviceInstanceId": "instance",
           "httpMethod": "GET", "path": "/features?token=SECRET_QUERY", "status": "COMPLETED",
           "httpStatus": 200, "durationMs": 4, "user": "SECRET_USER",
           "events": [{"seq": 1, "type": "METHOD_ENTER", "className": "example.FeatureService",
                       "methodName": "retrieve", "thread": "request", "nanoTime": 1},
                      {"seq": 2, "type": "EXCEPTION", "className": "example.FeatureService",
                       "methodName": "retrieve", "thread": "request", "nanoTime": 2,
                       "attributes": {"exceptionType": "example.LookupException", "message": "SECRET_MESSAGE"}}]}

    class Client:
        def find_by_trace_id(self, trace_id):
            assert trace_id == trace
            return {"traces": [{key: value for key, value in row.items() if key != "events"}]}

        def export(self, segment_id, evict=None):
            assert segment_id == segment and evict is False
            return copy.deepcopy(row)

    discovery = SimpleNamespace(services={"app": object()}, clients={"app": Client()})
    store = captures.CaptureStore(tmp_path / "captures")
    store.save(captures.build_capture(discovery, trace, "Baseline"))
    return store


def test_cli_diagnose_passes_exact_filters_and_global_service(monkeypatch, capsys):
    calls = []
    def diagnose(discovery, **filters):
        calls.append((list(discovery.services), filters))
        return {"kind": "guided-investigation", "status": "no_retained_match"}
    monkeypatch.setattr(guided_investigation, "diagnose_request", diagnose)
    assert cli.main(["--url", "http://unused", "--service", "default", "diagnose",
                     "--path", "/features", "--method", "GET", "--user", "Alice", "--site", "42"]) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "no_retained_match"
    assert calls == [(["default"], {"path": "/features", "method": "GET", "service": "default",
                                    "user": "Alice", "site": "42"})]


def test_cli_diagnose_requires_path_and_invalid_path_never_reads_live_data(capsys):
    with pytest.raises(SystemExit) as stopped:
        cli.main(["diagnose"])
    assert stopped.value.code == 2
    assert cli.main(["--url", "http://unused", "diagnose", "--path", "/features?secret=x"]) == 1
    assert "exact absolute backend path" in capsys.readouterr().err


def test_cli_saved_search_uses_only_minimized_store_and_does_not_mutate_files(tmp_path, capsys):
    store = saved_capture(tmp_path)
    before = {path.name: path.read_bytes() for path in store.root.iterdir()}
    assert cli.main(["--url", "http://unused", "--capture-dir", str(store.root),
                     "capture", "search", "FeatureService", "--category", "method", "--limit", "1"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["returned"] == result["recordsScanned"] == 1
    assert result["results"][0]["storage"] == "capture"
    assert result["results"][0]["methodName"] == "retrieve"
    assert result["eventsProcessed"] == 0
    assert "SECRET" not in json.dumps(result)
    assert {path.name: path.read_bytes() for path in store.root.iterdir()} == before


def test_cli_saved_search_default_limit_and_store_authority(monkeypatch, capsys, tmp_path):
    calls = []
    def search(snapshot_store, capture_store, query, category, limit):
        calls.append((snapshot_store, capture_store.root, query, category, limit))
        return {"returned": 0}
    monkeypatch.setattr(capture_tools, "search_saved_evidence", search)
    assert cli.main(["--capture-dir", str(tmp_path / "captures"), "capture", "search", "needle"]) == 0
    assert calls == [(None, tmp_path / "captures", "needle", None, 50)]
    assert json.loads(capsys.readouterr().out) == {"returned": 0}
    assert not (tmp_path / "captures").exists()


@pytest.mark.parametrize("query,limit", [("", "50"), ("x" * 129, "50"), ("needle", "0"), ("needle", "101")])
def test_cli_saved_search_validation_precedes_store_reads(tmp_path, capsys, query, limit):
    assert cli.main(["--capture-dir", str(tmp_path / "missing"), "capture", "search", query, "--limit", limit]) == 1
    assert capsys.readouterr().err.startswith("error:")
    assert not (tmp_path / "missing").exists()


def test_cli_serve_passes_shared_capture_directory(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr("tracelens_cli.server.serve", lambda *args, **kwargs: calls.append((args, kwargs)) or 0)
    assert cli.main(["--capture-dir", str(tmp_path / "captures"), "serve", "--port", "7781",
                     "--snapshot-dir", str(tmp_path / "viewer")]) == 0
    assert calls[0][0][-1] == str(tmp_path / "viewer")
    assert calls[0][1] == {"capture_dir": str(tmp_path / "captures")}


def test_mcp_tools_have_strict_read_only_schemas_and_no_path_authority(tmp_path):
    tools = {tool["name"]: tool for tool in server(tmp_path)._tools()}
    for name in ("diagnose_capture_request", "search_saved_captures"):
        assert tools[name]["annotations"]["readOnlyHint"] is True
        assert tools[name]["annotations"]["destructiveHint"] is False
        assert tools[name]["inputSchema"]["additionalProperties"] is False
    diagnosis = tools["diagnose_capture_request"]["inputSchema"]
    assert diagnosis["required"] == ["path"]
    assert set(diagnosis["properties"]) == {"path", "method", "service", "user", "site"}
    assert diagnosis["properties"]["path"]["maxLength"] == 2048
    search = tools["search_saved_captures"]["inputSchema"]
    assert search["required"] == ["query"]
    assert set(search["properties"]) == {"query", "category", "limit"}
    assert search["properties"]["category"]["enum"] == ["class", "method", "exception", "destination"]
    assert search["properties"]["limit"] == {"type": "integer", "minimum": 1, "maximum": 100, "default": 50}


def test_mcp_diagnosis_forwards_exact_filters_and_preserves_legacy_fallback(monkeypatch, tmp_path):
    calls, instance = [], server(tmp_path)
    model = {"kind": "guided-investigation", "status": "no_retained_match"}
    def diagnose(discovery, **filters):
        assert discovery is instance.discovery
        calls.append(filters)
        return model
    monkeypatch.setattr(guided_investigation, "diagnose_request", diagnose)
    filters = {"path": "/features", "method": "GET", "service": "default", "user": "Alice", "site": "42"}
    message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
               "params": {"name": "diagnose_capture_request", "arguments": filters}}
    modern = instance.handle_message(message)["result"]
    assert modern["structuredContent"] == model and not modern["isError"]
    assert json.loads(modern["content"][0]["text"]) == model
    instance.protocol_version = "2024-11-05"
    legacy = instance.handle_message(message)["result"]
    assert "structuredContent" not in legacy
    assert json.loads(legacy["content"][0]["text"]) == model
    assert calls == [filters, filters]


@pytest.mark.parametrize("arguments", [
    {}, {"path": "/api?secret=x"}, {"path": "/api", "service": "missing"},
    {"path": "/api", "method": []}, {"path": "/api", "site": "x" * 257},
    {"path": "/api", "user": "bad\nuser"}, {"path": "/api", "url": "http://attacker"},
])
def test_mcp_diagnosis_rejects_invalid_arguments_before_network(tmp_path, arguments):
    assert server(tmp_path)._dispatch_tool("diagnose_capture_request", arguments)["isError"]


def test_mcp_saved_search_is_offline_minimized_and_returns_matching_text_json(tmp_path):
    store = saved_capture(tmp_path)
    instance = server(tmp_path)
    result = instance._dispatch_tool("search_saved_captures", {"query": "lookupexception", "category": "exception"})
    assert not result["isError"]
    assert json.loads(result["content"][0]["text"]) == result["structuredContent"]
    doc = result["structuredContent"]
    assert doc["returned"] == 1 and doc["eventsProcessed"] == 0
    assert doc["results"][0]["exceptionType"] == "example.LookupException"
    assert doc["results"][0]["storage"] == "capture"
    assert "SECRET" not in json.dumps(result)
    assert store.list()["count"] == 1


@pytest.mark.parametrize("arguments", [
    {}, {"query": ""}, {"query": "x" * 129}, {"query": "bad\nquery"}, {"query": "needle", "limit": True},
    {"query": "needle", "limit": 101}, {"query": "needle", "category": "all"},
    {"query": "needle", "category": "payload"}, {"query": "needle", "capture_dir": "/tmp/elsewhere"},
])
def test_mcp_search_rejects_invalid_arguments_before_store_reads(tmp_path, arguments):
    instance = server(tmp_path)
    instance.capture_store.list = lambda: pytest.fail("invalid arguments must not read files")
    assert instance._dispatch_tool("search_saved_captures", arguments)["isError"]
    assert not (tmp_path / "captures").exists()


@pytest.mark.parametrize("tool,module_function,arguments", [
    ("diagnose_capture_request", "tracelens_cli.guided_investigation.diagnose_request", {"path": "/api"}),
    ("search_saved_captures", "tracelens_cli.capture_tools.search_saved_evidence", {"query": "needle"}),
])
def test_mcp_new_tools_bound_transport_response(monkeypatch, tmp_path, tool, module_function, arguments):
    monkeypatch.setattr(module_function, lambda *_args, **_kwargs: {"oversized": "x" * (256 * 1024)})
    result = server(tmp_path)._dispatch_tool(tool, arguments)
    assert result["isError"]
    assert "256 KiB" in result["content"][0]["text"]
