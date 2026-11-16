const test = require("node:test");
const assert = require("node:assert/strict");
const graph = require("../tracelens_cli/web/trace-graph.js");

const owner = (id = "ems", extra = {}) => ({id, kind: "segment", service: "cb-ems",
  serviceInstanceId: "ems-instance", segmentId: "ems-segment", segmentKey: "ems-key", ...extra});
const sql = (id, extra = {}) => ({id, kind: "dependency-call", ownerId: "ems", service: "cb-ems",
  serviceInstanceId: "ems-instance", segmentId: "ems-segment", segmentKey: "ems-key", boundaryKind: "database",
  system: "postgresql", direction: "outbound", evidenceKind: "jdbc-execution", operation: "executeQuery",
  status: "OBSERVED", sql: "select * from features where id = ?", sqlSource: "prepared", sqlTruncated: false,
  durationMs: 3, eventSeq: 1, thread: "ems-worker", callKey: `call:${id}`, ...extra});
const link = (node, extra = {}) => ({id: `edge:${node.id}`, from: node.ownerId, to: node.id, kind: "outbound", ...extra});
const capture = (queries = [sql("late", {eventSeq: 20}), sql("early", {eventSeq: 3})]) => ({schemaVersion: 1,
  kind: "request-flow", nodes: [owner(), ...queries], edges: queries.map(node => link(node)), roots: ["ems"],
  coverage: {complete: false, warnings: ["captured evidence only"]}});
const freeze = value => {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.values(value).forEach(freeze); Object.freeze(value);
  }
  return value;
};
const clickhouseClient = (id, extra = {}) => ({id, kind: "dependency-call", ownerId: "ems", service: "cb-ems",
  serviceInstanceId: "ems-instance", segmentId: "ems-segment", segmentKey: "ems-key", boundaryKind: "database",
  system: "clickhouse", direction: "outbound", evidenceKind: "client-boundary", operation: "queryRecords",
  status: "SUBMITTED", destination: null, destinationName: null, downstreamState: "not-observed",
  eventSeq: 1, thread: "ems-worker", callKey: `call:${id}`, ...extra});

test("SQL group is presentation-only, preserves raw identities and strips representative SQL details", () => {
  const input = capture(), before = JSON.stringify(input); freeze(input);
  const result = graph.groupDatabaseCalls(input);
  assert.equal(result.groups.length, 1);
  assert.deepEqual(result.groups[0].nodes, [input.nodes[2], input.nodes[1]]);
  assert.equal(result.groups[0].nodes[0], input.nodes[2]);
  assert.equal(result.groups[0].nodes[1], input.nodes[1]);
  assert.equal(result.groups[0].id, "early"); assert.equal(result.groups[0].ownerId, "ems");
  const summary = result.model.nodes.find(node => node.id === "early");
  for (const key of ["ownerId", "service", "serviceInstanceId", "segmentId", "segmentKey", "system", "direction"]) {
    assert.equal(summary[key], input.nodes[2][key], key);
  }
  assert.equal(summary.kind, "dependency-call"); assert.equal(summary.boundaryKind, "database");
  assert.equal(summary.presentationKind, "jdbc-group"); assert.equal(summary.evidenceKind, "jdbc-group");
  assert.equal(summary.sqlExecutionCount, 2); assert.equal(summary.sqlErrorCount, 0);
  assert.equal(summary.sqlLimitReached, false); assert.equal(summary.sqlTextTruncated, false);
  assert.equal(summary.status, "OBSERVED");
  for (const key of ["sql", "sqlSource", "sqlTruncated", "batchSize", "queryLimit", "operation", "eventSeq", "thread", "callKey", "durationMs", "errorType"]) {
    assert.ok(summary[key] == null, `group cannot imply a first-query ${key}`);
  }
  assert.equal(result.model.nodes[0], input.nodes[0]);
  assert.equal(result.model.edges.length, 1); assert.equal(result.model.edges[0], input.edges[1]);
  assert.equal(result.model.coverage, input.coverage); assert.equal(result.model.groups, undefined);
  assert.equal(JSON.stringify(input), before);
});

test("event ordering is numeric, then ID, with missing coordinates last and independent of input order", () => {
  const input = capture([sql("z", {eventSeq: "12"}), sql("b", {eventSeq: 2}), sql("a", {eventSeq: "2"}),
    sql("missing-z", {eventSeq: null}), sql("missing-a", {eventSeq: -1})]);
  const ordered = value => graph.groupDatabaseCalls(value).groups.map(group => ({id: group.id, nodes: group.nodes.map(node => node.id)}));
  assert.deepEqual(ordered(input), [{id: "a", nodes: ["a", "b", "z", "missing-a", "missing-z"]}]);
  assert.deepEqual(ordered({...input, nodes: [...input.nodes].reverse(), edges: [...input.edges].reverse()}), ordered(input));
});

test("singleton queries use the same DB-call group model and are idempotent", () => {
  const result = graph.groupDatabaseCalls(capture([sql("one")]));
  assert.equal(result.groups.length, 1); assert.equal(result.groups[0].nodes.length, 1);
  assert.equal(result.model.nodes[1].sqlExecutionCount, 1);
  const again = graph.groupDatabaseCalls(result.model);
  assert.equal(again.model, result.model); assert.deepEqual(again.groups, []);
});

test("summary counts executions, not SQL text, batch operations or capture-limit markers", () => {
  const input = capture([sql("plain"), sql("batch", {eventSeq: 2, operation: "executeBatch", sqlSource: "batch", batchSize: 100}),
    sql("failed", {eventSeq: 3, status: "ERROR", errorType: "java.sql.SQLException", sqlTruncated: "true"}),
    sql("limit", {eventSeq: 4, evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 100})]);
  const result = graph.groupDatabaseCalls(input), summary = result.model.nodes[1];
  assert.equal(summary.sqlExecutionCount, 3); assert.equal(summary.sqlErrorCount, 1);
  assert.equal(summary.sqlLimitReached, true); assert.equal(summary.sqlTextTruncated, true); assert.equal(summary.status, "ERROR");
  assert.equal(result.groups[0].nodes[3], input.nodes[4]);
  const limited = graph.groupDatabaseCalls(capture([input.nodes[4]])).model.nodes[1];
  assert.equal(limited.status, "TRUNCATED"); assert.equal(limited.sqlExecutionCount, 0); assert.equal(limited.sqlLimitReached, true);
});

test("SQL text over the display bound surfaces truncation without changing raw evidence", () => {
  const query = sql("large", {sql: "x".repeat(16385)}), result = graph.groupDatabaseCalls(capture([query]));
  assert.equal(result.model.nodes[1].sqlTextTruncated, true);
  assert.equal(result.groups[0].nodes[0].sql.length, 16385);
});

test("system and destination boundaries never merge or infer missing destinations", () => {
  const queries = [sql("unknown"), sql("mysql", {system: "mysql"}), sql("primary", {destination: "primary"}),
    sql("replica", {destination: "replica"}), sql("named", {destination: "primary", destinationName: "orders"}),
    sql("other-name", {destination: "primary", destinationName: "billing"}), sql("empty", {destination: ""})];
  const result = graph.groupDatabaseCalls(capture(queries));
  assert.equal(result.groups.length, queries.length);
  for (const group of result.groups) assert.equal(group.nodes.length, 1);
  const named = result.model.nodes.find(node => node.id === "named");
  assert.equal(named.destination, "primary"); assert.equal(named.destinationName, "orders");
});

test("separate owning service invocations never merge even with identical SQL and destinations", () => {
  const input = capture([sql("ems-query")]);
  const app = owner("app", {service: "cb-app", serviceInstanceId: "app-instance", segmentId: "app-segment", segmentKey: "app-key"});
  const appSql = sql("app-query", {...app, id: "app-query", kind: "dependency-call", ownerId: "app"});
  const repeat = owner("ems-again", {segmentId: "ems-next", segmentKey: "ems-next-key"});
  const repeatedSql = sql("repeated-query", {...repeat, id: "repeated-query", kind: "dependency-call", ownerId: "ems-again"});
  input.nodes.push(app, appSql, repeat, repeatedSql); input.edges.push(link(appSql), link(repeatedSql));
  const result = graph.groupDatabaseCalls(input);
  assert.equal(result.groups.length, 3); assert.deepEqual(new Set(result.groups.map(group => group.ownerId)), new Set(["app", "ems", "ems-again"]));
});

test("missing optional identity never merges different recorded instances or services", () => {
  const input = capture([sql("one", {serviceInstanceId: "one"}), sql("two", {serviceInstanceId: "two"})]);
  delete input.nodes[0].serviceInstanceId;
  assert.equal(graph.groupDatabaseCalls(input).groups.length, 2);
  input.nodes[1].service = "one-service"; input.nodes[2].service = "two-service"; delete input.nodes[0].service;
  assert.equal(graph.groupDatabaseCalls(input).groups.length, 2);
});

test("DB routing, cache and unrecognized database boundaries remain separate raw nodes", () => {
  const route = sql("route", {boundaryKind: "database-route", system: "database", evidenceKind: "routing-marker", operation: "route"});
  const cache = sql("cache", {boundaryKind: "cache", system: "redis", evidenceKind: "client-boundary", operation: "get"});
  const external = sql("external", {system: "clickhouse", evidenceKind: "client-boundary"});
  const unknown = sql("unknown", {evidenceKind: "future-sql", operation: "executeMystery"});
  const input = capture([sql("query"), route, cache, external, unknown]), result = graph.groupDatabaseCalls(input);
  assert.equal(result.groups.length, 1);
  for (const raw of [route, cache, external, unknown]) assert.equal(result.model.nodes.find(node => node.id === raw.id), raw);
});

test("unsafe individual query topology or identity stays raw without hiding other valid evidence", () => {
  const scenarios = [
    ["missing owner", value => { delete value.nodes[1].ownerId; }],
    ["wrong owner", value => { value.nodes[1].ownerId = "early"; }],
    ["wrong edge direction", value => { value.edges[0].kind = "inbound"; }],
    ["missing edge", value => { value.edges.shift(); }],
    ["parallel ambiguous edge", value => { value.edges.push({...value.edges[0], id: "duplicate-link"}); }],
    ["query is root", value => { value.roots.push("late"); }],
    ["different service", value => { value.nodes[1].service = "cb-app"; }],
    ["different instance", value => { value.nodes[1].serviceInstanceId = "other"; }],
    ["different segment", value => { value.nodes[1].segmentId = "other"; }],
    ["different segment key", value => { value.nodes[1].segmentKey = "other"; }],
    ["malformed identity", value => { value.nodes[1].service = {}; }],
    ["malformed call key", value => { value.nodes[1].callKey = 0; }],
    ["non-string destination", value => { value.nodes[1].destination = {}; }],
    ["oversized destination", value => { value.nodes[1].destination = "d".repeat(4097); }],
    ["remote execution assertion", value => { value.nodes[1].downstreamState = "observed"; }],
    ["invalid JDBC operation", value => { value.nodes[1].operation = "close"; }],
    ["invalid JDBC status", value => { value.nodes[1].status = "COMPLETED"; }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capture(); mutate(input); const result = graph.groupDatabaseCalls(input);
    assert.equal(result.groups.length, 1, name); assert.deepEqual(result.groups[0].nodes.map(node => node.id), ["early"], name);
    assert.equal(result.model.nodes.find(node => node.id === "late"), input.nodes[1], name);
  }
});

test("non-leaf queries and duplicate call keys are not collapsed", () => {
  const linked = capture(); linked.edges.push({id: "query-to-query", from: "late", to: "early", kind: "observed-call"});
  assert.equal(graph.groupDatabaseCalls(linked).model, linked);
  const duplicate = capture(); duplicate.nodes[1].callKey = duplicate.nodes[2].callKey;
  assert.equal(graph.groupDatabaseCalls(duplicate).model, duplicate);
});

test("cycles and their downstream SQL remain unchanged", () => {
  const input = capture(); input.nodes.push(owner("cycle"));
  input.edges.push({id: "cycle-in", from: "ems", to: "cycle", kind: "observed-call"},
    {id: "cycle-out", from: "cycle", to: "ems", kind: "observed-call"});
  assert.equal(graph.groupDatabaseCalls(input).model, input);
  const self = capture(); self.edges.push({id: "self", from: "late", to: "late", kind: "outbound"});
  const result = graph.groupDatabaseCalls(self);
  assert.deepEqual(result.groups[0].nodes.map(node => node.id), ["early"]);
  assert.equal(result.model.nodes.find(node => node.id === "late"), self.nodes[1]);
});

test("malformed graph topology is never used as a basis for grouping", () => {
  const changes = [value => value.nodes.push({...value.nodes[1]}), value => value.nodes.push(null),
    value => value.edges.push({...value.edges[0]}), value => { value.edges[0].from = "missing"; },
    value => { value.edges[0].kind = "guessed"; }, value => { value.edges[0].id = ""; },
    value => { value.roots = ["missing"]; }, value => { value.roots = "ems"; }];
  for (const mutate of changes) {
    const input = capture(); mutate(input);
    const result = graph.groupDatabaseCalls(input); assert.equal(result.model, input); assert.deepEqual(result.groups, []);
  }
  for (const input of [null, undefined, {}, {nodes: []}, {nodes: [], edges: null}]) {
    assert.equal(graph.groupDatabaseCalls(input).model, input);
  }
});

test("grouping occurs before the visual node cap and remains bounded by the input safety caps", () => {
  const input = capture(Array.from({length: 1000}, (_, i) => sql(`sql-${i}`, {eventSeq: i}))), before = JSON.stringify(input);
  const result = graph.groupDatabaseCalls(input), rendered = graph.layout(result.model);
  assert.equal(result.groups[0].nodes.length, 1000); assert.equal(result.model.nodes.length, 2);
  assert.equal(rendered.nodes.length, 2); assert.equal(rendered.omittedNodes, 0);
  assert.equal(JSON.stringify(input), before);
  const atCap = capture(Array.from({length: graph.limits.inputNodes - 1}, (_, i) => sql(`at-${i}`, {eventSeq: i})));
  assert.equal(graph.groupDatabaseCalls(atCap).groups[0].nodes.length, graph.limits.inputNodes - 1);
  const overNodes = {...atCap, nodes: [...atCap.nodes, owner("excess")]};
  assert.equal(graph.groupDatabaseCalls(overNodes).model, overNodes);
  const overEdges = capture(); overEdges.edges = Array.from({length: graph.limits.inputEdges + 1}, (_, i) => ({...overEdges.edges[0], id: `edge-${i}`}));
  assert.equal(graph.groupDatabaseCalls(overEdges).model, overEdges);
});

test("legacy ClickHouse observations group without inventing SQL executions or a database destination", () => {
  const queries = [clickhouseClient("schema", {operation: "getTableSchemaFromQuery", status: "OBSERVED", eventSeq: 1131}),
    clickhouseClient("second-query", {eventSeq: 1138}), clickhouseClient("first-query", {eventSeq: 157})];
  const input = capture(queries), before = JSON.stringify(input); freeze(input);
  const result = graph.groupDatabaseCalls(input), summary = result.model.nodes.find(node => node.id === "first-query");
  assert.equal(result.groups.length, 1); assert.equal(result.model.nodes.length, 2);
  assert.deepEqual(result.groups[0].nodes.map(node => node.id), ["first-query", "schema", "second-query"]);
  assert.equal(result.groups[0].nodes[0], queries[2]);
  assert.equal(result.groups[0].nodes[1], queries[0]);
  assert.equal(result.groups[0].nodes[2], queries[1]);
  assert.equal(summary.system, "clickhouse"); assert.equal(summary.destination, null); assert.equal(summary.destinationName, null);
  assert.equal(summary.clientObservationCount, 3); assert.equal(summary.clientSubmittedCount, 2); assert.equal(summary.clientErrorCount, 0);
  assert.equal(summary.sqlExecutionCount, 0); assert.equal(summary.sqlPendingCount, 0); assert.equal(summary.sqlErrorCount, 0);
  assert.equal(summary.status, "SUBMITTED"); assert.equal(summary.sqlLimitReached, false);
  for (const key of ["sql", "sqlSource", "sqlParameters", "durationMs", "eventSeq", "thread", "callKey", "operation"]) {
    assert.ok(summary[key] == null, `group cannot imply a representative ${key}`);
  }
  assert.equal(result.model.edges.length, 1); assert.equal(result.model.edges[0], input.edges[2]);
  assert.equal(result.model.coverage, input.coverage); assert.equal(JSON.stringify(input), before);
  const again = graph.groupDatabaseCalls(result.model);
  assert.equal(again.model, result.model); assert.deepEqual(again.groups, []);
});

test("recognized ClickHouse client operations retain their own evidence and exact status counts", () => {
  const operations = ["query", "queryRecords", "execute", "insert", "getTableSchema", "getTableSchemaFromQuery"];
  const queries = operations.map((operation, index) => clickhouseClient(`operation-${index}`, {
    operation, eventSeq: index + 1, status: ["OBSERVED", "SUBMITTED", "ERROR"][index % 3]}));
  const input = capture(queries), result = graph.groupDatabaseCalls(input), summary = result.model.nodes[1];
  assert.equal(result.groups.length, 1); assert.equal(summary.clientObservationCount, 6);
  assert.equal(summary.clientSubmittedCount, 2); assert.equal(summary.clientErrorCount, 2);
  assert.equal(summary.sqlExecutionCount, 0); assert.equal(summary.sqlErrorCount, 0); assert.equal(summary.status, "ERROR");
  assert.deepEqual(result.groups[0].nodes, queries);
  for (let i = 0; i < queries.length; i++) assert.equal(result.groups[0].nodes[i], queries[i]);
});

test("mixed native SQL and client observations share a destination card but never share execution counts", () => {
  const input = capture([
    clickhouseClient("client-submitted", {eventSeq: 1, sql: "must not be treated as query evidence", sqlTruncated: true}),
    sql("native-complete", {system: "clickhouse", evidenceKind: "clickhouse-query", operation: "query", sqlSource: "native-query", eventSeq: 2}),
    clickhouseClient("client-failed", {status: "ERROR", eventSeq: 3}),
    sql("native-submitted", {system: "clickhouse", evidenceKind: "clickhouse-query", operation: "query", status: "SUBMITTED", sqlSource: "native-query", eventSeq: 4}),
    clickhouseClient("client-observed", {operation: "getTableSchema", status: "OBSERVED", eventSeq: 5}),
    sql("native-limit", {system: "clickhouse", evidenceKind: "clickhouse-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 100, eventSeq: 6}),
  ]);
  const result = graph.groupDatabaseCalls(input), summary = result.model.nodes[1];
  assert.equal(result.groups.length, 1); assert.equal(result.groups[0].nodes.length, 6);
  assert.equal(summary.clientObservationCount, 3); assert.equal(summary.clientSubmittedCount, 1); assert.equal(summary.clientErrorCount, 1);
  assert.equal(summary.sqlExecutionCount, 2); assert.equal(summary.sqlPendingCount, 1); assert.equal(summary.sqlErrorCount, 0);
  assert.equal(summary.sqlLimitReached, true); assert.equal(summary.sqlTextTruncated, false);
  assert.equal(summary.status, "ERROR"); assert.ok(summary.sql == null);
});

test("ClickHouse observation groups keep different recorded destinations and aliases separate", () => {
  const queries = [clickhouseClient("unknown"), clickhouseClient("unknown-again"),
    clickhouseClient("primary", {destination: "clickhouse-primary:8123"}),
    clickhouseClient("replica", {destination: "clickhouse-replica:8123"}),
    clickhouseClient("named", {destination: "clickhouse-primary:8123", destinationName: "analytics"}),
    clickhouseClient("other-name", {destination: "clickhouse-primary:8123", destinationName: "billing"})];
  const result = graph.groupDatabaseCalls(capture(queries));
  assert.equal(result.groups.length, 5);
  assert.deepEqual(result.groups.find(group => group.nodes.length === 2).nodes.map(node => node.id), ["unknown", "unknown-again"]);
  assert.equal(result.model.nodes.find(node => node.id === "unknown").destination, null);
  assert.equal(result.model.nodes.find(node => node.id === "primary").destination, "clickhouse-primary:8123");
});

test("ClickHouse observations never merge across explicit owners or unknown identity fields", () => {
  const input = capture([clickhouseClient("first")]);
  const repeated = owner("ems-again", {segmentId: "next-segment", segmentKey: "next-key"});
  const repeatedCall = clickhouseClient("second", {ownerId: repeated.id, segmentId: repeated.segmentId, segmentKey: repeated.segmentKey});
  input.nodes.push(repeated, repeatedCall); input.edges.push(link(repeatedCall));
  const result = graph.groupDatabaseCalls(input);
  assert.equal(result.groups.length, 2); assert.deepEqual(new Set(result.groups.map(group => group.ownerId)), new Set(["ems", "ems-again"]));
  const uncertain = capture([clickhouseClient("recorded"), clickhouseClient("missing", {serviceInstanceId: null})]);
  assert.equal(graph.groupDatabaseCalls(uncertain).groups.length, 2, "unknown identity does not silently equal recorded identity");
});

test("unrecognized or inconsistent ClickHouse client metadata remains raw", () => {
  const scenarios = [
    ["unknown operation", {operation: "executeQuery"}], ["missing operation", {operation: null}],
    ["unexpected status", {status: "COMPLETED"}], ["missing status", {status: null}],
    ["wrong direction", {direction: "inbound"}], ["wrong system", {system: "postgresql"}],
    ["wrong boundary", {boundaryKind: "external"}], ["wrong evidence", {evidenceKind: "wrapper-entry"}],
    ["wrong service", {service: "cb-app"}], ["wrong instance", {serviceInstanceId: "other"}],
    ["wrong segment", {segmentId: "other"}], ["wrong segment key", {segmentKey: "other"}],
    ["invalid destination", {destination: {host: "unknown"}}], ["oversized destination", {destination: "d".repeat(4097)}],
    ["remote execution", {downstreamState: "observed"}], ["invalid call key", {callKey: 4}],
  ];
  for (const [name, extra] of scenarios) {
    const bad = clickhouseClient("invalid", extra), input = capture([clickhouseClient("valid"), bad]);
    const result = graph.groupDatabaseCalls(input);
    assert.equal(result.groups.length, 1, name); assert.deepEqual(result.groups[0].nodes.map(node => node.id), ["valid"], name);
    assert.equal(result.model.nodes.find(node => node.id === "invalid"), bad, name);
  }
});

test("unsafe ClickHouse observation topology cannot collapse away recorded calls", () => {
  const scenarios = [
    ["missing owner", input => { delete input.nodes[1].ownerId; }],
    ["wrong owner", input => { input.nodes[1].ownerId = "other-call"; }],
    ["root observation", input => { input.roots.push("first"); }],
    ["missing link", input => { input.edges.shift(); }],
    ["inbound link", input => { input.edges[0].kind = "inbound"; }],
    ["ambiguous links", input => { input.edges.push({...input.edges[0], id: "extra-link"}); }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capture([clickhouseClient("first"), clickhouseClient("other-call")]); mutate(input);
    const result = graph.groupDatabaseCalls(input);
    assert.equal(result.groups.length, 1, name); assert.deepEqual(result.groups[0].nodes.map(node => node.id), ["other-call"], name);
    assert.equal(result.model.nodes.find(node => node.id === "first"), input.nodes[1], name);
  }
  const duplicates = capture([clickhouseClient("first"), clickhouseClient("second", {callKey: "call:first"})]);
  assert.equal(graph.groupDatabaseCalls(duplicates).model, duplicates);
  const cycle = capture([clickhouseClient("first")]); cycle.edges.push({id: "cycle", from: "first", to: "ems", kind: "observed-call"});
  assert.equal(graph.groupDatabaseCalls(cycle).model, cycle);
  const nonLeaf = capture([clickhouseClient("first"), clickhouseClient("second")]);
  nonLeaf.edges.push({id: "client-chain", from: "first", to: "second", kind: "observed-call"});
  assert.equal(graph.groupDatabaseCalls(nonLeaf).model, nonLeaf);
});
