const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const model = require("../tracelens_cli/web/performance-model.js");
const graphModel = require("../tracelens_cli/web/trace-graph.js");

const segment = (id = "segment", extra = {}) => ({id, kind: "segment", service: "orders",
  serviceInstanceId: "instance", segmentId: "request", segmentKey: "request-key", ...extra});
const query = (id, extra = {}) => ({id, kind: "dependency-call", ownerId: "segment", service: "orders",
  serviceInstanceId: "instance", segmentId: "request", segmentKey: "request-key", system: "postgresql",
  destination: "primary", boundaryKind: "database", direction: "outbound", evidenceKind: "jdbc-execution",
  status: "OBSERVED", operation: "executeQuery", sqlSource: "prepared", sqlTruncated: false,
  sql: "SELECT * FROM orders WHERE id = ?", durationMs: 2, thread: "worker", eventSeq: Number(id.replace(/\D/g, "")) || 1,
  callKey: `call:${id}`, ...extra});
const capture = (queries, extra = {}) => ({schemaVersion: 1, kind: "request-flow", nodes: [segment(), ...queries],
  edges: queries.map(node => ({id: `edge:${node.id}`, from: node.ownerId, to: node.id, kind: "outbound"})),
  roots: ["segment"], coverage: {complete: true}, ...extra});
const queries = (count, extra = {}) => Array.from({length: count}, (_, i) => query(`query-${i + 1}`, typeof extra === "function" ? extra(i) : extra));
const findings = (result, kind) => result.findings.filter(finding => finding.kind === kind);
const warning = (result, expression) => assert.ok(result.warnings.some(item => expression.test(item)), result.warnings.join("\n"));
const freeze = value => {
  if (value && typeof value === "object" && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
};
const fingerprint = (sql, extra = {}) => model.analyze(capture([query("one", {sql, durationMs: 100, ...extra})])).findings[0].pattern;
const noFingerprint = (sql, extra = {}) => {
  const result = model.analyze(capture(queries(5, {sql, durationMs: 100, ...extra})));
  assert.ok(findings(result, "slow-query").every(item => item.pattern === null), sql);
  assert.equal(findings(result, "repeated-query").length, 0, sql);
  assert.equal(findings(result, "suspected-n-plus-one").length, 0, sql);
  warning(result, /templates|batch execution/);
};

test("UMD exposes an immutable browser API and does not require a DOM or a network", () => {
  const context = {TraceLensGraph: graphModel}; vm.createContext(context);
  vm.runInContext(fs.readFileSync(require.resolve("../tracelens_cli/web/performance-model.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensPerformanceModel.analyze, "function");
  assert.ok(Object.isFrozen(context.TraceLensPerformanceModel)); assert.ok(Object.isFrozen(model.defaults));
  assert.deepEqual(model.defaults, {slowQueryMs: 100, repeatedQueryCount: 3, nPlusOneCount: 5, queryHeavyCount: 50});
  const absent = {}; vm.createContext(absent);
  vm.runInContext(fs.readFileSync(require.resolve("../tracelens_cli/web/performance-model.js"), "utf8"), absent);
  warning(absent.TraceLensPerformanceModel.analyze(capture(queries(1))), /validator is not loaded/);
});

test("returns a pure evidence result with original navigation boundaries and conservative timing language", () => {
  const input = capture(queries(3, {durationMs: 125})), before = JSON.stringify(input); freeze(input);
  const result = model.analyze(input);
  assert.equal(result.queryCount, 3); assert.equal(result.segmentCount, 1);
  assert.equal(findings(result, "slow-query").length, 3);
  const repeated = findings(result, "repeated-query")[0];
  assert.equal(repeated.count, 3); assert.equal(repeated.ownerId, "segment");
  assert.equal(repeated.service, "orders"); assert.equal(repeated.system, "postgresql"); assert.equal(repeated.destination, "primary");
  assert.equal(repeated.totalDurationMs, 375); assert.equal(repeated.maxDurationMs, 125); assert.equal(repeated.confidence, "observed");
  assert.equal(repeated.nodes[0], input.nodes[1]); assert.equal(repeated.nodes[2], input.nodes[3]);
  assert.match(repeated.explanation, /cumulative individual JDBC/); assert.match(repeated.explanation, /not request latency/);
  assert.equal(JSON.stringify(input), before);
});

test("threshold boundaries and per-analysis overrides are honored without changing defaults", () => {
  const input = capture(queries(3, i => ({durationMs: 99 + i})));
  assert.equal(findings(model.analyze(input), "slow-query").length, 2);
  const result = model.analyze(input, {slowQueryMs: 100.5, repeatedQueryCount: 4, nPlusOneCount: 3, queryHeavyCount: 3});
  assert.equal(findings(result, "slow-query").length, 1);
  assert.equal(findings(result, "suspected-n-plus-one").length, 1);
  assert.equal(findings(result, "repeated-query").length, 0);
  assert.equal(findings(result, "query-heavy").length, 1);
  assert.equal(model.defaults.nPlusOneCount, 5);
  assert.equal(findings(model.analyze(input, {slowQueryMs: 102, repeatedQueryCount: 4, nPlusOneCount: 4, queryHeavyCount: 4}), "slow-query").length, 0);
});

test("invalid thresholds never disable or expand analysis silently", () => {
  for (const invalid of [null, "1", 0, -1, NaN, Infinity, {}, true]) {
    const result = model.analyze(capture([]), Object.fromEntries(Object.keys(model.defaults).map(key => [key, invalid])));
    assert.deepEqual(result.thresholds, model.defaults); warning(result, /Invalid/);
  }
  for (const key of ["repeatedQueryCount", "nPlusOneCount", "queryHeavyCount"]) {
    assert.equal(model.analyze(capture([]), {[key]: 1.5}).thresholds[key], model.defaults[key]);
  }
  assert.equal(model.analyze(capture([]), {nPlusOneCount: 1}).thresholds.nPlusOneCount, 5);
  for (const key of ["repeatedQueryCount", "nPlusOneCount", "queryHeavyCount"]) {
    assert.equal(model.analyze(capture([]), {[key]: 1001}).thresholds[key], model.defaults[key]);
    assert.equal(model.analyze(capture([]), {[key]: 1000}).thresholds[key], 1000);
  }
  for (const slowQueryMs of [.5, 600001]) assert.equal(model.analyze(capture([]), {slowQueryMs}).thresholds.slowQueryMs, 100);
  assert.equal(model.analyze(capture([]), {slowQueryMs: 600000}).thresholds.slowQueryMs, 600000);
});

test("N+1 is a hypothesis requiring same SELECT shape, segment, thread and distinct sequence IDs", () => {
  const input = capture(queries(5, i => ({sql: `select * from orders where id=${i + 1}`})));
  const result = model.analyze(input), item = findings(result, "suspected-n-plus-one")[0];
  assert.equal(item.count, 5); assert.equal(item.confidence, "heuristic");
  assert.equal(findings(result, "repeated-query").length, 0);
  for (const phrase of [/hypothesis/, /pagination/, /retries/, /intentional loop/, /Inspect the calling code/, /not proof/]) assert.match(item.explanation, phrase);
  for (const sql of ["UPDATE orders SET id=?", "DELETE FROM orders WHERE id=?", "INSERT INTO orders(id) VALUES(?)",
    "WITH x AS (SELECT * FROM orders) SELECT * FROM x WHERE id=?", "SELECT 1", "SELECT * FROM orders", "SELECT * FROM orders LIMIT ?"]) {
    const write = model.analyze(capture(queries(5, {sql})));
    assert.equal(findings(write, "suspected-n-plus-one").length, 0, sql);
    assert.equal(findings(write, "repeated-query").length, 1, sql);
  }
});

test("parallel threads cannot combine to satisfy the N+1 threshold", () => {
  const mixed = model.analyze(capture(queries(6, i => ({thread: i % 2 ? "worker-a" : "worker-b"}))));
  assert.equal(findings(mixed, "suspected-n-plus-one").length, 0);
  assert.equal(findings(mixed, "repeated-query")[0].count, 6);
  const twoLoops = model.analyze(capture(queries(10, i => ({thread: i % 2 ? "worker-a" : "worker-b"}))));
  assert.equal(findings(twoLoops, "suspected-n-plus-one").length, 2);
  assert.ok(findings(twoLoops, "suspected-n-plus-one").every(item => new Set(item.nodes.map(node => node.thread)).size === 1));
  assert.equal(findings(twoLoops, "repeated-query").length, 0);
});

test("missing, malformed and duplicate order coordinates do not support N+1", () => {
  for (const extra of [{thread: null}, {thread: ""}, {thread: {}}, {thread: "x".repeat(4097)}, {eventSeq: null},
    {eventSeq: -1}, {eventSeq: 1.5}, {eventSeq: false}, {eventSeq: " "}, {eventSeq: Number.MAX_SAFE_INTEGER + 1}, {eventSeq: 1}]) {
    const result = model.analyze(capture(queries(5, extra)));
    assert.equal(findings(result, "suspected-n-plus-one").length, 0, JSON.stringify(extra));
    assert.equal(findings(result, "repeated-query").length, 1, JSON.stringify(extra));
    warning(result, /ordering is unknown/);
  }
  const strings = model.analyze(capture(queries(5, i => ({eventSeq: String(10 - i)}))));
  assert.deepEqual(findings(strings, "suspected-n-plus-one")[0].nodes.map(node => node.eventSeq), ["6", "7", "8", "9", "10"]);
  const conflicting = model.analyze(capture([...queries(5), query("other-shape", {eventSeq: 1, sql: "SELECT * FROM other WHERE id=?"})]));
  assert.equal(findings(conflicting, "suspected-n-plus-one").length, 0); warning(conflicting, /Duplicate sequence/);
});

test("query-heavy analysis counts executions per owner/system/destination scope, not rows", () => {
  const result = model.analyze(capture(queries(50, {operation: "executeBatch", sqlSource: "batch", batchSize: 1000})));
  assert.equal(result.queryCount, 50); assert.equal(findings(result, "query-heavy").length, 1);
  const item = findings(result, "query-heavy")[0]; assert.equal(item.count, 50); assert.equal(item.pattern, null);
  assert.match(item.explanation, /Each batch counts once/); assert.equal(item.confidence, "heuristic");
  assert.equal(findings(result, "repeated-query").length, 0); assert.equal(findings(result, "suspected-n-plus-one").length, 0);
  assert.equal(findings(model.analyze(capture(queries(49))), "query-heavy").length, 0);
});

test("all supported JDBC execution operations count once, including failed attempts", () => {
  const operations = ["execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"];
  const result = model.analyze(capture(operations.map((operation, i) => query(`operation-${i}`, {operation, durationMs: 200,
    status: i % 2 ? "ERROR" : "OBSERVED", errorType: "java.sql.SQLException"}))));
  assert.equal(result.queryCount, operations.length); assert.equal(findings(result, "slow-query").length, operations.length);
  assert.equal(findings(result, "repeated-query")[0].count, 4);
  assert.ok(findings(result, "slow-query").filter(item => /Batch$/.test(item.nodes[0].operation)).every(item => item.pattern === null));
});

test("batch source, operations and metadata cannot masquerade as repeated individual queries", () => {
  for (const extra of [{operation: "executeBatch"}, {operation: "executeLargeBatch"}, {sqlSource: "batch"},
    {batchSize: 200}, {batchSize: 0}, {sqlParametersStatus: "batch"}, {sqlParameterSets: []}]) {
    const result = model.analyze(capture(queries(5, {...extra, durationMs: 101})));
    assert.equal(result.queryCount, 5); assert.equal(findings(result, "slow-query").length, 5);
    assert.equal(findings(result, "repeated-query").length, 0); assert.equal(findings(result, "suspected-n-plus-one").length, 0);
    warning(result, /batch rows are not queries/);
  }
});

test("route markers, generic boundaries, cache calls and capture limits never become queries", () => {
  const nodes = [query("route", {boundaryKind: "database-route", system: "database", evidenceKind: "routing-marker", operation: "route"}),
    query("cache", {boundaryKind: "cache", system: "redis", evidenceKind: "client-boundary"}),
    query("generic", {evidenceKind: "client-boundary"}),
    query("limit", {evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 100}),
    query("wrapper", {evidenceKind: "wrapper-entry"})];
  const result = model.analyze(capture(nodes), {queryHeavyCount: 1});
  assert.equal(result.queryCount, 0); assert.equal(result.findings.length, 0); warning(result, /capture-limit marker/); warning(result, /unknown/);
});

test("drivers, destinations and destination aliases never merge", () => {
  const scopes = [{system: "postgresql", destination: "primary"}, {system: "mysql", destination: "primary"},
    {destination: "replica"}, {destination: "primary", destinationName: "alias-a"}, {destination: "primary", destinationName: "alias-b"},
    {destination: null}, {destination: ""}];
  const nodes = scopes.flatMap((extra, scope) => queries(3, extra).map((node, i) => ({...node, id: `s${scope}-${i}`, callKey: `s${scope}-${i}`})));
  const result = model.analyze(capture(nodes));
  assert.equal(findings(result, "repeated-query").length, scopes.length);
  assert.ok(findings(result, "repeated-query").every(item => item.count === 3));
  warning(result, /destination is unavailable/);
  for (const item of result.findings.filter(item => !item.destination)) assert.match(item.explanation, /do not establish a single database/);
});

test("separate owning segments and service instances never merge even with equal templates", () => {
  const nodes = queries(3), input = capture(nodes);
  const other = segment("other", {service: "billing", serviceInstanceId: "instance-2", segmentId: "request-2", segmentKey: "key-2"});
  input.nodes.push(other);
  for (const node of queries(3)) {
    const child = {...node, ...other, id: `${node.id}-other`, kind: "dependency-call", ownerId: "other", callKey: `${node.callKey}-other`};
    input.nodes.push(child); input.edges.push({id: `edge:${child.id}`, from: "other", to: child.id, kind: "outbound"});
  }
  const result = model.analyze(input);
  assert.equal(result.queryCount, 6); assert.equal(result.segmentCount, 2);
  assert.deepEqual(new Set(findings(result, "repeated-query").map(item => item.ownerId)), new Set(["segment", "other"]));
  assert.equal(findings(result, "suspected-n-plus-one").length, 0);
});

test("unsupported or inconsistent identity remains unknown and cannot hide valid unrelated calls", () => {
  for (const extra of [{ownerId: null}, {service: "other"}, {serviceInstanceId: "other"}, {segmentId: "other"}, {segmentKey: "other"},
    {system: "clickhouse"}, {system: "oracle"}, {destination: {}}, {service: []}, {destination: "x".repeat(4097)},
    {operation: "close"}, {status: "COMPLETED"}, {downstreamState: "observed"}, {callKey: ""}, {callKey: 0}]) {
    const valid = query("valid", {durationMs: 200}), unsafe = query("unsafe", {...extra, durationMs: 200});
    const input = capture([valid, unsafe]); input.edges[1].from = "segment";
    const result = model.analyze(input);
    assert.equal(result.queryCount, 1, JSON.stringify(extra)); assert.equal(result.findings.length, 1, JSON.stringify(extra));
    assert.equal(result.findings[0].nodes[0], valid); warning(result, /excluded/);
  }
});

test("ambiguous topology, duplicate identities, roots and cycles fail closed", () => {
  const mutations = [
    value => value.nodes.push({...value.nodes[1]}), value => value.nodes.push(null),
    value => value.edges.push({...value.edges[0]}), value => { value.edges[0].from = "missing"; },
    value => { value.edges[0].kind = "guessed"; }, value => { value.roots = ["missing"]; }, value => { value.roots = "segment"; },
    value => { value.nodes[1].callKey = value.nodes[2].callKey; },
    value => value.edges.push({id: "cycle", from: value.nodes[1].id, to: "segment", kind: "observed-call"}),
  ];
  for (const mutate of mutations) {
    const input = capture(queries(2, {durationMs: 200})); mutate(input);
    const result = model.analyze(input); assert.equal(result.findings.length, 0); warning(result, /unknown|incomplete/);
  }
  for (const mutate of [value => { value.edges.shift(); }, value => { value.roots.push(value.nodes[1].id); },
    value => value.edges.push({...value.edges[0], id: "parallel"})]) {
    const input = capture(queries(2, {durationMs: 200})); mutate(input);
    const result = model.analyze(input); assert.equal(result.queryCount, 1); warning(result, /excluded/);
  }
});

test("missing timing is never coerced to zero and incomplete totals are not fabricated", () => {
  for (const invalid of [null, undefined, "", " ", false, {}, [], NaN, Infinity, -1, "-1", "Infinity", "0x100", "1ms"]) {
    const result = model.analyze(capture(queries(3, i => ({durationMs: i ? 2 : invalid}))));
    assert.equal(findings(result, "slow-query").length, 0);
    const repeated = findings(result, "repeated-query")[0];
    assert.equal(repeated.totalDurationMs, null); assert.equal(repeated.maxDurationMs, 2);
    assert.match(repeated.explanation, /timing is unavailable/); warning(result, /timing/);
  }
  const zeros = findings(model.analyze(capture(queries(3, {durationMs: "0"}))), "repeated-query")[0];
  assert.equal(zeros.totalDurationMs, 0); assert.equal(zeros.maxDurationMs, 0);
  const overflow = findings(model.analyze(capture(queries(3, {durationMs: 1e308}))), "repeated-query")[0];
  assert.equal(overflow.totalDurationMs, null); assert.equal(overflow.maxDurationMs, 1e308);
});

test("literal values, comments and captured bindings are absent from normalized patterns", () => {
  const sql = "SELECT * FROM orders /* PASSWORD_SECRET */ WHERE email='alice-secret@example.test' AND id=987654321 AND token=$tag$TOKEN_SECRET$tag$ AND active=TRUE -- COMMENT_SECRET\n";
  const pattern = fingerprint(sql, {sqlParameters: [{value: "PARAM_SECRET"}], sqlParametersStatus: "captured"});
  assert.equal(pattern, "select * from orders where email = ? and id = ? and token = ? and active = ?");
  assert.doesNotMatch(pattern, /SECRET|alice|987654321/);
  const bound = query("bindings", {durationMs: 150});
  Object.defineProperty(bound, "sqlParameters", {get() { throw Error("parameters must never be read"); }});
  const result = model.analyze(capture([bound])); assert.equal(result.findings[0].pattern, "select * from orders where id = ?");
});

test("literal variations normalize equally while shape changes remain distinct", () => {
  const expected = fingerprint("SELECT * FROM orders WHERE id=42 AND email='first'", {sqlSource: "statement"});
  assert.equal(fingerprint(" select  *  from orders WHERE id = 7 AND email = 'second' ; -- hidden", {sqlSource: "statement"}), expected);
  assert.equal(fingerprint("SELECT * FROM orders WHERE id=? AND email=?"), expected);
  assert.notEqual(fingerprint("SELECT * FROM customers WHERE id=? AND email=?"), expected);
  assert.notEqual(fingerprint("SELECT * FROM orders WHERE id=? OR email=?"), expected);
  assert.notEqual(fingerprint("SELECT * FROM orders WHERE id IN (1, 2)"), fingerprint("SELECT * FROM orders WHERE id IN (1)"));
});

test("PostgreSQL quoting, escaped literals, dollar strings, numeric constants and binds are tokenized", () => {
  for (const literal of ["'it''s SECRET'", "E'it\\'s SECRET'", "E'back\\\\slash SECRET'", "$$SECRET '$$", "$тег$SECRET$тег$",
    "N'SECRET'", "B'010101'", "X'CAFE'", "123.45e-6", ".25", "0xCAFE", "0b0101", "$1"]) {
    assert.equal(fingerprint(`SELECT ${literal} FROM orders`), "select ? from orders", literal);
  }
  assert.equal(fingerprint('SELECT "Case", "double""quoted" FROM "Orders" WHERE id=?'), 'select "Case" , "double""quoted" from "Orders" where id = ?');
  assert.notEqual(fingerprint('SELECT * FROM "Orders"'), fingerprint('SELECT * FROM "orders"'));
  assert.notEqual(fingerprint('SELECT * FROM "Orders"'), fingerprint("SELECT * FROM orders"));
  assert.equal(fingerprint("SELECT /* outer /* inner SECRET */ comment */ 1"), "select ?");
});

test("MySQL dialect respects backtick identifiers, hash comments and -- whitespace rules", () => {
  assert.equal(fingerprint("SELECT `Order`, `a``b` FROM `Orders` WHERE id=1 #SECRET\n", {system: "mysql"}),
    "select `Order` , `a``b` from `Orders` where id = ?");
  assert.equal(fingerprint("SELECT 1 -- SECRET", {system: "mysql"}), "select ?");
  assert.equal(fingerprint("SELECT 1--2", {system: "mysql"}), "select ? - - ?");
  assert.notEqual(fingerprint("SELECT * FROM Orders", {system: "mysql"}), fingerprint("SELECT * FROM orders", {system: "mysql"}));
  for (const sql of ['SELECT "SECRET"', "SELECT 'secret\\'s suffix'", "SELECT /*!80000 SECRET */ 1",
    "SELECT /*M!100100 SECRET */ 1", "SELECT /*+ SECRET */ 1", "SELECT _utf8'SECRET'", "SELECT @secret", "SELECT /* a /* b */ 1"]) {
    noFingerprint(sql, {system: "mysql"});
  }
});

test("unsupported, unterminated, truncated and ambiguous templates fail closed", () => {
  for (const sql of ["", " ", "SELECT", "SELECT 'SECRET", 'SELECT "SECRET', "SELECT /* SECRET", "SELECT $tag$SECRET", "SELECT `SECRET`",
    "SELECT 'secret\\'s suffix'", "SELECT U&'SECRET'", "SELECT q'[SECRET]'", "SELECT :SECRET", "SELECT ${SECRET}",
    "SELECT 1; SELECT 2", "SELECT 1;;", "SELECT (1", "SELECT 1)", "SELECT 1 FROM", "SELECT id =", "SELECT 1 +",
    "SELECT 0xSECRET", "SELECT 123SECRET", "SELECT x ? 'SECRET'", "SELECT ? ? ?", "SELECT \u0000'SECRET'", "SELECT \u202e'SECRET'",
    "EXECUTE SECRET", "COPY orders TO 'SECRET'"]) noFingerprint(sql);
  for (const extra of [{sqlTruncated: true}, {sqlTruncated: "true"}, {sqlTruncated: 1}, {sqlTruncated: "unknown"},
    {sqlSource: null}, {sqlSource: "unavailable"}, {sqlSource: "unknown"}]) noFingerprint("SELECT * FROM orders WHERE id=?", extra);
  noFingerprint("SELECT '" + "SECRET".repeat(4000) + "'");
  noFingerprint("SELECT " + Array(4100).fill("?").join(","));
});

test("supported PostgreSQL question-mark operators retain distinct shapes without leaking operands", () => {
  for (const operator of ["??", "?|", "?&", "??|", "??&", "@?"]) {
    const result = fingerprint(`SELECT payload ${operator} 'SECRET' FROM orders WHERE id=?`);
    assert.ok(result, operator); assert.ok(result.includes(operator), operator); assert.doesNotMatch(result, /SECRET/);
  }
  assert.notEqual(fingerprint("SELECT payload @> 'SECRET' FROM orders"), fingerprint("SELECT payload <@ 'SECRET' FROM orders"));
  assert.equal(fingerprint("SELECT ?::text"), "select ? :: text");
});

test("partial coverage, capture limits and absent evidence are never reported as healthy", () => {
  for (const extra of [{coverage: undefined}, {coverage: {complete: false}}, {coverage: {complete: true, warnings: ["secret detail"]}},
    {coverage: {complete: true, truncated: true}}, {truncated: true}, {partial: true}, {coverage: {complete: true, partial: true}}]) {
    const result = model.analyze(capture(queries(1), extra));
    assert.equal(result.findings.length, 0); warning(result, /partial or unavailable/); warning(result, /not a clean bill/);
    assert.ok(!result.warnings.some(item => item.includes("secret detail")));
  }
  const limited = model.analyze(capture([...queries(3), query("limit", {evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED"})]));
  assert.equal(limited.queryCount, 3); assert.equal(findings(limited, "repeated-query")[0].count, 3); warning(limited, /lower bounds/);
  const empty = model.analyze(capture([])); assert.equal(empty.queryCount, 0); warning(empty, /unknown, not healthy/);
});

test("raw graph schema is mandatory; presentation groups cannot substitute for raw executions", () => {
  for (const input of [null, undefined, {}, {nodes: [], edges: []}, {...capture([]), schemaVersion: 2},
    {...capture([]), schemaVersion: "1"}, {...capture([]), kind: "service-flow"}, {...capture([]), nodes: null}, {...capture([]), edges: {}}]) {
    const result = model.analyze(input); assert.equal(result.findings.length, 0); warning(result, /raw schema-version-1/);
  }
  const grouped = graphModel.groupDatabaseCalls(capture(queries(5))).model;
  const result = model.analyze(grouped); assert.equal(result.queryCount, 0); warning(result, /Grouped presentation/);
});

test("node/edge safety limits fail closed before scanning unsupported input sizes", () => {
  assert.ok(model.limits.nodes <= 10000);
  const atCap = model.analyze(capture(queries(model.limits.nodes - 1, {durationMs: 1})));
  assert.equal(atCap.queryCount, model.limits.nodes - 1);
  const overNodes = model.analyze(capture(queries(model.limits.nodes)));
  assert.equal(overNodes.queryCount, 0); warning(overNodes, /safety limit/);
  const overEdges = capture([]); overEdges.edges = new Array(model.limits.edges + 1);
  warning(model.analyze(overEdges), /safety limit/);
});

test("finding output is capped with an explicit omission notice without losing query counts", () => {
  const result = model.analyze(capture(queries(500, {durationMs: 200})));
  assert.equal(result.queryCount, 500); assert.equal(result.findings.length, 200);
  assert.equal(new Set(result.findings.map(item => item.id)).size, 200); warning(result, /additional matching findings are omitted/);
  assert.equal(result.findings[0].totalDurationMs, 100000);
  assert.equal(findings(result, "suspected-n-plus-one").length, 1); assert.equal(findings(result, "query-heavy").length, 1);
});

test("clipping retains late high-duration evidence instead of the first 200 findings", () => {
  const input = capture(queries(250, {durationMs: 101}));
  input.nodes.push(query("late-costly", {eventSeq: 10000, durationMs: 500000, sql: "SELECT * FROM costly WHERE id=?"}));
  input.edges.push({id: "costly-edge", from: "segment", to: "late-costly", kind: "outbound"});
  const result = model.analyze(input);
  assert.equal(result.findings.length, 200);
  assert.ok(result.findings.some(item => item.kind === "slow-query" && item.nodes[0].id === "late-costly"));
  for (let i = 1; i < result.findings.length; i++) assert.ok(result.findings[i - 1].totalDurationMs >= result.findings[i].totalDurationMs);
});

test("evidence ordering and finding output do not depend on input array ordering", () => {
  const input = capture(queries(8, i => ({eventSeq: 20 - i, durationMs: 110 + i, thread: i < 5 ? "one" : "two"})));
  const before = model.analyze(input);
  const after = model.analyze({...input, nodes: [...input.nodes].reverse(), edges: [...input.edges].reverse()});
  assert.deepEqual(before, after);
});
