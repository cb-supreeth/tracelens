const test = require("node:test");
const assert = require("node:assert/strict");
const deps = require("../tracelens_cli/web/dependency-ui.js");
const graph = require("../tracelens_cli/web/trace-graph.js");
const source = require("../tracelens_cli/web/boundary-source.js");
const native = extra => ({boundaryKind: "database", system: "clickhouse", direction: "outbound", operation: "query",
  evidenceKind: "clickhouse-query", status: "OBSERVED", sqlSource: "native-query", sql: "SELECT * FROM usage WHERE site = {site:String}",
  durationMs: 12, completionKind: "response-complete", clientThread: "worker-1", ...extra});
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; }
  append(...items) { this.children.push(...items); }
  setAttribute() {}
  set textContent(value) { this.text = value; }
  get textContent() { return (this.text || "") + this.children.map(node => node.textContent).join(" "); }
  set innerHTML(_) { throw Error("SQL must be inert text"); }
}
test("only the native ClickHouse contract retains SQL and bounded named bindings", () => {
  const raw = native({sqlParametersStatus: "captured", sqlParameters: JSON.stringify([{name: "site", type: "String", status: "captured", value: "<script>private</script>"}])});
  const result = deps.boundaryMetadata({type: "EXTERNAL_CALL", attributes: raw});
  assert.equal(result.sql, raw.sql); assert.equal(result.sqlParameters[0].name, "site");
  assert.equal(result.completionKind, "response-complete"); assert.equal(result.clientThread, "worker-1");
  assert.equal(deps.sqlTitle(result), "SELECT · usage");
  assert.doesNotMatch(JSON.stringify(deps.collect({events: [{type: "EXTERNAL_CALL", attributes: raw}]})), /private|SELECT/);
  for (const change of [{evidenceKind: "client-boundary"}, {operation: "insert"}, {status: "ATTEMPTED"}, {system: "postgresql"}, {direction: "consume"}])
    assert.equal(deps.jdbcDetails({...raw, ...change}), null);
  for (const bindings of [[{index: 1, type: "String", value: "secret", status: "captured"}], [{name: "x", type: "Object", value: "secret", status: "unsupported"}]]) {
    const projection = deps.jdbcDetails(native({sqlParametersStatus: "captured", sqlParameters: bindings}));
    assert.equal(projection.sqlParametersStatus, "unavailable"); assert.doesNotMatch(JSON.stringify(projection), /secret/);
  }
});
test("native SQL panel names future-completion timing without JDBC substitution or result claims", () => {
  const panel = deps.renderJdbc({createElement: tag => new Element(tag)}, native({sqlParametersStatus: "captured",
    sqlParameters: [{name: "site", type: "String", status: "captured", value: "<img src=x>"}]}));
  assert.match(panel.textContent, /Native ClickHouse query/); assert.match(panel.textContent, /response-future completion/);
  assert.match(panel.textContent, /Recorded SQL/); assert.match(panel.textContent, /site · String/);
  assert.match(panel.textContent, /\{site:String\}/); assert.doesNotMatch(panel.textContent, /JDBC|SQL with captured values/);
});
test("native queries form a DB call group and capture limits are not counted as executions", () => {
  const owner = {id: "ems", kind: "segment", service: "cb-ems", segmentId: "s", serviceInstanceId: "i"};
  const queries = [1, 2, 3].map(index => ({...native(), id: `q${index}`, kind: "dependency-call", ownerId: "ems",
    service: "cb-ems", segmentId: "s", serviceInstanceId: "i", callKey: `call${index}`, eventSeq: index,
    ...(index === 3 ? {evidenceKind: "clickhouse-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 2} : {})}));
  const input = {nodes: [owner, ...queries], edges: queries.map(node => ({id: node.id, from: "ems", to: node.id, kind: "outbound"})), roots: ["ems"]};
  const grouped = graph.groupDatabaseCalls(input);
  assert.equal(grouped.groups.length, 1); assert.equal(grouped.model.nodes[1].sqlExecutionCount, 2);
  assert.equal(grouped.model.nodes[1].sqlLimitReached, true); assert.equal(input.nodes.length, 4);
});
test("native completion cannot borrow a callback thread's unrelated source frame", () => {
  const events = [{seq: 1, type: "METHOD_ENTER", thread: "callback", className: "sample.Other", methodName: "run"},
    {seq: 2, type: "LINE", thread: "callback", className: "sample.Other", line: 42},
    {seq: 3, type: "EXTERNAL_CALL", thread: "callback", attributes: native()}];
  const resolved = source.resolve({events}, {kind: "dependency-call", eventSeq: 3, ...deps.boundaryMetadata(events[2])});
  assert.equal(resolved.reason, "native-async-completion"); assert.equal(resolved.sourceIndex, null); assert.equal(resolved.eventIndex, 2);
});
test("native submission-only evidence never claims completion or duration", () => {
  const raw = native({status: "SUBMITTED", completionKind: "capture-capacity", durationMs: 42});
  const projected = deps.jdbcDetails(raw); assert.equal(projected.durationMs, null); assert.equal(projected.completionKind, "capture-capacity");
  const panel = deps.renderJdbc({createElement: tag => new Element(tag)}, raw);
  assert.match(panel.textContent, /Submitted · completion not observed/);
  assert.doesNotMatch(panel.textContent, /Response future completed|42 ms|Execution returned/);
  const owner = {id: "ems", kind: "segment", service: "cb-ems", segmentId: "s", serviceInstanceId: "i"};
  const node = {...raw, id: "q1", kind: "dependency-call", ownerId: "ems", service: "cb-ems", segmentId: "s", serviceInstanceId: "i", callKey: "call1", eventSeq: 1};
  const grouped = graph.groupDatabaseCalls({nodes: [owner, node], edges: [{id: "link", from: "ems", to: "q1", kind: "outbound"}], roots: ["ems"]});
  assert.equal(grouped.model.nodes[1].status, "SUBMITTED"); assert.equal(grouped.model.nodes[1].sqlPendingCount, 1);
  assert.equal(grouped.model.nodes[1].sqlErrorCount, 0);
});
