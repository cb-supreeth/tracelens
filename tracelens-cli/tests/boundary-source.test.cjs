const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");
const deps = require("../tracelens_cli/web/dependency-ui.js");
const source = require("../tracelens_cli/web/boundary-source.js");

const CLS = "com.chargebee.ems.FeaturesService";
const CHILD = "com.chargebee.ems.FeatureRepository";
const event = (seq, type, extra = {}) => ({seq, type, thread: "emsAsync-353", className: CLS, methodName: "createFeature", ...extra});
const route = (seq, extra = {}) => event(seq, "DB_ROUTE", {attributes: {route: "TEST_MASTER"}, ...extra});
const sqs = (seq, extra = {}) => event(seq, "EXTERNAL_CALL", {attributes: {
  boundaryKind: "messaging", system: "sqs", operation: "sendMessage", direction: "publish",
  destination: "cb-local-ems-events-ms", evidenceKind: "client-boundary", status: "SUBMITTED"}, ...extra});
const trace = events => ({segmentId: "segment-ems", serviceInstanceId: "ems-instance", service: "cb-ems", events});
const node = (boundary, extra = {}) => ({kind: "dependency-call", segmentId: "segment-ems",
  serviceInstanceId: "ems-instance", service: "cb-ems", eventSeq: boundary.seq, thread: boundary.thread,
  ...deps.boundaryMetadata(boundary), ...extra});
const result = (events, marker = events[events.length - 1], extra = {}) => source.resolve(trace(events), node(marker, extra));

test("runtime thread identities separate duplicate names and survive thread renames", () => {
  const events = [event(1, "METHOD_ENTER", {threadId: 1, methodDescriptor: "()V"}),
    event(2, "LINE", {threadId: 1, line: 54}),
    event(3, "METHOD_ENTER", {threadId: 2, methodDescriptor: "()V"}),
    event(4, "LINE", {threadId: 2, line: 72}), route(5, {threadId: 1, thread: "renamed"})];
  const resolved = result(events, events[4], {threadId: 1});
  assert.deepEqual(resolved.coordinate, {className: CLS, line: 54});
  assert.equal(resolved.methodDescriptor, "()V");
  assert.match(resolved.note, /Runtime thread identity/);
  assert.equal(result(events, events[4], {threadId: 2}).reason, "thread-id-mismatch");
  assert.equal(result([...events.slice(0, 4), route(5, {threadId: "1"})]).coordinate, null);
  assert.equal(result([...events.slice(0, 4), route(5)]).coordinate, null, "legacy boundary must not adopt a strong lane by name");
});

test("overloaded method exits do not leave another invocation as the inferred caller", () => {
  const events = [event(1, "METHOD_ENTER", {threadId: 1, methodDescriptor: "(I)V"}),
    event(2, "LINE", {threadId: 1, line: 54}),
    event(3, "METHOD_ENTER", {threadId: 1, methodDescriptor: "()V"}),
    event(4, "LINE", {threadId: 1, line: 72}),
    event(5, "METHOD_EXIT", {threadId: 1, methodDescriptor: "(I)V"}), route(6, {threadId: 1})];
  assert.equal(result(events).coordinate, null);
  events[4].methodDescriptor = "()V";
  assert.deepEqual(result(events).coordinate, {className: CLS, line: 54});
  for (const descriptor of [null, "broken", "(V)V"]) {
    events[4].methodDescriptor = descriptor;
    assert.equal(result(events).coordinate, null, "malformed or legacy exits cannot match described frames");
  }
});

test("HTTP anchors validate strong thread identity instead of falling back to names", () => {
  const events = [event(1, "METHOD_ENTER", {threadId: 1, methodDescriptor: "()V"}),
    event(2, "LINE", {threadId: 1, line: 54, thread: "renamed"})];
  const call = {callingContextStatus: "recorded", callingEventSeq: "2", callingThread: "old-name", callingThreadId: "1"};
  assert.deepEqual(source.resolveHttp(trace(events), call).coordinate, {className: CLS, line: 54});
  assert.equal(source.resolveHttp(trace(events), {...call, callingThreadId: "2"}).coordinate, null);
  assert.equal(source.resolveHttp(trace(events), {...call, callingThreadId: null, callingThread: "renamed"}).coordinate, null);
  assert.equal(source.resolveHttp(trace(events), {...call, callingThreadId: "1e0"}).coordinate, null);
});

test("JDBC calling code resolves exact retained events without copying SQL into source coordinates", () => {
  for (const system of ["mysql", "postgresql"]) {
    const marker = event(3, "EXTERNAL_CALL", {attributes: {boundaryKind: "database", system,
      direction: "outbound", operation: "executeQuery", status: "OBSERVED", evidenceKind: "jdbc-execution",
      sql: "select 'SECRET'", durationMs: 3}});
    const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 72}), marker];
    const resolved = result(events);
    assert.equal(resolved.eventIndex, 2); assert.equal(resolved.sourceIndex, 1);
    assert.deepEqual(resolved.coordinate, {className: CLS, line: 72});
    assert.doesNotMatch(JSON.stringify(resolved), /SECRET|select/);
  }
});

test("DB route and SQS boundary resolve a retained calling line without exporting payloads", () => {
  for (const marker of [route(3), sqs(3)]) {
    const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54, source: "SECRET"}), marker];
    marker.attributes.payload = "SECRET";
    const before = JSON.stringify(events);
    const resolved = result(events);
    assert.equal(resolved.eventIndex, 2);
    assert.equal(resolved.sourceIndex, 1);
    assert.deepEqual(resolved.coordinate, {className: CLS, line: 54});
    assert.equal(resolved.methodName, "createFeature");
    assert.match(resolved.note, /Observed calling context.*not proof.*exact callsite.*direct caller/);
    assert.match(resolved.note, /Thread names are not unique/);
    assert.doesNotMatch(JSON.stringify(resolved), /SECRET|cb-local/);
    assert.equal(JSON.stringify(events), before);
  }
});

test("exact retained cache wrapper entry resolves preceding caller context without a cache hit claim", () => {
  const wrapper = event(3, "METHOD_ENTER", {className: "com.chargebee.ems.config.cache.ValkeyCacheService",
    methodName: "get", arguments: ["SECRET"]});
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), wrapper];
  const resolved = result(events);
  assert.equal(resolved.eventIndex, 2); assert.equal(resolved.sourceIndex, 1);
  assert.deepEqual(resolved.coordinate, {className: CLS, line: 54});
  assert.doesNotMatch(JSON.stringify(resolved), /SECRET|HIT|MISS/);
  assert.match(resolved.note, /Observed calling context.*not proof/);
});

test("repeated grouped DB routes independently locate their own exact events", () => {
  const first = route(3), second = route(5);
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), first, event(4, "LINE", {line: 72}), second];
  assert.deepEqual(result(events, first).coordinate, {className: CLS, line: 54});
  assert.deepEqual(result(events, second).coordinate, {className: CLS, line: 72});
  assert.equal(result(events, first).eventIndex, 2);
  assert.equal(result(events, second).eventIndex, 4);
});

test("safe decimal sequences are accepted but unsafe and coercible identities are rejected", () => {
  const events = [event("1", "METHOD_ENTER"), event("2", "LINE", {line: 54}), route("3")];
  assert.equal(result(events, events[2], {eventSeq: 3}).sourceIndex, 1);
  for (const seq of [null, undefined, "", "3e0", " 3", "-3", 0, -3, 3.1, true, {}, [], "9007199254740992", Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(result(events, events[2], {eventSeq: seq}).eventIndex, null);
  }
  assert.equal(result(events, events[2], {eventSeq: "9007199254740991"}).reason, "event-not-retained");
});

test("duplicate sequence anywhere, even another event type or thread, prevents resolution", () => {
  const marker = route(3);
  for (const duplicate of [event(3, "LINE", {thread: "another-thread", line: 4}), sqs("3")]) {
    const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), marker, duplicate];
    assert.equal(result(events, marker).reason, "ambiguous-event-sequence");
  }
  assert.equal(result([event(1, "METHOD_ENTER")], route(3)).eventIndex, null);
});

test("supplied owning identities and boundary metadata must match exactly", () => {
  const marker = sqs(3), events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), marker];
  for (const field of ["segmentId", "serviceInstanceId", "service", "thread", "boundaryKind", "system", "operation", "destination", "status", "direction", "evidenceKind"]) {
    assert.equal(result(events, marker, {[field]: "wrong"}).eventIndex, null, field);
    assert.equal(result(events, marker, {[field]: {toString: () => marker[field]}}).eventIndex, null, field);
  }
  assert.equal(result(events, marker, {kind: "segment"}).eventIndex, null);
  assert.equal(result([event(1, "LINE")], event(1, "LINE")).eventIndex, null);
});

test("canonical metadata strips credentials and aliases never establish identity", () => {
  const marker = sqs(3);
  marker.attributes.destination = "https://user:SECRET@sqs.example/orders?token=SECRET";
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), marker];
  assert.equal(result(events, marker, {destinationName: "Alias ignored"}).sourceIndex, 1);
  assert.equal(result(events, marker, {destination: marker.attributes.destination}).eventIndex, null);
  assert.doesNotMatch(JSON.stringify(result(events)), /SECRET/);
});

test("database and cache client markers use the same context without inventing SQL or hit/miss", () => {
  for (const [boundaryKind, system, operation] of [["database", "clickhouse", "queryRecords"], ["cache", "redis", "get"]]) {
    const marker = event(3, "EXTERNAL_CALL", {className: null, methodName: null, attributes: {
      boundaryKind, system, operation, direction: "outbound", evidenceKind: "client-boundary", status: "OBSERVED",
      sql: "SECRET", key: "SECRET", value: "SECRET"}});
    const resolved = result([event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}), marker]);
    assert.deepEqual(resolved.coordinate, {className: CLS, line: 54});
    assert.doesNotMatch(JSON.stringify(resolved), /SECRET|SQL|cache hit|cache miss/);
  }
});

test("interleaved thread names retain separate active frames", () => {
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 54}),
    event(3, "METHOD_ENTER", {thread: "famAsync-5", className: CHILD}),
    event(4, "LINE", {thread: "famAsync-5", className: CHILD, line: 99}), route(5)];
  assert.deepEqual(result(events).coordinate, {className: CLS, line: 54});
  const other = route(6, {thread: "famAsync-5"});
  assert.deepEqual(result([...events, other]).coordinate, {className: CHILD, line: 99});
});

test("nested and recursive frames never borrow a parent or sibling line", () => {
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10}),
    event(3, "METHOD_ENTER", {className: CHILD}), event(4, "LINE", {className: CHILD, line: 20}),
    event(5, "METHOD_EXIT", {className: CHILD}), route(6)];
  assert.deepEqual(result(events).coordinate, {className: CLS, line: 10});
  const recursive = [...events.slice(0, 2), event(3, "METHOD_ENTER"), route(4)];
  assert.equal(result(recursive).sourceIndex, null, "fresh recursion cannot borrow parent's line");
  const withLine = [...recursive.slice(0, 3), event(4, "LINE", {line: 30}), route(5)];
  assert.equal(result(withLine).coordinate.line, 30);
  const afterExit = [...withLine, event(6, "METHOD_EXIT"), route(7)];
  assert.equal(result(afterExit).coordinate.line, 10);
  const sibling = [...events.slice(0, 5), event(6, "METHOD_ENTER", {className: CHILD}), route(7)];
  assert.equal(result(sibling).sourceIndex, null, "fresh sibling cannot borrow exited child's line");
});

test("exceptions and unmatched exits clear frames without inventing direct callers", () => {
  const start = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10})];
  for (const close of ["METHOD_EXIT", "EXCEPTION"]) {
    assert.equal(result([...start, event(3, close), route(4)]).sourceIndex, null);
    assert.equal(result([...start, event(3, close, {methodName: "unknown"}), route(4)]).sourceIndex, null);
    const nested = [...start, event(3, "METHOD_ENTER", {className: CHILD}),
      event(4, "LINE", {className: CHILD, line: 20}), event(5, close), route(6)];
    assert.equal(result(nested).sourceIndex, null, "matched outer exit closes abandoned inner frames too");
  }
});

test("sequence gaps, reordering and malformed events interrupt all source context", () => {
  for (const events of [
    [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10}), route(4)],
    [event(2, "METHOD_ENTER"), event(1, "LINE", {line: 10}), route(3)],
    [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10}), null, route(4)],
    [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10}), event(null, "OTHER"), route(4)]
  ]) {
    const resolved = result(events);
    assert.equal(resolved.eventIndex, events.length - 1);
    assert.equal(resolved.sourceIndex, null);
  }
  const recovered = [event(1, "METHOD_ENTER"), event(3, "METHOD_ENTER"), event(4, "LINE", {line: 30}), route(5)];
  assert.equal(result(recovered).coordinate.line, 30, "new observed frame after gap is usable");
});

test("omitted detail clears all callers even when retained event sequences are contiguous", () => {
  const start = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10})];
  for (const tail of [[route(3, {detailGapBefore: true})],
    [event(3, "OTHER", {thread: "other", detailGapBefore: true}), route(4)]]) {
    const resolved = result([...start, ...tail]);
    assert.equal(resolved.eventIndex, start.length + tail.length - 1);
    assert.equal(resolved.sourceIndex, null);
    assert.equal(resolved.coordinate, null);
  }
  const anchor = event(3, "LINE", {line: 20, detailGapBefore: true});
  assert.equal(source.resolveHttp(trace([...start, anchor]), {callingContextStatus: "recorded",
    callingEventSeq: 3, callingThread: anchor.thread}).coordinate, null);
  const recovered = [...start, event(3, "METHOD_ENTER", {detailGapBefore: true}),
    event(4, "LINE", {line: 30}), route(5)];
  assert.equal(result(recovered).coordinate.line, 30, "a newly observed frame after omitted details is usable");
});

test("missing thread identity, invalid method identity and mismatched LINE classes are conservative", () => {
  const start = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 10})];
  for (const badThread of [null, "", [], "x".repeat(4097)]) {
    const events = [...start, event(3, "METHOD_ENTER", {thread: badThread}), route(4)];
    assert.equal(result(events).sourceIndex, null);
    assert.equal(result([...start, route(3, {thread: badThread})], undefined, {thread: null}).sourceIndex, null);
  }
  for (const malformed of [
    event(3, "METHOD_ENTER", {className: "<script>SECRET</script>"}),
    event(3, "METHOD_ENTER", {methodName: {secret: "SECRET"}})
  ]) assert.equal(result([...start, malformed, route(4)]).sourceIndex, null);
  for (const cls of [CHILD, "../SECRET", "com.x\nSECRET", {}, "Foo"]) {
    assert.equal(result([event(1, "METHOD_ENTER"), event(2, "LINE", {className: cls, line: 10}), route(3)]).sourceIndex, null);
  }
  for (const line of ["10", 0, -1, 1.1, 2147483648, true, {}]) {
    assert.equal(result([event(1, "METHOD_ENTER"), event(2, "LINE", {line}), route(3)]).sourceIndex, null);
  }
});

test("bounded reconstruction never reuses stale context after a depth or thread limit", () => {
  const deep = Array.from({length: 513}, (_, i) => event(i + 1, "METHOD_ENTER"));
  assert.equal(result([...deep, route(514)]).reason, "depth-limit");
  const threads = Array.from({length: 65}, (_, i) => event(i + 1, "OTHER", {thread: "thread-" + i}));
  assert.equal(result([...threads, route(66)]).reason, "thread-limit");
  const huge = Array.from({length: 100001}, (_, i) => event(i + 1, "OTHER"));
  huge[2] = route(3);
  assert.equal(result(huge, huge[2]).reason, "event-limit");
});

test("UMD browser export needs only the existing canonical metadata helper", () => {
  const context = {TraceLensDependencies: deps};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../tracelens_cli/web/boundary-source.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensBoundarySource.resolve, "function");
  assert.equal(Object.isFrozen(context.TraceLensBoundarySource), true);
  const missing = {};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../tracelens_cli/web/boundary-source.js"), "utf8"), missing);
  assert.equal(missing.TraceLensBoundarySource.resolve(trace([route(1)]), node(route(1))).reason, "metadata-unavailable");
});

test("HTTP start-time anchors resolve the caller frame inclusively, not the completion thread", () => {
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 72}),
    event(3, "METHOD_EXIT"), event(4, "LINE", {thread: "response-worker", line: 999})];
  const call = {callingContextStatus: "recorded", callingEventSeq: "2", callingThread: "emsAsync-353",
    path: "/SECRET", payload: "SECRET", durationNanos: "123"};
  const resolved = source.resolveHttp(trace(events), call);
  assert.equal(resolved.eventIndex, 1); assert.equal(resolved.sourceIndex, 1);
  assert.deepEqual(resolved.coordinate, {className: CLS, line: 72});
  assert.match(resolved.note, /when the outgoing HTTP call started/);
  assert.doesNotMatch(JSON.stringify(resolved), /SECRET|999/);
});

test("HTTP anchors process nested enter and exits before selecting an active frame", () => {
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 72}),
    event(3, "METHOD_ENTER", {className: CHILD}), event(4, "LINE", {className: CHILD, line: 90}),
    event(5, "METHOD_EXIT", {className: CHILD}), event(6, "METHOD_EXIT")];
  const resolve = seq => source.resolveHttp(trace(events), {callingContextStatus: "recorded",
    callingEventSeq: seq, callingThread: "emsAsync-353"});
  assert.equal(resolve(3).coordinate, null, "a fresh child cannot borrow its parent's line");
  assert.equal(resolve(4).coordinate.line, 90);
  assert.equal(resolve(5).coordinate.line, 72);
  assert.equal(resolve(6).coordinate, null, "an exited frame is not a caller");
});

test("legacy and invalid HTTP anchors never infer source from timestamps or other calls", () => {
  const events = [event(1, "METHOD_ENTER"), event(2, "LINE", {line: 72})];
  const anchor = {callingContextStatus: "recorded", callingEventSeq: "2", callingThread: "emsAsync-353"};
  for (const call of [{startedAt: 123}, {...anchor, callingContextStatus: "start-not-observed"},
    {...anchor, callingEventSeq: "2e0"}, {...anchor, callingThread: "other"}]) {
    const resolved = source.resolveHttp(trace(events), call);
    assert.equal(resolved.coordinate, null); assert.equal(resolved.eventIndex, null);
  }
  assert.match(source.resolveHttp(trace(events), {}).note, /updated agent.*No source line is guessed/);
  assert.equal(source.resolveHttp(trace([...events, event(2, "LINE")]), anchor).reason, "ambiguous-event-sequence");
  assert.equal(source.resolveHttp(trace([event(1, "METHOD_ENTER"), event(3, "LINE", {line: 72})]),
    {...anchor, callingEventSeq: "3"}).coordinate, null);
});
