"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const script = fs.readFileSync(require.resolve("../tracelens_cli/web/performance-ui.js"), "utf8");
const settle = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return {promise, resolve, reject}; };
const now = Date.now();
const request = (id, extra = {}) => ({key: id, distributedTraceId: id, method: "GET", path: `/api/${id}`, startedAtMillis: now - 1000, root: {traceId: id}, ...extra});
const node = {id: "jdbc-1", type: "EXTERNAL_CALL", attributes: {evidenceKind: "jdbc-execution"}};
const finding = (extra = {}) => ({id: "slow-1", kind: "slow-query", title: "Slow query", service: "app", system: "postgresql", destination: null,
  pattern: "SELECT * FROM orders WHERE id = ?", count: 1, totalDurationMs: 250, maxDurationMs: 250, confidence: "observed", nodes: [node], explanation: "Observed execution exceeded the threshold.", ...extra});
const analysis = (extra = {}) => ({findings: [finding()], queryCount: 3, segmentCount: 1, warnings: [], ...extra});

function fixture(options = {}, model = {analyze: () => analysis()}) {
  const created = [];
  class Element {
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = []; this.attrs = {}; this.listeners = {};
      this.className = ""; this.value = ""; this.hidden = false; this.disabled = false; this.text = "";
      this.classList = {contains: name => this.className.split(" ").includes(name)}; created.push(this);
    }
    set innerHTML(_) { assert.fail("Evidence must be rendered as text, never HTML"); }
    set textContent(value) { this.replaceChildren(); this.text = String(value); }
    get textContent() { return this.text + this.childNodes.map(child => child.textContent).join(" "); }
    append(...nodes) { nodes.forEach(child => { child.remove(); child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...nodes) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; this.text = ""; this.append(...nodes); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    getAttribute(key) { return this.attrs[key]; }
    addEventListener(key, action) { (this.listeners[key] ||= []).push(action); }
    removeEventListener(key, action) { this.listeners[key] = (this.listeners[key] || []).filter(value => value !== action); }
    async fire(key) { await Promise.all((this.listeners[key] || []).slice().map(action => action({target: this}))); }
    focus() { this.focused = true; this.ownerDocument.activeElement = this; }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
  }
  const document = {createElement: tag => new Element(tag, document)};
  const container = document.createElement("div");
  const forbidden = () => assert.fail("The panel cannot access network, storage, or timers");
  const context = {TraceLensPerformanceModel: model, Date, fetch: forbidden, XMLHttpRequest: forbidden,
    setInterval: forbidden, setTimeout: forbidden,
    localStorage: new Proxy({}, {get: forbidden}), indexedDB: new Proxy({}, {get: forbidden})};
  vm.runInNewContext(script, context);
  const ui = context.TraceLensPerformanceUI.create(container, options);
  const find = cls => container.all().find(item => item.classList.contains(`performance-${cls}`));
  return {container, ui, find, context, async click(cls) { await find(cls).fire("click"); },
    async change(cls, value, event = "change") { const input = find(cls); input.value = value; await input.fire(event); },
    listeners: () => created.reduce((n, item) => n + Object.values(item.listeners).reduce((a, values) => a + values.length, 0), 0)};
}

test("buildless module exposes only a read-only controller and performs no automatic loads", async () => {
  let calls = 0, analyses = 0;
  const h = fixture({loadRequest: () => { calls++; }, loadPin: () => { calls++; }}, {analyze: () => { analyses++; }});
  assert.deepEqual(Object.keys(h.ui), ["setSources", "setVisible", "dispose"]);
  h.ui.setSources({requests: [request("a")], pins: [{id: "saved", name: "Saved", storage: "viewer"}]});
  h.ui.setVisible(true); h.ui.setVisible(false); h.ui.setVisible(true);
  h.ui.setSources({requests: [request("b")], partial: true});
  await settle();
  assert.equal(calls, 0); assert.equal(analyses, 0);
  assert.equal(h.find("window").value, "30");
  assert.match(h.container.textContent, /no performance issues/);
  assert.doesNotMatch(script, /\b(?:fetch|XMLHttpRequest|localStorage|sessionStorage|indexedDB|setInterval|setTimeout|innerHTML)\b/);
  h.ui.dispose(); assert.equal(h.listeners(), 0);
});

test("explicit analysis loads newest matching requests sequentially with a hard ten-request cap", async () => {
  const calls = [], first = deferred(); let active = 0, maxActive = 0;
  const h = fixture({loadRequest: async value => {
    calls.push(value.key); active++; maxActive = Math.max(maxActive, active);
    if (calls.length === 1) await first.promise;
    active--; return {nodes: [], key: value.key};
  }});
  h.ui.setSources({requests: [request("outside", {path: "/other"}), request("old", {path: "/api/orders", startedAtMillis: now - 3600000}),
    ...Array.from({length: 12}, (_, i) => request(`q${i}`, {path: "/API/orders", startedAtMillis: now - i * 10000}))]});
  h.ui.setVisible(true); await h.change("path", "/api/orders", "input");
  const pending = h.click("analyze"); await settle();
  assert.deepEqual(calls, ["q0"]); assert.equal(h.find("analyze").disabled, true);
  first.resolve(); await pending;
  assert.deepEqual(calls, Array.from({length: 10}, (_, i) => `q${i}`)); assert.equal(maxActive, 1);
  assert.match(h.find("results").textContent, /10 scanned · 2 skipped by the 10-request cap · 0 failed · 12 matched/);
  assert.match(h.find("results").textContent, /Only the newest 10/);
  h.ui.dispose();
});

test("All retained includes old and undated requests and thresholds are passed exactly", async () => {
  const seen = [], configs = [];
  const h = fixture({loadRequest: async value => { seen.push(value.key); return {}; }}, {analyze: (graph, values) => { configs.push({...values}); return analysis(); }});
  h.ui.setSources({requests: [request("undated", {startedAtMillis: null}), request("old", {startedAtMillis: now - 3600000})]});
  h.ui.setVisible(true); await h.change("window", "0"); await h.click("analyze");
  assert.deepEqual(seen, ["old", "undated"]);
  assert.deepEqual(configs[0], {slowQueryMs: 100, repeatedQueryCount: 3, nPlusOneCount: 5, queryHeavyCount: 50});
  assert.match(h.find("provenance").textContent, /all retained/); h.ui.dispose();
});

test("invalid thresholds are rejected before any evidence is loaded", async () => {
  let calls = 0;
  const h = fixture({loadRequest: async () => { calls++; return {}; }});
  h.ui.setSources({requests: [request("a")]}); h.ui.setVisible(true);
  for (const [key, values] of [["slowQueryMs", ["", "-1", "0", "Infinity", "600001", "wat"]],
    ["repeatedQueryCount", ["1", "2.5", "1001"]], ["nPlusOneCount", ["1", "1001"]], ["queryHeavyCount", ["0", "1.5", "1001"]]]) {
    for (const value of values) {
      await h.change(key, value, "input"); await h.click("analyze");
      assert.equal(calls, 0); assert.equal(h.find(key).getAttribute("aria-invalid"), "true"); assert.equal(h.find(key).focused, true);
    }
    await h.change(key, key === "slowQueryMs" ? "100" : "5", "input");
  }
  await h.click("analyze"); assert.equal(calls, 1); h.ui.dispose();
});

test("leaving, cancelling, and changing settings discard stale responses and stop subsequent reads", async () => {
  for (const action of [h => h.ui.setVisible(false), h => h.click("cancel"), h => h.change("path", "new", "input")]) {
    const pending = deferred(), calls = [];
    const h = fixture({loadRequest: value => { calls.push(value.key); return pending.promise; }});
    h.ui.setSources({requests: [request("a"), request("b")]}); h.ui.setVisible(true);
    const run = h.click("analyze"); await settle(); await action(h); pending.resolve({}); await run;
    assert.equal(calls.length, 1); assert.equal(h.find("finding"), undefined); assert.match(h.find("results").textContent, /Inspect captured query evidence/);
    h.ui.setVisible(true); await settle(); assert.equal(calls.length, 1, "Re-entry must not resume a cancelled run"); h.ui.dispose();
  }
});

test("a cancelled rejected read cannot overwrite a newer completed run", async () => {
  const old = deferred(); let calls = 0;
  const h = fixture({loadRequest: () => ++calls === 1 ? old.promise : Promise.resolve({})});
  h.ui.setSources({requests: [request("a")]}); h.ui.setVisible(true);
  const stale = h.click("analyze"); await settle(); await h.click("cancel"); await h.click("analyze");
  const content = h.find("results").textContent, status = h.find("status").textContent;
  old.reject(new Error("STALE ERROR")); await stale;
  assert.equal(h.find("results").textContent, content); assert.equal(h.find("status").textContent, status); h.ui.dispose();
});

test("summary refresh preserves completed results, source selection, and in-flight frozen inputs", async () => {
  const wait = deferred(); let calls = 0;
  const h = fixture({loadRequest: async () => { calls++; if (calls === 2) await wait.promise; return {}; }});
  h.ui.setSources({requests: [request("first")]}); h.ui.setVisible(true); await h.click("analyze");
  const previous = h.find("results").textContent;
  h.ui.setSources({requests: [request("second")], partial: true, limited: true});
  assert.equal(h.find("results").textContent, previous); assert.equal(calls, 1);
  const run = h.click("analyze"); await settle();
  assert.equal(h.find("results").textContent, previous, "Previous findings stay visible while analysis runs");
  h.ui.setSources({requests: [request("third")]}); wait.resolve(); await run;
  assert.match(h.find("results").textContent, /GET \/api\/second/); assert.doesNotMatch(h.find("results").textContent, /GET \/api\/third/);
  assert.match(h.find("results").textContent, /retained request list is partial/); h.ui.dispose();
});

test("saved analysis ignores live filters and navigates only with the loaded saved graph and full pin", async () => {
  const summary = {id: "pin1", name: "Saved", storage: "viewer", request: request("summary")};
  const graph = {saved: true, nodes: [node]}, loaded = {...summary, request: request("stored"), graph, journey: {visualization: graph}};
  const calls = [], opened = [];
  const h = fixture({loadRequest: () => assert.fail("Saved capture cannot read live requests"), loadPin: async pin => { calls.push(pin); return loaded; },
    onOpenPin: pin => opened.push(pin), onOpenBoundary: value => opened.push(value)});
  h.ui.setSources({requests: [request("live")], pins: [summary]}); h.ui.setVisible(true);
  await h.change("path", "no-match", "input"); await h.change("source", "pin:viewer:pin1");
  assert.equal(h.find("path").disabled, true); assert.equal(h.find("window").disabled, true); assert.equal(calls.length, 0);
  await h.click("analyze"); assert.equal(calls[0], summary);
  await h.click("open-request"); await h.click("open-evidence"); await settle();
  assert.equal(opened[0], loaded); assert.equal(opened[1].pin, loaded); assert.equal(opened[1].graph, graph);
  assert.equal(opened[1].node, node); assert.equal(opened[1].request, loaded.request);
  assert.match(h.find("results").textContent, /Saved capture: Saved/); h.ui.dispose();
});

test("missing, failed, or unlisted saved evidence never falls back to live data", async () => {
  for (const load of [async () => ({}), async () => { throw new Error("Saved file expired"); }]) {
    let liveCalls = 0, savedCalls = 0;
    const h = fixture({loadRequest: () => { liveCalls++; }, loadPin: async () => { savedCalls++; return load(); }});
    const pin = {id: "gone", storage: "browser", name: "Saved"};
    h.ui.setSources({requests: [request("a")], pins: [pin]}); h.ui.setVisible(true); await h.change("source", "pin:browser:gone");
    await h.click("analyze"); assert.equal(liveCalls, 0); assert.equal(savedCalls, 1);
    assert.match(h.find("results").textContent, /0 scanned.*1 failed/); assert.match(h.find("results").textContent, /No captured evidence analyzed/);
    h.ui.setSources({requests: [request("a")], pins: []}); assert.equal(h.find("source").value, "pin:browser:gone");
    await h.click("analyze"); assert.equal(savedCalls, 1); assert.equal(liveCalls, 0); assert.match(h.find("status").textContent, /no live evidence was loaded/); h.ui.dispose();
  }
});

test("partial responses and per-request failures remain explicit without losing successful evidence", async () => {
  const h = fixture({loadRequest: async value => { if (value.key === "bad") throw new Error("Expired <script>bad</script>"); return {partial: true}; }},
    {analyze: () => analysis({warnings: ["Capture reached its query limit."]})});
  h.ui.setSources({requests: [request("bad"), request("good")], partial: true, limited: true}); h.ui.setVisible(true); await h.click("analyze");
  const rendered = h.find("results").textContent;
  assert.match(rendered, /1 scanned · 0 skipped by the 10-request cap · 1 failed/);
  assert.match(rendered, /request list is partial/); assert.match(rendered, /request list is limited/);
  assert.match(rendered, /request graph is partial/); assert.match(rendered, /query limit/); assert.match(rendered, /Expired <script>bad<\/script>/);
  assert.match(rendered, /not request wall-clock latency/); h.ui.dispose();
});

test("untrusted finding text stays text, category changes are local, and evidence retains original references", async () => {
  const graph = {}, req = request("unsafe", {path: "/<img onerror=bad>"}), opened = []; let reads = 0;
  const malicious = "<svg onload=bad>";
  const h = fixture({loadRequest: async () => { reads++; return graph; }, onOpenRequest: value => opened.push(value), onOpenBoundary: value => opened.push(value)},
    {analyze: () => analysis({findings: [finding({title: malicious, pattern: malicious, explanation: malicious}),
      finding({id: "heuristic", kind: "suspected-n-plus-one", confidence: "heuristic", title: "Suspected N+1", totalDurationMs: 100})]})});
  h.ui.setSources({requests: [req]}); h.ui.setVisible(true); await h.click("analyze");
  assert.match(h.find("results").textContent, /<svg onload=bad>/);
  assert.equal(h.container.all().some(value => ["svg", "img", "script"].includes(value.tagName)), false);
  await h.click("open-request"); await h.click("open-evidence"); await settle();
  assert.equal(opened[0], req); assert.equal(opened[1].request, req); assert.equal(opened[1].graph, graph); assert.equal(opened[1].node, node);
  await h.change("category", "suspected-n-plus-one");
  assert.equal(reads, 1); assert.equal(h.container.all().filter(value => value.classList.contains("performance-finding")).length, 1);
  assert.match(h.find("finding").textContent, /Heuristic · investigate/); assert.match(h.find("finding").textContent, /not proof of N\+1/); h.ui.dispose();
});

test("ranked finding rendering is bounded and unknown timing remains unknown", async () => {
  const h = fixture({loadRequest: async () => ({})}, {analyze: () => analysis({findings: Array.from({length: 205}, (_, i) =>
    finding({id: `f${i}`, title: `Finding ${i}`, totalDurationMs: i === 204 ? null : i, maxDurationMs: null}))})});
  h.ui.setSources({requests: [request("a")]}); h.ui.setVisible(true); await h.click("analyze");
  assert.equal(h.container.all().filter(value => value.classList.contains("performance-finding")).length, 200);
  assert.match(h.find("finding").textContent, /Finding 203/); assert.match(h.find("finding").textContent, /Unknown/);
  assert.match(h.find("results").textContent, /Display capped at the highest-ranked 200/); h.ui.dispose();
});

test("keyboard category filtering restores focus without reloading captured evidence", async () => {
  let reads = 0;
  const h = fixture({loadRequest: async () => { reads++; return {}; }}, {analyze: () => analysis({findings: [finding(),
    finding({kind: "repeated-query", title: "Repeated query"})]})});
  h.ui.setSources({requests: [request("a")]}); h.ui.setVisible(true); await h.click("analyze");
  const previous = h.find("category"); previous.focus();
  await h.change("category", "repeated-query");
  assert.notEqual(h.find("category"), previous);
  assert.equal(h.container.ownerDocument.activeElement, h.find("category"));
  assert.equal(h.find("category").value, "repeated-query");
  assert.equal(reads, 1); assert.match(h.find("finding").textContent, /Repeated query/);
  h.ui.dispose(); assert.equal(h.listeners(), 0);
});

test("repeated-query occurrence picker navigates exact retained saved evidence and stays bounded", async () => {
  const nodes = Array.from({length: 205}, (_, index) => ({id: `q${index}`, eventSeq: index + 7, thread: "worker", durationMs: index}));
  const graph = {nodes}, pin = {id: "saved", storage: "viewer", graph}, opened = [];
  const h = fixture({loadPin: async () => pin, onOpenBoundary: value => opened.push(value)},
    {analyze: () => analysis({findings: [finding({kind: "repeated-query", nodes, count: 205})]})});
  h.ui.setSources({pins: [pin]}); h.ui.setVisible(true);
  await h.change("source", "pin:viewer:saved"); await h.click("analyze");
  const picker = h.find("occurrence");
  assert.equal(picker.childNodes.length, 200); assert.match(picker.childNodes[1].textContent, /Occurrence 2 · event 8 · worker · 1 ms/);
  assert.match(h.find("finding").textContent, /200 of 205 retained query nodes.*limited to the first 200/);
  await h.change("occurrence", "199"); await h.click("open-evidence"); await settle();
  assert.equal(opened[0].node, nodes[199]); assert.equal(opened[0].pin, pin); assert.equal(opened[0].graph, graph);
  await h.change("occurrence", "200"); await h.click("open-evidence"); await settle(); assert.equal(opened.length, 1);
  h.ui.dispose(); assert.equal(h.listeners(), 0);
});

test("empty analysis explains opt-in, retained evidence, and absence-of-findings limits", async () => {
  const h = fixture({loadRequest: async () => ({})}, {analyze: () => analysis({findings: [], queryCount: 0})});
  h.ui.setVisible(true); await h.click("analyze"); assert.match(h.find("results").textContent, /No retained requests match/);
  h.ui.setSources({requests: [request("a")]}); await h.click("analyze");
  assert.match(h.find("results").textContent, /No findings at these thresholds/); assert.match(h.find("results").textContent, /No findings does not mean no performance issues/);
  assert.match(h.find("results").textContent, /explicit JDBC opt-in/); h.ui.dispose();
});

test("dispose removes handlers and ignores pending work without starting additional reads", async () => {
  const pending = deferred(); let reads = 0;
  const h = fixture({loadRequest: () => { reads++; return pending.promise; }});
  h.ui.setSources({requests: [request("a"), request("b")]}); h.ui.setVisible(true);
  const run = h.click("analyze"); await settle(); h.ui.dispose();
  pending.resolve({}); await run;
  assert.equal(h.listeners(), 0); assert.equal(reads, 1); assert.equal(h.container.childNodes.length, 0);
  h.ui.setSources({requests: [request("c")]}); h.ui.setVisible(true); assert.equal(reads, 1);
});

test("the real model renders retained raw JDBC evidence and callbacks keep the original node", async () => {
  const realModel = require("../tracelens_cli/web/performance-model.js");
  const segment = {id: "s1", kind: "segment", service: "app", serviceInstanceId: "instance", segmentId: "seg", segmentKey: "key"};
  const raw = {id: "query", kind: "dependency-call", ownerId: "s1", service: "app", serviceInstanceId: "instance", segmentId: "seg", segmentKey: "key",
    boundaryKind: "database", system: "postgresql", direction: "outbound", evidenceKind: "jdbc-execution", operation: "executeQuery", status: "OBSERVED",
    sql: "select * from orders where id = 'sensitive-literal'", sqlSource: "prepared", sqlTruncated: false, durationMs: 250, eventSeq: 1, thread: "worker", callKey: "call"};
  const graph = {schemaVersion: 1, kind: "request-flow", nodes: [segment, raw], edges: [{id: "edge", from: "s1", to: "query", kind: "outbound"}], roots: ["s1"], coverage: {complete: false}};
  const opened = [], h = fixture({loadRequest: async () => graph, onOpenBoundary: value => opened.push(value)}, realModel);
  h.ui.setSources({requests: [request("orders")]}); h.ui.setVisible(true); await h.click("analyze");
  assert.match(h.find("finding").textContent, /Slow JDBC execution/);
  assert.doesNotMatch(h.find("results").textContent, /sensitive-literal/);
  assert.match(h.find("results").textContent, /Capture coverage is partial/);
  await h.click("open-evidence"); await settle(); assert.equal(opened[0].node, raw); assert.equal(opened[0].graph, graph); h.ui.dispose();
});

test("unavailable analysis and evidence-navigation errors are reported safely", async () => {
  let calls = 0;
  const h = fixture({loadRequest: async () => { calls++; return {}; }, onOpenRequest: async () => { throw new Error("expired <query>"); }}, null);
  h.ui.setSources({requests: [request("a")]}); h.ui.setVisible(true); await h.click("analyze");
  assert.equal(calls, 0); assert.match(h.find("status").textContent, /Performance analysis is unavailable/);
  h.context.TraceLensPerformanceModel = {analyze: () => analysis()}; await h.click("analyze");
  await h.click("open-request"); await settle(); assert.match(h.find("status").textContent, /Could not open evidence: expired <query>/); h.ui.dispose();
});
