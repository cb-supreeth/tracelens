"""Summary polling limits upstream bytes, redirects and sensitive failures."""

import io
import json
import urllib.error

import pytest

from tracelens_cli import client
from tracelens_cli.services import Federation, Service


def opener(monkeypatch, data=b'{"traces":[]}', failure=None):
    calls, reads, handlers = [], [], []
    class Response(io.BytesIO):
        def read(self, size=-1):
            assert 0 < size <= client.MAX_TRACE_SUMMARY_BYTES + 1
            reads.append(size)
            if failure:
                raise failure
            return super().read(size)
    response = Response(data)
    class Opener:
        def open(self, request, timeout):
            calls.append((request.full_url, request.method, timeout))
            return response
    def build(*values):
        handlers.extend(values)
        return Opener()
    monkeypatch.setattr(client.urllib.request, "build_opener", build)
    return calls, reads, handlers, response


def test_default_trace_summary_read_is_bounded_and_disallows_redirects(monkeypatch):
    calls, reads, handlers, response = opener(monkeypatch)
    assert client.TraceLensClient("http://configured.test").traces() == {"traces": []}
    assert calls == [("http://configured.test/tracelens/traces", "GET", 5.0)]
    assert reads == [client.MAX_TRACE_SUMMARY_BYTES + 1]
    assert response.closed
    assert len(handlers) == 1
    assert handlers[0].redirect_request(None, None, 302, "", {}, "http://other.test") is None


@pytest.mark.parametrize("timeout,expected", [(0.25, 0.25), (5, 5), (60, 5.0), (0, 5.0),
                                             (-1, 5.0), (float("nan"), 5.0), (True, 5.0), ("secret", 5.0)])
def test_summary_timeout_has_a_finite_five_second_ceiling(monkeypatch, timeout, expected):
    calls, _, _, _ = opener(monkeypatch)
    client.TraceLensClient(timeout=timeout).traces()
    assert calls[0][2] == expected


def test_tighter_budget_and_overflow_never_parse_or_echo_body(monkeypatch):
    calls, reads, _, response = opener(monkeypatch, b"SECRET_RESPONSE" * 100)
    with pytest.raises(client.DebugApiError, match="byte limit") as raised:
        client.TraceLensClient("http://SECRET_ENDPOINT").traces(max_bytes=16)
    assert reads == [17] and response.closed
    assert "SECRET" not in str(raised.value)
    assert len(calls) == 1


@pytest.mark.parametrize("value", [None, True, 0, -1, 1024 * 1024 + 1, "1048576"])
def test_invalid_summary_budget_fails_before_network(monkeypatch, value):
    monkeypatch.setattr(client.urllib.request, "build_opener",
                        lambda *_: pytest.fail("Invalid budgets must fail before network"))
    with pytest.raises(ValueError, match="byte limit"):
        client.TraceLensClient().traces(max_bytes=value)


@pytest.mark.parametrize("failure", [TimeoutError("SECRET_TIMEOUT"), OSError("SECRET_DISK"),
                                    urllib.error.URLError("SECRET_CONNECTION")])
def test_read_failures_are_closed_and_sanitized(monkeypatch, failure):
    _, _, _, response = opener(monkeypatch, failure=failure)
    with pytest.raises(client.DebugApiError, match="unavailable") as raised:
        client.TraceLensClient("http://SECRET_ENDPOINT").traces()
    assert response.closed
    assert "SECRET" not in str(raised.value)


@pytest.mark.parametrize("status", [302, 403, 500])
def test_http_errors_and_redirects_do_not_read_or_expose_error_bodies(monkeypatch, status):
    class Body(io.BytesIO):
        def read(self, *_):
            pytest.fail("An error response body must never be read")
    body = Body(b"SECRET_RESPONSE")
    class Opener:
        def open(self, *_args, **_kwargs):
            raise urllib.error.HTTPError("http://SECRET_ENDPOINT", status, "SECRET_REASON", {}, body)
    monkeypatch.setattr(client.urllib.request, "build_opener", lambda *_: Opener())
    with pytest.raises(client.DebugApiError) as raised:
        client.TraceLensClient().traces()
    assert raised.value.status == status and body.closed
    assert "SECRET" not in str(raised.value)


@pytest.mark.parametrize("data", [b"SECRET_NOT_JSON", b"\xff", b"[]", b"{}", b'{"traces":{}}',
                                 b'{"traces":[null]}', b'{"traces":[],"secret":NaN}'])
def test_invalid_summary_documents_become_sanitized_discovery_failures(monkeypatch, data):
    opener(monkeypatch, data)
    with pytest.raises(client.DebugApiError, match="invalid JSON") as raised:
        client.TraceLensClient().traces()
    assert "SECRET" not in str(raised.value)


def test_federation_marks_overflow_partial_without_leaking_payload_or_endpoint(monkeypatch):
    opener(monkeypatch, b"SECRET" * (client.MAX_TRACE_SUMMARY_BYTES // 6 + 1))
    federation = Federation([Service("app", "http://SECRET_ENDPOINT")])
    result = federation.traces()
    assert result["partial"] and result["traces"] == [] and result["count"] == 0
    assert result["errors"] == [{"service": "app", "error": "Trace summaries exceed the response byte limit."}]
    assert "SECRET" not in json.dumps(result)
