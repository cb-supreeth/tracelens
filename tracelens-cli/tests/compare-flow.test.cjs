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

test("render draws a headline, services, calls, the unchanged group and the method tree as text", () => {
  const h = fixture();
  flow.render(h.container, rows(), {limited: true}, {});
  const content = h.container.textContent;
  const headline = h.find("cfv-headline").textContent;
  assert.match(headline, /cb-app response HTTP 404 · COMPLETED → HTTP 200 · COMPLETED · cb-ems response HTTP 404 → Not observed/);
  assert.match(headline, /1 call no longer observed/);
  assert.match(headline, /1 method differs/);
  assert.doesNotMatch(headline, /removed/i);
  assert.match(headline, /Bounded comparison/);
  assert.match(content, /cb-app.*POST \/subscriptions.*HTTP 404 · COMPLETED → HTTP 200 · COMPLETED.*Changed/);
  assert.match(content, /GET cb-ems.*\/entitlements.*before 1 · after 0.*Before only/);
  assert.match(content, /cb-ems request.*Matched by configured name/);
  assert.match(content, /2 unchanged calls/);
  assert.match(content, /Methods in cb-app · 1 outermost difference/);
  assert.match(content, /Nested behavior differs.*FeatureGuard\.check.*Before only.*EntitlementClient\.fetch/);
  assert.doesNotMatch(content, /Ignored\.same/);
  assert.match(content, /3 more nested differences/);
  assert.match(content, /do not record call order/);
  assert.equal(h.findAll("cfv-call").find(node => /unchanged calls/.test(node.textContent)).tagName, "div");
  assert.equal(h.findAll("tcf-node").length + h.findAll("tcf-title").length, 0, "no class shared with the Code flow view");
});

test("the headline says plainly when nothing differs", () => {
  const h = fixture();
  flow.render(h.container, [call("app", "x", "unchanged", 1, 1)], {}, {});
  assert.match(h.find("cfv-headline").textContent, /No differences in the returned evidence/);
});

test("nested method counts use the backend total and are not counted twice", () => {
  const values = rows(); values[5].nestedChangedCount = 9; values[5].disposition = "changed";
  assert.equal(flow.buildModel(values).summary.methods, 10);
});

test("selecting a node reports the row, and the owning list row for a nested method", () => {
  const h = fixture(), values = rows(), picked = [];
  const stop = flow.render(h.container, values, {}, {onSelect: (row, listRow) => picked.push([row, listRow])});
  const callNode = h.findAll("cfv-call")[0], service = h.find("cfv-service");
  callNode.fire("click");
  assert.deepEqual(picked[0], [values[2], values[2]]);
  assert.equal(callNode.getAttribute("aria-pressed"), "true");
  service.fire("click");
  assert.equal(picked[1][0], values[0]);
  assert.equal(callNode.getAttribute("aria-pressed"), "false");
  h.find("cfv-ref").fire("click");
  assert.equal(picked[2][0], values[1]);
  h.findAll("cfv-mrow")[1].fire("click");
  assert.equal(picked[3][0].className, "example.EntitlementClient", "a nested method shows its own evidence");
  assert.equal(picked[3][0].category, "methods");
  assert.equal(picked[3][0].service, "cb-app");
  assert.equal(picked[3][1], values[5], "the list row is the outermost parent");
  stop();
  assert.equal(h.listeners(), 0);
});

test("plain getters and setters fold away, but is/has methods and outermost methods stay visible", () => {
  const h = fixture();
  const nested = (name, disposition = "before_only") => ({className: "example.Entity", methodName: name, disposition, beforeCount: 1, afterCount: 0});
  flow.render(h.container, [{category: "methods", service: "app", className: "example.Entity", methodName: "getId", disposition: "changed",
    beforeCount: 2, afterCount: 1, nestedChanges: [nested("getValue"), nested("setValue"), nested("isGrandfatheringEnabled"), nested("hasAccess"), nested("getter")]}], {}, {});
  const folded = h.find("cfv-accessors");
  assert.match(folded.textContent, /2 plain getters and setters/);
  assert.match(folded.textContent, /Entity\.getValue.*Entity\.setValue/);
  assert.equal(folded.open, false);
  const visible = h.findAll("cfv-mrow").filter(row => !folded.all().includes(row)).map(row => row.textContent);
  assert.ok(visible.some(value => /Entity\.getId/.test(value)), "an outermost getter is never folded");
  assert.ok(visible.some(value => /isGrandfatheringEnabled/.test(value)));
  assert.ok(visible.some(value => /hasAccess/.test(value)));
  assert.ok(visible.some(value => /Entity\.getter/.test(value)), "only get/set followed by a capital is an accessor");
});

test("unchanged services shrink to one line after the services that differ; differing-inside services stay full", () => {
  const h = fixture(), picked = [];
  const values = [
    {category: "outcomes", service: "quiet", method: "GET", path: "/plans", disposition: "unchanged", before: [{httpStatus: 200}], after: [{httpStatus: 200}]},
    call("quiet", "db", "unchanged", 3, 3),
    {category: "outcomes", service: "busy", method: "GET", path: "/items", disposition: "unchanged", before: [{httpStatus: 200}], after: [{httpStatus: 200}]},
    call("busy", "cache", "before_only", 1, 0)];
  flow.render(h.container, values, {}, {onSelect: row => picked.push(row)});
  assert.deepEqual(h.findAll("cfv-block").map(block => block.find ? "" : block.textContent.split(" ")[0]), ["busy"]);
  const compact = h.find("cfv-srow");
  assert.match(compact.textContent, /quiet.*GET \/plans · HTTP 200 in both · 1 call unchanged.*Unchanged/);
  const graph = h.find("cfv-graph").childNodes;
  assert.ok(graph.indexOf(compact) > graph.indexOf(h.find("cfv-block")), "unchanged services come last");
  compact.fire("click");
  assert.equal(picked[0], values[0], "the one-line row still fills the panel");
});

test("an empty comparison explains that nothing can be drawn", () => {
  const h = fixture();
  flow.render(h.container, [], {}, {});
  assert.match(h.container.textContent, /No service evidence was returned/);
  assert.equal(h.findAll("cfv-block").length, 0);
});

test("large comparisons are capped with a visible note", () => {
  const many = Array.from({length: 20}, (_, index) => call("svc" + index, "x", "changed", 1, 2));
  const model = flow.buildModel(many);
  assert.equal(model.services.length, 16);
  assert.equal(model.servicesOmitted, 4);
  const h = fixture();
  flow.render(h.container, Array.from({length: 15}, (_, index) => call("app", "host" + index, "changed", 1, 2)), {}, {});
  assert.equal(h.findAll("cfv-call").length, 12);
  assert.match(h.container.textContent, /3 more differing calls/);
});

function comparison() {
  const group = {added: [], noLongerObserved: [], countChanged: [], totals: {added: 0, noLongerObserved: 0, countChanged: 0}, omitted: 0};
  const ref = {snapshotId: "a", service: "cb-app", segmentId: "s", serviceInstanceId: "i"};
  return {kind: "capture-comparison", before: {label: "Baseline"}, after: {label: "Candidate"},
    methods: {...group}, dependencies: {...group}, outcomes: {...group}, exceptions: {...group}, lines: {...group}, nesting: {...group},
    warnings: [], limitations: [], limited: false, durationSamples: {before: [], after: []},
    evidenceWarnings: {before: {warnings: [], segments: []}, after: {warnings: [], segments: []}},
    sourceSnapshots: {before: {snapshotId: "a"}, after: {snapshotId: "b"}},
    behavior: {schemaVersion: 1, limited: false, omitted: {}, warnings: [],
      outcomes: [{service: "cb-app", method: "POST", path: "/subscriptions", disposition: "changed",
        before: [{...ref, httpStatus: 404}], after: [{...ref, snapshotId: "b", httpStatus: 200}]}],
      dependencies: [{service: "cb-app", boundaryKind: "http", operation: "GET", destination: "ems.local", path: "/entitlements",
        disposition: "before_only", before: {count: 1, statuses: [], evidence: []}, after: {count: 0, statuses: [], evidence: []}}],
      methods: []}};
}
function withFlow(run) {
  globalThis.TraceLensCompareFlow = flow;
  try { run(); } finally { delete globalThis.TraceLensCompareFlow; }
}

test("without the flow script the list view is unchanged", () => {
  const h = fixture();
  tools.renderComparison(h.container, comparison());
  assert.equal(h.find("tct-flow"), null);
  assert.ok(h.find("tct-behavior-dependencies"));
});

test("the flow view opens by default when anything differs, with the most important difference in the panel", () => withFlow(() => {
  const h = fixture();
  const stop = tools.renderComparison(h.container, comparison());
  assert.equal(h.find("tct-flow").open, true);
  const panel = h.find("tct-flow-panel");
  assert.ok(h.find("cfv-layout").childNodes.includes(panel), "the panel sits in the flow layout");
  assert.match(panel.textContent, /Selected.*POST \/subscriptions.*HTTP 404.*HTTP 200.*Show in list/, "an HTTP error outranks other differences");
  assert.equal(h.find("cfv-service").getAttribute("aria-pressed"), "true");
  stop();
  assert.equal(h.listeners(), 0);
}));

test("the flow view stays closed and undrawn when nothing differs", () => withFlow(() => {
  const h = fixture(), value = comparison();
  value.behavior.outcomes[0].disposition = "unchanged"; value.behavior.dependencies[0].disposition = "unchanged";
  tools.renderComparison(h.container, value);
  const details = h.find("tct-flow");
  assert.equal(details.open, false);
  assert.equal(h.find("cfv"), null, "nothing is drawn while the flow view is closed");
  details.open = true; details.fire("toggle");
  assert.match(h.find("cfv-headline").textContent, /No differences/);
}));

test("selecting a node fills the panel without narrowing or scrolling the list; Show in list narrows it", () => withFlow(() => {
  const h = fixture();
  const stop = tools.renderComparison(h.container, comparison());
  h.findAll("cfv-call")[0].fire("click");
  assert.match(h.find("tct-flow-panel").textContent, /GET · ems\.local · \/entitlements.*1 recorded call.*Not observed in this capture/);
  assert.equal(h.find("tct-focus-bar").hidden, true, "the list is not narrowed by a graph click");
  assert.ok(h.find("tct-behavior-outcomes"));
  h.find("tct-flow-show-in-list").fire("click");
  assert.match(h.find("tct-focus-label").textContent, /Focused difference: cb-app · GET · ems\.local/);
  assert.equal(h.find("tct-behavior-outcomes"), null);
  h.find("tct-clear-focus").fire("click");
  assert.equal(h.findAll("cfv-call")[0].getAttribute("aria-pressed"), "true", "clearing the list focus keeps the graph selection");
  stop();
}));

test("panel evidence buttons keep working after the list redraws", () => withFlow(() => {
  const h = fixture(), opened = [];
  const stop = tools.renderComparison(h.container, comparison(), {onOpenEvidence: async ref => { opened.push(ref); }});
  const panel = h.find("tct-flow-panel");
  const button = () => panel.all().find(node => node.classList.contains("tct-open-evidence"));
  assert.ok(button(), "the preselected outcome offers its saved evidence");
  const search = h.find("tct-pair-search"); search.value = "ems"; search.fire("input");
  search.value = ""; search.fire("input");
  button().fire("click");
  assert.equal(opened.length, 1);
  assert.equal(opened[0].snapshotId, "a");
  stop();
  assert.equal(h.listeners(), 0);
}));

test("Show in list on an unchanged row opens the common context so the row is visible", () => withFlow(() => {
  const h = fixture(), value = comparison();
  value.behavior.outcomes[0].disposition = "unchanged";
  const stop = tools.renderComparison(h.container, value);
  assert.equal(h.find("tct-common-context").open, false, "common context starts collapsed");
  h.find("cfv-service").fire("click");
  h.find("tct-flow-show-in-list").fire("click");
  const common = h.find("tct-common-context");
  assert.equal(common.open, true);
  assert.match(common.textContent, /POST \/subscriptions/);
  assert.doesNotMatch(h.find("tct-behavior-rows").textContent, /No behavior differences/);
  stop();
}));

test("Show in list for a nested method focuses its outermost parent row", () => withFlow(() => {
  const h = fixture(), value = comparison();
  value.behavior.methods = [{service: "cb-app", className: "example.Guard", methodName: "check", disposition: "descendants_changed", beforeCount: 1, afterCount: 1,
    before: [], after: [], nestedChanges: [{className: "example.Client", methodName: "fetch", disposition: "before_only", beforeCount: 1, afterCount: 0}]}];
  const stop = tools.renderComparison(h.container, value);
  h.findAll("cfv-mrow")[1].fire("click");
  assert.match(h.find("tct-flow-panel").textContent, /example\.Client\.fetch/);
  h.find("tct-flow-show-in-list").fire("click");
  assert.match(h.find("tct-focus-label").textContent, /example\.Guard\.check/);
  assert.ok(h.find("tct-behavior-methods"));
  stop();
}));

test("calls without a recorded destination are named by their system", () => {
  const h = fixture();
  flow.render(h.container, [{category: "dependencies", service: "cb-ems", boundaryKind: "cache", system: "redis", operation: "set",
    destination: null, path: null, disposition: "before_only", before: {count: 1}, after: {count: 0}},
  {category: "dependencies", service: "cb-ems", boundaryKind: "queue", disposition: "changed", before: {count: 1}, after: {count: 2}}], {}, {});
  const titles = h.findAll("cfv-title").map(node => node.textContent);
  assert.ok(titles.includes("redis set"));
  assert.ok(titles.includes("queue"));
});

test("select highlights a row's node without reporting a selection", () => {
  const h = fixture(), values = rows(), picked = [];
  const view = flow.render(h.container, values, {}, {onSelect: row => picked.push(row)});
  view.select(values[5]);
  assert.equal(h.findAll("cfv-mrow")[0].getAttribute("aria-pressed"), "true");
  view.select(null);
  assert.equal(h.findAll("cfv-mrow")[0].getAttribute("aria-pressed"), "false");
  assert.equal(picked.length, 0);
});

test("selecting a downstream service highlights its own node, not the reference beside the call", () => {
  const h = fixture(), values = rows();
  const view = flow.render(h.container, values, {}, {});
  view.select(values[1]);
  assert.equal(h.findAll("cfv-service")[1].getAttribute("aria-pressed"), "true");
  assert.equal(h.find("cfv-ref").getAttribute("aria-pressed"), "false");
});
