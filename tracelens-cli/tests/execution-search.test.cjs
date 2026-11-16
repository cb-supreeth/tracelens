"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const graph = require("../tracelens_cli/web/execution-graph.js");

const invocation = (id, parentId = null, extra = {}) => ({id, kind: "invocation", laneId: "main", parentId,
  relationship: parentId ? "observed-nesting" : null, className: "example.OrderService", methodName: id, ...extra});
const model = (nodes, extra = {}) => ({nodes, lanes: [{id: "main", threadName: "worker"}, {id: "async", threadName: "worker"}], ...extra});
const ids = result => result.matches.map(item => item.id);

test("literal search matches class, method, escaping/marker exception and boundary destination", () => {
  const input = model([invocation("save"), invocation("failed", "save", {exceptionType: "example.OrderFailure"}),
    {id: "db", kind: "marker", laneId: "main", parentId: "save", relationship: "observed-nesting", eventType: "DB_ROUTE", route: "MASTER_READ"},
    {id: "queue", kind: "marker", laneId: "main", eventType: "EXTERNAL_CALL", boundary: {destination: "orders-v2", system: "sqs", operation: "send"}},
    {id: "exception", kind: "marker", laneId: "main", eventType: "EXCEPTION", exceptionType: "java.lang.IllegalStateException"}]);
  const before = JSON.stringify(input);
  assert.deepEqual(ids(graph.search(input, "ORDERSERVICE SAVE")), ["save"]);
  assert.deepEqual(ids(graph.search(input, "orderfailure")), ["failed"]);
  assert.deepEqual(ids(graph.search(input, "master_read")), ["db"]);
  assert.deepEqual(ids(graph.search(input, "sqs orders-v2")), ["queue"]);
  assert.deepEqual(ids(graph.search(input, "IllegalStateException")), ["exception"]);
  assert.deepEqual(graph.search(input, "OrderService failed").matches[0].fields, ["class", "method"]);
  assert.equal(JSON.stringify(input), before);
});

test("search indexes all available collapsed nodes but neither raw events nor unplaced HTTP", () => {
  const input = model([invocation("root"), invocation("child", "root"), invocation("needle", "child")],
    {events: [{methodName: "raw-only"}], httpCalls: [{destination: "unplaced-only"}], counts: {nodesOmitted: 37}});
  assert.deepEqual(ids(graph.search(input, "needle")), ["needle"]);
  assert.deepEqual(ids(graph.search(input, "raw-only")), []);
  assert.deepEqual(ids(graph.search(input, "unplaced-only")), []);
  assert.equal(graph.search(input, "needle").searchedNodes, 3);
  assert.equal(graph.search(input, "needle").omittedNodes, 37);
});

test("search and focus obey the renderer's node and depth limits", () => {
  const input = model(Array.from({length: 250}, (_, i) => invocation(`node${i}`)));
  assert.equal(graph.search(input, "node").searchedNodes, 200);
  assert.equal(graph.search(input, "node").matches.length, 200);
  assert.equal(graph.search(input, "node").omittedNodes, 50);
  assert.deepEqual(ids(graph.search(input, "node249")), []);
  assert.equal(graph.focusIds(input, "node249").size, 0);
  const deep = model(Array.from({length: 40}, (_, i) => invocation(`deep${i}`, i ? `deep${i - 1}` : null)));
  assert.equal(graph.search(deep, "deep").searchedNodes, 16);
  assert.equal(graph.search(deep, "deep").omittedNodes, 24);
  assert.equal(graph.focusIds(deep, "deep0").size, 16);
});

test("focus preserves ancestors and selected descendants but omits ancestor siblings and unrelated lanes", () => {
  const input = model([invocation("root"), invocation("selected", "root"), invocation("descendant", "selected"),
    invocation("sibling", "root"), invocation("other-lane", "selected", {laneId: "async"})]);
  const before = JSON.stringify(input), includedIds = graph.focusIds(input, "selected");
  assert.deepEqual([...includedIds], ["selected", "root", "descendant"]);
  const focused = graph.layout(input, {includedIds});
  assert.deepEqual(focused.nodes.map(item => item.id), ["root", "selected", "descendant"]);
  assert.deepEqual(focused.edges.map(edge => [edge.from, edge.to]), [["root", "selected"], ["selected", "descendant"]]);
  assert.equal(focused.hiddenNodes, 2);
  assert.equal(JSON.stringify(input), before);
});

test("focus never invents missing, cross-lane, cyclic or untrusted relationships", () => {
  const input = model([invocation("root"), invocation("unsupported", "root", {relationship: "async"}),
    invocation("worker", "root", {laneId: "async"}), invocation("a", "b"), invocation("b", "a"), invocation("child", "a")]);
  assert.deepEqual([...graph.focusIds(input, "unsupported")], ["unsupported"]);
  assert.deepEqual([...graph.focusIds(input, "worker")], ["worker"]);
  assert.deepEqual([...graph.focusIds(input, "a")], ["a", "child"]);
  assert.equal(graph.focusIds(input, "missing").size, 0);
});

test("malformed models and query values are bounded and never become regular expressions", () => {
  for (const value of [null, {}, [], {nodes: "bad"}, model([null, {}, invocation("duplicate"), invocation("duplicate")])]) {
    assert.equal(graph.search(value, "needle").matches.length, 0);
    assert.equal(graph.focusIds(value, "needle").size, 0);
  }
  const unsafe = '<img src=x onerror="alert(1)">', input = model([invocation("safe", null, {methodName: unsafe})]);
  assert.deepEqual(ids(graph.search(input, unsafe)), ["safe"]);
  for (const query of [null, {}, 123, "", "   ", "(a+)+$"]) assert.deepEqual(ids(graph.search(input, query)), []);
  const huge = graph.search(input, "x".repeat(100000));
  assert.equal(huge.query.length, 256); assert.equal(huge.truncated, true);
  assert.equal(graph.search(input, Array(30).fill("x").join(" ")).truncated, true);
});

test("source action requires a concrete class name and bounded positive observed line", () => {
  assert.deepEqual(graph.sourceLocation(invocation("a", null, {observedLines: {first: 42}})), {className: "example.OrderService", line: 42});
  assert.deepEqual(graph.sourceLocation(invocation("a", null, {className: "example.Order$Nested", observedLines: {first: 1}})), {className: "example.Order$Nested", line: 1});
  for (const className of ["", "../Private", "<script>", "example/Class", "a b", "A".repeat(257)]) {
    assert.equal(graph.sourceLocation(invocation("a", null, {className, observedLines: {first: 42}})), null);
  }
  for (const first of [null, "42", -1, 0, 1.5, Infinity, 2147483648]) {
    assert.equal(graph.sourceLocation(invocation("a", null, {observedLines: {first}})), null);
  }
});
