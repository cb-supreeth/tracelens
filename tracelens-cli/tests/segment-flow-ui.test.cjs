"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const dependencies = require("../tracelens_cli/web/dependency-ui.js");

const settle = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); };
const response = (doc, status = 200) => ({ok: status === 200, status, json: async () => doc});
function segment(id = "one") {
  return {segmentId: id, service: "cb-app", serviceInstanceId: "historical-instance", traceId: "trace",
    status: "COMPLETED", eventCount: 3, user: "private-user", attributes: {secret: "private-context"},
    events: [
      {type: "METHOD_ENTER", seq: 1, thread: "worker", className: "Feature", methodName: "update", nanoTime: 1},
      {type: "LINE", seq: 2, thread: "worker", className: "Feature", line: 20, nanoTime: 2,
        sourceLine: "saved source", message: "private-message", attributes: {payload: "private-payload"}},
      {type: "METHOD_EXIT", seq: 3, thread: "worker", className: "Feature", methodName: "update", nanoTime: 3}
    ]};
}
const model = id => ({kind: "segment-execution", lanes: [], nodes: [], segment: {segmentId: id}});

async function harness(compute = input => response(model(input.trace.segmentId)), retrieve, settings = {}) {
  const elements = new Map(), calls = [], renders = [], journeys = [], disposedRenderers = [];
  const listeners = {}, copied = [], timers = [];
  let options, requestUI, summary = settings.summary, cleanups = 0;
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase();
      this.childNodes = []; this.dataset = {}; this.attributes = {}; this.value = "all"; this.htmlWrites = [];
      const classes = new Set();
      this.classList = {add: value => classes.add(value), remove: value => classes.delete(value),
        contains: value => classes.has(value), toggle: (value, enabled) => enabled ? classes.add(value) : classes.delete(value)};
    }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(node => node.textContent).join(""); }
    set innerHTML(value) { this.htmlWrites.push(String(value)); this.textContent = value; }
    append(...nodes) { this.childNodes.push(...nodes); }
    appendChild(node) { this.append(node); }
    replaceChildren(...nodes) { this.text = ""; this.childNodes = nodes; }
    setAttribute(key, value) { this.attributes[key] = value; }
    querySelector(selector) {
      const found = /^\[data-(event-index|source-event-index)="(\d+)"\]$/.exec(selector);
      if (!found) throw new Error("Unknown fixture selector " + selector);
      return this.childNodes.find(node => node.dataset[found[1] === "event-index" ? "eventIndex" : "sourceEventIndex"] === found[2]);
    }
    focus() { this.focused = true; }
    scrollIntoView() { this.scrolled = true; }
  }
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
  };
  const banners = [new Element(), new Element()];
  banners.forEach(banner => banner.classList.add("hidden"));
  const context = {
    document: {querySelector: element, querySelectorAll: selector => selector === ".snapshot-banner" ? banners : [], createElement: tag => new Element(tag)},
    window: {TraceLensDependencies: dependencies,
      TraceLensBoundarySource: require("../tracelens_cli/web/boundary-source.js"),
      TraceLensRequestData: require("../tracelens_cli/web/request-data.js"),
      TraceLensCacheEvidence: require("../tracelens_cli/web/cache-evidence.js"),
      TraceLensRequests: require("../tracelens_cli/web/request-model.js"),
      TraceLensViewerStore: require("../tracelens_cli/web/viewer-store.js"),
      TraceLensLargeTrace: require("../tracelens_cli/web/large-trace.js"),
      addEventListener(name, handler) { listeners[name] = handler; },
      TraceLensRequestUI: class {
        constructor(value) {
          options = value; requestUI = this; this.mode = settings.mode || "live";
          this.homeCalls = 0; this.errorRefreshes = 0; this.filters = {query: "preserved query", service: "cb-app"};
        }
        init() { options.viewerContext?.({capabilities: settings.capabilities || {}}); } setServices(value) { this.services = value; } setTraces() {} refreshStored() {}
        refreshErrors() { this.errorRefreshes++; }
        goHome() { this.homeCalls++; this.mode = "live"; }
      },
      TraceLensCodeFlow: {render(container, output, config) {
        renders.push({renderer: "flow", container, output, config});
        return () => { cleanups++; disposedRenderers.push("flow"); };
      }},
      TraceLensExecutionGraph: {render(container, output, config) {
        renders.push({renderer: "tree", container, output, config});
        return () => { cleanups++; disposedRenderers.push("tree"); };
      }},
      TraceLensGraph: {render(container, output, config) {
        journeys.push({container, output, config}); return () => {};
      }}},
    URLSearchParams, TextEncoder, setInterval(fn) { timers.push(fn); },
    navigator: {clipboard: {async writeText(value) { copied.push(value); }}},
    async fetch(path, config) {
      calls.push({path, ...config});
      if (settings.retrieveDetail && (/\/api\/segment-(overview|events|request-data|execution)\//.test(path)
        || /\/api\/snapshots\/[^/]+\/segments\//.test(path))) return settings.retrieveDetail(path, config);
      if (path.startsWith("/api/snapshots/")) return settings.retrieveSnapshot(path);
      if (path === "/api/summary") return typeof summary === "function" ? summary()
        : response(summary || {services: [], configuredServices: [], federated: true});
      if (path === "/api/traces") return response({traces: []});
      if (path === "/api/segment-execution") return compute(JSON.parse(config.body));
      if (path.startsWith("/api/render-data/")) {
        if (retrieve) return retrieve(path);
        const params = new URLSearchParams(path.split("?")[1]);
        return response({...segment(path.split("/").pop().split("?")[0]),
          service: params.get("service") || "cb-app", serviceInstanceId: params.get("instance") || "historical-instance"});
      }
      if (path.startsWith("/api/distributed-trace/")) return response(settings.journey || journey());
      throw new Error("Unexpected upstream call " + path);
    }
  };
  if (settings.hash !== undefined) {
    const location = context.window.location = {pathname: "/", search: "", hash: settings.hash,
      href: "http://127.0.0.1:7781/" + settings.hash};
    context.window.history = {replaceState(_, __, path) {
      location.hash = path.includes("#") ? path.slice(path.indexOf("#")) : "";
      location.href = "http://127.0.0.1:7781" + path;
    }};
  }
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/app.js"), "utf8"), context);
  await settle();
  return {options, requestUI, calls, renders, journeys, disposedRenderers, element, copied, timers, banners, location: context.window.location,
    setSummary(value) { summary = value; },
    async hash(value) {
      context.window.location.hash = value;
      context.window.location.href = "http://127.0.0.1:7781/" + value;
      listeners.hashchange(); await settle();
    }, get cleanups() { return cleanups; }};
}

function journey() {
  return {traceId: "trace", segments: [
    {...segment("root"), segmentKey: "root-key"},
    {...segment("ems"), service: "cb-ems", serviceInstanceId: "ems-instance", segmentKey: "ems-key"}
  ], links: [], httpCalls: [], visualization: {kind: "request-flow", nodes: [], edges: []}};
}

function timedJourney() {
  const doc = journey(), owner = doc.segments[0];
  doc.httpCalls = [0, 1].map(index => ({callKey: `root-key:call:${index}`, ownerSegmentKey: owner.segmentKey,
    service: owner.service, serviceInstanceId: owner.serviceInstanceId, traceId: doc.traceId,
    httpMethod: "GET", path: `/call-${index}`, peer: "api.internal:8080", status: "COMPLETED", downstreamSegmentKeys: [],
    startedAt: String(1000 + index * 100), durationNanos: "10000000", timingBoundary: "response-headers"}));
  doc.visualization = {kind: "request-flow", traceId: doc.traceId, roots: ["owner"], nodes: [
    {id: "owner", kind: "segment", service: owner.service, serviceInstanceId: owner.serviceInstanceId,
      segmentId: owner.segmentId, segmentKey: owner.segmentKey},
    ...doc.httpCalls.map((call, index) => ({id: `call-${index}`, kind: "http-call", ownerId: "owner",
      callKey: call.callKey, segmentId: owner.segmentId, segmentKey: owner.segmentKey,
      service: owner.service, serviceInstanceId: owner.serviceInstanceId, method: call.httpMethod,
      path: call.path, peer: call.peer, status: call.status, downstreamState: "not-observed", durationMs: 10}))],
    edges: doc.httpCalls.map((_, index) => ({id: `out-${index}`, from: "owner", to: `call-${index}`, kind: "outbound"}))};
  return doc;
}

test("older saved graphs recover exact caller-local HTTP timing only in their displayed projection", async () => {
  const doc = timedJourney(), pin = {name: "Timed calls", journey: doc, segments: doc.segments};
  const before = JSON.stringify(pin), h = await harness(), callCount = h.calls.length;
  await h.options.openPin(pin); await settle();
  const displayed = h.journeys.at(-1).output;
  assert.notEqual(displayed, doc.visualization);
  assert.equal(displayed.nodes[0], doc.visualization.nodes[0]);
  assert.equal(displayed.edges, doc.visualization.edges, "timing never creates or changes a causal edge");
  assert.equal(displayed.nodes[1].startedAtMillis, 1000);
  assert.equal(displayed.nodes[2].startedAtMillis, 1100);
  assert.equal(displayed.nodes[1].timingBoundary, "response-headers");
  assert.equal(displayed.nodes[1].callKey, doc.visualization.nodes[1].callKey);
  const order = require("../tracelens_cli/web/trace-graph.js").httpCallOrder(displayed);
  assert.equal(order.get("call-0").label, "1"); assert.equal(order.get("call-1").label, "2");
  assert.equal(h.calls.length, callCount, "historical timing comes from the saved journey, not a live service");
  assert.equal(JSON.stringify(pin), before);
});

test("saved timing enrichment refuses ambiguous or contradictory exact call and owner identities", async () => {
  for (const [name, mutate] of [
    ["duplicate raw key", doc => doc.httpCalls.push({...doc.httpCalls[0]})],
    ["duplicate graph key", doc => { doc.visualization.nodes.push({...doc.visualization.nodes[1], id: "duplicate"}); }],
    ["duplicate saved owner", doc => doc.segments.push({...doc.segments[0]})],
    ["wrong instance", doc => { doc.httpCalls[0].serviceInstanceId = "another-instance"; }],
    ["wrong service", doc => { doc.httpCalls[0].service = "another-service"; }],
    ["wrong owner key", doc => { doc.httpCalls[0].ownerSegmentKey = "ems-key"; }],
    ["wrong graph owner", doc => { doc.visualization.nodes[1].ownerId = "missing"; }],
    ["wrong graph segment", doc => { doc.visualization.nodes[1].segmentId = "different"; }],
    ["wrong raw trace", doc => { doc.httpCalls[0].traceId = "other-trace"; }],
    ["wrong graph trace", doc => { doc.visualization.traceId = "other-trace"; }],
    ["changed path", doc => { doc.httpCalls[0].path = "/different"; }],
    ["changed method", doc => { doc.httpCalls[0].httpMethod = "POST"; }],
    ["changed peer", doc => { doc.httpCalls[0].peer = "other.internal:8080"; }],
    ["changed completion", doc => { doc.httpCalls[0].status = "ACTIVE"; }],
    ["missing ownership edge", doc => { doc.visualization.edges.splice(0, 1); }],
    ["duplicate ownership edge", doc => { doc.visualization.edges.push({...doc.visualization.edges[0], id: "duplicate"}); }],
  ]) {
    const doc = timedJourney(); mutate(doc);
    const pin = {name, journey: doc, segments: doc.segments}, before = JSON.stringify(pin), h = await harness();
    const readsBefore = h.calls.length; await h.options.openPin(pin); await settle();
    assert.equal(h.journeys.at(-1).output.nodes.find(node => node.id === "call-0").startedAtMillis, undefined, name);
    assert.equal(h.calls.length, readsBefore, name); assert.equal(JSON.stringify(pin), before, name);
  }
});

test("timing enrichment keeps existing graph evidence and validates legacy numeric timing", async () => {
  for (const [name, value, expected] of [
    ["integer string", "1200", 1200], ["integer", 1200, 1200], ["fractional start", 1200.5, undefined],
    ["negative start", -1, undefined], ["boolean start", true, undefined], ["empty start", "", undefined],
    ["nonfinite start", Infinity, undefined], ["unsafe start", Number.MAX_SAFE_INTEGER + 1, undefined],
  ]) {
    const doc = timedJourney(); doc.httpCalls[0].startedAt = value;
    delete doc.visualization.nodes[1].durationMs;
    doc.visualization.nodes[2].startedAtMillis = 99;
    const h = await harness(); await h.options.openPin({name, journey: doc, segments: doc.segments});
    const shown = h.journeys.at(-1).output;
    assert.equal(shown.nodes[1].startedAtMillis, expected, name); assert.equal(shown.nodes[1].durationMs, 10, name);
    assert.equal(shown.nodes[2].startedAtMillis, 99, "an existing graph timestamp is never overwritten");
  }
});

test("HTTP calling-code action opens the exact caller timeline for live and saved service/external calls", async () => {
  for (const saved of [false, true]) {
    const doc = journey(), caller = doc.segments[0];
    caller.events.forEach(event => { event.className = "com.example.FeatureClient"; });
    const raw = {spanId: "abcdef0123456789", httpMethod: "POST", path: "/features/search", peer: "localhost:8082",
      callingContextStatus: "recorded", callingEventSeq: "2", callingThread: "worker"};
    caller.httpCalls = [raw];
    const call = {...raw, downstreamSegmentKeys: [], callKey: "root-key:call:0", ownerSegmentKey: caller.segmentKey,
      service: caller.service, serviceInstanceId: caller.serviceInstanceId};
    doc.httpCalls = [call];
    const node = {kind: "http-call", callKey: call.callKey, segmentId: caller.segmentId,
      segmentKey: caller.segmentKey, service: caller.service, serviceInstanceId: caller.serviceInstanceId};
    const h = await harness(undefined, () => response(caller), {journey: doc});
    if (saved) await h.options.openPin({name: "HTTP", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: doc.traceId});
    h.journeys.at(-1).config.onOpenBoundary(node); await settle();
    assert.equal(h.element("#segment-service-name").textContent, "cb-app",
      "calling code belongs to the captured caller, not the destination service");
    assert.equal(h.element("#segment-toolbar-service").textContent, "cb-app");
    assert.match(h.element("#boundary-code-context").textContent, /FeatureClient.update\(\):20/);
    assert.equal(h.element("#timeline").querySelector('[data-event-index="1"]').focused, true);
    assert.equal(h.element("#execution-timeline-tab").attributes["aria-pressed"], "true");
    const reads = h.calls.filter(call => call.path.startsWith("/api/render-data/"));
    assert.equal(reads.length, saved ? 0 : 1);
    if (!saved) assert.match(reads[0].path, /root\?service=cb-app&instance=historical-instance/);
  }
});

test("HTTP calling code does not invent an anchor for old evidence or substitute repeated/missing calls", async () => {
  for (const invalid of ["legacy", "duplicate", "missing", "different-path"]) {
    const doc = journey(), caller = doc.segments[0];
    const raw = {spanId: "abcdef0123456789", httpMethod: "POST", path: "/", peer: "secrets.example"};
    caller.httpCalls = invalid === "missing" ? [] : invalid === "duplicate" ? [raw, {...raw}] : [raw];
    doc.httpCalls = [{...raw, downstreamSegmentKeys: [], path: invalid === "different-path" ? "/different" : "/", callKey: "root-key:call:0",
      ownerSegmentKey: caller.segmentKey, service: caller.service, serviceInstanceId: caller.serviceInstanceId}];
    const h = await harness(undefined, undefined, {journey: doc});
    await h.options.openPin({name: "Old HTTP", journey: doc, segments: doc.segments});
    h.journeys.at(-1).config.onOpenBoundary({kind: "http-call", callKey: "root-key:call:0",
      segmentId: caller.segmentId, segmentKey: caller.segmentKey, service: caller.service,
      serviceInstanceId: caller.serviceInstanceId}); await settle();
    assert.match(h.element("#journey-meta").textContent, /Calling code in cb-app unavailable/);
    assert.match(h.element("#journey-meta").textContent,
      invalid === "legacy" ? /location was not captured/ : /No other call or source line was substituted/);
    assert.equal(h.element("#journey-view").classList.contains("hidden"), false,
      "missing caller evidence must not navigate into an unrelated timeline");
    assert.equal(h.element("#timeline").textContent, "");
    assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false);
  }
});

test("flow request data lazily reads only the exact live or saved service invocation without navigation", async () => {
  for (const saved of [false, true]) {
    const doc = journey(), trace = doc.segments[1];
    Object.assign(trace.attributes, {httpQueryParametersStatus: "captured", httpQueryParameters: '{"limit":["PRIVATE-QUERY"]}'});
    const h = await harness(undefined, () => response(trace), {journey: doc});
    if (saved) await h.options.openPin({name: "Request data", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: doc.traceId});
    assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false);
    const config = h.journeys.at(-1).config;
    const result = await config.loadRequestData({kind: "segment", ...trace});
    assert.equal(result.attributes.httpQueryParameters, trace.attributes.httpQueryParameters);
    assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
    assert.equal(h.calls.some(call => call.path === "/api/segment-execution"), false);
    assert.doesNotMatch(JSON.stringify(h.journeys.at(-1).output), /PRIVATE-QUERY/);
    const reads = h.calls.filter(call => call.path.startsWith("/api/render-data/"));
    assert.equal(reads.length, saved ? 0 : 1);
    if (!saved) assert.match(reads[0].path, /ems\?service=cb-ems&instance=ems-instance/);
  }
});

test("flow request data refuses missing or duplicate saved segments and never substitutes live evidence", async () => {
  for (const duplicate of [false, true]) {
    const doc = journey(), target = doc.segments[1];
    const h = await harness();
    await h.options.openPin({name: "Incomplete", journey: doc,
      segments: duplicate ? [doc.segments[0], target, {...target}] : [doc.segments[0]]});
    await assert.rejects(h.journeys.at(-1).config.loadRequestData(target), /exact segment was not included/);
    assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false);
  }
});

test("flow request data rejects mismatched segment identities and expired live data", async () => {
  for (const mismatch of ["traceId", "segmentId", "service", "serviceInstanceId", "segmentKey", "expired"]) {
    const doc = journey(), target = doc.segments[1];
    const h = await harness(undefined, () => mismatch === "expired" ? response({error: "gone"}, 410)
      : response({...target, [mismatch]: "different"}), {journey: doc});
    await h.options.openRequest({traceId: doc.traceId});
    await assert.rejects(h.journeys.at(-1).config.loadRequestData(target),
      mismatch === "expired" ? /no longer retained/ : /does not match/);
    assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  }
});

test("late flow request-data reads cannot affect a newer navigation", async () => {
  const doc = journey(), target = doc.segments[1];
  let complete;
  const h = await harness(undefined, () => new Promise(resolve => { complete = resolve; }), {journey: doc});
  await h.options.openRequest({traceId: doc.traceId});
  const config = h.journeys.at(-1).config, pending = config.loadRequestData(target);
  const rejected = assert.rejects(pending, /request view changed/);
  await h.options.openRequest({traceId: doc.traceId});
  complete(response(target)); await rejected;
  await assert.rejects(config.loadRequestData(target), /request view changed/);
});

test("full segment request data is shown beside details but excluded from execution reconstruction", async () => {
  const trace = segment(); Object.assign(trace.attributes, {httpQueryParametersStatus: "captured",
    httpQueryParameters: '{"id":["PRIVATE-QUERY"]}', httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json",
    httpRequestBody: '{"id":9007199254740993,"description":"PRIVATE-BODY"}'});
  const h = await harness(undefined, () => response(trace)); await h.options.openSegment(trace); await settle();
  assert.match(h.element("#trace-meta").textContent, /Request data · captured/);
  assert.match(h.element("#trace-meta").textContent, /PRIVATE-QUERY/);
  assert.match(h.element("#trace-meta").textContent, /9007199254740993/);
  const request = h.calls.find(call => call.path === "/api/segment-execution");
  assert.doesNotMatch(request.body, /PRIVATE-QUERY|PRIVATE-BODY|httpRequestBody/);
});

test("native ClickHouse SQL and named bindings survive only the explicit execution projection", async () => {
  const trace = segment(); trace.events.push({seq: 4, type: "EXTERNAL_CALL", thread: "callback",
    attributes: {boundaryKind: "database", system: "clickhouse", operation: "query", direction: "outbound", status: "OBSERVED",
      evidenceKind: "clickhouse-query", sqlSource: "native-query", sql: "SELECT * FROM usage WHERE id={id:Int64}",
      sqlParametersStatus: "captured", sqlParameters: '[{"name":"id","type":"Long","status":"captured","value":"1"}]',
      clientThread: "request-worker", completionKind: "response-complete", durationMs: "2.5", resultRows: ["NEVER-RETAIN"]}});
  const h = await harness(undefined, () => response(trace)); await h.options.openSegment(trace); await settle();
  const attrs = JSON.parse(h.calls.find(call => call.path === "/api/segment-execution").body).trace.events[3].attributes;
  assert.equal(attrs.evidenceKind, "clickhouse-query"); assert.equal(attrs.sqlParameters[0].name, "id");
  assert.equal(attrs.completionKind, "response-complete"); assert.equal(attrs.clientThread, "request-worker");
  assert.doesNotMatch(JSON.stringify(attrs), /NEVER-RETAIN/);
  assert.match(h.element("#timeline").textContent, /Native ClickHouse query/);
});

test("local execution reconstruction and timeline admit bounded JDBC SQL only, as inert disclosed text", async () => {
  const rawSql = "select '<script>alert(1)</script>' from features where id = 'literal'";
  const attrs = {boundaryKind: "database", system: "postgresql", direction: "outbound", operation: "executeQuery",
    status: "OBSERVED", evidenceKind: "jdbc-execution", sql: rawSql, durationMs: "1.5", sqlSource: "prepared",
    parameters: ["NOT-ALLOWED"], resultRows: ["NOT-ALLOWED"]};
  const trace = segment(); trace.events.push(
    {type: "EXTERNAL_CALL", seq: 4, thread: "worker", attributes: attrs},
    {type: "EXTERNAL_CALL", seq: 5, thread: "worker", attributes: {...attrs, evidenceKind: "client-boundary", sql: "NOT-ALLOWED"}},
    {type: "DB_ROUTE", seq: 6, thread: "worker", attributes: {...attrs, sql: "NOT-ALLOWED"}},
    {type: "EXTERNAL_CALL", seq: 7, thread: "worker", attributes: {...attrs, evidenceKind: "jdbc-query-limit", operation: "query-limit",
      status: "TRUNCATED", queryLimit: 20, sql: "NOT-ALLOWED"}});
  const h = await harness(undefined, () => response(trace)); await h.options.openSegment(trace); await settle();
  const request = JSON.parse(h.calls.find(call => call.path === "/api/segment-execution").body);
  assert.equal(request.trace.events[3].attributes.sql, rawSql);
  assert.equal(request.trace.events[3].attributes.durationMs, 1.5);
  assert.equal(request.trace.events[4].attributes.sql, undefined);
  assert.equal(request.trace.events[5].attributes.sql, undefined);
  assert.equal(request.trace.events[6].attributes.sql, undefined);
  assert.equal(request.trace.events[6].attributes.queryLimit, 20);
  assert.doesNotMatch(JSON.stringify(request), /NOT-ALLOWED/);
  const all = node => [node, ...node.childNodes.flatMap(all)];
  const timeline = all(h.element("#timeline"));
  const sql = timeline.find(node => node.tagName === "PRE");
  assert.equal(sql.textContent, rawSql); assert.deepEqual(sql.htmlWrites, []);
  assert.equal(timeline.filter(node => node.tagName === "DETAILS").length, 2);
  assert.doesNotMatch(h.element("#timeline").textContent, /NOT-ALLOWED/);
  assert.match(h.element("#timeline").textContent, /SQL capture limit reached/);
});

test("default saved-snapshot flow keeps canonical JDBC query cards and SQL without live substitution", async () => {
  const traceId = "0123456789abcdef0123456789abcdef", rawSql = "select 'saved-literal'";
  const trace = {...segment("owner"), traceId, service: "cb-ems", segmentKey: "owner-key"};
  const query = {id: "query", kind: "dependency-call", ownerId: "owner", service: trace.service,
    segmentId: trace.segmentId, serviceInstanceId: trace.serviceInstanceId, segmentKey: trace.segmentKey,
    boundaryKind: "database", system: "postgresql", direction: "outbound", operation: "executeQuery",
    status: "OBSERVED", evidenceKind: "jdbc-execution", sql: rawSql, durationMs: 2, sqlSource: "prepared", eventSeq: 4};
  trace.events.push({type: "EXTERNAL_CALL", seq: 4, thread: "worker", attributes: {...query}});
  const doc = {traceId, segments: [{...trace, events: undefined}], links: [], httpCalls: [], externalCalls: [query],
    visualization: {schemaVersion: 1, kind: "request-flow", traceId, nodes: [{id: "owner", kind: "segment", service: trace.service,
      segmentId: trace.segmentId, segmentKey: trace.segmentKey, serviceInstanceId: trace.serviceInstanceId}, query],
      roots: ["owner"], edges: [{id: "out", from: "owner", to: "query", kind: "outbound"}], warnings: []}};
  const pin = {name: "Saved SQL capture", journey: doc, segments: [trace]}, before = JSON.stringify(pin), h = await harness();
  const callCount = h.calls.length; await h.options.openPin(pin); await settle();
  assert.notEqual(h.journeys.at(-1).config.showNodeDetails, false, "default graph inspector remains enabled");
  const savedQuery = h.journeys.at(-1).output.nodes.find(node => node.id === "query");
  assert.equal(savedQuery.sql, rawSql); assert.equal(savedQuery.sqlSource, "prepared"); assert.equal(savedQuery.eventSeq, 4);
  assert.equal(h.calls.length, callCount, "opening a historical query does not load live data");
  assert.equal(JSON.stringify(pin), before);
});

test("saved cache wrapper evidence adds a presentation card without altering the capture or querying a service", async () => {
  const traceId = "0123456789abcdef0123456789abcdef";
  const trace = {...segment("cache-owner"), traceId, service: "cb-ems", segmentKey: "cache-owner-key",
    events: [{type: "METHOD_ENTER", seq: 1, thread: "worker",
      className: "com.chargebee.ems.config.cache.ValkeyCacheService", methodName: "get"}]};
  const doc = {traceId, segments: [{...trace, events: undefined}], links: [], httpCalls: [], externalCalls: [],
    visualization: {schemaVersion: 1, kind: "request-flow", traceId, nodes: [
      {kind: "segment", id: "owner", service: trace.service, serviceInstanceId: trace.serviceInstanceId,
        segmentId: trace.segmentId, segmentKey: trace.segmentKey}
    ], roots: ["owner"], edges: [], warnings: []}};
  const pin = {name: "Older cache capture", journey: doc, segments: [trace]};
  const before = JSON.stringify(pin), h = await harness(), callsBefore = h.calls.length;
  await h.options.openPin(pin); await settle();
  const projected = h.journeys.at(-1).output;
  const cache = projected.nodes.find(node => node.boundaryKind === "cache");
  assert.ok(cache);
  assert.equal(cache.evidenceKind, "wrapper-entry");
  assert.equal(cache.ownerId, "owner");
  assert.equal(cache.eventSeq, 1);
  assert.equal(JSON.stringify(pin), before, "the immutable saved capture remains unchanged");
  assert.equal(h.calls.length, callsBefore, "historical display never substitutes live evidence");
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.ok(html.indexOf('src="/static/cache-evidence.js"') > html.indexOf('src="/static/dependency-ui.js"'));
  assert.ok(html.indexOf('src="/static/cache-evidence.js"') < html.indexOf('src="/static/app.js"'));
});

test("request back action precedes the API heading with a scroll-only sticky shortcut", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  const detail = html.split('<section id="detail-view"')[1];
  const navigation = detail.split('<nav id="segment-navigation"')[1].split('</nav>')[0];
  assert.ok(detail.indexOf('id="segment-navigation"') < detail.indexOf('id="trace-identity"'));
  for (const id of ["back", "view-journey"]) {
    assert.ok(navigation.includes(`id="${id}"`));
    assert.equal(html.split(`id="${id}"`).length - 1, 1);
  }
  const toolbar = html.split('<div id="segment-toolbar"')[1].split('<div id="execution-graph"')[0];
  assert.match(toolbar, /aria-label="Segment navigation and view controls"/);
  assert.match(toolbar, /<button id="segment-back" hidden>/);
  for (const id of ["segment-back", "execution-flow-tab", "execution-tree-tab",
    "execution-timeline-tab", "timeline-mode", "segment-top"]) {
    assert.ok(toolbar.includes(`id="${id}"`), id + " stays inside the toolbar");
    assert.equal(html.split(`id="${id}"`).length - 1, 1, id + " is not a duplicate control");
  }
  assert.match(html, /<script src="\/static\/sticky-toolbar.js"><\/script>/);
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/style.css"), "utf8");
  assert.match(css, /\.segment-toolbar\s*\{[^}]*position:\s*sticky;[^}]*top:\s*var\(--viewer-header-height\)/);
  assert.match(css, /scroll-margin-top:\s*calc\(var\(--viewer-header-height\) \+ var\(--segment-toolbar-height\) \+ 16px\)/);
});

test("top and sticky back actions have the same label and return to the same request", async () => {
  for (const control of ["#back", "#segment-back"]) {
    const h = await harness();
    await h.options.openRequest({traceId: "trace"}); await settle();
    h.journeys.at(-1).config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
    await settle();
    assert.equal(h.element("#segment-back").textContent, h.element("#back").textContent);
    assert.equal(h.element("#segment-back").onclick, h.element("#back").onclick);
    const calls = h.calls.length;
    h.element(control).onclick();
    assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
    assert.equal(h.element("#detail-view").classList.contains("hidden"), true);
    assert.equal(h.calls.length, calls);
  }
});

test("pinned timeline controls filter without fetching and Top returns to the segment header", async () => {
  const h = await harness();
  await h.options.openSegment(segment()); await settle();
  assert.equal(h.element("#timeline-controls").classList.contains("hidden"), true);
  h.element("#execution-timeline-tab").onclick();
  assert.equal(h.element("#timeline-controls").classList.contains("hidden"), false);
  const before = h.calls.length;
  h.element("#timeline-mode").value = "boundaries";
  h.element("#timeline-mode").onchange();
  assert.equal(h.element("#timeline-panel").scrolled, true);
  assert.equal(h.element("#timeline").childNodes.length, 0);
  h.element("#detail-view").scrolled = false;
  h.element("#segment-top").onclick();
  assert.equal(h.element("#detail-view").scrolled, true);
  assert.equal(h.calls.length, before, "scrolling/filtering does not query or change recording");
  h.element("#execution-flow-tab").onclick();
  assert.equal(h.element("#timeline-controls").classList.contains("hidden"), true);
  assert.equal(h.element("#execution-flow-tab").attributes["aria-pressed"], "true");
});

function apiJourney() {
  const traceId = "0123456789abcdef0123456789abcdef";
  const root = {...segment("root"), traceId, segmentKey: "root-key", lensId: "human-reproduction-label",
    entryChannel: "HTTP", attributes: {httpMethod: "GET", path: "/api/v2/features", httpStatus: 200}};
  const child = {...segment("ems"), traceId, service: "cb-ems", segmentKey: "ems-key", serviceInstanceId: "ems-instance",
    entryChannel: "HTTP", parentSpanId: "parent-span", attributes: {httpMethod: "POST", path: "/features/search", httpStatus: 200}};
  return {...journey(), traceId, partial: true, completeness: "partial", segments: [root, child],
    links: [{fromSegmentKey: "root-key", toSegmentKey: "ems-key"}]};
}

test("live and saved request headings prominently identify the captured root API rather than a reproduction label", async () => {
  for (const saved of [false, true]) {
    const doc = apiJourney(), before = JSON.stringify(doc), h = await harness(undefined, undefined, {journey: doc});
    const callsBefore = h.calls.length;
    if (saved) await h.options.openPin({name: "Saved", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: doc.traceId});
    const identity = h.element("#journey-identity");
    assert.equal(identity.childNodes[0].textContent, "Request API · cb-app");
    assert.equal(identity.childNodes[1].tagName, "H1");
    assert.equal(identity.childNodes[1].textContent, "GET /api/v2/features");
    assert.doesNotMatch(identity.textContent, /human-reproduction-label/);
    assert.equal(h.element("#journey-meta").childNodes[1].open, false);
    assert.match(h.element("#journey-meta").textContent, /Request API \/ jobGET \/api\/v2\/features/);
    h.element("#journey-details-tab").onclick();
    assert.equal(identity.childNodes[1].textContent, "GET /api/v2/features", "identity persists across diagram and service views");
    assert.equal(JSON.stringify(doc), before);
    assert.deepEqual(h.calls.slice(callsBefore).map(call => call.path), saved ? [] : [`/api/distributed-trace/${doc.traceId}`]);
  }
});

test("request share actions start closed and close after copying, switching tabs, or Escape", async () => {
  const doc = apiJourney(), h = await harness(undefined, undefined, {journey: doc, hash: ""});
  await h.options.openRequest({traceId: doc.traceId});
  const share = h.element("#journey-share");
  assert.equal(share.open, false);
  share.open = true;
  h.element("#journey-copy-link").onclick(); await settle();
  assert.equal(share.open, false);
  assert.equal(h.copied[0], `http://127.0.0.1:7781/#trace=${doc.traceId}`);
  share.open = true;
  h.element("#journey-details-tab").onclick();
  assert.equal(share.open, false);
  share.open = true;
  let prevented = false;
  share.onkeydown({key: "Escape", preventDefault() { prevented = true; }});
  assert.equal(share.open, false);
  assert.equal(h.element("#journey-share-toggle").focused, true);
  assert.equal(prevented, true);
});

test("request identity, actions and view tabs share a single header", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  const requestSection = html.split('<section id="journey-view"')[1].split('<section id="detail-view"')[0];
  assert.match(requestSection, /class="journey-header"/);
  assert.match(requestSection, /class="journey-heading-row"[\s\S]*id="journey-identity"[\s\S]*id="journey-meta"[\s\S]*aria-label="Request actions"/);
  assert.match(requestSection, /<details id="journey-share"[^>]*>\s*<summary[^>]*>Share \/ export<\/summary>[\s\S]*id="journey-copy-link"[\s\S]*id="journey-download"[\s\S]*<\/details>/);
  assert.doesNotMatch(requestSection, /<details id="journey-share"[^>]*\sopen[\s>]/);
});

test("execution headings identify the selected service API and preserve the upstream request context", async () => {
  for (const saved of [false, true]) {
    const doc = apiJourney(), child = doc.segments[1];
    const h = await harness(undefined, () => response(child), {journey: doc});
    if (saved) await h.options.openPin({name: "Saved", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: doc.traceId});
    h.journeys.at(-1).config.onSelect(child); await settle();
    const identity = h.element("#trace-identity");
    assert.equal(identity.childNodes[0].textContent, "Service API · cb-ems");
    assert.equal(identity.childNodes[1].textContent, "POST /features/search");
    assert.equal(identity.childNodes[2].textContent, "Request: GET /api/v2/features · cb-app");
    assert.match(h.element("#trace-meta").textContent, /API \/ jobPOST \/features\/search/);
    assert.equal(h.element("#trace-meta").childNodes[1].open, false);
    if (saved) assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false);
  }
});

test("active service identity follows the opened live or saved segment across every execution view", async () => {
  for (const saved of [false, true]) {
    const doc = apiJourney(), [root, child] = doc.segments;
    const h = await harness(undefined, path => {
      const id = decodeURIComponent(path.split("/").pop().split("?")[0]);
      return response(doc.segments.find(item => item.segmentId === id));
    }, {journey: doc});
    if (saved) await h.options.openPin({name: "Saved", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: doc.traceId});
    for (const selected of [child, root, child]) {
      h.journeys.at(-1).config.onSelect(selected); await settle();
      for (const view of ["#execution-flow-tab", "#execution-tree-tab", "#execution-timeline-tab"]) {
        h.element(view).onclick(); await settle();
        assert.equal(h.element("#segment-service-name").textContent, selected.service,
          "prominent service label belongs to the opened segment, not the request root");
        assert.equal(h.element("#segment-toolbar-service").textContent, selected.service,
          "sticky controls retain the same service context while switching presentations");
      }
      if (selected === child) assert.match(h.element("#trace-identity").textContent, /Request: GET \/api\/v2\/features · cb-app/);
      h.element("#back").onclick();
    }
    if (saved) assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false,
      "saved service identity comes from saved evidence without consulting a live service");
  }
});

test("selected service labels exist in the segment heading and sticky navigation markup", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  const detail = html.split('<section id="detail-view"')[1].split('<dialog id="help-dialog"')[0];
  const heading = detail.split('<div id="segment-toolbar"')[0];
  const toolbar = detail.split('<div id="segment-toolbar"')[1].split('<div id="execution-graph"')[0];
  assert.match(heading, /class="segment-heading"/);
  assert.match(heading, /id="trace-identity"/);
  assert.match(heading, /id="segment-service-name"/);
  assert.match(toolbar, /id="segment-toolbar-service"/);
  assert.doesNotMatch(detail, /Viewing service/);
  assert.match(heading, /title="Service whose execution is displayed"/);
  assert.match(toolbar, /title="Service whose execution is displayed"/);
  assert.equal((html.match(/id="segment-service-name"/g) || []).length, 1);
  assert.equal((html.match(/id="segment-toolbar-service"/g) || []).length, 1);
  for (const id of ["segment-service-name", "segment-toolbar-service"]) {
    const tag = detail.match(new RegExp(`<[^>]+id="${id}"[^>]*>`))[0];
    assert.match(tag, /^<(?:div|strong)\b/);
    assert.doesNotMatch(tag, /\b(?:role="button"|tabindex=|onclick=)/);
  }
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/style.css"), "utf8");
  const labelStyle = css.match(/\.segment-service-context\s*\{([^}]*)\}/)[1];
  assert.match(labelStyle, /font:\s*700\s+15px/);
  assert.match(labelStyle, /cursor:\s*text/);
  assert.doesNotMatch(labelStyle, /\b(?:border|border-radius|background|box-shadow)\s*:/);
});

test("service identity uses captured application fallback and explicitly identifies missing evidence", async () => {
  for (const [extra, expected] of [[{applicationId: "cb-consumption-ledger"}, "cb-consumption-ledger"],
    [{attributes: {applicationId: "cb-grant-orchestrator"}}, "cb-grant-orchestrator"],
    [{service: " \t ", applicationId: " cb-ems "}, "cb-ems"],
    [{service: 123, applicationId: {}, attributes: {applicationId: " cb-ledger "}}, "cb-ledger"],
    [{}, "Unknown service"]]) {
    const trace = {...segment(), service: undefined, ...extra};
    const h = await harness(undefined, () => response(trace));
    await h.options.openPin({name: "Previous service", segments: [segment("previous")]}); await settle();
    assert.equal(h.element("#segment-service-name").textContent, "cb-app");
    await h.options.openSegment(trace); await settle();
    assert.equal(h.element("#segment-service-name").textContent, expected);
    assert.equal(h.element("#segment-toolbar-service").textContent, expected);
  }
});

test("captured service names are inert text in both prominent and sticky labels", async () => {
  const name = '<img src=x onerror="alert(1)"> & cb-ems', trace = {...segment(), service: name};
  const before = JSON.stringify(trace), h = await harness();
  await h.options.openPin({name: "Untrusted saved label", segments: [trace]}); await settle();
  const header = h.element("#segment-service-name"), toolbar = h.element("#segment-toolbar-service");
  assert.equal(header.textContent, name); assert.equal(toolbar.textContent, name);
  assert.deepEqual(header.htmlWrites, []); assert.deepEqual(toolbar.htmlWrites, []);
  assert.equal(header.childNodes.length, 0); assert.equal(toolbar.childNodes.length, 0);
  assert.equal(JSON.stringify(trace), before);
});

test("missing, ambiguous and contradictory roots use observed identity without inventing an entry API", async () => {
  for (const kind of ["missing", "ambiguous", "contradictory", "malformed-parent"]) {
    const doc = apiJourney();
    if (kind === "missing") doc.segments = [doc.segments[1]];
    if (kind === "ambiguous") { delete doc.segments[1].parentSpanId; doc.links = []; }
    if (kind === "contradictory") doc.links.push({fromSegmentKey: "ems-key", toSegmentKey: "root-key"});
    if (kind === "malformed-parent") { doc.segments = [doc.segments[0]]; doc.segments[0].parentSpanId = {}; }
    const before = JSON.stringify(doc), h = await harness(undefined, undefined, {journey: doc});
    await h.options.openRequest({traceId: doc.traceId});
    const identity = h.element("#journey-identity");
    assert.match(identity.childNodes[0].textContent, /^Observed API · /, kind);
    assert.match(identity.textContent, /missing or ambiguous/);
    if (kind === "missing") assert.equal(identity.childNodes[1].textContent, "POST /features/search");
    assert.equal(JSON.stringify(doc), before);
  }
});

test("Chargebee gateway absence is explicit only in configured Chargebee context, without fabricating cb-app", async () => {
  for (const gateway of ["cb-app", "chargebee-app", null]) {
    const doc = apiJourney(); doc.segments = [doc.segments[1]];
    delete doc.segments[0].parentSpanId; doc.links = [];
    const summary = {federated: true, configuredServices: [gateway, "cb-ems"].filter(Boolean), services: []};
    const h = await harness(undefined, undefined, {journey: doc, summary});
    await h.options.openRequest({traceId: doc.traceId});
    const identity = h.element("#journey-identity");
    assert.equal(identity.childNodes[1].textContent, "POST /features/search");
    assert.equal(identity.childNodes[0].textContent, `${gateway ? "Observed" : "Request"} API · cb-ems`);
    if (gateway) assert.ok(identity.textContent.includes(`${gateway} entry segment unavailable`));
    else assert.doesNotMatch(identity.textContent, /cb-app|chargebee-app|unavailable/);
    assert.equal(h.element("#journey-tree").childNodes.length, 1, "no placeholder service or causal link invented");
  }
});

test("scheduled roots show the job identity even with HTTP descendants, and missing API data is explicit", async () => {
  const doc = apiJourney();
  doc.segments[0] = {...doc.segments[0], entryChannel: "SCHEDULER", lensId: "BillingTask.run", attributes: {}};
  const h = await harness(undefined, undefined, {journey: doc});
  await h.options.openPin({name: "Job", journey: doc, segments: doc.segments});
  assert.equal(h.element("#journey-identity").childNodes[0].textContent, "Scheduled job · cb-app");
  assert.equal(h.element("#journey-identity").childNodes[1].textContent, "BillingTask.run");
  h.journeys.at(-1).config.onSelect(doc.segments[0]); await settle();
  assert.equal(h.element("#trace-identity").childNodes[1].textContent, "BillingTask.run");
  const noPath = {...apiJourney().segments[0], attributes: {httpMethod: "GET"}};
  await h.options.openPin({name: "No path", segments: [noPath]});
  assert.equal(h.element("#trace-identity").childNodes[1].textContent, "GET (API path not captured)");
  await h.options.openPin({name: "Empty", journey: {...doc, segments: []}, segments: []});
  assert.equal(h.element("#journey-identity").childNodes[1].textContent, "Request API unavailable");
});

test("saved scheduler exports reopen all job metadata from attributes without a live export", async () => {
  const doc = apiJourney();
  const metadata = {jobType: "compute_billing_metrics", jobId: "42", jobOutcome: "FAILED",
    jobAuditStatus: "RESCHEDULE_FAILED", jobFailureType: "java.lang.IllegalStateException", scheduler: "chargebee"};
  doc.segments = [{...doc.segments[0], entryChannel: "SCHEDULER", lensId: metadata.jobType,
    attributes: {...metadata, payload: "SECRET_JOB_PAYLOAD"}}];
  doc.links = [];
  const h = await harness(undefined, () => assert.fail("Saved jobs cannot fetch live exports"));
  const readsBefore = h.calls.length;
  await h.options.openPin({name: "Saved failed job", journey: doc, segments: doc.segments});
  h.journeys.at(-1).config.onSelect(doc.segments[0]); await settle();
  const details = h.element("#trace-meta").textContent;
  for (const value of Object.values(metadata)) assert.ok(details.includes(value), `${value} retained on reopen`);
  assert.doesNotMatch(details, /SECRET_JOB_PAYLOAD/);
  assert.ok(h.calls.slice(readsBefore).every(call => call.path === "/api/segment-execution"));
});

test("API headings preserve exact encoded paths and render untrusted metadata only as text", async () => {
  const doc = apiJourney(), unsafe = '<img src=x onerror="alert(1)">';
  doc.segments[0].attributes.path = "/features/dGVzdDE=/" + unsafe + "/subscription_metrics";
  doc.segments[0].service = unsafe;
  const h = await harness(undefined, undefined, {journey: doc, hash: "", retrieveSnapshot: () => response({error: "Gone"}, 404)});
  await h.options.openRequest({traceId: doc.traceId});
  const identity = h.element("#journey-identity"), heading = identity.childNodes[1];
  assert.equal(heading.textContent, "GET " + doc.segments[0].attributes.path);
  assert.equal(heading.title, heading.textContent);
  assert.equal(heading.htmlWrites.length, 0);
  assert.equal(identity.childNodes[0].htmlWrites.length, 0);
  assert.ok(identity.childNodes.every(node => node.tagName !== "IMG"));
  await h.hash("#snapshot=12345678-1234-1234-1234-123456789abc");
  assert.equal(identity.textContent, "", "unavailable saved navigation clears the prior API heading");
});

test("federated status names each service's recording state without an aggregate read-only banner", async () => {
  const unsafe = '<img src=x onerror="alert(1)">';
  const summary = {federated: true, readOnly: false, traceCount: 999, configuredServices: ["cb-app", "cb-ems", "cb-plans", unsafe],
    services: [{service: "cb-app", recordingEnabled: true, agentActive: true, traceCount: 12, instrumentedClasses: 250},
      {service: "cb-ems", recordingEnabled: false, agentActive: true, traceCount: 8},
      {service: unsafe, recordingEnabled: true, agentActive: false, traceCount: 2}], errors: [{service: "cb-plans", error: "connection refused"}]};
  const before = JSON.stringify(summary), h = await harness(undefined, undefined, {summary});
  const status = h.element("#status");
  assert.match(status.textContent, /cb-app · recording/); assert.match(status.textContent, /cb-ems · paused/);
  assert.match(status.textContent, /cb-plans · unreachable/); assert.ok(status.textContent.includes(unsafe));
  assert.doesNotMatch(status.textContent, /\d+\/\d+ services|999 traces|read-only/i);
  assert.equal(status.htmlWrites.some(value => value.includes(unsafe)), false, "service names are never interpolated into HTML");
  assert.equal(status.childNodes.some(item => item.tagName === "IMG"), false);
  assert.equal(h.element("#reset").disabled, true, "federation disables global reset even without readOnly metadata");
  assert.match(h.element("#reset").title, /federat|service/i);
  assert.deepEqual(Array.from(h.requestUI.services), summary.configuredServices);
  const active = status.childNodes.find(chip => chip.textContent === "cb-app · recording on");
  assert.match(active.title, /12 retained service segments/); assert.match(active.title, /configured capture scope/);
  assert.match(status.childNodes.find(chip => chip.textContent === "cb-ems · paused").title, /retained evidence can still be viewed/);
  assert.match(status.childNodes.find(chip => chip.textContent.includes(unsafe)).title, /Line agent inactive.*request-level evidence only/);
  assert.equal(JSON.stringify(summary), before);
});

test("summary failure replaces stale recording status without enabling reset", async () => {
  const h = await harness(undefined, undefined, {summary: {federated: true, readOnly: true, configuredServices: ["cb-app", "cb-ems"],
    services: [{service: "cb-app", recordingEnabled: true, agentActive: true}, {service: "cb-ems", recordingEnabled: false, agentActive: true}]}});
  assert.match(h.element("#status").textContent, /cb-app · recording/);
  h.setSummary(() => response({error: "Summary unavailable"}, 503));
  h.element("#refresh").onclick(); await settle();
  assert.match(h.element("#status").textContent, /unreachable|unavailable/i);
  assert.match(h.element("#status").textContent, /cb-app · unreachable/);
  assert.match(h.element("#status").textContent, /cb-ems · unreachable/);
  assert.doesNotMatch(h.element("#status").textContent, /· recording|· paused/);
  assert.equal(h.element("#reset").disabled, true);
  assert.equal(h.calls.some(call => call.path === "/api/reset"), false);
});

test("single-service status keeps scoped counts in its tooltip and does not infer unknown recording state", async () => {
  for (const [recordingEnabled, label] of [[true, "recording on"], [false, "paused"], [undefined, "connected"]]) {
    const h = await harness(undefined, undefined, {summary: {federated: false, readOnly: false, applicationId: "cb-ems",
      recordingEnabled, agentActive: false, traceCount: 9, totalEvents: 100, failedClasses: 2}});
    const status = h.element("#status");
    assert.equal(status.textContent, `cb-ems · ${label}`);
    assert.equal(status.childNodes.length, 1); assert.match(status.childNodes[0].className, /service-status/);
    assert.match(status.childNodes[0].title, /9 retained service segments.*before UI filters/);
    assert.match(status.childNodes[0].title, /Line agent inactive/);
    assert.match(status.childNodes[0].title, /2 class instrumentation failures.*coverage may be incomplete/);
    assert.equal(status.htmlWrites.length, 0);
    assert.equal(h.element("#reset").disabled, false);
    assert.match(h.element("#reset").title, /this service.*confirmation/i);
  }
  const fallback = await harness(undefined, undefined, {summary: {recordingEnabled: true}});
  assert.equal(fallback.element("#status").textContent, "Service · recording on");
  const empty = await harness(); assert.equal(empty.element("#status").textContent, "No services configured");
  const failure = await harness(undefined, undefined, {summary: () => response({error: "Offline"}, 503)});
  assert.equal(failure.element("#status").textContent, "Service · unreachable");
  assert.equal(failure.element("#reset").disabled, true);
});

test("Home returns saved requests, details and Errors to Live without discarding filters or resetting services", async () => {
  for (const from of ["live detail", "live request", "saved request", "saved detail", "errors"]) {
    const saved = from.startsWith("saved"), pin = savedFile();
    const h = await harness(undefined, undefined, {hash: saved ? "#snapshot=" + pin.id : from === "live request" ? "#trace=trace" : "",
      mode: from === "errors" ? "errors" : saved ? "pins" : "live", retrieveSnapshot: () => response(pin),
      summary: {federated: true, readOnly: true, configuredServices: ["cb-app"],
        services: [{service: "cb-app", recordingEnabled: true, agentActive: true, traceCount: 4}]}});
    if (from === "live detail") await h.options.openSegment(segment());
    if (from === "saved detail") {
      h.journeys[0].config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
      await settle();
    }
    h.element("#filter").value = "existing filter"; h.element("#service-filter").value = "cb-app";
    const filters = JSON.stringify(h.requestUI.filters), before = h.calls.length, errorsBefore = h.requestUI.errorRefreshes;
    h.element("#home").onclick(); await settle();
    assert.equal(h.requestUI.homeCalls, 1, from); assert.equal(h.requestUI.mode, "live", from);
    assert.equal(h.location.hash, "", from);
    assert.equal(h.element("#list-view").classList.contains("hidden"), false, from);
    assert.equal(h.element("#detail-view").classList.contains("hidden"), true, from);
    assert.equal(h.element("#journey-view").classList.contains("hidden"), true, from);
    assert.equal(h.element("#live-tab").focused, true, from);
    assert.equal(h.element("#list-view").scrolled, true, from);
    assert.equal(h.element("#filter").value, "existing filter", from);
    assert.equal(h.element("#service-filter").value, "cb-app", from);
    assert.equal(JSON.stringify(h.requestUI.filters), filters, from);
    assert.equal(h.requestUI.errorRefreshes, errorsBefore, "Home fetches Live, not Errors");
    const paths = h.calls.slice(before).map(call => call.path);
    assert.ok(paths.includes("/api/summary"), `${from}: status refreshes immediately`);
    assert.ok(paths.includes("/api/traces"), `${from}: Live list refreshes immediately`);
    assert.equal(paths.some(path => path.startsWith("/api/reset")), false, from);
    assert.doesNotMatch(h.element("#status").textContent, /Saved snapshot|not live/i, from);
    assert.equal(h.element("#autorefresh").disabled, false, from);
    assert.equal(h.element("#reset").disabled, true, "Home still respects federation reset protection");
  }
});

test("compact saved headers keep not-live and partial visible with complete metadata and warnings in closed disclosures", async () => {
  const trace = {...segment("root"), lensId: "full-reproduction-lens-id", entryChannel: "HTTP", truncated: true,
    attributes: {httpStatus: 201, siteId: "full-site-id", siteType: "sandbox", datadogTraceId: "full-datadog-id"}};
  const warning = "Full warning " + "retained detail ".repeat(40);
  const doc = {...journey(), partial: true, completeness: "partial snapshot", note: "Full causal interpretation note",
    errors: [{service: "cb-plans", error: "Full unavailable-service reason"}], warnings: [warning]};
  const pin = {id: "12345678-1234-1234-1234-123456789abc", storage: "viewer", name: "Long saved capture " + "name ".repeat(20),
    capturedAt: 1000, partial: true, warnings: [warning], journey: doc, segments: [trace]};
  const before = JSON.stringify(pin), h = await harness(undefined, () => response(trace), {hash: ""});
  const initialCalls = h.calls.length;
  await h.options.openPin(pin);
  for (const banner of h.banners) {
    assert.equal(banner.classList.contains("hidden"), false);
    assert.equal(banner.childNodes[0].textContent, "Saved viewer snapshot · not live · PARTIAL");
    assert.equal(banner.childNodes[1].textContent, pin.name);
    const details = banner.childNodes[2];
    assert.equal(details.tagName, "DETAILS"); assert.equal(details.open, false);
    assert.equal(details.childNodes[0].tagName, "SUMMARY");
    assert.equal(details.childNodes[0].textContent, "Snapshot details · 1 warning");
    assert.ok(details.textContent.includes(pin.name) && details.textContent.includes(pin.id));
    assert.ok(details.textContent.includes(new Date(pin.capturedAt).toLocaleString()) && details.textContent.includes(warning));
    assert.ok(!banner.childNodes[0].textContent.includes(warning));
  }
  const requestMeta = h.element("#journey-meta"), requestDetails = requestMeta.childNodes[1];
  assert.equal(requestMeta.childNodes[0].textContent, "2 service segments · PARTIAL");
  assert.equal(requestDetails.open, false);
  for (const value of [doc.traceId, doc.completeness, doc.note, warning, "cb-plans: Full unavailable-service reason", "not a shared time axis"])
    assert.ok(requestDetails.textContent.includes(value), value + " remains accessible");
  h.element("#journey-details-tab").onclick(); h.element("#journey-diagram-tab").onclick();
  assert.equal(h.element("#journey-meta").childNodes[1], requestDetails, "request metadata remains available in either presentation");
  h.journeys[0].config.onSelect({segmentId: "root", service: "cb-app", serviceInstanceId: "historical-instance"}); await settle();
  const segmentMeta = h.element("#trace-meta"), segmentDetails = segmentMeta.childNodes[1];
  assert.equal(segmentMeta.childNodes[0].textContent, "cb-app · HTTP · COMPLETED · 3 events · HTTP 201 · TRUNCATED");
  assert.equal(segmentDetails.tagName, "DETAILS"); assert.equal(segmentDetails.open, false);
  for (const value of [trace.lensId, trace.user, trace.traceId, trace.segmentId, trace.serviceInstanceId,
    "full-site-id (sandbox)", "full-datadog-id"])
    assert.ok(segmentDetails.textContent.includes(value), value + " remains accessible");
  assert.ok(!segmentMeta.childNodes[0].textContent.includes(trace.lensId));
  const callsBeforeExpand = h.calls.length;
  segmentDetails.open = true; requestDetails.open = true; h.banners[0].childNodes[2].open = true;
  assert.equal(h.calls.length, callsBeforeExpand, "metadata disclosure never consults live services");
  assert.deepEqual(h.calls.slice(initialCalls).map(call => call.path), ["/api/segment-execution"]);
  assert.equal(JSON.stringify(pin), before);
  await h.options.openSegment(trace); await settle();
  assert.ok(h.banners.every(banner => banner.classList.contains("hidden") && !banner.textContent), "live navigation clears saved banners");
  assert.equal(h.element("#trace-meta").childNodes[1].open, false, "new segment metadata starts compact");
});

test("request metadata retains missing-diagram guidance and browser-only snapshot status", async () => {
  const doc = journey(); delete doc.visualization;
  doc.note = "Retained request interpretation";
  const h = await harness();
  await h.options.openPin({name: "Browser capture", capturedAt: 1234, journey: doc, segments: doc.segments});
  assert.equal(h.banners[0].childNodes[0].textContent, "Browser-only snapshot · not live");
  const details = h.element("#journey-meta").childNodes[1];
  assert.equal(details.open, false);
  assert.match(details.textContent, /Retained request interpretation/);
  assert.match(details.textContent, /No diagram was saved.*no live data has been substituted/);
  assert.equal(h.element("#journey-diagram-tab").disabled, true);
  assert.equal(h.element("#journey-tree").classList.contains("hidden"), false);
});

test("service-detail HTTP labels use the alias with peer hover while retaining recorded downstream navigation", async () => {
  for (const saved of [false, true]) {
    const doc = journey();
    doc.httpCalls = [{callKey: "ems-call", ownerSegmentKey: "root-key", destinationName: "cb-ems",
      peer: "127.0.0.1:8082", httpMethod: "POST", path: "/features", httpStatus: 201, status: "COMPLETED",
      durationNanos: 119210000, propagationStatus: "INJECTED", downstreamSegmentKeys: ["ems-key"]}];
    doc.links = [{fromCallKey: "ems-call", fromSegmentKey: "root-key", toSegmentKey: "ems-key"}];
    const before = JSON.stringify(doc);
    const h = await harness(undefined, undefined, {journey: doc});
    if (saved) await h.options.openPin({name: "Saved", journey: doc, segments: doc.segments});
    else await h.options.openRequest({traceId: "trace"});
    const root = h.element("#journey-tree").childNodes[0];
    const call = root.childNodes.find(node => node.className === "http-call");
    const label = call.childNodes[0];
    assert.equal(label.textContent, "HTTP OUT → POST cb-ems/features · 201 · 119.21 ms · INJECTED");
    assert.ok(!label.textContent.includes("127.0.0.1:8082"));
    assert.match(label.title, /127\.0\.0\.1:8082/);
    const downstream = call.childNodes.find(node => node.className === "service-segment");
    assert.ok(downstream, "the same recorded service remains nested under its caller");
    assert.match(downstream.childNodes[0].textContent, /^cb-ems/);
    assert.ok(!call.childNodes.some(node => node.className === "missing"));
    assert.equal(JSON.stringify(doc), before, "label formatting does not rewrite evidence");
    downstream.childNodes[0].onclick(); await settle();
    assert.equal(h.renders.at(-1).config.trace.segmentId, "ems");
    assert.equal(h.renders.at(-1).config.trace.serviceInstanceId, "ems-instance");
    assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, saved ? 0 : 1);
  }
});

test("service-detail HTTP labels fall back to peer and preserve missing-evidence status fields", async () => {
  const doc = journey();
  doc.httpCalls = [
    {ownerSegmentKey: "root-key", peer: "plans.example:8081", httpMethod: "GET", path: "/plans",
      status: "ERROR", durationNanos: 0, propagationStatus: "NOT_ALLOWLISTED", downstreamSegmentKeys: []},
    {ownerSegmentKey: "root-key", peer: "unknown.example", downstreamSegmentKeys: []}
  ];
  const h = await harness(undefined, undefined, {journey: doc});
  await h.options.openRequest({traceId: "trace"});
  const calls = h.element("#journey-tree").childNodes[0].childNodes.filter(node => node.className === "http-call");
  assert.equal(calls[0].childNodes[0].textContent, "HTTP OUT → GET plans.example:8081/plans · ERROR · 0.00 ms · NOT_ALLOWLISTED");
  assert.match(calls[0].childNodes[0].title, /plans\.example:8081/);
  assert.equal(calls[1].childNodes[0].textContent, "HTTP OUT → HTTP unknown.example · pending · ? ms · unknown propagation");
  for (const call of calls) {
    const missing = call.childNodes.find(node => node.className === "missing");
    assert.match(missing.textContent, /Downstream not observed/);
    assert.ok(!call.childNodes.some(node => node.className === "service-segment"));
  }
});

test("live boundary labels reuse the exact journey alias and retain the peer without changing evidence", async () => {
  const call = {spanId: "ems-call", peer: "127.0.0.1:8082", destinationName: "127.0.0.1:8082", httpMethod: "POST", path: "/features", status: "COMPLETED"};
  const trace = {...segment("root"), httpCalls: [call]};
  const doc = journey();
  doc.httpCalls = [{...call, ownerSegmentKey: "root-key", destinationName: "cb-ems", downstreamSegmentKeys: []}];
  const before = JSON.stringify({trace, doc});
  const h = await harness(undefined, () => response(trace), {journey: doc});
  await h.options.openRequest({traceId: "trace"});
  h.journeys[0].config.onSelect({segmentId: "root", service: "cb-app", serviceInstanceId: "historical-instance"});
  await settle();
  assert.match(h.element("#dependency-summary").textContent, /cb-ems \(127\.0\.0\.1:8082\)/);
  assert.equal(JSON.stringify({trace, doc}), before, "display labels do not rewrite captured metadata");
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 1);

  await h.options.openSegment(trace); await settle();
  assert.ok(!h.element("#dependency-summary").textContent.includes("cb-ems"), "direct segments never reuse the previous request's mapping");
  assert.match(h.element("#dependency-summary").textContent, /127\.0\.0\.1:8082/);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 1, "no extra discovery just for labels");
});

test("saved boundary labels use captured aliases or a unique recorded downstream service without live lookup", async () => {
  const calls = [
    {spanId: "ems-call", peer: "127.0.0.1:8082", httpMethod: "POST", path: "/features"},
    {spanId: "plans-call", peer: "127.0.0.1:8081", httpMethod: "GET", path: "/plans"}
  ];
  const trace = {...segment("root"), httpCalls: calls};
  const doc = journey();
  doc.segments.push({...segment("plans"), service: "cb-plans", serviceInstanceId: "plans-instance", segmentKey: "plans-key"});
  doc.httpCalls = [
    {...calls[0], ownerSegmentKey: "root-key", destinationName: "Archived EMS", downstreamSegmentKeys: []},
    {...calls[1], ownerSegmentKey: "root-key", downstreamSegmentKeys: ["plans-key"]}
  ];
  const pin = {id: "12345678-1234-1234-1234-123456789abc", storage: "viewer", name: "Historical", capturedAt: 1000,
    journey: doc, segments: [trace]};
  const before = JSON.stringify(pin);
  const h = await harness(undefined, undefined, {hash: ""});
  const initialCalls = h.calls.length;
  await h.options.openPin(pin);
  h.journeys[0].config.onSelect({segmentId: "root", service: "cb-app", serviceInstanceId: "historical-instance"});
  await settle();
  assert.match(h.element("#dependency-summary").textContent, /Archived EMS \(127\.0\.0\.1:8082\)/);
  assert.match(h.element("#dependency-summary").textContent, /cb-plans \(127\.0\.0\.1:8081\)/);
  assert.equal(JSON.stringify(pin), before);
  assert.deepEqual(h.calls.slice(initialCalls).map(call => call.path), ["/api/segment-execution"]);
});

test("boundary label matching rejects ambiguous, unrelated and incomplete journey identities", async () => {
  for (const alter of [
    doc => { doc.traceId = "other-trace"; },
    doc => { doc.segments[0].serviceInstanceId = "replacement-instance"; },
    doc => { doc.segments.push({...doc.segments[0]}); },
    doc => { doc.httpCalls[0].ownerSegmentKey = "ems-key"; },
    doc => { doc.httpCalls[0].spanId = "different-call"; },
    doc => { doc.httpCalls[0].peer = "127.0.0.1:8081"; },
    doc => { doc.httpCalls.push({...doc.httpCalls[0]}); },
    doc => { delete doc.httpCalls[0].destinationName; doc.httpCalls[0].downstreamSegmentKeys = ["ems-key", "root-key"]; }
  ]) {
    const call = {spanId: "ems-call", peer: "127.0.0.1:8082"};
    const trace = {...segment("root"), httpCalls: [call]};
    const doc = journey();
    doc.httpCalls = [{...call, ownerSegmentKey: "root-key", destinationName: "wrong-name", downstreamSegmentKeys: []}];
    alter(doc);
    const h = await harness();
    await h.options.openPin({name: "Saved", journey: doc, segments: [trace]});
    h.journeys[0].config.onSelect({segmentId: "root", service: "cb-app", serviceInstanceId: "historical-instance"});
    await settle();
    const output = h.element("#dependency-summary").textContent;
    assert.match(output, /127\.0\.0\.1:8082/);
    assert.ok(!output.includes("wrong-name") && !output.includes("cb-ems"));
    assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/") || call.path.startsWith("/api/render-data/")).length, 0);
  }
});

test("direct segment labels retain explicit aliases and safely fall back for malformed metadata", async () => {
  const trace = {...segment(), httpCalls: [
    {spanId: "named", destinationName: "Plans API", peer: "127.0.0.1:8081"},
    {spanId: "same", destinationName: "plans.example", peer: "plans.example"},
    {spanId: "invalid-name", destinationName: "<img src=private-secret>", peer: "unmapped.example:8088"},
    {spanId: "invalid-peer", destinationName: "private-secret\nname", peer: "https://user:private-secret@host/path?token=private-secret"}
  ], events: [{type: "DB_ROUTE", attributes: {route: "billing"}}]};
  const h = await harness(undefined, () => response(trace));
  await h.options.openSegment(trace); await settle();
  const output = h.element("#dependency-summary").textContent;
  assert.match(output, /Plans API \(127\.0\.0\.1:8081\)/);
  assert.match(output, /plans\.example/);
  assert.ok(!output.includes("plans.example (plans.example)"));
  assert.match(output, /unmapped\.example:8088/);
  assert.match(output, /Unknown peer/);
  assert.match(output, /DB ROUTEbilling/);
  assert.ok(!output.includes("private-secret") && !output.includes("<img"));
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 0);
});

test("boundary disclosure starts closed for each live or saved segment without discarding rows", async () => {
  for (const saved of [false, true]) {
    const trace = {...segment(), httpCalls: [{spanId: "ems-call", destinationName: "cb-ems", peer: "127.0.0.1:8082"}],
      events: [...segment().events,
        {type: "DB_ROUTE", attributes: {route: "billing"}},
        {type: "EXTERNAL_CALL", attributes: {boundaryKind: "messaging", system: "sqs", direction: "publish",
          destination: "feature-events", operation: "sendMessage", status: "SUBMITTED", evidenceKind: "client-boundary"}}]};
    const h = await harness(undefined, () => response(trace));
    const open = () => saved ? h.options.openPin({name: "Saved", segments: [trace]}) : h.options.openSegment(trace);
    await open(); await settle();
    const first = h.element("#dependency-summary").childNodes[0];
    assert.equal(first.tagName, "DETAILS");
    assert.equal(first.open, false);
    assert.equal(first.childNodes[0].tagName, "SUMMARY", "native summary provides keyboard activation");
    assert.equal(first.childNodes[0].textContent, "Observed external boundaries (3)");
    const table = first.childNodes.find(node => node.tagName === "TABLE");
    assert.equal(table.childNodes[1].childNodes.length, 3);
    assert.match(table.textContent, /cb-ems \(127\.0\.0\.1:8082\)/);
    assert.match(table.textContent, /DB ROUTEbilling/);
    assert.match(table.textContent, /QUEUE PUBLISHsqs · feature-events/);
    const callsBeforeToggle = h.calls.length;
    first.open = true;
    assert.equal(first.childNodes.find(node => node.tagName === "TABLE"), table, "expansion reveals the already captured rows");
    assert.equal(h.calls.length, callsBeforeToggle, "native disclosure requires no fetch or reconstruction");
    await open(); await settle();
    const next = h.element("#dependency-summary").childNodes[0];
    assert.notEqual(next, first);
    assert.equal(next.open, false, "reopening a segment resets the disclosure to closed");
    assert.match(next.textContent, /cb-ems \(127\.0\.0\.1:8082\)/);
  }
});

test("empty boundary evidence remains available within a closed disclosure", async () => {
  const h = await harness();
  await h.options.openSegment(segment()); await settle();
  const disclosure = h.element("#dependency-summary").childNodes[0];
  assert.equal(disclosure.open, false);
  assert.equal(disclosure.childNodes[0].textContent, "Observed external boundaries (0)");
  assert.match(disclosure.textContent, /No boundary observations retained/);
  assert.ok(!disclosure.childNodes.some(node => node.tagName === "TABLE"));
});

test("boundary disclosure preserves bounded counts and truncation warnings while closed", async () => {
  const trace = {...segment(), httpCallsTruncated: true,
    httpCalls: Array.from({length: 5001}, (_, index) => ({spanId: "call-" + index, peer: "unknown.example"}))};
  const h = await harness(undefined, () => response(trace));
  await h.options.openSegment(trace); await settle();
  const disclosure = h.element("#dependency-summary").childNodes[0];
  assert.equal(disclosure.open, false);
  assert.equal(disclosure.childNodes[0].textContent, "Observed external boundaries (5000+) · capture truncated");
  assert.match(h.element("#dependency-summary").textContent, /Observed external boundaries \(5000\+\)/);
  assert.match(h.element("#dependency-summary").textContent, /input scan limit reached/);
  assert.match(disclosure.textContent, /4800 additional observed boundaries not displayed/);
  assert.match(disclosure.textContent, /Capture truncated: some dependency observations may be missing/);
});

test("live segment defaults to Code flow with lazy, projected evidence cached across timeline switches", async () => {
  const h = await harness();
  assert.equal(h.renders.length, 0);
  await h.options.openSegment(segment()); await settle();
  assert.equal(h.renders.length, 1);
  assert.equal(h.renders[0].renderer, "flow");
  assert.equal(h.renders[0].config.saved, false);
  assert.equal(h.element("#execution-flow-tab").attributes["aria-pressed"], "true");
  assert.equal(h.element("#execution-tree-tab").attributes["aria-pressed"], "false");
  assert.equal(h.element("#execution-timeline-tab").attributes["aria-pressed"], "false");
  const post = h.calls.find(call => call.path === "/api/segment-execution");
  assert.equal(post.method, "POST");
  assert.equal(post.headers["Content-Type"], "application/json");
  assert.ok(!post.body.includes("private-") && !post.body.includes("saved source"));
  assert.equal(JSON.parse(post.body).trace.events.length, 3);
  h.element("#execution-timeline-tab").onclick();
  h.element("#execution-flow-tab").onclick(); await settle();
  assert.equal(h.calls.filter(call => call.path === "/api/segment-execution").length, 1);
  assert.equal(h.renders.length, 1);
  assert.equal(h.cleanups, 0, "Timeline preserves the mounted reader's expansion state");
});

test("Code flow and Call tree are distinct named modes with local assets loaded before the app", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.match(html, /id="execution-flow-tab"[^>]*>Code flow<\/button>/);
  assert.match(html, /id="execution-tree-tab"[^>]*>Call tree<\/button>/);
  assert.match(html, /id="execution-timeline-tab"[^>]*>Timeline \/ source<\/button>/);
  for (const asset of ["code-flow.js", "execution-graph.js"]) {
    assert.ok(html.indexOf(`src="/static/${asset}"`) >= 0, asset + " is included locally");
    assert.ok(html.indexOf(`src="/static/${asset}"`) < html.indexOf('src="/static/app.js"'), asset + " loads before integration");
  }
});

test("switching Code flow and Call tree disposes only the previous renderer and reuses one reconstruction", async () => {
  for (const saved of [false, true]) {
    const trace = segment(), before = JSON.stringify(trace), h = await harness();
    if (saved) await h.options.openPin({name: "Saved", segments: [trace]});
    else await h.options.openSegment(trace);
    await settle();
    const callsAfterLoad = h.calls.length, initial = h.renders[0];
    assert.equal(initial.renderer, "flow");
    h.element("#execution-tree-tab").onclick(); await settle();
    assert.deepEqual(h.renders.map(item => item.renderer), ["flow", "tree"]);
    assert.deepEqual(h.disposedRenderers, ["flow"]);
    assert.equal(h.element("#execution-tree-tab").attributes["aria-pressed"], "true");
    assert.equal(h.element("#execution-flow-tab").attributes["aria-pressed"], "false");
    h.element("#execution-tree-tab").onclick(); await settle();
    assert.equal(h.renders.length, 2, "selecting the current mode does not mount another renderer");
    h.element("#execution-timeline-tab").onclick();
    h.element("#execution-tree-tab").onclick(); await settle();
    assert.equal(h.renders.length, 2, "returning from Timeline keeps the mounted call tree");
    h.element("#execution-flow-tab").onclick(); await settle();
    assert.deepEqual(h.renders.map(item => item.renderer), ["flow", "tree", "flow"]);
    assert.deepEqual(h.disposedRenderers, ["flow", "tree"]);
    for (const rendered of h.renders) {
      assert.equal(rendered.output, initial.output, "both views receive the same cached model");
      assert.equal(rendered.config.trace, initial.config.trace, "both views retain the same captured evidence");
      assert.equal(rendered.config.saved, saved);
      assert.equal(rendered.container, h.element("#execution-graph"));
    }
    assert.equal(h.calls.length, callsAfterLoad, "presentation switches never fetch new evidence");
    assert.equal(h.calls.filter(call => call.path === "/api/segment-execution").length, 1);
    assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, saved ? 0 : 1);
    assert.equal(JSON.stringify(trace), before);
    h.element("#back").onclick();
    assert.deepEqual(h.disposedRenderers, ["flow", "tree", "flow"], "leaving cleans up the active reader exactly once");
  }
});

test("mode changes during pending reconstruction render only the latest selection from the same evidence", async () => {
  for (const finalMode of ["tree", "flow"]) {
    let complete;
    const h = await harness(() => new Promise(resolve => { complete = resolve; }));
    await h.options.openSegment(segment()); await settle();
    h.element("#execution-tree-tab").onclick();
    if (finalMode === "flow") h.element("#execution-flow-tab").onclick();
    complete(response(model("one"))); await settle();
    assert.deepEqual(h.renders.map(item => item.renderer), [finalMode]);
    assert.equal(h.renders[0].output.segment.segmentId, "one");
    assert.equal(h.cleanups, 0, "superseded pending views were never mounted");
    assert.equal(h.calls.filter(call => call.path === "/api/segment-execution").length, 1);
    assert.equal(h.element("#execution-graph").attributes["aria-busy"], "false");
    assert.equal(h.element(`#execution-${finalMode}-tab`).attributes["aria-pressed"], "true");
  }
});

test("Timeline suppresses both pending readers until a visual mode is selected again", async () => {
  let complete;
  const h = await harness(() => new Promise(resolve => { complete = resolve; }));
  await h.options.openSegment(segment()); await settle();
  h.element("#execution-tree-tab").onclick();
  h.element("#execution-timeline-tab").onclick();
  complete(response(model("one"))); await settle();
  assert.equal(h.renders.length, 0);
  assert.equal(h.element("#execution-graph").classList.contains("hidden"), true);
  assert.equal(h.element("#timeline-panel").classList.contains("hidden"), false);
  h.element("#execution-tree-tab").onclick(); await settle();
  assert.deepEqual(h.renders.map(item => item.renderer), ["tree"]);
  assert.equal(h.calls.filter(call => call.path === "/api/segment-execution").length, 1);
});

test("historical pins reconstruct their saved data without any live segment lookup", async () => {
  const h = await harness();
  const saved = segment("evicted");
  await h.options.openPin({name: "Saved", segments: [saved]}); await settle();
  assert.equal(h.renders[0].renderer, "flow");
  assert.equal(h.renders[0].config.saved, true);
  assert.equal(h.renders[0].config.trace, saved);
  assert.equal(h.renders[0].output.segment.segmentId, "evicted");
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 0);
  assert.equal(JSON.parse(h.calls.find(call => call.method === "POST").body).trace.serviceInstanceId, "historical-instance");
});

test("both readers reveal the exact saved source event and return without fetching or losing the current renderer", async () => {
  for (const mode of ["flow", "tree"]) {
    const h = await harness();
    await h.options.openPin({name: "Saved", segments: [segment()]}); await settle();
    h.element(`#execution-${mode}-tab`).onclick(); await settle();
    const active = h.renders.at(-1), mounts = h.renders.length, cleanups = h.cleanups, calls = h.calls.length;
    h.element("#timeline-mode").value = "boundaries";
    active.config.onRevealEvidence({entry: {index: 0}, observedLines: {firstEvidence: {index: 1}}});
    assert.equal(h.element("#timeline-mode").value, "all");
    assert.equal(h.element("#execution-timeline-tab").attributes["aria-pressed"], "true");
    const row = h.element("#timeline").querySelector('[data-event-index="1"]');
    assert.ok(row.focused && row.scrolled && row.classList.contains("evidence-highlight"));
    assert.ok(h.element("#timeline").querySelector('[data-source-event-index="1"]').textContent.includes("saved source"));
    h.element("#timeline-mode").onchange(); await settle();
    h.element(`#execution-${mode}-tab`).onclick(); await settle();
    assert.equal(h.renders.length, mounts, "source drill-down keeps the previous reader mounted");
    assert.equal(h.cleanups, cleanups);
    assert.equal(h.calls.length, calls, "saved source is read locally without new upstream or reconstruction calls");
    assert.equal(h.element(`#execution-${mode}-tab`).attributes["aria-pressed"], "true");
  }
});

test("a delayed model cannot redraw the detail view after navigation away", async () => {
  let complete;
  const h = await harness(() => new Promise(resolve => { complete = resolve; }));
  await h.options.openSegment(segment()); await settle();
  h.element("#back").onclick();
  complete(response(model("one"))); await settle();
  assert.equal(h.renders.length, 0);
  assert.ok(h.element("#detail-view").classList.contains("hidden"));
});

test("out-of-order model responses cannot replace a newer selected segment", async () => {
  const complete = {};
  const h = await harness(input => new Promise(resolve => { complete[input.trace.segmentId] = resolve; }));
  await h.options.openSegment(segment("one")); await settle();
  await h.options.openSegment(segment("two")); await settle();
  complete.two(response(model("two"))); await settle();
  complete.one(response(model("one"))); await settle();
  assert.deepEqual(h.renders.map(item => item.output.segment.segmentId), ["two"]);
  h.element("#back").onclick();
  assert.equal(h.cleanups, 1);
});

test("reconstruction failure leaves Timeline available and can be retried", async () => {
  let failing = true;
  const h = await harness(() => failing ? response({}, 503) : response(model("one")));
  await h.options.openSegment(segment()); await settle();
  assert.match(h.element("#execution-graph").textContent, /503.*Timeline/);
  h.element("#execution-timeline-tab").onclick();
  assert.equal(h.element("#timeline-panel").classList.contains("hidden"), false);
  failing = false;
  h.element("#execution-flow-tab").onclick(); await settle();
  assert.equal(h.renders.length, 1);
});

test("switching to Timeline during reconstruction defers graph layout until visible", async () => {
  let complete;
  const h = await harness(() => new Promise(resolve => { complete = resolve; }));
  await h.options.openSegment(segment()); await settle();
  h.element("#execution-timeline-tab").onclick();
  complete(response(model("one"))); await settle();
  assert.equal(h.renders.length, 0);
  h.element("#execution-flow-tab").onclick(); await settle();
  assert.equal(h.renders.length, 1);
  assert.equal(h.calls.filter(call => call.method === "POST").length, 1);
});

test("service activation opens that exact live service instance directly in execution flow and back returns to the request", async () => {
  const h = await harness();
  await h.options.openRequest({traceId: "trace"}); await settle();
  assert.equal(h.renders.length, 0, "initial diagram render does not fetch segment execution");
  assert.notEqual(h.journeys[0].config.showNodeDetails, false, "viewer uses the adjacent graph inspector");
  assert.equal(h.journeys[0].config.showHttpHops, undefined, "live requests use the graph's compact default and explicit Show HTTP hops control");
  h.journeys[0].config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
  await settle();
  assert.equal(h.calls.filter(call => call.path === "/api/render-data/ems?service=cb-ems&instance=ems-instance").length, 1);
  assert.equal(h.renders[0].config.trace.service, "cb-ems");
  assert.equal(h.renders[0].config.trace.serviceInstanceId, "ems-instance");
  assert.equal(h.element("#execution-flow-tab").attributes["aria-pressed"], "true");
  assert.equal(h.element("#detail-view").classList.contains("hidden"), false);
  assert.equal(h.element("#back").textContent, "← request flow");
  assert.equal(h.element("#view-journey").classList.contains("hidden"), true);
  assert.equal(h.element("#view-journey").disabled, true);
  h.element("#execution-timeline-tab").onclick();
  h.element("#back").onclick();
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  assert.notEqual(h.journeys.at(-1).config.showNodeDetails, false, "returning to the request retains the graph inspector");
  assert.equal(h.journeys.at(-1).config.showHttpHops, undefined, "returning to a live request restores the compact default without forcing HTTP hops open");
  h.journeys.at(-1).config.onSelect({kind: "segment", segmentId: "root", service: "cb-app", serviceInstanceId: "historical-instance"});
  await settle();
  assert.equal(h.element("#execution-flow-tab").attributes["aria-pressed"], "true", "each service opens its flow, regardless of previous Timeline choice");
  assert.equal(h.renders.at(-1).config.trace.segmentId, "root");
});

test("pinned service activation uses the matching saved instance and never replaces missing events with live data", async () => {
  const h = await harness();
  const savedJourney = journey();
  const saved = savedJourney.segments[1];
  await h.options.openPin({name: "Saved request", journey: savedJourney, segments: [saved]});
  assert.notEqual(h.journeys[0].config.showNodeDetails, false, "saved captures use the same adjacent inspector");
  assert.equal(h.journeys[0].config.showHttpHops, undefined, "saved captures use the same compact default and explicit Show HTTP hops control");
  h.journeys[0].config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
  await settle();
  assert.equal(h.renders[0].config.trace, saved);
  assert.equal(h.element("#back").textContent, "← request flow");
  assert.equal(h.element("#view-journey").classList.contains("hidden"), true);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 0);
  h.element("#back").onclick();
  h.journeys.at(-1).config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "different-instance"});
  await settle();
  assert.match(h.element("#notice").textContent, /not included.*No live data was substituted/);
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 0);
});

test("expired live service feedback stays visible through polling and offers recovery without substitution", async () => {
  let expired = true;
  const h = await harness(undefined, () => expired ? response({error: "Segment not found"}, 404)
    : response({...segment("ems"), service: "cb-ems", serviceInstanceId: "ems-instance"}));
  await h.options.openRequest({traceId: "trace"});
  h.journeys[0].config.onSelect({segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"}); await settle();
  const panel = h.element("#journey-meta").childNodes.find(node => node.attributes.role === "alert");
  assert.ok(panel); assert.equal(panel.hidden, false); assert.equal(panel.scrolled, true); assert.equal(panel.focused, true);
  assert.match(panel.textContent, /cb-ems code flow unavailable/); assert.match(panel.textContent, /expired.*reproduce.*save a snapshot/);
  assert.match(panel.textContent, /No other request was substituted/); assert.equal(h.renders.length, 0);
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  h.timers.forEach(fn => fn()); await settle();
  assert.equal(panel.hidden, false); assert.match(panel.textContent, /expired/);
  expired = false; panel.childNodes.find(node => node.textContent === "Retry service").onclick(); await settle();
  assert.equal(panel.hidden, true); assert.equal(h.renders.at(-1).config.trace.segmentId, "ems");
});

test("unreachable service errors remain distinct from expiry and render error text inertly", async () => {
  const message = '<img src=x onerror="alert(1)"> connection refused';
  const h = await harness(undefined, () => response({error: message}, 503));
  await h.options.openRequest({traceId: "trace"});
  h.journeys[0].config.onSelect({segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"}); await settle();
  const panel = h.element("#journey-meta").childNodes.find(node => node.attributes.role === "alert");
  assert.match(panel.textContent, /Check the service connection and retry/); assert.doesNotMatch(panel.textContent, /may have expired/);
  assert.ok(panel.textContent.includes(message)); assert.deepEqual(panel.childNodes.flatMap(node => node.htmlWrites), []);
  panel.childNodes.find(node => node.textContent === "Back to requests").onclick(); await settle();
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
});

test("empty expired live trace links show persistent actionable guidance", async () => {
  const h = await harness(undefined, undefined, {journey: {traceId: "trace", segments: [], links: [], httpCalls: [], warnings: ["Trace not recorded or evicted"]}});
  await h.options.openRequest({traceId: "trace"});
  const panel = h.element("#journey-meta").childNodes.find(node => node.attributes.role === "alert");
  assert.match(panel.textContent, /Live request unavailable.*reproduce the API call.*saved capture/);
  assert.doesNotMatch(h.element("#journey-meta").textContent, /No diagram was saved/);
  h.timers.forEach(fn => fn()); await settle(); assert.equal(panel.hidden, false);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 0);
});

test("live request metadata explains retention inline without applying live guidance to saved captures", async () => {
  const doc = journey(), h = await harness(undefined, undefined, {journey: doc});
  await h.options.openRequest({traceId: doc.traceId});
  const meta = h.element("#journey-meta"), summary = meta.childNodes[0];
  assert.match(summary.textContent, /2 service segments.*Live retained evidence/);
  assert.match(summary.title, /Refreshing the page.*expire.*restart.*Save a snapshot/);
  assert.ok(meta.childNodes[1].textContent.includes(summary.title), "retention guidance is also available without hovering");
  assert.equal(meta.childNodes.length, 2, "retention should not add a separate warning row");
  assert.match(h.element("#journey-save").title, /survives service restarts.*expired segments cannot be recovered/);
  const liveReads = h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length;
  for (const storage of ["browser", "viewer"]) {
    await h.options.openPin({name: "Preserved evidence", storage, journey: doc, segments: doc.segments});
    assert.doesNotMatch(meta.textContent, /Live retained evidence|Refreshing the page reloads/);
    assert.equal(meta.childNodes[0].title, undefined);
    assert.doesNotMatch(h.element("#journey-save").title, /currently retained|expired/);
  }
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, liveReads,
    "saved evidence must not be refreshed from live services");
});

test("reopening a live trace uses remaining evidence rather than restoring an expired receiver", async () => {
  const original = journey();
  original.visualization.nodes = [{id: "root", kind: "segment"}, {id: "ems", kind: "segment"}, {id: "ems-sql", kind: "dependency-call"}];
  const settings = {hash: "#trace=trace", journey: original};
  const h = await harness(undefined, undefined, settings);
  assert.match(h.element("#journey-meta").childNodes[0].textContent, /2 service segments/);
  assert.equal(h.journeys.at(-1).output.nodes.length, 3);
  const remaining = {traceId: "trace", segments: [original.segments[0]], links: [], partial: true,
    warnings: ["Downstream not observed; may be pending or evicted"],
    httpCalls: [{ownerSegmentKey: "root-key", peer: "localhost:8082", path: "/features/search", httpMethod: "POST",
      downstreamState: "not-observed", downstreamSegmentKeys: []}],
    visualization: {kind: "request-flow", nodes: [{id: "root", kind: "segment"}, {id: "ems-call", kind: "http-call", downstreamState: "not-observed"}], edges: []}};
  settings.journey = remaining;
  await h.hash("#trace=trace");
  assert.match(h.element("#journey-meta").childNodes[0].textContent, /1 service segments.*Live retained evidence/);
  assert.equal(h.journeys.at(-1).output, remaining.visualization);
  assert.equal(h.journeys.at(-1).output.nodes.some(node => node.id === "ems" || node.id === "ems-sql"), false);
  assert.match(h.element("#journey-tree").textContent, /Downstream not observed/);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 2);
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 0);
});

test("late live segment failures cannot overwrite newer navigation", async () => {
  for (const destination of ["newer segment", "requests"]) {
    let fail;
    const h = await harness(undefined, path => path.includes("/old?") ? new Promise(resolve => { fail = resolve; })
      : response({...segment("ems"), service: "cb-ems", serviceInstanceId: "ems-instance"}));
    await h.options.openRequest({traceId: "trace"});
    const activate = h.journeys[0].config.onSelect;
    activate({segmentId: "old", service: "cb-ems", serviceInstanceId: "old-instance"}); await settle();
    if (destination === "requests") h.element("#journey-back").onclick();
    else activate({segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
    await settle(); fail(response({error: "Old segment missing"}, 404)); await settle();
    assert.doesNotMatch(h.element("#journey-meta").textContent, /code flow unavailable/);
    assert.doesNotMatch(h.element("#notice").textContent, /Old segment missing/);
    assert.equal(h.element(destination === "requests" ? "#list-view" : "#detail-view").classList.contains("hidden"), false);
  }
});

test("rapid service activation cannot let an older segment fetch replace the newer selection", async () => {
  const pending = {};
  const h = await harness(undefined, path => new Promise(resolve => { pending[path.split("/").pop().split("?")[0]] = resolve; }));
  await h.options.openRequest({traceId: "trace"});
  const activate = h.journeys[0].config.onSelect;
  activate({segmentId: "one", service: "cb-app", serviceInstanceId: "i1"}); await settle();
  activate({segmentId: "two", service: "cb-ems", serviceInstanceId: "i2"}); await settle();
  pending.two(response({...segment("two"), service: "cb-ems", serviceInstanceId: "i2"})); await settle();
  pending.one(response(segment("one"))); await settle();
  assert.deepEqual(h.renders.map(item => item.output.segment.segmentId), ["two"]);
  assert.equal(h.element("#segment-service-name").textContent, "cb-ems");
  assert.equal(h.element("#segment-toolbar-service").textContent, "cb-ems");
});

test("leaving the request cancels a pending service navigation", async () => {
  let complete;
  const h = await harness(undefined, () => new Promise(resolve => { complete = resolve; }));
  await h.options.openRequest({traceId: "trace"});
  h.journeys[0].config.onSelect({segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"}); await settle();
  h.element("#journey-back").onclick();
  complete(response(segment("ems"))); await settle();
  assert.equal(h.renders.length, 0);
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
});

test("direct live segments retain distinct list and request-flow navigation after a request drill-down", async () => {
  const h = await harness();
  await h.options.openRequest({traceId: "trace"});
  h.journeys[0].config.onSelect({segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
  await settle();
  assert.equal(h.element("#view-journey").classList.contains("hidden"), true);
  await h.options.openSegment(segment());
  assert.equal(h.element("#back").textContent, "← requests");
  assert.equal(h.element("#view-journey").classList.contains("hidden"), false);
  assert.equal(h.element("#view-journey").disabled, false);
  const before = h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length;
  h.element("#view-journey").onclick(); await settle();
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, before + 1);
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
});

test("saved segment-only captures never offer live request discovery", async () => {
  const h = await harness();
  await h.options.openPin({name: "Old segment", segments: [segment()]});
  assert.equal(h.element("#back").textContent, "← requests");
  assert.equal(h.element("#view-journey").classList.contains("hidden"), true);
  assert.equal(h.element("#view-journey").disabled, true);
  h.element("#view-journey").onclick(); await settle();
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 0);
});

test("segments without a trace identifier offer only the return-to-requests action", async () => {
  const h = await harness(undefined, () => response({...segment(), traceId: null}));
  await h.options.openSegment(segment());
  assert.equal(h.element("#back").textContent, "← requests");
  assert.equal(h.element("#view-journey").classList.contains("hidden"), true);
  h.element("#view-journey").onclick(); await settle();
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/distributed-trace/")).length, 0);
});

const SNAPSHOT_ID = "12345678-1234-1234-1234-123456789abc";
const OTHER_ID = "87654321-1234-1234-1234-123456789abc";
function savedFile(id = SNAPSHOT_ID) {
  const savedJourney = journey();
  return {id, storage: "viewer", name: "DB + SQS", capturedAt: 1000, savedAt: 2000,
    journey: savedJourney, segments: savedJourney.segments};
}

test("snapshot links load immutable file evidence with no agent reads or polling", async () => {
  const pin = savedFile();
  const h = await harness(undefined, undefined, {hash: "#snapshot=" + pin.id,
    retrieveSnapshot: () => response(pin)});
  assert.deepEqual(h.calls.map(call => call.path), ["/api/snapshots/" + pin.id]);
  assert.equal(h.journeys[0].output, pin.journey.visualization);
  assert.match(h.element("#status").textContent, /viewer file.*not live/);
  assert.equal(h.element("#journey-save").classList.contains("hidden"), true);
  h.timers.forEach(fn => fn());
  h.element("#refresh").onclick(); await settle();
  assert.equal(h.calls.length, 1, "saved-file Refresh never reads agents");
  h.element("#journey-copy-link").onclick(); await settle();
  assert.deepEqual(h.copied, ["http://127.0.0.1:7781/#snapshot=" + pin.id]);
  h.journeys[0].config.onSelect({kind: "segment", segmentId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance"});
  await settle();
  assert.equal(h.renders[0].config.trace, pin.segments[1]);
  assert.ok(h.calls.every(call => call.path.startsWith("/api/snapshots/") || call.path === "/api/segment-execution"));
});

test("a missing snapshot clears older evidence and never falls back to live", async () => {
  const h = await harness(undefined, undefined, {hash: "#snapshot=" + SNAPSHOT_ID,
    retrieveSnapshot: path => path.endsWith(SNAPSHOT_ID) ? response(savedFile()) : response({error: "Snapshot not found"}, 404)});
  await h.hash("#snapshot=" + OTHER_ID);
  assert.match(h.element("#journey-meta").textContent, /unavailable/);
  assert.match(h.element("#notice").textContent, /Snapshot not found/);
  assert.equal(h.element("#journey-graph").childNodes.length, 0);
  assert.equal(h.element("#list-view").classList.contains("hidden"), true);
  h.timers.forEach(fn => fn()); await settle();
  assert.ok(h.calls.every(call => call.path.startsWith("/api/snapshots/")));
});

test("malformed snapshot links are rejected before any API call", async () => {
  const h = await harness(undefined, undefined, {hash: "#snapshot=../../private"});
  assert.equal(h.calls.length, 0);
  assert.match(h.element("#notice").textContent, /Invalid saved snapshot ID/);
  assert.match(h.element("#status").textContent, /no live substitution/);
});

test("out-of-order snapshot success and failure cannot replace a newer file", async () => {
  let finish;
  const h = await harness(undefined, undefined, {hash: "#snapshot=" + SNAPSHOT_ID,
    retrieveSnapshot: path => path.endsWith(SNAPSHOT_ID) ? new Promise(resolve => { finish = resolve; }) : response(savedFile(OTHER_ID))});
  await h.hash("#snapshot=" + OTHER_ID);
  finish(response({error: "Old request failed"}, 500)); await settle();
  assert.equal(h.journeys.length, 1);
  assert.equal(h.element("#notice").textContent, "");
  h.element("#journey-copy-link").onclick(); await settle();
  assert.deepEqual(h.copied, ["http://127.0.0.1:7781/#snapshot=" + OTHER_ID]);
});

test("leaving a pending file link cancels its eventual response", async () => {
  let finish;
  const h = await harness(undefined, undefined, {hash: "#snapshot=" + SNAPSHOT_ID,
    retrieveSnapshot: () => new Promise(resolve => { finish = resolve; })});
  await h.hash("");
  finish(response(savedFile())); await settle();
  assert.equal(h.journeys.length, 0);
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
});

function pagedDetail(trace, path) {
  const params = new URLSearchParams(path.split("?")[1]);
  const identity = {segmentId: trace.segmentId, traceId: trace.traceId, service: trace.service, serviceInstanceId: trace.serviceInstanceId};
  if (path.includes("overview")) return response({...trace, events: [], eventCount: 60000, through: 60000, captureMode: "full", detailsAvailable: true});
  if (path.includes("execution")) return response({...model(trace.segmentId), identity});
  if (path.includes("request-data")) return response({...identity, attributes: {httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json", httpRequestBody: '{"full":"captured value"}'}});
  const after = params.has("aroundSeq") ? Math.max(0, Number(params.get("aroundSeq")) - 3) : Number(params.get("after") || 0);
  const events = Array.from({length: Math.min(500, 60000 - after)}, (_, index) => ({type: "LINE", thread: "worker", seq: after + index + 1,
    eventIndex: after + index, className: "com.example.Feature", line: index + 1, sourceLine: "retained source " + (after + index + 1)}));
  return response({identity, after, through: 60000, events, nextAfter: after + events.length, hasMore: after + events.length < 60000});
}

test("large-trace flow fetches no raw events until Timeline and renders only its visible bounded page", async () => {
  const trace = segment(), h = await harness(undefined, undefined, {capabilities: {largeTraceDetails: true}, retrieveDetail: path => pagedDetail(trace, path)});
  await h.options.openSegment(trace); await settle();
  assert.equal(h.calls.filter(call => call.path.includes("segment-overview/")).length, 1);
  assert.equal(h.calls.filter(call => call.path.includes("segment-execution/")).length, 1);
  assert.equal(h.calls.filter(call => call.path.includes("segment-events/") || call.path.includes("render-data/") || call.method === "POST").length, 0);
  h.element("#execution-timeline-tab").onclick(); await settle();
  const rows = () => h.element("#timeline").childNodes.filter(node => node.dataset.eventIndex !== undefined);
  assert.equal(rows().length, 500); assert.equal(rows()[0].dataset.eventIndex, "0");
  assert.match(h.element("#timeline-pages").textContent, /500 events.*captured through 60000/);
  h.element("#timeline-pages").childNodes.at(-1).onclick(); await settle();
  assert.equal(rows().length, 500); assert.equal(rows()[0].dataset.eventIndex, "500");
  assert.equal(h.element("#timeline").querySelector('[data-event-index="0"]'), undefined);
  h.element("#timeline-pages").childNodes[1].onclick(); await settle();
  assert.equal(rows()[0].dataset.eventIndex, "0");
});

test("large-trace source navigation fetches a late event page and preserves raw source indices", async () => {
  const trace = segment(), h = await harness(undefined, undefined, {capabilities: {largeTraceDetails: true}, retrieveDetail: path => pagedDetail(trace, path)});
  await h.options.openSegment(trace); await settle();
  await h.renders.at(-1).config.onRevealEvidence({entry: {index: 59989, seq: 59990}}); await settle();
  assert.ok(h.calls.some(call => call.path.includes("aroundSeq=59990")));
  const row = h.element("#timeline").querySelector('[data-event-index="59989"]');
  assert.ok(row.focused && row.scrolled && row.classList.contains("evidence-highlight"));
  assert.equal(h.element("#execution-timeline-tab").attributes["aria-pressed"], "true");
  assert.match(h.element("#timeline").querySelector('[data-source-event-index="59989"]').textContent, /retained source 59990/);
  const first = h.element("#timeline-pages").childNodes.find(node => node.textContent === "First page");
  assert.equal(first.disabled, false); first.onclick(); await settle();
  assert.ok(h.element("#timeline").querySelector('[data-event-index="0"]'));
});

test("large-trace request data disclosure does not load raw event pages or payloads before expansion", async () => {
  const trace = segment(), h = await harness(undefined, undefined, {capabilities: {largeTraceDetails: true}, retrieveDetail: path => pagedDetail(trace, path)});
  await h.options.openSegment(trace); await settle();
  assert.equal(h.calls.filter(call => call.path.includes("request-data/")).length, 0);
  const disclosure = h.element("#trace-meta").childNodes.find(node => node.className === "request-data");
  disclosure.open = true; await disclosure.ontoggle();
  assert.match(disclosure.textContent, /captured value/);
  assert.equal(h.calls.filter(call => call.path.includes("segment-events/") || call.path.includes("render-data/")).length, 0);
});

test("v2 captures page only saved chunks and expose an archive even after the live trace disappears", async () => {
  const trace = segment(), h = await harness(undefined, undefined, {retrieveDetail: path => pagedDetail(trace, path)});
  const initial = h.calls.length;
  await h.options.openPin({schemaVersion: 2, storage: "viewer", id: SNAPSHOT_ID, name: "Large saved capture", segments: [trace]}); await settle();
  h.element("#execution-timeline-tab").onclick(); await settle();
  assert.ok(h.calls.slice(initial).every(call => call.path.startsWith(`/api/snapshots/${SNAPSHOT_ID}/segments/one/`)));
  assert.equal(h.element("#timeline").childNodes.filter(node => node.dataset.eventIndex !== undefined).length, 500);
  assert.ok(h.banners[0].childNodes.some(node => node.href === `/api/snapshots/${SNAPSHOT_ID}/download`));
});

test("legacy snapshots also window their timeline without changing retained evidence", async () => {
  const trace = segment(); trace.events = Array.from({length: 1200}, (_, index) => ({type: "LINE", seq: index + 1, thread: "worker", sourceLine: "line " + index}));
  const before = JSON.stringify(trace), h = await harness();
  await h.options.openPin({name: "Legacy", segments: [trace]}); await settle();
  assert.equal(h.element("#timeline").childNodes.filter(node => node.dataset.eventIndex !== undefined).length, 500);
  await h.renders.at(-1).config.onRevealEvidence({entry: {index: 1100, seq: 1101}});
  assert.ok(h.element("#timeline").querySelector('[data-event-index="1100"]').focused);
  assert.equal(JSON.stringify(trace), before);
});

test("older agents use a byte-bounded live fallback with an explicit upgrade note and never substitute saved chunks", async () => {
  const h = await harness(undefined, undefined, {capabilities: {largeTraceDetails: true}, retrieveDetail: () => response({error: "Route unsupported"}, 404)});
  await h.options.openSegment(segment()); await settle();
  assert.match(h.element("#trace-meta").textContent, /legacy read is limited to 5 MiB/);
  assert.equal(h.calls.filter(call => call.path.includes("render-data/")).length, 1);
  const saved = await harness(undefined, () => assert.fail("Saved files must never fetch live legacy export"), {retrieveDetail: () => response({error: "Saved chunk missing"}, 404)});
  await assert.rejects(saved.options.openPin({schemaVersion: 2, storage: "viewer", id: SNAPSHOT_ID, name: "Saved", segments: [segment()]}), /Saved chunk missing/);
});

test("overview mode exposes counted omissions without claiming method detail exists", async () => {
  const trace = segment(), h = await harness(undefined, undefined, {capabilities: {largeTraceDetails: true}, retrieveDetail: path => path.includes("overview")
    ? response({...trace, events: [], through: 9000, captureMode: "overview", codeDetailAvailable: false, detailTruncated: true, dropReasons: {"capture-mode-overview": 8999}})
    : pagedDetail(trace, path)});
  await h.options.openSegment(trace); await settle();
  assert.match(h.element("#trace-meta").textContent, /Method\/line detail was not captured/);
  assert.match(h.element("#trace-meta").textContent, /capture-mode-overview: 8999/);
  assert.match(h.element("#trace-meta").textContent, /DETAILS INCOMPLETE.*overview/);
});

test("large HTTP caller navigation reads its anchored page, preserving raw indices and captured source", async () => {
  const doc = journey(), caller = doc.segments[0];
  const call = {spanId: "abcdef0123456789", httpMethod: "POST", path: "/features/search", peer: "localhost:8082",
    callingContextStatus: "recorded", callingEventSeq: "59999", callingThread: "worker", downstreamSegmentKeys: [],
    callKey: "root-key:call:0", ownerSegmentKey: caller.segmentKey, service: caller.service, serviceInstanceId: caller.serviceInstanceId};
  caller.httpCalls = [call]; doc.httpCalls = [call];
  const node = {kind: "http-call", callKey: call.callKey, segmentId: caller.segmentId, segmentKey: caller.segmentKey,
    service: caller.service, serviceInstanceId: caller.serviceInstanceId};
  const h = await harness(undefined, undefined, {journey: doc, capabilities: {largeTraceDetails: true}, retrieveDetail: path => {
    if (!path.includes("events")) return pagedDetail(caller, path);
    const identity = {traceId: caller.traceId, segmentId: caller.segmentId, service: caller.service, serviceInstanceId: caller.serviceInstanceId};
    return response({identity, after: 59997, through: 60000, nextAfter: 60000, hasMore: false, events: [
      {type: "METHOD_ENTER", seq: 59998, eventIndex: 59997, thread: "worker", className: "com.example.FeatureClient", methodName: "search"},
      {type: "LINE", seq: 59999, eventIndex: 59998, thread: "worker", className: "com.example.FeatureClient", line: 80, sourceLine: "sendCapturedRequest();"}
    ]});
  }});
  await h.options.openRequest({traceId: doc.traceId}); await settle();
  h.journeys.at(-1).config.onOpenBoundary(node); await settle();
  assert.ok(h.calls.some(call => call.path.includes("aroundSeq=59999")));
  assert.ok(h.element("#timeline").querySelector('[data-event-index="59998"]').focused);
  assert.match(h.element("#boundary-code-context").textContent, /FeatureClient.search\(\):80/);
  assert.equal(h.calls.filter(call => call.path.includes("render-data/")).length, 0);
});
