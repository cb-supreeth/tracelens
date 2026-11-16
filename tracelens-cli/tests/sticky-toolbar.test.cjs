const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const sticky = require("../tracelens_cli/web/sticky-toolbar.js");

function fixture({resize = true, mutations = true, header = true, toolbar = true} = {}) {
  const values = new Map(), writes = [], listeners = new Map(), observers = {resize: [], mutation: []};
  const style = {
    setProperty(name, value, priority = "") { values.set(name, {value, priority}); writes.push([name, value]); },
    getPropertyValue(name) { return values.get(name)?.value || ""; },
    getPropertyPriority(name) { return values.get(name)?.priority || ""; },
    removeProperty(name) { values.delete(name); }
  };
  const make = (height, parentElement = null) => ({
    size: height, parentElement, hidden: false, rendered: true,
    getBoundingClientRect() { return {height: this.size}; },
    getClientRects() {
      for (let node = this; node; node = node.parentElement) {
        if (node.hidden || !node.rendered) return [];
      }
      return [{}];
    }
  });
  const html = make(800); html.style = style;
  const body = make(800, html), detail = make(500, body);
  const headerNode = make(55.5, body), toolbarNode = make(81, detail);
  const nodes = new Map([
    ["body > header", header ? headerNode : null],
    ["#segment-toolbar", toolbar ? toolbarNode : null],
    ["#detail-view", detail]
  ]);
  const document = {documentElement: html, querySelector: selector => nodes.get(selector)};
  const observer = kind => class {
    constructor(callback) { this.callback = callback; this.targets = []; observers[kind].push(this); }
    observe(element, options) { this.targets.push({element, options}); }
    disconnect() { this.targets = []; }
    emit() { this.callback([]); }
  };
  const view = {
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name, callback) { if (listeners.get(name) === callback) listeners.delete(name); }
  };
  if (resize) view.ResizeObserver = observer("resize");
  if (mutations) view.MutationObserver = observer("mutation");
  return {document, view, header: headerNode, toolbar: toolbarNode, detail, nodes, values, writes, listeners, observers,
    value: name => style.getPropertyValue(name),
    dispatch: (name, event) => listeners.get(name)?.(event)};
}

test("initial border-box metrics round up to avoid covering fractional pixels", () => {
  const f = fixture();
  sticky.mount(f.document, f.view);
  assert.equal(f.value("--viewer-header-height"), "56px");
  assert.equal(f.value("--segment-toolbar-height"), "81px");
  assert.deepEqual(f.observers.resize[0].targets.map(target => target.element), [f.header, f.toolbar]);
});

test("sticky back is offered only after the top breadcrumb scrolls behind the header", () => {
  const f = fixture();
  let bottom = 150;
  const navigation = {hidden: false, getBoundingClientRect: () => ({height: 32, bottom}),
    getClientRects: () => f.detail.rendered ? [{}] : []};
  const button = {hidden: true};
  f.nodes.set("#segment-navigation", navigation);
  f.nodes.set("#segment-back", button);
  const controller = sticky.mount(f.document, f.view);
  assert.equal(button.hidden, true);
  bottom = 56; f.dispatch("scroll");
  assert.equal(button.hidden, false);
  bottom = 100; f.dispatch("scroll");
  assert.equal(button.hidden, true);
  f.header.size = 120; f.dispatch("resize");
  assert.equal(button.hidden, false, "wrapped viewer header changes the occlusion boundary");
  f.detail.rendered = false; controller.update();
  assert.equal(button.hidden, true);
  f.detail.rendered = true; bottom = NaN; controller.update();
  assert.equal(button.hidden, true);
  controller.destroy();
  assert.equal(f.listeners.has("scroll"), false);
});

test("wrapped header and toolbar heights update on ResizeObserver without repeated style writes", () => {
  const f = fixture(), controller = sticky.mount(f.document, f.view);
  f.header.size = 97; f.toolbar.size = 119.1;
  f.observers.resize[0].emit();
  assert.equal(f.value("--viewer-header-height"), "97px");
  assert.equal(f.value("--segment-toolbar-height"), "120px");
  const count = f.writes.length;
  f.observers.resize[0].emit(); controller.update();
  assert.equal(f.writes.length, count);
});

test("hidden toolbar and hidden ancestor clear the offset and visible navigation restores it", () => {
  const f = fixture();
  sticky.mount(f.document, f.view);
  const changes = f.observers.mutation[0];
  assert.ok(changes.targets.some(target => target.element === f.detail));
  assert.equal(changes.targets.length, 3);
  assert.ok(changes.targets.every(target => !target.options.subtree));
  f.detail.rendered = false; changes.emit();
  assert.equal(f.value("--segment-toolbar-height"), "0px");
  f.detail.rendered = true; changes.emit();
  assert.equal(f.value("--segment-toolbar-height"), "81px");
  f.toolbar.hidden = true; changes.emit();
  assert.equal(f.value("--segment-toolbar-height"), "0px");
});

test("missing elements and invalid measurements are safe and later replacement is observed", () => {
  const f = fixture({header: false, toolbar: false}), controller = sticky.mount(f.document, f.view);
  assert.equal(f.value("--viewer-header-height"), "0px");
  assert.equal(f.value("--segment-toolbar-height"), "0px");
  f.nodes.set("body > header", f.header);
  f.nodes.set("#segment-toolbar", f.toolbar);
  f.header.size = NaN; f.toolbar.size = -1;
  controller.update();
  assert.equal(f.value("--viewer-header-height"), "0px");
  assert.equal(f.value("--segment-toolbar-height"), "0px");
  assert.equal(f.observers.resize[0].targets.length, 2);
  f.header.size = Infinity; f.toolbar.size = 80;
  controller.update();
  assert.equal(f.value("--viewer-header-height"), "0px");
  assert.equal(f.value("--segment-toolbar-height"), "80px");
  assert.doesNotThrow(() => sticky.mount(null, null).destroy());
  assert.doesNotThrow(() => sticky.mount({}, {}).update());
});

test("resize and explicit update provide a fallback when observers are unavailable", () => {
  const f = fixture({resize: false, mutations: false}), controller = sticky.mount(f.document, f.view);
  f.header.size = 100; f.toolbar.size = 60;
  f.dispatch("resize");
  assert.equal(f.value("--viewer-header-height"), "100px");
  assert.equal(f.value("--segment-toolbar-height"), "60px");
  f.detail.rendered = false; controller.update();
  assert.equal(f.value("--segment-toolbar-height"), "0px");
});

test("destroy disconnects observers/listeners, restores previous properties, and is idempotent", () => {
  const f = fixture();
  f.document.documentElement.style.setProperty("--viewer-header-height", "64px", "important");
  const controller = sticky.mount(f.document, f.view);
  controller.destroy(); controller.destroy();
  assert.equal(f.value("--viewer-header-height"), "64px");
  assert.equal(f.values.get("--viewer-header-height").priority, "important");
  assert.equal(f.value("--segment-toolbar-height"), "");
  assert.equal(f.listeners.size, 0);
  assert.equal(f.observers.resize[0].targets.length, 0);
  assert.equal(f.observers.mutation[0].targets.length, 0);
  const count = f.writes.length;
  f.header.size = 200; f.observers.resize[0].emit(); controller.update();
  assert.equal(f.writes.length, count);
});

test("bfcache navigation retains observation and refreshes offsets on pageshow", () => {
  const f = fixture();
  sticky.mount(f.document, f.view);
  f.dispatch("pagehide", {persisted: true});
  f.header.size = 100;
  f.dispatch("pageshow");
  assert.equal(f.value("--viewer-header-height"), "100px");
  f.dispatch("pagehide", {persisted: false});
  assert.equal(f.listeners.size, 0);
});

test("browser entry point waits for DOM ready then mounts once", () => {
  const f = fixture(), ready = [];
  f.document.readyState = "loading";
  f.document.addEventListener = (name, callback, options) => ready.push({name, callback, options});
  const context = {...f.view, document: f.document};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../tracelens_cli/web/sticky-toolbar.js"), "utf8"), context);
  assert.equal(f.value("--viewer-header-height"), "");
  assert.equal(ready[0].name, "DOMContentLoaded");
  assert.equal(ready[0].options.once, true);
  ready[0].callback();
  assert.equal(f.value("--viewer-header-height"), "56px");
  assert.equal(typeof context.TraceLensStickyToolbar.controller.destroy, "function");
  context.TraceLensStickyToolbar.controller.destroy();
});
