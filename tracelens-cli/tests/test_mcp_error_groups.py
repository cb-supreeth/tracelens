"""MCP error groups use full lightweight snapshots and the shared pure model."""

import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import pytest

from tracelens_cli.client import DebugApiError
from tracelens_cli.error_groups import build_error_groups, select_error_group
from tracelens_cli.mcp_server import TraceLensMcpServer


NOW_MS = 2_000_000_000_000
TRACE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
TRACE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
TRACE_C = "cccccccccccccccccccccccccccccccc"
TRACE_OLD = "dddddddddddddddddddddddddddddddd"


def _row(trace, segment, service, status, *, parent=None, user="alice", site="42", duration=35,
         path="/features/7", route="/features/{id}", age=60_000, exception=None, channel="HTTP"):
    row = {"traceId": trace, "segmentId": segment, "service": service, "serviceInstanceId": service + "-instance",
           "httpMethod": "GET", "path": path, "routeTemplate": route, "httpStatus": status,
           "entryChannel": channel, "status": "COMPLETED", "user": user, "siteId": site,
           "durationMs": duration, "startedAtMillis": NOW_MS - age, "lastActivityMillis": NOW_MS - age + duration,
           "eventCount": 4, "parentSpanId": parent, "segmentKey": service + ":" + segment}
    if exception:
        row["filterException"] = exception
    return row


def _document():
    root = _row(TRACE_A, "root-a", "cb-app", 200, path="/api/features/7", route="/api/features/{id}")
    # A caught exception in a recovered root is not a failure by itself.
    root.update(exceptionCount=2, hasExceptions=True)
    failed_child = _row(TRACE_A, "child-a", "cb-ems", 503, parent="upstream-span",
                        user="apicall", site=None, duration=12, exception="java.lang.IllegalStateException")
    root_b = _row(TRACE_B, "root-b", "cb-app", 500, user="bob", site="84", duration=60,
                  exception="java.lang.IllegalArgumentException")
    root_c = _row(TRACE_C, "root-c", "cb-ems", 404, user="charlie", site="99", path="/missing", route=None)
    old = _row(TRACE_OLD, "root-old", "cb-ems", 502, age=40 * 60_000)
    return {"traces": [root, failed_child, root_b, root_c, old], "partial": False, "limited": False, "errors": []}


class SummaryDiscovery:
    def __init__(self, document):
        self.document = copy.deepcopy(document)
        self.calls = []

    def traces(self, **kwargs):
        self.calls.append(kwargs)
        # Any upstream filtering can lose the successful root and its user/site.
        assert kwargs == {"limit": 1000}
        return copy.deepcopy(self.document)

    def export(self, *args, **kwargs):
        raise AssertionError("Error grouping must not export event/source data")

    def get_distributed_trace(self, *args, **kwargs):
        raise AssertionError("Error grouping must not fetch full distributed exports")


@pytest.fixture
def server(monkeypatch):
    monkeypatch.setattr("time.time", lambda: NOW_MS / 1000)
    monkeypatch.setattr("time.time_ns", lambda: NOW_MS * 1_000_000)
    instance = TraceLensMcpServer("http://unused", None)
    discovery = SummaryDiscovery(_document())
    instance.discovery = discovery
    instance.client = discovery
    return instance


def _call(server, name, arguments=None):
    return server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                  "params": {"name": name, "arguments": arguments or {}}})["result"]


def _unlimited_list_projection(model):
    """Expected presentation for small fixtures whose labels need no clipping."""
    return {**{key: value for key, value in model.items() if key != "requests"},
            "groups": [{key: value for key, value in group.items() if key != "requestKeys"}
                       for group in model["groups"]],
            "returnedGroupCount": len(model["groups"]), "omittedGroupCount": 0}


def _unlimited_detail_projection(model):
    return {**model, "returnedRequestCount": len(model["requests"]), "omittedRequestCount": 0}


def test_list_reads_only_full_summary_snapshot_and_preserves_shared_grouping(server):
    result = _call(server, "list_error_groups")
    expected = build_error_groups(_document(), now_ms=NOW_MS)
    assert result["isError"] is False
    assert result["structuredContent"] == _unlimited_list_projection(expected)
    assert "requests" not in result["structuredContent"]
    assert all("requestKeys" not in group for group in result["structuredContent"]["groups"])
    assert server.discovery.calls == [{"limit": 1000}]
    assert result["structuredContent"]["counts"]["loadedSegments"] == 5
    assert result["structuredContent"]["counts"]["loadedRequests"] == 4
    assert "_meta" not in result
    text = result["content"][0]["text"]
    assert "Retained-only" in text and "not traffic/error rates" in text
    assert "not a durable incident record" in text
    assert "get_error_group" in text and "get_trace_visualization" in text and "get_flow" in text
    assert all(group["id"] in text for group in expected["groups"])


def test_failing_service_filter_keeps_successful_root_and_request_identity(server):
    filters = {"service": "cb-ems", "user": "alice", "site": "42", "category": "http5xx"}
    result = server.tool_list_error_groups(**filters)["structuredContent"]
    assert result == _unlimited_list_projection(build_error_groups(_document(), filters, now_ms=NOW_MS))
    assert len(result["groups"]) == 1
    assert result["groups"][0]["service"] == "cb-ems"
    detail = server.tool_get_error_group(result["groups"][0]["id"], **filters)["structuredContent"]
    request = detail["requests"][0]
    assert request["traceId"] == TRACE_A
    assert request["root"]["service"] == "cb-app"
    assert {segment["service"] for segment in request["segments"]} == {"cb-app", "cb-ems"}
    assert server.discovery.calls == [{"limit": 1000}, {"limit": 1000}]


@pytest.mark.parametrize("filters", [
    {"service": "cb-ems"}, {"method": "get"}, {"path": "/features"}, {"category": "http4xx"},
    {"category": "http5xx"}, {"category": "timeout"}, {"category": "execution"},
    {"category": "all"},
    {"http_status": "503"}, {"exception_class": "IllegalState"}, {"user": "alice"},
    {"site": "42"}, {"channel": "HTTP"}, {"since_minutes": None}, {"since_minutes": ""},
    {"since_minutes": "45"}, {"query": "features"}, {"min_duration": "20"},
    {"max_duration": 40}, {"min_duration": "10", "max_duration": "60", "http_status": 500},
])
def test_filter_parity_uses_shared_model_without_pushing_filters_upstream(server, filters):
    result = _call(server, "list_error_groups", filters)
    assert result["isError"] is False
    assert result["structuredContent"] == _unlimited_list_projection(build_error_groups(_document(), filters, now_ms=NOW_MS))
    assert server.discovery.calls == [{"limit": 1000}]


def test_get_error_group_uses_same_filters_and_fresh_snapshot_with_full_member_context(server):
    filters = {"service": "cb-ems", "category": "http5xx", "since_minutes": None}
    listed = server.tool_list_error_groups(**filters)["structuredContent"]
    group = next(group for group in listed["groups"] if group["httpStatus"] == 503)
    assert re.fullmatch(r"eg_[0-9a-f]{24}", group["id"])
    result = server.tool_get_error_group(group["id"], **filters)
    canonical = build_error_groups(_document(), filters, now_ms=NOW_MS)
    assert result["structuredContent"] == _unlimited_detail_projection(select_error_group(canonical, group["id"]))
    assert {key: value for key, value in result["structuredContent"]["group"].items() if key != "requestKeys"} == group
    assert [request["traceId"] for request in result["structuredContent"]["requests"]] == [TRACE_A]
    assert len(result["structuredContent"]["requests"][0]["segments"]) == 2
    assert server.discovery.calls == [{"limit": 1000}, {"limit": 1000}]
    assert group["id"] in result["content"][0]["text"]
    assert TRACE_A in result["content"][0]["text"]


@pytest.mark.parametrize("group_count", [49, 50, 51, 75])
def test_list_caps_groups_without_changing_full_counts_or_group_ids(server, group_count):
    rows = [_row(f"{index + 1:032x}", f"segment-{index}", "cb-ems", 503,
                 route=f"/separate-route-{index}", age=index * 1000)
            for index in range(group_count)]
    server.discovery.document = {"traces": rows}
    expected = build_error_groups(server.discovery.document, now_ms=NOW_MS)
    result = server.tool_list_error_groups()
    model = result["structuredContent"]
    assert model["counts"] == expected["counts"]
    assert model["returnedGroupCount"] == min(group_count, 50)
    assert model["omittedGroupCount"] == max(group_count - 50, 0)
    assert [group["id"] for group in model["groups"]] == [group["id"] for group in expected["groups"][:50]]
    assert "requests" not in model and all("requestKeys" not in group for group in model["groups"])
    assert model["limited"] is (group_count > 50)
    assert model["partial"] == expected["partial"]
    if group_count > 50:
        assert any("additional groups omitted" in warning for warning in model["warnings"])
        assert "Narrow filters" in result["content"][0]["text"]
        # The presentation cap must not change the underlying selectable groups.
        hidden_group = expected["groups"][-1]
        detail = server.tool_get_error_group(hidden_group["id"])["structuredContent"]
        assert detail["group"]["id"] == hidden_group["id"]
        assert detail["returnedRequestCount"] == 1
    else:
        assert model["warnings"] == expected["warnings"]


@pytest.mark.parametrize("route_length,class_length", [(256, 200), (257, 200), (256, 201), (400, 230)])
def test_list_marks_display_clipping_without_changing_identity_or_detail(server, route_length, class_length):
    route = "/" + "r" * (route_length - 1)
    exception = "com.example." + "E" * (class_length - len("com.example."))
    server.discovery.document = {"traces": [_row(TRACE_A, "segment-a", "cb-ems", 503,
                                                route=route, exception=exception)]}
    canonical = build_error_groups(server.discovery.document, now_ms=NOW_MS)
    result = server.tool_list_error_groups()
    model = result["structuredContent"]
    group = model["groups"][0]
    assert group["id"] == canonical["groups"][0]["id"]
    assert model["counts"] == canonical["counts"]
    assert len(group["route"]) <= 256 and len(group["exceptionClass"]) <= 200
    clipped = [field for field, length, maximum in (("route", route_length, 256),
                                                   ("exceptionClass", class_length, 200)) if length > maximum]
    assert group.get("presentationTruncatedFields", []) == clipped
    for field in clipped:
        assert group[field].endswith("… [truncated]")
    if clipped:
        assert any("MCP presentation truncates" in warning for warning in model["warnings"])
        assert "Presentation markers" in result["content"][0]["text"]
    else:
        assert "presentationTruncatedFields" not in group
        assert model["warnings"] == canonical["warnings"]
    assert model["limited"] is False  # display clipping does not omit groups
    detail = server.tool_get_error_group(group["id"])["structuredContent"]
    assert detail["group"]["route"] == route
    assert detail["group"]["exceptionClass"] == exception
    assert "presentationTruncatedFields" not in detail["group"]


@pytest.mark.parametrize("request_count", [19, 20, 21, 30])
def test_detail_caps_newest_members_but_preserves_full_counts_and_all_siblings(server, request_count):
    rows = []
    for index in range(request_count):
        trace = f"{index + 1:032x}"
        rows.append(_row(trace, f"root-{index}", "cb-app", 200, age=index * 1000))
        for child in range(2):
            rows.append(_row(trace, f"child-{index}-{child}", "cb-ems", 503, parent="parent-span",
                             user="apicall", site=None, age=index * 1000))
    server.discovery.document = {"traces": list(reversed(rows))}
    filters = {"service": "cb-ems", "user": "alice", "since_minutes": None}
    canonical = build_error_groups(server.discovery.document, filters, now_ms=NOW_MS)
    group_id = canonical["groups"][0]["id"]
    expected = select_error_group(canonical, group_id)
    result = server.tool_get_error_group(group_id, **filters)
    model = result["structuredContent"]
    assert model["requests"] == expected["requests"][:20]
    assert model["counts"] == expected["counts"]
    assert model["counts"]["matchingRequests"] == request_count
    assert model["counts"]["matchingSegments"] == request_count * 2
    assert model["group"]["requestCount"] == request_count
    assert model["group"]["segmentCount"] == request_count * 2
    assert model["group"]["requestKeys"] == sorted(request["key"] for request in model["requests"])
    assert model["returnedRequestCount"] == min(request_count, 20)
    assert model["omittedRequestCount"] == max(request_count - 20, 0)
    assert model["limited"] is (request_count > 20)
    assert model["partial"] == expected["partial"]
    for request in model["requests"]:
        assert request["root"]["service"] == "cb-app" and request["root"]["httpStatus"] == 200
        assert len(request["segments"]) == 3
    if request_count > 20:
        assert any("additional requests omitted" in warning for warning in model["warnings"])
        assert "Narrow filters" in result["content"][0]["text"]
    else:
        assert model["warnings"] == expected["warnings"]
    assert server.discovery.calls == [{"limit": 1000}]


def test_projections_do_not_mutate_shared_model(server, monkeypatch):
    model = build_error_groups(_document(), now_ms=NOW_MS)
    unchanged = copy.deepcopy(model)
    monkeypatch.setattr(server, "_error_groups", lambda filters: model)
    server.tool_list_error_groups()
    server.tool_get_error_group(model["groups"][0]["id"])
    assert model == unchanged


def test_large_list_payload_omits_repeated_request_metadata(server):
    server.discovery.document = {"traces": [
        _row(f"{index + 1:032x}", f"segment-{index}", "cb-ems", 503,
             route="/" + "r" * 1900 + str(index), exception="com.example." + "E" * 220)
        for index in range(1000)]}
    result = server.tool_list_error_groups()
    model = result["structuredContent"]
    assert model["counts"]["groups"] == 1000
    assert model["returnedGroupCount"] == 50 and model["omittedGroupCount"] == 950
    assert "requests" not in model
    assert len(json.dumps(result, ensure_ascii=False).encode("utf-8")) < 150_000


def test_group_can_disappear_after_retention_changes_without_becoming_durable_incident(server):
    group = server.tool_list_error_groups()["structuredContent"]["groups"][0]
    server.discovery.document["traces"] = []
    result = server.tool_get_error_group(group["id"])
    assert result["isError"] is False
    assert result["structuredContent"]["group"] is None
    assert result["structuredContent"]["requests"] == []
    assert result["structuredContent"]["counts"]["matchingRequests"] == 0
    assert result["structuredContent"]["returnedRequestCount"] == 0
    assert result["structuredContent"]["omittedRequestCount"] == 0
    assert "eviction or restart" in result["content"][0]["text"]


@pytest.mark.parametrize("filters", [
    {"url": "http://arbitrary-upstream"}, {"service": []}, {"path": {}}, {"user": True},
    {"category": "rootcause"}, {"since_minutes": True}, {"since_minutes": -1},
    {"since_minutes": 525601}, {"since_minutes": "nan"}, {"since_minutes": float("inf")},
    {"http_status": 99}, {"http_status": 600}, {"http_status": "503.5"}, {"http_status": True},
    {"min_duration": -1}, {"max_duration": float("nan")}, {"min_duration": 20, "max_duration": 10},
])
def test_invalid_filters_rejected_before_any_summary_read(server, filters):
    listed = _call(server, "list_error_groups", filters)
    detail = _call(server, "get_error_group", {"group_id": "eg_" + "a" * 24, **filters})
    assert listed["isError"] is True and detail["isError"] is True
    assert "structuredContent" not in listed and "structuredContent" not in detail
    assert server.discovery.calls == []


@pytest.mark.parametrize("group_id", [None, 42, "", "../secret", "http://evil", "eg_" + "a" * 23,
                                       "eg_" + "a" * 25, "eg_" + "A" * 24, "eg_" + "a" * 24 + "\n"])
def test_malformed_group_id_rejected_before_any_io(server, group_id):
    result = _call(server, "get_error_group", {"group_id": group_id})
    assert result["isError"] is True
    assert server.discovery.calls == []


def test_offline_partial_limited_and_empty_results_keep_shared_caveats(server):
    server.discovery.document = {"traces": [], "partial": True, "limited": True,
                                 "errors": [{"service": "cb-ems", "error": "offline"}]}
    result = server.tool_list_error_groups()
    model = result["structuredContent"]
    assert model == _unlimited_list_projection(build_error_groups(server.discovery.document, now_ms=NOW_MS))
    assert model["partial"] is True and model["limited"] is True
    assert model["groups"] == [] and "requests" not in model
    text = result["content"][0]["text"]
    assert "PARTIAL" in text and "LIMITED" in text and "offline" in text
    assert "does not establish an absence of errors" in text


def test_transport_failure_is_tool_error_and_server_remains_usable(server):
    def unavailable(**kwargs):
        raise DebugApiError("summary unavailable")
    server.discovery.traces = unavailable
    assert _call(server, "list_error_groups")["isError"] is True
    assert _call(server, "get_error_group", {"group_id": "eg_" + "a" * 24})["isError"] is True
    assert server.handle_message({"id": 9, "method": "ping"})["result"] == {}


def test_error_tools_are_read_only_and_list_identical_filter_schemas(server):
    tools = {tool["name"]: tool for tool in server._tools()}
    listed = tools["list_error_groups"]
    detail = tools["get_error_group"]
    for tool in (listed, detail):
        assert tool["annotations"]["readOnlyHint"] is True
        assert tool["annotations"]["destructiveHint"] is False
        assert tool["inputSchema"]["additionalProperties"] is False
        assert "_meta" not in tool
    assert detail["inputSchema"]["required"] == ["group_id"]
    assert listed["inputSchema"]["properties"] == {name: value for name, value in detail["inputSchema"]["properties"].items() if name != "group_id"}
    assert set(listed["inputSchema"]["properties"]) == {
        "service", "method", "path", "category", "http_status", "exception_class", "user", "site", "channel",
        "since_minutes", "query", "min_duration", "max_duration"}
    assert "at most 50 groups" in listed["description"]
    assert "at most 20 most-recent member requests" in detail["description"]
    assert "all observed sibling segments" in detail["description"]


def test_legacy_protocol_gets_meaningful_text_without_structured_content(server):
    initialized = server.handle_message({"id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}})
    assert initialized["result"]["protocolVersion"] == "2024-11-05"
    result = _call(server, "list_error_groups")
    assert "structuredContent" not in result and "get_error_group" in result["content"][0]["text"]
    group = build_error_groups(_document(), now_ms=NOW_MS)["groups"][0]
    result = _call(server, "get_error_group", {"group_id": group["id"]})
    assert "structuredContent" not in result
    assert group["id"] in result["content"][0]["text"]


def test_real_stdio_groups_two_requests_without_exporting_sources():
    """Exercise the real JSONL server against isolated loopback fixture agents."""
    from errors_ui_fixture import error_rows
    from ui_fixture import fake_api

    rows = error_rows()
    filters = {"service": "cb-ems", "category": "http5xx", "http_status": 503, "user": "alice_42"}
    expected = build_error_groups({"traces": rows["cb-app"] + rows["cb-ems"]}, filters)
    assert len(expected["groups"]) == 1
    group_id = expected["groups"][0]["id"]
    assert expected["groups"][0]["requestCount"] == 2
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "error-group-stdio-test", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
            "name": "list_error_groups", "arguments": filters}},
        {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
            "name": "get_error_group", "arguments": {"group_id": group_id, **filters}}},
        {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {
            "name": "get_error_group", "arguments": {"group_id": group_id, **filters, "user": "missing-user"}}},
        {"jsonrpc": "2.0", "id": 6, "method": "tools/call", "params": {
            "name": "get_error_group", "arguments": {"group_id": "../../not-a-group"}}},
        {"jsonrpc": "2.0", "id": 7, "method": "ping"},
    ]
    with fake_api("cb-app", rows["cb-app"]) as (app_url, app_reads), fake_api("cb-ems", rows["cb-ems"]) as (ems_url, ems_reads):
        code = ("import sys; from tracelens_cli.mcp_server import serve; "
                "from tracelens_cli.services import Service; "
                "raise SystemExit(serve(sys.argv[1], None, "
                "[Service('cb-app', sys.argv[1]), Service('cb-ems', sys.argv[2])]))")
        process = subprocess.run([sys.executable, "-c", code, app_url, ems_url],
                                 input="".join(json.dumps(message) + "\n" for message in messages),
                                 text=True, capture_output=True, timeout=20,
                                 cwd=Path(__file__).parents[1],
                                 env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        assert process.returncode == 0, process.stderr
        responses = [json.loads(line) for line in process.stdout.splitlines()]
        assert [message["id"] for message in responses] == list(range(1, 8))
        assert all(message["jsonrpc"] == "2.0" and "result" in message for message in responses)
        results = {message["id"]: message["result"] for message in responses}
        assert {"list_error_groups", "get_error_group"} <= {tool["name"] for tool in results[2]["tools"]}
        listed = results[3]["structuredContent"]
        selected = results[4]["structuredContent"]
        assert listed["groups"][0]["id"] == group_id
        assert listed["groups"][0]["requestCount"] == selected["counts"]["matchingRequests"] == 2
        assert "requests" not in listed and "requestKeys" not in listed["groups"][0]
        assert listed["returnedGroupCount"] == 1 and listed["omittedGroupCount"] == 0
        assert selected["returnedRequestCount"] == 2 and selected["omittedRequestCount"] == 0
        assert {key: value for key, value in selected["group"].items() if key != "requestKeys"} == listed["groups"][0]
        assert selected["group"]["requestKeys"] == sorted(request["key"] for request in selected["requests"])
        assert selected["group"]["service"] == "cb-ems" and selected["group"]["httpStatus"] == 503
        assert len(selected["requests"]) == 2
        for request in selected["requests"]:
            assert request["root"]["service"] == "cb-app"
            assert {segment["service"] for segment in request["segments"]} == {"cb-app", "cb-ems"}
            assert all("events" not in segment and "contextParams" not in segment for segment in request["segments"])
        narrowed = results[5]["structuredContent"]
        assert narrowed["group"] is None and narrowed["requests"] == []
        assert narrowed["counts"]["matchingRequests"] == narrowed["counts"]["matchingSegments"] == 0
        assert narrowed["counts"]["loadedRequests"] == listed["counts"]["loadedRequests"]
        assert results[6]["isError"] is True and results[7] == {}
        assert app_reads == ems_reads == ["/tracelens/traces"] * 3
        assert process.stderr.startswith("tracelens mcp server ready")
