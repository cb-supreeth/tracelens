"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const store = require("../tracelens_cli/web/browser-store.js");
const sample = (extra = {}) => ({ id: "request-a", name: "Pinned request", traceId: "trace-a",
  request: { segmentId: "a", service: "cb-app", httpMethod: "GET", path: "/features" },
  journey: { links: [{ from: "a", to: "b" }] },
  segments: [{ segmentId: "a", events: [{ type: "LINE", line: 42 }] }], ...extra });

test("names are trimmed, control-free and capped in Unicode characters", () => {
  assert.equal(store.sanitizeName(" \u202eA\nB\t "), "AB");
  assert.equal(Array.from(store.sanitizeName("🙂".repeat(120))).length, 100);
  assert.throws(() => store.sanitizeName(" \n "), { code: "INVALID_NAME" });
});

test("full snapshots preserve events without including them in list summaries", () => {
  const input = sample({ request: { segmentId: "a", events: [{ sensitive: "not duplicated" }] } });
  const prepared = store.preparePin(input, 1234);
  assert.equal(prepared.snapshot.capturedAt, 1234);
  assert.deepEqual(JSON.parse(prepared.json).segments, input.segments);
  assert.equal(prepared.summary.segmentCount, 1);
  assert.equal(prepared.summary.request.events, undefined);
  assert.equal(prepared.summary.segments, undefined);
  assert.equal(prepared.summary.journey, undefined);
  input.segments[0].events[0].line = 99;
  assert.equal(prepared.snapshot.segments[0].events[0].line, 42);
});

test("JSON byte budget measures UTF8, including multibyte source lines", () => {
  assert.equal(store.byteLength("aé🙂"), 7);
  const prepared = store.preparePin(sample(), 1234);
  assert.equal(prepared.summary.sizeBytes, Buffer.byteLength(prepared.json, "utf8"));
  assert.throws(() => store.preparePin(sample({ segments: [{ source: "x".repeat(store.CONSTANTS.MAX_PIN_BYTES) }] })),
    { code: "PIN_TOO_LARGE" });
});

test("logical request display fields survive summaries without root or segment events", () => {
  const logical = { key: "trace:abc", traceId: "abc", label: "Features", method: "GET", path: "/features",
    user: "alice", site: "site-42", services: ["cb-app", "cb-ems"], channel: "HTTP",
    startedAtMillis: 1000, lastActivityMillis: 1200, durationMs: 200, httpStatus: 200,
    outcome: "success", hasErrors: false, eventCount: 800, rootMissing: false, rootAmbiguous: false,
    root: { events: [{ line: 8 }] }, segments: [{ events: [{ line: 9 }] }] };
  const summary = store.preparePin(sample({ request: logical }), 1234).summary.request;
  const { root, segments, ...display } = logical;
  assert.deepEqual(summary, display);
  logical.services.push("mutated");
  assert.deepEqual(summary.services, ["cb-app", "cb-ems"]);
  assert.deepEqual(store.requestSummary({ method: "GET" }).services, []);
});

test("scheduler snapshot summaries retain approved job metadata, not raw attributes", () => {
  const job = {channel: "SCHEDULER", outcome: "skipped", hasErrors: false,
    jobType: "compute_billing_metrics", jobId: "42", jobOutcome: "SKIPPED", jobAuditStatus: "IGNORED",
    jobFailureType: null, scheduler: "chargebee"};
  const request = {...job, attributes: {secret: "not summary data"}, root: {events: [{type: "EXCEPTION"}]}};
  const prepared = store.preparePin(sample({request}), 1234);
  assert.deepEqual(prepared.summary.request, {...job, services: []});
  assert.equal(prepared.snapshot.request.attributes.secret, "not summary data");
});

test("malformed or circular snapshots and filters never silently lose data", () => {
  const cycle = {}; cycle.self = cycle;
  assert.throws(() => store.preparePin(sample({ journey: cycle })), { code: "INVALID_DATA" });
  assert.throws(() => store.preparePin(sample({ capturedAt: "yesterday" })), { code: "INVALID_DATA" });
  assert.throws(() => store.preparePin(sample({ segments: {} })), { code: "INVALID_DATA" });
  assert.throws(() => store.validateFilters({ value: NaN }), { code: "INVALID_DATA" });
  assert.throws(() => store.validateFilters({ value: undefined }), { code: "INVALID_DATA" });
  assert.throws(() => store.validateFilters({ value: "x".repeat(20 * 1024) }), { code: "INVALID_FILTERS" });
  assert.deepEqual(store.validateFilters({ service: "cb-app", method: "GET" }), { service: "cb-app", method: "GET" });
});

test("pin count and total byte limits allow replacing the same stable request id", () => {
  const prepared = store.preparePin(sample(), 1234);
  const rows = Array.from({ length: 20 }, (_, index) => ({ id: "request-" + index, sizeBytes: 100 }));
  assert.throws(() => store.validatePinBudget(rows, prepared), { code: "PIN_LIMIT" });
  rows[0].id = prepared.summary.id;
  assert.equal(store.validatePinBudget(rows, prepared), 1900 + prepared.summary.sizeBytes);
  const full = [{ id: "other", sizeBytes: store.CONSTANTS.MAX_TOTAL_BYTES }];
  assert.throws(() => store.validatePinBudget(full, prepared), { code: "TOTAL_LIMIT" });
  assert.equal(store.validatePinBudget([{ id: prepared.summary.id, sizeBytes: store.CONSTANTS.MAX_TOTAL_BYTES }], prepared),
    prepared.summary.sizeBytes);
});

test("unavailable IndexedDB is an explicit rejection, never a lossy fallback", async () => {
  const unavailable = store.create("viewer-a", { indexedDB: null });
  await assert.rejects(unavailable.listPins(), { code: "UNAVAILABLE" });
  await assert.rejects(unavailable.savePin(sample()), { code: "UNAVAILABLE" });
  assert.throws(() => store.create(""), { code: "INVALID_ID" });
});
