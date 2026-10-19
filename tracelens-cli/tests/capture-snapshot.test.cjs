"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const model = require("../tracelens_cli/web/request-model.js");
const browserStore = require("../tracelens_cli/web/browser-store.js");

const TRACE = "1234567890abcdef1234567890abcdef";
function segment(id, service, extra = {}) {
  return { segmentId: id, service, serviceInstanceId: service + "-instance", traceId: TRACE,
    segmentKey: JSON.stringify([service, service + "-instance", id]),
    entryChannel: "HTTP", httpMethod: "GET", path: "/features", lensId: "Features",
    user: "alice_42", siteId: "42", status: "COMPLETED", httpStatus: 200,
    startedAtMillis: 1000, lastActivityMillis: 1200, durationMs: 200, eventCount: 1,
    ...extra };
}
function response(doc, status = 200) {
  const bytes = new TextEncoder().encode(JSON.stringify(doc));
  return { ok: status >= 200 && status < 300, status,
    json: async () => JSON.parse(JSON.stringify(doc)),
    body: { getReader() {
      let read = false;
      return { async read() {
        if (read) return { done: true };
        read = true; return { done: false, value: bytes };
      }, async cancel() {} };
    } } };
}
function clone(value) { return JSON.parse(JSON.stringify(value)); }

/** Run the actual SPA wiring without a browser, timers, or any network. */
async function harness(discovery, exports, discoveryStatus = 200, settings = {}) {
  let options;
  const federated = settings.federated !== false;
  const nodes = new Map();
  const requests = [];
  const context = {
    window: { TraceLensRequests: model, TraceLensBrowserStore: browserStore,
      TraceLensRequestUI: class {
        constructor(value) { options = value; }
        init() {} setServices() {} setTraces() {}
      } },
    document: {
      querySelector(selector) {
        if (!nodes.has(selector)) nodes.set(selector, { classList: { toggle() {}, add() {}, remove() {} } });
        return nodes.get(selector);
      },
      querySelectorAll() { return []; },
    },
    TextEncoder, TextDecoder, URLSearchParams,
    setInterval() { return 0; },
    async fetch(path, config) {
      requests.push({ path, method: config.method });
      const url = new URL(path, "http://fixture.invalid");
      if (url.pathname === "/api/summary") return response({ federated, readOnly: federated,
        services: [], configuredServices: ["cb-app", "cb-ems"], traceCount: 0 });
      if (url.pathname === "/api/traces") return response({ traces: [] });
      if (url.pathname === "/api/distributed-trace/" + TRACE) return response(discovery, discoveryStatus);
      if (url.pathname.startsWith("/api/render-data/")) {
        const id = decodeURIComponent(url.pathname.slice("/api/render-data/".length));
        const composite = JSON.stringify([url.searchParams.get("service"), url.searchParams.get("instance"), id]);
        const exported = exports[composite] || exports[id];
        if (!exported) return response({ error: "not retained" }, 404);
        assert.equal(url.searchParams.get("service"), federated ? exported.service : null);
        assert.equal(url.searchParams.get("instance"), exported.serviceInstanceId);
        return response(exported);
      }
      throw new Error("Unexpected fixture request: " + path);
    },
  };
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/app.js"), "utf8"), context);
  // Complete the SPA's initial read-only status/list tick before invoking capture.
  await new Promise(resolve => setImmediate(resolve));
  return { capture: async request => clone(await options.snapshot(request)), requests };
}

const app = segment("app-segment", "cb-app");
const ems = segment("ems-segment", "cb-ems", { parentSpanId: "1234567890abcdef" });
function exportsFor(rows) {
  return Object.fromEntries(rows.map((row, index) => [row.segmentId, { ...row,
    events: [{ type: "LINE", line: index + 10, sourceLine: "saved source " + row.segmentId }] }]));
}

function visualizationFor(rows) {
  return { schemaVersion: 1, kind: "request-flow", traceId: TRACE,
    partial: false, completeness: "observed-only", note: "Observed evidence only",
    warnings: [], errors: [], nodes: rows.map((row, index) => ({id: "node-" + index,
      kind: "segment", service: row.service, segmentId: row.segmentId,
      serviceInstanceId: row.serviceInstanceId, segmentKey: row.segmentKey})),
    edges: [], roots: rows.map((_, index) => "node-" + index) };
}

test("an ordinary federated pin preserves the exact canonical discovery graph", async () => {
  const rows = [app, ems];
  const visualization = visualizationFor(rows);
  const discovery = {traceId: TRACE, segments: rows, links: [], httpCalls: [],
    errors: [], warnings: [], partial: false, completeness: "observed-only", visualization};
  const fixture = await harness(discovery, exportsFor(rows));
  const result = await fixture.capture(model.groupTraces(rows)[0]);
  assert.equal(result.partial, false);
  assert.deepEqual(result.journey.visualization, visualization);
  assert.equal(result.segments.length, 2);
  assert.equal(fixture.requests.filter(call => call.path.startsWith("/api/render-data/")).length, 2);
});

test("single-service application and default discovery aliases retain one saved segment and its graph", async () => {
  const raw = segment("single-segment", undefined, {applicationId: "billing-app", serviceInstanceId: "single-instance"});
  delete raw.segmentKey;
  const tagged = {...raw, service: "default", segmentKey: JSON.stringify(["default", raw.serviceInstanceId, raw.segmentId])};
  const visualization = visualizationFor([tagged]);
  const discovery = {traceId: TRACE, segments: [tagged], links: [], httpCalls: [],
    errors: [], warnings: [], partial: false, completeness: "observed-only", visualization};
  const fixture = await harness(discovery, exportsFor([raw]), 200, {federated: false});
  const request = model.groupTraces([raw])[0];
  assert.equal(request.segments[0].service, "billing-app");
  const result = await fixture.capture(request);
  assert.equal(result.partial, false);
  assert.equal(result.segments.length, 1);
  assert.equal(result.segments[0].service, "default");
  assert.equal(result.journey.segments.length, 1);
  assert.deepEqual(result.journey.visualization, visualization);
  assert.equal(fixture.requests.filter(call => call.path.startsWith("/api/render-data/")).length, 1);
});

test("a failed source export retains observed graph evidence with partial snapshot warnings", async () => {
  const rows = [app, ems];
  const visualization = visualizationFor(rows);
  const discovery = {traceId: TRACE, segments: rows, links: [], httpCalls: [],
    errors: [], warnings: [], partial: false, completeness: "observed-only", visualization};
  const fixture = await harness(discovery, exportsFor([app]));
  const result = await fixture.capture(model.groupTraces(rows)[0]);
  assert.equal(result.partial, true);
  assert.equal(result.segments.length, 1);
  assert.deepEqual(result.journey.visualization.nodes, visualization.nodes);
  assert.deepEqual(result.journey.visualization.edges, visualization.edges);
  assert.equal(result.journey.visualization.partial, true);
  assert.equal(result.journey.visualization.completeness, "partial snapshot");
  assert.ok(result.journey.visualization.warnings.some(warning => warning.includes("ems-segment could not be saved")));
});

test("federated service identity is retained even when segment and instance IDs collide", async () => {
  const rows = ["cb-app", "cb-ems"].map(service => segment("shared-segment", service,
    {serviceInstanceId: "shared-instance", segmentKey: JSON.stringify([service, "shared-instance", "shared-segment"])}));
  const visualization = visualizationFor(rows);
  const discovery = {traceId: TRACE, segments: rows, links: [], httpCalls: [],
    errors: [], warnings: [], partial: false, completeness: "observed-only", visualization};
  const exports = Object.fromEntries(rows.map(row => [JSON.stringify([row.service, row.serviceInstanceId, row.segmentId]),
    {...row, events: [{type: "LINE", sourceLine: row.service + " source"}]}]));
  const fixture = await harness(discovery, exports);
  const result = await fixture.capture(model.groupTraces(rows)[0]);
  assert.equal(result.partial, false);
  assert.equal(result.segments.length, 2);
  assert.deepEqual(result.segments.map(row => row.service).sort(), ["cb-app", "cb-ems"]);
  assert.ok(result.segments.every(row => row.events[0].sourceLine === row.service + " source"));
  assert.deepEqual(result.journey.visualization, visualization);
});

for (const scenario of ["failed", "empty"]) {
  test(scenario + " link discovery retains every known segment in an unlinked partial snapshot", async () => {
    const rows = [app, ems];
    const request = model.groupTraces(rows)[0];
    const discovery = scenario === "failed" ? { error: "offline" } :
      { traceId: TRACE, segments: [], links: [], httpCalls: [], warnings: [], errors: [], partial: false };
    const fixture = await harness(discovery, exportsFor(rows), scenario === "failed" ? 503 : 200);
    const result = await fixture.capture(request);
    assert.equal(result.partial, true);
    assert.equal(result.journey.partial, true);
    assert.equal(result.journey.completeness, "partial snapshot");
    assert.deepEqual(result.journey.links, []);
    assert.deepEqual(result.journey.httpCalls, []);
    assert.deepEqual(result.segments.map(row => row.segmentId).sort(), ["app-segment", "ems-segment"]);
    assert.deepEqual(result.journey.segments.map(row => row.segmentId).sort(), ["app-segment", "ems-segment"]);
    assert.ok(result.journey.segments.every(row => row.segmentKey && !row.events));
    assert.ok(result.segments.every(row => row.events[0].sourceLine === "saved source " + row.segmentId));
    assert.ok(result.warnings.some(warning => warning.includes("shown independently")));
    assert.equal(result.request.key, request.key);
    assert.ok(fixture.requests.every(call => call.method === "GET"));
  });
}

test("a known segment absent from successful discovery stays accessible without fabricated links", async () => {
  const extra = segment("extra-segment", "cb-plans", { parentSpanId: "abcdefabcdefabcd" });
  const rows = [app, ems, extra];
  const originalLinks = [{ fromCallKey: "call-a", fromSegmentKey: app.segmentKey, toSegmentKey: ems.segmentKey }];
  const originalCalls = [{ callKey: "call-a", ownerSegmentKey: app.segmentKey, spanId: ems.parentSpanId,
    downstreamSegmentKeys: [ems.segmentKey], httpMethod: "GET", path: "/features" }];
  const discovery = { traceId: TRACE, segments: [app, ems], links: originalLinks,
    httpCalls: originalCalls, warnings: [], errors: [], partial: false, completeness: "observed-only",
    visualization: visualizationFor([app, ems]) };
  const fixture = await harness(discovery, exportsFor(rows));
  const result = await fixture.capture(model.groupTraces(rows)[0]);
  assert.equal(result.partial, true);
  assert.equal(result.segments.length, 3);
  assert.equal(result.journey.segments.length, 3);
  assert.deepEqual(result.journey.links, originalLinks);
  assert.deepEqual(result.journey.httpCalls, originalCalls);
  const independent = result.journey.segments.find(row => row.segmentId === extra.segmentId);
  assert.ok(independent.segmentKey);
  assert.ok(!result.journey.links.some(link => link.toSegmentKey === independent.segmentKey));
  assert.ok(result.segments.find(row => row.segmentId === independent.segmentId).events.length);
  assert.ok(result.warnings.some(warning => warning.includes("absent from link discovery")));
  assert.equal(result.journey.visualization, undefined, "a stale graph must not omit a genuinely additional saved segment");
  assert.equal(fixture.requests.filter(call => call.path.startsWith("/api/render-data/")).length, 3);
});
