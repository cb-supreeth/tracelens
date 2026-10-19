"""HTTP error grouping is shared with MCP and reads only retained summaries."""
import copy
import urllib.parse

import pytest

from tracelens_cli import server
from tracelens_cli.error_groups import build_error_groups


TRACE = "1234567890abcdef1234567890abcdef"


def evidence():
    root = {
        "traceId": TRACE, "segmentId": "app-segment", "service": "cb-app",
        "serviceInstanceId": "app-instance", "entryChannel": "HTTP",
        "httpMethod": "GET", "path": "/api/v2/features/test1", "httpStatus": "200",
        "status": "COMPLETED", "durationMs": 30, "startedAtMillis": 1000,
        "lastActivityMillis": 1030, "user": "alice_42", "siteId": "42",
        "eventCount": 12, "contextParams": {"private": "do-not-copy"},
    }
    child = {
        **root, "segmentId": "ems-segment", "service": "cb-ems",
        "serviceInstanceId": "ems-instance", "path": "/features/test1",
        "parentSpanId": "parent-call", "httpStatus": "503", "durationMs": 12,
        "filterException": "java.lang.IllegalStateException",
    }
    return {"traces": [root, child], "errors": [], "partial": False, "limited": False}


class Discovery:
    def __init__(self, doc):
        self.doc = doc
        self.calls = []

    def traces(self, **filters):
        self.calls.append(filters)
        return copy.deepcopy(self.doc)

    def export(self, *args, **kwargs):
        raise AssertionError("Error grouping must not export execution events")


def request(path, discovery):
    handler = server._Handler.__new__(server._Handler)
    handler.path = path
    handler.discovery = discovery
    responses = []
    handler._send_json = lambda doc, status: responses.append((status, doc))
    handler.do_GET()
    assert len(responses) == 1
    return responses[0]


def test_http_uses_shared_model_and_preserves_successful_root_for_downstream_error():
    discovery = Discovery(evidence())
    filters = {"since_minutes": "", "service": "cb-ems", "category": "http5xx"}
    status, doc = request("/api/error-groups?" + urllib.parse.urlencode(filters), discovery)
    assert status == 200
    assert doc == build_error_groups(evidence(), filters)
    assert discovery.calls == [{"limit": 1000}]  # never filter away root metadata upstream
    assert doc["counts"]["matchingRequests"] == 1
    assert doc["requests"][0]["httpStatus"] == 200
    assert len(doc["requests"][0]["segments"]) == 2
    assert doc["groups"][0]["service"] == "cb-ems"
    assert "do-not-copy" not in str(doc)


def test_failing_service_and_status_must_match_the_same_segment():
    status, doc = request("/api/error-groups?since_minutes=&service=cb-app&http_status=503", Discovery(evidence()))
    assert status == 200
    assert doc["groups"] == []
    assert doc["counts"]["matchingRequests"] == 0


def test_http_and_mcp_grouping_identity_and_counts_match():
    from tracelens_cli.mcp_server import TraceLensMcpServer

    mcp = TraceLensMcpServer("http://127.0.0.1:1", None)
    mcp.discovery = Discovery(evidence())
    result = mcp._dispatch_tool("list_error_groups", {"since_minutes": None, "category": "http5xx"})
    status, doc = request("/api/error-groups?since_minutes=&category=http5xx", Discovery(evidence()))
    assert status == 200
    assert result["isError"] is False
    presented = result["structuredContent"]
    assert presented["counts"] == doc["counts"]
    assert presented["groups"] == [{key: value for key, value in group.items() if key != "requestKeys"}
                                   for group in doc["groups"]]
    assert presented["returnedGroupCount"] == len(doc["groups"])
    assert presented["omittedGroupCount"] == 0
    assert "requests" not in presented
    assert len(doc["requests"]) == 1  # HTTP/UI keeps the unprojected shared model.
    for field in ("schemaVersion", "kind", "scope", "partial", "limited", "errors", "warnings"):
        assert presented[field] == doc[field]


@pytest.mark.parametrize("query", [
    "category=unrecognized", "http_status=503.1", "http_status=true", "http_status=99",
    "since_minutes=NaN", "since_minutes=inf", "since_minutes=-1",
    "min_duration=100&max_duration=10", "service=cb-app&service=cb-ems", "upstream=https://example.invalid",
])
def test_invalid_filters_are_rejected_before_upstream_reads(query):
    discovery = Discovery(evidence())
    status, doc = request("/api/error-groups?" + query, discovery)
    assert status == 400
    assert "error" in doc
    assert discovery.calls == []


def test_offline_and_limited_data_are_not_application_error_groups():
    data = {"traces": [], "errors": [{"service": "cb-ems", "error": "offline"}],
            "partial": True, "limited": True}
    status, doc = request("/api/error-groups?since_minutes=", Discovery(data))
    assert status == 200
    assert doc["partial"] and doc["limited"]
    assert doc["groups"] == []
    assert doc["errors"][0]["service"] == "cb-ems"
