"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const requests = require("../tracelens_cli/web/request-model.js");
const browserStore = require("../tracelens_cli/web/browser-store.js");

const FILE_ID = "01234567-89ab-cdef-0123-456789abcdef";
const TRACE = "11111111111111111111111111111111";
const clone = value => JSON.parse(JSON.stringify(value));
const settle = async () => { for (let i = 0; i < 3; i++) await new Promise(resolve => setImmediate(resolve)); };
const segment = (extra = {}) => ({traceId: TRACE, segmentId: "root", service: "cb-app", serviceInstanceId: "saved-instance",
  entryChannel: "HTTP", httpMethod: "GET", path: "/api/features", httpStatus: 200, status: "COMPLETED",
  startedAtMillis: 1000, durationMs: 42, eventCount: 1,
  events: [{type: "LINE", line: 42, sourceLine: "saved source;", attributes: {evidence: "preserve me"}}], ...extra});

function capture(extra = {}, segments = [segment()]) {
  return {id: FILE_ID, schemaVersion: 1, storage: "viewer", name: "Saved request", traceId: TRACE,
    capturedAt: 2000, savedAt: 3000, partial: false, warnings: [],
    request: requests.groupTraces(segments)[0], journey: {traceId: TRACE, segments, links: []}, segments, ...extra};
}

// Match viewer_snapshots._summary: the list response contains scalar request
// metadata and service names, never request.root, request.segments or source.
function viewerSummary(snapshot) {
  const request = Object.fromEntries(Object.entries(snapshot.request)
    .filter(([, value]) => value === null || ["string", "boolean", "number"].includes(typeof value)));
  request.services = (snapshot.request.services || []).filter(value => typeof value === "string");
  return {...Object.fromEntries(["id", "schemaVersion", "storage", "name", "traceId", "capturedAt", "savedAt", "partial", "warnings"]
    .map(key => [key, clone(snapshot[key])])), request, segmentCount: snapshot.segments.length,
    sizeBytes: Buffer.byteLength(JSON.stringify(snapshot))};
}

function harness({files = [], browserPins = []} = {}) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.childNodes = []; this.className = ""; this.value = "";
      this.listeners = {}; this.attributes = {}; this.parentElement = null;
      this.classList = {
        contains: name => this.className.split(" ").includes(name),
        add: (...names) => { this.className = [...new Set([...this.className.split(" ").filter(Boolean), ...names])].join(" "); },
        toggle: (name, force) => {
          const classes = new Set(this.className.split(" ").filter(Boolean));
          if (force === undefined ? !classes.has(name) : force) classes.add(name); else classes.delete(name);
          this.className = [...classes].join(" ");
        }
      };
    }
    set innerHTML(_) { throw new Error("Saved metadata must be rendered as text"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...children) {
      for (const child of children) {
        if (child.parentElement) child.parentElement.childNodes = child.parentElement.childNodes.filter(node => node !== child);
        child.parentElement = this; this.childNodes.push(child);
      }
    }
    appendChild(child) { this.append(child); return child; }
    replaceChildren(...children) { this.text = ""; this.childNodes = []; this.append(...children); }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    fire(name) {
      this["on" + name]?.({target: this});
      (this.listeners[name] || []).forEach(handler => handler({target: this}));
    }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    focus() {}
  }
  const nodes = new Map();
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  for (const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const element = new Element(match[1]);
    element.type = /\btype="([^"]+)"/.exec(match[2])?.[1] || "";
    element.className = /\bclass="([^"]+)"/.exec(match[2])?.[1] || "";
    nodes.set(match[3], element);
  }
  const calls = [], opened = [];
  const listing = files.map(viewerSummary);
  const legacy = browserPins.map(pin => browserStore.preparePin(pin, pin.capturedAt));
  const browser = {
    async listViews() { calls.push(["browser", "listViews"]); return []; },
    async listPins() { calls.push(["browser", "listPins"]); return legacy.map(pin => pin.summary); },
    async getPin(id) { calls.push(["browser", "getPin", id]); return legacy.find(pin => pin.snapshot.id === id)?.snapshot; },
    async savePin() { assert.fail("Opening saved captures must not write browser storage"); },
    async renamePin() { assert.fail("Listing saved captures must not rename evidence"); },
    async deletePin() { assert.fail("Listing saved captures must not delete evidence"); }
  };
  const context = {
    window: {TraceLensRequests: requests, TraceLensBrowserStore: {create: () => browser},
      TraceLensViewerStore: require("../tracelens_cli/web/viewer-store.js")},
    document: {getElementById: id => { assert.ok(nodes.has(id), "Unknown element " + id); return nodes.get(id); },
      createElement: tag => new Element(tag)},
    Option: function (label, value) { const node = new Element("option"); node.textContent = label; node.value = value; return node; },
    URLSearchParams, Date, setTimeout, clearTimeout, confirm: () => assert.fail("No automatic deletion")
  };
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/request-ui.js"), "utf8"), context);
  const ui = new context.window.TraceLensRequestUI({
    async api(path, method = "GET") {
      calls.push(["viewer", method, path]);
      assert.equal(method, "GET", "Displaying saved captures is read-only");
      if (path === "/api/viewer-context") return {namespace: "saved-captures-test", mode: "federated"};
      if (path === "/api/snapshots") return {snapshots: listing, count: listing.length, warnings: []};
      const file = files.find(snapshot => path === "/api/snapshots/" + snapshot.id);
      if (file) return file;
      assert.fail("Saved capture UI must not request live evidence: " + path);
    },
    async openPin(snapshot) { opened.push(snapshot); },
    async openRequest() { assert.fail("Saved captures must not open live requests"); },
    async openSegment() { assert.fail("Saved captures must not fetch live segments"); },
    async snapshot() { assert.fail("Listing saved captures must not collect evidence"); },
    async refreshLive() { assert.fail("Saved captures do not refresh live evidence"); },
    async copySnapshotLink() {}
  });
  return {ui, calls, opened, listing, legacy, get: id => nodes.get(id), rows: () => nodes.get("trace-rows").childNodes};
}

const action = (node, label) => node.all().find(child => child.tagName === "BUTTON" && child.textContent === label);
function assertCountMatchesRows(h, count) {
  assert.equal(h.get("pin-count").textContent, String(count));
  assert.equal(h.rows().length, count, "Every counted capture must have a visible row");
  assert.equal(h.get("empty").classList.contains("hidden"), count !== 0);
  assert.doesNotMatch(h.get("list-message").textContent, /Cannot read|undefined|null/);
}

test("compact viewer-file summaries render and open saved evidence with no root or segment export", async () => {
  const saved = capture(), before = clone(saved), h = harness({files: [saved]});
  assert.equal(h.listing[0].request.root, undefined);
  assert.equal(h.listing[0].request.segments, undefined);
  assert.doesNotMatch(JSON.stringify(h.listing), /saved source|preserve me/);
  h.ui.setServices(["cb-app", "cb-ems"]);
  await h.ui.init();
  h.ui.filters.path = "/does-not-match";
  assert.doesNotThrow(() => h.ui.changeMode("pins"));
  assertCountMatchesRows(h, 1);
  assert.match(h.rows()[0].textContent, /Viewer file.*HTTP 200/);
  assert.doesNotMatch(h.rows()[0].textContent, /cb-app capture missing|Partial capture/);
  assert.ok(action(h.rows()[0], "Copy snapshot link"));
  assert.ok(action(h.rows()[0], "Rename"));
  assert.ok(action(h.rows()[0], "Delete snapshot"));
  assert.equal(h.calls.some(call => call[2] === "/api/snapshots/" + saved.id), false, "Listing must not load full snapshots");
  action(h.rows()[0], saved.name).fire("click"); await settle();
  assert.equal(h.opened[0], saved);
  assert.deepEqual(saved, before, "Display and opening preserve the stored source and graph evidence");
  assert.equal(h.calls.filter(call => call[2] === "/api/snapshots/" + saved.id).length, 1);
});

test("Performance tab gets unfiltered non-job summaries and saved metadata without loading evidence", async () => {
  const h = harness({files: [capture()]});
  await h.ui.init();
  const updates = [], modes = [];
  h.ui.options.onPerformanceSources = value => updates.push(value);
  h.ui.options.onModeChange = value => modes.push(value);
  h.ui.setTraces({traces: [segment(), segment({traceId: "2".repeat(32), segmentId: "job", entryChannel: "SCHEDULER"})], partial: true});
  h.ui.filters.path = "/hidden-on-live";
  const before = h.calls.length;
  h.ui.changeMode("performance", {refreshLive: false});
  assert.equal(h.calls.length, before, "Entering Performance must not load graphs or full snapshots");
  assert.equal(modes.at(-1), "performance");
  assert.equal(updates.at(-1).requests.length, 1);
  assert.equal(updates.at(-1).requests[0].path, "/api/features");
  assert.equal(updates.at(-1).pins.length, 1);
  assert.equal(updates.at(-1).partial, true);
  assert.equal(h.get("performance-tab").attributes["aria-pressed"], "true");
  assert.equal(h.get("performance-insights").classList.contains("hidden"), false);
  for (const id of ["live-controls", "workflow-tools", "results-heading", "request-table", "empty"])
    assert.equal(h.get(id).classList.contains("hidden"), true, id);
  h.ui.changeMode("live", {refreshLive: false});
  assert.equal(h.ui.filters.path, "/hidden-on-live", "Performance must not reset the live filters");
  assert.equal(h.get("performance-insights").classList.contains("hidden"), true);
  for (const id of ["live-controls", "workflow-tools", "results-heading", "request-table"])
    assert.equal(h.get(id).classList.contains("hidden"), false, id);
});

test("saved partial roots remain explicit when the omitted representative root status is unknown", async () => {
  const child = segment({service: "cb-ems", parentSpanId: "1234567890abcdef", httpStatus: 503});
  const saved = capture({name: "Downstream evidence", partial: true}, [child]);
  const h = harness({files: [saved]});
  assert.equal(h.listing[0].request.rootMissing, true);
  assert.equal(h.listing[0].request.root, undefined);
  h.ui.setServices(["cb-app", "cb-ems"]); await h.ui.init();
  assert.doesNotThrow(() => h.ui.changeMode("pins"));
  assertCountMatchesRows(h, 1);
  assert.match(h.rows()[0].textContent, /Upstream segment unavailable/);
  assert.match(h.rows()[0].textContent, /Observed HTTP unknown/);
  assert.doesNotMatch(h.rows()[0].textContent, /HTTP 200|Root HTTP 503/);
});

test("saved scheduled jobs render without inventing completion from omitted root metadata", async () => {
  const job = segment({entryChannel: "SCHEDULER", httpMethod: "", path: "", httpStatus: null, lensId: "Nightly cleanup"});
  const h = harness({files: [capture({name: "Saved scheduled run"}, [job])]});
  assert.equal(h.listing[0].request.channel, "SCHEDULER");
  assert.equal(h.listing[0].request.outcome, "unknown");
  assert.equal(h.listing[0].request.root, undefined);
  await h.ui.init();
  assert.doesNotThrow(() => h.ui.changeMode("pins"));
  assertCountMatchesRows(h, 1);
  assert.match(h.rows()[0].textContent, /Scheduled execution/);
  assert.doesNotMatch(h.rows()[0].textContent, /Execution completed|HTTP 200/);
});

test("saved comparison selection is ordered, limited to two viewer files and pruned when removed", async () => {
  const ids = [FILE_ID, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "cccccccc-cccc-cccc-cccc-cccccccccccc"];
  const h = harness({files: ids.map((id, i) => capture({id, name: "Saved " + i}))});
  await h.ui.init(); h.ui.changeMode("pins"); await settle();
  const choices = () => h.rows().flatMap(row => row.all()).filter(node => node.type === "checkbox");
  assert.equal(choices().length, 3);
  choices()[0].checked = true; choices()[0].fire("change");
  choices()[1].checked = true; choices()[1].fire("change");
  assert.equal(choices()[2].disabled, true);
  let selected;
  h.ui.options.compareSelected = values => { selected = values; };
  h.get("compare-selected").onclick();
  assert.deepEqual(Array.from(selected), ids.slice(0, 2));
  h.ui.pins = h.ui.pins.filter(pin => pin.id !== ids[0]); h.ui.render();
  assert.equal(h.get("compare-selected").disabled, true);
  assert.equal(h.ui.comparisonSelection.has(ids[0]), false);
  h.get("clear-comparison-selection").onclick();
  assert.equal(h.ui.comparisonSelection.size, 0);
  assert.equal(h.calls.some(call => call[1] === "POST"), false);
});

test("legacy browser summaries and missing request metadata remain counted visible and openable", async () => {
  const logical = capture({id: "legacy-logical", name: "Legacy logical pin", storage: "browser"});
  const noRequest = capture({id: "legacy-without-request", name: "Legacy evidence only", storage: "browser", request: null});
  const originals = clone([logical, noRequest]);
  const h = harness({browserPins: [logical, noRequest]});
  assert.equal(h.legacy[0].summary.request.root, undefined);
  assert.equal(h.legacy[0].summary.request.segments, undefined);
  assert.equal(h.legacy[1].summary.request, null);
  h.ui.setServices(["cb-app", "cb-ems"]); await h.ui.init();
  assert.doesNotThrow(() => h.ui.changeMode("pins"));
  assertCountMatchesRows(h, 2);
  assert.match(h.get("results-summary").textContent, /0 viewer files.*2 browser-only pins/);
  for (const original of [logical, noRequest]) {
    const row = h.rows().find(candidate => action(candidate, original.name));
    assert.ok(row, original.name + " must not disappear because list metadata is incomplete");
    assert.match(row.textContent, /Browser only/);
    assert.ok(action(row, "Save to viewer")); assert.ok(action(row, "Rename")); assert.ok(action(row, "Unpin"));
    action(row, original.name).fire("click"); await settle();
    assert.deepEqual(h.opened.at(-1), h.legacy.find(pin => pin.snapshot.id === original.id).snapshot);
  }
  assert.deepEqual([logical, noRequest], originals);
  assert.deepEqual(h.legacy.map(pin => pin.snapshot.segments), originals.map(pin => pin.segments));
  await h.ui.refreshStored(); assertCountMatchesRows(h, 2);
  assert.equal(h.calls.filter(call => call[0] === "browser" && call[1] === "getPin").length, 2);
  assert.equal(h.calls.some(call => call[0] === "viewer" && call[2].startsWith("/api/snapshots/")), false);
});

test("saved capture ingress metadata is not reinterpreted using currently connected services", async () => {
  const saved = capture({name: "EMS standalone capture"}, [segment({service: "cb-ems"})]);
  const h = harness({files: [saved]});
  assert.equal(h.listing[0].request.rootMissing, false);
  h.ui.setServices(["cb-app", "cb-ems"]); await h.ui.init();
  assert.doesNotThrow(() => h.ui.changeMode("pins"));
  assertCountMatchesRows(h, 1);
  assert.match(h.rows()[0].textContent, /HTTP 200/);
  assert.doesNotMatch(h.rows()[0].textContent, /cb-app capture missing|Partial capture|Upstream segment unavailable/);
});

test("a legacy pin without any request summary is not silently omitted from a nonempty saved count", async () => {
  const saved = capture({id: "legacy-evidence-only", name: "Evidence retained", storage: "browser", request: null});
  const h = harness({browserPins: [saved]});
  await h.ui.init(); h.ui.changeMode("pins");
  assertCountMatchesRows(h, 1);
  assert.ok(action(h.rows()[0], saved.name));
  assert.ok(action(h.rows()[0], "Unpin"));
  action(h.rows()[0], saved.name).fire("click"); await settle();
  assert.deepEqual(h.opened[0].segments, saved.segments);
  assert.equal(h.opened[0].request, null, "Unknown display metadata must not be fabricated into stored evidence");
});
