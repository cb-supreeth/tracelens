"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const web = path.resolve(__dirname, "../tracelens_cli/web");
const source = fs.readFileSync(path.join(web, "context-help.js"), "utf8");
const help = require("../tracelens_cli/web/context-help.js");
const keys = ["live-requests", "scheduled-jobs", "errors", "performance-insights", "saved-captures", "investigation",
  "capture-tools", "request-flow", "service-details", "code-flow", "call-tree", "timeline"];

function fixture({browser = false} = {}) {
  const created = [], timers = new Map();
  let now = 0, nextTimer = 0;
  const forbidden = name => () => assert.fail("Context help must not access " + name);
  class Target {
    constructor() { this.listeners = {}; }
    addEventListener(type, fn, options) {
      (this.listeners[type] ||= []).push({fn, capture: options === true || !!options?.capture});
    }
    removeEventListener(type, fn, options) {
      const capture = options === true || !!options?.capture;
      this.listeners[type] = (this.listeners[type] || []).filter(item => item.fn !== fn || item.capture !== capture);
    }
    fire(type, values = {}) {
      if (type === "pointerenter" || type === "mouseenter") this.hovered = true;
      if (type === "pointerleave" || type === "mouseleave") this.hovered = false;
      const event = {
        type, target: this, currentTarget: this, defaultPrevented: false, propagationStopped: false,
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() { this.propagationStopped = true; },
        ...values
      };
      for (const {fn} of [...(this.listeners[type] || [])]) fn(event);
      return event;
    }
  }
  class Element extends Target {
    constructor(tag) {
      super();
      this.tagName = tag.toUpperCase(); this.ownerDocument = document; this.childNodes = [];
      this.attributes = {}; this.style = {setProperty(name, value) { this[name] = value; }};
      this.hidden = false; this.className = ""; this.text = ""; this.hovered = false;
      this.rect = {left: 80, right: 104, top: 80, bottom: 104, width: 24, height: 24};
      this.rectReads = 0;
      this.classList = {
        contains: name => this.className.split(/\s+/).includes(name),
        add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(" "); },
        remove: (...names) => { this.className = this.className.split(/\s+/).filter(name => !names.includes(name)).join(" "); },
        toggle: (name, value) => {
          const enabled = value ?? !this.classList.contains(name);
          this.classList[enabled ? "add" : "remove"](name); return enabled;
        }
      };
      this.dataset = new Proxy({}, {
        get: (_, key) => this.getAttribute("data-" + String(key).replace(/[A-Z]/g, letter => "-" + letter.toLowerCase())),
        set: (_, key, value) => { this.setAttribute("data-" + String(key), value); return true; }
      });
      created.push(this);
    }
    get id() { return this.getAttribute("id") || ""; }
    set id(value) { this.setAttribute("id", value); }
    get parentElement() { return this.parentNode instanceof Element ? this.parentNode : null; }
    get children() { return this.childNodes; }
    get isConnected() { return document.body === this || !!document.body?.contains(this); }
    get offsetWidth() { return this.getBoundingClientRect().width; }
    get offsetHeight() { return this.getBoundingClientRect().height; }
    set innerHTML(value) {
      assert.equal(this.className, "context-help-info", "Only the fixed decorative icon uses HTML");
      assert.match(value, /^<svg\b[^>]*aria-hidden="true"[^>]*focusable="false">[\s\S]*<\/svg>$/);
      assert.doesNotMatch(value, /<(?:script|foreignObject)|\bon\w+=|(?:href|src)=/i);
      this.iconHTML = value;
    }
    set textContent(value) { this.text = String(value); this.replaceChildren(); }
    get textContent() { return this.text + this.childNodes.map(child => child.textContent).join(" "); }
    setAttribute(name, value) {
      if (name === "class") this.className = String(value);
      else this.attributes[name] = String(value);
    }
    getAttribute(name) { return name === "class" ? this.className : this.attributes[name] ?? null; }
    hasAttribute(name) { return this.getAttribute(name) !== null; }
    removeAttribute(name) { delete this.attributes[name]; }
    append(...children) {
      for (const child of children) { child.remove(); child.parentNode = this; this.childNodes.push(child); }
    }
    appendChild(child) { this.append(child); return child; }
    removeChild(child) { child.remove(); return child; }
    replaceChildren(...children) {
      for (const child of this.childNodes) child.parentNode = null;
      this.childNodes = []; this.append(...children);
    }
    remove() {
      if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this);
      this.parentNode = null;
    }
    contains(other) { return this === other || this.childNodes.some(child => child.contains(other)); }
    matches(selector) {
      if (selector === ":hover") return this.hovered;
      if (selector === ":focus" || selector === ":focus-visible") return document.activeElement === this;
      if (selector.startsWith(".")) return this.classList.contains(selector.slice(1));
      if (selector.startsWith("#")) return this.id === selector.slice(1);
      const attribute = /^\[([^=\]]+)(?:=["']?([^"'\]]+)["']?)?\]$/.exec(selector);
      if (attribute) return attribute[2] === undefined ? this.hasAttribute(attribute[1])
        : this.getAttribute(attribute[1]) === attribute[2];
      return this.tagName.toLowerCase() === selector.toLowerCase();
    }
    closest(selector) {
      for (let element = this; element; element = element.parentElement) if (element.matches(selector)) return element;
      return null;
    }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelectorAll(selector) { return this.all().filter(element => selector.split(",").some(part => element.matches(part.trim()))); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    getBoundingClientRect() {
      this.rectReads++;
      return this.getAttribute("role") === "tooltip"
        ? {left: 0, right: 300, top: 0, bottom: 90, width: 300, height: 90} : {...this.rect};
    }
    getClientRects() { return this.hidden || !this.isConnected ? [] : [this.getBoundingClientRect()]; }
    focus() {
      const previous = document.activeElement;
      if (previous === this) return;
      document.activeElement = this;
      previous?.fire("blur", {relatedTarget: this});
      this.fire("focus", {relatedTarget: previous});
    }
    blur() {
      if (document.activeElement === this) document.activeElement = document.body;
      this.fire("blur", {relatedTarget: document.activeElement});
    }
  }
  const document = new Target();
  document.createElement = tag => new Element(tag);
  document.body = new Element("body"); document.activeElement = document.body;
  document.documentElement = {clientWidth: 800, clientHeight: 600};
  document.querySelectorAll = selector => document.body.querySelectorAll(selector);
  document.querySelector = selector => document.body.querySelector(selector);
  document.getElementById = id => document.body.all().find(node => node.id === id) || null;
  const window = new Target();
  Object.assign(window, {
    document, innerWidth: 800, innerHeight: 600, scrollX: 0, scrollY: 0,
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, {fn, due: now + delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    requestAnimationFrame(fn) { return window.setTimeout(fn, 0); },
    cancelAnimationFrame(id) { timers.delete(id); },
    location: Object.freeze({hash: "#trace=selected-trace&segment=selected-segment"}),
    history: {replaceState: forbidden("history"), pushState: forbidden("history")},
    fetch: forbidden("network"), XMLHttpRequest: forbidden("network"), setInterval: forbidden("polling")
  });
  document.defaultView = window;
  const context = {
    window, document, setTimeout: window.setTimeout, clearTimeout: window.clearTimeout,
    requestAnimationFrame: window.requestAnimationFrame, cancelAnimationFrame: window.cancelAnimationFrame,
    fetch: window.fetch, XMLHttpRequest: window.XMLHttpRequest, setInterval: window.setInterval,
    location: window.location, history: window.history
  };
  if (!browser) context.module = {exports: {}};
  for (const name of ["localStorage", "sessionStorage", "indexedDB"]) {
    Object.defineProperty(window, name, {get: forbidden(name)});
    Object.defineProperty(context, name, {get: forbidden(name)});
  }
  const primaries = new Map(), hosts = new Map();
  for (const key of keys) {
    const host = new Element("span");
    host.className = "context-help-host";
    const primary = new Element(key === "investigation" || key === "capture-tools" ? "summary" : "button");
    primary.setAttribute("data-help", key); primary.textContent = key;
    primary.setAttribute("aria-label", "Original " + key); primary.setAttribute("title", "Existing title " + key);
    if (key === "live-requests") primary.setAttribute("aria-describedby", "existing-description");
    if (primary.tagName === "SUMMARY") {
      const details = new Element("details"); details.append(primary); host.append(details);
    } else host.append(primary);
    document.body.append(host); primaries.set(key, primary); hosts.set(key, host);
  }
  function advance(milliseconds = 200) {
    now += milliseconds;
    let ready;
    while ((ready = [...timers].find(([, timer]) => timer.due <= now))) {
      timers.delete(ready[0]); ready[1].fn();
    }
  }
  function listenerCount() {
    return [document, window, ...created].reduce((count, target) =>
      count + Object.values(target.listeners).reduce((n, listeners) => n + listeners.length, 0), 0);
  }
  vm.runInNewContext(source, context);
  const controller = browser ? null : context.module.exports.create(document, {window});
  return {
    document, window, context, controller, primaries, hosts, created, timers, advance, listenerCount,
    info: key => hosts.get(key).querySelector(".context-help-info"),
    tooltip: key => document.getElementById("context-help-" + key),
    visible: () => document.querySelectorAll('[role="tooltip"]').filter(node => !node.hidden)
  };
}

test("dependency-free browser and CommonJS exports expose a concise explanation for every contextual control", () => {
  assert.equal(typeof help.create, "function");
  assert.deepEqual(Object.keys(help.HELP).sort(), [...keys].sort());
  for (const [key, entry] of Object.entries(help.HELP)) {
    assert.equal(typeof entry.title, "string", key);
    assert.equal(typeof entry.text, "string", key);
    assert.ok(entry.title.trim().length > 0, key);
    assert.ok(entry.text.trim().length > 20, key);
  }
  assert.doesNotMatch(source, /\b(?:require|fetch|XMLHttpRequest|localStorage|sessionStorage|indexedDB|setInterval|innerHTML)\s*\(/);
  const h = fixture({browser: true});
  assert.equal(typeof h.window.TraceLensContextHelp.create, "function");
  assert.equal(h.document.querySelectorAll(".context-help-info").length, keys.length,
    "Loading the browser script enhances each anchor once");
  assert.equal(h.visible().length, 0);
});

test("separate info buttons describe primary controls without replacing their existing accessible names", () => {
  const h = fixture();
  assert.equal(h.visible().length, 0);
  for (const key of keys) {
    const primary = h.primaries.get(key), info = h.info(key), tooltip = h.tooltip(key);
    assert.ok(info, key + " has an independent info button");
    assert.equal(info.tagName, "BUTTON");
    assert.equal(info.type || info.getAttribute("type"), "button");
    assert.equal(info.getAttribute("aria-label"), "About " + help.HELP[key].title);
    assert.equal(info.parentNode, h.hosts.get(key));
    assert.equal(primary.contains(info), false, "Help cannot nest a button inside a primary control");
    assert.equal(info.closest("details"), null, "Disclosure help does not toggle its details element");
    assert.equal(primary.getAttribute("aria-label"), "Original " + key);
    assert.equal(primary.getAttribute("title"), "Existing title " + key);
    assert.ok(primary.getAttribute("aria-describedby").split(/\s+/).includes(tooltip.id));
    assert.ok(info.getAttribute("aria-describedby").split(/\s+/).includes(tooltip.id));
    assert.equal(tooltip.getAttribute("role"), "tooltip");
    assert.equal(tooltip.hidden, true);
    assert.equal(tooltip.parentNode, h.document.body, "Tooltip escapes clipping containers");
    assert.ok(tooltip.textContent.includes(help.HELP[key].title));
    assert.ok(tooltip.textContent.includes(help.HELP[key].text));
  }
  assert.ok(h.primaries.get("live-requests").getAttribute("aria-describedby").includes("existing-description"));
  h.controller.destroy();
});

test("pointer hover is delayed on leave and remains open while the pointer crosses into the explanation", () => {
  const h = fixture(), info = h.info("live-requests"), tooltip = h.tooltip("live-requests");
  info.fire("pointerenter");
  assert.equal(tooltip.hidden, false);
  info.fire("pointerleave");
  assert.equal(tooltip.hidden, false, "Moving toward the tooltip must not immediately dismiss it");
  tooltip.fire("pointerenter");
  h.advance();
  assert.equal(tooltip.hidden, false, "Tooltip content remains hoverable");
  tooltip.fire("pointerleave");
  h.advance();
  assert.equal(tooltip.hidden, true);
  h.controller.destroy();
});

test("keyboard focus opens help, pointer leave preserves focus help, and Escape dismisses it", () => {
  const h = fixture(), info = h.info("scheduled-jobs"), tooltip = h.tooltip("scheduled-jobs");
  info.focus();
  assert.equal(tooltip.hidden, false);
  info.fire("pointerleave"); h.advance();
  assert.equal(tooltip.hidden, false);
  h.document.fire("keydown", {key: "Escape", target: info});
  assert.equal(tooltip.hidden, true);
  assert.equal(h.document.activeElement, info, "Dismissal does not move keyboard focus");
  info.blur(); info.focus();
  assert.equal(tooltip.hidden, false, "Focus can reopen help after dismissal");
  info.blur(); h.advance();
  assert.equal(tooltip.hidden, true);
  h.controller.destroy();
});

test("tap or click pins a single explanation without activating a tab or disclosure", () => {
  const h = fixture(), info = h.info("investigation"), tooltip = h.tooltip("investigation");
  let primaryActivations = 0;
  h.primaries.get("investigation").addEventListener("click", () => primaryActivations++);
  const event = info.fire("click", {pointerType: "touch"});
  assert.equal(event.defaultPrevented, true);
  assert.equal(event.propagationStopped, true);
  assert.equal(primaryActivations, 0);
  assert.equal(tooltip.hidden, false);
  info.fire("pointerleave"); h.advance();
  assert.equal(tooltip.hidden, false, "Pinned help survives pointer departure");
  h.info("capture-tools").fire("click", {pointerType: "touch"});
  assert.equal(tooltip.hidden, true);
  assert.deepEqual(h.visible().map(node => node.id), ["context-help-capture-tools"]);
  h.info("capture-tools").fire("click");
  assert.equal(h.visible().length, 0, "A second tap dismisses pinned help");
  h.controller.destroy();
});

test("outside interaction and route changes dismiss pinned help while tooltip interaction does not", () => {
  const h = fixture(), info = h.info("errors"), tooltip = h.tooltip("errors");
  info.fire("click");
  h.document.fire("pointerdown", {target: tooltip});
  assert.equal(tooltip.hidden, false);
  h.document.fire("pointerdown", {target: h.document.body});
  assert.equal(tooltip.hidden, true);
  info.fire("click");
  h.window.fire("hashchange");
  assert.equal(tooltip.hidden, true);
  h.controller.destroy();
});

test("open help repositions on resize and nested scrolling without changing selected viewer state", () => {
  const h = fixture(), info = h.info("service-details"), tooltip = h.tooltip("service-details");
  const originalHash = h.window.location.hash;
  info.fire("click"); h.advance(0);
  const initialReads = info.rectReads;
  assert.ok(initialReads > 0, "Positioning uses the help trigger's geometry");
  h.window.fire("resize"); h.advance(0);
  assert.ok(info.rectReads > initialReads);
  const resizedReads = info.rectReads;
  h.window.fire("scroll"); h.advance(0);
  assert.ok(info.rectReads > resizedReads);
  assert.ok(h.window.listeners.scroll.some(listener => listener.capture),
    "Capture-phase scroll observes nested scrolling containers");
  assert.equal(tooltip.hidden, false);
  assert.equal(h.window.location.hash, originalHash);
  h.controller.destroy();
});

test("workflow help follows its title, not the expanded panel's right edge", () => {
  const h = fixture();
  for (const key of ["investigation", "capture-tools"]) {
    const title = h.primaries.get(key), host = h.hosts.get(key), info = h.info(key);
    const details = title.parentElement;
    host.rect = {left: 16, right: 280, top: 200, bottom: 244, width: 264, height: 44};
    title.rect = {left: 17, right: 279, top: 201, bottom: 243, width: 262, height: 42};
    info.rect = {left: 244, right: 270, top: 209, bottom: 235, width: 26, height: 26};
    details.fire("toggle");
    const closedLeft = info.style.left;
    assert.equal(closedLeft, "228px");
    assert.equal(info.style.top, "9px");
    host.rect = {...host.rect, right: 1264, bottom: 700, width: 1248, height: 500};
    details.open = true;
    details.fire("toggle");
    assert.equal(info.style.left, closedLeft, "Opening keeps the icon beside the unchanged title");
    title.rect = {...title.rect, right: 240, bottom: 265, width: 223, height: 64};
    h.window.fire("resize");
    assert.equal(info.style.left, "189px", "Responsive title wrapping also updates icon placement");
    assert.equal(info.style.top, "20px");
    details.open = false;
    info.fire("click");
    assert.equal(details.open, false, "Asking for help still cannot toggle the panel");
  }
  h.controller.destroy();
});

test("help stays within viewport edges and closes when its anchor is hidden or scrolled away", () => {
  const h = fixture(), info = h.info("timeline"), tooltip = h.tooltip("timeline");
  info.rect = {left: 770, right: 794, top: 560, bottom: 584, width: 24, height: 24};
  info.fire("click");
  const left = Number.parseFloat(tooltip.style.left), top = Number.parseFloat(tooltip.style.top);
  assert.ok(left >= 0 && left + 300 <= h.window.innerWidth);
  assert.ok(top >= 0 && top + 90 < info.rect.top, "An explanation near the bottom is placed above its icon");
  info.hidden = true;
  h.window.fire("resize");
  assert.equal(tooltip.hidden, true, "Hidden view controls cannot leave an orphaned explanation");
  info.hidden = false;
  info.fire("click");
  assert.equal(tooltip.hidden, false);
  info.rect = {...info.rect, top: -40, bottom: -16};
  h.window.fire("scroll");
  assert.equal(tooltip.hidden, true, "Scrolling an anchor out of view dismisses its explanation");
  h.controller.destroy();
});

test("destroy cancels pending dismissal, removes generated nodes and listeners, and restores descriptions", () => {
  const h = fixture(), info = h.info("live-requests");
  info.fire("pointerenter"); info.fire("pointerleave");
  assert.ok(h.listenerCount() > 0);
  h.controller.destroy(); h.advance();
  assert.equal(h.listenerCount(), 0);
  assert.equal(h.timers.size, 0);
  assert.equal(h.document.querySelectorAll(".context-help-info").length, 0);
  assert.equal(h.document.querySelectorAll('[role="tooltip"]').length, 0);
  assert.equal(h.primaries.get("live-requests").getAttribute("aria-describedby"), "existing-description");
  for (const key of keys.filter(key => key !== "live-requests")) {
    assert.equal(h.primaries.get(key).getAttribute("aria-describedby"), null);
  }
  h.controller.destroy();
  assert.equal(h.listenerCount(), 0, "Repeated cleanup is harmless");
});

test("the twelve help anchors and local assets are present without replacing the global guide", () => {
  const html = fs.readFileSync(path.join(web, "index.html"), "utf8");
  const anchors = [...html.matchAll(/\bdata-help="([^"]+)"/g)].map(match => match[1]);
  assert.deepEqual(anchors.sort(), [...keys].sort());
  assert.match(html, /<link rel="stylesheet" href="\/static\/context-help\.css">/);
  assert.match(html, /<script src="\/static\/context-help\.js"><\/script>/);
  assert.ok(html.indexOf('src="/static/context-help.js"') < html.indexOf('src="/static/app.js"'));
  assert.match(html, /id="help-open"/);
  assert.match(html, /id="help-dialog"/);
  const css = fs.readFileSync(path.join(web, "context-help.css"), "utf8");
  assert.match(css, /\.context-help-info/);
  assert.match(css, /:focus-visible/);
  assert.match(css, /\[hidden\]/);
  assert.doesNotMatch(css, /@import|https?:\/\//);
  const layout = fs.readFileSync(path.join(web, "style.css"), "utf8");
  assert.match(layout, /\.workflow-tools \.workflow-tool\.hidden\s*\{\s*display:\s*none;/,
    "Workspace visibility must override the later context-help workflow display rule");
});
