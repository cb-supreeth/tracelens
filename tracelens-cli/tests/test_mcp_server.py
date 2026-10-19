from tracelens_cli.mcp_server import TraceLensMcpServer


def _trace_summary(segment_id="segment-1", lens_id="CRI-123", channel="HTTP", activity=200,
                   action_id=None, action_name=None):
    trace = {
        "traceId": "trace-1",
        "segmentId": segment_id,
        "lensId": lens_id,
        "user": "engineer@chargebee.com",
        "entryChannel": channel,
        "status": "COMPLETED",
        "eventCount": 3,
        "startedAtMillis": 1_000,
        "lastActivityMillis": activity,
    }
    if action_id:
        trace.update({"actionId": action_id, "actionName": action_name,
                      "sessionId": "browser-session-1", "durationMs": 12})
    return trace


def _trace(events=None):
    events = events or [{
        "seq": 1, "type": "LINE", "className": "com.example.Foo", "line": 12,
        "thread": "main", "nanoTime": 0, "epochMillis": 1_000,
    }]
    return {
        "traceId": "trace-1", "segmentId": "segment-1", "lensId": "CRI-123",
        "user": "engineer@chargebee.com", "entryChannel": "HTTP", "status": "COMPLETED",
        "startNanos": 0, "eventCount": len(events), "truncated": False,
        "attributes": {}, "events": events,
    }


class _Client:
    def __init__(self):
        self.export_calls = []
        self.summaries = [
            _trace_summary(action_id="action-123", action_name="update-user-licence"),
            _trace_summary("segment-2", "other", "SQS", 100,
                           action_id="action-123", action_name="update-user-licence"),
            _trace_summary("segment-3", "other-action", "HTTP", 50,
                           action_id="action-456", action_name="open-reference-counts"),
        ]

    def traces(self):
        return {"traces": self.summaries}

    def find_by_lens_id(self, lens_id):
        return {"traces": [t for t in self.summaries if t["lensId"] == lens_id]}

    def export(self, segment_id, evict=None):
        self.export_calls.append((segment_id, evict))
        return _trace()

    def summary(self):
        return {
            "recordingEnabled": True, "agentActive": True, "instrumentedClasses": 42,
            "traceCount": 2, "maxTrackedRequests": 200, "totalEvents": 5,
            "maxTrackedEvents": 500_000, "bridge": {"emitted": 5, "droppedNoTrace": 0},
        }


def _server():
    server = TraceLensMcpServer("http://unused", None)
    server.client = _Client()
    return server


def _text(result):
    return result["content"][0]["text"]


def test_initialize_and_tool_discovery_follow_mcp_shape():
    server = _server()

    initialized = server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
    assert initialized["id"] == 1
    assert initialized["result"]["capabilities"] == {"tools": {}, "resources": {}}

    tools = server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    assert {tool["name"] for tool in tools["result"]["tools"]} == {
        "find_actions", "get_action", "list_flows", "get_flow", "get_flow_summary", "get_flow_mermaid",
        "search_flows", "flow_status",
        "find_distributed_traces", "get_distributed_trace",
        "get_trace_visualization", "show_trace_visualization",
        "list_error_groups", "get_error_group",
        "get_segment_execution",
        "tracelens_doctor", "investigate_request", "save_request_capture",
        "list_request_captures", "compare_request_captures",
    }


def test_find_actions_groups_related_flows_and_get_action_lists_them(monkeypatch):
    server = _server()
    monkeypatch.setattr("time.time", lambda: 0.2)  # cutoff is below fixture timestamps

    found = _text(server.tool_find_actions(action_name="update", since_minutes=1))
    details = _text(server.tool_get_action("action-123"))

    assert "action-123 | update-user-licence" in found
    assert "| 2 |" in found
    assert "segment-1" in details
    assert "segment-2" in details
    assert "segment-3" not in details


def test_list_flows_filters_and_sorts_by_latest_activity():
    server = _server()

    result = server.tool_list_flows(channel="http")

    text = _text(result)
    assert "segment-1" in text
    assert "segment-2" not in text


def test_get_flow_by_label_exports_non_destructively():
    server = _server()

    result = server.tool_get_flow("CRI-123")

    assert result["isError"] is False
    assert server.client.export_calls == [("segment-1", False)]
    assert 'trace trace-1' in _text(result)


def test_long_api_label_with_hyphens_resolves_to_newest_segment():
    server = _server()
    label = "auto:GET /api/v2/features/user-licenses/reference_counts"
    server.client.summaries = [
        _trace_summary("older-segment", label, activity=100),
        _trace_summary("newer-segment", label, activity=200),
    ]

    result = server.tool_get_flow(label)

    assert result["isError"] is False
    assert server.client.export_calls == [("newer-segment", False)]


def test_canonical_uuid_is_used_directly_without_label_lookup():
    server = _server()
    segment_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    result = server.tool_get_flow(segment_id)

    assert result["isError"] is False
    assert server.client.export_calls == [(segment_id, False)]


def test_get_flow_caps_events_before_rendering():
    server = _server()
    events = [_trace()["events"][0] for _ in range(3)]
    server.client.export = lambda segment_id, evict: _trace(events)

    result = server.tool_get_flow("11111111-1111-1111-1111-111111111111", max_events=1)

    assert "[TRUNCATED]" in _text(result)


def test_summary_and_mermaid_focus_on_runtime_boundaries():
    server = _server()
    server.client.export = lambda segment_id, evict: _trace([
        {"seq": 1, "type": "LINE", "className": "com.example.Foo", "line": 12,
         "thread": "http", "nanoTime": 0, "epochMillis": 1_000},
        {"seq": 2, "type": "DB_ROUTE", "thread": "http", "nanoTime": 1,
         "epochMillis": 1_000, "message": "route -> MASTER",
         "attributes": {"route": "MASTER"}},
        {"seq": 3, "type": "EXTERNAL_CALL", "thread": "worker", "nanoTime": 2,
         "epochMillis": 1_000, "message": "Sqs.send", "attributes": {"system": "Sqs", "detail": "send"}},
        {"seq": 4, "type": "EXCEPTION", "className": "com.example.Foo", "methodName": "run",
         "thread": "worker", "nanoTime": 3, "epochMillis": 1_000,
         "attributes": {"exceptionType": "IllegalStateException"}},
    ])

    summary = _text(server.tool_get_flow_summary("CRI-123"))
    mermaid = _text(server.tool_get_flow_mermaid("CRI-123"))

    assert "threads=http → worker" in summary
    assert "db-routes=MASTER" in summary
    assert "external-calls=Sqs.send" in summary
    assert "IllegalStateException at com.example.Foo.run" in summary
    assert "```mermaid" in mermaid
    assert "thread1->>thread2: thread hop" in mermaid
    assert "DB route" in mermaid
    assert "thread2->>system1" in mermaid
    assert "threw" in mermaid


def test_unknown_tool_and_unknown_label_return_tool_errors():
    server = _server()

    unknown_tool = server._dispatch_tool("missing", {})
    missing_label = server.tool_get_flow("not-found")

    assert unknown_tool["isError"] is True
    assert "unknown tool" in _text(unknown_tool)
    assert missing_label["isError"] is True
    assert "no flow found" in _text(missing_label)


def test_status_is_compact_and_notifications_do_not_reply():
    server = _server()

    status = server.tool_flow_status()

    assert "recording=on" in _text(status)
    assert "agent=active" in _text(status)
    assert server.handle_message({"method": "notifications/initialized"}) is None
