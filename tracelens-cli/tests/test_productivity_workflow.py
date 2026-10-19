"""Public CLI/MCP workflow wiring, persistence authority and ambiguity contract."""

import json
import pytest

from tracelens_cli import cli, workflow
from tracelens_cli.mcp_server import TraceLensMcpServer


class Store:
    def __init__(self):
        self.saved = []
        self.rows = [{"captureId": "id-one", "label": "baseline"},
                     {"captureId": "id-two", "label": "candidate"}]

    def list(self):
        return {"captures": self.rows, "count": len(self.rows)}

    def get(self, capture_id):
        return {"captureId": capture_id}

    def save(self, capture):
        self.saved.append(capture)
        return {"captureId": capture["captureId"]}


def test_names_resolve_exactly_and_duplicate_names_never_choose_latest():
    store = Store()
    assert workflow.resolve_capture(store, "baseline") == {"captureId": "id-one"}
    assert workflow.resolve_capture(store, "id-two") == {"captureId": "id-two"}
    store.rows.append({"captureId": "id-three", "label": "baseline"})
    with pytest.raises(ValueError, match="ambiguous"):
        workflow.resolve_capture(store, "baseline")
    assert workflow.resolve_capture(store, "id-one")["captureId"] == "id-one"
    with pytest.raises(ValueError, match="not found"):
        workflow.resolve_capture(store, "base")


@pytest.mark.parametrize("reference", [None, {}, "", "x" * 129])
def test_bad_capture_references(reference):
    with pytest.raises(ValueError):
        workflow.resolve_capture(Store(), reference)


@pytest.mark.parametrize("status", ["ambiguous", "not_found"])
def test_capture_refuses_to_save_without_selected_request(monkeypatch, status):
    store = Store()
    monkeypatch.setattr(workflow, "investigate_request", lambda *a, **k: {"status": status})
    monkeypatch.setattr(workflow, "build_capture", lambda *a, **k: pytest.fail("unexpected export"))
    assert workflow.save_request_capture(object(), store, "baseline", path="/features") == {
        "status": status, "saved": False}
    assert store.saved == []


def test_capture_selection_then_explicit_save(monkeypatch):
    store = Store()
    seen = []
    monkeypatch.setattr(workflow, "investigate_request", lambda *a, **k: {
        "status": "selected", "selectedTraceId": "trace-one"})

    def build(discovery, trace_id, name, revision):
        seen.append((trace_id, name, revision))
        return {"captureId": "saved-one", "quality": {"warnings": ["observed only"]}}

    monkeypatch.setattr(workflow, "build_capture", build)
    result = workflow.save_request_capture(object(), store, "baseline", "build-1", path="/features")
    assert result["saved"] is True
    assert result["warnings"] == ["observed only"]
    assert seen == [("trace-one", "baseline", "build-1")]
    assert len(store.saved) == 1


@pytest.mark.parametrize("name,revision", [("", None), ("bad\nname", None), ("n" * 81, None),
                                           ("baseline", ""), ("baseline", "bad\nrevision"),
                                           ("baseline", "release 1"), ("bad\u200bname", None)])
def test_save_validates_metadata_before_discovery(monkeypatch, name, revision):
    monkeypatch.setattr(workflow, "investigate_request", lambda *a, **k: pytest.fail("unexpected I/O"))
    with pytest.raises(ValueError):
        workflow.save_request_capture(object(), Store(), name, revision, path="/features")


def server(tmp_path):
    return TraceLensMcpServer("http://unused", None, capture_dir=str(tmp_path / "captures"))


def test_mcp_read_tools_do_not_create_capture_directory(tmp_path):
    instance = server(tmp_path)
    result = instance.tool_list_request_captures()
    assert result["structuredContent"]["captures"] == []
    assert not (tmp_path / "captures").exists()


def test_mcp_annotations_and_operator_only_paths(tmp_path):
    tools = {t["name"]: t for t in server(tmp_path)._tools()}
    for name in ("tracelens_doctor", "investigate_request", "list_request_captures", "compare_request_captures"):
        assert tools[name]["annotations"]["readOnlyHint"] is True
        assert tools[name]["inputSchema"]["additionalProperties"] is False
    assert tools["save_request_capture"]["annotations"]["readOnlyHint"] is False
    assert tools["save_request_capture"]["annotations"]["destructiveHint"] is False
    for name in ("save_request_capture", "list_request_captures", "compare_request_captures"):
        assert "capture_dir" not in tools[name]["inputSchema"]["properties"]
    assert server(tmp_path)._dispatch_tool("list_request_captures", {"capture_dir": "/tmp"})["isError"]


def test_new_mcp_tool_json_and_legacy_fallback(monkeypatch, tmp_path):
    model = {"schemaVersion": 1, "ready": False, "status": "attention", "services": []}
    monkeypatch.setattr("tracelens_cli.doctor.run_doctor", lambda discovery: model)
    instance = server(tmp_path)
    msg = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
           "params": {"name": "tracelens_doctor", "arguments": {}}}
    modern = instance.handle_message(msg)["result"]
    assert modern["structuredContent"] == model
    assert json.loads(modern["content"][0]["text"]) == model
    instance.protocol_version = "2024-11-05"
    legacy = instance.handle_message(msg)["result"]
    assert "structuredContent" not in legacy
    assert json.loads(legacy["content"][0]["text"]) == model


def test_mcp_investigate_passes_filters_without_implicit_latest(monkeypatch, tmp_path):
    calls = []

    def investigate(discovery, **filters):
        calls.append(filters)
        return {"status": "ambiguous", "candidates": []}

    monkeypatch.setattr("tracelens_cli.investigation.investigate_request", investigate)
    result = server(tmp_path)._dispatch_tool("investigate_request", {"path": "/features", "outcome": "error"})
    assert result["isError"] is False
    assert result["structuredContent"]["status"] == "ambiguous"
    assert calls == [{"path": "/features", "outcome": "error"}]


@pytest.mark.parametrize("status,code", [("selected", 0), ("ambiguous", 2), ("not_found", 2)])
def test_cli_investigate_and_exit_status(monkeypatch, capsys, status, code):
    calls = []

    def investigate(discovery, **filters):
        calls.append(filters)
        return {"status": status}

    monkeypatch.setattr("tracelens_cli.investigation.investigate_request", investigate)
    assert cli.main(["--url", "http://unused", "investigate", "--path", "/features"]) == code
    assert json.loads(capsys.readouterr().out)["status"] == status
    assert calls[0]["selection"] == "unique"
    assert calls[0]["since_minutes"] == 10


def test_cli_doctor_exit_status(monkeypatch, capsys):
    monkeypatch.setattr("tracelens_cli.doctor.run_doctor", lambda discovery: {"ready": False})
    assert cli.main(["--url", "http://unused", "doctor"]) == 1
    assert json.loads(capsys.readouterr().out) == {"ready": False}


def test_cli_offline_list_creates_no_directory(tmp_path, capsys):
    directory = tmp_path / "captures"
    assert cli.main(["--url", "http://unused", "--capture-dir", str(directory), "capture", "list"]) == 0
    assert json.loads(capsys.readouterr().out)["captures"] == []
    assert not directory.exists()


def test_cli_save_forwards_selection_and_capture_configuration(monkeypatch, capsys, tmp_path):
    calls = []

    def save(discovery, store, name, revision, **filters):
        calls.append((name, revision, filters))
        return {"saved": True, "status": "saved"}

    monkeypatch.setattr(workflow, "save_request_capture", save)
    assert cli.main(["--capture-dir", str(tmp_path), "--service", "default", "capture", "save", "before",
                     "--revision", "build-1", "--path", "/features", "--selection", "latest"]) == 0
    assert json.loads(capsys.readouterr().out)["saved"] is True
    assert calls[0][0:2] == ("before", "build-1")
    assert calls[0][2]["selection"] == "latest"
    assert calls[0][2]["service"] == "default"


def test_cli_passes_operator_capture_dir_to_mcp(monkeypatch):
    calls = []
    monkeypatch.setattr("tracelens_cli.mcp_server.serve", lambda *args: calls.append(args) or 0)
    assert cli.main(["--capture-dir", "/tmp/explicit-captures", "mcp"]) == 0
    assert calls[0][-1] == "/tmp/explicit-captures"


def test_mcp_bounds_results():
    from tracelens_cli.mcp_server import _workflow_result
    with pytest.raises(ValueError, match="256 KiB"):
        _workflow_result({"unbounded": "x" * (256 * 1024)})
