"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const requests = require("../tracelens_cli/web/request-model.js");

const TRACE = "11111111111111111111111111111111";
const row = (extra = {}) => ({ traceId: TRACE, segmentId: "root", service: "cb-app", serviceInstanceId: "app-1",
  status: "COMPLETED", httpMethod: "GET", httpStatus: 200, path: "/api/features", entryChannel: "HTTP",
  startedAtMillis: Date.now() - 1000, durationMs: 40, user: "alice", siteId: "42", ...extra });
const request = () => requests.groupTraces([row(), row({ segmentId: "child", service: "cb-ems", serviceInstanceId: "ems-1",
  parentSpanId: "1234567890abcdef", httpStatus: 503, path: "/features", durationMs: 10 })])[0];
function doc(extra = {}) {
  const observed = request();
  return { schemaVersion: 1, kind: "error-groups", scope: "retained-only", partial: false, limited: false,
    warnings: [], errors: [], counts: {loadedSegments: 2, loadedRequests: 1, matchingRequests: 1, matchingSegments: 1, groups: 1},
    requests: [observed], groups: [{id: "ems-503", service: "cb-ems", channel: "HTTP", method: "GET", route: "/features",
      routeKind: "template", category: "http5xx", httpStatus: 503, exceptionClass: "java.lang.IllegalStateException",
      requestCount: 1, segmentCount: 1, firstSeenMillis: 1000, lastSeenMillis: 2000, requestKeys: [observed.key]}], ...extra };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
const clone = value => JSON.parse(JSON.stringify(value));

function harness(api = async () => doc()) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.type = ""; this.value = ""; this.className = "";
      this.childNodes = []; this.listeners = {}; this.attrs = {}; this.checked = false;
      this.classList = {
        contains: name => this.className.split(" ").includes(name),
        add: (...names) => { this.className = [...new Set([...this.className.split(" ").filter(Boolean), ...names])].join(" "); },
        toggle: (name, force) => {
          const names = new Set(this.className.split(" ").filter(Boolean));
          const add = force === undefined ? !names.has(name) : force;
          if (add) names.add(name); else names.delete(name);
          this.className = [...names].join(" ");
        },
      };
    }
    set innerHTML(_) { throw new Error("Unsafe HTML interpolation"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...children) { this.childNodes.push(...children); }
    replaceChildren(...children) { this.text = ""; this.childNodes = [...children]; }
    setAttribute(name, value) { this.attrs[name] = String(value); }
    getAttribute(name) { return this.attrs[name]; }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    fire(name) { if (this["on" + name]) this["on" + name]({target: this}); (this.listeners[name] || []).forEach(handler => handler({target: this})); }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    focus() {}
  }
  const nodes = new Map();
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  for (const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const node = new Element(match[1]);
    node.type = /\btype="([^"]+)"/.exec(match[2])?.[1] || "";
    node.className = /\bclass="([^"]+)"/.exec(match[2])?.[1] || "";
    nodes.set(match[3], node);
  }
  const timers = new Map(); let nextTimer = 0;
  const calls = [], saves = []; let refreshes = 0;
  const store = { views: [], async listViews() { return this.views; }, async listPins() { return []; },
    async saveView(name, filters) { const view = {id: "saved", name, filters: clone(filters)}; saves.push(view); this.views = [view]; return view; } };
  const context = {window: { TraceLensRequests: requests, TraceLensBrowserStore: {create: () => store} },
    document: {getElementById: id => { assert.ok(nodes.has(id), "Unknown element " + id); return nodes.get(id); }, createElement: tag => new Element(tag)},
    Option: function (label, value) { const node = new Element("option"); node.textContent = label; node.value = value; return node; },
    URLSearchParams, Date, confirm: () => true,
    setTimeout: fn => { const id = ++nextTimer; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id),
  };
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-ui.js"), "utf8"), context);
  const ui = new context.window.TraceLensRequestUI({
    api: path => { calls.push(path); return api(path); }, refreshLive: async () => { refreshes++; },
    openRequest: async () => { throw new Error("No automatic request opening"); },
    openSegment: async () => { throw new Error("No automatic segment exports"); },
    snapshot: async () => { throw new Error("No automatic pinning"); },
  });
  const get = id => nodes.get(id);
  return { ui, get, calls, saves, store, nodes, refreshes: () => refreshes,
    async flushTimers() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); await settle(); },
    pendingTimers: () => timers.size };
}

test("Errors sends failing-segment filters, preserves common controls, and excludes hidden live-only fields", async () => {
  const h = harness();
  Object.assign(h.ui.filters, {service: "cb-ems", path: "/features?x=1&test=<x>", user: "alice", site: "42", method: "POST", channel: "", sinceMinutes: "",
    outcome: "success", collapse: false, query: "trace", minDuration: "1", maxDuration: "20"});
  Object.assign(h.ui.errorFilters, {category: "http5xx", httpStatus: "503", exceptionClass: "java.lang.IllegalStateException"});
  h.ui.writeFilters();
  const before = clone(h.ui.filters);
  h.ui.changeMode("errors"); await settle();
  assert.equal(h.calls.length, 1);
  const query = new URL(h.calls[0], "http://fixture.invalid").searchParams;
  assert.equal(query.get("service"), "cb-ems"); assert.equal(query.get("path"), before.path);
  assert.equal(query.get("since_minutes"), ""); assert.equal(query.get("channel"), "");
  assert.equal(query.get("category"), "http5xx"); assert.equal(query.get("http_status"), "503");
  assert.equal(query.get("exception_class"), "java.lang.IllegalStateException");
  assert.equal(query.has("outcome"), false); assert.equal(query.has("collapse"), false);
  assert.equal(h.get("service-filter-label").textContent, "Failing service");
  assert.equal(h.get("outcome-filter-control").classList.contains("hidden"), true);
  assert.equal(h.get("collapse-apis-control").classList.contains("hidden"), true);
  h.ui.changeMode("pins"); h.ui.changeMode("live"); await settle();
  assert.deepEqual(clone(h.ui.filters), before);
  assert.equal(h.get("outcome-filter-control").classList.contains("hidden"), false);
  assert.equal(h.refreshes(), 1);
});

test("debounced edits discard stale responses, including responses delivered after leaving Errors", async () => {
  const pending = [];
  const h = harness(() => new Promise(resolve => pending.push(resolve)));
  h.ui.changeMode("errors"); await settle();
  assert.equal(pending.length, 1);
  h.get("path-filter").value = "/first"; h.get("path-filter").fire("input");
  h.get("path-filter").value = "/second"; h.get("path-filter").fire("input");
  assert.equal(h.pendingTimers(), 1);
  await h.flushTimers(); assert.equal(pending.length, 2);
  const newest = doc(); newest.groups[0].route = "/second";
  pending[1](newest); await settle();
  pending[0](doc()); await settle();
  assert.equal(h.ui.errorDoc.groups[0].route, "/second");
  const fetching = h.ui.refreshErrors(); await settle();
  h.ui.changeMode("pins"); pending[2](doc()); await fetching;
  assert.equal(h.ui.mode, "pins"); assert.equal(h.get("trace-rows").childNodes.length, 0);
  assert.match(h.get("results-summary").textContent, /named browser snapshots/);
});

test("polling reuses an in-flight request and retains group and segment expansion", async () => {
  let resolve;
  const h = harness(() => new Promise(done => { resolve = done; }));
  h.ui.mode = "errors";
  const first = h.ui.refreshErrors(), duplicate = h.ui.refreshErrors();
  await settle();
  assert.equal(h.calls.length, 1); resolve(doc()); await Promise.all([first, duplicate]);
  let toggle = h.get("trace-rows").all().find(node => node.className.includes("error-group-button"));
  toggle.fire("click");
  assert.equal(h.get("trace-rows").childNodes.length, 3);
  assert.match(h.get("trace-rows").textContent, /Root HTTP 200/);
  assert.match(h.get("trace-rows").textContent, /recovery not established/);
  const segments = h.get("trace-rows").all().find(node => node.className === "segment-details");
  segments.open = true; segments.fire("toggle");
  const poll = h.ui.refreshErrors(); await settle(); resolve(doc()); await poll;
  toggle = h.get("trace-rows").all().find(node => node.className.includes("error-group-button"));
  assert.equal(toggle.getAttribute("aria-expanded"), "true");
  assert.equal(h.get("trace-rows").all().find(node => node.className === "segment-details").open, true);
  assert.ok(h.calls.every(path => path.startsWith("/api/error-groups?")));
});

test("signature totals are not summed into unique requests and labels remain inert text", async () => {
  const unsafe = '<img src=x onerror="alert(1)">';
  const result = doc({partial: true, limited: true, warnings: [unsafe], errors: [{service: "cb-ems", error: unsafe}]});
  result.groups[0].route = unsafe; result.groups[0].exceptionClass = unsafe;
  result.groups.push({...result.groups[0], id: "second-signature"}); result.counts.groups = 2;
  const h = harness(async () => result); h.ui.changeMode("errors"); await settle();
  assert.match(h.get("results-summary").textContent, /^1 unique matching retained request\b/);
  assert.match(h.get("results-summary").textContent, /Groups may overlap; not an error rate/);
  assert.ok(h.get("trace-rows").textContent.includes(unsafe));
  assert.ok(h.get("error-state").textContent.includes(unsafe));
  assert.equal([...h.nodes.values()].flatMap(node => node.all()).some(node => node.tagName === "IMG"), false);
  assert.match(h.get("error-state").textContent, /Partial retained evidence/);
  assert.match(h.get("error-state").textContent, /Results are limited/);
});

test("empty, malformed, network-error, and invalid-filter states are explicit without exporting traces", async () => {
  let response = doc({groups: [], requests: [], counts: {loadedRequests: 4, loadedSegments: 6, matchingRequests: 0, matchingSegments: 0, groups: 0}});
  const h = harness(async () => { if (response instanceof Error) throw response; return response; });
  h.ui.changeMode("errors"); await settle();
  assert.equal(h.get("empty").classList.contains("hidden"), false);
  assert.match(h.get("empty").textContent, /does not establish that no failures occurred/);
  response = {}; await h.ui.refreshErrors(); assert.match(h.get("error-state").textContent, /unsupported error-groups response/);
  response = new Error("offline"); await h.ui.refreshErrors(); assert.match(h.get("error-state").textContent, /offline/);
  const calls = h.calls.length;
  h.ui.errorFilters.httpStatus = "503.5"; await h.ui.refreshErrors();
  assert.equal(h.calls.length, calls); assert.match(h.get("error-state").textContent, /exact HTTP status/);
  h.ui.errorFilters.httpStatus = ""; h.ui.filters.minDuration = "100"; h.ui.filters.maxDuration = "10";
  await h.ui.refreshErrors(); assert.equal(h.calls.length, calls); assert.match(h.get("error-state").textContent, /valid duration range/);
});

test("explicit saved Errors views preserve mode and error controls; legacy views still load Live", async () => {
  const h = harness(); h.ui.store = h.store; h.ui.askName = async () => "My failing APIs";
  h.ui.filters.path = "/features"; h.ui.errorFilters = {category: "http5xx", httpStatus: "503", exceptionClass: "java.lang.Error"};
  h.ui.writeFilters(); h.ui.changeMode("errors"); await settle();
  h.get("save-view").fire("click"); await settle();
  assert.equal(h.saves.length, 1);
  assert.equal(h.saves[0].filters.viewMode, "errors");
  assert.equal(h.saves[0].filters.errorCategory, "http5xx");
  assert.equal(h.saves[0].filters.errorStatus, "503");
  assert.equal(h.saves[0].filters.errorException, "java.lang.Error");
  h.ui.changeMode("live"); h.ui.filters.path = "/other"; h.ui.errorFilters.httpStatus = "";
  h.get("saved-view").value = "saved"; h.get("saved-view").fire("change"); await settle();
  assert.equal(h.ui.mode, "errors"); assert.equal(h.ui.filters.path, "/features"); assert.equal(h.get("error-http-status").value, "503");
  h.ui.views.push({id: "legacy", filters: {path: "/legacy", service: "cb-app"}});
  h.get("saved-view").value = "legacy"; h.get("saved-view").fire("change"); await settle();
  assert.equal(h.ui.mode, "live"); assert.equal(h.ui.filters.path, "/legacy");
  assert.ok(h.calls.every(path => path.startsWith("/api/error-groups?")));
});

test("single-viewer service display maps to canonical default only in error queries", async () => {
  const h = harness(async path => path === "/api/viewer-context" ? {namespace: "fixture", mode: "single"} : doc());
  await h.ui.init(); h.ui.filters.service = "cb-app"; h.ui.writeFilters();
  assert.equal(new URL(h.ui.errorQuery(), "http://fixture.invalid").searchParams.get("service"), "default");
  assert.equal(h.ui.filters.service, "cb-app");
  h.ui.viewerMode = "federated";
  assert.equal(new URL(h.ui.errorQuery(), "http://fixture.invalid").searchParams.get("service"), "cb-app");
});

test("live rows stay available after Errors polling and hidden outcome survives reset in Errors", async () => {
  const h = harness(); h.ui.setTraces({traces: [row()]});
  h.ui.filters.outcome = "success"; h.ui.filters.collapse = false; h.ui.writeFilters();
  h.ui.changeMode("errors"); await settle();
  assert.equal(h.ui.requests.length, 1);
  h.get("clear-filters").fire("click"); await h.flushTimers();
  assert.equal(h.ui.filters.outcome, "success"); assert.equal(h.ui.filters.collapse, false);
  h.ui.changeMode("live"); await settle();
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.match(h.get("trace-rows").textContent, /api\/features/);
});

test("synchronous API failure can be retried and never leaves an in-flight request stuck", async () => {
  let fail = true;
  const h = harness(() => { if (fail) throw new Error("temporary setup failure"); return doc(); });
  h.ui.mode = "errors"; await h.ui.refreshErrors();
  assert.match(h.get("error-state").textContent, /temporary setup failure/);
  fail = false; await h.ui.refreshErrors();
  assert.equal(h.calls.length, 2); assert.equal(h.ui.errorDoc.groups.length, 1);
});

test("single-service application labels are display-only and singular Errors counts read correctly", async () => {
  const result = doc();
  result.groups[0].service = "default";
  result.requests[0].services = ["default"];
  for (const segment of result.requests[0].segments) {
    segment.service = "default"; segment.applicationId = "cb-app";
    segment.segmentKey = JSON.stringify(["default", segment.serviceInstanceId, segment.segmentId]);
  }
  const before = JSON.stringify(result);
  const h = harness(async () => result);
  h.ui.viewerMode = "single"; h.ui.changeMode("errors"); await settle();
  const group = h.get("trace-rows").all().find(node => node.className.includes("error-group-button"));
  assert.match(group.textContent, /cb-app · GET/);
  assert.equal(group.title, "Canonical failing service: default");
  assert.match(h.get("trace-rows").textContent, /1 unique request · 1 failing segment/);
  assert.match(h.get("results-summary").textContent, /1 signature \/ 1 loaded request/);
  group.fire("click");
  const row = h.get("trace-rows").childNodes[1];
  assert.ok(row.childNodes[1].textContent.startsWith("cb-app"));
  const opened = [];
  h.ui.options.openRequest = async value => opened.push(value);
  h.ui.options.openSegment = async value => opened.push(value);
  row.all().find(node => node.className === "request-link").fire("click");
  row.all().find(node => node.className === "text-button").fire("click"); await settle();
  assert.equal(opened[0], result.requests[0]); assert.equal(opened[1], result.requests[0].segments[0]);
  assert.equal(opened[1].service, "default"); assert.equal(JSON.stringify(result), before);
  h.ui.viewerMode = "federated"; h.ui.render();
  assert.match(h.get("trace-rows").all().find(node => node.className.includes("error-group-button")).textContent, /default · GET/);
  h.ui.viewerMode = "single";
  assert.equal(h.ui.displayService("default", [{service: "default", applicationId: "one"}, {service: "default", applicationId: "two"}]), "default");
  assert.equal(h.ui.displayService("default", []), "default");
});

const hasClass = (node, name) => node.classList.contains(name);
const headerButton = node => node.all().find(child => hasClass(child, "group-button"));
const headerId = node => headerButton(node).id || headerButton(node).getAttribute("id");
const groupLabels = node => node.all().filter(child => hasClass(child, "group-member-label"));
function assertUngrouped(node) {
  assert.equal(hasClass(node, "group-member"), false);
  assert.equal(hasClass(node, "group-member-last"), false);
  assert.equal(hasClass(node, "group-end"), false);
  assert.equal(node.getAttribute("aria-describedby"), undefined);
  assert.equal(groupLabels(node).length, 0);
}
function repeatedRows() {
  const now = Date.now();
  return [row({startedAtMillis: now - 1000}),
    row({traceId: "22222222222222222222222222222222", segmentId: "second", startedAtMillis: now - 2000}),
    row({traceId: "33333333333333333333333333333333", segmentId: "standalone", path: "/api/other", startedAtMillis: now - 3000})];
}

test("expanded repeated APIs visibly bracket members without marking adjacent individual requests", async () => {
  const h = harness(); const source = repeatedRows(); h.ui.setTraces({traces: source});
  let rows = h.get("trace-rows").childNodes;
  assert.equal(rows.length, 2);
  assert.equal(hasClass(rows[0], "api-group"), true);
  assert.equal(hasClass(rows[0], "group-expanded"), false);
  assertUngrouped(rows[1]);
  headerButton(rows[0]).fire("click");
  rows = h.get("trace-rows").childNodes;
  assert.equal(rows.length, 5);
  assert.equal(hasClass(rows[0], "group-expanded"), true);
  assert.equal(headerButton(rows[0]).getAttribute("aria-expanded"), "true");
  const id = headerId(rows[0]); assert.ok(id);
  for (const [index, member] of rows.slice(1, 3).entries()) {
    assert.equal(hasClass(member, "request-row"), true);
    assert.equal(hasClass(member, "group-member"), true);
    assert.equal(hasClass(member, "api-group-member"), true);
    assert.equal(hasClass(member, "error-group-member"), false);
    assert.equal(member.getAttribute("aria-describedby"), id);
    assert.equal(groupLabels(member).length, 1);
    assert.equal(groupLabels(member)[0].textContent, `Request ${index + 1} of 2`);
    assert.ok(member.childNodes[0].all().includes(groupLabels(member)[0]));
    assert.equal(hasClass(member, "group-member-last"), index === 1);
  }
  assert.equal(hasClass(rows[3], "group-end"), true);
  assert.equal(hasClass(rows[3], "api-group-end"), true);
  assert.match(rows[3].textContent, /^End of group · 2 requests/);
  assertUngrouped(rows[4]);

  const opened = [], pinned = [];
  h.ui.options.openRequest = async value => opened.push(value);
  h.ui.options.openSegment = async value => opened.push(value);
  h.ui.pin = async value => pinned.push(value);
  rows[1].all().find(node => node.className === "request-link").fire("click");
  rows[1].all().find(node => node.className === "text-button").fire("click");
  rows[1].all().find(node => node.tagName === "BUTTON" && node.textContent === "Pin capture").fire("click");
  await settle();
  assert.equal(opened[0], h.ui.requests[0]);
  assert.equal(opened[1], h.ui.requests[0].segments[0]);
  assert.equal(pinned[0], h.ui.requests[0]);

  h.ui.setTraces({traces: source});
  rows = h.get("trace-rows").childNodes;
  assert.equal(hasClass(rows[0], "group-expanded"), true);
  assert.equal(rows[1].getAttribute("aria-describedby"), headerId(rows[0]));
  assert.equal(hasClass(rows[2], "group-member-last"), true);
  assert.equal(hasClass(rows[3], "api-group-end"), true);
  headerButton(rows[0]).fire("click");
  rows = h.get("trace-rows").childNodes;
  assert.equal(rows.length, 2);
  assert.equal(hasClass(rows[0], "group-expanded"), false);
  assert.equal(headerButton(rows[0]).getAttribute("aria-expanded"), "false");
  assert.ok(rows.every(node => !hasClass(node, "group-member") && !hasClass(node, "group-end")));
});

test("collapse-off and pinned captures never inherit membership markers from expanded APIs", () => {
  const h = harness(); h.ui.setTraces({traces: repeatedRows()});
  headerButton(h.get("trace-rows").childNodes[0]).fire("click");
  h.ui.filters.collapse = false; h.ui.render();
  assert.equal(h.get("trace-rows").childNodes.length, 3);
  h.get("trace-rows").childNodes.forEach(assertUngrouped);
  h.ui.pins = [{id: h.ui.requests[0].key, name: "Saved request", capturedAt: Date.now(), request: h.ui.requests[0]}];
  h.ui.changeMode("pins");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assertUngrouped(h.get("trace-rows").childNodes[0]);
  assert.match(h.get("trace-rows").textContent, /Saved request/);
});

test("overlapping error groups have distinct member ownership and preserve it across refreshes", async () => {
  const result = doc();
  result.groups.push({...result.groups[0], id: "second-signature", service: "cb-app", route: "/api/features"});
  result.counts.groups = 2;
  const before = JSON.stringify(result);
  const h = harness(async () => result); h.ui.changeMode("errors"); await settle();
  for (let index = 0; index < 2; index++) {
    const headers = h.get("trace-rows").childNodes.filter(node => hasClass(node, "error-group"));
    headerButton(headers[index]).fire("click");
  }
  function assertExpanded() {
    const rows = h.get("trace-rows").childNodes;
    assert.equal(rows.length, 6);
    const ids = [];
    for (const start of [0, 3]) {
      const [header, member, end] = rows.slice(start, start + 3);
      assert.equal(hasClass(header, "error-group"), true);
      assert.equal(hasClass(header, "group-expanded"), true);
      const id = headerId(header); assert.ok(id); ids.push(id);
      assert.equal(hasClass(member, "error-group-member"), true);
      assert.equal(hasClass(member, "api-group-member"), false);
      assert.equal(hasClass(member, "group-member"), true);
      assert.equal(hasClass(member, "group-member-last"), true);
      assert.equal(member.getAttribute("aria-describedby"), id);
      assert.equal(groupLabels(member)[0].textContent, "Request 1 of 1");
      assert.match(member.textContent, new RegExp(`Trace ${TRACE}`));
      assert.equal(hasClass(end, "group-end"), true);
      assert.equal(hasClass(end, "error-group-end"), true);
      assert.match(end.textContent, /^End of group · 1 request\b/);
    }
    assert.notEqual(ids[0], ids[1], "A shared trace must not duplicate group header IDs");
  }
  assertExpanded(); await h.ui.refreshErrors(); assertExpanded();
  assert.equal(JSON.stringify(result), before, "Presentation metadata must not mutate canonical requests");
  headerButton(h.get("trace-rows").childNodes[0]).fire("click");
  const rows = h.get("trace-rows").childNodes;
  assert.equal(rows.length, 4);
  assert.equal(hasClass(rows[0], "group-expanded"), false);
  assert.equal(hasClass(rows[1], "error-group"), true);
  assert.equal(hasClass(rows[2], "error-group-member"), true);
});

test("partial error groups keep missing-member notices inside the boundary without inflating displayed counts", async () => {
  const result = doc(); result.partial = true;
  result.groups[0].requestKeys.push("trace:missing"); result.groups[0].requestCount = 2;
  const h = harness(async () => result); h.ui.changeMode("errors"); await settle();
  headerButton(h.get("trace-rows").childNodes[0]).fire("click");
  const rows = h.get("trace-rows").childNodes;
  assert.equal(rows.length, 4);
  const [header, member, unavailable, end] = rows;
  assert.equal(hasClass(member, "group-member"), true);
  assert.equal(hasClass(member, "group-member-last"), false);
  assert.equal(hasClass(unavailable, "group-member"), true);
  assert.equal(hasClass(unavailable, "error-group-member"), true);
  assert.equal(hasClass(unavailable, "group-unavailable"), true);
  assert.equal(hasClass(unavailable, "group-member-last"), true);
  assert.equal(unavailable.getAttribute("aria-describedby"), headerId(header));
  assert.match(unavailable.textContent, /unavailable/i);
  assert.equal(groupLabels(unavailable).length, 0, "A missing row is not a captured request");
  assert.equal(hasClass(end, "error-group-end"), true);
  assert.match(end.textContent, /^End of group · 1 request\b/);
  assert.doesNotMatch(end.textContent, /2 requests/);
});

test("wholly unavailable error groups show an explicit boundary with zero displayed requests", async () => {
  const result = doc({requests: [], partial: true});
  const h = harness(async () => result); h.ui.changeMode("errors"); await settle();
  headerButton(h.get("trace-rows").childNodes[0]).fire("click");
  const [header, unavailable, end] = h.get("trace-rows").childNodes;
  assert.equal(h.get("trace-rows").childNodes.length, 3);
  assert.equal(hasClass(unavailable, "group-unavailable"), true);
  assert.equal(hasClass(unavailable, "group-member-last"), true);
  assert.equal(unavailable.getAttribute("aria-describedby"), headerId(header));
  assert.equal(hasClass(end, "error-group-end"), true);
  assert.match(end.textContent, /^End of group · 0 requests/);
});
