const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const copy = require("../tracelens_cli/web/evidence-copy.js");
function fixture(writeText) {
  class Element {
    constructor(tag) { this.tagName = tag; this.children = []; this.attributes = {}; }
    append(...nodes) { this.children.push(...nodes); }
    setAttribute(name, value) { this.attributes[name] = value; }
  }
  const timers = new Map(); let timerId = 0;
  const document = {createElement: tag => new Element(tag), defaultView: {
    navigator: {clipboard: writeText ? {writeText} : undefined},
    setTimeout(fn) { timers.set(++timerId, fn); return timerId; },
    clearTimeout(id) { timers.delete(id); },
  }};
  return {document, timers};
}
const click = control => control.onclick({preventDefault() {}, stopPropagation() {}});

test("copy happens only on explicit activation and writes the exact displayed text", async () => {
  const writes = [], {document, timers} = fixture(async value => writes.push(value));
  const text = 'SELECT "雪", \'<script>literal</script>\'\nWHERE id = 9007199254740993';
  const [control, status] = copy.button(document, text, "Copy SQL").children;
  assert.deepEqual(writes, []); assert.equal(control.type, "button");
  assert.equal(control.attributes["aria-label"], "Copy SQL");
  assert.equal(status.attributes["aria-live"], "polite");
  let prevented = false, stopped = false;
  await control.onclick({preventDefault() { prevented = true; }, stopPropagation() { stopped = true; }});
  assert.ok(prevented && stopped); assert.deepEqual(writes, [text]);
  assert.equal(control.textContent, "Copied"); assert.equal(control.disabled, false);
  assert.equal(status.textContent, "Copied to clipboard.");
  [...timers.values()][0](); assert.equal(control.textContent, "Copy"); assert.equal(status.textContent, "");
});
test("pending copies cannot submit twice and a retry cancels earlier feedback reset", async () => {
  const writes = []; let finish;
  const {document, timers} = fixture(value => { writes.push(value); return new Promise(resolve => { finish = resolve; }); });
  const [control] = copy.button(document, "value", "Copy parameters").children;
  const first = click(control); assert.equal(control.disabled, true); assert.equal(control.textContent, "Copying…");
  await click(control); assert.equal(writes.length, 1);
  finish(); await first; assert.equal(timers.size, 1);
  const second = click(control); assert.equal(timers.size, 0); finish(); await second;
  assert.equal(writes.length, 2); assert.equal(timers.size, 1);
});
test("clipboard denial or absence gives actionable feedback without leaking exception data", async () => {
  for (const writer of [undefined, async () => { throw new Error("private platform exception"); }]) {
    const {document} = fixture(writer), [control, status] = copy.button(document, "value", "Copy SQL").children;
    await click(control);
    assert.equal(control.disabled, false); assert.equal(control.textContent, "Retry copy");
    assert.match(status.textContent, /Could not copy.*manually/); assert.doesNotMatch(status.textContent, /private/);
    assert.match(status.className, /evidence-copy-error/);
    document.defaultView.navigator.clipboard = {writeText: async () => {}};
    await click(control); assert.equal(control.textContent, "Copied"); assert.doesNotMatch(status.className, /error/);
  }
});
test("browser export and script/style ordering support both shared evidence renderers", () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/evidence-copy.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensEvidenceCopy.button, "function");
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.ok(html.indexOf('evidence-copy.js') < html.indexOf('request-data.js'));
  assert.ok(html.indexOf('evidence-copy.js') < html.indexOf('dependency-ui.js'));
  assert.match(html, /href="\/static\/evidence-copy.css"/);
});
