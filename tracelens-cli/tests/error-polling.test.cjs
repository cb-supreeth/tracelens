"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

test("Errors polling replaces ordinary list reads and never exports execution data", async () => {
  const nodes = new Map(), reads = [];
  let ui, tick, errors = 0;
  const context = {
    window: { TraceLensRequestUI: class {
      constructor(options) { this.options = options; this.mode = "errors"; ui = this; }
      init() {} setServices() {} setTraces() {}
      async refreshErrors() { errors++; }
    } },
    document: {
      querySelector(selector) {
        if (!nodes.has(selector)) nodes.set(selector, {classList: {toggle() {}, add() {}, remove() {}}});
        return nodes.get(selector);
      },
      querySelectorAll() { return []; },
    },
    URLSearchParams,
    setInterval(callback) { tick = callback; return 0; },
    async fetch(path) {
      reads.push(path);
      if (path === "/api/summary") return {ok: true, json: async () => ({federated: true,
        services: [], configuredServices: [], readOnly: true, traceCount: 0})};
      if (path === "/api/traces") return {ok: true, json: async () => ({traces: []})};
      throw new Error("Unexpected application read: " + path);
    },
  };
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/app.js"), "utf8"), context);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(errors, 1);
  assert.deepEqual(reads, ["/api/summary"]);
  tick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(errors, 2);
  assert.ok(!reads.includes("/api/traces"));
  ui.mode = "live";
  await ui.options.refreshLive();
  assert.equal(reads.filter(path => path === "/api/traces").length, 1);
  assert.equal(errors, 2);
  assert.ok(reads.every(path => !path.includes("export") && !path.includes("render-data")));
});
