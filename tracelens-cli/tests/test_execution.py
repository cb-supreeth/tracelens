"""Evidence and resource-limit regressions for the on-demand execution model."""

import copy
import json

import pytest

from tracelens_cli import execution
from tracelens_cli.execution import MODEL_MAX_BYTES, VIEWER_MODEL_MAX_BYTES, MAX_NODES, build_segment_execution


CLASS = "com.example.FeatureService"
OTHER_CLASS = "com.example.FeatureRepository"
NANOS = 9_000_000_000


def event(seq, kind, method=None, *, thread="http-nio-8082-exec-3",
          cls=CLASS, nanos=None, line=-1, **values):
    """Match the exported Java shape, including LINE without methodName."""
    result = {
        "seq": seq, "epochMillis": 1_700_000_000_000 + seq,
        "nanoTime": NANOS + seq * 1_000_000 if nanos is None else nanos,
        "thread": thread, "type": kind, "className": cls, "line": line,
        "attributes": {},
    }
    if method is not None:
        result["methodName"] = method
    result.update(values)
    return result


def trace(events, **values):
    return {
        "schemaVersion": 2, "traceId": "a" * 32, "segmentId": "ems-segment",
        "spanId": "b" * 16, "parentSpanId": "c" * 16,
        "applicationId": "cb-ems", "applicationVersion": "dev",
        "serviceInstanceId": "ems-instance", "entryChannel": "HTTP",
        "status": "COMPLETED", "startedAt": 1_700_000_000_000,
        "startNanos": NANOS, "endedAt": 1_700_000_000_020, "durationMs": 20,
        "eventCount": len(events), "truncated": False,
        "attributes": {"httpMethod": "GET", "path": "/features/test1", "httpStatus": "200"},
        "events": events, **values,
    }


def simple_events():
    return [event(1, "METHOD_ENTER", "retrieve"),
            event(2, "LINE", line=42),
            event(3, "METHOD_EXIT", "retrieve")]


def encoded(model):
    return json.dumps(model, ensure_ascii=True, separators=(",", ":")).encode("utf-8")


def warning_text(model):
    return " ".join(warning["code"] for warning in model["warnings"]).lower()


def invocations(model):
    return [node for node in model["nodes"] if node["kind"] == "invocation"]


def markers(model):
    return [node for node in model["nodes"] if node["kind"] == "marker"]


def assert_references_resolve(model):
    node_ids = {node["id"] for node in model["nodes"]}
    lane_ids = {lane["id"] for lane in model["lanes"]}
    assert len(node_ids) == len(model["nodes"])
    for node in model["nodes"]:
        assert node["laneId"] in lane_ids
        assert node["parentId"] is None or node["parentId"] in node_ids


def test_single_invocation_uses_observed_entry_exit_and_source_coordinates():
    model = build_segment_execution(trace(simple_events()))
    assert model["schemaVersion"] == 1 and model["kind"] == "segment-execution"
    assert model["completeness"] == "observed-only"
    assert model["partial"] is False
    node, = invocations(model)
    assert node["className"] == CLASS
    assert node["methodName"] == "retrieve"
    assert node["parentId"] is None and node["relationship"] is None
    assert node["entry"] == {"index": 0, "seq": 1}
    assert node["exit"] == {"index": 2, "seq": 3}
    assert node["complete"] is True and node["outcome"] == "returned"
    assert node["durationMs"] == 2
    assert node["observedLines"] == {
        "first": 42, "last": 42, "count": 1,
        "firstEvidence": {"index": 1, "seq": 2},
        "lastEvidence": {"index": 1, "seq": 2},
    }
    assert model["counts"]["eventsAvailable"] == 3
    assert model["counts"]["eventsProcessed"] == 3
    assert model["counts"]["eventsOmitted"] == 0
    assert_references_resolve(model)


def test_repeated_calls_and_recursion_remain_distinct_invocations():
    events = [event(1, "METHOD_ENTER", "root"),
              event(2, "METHOD_ENTER", "visit"),
              event(3, "METHOD_ENTER", "visit"),
              event(4, "METHOD_EXIT", "visit"),
              event(5, "METHOD_EXIT", "visit"),
              event(6, "METHOD_ENTER", "visit"),
              event(7, "METHOD_EXIT", "visit"),
              event(8, "METHOD_EXIT", "root")]
    model = build_segment_execution(trace(events))
    root, visit, recursive, sibling = invocations(model)
    assert len({node["id"] for node in (visit, recursive, sibling)}) == 3
    assert visit["parentId"] == sibling["parentId"] == root["id"]
    assert recursive["parentId"] == visit["id"]
    assert all(node["relationship"] == "observed-nesting" for node in (visit, recursive, sibling))
    assert [node["durationMs"] for node in (root, visit, recursive, sibling)] == [7, 3, 1, 1]
    assert all(node["complete"] for node in invocations(model))


def test_interleaved_thread_names_have_separate_roots_and_no_async_causal_edge():
    events = [event(1, "METHOD_ENTER", "request", thread="http-1"),
              event(2, "METHOD_ENTER", "task", thread="pool-1"),
              event(3, "METHOD_ENTER", "lookup", thread="http-1"),
              event(4, "METHOD_EXIT", "task", thread="pool-1"),
              event(5, "METHOD_EXIT", "lookup", thread="http-1"),
              event(6, "METHOD_EXIT", "request", thread="http-1")]
    model = build_segment_execution(trace(events))
    request, task, lookup = invocations(model)
    assert request["parentId"] is None and task["parentId"] is None
    assert request["laneId"] == lookup["laneId"] != task["laneId"]
    assert lookup["parentId"] == request["id"]
    assert {lane["threadName"] for lane in model["lanes"]} == {"http-1", "pool-1"}
    assert all(lane["identity"] == "thread-name" for lane in model["lanes"])
    assert not model.get("edges")


def test_exception_is_terminal_evidence_instead_of_an_additional_exit():
    events = [event(1, "METHOD_ENTER", "root"),
              event(2, "METHOD_ENTER", "fail", cls=OTHER_CLASS),
              event(3, "EXCEPTION", "fail", cls=OTHER_CLASS,
                    attributes={"exceptionType": "java.lang.IllegalStateException"},
                    message="SECRET exception message"),
              event(4, "EXCEPTION", "root",
                    attributes={"exceptionType": "java.lang.IllegalStateException"})]
    model = build_segment_execution(trace(events))
    root, failing = invocations(model)
    assert len(invocations(model)) == 2 and not markers(model)
    assert root["exit"] == {"index": 3, "seq": 4}
    assert failing["exit"] == {"index": 2, "seq": 3}
    assert all(node["complete"] and node["outcome"] == "threw" for node in (root, failing))
    assert all(node["exceptionType"] == "java.lang.IllegalStateException" for node in (root, failing))
    assert "SECRET" not in encoded(model).decode()


@pytest.mark.parametrize("status,truncated", [("ACTIVE", False), ("COMPLETED", False), ("COMPLETED", True)])
def test_missing_exit_is_explicit_without_fabricated_duration(status, truncated):
    model = build_segment_execution(trace([event(1, "METHOD_ENTER", "unfinished")],
                                          status=status, truncated=truncated))
    node, = invocations(model)
    assert node["outcome"] == "incomplete" and node["complete"] is False
    assert node["exit"] is None and node["durationMs"] is None
    assert model["partial"] and model["warnings"]


def test_exit_skipping_unclosed_callee_does_not_claim_complete_outer_timing():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "outer"),
        event(2, "METHOD_ENTER", "thrownButUnrecorded", cls=OTHER_CLASS),
        event(3, "METHOD_EXIT", "outer"),
    ]))
    outer, inner = invocations(model)
    assert not outer["complete"] and not inner["complete"]
    assert outer["durationMs"] is None and inner["durationMs"] is None
    assert inner["exit"] is None and inner["outcome"] == "incomplete"
    assert model["partial"] and model["warnings"]


@pytest.mark.parametrize("kind", ["METHOD_EXIT", "EXCEPTION"])
def test_orphan_exit_is_a_marker_not_a_fabricated_invocation(kind):
    model = build_segment_execution(trace([
        event(1, kind, "orphan", attributes={"exceptionType": "java.lang.IllegalStateException"}),
    ]))
    assert invocations(model) == []
    marker, = markers(model)
    assert marker["eventType"] == kind and marker["parentId"] is None
    assert marker["evidence"] == {"index": 0, "seq": 1}
    assert model["partial"]


def test_line_evidence_is_not_attributed_to_a_non_top_class_frame():
    model = build_segment_execution(trace([
        event(1, "LINE", line=5),
        event(2, "METHOD_ENTER", "outer"),
        event(3, "LINE", line=80),
        event(4, "METHOD_ENTER", "inner", cls=OTHER_CLASS),
        event(5, "LINE", line=99),  # class belongs to outer, not current frame
        event(6, "LINE", cls=OTHER_CLASS, line=12),
        event(7, "METHOD_EXIT", "inner", cls=OTHER_CLASS),
        event(8, "LINE", line=20),
        event(9, "METHOD_EXIT", "outer"),
    ]))
    outer, inner = invocations(model)
    assert outer["observedLines"]["first"] == 80
    assert outer["observedLines"]["last"] == 20  # observed order, not a source min/max range
    assert outer["observedLines"]["count"] == 2
    assert inner["observedLines"]["count"] == 1
    assert inner["observedLines"]["first"] == 12
    assert model["counts"]["unattributedLineEvents"] == 2


def test_line_only_capture_does_not_guess_method_names_or_calls():
    model = build_segment_execution(trace([event(1, "LINE", line=22), event(2, "LINE", line=23)]))
    assert not invocations(model)
    assert model["counts"]["unattributedLineEvents"] == 2
    assert model["partial"] and model["warnings"]


@pytest.mark.parametrize("thread", [None, ""])
def test_unknown_thread_events_cannot_establish_nesting_or_match_an_exit(thread):
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "outer", thread=thread),
        event(2, "METHOD_ENTER", "inner", thread=thread),
        event(3, "METHOD_EXIT", "inner", thread=thread),
        event(4, "METHOD_EXIT", "outer", thread=thread),
    ]))
    assert all(node["parentId"] is None for node in model["nodes"])
    assert all(not node["complete"] for node in invocations(model))
    assert all(lane["identity"] == "unknown" for lane in model["lanes"])
    assert model["partial"]


def test_unknown_thread_method_breaks_known_lane_instead_of_leaving_a_false_match():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "outer"),
        event(2, "METHOD_ENTER", "unknownTask", thread=None),
        event(3, "METHOD_EXIT", "outer"),
        event(4, "METHOD_ENTER", "next"),
        event(5, "METHOD_EXIT", "next"),
    ]))
    outer, unknown, later = invocations(model)
    assert not outer["complete"] and outer["exit"] is None
    assert not unknown["complete"] and unknown["parentId"] is None
    assert later["complete"] and later["parentId"] is None
    assert len(markers(model)) == 1
    assert "interrupted_by_missing_evidence" in warning_text(model)


def test_signed_negative_monotonic_nanos_are_valid_elapsed_time():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "run", nanos=-5_000_000),
        event(2, "METHOD_EXIT", "run", nanos=-2_000_000),
    ], startNanos=-6_000_000))
    node, = invocations(model)
    assert node["complete"] and node["durationMs"] == 3


@pytest.mark.parametrize("bad_clock", [None, True, "9000000001", float("nan"), float("inf")])
def test_missing_or_invalid_clock_does_not_invent_duration(bad_clock):
    events = simple_events()
    events[0]["nanoTime"] = bad_clock
    model = build_segment_execution(trace(events))
    node, = invocations(model)
    assert node["durationMs"] is None
    assert model["warnings"]
    json.dumps(model, allow_nan=False)


def test_backwards_clock_suppresses_duration_even_when_endpoint_difference_is_positive():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "run", nanos=100),
        event(2, "LINE", line=5, nanos=90),
        event(3, "METHOD_EXIT", "run", nanos=110),
    ]))
    assert invocations(model)[0]["durationMs"] is None
    assert model["partial"]


def test_cross_thread_clock_inversion_is_not_a_reordering_or_negative_duration():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "a", thread="a", nanos=100),
        event(2, "METHOD_ENTER", "b", thread="b", nanos=90),
        event(3, "METHOD_EXIT", "a", thread="a", nanos=200),
        event(4, "METHOD_EXIT", "b", thread="b", nanos=150),
    ]))
    a, b = invocations(model)
    assert (a["methodName"], b["methodName"]) == ("a", "b")
    assert a["durationMs"] == 0.0001 and b["durationMs"] == 0.00006
    assert a["parentId"] is None and b["parentId"] is None


@pytest.mark.parametrize("bad_seq", [1, 3, None, True, "2"])
def test_sequence_anomaly_breaks_nesting_without_silently_reordering(bad_seq):
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "outer"),
        {**event(2, "METHOD_ENTER", "inner"), "seq": bad_seq},
        event(4, "METHOD_EXIT", "inner"),
        event(5, "METHOD_EXIT", "outer"),
    ]))
    outer, inner = invocations(model)
    assert inner["parentId"] is None
    assert not outer["complete"] and outer["durationMs"] is None
    assert model["partial"] and model["warnings"]


def test_external_and_database_markers_have_no_fabricated_return_or_payload():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "load"),
        event(2, "EXTERNAL_CALL", attributes={"system": "http", "route": "/features", "payload": "SECRET"}),
        event(3, "DB_ROUTE", attributes={"system": "mysql", "route": "read", "sql": "SECRET"}),
        event(4, "METHOD_EXIT", "load"),
    ]))
    root, = invocations(model)
    assert len(markers(model)) == 2
    assert all(node["parentId"] == root["id"] for node in markers(model))
    assert {node["eventType"] for node in markers(model)} == {"EXTERNAL_CALL", "DB_ROUTE"}
    assert "SECRET" not in encoded(model).decode()
    assert not model.get("edges")


@pytest.mark.parametrize("direction,status", [
    ("publish", "SUBMITTED"), ("consume", "OBSERVED"), ("consume", "ERROR"),
])
def test_messaging_marker_retains_allowlisted_direction_status_and_target(direction, status):
    doc = trace([
        event(1, "METHOD_ENTER", "handle"),
        event(2, "EXTERNAL_CALL", message="SECRET message", attributes={
            "boundaryKind": "messaging", "system": "sqs", "direction": direction,
            "destination": "https://SECRET:SECRET@sqs.example/queues/events?token=SECRET#SECRET",
            "operation": "sendMessage" if direction == "publish" else "processMessage",
            "status": status, "evidenceKind": "client-boundary",
            "errorType": "java.lang.IllegalStateException" if status == "ERROR" else None,
            "headers": {"Authorization": "SECRET"}, "payload": "SECRET", "user": "SECRET",
        }),
        event(3, "METHOD_EXIT", "handle"),
    ])
    original = copy.deepcopy(doc)
    model = build_segment_execution(doc)
    root, = invocations(model)
    marker, = markers(model)
    assert marker["system"] == "sqs" and marker["route"] is None
    assert marker["parentId"] == root["id"] and marker["relationship"] == "observed-nesting"
    assert marker["evidence"] == {"index": 1, "seq": 2}
    assert marker["boundary"] == {
        "boundaryKind": "messaging", "system": "sqs", "direction": direction,
        "destination": "https://sqs.example/queues/events",
        "operation": "sendMessage" if direction == "publish" else "processMessage",
        "status": status, "evidenceKind": "client-boundary",
        "errorType": "java.lang.IllegalStateException" if status == "ERROR" else None,
        "downstreamState": "not-observed",
    }
    assert "durationMs" not in marker and "exit" not in marker
    assert root["complete"] and root["outcome"] == "returned" and root["durationMs"] == 2
    assert b"SECRET" not in encoded(model)
    assert doc == original


def test_legacy_wrapper_and_database_marker_fields_remain_backward_compatible():
    model = build_segment_execution(trace([
        event(1, "EXTERNAL_CALL", attributes={"system": "Repository", "detail": "execute",
                                              "payload": "SECRET"}),
        event(2, "EXTERNAL_CALL", attributes={"system": "LegacyClient", "detail": "SECRET free text"}),
        event(3, "DB_ROUTE", attributes={"route": "LIVE_MASTER", "sql": "SECRET"}),
        event(4, "METHOD_EXIT", "orphan"),
    ]))
    wrapper, free_text_wrapper, database, orphan = markers(model)
    assert wrapper["system"] == "Repository" and wrapper["route"] is None
    assert wrapper["boundary"]["operation"] == "execute"
    assert wrapper["boundary"]["evidenceKind"] == "wrapper-entry"
    assert wrapper["boundary"]["status"] == "OBSERVED"
    assert wrapper["boundary"]["destination"] is None
    assert free_text_wrapper["system"] == "LegacyClient"
    assert free_text_wrapper["boundary"]["operation"] is None
    assert database["route"] == "LIVE_MASTER" and database["system"] is None
    assert database["boundary"]["boundaryKind"] == "database-route"
    assert database["boundary"]["destination"] == "LIVE_MASTER"
    assert database["boundary"]["evidenceKind"] == "routing-marker"
    assert "boundary" not in orphan
    assert all("durationMs" not in marker for marker in markers(model))
    assert b"SECRET" not in encoded(model)


def test_boundary_metadata_obeys_existing_display_and_model_byte_caps():
    events = [event(index + 1, "EXTERNAL_CALL", attributes={
        "boundaryKind": "messaging", "system": "kafka", "direction": "publish",
        "destination": "topic-" + "x" * 400, "operation": "send",
        "status": "SUBMITTED", "evidenceKind": "client-boundary",
    }) for index in range(MAX_NODES)]
    model = build_segment_execution(trace(events), max_nodes=MAX_NODES)
    assert 0 < len(markers(model)) < MAX_NODES
    assert all(len(marker["boundary"]["destination"]) <= execution.MAX_TEXT for marker in markers(model))
    assert len(encoded(model)) <= MODEL_MAX_BYTES
    assert "model_byte_limit" in warning_text(model)
    assert "display_fields_shortened_or_sanitized" in warning_text(model)
    assert model["counts"]["nodesOmitted"] == MAX_NODES - len(markers(model))
    assert_references_resolve(model)


def test_deterministic_without_mutating_input_and_private_fields_are_not_forwarded():
    events = simple_events()
    events[1].update(sourceLine="SECRET source text", message="SECRET message", payload="SECRET body",
                     attributes={"token": "SECRET event attribute"})
    doc = trace(events, user="SECRET user", lensId="SECRET label", siteId="SECRET site",
                contextParams={"password": "SECRET context"}, httpCalls=[{"url": "SECRET URL"}],
                request={"headers": {"Authorization": "SECRET token"}}, source="SECRET source")
    doc["attributes"]["Authorization"] = "SECRET header"
    original = copy.deepcopy(doc)
    model = build_segment_execution(doc)
    assert doc == original
    assert model == build_segment_execution(copy.deepcopy(doc))
    assert b"SECRET" not in encoded(model)
    assert "events" not in model and "contextParams" not in model
    assert_references_resolve(model)


def test_node_cap_scans_remaining_events_for_retained_invocation_exits():
    events = [event(1, "METHOD_ENTER", "outer"), event(2, "METHOD_ENTER", "inner"),
              event(3, "METHOD_EXIT", "inner"), event(4, "METHOD_EXIT", "outer")]
    model = build_segment_execution(trace(events), max_nodes=1)
    outer, = invocations(model)
    assert outer["methodName"] == "outer"
    assert outer["complete"] and outer["durationMs"] == 3
    assert model["counts"]["eventsProcessed"] == 4
    assert model["counts"]["nodesAvailable"] == 2
    assert model["counts"]["nodesReturned"] == 1
    assert model["counts"]["nodesOmitted"] == 1
    assert model["partial"] and model["warnings"]
    assert_references_resolve(model)


@pytest.mark.parametrize("max_model_bytes", [MODEL_MAX_BYTES, VIEWER_MODEL_MAX_BYTES])
def test_hard_byte_cap_handles_long_unicode_strings_and_omits_suffix_explicitly(max_model_bytes):
    events = []
    for index in range(MAX_NODES):
        events.extend([
            event(index * 2 + 1, "METHOD_ENTER", "\u2603" * 300 + str(index),
                  thread="thread", cls="com." + "\u2603" * 300),
            event(index * 2 + 2, "METHOD_EXIT", "\u2603" * 300 + str(index),
                  thread="thread", cls="com." + "\u2603" * 300),
        ])
    model = build_segment_execution(trace(events), max_nodes=MAX_NODES, max_model_bytes=max_model_bytes)
    assert len(encoded(model)) <= max_model_bytes
    assert model["limits"]["maxModelBytes"] == max_model_bytes
    assert model["counts"]["nodesReturned"] == len(model["nodes"]) < MAX_NODES
    assert model["counts"]["nodesOmitted"] > 0
    assert model["counts"]["eventsProcessed"] == len(events)
    assert "model_byte_limit" in warning_text(model)
    assert model["partial"]
    assert model == build_segment_execution(trace(events), max_nodes=MAX_NODES, max_model_bytes=max_model_bytes)
    assert_references_resolve(model)


@pytest.mark.parametrize("events", [[], [None, "garbage", 42]])
def test_empty_and_malformed_event_records_do_not_crash_or_invent_execution(events):
    model = build_segment_execution(trace(events))
    assert not invocations(model)
    assert model["partial"] and model["warnings"]
    assert_references_resolve(model)


def test_entry_with_missing_method_identity_is_explicitly_unmatchable():
    model = build_segment_execution(trace([{"type": "METHOD_ENTER"}]))
    node, = invocations(model)
    assert node["className"] is None and node["methodName"] is None
    assert node["complete"] is False and node["durationMs"] is None
    assert node["parentId"] is None and node["exit"] is None
    assert "invalid_method_identity" in warning_text(model)
    assert model["partial"]


@pytest.mark.parametrize("max_nodes", [0, -1, 501, True, False, 1.5, "2", None])
def test_invalid_node_limit_is_rejected_instead_of_silently_coerced(max_nodes):
    with pytest.raises(ValueError, match="max_nodes"):
        build_segment_execution(trace(simple_events()), max_nodes=max_nodes)


@pytest.mark.parametrize("max_model_bytes", [
    None, False, True, 0, -1, MODEL_MAX_BYTES - 1,
    VIEWER_MODEL_MAX_BYTES + 1, float(MODEL_MAX_BYTES), str(MODEL_MAX_BYTES),
])
def test_invalid_model_byte_budget_is_rejected(max_model_bytes):
    with pytest.raises(ValueError, match="max_model_bytes"):
        build_segment_execution(trace(simple_events()), max_model_bytes=max_model_bytes)


@pytest.mark.parametrize("export", [None, [], "{}", 0])
def test_non_object_exports_are_rejected(export):
    with pytest.raises(ValueError, match="object"):
        build_segment_execution(export)


@pytest.mark.parametrize("events", [None, {}, "[]"])
def test_missing_or_invalid_event_collection_returns_an_explicit_empty_partial_model(events):
    doc = trace([])
    doc["events"] = events
    model = build_segment_execution(doc)
    assert not model["nodes"]
    assert model["partial"]
    assert "missing_or_invalid_events" in warning_text(model)


def test_processing_event_cap_marks_unfinished_frames_and_unprocessed_events(monkeypatch):
    monkeypatch.setattr(execution, "MAX_EVENTS", 2)
    model = build_segment_execution(trace(simple_events()))
    node, = invocations(model)
    assert node["complete"] is False and node["exit"] is None
    assert model["counts"]["eventsAvailable"] == 3
    assert model["counts"]["eventsProcessed"] == 2
    assert model["counts"]["eventsOmitted"] == 1
    assert model["partial"]
    assert "event_processing_limit" in warning_text(model)


def test_processing_lane_cap_stops_without_reusing_a_different_thread_lane(monkeypatch):
    monkeypatch.setattr(execution, "MAX_LANES", 2)
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "a", thread="a"),
        event(2, "METHOD_ENTER", "b", thread="b"),
        event(3, "METHOD_ENTER", "c", thread="c"),
        event(4, "METHOD_EXIT", "a", thread="a"),
    ]))
    assert len(model["lanes"]) == len(invocations(model)) == 2
    assert all(node["parentId"] is None and not node["complete"] for node in invocations(model))
    assert model["counts"]["eventsProcessed"] == 2
    assert model["counts"]["eventsOmitted"] == 2
    assert "lane_processing_limit" in warning_text(model)
    assert_references_resolve(model)


def test_processing_depth_cap_does_not_reattach_deeper_calls_to_visible_ancestors(monkeypatch):
    monkeypatch.setattr(execution, "MAX_DEPTH", 2)
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "outer"),
        event(2, "METHOD_ENTER", "middle"),
        event(3, "METHOD_ENTER", "deepest"),
        event(4, "METHOD_EXIT", "deepest"),
        event(5, "METHOD_EXIT", "middle"),
        event(6, "METHOD_EXIT", "outer"),
    ]))
    assert len(invocations(model)) == 2
    assert all(not node["complete"] for node in invocations(model))
    assert model["counts"]["eventsProcessed"] == 2
    assert model["counts"]["eventsOmitted"] == 4
    assert "depth_processing_limit" in warning_text(model)
    assert_references_resolve(model)


def test_same_display_prefix_does_not_merge_distinct_raw_method_identities():
    prefix = "method" * 60
    first, second = prefix + "one", prefix + "two"
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", first), event(2, "METHOD_ENTER", second),
        event(3, "METHOD_EXIT", second), event(4, "METHOD_EXIT", first),
    ]))
    outer, inner = invocations(model)
    assert outer["id"] != inner["id"]
    assert outer["exit"] == {"index": 3, "seq": 4}
    assert inner["exit"] == {"index": 2, "seq": 3}
    assert outer["durationMs"] == 3 and inner["durationMs"] == 1
    assert model["partial"]  # shortened labels are disclosed


def test_generic_marker_and_unknown_event_fields_are_not_replayed():
    model = build_segment_execution(trace([
        event(1, "MARKER", message="SECRET generic marker"),
        event(2, "FUTURE_EVENT", message="SECRET unknown event", attributes={"token": "SECRET"}),
    ]))
    assert not model["nodes"]
    assert model["counts"]["genericMarkersOmitted"] == 1
    assert model["counts"]["otherEventsOmitted"] == 1
    assert "unknown_event_type" in warning_text(model)
    assert b"SECRET" not in encoded(model)


def test_legacy_marker_fields_do_not_bypass_boundary_sanitization():
    model = build_segment_execution(trace([
        event(1, "DB_ROUTE", attributes={"route": "https://user:SECRET@db.example/pool?token=SECRET"}),
        event(2, "EXTERNAL_CALL", attributes={"system": "client SECRET", "detail": "send"}),
    ]))
    route, wrapper = markers(model)
    assert route["route"] == route["boundary"]["destination"] == "https://db.example/pool"
    assert wrapper["system"] is None and wrapper["boundary"]["system"] is None
    assert b"SECRET" not in encoded(model)


def test_live_export_count_mismatch_is_disclosed_without_inventing_dropped_events():
    model = build_segment_execution(trace(simple_events(), eventCount=1, status="ACTIVE"))
    assert model["counts"]["eventsAvailable"] == model["counts"]["eventsProcessed"] == 3
    assert model["counts"]["eventsOmitted"] == 0
    assert invocations(model)[0]["complete"]
    assert "event_count_snapshot_mismatch" in warning_text(model)
    assert "capture_truncated" not in warning_text(model)


def test_unmatched_exit_clears_stale_parent_for_subsequent_calls():
    model = build_segment_execution(trace([
        event(1, "METHOD_ENTER", "stale"), event(2, "METHOD_EXIT", "unobserved"),
        event(3, "METHOD_ENTER", "later"), event(4, "METHOD_EXIT", "later"),
    ]))
    stale, later = invocations(model)
    assert not stale["complete"]
    assert later["parentId"] is None and later["complete"]
    assert len(markers(model)) == 1


def test_default_node_limit_is_200_and_omissions_still_count_the_observed_prefix():
    events = []
    for index in range(220):
        events.extend([event(index * 2 + 1, "METHOD_ENTER", "repeated"),
                       event(index * 2 + 2, "METHOD_EXIT", "repeated")])
    model = build_segment_execution(trace(events))
    assert model["limits"]["maxNodes"] == 200
    assert len(model["nodes"]) <= 200  # the independent byte cap may be reached sooner
    assert model["counts"]["invocationsObserved"] == 220
    assert model["counts"]["nodesOmitted"] == 220 - len(model["nodes"])
    assert model["counts"]["eventsProcessed"] == 440
    assert model["counts"]["eventsOmitted"] == 0
    assert len(encoded(model)) <= MODEL_MAX_BYTES
