const test = require("node:test");
const assert = require("node:assert/strict");
const deps = require("../tracelens_cli/web/dependency-ui.js");

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
