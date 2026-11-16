const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const deps = require("../tracelens_cli/web/dependency-ui.js");
const cache = require("../tracelens_cli/web/cache-evidence.js");
const CLS = "com.chargebee.ems.config.cache.ValkeyCacheService";
const event = (seq, type = "METHOD_ENTER", extra = {}) => ({seq, type, className: CLS, methodName: "get", thread: "worker", ...extra});
const identity = {traceId: "trace", service: "cb-ems", serviceInstanceId: "instance", segmentId: "segment", segmentKey: "ems-key"};
const fixture = (events = [event(1), event(2, "METHOD_EXIT")]) => {
  const segment = {...identity}, owner = {...identity, id: "owner", kind: "segment"};
  delete owner.traceId;
  return {exports: [{...identity, events}],
    journey: {traceId: "trace", partial: false, segments: [segment], externalCalls: [], links: ["preserved-link"],
      visualization: {schemaVersion: 1, kind: "request-flow", traceId: "trace", nodes: [owner], edges: [], roots: ["owner"], warnings: []}}};
};
const augment = f => cache.augmentJourney(f.journey, f.exports);
const freeze = value => { if (value && typeof value === "object") { Object.values(value).forEach(freeze); Object.freeze(value); } return value; };
const client = (seq, operation = "get", extra = {}) => event(seq, "EXTERNAL_CALL", {className: null, methodName: null,
  attributes: {boundaryKind: "cache", system: "redis", operation, evidenceKind: "client-boundary", status: "OBSERVED", direction: "outbound"}, ...extra});

test("browser and CommonJS expose metadata-only augmentation without I/O", () => {
  const context = {TraceLensDependencies: deps};
  const script = fs.readFileSync(require.resolve("../tracelens_cli/web/cache-evidence.js"), "utf8");
  vm.runInNewContext(script, context);
  assert.equal(typeof context.TraceLensCacheEvidence.augmentJourney, "function");
  assert.doesNotMatch(script, /fetch\(|XMLHttpRequest|localStorage|sessionStorage|setInterval|setTimeout/);
});

test("old wrapper capture adds one truthful owned cache card and retains every existing relationship", () => {
  const f = fixture();
  f.exports[0].events[0].sourceLine = "SECRET";
  f.exports[0].events[0].attributes = {key: "SECRET", result: "HIT"};
  f.journey.visualization.nodes.push({id: "db", kind: "dependency-call", ...identity, boundaryKind: "database-route"});
  f.journey.visualization.edges.push({id: "db-edge", from: "owner", to: "db", kind: "outbound"});
  freeze(f);
  const result = augment(f), node = result.visualization.nodes.at(-1);
  assert.notEqual(result, f.journey);
  assert.equal(node.kind, "dependency-call"); assert.equal(node.boundaryKind, "cache");
  assert.equal(node.system, "redis"); assert.equal(node.evidenceKind, "wrapper-entry");
  assert.equal(node.status, "OBSERVED"); assert.equal(node.downstreamState, "not-observed");
  assert.equal(node.ownerId, "owner"); assert.equal(node.eventSeq, 1); assert.equal(node.thread, "worker");
  assert.equal(node.callKey, "ems-key:cache-wrapper:1");
  assert.deepEqual(result.visualization.edges.at(-1), {id: "cache-wrapper-edge:owner:1", from: "owner", to: node.id, kind: "outbound"});
  assert.equal(result.visualization.edges[0], f.journey.visualization.edges[0]);
  assert.equal(result.visualization.roots, f.journey.visualization.roots);
  assert.equal(result.links, f.journey.links); assert.equal(result.segments, f.journey.segments);
  assert.match(result.visualization.warnings.join(" "), /recorded.*method entries.*not new remote evidence/);
  assert.doesNotMatch(JSON.stringify(node), /SECRET|HIT|MISS/);
  assert.equal(f.journey.visualization.nodes.length, 2);
  assert.equal(cache.augmentJourney(result, f.exports), result, "second augmentation returns the same object");
});

test("only exact cache wrapper methods qualify; helpers and similarly named classes remain untouched", () => {
  for (const extra of [{methodName: "getKey"}, {methodName: "getCachedValue"}, {className: "example.ValkeyCacheService"},
    {type: "LINE"}, {className: CLS + "$Proxy"}]) {
    const f = fixture([event(1, "METHOD_ENTER", extra)]);
    assert.equal(augment(f), f.journey);
  }
  for (const methodName of ["get", "put", "evict"]) {
    const result = augment(fixture([event(1, "METHOD_ENTER", {methodName})]));
    assert.equal(result.externalCalls[0].operation, methodName);
  }
});

test("missing or ambiguous captured owners and cross-request/instance identities never add a card", () => {
  for (const field of ["traceId", "service", "serviceInstanceId", "segmentId", "segmentKey"]) {
    const f = fixture(); f.exports[0][field] = "different";
    assert.equal(augment(f), f.journey, field);
    const missing = fixture(); delete missing.exports[0][field];
    assert.equal(augment(missing), missing.journey, `missing ${field}`);
  }
  const mutations = [
    f => f.exports.push({...f.exports[0]}),
    f => f.journey.segments.push({...f.journey.segments[0]}),
    f => f.journey.visualization.nodes.push({...f.journey.visualization.nodes[0], id: "second"}),
    f => f.journey.segments.push({...f.journey.segments[0], service: "different"}),
    f => f.journey.visualization.traceId = "different",
    f => f.journey.visualization.nodes[0].serviceInstanceId = "different",
    f => f.journey.visualization.nodes[0].traceId = "different"
  ];
  for (const change of mutations) { const f = fixture(); change(f); assert.equal(augment(f), f.journey); }
});

test("missing diagrams or invalid graph identities do not create or repair a graph", () => {
  for (const change of [
    f => delete f.journey.visualization,
    f => f.journey.visualization.kind = "execution-flow",
    f => f.journey.visualization.nodes.push({...f.journey.visualization.nodes[0]}),
    f => f.journey.visualization.edges.push({id: "bad", from: "missing", to: "owner", kind: "outbound"}),
    f => f.journey.visualization.roots.push("missing")
  ]) { const f = fixture(); change(f); assert.equal(augment(f), f.journey); }
});

test("duplicate, missing or unsafe sequence and missing thread cannot create source-navigation targets", () => {
  for (const events of [
    [event(1), event(1, "LINE")], [event(null)], [event("9007199254740992")],
    [event(1, "METHOD_ENTER", {thread: ""})], [event(1, "METHOD_ENTER", {thread: null})],
    [event(1, "METHOD_ENTER", {thread: "x".repeat(129)})], [event(1, "METHOD_ENTER", {thread: "worker\n"})]
  ]) {
    const f = fixture(events), result = augment(f);
    assert.equal(result.visualization.nodes.length, 1);
    assert.equal(result.externalCalls.length, 0);
  }
});

test("existing graph and external-call owner/event identities suppress duplicate derived cards", () => {
  for (const place of ["graph", "calls"]) {
    const f = fixture();
    if (place === "graph") f.journey.visualization.nodes.push({id: "already", kind: "dependency-call", ...identity, eventSeq: 1});
    else f.journey.externalCalls.push({ownerSegmentKey: "ems-key", eventSeq: 1});
    assert.equal(augment(f), f.journey);
  }
  const f = fixture();
  f.journey.externalCalls.push({ownerSegmentKey: "another-owner", eventSeq: 1});
  assert.equal(augment(f).externalCalls.length, 2);
});

test("matching typed clients suppress wrappers only within same valid thread frame", () => {
  for (const [methodName, operation] of [["get", "get"], ["put", "set"], ["evict", "delete"]]) {
    const f = fixture([event(1, "METHOD_ENTER", {methodName}), client(2, operation), event(3, "METHOD_EXIT", {methodName})]);
    assert.equal(augment(f), f.journey);
  }
  for (const events of [
    [event(1), client(2, "get", {thread: "another-thread"}), event(3, "METHOD_EXIT")],
    [event(1), client(3), event(4, "METHOD_EXIT")],
    [event(1), event(2, "METHOD_EXIT"), client(3)],
    [event(1), client(2, "set"), event(3, "METHOD_EXIT")]
  ]) assert.equal(augment(fixture(events)).externalCalls.length, 1);
});

test("derived IDs remain deterministic and collision-free without changing existing nodes", () => {
  const f = fixture();
  f.journey.visualization.nodes.push({id: "cache-wrapper:owner:1", kind: "dependency-call"});
  f.journey.visualization.edges.push({id: "cache-wrapper-edge:owner:1", from: "owner", to: "cache-wrapper:owner:1", kind: "outbound"});
  const first = augment(f), second = augment(f);
  assert.equal(first.visualization.nodes.at(-1).id, "cache-wrapper:owner:1:1");
  assert.equal(first.visualization.edges.at(-1).id, "cache-wrapper-edge:owner:1:1");
  assert.deepEqual(first, second);
});

test("export and event scan limits fail closed with visible warnings", () => {
  const exports = fixture(); exports.exports = Array(65).fill(exports.exports[0]);
  const tooManyExports = augment(exports);
  assert.equal(tooManyExports.visualization.nodes.length, 1);
  assert.match(tooManyExports.visualization.warnings.join(" "), /limit reached/);
  const events = fixture(Array(100001).fill(event(1)));
  const tooManyEvents = augment(events);
  assert.equal(tooManyEvents.visualization.nodes.length, 1);
  assert.match(tooManyEvents.visualization.warnings.join(" "), /event limit reached/);
});

test("additional cache cards are bounded while remaining entries stay in the untouched export", () => {
  const events = Array.from({length: 130}, (_, index) => [
    event(index * 2 + 1), event(index * 2 + 2, "METHOD_EXIT")
  ]).flat();
  const f = fixture(events), result = augment(f);
  assert.equal(result.externalCalls.length, 128);
  assert.equal(f.exports[0].events.length, 260);
  assert.match(result.visualization.warnings.join(" "), /card limit reached/);
  assert.equal(cache.augmentJourney(result, f.exports), result, "repeated projection cannot exceed the card limit");
});
