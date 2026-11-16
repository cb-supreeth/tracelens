import copy
import json

import pytest

from tracelens_cli.guided_investigation import diagnose_request, MAX_REPORT_BYTES


TRACE = "1" * 32


def row(**extra):
    return {"traceId": TRACE, "segmentId": "root", "serviceInstanceId": "instance",
            "entryChannel": "HTTP", "httpMethod": "GET", "path": "/api/orders",
            "user": "Alice", "siteId": "42", "status": "COMPLETED", "httpStatus": 200,
            "startedAtMillis": 1000, **extra}


class Client:
    def __init__(self, *, metadata=None, summary=None, rows=None):
        self.meta = {"agentActive": True, "requireLabels": False, "httpIncludePaths": ["/api"],
                     "packages": ["com.example"], "idleTtlMinutes": 5, **(metadata or {})}
        self.summary_doc = {"agentActive": True, "recordingEnabled": True, "httpIncludePaths": ["/api"],
                            "maxTrackedRequests": 1000, **(summary or {})}
        self.rows = rows or []
        self.reads = []

    def metadata(self):
        self.reads.append("metadata")
        return copy.deepcopy(self.meta)

    def summary(self):
        self.reads.append("summary")
        return copy.deepcopy(self.summary_doc)

    def traces(self):
        self.reads.append("traces")
        return {"traces": copy.deepcopy(self.rows)}


class Discovery:
    def __init__(self, client=None):
        self.clients = {"app": client or Client()}
        self.services = {"app": None}


def check(result, key):
    return next(item for item in result["checks"] if item["id"] == key)


@pytest.mark.parametrize("kwargs", [
    {"path": None}, {"path": ""}, {"path": "https://example.test/api"}, {"path": "//evil/api"},
    {"path": "/api?password=secret"}, {"path": "/api#fragment"}, {"path": "/api/*"},
    {"path": "/api/{id}"}, {"path": "/api\n"}, {"path": "/api%0a"},
    {"path": "/api%xx"}, {"path": "/a" + "b" * 2048}, {"path": "/api", "user": {}},
    {"path": "/api", "site": "x" * 257}, {"path": "/api", "method": "GET /"},
    {"path": "/api", "service": "unknown"}, {"path": "/api", "site": "\u200e42"},
])
def test_validation_precedes_any_io(kwargs):
    discovery = Discovery()
    with pytest.raises(ValueError):
        diagnose_request(discovery, **kwargs)
    assert discovery.clients["app"].reads == []


@pytest.mark.parametrize("path,allowed", [
    ("/api", True), ("/api/orders", True), ("/apix", False), ("/API/orders", False),
])
def test_actual_running_paths_use_directory_boundary_matching(path, allowed):
    result = diagnose_request(Discovery(), path)
    assert check(result, "http_path")["details"]["included"] is allowed
    assert check(result, "http_path")["status"] == ("pass" if allowed else "excluded")
    assert result["status"] == "no_retained_match"
    assert check(result, "retention")["status"] == "unknown"
    assert "does not prove expiry" in check(result, "retention")["message"]


@pytest.mark.parametrize("paths,allowed", [([], False), (["/"], True), (["/api///"], True)])
def test_empty_root_and_trailing_slash_effective_prefixes(paths, allowed):
    client = Client(metadata={"httpIncludePaths": paths}, summary={"httpIncludePaths": paths})
    assert check(diagnose_request(Discovery(client), "/api/orders"), "http_path")["details"]["included"] is allowed


def test_conflicting_or_missing_paths_are_unknown_not_exclusion():
    client = Client(metadata={"httpIncludePaths": ["/elsewhere"]})
    assert check(diagnose_request(Discovery(client), "/api/orders"), "http_path")["status"] == "unknown"
    del client.meta["httpIncludePaths"]
    del client.summary_doc["httpIncludePaths"]
    result = diagnose_request(Discovery(client), "/api/orders")
    assert result["partial"]
    assert check(result, "http_path")["status"] == "unknown"


def test_exact_filters_match_one_segment_and_are_case_sensitive():
    client = Client(rows=[row(), row(segmentId="child", path="/api/downstream", user="Bob",
                                    siteId="13", parentSpanId="parent")])
    result = diagnose_request(Discovery(client), "/api/orders", method="get", service="app", user="Alice", site="42")
    assert result["counts"]["matchingRequests"] == 1
    assert result["candidates"][0]["segmentCount"] == 2
    assert result["candidates"][0]["traceId"] == TRACE
    assert diagnose_request(Discovery(client), "/api/orders", user="alice")["candidates"] == []
    assert diagnose_request(Discovery(client), "/api/orders", user="Bob")["candidates"] == []
    assert diagnose_request(Discovery(client), "/api/order")["candidates"] == []
    assert set(client.reads) == {"metadata", "summary", "traces"}


def test_site_attribute_scalar_and_non_http_exclusion():
    client = Client(rows=[row(siteId=None, attributes={"siteId": 42}),
                         row(traceId="2" * 32, segmentId="job", entryChannel="SCHEDULER")])
    result = diagnose_request(Discovery(client), "/api/orders", site="42")
    assert [c["traceId"] for c in result["candidates"]] == [TRACE]


def test_labels_recording_agent_scope_are_current_evidence_only():
    client = Client(metadata={"requireLabels": True, "agentActive": False, "packages": []},
                    summary={"recordingEnabled": False, "agentActive": False})
    result = diagnose_request(Discovery(client), "/api/orders")
    assert check(result, "labels")["status"] == "warn"
    assert "user/site filters do not supply a label" in check(result, "labels")["message"]
    assert check(result, "agent")["status"] == "fail"
    assert check(result, "recording")["status"] == "excluded"
    assert check(result, "scope")["status"] == "warn"
    assert check(result, "retention")["status"] == "unknown"


def test_unreachable_and_malformed_reads_never_return_exception_or_claim_expiry():
    client = Client()
    def fail():
        raise OSError("SECRET endpoint body with token")
    client.summary = client.metadata = client.traces = fail
    result = diagnose_request(Discovery(client), "/api/orders")
    assert result["partial"]
    assert check(result, "reachability")["status"] == "unknown"
    assert result["candidates"] == []
    assert "SECRET" not in json.dumps(result)
    assert check(result, "retention")["status"] == "unknown"


def test_summary_and_candidates_are_bounded_and_never_echo_events():
    client = Client(rows=[row(traceId=f"trace-{i}", segmentId=str(i), events=[{"source": "SECRET_SOURCE"}],
                             contextParams={"token": "SECRET_TOKEN"}) for i in range(1100)])
    result = diagnose_request(Discovery(client), "/api/orders")
    assert result["counts"]["loadedRequests"] == 1000
    assert result["counts"]["matchingRequests"] == 1000
    assert len(result["candidates"]) == 20
    assert result["limited"] and result["partial"]
    assert "SECRET" not in json.dumps(result)
    assert len(json.dumps(result).encode()) < MAX_REPORT_BYTES


def test_selected_service_limits_upstream_reads():
    discovery = Discovery()
    discovery.services["other"] = None
    discovery.clients["other"] = Client()
    diagnose_request(discovery, "/api/orders", service="app")
    assert not discovery.clients["other"].reads


def test_real_summary_wire_read_has_byte_timeout_and_redirect_bounds(monkeypatch):
    from tracelens_cli import guided_investigation
    from tracelens_cli.client import TraceLensClient
    read_sizes, timeouts = [], []
    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def read(self, size):
            read_sizes.append(size)
            return b'{"traces":[]}'
    class Opener:
        def open(self, req, timeout):
            assert req.full_url == "http://localhost:7778/tracelens/traces"
            timeouts.append(timeout)
            return Response()
    def opener(handler):
        assert isinstance(handler, guided_investigation._NoRedirect)
        return Opener()
    monkeypatch.setattr(guided_investigation.urllib.request, "build_opener", opener)
    guided_investigation._traces(TraceLensClient("http://localhost:7778", timeout=99))
    assert read_sizes == [guided_investigation.MAX_TRACE_BYTES + 1]
    assert timeouts == [5]
