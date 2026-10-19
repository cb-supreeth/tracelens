import copy
import json

import pytest

from tracelens_cli import investigation
from tracelens_cli.investigation import MAX_CANDIDATES, MAX_MODEL_BYTES, investigate_request
from tracelens_cli.services import join_trace


NOW = 2_000_000_000_000
TRACE = "1" * 32


def segment(segment_id="root", service="app", **values):
    return {"traceId": TRACE, "segmentId": segment_id, "service": service,
            "segmentKey": service + ":" + segment_id, "serviceInstanceId": service + "-instance",
            "parentSpanId": None, "entryChannel": "HTTP", "httpMethod": "GET",
            "path": "/api/features", "httpStatus": 200, "status": "COMPLETED",
            "durationMs": 30, "startedAtMillis": NOW - 1000, "lastActivityMillis": NOW,
            "eventCount": 10, "user": "Alice", "lensId": "scenario:upgrade", **values}


class Discovery:
    def __init__(self, rows, *, partial=False, limited=False, journey=None):
        self.rows = rows
        self.services = {"app": None, "ems": None}
        self.partial, self.limited, self.journey = partial, limited, journey
        self.reads = []

    def traces(self, **filters):
        self.reads.append(("traces", filters))
        return {"traces": copy.deepcopy(self.rows), "partial": self.partial, "limited": self.limited,
                "errors": [{"service": "ems", "error": "SECRET_EXCEPTION_MESSAGE"}] if self.partial else []}

    def get_distributed_trace(self, trace_id):
        self.reads.append(("journey", trace_id))
        if self.journey is not None:
            return copy.deepcopy(self.journey)
        return join_trace(trace_id, copy.deepcopy([r for r in self.rows if r["traceId"] == trace_id]), [])


@pytest.fixture(autouse=True)
def fixed_clock(monkeypatch):
    monkeypatch.setattr(investigation.time, "time", lambda: NOW / 1000)


def test_one_request_is_selected_and_multiple_segments_are_not_ambiguous():
    rows = [segment(), segment("child", "ems", parentSpanId="parent", httpStatus=503)]
    discovery = Discovery(rows)
    original = copy.deepcopy(rows)
    result = investigate_request(discovery, path="FEATURES", service="ems", method="get", user="alice")
    assert result["status"] == "selected"
    assert result["selectedTraceId"] == TRACE
    assert len(result["candidates"]) == 1
    assert result["candidates"][0]["segmentCount"] == 2
    assert result["candidates"][0]["outcome"] == "error"
    assert result["investigation"]["request"]["httpStatus"] == 200
    assert result["investigation"]["request"]["durationMs"] == 30  # not summed across JVMs
    assert result["investigation"]["hasObservedFailure"]
    assert result["investigation"]["failureEvidence"][0]["httpStatus"] == 503
    assert {n["service"] for n in result["investigation"]["journey"]["nodes"]} == {"app", "ems"}
    assert discovery.reads == [("traces", {"limit": 10000}), ("journey", TRACE)]
    assert rows == original


def test_no_silent_latest_selection_and_ambiguity_does_not_export():
    rows = [segment(), segment("new", traceId="2" * 32, startedAtMillis=NOW - 500)]
    discovery = Discovery(rows)
    result = investigate_request(discovery, label="upgrade")
    assert result["status"] == "ambiguous"
    assert result["selectedTraceId"] is None
    assert result["counts"]["matchingRequests"] == 2
    assert discovery.reads == [("traces", {"limit": 10000})]
    result = investigate_request(discovery, label="upgrade", selection="latest")
    assert result["status"] == "selected"
    assert result["selectedTraceId"] == "2" * 32


def test_latest_timestamp_tie_does_not_arbitrarily_select():
    discovery = Discovery([segment(), segment("two", traceId="2" * 32)])
    result = investigate_request(discovery, path="features", selection="latest")
    assert result["status"] == "ambiguous"
    assert "latest_timestamp_ambiguous" in result["warnings"]
    assert len(discovery.reads) == 1


@pytest.mark.parametrize("state", [{"partial": True}, {"limited": True}])
@pytest.mark.parametrize("selection", ["unique", "latest"])
def test_incomplete_discovery_cannot_prove_unique_or_latest(state, selection):
    discovery = Discovery([segment()], **state)
    result = investigate_request(discovery, path="features", selection=selection)
    assert result["status"] == "ambiguous"
    assert result["partial"]
    assert len(discovery.reads) == 1
    assert "SECRET_EXCEPTION_MESSAGE" not in json.dumps(result)


def test_exact_trace_id_needs_no_broad_discovery_and_preserves_case():
    discovery = Discovery([segment(traceId="Legacy-A")])
    result = investigate_request(discovery, trace_id="Legacy-A")
    assert result["status"] == "selected" and result["selectedTraceId"] == "Legacy-A"
    assert discovery.reads == [("journey", "Legacy-A")]


def test_exact_trace_id_still_honors_supplied_filters():
    discovery = Discovery([segment()])
    result = investigate_request(discovery, trace_id=TRACE, path="another-path")
    assert result["status"] == "not_found"
    assert discovery.reads == [("journey", TRACE)]


@pytest.mark.parametrize("arguments", [
    {}, {"method": "GET"}, {"path": " "}, {"path": "features", "service": "unknown"},
    {"path": "features", "since_minutes": float("nan")},
    {"path": "features", "since_minutes": float("inf")},
    {"path": "features", "since_minutes": True}, {"path": "features", "since_minutes": -1},
    {"path": "features", "since_minutes": 10 ** 400},
    {"path": "features", "outcome": "failed"}, {"path": "features", "selection": "first"},
    {"trace_id": "../../private"}, {"path": "features\n"}, {"label": "x" * 257},
    {"path": "features", "user": []}, {"path": "features", "outcome": []},
])
def test_invalid_filters_fail_before_any_io(arguments):
    discovery = Discovery([segment()])
    with pytest.raises(ValueError):
        investigate_request(discovery, **arguments)
    assert not discovery.reads


def test_path_label_user_method_service_and_time_filter_together():
    rows = [segment(), segment("old", traceId="2" * 32, startedAtMillis=NOW - 700_000),
            segment("other", traceId="3" * 32, lensId="scenario:cancel")]
    result = investigate_request(Discovery(rows), path="features", label="upgrade", user="ALICE",
                                 method="get", service="app", since_minutes=10)
    assert result["selectedTraceId"] == TRACE
    assert investigate_request(Discovery(rows), path="missing")["status"] == "not_found"
    assert investigate_request(Discovery(rows), label="upgrade", user="bob")["status"] == "not_found"


def test_outcome_filters_use_whole_request_including_downstream():
    discovery = Discovery([segment(), segment("child", "ems", parentSpanId="parent", httpStatus=500)])
    assert investigate_request(discovery, path="features", outcome="error")["status"] == "selected"
    assert investigate_request(discovery, path="features", outcome="success")["status"] == "not_found"


def test_missing_root_is_unknown_not_success_and_exception_is_not_automatically_failure():
    result = investigate_request(Discovery([segment(parentSpanId="missing", hasExceptions=True)]),
                                 path="features", outcome="unknown")
    assert result["status"] == "selected"
    detail = result["investigation"]
    assert detail["request"]["outcome"] == "unknown"
    assert detail["request"]["httpStatus"] is None
    assert detail["request"]["durationMs"] is None
    assert not detail["hasObservedFailure"]
    assert detail["journey"]["edges"] == []
    assert "root_missing_or_ambiguous" in result["warnings"]


def test_http_call_failure_is_observed_even_when_root_succeeded():
    root = segment(httpCalls=[{"spanId": "outbound", "httpStatus": 502, "status": "COMPLETED",
                               "httpMethod": "GET", "path": "/dependency"}])
    result = investigate_request(Discovery([root]), path="features")
    detail = result["investigation"]
    assert detail["hasObservedFailure"]
    assert detail["request"]["httpStatus"] == 200
    assert detail["request"]["outcome"] == "error"
    assert result["candidates"][0]["outcome"] == "error"
    assert detail["failureEvidence"][0]["kind"] == "http-call"


def test_hidden_failure_evidence_still_affects_outcome():
    rows = [segment(str(index), parentSpanId="parent" if index else None, hasExceptions=True)
            for index in range(21)]
    rows[0]["httpCalls"] = [{"spanId": "outbound", "httpStatus": 500}]
    result = investigate_request(Discovery(rows), path="features")
    detail = result["investigation"]
    assert detail["hasObservedFailure"]
    assert detail["request"]["outcome"] == "error"
    assert detail["failureEvidenceCount"] == 22
    assert len(detail["failureEvidence"]) == 20


def test_raw_payload_source_user_exception_and_upstream_messages_never_returned():
    root = segment(user="SECRET_USER", events=[{"message": "SECRET_SOURCE"}],
                   contextParams={"password": "SECRET_PAYLOAD"},
                   filterException="SECRET_EXCEPTION_MESSAGE: do not copy")
    journey = join_trace(TRACE, [root], [{"service": "ems", "error": "SECRET_UPSTREAM_EXCEPTION"}])
    journey["warnings"] = ["SECRET_WARNING"]
    result = investigate_request(Discovery([root], journey=journey), path="features")
    rendered = json.dumps(result)
    assert "SECRET_" not in rendered
    assert result["partial"]


def test_expired_selected_request_is_not_silently_replaced():
    result = investigate_request(Discovery([segment()], journey={"segments": []}), path="features")
    assert result["status"] == "selected"
    assert result["selectedTraceId"] == TRACE
    assert "selected_request_no_longer_available" in result["warnings"]
    assert result["investigation"]["journey"]["nodes"] == []


def test_identical_duplicates_do_not_manufacture_an_ambiguous_root():
    root = segment()
    result = investigate_request(Discovery([root, copy.deepcopy(root)]), path="features")
    assert result["status"] == "selected"
    assert result["candidates"][0]["segmentCount"] == 1
    assert not result["candidates"][0]["rootMissing"]


def test_conflicting_identity_prevents_automatic_selection():
    result = investigate_request(Discovery([segment(), segment(httpStatus=500)]), path="features")
    assert result["status"] == "ambiguous"
    assert "conflicting_segment_identity" in result["warnings"]


def test_candidate_list_is_bounded_but_total_ambiguity_is_not_hidden():
    rows = [segment(str(index), traceId=f"trace-{index}") for index in range(100)]
    result = investigate_request(Discovery(rows), path="features")
    assert result["status"] == "ambiguous"
    assert len(result["candidates"]) == MAX_CANDIDATES
    assert result["counts"]["matchingRequests"] == 100
    assert result["limited"]


def test_selected_output_is_byte_bounded_even_with_many_long_unicode_labels():
    rows = [segment(str(index), service="界" * 256, parentSpanId="parent" if index else None,
                    path="/" + "界" * 2047, lensId="界" * 256, httpStatus=500)
            for index in range(150)]
    result = investigate_request(Discovery(rows), label="界")
    assert result["status"] == "selected"
    assert len(json.dumps(result, ensure_ascii=True, separators=(",", ":")).encode("ascii")) <= MAX_MODEL_BYTES
    assert result["limited"] and result["partial"]
    nodes = result["investigation"]["journey"]["nodes"]
    ids = {node["id"] for node in nodes}
    assert all(edge["from"] in ids and edge["to"] in ids for edge in result["investigation"]["journey"]["edges"])
