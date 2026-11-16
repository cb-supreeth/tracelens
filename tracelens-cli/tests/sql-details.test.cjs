const test = require("node:test");
const assert = require("node:assert/strict");
const deps = require("../tracelens_cli/web/dependency-ui.js");
const jdbc = extra => ({boundaryKind: "database", system: "postgresql", direction: "outbound", operation: "executeQuery",
  status: "OBSERVED", evidenceKind: "jdbc-execution", sql: "select * from features where id = ?", sqlSource: "prepared", ...extra});
const entry = (index, value = "value", extra = {}) => ({index, type: "String", value, status: "captured", ...extra});

test("query previews name operations and primary tables rather than long selected column lists", () => {
  const cases = [
    ["select f.a,f.b,f.c from ems.features_v2 f where f.id=?", "SELECT · ems.features_v2"],
    ["select case when exists(select 1 from nested_table) then ? end from primary_table", "SELECT · primary_table"],
    ["select * from features f join entitlements e on f.id=e.feature_id", "SELECT · features + entitlements"],
    ['SELECT * FROM "ems"."Features"', 'SELECT · "ems"."Features"'],
    ["insert into features(id,name) values (?,?)", "INSERT · features"],
    ["update subscriptions set status=? where id=?", "UPDATE · subscriptions"],
    ["delete from entitlements where id=?", "DELETE · entitlements"],
    ["with rows as (select * from hidden) select * from rows", "SELECT · rows"],
    ["select set_config(?,?,?)", "SELECT · set_config()"],
    ["reset chargebee.tenant_id", "RESET · chargebee.tenant_id"],
    ["SET LOCAL chargebee.tenant_id = ?", "SET · chargebee.tenant_id"],
    ["update only subscriptions set status=?", "UPDATE · subscriptions"],
    ["UPDATE LOW_PRIORITY IGNORE features SET name=?", "UPDATE · features"],
    ["EXPLAIN DELETE FROM features", "EXPLAIN · expand for details"],
    ["CREATE VIEW test AS SELECT * FROM features", "SQL statement · expand for details"],
    ["truncate table temp_features", "TRUNCATE · temp_features"],
    ["select 1", "SELECT · expression / derived result"],
    ["/* SELECT * FROM false_table */ select 'FROM false_table' from actual_table", "SELECT · actual_table"],
    ["-- DELETE FROM false_table\n select $$from false_table$$ from actual_table", "SELECT · actual_table"],
    ["select $text$ from false_table $text$ from actual_table", "SELECT · actual_table"],
    ["/* outer /* nested */ comment */ select * from actual_table", "SELECT · actual_table"],
  ];
  for (const [sql, expected] of cases) assert.equal(deps.sqlPreview(jdbc({sql})), expected, sql);
  assert.equal(deps.sqlPreview(jdbc({sql: "select * from `site`.`features`", system: "mysql"})), "SELECT · `site`.`features`");
  assert.equal(deps.sqlPreview(jdbc({sql: null})), "SQL text unavailable");
  assert.equal(deps.sqlPreview(jdbc({sqlSource: "batch", batchSize: 4})), "SQL batch · 4 entries · template inventory");
});

test("parameter projections accept bounded typed entries from events and canonical arrays without mutation", () => {
  const values = [entry(3, null, {type: "Null", status: "null"}), entry(1, "<script>x</script>"), entry(2, "123", {type: "Int"})];
  for (const raw of [values, JSON.stringify(values)]) {
    const input = jdbc({sqlParameters: raw, sqlParametersStatus: "captured", sqlParametersTruncated: "false"}), before = JSON.stringify(input);
    const output = deps.jdbcDetails(input);
    assert.deepEqual(output.sqlParameters.map(item => item.index), [1, 2, 3]);
    assert.equal(output.sqlParameters[0].value, "<script>x</script>");
    assert.equal(output.sqlParameters[2].value, null); assert.equal(output.sqlParametersTruncated, false);
    assert.equal(JSON.stringify(input), before);
    assert.equal(deps.sqlPreview(input), "SELECT · features · 3 parameter bindings");
  }
});

test("partial and batch parameters retain their uncertainty and never masquerade as one final binding set", () => {
  const partial = deps.jdbcDetails(jdbc({sqlParameters: [entry(2)], sqlParametersStatus: "unavailable"}));
  assert.equal(partial.sqlParameters.length, 1); assert.equal(partial.sqlParametersStatus, "unavailable");
  const batch = deps.jdbcDetails(jdbc({operation: "executeBatch", sqlParameterSets: JSON.stringify([[entry(1, "first")], [entry(1, "second")]]),
    sqlParametersStatus: "batch", sqlParameterSetsOmitted: "3", sqlParametersTruncated: true}));
  assert.deepEqual(batch.sqlParameterSets.map(set => set[0].value), ["first", "second"]);
  assert.equal(batch.sqlParameters, undefined); assert.equal(batch.sqlParameterSetsOmitted, 3);
  assert.equal(batch.sqlParametersTruncated, true);
  const partialBatch = deps.jdbcDetails(jdbc({operation: "executeBatch", sqlParameterSets: [[entry(2)]], sqlParametersStatus: "unavailable"}));
  assert.equal(partialBatch.sqlParameterSets.length, 1); assert.equal(partialBatch.sqlParametersStatus, "unavailable");
  assert.equal(deps.jdbcDetails(jdbc({sqlParameters: [entry(1, "prefix", {status: "truncated"})], sqlParametersStatus: "captured"})).sqlParametersTruncated, true);
  assert.equal(deps.jdbcDetails(jdbc({sqlParameters: [entry(1, "a\u202eb\u001bc")], sqlParametersStatus: "captured"})).sqlParameters[0].value, "a b c");
});

test("malformed, ambiguous or excessive parameters fail closed with visible incomplete evidence", () => {
  for (const extra of [
    {sqlParameters: "not json"}, {sqlParameters: {}}, {sqlParameters: [entry(0)]}, {sqlParameters: [entry(129)]},
    {sqlParameters: [entry(1), entry(1)]}, {sqlParameters: [entry(1, {})]}, {sqlParameters: [entry(1, "secret", {status: "null"})]},
    {sqlParameters: [entry(1, "x".repeat(513))]}, {sqlParameters: [entry(1, "secret", {type: "Unexpected"})]},
    {sqlParameters: [entry(1)], sqlParameterSets: [[entry(1)]]},
    {sqlParameterSets: Array.from({length: 11}, () => [entry(1)]), sqlParametersStatus: "batch"},
    {sqlParameters: Array.from({length: 128}, (_, index) => entry(index + 1, "x".repeat(512)))},
    {sqlParameters: [entry(1)], sqlParametersStatus: "not-applicable"},
    {sqlParameters: [entry(1)], sqlParametersStatus: "unknown"}, {sqlParameters: null},
  ]) {
    const output = deps.jdbcDetails(jdbc({sqlParametersStatus: "captured", ...extra}));
    assert.equal(output.sqlParameters, undefined); assert.equal(output.sqlParameterSets, undefined);
    assert.equal(output.sqlParametersStatus, "unavailable"); assert.equal(output.sqlParametersTruncated, true);
  }
});

test("binding values are never exposed on other boundaries, quota markers or compact summaries", () => {
  const input = jdbc({sqlParametersStatus: "captured", sqlParameters: [entry(1, "PRIVATE_BINDING")]});
  for (const extra of [{evidenceKind: "client-boundary"}, {system: "other"}, {boundaryKind: "cache"},
    {evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 10}]) {
    assert.doesNotMatch(JSON.stringify(deps.boundaryMetadata({type: "EXTERNAL_CALL", attributes: {...input, ...extra}})), /PRIVATE_BINDING/);
  }
  assert.doesNotMatch(JSON.stringify(deps.summaryBoundary(input)), /PRIVATE_BINDING/);
  assert.doesNotMatch(JSON.stringify(deps.collect({events: [{type: "EXTERNAL_CALL", attributes: input}]})), /PRIVATE_BINDING/);
});
