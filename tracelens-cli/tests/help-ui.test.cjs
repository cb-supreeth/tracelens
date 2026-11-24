"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const web = path.resolve(__dirname, "../tracelens_cli/web");
const html = fs.readFileSync(path.join(web, "index.html"), "utf8");
const script = fs.readFileSync(path.join(web, "help.js"), "utf8");
const css = fs.readFileSync(path.join(web, "help.css"), "utf8");
const helpMarkup = /<dialog id="help-dialog"[\s\S]*?<\/dialog>/.exec(html)?.[0];
const plainText = markup => markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();

function harness(hash = "") {
  const calls = [];
  const forbidden = name => () => assert.fail("Help must not access " + name);
  class Element {
    constructor(id) { this.id = id; this.listeners = {}; this.open = false; }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    fire(type) {
      const event = {defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }};
      for (const listener of this.listeners[type] || []) listener(event);
      return event;
    }
    showModal() { assert.equal(this.open, false); this.open = true; calls.push("showModal"); }
    close() { assert.equal(this.open, true); this.open = false; calls.push("close"); this.fire("close"); }
    focus(options) { calls.push(["focus", this.id, options.preventScroll]); }
  }
  const elements = new Map(["help-open", "help-dialog", "help-close"].map(id => [id, new Element(id)]));
  const location = Object.freeze({hash});
  const filters = Object.freeze({path: "/api/v2/features", user: "developer", site: "local", mode: "live"});
  const selection = Object.freeze({traceId: "selected-trace", segmentId: "selected-segment", recording: false});
  const context = {
    document: {getElementById(id) {
      assert.ok(elements.has(id), "Help cannot touch request/filter/diagram elements: " + id);
      return elements.get(id);
    }},
    window: {location, history: {replaceState: forbidden("history"), pushState: forbidden("history")}},
    location, filters, selection,
    fetch: forbidden("network"), XMLHttpRequest: forbidden("network"),
    localStorage: new Proxy({}, {get: forbidden("local storage")}),
    indexedDB: new Proxy({}, {get: forbidden("IndexedDB")}),
    setInterval: forbidden("polling"), setTimeout: forbidden("timers")
  };
  vm.runInNewContext(script, context);
  return {calls, context, trigger: elements.get("help-open"), dialog: elements.get("help-dialog"), close: elements.get("help-close")};
}

test("guide is initially closed and available from the global header, independent of each view", () => {
  const header = /<header>[\s\S]*?<\/header>/.exec(html)?.[0];
  const trigger = /<button\b([^>]*\bid="help-open"[^>]*)>([\s\S]*?)<\/button>/.exec(header);
  assert.ok(trigger, "The global header contains the help trigger");
  assert.match(trigger[1], /\btype="button"/);
  assert.match(trigger[1], /\baria-haspopup="dialog"/);
  assert.match(trigger[1], /\baria-controls="help-dialog"/);
  assert.equal(plainText(trigger[2]), "What is TraceLens");
  assert.ok(html.indexOf('id="help-open"') < html.indexOf("<main>"));
  assert.ok(html.indexOf('id="help-dialog"') > html.indexOf("</main>"));
  assert.match(helpMarkup, /^<dialog id="help-dialog" aria-labelledby="help-title" aria-describedby="help-intro">/);
  assert.doesNotMatch(helpMarkup.split(">", 1)[0], /\bopen\b/);
  assert.match(helpMarkup, /<h2 id="help-title">What is TraceLens\?<\/h2>/);
  assert.match(helpMarkup, /id="help-close"[^>]*autofocus[^>]*aria-label="Close TraceLens guide"/);
  const details = [...helpMarkup.matchAll(/<details\b([^>]*)>/g)];
  assert.equal(details.length, 7);
  assert.ok(details.every(match => !/\bopen\b/.test(match[1])), "Navigation topics start collapsed");
});

test("global help trigger pairs its text with a decorative inline icon without extra focus stops or loads", () => {
  const trigger = /<button\b[^>]*\bid="help-open"[^>]*>([\s\S]*?)<\/button>/.exec(html);
  assert.ok(trigger, "The help trigger is present");
  const content = trigger[1];
  const icons = [...content.matchAll(/<svg\b([^>]*)>[\s\S]*?<\/svg>/g)];
  assert.equal(icons.length, 1, "The trigger has one inline SVG icon");
  assert.match(icons[0][1], /\baria-hidden="true"/, "The icon must not repeat the button's text label");
  assert.match(icons[0][1], /\bfocusable="false"/, "The decorative icon must not become a focus stop");
  assert.match(content, /<span\b[^>]*>\s*What is TraceLens\s*<\/span>/);
  assert.doesNotMatch(content, /<(?:a|button|input|select|textarea|summary|iframe|object|embed)\b/i);
  assert.doesNotMatch(content, /\b(?:tabindex\s*=|contenteditable\s*=|role="(?:button|link)")/i,
    "Only the help button belongs in the trigger's tab order");
  assert.doesNotMatch(content, /\b(?:href|src)\s*=|url\s*\(/i,
    "The icon must not load an external asset");
});

test("open uses a native modal; Close restores trigger focus without moving the page", () => {
  const h = harness();
  assert.deepEqual(h.calls, [], "The guide does nothing before an explicit click");
  h.trigger.fire("click");
  assert.equal(h.dialog.open, true);
  assert.deepEqual(h.calls, ["showModal"], "Native modal owns focus containment and background inertness");
  h.trigger.fire("click");
  assert.equal(h.calls.length, 1, "Repeated opening is harmless");
  h.close.fire("click");
  assert.equal(h.dialog.open, false);
  assert.deepEqual(h.calls, ["showModal", "close", ["focus", "help-open", true]]);
  h.close.fire("click");
  assert.equal(h.calls.length, 3, "Repeated closing is harmless");
});

test("Escape cancellation closes only the guide and permits reopening", () => {
  const h = harness();
  h.trigger.fire("click");
  const event = h.dialog.fire("cancel");
  assert.equal(event.defaultPrevented, true);
  assert.equal(h.dialog.open, false);
  assert.deepEqual(h.calls.slice(-2), ["close", ["focus", "help-open", true]]);
  h.trigger.fire("click");
  assert.equal(h.dialog.open, true);
  h.dialog.close();
  assert.deepEqual(h.calls.at(-1), ["focus", "help-open", true], "Native closure also restores focus");
});

test("guide leaves live requests, request flow, execution and saved snapshot state untouched", () => {
  for (const hash of ["", "#trace=selected-trace", "#trace=selected-trace&segment=selected-segment", "#snapshot=saved-id"]) {
    const h = harness(hash);
    const before = JSON.stringify({location: h.context.location, filters: h.context.filters, selection: h.context.selection});
    h.trigger.fire("click"); h.dialog.fire("cancel");
    h.trigger.fire("click"); h.close.fire("click");
    assert.equal(JSON.stringify({location: h.context.location, filters: h.context.filters, selection: h.context.selection}), before);
  }
  assert.doesNotMatch(script, /\b(?:fetch|localStorage|sessionStorage|indexedDB|history|location|setInterval|setTimeout)\b/);
});

test("help assets are local, packaged and do not add remote loads or documentation routes", () => {
  assert.match(html, /<link rel="stylesheet" href="\/static\/help\.css">/);
  assert.match(html, /<script src="\/static\/help\.js"><\/script>/);
  assert.ok(html.indexOf('src="/static/help.js"') < html.indexOf('src="/static/app.js"'));
  assert.doesNotMatch(helpMarkup, /(?:href|src)\s*=/i);
  assert.doesNotMatch(helpMarkup, /\/Users\/|\/home\/|https?:\/\//);
  assert.match(fs.readFileSync(path.resolve(web, "../../../tracelens-tool/build.gradle"), "utf8"), /from\('\.\.\/tracelens-cli\/tracelens_cli\/web'\)/);
  assert.doesNotMatch(helpMarkup, /\b(?:docs|skills)\//i);
});

test("quickstart walks from finding a request through request and code flow to saving it", () => {
  const quickstart = /<section\b[^>]*class="help-quickstart"[\s\S]*?<\/section>/.exec(helpMarkup)?.[0];
  assert.ok(quickstart, "A short first-investigation walkthrough is visible before the topics");
  assert.ok(helpMarkup.indexOf(quickstart) < helpMarkup.indexOf("<details"));
  assert.match(quickstart, /<ol\b[^>]*>/);
  const steps = [...quickstart.matchAll(/<li\b[^>]*>([\s\S]*?)<\/li>/g)].map(match => plainText(match[1]));
  assert.equal(steps.length, 4, "Keep the introductory walkthrough to four UI actions");
  assert.match(steps[0], /\bLive requests\b/);
  assert.match(steps[1], /\brequest (?:flow|diagram)\b/i);
  assert.match(steps[2], /\bservice\b/i);
  assert.match(steps[2], /\bCode flow\b/);
  assert.match(steps[3], /\bSave snapshot\b/);
});

test("four journey cards preserve ordered-list semantics and hide decorative numbers", () => {
  const journey = /<ol\b([^>]*class="help-steps"[^>]*)>([\s\S]*?)<\/ol>/.exec(helpMarkup);
  assert.ok(journey, "The journey cards are an ordered list, not action buttons");
  assert.match(journey[1], /\brole="list"/, "Keep list semantics when CSS removes native markers");
  const steps = [...journey[2].matchAll(/<li\b([^>]*)>([\s\S]*?)<\/li>/g)];
  assert.equal(steps.length, 4);
  steps.forEach(([_, attributes, body], index) => {
    assert.match(attributes, /\bclass="[^"]*\bhelp-step\b[^"]*"/);
    const number = /<span\b([^>]*class="help-step-number"[^>]*)>(\d+)<\/span>/.exec(body);
    assert.ok(number, "Each journey card has a visible number");
    assert.match(number[1], /\baria-hidden="true"/, "Avoid repeating list numbers to screen readers");
    assert.equal(Number(number[2]), index + 1);
    assert.match(body, /class="help-step-title">[^<]+<\//, "Each card has a readable title");
  });
});

test("seven navigation topics cover the request list, diagrams, execution, jobs/errors, performance, saved captures and missing requests", () => {
  const topics = [...helpMarkup.matchAll(/<details\b[^>]*>\s*<summary\b[^>]*>([\s\S]*?)<\/summary>([\s\S]*?)<\/details>/g)]
    .map(match => ({summary: plainText(match[1]), body: plainText(match[2])}));
  const expectedTopics = [
    [/\bfind(?:ing)?\b.*\brequests?\b/i, ["API path", "More filters", "Reset filters"]],
    [/\brequest (?:flow|diagram)\b/i, ["Show HTTP hops"]],
    [/\bCode flow\b/i, ["Code flow", "Call tree", "Timeline / source", "← request flow"]],
    [/\b(?:scheduled )?jobs\b.*\berrors\b/i, ["Scheduled jobs", "Errors"]],
    [/\bPerformance insights\b/i, ["Analyze", "Open query evidence", "suspected N+1"]],
    [/\bsav(?:e|ed|ing)\b/i, ["Save snapshot", "Saved captures", "Viewer file", "Browser only", "Copy snapshot link"]],
    [/\b(?:missing|no matching|can['’]t find)\b.*\brequests?\b|\brequests?\b.*\bmissing\b/i, ["Show all retained"]]
  ];
  assert.equal(topics.length, expectedTopics.length);
  const matchedTopics = new Set();
  for (const [summaryPattern, labels] of expectedTopics) {
    const topic = topics.find(candidate => summaryPattern.test(candidate.summary));
    assert.ok(topic, "Missing navigation topic: " + summaryPattern);
    matchedTopics.add(topic);
    for (const label of labels) {
      assert.ok(topic.body.includes(label), "Missing UI control in “" + topic.summary + "”: " + label);
    }
  }
  assert.equal(matchedTopics.size, topics.length, "Each disclosure has a distinct navigation purpose");
});

test("seven topic cards keep native disclosures and accessible text beside decorative icons", () => {
  const topics = [...helpMarkup.matchAll(/<details\b([^>]*)>\s*<summary\b[^>]*>([\s\S]*?)<\/summary>/g)];
  assert.equal(topics.length, 7);
  for (const [_, attributes, summary] of topics) {
    assert.match(attributes, /\bclass="[^"]*\bhelp-topic\b[^"]*"/);
    const icon = /<span\b[^>]*class="help-topic-icon"[^>]*>\s*<svg\b([^>]*)>/.exec(summary);
    assert.ok(icon, "Each topic has an inline icon");
    assert.match(icon[1], /\baria-hidden="true"/);
    assert.match(icon[1], /\bfocusable="false"/);
    assert.match(summary, /class="help-topic-title">[^<]+<\/span>/);
    assert.match(summary, /class="help-topic-description">[^<]+<\/span>/);
    assert.match(summary, /class="help-topic-chevron"[^>]*aria-hidden="true"/);
  }
  for (const [_, attributes] of helpMarkup.matchAll(/<svg\b([^>]*)>/g)) {
    assert.match(attributes, /\baria-hidden="true"/, "Guide icons must not replace or repeat their text label");
    assert.match(attributes, /\bfocusable="false"/, "Decorative SVGs must not become extra focus stops");
  }
});

test("guide cards explain navigation without exposing request actions or links", () => {
  const buttons = [...helpMarkup.matchAll(/<button\b([^>]*)>/g)];
  assert.equal(buttons.length, 1, "Close is the guide's only action button");
  assert.match(buttons[0][1], /\bid="help-close"/);
  assert.doesNotMatch(helpMarkup, /<(?:a|form|input|select|textarea)\b/i);
  assert.doesNotMatch(helpMarkup, /\b(?:role="(?:button|link)"|on[a-z]+\s*=|tabindex\s*=)/i,
    "Only native Close and disclosure controls belong in the guide's tab order");
});

test("guide stays focused on using the UI and contains no installation, configuration or architecture guidance", () => {
  // This is an actual viewer disclosure label, not command-line setup guidance.
  const text = plainText(helpMarkup).replaceAll("Use with CLI/MCP", "Use with other tools");
  assert.doesNotMatch(text, /\b(?:MCP|CLI|JVM|JAR|Codex|Cursor|agents?|servers?|skills?|instrumentation|architecture|setup|set up|configuration|configure|install(?:ation|ed|ing)?)\b/i);
  assert.doesNotMatch(text, /httpIncludePaths|tracelens\.configPath|source[-_ ]?roots?|--[a-z][a-z-]*|-javaagent|\b(?:pipx?|gradle|curl|localhost)\b|127\.0\.0\.1/i);
  assert.doesNotMatch(helpMarkup, /<pre\b/i, "UI help should not include commands or configuration blocks");
});

test("guide explains SQL card navigation and raw local evidence limits without setup instructions", () => {
  const text = plainText(helpMarkup);
  assert.match(text, /Click a purple DB CALL card.*grouped SQL executions.*operation and table names.*View calling code/);
  assert.match(text, /Details also appear in Code flow and Timeline \/ source/);
  assert.match(text, /SQL CAPTURE LIMIT.*queries were not retained/);
  assert.match(text, /DB routing boxes are pool choices, not executed queries/);
  assert.match(text, /SQL and parameter values are separate local opt-ins/);
  assert.match(text, /reconstructed SQL preview.*original template and typed bindings.*not exact wire SQL or a replay command/);
  assert.match(text, /SQL literals and bindings can be sensitive.*before sharing/);
  assert.match(text, /Result rows are not collected/);
  assert.match(text, /returned query does not establish transaction commit/);
});

test("guide explains current investigation, saved-evidence and execution controls with their limits", () => {
  const text = plainText(helpMarkup.replaceAll("&amp;", "&"));
  const workflowMarkup = html.slice(0, html.indexOf('<dialog id="help-dialog"'));
  const controls = [
    ["guided-investigation.js", ["Backend path", "Check capture readiness", "Watch window", "Watch next request & save", "Cancel watch"]],
    ["capture-tools.js", ["Compare snapshots", "Baseline snapshot", "Candidate snapshot", "Compare saved snapshots", "Search saved evidence", "Use with CLI/MCP"]],
    ["execution-graph.js", ["Search retained execution evidence", "Previous match", "Next match", "Focus selected branch", "Clear search / focus", "Open local source"]]
  ];
  for (const label of ["Start an investigation", "Compare & search saved captures"]) {
    assert.ok(text.includes(label), "Guide should name the workflow entry point: " + label);
    assert.ok(plainText(workflowMarkup.replaceAll("&amp;", "&")).includes(label), "Entry point should exist: " + label);
  }
  for (const [file, labels] of controls) {
    const implementation = fs.readFileSync(path.join(web, file), "utf8");
    for (const label of labels) {
      assert.ok(text.includes(label), "Guide should explain the current control: " + label);
      assert.ok(implementation.includes(label), "Documented label should exist in " + file + ": " + label);
    }
  }
  assert.match(text, /Only one completed, unambiguous new match is saved/);
  assert.match(text, /check capture settings and look for existing traces/);
  assert.match(text, /This does not diagnose application bugs/);
  assert.doesNotMatch(helpMarkup, /<strong>Diagnose<\/strong>/);
  assert.match(text, /does not enable recording or replay/);
  assert.match(text, /fixed evidence, not live data/);
  assert.match(text, /missing observation is not proof of removed code/);
  assert.match(text, /one timing difference is not a benchmark/);
  assert.match(text, /no match does not prove code was unused/);
  assert.match(text, /current checkout.*build-mismatch warning/);
  assert.match(text, /Live source uses your current checkout; snapshots keep the lines saved with them/);
  assert.match(text, /bounded execution view, not every raw event/);
  assert.match(text, /leaves the original unchanged/);
});

test("only the body scrolls while the modal heading and Close stay available on small screens", () => {
  assert.match(css, /#help-dialog\[open\]\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column/);
  assert.match(css, /\.help-heading\s*\{[^}]*flex-shrink:\s*0/s);
  assert.match(css, /\.help-body\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto/s);
  assert.match(css, /@media\s*\(max-width:\s*600px\)/);
  assert.match(css, /100dvh/);
  assert.match(css, /:focus-visible/);
});
