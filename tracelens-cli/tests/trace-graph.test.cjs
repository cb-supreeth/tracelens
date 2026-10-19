const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const graph = require("../tracelens_cli/web/trace-graph.js");

const node = (id, kind = "segment", extra = {}) => ({ id, kind, service: id, ...extra });
const edge = (from, to, kind = "observed-call", id = `${from}:${to}`) => ({ id, from, to, kind });
const model = (nodes, edges = [], roots = []) => ({ schemaVersion: 1, kind: "request-flow", nodes, edges, roots });

test("browser global and Node exports share the buildless API", () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/trace-graph.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensGraph.render, "function");
  assert.equal(typeof context.TraceLensGraph.layout, "function");
});

test("empty and malformed captures produce bounded empty diagrams", () => {
  for (const input of [null, {}, model([]), model([null, {}, { id: "x", kind: "mystery" }])]) {
    const output = graph.layout(input);
    assert.equal(output.nodes.length, 0);
    assert.equal(output.width, 0);
    assert.equal(output.height, 0);
  }
});

test("only explicit edges establish connectivity, never matching service/span metadata", () => {
  const output = graph.layout(model([node("a", "segment", { parentSpanId: "shared" }), node("b", "segment", { parentSpanId: "shared" }), node("call", "http-call", { ownerId: "a", peer: "b" })]));
  assert.equal(output.edges.length, 0);
  assert.ok(output.nodes.every(item => item.rank === 0 && item.unlinked));
});

test("causal ranks ignore inconsistent timestamps and do not sum local duration", () => {
  const input = model([node("app", "segment", { startedAtMillis: 1000, durationMs: 90 }), node("call", "http-call", { startedAtMillis: 10, durationMs: 40 }), node("ems", "segment", { startedAtMillis: 0, durationMs: 20 })], [edge("app", "call", "outbound"), edge("call", "ems")], ["app"]);
  const before = JSON.stringify(input);
  const output = graph.layout(input);
  assert.deepEqual(output.nodes.map(item => [item.id, item.rank]), [["app", 0], ["call", 1], ["ems", 2]]);
  assert.equal(output.nodes[0].node.durationMs, 90);
  assert.equal(JSON.stringify(input), before);
  assert.ok(output.edges.every(item => item.path.startsWith("M ")));
});

test("parallel calls and disconnected evidence remain distinct and deterministic", () => {
  const nodes = [node("app"), node("c1", "http-call"), node("c2", "http-call"), node("ems1"), node("ems2"), node("orphan")];
  const edges = [edge("app", "c2", "outbound"), edge("app", "c1", "outbound"), edge("c1", "ems1"), edge("c2", "ems2")];
  const output = graph.layout(model(nodes, edges, ["app"]));
  assert.deepEqual(output, graph.layout(model([...nodes].reverse(), [...edges].reverse(), ["app"])));
  assert.equal(output.nodes.find(item => item.id === "orphan").rank, 0);
  assert.equal(output.nodes.find(item => item.id === "orphan").unlinked, true);
  assert.notEqual(output.nodes.find(item => item.id === "c1").y, output.nodes.find(item => item.id === "c2").y);
});

test("cycles and self links terminate and retain actual directed edges", () => {
  const output = graph.layout(model([node("a"), node("b"), node("c"), node("self")], [edge("a", "b"), edge("b", "a"), edge("b", "c"), edge("self", "self")]));
  assert.equal(output.cycleCount, 2);
  assert.equal(output.edges.length, 4);
  assert.equal(output.nodes.find(item => item.id === "a").rank, output.nodes.find(item => item.id === "b").rank);
  assert.equal(output.nodes.find(item => item.id === "c").rank, 1);
  assert.equal(output.edges.filter(item => item.cyclic).length, 3);
});

test("duplicate nodes, dangling edges and unknown edge kinds cannot invent links", () => {
  const output = graph.layout(model([node("a"), node("a"), node("b")], [edge("a", "missing"), edge("a", "b", "inferred"), edge("a", "b")]));
  assert.equal(output.invalidNodes, 1);
  assert.equal(output.invalidEdges, 2);
  assert.equal(output.edges.length, 1);
});

test("a 256-node fanout remains bounded with explicit omitted counts", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`));
  const edges = nodes.slice(1).map(item => edge(nodes[0].id, item.id));
  const output = graph.layout(model(nodes, edges, [nodes[0].id]));
  assert.equal(output.nodes.length, graph.limits.nodes);
  assert.equal(output.omittedNodes, 256 - graph.limits.nodes);
  assert.equal(output.totalNodes, 256);
  assert.ok(output.width < 9000 && output.height < 18000);
  assert.ok(output.edges.every(item => output.nodes.some(node => node.id === item.from) && output.nodes.some(node => node.id === item.to)));
});

test("very deep chains are capped by causal column count without fake reattachment", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`));
  const edges = nodes.slice(1).map((item, index) => edge(nodes[index].id, item.id));
  const output = graph.layout(model(nodes, edges, [nodes[0].id]));
  assert.equal(output.nodes.length, graph.limits.columns);
  assert.equal(output.edges.length, graph.limits.columns - 1);
  assert.ok(output.width < 9000);
  assert.equal(output.omittedNodes, 256 - graph.limits.columns);
});

test("parallel edges and dense graph edge counts are bounded", () => {
  const parallel = graph.layout(model([node("a"), node("b")], [edge("a", "b", "observed-call", "one"), edge("a", "b", "observed-call", "two")]));
  assert.notEqual(parallel.edges[0].path, parallel.edges[1].path);
  const nodes = Array.from({ length: 30 }, (_, index) => node(String(index)));
  const edges = nodes.flatMap(from => nodes.filter(to => from.id !== to.id).map(to => edge(from.id, to.id)));
  const output = graph.layout(model(nodes, edges));
  assert.equal(output.edges.length, graph.limits.edges);
  assert.equal(output.omittedEdges, edges.length - graph.limits.edges);
});

// A small DOM double keeps renderer interaction tests dependency-free. innerHTML
// deliberately throws: application strings must only become inert text nodes.
function documentFixture() {
  class Element {
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = [];
      this.style = {}; this.dataset = {}; this.attrs = {}; this.listeners = {};
      this.className = ""; this.scrollLeft = 0; this.scrollTop = 0;
      this.clientWidth = 800; this.clientHeight = 500;
      this.classList = { contains: name => this.className.split(" ").includes(name) };
    }
    set innerHTML(_) { throw new Error("Unsafe HTML interpolation"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...children) { children.forEach(child => { child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; this.text = ""; this.append(...children); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(name, value) { this.attrs[name] = String(value); if (name === "class") this.className = String(value); }
    getAttribute(name) { return this.attrs[name]; }
    contains(element) { return element === this || this.childNodes.some(child => child.contains(element)); }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    fire(name, event = {}) { (this.listeners[name] || []).forEach(handler => handler(event)); }
    focus() { this.ownerDocument.activeElement = this; this.fire("focus"); }
    querySelector(selector) {
      return this.all().find(child => selector === ".tl-graph-node[aria-pressed=true]"
        ? child.classList.contains("tl-graph-node") && child.getAttribute("aria-pressed") === "true"
        : child.classList.contains(selector.slice(1))) || null;
    }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
  }
  const document = { activeElement: null, createElement: tag => new Element(tag, document), createElementNS: (_, tag) => new Element(tag, document) };
  return document.createElement("div");
}

test("renderer uses inert text, shows upstream errors, and only explicit source action invokes callback", () => {
  const container = documentFixture();
  const malicious = '<img src=x onerror="alert(1)">';
  const app = node("app", "segment", { path: malicious, serviceInstanceId: "instance-1", segmentId: "segment-1" });
  const input = { ...model([app, node("call", "http-call", { downstreamState: "not-observed" })], [edge("app", "call", "outbound")], ["app"]), errors: [{ service: "downstream", error: "offline" }], partial: true };
  const selected = [];
  const cleanup = graph.render(container, input, { onSelect: value => selected.push(value) });
  assert.match(container.textContent, /Causal flow, not a shared time axis/);
  assert.match(container.textContent, /downstream: offline/);
  assert.ok(container.textContent.includes(malicious));
  assert.ok(!container.all().some(item => item.tagName === "img"));
  const button = container.all().find(item => item.dataset.nodeId === "app");
  button.fire("mouseenter"); button.focus(); button.fire("click");
  assert.equal(selected.length, 0);
  container.querySelector(".tl-graph-source").fire("click");
  assert.deepEqual(selected, [app]);
  const call = container.all().find(item => item.dataset.nodeId === "call");
  call.focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /Unavailable — no downstream segment is linked/);
  assert.equal(container.querySelector(".tl-graph-source"), null);
  cleanup();
  assert.equal(container.childNodes.length, 0);
});

test("arrow-key navigation is stable across rerenders and does not automatically open sources", () => {
  const container = documentFixture();
  const input = model([node("app"), node("call", "http-call"), node("ems")], [edge("app", "call", "outbound"), edge("call", "ems")], ["app"]);
  graph.render(container, input);
  const start = container.all().find(item => item.dataset.nodeId === "app");
  start.focus();
  let prevented = false;
  start.fire("keydown", { key: "ArrowRight", preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "call");
  assert.equal(container.querySelector(".tl-graph-node[aria-pressed=true]").dataset.nodeId, "call");
  graph.render(container, input);
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "call");
  assert.equal(container.querySelector(".tl-graph-source"), null);
  graph.render(container, model([]));
  assert.equal(container.querySelector(".tl-graph-node"), null);
  assert.match(container.textContent, /No observed request-flow nodes/);
});

test("diagram cap does not reinterpret observed downstream evidence as missing", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`, index === 23 ? "http-call" : "segment"));
  const edges = nodes.slice(1).map((item, index) => edge(nodes[index].id, item.id));
  const input = model(nodes, edges, [nodes[0].id]);
  const output = graph.layout(input);
  const terminalVisible = output.nodes.find(item => item.id === "n023");
  assert.equal(terminalVisible.downstreamObserved, true);
  const container = documentFixture();
  graph.render(container, input);
  assert.match(container.textContent, /Diagram limit: showing 24 of 256 nodes/);
  const call = container.all().find(item => item.dataset.nodeId === "n023");
  assert.match(call.textContent, /Downstream observed/);
});

test("zero-incoming roots with unresolved parents are not claimed to be request entries", () => {
  const container = documentFixture();
  graph.render(container, model([node("orphan", "segment", { parentSpanId: "missing-parent" })], [], ["orphan"]));
  const card = container.all().find(item => item.dataset.nodeId === "orphan");
  assert.match(card.textContent, /NO INCOMING LINK/);
  assert.doesNotMatch(card.textContent, /ENTRY/);
  assert.match(container.querySelector(".tl-graph-details").textContent, /this does not prove request entry/);
});

test("untraced HTTP dependency is named and stays visible without source drill-down", () => {
  const container = documentFixture();
  graph.render(container, model([node("app"), node("ems", "http-call", {peer: "127.0.0.1:8082", destinationName: "cb-ems", path: "/features", status: "COMPLETED", httpStatus: 200})], [edge("app", "ems", "outbound")], ["app"]));
  const card = container.all().find(item => item.dataset.nodeId === "ems");
  assert.match(card.textContent, /cb-ems/);
  assert.match(card.textContent, /Remote execution not observed/);
  card.focus();
  assert.equal(container.querySelector(".tl-graph-source"), null);
  assert.match(container.querySelector(".tl-graph-details").textContent, /127.0.0.1:8082/);
});

test("queue consume points into local segment; publish points out without inferred consumer", () => {
  const input = model([node("app"),
    node("consume", "dependency-call", {boundaryKind: "messaging", direction: "consume", system: "queueman", destination: "usage", evidenceKind: "client-boundary", status: "OBSERVED"}),
    node("publish", "dependency-call", {boundaryKind: "messaging", direction: "publish", system: "kafka", destination: "metrics", evidenceKind: "client-boundary", status: "SUBMITTED"})],
    [edge("consume", "app", "inbound"), edge("app", "publish", "outbound")], ["consume"]);
  const layout = graph.layout(input);
  assert.deepEqual(layout.nodes.map(n => [n.id, n.rank]), [["consume", 0], ["app", 1], ["publish", 2]]);
  const container = documentFixture();
  graph.render(container, input);
  assert.match(container.textContent, /QUEUE CONSUME/);
  assert.match(container.textContent, /QUEUE PUBLISH/);
  container.all().find(item => item.dataset.nodeId === "publish").focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /delivery and remote processing are not implied/);
  assert.equal(container.querySelector(".tl-graph-source"), null);
});

test("missing messaging direction and evidence never become publish or wrapper claims", () => {
  for (const direction of [null, undefined, "INVALID", "outbound"]) {
    const container = documentFixture();
    graph.render(container, model([node("boundary", "dependency-call", {boundaryKind: "messaging", direction,
      destination: "orders", evidenceKind: null, status: null})]));
    const card = container.all().find(item => item.dataset.nodeId === "boundary");
    assert.match(card.textContent, /MESSAGING BOUNDARY/);
    assert.doesNotMatch(card.textContent, /QUEUE PUBLISH|QUEUE CONSUME|EXTERNAL WRAPPER/);
    card.focus();
    const detail = container.querySelector(".tl-graph-details").textContent;
    assert.match(detail, /evidence kind unknown/);
    assert.doesNotMatch(detail, /Configured wrapper entry|delivery\/handler/);
  }
});

test("generic client boundaries are not presented as configured wrapper entries", () => {
  const container = documentFixture();
  graph.render(container, model([node("client", "dependency-call", {boundaryKind: "external", direction: "outbound",
    system: "gateway", evidenceKind: "client-boundary", status: "ATTEMPTED"})]));
  const card = container.all().find(item => item.dataset.nodeId === "client");
  assert.match(card.textContent, /EXTERNAL BOUNDARY/);
  assert.doesNotMatch(card.textContent, /EXTERNAL WRAPPER/);
  card.focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /Local client boundary/);
});

test("consume boundary alone does not claim a message was received", () => {
  const container = documentFixture();
  graph.render(container, model([node("consumer", "dependency-call", {boundaryKind: "messaging", direction: "consume",
    system: "sqs", operation: "receiveMessage", evidenceKind: "client-boundary", status: "OBSERVED"})]));
  container.all().find(item => item.dataset.nodeId === "consumer").focus();
  const details = container.querySelector(".tl-graph-details").textContent;
  assert.match(details, /message receipt, producer and processing completion are not inferred/);
  assert.doesNotMatch(details, /delivery\/handler/);
});
