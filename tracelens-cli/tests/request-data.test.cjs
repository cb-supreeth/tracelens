const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const ui = require("../tracelens_cli/web/request-data.js");
const trace = attributes => ({attributes});
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.open = false; this.attributes = {}; this.listeners = {}; }
  append(...items) { this.children.push(...items); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name]; }
  addEventListener(name, listener) { this.listeners[name] = listener; }
  async click() { await (this.onclick || this.listeners.click)?.({preventDefault() {}, stopPropagation() {}}); }
  set textContent(value) { this.text = value; }
  get textContent() { return (this.text || "") + this.children.map(node => node.textContent).join(" "); }
  set innerHTML(_) { throw Error("Request evidence must never be parsed as HTML"); }
}
const document = {createElement: tag => new Element(tag)};
const descendants = node => [node, ...node.children.flatMap(descendants)];
test("old/default-off captures have no request-data panel", () => {
  for (const value of [null, {}, {attributes: {}}, trace({path: "/features"})]) assert.equal(ui.render(document, value), null);
});
test("captured incoming data stays collapsed and inert with truthful redaction notice", () => {
  const raw = trace({httpQueryParameters: '{"filter":["<script>literal</script>"],"token":"[REDACTED]"}',
    httpQueryParametersStatus: "captured", httpRequestBody: '{"id":9007199254740993,"name":"<img src=x onerror=alert(1)>"}',
    httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json", httpPayloadRedacted: "true", httpPayloadRedaction: "enabled"});
  const before = JSON.stringify(raw), panel = ui.render(document, raw);
  assert.equal(panel.tagName, "details"); assert.equal(panel.open, false);
  assert.equal(panel.children[0].textContent, "Request data · captured");
  assert.match(panel.textContent, /9007199254740993/); assert.doesNotMatch(panel.textContent, /9007199254740992/);
  assert.match(panel.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.match(panel.textContent, /Best-effort credential-field redaction was enabled/);
  assert.match(panel.textContent, /never replayed/); assert.equal(JSON.stringify(raw), before);
});
test("explicitly unredacted local captures display credential fields without changing their values", () => {
  const raw = trace({httpPayloadRedaction: "disabled", httpQueryParametersStatus: "captured",
    httpQueryParameters: '{"api_key":["synthetic-query-secret"],"token":["<script>synthetic-token</script>"]}',
    httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json",
    httpRequestBody: '{"password":"synthetic-password","nested":{"secret":"synthetic-nested-secret"}}'});
  const before = JSON.stringify(raw), projected = ui.project(raw), panel = ui.render(document, raw);
  assert.equal(projected.redactionPolicy, "disabled");
  assert.equal(panel.children[0].textContent, "Request data · captured");
  assert.match(panel.textContent, /Redaction was disabled.*local debugging/);
  assert.match(panel.textContent, /may include credentials/);
  assert.match(panel.textContent, /synthetic-query-secret/);
  assert.match(panel.textContent, /<script>synthetic-token<\/script>/);
  assert.match(panel.textContent, /synthetic-password/);
  assert.match(panel.textContent, /synthetic-nested-secret/);
  assert.doesNotMatch(panel.textContent, /redaction was enabled|\[REDACTED\]/);
  assert.equal(JSON.stringify(raw), before);
});
test("enabled policy does not claim that unchanged values were redacted", () => {
  const raw = trace({httpPayloadRedaction: "enabled", httpQueryParametersStatus: "captured", httpQueryParameters: '{"limit":["1"]}'});
  const panel = ui.render(document, raw);
  assert.equal(ui.project(raw).redactionPolicy, "enabled");
  assert.equal(panel.children[0].textContent, "Request data · captured");
  assert.match(panel.textContent, /Best-effort credential-field redaction was enabled/);
  assert.doesNotMatch(panel.textContent, /marked redacted|policy was not recorded|unredacted/);
});
test("legacy and malformed policy metadata never imply that credential redaction was enabled", () => {
  for (const policy of [undefined, null, true, "false", "ENABLED", "unexpected"]) {
    for (const redacted of [undefined, "true"]) {
      const raw = trace({httpPayloadRedaction: policy, httpPayloadRedacted: redacted,
        httpQueryParametersStatus: "captured", httpQueryParameters: '{"id":["value"]}'});
      const panel = ui.render(document, raw);
      assert.equal(ui.project(raw).redactionPolicy, "unknown");
      assert.match(panel.textContent, /Redaction policy was not recorded/);
      assert.doesNotMatch(panel.textContent, /redaction was enabled|redaction was disabled|· unredacted/);
      assert.equal(panel.children[0].textContent, "Request data · captured");
      if (redacted) assert.match(panel.textContent, /original values cannot be recovered/);
    }
  }
});
test("imported evidence with disabled policy and an existing redacted marker preserves both facts", () => {
  const raw = trace({httpPayloadRedaction: "disabled", httpPayloadRedacted: true,
    httpQueryParametersStatus: "captured", httpQueryParameters: '{"password":"[REDACTED]"}'});
  const panel = ui.render(document, raw);
  assert.equal(panel.children[0].textContent, "Request data · captured");
  assert.match(panel.textContent, /Redaction was disabled/);
  assert.match(panel.textContent, /\[REDACTED\]/);
  assert.match(panel.textContent, /original values cannot be recovered/);
});
test("embedded request data has no nested disclosure or duplicate heading and preserves exact evidence", () => {
  const raw = trace({httpPayloadRedaction: "disabled", httpQueryParametersStatus: "captured",
    httpQueryParameters: '{"token":["synthetic-secret"],"filter":["one","two"]}',
    httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json",
    httpRequestBody: '{"id":9007199254740993,"value":"<script>literal</script>"}'});
  const before = JSON.stringify(raw), panel = ui.render(document, raw, {collapsible: false});
  const all = node => [node, ...node.children.flatMap(all)];
  assert.equal(panel.tagName, "section");
  assert.equal(panel.className, "request-data request-data-embedded");
  assert.equal(all(panel).filter(node => ["details", "summary"].includes(node.tagName)).length, 0);
  assert.doesNotMatch(panel.textContent, /Request data · captured|unredacted/);
  assert.deepEqual(all(panel).filter(node => node.tagName === "h4").map(node => node.textContent),
    ["Query parameters", "Request body · JSON"]);
  assert.deepEqual(all(panel).filter(node => node.tagName === "pre").map(node => node.textContent),
    ui.project(raw).fields.map(field => field.data));
  assert.equal(ui.project(raw).redactionPolicy, "disabled");
  assert.equal(JSON.stringify(raw), before);
});
test("standalone request data keeps exactly one disclosure with a clean label for every policy", () => {
  const all = node => [node, ...node.children.flatMap(all)];
  for (const policy of ["enabled", "disabled", undefined]) {
    for (const redacted of [undefined, "true"]) {
      const panel = ui.render(document, trace({httpPayloadRedaction: policy, httpPayloadRedacted: redacted,
        httpQueryParametersStatus: "captured", httpQueryParameters: '{"id":["value"]}'}));
      assert.equal(panel.tagName, "details");
      assert.equal(panel.open, false);
      assert.equal(all(panel).filter(node => node.tagName === "details").length, 1);
      const summaries = all(panel).filter(node => node.tagName === "summary");
      assert.equal(summaries.length, 1);
      assert.equal(summaries[0].textContent, "Request data · captured");
    }
  }
});
test("omitted, incomplete and not-consumed statuses never display retained prefixes", () => {
  for (const status of ["not-consumed", "incomplete", "omitted-too-large", "omitted-invalid", "unsupported-content-type", "unsupported-encoding", "unsupported-container", "omitted-capacity"]) {
    const raw = trace({httpRequestBodyStatus: status, httpRequestBody: '{"value":"DO-NOT-PRINT"}', httpRequestBodyFormat: "json"});
    assert.equal(ui.project(raw).fields[0].data, null);
    assert.doesNotMatch(ui.render(document, raw).textContent, /DO-NOT-PRINT/);
  }
});
test("untrusted imported data is bounded and typed while form fields retain repeated values", () => {
  const body = raw => ui.project(trace({httpRequestBodyStatus: "captured", httpRequestBody: raw, httpRequestBodyFormat: "json"})).fields[0];
  for (const value of ["{" , {}, undefined]) assert.equal(body(value).status, "omitted-invalid");
  for (const value of ["x".repeat(16385), "[".repeat(34) + "0" + "]".repeat(34)]) assert.equal(body(value).status, "omitted-display-limit");
  const query = ui.project(trace({httpQueryParametersStatus: "captured", httpQueryParameters: '{"k":"not-array"}'}));
  assert.equal(query.fields[0].status, "omitted-invalid");
  const form = ui.project(trace({httpRequestBodyFormat: "form", httpRequestBodyStatus: "captured", httpRequestBody: '{"k":["first","second"]}'}));
  assert.match(form.fields[0].label, /form/); assert.match(form.fields[0].data, /first.*\n.*second/);
  const redacted = ui.project(trace({httpRequestBodyFormat: "form", httpRequestBodyStatus: "captured",
    httpRequestBody: '{"name":["safe"],"password":"[REDACTED]"}', httpPayloadRedacted: "true"}));
  assert.equal(redacted.fields[0].status, "captured"); assert.match(redacted.fields[0].data, /\[REDACTED\]/);
});
test("valid bounded bodies retain exact huge numbers, long keys and broad JSON arrays", () => {
  for (const raw of ['{"large":1e999,"small":1e-999,"id":9007199254740993}', JSON.stringify({["k".repeat(300)]: "value"}),
    JSON.stringify({rows: Array(5000).fill(0)})]) {
    const input = trace({httpRequestBodyStatus: "captured", httpRequestBody: raw, httpRequestBodyFormat: "json"});
    const field = ui.project(input).fields[0];
    assert.equal(field.status, "captured"); assert.equal(field.data, raw);
    assert.match(ui.render(document, input).textContent, /Request data · captured/);
  }
});
test("viewer display omissions do not claim retained evidence was missing or malformed", () => {
  const input = trace({httpRequestBodyStatus: "captured", httpRequestBody: JSON.stringify({value: "x".repeat(16384)}), httpRequestBodyFormat: "json"});
  const before = JSON.stringify(input), panel = ui.render(document, input);
  assert.match(panel.textContent, /Request data · not displayed/);
  assert.match(panel.textContent, /viewer safety limit; captured data is unchanged/);
  assert.doesNotMatch(panel.textContent, /not retained|invalid or unsupported|x{20}/);
  assert.equal(JSON.stringify(input), before);
});
test("copy controls copy exactly the displayed query parameters and request body on explicit click", async () => {
  const raw = trace({httpQueryParametersStatus: "captured", httpQueryParameters: '{"filter":["one","two"],"html":["<b>literal</b>"]}',
    httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json", httpRequestBody: '{"id":9007199254740993,"name":"<script>literal</script>"}'});
  const before = JSON.stringify(raw);
  for (const collapsible of [true, false]) {
    const copied = [];
    const clipboardDocument = {createElement: document.createElement,
      defaultView: {navigator: {clipboard: {writeText: async text => {copied.push(text);}}}}};
    const panel = ui.render(clipboardDocument, raw, {collapsible});
    const buttons = descendants(panel).filter(node => node.tagName === "button");
    const values = descendants(panel).filter(node => node.tagName === "pre").map(node => node.textContent);
    assert.equal(buttons.length, 2);
    assert.deepEqual(buttons.map(button => button.getAttribute("aria-label")), ["Copy query parameters", "Copy request body"]);
    assert.deepEqual(copied, [], "rendering must not copy request evidence");
    for (const button of buttons) await button.click();
    assert.deepEqual(copied, values);
    assert.equal(copied[1], raw.attributes.httpRequestBody, "body numeric tokens are preserved exactly");
    assert.equal(JSON.stringify(raw), before);
  }
});
test("copy controls preserve repeated form fields and are absent for unavailable evidence", async () => {
  const copied = [];
  const clipboardDocument = {createElement: document.createElement,
    defaultView: {navigator: {clipboard: {writeText: async text => {copied.push(text);}}}}};
  const form = ui.render(clipboardDocument, trace({httpRequestBodyStatus: "captured", httpRequestBodyFormat: "form",
    httpRequestBody: '{"tag":["one","two"],"password":"[REDACTED]"}'}));
  const button = descendants(form).find(node => node.tagName === "button");
  await button.click();
  assert.deepEqual(copied, [descendants(form).find(node => node.tagName === "pre").textContent]);
  assert.deepEqual(JSON.parse(copied[0]), {tag: ["one", "two"], password: "[REDACTED]"});
  for (const status of ["not-consumed", "incomplete", "omitted-too-large", "omitted-invalid", "unsupported-content-type"]) {
    const omitted = ui.render(clipboardDocument, trace({httpRequestBodyStatus: status, httpRequestBodyFormat: "json",
      httpRequestBody: '{"secret":"DO-NOT-COPY"}'}));
    assert.equal(descendants(omitted).filter(node => node.tagName === "button").length, 0);
  }
  const invalid = ui.render(clipboardDocument, trace({httpQueryParametersStatus: "captured", httpQueryParameters: "{"}));
  assert.equal(descendants(invalid).filter(node => node.tagName === "button").length, 0);
});
test("request-data module is loaded before app and only attaches to full segment detail", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.ok(html.indexOf("request-data.js") < html.indexOf("app.js"));
  const app = fs.readFileSync(require.resolve("../tracelens_cli/web/app.js"), "utf8");
  assert.match(app, /TraceLensRequestData\?\.render\(document, trace\)/);
});
