"""Read-only MCP routing, validation and bounded JSON fallback for segment execution."""

import copy
import json

import pytest

from tracelens_cli.client import DebugApiError
from tracelens_cli.execution import MODEL_MAX_BYTES
from tracelens_cli.mcp_server import SEGMENT_EXECUTION_RESULT_MAX_BYTES, TraceLensMcpServer
from tracelens_cli.services import Service


SEGMENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
OTHER_SEGMENT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
INSTANCE = "cccccccc-cccc-cccc-cccc-cccccccccccc"


def _trace(segment_id=SEGMENT_ID, instance=INSTANCE, count=2):
    events = []
    for index in range(count):
        for event_type in ("METHOD_ENTER", "METHOD_EXIT"):
            seq = len(events) + 1
            events.append({"seq": seq, "type": event_type, "className": "com.example.Controller",
                           "methodName": "run", "thread": "http-worker", "line": 12,
                           "nanoTime": seq * 1_000_000, "epochMillis": 1_000 + seq,
                           "attributes": {}})
    return {"segmentId": segment_id, "traceId": "trace-1", "serviceInstanceId": instance,
            "status": "COMPLETED", "eventCount": len(events), "events": events,
            "attributes": {}, "startNanos": 0, "truncated": False}


class RetainedClient:
    """Exports copies, permitting assertions that inspection never mutates retention."""

    def __init__(self, trace=None):
        self.trace = copy.deepcopy(trace if trace is not None else _trace())
        self.calls = []
        self.error = None

    def export(self, segment_id, evict=None):
        self.calls.append(("export", segment_id, evict))
        assert evict is False
        if self.error:
            raise self.error
        return copy.deepcopy(self.trace)

    def traces(self):
        self.calls.append(("traces",))
        if self.error:
            raise self.error
        return {"traces": [{key: value for key, value in self.trace.items() if key != "events"}]}

    def find_by_lens_id(self, label):
        raise AssertionError("Exact segment execution must never resolve labels")


def _single(trace=None):
    server = TraceLensMcpServer("http://unused", "/not-read")
    server.client = RetainedClient(trace)
    return server


def _federated(first=None, second=None):
    server = TraceLensMcpServer("http://unused", None,
                                services=[Service("cb-app", "http://app.invalid"),
                                          Service("cb-ems", "http://ems.invalid")])
    server.client.clients = {"cb-app": RetainedClient(first),
                             "cb-ems": RetainedClient(second if second is not None else _trace(OTHER_SEGMENT_ID))}
    return server


def _call(server, **arguments):
    return server.handle_message({"jsonrpc": "2.0", "id": 17, "method": "tools/call",
                                  "params": {"name": "get_segment_execution", "arguments": arguments}})["result"]


def _assert_no_io(server):
    clients = getattr(server.client, "clients", {"default": server.client})
    assert all(not client.calls for client in clients.values())


def test_discovery_advertises_bounded_read_only_tool():
    server = _single()
    tool = next(tool for tool in server._tools() if tool["name"] == "get_segment_execution")
    assert tool["annotations"] == {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}
    schema = tool["inputSchema"]
    assert schema["required"] == ["segment_id"]
    assert schema["additionalProperties"] is False
    assert schema["properties"]["max_nodes"] == {"type": "integer", "minimum": 1, "maximum": 500, "default": 200}
    assert "not the full retained export" in tool["description"]
    _assert_no_io(server)


def test_single_export_is_non_destructive_and_json_fallback_is_the_complete_model():
    server = _single()
    original = copy.deepcopy(server.client.trace)
    result = _call(server, segment_id=SEGMENT_ID, service="default", instance=INSTANCE, max_nodes=1)
    assert result["isError"] is False
    assert server.client.calls == [("export", SEGMENT_ID, False)]
    assert server.client.trace == original
    assert len(result["content"]) == 1
    assert result["content"][0]["type"] == "text"
    model = result["structuredContent"]
    assert json.loads(result["content"][0]["text"]) == model
    assert model["kind"] == "segment-execution"
    assert model["schemaVersion"] == 1
    assert model["segment"]["segmentId"] == SEGMENT_ID
    assert model["segment"]["service"] == "default"
    assert len(model["nodes"]) == 1
    assert model["nodes"][0]["kind"] == "invocation"
    assert model["nodes"][0]["outcome"] == "returned"
    assert model["nodes"][0]["complete"] is True
    assert model["nodes"][0]["durationMs"] == 1
    assert model["counts"]["invocationsObserved"] == 2
    assert model["counts"]["nodesOmitted"] == 1
    assert model["partial"] is True
    assert "_meta" not in result  # no renderer is attached


def test_federated_explicit_service_routes_only_that_service_and_keeps_instance():
    server = _federated()
    result = _call(server, segment_id=SEGMENT_ID, service="cb-app", instance=INSTANCE)
    assert result["isError"] is False
    assert result["structuredContent"]["segment"]["service"] == "cb-app"
    assert result["structuredContent"]["segment"]["serviceInstanceId"] == INSTANCE
    assert len(result["structuredContent"]["nodes"]) == 2
    assert all(node["kind"] == "invocation" for node in result["structuredContent"]["nodes"])
    assert server.client.clients["cb-app"].calls == [("export", SEGMENT_ID, False)]
    assert server.client.clients["cb-ems"].calls == []


def test_legacy_client_receives_the_same_model_without_mcp_apps_support():
    server = _single()
    expected = server.tool_get_segment_execution(SEGMENT_ID)["structuredContent"]
    initialized = server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                         "params": {"protocolVersion": "2024-11-05"}})
    assert initialized["result"]["protocolVersion"] == "2024-11-05"
    result = _call(server, segment_id=SEGMENT_ID)
    assert result["isError"] is False
    assert json.loads(result["content"][0]["text"]) == expected
    assert "structuredContent" not in result  # legacy protocol strips this field
    assert "_meta" not in result


def test_federated_without_service_discovers_exact_segment_and_exports_only_match():
    server = _federated()
    result = _call(server, segment_id=SEGMENT_ID)
    assert result["isError"] is False
    assert result["structuredContent"]["segment"]["service"] == "cb-app"
    assert server.client.clients["cb-app"].calls == [("traces",), ("export", SEGMENT_ID, False)]
    assert server.client.clients["cb-ems"].calls == [("traces",)]


@pytest.mark.parametrize("problem", ["ambiguous", "missing", "offline"])
def test_federated_discovery_does_not_guess_when_identity_is_ambiguous_or_incomplete(problem):
    server = _federated(second=_trace() if problem == "ambiguous" else _trace(OTHER_SEGMENT_ID))
    if problem == "offline":
        server.client.clients["cb-ems"].error = DebugApiError("offline")
    segment = "dddddddd-dddd-dddd-dddd-dddddddddddd" if problem == "missing" else SEGMENT_ID
    result = _call(server, segment_id=segment)
    assert result["isError"] is True
    assert "specify service and instance" in result["content"][0]["text"]
    assert all(client.calls == [("traces",)] for client in server.client.clients.values())


@pytest.mark.parametrize("federated", [False, True])
def test_stale_instance_is_an_error_not_a_different_execution_model(federated):
    server = _federated() if federated else _single()
    result = _call(server, segment_id=SEGMENT_ID, service="cb-app" if federated else "default",
                   instance="previous-instance")
    assert result["isError"] is True
    assert "service instance changed" in result["content"][0]["text"]
    assert "structuredContent" not in result


@pytest.mark.parametrize("federated", [False, True])
def test_export_segment_identity_is_checked_before_building_model(federated):
    server = _federated(first=_trace(OTHER_SEGMENT_ID)) if federated else _single(_trace(OTHER_SEGMENT_ID))
    result = _call(server, segment_id=SEGMENT_ID, service="cb-app" if federated else "default")
    assert result["isError"] is True
    assert "different segment" in result["content"][0]["text"]
    assert "structuredContent" not in result


@pytest.mark.parametrize("arguments", [
    {}, {"segment_id": "auto:GET /features"}, {"segment_id": "trace-1"},
    {"segment_id": SEGMENT_ID.upper()}, {"segment_id": SEGMENT_ID.replace("-", "")},
    {"segment_id": "{" + SEGMENT_ID + "}"}, {"segment_id": SEGMENT_ID + "\n"},
    {"segment_id": None}, {"segment_id": 17}, {"segment_id": True},
    {"segment_id": SEGMENT_ID, "max_nodes": 0}, {"segment_id": SEGMENT_ID, "max_nodes": 501},
    {"segment_id": SEGMENT_ID, "max_nodes": True}, {"segment_id": SEGMENT_ID, "max_nodes": 1.5},
    {"segment_id": SEGMENT_ID, "max_nodes": "20"}, {"segment_id": SEGMENT_ID, "max_nodes": None},
    {"segment_id": SEGMENT_ID, "service": "http://other.invalid"},
    {"segment_id": SEGMENT_ID, "service": "unknown-service"},
    {"segment_id": SEGMENT_ID, "service": ""}, {"segment_id": SEGMENT_ID, "service": True},
    {"segment_id": SEGMENT_ID, "service": "a" * 65},
    {"segment_id": SEGMENT_ID, "instance": ""}, {"segment_id": SEGMENT_ID, "instance": "a" * 129},
    {"segment_id": SEGMENT_ID, "instance": "instance\n"},
    {"segment_id": SEGMENT_ID, "instance": "http://other.invalid"},
    {"segment_id": SEGMENT_ID, "instance": False},
    {"segment_id": SEGMENT_ID, "source": True},
])
@pytest.mark.parametrize("federated", [False, True])
def test_invalid_arguments_fail_before_upstream_io(arguments, federated):
    server = _federated() if federated else _single()
    result = _call(server, **arguments)
    assert result["isError"] is True
    _assert_no_io(server)


@pytest.mark.parametrize("status", [404, 500, None])
def test_export_errors_are_reported_without_killing_mcp_server(status):
    server = _single()
    server.client.error = DebugApiError("segment missing or agent unavailable", status)
    result = _call(server, segment_id=SEGMENT_ID)
    assert result["isError"] is True
    assert "service error" in result["content"][0]["text"]
    assert server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "ping"})["result"] == {}


def test_model_and_mirrored_result_are_byte_bounded_including_unicode_and_escapes():
    trace = _trace(count=700)
    for event in trace["events"]:
        event["className"] = 'com.example.\\"😀' * 100
        event["methodName"] = '\\"\n例' * 100
    result = _call(_single(trace), segment_id=SEGMENT_ID, max_nodes=500)
    assert result["isError"] is False
    model = result["structuredContent"]
    fallback = result["content"][0]["text"]
    assert json.loads(fallback) == model
    assert len(fallback.encode("utf-8")) <= MODEL_MAX_BYTES
    assert len(json.dumps(result).encode("utf-8")) <= SEGMENT_EXECUTION_RESULT_MAX_BYTES
    assert model["partial"] is True


def test_defensive_guard_refuses_an_over_budget_model(monkeypatch):
    monkeypatch.setattr("tracelens_cli.mcp_server.build_segment_execution",
                        lambda *args, **kwargs: {"unexpected": "x" * MODEL_MAX_BYTES})
    result = _call(_single(), segment_id=SEGMENT_ID)
    assert result["isError"] is True
    assert "byte budget" in result["content"][0]["text"]
