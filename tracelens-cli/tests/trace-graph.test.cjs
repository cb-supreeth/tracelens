const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const graph = require("../tracelens_cli/web/trace-graph.js");

const node = (id, kind = "segment", extra = {}) => ({ id, kind, service: id, ...extra });
const edge = (from, to, kind = "observed-call", id = `${from}:${to}`) => ({ id, from, to, kind });
const model = (nodes, edges = [], roots = []) => ({ schemaVersion: 1, kind: "request-flow", nodes, edges, roots });
const capturedHttp = () => {
  const app = node("app", "segment", {service: "cb-app", segmentId: "app-segment", serviceInstanceId: "app-instance", durationMs: 50, httpStatus: 200});
  const call = node("call", "http-call", {service: "cb-app", ownerId: "app", segmentId: "app-segment", serviceInstanceId: "app-instance",
    peer: "127.0.0.1:8082", destinationName: "cb-ems", method: "POST", path: "/features", callKey: "caller-key",
    downstreamState: "observed", durationMs: 14, httpStatus: 503, status: "ERROR", propagationStatus: "INJECTED"});
  const ems = node("ems", "segment", {service: "cb-ems", segmentId: "ems-segment", serviceInstanceId: "ems-instance", durationMs: 7, httpStatus: 200});
  return model([app, call, ems], [edge("app", "call", "outbound"), edge("call", "ems")], ["app"]);
};
const externalHttp = () => {
  const input = capturedHttp();
  input.nodes.pop(); input.edges.pop();
  Object.assign(input.nodes[1], {peer: "127.0.0.1:8081", destinationName: "cb-plans", method: "POST",
    path: "/items/search", downstreamState: "not-observed", httpStatus: 200, status: "COMPLETED",
    propagationStatus: "NOT_ALLOWLISTED"});
  return input;
};
const databaseRoute = (id, extra = {}) => node(id, "dependency-call", {service: "app", ownerId: "app",
  serviceInstanceId: "app-instance", segmentId: "app-segment", segmentKey: "app-key", boundaryKind: "database-route",
  system: "database", direction: "outbound", evidenceKind: "routing-marker", operation: "route", status: "OBSERVED",
  destination: "TEST_MASTER", eventSeq: 1, thread: "http-worker", callKey: id, ...extra});
const capturedRoutes = () => model([
  node("app", "segment", {serviceInstanceId: "app-instance", segmentId: "app-segment", segmentKey: "app-key"}),
  databaseRoute("route-late", {eventSeq: 20, thread: "queue-worker"}), databaseRoute("route-early", {eventSeq: 3})],
  [edge("app", "route-late", "outbound"), edge("app", "route-early", "outbound")], ["app"]);
const sqlNode = (id, extra = {}) => node(id, "dependency-call", {boundaryKind: "database", system: "postgresql",
  evidenceKind: "jdbc-execution", direction: "outbound", operation: "executeQuery", status: "OBSERVED",
  durationMs: 2.75, sqlSource: "statement", eventSeq: 12, ...extra});
const capturedSql = (count = 2, overrides = {}) => {
  const identity = {service: "cb-ems", serviceInstanceId: "ems-instance", segmentId: "ems-segment"};
  const queries = Array.from({length: count}, (_, index) => sqlNode(`query-${index + 1}`, {...identity,
    ownerId: "ems", callKey: `call-${index + 1}`, eventSeq: index + 1, thread: "ems-worker",
    sql: `select ${index + 1}`, ...(overrides[index + 1] || {})}));
  return model([node("ems", "segment", identity), ...queries], queries.map(query => edge("ems", query.id, "outbound")), ["ems"]);
};
function freezeTree(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.values(value).forEach(freezeTree); Object.freeze(value);
  }
  return value;
}

test("browser global and Node exports share the buildless API", () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(require.resolve("../tracelens_cli/web/trace-graph.js"), "utf8"), context);
  assert.equal(typeof context.TraceLensGraph.render, "function");
  assert.equal(typeof context.TraceLensGraph.layout, "function");
  assert.equal(typeof context.TraceLensGraph.groupDatabaseRoutes, "function");
});

test("empty and malformed captures produce bounded empty diagrams", () => {
  for (const input of [null, {}, model([]), model([null, {}, { id: "x", kind: "mystery" }])]) {
    const output = graph.layout(input);
    assert.equal(output.nodes.length, 0);
    assert.equal(output.width, 0);
    assert.equal(output.height, 0);
  }
});

test("only explicit edges establish connectivity, never matching service/span metadata", () => {
  const output = graph.layout(model([node("a", "segment", { parentSpanId: "shared" }), node("b", "segment", { parentSpanId: "shared" }), node("call", "http-call", { ownerId: "a", peer: "b" })]));
  assert.equal(output.edges.length, 0);
  assert.ok(output.nodes.every(item => item.rank === 0 && item.unlinked));
});

test("causal ranks ignore inconsistent timestamps and do not sum local duration", () => {
  const input = model([node("app", "segment", { startedAtMillis: 1000, durationMs: 90 }), node("call", "http-call", { startedAtMillis: 10, durationMs: 40 }), node("ems", "segment", { startedAtMillis: 0, durationMs: 20 })], [edge("app", "call", "outbound"), edge("call", "ems")], ["app"]);
  const before = JSON.stringify(input);
  const output = graph.layout(input);
  assert.deepEqual(output.nodes.map(item => [item.id, item.rank]), [["app", 0], ["call", 1], ["ems", 2]]);
  assert.equal(output.nodes[0].node.durationMs, 90);
  assert.equal(JSON.stringify(input), before);
  assert.ok(output.edges.every(item => item.path.startsWith("M ")));
});

test("callers center on three visible destinations without timestamps or overlapping cards", () => {
  const input = capturedHttp();
  const owner = {ownerId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance", segmentId: "ems-segment"};
  input.nodes.push(databaseRoute("db-master", {...owner, destination: "TEST_MASTER", startedAtMillis: 9000}),
    databaseRoute("db-slave", {...owner, destination: "TEST_SLAVE", startedAtMillis: 1}),
    node("queue", "dependency-call", {...owner, boundaryKind: "messaging", direction: "publish", startedAtMillis: 500}));
  input.edges.push(...input.nodes.slice(3).map(item => edge("ems", item.id, "outbound")));
  const before = JSON.stringify(input); freezeTree(input);
  const shown = graph.presentation(input).model, output = graph.layout(shown);
  const destinationIds = ["db-master", "db-slave", "queue"];
  const destinations = output.nodes.filter(item => destinationIds.includes(item.id));
  const center = destinations.reduce((sum, item) => sum + item.y + item.height / 2, 0) / destinations.length;
  for (const id of ["app", "ems"]) {
    const caller = output.nodes.find(item => item.id === id);
    assert.equal(caller.y + caller.height / 2, center, `${id} is centered on its visible downstream branch`);
  }
  const changedTimes = graph.layout({...shown, nodes: shown.nodes.map((item, index) =>
    ({...item, startedAtMillis: 999999 - index, durationMs: index * 10000}))});
  const geometry = value => ({
    nodes: value.nodes.map(({id, x, y, width, height, rank, slot}) => ({id, x, y, width, height, rank, slot})),
    edges: value.edges.map(({id, path}) => ({id, path})),
  });
  assert.deepEqual(geometry(changedTimes), geometry(output), "timestamps and durations do not position cards");
  for (const result of [output, changedTimes, graph.layout(shown, {expandedRouteId: "db-master"})]) {
    for (const item of result.nodes) {
      for (const other of result.nodes.filter(candidate => candidate.id !== item.id && candidate.rank === item.rank)) {
        assert.ok(item.y + item.height <= other.y || other.y + other.height <= item.y, "cards never overlap within a column");
      }
    }
  }
  assert.equal(JSON.stringify(input), before);
});

test("parallel calls and disconnected evidence remain distinct and deterministic", () => {
  const nodes = [node("app"), node("c1", "http-call"), node("c2", "http-call"), node("ems1"), node("ems2"), node("orphan")];
  const edges = [edge("app", "c2", "outbound"), edge("app", "c1", "outbound"), edge("c1", "ems1"), edge("c2", "ems2")];
  const output = graph.layout(model(nodes, edges, ["app"]));
  assert.deepEqual(output, graph.layout(model([...nodes].reverse(), [...edges].reverse(), ["app"])));
  assert.equal(output.nodes.find(item => item.id === "orphan").rank, 0);
  assert.equal(output.nodes.find(item => item.id === "orphan").unlinked, true);
  assert.notEqual(output.nodes.find(item => item.id === "c1").y, output.nodes.find(item => item.id === "c2").y);
});

test("cycles and self links terminate and retain actual directed edges", () => {
  const output = graph.layout(model([node("a"), node("b"), node("c"), node("self")], [edge("a", "b"), edge("b", "a"), edge("b", "c"), edge("self", "self")]));
  assert.equal(output.cycleCount, 2);
  assert.equal(output.edges.length, 4);
  assert.equal(output.nodes.find(item => item.id === "a").rank, output.nodes.find(item => item.id === "b").rank);
  assert.equal(output.nodes.find(item => item.id === "c").rank, 1);
  assert.equal(output.edges.filter(item => item.cyclic).length, 3);
});

test("duplicate nodes, dangling edges and unknown edge kinds cannot invent links", () => {
  const output = graph.layout(model([node("a"), node("a"), node("b")], [edge("a", "missing"), edge("a", "b", "inferred"), edge("a", "b")]));
  assert.equal(output.invalidNodes, 1);
  assert.equal(output.invalidEdges, 2);
  assert.equal(output.edges.length, 1);
});

test("a 256-node fanout remains bounded with explicit omitted counts", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`));
  const edges = nodes.slice(1).map(item => edge(nodes[0].id, item.id));
  const output = graph.layout(model(nodes, edges, [nodes[0].id]));
  assert.equal(output.nodes.length, graph.limits.nodes);
  assert.equal(output.omittedNodes, 256 - graph.limits.nodes);
  assert.equal(output.totalNodes, 256);
  assert.ok(output.width < 9000 && output.height < 18000);
  assert.ok(output.edges.every(item => output.nodes.some(node => node.id === item.from) && output.nodes.some(node => node.id === item.to)));
});

test("very deep chains are capped by causal column count without fake reattachment", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`));
  const edges = nodes.slice(1).map((item, index) => edge(nodes[index].id, item.id));
  const output = graph.layout(model(nodes, edges, [nodes[0].id]));
  assert.equal(output.nodes.length, graph.limits.columns);
  assert.equal(output.edges.length, graph.limits.columns - 1);
  assert.ok(output.width < 9000);
  assert.equal(output.omittedNodes, 256 - graph.limits.columns);
});

test("parallel edges and dense graph edge counts are bounded", () => {
  const parallel = graph.layout(model([node("a"), node("b")], [edge("a", "b", "observed-call", "one"), edge("a", "b", "observed-call", "two")]));
  assert.notEqual(parallel.edges[0].path, parallel.edges[1].path);
  const nodes = Array.from({ length: 30 }, (_, index) => node(String(index)));
  const edges = nodes.flatMap(from => nodes.filter(to => from.id !== to.id).map(to => edge(from.id, to.id)));
  const output = graph.layout(model(nodes, edges));
  assert.equal(output.edges.length, graph.limits.edges);
  assert.equal(output.omittedEdges, edges.length - graph.limits.edges);
});

test("service observation frames require explicit ownership, a local segment identity and a recorded boundary edge", () => {
  const input = capturedRoutes(), before = JSON.stringify(input);
  freezeTree(input);
  const output = graph.layout(input);
  assert.equal(output.groups.length, 1);
  const group = output.groups[0];
  assert.equal(group.id, "app"); assert.equal(group.ownerId, "app"); assert.equal(group.name, "app");
  assert.deepEqual(group.nodeIds, ["app", "route-early", "route-late"]);
  for (const id of group.nodeIds) {
    const item = output.nodes.find(value => value.id === id);
    assert.ok(group.x <= item.x && group.y <= item.y);
    assert.ok(group.x + group.width >= item.x + item.width && group.y + group.height >= item.y + item.height);
    assert.equal(item.node, input.nodes.find(value => value.id === id), "decorations retain canonical record identity");
  }
  assert.deepEqual(output, graph.layout({...input, nodes: [...input.nodes].reverse(), edges: [...input.edges].reverse()}));
  assert.equal(JSON.stringify(input), before);
  const scenarios = [
    ["owner absent", value => value.nodes.slice(1).forEach(item => { delete item.ownerId; })],
    ["owner contradicts edge", value => value.nodes.slice(1).forEach(item => { item.ownerId = "route-early"; })],
    ["no recorded boundary edge", value => { value.edges = []; }],
    ["wrong recorded direction", value => value.edges.forEach(item => { item.kind = "inbound"; })],
    ["no shared segment identity", value => value.nodes.forEach(item => { delete item.segmentId; delete item.segmentKey; })],
    ["segment contradiction despite matching key", value => value.nodes.slice(1).forEach(item => { item.segmentId = "different"; })],
    ["segment-key contradiction despite matching ID", value => value.nodes.slice(1).forEach(item => { item.segmentKey = "different"; })],
    ["service contradiction", value => value.nodes.slice(1).forEach(item => { item.service = "different"; })],
    ["instance contradiction", value => value.nodes.slice(1).forEach(item => { item.serviceInstanceId = "different"; })],
  ];
  for (const [name, mutate] of scenarios) {
    const value = capturedRoutes(); mutate(value);
    assert.deepEqual(graph.layout(value).groups, [], name);
  }
  const keyOnly = capturedRoutes(); keyOnly.nodes.forEach(item => { delete item.segmentId; });
  assert.equal(graph.layout(keyOnly).groups.length, 1, "an explicit matching segment key can establish local identity");
});

test("observation frames never absorb remote services or a foreign card inside their rectangle", () => {
  const input = capturedHttp(), before = JSON.stringify(input);
  const output = graph.layout(input);
  assert.equal(output.groups.length, 2);
  assert.deepEqual(output.groups.find(group => group.ownerId === "app").nodeIds, ["app", "call"]);
  assert.deepEqual(output.groups.find(group => group.ownerId === "ems").nodeIds, ["ems"], "remote execution has its own invocation frame");
  assert.equal(JSON.stringify(input), before);
  const crowded = capturedRoutes();
  crowded.nodes.push(node("foreign", "segment", {service: "app", segmentId: "different-invocation"}));
  const packed = graph.layout(crowded), foreign = packed.nodes.find(item => item.id === "foreign");
  assert.equal(packed.groups.length, 1, "unrelated evidence is laid out outside the invocation instead of hiding its frame");
  assert.ok(packed.groups.every(frame => !frame.nodeIds.includes("foreign")
    && (foreign.x >= frame.x + frame.width || foreign.x + foreign.width <= frame.x
      || foreign.y >= frame.y + frame.height || foreign.y + foreign.height <= frame.y)),
  "a frame cannot visually claim an unrelated service card");
});

test("unobserved HTTP destinations sit outside the caller frame without creating a captured service", () => {
  const input = externalHttp(), before = JSON.stringify(input);
  freezeTree(input);
  const output = graph.layout(input), caller = output.groups.find(group => group.ownerId === "app");
  assert.ok(caller, "the caller retains a boundary even when its only destination is unobserved");
  assert.deepEqual(caller.nodeIds, ["app"]);
  const external = output.nodes.find(item => item.id === "call");
  assert.equal(external.node, input.nodes[1]);
  assert.equal(external.node.kind, "http-call", "the destination remains the original caller-side HTTP evidence");
  assert.equal(external.downstreamObserved, false);
  assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, 1);
  assert.equal(output.edges.length, 1); assert.equal(output.edges[0].from, "app"); assert.equal(output.edges[0].to, "call");
  assert.ok(external.x >= caller.x + caller.width || external.x + external.width <= caller.x
    || external.y >= caller.y + caller.height || external.y + external.height <= caller.y,
    "the external destination cannot be visually enclosed by cb-app");
  assert.equal(JSON.stringify(input), before);
});

test("local SQL and observed HTTP hops remain caller-local while unobserved destinations stay outside every frame", () => {
  const input = capturedHttp();
  const plans = {...externalHttp().nodes[1], id: "plans-call", callKey: "plans-call-key"};
  const sql = sqlNode("mysql", {ownerId: "app", service: "cb-app", serviceInstanceId: "app-instance",
    segmentId: "app-segment", system: "mysql", sql: "select 1"});
  input.nodes.push(plans, sql); input.edges.push(edge("app", plans.id, "outbound"), edge("app", sql.id, "outbound"));
  const before = JSON.stringify(input); freezeTree(input);
  for (const shown of [input, graph.presentation(input).model]) {
    const output = graph.layout(shown), caller = output.groups.find(group => group.ownerId === "app");
    assert.ok(caller, "mixed local and external evidence keeps the caller boundary visible");
    assert.ok(caller.nodeIds.includes("mysql"), "local JDBC evidence retains caller ownership");
    assert.equal(caller.nodeIds.includes("call"), shown === input, "only the displayed observed hop is caller-local");
    assert.equal(caller.nodeIds.includes(plans.id), false);
    const external = output.nodes.find(item => item.id === plans.id);
    for (const frame of output.groups) {
      assert.ok(external.x >= frame.x + frame.width || external.x + external.width <= frame.x
        || external.y >= frame.y + frame.height || external.y + external.height <= frame.y,
        "external HTTP cards cannot intersect an observed-service frame");
    }
    assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, 2);
    assert.equal(external.node, plans);
  }
  assert.equal(JSON.stringify(input), before);
});

test("repeated external calls to the same peer remain separate observations with their own APIs and timings", () => {
  const input = externalHttp();
  input.nodes.push({...input.nodes[1], id: "second-call", callKey: "second-call-key", path: "/item_prices/search",
    durationMs: 29, httpStatus: 503, status: "ERROR"});
  input.edges.push(edge("app", "second-call", "outbound"));
  const before = JSON.stringify(input); freezeTree(input);
  const shown = graph.presentation(input), output = graph.layout(shown.model);
  assert.equal(shown.foldedHops.length, 0);
  assert.deepEqual(output.nodes.filter(item => item.node.kind === "http-call")
    .map(item => [item.id, item.node.path, item.node.durationMs, item.node.httpStatus]),
  [["call", "/items/search", 14, 200], ["second-call", "/item_prices/search", 29, 503]]);
  assert.equal(output.edges.length, 2);
  assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, 1);
  assert.notEqual(output.nodes.find(item => item.id === "call").y,
    output.nodes.find(item => item.id === "second-call").y);
  assert.ok(output.groups.every(group => !group.nodeIds.includes("call") && !group.nodeIds.includes("second-call")));
  assert.equal(JSON.stringify(input), before);
});

test("external side branches stay adjacent to their caller, below its local frame, without global top detours", () => {
  const input = capturedHttp();
  const appIdentity = {ownerId: "app", service: "cb-app", serviceInstanceId: "app-instance", segmentId: "app-segment"};
  const emsIdentity = {ownerId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance", segmentId: "ems-segment"};
  input.nodes.push(sqlNode("app-db", {...appIdentity, system: "mysql"}), sqlNode("ems-db", emsIdentity));
  for (const [id, identity] of [["app-secret", appIdentity], ["ems-secret", emsIdentity], ["another-secret", appIdentity]]) {
    input.nodes.push(node(id, "http-call", {...identity, peer: "secretsmanager.example", path: "/", method: "POST", downstreamState: "not-observed"}));
    input.edges.push(edge(identity.ownerId, id, "outbound"));
  }
  input.edges.push(edge("app", "app-db", "outbound"), edge("ems", "ems-db", "outbound"));
  const before = JSON.stringify(input); freezeTree(input);
  for (const shown of [input, graph.presentation(input).model]) {
    for (const options of [{}, {expandedRouteId: "ems-db", boundaryActions: true},
      {requestDataActions: true, expandedRequestId: "app", boundaryActions: true}]) {
      const output = graph.layout(shown, options);
      assert.equal(output.groups.length, 2, "both captured service frames remain visible");
      for (const id of ["app-secret", "another-secret", "ems-secret"]) {
        const satellite = output.nodes.find(item => item.id === id);
        const owner = output.nodes.find(item => item.id === satellite.node.ownerId);
        const frame = output.groups.find(group => group.ownerId === owner.id);
        assert.equal(satellite.rank, owner.rank + 1, "external service stays in its caller's adjacent dependency column");
        assert.ok(satellite.y > frame.y + frame.height, "external destination sits below, never inside, its caller frame");
        const link = output.edges.find(item => item.to === id);
        assert.ok(link.path.startsWith(`M ${owner.x + owner.width / 2} ${owner.y + owner.height} V `), "branch exits the actual caller's bottom");
        assert.ok(!/V (12|18|24|30) H/.test(link.path), "no global top-of-canvas detour");
        for (const other of output.nodes.filter(item => item.id !== id)) {
          assert.ok(satellite.x >= other.x + other.width || satellite.x + satellite.width <= other.x
            || satellite.y >= other.y + other.height || satellite.y + satellite.height <= other.y, "no card overlaps");
        }
      }
      assert.deepEqual(output, graph.layout({...shown, nodes: [...shown.nodes].reverse(), edges: [...shown.edges].reverse()}, options),
        "identical evidence has identical layout after reload or input reorder");
    }
  }
  assert.equal(JSON.stringify(input), before, "presentation never modifies evidence");
});

test("missing receiver evidence remains an adjacent external observation without inventing a service", () => {
  const input = externalHttp();
  input.nodes.push(sqlNode("db", {ownerId: "app", service: "cb-app", serviceInstanceId: "app-instance", segmentId: "app-segment", system: "mysql"}));
  input.edges.push(edge("app", "db", "outbound"));
  const output = graph.layout(input, {requestDataActions: true});
  const call = output.nodes.find(item => item.id === "call"), db = output.nodes.find(item => item.id === "db");
  assert.equal(call.rank, db.rank);
  assert.ok(call.y > db.y + db.height);
  assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, 1);
  assert.equal(call.downstreamObserved, false);
});

test("missing or malformed outgoing links never manufacture remote execution or service membership", () => {
  for (const [name, mutate] of [
    ["no link", input => { input.edges = []; }],
    ["dangling target", input => { input.edges[0].to = "missing"; }],
    ["unknown edge kind", input => { input.edges[0].kind = "guessed"; }],
    ["wrong owner", input => { input.nodes[1].ownerId = "missing"; }],
    ["absent owner", input => { delete input.nodes[1].ownerId; }],
    ["conflicting segment", input => { input.nodes[1].segmentId = "other-segment"; }],
  ]) {
    const input = externalHttp(); mutate(input);
    const before = JSON.stringify(input); freezeTree(input);
    const output = graph.layout(input), external = output.nodes.find(item => item.id === "call");
    assert.equal(external.downstreamObserved, false, name);
    assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, 1, name);
    assert.equal(output.groups.some(group => group.nodeIds.includes("call")), false, name);
    assert.equal(graph.presentation(input).foldedHops.length, 0, name);
    assert.equal(JSON.stringify(input), before, name);
  }
});

test("frames retain exact invocation identity and cannot overlap other frames or foreign evidence", () => {
  const overlaps = (a, b) => a.x < b.x + b.width && a.x + a.width > b.x &&
    a.y < b.y + b.height && a.y + a.height > b.y;
  for (const count of [2, 3, 5]) {
    const nodes = [], edges = [];
    for (let index = 0; index < count; index++) {
      const ownerId = `app-${index}`, segmentId = `invocation-${index}`;
      nodes.push(node(ownerId, "segment", {service: "same-service", serviceInstanceId: "same-instance", segmentId}),
        node(`boundary-${index}`, "dependency-call", {ownerId, service: "same-service", serviceInstanceId: "same-instance", segmentId,
          boundaryKind: "messaging", direction: "publish", evidenceKind: "client-boundary", destination: "same-queue"}));
      edges.push(edge(ownerId, `boundary-${index}`, "outbound"));
    }
    const output = graph.layout(model(nodes, edges));
    assert.equal(output.nodes.filter(item => item.node.kind === "segment").length, count);
    for (const group of output.groups) {
      const index = group.ownerId.slice("app-".length);
      assert.deepEqual(group.nodeIds, [`app-${index}`, `boundary-${index}`]);
      for (const other of output.nodes.filter(item => !group.nodeIds.includes(item.id))) {
        assert.equal(overlaps(group, other), false, "no unrelated node lies within a group");
      }
      for (const other of output.groups.filter(item => item.id !== group.id)) {
        assert.equal(overlaps(group, other), false, "overlapping group frames are omitted");
      }
    }
  }
});

test("edge categories and labels describe recorded boundary semantics without inventing delivery or SQL", () => {
  const cases = [
    ["database", databaseRoute("boundary"), "outbound", "database", "DB route"],
    ["queue publish", node("boundary", "dependency-call", {boundaryKind: "messaging", direction: "publish"}), "outbound", "queue", "publish"],
    ["queue consume", node("boundary", "dependency-call", {boundaryKind: "messaging", direction: "consume"}), "inbound", "queue", "consume"],
    ["unknown messaging", node("boundary", "dependency-call", {boundaryKind: "messaging"}), "outbound", "queue", "boundary"],
    ["HTTP", node("boundary", "http-call"), "outbound", "http", "HTTP"],
    ["wrapper", node("boundary", "dependency-call", {boundaryKind: "external", evidenceKind: "wrapper-entry"}), "outbound", "external", "wrapper"],
    ["generic database-named external", node("boundary", "dependency-call", {boundaryKind: "external", system: "database"}), "outbound", "external", "boundary"],
    ["observed service", node("boundary"), "observed-call", "service", "observed"],
  ];
  for (const [name, boundary, kind, category, label] of cases) {
    const link = kind === "inbound" ? edge("boundary", "app", kind) : edge("app", "boundary", kind);
    const input = model([node("app"), boundary], [link]), before = JSON.stringify(input);
    const output = graph.layout(input).edges[0];
    assert.equal(output.category, category, name); assert.equal(output.label, label, name);
    assert.ok(Number.isFinite(output.labelX) && Number.isFinite(output.labelY), name);
    assert.equal(JSON.stringify(input), before);
  }
  const parallel = graph.layout(model([node("app"), databaseRoute("db")],
    [edge("app", "db", "outbound", "first"), edge("app", "db", "outbound", "second")]));
  assert.equal(parallel.edges.filter(item => Number.isFinite(item.labelX) && Number.isFinite(item.labelY)).length, 1);
  const distant = graph.layout(model([node("a"), node("b"), node("c")],
    [edge("a", "b"), edge("b", "c"), edge("a", "c")]));
  const longLink = distant.edges.find(item => item.from === "a" && item.to === "c");
  assert.ok(longLink.labelX == null && longLink.labelY == null, "long links do not place labels over unrelated cards");
  const cyclic = graph.layout(model([node("a")], [edge("a", "a")])).edges[0];
  assert.ok(cyclic.labelX == null && cyclic.labelY == null, "cyclic links retain their route without a misleading label position");
});

// A small DOM double keeps renderer interaction tests dependency-free. innerHTML
// deliberately throws: application strings must only become inert text nodes.
function documentFixture() {
  const created = [];
  class Element {
    [Symbol.for("nodejs.util.inspect.custom")]() { return `<${this.tagName} class="${this.className}" node="${this.dataset.nodeId || ""}">`; }
    constructor(tag, document) {
      this.tagName = tag; this.ownerDocument = document; this.childNodes = [];
      this.style = {}; this.dataset = {}; this.attrs = {}; this.listeners = {};
      this.className = ""; this.scrollLeft = 0; this.scrollTop = 0;
      this.clientWidth = 800; this.clientHeight = 500;
      this.classList = { contains: name => this.className.split(" ").includes(name) };
      created.push(this);
    }
    set innerHTML(_) { throw new Error("Unsafe HTML interpolation"); }
    set textContent(value) { this.text = String(value); this.childNodes = []; }
    get textContent() { return (this.text || "") + this.childNodes.map(child => child.textContent).join(""); }
    append(...children) { children.forEach(child => { child.parentNode = this; this.childNodes.push(child); }); }
    replaceChildren(...children) { this.childNodes.forEach(child => { child.parentNode = null; }); this.childNodes = []; this.text = ""; this.append(...children); }
    remove() { if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(child => child !== this); this.parentNode = null; }
    setAttribute(name, value) { this.attrs[name] = String(value); if (name === "class") this.className = String(value); }
    getAttribute(name) { return this.attrs[name]; }
    contains(element) { return element === this || this.childNodes.some(child => child.contains(element)); }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    removeEventListener(name, handler) { this.listeners[name] = (this.listeners[name] || []).filter(value => value !== handler); }
    fire(name, event = {}) { (this.listeners[name] || []).forEach(handler => handler(event)); }
    focus() { this.ownerDocument.activeElement = this; this.fire("focus"); }
    querySelector(selector) {
      return this.all().find(child => selector === ".tl-graph-node[aria-pressed=true]"
        ? child.classList.contains("tl-graph-node") && child.getAttribute("aria-pressed") === "true"
        : child.classList.contains(selector.slice(1))) || null;
    }
    all() { return this.childNodes.flatMap(child => [child, ...child.all()]); }
  }
  const document = { activeElement: null, createElement: tag => new Element(tag, document), createElementNS: (_, tag) => new Element(tag, document) };
  const container = document.createElement("div");
  container.listenerCount = () => created.reduce((sum, item) => sum + Object.values(item.listeners).reduce((count, listeners) => count + listeners.length, 0), 0);
  return container;
}

test("SQL cards inspect raw literals safely, retain camera, and navigate the exact event", () => {
  const raw = "select '<img src=x onerror=alert(1)>' as literal\nfrom features where id = ?";
  const query = sqlNode("sql", {sql: raw, sqlTruncated: true, batchSize: 3});
  const input = model([node("ems"), query], [edge("ems", "sql", "outbound")], ["ems"]);
  const before = JSON.stringify(input), container = documentFixture(), opened = [];
  const stop = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: value => opened.push(value)});
  const card = () => container.all().find(value => value.dataset.nodeId === "sql");
  assert.match(card().textContent, /SQL QUERY.*PostgreSQL/);
  assert.equal(container.querySelector(".jdbc-sql"), null, "query is disclosed on demand");
  const viewport = container.querySelector(".tl-graph-viewport"); viewport.scrollLeft = 50; viewport.scrollTop = 75;
  card().fire("click");
  assert.equal(container.querySelector(".jdbc-sql").textContent, raw);
  assert.equal(container.querySelector(".jdbc-sql").tagName, "pre");
  assert.match(container.querySelector(".jdbc-evidence").textContent, /3 batch entries.*Raw SQL.*sensitive data.*SQL text truncated.*transaction commit/s);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollLeft, 50);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollTop, 75);
  const action = container.querySelector(".tl-graph-boundary-footer");
  assert.ok(container.querySelector(".tl-graph-details").contains(action));
  action.fire("click"); assert.equal(opened[0], query); assert.equal(opened[0].eventSeq, 12);
  container.querySelector(".tl-graph-inspector-close").fire("click"); assert.equal(container.querySelector(".jdbc-sql"), null);
  assert.ok(container.querySelector(".tl-graph-inspector-empty"));
  stop(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("SQL execution counts exclude query limit markers and never combine routing with execution", () => {
  const query = sqlNode("query", {system: "mysql", status: "ERROR", errorType: "java.sql.SQLException", sql: null});
  const limit = sqlNode("limit", {system: "mysql", evidenceKind: "jdbc-query-limit", status: "TRUNCATED",
    operation: "query-limit", queryLimit: 10, sql: "FORBIDDEN", durationMs: undefined});
  const input = model([node("app"), query, limit, databaseRoute("route")],
    [edge("app", "query", "outbound"), edge("app", "limit", "outbound"), edge("app", "route", "outbound")], ["app"]);
  const container = documentFixture(), stop = graph.render(container, input, {showNodeDetails: false});
  assert.match(container.textContent, /1 visible SQL execution/); assert.doesNotMatch(container.textContent, /2 visible SQL executions/);
  container.all().find(value => value.dataset.nodeId === "query").fire("click");
  assert.match(container.textContent, /Execution threw.*SQL text unavailable/s);
  container.all().find(value => value.dataset.nodeId === "limit").fire("click");
  assert.match(container.textContent, /SQL capture limit reached \(10 executions\).*not an executed query/s);
  assert.doesNotMatch(container.textContent, /FORBIDDEN/);
  stop();
});

test("legacy ClickHouse calls share one card with separate operations and exact calling-code links", () => {
  const input = capturedSql(3, {
    1: {system: "clickhouse", evidenceKind: "client-boundary", operation: "queryRecords", status: "SUBMITTED", eventSeq: 157, sql: "NOT_CAPTURED_SECRET", sqlParameters: [{value: "NOT_CAPTURED_SECRET"}]},
    2: {system: "clickhouse", evidenceKind: "client-boundary", operation: "getTableSchemaFromQuery", status: "OBSERVED", eventSeq: 1131},
    3: {system: "clickhouse", evidenceKind: "client-boundary", operation: "queryRecords", status: "SUBMITTED", eventSeq: 1138}
  });
  const before = JSON.stringify(input); freezeTree(input);
  const container = documentFixture(), opened = [];
  const stop = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: observation => opened.push(observation)});
  const card = () => container.all().find(value => value.dataset.nodeId === "query-1");
  assert.equal(container.all().filter(value => value.classList.contains("tl-graph-node")).length, 2);
  assert.match(card().textContent, /DB CALL.*ClickHouse.*3 client observations.*Inspect recorded calls/s);
  assert.doesNotMatch(card().textContent, /All returned|All responses completed|SQL executions/);
  assert.equal(container.querySelector(".tl-graph-boundary-source"), null);
  const viewport = container.querySelector(".tl-graph-viewport"); viewport.scrollLeft = 45; viewport.scrollTop = 70;
  card().fire("click");
  const calls = container.all().filter(value => value.classList.contains("tl-graph-sql-call"));
  assert.equal(calls.length, 3);
  assert.deepEqual(calls.map(call => call.querySelector(".tl-graph-sql-call-title").textContent), ["queryRecords", "getTableSchemaFromQuery", "queryRecords"]);
  assert.match(calls[0].textContent, /#157.*Submitted · completion not observed/);
  assert.match(calls[1].textContent, /#1131.*Client call observed/);
  calls.forEach(call => { call.open = true; call.fire("toggle"); call.querySelector(".tl-graph-boundary-source").fire("click"); });
  assert.deepEqual(opened, input.nodes.slice(1));
  opened.forEach((call, index) => assert.equal(call, input.nodes[index + 1]));
  assert.match(container.textContent, /SQL text and parameters were not captured/);
  assert.doesNotMatch(container.textContent, /NOT_CAPTURED_SECRET|Response completed|All returned/);
  assert.equal(container.querySelector(".jdbc-sql"), null);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollLeft, 45);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollTop, 70);
  container.querySelector(".tl-graph-inspector-close").fire("click");
  assert.equal(container.querySelector(".tl-graph-sql-group-evidence"), null);
  stop(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("mixed native SQL and ClickHouse client calls keep typed counts and distinct row evidence", () => {
  const input = capturedSql(2, {
    1: {system: "clickhouse", evidenceKind: "clickhouse-query", operation: "query", sqlSource: "native-query", completionKind: "response-complete", sql: "SELECT x FROM usage"},
    2: {system: "clickhouse", evidenceKind: "client-boundary", operation: "getTableSchema", status: "ERROR", errorType: "ClientFailure"}
  });
  const container = documentFixture(), stop = graph.render(container, input, {showNodeDetails: false});
  const card = container.all().find(value => value.dataset.nodeId === "query-1");
  assert.match(card.textContent, /1 SQL execution · 1 client observation/);
  assert.match(card.textContent, /1 client error/);
  assert.doesNotMatch(card.textContent, /All responses completed|2 SQL executions/);
  card.fire("click");
  const calls = container.all().filter(value => value.classList.contains("tl-graph-sql-call"));
  calls.forEach(call => { call.open = true; call.fire("toggle"); });
  assert.match(calls[0].textContent, /SELECT · usage.*Native ClickHouse query/s);
  assert.match(calls[1].textContent, /getTableSchema.*Client error.*SQL text and parameters were not captured.*ClientFailure/s);
  stop();
});

test("DB-call groups disclose individual SQL safely, preserve the camera and exact source identities", () => {
  const raw = "select '<img src=x onerror=alert(1)>' as literal\nfrom features where id = ?";
  const input = capturedSql(2, {1: {sql: raw, sqlTruncated: true, sqlParametersStatus: "captured",
    sqlParameters: [{index: 1, type: "String", value: "<img src=x onerror=alert(2)>", status: "captured"}]}}), before = JSON.stringify(input);
  freezeTree(input);
  const container = documentFixture(), opened = [], stop = graph.render(container, input,
    {showNodeDetails: false, onOpenBoundary: value => opened.push(value)});
  const card = () => container.all().find(value => value.dataset.nodeId === "query-1");
  assert.equal(container.all().filter(value => value.classList.contains("tl-graph-node")).length, 2);
  assert.match(card().textContent, /DB CALL.*PostgreSQL.*2 SQL executions.*All returned.*SQL text truncated/s);
  assert.equal(container.querySelector(".jdbc-sql"), null);
  assert.equal(container.querySelector(".tl-graph-boundary-source"), null, "no misleading group-level source");
  const viewport = container.querySelector(".tl-graph-viewport"); viewport.scrollLeft = 45; viewport.scrollTop = 70;
  card().fire("click");
  assert.equal(card().getAttribute("aria-pressed"), "true");
  const calls = container.all().filter(value => value.classList.contains("tl-graph-sql-call"));
  assert.equal(calls.length, 2); assert.equal(container.querySelector(".jdbc-sql"), null);
  assert.equal(container.querySelector(".tl-graph-sql-call-title").textContent, "SELECT · features");
  assert.doesNotMatch(calls[0].querySelector(".tl-graph-sql-summary").textContent, /executeQuery/);
  assert.match(container.querySelector(".tl-graph-sql-call-result").textContent, /#1.*Returned.*1 binding/);
  calls[0].open = true; calls[0].fire("toggle");
  assert.equal(container.querySelector(".jdbc-sql").textContent, raw);
  assert.equal(container.querySelector(".jdbc-sql").tagName, "pre");
  assert.match(container.querySelector(".jdbc-parameters").textContent, /#1 · String<img src=x onerror=alert\(2\)>/);
  assert.match(container.querySelector(".jdbc-evidence").textContent, /Raw SQL.*SQL text truncated.*transaction commit/s);
  const action = container.querySelector(".tl-graph-boundary-source");
  action.fire("click"); assert.equal(opened[0], input.nodes[1]);
  assert.equal(opened[0].callKey, "call-1"); assert.equal(opened[0].eventSeq, 1);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollLeft, 45);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollTop, 70);
  container.querySelector(".tl-graph-details").fire("keydown", {key: "Escape", preventDefault() {}});
  assert.equal(container.querySelector(".tl-graph-sql-group-evidence"), null);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollLeft, 45);
  assert.equal(container.querySelector(".tl-graph-viewport").scrollTop, 70);
  assert.ok(container.querySelector(".tl-graph-inspector-empty"));
  assert.equal(container.all().some(value => value.tagName === "img"), false);
  stop(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("SQL details default to inert captured-value previews and retain the original template", () => {
  const deps = require("../tracelens_cli/web/dependency-ui.js");
  const template = "select * from features where name=? and enabled=? and deleted_at=?";
  const boundary = sqlNode("q", {sql: template, sqlSource: "prepared", sqlParametersStatus: "captured", sqlParameters: [
    {index: 1, type: "String", status: "captured", value: "O'Brien <img src=x onerror=alert(1)>"},
    {index: 2, type: "Boolean", status: "captured", value: "true"},
    {index: 3, type: "Null", status: "null", value: null}
  ]});
  const before = JSON.stringify(boundary); freezeTree(boundary);
  const container = documentFixture(), panel = deps.renderJdbc(container.ownerDocument, boundary); container.append(panel);
  assert.equal(panel.querySelector(".jdbc-resolved").textContent,
    "select * from features where name='O''Brien <img src=x onerror=alert(1)>' and enabled=TRUE and deleted_at=NULL");
  assert.equal(panel.querySelector(".jdbc-template").textContent, template);
  assert.equal(panel.querySelector(".jdbc-original-template").tagName, "details");
  assert.ok(!panel.querySelector(".jdbc-original-template").open);
  assert.equal(panel.querySelector(".jdbc-parameter-details").tagName, "details");
  assert.match(panel.textContent, /SQL with captured values.*reconstructed display.*not the exact SQL/s);
  assert.equal(panel.all().some(el => el.tagName === "img"), false);
  assert.equal(JSON.stringify(boundary), before);
  assert.equal(container.listenerCount(), 0, "native disclosures do not retain event listeners");
});

test("prepared batches show separate reconstructed SQL and preserve uncertain bindings", () => {
  const deps = require("../tracelens_cli/web/dependency-ui.js");
  const container = documentFixture();
  const boundary = sqlNode("q", {operation: "executeBatch", sql: "insert into features(id) values(?)", sqlSource: "prepared",
    sqlParametersStatus: "batch", sqlParameterSets: [
      [{index: 1, type: "Int", status: "captured", value: "1"}],
      [{index: 1, type: "Int", status: "captured", value: "2"}],
      [{index: 1, type: "Blob", status: "unsupported", value: null}]
    ]});
  const panel = deps.renderJdbc(container.ownerDocument, boundary);
  const entries = panel.all().filter(el => el.classList.contains("jdbc-parameter-set"));
  assert.equal(entries.length, 3); assert.equal(entries[0].open, true); assert.equal(entries[1].open, false);
  assert.equal(entries[0].querySelector(".jdbc-resolved").textContent, "insert into features(id) values(1)");
  assert.equal(entries[1].querySelector(".jdbc-resolved").textContent, "insert into features(id) values(2)");
  assert.equal(entries[2].querySelector(".jdbc-sql").textContent, boundary.sql);
  assert.match(entries[2].textContent, /unsupported/i);
  const old = deps.renderJdbc(container.ownerDocument, sqlNode("old", {sql: "select * from features where id=?", sqlSource: "prepared"}));
  assert.equal(old.querySelector(".jdbc-resolved"), null);
  assert.equal(old.querySelector(".jdbc-sql").textContent, "select * from features where id=?");
  assert.match(old.textContent, /older recordings cannot recover/);
});

test("reconstructed SQL dependency loads before consumers in the browser", () => {
  const html = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.ok(html.indexOf('src="/static/sql-values.js"') >= 0);
  assert.ok(html.indexOf('src="/static/sql-values.js"') < html.indexOf('src="/static/dependency-ui.js"'));
});

test("grouping precedes the diagram node limit and paginates every retained SQL call without leaking handlers", () => {
  const input = capturedSql(120), container = documentFixture(), opened = [];
  const stop = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: value => opened.push(value)});
  assert.equal(container.all().filter(value => value.classList.contains("tl-graph-node")).length, 2);
  assert.match(container.textContent, /120 visible SQL executions/);
  container.all().find(value => value.dataset.nodeId === "query-1").fire("click");
  const rows = () => container.all().filter(value => value.classList.contains("tl-graph-sql-call"));
  const range = () => container.querySelector(".tl-graph-sql-range").textContent;
  const previous = container.querySelector(".tl-graph-sql-prev"), next = container.querySelector(".tl-graph-sql-next");
  assert.equal(rows().length, 50); assert.equal(range(), "Calls 1–50 of 120"); assert.equal(previous.disabled, true);
  const listenerCount = container.listenerCount();
  next.fire("click"); assert.equal(rows().length, 50); assert.equal(range(), "Calls 51–100 of 120");
  assert.equal(container.listenerCount(), listenerCount, "old page listeners were removed");
  assert.match(container.ownerDocument.activeElement.textContent, /#51/);
  next.fire("click"); assert.equal(rows().length, 20); assert.equal(range(), "Calls 101–120 of 120");
  assert.equal(next.disabled, true);
  rows()[19].open = true; rows()[19].fire("toggle");
  assert.equal(container.querySelector(".jdbc-sql").textContent, "select 120");
  container.querySelector(".tl-graph-boundary-source").fire("click");
  assert.equal(opened[0], input.nodes[120]);
  previous.fire("click"); previous.fire("click");
  assert.equal(container.querySelector(".jdbc-sql"), null);
  assert.equal(container.listenerCount(), listenerCount);
  stop(); assert.equal(container.listenerCount(), 0);
});

test("169 SQL boundaries across four services are grouped before the 96-node display limit without omissions", () => {
  const services = [["cb-app", 96], ["cb-ems", 9], ["cb-consumption-ledger", 47], ["cb-grant-orchestrator", 17]];
  const segments = services.map(([service]) => node(service, "segment", {service,
    serviceInstanceId: `${service}-instance`, segmentId: `${service}-segment`}));
  const queries = services.flatMap(([service, count]) => Array.from({length: count}, (_, index) =>
    sqlNode(`${service}-query-${index + 1}`, {service, ownerId: service,
      serviceInstanceId: `${service}-instance`, segmentId: `${service}-segment`,
      callKey: `${service}-call-${index + 1}`, eventSeq: index + 1, sql: `select ${index + 1}`})));
  const input = model([...segments, ...queries], queries.map(query => edge(query.ownerId, query.id, "outbound")),
    segments.map(segment => segment.id));
  assert.equal(queries.length, 169);
  assert.equal(graph.limits.nodes, 96);
  assert.ok(input.nodes.length > graph.limits.nodes);
  const before = JSON.stringify(input); freezeTree(input);
  const grouped = graph.groupDatabaseCalls(input), output = graph.layout(grouped.model);
  assert.equal(grouped.groups.length, 4);
  assert.equal(output.nodes.length, 8);
  assert.equal(output.omittedNodes, 0);
  assert.equal(output.omittedEdges, 0);
  const container = documentFixture(), stop = graph.render(container, input, {showNodeDetails: false});
  assert.equal(container.all().filter(value => value.classList.contains("tl-graph-node")).length, 8);
  assert.match(container.querySelector(".tl-graph-counts").textContent, /169 visible SQL executions/);
  assert.equal(container.querySelector(".tl-graph-coverage").childNodes.find(value => value.tagName === "summary").textContent, "Diagram guide");
  for (const [service, count] of services) {
    const group = grouped.groups.find(value => value.ownerId === service);
    assert.equal(group.nodes.length, count, `${service} retains every raw SQL execution`);
    assert.deepEqual(group.nodes, queries.filter(query => query.ownerId === service));
    assert.equal(output.nodes.find(value => value.id === group.id).node.sqlExecutionCount, count);
    assert.ok(output.nodes.some(value => value.id === service), `${service} invocation remains visible`);
    const card = container.all().find(value => value.dataset.nodeId === group.id);
    assert.match(card.textContent, new RegExp(`${count} SQL executions`), `${service} renders its complete SQL count`);
  }
  assert.equal(JSON.stringify(input), before);
  stop(); assert.equal(container.listenerCount(), 0);
});

test("DB-call summaries retain failures and capture limits separately from routing decisions", () => {
  const input = capturedSql(3, {2: {status: "ERROR", errorType: "java.sql.SQLException", sql: null},
    3: {evidenceKind: "jdbc-query-limit", operation: "query-limit", status: "TRUNCATED", queryLimit: 2, sql: "FORBIDDEN"}});
  input.nodes.push(databaseRoute("route", {ownerId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance",
    segmentId: "ems-segment", segmentKey: undefined}));
  input.edges.push(edge("ems", "route", "outbound"));
  const container = documentFixture(), stop = graph.render(container, input, {showNodeDetails: false});
  const group = container.all().find(value => value.dataset.nodeId === "query-1");
  assert.match(group.textContent, /2 SQL executions.*1 failed execution.*Capture limit reached/s);
  assert.ok(group.classList.contains("tl-graph-failed"));
  assert.match(container.all().find(value => value.dataset.nodeId === "route").textContent, /DB ROUTING DECISION/);
  group.fire("click");
  const calls = container.all().filter(value => value.classList.contains("tl-graph-sql-call"));
  assert.equal(calls.length, 3); assert.match(container.textContent, /Capture limit reached — this list contains only retained calls/);
  calls[1].open = true; calls[1].fire("toggle");
  assert.match(calls[1].textContent, /Execution threw.*SQL text unavailable/s);
  calls[2].open = true; calls[2].fire("toggle");
  assert.match(calls[2].textContent, /SQL capture limit reached \(2 executions\).*not an executed query/s);
  assert.doesNotMatch(container.textContent, /FORBIDDEN/);
  stop(); assert.equal(container.listenerCount(), 0);
});

test("expanded DB-call groups reserve space before following columns", () => {
  const input = capturedSql(), grouped = graph.groupDatabaseCalls(input).model;
  grouped.nodes.push(node("next")); grouped.edges.push(edge("query-1", "next"));
  const result = graph.layout(grouped, {expandedRouteId: "query-1"});
  const group = result.nodes.find(value => value.id === "query-1"), next = result.nodes.find(value => value.id === "next");
  assert.equal(group.width, 460); assert.equal(group.height, 544);
  assert.ok(next.x > group.x + group.width, "SQL list does not overlap the next column");
});

test("architecture glyphs and semantic edge labels remain decorative, distinct and inert", () => {
  const unsafe = '<script>alert("service")</script>';
  const owner = node("app", "segment", {service: unsafe, segmentId: "local"});
  const local = {ownerId: owner.id, service: unsafe, segmentId: owner.segmentId};
  const input = model([owner,
    node("db", "dependency-call", {...local, boundaryKind: "database-route", destination: "TEST_MASTER"}),
    node("queue", "dependency-call", {...local, boundaryKind: "messaging", direction: "publish", destination: "events"}),
    node("http", "http-call", {...local, peer: "api.example"}),
    node("external", "dependency-call", {...local, boundaryKind: "external", system: "database", destination: "external"})],
    ["db", "queue", "http", "external"].map(id => edge("app", id, "outbound")), ["app"]);
  const before = JSON.stringify(input), container = documentFixture();
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const signatures = [];
  for (const id of ["app", "db", "queue", "http", "external"]) {
    const card = container.all().find(item => item.dataset.nodeId === id);
    const icon = card.querySelector(".tl-graph-node-icon");
    assert.equal(icon.tagName, "svg");
    assert.equal(icon.getAttribute("aria-hidden"), "true");
    assert.equal(icon.getAttribute("focusable"), "false");
    assert.equal(icon.getAttribute("fill"), "none");
    assert.equal(icon.getAttribute("stroke"), "currentColor");
    assert.ok(card.querySelector(".tl-graph-node-heading").contains(icon));
    assert.ok(icon.childNodes.every(item => item.tagName === "path" && item.getAttribute("d")));
    signatures.push(icon.childNodes.map(item => item.getAttribute("d")).join("|"));
  }
  assert.equal(new Set(signatures).size, signatures.length, "boundary categories have distinct code-native glyphs");
  assert.equal(container.all().some(item => item.tagName === "script"), false);
  assert.ok(container.querySelector(".tl-graph-service-group-name").textContent.includes(unsafe));
  const labels = container.all().filter(item => item.classList.contains("tl-graph-edge-label"));
  assert.deepEqual(labels.map(item => item.textContent).sort(), ["DB route", "HTTP", "boundary", "publish"].sort());
  for (const [id, category] of [["db", "database"], ["queue", "queue"], ["http", "http"], ["external", "external"]]) {
    const path = container.all().find(item => item.classList.contains("tl-graph-edge") &&
      item.classList.contains(`tl-graph-link-${category}`));
    assert.ok(path, id);
    assert.ok(path.getAttribute("marker-end").endsWith(`-${category})`), id);
  }
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("repeated service frames show their separate API invocations without merging records", () => {
  const input = capturedHttp(), container = documentFixture();
  Object.assign(input.nodes[0], {method: "POST", path: "/grants"});
  Object.assign(input.nodes[2], {service: "cb-app", method: "GET", path: "/subscription"});
  const cleanup = graph.render(container, input, {showNodeDetails: false, showHttpHops: true});
  const notes = container.all().filter(item => item.classList.contains("tl-graph-service-group-note"));
  assert.deepEqual(notes.map(item => item.textContent).sort(), ["GET /subscription", "POST /grants"]);
  assert.equal(container.all().filter(item => item.classList.contains("tl-graph-service-group-name")).length, 2);
  cleanup();
});

test("service frames stay fixed while inspector evidence preserves exact service activation", () => {
  const input = capturedRoutes(), before = JSON.stringify(input), container = documentFixture(), opened = [];
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false, onSelect: value => opened.push(value)});
  const card = id => container.all().find(item => item.dataset.nodeId === id);
  const region = () => container.querySelector(".tl-graph-service-group");
  assert.equal(region().getAttribute("aria-hidden"), "true");
  assert.equal(region().parentNode, card("app").parentNode, "frame is a backdrop, not a new interactive owner");
  assert.equal(region().contains(card("app")), false);
  assert.match(region().textContent, /app.*Captured invocation.*local observations/);
  const height = Number.parseFloat(region().style.height);
  card("app").focus();
  card("app").fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement, card("route-early"));
  assert.deepEqual(opened, []);
  card("route-early").fire("click");
  assert.equal(Number.parseFloat(region().style.height), height);
  assert.equal(container.ownerDocument.activeElement, card("route-early"));
  assert.match(container.querySelector(".tl-graph-route-evidence").textContent, /Event #3.*Event #20/);
  assert.deepEqual(opened, []);
  card("app").fire("click");
  assert.equal(opened.length, 0);
  container.querySelector(".tl-graph-source").fire("click");
  assert.equal(opened.length, 1); assert.equal(opened[0], input.nodes[0]);
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("scheduler graph cards show explicit outcome and audit details, not completion as success", () => {
  for (const outcome of ["SUCCEEDED", "SKIPPED", "FAILED", "UNKNOWN", null]) {
    const container = documentFixture();
    graph.render(container, model([node("job", "segment", {entryChannel: "SCHEDULER", status: "COMPLETED",
      jobType: "compute_billing_metrics", jobId: "42", jobOutcome: outcome, jobAuditStatus: "IGNORED", scheduler: "chargebee"})]));
    const card = container.querySelector(".tl-graph-node");
    assert.match(card.textContent, new RegExp(`Job ${outcome || "outcome unknown"}`));
    assert.equal(card.className.includes("tl-graph-failed"), outcome === "FAILED");
    card.fire("click");
    assert.match(container.querySelector(".tl-graph-details").textContent, /Job typecompute_billing_metrics.*Job ID42.*Audit statusIGNORED/);
  }
});

test("renderer uses inert text, shows upstream errors, and service activation opens the exact segment", () => {
  const container = documentFixture();
  const malicious = '<img src=x onerror="alert(1)">';
  const app = node("app", "segment", { path: malicious, serviceInstanceId: "instance-1", segmentId: "segment-1" });
  const input = { ...model([app, node("call", "http-call", { downstreamState: "not-observed" })], [edge("app", "call", "outbound")], ["app"]), errors: [{ service: "downstream", error: "offline" }], partial: true };
  const selected = [];
  const cleanup = graph.render(container, input, { onSelect: value => selected.push(value) });
  assert.match(container.textContent, /Causal flow, not a shared time axis/);
  assert.match(container.textContent, /downstream: offline/);
  assert.ok(container.textContent.includes(malicious));
  assert.ok(!container.all().some(item => item.tagName === "img"));
  const button = container.all().find(item => item.dataset.nodeId === "app");
  button.fire("mouseenter"); button.focus();
  assert.equal(selected.length, 0);
  button.fire("click");
  assert.deepEqual(selected, []);
  container.querySelector(".tl-graph-source").fire("click");
  assert.deepEqual(selected, [app]);
  const call = container.all().find(item => item.dataset.nodeId === "call");
  call.focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /Unavailable — no downstream segment is linked/);
  assert.equal(container.querySelector(".tl-graph-source"), null);
  cleanup();
  assert.equal(container.childNodes.length, 0);
});

test("arrow-key navigation is stable across rerenders and does not automatically open sources", () => {
  const container = documentFixture();
  const input = model([node("app"), node("call", "http-call"), node("ems")], [edge("app", "call", "outbound"), edge("call", "ems")], ["app"]);
  graph.render(container, input, {showHttpHops: true});
  const start = container.all().find(item => item.dataset.nodeId === "app");
  start.focus();
  let prevented = false;
  start.fire("keydown", { key: "ArrowRight", preventDefault: () => { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "call");
  assert.equal(container.querySelector(".tl-graph-node[aria-pressed=true]").dataset.nodeId, "call");
  container.ownerDocument.activeElement.fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "ems");
  assert.equal(container.querySelector(".tl-graph-node[aria-pressed=true]").dataset.nodeId, "ems");
  graph.render(container, input, {showHttpHops: true});
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "ems");
  assert.equal(container.querySelector(".tl-graph-source"), null);
  graph.render(container, model([]));
  assert.equal(container.querySelector(".tl-graph-node"), null);
  assert.match(container.textContent, /No observed request-flow nodes/);
});

test("diagram cap does not reinterpret observed downstream evidence as missing", () => {
  const nodes = Array.from({ length: 256 }, (_, index) => node(`n${String(index).padStart(3, "0")}`, index === 23 ? "http-call" : "segment"));
  const edges = nodes.slice(1).map((item, index) => edge(nodes[index].id, item.id));
  const input = model(nodes, edges, [nodes[0].id]);
  const output = graph.layout(input);
  const terminalVisible = output.nodes.find(item => item.id === "n023");
  assert.equal(terminalVisible.downstreamObserved, true);
  const container = documentFixture();
  graph.render(container, input);
  assert.match(container.textContent, /Diagram limit: showing 24 of 256 nodes/);
  const call = container.all().find(item => item.dataset.nodeId === "n023");
  assert.match(call.textContent, /Downstream observed/);
});

test("zero-incoming roots with unresolved parents are not claimed to be request entries", () => {
  const container = documentFixture();
  graph.render(container, model([node("orphan", "segment", { parentSpanId: "missing-parent" })], [], ["orphan"]));
  const card = container.all().find(item => item.dataset.nodeId === "orphan");
  assert.doesNotMatch(card.textContent, /NO INCOMING LINK|ENTRY|ROOT/);
  card.fire("click");
  assert.match(container.querySelector(".tl-graph-details").textContent, /this does not prove request entry/);
});

test("untraced HTTP dependency is named and stays visible without source drill-down", () => {
  const container = documentFixture();
  graph.render(container, model([node("app"), node("ems", "http-call", {peer: "127.0.0.1:8082", destinationName: "cb-ems", path: "/features", status: "COMPLETED", httpStatus: 200})], [edge("app", "ems", "outbound")], ["app"]));
  const card = container.all().find(item => item.dataset.nodeId === "ems");
  assert.match(card.textContent, /cb-ems/);
  assert.match(card.textContent, /Remote execution not observed/);
  card.focus();
  assert.equal(container.querySelector(".tl-graph-source"), null);
  assert.match(container.querySelector(".tl-graph-details").textContent, /127.0.0.1:8082/);
});

test("database routing has its own color class without reclassifying queue or external evidence", () => {
  const input = model([
    node("app"), databaseRoute("db"),
    node("sqs", "dependency-call", {boundaryKind: "messaging", direction: "publish", system: "sqs",
      destination: "ems-events", operation: "sendMessage", status: "SUBMITTED", evidenceKind: "client-boundary"}),
    node("external", "dependency-call", {boundaryKind: "external", system: "database", destination: "TEST_MASTER"}),
    node("unknown", "dependency-call", {system: "database", destination: "TEST_MASTER"}),
    node("http", "http-call", {boundaryKind: "database-route", peer: "database.example"}),
    node("segment", "segment", {boundaryKind: "database-route"})
  ], [edge("app", "db", "outbound"), edge("app", "sqs", "outbound")], ["app"]);
  const before = JSON.stringify(input), container = documentFixture();
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const card = id => container.all().find(item => item.dataset.nodeId === id);
  assert.equal(card("db").classList.contains("tl-graph-database-route"), true);
  assert.equal(card("db").classList.contains("tl-graph-dependency-call"), true);
  for (const id of ["sqs", "external", "unknown", "http", "segment"]) {
    assert.equal(card(id).classList.contains("tl-graph-database-route"), false, `${id} is not a database-routing observation`);
  }
  for (const id of ["sqs", "external", "unknown"]) assert.equal(card(id).classList.contains("tl-graph-dependency-call"), true);
  assert.match(card("db").textContent, /DB ROUTING DECISION.*TEST_MASTER.*OBSERVED/);
  assert.match(card("sqs").textContent, /QUEUE PUBLISH.*ems-events.*sqs.*sendMessage.*SUBMITTED/);
  assert.match(card("db").getAttribute("aria-description"), /no database query or network success implied/);
  assert.match(card("sqs").getAttribute("aria-description"), /broker delivery and remote processing are not implied/);
  assert.equal(container.querySelector(".tl-graph-legend-database").textContent, "Database call / routing decision");
  assert.equal(container.querySelector(".tl-graph-legend-queue").textContent, "Queue / messaging");
  assert.equal(container.querySelector(".tl-graph-legend-dependency").textContent, "Other external boundary");
  assert.equal(card("sqs").classList.contains("tl-graph-queue"), true);
  for (const id of ["app", "db", "external", "unknown", "http", "segment"]) {
    assert.equal(card(id).classList.contains("tl-graph-queue"), false, `${id} is not a messaging observation`);
  }
  assert.equal(JSON.stringify(input), before, "color classification does not modify captured evidence");
  cleanup();
});

test("grouped database cards keep database styling when their exact event evidence is expanded", () => {
  const input = capturedRoutes(), before = JSON.stringify(input), container = documentFixture();
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const card = () => container.all().find(item => item.dataset.nodeId === "route-early");
  assert.equal(container.all().some(item => item.dataset.nodeId === "route-late"), false);
  assert.equal(card().classList.contains("tl-graph-database-route"), true);
  assert.match(card().textContent, /2 recorded routing decisions/);
  card().fire("click");
  assert.equal(card().classList.contains("tl-graph-database-route"), true);
  assert.equal(card().getAttribute("aria-pressed"), "true");
  const evidence = container.querySelector(".tl-graph-route-evidence");
  assert.match(evidence.textContent, /Event #3.*http-worker.*Event #20.*queue-worker/);
  assert.match(evidence.textContent, /not SQL queries, connections or a shared timeline/);
  container.querySelector(".tl-graph-inspector-close").fire("click");
  assert.equal(card().classList.contains("tl-graph-database-route"), true);
  assert.equal(container.querySelector(".tl-graph-route-evidence"), null);
  assert.equal(JSON.stringify(input), before);
  cleanup();
});

test("architecture cards, legend and links keep services blue, database purple and queues orange in both themes", () => {
  // Forced-color mode deliberately replaces the category palette with system colors.
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/trace-graph.css"), "utf8").split("@media (forced-colors: active)")[0];
  const rule = selector => {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const match = [...css.matchAll(new RegExp(`${escaped}\\s*\\{([^}]+)\\}`, "g"))].at(-1);
    assert.ok(match, `CSS rule exists for ${selector}`);
    return match[1];
  };
  for (const selector of [".tl-graph", '.tl-graph[data-color-scheme="light"]']) {
    const declarations = rule(selector);
    const value = name => declarations.match(new RegExp(`--tg-${name}:\\s*([^;]+);`))?.[1].trim();
    for (const suffix of ["", "-soft", "-border"]) {
      const colors = ["blue", "purple", "orange", "amber", "teal"].map(name => value(name + suffix));
      assert.ok(colors.every(Boolean));
      assert.equal(new Set(colors).size, colors.length, `${selector} keeps each boundary category distinct`);
    }
  }
  for (const [selector, color] of [[".tl-graph-node", "blue"], [".tl-graph-node.tl-graph-http-call", "amber"],
    [".tl-graph-node.tl-graph-database-route", "purple"], [".tl-graph-node.tl-graph-queue", "orange"],
    [".tl-graph-node.tl-graph-dependency-call", "teal"]]) {
    assert.match(rule(selector), new RegExp(`--tg-node-accent:\\s*var\\(--tg-${color}\\)`));
    assert.match(rule(selector), new RegExp(`--tg-node-soft:\\s*var\\(--tg-${color}-soft\\)`));
    assert.match(rule(selector), new RegExp(`--tg-node-border:\\s*var\\(--tg-${color}-border\\)`));
  }
  assert.match(rule(".tl-graph-node"), /border:\s*1px solid var\(--tg-node-border\)/);
  assert.doesNotMatch(rule(".tl-graph-node"), /border-left/);
  assert.match(rule(".tl-graph-node-kind"), /color:\s*var\(--tg-node-accent\)/);
  assert.match(rule(".tl-graph-route-evidence"), /var\(--tg-purple\)/);
  assert.match(rule(".tl-graph-route-evidence:focus-visible"), /var\(--tg-purple\)/);
  for (const [kind, color] of [["database", "purple"], ["queue", "orange"], ["dependency", "teal"]]) {
    assert.match(rule(`.tl-graph-legend-${kind}::before`), new RegExp(`background:\\s*var\\(--tg-${color}-soft\\)`));
    assert.match(rule(`.tl-graph-legend-${kind}::before`), new RegExp(`var\\(--tg-${color}\\)`));
  }
  for (const [kind, color] of [["service", "blue"], ["http", "amber"], ["database", "purple"], ["queue", "orange"], ["external", "teal"]]) {
    assert.match(rule(`.tl-graph-edge.tl-graph-link-${kind}`), new RegExp(`stroke:\\s*var\\(--tg-${color}\\)`));
    assert.match(rule(`.tl-graph-arrow.tl-graph-link-${kind}`), new RegExp(`fill:\\s*var\\(--tg-${color}\\)`));
    assert.match(rule(`.tl-graph-edge-label.tl-graph-link-${kind}`), new RegExp(`--tg-link-label:\\s*var\\(--tg-${color}\\)`));
  }
  assert.match(rule(".tl-graph-edge-label text"), /fill:\s*var\(--tg-link-label\)/);
  assert.match(rule(".tl-graph-service-group"), /pointer-events:\s*none/);
  assert.match(rule(".tl-graph-service-group"), /border:\s*1px dashed/);
  assert.match(rule(".tl-graph-canvas"), /background-image:\s*linear-gradient/);
  assert.match(rule(".tl-graph-canvas"), /background-size:\s*28px 28px/);
});

test("queue consume points into local segment; publish points out without inferred consumer", () => {
  const input = model([node("app"),
    node("consume", "dependency-call", {boundaryKind: "messaging", direction: "consume", system: "queueman", destination: "usage", evidenceKind: "client-boundary", status: "OBSERVED"}),
    node("publish", "dependency-call", {boundaryKind: "messaging", direction: "publish", system: "kafka", destination: "metrics", evidenceKind: "client-boundary", status: "SUBMITTED"})],
    [edge("consume", "app", "inbound"), edge("app", "publish", "outbound")], ["consume"]);
  const layout = graph.layout(input);
  assert.deepEqual(layout.nodes.map(n => [n.id, n.rank]), [["consume", 0], ["app", 1], ["publish", 2]]);
  const container = documentFixture();
  graph.render(container, input);
  assert.match(container.textContent, /QUEUE CONSUME/);
  assert.match(container.textContent, /QUEUE PUBLISH/);
  container.all().find(item => item.dataset.nodeId === "publish").focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /delivery and remote processing are not implied/);
  assert.equal(container.querySelector(".tl-graph-source"), null);
});

test("missing messaging direction and evidence never become publish or wrapper claims", () => {
  for (const direction of [null, undefined, "INVALID", "outbound"]) {
    const container = documentFixture();
    graph.render(container, model([node("boundary", "dependency-call", {boundaryKind: "messaging", direction,
      destination: "orders", evidenceKind: null, status: null})]));
    const card = container.all().find(item => item.dataset.nodeId === "boundary");
    assert.match(card.textContent, /MESSAGING BOUNDARY/);
    assert.doesNotMatch(card.textContent, /QUEUE PUBLISH|QUEUE CONSUME|EXTERNAL WRAPPER/);
    card.focus();
    const detail = container.querySelector(".tl-graph-details").textContent;
    assert.match(detail, /evidence kind unknown/);
    assert.doesNotMatch(detail, /Configured wrapper entry|delivery\/handler/);
  }
});

test("generic client boundaries are not presented as configured wrapper entries", () => {
  const container = documentFixture();
  graph.render(container, model([node("client", "dependency-call", {boundaryKind: "external", direction: "outbound",
    system: "gateway", evidenceKind: "client-boundary", status: "ATTEMPTED"})]));
  const card = container.all().find(item => item.dataset.nodeId === "client");
  assert.match(card.textContent, /EXTERNAL BOUNDARY/);
  assert.doesNotMatch(card.textContent, /EXTERNAL WRAPPER/);
  card.focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /Local client boundary/);
});

test("explicit database and cache clients have named distinct cards without inferred receiver nodes", () => {
  const container = documentFixture();
  const input = model([node("app"),
    node("db", "dependency-call", {boundaryKind: "database", system: "clickhouse", operation: "queryRecords", evidenceKind: "client-boundary", status: "SUBMITTED"}),
    node("cache", "dependency-call", {boundaryKind: "cache", system: "redis", operation: "get", evidenceKind: "client-boundary", status: "OBSERVED"})],
    [edge("app", "db", "outbound"), edge("app", "cache", "outbound")]);
  graph.render(container, input);
  const db = container.all().find(item => item.dataset.nodeId === "db");
  const cache = container.all().find(item => item.dataset.nodeId === "cache");
  assert.match(db.textContent, /DATABASE CALL.*ClickHouse/);
  assert.match(cache.textContent, /CACHE CALL.*Redis \/ Valkey/);
  assert.match(db.className, /tl-graph-database-route/);
  assert.match(cache.className, /tl-graph-cache/);
  assert.doesNotMatch(db.textContent, /routing decision|Show events/);
  assert.equal(graph.layout(input).nodes.length, 3);
  cache.focus();
  assert.match(container.querySelector(".tl-graph-details").textContent, /cache hit\/miss and remote execution are not inferred/);
});

test("database and unknown cache evidence never acquire client call labels", () => {
  for (const boundaryKind of ["database", "cache"]) for (const evidenceKind of [null, "unknown", "wrapper-entry"]) {
    const container = documentFixture();
    graph.render(container, model([node("app"), node("dep", "dependency-call", {boundaryKind, evidenceKind, system: "clickhouse"})],
      [edge("app", "dep", "outbound")]));
    const card = container.all().find(item => item.dataset.nodeId === "dep");
    if (boundaryKind === "cache" && evidenceKind === "wrapper-entry") assert.match(card.className, /tl-graph-cache/);
    else assert.doesNotMatch(card.className, /tl-graph-cache|tl-graph-database-route/);
    assert.doesNotMatch(card.textContent, /DATABASE CALL|CACHE CALL/);
    assert.equal(container.all().some(item => item.textContent === "DB call"), false);
  }
});

test("typed cache wrapper access is cyan with weaker evidence and retains original source action identity", () => {
  const observation = node("cache", "dependency-call", {boundaryKind: "cache", system: "redis", operation: "get",
    evidenceKind: "wrapper-entry", status: "OBSERVED", eventSeq: 292, service: "cb-ems", segmentId: "ems-segment"});
  const input = model([node("ems"), observation], [edge("ems", "cache", "outbound")]);
  const container = documentFixture(), opened = [];
  const cleanup = graph.render(container, input, {onOpenBoundary: value => opened.push(value)});
  const card = container.all().find(item => item.dataset.nodeId === "cache");
  assert.match(card.className, /tl-graph-cache/); assert.match(card.textContent, /CACHE ACCESS.*Redis \/ Valkey/);
  assert.doesNotMatch(card.textContent, /CACHE CALL|HIT|MISS/);
  assert.match(card.title, /Observed cache wrapper invocation.*not established/);
  card.focus(); assert.equal(opened.length, 0);
  assert.match(container.querySelector(".tl-graph-details").textContent, /cache hit\/miss and remote execution are not established/);
  assert.equal(graph.layout(input).edges[0].label, "cache access");
  container.querySelector(".tl-graph-boundary-source").fire("click");
  assert.equal(opened[0], observation);
  cleanup();
});

test("consume boundary alone does not claim a message was received", () => {
  const container = documentFixture();
  graph.render(container, model([node("consumer", "dependency-call", {boundaryKind: "messaging", direction: "consume",
    system: "sqs", operation: "receiveMessage", evidenceKind: "client-boundary", status: "OBSERVED"})]));
  container.all().find(item => item.dataset.nodeId === "consumer").focus();
  const details = container.querySelector(".tl-graph-details").textContent;
  assert.match(details, /message receipt, producer and processing completion are not inferred/);
  assert.doesNotMatch(details, /delivery\/handler/);
});

test("presentation folds only the explicit HTTP hop, retaining original canonical records and timings", () => {
  const input = capturedHttp(), before = JSON.stringify(input);
  const shown = graph.presentation(input);
  assert.deepEqual(shown.model.nodes.map(item => item.id), ["app", "ems"]);
  assert.equal(shown.foldedHops.length, 1);
  assert.equal(shown.foldedHops[0].call, input.nodes[1]);
  assert.equal(shown.foldedHops[0].outbound, input.edges[0]);
  assert.equal(shown.foldedHops[0].observed, input.edges[1]);
  assert.equal(shown.model.nodes[1], input.nodes[2]);
  assert.deepEqual(shown.model.edges.map(item => [item.from, item.to, item.kind, item.foldedCallId]), [["app", "ems", "observed-call", "call"]]);
  assert.equal(shown.model.nodes[1].durationMs, 7); assert.equal(shown.foldedHops[0].call.durationMs, 14);
  assert.equal(graph.layout(shown.model).omittedNodes, 0); assert.equal(graph.layout(shown.model).omittedEdges, 0);
  assert.equal(graph.layout(input).nodes.length, 3, "Canonical layout and exported model stay raw");
  assert.equal(JSON.stringify(input), before);
});

test("folding replaces only the linked HTTP hop while every other boundary and direction survives exactly", () => {
  const input = capturedHttp();
  const database = (id, destination) => node(id, "dependency-call", {boundaryKind: "database-route", direction: "outbound",
    system: "database", destination, operation: "route", status: "OBSERVED", evidenceKind: "routing-marker", ownerId: "ems"});
  const boundaries = [database("db-master-1", "TEST_MASTER"), database("db-master-2", "TEST_MASTER"), database("db-slave", "TEST_SLAVE"),
    node("publish", "dependency-call", {boundaryKind: "messaging", direction: "publish", system: "sqs",
      destination: "ems-events", operation: "sendMessage", status: "SUBMITTED", evidenceKind: "client-boundary", ownerId: "ems"}),
    node("consume", "dependency-call", {boundaryKind: "messaging", direction: "consume", system: "kafka",
      destination: "usage", operation: "poll", status: "OBSERVED", evidenceKind: "client-boundary", ownerId: "app"}),
    node("external", "dependency-call", {boundaryKind: "external", direction: "outbound", system: "redis",
      destination: "cache.example:6379", operation: "get", status: "OBSERVED", evidenceKind: "client-boundary", ownerId: "ems"}),
    node("untraced-http", "http-call", {ownerId: "app", segmentId: "app-segment", peer: "external.example",
      method: "GET", path: "/health", downstreamState: "not-observed", httpStatus: 503, durationMs: 22})];
  const preservedEdges = [edge("ems", "db-master-1", "outbound"), edge("ems", "db-master-2", "outbound"),
    edge("ems", "db-slave", "outbound"), edge("ems", "publish", "outbound"), edge("consume", "app", "inbound"),
    edge("ems", "external", "outbound"), edge("app", "untraced-http", "outbound")];
  input.nodes.push(...boundaries); input.edges.push(...preservedEdges); input.roots = ["consume"];
  const before = JSON.stringify(input), shown = graph.presentation(input);
  assert.equal(shown.foldedHops.length, 1);
  assert.deepEqual(shown.model.nodes, input.nodes.filter(item => item.id !== "call"));
  for (const original of input.nodes.filter(item => item.id !== "call")) {
    assert.equal(shown.model.nodes.find(item => item.id === original.id), original, `Preserve exact node ${original.id}`);
  }
  assert.deepEqual(shown.model.edges.filter(item => !item.foldedCallId), preservedEdges);
  for (const original of preservedEdges) {
    assert.equal(shown.model.edges.find(item => item.id === original.id), original, `Preserve exact edge ${original.id}`);
  }
  assert.equal(shown.model.edges.length, input.edges.length - 1);
  assert.deepEqual(shown.model.edges.filter(item => item.foldedCallId).map(item => [item.from, item.to, item.kind, item.foldedCallId]),
    [["app", "ems", "observed-call", "call"]]);
  assert.equal(shown.model.edges.some(item => item.id === input.edges[0].id || item.id === input.edges[1].id), false);
  assert.equal(shown.foldedHops[0].outbound, input.edges[0]); assert.equal(shown.foldedHops[0].observed, input.edges[1]);
  assert.deepEqual(shown.model.roots, ["consume"]);
  assert.equal(graph.layout(shown.model).omittedNodes, 0); assert.equal(graph.layout(shown.model).omittedEdges, 0);
  assert.equal(JSON.stringify(input), before, "Canonical graph and all original boundary metadata remain untouched");
});

test("same-name service invocations stay distinct, including different instance identities", () => {
  const first = capturedHttp(), app = first.nodes[0];
  const secondCall = {...first.nodes[1], id: "call-2", callKey: "second-call"};
  const secondEms = {...first.nodes[2], id: "ems-2", segmentId: "ems-segment-2", serviceInstanceId: "ems-instance-2"};
  const input = model([app, first.nodes[1], first.nodes[2], secondCall, secondEms],
    [...first.edges, edge("app", "call-2", "outbound"), edge("call-2", "ems-2")], ["app"]);
  const shown = graph.presentation(input);
  assert.equal(shown.foldedHops.length, 2);
  assert.deepEqual(shown.model.nodes.filter(item => item.service === "cb-ems").map(item => [item.id, item.segmentId, item.serviceInstanceId]),
    [["ems", "ems-segment", "ems-instance"], ["ems-2", "ems-segment-2", "ems-instance-2"]]);
  assert.equal(graph.presentation(model([first.nodes[2], secondEms])).foldedHops.length, 0, "A matching name does not establish a link");
  const sameInstance = model(input.nodes.map(item => item.id === "ems-2" ? {...item, serviceInstanceId: "ems-instance"} : item), input.edges, input.roots);
  assert.equal(graph.presentation(sameInstance).model.nodes.filter(item => item.service === "cb-ems").length, 2);
});

test("ambiguous, extra, malformed, contradictory and cyclic links remain expanded", () => {
  const scenarios = [
    ["second caller", input => { input.nodes.push(node("other")); input.edges.push(edge("other", "call", "outbound")); }],
    ["second receiver", input => { input.nodes.push(node("other")); input.edges.push(edge("call", "other")); }],
    ["extra call boundary", input => { input.nodes.push(node("other", "dependency-call")); input.edges.push(edge("call", "other", "outbound")); }],
    ["ambiguous target parent", input => { input.nodes.push(node("other")); input.edges.push(edge("other", "ems")); }],
    ["self receiver", input => { input.edges[1].to = "app"; }],
    ["cycle", input => { input.edges.push(edge("ems", "app")); }],
    ["duplicate node", input => { input.nodes.push({...input.nodes[1]}); }],
    ["duplicate edge id", input => { input.edges[1].id = input.edges[0].id; }],
    ["dangling extra edge", input => { input.edges.push(edge("call", "missing")); }],
    ["unknown edge kind", input => { input.edges[0].kind = "guessed"; }],
    ["no outbound evidence", input => { input.edges[0].kind = "observed-call"; }],
    ["no observed receiver", input => { input.edges[1].kind = "outbound"; }],
    ["contradictory owner", input => { input.nodes[1].ownerId = "ems"; }],
    ["contradictory caller segment", input => { input.nodes[1].segmentId = "different"; }],
    ["contradictory downstream state", input => { input.nodes[1].downstreamState = "not-observed"; }],
    ["HTTP node listed as root", input => { input.roots.push("call"); }],
    ["receiver listed as root", input => { input.roots.push("ems"); }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capturedHttp(); mutate(input);
    const shown = graph.presentation(input);
    assert.equal(shown.foldedHops.length, 0, name); assert.equal(shown.model, input, name);
  }
});

test("presentation never assumes topology beyond bounded inputs and avoids derived-edge identity collisions", () => {
  const input = capturedHttp();
  const oversized = {...input, nodes: [...input.nodes, ...Array.from({length: graph.limits.inputNodes}, (_, n) => node(`extra${n}`))]};
  assert.equal(graph.presentation(oversized).foldedHops.length, 0);
  assert.equal(graph.presentation({...input, edges: Array(graph.limits.inputEdges + 1).fill(input.edges[0])}).foldedHops.length, 0);
  const collision = "presentation:" + JSON.stringify(input.edges.map(item => item.id));
  input.nodes.push(node("other")); input.edges.push(edge("app", "other", "observed-call", collision));
  const shown = graph.presentation(input);
  assert.equal(shown.foldedHops.length, 1);
  assert.equal(new Set(shown.model.edges.map(item => item.id)).size, shown.model.edges.length);
});

test("DB-route grouping is presentation-only and retains every original observation and raw event", () => {
  const input = capturedRoutes();
  input.events = [{type: "DB_ROUTE", seq: 3, thread: "http-worker"}, {type: "DB_ROUTE", seq: 20, thread: "queue-worker"}];
  const before = JSON.stringify(input); freezeTree(input);
  const shown = graph.groupDatabaseRoutes(input);
  assert.equal(shown.groups.length, 1);
  assert.equal(shown.groups[0].id, "route-early"); assert.equal(shown.groups[0].ownerId, "app");
  assert.deepEqual(shown.groups[0].nodes, [input.nodes[2], input.nodes[1]]);
  assert.equal(shown.groups[0].nodes[0], input.nodes[2]); assert.equal(shown.groups[0].nodes[1], input.nodes[1]);
  assert.deepEqual(shown.model.nodes.map(item => item.id), ["app", "route-early"]);
  assert.equal(shown.model.nodes[0], input.nodes[0]);
  assert.notEqual(shown.model.nodes[1], input.nodes[2], "the representative is a presentation copy");
  for (const field of ["eventSeq", "thread", "callKey"]) assert.equal(shown.model.nodes[1][field], null);
  assert.equal(shown.model.nodes[1].durationMs, undefined, "grouping does not synthesize a duration");
  assert.deepEqual(shown.model.edges, [input.edges[1]]); assert.equal(shown.model.edges[0], input.edges[1]);
  assert.equal(shown.model.events, input.events);
  assert.equal(graph.layout(input).nodes.length, 3); assert.equal(graph.layout(shown.model).nodes.length, 2);
  assert.equal(graph.layout(shown.model).omittedNodes, 0);
  assert.equal(graph.presentation(input).model, input, "the separate HTTP presentation API stays unchanged");
  assert.equal(JSON.stringify(input), before);
});

test("DB-route summaries never merge distinct owners, destinations or recorded boundary metadata", () => {
  const changes = {destination: "TEST_SLAVE", destinationName: "Replica pool", system: "other-database", direction: "inbound",
    evidenceKind: "client-boundary", operation: "query", status: "SUBMITTED", service: "other-service",
    serviceInstanceId: "other-instance", segmentId: "other-segment", segmentKey: "other-key", downstreamState: "observed", truncated: true};
  for (const [field, value] of Object.entries(changes)) {
    const input = capturedRoutes(); input.nodes[1][field] = value;
    const shown = graph.groupDatabaseRoutes(input);
    assert.equal(shown.groups.length, 0, field); assert.equal(shown.model, input, field);
  }
  const input = capturedRoutes();
  input.nodes.push({...input.nodes[0], id: "second-owner"});
  input.nodes[1].ownerId = "second-owner"; input.edges[0].from = "second-owner";
  assert.equal(graph.groupDatabaseRoutes(input).groups.length, 0, "identical service and segment labels do not replace explicit owner identity");
  const missingIdentity = capturedRoutes(); delete missingIdentity.nodes[1].segmentId;
  assert.equal(graph.groupDatabaseRoutes(missingIdentity).groups.length, 0, "unknown identity is not silently equated with recorded identity");
});

test("only safe canonical outbound DB-route leaves can be grouped", () => {
  const scenarios = [
    ["missing owner", input => { delete input.nodes[1].ownerId; }],
    ["owner mismatch", input => { input.nodes[1].ownerId = "route-early"; }],
    ["missing outbound edge", input => { input.edges.shift(); }],
    ["wrong edge kind", input => { input.edges[0].kind = "inbound"; }],
    ["multiple incoming edges", input => { input.edges.push(edge("app", "route-late", "outbound", "extra")); }],
    ["non-leaf route", input => { input.nodes.push(node("receiver")); input.edges.push(edge("route-late", "receiver")); }],
    ["route listed as root", input => { input.roots.push("route-late"); }],
    ["cycle at owner", input => { input.edges.push(edge("app", "app")); }],
    ["missing direction", input => { delete input.nodes[1].direction; }],
    ["missing evidence", input => { delete input.nodes[1].evidenceKind; }],
    ["empty destination", input => { input.nodes[1].destination = "   "; }],
    ["non-text destination", input => { input.nodes[1].destination = 123; }],
    ["oversized destination", input => { input.nodes[1].destination = "d".repeat(513); }],
    ["timed boundary", input => { input.nodes[1].durationMs = 0; }],
    ["HTTP status", input => { input.nodes[1].httpStatus = 200; }],
    ["error evidence", input => { input.nodes[1].errorType = "DatabaseFailure"; }],
    ["ambiguous call key", input => { input.nodes[1].callKey = input.nodes[2].callKey; }],
    ["other dependency kind", input => { input.nodes[1].boundaryKind = "messaging"; }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capturedRoutes(); mutate(input); const before = JSON.stringify(input);
    const shown = graph.groupDatabaseRoutes(input);
    assert.equal(shown.groups.length, 0, name); assert.equal(shown.model, input, name);
    assert.equal(JSON.stringify(input), before, name);
  }
});

test("invalid or oversized topology keeps DB-route observations ungrouped", () => {
  const scenarios = [
    ["duplicate node", input => { input.nodes.push({...input.nodes[1]}); }],
    ["unknown node kind", input => { input.nodes.push(node("invalid", "unknown")); }],
    ["duplicate edge identity", input => { input.edges[1].id = input.edges[0].id; }],
    ["unknown edge kind", input => { input.edges[1].kind = "inferred"; }],
    ["dangling edge", input => { input.edges.push(edge("app", "absent")); }],
    ["missing root", input => { input.roots.push("absent"); }],
    ["node input cap", input => { input.nodes.push(...Array.from({length: graph.limits.inputNodes}, (_, i) => node(`extra-${i}`))); }],
    ["edge input cap", input => { input.edges = Array(graph.limits.inputEdges + 1).fill(input.edges[0]); }],
    ["root input cap", input => { input.roots = Array(graph.limits.inputNodes + 1).fill("app"); }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capturedRoutes(); mutate(input); const shown = graph.groupDatabaseRoutes(input);
    assert.equal(shown.groups.length, 0, name); assert.equal(shown.model, input, name);
  }
  for (const input of [null, {}, model([])]) assert.equal(graph.groupDatabaseRoutes(input).model, input);
});

test("route member ordering is deterministic by numeric local sequence with unknown sequences last", () => {
  const input = capturedRoutes();
  input.nodes = [input.nodes[0], databaseRoute("z-later", {eventSeq: 12}), databaseRoute("b-first", {eventSeq: "2"}),
    databaseRoute("a-first", {eventSeq: 2}), databaseRoute("unknown", {eventSeq: null})];
  input.edges = input.nodes.slice(1).map(item => edge("app", item.id, "outbound"));
  const first = graph.groupDatabaseRoutes(input);
  const reversed = graph.groupDatabaseRoutes({...input, nodes: [...input.nodes].reverse(), edges: [...input.edges].reverse()});
  assert.deepEqual(first.groups[0].nodes.map(item => item.id), ["a-first", "b-first", "z-later", "unknown"]);
  assert.deepEqual(reversed.groups, first.groups);
  assert.deepEqual(graph.layout(reversed.model), graph.layout(first.model));
});

test("expanding a route reserves inline evidence space without changing nodes or causal links", () => {
  const input = capturedRoutes();
  input.nodes.push(databaseRoute("route-other", {destination: "TEST_SLAVE"}));
  input.edges.push(edge("app", "route-other", "outbound"));
  const grouped = graph.groupDatabaseRoutes(input).model, before = JSON.stringify(grouped);
  const compact = graph.layout(grouped), expanded = graph.layout(grouped, {expandedRouteId: "route-early"});
  assert.deepEqual(expanded.nodes.map(item => item.id), compact.nodes.map(item => item.id));
  assert.deepEqual(expanded.edges.map(item => [item.id, item.from, item.to, item.kind]), compact.edges.map(item => [item.id, item.from, item.to, item.kind]));
  const route = expanded.nodes.find(item => item.id === "route-early");
  assert.equal(route.height, 144 + 220);
  for (const other of expanded.nodes.filter(item => item.rank === route.rank && item.id !== route.id)) {
    assert.ok(other.y >= route.y + route.height || route.y >= other.y + other.height);
  }
  assert.equal(expanded.edges.find(item => item.to === route.id).path.endsWith(`V ${route.y + 72} H ${route.x}`), true,
    "connectors remain anchored at the route header, not the evidence midpoint");
  assert.equal(JSON.stringify(grouped), before);
});

test("grouped routes inspect exact local events without navigation and clean up replaced listeners", () => {
  const input = capturedRoutes(), before = JSON.stringify(input), container = documentFixture(), opened = [];
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false, onSelect: item => opened.push(item)});
  const card = () => container.all().find(item => item.dataset.nodeId === "route-early");
  assert.equal(container.all().filter(item => item.classList.contains("tl-graph-node")).length, 2);
  assert.match(card().textContent, /2 recorded routing decisions/);
  assert.match(card().getAttribute("aria-description"), /no database query or network success implied/);
  assert.ok(container.querySelector(".tl-graph-inspector-empty"));
  const initialListeners = container.listenerCount(), oldCard = card(); oldCard.focus(); oldCard.fire("click");
  const evidence = container.querySelector(".tl-graph-route-evidence");
  assert.equal(card().getAttribute("aria-pressed"), "true");
  assert.equal(card().getAttribute("aria-controls"), container.querySelector(".tl-graph-details").id);
  assert.ok(container.querySelector(".tl-graph-details").contains(evidence));
  assert.equal(card().contains(evidence), false, "evidence never becomes interactive content inside a card button");
  assert.deepEqual(evidence.all().filter(item => item.classList.contains("tl-graph-route-event"))
    .map(row => row.childNodes.map(child => child.textContent)), [["Event #3", "http-worker"], ["Event #20", "queue-worker"]]);
  assert.match(evidence.textContent, /not SQL queries, connections or a shared timeline/);
  assert.equal(container.ownerDocument.activeElement, card());
  assert.equal(container.querySelector(".tl-graph-node[aria-pressed=true]"), card());
  assert.deepEqual(opened, []);
  oldCard.fire("click"); assert.equal(container.querySelector(".tl-graph-route-evidence"), evidence, "reselecting the card does not recreate evidence");
  let prevented = 0;
  evidence.focus(); container.querySelector(".tl-graph-details").fire("keydown", {key: "Escape", preventDefault() { prevented++; }});
  assert.equal(prevented, 1); assert.equal(container.querySelector(".tl-graph-route-evidence"), null);
  assert.equal(container.ownerDocument.activeElement, card());
  evidence.fire("keydown", {key: "Escape", preventDefault() {}});
  assert.equal(container.querySelector(".tl-graph-route-evidence"), null, "detached evidence cannot reopen the card");
  for (let i = 0; i < 5; i++) { card().fire("click"); container.querySelector(".tl-graph-inspector-close").fire("click"); }
  assert.equal(container.listenerCount(), initialListeners, "repeated disclosure does not accumulate listeners");
  assert.equal(JSON.stringify(input), before);
  const current = card(); cleanup(); cleanup(); current.fire("click");
  assert.equal(container.childNodes.length, 0); assert.equal(container.listenerCount(), 0); assert.deepEqual(opened, []);
});

test("singleton route cards retain truthful counts, missing-field fallbacks and inert labels", () => {
  const input = capturedRoutes(); input.nodes.splice(1, 1); input.edges.shift();
  const route = input.nodes[1], unsafe = '<img src=x onerror="alert(1)">';
  route.destination = unsafe; route.eventSeq = null; delete route.thread;
  const container = documentFixture(), cleanup = graph.render(container, input, {showNodeDetails: false});
  const card = () => container.all().find(item => item.dataset.nodeId === "route-early");
  assert.match(card().textContent, /1 recorded routing decision/);
  assert.doesNotMatch(card().textContent, /1 recorded routing decisions/);
  assert.ok(card().textContent.includes(unsafe));
  let prevented = false; card().fire("keydown", {key: "Enter", preventDefault() { prevented = true; }});
  assert.equal(prevented, false); assert.equal(container.querySelector(".tl-graph-route-evidence"), null);
  card().fire("click");
  const evidence = container.querySelector(".tl-graph-route-evidence");
  assert.match(evidence.textContent, /Event sequence unavailable/); assert.match(evidence.textContent, /Thread not captured/);
  assert.equal(container.all().some(item => item.tagName === "img"), false);
  container.querySelector(".tl-graph-inspector-close").fire("click"); assert.equal(container.querySelector(".tl-graph-route-evidence"), null);
  cleanup(); assert.equal(container.listenerCount(), 0);
  route.thread = '<script>alert("thread")</script>';
  const second = documentFixture(), cleanupSecond = graph.render(second, input, {showNodeDetails: false});
  second.all().find(item => item.dataset.nodeId === "route-early").fire("click");
  assert.ok(second.querySelector(".tl-graph-route-evidence").textContent.includes(route.thread));
  assert.equal(second.all().some(item => item.tagName === "script"), false); cleanupSecond();
});

test("HTTP-hop toggling remains independent of grouped routes and their open event disclosure", () => {
  const input = capturedHttp(), owner = {ownerId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance", segmentId: "ems-segment", segmentKey: null};
  input.nodes.push(databaseRoute("db-late", {...owner, eventSeq: 1519}), databaseRoute("db-early", {...owner, eventSeq: 147}),
    databaseRoute("db-slave", {...owner, destination: "TEST_SLAVE"}),
    node("queue", "dependency-call", {ownerId: "ems", boundaryKind: "messaging", direction: "publish", system: "sqs", status: "SUBMITTED"}));
  input.edges.push(...input.nodes.slice(3).map(item => edge("ems", item.id, "outbound")));
  const before = JSON.stringify(input), container = documentFixture(), opened = [];
  const cleanup = graph.render(container, input, {showNodeDetails: false, onSelect: item => opened.push(item)});
  const card = id => container.all().find(item => item.classList.contains("tl-graph-node") && item.dataset.nodeId === id);
  assert.equal(card("call"), undefined); assert.equal(card("db-late"), undefined);
  card("db-early").fire("click");
  for (let i = 0; i < 2; i++) {
    container.querySelector(".tl-graph-hop-toggle").fire("click");
    assert.equal(!!card("call"), i === 0);
    assert.equal(card("db-late"), undefined); assert.ok(card("db-slave")); assert.ok(card("queue"));
    assert.match(card("db-early").textContent, /2 recorded routing decisions/);
    assert.equal(card("db-early").getAttribute("aria-pressed"), "true");
    assert.match(container.querySelector(".tl-graph-route-evidence").textContent, /Event #147.*Event #1519/);
    assert.doesNotMatch(container.textContent, /Diagram limit/);
  }
  assert.equal(JSON.stringify(input), before); assert.deepEqual(opened, []);
  card("ems").fire("click"); container.querySelector(".tl-graph-source").fire("click"); assert.equal(opened[0], input.nodes[2]); cleanup();
  assert.equal(container.listenerCount(), 0);
});

test("route evidence rows are bounded without losing count fidelity, and malformed captures still render safely", () => {
  const input = capturedRoutes(), count = graph.limits.routeRows + 7;
  input.nodes = [input.nodes[0], ...Array.from({length: count}, (_, i) => databaseRoute(`route-${String(i).padStart(3, "0")}`, {eventSeq: i + 1}))];
  input.edges = input.nodes.slice(1).map(item => edge("app", item.id, "outbound"));
  const before = JSON.stringify(input), container = documentFixture(), cleanup = graph.render(container, input, {showNodeDetails: false});
  const card = container.all().find(item => item.dataset.nodeId === "route-000");
  assert.match(card.textContent, new RegExp(`${count} recorded routing decisions`)); card.fire("click");
  const evidence = container.querySelector(".tl-graph-route-evidence");
  assert.equal(evidence.all().filter(item => item.classList.contains("tl-graph-route-event")).length, graph.limits.routeRows);
  assert.match(evidence.textContent, new RegExp(`Showing ${graph.limits.routeRows} of ${count} recorded decisions`));
  assert.match(evidence.textContent, /Remaining events are retained in the timeline and source capture/);
  assert.doesNotMatch(container.textContent, /Diagram limit/); assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
  for (const malformed of [null, {}, {nodes: {}, edges: []}, {nodes: null, edges: []}, {nodes: [null], edges: []}]) {
    const target = documentFixture(), cleanupMalformed = graph.render(target, malformed);
    assert.match(target.textContent, /No observed request-flow nodes/);
    assert.equal(target.querySelector(".tl-graph-route-evidence"), null);
    cleanupMalformed(); assert.equal(target.listenerCount(), 0);
  }
});

test("HTTP hops are hidden by default while an explicit expanded option remains supported", () => {
  for (const options of [undefined, {}, {showHttpHops: true}, {showHttpHops: false}]) {
    const container = documentFixture(), input = capturedHttp(), before = JSON.stringify(input);
    freezeTree(input);
    const cleanup = graph.render(container, input, options);
    const expanded = options?.showHttpHops === true;
    const cards = () => container.all().filter(item => item.classList.contains("tl-graph-node"));
    assert.deepEqual(cards().map(item => item.dataset.nodeId), expanded ? ["app", "call", "ems"] : ["app", "ems"]);
    const toggle = container.querySelector(".tl-graph-hop-toggle");
    assert.equal(toggle.textContent, expanded ? "Hide HTTP hops" : "Show HTTP hops");
    assert.equal(toggle.getAttribute("aria-pressed"), String(expanded));
    assertDiagramFits(container);
    assert.equal(container.querySelector(".tl-graph-coverage").open, false);
    assert.equal(JSON.stringify(input), before, "default selection must not rewrite the canonical capture");
    cleanup(); assert.equal(container.listenerCount(), 0);
  }
});

test("default compact HTTP toggle is reversible without navigation, mutation, or treating folds as omitted evidence", () => {
  const container = documentFixture(), input = capturedHttp(), before = JSON.stringify(input), opened = [];
  freezeTree(input);
  const cleanup = graph.render(container, input, {onSelect: item => opened.push(item)});
  const cards = () => container.all().filter(item => item.classList.contains("tl-graph-node"));
  assert.deepEqual(cards().map(item => item.dataset.nodeId), ["app", "ems"]);
  assert.match(container.querySelector(".tl-graph-counts").textContent, /1 HTTP hop folded/);
  assert.doesNotMatch(container.textContent, /Diagram limit/);
  const old = cards()[0];
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.deepEqual(cards().map(item => item.dataset.nodeId), ["app", "call", "ems"]);
  assert.equal(container.querySelector(".tl-graph-hop-toggle").textContent, "Hide HTTP hops");
  assert.doesNotMatch(container.querySelector(".tl-graph-counts").textContent, /HTTP hop folded/);
  old.fire("click"); assert.equal(opened.length, 0, "Detached listeners are removed when toggling");
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.deepEqual(cards().map(item => item.dataset.nodeId), ["app", "ems"]);
  assert.equal(container.querySelector(".tl-graph-hop-toggle").textContent, "Show HTTP hops");
  assert.equal(opened.length, 0); assert.equal(JSON.stringify(input), before);
  const current = cards()[0]; cleanup(); cleanup(); current.fire("click"); assert.equal(opened.length, 0);
});

test("legacy diagram-only option retains the stable inspector and explicit service activation", () => {
  const container = documentFixture(), input = capturedHttp(), opened = [];
  input.nodes.push(
    node("db", "dependency-call", {boundaryKind: "database-route", direction: "outbound",
      destination: "TEST_MASTER", system: "database", operation: "route", evidenceKind: "routing-marker", status: "OBSERVED"}),
    node("sqs", "dependency-call", {boundaryKind: "messaging", direction: "publish",
      destination: "ems-events", system: "sqs", operation: "sendMessage", evidenceKind: "client-boundary", status: "SUBMITTED"}));
  input.edges.push(edge("ems", "db", "outbound"), edge("ems", "sqs", "outbound"));
  const before = JSON.stringify(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false, showHttpHops: true, onSelect: item => opened.push(item)});
  const card = id => container.all().find(item => item.dataset.nodeId === id);
  const noPanel = () => {
    assert.ok(container.querySelector(".tl-graph-details"));
    assert.match(container.querySelector(".tl-graph-help").textContent, /inspect without moving/);
  };
  noPanel();
  assert.deepEqual(container.all().filter(item => item.classList.contains("tl-graph-node")).map(item => item.dataset.nodeId),
    ["app", "call", "ems", "db", "sqs"]);
  assert.match(card("db").textContent, /DB ROUTING DECISION.*TEST_MASTER/);
  assert.match(card("sqs").textContent, /QUEUE PUBLISH.*ems-events.*SUBMITTED/);
  assert.match(card("db").attrs["aria-description"], /no database query or network success implied/);
  assert.match(card("sqs").title, /broker delivery and remote processing are not implied/);
  card("db").focus(); card("sqs").fire("click"); noPanel();
  assert.equal(opened.length, 0);
  card("app").focus(); card("app").fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement, card("call"));
  card("call").fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement, card("ems"));
  assert.equal(opened.length, 0); noPanel();
  card("ems").fire("click"); container.querySelector(".tl-graph-source").fire("click"); assert.equal(opened[0], input.nodes[2]);
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.equal(card("call"), undefined); assert.ok(card("db")); assert.ok(card("sqs")); noPanel();
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.ok(card("call")); card("call").fire("click"); noPanel();
  assert.match(card("call").textContent, /HTTP 503.*14 ms/);
  assert.ok(card("db")); assert.ok(card("sqs"));
  assert.equal(JSON.stringify(input), before);
  const receiver = card("ems"); cleanup(); receiver.fire("click");
  assert.equal(opened.length, 1); assert.equal(container.childNodes.length, 0);
});

test("folded caller errors and timing remain separately inspectable from a successful receiver", () => {
  const container = documentFixture(), input = capturedHttp(), opened = [];
  const cleanup = graph.render(container, input, {showHttpHops: false, onSelect: item => opened.push(item)});
  const receiver = container.all().find(item => item.dataset.nodeId === "ems");
  assert.match(receiver.textContent, /HTTP 200/); assert.match(receiver.textContent, /Caller HTTP failure/);
  assert.equal(receiver.classList.contains("tl-graph-failed"), true);
  assert.match(receiver.textContent, /Inspect service →/);
  assert.doesNotMatch(receiver.textContent, /Open execution flow →/);
  receiver.focus(); assert.equal(opened.length, 0);
  const details = container.querySelector(".tl-graph-details");
  assert.equal(details.tagName, "aside");
  assert.match(details.textContent, /Server-local duration7 ms/);
  assert.match(details.textContent, /Caller-local duration14 ms/);
  assert.match(details.textContent, /Caller statusERROR · HTTP 503/);
  assert.match(details.textContent, /Server statusHTTP 200/);
  for (const value of ["ems-instance", "app-instance", "caller-key", "INJECTED", "127.0.0.1:8082", "not interchangeable or additive"]) assert.ok(details.textContent.includes(value));
  receiver.fire("click"); container.querySelector(".tl-graph-source").fire("click"); assert.equal(opened[0], input.nodes[2]); cleanup();
});

test("focus, selection and native card activation inspect without navigating; source action navigates", () => {
  const container = documentFixture(), opened = [], input = capturedHttp();
  const cleanup = graph.render(container, input, {showHttpHops: true, onSelect: item => opened.push(item)});
  const app = container.all().find(item => item.dataset.nodeId === "app");
  const ems = container.all().find(item => item.dataset.nodeId === "ems");
  const initialDetails = container.querySelector(".tl-graph-details").textContent;
  ems.fire("mouseenter"); ems.fire("mouseleave"); assert.equal(container.querySelector(".tl-graph-details").textContent, initialDetails);
  assert.equal(ems.listeners.mouseenter, undefined); assert.equal(ems.listeners.mouseleave, undefined);
  app.focus(); app.fire("keydown", {key: "ArrowRight", preventDefault() {}});
  const call = container.all().find(item => item.dataset.nodeId === "call");
  assert.equal(container.ownerDocument.activeElement, call); assert.equal(opened.length, 0);
  call.fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement, ems); assert.equal(opened.length, 0);
  for (const key of ["Enter", " "]) {
    let prevented = false; ems.fire("keydown", {key, preventDefault() { prevented = true; }});
    assert.equal(prevented, false, "Native button activation is not overridden");
    assert.equal(opened.length, 0);
    ems.fire("click");
  }
  assert.deepEqual(opened, []);
  container.querySelector(".tl-graph-source").fire("click");
  assert.deepEqual(opened, [input.nodes[2]]); cleanup();
});

test("outbound-only cards inspect without navigation, and no-callback MCP rendering stays usable offline", () => {
  const input = capturedHttp(); input.nodes.pop(); input.edges.pop(); input.nodes[1].downstreamState = "not-observed";
  const container = documentFixture(), opened = [];
  let cleanup = graph.render(container, input, {onSelect: item => opened.push(item)});
  const outbound = container.all().find(item => item.dataset.nodeId === "call");
  outbound.fire("click");
  assert.equal(opened.length, 0); assert.ok(container.querySelector(".tl-graph-detail-body"));
  assert.match(outbound.textContent, /Remote execution not observed/);
  assert.equal(container.querySelector(".tl-graph-source"), null); cleanup();
  cleanup = graph.render(container, capturedHttp(), {showHttpHops: false});
  const ems = container.all().find(item => item.dataset.nodeId === "ems"); ems.fire("click");
  assert.ok(container.querySelector(".tl-graph-detail-body"));
  assert.match(container.querySelector(".tl-graph-details").textContent, /Captured HTTP hop/);
  assert.equal(container.querySelector(".tl-graph-source"), null); cleanup();
});

test("external HTTP cards name any known service or raw peer without pretending its internals were captured", () => {
  for (const [destinationName, peer, expectedName] of [
    ["cb-plans", "127.0.0.1:8081", "cb-plans"],
    ["catalogue-service", "catalogue.internal:9090", "catalogue-service"],
    [undefined, "inventory.internal:8443", "inventory.internal:8443"],
    [undefined, undefined, "Unknown external service"],
  ]) {
    const input = externalHttp(); Object.assign(input.nodes[1], {destinationName, peer});
    const before = JSON.stringify(input), container = documentFixture(), opened = [];
    freezeTree(input);
    const cleanup = graph.render(container, input, {onSelect: item => opened.push(item)});
    const card = container.all().find(item => item.dataset.nodeId === "call");
    assert.equal(card.querySelector(".tl-graph-node-kind").textContent, "EXTERNAL SERVICE");
    assert.equal(card.querySelector(".tl-graph-node-name").textContent, expectedName);
    assert.match(card.textContent, /POST \/items\/search/);
    assert.match(card.textContent, /HTTP 200.*14 ms caller/);
    assert.match(card.textContent, /Remote execution not observed/);
    assert.doesNotMatch(card.textContent, /SERVICE SEGMENT|Open code flow|Captured service invocation/);
    assert.match(card.getAttribute("aria-label"), /EXTERNAL SERVICE/);
    assert.match(card.getAttribute("aria-label"), /caller duration 14 ms/);
    assert.match(card.title, /Caller-side HTTP observation/);
    card.fire("click");
    assert.equal(opened.length, 0, "opening the external card never invents a remote execution-flow route");
    const details = container.querySelector(".tl-graph-details");
    assert.equal(details.tagName, "aside");
    if (peer) assert.ok(details.textContent.includes(peer));
    else assert.doesNotMatch(card.querySelector(".tl-graph-node-name").textContent, /cb-app/,
      "missing destination metadata must not promote the caller's service identity to a remote service name");
    assert.equal(details.querySelector(".tl-graph-detail-title").textContent, "EXTERNAL SERVICE");
    assert.match(details.textContent, /NOT_ALLOWLISTED/);
    assert.equal(container.querySelector(".tl-graph-short-counts").textContent, "1 segment · 1 boundary");
    assert.equal(JSON.stringify(input), before);
    cleanup(); assert.equal(container.listenerCount(), 0);
  }
});

function peerNamedCalls() {
  const input = capturedHttp(); delete input.nodes[1].destinationName;
  const call = {...input.nodes[1], id: "external-a", callKey: "external-a-key", path: "/other-api", downstreamState: "not-observed"};
  const second = {...call, id: "external-b", callKey: "external-b-key", ownerId: "ems", service: "cb-ems",
    serviceInstanceId: "ems-instance", segmentId: "ems-segment"};
  input.nodes.push(call, second);
  input.edges.push(edge("app", call.id, "outbound"), edge("ems", second.id, "outbound"));
  return input;
}

test("external destinations reuse unique captured peer names as display hints without creating receiver evidence", () => {
  for (const showHttpHops of [false, true]) {
    const input = peerNamedCalls(), before = JSON.stringify(input), container = documentFixture(), selected = [];
    freezeTree(input);
    const cleanup = graph.render(container, input, {showHttpHops, onSelect: node => selected.push(node)});
    for (const id of ["external-a", "external-b"]) {
      const card = container.all().find(item => item.dataset.nodeId === id);
      assert.equal(card.querySelector(".tl-graph-node-name").textContent, "cb-ems");
      assert.match(card.title, /inferred from another observed HTTP call to this exact peer in the same capture/);
      assert.match(card.title, /127\.0\.0\.1:8082/);
      assert.match(card.textContent, /EXTERNAL SERVICE.*Remote execution not observed/);
      card.fire("click");
      assert.match(container.querySelector(".tl-graph-details").textContent, /Name source.*inferred/);
      assert.match(container.querySelector(".tl-graph-details").textContent, /Unavailable — no downstream segment is linked/);
    }
    assert.deepEqual(selected, [], "a destination label must never invent receiver navigation");
    if (showHttpHops) {
      const observed = container.all().find(item => item.dataset.nodeId === "call");
      assert.equal(observed.querySelector(".tl-graph-node-name").textContent, "cb-ems");
      assert.match(observed.title, /linked to its captured service segment/);
      assert.doesNotMatch(observed.title, /inferred from another/);
    }
    assert.equal(JSON.stringify(input), before, "historical evidence and caller identity stay unchanged");
    cleanup();
  }
});

test("destination naming honors explicit names and fails closed for unknown or conflicting peers", () => {
  const scenarios = [
    ["explicit alias", input => { input.nodes.find(n => n.id === "external-a").destinationName = "configured-name"; }, "configured-name"],
    ["other port", input => { input.nodes.find(n => n.id === "external-a").peer = "127.0.0.1:8083"; }, "127.0.0.1:8083"],
    ["other host", input => { input.nodes.find(n => n.id === "external-a").peer = "other.internal:8082"; }, "other.internal:8082"],
    ["missing observed link", input => { input.edges = input.edges.filter(e => e.kind !== "observed-call"); }, "127.0.0.1:8082"],
    ["invalid call ownership", input => { input.nodes[1].segmentId = "different-segment"; }, "127.0.0.1:8082"],
    ["ambiguous receiver", input => {
      input.nodes.push(node("other", "segment", {service: "different-service", serviceInstanceId: "other-instance", segmentId: "other-segment"}));
      input.edges.push(edge("call", "other"));
    }, "127.0.0.1:8082"],
    ["gateway to two services", input => {
      input.nodes.push(node("other", "segment", {service: "different-service", serviceInstanceId: "other-instance", segmentId: "other-segment"}),
        {...input.nodes[1], id: "other-call", callKey: "other-call-key"});
      input.edges.push(edge("app", "other-call", "outbound"), edge("other-call", "other"));
    }, "127.0.0.1:8082"],
  ];
  for (const [name, mutate, expected] of scenarios) {
    const input = peerNamedCalls(); mutate(input);
    const before = JSON.stringify(input);
    for (const shown of [input, {...input, nodes: [...input.nodes].reverse(), edges: [...input.edges].reverse()}]) {
      const container = documentFixture(), cleanup = graph.render(container, shown);
      const card = container.all().find(item => item.dataset.nodeId === "external-a");
      assert.equal(card.querySelector(".tl-graph-node-name").textContent, expected, name);
      assert.match(card.textContent, /Remote execution not observed/, name);
      cleanup();
    }
    assert.equal(JSON.stringify(input), before, name);
  }
});

test("compact mode folds observed hops only and keeps external destinations visible across repeated toggles", () => {
  const input = capturedHttp(), external = {...externalHttp().nodes[1], id: "external", callKey: "external-key"};
  input.nodes.push(external); input.edges.push(edge("app", external.id, "outbound"));
  const before = JSON.stringify(input), container = documentFixture(); freezeTree(input);
  const cleanup = graph.render(container, input, {showHttpHops: false, showNodeDetails: false});
  const card = id => container.all().find(item => item.dataset.nodeId === id);
  for (let iteration = 0; iteration < 4; iteration++) {
    const expanded = iteration % 2 === 1;
    assert.ok(card("external"));
    assert.equal(card("external").querySelector(".tl-graph-node-kind").textContent, "EXTERNAL SERVICE");
    assert.match(card("external").textContent, /Remote execution not observed/);
    assert.equal(Boolean(card("call")), expanded);
    if (expanded) {
      assert.equal(card("call").querySelector(".tl-graph-node-kind").textContent, "OUTGOING HTTP");
      assert.match(card("call").textContent, /Downstream observed/);
    }
    assert.equal(container.querySelector(".tl-graph-short-counts").textContent,
      expanded ? "2 segments · 2 boundaries" : "2 segments · 1 boundary");
    container.querySelector(".tl-graph-hop-toggle").fire("click");
  }
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("coverage and warnings stay in one collapsed guide while errors remain visible", () => {
  const container = documentFixture(), input = capturedHttp();
  input.partial = true;
  input.warnings = [...Array.from({length: 15}, (_, n) => `Unobserved local boundary ${n}`), "Visualization dependency limit reached (128); evidence is incomplete", "Truncated segment: ems"];
  input.errors = [{service: "other", error: "offline"}];
  const cleanup = graph.render(container, input);
  const coverage = container.querySelector(".tl-graph-coverage"), prominent = container.querySelector(".tl-graph-notices");
  assert.equal(coverage.tagName, "details"); assert.equal(coverage.open, false);
  assert.match(coverage.textContent, /Partial evidence/);
  assert.match(coverage.textContent, /Partial capture/);
  assert.match(prominent.textContent, /other: offline/);
  assert.match(coverage.textContent, /dependency limit reached/);
  assert.match(coverage.textContent, /Truncated segment/);
  assert.doesNotMatch(prominent.textContent, /Unobserved local boundary|limit reached|Truncated/);
  assert.equal(coverage.contains(prominent), false, "errors must not be hidden by the guide");
  assert.match(container.querySelector(".tl-graph-legend").textContent, /Arrows = recorded boundary direction or causal links/);
  coverage.open = true;
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.equal(container.querySelector(".tl-graph-coverage").open, false, "redrawing does not automatically expand coverage");
  cleanup();
  input.partial = false;
  const cleanupComplete = graph.render(container, input);
  assert.equal(container.querySelector(".tl-graph-coverage").open, false);
  assert.equal(container.querySelector(".tl-graph-coverage").childNodes[0].textContent, "Partial evidence",
    "limit/truncation warnings still label the visible summary as partial");
  cleanupComplete();
});

test("graph chrome is one panel with short counts and a single collapsed guide", () => {
  const container = documentFixture(), input = capturedHttp();
  input.partial = true;
  input.nodes.push(node("queue", "dependency-call")); input.edges.push(edge("ems", "queue", "outbound"));
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const frame = container.querySelector(".tl-graph-frame"), toolbar = container.querySelector(".tl-graph-toolbar");
  const coverage = container.querySelector(".tl-graph-coverage"), viewport = container.querySelector(".tl-graph-viewport");
  assert.equal(toolbar.parentNode, frame);
  assert.equal(viewport.parentNode, container.querySelector(".tl-graph-diagram"));
  assert.equal(viewport.parentNode.parentNode, container.querySelector(".tl-graph-workspace"));
  assert.equal(viewport.parentNode.parentNode.parentNode, frame);
  assert.equal(container.all().filter(item => item.tagName === "details").length, 1);
  assert.equal(coverage.open, false);
  assert.equal(container.querySelector(".tl-graph-short-counts").textContent, "2 segments · 1 boundary");
  assert.ok(toolbar.contains(container.querySelector(".tl-graph-short-counts")));
  assert.ok(toolbar.contains(container.querySelector(".tl-graph-hop-toggle")));
  for (const selector of [".tl-graph-counts", ".tl-graph-subtitle", ".tl-graph-legend", ".tl-graph-help"]) {
    assert.ok(coverage.contains(container.querySelector(selector)), `${selector} belongs inside the one guide`);
  }
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.equal(container.querySelector(".tl-graph-short-counts").textContent, "2 segments · 2 boundaries");
  assert.equal(container.querySelector(".tl-graph-coverage").open, false);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("empty and single-segment diagrams retain compact guide and truthful counts", () => {
  const container = documentFixture();
  let cleanup = graph.render(container, model([]), {showNodeDetails: false});
  assert.equal(container.querySelector(".tl-graph-short-counts").textContent, "0 segments · 0 boundaries");
  assert.equal(container.querySelector(".tl-graph-coverage").childNodes[0].textContent, "Diagram guide");
  assert.equal(container.querySelector(".tl-graph-empty").parentNode, container.querySelector(".tl-graph-frame"));
  cleanup();
  cleanup = graph.render(container, model([node("app")]), {showNodeDetails: false});
  assert.equal(container.querySelector(".tl-graph-short-counts").textContent, "1 segment · 0 boundaries");
  assert.equal(container.querySelector(".tl-graph-hop-toggle"), null);
  assert.equal(container.querySelector(".tl-graph-coverage").open, false);
  cleanup();
});

test("request and execution diagrams allow vertical scroll chaining without horizontal page gestures", () => {
  for (const [file, selector] of [["trace-graph.css", "tl-graph-viewport"], ["execution-graph.css", "teg-viewport"]]) {
    const css = fs.readFileSync(require.resolve(`../tracelens_cli/web/${file}`), "utf8");
    const declarations = css.match(new RegExp(`\\.${selector}\\s*\\{([^}]+)\\}`))[1];
    assert.match(declarations, /overflow\s*:\s*auto\s*;/);
    assert.match(declarations, /overscroll-behavior\s*:\s*contain\s+auto\s*;/,
      `${file} must allow the page to scroll at either vertical edge`);
  }
});

test("raw display caps do not turn an explicitly captured receiver into an unobserved dependency", () => {
  const nodes = [node("app")], edges = [];
  for (let index = 0; index < 120; index++) {
    const suffix = String(index).padStart(3, "0"), call = "call" + suffix, receiver = "receiver" + suffix;
    nodes.push(node(call, "http-call", {ownerId: "app", downstreamState: "observed"}), node(receiver));
    edges.push(edge("app", call, "outbound"), edge(call, receiver));
  }
  const input = model(nodes, edges, ["app"]), container = documentFixture();
  const compact = graph.presentation(input);
  assert.equal(compact.foldedHops.length, 120);
  const cleanup = graph.render(container, input, {showHttpHops: true});
  const call = container.all().find(item => item.dataset.nodeId === "call000");
  assert.ok(call); assert.match(call.textContent, /Downstream observed/);
  assert.doesNotMatch(call.textContent, /Remote execution not observed/);
  assert.equal(call.classList.contains("tl-graph-external-service"), false);
  assert.equal(container.all().some(item => item.dataset.nodeId === "receiver000"), false);
  assert.match(container.textContent, /Diagram limit/); cleanup();
  for (const state of [undefined, "not-observed"]) {
    const legacy = {...input, nodes: input.nodes.map(item => item.kind === "http-call"
      ? {...item, downstreamState: state} : item)};
    const before = JSON.stringify(legacy); freezeTree(legacy);
    const bounded = graph.layout(legacy), observation = bounded.nodes.find(item => item.id === "call000");
    assert.equal(observation.downstreamObserved, true, "the full recorded edge survives a display-only receiver cap");
    assert.equal(observation.externalService, false, "an explicit observed link wins over missing/stale downstream metadata");
    assert.equal(bounded.nodes.some(item => item.id === "receiver000"), false);
    const cleanLegacy = graph.render(container, legacy, {showHttpHops: true});
    const legacyCard = container.all().find(item => item.dataset.nodeId === "call000");
    assert.equal(legacyCard.querySelector(".tl-graph-node-kind").textContent, "OUTGOING HTTP");
    assert.match(legacyCard.textContent, /Downstream observed/);
    assert.doesNotMatch(legacyCard.textContent, /EXTERNAL SERVICE|Remote execution not observed/);
    assert.equal(JSON.stringify(legacy), before);
    cleanLegacy();
  }
});

test("singular and zero graph counts use clear grammar", () => {
  const input = capturedHttp();
  input.nodes.push(node("queue", "dependency-call")); input.edges.push(edge("ems", "queue", "outbound"));
  const container = documentFixture(), cleanup = graph.render(container, input);
  assert.equal(container.querySelector(".tl-graph-counts").textContent, "2 service invocations · 0 HTTP boundaries · 1 other boundary · 1 HTTP hop folded");
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.equal(container.querySelector(".tl-graph-counts").textContent, "2 service invocations · 1 HTTP boundary · 1 other boundary");
  cleanup();
});

function assertDiagramFits(container) {
  const viewport = container.querySelector(".tl-graph-viewport"), canvas = container.querySelector(".tl-graph-canvas");
  const zoom = Number(canvas.style.transform.match(/scale\((.+)\)/)[1]);
  assert.equal(zoom, Math.max(.005, Math.min(1, (viewport.clientWidth - 16) / parseFloat(canvas.style.width),
    (viewport.clientHeight - 16) / parseFloat(canvas.style.height))));
  return zoom;
}

function deferredFitFixture({connected = false, width = 800, height = 500, resizeObserver = true} = {}) {
  const container = documentFixture(), document = container.ownerDocument;
  const state = {connected, width, height}, frames = new Map(), observers = [], resizeListeners = new Set();
  let nextFrame = 0;
  const createElement = document.createElement;
  document.createElement = tag => {
    const item = createElement(tag);
    Object.defineProperties(item, {
      isConnected: {get: () => state.connected},
      clientWidth: {get: () => state.width},
      clientHeight: {get: () => state.height},
    });
    return item;
  };
  document.defaultView = {
    requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame(id) { frames.delete(id); },
    addEventListener(name, callback) { assert.equal(name, "resize"); resizeListeners.add(callback); },
    removeEventListener(name, callback) { assert.equal(name, "resize"); resizeListeners.delete(callback); },
  };
  if (resizeObserver) document.defaultView.ResizeObserver = class {
    constructor(callback) { this.callback = callback; this.active = false; observers.push(this); }
    observe(target) { this.target = target; this.active = true; }
    disconnect() { this.active = false; }
  };
  return {container, state, frames, observers, resizeListeners,
    frame() { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback()); },
    resize() { [...resizeListeners].forEach(callback => callback()); },
    observe() { observers.filter(observer => observer.active).forEach(observer => observer.callback()); },
  };
}

test("initial Fit waits for an attached measurable viewport and stops observing after fitting", () => {
  const fixture = deferredFitFixture(), {container, state, frames, observers, resizeListeners} = fixture;
  const cleanup = graph.render(container, capturedHttp());
  const canvas = container.querySelector(".tl-graph-canvas");
  assert.equal(canvas.style.transform, "scale(1)", "detached dimensions cannot commit an initial fit");
  assert.equal(observers[0].target, container.querySelector(".tl-graph-viewport"));
  fixture.frame(); assert.equal(frames.size, 0, "ResizeObserver avoids polling a hidden or detached tab");
  state.connected = true; state.width = 0; state.height = 0; fixture.observe(); fixture.frame();
  assert.equal(canvas.style.transform, "scale(1)", "a hidden tab cannot collapse to minimum zoom");
  state.width = 540; state.height = 320; fixture.observe();
  assertDiagramFits(container);
  assert.equal(observers.filter(observer => observer.active).length, 0);
  assert.equal(frames.size, 0); assert.equal(resizeListeners.size, 0);
  container.querySelector(".tl-graph-zoom-in").fire("click");
  const manualZoom = canvas.style.transform;
  state.width = 700; fixture.observe(); fixture.resize(); observers[0].callback();
  assert.equal(canvas.style.transform, manualZoom, "completed fits never override a later manual zoom");
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("deferred Fit supports animation-frame and window-resize fallbacks without ResizeObserver", () => {
  for (const wake of ["frame", "resize"]) {
    const fixture = deferredFitFixture({resizeObserver: false}), {container, state, frames, resizeListeners} = fixture;
    const cleanup = graph.render(container, capturedHttp());
    fixture.frame(); assert.equal(frames.size, 1, "an unmounted view retains one pending frame");
    state.connected = true; state.width = 440; state.height = 280;
    fixture[wake](); assertDiagramFits(container);
    assert.equal(frames.size, 0); assert.equal(resizeListeners.size, 0);
    cleanup(); assert.equal(container.listenerCount(), 0);
  }
});

test("hidden hop redraws and disposal clean every pending observer, resize handler and animation frame", () => {
  const fixture = deferredFitFixture({connected: true, width: 0, height: 0});
  const {container, frames, observers, resizeListeners} = fixture;
  const cleanup = graph.render(container, capturedHttp());
  const staleFrame = [...frames.values()][0], staleObserver = observers[0];
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.equal(staleObserver.active, false);
  assert.equal(observers.filter(observer => observer.active).length, 1);
  assert.equal(frames.size, 1); assert.equal(resizeListeners.size, 1);
  staleFrame(); staleObserver.callback();
  assert.equal(frames.size, 1, "obsolete callbacks cannot schedule work for replaced views");
  cleanup(); cleanup();
  assert.equal(observers.filter(observer => observer.active).length, 0);
  assert.equal(frames.size, 0); assert.equal(resizeListeners.size, 0); assert.equal(container.listenerCount(), 0);
  observers.forEach(observer => observer.callback());
  assert.equal(frames.size, 0); assert.equal(container.childNodes.length, 0);
});

test("manual zoom cancels an outstanding initial Fit while an explicit Fit can request it again", () => {
  const fixture = deferredFitFixture(), {container, state, frames, observers, resizeListeners} = fixture;
  const cleanup = graph.render(container, capturedHttp());
  container.querySelector(".tl-graph-zoom-in").fire("click");
  assert.equal(container.querySelector(".tl-graph-canvas").style.transform, "scale(1.2)");
  assert.equal(frames.size, 0); assert.equal(resizeListeners.size, 0);
  assert.equal(observers.filter(observer => observer.active).length, 0);
  state.connected = true; state.width = 500; state.height = 320;
  fixture.observe(); fixture.resize();
  assert.equal(container.querySelector(".tl-graph-canvas").style.transform, "scale(1.2)");
  container.querySelector(".tl-graph-fit").fire("click"); assertDiagramFits(container);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("zoom controls remain outside both scroll axes, scale geometry, fit and clamp with complete cleanup", () => {
  const input = capturedRoutes(), before = JSON.stringify(input), container = documentFixture();
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const viewport = container.querySelector(".tl-graph-viewport");
  const controls = container.querySelector(".tl-graph-zoom-controls");
  const canvas = container.querySelector(".tl-graph-canvas"), surface = container.querySelector(".tl-graph-surface");
  const value = () => container.querySelector(".tl-graph-zoom-value").textContent;
  const plus = container.querySelector(".tl-graph-zoom-in"), minus = container.querySelector(".tl-graph-zoom-out");
  assert.equal(controls.parentNode, viewport.parentNode);
  assert.equal(viewport.contains(controls), false, "scrolling either axis never moves the controls");
  assert.equal(surface.parentNode, viewport); assert.equal(canvas.parentNode, surface);
  assertDiagramFits(container);
  container.querySelector(".tl-graph-zoom-reset").fire("click");
  assert.equal(value(), "100%"); assert.equal(canvas.style.transform, "scale(1)");
  viewport.clientWidth = 300; viewport.clientHeight = 220;
  viewport.scrollLeft = 100; viewport.scrollTop = 20;
  plus.fire("click");
  assert.equal(value(), "120%"); assert.equal(canvas.style.transform, "scale(1.2)");
  assert.equal(viewport.scrollLeft, 150, "zoom preserves the content under the viewport center");
  assert.equal(parseFloat(surface.style.width), Math.ceil(parseFloat(canvas.style.width) * 1.2));
  container.querySelector(".tl-graph-fit").fire("click");
  const fitZoom = Number(canvas.style.transform.match(/scale\((.+)\)/)[1]);
  assert.ok(fitZoom < 1);
  assert.ok(parseFloat(surface.style.width) <= viewport.clientWidth);
  assert.ok(parseFloat(surface.style.height) <= viewport.clientHeight);
  assert.equal(viewport.scrollLeft, 0); assert.equal(viewport.scrollTop, 0);
  container.querySelector(".tl-graph-zoom-reset").fire("click"); assert.equal(value(), "100%");
  for (let i = 0; i < 50; i++) plus.fire("click");
  assert.equal(value(), "200%"); assert.equal(plus.disabled, true);
  for (let i = 0; i < 100; i++) minus.fire("click");
  assert.equal(value(), "0.5%"); assert.equal(minus.disabled, true);
  const oldTransform = canvas.style.transform;
  cleanup(); cleanup(); plus.fire("click");
  assert.equal(canvas.style.transform, oldTransform); assert.equal(container.listenerCount(), 0);
  assert.equal(JSON.stringify(input), before);
});

test("Fit includes the maximum bounded diagram in a short narrow viewport", () => {
  const input = model(Array.from({length: graph.limits.nodes}, (_, index) =>
    node(`queue-${String(index).padStart(3, "0")}`, "dependency-call",
      {boundaryKind: "messaging", system: "sqs", direction: "publish", eventSeq: index + 1})));
  const container = documentFixture();
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary() {}});
  const viewport = container.querySelector(".tl-graph-viewport");
  viewport.clientWidth = 260; viewport.clientHeight = 220;
  const canvas = container.querySelector(".tl-graph-canvas"), surface = container.querySelector(".tl-graph-surface");
  assert.ok(parseFloat(canvas.style.height) > 16000, "fixture covers the maximum-height compact bounded layout");
  container.querySelector(".tl-graph-fit").fire("click");
  const zoom = Number(canvas.style.transform.match(/scale\((.+)\)/)[1]);
  assert.ok(zoom < .02 && zoom >= .005);
  assert.ok(parseFloat(surface.style.height) <= viewport.clientHeight - 15);
  assert.ok(parseFloat(surface.style.width) <= viewport.clientWidth - 15);
  assert.equal(viewport.scrollTop, 0); assert.equal(viewport.scrollLeft, 0);
  container.querySelector(".tl-graph-zoom-in").fire("click");
  assert.ok(Number(canvas.style.transform.match(/scale\((.+)\)/)[1]) > zoom);
  container.querySelector(".tl-graph-zoom-reset").fire("click");
  assert.equal(container.querySelector(".tl-graph-zoom-value").textContent, "100%");
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("DB disclosures preserve chosen zoom and scroll without automatic pan on restored focus", () => {
  const input = capturedHttp(), container = documentFixture();
  input.nodes.push(databaseRoute("db", {ownerId: "ems", service: "cb-ems", serviceInstanceId: "ems-instance",
    segmentId: "ems-segment", segmentKey: null}));
  input.edges.push(edge("ems", "db", "outbound"));
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const get = cls => container.querySelector(`.${cls}`);
  get("tl-graph-zoom-out").fire("click");
  const zoom = get("tl-graph-canvas").style.transform;
  const card = () => container.all().find(item => item.dataset.nodeId === "db");
  for (const change of [() => card().fire("click"), () => card().fire("click")]) {
    card().focus(); get("tl-graph-viewport").scrollLeft = 76; get("tl-graph-viewport").scrollTop = 90;
    change();
    assert.equal(get("tl-graph-canvas").style.transform, zoom);
    assert.equal(get("tl-graph-viewport").scrollLeft, 76);
    assert.equal(get("tl-graph-viewport").scrollTop, 90);
  }
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("initial rendering and both HTTP-hop modes fit the complete current layout", () => {
  const input = capturedHttp(), container = documentFixture(), before = JSON.stringify(input);
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const get = cls => container.querySelector(`.${cls}`);
  const compactZoom = assertDiagramFits(container);
  for (const expanded of [true, false]) {
    get("tl-graph-zoom-reset").fire("click"); get("tl-graph-zoom-in").fire("click");
    get("tl-graph-viewport").scrollLeft = 76; get("tl-graph-viewport").scrollTop = 90;
    get("tl-graph-hop-toggle").fire("click");
    const zoom = assertDiagramFits(container);
    if (expanded) assert.ok(zoom < compactZoom, "showing HTTP hops fits the wider layout independently");
    else assert.equal(zoom, compactZoom);
    assert.equal(get("tl-graph-viewport").scrollLeft, 0); assert.equal(get("tl-graph-viewport").scrollTop, 0);
    assert.equal(container.ownerDocument.activeElement, get("tl-graph-hop-toggle"));
  }
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("arrow-key reveal uses zoomed node bounds rather than unscaled diagram coordinates", () => {
  const input = model([node("a"), node("b")], [edge("a", "b")], ["a"]), container = documentFixture();
  const cleanup = graph.render(container, input, {showNodeDetails: false});
  const viewport = container.querySelector(".tl-graph-viewport");
  viewport.clientWidth = 200; viewport.clientHeight = 200;
  container.querySelector(".tl-graph-zoom-reset").fire("click");
  container.querySelector(".tl-graph-zoom-in").fire("click");
  const target = graph.layout(input).nodes.find(item => item.id === "b");
  viewport.scrollLeft = 0; viewport.scrollTop = 0;
  container.all().find(item => item.dataset.nodeId === "a").fire("keydown", {key: "ArrowRight", preventDefault() {}});
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "b");
  assert.equal(viewport.scrollLeft, (target.x + target.width) * 1.2 + 24 - viewport.clientWidth);
  assert.equal(viewport.scrollTop, (target.y + target.height) * 1.2 + 24 - viewport.clientHeight);
  cleanup();
});

test("grouped DB calling-code actions preserve each canonical event and never navigate on focus or disclosure", () => {
  const input = capturedRoutes(), before = JSON.stringify(input), opened = [], container = documentFixture();
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: item => opened.push(item)});
  const card = () => container.all().find(item => item.dataset.nodeId === "route-early");
  card().focus(); card().fire("mouseenter"); assert.equal(opened.length, 0);
  card().fire("click"); assert.equal(opened.length, 0);
  const rows = container.querySelector(".tl-graph-route-evidence").all().filter(item => item.classList.contains("tl-graph-route-event"));
  assert.equal(rows.length, 2);
  const actions = rows.map(row => row.querySelector(".tl-graph-boundary-source"));
  actions[0].focus(); assert.equal(opened.length, 0);
  actions[0].fire("click"); actions[1].fire("click");
  assert.equal(opened[0], input.nodes.find(item => item.id === "route-early"));
  assert.equal(opened[1], input.nodes.find(item => item.id === "route-late"));
  assert.deepEqual(opened.map(item => item.eventSeq), [3, 20]);
  assert.equal(card().contains(actions[0]), false, "no nested interactive button");
  assert.ok(container.querySelector(".tl-graph-details").contains(actions[0]));
  container.querySelector(".tl-graph-inspector-close").fire("click"); actions[0].fire("click");
  assert.equal(opened.length, 2, "discarded event action cannot navigate");
  cleanup(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("database, cache and queue cards expose separate calling-code actions without inventing unavailable events", () => {
  const dependencies = [
    node("db", "dependency-call", {boundaryKind: "database", system: "clickhouse", evidenceKind: "client-boundary", eventSeq: 41}),
    node("cache", "dependency-call", {boundaryKind: "cache", system: "redis", evidenceKind: "client-boundary", eventSeq: 42}),
    node("queue", "dependency-call", {boundaryKind: "messaging", system: "sqs", direction: "publish", eventSeq: 43}),
    node("unknown", "dependency-call", {boundaryKind: "external", eventSeq: null}),
  ];
  const input = model([node("app"), ...dependencies], dependencies.map(item => edge("app", item.id, "outbound")), ["app"]);
  const before = JSON.stringify(input), opened = [], container = documentFixture();
  freezeTree(input);
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: item => opened.push(item)});
  const actions = [];
  assert.equal(container.all().filter(item => item.classList.contains("tl-graph-boundary-footer")).length, 0);
  const positioned = graph.layout(input, {boundaryActions: true});
  for (const item of positioned.nodes.filter(item => item.node.kind === "dependency-call")) {
    assert.equal(item.height, 182);
    for (const other of positioned.nodes.filter(other => other.rank === item.rank && other.id !== item.id)) {
      assert.ok(item.y + item.height <= other.y || other.y + other.height <= item.y);
    }
  }
  for (const observation of dependencies) {
    const card = container.all().find(item => item.dataset.nodeId === observation.id);
    card.fire("click");
    const action = container.querySelector(".tl-graph-boundary-footer"); actions.push(action);
    assert.ok(container.querySelector(".tl-graph-details").contains(action));
    card.focus(); card.fire("click"); action.focus();
    assert.equal(card.contains(action), false);
    action.fire("click");
  }
  assert.equal(actions.length, 4);
  assert.deepEqual(opened.map(item => item.eventSeq).sort(), [41, 42, 43]);
  for (const observation of opened) assert.ok(dependencies.includes(observation));
  const disabled = actions.find(action => action.disabled);
  assert.match(disabled.title, /No recorded event sequence/);
  assert.ok(container.querySelector(".tl-graph-details"));
  cleanup(); actions.forEach(action => action.fire("click"));
  assert.equal(opened.length, 3); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

function httpFooter(container, id) {
  const card = container.all().find(item => item.dataset.nodeId === id);
  card?.fire("click");
  return card && container.all().find(item => item.classList.contains("tl-graph-http-footer"));
}

function requestAction(container, id) {
  container.all().find(item => item.dataset.nodeId === id)?.fire("click");
  return container.all().find(item => item.classList.contains("tl-graph-request-toggle") && item.dataset.requestNodeId === id);
}
const settleRequestData = () => new Promise(resolve => setImmediate(resolve));

test("service request data stays collapsed and loads the exact segment only on explicit disclosure", async () => {
  const input = capturedHttp(), before = JSON.stringify(input), container = documentFixture(), loaded = [], selected = [];
  freezeTree(input);
  const raw = '<img src=x onerror="alert(1)">';
  const fullTrace = freezeTree({attributes: {httpQueryParametersStatus: "captured",
    httpQueryParameters: JSON.stringify({site: ["site-1"], repeated: ["a", "b"], text: [raw]}),
    httpRequestBodyStatus: "captured", httpRequestBodyFormat: "json", httpRequestBody: '{"amount":9007199254740993}',
    httpPayloadRedacted: "true"}});
  let resolveData;
  const cleanup = graph.render(container, input, {showNodeDetails: false, showHttpHops: false,
    onSelect: value => selected.push(value), onOpenBoundary() {},
    loadRequestData: value => { loaded.push(value); return new Promise(resolve => { resolveData = resolve; }); }});
  await settleRequestData();
  assert.deepEqual(loaded, [], "initial diagram render never fetches request payloads");
  assert.equal(container.all().filter(item => item.classList.contains("tl-graph-request-toggle")).length, 0);
  assert.equal(container.querySelector(".tl-graph-request-evidence"), null);
  assert.equal(requestAction(container, "call"), undefined, "caller HTTP observations do not pretend to hold the incoming body");
  assert.equal(requestAction(container, "ems").getAttribute("aria-expanded"), "false");
  requestAction(container, "ems").fire("click");
  assert.deepEqual(selected, []);
  assert.match(container.querySelector(".tl-graph-request-evidence").textContent, /Loading request data/);
  await settleRequestData(); assert.deepEqual(loaded, [input.nodes[2]]);
  resolveData(fullTrace); await settleRequestData();
  const evidence = container.querySelector(".tl-graph-request-evidence");
  assert.ok(container.querySelector(".tl-graph-details").contains(evidence));
  assert.match(evidence.textContent, /Query parameters.*site-1.*repeated.*Request body · JSON/s);
  assert.match(evidence.textContent, /9007199254740993/); assert.ok(evidence.textContent.includes(JSON.stringify(raw)));
  assert.match(evidence.textContent, /sensitive.*marked redacted.*never replayed/s);
  assert.equal(evidence.getAttribute("aria-busy"), "false");
  assert.equal(evidence.querySelector(".request-data").tagName, "section");
  assert.equal(evidence.all().some(item => item.tagName === "details" || item.tagName === "summary"), false,
    "the card's Request data button is the only disclosure control");
  assert.equal(requestAction(container, "ems").getAttribute("aria-controls"), evidence.id);
  assert.equal(container.all().some(item => item.tagName === "img" || item.tagName === "script"), false);
  assert.ok(container.querySelector(".tl-graph-details"));
  requestAction(container, "ems").fire("click"); assert.equal(container.querySelector(".tl-graph-request-evidence"), null);
  assert.equal(container.textContent.includes("site-1"), false, "collapsed evidence is removed from the rendered diagram");
  assert.equal(JSON.stringify(input), before, "request payload never enters the graph or its exports");
  assert.equal(JSON.stringify(graph.presentation(input)).includes("site-1"), false);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("opening and closing request data preserves compact card and canvas geometry", async () => {
  const container = documentFixture(), input = capturedHttp();
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary() {},
    loadRequestData: async () => ({attributes: {httpQueryParametersStatus: "captured",
      httpQueryParameters: '{"limit":["10"]}', httpPayloadRedaction: "disabled"}})});
  const geometry = () => ({
    cards: container.all().filter(item => item.classList.contains("tl-graph-node"))
      .map(item => [item.dataset.nodeId, item.style.left, item.style.top, item.style.width, item.style.height]),
    groups: container.all().filter(item => item.classList.contains("tl-graph-service-group"))
      .map(item => [item.style.left, item.style.top, item.style.width, item.style.height]),
    canvas: [container.querySelector(".tl-graph-canvas").style.width, container.querySelector(".tl-graph-canvas").style.height],
  });
  const initial = geometry();
  for (let cycle = 0; cycle < 2; cycle++) {
    requestAction(container, "ems").fire("click"); await settleRequestData();
    const panel = container.querySelector(".tl-graph-request-evidence");
    assert.ok(panel); assert.ok(container.querySelector(".tl-graph-details").contains(panel));
    assert.equal(panel.querySelector(".request-data-summary"), null);
    assert.doesNotMatch(panel.textContent, /unredacted/i);
    assert.deepEqual(geometry(), initial);
    requestAction(container, "ems").fire("click");
    assert.equal(container.querySelector(".tl-graph-request-evidence"), null);
    assert.equal(requestAction(container, "ems").getAttribute("aria-expanded"), "false");
    assert.deepEqual(geometry(), initial, "inspector content never changes canvas geometry");
  }
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("request evidence distinguishes not captured, omission status, viewer limits and load failures", async () => {
  for (const [trace, expected] of [
    [{attributes: {}}, /Request data was not captured.*no retained query-parameter or request-body evidence/s],
    [null, /Request data was not captured/],
    [{attributes: {httpQueryParametersStatus: "omitted-too-large", httpRequestBodyStatus: "not-consumed"}},
      /Query parameters.*Omitted — capture size limit.*Request body.*Not read by the application/s],
    [{attributes: {httpQueryParametersStatus: "captured", httpQueryParameters: "x".repeat(16385)}}, /viewer safety limit/],
  ]) {
    const container = documentFixture();
    const cleanup = graph.render(container, capturedHttp(), {loadRequestData: async () => trace});
    requestAction(container, "ems").fire("click"); await settleRequestData();
    assert.match(container.querySelector(".tl-graph-request-evidence").textContent, expected);
    cleanup(); assert.equal(container.listenerCount(), 0);
  }
  const container = documentFixture(); let calls = 0;
  const cleanup = graph.render(container, capturedHttp(), {loadRequestData() { calls++; throw new Error("sensitive transport details"); }});
  requestAction(container, "ems").fire("click"); await settleRequestData();
  assert.match(container.querySelector(".tl-graph-request-evidence").textContent, /could not be loaded.*Close and reopen to retry/s);
  assert.doesNotMatch(container.textContent, /sensitive transport details/);
  requestAction(container, "ems").fire("click"); requestAction(container, "ems").fire("click"); await settleRequestData();
  assert.equal(calls, 2); cleanup();
});

test("request data async completions cannot replace another service, a collapsed panel or a disposed graph", async () => {
  const container = documentFixture(), pending = [];
  const cleanup = graph.render(container, capturedHttp(), {loadRequestData: node => new Promise((resolve, reject) => pending.push({node, resolve, reject}))});
  const evidence = text => ({attributes: {httpQueryParametersStatus: "captured", httpQueryParameters: JSON.stringify({text: [text]})}});
  requestAction(container, "app").fire("click"); await settleRequestData();
  requestAction(container, "ems").fire("click"); await settleRequestData();
  pending[0].resolve(evidence("STALE APP DATA")); await settleRequestData();
  assert.doesNotMatch(container.textContent, /STALE APP DATA/);
  pending[1].resolve(evidence("CURRENT EMS DATA")); await settleRequestData();
  assert.match(container.querySelector(".tl-graph-request-evidence").textContent, /CURRENT EMS DATA/);
  requestAction(container, "ems").fire("click"); requestAction(container, "app").fire("click"); await settleRequestData();
  requestAction(container, "app").fire("click"); pending[2].reject(new Error("stale failure")); await settleRequestData();
  assert.equal(container.querySelector(".tl-graph-request-evidence"), null);
  requestAction(container, "ems").fire("click"); await settleRequestData();
  const oldAction = requestAction(container, "ems"); cleanup();
  pending[3].resolve(evidence("AFTER DISPOSAL")); await settleRequestData();
  assert.equal(container.childNodes.length, 0); assert.equal(container.listenerCount(), 0);
  oldAction.fire("click"); await settleRequestData(); assert.equal(pending.length, 4);
});

test("request inspector preserves camera and keyboard focus without reserving canvas space", async () => {
  const input = capturedHttp();
  const second = {...input.nodes[2], id: "ems-2", segmentId: "second-segment"}; input.nodes.push(second);
  const before = JSON.stringify(input), container = documentFixture();
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary() {}, loadRequestData: async () => ({attributes: {}})});
  const viewport = () => container.querySelector(".tl-graph-viewport"), canvas = () => container.querySelector(".tl-graph-canvas");
  container.querySelector(".tl-graph-zoom-out").fire("click"); const zoom = canvas().style.transform;
  viewport().scrollLeft = 72; viewport().scrollTop = 43;
  const firstAction = requestAction(container, "ems"); firstAction.focus(); firstAction.fire("click");
  await settleRequestData();
  assert.equal(canvas().style.transform, zoom); assert.equal(viewport().scrollLeft, 72); assert.equal(viewport().scrollTop, 43);
  assert.equal(container.ownerDocument.activeElement, requestAction(container, "ems"));
  assert.equal((firstAction.listeners.click || []).length, 0, "async redraw detaches replaced action listeners");
  const caller = httpFooter(container, "ems"), action = requestAction(container, "ems"), panel = container.querySelector(".tl-graph-request-evidence");
  assert.ok(container.querySelector(".tl-graph-details").contains(caller));
  assert.ok(container.querySelector(".tl-graph-details").contains(action));
  assert.ok(container.querySelector(".tl-graph-details").contains(panel));
  assert.equal(canvas().contains(panel), false);
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assert.ok(container.querySelector(".tl-graph-request-evidence")); assertDiagramFits(container);
  const fittedZoom = canvas().style.transform;
  assert.equal(viewport().scrollLeft, 0); assert.equal(viewport().scrollTop, 0);
  const expanded = graph.layout(input, {boundaryActions: true, requestDataActions: true, expandedRequestId: "ems"});
  const receiver = expanded.nodes.find(item => item.id === "ems");
  assert.equal(receiver.height, 540); assert.equal(receiver.width, 400);
  for (const item of expanded.nodes) for (const other of expanded.nodes.filter(candidate => candidate !== item)) {
    assert.ok(item.x + item.width <= other.x || other.x + other.width <= item.x
      || item.y + item.height <= other.y || other.y + other.height <= item.y, "expanded request evidence never overlaps another card");
  }
  const currentPanel = container.querySelector(".tl-graph-request-evidence"); currentPanel.focus();
  viewport().scrollLeft = 72; viewport().scrollTop = 43;
  container.querySelector(".tl-graph-details").fire("keydown", {key: "Escape", preventDefault() {}, stopPropagation() {}});
  assert.equal(container.querySelector(".tl-graph-request-evidence"), null);
  assert.equal(container.ownerDocument.activeElement.dataset.nodeId, "ems");
  assert.equal(canvas().style.transform, fittedZoom); assert.equal(viewport().scrollLeft, 72); assert.equal(viewport().scrollTop, 43);
  cleanup(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("request-data actions require the exact-trace loader and shared browser renderer dependency", () => {
  const container = documentFixture(), cleanup = graph.render(container, capturedHttp());
  assert.equal(container.querySelector(".tl-graph-request-toggle"), null); cleanup();
  const source = fs.readFileSync(require.resolve("../tracelens_cli/web/index.html"), "utf8");
  assert.ok(source.indexOf('/static/request-data.js') < source.indexOf('/static/trace-graph.js'));
});

test("HTTP cards and receiving services navigate the original caller independently of service code flow", () => {
  for (const showHttpHops of [true, false]) {
    const input = capturedHttp(), before = JSON.stringify(input), container = documentFixture(), opened = [], services = [];
    freezeTree(input);
    const cleanup = graph.render(container, input, {showNodeDetails: false, showHttpHops,
      onOpenBoundary: value => opened.push(value), onSelect: value => services.push(value)});
    assert.equal(httpFooter(container, "app"), undefined, "the root has no invented incoming caller");
    const receiver = container.all().find(item => item.dataset.nodeId === "ems"), footer = httpFooter(container, "ems");
    assert.ok(footer); assert.ok(container.querySelector(".tl-graph-details").contains(footer)); assert.equal(receiver.contains(footer), false);
    const action = footer.querySelector(".tl-graph-http-caller-action");
    assert.equal(action.textContent, "View caller in cb-app"); assert.equal(action.disabled, false, "HTTP call identity does not require an event sequence");
    assert.match(action.getAttribute("aria-label"), /cb-app.*POST \/features/);
    action.focus(); assert.equal(opened.length, 0); receiver.focus(); assert.equal(services.length, 0);
    receiver.fire("click"); assert.equal(services.length, 0);
    container.querySelector(".tl-graph-source").fire("click"); assert.equal(services[0], input.nodes[2]);
    action.fire("click"); assert.equal(opened[0], input.nodes[1]);
    if (showHttpHops) {
      const callFooter = httpFooter(container, "call"); assert.ok(callFooter);
      callFooter.querySelector(".tl-graph-http-caller-action").fire("click"); assert.equal(opened[1], input.nodes[1]);
    } else assert.equal(container.all().some(item => item.dataset.nodeId === "call"), false);
    assert.ok(container.querySelector(".tl-graph-details"));
    const count = opened.length; cleanup(); action.fire("click");
    assert.equal(opened.length, count); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
  }
});

test("external HTTP destinations retain caller actions outside the service boundary", () => {
  const input = externalHttp(), container = documentFixture(), opened = [];
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary: call => opened.push(call)});
  const card = container.all().find(item => item.dataset.nodeId === "call"), footer = httpFooter(container, "call");
  assert.ok(card.classList.contains("tl-graph-external-service")); assert.ok(footer);
  const action = footer.querySelector(".tl-graph-http-caller-action"); action.fire("click");
  assert.equal(opened[0], input.nodes[1]); assert.equal(opened[0].service, "cb-app");
  const frame = container.querySelector(".tl-graph-service-group");
  assert.ok(parseFloat(frame.style.left) + parseFloat(frame.style.width) < parseFloat(card.style.left));
  assert.ok(container.querySelector(".tl-graph-details").contains(footer));
  cleanup();
});

test("multiple captured incoming HTTP calls offer an accessible explicit caller chooser", () => {
  const input = capturedHttp();
  const second = {...input.nodes[1], id: "second-call", callKey: "second-key", method: "GET", path: "/features/other"};
  input.nodes.push(second); input.edges.push(edge("app", second.id, "outbound"), edge(second.id, "ems"));
  const before = JSON.stringify(input), container = documentFixture(), opened = [];
  freezeTree(input);
  const cleanup = graph.render(container, input, {showHttpHops: false, showNodeDetails: false, onOpenBoundary: call => opened.push(call)});
  const footer = httpFooter(container, "ems"), chooser = footer.querySelector(".tl-graph-http-caller-select"), action = footer.querySelector(".tl-graph-http-caller-action");
  assert.equal(chooser.childNodes.length, 2); assert.match(chooser.getAttribute("aria-label"), /Choose a captured caller/);
  assert.equal(chooser.parentNode, action.parentNode);
  chooser.value = "second-call"; chooser.fire("change"); assert.equal(opened.length, 0);
  assert.match(action.getAttribute("aria-label"), /GET \/features\/other.*second-key/);
  action.fire("click"); assert.equal(opened[0], second);
  chooser.value = "call"; chooser.fire("change"); action.fire("click"); assert.equal(opened[1], input.nodes[1]);
  assert.equal(graph.layout(input, {boundaryActions: true}).nodes.find(item => item.id === "ems").height, 220);
  cleanup(); assert.equal(container.listenerCount(), 0); assert.equal(JSON.stringify(input), before);
});

test("malformed or ambiguous HTTP ownership never fabricates receiving-service calling actions", () => {
  const scenarios = [
    ["missing owner", input => { delete input.nodes[1].ownerId; }],
    ["wrong owner", input => { input.nodes[1].ownerId = "ems"; }],
    ["different service", input => { input.nodes[1].service = "wrong"; }],
    ["different instance", input => { input.nodes[1].serviceInstanceId = "wrong"; }],
    ["different segment", input => { input.nodes[1].segmentId = "wrong"; }],
    ["missing call identity", input => { delete input.nodes[1].callKey; }],
    ["duplicate call identity", input => { input.nodes.push({...input.nodes[1], id: "duplicate"}); input.edges.push(edge("app", "duplicate", "outbound")); }],
    ["ambiguous outbound", input => { input.edges.push(edge("app", "call", "outbound", "extra")); }],
    ["contradictory downstream", input => { input.nodes[1].downstreamState = "not-observed"; }],
    ["root receiver", input => { input.roots.push("ems"); }],
    ["unknown incoming link", input => { input.edges.push(edge("app", "ems")); }],
    ["cycle", input => { input.edges.push(edge("ems", "app")); }],
    ["duplicate node", input => { input.nodes.push({...input.nodes[0]}); }],
    ["duplicate edge", input => { input.edges.push({...input.edges[0]}); }],
    ["dangling edge", input => { input.edges.push(edge("call", "missing")); }],
  ];
  for (const [name, mutate] of scenarios) {
    const input = capturedHttp(); mutate(input); const container = documentFixture(), opened = [];
    const cleanup = graph.render(container, input, {showNodeDetails: false, showHttpHops: true, onOpenBoundary: call => opened.push(call)});
    assert.equal(httpFooter(container, "ems"), undefined, name);
    assert.equal(httpFooter(container, "app"), undefined, name);
    if (!["contradictory downstream", "root receiver", "unknown incoming link"].includes(name)) {
      const action = httpFooter(container, "call").querySelector(".tl-graph-http-caller-action");
      assert.equal(action.disabled, true, name); action.fire("click"); assert.equal(opened.length, 0, name);
    }
    cleanup(); assert.equal(container.listenerCount(), 0, name);
  }
});

test("HTTP footer layout participates in sizing, frames, Fit and hop-toggle auto-fit", () => {
  const input = capturedHttp(), container = documentFixture();
  const positioned = graph.layout(input, {boundaryActions: true});
  assert.equal(positioned.nodes.find(item => item.id === "call").height, 182);
  assert.equal(positioned.nodes.find(item => item.id === "ems").height, 182);
  const cleanup = graph.render(container, input, {showNodeDetails: false, onOpenBoundary() {}});
  const canvas = () => container.querySelector(".tl-graph-canvas"), viewport = () => container.querySelector(".tl-graph-viewport");
  container.querySelector(".tl-graph-zoom-out").fire("click");
  viewport().scrollLeft = 80; viewport().scrollTop = 40;
  const oldAction = httpFooter(container, "ems").querySelector(".tl-graph-http-caller-action");
  container.querySelector(".tl-graph-hop-toggle").fire("click");
  assertDiagramFits(container); assert.equal(viewport().scrollLeft, 0); assert.equal(viewport().scrollTop, 0);
  assert.equal((oldAction.listeners.click || []).length, 0); assert.ok(httpFooter(container, "ems"));
  const footer = httpFooter(container, "ems");
  assert.ok(container.querySelector(".tl-graph-details").contains(footer));
  assert.equal(canvas().contains(footer), false);
  viewport().clientWidth = 300; viewport().clientHeight = 220;
  container.querySelector(".tl-graph-fit").fire("click");
  const surface = container.querySelector(".tl-graph-surface");
  assert.ok(parseFloat(surface.style.width) <= 300); assert.ok(parseFloat(surface.style.height) <= 220);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("HTTP arrow sequence labels survive hop toggles and expose evidence without changing caller actions", () => {
  const input = capturedHttp(); input.nodes[1].startedAtMillis = 100;
  const external = {...input.nodes[1], id: "external", callKey: "external-key", startedAtMillis: 105,
    durationMs: 10, downstreamState: "not-observed", status: "COMPLETED"};
  input.nodes.push(external); input.edges.push(edge("app", "external", "outbound"));
  const before = JSON.stringify(input), container = documentFixture(), opened = [];
  freezeTree(input);
  const cleanup = graph.render(container, input, {onOpenBoundary: call => opened.push(call)});
  const labels = () => container.all().filter(item => item.classList.contains("tl-graph-edge-label") && item.getAttribute("data-call-id"))
    .map(item => [item.getAttribute("data-call-id"), item.childNodes.find(child => child.tagName === "text").textContent])
    .sort((a, b) => a[0].localeCompare(b[0]));
  for (let mode = 0; mode < 3; mode++) {
    assert.deepEqual(labels(), [["call", "1.1 · HTTP"], ["external", "1.2 · HTTP"]]);
    const externalCard = container.all().find(item => item.dataset.nodeId === "external");
    assert.match(externalCard.getAttribute("aria-description"), /overlapping caller-local HTTP intervals/);
    externalCard.fire("click");
    assert.match(container.querySelector(".tl-graph-details").textContent, /HTTP call order.*parallel in-flight/);
    httpFooter(container, "external").querySelector(".tl-graph-http-caller-action").fire("click");
    assert.equal(opened.at(-1), external, "sequence annotations never substitute another caller observation");
    assertDiagramFits(container);
    container.querySelector(".tl-graph-hop-toggle").fire("click");
  }
  assert.match(container.querySelector(".tl-graph-coverage").textContent, /sibling arrows alone do not prove parallel/);
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("inspector selection, evidence expansion and close leave nodes and connectors fixed", () => {
  const input = capturedSql(2), container = documentFixture(), opened = [];
  const cleanup = graph.render(container, input, {onSelect: node => opened.push(node), onOpenBoundary() {}});
  const geometry = () => ({
    cards: container.all().filter(item => item.classList.contains("tl-graph-node"))
      .map(item => [item.dataset.nodeId, item.style.left, item.style.top, item.style.width, item.style.height]),
    edges: container.all().filter(item => item.classList.contains("tl-graph-edge")).map(item => item.getAttribute("d")),
    canvas: [container.querySelector(".tl-graph-canvas").style.width, container.querySelector(".tl-graph-canvas").style.height],
  });
  const original = geometry(), viewport = container.querySelector(".tl-graph-viewport");
  viewport.scrollLeft = 30; viewport.scrollTop = 40;
  const query = container.all().find(item => item.dataset.nodeId === "query-1");
  query.fire("click");
  const call = container.querySelector(".tl-graph-sql-call"); call.open = true; call.fire("toggle");
  assert.ok(container.querySelector(".jdbc-sql"));
  assert.deepEqual(geometry(), original);
  assert.notEqual(container.querySelector(".tl-graph-detail-metadata").open, true, "technical metadata is collapsed by default");
  container.querySelector(".tl-graph-inspector-close").fire("click");
  assert.equal(container.ownerDocument.activeElement, query);
  assert.ok(container.querySelector(".tl-graph-inspector-empty"), "restoring focus does not immediately reopen details");
  assert.equal(viewport.scrollLeft, 30); assert.equal(viewport.scrollTop, 40);
  assert.deepEqual(geometry(), original); assert.deepEqual(opened, []);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("stale lazy SQL completion cannot replace the current inspector or reopen a closed graph", async () => {
  const container = documentFixture(), pending = [], input = capturedSql();
  const cleanup = graph.render(container, input, {loadBoundaryData: observation => new Promise(resolve => pending.push({observation, resolve}))});
  const inspectQuery = () => {
    container.all().find(item => item.dataset.nodeId === "query-1").fire("click");
    const disclosure = container.querySelector(".tl-graph-sql-call"); disclosure.open = true; disclosure.fire("toggle");
  };
  inspectQuery(); await settleRequestData();
  container.all().find(item => item.dataset.nodeId === "ems").fire("click");
  pending[0].resolve({...pending[0].observation, sql: "SELECT 'STALE SQL'"}); await settleRequestData();
  assert.doesNotMatch(container.textContent, /STALE SQL/);
  assert.equal(container.querySelector(".tl-graph-detail-title").textContent, "cb-ems");
  inspectQuery(); await settleRequestData();
  container.querySelector(".tl-graph-inspector-close").fire("click");
  pending[1].resolve({...pending[1].observation, sql: "SELECT 'CLOSED SQL'"}); await settleRequestData();
  assert.ok(container.querySelector(".tl-graph-inspector-empty")); assert.doesNotMatch(container.textContent, /CLOSED SQL/);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("focus selected and focus branch update a bounded inert minimap without changing graph evidence", () => {
  const input = capturedHttp(), container = documentFixture(), before = JSON.stringify(input);
  const cleanup = graph.render(container, input, {showHttpHops: true});
  const viewport = container.querySelector(".tl-graph-viewport"), canvas = container.querySelector(".tl-graph-canvas");
  const card = container.all().find(item => item.dataset.nodeId === "call"); card.fire("click");
  const geometry = canvas.childNodes.filter(item => item.classList.contains("tl-graph-node")).map(item => [item.style.left, item.style.top]);
  container.querySelector(".tl-graph-focus-selected").fire("click");
  const selectedZoom = Number(canvas.style.transform.match(/scale\((.+)\)/)[1]);
  container.querySelector(".tl-graph-focus-branch").fire("click");
  const branchZoom = Number(canvas.style.transform.match(/scale\((.+)\)/)[1]);
  assert.ok(branchZoom <= selectedZoom);
  const minimap = container.querySelector(".tl-graph-minimap"), view = container.querySelector(".tl-graph-minimap-view");
  assert.equal(minimap.getAttribute("aria-hidden"), "true");
  assert.equal(minimap.childNodes.length, input.nodes.length + 1);
  assert.equal(minimap.all().filter(item => item.classList.contains("tl-graph-minimap-selected")).length, 1);
  assert.equal(Number(view.getAttribute("x")), viewport.scrollLeft / branchZoom);
  viewport.scrollLeft = 15; viewport.scrollTop = 20; viewport.fire("scroll");
  assert.equal(Number(view.getAttribute("y")), 20 / branchZoom);
  assert.deepEqual(canvas.childNodes.filter(item => item.classList.contains("tl-graph-node")).map(item => [item.style.left, item.style.top]), geometry);
  assert.equal(JSON.stringify(input), before);
  cleanup(); assert.equal(container.listenerCount(), 0);
});

test("only explicit narrow-screen card activation reveals and focuses the inspector", () => {
  for (const narrow of [true, false]) {
    const container = documentFixture(), opened = [], scrolls = [];
    container.ownerDocument.defaultView = {matchMedia: query => {
      assert.equal(query, "(max-width: 900px)"); return {matches: narrow};
    }};
    const cleanup = graph.render(container, capturedHttp(), {onSelect: node => opened.push(node)});
    const inspector = container.querySelector(".tl-graph-details");
    inspector.scrollIntoView = options => scrolls.push(options);
    const card = container.all().find(item => item.dataset.nodeId === "ems");
    card.focus();
    assert.equal(container.ownerDocument.activeElement, card);
    assert.equal(scrolls.length, 0, "roving focus does not scroll the page to the inspector");
    card.fire("click");
    assert.equal(container.ownerDocument.activeElement, narrow ? inspector : card);
    assert.equal(scrolls.length, narrow ? 1 : 0);
    if (narrow) assert.deepEqual(scrolls[0], {block: "nearest", inline: "nearest"});
    assert.equal(opened.length, 0, "activation only inspects; source navigation remains explicit");
    container.querySelector(".tl-graph-inspector-close").fire("click");
    assert.equal(container.ownerDocument.activeElement, card);
    assert.ok(container.querySelector(".tl-graph-inspector-empty"));
    cleanup(); assert.equal(container.listenerCount(), 0);
  }
  const css = fs.readFileSync(require.resolve("../tracelens_cli/web/trace-graph.css"), "utf8");
  assert.match(css, /@media \(max-width: 520px\)\s*\{\s*\.tl-graph-details \.tl-graph-detail-list\s*\{\s*grid-template-columns: 1fr/);
});
