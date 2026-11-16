const test = require("node:test");
const assert = require("node:assert/strict");
const graph = require("../tracelens_cli/web/trace-graph.js");

// Sanitized structural reproduction of a saved grant flow. No captured SQL,
// request payloads, account identifiers, credentials or production data belong
// in this fixture. The callback and two Ledger calls are distinct invocations,
// even though each service name and process identity may occur more than once.
function grantFlow() {
  const segment = (id, service, method, path) => {
    const serviceInstanceId = `${service}-fixture-instance`, segmentId = `${id}-invocation`;
    return {id, kind: "segment", service, serviceInstanceId, segmentId,
      segmentKey: JSON.stringify([service, serviceInstanceId, segmentId]),
      method, path, httpStatus: 200, durationMs: 12};
  };
  const app = segment("s-app-root", "cb-app", "POST", "/api/v2/promotional_grants");
  const callback = segment("s-app-callback", "cb-app", "GET", "/api/v2/subscriptions/active_credits");
  const go = segment("s-go", "cb-grant-orchestrator", "POST", "/api/v2/promotional_grants");
  const accounts = segment("s-ledger-accounts", "cb-consumption-ledger", "GET", "/api/v2/accounts");
  const allocations = segment("s-ledger-allocations", "cb-consumption-ledger", "POST", "/api/v2/allocations");
  const nodes = [callback, app, accounts, allocations, go], edges = [];
  const identity = owner => ({ownerId: owner.id, service: owner.service,
    serviceInstanceId: owner.serviceInstanceId, segmentId: owner.segmentId, segmentKey: owner.segmentKey});
  const link = (from, to, kind) => edges.push({id: `${kind}:${from}:${to}`, from, to, kind});
  function http(id, owner, target) {
    const call = {id, kind: "http-call", ...identity(owner), callKey: `call:${id}`,
      destinationName: target.service, method: target.method, path: target.path,
      peer: "127.0.0.1:9000", downstreamState: "observed", propagationStatus: "INJECTED",
      httpStatus: 200, status: "COMPLETED", durationMs: 15};
    nodes.push(call); link(owner.id, id, "outbound"); link(id, target.id, "observed-call");
  }
  http("h-app-go", app, go);
  http("h-go-callback", go, callback);
  http("h-go-accounts", go, accounts);
  http("h-go-allocations", go, allocations);
  for (const owner of [go, allocations]) {
    const id = `external:${owner.id}`;
    nodes.push({id, kind: "http-call", ...identity(owner), callKey: `call:${id}`,
      destinationName: "cb-plans", peer: "127.0.0.1:9001", method: "GET",
      path: "/api/v2/custom_pricing_units/", downstreamState: "not-observed",
      propagationStatus: "NOT_ALLOWLISTED", httpStatus: 200, status: "COMPLETED", durationMs: 3});
    link(owner.id, id, "outbound");
  }
  for (const [owner, count] of [[app, 2], [callback, 4], [go, 3], [accounts, 2], [allocations, 5]]) {
    for (let i = 0; i < count; i++) {
      const id = `sql:${owner.id}:${i}`;
      nodes.push({id, kind: "dependency-call", ...identity(owner), callKey: `call:${id}`,
        boundaryKind: "database", system: owner.service === "cb-app" ? "mysql" : "postgresql",
        direction: "outbound", evidenceKind: "jdbc-execution", operation: "executeQuery",
        downstreamState: "not-observed", status: "OBSERVED", durationMs: 1,
        eventSeq: 10 + i, thread: "fixture-worker", sqlSource: "statement", sql: "SELECT 1"});
      link(owner.id, id, "outbound");
    }
  }
  return {schemaVersion: 1, kind: "request-flow", nodes, edges, roots: [app.id]};
}

function freezeTree(value) {
  if (value && typeof value === "object") {
    Object.values(value).forEach(freezeTree);
    Object.freeze(value);
  }
  return value;
}

function visibleModel(input, showHttpHops) {
  const hops = showHttpHops ? input : graph.presentation(input).model;
  return graph.groupDatabaseCalls(graph.groupDatabaseRoutes(hops).model).model;
}

const intersects = (a, b) => a.x < b.x + b.width && a.x + a.width > b.x
  && a.y < b.y + b.height && a.y + a.height > b.y;
const contains = (outer, inner) => inner.x >= outer.x && inner.y >= outer.y
  && inner.x + inner.width <= outer.x + outer.width && inner.y + inner.height <= outer.y + outer.height;

function assertFrames(output, input) {
  const segments = input.nodes.filter(node => node.kind === "segment");
  assert.equal(output.groups.length, segments.length, "every observed invocation needs its own frame");
  for (const owner of segments) {
    const frames = output.groups.filter(frame => frame.ownerId === owner.id);
    assert.equal(frames.length, 1, `missing or duplicate frame for ${owner.id}`);
    const frame = frames[0];
    const expected = input.nodes.filter(node => node.id === owner.id
      || node.ownerId === owner.id && !(node.kind === "http-call" && node.downstreamState === "not-observed"));
    assert.deepEqual([...frame.nodeIds].sort(), expected.map(node => node.id).sort(),
      `${owner.id} must contain its own retained local observations only`);
    for (const id of frame.nodeIds) {
      const placed = output.nodes.find(node => node.id === id);
      assert.ok(placed && contains(frame, placed), `${id} must be wholly inside ${owner.id}'s frame`);
      if (id !== owner.id) {
        for (const field of ["service", "serviceInstanceId", "segmentId", "segmentKey"]) {
          assert.equal(placed.node[field], owner[field], `${id} must retain its recorded ${field}`);
        }
      }
    }
  }
  for (const external of output.nodes.filter(node => node.externalService)) {
    assert.equal(external.node.kind, "http-call", "an unobserved destination is not a fabricated service segment");
    assert.ok(output.groups.every(frame => !frame.nodeIds.includes(external.id) && !intersects(frame, external)),
      `${external.id} must stay outside every captured invocation frame`);
  }
}

function assertNoOverlaps(output) {
  for (let i = 0; i < output.nodes.length; i++) {
    const node = output.nodes[i];
    for (const value of [node.x, node.y, node.width, node.height]) assert.ok(Number.isFinite(value));
    assert.ok(node.x >= 0 && node.y >= 0 && node.x + node.width <= output.width
      && node.y + node.height <= output.height, `${node.id} must remain within the diagram bounds`);
    for (const other of output.nodes.slice(i + 1)) {
      assert.ok(!intersects(node, other), `node overlap: ${node.id} and ${other.id}`);
    }
  }
  for (let i = 0; i < output.groups.length; i++) {
    const frame = output.groups[i];
    for (const other of output.groups.slice(i + 1)) {
      assert.ok(!intersects(frame, other), `frame overlap: ${frame.ownerId} and ${other.ownerId}`);
    }
    for (const node of output.nodes.filter(node => !frame.nodeIds.includes(node.id))) {
      assert.ok(!intersects(frame, node), `frame ${frame.ownerId} encloses foreign node ${node.id}`);
    }
  }
}

// Parse the renderer's absolute SVG path geometry. Rectilinear segments are
// checked exactly; cubic paths are subdivided so a future rounded routing style
// still checks the actual connector rather than its bounding rectangle.
function pathLines(path) {
  const tokens = path.match(/[MLHVC]|-?(?:\d*\.\d+|\d+\.?\d*)(?:e[-+]?\d+)?/gi) || [];
  const lines = []; let i = 0, point = null;
  const number = () => { const value = Number(tokens[i++]); assert.ok(Number.isFinite(value), path); return value; };
  const lineTo = next => { assert.ok(point, path); lines.push([point, next]); point = next; };
  while (i < tokens.length) {
    const command = tokens[i++];
    if (command === "M") point = {x: number(), y: number()};
    else if (command === "L") lineTo({x: number(), y: number()});
    else if (command === "H") lineTo({x: number(), y: point.y});
    else if (command === "V") lineTo({x: point.x, y: number()});
    else if (command === "C") {
      const p0 = point, p1 = {x: number(), y: number()}, p2 = {x: number(), y: number()}, p3 = {x: number(), y: number()};
      for (let step = 1; step <= 64; step++) {
        const t = step / 64, u = 1 - t;
        lineTo({x: u ** 3 * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t ** 3 * p3.x,
          y: u ** 3 * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t ** 3 * p3.y});
      }
    } else assert.fail(`Unsupported connector command ${command}: ${path}`);
  }
  assert.ok(lines.length, `connector must have drawable geometry: ${path}`);
  return lines;
}

function lineCrossesInterior([from, to], box) {
  let lower = 0, upper = 1;
  for (const [axis, size] of [["x", "width"], ["y", "height"]]) {
    const minimum = box[axis] + 1e-6, maximum = box[axis] + box[size] - 1e-6;
    const delta = to[axis] - from[axis];
    if (!delta) {
      if (from[axis] < minimum || from[axis] > maximum) return false;
    } else {
      const first = (minimum - from[axis]) / delta, second = (maximum - from[axis]) / delta;
      lower = Math.max(lower, Math.min(first, second));
      upper = Math.min(upper, Math.max(first, second));
      if (lower > upper) return false;
    }
  }
  return lower <= upper;
}

function assertClearConnectors(output) {
  for (const edge of output.edges) {
    const lines = pathLines(edge.path);
    for (const node of output.nodes.filter(node => node.id !== edge.from && node.id !== edge.to)) {
      assert.ok(!lines.some(line => lineCrossesInterior(line, node)),
        `connector ${edge.from} → ${edge.to} crosses unrelated ${node.id}: ${edge.path}`);
    }
  }
}

function shuffled(items, seed) {
  const result = [...items]; let state = seed;
  for (let i = result.length - 1; i > 0; i--) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    const j = state % (i + 1); [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

test("ambiguous HTTP receiver evidence stays intact and deterministic when edges are reordered", () => {
  const root = {id: "root", kind: "segment", service: "caller", serviceInstanceId: "caller-instance", segmentId: "root-invocation"};
  const calls = ["call-a", "call-b"].map(id => ({id, kind: "http-call", ownerId: root.id,
    service: root.service, serviceInstanceId: root.serviceInstanceId, segmentId: root.segmentId,
    callKey: id, method: "GET", path: "/fixture", downstreamState: "observed"}));
  const receivers = ["a", "m", "z"].map(id => ({id, kind: "segment", service: `receiver-${id}`,
    serviceInstanceId: `receiver-${id}-instance`, segmentId: `receiver-${id}-invocation`, method: "GET", path: "/fixture"}));
  const edges = [["root", "call-a", "outbound"], ["root", "call-b", "outbound"],
    ["call-a", "a", "observed-call"], ["call-a", "z", "observed-call"], ["call-b", "m", "observed-call"]]
    .map(([from, to, kind]) => ({id: `${from}:${to}`, from, to, kind}));
  const input = freezeTree({schemaVersion: 1, kind: "request-flow", nodes: [root, ...calls, ...receivers], edges, roots: [root.id]});
  const before = JSON.stringify(input);
  for (const showHttpHops of [true, false]) {
    const shown = visibleModel(input, showHttpHops), expected = graph.layout(shown);
    assert.ok(shown.nodes.some(node => node.id === "call-a"), "an ambiguous hop must never be silently folded");
    assert.deepEqual(shown.edges.filter(edge => edge.from === "call-a").map(edge => edge.to).sort(), ["a", "z"],
      "both recorded receiver edges must remain available");
    assert.deepEqual(expected.nodes.filter(item => item.node.kind === "segment").map(item => item.id).sort(), ["a", "m", "root", "z"],
      "receiver records must not be merged or dropped");
    for (const reordered of [
      {...input, edges: [...input.edges].reverse()},
      {...input, nodes: shuffled(input.nodes, 13), edges: shuffled(input.edges, 31)},
    ]) assert.deepEqual(graph.layout(visibleModel(reordered, showHttpHops)), expected,
      "an arbitrary first receiver edge must not determine local HTTP card order");
  }
  assert.equal(JSON.stringify(input), before);
});

for (const showHttpHops of [true, false]) {
  const mode = showHttpHops ? "HTTP hops shown" : "HTTP hops folded";
  test(`grant flow keeps one correctly owned frame per invocation (${mode})`, () => {
    const input = freezeTree(grantFlow()), shown = visibleModel(input, showHttpHops);
    assert.equal(graph.presentation(input).foldedHops.length, 4);
    assert.equal(shown.nodes.filter(node => node.presentationKind === "jdbc-group").length, 5);
    assertFrames(graph.layout(shown, {boundaryActions: true}), shown);
  });

  test(`grant flow cards, invocation frames and external services do not overlap (${mode})`, () => {
    const shown = visibleModel(grantFlow(), showHttpHops);
    assertNoOverlaps(graph.layout(shown, {boundaryActions: true, requestDataActions: true}));
  });

  test(`an observed leaf with no retained SQL still gets its own invocation frame (${mode})`, () => {
    const input = grantFlow();
    const omitted = new Set(input.nodes.filter(node => node.kind === "dependency-call"
      && node.ownerId === "s-ledger-accounts").map(node => node.id));
    const withoutLeafSql = {...input, nodes: input.nodes.filter(node => !omitted.has(node.id)),
      edges: input.edges.filter(edge => !omitted.has(edge.from) && !omitted.has(edge.to))};
    const shown = visibleModel(withoutLeafSql, showHttpHops);
    const output = graph.layout(shown, {boundaryActions: true});
    assertFrames(output, shown);
    assertNoOverlaps(output);
  });

  test(`grant flow connectors avoid unrelated card interiors (${mode})`, () => {
    const shown = visibleModel(grantFlow(), showHttpHops);
    assertClearConnectors(graph.layout(shown, {boundaryActions: true}));
  });

  test(`grant flow keeps both Ledger invocations and both cb-app invocations (${mode})`, () => {
    const shown = visibleModel(grantFlow(), showHttpHops), output = graph.layout(shown);
    for (const service of ["cb-consumption-ledger", "cb-app"]) {
      const invocations = output.nodes.filter(node => node.node.kind === "segment" && node.node.service === service);
      assert.equal(invocations.length, 2, `${service} invocations must not be merged by service name`);
      assert.equal(new Set(invocations.map(node => node.node.segmentId)).size, 2);
      assert.equal(new Set(invocations.map(node => node.node.serviceInstanceId)).size, 1,
        "the fixture intentionally uses one process per service");
      assert.equal(output.groups.filter(frame => invocations.some(node => node.id === frame.ownerId)).length, 2);
    }
    const ledger = output.nodes.filter(node => node.node.kind === "segment" && node.node.service === "cb-consumption-ledger");
    assert.deepEqual(ledger.map(node => `${node.node.method} ${node.node.path}`).sort(),
      ["GET /api/v2/accounts", "POST /api/v2/allocations"]);
  });

  test(`grant flow layout is deterministic after shuffled input and SQL grouping (${mode})`, () => {
    const input = freezeTree(grantFlow()), before = JSON.stringify(input);
    const options = {boundaryActions: true, requestDataActions: true};
    const expected = graph.layout(visibleModel(input, showHttpHops), options);
    for (const seed of [1, 7, 29, 101]) {
      const reordered = {...input, nodes: shuffled(input.nodes, seed), edges: shuffled(input.edges, seed + 1)};
      assert.deepEqual(graph.layout(visibleModel(reordered, showHttpHops), options), expected,
        `input order must not affect layout (seed ${seed})`);
    }
    assert.equal(JSON.stringify(input), before, "layout must not rewrite captured evidence");
  });

  test(`expanded SQL and request evidence preserve grant flow geometry (${mode})`, () => {
    const shown = visibleModel(grantFlow(), showHttpHops);
    for (const owner of ["s-go", "s-ledger-allocations", "s-app-callback"]) {
      const sql = shown.nodes.find(node => node.ownerId === owner && node.presentationKind === "jdbc-group");
      assert.ok(sql, `${owner} needs a grouped SQL card in the fixture`);
      const output = graph.layout(shown, {boundaryActions: true, requestDataActions: true,
        expandedRouteId: sql.id, expandedRequestId: owner});
      assertFrames(output, shown);
      assertNoOverlaps(output);
      assertClearConnectors(output);
    }
  });
}
