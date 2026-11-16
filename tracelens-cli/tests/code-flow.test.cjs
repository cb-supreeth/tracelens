"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const reader = require("../tracelens_cli/web/code-flow.js");
const graph = require("../tracelens_cli/web/execution-graph.js");

const invocation = (id, parentId = null, extra = {}) => ({id, kind: "invocation", laneId: "one", parentId,
  relationship: parentId ? "observed-nesting" : null, className: "example.FeatureService", methodName: id,
  entry: {index: 1, seq: 2}, exit: {index: 4, seq: 5}, complete: true, outcome: "returned", durationMs: 1.2,
  observedLines: {first: 40, last: 32, count: 2}, ...extra});
const marker = (id, parentId, eventType, extra = {}) => ({id, kind: "marker", laneId: "one", parentId,
  relationship: parentId ? "observed-nesting" : null, eventType, evidence: {index: 2, seq: 3}, ...extra});
const model = (nodes, extra = {}) => ({nodes, lanes: [{id: "one", threadName: "http-exec"}, {id: "two", threadName: "worker-1"}],
  counts: {}, warnings: [], limitations: [], ...extra});

function fixture() {
  const created = [];
  class Element {
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = []; this.attrs = {}; this.listeners = {};
      this.dataset = {}; this.style = {setProperty(name, value) { this[name] = value; }};
      this.className = ""; this.value = ""; this.open = false; this.hidden = false;
      this.classList = {contains: name => this.className.split(" ").includes(name)}; created.push(this);
    }
    set innerHTML(_) { throw new Error("No HTML interpolation"); }
    set textContent(value) { this.text = String(value); this.replaceChildren(); }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(" "); }
    append(...children) { children.forEach(child => { child.remove(); child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; if (children.length) this.text = ""; this.append(...children); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    getAttribute(key) { return this.attrs[key]; }
    addEventListener(key, fn) { (this.listeners[key] ||= []).push(fn); }
    removeEventListener(key, fn) { this.listeners[key] = (this.listeners[key] || []).filter(value => value !== fn); }
    fire(key, event = {}) { (this.listeners[key] || []).slice().forEach(fn => fn({target: this, ...event})); }
    focus() { this.ownerDocument.activeElement = this; }
    scrollIntoView(options) { this.scrolledIntoView = true; this.scrollOptions = options; }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelector(selector) { return this.all().find(child => child.classList.contains(selector.slice(1))) || null; }
  }
  const document = {createElement: tag => new Element(tag, document)};
  const container = document.createElement("div");
  return {container, created, find: cls => container.querySelector(`.${cls}`),
    row: id => container.all().find(item => item.classList.contains("tcf-node") && item.dataset.nodeId === id),
    listeners: () => created.reduce((sum, item) => sum + Object.values(item.listeners).reduce((n, values) => n + values.length, 0), 0)};
}

test("browser and CommonJS expose a buildless render contract without new dependencies", () => {
  const context = {TraceLensExecutionGraph: graph};
  const source = fs.readFileSync(require.resolve("../tracelens_cli/web/code-flow.js"), "utf8");
  vm.runInNewContext(source, context);
  assert.equal(typeof context.TraceLensCodeFlow.render, "function");
  assert.equal(typeof context.TraceLensCodeFlow.project, "function");
  assert.doesNotMatch(source, /fetch\(|XMLHttpRequest|localStorage|sessionStorage|setInterval|setTimeout|innerHTML/);
});

test("evidence disclosure shares the code-flow heading and retains expanded warnings", () => {
  for (const partial of [false, true]) {
    const h = fixture(), warning = '<img src=x onerror="alert(1)">';
    const stop = reader.render(h.container, model([invocation("entry")], {
      partial, warnings: [warning], limitations: ["Thread names are not stable identities."]
    }), {saved: true, trace: {service: "cb-grant-orchestrator"}});
    const heading = h.find("tcf-heading"), notes = h.find("tcf-notes"), counts = h.find("tcf-counts");
    assert.equal(notes.parentNode, heading, "closed disclosure has no separate page row");
    assert.equal(h.find("tcf-saved").parentNode, heading, "saved status remains in the heading");
    assert.equal(counts.parentNode, heading.parentNode, "node counts remain outside the disclosure");
    assert.equal(notes.tagName, "details"); assert.equal(notes.open, false);
    assert.equal(notes.childNodes[0].tagName, "summary");
    assert.equal(notes.childNodes[0].textContent, `${partial ? "Partial evidence" : "Observed evidence only"} · limits and interpretation`);
    notes.open = true; notes.fire("toggle");
    assert.ok(notes.textContent.includes(warning));
    assert.match(notes.textContent, /Thread names are not stable identities/);
    assert.match(notes.textContent, /Do not add parent and child durations/);
    h.find("tcf-expand-all").fire("click");
    assert.equal(notes.open, true, "expanding code nodes does not close evidence notes");
    assert.equal(h.container.all().some(item => item.tagName === "img"), false);
    stop(); assert.equal(h.listeners(), 0);
  }
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/code-flow.css"), "utf8");
  assert.match(css, /\.tcf-heading\s*\{[^}]*display:flex/);
  assert.match(css, /\.tcf-notes\[open\]\s*\{[^}]*flex-basis:100%/);
});

test("sticky controls follow a wrapped page header and disconnect on cleanup", () => {
  const h = fixture(), document = h.container.ownerDocument;
  let height = 50, resized, disconnected = 0;
  const observed = [];
  const header = {getBoundingClientRect: () => ({height})};
  document.querySelector = selector => selector === "body > header" ? header : null;
  document.defaultView = {ResizeObserver: class {
    constructor(callback) { resized = callback; }
    observe(target) { observed.push(target); }
    disconnect() { disconnected++; }
  }};
  const stop = reader.render(h.container, model([invocation("entry")]));
  const toolbar = h.find("tcf-toolbar");
  assert.deepEqual(observed, [header, toolbar]); assert.equal(toolbar.style.top, "58px");
  height = 120; resized(); assert.equal(toolbar.style.top, "128px");
  stop(); assert.equal(disconnected, 1);
  height = 40; resized(); assert.equal(toolbar.style.top, "128px", "detached render ignores queued resize callbacks");
});

test("sticky reader controls sit below segment navigation and follow view wrapping", () => {
  const h = fixture(), document = h.container.ownerDocument;
  let navigationHeight = 86.5, resized;
  const observed = [];
  const header = {getBoundingClientRect: () => ({height: 100.5})};
  const navigation = {getBoundingClientRect: () => ({height: navigationHeight})};
  document.querySelector = selector => selector === "body > header" ? header : selector === "#segment-toolbar" ? navigation : null;
  document.defaultView = {ResizeObserver: class {
    constructor(callback) { resized = callback; }
    observe(target) { observed.push(target); }
    disconnect() {}
  }};
  const stop = reader.render(h.container, model([invocation("entry")]));
  const toolbar = h.find("tcf-toolbar");
  assert.deepEqual(observed, [header, navigation, toolbar]);
  assert.equal(toolbar.style.top, "196px");
  navigationHeight = 46; resized();
  assert.equal(toolbar.style.top, "155px", "removing timeline filter reduces navigation height");
  navigationHeight = 0; resized();
  assert.equal(toolbar.style.top, "109px", "hidden segment does not reserve navigation space");
  stop();
});

test("search targets reserve the full sticky stack including wrapped reader controls", () => {
  const h = fixture(), document = h.container.ownerDocument;
  let headerHeight = 56, navigationHeight = 62, toolbarHeight = 102.5, resized;
  const header = {getBoundingClientRect: () => ({height: headerHeight})};
  const navigation = {getBoundingClientRect: () => ({height: navigationHeight})};
  document.querySelector = selector => selector === "body > header" ? header : selector === "#segment-toolbar" ? navigation : null;
  document.defaultView = {ResizeObserver: class {
    constructor(callback) { resized = callback; }
    observe() {}
    disconnect() {}
  }};
  const create = document.createElement;
  document.createElement = tag => {
    const node = create(tag);
    node.getBoundingClientRect = () => ({height: node.className === "tcf-toolbar" ? toolbarHeight : 0});
    return node;
  };
  const stop = reader.render(h.container, model([invocation("root"), invocation("needle", "root")]));
  const wrapper = h.find("tcf"), search = h.find("tcf-search-input");
  assert.equal(wrapper.style["--cf-sticky-offset"], "237px");
  toolbarHeight = 189.1; resized();
  assert.equal(wrapper.style["--cf-sticky-offset"], "324px");
  headerHeight = 91.5; navigationHeight = 105;
  resized();
  assert.equal(wrapper.style["--cf-sticky-offset"], "403px");
  toolbarHeight = 213;
  search.value = "needle"; search.fire("input");
  assert.equal(wrapper.style["--cf-sticky-offset"], "426px", "search measures before observer delivery");
  assert.deepEqual(h.row("needle").querySelector(".tcf-row").scrollOptions, {block: "nearest", inline: "nearest"});
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/code-flow.css"), "utf8");
  assert.match(css, /\.tcf-row\s*\{[^}]*scroll-margin-top:\s*var\(--cf-sticky-offset/);
  stop();
  toolbarHeight = 300; resized();
  assert.equal(wrapper.style["--cf-sticky-offset"], "426px", "queued measurements do not affect a detached render");
});

test("standalone reader retains CSS top fallback and measures search offsets without ResizeObserver", () => {
  const h = fixture(), document = h.container.ownerDocument;
  let toolbarHeight = 110;
  const listeners = new Map();
  document.defaultView = {
    getComputedStyle: () => ({top: "64px"}),
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name, callback) { if (listeners.get(name) === callback) listeners.delete(name); }
  };
  const create = document.createElement;
  document.createElement = tag => {
    const node = create(tag);
    node.getBoundingClientRect = () => ({height: node.className === "tcf-toolbar" ? toolbarHeight : 0});
    return node;
  };
  const stop = reader.render(h.container, model([invocation("needle")]));
  const toolbar = h.find("tcf-toolbar"), wrapper = h.find("tcf");
  assert.equal(toolbar.style.top, undefined, "standalone top remains controlled by CSS");
  assert.equal(wrapper.style["--cf-sticky-offset"], "182px");
  toolbarHeight = 150; listeners.get("resize")();
  assert.equal(wrapper.style["--cf-sticky-offset"], "222px");
  toolbarHeight = 180;
  const search = h.find("tcf-search-input"); search.value = "needle"; search.fire("input");
  assert.equal(wrapper.style["--cf-sticky-offset"], "252px");
  stop(); assert.equal(listeners.size, 0);
});

test("empty and malformed models stay empty without fabricated invocation evidence", () => {
  for (const input of [null, {}, {nodes: "bad"}, model([null, {}, {id: "bad", kind: "oops"}])]) {
    const h = fixture(), stop = reader.render(h.container, input, null);
    assert.match(h.container.textContent, /No observed invocation or marker nodes/);
    assert.equal(h.find("tcf-notes").open, false); stop();
    assert.equal(h.listeners(), 0);
  }
});

test("code flow identifies explicit database/cache calls and distinguishes cache from queue", () => {
  const h = fixture();
  const stop = reader.render(h.container, model([
    marker("cache", null, "EXTERNAL_CALL", {boundary: {boundaryKind: "cache", system: "redis", operation: "get", evidenceKind: "client-boundary"}}),
    marker("db", null, "EXTERNAL_CALL", {boundary: {boundaryKind: "database", system: "clickhouse", operation: "queryRecords", evidenceKind: "client-boundary"}}),
    marker("queue", null, "EXTERNAL_CALL", {boundary: {boundaryKind: "messaging", system: "sqs", direction: "publish", evidenceKind: "client-boundary"}}),
    marker("wrapper", null, "EXTERNAL_CALL", {boundary: {boundaryKind: "database", system: "clickhouse", evidenceKind: "wrapper-entry"}})
  ]));
  assert.match(h.row("cache").textContent, /Cache call · redis · get/);
  assert.match(h.row("db").textContent, /Database call · clickhouse · queryRecords/);
  assert.match(h.row("cache").className, /tcf-cache/);
  assert.match(h.row("queue").className, /tcf-queue/);
  assert.doesNotMatch(h.row("wrapper").textContent, /Database call/);
  stop();
});

test("projection preserves validated nesting, captures sibling order and never joins lanes", () => {
  const nodes = [invocation("root"), invocation("later", "root", {entry: {index: 10}}), invocation("first", "root", {entry: {index: 2}}),
    invocation("worker", "root", {laneId: "two"}), invocation("untrusted-parent", "root", {relationship: null})];
  const input = model(nodes), before = JSON.stringify(input), view = reader.project(input);
  assert.deepEqual(view.byId.get("root").children, ["first", "later"]);
  assert.equal(view.byId.get("worker").parentId, null); assert.equal(view.invalidLinks, 2);
  assert.deepEqual(view.lanes[1].roots, ["worker"]);
  assert.equal(view.byId.get("first").ordinal, 2); assert.equal(view.byId.get("worker").ordinal, 1);
  assert.equal(JSON.stringify(input), before);
  const h = fixture(), stop = reader.render(h.container, input);
  assert.match(h.container.textContent, /no asynchronous handoff is established/);
  assert.equal(h.container.all().some(item => item.tagName === "svg"), false); stop();
});

test("overview opens worker parent and immediate stages but folds their helper subtrees", () => {
  const nodes = [invocation("worker"), invocation("mapping", "worker"), invocation("getter", "mapping"),
    invocation("validation", "worker"), invocation("validate-helper", "validation"), invocation("creation", "worker")];
  const h = fixture(), stop = reader.render(h.container, model(nodes));
  assert.equal(h.row("worker").querySelector(".tcf-branch").open, true);
  assert.equal(h.row("mapping").querySelector(".tcf-branch").open, false);
  assert.equal(h.row("mapping").hidden, false); assert.equal(h.row("getter").hidden, true);
  assert.match(h.find("tcf-counts").textContent, /^4 nodes shown · 2 collapsed/);
  const nested = h.row("mapping").querySelector(".tcf-branch"); nested.open = true; nested.fire("toggle");
  assert.equal(h.row("getter").hidden, false); assert.match(h.find("tcf-counts").textContent, /^5 nodes shown · 1 collapsed/);
  stop();
});

test("Spring proxies are visibly labeled, not removed or reparented", () => {
  const proxy = invocation("proxy", null, {className: "example.FeatureService$$SpringCGLIB$$0"});
  const input = model([proxy, invocation("actual", "proxy"), invocation("helper", "actual")]);
  const before = JSON.stringify(input), h = fixture(), stop = reader.render(h.container, input);
  assert.equal(h.row("proxy").querySelector(".tcf-class").textContent, "FeatureService");
  assert.equal(h.row("proxy").querySelector(".tcf-proxy").textContent, "Spring proxy");
  assert.ok(h.row("proxy").querySelector(".tcf-title").title.includes("$$SpringCGLIB$$0"));
  assert.equal(reader.project(input).byId.get("actual").parentId, "proxy");
  assert.equal(JSON.stringify(input), before); stop();
});

test("collapsed ancestors report available failures and boundaries without request-failure inference", () => {
  const input = model([invocation("root"), invocation("service", "root"), invocation("throws", "service", {outcome: "threw", exceptionType: "example.Failure"}),
    marker("db", "service", "DB_ROUTE", {boundary: {destination: "TEST_MASTER"}}),
    marker("queue", "service", "EXTERNAL_CALL", {boundary: {boundaryKind: "messaging", direction: "publish", system: "sqs", destination: "events", status: "SUBMITTED"}})]);
  const h = fixture(), stop = reader.render(h.container, input);
  assert.equal(h.row("root").querySelector(".tcf-error-badge").textContent, "1 exception observation");
  assert.equal(h.row("root").querySelector(".tcf-boundary-badge").textContent, "2 boundaries");
  assert.match(h.find("tcf-notes").textContent, /throwing does not necessarily fail the request/);
  const detail = h.row("db").querySelector(".tcf-evidence"); detail.open = true; detail.fire("toggle");
  assert.match(detail.textContent, /Pool selection only; no query or database success/);
  stop();
});

test("inline evidence is lazy, inert, retains exact source callback identity and cleans listeners", () => {
  const unsafe = '<img src=x onerror="alert(1)">', node = invocation("x", null, {className: unsafe, methodName: unsafe});
  const h = fixture(), calls = [], stop = reader.render(h.container, model([node]), {onRevealEvidence: value => calls.push(value)});
  assert.ok(h.container.textContent.includes(unsafe)); assert.equal(h.find("tcf-inline"), null);
  const detail = h.row("x").querySelector(".tcf-evidence"); detail.open = true; detail.fire("toggle");
  assert.match(h.find("tcf-inline").textContent, /First 40, last 32.*Not a continuous source range/);
  assert.equal(calls.length, 0); h.find("tcf-source").fire("click"); assert.equal(calls[0], node);
  assert.equal(h.container.all().filter(item => item.tagName === "img").length, 0);
  detail.open = false; detail.fire("toggle"); detail.open = true; detail.fire("toggle");
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-inline")).length, 1);
  stop(); stop(); assert.equal(h.listeners(), 0); assert.equal(h.container.childNodes.length, 0);
});

test("code-flow SQL marker reveals raw text and preserves evidence navigation without commit claims", () => {
  const raw = "select '<img src=x onerror=alert(1)>'", boundary = {boundaryKind: "database", system: "mysql",
    evidenceKind: "jdbc-execution", direction: "outbound", operation: "execute", status: "ERROR", sql: raw,
    durationMs: 4.5, errorType: "java.sql.SQLException"};
  const node = marker("sql", null, "EXTERNAL_CALL", {boundary}), h = fixture(), opened = [];
  const stop = reader.render(h.container, model([node]), {onRevealEvidence: value => opened.push(value)});
  assert.match(h.container.textContent, /SQL query.*mysql/);
  const detail = h.row("sql").querySelector(".tcf-evidence"); detail.open = true; detail.fire("toggle");
  assert.equal(h.find("jdbc-sql").textContent, raw);
  assert.match(h.find("jdbc-evidence").textContent, /Execution threw.*4.5 ms client-side.*transaction commit/s);
  h.find("tcf-source").fire("click"); assert.equal(opened[0], node);
  assert.equal(h.container.all().filter(item => item.tagName === "img").length, 0);
  stop(); assert.equal(h.listeners(), 0);
});

test("overview caps visible rows at 40 with explicit recovery and distinct omitted counts", () => {
  const h = fixture(), nodes = Array.from({length: 250}, (_, i) => invocation(`n${i}`));
  const stop = reader.render(h.container, model(nodes, {counts: {nodesOmitted: 99}}));
  assert.match(h.find("tcf-counts").textContent, /^40 nodes shown · 0 collapsed · 160 hidden by overview · 0 filtered · 149 omitted/);
  assert.equal(h.find("tcf-show-remaining").hidden, false);
  h.find("tcf-show-remaining").fire("click");
  assert.match(h.find("tcf-counts").textContent, /^200 nodes shown/); assert.equal(h.find("tcf-show-remaining").hidden, true);
  h.find("tcf-overview").fire("click"); assert.match(h.find("tcf-counts").textContent, /^40 nodes shown/);
  assert.match(h.find("tcf-notes").textContent, /99 model nodes omitted upstream.*Expand all cannot recover/);
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-node")).length, 200);
  stop();
});

test("expand/collapse/filter preserve scope and count hidden separately from omissions", () => {
  const input = model([invocation("root"), invocation("child", "root"), invocation("deep", "child"), invocation("other", null, {laneId: "two"})]);
  const h = fixture(), stop = reader.render(h.container, input);
  h.find("tcf-collapse-all").fire("click"); assert.match(h.find("tcf-counts").textContent, /^0 nodes shown · 4 collapsed/);
  const filter = h.find("tcf-thread-filter"); filter.value = "two"; filter.fire("change");
  assert.match(h.find("tcf-counts").textContent, /^1 nodes shown · 0 collapsed · 3 filtered · 0 omitted · 1 thread-name lane$/);
  h.find("tcf-expand-all").fire("click"); assert.equal(filter.value, "two");
  filter.value = ""; filter.fire("change"); assert.match(h.find("tcf-counts").textContent, /^4 nodes shown · 0 collapsed/);
  assert.equal(h.find("tcf-notes").open, false); stop();
});

test("small linear lanes fold in overview when another lane has more work; failures remain visible", () => {
  const nodes = [invocation("filter"), invocation("proxy", "filter"), invocation("controller", "proxy"),
    invocation("worker", null, {laneId: "two"}), invocation("mapping", "worker", {laneId: "two"}),
    invocation("validation", "worker", {laneId: "two"}), invocation("create", "worker", {laneId: "two"})];
  const input = model(nodes), view = reader.project(input);
  assert.equal(view.defaultOpenLanes.has("one"), false); assert.equal(view.defaultOpenLanes.has("two"), true);
  const h = fixture(), stop = reader.render(h.container, input);
  assert.equal(h.row("filter").hidden, true); assert.equal(h.row("mapping").hidden, false);
  assert.match(h.find("tcf-counts").textContent, /^4 nodes shown · 3 collapsed/);
  assert.match(h.row("mapping").querySelector(".tcf-ordinal").title, /not an event sequence number/);
  const filter = h.find("tcf-thread-filter"); filter.value = "one"; filter.fire("change");
  assert.equal(h.row("controller").hidden, false); assert.match(h.find("tcf-counts").textContent, /^3 nodes shown/);
  h.find("tcf-overview").fire("click"); assert.match(h.find("tcf-counts").textContent, /^3 nodes shown/);
  h.find("tcf-expand-all").fire("click"); assert.match(h.find("tcf-counts").textContent, /^3 nodes shown/);
  stop();
  nodes[2] = invocation("controller", "proxy", {outcome: "threw"});
  assert.equal(reader.project(model(nodes)).defaultOpenLanes.has("one"), true);
});

test("cycles and depth limits remain bounded and do not invent repaired parent edges", () => {
  const cycle = reader.project(model([invocation("a", "b"), invocation("b", "a")]));
  assert.equal(cycle.cycleLinks, 2); assert.equal(cycle.byId.get("a").parentId, null);
  const deep = reader.project(model(Array.from({length: 40}, (_, i) => invocation(`n${i}`, i ? `n${i - 1}` : null))));
  assert.equal(deep.nodes.length, 16); assert.equal(deep.omittedNodes, 24);
  assert.equal(deep.byId.get("n0").availableDescendants, 15);
});

test("HTTP calls remain explicitly unplaced, capped, and never attached to a guessed method", () => {
  const h = fixture(), calls = Array.from({length: 25}, () => ({httpMethod: "POST", destinationName: "cb-ems (127.0.0.1:8082)", path: "/features", httpStatus: 201}));
  const stop = reader.render(h.container, model([invocation("root")]), {saved: true, trace: {service: "cb-app", path: "/api/v2/features", httpMethod: "POST", httpCalls: calls, httpCallsTruncated: true}});
  assert.match(h.find("tcf-context").textContent, /cb-app · POST · \/api\/v2\/features/);
  assert.match(h.find("tcf-saved").textContent, /Saved · not live/);
  const unplaced = h.find("tcf-unplaced"); assert.equal(unplaced.open, false);
  assert.match(unplaced.textContent, /no invocation owner/); assert.match(unplaced.textContent, /1–20 of 25 matching HTTP observations/);
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-http")).length, 20);
  h.find("tcf-http-next").fire("click");
  assert.match(unplaced.textContent, /21–25 of 25 matching HTTP observations/);
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-http")).length, 5);
  assert.equal(h.find("tcf-http-next").disabled, true);
  h.find("tcf-http-previous").fire("click");
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-http")).length, 20);
  assert.equal(h.row("root").querySelector(".tcf-http"), null); stop();
});

test("HTTP search reaches later bounded observations without guessing invocation ownership", () => {
  const calls = Array.from({length: 48}, (_, index) => ({httpMethod: "GET", peer: "127.0.0.1:8082", destinationName: "cb-ems",
    path: index === 47 ? "/entitlements/needle" : "/features", status: "COMPLETED", httpStatus: 200}));
  const h = fixture(), stop = reader.render(h.container, model([invocation("root")]), {trace: {httpCalls: calls}});
  h.find("tcf-http-jump").fire("click");
  assert.equal(h.find("tcf-unplaced").open, true);
  assert.equal(h.container.ownerDocument.activeElement, h.find("tcf-http-search"));
  const search = h.find("tcf-http-search"); search.value = "needle"; search.fire("input");
  assert.match(h.find("tcf-http-status").textContent, /1–1 of 1 matching/);
  assert.match(h.find("tcf-http").textContent, /\/entitlements\/needle/);
  assert.equal(h.row("root").querySelector(".tcf-http"), null);
  search.value = "<img src=x onerror=alert(1)>"; search.fire("input");
  assert.match(h.find("tcf-http-status").textContent, /does not establish that a call did not happen/);
  assert.equal(h.container.all().some(item => item.tagName === "img"), false);
  search.value = "127.0.0.1"; search.fire("input");
  assert.match(h.find("tcf-http-status").textContent, /1–20 of 48 matching/);
  stop(); assert.equal(h.listeners(), 0);
});

test("application logic folds only recognized calls and keeps evidence, children and failures reversible", () => {
  const h = fixture(), input = model([
    invocation("proxy", null, {className: "example.Service$$SpringCGLIB$$0"}),
    invocation("business", "proxy", {methodName: "getEntitlements"}),
    marker("db", "business", "DB_ROUTE", {route: "MASTER"}),
    invocation("access", null, {methodName: "access$100"}),
    invocation("incomplete", null, {className: "example.Service$$SpringCGLIB$$0", complete: false}),
    invocation("failed-proxy", null, {className: "example.Service$$SpringCGLIB$$0"}),
    marker("failure", "failed-proxy", "EXCEPTION", {exceptionType: "example.Failure"})
  ]);
  const stop = reader.render(h.container, input, {callView: "application"});
  assert.equal(h.row("proxy").getAttribute("data-folded"), "true");
  assert.equal(h.row("access").getAttribute("data-folded"), "true");
  for (const id of ["business", "db", "incomplete", "failed-proxy", "failure"]) {
    assert.equal(h.row(id).getAttribute("data-folded"), "false", id);
    assert.equal(h.row(id).hidden, false, id);
  }
  assert.equal(reader.project(input).byId.get("business").parentId, "proxy");
  assert.match(h.find("tcf-counts").textContent, /2 recorded calls folded/);
  h.row("proxy").querySelector(".tcf-folded-call").fire("click");
  assert.equal(h.row("proxy").querySelector(".tcf-evidence").hidden, false);
  const select = h.find("tcf-call-view"); select.value = "all"; select.fire("change");
  assert.equal(h.row("access").querySelector(".tcf-evidence").hidden, false);
  assert.doesNotMatch(h.find("tcf-counts").textContent, /recorded calls folded/);
  select.value = "application"; select.fire("change");
  assert.equal(h.row("proxy").getAttribute("data-folded"), "true");
  const search = h.find("tcf-search-input"); search.value = "access$100"; search.fire("input");
  assert.equal(h.row("access").getAttribute("data-folded"), "false");
  h.find("tcf-search-clear").fire("click");
  assert.equal(h.row("access").getAttribute("data-folded"), "true");
  stop(); assert.equal(h.listeners(), 0);
});

test("malformed HTTP entries are not counted as observations and scanning stays bounded", () => {
  const h = fixture(), stop = reader.render(h.container, model([]), {trace: {httpCalls: [null, "bad", [] , {}]}});
  assert.match(h.find("tcf-unplaced").textContent, /HTTP boundaries · 1 unplaced observations/);
  assert.match(h.find("tcf-unplaced").textContent, /3 malformed HTTP entries skipped/);
  assert.equal(h.container.all().filter(item => item.classList.contains("tcf-http")).length, 1); stop();
  const limited = fixture(), done = reader.render(limited.container, model([]), {trace: {httpCalls: Array(6000).fill({httpMethod: "GET"})}});
  assert.match(limited.find("tcf-unplaced").textContent, /5000 unplaced observations/);
  assert.match(limited.find("tcf-unplaced").textContent, /Only the first 5000 HTTP entries were inspected/); done();
});

test("search reveals collapsed matches, highlights them, navigates by keyboard and restores the overview", () => {
  const input = model([invocation("root"), invocation("branch", "root"), invocation("needle-one", "branch"),
    invocation("needle-two", "branch"), invocation("sibling", "root")]);
  const h = fixture(), stop = reader.render(h.container, input), search = h.find("tcf-search-input");
  assert.equal(h.row("needle-one").hidden, true);
  search.value = "needle"; search.fire("input");
  assert.equal(h.row("needle-one").hidden, false);
  assert.equal(h.row("needle-one").getAttribute("data-search-match"), "true");
  assert.equal(h.row("needle-two").getAttribute("data-search-match"), "true");
  assert.match(h.find("tcf-search-status").textContent, /1 of 2/);
  assert.match(h.find("tcf-search-scope").textContent, /5 searchable retained model nodes.*Raw events.*not searched/);
  let prevented = 0;
  search.fire("keydown", {key: "Enter", preventDefault() { prevented++; }});
  assert.match(h.find("tcf-search-status").textContent, /2 of 2/);
  assert.equal(h.row("needle-two").getAttribute("data-selected"), "true");
  search.fire("keydown", {key: "Enter", shiftKey: true, preventDefault() { prevented++; }});
  assert.match(h.find("tcf-search-status").textContent, /1 of 2/);
  search.fire("keydown", {key: "Escape", preventDefault() { prevented++; }});
  assert.equal(search.value, ""); assert.equal(h.row("needle-one").hidden, true);
  assert.equal(h.row("needle-one").getAttribute("data-search-match"), "false");
  assert.equal(prevented, 3); stop(); assert.equal(h.listeners(), 0);
});

test("reader focus keeps observed ancestry and descendants, restores prior filter, and exposes source safely", () => {
  const input = model([invocation("root"), invocation("chosen", "root"), invocation("child", "chosen"),
    invocation("sibling", "root"), invocation("worker", "chosen", {laneId: "two"})]);
  const h = fixture(), opened = [], stop = reader.render(h.container, input, {onOpenSource: value => opened.push(value)});
  const filter = h.find("tcf-thread-filter"); filter.value = "one"; filter.fire("change");
  const summary = h.row("chosen").querySelector(".tcf-row"); summary.fire("click");
  h.find("tcf-focus-branch").fire("click");
  for (const id of ["root", "chosen", "child"]) assert.equal(h.row(id).hidden, false);
  assert.equal(h.row("sibling").hidden, true);
  assert.match(h.find("tcf-counts").textContent, /^3 nodes shown.*2 outside focused branch/);
  assert.match(h.find("tcf-search-status").textContent, /No asynchronous handoff is inferred/);
  const detail = h.row("chosen").querySelector(".tcf-evidence"); detail.open = true; detail.fire("toggle");
  h.find("tcf-open-source").fire("click"); assert.deepEqual(opened, [{className: "example.FeatureService", line: 40}]);
  h.find("tcf-search-clear").fire("click"); assert.equal(filter.value, "one");
  assert.equal(h.row("sibling").hidden, false); stop();
});

test("reader no-match and hostile queries never infer non-execution or inject content", () => {
  const h = fixture(), stop = reader.render(h.container, model([invocation("root")]), {onOpenSource() {}});
  const input = h.find("tcf-search-input"); input.value = "<img src=x onerror=alert(1)>"; input.fire("input");
  assert.match(h.find("tcf-search-status").textContent, /does not prove the code did not execute/);
  assert.equal(h.container.all().some(item => item.tagName === "img"), false);
  assert.equal(h.find("tcf-search-next").disabled, true);
  assert.equal(h.row("root").hidden, false); stop();
  const bad = fixture(), cleanup = reader.render(bad.container, model([invocation("bad", null, {className: "../secret"})]), {onOpenSource() {}});
  const detail = bad.row("bad").querySelector(".tcf-evidence"); detail.open = true; detail.fire("toggle");
  assert.equal(bad.find("tcf-open-source"), null); cleanup();
});
