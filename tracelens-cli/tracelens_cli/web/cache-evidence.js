/* Derive explicitly weaker cache-access cards from retained wrapper entries. No I/O. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory(require("./dependency-ui.js"));
  else root.TraceLensCacheEvidence = factory(root.TraceLensDependencies);
})(typeof globalThis !== "undefined" ? globalThis : this, function (dependencies) {
  "use strict";
  const LIMITS = Object.freeze({exports: 64, events: 100000, added: 128, rows: 8192, nodes: 4096, edges: 16384});
  const NOTE = "Cache access cards derived from recorded ValkeyCacheService method entries; not new remote evidence. Cache hit/miss, network operations and remote execution are not inferred.";
  const object = value => value !== null && typeof value === "object" && !Array.isArray(value);
  const key = value => typeof value === "string" && value.length > 0 && value.length <= 4096 && value.trim() ? value : null;
  const sequence = value => {
    if (typeof value === "string" && /^[0-9]{1,16}$/.test(value)) value = Number(value);
    return Number.isSafeInteger(value) && value > 0 ? value : null;
  };
  const identityFields = ["service", "serviceInstanceId", "segmentId", "segmentKey"];
  const identity = row => object(row) && identityFields.every(field => key(row[field])) && key(row.traceId);
  const threadName = value => typeof value === "string" && !!value.trim() && /^[A-Za-z0-9_.$:#/\[\] -]{1,128}$/.test(value);
  const matches = (a, b) => identityFields.every(field => a[field] === b[field]);
  const exactEvent = (owner, seq) => JSON.stringify([owner.segmentKey, seq]);
  const appendWarnings = (existing, added) => [...new Set([...(Array.isArray(existing) ? existing : []), ...added])];

  function augmentJourney(journey, exports) {
    if (!object(journey) || !Array.isArray(exports) || !dependencies || typeof dependencies.boundaryEvents !== "function") return journey;
    const graph = journey.visualization;
    if (!object(graph) || graph.kind !== "request-flow" || graph.schemaVersion !== 1 ||
        !key(journey.traceId) || graph.traceId !== journey.traceId ||
        !Array.isArray(graph.nodes) || !Array.isArray(graph.edges) || !Array.isArray(journey.segments)) return journey;
    const warningOnly = warning => ({
      ...journey, partial: true, warnings: appendWarnings(journey.warnings, [warning]),
      visualization: {...graph, partial: true, warnings: appendWarnings(graph.warnings, [warning])}
    });
    const calls = journey.externalCalls === undefined ? [] : journey.externalCalls;
    if (!Array.isArray(calls)) return journey;
    if (exports.length > LIMITS.exports || journey.segments.length > LIMITS.rows || calls.length > LIMITS.rows ||
        graph.nodes.length > LIMITS.nodes || graph.edges.length > LIMITS.edges) {
      return warningOnly("Cache access derivation limit reached; no additional cache cards were inferred from this capture.");
    }
    const ids = new Set(), edgeIds = new Set();
    for (const node of graph.nodes) {
      if (!object(node) || !key(node.id) || ids.has(node.id) ||
          !["segment", "http-call", "dependency-call"].includes(node.kind)) return journey;
      ids.add(node.id);
    }
    for (const edge of graph.edges) {
      if (!object(edge) || !key(edge.id) || edgeIds.has(edge.id) || !ids.has(edge.from) || !ids.has(edge.to) ||
          !["outbound", "inbound", "observed-call"].includes(edge.kind)) return journey;
      edgeIds.add(edge.id);
    }
    if (graph.roots !== undefined && (!Array.isArray(graph.roots) || graph.roots.some(id => !ids.has(id)))) return journey;
    const addedNodes = [], addedEdges = [], addedCalls = [], warnings = new Set(), existing = new Set();
    let previousDerived = 0;
    for (const node of graph.nodes) {
      const seq = sequence(node.eventSeq);
      if (node.kind === "dependency-call" && key(node.segmentKey) && seq) existing.add(exactEvent(node, seq));
      if (node.kind === "dependency-call" && node.boundaryKind === "cache" && node.evidenceKind === "wrapper-entry" &&
          key(node.ownerId) && key(node.segmentKey) && seq &&
          node.callKey === `${node.segmentKey}:cache-wrapper:${seq}` && node.id.startsWith(`cache-wrapper:${node.ownerId}:${seq}`)) previousDerived++;
    }
    for (const call of calls) {
      const seq = sequence(call?.eventSeq);
      if (key(call?.ownerSegmentKey) && seq) existing.add(JSON.stringify([call.ownerSegmentKey, seq]));
    }
    const allocate = (base, used) => {
      let id = base, suffix = 1;
      while (used.has(id)) id = `${base}:${suffix++}`;
      used.add(id); return id;
    };
    for (const exported of exports) {
      if (!identity(exported) || exported.traceId !== journey.traceId || !Array.isArray(exported.events)) continue;
      const owners = journey.segments.filter(row => identity(row) && row.traceId === journey.traceId && matches(row, exported));
      const copies = exports.filter(row => identity(row) && row.traceId === journey.traceId && matches(row, exported));
      const canonical = graph.nodes.filter(node => node.kind === "segment" && matches(node, exported));
      if (owners.length !== 1 || copies.length !== 1 || canonical.length !== 1 ||
          journey.segments.filter(row => row?.segmentKey === exported.segmentKey).length !== 1 ||
          graph.nodes.filter(node => node.kind === "segment" && node.segmentKey === exported.segmentKey).length !== 1) continue;
      const owner = canonical[0];
      if (owner.traceId !== undefined && owner.traceId !== journey.traceId) continue;
      if (exported.events.length > LIMITS.events) {
        warnings.add("Cache access derivation event limit reached; this segment was not scanned.");
        continue;
      }
      const sequences = new Map();
      for (const event of exported.events) {
        const seq = sequence(event?.seq);
        if (seq) sequences.set(seq, (sequences.get(seq) || 0) + 1);
      }
      const selected = dependencies.boundaryEvents(exported.events);
      if (selected.scanLimited) {
        warnings.add("Cache access derivation event limit reached; this segment was not scanned.");
        continue;
      }
      for (const {event, metadata} of selected.rows) {
        if (event?.type !== "METHOD_ENTER" || event.className !== "com.chargebee.ems.config.cache.ValkeyCacheService" ||
            !["get", "put", "evict"].includes(event.methodName) || metadata?.boundaryKind !== "cache" || metadata.system !== "redis" ||
            metadata.evidenceKind !== "wrapper-entry" || metadata.status !== "OBSERVED") continue;
        const seq = sequence(event.seq);
        if (!seq || sequences.get(seq) !== 1 || !threadName(event.thread)) {
          warnings.add("Cache access entries with missing or ambiguous event identity were omitted; no calling source was guessed.");
          continue;
        }
        const eventKey = exactEvent(owner, seq);
        if (existing.has(eventKey)) continue;
        if (previousDerived + addedNodes.length >= LIMITS.added || graph.nodes.length + addedNodes.length >= LIMITS.nodes ||
            graph.edges.length + addedEdges.length >= LIMITS.edges) {
          warnings.add("Cache access card limit reached; additional wrapper entries remain in the captured timeline.");
          continue;
        }
        existing.add(eventKey);
        const id = allocate(`cache-wrapper:${owner.id}:${seq}`, ids);
        const callKey = `${owner.segmentKey}:cache-wrapper:${seq}`;
        // Copy only canonical metadata and ownership, never method source,
        // arguments, cache keys, payloads or return values.
        const boundary = {
          boundaryKind: "cache", system: "redis", direction: "outbound", destination: null,
          operation: metadata.operation, status: "OBSERVED", evidenceKind: "wrapper-entry",
          eventSeq: seq, thread: event.thread, errorType: null
        };
        addedCalls.push({...boundary, service: owner.service, serviceInstanceId: owner.serviceInstanceId, segmentId: owner.segmentId,
          ownerSegmentKey: owner.segmentKey, callKey});
        addedNodes.push({...boundary, id, kind: "dependency-call", label: `Cache access · Redis / Valkey · ${metadata.operation}`,
          service: owner.service, serviceInstanceId: owner.serviceInstanceId, segmentId: owner.segmentId,
          segmentKey: owner.segmentKey, ownerId: owner.id, callKey, downstreamState: "not-observed",
          durationMs: null, httpStatus: null, method: null, path: null, entryChannel: null, eventCount: null, truncated: false});
        addedEdges.push({id: allocate(`cache-wrapper-edge:${owner.id}:${seq}`, edgeIds), from: owner.id, to: id, kind: "outbound"});
      }
    }
    if (!addedNodes.length && !warnings.size) return journey;
    if (!addedNodes.length && [...warnings].every(warning =>
      Array.isArray(journey.warnings) && journey.warnings.includes(warning) &&
      Array.isArray(graph.warnings) && graph.warnings.includes(warning))) return journey;
    if (addedNodes.length) warnings.add(NOTE);
    const messages = [...warnings];
    return {...journey, partial: true, externalCalls: [...calls, ...addedCalls],
      warnings: appendWarnings(journey.warnings, messages),
      visualization: {...graph, partial: true, nodes: [...graph.nodes, ...addedNodes], edges: [...graph.edges, ...addedEdges],
        warnings: appendWarnings(graph.warnings, messages)}};
  }
  return Object.freeze({augmentJourney, LIMITS});
});
