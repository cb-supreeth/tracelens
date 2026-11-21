"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const flow = require("../tracelens_cli/web/compare-flow.js");
const tools = require("../tracelens_cli/web/capture-tools.js");

function fixture() {
  const created = [];
  class Element {
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = []; this.attrs = {}; this.listeners = {};
      this.style = {}; this.className = ""; this.value = ""; this.open = false; this.hidden = false; this.disabled = false;
      this.classList = {contains: name => this.className.split(" ").includes(name)}; created.push(this);
    }
    set innerHTML(_) { assert.fail("Untrusted evidence must never be interpolated as HTML"); }
    set textContent(value) { this.text = String(value); this.replaceChildren(); }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(" "); }
    append(...children) { children.forEach(child => { child.remove(); child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; if (children.length) this.text = ""; this.append(...children); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    getAttribute(key) { return this.attrs[key]; }
    addEventListener(key, fn) { (this.listeners[key] ||= []).push(fn); }
    removeEventListener(key, fn) { this.listeners[key] = (this.listeners[key] || []).filter(value => value !== fn); }
    fire(key) { (this.listeners[key] || []).slice().forEach(fn => fn({target: this, preventDefault() {}})); }
    focus() {}
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelector(selector) { return this.all().find(child => child.classList.contains(selector.slice(1))) || null; }
  }
  const document = {createElement: tag => new Element(tag, document)};
  const container = document.createElement("div");
  return {container, find: cls => container.querySelector(`.${cls}`),
    findAll: cls => container.all().filter(child => child.classList.contains(cls)),
    listeners: () => created.reduce((sum, item) => sum + Object.values(item.listeners).reduce((n, values) => n + values.length, 0), 0)};
}
const call = (service, destination, disposition, before, after, extra = {}) => ({category: "dependencies", service, boundaryKind: "http",
  operation: "GET", destination, path: "/entitlements", disposition, before: {count: before}, after: {count: after}, ...extra});
function rows() {
  return [
    {category: "outcomes", service: "cb-app", method: "POST", path: "/subscriptions", disposition: "changed",
      before: [{httpStatus: 404, status: "COMPLETED"}], after: [{httpStatus: 200, status: "COMPLETED"}]},
    {category: "outcomes", service: "cb-ems", method: "GET", path: "/entitlements", disposition: "before_only",
      before: [{httpStatus: 404}], after: []},
    call("cb-app", "ems.local:8082", "before_only", 1, 0, {destinationName: "cb-ems", destinationNameSource: "configured"}),
    call("cb-app", "catalog.local", "unchanged", 1, 1),
    call("cb-app", "plans.local", "unchanged", 2, 2),
    {category: "methods", service: "cb-app", className: "example.FeatureGuard", methodName: "check", disposition: "descendants_changed",
      beforeCount: 1, afterCount: 1, nestedOmitted: 3, nestedChanges: [
        {service: "cb-app", className: "example.EntitlementClient", methodName: "fetch", disposition: "before_only", beforeCount: 1, afterCount: 0},
        {service: "cb-app", className: "example.Ignored", methodName: "same", disposition: "unchanged", beforeCount: 1, afterCount: 1}]}
  ];
}

test("model groups rows by calling service and derives each service state", () => {
  const model = flow.buildModel(rows());
  assert.deepEqual(model.services.map(service => [service.name, service.state]), [["cb-app", "changed"], ["cb-ems", "before_only"]]);
  const app = model.services[0];
  assert.equal(app.changedCalls.length, 1);
  assert.equal(app.commonCalls, 2);
  assert.equal(app.changedMethods.length, 1);
  assert.equal(model.unattributed, 0);
});

test("a service with unchanged outcomes but differing calls is marked as differing inside", () => {
  const values = rows(); values[0].disposition = "unchanged";
  assert.equal(flow.buildModel(values).services[0].state, "inner_changed");
  assert.equal(flow.buildModel([call("worker", "queue", "after_only", 0, 1)]).services[0].state, "inner_changed");
});

test("downstream links need a configured name that equals another compared service", () => {
  const linked = flow.buildModel(rows()).services[0];
  assert.deepEqual([...linked.links.values()], ["cb-ems"]);
  const guessed = rows(); delete guessed[2].destinationNameSource;
  assert.equal(flow.buildModel(guessed).services[0].links.size, 0);
  const absent = rows().filter(row => row.service !== "cb-ems");
  assert.equal(flow.buildModel(absent).services[0].links.size, 0);
  const self = rows(); self[2].destinationName = "cb-app";
  assert.equal(flow.buildModel(self).services[0].links.size, 0);
});

test("malformed, unknown and unattributed rows are never drawn", () => {
  const model = flow.buildModel([null, "x", {category: "outcomes", service: "a", disposition: "invented"},
    {category: "lines", service: "a", disposition: "changed"}, call("", "x", "changed", 1, 2), call(7, "x", "changed", 1, 2)]);
  assert.equal(model.services.length, 0);
  assert.equal(model.unattributed, 2);
  assert.equal(flow.buildModel(undefined).services.length, 0);
});

test("render draws services, calls, the unchanged group and the method tree as text", () => {
  const h = fixture();
  flow.render(h.container, rows(), {limited: true}, {});
  const content = h.container.textContent;
  assert.match(content, /cb-app.*POST \/subscriptions.*HTTP 404 · COMPLETED → HTTP 200 · COMPLETED.*Changed/);
  assert.match(content, /GET cb-ems.*\/entitlements.*before 1 · after 0.*Before only/);
  assert.match(content, /cb-ems request.*Matched by configured name/);
  assert.match(content, /2 unchanged calls/);
  assert.match(content, /Methods in cb-app · 1 outermost difference/);
  assert.match(content, /FeatureGuard\.check.*Nested behavior differs.*EntitlementClient\.fetch.*Before only/);
  assert.doesNotMatch(content, /Ignored\.same/);
  assert.match(content, /3 more nested differences/);
  assert.match(content, /do not record call order/);
  assert.match(content, /Bounded comparison/);
  assert.equal(h.findAll("tcf-call").find(node => /unchanged calls/.test(node.textContent)).tagName, "div");
});

test("selecting a node reports the original row and marks only that node as pressed", () => {
  const h = fixture(), values = rows(), picked = [];
  const stop = flow.render(h.container, values, {}, {onSelect: row => picked.push(row)});
  const callNode = h.findAll("tcf-call")[0], service = h.find("tcf-service");
  callNode.fire("click");
  assert.equal(picked[0], values[2]);
  assert.equal(callNode.getAttribute("aria-pressed"), "true");
  service.fire("click");
  assert.equal(picked[1], values[0]);
  assert.equal(callNode.getAttribute("aria-pressed"), "false");
  h.find("tcf-ref").fire("click");
  assert.equal(picked[2], values[1]);
  const nested = h.findAll("tcf-method-node")[1];
  nested.fire("click");
  assert.equal(picked[3], values[5], "a nested method opens its outermost parent row");
  stop();
  assert.equal(h.listeners(), 0);
});

test("an empty comparison explains that nothing can be drawn", () => {
  const h = fixture();
  flow.render(h.container, [], {}, {});
  assert.match(h.container.textContent, /No service evidence was returned/);
  assert.equal(h.findAll("tcf-block").length, 0);
});

test("large comparisons are capped with a visible note", () => {
  const many = Array.from({length: 20}, (_, index) => call("svc" + index, "x", "changed", 1, 2));
  const model = flow.buildModel(many);
  assert.equal(model.services.length, 16);
  assert.equal(model.servicesOmitted, 4);
  const h = fixture();
  flow.render(h.container, Array.from({length: 15}, (_, index) => call("app", "host" + index, "changed", 1, 2)), {}, {});
  assert.equal(h.findAll("tcf-call").length, 12);
  assert.match(h.container.textContent, /3 more differing calls/);
});

function comparison() {
  const group = {added: [], noLongerObserved: [], countChanged: [], totals: {added: 0, noLongerObserved: 0, countChanged: 0}, omitted: 0};
  const ref = {snapshotId: "a", service: "cb-app", segmentId: "s", serviceInstanceId: "i"};
  return {kind: "capture-comparison", before: {label: "Baseline"}, after: {label: "Candidate"},
    methods: {...group}, dependencies: {...group}, outcomes: {...group}, exceptions: {...group}, lines: {...group}, nesting: {...group},
    warnings: [], limitations: [], limited: false, durationSamples: {before: [], after: []},
    evidenceWarnings: {before: {warnings: [], segments: []}, after: {warnings: [], segments: []}},
    behavior: {schemaVersion: 1, limited: false, omitted: {}, warnings: [],
      outcomes: [{service: "cb-app", method: "POST", path: "/subscriptions", disposition: "changed",
        before: [{...ref, httpStatus: 404}], after: [{...ref, snapshotId: "b", httpStatus: 200}]}],
      dependencies: [{service: "cb-app", boundaryKind: "http", operation: "GET", destination: "ems.local", path: "/entitlements",
        disposition: "before_only", before: {count: 1, statuses: [], evidence: []}, after: {count: 0, statuses: [], evidence: []}}],
      methods: []}};
}

test("the comparison offers the flow view only when its script is loaded, and draws it on first open", () => {
  const without = fixture();
  tools.renderComparison(without.container, comparison());
  assert.equal(without.find("tct-flow"), null);
  assert.ok(without.find("tct-behavior-dependencies"), "list view is unaffected");

  globalThis.TraceLensCompareFlow = flow;
  try {
    const h = fixture();
    const stop = tools.renderComparison(h.container, comparison());
    const details = h.find("tct-flow");
    assert.equal(h.find("tcf"), null, "nothing is drawn while the flow view is closed");
    details.open = true; details.fire("toggle");
    assert.match(h.find("tcf").textContent, /GET ems\.local/);
    h.findAll("tcf-call")[0].fire("click");
    assert.match(h.find("tct-focus-label").textContent, /Focused difference: cb-app · GET · ems\.local/);
    assert.equal(h.find("tct-behavior-outcomes"), null, "the list narrows to the selected node");
    assert.ok(h.find("tct-behavior-dependencies"));
    stop();
    assert.equal(h.listeners(), 0);
  } finally { delete globalThis.TraceLensCompareFlow; }
});

test("selecting a node whose row is unchanged opens the common context so the row is visible", () => {
  globalThis.TraceLensCompareFlow = flow;
  try {
    const h = fixture(), value = comparison();
    value.behavior.outcomes[0].disposition = "unchanged";
    const stop = tools.renderComparison(h.container, value);
    assert.equal(h.find("tct-common-context").open, false, "common context starts collapsed");
    const details = h.find("tct-flow"); details.open = true; details.fire("toggle");
    h.find("tcf-service").fire("click");
    const common = h.find("tct-common-context");
    assert.equal(common.open, true);
    assert.match(common.textContent, /POST \/subscriptions/);
    assert.doesNotMatch(h.find("tct-behavior-rows").textContent, /No behavior differences/);
    stop();
  } finally { delete globalThis.TraceLensCompareFlow; }
});

test("calls without a recorded destination are named by their system", () => {
  const h = fixture();
  flow.render(h.container, [{category: "dependencies", service: "cb-ems", boundaryKind: "cache", system: "redis", operation: "set",
    destination: null, path: null, disposition: "before_only", before: {count: 1}, after: {count: 0}},
  {category: "dependencies", service: "cb-ems", boundaryKind: "queue", disposition: "changed", before: {count: 1}, after: {count: 2}}], {}, {});
  const titles = h.findAll("tcf-title").map(node => node.textContent);
  assert.ok(titles.includes("redis set"));
  assert.ok(titles.includes("queue"));
});

test("the highlighted node follows a focus that is set or cleared in the list", () => {
  globalThis.TraceLensCompareFlow = flow;
  try {
    const h = fixture();
    const stop = tools.renderComparison(h.container, comparison());
    const details = h.find("tct-flow"); details.open = true; details.fire("toggle");
    const callNode = h.findAll("tcf-call")[0], service = h.find("tcf-service");
    callNode.fire("click");
    assert.equal(callNode.getAttribute("aria-pressed"), "true");
    h.find("tct-clear-focus").fire("click");
    assert.equal(callNode.getAttribute("aria-pressed"), "false", "Show all changes clears the highlight");
    h.findAll("tct-inspect-difference")[0].fire("click");
    assert.equal(service.getAttribute("aria-pressed"), "true", "Inspect difference highlights the matching node");
    stop();
  } finally { delete globalThis.TraceLensCompareFlow; }
});

test("a selected nested method keeps its own highlight when its parent row is focused", () => {
  const h = fixture(), values = rows();
  const view = flow.render(h.container, values, {}, {});
  const nodes = h.findAll("tcf-method-node");
  nodes[1].fire("click");
  view.select(values[5]);
  assert.equal(nodes[1].getAttribute("aria-pressed"), "true");
  assert.equal(nodes[0].getAttribute("aria-pressed"), "false");
  view.select(null);
  assert.equal(nodes[1].getAttribute("aria-pressed"), "false");
});

test("focusing a downstream service highlights its own node, not the reference beside the call", () => {
  const h = fixture(), values = rows();
  const view = flow.render(h.container, values, {}, {});
  view.select(values[1]);
  assert.equal(h.findAll("tcf-service")[1].getAttribute("aria-pressed"), "true");
  assert.equal(h.find("tcf-ref").getAttribute("aria-pressed"), "false");
});
