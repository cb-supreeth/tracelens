"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const requests = require("../tracelens_cli/web/request-model.js");
const requestSearch = require("../tracelens_cli/web/request-search.js");

const clone = value => JSON.parse(JSON.stringify(value));
const settle = async () => { for (let i = 0; i < 3; i++) await new Promise(resolve => setImmediate(resolve)); };
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise, resolve}; };
const row = (extra = {}) => ({traceId: "1".repeat(32), segmentId: "root-1", service: "cb-app", serviceInstanceId: "app-1",
  status: "COMPLETED", httpMethod: "GET", httpStatus: 200, path: "/api/features", entryChannel: "HTTP",
  startedAtMillis: Date.now() - 1000, durationMs: 40, user: "alice", siteId: "42", ...extra});
const child = (extra = {}) => row({segmentId: "child-1", service: "cb-ems", serviceInstanceId: "ems-1",
  parentSegmentId: "root-1", path: "/features", ...extra});
const other = (extra = {}) => row({traceId: "2".repeat(32), segmentId: "root-2", path: "/api/subscriptions", ...extra});
const job = () => row({traceId: "3".repeat(32), segmentId: "job-1", entryChannel: "SCHEDULER",
  httpMethod: "", httpStatus: null, path: "", lensId: "Nightly cleanup"});
const matching = (body, predicate = () => true, extra = {}) => {
  const matches = body.segments.filter(predicate).map(segment => ({...segment, fields: ["query"]}));
  return {schemaVersion: 1, kind: "request-data-search", matches, limited: false,
    counts: {requested: body.segments.length, scanned: body.segments.length, withData: body.segments.length,
      matched: matches.length, unavailable: 0, unsearched: 0}, ...extra};
};

function harness(search = async body => matching(body), {pins = []} = {}) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.type = ""; this.value = ""; this.className = ""; this.checked = false;
      this.childNodes = []; this.listeners = {}; this.attrs = {}; this.parentElement = null; this.open = false;
      const names = () => new Set(this.className.split(" ").filter(Boolean));
      this.classList = {
        contains: name => names().has(name),
        add: (...values) => { const found = names(); values.forEach(value => found.add(value)); this.className = [...found].join(" "); },
        remove: (...values) => { const found = names(); values.forEach(value => found.delete(value)); this.className = [...found].join(" "); },
        toggle: (name, force) => { const found = names(); if (force === undefined ? !found.has(name) : force) found.add(name); else found.delete(name); this.className = [...found].join(" "); }
      };
    }
    set innerHTML(_) { throw Error("Request keywords and matching identities must remain inert text"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...children) {
      for (const node of children) {
        if (node.parentElement) node.parentElement.childNodes = node.parentElement.childNodes.filter(child => child !== node);
        node.parentElement = this; this.childNodes.push(node);
      }
    }
    appendChild(child) { this.append(child); return child; }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentElement = null; }); this.childNodes = []; this.text = ""; this.append(...children); }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    getAttribute(key) { return this.attrs[key]; }
    addEventListener(name, listener) { (this.listeners[name] ||= []).push(listener); }
    fire(name, extra = {}) {
      const event = {target: this, preventDefault() { this.defaultPrevented = true; }, ...extra};
      this["on" + name]?.(event); (this.listeners[name] || []).forEach(listener => listener(event)); return event;
    }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    focus() {}
  }
  const nodes = new Map(), calls = [];
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  for (const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const node = new Element(match[1]);
    node.type = /\btype="([^"]+)"/.exec(match[2])?.[1] || "";
    node.className = /\bclass="([^"]+)"/.exec(match[2])?.[1] || "";
    nodes.set(match[3], node);
  }
  const errorRequest = requests.groupTraces([row({httpStatus: 500})])[0];
  const errors = {schemaVersion: 1, kind: "error-groups", scope: "retained-only", requests: [errorRequest],
    counts: {loadedSegments: 1, loadedRequests: 1, matchingRequests: 1, matchingSegments: 1, groups: 1},
    groups: [{id: "app-error", service: "cb-app", channel: "HTTP", method: "GET", route: "/api/features",
      category: "http5xx", httpStatus: 500, requestCount: 1, segmentCount: 1, requestKeys: [errorRequest.key]}]};
  const context = {window: {TraceLensRequests: requests, TraceLensRequestSearch: requestSearch,
    TraceLensViewerStore: require("../tracelens_cli/web/viewer-store.js"),
    TraceLensBrowserStore: {create: () => ({async listViews() { return []; }, async listPins() { return []; }})}},
    document: {getElementById: id => { assert.ok(nodes.has(id), "Unknown element: " + id); return nodes.get(id); }, createElement: tag => new Element(tag)},
    Option: function (label, value) { const node = new Element("option"); node.textContent = label; node.value = value; return node; },
    URLSearchParams, Date, setTimeout, clearTimeout, confirm: () => assert.fail("Search must never mutate retained evidence")};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-ui.js"), "utf8"), context);
  const ui = new context.window.TraceLensRequestUI({
    async api(path, method = "GET", query, body) {
      calls.push({path, method, query, body: body && clone(body)});
      if (path === "/api/viewer-context") return {namespace: "request-keyword-ui", mode: "federated"};
      if (path === "/api/snapshots") return {snapshots: pins, warnings: []};
      if (path.startsWith("/api/error-groups?")) return errors;
      if (path === "/api/request-search") { assert.equal(method, "POST"); return search(body); }
      assert.fail("Keyword UI must not fetch or export payload evidence: " + path);
    }, refreshLive: async () => {}, openRequest: async () => assert.fail("Search must not navigate"),
    openSegment: async () => assert.fail("Search must not export segments"), snapshot: async () => assert.fail("Search must not save"),
    openPin: async () => assert.fail("Search must not open snapshots"), copySnapshotLink: async () => {}});
  ui.filters.collapse = false; ui.writeFilters();
  const get = id => nodes.get(id);
  return {ui, get, calls, rows: () => get("trace-rows").childNodes,
    searches: () => calls.filter(call => call.path === "/api/request-search"),
    type(value) { get("request-keyword").value = value; get("request-keyword").fire("input"); },
    async search(value, enter = false) {
      if (value !== undefined) this.type(value);
      if (enter) get("request-keyword").fire("keydown", {key: "Enter"}); else get("request-keyword-search").fire("click");
      await settle();
    }};
}

test("keyword typing, initialization, polling and rendering never scan or export captured payloads", async () => {
  const h = harness(); await h.ui.init(); h.ui.setTraces({traces: [row(), child(), other()]});
  h.type("<script>literal keyword</script>"); h.ui.render();
  h.ui.setTraces({traces: [row(), child(), other()]}); await settle();
  assert.equal(h.searches().length, 0); assert.equal(h.rows().length, 2);
  assert.match(h.get("request-keyword-status").textContent, /Press Search or Enter/);
  assert.ok(h.calls.every(call => ["/api/viewer-context", "/api/snapshots"].includes(call.path)));
});

test("Search and Enter send one bounded metadata-prefiltered POST and downstream matches keep the root request", async () => {
  for (const enter of [false, true]) {
    const wait = deferred(), h = harness(body => wait.promise.then(() => matching(body, segment => segment.service === "cb-ems")));
    h.ui.setTraces({traces: [row(), child(), other(), job(), row({traceId: "4".repeat(32), segmentId: "old", startedAtMillis: Date.now() - 3600000})]});
    h.get("path-filter").value = "/api/features"; h.get("path-filter").fire("input");
    await h.search("needle", enter); h.get("request-keyword-search").fire("click");
    h.get("request-keyword").fire("keydown", {key: "Enter"}); await settle();
    assert.equal(h.searches().length, 1); assert.equal(h.get("request-keyword-search").disabled, true);
    const body = h.searches()[0].body;
    assert.equal(body.keyword, "needle"); assert.deepEqual(body.segments.map(segment => segment.segmentId).sort(), ["child-1", "root-1"]);
    assert.ok(body.segments.every(segment => Object.keys(segment).every(key => ["service", "serviceInstanceId", "segmentId", "traceId"].includes(key))));
    wait.resolve(); await settle();
    assert.equal(h.rows().length, 1); assert.equal(h.get("live-count").textContent, "1");
    assert.match(h.rows()[0].textContent, /\/api\/features/); assert.match(h.rows()[0].textContent, /cb-app, cb-ems/);
    assert.match(h.get("results-summary").textContent, /^1 matching request/);
    assert.match(h.get("request-keyword-status").textContent, /2 of 2 service segments/);
  }
});

test("clearing the keyword or resetting filters restores metadata rows without another scan", async () => {
  for (const clear of [h => h.type(""), h => h.get("clear-filters").fire("click"),
    h => h.get("active-filters").all().find(node => node.attrs?.["aria-label"] === "Clear request data keyword filter").fire("click")]) {
    const h = harness(body => matching(body, () => false)); h.ui.setTraces({traces: [row(), other()]});
    await h.search("not-found"); assert.equal(h.rows().length, 0);
    clear(h); await settle(); assert.equal(h.get("request-keyword").value, "");
    assert.equal(h.rows().length, 2); assert.equal(h.get("live-count").textContent, "2"); assert.equal(h.searches().length, 1);
  }
});

test("changing display grouping preserves the applied keyword without another scan", async () => {
  const h = harness(body => matching(body, segment => segment.service === "cb-ems"));
  h.ui.setTraces({traces: [row(), child(), other()]});
  await h.search("needle");
  h.get("collapse-apis").checked = true; h.get("collapse-apis").fire("change");
  assert.equal(h.ui.keywordSearch.state.status, "ready");
  assert.equal(h.rows().length, 1); assert.equal(h.get("live-count").textContent, "1");
  assert.equal(h.searches().length, 1);
});

test("zero matches disclose missing, expired and unsearched coverage without asserting absent traffic", async () => {
  const h = harness(body => matching(body, () => false, {limited: true,
    counts: {requested: 4, scanned: 2, withData: 1, matched: 0, unavailable: 1, unsearched: 1}}));
  h.ui.setTraces({traces: [row(), child(), other(), other({traceId: "4".repeat(32), segmentId: "root-4"})]});
  await h.search("missing"); assert.equal(h.rows().length, 0); assert.equal(h.get("live-count").textContent, "0");
  assert.match(h.get("request-keyword-status").textContent, /1 of 4 service segments/);
  assert.match(h.get("request-keyword-status").textContent, /1 without captured data/);
  assert.match(h.get("request-keyword-status").textContent, /1 unavailable or expired/);
  assert.match(h.get("request-keyword-status").textContent, /1 not searched/);
  assert.match(h.get("empty").textContent, /No keyword matches in the captured request data searched/);
  assert.doesNotMatch(h.get("empty").textContent, /No retained requests|No API requests occurred/);
});

test("no searchable evidence and search failure remain distinguishable from confirmed zero matches", async () => {
  const missing = harness(body => matching(body, () => false, {counts: {requested: 1, scanned: 0, withData: 0, matched: 0, unavailable: 1, unsearched: 0}}));
  missing.ui.setTraces({traces: [row()]}); await missing.search("missing");
  assert.match(missing.get("empty").textContent, /No searchable request data/);
  const failed = harness(async () => { throw Error("synthetic service failure"); });
  failed.ui.setTraces({traces: [row()]}); await failed.search("missing");
  assert.match(failed.get("results-summary").textContent, /search unavailable/);
  assert.match(failed.get("empty").textContent, /search could not be completed/);
  assert.doesNotMatch(failed.get("empty").textContent, /No keyword matches/);
});

test("changing tabs clears the live keyword and does not hide jobs, errors or saved captures", async () => {
  const saved = {id: "01234567-89ab-cdef-0123-456789abcdef", storage: "viewer", name: "Saved example", savedAt: Date.now(),
    request: requests.groupTraces([row()])[0]};
  for (const mode of ["jobs", "errors", "pins"]) {
    const h = harness(body => matching(body, () => false), {pins: [saved]});
    await h.ui.init(); h.ui.setTraces({traces: [row(), job()]}); await h.search("missing");
    h.ui.changeMode(mode, {refreshLive: false}); await settle();
    assert.equal(h.get("request-keyword").value, ""); assert.equal(h.ui.keywordSearch.state.status, "idle");
    assert.equal(h.get("request-keyword-control").classList.contains("hidden"), true);
    assert.equal(h.rows().length, 1, mode + " keeps its own result");
    h.ui.changeMode("live", {refreshLive: false}); await settle(); assert.equal(h.rows().length, 1);
    assert.equal(h.searches().length, 1);
  }
});

test("late search replies after resetting, editing the keyword, changing filters or leaving Live are ignored", async () => {
  for (const change of [h => h.get("clear-filters").fire("click"), h => h.type("changed"),
    h => { h.get("path-filter").value = "/api/subscriptions"; h.get("path-filter").fire("input"); },
    h => h.ui.changeMode("jobs", {refreshLive: false})]) {
    const wait = deferred(), h = harness(body => wait.promise.then(() => matching(body, () => false)));
    h.ui.setTraces({traces: [row(), other(), job()]}); await h.search("original"); change(h); await settle();
    const expected = h.rows().map(node => node.textContent); wait.resolve(); await settle();
    assert.equal(h.ui.keywordSearch.state.status, "idle"); assert.deepEqual(h.rows().map(node => node.textContent), expected);
    assert.doesNotMatch(h.get("results-summary").textContent, /Searching|search unavailable/); assert.equal(h.searches().length, 1);
  }
});

test("new retained live identities mark search results stale and require another explicit Search", async () => {
  const h = harness(); h.ui.setTraces({traces: [row()]}); await h.search("needle");
  h.ui.setTraces({traces: [row(), other()]}); h.ui.render(); await settle();
  assert.equal(h.searches().length, 1); assert.equal(h.rows().length, 1);
  assert.match(h.get("request-keyword-status").textContent, /List changed — search again/);
  await h.search(); assert.equal(h.searches().length, 2); assert.equal(h.rows().length, 2);
  assert.doesNotMatch(h.get("request-keyword-status").textContent, /List changed/);
});
