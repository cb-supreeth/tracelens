"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const requests = require("../tracelens_cli/web/request-model.js");
const guided = require("../tracelens_cli/web/guided-investigation.js");
const clone = value => JSON.parse(JSON.stringify(value));
const settle = async () => { for (let i = 0; i < 4; i++) await Promise.resolve(); };
const labels = ["Check capture for this API", "Capture my next reproduction"];

function harness({allowed = true, callbacks = true} = {}) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.value = ""; this.className = ""; this.childNodes = []; this.listeners = {}; this.attrs = {};
      const names = () => new Set(this.className.split(" ").filter(Boolean));
      const change = (name, present) => { const found = names(); if (present) found.add(name); else found.delete(name); this.className = [...found].join(" "); };
      this.classList = {contains: name => names().has(name), add: name => change(name, true), remove: name => change(name, false),
        toggle: (name, force) => change(name, force === undefined ? !names().has(name) : force)};
    }
    set innerHTML(_) { throw Error("Recovery suggestions must remain inert text"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...nodes) { this.childNodes.push(...nodes); }
    appendChild(node) { this.append(node); return node; }
    replaceChildren(...nodes) { this.text = ""; this.childNodes = nodes; }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    addEventListener(name, listener) { (this.listeners[name] ||= []).push(listener); }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
  }
  const nodes = new Map(), drafts = [], calls = [], timers = [];
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  for (const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const node = new Element(match[1]); node.type = /\btype="([^"]+)"/.exec(match[2])?.[1] || "";
    nodes.set(match[3], node);
  }
  let canInvestigate = allowed;
  const context = {window: {TraceLensRequests: requests, TraceLensGuidedInvestigation: guided,
    TraceLensViewerStore: {create: () => ({})}},
    document: {getElementById: id => { assert.ok(nodes.has(id), "Unknown element: " + id); return nodes.get(id); }, createElement: tag => new Element(tag)},
    Option: function (label, value) { const option = new Element("option"); option.textContent = label; option.value = value; return option; },
    URLSearchParams, Date, setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {},
    confirm: () => assert.fail("Recovery must not mutate anything")};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-ui.js"), "utf8"), context);
  const ui = new context.window.TraceLensRequestUI({api: (...args) => { calls.push(args); return Promise.reject(Error("Unexpected I/O")); },
    ...(callbacks ? {canInvestigate: () => canInvestigate, onInvestigate: draft => drafts.push(clone(draft))} : {})});
  ui.viewerMode = "federated"; ui.setServices(["app", "ems"]);
  const empty = nodes.get("empty");
  return {ui, empty, drafts, calls, timers, allow(value) { canInvestigate = value; },
    actions: () => empty.all().filter(node => node.tagName === "BUTTON" && labels.includes(node.textContent)),
    render(filters = {}) { Object.assign(ui.filters, filters); ui.render(); }};
}

test("empty HTTP requests offer review-only recovery with exact filters and unconfirmed suggestions", async () => {
  const h = harness();
  h.render({path: "/api/order", user: "Ali", service: "ems", method: "POST", site: "42", query: "label",
    sinceMinutes: "5", minDuration: "100", outcome: "error"});
  assert.deepEqual(h.actions().map(node => node.textContent), labels);
  assert.match(h.empty.textContent, /neither starts a check or watch/);
  assert.equal(h.calls.length, 0); assert.equal(h.timers.length, 0); assert.equal(h.drafts.length, 0);
  for (const action of h.actions()) { action.onclick(); await settle(); }
  assert.deepEqual(h.drafts, ["check", "watch"].map(intent => ({source: "empty-requests", intent,
    filters: {service: "ems", method: "POST", site: "42"}, pathSuggestion: "/api/order", userSuggestion: "Ali"})));
  assert.equal(h.calls.length, 0); assert.equal(h.timers.length, 0);
});

test("recovery does not appear for jobs, saved captures, errors, offline guards or non-HTTP channels", () => {
  for (const mode of ["jobs", "pins", "errors", "performance"]) {
    const h = harness(); h.ui.mode = mode; h.render(); assert.equal(h.actions().length, 0, mode);
    assert.equal(h.calls.length, 0); assert.equal(h.timers.length, 0);
  }
  for (const channel of ["SQS", "OTHER"]) {
    const h = harness(); h.render({channel});
    assert.equal(h.actions().length, 0, channel);
  }
  for (const options of [{allowed: false}, {callbacks: false}]) {
    const h = harness(options); h.render(); assert.equal(h.actions().length, 0);
  }
  const allChannels = harness(); allChannels.render({channel: ""}); assert.equal(allChannels.actions().length, 2);
});

test("stale recovery buttons cannot reopen live actions after moving offline or to jobs", async () => {
  for (const offline of [true, false]) {
    const h = harness(); h.render(); const action = h.actions()[0];
    action.onclick();
    if (offline) h.allow(false); else h.ui.mode = "jobs";
    await settle(); assert.equal(h.drafts.length, 0); assert.equal(h.calls.length, 0);
  }
});

test("invalid duration filters do not suggest capture as a remedy", () => {
  const h = harness(); h.render({minDuration: "100", maxDuration: "10"});
  assert.equal(h.actions().length, 0); assert.match(h.empty.textContent, /valid duration range/);
});

test("a nonempty live request list does not show empty-state recovery actions", () => {
  const h = harness(); h.ui.setTraces({traces: [{traceId: "1".repeat(32), segmentId: "root", service: "app", serviceInstanceId: "app-1",
    status: "COMPLETED", entryChannel: "HTTP", httpMethod: "GET", path: "/api/orders", startedAtMillis: Date.now(), durationMs: 2, httpStatus: 200}]});
  assert.equal(h.actions().length, 0); assert.equal(h.empty.classList.contains("hidden"), true);
  assert.equal(h.calls.length, 0); assert.equal(h.timers.length, 0);
});

test("completed empty keyword results offer recovery without carrying request-data search terms", async () => {
  const h = harness();
  h.ui.keywordSearch = {state: {status: "ready", keyword: "private-body-term", counts: {withData: 0, requested: 0}},
    filter: () => [], isStale: () => false};
  h.render({path: "/api/orders"}); assert.equal(h.actions().length, 2);
  h.actions()[0].onclick(); await settle();
  assert.doesNotMatch(JSON.stringify(h.drafts), /private-body-term/);
  assert.equal(h.calls.length, 0); assert.equal(h.timers.length, 0);
  h.render({minDuration: "100", maxDuration: "10"}); assert.equal(h.actions().length, 0);
  h.render({minDuration: "", maxDuration: ""});
  h.ui.keywordSearch.state.status = "loading"; h.ui.render(); assert.equal(h.actions().length, 0);
});
