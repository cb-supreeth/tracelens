"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const large = require("../tracelens_cli/web/large-trace.js");
const identity = {segmentId: "segment", traceId: "trace", service: "cb-app", serviceInstanceId: "instance"};
const id = "01234567-89ab-cdef-0123-456789abcdef";

test("large traces retain only one count/byte-bounded page and preserve raw event coordinates", async () => {
  const calls = [];
  const source = large.create(async (path, method, cap) => {
    calls.push({path, method, cap});
    if (path.includes("overview")) return {...identity, through: 200000};
    const query = new URLSearchParams(path.split("?")[1]), after = Number(query.get("after"));
    return {identity, after, through: 200000, nextAfter: after + 500, hasMore: true,
      events: Array.from({length: 500}, (_, i) => ({eventIndex: after + i, seq: after + i + 1}))};
  }, identity);
  await source.overview();
  await source.show(); const first = source.page;
  await source.show({after: first.nextAfter}, "next");
  assert.equal(source.page.events.length, 500); assert.notEqual(source.page, first);
  assert.equal(source.page.events[0].eventIndex, 500);
  assert.deepEqual(source.history, [0]);
  assert.ok(calls.slice(1).every(call => call.path.includes("limit=500") && call.path.includes("maxBytes=262144")
    && call.path.includes("through=200000") && call.method === "GET" && call.cap <= 327680));
  await source.show({after: source.history.at(-1)}, "previous");
  assert.equal(source.history.length, 0); assert.equal(source.page.events[0].eventIndex, 0);
});

test("overview discovery binds later pages to the exact returned service instance", async () => {
  const calls = [], source = large.create(async path => {
    calls.push(path);
    return path.includes("overview") ? {...identity, through: 10} : {identity, through: 10, events: []};
  }, {segmentId: identity.segmentId});
  await source.overview(); await source.events();
  assert.match(calls[1], /service=cb-app/); assert.match(calls[1], /instance=instance/);
});

test("saved detail failures never fall back to live and archived identities stay exact", async () => {
  const calls = [], source = large.create(async path => { calls.push(path); throw new Error("Saved chunk unavailable"); }, identity, id);
  for (const action of [() => source.overview(), () => source.requestData(), () => source.execution(), () => source.events()])
    await assert.rejects(action, /Saved chunk unavailable/);
  assert.equal(calls.length, 4);
  assert.ok(calls.every(path => path.startsWith(`/api/snapshots/${id}/segments/segment/`) && path.includes("instance=instance")));
});

test("mismatched identities watermarks and oversized count pages fail closed", async () => {
  for (const wrong of [{identity: {...identity, serviceInstanceId: "restarted"}, through: 9, events: []},
    {identity, through: 10, events: []}, {identity, through: 9, events: Array(501).fill({seq: 1})}]) {
    const source = large.create(async path => path.includes("overview") ? {...identity, through: 9} : wrong, identity);
    await source.overview(); await assert.rejects(source.show()); assert.equal(source.page, null);
  }
});

test("late page requests cannot overwrite newer filters and caller navigation sends exact sequence", async () => {
  let finish;
  const calls = [], source = large.create(async path => {
    calls.push(path);
    if (path.includes("overview")) return {...identity, through: 10000};
    if (path.includes("kind=boundaries")) return {identity, through: 10000, events: [{seq: 9000, eventIndex: 8999}], nextAfter: 9000};
    return new Promise(resolve => { finish = () => resolve({identity, through: 10000, events: [], nextAfter: 1}); });
  }, identity);
  await source.overview(); const old = source.show();
  await source.show({kind: "boundaries", aroundSeq: 9000}); finish(); await old;
  assert.equal(source.page.events[0].eventIndex, 8999); assert.equal(source.kind, "boundaries");
  assert.ok(calls.at(-1).includes("aroundSeq=9000"));
});

test("full SQL is read only from its exact retained event, never a neighboring query", async () => {
  let match = false;
  const source = large.create(async path => {
    assert.ok(path.includes("after=8999") && path.includes("limit=1"));
    return {identity, through: 10000, events: [{type: "EXTERNAL_CALL", seq: match ? 9000 : 9001, attributes: {sql: "select full_query"}}]};
  }, identity);
  await assert.rejects(source.boundary(9000), /exact SQL event/);
  match = true; assert.equal((await source.boundary(9000)).attributes.sql, "select full_query");
});

test("caller page byte limits cannot silently exclude the requested anchor", async () => {
  const calls = [], source = large.create(async path => {
    calls.push(path);
    return {identity, through: 10000, events: [{seq: path.includes("aroundSeq") ? 8901 : 9000, eventIndex: 8999}], hasMore: true, nextAfter: 9000};
  }, identity);
  await source.show({aroundSeq: 9000});
  assert.equal(calls.length, 2); assert.ok(calls[1].includes("after=8999"));
  assert.equal(source.page.events[0].seq, 9000);
});

function sqlGraph() {
  const owner = {...identity, kind: "segment", id: "owner", segmentKey: "owner-key"};
  const node = {...identity, id: "sql", segmentKey: owner.segmentKey, kind: "dependency-call", eventSeq: 9000,
    boundaryKind: "database", system: "postgresql", direction: "outbound", destination: "database", operation: "executeQuery", status: "OBSERVED",
    evidenceKind: "jdbc-execution", sqlDetailsAvailable: true, sqlPreview: "SELECT compact preview…", sqlSource: "prepared"};
  return {traceId: identity.traceId, nodes: [owner, node], edges: []};
}

test("duplicate SQL sequences are marked partial without reading or analyzing previews", async () => {
  const graph = sqlGraph(); graph.nodes.push({...graph.nodes[1], id: "duplicate"});
  const result = await large.hydrateSql(() => assert.fail("Ambiguous sequence must not load detail"), graph);
  assert.equal(result.partial, true); assert.match(result.warnings.join(" "), /sequence is ambiguous/);
  assert.ok(result.nodes.every(node => node.sql === undefined));
});

test("explicit analysis hydrates exact bounded SQL candidates without retaining raw pages or parameters", async () => {
  const graph = sqlGraph(), before = JSON.stringify(graph), calls = [];
  const result = await large.hydrateSql(async path => {
    calls.push(path);
    if (path.includes("overview")) return {...identity, through: 10000};
    assert.match(path, /kind=boundaries/); assert.match(path, /maxBytes=262144/);
    return {identity, through: 10000, nextAfter: 10000, hasMore: false, events: [{type: "EXTERNAL_CALL", seq: 9000,
      attributes: {...graph.nodes[1], sql: "SELECT full_template WHERE id = ?", sqlParameters: "private-binding", sqlTruncated: "false"}}]};
  }, graph, id);
  assert.equal(calls.length, 2); assert.ok(calls.every(path => path.startsWith(`/api/snapshots/${id}/segments/segment/`)));
  assert.equal(result.nodes[1].sql, "SELECT full_template WHERE id = ?");
  assert.equal(result.nodes[1].sqlParameters, undefined); assert.equal(result.events, undefined);
  assert.equal(result.partial, false); assert.equal(JSON.stringify(graph), before);
});

test("analysis respects its page budget and never fingerprints missing or ambiguous SQL previews", async () => {
  const graph = sqlGraph(); let reads = 0;
  const result = await large.hydrateSql(async path => {
    if (path.includes("overview")) return {...identity, through: 10000};
    reads++; const after = Number(new URLSearchParams(path.split("?")[1]).get("after"));
    return {identity, through: 10000, events: [], nextAfter: after + 1, hasMore: true};
  }, graph);
  assert.equal(reads, 16); assert.equal(result.nodes[1].sql, undefined);
  assert.equal(result.partial, true); assert.match(result.warnings.join(" "), /16-page.*not fingerprinted/);
  graph.nodes.push({...graph.nodes[0]});
  const ambiguous = await large.hydrateSql(() => assert.fail("Ambiguous owner must not load detail"), graph);
  assert.equal(ambiguous.partial, true); assert.match(ambiguous.warnings.join(" "), /ambiguous/);
});

test("analysis cancellation stops subsequent page reads", async () => {
  let current = true, reads = 0;
  await assert.rejects(large.hydrateSql(async path => {
    reads++; current = false; return {...identity, through: 10000};
  }, sqlGraph(), undefined, () => current), /cancelled/);
  assert.equal(reads, 1);
});
