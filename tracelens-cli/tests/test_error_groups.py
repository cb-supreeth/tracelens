import copy
import json
import re

import pytest

from tracelens_cli.error_groups import (
    MAX_ERRORS, MAX_SEGMENTS, build_error_groups, select_error_group,
    validate_filters, validate_group_id,
)


NOW = 2_000_000_000_000
TRACE = "1" * 32


def segment(segment_id="root", service="app", **values):
    return {"traceId": TRACE, "segmentId": segment_id, "service": service,
            "serviceInstanceId": service + "-instance", "parentSpanId": None,
            "entryChannel": "HTTP", "httpMethod": "GET", "path": "/features/item1",
            "routeTemplate": "/features/{id}", "httpStatus": 500, "status": "COMPLETED",
            "durationMs": 30, "startedAtMillis": NOW - 1000, "lastActivityMillis": NOW - 950,
            "eventCount": 2, "user": "alice", "siteId": "42", **values}


def build(rows, filters=None, **doc):
    return build_error_groups({"traces": rows, **doc}, filters, NOW)


def test_cross_service_root_success_is_still_a_request_with_observed_downstream_error():
    rows = [segment(httpStatus=200), segment("child", "ems", parentSpanId="parent", httpStatus="503", durationMs=12)]
    result = build(rows)
    assert result["schemaVersion"] == 1 and result["kind"] == "error-groups"
    assert result["scope"] == "retained-only"
    assert result["partial"] is False
    assert result["counts"] == {"loadedSegments": 2, "loadedRequests": 1, "matchingRequests": 1, "matchingSegments": 1, "groups": 1}
    group = result["groups"][0]
    assert (group["service"], group["category"], group["httpStatus"]) == ("ems", "http5xx", 503)
    assert group["requestCount"] == 1 and group["segmentCount"] == 1
    request = result["requests"][0]
    assert request["httpStatus"] == 200
    assert request["durationMs"] == 30  # local root only, never summed
    assert request["outcome"] == "error"
    assert request["hasErrors"]
    assert len(request["segments"]) == 2  # successful siblings remain available
    assert "recovered" not in json.dumps(result).lower()


def test_multiple_segments_in_group_count_one_request_and_multiple_groups_overlap():
    rows = [segment(), segment("child", "app", parentSpanId="parent"),
            segment("other", "ems", parentSpanId="parent", httpStatus=504)]
    result = build(rows)
    groups = {g["service"]: g for g in result["groups"]}
    assert groups["app"]["requestCount"] == 1
    assert groups["app"]["segmentCount"] == 2
    assert groups["ems"]["requestCount"] == 1
    assert result["counts"]["matchingRequests"] == 1
    assert result["counts"]["matchingSegments"] == 3
    assert sum(g["requestCount"] for g in groups.values()) == 2
    assert all(g["requestKeys"] == ["trace:" + TRACE] for g in groups.values())
    assert any("not additive" in warning for warning in result["warnings"])


def test_identical_duplicate_summaries_are_deduplicated_without_ambiguous_root():
    row = segment()
    result = build([row, copy.deepcopy(row)])
    assert result["counts"]["loadedSegments"] == 1
    assert result["groups"][0]["segmentCount"] == 1
    assert not result["requests"][0]["rootMissing"]
    assert result["partial"]
    assert any("deduplicated" in warning for warning in result["warnings"])


def test_conflicting_duplicate_identity_does_not_invent_root_or_inflate_segment_counts():
    result = build([segment(durationMs=10), segment(durationMs=20)])
    assert result["counts"]["loadedSegments"] == 2
    assert result["counts"]["matchingSegments"] == 1
    assert result["groups"][0]["segmentCount"] == 1
    assert result["requests"][0]["rootAmbiguous"]
    assert result["requests"][0]["durationMs"] is None
    assert any("Conflicting" in warning for warning in result["warnings"])


def test_conflicting_parent_metadata_cannot_make_one_variant_a_credible_root():
    result = build([segment(), segment(parentSpanId="contradictory-parent")])
    assert result["requests"][0]["rootMissing"]
    assert result["requests"][0]["httpStatus"] is None
    assert result["requests"][0]["durationMs"] is None


@pytest.mark.parametrize("rows,ambiguous", [([segment(parentSpanId="unobserved")], False),
    ([segment(), segment("second-root")], True)])
def test_missing_and_ambiguous_root_remain_unknown_but_explicit_segment_errors_group(rows, ambiguous):
    result = build(rows)
    request = result["requests"][0]
    assert request["rootMissing"] and request["rootAmbiguous"] is ambiguous
    assert request["httpStatus"] is None and request["durationMs"] is None
    assert request["outcome"] == "error"
    assert not build(rows, {"min_duration": 1})["groups"]


def test_error_filters_match_one_failing_segment_not_a_successful_sibling():
    rows = [segment(httpStatus=200, path="/entry", routeTemplate="/entry", httpMethod="POST"),
            segment("child", "ems", parentSpanId="parent", path="/downstream", routeTemplate="/downstream", httpStatus=502)]
    assert not build(rows, {"service": "app"})["groups"]
    assert not build(rows, {"service": "ems", "path": "/entry"})["groups"]
    assert not build(rows, {"service": "ems", "method": "POST"})["groups"]
    assert not build(rows, {"service": "ems", "http_status": 500})["groups"]
    assert not build(rows, {"service": "ems", "channel": "QUEUE"})["groups"]
    result = build(rows, {"service": "ems", "method": "get", "path": "downstream", "http_status": "502", "category": "http5xx"})
    assert result["counts"]["matchingRequests"] == 1
    assert len(result["requests"][0]["segments"]) == 2


def test_user_site_apply_together_to_one_request_segment_independently_of_failure():
    rows = [segment(httpStatus=200, user="root-user", siteId=42),
            segment("child", "ems", parentSpanId="p", user="child-user", siteId="43")]
    assert build(rows, {"service": "ems", "user": "root-user", "site": "42"})["groups"]
    assert not build(rows, {"service": "ems", "user": "root-user", "site": "43"})["groups"]
    assert not build(rows, {"site": "user"})["groups"]


@pytest.mark.parametrize("data,category,status", [
    ({"httpStatus": 400}, "http4xx", 400), ({"httpStatus": 499}, "http4xx", 499),
    ({"httpStatus": 500}, "http5xx", 500), ({"httpStatus": 599}, "http5xx", 599),
    ({"httpStatus": 503, "asyncTimeout": "true"}, "timeout", 503),
    ({"httpStatus": 200, "status": "ERROR"}, "execution", 200),
    ({"httpStatus": None, "status": "FAILED"}, "execution", None),
    ({"httpStatus": None, "hasErrors": True}, "execution", None),
])
def test_one_primary_category_and_exact_status_per_failing_segment(data, category, status):
    result = build([segment(**data)])
    assert result["counts"]["groups"] == 1
    assert result["groups"][0]["category"] == category
    assert result["groups"][0]["httpStatus"] == status


def test_exception_only_completed_unknown_and_active_are_not_failures():
    rows = [segment(httpStatus=200, filterException="java.lang.IllegalStateException"),
            segment("unknown", traceId="2" * 32, httpStatus=None, status="EXPORTED", exceptionCount=8),
            segment("active", traceId="3" * 32, httpStatus=None, status="ACTIVE"),
            segment("event", traceId="4" * 32, httpStatus=None, events=[{"type": "EXCEPTION"}], hasExceptions=True)]
    result = build(rows)
    assert result["groups"] == [] and result["requests"] == []
    assert result["counts"]["loadedRequests"] == 4
    assert result["partial"]  # active evidence can still change


def test_boundary_exception_class_optional_filterable_and_not_a_free_message():
    rows = [segment(filterException="java.net.SocketTimeoutException"),
            segment("second", traceId="2" * 32, filterException="SECRET message including user token")]
    result = build(rows)
    assert "SECRET" not in json.dumps(result)
    typed = build(rows, {"exception_class": "sockettimeout"})
    assert typed["counts"]["matchingRequests"] == 1
    assert typed["groups"][0]["exceptionClass"] == "java.net.SocketTimeoutException"
    assert not build([segment(httpStatus=200, filterException="java.net.SocketTimeoutException")],
                     {"exception_class": "SocketTimeout"})["groups"]


def test_private_payloads_events_messages_and_context_never_copied():
    secret = "DO_NOT_COPY_PRIVATE_CONTEXT"
    row = segment(user="allowed-user", headers={"Authorization": secret}, contextParams={"secret": secret},
                  payload=secret, body=secret, message=secret, events=[{"message": secret}],
                  attributes={"secret": secret, "exceptionMessage": secret, "userContext": secret})
    result = build([row])
    assert secret not in json.dumps(result)
    node = result["requests"][0]["segments"][0]
    assert node["user"] == "allowed-user"
    assert all(value is None or isinstance(value, (str, int, float, bool)) for value in node.values())
    assert not {"attributes", "headers", "contextParams", "payload", "body", "message", "events"}.intersection(node)


def test_group_identity_excludes_user_instance_trace_segment_ids_and_times():
    first = build([segment()])["groups"][0]["id"]
    changed = segment("new-id", traceId="2" * 32, serviceInstanceId="restarted", user="bob",
                      startedAtMillis=NOW - 2000, lastActivityMillis=NOW - 100, siteId="other")
    assert build([changed])["groups"][0]["id"] == first
    assert re.fullmatch(r"eg_[0-9a-f]{24}", first)


def test_raw_paths_not_decoded_or_guessed_and_captured_templates_are_respected():
    rows = [segment("first", path="/features/dGVzdDE=", routeTemplate=""),
            segment("second", traceId="2" * 32, path="/features/test1", routeTemplate="")]
    result = build(rows)
    assert {g["route"] for g in result["groups"]} == {"/features/dGVzdDE=", "/features/test1"}
    assert all(g["routeKind"] == "literal" for g in result["groups"])
    for row in rows:
        row["routeTemplate"] = "/features/{id}"
    result = build(rows)
    assert len(result["groups"]) == 1 and result["groups"][0]["routeKind"] == "template"
    assert result["groups"][0]["requestCount"] == 2
    assert len(build([segment(routeTemplate="", path="", lensId="job-one", entryChannel="SCHEDULER"),
                      segment("other", traceId="2" * 32, routeTemplate="", path="", lensId="job-two", entryChannel="SCHEDULER")])["groups"]) == 2


def test_clipped_routes_do_not_merge_different_long_raw_routes():
    rows = [segment("one", path="/" + "a" * 2100 + "one", routeTemplate=""),
            segment("two", traceId="2" * 32, path="/" + "a" * 2100 + "two", routeTemplate="")]
    result = build(rows)
    assert len(result["groups"]) == 2
    assert all(len(g["route"]) <= 2048 for g in result["groups"])


def test_first_last_use_distinct_request_starts_not_child_activity_or_durations():
    rows = [segment(httpStatus=200, startedAtMillis=NOW - 5000),
            segment("child", "ems", parentSpanId="p", startedAtMillis=NOW - 900000, lastActivityMillis=NOW),
            segment("new-root", traceId="2" * 32, httpStatus=200, startedAtMillis=NOW - 1000),
            segment("new-child", "ems", traceId="2" * 32, parentSpanId="p", startedAtMillis=NOW - 10000)]
    group = build(rows)["groups"][0]
    assert group["firstSeenMillis"] == NOW - 5000
    assert group["lastSeenMillis"] == NOW - 1000
    assert group["requestCount"] == 2


def test_time_window_defaults_null_all_query_and_duration_filters():
    rows = [segment(), segment("old", traceId="2" * 32, startedAtMillis=NOW - 60 * 60000)]
    assert build(rows)["counts"]["matchingRequests"] == 1
    assert build(rows, {"since_minutes": None})["counts"]["matchingRequests"] == 2
    assert build(rows, {"since_minutes": ""})["counts"]["matchingRequests"] == 2
    assert not build(rows, {"since_minutes": 0})["groups"]
    assert build(rows, {"min_duration": "30", "max_duration": 30, "query": "alice"})["groups"]
    assert not build(rows, {"min_duration": 31})["groups"]
    assert not build(rows, {"query": "not observed"})["groups"]


def test_determinism_input_order_sorting_and_no_mutation():
    rows = [segment("one"), segment("two", traceId="2" * 32),
            segment("other", "ems", traceId="3" * 32, startedAtMillis=NOW - 1)]
    original = copy.deepcopy(rows)
    result = build(rows)
    assert rows == original
    assert result == build(list(reversed(rows)))
    assert result["groups"][0]["requestCount"] == 2  # recurrence before recency
    assert result["requests"][0]["traceId"] == "3" * 32


def test_malformed_and_missing_trace_ids_do_not_invent_one_shared_request():
    rows = [segment(segment_id="", traceId=None), segment(segment_id="", traceId=None),
            segment("zero", traceId="0" * 32), segment("bad", traceId="not-a-trace")]
    result = build(rows)
    assert result["counts"]["loadedRequests"] == 4
    assert result["counts"]["matchingRequests"] == 4
    assert result["groups"][0]["segmentCount"] == 4
    assert len({r["key"] for r in result["requests"]}) == 4
    assert all(r["traceId"] is None for r in result["requests"])


def test_unknown_times_stay_unknown_and_legacy_metadata_fallback_works():
    row = {"segmentId": "legacy", "traceId": "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB", "eventCount": 1,
           "attributes": {"applicationId": "legacy", "httpMethod": "post", "path": "/literal", "route": "/template",
                          "httpStatus": "502", "filterException": "legacy.SomeException", "siteId": 42}}
    result = build([row], {"since_minutes": None, "site": "42"})
    group, request = result["groups"][0], result["requests"][0]
    assert group["firstSeenMillis"] is None and group["lastSeenMillis"] is None
    assert request["traceId"] == "abcdefab-cdef-abcd-efab-cdefabcdefab"
    assert group["route"] == "/template" and group["method"] == "POST"
    assert request["durationMs"] is None
    assert not build([row])["groups"]


@pytest.mark.parametrize("doc", [None, [], {"traces": {}}, {"traces": [None, 1, []], "errors": {}}])
def test_malformed_documents_are_safe_partial_and_do_not_create_groups(doc):
    result = build_error_groups(doc, now_ms=NOW)
    assert result["partial"] and result["groups"] == [] and result["requests"] == []
    json.dumps(result, allow_nan=False)


def test_malformed_scalar_metadata_is_safe_and_finite():
    row = segment(serviceInstanceId=[], status={}, httpStatus=float("nan"), httpMethod=[],
                  path={}, routeTemplate=[], parentSpanId={}, durationMs=float("inf"), startedAtMillis=None,
                  user={"secret": "no"}, siteId=[], attributes=[], eventCount=1e308, hasErrors=True)
    row["service"] = {}
    result = build([row], {"since_minutes": None})
    assert result["groups"][0]["category"] == "execution"
    assert result["requests"][0]["rootMissing"]
    assert result["requests"][0]["httpStatus"] is None
    assert "secret" not in json.dumps(result, allow_nan=False)


def test_limits_offline_errors_are_separate_and_counts_are_retained_only():
    rows = [segment(f"segment-{i}", traceId=f"{i + 1:032x}") for i in range(MAX_SEGMENTS + 1)]
    result = build(rows, errors=[{"service": "offline", "error": "timeout", "payload": "SECRET"}] * (MAX_ERRORS + 1))
    assert result["partial"] and result["limited"]
    assert result["counts"]["loadedSegments"] == MAX_SEGMENTS
    assert result["counts"]["matchingRequests"] == MAX_SEGMENTS
    assert result["groups"][0]["requestCount"] == MAX_SEGMENTS
    assert len(result["errors"]) == MAX_ERRORS
    assert "SECRET" not in json.dumps(result)
    assert any("limit" in warning.lower() for warning in result["warnings"])
    offline = build([], partial=True, errors=[{"service": "offline", "error": "timeout"}])
    assert offline["groups"] == [] and offline["counts"]["matchingRequests"] == 0
    assert build([], limited=True)["limited"]


def test_group_detail_helpers_select_without_changing_list_model():
    model = build([segment(), segment("other", "ems", traceId="2" * 32, httpStatus=503)])
    original = copy.deepcopy(model)
    group_id = model["groups"][0]["id"]
    assert validate_group_id(group_id) == group_id
    detail = select_error_group(model, group_id)
    assert detail["kind"] == "error-group" and detail["scope"] == "retained-only"
    assert detail["group"]["id"] == group_id
    assert detail["counts"]["matchingRequests"] == 1
    assert detail["counts"]["matchingSegments"] == 1
    assert detail["counts"]["groups"] == 1
    assert len(detail["requests"]) == 1
    absent = select_error_group(model, "eg_" + "0" * 24)
    assert absent["group"] is None and absent["requests"] == []
    assert any("not observed" in warning for warning in absent["warnings"])
    assert model == original


def test_filter_defaults_aliases_and_numeric_query_strings():
    assert validate_filters()["since_minutes"] == 30
    assert validate_filters({"since_minutes": "", "category": "all", "http_status": ""})["since_minutes"] is None
    assert validate_filters({"category": "all"})["category"] is None
    assert validate_filters({"http_status": "503.0", "since_minutes": "1e1"})["http_status"] == 503
    assert validate_filters({"method": "post", "channel": "http"})["method"] == "POST"


@pytest.mark.parametrize("filters", [[], "bad", {"unknown": 1}, {"site": 42}, {"service": {}},
    {"http_status": True}, {"http_status": 500.5}, {"http_status": 99}, {"http_status": 600},
    {"since_minutes": -1}, {"since_minutes": 525601}, {"since_minutes": "NaN"}, {"since_minutes": float("inf")},
    {"min_duration": True}, {"min_duration": -1}, {"max_duration": []}, {"min_duration": 20, "max_duration": 10},
    {"category": "exception"}, {"query": "x" * 257}, {"path": "x\nclick"}])
def test_invalid_filters_rejected_before_processing(filters):
    with pytest.raises(ValueError):
        validate_filters(filters)
    with pytest.raises(ValueError):
        build_error_groups({"traces": []}, filters, NOW)


@pytest.mark.parametrize("group_id", [None, [], "eg_bad", "EG_" + "0" * 24, "eg_" + "0" * 25, "eg_" + "0" * 24 + "\n"])
def test_group_id_validation(group_id):
    with pytest.raises(ValueError):
        validate_group_id(group_id)
