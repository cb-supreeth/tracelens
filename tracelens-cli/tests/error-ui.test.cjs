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
const jobRow = (extra = {}) => row({traceId: "22222222222222222222222222222222", segmentId: "job-root",
  entryChannel: "SCHEDULER", httpMethod: "", httpStatus: null, path: "", lensId: "Nightly cleanup", ...extra});
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

function harness(api = async () => doc(), options = {}) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.type = ""; this.value = ""; this.className = "";
      this.childNodes = []; this.listeners = {}; this.attrs = {}; this.checked = false;
      this.parentElement = null; this.open = false;
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
    append(...children) {
      for (const child of children) {
        if (child.parentElement) child.parentElement.childNodes = child.parentElement.childNodes.filter(node => node !== child);
        child.parentElement = this; this.childNodes.push(child);
      }
    }
    appendChild(child) { this.append(child); return child; }
    replaceChildren(...children) {
      for (const child of this.childNodes) child.parentElement = null;
      this.text = ""; this.childNodes = []; this.append(...children);
    }
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
  const calls = [], saves = [], viewerCalls = [], browserCalls = [], allCalls = []; let refreshes = 0;
  const store = { views: [], pins: [], snapshots: new Map(),
    async listViews() { return this.views; },
    async listPins() { browserCalls.push({operation: "list"}); return this.pins; },
    async getPin(id) { browserCalls.push({operation: "get", id}); return this.snapshots.get(id) || null; },
    async savePin(snapshot) { browserCalls.push({operation: "save", snapshot}); return snapshot; },
    async renamePin(id, name) {
      browserCalls.push({operation: "rename", id, name});
      this.pins = this.pins.map(pin => pin.id === id ? {...pin, name} : pin);
      if (this.snapshots.has(id)) this.snapshots.set(id, {...this.snapshots.get(id), name});
    },
    async deletePin(id) {
      browserCalls.push({operation: "delete", id});
      this.pins = this.pins.filter(pin => pin.id !== id); this.snapshots.delete(id);
    },
    async saveView(name, filters) { const view = {id: "saved", name, filters: clone(filters)}; saves.push(view); this.views = [view]; return view; } };
  const context = {window: { TraceLensRequests: options.requests || requests, TraceLensBrowserStore: {create: () => {
    if (options.browserError) throw options.browserError;
    return store;
  }},
    TraceLensViewerStore: require("../tracelens_cli/web/viewer-store.js") },
    document: {getElementById: id => { assert.ok(nodes.has(id), "Unknown element " + id); return nodes.get(id); }, createElement: tag => new Element(tag)},
    Option: function (label, value) { const node = new Element("option"); node.textContent = label; node.value = value; return node; },
    URLSearchParams, Date: options.Date || Date, confirm: options.confirm || (() => true),
    setTimeout: fn => { const id = ++nextTimer; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id),
  };
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-ui.js"), "utf8"), context);
  const ui = new context.window.TraceLensRequestUI({
    api: (...args) => {
      const [path, method = "GET", query = null, body] = args;
      const call = {path, method, query, body}; allCalls.push(call);
      if (path.startsWith("/api/snapshots")) {
        viewerCalls.push(call);
        if (options.viewerApi) return options.viewerApi(...args);
        if (path === "/api/snapshots" && method === "GET") return {snapshots: []};
        throw new Error("Unexpected viewer snapshot operation");
      }
      calls.push(path); return api(...args);
    }, refreshLive: async () => { refreshes++; },
    openRequest: async () => { throw new Error("No automatic request opening"); },
    openSegment: async () => { throw new Error("No automatic segment exports"); },
    snapshot: async () => { throw new Error("No automatic pinning"); },
    openPin: async () => { throw new Error("No automatic snapshot opening"); },
    copySnapshotLink: async () => { throw new Error("No automatic snapshot link copy"); },
  });
  const get = id => nodes.get(id);
  return { ui, get, calls, saves, store, nodes, viewerCalls, browserCalls, allCalls, refreshes: () => refreshes,
    async flushTimers() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); await settle(); },
    pendingTimers: () => timers.size };
}

test("Live distinguishes filtered requests from retained requests and service segments; showing all is explicit", () => {
  const h = harness();
  const old = Date.now() - 61 * 60000;
  const retained = {partial: true, limited: true, traces: [
    row({startedAtMillis: old}),
    row({segmentId: "child", service: "cb-ems", serviceInstanceId: "ems-1", parentSegmentId: "root", startedAtMillis: old + 1}),
    row({traceId: "22222222222222222222222222222222", segmentId: "job", entryChannel: "SCHEDULER", path: "", httpMethod: "", httpStatus: null}),
  ]};
  const before = clone(retained);
  h.ui.filters.collapse = false;
  h.ui.writeFilters(); h.ui.setTraces(retained);
  assert.match(h.get("results-summary").textContent, /^0 matching requests/);
  assert.match(h.get("retention-summary").textContent, /1 retained request.*2 captured service segments/);
  assert.equal(h.get("live-count").textContent, "0");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.match(h.get("results-summary").textContent, /Some services are unavailable.*Upstream results limited/);
  assert.match(h.get("empty").textContent, /Current filters hide all 1 retained request/);
  assert.match(h.get("empty").textContent, /Retained service segments in this tab \(all times\): 2 HTTP segments\./);
  assert.match(h.get("empty").textContent, /does not change recording or recover expired data/);
  assert.equal(h.ui.filters.channel, "HTTP"); assert.equal(h.ui.filters.sinceMinutes, "30");
  assert.equal(h.get("trace-rows").childNodes.length, 0);

  Object.assign(h.ui.filters, {query: "no match", path: "/missing", user: "nobody", site: "99", service: "missing",
    method: "DELETE", outcome: "error", minDuration: "100", maxDuration: "200"});
  h.ui.selectedView = "saved-filter"; h.ui.writeFilters(); h.ui.render();
  const showAll = h.get("empty").all().find(node => node.tagName === "BUTTON" && node.textContent === "Show all retained");
  assert.equal(showAll.textContent, "Show all retained"); assert.equal(showAll.type, "button");
  showAll.fire("click");
  assert.deepEqual(clone(h.ui.filters), {...requests.defaults(), channel: "", sinceMinutes: "", collapse: false});
  assert.equal(h.get("channel-filter").value, ""); assert.equal(h.get("time-filter").value, "");
  assert.equal(h.ui.selectedView, ""); assert.equal(h.get("delete-view").disabled, true);
  assert.match(h.get("results-summary").textContent, /^1 matching request/);
  assert.match(h.get("retention-summary").textContent, /1 retained request.*2 captured service segments/);
  assert.equal(h.get("live-count").textContent, "1");
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
  assert.equal(h.get("empty").classList.contains("hidden"), true);
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.deepEqual(h.allCalls, []); assert.equal(h.refreshes(), 0);
  assert.deepEqual(retained, before);
});

test("Live reports absent retained data separately and keeps invalid-filter and singular counts honest", () => {
  const h = harness();
  h.ui.setTraces({traces: [], partial: true, limited: true});
  assert.match(h.get("empty").textContent, /No retained requests are available in this tab/);
  assert.equal(h.get("empty").all().some(node => node.tagName === "BUTTON"), false);
  assert.match(h.get("results-summary").textContent, /^0 matching requests/);
  assert.match(h.get("retention-summary").textContent, /0 retained requests.*0 captured service segments/);
  assert.match(h.get("results-summary").textContent, /Some services are unavailable.*Upstream results limited/);

  h.ui.setTraces({traces: [row()]});
  assert.match(h.get("results-summary").textContent, /^1 matching request/);
  assert.match(h.get("retention-summary").textContent, /1 retained request.*1 captured service segment\b/);
  h.ui.filters.minDuration = "100"; h.ui.filters.maxDuration = "10"; h.ui.render();
  assert.match(h.get("empty").textContent, /valid duration range/);
  assert.doesNotMatch(h.get("empty").textContent, /No retained requests/);
  assert.equal(h.get("empty").all().find(node => node.tagName === "BUTTON").textContent, "Show all retained");
  h.ui.setTraces({traces: [row({entryChannel: "SQS"}), row({traceId: "22222222222222222222222222222222", entryChannel: "UNKNOWN"})]});
  assert.match(h.get("empty").textContent, /1 queue-message segment · 1 other\/unknown-channel segment\./);
  assert.deepEqual(h.allCalls, []);
});

test("empty Live and jobs lists suggest a wider time window only for otherwise-matching older captures", () => {
  const now = Date.now();
  class Clock extends Date { static now() { return now; } }
  for (const [mode, makeRow, noun] of [["live", row, "requests"], ["jobs", jobRow, "job runs"]]) {
    const h = harness(undefined, {Date: Clock});
    h.ui.changeMode(mode);
    Object.assign(h.ui.filters, {sinceMinutes: "30", user: "alice", minDuration: "20", maxDuration: "60"});
    h.ui.writeFilters();
    const filters = clone(h.ui.filters);
    const retained = {traces: [makeRow({startedAtMillis: now - 61 * 60000})]};
    const before = clone(retained);
    h.ui.setTraces(retained);
    assert.equal(h.get("empty").classList.contains("hidden"), false, mode);
    assert.ok(h.get("empty").textContent.includes(
      `Try increasing the time window, or select All retained to see older ${noun}.`), mode);
    assert.equal(h.get("trace-rows").childNodes.length, 0, mode);
    assert.deepEqual(clone(h.ui.filters), filters, "The suggestion must not change filters");
    assert.deepEqual(retained, before, "The suggestion must not change retained evidence");
    assert.deepEqual(h.allCalls, [], "The suggestion must not fetch or export evidence");
    assert.equal(h.refreshes(), 0);

    h.get("time-filter").value = ""; h.get("time-filter").fire("change");
    assert.equal(h.get("empty").classList.contains("hidden"), true, mode);
    assert.doesNotMatch(h.get("empty").textContent, /Try increasing the time window/, mode);
    assert.equal(h.get("trace-rows").childNodes.length, 1, mode);
    assert.equal(h.ui.filters.user, "alice", "Widening time keeps the other filters");
    assert.deepEqual(h.allCalls, []);
  }
});

test("empty-state time guidance is absent when a wider window cannot establish matching older evidence", () => {
  const now = Date.now(), old = now - 61 * 60000;
  class Clock extends Date { static now() { return now; } }
  for (const [mode, makeRow, otherRow] of [["live", row, jobRow], ["jobs", jobRow, row]]) {
    const cases = [
      {name: "no retained data", traces: []},
      {name: "only another tab has older data", traces: [otherRow({startedAtMillis: old})]},
      {name: "All retained", filters: {sinceMinutes: "", query: "no match"}},
      {name: "query mismatch", filters: {query: "no match"}},
      {name: "service mismatch", filters: {service: "missing-service"}},
      {name: "user mismatch", filters: {user: "nobody"}},
      {name: "site mismatch", filters: {site: "99"}},
      {name: "outcome mismatch", filters: {outcome: "error"}},
      {name: "duration mismatch", filters: {minDuration: "100"}},
      {name: "reversed duration", filters: {minDuration: "100", maxDuration: "10"}},
      {name: "negative duration", filters: {minDuration: "-1"}},
      {name: "invalid duration", filters: {maxDuration: "not-a-number"}},
      {name: "zero time window", filters: {sinceMinutes: "0"}},
      {name: "negative time window", filters: {sinceMinutes: "-1"}},
      {name: "invalid time window", filters: {sinceMinutes: "not-a-number"}},
      {name: "non-finite time window", filters: {sinceMinutes: "Infinity"}},
      {name: "unknown capture time", traces: [makeRow({startedAtMillis: null})]},
      {name: "only unknown-time evidence matches other filters", filters: {user: "alice"}, traces: [
        makeRow({startedAtMillis: null}),
        makeRow({traceId: "33333333333333333333333333333333", startedAtMillis: old, user: "bob"}),
      ]},
    ];
    if (mode === "live") cases.push({name: "path mismatch", filters: {path: "/missing"}},
      {name: "method mismatch", filters: {method: "DELETE"}}, {name: "channel mismatch", filters: {channel: "SQS"}});
    for (const scenario of cases) {
      const h = harness(undefined, {Date: Clock});
      h.ui.changeMode(mode);
      Object.assign(h.ui.filters, scenario.filters || {}); h.ui.writeFilters();
      h.ui.setTraces({traces: scenario.traces || [makeRow({startedAtMillis: old})]});
      const label = `${mode}: ${scenario.name}`;
      assert.equal(h.get("empty").classList.contains("hidden"), false, label);
      assert.doesNotMatch(h.get("empty").textContent, /Try increasing the time window/, label);
      assert.deepEqual(h.allCalls, [], label);
    }
  }
});

test("goHome switches to Live preserving filters without fetching and ignores an outstanding Errors response", async () => {
  let finish;
  const h = harness(() => new Promise(resolve => { finish = resolve; }));
  Object.assign(h.ui.filters, {path: "/chosen", channel: "SQS", sinceMinutes: "1440", collapse: false});
  h.ui.errorFilters.httpStatus = "503"; h.ui.writeFilters();
  const filters = clone(h.ui.filters);
  h.ui.changeMode("errors"); await settle();
  assert.equal(h.calls.length, 1);
  assert.equal(h.ui.goHome(), undefined); await settle();
  assert.equal(h.ui.mode, "live");
  assert.equal(h.get("live-tab").getAttribute("aria-pressed"), "true");
  assert.equal(h.get("errors-tab").getAttribute("aria-pressed"), "false");
  assert.equal(h.get("error-controls").classList.contains("hidden"), true);
  assert.deepEqual(clone(h.ui.filters), filters); assert.equal(h.ui.errorFilters.httpStatus, "503");
  assert.equal(h.refreshes(), 0); assert.equal(h.calls.length, 1);
  finish(doc()); await settle();
  assert.equal(h.ui.errorDoc, null); assert.equal(h.ui.errorLoading, false);
  h.ui.changeMode("pins"); h.ui.goHome(); h.ui.goHome(); await settle();
  assert.equal(h.ui.mode, "live"); assert.deepEqual(clone(h.ui.filters), filters);
  assert.equal(h.refreshes(), 0); assert.equal(h.allCalls.length, 1);
});

test("Live and Scheduled jobs partition by root channel without splitting linked service segments", async () => {
  const h = harness();
  const source = [row(),
    row({segmentId: "http-job-child", parentSegmentId: "root", entryChannel: "SCHEDULER", httpMethod: "", httpStatus: null, path: "", lensId: "HTTP follow-up"}),
    jobRow(),
    jobRow({segmentId: "job-http-child", parentSegmentId: "job-root", service: "cb-ems", serviceInstanceId: "ems-1",
      entryChannel: "HTTP", httpMethod: "POST", httpStatus: 200, path: "/cleanup", lensId: ""})];
  const before = clone(source);
  Object.assign(h.ui.filters, {channel: "", sinceMinutes: "", collapse: false});
  h.ui.writeFilters(); h.ui.setTraces({traces: source, partial: true, limited: true});
  const live = h.ui.requests.find(value => value.channel === "HTTP");
  const job = h.ui.requests.find(value => value.channel === "SCHEDULER");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.match(h.get("results-summary").textContent, /^1 matching request/);
  assert.match(h.get("retention-summary").textContent, /1 retained request.*2 captured service segments/);
  const opened = [], pinned = [];
  h.ui.options.openRequest = async value => opened.push(value);
  h.ui.pin = async value => pinned.push(value);
  h.get("trace-rows").all().find(node => node.className === "request-link").fire("click"); await settle();
  assert.equal(opened[0], live);
  assert.deepEqual(live.segments.map(value => value.segmentId).sort(), ["http-job-child", "root"]);

  h.get("jobs-tab").fire("click"); await settle();
  assert.equal(h.ui.mode, "jobs");
  assert.equal(h.get("jobs-tab").getAttribute("aria-pressed"), "true");
  assert.equal(h.get("live-tab").getAttribute("aria-pressed"), "false");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.match(h.get("results-summary").textContent, /^1 matching job run/);
  assert.match(h.get("retention-summary").textContent, /1 retained job run.*2 captured service segments/);
  assert.match(h.get("results-summary").textContent, /Some services are unavailable.*Upstream results limited/);
  const jobNode = h.get("trace-rows").childNodes[0];
  assert.match(jobNode.textContent, /Completed/);
  assert.doesNotMatch(jobNode.textContent, /HTTP unknown|Root HTTP|Successful HTTP/);
  jobNode.all().find(node => node.className === "request-link").fire("click");
  actionButton(jobNode, "Save snapshot").fire("click"); await settle();
  assert.equal(opened[1], job); assert.equal(pinned[0], job);
  assert.deepEqual(job.segments.map(value => value.segmentId).sort(), ["job-http-child", "job-root"]);
  assert.deepEqual(source, before);
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("Scheduled jobs filters are independent and Reset Show all and Home stay scoped to their tab", async () => {
  const h = harness();
  Object.assign(h.ui.filters, {path: "/http-only", method: "POST", query: "web", channel: "SQS", sinceMinutes: "5", collapse: false});
  h.ui.writeFilters(); const liveFilters = clone(h.ui.filters);
  h.ui.setTraces({traces: [row(), jobRow()]});
  h.ui.changeMode("jobs"); await settle();
  assert.deepEqual(clone(h.ui.filters), {...requests.defaults(), channel: "SCHEDULER"});
  for (const id of ["path-filter-control", "method-filter-control", "channel-filter-control"]) assert.equal(h.get(id).classList.contains("hidden"), true);
  assert.equal(h.get("outcome-filter-control").classList.contains("hidden"), false);
  assert.equal(h.get("collapse-apis-control").classList.contains("hidden"), false);
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  for (const [id, value] of [["service-filter", "cb-app"], ["user-filter", "alice"], ["site-filter", "42"],
    ["time-filter", "1440"], ["filter", "Nightly"], ["outcome-filter", "unknown"], ["min-duration", "1"], ["max-duration", "100"]]) {
    const control = h.get(id); control.value = value; control.fire(control.tagName === "SELECT" ? "change" : "input");
  }
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  const jobFilters = clone(h.ui.filters), reads = h.refreshes();
  h.ui.goHome(); await settle();
  assert.equal(h.ui.mode, "live"); assert.equal(h.refreshes(), reads);
  assert.deepEqual(clone(h.ui.filters), liveFilters);
  for (const id of ["path-filter-control", "method-filter-control", "channel-filter-control"]) assert.equal(h.get(id).classList.contains("hidden"), false);
  h.ui.changeMode("jobs"); await settle();
  assert.deepEqual(clone(h.ui.filters), jobFilters);
  h.get("clear-filters").fire("click");
  assert.deepEqual(clone(h.ui.filters), {...requests.defaults(), channel: "SCHEDULER"});
  h.get("filter").value = "missing"; h.get("filter").fire("input");
  assert.equal(h.get("trace-rows").childNodes.length, 0);
  actionButton(h.get("empty"), "Show all retained").fire("click");
  assert.deepEqual(clone(h.ui.filters), {...requests.defaults(), channel: "SCHEDULER", sinceMinutes: ""});
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.match(h.get("results-summary").textContent, /^1 matching job run/);
  assert.match(h.get("retention-summary").textContent, /1 retained job run/);
  h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("Live with only scheduled roots offers the jobs tab instead of a misleading Show all action", async () => {
  const h = harness(); h.ui.setTraces({traces: [jobRow()]});
  assert.match(h.get("results-summary").textContent, /^0 matching requests/);
  assert.match(h.get("retention-summary").textContent, /0 retained requests.*0 captured service segments/);
  assert.match(h.get("empty").textContent, /1 scheduled job run.*available in Scheduled jobs/);
  assert.equal(actionButton(h.get("empty"), "Show all retained"), undefined);
  actionButton(h.get("empty"), "View scheduled jobs").fire("click"); await settle();
  assert.equal(h.ui.mode, "jobs"); assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.deepEqual(h.allCalls, []);
});

test("Scheduled jobs groups repeated labels without merging runs or automatically exporting evidence", async () => {
  const h = harness();
  h.ui.setTraces({traces: [jobRow(), jobRow({traceId: "33333333333333333333333333333333", segmentId: "repeat"}),
    jobRow({traceId: "44444444444444444444444444444444", segmentId: "different", lensId: "Invoice sweep"})]});
  h.ui.changeMode("jobs"); await settle();
  assert.equal(h.get("trace-rows").childNodes.length, 2);
  const grouped = h.get("trace-rows").childNodes.find(node => headerButton(node));
  assert.match(grouped.textContent, /Nightly cleanup/);
  headerButton(grouped).fire("click");
  const members = h.get("trace-rows").childNodes.filter(node => hasClass(node, "group-member"));
  assert.equal(members.length, 2);
  const opened = []; h.ui.options.openRequest = async value => opened.push(value);
  for (const member of members) member.all().find(node => node.className === "request-link").fire("click");
  await settle();
  assert.equal(new Set(opened.map(value => value.traceId)).size, 2);
  assert.ok(opened.every(value => value.channel === "SCHEDULER"));
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("Jobs saved views and legacy scheduler views preserve independent Live filters; Errors can still select scheduler", async () => {
  const h = harness(); h.ui.store = h.store; h.ui.askName = async () => "My jobs";
  Object.assign(h.ui.filters, {path: "/chosen-http", method: "GET", channel: "HTTP"});
  h.ui.writeFilters(); const liveFilters = clone(h.ui.filters);
  h.ui.changeMode("jobs"); await settle();
  h.get("filter").value = "Nightly"; h.get("filter").fire("input");
  h.get("save-view").fire("click"); await settle();
  assert.equal(h.saves.length, 1);
  assert.equal(h.saves[0].filters.viewMode, "jobs"); assert.equal(h.saves[0].filters.channel, "SCHEDULER");
  h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
  h.get("saved-view").value = "saved"; h.get("saved-view").fire("change"); await settle();
  assert.equal(h.ui.mode, "jobs"); assert.equal(h.ui.filters.query, "Nightly");
  h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
  for (const viewMode of [undefined, "live"]) {
    h.ui.views.push({id: "legacy-job", filters: {viewMode, channel: "SCHEDULER", path: "/irrelevant-http", method: "POST", query: "Invoice", sinceMinutes: "60"}});
    h.get("saved-view").value = "legacy-job"; h.get("saved-view").fire("change"); await settle();
    assert.equal(h.ui.mode, "jobs"); assert.equal(h.ui.filters.query, "Invoice");
    assert.equal(h.ui.filters.channel, "SCHEDULER"); assert.equal(h.ui.filters.sinceMinutes, "60");
    assert.equal(h.ui.filters.path, ""); assert.equal(h.ui.filters.method, "");
    h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
    h.ui.views = h.ui.views.filter(view => view.id !== "legacy-job");
  }
  h.ui.views.push({id: "scheduler-errors", filters: {viewMode: "errors", channel: "SCHEDULER", path: "", method: "", errorCategory: "execution"}});
  h.get("saved-view").value = "scheduler-errors"; h.get("saved-view").fire("change"); await settle();
  assert.equal(h.ui.mode, "errors");
  const query = new URL(h.calls.at(-1), "http://fixture.invalid").searchParams;
  assert.equal(query.get("channel"), "SCHEDULER"); assert.equal(query.get("category"), "execution");
  assert.equal(h.get("channel-filter-control").classList.contains("hidden"), false);
  assert.ok(h.viewerCalls.every(call => call.method === "GET")); assert.deepEqual(browserWrites(h), []);
});

test("Jobs has explicit success and skipped outcomes, retained in saved and legacy job views", async () => {
  const h = harness(); h.ui.changeMode("jobs"); await settle();
  const options = h.get("outcome-filter").childNodes;
  assert.deepEqual(options.map(option => option.value), ["", "error", "success", "skipped", "active", "unknown"]);
  assert.equal(options.find(option => option.value === "unknown").textContent, "No confirmed outcome");
  assert.equal(options.some(option => /Successful HTTP/.test(option.textContent)), false);
  for (const viewMode of ["jobs", "live", undefined]) {
    h.ui.views = [{id: "job-success", filters: {viewMode, channel: "SCHEDULER", outcome: "success"}}];
    h.get("saved-view").value = "job-success"; h.get("saved-view").fire("change"); await settle();
    assert.equal(h.ui.mode, "jobs");
    assert.equal(h.ui.filters.outcome, "success");
    assert.equal(h.get("outcome-filter").value, "success");
  }
  h.ui.goHome();
  assert.equal(h.get("outcome-filter").childNodes.find(option => option.value === "success").textContent, "Successful HTTP");
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("Jobs shows explicit business outcome and audit status without treating skipped exceptions as failure", async () => {
  const h = harness(); h.ui.changeMode("jobs"); await settle();
  h.ui.filters.collapse = false;
  for (const [jobOutcome, expected, audit] of [["SUCCEEDED", "Succeeded", "SUCCEEDED"],
    ["SKIPPED", "Skipped", "IGNORED"], ["FAILED", "Failed", "RESCHEDULE_FAILED"], ["UNKNOWN", "Unknown", null]]) {
    h.ui.setTraces({traces: [jobRow({jobOutcome, jobAuditStatus: audit, jobType: "compute_billing_metrics",
      jobId: "42", scheduler: "chargebee", exceptionCount: 1})]});
    const rendered = h.get("trace-rows").childNodes[0];
    assert.equal(rendered.childNodes[3].childNodes[0].textContent, expected);
    if (audit) assert.match(rendered.childNodes[3].textContent, new RegExp(`Audit: ${audit}`));
    assert.doesNotMatch(rendered.childNodes[3].textContent, /HTTP null|HTTP unknown/);
    assert.match(rendered.textContent, /Job typecompute_billing_metrics.*Job ID42.*Schedulerchargebee/);
    assert.equal(h.ui.requests[0].hasErrors, jobOutcome === "FAILED");
  }
  h.ui.setTraces({traces: [jobRow({jobOutcome: "SKIPPED", jobId: "42"}),
    jobRow({traceId: "a".repeat(32), segmentId: "another", jobOutcome: "SUCCEEDED"})]});
  h.get("outcome-filter").value = "skipped"; h.get("outcome-filter").fire("change");
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.match(h.get("trace-rows").textContent, /Skipped/);
});

test("switching from Errors to Jobs discards stale errors without changing live/error filters", async () => {
  let finish;
  const h = harness(() => new Promise(resolve => { finish = resolve; }));
  Object.assign(h.ui.filters, {path: "/live", channel: "HTTP", sinceMinutes: "60"});
  h.ui.writeFilters(); const liveFilters = clone(h.ui.filters);
  h.ui.setTraces({traces: [jobRow()]}); h.ui.changeMode("errors"); await settle();
  assert.equal(h.calls.length, 1);
  h.ui.changeMode("jobs"); await settle();
  finish(doc()); await settle();
  assert.equal(h.ui.mode, "jobs"); assert.equal(h.ui.errorDoc, null); assert.equal(h.ui.errorLoading, false);
  assert.match(h.get("results-summary").textContent, /^1 matching job run/);
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
  assert.equal(h.calls.length, 1); assert.deepEqual(browserWrites(h), []);
});

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
  assert.ok(!h.get("active-filters").all().some(node => node.tagName === "BUTTON" && node.textContent.startsWith("Outcome:")),
    "Preserved Live outcome is not an active Errors filter");
  h.ui.changeMode("pins"); h.ui.changeMode("live"); await settle();
  assert.deepEqual(clone(h.ui.filters), before);
  assert.equal(h.get("outcome-filter-control").classList.contains("hidden"), false);
  assert.equal(h.refreshes(), 1);
});

test("Errors badge is visible before opening the tab and counts requests rather than failing segments", () => {
  const h = harness();
  h.ui.setTraces({traces: [row(),
    row({segmentId: "ems", service: "cb-ems", parentSegmentId: "root", httpStatus: 503}),
    row({segmentId: "go", service: "cb-grant-orchestrator", parentSegmentId: "root", httpStatus: 400}),
    row({traceId: "33333333333333333333333333333333", httpStatus: 500, startedAtMillis: Date.now() - 61 * 60000}),
    jobRow({jobOutcome: "FAILED"})]});
  assert.equal(h.get("errors-count").textContent, "1");
  assert.match(h.get("errors-tab").getAttribute("aria-label"), /1 unique matching retained request/);
  assert.match(h.get("errors-tab").title, /not service segments or error groups/);
  h.ui.filters.service = "cb-app"; h.ui.render();
  assert.equal(h.get("errors-count").textContent, "0", "Successful caller is not a failing service");
  h.ui.filters.service = ""; h.ui.filters.sinceMinutes = ""; h.ui.render();
  assert.equal(h.get("errors-count").textContent, "2");
  h.ui.changeMode("jobs");
  assert.equal(h.get("errors-count").textContent, "2", "Jobs filters must not replace the Errors filters");
  assert.deepEqual(h.allCalls, [], "Badge must not fetch execution data or add polling");
});

test("Errors badge follows the authoritative result, filter loading, failures, and fresh zero results", async () => {
  let response = doc();
  const pending = [];
  const h = harness(() => new Promise((resolve, reject) => pending.push({resolve, reject})));
  h.ui.changeMode("errors"); await settle();
  assert.equal(h.get("errors-count").textContent, "…");
  assert.equal(h.get("errors-tab").getAttribute("aria-busy"), "true");
  response.groups.push({...response.groups[0], id: "another-signature"});
  response.counts.groups = 2; response.counts.matchingSegments = 3;
  pending[0].resolve(response); await settle();
  assert.equal(h.get("errors-count").textContent, "1");
  assert.equal(h.get("errors-tab").getAttribute("aria-busy"), "false");
  h.get("error-http-status").value = "404"; h.get("error-http-status").fire("input");
  assert.equal(h.get("errors-count").textContent, "…", "Old count must not describe a new filter");
  await h.flushTimers(); pending[1].reject(new Error("offline")); await settle();
  assert.equal(h.get("errors-count").textContent, "—", "Unavailable is not zero");
  assert.match(h.get("errors-tab").title, /unavailable/);
  const refresh = h.ui.refreshErrors(); await settle();
  pending[2].resolve(doc({groups: [], requests: [], counts: {matchingRequests: 0, matchingSegments: 0, groups: 0}}));
  await refresh;
  assert.equal(h.get("errors-count").textContent, "0");
  assert.ok(h.calls.every(path => path.startsWith("/api/error-groups?")));
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
  assert.match(h.get("results-summary").textContent, /viewer files.*browser-only pins/);
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
  assert.equal(h.get("errors-count").textContent, "1");
  assert.match(h.get("errors-tab").title, /partial or limited/);
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
  for (const matchingRequests of [undefined, null, -1, 0.5, "1"]) {
    response = doc({counts: {matchingRequests}}); await h.ui.refreshErrors();
    assert.equal(h.get("errors-count").textContent, "—", "Malformed counts must not appear as zero");
  }
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
  rows[1].all().find(node => node.tagName === "BUTTON" && node.textContent === "Save snapshot").fire("click");
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

test("missing upstream root is described as unavailable without promoting EMS to a known root", () => {
  const h = harness();
  h.ui.setTraces({traces: [row({segmentId: "ems-only", service: "cb-ems", serviceInstanceId: "ems-1",
    parentSpanId: "1234567890abcdef", path: "/features", httpStatus: 201, durationMs: 10})]});
  const observed = h.ui.requests[0];
  assert.equal(observed.rootMissing, true); assert.equal(observed.rootAmbiguous, false);
  assert.equal(observed.outcome, "unknown"); assert.equal(observed.httpStatus, null); assert.equal(observed.durationMs, null);
  assert.deepEqual(observed.services, ["cb-ems"]);
  const title = h.get("trace-rows").childNodes[0].childNodes[0];
  const warning = title.childNodes.find(node => node.className === "missing");
  assert.equal(warning.textContent, "Upstream segment unavailable · representative segment shown");
  for (const explanation of ["filtered", "evicted", "not instrumented", "unreachable", "not an identified request root"]) {
    assert.ok(warning.title.includes(explanation));
  }
  assert.doesNotMatch(title.textContent, /Root missing or ambiguous|Multiple possible roots/);
});

test("multiple root candidates have their own warning and keep outcome and duration unknown", () => {
  const h = harness();
  h.ui.setTraces({traces: [row(), row({segmentId: "ems-candidate", service: "cb-ems", serviceInstanceId: "ems-1",
    parentSpanId: null, path: "/features", httpStatus: 201, durationMs: 10})]});
  const observed = h.ui.requests[0];
  assert.equal(observed.rootMissing, true); assert.equal(observed.rootAmbiguous, true);
  assert.equal(observed.outcome, "unknown"); assert.equal(observed.httpStatus, null); assert.equal(observed.durationMs, null);
  const title = h.get("trace-rows").childNodes[0].childNodes[0];
  const warning = title.childNodes.find(node => node.className === "missing");
  assert.equal(warning.textContent, "Multiple possible roots · representative segment shown");
  assert.match(warning.title, /unique request root has not been established/);
  assert.doesNotMatch(title.textContent, /Upstream segment unavailable|Root missing or ambiguous/);
});

test("an observed cb-app root with an EMS child has no representative warning and preserves root facts", () => {
  const h = harness();
  h.ui.setTraces({traces: [row({segmentId: "ems-child", service: "cb-ems", serviceInstanceId: "ems-1",
    parentSpanId: "1234567890abcdef", path: "/features", httpStatus: 201, durationMs: 10}), row()]});
  const observed = h.ui.requests[0];
  assert.equal(observed.rootMissing, false); assert.equal(observed.rootAmbiguous, false);
  assert.equal(observed.root.service, "cb-app"); assert.equal(observed.outcome, "success");
  assert.equal(observed.httpStatus, 200); assert.equal(observed.durationMs, 40);
  const rendered = h.get("trace-rows").childNodes[0];
  assert.equal(rendered.childNodes[0].childNodes.some(node => node.className === "missing"), false);
  assert.doesNotMatch(rendered.textContent, /representative segment shown|Root missing or ambiguous/);
  assert.equal(rendered.childNodes[3].textContent, "HTTP 200");
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
      assert.match(member.textContent, new RegExp(`Trace ID${TRACE}`));
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

const FILE_ID = "01234567-89ab-cdef-0123-456789abcdef";
const viewerContext = async path => {
  assert.equal(path, "/api/viewer-context", "Snapshot actions must not fetch live traces or source");
  return {namespace: "fixture", mode: "federated"};
};
function savedCapture(extra = {}) {
  const logical = request();
  return {schemaVersion: 1, storage: "viewer", id: FILE_ID, name: "Saved file", traceId: TRACE,
    capturedAt: 1234567, savedAt: 2000000, request: logical, partial: false, warnings: [],
    journey: {traceId: TRACE, segments: logical.segments, links: []},
    segments: logical.segments.map(segment => ({...segment, events: [{type: "LINE", line: 42, sourceLine: "original saved source"}]})),
    ...extra};
}
function pinSummary(snapshot) {
  const {segments, journey, ...summary} = clone(snapshot);
  return {...summary, segmentCount: segments.length};
}
function viewerBackend(initial = []) {
  const files = new Map(initial.map(snapshot => [snapshot.id, clone(snapshot)]));
  let sequence = 0;
  return {files, async api(path, method = "GET", query, body) {
    if (path === "/api/snapshots" && method === "GET") return {snapshots: [...files.values()].map(pinSummary)};
    if (path === "/api/snapshots" && method === "POST") {
      const id = "10000000-0000-0000-0000-" + String(++sequence).padStart(12, "0");
      const saved = {...clone(body.snapshot), id, schemaVersion: 1, storage: "viewer", savedAt: 3000000 + sequence};
      files.set(id, saved); return clone(saved);
    }
    const match = /^\/api\/snapshots\/([0-9a-f-]+)(\/rename)?$/.exec(path);
    assert.ok(match, "Unexpected viewer path: " + path);
    const id = match[1];
    if (method === "GET") return files.has(id) ? clone(files.get(id)) : null;
    if (method === "POST" && match[2]) {
      const renamed = {...files.get(id), name: body.name}; files.set(id, renamed); return clone(renamed);
    }
    if (method === "DELETE") { files.delete(id); return {id, deleted: true}; }
    throw new Error("Unexpected snapshot method: " + method);
  }};
}
const actionButton = (node, label) => node.all().find(child => child.tagName === "BUTTON" && child.textContent === label);
const browserWrites = h => h.browserCalls.filter(call => ["save", "rename", "delete"].includes(call.operation));

test("large capture save streams on the viewer and never builds or reposts a full browser snapshot", async () => {
  const saved = savedCapture({schemaVersion: 2, segments: [{segmentId: "root"}], name: "Large capture"});
  const h = harness(async path => ({...(await viewerContext(path)), capabilities: {largeTraceDetails: true}}), {
    viewerApi: async (path, method = "GET", query, body) => {
      if (method === "GET") return {snapshots: []};
      assert.equal(path, "/api/snapshots/capture"); assert.equal(method, "POST");
      assert.equal(body.traceId, TRACE); assert.equal(body.name, "Large capture");
      assert.equal(body.snapshot, undefined); return saved;
    }
  });
  await h.ui.init(); h.ui.setTraces({traces: [row()]});
  h.ui.askName = async () => "Large capture";
  const opened = []; h.ui.options.openPin = async pin => opened.push(pin);
  await h.ui.pin(h.ui.requests[0]);
  assert.deepEqual(opened, [saved]); assert.equal(h.viewerCalls.filter(call => call.method === "POST").length, 1);
  assert.deepEqual(browserWrites(h), []);
});

test("saved capture archive import is explicit, bounded, and never contacts a live service", async () => {
  const saved = savedCapture({schemaVersion: 2, name: "Imported archive"}), file = {size: 12345, name: "capture.zip"};
  const h = harness(async path => ({...(await viewerContext(path)), capabilities: {largeTraceDetails: true}}), {
    viewerApi: async (path, method = "GET", cap, body, contentType) => {
      if (method === "GET") return {snapshots: []};
      assert.equal(path, "/api/snapshots/import"); assert.equal(method, "POST");
      assert.equal(body, file); assert.equal(contentType, "application/zip"); return saved;
    }
  });
  await h.ui.init(); h.ui.changeMode("pins");
  assert.equal(h.get("snapshot-import-controls").classList.contains("hidden"), false);
  const opened = []; h.ui.options.openPin = async pin => opened.push(pin);
  h.get("snapshot-import").files = [file]; h.get("snapshot-import").fire("change"); await settle();
  assert.deepEqual(opened, [saved]); assert.deepEqual(h.calls, ["/api/viewer-context"]);
  assert.equal(h.viewerCalls.filter(call => call.method === "POST").length, 1);
  assert.equal(h.get("snapshot-import").disabled, false); assert.equal(h.get("snapshot-import").value, "");
  assert.deepEqual(browserWrites(h), []);
});

test("viewer files remain usable when browser storage cannot be created", async () => {
  const backend = viewerBackend([savedCapture()]);
  const h = harness(viewerContext, {browserError: new Error("IndexedDB unavailable"), viewerApi: backend.api});
  assert.deepEqual(h.allCalls, [], "Constructing the UI must not load or save snapshots");
  await h.ui.init();
  assert.equal(h.ui.store, null); assert.equal(h.ui.viewerReady, true);
  assert.equal(h.get("save-view").disabled, true);
  assert.equal(h.ui.pins.length, 1); assert.equal(h.ui.pins[0].storage, "viewer");
  h.ui.setTraces({traces: [row()]});
  assert.equal(actionButton(h.get("trace-rows"), "Save snapshot").disabled, false);
  h.ui.askName = async () => "Without browser storage";
  h.ui.options.snapshot = async () => savedCapture();
  const opened = []; h.ui.options.openPin = async snapshot => opened.push(snapshot);
  await h.ui.pin(h.ui.requests[0]);
  assert.equal(backend.files.size, 2); assert.equal(opened.length, 1);
  assert.deepEqual(h.browserCalls, []);
  assert.equal(h.viewerCalls.filter(call => call.method === "POST").length, 1);
});

test("explicit Scheduled jobs snapshot keeps the complete job trace and stays available in Saved captures", async () => {
  const backend = viewerBackend();
  const h = harness(viewerContext, {viewerApi: backend.api});
  await h.ui.init();
  h.ui.setTraces({traces: [jobRow(), jobRow({segmentId: "http-child", parentSegmentId: "job-root",
    service: "cb-ems", serviceInstanceId: "ems-1", entryChannel: "HTTP", httpMethod: "POST",
    httpStatus: 200, path: "/cleanup", lensId: ""})]});
  const logical = h.ui.requests[0], captures = [], opened = [];
  h.ui.askName = async () => "Saved nightly job";
  h.ui.options.snapshot = async request => {
    captures.push(request);
    return savedCapture({traceId: request.traceId, request, journey: {traceId: request.traceId, segments: request.segments, links: []},
      segments: request.segments.map(segment => ({...segment, events: [{type: "LINE", line: 42}]}))});
  };
  h.ui.options.openPin = async snapshot => opened.push(snapshot);
  h.ui.changeMode("jobs"); await settle();
  assert.equal(captures.length, 0); assert.equal(opened.length, 0); assert.equal(backend.files.size, 0);
  actionButton(h.get("trace-rows"), "Save snapshot").fire("click"); await settle();
  assert.deepEqual(captures, [logical]); assert.equal(logical.segments.length, 2);
  assert.equal(opened.length, 1); assert.equal(backend.files.size, 1);
  const saved = h.viewerCalls.find(call => call.method === "POST").body.snapshot;
  assert.equal(saved.request.channel, "SCHEDULER");
  assert.deepEqual(saved.segments.map(segment => segment.segmentId).sort(), ["http-child", "job-root"]);
  assert.deepEqual(saved.journey.segments.map(segment => segment.segmentId).sort(), ["http-child", "job-root"]);
  assert.equal(h.viewerCalls.filter(call => call.method !== "GET").length, 1);
  assert.deepEqual(browserWrites(h), []);
  h.ui.filters.query = "does not match"; h.ui.writeFilters(); h.ui.render();
  h.ui.changeMode("pins"); await settle();
  assert.match(h.get("trace-rows").textContent, /Saved nightly job/);
  assert.equal(captures.length, 1);
});

test("viewer file loading survives rejected browser reads and reports them without fallback", async () => {
  const backend = viewerBackend([savedCapture()]);
  const h = harness(viewerContext, {viewerApi: backend.api});
  h.store.listViews = h.store.listPins = async () => { throw new Error("Browser storage denied"); };
  await h.ui.init();
  assert.equal(h.ui.viewerReady, true); assert.equal(h.ui.pins[0].storage, "viewer");
  assert.match(h.get("list-message").textContent, /Browser storage denied/);
  assert.equal(h.viewerCalls.every(call => call.method === "GET"), true);
});

test("saved captures list viewer files and legacy browser pins without auto migration or live filtering", async () => {
  const file = savedCapture();
  const legacy = savedCapture({id: "trace:legacy", storage: "browser", name: "Legacy browser evidence", savedAt: 4000000});
  const backend = viewerBackend([file]);
  const h = harness(viewerContext, {viewerApi: backend.api});
  h.store.pins = [pinSummary(legacy)]; h.store.snapshots.set(legacy.id, legacy);
  await h.ui.init(); h.ui.filters.path = "/not-present"; h.ui.changeMode("pins"); await settle();
  assert.deepEqual(clone(h.ui.pins.map(pin => [pin.id, pin.storage])), [[legacy.id, "browser"], [FILE_ID, "viewer"]]);
  assert.match(h.get("results-summary").textContent, /1 viewer files.*1 browser-only pins/);
  const [browserRow, fileRow] = h.get("trace-rows").childNodes;
  assert.match(browserRow.textContent, /Browser only/); assert.match(fileRow.textContent, /Viewer file/);
  assert.ok(actionButton(browserRow, "Save to viewer")); assert.equal(actionButton(browserRow, "Copy snapshot link"), undefined);
  assert.ok(actionButton(fileRow, "Copy snapshot link")); assert.equal(actionButton(fileRow, "Save to viewer"), undefined);
  assert.deepEqual(browserWrites(h), []);
  assert.equal(h.browserCalls.some(call => call.operation === "get"), false);
  assert.equal(h.viewerCalls.every(call => call.path === "/api/snapshots" && call.method === "GET"), true);
});

test("failed viewer file saves never fall back to browser pins or open unsaved evidence", async () => {
  const legacy = savedCapture({id: "trace:legacy", storage: "browser"});
  const h = harness(viewerContext, {viewerApi: async (path, method = "GET") => {
    if (method === "GET") return {snapshots: []};
    throw new Error("Viewer disk is full");
  }});
  h.store.pins = [pinSummary(legacy)]; h.store.snapshots.set(legacy.id, legacy);
  await h.ui.init();
  h.ui.askName = async () => "New file";
  let captures = 0, opens = 0;
  h.ui.options.snapshot = async () => { captures++; return savedCapture(); };
  h.ui.options.openPin = async () => { opens++; };
  const logical = request();
  await assert.rejects(h.ui.pin(logical), /Viewer disk is full/);
  assert.equal(captures, 1); assert.equal(opens, 0); assert.equal(h.ui.busy.has(logical.key), false);
  assert.deepEqual(browserWrites(h), []); assert.equal(h.store.snapshots.get(legacy.id), legacy);
  assert.equal(h.viewerCalls.filter(call => call.method === "POST").length, 1);
});

test("unavailable viewer files disable saving and never capture live evidence or migrate browser pins", async () => {
  const h = harness(viewerContext, {viewerApi: async () => { throw new Error("Viewer storage unavailable"); }});
  h.store.pins = [pinSummary(savedCapture({id: "legacy", storage: "browser"}))];
  await h.ui.init(); h.ui.setTraces({traces: [row()]});
  assert.equal(h.ui.viewerReady, false);
  assert.equal(actionButton(h.get("trace-rows"), "Save snapshot").disabled, true);
  await assert.rejects(h.ui.pin(request()), /Viewer snapshot storage is unavailable/);
  await assert.rejects(h.ui.saveBrowserPin(h.store.pins[0]), /browser pin was not changed/);
  h.ui.changeMode("pins"); await settle();
  assert.equal(actionButton(h.get("trace-rows"), "Save to viewer").disabled, true);
  assert.deepEqual(browserWrites(h), []);
  assert.equal(h.viewerCalls.every(call => call.method === "GET"), true);
});

test("explicit legacy migration preserves capture time and complete saved evidence without live fetching", async () => {
  const legacy = savedCapture({id: "trace:legacy", storage: "browser", capturedAt: 123, name: "Old evidence"});
  const original = clone(legacy), backend = viewerBackend();
  const h = harness(viewerContext, {viewerApi: backend.api});
  h.store.pins = [pinSummary(legacy)]; h.store.snapshots.set(legacy.id, legacy);
  await h.ui.init(); h.ui.askName = async () => "Migrated evidence";
  const opened = []; h.ui.options.openPin = async snapshot => opened.push(snapshot);
  await h.ui.saveBrowserPin({...h.store.pins[0], storage: "browser"});
  const post = h.viewerCalls.find(call => call.method === "POST");
  assert.equal(post.path, "/api/snapshots");
  assert.equal(post.body.snapshot.capturedAt, 123);
  assert.deepEqual(clone(post.body.snapshot.segments), original.segments);
  assert.deepEqual(clone(post.body.snapshot.journey), original.journey);
  assert.equal(opened[0].capturedAt, 123); assert.notEqual(opened[0].id, legacy.id);
  assert.equal(h.browserCalls.filter(call => call.operation === "get").length, 1);
  assert.deepEqual(browserWrites(h), []); assert.deepEqual(legacy, original);
  assert.equal(h.store.snapshots.get(legacy.id), legacy);
  assert.deepEqual(h.calls, ["/api/viewer-context"]);
  assert.match(h.get("list-message").textContent, /original browser pin is unchanged/);
});

test("failed explicit migration keeps the legacy capture and never uses a fresh live snapshot", async () => {
  const legacy = savedCapture({id: "legacy", storage: "browser", capturedAt: 456});
  const h = harness(viewerContext, {viewerApi: async (path, method = "GET") => {
    if (method === "GET") return {snapshots: []};
    throw new Error("Viewer storage quota exceeded");
  }});
  h.store.pins = [pinSummary(legacy)]; h.store.snapshots.set(legacy.id, legacy);
  await h.ui.init(); h.ui.askName = async () => "Migration";
  await assert.rejects(h.ui.saveBrowserPin(h.store.pins[0]), /quota exceeded/);
  assert.equal(h.ui.busy.has(legacy.id), false);
  assert.equal(h.store.snapshots.get(legacy.id), legacy); assert.deepEqual(browserWrites(h), []);
  assert.equal(h.viewerCalls.filter(call => call.method === "POST").length, 1);
});

test("every explicit live save posts a new file even for an already-saved request", async () => {
  const existing = savedCapture(), backend = viewerBackend([existing]);
  const h = harness(viewerContext, {viewerApi: backend.api});
  await h.ui.init(); h.ui.askName = async () => "Another capture";
  let captures = 0; const opened = [];
  h.ui.options.snapshot = async () => { captures++; return savedCapture(); };
  h.ui.options.openPin = async snapshot => opened.push(snapshot);
  const logical = request();
  await h.ui.pin(logical); await h.ui.pin(logical);
  const posts = h.viewerCalls.filter(call => call.method === "POST");
  assert.equal(posts.length, 2); assert.equal(captures, 2);
  assert.equal(posts.every(call => call.path === "/api/snapshots"), true);
  assert.equal(backend.files.size, 3); assert.equal(new Set(opened.map(snapshot => snapshot.id)).size, 2);
  assert.deepEqual(backend.files.get(FILE_ID), existing);
  assert.deepEqual(browserWrites(h), []);
});

test("viewer and browser capture actions use their own read rename delete and copy backends", async () => {
  const file = savedCapture(), legacy = savedCapture({id: "trace:legacy", storage: "browser", name: "Legacy"});
  const backend = viewerBackend([file]), h = harness(viewerContext, {viewerApi: backend.api});
  h.store.pins = [pinSummary(legacy)]; h.store.snapshots.set(legacy.id, legacy);
  const opened = [], copied = [];
  h.ui.options.openPin = async snapshot => opened.push(snapshot);
  h.ui.options.copySnapshotLink = async id => copied.push(id);
  h.ui.askName = async () => "Renamed";
  await h.ui.init(); h.ui.changeMode("pins"); await settle();
  const rowFor = storage => h.get("trace-rows").childNodes.find(node => node.textContent.includes(storage === "viewer" ? "Viewer file" : "Browser only"));
  rowFor("viewer").all().find(node => node.className === "request-link").fire("click"); await settle();
  rowFor("browser").all().find(node => node.className === "request-link").fire("click"); await settle();
  actionButton(rowFor("viewer"), "Copy snapshot link").fire("click"); await settle();
  assert.deepEqual(opened.map(snapshot => snapshot.id), [FILE_ID, legacy.id]);
  assert.deepEqual(copied, [FILE_ID]);
  assert.equal(h.viewerCalls.filter(call => call.path === "/api/snapshots/" + FILE_ID && call.method === "GET").length, 1);
  assert.equal(h.browserCalls.filter(call => call.operation === "get" && call.id === legacy.id).length, 1);
  actionButton(rowFor("viewer"), "Rename").fire("click"); await settle();
  actionButton(rowFor("browser"), "Rename").fire("click"); await settle();
  assert.equal(backend.files.get(FILE_ID).name, "Renamed"); assert.equal(h.store.snapshots.get(legacy.id).name, "Renamed");
  const rename = h.viewerCalls.find(call => call.path.endsWith("/rename"));
  assert.equal(rename.path, "/api/snapshots/" + FILE_ID + "/rename");
  assert.equal(rename.method, "POST"); assert.deepEqual(clone(rename.body), {name: "Renamed"});
  actionButton(rowFor("viewer"), "Delete snapshot").fire("click"); await settle();
  actionButton(rowFor("browser"), "Unpin").fire("click"); await settle();
  assert.equal(backend.files.size, 0); assert.equal(h.store.snapshots.size, 0);
  assert.deepEqual(browserWrites(h), [{operation: "rename", id: legacy.id, name: "Renamed"}, {operation: "delete", id: legacy.id}]);
  assert.equal(h.viewerCalls.filter(call => call.method === "DELETE" && call.path === "/api/snapshots/" + FILE_ID).length, 1);
  assert.deepEqual(h.calls, ["/api/viewer-context"]);
});

test("missing saved files and legacy pins never substitute live traces", async () => {
  const file = savedCapture(), legacy = savedCapture({id: "legacy", storage: "browser", name: "Missing legacy"});
  const backend = viewerBackend([file]), h = harness(viewerContext, {viewerApi: backend.api});
  h.store.pins = [pinSummary(legacy)];
  await h.ui.init(); h.ui.mode = "pins"; h.ui.render(); backend.files.clear();
  const links = h.get("trace-rows").all().filter(node => node.className === "request-link");
  for (const link of links) {
    link.fire("click"); await settle();
    assert.match(h.get("list-message").textContent, /No live data was substituted/);
  }
  assert.deepEqual(h.calls, ["/api/viewer-context"]);
  assert.deepEqual(browserWrites(h), []);
});

const detailsFor = node => node.all().find(child => hasClass(child, "request-details"));
function inspectRow(node, open = true) {
  const details = detailsFor(node); assert.ok(details, "Every request has on-demand evidence details");
  details.open = open; details.fire("toggle"); return details;
}

test("request-first controls hide advanced filters by default and expose removable active filters", async () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  const primary = html.slice(html.indexOf('class="filters primary-filters"'), html.indexOf('id="advanced-filters"'));
  for (const id of ["path-filter", "time-filter", "service-filter", "outcome-filter"]) assert.ok(primary.includes(`id="${id}"`));
  for (const id of ["user-filter", "site-filter", "method-filter", "channel-filter", "min-duration", "max-duration", "filter"]) assert.ok(!primary.includes(`id="${id}"`));
  const h = harness(); h.ui.setTraces({traces: [row()]});
  assert.equal(h.get("more-filters-toggle").getAttribute("aria-expanded"), "false");
  assert.equal(h.get("advanced-filters").classList.contains("hidden"), true);
  assert.equal(h.get("saved-view-controls").tagName, "DETAILS");
  assert.equal(h.get("saved-view-controls").open, false);
  h.get("more-filters-toggle").fire("click");
  assert.equal(h.get("advanced-filters").classList.contains("hidden"), false);
  assert.equal(h.get("more-filters-toggle").getAttribute("aria-expanded"), "true");
  h.get("user-filter").value = "alice"; h.get("user-filter").fire("input");
  h.get("site-filter").value = "42"; h.get("site-filter").fire("input");
  h.get("more-filters-toggle").fire("click");
  assert.equal(h.get("advanced-filters").classList.contains("hidden"), true);
  const userChip = h.get("active-filters").all().find(node => node.tagName === "BUTTON" && node.textContent.includes("alice"));
  const siteChip = h.get("active-filters").all().find(node => node.tagName === "BUTTON" && node.textContent.includes("42"));
  assert.ok(userChip); assert.ok(siteChip);
  userChip.fire("click");
  assert.equal(h.ui.filters.user, ""); assert.equal(h.get("user-filter").value, "");
  assert.equal(h.ui.filters.site, "42");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);

  h.ui.changeMode("jobs"); await settle();
  assert.equal(h.get("query-filter-control").parentElement, h.get("primary-query-slot"));
  assert.equal(h.get("path-filter-control").classList.contains("hidden"), true);
  h.ui.goHome();
  assert.equal(h.get("query-filter-control").parentElement, h.get("advanced-query-slot"));
  assert.equal(h.ui.filters.site, "42");
});

test("compact rows preserve exact long API paths and evidence inside closed details without changing grouping", async () => {
  const id = "dGwtZGVtby1kYi1zcXM1MjAyNjEwMTktMTUxOTA5";
  const path = "/api/v2/features/" + id + "/subscription_metrics";
  const source = [row({path, lensId: "auto:GET " + path})], before = clone(source);
  const h = harness(); h.ui.setTraces({traces: source});
  const rendered = h.get("trace-rows").childNodes[0], details = detailsFor(rendered);
  const link = rendered.all().find(node => node.className === "request-link");
  assert.equal(details.open, false);
  assert.match(link.textContent, /subscription_metrics$/);
  assert.ok(link.textContent.includes(id.slice(0, 8) + "…" + id.slice(-6)));
  assert.ok(!link.textContent.includes(id));
  assert.equal(link.title, "GET " + path);
  assert.equal(link.getAttribute("aria-label"), "Open request flow: GET " + path);
  for (const value of [path, TRACE, "alice", "42"]) assert.ok(details.textContent.includes(value), "Exact evidence " + value);
  assert.ok(actionButton(details, "Save snapshot"));
  assert.ok(details.all().some(node => hasClass(node, "segment-details")));
  assert.equal(hasClass(rendered.childNodes[2], "hidden"), false, "User identity is visible without opening Details");
  assert.equal(hasClass(rendered.childNodes[6], "hidden"), true, "Snapshot actions are available on demand");
  const opened = []; h.ui.options.openRequest = async request => opened.push(request);
  link.fire("click"); await settle();
  assert.equal(opened[0].path, path); assert.deepEqual(source, before);
  h.get("path-filter").value = id; h.get("path-filter").fire("input");
  assert.equal(h.get("trace-rows").childNodes.length, 1, "Exact opaque ID remains searchable");
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

function assertVisibleIdentity(node, user, site, pinned = false) {
  assert.equal(node.childNodes.length, 7, "Request rows retain the same column positions in every tab");
  const identity = node.childNodes[2];
  assert.equal(identity.tagName, "TD");
  assert.equal(hasClass(identity, "hidden"), false, "User / site must be visible directly on the request row");
  assert.equal(identity.textContent, `${user || "unknown"}Site ${site || "unknown"}`);
  assert.equal(identity.all().some(child => ["IMG", "SCRIPT"].includes(child.tagName)), false);
  assert.equal(node.childNodes.filter(child => !hasClass(child, "hidden")).length, pinned ? 7 : 6);
  if (!pinned) {
    const details = detailsFor(node); assert.ok(details);
    assert.equal(details.open, false, "Showing identity must not open request Details");
    const fields = details.all().find(child => hasClass(child, "metadata-fields"));
    const values = fields.childNodes;
    const valueFor = label => values[values.findIndex(child => child.tagName === "DT" && child.textContent === label) + 1].textContent;
    assert.equal(valueFor("User"), user || "unknown");
    assert.equal(valueFor("Site"), site || "unknown");
  }
}

test("visible User / site preserves exact recorded identities, inert long names and explicit unknowns", () => {
  const user = 'qa_owner_' + "very_long_recorded_name_".repeat(8) + '<img src=x onerror="alert(1)">';
  const site = 'recorded_site_<script>alert(1)</script>';
  const source = [row({user, siteId: site}),
    row({traceId: "22222222222222222222222222222222", user: undefined, siteId: undefined}),
    row({traceId: "33333333333333333333333333333333", user: "", siteId: ""}),
    row({traceId: "44444444444444444444444444444444", user: null, siteId: null}),
    row({traceId: "55555555555555555555555555555555", user: "account_user_site_99", siteId: undefined})];
  const before = clone(source), h = harness(); h.ui.filters.collapse = false;
  h.ui.setTraces({traces: source});
  assert.equal(hasClass(h.get("identity-column-header"), "hidden"), false, "Initial Live header is visible");
  const rendered = h.get("trace-rows").childNodes;
  assert.equal(rendered.length, source.length);
  for (const observed of source) {
    const node = rendered.find(node => detailsFor(node).textContent.includes(observed.traceId));
    assertVisibleIdentity(node, observed.user, observed.siteId);
  }
  assert.deepEqual(clone(source), before, "Rendering identity must not rewrite recorded evidence");
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("User / site stays aligned and visible across grouped Live, jobs, Errors and Saved captures tab changes", async () => {
  const errorResult = doc(); errorResult.requests[0].user = "error_owner_with_underscores"; errorResult.requests[0].site = "error-site";
  const h = harness(async () => errorResult);
  const source = [row({user: "live_first_owner", siteId: "live-site"}),
    row({traceId: "33333333333333333333333333333333", user: "live_second_owner", siteId: "other-site"}),
    jobRow({user: "scheduler_service_account", siteId: "job-site"}),
    jobRow({traceId: "44444444444444444444444444444444", user: undefined, siteId: undefined})];
  h.ui.setTraces({traces: source});
  h.ui.pins = [{id: "saved-with-user", name: "Recorded identity", capturedAt: Date.now(), storage: "browser",
    request: {...h.ui.requests[0], user: "saved_owner_with_underscores", site: "saved-site", root: undefined, segments: undefined}},
    {id: "saved-without-summary", name: "Legacy evidence", capturedAt: Date.now(), storage: "browser"}];
  for (const mode of ["live", "jobs", "errors", "pins", "live", "jobs", "live"]) {
    h.ui.changeMode(mode); await settle();
    assert.equal(hasClass(h.get("identity-column-header"), "hidden"), false, `${mode} keeps the User / site header`);
    assert.equal(hasClass(h.get("actions-column-header"), "hidden"), mode !== "pins");
    for (const node of [...h.get("trace-rows").childNodes]) {
      const toggle = headerButton(node);
      if (toggle && toggle.getAttribute("aria-expanded") !== "true") toggle.fire("click");
    }
    const rendered = h.get("trace-rows").childNodes;
    const members = rendered.filter(node => hasClass(node, "request-row"));
    assert.equal(members.length, mode === "errors" ? 1 : 2, `${mode} displays the expected requests`);
    const expected = mode === "pins" ? h.ui.pins.map(pin => pin.request || {})
      : mode === "errors" ? errorResult.requests : h.ui.requests.filter(request => (request.channel === "SCHEDULER") === (mode === "jobs"));
    for (const [index, member] of members.entries()) {
      const request = expected[index];
      assertVisibleIdentity(member, request.user, request.site, mode === "pins");
      assert.equal(hasClass(member, "group-member"), mode !== "pins");
    }
    for (const boundary of rendered.filter(node => !hasClass(node, "request-row"))) {
      assert.equal(boundary.childNodes[0].colSpan, 7, "Group boundaries span all request columns");
    }
  }
  assert.equal(h.viewerCalls.length, 0); assert.deepEqual(browserWrites(h), []);
  assert.ok(h.calls.every(path => path.startsWith("/api/error-groups?")));
});

test("API compaction preserves meaningful long endpoint names, exact group identity, and inert labels", () => {
  const id = "12345678-1234-1234-1234-123456789012";
  const suffix = "feature_entitlement_reference_counts_after_migration";
  const path = "/api/v2/features/" + id + "/" + suffix;
  const h = harness();
  h.ui.setTraces({traces: [row({path}), row({path, traceId: "33333333333333333333333333333333"})]});
  const header = headerButton(h.get("trace-rows").childNodes[0]);
  assert.ok(header.textContent.includes(suffix), "Readability must preserve meaningful endpoint suffixes");
  assert.ok(header.textContent.includes("12345678…789012"));
  assert.ok(!header.textContent.includes(id));
  header.fire("click");
  const member = h.get("trace-rows").childNodes.find(node => hasClass(node, "group-member"));
  const link = member.all().find(node => node.className === "request-link");
  assert.ok(link.textContent.endsWith(suffix));
  assert.equal(link.title, "GET " + path);
  assert.equal(h.ui.requests[0].root.path, path);
  const title = member.childNodes[0];
  assert.ok(!title.childNodes.some(node => node.tagName === "SMALL" && node.textContent === "GET " + path),
    "A generated label must not repeat the full path outside closed details");

  const unsafe = '<img src=x onerror="alert(1)">';
  h.get("clear-filters").fire("click");
  h.ui.setTraces({traces: [row({path: "/api/" + unsafe})]});
  h.get("apply-updates").fire("click");
  assert.ok(h.get("trace-rows").textContent.includes(unsafe));
  assert.equal(h.get("trace-rows").all().some(node => node.tagName === "IMG"), false);
});

test("tab badges count matches while the collapsed capture summary separately shows retained totals", async () => {
  const h = harness();
  h.ui.setTraces({traces: [row(), row({traceId: "33333333333333333333333333333333", path: "/different"}), jobRow()]});
  h.get("path-filter").value = "/api/features"; h.get("path-filter").fire("input");
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "1");
  assert.equal(h.get("results-summary").textContent, "1 matching request");
  assert.doesNotMatch(h.get("results-summary").textContent, /scheduled|retained|segments/);
  assert.match(h.get("retention-summary").textContent, /2 retained requests/);
  assert.equal(h.get("retention-label").textContent, "2 retained · Capture info");
  assert.match(h.get("live-tab").title, /1 matching request.*2 retained requests.*all times/);
  assert.match(h.get("live-tab").getAttribute("aria-label"), /Live requests.*1 matching request.*2 retained requests/);
  assert.equal(h.get("retention-details").open, false);
  h.ui.changeMode("jobs"); await settle();
  assert.equal(h.get("results-summary").textContent, "1 matching job run");
  assert.match(h.get("retention-summary").textContent, /1 retained job run/);
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
  assert.match(h.get("jobs-tab").title, /1 matching job run.*1 retained job run.*all times/);
  assert.match(h.get("jobs-tab").getAttribute("aria-label"), /Scheduled jobs.*1 matching job run.*1 retained job run/);
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "1");
});

test("each tab badge keeps its own filters through Live, jobs, Errors and Saved captures switches", async () => {
  const h = harness();
  h.ui.setTraces({traces: [row(), row({traceId: "33333333333333333333333333333333", path: "/other"}),
    jobRow(), jobRow({traceId: "44444444444444444444444444444444", lensId: "Invoice sweep"})]});
  h.get("path-filter").value = "/api/features"; h.get("path-filter").fire("input");
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "2");
  const liveFilters = clone(h.ui.filters);
  h.ui.changeMode("jobs"); await settle();
  h.get("filter").value = "Nightly"; h.get("filter").fire("input");
  const jobFilters = clone(h.ui.filters);
  for (const mode of ["live", "jobs", "errors", "pins", "jobs", "live"]) {
    h.ui.changeMode(mode); await settle();
    assert.equal(h.get("live-count").textContent, "1", `${mode} retains the Live path filter`);
    assert.equal(h.get("jobs-count").textContent, "1", `${mode} retains the jobs label filter`);
    if (mode === "live" || mode === "jobs") {
      assert.deepEqual(clone(h.ui.filters), mode === "jobs" ? jobFilters : liveFilters);
      assert.equal(h.get("trace-rows").childNodes.length, 1);
      assert.equal(h.get("results-summary").textContent, mode === "jobs" ? "1 matching job run" : "1 matching request");
      assert.equal(h.get("retention-label").textContent, "2 retained · Capture info");
    }
  }
  h.ui.changeMode("jobs"); await settle();
  h.get("filter").value = "No matching job"; h.get("filter").fire("input");
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "0");
  assert.equal(h.get("results-summary").textContent, "0 matching job runs");
  actionButton(h.get("empty"), "Show all retained").fire("click");
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "2");
  h.ui.goHome(); assert.deepEqual(clone(h.ui.filters), liveFilters);
});

test("inactive tab counts normalize unsupported filters exactly as entering that tab does", async () => {
  const h = harness(); h.ui.setTraces({traces: [row(), jobRow({jobOutcome: "SUCCEEDED"})]});
  Object.assign(h.ui.jobFilters, {channel: "HTTP", path: "/not-a-job", method: "DELETE", outcome: "success"});
  const savedJobFilters = clone(h.ui.jobFilters);
  h.ui.render();
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.deepEqual(clone(h.ui.jobFilters), savedJobFilters, "Counting must not rewrite inactive filters");
  h.ui.changeMode("jobs"); await settle();
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.equal(h.get("results-summary").textContent, "1 matching job run");
  h.ui.changeMode("errors"); await settle();
  h.get("channel-filter").value = "SCHEDULER"; h.get("channel-filter").fire("change");
  assert.equal(h.get("live-count").textContent, "1", "Live previews its HTTP fallback for a scheduler-only Errors filter");
  assert.equal(h.ui.filters.channel, "SCHEDULER", "Badge calculation must not change the Errors filter");
  h.ui.goHome();
  assert.equal(h.ui.filters.channel, "HTTP");
  assert.equal(h.get("live-count").textContent, "1");
  assert.equal(h.get("results-summary").textContent, "1 matching request");
});

test("invalid duration filters zero only the affected tab badge, including inactive tabs", async () => {
  for (const invalid of [{minDuration: "100", maxDuration: "10"}, {minDuration: "-1"}, {maxDuration: "not-a-number"}]) {
    const h = harness(); h.ui.setTraces({traces: [row(), jobRow()]});
    Object.assign(h.ui.filters, invalid); h.ui.writeFilters(); h.ui.render();
    assert.equal(h.get("live-count").textContent, "0"); assert.equal(h.get("jobs-count").textContent, "1");
    assert.equal(h.get("results-summary").textContent, "0 matching requests");
    assert.equal(h.get("trace-rows").childNodes.length, 0);
    assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
    h.ui.changeMode("jobs"); await settle();
    assert.equal(h.get("live-count").textContent, "0"); assert.equal(h.get("jobs-count").textContent, "1");
    Object.assign(h.ui.filters, invalid); h.ui.writeFilters(); h.ui.render();
    assert.equal(h.get("jobs-count").textContent, "0");
    assert.equal(h.get("results-summary").textContent, "0 matching job runs");
    assert.equal(h.get("trace-rows").childNodes.length, 0);
    h.ui.goHome(); h.get("clear-filters").fire("click");
    assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "0");
  }
});

test("badges count linked requests and job runs, never service segments or collapsed API groups", async () => {
  const h = harness();
  const source = [row(), row({segmentId: "child", parentSegmentId: "root", service: "cb-ems", serviceInstanceId: "ems-1"}),
    row({traceId: "33333333333333333333333333333333"}),
    row({traceId: "33333333333333333333333333333333", segmentId: "child", parentSegmentId: "root", service: "cb-ems", serviceInstanceId: "ems-1"}),
    jobRow(), jobRow({segmentId: "job-child", parentSegmentId: "job-root", entryChannel: "HTTP", path: "/cleanup", httpMethod: "POST"}),
    jobRow({traceId: "44444444444444444444444444444444"}),
    jobRow({traceId: "44444444444444444444444444444444", segmentId: "job-child", parentSegmentId: "job-root", entryChannel: "HTTP", path: "/cleanup", httpMethod: "POST"})];
  h.ui.setTraces({traces: source});
  for (const mode of ["live", "jobs"]) {
    h.ui.changeMode(mode); await settle();
    assert.equal(h.get("live-count").textContent, "2"); assert.equal(h.get("jobs-count").textContent, "2");
    assert.equal(h.get("trace-rows").childNodes.length, 1, "Two matching executions collapse to one header");
    assert.match(h.get("retention-summary").textContent, /2 retained.*4 captured service segments/);
    headerButton(h.get("trace-rows").childNodes[0]).fire("click");
    assert.equal(h.get("trace-rows").childNodes.length, 4, "An expanded group adds member rows and a footer, not matches");
    assert.equal(h.get("live-count").textContent, "2"); assert.equal(h.get("jobs-count").textContent, "2");
    h.get("collapse-apis").checked = false; h.get("collapse-apis").fire("change");
    assert.equal(h.get("trace-rows").childNodes.length, 2);
    assert.equal(h.get("live-count").textContent, "2"); assert.equal(h.get("jobs-count").textContent, "2");
  }
});

test("badges and active rows share one clock at the relative-time boundary", () => {
  const now = Date.now(), filterClocks = []; let reads = 0;
  class Clock extends Date { static now() { return now + reads++; } }
  const h = harness(undefined, {Date: Clock, requests: {...requests,
    filterRequests(retained, filters, time) { filterClocks.push(time); return requests.filterRequests(retained, filters, time); },
  }});
  const retained = {traces: [row({startedAtMillis: now - 30 * 60000}), jobRow({startedAtMillis: now - 30 * 60000})]};
  h.ui.setTraces(retained);
  assert.deepEqual(filterClocks, [now, now], "Each tab is filtered once with the same clock; active rows reuse those matches");
  assert.equal(h.get("live-count").textContent, "1"); assert.equal(h.get("jobs-count").textContent, "1");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.equal(h.get("results-summary").textContent, "1 matching request");
  h.ui.setTraces(clone(retained));
  assert.equal(filterClocks.length, 5, "Empty-state guidance adds one diagnostic match without a time filter");
  const secondRenderClock = filterClocks[2];
  assert.ok(secondRenderClock > now, "The second render must cross the relative-time boundary");
  assert.deepEqual(filterClocks.slice(2), [secondRenderClock, secondRenderClock, secondRenderClock],
    "Both tab counts and the older-evidence diagnostic share the render's captured clock");
  assert.equal(h.get("live-count").textContent, "0"); assert.equal(h.get("jobs-count").textContent, "0");
  assert.equal(h.get("trace-rows").childNodes.length, 0);
  assert.equal(h.get("results-summary").textContent, "0 matching requests");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
});

test("polling keeps an inspected request stable and applies only the latest buffered data explicitly", () => {
  const h = harness(), original = row(); h.ui.setTraces({traces: [original]});
  const rendered = h.get("trace-rows").childNodes[0];
  const details = inspectRow(rendered);
  const second = row({traceId: "33333333333333333333333333333333", path: "/second", startedAtMillis: Date.now()});
  h.ui.setTraces({traces: [original, second]});
  assert.equal(h.get("trace-rows").childNodes[0], rendered, "Polling must not replace the inspected DOM row");
  assert.equal(h.ui.requests.length, 1); assert.equal(details.open, true);
  assert.equal(h.get("live-count").textContent, "1", "A held poll must not advance the badge before its rows");
  assert.equal(h.get("results-summary").textContent, "1 matching request");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
  assert.equal(h.get("pending-updates").classList.contains("hidden"), false);
  const newest = row({traceId: "44444444444444444444444444444444", path: "/newest", startedAtMillis: Date.now()});
  h.ui.setTraces({traces: [newest]});
  assert.equal(h.get("trace-rows").childNodes[0], rendered);
  details.open = false; details.fire("toggle");
  assert.equal(h.ui.requests.length, 1);
  assert.equal(h.ui.requests[0].traceId, TRACE, "Closing details must not silently change the list");
  h.get("apply-updates").fire("click");
  assert.equal(h.ui.requests.length, 1); assert.equal(h.ui.requests[0].path, "/newest");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.equal(detailsFor(h.get("trace-rows").childNodes[0]).open, false);
  assert.equal(h.get("pending-updates").classList.contains("hidden"), true);
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("expanded API groups keep polling stable but filter and tab changes use current data", async () => {
  const h = harness(); h.ui.setTraces({traces: repeatedRows()});
  headerButton(h.get("trace-rows").childNodes[0]).fire("click");
  const rendered = h.get("trace-rows").childNodes[0];
  h.ui.setTraces({traces: [row({path: "/changed"}), jobRow()]});
  assert.equal(h.get("trace-rows").childNodes[0], rendered);
  h.get("path-filter").value = "/changed"; h.get("path-filter").fire("input");
  assert.equal(h.get("trace-rows").childNodes.length, 1);
  assert.match(h.get("trace-rows").textContent, /changed/);
  assert.equal(h.get("pending-updates").classList.contains("hidden"), true);
  inspectRow(h.get("trace-rows").childNodes[0]);
  h.ui.setTraces({traces: [jobRow({lensId: "Current nightly job"})]});
  h.ui.changeMode("jobs"); await settle();
  assert.match(h.get("trace-rows").textContent, /Current nightly job/);
  assert.equal(h.get("pending-updates").classList.contains("hidden"), true);
  assert.equal(h.get("live-count").textContent, "0");
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("scheduled-job row inspection also buffers polls and preserves independent filters on apply", async () => {
  const h = harness(); h.ui.setTraces({traces: [jobRow()]});
  h.ui.changeMode("jobs"); await settle();
  h.get("filter").value = "Nightly"; h.get("filter").fire("input");
  const rendered = h.get("trace-rows").childNodes[0]; inspectRow(rendered);
  h.ui.setTraces({traces: [jobRow(), jobRow({traceId: "44444444444444444444444444444444", segmentId: "next-job"})]});
  assert.equal(h.get("trace-rows").childNodes[0], rendered);
  assert.equal(h.get("jobs-count").textContent, "1");
  assert.equal(h.get("results-summary").textContent, "1 matching job run");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
  assert.match(h.get("apply-updates").textContent, /job/i);
  h.get("apply-updates").fire("click");
  assert.equal(h.ui.filters.query, "Nightly"); assert.equal(h.ui.filters.channel, "SCHEDULER");
  assert.equal(h.ui.requests.length, 2);
  assert.equal(h.get("jobs-count").textContent, "2");
  assert.equal(h.get("results-summary").textContent, "2 matching job runs");
  assert.equal(h.get("retention-label").textContent, "2 retained · Capture info");
  assert.ok(h.get("trace-rows").childNodes.every(node => !hasClass(node, "group-expanded")));
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("Chargebee missing-entry warning is scoped to known cb-app HTTP traffic and never invents its segment", async () => {
  const source = row({service: "cb-ems", serviceInstanceId: "ems-1", segmentId: "ems-only",
    parentSpanId: "1234567890abcdef", path: "/features"});
  const h = harness(); h.ui.setServices(["cb-app", "cb-ems"]); h.ui.setTraces({traces: [source]});
  assert.match(h.get("trace-rows").textContent, /cb-app capture missing/);
  assert.deepEqual(h.ui.requests[0].services, ["cb-ems"]);
  assert.equal(h.ui.requests[0].segments.length, 1);
  assert.equal(h.ui.requests[0].rootMissing, true);
  assert.equal(h.ui.requests[0].httpStatus, null);
  assert.equal(h.ui.requests[0].durationMs, null);
  const standalone = harness(); standalone.ui.setServices(["cb-ems"]); standalone.ui.setTraces({traces: [source]});
  assert.doesNotMatch(standalone.get("trace-rows").textContent, /cb-app capture missing/);
  assert.match(standalone.get("trace-rows").textContent, /Upstream segment unavailable/);
  h.ui.setTraces({traces: [jobRow({service: "cb-ems", serviceInstanceId: "ems-1"})]});
  h.ui.changeMode("jobs"); await settle();
  assert.doesNotMatch(h.get("trace-rows").textContent, /cb-app capture missing/);
  assert.deepEqual(h.allCalls, []); assert.deepEqual(browserWrites(h), []);
});

test("unchanged polls respect inspection but uninspected requests still age out of the time window", () => {
  let now = Date.now();
  class Clock extends Date { static now() { return now; } }
  const h = harness(undefined, {Date: Clock});
  const retained = {traces: [row({startedAtMillis: now - 29 * 60000})]};
  h.ui.setTraces(retained);
  const rendered = h.get("trace-rows").childNodes[0]; inspectRow(rendered);
  now += 2 * 60000;
  h.ui.setTraces(clone(retained));
  assert.equal(h.get("trace-rows").childNodes[0], rendered);
  assert.equal(h.get("live-count").textContent, "1", "Inspected rows and their badge stay on the same accepted view");
  assert.equal(h.get("pending-updates").classList.contains("hidden"), true);
  inspectRow(rendered, false);
  h.ui.setTraces(clone(retained));
  assert.equal(h.get("trace-rows").childNodes.length, 0);
  assert.equal(h.get("results-summary").textContent, "0 matching requests");
  assert.equal(h.get("live-count").textContent, "0");
  assert.equal(h.get("retention-label").textContent, "1 retained · Capture info");
});
