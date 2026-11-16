const test = require("node:test");
const assert = require("node:assert/strict");
const deps = require("../tracelens_cli/web/dependency-ui.js");
const jdbc = extra => ({boundaryKind: "database", system: "postgresql", direction: "outbound", operation: "executeQuery",
  status: "OBSERVED", evidenceKind: "jdbc-execution", sql: "select * from features where id = 'local-literal'", sqlSource: "prepared", ...extra});

test("only explicit supported JDBC execution admits raw bounded SQL and typed metrics", () => {
  for (const system of ["mysql", "postgresql"]) {
    const raw = {type: "EXTERNAL_CALL", attributes: jdbc({system, durationMs: "1.25", batchSize: "3", sqlTruncated: "true",
      errorType: "java.sql.SQLException", payload: "NOT-ALLOWED", bindings: ["NOT-ALLOWED"], rows: ["NOT-ALLOWED"]})};
    const result = deps.boundaryMetadata(raw);
    assert.equal(result.sql, raw.attributes.sql); assert.equal(result.durationMs, 1.25);
    assert.equal(result.batchSize, 3); assert.equal(result.sqlTruncated, true); assert.equal(result.sqlSource, "prepared");
    assert.doesNotMatch(JSON.stringify(result), /NOT-ALLOWED/);
    assert.equal(deps.eventBoundary(raw).kind, "SQL QUERY");
    assert.doesNotMatch(JSON.stringify(deps.collect({events: [raw]})), /local-literal/);
    assert.doesNotMatch(JSON.stringify(deps.summaryBoundary(raw.attributes)), /local-literal/);
  }
  for (const changed of [{boundaryKind: "cache"}, {system: "redis"}, {system: "clickhouse"}, {direction: "publish"},
    {evidenceKind: "client-boundary"}, {operation: "prepareStatement"}, {status: "SUBMITTED"}]) {
    assert.equal(deps.jdbcDetails(jdbc(changed)), null);
    assert.equal(deps.boundaryMetadata({type: "EXTERNAL_CALL", attributes: jdbc(changed)}).sql, undefined);
  }
  assert.equal(deps.boundaryMetadata({type: "DB_ROUTE", attributes: jdbc()}).sql, undefined);
  assert.equal(deps.boundaryMetadata({type: "LINE", attributes: jdbc()}), null);
});

test("SQL clipping is explicit, missing SQL is not invented, and malformed numeric fields stay unknown", () => {
  const projected = deps.jdbcDetails(jdbc({sql: "x".repeat(18000)}));
  assert.equal(projected.sql.length, 16384); assert.equal(projected.sqlTruncated, true);
  const chars = deps.jdbcDetails(jdbc({sql: "select\n\t'x'\u0000\u202e"})).sql;
  assert.equal(chars, "select\n\t'x'  ");
  for (const value of [null, "", true, [], {}, "Infinity", -1, " 1", "1e999"]) {
    const fields = deps.jdbcDetails(jdbc({sql: value, durationMs: value, batchSize: value}));
    assert.equal(fields.durationMs, null); assert.equal(fields.batchSize, null);
    if (typeof value !== "string") assert.equal(fields.sql, null);
  }
  assert.equal(deps.jdbcDetails(jdbc({batchSize: "1.5"})).batchSize, null);
  assert.equal(deps.jdbcDetails(jdbc({durationMs: "1.0E-4"})).durationMs, .0001);
  assert.equal(deps.jdbcDetails(jdbc({batchSize: "1e2"})).batchSize, null);
  assert.equal(deps.jdbcDetails(jdbc({queryLimit: 2000, evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED"})).queryLimit, null);
  assert.equal(deps.jdbcDetails(jdbc({sql: "a".repeat(16383) + "😀"})).sql.length, 16383);
});

test("display-sanitized SQL and binding values retain uncertainty through repeated projection", () => {
  const {reconstruct} = require("../tracelens_cli/web/sql-values.js");
  const parameter = (index, value) => ({index, type: "String", value, status: "captured"});
  for (const serialized of [false, true]) {
    const bindings = [parameter(1, "a\u0000b"), parameter(2, "c\u202ed"), parameter(3, "safe\nvalue")];
    const raw = jdbc({sql: "SELECT ?, ?, ?", sqlParameters: serialized ? JSON.stringify(bindings) : bindings,
      sqlParametersStatus: "captured"});
    const before = JSON.stringify(raw), first = deps.jdbcDetails(raw);
    assert.deepEqual(first.sqlParameters.map(row => row.status), ["truncated", "truncated", "captured"]);
    assert.equal(first.sqlParametersTruncated, true);
    assert.equal(reconstruct(first, "postgresql").sql, "SELECT ?, ?, 'safe\nvalue'");
    assert.equal(reconstruct(first, "postgresql").complete, false);
    assert.deepEqual(deps.jdbcDetails({...raw, ...first}), first);
    assert.equal(JSON.stringify(raw), before);
  }
  const batch = deps.jdbcDetails(jdbc({sql: "SELECT ?", sqlParameterSets: [[parameter(1, "a\u0000b")], [parameter(1, "safe")]],
    sqlParametersStatus: "batch"}));
  assert.equal(batch.sqlParameterSets[0][0].status, "truncated");
  assert.equal(reconstruct(batch, "postgresql", batch.sqlParameterSets[0]).sql, "SELECT ?");
  assert.equal(reconstruct(batch, "postgresql", batch.sqlParameterSets[1]).sql, "SELECT 'safe'");
  assert.equal(batch.sqlParametersTruncated, true);
  const altered = jdbc({sql: "SELECT 'a\u202eb', ?", sqlParameters: [parameter(1, "safe")], sqlParametersStatus: "captured"});
  const first = deps.jdbcDetails(altered);
  assert.equal(first.sql, "SELECT 'a b', ?"); assert.equal(first.sqlTruncated, true);
  assert.equal(reconstruct(first, "postgresql").substituted, 0);
  assert.deepEqual(deps.jdbcDetails({...altered, ...first}), first);
  assert.equal(deps.jdbcDetails(jdbc({sql: "SELECT\n\t?"})).sqlTruncated, false);
});

test("SQL limit marker cannot carry SQL, duration, bindings or masquerade as execution", () => {
  const raw = {type: "EXTERNAL_CALL", attributes: jdbc({evidenceKind: "jdbc-query-limit", operation: "query-limit",
    status: "TRUNCATED", queryLimit: "20", durationMs: 7, sql: "NOT-ALLOWED"})};
  const value = deps.boundaryMetadata(raw);
  assert.equal(value.queryLimit, 20); assert.equal(value.sql, undefined); assert.equal(value.durationMs, undefined);
  assert.equal(deps.eventBoundary(raw).kind, "SQL CAPTURE LIMIT");
  assert.equal(deps.summaryBoundary(value).kind, "SQL CAPTURE LIMIT");
  assert.doesNotMatch(JSON.stringify(value), /NOT-ALLOWED/);
  assert.equal(deps.jdbcDetails({...raw.attributes, operation: "executeQuery"}), null);
  const summary = deps.collect({events: [raw, {type: "EXTERNAL_CALL", attributes: jdbc()}, {type: "DB_ROUTE", attributes: {route: "master"}}]}, 1);
  assert.equal(summary.queryCount, 1); assert.equal(summary.queryLimitCount, 1); assert.equal(summary.omitted, 2);
  assert.equal(summary.rows.length, 1, "counts are independent of bounded visible rows");
});

test("HTTP evidence remains visible with no receiver segment or propagation", () => {
  const result = deps.collect({httpCalls: [{peer: "ems:8082", path: "/features", httpMethod: "GET", status: "ERROR", httpStatus: "503", propagationStatus: "NOT_ALLOWLISTED"}]});
  assert.equal(result.rows[0].kind, "HTTP OUT");
  assert.equal(result.rows[0].destination, "ems:8082");
  assert.match(result.rows[0].status, /503/);
  assert.match(result.rows[0].evidence, /Caller-side/);
});

test("queue directions and async send semantics remain explicit", () => {
  const event = direction => ({type: "EXTERNAL_CALL", attributes: {boundaryKind: "messaging", system: "kafka", destination: "billing-events", direction, operation: "send", status: "SUBMITTED", evidenceKind: "client-boundary", payload: "SECRET"}});
  const result = deps.collect({events: [event("publish"), event("consume")]});
  assert.equal(result.rows[0].kind, "QUEUE PUBLISH");
  assert.equal(result.rows[1].kind, "QUEUE CONSUME");
  assert.match(result.rows[0].evidence, /not implied/);
  assert.doesNotMatch(JSON.stringify(result), /SECRET/);
});

test("legacy wrappers and DB routes do not masquerade as confirmed network operations", () => {
  const result = deps.collect({events: [
    {type: "EXTERNAL_CALL", message: "SECRET payload", attributes: {system: "Repository", detail: "find", status: "COMPLETED"}},
    {type: "DB_ROUTE", attributes: {route: "test-reader"}},
    {type: "LINE"}, null
  ]});
  assert.equal(result.total, 2);
  assert.equal(result.rows[0].status, "OBSERVED");
  assert.match(result.rows[0].evidence, /not confirmed/);
  assert.match(result.rows[1].evidence, /Pool selection/);
  assert.doesNotMatch(JSON.stringify(result), /SECRET/);
});

test("malformed inputs and long metadata are bounded without rendering raw messages", () => {
  assert.deepEqual(deps.collect({events: {}, httpCalls: {}}).rows, []);
  const event = {type: "EXTERNAL_CALL", attributes: {system: "x".repeat(400), destination: "\u202eevil", operation: ["secret"], detail: "token=SECRET"}};
  const result = deps.collect({events: Array(10).fill(event)}, 2);
  assert.equal(result.rows.length, 2);
  assert.equal(result.omitted, 8);
  assert.ok(result.rows[0].destination.length < 280);
  assert.doesNotMatch(JSON.stringify(result), /SECRET|\u202e/);
});

test("raw dependency metadata matches canonical credential and token filtering", () => {
  const raw = {type: "EXTERNAL_CALL", attributes: {boundaryKind: "messaging", system: "sqs",
    destination: "https://user:SECRET@sqs.example/123/orders?token=SECRET#SECRET", operation: "send SECRET",
    direction: "publish", status: "SUBMITTED", evidenceKind: "client-boundary", headers: {secret: "SECRET"}}};
  assert.deepEqual(deps.boundaryMetadata(raw), {boundaryKind: "messaging", system: "sqs", direction: "publish",
    destination: "https://sqs.example/123/orders", operation: null, status: "SUBMITTED", evidenceKind: "client-boundary"});
  assert.doesNotMatch(JSON.stringify(deps.eventBoundary(raw)), /SECRET/);
  for (const target of ["orders?token=SECRET", "kafka://user:SECRET@host/topic", "orders\u202e", "x".repeat(513), "https://host:65536/topic"]) {
    raw.attributes.destination = target;
    assert.equal(deps.boundaryMetadata(raw).destination, null);
  }
  for (const [input, expected] of [["HTTPS://USER:PASS@HOST:0080/a/../b?token=x", "https://host:80/a/../b"],
    ["https://host", "https://host"], ["https://[::1]:443/orders#token", "https://[::1]:443/orders"],
    ["https://host:/orders", "https://host/orders"]]) {
    raw.attributes.destination = input;
    assert.equal(deps.boundaryMetadata(raw).destination, expected);
  }
  for (const operation of ["send\n", "send\u2028", "x".repeat(129), [], "password=SECRET", "<init>"]) {
    raw.attributes.operation = operation;
    assert.equal(deps.boundaryMetadata(raw).operation, null);
  }
  const route = deps.eventBoundary({type: "DB_ROUTE", attributes: {route: "https://user:SECRET@db.example/pool?token=SECRET"}});
  assert.equal(route.destination, "https://db.example/pool");
  assert.doesNotMatch(JSON.stringify(route), /SECRET/);
});

test("legacy attributes cannot upgrade wrapper observations into client delivery", () => {
  const raw = {type: "EXTERNAL_CALL", attributes: {system: "LegacyWrapper", detail: "read",
    direction: "consume", destination: "SECRET", operation: "SECRET", status: "SUBMITTED", evidenceKind: "client-boundary"}};
  assert.deepEqual(deps.boundaryMetadata(raw), {boundaryKind: "external", system: "LegacyWrapper", direction: "outbound",
    destination: null, operation: "read", status: "OBSERVED", evidenceKind: "wrapper-entry"});
  const view = deps.eventBoundary(raw);
  assert.equal(view.kind, "EXTERNAL WRAPPER");
  assert.equal(view.status, "OBSERVED");
  assert.match(view.evidence, /wrapper entry.*not confirmed/);
  assert.doesNotMatch(JSON.stringify(view), /SECRET|SUBMITTED|delivery\/handler/);
});

test("unknown directions and evidence stay unknown in raw and distributed views", () => {
  for (const direction of [undefined, null, "INVALID", [], "outbound"]) {
    const attrs = {boundaryKind: "messaging", direction, system: "sqs", destination: "orders", status: "COMPLETED", evidenceKind: "unknown"};
    for (const view of [deps.eventBoundary({type: "EXTERNAL_CALL", attributes: attrs}), deps.summaryBoundary(attrs)]) {
      assert.equal(view.kind, "MESSAGING BOUNDARY");
      assert.equal(view.direction, direction === "outbound" ? "outbound" : null);
      assert.equal(view.status, "Unknown");
      assert.match(view.evidence, /evidence kind unknown/);
      assert.doesNotMatch(view.evidence, /Configured wrapper|delivery\/handler/);
    }
  }
  const view = deps.summaryBoundary({boundaryKind: "external", system: "gateway", direction: "outbound",
    destination: "payments", destinationName: "Payments API", operation: "send", status: "ATTEMPTED", evidenceKind: "client-boundary"});
  assert.equal(view.kind, "EXTERNAL BOUNDARY");
  assert.equal(view.destination, "gateway · Payments API");
  assert.equal(view.status, "ATTEMPTED");
});

test("new wrapper-entry status and operation follow explicit canonical metadata", () => {
  const raw = {type: "EXTERNAL_CALL", attributes: {boundaryKind: "external", system: "Wrapper", operation: "publish",
    direction: "outbound", status: "ERROR", evidenceKind: "wrapper-entry", detail: "SECRET"}};
  const view = deps.eventBoundary(raw);
  assert.equal(view.operation, "publish");
  assert.equal(view.status, "ERROR");
  assert.doesNotMatch(JSON.stringify(view), /SECRET/);
});

test("explicit database/cache clients stay distinct from routing and messaging without payloads", () => {
  for (const [boundaryKind, system, kind] of [["database", "clickhouse", "DATABASE CALL"],
    ["database", "dynamodb", "DATABASE CALL"], ["cache", "redis", "CACHE CALL"]]) {
    const attributes = {boundaryKind, system, direction: "outbound", operation: "get",
      evidenceKind: "client-boundary", status: "OBSERVED", sql: "SECRET", key: "SECRET", value: "SECRET"};
    for (const view of [deps.eventBoundary({type: "EXTERNAL_CALL", attributes}), deps.summaryBoundary(attributes)]) {
      assert.equal(view.kind, kind);
      assert.equal(view.destination, system);
      assert.match(view.evidence, /cache hit\/miss and remote execution are not inferred/);
      assert.doesNotMatch(JSON.stringify(view), /SECRET|Pool selection|QUEUE/);
    }
    assert.equal(deps.eventBoundary({type: "EXTERNAL_CALL", attributes: {...attributes, evidenceKind: "wrapper-entry"}}).kind,
      boundaryKind === "cache" ? "CACHE ACCESS" : "EXTERNAL WRAPPER", "a wrapper never upgrades into a client observation");
  }
});

const cacheClass = "com.chargebee.ems.config.cache.ValkeyCacheService";
const cacheEntry = (seq, methodName = "get", extra = {}) => ({type: "METHOD_ENTER", seq, thread: "emsAsync-1",
  className: cacheClass, methodName, ...extra});
const cacheClient = (seq, operation = "get", extra = {}) => ({type: "EXTERNAL_CALL", seq, thread: "emsAsync-1", attributes: {
  boundaryKind: "cache", system: "redis", direction: "outbound", destination: null, operation,
  status: "OBSERVED", evidenceKind: "client-boundary"}, ...extra});

test("cache wrapper suppression uses runtime thread identity without falling back to duplicate names", () => {
  assert.equal(deps.boundaryEvents([cacheEntry(1, "get", {threadId: 1}), cacheClient(2, "get", {threadId: 2})]).rows.length, 2);
  assert.equal(deps.boundaryEvents([cacheEntry(1, "get", {threadId: 1}), cacheClient(2)]).rows.length, 2);
  assert.equal(deps.boundaryEvents([cacheEntry(1, "get", {threadId: 1}), cacheClient(2, "get", {threadId: "1"})]).rows.length, 2);
  assert.equal(deps.boundaryEvents([cacheEntry(1, "get", {threadId: 1}), cacheClient(2, "get", {threadId: 1, thread: "renamed"})]).rows.length, 1);
});

test("cache wrapper overload exits cannot suppress a later call using a stale invocation", () => {
  const events = [cacheEntry(1, "get", {threadId: 1, methodDescriptor: "(I)V"}),
    cacheEntry(2, "get", {threadId: 1, methodDescriptor: "()V"}),
    cacheEntry(3, "get", {threadId: 1, type: "METHOD_EXIT", methodDescriptor: "(I)V"}),
    cacheClient(4, "get", {threadId: 1})];
  assert.equal(deps.boundaryEvents(events).rows.length, 3);
  for (const descriptor of [null, "invalid"]) {
    events[2].methodDescriptor = descriptor;
    assert.equal(deps.boundaryEvents(events).rows.length, 3);
  }
});

test("descriptor parser rejects malformed and oversized identities", () => {
  for (const descriptor of ["()V", "(I)Ljava/lang/String;", "([[I[Ljava/lang/String;)J", "()Lsample/Outer$Inner;"]) assert.equal(deps.methodDescriptor(descriptor), descriptor);
  for (const descriptor of ["(V)V", "()VV", "([V)V", "()L;", "()Ljava//lang;", "()Ljava.lang.String;", "(" + "I".repeat(3000) + ")V", {}, null]) assert.equal(deps.methodDescriptor(descriptor), null);
});

test("only exact audited cache wrapper method entries become weaker cache-access observations", () => {
  for (const methodName of ["get", "put", "evict"]) {
    const event = cacheEntry(292, methodName, {message: "SECRET", arguments: ["SECRET"],
      attributes: {status: "HIT", destination: "SECRET", payload: "SECRET"}});
    assert.deepEqual(deps.boundaryMetadata(event), {boundaryKind: "cache", system: "redis", direction: "outbound",
      destination: null, operation: methodName, status: "OBSERVED", evidenceKind: "wrapper-entry"});
    const view = deps.eventBoundary(event);
    assert.equal(view.kind, "CACHE ACCESS"); assert.equal(view.operation, methodName);
    assert.match(view.evidence, /wrapper invocation.*hit\/miss and remote execution are not established/);
    assert.doesNotMatch(JSON.stringify(view), /SECRET|HIT|CACHE CALL/);
  }
  for (const extra of [{type: "METHOD_EXIT"}, {type: "LINE"}, {methodName: "compute"}, {methodName: "getSomething"},
    {className: "com.example.ValkeyCacheService"}, {className: cacheClass + "$Proxy"}, {className: null}]) {
    assert.equal(deps.boundaryMetadata(cacheEntry(292, "get", extra)), null);
  }
  assert.equal(deps.eventBoundary(cacheClient(293)).kind, "CACHE CALL");
});

test("matching nested client replaces only its original wrapper observation without mutating evidence", () => {
  for (const [wrapperOp, clientOp] of [["get", "get"], ["put", "set"], ["evict", "delete"]]) {
    const events = [cacheEntry(1, wrapperOp), cacheClient(2, clientOp)];
    const before = JSON.stringify(events);
    const observed = deps.boundaryEvents(events);
    assert.equal(observed.rows.length, 1); assert.equal(observed.rows[0].event, events[1]);
    assert.equal(observed.rows[0].index, 1); assert.equal(observed.rows[0].metadata.evidenceKind, "client-boundary");
    assert.equal(deps.collect({events}).total, 1); assert.equal(observed.scanLimited, false);
    assert.equal(JSON.stringify(events), before);
  }
  const events = [cacheEntry(1), cacheEntry(2), cacheClient(3)];
  const rows = deps.boundaryEvents(events).rows;
  assert.deepEqual(rows.map(row => row.index), [0, 2], "nearest matching invocation alone is superseded");
  assert.equal(rows[0].event, events[0]);
});

test("cache wrapper suppression requires contiguous matching same-thread active invocation evidence", () => {
  const scenarios = [
    [cacheEntry(1), cacheClient(2, "set")],
    [cacheEntry(1), cacheClient(2, "get", {thread: "other"})],
    [cacheEntry(1), cacheClient(3)],
    [cacheEntry(1), cacheClient(2, "get", {detailGapBefore: true})],
    [cacheEntry(1), {type: "LINE", seq: 2, thread: "other", detailGapBefore: true}, cacheClient(3)],
    [cacheEntry(1), cacheClient(null)],
    [cacheEntry(1), cacheEntry(2, "get", {type: "METHOD_EXIT"}), cacheClient(3)],
    [cacheEntry(1), cacheEntry(2, "other", {type: "METHOD_EXIT"}), cacheClient(3)],
    [cacheEntry(1), cacheEntry(2, "get", {className: "<script>"}), cacheClient(3)],
    [cacheEntry(1), cacheEntry(2, "bad method"), cacheClient(3)],
    [cacheEntry(1), cacheEntry(2, "get", {thread: null}), cacheClient(3)],
    [cacheEntry(1), null, cacheClient(2)],
    [cacheEntry(1), {...cacheClient(2), attributes: {...cacheClient(2).attributes, status: "INVALID"}}],
    [cacheEntry(1), {...cacheClient(2), attributes: {...cacheClient(2).attributes, direction: "INVALID"}}],
  ];
  for (const events of scenarios) {
    const rows = deps.boundaryEvents(events).rows;
    assert.ok(rows.some(row => row.event === events[0]), "uncertain evidence must retain the original wrapper");
  }
  const interleaved = [cacheEntry(1), cacheEntry(2, "put", {thread: "other"}), cacheClient(3)];
  assert.deepEqual(deps.boundaryEvents(interleaved).rows.map(row => row.index), [1, 2]);
});

test("cache boundary derivation respects input, thread and depth limits with visible uncertainty", () => {
  assert.deepEqual(deps.boundaryEvents(null), {rows: [], scanLimited: false});
  const events = Array.from({length: 100001}, () => null);
  events[0] = cacheEntry(1); events[100000] = cacheEntry(100001);
  const result = deps.boundaryEvents(events);
  assert.equal(result.rows.length, 1); assert.equal(result.scanLimited, true);
  for (const input of [
    [...Array.from({length: 65}, (_, index) => cacheEntry(index + 1, "get", {thread: `thread-${index}`})),
      cacheClient(66, "get", {thread: "thread-0"})],
    [...Array.from({length: 513}, (_, index) => cacheEntry(index + 1)), cacheClient(514)]
  ]) {
    const observed = deps.boundaryEvents(input);
    assert.equal(observed.scanLimited, true);
    assert.equal(observed.rows[0].event, input[0], "limit does not discard uncertain wrapper observations");
  }
});
