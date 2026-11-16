const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const {reconstruct} = require("../tracelens_cli/web/sql-values.js");

const entry = (index, value, type = "String", status = "captured") => ({index, type, value, status});
const info = (sql, values, extra = {}) => ({sql, sqlSource: "prepared", sqlTruncated: false,
  sqlParameters: values, sqlParametersStatus: "captured", sqlParametersTruncated: false, ...extra});
const resolve = (sql, values, system = "postgresql", extra = {}) => reconstruct(info(sql, values, extra), system);
const unchanged = (sql, values, system = "postgresql", extra = {}) => {
  const output = resolve(sql, values, system, extra);
  assert.equal(output.sql, sql); assert.equal(output.substituted, 0); assert.equal(output.complete, false);
  assert.ok(output.reason); return output;
};

test("reconstructs a prepared statement with typed diagnostic literals without changing evidence", () => {
  const input = info("SELECT * FROM items WHERE id=? AND name=? AND enabled=? AND deleted_at IS ?", [
    entry(4, null, "Timestamp", "null"), entry(2, "O'Reilly"), entry(3, "true", "Boolean"), entry(1, "9223372036854775807", "Long"),
  ]);
  const before = JSON.stringify(input);
  input.sqlParameters.forEach(Object.freeze); Object.freeze(input.sqlParameters); Object.freeze(input);
  assert.deepEqual(reconstruct(input, "postgresql"), {
    sql: "SELECT * FROM items WHERE id=9223372036854775807 AND name='O''Reilly' AND enabled=TRUE AND deleted_at IS NULL",
    substituted: 4, unresolved: 0, complete: true,
  });
  assert.equal(JSON.stringify(input), before);
});

test("quoted values, identifiers, comments and PostgreSQL dollar literals are never placeholders", () => {
  const cases = [
    "SELECT '?', 'it''s ?', \"question?\", \"double\"\"?\", ?",
    "SELECT -- ?\n/* ? */ ?",
    "SELECT -- ?\r?",
    "SELECT /* outer ? /* inner ? */ outer ? */ ?",
    "SELECT $$ ? ' \" $$, $tag$ ? $other$ $tag$, ?",
    "SELECT $тег$ ? $тег$, ?",
    "SELECT E'it\\'s ?', ?",
    "SELECT E'\\\\?', ?",
    "SELECT U&'\\003F', ?",
    "SELECT \"a\\\" , ?",
  ];
  for (const sql of cases) {
    const result = resolve(sql, [entry(1, "ok")]);
    assert.equal(result.sql, sql.slice(0, sql.lastIndexOf("?")) + "'ok'" + sql.slice(sql.lastIndexOf("?") + 1), sql);
    assert.equal(result.complete, true, sql);
  }
  assert.equal(resolve("SELECT ? /* ? */", [entry(1, "ok")]).sql, "SELECT 'ok' /* ? */");
});

test("MySQL recognizes quoted identifiers, hash comments, and whitespace-sensitive -- comments", () => {
  for (const sql of ["SELECT `what?`, `double``?`, '?', ?", "SELECT # ?\n?", "SELECT -- ?\n?"]) {
    const result = resolve(sql, [entry(1, "7", "Int")], "mysql");
    assert.equal(result.sql, sql.slice(0, sql.lastIndexOf("?")) + "7");
    assert.equal(result.complete, true);
  }
  assert.equal(resolve("SELECT 1--?", [entry(1, "7", "Int")], "mysql").sql, "SELECT 1--7");
  unchanged("SELECT /*!80000 ? */ ?", [entry(1, "a"), entry(2, "b")], "mysql");
});

test("session-dependent escaped quotes fail closed instead of shifting values", () => {
  for (const system of ["mysql", "postgresql"]) {
    unchanged("SELECT 'it\\'s ?', ?", [entry(1, "private")], system);
    unchanged("SELECT 'a\\'? + '\\' ?', ?", [entry(1, "private")], system);
  }
  unchanged('SELECT "it\\"s ?", ?', [entry(1, "private")], "mysql");
});

test("unterminated syntax and truncated SQL retain the original template", () => {
  for (const sql of ["SELECT '?, ?", 'SELECT "?, ?', "SELECT /* ?", "SELECT $tag$ ?", "SELECT $$ ?", "SELECT `?` , ?"]) {
    unchanged(sql, [entry(1, "private")]);
  }
  unchanged("SELECT ?", [entry(1, "private")], "postgresql", {sqlTruncated: true});
});

test("preserves PostgreSQL operators and JDBC escapes while replacing distinct bind markers", () => {
  for (const sql of [
    "SELECT payload ?? 'key' FROM records WHERE id=?",
    "SELECT payload ?| array['a?'] FROM records WHERE id=?",
    "SELECT payload ?& array['a?'] FROM records WHERE id=?",
    "SELECT payload ??| array['a?'] FROM records WHERE id=?",
    "SELECT payload @? '$.a ? (@ > 2)' FROM records WHERE id=?",
    "SELECT payload ?? ?",
    "SELECT payload ?| ?",
  ]) {
    const result = resolve(sql, [entry(1, "8", "Int")]);
    assert.equal(result.sql, sql.slice(0, -1) + "8", sql);
    assert.equal(result.complete, true, sql);
  }
  for (const sql of ["SELECT payload ? 'key' FROM records WHERE id=?", "SELECT payload ? ?", "SELECT (payload) ? ?", "SELECT 'text' ? ?", "SELECT ? ? ?"]) {
    assert.match(unchanged(sql, [entry(1, "private")]).reason, /operator/);
  }
});

test("normal expression positions remain reconstructable", () => {
  for (const sql of ["SELECT ?", "SELECT DISTINCT ?", "SELECT ?::text", "SELECT ? + 1", "SELECT f(?)", "SELECT * FROM t WHERE ?",
    "INSERT INTO t(a) VALUES (?)", "UPDATE t SET a=?", "SELECT * FROM t LIMIT ?", "SELECT * FROM t OFFSET ?",
    "SELECT CASE WHEN ? THEN 1 ELSE 0 END", "SELECT x IS ?", "SELECT ? #>> '{x}'"]) {
    assert.equal(resolve(sql, [entry(1, "9", "Int")]).complete, true, sql);
  }
});

test("numeric values are validated, never rounded or coerced into executable text", () => {
  for (const [type, value] of [["Byte", "-128"], ["Short", "32767"], ["Int", "2147483647"], ["Long", "-9223372036854775808"],
    ["Float", "1.2E-4"], ["Double", "-0.0"], ["BigDecimal", "12345678901234567890.123456789"], ["BigDecimal", "1E+3000"]]) {
    assert.equal(resolve("SELECT ?", [entry(1, value, type)]).sql, "SELECT " + value);
  }
  for (const [type, value] of [["Byte", "128"], ["Int", "2147483648"], ["Long", "9223372036854775808"], ["Int", "1.0"],
    ["Int", "1; DROP TABLE x"], ["Int", " 1"], ["Int", "0x1"], ["Double", "NaN"], ["Double", "Infinity"],
    ["Double", "1e400"], ["BigDecimal", "1.0 OR TRUE"], ["Boolean", "TRUE"], ["Null", "NULL"]]) {
    unchanged("SELECT ?", [entry(1, value, type)]);
  }
});

test("known string and temporal setters are quoted while setObject types remain ambiguous", () => {
  for (const type of ["String", "NString", "Date", "Time", "Timestamp"]) {
    assert.equal(resolve("SELECT ?", [entry(1, "2026-10-22'", type)]).sql, "SELECT '2026-10-22'''" );
  }
  assert.equal(resolve("SELECT ?", [entry(1, "")]).sql, "SELECT ''");
  assert.equal(resolve("SELECT ?", [entry(1, "false", "Boolean")]).sql, "SELECT FALSE");
  assert.equal(resolve("SELECT ?", [entry(1, "C:\\folder\\O'Reilly")]).sql, "SELECT E'C:\\\\folder\\\\O''Reilly'");
  assert.match(unchanged("SELECT ?", [entry(1, "C:\\folder")], "mysql").reason, /SQL mode/);
  for (const value of ["123", "true", "2026-10-22", "example"]) {
    assert.match(unchanged("SELECT ?", [entry(1, value, "Object")]).reason, /setObject runtime types/);
  }
  assert.equal(resolve("SELECT ?", [entry(1, null, "Object", "null")]).sql, "SELECT NULL");
  unchanged("SELECT ?", [entry(1, "a\u0000b")]);
});

test("missing, truncated and unsupported values stay at their own indices", () => {
  const result = resolve("SELECT ?, ?, ?, ?, ?", [entry(2, "second"), entry(3, "prefix", "String", "truncated"),
    entry(4, null, "Bytes", "unsupported"), entry(5, null, "Null", "null")]);
  assert.equal(result.sql, "SELECT ?, 'second', ?, ?, NULL");
  assert.equal(result.substituted, 2); assert.equal(result.unresolved, 3); assert.equal(result.complete, false);
  assert.match(result.reason, /Missing/); assert.match(result.reason, /Truncated/); assert.match(result.reason, /Unsupported/);
  for (const extra of [{sqlParametersStatus: "unavailable"}, {sqlParametersTruncated: true}]) {
    const partial = resolve("SELECT ?", [entry(1, "visible")], "postgresql", extra);
    assert.equal(partial.sql, "SELECT 'visible'"); assert.equal(partial.complete, false); assert.ok(partial.reason);
  }
});

test("malformed indices, inconsistent values and count overruns retain the whole template", () => {
  for (const entries of [[entry(0, "x")], [entry(2, "x")], [entry(1, "a"), entry(1, "b")], [entry(1.5, "x")],
    [entry(1, "x", "Unknown")], [entry(1, {}, "String")], [entry(1, null)], [entry(1, "x", "String", "null")],
    [entry(1, "x", "String", "bad")], [entry(1, "x".repeat(513))], [null], {}, "[]"]) {
    unchanged("SELECT ?", entries);
  }
  unchanged("SELECT '?', 1", [entry(1, "x")]);
  assert.equal(resolve("SELECT 1", []).complete, true);
  unchanged("SELECT ?", undefined);
  unchanged("SELECT ?", [entry(1, "x")], "postgresql", {sqlParametersStatus: "not-applicable"});
});

test("batch reconstruction requires a prepared source and an explicit, separate entry", () => {
  const sets = [[entry(1, "first")], [entry(1, "second")]];
  const input = info("SELECT ?", undefined, {sqlParameterSets: sets, sqlParametersStatus: "batch"});
  assert.match(reconstruct(input, "postgresql").reason, /Select/);
  assert.equal(reconstruct(input, "postgresql", sets[0]).sql, "SELECT 'first'");
  assert.equal(reconstruct(input, "postgresql", sets[1]).sql, "SELECT 'second'");
  for (const sqlSource of ["batch", "statement"]) {
    const output = reconstruct({...input, sqlSource}, "postgresql", sets[0]);
    assert.equal(output.sql, "SELECT ?"); assert.equal(output.complete, false); assert.ok(output.reason);
  }
  const omitted = reconstruct({...input, sqlParameterSetsOmitted: 2, sqlParametersTruncated: true}, "postgresql", sets[0]);
  assert.equal(omitted.sql, "SELECT 'first'"); assert.equal(omitted.complete, false); assert.ok(omitted.reason);
});

test("input, parameter and output limits bound work and never display a chopped replacement", () => {
  const giant = resolve("SELECT " + "?".repeat(20000), [entry(1, "x")]);
  assert.ok(giant.sql.length <= 16384); assert.equal(giant.substituted, 0); assert.ok(giant.reason);
  const sql = "SELECT " + Array(128).fill("?").join(", ");
  const output = resolve(sql, Array.from({length: 128}, (_, index) => entry(index + 1, "'".repeat(512))));
  assert.equal(output.sql, sql); assert.equal(output.substituted, 0); assert.match(output.reason, /display limit/);
  const excessive = "SELECT " + Array(129).fill("?").join(", ");
  unchanged(excessive, []);
  const unknown = reconstruct({sql: "SELECT ?", sqlSource: "prepared"}, "unknown");
  assert.equal(unknown.complete, false); assert.ok(unknown.reason);
  assert.ok(reconstruct(null, "postgresql").reason);
});

test("module exposes the same pure API in a browser without any execution or DOM dependency", () => {
  const source = fs.readFileSync(require.resolve("../tracelens_cli/web/sql-values.js"), "utf8");
  const context = vm.createContext({}); vm.runInContext(source, context);
  assert.equal(typeof context.TraceLensSqlValues.reconstruct, "function");
  assert.equal(context.TraceLensSqlValues.reconstruct(info("SELECT ?", [entry(1, "<script>not HTML</script>")]), "postgresql").sql,
    "SELECT '<script>not HTML</script>'");
});
