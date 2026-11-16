"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const store = require("../tracelens_cli/web/viewer-store.js");

const ID = "01234567-89ab-cdef-0123-456789abcdef";
const snapshot = () => ({schemaVersion: 1, storage: "viewer", id: ID, name: "Evidence",
  capturedAt: 1234, traceId: "trace-a", segments: [{segmentId: "segment-a", events: [{type: "LINE", line: 42, sourceLine: "saved source"}]}],
  journey: {nodes: [{id: "node-a"}]}, partial: true, warnings: ["missing segment"]});

test("module load and store creation perform no requests storage access or automatic saves", () => {
  const context = {};
  for (const key of ["fetch", "indexedDB", "localStorage", "sessionStorage"]) {
    Object.defineProperty(context, key, {get() { throw new Error("Unexpected " + key + " access"); }});
  }
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/viewer-store.js"), "utf8"), context);
  let calls = 0;
  const backend = context.TraceLensViewerStore.create(() => { calls++; });
  assert.equal(calls, 0); assert.equal(Object.isFrozen(backend), true);
  assert.equal(context.TraceLensViewerStore.snapshotHash(ID), "#snapshot=" + ID);
  assert.equal(calls, 0);
});

test("canonical UUID identifiers and snapshot links never accept paths or URL fragments", () => {
  assert.equal(store.identifier(ID), ID); assert.equal(store.snapshotHash(ID), "#snapshot=" + ID);
  const calls = [], backend = store.create((...args) => calls.push(args));
  for (const id of [null, undefined, 12, {}, [], "", "legacy-trace-id", ID.toUpperCase(), " " + ID, ID + "\n",
    "../outside", "%2e%2e%2foutside", "/tmp/snapshot", ID + "/rename", ID + "?x=1", ID + "#fragment"]) {
    for (const action of [() => store.identifier(id), () => store.snapshotHash(id), () => backend.getPin(id),
      () => backend.renamePin(id, "name"), () => backend.deletePin(id)]) {
      assert.throws(action, /Invalid saved snapshot ID/);
    }
  }
  assert.deepEqual(calls, []);
});

test("viewer methods use exact snapshot paths methods and request bodies", async () => {
  const calls = [], payload = snapshot(), listing = {snapshots: [{id: ID}], warnings: ["partial listing"]};
  const backend = store.create(async (...args) => { calls.push(args); return args[0] === "/api/snapshots" && args.length === 1 ? listing : payload; });
  assert.equal(await backend.listPins(), listing);
  assert.equal(await backend.getPin(ID), payload);
  assert.equal(await backend.savePin(payload), payload);
  assert.equal(await backend.renamePin(ID, "New name"), payload);
  assert.equal(await backend.deletePin(ID), payload);
  assert.deepEqual(calls, [
    ["/api/snapshots"],
    ["/api/snapshots/" + ID],
    ["/api/snapshots", "POST", null, {snapshot: payload}],
    ["/api/snapshots/" + ID + "/rename", "POST", null, {name: "New name"}],
    ["/api/snapshots/" + ID, "DELETE"],
  ]);
});

test("large snapshot capture sends only request identity metadata and exposes its archive URL", async () => {
  const calls = [], request = {traceId: "trace", method: "GET", path: "/orders", services: ["shop", {name: "wrong"}],
    root: {events: [{message: "SECRET"}]}, attributes: {httpRequestBody: "SECRET"}, segments: [{segmentId: "one"}]};
  const backend = store.create(async (...args) => { calls.push(args); return {id: ID, schemaVersion: 2}; });
  const saved = await backend.capture(request, "Large trace");
  assert.equal(saved.schemaVersion, 2);
  assert.deepEqual(calls, [["/api/snapshots/capture", "POST", null, {traceId: "trace", name: "Large trace",
    request: {traceId: "trace", method: "GET", path: "/orders", services: ["shop"]}}]]);
  assert.equal(backend.downloadUrl(ID), `/api/snapshots/${ID}/download`);
  assert.throws(() => backend.downloadUrl("../outside"), /Invalid saved snapshot ID/);
});

test("oversized optional capture metadata is omitted without losing server-side evidence", async () => {
  const calls = [], backend = store.create(async (...args) => { calls.push(args); return {id: ID}; });
  await backend.capture({traceId: "trace", path: "界".repeat(5000), segments: [{events: "NEVER SEND"}]}, "Large trace");
  assert.deepEqual(calls, [["/api/snapshots/capture", "POST", null, {traceId: "trace", name: "Large trace"}]]);
});

test("archive import sends the file body directly with a finite upload and manifest budget", async () => {
  const calls = [], file = {size: 66 * 1024 * 1024, name: "capture.zip"};
  const backend = store.create(async (...args) => { calls.push(args); return {id: ID, schemaVersion: 2}; });
  await backend.importArchive(file);
  assert.deepEqual(calls, [["/api/snapshots/import", "POST", 2 * 1024 * 1024, file, "application/zip"]]);
  for (const size of [undefined, -1, 0, Infinity, 66 * 1024 * 1024 + 1]) assert.throws(() => backend.importArchive({size}), /66 MiB/);
  assert.equal(calls.length, 1, "Invalid file sizes never read or write storage");
});

test("each save posts to the collection without overwriting an existing supplied snapshot ID", async () => {
  const calls = [], input = snapshot(), original = structuredClone(input);
  const backend = store.create(async (...args) => { calls.push(args); return {id: "new-id-" + calls.length}; });
  const first = await backend.savePin(input), second = await backend.savePin(input);
  assert.notEqual(first.id, second.id);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(([path, method, query, body]) => path === "/api/snapshots" && method === "POST" && query === null && body.snapshot === input));
  assert.deepEqual(input, original);
  assert.deepEqual(calls[0][3].snapshot, original, "The transport preserves schema, capturedAt, source, and graph evidence");
});

test("malformed snapshot list envelopes fail explicitly without retries or fallback", async () => {
  for (const response of [null, undefined, false, 0, "", [], {}, {snapshots: null}, {snapshots: {}}, {snapshots: "[]"}]) {
    const calls = [];
    const backend = store.create(async (...args) => { calls.push(args); return response; });
    await assert.rejects(backend.listPins(), /Viewer snapshot storage is unavailable.*Restart the viewer/);
    assert.deepEqual(calls, [["/api/snapshots"]]);
  }
});

test("valid empty lists and full snapshot schemas are preserved without reconstruction", async () => {
  const listing = {snapshots: [], count: 0, totalBytes: 0, limits: {maxSnapshots: 20}, warnings: []};
  const payload = snapshot(), before = structuredClone(payload);
  const backend = store.create(async path => path === "/api/snapshots" ? listing : payload);
  assert.equal(await backend.listPins(), listing);
  assert.equal(await backend.getPin(ID), payload);
  assert.deepEqual(payload, before);
});

test("API failures propagate unchanged and never invoke an alternate endpoint or backend", async () => {
  const failure = new Error("Viewer storage quota exceeded"), calls = [];
  const backend = store.create(async (...args) => { calls.push(args); throw failure; });
  for (const action of [() => backend.listPins(), () => backend.getPin(ID), () => backend.savePin(snapshot()),
    () => backend.renamePin(ID, "Rename"), () => backend.deletePin(ID)]) {
    const previous = calls.length;
    await assert.rejects(action, error => error === failure);
    assert.equal(calls.length, previous + 1);
  }
  assert.equal(calls.every(([path]) => path.startsWith("/api/snapshots")), true);
});
