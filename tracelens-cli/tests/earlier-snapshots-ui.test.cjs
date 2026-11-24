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

function harness({files = [], browserPins = [], earlier = null, report = null, copyResult = null} = {}) {
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
  const calls = [], opened = [], bodies = [];
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
      if (path === "/api/viewer-context") return {namespace: "saved-captures-test", mode: "federated"};
      if (path === "/api/snapshots") return {snapshots: listing, count: listing.length, warnings: [], ...(earlier ? {earlier} : {})};
      if (path === "/api/snapshots/other-configurations") { if (report instanceof Error) throw report; return report; }
      if (path === "/api/snapshots/copy-from" && method === "POST") {
        bodies.push(arguments[3]);
        if (copyResult.copied.length) { listing.push(...copyResult.copied.map(row => viewerSummary(capture({id: row.id, name: row.name})))); earlier = null; }
        return copyResult;
      }
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
  return {ui, calls, opened, listing, legacy, bodies, get: id => nodes.get(id), rows: () => nodes.get("trace-rows").childNodes};
}


const NS = "b".repeat(64);
const OTHER_ID = "99999999-89ab-cdef-0123-456789abcdef";
const box = h => h.get("earlier-snapshots");
const press = (h, label) => box(h).all().find(node => node.tagName === "BUTTON" && node.textContent === label);
const report = () => ({configurations: [{namespace: NS, count: 2, latestSavedAt: 5000,
  snapshots: [{id: OTHER_ID, name: "UBB-4005 BEFORE", savedAt: 5000, large: false}, {id: FILE_ID, name: "<b>Large one</b>", savedAt: 4000, large: true}]}],
  snapshotCount: 2, omittedConfigurations: 0, warnings: []});

test("an empty Saved captures list says that earlier snapshots exist, without loading them", async () => {
  const h = harness({earlier: {snapshotCount: 32, configurations: 3}, report: report()});
  await h.ui.init();
  assert.equal(box(h).classList.contains("hidden"), true, "the notice belongs to Saved captures only");
  h.ui.changeMode("pins"); await settle();
  assert.equal(box(h).classList.contains("hidden"), false);
  assert.match(box(h).textContent, /32 snapshots saved under earlier viewer configurations/);
  assert.match(box(h).textContent, /Nothing was deleted/);
  assert.ok(press(h, "Show earlier snapshots"));
  assert.equal(h.calls.some(call => call[2] === "/api/snapshots/other-configurations"), false, "the full report waits for the user");
  assert.equal(h.get("empty").classList.contains("hidden"), false, "the normal empty message stays");
});

test("no notice appears when there is nothing earlier or the viewer does not report it", async () => {
  for (const earlier of [null, {snapshotCount: 0, configurations: 0}, {snapshotCount: "many"}]) {
    const h = harness({earlier});
    await h.ui.init(); h.ui.changeMode("pins"); await settle();
    assert.equal(box(h).classList.contains("hidden"), true);
    assert.equal(box(h).childNodes.length, 0);
  }
});

test("showing earlier snapshots lists their names as text and offers an explicit copy", async () => {
  const h = harness({earlier: {snapshotCount: 2, configurations: 1}, report: report()});
  await h.ui.init(); h.ui.changeMode("pins"); await settle();
  press(h, "Show earlier snapshots").fire("click"); await settle();
  assert.match(box(h).textContent, /2 snapshots · last saved/);
  assert.match(box(h).textContent, /UBB-4005 BEFORE/);
  assert.match(box(h).textContent, /<b>Large one<\/b> · large/, "names render as text, never as markup");
  assert.ok(press(h, "Copy into this configuration"));
  assert.equal(h.calls.some(call => call[1] === "POST"), false, "nothing is copied until the user asks");
});

test("copying sends only the configuration id, reports the result and refreshes the list", async () => {
  const h = harness({earlier: {snapshotCount: 2, configurations: 1}, report: report(),
    copyResult: {namespace: NS, copied: [{id: OTHER_ID, name: "UBB-4005 BEFORE"}], skipped: [{id: FILE_ID, name: "Large one", reason: "capture limit reached (20); delete or move unwanted evidence manually"}]}});
  await h.ui.init(); h.ui.changeMode("pins"); await settle();
  press(h, "Show earlier snapshots").fire("click"); await settle();
  press(h, "Copy into this configuration").fire("click"); await settle();
  assert.deepEqual(clone(h.bodies), [{namespace: NS}]);
  assert.match(h.get("list-message").textContent, /1 snapshot copied\. The originals were left in place\. 1 not copied: capture limit reached/);
  assert.equal(h.rows().length, 1, "the copied snapshot appears in the list");
  assert.equal(box(h).classList.contains("hidden"), true, "the notice goes once nothing earlier is reported");
});

test("a report with a malformed configuration id offers no copy, and a failed report says so", async () => {
  const bad = report(); bad.configurations[0].namespace = "../" + NS;
  const h = harness({earlier: {snapshotCount: 2, configurations: 1}, report: bad});
  await h.ui.init(); h.ui.changeMode("pins"); await settle();
  press(h, "Show earlier snapshots").fire("click"); await settle();
  assert.equal(press(h, "Copy into this configuration"), undefined);
  assert.match(box(h).textContent, /could not be read/);
  const failed = harness({earlier: {snapshotCount: 2, configurations: 1}, report: new Error("offline")});
  await failed.ui.init(); failed.ui.changeMode("pins"); await settle();
  press(failed, "Show earlier snapshots").fire("click"); await settle();
  assert.match(failed.get("list-message").textContent, /cannot list earlier snapshots/);
  assert.ok(press(failed, "Show earlier snapshots"), "the user can try again");
});
