import copy
import json

import pytest

from tracelens_cli.client import DebugApiError
from tracelens_cli.dependencies import (
    MAX_EVENTS, MAX_EXTERNAL_CALLS, MAX_WRAPPER_DEPTH, MAX_WRAPPER_THREADS, boundary_summary,
    destination, extract_boundaries, sanitize_summary, validate_aliases,
)
from tracelens_cli.mcp_server import TraceLensMcpServer
from tracelens_cli.services import Federation, Service, join_trace, load_services
from tracelens_cli.visualization import MAX_DEPENDENCIES, build_visualization, to_mermaid


TRACE_ID = "1234567890abcdef1234567890abcdef"


def event(direction="publish", **attrs):
    return {"seq": 2, "thread": "worker-1", "type": "EXTERNAL_CALL", "attributes": {
        "boundaryKind": "messaging", "system": "sqs", "direction": direction,
        "destination": "orders", "operation": "sendMessage", "status": "SUBMITTED",
        "evidenceKind": "client-boundary", **attrs}}


def segment(events=None):
    return {"traceId": TRACE_ID, "segmentId": "app-segment", "serviceInstanceId": "app-instance",
            "service": "app", "segmentKey": "app-key", "spanId": "app-span", "status": "COMPLETED",
            "events": events or [], "httpCalls": []}


class ReadOnlyClient:
    def __init__(self, row):
        self.row = row
        self.exports = []

    def find_by_trace_id(self, trace_id):
        assert trace_id == TRACE_ID
        return {"traces": [copy.deepcopy(self.row)]}

    def export(self, segment_id, evict):
        assert evict is False
        self.exports.append(segment_id)
        return copy.deepcopy(self.row)


def test_boundaries_survive_federation_without_raw_event_payloads():
    secret = "SHOULD_NOT_APPEAR"
    raw = event(payload=secret, messages=[secret], headers={"Authorization": secret}, user=secret)
    raw.update(className=secret, sourceLine=secret, payload=secret)
    row = segment([{"type": "LINE", "sourceLine": secret}, raw,
                   {"type": "EXTERNAL_CALL", "attributes": {"system": "DynamoRepository", "detail": "save"}},
                   {"type": "DB_ROUTE", "attributes": {"route": "live-replica", "sql": secret}}])
    federation = Federation([Service("app", "http://unused", dependency_aliases={"orders": "Billing events"})])
    client = ReadOnlyClient(row)
    federation.clients = {"app": client}
    original = copy.deepcopy(row)
    result = federation.get_distributed_trace(TRACE_ID)
    assert row == original
    assert len(result["segments"]) == 1
    assert "events" not in result["segments"][0]
    assert "externalCalls" not in result["segments"][0]
    assert secret not in json.dumps(result)
    assert len(result["externalCalls"]) == 3
    messaging, wrapper, database = result["externalCalls"]
    assert messaging["callKey"].endswith(":dependency:1")
    assert messaging["ownerSegmentKey"] == result["segments"][0]["segmentKey"]
    assert messaging["destinationName"] == "Billing events"
    assert messaging["eventSeq"] == 2 and messaging["thread"] == "worker-1"
    assert messaging["downstreamState"] == "not-observed"
    assert (wrapper["boundaryKind"], wrapper["evidenceKind"], wrapper["status"]) == (
        "external", "wrapper-entry", "OBSERVED")
    assert wrapper["operation"] == "save"
    assert wrapper["destination"] is None
    assert (database["boundaryKind"], database["evidenceKind"], database["destination"]) == (
        "database-route", "routing-marker", "live-replica")
    assert result["links"] == []
    assert client.exports == ["app-segment"]


@pytest.mark.parametrize("kind,system,prefix", [
    ("database", "clickhouse", "Database call"), ("database", "dynamodb", "Database call"),
    ("cache", "redis", "Cache call"), ("external", "s3", "External boundary")])
def test_data_clients_preserve_identity_without_sql_keys_payloads_or_remote_segments(kind, system, prefix):
    raw = event("outbound", boundaryKind=kind, system=system, destination=None,
                operation="get", status="OBSERVED", sql="SECRET", key="SECRET", value="SECRET")
    journey = join_trace(TRACE_ID, [segment([raw])], [])
    graph = build_visualization(journey)
    dependency = next(node for node in graph["nodes"] if node["kind"] == "dependency-call")
    assert dependency["boundaryKind"] == kind
    assert dependency["system"] == system
    assert dependency["destination"] is None
    assert dependency["evidenceKind"] == "client-boundary"
    assert dependency["label"].startswith(prefix)
    assert dependency["downstreamState"] == "not-observed"
    assert len(graph["nodes"]) == 2
    assert [edge["kind"] for edge in graph["edges"]] == ["outbound"]
    assert "SECRET" not in json.dumps(graph)


@pytest.mark.parametrize("kind", ["database", "cache"])
@pytest.mark.parametrize("evidence", [None, "unsupported", "wrapper-entry"])
def test_data_kind_without_client_evidence_does_not_claim_a_client_call(kind, evidence):
    raw = event("outbound", boundaryKind=kind, system="redis", evidenceKind=evidence)
    graph = build_visualization(join_trace(TRACE_ID, [segment([raw])], []))
    dependency = next(node for node in graph["nodes"] if node["kind"] == "dependency-call")
    assert not dependency["label"].startswith(("Database call", "Cache call"))
    if evidence == "wrapper-entry":
        assert dependency["label"].startswith("Cache access" if kind == "cache" else "Wrapper entry")


@pytest.mark.parametrize("kind,system,status", [
    ("database", "clickhouse", "SUBMITTED"), ("cache", "redis", "ERROR")])
def test_mcp_preserves_data_client_boundaries_without_invented_receiver(kind, system, status):
    server = TraceLensMcpServer("http://unused", None)
    server.discovery.clients = {"default": ReadOnlyClient(segment([
        event("outbound", boundaryKind=kind, system=system, destination=None, status=status)]))}
    result = server.tool_get_trace_visualization(TRACE_ID)
    dependency = next(node for node in result["structuredContent"]["nodes"] if node["kind"] == "dependency-call")
    assert (dependency["boundaryKind"], dependency["system"], dependency["status"]) == (kind, system, status)
    assert dependency["destination"] is None
    assert dependency["downstreamState"] == "not-observed"
    assert len(result["structuredContent"]["nodes"]) == 2


@pytest.mark.parametrize("receiver", ["unregistered", "down"])
def test_http_target_stays_visible_and_alias_never_invents_receiver(receiver):
    row = segment([event()])
    row["httpCalls"] = [{"spanId": "outgoing", "peer": "ems:8082", "httpMethod": "GET", "path": "/features",
                         "status": "ERROR", "durationNanos": 1000}]
    registry = [Service("app", "http://unused-app", dependency_aliases={"ems:8082": "EMS", "orders": "EMS"})]
    if receiver == "down":
        registry.append(Service("ems", "http://unused-ems"))
    federation = Federation(registry)
    federation.clients["app"] = ReadOnlyClient(row)
    if receiver == "down":
        class Offline:
            def find_by_trace_id(self, trace_id):
                raise DebugApiError("offline")
        federation.clients["ems"] = Offline()
    journey = federation.get_distributed_trace(TRACE_ID)
    assert len(journey["segments"]) == 1 and journey["links"] == []
    assert journey["httpCalls"][0]["destinationName"] == "EMS"
    model = build_visualization(journey)
    calls = [node for node in model["nodes"] if node["kind"] == "http-call"]
    assert len(calls) == 1 and calls[0]["downstreamState"] == "not-observed"
    assert "EMS" in calls[0]["label"] and "ems:8082" in calls[0]["label"]
    assert {edge["kind"] for edge in model["edges"]} == {"outbound"}
    assert len(model["nodes"]) == 3  # Owner + observed HTTP target + queue boundary, not a fake receiver.


def test_publish_consume_direction_never_correlates_similarly_named_targets():
    row = segment([event(), event("consume", operation="receiveMessage", status="OBSERVED")])
    model = build_visualization(join_trace(TRACE_ID, [row], []))
    owner = next(node for node in model["nodes"] if node["kind"] == "segment")
    calls = {node["direction"]: node for node in model["nodes"] if node["kind"] == "dependency-call"}
    edges = {(edge["from"], edge["to"], edge["kind"]) for edge in model["edges"]}
    assert edges == {(owner["id"], calls["publish"]["id"], "outbound"),
                     (calls["consume"]["id"], owner["id"], "inbound")}
    assert all(node["ownerId"] == owner["id"] and node["segmentId"] == "app-segment"
               and node["segmentKey"] == "app-key" and node["downstreamState"] == "not-observed"
               for node in calls.values())
    assert model["roots"] == [calls["consume"]["id"]]
    source = to_mermaid(model)
    assert "-->|inbound|" in source and "-->|outbound|" in source
    assert "remote execution not observed" in source


def test_dependency_link_does_not_become_an_observed_http_link():
    row = segment([event()])
    journey = join_trace(TRACE_ID, [row], [])
    journey["links"] = [{"fromCallKey": journey["externalCalls"][0]["callKey"],
                         "fromSegmentKey": "app-key", "toSegmentKey": "app-key"}]
    graph = build_visualization(journey)
    assert {edge["kind"] for edge in graph["edges"]} == {"outbound"}
    assert any("link omitted" in warning for warning in graph["warnings"])


def test_mcp_distributed_and_graph_tools_share_allowlisted_boundaries():
    server = TraceLensMcpServer("http://unused", None)
    client = ReadOnlyClient(segment([event("consume", operation="receiveMessage", status="OBSERVED")]))
    server.discovery.clients = {"default": client}
    journey = json.loads(server.tool_get_distributed_trace(TRACE_ID)["content"][0]["text"])
    result = server.tool_get_trace_visualization(TRACE_ID)
    assert result["structuredContent"] == build_visualization(journey)
    assert len(journey["externalCalls"]) == 1
    assert "1 dependency boundaries" in result["content"][0]["text"]
    assert result["structuredContent"]["edges"][0]["kind"] == "inbound"


def test_legacy_detail_is_not_arbitrary_text_or_confirmed_network():
    raw = {"type": "EXTERNAL_CALL", "attributes": {"system": "Wrapper", "detail": "password=SECRET",
           "status": "COMPLETED", "direction": "consume", "destination": "secret-queue"}}
    summary = boundary_summary(raw)
    assert summary["evidenceKind"] == "wrapper-entry"
    assert summary["status"] == "OBSERVED" and summary["direction"] == "outbound"
    assert summary["operation"] is None and summary["destination"] is None
    assert "SECRET" not in json.dumps(summary)
    graph = build_visualization(join_trace(TRACE_ID, [segment([raw])], []))
    assert "Wrapper entry" in graph["nodes"][1]["label"]


@pytest.mark.parametrize("bad", [None, {}, "payload", 1])
def test_malformed_event_collections_are_bounded_and_explicit(bad):
    assert extract_boundaries(bad) == ([], True)
    doc = segment()
    doc["events"] = bad
    journey = join_trace(TRACE_ID, [doc], [])
    assert journey["externalCalls"] == []
    assert journey["partial"]


@pytest.mark.parametrize("bad", [None, {}, [], True, -1, "1e1000", "NaN", "x" * 1000])
def test_malformed_boundary_fields_never_escape_scalar_allowlist(bad):
    raw = event(boundaryKind=bad, system=bad, direction=bad, destination=bad,
                operation=bad, status=bad, evidenceKind=bad, errorType=bad)
    raw.update(seq=bad, thread=bad)
    result = boundary_summary(raw)
    assert result["direction"] is None and result["eventSeq"] is None
    assert all(value is None or isinstance(value, (str, int)) for value in result.values())
    journey = join_trace(TRACE_ID, [segment([raw])], [])
    graph = build_visualization(journey)
    assert len(graph["nodes"]) == 2 and graph["edges"] == []
    assert any("direction not inferred" in warning for warning in graph["warnings"])
    json.dumps(graph, allow_nan=False)


def test_credential_queries_controls_and_html_are_not_boundary_metadata():
    target = "https://user:SECRET@sqs.example/123/orders?token=SECRET#SECRET"
    assert destination(target) == "https://sqs.example/123/orders"
    raw = event(destination=target, operation='send\n<script>SECRET</script>', errorType={"message": "SECRET"})
    raw["thread"] = "worker\u202eSECRET"
    graph = build_visualization(join_trace(TRACE_ID, [segment([raw])], []))
    assert "SECRET" not in json.dumps(graph)
    assert "<script>" not in to_mermaid(graph)
    assert destination("orders?password=SECRET") is None
    assert destination("kafka://user:SECRET@host/topic") is None
    assert destination("x" * 2049) is None


def test_boundary_budgets_stable_ids_and_no_input_mutation():
    events = [event() for _ in range(MAX_EXTERNAL_CALLS + 1)]
    calls, limited = extract_boundaries(events)
    assert limited and len(calls) == MAX_EXTERNAL_CALLS
    assert extract_boundaries([None] * (MAX_EVENTS + 1))[1]
    row = segment(events)
    original = copy.deepcopy(row)
    journey = join_trace(TRACE_ID, [row], [])
    assert row == original
    assert len(journey["externalCalls"]) == MAX_EXTERNAL_CALLS
    graph = build_visualization(journey)
    assert sum(node["kind"] == "dependency-call" for node in graph["nodes"]) == MAX_DEPENDENCIES
    assert any("limit reached" in warning for warning in graph["warnings"])
    first = build_visualization(join_trace(TRACE_ID, [segment([event()])], []))
    changed = build_visualization(join_trace(TRACE_ID, [segment([event(status="ERROR", errorType="TimeoutException")])], []))
    assert [node["id"] for node in first["nodes"]] == [node["id"] for node in changed["nodes"]]


def test_aliases_load_exactly_and_do_not_normalize_or_infer(tmp_path):
    aliases = {"ems:8082": "EMS", "orders": "Billing queue"}
    path = tmp_path / "services.json"
    path.write_text(json.dumps({"services": [{"name": "app", "url": "http://unused", "dependencyAliases": aliases}]}))
    services = load_services(str(path))
    assert services[0].dependency_aliases == aliases
    calls, _ = extract_boundaries([event(), event(destination="ORDERS")], aliases)
    assert calls[0]["destinationName"] == "Billing queue"
    assert calls[1]["destinationName"] is None
    assert Service("default", "http://unused").dependency_aliases == {}


@pytest.mark.parametrize("aliases", [None, [], {None: "bad"}, {"target": []},
    {"http://user:secret@host": "Host"}, {"target?password=secret": "Host"},
    {"target": "<script>"}, {"target": "x" * 129}, {"x" * 513: "Too long"},
    {str(i): "Many" for i in range(129)}])
def test_invalid_alias_configuration_rejected(aliases):
    with pytest.raises(ValueError, match="dependencyAliases"):
        validate_aliases(aliases)
    with pytest.raises(ValueError, match="dependencyAliases"):
        Service("app", "http://unused", dependency_aliases=aliases)


CACHE_WRAPPER = "com.chargebee.ems.config.cache.ValkeyCacheService"


def cache_method(seq, method="get", kind="METHOD_ENTER", **extra):
    return {"seq": seq, "type": kind, "thread": "emsAsync-353",
            "className": CACHE_WRAPPER, "methodName": method, **extra}


def cache_client(seq, operation="get", **extra):
    return {**event("outbound", boundaryKind="cache", system="redis", destination=None,
                   operation=operation, status="OBSERVED"),
            "seq": seq, "thread": "emsAsync-353", **extra}


@pytest.mark.parametrize("method", ["get", "put", "evict"])
def test_exact_audited_cache_wrapper_entry_is_weaker_metadata_only_evidence(method):
    raw = cache_method(292, method, sourceLine="SECRET", message="SECRET",
                       attributes={"key": "SECRET", "value": "SECRET", "status": "HIT"})
    original = copy.deepcopy(raw)
    projected = boundary_summary(raw)
    assert projected == {
        "boundaryKind": "cache", "system": "redis", "direction": "outbound",
        "destination": None, "operation": method, "status": "OBSERVED",
        "evidenceKind": "wrapper-entry", "errorType": None,
        "eventSeq": 292, "thread": "emsAsync-353", "downstreamState": "not-observed"}
    assert raw == original
    assert sanitize_summary(projected) == {**projected, "destinationName": None}
    assert "SECRET" not in json.dumps(projected)
    graph = build_visualization(join_trace(TRACE_ID, [segment([raw])], []))
    dependency = next(node for node in graph["nodes"] if node["kind"] == "dependency-call")
    assert dependency["boundaryKind"] == "cache"
    assert dependency["evidenceKind"] == "wrapper-entry"
    assert dependency["label"].startswith("Cache access")
    assert dependency["eventSeq"] == 292
    assert dependency["ownerId"] is not None
    assert len(graph["nodes"]) == 2
    assert graph["edges"][0]["kind"] == "outbound"
    assert "client-boundary" not in json.dumps(graph)


@pytest.mark.parametrize("changes", [
    {"className": "com.chargebee.ems.config.cache.SiteEpochService", "methodName": "currentEpoch"},
    {"className": "com.example.ValkeyCacheService"},
    {"className": CACHE_WRAPPER + "$$SpringCGLIB$$0"},
    {"className": CACHE_WRAPPER.lower()},
    {"className": None}, {"className": []},
    {"methodName": "currentSubscriptionEpoch"}, {"methodName": "formatReadMs"},
    {"methodName": "indexSubscriptionKey"}, {"methodName": "putSubscriptionEntitlement"},
    {"methodName": "cacheHit"}, {"methodName": None}, {"methodName": []},
    {"type": "METHOD_EXIT"}, {"type": "LINE"}, {"type": "EXCEPTION"}
])
def test_cache_fallback_never_guesses_from_names_helpers_or_non_entry_events(changes):
    assert boundary_summary({**cache_method(1), **changes}) is None
    assert extract_boundaries([{**cache_method(1), **changes}])[0] == []


@pytest.mark.parametrize("method,operation", [("get", "get"), ("put", "set"), ("evict", "delete")])
def test_typed_client_suppresses_only_its_matching_enclosing_cache_wrapper(method, operation):
    events = [cache_method(1, method), cache_method(2, "helper", className="com.example.Helper"),
              cache_client(3, operation), cache_method(4, "helper", "METHOD_EXIT", className="com.example.Helper"),
              cache_method(5, method, "METHOD_EXIT")]
    original = copy.deepcopy(events)
    calls, limited = extract_boundaries(events)
    assert not limited
    assert len(calls) == 1
    assert calls[0]["evidenceKind"] == "client-boundary"
    assert calls[0]["eventSeq"] == 3 and calls[0]["eventIndex"] == 2
    assert events == original


def test_same_trace_thread_or_operation_does_not_establish_cache_dedup_without_nested_evidence():
    cases = [
        [cache_method(1), cache_method(2, kind="METHOD_EXIT"), cache_client(3)],
        [cache_method(1), cache_method(2, kind="EXCEPTION"), cache_client(3)],
        [cache_method(1), cache_client(2, thread="another-thread")],
        [cache_method(1), cache_client(2, "set")],
        [cache_method(1), cache_client(3)],
        [cache_method(2), cache_client(1)],
        [cache_method(1), None, cache_client(3)],
        [cache_method(1), cache_method(2, "unknown", "METHOD_EXIT"), cache_client(3)],
        [cache_method(1), cache_method(2, "unknown", thread=None), cache_client(3)],
        [cache_method(1), cache_method(2, method=None), cache_client(3)],
    ]
    for events in cases:
        calls, _ = extract_boundaries(events)
        assert [call["evidenceKind"] for call in calls] == ["wrapper-entry", "client-boundary"], events


def test_unrelated_typed_boundaries_do_not_suppress_cache_access():
    for changes in [{"system": "clickhouse"}, {"boundaryKind": "database"},
                    {"evidenceKind": "wrapper-entry"}, {"direction": "consume"}, {"status": "unknown"}]:
        client = cache_client(2)
        client["attributes"].update(changes)
        calls, _ = extract_boundaries([cache_method(1), client])
        assert len(calls) == 2
        assert calls[0]["evidenceKind"] == "wrapper-entry"


def test_repeated_wrappers_and_interleaved_threads_keep_only_matching_client_replacements():
    events = [cache_method(1), cache_client(2), cache_method(3, kind="METHOD_EXIT"),
              cache_method(4), cache_method(5, thread="other"),
              cache_client(6, thread="other"), cache_method(7, kind="METHOD_EXIT", thread="other"),
              cache_method(8, kind="METHOD_EXIT")]
    calls, _ = extract_boundaries(events)
    assert [(call["eventSeq"], call["evidenceKind"]) for call in calls] == [
        (2, "client-boundary"), (4, "wrapper-entry"), (6, "client-boundary")]


def test_nested_same_wrapper_replaces_only_nearest_frame_and_can_recover_after_gap():
    events = [cache_method(1), cache_method(2), cache_client(3),
              cache_method(4, kind="METHOD_EXIT"), cache_method(5, kind="METHOD_EXIT")]
    calls, _ = extract_boundaries(events)
    assert [(call["eventSeq"], call["evidenceKind"]) for call in calls] == [
        (1, "wrapper-entry"), (3, "client-boundary")]
    events = [cache_method(1), cache_method(3), cache_client(4)]
    calls, _ = extract_boundaries(events)
    assert [(call["eventSeq"], call["evidenceKind"]) for call in calls] == [
        (1, "wrapper-entry"), (4, "client-boundary")]


def test_cache_wrapper_suppression_bounds_do_not_hide_actual_typed_client_evidence():
    deep = [cache_method(1)]
    deep.extend(cache_method(i + 2, "helper", className="com.example.Helper")
                for i in range(MAX_WRAPPER_DEPTH))
    deep.append(cache_client(len(deep) + 1))
    calls, limited = extract_boundaries(deep)
    assert limited
    assert [call["evidenceKind"] for call in calls] == ["wrapper-entry", "client-boundary"]
    threads = [cache_method(1)]
    threads.extend(cache_method(i + 2, "helper", className="com.example.Helper", thread=f"thread-{i}")
                   for i in range(MAX_WRAPPER_THREADS))
    threads.append(cache_client(len(threads) + 1))
    calls, limited = extract_boundaries(threads)
    assert limited
    assert [call["evidenceKind"] for call in calls] == ["wrapper-entry", "client-boundary"]
