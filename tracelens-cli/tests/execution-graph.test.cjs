"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const graph = require("../tracelens_cli/web/execution-graph.js");

const invocation = (id, parentId = null, extra = {}) => ({id, kind: "invocation", laneId: "lane_1", parentId,
  relationship: parentId ? "observed-nesting" : null, className: "com.example.FeatureService", methodName: id,
  entry: {index: 0, seq: 1}, exit: {index: 4, seq: 5}, outcome: "returned", complete: true, durationMs: 2.5,
  observedLines: {first: 70, last: 42, count: 2, firstEvidence: {index: 1}, lastEvidence: {index: 3}}, ...extra});
const marker = (id, parentId, eventType, extra = {}) => ({id, kind: "marker", laneId: "lane_1", parentId,
  relationship: parentId ? "observed-nesting" : null, eventType, evidence: {index: 2, seq: 3}, ...extra});
const model = (nodes, extra = {}) => ({schemaVersion: 1, kind: "segment-execution", nodes,
  lanes: [{id: "lane_1", threadName: "http-worker"}, {id: "lane_2", threadName: "queue-worker"}],
  warnings: [], limitations: [], counts: {}, partial: false, ...extra});

// No browser dependency. Unsafe HTML writes fail, and detached listener cleanup
// remains observable rather than being hidden by garbage collection.
function fixture(width = 570, height = 480, {dialogMode} = {}) {
  const created = [];
  class Element {
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = []; this.style = {}; this.attrs = {};
      this.dataset = {}; this.listeners = {}; this.className = ""; this.value = "";
      this.clientWidth = width; this.clientHeight = height; this.scrollLeft = 0; this.scrollTop = 0;
      this.classList = {contains: name => this.className.split(" ").includes(name)};
      if (tag === "dialog") this.open = false;
      if (tag === "dialog" && dialogMode) {
        this.showModal = () => {
          if (dialogMode === "throwing") throw new Error("Dialog unavailable");
          this.open = true;
        };
        this.close = () => { this.open = false; this.fire("close"); };
      }
      created.push(this);
    }
    set innerHTML(_) { throw new Error("Unsafe HTML interpolation"); }
    set textContent(value) { this.text = String(value); this.replaceChildren(); }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(" "); }
    append(...children) { children.forEach(child => { child.remove(); child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; if (children.length) this.text = ""; this.append(...children); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(name, value) { this.attrs[name] = String(value); if (name === "class") this.className = String(value); }
    getAttribute(name) { return this.attrs[name]; }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    removeEventListener(name, handler) { this.listeners[name] = (this.listeners[name] || []).filter(item => item !== handler); }
    fire(name, event = {}) { (this.listeners[name] || []).slice().forEach(handler => handler({target: this, ...event})); }
    focus() { this.ownerDocument.activeElement = this; this.fire("focus"); }
    contains(item) { return item === this || this.all().includes(item); }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelector(selector) { return this.all().find(child => child.classList.contains(selector.slice(1))) || null; }
    setPointerCapture(id) { this.pointer = id; }
    hasPointerCapture(id) { return this.pointer === id; }
    releasePointerCapture(id) { if (this.pointer === id) this.pointer = null; }
  }
  const document = {activeElement: null, createElement: tag => new Element(tag, document), createElementNS: (_, tag) => new Element(tag, document)};
  const container = document.createElement("div");
  return {container, created, find: cls => container.querySelector(`.${cls}`),
    node: id => container.all().find(item => item.classList.contains("teg-node") && item.dataset.nodeId === id),
    listeners: () => created.reduce((sum, item) => sum + Object.values(item.listeners).reduce((n, list) => n + list.length, 0), 0)};
}

function assertSeparatedCards(result) {
  for (const item of result.nodes) {
    const lane = result.lanes.find(lane => lane.id === item.laneId);
    assert.ok(item.y >= lane.y, `${item.id} starts inside its lane`);
    assert.ok(item.y + item.height <= lane.y + lane.height, `${item.id} ends inside its lane`);
    for (const other of result.nodes) {
      if (item === other) continue;
      const separated = item.x + item.width <= other.x || other.x + other.width <= item.x
        || item.y + item.height <= other.y || other.y + other.height <= item.y;
      assert.ok(separated, `${item.id} and ${other.id} must not overlap`);
    }
  }
}

test("search jumps through collapsed evidence without refitting and restores the prior view", () => {
  const h = fixture(), input = model([invocation("root"), invocation("branch", "root"),
    invocation("needle-one", "branch"), invocation("needle-two", "branch"), invocation("sibling", "root")]);
  const stop = graph.render(h.container, input), query = h.find("teg-search-input");
  assert.equal(h.node("needle-one"), undefined);
  h.find("teg-fit").fire("click"); const scale = h.find("teg-zoom-label").textContent;
  query.value = "needle"; query.fire("input");
  assert.ok(h.node("needle-one").classList.contains("teg-search-match"));
  assert.ok(h.node("needle-two").classList.contains("teg-search-match"));
  assert.equal(h.find("teg-zoom-label").textContent, scale);
  assert.match(h.find("teg-search-status").textContent, /1 of 2/);
  let prevented = 0;
  query.fire("keydown", {key: "Enter", preventDefault() { prevented++; }});
  assert.equal(h.node("needle-two").getAttribute("aria-pressed"), "true");
  assert.match(h.find("teg-search-status").textContent, /2 of 2/);
  query.fire("keydown", {key: "Enter", shiftKey: true, preventDefault() { prevented++; }});
  assert.equal(h.node("needle-one").getAttribute("aria-pressed"), "true");
  query.fire("keydown", {key: "Escape", preventDefault() { prevented++; }});
  assert.equal(h.node("needle-one"), undefined); assert.equal(query.value, "");
  assert.equal(h.find("teg-zoom-label").textContent, scale); assert.equal(prevented, 3);
  stop(); assert.equal(h.listeners(), 0);
});

test("graph branch focus keeps ancestors and descendants without attaching unknown async work", () => {
  const h = fixture(), input = model([invocation("root"), invocation("chosen", "root"), invocation("child", "chosen"),
    invocation("sibling", "root"), invocation("worker", "chosen", {laneId: "lane_2"})]);
  const stop = graph.render(h.container, input), filter = h.find("teg-thread-filter");
  filter.value = "lane_1"; filter.fire("change"); h.node("chosen").fire("click");
  h.find("teg-zoom-in").fire("click"); const scale = h.find("teg-zoom-label").textContent;
  h.find("teg-focus-branch").fire("click");
  assert.deepEqual(h.container.all().filter(item => item.classList.contains("teg-node")).map(item => item.dataset.nodeId), ["root", "chosen", "child"]);
  assert.match(h.find("teg-search-status").textContent, /observed ancestors and descendants.*No asynchronous/);
  assert.match(h.find("teg-counts").textContent, /^3 nodes shown · 2 hidden/);
  assert.equal(h.find("teg-zoom-label").textContent, scale);
  h.find("teg-search-clear").fire("click");
  assert.equal(filter.value, "lane_1"); assert.ok(h.node("sibling")); assert.equal(h.node("worker"), undefined);
  assert.equal(h.find("teg-zoom-label").textContent, scale); stop();
});

test("graph no-match search is explicit about retained-model scope and source actions require valid coordinates", () => {
  const h = fixture(), source = [], input = model([invocation("root"), invocation("bad", "root", {observedLines: {first: -1}})], {counts: {nodesOmitted: 91}});
  const stop = graph.render(h.container, input, {onOpenSource: location => source.push(location)});
  const query = h.find("teg-search-input"); query.value = "<img src=x onerror=alert(1)>"; query.fire("input");
  assert.match(h.find("teg-search-status").textContent, /does not prove the code did not execute/);
  assert.match(h.find("teg-search-scope").textContent, /2 searchable retained model nodes · 91 omitted.*Raw events.*not searched/);
  assert.equal(h.find("teg-search-next").disabled, true); assert.ok(h.node("root"));
  assert.equal(h.container.all().some(item => item.tagName === "img"), false);
  h.node("root").fire("click"); h.find("teg-open-source").fire("click");
  assert.deepEqual(source, [{className: "com.example.FeatureService", line: 70}]);
  h.node("bad").fire("click"); assert.equal(h.find("teg-open-source"), null);
  stop(); assert.equal(h.listeners(), 0);
});

test("browser global and CommonJS expose the same buildless contract", () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/execution-graph.js"), "utf8"), context);
  for (const name of ["render", "layout", "fitScale"]) assert.equal(typeof context.TraceLensExecutionGraph[name], "function");
  assert.equal(graph.limits.nodes, 200);
});

test("empty/malformed models remain bounded and do not render invented nodes", () => {
  for (const input of [null, {}, model([]), model([null, {}, {id: "x", kind: "unknown"}])]) {
    const result = graph.layout(input);
    assert.equal(result.nodes.length, 0); assert.equal(result.width, 0); assert.equal(result.height, 0);
    const h = fixture(); const cleanup = graph.render(h.container, input);
    assert.match(h.container.textContent, /No observed invocation or marker nodes/); cleanup();
  }
});

test("only explicit same-thread-name observed nesting creates edges", () => {
  const input = model([invocation("parent"), invocation("child", "parent"),
    invocation("wrong-lane", "parent", {laneId: "lane_2"}), invocation("no-relationship", "parent", {relationship: null}),
    invocation("missing", "gone"), invocation("standalone", null, {entry: {index: 1, seq: 2}})]);
  const before = JSON.stringify(input), result = graph.layout(input);
  assert.deepEqual(result.edges.map(edge => [edge.from, edge.to]), [["parent", "child"]]);
  assert.equal(result.invalidLinks, 3);
  assert.equal(result.lanes.length, 2);
  assert.equal(JSON.stringify(input), before);
});

test("duplicate identities and marker parents do not create ambiguous method edges", () => {
  const result = graph.layout(model([invocation("same"), invocation("same"), invocation("child", "same"),
    marker("db", null, "DB_ROUTE"), invocation("under-marker", "db")]));
  assert.equal(result.invalidNodes, 2); assert.equal(result.edges.length, 0); assert.equal(result.invalidLinks, 2);
});

test("cycles and self-parents terminate and drop cyclic edges without fake reattachment", () => {
  const result = graph.layout(model([invocation("a", "b"), invocation("b", "a"), invocation("self", "self"), invocation("child", "a")]));
  assert.equal(result.nodes.length, 4); assert.equal(result.cycleLinks, 3);
  assert.deepEqual(result.edges.map(edge => [edge.from, edge.to]), [["a", "child"]]);
});

test("200-node and 16-level bounds remain explicit without reconnecting omitted parents", () => {
  const flat = graph.layout(model(Array.from({length: 350}, (_, n) => invocation(`n${n}`))));
  assert.equal(flat.nodes.length, 200); assert.equal(flat.omittedNodes, 150); assert.ok(flat.height < 30000);
  const deep = graph.layout(model(Array.from({length: 200}, (_, n) => invocation(`n${n}`, n ? `n${n - 1}` : null))));
  assert.equal(deep.nodes.length, 16); assert.equal(deep.omittedNodes, 184); assert.equal(deep.edges.length, 15);
  assert.ok(deep.width < 6000);
});

test("collapsed subtrees and thread filters hide, never discard or reparent, observed nodes", () => {
  const input = model([invocation("a"), invocation("b", "a"), marker("db", "b", "DB_ROUTE"), invocation("other", null, {laneId: "lane_2"})]);
  const collapsed = graph.layout(input, {collapsed: new Set(["b"])});
  assert.deepEqual(collapsed.nodes.map(item => item.id), ["a", "b", "other"]); assert.equal(collapsed.hiddenNodes, 1);
  const filtered = graph.layout(input, {laneId: "lane_2"});
  assert.deepEqual(filtered.nodes.map(item => item.id), ["other"]); assert.equal(filtered.hiddenNodes, 3); assert.equal(filtered.edges.length, 0);
});

test("wider cards form a compact tree with parents centered alongside their child stack", () => {
  const input = model([invocation("root"), invocation("first", "root"), invocation("middle", "root"),
    invocation("last", "root"), invocation("separate"), invocation("worker", null, {laneId: "lane_2"})]);
  const before = JSON.stringify(input), result = graph.layout(input);
  const byId = id => result.nodes.find(node => node.id === id);
  for (const item of result.nodes) {
    assert.equal(item.width, 320); assert.equal(item.height, 132);
  }
  assert.equal(byId("root").y + 66, (byId("first").y + byId("last").y + 132) / 2);
  assert.equal(byId("root").y, byId("middle").y, "an ancestor does not consume a separate row");
  assert.ok(byId("first").x > byId("root").x + 320);
  assert.deepEqual(result.edges.map(edge => [edge.from, edge.to]), [["root", "first"], ["root", "middle"], ["root", "last"]]);
  assertSeparatedCards(result);
  const chain = graph.layout(model([invocation("a"), invocation("b", "a"), invocation("c", "b")]));
  assert.equal(new Set(chain.nodes.map(node => node.y)).size, 1, "a single-child chain shares a row");
  assert.equal(JSON.stringify(input), before);
});

test("fitScale fits narrow viewports, never enlarges an overview, and handles zero sizes", () => {
  const scale = graph.fitScale(1392, 766, 570, 480);
  assert.ok(1392 * scale <= 570); assert.ok(766 * scale <= 480); assert.ok(scale > 0);
  assert.equal(graph.fitScale(100, 100, 800, 500), 1);
  const largestForest = graph.layout(model(Array.from({length: 200}, (_, i) => invocation(`n${i}`))));
  const forestScale = graph.fitScale(largestForest.width, largestForest.height, 350, 220);
  assert.ok(largestForest.width * forestScale <= 350); assert.ok(largestForest.height * forestScale <= 220);
  assert.equal(graph.fitScale(0, 0, 0, 0), 1);
  assert.equal(graph.fitScale(Infinity, 100, 500, 500), 1);
});

test("untrusted labels are inert and exact evidence callback is explicit, with bounded line coordinates", () => {
  const h = fixture(), calls = [], unsafe = '<img src=x onerror="alert(1)">';
  const node = invocation("a", null, {className: unsafe, methodName: unsafe});
  const cleanup = graph.render(h.container, model([node]), {onRevealEvidence: value => calls.push(value)});
  assert.ok(h.container.textContent.includes(unsafe)); assert.ok(!h.container.all().some(item => item.tagName === "img"));
  h.node("a").focus(); h.node("a").fire("click"); assert.equal(calls.length, 0);
  assert.match(h.find("teg-details").textContent, /first 70, last 42/);
  assert.match(h.find("teg-details").textContent, /Not a continuous source range/);
  h.find("teg-source").fire("click"); assert.equal(calls[0], node);
  cleanup(); assert.equal(h.container.childNodes.length, 0); assert.equal(h.listeners(), 0);
});

test("invocation cards separate class and method labels without a redundant invocation heading", () => {
  const h = fixture(), node = invocation("service", null, {methodName: "findFeature"});
  const cleanup = graph.render(h.container, model([node]));
  assert.equal(h.node("service").querySelector(".teg-class").textContent, "FeatureService");
  assert.equal(h.node("service").querySelector(".teg-method").textContent, "findFeature()");
  assert.equal(h.node("service").querySelector(".teg-kind"), null);
  assert.doesNotMatch(h.node("service").textContent, /METHOD INVOCATION/);
  assert.equal(h.node("service").title, "com.example.FeatureService.findFeature()");
  assert.equal(h.node("service").querySelector(".teg-proxy"), null);
  cleanup();
});

test("known Spring proxies get a compact display without changing identities or full evidence", () => {
  const h = fixture(), revealed = [];
  const nodes = [invocation("spring", null, {className: "com.example.FeatureService$$SpringCGLIB$$0", methodName: "findFeature"}),
    invocation("legacy", "spring", {className: "com.example.FeatureService$$EnhancerBySpringCGLIB$$a1B2", methodName: "findFeature"})];
  const input = model(nodes), before = JSON.stringify(input);
  const cleanup = graph.render(h.container, input, {onRevealEvidence: node => revealed.push(node)});
  for (const node of nodes) {
    const card = h.node(node.id);
    assert.equal(card.querySelector(".teg-class").textContent, "FeatureService");
    assert.equal(card.querySelector(".teg-method").textContent, "findFeature()");
    assert.equal(card.querySelector(".teg-proxy").textContent, "Spring proxy");
    assert.equal(card.title, `${node.className}.findFeature()`);
    card.fire("click");
    assert.ok(h.find("teg-details").textContent.includes(node.className));
    h.find("teg-source").fire("click");
    assert.equal(revealed.at(-1), node);
    assert.equal(h.node(node.id).dataset.nodeId, node.id);
  }
  assert.deepEqual(graph.layout(input).edges.map(edge => [edge.from, edge.to]), [["spring", "legacy"]]);
  assert.equal(JSON.stringify(input), before);
  cleanup();
});

test("other dollar-suffixed classes and malformed proxy-like names are not shortened as Spring proxies", () => {
  const names = ["FeatureService$Nested", "FeatureService$$EnhancerByCGLIB$$abc", "FeatureService$$SpringCGLIB$$",
    "FeatureService$$SpringCGLIB$$bad-name", "FeatureService$$OtherProxy$$0"];
  const h = fixture(), cleanup = graph.render(h.container, model(names.map((name, i) => invocation(`n${i}`, null, {className: `com.example.${name}`}))));
  names.forEach((name, i) => {
    assert.equal(h.node(`n${i}`).querySelector(".teg-class").textContent, name);
    assert.equal(h.node(`n${i}`).querySelector(".teg-proxy"), null);
  });
  cleanup();
});

test("saved service context is plain text and stays visible when the graph enters fullscreen", () => {
  const h = fixture(570, 480, {dialogMode: "supported"});
  const service = '<img src=x onerror="alert(1)">', lensId = '<script>alert("lens")</script>';
  const cleanup = graph.render(h.container, model([invocation("a")]),
    {trace: {service, applicationId: "fallback-must-not-override-service", lensId}, saved: true});
  const context = h.find("teg-context"), capture = h.find("teg-capture-label"), heading = h.find("teg-heading");
  assert.equal(context.textContent, `${service} · ${lensId}`); assert.equal(context.title, context.textContent);
  assert.equal(capture.textContent, "Saved · not live");
  assert.equal(context.parentNode, heading); assert.equal(capture.parentNode, heading);
  assert.equal(h.container.all().some(node => ["img", "script"].includes(node.tagName)), false);
  h.find("teg-fullscreen").fire("click");
  assert.equal(h.find("teg-context"), context); assert.equal(h.find("teg-capture-label"), capture);
  assert.ok(h.find("teg-fullscreen-dialog").contains(context));
  assert.ok(h.find("teg-fullscreen-dialog").contains(capture));
  h.find("teg-fullscreen").fire("click");
  assert.equal(h.find("teg-context"), context); assert.equal(h.find("teg-capture-label"), capture);
  cleanup(); assert.equal(h.listeners(), 0);
});

test("evidence disclosure shares the call-tree heading and retains warnings through redraw and fullscreen", () => {
  for (const partial of [false, true]) {
    const h = fixture(900, 480, {dialogMode: "supported"}), warning = '<img src=x onerror="alert(1)">';
    const cleanup = graph.render(h.container, model([invocation("entry")], {
      partial, warnings: [warning], limitations: ["Thread names are not stable identities."]
    }), {saved: true, trace: {service: "cb-grant-orchestrator"}});
    const heading = h.find("teg-heading"), notes = h.find("teg-notes");
    assert.equal(notes.parentNode, heading, "closed disclosure has no separate metadata row");
    assert.equal(h.find("teg-capture-label").parentNode, heading);
    assert.equal(h.find("teg-meta").childNodes.includes(notes), false);
    assert.equal(notes.tagName, "details"); assert.equal(Boolean(notes.open), false);
    assert.equal(notes.childNodes[0].tagName, "summary");
    assert.equal(notes.childNodes[0].textContent, `${partial ? "Partial evidence" : "Observed evidence only"} · limits and interpretation`);
    notes.open = true; notes.fire("toggle");
    h.node("entry").fire("click");
    assert.equal(notes.open, true, "selecting evidence does not close the disclosure");
    assert.ok(notes.textContent.includes(warning));
    assert.match(notes.textContent, /Thread names are not stable identities/);
    assert.equal(h.container.all().some(item => item.tagName === "img"), false);
    h.find("teg-fullscreen").fire("click");
    assert.ok(h.find("teg-fullscreen-dialog").contains(notes));
    assert.equal(notes.open, true);
    h.find("teg-fullscreen").fire("click");
    cleanup(); assert.equal(h.listeners(), 0);
  }
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/execution-graph.css"), "utf8");
  assert.match(css, /\.teg-heading\s*\{[^}]*display:flex/);
  assert.match(css, /\.teg-notes\[open\]\s*\{[^}]*flex-basis:100%/);
});

test("context falls back to applicationId and absent context does not invent a capture label", () => {
  for (const options of [{}, {trace: {}}, {trace: {service: "", lensId: ""}, saved: false}, {saved: "true"}]) {
    const h = fixture(), cleanup = graph.render(h.container, model([invocation("a")]), options);
    assert.equal(h.find("teg-context"), null); assert.equal(h.find("teg-capture-label"), null);
    cleanup();
  }
  const h = fixture(), cleanup = graph.render(h.container, model([invocation("a")]),
    {trace: {applicationId: "cb-ems", lensId: "capture-42"}});
  assert.equal(h.find("teg-context").textContent, "cb-ems · capture-42");
  assert.equal(h.find("teg-context").title, "cb-ems · capture-42");
  assert.equal(h.find("teg-capture-label"), null);
  cleanup();
});

test("DB, queue and exception markers retain their evidence meanings", () => {
  const h = fixture();
  const input = model([invocation("root"), marker("db", "root", "DB_ROUTE", {route: "TEST_MASTER"}),
    marker("queue", "root", "EXTERNAL_CALL", {boundary: {boundaryKind: "messaging", direction: "publish", system: "sqs", destination: "demo-events", operation: "sendMessage", status: "SUBMITTED"}}),
    marker("exception", "root", "EXCEPTION", {exceptionType: "java.lang.IllegalArgumentException"})]);
  const cleanup = graph.render(h.container, input);
  assert.match(h.node("db").textContent, /DB route.*TEST_MASTER/);
  assert.match(h.node("queue").textContent, /Queue publish.*sqs/);
  h.node("db").fire("click"); assert.match(h.find("teg-details").textContent, /no query or database success/);
  h.node("queue").fire("click"); assert.match(h.find("teg-details").textContent, /not a timed call, return, or proof of remote processing/);
  h.node("exception").fire("click"); assert.match(h.find("teg-details").textContent, /IllegalArgumentException/);
  cleanup();
});

test("call-tree JDBC markers display raw bounded SQL as text and distinguish capture limits", () => {
  const raw = "select '<svg onload=alert(1)>'", boundary = {boundaryKind: "database", system: "postgresql",
    evidenceKind: "jdbc-execution", direction: "outbound", operation: "executeQuery", status: "OBSERVED", sql: raw, durationMs: 2};
  const h = fixture(), input = model([invocation("root"), marker("sql", "root", "EXTERNAL_CALL", {boundary}),
    marker("limit", "root", "EXTERNAL_CALL", {boundary: {...boundary, evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 5}})]);
  const stop = graph.render(h.container, input);
  assert.match(h.node("sql").textContent, /SQL query.*postgresql/);
  h.node("sql").fire("click"); assert.equal(h.find("jdbc-sql").textContent, raw);
  assert.match(h.find("teg-details").textContent, /Timed JDBC client execution.*transaction commit/s);
  h.node("limit").fire("click"); assert.match(h.find("teg-details").textContent, /SQL capture limit reached \(5 executions\)/);
  assert.equal(h.find("jdbc-sql"), null);
  stop();
});

test("HTTP snapshots remain separate bounded unplaced cards, never method graph edges", () => {
  const h = fixture(), calls = Array.from({length: 23}, () => ({httpMethod: "GET", peer: "localhost:8082", path: "/features", status: "COMPLETED", httpStatus: 200}));
  const cleanup = graph.render(h.container, model([invocation("a")]), {trace: {httpCalls: calls, httpCallsTruncated: true}});
  assert.equal(h.container.all().filter(item => item.classList.contains("teg-http-card")).length, 20);
  assert.equal(h.container.all().filter(item => item.classList.contains("teg-edge")).length, 0);
  assert.match(h.find("teg-unplaced").textContent, /cannot be placed in the method stack/);
  assert.match(h.find("teg-unplaced").textContent, /3 additional HTTP boundary cards omitted/);
  assert.match(h.find("teg-unplaced").textContent, /HTTP boundary capture was truncated/); cleanup();
});

test("overview expands subtrees and keyboard navigation reveals only explicit child/parent nodes", () => {
  const h = fixture();
  const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", "a"), invocation("c", "b")]));
  assert.equal(h.node("c"), undefined); assert.match(h.find("teg-counts").textContent, /1 hidden/);
  let prevented = 0;
  h.node("b").fire("keydown", {key: "ArrowRight", preventDefault: () => prevented++});
  assert.equal(prevented, 1); assert.equal(h.container.ownerDocument.activeElement.dataset.nodeId, "c");
  h.node("c").fire("keydown", {key: "ArrowLeft", preventDefault() {}});
  assert.equal(h.container.ownerDocument.activeElement.dataset.nodeId, "b");
  h.find("teg-overview").fire("click"); assert.equal(h.node("c"), undefined);
  h.find("teg-expand-all").fire("click"); assert.ok(h.node("c")); cleanup();
});

test("Collapse all fits lane roots, clears inline evidence and preserves the filter without discarding descendants", () => {
  const input = model([invocation("a"), invocation("b", "a"), invocation("c", "b"), marker("db", "b", "DB_ROUTE"),
    invocation("standalone"), invocation("queue", null, {laneId: "lane_2"}),
    invocation("queue-child", "queue", {laneId: "lane_2"}), invocation("queue-leaf", "queue-child", {laneId: "lane_2"})]);
  const before = JSON.stringify(input), h = fixture(), revealed = [];
  const cleanup = graph.render(h.container, input, {onRevealEvidence: node => revealed.push(node)});
  const visible = () => h.container.all().filter(node => node.classList.contains("teg-node")).map(node => node.dataset.nodeId);
  const toggle = id => h.container.all().find(node => node.classList.contains("teg-collapse") && node.dataset.nodeId === id);
  const assertFitted = () => {
    const canvas = h.find("teg-canvas"), stage = h.find("teg-stage"), viewport = h.find("teg-viewport");
    const scale = graph.fitScale(parseFloat(canvas.style.width), parseFloat(canvas.style.height), viewport.clientWidth, viewport.clientHeight);
    assert.equal(canvas.style.transform, `scale(${scale})`);
    assert.ok(parseFloat(stage.style.width) <= viewport.clientWidth);
    assert.ok(parseFloat(stage.style.height) <= viewport.clientHeight);
    assert.equal(viewport.scrollLeft, 0); assert.equal(viewport.scrollTop, 0);
    return canvas.style.transform;
  };
  h.find("teg-expand-all").fire("click");
  assert.equal(visible().length, 8);
  h.node("b").fire("click");
  const source = h.find("teg-source"); assert.ok(source);
  h.find("teg-zoom-reset").fire("click"); h.find("teg-zoom-out").fire("click");
  const chosenScale = h.find("teg-canvas").style.transform;
  h.find("teg-collapse-all").fire("click");
  assert.deepEqual(visible(), ["a", "standalone", "queue"]);
  assert.equal(h.find("teg-details"), null);
  assert.notEqual(assertFitted(), chosenScale, "Collapse all replaces manual zoom with fitted root bounds");
  assert.match(h.find("teg-counts").textContent, /3 nodes shown · 5 hidden in this view · 0 omitted · 2 thread-name lanes/);
  source.fire("click"); assert.deepEqual(revealed, [], "hidden evidence actions have no retained listener");
  toggle("a").fire("click");
  assert.deepEqual(visible(), ["a", "b", "standalone", "queue"]);
  assert.equal(toggle("b").getAttribute("aria-expanded"), "false", "deeper parents were collapsed too");

  const filter = h.find("teg-thread-filter"); filter.value = "lane_2"; filter.fire("change");
  h.find("teg-expand-all").fire("click");
  assert.deepEqual(visible(), ["queue", "queue-child", "queue-leaf"]);
  h.node("queue-child").fire("click");
  h.find("teg-zoom-reset").fire("click"); h.find("teg-zoom-in").fire("click");
  const filteredScale = h.find("teg-canvas").style.transform;
  h.find("teg-collapse-all").fire("click");
  assert.equal(filter.value, "lane_2");
  assert.deepEqual(visible(), ["queue"]);
  assert.equal(h.find("teg-details"), null);
  const fittedScale = assertFitted();
  assert.notEqual(fittedScale, filteredScale, "filtered roots are fitted too");
  assert.match(h.find("teg-counts").textContent, /1 nodes shown · 7 hidden in this view · 0 omitted · 1 thread-name lanes/);
  h.find("teg-expand-all").fire("click");
  assert.deepEqual(visible(), ["queue", "queue-child", "queue-leaf"]);
  assert.equal(h.find("teg-canvas").style.transform, fittedScale);
  filter.value = ""; filter.fire("change");
  assert.equal(visible().length, 8, "Expand all restores every bounded descendant, including filtered lanes");
  assert.equal(JSON.stringify(input), before);
  cleanup(); cleanup(); assert.equal(h.listeners(), 0); assert.equal(h.container.childNodes.length, 0);
});

test("fit/zoom affect a scroll-size wrapper and background pointer pan excludes node controls", () => {
  const h = fixture(); const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", "a")]));
  const viewport = h.find("teg-viewport"), canvas = h.find("teg-canvas"), stage = h.find("teg-stage");
  const initial = canvas.style.transform;
  h.find("teg-zoom-in").fire("click"); assert.notEqual(canvas.style.transform, initial);
  h.find("teg-fit").fire("click");
  const fitted = graph.fitScale(parseFloat(canvas.style.width), parseFloat(canvas.style.height), viewport.clientWidth, viewport.clientHeight);
  assert.equal(canvas.style.transform, `scale(${fitted})`);
  assert.ok(parseFloat(stage.style.width) <= viewport.clientWidth);
  assert.ok(parseFloat(stage.style.height) <= viewport.clientHeight);
  h.find("teg-zoom-reset").fire("click"); assert.equal(canvas.style.transform, "scale(1)");
  assert.ok(parseFloat(stage.style.width) > 0);
  viewport.scrollLeft = 30; viewport.scrollTop = 40;
  viewport.fire("pointerdown", {button: 0, pointerId: 1, clientX: 100, clientY: 100, target: h.node("a")});
  viewport.fire("pointermove", {pointerId: 1, clientX: 80, clientY: 70}); assert.equal(viewport.scrollLeft, 30);
  viewport.fire("pointerdown", {button: 0, pointerId: 1, clientX: 100, clientY: 100});
  viewport.fire("pointermove", {pointerId: 1, clientX: 80, clientY: 70});
  assert.equal(viewport.scrollLeft, 50); assert.equal(viewport.scrollTop, 70);
  viewport.fire("pointerup", {pointerId: 1}); assert.equal(viewport.pointer, null); cleanup();
});

test("a tall forest starts readable with scrolling; whole-diagram fit is explicit", () => {
  const h = fixture(570, 480);
  const cleanup = graph.render(h.container, model(Array.from({length: 80}, (_, index) => invocation(`n${index}`))));
  const canvas = h.find("teg-canvas"), stage = h.find("teg-stage");
  assert.equal(canvas.style.transform, "scale(1)");
  assert.ok(parseFloat(stage.style.height) > 4000);
  assert.equal(h.find("teg-help"), null);
  assert.doesNotMatch(h.container.textContent, /Scroll to explore at readable size/);
  h.find("teg-fit").fire("click");
  assert.ok(parseFloat(stage.style.height) <= 480);
  h.find("teg-overview").fire("click");
  assert.equal(canvas.style.transform, "scale(1)"); cleanup();
});

test("diagram controls stay in the frame outside the scrolling and zooming layers", () => {
  const h = fixture();
  const cleanup = graph.render(h.container, model(Array.from({length: 80}, (_, i) => invocation(`n${i}`))));
  const frame = h.find("teg-frame"), toolbar = h.find("teg-toolbar"), viewport = h.find("teg-viewport");
  assert.equal(toolbar.parentNode, frame);
  assert.equal(viewport.parentNode, frame);
  assert.equal(viewport.all().includes(toolbar), false);
  assert.equal(h.find("teg-canvas").all().includes(toolbar), false);
  for (const cls of ["teg-fit", "teg-zoom-in", "teg-zoom-out", "teg-zoom-reset", "teg-thread-filter", "teg-expand-all", "teg-collapse-all", "teg-overview"]) {
    assert.ok(toolbar.all().includes(h.find(cls)), `${cls} belongs to the pinned control bar`);
  }
  viewport.scrollTop = 10000; viewport.scrollLeft = 900;
  h.find("teg-expand-all").fire("click");
  assert.equal(h.find("teg-toolbar"), toolbar, "redraw does not replace controls or their focus");
  assert.ok(h.node("n79"));
  // Fit uses the actual scroll area after the toolbar has taken its own space.
  viewport.clientHeight = 220;
  h.find("teg-fit").fire("click");
  assert.ok(parseFloat(h.find("teg-stage").style.height) <= viewport.clientHeight);
  assert.equal(viewport.scrollTop, 0); assert.equal(viewport.scrollLeft, 0);
  cleanup(); assert.equal(h.listeners(), 0);
});

test("toolbar groups common controls while Overview and the thread filter live under More", () => {
  const h = fixture(), cleanup = graph.render(h.container, model([invocation("a")]));
  const toolbar = h.find("teg-toolbar"), more = h.find("teg-options");
  assert.equal(more.tagName, "details");
  assert.equal(more.childNodes.find(node => node.tagName === "summary").textContent, "More");
  assert.ok(more.contains(h.find("teg-overview")));
  assert.ok(more.contains(h.find("teg-thread-filter")));
  assert.ok(more.contains(h.find("teg-zoom-reset")));
  for (const cls of ["teg-fit", "teg-zoom-out", "teg-zoom-label", "teg-zoom-in", "teg-expand-all", "teg-collapse-all", "teg-fullscreen"]) {
    assert.ok(toolbar.contains(h.find(cls)), `${cls} remains in the toolbar`);
    assert.equal(more.contains(h.find(cls)), false, `${cls} does not require opening More`);
  }
  const zoomGroup = h.find("teg-fit").parentNode, actionGroup = h.find("teg-expand-all").parentNode;
  assert.notEqual(zoomGroup, toolbar, "zoom controls are grouped");
  assert.notEqual(actionGroup, toolbar, "branch controls are grouped");
  assert.notEqual(zoomGroup, actionGroup);
  assert.equal(h.find("teg-zoom-in").parentNode, zoomGroup);
  assert.equal(h.find("teg-collapse-all").parentNode, actionGroup);
  cleanup();
});

test("fullscreen uses a modal dialog without rebuilding cards or resetting graph state", () => {
  const h = fixture(570, 480, {dialogMode: "supported"});
  const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", "a")]));
  h.node("b").fire("click"); h.find("teg-zoom-out").fire("click");
  const wrapper = h.find("teg"), card = h.node("b"), details = h.find("teg-details"), toggle = h.find("teg-fullscreen");
  const canvas = h.find("teg-canvas"), scale = canvas.style.transform, viewport = h.find("teg-viewport");
  viewport.scrollTop = 30; viewport.scrollLeft = 45;
  toggle.focus(); toggle.fire("click");
  const dialog = h.find("teg-fullscreen-dialog");
  assert.equal(dialog.tagName, "dialog"); assert.equal(dialog.open, true);
  assert.equal(dialog.parentNode, h.container); assert.equal(wrapper.parentNode, dialog);
  assert.equal(h.find("teg"), wrapper); assert.equal(h.node("b"), card); assert.equal(h.find("teg-details"), details);
  assert.equal(toggle.textContent, "Exit fullscreen"); assert.equal(toggle.getAttribute("aria-pressed"), "true");
  assert.equal(canvas.style.transform, scale);
  assert.equal(viewport.scrollTop, 30); assert.equal(viewport.scrollLeft, 45);
  toggle.fire("click");
  assert.equal(h.find("teg-fullscreen-dialog"), null); assert.equal(wrapper.parentNode, h.container);
  assert.equal(toggle.textContent, "Fullscreen"); assert.equal(toggle.getAttribute("aria-pressed"), "false");
  assert.equal(h.container.ownerDocument.activeElement, toggle);
  assert.equal(h.node("b"), card); assert.equal(h.find("teg-details"), details);
  assert.equal(canvas.style.transform, scale);
  cleanup(); assert.equal(h.listeners(), 0);
});

test("Escape and native dialog close restore inline graph and fullscreen-button focus", () => {
  const h = fixture(570, 480, {dialogMode: "supported"});
  const cleanup = graph.render(h.container, model([invocation("a")]));
  const toggle = h.find("teg-fullscreen"), wrapper = h.find("teg");
  toggle.fire("click");
  let prevented = 0;
  h.node("a").focus();
  h.find("teg-fullscreen-dialog").fire("cancel", {preventDefault() { prevented++; }});
  assert.equal(prevented, 1); assert.equal(h.find("teg-fullscreen-dialog"), null);
  assert.equal(wrapper.parentNode, h.container); assert.equal(h.container.ownerDocument.activeElement, toggle);
  assert.equal(toggle.getAttribute("aria-pressed"), "false");
  toggle.fire("click"); h.node("a").focus(); h.find("teg-fullscreen-dialog").close();
  assert.equal(h.find("teg-fullscreen-dialog"), null);
  assert.equal(wrapper.parentNode, h.container); assert.equal(h.container.ownerDocument.activeElement, toggle);
  assert.equal(toggle.textContent, "Fullscreen"); assert.equal(toggle.getAttribute("aria-pressed"), "false");
  cleanup(); assert.equal(h.listeners(), 0);
});

test("Escape on expanded fullscreen evidence exits the dialog before closing the evidence", () => {
  const h = fixture(570, 480, {dialogMode: "supported"});
  const cleanup = graph.render(h.container, model([invocation("a")]));
  h.node("a").fire("click"); h.find("teg-zoom-out").fire("click");
  const card = h.node("a"), details = h.find("teg-details"), scale = h.find("teg-canvas").style.transform;
  const toggle = h.find("teg-fullscreen"); toggle.fire("click"); card.focus();
  let prevented = 0;
  card.fire("keydown", {key: "Escape", preventDefault() { prevented++; }});
  assert.equal(prevented, 1); assert.equal(h.find("teg-fullscreen-dialog"), null);
  assert.equal(h.find("teg-details"), details); assert.equal(h.node("a"), card);
  assert.equal(card.getAttribute("aria-expanded"), "true", "leaving fullscreen does not collapse evidence");
  assert.equal(h.find("teg-canvas").style.transform, scale);
  assert.equal(h.container.ownerDocument.activeElement, toggle);
  card.fire("keydown", {key: "Escape", preventDefault() { prevented++; }});
  assert.equal(prevented, 2); assert.equal(h.find("teg-details"), null, "inline Escape still closes evidence");
  assert.equal(h.node("a").getAttribute("aria-expanded"), "false");
  assert.equal(h.find("teg-canvas").style.transform, scale);
  assert.equal(h.container.ownerDocument.activeElement, h.node("a"));
  cleanup(); assert.equal(h.listeners(), 0);
});

test("unsupported or rejected fullscreen leaves a usable inline graph with an accessible status", () => {
  for (const dialogMode of [undefined, "throwing"]) {
    const h = fixture(570, 480, {dialogMode}), cleanup = graph.render(h.container, model([invocation("a")]));
    const wrapper = h.find("teg"), card = h.node("a"), toggle = h.find("teg-fullscreen");
    toggle.fire("click");
    assert.equal(h.find("teg-fullscreen-dialog"), null);
    assert.equal(wrapper.parentNode, h.container); assert.equal(h.node("a"), card);
    assert.equal(toggle.textContent, "Fullscreen"); assert.equal(toggle.getAttribute("aria-pressed"), "false");
    assert.equal(h.find("teg-toolbar-status").getAttribute("role"), "status");
    assert.match(h.find("teg-toolbar-status").textContent, /fullscreen/i);
    h.node("a").fire("click"); assert.ok(h.find("teg-details"), "inline evidence still works after failure");
    cleanup(); assert.equal(h.listeners(), 0);
  }
});

test("fullscreen cleanup removes dialog listeners and does not focus a discarded control", () => {
  const h = fixture(570, 480, {dialogMode: "supported"});
  const cleanup = graph.render(h.container, model([invocation("a")]));
  const toggle = h.find("teg-fullscreen");
  const inlineListeners = h.listeners();
  for (let i = 0; i < 3; i++) { toggle.fire("click"); toggle.fire("click"); }
  assert.equal(h.listeners(), inlineListeners, "repeated transitions do not accumulate listeners");
  toggle.fire("click"); const dialog = h.find("teg-fullscreen-dialog");
  assert.equal(dialog.listeners.cancel.length, 1); assert.equal(dialog.listeners.close.length, 1);
  assert.equal(h.listeners(), inlineListeners, "the same dialog listeners are reused");
  const outside = h.container.ownerDocument.createElement("button"); outside.focus();
  cleanup(); cleanup();
  assert.equal(h.container.childNodes.length, 0); assert.equal(h.listeners(), 0);
  assert.equal(h.container.ownerDocument.activeElement, outside);
  dialog.fire("cancel", {preventDefault() {}}); dialog.fire("close");
  assert.equal(h.container.childNodes.length, 0); assert.equal(h.container.ownerDocument.activeElement, outside);
});

test("filtering rebuilds lanes and repeated selection does not accumulate detached detail listeners", () => {
  const h = fixture(); const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", null, {laneId: "lane_2"})]), {onRevealEvidence() {}});
  h.node("a").fire("click");
  const startListeners = h.listeners();
  for (let i = 0; i < 50; i++) h.node(i % 2 ? "a" : "b").fire("click");
  assert.equal(h.listeners(), startListeners);
  const more = h.find("teg-options"); more.open = true;
  const filter = h.find("teg-thread-filter"); filter.value = "lane_2"; filter.fire("change");
  assert.equal(more.open, false, "selecting a thread closes More");
  assert.equal(filter.value, "lane_2");
  assert.equal(h.node("a"), undefined); assert.ok(h.node("b"));
  cleanup(); cleanup(); assert.equal(h.listeners(), 0);
});

test("upstream omission and incomplete durations remain visible without false completed timing", () => {
  const h = fixture(); const cleanup = graph.render(h.container, model([invocation("a", null, {complete: false, durationMs: 999, outcome: "incomplete"})],
    {partial: true, counts: {nodesOmitted: 91, eventsOmitted: 500}, warnings: [{code: "model_byte_limit", count: 1}]}));
  assert.match(h.find("teg-notes").textContent, /91 model nodes omitted upstream/);
  assert.match(h.find("teg-notes").textContent, /500 events not processed/);
  assert.match(h.find("teg-notes").textContent, /model_byte_limit/);
  assert.doesNotMatch(h.node("a").textContent, /999/);
  h.node("a").fire("click");
  assert.match(h.find("teg-details").textContent, /incomplete nesting evidence/); cleanup();
});

test("cleanup disconnects resize observers and removes every listener", () => {
  const observers = [], context = {ResizeObserver: class {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe(target) { this.target = target; }
    disconnect() { this.disconnected = true; }
  }};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/execution-graph.js"), "utf8"), context);
  const h = fixture(); const cleanup = context.TraceLensExecutionGraph.render(h.container, model([invocation("a")]));
  assert.equal(observers.length, 1); assert.equal(observers[0].target, h.find("teg-viewport"));
  cleanup(); observers[0].callback();
  assert.equal(observers[0].disconnected, true); assert.equal(h.listeners(), 0); assert.equal(h.container.childNodes.length, 0);
});

test("selected evidence is inside exactly one card, never below the diagram or nested inside a button", () => {
  const h = fixture(), calls = [];
  const input = model([invocation("a"), invocation("b", "a")]);
  const cleanup = graph.render(h.container, input, {onRevealEvidence: node => calls.push(node)});
  assert.equal(h.find("teg-details"), null, "overview starts compact");
  h.node("b").fire("click");
  const details = h.find("teg-details");
  assert.equal(details.parentNode.dataset.nodeId, "b");
  assert.equal(details.parentNode.classList.contains("teg-node-wrap"), true);
  assert.equal(details.parentNode.parentNode, h.find("teg-canvas"));
  assert.equal(h.node("b").getAttribute("aria-expanded"), "true");
  assert.equal(h.node("b").getAttribute("aria-controls"), details.id);
  let ancestor = h.find("teg-source").parentNode;
  while (ancestor) { assert.notEqual(ancestor.tagName, "button"); ancestor = ancestor.parentNode; }
  assert.match(details.textContent, /Thread name.*http-worker.*Class.*com.example.FeatureService.*Method.*b.*Outcome.*returned.*Inclusive duration.*2.5 ms inclusive/);
  assert.match(details.textContent, /Observed line coordinates.*first 70, last 42.*Relationship.*Observed instrumented nesting/);
  h.find("teg-source").fire("click"); assert.equal(calls[0], input.nodes[1]);
  h.node("a").fire("click");
  assert.equal(h.container.all().filter(node => node.classList.contains("teg-details")).length, 1);
  assert.equal(h.find("teg-details").parentNode.dataset.nodeId, "a");
  assert.equal(h.node("b").getAttribute("aria-expanded"), "false");
  h.node("a").fire("click");
  assert.equal(h.find("teg-details"), null);
  assert.equal(h.container.ownerDocument.activeElement, h.node("a"));
  cleanup(); assert.equal(h.listeners(), 0);
});

test("expanded-card geometry reserves space and preserves topology and lane boundaries", () => {
  const input = model([invocation("root"), invocation("child", "root"), marker("db", "child", "DB_ROUTE"), invocation("other", null, {laneId: "lane_2"})]);
  const before = JSON.stringify(input), compact = graph.layout(input);
  const expanded = graph.layout(input, {expandedId: "child", expandedWidth: 460, expandedHeight: 730});
  assert.deepEqual(expanded.edges.map(e => [e.from, e.to]), compact.edges.map(e => [e.from, e.to]));
  assertSeparatedCards(expanded);
  const child = expanded.nodes.find(n => n.id === "child"), db = expanded.nodes.find(n => n.id === "db");
  assert.equal(child.width, 460); assert.equal(child.height, 730);
  assert.ok(db.x > child.x + child.width);
  assert.ok(expanded.lanes[1].y > expanded.nodes[2].y + expanded.nodes[2].height);
  assert.ok(expanded.height > compact.height);
  assert.ok(expanded.edges.find(e => e.from === "child").path.includes(String(child.y + 66)), "edge anchors at method header, not evidence midpoint");
  assert.equal(JSON.stringify(input), before);
});

test("inline evidence text does not start panning and Escape closes it with focus retained", () => {
  const h = fixture(); const cleanup = graph.render(h.container, model([invocation("a")]), {onRevealEvidence() {}});
  h.node("a").fire("click");
  const viewport = h.find("teg-viewport"); viewport.scrollTop = 40;
  viewport.fire("pointerdown", {button: 0, pointerId: 3, clientX: 100, clientY: 100, target: h.find("teg-fields").childNodes[1]});
  viewport.fire("pointermove", {pointerId: 3, clientX: 10, clientY: 10});
  assert.equal(viewport.scrollTop, 40);
  h.node("a").fire("keydown", {key: "Escape", preventDefault() {}});
  assert.equal(h.find("teg-details"), null);
  assert.equal(h.container.ownerDocument.activeElement, h.node("a"));
  cleanup();
});

test("opening and closing evidence after Fit preserves the chosen zoom", () => {
  const h = fixture(350, 260);
  const cleanup = graph.render(h.container, model(Array.from({length: 80}, (_, i) => invocation(`n${i}`))));
  h.find("teg-fit").fire("click");
  const fitted = h.find("teg-canvas").style.transform, zoomLabel = h.find("teg-zoom-label").textContent;
  assert.notEqual(fitted, "scale(1)");
  h.node("n20").fire("click");
  assert.equal(h.find("teg-canvas").style.transform, fitted);
  assert.equal(h.find("teg-zoom-label").textContent, zoomLabel);
  const block = h.node("n20").parentNode;
  assert.ok(parseFloat(block.style.height) > 260);
  assert.ok(parseFloat(h.find("teg-canvas").style.height) > parseFloat(block.style.top) + parseFloat(block.style.height));
  h.node("n20").fire("click");
  assert.equal(h.find("teg-details"), null);
  assert.equal(h.find("teg-canvas").style.transform, fitted);
  cleanup();
});

test("manual zoom survives evidence, keyboard navigation and subtree expansion", () => {
  const h = fixture(350, 260);
  const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", "a"), invocation("c", "b")]));
  h.find("teg-zoom-out").fire("click");
  const chosen = h.find("teg-canvas").style.transform;
  assert.notEqual(chosen, "scale(1)");
  h.node("b").fire("click");
  assert.equal(h.find("teg-canvas").style.transform, chosen);
  h.node("b").fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(h.container.ownerDocument.activeElement, h.node("c"));
  assert.equal(h.find("teg-canvas").style.transform, chosen);
  h.node("c").fire("keydown", {key: "Escape", preventDefault() {}});
  assert.equal(h.find("teg-canvas").style.transform, chosen);
  const toggle = () => h.container.all().find(node => node.classList.contains("teg-collapse") && node.dataset.nodeId === "b");
  toggle().fire("click"); toggle().fire("click");
  assert.equal(h.find("teg-canvas").style.transform, chosen);
  cleanup();
});

test("subtree controls and Expand all do not refit an automatically fitted overview", () => {
  const h = fixture(350, 260);
  const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", "a"), invocation("c", "b")]));
  h.find("teg-fit").fire("click");
  const fitted = h.find("teg-canvas").style.transform;
  const toggle = () => h.container.all().find(node => node.classList.contains("teg-collapse") && node.dataset.nodeId === "b");
  toggle().fire("click");
  assert.ok(h.node("c")); assert.equal(h.find("teg-canvas").style.transform, fitted);
  toggle().fire("click");
  assert.equal(h.node("c"), undefined); assert.equal(h.find("teg-canvas").style.transform, fitted);
  h.find("teg-fit").fire("click");
  const refitted = h.find("teg-canvas").style.transform;
  h.find("teg-expand-all").fire("click");
  assert.ok(h.node("c")); assert.equal(h.find("teg-canvas").style.transform, refitted);
  cleanup();
});

test("filtering out the expanded node removes its evidence and detaches its source listener", () => {
  const h = fixture(), revealed = [];
  const cleanup = graph.render(h.container, model([invocation("a"), invocation("b", null, {laneId: "lane_2"})]), {onRevealEvidence: n => revealed.push(n)});
  h.node("a").fire("click"); const source = h.find("teg-source");
  h.find("teg-thread-filter").value = "lane_2"; h.find("teg-thread-filter").fire("change");
  assert.equal(h.find("teg-details"), null);
  source.fire("click"); assert.equal(revealed.length, 0);
  h.node("b").fire("click"); assert.match(h.find("teg-details").textContent, /queue-worker/);
  cleanup();
});

test("resize preserves focus on the inline source action and on a selected method", () => {
  const observers = [], context = {ResizeObserver: class {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {} disconnect() {}
  }};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/execution-graph.js"), "utf8"), context);
  const h = fixture();
  const cleanup = context.TraceLensExecutionGraph.render(h.container, model([invocation("a")]), {onRevealEvidence() {}, onOpenSource() {}});
  h.node("a").fire("click");
  h.find("teg-source").focus();
  observers[0].callback();
  assert.equal(h.container.ownerDocument.activeElement, h.find("teg-source"));
  h.find("teg-open-source").focus(); observers[0].callback();
  assert.equal(h.container.ownerDocument.activeElement, h.find("teg-open-source"));
  h.node("a").focus(); observers[0].callback();
  assert.equal(h.container.ownerDocument.activeElement, h.node("a"));
  cleanup(); assert.equal(h.listeners(), 0);
});

test("collapsing an ancestor removes expanded child evidence without expanding the parent evidence", () => {
  const h = fixture();
  const cleanup = graph.render(h.container, model([invocation("root"), invocation("child", "root")]));
  h.node("child").fire("click");
  assert.equal(h.find("teg-details").parentNode.dataset.nodeId, "child");
  h.container.all().find(node => node.classList.contains("teg-collapse") && node.dataset.nodeId === "root").fire("click");
  assert.equal(h.node("child"), undefined);
  assert.equal(h.find("teg-details"), null);
  assert.equal(h.container.ownerDocument.activeElement, h.node("root"));
  cleanup();
});
