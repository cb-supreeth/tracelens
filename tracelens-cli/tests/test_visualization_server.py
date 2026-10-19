"""The HTTP inspector and MCP must share one deterministic evidence model."""
import copy

import pytest

from tracelens_cli import server
from tracelens_cli.services import Federation, Service
from tracelens_cli.visualization import build_visualization


TRACE = "1234567890abcdef1234567890abcdef"


def evidence():
    return {
        "traceId": TRACE,
        "segments": [{"segmentKey": "app-key", "segmentId": "app-segment", "service": "app",
                      "serviceInstanceId": "instance-a", "entryChannel": "HTTP", "status": "COMPLETED",
                      "attributes": {"httpMethod": "GET", "path": "/features/test1", "httpStatus": "200"},
                      "durationMs": 12, "eventCount": 3, "user": "private-user",
                      "contextParams": {"private": "do-not-copy"}}],
        "httpCalls": [], "links": [], "warnings": [], "errors": [],
        "partial": False, "completeness": "observed-only",
        "note": "Live retained evidence only",
    }


class Discovery:
    def __init__(self, doc):
        self.doc = doc
        self.calls = []

    def get_distributed_trace(self, trace_id):
        self.calls.append(trace_id)
        return copy.deepcopy(self.doc)


def request(path, discovery):
    # Exercise actual route dispatch without requiring a listening socket.
    handler = server._Handler.__new__(server._Handler)
    handler.path = path
    handler.discovery = discovery
    results = []
    handler._send_json = lambda doc, status: results.append((status, doc))
    handler.do_GET()
    assert len(results) == 1
    return results[0]


def test_journey_includes_shared_graph_without_second_upstream_read():
    discovery = Discovery(evidence())
    status, doc = request("/api/distributed-trace/" + TRACE, discovery)
    assert status == 200
    assert doc["visualization"] == build_visualization(evidence())
    assert doc["segments"] == evidence()["segments"]  # existing inspector is preserved
    assert discovery.calls == [TRACE]


def test_direct_graph_endpoint_returns_only_canonical_evidence():
    discovery = Discovery(evidence())
    status, doc = request("/api/trace-visualization/" + TRACE, discovery)
    assert status == 200
    assert doc == build_visualization(evidence())
    assert "private-user" not in str(doc)
    assert "do-not-copy" not in str(doc)
    assert discovery.calls == [TRACE]


def test_http_and_mcp_return_the_same_model():
    from tracelens_cli.mcp_server import TraceLensMcpServer

    mcp = TraceLensMcpServer("http://127.0.0.1:7778", None)
    mcp.discovery = Discovery(evidence())
    response = mcp._dispatch_tool("get_trace_visualization", {"trace_id": TRACE})
    status, doc = request("/api/trace-visualization/" + TRACE, Discovery(evidence()))
    assert status == 200
    assert response["isError"] is False
    assert response["structuredContent"] == doc


@pytest.mark.parametrize("trace_id", ["%2Fetc%2Fpasswd", "https%3A%2F%2Funtrusted", "bad%20id"])
def test_graph_trace_id_validation_happens_before_any_network(trace_id):
    discovery = Federation([Service("local", "http://127.0.0.1:1")])
    status, doc = request("/api/trace-visualization/" + trace_id, discovery)
    assert status == 400
    assert doc == {"error": "invalid trace id"}
