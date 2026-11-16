const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const resource = path.resolve(__dirname, "../../tracelens-tool/src/main/resources/tracelens-setup");
const setup = require(path.join(resource, "setup.js"));
const defaults = extra => ({installDir: "/tmp/local-tracelens", viewerPort: 8765, captureJdbcQueries: false, captureJdbcParameters: false, captureMode: "detailed",
  cbApp: {enabled: true, sourceRoot: "/work/cb-app", debugPort: 7778, httpIncludePaths: ["/api/"]},
  ems: {enabled: false, sourceRoot: "/work/ems", debugPort: 7779, httpIncludePaths: ["/features"], launchJar: "", javaHome: ""},
  plans: {enabled: false, sourceRoot: "/work/plans", debugPort: 7786, httpIncludePaths: ["/api/"], launchJar: "", javaHome: ""},
  ledger: {enabled: false, sourceRoot: "/work/ledger", debugPort: 7783, httpIncludePaths: ["/api/"]},
  go: {enabled: false, sourceRoot: "/work/go", debugPort: 7784, httpIncludePaths: ["/api/"]}, ...extra});

test("setup request uses the installer contract and retains explicit service choices", () => {
  const input = defaults(), before = JSON.stringify(input);
  assert.deepEqual(setup.requestFrom(input), {...input, ems: {enabled: false}, plans: {enabled: false}, ledger: {enabled: false}, go: {enabled: false}});
  assert.equal(JSON.stringify(input), before);
  const output = setup.requestFrom(defaults({ems: {enabled: true, sourceRoot: " /work/ems ", debugPort: "9000", httpIncludePaths: "/a/, /b/\n/a/", launchJar: " /work/ems.jar ", javaHome: " /jdk "}}));
  assert.deepEqual(output.ems, {enabled: true, sourceRoot: "/work/ems", debugPort: 9000, httpIncludePaths: ["/a/", "/b/"], launchJar: "/work/ems.jar", javaHome: "/jdk"});
  assert.equal("services" in output, false); assert.equal("apiPrefixes" in output.ems, false);
});

test("SQL parameter capture cannot be enabled without explicit query capture", () => {
  assert.equal(setup.requestFrom(defaults({captureJdbcParameters: true})).captureJdbcParameters, false);
  assert.equal(setup.requestFrom(defaults({captureJdbcQueries: true, captureJdbcParameters: true})).captureJdbcParameters, true);
  assert.equal(setup.requestFrom(defaults({captureJdbcQueries: "true", captureJdbcParameters: "true"})).captureJdbcQueries, false);
});

test("capture level is explicit and rejects unsupported modes", () => {
  assert.equal(setup.requestFrom(defaults({captureMode: "overview"})).captureMode, "overview");
  assert.equal(setup.requestFrom(defaults({captureMode: "methods"})).captureMode, "methods");
  assert.equal(setup.requestFrom(defaults({captureMode: undefined})).captureMode, "detailed");
  assert.throws(() => setup.requestFrom(defaults({captureMode: "unlimited"})), /capture/);
});

test("only common invalid settings or no service selection block local review", () => {
  for (const viewerPort of [0, 65536, 1.5, "1e3", "", "abc", null]) assert.throws(() => setup.requestFrom(defaults({viewerPort})), /Viewer port/);
  assert.throws(() => setup.requestFrom(defaults({installDir: " "})), /installation directory/);
  assert.throws(() => setup.requestFrom(defaults({cbApp: {...defaults().cbApp, enabled: false}})), /Select at least one/);
});

test("bad selected-service fields reach independent server validation without blocking valid additions", () => {
  const input = defaults({plans: {enabled: true, sourceRoot: "", debugPort: "bad-port", httpIncludePaths: [], launchJar: "/work/plans.jar", javaHome: ""}});
  const output = setup.requestFrom(input);
  assert.equal(output.cbApp.enabled, true); assert.equal(output.plans.sourceRoot, "");
  assert.equal(output.plans.debugPort, "bad-port"); assert.deepEqual(output.plans.httpIncludePaths, []);
  assert.equal(output.plans.launchJar, "/work/plans.jar"); assert.equal(output.plans.javaHome, "");
  for (const prefix of ["/", "/api/*", "/api/?q=1", "//api", "/api/../x", "/api/.", "/api/é", "/" + "a".repeat(512)]) {
    assert.deepEqual(setup.requestFrom(defaults({plans: {...defaults().plans, enabled: true, httpIncludePaths: [prefix]}})).plans.httpIncludePaths, [prefix]);
  }
  assert.equal(setup.requestFrom(defaults({ledger: {...defaults().ledger, enabled: true, debugPort: 7778}})).ledger.debugPort, 7778,
    "port conflicts are reported for the affected services by the server");
});

test("disabled service fields are ignored, including stale invalid ports and launcher fields", () => {
  const input = defaults();
  for (const key of ["ems", "plans", "ledger", "go"]) input[key] = {enabled: false, sourceRoot: "", debugPort: "not-a-port",
    httpIncludePaths: ["*"], launchJar: "/old.jar", javaHome: null};
  const output = setup.requestFrom(input);
  for (const key of ["ems", "plans", "ledger", "go"]) assert.deepEqual(output[key], {enabled: false});
});

test("all five named services retain selections and only EMS and Plans accept JAR launchers", () => {
  const input = defaults();
  for (const key of ["ems", "plans", "ledger", "go"]) input[key] = {...input[key], enabled: true, launchJar: "/fixture.jar", javaHome: "/jdk21"};
  const output = setup.requestFrom(input);
  for (const key of ["cbApp", "ems", "plans", "ledger", "go"]) assert.equal(output[key].enabled, true);
  assert.equal(output.plans.launchJar, "/fixture.jar"); assert.equal(output.ems.javaHome, "/jdk21");
  assert.equal("launchJar" in output.ledger, false); assert.equal("javaHome" in output.go, false);
  assert.equal("middleware" in output, false); assert.equal("genericJava" in output, false);
  assert.deepEqual([output.cbApp, output.ems, output.plans, output.ledger, output.go].map(service => service.debugPort), [7778, 7779, 7786, 7783, 7784]);
});

test("server failures include session, stale-plan and filesystem recovery instructions", () => {
  assert.equal(setup.errorMessage({status: 410}, {error: "This setup session expired after 15 minutes. Stop it and run setup again."}),
    "This setup session expired after 15 minutes. Stop it and run setup again.");
  assert.match(setup.errorMessage({status: 409}, {error: "Plan expired."}), /Plan expired.*Review/);
  assert.match(setup.errorMessage({status: 403}, {error: "Invalid token."}), /Reopen the setup URL/);
  assert.match(setup.errorMessage({status: 500}, {error: "Permission denied."}), /directory permissions/);
  assert.equal(setup.errorMessage({status: 400}, {error: "Choose a path."}), "Choose a path.");
});

test("wizard has local assets, token metadata, clear review consent, and no HTML injection sinks", () => {
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8"), js = fs.readFileSync(path.join(resource, "setup.js"), "utf8");
  assert.match(html, /name="tracelens-setup-token" content="__TRACELENS_SETUP_TOKEN__"/);
  assert.match(html, /id="review-ack"/); assert.match(html, /id="apply-button"[^>]*disabled/);
  assert.doesNotMatch(html, /(?:src|href)="https?:/);
  assert.doesNotMatch(js, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\s*\(/);
  assert.match(js, /"X-TraceLens-Setup": token/);
  assert.match(js, /request: config, planId: selectedPlan\.planId/);
  assert.match(js, /No automatic retry/);
});

test("setup keeps prerequisites before configuration while the landing screen offers two focused paths", async () => {
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  const configure = /<section\b[^>]*\bid="configure-view"[^>]*>/.exec(html);
  assert.ok(configure, "setup configuration remains a distinct section");
  assert.match(configure[0], /\bhidden\b/, "setup fields are not part of the landing screen");
  const formIndex = html.indexOf('<form id="setup-form"', configure.index);
  assert.ok(formIndex > configure.index, "the configuration form follows the initial section opening");
  const beforeForm = html.slice(configure.index + configure[0].length, formIndex);
  const prerequisite = /<details\b([^>]*\bid="setup-prerequisites"[^>]*)>([\s\S]*?)<\/details>/.exec(beforeForm);
  assert.ok(prerequisite, "prerequisites are inside the initial view, before the form and its disabled fieldset");
  assert.match(prerequisite[1], /\bclass="[^"]*\bprerequisites\b/);
  assert.match(prerequisite[1], /\baria-labelledby="prerequisites-heading"/);
  assert.doesNotMatch(prerequisite[1], /\bhidden(?:\s|=|$)|aria-hidden="true"/);
  assert.doesNotMatch(prerequisite[1], /\bopen\b/, "readiness reference is available without expanding the setup by default");
  assert.match(prerequisite[2], /<h[1-6]\b[^>]*id="prerequisites-heading"[^>]*>\s*Before you start\s*<\/h[1-6]>/i);
  assert.doesNotMatch(prerequisite[2], /<(?:input|button|select|textarea|form)\b/i, "readiness guidance must not add a gating checkbox or automatic action");
  const text = value => value.replace(/<[^>]*>/g, " ").replace(/&(?:amp|nbsp);/g, " ").replace(/\s+/g, " ").trim();
  const items = Array.from(prerequisite[2].matchAll(/<li\b[^>]*>([\s\S]*?)<\/li>/g), match => text(match[1]));
  assert.equal(items.length, 4, "four concrete readiness prerequisites are shown");
  assert.match(items[0], /Run the selected services locally first/i);
  assert.match(items[0], /If cb-app is selected, use Java\s*8/i);
  assert.doesNotMatch(items[0], /Run cb-app locally first/i);
  assert.match(items[1], /log\s*in|login/i); assert.match(items[1], /reproduce/i);
  assert.match(items[1], /dependenc|database[^.]*cache[^.]*downstream/i); assert.match(items[1], /test data/i);
  assert.match(items[2], /EMS/i); assert.match(items[2], /Java\s*21/i);
  assert.match(items[2], /If the flow calls a downstream service, connect its caller/i);
  assert.doesNotMatch(items[2], /connect cb-app/i);
  assert.match(items[3], /source|checkout/i); assert.match(items[3], /match/i); assert.match(items[3], /path/i);
  const guidance = text(prerequisite[2]);
  assert.match(guidance, /(?:need not|needn.t|do not need|don.t need|not required)[^.]*running/i);
  assert.match(guidance, /(?:does not|doesn.t|will not|won.t)[^.]*build[^.]*provision[^.]*restart/i);
  assert.doesNotMatch(guidance, /(?:automatically|already)\s+(?:verified|checked|validated)|health checks?\s+(?:passed|complete)/i);
  const h = harness([defaults()]); await h.ready({landing: true});
  assert.equal(h.find("landing-view").hidden, false);
  assert.equal(h.find("configure-view").hidden, true);
  assert.equal(h.find("dashboard-view").hidden, true);
  await h.find("choose-setup").fire("click");
  assert.equal(h.find("configure-view").hidden, false); assert.equal(h.find("setup-prerequisites").hidden, false);
  assert.equal(h.find("capture-next-button").disabled, false, "the guidance adds no readiness acknowledgment requirement");
  assert.deepEqual(h.calls.map(call => [call.url, call.options.method]), [["/api/setup/defaults", "GET"]],
    "opening setup loads defaults only, without automatic health checks, provisioning, or restarts");
});

function harness(replies) {
  class Element {
    constructor(tag = "div") { this.tag = tag; this.textContent = ""; this.value = ""; this.checked = false; this.hidden = false;
      this.children = []; this.listeners = {}; this.attributes = {}; const names = new Set();
      this.classList = {toggle: (name, on) => on ? names.add(name) : names.delete(name)}; }
    append(...children) { for (const child of children) { child.remove(); child.parentElement = this; this.children.push(child); } }
    remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this); this.parentElement = null; }
    replaceChildren(...children) { for (const child of this.children) child.parentElement = null; this.children = []; this.append(...children); }
    setAttribute(key, value) { this.attributes[key] = value; }
    removeAttribute(key) { delete this.attributes[key]; }
    addEventListener(name, callback) { (this.listeners[name] ||= []).push(callback); }
    async fire(name) { for (const callback of this.listeners[name] || []) await callback({preventDefault() {}}); }
    reportValidity() { return true; }
    focus() { this.focused = true; }
    allText() { return this.textContent + this.children.map(child => child.allText()).join(" "); }
  }
  const elements = new Map(), html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  const stack = [], voidTags = new Set(["meta", "link", "input", "br", "hr", "img"]);
  for (const match of html.matchAll(/<(\/?)([a-z][\w-]*)\b([^>]*)>/gi)) {
    const [, closing, tag, attributes] = match;
    if (closing) { while (stack.length && stack.pop().tag !== tag) {} continue; }
    const element = new Element(tag), id = attributes.match(/\bid="([^"]+)"/)?.[1];
    if (id) { element.id = id; elements.set(id, element); }
    element.hidden = /(?:^|\s)hidden(?:\s|$)/.test(attributes);
    element.disabled = /(?:^|\s)disabled(?:\s|$)/.test(attributes);
    element.checked = /(?:^|\s)checked(?:\s|$)/.test(attributes);
    element.open = /(?:^|\s)open(?:\s|$)/.test(attributes);
    if (stack.length) stack[stack.length - 1].append(element);
    if (!voidTags.has(tag)) stack.push(element);
  }
  const descendants = element => [element, ...element.children.flatMap(descendants)];
  const find = id => elements.get(id) || Array.from(elements.values()).flatMap(descendants).find(element => element.id === id);
  const timers = [];
  const document = {body: new Element("body"), getElementById: find, createElement: tag => new Element(tag),
    querySelector: () => ({content: "local-session-token"}), defaultView: {navigator: {}, setTimeout: (callback, delay) => timers.push({callback, delay})}};
  const calls = [], fetchRequest = async (url, options) => {
    calls.push({url, options});
    const reply = await replies.shift(); if (reply instanceof Error) throw reply;
    return {ok: !reply?.error, status: reply?.error ? 409 : 200, json: async () => reply};
  };
  setup.start(document, fetchRequest);
  let initialized = false;
  return {find, calls, timers, ready: async ({landing = false} = {}) => {
    await new Promise(resolve => setImmediate(resolve));
    if (!initialized && !landing) {
      await find("choose-setup").fire("click");
      await find("capture-next-button").fire("click");
    }
    initialized = true;
  }};
}

test("the browser flow never applies automatically and uses only the reviewed normalized request", async () => {
  const normalized = defaults({installDir: "/tmp/normalized-install"});
  const plan = {planId: "test-plan", request: normalized, files: [{path: "/tmp/normalized-install/tracelens.jar", action: "create", description: "Combined JAR"}],
    warnings: ["Review <literal> content"], commands: [{label: "Plan command", command: "plan-only"}], viewerConfig: "/tmp/normalized-install/cb-app/tracelens.json"};
  const h = harness([defaults(), plan, {installed: true, installDir: normalized.installDir, viewerConfig: plan.viewerConfig,
    files: plan.files, commands: [{label: "Applied command", command: "apply-confirmed"}], warnings: []}]);
  await h.ready();
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].url, "/api/setup/defaults");
  assert.equal(h.calls[0].options.headers["X-TraceLens-Setup"], "local-session-token");
  await h.find("setup-form").fire("submit");
  assert.equal(h.calls.length, 2); assert.equal(h.find("review-view").hidden, false);
  assert.equal(h.find("apply-button").disabled, true); assert.match(h.find("review-warnings").allText(), /<literal>/);
  await h.find("apply-button").fire("click"); assert.equal(h.calls.length, 2);
  h.find("review-ack").checked = true; await h.find("review-ack").fire("change");
  await h.find("apply-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[2].options.body), {request: normalized, planId: "test-plan"});
  assert.equal(h.find("done-view").hidden, false); assert.match(h.find("done-commands").allText(), /apply-confirmed/);
  assert.doesNotMatch(h.find("done-commands").allText(), /plan-only/);
  assert.match(h.find("done-description").textContent, /opt-in TraceLens hook/);
  assert.equal(h.find("ide-next-step").hidden, true);
});

test("editing settings discards review consent and requires a new plan", async () => {
  const h = harness([defaults(), {planId: "old", files: [{path: "/tmp/a", action: "create"}], commands: []}]);
  await h.ready(); await h.find("setup-form").fire("submit");
  h.find("review-ack").checked = true; await h.find("review-ack").fire("change");
  await h.find("edit-button").fire("click");
  assert.equal(h.find("configure-view").hidden, false); assert.equal(h.find("review-ack").checked, false);
  await h.find("apply-button").fire("click"); assert.equal(h.calls.length, 2);
  h.find("cb-app-capture-captureJdbcQueries").checked = true; h.find("cb-app-capture-captureJdbcParameters").checked = true;
  h.find("cb-app-capture-captureJdbcQueries").checked = false; await h.find("setup-form").fire("input");
  assert.equal(h.find("cb-app-capture-captureJdbcParameters").checked, false); assert.equal(h.find("cb-app-capture-captureJdbcParameters").disabled, true);
});

test("apply failures show actionable uncertainty and cannot be silently retried", async () => {
  const h = harness([defaults(), {planId: "old", files: [{path: "/tmp/a", action: "create"}], commands: []}, new Error("offline")]);
  await h.ready(); await h.find("setup-form").fire("submit"); h.find("review-ack").checked = true;
  await h.find("apply-button").fire("click");
  assert.equal(h.find("configure-view").hidden, false); assert.equal(h.find("error").hidden, false);
  assert.match(h.find("error").textContent, /some files may already have been written/);
  assert.match(h.find("status").textContent, /No automatic retry/);
  await h.find("apply-button").fire("click"); assert.equal(h.calls.length, 3);
});

test("all five service choices load defaults and reveal only their selected fields", async () => {
  const h = harness([defaults()]); await h.ready({landing: true}); await h.find("choose-setup").fire("click");
  assert.equal(h.find("cb-app-enabled").checked, true); assert.equal(h.find("cb-app-fields").hidden, false);
  for (const id of ["ems", "plans", "ledger", "go"]) {
    assert.equal(h.find(id + "-enabled").checked, false);
    assert.equal(h.find(id + "-fields").disabled, true); assert.equal(h.find(id + "-fields").hidden, true);
    h.find(id + "-enabled").checked = true; await h.find("setup-form").fire("change");
    assert.equal(h.find(id + "-fields").disabled, false); assert.equal(h.find(id + "-fields").hidden, false);
  }
  assert.deepEqual(["cb-app", "ems", "plans", "ledger", "go"].map(id => h.find(id + "-port").value), [7778, 7779, 7786, 7783, 7784]);
  h.find("ledger-enabled").checked = false; await h.find("setup-form").fire("change");
  assert.equal(h.find("ledger-fields").hidden, true); assert.equal(h.find("ledger-fields").disabled, true);
});

test("review and completion distinguish ready, preserved and failed services without claiming all ready", async () => {
  const config = defaults();
  for (const key of ["ems", "plans", "ledger", "go"]) config[key].enabled = true;
  const services = [{key: "cbApp", name: "cb-app", status: "existing"}, {key: "ems", name: "EMS", status: "ready"},
    {key: "plans", name: "Plans", status: "failed", error: "Missing source <literal> /work/plans"},
    {key: "ledger", name: "Ledger", status: "ready"}, {key: "go", name: "Grant Orchestrator", status: "failed", error: "Port is already configured."}];
  const plan = {planId: "partial", request: config, services, warnings: [],
    files: [{path: "/tmp/local-tracelens/ems/tracelens.json", action: "create"}, {path: "/tmp/local-tracelens/ledger/vm-options.txt", action: "create"}]};
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  const h = harness([config, loaded, connectionFixture(), plan, {installed: true, files: plan.files, services, warnings: []}]);
  await openExisting(h); await h.find("dashboard-add-service").fire("click");
  for (const id of ["ems", "plans", "ledger", "go"]) h.find(id + "-enabled").checked = true;
  await h.find("capture-next-button").fire("click"); await h.find("setup-form").fire("submit");
  const outcomes = h.find("review-services").allText();
  assert.match(outcomes, /cb-app.*Already configured.*preserved/);
  assert.match(outcomes, /EMS.*Ready to add/); assert.match(outcomes, /Ledger.*Ready to add/);
  assert.match(outcomes, /Plans.*Needs attention.*Missing source <literal>/);
  assert.match(outcomes, /Grant Orchestrator.*Needs attention/);
  assert.match(h.find("status").textContent, /Some services need attention/);
  assert.equal(h.find("apply-button").disabled, true);
  h.find("review-ack").checked = true; await h.find("review-ack").fire("change");
  assert.equal(h.find("apply-button").disabled, false, "ready additions can be installed independently after consent");
  await h.find("apply-button").fire("click");
  assert.equal(h.find("done-view").hidden, false);
  assert.match(h.find("done-heading").textContent, /Some need attention/);
  assert.doesNotMatch(h.find("done-heading").textContent, /Your local setup is ready/);
  assert.match(h.find("done-description").textContent, /partial files may remain/);
  assert.doesNotMatch(h.find("done-description").textContent, /Failed services were skipped/);
  assert.match(h.find("done-services").allText(), /EMS.*Installed/);
  assert.match(h.find("done-services").allText(), /cb-app.*Already configured/);
  assert.doesNotMatch(h.find("done-summary").allText(), /Plans:|Grant Orchestrator:/, "failed services must not be described as having saved levels");
  assert.equal(h.find("ide-services").textContent, "EMS, Ledger", "failed and preserved services do not get new launcher instructions");
});

test("an empty add-service plan surfaces outcomes and cannot be installed even with manual consent", async () => {
  const plan = {planId: "nothing-new", request: defaults(), files: [], commands: [],
    services: [{key: "cbApp", status: "existing"}, {key: "plans", status: "failed", error: "Checkout missing."}]};
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  const h = harness([defaults(), loaded, connectionFixture(), plan]); await openExisting(h);
  await h.find("dashboard-add-service").fire("click"); h.find("plans-enabled").checked = true;
  await h.find("capture-next-button").fire("click"); await h.find("setup-form").fire("submit");
  assert.equal(h.find("review-view").hidden, false); assert.equal(h.find("error").hidden, true);
  assert.match(h.find("planned-files").allText(), /Nothing to save/);
  assert.match(h.find("review-services").allText(), /Already configured/);
  assert.match(h.find("review-services").allText(), /Checkout missing/);
  assert.match(h.find("status").textContent, /No files need saving/);
  assert.equal(h.find("review-ack").disabled, true);
  h.find("review-ack").checked = true; await h.find("review-ack").fire("change");
  assert.equal(h.find("apply-button").disabled, true);
  await h.find("apply-button").fire("click"); assert.equal(h.calls.length, 4);
});

test("completion retains reviewed service failures when apply omits its outcome list", async () => {
  const config = defaults({plans: {...defaults().plans, enabled: true}});
  const plan = {planId: "partial-fallback", request: config, files: [{path: "/tmp/new-file"}],
    services: [{key: "cbApp", status: "ready"}, {key: "plans", status: "failed", error: "Source is unavailable."}]};
  const h = harness([config, plan, {installed: true, files: plan.files}]);
  await h.ready(); await h.find("setup-form").fire("submit");
  h.find("review-ack").checked = true; await h.find("apply-button").fire("click");
  assert.match(h.find("done-services").allText(), /Plans.*Needs attention.*Source is unavailable/);
  assert.match(h.find("status").textContent, /Some selected services still need attention/);
});

test("Plans-only installation produces only Plans IDE instructions and accepts installed outcome status", async () => {
  const config = defaults({cbApp: {enabled: false}, plans: {...defaults().plans, enabled: true}});
  const plan = {planId: "plans-only", request: config, files: [{path: "/tmp/local-tracelens/plans/vm-options.txt"}],
    services: [{key: "plans", status: "ready"}]};
  const h = harness([config, plan, {installed: true, files: plan.files, services: [{key: "plans", status: "installed"}]}]);
  await h.ready(); await h.find("setup-form").fire("submit");
  const submitted = JSON.parse(h.calls[1].options.body);
  assert.deepEqual(submitted.cbApp, {enabled: false}); assert.equal(submitted.plans.enabled, true);
  h.find("review-ack").checked = true; await h.find("apply-button").fire("click");
  assert.equal(h.find("ide-next-step").hidden, false); assert.equal(h.find("ide-services").textContent, "Plans");
  assert.match(h.find("done-services").allText(), /Plans.*Installed/);
  assert.doesNotMatch(h.find("done-description").textContent, /cb-app startup|EMS launcher/);
});

test("setup copy explains incremental installs and distinct launchers without changing session expiry", () => {
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  assert.match(html, /Edit capture settings/);
  assert.match(html, /id="dashboard-add-service"/);
  assert.match(html, /Saving affects only reviewed settings/);
  assert.match(html, /Setup session expires __TRACELENS_SETUP_MINUTES__ minutes after launch/);
  for (const id of ["cb-app", "ems", "plans", "ledger", "go"]) {
    assert.match(html, new RegExp(`id="${id}-enabled"`));
    assert.match(html, new RegExp(`id="${id}-paths"`));
    assert.doesNotMatch(html, new RegExp(`<input[^>]+id="${id}-(?:source|port)"[^>]*\\brequired\\b`),
      "service validation belongs to the per-service backend plan, not a form-wide block");
  }
  for (const id of ["ems", "plans"]) assert.match(html, new RegExp(`id="${id}-jar"`));
  for (const id of ["ledger", "go"]) {
    assert.doesNotMatch(html, new RegExp(`id="${id}-(?:jar|java)"`));
    const card = html.slice(html.indexOf(`id="${id}-card"`)).split("</section>")[0];
    assert.match(card, /existing application runtime \/ Tomcat/); assert.match(card, /vm-options\.txt/);
    assert.doesNotMatch(card, /Java 8/);
  }
  assert.doesNotMatch(html, /middleware-enabled|generic-java-enabled/);
});

const captureFixture = () => ({captureMode: "methods", trackMethods: true, detailedCapturePaths: ["/api/test"], detailedCaptureUntilEpochMillis: 1793511000123,
  packages: ["com.example"], excludedPackages: ["com.example.generated"], trackExceptions: true, instrumentExecutors: true,
  instrumentScheduled: false, chargebeeJobTypes: ["ExampleJob"], captureHttpQueryParameters: true, captureHttpRequestBodies: false,
  redactHttpPayloads: true, maxHttpPayloadLength: 2048, httpClientCaptureEnabled: true, distributedTracingEnabled: false,
  httpPropagationAllowedDestinations: ["localhost:8080"], captureDataStoreBoundaries: true, captureMessagingBoundaries: false,
  captureJdbcQueries: true, captureJdbcParameters: true, captureClickHouseQueries: false, captureClickHouseParameters: false,
  maxSqlLength: 4096, maxSqlQueriesPerRequest: 50, maxTrackedBytes: 268435456, maxSegmentBytes: 33554432,
  maxTrackedEvents: 500000, maxTrackedRequests: 200, idleTtlMinutes: 30, httpRetentionPercent: 75});
const dashboardDefaults = () => {
  const value = defaults();
  for (const key of ["cbApp", "ems", "plans", "ledger", "go"]) value[key].capture = captureFixture();
  return value;
};
const savedFixture = config => ({installed: true, installDir: config.installDir, files: [{path: config.installDir + "/cb-app/tracelens.json"}],
  services: [{key: "cbApp", status: "updated", operation: "update", restartRequired: true}]});
const connectionFixture = (rows = []) => ({services: rows});
async function openExisting(h, installDir = "/tmp/local-tracelens") {
  await h.ready({landing: true});
  await h.find("choose-existing").fire("click");
  h.find("existing-install-dir").value = installDir;
  await h.find("existing-load-form").fire("submit");
  await h.ready();
}
async function saveFixture(h) {
  await h.ready(); await h.find("setup-form").fire("submit"); h.find("review-ack").checked = true; await h.find("apply-button").fire("click");
}

test("per-service capture whitelists all settings without mixing services or unrelated runtime configuration", () => {
  const input = dashboardDefaults(); input.ems.enabled = true;
  input.cbApp.capture = {...captureFixture(), captureClickHouseQueries: true, captureClickHouseParameters: true, captureHttpRequestBodies: true,
    maxSqlLength: "2048", packages: "com.one, com.two\ncom.one", viewer: {port: 1234}, arbitrary: "ignored"};
  input.ems.capture = {...captureFixture(), captureMode: "overview", captureJdbcQueries: false, captureJdbcParameters: true, redactHttpPayloads: false};
  const before = JSON.stringify(input), output = setup.requestFrom(input);
  assert.equal(JSON.stringify(input), before, "serialization must not mutate loaded values");
  assert.deepEqual(output.cbApp.capture.packages, ["com.one", "com.two"]);
  assert.equal(output.cbApp.capture.maxSqlLength, 2048);
  assert.equal(output.cbApp.capture.captureClickHouseParameters, true);
  assert.equal(output.cbApp.capture.captureHttpRequestBodies, true);
  assert.equal("viewer" in output.cbApp.capture, false); assert.equal("arbitrary" in output.cbApp.capture, false);
  assert.equal(output.ems.capture.captureMode, "overview"); assert.equal(output.ems.capture.captureJdbcParameters, false);
  assert.equal(output.ems.capture.redactHttpPayloads, false);
  assert.deepEqual(Object.keys(output.ems.capture).sort(), Object.keys(captureFixture()).sort());
  input.ems.capture.maxTrackedBytes = "invalid";
  assert.equal(setup.requestFrom(input).ems.capture.maxTrackedBytes, "invalid", "invalid per-service settings are validated independently by the server");
});

test("dashboard posts per-service settings only and preserves every loaded field including exact expiry", async () => {
  const config = dashboardDefaults(), h = harness([config, {planId: "complete-fields", request: config, files: []}]); await h.ready();
  for (const field of Object.keys(captureFixture())) assert.ok(h.find("cb-app-capture-" + field), field + " has a labeled control");
  await h.find("setup-form").fire("submit");
  const submitted = JSON.parse(h.calls[1].options.body);
  for (const field of ["captureMode", "captureJdbcQueries", "captureJdbcParameters"]) assert.equal(field in submitted, false);
  assert.deepEqual(submitted.cbApp.capture, captureFixture());
  assert.deepEqual(submitted.ems, {enabled: false});
  assert.match(h.find("cb-app-fields").allText(), /Redaction|redaction/);
  assert.match(h.find("cb-app-fields").allText(), /Overview.*Methods.*Detailed/);
  assert.match(h.find("cb-app-fields").allText(), /API paths to capture.*falls back to overview/);
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  assert.doesNotMatch(html, /id="capture-mode"|id="capture-queries"/, "there is no accidental global capture override");
});

test("JDBC, native ClickHouse, HTTP evidence and method switch are independent per service", async () => {
  const config = dashboardDefaults(); config.ems.enabled = true;
  const h = harness([config, {planId: "independent", files: []}]); await h.ready();
  h.find("cb-app-capture-captureClickHouseQueries").checked = true; h.find("cb-app-capture-captureClickHouseParameters").checked = true;
  h.find("cb-app-capture-captureJdbcQueries").checked = false;
  h.find("cb-app-capture-trackMethods").checked = false;
  h.find("cb-app-capture-detailedCaptureUntilEpochMillis").value = "";
  h.find("cb-app-paths").value = "";
  await h.find("setup-form").fire("input");
  assert.equal(h.find("cb-app-capture-captureJdbcParameters").disabled, true);
  assert.equal(h.find("cb-app-capture-captureClickHouseParameters").checked, true);
  assert.equal(h.find("ems-capture-captureJdbcParameters").checked, true);
  assert.equal(h.find("cb-app-capture-notice").hidden, false);
  await h.find("setup-form").fire("submit");
  const submitted = JSON.parse(h.calls[1].options.body);
  assert.equal(submitted.cbApp.capture.captureHttpQueryParameters, true);
  assert.equal(submitted.cbApp.capture.detailedCaptureUntilEpochMillis, 0);
  assert.equal(submitted.cbApp.capture.trackMethods, false);
  assert.equal(submitted.ems.capture.trackMethods, true);
  assert.deepEqual(submitted.cbApp.httpIncludePaths, []);
  assert.deepEqual(submitted.cbApp.capture.chargebeeJobTypes, ["ExampleJob"], "HTTP admission does not disable jobs");
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  assert.equal((html.match(/Blank disables incoming HTTP traces; scheduled work and jobs are independent/g) || []).length, 5);
});

test("explicit load is read-only and locks preserved paths, ports and launchers", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  loaded.cbApp.capture.captureMode = "overview"; loaded.cbApp.capture.redactHttpPayloads = false;
  loaded.plans.enabled = true; loaded.plans.configured = true; loaded.plans.launcherAvailable = false;
  const h = harness([dashboardDefaults(), loaded, connectionFixture()]); await h.ready({landing: true});
  assert.equal(h.calls.length, 1, "loading the page does not inspect or restart apps");
  await openExisting(h);
  assert.equal(h.calls[1].url, "/api/setup/load"); assert.deepEqual(JSON.parse(h.calls[1].options.body), {installDir: loaded.installDir});
  assert.equal(h.find("cb-app-capture-captureMode").value, "overview");
  assert.equal(h.find("cb-app-capture-redactHttpPayloads").checked, false);
  assert.equal(h.find("cb-app-source").readOnly, true); assert.equal(h.find("cb-app-port").readOnly, true);
  assert.equal(h.find("viewer-port").readOnly, true); assert.equal(h.find("plans-jar").readOnly, true);
  assert.equal(h.find("ems-source").readOnly, false);
  assert.equal(h.find("dashboard-view").hidden, false);
  assert.equal(h.calls.length, 3, "load only reads configuration and connection status");
  assert.equal(h.calls[2].url, "/api/setup/lifecycle/status");
});

test("a service load error excludes fallback values from all saves", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  loaded.ems.enabled = true; loaded.ems.configured = true; loaded.ems.loadError = "Unreadable <literal> config";
  const h = harness([dashboardDefaults(), loaded, connectionFixture(), {planId: "safe", files: []}]); await openExisting(h);
  assert.equal(h.find("ems-enabled").checked, false); assert.equal(h.find("ems-enabled").disabled, true);
  assert.equal(h.find("ems-load-error").hidden, false); assert.match(h.find("ems-load-error").textContent, /Unreadable <literal>/);
  await h.find("cb-app-configure-button").fire("click");
  await h.find("setup-form").fire("submit"); assert.deepEqual(JSON.parse(h.calls[3].options.body).ems, {enabled: false});
});

test("updates show before-after values and can be loaded, edited and saved again in one session", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const service = {...saved.services[0], status: "ready", configChanges: [{field: "captureMode", before: "detailed", after: "methods"}]};
  const plan = {planId: "update-one", request: config, files: saved.files, services: [service]};
  const loaded = dashboardDefaults(); loaded.cbApp.configured = true; loaded.configured = true;
  const h = harness([config, loaded, connectionFixture(), plan, saved, loaded, connectionFixture(), {...plan, planId: "update-two"}, saved]);
  await openExisting(h); await h.find("cb-app-configure-button").fire("click"); await saveFixture(h);
  assert.match(h.find("review-services").allText(), /Ready to update.*Restart required.*captureMode.*detailed.*methods/);
  assert.match(h.find("done-services").allText(), /Updated.*Restart required/);
  await h.find("done-edit-button").fire("click"); assert.equal(h.calls[5].url, "/api/setup/load");
  assert.equal(h.find("dashboard-view").hidden, false); assert.equal(h.find("review-ack").checked, false);
  await h.find("cb-app-configure-button").fire("click");
  h.find("cb-app-capture-captureMode").value = "overview"; await h.find("setup-form").fire("input");
  await h.find("setup-form").fire("submit"); h.find("review-ack").checked = true; await h.find("apply-button").fire("click");
  assert.equal(h.calls[8].url, "/api/setup/apply"); assert.equal(JSON.parse(h.calls[8].options.body).planId, "update-two");
  assert.equal(h.calls.some(call => call.url === "/api/setup/lifecycle/apply"), false, "neither save implicitly restarts any service");
});

test("service actions require a successful save, explicit selection, fresh plan and separate consent", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const h = harness([config, {planId: "save", request: config, files: saved.files}, saved,
    {planId: "restart-1", services: [{key: "cbApp", status: "ready", action: "restart", reason: "Owned process."}]},
    {planId: "restart-2", services: [{key: "cbApp", status: "ready", action: "restart", reason: "Owned process."}]},
    {services: [{key: "cbApp", status: "running", message: "Process and TraceLens confirmed."}]}]);
  await saveFixture(h); assert.equal(h.calls.length, 3); assert.equal(h.find("cb-app-restart").checked, false);
  await h.find("restart-plan-button").fire("click"); assert.equal(h.calls.length, 3);
  h.find("cb-app-restart").checked = true; await h.find("cb-app-restart").fire("change");
  await h.find("restart-plan-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[3].options.body), {installDir: config.installDir, services: ["cbApp"]});
  await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 4);
  h.find("restart-ack").checked = true; await h.find("restart-ack").fire("change");
  await h.find("cb-app-restart").fire("change");
  assert.equal(h.find("restart-ack").checked, false); await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 4);
  await h.find("restart-plan-button").fire("click"); h.find("restart-ack").checked = true;
  await h.find("restart-apply-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[5].options.body), {installDir: config.installDir, services: ["cbApp"], planId: "restart-2", confirmed: true});
  assert.match(h.find("restart-result").allText(), /Process \+ TraceLens responding/);
  assert.match(h.find("status").textContent, /does not confirm application business readiness/);
  await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 6, "a used action plan is invalidated");
});

test("launcher registration is explicit and preserves argument boundaries without a shell editor", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const h = harness([config, {planId: "save", request: config, files: saved.files}, saved,
    {planId: "manual", services: [{key: "cbApp", status: "manual", reason: "Stop the unmanaged instance first."}]}]);
  await saveFixture(h); h.find("cb-app-restart").checked = true; await h.find("cb-app-restart").fire("change");
  h.find("cb-app-launcher-type").value = "tomcat"; await h.find("cb-app-launcher-type").fire("change");
  h.find("cb-app-launcher-javaHome").value = "/jdk8"; h.find("cb-app-launcher-applicationPath").value = "/tomcat";
  h.find("cb-app-launcher-applicationPort").value = "8080"; h.find("cb-app-launcher-workingDirectory").value = "/work/cb-app";
  h.find("cb-app-launcher-arguments").value = "--one=a,b\n--two=has space\n";
  await h.find("restart-plan-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[3].options.body).launchers, {cbApp: {type: "tomcat", javaHome: "/jdk8", applicationPath: "/tomcat",
    applicationPort: 8080, workingDirectory: "/work/cb-app", arguments: ["--one=a,b", "--two=has space"]}});
  assert.match(h.find("restart-plan").allText(), /Manual action required.*Stop the unmanaged instance/);
  h.find("restart-ack").checked = true; await h.find("restart-ack").fire("change");
  assert.equal(h.find("restart-apply-button").disabled, true); await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 4);
});

test("starting actions poll status without repeating start and stop at terminal outcomes", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const h = harness([config, {planId: "save", request: config, files: saved.files}, saved,
    {planId: "start", services: [{key: "cbApp", status: "ready", action: "start"}]},
    {services: [{key: "cbApp", status: "starting", message: "Starting."}]},
    {services: [{key: "cbApp", status: "restarting", message: "Waiting for endpoint."}]},
    {services: [{key: "cbApp", status: "running", message: "TraceLens is responding."}]}]);
  await saveFixture(h); h.find("cb-app-restart").checked = true; await h.find("restart-plan-button").fire("click");
  h.find("restart-ack").checked = true; await h.find("restart-apply-button").fire("click");
  assert.match(h.find("restart-result").allText(), /Starting/); assert.equal(h.find("restart-plan-button").disabled, true);
  assert.equal(h.timers[0].delay, 2000); await h.timers.shift().callback();
  assert.equal(h.calls[5].url, "/api/setup/lifecycle/status");
  assert.deepEqual(JSON.parse(h.calls[5].options.body), {installDir: config.installDir, services: ["cbApp"]});
  await h.timers.shift().callback(); assert.equal(h.timers.length, 0);
  assert.match(h.find("restart-result").allText(), /Process \+ TraceLens responding/);
  assert.equal(h.find("done-edit-button").disabled, false);
  assert.equal(h.calls.filter(call => call.url === "/api/setup/lifecycle/apply").length, 1);
});

test("lifecycle failure leaves saved configuration intact and never silently retries an uncertain action", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const h = harness([config, {planId: "save", request: config, files: saved.files}, saved,
    {planId: "start", services: [{key: "cbApp", status: "ready", action: "start"}]}, new Error("disconnected")]);
  await saveFixture(h); h.find("cb-app-restart").checked = true; await h.find("restart-plan-button").fire("click");
  h.find("restart-ack").checked = true; await h.find("restart-apply-button").fire("click");
  assert.equal(h.find("done-view").hidden, false); assert.match(h.find("status").textContent, /Configuration remains saved/);
  assert.match(h.find("error").textContent, /process may already have started or stopped/);
  await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 5); assert.equal(h.timers.length, 0);
});

test("loaded services can open lifecycle without a no-op save and unsaved edits never become launch settings", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  const h = harness([dashboardDefaults(), loaded, connectionFixture(), loaded,
    {planId: "existing-start", services: [{key: "cbApp", status: "ready", action: "start", reason: "Registered profile available."}]}]);
  await openExisting(h);
  await h.find("cb-app-configure-button").fire("click");
  h.find("install-dir").value = "/tmp/unsaved-install";
  h.find("cb-app-capture-captureMode").value = "detailed"; await h.find("setup-form").fire("input");
  await h.find("editor-back-button").fire("click");
  assert.equal(h.find("cb-app-capture-captureMode").value, "methods", "back explicitly discards the editor draft");
  await h.find("dashboard-service-actions").fire("click");
  assert.deepEqual(JSON.parse(h.calls[3].options.body), {installDir: loaded.installDir});
  assert.equal(h.find("done-view").hidden, false);
  assert.match(h.find("done-description").textContent, /Unsaved form edits have not been applied/);
  assert.match(h.find("done-summary").allText(), /cb-app: methods/);
  h.find("cb-app-restart").checked = true; await h.find("restart-plan-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[4].options.body), {installDir: loaded.installDir, services: ["cbApp"]});
  assert.equal(h.calls.some(call => call.url === "/api/setup/plan" || call.url === "/api/setup/apply"), false);
  assert.equal(h.calls.some(call => call.url === "/api/setup/lifecycle/apply"), false);
});

test("malformed loads do not replace existing form data", async () => {
  const h = harness([dashboardDefaults(), {installDir: "/tmp/incomplete", viewerPort: 8765}]); await h.ready({landing: true});
  h.find("cb-app-source").value = "/work/preserved"; await openExisting(h);
  assert.equal(h.find("cb-app-source").value, "/work/preserved");
  assert.match(h.find("error").textContent, /incomplete configuration/);
});

test("status expiry or incomplete outcomes stop polling without claiming readiness", async () => {
  for (const finalStatus of [{error: "This setup session expired after 15 minutes."}, {services: []}, {services: [{key: "cbApp", status: "unknown"}]}]) {
    const config = dashboardDefaults(), saved = savedFixture(config);
    const h = harness([config, {planId: "save", request: config, files: saved.files}, saved,
      {planId: "start", services: [{key: "cbApp", status: "ready", action: "start"}]},
      {services: [{key: "cbApp", status: "starting"}]}, finalStatus]);
    await saveFixture(h); h.find("cb-app-restart").checked = true; await h.find("restart-plan-button").fire("click");
    h.find("restart-ack").checked = true; await h.find("restart-apply-button").fire("click");
    await h.timers.shift().callback(); assert.equal(h.timers.length, 0);
    assert.match(h.find("error").textContent, /Configuration remains saved.*Process status is unconfirmed/);
    assert.doesNotMatch(h.find("status").textContent, /are responding/);
    assert.equal(h.calls.filter(call => call.url === "/api/setup/lifecycle/apply").length, 1);
  }
});

test("Overview with detail scope visibly explains legacy promotion instead of promising overview-only", async () => {
  const config = dashboardDefaults(); config.cbApp.capture.captureMode = "overview";
  const h = harness([config]); await h.ready();
  assert.equal(h.find("cb-app-capture-notice").hidden, false);
  assert.match(h.find("cb-app-capture-notice").textContent, /Matching paths use Detailed \(methods \+ lines\).*Clear scope for Overview everywhere/);
  assert.match(h.find("cb-app-fields").allText(), /With Overview and nonempty scope, matching paths use Detailed/);
  assert.match(h.find("cb-app-fields").allText(), /With Methods or Detailed, scope narrows that level/);
  h.find("cb-app-capture-detailedCapturePaths").value = ""; await h.find("setup-form").fire("input");
  assert.equal(h.find("cb-app-capture-notice").hidden, true);
  h.find("cb-app-capture-detailedCapturePaths").value = "/api/test";
  h.find("cb-app-capture-captureMode").value = "methods"; await h.find("setup-form").fire("change");
  assert.equal(h.find("cb-app-capture-notice").hidden, true, "scoped Methods does not promote to lines");
});

test("new setup separates locations from capture and cannot review before choosing capture", async () => {
  const h = harness([dashboardDefaults(), {planId: "new", files: []}]); await h.ready({landing: true});
  await h.find("setup-form").fire("submit"); assert.equal(h.calls.length, 1, "landing cannot submit hidden setup");
  await h.find("choose-setup").fire("click");
  assert.equal(h.find("workspace-settings").hidden, false);
  assert.equal(h.find("cb-app-source").parentElement.hidden, false);
  assert.equal(h.find("cb-app-capture-captureMode").parentElement.parentElement.hidden, true);
  assert.equal(h.find("review-button").hidden, true);
  await h.find("setup-form").fire("submit"); assert.equal(h.calls.length, 1, "locations cannot bypass the capture step");
  await h.find("capture-next-button").fire("click");
  assert.equal(h.find("workspace-settings").hidden, true);
  assert.equal(h.find("cb-app-source").parentElement.hidden, true);
  assert.equal(h.find("cb-app-capture-captureMode").parentElement.parentElement.hidden, false);
  assert.equal(h.find("ems-card").hidden, true, "unselected services disappear from capture choices");
  for (const disclosure of h.find("cb-app-fields").children.flatMap(child => child.children).filter(child => child.tag === "details"))
    assert.equal(disclosure.open, false, "capture details are closed by default");
  await h.find("setup-form").fire("submit"); assert.equal(h.calls[1].url, "/api/setup/plan");
});

test("existing dashboard edits exactly one service and discards drafts on explicit return", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  loaded.ems.configured = true; loaded.ems.enabled = true;
  const h = harness([dashboardDefaults(), loaded, connectionFixture(), {planId: "ems-only", files: []}]); await openExisting(h);
  assert.equal(h.find("configure-view").hidden, true); assert.equal(h.find("setup-progress").hidden, true);
  await h.find("ems-configure-button").fire("click");
  assert.equal(h.find("ems-card").hidden, false); assert.equal(h.find("cb-app-card").hidden, true);
  assert.match(h.find("configure-heading").textContent, /EMS/);
  h.find("cb-app-enabled").checked = true;
  h.find("cb-app-capture-captureMode").value = "overview";
  h.find("ems-capture-captureMode").value = "detailed";
  await h.find("setup-form").fire("submit");
  const request = JSON.parse(h.calls[3].options.body);
  assert.deepEqual(request.cbApp, {enabled: false}); assert.equal(request.ems.capture.captureMode, "detailed");
  await h.find("edit-button").fire("click"); await h.find("editor-back-button").fire("click");
  assert.equal(h.find("dashboard-view").hidden, false);
  await h.find("ems-configure-button").fire("click");
  assert.equal(h.find("ems-capture-captureMode").value, "methods", "unsaved changes are not reused");
  assert.equal(h.calls.some(call => call.url.endsWith("/apply")), false);
});

test("Add service hides existing services and never includes their settings in the new request", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  const h = harness([dashboardDefaults(), loaded, connectionFixture(), {planId: "add-ems", files: []}]); await openExisting(h);
  await h.find("dashboard-add-service").fire("click");
  assert.equal(h.find("cb-app-card").hidden, true); assert.equal(h.find("cb-app-enabled").disabled, true);
  assert.equal(h.find("ems-card").hidden, false); assert.equal(h.find("workspace-settings").hidden, true);
  h.find("cb-app-enabled").checked = true; h.find("ems-enabled").checked = true;
  await h.find("setup-form").fire("change"); await h.find("capture-next-button").fire("click");
  await h.find("setup-form").fire("submit");
  const request = JSON.parse(h.calls[3].options.body);
  assert.equal(request.installDir, loaded.installDir); assert.deepEqual(request.cbApp, {enabled: false});
  assert.equal(request.ems.enabled, true);
});

test("connection status is independent from ownership and offline services remain configurable", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true;
  loaded.cbApp.configured = true; loaded.ems.configured = true;
  const h = harness([dashboardDefaults(), loaded, connectionFixture([
    {key: "cbApp", status: "manual", connectionStatus: "connected", connectionMessage: "Local agent responded."},
    {key: "ems", status: "failed", connectionStatus: "unavailable", connectionMessage: "Local agent did not respond."}
  ])]); await openExisting(h);
  assert.match(h.find("cb-app-dashboard-card").allText(), /Agent connected/);
  assert.match(h.find("ems-dashboard-card").allText(), /Agent unavailable/);
  assert.doesNotMatch(h.find("cb-app-dashboard-card").allText(), /Managed process needs a restart/);
  assert.equal(h.find("ems-configure-button").disabled, false);
  assert.equal(h.calls.some(call => call.url.endsWith("/apply")), false);
});

test("failed status checks preserve loaded service settings and allow configuration", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  const h = harness([dashboardDefaults(), loaded, new Error("offline")]); await openExisting(h);
  assert.equal(h.find("dashboard-view").hidden, false);
  assert.match(h.find("dashboard-connection-note").textContent, /Connection not checked/);
  await h.find("cb-app-configure-button").fire("click");
  assert.equal(h.find("cb-app-capture-captureMode").value, "methods");
});

test("late connection responses cannot reopen the dashboard or overwrite an active editor", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true; loaded.cbApp.configured = true;
  let resolveStatus; const pending = new Promise(resolve => { resolveStatus = resolve; });
  const h = harness([dashboardDefaults(), loaded, pending]); await openExisting(h);
  await h.find("cb-app-configure-button").fire("click");
  h.find("cb-app-capture-captureMode").value = "detailed"; await h.find("setup-form").fire("input");
  resolveStatus(connectionFixture([{key: "cbApp", status: "running", connectionStatus: "connected", restartRequired: true}]));
  await h.ready();
  assert.equal(h.find("configure-view").hidden, false); assert.equal(h.find("dashboard-view").hidden, true);
  assert.equal(h.find("cb-app-capture-captureMode").value, "detailed");
  await h.find("editor-back-button").fire("click");
  assert.doesNotMatch(h.find("cb-app-dashboard-card").allText(), /Agent connected|Managed process needs a restart/);
});

test("first-time setup cannot save into an existing installation even when only a new service is selected", async () => {
  const config = dashboardDefaults(); config.cbApp.enabled = false; config.ems.enabled = true;
  const h = harness([config, {planId: "existing-add", existingInstallation: true,
    files: [{path: "/tmp/local-tracelens/ems/tracelens.json"}], services: [{key: "ems", status: "ready", operation: "add"}]}]);
  await h.ready(); await h.find("setup-form").fire("submit");
  assert.equal(h.find("review-view").hidden, true);
  assert.match(h.find("error").textContent, /Edit capture settings/);
  h.find("review-ack").checked = true; await h.find("apply-button").fire("click");
  assert.equal(h.calls.length, 2, "no apply after a mode mismatch");
});

test("existing flow refuses a fresh directory without replacing displayed configuration", async () => {
  const h = harness([dashboardDefaults(), dashboardDefaults()]); await openExisting(h);
  assert.equal(h.find("existing-load-view").hidden, false);
  assert.equal(h.find("dashboard-view").hidden, true);
  assert.match(h.find("error").textContent, /no wizard-managed installation.*Set up TraceLens/);
  assert.equal(h.calls.length, 2, "no agent probe or file write for an unrecognized installation");
});

test("policy summary explains all three levels, scope promotion and independent evidence opt-ins", () => {
  const service = {httpIncludePaths: ["/api/test"], capture: {...captureFixture(), detailedCapturePaths: [], detailedCaptureUntilEpochMillis: 0}};
  const summary = () => setup.capturePolicy(service, 1000).join(" ");
  assert.match(summary(), /Incoming APIs: \/api\/test \(exact paths \+ descendants\).*Detail: Methods for admitted work/);
  assert.match(summary(), /query parameters on.*request bodies off.*JDBC SQL \+ bindings.*ClickHouse off.*redaction: best-effort/);
  service.capture.captureMode = "overview";
  assert.match(summary(), /Default level \(without scope\): Overview\. No scoped override/);
  assert.match(summary(), /Detail: Overview — no method\/line history/);
  service.capture.detailedCapturePaths = ["/api/test/detail"];
  assert.match(summary(), /Default level \(without scope\): Overview\. Scoped override: Detailed for matching paths; Overview outside scope/);
  assert.match(summary(), /Methods \+ lines on \/api\/test\/detail; Overview elsewhere/);
  service.capture.captureMode = "methods";
  assert.match(summary(), /Default level \(without scope\): Methods\. Scoped override: Methods for matching paths/);
  assert.match(summary(), /Detail: Methods on \/api\/test\/detail; Overview elsewhere/);
  assert.doesNotMatch(summary(), /Methods \+ lines/);
  service.capture.captureMode = "detailed";
  assert.match(summary(), /Methods \+ lines on \/api\/test\/detail/);
  service.capture.captureJdbcQueries = false; service.capture.redactHttpPayloads = false;
  assert.match(summary(), /JDBC off.*redaction: off/);
  service.httpIncludePaths = [];
  assert.match(summary(), /Incoming HTTP capture disabled.*Jobs and messaging are independent/);
  assert.match(summary(), /query parameters on/, "admission and detail do not silently change evidence settings");
});

test("policy summary surfaces empty packages, method switches, expired detail and invalid expiry honestly", () => {
  const service = {httpIncludePaths: ["/api/test"], capture: {...captureFixture(), detailedCaptureUntilEpochMillis: 2000}};
  const summary = now => setup.capturePolicy(service, now).join(" ");
  assert.match(summary(2000), /Detail: Methods on/);
  assert.match(summary(2001), /Overview now — detail expired \(browser clock\)/);
  service.capture.detailedCaptureUntilEpochMillis = 0; service.capture.trackMethods = false;
  assert.match(summary(1000), /No method\/line history — method events are off/);
  service.capture.captureMode = "detailed";
  assert.match(summary(1000), /Executed lines only — method events are off/);
  service.capture.packages = [];
  assert.match(summary(1000), /No application method\/line history — package scope is empty/);
  service.capture.detailedCaptureUntilEpochMillis = NaN;
  assert.match(summary(1000), /Invalid detail expiry; review settings/);
  service.capture.captureMode = "unknown";
  assert.match(summary(1000), /Unknown capture level; review settings/);
});

test("policy summaries bound long path lists without hiding that more scope exists", () => {
  const paths = Array.from({length: 64}, (_, i) => "/api/" + i + "x".repeat(500));
  const policy = setup.capturePolicy({httpIncludePaths: paths, capture: captureFixture()}, 1000);
  assert.match(policy[0], /….*\(\+61 more\)/);
  assert.ok(policy[0].length < 350);
});

test("viewer handoff uses only the configured loopback port and successful known service aliases", () => {
  const config = dashboardDefaults(); config.cbApp.enabled = false;
  for (const [key, alias] of [["cbApp", "cb-app"], ["ems", "cb-ems"], ["plans", "cb-plans"], ["ledger", "cb-consumption-ledger"], ["go", "cb-grant-orchestrator"]]) {
    config[key].enabled = true;
    assert.equal(setup.viewerHandoff(config, [{key, status: "updated"}]), "http://127.0.0.1:8765/#investigate=1&service=" + alias);
    config[key].enabled = false;
  }
  config.cbApp.enabled = true; config.ems.enabled = true;
  assert.equal(setup.viewerHandoff(config, [{key: "cbApp", status: "installed"}, {key: "ems", status: "installed"}]), "http://127.0.0.1:8765/#investigate=1");
  assert.equal(setup.viewerHandoff(config, [{key: "cbApp", status: "failed"}, {key: "ems", status: "installed"}]), "http://127.0.0.1:8765/#investigate=1&service=cb-ems");
  assert.equal(setup.viewerHandoff(config, [{key: "unknown", status: "installed"}]), "http://127.0.0.1:8765/");
  for (const viewerPort of ["8765@remote.example", "8765/#path=secret", 65536, null]) assert.equal(setup.viewerHandoff({...config, viewerPort}), "");
  assert.doesNotMatch(setup.viewerHandoff(config), /api|source|token|secret/, "no API paths, credentials or local filesystem paths enter the URL");
});

test("capture admission and effective policy stay visible while detailed controls remain collapsed", async () => {
  const h = harness([dashboardDefaults()]); await h.ready();
  const policy = h.find("cb-app-capture-policy");
  assert.equal(policy.hidden, false); assert.equal(policy.parentElement.hidden, false);
  assert.equal(policy.parentElement.tag, "div", "summary is not nested inside a disclosure");
  assert.match(policy.allText(), /Policy preview · not applied.*Incoming APIs: \/api/);
  const disclosure = h.find("cb-app-paths").parentElement.parentElement.parentElement;
  assert.equal(disclosure.tag, "details"); assert.equal(disclosure.open, false);
  assert.match(disclosure.allText(), /Edit API paths to capture/);
  h.find("cb-app-paths").value = ""; await h.find("setup-form").fire("input");
  assert.match(policy.allText(), /Incoming HTTP capture disabled/);
  assert.equal(h.calls.length, 1, "summary updates do not probe, save or restart services");
});

test("saved policy remains distinct from manual, pending and unchanged managed runtime status", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true;
  for (const key of ["cbApp", "ems", "plans"]) { loaded[key].configured = true; loaded[key].enabled = true; }
  const h = harness([dashboardDefaults(), loaded, connectionFixture([
    {key: "cbApp", status: "manual", connectionStatus: "connected"},
    {key: "ems", status: "running", connectionStatus: "connected", restartRequired: true},
    {key: "plans", status: "running", connectionStatus: "connected", restartRequired: false}
  ])]); await openExisting(h);
  assert.match(h.find("cb-app-dashboard-card").allText(), /Saved capture level.*Saved policy · not proof of active capture.*Agent connected.*Running capture settings are unverified/);
  assert.match(h.find("ems-dashboard-card").allText(), /Managed process needs a restart/);
  assert.match(h.find("plans-dashboard-card").allText(), /No pending configuration restart reported.*new trace still needs verification/);
  assert.equal(h.calls.some(call => call.url.endsWith("/apply")), false);
});

test("post-save verification only links to investigation and does not start checks, watches or services", async () => {
  const config = dashboardDefaults(), saved = savedFixture(config);
  const h = harness([config, {planId: "save", request: config, files: saved.files}, saved]); await saveFixture(h);
  assert.equal(h.find("verify-capture").hidden, false);
  assert.equal(h.find("verify-viewer-link").hidden, false);
  assert.equal(h.find("verify-viewer-link").attributes.href, "http://127.0.0.1:8765/#investigate=1&service=cb-app");
  assert.equal(h.calls.length, 3); assert.equal(h.timers.length, 0);
  const html = fs.readFileSync(path.join(resource, "setup.html"), "utf8");
  const checklist = html.slice(html.indexOf('id="verify-capture"'), html.indexOf('id="restart-section"'));
  assert.match(checklist, /Files saved, agent connected and request captured are separate milestones/);
  assert.match(checklist, /Check capture readiness/); assert.match(checklist, /Watch next request &amp; save/);
  assert.match(checklist, /Wait for Watching/); assert.match(checklist, /partial-evidence warnings/);
  assert.match(checklist, /First useful capture progression/);
  assert.match(checklist, /Saved<\/strong>.*Apply \/ restart explicitly<\/strong>.*Open viewer<\/strong>.*Reproduce once<\/strong>.*Inspect evidence<\/strong>/s);
  assert.equal((checklist.match(/aria-current="step"/g) || []).length, 1);
  assert.match(checklist, /<li aria-current="step"><strong>Apply \/ restart explicitly/);
  assert.match(checklist, /target="_blank" rel="noopener noreferrer"/);
  assert.doesNotMatch(checklist, /type="checkbox"/, "the checklist cannot imply verified milestones from user ticks");
});

test("setup validation and expired sessions focus recovery without making a write or restart", async () => {
  const h = harness([dashboardDefaults(), {error: "This setup session expired after 15 minutes. Stop it and run setup again."}]);
  await h.ready(); h.find("viewer-port").value = "not a port";
  await h.find("setup-form").fire("submit");
  assert.equal(h.calls.length, 1); assert.equal(h.find("error").focused, true);
  assert.match(h.find("error").textContent, /Viewer port must be a whole number/);
  h.find("error").focused = false; h.find("viewer-port").value = 8765;
  await h.find("setup-form").fire("submit");
  assert.equal(h.find("error").focused, true); assert.match(h.find("error").textContent, /expired after 15 minutes.*run setup again/);
  assert.equal(h.calls.length, 2); assert.equal(h.find("review-view").hidden, true);
  assert.equal(h.calls.some(call => call.url.endsWith("/apply")), false);
  assert.equal(h.timers.length, 0);
});

test("existing-update shortcut selects only successful changed services and retains lifecycle review and consent", async () => {
  const loaded = dashboardDefaults(); loaded.configured = true;
  for (const key of ["cbApp", "ems", "plans"]) { loaded[key].configured = true; loaded[key].enabled = true; }
  const request = setup.requestFrom(loaded), services = [
    {key: "cbApp", status: "existing"}, {key: "ems", status: "updated", operation: "update", restartRequired: true},
    {key: "plans", status: "failed", operation: "update", error: "Save failed."}
  ];
  const files = [{path: loaded.installDir + "/ems/tracelens.json"}];
  const h = harness([loaded, loaded, connectionFixture(), {planId: "update", request, files, services},
    {installed: true, installDir: loaded.installDir, files, services},
    {planId: "restart-ems", services: [{key: "ems", status: "ready", action: "restart"}]},
    {services: [{key: "ems", status: "running"}]}]);
  await openExisting(h); await h.find("ems-configure-button").fire("click"); await saveFixture(h);
  assert.equal(h.find("done-service-actions").hidden, false); assert.equal(h.find("restart-section").hidden, true);
  assert.equal(h.find("ems-restart").checked, true); assert.equal(h.find("cb-app-restart").checked, false);
  assert.equal(h.find("plans-restart"), undefined);
  await h.find("done-service-actions").fire("click");
  assert.equal(h.calls.length, 5, "opening the shortcut does not request any runtime action or plan");
  assert.equal(h.find("restart-section").hidden, false); assert.equal(h.find("restart-heading").focused, true);
  assert.equal(h.find("restart-ack").checked, false);
  await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 5);
  await h.find("restart-plan-button").fire("click");
  assert.deepEqual(JSON.parse(h.calls[5].options.body), {installDir: loaded.installDir, services: ["ems"]});
  await h.find("restart-apply-button").fire("click"); assert.equal(h.calls.length, 6);
  h.find("restart-ack").checked = true; await h.find("restart-apply-button").fire("click");
  assert.equal(h.calls[6].url, "/api/setup/lifecycle/apply");
  assert.deepEqual(JSON.parse(h.calls[6].options.body), {installDir: loaded.installDir, services: ["ems"], planId: "restart-ems", confirmed: true});
});
