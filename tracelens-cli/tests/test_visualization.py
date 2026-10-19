import copy
import json
import re

import pytest

from tracelens_cli.services import join_trace
from tracelens_cli.visualization import (
    MAX_CALLS, MAX_EDGES, MAX_ERRORS, MAX_LABEL, MAX_SEGMENTS, MAX_WARNINGS,
    build_visualization, to_mermaid,
)


def segment(key="app", **overrides):
    return {"segmentKey": key, "segmentId": key + "-segment", "service": key,
            "serviceInstanceId": key + "-instance", "spanId": key + "-span",
            "parentSpanId": None, "entryChannel": "HTTP", "status": "COMPLETED",
            "httpMethod": "GET", "path": "/features", "durationMs": 12,
            "eventCount": 4, "httpCalls": [], **overrides}


def call(key="app:call:0", owner="app", **overrides):
    return {"callKey": key, "ownerSegmentKey": owner, "service": "app",
            "serviceInstanceId": "app-instance", "httpMethod": "GET", "path": "/features",
            "peer": "ems:8082", "status": "COMPLETED", "httpStatus": "200",
            "durationNanos": "9123456", "spanId": "outgoing-span", "propagationStatus": "INJECTED",
            "downstreamState": "observed", **overrides}


def journey():
    return {"traceId": "trace-1", "segments": [segment(), segment("ems", parentSpanId="outgoing-span")],
            "httpCalls": [call()], "links": [{"fromCallKey": "app:call:0", "fromSegmentKey": "app",
                                              "toSegmentKey": "ems"}],
            "partial": False, "warnings": [], "errors": [], "completeness": "observed-only"}


def by_kind(model, kind):
    return [node for node in model["nodes"] if node["kind"] == kind]


def warnings(model):
    return "\n".join(model["warnings"])


def test_join_trace_input_schema_and_local_durations():
    app = segment()
    app["httpCalls"] = [call()]
    ems = segment("ems", parentSpanId="outgoing-span", durationMs="10.25")
    doc = join_trace("trace-1", [ems, app], [])
    model = build_visualization(doc)
    assert model["schemaVersion"] == 1
    assert model["kind"] == "request-flow"
    assert model["traceId"] == "trace-1"
    assert model["partial"] is False
    assert model["completeness"] == "observed-only"
    assert len(model["nodes"]) == 3
    assert {e["kind"] for e in model["edges"]} == {"outbound", "observed-call"}
    nodes = {node["id"]: node for node in model["nodes"]}
    assert len(model["roots"]) == 1
    assert nodes[model["roots"][0]]["service"] == "app"
    http_call = by_kind(model, "http-call")[0]
    assert http_call["durationMs"] == 9.123456
    assert http_call["httpStatus"] == 200
    assert http_call["segmentId"] == "app-segment"
    assert http_call["downstreamState"] == "observed"
    assert "local milliseconds" in model["note"]
    assert model["note"].startswith("Observed evidence only;")
    assert "live" not in model["note"].lower()
    assert "absence does not prove a service was not called" in model["note"]


def test_model_is_deterministic_order_independent_and_does_not_mutate():
    doc = journey()
    original = copy.deepcopy(doc)
    model = build_visualization(doc)
    assert doc == original
    assert model == build_visualization(doc)
    doc["segments"].reverse()
    doc["links"].reverse()
    assert model == build_visualization(doc)
    ids = {n["segmentKey"]: n["id"] for n in by_kind(model, "segment")}
    doc["segments"][0]["durationMs"] = 50000
    doc["segments"][0]["status"] = "ERROR"
    assert ids == {n["segmentKey"]: n["id"] for n in by_kind(build_visualization(doc), "segment")}
    assert to_mermaid(model) == to_mermaid(copy.deepcopy(model))


def test_no_causality_from_parent_span_downstream_keys_timestamps_or_routes():
    doc = journey()
    doc["links"] = []
    doc["httpCalls"][0]["downstreamSegmentKeys"] = ["ems"]
    for row in doc["segments"]:
        row.update(startNanos=1, startedAtMillis=1, lastActivityMillis=1000)
    model = build_visualization(doc)
    assert [e["kind"] for e in model["edges"]] == ["outbound"]
    assert len(model["roots"]) == 2
    assert by_kind(model, "http-call")[0]["downstreamState"] == "not-observed"
    assert model["partial"]
    assert "parentSpanId alone is not an edge" in warnings(model)
    assert "reachability is unknown" in warnings(model)
    # There is no wall-clock subtraction, nanoTime merge or inferred return edge.
    doc["segments"][0].pop("durationMs")
    assert by_kind(build_visualization(doc), "segment")[0]["durationMs"] is None
    assert "sequenceDiagram" not in to_mermaid(model)


def test_segment_attributes_fallback_preserves_raw_routes():
    encoded_route = "L2FwaS9mZWF0dXJlcw=="
    doc = {"segments": [segment(httpMethod=None, path=None, attributes={
        "httpMethod": "POST", "path": encoded_route, "httpStatus": "503", "user": "secret"})]}
    node = build_visualization(doc)["nodes"][0]
    assert node["method"] == "POST"
    assert node["path"] == encoded_route
    assert node["httpStatus"] == 503
    assert encoded_route in node["label"]
    assert "/api/features" not in node["label"]


def test_payloads_events_context_and_users_never_copied():
    doc = journey()
    secret = "SHOULD_NOT_APPEAR"
    for row in doc["segments"] + doc["httpCalls"]:
        row.update(events=[{"secret": secret}], contextParams={"secret": secret},
                   user=secret, payload={"secret": secret}, attributes={"user": secret},
                   arbitrary=secret)
    doc["errors"] = [{"service": "offline", "error": "unavailable", "context": secret,
                      "payload": secret, "segmentId": "safe-id"}]
    model = build_visualization(doc)
    assert secret not in json.dumps(model)
    assert model["errors"] == [{"service": "offline", "error": "unavailable", "segmentId": "safe-id"}]
    for node in model["nodes"]:
        assert all(value is None or isinstance(value, (str, int, float, bool)) for value in node.values())
    assert not {"events", "contextParams", "attributes", "payload", "user"}.intersection(model["nodes"][0])


def test_hostile_labels_cannot_escape_mermaid_statements_or_create_html():
    hostile = '\"]\nclick bad "javascript:alert(1)"\n%%{init: {"securityLevel":"loose"}}%%\n<img src=x onerror=alert(1)>`'
    doc = journey()
    doc["segments"][0].update(service=hostile, path=hostile + "\x00\u202e")
    model = build_visualization(doc)
    assert all(len(n["label"]) <= MAX_LABEL for n in model["nodes"])
    assert not any(c in json.dumps(model, ensure_ascii=False) for c in ("\x00", "\u202e"))
    source = to_mermaid(model)
    assert source.startswith("flowchart LR\n")
    assert "<" not in source and ">" not in source.replace("-->", "")
    assert "%%{init" not in source
    assert "`" not in source
    assert not re.search(r"^\s*(click|style|classDef|linkStyle)\s", source, re.MULTILINE)
    assert len(source.splitlines()) == 2 + len(model["nodes"]) + len(model["edges"])
    for line in source.splitlines()[2:2 + len(model["nodes"])]:
        assert line.count('"') == 2
    assert "#34;" in source and "#8249;" in source
    # Even after a Mermaid renderer decodes entities, user text contains no tags.
    decoded = re.sub(r"#([0-9]+);", lambda m: chr(int(m[1])), source)
    assert "<img" not in decoded and "<script" not in decoded


def test_mermaid_rejects_foreign_ids_and_edge_statements():
    model = build_visualization(journey())
    model["nodes"].append({"id": 'x\nclick x "javascript:alert(1)"', "label": "bad"})
    model["edges"].extend([{"from": [], "to": "bad", "kind": "outbound"},
                           {"from": model["roots"][0], "to": "not-in-model", "kind": "outbound"}])
    result = to_mermaid(model)
    assert "javascript" not in result
    assert "not-in-model" not in result


@pytest.mark.parametrize("doc", [{}, {"segments": []}, None, [], {"segments": [None, "bad"], "links": {}}])
def test_empty_and_malformed_journeys_have_no_invented_evidence(doc):
    model = build_visualization(doc)
    assert model["nodes"] == []
    assert model["edges"] == []
    assert model["roots"] == []
    assert model["partial"] is True
    assert model["completeness"] == "not-observed"
    assert model["warnings"]
    assert to_mermaid(model).startswith("flowchart LR")


def test_single_segment_active_and_truncation_states():
    doc = {"traceId": "single", "segments": [segment()]}
    model = build_visualization(doc)
    assert len(model["nodes"]) == 1
    assert model["edges"] == []
    assert model["roots"] == [model["nodes"][0]["id"]]
    assert not model["partial"]
    assert model["completeness"] == "observed-only"
    doc["segments"][0].update(status="ACTIVE", truncated=True, httpCallsTruncated=True)
    model = build_visualization(doc)
    assert model["partial"]
    assert "Active segment" in warnings(model)
    assert "Truncated segment" in warnings(model)
    assert "HTTP call capture truncated" in warnings(model)
    assert "ACTIVE" in to_mermaid(model)
    assert "TRUNCATED" in to_mermaid(model)


def test_upstream_partial_errors_and_warnings_stay_visible():
    doc = journey()
    doc.update(partial=True, errors=[{"service": "unreachable", "error": "timeout"}], warnings=["Truncated upstream"])
    model = build_visualization(doc)
    assert model["partial"]
    assert model["completeness"] == "partial"
    assert "Truncated upstream" in model["warnings"]
    assert model["errors"] == doc["errors"]


def test_duplicate_segment_keys_do_not_pick_a_callee_or_owner():
    doc = journey()
    doc["segments"].append(segment("ems", serviceInstanceId="other-instance"))
    model = build_visualization(doc)
    assert len({n["id"] for n in model["nodes"]}) == 4
    assert [e["kind"] for e in model["edges"]] == ["outbound"]
    assert "Duplicate segmentKey" in warnings(model)
    original = copy.deepcopy(model)
    doc["segments"].reverse()
    assert build_visualization(doc) == original
    doc["segments"].append(segment("app"))
    model = build_visualization(doc)
    assert not model["edges"]
    assert by_kind(model, "http-call")[0]["ownerId"] is None


def test_duplicate_call_keys_and_identical_rows_are_unique_but_unresolved():
    doc = journey()
    doc["httpCalls"].append(copy.deepcopy(doc["httpCalls"][0]))
    model = build_visualization(doc)
    assert len({n["id"] for n in model["nodes"]}) == 4
    assert {e["kind"] for e in model["edges"]} == {"outbound"}
    assert "Duplicate callKey" in warnings(model)
    assert all(c["downstreamState"] == "not-observed" for c in by_kind(model, "http-call"))


@pytest.mark.parametrize("mutation", ["missing-call", "missing-target", "missing-owner", "conflicting-owner"])
def test_missing_targets_and_conflicting_ownership_do_not_get_guessed(mutation):
    doc = journey()
    if mutation == "missing-call":
        doc["httpCalls"].clear()
    elif mutation == "missing-target":
        doc["segments"].pop()
    elif mutation == "missing-owner":
        doc["httpCalls"][0]["ownerSegmentKey"] = "absent"
    else:
        doc["links"][0]["fromSegmentKey"] = "ems"
    model = build_visualization(doc)
    assert all(e["kind"] == "outbound" for e in model["edges"])
    assert model["partial"]
    assert "link omitted" in warnings(model)


def test_missing_keys_remain_visible_with_no_synthetic_link_targets():
    doc = journey()
    del doc["segments"][1]["segmentKey"]
    del doc["httpCalls"][0]["callKey"]
    model = build_visualization(doc)
    assert len(model["nodes"]) == 3
    assert [e["kind"] for e in model["edges"]] == ["outbound"]
    assert "Missing or malformed segmentKey" in warnings(model)
    assert "Missing or malformed callKey" in warnings(model)


def test_multiple_explicit_parent_calls_are_ambiguous_not_arbitrarily_chosen():
    doc = journey()
    doc["httpCalls"].append(call("second-call"))
    doc["links"].append({"fromCallKey": "second-call", "fromSegmentKey": "app", "toSegmentKey": "ems"})
    model = build_visualization(doc)
    assert {e["kind"] for e in model["edges"]} == {"outbound"}
    assert "Ambiguous parent calls" in warnings(model)


def test_cycles_are_finite_flagged_evidence_without_a_fabricated_root():
    doc = journey()
    doc["segments"] = [segment(parentSpanId="outgoing-span")]
    doc["links"][0]["toSegmentKey"] = "app"
    model = build_visualization(doc)
    assert len(model["nodes"]) == 2
    assert len(model["edges"]) == 2
    assert model["roots"] == []
    assert model["partial"]
    assert "Cyclic retained call links" in warnings(model)
    assert len(to_mermaid(model).splitlines()) == 6


def test_budgets_are_bounded_and_visibly_partial():
    doc = {"segments": [segment(f"s-{i:04}") for i in range(300)],
           "httpCalls": [call(f"call-{i:04}", f"s-{i:04}") for i in range(300)],
           "links": [], "errors": [{"service": f"offline-{i}", "error": "timeout"} for i in range(100)]}
    model = build_visualization(doc)
    assert len(by_kind(model, "segment")) == MAX_SEGMENTS
    assert len(by_kind(model, "http-call")) == MAX_CALLS
    assert len(model["nodes"]) <= MAX_SEGMENTS + MAX_CALLS
    assert len(model["edges"]) <= MAX_EDGES
    assert len(model["warnings"]) <= MAX_WARNINGS
    assert len(model["errors"]) == MAX_ERRORS
    assert model["partial"]
    assert "limit reached" in warnings(model)
    assert "Visualization segment limit reached" in warnings(model)
    assert "Visualization http-call limit reached" in warnings(model)
    assert model == build_visualization(doc)
    doc["segments"].reverse()
    doc["httpCalls"].reverse()
    assert model == build_visualization(doc)


@pytest.mark.parametrize("bad", [None, True, {}, [], -1, "NaN", float("inf"), "bad"])
def test_invalid_numbers_are_not_timings_or_http_status(bad):
    doc = journey()
    doc["segments"][0].update(durationMs=bad, eventCount=bad, httpStatus=bad, startedAtMillis=1, lastActivityMillis=100)
    doc["httpCalls"][0]["durationNanos"] = bad
    model = build_visualization(doc)
    node = by_kind(model, "segment")[0]
    assert node["durationMs"] is None
    assert node["eventCount"] is None
    assert node["httpStatus"] is None
    assert by_kind(model, "http-call")[0]["durationMs"] is None
    json.dumps(model, allow_nan=False)


def test_control_text_and_containers_do_not_escape_the_scalar_schema():
    doc = journey()
    for row in doc["segments"] + doc["httpCalls"]:
        row.update(service={}, serviceInstanceId=[], segmentId={"secret": "payload"}, httpMethod={},
                   path=[], entryChannel={}, attributes=[], httpStatus="999")
    model = build_visualization(doc)
    assert all(all(value is None or isinstance(value, (str, int, float, bool)) for value in n.values())
               for n in model["nodes"])
    assert all(n["httpStatus"] is None for n in model["nodes"])


def test_duplicate_call_identities_do_not_collapse_control_cleaned_owners():
    doc = {"segments": [segment("a\nb"), segment("a\tb")],
           "httpCalls": [call("same-key", "a\nb"), call("same-key", "a\tb")]}
    original = build_visualization(doc)
    doc["segments"].reverse()
    doc["httpCalls"].reverse()
    assert build_visualization(doc) == original
