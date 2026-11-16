"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const guided = require("../tracelens_cli/web/guided-investigation.js");
const TRACE = "1".repeat(32), OTHER = "2".repeat(32);
const row = (extra = {}) => ({traceId: TRACE, segmentId: "root", service: "app", serviceInstanceId: "instance",
  entryChannel: "HTTP", httpMethod: "GET", path: "/api/orders", user: "Alice", siteId: "42",
  startedAtMillis: 1001, status: "COMPLETED", httpStatus: 200, ...extra});
const doc = (...rows) => ({traces: rows, partial: false, limited: false});
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; };

function harness(responses = [], extraOptions = {}) {
  class Element {
    constructor(tag) { this.tagName = tag; this.childNodes = []; this.value = ""; this.disabled = false; }
    set value(value) { this.selected = this.tagName === "select" && !this.childNodes.some(child => child.value === value) ? "" : value; }
    get value() { return this.selected; }
    set innerHTML(_) { throw new Error("Never render untrusted HTML"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(c => c.textContent).join(""); }
    append(...nodes) { this.childNodes.push(...nodes); }
    replaceChildren(...nodes) { this.text = ""; this.childNodes = nodes; }
    setAttribute(key, value) { this[key] = value; }
    focus() { this.focused = true; }
    all() { return this.childNodes.flatMap(c => [c, ...c.all()]); }
  }
  const document = {createElement: tag => new Element(tag)}, container = new Element("main");
  const calls = [], reads = [], saved = [], opened = [], notifications = [], timers = new Map();
  let clock = 1000, nextTimer = 1, save = async request => ({request, partial: false});
  const options = {document, now: () => clock, services: ["app", "ems"],
    setTimeout: (fn, delay) => { const id = nextTimer++; timers.set(id, {fn, at: clock + delay}); return id; },
    clearTimeout: id => timers.delete(id),
    api: async (path, method, maxBytes) => {
      assert.equal(method, "GET", "Capture checks and watches must remain read-only");
      calls.push(path); reads.push({method, maxBytes}); const response = responses.shift() ?? doc();
      if (response instanceof Error) throw response; return response; },
    saveTrace: async (request, guard) => { saved.push({request, guard}); return save(request, guard); },
    onOpen: id => opened.push(id), onSaved: capture => notifications.push(capture)};
  const ui = guided.render(container, {...options, ...extraOptions});
  const find = predicate => container.all().find(predicate);
  const field = name => find(e => e.name === name);
  if (!extraOptions.draft) field("path").value = "/api/orders";
  return {ui, container, calls, reads, saved, opened, notifications, timers, field,
    button: label => find(e => e.tagName === "button" && e.textContent === label),
    confirmation: () => find(e => e.type === "checkbox"),
    setSave: fn => { save = fn; }, responses,
    diagnose: () => find(e => e.tagName === "form").onsubmit({preventDefault() {}}),
    async advance(ms) {
      const target = clock + ms;
      while (true) {
        const pending = [...timers].filter(([, t]) => t.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
        if (!pending) break;
        timers.delete(pending[0]); clock = pending[1].at; pending[1].fn(); await flush();
      }
      clock = target; await flush();
    }};
}

test("exact bounded path validation rejects URLs, route patterns, query and controls", () => {
  for (const path of ["", "https://host/api", "//host/api", "/api?a=1", "/api#x", "/api/*", "/api/{id}", "/api%0a", "/api%zz", "/api\n"]) {
    assert.throws(() => guided.validate({path}));
  }
  assert.equal(guided.validate({path: "/api/orders", method: "get"}).method, "GET");
});

test("recovery drafts distinguish substring suggestions from exact filters and discard incompatible filters", () => {
  const draft = guided.recoveryDraft({channel: "HTTP", path: "/api/order", user: "Ali", method: "post", service: "ems", site: "42",
    query: "trace-label", sinceMinutes: "30", minDuration: "200", maxDuration: "500", outcome: "error", keyword: "body-secret"}, {mode: "live"});
  assert.deepEqual(draft, {source: "empty-requests", filters: {service: "ems", method: "POST", site: "42"},
    pathSuggestion: "/api/order", userSuggestion: "Ali"});
  for (const context of [{mode: "jobs"}, {mode: "pins"}, {mode: "errors"}, {mode: "performance"},
    {mode: "live", saved: true}, {mode: "live", offline: true}]) {
    assert.equal(guided.recoveryDraft({channel: "HTTP"}, context), null);
  }
  for (const channel of ["SCHEDULER", "SQS", "OTHER"]) assert.equal(guided.recoveryDraft({channel}, {mode: "live"}), null);
  assert.ok(guided.recoveryDraft({channel: ""}, {mode: "live"}));
  assert.deepEqual(guided.recoveryDraft({path: "a".repeat(2049), user: "Alice\n", method: "GET /", service: "bad\0", site: 42}, {mode: "live"}),
    {source: "empty-requests", filters: {service: "", method: "", site: "42"}, pathSuggestion: "", userSuggestion: ""});
});

test("prepare never checks, arms, polls or saves and requires explicit exact-match confirmation", async () => {
  const h = harness([{checks: [], candidates: []}]);
  h.ui.prepare({...guided.recoveryDraft({path: "/api/order", user: "Ali", service: "ems", method: "post", site: "42"}, {mode: "live"}), intent: "watch"});
  assert.equal(h.field("path").value, "/api/order"); assert.equal(h.field("user").value, "Ali");
  assert.equal(h.field("service").value, "ems"); assert.equal(h.field("method").value, "POST");
  assert.equal(h.confirmation().checked, false); assert.match(h.container.textContent, /suggestions, not confirmed exact matches/);
  await h.advance(120_000);
  assert.equal(h.calls.length, 0); assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
  await h.diagnose(); await h.button("Watch next request & save").onclick();
  assert.equal(h.calls.length, 0); assert.match(h.container.textContent, /confirm them before checking or watching/);
  h.field("path").value = "/api/orders/123"; h.field("path").oninput();
  h.field("user").value = "Alice"; h.confirmation().checked = true;
  await h.diagnose();
  assert.deepEqual(h.calls, ["/api/guided-investigation?path=%2Fapi%2Forders%2F123&method=POST&service=ems&user=Alice&site=42"]);
  h.field("site").value = "43"; h.field("site").onchange();
  assert.equal(h.confirmation().checked, false);
  await h.diagnose(); assert.equal(h.calls.length, 1);
});

test("setup drafts render without I/O and unavailable service selections never broaden to all services", async () => {
  const h = harness([], {draft: {source: "setup", intent: "check", filters: {service: "unavailable"}}});
  assert.equal(h.field("path").value, ""); assert.equal(h.field("service").value, "unavailable");
  assert.match(h.container.textContent, /Not configured: unavailable/);
  assert.equal(h.calls.length, 0); assert.equal(h.timers.size, 0);
  h.field("path").value = "/api/orders"; h.confirmation().checked = true;
  await h.diagnose(); await h.button("Watch next request & save").onclick();
  assert.equal(h.calls.length, 0); assert.match(h.container.textContent, /Choose a configured service/);
  h.ui.setServices(["app", "ems", "unavailable"]);
  assert.equal(h.field("service").value, "unavailable");
  await h.diagnose(); assert.equal(h.calls.length, 1);
  assert.match(h.calls[0], /service=unavailable/);
});

test("preparing a new draft cancels an earlier watch and invalidates pending reads", async () => {
  const pending = deferred(), h = harness([doc(), pending.promise]);
  await h.button("Watch next request & save").onclick(); await h.advance(1000);
  h.ui.prepare({source: "setup", filters: {service: "app"}});
  assert.equal(h.ui.phase, "idle"); assert.equal(h.timers.size, 0);
  pending.resolve(doc(row())); await flush(); await h.advance(120_000);
  assert.equal(h.saved.length, 0); assert.equal(h.calls.length, 2);
  assert.match(h.container.textContent, /No check or watch has run from this form preparation/);
});

test("preparing while an earlier save is pending retains the explicit save uncertainty", async () => {
  const pending = deferred(), h = harness([doc(), doc(row()), doc(row())]);
  h.setSave(() => pending.promise);
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  h.ui.prepare({source: "setup", filters: {service: "app"}});
  assert.match(h.container.textContent, /previous save may already be in progress or committed/);
  assert.equal(h.timers.size, 0);
  pending.resolve({partial: false}); await flush(); assert.equal(h.notifications.length, 0);
});

test("capture readiness label and explanation describe a read-only setup and existing-trace check", async () => {
  const pending = deferred(), h = harness([pending.promise]);
  assert.equal(h.button("Check capture readiness").type, "submit");
  assert.equal(h.button("Diagnose"), undefined);
  assert.match(h.container.textContent, /Check capture setup and look for existing traces/);
  assert.match(h.container.textContent, /This does not diagnose application bugs/);
  assert.match(h.container.textContent, /Ready to check capture readiness/);
  const checking = h.diagnose();
  assert.match(h.container.textContent, /Checking capture setup and looking for existing traces/);
  pending.resolve({checks: [], candidates: [], partial: true});
  await checking;
  assert.deepEqual(h.calls, ["/api/guided-investigation?path=%2Fapi%2Forders"]);
  assert.deepEqual(h.reads, [{method: "GET", maxBytes: 1024 * 1024}]);
  assert.match(h.container.textContent, /Existing matching requests: 0/);
  assert.match(h.container.textContent, /Some capture evidence could not be checked/);
  assert.match(h.container.textContent, /No match does not prove a request never ran or that it expired/);
  assert.equal(h.saved.length, 0);
  assert.equal(h.notifications.length, 0);
  assert.equal(h.timers.size, 0);
  assert.equal(h.ui.phase, "idle");
});

test("unavailable capture readiness check explains that nothing was changed", async () => {
  const h = harness([new Error("unavailable")]);
  await h.diagnose();
  assert.match(h.container.textContent, /Capture readiness check unavailable/);
  assert.match(h.container.textContent, /nothing was changed/);
  assert.equal(h.saved.length, 0);
  assert.equal(h.timers.size, 0);
  assert.equal(h.container.all().find(element => element.className === "guided-status").focused, true);
});

const readinessFixture = (services = ["app"]) => ({partial: false, limited: false, candidates: [], warnings: [],
  checks: services.flatMap(service => ["reachability", "metadata", "agent", "recording", "http_path", "labels", "scope", "retention"]
    .map(id => ({service, id, status: ["scope", "retention"].includes(id) ? "unknown" : "pass", message: "Evidence for " + id})))});

test("readiness requires complete passing admission checks without claiming code coverage or retained history", async () => {
  assert.equal(guided.readiness(readinessFixture(), ["app"]).state, "ready");
  const h = harness([readinessFixture()], {services: ["app"]}); await h.diagnose();
  assert.match(h.container.textContent, /Ready to reproduce.*Next: choose Watch next request/);
  assert.match(h.container.textContent, /not a capture guarantee/);
  assert.match(h.container.textContent, /Code coverage and retained evidence remain unverified/);
  assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
  const passed = h.container.all().find(element => element.className === "guided-check-details");
  assert.equal(passed.open, undefined, "passing detail stays collapsed while limitations remain visible");
  assert.match(passed.textContent, /6 passed checks/);
});

test("failed or excluded checks and label requirements produce actionable readiness, never Ready", () => {
  for (const [id, status] of [["agent", "fail"], ["http_path", "excluded"], ["labels", "warn"], ["scope", "warn"]]) {
    const result = readinessFixture(); result.checks.find(check => check.id === id).status = status;
    const summary = guided.readiness(result, ["app"]);
    assert.equal(summary.state, "action"); assert.equal(summary.headline, "Action required");
    assert.match(summary.next, /Resolve the checks.*Saving settings alone does not apply/);
  }
});

test("unknown, malformed, duplicate, partial and truncated readiness evidence cannot become Ready", () => {
  const changes = [
    result => { result.partial = true; }, result => { delete result.partial; },
    result => { result.limited = true; }, result => { delete result.limited; },
    result => { result.checks.find(check => check.id === "metadata").status = "unknown"; },
    result => { result.checks[0].message = ""; }, result => { result.checks[0].status = "unexpected"; },
    result => { result.checks.pop(); }, result => { result.checks.push({...result.checks[0]}); },
    result => { result.checks[0].service = "unselected"; }, result => { result.checks[0].id = "unexpected"; },
    result => { result.checks = Array(289).fill(result.checks[0]); },
    result => { result.errors = ["unavailable"]; }, result => { result.warnings = ["report_detail_limit"]; }
  ];
  for (const change of changes) {
    const result = readinessFixture(); change(result);
    assert.equal(guided.readiness(result, ["app"]).headline, "Cannot verify");
  }
  assert.equal(guided.readiness(readinessFixture(), ["app", "ems"]).state, "unknown", "every selected service is required");
  assert.equal(guided.readiness(readinessFixture(), []).state, "unknown");
  assert.equal(guided.readiness({}, ["app"]).state, "unknown");
});

test("invalid investigation input focuses its actionable error and performs no reads", async () => {
  const h = harness(); h.field("path").value = "https://app.example/page"; await h.diagnose();
  const status = h.container.all().find(element => element.className === "guided-status");
  assert.equal(status.focused, true); assert.match(status.textContent, /Use an exact backend path/);
  assert.equal(h.calls.length, 0); assert.equal(h.saved.length, 0);
});

test("diagnosis validates before I/O, uses bounded query and renders hostile text without HTML", async () => {
  const h = harness([{checks: [{service: "app", id: "http_path", status: "excluded", message: "<img onerror=bad>"}],
    candidates: [{traceId: TRACE, path: "/api/orders", method: "GET"}], guidance: ["No mutation."]}]);
  h.field("path").value = "https://host/api"; await h.diagnose();
  assert.equal(h.calls.length, 0);
  h.field("path").value = "/api/orders"; h.field("user").value = "Alice";
  await h.diagnose();
  assert.equal(h.calls[0], "/api/guided-investigation?path=%2Fapi%2Forders&user=Alice");
  assert.match(h.container.textContent, /<img onerror=bad>/);
  h.button("GET · /api/orders · " + TRACE).onclick();
  assert.deepEqual(h.opened, [TRACE]);
});

test("watch only polls summaries and saves one newly started completed identity after confirmation", async () => {
  const h = harness([doc(), doc(row({status: "ACTIVE"})), doc(row()), doc(row())]);
  await h.button("Watch next request & save").onclick();
  assert.match(h.container.textContent, /Watching for 60 seconds/);
  await h.advance(1000); assert.equal(h.saved.length, 0);
  await h.advance(1000); assert.equal(h.saved.length, 0);
  await h.advance(1000); assert.equal(h.saved.length, 1);
  assert.equal(h.saved[0].request.traceId, TRACE);
  assert.equal(h.saved[0].request.key, "trace:" + TRACE);
  assert.equal(h.saved[0].guard.armedAt, 1000);
  assert.equal(h.notifications.length, 1);
  assert.equal(h.timers.size, 0);
  assert.ok(h.calls.every(path => path === "/api/distributed-traces?limit=1000"));
});

test("baseline and before-arming requests never get captured even if newly updated", async () => {
  const h = harness([doc(row()), doc(row(), row({traceId: OTHER, segmentId: "other", startedAtMillis: 999})), doc(row())]);
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  assert.equal(h.saved.length, 0); h.ui.destroy();
  assert.equal(h.timers.size, 0);
});

test("filters are exact and must match one HTTP segment", () => {
  const requests = guided.summaries(doc(row(), row({segmentId: "child", parentSpanId: "parent", path: "/child", user: "Bob"})));
  assert.equal(guided.matches(requests[0], {path: "/api/orders", user: "Alice", site: "42", method: "GET", service: "app"}), true);
  assert.equal(guided.matches(requests[0], {path: "/api/order"}), false);
  assert.equal(guided.matches(requests[0], {path: "/api/orders", user: "alice"}), false);
  assert.equal(guided.matches(requests[0], {path: "/api/orders", user: "Bob"}), false);
  assert.equal(guided.matches(guided.summaries(doc(row({entryChannel: "SCHEDULER"})))[0], {path: "/api/orders"}), false);
});

test("multiple matches stop without selecting a latest request or exporting", async () => {
  const h = harness([doc(), doc(row(), row({traceId: OTHER, segmentId: "two", startedAtMillis: 1100}))]);
  await h.button("Watch next request & save").onclick(); await h.advance(1000);
  assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
  assert.match(h.container.textContent, /Multiple new matching requests/);
});

test("late second match also stops an already-selected active request", async () => {
  const h = harness([doc(), doc(row({status: "ACTIVE"})), doc(row({traceId: OTHER, segmentId: "two", startedAtMillis: 1100}))]);
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  assert.equal(h.saved.length, 0); assert.match(h.container.textContent, /Multiple new matching/);
});

test("partial, limited or conflicting discovery never starts a capture", async () => {
  for (const invalid of [{...doc(row()), partial: true}, {...doc(row()), limited: true}, doc(row(), row({status: "ACTIVE"}))]) {
    const h = harness([doc(), invalid]); await h.button("Watch next request & save").onclick(); await h.advance(1000);
    assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
    assert.equal(h.ui.phase, "idle");
  }
});

test("root start must be new and all observed siblings must be completed", async () => {
  const h = harness([doc(), doc(row(), row({segmentId: "child", parentSpanId: "x", path: "/child", status: "ACTIVE"})),
    doc(row({startedAtMillis: 999}), row({segmentId: "child", parentSpanId: "x", path: "/child", startedAtMillis: 1500}))]);
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  assert.equal(h.saved.length, 0); h.ui.destroy();
});

test("identity change or eviction cannot silently select replacement evidence", async () => {
  for (const next of [doc(row({serviceInstanceId: "new-instance"})), doc()]) {
    const h = harness([doc(), doc(row({status: "ACTIVE"})), next]);
    await h.button("Watch next request & save").onclick(); await h.advance(2000);
    assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
    assert.match(h.container.textContent, /identity changed|no longer retained/);
  }
});

test("deadline fires despite a pending request and ignores its late response", async () => {
  const pending = deferred(), h = harness([doc(), pending.promise]);
  await h.button("Watch next request & save").onclick(); await h.advance(60_000);
  assert.match(h.container.textContent, /Watch timed out/); assert.equal(h.timers.size, 0);
  pending.resolve(doc(row())); await flush();
  assert.equal(h.saved.length, 0); assert.equal(h.ui.phase, "idle");
});

test("cancel and destroy invalidate in-flight poll and saving effects", async () => {
  for (const destroy of [false, true]) {
    const pending = deferred(), h = harness([doc(), pending.promise]);
    await h.button("Watch next request & save").onclick(); await h.advance(1000);
    destroy ? h.ui.destroy() : h.button("Cancel watch").onclick();
    pending.resolve(doc(row())); await flush(); await h.advance(10_000);
    assert.equal(h.saved.length, 0); assert.equal(h.notifications.length, 0); assert.equal(h.timers.size, 0);
  }
  const pendingSave = deferred(), h = harness([doc(), doc(row()), doc(row())]);
  h.setSave(async (_, guard) => { await pendingSave.promise; assert.equal(guard.isCurrent(), false); return {partial: true}; });
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  h.ui.destroy(); pendingSave.resolve(); await flush();
  assert.equal(h.notifications.length, 0); assert.equal(h.timers.size, 0);
});

test("cancel or timeout during saving never falsely says nothing was saved", async () => {
  for (const cancel of [true, false]) {
    const pending = deferred(), h = harness([doc(), doc(row()), doc(row())]);
    h.setSave(() => pending.promise);
    await h.button("Watch next request & save").onclick(); await h.advance(2000);
    if (cancel) h.ui.cancel(); else await h.advance(58_000);
    assert.match(h.container.textContent, /save may already be in progress or committed; check Saved captures/);
    assert.doesNotMatch(h.container.textContent, /Nothing was saved|No further capture will be saved|No unambiguous completed new match was saved/);
    pending.resolve({partial: false}); await flush();
    assert.equal(h.notifications.length, 0); assert.equal(h.timers.size, 0);
  }
});

test("arming itself times out and stale diagnosis does not change current UI", async () => {
  const pending = deferred(), h = harness([pending.promise]);
  h.button("Watch next request & save").onclick(); await flush(); await h.advance(60_000);
  pending.resolve(doc()); await flush();
  assert.match(h.container.textContent, /timed out while arming/); assert.equal(h.timers.size, 0);
  const diagnosis = deferred(), second = harness([diagnosis.promise]);
  second.diagnose(); await flush(); second.ui.destroy();
  diagnosis.resolve({checks: [{message: "OLD RESULT"}]}); await flush();
  assert.equal(second.container.textContent, "");
});

test("baseline latency is inside the selected 60-second deadline, not an extension", async () => {
  const baseline = deferred(), poll = deferred(), h = harness([baseline.promise, poll.promise]);
  h.button("Watch next request & save").onclick(); await flush();
  await h.advance(30_000); baseline.resolve(doc()); await flush();
  assert.match(h.container.textContent, /Watching for 30 seconds/);
  await h.advance(30_000);
  assert.match(h.container.textContent, /Watch timed out/);
  poll.resolve(doc(row({startedAtMillis: 31001}))); await flush();
  assert.equal(h.saved.length, 0); assert.equal(h.timers.size, 0);
});

test("120-second opt-in duration and partial capture warning remain explicit", async () => {
  const h = harness([doc(), doc(row()), doc(row())]);
  h.container.all().find(e => e["aria-label"] === "Watch window").value = "120";
  h.setSave(async () => ({partial: true}));
  await h.button("Watch next request & save").onclick(); await h.advance(2000);
  assert.equal(h.saved[0].guard.deadline, 121000);
  assert.match(h.container.textContent, /PARTIAL point-in-time capture/);
});
