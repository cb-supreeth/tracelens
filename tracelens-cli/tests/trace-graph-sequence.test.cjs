"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const graph = require("../tracelens_cli/web/trace-graph.js");

function fixture() {
  const model = {kind: "request-flow", nodes: [], edges: [], roots: []};
  function segment(id, service = id) {
    const value = {id, kind: "segment", service, segmentId: `${id}-segment`, segmentKey: `${id}-key`, serviceInstanceId: `${service}-instance`};
    model.nodes.push(value); return value;
  }
  function call(id, owner, target, start, duration = 10, extra = {}) {
    const value = {id, kind: "http-call", ownerId: owner.id, service: owner.service, segmentId: owner.segmentId,
      segmentKey: owner.segmentKey, serviceInstanceId: owner.serviceInstanceId, callKey: `${id}-key`,
      status: "COMPLETED", downstreamState: target ? "observed" : "not-observed", peer: "api.internal:8080",
      durationMs: duration, ...(start === undefined ? {} : {startedAtMillis: start}), ...extra};
    model.nodes.push(value); model.edges.push({id: `out:${id}`, from: owner.id, to: id, kind: "outbound"});
    if (target) model.edges.push({id: `observed:${id}`, from: id, to: target.id, kind: "observed-call"});
    return value;
  }
  return {model, segment, call};
}
const labels = model => Object.fromEntries([...graph.httpCallOrder(model)].map(([id, order]) => [id, order.label]));
function freeze(value) {
  if (value && typeof value === "object") { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}

test("a captured service chain increments once per HTTP call without requiring synchronized clocks", () => {
  const f = fixture(), app = f.segment("app"), go = f.segment("go"), ledger = f.segment("ledger");
  f.model.roots.push(app.id);
  f.call("a", app, go, 900000); f.call("b", go, ledger, 10); f.call("c", ledger, null, 4000);
  assert.deepEqual(labels(f.model), {a: "1", b: "2", c: "3"});
  f.model.nodes.forEach(node => { delete node.startedAtMillis; });
  assert.deepEqual(labels(f.model), {a: "1", b: "2", c: "3"}, "causal parentage alone orders a single chain");
});

test("sequential sibling HTTP calls follow completed caller-local intervals, not IDs or input order", () => {
  const f = fixture(), app = f.segment("app"), go = f.segment("go");
  f.call("root", app, go, 500000);
  f.call("z-first", go, null, 100); f.call("a-second", go, null, 200); f.call("m-third", go, null, 300);
  assert.deepEqual(labels(f.model), {"a-second": "3", "m-third": "4", root: "1", "z-first": "2"});
  assert.deepEqual(labels({...f.model, nodes: [...f.model.nodes].reverse(), edges: [...f.model.edges].reverse()}), labels(f.model));
});

test("decimal branches require positive same-invocation overlap and do not encode completion order", () => {
  const f = fixture(), app = f.segment("app");
  f.call("slow", app, null, 100, 100); f.call("fast", app, null, 110, 10); f.call("later", app, null, 220);
  assert.deepEqual(labels(f.model), {fast: "1.2", later: "2", slow: "1.1"});
  assert.match(graph.httpCallOrder(f.model).get("slow").description, /parallel in-flight.*not completion order/);
});

test("simultaneous recorded starts get deterministic branch identities only with overlap evidence", () => {
  const f = fixture(), app = f.segment("app");
  f.call("b", app, null, 100, 30); f.call("a", app, null, 100, 20);
  assert.deepEqual(labels(f.model), {a: "1.1", b: "1.2"});
  assert.deepEqual(labels({...f.model, nodes: [...f.model.nodes].reverse(), edges: [...f.model.edges].reverse()}), {a: "1.1", b: "1.2"});
});

test("siblings, missing durations, active calls and millisecond-boundary ties never establish parallelism", () => {
  for (const [name, first, second] of [
    ["missing timing", {}, {}],
    ["missing duration", {startedAtMillis: 100, durationMs: null}, {startedAtMillis: 101}],
    ["active", {startedAtMillis: 100, status: "ACTIVE"}, {startedAtMillis: 101}],
    ["submillisecond overlap", {startedAtMillis: 100, durationMs: 1.2}, {startedAtMillis: 101}],
    ["touching boundaries", {startedAtMillis: 100, durationMs: 10}, {startedAtMillis: 110}],
    ["fractional start", {startedAtMillis: 100.5}, {startedAtMillis: 101}],
    ["negative duration", {startedAtMillis: 100, durationMs: -2}, {startedAtMillis: 101}],
  ]) {
    const f = fixture(), app = f.segment("app");
    f.call("a", app, null, undefined, 10, first); f.call("b", app, null, undefined, 10, second);
    assert.deepEqual(labels(f.model), {a: "?", b: "?"}, name);
  }
});

test("unrelated callers with identical clocks are incomparable, not a manufactured parallel group", () => {
  const f = fixture(), app = f.segment("app"), other = f.segment("other");
  f.call("a", app, null, 100, 50); f.call("b", other, null, 100, 50);
  assert.deepEqual(labels(f.model), {a: "?", b: "?"});
});

test("a descendant and its ancestor's next sibling call do not acquire a false global order", () => {
  const f = fixture(), app = f.segment("app"), child = f.segment("child"), other = f.segment("other");
  f.call("a", app, child, 100, 10); f.call("b", app, other, 200, 10);
  f.call("nested", child, null, 1500000, 5); f.call("later", other, null, 5, 2);
  assert.deepEqual(labels(f.model), {a: "1", b: "?", later: "?", nested: "?"});
});

test("same named service invocations keep independent local clocks and cannot share an ordering scope", () => {
  const f = fixture(), first = f.segment("first", "app"), second = f.segment("second", "app");
  f.call("a", first, null, 100, 50); f.call("b", second, null, 110, 20);
  assert.deepEqual(labels(f.model), {a: "?", b: "?"});
});

test("malformed sibling ownership does not leave an apparently precise remaining call sequence", () => {
  const f = fixture(), app = f.segment("app");
  f.call("valid", app, null, 200); f.call("invalid", app, null, 100, 10, {segmentId: "wrong"});
  assert.deepEqual(labels(f.model), {valid: "?"});
});

test("ambiguous and cyclic captured topology never obtains a numbered linear execution", () => {
  const f = fixture(), app = f.segment("app"), child = f.segment("child");
  f.call("a", app, child, 100); f.call("b", child, app, 200);
  assert.deepEqual(labels(f.model), {});
  f.model.edges.push({...f.model.edges[0]});
  assert.deepEqual(labels(f.model), {});
});

test("folded and expanded modes keep one number per logical hop without modifying geometry or evidence", () => {
  const f = fixture(), app = f.segment("app"), go = f.segment("go"), ledger = f.segment("ledger");
  f.call("a", app, go, 100); f.call("b", go, ledger, 5000);
  const before = JSON.stringify(f.model); freeze(f.model);
  const callOrders = graph.httpCallOrder(f.model);
  for (const shown of [f.model, graph.presentation(f.model).model]) {
    const result = graph.layout(shown, {callOrders});
    const numbered = result.edges.filter(edge => edge.callOrder);
    assert.deepEqual(numbered.map(edge => [edge.callId, edge.label]), [["a", "1 · HTTP"], ["b", "2 · HTTP"]]);
    assert.ok(numbered.every(edge => Number.isFinite(edge.labelX) && Number.isFinite(edge.labelY)));
    assert.equal(result.edges.filter(edge => edge.kind === "observed-call" && !edge.foldedCallId).some(edge => edge.callOrder), false);
    const noOrders = graph.layout(shown, {callOrders: new Map()});
    assert.deepEqual(result.nodes, noOrders.nodes);
    assert.deepEqual(result.edges.map(edge => edge.path), noOrders.edges.map(edge => edge.path));
  }
  assert.equal(JSON.stringify(f.model), before);
});

test("grouped SQL observations keep their aggregate arrow unnumbered and do not shift HTTP ordering", () => {
  const f = fixture(), app = f.segment("app"), go = f.segment("go");
  f.call("http", app, go, 100);
  for (let i = 0; i < 2; i++) {
    f.model.nodes.push({id: `sql${i}`, kind: "dependency-call", ownerId: app.id, service: app.service,
      segmentId: app.segmentId, segmentKey: app.segmentKey, serviceInstanceId: app.serviceInstanceId,
      callKey: `sql-key${i}`, boundaryKind: "database", system: "postgresql", direction: "outbound",
      evidenceKind: "jdbc-execution", operation: "executeQuery", sqlSource: "statement",
      status: "OBSERVED", eventSeq: i + 1, durationMs: 1, sql: "SELECT 1"});
    f.model.edges.push({id: `sql-edge${i}`, from: app.id, to: `sql${i}`, kind: "outbound"});
  }
  const callOrders = graph.httpCallOrder(f.model);
  const grouped = graph.groupDatabaseCalls(graph.presentation(f.model).model);
  assert.equal(grouped.groups.length, 1);
  const result = graph.layout(grouped.model, {callOrders});
  const sql = result.edges.find(edge => edge.category === "database");
  assert.equal(sql.callOrder, undefined); assert.equal(sql.label, "DB call");
  const http = result.edges.find(edge => edge.callOrder);
  assert.equal(http.label, "1 · HTTP");
  assert.ok(Number.isFinite(http.labelX) && Number.isFinite(http.labelY), "a long folded HTTP connector still receives a label");
});

test("order analysis remains bounded for very large captured call sets", () => {
  const f = fixture(), app = f.segment("app");
  for (let i = 0; i <= graph.limits.edges; i++) f.call(`call${i}`, app, null, i * 100);
  const orders = graph.httpCallOrder(f.model);
  assert.equal(orders.size, graph.limits.edges + 1);
  assert.ok([...orders.values()].every(order => order.label === "?" && /limit reached/.test(order.description)));
});

test("an unrelated edge cannot borrow another HTTP call's order through a forged folded ID", () => {
  const f = fixture(), app = f.segment("app"), go = f.segment("go"), other = f.segment("other");
  f.call("http", app, go, 100);
  const callOrders = graph.httpCallOrder(f.model);
  const shown = graph.presentation(f.model).model;
  shown.edges.push({id: "unrelated", from: "app", to: other.id, kind: "observed-call", foldedCallId: "http"});
  assert.equal(graph.layout(shown, {callOrders}).edges.find(edge => edge.id === "unrelated").callOrder, undefined);
});
