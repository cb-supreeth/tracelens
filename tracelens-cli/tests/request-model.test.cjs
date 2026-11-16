const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const model = require("../tracelens_cli/web/request-model.js");

const NOW = 1_800_000_000_000;
const TRACE = "1234567890abcdef1234567890abcdef";
const OTHER = "abcdefabcdefabcdefabcdefabcdefab";
function row(overrides = {}) {
  return { traceId: TRACE, segmentId: "app-segment", service: "cb-app", serviceInstanceId: "app-1",
    entryChannel: "HTTP", httpMethod: "GET", path: "/features", user: "alice_42", siteId: 42,
    httpStatus: 200, status: "COMPLETED", durationMs: 25, eventCount: 10,
    startedAtMillis: NOW - 1000, lastActivityMillis: NOW - 500, ...overrides };
}
function child(overrides = {}) {
  return row({ service: "cb-ems", serviceInstanceId: "ems-1", segmentId: "ems-segment",
    parentSpanId: "1234567890abcdef", startedAtMillis: NOW - 900, durationMs: 10, eventCount: 5, ...overrides });
}
const unrestricted = { channel: "", sinceMinutes: "" };

test("defaults are explicit, fresh, request-focused and browser export works", () => {
  assert.deepEqual(model.defaults(), { query: "", path: "", user: "", site: "", service: "", method: "",
    channel: "HTTP", sinceMinutes: "30", outcome: "", minDuration: "", maxDuration: "", collapse: true });
  const first = model.defaults(); first.path = "changed";
  assert.equal(model.defaults().path, "");
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-model.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensRequests.groupTraces, "function");
});

test("two services under one valid trace become one request without summing latency", () => {
  const input = [child(), row()];
  const requests = model.groupTraces(input);
  assert.equal(requests.length, 1);
  const request = requests[0];
  assert.equal(request.root.service, "cb-app");
  assert.equal(request.rootMissing, false);
  assert.equal(request.traceId, TRACE);
  assert.equal(request.durationMs, 25);
  assert.equal(request.eventCount, 15);
  assert.equal(request.outcome, "success");
  assert.deepEqual(request.services, ["cb-app", "cb-ems"]);
  assert.equal(request.segments.length, 2);
  assert.equal(input[0].service, "cb-ems"); // normalization never mutates input
});

test("legacy UUID trace ids and uppercase IDs are normalized", () => {
  const legacy = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE";
  assert.equal(model.groupTraces([row({ traceId: legacy }), child({ traceId: legacy.toLowerCase() })]).length, 1);
  assert.equal(model.groupTraces([row({ traceId: TRACE.toUpperCase() }), child()]).length, 1);
});

test("missing, zero or malformed trace IDs do not invent shared requests", () => {
  for (const invalid of [null, undefined, "", "trace-1", "0".repeat(32), "00000000-0000-0000-0000-000000000000", {}, []]) {
    const result = model.groupTraces([row({ traceId: invalid }), child({ traceId: invalid })]);
    assert.equal(result.length, 2);
    assert.ok(result.every((request) => request.traceId === null));
    assert.notEqual(result[0].key, result[1].key);
  }
  const noIds = model.groupTraces([{}, {}, { segmentId: "x" }, { segmentId: "x" }]);
  assert.equal(new Set(noIds.map((request) => request.key)).size, 4);
  assert.equal(model.groupTraces([row({ traceId: null })])[0].key, model.groupTraces([row({ traceId: null })])[0].key);
});

test("repeated API requests retain separate IDs even when collapsed", () => {
  const requests = model.groupTraces([row(), row({ traceId: OTHER, segmentId: "app-two" })]);
  assert.equal(requests.length, 2);
  const buckets = model.bucketRequests(requests, true);
  assert.equal(buckets.length, 1);
  assert.equal(buckets[0].requests.length, 2);
  assert.equal(model.bucketRequests(requests, false).length, 2);
});

test("any segment error outranks root success and active work", () => {
  const request = model.groupTraces([row(), child({ httpStatus: "503", status: "ACTIVE" })])[0];
  assert.equal(request.httpStatus, 200);
  assert.equal(request.hasErrors, true);
  assert.equal(request.outcome, "error");
  for (const failure of [{ status: "FAILED" }, { status: "ERROR" }, { hasErrors: true },
    { asyncTimeout: true },
    { attributes: { asyncTimeout: "true" } }]) {
    assert.equal(model.groupTraces([row(), child(failure)])[0].outcome, "error");
  }
  assert.equal(model.groupTraces([row(), child({ status: "ACTIVE" })])[0].outcome, "active");
  assert.equal(model.groupTraces([row({ status: "EXPORTED", httpStatus: null })])[0].outcome, "unknown");
  assert.equal(model.groupTraces([row({ status: "COMPLETED", httpStatus: null })])[0].outcome, "unknown");
});

test("caught exception evidence is separate from request failure", () => {
  for (const caught of [{ exceptionCount: 1 }, { attributes: { exceptionCount: "2" } },
    { events: [{ type: "EXCEPTION" }, { type: "LINE" }] },
    { filterException: "java.lang.IllegalStateException" },
    { attributes: { filterException: "java.lang.IllegalStateException" } }]) {
    const request = model.groupTraces([row(), child(caught)])[0];
    assert.equal(request.hasErrors, false);
    assert.equal(request.outcome, "success");
    assert.equal(request.hasExceptions, true);
  }
  for (const benign of [{ asyncTimeout: false }, { asyncTimeout: "false" },
    { attributes: { asyncTimeout: "false" } }, { filterException: "" }, { filterException: {} }]) {
    assert.equal(model.groupTraces([row(benign)])[0].outcome, "success");
  }
});

test("missing or ambiguous roots retain evidence but never fabricate root latency or status", () => {
  const orphan = model.groupTraces([child()])[0];
  assert.equal(orphan.rootMissing, true);
  assert.equal(orphan.rootAmbiguous, false);
  assert.equal(orphan.durationMs, null);
  assert.equal(orphan.httpStatus, null);
  assert.equal(orphan.outcome, "unknown");
  assert.equal(orphan.root.service, "cb-ems");
  const ambiguous = model.groupTraces([row(), child({ parentSpanId: null })])[0];
  assert.equal(ambiguous.rootMissing, true);
  assert.equal(ambiguous.rootAmbiguous, true);
  assert.equal(ambiguous.segments.length, 2);
  assert.equal(ambiguous.durationMs, null);
  assert.equal(model.groupTraces([row({ parentSpanId: {} })])[0].rootMissing, true);
});

test("normalizes old attributes and malformed scalar values without throwing", () => {
  const request = model.groupTraces([row({ httpMethod: {}, path: null, user: [], siteId: null,
    httpStatus: {}, durationMs: "25.5", eventCount: "10", startedAtMillis: null, startedAt: String(NOW - 1000),
    attributes: { httpMethod: "post", path: "/legacy", user: "bob_8", siteId: 8, httpStatus: "201" } })])[0];
  assert.equal(request.method, "POST");
  assert.equal(request.path, "/legacy");
  assert.equal(request.site, "8");
  assert.equal(request.user, "bob_8");
  assert.equal(request.durationMs, 25.5);
  assert.equal(request.httpStatus, 201);
  for (const bad of [NaN, Infinity, -1, true, {}, [], "garbage"]) {
    const malformed = model.groupTraces([row({ startedAtMillis: bad, lastActivityMillis: bad, durationMs: bad, eventCount: bad, httpStatus: bad })])[0];
    assert.equal(malformed.durationMs, null);
    assert.equal(malformed.httpStatus, null);
    assert.equal(malformed.eventCount, 0);
  }
});

test("AND filters must match the same segment, not unrelated metadata from sibling services", () => {
  const requests = model.groupTraces([row(), child({ path: "/reference_counts", user: "service_42", siteId: 99, httpMethod: "POST" })]);
  assert.equal(model.filterRequests(requests, { service: "cb-ems", path: "reference", user: "service", site: "99", method: "post" }, NOW).length, 1);
  for (const mismatch of [{ service: "cb-ems", user: "alice" }, { path: "reference", site: "42" },
    { service: "cb-app", method: "POST" }, { path: "reference", method: "GET" }, { service: "cb-em" }]) {
    assert.equal(model.filterRequests(requests, mismatch, NOW).length, 0);
  }
  assert.equal(model.filterRequests(requests, { site: "9" }, NOW).length, 0); // site is exact
});

test("time, root duration, query, channel and outcome filters compose", () => {
  const requests = model.groupTraces([row(), child({ httpStatus: 500 }),
    row({ traceId: OTHER, segmentId: "another", startedAtMillis: NOW - 3600000 })]);
  assert.equal(model.filterRequests(requests, {}, NOW).length, 1);
  assert.equal(model.filterRequests(requests, { query: "ems-1", outcome: "error", minDuration: "20", maxDuration: "30" }, NOW).length, 1);
  assert.equal(model.filterRequests(requests, { outcome: "success" }, NOW).length, 0);
  assert.equal(model.filterRequests(requests, { minDuration: "30" }, NOW).length, 0);
  assert.equal(model.filterRequests(requests, { maxDuration: "20" }, NOW).length, 0);
  assert.equal(model.filterRequests(requests, { query: "missing" }, NOW).length, 0);
  assert.equal(model.filterRequests(requests, unrestricted, NOW).length, 2);
  const jobs = model.groupTraces([row({ entryChannel: "SQS" })]);
  assert.equal(model.filterRequests(jobs, {}, NOW).length, 0);
  assert.equal(model.filterRequests(jobs, { channel: "SQS" }, NOW).length, 1);
  const orphan = model.groupTraces([child()]);
  assert.equal(model.filterRequests(orphan, { minDuration: "0" }, NOW).length, 0);
  for (const invalid of [{ sinceMinutes: "bad" }, { minDuration: "bad" }, { maxDuration: Infinity }, { minDuration: 30, maxDuration: 10 }]) {
    assert.equal(model.filterRequests(requests, invalid, NOW).length, 0);
  }
});

test("API path and free query match captured templates as well as literal paths", () => {
  const requests = model.groupTraces([row({ path: "/features/123", routeTemplate: "/features/{id}" }),
    child({ path: "/licenses/456", attributes: { route: "/licenses/{id}" } })]);
  for (const filters of [{ path: "/features/{id}" }, { path: "/features/123" },
    { query: "/licenses/{id}" }, { query: "/licenses/456" },
    { path: "/licenses/{id}", service: "cb-ems" }]) {
    assert.equal(model.filterRequests(requests, filters, NOW).length, 1);
  }
  assert.equal(model.filterRequests(requests, { path: "/licenses/{id}", service: "cb-app" }, NOW).length, 0);
  const literalOnly = model.groupTraces([row({ path: "/features/123" })]);
  assert.equal(model.filterRequests(literalOnly, { path: "/features/{id}" }, NOW).length, 0);
  assert.equal(model.filterRequests(literalOnly, { query: "/features/{id}" }, NOW).length, 0);
});

test("sorts by request start, not noisy later activity; tie ordering is deterministic", () => {
  const requests = model.groupTraces([row({ lastActivityMillis: NOW + 100000 }),
    row({ traceId: OTHER, segmentId: "new", startedAtMillis: NOW, lastActivityMillis: NOW })]);
  assert.equal(requests[0].traceId, OTHER);
  assert.equal(model.bucketRequests(requests, true)[0].latestMillis, NOW);
  const ties = [row(), row({ traceId: OTHER, segmentId: "other" })];
  assert.deepEqual(model.groupTraces(ties).map((r) => r.key), model.groupTraces(ties.slice().reverse()).map((r) => r.key));
});

test("buckets use only actual templates or literal paths, preserve requests and service/channel separation", () => {
  const literals = model.groupTraces([row({ path: "/features/123" }), row({ traceId: OTHER, path: "/features/456" })]);
  assert.equal(model.bucketRequests(literals, true).length, 2);
  const templated = model.groupTraces([row({ path: "/features/123", routeTemplate: "/features/{id}" }),
    row({ traceId: OTHER, path: "/features/456", attributes: { "http.route": "/features/{id}" } })]);
  assert.equal(model.bucketRequests(templated, true).length, 1);
  const capturedRoute = model.groupTraces([row({ path: "/features/123", attributes: { route: "/features/{id}" } })]);
  assert.equal(capturedRoute[0].root.routeTemplate, "/features/{id}");
  assert.equal(model.bucketRequests(model.filterRequests(literals, { path: "123" }, NOW), true)[0].requests.length, 1);
  const separated = model.groupTraces([row(), row({ traceId: OTHER, service: "cb-ems" })]);
  assert.equal(model.bucketRequests(separated, true).length, 2);
});

test("empty and malformed collections are safe", () => {
  for (const value of [null, undefined, {}, [], [null, 1, "x", []]]) assert.deepEqual(model.groupTraces(value), []);
  assert.deepEqual(model.filterRequests([], {}, NOW), []);
  assert.deepEqual(model.bucketRequests([], true), []);
});

test("pathless scheduler buckets preserve distinct job labels and repeat counts", () => {
  const job = { entryChannel: "SCHEDULER", httpMethod: "", path: "", httpStatus: null,
    lensId: "refresh-feature-cache" };
  const requests = model.groupTraces([row(job),
    row({ ...job, traceId: OTHER, segmentId: "job-repeat" }),
    row({ ...job, traceId: "fedcbafedcbafedcbafedcbafedcbafedc", segmentId: "other-job", lensId: "expire-licenses" })]);
  const buckets = model.bucketRequests(requests, true);
  assert.equal(buckets.length, 2);
  assert.equal(buckets.find((bucket) => bucket.label.includes("refresh-feature-cache")).requests.length, 2);
  assert.equal(buckets.find((bucket) => bucket.label.includes("expire-licenses")).requests.length, 1);
  assert.equal(model.bucketRequests(requests, false).length, 3);
});

test("scheduler outcomes require explicit execution evidence, never lifecycle or exception events", () => {
  const job = extra => row({entryChannel: "SCHEDULER", httpStatus: null, httpMethod: "", path: "", lensId: "",
    jobType: "compute_billing_metrics", jobId: "123", scheduler: "chargebee", ...extra});
  for (const [jobOutcome, outcome] of [["SUCCEEDED", "success"], ["SKIPPED", "skipped"], ["FAILED", "error"],
    ["RUNNING", "active"], ["UNKNOWN", "unknown"], [null, "unknown"]]) {
    const request = model.groupTraces([job({jobOutcome, exceptionCount: 1,
      events: [{type: "EXCEPTION", attributes: {exceptionType: "com.chargebee.jobs.IgnoreJobSignal"}}]})])[0];
    assert.equal(request.outcome, outcome);
    assert.equal(request.hasErrors, outcome === "error");
    assert.equal(request.hasExceptions, true);
    assert.equal(request.label, "compute_billing_metrics");
    assert.equal(request.jobId, "123");
    assert.equal(model.filterRequests([request], {channel: "SCHEDULER", outcome, query: "123"}, NOW).length, 1);
  }
  assert.equal(model.groupTraces([job({httpStatus: 200})])[0].outcome, "unknown");
  assert.equal(model.groupTraces([job({jobOutcome: "SKIPPED"}), child({httpStatus: 500})])[0].outcome, "error");
  for (const jobOutcome of ["SUCCEEDED", "SKIPPED"]) {
    assert.equal(model.groupTraces([job({jobOutcome, parentSpanId: "unobserved"})])[0].outcome, "unknown");
  }
  for (const jobOutcome of ["SUCCEEDED", "SKIPPED", "FAILED", "RUNNING", "UNKNOWN"]) {
    assert.equal(model.groupTraces([row({jobOutcome})])[0].outcome, "success");
    assert.equal(model.groupTraces([row({httpStatus: null, attributes: {jobOutcome}})])[0].outcome, "unknown");
  }
});

test("job metadata normalizes raw attributes without mutating or interpreting audit status", () => {
  const attrs = {jobType: "compute_billing_metrics", jobId: 42, scheduler: "chargebee", jobOutcome: "skipped",
    jobAuditStatus: "rescheduled", jobFailureType: null, secretPayload: "excluded from summary"};
  const request = model.groupTraces([row({entryChannel: "SCHEDULER", httpStatus: null, attributes: attrs})])[0];
  assert.equal(request.outcome, "skipped");
  assert.equal(request.jobOutcome, "SKIPPED");
  assert.equal(request.jobAuditStatus, "RESCHEDULED");
  assert.equal(request.jobId, "42");
  assert.equal(request.jobFailureType, null);
  assert.equal(attrs.jobOutcome, "skipped");
  assert.equal(model.filterRequests([request], {channel: "SCHEDULER", query: "compute_billing_metrics"}, NOW).length, 1);
});
