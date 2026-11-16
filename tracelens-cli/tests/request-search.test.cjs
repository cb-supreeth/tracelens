const test = require("node:test");
const assert = require("node:assert/strict");
const {create} = require("../tracelens_cli/web/request-search.js");
const segment = (id, extra = {}) => ({service: "cb-app", serviceInstanceId: "app-instance", segmentId: id, traceId: `trace-${id}`, ...extra});
const request = (key, segments) => ({key, segments});
function response(segments, matches = segments, overrides = {}) {
  return {schemaVersion: 1, kind: "request-data-search", matches: matches.map(item => ({...item, fields: ["query"]})),
    counts: {requested: segments.length, scanned: segments.length, withData: segments.length, matched: matches.length, unavailable: 0, unsearched: 0},
    limited: false, ...overrides};
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => {resolve = yes; reject = no;}); return {promise, resolve, reject}; }
const tick = () => new Promise(resolve => setImmediate(resolve));

test("only explicit search reads captured data, preserving the trimmed literal keyword", async () => {
  const rows = [request("one", [segment("a")])], calls = [];
  const controller = create(async (...args) => {calls.push(args); return response(args[3].segments);});
  assert.equal(controller.state.status, "idle");
  assert.deepEqual(controller.filter(rows), rows); assert.equal(controller.isStale(rows), false);
  assert.equal(calls.length, 0);
  const result = await controller.search("  MiXeD.*[literal]  ", rows);
  assert.equal(result.status, "ready"); assert.equal(result.keyword, "MiXeD.*[literal]");
  assert.deepEqual(calls, [["/api/request-search", "POST", undefined, {keyword: "MiXeD.*[literal]", segments: rows[0].segments}]]);
  for (let i = 0; i < 10; i++) { controller.filter(rows); controller.isStale(rows); }
  assert.equal(calls.length, 1, "polling never fetches request bodies");
});

test("a downstream match retains its entire request, with no fetched payload retained in state", async () => {
  const app = segment("a"), ems = segment("e", {service: "cb-ems", serviceInstanceId: "ems-instance"});
  const rows = [request("linked", [app, ems]), request("other", [segment("b")])];
  const controller = create(async (_path, _method, _query, body) => response(body.segments, [ems]));
  await controller.search("customer", rows);
  assert.deepEqual(controller.filter(rows), [rows[0]]);
  assert.equal(controller.filter(rows)[0], rows[0]);
  assert.deepEqual([...controller.state.requestKeys], ["linked"]);
  assert.equal(controller.state.counts.matched, 1);
  assert.equal("matches" in controller.state, false);
  assert.equal("segments" in controller.state, false);
});

test("reused segment IDs, instances, trace IDs and request keys never inherit a match", async () => {
  const original = segment("a"), row = request("request-a", [original]);
  const controller = create(async (_p, _m, _q, body) => response(body.segments));
  await controller.search("value", [row]);
  for (const replacement of [segment("a", {service: "cb-ems"}), segment("a", {serviceInstanceId: "new-instance"}),
    segment("a", {traceId: "new-trace"}), segment("different"), segment("a", {traceId: undefined})]) {
    assert.deepEqual(controller.filter([request(row.key, [replacement])]), []);
    assert.equal(controller.isStale([request(row.key, [replacement])]), true);
  }
  assert.deepEqual(controller.filter([request("different-request", [original])]), []);
  const state = controller.state; state.requestKeys.add("different-request"); state.counts.matched = 500;
  assert.equal(controller.state.counts.matched, 1);
  assert.deepEqual(controller.filter([request("different-request", [original])]), []);
});

test("candidate exact identities are deduplicated and capped at 200", async () => {
  const all = Array.from({length: 203}, (_, i) => segment(String(i))), calls = [];
  const rows = all.map((item, i) => request(`request-${i}`, [item, {...item}]));
  const controller = create(async (_p, _m, _q, body) => {calls.push(body); return response(body.segments, [body.segments[0]]);});
  await controller.search("needle", rows);
  assert.equal(calls[0].segments.length, 200);
  assert.deepEqual(calls[0].segments, all.slice(0, 200));
  assert.equal(controller.state.counts.requested, 203); assert.equal(controller.state.counts.unsearched, 3);
  assert.equal(controller.state.counts.scanned, 200); assert.equal(controller.state.limited, true);
  assert.deepEqual(controller.filter(rows), [rows[0]]);
  assert.equal(controller.isStale([...rows].reverse()), false, "candidate ordering does not make evidence stale");
  assert.equal(controller.isStale(rows.slice(0, 200)), true, "signature includes candidates beyond the scan cap");
});

test("missing or malformed segment identity never triggers a guessed lookup", async () => {
  const calls = [], controller = create(async (...args) => {calls.push(args); return response(args[3].segments);});
  const invalid = [null, {}, [], segment("a", {serviceInstanceId: ""}), segment("a", {service: 42}),
    segment("a", {segmentId: " "}), segment("a", {traceId: {id: "secret"}})];
  await controller.search("needle", [request("invalid", invalid), request("", [segment("a")])]);
  assert.equal(controller.state.status, "ready"); assert.equal(calls.length, 0);
  assert.equal(controller.state.counts.requested, 0); assert.deepEqual(controller.filter([request("invalid", invalid)]), []);
  await controller.search("needle", [request("legacy", [segment("legacy", {traceId: null})])]);
  assert.equal(calls.length, 1); assert.equal("traceId" in calls[0][3].segments[0], false);
  assert.equal(controller.state.status, "ready");
});

test("skipped identities are counted separately through loading, success and failure and cleared on reset", async () => {
  const missing = request("old", [segment("old", {serviceInstanceId: ""}), null]);
  const valid = request("new", [segment("new")]), gate = deferred();
  const controller = create(async () => gate.promise);
  const pending = controller.search("needle", [missing, missing, valid]);
  assert.equal(controller.state.skipped, 2, "duplicate request references do not inflate skipped coverage");
  assert.equal(controller.state.counts.requested, 1);
  await tick(); gate.resolve(response(valid.segments)); await pending;
  assert.equal(controller.state.skipped, 2); assert.equal(controller.state.status, "ready");
  assert.equal(controller.isStale([valid]), true, "changes in unsearchable records also invalidate coverage");
  controller.reset(); assert.equal(controller.state.skipped, 0);
  const failed = create(async () => {throw Error("unavailable");});
  await failed.search("needle", [missing, valid]);
  assert.equal(failed.state.status, "error"); assert.equal(failed.state.skipped, 2);
  failed.reset(); assert.equal(failed.state.skipped, 0);
});

test("reset before dispatch avoids reading request data at all", async () => {
  let calls = 0;
  const controller = create(async () => {calls++; throw Error("must not dispatch");});
  const pending = controller.search("needle", [request("one", [segment("a")])]);
  controller.reset(); await pending;
  assert.equal(calls, 0); assert.equal(controller.state.status, "idle");
});

test("invalid or unknown matches and inconsistent counts fail closed", async () => {
  const row = request("one", [segment("a")]);
  const malformed = [
    valid => ({...valid, schemaVersion: 2}), valid => ({...valid, kind: "other"}),
    valid => ({...valid, limited: "false"}), valid => ({...valid, matches: [{...segment("unknown"), fields: ["query"]}]}),
    valid => ({...valid, matches: [{...segment("a", {serviceInstanceId: "other"}), fields: ["body"]}]}),
    valid => ({...valid, matches: [{...segment("a", {traceId: "different"}), fields: ["query"]}]}),
    valid => ({...valid, matches: [{...segment("a"), fields: []}]}),
    valid => ({...valid, matches: [{...segment("a"), fields: ["password"]}]}),
    valid => ({...valid, matches: [{...segment("a"), fields: ["body", "body"]}]}),
    valid => ({...valid, counts: {...valid.counts, requested: 2}}),
    valid => ({...valid, counts: {...valid.counts, matched: 0}}),
    valid => ({...valid, counts: {...valid.counts, scanned: 0}}),
    valid => ({...valid, counts: {...valid.counts, unavailable: 1}}),
    valid => ({...valid, counts: {...valid.counts, withData: NaN}}),
    valid => ({...valid, counts: {...valid.counts, unsearched: -1}}),
  ];
  for (const corrupt of malformed) {
    const controller = create(async () => corrupt(response(row.segments)));
    await controller.search("synthetic-private-needle", [row]);
    assert.equal(controller.state.status, "error"); assert.deepEqual(controller.filter([row]), []);
    assert.doesNotMatch(controller.state.error, /synthetic-private-needle|password|different|unknown/);
  }
});

test("valid unavailable and unsearched counts remain distinct from captured requests with no data", async () => {
  const rows = Array.from({length: 5}, (_, i) => request(String(i), [segment(String(i))]));
  const controller = create(async (_p, _m, _q, body) => response(body.segments, [body.segments[0]], {
    counts: {requested: 5, scanned: 3, withData: 1, matched: 1, unavailable: 1, unsearched: 1}, limited: true}));
  await controller.search("needle", rows);
  assert.equal(controller.state.status, "ready"); assert.equal(controller.state.limited, true);
  assert.equal(controller.state.counts.scanned - controller.state.counts.withData, 2);
  assert.deepEqual(controller.filter(rows), [rows[0]]);
});

test("empty keywords reset, overly long keywords are rejected locally, and failure is generic", async () => {
  let calls = 0;
  const controller = create(() => {calls++; throw Error("private payload: SECRET");});
  const rows = [request("one", [segment("a")])];
  await controller.search("x".repeat(257), rows);
  assert.equal(calls, 0); assert.equal(controller.state.status, "error"); assert.deepEqual(controller.filter(rows), []);
  await controller.search(" \n ", rows);
  assert.equal(controller.state.status, "idle"); assert.deepEqual(controller.filter(rows), rows);
  await controller.search("first", rows);
  assert.equal(calls, 1); assert.equal(controller.state.status, "error"); assert.doesNotMatch(controller.state.error, /SECRET|payload|first/);
  await controller.search("retry", rows);
  assert.equal(calls, 2, "a synchronous API failure does not leave search permanently pending");
});

test("concurrent searches dispatch once and reset discards late results without applying old matches", async () => {
  const gate = deferred(), rows = [request("one", [segment("a")])], calls = [];
  const controller = create(async (_p, _m, _q, body) => {calls.push(body); return gate.promise;});
  const first = controller.search("first", rows), second = controller.search("second", rows);
  assert.equal(first, second); assert.equal(controller.state.status, "loading"); assert.deepEqual(controller.filter(rows), []);
  await tick(); assert.equal(calls.length, 1); assert.equal(calls[0].keyword, "first");
  controller.reset(); gate.resolve(response(rows[0].segments)); await first;
  assert.equal(controller.state.status, "idle"); assert.equal(controller.state.requestKeys.size, 0);
  assert.deepEqual(controller.filter(rows), rows);
});

test("a reset and newer search cannot be overwritten by an older completion or rejection", async () => {
  for (const rejectOld of [false, true]) {
    const old = deferred(), newer = deferred(), a = request("one", [segment("a")]), b = request("two", [segment("b")]);
    let calls = 0;
    const controller = create(async () => (++calls === 1 ? old.promise : newer.promise));
    const first = controller.search("old", [a]); await tick(); controller.reset();
    const second = controller.search("new", [b]); await tick(); newer.resolve(response(b.segments)); await second;
    if (rejectOld) old.reject(Error("sensitive old failure")); else old.resolve(response(a.segments));
    await first;
    assert.equal(controller.state.keyword, "new"); assert.equal(controller.state.status, "ready");
    assert.deepEqual(controller.filter([a, b]), [b]);
  }
});

test("stale results keep only the current identity intersection and never trigger a rescan", async () => {
  const a = request("one", [segment("a")]), b = request("two", [segment("b")]); let calls = 0;
  const controller = create(async (_p, _m, _q, body) => {calls++; return response(body.segments);});
  await controller.search("needle", [a]);
  assert.equal(controller.isStale([a]), false); assert.equal(controller.isStale([a, b]), true);
  assert.deepEqual(controller.filter([a, b]), [a]); assert.deepEqual(controller.filter([b]), []);
  assert.equal(controller.isStale([]), true); assert.equal(calls, 1);
});
