"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const insights = require("../tracelens_cli/web/request-insights.js");
const script = fs.readFileSync(require.resolve("../tracelens_cli/web/request-insights.js"), "utf8");
const traceId = "0123456789abcdef0123456789abcdef";
const segment = (service, extra = {}) => ({service, serviceInstanceId: service + "-instance", segmentId: service + "-segment",
  segmentKey: service + "-key", traceId, entryChannel: "HTTP", httpStatus: 200, status: "COMPLETED", ...extra});
const journey = (segments, extra = {}) => ({traceId, segments, links: [], httpCalls: [], externalCalls: [], ...extra});
const entry = root => ({entry: {known: true, segment: root}});
const link = (from, to) => ({fromSegmentKey: from.segmentKey, toSegmentKey: to.segmentKey});
const call = (owner, extra = {}) => ({ownerSegmentKey: owner.segmentKey, service: owner.service,
  serviceInstanceId: owner.serviceInstanceId, segmentId: owner.segmentId, callKey: "call-1", eventSeq: 9, status: "ERROR", ...extra});
const boundaryNode = (owner, extra = {}) => ({...call(owner), segmentKey: owner.segmentKey, kind: "dependency-call",
  id: "boundary-1", eventSeq: 9, ...extra});
const kinds = value => value.items.map(item => item.kind);
const freeze = value => {
  if (value && typeof value === "object" && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
};

function fixture(request, options = {}) {
  const created = [];
  class Element {
    constructor(tag, doc) { this.tagName = tag; this.ownerDocument = doc; this.childNodes = []; this.attributes = {}; this.listeners = {};
      this.text = ""; this.className = ""; this.hidden = false; this.disabled = false; this.open = false; created.push(this); }
    set innerHTML(_) { assert.fail("Evidence must never be interpreted as HTML"); }
    set textContent(value) { this.replaceChildren(); this.text = String(value); }
    get textContent() { return this.text + this.childNodes.map(child => child.textContent).join(" "); }
    append(...nodes) { for (const node of nodes) { node.remove(); node.parentNode = this; this.childNodes.push(node); } }
    replaceChildren(...nodes) { this.childNodes.forEach(node => { node.parentNode = null; }); this.childNodes = []; this.text = ""; this.append(...nodes); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(node => node !== this); this.parentNode = null; }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    addEventListener(name, action) { (this.listeners[name] ||= []).push(action); }
    removeEventListener(name, action) { this.listeners[name] = (this.listeners[name] || []).filter(value => value !== action); }
    async click() { for (const action of this.listeners.click || []) await action(); }
    all() { return this.childNodes.flatMap(node => [node, ...node.all()]); }
  }
  const document = {createElement: tag => new Element(tag, document)}, container = document.createElement("div");
  const controller = insights.render(container, request, options);
  return {container, controller, buttons: () => container.all().filter(node => node.tagName === "button"),
    listeners: () => created.reduce((n, node) => n + Object.values(node.listeners).reduce((a, values) => a + values.length, 0), 0)};
}

test("browser and Node expose a buildless metadata-only API with no I/O", () => {
  const forbidden = () => assert.fail("Summary must not read traffic, events, storage or timers");
  const context = {fetch: forbidden, XMLHttpRequest: forbidden, setTimeout: forbidden, setInterval: forbidden,
    localStorage: new Proxy({}, {get: forbidden}), indexedDB: new Proxy({}, {get: forbidden})};
  vm.runInNewContext(script, context);
  assert.deepEqual(Object.keys(insights), ["summarize", "render", "LIMITS"]);
  assert.equal(typeof context.TraceLensRequestInsights.summarize, "function");
  assert.doesNotMatch(script, /\b(?:fetch|XMLHttpRequest|localStorage|indexedDB|setTimeout|setInterval|innerHTML)\b/);
  assert.equal(insights.LIMITS.visible, 3);
});

test("empty or healthy metadata does not invent a diagnosis or generic caveat panel", () => {
  for (const input of [null, undefined, {}, "invalid", [], journey([segment("app")])]) {
    const value = insights.summarize(input);
    assert.deepEqual(value.items, []); assert.deepEqual(value.captures, []);
    const h = fixture(input); assert.equal(h.container.childNodes.length, 0); assert.equal(h.container.hidden, true); h.controller.dispose();
  }
  assert.deepEqual(kinds(insights.summarize(journey([]))), ["no-segments"]);
});

test("root HTTP 200 plus explicitly linked HTTP 500 is first without asserting recovery or root cause", () => {
  const app = segment("cb-app"), go = segment("GO", {httpStatus: 500});
  const result = insights.summarize(journey([app, go], {links: [link(app, go)]}), entry(app));
  assert.equal(result.items[0].kind, "downstream-http-failure");
  assert.match(result.items[0].title, /Entry HTTP 200; GO returned HTTP 500/);
  assert.match(result.items[0].detail, /does not establish recovery or business success/);
  assert.equal(result.items[0].segment.segmentId, go.segmentId);
  assert.doesNotMatch(result.items[0].title, /root cause|successful request|recovered/i);
});

test("a multi-hop linked path supports downstream wording without comparing timestamps", () => {
  const app = segment("app", {startedAtMillis: 2000}), ems = segment("ems", {startedAtMillis: 1}),
    go = segment("go", {httpStatus: 503, startedAtMillis: 9999});
  const result = insights.summarize(journey([go, ems, app], {links: [link(ems, go), link(app, ems)]}), entry(app));
  assert.equal(result.items[0].kind, "downstream-http-failure");
  assert.doesNotMatch(JSON.stringify(result), /duration|2000|9999/);
});

test("matching trace, service names, parent metadata or timing do not replace causal links", () => {
  const app = segment("app"), ems = segment("ems", {httpStatus: 500, parentSpanId: "same", parentSegmentId: app.segmentId});
  const result = insights.summarize(journey([app, ems]), entry(app));
  assert.deepEqual(kinds(result), ["segment-failure"]);
  assert.doesNotMatch(result.items[0].title, /Entry|downstream/);
});

test("missing, ambiguous or conflicting entry never produces root-success claims", () => {
  const app = segment("app"), ems = segment("ems", {httpStatus: 500});
  for (const options of [{}, {entry: {known: false, segment: app}}, entry({...app, traceId: "other"}), entry({...app, segmentId: "wrong"})]) {
    const result = insights.summarize(journey([app, ems], {links: [link(app, ems)]}), options);
    assert.ok(!kinds(result).includes("downstream-http-failure"));
  }
  const gap = insights.summarize(journey([ems]), {entry: {known: false, segment: ems, gatewayMissing: "cb-app"}});
  assert.match(gap.items.find(item => item.kind === "entry-unknown").title, /cb-app entry is not observed/);
  assert.doesNotMatch(JSON.stringify(gap), /recording disabled|filtered|expired|excluded by configuration/);
});

test("incoming links or cycles invalidate a supplied root rather than manufacture ordering", () => {
  const app = segment("app"), ems = segment("ems", {httpStatus: 500});
  const result = insights.summarize(journey([app, ems], {links: [link(app, ems), link(ems, app)]}), entry(app));
  assert.ok(!kinds(result).includes("downstream-http-failure"));
  assert.ok(kinds(result).includes("entry-unconfirmed"));
});

test("explicit segment failure, HTTP errors and job outcomes are supported without exception inference", () => {
  const observations = [segment("a", {httpStatus: "404"}), segment("b", {httpStatus: null, status: "FAILED"}),
    segment("c", {httpStatus: null, asyncTimeout: true}), segment("d", {httpStatus: null, entryChannel: "SCHEDULER", jobOutcome: "FAILED"}),
    segment("e", {httpStatus: null, attributes: {httpStatus: "502"}})];
  assert.equal(insights.summarize(journey(observations)).items.filter(item => item.kind === "segment-failure").length, 5);
  for (const extra of [{exceptionCount: 7}, {filterException: "SomeException"}, {hasExceptions: true}, {hasErrors: true},
    {entryChannel: "SCHEDULER", jobOutcome: "UNKNOWN"}, {httpStatus: "500oops"}]) {
    assert.deepEqual(insights.summarize(journey([segment("app", extra)])).items, []);
  }
});

test("only explicit dependency errors count; SUBMITTED and wrapper metadata do not become failures", () => {
  const app = segment("app"), valid = call(app, {boundaryKind: "database", evidenceKind: "jdbc-execution"});
  const result = insights.summarize(journey([app], {externalCalls: [valid, {...valid, callKey: "call-2"}],
    httpCalls: [call(app, {httpStatus: 503})]}));
  assert.equal(result.items.filter(item => item.kind === "dependency-failure").length, 2);
  assert.match(result.items[0].detail, /caller-side observation/);
  assert.match(result.items[1].title, /2 database calls/);
  for (const change of [{status: "SUBMITTED"}, {status: "OBSERVED", errorType: "SomeException"}, {evidenceKind: "wrapper-entry"}, {boundaryKind: "database-route"}]) {
    assert.deepEqual(insights.summarize(journey([app], {externalCalls: [{...valid, ...change}]})).items, []);
  }
});

test("ambiguous and cross-service owners never produce a link to a similarly named segment", () => {
  const app = segment("app"), other = segment("other", {segmentKey: app.segmentKey});
  for (const doc of [journey([app, other], {httpCalls: [call(app)]}),
    journey([app], {httpCalls: [call(app, {serviceInstanceId: "different"})]}),
    journey([app], {httpCalls: [call(app, {ownerSegmentKey: "missing"})]})]) {
    const result = insights.summarize(doc);
    assert.ok(!kinds(result).includes("dependency-failure"));
    assert.ok(kinds(result).includes("unattributed-evidence"));
    assert.ok(result.items.every(item => item.segment === null));
  }
});

test("duplicate segment identity disables navigation and root-success comparisons", () => {
  const app = segment("app"), bad = segment("ems", {httpStatus: 500});
  const result = insights.summarize(journey([app, {...app}, bad], {links: [link(app, bad)]}), entry(app));
  assert.ok(!kinds(result).includes("downstream-http-failure"));
  const same = insights.summarize(journey([bad, {...bad}]));
  assert.ok(same.items.every(item => item.segment === null));
});

test("foreign trace segments are excluded and the attribution gap stays explicit", () => {
  const wrong = segment("wrong", {traceId: "different", httpStatus: 500});
  const result = insights.summarize(journey([wrong]));
  assert.equal(result.segmentsInspected, 0);
  assert.ok(kinds(result).includes("unattributed-evidence"));
  assert.ok(!kinds(result).includes("segment-failure"));
});

test("detail availability and truncation remain distinct from overview projection limits", () => {
  const result = insights.summarize(journey([segment("missing", {detailsAvailable: false}),
    segment("short", {detailTruncated: true}), segment("drops", {omittedDetailCallbacks: 4}),
    segment("overview", {overviewTruncated: true})]));
  assert.ok(kinds(result).includes("details-unavailable"));
  assert.equal(result.items.filter(item => item.kind === "details-truncated").length, 2);
  assert.ok(kinds(result).includes("overview-limited"));
  assert.equal(result.items.find(item => item.kind === "details-unavailable").segment, null);
  assert.doesNotMatch(JSON.stringify(result), /scope excluded|recording off|restart to fix|budget exceeded/);
});

test("per-service capture modes are reported without promising recorded methods or lines", () => {
  const result = insights.summarize(journey([segment("a", {captureMode: "overview"}),
    segment("b", {captureMode: "methods"}), segment("c", {captureMode: "detailed"}),
    segment("d", {codeDetailAvailable: false}), segment("e", {codeDetailAvailable: true}),
    segment("unknown", {captureMode: "not-a-mode"})]));
  assert.equal(result.captures.length, 5);
  assert.match(result.captures[0].detail, /availability is not established/);
  assert.match(result.captures[1].detail, /line events excluded/);
  assert.equal(result.captures[2].detail, "Reported capture: Detailed");
  assert.match(result.captures[3].detail, /method \/ line detail unavailable/);
  assert.match(result.captures[4].detail, /capture mode unknown/);
  assert.doesNotMatch(JSON.stringify(result), /all code|complete coverage|methods recorded|all services use/);
});

test("attribute-backed capture and availability flags are supported without overriding explicit top-level values", () => {
  const result = insights.summarize(journey([
    segment("methods", {attributes: {captureMode: "methods", codeDetailAvailable: "true"}}),
    segment("missing", {attributes: {detailsAvailable: "false"}}),
    segment("truncated", {attributes: {detailTruncated: "true", omittedDetailCallbacks: "4"}}),
    segment("limited", {attributes: {overviewMetadataTruncated: "true"}}),
    segment("top", {captureMode: "detailed", detailsAvailable: true, attributes: {captureMode: "methods", detailsAvailable: false}})
  ]));
  assert.match(result.captures.find(row => row.service === "methods").detail, /Methods.*line events excluded/);
  assert.ok(kinds(result).includes("details-unavailable"));
  assert.ok(kinds(result).includes("details-truncated"));
  assert.ok(kinds(result).includes("overview-limited"));
  assert.equal(result.captures.find(row => row.service === "top").detail, "Reported capture: Detailed");
});

test("partial flags produce one concise gap while concrete gaps avoid a duplicate generic warning", () => {
  assert.deepEqual(kinds(insights.summarize(journey([segment("app")], {partial: true}))), ["partial-request"]);
  const value = insights.summarize(journey([segment("app", {detailTruncated: true})], {partial: true}));
  assert.ok(!kinds(value).includes("partial-request"));
  const error = insights.summarize(journey([segment("app")], {errors: [{service: "ems", error: "SECRET error text"}]}));
  assert.ok(kinds(error).includes("service-unavailable"));
  assert.doesNotMatch(JSON.stringify(error), /SECRET/);
});

test("summary never reads events, source, request fields, SQL, bindings or raw error text", () => {
  const app = segment("app", {status: "FAILED", captureMode: "methods"});
  const fail = call(app, {boundaryKind: "database", evidenceKind: "jdbc-execution"});
  for (const value of [app, fail]) for (const field of ["events", "sql", "sqlParameters", "httpRequestBody", "httpQueryParameters", "source", "exceptionMessage", "errorType"])
    Object.defineProperty(value, field, {get() { assert.fail("Read sensitive/expanded field: " + field); }});
  const output = insights.summarize(journey([app], {externalCalls: [fail]}));
  assert.equal(output.items.length, 2); assert.equal(output.captures.length, 1);
});

test("boundary navigation uses an exact graph owner and a payload-free identity projection", async () => {
  const app = segment("app"), raw = call(app, {boundaryKind: "database", evidenceKind: "jdbc-execution"});
  const node = boundaryNode(app, {boundaryKind: "database", evidenceKind: "jdbc-execution",
    sql: "SECRET SQL", destination: "scheme://user:SECRET@host", httpRequestBody: "SECRET body"});
  const opened = [], h = fixture(journey([app], {externalCalls: [raw], visualization: {traceId, nodes: [node]}}),
    {onOpenBoundary: value => opened.push(value), onOpenSegment: () => assert.fail("Exact boundary should win")});
  assert.equal(opened.length, 0); await h.buttons()[0].click();
  assert.equal(opened.length, 1); assert.equal(opened[0].eventSeq, 9); assert.equal(opened[0].segmentKey, app.segmentKey);
  assert.equal(opened[0].status, "ERROR"); assert.equal(opened[0].evidenceKind, "jdbc-execution");
  assert.doesNotMatch(JSON.stringify(h.controller.summary), /SECRET|destination|httpRequestBody|sql/);
  h.controller.dispose(); assert.equal(h.listeners(), 0);
});

test("missing or mismatched graph boundary safely falls back to owner details", async () => {
  const app = segment("app"), raw = call(app, {boundaryKind: "cache", evidenceKind: "client-boundary"});
  for (const graph of [{nodes: [boundaryNode(app, {serviceInstanceId: "wrong"})]},
    {nodes: [boundaryNode(app), boundaryNode(app)]}, {traceId: "wrong", nodes: [boundaryNode(app)]}]) {
    const opened = [], h = fixture(journey([app], {externalCalls: [raw], visualization: graph}),
      {onOpenSegment: value => opened.push(value), onOpenBoundary: () => assert.fail("Wrong boundary must not open")});
    await h.buttons()[0].click(); assert.equal(opened[0].segmentKey, app.segmentKey); h.controller.dispose();
  }
});

test("graph-only loaded metadata works without expanding raw events", () => {
  const app = segment("app"), node = boundaryNode(app, {boundaryKind: "messaging", evidenceKind: "client-boundary"});
  const result = insights.summarize({traceId, segments: [app], visualization: {nodes: [node]}});
  assert.deepEqual(kinds(result), ["dependency-failure"]);
});

test("duplicate call keys and foreign graph or call identities never become attributed failures", () => {
  const app = segment("app"), node = boundaryNode(app, {boundaryKind: "messaging", evidenceKind: "client-boundary"});
  for (const input of [journey([app], {httpCalls: [call(app), call(app)]}),
    journey([app], {httpCalls: [call(app, {traceId: "foreign"})]}),
    {traceId, segments: [app], visualization: {traceId: "foreign", nodes: [node]}}]) {
    const result = insights.summarize(input);
    assert.ok(!kinds(result).includes("dependency-failure"));
    assert.ok(kinds(result).includes("unattributed-evidence"));
  }
});

test("boundary metadata and exact event sequence must agree before offering calling evidence", async () => {
  const app = segment("app"), raw = call(app, {boundaryKind: "database", evidenceKind: "jdbc-execution"});
  for (const change of [{eventSeq: 10}, {boundaryKind: "cache"}, {evidenceKind: "client-boundary"}]) {
    const opened = [], node = boundaryNode(app, {...raw, ...change});
    const h = fixture(journey([app], {externalCalls: [raw], visualization: {nodes: [node]}}),
      {onOpenSegment: value => opened.push(value), onOpenBoundary: () => assert.fail("Mismatched boundary must not open")});
    await h.buttons()[0].click(); assert.equal(opened[0].segmentKey, app.segmentKey); h.controller.dispose();
  }
});

test("HTTP calling evidence requires an exact journey call with a valid span identity", async () => {
  const app = segment("app"), raw = call(app, {httpStatus: 502, spanId: "0123456789abcdef"});
  const node = boundaryNode(app, {kind: "http-call", httpStatus: 502});
  const opened = [], h = fixture(journey([app], {httpCalls: [raw], visualization: {nodes: [node]}}),
    {onOpenBoundary: value => opened.push(value), onOpenSegment: () => assert.fail("Exact HTTP call should win")});
  await h.buttons()[0].click(); assert.equal(opened[0].callKey, raw.callKey); h.controller.dispose();
  for (const input of [journey([app], {httpCalls: [{...raw, spanId: null}], visualization: {nodes: [node]}}),
    journey([app], {httpCalls: [{...raw, spanId: "not-an-id"}], visualization: {nodes: [node]}}),
    {traceId, segments: [app], visualization: {nodes: [{...node, spanId: raw.spanId}]}}]) {
    const owners = [], fallback = fixture(input, {onOpenSegment: value => owners.push(value),
      onOpenBoundary: () => assert.fail("Missing journey call identity must fall back")});
    await fallback.buttons()[0].click(); assert.equal(owners[0].segmentId, app.segmentId); fallback.controller.dispose();
  }
});

test("bounded input prefixes are never scanned past their limit and omissions stay visible", () => {
  const guard = (size, limit, create) => {
    const rows = Array.from({length: size}, (_, index) => index < limit ? create(index) : null);
    return new Proxy(rows, {get(target, property, receiver) {
      if (/^\d+$/.test(String(property)) && Number(property) >= limit) assert.fail("Read beyond bound");
      return Reflect.get(target, property, receiver);
    }});
  };
  const input = {segments: guard(10000, insights.LIMITS.segments, index => segment("s" + index, {status: "FAILED", captureMode: "detailed"})),
    links: guard(10000, insights.LIMITS.links, () => ({})),
    httpCalls: guard(10000, insights.LIMITS.calls, () => ({})), externalCalls: guard(10000, insights.LIMITS.calls, () => ({})),
    visualization: {nodes: guard(10000, insights.LIMITS.nodes, () => ({}))}};
  const result = insights.summarize(input);
  assert.equal(result.items.length, insights.LIMITS.items); assert.equal(result.captures.length, insights.LIMITS.captures);
  assert.equal(result.limited, true); assert.ok(result.omittedInputs > 0); assert.ok(result.omittedItems > 0);
  assert.ok(result.items.every(item => !item.segment), "Unscanned duplicate identities cannot become safe navigation targets");
});

test("link budget prevents claiming a rooted causal path from a partial prefix", () => {
  const app = segment("app"), bad = segment("bad", {httpStatus: 500});
  const links = [link(app, bad), ...Array.from({length: insights.LIMITS.links}, () => ({}))];
  const result = insights.summarize(journey([app, bad], {links}), entry(app));
  assert.ok(!kinds(result).includes("downstream-http-failure")); assert.equal(result.limited, true);
});

test("render keeps only three prioritized rows expanded and capture detail collapsed", () => {
  const rows = Array.from({length: 20}, (_, index) => segment("service" + index, {httpStatus: 500, captureMode: "methods"}));
  const h = fixture(journey(rows));
  const section = h.container.childNodes[0], visible = section.childNodes.find(node => node.tagName === "ul");
  assert.equal(visible.childNodes.length, 3);
  const details = section.childNodes.filter(node => node.tagName === "details");
  assert.equal(details.length, 2); assert.ok(details.every(node => !node.open));
  assert.match(section.textContent, /compact summary is bounded/);
  assert.equal(section.attributes["aria-label"], "Request evidence to inspect");
  h.controller.dispose();
});

test("render unhides an initially hidden host for evidence and hides it again for healthy metadata", () => {
  const h = fixture(journey([segment("app")]));
  assert.equal(h.container.hidden, true);
  const evidence = insights.render(h.container, journey([segment("app", {status: "FAILED"})]));
  assert.equal(h.container.hidden, false); assert.ok(h.container.childNodes.length);
  evidence.dispose();
  const healthy = insights.render(h.container, journey([segment("app")]));
  assert.equal(h.container.hidden, true); assert.equal(h.container.childNodes.length, 0); healthy.dispose();
});

test("untrusted labels are bounded text, never markup, event handlers or URLs", () => {
  const hostile = '<img src=x onerror="alert(1)">\u202e' + "x".repeat(1000);
  const h = fixture(journey([segment(hostile, {httpStatus: 500})]));
  const content = h.container.textContent;
  assert.ok(content.length < 1000); assert.doesNotMatch(content, /\u202e/);
  assert.ok(h.container.all().every(node => !["img", "script", "a"].includes(node.tagName)));
  assert.equal(h.buttons().length, 0); h.controller.dispose();
});

test("callbacks run only on explicit accessible buttons and cannot leak their error text", async () => {
  const app = segment("app", {status: "FAILED"}); let calls = 0;
  const h = fixture(journey([app]), {onOpenSegment: async target => { calls++; assert.equal(target.segmentId, app.segmentId); throw Error("SECRET body"); }});
  assert.equal(calls, 0); const button = h.buttons()[0];
  assert.equal(button.type, "button"); assert.match(button.attributes["aria-label"], /Inspect service evidence/);
  await button.click(); assert.equal(calls, 1); assert.equal(button.disabled, false);
  const feedback = h.container.all().find(node => node.attributes.role === "status");
  assert.equal(feedback.hidden, false); assert.match(feedback.textContent, /could not be opened/); assert.doesNotMatch(feedback.textContent, /SECRET/);
  h.controller.dispose(); await button.click(); assert.equal(calls, 1); assert.equal(h.listeners(), 0);
});

test("late callback completion after dispose does not alter replacement content", async () => {
  let reject;
  const pending = new Promise((_, fail) => { reject = fail; });
  const h = fixture(journey([segment("app", {status: "FAILED"})]), {onOpenSegment: () => pending});
  const action = h.buttons()[0].click(); h.controller.dispose();
  h.container.textContent = "New request"; reject(Error("old failure")); await action;
  assert.equal(h.container.textContent, "New request");
});

test("frozen source evidence is unchanged by summary, render and navigation", async () => {
  const app = segment("app", {status: "FAILED", captureMode: "detailed"});
  const input = freeze(journey([app])), before = JSON.stringify(input);
  const h = fixture(input, {onOpenSegment: target => { target.segmentId = "callback-local change"; }});
  await h.buttons()[0].click(); assert.equal(JSON.stringify(input), before); h.controller.dispose();
});
