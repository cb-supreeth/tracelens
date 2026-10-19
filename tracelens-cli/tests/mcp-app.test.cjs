"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const bridge = fs.readFileSync(require.resolve("../tracelens_cli/web/mcp-app.js"), "utf8");
const VERSION = "2026-01-26";
const HOST = "https://host.example";
const model = (extra = {}) => ({schemaVersion: 1, kind: "request-flow", traceId: "trace-1",
  nodes: [{id: "app", kind: "segment"}, {id: "call", kind: "http-call"}, {id: "ems", kind: "segment"}],
  edges: [{id: "out", from: "app", to: "call", kind: "outbound"},
    {id: "linked", from: "call", to: "ems", kind: "observed-call"}],
  roots: ["app"], warnings: [], partial: false, ...extra});

/** Exercise the actual postMessage bridge without a browser, network or real timers. */
function harness() {
  const sent = [], renders = [], listeners = new Map(), timers = new Map(), frames = new Map();
  let nextId = 0, cleanups = 0;
  const parent = {postMessage(message, origin) { sent.push({message, origin}); }};
  const status = {textContent: ""};
  const container = {replaceChildren() {}};
  const document = {body: {scrollHeight: 400}, documentElement: {clientWidth: 700, dataset: {}},
    getElementById(id) {
      assert.ok(["app-status", "request-graph"].includes(id));
      return id === "app-status" ? status : container;
    }};
  const window = {parent,
    addEventListener(name, handler) { listeners.set(name, handler); },
    removeEventListener(name) { listeners.delete(name); },
    TraceLensGraph: {render(target, graph, options) {
      assert.equal(target, container);
      renders.push({graph, options});
      return () => { cleanups++; };
    }}};
  vm.runInNewContext(bridge, {window, document,
    setTimeout(callback) { const id = ++nextId; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); },
    requestAnimationFrame(callback) { const id = ++nextId; frames.set(id, callback); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
  });
  const emit = (data, source = parent, origin = HOST) => listeners.get("message")?.({data, source, origin});
  const init = (extra = {}) => emit({jsonrpc: "2.0", id: "tracelens-ui-initialize",
    result: {protocolVersion: VERSION, hostInfo: {name: "fixture", version: "1"}, hostCapabilities: {}, ...extra}});
  const result = (graph, source, origin) => emit({jsonrpc: "2.0", method: "ui/notifications/tool-result",
    params: {structuredContent: graph}}, source, origin);
  return {sent, renders, listeners, timers, frames, status, emit, init, result, cleanups: () => cleanups};
}

test("MCP Apps retains HTTP graph compatibility and read-only rendering after initialization", () => {
  const h = harness(), graph = model();
  assert.equal(h.sent[0].message.method, "ui/initialize");
  assert.equal(h.sent[0].message.params.protocolVersion, VERSION);
  assert.equal(h.sent[0].origin, "*");
  h.result(graph);
  assert.equal(h.renders.length, 0, "Tool data cannot render before the host handshake");
  h.init();
  assert.equal(h.sent[1].message.method, "ui/notifications/initialized");
  assert.equal(h.sent[1].origin, HOST);
  assert.equal(h.timers.size, 0);
  h.result(graph);
  assert.equal(h.renders[0].graph, graph);
  assert.deepEqual(Object.keys(h.renders[0].options), [], "Embedded graphs cannot invoke source actions");
  assert.match(h.status.textContent, /Retained request evidence/);
});

test("MCP Apps renders canonical dependency nodes and inbound consume edges unchanged", () => {
  const h = harness(), http = model();
  const graph = model({nodes: [...http.nodes,
    {id: "consume", kind: "dependency-call", boundaryKind: "messaging", direction: "consume", system: "queueman"},
    {id: "publish", kind: "dependency-call", boundaryKind: "messaging", direction: "publish", system: "kafka"}],
  edges: [...http.edges, {id: "consume-in", from: "consume", to: "app", kind: "inbound"},
    {id: "publish-out", from: "ems", to: "publish", kind: "outbound"}], roots: ["consume"], partial: true});
  h.init(); h.result(graph);
  assert.equal(h.renders.length, 1);
  assert.equal(h.renders[0].graph, graph, "The bridge must not rewrite canonical evidence");
  assert.match(h.status.textContent, /Partial retained request evidence/);
});

test("MCP Apps still rejects unknown kinds, malformed identities and dangling graph references", () => {
  const invalid = [
    ["unknown node", model({nodes: [{id: "app", kind: "future-node"}]})],
    ["unknown edge", model({edges: [{id: "bad", from: "app", to: "ems", kind: "future-edge"}]})],
    ["missing source", model({edges: [{id: "bad", from: "missing", to: "app", kind: "inbound"}]})],
    ["missing target", model({edges: [{id: "bad", from: "app", to: "missing", kind: "outbound"}]})],
    ["missing root", model({roots: ["missing"]})],
    ["duplicate nodes", model({nodes: [{id: "app", kind: "segment"}, {id: "app", kind: "dependency-call"}]})],
    ["empty node ID", model({nodes: [{id: "", kind: "segment"}]})],
    ["oversized node ID", model({nodes: [{id: "x".repeat(257), kind: "segment"}]})],
    ["invalid edge ID", model({edges: [{id: null, from: "app", to: "ems", kind: "inbound"}]})],
    ["invalid trace ID", model({traceId: ""})],
    ["unknown schema", model({schemaVersion: 2})],
    ["unknown model kind", model({kind: "future-flow"})],
    ["non-list nodes", model({nodes: {}})],
    ["non-record node", model({nodes: [null]})],
    ["non-record edge", model({edges: [null]})],
  ];
  for (const [label, graph] of invalid) {
    const h = harness(); h.init(); h.result(model()); h.result(graph);
    assert.equal(h.renders.length, 1, label);
    assert.equal(h.cleanups(), 1, `${label}: clear previous evidence without substituting data`);
    assert.match(h.status.textContent, /supported request graph/, label);
  }
});

test("MCP Apps preserves collection and serialized-size limits", () => {
  const invalid = [
    ["node count", model({nodes: Array.from({length: 6001}, (_, n) => ({id: `n${n}`, kind: "segment"}))})],
    ["edge count", model({edges: Array.from({length: 12001}, (_, n) => ({id: `e${n}`, from: "app", to: "ems", kind: "inbound"}))})],
    ["root count", model({roots: Array(6001).fill("app")})],
    ["warning count", model({warnings: Array(2001).fill("warning")})],
    ["serialized size", model({note: "x".repeat(4 * 1024 * 1024)})],
    ["oversized trace ID", model({traceId: "x".repeat(257)})],
  ];
  const circular = model(); circular.note = circular;
  invalid.push(["non-serializable model", circular]);
  for (const [label, graph] of invalid) {
    const h = harness(); h.init(); h.result(graph);
    assert.equal(h.renders.length, 0, label);
    assert.match(h.status.textContent, /supported request graph/, label);
  }
});

test("MCP Apps ignores untrusted host messages and preserves protocol handshake validation", () => {
  const h = harness(); h.init();
  h.result(model(), {}, HOST);
  h.result(model(), undefined, "https://other.example");
  h.emit({jsonrpc: "1.0", method: "ui/notifications/tool-result", params: {structuredContent: model()}});
  h.emit(null); h.emit([]); h.emit({jsonrpc: "2.0", method: 4});
  assert.equal(h.renders.length, 0);
  h.emit({jsonrpc: "2.0", id: 7, method: "ui/notifications/tool-result", params: {structuredContent: model()}});
  assert.equal(h.renders.length, 0, "Tool results must be notifications, not requests");
  assert.equal(h.sent.at(-1).message.error.code, -32601);
  for (const extra of [{protocolVersion: "future"}, {hostCapabilities: []}, {hostInfo: null}]) {
    const invalid = harness(); invalid.init(extra); invalid.result(model());
    assert.equal(invalid.renders.length, 0);
    assert.match(invalid.status.textContent, /does not support/);
    assert.equal(invalid.listeners.size, 0);
  }
});

test("MCP Apps clears graphs on malformed tool results, cancellation and teardown", () => {
  const h = harness(); h.init(); h.result(model());
  h.emit({jsonrpc: "2.0", method: "ui/notifications/tool-result", params: {isError: true}});
  assert.equal(h.cleanups(), 1);
  assert.match(h.status.textContent, /lookup failed/);
  h.emit({jsonrpc: "2.0", method: "ui/notifications/tool-result", params: null});
  assert.match(h.status.textContent, /lookup failed/);
  h.result(model());
  h.emit({jsonrpc: "2.0", method: "ui/notifications/tool-cancelled"});
  assert.equal(h.cleanups(), 2);
  assert.match(h.status.textContent, /cancelled/);
  h.result(model());
  h.emit({jsonrpc: "2.0", id: 8, method: "ui/resource-teardown"});
  assert.equal(h.cleanups(), 3);
  assert.equal(h.listeners.size, 0);
  assert.equal(h.frames.size, 0);
  assert.equal(h.sent.at(-1).message.id, 8);
  h.result(model());
  assert.equal(h.renders.length, 3, "A closed view cannot render more tool data");
  assert.ok(!h.sent.some(({message}) => ["tools/call", "resources/read", "ui/open-link", "ui/message"].includes(message.method)));
});
