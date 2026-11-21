"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const tools = require("../tracelens_cli/web/capture-tools.js");

const first = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const second = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const cliId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const listing = {snapshots: [{id: second, name: "Candidate"}, {id: first, name: "Baseline"}], captures: []};
const settle = () => new Promise(resolve => setImmediate(resolve));
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
    async fire(key, values = {}) {
      const event = {target: this, preventDefault() { this.defaultPrevented = true; }, ...values};
      await Promise.all((this.listeners[key] || []).slice().map(fn => fn(event)));
      return event;
    }
    focus() { this.focused = true; }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelector(selector) { return this.all().find(child => child.classList.contains(selector.slice(1))) || null; }
  }
  const document = {createElement: tag => new Element(tag, document)};
  const container = document.createElement("div");
  return {container, created, find: cls => container.querySelector(`.${cls}`),
    listeners: () => created.reduce((sum, item) => sum + Object.values(item.listeners).reduce((n, values) => n + values.length, 0), 0)};
}
function comparison() {
  const row = {added: [], noLongerObserved: [], countChanged: [],
    totals: {added: 0, noLongerObserved: 0, countChanged: 0}, omitted: 0};
  return {
    kind: "capture-comparison", before: {label: "Baseline", segmentCount: 1}, after: {label: "Candidate", segmentCount: 2},
    methods: {...row, added: [{service: "app", className: "example.Features", methodName: "lookup", beforeCount: 0, afterCount: 1}],
      noLongerObserved: [{service: "app", className: "example.Features", methodName: "retrieve", beforeCount: 1, afterCount: 0}],
      countChanged: [{service: "app", className: "example.Features", methodName: "load", beforeCount: 1, afterCount: 3}],
      totals: {added: 1, noLongerObserved: 1, countChanged: 1}},
    dependencies: {...row, added: [{destination: "orders", boundaryKind: "messaging", beforeCount: 0, afterCount: 2}],
      totals: {...row.totals, added: 1}}, outcomes: {...row}, exceptions: {...row}, lines: {...row}, nesting: {...row},
    warnings: ["request_paths_or_methods_differ", "service_or_segment_counts_differ", "partial_capture_evidence"],
    durationSamples: {before: [{service: "app", durationMs: 12}], after: [{service: "app", durationMs: 8}]},
    evidenceWarnings: {before: {warnings: ["viewer_snapshot_partial"], segments: []}, after: {warnings: [], segments: []}},
    limitations: ["Observed retained evidence only."], limited: false
  };
}
function behaviorComparison() {
  const value = comparison();
  const before = {snapshotId: first, service: "cb-app", segmentId: "before-segment", serviceInstanceId: "app-instance"};
  const after = {snapshotId: second, service: "cb-app", segmentId: "after-segment", serviceInstanceId: "app-instance"};
  value.sourceSnapshots = {before: {snapshotId: first}, after: {snapshotId: second}};
  value.behavior = {schemaVersion: 1, completeness: "observed-only", limited: false, omitted: {}, warnings: [],
    outcomes: [{service: "cb-app", method: "POST", path: "/cart_sub_create", disposition: "changed",
      before: [{...before, httpStatus: 404, status: "COMPLETED"}], after: [{...after, httpStatus: 200, status: "COMPLETED"}]}],
    dependencies: [{service: "cb-app", boundaryKind: "http", system: "http", direction: "outbound", destination: "ems.local:8082",
      operation: "GET", path: "/subscriptions/new/entitlements", evidenceKind: "http_metadata", disposition: "before_only",
      before: {count: 1, statuses: [{httpStatus: 404, count: 1}], evidence: [before]}, after: {count: 0, statuses: [], evidence: []}},
    {service: "cb-app", boundaryKind: "http", destination: "catalog.local", operation: "GET", path: "/plans", disposition: "unchanged",
      before: {count: 1, statuses: [{httpStatus: 200, count: 1}], evidence: [before]}, after: {count: 1, statuses: [{httpStatus: 200, count: 1}], evidence: [after]}}],
    methods: [{service: "cb-app", className: "example.UsageEntitlementClient", methodName: "fetchEntitlements", disposition: "before_only", beforeCount: 1, afterCount: 0,
      before: [{...before, className: "example.UsageEntitlementClient", methodName: "fetchEntitlements", count: 1}], after: []}]
  };
  return value;
}
function nestedBehaviorComparison() {
  const value = behaviorComparison(), template = value.behavior.methods[0];
  function method(name, countBefore, countAfter, disposition) {
    return {...template, className: `example.${name}`, methodName: name, beforeCount: countBefore, afterCount: countAfter, disposition,
      before: countBefore ? [{...template.before[0], className: `example.${name}`, methodName: name, count: countBefore}] : [],
      after: countAfter ? [{...template.before[0], snapshotId: second, segmentId: "after-segment", className: `example.${name}`, methodName: name, count: countAfter}] : []};
  }
  value.behavior.outcomes = []; value.behavior.dependencies = [];
  value.behavior.methods = [{...method("A", 1, 1, "descendants_changed"),
    nestedChanges: [method("B", 1, 0, "before_only"), method("C", 0, 1, "after_only"), method("E", 2, 1, "changed")],
    nestedChangedCount: 3, nestedOmitted: 0}];
  return value;
}

test("buildless UMD is dependency-free and does not read live data or create timers", () => {
  const source = fs.readFileSync(require.resolve("../tracelens_cli/web/capture-tools.js"), "utf8");
  const context = {}; vm.runInNewContext(source, context);
  assert.equal(typeof context.TraceLensCaptureTools.render, "function");
  assert.equal(typeof context.TraceLensCaptureTools.renderComparison, "function");
  assert.doesNotMatch(source, /\b(?:fetch|XMLHttpRequest|localStorage|sessionStorage|indexedDB|setInterval|setTimeout|innerHTML)\b/);
  assert.doesNotMatch(source, /\/api\/(?:distributed-trace|render-data|traces|summary|reset|record)/);
});

test("selected saved pair retains order and exposes comparability before an explicit comparison", async () => {
  const h = fixture(), calls = [];
  const stop = tools.render(h.container, {api: (...args) => { calls.push(args); return listing; }});
  await settle(); await stop.selectPair([second, first]);
  assert.equal(h.find("tct-baseline").value, second);
  assert.equal(h.find("tct-candidate").value, first);
  assert.match(h.find("tct-selection-metadata").textContent, /Candidate/);
  assert.match(h.find("tct-selection-metadata").textContent, /Baseline/);
  assert.equal(calls.some(call => call[1] !== "GET"), false);
  stop();
});

test("an older asynchronous pair selection cannot replace a newer selection", async () => {
  const h = fixture(), pendingLists = [];
  let initial = true;
  const stop = tools.render(h.container, {api: () => {
    if (initial) { initial = false; return listing; }
    return new Promise(resolve => pendingLists.push(resolve));
  }});
  await settle();
  const older = stop.selectPair([second, first]); await settle();
  const newer = stop.selectPair([first, second]); await settle();
  assert.equal(pendingLists.length, 2);
  pendingLists[1](listing); await newer;
  assert.equal(h.find("tct-baseline").value, first);
  assert.equal(h.find("tct-candidate").value, second);
  const status = h.find("tct-compare-status").textContent;
  pendingLists[0](listing); await older;
  assert.equal(h.find("tct-baseline").value, first);
  assert.equal(h.find("tct-candidate").value, second);
  assert.equal(h.find("tct-compare-status").textContent, status);
  stop(); assert.equal(h.listeners(), 0);
});

test("manual pair changes supersede a pending asynchronous selection", async () => {
  const h = fixture(); let resolveList, initial = true;
  const stop = tools.render(h.container, {api: () => {
    if (initial) { initial = false; return listing; }
    return new Promise(resolve => { resolveList = resolve; });
  }});
  await settle();
  const pending = stop.selectPair([first, second]); await settle();
  h.find("tct-baseline").value = second; await h.find("tct-baseline").fire("change");
  h.find("tct-candidate").value = first; await h.find("tct-candidate").fire("change");
  resolveList(listing); await pending;
  assert.equal(h.find("tct-baseline").value, second);
  assert.equal(h.find("tct-candidate").value, first);
  assert.equal(h.find("tct-compare-status").textContent, "");
  assert.notEqual(h.find("tct-compare").focused, true, "stale selection must not steal focus");
  stop(); assert.equal(h.listeners(), 0);
});

test("initial rendering only lists saved files and has accessible independent tabs", async () => {
  const h = fixture(), calls = [];
  const stop = tools.render(h.container, {api: (...args) => { calls.push(args); return listing; }});
  await settle();
  assert.deepEqual(calls, [["/api/capture-tools", "GET", undefined]]);
  assert.equal(h.find("tct-baseline").value, first);
  assert.equal(h.find("tct-candidate").value, second);
  assert.equal(h.find("tct-export").open, false);
  assert.equal(h.find("tct-search-panel").hidden, true);
  const tabs = h.container.all().filter(node => node.getAttribute("role") === "tab");
  assert.equal(tabs[0].getAttribute("aria-selected"), "true");
  await tabs[0].fire("keydown", {key: "ArrowRight"});
  assert.equal(tabs[1].getAttribute("aria-selected"), "true");
  assert.equal(tabs[1].focused, true);
  assert.equal(h.find("tct-search-panel").hidden, false);
  assert.equal(calls.length, 1, "Changing tabs does not start a search");
  stop(); assert.equal(h.listeners(), 0);
});

test("comparison submits exactly the selected IDs and renders observed differences with timing caveats", async () => {
  const h = fixture(), calls = [];
  const stop = tools.render(h.container, {api: (path, method, body) => {
    calls.push({path, method, body}); return path.endsWith("/compare") ? comparison() : listing;
  }});
  await settle(); await h.find("tct-compare").fire("click");
  assert.deepEqual(calls[1], {path: "/api/capture-tools/compare", method: "POST", body: {before: first, after: second}});
  assert.match(h.find("tct-comparison").textContent, /No longer observed/);
  assert.match(h.find("tct-comparison").textContent, /absent from this retained sample, not deleted code/);
  assert.match(h.find("tct-comparison").textContent, /Request paths or HTTP methods differ/);
  assert.match(h.find("tct-comparison").textContent, /Service scope or segment counts differ/);
  assert.match(h.find("tct-comparison").textContent, /partial evidence/);
  assert.match(h.find("tct-comparison").textContent, /1 → 3/);
  assert.match(h.find("tct-timing").textContent, /not a benchmark/);
  assert.match(h.find("tct-timing").textContent, /12 ms.*8 ms/);
  assert.equal(h.container.all().filter(node => node.classList.contains("tct-summary-row")).length, 6);
  assert.equal(h.find("tct-differences").open, false);
  stop();
});

test("conversion is explicit, creates a separate CLI capture and excludes file-path controls", async () => {
  const h = fixture(), calls = [];
  const stop = tools.render(h.container, {api: (path, method, body) => {
    calls.push({path, method, body});
    return path.endsWith("/project") ? {saved: true, capture: {captureId: cliId}} : listing;
  }});
  await settle();
  assert.equal(calls.length, 1);
  h.find("tct-export").open = true; await h.find("tct-export").fire("toggle");
  assert.equal(calls.length, 1, "Opening the explanation does not create a file");
  h.find("tct-export-label").value = "  Trial  ";
  h.find("tct-export-revision").value = "build-2";
  await h.find("tct-project").fire("click");
  assert.deepEqual(calls[1], {path: "/api/capture-tools/project", method: "POST",
    body: {snapshotId: second, label: "Trial", revision: "build-2"}});
  assert.match(h.find("tct-project-status").textContent, /Created CLI\/MCP capture/);
  assert.match(h.find("tct-project-status").textContent, /viewer snapshot is unchanged/);
  assert.match(h.find("tct-export").textContent, /Source, user\/context fields, headers, payloads and raw exception messages are excluded/);
  assert.equal(h.container.all().some(node => node.type === "file"), false);
  stop();
});

test("search is on demand, bounded, literal and can open only a returned saved snapshot", async () => {
  const h = fixture(), calls = [], opened = [], payload = '<img src=x onerror="alert(1)">';
  const stop = tools.render(h.container, {onOpenSnapshot: id => opened.push(id), api: (path, method, body) => {
    calls.push({path, method, body});
    return path.endsWith("/search") ? {returned: 2, recordsScanned: 2, limited: true,
      warnings: ["partial_saved_evidence"], results: [
        {storage: "viewer", snapshotId: first, label: payload, category: "method", className: payload,
          methodName: "lookup", count: 2, request: {path: "/features", durationMs: 7}},
        {storage: "capture", captureId: cliId, label: "CLI run", category: "method", methodName: "retrieve", count: 1}
      ]} : listing;
  }});
  await settle();
  h.find("tct-query").value = "Feature.*";
  h.find("tct-search-category").value = "method";
  await h.find("tct-search-form").fire("submit");
  assert.deepEqual(calls[1], {path: "/api/capture-tools/search", method: "POST",
    body: {query: "Feature.*", category: "method", limit: 50}});
  assert.match(h.find("tct-search-status").textContent, /Bounded or incomplete/);
  assert.ok(h.find("tct-search-results").textContent.includes(payload), "Untrusted strings remain inert text");
  assert.match(h.find("tct-search-results").textContent, /7 ms local sample \(not a benchmark\)/);
  assert.equal(h.container.all().filter(node => node.classList.contains("tct-open-snapshot")).length, 1);
  await h.find("tct-open-snapshot").fire("click");
  assert.deepEqual(opened, [first]);
  assert.equal(calls.length, 2);
  stop(); assert.equal(h.listeners(), 0);
});

test("offline failures are shown without a live fallback, and selected identity changes discard stale responses", async () => {
  const h = fixture(), calls = []; let resolveCompare;
  const stop = tools.render(h.container, {api: path => {
    calls.push(path);
    return path.endsWith("/compare") ? new Promise(resolve => { resolveCompare = resolve; }) : listing;
  }});
  await settle();
  const pending = h.find("tct-compare").fire("click"); await settle();
  h.find("tct-candidate").value = first; await h.find("tct-candidate").fire("change");
  resolveCompare(comparison()); await pending;
  assert.equal(h.find("tct-comparison").textContent, "");
  assert.equal(h.find("tct-compare").disabled, true);
  assert.deepEqual(calls, ["/api/capture-tools", "/api/capture-tools/compare"]);
  stop();
  const broken = fixture();
  tools.render(broken.container, {api: () => Promise.reject(new Error("Saved file is missing; no live data was substituted."))});
  await settle(); assert.match(broken.find("tct-status").textContent, /no live data was substituted/);
});

test("cleanup ignores pending list and compare work and releases all handlers", async () => {
  const h = fixture(); let resolve;
  const stop = tools.render(h.container, {api: () => new Promise(done => { resolve = done; })});
  await settle(); stop();
  const previous = h.container.textContent; resolve(listing); await settle();
  assert.equal(h.container.textContent, previous);
  assert.equal(h.listeners(), 0);
});

test("empty stores guide saving without starting live capture or conversion", async () => {
  const h = fixture(), calls = [];
  const stop = tools.render(h.container, {api: (...args) => { calls.push(args); return {snapshots: [], captures: []}; }});
  await settle();
  assert.match(h.find("tct-status").textContent, /Save a request snapshot first/);
  assert.equal(h.find("tct-compare").disabled, true); assert.equal(h.find("tct-project").disabled, true);
  await h.find("tct-project").fire("click"); await h.find("tct-compare").fire("click");
  assert.equal(calls.length, 1); stop();
});

test("comparison limits, safe text and counts are reusable without the tools controller", () => {
  const h = fixture(), value = comparison();
  value.before.label = "<script>danger()</script>";
  value.limited = true; value.methods.omitted = 17;
  tools.renderComparison(h.container, value);
  assert.ok(h.container.textContent.includes("<script>danger()</script>"));
  assert.match(h.container.textContent, /17 difference rows omitted/);
  assert.match(h.container.textContent, /This comparison is bounded/);
  assert.deepEqual(tools.summarizeComparison(value)[0],
    {key: "methods", label: "Methods", added: 1, noLongerObserved: 1, countChanged: 1, omitted: 17});
  assert.ok(Math.abs(parseFloat(h.find("tct-bar-added").style.width) - 100 / 3) < 0.001);
  assert.equal(tools.summarizeComparison(null).length, 6);
});

test("behavior review leads with paired outcomes and absent-call evidence, with raw differences secondary", () => {
  const h = fixture();
  const stop = tools.renderComparison(h.container, behaviorComparison());
  assert.match(h.find("tct-findings").textContent, /HTTP 404.*→ HTTP 200/);
  assert.match(h.find("tct-behavior-dependencies").textContent, /Before only.*ems\.local.*1 recorded call.*Not observed in this capture/);
  assert.match(h.find("tct-behavior-methods").textContent, /UsageEntitlementClient\.fetchEntitlements\(\).*1 recorded invocation.*Not observed/);
  assert.match(h.find("tct-behavior").textContent, /matching rows do not imply the same call sequence/);
  assert.match(h.find("tct-quality").textContent, /not deliberately skipped, deleted code, or proof that the fix is correct/);
  assert.match(h.find("tct-quality").textContent, /partial captures/);
  assert.equal(h.find("tct-all-differences").open, false);
  assert.equal(h.find("tct-common-context").open, false);
  assert.equal(h.find("tct-quality").open, false);
  assert.equal(h.find("tct-common-context").textContent.includes("catalog.local"), true);
  stop(); assert.equal(h.listeners(), 0);
});

test("known settings and agent build differences are visible quality flags without claiming equivalence", () => {
  const h = fixture(), value = behaviorComparison();
  value.warnings.push("capture_configuration_differs", "agent_build_differs");
  const stop = tools.renderComparison(h.container, value);
  assert.match(h.find("tct-quality").childNodes[0].textContent, /capture settings differ.*agent builds differ/);
  assert.match(h.find("tct-quality").textContent, /recording scope can affect the observations/);
  assert.match(h.find("tct-quality").textContent, /not deliberately skipped, deleted code, or proof that the fix is correct/);
  stop();
});

test("external-call names are display-only, retain addresses and caller ownership, and search by either", async () => {
  const h = fixture(), value = behaviorComparison(), opened = [];
  value.behavior.dependencies[0].destinationName = "cb-ems";
  value.behavior.dependencies[0].destinationNameSource = "configured";
  const original = structuredClone(value);
  const stop = tools.renderComparison(h.container, value, {onOpenEvidence: ref => opened.push(ref)});
  const calls = h.find("tct-behavior-dependencies"), heading = calls.querySelector(".tct-row-heading");
  assert.equal(heading.childNodes.find(node => node.tagName === "strong").textContent, "GET · cb-ems · /subscriptions/new/entitlements");
  assert.equal(calls.querySelector(".tct-row-service").textContent, "From cb-app");
  assert.equal(calls.querySelector(".tct-row-destination").textContent, "ems.local:8082 · configured label");
  assert.match(calls.querySelector(".tct-row-destination").title, /current viewer configuration.*does not establish.*downstream execution/);
  assert.match(h.find("tct-findings").textContent, /cb-ems/);
  await calls.querySelector(".tct-open-evidence").fire("click");
  assert.deepEqual(opened, [{snapshotId: first, service: "cb-app", segmentId: "before-segment", serviceInstanceId: "app-instance"}]);
  for (const query of ["cb-ems", "ems.local:8082", "cb-ems 8082", "cb-app cb-ems"]) {
    h.find("tct-pair-search").value = query; await h.find("tct-pair-search").fire("input");
    assert.match(h.find("tct-behavior-dependencies").textContent, /GET · cb-ems/);
    assert.match(h.find("tct-pair-search-status").textContent, /1 matching groups/);
  }
  assert.deepEqual(value, original, "Display labels never rewrite comparison or saved evidence identities");
  stop(); assert.equal(h.listeners(), 0);
});

test("unknown external destinations retain their addresses and malformed labels do not replace them", () => {
  for (const name of [undefined, null, {}, "  "]) {
    const h = fixture(), value = behaviorComparison(); value.behavior.dependencies[0].destinationName = name;
    const stop = tools.renderComparison(h.container, value);
    assert.match(h.find("tct-behavior-dependencies").querySelector(".tct-row-heading").textContent, /GET · ems\.local:8082/);
    assert.equal(h.find("tct-row-destination"), null);
    stop();
  }
});

test("external service labels and addresses render as bounded inert text", () => {
  const h = fixture(), value = behaviorComparison(), payload = '<img src=x onerror="evil()">';
  value.behavior.dependencies[0].destinationName = payload;
  value.behavior.dependencies[0].destinationNameSource = "configured";
  value.behavior.dependencies[0].destination = "a".repeat(2000);
  const stop = tools.renderComparison(h.container, value);
  assert.ok(h.find("tct-behavior-dependencies").textContent.includes(payload));
  assert.equal(h.find("tct-row-destination").textContent, "a".repeat(1600) + " · configured label");
  assert.equal(h.container.all().some(node => node.tagName === "img"), false);
  stop();
});

test("behavior links open only exact saved service/method evidence from their own side", async () => {
  const h = fixture(), opened = [];
  const stop = tools.renderComparison(h.container, behaviorComparison(), {onOpenEvidence: ref => opened.push(ref)});
  const outcome = h.find("tct-behavior-outcomes");
  const buttons = outcome.all().filter(node => node.classList.contains("tct-open-evidence"));
  await buttons[0].fire("click"); await buttons[1].fire("click");
  assert.deepEqual(opened.slice(0, 2), [
    {snapshotId: first, service: "cb-app", segmentId: "before-segment", serviceInstanceId: "app-instance"},
    {snapshotId: second, service: "cb-app", segmentId: "after-segment", serviceInstanceId: "app-instance"}
  ]);
  await h.find("tct-behavior-methods").querySelector(".tct-open-evidence").fire("click");
  assert.equal(opened[2].className, "example.UsageEntitlementClient");
  assert.equal(opened[2].methodName, "fetchEntitlements");
  assert.match(buttons[0].getAttribute("aria-label"), /before, cb-app/);
  assert.match(h.find("tct-behavior-dependencies").textContent, /Inspect service evidence/);
  assert.doesNotMatch(h.find("tct-behavior-dependencies").textContent, /View calling code/);
  stop(); assert.equal(h.listeners(), 0);
});

test("descriptor-bearing method differences display and navigate the exact overload", async () => {
  const h = fixture(), value = behaviorComparison(), opened = [], method = value.behavior.methods[0];
  method.methodDescriptor = "(Ljava/lang/String;)V";
  method.before.forEach(ref => { ref.methodDescriptor = method.methodDescriptor; });
  method.after.forEach(ref => { ref.methodDescriptor = method.methodDescriptor; });
  const stop = tools.renderComparison(h.container, value, {onOpenEvidence: ref => opened.push(ref)});
  const section = h.find("tct-behavior-methods");
  assert.match(section.textContent, /fetchEntitlements\(Ljava\/lang\/String;\)V/);
  await section.querySelector(".tct-open-evidence").fire("click");
  assert.equal(opened[0].methodDescriptor, "(Ljava/lang/String;)V");
  stop();
  method.before.forEach(ref => { ref.methodDescriptor = "(I)V"; });
  method.after = [];
  const mismatch = fixture(), cleanup = tools.renderComparison(mismatch.container, value, {onOpenEvidence: ref => opened.push(ref)});
  assert.equal(mismatch.find("tct-behavior-methods").querySelector(".tct-open-evidence"), null);
  cleanup();
});

test("selected-pair method search preserves descriptors without truncating navigation identity", async () => {
  const h = fixture(), opened = [], descriptor = "(L" + "a".repeat(1700) + ";)V";
  const stop = tools.renderComparison(h.container, behaviorComparison(), {onOpenEvidence: ref => opened.push(ref), api: () => ({
    scope: "selected-snapshots", snapshotIds: [first, second], returned: 1, recordsScanned: 2, results: [{
      storage: "viewer", snapshotId: first, service: "cb-app", segmentId: "before-segment", serviceInstanceId: "app-instance",
      category: "method", className: "example.Client", methodName: "read", methodDescriptor: descriptor, count: 1
    }]
  })});
  h.find("tct-pair-search").value = "read"; await h.find("tct-pair-search-form").fire("submit");
  await h.find("tct-open-pair-result").fire("click");
  assert.equal(opened[0].methodDescriptor, descriptor);
  assert.match(h.find("tct-pair-file-results").textContent, /example\.Client\.read\(La+/);
  stop();
});

test("pair search uses literal words, searches returned paired identities only and never calls an API", async () => {
  const h = fixture(), calls = [];
  const value = behaviorComparison(); value.behavior.methods[0].requestBody = "secret-payload";
  const stop = tools.render(h.container, {api: (...args) => { calls.push(args); return args[0].endsWith("/compare") ? value : listing; }, onOpenEvidence: () => {}});
  await settle(); await h.find("tct-compare").fire("click");
  const query = h.find("tct-pair-search");
  query.value = "UsageEntitlementClient fetchEntitlements"; await query.fire("input");
  assert.match(h.find("tct-behavior-rows").textContent, /fetchEntitlements/);
  assert.doesNotMatch(h.find("tct-behavior-rows").textContent, /ems\.local|cart_sub_create/);
  query.value = "fetch.*"; await query.fire("input");
  assert.match(h.find("tct-behavior-rows").textContent, /No matching identities/);
  query.value = "secret-payload"; await query.fire("input");
  assert.match(h.find("tct-behavior-rows").textContent, /No matching identities/);
  query.value = "catalog.local"; await query.fire("input");
  assert.equal(h.find("tct-common-context").open, true);
  assert.match(h.find("tct-pair-search-status").textContent, /1 matching groups.*no other captures or request payloads/);
  assert.equal(calls.length, 2);
  stop(); assert.equal(h.listeners(), 0);
});

test("invalid, cross-snapshot or ambiguous method references cannot navigate", async () => {
  const h = fixture(), value = behaviorComparison(), opened = [];
  value.behavior.outcomes = []; value.behavior.dependencies = [];
  const row = value.behavior.methods[0];
  row.before = [
    {...row.before[0], snapshotId: second},
    {...row.before[0], service: "other-service"},
    {...row.before[0], serviceInstanceId: ""},
    {...row.before[0], methodName: "differentMethod"}
  ];
  const stop = tools.renderComparison(h.container, value, {onOpenEvidence: ref => opened.push(ref)});
  assert.equal(h.find("tct-open-evidence"), null);
  assert.equal(opened.length, 0);
  stop();
});

test("unknown counts are not reported as absent, and limits remain visible without opening raw evidence", () => {
  const h = fixture(), value = behaviorComparison();
  value.behavior.dependencies[0].after.count = null;
  value.behavior.limited = true; value.behavior.omitted = {dependencies: 9, methods: 2};
  const stop = tools.renderComparison(h.container, value);
  assert.match(h.find("tct-behavior-dependencies").textContent, /Observation count unavailable/);
  assert.match(h.find("tct-quality").textContent, /bounded results/);
  assert.match(h.find("tct-quality").textContent, /9 dependencies groups omitted/);
  assert.match(h.find("tct-quality").textContent, /2 methods groups omitted/);
  stop();
});

test("large behavior reviews are bounded, progressively disclosed and safe text", async () => {
  const h = fixture(), value = behaviorComparison();
  const template = value.behavior.methods[0];
  value.behavior.methods = Array.from({length: 250}, (_, i) => ({...template, methodName: `method${i}`}));
  value.behavior.methods[0].className = "<img src=x onerror=evil()>";
  const stop = tools.renderComparison(h.container, value);
  assert.equal(h.find("tct-behavior-methods").all().filter(node => node.classList.contains("tct-behavior-row")).length, 8);
  assert.match(h.find("tct-behavior-methods").textContent, /Changed methods · 200/);
  assert.ok(h.find("tct-behavior-methods").textContent.includes("<img src=x onerror=evil()>"));
  await h.find("tct-more-behavior").fire("click");
  assert.equal(h.find("tct-behavior-methods").all().filter(node => node.classList.contains("tct-behavior-row")).length, 28);
  stop(); assert.equal(h.listeners(), 0);
});

test("changing pair cleans up prior behavior handlers and navigation results cannot overwrite disposed UI", async () => {
  const h = fixture(); let rejectNavigation;
  const stop = tools.render(h.container, {api: path => path.endsWith("/compare") ? behaviorComparison() : listing,
    onOpenEvidence: () => new Promise((resolve, reject) => { rejectNavigation = reject; })});
  await settle(); await h.find("tct-compare").fire("click");
  const oldSearch = h.find("tct-pair-search"), button = h.find("tct-open-evidence"), status = h.find("tct-evidence-status");
  const pending = button.fire("click"); await settle();
  h.find("tct-candidate").value = first; await h.find("tct-candidate").fire("change");
  assert.equal((oldSearch.listeners.input || []).length, 0);
  assert.equal((button.listeners.click || []).length, 0);
  rejectNavigation(new Error("late navigation failure")); await pending;
  assert.equal(status.textContent, "Opening saved evidence…");
  assert.equal(h.find("tct-comparison").textContent, "");
  stop(); assert.equal(h.listeners(), 0);
});

test("navigation errors stay visible and do not retry another capture", async () => {
  const h = fixture(); let attempts = 0;
  const stop = tools.renderComparison(h.container, behaviorComparison(), {onOpenEvidence: () => { attempts++; throw new Error("Exact service evidence is missing"); }});
  await h.find("tct-open-evidence").fire("click");
  assert.match(h.find("tct-evidence-status").textContent, /Exact service evidence is missing/);
  assert.equal(attempts, 1);
  assert.equal(h.find("tct-open-evidence").disabled, false);
  stop();
});

test("explicit saved-pair search passes exactly two IDs and renders only confirmed pair results", async () => {
  const h = fixture(), calls = [], opened = [], value = behaviorComparison();
  const stop = tools.renderComparison(h.container, value, {onOpenEvidence: ref => opened.push(ref), api: (...args) => {
    calls.push(args);
    return {scope: "selected-snapshots", snapshotIds: [first, second], returned: 2, recordsScanned: 2, limited: true, warnings: [], results: [
      {storage: "viewer", snapshotId: first, service: "cb-app", serviceInstanceId: "app-instance", segmentId: "before-segment", category: "method",
        className: "example.UsageEntitlementClient", methodName: "fetchEntitlements", count: 1},
      {storage: "viewer", snapshotId: cliId, service: "unselected-service", category: "method", className: "OtherCapture", count: 1}
    ]};
  }});
  const query = h.find("tct-pair-search"); query.value = "UsageEntitlementClient fetchEntitlements"; await query.fire("input");
  assert.equal(calls.length, 0);
  await h.find("tct-pair-search-form").fire("submit");
  assert.deepEqual(calls, [["/api/capture-tools/search", "POST", {query: "UsageEntitlementClient fetchEntitlements", category: "all", limit: 50, snapshotIds: [first, second]}]]);
  assert.match(h.find("tct-pair-file-results").textContent, /Before — search results.*UsageEntitlementClient.*After — search results.*No matching observation returned/);
  assert.doesNotMatch(h.find("tct-pair-file-results").textContent, /OtherCapture|unselected-service/);
  assert.match(h.find("tct-pair-file-status").textContent, /Bounded or incomplete/);
  await h.find("tct-open-pair-result").fire("click");
  assert.deepEqual(opened, [{snapshotId: first, service: "cb-app", serviceInstanceId: "app-instance", segmentId: "before-segment", className: "example.UsageEntitlementClient", methodName: "fetchEntitlements"}]);
  stop(); assert.equal(h.listeners(), 0);
});

test("an older backend cannot silently present global search results as pair results", async () => {
  const h = fixture(); let calls = 0;
  const stop = tools.renderComparison(h.container, behaviorComparison(), {api: () => {
    calls++; return {returned: 1, results: [{storage: "viewer", snapshotId: first, className: "MustNotShow"}]};
  }});
  h.find("tct-pair-search").value = "fetchEntitlements";
  await h.find("tct-pair-search-form").fire("submit");
  assert.equal(calls, 1);
  assert.match(h.find("tct-pair-file-status").textContent, /did not confirm pair-scoped search/);
  assert.equal(h.find("tct-pair-file-results").textContent, "");
  stop();
});

test("editing query or disposing invalidates pending pair search and pending evidence navigation", async () => {
  const h = fixture(); let resolveSearch, resolveNavigation, navigationGuard;
  const stop = tools.renderComparison(h.container, behaviorComparison(), {
    api: () => new Promise(resolve => { resolveSearch = resolve; }),
    onOpenEvidence: (ref, options) => { navigationGuard = options.isCurrent; return new Promise(resolve => { resolveNavigation = resolve; }); }
  });
  const query = h.find("tct-pair-search"); query.value = "fetchEntitlements";
  const searching = h.find("tct-pair-search-form").fire("submit"); await settle();
  query.value = "another"; await query.fire("input");
  resolveSearch({scope: "selected-snapshots", snapshotIds: [first, second], returned: 99, recordsScanned: 2, results: []}); await searching;
  assert.equal(h.find("tct-pair-file-status").textContent, "");
  query.value = ""; await query.fire("input");
  const navigating = h.find("tct-open-evidence").fire("click"); await settle();
  assert.equal(navigationGuard(), true);
  stop(); assert.equal(navigationGuard(), false);
  resolveNavigation(); await navigating;
  assert.equal(h.listeners(), 0);
});

test("local status, outcome and evidence-reference limits are disclosed next to the affected side", () => {
  const h = fixture(), value = behaviorComparison();
  const dependency = value.behavior.dependencies[0], outcome = value.behavior.outcomes[0];
  dependency.before.statuses = Array.from({length: 10}, (_, i) => ({httpStatus: 400 + i, count: 1}));
  dependency.before.statusesOmitted = 3;
  dependency.before.evidence = Array.from({length: 19}, (_, i) => ({...dependency.before.evidence[0], segmentId: `segment-${i}`}));
  outcome.before = Array.from({length: 18}, (_, i) => ({...outcome.before[0], httpStatus: 400 + i}));
  const stop = tools.renderComparison(h.container, value, {onOpenEvidence: () => {}});
  assert.match(h.find("tct-behavior-dependencies").textContent, /5 additional status groups not shown/);
  assert.match(h.find("tct-behavior-dependencies").textContent, /3 additional segment references not shown/);
  assert.match(h.find("tct-behavior-outcomes").textContent, /2 additional segment outcomes not shown/);
  stop(); assert.equal(h.listeners(), 0);
});

test("findings focus their paired evidence with keyboard controls, and search or show-all clears focus", async () => {
  const h = fixture(), stop = tools.renderComparison(h.container, behaviorComparison());
  const findings = h.find("tct-findings").all().filter(node => node.classList.contains("tct-inspect-difference"));
  assert.equal(findings.length, 3);
  assert.equal(findings[1].tagName, "button");
  assert.match(findings[1].getAttribute("aria-label"), /ems\.local/);
  await findings[1].fire("click");
  assert.equal(h.find("tct-focus-bar").hidden, false);
  assert.equal(h.find("tct-behavior-rows").all().filter(node => node.classList.contains("tct-behavior-row")).length, 1);
  assert.match(h.find("tct-focus-label").textContent, /ems\.local/);
  assert.equal(h.find("tct-behavior-rows").querySelector(".tct-behavior-row").focused, true);
  await h.find("tct-clear-focus").fire("click");
  assert.equal(h.find("tct-focus-bar").hidden, true);
  assert.ok(h.find("tct-behavior-outcomes")); assert.ok(h.find("tct-behavior-methods"));
  await findings[2].fire("click");
  h.find("tct-pair-search").value = "catalog.local"; await h.find("tct-pair-search").fire("input");
  assert.equal(h.find("tct-focus-bar").hidden, true);
  assert.equal(h.find("tct-common-context").open, true);
  stop(); assert.equal(h.listeners(), 0);
});

test("pair search displays HTTP operation and path plus owning request without query or payload values", async () => {
  const h = fixture();
  const stop = tools.renderComparison(h.container, behaviorComparison(), {api: () => ({
    scope: "selected-snapshots", snapshotIds: [first, second], returned: 1, recordsScanned: 2, results: [{
      storage: "viewer", snapshotId: first, service: "cb-app", category: "destination", destination: "ems.local:8082",
      operation: "GET", path: "/subscriptions/new/entitlements?token=query-secret", count: 1,
      request: {method: "POST", path: "/cart_sub_create?email=private-value", body: "payload-secret", queryParameters: {token: "raw-secret"}}
    }]
  })});
  h.find("tct-pair-search").value = "/subscriptions/new/entitlements";
  await h.find("tct-pair-search-form").fire("submit");
  assert.match(h.find("tct-pair-file-results").textContent, /cb-app.*ems\.local:8082.*GET.*\/subscriptions\/new\/entitlements/);
  assert.match(h.find("tct-pair-file-results").textContent, /Request: POST \/cart_sub_create/);
  assert.doesNotMatch(h.find("tct-pair-file-results").textContent, /query-secret|private-value|payload-secret|raw-secret/);
  stop();
});

test("grouped method review initially shows only outermost observed parent and lazily expands descendants", async () => {
  const h = fixture(), stop = tools.renderComparison(h.container, nestedBehaviorComparison());
  const section = h.find("tct-behavior-methods"), disclosure = h.find("tct-nested-methods");
  assert.equal(section.all().filter(node => node.classList.contains("tct-behavior-row")).length, 1);
  assert.match(section.textContent, /example\.A\.A\(\)/);
  assert.doesNotMatch(section.textContent, /example\.[BCE]\.[BCE]\(\)/);
  assert.equal(disclosure.open, false);
  assert.match(disclosure.textContent, /3 nested method differences/);
  assert.match(section.textContent, /outermost observed parents/);
  assert.match(section.textContent, /parent's own invocation count may be unchanged/);
  assert.match(h.find("tct-findings").textContent, /Nested behavior differs.*example\.A/);
  disclosure.open = true; await disclosure.fire("toggle");
  assert.equal(section.all().filter(node => node.classList.contains("tct-behavior-row")).length, 4);
  assert.match(disclosure.textContent, /example\.B\.B\(\).*example\.C\.C\(\).*example\.E\.E\(\)/);
  assert.match(disclosure.textContent, /not source-code edits or a shared execution order/);
  const before = section.textContent;
  disclosure.open = false; await disclosure.fire("toggle"); disclosure.open = true; await disclosure.fire("toggle");
  assert.equal(section.textContent, before, "Reopening does not duplicate nested rows");
  stop(); assert.equal(h.listeners(), 0);
});

test("searching a grouped descendant keeps its parent and opens only the matching nested evidence", async () => {
  const h = fixture(), opened = [], stop = tools.renderComparison(h.container, nestedBehaviorComparison(), {onOpenEvidence: ref => opened.push(ref)});
  h.find("tct-pair-search").value = "example.E"; await h.find("tct-pair-search").fire("input");
  const section = h.find("tct-behavior-methods"), disclosure = h.find("tct-nested-methods");
  assert.equal(disclosure.open, true);
  assert.match(section.textContent, /example\.A\.A\(\).*example\.E\.E\(\)/);
  assert.doesNotMatch(section.textContent, /example\.[BC]\.[BC]\(\)/);
  assert.match(disclosure.textContent, /3 nested method differences · 1 match search/);
  const child = h.find("tct-nested-method-list").querySelector(".tct-behavior-row");
  const buttons = child.all().filter(node => node.classList.contains("tct-open-evidence"));
  await buttons[0].fire("click"); await buttons[1].fire("click");
  assert.deepEqual(opened.map(row => [row.snapshotId, row.className, row.methodName]), [[first, "example.E", "E"], [second, "example.E", "E"]]);
  h.find("tct-pair-search").value = ""; await h.find("tct-pair-search").fire("input");
  assert.equal(h.find("tct-nested-methods").open, false);
  assert.equal(h.find("tct-nested-method-list"), null);
  stop(); assert.equal(h.listeners(), 0);
});

test("nested method lists are bounded, progressively expanded, and disclose missing descendants", async () => {
  const h = fixture(), value = nestedBehaviorComparison(), parent = value.behavior.methods[0];
  parent.nestedChanges = Array.from({length: 205}, (_, index) => ({...parent.nestedChanges[0], methodName: `child${index}`}));
  parent.nestedChangedCount = 212; parent.nestedOmitted = 7;
  const stop = tools.renderComparison(h.container, value);
  const disclosure = h.find("tct-nested-methods"); disclosure.open = true; await disclosure.fire("toggle");
  assert.equal(h.find("tct-nested-method-list").childNodes.length, 8);
  assert.match(disclosure.textContent, /212 nested method differences/);
  assert.match(disclosure.textContent, /12 nested method differences omitted/);
  await h.find("tct-more-nested-methods").fire("click");
  assert.equal(h.find("tct-nested-method-list").childNodes.length, 28);
  h.find("tct-pair-search").value = "child199"; await h.find("tct-pair-search").fire("input");
  assert.equal(h.find("tct-nested-methods").open, true);
  assert.match(h.find("tct-nested-method-list").textContent, /child199/);
  stop(); assert.equal(h.listeners(), 0);
});

test("flat method evidence remains separate when no recorded parent grouping is supplied", () => {
  const h = fixture(), value = nestedBehaviorComparison(), parent = value.behavior.methods[0];
  value.behavior.methods = parent.nestedChanges;
  const stop = tools.renderComparison(h.container, value);
  assert.equal(h.find("tct-behavior-methods").all().filter(node => node.classList.contains("tct-behavior-row")).length, 3);
  assert.equal(h.find("tct-nested-methods"), null);
  stop();
});
