"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const requests = require("../tracelens_cli/web/request-model.js");
const viewerStore = require("../tracelens_cli/web/viewer-store.js");
const dependencies = require("../tracelens_cli/web/dependency-ui.js");
const boundarySource = require("../tracelens_cli/web/boundary-source.js");

const TRACE = "a".repeat(32), OTHER_TRACE = "b".repeat(32);
const ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", OTHER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; }
function segment(extra = {}) {
  return {service: "app", serviceInstanceId: "instance", segmentId: ID, traceId: TRACE,
    segmentKey: '["app","instance","' + ID + '"]', entryChannel: "HTTP", httpMethod: "GET",
    path: "/api/orders", status: "COMPLETED", httpStatus: 200, startedAtMillis: 1001,
    durationMs: 12, eventCount: 0, events: [], ...extra};
}
function journey(rows = [segment()]) {
  return {traceId: TRACE, segments: rows, links: [], httpCalls: [], warnings: [], errors: [], partial: false};
}
function pin(id = ID, extra = {}) {
  return {id, storage: "viewer", name: "Saved request", traceId: TRACE, capturedAt: 2000,
    partial: false, warnings: [], segments: [segment()], journey: journey(), ...extra};
}
function response(doc, status = 200) {
  const bytes = new TextEncoder().encode(JSON.stringify(doc));
  return {ok: status >= 200 && status < 300, status, json: async () => structuredClone(doc),
    body: {getReader() {
      let done = false;
      return {async read() { if (done) return {done: true}; done = true; return {done: false, value: bytes}; },
        async cancel() {}};
    }}};
}

/** Real SPA and viewer store; only rendering and network responses are replaced. */
async function harness(settings = {}) {
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.childNodes = []; this.attributes = {}; this.listeners = {};
      this.dataset = {}; this.open = false; this.value = "all"; this.className = ""; this.parent = null;
      const classes = new Set();
      this.classList = {add: name => classes.add(name), remove: name => classes.delete(name),
        contains: name => classes.has(name), toggle: (name, active) => active ? classes.add(name) : classes.delete(name)};
    }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    set innerHTML(value) {
      if (value !== "") assert.ok(this.className.split(" ").includes("ev"), "Only timeline rows use escaped HTML");
      this.replaceChildren(); this.html = value;
      if (value) this.text = value.replace(/<[^>]*>/g, "");
    }
    append(...nodes) { for (const node of nodes) { node.parent = this; this.childNodes.push(node); } }
    appendChild(node) { this.append(node); return node; }
    after(node) { node.parent = this.parent; this.parent.childNodes.splice(this.parent.childNodes.indexOf(this) + 1, 0, node); }
    contains(node) { return node === this || this.all().includes(node); }
    replaceChildren(...nodes) { this.text = ""; this.childNodes = []; this.append(...nodes); }
    setAttribute(key, value) { this.attributes[key] = String(value); }
    addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
    fire(name) { this["on" + name]?.({target: this}); for (const fn of this.listeners[name] || []) fn({target: this}); }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
    querySelector(selector) {
      const match = /^\[data-(source-event-index|event-index)="([0-9]+)"\]$/.exec(selector);
      assert.ok(match, "Fixture selector is a bounded timeline evidence index");
      const key = match[1] === "event-index" ? "eventIndex" : "sourceEventIndex";
      return this.all().find(node => node.dataset[key] === match[2]) || null;
    }
    focus(options) { (this.focusCalls ||= []).push(options); context.document.activeElement = this; }
    scrollIntoView(options) { (this.scrollCalls ||= []).push(options); }
    showModal() { this.open = true; }
    close() { this.open = false; this.fire("close"); }
    remove() { if (this.parent) this.parent.childNodes = this.parent.childNodes.filter(child => child !== this); }
  }
  const elements = new Map(), body = new Element("body"), calls = [], guided = [], tools = [], rendered = [], copied = [], graphs = [], performance = [], insights = [], intervals = [];
  const listeners = {}, location = {pathname: "/", search: "", hash: settings.hash || "",
    href: "http://127.0.0.1:7781/" + (settings.hash || "")};
  let requestOptions, requestUI;
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
  };
  const context = {URLSearchParams, TextEncoder, TextDecoder, setTimeout, clearTimeout, setInterval(action) { intervals.push(action); },
    navigator: {clipboard: {async writeText(value) { copied.push(value); }}},
    document: {querySelector: element, querySelectorAll: () => [], createElement: tag => new Element(tag), body},
    window: {location, history: {replaceState(_, __, path) {
      location.hash = path.includes("#") ? path.slice(path.indexOf("#")) : "";
      location.href = "http://127.0.0.1:7781" + path;
    }}, addEventListener(name, fn) { listeners[name] = fn; },
      TraceLensRequests: requests, TraceLensViewerStore: viewerStore, TraceLensDependencies: dependencies,
      TraceLensBoundarySource: boundarySource,
      TraceLensRequestInsights: {render(container, doc, options) { insights.push({container, doc, options}); container.hidden = false; }},
      TraceLensGraph: {render(container, model, options) { graphs.push({container, model, options}); return () => {}; }},
      TraceLensBrowserStore: {CONSTANTS: {MAX_PIN_BYTES: 5 * 1024 * 1024}},
      TraceLensRequestUI: class {
        constructor(options) {
          requestOptions = options; requestUI = this; this.mode = "live"; this.refreshes = 0;
          this.viewerStore = viewerStore.create(options.api);
          this.store = {getPin: async id => settings.browserPin?.(id)};
        }
        init() { requestOptions.viewerContext?.({mode: settings.viewerMode || "federated"}); }
        setServices() {} setTraces() {} goHome() { this.mode = "live"; }
        changeMode(mode) { this.mode = mode; requestOptions.onModeChange?.(mode); }
        async refreshStored() { this.refreshes++; }
      },
      TraceLensPerformanceUI: {create(container, options) {
        const item = {container, options, visible: false, visibility: [], sources: []}; performance.push(item);
        return {setVisible(value) { item.visible = value; item.visibility.push(value); },
          setSources(value) { item.sources.push(value); }, dispose() {}};
      }},
      TraceLensGuidedInvestigation: {render(container, options) {
        const item = {container, options, active: true, destroys: 0, cancels: 0, services: [], drafts: []};
        guided.push(item);
        return {destroy() { item.active = false; item.destroys++; },
          cancel() { item.active = false; item.cancels++; }, setServices(value) { item.services = value; },
          prepare(draft) { item.drafts.push(draft); item.active = false; }};
      }},
      TraceLensCaptureTools: {render(container, options) {
        const item = {container, options, closes: 0, refreshes: 0}; tools.push(item);
        const cleanup = () => item.closes++; cleanup.refresh = () => item.refreshes++; return cleanup;
      }},
      TraceLensCodeFlow: {render(container, model, options) { rendered.push({container, model, options}); return () => {}; }},
      TraceLensExecutionGraph: {render() { return () => {}; }}},
    async fetch(path, config) {
      calls.push({path, ...config});
      if (settings.fetch) {
        const custom = settings.fetch(path, config);
        if (custom !== undefined) return await custom;
      }
      if (path === "/api/summary") return response({federated: true, configuredServices: ["app"], services: []});
      if (path === "/api/viewer-context") return response({mode: "federated", namespace: "test"});
      if (path === "/api/traces") return response({traces: []});
      if (path.startsWith("/api/distributed-trace/")) return response(journey());
      if (path.startsWith("/api/render-data/")) return response(segment());
      if (path === "/api/segment-execution") return response({kind: "segment-execution", nodes: [], lanes: []});
      if (path === "/api/snapshots" && config.method === "POST") return response({...JSON.parse(config.body).snapshot, id: ID, storage: "viewer"}, 201);
      if (path.startsWith("/api/snapshots/")) return response(pin(path.split("/").pop()));
      throw new Error("Unexpected fixture request: " + path);
    }};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/app.js"), "utf8"), context);
  await settle();
  return {element, calls, guided, tools, rendered, body, copied, graphs, location, requestOptions, requestUI, performance, insights,
    activeElement: () => context.document.activeElement,
    mode(mode) { requestUI.mode = mode; requestOptions.onModeChange?.(mode); },
    hash(value) { location.hash = value; listeners.hashchange?.(); },
    tick() { intervals.forEach(action => action()); },
    toggle(selector, open) { const node = element(selector); node.open = open; node.fire("toggle"); },
    openInvestigation() { this.toggle("#investigation-panel", true); return guided.at(-1); },
    openTools() { this.toggle("#capture-tools-panel", true); return tools.at(-1); },
    home() { element("#home").onclick(); },
    posts() { return calls.filter(call => call.path === "/api/snapshots" && call.method === "POST"); }};
}

test("workspace navigation separates saved evidence and service settings without lifecycle actions", async () => {
  const h = await harness();
  h.element("#services-workspace").onclick(); await settle();
  assert.equal(h.element("#services-view").classList.contains("hidden"), false);
  assert.equal(h.element("#list-view").classList.contains("hidden"), true);
  assert.equal(h.element("#services-workspace").attributes["aria-pressed"], "true");
  assert.equal(h.element("#connection-summary").textContent, "0/1 services connected");
  h.element("#pins-tab").onclick(); await settle();
  assert.equal(h.requestUI.mode, "pins");
  assert.equal(h.element("#services-view").classList.contains("hidden"), true);
  assert.equal(h.element("#pins-tab").attributes["aria-pressed"], "true");
  assert.equal(h.calls.some(call => call.method !== "GET"), false);
  assert.equal(h.calls.some(call => /restart|setup\/apply|diagnose|record/.test(call.path)), false);
});

test("return from saved comparison evidence preserves its controller and does not substitute live data", async () => {
  const h = await harness(), tools = h.openTools();
  const focus = h.element("#inspect-before");
  await tools.options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance"},
    {isCurrent: () => true, context: {side: "before", label: "EMS call", focus, scroll: 120}});
  assert.equal(h.element("#comparison-return").classList.contains("hidden"), false);
  assert.equal(h.element("#comparison-context").textContent, "Before · app · EMS call");
  const reads = h.calls.length;
  h.element("#back-to-comparison").onclick(); await settle();
  assert.equal(h.requestUI.mode, "pins");
  assert.equal(h.element("#capture-tools-panel").open, true);
  assert.equal(h.tools.length, 1); assert.equal(tools.closes, 0);
  assert.equal(focus.focusCalls.length, 1);
  assert.equal(h.calls.length, reads, "return navigation is presentation only");
});

test("independent saved navigation clears comparison context and leaves the services workspace", async () => {
  const h = await harness(), tools = h.openTools();
  await tools.options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance"},
    {isCurrent: () => true, context: {side: "before", label: "EMS call"}});
  assert.equal(h.element("#comparison-return").classList.contains("hidden"), false);
  await h.requestOptions.openPin(pin(OTHER_ID));
  assert.equal(h.element("#comparison-return").classList.contains("hidden"), true);
  h.element("#services-workspace").onclick(); await settle();
  assert.equal(h.element("#services-view").classList.contains("hidden"), false);
  h.hash("#snapshot=" + ID); await settle();
  assert.equal(h.element("#services-view").classList.contains("hidden"), true);
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  assert.equal(h.element("#pins-tab").attributes["aria-pressed"], "true");
  assert.equal(h.element("#comparison-return").classList.contains("hidden"), true);
});

test("back from a saved deep link returns to Saved captures without a live read", async () => {
  const h = await harness({hash: "#snapshot=" + ID});
  assert.equal(h.element("#journey-back").textContent, "← Saved captures");
  const reads = h.calls.length;
  h.element("#journey-back").onclick(); await settle();
  assert.equal(h.requestUI.mode, "pins");
  assert.equal(h.element("#pins-tab").attributes["aria-pressed"], "true");
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
  assert.equal(h.calls.length, reads);
});

test("timeline keeps paging controls stable and exposes escaped full event details", async () => {
  const trace = segment({events: Array.from({length: 501}, (_, index) => ({seq: index + 1,
    type: "LINE", className: "example.LongClass", methodName: "load", line: index + 1,
    sourceLine: "<script>not executable</script>", thread: "worker"}))});
  const h = await harness({fetch(path) { if (path.startsWith("/api/render-data/")) return response(trace); }});
  await h.requestOptions.openSegment(trace); await settle();
  h.element("#execution-timeline-tab").onclick();
  const pager = h.element("#timeline-pages"), controls = [...pager.childNodes];
  controls[3].focus();
  controls[3].onclick();
  assert.deepEqual(pager.childNodes, controls, "paging does not replace keyboard controls");
  assert.equal(controls[3].disabled, true);
  assert.equal(h.activeElement(), h.element("#timeline-mode"), "terminal paging leaves focus on an enabled control");
  controls[1].onclick();
  const row = h.element("#timeline").childNodes.find(node => node.dataset.eventIndex === "0");
  assert.equal(row.attributes.role, "button");
  row.onclick(); row.onclick();
  const details = h.element("#timeline").childNodes.filter(node => node.className === "timeline-event-detail");
  assert.equal(details.length, 1);
  assert.match(details[0].textContent, /example.LongClass/);
  assert.match(details[0].textContent, /<script>not executable<\/script>/);
  details[0].childNodes[1].onclick();
  assert.equal(row.focusCalls.length, 1);
  h.element("#timeline-wrap").checked = true; h.element("#timeline-wrap").onchange();
  assert.equal(h.element("#timeline").classList.contains("wrap-events"), true);
});

test("setup handoff opens a configured-service draft without diagnosis, watching or saving", async () => {
  const h = await harness({hash: "#investigate=1&service=app"});
  assert.equal(h.guided.length, 1);
  assert.equal(h.element("#investigation-panel").open, true);
  assert.equal(JSON.stringify(h.guided[0].drafts), JSON.stringify([{source: "setup", intent: "check", filters: {service: "app"}}]));
  assert.equal(h.guided[0].active, false);
  assert.equal(h.calls.some(call => /diagnose|distributed-trace|render-data/.test(call.path)), false);
  assert.equal(h.calls.some(call => call.method !== "GET"), false);
  h.toggle("#investigation-panel", true);
  assert.equal(h.guided.length, 1, "the browser's delayed toggle does not duplicate the controller");
});

test("empty-list recovery prepares a draft and cannot open from saved or offline context", async () => {
  const draft = {source: "empty-requests", intent: "watch", filters: {service: "app", method: "GET"}, pathSuggestion: "/orders"};
  const h = await harness();
  assert.equal(h.requestOptions.canInvestigate(), true);
  h.requestOptions.onInvestigate(draft);
  assert.equal(h.guided.length, 1);
  assert.deepEqual(h.guided[0].drafts, [draft]);
  assert.equal(h.calls.some(call => /diagnose|distributed-trace|render-data/.test(call.path)), false);
  for (const settings of [{hash: "#snapshot=" + ID}, {viewerMode: "offline"}]) {
    const blocked = await harness(settings);
    assert.equal(blocked.requestOptions.canInvestigate(), false);
    blocked.requestOptions.onInvestigate(draft);
    assert.equal(blocked.guided.length, 0);
    assert.equal(blocked.posts().length, 0);
  }
});

test("setup handoff handles unavailable service and rejects invalid aliases without network actions", async () => {
  const unknown = await harness({hash: "#investigate=1&service=missing"});
  assert.match(unknown.element("#notice").textContent, /missing is not listed/);
  assert.equal(unknown.guided[0].drafts[0].filters.service, "missing", "the form must require an explicit configured choice, not broaden to all services");
  const bad = await harness({hash: "#investigate=1&service=" + encodeURIComponent("https://elsewhere/api")});
  assert.equal(bad.guided.length, 0);
  assert.match(bad.element("#notice").textContent, /Invalid service/);
  assert.equal(bad.calls.some(call => /diagnose|distributed-trace|render-data/.test(call.path)), false);
});

test("snapshot navigation wins over a handoff and never starts live service reads", async () => {
  const h = await harness({hash: "#snapshot=" + ID + "&investigate=1&service=app"});
  assert.equal(h.guided.length, 0);
  assert.equal(h.insights.length, 1);
  assert.equal(h.calls.some(call => /\/api\/(summary|traces|distributed-trace|render-data)/.test(call.path)), false);
});

test("late setup status response cannot reopen investigation after navigation away", async () => {
  const pending = deferred(); let summaries = 0;
  const h = await harness({hash: "#investigate=1&service=app", fetch: path => {
    if (path === "/api/summary" && ++summaries === 2) return pending.promise;
  }});
  h.hash(""); await settle();
  pending.resolve(response({federated: true, configuredServices: ["app"], services: []}));
  await settle();
  assert.equal(h.guided.length, 0);
});

test("request insights use loaded saved evidence and exact segment navigation", async () => {
  const h = await harness({hash: "#snapshot=" + ID});
  const insight = h.insights[0];
  assert.equal(insight.doc.traceId, TRACE);
  assert.equal(insight.options.entry.known, true);
  assert.equal(h.calls.some(call => /\/api\/(summary|traces|distributed-trace|render-data)/.test(call.path)), false);
  insight.options.onOpenSegment(insight.doc.segments[0]); await settle();
  assert.equal(h.rendered.at(-1).options.saved, true);
  assert.equal(h.calls.some(call => /\/api\/(summary|traces|distributed-trace|render-data)/.test(call.path)), false);
});

test("workflow details mount once, close disposes, remount is fresh and navigation cancels", async () => {
  const h = await harness(), first = h.openInvestigation(), savedTools = h.openTools();
  h.toggle("#investigation-panel", true); h.toggle("#capture-tools-panel", true);
  assert.equal(h.guided.length, 1); assert.equal(h.tools.length, 1);
  h.toggle("#investigation-panel", false); h.toggle("#capture-tools-panel", false);
  assert.equal(first.destroys, 1); assert.equal(savedTools.closes, 1);
  const second = h.openInvestigation();
  assert.equal(h.guided.length, 2); assert.notEqual(first, second);
  assert.equal(JSON.stringify(second.options.services), '["app"]');
  h.home(); await settle();
  assert.ok(second.cancels >= 1); assert.equal(second.active, false);
  assert.equal(h.posts().length, 0);
});

test("watch save rechecks cancellation after capture and before the real persistent POST", async () => {
  const waiting = deferred();
  const h = await harness({fetch: path => path.startsWith("/api/render-data/") ? waiting.promise : undefined});
  const watch = h.openInvestigation(), request = requests.groupTraces([segment()])[0];
  const guard = {isCurrent: () => watch.active, filters: {path: "/api/orders", method: "GET"}};
  const saving = watch.options.saveTrace(request, guard);
  await settle(); assert.ok(h.calls.some(call => call.path.startsWith("/api/render-data/")));
  assert.equal(h.posts().length, 0);
  h.toggle("#investigation-panel", false); waiting.resolve(response(segment()));
  await assert.rejects(saving, /cancelled or expired before saving/);
  assert.equal(h.posts().length, 0); assert.equal(h.requestUI.refreshes, 0);
});

test("watch save uses existing snapshot capture, exact request key and one explicit save POST", async () => {
  const h = await harness(), tools = h.openTools(), watch = h.openInvestigation();
  const request = {...requests.groupTraces([segment()])[0], key: "original-selected-request-key"};
  const saved = await watch.options.saveTrace(request, {isCurrent: () => true, filters: {path: "/api/orders", method: "GET"}});
  assert.equal(h.posts().length, 1);
  const written = JSON.parse(h.posts()[0].body).snapshot;
  assert.equal(written.request.key, request.key);
  assert.equal(written.traceId, request.traceId);
  assert.equal(written.segments[0].serviceInstanceId, "instance");
  assert.equal(written.name, "Investigation · GET /api/orders");
  assert.equal(saved.storage, "viewer");
  assert.equal(h.requestUI.refreshes, 1); assert.equal(tools.refreshes, 1);
  assert.ok(h.calls.filter(call => /distributed-trace|render-data/.test(call.path)).every(call => call.method === "GET"));
});

test("an already-dispatched save remains discoverable after cancellation without a second POST", async () => {
  const committed = deferred(), h = await harness({fetch: (path, config) =>
    path === "/api/snapshots" && config.method === "POST" ? committed.promise : undefined});
  const watch = h.openInvestigation(), request = requests.groupTraces([segment()])[0];
  const saving = watch.options.saveTrace(request, {isCurrent: () => watch.active, filters: {path: "/api/orders"}});
  await settle(); assert.equal(h.posts().length, 1);
  h.home(); committed.resolve(response(pin(), 201));
  await saving;
  assert.equal(h.posts().length, 1); assert.equal(h.requestUI.refreshes, 1);
  assert.equal(h.location.hash, "", "a late saved result does not force navigation");
});

test("wrong trace export identity cannot be persisted under the selected request", async () => {
  const h = await harness({fetch: path => path.startsWith("/api/render-data/") ? response(segment({traceId: OTHER_TRACE})) : undefined});
  const watch = h.openInvestigation(), request = requests.groupTraces([segment()])[0];
  await assert.rejects(watch.options.saveTrace(request, {isCurrent: () => true, filters: {path: "/api/orders"}}),
    /No execution snapshots/);
  assert.equal(h.posts().length, 0);
});

test("saved-tool navigation ignores a pending response after newer navigation", async () => {
  const waiting = deferred(), h = await harness({fetch: path => path === "/api/snapshots/" + ID ? waiting.promise : undefined});
  const watch = h.openInvestigation(), tools = h.openTools();
  tools.options.onOpenSnapshot(ID); await settle();
  assert.ok(watch.cancels >= 1);
  h.home(); await settle(); waiting.resolve(response(pin())); await settle();
  assert.equal(h.location.hash, "");
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
  assert.equal(h.calls.some(call => call.path.startsWith("/api/distributed-trace/")), false);
});

test("overlapping saved-tool opens retain the newest selected snapshot", async () => {
  const first = deferred(), second = deferred(), h = await harness({fetch: path => {
    if (path === "/api/snapshots/" + ID) return first.promise;
    if (path === "/api/snapshots/" + OTHER_ID) return second.promise;
  }});
  const tools = h.openTools();
  tools.options.onOpenSnapshot(ID); await settle();
  tools.options.onOpenSnapshot(OTHER_ID); await settle();
  second.resolve(response(pin(OTHER_ID))); await settle();
  assert.equal(h.location.hash, "#snapshot=" + OTHER_ID);
  first.resolve(response(pin())); await settle();
  assert.equal(h.location.hash, "#snapshot=" + OTHER_ID);
  assert.equal(h.calls.some(call => /distributed-trace|render-data/.test(call.path)), false);
});

test("saved-tool opening rejects mismatched IDs and non-viewer storage without live fallback", async () => {
  for (const bad of [pin(OTHER_ID), pin(ID, {storage: "browser"})]) {
    const h = await harness({fetch: path => path === "/api/snapshots/" + ID ? response(bad) : undefined});
    h.openTools().options.onOpenSnapshot(ID); await settle();
    assert.match(h.element("#notice").textContent, /Invalid saved snapshot response/);
    assert.equal(h.location.hash, "");
    assert.equal(h.calls.some(call => /distributed-trace|render-data/.test(call.path)), false);
  }
  const h = await harness(); h.openTools().options.onOpenSnapshot("../outside"); await settle();
  assert.match(h.element("#notice").textContent, /Invalid saved snapshot ID/);
  assert.equal(h.calls.some(call => call.path.startsWith("/api/snapshots/")), false);
});

test("comparison evidence opens only its exact saved service invocation", async () => {
  const other = segment({service: "ems", segmentId: OTHER_ID, serviceInstanceId: "ems-instance"});
  const saved = pin(ID, {segments: [segment(), other], journey: journey([segment(), other])});
  const h = await harness({fetch: path => path === "/api/snapshots/" + ID ? response(saved) : undefined});
  const from = h.calls.length;
  h.openTools().options.onOpenEvidence({snapshotId: ID, service: "ems", segmentId: OTHER_ID, serviceInstanceId: "ems-instance"});
  await settle();
  assert.equal(h.element("#segment-service-name").textContent, "ems");
  assert.equal(h.location.hash, "#snapshot=" + ID);
  assert.match(h.element("#boundary-code-context").textContent, /Exact captured service invocation/);
  assert.equal(h.calls.slice(from).some(call => /distributed-trace|render-data|\/api\/(summary|traces)$/.test(call.path)), false);
});

test("comparison evidence refuses missing or ambiguous owners instead of a live fallback", async () => {
  for (const rows of [[segment()], [segment(), segment()]]) {
    const h = await harness({fetch: path => path === "/api/snapshots/" + ID ? response(pin(ID, {segments: rows})) : undefined});
    const from = h.calls.length;
    await assert.rejects(h.openTools().options.onOpenEvidence({snapshotId: ID, service: rows.length === 1 ? "missing" : "app", segmentId: ID, serviceInstanceId: "instance"}), /missing or ambiguous/);
    assert.equal(h.location.hash, "");
    assert.equal(h.calls.slice(from).some(call => /render-data|distributed-trace/.test(call.path)), false);
  }
});

test("comparison method navigation reports missing retained detail without guessing", async () => {
  const h = await harness();
  h.openTools().options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance",
    className: "example.UsageClient", methodName: "fetchEntitlements"});
  await settle();
  assert.match(h.element("#boundary-code-context").textContent, /UsageClient.fetchEntitlements\(\).*outside the available execution model/);
});

test("comparison method navigation selects only the exact captured descriptor and preserves event identity", async () => {
  for (const descriptor of ["(I)V", "()V", null]) {
    const events = [{seq: 1, type: "METHOD_ENTER", className: "example.Client", methodName: "read", methodDescriptor: "(I)V", thread: "worker", threadId: 7, detailGapBefore: true}];
    const saved = pin(ID, {segments: [segment({events, eventCount: 1})]});
    const h = await harness({fetch: path => {
      if (path === "/api/snapshots/" + ID) return response(saved);
      if (path === "/api/segment-execution") return response({kind: "segment-execution", lanes: [], nodes: [
        {kind: "invocation", className: "example.Client", methodName: "read", methodDescriptor: "(I)V", entry: {index: 0, seq: 1}}
      ]});
    }});
    await h.openTools().options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance",
      className: "example.Client", methodName: "read", ...(descriptor ? {methodDescriptor: descriptor} : {})});
    await settle();
    assert.match(h.element("#boundary-code-context").textContent, descriptor === "(I)V" ? /Highlighting a retained invocation/ : /outside the available execution model/);
    const request = JSON.parse(h.calls.find(call => call.path === "/api/segment-execution").body);
    assert.equal(request.trace.events[0].threadId, 7);
    assert.equal(request.trace.events[0].methodDescriptor, "(I)V");
    assert.equal(request.trace.events[0].detailGapBefore, true);
  }
});

test("comparison evidence navigation cannot override newer navigation", async () => {
  const waiting = deferred(), h = await harness({fetch: path => path === "/api/snapshots/" + ID ? waiting.promise : undefined});
  h.openTools().options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance"});
  await settle(); h.home(); await settle(); waiting.resolve(response(pin())); await settle();
  assert.equal(h.location.hash, "");
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
});

test("closing or replacing the comparison invalidates a pending evidence open", async () => {
  let active = true;
  const waiting = deferred(), h = await harness({fetch: path => path === "/api/snapshots/" + ID ? waiting.promise : undefined});
  const pending = h.openTools().options.onOpenEvidence({snapshotId: ID, service: "app", segmentId: ID, serviceInstanceId: "instance"}, {isCurrent: () => active});
  await settle(); active = false; waiting.resolve(response(pin())); await pending;
  assert.equal(h.location.hash, "");
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
});

const locationDoc = () => ({path: "/safe checkout/example/OrderService.java", line: 2,
  note: "Current local checkout; correspondence with the captured running build is not verified.",
  editors: {cursor: "cursor://file/safe%20checkout/example/OrderService.java:2",
    vscode: "vscode://file/safe%20checkout/example/OrderService.java:2",
    idea: "idea://open?file=%2Fsafe%20checkout%2Fexample%2FOrderService.java&line=2"}});

test("source dialog retains current-checkout warning, configured service and explicit copy/open actions", async () => {
  const h = await harness({fetch: path => path.startsWith("/api/source-location?") ? response(locationDoc()) : undefined});
  await h.requestOptions.openSegment(segment()); await settle();
  h.rendered.at(-1).options.onOpenSource({className: "example.OrderService", line: 2}); await settle();
  const sourceCall = h.calls.find(call => call.path.startsWith("/api/source-location?"));
  assert.equal(sourceCall.path, "/api/source-location?className=example.OrderService&line=2&service=app");
  const dialog = h.body.childNodes[0];
  assert.equal(dialog.tagName, "DIALOG"); assert.equal(dialog.open, true);
  assert.match(dialog.textContent, /Current local checkout; correspondence with the captured running build is not verified/);
  assert.deepEqual(dialog.all().filter(node => node.tagName === "A").map(node => node.href),
    Object.values(locationDoc().editors));
  dialog.all().find(node => node.textContent === "Copy path").onclick(); await settle();
  assert.deepEqual(h.copied, ["/safe checkout/example/OrderService.java:2"]);
  dialog.all().find(node => node.textContent === "Close").onclick();
  assert.equal(h.body.childNodes.length, 0);
});

test("source dialog never creates links for unrelated or script URL protocols", async () => {
  const doc = locationDoc(); doc.editors = {cursor: "javascript:alert(1)", vscode: "https://evil.example", idea: "idea://open?line=2"};
  const h = await harness({fetch: path => path.startsWith("/api/source-location?") ? response(doc) : undefined});
  await h.requestOptions.openSegment(segment()); await settle();
  h.rendered.at(-1).options.onOpenSource({className: "example.OrderService", line: 2}); await settle();
  assert.deepEqual(h.body.childNodes[0].all().filter(node => node.tagName === "A").map(node => node.href), ["idea://open?line=2"]);
});

test("source lookup completion after navigation does not open a stale dialog", async () => {
  const pending = deferred(), h = await harness({fetch: path => path.startsWith("/api/source-location?") ? pending.promise : undefined});
  await h.requestOptions.openSegment(segment()); await settle();
  h.rendered.at(-1).options.onOpenSource({className: "example.OrderService", line: 2}); await settle();
  h.home(); await settle(); pending.resolve(response(locationDoc())); await settle();
  assert.equal(h.body.childNodes.length, 0);
});

test("saved deep-link source navigation uses local viewer mode without live status or segment reads", async () => {
  const h = await harness({hash: "#snapshot=" + ID,
    fetch: path => path.startsWith("/api/source-location?") ? response(locationDoc()) : undefined});
  const select = h.element("#journey-tree").all().find(node => node.className === "segment-link");
  assert.ok(select, "the loaded saved snapshot exposes its saved segment");
  select.onclick(); await settle();
  assert.equal(h.rendered.at(-1).options.saved, true);
  h.rendered.at(-1).options.onOpenSource({className: "example.OrderService", line: 2}); await settle();
  assert.ok(h.calls.some(call => call.path === "/api/viewer-context"));
  assert.ok(h.calls.some(call => call.path.endsWith("className=example.OrderService&line=2&service=app")));
  assert.equal(h.calls.some(call => /\/api\/(?:summary|traces|render-data|distributed-trace)/.test(call.path)), false);
  assert.match(h.body.childNodes[0].textContent, /Current local checkout/);
});

function boundaryFixture(kind = "DB_ROUTE", withLine = true) {
  const event = (seq, type, extra = {}) => ({seq, type, thread: "emsAsync-353",
    className: "com.chargebee.ems.FeaturesService", methodName: "createFeature", ...extra});
  const marker = event(withLine ? 3 : 2, kind, {attributes: kind === "DB_ROUTE" ? {route: "TEST_MASTER"} :
    {boundaryKind: "messaging", system: "sqs", operation: "sendMessage", direction: "publish",
      destination: "cb-local-ems-events-ms", status: "SUBMITTED", evidenceKind: "client-boundary"}});
  const events = [event(1, "METHOD_ENTER"),
    ...(withLine ? [event(2, "LINE", {line: 54, sourceLine: "recordedSourceLine();"})] : []), marker];
  const owner = segment({service: "cb-ems", segmentId: OTHER_ID, serviceInstanceId: "ems-instance",
    segmentKey: '["cb-ems","ems-instance","' + OTHER_ID + '"]',
    httpMethod: "POST", path: "/features", eventCount: events.length, events});
  const node = {id: "boundary-1", kind: "dependency-call", service: owner.service,
    segmentId: owner.segmentId, segmentKey: owner.segmentKey, serviceInstanceId: owner.serviceInstanceId,
    eventSeq: marker.seq, thread: marker.thread, ...dependencies.boundaryMetadata(marker)};
  const doc = {...journey([segment(), owner]), visualization: {nodes: [node], edges: []}};
  return {node, owner, doc, marker, saved: pin(ID, {segments: [segment(), owner], journey: doc})};
}

test("timeline presentation separates duplicate-name workers and keeps renamed runtime lanes and overload exits precise", async () => {
  const fixture = boundaryFixture();
  const event = (seq, type, threadId, methodName, descriptor, extra = {}) => ({seq, type, thread: "worker", threadId,
    className: "example.Service", methodName, ...(descriptor ? {methodDescriptor: descriptor} : {}), ...extra});
  fixture.owner.events = [event(1, "METHOD_ENTER", 1, "read", "(I)V"),
    event(2, "METHOD_ENTER", 2, "other", "()V"), event(3, "LINE", 1, null, null, {thread: "renamed", line: 12}),
    event(4, "METHOD_ENTER", 1, "read", "()V"), event(5, "METHOD_EXIT", 1, "read", "(I)V"),
    event(6, "LINE", 1, null, null, {line: 20}), event(7, "LINE", 2, null, null, {line: 30}),
    event(8, "METHOD_ENTER", "2", "invalid", "()V"), event(9, "LINE", 2, null, null, {line: 40}),
    {...fixture.marker, seq: 10, threadId: 2}];
  fixture.owner.eventCount = fixture.owner.events.length; fixture.node.eventSeq = 10;
  const h = await harness({hash: "#snapshot=" + ID, fetch: path => path === "/api/snapshots/" + ID ? response(fixture.saved) : undefined});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  const timeline = h.element("#timeline"), body = index => timeline.querySelector(`[data-event-index="${index}"]`).html.split('<span class="body">')[1];
  assert.match(timeline.textContent, /worker · runtime #1/); assert.match(timeline.textContent, /worker · runtime #2/);
  assert.match(timeline.textContent, /renamed · runtime #1/);
  assert.match(body(1), /^▶/, "another worker does not inherit indentation");
  assert.match(body(2), /^  ·/, "renaming retains the original worker's frame");
  assert.match(body(5), /^·/, "an outer overload exit unwinds its incomplete nested overload");
  assert.match(body(6), /^  ·/, "another worker keeps its own active frame");
  assert.match(body(8), /^·/, "invalid explicit thread IDs cannot fall back to a matching name");
});

test("saved DB and SQS actions open exact owning evidence, highlight source and never substitute live data", async () => {
  for (const kind of ["DB_ROUTE", "EXTERNAL_CALL"]) {
    const fixture = boundaryFixture(kind);
    const h = await harness({hash: "#snapshot=" + ID, fetch: path =>
      path === "/api/snapshots/" + ID ? response(fixture.saved) : undefined});
    h.element("#timeline-mode").value = "boundaries";
    h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
    const timeline = h.element("#timeline"), context = h.element("#boundary-code-context");
    assert.match(context.textContent, /Observed calling context · FeaturesService\.createFeature\(\):54/);
    assert.match(context.textContent, /not proof of an exact callsite or direct caller/);
    assert.equal(context.classList.contains("hidden"), false);
    assert.equal(h.element("#execution-timeline-tab").attributes["aria-pressed"], "true");
    assert.equal(h.element("#timeline-mode").value, "all");
    assert.match(h.element("#trace-meta").textContent, /cb-ems/);
    const line = timeline.querySelector('[data-event-index="1"]');
    assert.equal(line.classList.contains("evidence-highlight"), true);
    assert.equal(timeline.querySelector('[data-source-event-index="1"]').classList.contains("evidence-highlight"), true);
    assert.equal(timeline.querySelector('[data-event-index="2"]').classList.contains("evidence-highlight"), true);
    assert.equal(line.focusCalls.length, 1);
    assert.equal(line.focusCalls[0].preventScroll, true);
    assert.equal(line.scrollCalls[0].block, "center");
    assert.equal(h.location.hash, "#snapshot=" + ID);
    assert.equal(h.calls.some(call => /\/api\/(?:summary|traces|render-data|distributed-trace|segment-execution|source-location|viewer-context)/.test(call.path)), false);
    assert.equal(h.posts().length, 0);
  }
});

test("boundary local source lookup happens only after explicit action and retains owning service", async () => {
  const fixture = boundaryFixture("EXTERNAL_CALL");
  const h = await harness({hash: "#snapshot=" + ID, fetch: path => {
    if (path === "/api/snapshots/" + ID) return response(fixture.saved);
    if (path.startsWith("/api/source-location?")) return response(locationDoc());
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.equal(h.calls.some(call => call.path.startsWith("/api/source-location?")), false);
  assert.equal(h.body.childNodes.length, 0);
  h.element("#boundary-code-context").all().find(node => node.textContent === "Open local source").onclick();
  await settle();
  assert.equal(h.calls.find(call => call.path.startsWith("/api/source-location?")).path,
    "/api/source-location?className=com.chargebee.ems.FeaturesService&line=54&service=cb-ems");
  assert.equal(h.body.childNodes[0].open, true);
  assert.match(h.body.childNodes[0].textContent, /Current local checkout/);
  assert.equal(h.calls.some(call => /\/api\/(?:summary|traces|render-data|distributed-trace)/.test(call.path)), false);
});

test("a retained boundary without source opens only its exact timeline event", async () => {
  const fixture = boundaryFixture("EXTERNAL_CALL", false);
  const h = await harness({hash: "#snapshot=" + ID, fetch: path =>
    path === "/api/snapshots/" + ID ? response(fixture.saved) : undefined});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  const context = h.element("#boundary-code-context"), row = h.element("#timeline").querySelector('[data-event-index="1"]');
  assert.match(context.textContent, /Calling code unavailable.*no reliable preceding source line/);
  assert.equal(context.all().some(node => node.textContent === "Open local source"), false);
  assert.equal(row.classList.contains("evidence-highlight"), true);
  assert.equal(row.focusCalls.length, 1);
  assert.equal(row.scrollCalls.length, 1);
  assert.equal(h.calls.some(call => /render-data|source-location|segment-execution/.test(call.path)), false);
});

test("wrong, absent or ambiguous boundary owners are rejected before segment reads", async () => {
  const fixture = boundaryFixture();
  for (const change of [
    {node: {...fixture.node, service: "app"}},
    {node: {...fixture.node, segmentKey: undefined}},
    {node: {...fixture.node, serviceInstanceId: "another-instance"}},
    {segments: [segment()]},
    {segments: [fixture.owner, {...fixture.owner}]}
  ]) {
    const saved = {...fixture.saved, journey: {...fixture.doc, segments: change.segments || fixture.doc.segments}};
    const h = await harness({hash: "#snapshot=" + ID, fetch: path =>
      path === "/api/snapshots/" + ID ? response(saved) : undefined});
    h.graphs.at(-1).options.onOpenBoundary(change.node || fixture.node); await settle();
    assert.match(h.element("#notice").textContent, /owning service segment is missing or ambiguous/);
    assert.equal(h.element("#boundary-code-context").textContent, "");
    assert.equal(h.calls.some(call => /render-data|source-location|segment-execution/.test(call.path)), false);
  }
});

test("a saved boundary with an unsaved owning segment never falls back to live data", async () => {
  const fixture = boundaryFixture();
  const h = await harness({hash: "#snapshot=" + ID, fetch: path => path === "/api/snapshots/" + ID
    ? response({...fixture.saved, segments: [segment()]}) : undefined});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.match(h.element("#journey-meta").textContent, /not included in the saved capture.*No live data was substituted/);
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  assert.equal(h.calls.some(call => /render-data|source-location|segment-execution/.test(call.path)), false);
  assert.equal(h.element("#boundary-code-context").textContent, "");
});

test("live boundary navigation validates the returned trace, segment, instance and service", async () => {
  const fixture = boundaryFixture();
  for (const changed of [{traceId: OTHER_TRACE}, {segmentId: ID}, {serviceInstanceId: "old-instance"},
    {serviceInstanceId: undefined}, {service: "another-service"}]) {
    const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
      if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
      if (path.startsWith("/api/render-data/")) return response({...fixture.owner, ...changed});
    }});
    h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
    assert.equal(h.calls.find(call => call.path.startsWith("/api/render-data/")).path,
      "/api/render-data/" + OTHER_ID + "?service=cb-ems&instance=ems-instance");
    assert.match(h.element("#journey-meta").textContent, /does not match this captured service\/request/);
    assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
    assert.equal(h.element("#boundary-code-context").textContent, "");
    assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
  }
});

test("initial live deep-link boundary navigation reads its exact owner without needing Refresh", async () => {
  const fixture = boundaryFixture("EXTERNAL_CALL");
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response(fixture.owner);
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  const reads = h.calls.filter(call => call.path.startsWith("/api/render-data/"));
  assert.equal(reads.length, 1);
  assert.equal(reads[0].path, "/api/render-data/" + OTHER_ID + "?service=cb-ems&instance=ems-instance");
  assert.match(h.element("#boundary-code-context").textContent, /FeaturesService\.createFeature\(\):54/);
  assert.equal(h.element("#timeline").querySelector('[data-event-index="2"]').classList.contains("evidence-highlight"), true);
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
  assert.equal(h.calls.some(call => call.path === "/api/viewer-context"), false);
  assert.equal(h.posts().length, 0);
});

function localBoundaryFixture() {
  const fixture = boundaryFixture();
  const owner = {...fixture.owner, service: "local",
    segmentKey: '["local","ems-instance","' + OTHER_ID + '"]'};
  const node = {...fixture.node, service: owner.service, segmentKey: owner.segmentKey};
  const doc = {...journey([owner]), visualization: {nodes: [node], edges: []}};
  const untagged = {...owner}; delete untagged.service;
  return {node, owner, doc, untagged, saved: pin(ID, {segments: [untagged], journey: doc})};
}

test("single viewer mode explicitly binds an untagged live export to its exact local owner", async () => {
  const fixture = localBoundaryFixture();
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response(fixture.untagged);
    if (path === "/api/viewer-context") return response({mode: "single", namespace: "test-single"});
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.equal(h.calls.find(call => call.path.startsWith("/api/render-data/")).path,
    "/api/render-data/" + OTHER_ID + "?service=local&instance=ems-instance");
  assert.equal(h.calls.filter(call => call.path === "/api/viewer-context").length, 1);
  assert.match(h.element("#boundary-code-context").textContent, /FeaturesService\.createFeature\(\):54/);
  const line = h.element("#timeline").querySelector('[data-event-index="1"]');
  assert.equal(line.classList.contains("evidence-highlight"), true);
  assert.equal(line.focusCalls.length, 1);
  assert.equal(line.all().find(node => node.className === "boundary-code-source").textContent, "Open local source");
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
  assert.equal(fixture.untagged.service, undefined, "the original export is not rewritten");
});

test("federated viewer mode never assigns a local service tag to an untagged export", async () => {
  const fixture = localBoundaryFixture();
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response(fixture.untagged);
    if (path === "/api/viewer-context") return response({mode: "federated", namespace: "test"});
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.equal(h.calls.filter(call => call.path === "/api/viewer-context").length, 1);
  const context = h.element("#boundary-code-context");
  assert.match(context.textContent, /Calling code unavailable.*exact retained boundary could not be identified safely/);
  assert.equal(context.all().some(node => node.textContent === "Open local source"), false);
  assert.equal(h.element("#timeline").all().some(node => node.classList.contains("evidence-highlight")), false);
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
});

test("saved untagged local evidence is never normalized using current viewer configuration", async () => {
  const fixture = localBoundaryFixture();
  const h = await harness({hash: "#snapshot=" + ID, fetch: path => {
    if (path === "/api/snapshots/" + ID) return response(fixture.saved);
    if (path === "/api/viewer-context") return response({mode: "single", namespace: "test-single"});
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.match(h.element("#boundary-code-context").textContent, /Calling code unavailable/);
  assert.equal(h.calls.some(call => /viewer-context|render-data|source-location|segment-execution/.test(call.path)), false);
  assert.equal(h.element("#timeline").all().some(node => node.classList.contains("evidence-highlight")), false);
});

test("stale single-viewer mode verification does not reveal boundary context after navigation", async () => {
  const fixture = localBoundaryFixture(), pending = deferred();
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response(fixture.untagged);
    if (path === "/api/viewer-context") return pending.promise;
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.equal(h.calls.filter(call => call.path === "/api/viewer-context").length, 1);
  h.home(); await settle(); pending.resolve(response({mode: "single"})); await settle();
  assert.equal(h.location.hash, "");
  assert.equal(h.element("#detail-view").classList.contains("hidden"), true);
  assert.equal(h.element("#boundary-code-context").textContent, "");
  assert.equal(h.element("#timeline").all().some(node => node.classList.contains("evidence-highlight")), false);
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
});

test("a live response missing service cannot resolve or offer another service's source", async () => {
  const fixture = boundaryFixture();
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response({...fixture.owner, service: undefined});
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  const context = h.element("#boundary-code-context");
  assert.match(h.element("#journey-meta").textContent, /Calling code in cb-ems unavailable.*does not match this captured service\/request/);
  assert.equal(context.textContent, "");
  assert.equal(h.element("#journey-view").classList.contains("hidden"), false);
  assert.equal(context.all().some(node => node.textContent === "Open local source"), false);
  assert.equal(h.element("#timeline").all().some(node => node.classList.contains("evidence-highlight")), false);
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
  assert.equal(h.calls.some(call => call.path === "/api/viewer-context"), false);
});

test("late boundary segment responses cannot navigate or highlight after returning home", async () => {
  const fixture = boundaryFixture(), pending = deferred();
  const h = await harness({hash: "#trace=" + TRACE, fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return pending.promise;
  }});
  h.graphs.at(-1).options.onOpenBoundary(fixture.node); await settle();
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 1);
  h.home(); await settle(); pending.resolve(response(fixture.owner)); await settle();
  assert.equal(h.location.hash, "");
  assert.equal(h.element("#list-view").classList.contains("hidden"), false);
  assert.equal(h.element("#detail-view").classList.contains("hidden"), true);
  assert.equal(h.element("#boundary-code-context").textContent, "");
  assert.equal(h.calls.some(call => /source-location|segment-execution/.test(call.path)), false);
});

function performanceFixture() {
  const fixture = boundaryFixture("EXTERNAL_CALL");
  fixture.marker.attributes = {boundaryKind: "database", system: "postgresql", direction: "outbound",
    evidenceKind: "jdbc-execution", operation: "executeQuery", status: "OBSERVED", sql: "select * from orders where id = ?",
    sqlSource: "prepared", sqlTruncated: false, durationMs: 250};
  fixture.node = {...fixture.node, ...dependencies.boundaryMetadata(fixture.marker)};
  fixture.doc.visualization = {schemaVersion: 1, kind: "request-flow", traceId: TRACE, nodes: [fixture.node], edges: []};
  fixture.saved = pin(ID, {segments: [segment(), fixture.owner], journey: fixture.doc});
  return fixture;
}

test("Performance tab entry, source refresh and ordinary polling do not load analysis graphs", async () => {
  const h = await harness(), panel = h.performance[0], start = h.calls.length;
  assert.equal(panel.visible, false); h.mode("performance");
  const sources = {requests: requests.groupTraces([segment()]), pins: [pin()], partial: true};
  h.requestOptions.onPerformanceSources(sources); h.tick(); await settle();
  assert.equal(panel.visible, true); assert.equal(panel.sources[0], sources);
  assert.equal(h.calls.slice(start).some(call => /trace-visualization|distributed-trace|render-data|snapshots\//.test(call.path)), false);
  h.mode("live"); assert.equal(panel.visible, false);
});

test("Performance query evidence opens its validated live owner after journey rendering", async () => {
  const fixture = performanceFixture(), h = await harness({fetch: path => {
    if (path.startsWith("/api/distributed-trace/")) return response(fixture.doc);
    if (path.startsWith("/api/render-data/")) return response(fixture.owner);
  }});
  h.mode("performance");
  h.performance[0].options.onOpenBoundary({request: requests.groupTraces([segment()])[0], node: fixture.node}); await settle();
  assert.equal(h.calls.filter(call => call.path.startsWith("/api/render-data/")).length, 1);
  assert.match(h.element("#boundary-code-context").textContent, /FeaturesService\.createFeature\(\):54/);
  assert.equal(h.element("#timeline").querySelector('[data-event-index="2"]').classList.contains("evidence-highlight"), true);
  assert.equal(h.performance[0].visible, false);
});

test("Performance saved query evidence uses the loaded pin and makes no live read", async () => {
  const fixture = performanceFixture(), h = await harness(); h.mode("performance");
  const start = h.calls.length;
  h.performance[0].options.onOpenBoundary({request: requests.groupTraces([segment()])[0], pin: fixture.saved, node: fixture.node}); await settle();
  assert.equal(h.calls.length, start, "Opening retained saved JDBC evidence does not consult live services");
  assert.equal(h.location.hash, "#snapshot=" + ID);
  assert.match(h.element("#boundary-code-context").textContent, /FeaturesService\.createFeature\(\):54/);
  assert.equal(h.element("#timeline").querySelector('[data-event-index="2"]').classList.contains("evidence-highlight"), true);
  assert.equal(h.performance[0].visible, false);
});

test("Performance restores its controller after expired request navigation fails", async () => {
  for (const boundary of [false, true]) {
    const h = await harness({fetch: path => path.startsWith("/api/distributed-trace/") ? response({error: "Captured request expired"}, 404) : undefined});
    const panel = h.performance[0]; h.mode("performance");
    const request = requests.groupTraces([segment()])[0];
    if (boundary) panel.options.onOpenBoundary({request, node: performanceFixture().node}); else panel.options.onOpenRequest(request);
    await settle();
    assert.equal(h.element("#list-view").classList.contains("hidden"), false);
    assert.equal(panel.visible, true, "Analyze and the prior results' actions remain usable");
    assert.match(h.element("#notice").textContent, /Captured request expired/);
  }
});

test("tab changes invalidate pending Performance navigation and cannot reopen old query evidence", async () => {
  for (const boundary of [false, true]) {
    const fixture = performanceFixture(), pending = deferred();
    const h = await harness({fetch: path => path.startsWith("/api/distributed-trace/") ? pending.promise : undefined});
    h.mode("performance"); const panel = h.performance[0], request = requests.groupTraces([segment()])[0];
    if (boundary) panel.options.onOpenBoundary({request, node: fixture.node}); else panel.options.onOpenRequest(request);
    await settle(); h.mode("live"); pending.resolve(response(fixture.doc)); await settle();
    assert.equal(h.location.hash, ""); assert.equal(h.element("#list-view").classList.contains("hidden"), false);
    assert.equal(h.element("#boundary-code-context").textContent, "");
    assert.equal(h.calls.some(call => call.path.startsWith("/api/render-data/")), false);
    assert.equal(panel.visible, false);
  }
});

test("snapshot-hash departure immediately cancels Performance even when the saved read fails", async () => {
  const pending = deferred(), h = await harness({fetch: path => path === "/api/snapshots/" + ID ? pending.promise : undefined});
  h.mode("performance"); const panel = h.performance[0]; assert.equal(panel.visible, true);
  h.hash("#snapshot=" + ID); await settle();
  assert.equal(h.element("#list-view").classList.contains("hidden"), true);
  assert.equal(panel.visible, false, "Cancellation happens before the saved response settles");
  pending.resolve(response({error: "Saved capture unavailable"}, 404)); await settle();
  assert.equal(panel.visible, false); assert.match(h.element("#journey-meta").textContent, /Saved snapshot unavailable/);
});

test("Performance graph reads validate request identity and enforce the 5 MiB byte cap", async () => {
  const request = requests.groupTraces([segment()])[0];
  const h = await harness({fetch: path => path.startsWith("/api/trace-visualization/") ? response({traceId: TRACE, nodes: [], edges: []}) : undefined});
  const graph = await h.performance[0].options.loadRequest(request);
  assert.equal(graph.traceId, TRACE);
  assert.equal(h.calls.at(-1).path, "/api/trace-visualization/" + TRACE); assert.equal(h.calls.at(-1).method, "GET");
  const mismatch = await harness({fetch: path => path.startsWith("/api/trace-visualization/") ? response({traceId: OTHER_TRACE}) : undefined});
  await assert.rejects(mismatch.performance[0].options.loadRequest(request), /graph identity does not match/);
  let cancelled = false;
  const oversized = await harness({fetch: path => path.startsWith("/api/trace-visualization/") ? {ok: true, body: {getReader: () => ({
    read: async () => ({done: false, value: new Uint8Array(5 * 1024 * 1024 + 1)}), cancel: async () => { cancelled = true; }
  })}} : undefined});
  await assert.rejects(oversized.performance[0].options.loadRequest(request), /5 MiB analysis read limit.*not analyzed/);
  assert.equal(cancelled, true);
  const before = h.calls.length;
  await assert.rejects(h.performance[0].options.loadRequest({...request, traceId: null}), /no trace identity/);
  assert.equal(h.calls.length, before);
});

test("Performance saved graph loading rejects missing or mismatched evidence without live fallback", async () => {
  const good = performanceFixture().saved;
  for (const saved of [pin(), {...good, journey: {...good.journey, visualization: {...good.journey.visualization, traceId: OTHER_TRACE}}}, {...good, id: OTHER_ID}]) {
    const h = await harness({fetch: path => path === "/api/snapshots/" + ID ? response(saved) : undefined});
    const start = h.calls.length;
    await assert.rejects(h.performance[0].options.loadPin({id: ID, storage: "viewer"}), /No SQL request graph|graph identity does not match|capture is unavailable/);
    assert.deepEqual(h.calls.slice(start).map(call => call.path), ["/api/snapshots/" + ID]);
  }
  let localReads = 0;
  const h = await harness({browserPin: id => { localReads++; assert.equal(id, ID); return {...good, storage: "browser"}; }});
  const start = h.calls.length, loaded = await h.performance[0].options.loadPin({id: ID, storage: "browser"});
  assert.equal(localReads, 1); assert.equal(loaded.graph, good.journey.visualization); assert.equal(h.calls.length, start);
});
