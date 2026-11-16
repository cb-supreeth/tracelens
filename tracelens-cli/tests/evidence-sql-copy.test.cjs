const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const deps = require("../tracelens_cli/web/dependency-ui.js");

class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.attributes = {}; this.text = ""; }
  append(...nodes) { for (const node of nodes) { node.parentElement = this; this.children.push(node); } }
  replaceChildren(...nodes) { this.text = ""; this.children = []; this.append(...nodes); }
  setAttribute(name, value) { this.attributes[name] = value; }
  getAttribute(name) { return this.attributes[name]; }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(_) { throw Error("Evidence must be inert text"); }
}
const all = (node, predicate) => [node, ...node.children.flatMap(child => all(child, () => true))].filter(predicate);
const tags = (node, tag) => all(node, element => element.tagName === tag);
const classed = (node, cls) => all(node, element => (element.className || "").split(" ").includes(cls));
const buttons = (node, label) => tags(node, "button").filter(button => !label || button.getAttribute("aria-label") === label);
function fixture() {
  const copied = [];
  return {copied, document: {createElement: tag => new Element(tag),
    defaultView: {navigator: {clipboard: {async writeText(text) { copied.push(text); }}}}}};
}
async function click(button) {
  let prevented = false, stopped = false;
  assert.ok(button, "Copy action exists");
  await button.onclick({preventDefault() { prevented = true; }, stopPropagation() { stopped = true; }});
  assert.equal(prevented, true); assert.equal(stopped, true);
}
const jdbc = extra => ({boundaryKind: "database", system: "postgresql", direction: "outbound",
  evidenceKind: "jdbc-execution", operation: "executeQuery", status: "OBSERVED", sqlSource: "statement",
  sql: "SELECT *\nFROM features WHERE name = '<img src=x>'", ...extra});
const parameter = (index, value, extra) => ({index, type: "String", status: "captured", value, ...extra});
const native = extra => ({...jdbc(), system: "clickhouse", operation: "query", evidenceKind: "clickhouse-query",
  sqlSource: "native-query", sql: "SELECT * FROM usage WHERE site = {site:String}", ...extra});

test("lazy SQL offers no copy action until complete retained detail is loaded", async () => {
  const {document, copied} = fixture();
  let complete, loads = 0;
  const preview = jdbc({sql: undefined, sqlPreview: "SELECT preview…"});
  const panel = deps.renderLazyJdbc(document, preview, () => { loads++; return new Promise(resolve => { complete = resolve; }); });
  assert.equal(buttons(panel).length, 0); assert.deepEqual(copied, []);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(loads, 1); complete(jdbc({sql: "SELECT complete_retained_statement"}));
  await new Promise(resolve => setImmediate(resolve));
  await click(buttons(panel, "Copy SQL")[0]);
  assert.deepEqual(copied, ["SELECT complete_retained_statement"]);
  assert.doesNotMatch(copied[0], /preview/);
});

test("missing lazy SQL detail explicitly refuses to copy compact preview text", async () => {
  const {document, copied} = fixture();
  const panel = deps.renderLazyJdbc(document, jdbc({sql: undefined, sqlPreview: "SELECT preview…"}), async () => { throw new Error("Saved chunk unavailable"); });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(buttons(panel).length, 0); assert.deepEqual(copied, []);
  assert.match(panel.textContent, /Saved chunk unavailable.*preview text is not offered/);
});

test("compact boundary previews survive metadata projection but never become copyable SQL", () => {
  const {document} = fixture(), preview = jdbc({sql: undefined, sqlPreview: "SELECT preview…", sqlDetailsAvailable: true});
  const metadata = deps.boundaryMetadata({type: "EXTERNAL_CALL", attributes: preview});
  assert.equal(deps.sqlTitle(metadata), "SELECT preview…");
  assert.equal(metadata.sql, null); assert.equal(metadata.sqlDetailsAvailable, true);
  assert.equal(buttons(deps.renderJdbc(document, metadata)).length, 0);
});

test("recorded JDBC SQL is copied exactly as displayed, only on a user click", async () => {
  for (const system of ["mysql", "postgresql"]) {
    const {document, copied} = fixture(), boundary = jdbc({system}), panel = deps.renderJdbc(document, boundary);
    assert.deepEqual(copied, []);
    assert.equal(buttons(panel).length, 1);
    assert.equal(classed(panel, "evidence-heading").length, 1);
    const control = buttons(panel, "Copy SQL")[0];
    assert.equal(control.parentElement.parentElement.className, "evidence-heading");
    await click(control);
    assert.deepEqual(copied, [tags(panel, "pre")[0].textContent]);
    assert.equal(copied[0], boundary.sql);
    assert.equal(tags(panel, "img").length, 0);
  }
});

test("prepared SQL preview, original template and captured values copy separately", async () => {
  const {document, copied} = fixture();
  const boundary = jdbc({sqlSource: "prepared", sql: "SELECT * FROM features WHERE name=? AND id=?",
    sqlParametersStatus: "captured", sqlParameters: [parameter(2, "12", {type: "Long"}), parameter(1, "O'Reilly")]});
  const panel = deps.renderJdbc(document, boundary);
  await click(buttons(panel, "Copy SQL")[0]);
  await click(buttons(panel, "Copy SQL template")[0]);
  await click(buttons(panel, "Copy parameters")[0]);
  assert.deepEqual(copied, ["SELECT * FROM features WHERE name='O''Reilly' AND id=12", boundary.sql,
    "#1 · String: O'Reilly\n#2 · Long: 12"]);
  assert.equal(copied[0], classed(panel, "jdbc-resolved")[0].textContent);
  assert.equal(copied[1], classed(panel, "jdbc-template")[0].textContent);
  assert.match(panel.textContent, /reconstructed display, not the exact SQL sent over the wire/);
  for (const summary of tags(panel, "summary")) assert.equal(buttons(summary).length, 0);
  assert.equal(classed(panel, "evidence-actions").length, 2);
});

test("a prepared template without values has a template copy action, not invented values", async () => {
  const {document, copied} = fixture(), boundary = jdbc({sqlSource: "prepared", sql: "select ?"});
  const panel = deps.renderJdbc(document, boundary);
  assert.equal(buttons(panel).length, 1);
  await click(buttons(panel, "Copy SQL template")[0]);
  assert.deepEqual(copied, ["select ?"]);
});

test("parameter copy mirrors visible NULL, empty, unsupported and incomplete values", async () => {
  const {document, copied} = fixture();
  const panel = deps.renderJdbc(document, jdbc({sqlSource: "prepared", sql: "select ?, ?, ?, ?, ?, ?",
    sqlParametersStatus: "captured", sqlParameters: [parameter(1, null, {type: "Null", status: "null"}),
      parameter(2, ""), parameter(3, null, {type: "Object", status: "unsupported"}),
      parameter(4, "part", {status: "truncated"}), parameter(5, "a\nline\tvalue"), parameter(6, "hidden\u202etext")]}));
  await click(buttons(panel, "Copy parameters")[0]);
  assert.deepEqual(copied, ["#1 · Null: NULL\n#2 · String: \"\"\n#3 · Object: Not captured (unsupported value type)\n"
    + "#4 · String: part … [incomplete]\n#5 · String: a\nline\tvalue\n#6 · String: hidden text … [incomplete]"]);
  const list = classed(panel, "jdbc-parameters")[0];
  const visible = tags(list, "dt").map((label, index) => `${label.textContent}: ${tags(list, "dd")[index].textContent}`).join("\n");
  assert.equal(copied[0], visible);
  assert.doesNotMatch(copied[0], /\u202e|"status"|"index"/);
});

test("batch entries preserve individual SQL previews and parameter copy actions", async () => {
  const {document, copied} = fixture();
  const panel = deps.renderJdbc(document, jdbc({sqlSource: "prepared", operation: "executeBatch", sql: "INSERT INTO features(name) VALUES (?)",
    sqlParametersStatus: "batch", sqlParameterSets: [[parameter(1, "first")], [parameter(1, "second")]]}));
  const groups = classed(panel, "jdbc-parameter-set");
  assert.equal(groups.length, 2);
  for (const group of groups) {
    await click(buttons(group, "Copy SQL")[0]);
    await click(buttons(group, "Copy parameters")[0]);
    assert.equal(buttons(tags(group, "summary")[0]).length, 0);
  }
  await click(buttons(panel, "Copy SQL template")[0]);
  assert.deepEqual(copied, ["INSERT INTO features(name) VALUES ('first')", "#1 · String: first",
    "INSERT INTO features(name) VALUES ('second')", "#1 · String: second", "INSERT INTO features(name) VALUES (?)"]);
});

test("native ClickHouse copies the recorded query unchanged and named values separately", async () => {
  const {document, copied} = fixture(), boundary = native({sqlParametersStatus: "captured", sqlParameters: [
    {name: "site", type: "String", status: "captured", value: "<script>local</script>"},
    {name: "limit", type: "Int", status: "captured", value: "10"}]});
  const panel = deps.renderJdbc(document, boundary);
  await click(buttons(panel, "Copy SQL")[0]);
  await click(buttons(panel, "Copy parameters")[0]);
  assert.deepEqual(copied, [boundary.sql, "limit · Int: 10\nsite · String: <script>local</script>"]);
  assert.equal(buttons(panel, "Copy SQL template").length, 0);
  assert.equal(tags(panel, "script").length, 0);
  assert.doesNotMatch(panel.textContent, /SQL with captured values/);
});

test("SQL copy uses the normalized bounded display, not the unvalidated source", async () => {
  const {document, copied} = fixture();
  const raw = "SELECT '\u0000\u202e" + "x".repeat(17000);
  const panel = deps.renderJdbc(document, jdbc({sql: raw}));
  await click(buttons(panel, "Copy SQL")[0]);
  assert.equal(copied[0], tags(panel, "pre")[0].textContent);
  assert.equal(copied[0].length, 16384);
  assert.doesNotMatch(copied[0], /\u0000|\u202e/);
  assert.match(panel.textContent, /SQL text truncated or normalized/);
});

test("no actions are offered for absent SQL, empty parameters, invalid bindings or capture limits", () => {
  const {document, copied} = fixture();
  for (const boundary of [jdbc({sql: null}), jdbc({sql: ""}), jdbc({sql: null, sqlParametersStatus: "captured", sqlParameters: []}),
    jdbc({sql: null, sqlParametersStatus: "captured", sqlParameters: [{index: 1, type: "Secret", value: "private", status: "captured"}]}),
    jdbc({evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 3}),
    native({evidenceKind: "clickhouse-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 3})]) {
    assert.equal(buttons(deps.renderJdbc(document, boundary)).length, 0);
  }
  const empty = deps.renderJdbc(document, jdbc({sqlParametersStatus: "captured", sqlParameters: []}));
  assert.equal(buttons(empty, "Copy parameters").length, 0);
  assert.equal(deps.renderJdbc(document, jdbc({evidenceKind: "client-boundary"})), null);
  assert.deepEqual(copied, []);
});

test("standalone browser embeds without the shared helper retain readable evidence", () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/dependency-ui.js"), "utf8"), context);
  const {document} = fixture(), panel = context.TraceLensDependencies.renderJdbc(document, jdbc());
  assert.match(panel.textContent, /Recorded SQL/);
  assert.equal(tags(panel, "pre")[0].textContent, jdbc().sql);
  assert.equal(buttons(panel).length, 0);
});
