"""The viewer computes the shared execution model from uploaded evidence only."""

import copy
from email.message import Message
import http.client
import io
import json
import threading

import pytest

from tracelens_cli import server
from tracelens_cli.execution import MODEL_MAX_BYTES, VIEWER_MODEL_MAX_BYTES, build_segment_execution
from tracelens_cli.services import Service


def evidence():
    return {
        "segmentId": "historical-segment", "traceId": "historical-trace",
        "service": "cb-app", "serviceInstanceId": "previous-jvm-instance",
        "status": "COMPLETED", "eventCount": 2,
        "user": "private-user", "contextParams": {"secret": "private-payload"},
        "httpCalls": [{"requestBody": "private-http-payload"}],
        "events": [
            {"seq": 1, "type": "METHOD_ENTER", "thread": "worker",
             "className": "com.example.Controller", "methodName": "run", "nanoTime": 1000000},
            {"seq": 2, "type": "METHOD_EXIT", "thread": "worker",
             "className": "com.example.Controller", "methodName": "run", "nanoTime": 3000000},
        ],
    }


class Connection:
    def __init__(self):
        self.timeout = None
        self.timeouts = []

    def gettimeout(self):
        return self.timeout

    def settimeout(self, value):
        self.timeouts.append(value)
        self.timeout = value


class Body(io.BytesIO):
    def __init__(self, data):
        super().__init__(data)
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        return super().read(size)


class NoServiceIO:
    def __getattr__(self, name):
        raise AssertionError("segment execution must not access services: " + name)


@pytest.fixture(autouse=True)
def forbid_io(monkeypatch):
    def fail(*args, **kwargs):
        raise AssertionError("segment execution must not read agents or local sources")

    monkeypatch.setattr(server.urllib.request, "urlopen", fail)
    monkeypatch.setattr(server.source, "source_line", fail)
    monkeypatch.setattr(server._Handler, "_proxy", fail)


def request(body=None, *, headers=None, federated=False, path="/api/segment-execution", stream=None):
    if body is None:
        body = json.dumps({"trace": evidence()}).encode("utf-8")
    if headers is None:
        headers = [("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
    handler = server._Handler.__new__(server._Handler)
    handler.path = path
    handler.headers = Message()
    for name, value in headers:
        handler.headers[name] = value
    handler.connection = Connection()
    handler.rfile = stream if stream is not None else Body(body)
    handler.federation = NoServiceIO() if federated else None
    handler.discovery = NoServiceIO()
    responses = []
    handler._send_json = lambda doc, status: responses.append((status, doc))
    handler.do_POST()
    assert len(responses) == 1
    return *responses[0], handler


@pytest.mark.parametrize("federated", [False, True])
def test_supplied_historical_evidence_returns_exact_shared_model_without_io(federated):
    trace = evidence()
    original = copy.deepcopy(trace)
    body = json.dumps({"trace": trace}).encode("utf-8")
    status, doc, handler = request(body, federated=federated)
    assert status == 200
    assert doc == build_segment_execution(trace, max_model_bytes=VIEWER_MODEL_MAX_BYTES)
    assert doc["segment"]["serviceInstanceId"] == "previous-jvm-instance"
    assert trace == original
    assert doc["nodes"][0]["durationMs"] == 2
    assert all(secret not in json.dumps(doc) for secret in (
        "private-user", "private-payload", "private-http-payload"))
    assert handler.rfile.read_sizes == [len(body)]
    assert handler.connection.timeouts == [server.SEGMENT_EXECUTION_READ_TIMEOUT_SECONDS, None]
    assert handler.close_connection is True


@pytest.mark.parametrize("max_nodes", [1, 200, 500])
def test_explicit_node_limit_is_forwarded_to_authoritative_builder(max_nodes):
    status, doc, _ = request(json.dumps({"trace": evidence(), "maxNodes": max_nodes}).encode())
    assert status == 200
    assert doc == build_segment_execution(evidence(), max_nodes=max_nodes,
                                          max_model_bytes=VIEWER_MODEL_MAX_BYTES)


def test_viewer_budget_retains_late_cache_methods_omitted_by_default_mcp_budget():
    trace = evidence()
    events = []
    for index in range(180):
        class_name = "com.example.FeatureService" if index < 178 else "com.example.RedisCache"
        method_name = "evaluateFeature" if index < 178 else ("getCachedFeature" if index == 178 else "setCachedFeature")
        for kind in ("METHOD_ENTER", "METHOD_EXIT"):
            seq = len(events) + 1
            events.append({"seq": seq, "type": kind, "thread": "worker",
                           "className": class_name, "methodName": method_name,
                           "nanoTime": seq * 1_000_000})
    trace.update(events=events, eventCount=len(events))
    original = copy.deepcopy(trace)
    default_model = build_segment_execution(trace)
    assert default_model["limits"]["maxModelBytes"] == MODEL_MAX_BYTES == 32 * 1024
    assert all(node["className"] != "com.example.RedisCache" for node in default_model["nodes"])
    assert any(warning["code"] == "model_byte_limit" for warning in default_model["warnings"])

    status, viewer_model, _ = request(json.dumps({"trace": trace}).encode())
    assert status == 200
    assert viewer_model == build_segment_execution(trace, max_model_bytes=VIEWER_MODEL_MAX_BYTES)
    assert viewer_model["limits"]["maxModelBytes"] == VIEWER_MODEL_MAX_BYTES == 128 * 1024
    assert viewer_model["counts"]["nodesReturned"] == 180
    assert viewer_model["counts"]["nodesOmitted"] == 0
    assert viewer_model["partial"] is False
    assert [node["methodName"] for node in viewer_model["nodes"][-2:]] == [
        "getCachedFeature", "setCachedFeature",
    ]
    assert all(node["complete"] for node in viewer_model["nodes"])
    # A cache-like class name remains invocation evidence, not a fabricated
    # outbound marker or proof of a Redis command.
    assert all(node["kind"] == "invocation" and "boundary" not in node for node in viewer_model["nodes"])
    encoded = json.dumps(viewer_model, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    assert MODEL_MAX_BYTES < len(encoded) <= VIEWER_MODEL_MAX_BYTES
    assert trace == original


@pytest.mark.parametrize("trace", [{}, {"events": None}, {"events": [None, {}], "truncated": True}])
def test_partial_event_evidence_retains_authoritative_warnings(trace):
    status, doc, _ = request(json.dumps({"trace": trace}).encode())
    assert status == 200
    assert doc == build_segment_execution(trace, max_model_bytes=VIEWER_MODEL_MAX_BYTES)
    assert doc["partial"] is True


@pytest.mark.parametrize("doc", [
    None, [], "private-payload", {}, {"trace": []}, {"trace": None},
    {"trace": {}, "upstream": "http://untrusted.invalid"},
    {"trace": {}, "maxModelBytes": VIEWER_MODEL_MAX_BYTES * 2},
    {"trace": {}, "max_model_bytes": VIEWER_MODEL_MAX_BYTES * 2},
    *({"trace": {}, "maxNodes": value} for value in (None, False, True, 0, -1, 501, 1.5, "2")),
])
def test_bad_request_shapes_and_node_limits_return_clean_400(doc):
    status, result, _ = request(json.dumps(doc).encode())
    assert status == 400
    assert set(result) == {"error"}
    assert "private-payload" not in result["error"]


@pytest.mark.parametrize("body", [
    b"", b"{private-payload", b'{} {}', b'\xff',
    b'{"trace":{},"trace":{}}', b'{"trace":{"events":[],"events":[]}}',
    b'{"trace":{},"maxNodes":NaN}', b'{"trace":{},"maxNodes":Infinity}',
    b'{"trace":' + b'[' * 2000 + b']' * 2000 + b'}',
])
def test_malformed_non_utf8_ambiguous_or_deep_json_returns_clean_400(body):
    status, doc, _ = request(body)
    assert status == 400
    assert set(doc) == {"error"}
    assert "private-payload" not in doc["error"]


@pytest.mark.parametrize("lengths", [[], [""], ["-1"], ["+2"], ["2.0"], ["2, 2"],
                                      [" 2"], ["2 "], ["٢"], ["2", "2"], ["2", "3"]])
def test_invalid_or_ambiguous_content_length_is_rejected_before_read(lengths):
    headers = [("Content-Type", "application/json"), *(("Content-Length", n) for n in lengths)]
    status, doc, handler = request(b"{}", headers=headers)
    assert status == 400
    assert "error" in doc
    assert handler.rfile.read_sizes == []
    assert handler.close_connection is True


@pytest.mark.parametrize("extra_headers", [
    [], [("Content-Type", "text/plain")], [("Content-Type", "application/octet-stream")],
    [("Content-Type", "application/json; charset=iso-8859-1")],
    [("Content-Type", "application/json"), ("Content-Type", "application/json")],
    [("Content-Type", "application/json"), ("Transfer-Encoding", "chunked")],
    [("Content-Type", "application/json"), ("Transfer-Encoding", "identity")],
    [("Content-Type", "application/json"), ("Content-Encoding", "gzip")],
])
def test_unsupported_content_or_transfer_encoding_is_rejected_before_read(extra_headers):
    status, _, handler = request(b"{}", headers=[("Content-Length", "2"), *extra_headers])
    assert status == 400
    assert handler.rfile.read_sizes == []


@pytest.mark.parametrize("length", [str(server.SEGMENT_EXECUTION_REQUEST_MAX_BYTES + 1), "9" * 5000])
def test_oversized_request_is_413_without_reading_or_parsing_body(length):
    status, doc, handler = request(b"", headers=[("Content-Length", length),
                                                ("Content-Type", "application/json")])
    assert status == 413
    assert "5 MiB" in doc["error"]
    assert handler.rfile.read_sizes == []


def test_exact_body_limit_and_leading_zero_length_are_accepted():
    prefix = b'{"trace":{"events":[],"ignored":"'
    suffix = b'"}}'
    length = server.SEGMENT_EXECUTION_REQUEST_MAX_BYTES
    body = prefix + b"x" * (length - len(prefix) - len(suffix)) + suffix
    status, doc, handler = request(body, headers=[("Content-Type", "Application/JSON; charset=UTF-8"),
                                                ("Content-Length", "000" + str(length))])
    assert status == 200
    assert doc == build_segment_execution({"events": []}, max_model_bytes=VIEWER_MODEL_MAX_BYTES)
    assert handler.rfile.read_sizes == [length]


def test_short_body_is_clean_400_and_timeout_is_restored():
    status, _, handler = request(b"{}", headers=[("Content-Type", "application/json"),
                                                ("Content-Length", "10")])
    assert status == 400
    assert handler.connection.timeout is None


def test_stalled_body_is_clean_400_and_timeout_is_restored():
    class StalledBody:
        def read(self, size):
            raise TimeoutError("private-payload")

    status, doc, handler = request(stream=StalledBody())
    assert status == 400
    assert "private-payload" not in doc["error"]
    assert handler.connection.timeout is None


@pytest.mark.parametrize("error", [ValueError, TypeError, RecursionError, OverflowError])
def test_model_validation_errors_do_not_echo_uploaded_data(monkeypatch, error):
    def fail(*args, **kwargs):
        raise error("private-payload")

    monkeypatch.setattr(server, "build_segment_execution", fail)
    status, doc, _ = request()
    assert status == 400
    assert "private-payload" not in doc["error"]


@pytest.mark.parametrize("path", ["/api/enable", "/api/disable", "/api/reset", "/api/reset/segment"])
def test_pure_route_does_not_enable_existing_federated_mutations(path):
    status, _, handler = request(federated=True, path=path)
    assert status == 405
    assert handler.rfile.read_sizes == []


@pytest.mark.parametrize("federated", [False, True])
def test_http_post_uses_supplied_evidence_with_unreachable_agents(federated):
    services = [Service("cb-app", "http://127.0.0.1:1")] if federated else None
    bridge = server.make_server(0, "http://127.0.0.1:1", None, services=services)
    worker = threading.Thread(target=bridge.serve_forever, daemon=True)
    worker.start()
    connection = http.client.HTTPConnection("127.0.0.1", bridge.server_port, timeout=3)
    try:
        connection.request("POST", "/api/segment-execution", json.dumps({"trace": evidence()}),
                           {"Content-Type": "application/json"})
        response = connection.getresponse()
        body = response.read()
        assert response.status == 200
        assert response.getheader("Content-Type") == "application/json"
        assert int(response.getheader("Content-Length")) == len(body)
        assert json.loads(body) == build_segment_execution(evidence(),
                                                          max_model_bytes=VIEWER_MODEL_MAX_BYTES)
    finally:
        connection.close()
        bridge.shutdown()
        bridge.server_close()
        worker.join(timeout=3)
