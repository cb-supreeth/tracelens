/* Shared, dependency-free causal request diagram. No timestamps participate in layout. */
(function (root, factory) {
  "use strict";
  const commonjs = typeof module === "object" && module.exports;
  const api = factory(commonjs ? require("./dependency-ui.js") : root.TraceLensDependencies,
    commonjs ? require("./request-data.js") : root.TraceLensRequestData);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensGraph = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (dependencies, requestData) {
  "use strict";

  const LIMITS = Object.freeze({ nodes: 96, columns: 24, edges: 384, inputNodes: 4096, inputEdges: 16384, routeRows: 128, sqlRows: 50 });
  const BOX = Object.freeze({ width: 264, height: 144, columnGap: 100, rowGap: 34, padding: 64 });
  // A maximum-sized bounded diagram can exceed 20,000px vertically. Keep the
  // lower bound small enough for Fit to work even in the 220px minimum viewport.
  const ZOOM = Object.freeze({ min: .005, max: 2, factor: 1.2 });
  const boundaryActionHeight = 38;
  const requestPanelHeight = 320;
  const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  const scalar = value => ["string", "number", "boolean"].includes(typeof value) ? String(value) : "";
  const jdbc = node => dependencies?.jdbcDetails(node);
  const clickhouseClient = node => node?.boundaryKind === "database" && node.system === "clickhouse"
    && node.direction === "outbound" && node.evidenceKind === "client-boundary"
    && ["query", "queryRecords", "execute", "insert", "getTableSchema", "getTableSchemaFromQuery"].includes(node.operation)
    && ["OBSERVED", "SUBMITTED", "ERROR"].includes(node.status);
  const dbGroup = node => node.presentationKind === "jdbc-group" && node.evidenceKind === "jdbc-group";
  const systemNames = new Map([["mysql", "MySQL"], ["postgresql", "PostgreSQL"], ["clickhouse", "ClickHouse"], ["redis", "Redis / Valkey"], ["dynamodb", "DynamoDB"], ["s3", "S3"]]);
  const systemName = value => systemNames.get(value) || scalar(value);
  const idOf = value => typeof value === "string" && value.length ? value : null;
  const numeric = value => value !== null && value !== undefined && value !== "" &&
    (typeof value === "number" || typeof value === "string") && Number.isFinite(Number(value)) && Number(value) >= 0
    ? Number(value) : null;
  const duration = value => {
    const number = numeric(value);
    return number === null ? "Unavailable" : `${number.toLocaleString(undefined, { maximumFractionDigits: 2 })} ms`;
  };
  const rawApi = node => node.kind === "dependency-call"
    ? [scalar(node.system), scalar(node.operation)].filter(Boolean).join(" · ") || "Operation unavailable"
    : [scalar(node.method), scalar(node.path)].filter(Boolean).join(" ") || scalar(node.label) || "API unavailable";
  const boundaryType = node => node.kind === "segment" ? "SERVICE SEGMENT" : node.kind === "http-call" ? "OUTGOING HTTP"
    : node.boundaryKind === "messaging" ? node.direction === "consume" ? "QUEUE CONSUME"
      : node.direction === "publish" ? "QUEUE PUBLISH" : "MESSAGING BOUNDARY"
    : node.boundaryKind === "database-route" ? "DB ROUTING DECISION"
    : dbGroup(node) ? "DB CALL"
    : jdbc(node) ? dependencies.isSqlLimit(node) ? "SQL CAPTURE LIMIT" : "SQL QUERY"
    : node.evidenceKind === "client-boundary" && node.boundaryKind === "database" ? "DATABASE CALL"
    : node.evidenceKind === "client-boundary" && node.boundaryKind === "cache" ? "CACHE CALL"
    : node.evidenceKind === "wrapper-entry" && node.boundaryKind === "cache" ? "CACHE ACCESS"
    : node.evidenceKind === "wrapper-entry" ? "EXTERNAL WRAPPER" : "EXTERNAL BOUNDARY";
  const boundaryEvidence = node => node.kind === "http-call" ? "Caller-side HTTP observation; no remote execution is inferred"
    : dbGroup(node) ? "Database observations grouped within this service segment and recorded destination. An unspecified destination does not establish a shared server. Expand to inspect each retained call; client observations are not SQL execution counts and durations are not combined."
    : jdbc(node) ? dependencies.isSqlLimit(node) ? "Query capture limit reached; additional executions were not retained"
      : dependencies.summaryBoundary(node).evidence
    : node.evidenceKind === "client-boundary" && ["database", "cache"].includes(node.boundaryKind)
      ? "Local client operation; transaction/pipeline completion, cache hit/miss and remote execution are not inferred"
    : node.evidenceKind === "client-boundary" ? node.direction === "consume"
      ? "Local consume boundary; message receipt, producer and processing completion are not inferred"
      : "Local client boundary; broker delivery and remote processing are not implied"
    : node.boundaryKind === "database-route" ? "Pool selection only; no database query or network success implied"
    : node.evidenceKind === "wrapper-entry" && node.boundaryKind === "cache"
      ? "Observed cache wrapper invocation; cache hit/miss and remote execution are not established"
    : node.evidenceKind === "wrapper-entry" ? "Configured wrapper entry only; a network operation is not confirmed"
    : "Boundary recorded; evidence kind unknown; a network operation is not confirmed";
  const failure = node => Number(node.httpStatus) >= 400 || /^(error|failed|failure)$/i.test(scalar(node.status))
    || (node.entryChannel === "SCHEDULER" && node.jobOutcome === "FAILED");
  const category = node => node.kind === "segment" ? "service" : node.kind === "http-call" ? "http"
    : node.boundaryKind === "database-route" || dbGroup(node) || jdbc(node) || node.evidenceKind === "client-boundary" && node.boundaryKind === "database" ? "database"
    : ["client-boundary", "wrapper-entry"].includes(node.evidenceKind) && node.boundaryKind === "cache" ? "cache"
    : node.boundaryKind === "messaging" ? "queue" : "external";

  function edgeAppearance(edge, from, to) {
    const boundary = from.kind !== "segment" ? from : to.kind !== "segment" ? to : null;
    if (edge.kind === "observed-call") return {category: "service",
      label: edge.foldedCallId || from.kind === "http-call" ? "HTTP" : "observed"};
    const kind = boundary ? category(boundary) : "external";
    const label = kind === "database" ? boundary.boundaryKind === "database-route" ? "DB route"
      : jdbc(boundary) ? dependencies.isSqlLimit(boundary) ? "limit" : "SQL" : "DB call"
      : kind === "cache" ? boundary.evidenceKind === "wrapper-entry" ? "cache access" : "cache" : kind === "http" ? "HTTP"
      : kind === "queue" && ["publish", "consume"].includes(boundary.direction) ? boundary.direction
      : boundary?.evidenceKind === "wrapper-entry" ? "wrapper" : "boundary";
    return {category: kind, label};
  }

  function ownsObservation(owner, node) {
    return node.kind !== "segment" && node.ownerId === owner.id
      && !["service", "serviceInstanceId", "segmentId", "segmentKey"].some(key =>
        node[key] != null && owner[key] != null && node[key] !== owner[key])
      && ["segmentId", "segmentKey"].some(key => idOf(node[key]) && node[key] === owner[key]);
  }

  // These frames group captured observations, not deployed components or database
  // ownership. A name/host match alone must never establish service membership.
  function serviceGroups(nodes, edges) {
    const candidates = [];
    for (const owner of nodes.filter(item => item.node.kind === "segment")) {
      const observations = nodes.filter(item => {
        const node = item.node;
        if (!ownsObservation(owner.node, node) || item.cyclic || owner.cyclic) return false;
        return edges.some(edge => edge.kind === "outbound" && edge.from === owner.id && edge.to === item.id
          || edge.kind === "inbound" && edge.from === item.id && edge.to === owner.id && node.direction === "consume");
      });
      const observedReceiver = !owner.cyclic && idOf(owner.node.serviceInstanceId) && idOf(owner.node.segmentId)
        && edges.some(edge => edge.kind === "observed-call" && edge.to === owner.id);
      if (!observations.length && !observedReceiver) continue;
      // An unobserved HTTP card represents the destination, not execution inside
      // this service. Keep the caller frame even when it has no other boundaries.
      const members = observations.filter(item => !item.externalService);
      const items = [owner, ...members], nodeIds = items.map(item => item.id).sort(compare);
      const x = Math.min(...items.map(item => item.x)) - 20, y = Math.min(...items.map(item => item.y)) - 48;
      const frame = {id: owner.id, ownerId: owner.id, nodeIds,
        name: scalar(owner.node.service) || "Captured service", x, y,
        width: Math.max(...items.map(item => item.x + item.width)) + 20 - x,
        height: Math.max(...items.map(item => item.y + item.height)) + 20 - y};
      // Conservative fallback for interleaved/complex graphs: no frame is better
      // than a rectangle that visually claims an unrelated node.
      if (!nodes.some(item => !nodeIds.includes(item.id) && intersects(frame, item))) candidates.push(frame);
    }
    return candidates.filter(frame => !candidates.some(other => other !== frame && intersects(frame, other)));
  }

  function intersects(a, b) {
    return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
  }

  // Lay out a captured invocation and its own observations as one unit. Sorting
  // each column independently interleaves sibling invocations (and makes their
  // ownership frames disappear). Only apply this packing to an unambiguous
  // forward forest; malformed/cyclic/shared evidence keeps the bounded fallback.
  function packInvocationLanes(nodes, edges, satelliteOwners) {
    const main = nodes.filter(item => !satelliteOwners.has(item.id));
    if (main.some(item => item.cyclic)) return;
    const blockOf = new Map(), blocks = [];
    for (const owner of main.filter(item => item.node.kind === "segment")) {
      const members = main.filter(item => ownsObservation(owner.node, item.node) && edges.some(edge =>
        edge.kind === "outbound" && edge.from === owner.id && edge.to === item.id));
      const block = {id: owner.id, owner, members: [owner, ...members], children: new Set(), parents: new Set()};
      blocks.push(block); block.members.forEach(item => blockOf.set(item.id, block));
    }
    for (const item of main) if (!blockOf.has(item.id)) {
      const block = {id: item.id, owner: item, members: [item], children: new Set(), parents: new Set()};
      blocks.push(block); blockOf.set(item.id, block);
    }
    if (!blocks.some(block => block.members.length > 1)) return;
    for (const edge of edges) {
      const from = blockOf.get(edge.from), to = blockOf.get(edge.to);
      if (!from || !to || from === to) continue;
      if (Math.max(...from.members.map(item => item.rank)) >= Math.min(...to.members.map(item => item.rank))) return;
      from.children.add(to); to.parents.add(from);
    }
    if (blocks.some(block => block.parents.size > 1)) return;
    // Service/path order keeps repeated calls beside one another without merging
    // their records or using wall-clock timing as a causal relationship.
    const order = (a, b) => compare(scalar(a.owner.node.service), scalar(b.owner.node.service))
      || compare(rawApi(a.owner.node), rawApi(b.owner.node)) || compare(a.id, b.id);
    for (const block of blocks) {
      block.columns = new Map();
      for (const item of block.members) {
        if (!block.columns.has(item.rank)) block.columns.set(item.rank, []);
        block.columns.get(item.rank).push(item);
      }
      const targetOf = item => {
        const targets = [...new Set(edges.filter(edge => edge.from === item.id && edge.kind === "observed-call"
          && blockOf.has(edge.to) && blockOf.get(edge.to) !== block).map(edge => blockOf.get(edge.to)))];
        return targets.length === 1 ? targets[0] : null;
      };
      for (const items of block.columns.values()) items.sort((a, b) => {
        const at = targetOf(a), bt = targetOf(b);
        return at && bt ? order(at, bt) : at ? -1 : bt ? 1 : compare(a.id, b.id);
      });
      block.contentHeight = Math.max(...[...block.columns.values()].map(items =>
        items.reduce((sum, item) => sum + item.height, 0) + (items.length - 1) * BOX.rowGap));
      const satellites = nodes.filter(item => satelliteOwners.get(item.id) === block.owner.id);
      block.localHeight = block.contentHeight + 68 + (satellites.length
        ? 52 + satellites.reduce((sum, item) => sum + item.height + 40, 0) : 0);
    }
    const gap = 40;
    function measure(block) {
      block.orderedChildren = [...block.children].sort(order);
      block.childrenHeight = block.orderedChildren.reduce((sum, child) => sum + measure(child), 0)
        + Math.max(0, block.children.size - 1) * gap;
      block.height = Math.max(block.localHeight, block.childrenHeight);
      return block.height;
    }
    function place(block, top) {
      const contentTop = top + (block.height - block.localHeight) / 2 + 48;
      for (const items of block.columns.values()) {
        const height = items.reduce((sum, item) => sum + item.height, 0) + (items.length - 1) * BOX.rowGap;
        let y = contentTop + (block.contentHeight - height) / 2;
        for (const item of items) { item.y = y; y += item.height + BOX.rowGap; }
      }
      let childTop = top + (block.height - block.childrenHeight) / 2;
      for (const child of block.orderedChildren) { place(child, childTop); childTop += child.height + gap; }
    }
    let top = BOX.padding - 48;
    for (const block of blocks.filter(block => !block.parents.size).sort(order)) {
      measure(block); place(block, top); top += block.height + gap;
    }
  }

  // Route long (folded) hops around the actual local cards, not around the top
  // of the whole canvas. Candidate rails come from card edges, so this remains
  // deterministic, bounded and valid when SQL/request details change size.
  function foldedHopPath(from, to, nodes, offset) {
    const startX = from.x + from.width, startY = from.y + from.height / 2 + offset;
    const targetY = to.y + BOX.height / 2 + offset;
    const columnRight = Math.max(...nodes.filter(item => item.rank === from.rank).map(item => item.x + item.width));
    const exitX = columnRight + 30 + offset, entryX = to.x - 30 - offset;
    const obstacles = nodes.filter(item => item.id !== from.id && item.id !== to.id);
    const clear = (x1, y1, x2, y2) => !obstacles.some(item => x1 === x2
      ? x1 > item.x - 8 && x1 < item.x + item.width + 8 && Math.max(y1, y2) > item.y - 8 && Math.min(y1, y2) < item.y + item.height + 8
      : y1 > item.y - 8 && y1 < item.y + item.height + 8 && Math.max(x1, x2) > item.x - 8 && Math.min(x1, x2) < item.x + item.width + 8);
    const rails = [...new Set([targetY, startY, ...obstacles.flatMap(item => [item.y - 24, item.y + item.height + 24])])]
      .filter(y => y >= 12).sort((a, b) => Math.abs(startY - a) + Math.abs(targetY - a)
        - Math.abs(startY - b) - Math.abs(targetY - b) || Math.abs(targetY - a) - Math.abs(targetY - b) || a - b);
    for (const y of rails) if (clear(startX, startY, exitX, startY) && clear(exitX, startY, exitX, y)
      && clear(exitX, y, entryX, y) && clear(entryX, y, entryX, targetY) && clear(entryX, targetY, to.x, targetY)) {
      return `M ${startX} ${startY} H ${exitX} V ${y} H ${entryX} V ${targetY} H ${to.x}`;
    }
    return null;
  }

  // Caller navigation uses original HTTP observations, never a receiver's name,
  // peer or presentation-only folded edge as a substitute for recorded identity.
  function httpCallingContexts(model) {
    const empty = () => ({calls: new Map(), receivers: new Map()});
    if (!model || !Array.isArray(model.nodes) || !Array.isArray(model.edges)
        || model.nodes.length > LIMITS.inputNodes || model.edges.length > LIMITS.inputEdges) return empty();
    const nodes = new Map(), incoming = new Map(), outgoing = new Map(), edges = new Set(), keys = new Map();
    for (const node of model.nodes) {
      if (!node || !idOf(node.id) || nodes.has(node.id) || !["segment", "http-call", "dependency-call"].includes(node.kind)) return empty();
      nodes.set(node.id, node); incoming.set(node.id, []); outgoing.set(node.id, []);
      if (node.kind === "http-call" && idOf(node.callKey)) {
        const key = JSON.stringify([node.ownerId, node.callKey]); keys.set(key, (keys.get(key) || 0) + 1);
      }
    }
    for (const edge of model.edges) {
      if (!edge || !idOf(edge.id) || edges.has(edge.id) || !nodes.has(edge.from) || !nodes.has(edge.to)
          || !["outbound", "inbound", "observed-call"].includes(edge.kind)) return empty();
      edges.add(edge.id); incoming.get(edge.to).push(edge); outgoing.get(edge.from).push(edge);
    }
    if (model.roots != null && (!Array.isArray(model.roots) || model.roots.length > LIMITS.inputNodes || model.roots.some(id => !nodes.has(id)))) return empty();
    const roots = new Set(model.roots || []), degrees = new Map([...incoming].map(([id, links]) => [id, links.length]));
    const ready = [...nodes.keys()].filter(id => degrees.get(id) === 0), acyclic = new Set();
    for (let i = 0; i < ready.length; i++) {
      const id = ready[i]; acyclic.add(id);
      for (const link of outgoing.get(id)) { degrees.set(link.to, degrees.get(link.to) - 1); if (!degrees.get(link.to)) ready.push(link.to); }
    }
    const result = empty();
    for (const call of model.nodes) {
      if (call.kind !== "http-call" || roots.has(call.id) || !acyclic.has(call.id) || !idOf(call.callKey)
          || keys.get(JSON.stringify([call.ownerId, call.callKey])) !== 1) continue;
      const owner = nodes.get(call.ownerId), links = incoming.get(call.id);
      if (!owner || owner.kind !== "segment" || !acyclic.has(owner.id) || links.length !== 1
          || links[0].kind !== "outbound" || links[0].from !== owner.id) continue;
      if (["service", "serviceInstanceId", "segmentId"].some(key => !idOf(call[key]) || call[key] !== owner[key])
          || [call.segmentKey, owner.segmentKey].some(value => value != null && !idOf(value))
          || call.segmentKey != null && owner.segmentKey != null && call.segmentKey !== owner.segmentKey) continue;
      result.calls.set(call.id, call);
    }
    for (const target of model.nodes) {
      if (target.kind !== "segment" || roots.has(target.id) || !acyclic.has(target.id)) continue;
      const links = incoming.get(target.id);
      if (!links.length || links.some(link => link.kind !== "observed-call" || !result.calls.has(link.from))) continue;
      const calls = links.map(link => result.calls.get(link.from));
      if (calls.some(call => outgoing.get(call.id).length !== 1 || call.ownerId === target.id
          || call.downstreamState != null && call.downstreamState !== "observed")) continue;
      result.receivers.set(target.id, calls.sort((a, b) => compare(a.id, b.id)));
    }
    return result;
  }

  function httpActionRows(node, contexts) {
    return node.kind === "http-call" ? 1 : node.kind === "segment" && contexts.receivers.has(node.id)
      ? contexts.receivers.get(node.id).length > 1 ? 2 : 1 : 0;
  }

  // Number logical HTTP calls, not both halves of an expanded HTTP hop. The
  // ordering graph combines captured parentage with non-overlapping intervals
  // from ONE caller invocation. Never compare clocks belonging to two services.
  function httpCallOrder(model, contexts) {
    contexts = contexts || httpCallingContexts(model);
    const calls = [...contexts.calls.values()].sort((a, b) => compare(a.id, b.id));
    const result = new Map(), unknown = reason => ({label: "?", kind: "unknown", description: `HTTP call order unknown: ${reason}`});
    if (!calls.length) return result;
    const receivers = new Map();
    for (const [target, linked] of contexts.receivers) linked.forEach(call => receivers.set(call.id, target));
    const finish = () => {
      for (const call of calls) if (result.has(call.id)) result.set(call.id,
        {...result.get(call.id), ownerId: call.ownerId, receiverId: receivers.get(call.id) || null});
      return result;
    };
    if (calls.length > LIMITS.edges) {
      calls.forEach(call => result.set(call.id, unknown("order-analysis limit reached; no sequence is inferred.")));
      return finish();
    }
    const nodeById = new Map(model.nodes.map(node => [node.id, node]));
    const next = new Map(calls.map(call => [call.id, new Set()]));
    const degree = new Map(calls.map(call => [call.id, 0])), levels = new Map(calls.map(call => [call.id, 1]));
    const owners = new Map(), intervals = new Map(), uncertain = new Set(), overlaps = new Set();
    const pair = (a, b) => JSON.stringify([a, b].sort(compare));
    const precedes = (a, b) => {
      if (a === b || !next.has(a) || !next.has(b) || next.get(a).has(b)) return;
      next.get(a).add(b); degree.set(b, degree.get(b) + 1);
    };
    for (const call of calls) {
      if (!owners.has(call.ownerId)) owners.set(call.ownerId, []);
      owners.get(call.ownerId).push(call);
      const parents = contexts.receivers.get(call.ownerId) || [];
      if (parents.length > 1) uncertain.add(call.id);
      else if (parents.length === 1) precedes(parents[0].id, call.id);
      const start = numeric(call.startedAtMillis), elapsed = numeric(call.durationMs);
      if (start !== null && Number.isSafeInteger(start) && elapsed !== null
          && start + elapsed <= Number.MAX_SAFE_INTEGER && ["COMPLETED", "ERROR"].includes(call.status)) {
        intervals.set(call.id, {start, end: start + elapsed});
      }
    }
    // A malformed sibling must not silently disappear from a caller's order.
    for (const edge of model.edges) if (edge.kind === "outbound" && owners.has(edge.from)
        && nodeById.get(edge.to)?.kind === "http-call" && !contexts.calls.has(edge.to)) {
      owners.get(edge.from).forEach(call => uncertain.add(call.id));
    }
    for (const siblings of owners.values()) for (let i = 0; i < siblings.length; i++) for (let j = i + 1; j < siblings.length; j++) {
      const a = siblings[i], b = siblings[j], first = intervals.get(a.id), second = intervals.get(b.id);
      // Starts have millisecond precision; reserve a full millisecond before
      // claiming either separation or overlap from a monotonic local duration.
      if (first && second && first.end + 1 <= second.start) precedes(a.id, b.id);
      else if (first && second && second.end + 1 <= first.start) precedes(b.id, a.id);
      else if (first && second && Math.min(first.end, second.end) - Math.max(first.start, second.start) > 1) overlaps.add(pair(a.id, b.id));
      else { uncertain.add(a.id); uncertain.add(b.id); }
    }
    const ready = calls.filter(call => !degree.get(call.id)).map(call => call.id), visited = new Set();
    for (let i = 0; i < ready.length; i++) {
      const id = ready[i]; visited.add(id);
      for (const target of next.get(id)) {
        levels.set(target, Math.max(levels.get(target), levels.get(id) + 1));
        degree.set(target, degree.get(target) - 1);
        if (!degree.get(target)) ready.push(target);
      }
    }
    const stages = new Map();
    for (const call of calls) {
      if (!visited.has(call.id)) { result.set(call.id, unknown("conflicting or cyclic order evidence.")); continue; }
      const level = levels.get(call.id);
      if (!stages.has(level)) stages.set(level, []);
      stages.get(level).push(call);
    }
    for (const [level, stage] of [...stages].sort((a, b) => a[0] - b[0])) {
      const parallel = stage.length > 1 && stage.every(call => call.ownerId === stage[0].ownerId
        && stage.every(other => other === call || overlaps.has(pair(call.id, other.id))));
      const known = !stage.some(call => uncertain.has(call.id)) && (stage.length === 1 || parallel);
      stage.sort((a, b) => (intervals.get(a.id)?.start || 0) - (intervals.get(b.id)?.start || 0) || compare(a.id, b.id));
      stage.forEach((call, index) => {
        const label = parallel ? `${level}.${index + 1}` : String(level);
        result.set(call.id, known ? {label, kind: parallel ? "overlap" : "ordered", stage: level,
          description: parallel
            ? `HTTP call ${label}: overlapping caller-local HTTP intervals in this invocation (parallel in-flight calls). Decimal suffixes identify branches, not completion order.`
            : `HTTP call ${label}: captured causal/local order stage ${level}, not a global wall-clock position.`}
          : unknown("missing or incomparable caller-local timing; sibling arrows alone do not prove parallel calls."));
        if (!known) for (const target of next.get(call.id)) uncertain.add(target);
      });
    }
    return finish();
  }

  function httpOrderEdge(edge, orders) {
    const callId = edge.foldedCallId || (edge.kind === "outbound" ? edge.to : null);
    const order = callId && orders.get(callId);
    const matches = order && edge.from === order.ownerId && (edge.foldedCallId
      ? edge.kind === "observed-call" && edge.to === order.receiverId
      : edge.kind === "outbound" && edge.to === callId);
    return matches ? {callId, order, label: `${order.label} · HTTP`} : null;
  }

  function orderLabelPosition(path, nodes, text) {
    const tokens = path.match(/[MHV]|-?\d+(?:\.\d+)?/g) || [], segments = [];
    let x = 0, y = 0;
    for (let i = 0; i < tokens.length;) {
      const command = tokens[i++];
      if (command === "M") { x = Number(tokens[i++]); y = Number(tokens[i++]); }
      else if (command === "H") {
        const end = Number(tokens[i++]); segments.push({x: (x + end) / 2, y, width: Math.abs(end - x)}); x = end;
      } else if (command === "V") y = Number(tokens[i++]);
      else return {};
    }
    const width = text.length * 6.5 + 14;
    for (const segment of segments.sort((a, b) => b.width - a.width)) {
      const box = {x: segment.x - width / 2, y: segment.y - 24, width, height: 20};
      if (segment.width >= width && !nodes.some(node => intersects(box, node))) return {labelX: segment.x, labelY: segment.y - 14};
    }
    return {};
  }

  // Naming is a display hint, never a join or a source-navigation key. An
  // observed receiver names its exact call; an unobserved call can reuse a
  // unique peer/service association from this same capture, clearly marked as
  // inferred. Shared gateways, malformed links and unknown peers stay unnamed.
  // Do not consult today's registry or modify historical snapshot records.
  function httpDestinationLabels(model, contexts) {
    const result = new Map();
    if (!contexts.calls.size || !Array.isArray(model.nodes) || !Array.isArray(model.edges)) return result;
    const safeName = value => typeof value === "string" && /^[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}$/.test(value) ? value : null;
    const peerKey = value => typeof value === "string" && value.length <= 256
      && /^(?:[A-Za-z0-9._-]+|\[[A-Fa-f0-9:.%_-]+\])(?::[0-9]{1,5})?$/.test(value) ? value.toLowerCase() : null;
    const nodes = new Map(model.nodes.map(node => [node.id, node]));
    const targets = new Map(), peers = new Map(), blocked = new Set(), exact = new Map();
    for (const edge of model.edges) if (edge.kind === "observed-call") {
      if (!targets.has(edge.from)) targets.set(edge.from, new Set());
      targets.get(edge.from).add(edge.to);
    }
    for (const call of model.nodes.filter(node => node.kind === "http-call")) {
      const peer = peerKey(call.peer), receiverIds = [...(targets.get(call.id) || [])];
      if (!peer || !receiverIds.length) continue;
      const receiver = receiverIds.length === 1 && nodes.get(receiverIds[0]);
      const name = safeName(receiver?.service);
      if (!name || !contexts.receivers.get(receiver.id)?.some(item => item.id === call.id)) {
        blocked.add(peer); continue;
      }
      exact.set(call.id, name);
      if (!peers.has(peer)) peers.set(peer, new Set());
      peers.get(peer).add(name);
    }
    for (const call of model.nodes.filter(node => node.kind === "http-call")) {
      const peer = peerKey(call.peer);
      // A configured/captured name takes precedence over any display inference.
      if (scalar(call.destinationName) && peerKey(call.destinationName) !== peer) continue;
      if (exact.has(call.id)) result.set(call.id, {name: exact.get(call.id), source: "observed", peer: call.peer});
      else if (contexts.calls.has(call.id) && peer && !blocked.has(peer) && peers.get(peer)?.size === 1) {
        result.set(call.id, {name: [...peers.get(peer)][0], source: "inferred", peer: call.peer});
      }
    }
    return result;
  }

  const destinationNameEvidence = label => label.source === "observed"
    ? `Destination ${label.name} (${label.peer}): linked to its captured service segment.`
    : `Destination ${label.name} (${label.peer}): inferred from another observed HTTP call to this exact peer in the same capture. This call's remote execution was not captured.`;

  function presentation(model) {
    const unchanged = () => ({model, foldedHops: []});
    if (!model || typeof model !== "object" || !Array.isArray(model.nodes) || !Array.isArray(model.edges)
        || model.nodes.length > LIMITS.inputNodes || model.edges.length > LIMITS.inputEdges) return unchanged();
    const byId = new Map(), incoming = new Map(), outgoing = new Map(), edgeIds = new Set();
    // Incomplete/malformed topology is not a safe basis for hiding a hop. The
    // raw bounded renderer still displays it and reports validation problems.
    for (const node of model.nodes) {
      if (!node || !idOf(node.id) || node.id.length > 4096 || byId.has(node.id)
          || !["segment", "http-call", "dependency-call"].includes(node.kind)) return unchanged();
      byId.set(node.id, node); incoming.set(node.id, []); outgoing.set(node.id, []);
    }
    for (const edge of model.edges) {
      if (!edge || !idOf(edge.id) || edgeIds.has(edge.id) || !byId.has(edge.from) || !byId.has(edge.to)
          || !["outbound", "inbound", "observed-call"].includes(edge.kind)) return unchanged();
      edgeIds.add(edge.id); outgoing.get(edge.from).push(edge); incoming.get(edge.to).push(edge);
    }
    if (model.roots != null && (!Array.isArray(model.roots) || model.roots.length > LIMITS.inputNodes || model.roots.some(id => !byId.has(id)))) return unchanged();
    const roots = new Set(model.roots || []);
    // Kahn's bounded pass is intentionally conservative: cycles and their
    // downstream region remain expanded, with their original evidence intact.
    const degrees = new Map([...incoming].map(([id, edges]) => [id, edges.length]));
    const ready = [...byId.keys()].filter(id => degrees.get(id) === 0), acyclic = new Set();
    for (let index = 0; index < ready.length; index++) {
      const id = ready[index]; acyclic.add(id);
      for (const edge of outgoing.get(id)) {
        degrees.set(edge.to, degrees.get(edge.to) - 1);
        if (degrees.get(edge.to) === 0) ready.push(edge.to);
      }
    }
    const foldedHops = [];
    for (const call of [...byId.values()].sort((a, b) => compare(a.id, b.id))) {
      if (call.kind !== "http-call" || roots.has(call.id) || !acyclic.has(call.id)) continue;
      const before = incoming.get(call.id), after = outgoing.get(call.id);
      if (before.length !== 1 || after.length !== 1 || before[0].kind !== "outbound" || after[0].kind !== "observed-call") continue;
      const source = byId.get(before[0].from), target = byId.get(after[0].to);
      if (source.kind !== "segment" || target.kind !== "segment" || source.id === target.id
          || !acyclic.has(source.id) || !acyclic.has(target.id) || incoming.get(target.id).length !== 1 || roots.has(target.id)) continue;
      if (("ownerId" in call && call.ownerId !== source.id)
          || ("downstreamState" in call && call.downstreamState !== "observed")) continue;
      if (call.segmentId != null && source.segmentId != null && call.segmentId !== source.segmentId) continue;
      if (call.segmentKey != null && source.segmentKey != null && call.segmentKey !== source.segmentKey) continue;
      foldedHops.push({call, source, target, outbound: before[0], observed: after[0]});
    }
    if (!foldedHops.length) return unchanged();
    const hidden = new Set(foldedHops.map(hop => hop.call.id));
    const replaced = new Set(foldedHops.flatMap(hop => [hop.outbound.id, hop.observed.id]));
    const edges = model.edges.filter(edge => !replaced.has(edge.id));
    for (const hop of foldedHops) {
      let id = "presentation:" + JSON.stringify([hop.outbound.id, hop.observed.id]);
      while (edgeIds.has(id)) id += ":folded";
      edgeIds.add(id);
      edges.push({id, from: hop.source.id, to: hop.target.id, kind: "observed-call", foldedCallId: hop.call.id});
    }
    return {model: {...model, nodes: model.nodes.filter(node => !hidden.has(node.id)), edges,
      roots: (model.roots || []).filter(id => !hidden.has(id))}, foldedHops};
  }

  // A presentation-only destination summary, never a rewrite of recorded events.
  // Only explicitly owned, equivalent DB-route leaves may share a card.
  function groupDatabaseRoutes(model) {
    const unchanged = () => ({model, groups: []});
    if (!model || !Array.isArray(model.nodes) || !Array.isArray(model.edges)
        || model.nodes.length > LIMITS.inputNodes || model.edges.length > LIMITS.inputEdges) return unchanged();
    const byId = new Map(), incoming = new Map(), outgoing = new Map(), edgeIds = new Set(), callKeys = new Map();
    for (const node of model.nodes) {
      if (!node || !idOf(node.id) || node.id.length > 4096 || byId.has(node.id)
          || !["segment", "http-call", "dependency-call"].includes(node.kind)) return unchanged();
      byId.set(node.id, node); incoming.set(node.id, []); outgoing.set(node.id, []);
      if (node.callKey) callKeys.set(node.callKey, (callKeys.get(node.callKey) || 0) + 1);
    }
    for (const edge of model.edges) {
      if (!edge || !idOf(edge.id) || edgeIds.has(edge.id) || !byId.has(edge.from) || !byId.has(edge.to)
          || !["outbound", "inbound", "observed-call"].includes(edge.kind)) return unchanged();
      edgeIds.add(edge.id); incoming.get(edge.to).push(edge); outgoing.get(edge.from).push(edge);
    }
    if (model.roots != null && (!Array.isArray(model.roots) || model.roots.length > LIMITS.inputNodes || model.roots.some(id => !byId.has(id)))) return unchanged();
    const roots = new Set(model.roots || []), buckets = new Map();
    const degrees = new Map([...incoming].map(([id, edges]) => [id, edges.length]));
    const ready = [...byId.keys()].filter(id => !degrees.get(id)), acyclic = new Set();
    for (let index = 0; index < ready.length; index++) {
      const id = ready[index]; acyclic.add(id);
      for (const edge of outgoing.get(id)) {
        degrees.set(edge.to, degrees.get(edge.to) - 1);
        if (degrees.get(edge.to) === 0) ready.push(edge.to);
      }
    }
    for (const node of model.nodes) {
      if (node.kind !== "dependency-call" || node.boundaryKind !== "database-route" || node.system !== "database"
          || node.direction !== "outbound" || node.evidenceKind !== "routing-marker" || node.operation !== "route"
          || node.status !== "OBSERVED" || node.errorType || node.httpStatus != null || node.durationMs != null
          || (node.downstreamState != null && node.downstreamState !== "not-observed")
          || typeof node.destination !== "string" || !node.destination.trim() || node.destination.length > 512
          || roots.has(node.id) || !acyclic.has(node.id) || outgoing.get(node.id).length
          || incoming.get(node.id).length !== 1 || (node.callKey && callKeys.get(node.callKey) !== 1)) continue;
      const link = incoming.get(node.id)[0], owner = byId.get(node.ownerId);
      if (!owner || owner.kind !== "segment" || link.from !== owner.id || link.kind !== "outbound") continue;
      if (["service", "serviceInstanceId", "segmentId", "segmentKey", "destinationName"].some(key =>
        node[key] != null && typeof node[key] !== "string")) continue;
      if (["service", "serviceInstanceId", "segmentId", "segmentKey"].some(key =>
        node[key] != null && owner[key] != null && node[key] !== owner[key])) continue;
      const key = JSON.stringify([owner.id, node.destination, node.destinationName ?? null,
        node.service ?? null, node.serviceInstanceId ?? null, node.segmentId ?? null, node.segmentKey ?? null,
        node.downstreamState ?? null, node.truncated === true]);
      if (!buckets.has(key)) buckets.set(key, []);
      buckets.get(key).push(node);
    }
    const groups = [...buckets.values()].filter(nodes => nodes.length > 1).map(nodes => {
      nodes.sort((a, b) => (numeric(a.eventSeq) ?? Infinity) - (numeric(b.eventSeq) ?? Infinity) || compare(a.id, b.id));
      return {id: nodes[0].id, ownerId: nodes[0].ownerId, nodes};
    }).sort((a, b) => compare(a.id, b.id));
    if (!groups.length) return unchanged();
    const hidden = new Set(groups.flatMap(group => group.nodes.slice(1).map(node => node.id)));
    const groupedIds = new Set(groups.map(group => group.id));
    return {groups, model: {...model,
      nodes: model.nodes.filter(node => !hidden.has(node.id)).map(node => groupedIds.has(node.id)
        ? {...node, eventSeq: null, thread: null, callKey: null} : node),
      edges: model.edges.filter(edge => !hidden.has(edge.from) && !hidden.has(edge.to)),
      roots: (model.roots || []).filter(id => !hidden.has(id))}};
  }

  // Keep every recorded SQL execution available to the expanded card without
  // letting query volume dominate the request diagram. This is presentation
  // metadata only: raw nodes, event identities and downloaded evidence survive.
  function groupDatabaseCalls(model) {
    const unchanged = () => ({model, groups: []});
    if (!model || !Array.isArray(model.nodes) || !Array.isArray(model.edges)
        || model.nodes.length > LIMITS.inputNodes || model.edges.length > LIMITS.inputEdges) return unchanged();
    const byId = new Map(), incoming = new Map(), outgoing = new Map(), edgeIds = new Set(), callKeys = new Map();
    for (const node of model.nodes) {
      if (!node || !idOf(node.id) || node.id.length > 4096 || byId.has(node.id)
          || !["segment", "http-call", "dependency-call"].includes(node.kind)) return unchanged();
      byId.set(node.id, node); incoming.set(node.id, []); outgoing.set(node.id, []);
      if (node.callKey) callKeys.set(node.callKey, (callKeys.get(node.callKey) || 0) + 1);
    }
    for (const edge of model.edges) {
      if (!edge || !idOf(edge.id) || edgeIds.has(edge.id) || !byId.has(edge.from) || !byId.has(edge.to)
          || !["outbound", "inbound", "observed-call"].includes(edge.kind)) return unchanged();
      edgeIds.add(edge.id); incoming.get(edge.to).push(edge); outgoing.get(edge.from).push(edge);
    }
    if (model.roots != null && (!Array.isArray(model.roots) || model.roots.length > LIMITS.inputNodes
        || model.roots.some(id => !byId.has(id)))) return unchanged();
    const roots = new Set(model.roots || []), buckets = new Map();
    const degrees = new Map([...incoming].map(([id, edges]) => [id, edges.length]));
    const ready = [...byId.keys()].filter(id => !degrees.get(id)), acyclic = new Set();
    for (let index = 0; index < ready.length; index++) {
      const id = ready[index]; acyclic.add(id);
      for (const edge of outgoing.get(id)) {
        degrees.set(edge.to, degrees.get(edge.to) - 1);
        if (degrees.get(edge.to) === 0) ready.push(edge.to);
      }
    }
    for (const node of model.nodes) {
      if (node.kind !== "dependency-call" || (!jdbc(node) && !clickhouseClient(node)) || roots.has(node.id) || !acyclic.has(node.id)
          || outgoing.get(node.id).length || incoming.get(node.id).length !== 1
          || (node.callKey != null && (typeof node.callKey !== "string" || !node.callKey.length || callKeys.get(node.callKey) !== 1))
          || (node.downstreamState != null && node.downstreamState !== "not-observed")) continue;
      const link = incoming.get(node.id)[0], owner = byId.get(node.ownerId);
      if (!owner || owner.kind !== "segment" || !acyclic.has(owner.id)
          || link.from !== owner.id || link.kind !== "outbound") continue;
      if (["service", "serviceInstanceId", "segmentId", "segmentKey", "destination", "destinationName"].some(key =>
        node[key] != null && (typeof node[key] !== "string" || node[key].length > 4096))) continue;
      if (["service", "serviceInstanceId", "segmentId", "segmentKey"].some(key =>
        owner[key] != null && (typeof owner[key] !== "string" || node[key] != null && node[key] !== owner[key]))) continue;
      const key = JSON.stringify([owner.id, node.system, node.destination ?? null, node.destinationName ?? null,
        node.service ?? null, node.serviceInstanceId ?? null, node.segmentId ?? null, node.segmentKey ?? null]);
      if (!buckets.has(key)) buckets.set(key, []);
      buckets.get(key).push(node);
    }
    const groups = [...buckets.values()].map(nodes => {
      nodes.sort((a, b) => (numeric(a.eventSeq) ?? Infinity) - (numeric(b.eventSeq) ?? Infinity) || compare(a.id, b.id));
      return {id: nodes[0].id, ownerId: nodes[0].ownerId, nodes};
    }).sort((a, b) => compare(a.id, b.id));
    if (!groups.length) return unchanged();
    const hidden = new Set(), summaries = new Map();
    for (const group of groups) {
      group.nodes.slice(1).forEach(node => hidden.add(node.id));
      const representative = group.nodes[0], summary = {};
      for (const key of ["id", "ownerId", "service", "serviceInstanceId", "segmentId", "segmentKey",
        "system", "destination", "destinationName"]) {
        if (Object.prototype.hasOwnProperty.call(representative, key)) summary[key] = representative[key];
      }
      const evidence = group.nodes.map(node => jdbc(node)).filter(Boolean);
      const executions = group.nodes.filter(node => dependencies.isSqlExecution(node));
      const clients = group.nodes.filter(clickhouseClient);
      const clientErrorCount = clients.filter(node => node.status === "ERROR").length;
      const clientSubmittedCount = clients.filter(node => node.status === "SUBMITTED").length;
      const sqlErrorCount = executions.filter(node => node.status === "ERROR").length;
      const sqlPendingCount = executions.filter(node => node.status === "SUBMITTED").length;
      const sqlLimitReached = evidence.some(item => dependencies.isSqlLimit(item));
      summaries.set(group.id, {...summary, kind: "dependency-call", boundaryKind: "database", direction: "outbound",
        presentationKind: "jdbc-group", evidenceKind: "jdbc-group", downstreamState: "not-observed",
        operation: null, eventSeq: null, thread: null, callKey: null, durationMs: null, errorType: null,
        status: sqlErrorCount || clientErrorCount ? "ERROR" : sqlLimitReached ? "TRUNCATED" : sqlPendingCount || clientSubmittedCount ? "SUBMITTED" : "OBSERVED",
        sqlExecutionCount: executions.length, sqlErrorCount, sqlPendingCount, sqlLimitReached,
        clientObservationCount: clients.length, clientErrorCount, clientSubmittedCount,
        sqlTextTruncated: evidence.some(item => item.sqlTruncated === true)});
    }
    return {groups, model: {...model,
      nodes: model.nodes.filter(node => !hidden.has(node.id)).map(node => summaries.get(node.id) || node),
      edges: model.edges.filter(edge => !hidden.has(edge.from) && !hidden.has(edge.to)),
      roots: (model.roots || []).filter(id => !hidden.has(id))}};
  }

  function layout(model, options) {
    options = options || {};
    model = model && typeof model === "object" ? model : {};
    const httpContexts = options.httpContexts || (options.boundaryActions ? httpCallingContexts(model) : null);
    const callOrders = options.callOrders || httpCallOrder(model, httpContexts || undefined);
    const inputNodes = Array.isArray(model.nodes) ? model.nodes : [];
    const inputEdges = Array.isArray(model.edges) ? model.edges : [];
    const byId = new Map();
    let invalidNodes = 0;
    inputNodes.slice(0, LIMITS.inputNodes).forEach(node => {
      if (!node || !idOf(node.id) || !["segment", "http-call", "dependency-call"].includes(node.kind) || byId.has(node.id)) invalidNodes++;
      else byId.set(node.id, node);
    });
    const outgoing = new Map(), incoming = new Map();
    byId.forEach((_, id) => { outgoing.set(id, []); incoming.set(id, []); });
    const validEdges = [];
    let invalidEdges = 0;
    inputEdges.slice(0, LIMITS.inputEdges).forEach((edge, index) => {
      if (!edge || !byId.has(edge.from) || !byId.has(edge.to) || !["outbound", "inbound", "observed-call"].includes(edge.kind)) {
        invalidEdges++;
        return;
      }
      const normalized = { ...edge, id: idOf(edge.id) || `edge-${index}` };
      validEdges.push(normalized);
      outgoing.get(edge.from).push(edge.to);
      incoming.get(edge.to).push(edge.from);
    });
    // Use all validated evidence, not only the bounded visible subset: an omitted
    // receiver must never turn a known captured hop into an external destination.
    const observedCallSources = new Set(validEdges.filter(edge => edge.kind === "observed-call").map(edge => edge.from));
    const externalServices = new Set([...byId.values()].filter(node => node.kind === "http-call"
      && node.downstreamState !== "observed" && !observedCallSources.has(node.id)).map(node => node.id));
    outgoing.forEach(values => values.sort(compare));
    incoming.forEach(values => values.sort(compare));
    const allIds = [...byId.keys()].sort(compare);
    const roots = [...new Set((Array.isArray(model.roots) ? model.roots : []).filter(id => byId.has(id)))].sort(compare);
    const seeds = [...new Set([...roots, ...allIds.filter(id => !incoming.get(id).length), ...allIds])];
    // Breadth-first selection retains nearby causal context while bounding DOM work.
    const kept = new Set(), queued = new Set();
    for (const seed of seeds) {
      if (kept.size >= LIMITS.nodes) break;
      if (kept.has(seed)) continue;
      const queue = [seed];
      queued.add(seed);
      for (let index = 0; index < queue.length && kept.size < LIMITS.nodes; index++) {
        const id = queue[index];
        kept.add(id);
        for (const next of outgoing.get(id)) if (!queued.has(next)) { queued.add(next); queue.push(next); }
      }
    }
    const chosen = [...kept].sort(compare);
    const chosenEdges = validEdges.filter(edge => kept.has(edge.from) && kept.has(edge.to));
    // Strongly connected components keep malformed/cyclic evidence visible without
    // treating a cycle as a return span or allowing infinite rank expansion.
    let sequence = 0;
    const indexOf = new Map(), low = new Map(), stack = [], onStack = new Set(), components = [];
    function visit(id) {
      indexOf.set(id, sequence); low.set(id, sequence++); stack.push(id); onStack.add(id);
      for (const target of outgoing.get(id)) {
        if (!kept.has(target)) continue;
        if (!indexOf.has(target)) { visit(target); low.set(id, Math.min(low.get(id), low.get(target))); }
        else if (onStack.has(target)) low.set(id, Math.min(low.get(id), indexOf.get(target)));
      }
      if (low.get(id) === indexOf.get(id)) {
        const component = [];
        let member;
        do { member = stack.pop(); onStack.delete(member); component.push(member); } while (member !== id);
        components.push(component.sort(compare));
      }
    }
    chosen.forEach(id => { if (!indexOf.has(id)) visit(id); });
    components.sort((a, b) => compare(a[0], b[0]));
    const componentOf = new Map();
    components.forEach((members, index) => members.forEach(id => componentOf.set(id, index)));
    const nextComponents = components.map(() => new Set());
    const rankSteps = components.map(() => new Map());
    const localOwners = new Set(chosenEdges.filter(edge => edge.kind === "outbound"
      && byId.get(edge.from).kind === "segment" && !externalServices.has(edge.to)
      && ownsObservation(byId.get(edge.from), byId.get(edge.to))).map(edge => edge.from));
    const degrees = components.map(() => 0), ranks = components.map(() => 0);
    chosenEdges.forEach(edge => {
      const from = componentOf.get(edge.from), to = componentOf.get(edge.to);
      if (from !== to && !nextComponents[from].has(to)) { nextComponents[from].add(to); degrees[to]++; }
      const owner = byId.get(edge.from);
      const hasLocalObservations = edge.kind === "observed-call" && owner.kind === "segment"
        && byId.get(edge.to).kind === "segment" && localOwners.has(owner.id);
      rankSteps[from].set(to, Math.max(rankSteps[from].get(to) || 1, hasLocalObservations ? 2 : 1));
    });
    const ready = components.map((_, index) => index).filter(index => !degrees[index]);
    while (ready.length) {
      ready.sort((a, b) => compare(components[a][0], components[b][0]));
      const component = ready.shift();
      [...nextComponents[component]].forEach(next => {
        ranks[next] = Math.max(ranks[next], ranks[component] + rankSteps[component].get(next));
        if (--degrees[next] === 0) ready.push(next);
      });
    }
    // Safely owned external leaves are satellites of their caller, not another
    // step along the captured service chain. Reserve its adjacent column, then
    // place them below the local observation frame. Pushing them beyond local
    // SQL used to create long, misleading detours over the entire diagram.
    const satelliteOwners = new Map();
    for (const id of chosen.filter(id => externalServices.has(id))) {
      const call = byId.get(id), owner = byId.get(call.ownerId);
      if (!owner || owner.kind !== "segment" || !kept.has(owner.id) || !ownsObservation(owner, call)
          || incoming.get(id).length !== 1 || outgoing.get(id).length
          || components[componentOf.get(owner.id)].length !== 1 || outgoing.get(owner.id).includes(owner.id)
          || !validEdges.some(edge => edge.kind === "outbound" && edge.from === owner.id && edge.to === id)) continue;
      const rank = ranks[componentOf.get(owner.id)] + 1;
      if (rank < LIMITS.columns) {
        ranks[componentOf.get(id)] = rank;
        satelliteOwners.set(id, owner.id);
      }
    }
    const columns = [];
    chosen.forEach(id => {
      const rank = ranks[componentOf.get(id)];
      if (rank < LIMITS.columns) (columns[rank] ||= []).push(id);
    });
    const slots = new Map();
    columns.forEach(ids => {
      const parentSlot = id => {
        const positions = incoming.get(id).filter(parent => slots.has(parent)).map(parent => slots.get(parent));
        return positions.length ? positions.reduce((a, b) => a + b, 0) / positions.length : Number.MAX_SAFE_INTEGER;
      };
      ids.sort((a, b) => parentSlot(a) - parentSlot(b) || compare(a, b));
      ids.forEach((id, slot) => slots.set(id, slot));
    });
    const heightOf = id => BOX.height + (id === options.expandedRouteId ? dbGroup(byId.get(id)) ? 400 : 220 : 0)
      + (options.boundaryActions ? byId.get(id).kind === "dependency-call"
        && byId.get(id).boundaryKind !== "database-route" && !dbGroup(byId.get(id)) ? boundaryActionHeight
        : httpActionRows(byId.get(id), httpContexts) * boundaryActionHeight : 0)
      + (options.requestDataActions && byId.get(id).kind === "segment"
        ? boundaryActionHeight + (id === options.expandedRequestId ? requestPanelHeight : 0) : 0);
    const widthOf = id => id === options.expandedRouteId && dbGroup(byId.get(id)) ? 460
      : options.requestDataActions && id === options.expandedRequestId && byId.get(id).kind === "segment" ? 400 : BOX.width;
    const columnLeft = [];
    let columnX = BOX.padding;
    columns.forEach((ids, rank) => {
      columnLeft[rank] = columnX;
      columnX += Math.max(BOX.width, ...ids.map(widthOf)) + BOX.columnGap;
    });
    const offsets = new Map();
    columns.forEach(ids => {
      let top = BOX.padding;
      ids.forEach(id => {
        offsets.set(id, satelliteOwners.has(id) ? BOX.padding : top);
        if (!satelliteOwners.has(id)) top += heightOf(id) + BOX.rowGap;
      });
    });
    const nodes = columns.flatMap((ids, rank) => ids.map((id, slot) => ({
      id, node: byId.get(id), rank, slot,
      x: columnLeft[rank],
      y: offsets.get(id),
      width: widthOf(id), height: heightOf(id),
      root: roots.includes(id),
      unlinked: !incoming.get(id).length && !roots.includes(id),
      cyclic: components[componentOf.get(id)].length > 1 || outgoing.get(id).includes(id),
      downstreamObserved: validEdges.some(edge => edge.from === id && edge.kind === "observed-call"),
      externalService: externalServices.has(id),
    })));
    const positioned = new Map(nodes.map(node => [node.id, node]));
    // Centre callers on their visible downstream branches, without mixing the
    // chronological metadata into layout or allowing cards in a column to overlap.
    for (let rank = columns.length - 2; rank >= 0; rank--) {
      let floor = BOX.padding;
      for (const id of columns[rank] || []) {
        if (satelliteOwners.has(id)) continue;
        const item = positioned.get(id);
        const children = [...new Set(outgoing.get(id))].map(next => positioned.get(next))
          .filter(child => child && child.rank > item.rank && !satelliteOwners.has(child.id));
        const centre = children.length
          ? children.reduce((sum, child) => sum + child.y, 0) / children.length : item.y;
        item.y = Math.max(floor, centre);
        floor = item.y + item.height + BOX.rowGap;
      }
    }
    packInvocationLanes(nodes, chosenEdges, satelliteOwners);
    const mainNodes = nodes.filter(item => !satelliteOwners.has(item.id));
    const mainFrames = serviceGroups(mainNodes, chosenEdges);
    const placedSatellites = [];
    for (const item of nodes.filter(item => satelliteOwners.has(item.id)).sort((a, b) =>
      compare(satelliteOwners.get(a.id), satelliteOwners.get(b.id)) || compare(a.id, b.id))) {
      const owner = positioned.get(satelliteOwners.get(item.id));
      const locals = mainNodes.filter(other => ownsObservation(owner.node, other.node)
        && chosenEdges.some(edge => edge.kind === "outbound" && edge.from === owner.id && edge.to === other.id));
      item.y = locals.length ? Math.max(owner.y + owner.height, ...locals.map(other => other.y + other.height)) + 72 : owner.y;
      // Expansion, parallel invocations and unrelated evidence must never place
      // a satellite inside any captured-service frame or on another card.
      const obstacles = [...mainNodes, ...mainFrames, ...placedSatellites];
      for (let attempt = 0; attempt <= obstacles.length; attempt++) {
        const collisions = obstacles.filter(other => intersects(item, {x: other.x - 20, y: other.y - 20,
          width: other.width + 40, height: other.height + 40}));
        if (!collisions.length) break;
        item.y = Math.max(...collisions.map(other => other.y + other.height)) + 40;
      }
      item.satelliteOwnerId = owner.id;
      placedSatellites.push(item);
    }
    const edgeOccurrences = new Map();
    const drawable = chosenEdges.filter(edge => positioned.has(edge.from) && positioned.has(edge.to))
      .sort((a, b) => compare(a.from, b.from) || compare(a.to, b.to) || compare(a.kind, b.kind) || compare(a.id, b.id));
    const edges = drawable.slice(0, LIMITS.edges).map((edge, edgeIndex) => {
      const from = positioned.get(edge.from), to = positioned.get(edge.to);
      const key = JSON.stringify([edge.from, edge.to]);
      const parallel = edgeOccurrences.get(key) || 0;
      edgeOccurrences.set(key, parallel + 1);
      const offset = Math.min(parallel, 4) * 7;
      const startX = from.x + from.width, startY = from.y + from.height / 2 + offset;
      const targetY = to.y + BOX.height / 2 + offset;
      const cyclic = from.rank === to.rank;
      let path;
      if (from.id === to.id) {
        path = `M ${startX} ${startY - 22} C ${startX + 46} ${startY - 22}, ${startX + 46} ${startY + 22}, ${startX} ${startY + 22}`;
      } else if (cyclic) {
        const turn = startX + 24 + offset;
        path = `M ${startX} ${startY} C ${turn} ${startY}, ${turn} ${targetY}, ${to.x + to.width} ${targetY}`;
      } else if (to.satelliteOwnerId === from.id && to.y >= from.y + from.height + BOX.rowGap
          && !nodes.some(other => other.id !== from.id && other.id !== to.id
            && intersects({x: from.x + from.width / 2 - 2, y: from.y + from.height,
              width: 4, height: targetY - from.y - from.height}, other))) {
        // A local side branch exits below its caller, outside the service frame;
        // it does not look like it originates in SQL or the receiving service.
        path = `M ${from.x + from.width / 2 + offset} ${from.y + from.height} V ${targetY} H ${to.x}`;
      } else if (to.rank > from.rank + 1) {
        const railY = 12 + (edgeIndex % 4) * 6;
        path = foldedHopPath(from, to, nodes, offset)
          || `M ${startX} ${startY} H ${startX + 24 + offset} V ${railY} H ${to.x - 24 - offset} V ${targetY} H ${to.x}`;
      } else {
        const columnRight = Math.max(...nodes.filter(item => item.rank === from.rank).map(item => item.x + item.width));
        const bend = (columnRight + to.x) / 2 + offset;
        path = `M ${startX} ${startY} H ${bend} V ${targetY} H ${to.x}`;
      }
      const sequenceLabel = httpOrderEdge(edge, callOrders);
      let labelPosition = !cyclic && !parallel
        ? to.rank === from.rank + 1 ? {labelX: (startX + to.x) / 2, labelY: targetY - 14}
          : to.externalService ? {labelX: to.x - 50, labelY: targetY - 14} : {} : {};
      if (sequenceLabel && !cyclic && !parallel && labelPosition.labelX == null) labelPosition = orderLabelPosition(path, nodes, sequenceLabel.label);
      return { ...edge, path, cyclic, ...edgeAppearance(edge, from.node, to.node),
        ...(sequenceLabel ? {label: sequenceLabel.label, callId: sequenceLabel.callId, callOrder: sequenceLabel.order} : {}), ...labelPosition };
    });
    return {
      nodes, edges, groups: serviceGroups(nodes, edges),
      width: nodes.length ? Math.max(...nodes.map(node => node.x + node.width)) + BOX.padding + 20 : 0,
      height: nodes.length ? Math.max(...nodes.map(node => node.y + node.height)) + 32 : 0,
      totalNodes: inputNodes.length, totalEdges: inputEdges.length,
      omittedNodes: Math.max(0, inputNodes.length - invalidNodes - nodes.length),
      omittedEdges: Math.max(0, inputEdges.length - invalidEdges - edges.length),
      invalidNodes, invalidEdges,
      cycleCount: components.filter(members => members.length > 1 || outgoing.get(members[0]).includes(members[0])).length,
      limits: LIMITS,
    };
  }

  let renderSequence = 0;
  function renderView(container, model, options, showHttpHops, onToggle, inspection, onInspect, camera, requestState, onToggleRequest) {
    if (!container || !container.ownerDocument) throw new TypeError("A DOM container is required");
    model = model && typeof model === "object" ? model : {};
    options = options || {};
    const document = container.ownerDocument;
    const previous = container.querySelector(".tl-graph-node[aria-pressed=true]");
    const previousId = inspection.selectedId || previous && previous.dataset.nodeId;
    const previousViewport = container.querySelector(".tl-graph-viewport");
    const previousScroll = previousViewport ? {left: previousViewport.scrollLeft, top: previousViewport.scrollTop} : null;
    const restoreFocus = container.contains(document.activeElement) && document.activeElement.classList.contains("tl-graph-node");
    const previousRequestPanel = container.querySelector(".tl-graph-request-evidence");
    const previousRequestId = container.contains(document.activeElement)
      && document.activeElement.classList.contains("tl-graph-request-toggle") ? document.activeElement.dataset.requestNodeId
      : previousRequestPanel?.contains(document.activeElement) ? previousRequestPanel.dataset.requestNodeId : null;
    const compact = presentation(model);
    const foldedHops = showHttpHops ? [] : compact.foldedHops;
    const routes = groupDatabaseRoutes(showHttpHops ? model : compact.model);
    const grouped = groupDatabaseCalls(routes.model);
    const routeGroups = new Map(routes.groups.map(group => [group.id, group]));
    const sqlGroups = new Map(grouped.groups.map(group => [group.id, group]));
    const allGroups = [...routes.groups, ...grouped.groups];
    const isRoute = node => node.kind === "dependency-call" && node.boundaryKind === "database-route";
    const observations = node => routeGroups.get(node.id)?.nodes || [node];
    const boundaryActions = typeof options.onOpenBoundary === "function";
    const requestDataActions = typeof options.loadRequestData === "function" && typeof requestData?.render === "function";
    const httpContexts = httpCallingContexts(model);
    const httpLabels = httpDestinationLabels(model, httpContexts);
    const callOrders = httpCallOrder(model, httpContexts);
    // Evidence is outside the canvas: selecting or reading never changes geometry.
    const result = layout(grouped.model, {httpContexts, callOrders});
    const requestButtons = new Map();
    const listeners = [], detailListeners = [];
    const listen = (target, name, handler, detail) => {
      target.addEventListener(name, handler);
      (detail ? detailListeners : listeners).push(() => target.removeEventListener(name, handler));
    };
    const uid = `tl-graph-${++renderSequence}`;
    const element = (tag, className, text) => {
      const value = document.createElement(tag);
      if (className) value.className = className;
      if (text !== undefined) value.textContent = text;
      return value;
    };
    const wrapper = element("section", "tl-graph");
    wrapper.setAttribute("aria-label", "Causal request flow");
    const frame = element("div", "tl-graph-frame");
    const toolbar = element("div", "tl-graph-toolbar");
    const heading = element("div", "tl-graph-heading");
    const countLabel = (count, singular, plural) => `${count} ${count === 1 ? singular : plural}`;
    const sqlCount = node => dbGroup(node) ? [
      node.sqlExecutionCount || !node.clientObservationCount ? countLabel(node.sqlExecutionCount, "SQL execution", "SQL executions") : "",
      node.clientObservationCount ? countLabel(node.clientObservationCount, "client observation", "client observations") : ""
    ].filter(Boolean).join(" · ") : "";
    const sqlStatus = node => [node.sqlErrorCount ? countLabel(node.sqlErrorCount, "failed execution", "failed executions") : "",
      node.sqlPendingCount ? `${node.sqlPendingCount} completion(s) not observed` : "",
      !node.sqlErrorCount && !node.sqlPendingCount && !node.clientObservationCount ? node.sqlExecutionCount ? node.system === "clickhouse" ? "All responses completed" : "All returned" : "No executions retained" : "",
      node.clientObservationCount ? "Local client observations" : "",
      node.clientSubmittedCount ? `${node.clientSubmittedCount} submitted · completion not observed` : "",
      node.clientErrorCount ? countLabel(node.clientErrorCount, "client error", "client errors") : "",
      node.sqlLimitReached ? "Capture limit reached" : "",
      node.sqlTextTruncated ? "SQL text truncated" : ""].filter(Boolean).join(" · ");
    const visibleCalls = result.nodes.flatMap(item => sqlGroups.get(item.id)?.nodes || (jdbc(item.node) ? [item.node] : []));
    const visibleSql = visibleCalls.filter(node => jdbc(node));
    const segmentCount = result.nodes.filter(item => item.node.kind === "segment").length;
    const boundaryCount = result.nodes.filter(item => item.node.kind !== "segment").length;
    const counts = element("div", "tl-graph-counts", [
      countLabel(segmentCount, "service invocation", "service invocations"),
      countLabel(result.nodes.filter(item => item.node.kind === "http-call").length, "HTTP boundary", "HTTP boundaries"),
      countLabel(result.nodes.filter(item => item.node.kind === "dependency-call").length, "other boundary", "other boundaries"),
      result.nodes.some(item => isRoute(item.node)) ? countLabel(result.nodes.filter(item => isRoute(item.node)).reduce((count, item) => count + observations(item.node).length, 0), "recorded DB routing decision", "recorded DB routing decisions") : "",
      visibleSql.length ? countLabel(visibleSql.filter(node => dependencies.isSqlExecution(jdbc(node))).length, "visible SQL execution", "visible SQL executions") : "",
      visibleSql.some(node => dependencies.isSqlLimit(jdbc(node))) ? "SQL capture limit reached" : "",
      visibleCalls.some(clickhouseClient) ? countLabel(visibleCalls.filter(clickhouseClient).length, "ClickHouse client observation", "ClickHouse client observations") : "",
      foldedHops.length ? countLabel(foldedHops.length, "HTTP hop folded", "HTTP hops folded") : "",
    ].filter(Boolean).join(" · "));
    const shortCounts = element("span", "tl-graph-short-counts", [countLabel(segmentCount, "segment", "segments"),
      countLabel(boundaryCount, "boundary", "boundaries")].join(" · "));
    shortCounts.title = "Visible diagram cards. Database calls share a card within each service segment and recorded destination; SQL executions and client observations have separate counts. DB routing decisions stay separate. Open the diagram guide for complete counts.";
    heading.append(element("h3", "tl-graph-title", "Request flow"), shortCounts);
    toolbar.append(heading);
    frame.append(toolbar);
    wrapper.append(frame);
    if (compact.foldedHops.length) {
      const toggle = element("button", "tl-graph-hop-toggle", showHttpHops ? "Hide HTTP hops" : "Show HTTP hops");
      toggle.type = "button"; toggle.setAttribute("aria-pressed", String(showHttpHops));
      toggle.title = "Only unambiguous captured HTTP hops are folded. Show the original HTTP nodes for separate caller-side observations; the source graph is unchanged.";
      listen(toggle, "click", onToggle); toolbar.append(toggle);
    }
    const legend = element("div", "tl-graph-legend");
    legend.append(element("span", "tl-graph-legend-segment", "Service segment"), element("span", "tl-graph-legend-call", "Outgoing HTTP / external service"), element("span", "tl-graph-legend-database", "Database call / routing decision"), element("span", "tl-graph-legend-cache", "Cache call / access"), element("span", "tl-graph-legend-queue", "Queue / messaging"), element("span", "tl-graph-legend-dependency", "Other external boundary"), element("span", "tl-graph-legend-edge", "Arrows = recorded boundary direction or causal links"));
    const notices = element("div", "tl-graph-notices");
    const addNotice = (text, error) => notices.append(element("p", error ? "tl-graph-notice tl-graph-error" : "tl-graph-notice", text));
    const coverage = element("details", "tl-graph-coverage");
    coverage.open = false;
    const limited = model.partial || result.omittedNodes || result.omittedEdges || result.invalidNodes || result.invalidEdges;
    const coverageSummary = element("summary", limited ? "tl-graph-partial" : "", limited ? "Partial evidence" : "Diagram guide");
    coverageSummary.title = "Coverage, legend and controls";
    const coverageBody = element("div", "tl-graph-coverage-body");
    coverage.append(coverageSummary, coverageBody);
    coverageBody.append(counts,
      element("p", "tl-graph-subtitle", "Causal flow, not a shared time axis. Each service box is one captured invocation."), legend);
    if (model.partial) coverageBody.append(element("p", "tl-graph-notice", "Partial capture — some service segments or downstream evidence may be unavailable."));
    if (scalar(model.completeness)) coverageBody.append(element("p", "tl-graph-notice", `Capture completeness: ${scalar(model.completeness)}`));
    if (scalar(model.note)) coverageBody.append(element("p", "tl-graph-notice", scalar(model.note)));
    coverageBody.append(element("p", "tl-graph-notice", "Arrows retain recorded boundary direction or explicit causal links. Folded HTTP hops do not combine caller and server durations. A box with no incoming link is not proof of a request entry."));
    coverageBody.append(element("p", "tl-graph-notice", "HTTP arrow numbers show retained causal/local order stages, not a synchronized timeline. A chain increments 1, 2, 3; decimal branches (1.1, 1.2) require overlapping completed HTTP intervals within one caller invocation. ? means order is unknown: sibling arrows alone do not prove parallel execution. Grouped DB observations have no single execution position. Each HTTP hop is numbered once whether shown or folded."));
    coverageBody.append(element("p", "tl-graph-notice", "Service frames contain one captured invocation and its explicitly linked local observations, not deployed architecture or database ownership. Frames are omitted when grouping would include unrelated evidence."));
    coverageBody.append(element("p", "tl-graph-notice", "External service cards sit outside the caller frame when no downstream execution was captured. They retain caller-side HTTP evidence and use a configured destination name or recorded host; they do not imply an installed agent, captured server code or a separate service segment."));
    if (routes.groups.length) coverageBody.append(element("p", "tl-graph-notice", "Repeated DB routes are grouped only within the same service segment. Counts are recorded routing decisions, not queries or connections. Select a card for event/thread details in the inspector; the source capture and timeline are unchanged."));
    if (grouped.groups.length) coverageBody.append(element("p", "tl-graph-notice", "Database calls are grouped by service segment and recorded destination; an unspecified destination does not establish a shared server. Select a DB-call card for individual observations, calling-code links and SQL in the inspector. Client observations are not additional SQL executions. Timings are per execution, not summed; raw events and exports are unchanged."));
    ["warnings", "errors"].forEach(field => {
      const messages = Array.isArray(model[field]) ? model[field] : [];
      const formatted = messages.slice(0, 128).map(value => scalar(value) || (value && [scalar(value.service), scalar(value.message) || scalar(value.error)].filter(Boolean).join(": "))).filter(Boolean);
      formatted.slice(0, 12).forEach(message => {
        if (field === "warnings") coverageBody.append(element("p", "tl-graph-notice", message));
        else addNotice(message, true);
      });
      if (field === "warnings") {
        const critical = formatted.filter(message => /limit reached|truncat|omitt/i.test(message));
        if (critical.length) {
          coverageSummary.textContent = "Partial evidence";
          coverageSummary.className = "tl-graph-partial";
        }
        // Preserve important warnings even beyond the ordinary disclosure cap.
        const remaining = critical.filter(message => !formatted.slice(0, 12).includes(message));
        remaining.slice(0, 8).forEach(message => coverageBody.append(element("p", "tl-graph-notice", message)));
        if (remaining.length > 8) coverageBody.append(element("p", "tl-graph-notice", `${remaining.length - 8} additional limit/truncation warnings are present in the capture.`));
      }
      if (messages.length > 12) (field === "errors" ? notices : coverageBody).append(element("p", "tl-graph-notice", `${messages.length - 12} additional ${field} are present in the capture.`));
    });
    frame.append(coverage);
    const addCoverage = text => coverageBody.append(element("p", "tl-graph-notice", text));
    if (result.omittedNodes || result.omittedEdges) addCoverage(`Diagram limit: showing ${result.nodes.length} of ${result.totalNodes} nodes and ${result.edges.length} of ${result.totalEdges} links. At most ${LIMITS.nodes} nodes, ${LIMITS.columns} causal columns, and ${LIMITS.edges} links are drawn; the source capture is unchanged.`);
    if (result.invalidNodes || result.invalidEdges) addCoverage(`${result.invalidNodes} invalid/duplicate nodes and ${result.invalidEdges} invalid links were not drawn. No connections are inferred from metadata.`);
    if (result.cycleCount) addCoverage("Cyclic causal evidence is shown with dashed links. These are recorded links, not inferred return paths.");
    if (notices.childNodes.length) frame.append(notices);
    if (!result.nodes.length) {
      frame.append(element("p", "tl-graph-empty", "No observed request-flow nodes are available in this capture."));
      container.replaceChildren(wrapper);
      return () => { listeners.splice(0).forEach(remove => remove()); wrapper.remove(); };
    }
    const help = element("p", "tl-graph-help", "Select a card to inspect without moving the diagram. Arrow keys move selection; Escape closes details. Open code flow and View caller are separate actions. Fit shows the complete diagram.");
    help.id = `${uid}-help`;
    coverageBody.append(help);
    const diagram = element("div", "tl-graph-diagram");
    const controls = element("div", "tl-graph-zoom-controls");
    controls.setAttribute("role", "group");
    controls.setAttribute("aria-label", "Diagram zoom");
    const viewport = element("div", "tl-graph-viewport");
    viewport.setAttribute("aria-label", "Scrollable causal diagram");
    viewport.style.height = `${Math.min(result.height, 640)}px`;
    const surface = element("div", "tl-graph-surface");
    const canvas = element("div", "tl-graph-canvas");
    canvas.style.width = `${result.width}px`;
    canvas.style.height = `${result.height}px`;
    const zoomLabel = element("output", "tl-graph-zoom-value");
    zoomLabel.setAttribute("aria-live", "polite");
    zoomLabel.setAttribute("aria-label", "Zoom level");
    const control = (className, text, title, handler) => {
      const button = element("button", className, text);
      button.type = "button"; button.title = title; button.setAttribute("aria-label", title);
      listen(button, "click", handler); return button;
    };
    let updateMinimap = () => {};
    const applyZoom = (value, center = true) => {
      const previousZoom = camera.zoom;
      camera.zoom = Math.max(ZOOM.min, Math.min(ZOOM.max, value));
      const width = Math.ceil(result.width * camera.zoom), height = Math.ceil(result.height * camera.zoom);
      const left = center ? (viewport.scrollLeft + viewport.clientWidth / 2) * camera.zoom / previousZoom - viewport.clientWidth / 2 : 0;
      const top = center ? (viewport.scrollTop + viewport.clientHeight / 2) * camera.zoom / previousZoom - viewport.clientHeight / 2 : 0;
      surface.style.width = `${width}px`; surface.style.height = `${height}px`;
      canvas.style.transform = `scale(${camera.zoom})`;
      zoomLabel.textContent = `${Math.round(camera.zoom * 1000) / 10}%`;
      zoomOut.disabled = camera.zoom <= ZOOM.min; zoomIn.disabled = camera.zoom >= ZOOM.max;
      viewport.scrollLeft = Math.max(0, Math.min(left, Math.max(0, width - viewport.clientWidth)));
      viewport.scrollTop = Math.max(0, Math.min(top, Math.max(0, height - viewport.clientHeight)));
      updateMinimap();
    };
    const view = document.defaultView;
    let disposed = false, fitObserver = null, fitFrame = null, watchingResize = false;
    const stopFitWatch = () => {
      fitObserver?.disconnect(); fitObserver = null;
      if (fitFrame !== null) view?.cancelAnimationFrame?.(fitFrame);
      fitFrame = null;
      if (watchingResize) view.removeEventListener("resize", retryFit);
      watchingResize = false;
    };
    const tryFit = () => {
      if (disposed || !camera.fitPending) return true;
      // Request tabs and standalone embeds can render before they are mounted
      // or visible. Do not turn their zero-sized viewport into minimum zoom.
      if (viewport.isConnected === false || viewport.clientWidth <= 16 || viewport.clientHeight <= 16) return false;
      applyZoom(Math.min(1, (viewport.clientWidth - 16) / result.width,
        (viewport.clientHeight - 16) / result.height), false);
      camera.fitPending = false;
      stopFitWatch();
      return true;
    };
    function retryFit() {
      if (tryFit() || fitFrame !== null || !view?.requestAnimationFrame) return;
      fitFrame = view.requestAnimationFrame(() => {
        fitFrame = null;
        // ResizeObserver wakes hidden/mounted views without polling. Older
        // browsers retry on frames until measurable, then stop completely.
        if (!tryFit() && !fitObserver) retryFit();
      });
    }
    const requestFit = () => {
      camera.fitPending = true;
      if (tryFit()) return;
      if (!fitObserver && typeof view?.ResizeObserver === "function") {
        fitObserver = new view.ResizeObserver(retryFit);
        fitObserver.observe(viewport);
      }
      if (!watchingResize && view?.addEventListener) {
        view.addEventListener("resize", retryFit); watchingResize = true;
      }
      retryFit();
    };
    const manualZoom = value => {
      camera.fitPending = false; stopFitWatch(); applyZoom(value);
    };
    const fit = control("tl-graph-fit", "Fit", "Fit the diagram in view", requestFit);
    const zoomOut = control("tl-graph-zoom-out", "−", "Zoom out", () => manualZoom(camera.zoom / ZOOM.factor));
    const zoomIn = control("tl-graph-zoom-in", "+", "Zoom in", () => manualZoom(camera.zoom * ZOOM.factor));
    const resetZoom = control("tl-graph-zoom-reset", "100%", "Reset diagram zoom to 100%", () => manualZoom(1));
    controls.append(fit, zoomOut, zoomLabel, zoomIn, resetZoom);
    diagram.append(controls, viewport);
    result.groups.forEach(group => {
      const region = element("div", "tl-graph-service-group");
      const owner = result.nodes.find(item => item.id === group.ownerId)?.node;
      const repeated = owner && result.nodes.filter(item => item.node.kind === "segment" && item.node.service === owner.service).length > 1;
      const note = repeated && owner.method && owner.path ? rawApi(owner) : "Captured invocation · local observations";
      region.style.left = `${group.x}px`; region.style.top = `${group.y}px`;
      region.style.width = `${group.width}px`; region.style.height = `${group.height}px`;
      region.setAttribute("aria-hidden", "true");
      region.append(element("span", "tl-graph-service-group-name", group.name),
        element("span", "tl-graph-service-group-note", note));
      canvas.append(region);
    });
    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("class", "tl-graph-connectors");
    svg.setAttribute("width", String(result.width));
    svg.setAttribute("height", String(result.height));
    svg.setAttribute("aria-hidden", "true");
    const defs = document.createElementNS(svgNS, "defs");
    ["service", "http", "database", "cache", "queue", "external"].forEach(kind => {
      const marker = document.createElementNS(svgNS, "marker");
      marker.setAttribute("id", `${uid}-${kind}`);
      marker.setAttribute("viewBox", "0 0 10 10");
      marker.setAttribute("refX", "9"); marker.setAttribute("refY", "5");
      marker.setAttribute("markerWidth", "7"); marker.setAttribute("markerHeight", "7");
      marker.setAttribute("orient", "auto-start-reverse");
      const arrow = document.createElementNS(svgNS, "path");
      arrow.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
      arrow.setAttribute("class", `tl-graph-arrow tl-graph-link-${kind}`);
      marker.append(arrow); defs.append(marker);
    });
    svg.append(defs);
    result.edges.forEach(edge => {
      const path = document.createElementNS(svgNS, "path");
      path.setAttribute("d", edge.path);
      path.setAttribute("class", `tl-graph-edge tl-graph-${edge.kind} tl-graph-link-${edge.category}${edge.cyclic ? " tl-graph-cycle" : ""}`);
      path.setAttribute("marker-end", `url(#${uid}-${edge.category})`);
      svg.append(path);
    });
    result.edges.filter(edge => edge.labelX != null).forEach(edge => {
      const label = document.createElementNS(svgNS, "g");
      label.setAttribute("class", `tl-graph-edge-label tl-graph-link-${edge.category}`);
      label.setAttribute("transform", `translate(${edge.labelX} ${edge.labelY})`);
      if (edge.callOrder) {
        label.setAttribute("data-call-id", edge.callId);
        const title = document.createElementNS(svgNS, "title"); title.textContent = edge.callOrder.description; label.append(title);
      }
      const background = document.createElementNS(svgNS, "rect");
      const width = edge.label.length * 6.5 + 14;
      background.setAttribute("x", String(-width / 2)); background.setAttribute("y", "-10");
      background.setAttribute("width", String(width)); background.setAttribute("height", "20"); background.setAttribute("rx", "4");
      const text = document.createElementNS(svgNS, "text");
      text.setAttribute("text-anchor", "middle"); text.setAttribute("dy", ".35em"); text.textContent = edge.label;
      label.append(background, text); svg.append(label);
    });
    canvas.append(svg);
    function icon(node, externalService) {
      const value = document.createElementNS(svgNS, "svg");
      value.setAttribute("class", "tl-graph-node-icon"); value.setAttribute("viewBox", "0 0 24 24");
      value.setAttribute("aria-hidden", "true"); value.setAttribute("focusable", "false");
      value.setAttribute("fill", "none"); value.setAttribute("stroke", "currentColor");
      value.setAttribute("stroke-width", "1.7"); value.setAttribute("stroke-linecap", "round");
      value.setAttribute("stroke-linejoin", "round");
      const paths = {
        service: ["M4 4h16v6H4z M4 14h16v6H4z", "M7 7h1 M7 17h1 M12 7h5 M12 17h5"],
        http: ["M4 12h15 M14 6l6 6-6 6", "M4 5v14"],
        database: ["M4 6c0-4 16-4 16 0s-16 4-16 0v12c0 4 16 4 16 0V6", "M4 12c0 4 16 4 16 0"],
        cache: ["M5 5h14v14H5z M9 1v4 M15 1v4 M9 19v4 M15 19v4 M1 9h4 M1 15h4 M19 9h4 M19 15h4", "M10 8l-2 5h4l-2 4 6-6h-4l2-3"],
        queue: ["M4 5h16v4H4z M4 12h16 M4 16h16 M4 20h11"],
        external: ["M10 4H4v16h16v-6 M13 4h7v7 M10 14L20 4"],
        externalService: ["M4 4h9v6H4z M4 14h9v6H4z M7 7h2 M7 17h2", "M16 5h5v5 M15 11l6-6"],
      };
      paths[externalService ? "externalService" : category(node)].forEach(d => {
        const path = document.createElementNS(svgNS, "path"); path.setAttribute("d", d); value.append(path);
      });
      return value;
    }
    const workspace = element("div", "tl-graph-workspace");
    const details = element("aside", "tl-graph-details");
    details.id = `${uid}-inspector`;
    details.tabIndex = -1;
    details.setAttribute("aria-label", "Selected node inspector");
    const buttons = new Map();
    const priorFolded = foldedHops.find(hop => hop.call.id === previousId);
    const priorGroup = allGroups.find(group => group.nodes.some(node => node.id === previousId));
    const selected = result.nodes.find(item => item.id === (priorFolded ? priorFolded.target.id : priorGroup ? priorGroup.id : previousId)) || result.nodes.find(item => item.root) || result.nodes[0];
    let selectedId = selected.id;
    let detailId = null;
    let restoringFocus = false;
    const detailListen = (target, name, handler) => listen(target, name, handler, true);
    function focusSelection(branch) {
      const ids = new Set([selectedId]);
      if (branch) {
        const pending = [selectedId];
        for (let index = 0; index < pending.length; index++) {
          for (const link of result.edges.filter(link => link.from === pending[index])) {
            if (!ids.has(link.to)) { ids.add(link.to); pending.push(link.to); }
          }
        }
      }
      const items = result.nodes.filter(item => ids.has(item.id));
      const left = Math.min(...items.map(item => item.x)) - 24, top = Math.min(...items.map(item => item.y)) - 24;
      const right = Math.max(...items.map(item => item.x + item.width)) + 24, bottom = Math.max(...items.map(item => item.y + item.height)) + 24;
      if (viewport.clientWidth <= 16 || viewport.clientHeight <= 16) return;
      manualZoom(Math.min(1, viewport.clientWidth / (right - left), viewport.clientHeight / (bottom - top)));
      viewport.scrollLeft = Math.max(0, (left + right) / 2 * camera.zoom - viewport.clientWidth / 2);
      viewport.scrollTop = Math.max(0, (top + bottom) / 2 * camera.zoom - viewport.clientHeight / 2);
      updateMinimap();
    }
    controls.append(control("tl-graph-focus-selected", "Focus selected", "Focus selected node", () => focusSelection(false)),
      control("tl-graph-focus-branch", "Focus branch", "Focus selected branch", () => focusSelection(true)));
    const minimap = document.createElementNS(svgNS, "svg");
    minimap.setAttribute("class", "tl-graph-minimap"); minimap.setAttribute("aria-hidden", "true");
    minimap.setAttribute("viewBox", `0 0 ${result.width} ${result.height}`);
    const miniNodes = new Map();
    for (const item of result.nodes) {
      const mark = document.createElementNS(svgNS, "rect");
      mark.setAttribute("x", item.x); mark.setAttribute("y", item.y);
      mark.setAttribute("width", item.width); mark.setAttribute("height", item.height);
      miniNodes.set(item.id, mark); minimap.append(mark);
    }
    const miniViewport = document.createElementNS(svgNS, "rect");
    miniViewport.setAttribute("class", "tl-graph-minimap-view"); minimap.append(miniViewport); diagram.append(minimap);
    updateMinimap = () => {
      miniNodes.forEach((mark, id) => mark.setAttribute("class", id === selectedId ? "tl-graph-minimap-selected" : ""));
      miniViewport.setAttribute("x", viewport.scrollLeft / camera.zoom); miniViewport.setAttribute("y", viewport.scrollTop / camera.zoom);
      miniViewport.setAttribute("width", Math.min(result.width, viewport.clientWidth / camera.zoom));
      miniViewport.setAttribute("height", Math.min(result.height, viewport.clientHeight / camera.zoom));
    };
    listen(viewport, "scroll", updateMinimap);
    function closeDetails(focus = true) {
      inspection.open = false; detailId = null; onInspect(null);
      requestState = null;
      detailListeners.splice(0).forEach(remove => remove());
      details.replaceChildren(element("p", "tl-graph-inspector-empty", "Select a service or boundary to inspect its recorded evidence. The diagram stays in place."));
      if (focus) {
        restoringFocus = true; buttons.get(selectedId)?.focus({preventScroll: true}); restoringFocus = false;
      }
    }
    function showDetails(item) {
      if (detailId === item.id) return;
      if (requestState && requestState.nodeId !== item.id) requestState = null;
      inspection.open = true; inspection.selectedId = item.id; onInspect(item.id);
      detailId = item.id;
      detailListeners.splice(0).forEach(remove => remove());
      const node = item.node;
      const title = element("h4", "tl-graph-detail-title", node.kind === "segment" ? scalar(node.service) || "Service segment" : item.externalService ? "EXTERNAL SERVICE" : boundaryType(node));
      const api = element("p", "tl-graph-detail-api", rawApi(node));
      const list = element("dl", "tl-graph-detail-list");
      const field = (name, value) => { list.append(element("dt", "", name), element("dd", "", scalar(value) || "Unavailable")); };
      field(node.kind === "dependency-call" ? "Operation" : "Raw API", rawApi(node));
      if (dbGroup(node)) {
        field("Recorded calls", sqlCount(node));
        field("Execution results", sqlStatus(node));
        field("Timing", "Open individual recorded calls for their durations; no combined duration is inferred");
      } else field(node.kind === "segment" ? "Server-local duration" : "Caller-local duration", duration(node.durationMs));
      field("Service instance", node.serviceInstanceId);
      field(node.kind === "segment" ? "Server status" : node.kind === "http-call" ? "Caller status" : "Status", [scalar(node.status), node.httpStatus != null ? `HTTP ${scalar(node.httpStatus)}` : ""].filter(Boolean).join(" · "));
      field("Segment ID", node.segmentId);
      if (node.kind === "segment") {
        field("Entry channel", node.entryChannel);
        if (node.entryChannel === "SCHEDULER") {
          for (const [label, key] of [["Job type", "jobType"], ["Job ID", "jobId"], ["Job outcome", "jobOutcome"],
            ["Audit status", "jobAuditStatus"], ["Failure type", "jobFailureType"], ["Scheduler", "scheduler"]]) field(label, node[key]);
        }
        field("Events", numeric(node.eventCount) === null ? "Unavailable" : scalar(node.eventCount));
        field("Capture", node.truncated ? "Truncated — some events were not captured" : "No truncation reported");
      } else if (node.kind === "http-call") {
        if (callOrders.has(node.id)) field("HTTP call order", callOrders.get(node.id).description);
        const label = httpLabels.get(node.id);
        if (label) { field("Destination name", label.name); field("Name source", destinationNameEvidence(label)); }
        else if (node.destinationName) field("Destination name", node.destinationName);
        field("Peer", node.peer);
        field("Propagation", node.propagationStatus);
        field("Downstream evidence", item.downstreamObserved ? "Observed service segment" : "Unavailable — no downstream segment is linked");
        if (node.downstreamState) field("Reported downstream state", node.downstreamState);
        if (node.callKey) field("Call key", node.callKey);
        field("Evidence", boundaryEvidence(node));
      } else {
        field("Direction", node.direction);
        field("Destination", node.destinationName || node.destination || node.system);
        if (node.destinationName && node.destination) field("Recorded destination", node.destination);
        field("Observed in", node.service);
        field("Evidence", boundaryEvidence(node));
        field("Remote execution", "Not observed — no remote internals or producer/consumer link is invented");
        if (node.eventSeq != null) field("Event sequence", node.eventSeq);
        if (node.callKey) field("Call key", node.callKey);
      }
      if (node.parentSpanId) field("Parent span ID", node.parentSpanId);
      if (item.root) field("Connectivity", "No incoming recorded link — this does not prove request entry");
      if (item.unlinked) field("Connectivity", "Unlinked evidence — no incoming causal edge is present");
      if (item.cyclic) field("Connectivity", "Part of cyclic recorded evidence");
      const detailSummary = element("div", "tl-graph-details-summary");
      const close = element("button", "tl-graph-inspector-close", "Close"); close.type = "button";
      close.setAttribute("aria-label", "Close node inspector");
      detailListen(close, "click", () => closeDetails());
      detailSummary.append(element("span", "", "Selected evidence"), close);
      const body = element("div", "tl-graph-detail-body");
      const metrics = dbGroup(node) ? `${sqlCount(node)} · ${sqlStatus(node)}`
        : [node.httpStatus != null ? `HTTP ${scalar(node.httpStatus)}` : scalar(node.status),
          numeric(node.durationMs) === null ? "" : `${duration(node.durationMs)} ${node.kind === "segment" ? "server-local" : "caller-local"}`].filter(Boolean).join(" · ");
      body.append(title, api, element("p", "tl-graph-detail-metrics", metrics));
      const actions = element("div", "tl-graph-inspector-actions");
      body.append(actions);
      if (dbGroup(node)) body.append(sqlGroupEvidence(item));
      else if (isRoute(node)) body.append(routeEvidence(item));
      else if (jdbc(node)) body.append(dependencies.renderLazyJdbc(document, node,
        options.loadBoundaryData ? () => options.loadBoundaryData(node) : null));
      if (boundaryActions && node.kind === "dependency-call" && !isRoute(node) && !dbGroup(node))
        actions.append(callingCodeAction(node, "tl-graph-boundary-footer", detailListen));
      if (boundaryActions && httpActionRows(node, httpContexts)) actions.append(httpActions(node));
      if (requestDataActions && node.kind === "segment") {
        const expanded = requestState?.nodeId === item.id;
        const action = element("button", "tl-graph-request-toggle", expanded ? "Hide request data" : "Request data");
        action.type = "button"; action.dataset.requestNodeId = item.id;
        action.setAttribute("aria-expanded", String(expanded));
        action.setAttribute("aria-label", `${expanded ? "Hide" : "Show"} incoming request data for ${scalar(node.service) || "this service"}`);
        detailListen(action, "click", () => onToggleRequest(node));
        requestButtons.set(item.id, action); actions.append(action);
        if (expanded) {
          const evidence = requestEvidence(item); action.setAttribute("aria-controls", evidence.id); body.append(evidence);
        }
      }
      const metadata = element("details", "tl-graph-detail-metadata");
      metadata.append(element("summary", "", "Capture details"), list); body.append(metadata);
      for (const hop of foldedHops.filter(hop => hop.target.id === node.id)) {
        const hopDetails = element("section", "tl-graph-folded-hop");
        hopDetails.append(element("h5", "", `Captured HTTP hop from ${scalar(hop.source.service) || "upstream service"}`));
        const fields = element("dl", "tl-graph-detail-list");
        const hopField = (name, value) => fields.append(element("dt", "", name), element("dd", "", scalar(value) || "Unavailable"));
        hopField("Caller API", rawApi(hop.call));
        if (callOrders.has(hop.call.id)) hopField("HTTP call order", callOrders.get(hop.call.id).description);
        hopField("Caller-local duration", duration(hop.call.durationMs));
        hopField("Server-local duration", duration(node.durationMs));
        hopField("Caller status", [scalar(hop.call.status), hop.call.httpStatus != null ? `HTTP ${scalar(hop.call.httpStatus)}` : ""].filter(Boolean).join(" · "));
        hopField("Peer", hop.call.peer); hopField("Destination name", hop.call.destinationName);
        hopField("Propagation", hop.call.propagationStatus); hopField("Caller instance", hop.call.serviceInstanceId);
        hopField("Caller segment ID", hop.source.segmentId); hopField("Call key", hop.call.callKey);
        hopDetails.append(fields, element("p", "tl-graph-notice", "Caller and server durations/statuses are independent local observations, not interchangeable or additive. Show HTTP hops to inspect the original boundary card."));
        body.append(hopDetails);
      }
      details.replaceChildren(detailSummary, body);
      if (node.kind === "segment" && typeof options.onSelect === "function") {
        const source = element("button", "tl-graph-source", "Open code flow");
        source.type = "button";
        listen(source, "click", () => options.onSelect(node), true);
        actions.append(source);
      }
    }
    function select(item, focus) {
      selectedId = item.id;
      inspection.selectedId = item.id;
      buttons.forEach((button, id) => {
        button.setAttribute("aria-pressed", String(id === selectedId));
        button.tabIndex = id === selectedId ? 0 : -1;
      });
      showDetails(item);
      updateMinimap();
      if (focus) {
        const button = buttons.get(item.id);
        button.focus({ preventScroll: true });
        const left = item.x * camera.zoom - 24, top = item.y * camera.zoom - 24;
        const right = (item.x + item.width) * camera.zoom + 24, bottom = (item.y + item.height) * camera.zoom + 24;
        if (left < viewport.scrollLeft) viewport.scrollLeft = left;
        else if (right > viewport.scrollLeft + viewport.clientWidth) viewport.scrollLeft = right - viewport.clientWidth;
        if (top < viewport.scrollTop) viewport.scrollTop = top;
        else if (bottom > viewport.scrollTop + viewport.clientHeight) viewport.scrollTop = bottom - viewport.clientHeight;
        updateMinimap();
      }
    }
    function callingCodeAction(observation, className = "", register = listen) {
      const action = element("button", `tl-graph-boundary-source ${className}`.trim(), "View calling code");
      action.type = "button";
      if (observation.kind === "http-call") {
        action.disabled = !httpContexts.calls.has(observation.id);
        action.title = action.disabled ? "The captured caller identity or HTTP-call link is missing or ambiguous."
          : `Open the caller-side HTTP evidence in ${scalar(observation.service)} for ${rawApi(observation)}. This does not open the receiving service's code.`;
        action.textContent = `View caller in ${scalar(observation.service) || "recorded service"}`;
        action.setAttribute("aria-label", action.disabled ? "HTTP calling code unavailable: caller evidence incomplete"
          : `View caller in ${scalar(observation.service)} for ${rawApi(observation)} (${scalar(observation.callKey)})`);
        register(action, "click", () => { if (!action.disabled) options.onOpenBoundary(observation); });
        return action;
      }
      action.disabled = numeric(observation.eventSeq) === null;
      action.title = action.disabled
        ? "No recorded event sequence is available for this observation."
        : `Open the recorded boundary event #${scalar(observation.eventSeq)} and its nearby source in ${scalar(observation.service) || "this service"}.`;
      action.setAttribute("aria-label", action.disabled ? "Calling code unavailable: event sequence not captured"
        : `View calling code for event #${scalar(observation.eventSeq)}`);
      register(action, "click", () => { if (!action.disabled) options.onOpenBoundary(observation); });
      return action;
    }
    function sqlGroupEvidence(item) {
      const rows = sqlGroups.get(item.id)?.nodes || [];
      const hasClients = item.node.clientObservationCount > 0;
      const evidence = element("section", "tl-graph-route-evidence tl-graph-sql-group-evidence");
      evidence.id = `${uid}-sql-calls`; evidence.tabIndex = 0;
      evidence.setAttribute("aria-label", `${hasClients ? "Database" : "SQL"} calls for ${systemName(item.node.system)}`);
      evidence.append(element("p", "tl-graph-route-note", hasClients
        ? "Recorded calls in event-sequence order, not a shared timeline. Client observations are not SQL execution counts. Open each call for its evidence and source; SQL appears only where captured."
        : "Recorded calls in event order. Open a call for SQL and source. Timings are per execution; returned does not imply commit."));
      if (item.node.sqlLimitReached) evidence.append(element("p", "tl-graph-sql-warning", "Capture limit reached — this list contains only retained calls."));
      const list = element("ol", "tl-graph-sql-calls");
      const navigation = element("div", "tl-graph-sql-pages");
      const range = element("span", "tl-graph-sql-range"); range.setAttribute("aria-live", "polite");
      const previous = element("button", "tl-graph-sql-prev", "Previous"), next = element("button", "tl-graph-sql-next", "Next");
      previous.type = next.type = "button";
      previous.setAttribute("aria-label", `Previous ${hasClients ? "database" : "SQL"} calls`); next.setAttribute("aria-label", `Next ${hasClients ? "database" : "SQL"} calls`);
      navigation.append(range, previous, next);
      evidence.append(list, navigation);
      let page = 0;
      const pageListeners = [];
      const clearPage = () => pageListeners.splice(0).forEach(remove => remove());
      detailListeners.push(clearPage);
      const pageListen = (target, name, handler) => {
        target.addEventListener(name, handler);
        pageListeners.push(() => target.removeEventListener(name, handler));
      };
      function drawPage() {
        clearPage(); list.replaceChildren();
        const start = page * LIMITS.sqlRows;
        for (const observation of rows.slice(start, start + LIMITS.sqlRows)) {
          const info = jdbc(observation), limit = dependencies.isSqlLimit(info);
          const row = element("li", "tl-graph-sql-row");
          const disclosure = element("details", "tl-graph-sql-call");
          const summary = element("summary", "tl-graph-sql-summary");
          const identity = numeric(observation.eventSeq) === null ? "Event unavailable" : `#${scalar(observation.eventSeq)}`;
          const outcome = !info ? observation.status === "SUBMITTED" ? "Submitted · completion not observed" : observation.status === "ERROR" ? "Client error" : "Client call observed"
            : limit ? "Capture limit" : observation.status === "SUBMITTED" ? "Completion not observed" : observation.status === "ERROR" ? observation.system === "clickhouse" ? "Failed" : "Threw" : observation.system === "clickhouse" ? "Response completed" : "Returned";
          summary.append(element("span", "tl-graph-sql-call-title", !info ? scalar(observation.operation) : limit ? "SQL capture limit" : dependencies.sqlTitle(observation)),
            element("span", `tl-graph-sql-call-result${limit || observation.status === "ERROR" ? " tl-graph-sql-warning" : ""}`,
              [identity, limit || !info ? "" : duration(info.durationMs), outcome,
                info?.sqlParameterSets ? `${info.sqlParameterSets.length} parameter sets` : info?.sqlParameters?.length ? `${info.sqlParameters.length} binding${info.sqlParameters.length === 1 ? "" : "s"}` : "",
                info?.sqlTruncated ? "SQL truncated" : ""].filter(Boolean).join(" · ")));
          const body = element("div", "tl-graph-sql-call-body");
          disclosure.append(summary, body); row.append(disclosure); list.append(row);
          let loaded = false;
          pageListen(disclosure, "toggle", () => {
            if (!disclosure.open || loaded) return;
            loaded = true;
            body.append(element("p", "tl-graph-sql-thread", scalar(observation.thread) || "Thread not captured"));
            if (info) body.append(dependencies.renderLazyJdbc(document, observation,
              options.loadBoundaryData ? () => options.loadBoundaryData(observation) : null));
            else {
              body.append(element("p", "tl-graph-route-note", "Local client observation only; remote execution and query completion are not established. SQL text and parameters were not captured for this observation."));
              if (observation.errorType) body.append(element("p", "tl-graph-sql-warning", scalar(observation.errorType)));
            }
            if (boundaryActions) body.append(callingCodeAction(observation, "", pageListen));
          });
        }
        range.textContent = `Calls ${start + 1}–${Math.min(start + LIMITS.sqlRows, rows.length)} of ${rows.length}`;
        previous.disabled = page === 0; next.disabled = start + LIMITS.sqlRows >= rows.length;
        navigation.hidden = rows.length <= LIMITS.sqlRows;
      }
      const changePage = delta => {
        page = Math.max(0, Math.min(Math.ceil(rows.length / LIMITS.sqlRows) - 1, page + delta));
        drawPage(); evidence.scrollTop = 0;
        list.querySelector(".tl-graph-sql-summary")?.focus({preventScroll: true});
      };
      detailListen(previous, "click", () => changePage(-1)); detailListen(next, "click", () => changePage(1));
      drawPage();
      return evidence;
    }
    function routeEvidence(item) {
      const node = item.node;
      const evidence = element("section", "tl-graph-route-evidence");
      evidence.setAttribute("aria-label", `Recorded routing decisions for ${scalar(node.destination)}`);
      evidence.append(element("p", "tl-graph-route-note", "Route observations only — not SQL queries, connections or a shared timeline."));
      const list = element("ol", "tl-graph-route-events"), rows = observations(node);
      for (const observation of rows.slice(0, LIMITS.routeRows)) {
        const row = element("li", "tl-graph-route-event");
        row.append(element("strong", "", numeric(observation.eventSeq) === null ? "Event sequence unavailable" : `Event #${scalar(observation.eventSeq)}`),
          element("span", "", scalar(observation.thread) || "Thread not captured"));
        if (boundaryActions) row.append(callingCodeAction(observation, "", detailListen));
        list.append(row);
      }
      evidence.append(list);
      if (rows.length > LIMITS.routeRows) evidence.append(element("p", "tl-graph-route-note", `Showing ${LIMITS.routeRows} of ${rows.length} recorded decisions. Remaining events are retained in the timeline and source capture.`));
      return evidence;
    }
    function httpActions(node) {
      const calls = node.kind === "http-call" ? [node] : httpContexts.receivers.get(node.id);
      const footer = element("div", "tl-graph-http-footer");
      let chosen = calls[0];
      if (calls.length > 1) {
        const chooser = element("select", "tl-graph-http-caller-select");
        chooser.setAttribute("aria-label", `Choose a captured caller for ${scalar(node.service) || "this service"}`);
        for (const call of calls) {
          const option = element("option", "", `${scalar(call.service)} · ${rawApi(call)} · ${scalar(call.callKey)}`);
          option.value = call.id; chooser.append(option);
        }
        chooser.value = chosen.id;
        const action = element("button", "tl-graph-boundary-source tl-graph-http-caller-action"); action.type = "button";
        const describe = () => {
          action.textContent = `View caller in ${scalar(chosen.service)}`;
          action.title = `Open the selected caller-side HTTP evidence in ${scalar(chosen.service)}. This does not open the receiving service's code.`;
          action.setAttribute("aria-label", `View caller in ${scalar(chosen.service)} for ${rawApi(chosen)} (${scalar(chosen.callKey)})`);
        };
        describe();
        detailListen(chooser, "change", () => { chosen = calls.find(call => call.id === chooser.value) || calls[0]; describe(); });
        detailListen(action, "click", () => options.onOpenBoundary(chosen)); footer.append(chooser, action);
      } else footer.append(callingCodeAction(chosen, "tl-graph-http-caller-action", detailListen));
      return footer;
    }
    function requestEvidence(item) {
      const evidence = element("section", "tl-graph-request-evidence");
      evidence.id = `${uid}-request-data`; evidence.tabIndex = 0; evidence.dataset.requestNodeId = item.id;
      evidence.setAttribute("aria-label", `Incoming request data for ${scalar(item.node.service) || "this service"}`);
      evidence.setAttribute("aria-busy", String(requestState.status === "loading"));
      if (requestState.status === "ready") {
        const content = requestData.render(document, requestState.trace, {collapsible: false});
        if (content) evidence.append(content);
        else evidence.append(element("p", "tl-graph-request-status", "Request data was not captured for this service segment. This capture has no retained query-parameter or request-body evidence."));
      } else {
        const status = element("p", "tl-graph-request-status", requestState.status === "loading"
          ? "Loading request data for this service segment…"
          : "Request data could not be loaded for this service segment. Close and reopen to retry.");
        status.setAttribute("role", "status"); evidence.append(status);
      }
      return evidence;
    }
    result.nodes.forEach(item => {
      const node = item.node;
      const incomingHops = foldedHops.filter(hop => hop.target.id === node.id);
      const callerFailed = incomingHops.some(hop => failure(hop.call));
      const callerTruncated = incomingHops.some(hop => hop.call.truncated);
      const button = element("button", `tl-graph-node tl-graph-${node.kind}${item.externalService ? " tl-graph-external-service" : ""}${category(node) === "database" ? " tl-graph-database-route" : ""}${category(node) === "cache" ? " tl-graph-cache" : ""}${category(node) === "queue" ? " tl-graph-queue" : ""}${failure(node) || callerFailed ? " tl-graph-failed" : ""}`);
      button.type = "button";
      button.dataset.nodeId = item.id;
      button.style.left = `${item.x}px`; button.style.top = `${item.y}px`;
      button.style.width = `${item.width}px`; button.style.height = `${BOX.height}px`;
      button.setAttribute("aria-describedby", help.id);
      const destinationLabel = httpLabels.get(node.id);
      const name = dbGroup(node) ? systemName(node.destinationName || node.destination || node.system)
        : destinationLabel?.name || scalar(node.destinationName) || scalar(node.destination) || scalar(node.peer) || systemName(node.system)
          || (item.externalService ? "Unknown external service" : scalar(node.service));
      const type = item.externalService ? "EXTERNAL SERVICE" : boundaryType(node);
      button.setAttribute("aria-label", `${type}: ${name}, ${dbGroup(node) ? `${sqlCount(node)}, ${sqlStatus(node)}` : isRoute(node) ? countLabel(observations(node).length, "recorded routing decision", "recorded routing decisions") : `${rawApi(node)}, ${item.externalService ? "caller" : "local"} duration ${duration(node.durationMs)}`}`);
      button.setAttribute("aria-pressed", String(item.id === selectedId));
      button.tabIndex = item.id === selectedId ? 0 : -1;
      const nodeHeading = element("span", "tl-graph-node-heading");
      nodeHeading.append(icon(node, item.externalService), element("span", "tl-graph-node-kind", type)); button.append(nodeHeading);
      button.append(element("span", "tl-graph-node-name", node.kind === "segment" ? scalar(node.service) || scalar(node.label) || "Unknown service" : name || "Unknown dependency"));
      const api = element("span", "tl-graph-node-api", dbGroup(node) ? sqlCount(node) : isRoute(node)
        ? countLabel(observations(node).length, "recorded routing decision", "recorded routing decisions") : rawApi(node));
      api.title = isRoute(node) ? "Recorded route observations, not a query or connection count" : rawApi(node); button.append(api);
      const status = dbGroup(node) ? sqlStatus(node) : [node.entryChannel === "SCHEDULER" ? `Job ${scalar(node.jobOutcome) || "outcome unknown"}`
        : node.httpStatus != null ? `HTTP ${scalar(node.httpStatus)}` : scalar(node.status) || "Status unknown",
        node.kind === "dependency-call" && numeric(node.durationMs) === null ? "" : `${duration(node.durationMs)} ${item.externalService ? "caller" : "local"}`].filter(Boolean).join(" · ");
      button.append(element("span", "tl-graph-node-metrics", status));
      const context = node.kind === "segment" ? `${callerFailed ? "Caller HTTP failure · " : ""}${callerTruncated ? "Caller capture truncated · " : ""}Inspect service →`
        : dbGroup(node) ? "Inspect recorded calls →"
        : isRoute(node) ? "Inspect events / threads →"
        : jdbc(node) ? "Inspect SQL / execution →"
        : node.kind === "dependency-call" ? node.evidenceKind === "wrapper-entry" ? "Wrapper only · network unconfirmed" : "Local observation · remote not observed"
        : item.downstreamObserved ? "Downstream observed" : "Remote execution not observed";
      button.append(element("span", "tl-graph-node-context", `${node.truncated ? "Truncated · " : ""}${context}`));
      if (node.kind === "segment") button.title = "Inspect this service segment; open code flow from its inspector";
      else if (node.kind !== "segment") {
        const evidence = destinationLabel ? `${destinationNameEvidence(destinationLabel)} ${boundaryEvidence(node)}` : boundaryEvidence(node);
        const description = callOrders.has(node.id) ? `${callOrders.get(node.id).description} ${evidence}` : evidence;
        button.title = description;
        button.setAttribute("aria-description", description);
      }
      if (incomingHops.length) button.setAttribute("aria-description", incomingHops.map(hop => callOrders.get(hop.call.id)?.description).filter(Boolean).join(" "));
      button.setAttribute("aria-controls", details.id);
      listen(button, "focus", () => { if (!restoringFocus) select(item, false); });
      listen(button, "click", () => {
        select(item, false);
        if (view?.matchMedia?.("(max-width: 900px)")?.matches) {
          details.focus({preventScroll: true});
          details.scrollIntoView?.({block: "nearest", inline: "nearest"});
        }
      });
      listen(button, "keydown", event => {
        if (event.key === "Escape") { event.preventDefault(); closeDetails(); return; }
        let target;
        if (event.key === "Home") target = result.nodes[0];
        else if (event.key === "End") target = result.nodes[result.nodes.length - 1];
        else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
          const direction = event.key === "ArrowUp" ? -1 : 1;
          target = result.nodes.find(candidate => candidate.rank === item.rank && candidate.slot === item.slot + direction);
        } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
          const direction = event.key === "ArrowLeft" ? -1 : 1;
          target = result.nodes.filter(candidate => candidate.rank === item.rank + direction)
            .sort((a, b) => Math.abs(a.y - item.y) - Math.abs(b.y - item.y) || compare(a.id, b.id))[0];
        } else return;
        event.preventDefault();
        if (target) select(target, true);
      });
      buttons.set(item.id, button); canvas.append(button);
    });
    surface.append(canvas); viewport.append(surface);
    workspace.append(diagram, details); frame.append(workspace);
    listen(details, "keydown", event => { if (event.key === "Escape") { event.preventDefault(); event.stopPropagation?.(); closeDetails(); } });
    if (inspection.open) showDetails(selected);
    else closeDetails(false);
    container.replaceChildren(wrapper);
    applyZoom(camera.zoom, false);
    if (camera.fitPending) requestFit();
    else if (previousScroll) { viewport.scrollLeft = previousScroll.left; viewport.scrollTop = previousScroll.top; }
    updateMinimap();
    // Disclosures preserve the camera; HTTP-hop changes intentionally fit the
    // new layout. Restoring focus itself must not reveal or pan to a card.
    if (restoreFocus) {
      restoringFocus = true; buttons.get(selected.id)?.focus({preventScroll: true}); restoringFocus = false;
    }
    if (previousRequestId) requestButtons.get(previousRequestId)?.focus({preventScroll: true});
    return () => {
      disposed = true; stopFitWatch();
      listeners.splice(0).forEach(remove => remove()); detailListeners.splice(0).forEach(remove => remove()); wrapper.remove();
    };
  }

  function render(container, model, options) {
    options = options || {};
    let cleanup = () => {}, stopped = false, showHttpHops = options.showHttpHops === true;
    const inspection = {selectedId: null, open: false};
    // Full trace evidence lives only in this active disclosure, never in the
    // canonical graph, grouping model, search summaries or exported graph.
    let requestState = null;
    const camera = {zoom: 1, fitPending: true};
    function toggleRequest(node) {
      if (stopped) return;
      if (requestState?.nodeId === node.id) { requestState = null; draw(); return; }
      const pending = {nodeId: node.id, status: "loading", trace: null};
      requestState = pending; draw();
      Promise.resolve().then(() => {
        if (!stopped && requestState === pending) return options.loadRequestData(node);
      }).then(trace => {
        if (stopped || requestState !== pending) return;
        pending.status = "ready"; pending.trace = trace; draw();
      }).catch(() => {
        if (stopped || requestState !== pending) return;
        pending.status = "error"; pending.trace = null; draw();
      });
    }
    function draw() {
      const previousCleanup = cleanup;
      cleanup = renderView(container, model, options, showHttpHops, () => {
        if (stopped) return;
        showHttpHops = !showHttpHops; camera.fitPending = true; draw();
        container.querySelector(".tl-graph-hop-toggle")?.focus({preventScroll: true});
      }, inspection, id => {
        if (requestState && requestState.nodeId !== id) requestState = null;
      }, camera, requestState, toggleRequest);
      previousCleanup();
    }
    draw();
    return () => { if (!stopped) { stopped = true; requestState = null; cleanup(); } };
  }

  return Object.freeze({ render, layout, presentation, groupDatabaseRoutes, groupDatabaseCalls, httpCallOrder, limits: LIMITS });
});
