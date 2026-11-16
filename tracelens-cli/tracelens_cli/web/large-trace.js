/* One bounded detail window per opened segment; saved sources never read live APIs. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensLargeTrace = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const LIMIT = 500, PAGE_BYTES = 262144;
  function create(api, identity, snapshotId) {
    const expected = {...identity};
    const params = new URLSearchParams();
    if (expected.service) params.set("service", expected.service);
    if (expected.serviceInstanceId) params.set("instance", expected.serviceInstanceId);
    if (snapshotId && !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(snapshotId)) throw new Error("Invalid saved snapshot ID.");
    const prefix = snapshotId ? `/api/snapshots/${snapshotId}/segments/${encodeURIComponent(expected.segmentId)}/`
      : "/api/segment-";
    function check(doc) {
      const actual = {...doc, ...doc?.segment, ...doc?.identity};
      for (const field of ["segmentId", "traceId", "serviceInstanceId", "service"]) {
        if (expected[field] && actual[field] !== expected[field]) throw new Error("Segment detail identity changed. No other request was substituted.");
      }
      return actual;
    }
    let through, generation = 0;
    const source = {page: null, history: [], kind: "all", loading: false,
      async read(kind, query = {}) {
        const search = new URLSearchParams(params);
        for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== null) search.set(key, value);
        const path = snapshotId ? prefix + kind : prefix + kind + "/" + encodeURIComponent(expected.segmentId);
        return check(await api(path + "?" + search, "GET", kind === "events" ? PAGE_BYTES + 65536 : 2 * 1024 * 1024));
      },
      async overview() {
        const doc = await source.read("overview");
        for (const field of ["traceId", "serviceInstanceId", "service"]) if (!expected[field] && doc[field]) expected[field] = doc[field];
        if (expected.service) params.set("service", expected.service);
        if (expected.serviceInstanceId) params.set("instance", expected.serviceInstanceId);
        through = doc.through;
        return doc;
      },
      requestData() { return source.read("request-data"); },
      execution() { return source.read("execution", {through}); },
      async events(query = {}) {
        const doc = await source.read("events", {after: 0, through, limit: LIMIT, maxBytes: PAGE_BYTES, kind: "all", ...query});
        if (!Array.isArray(doc.events) || doc.events.length > LIMIT) throw new Error("Invalid bounded event page.");
        if (through === undefined) through = doc.through;
        if (doc.through !== through) throw new Error("The event capture watermark changed. Reopen this segment.");
        return {...doc, after: doc.after ?? (query.aroundSeq != null ? Math.max(0, Number(query.aroundSeq) - 100) : query.after || 0)};
      },
      async show(query = {}, direction) {
        const version = ++generation;
        source.loading = true;
        try {
          let page = await source.events({...query, kind: query.kind || source.kind});
          if (version !== generation) return null;
          if (query.aroundSeq != null && !page.events.some(event => String(event.seq) === String(query.aroundSeq))) {
            page = await source.events({after: Math.max(0, Number(query.aroundSeq) - 1), kind: query.kind || source.kind});
          }
          if (version !== generation) return null;
          if (direction === "next" && source.page) source.history = [...source.history.slice(-127), source.page.after || 0];
          else if (direction === "previous") source.history.pop();
          else source.history = [];
          source.kind = query.kind || source.kind; source.page = page;
          return page;
        } finally { if (version === generation) source.loading = false; }
      },
      async boundary(seq) {
        if (!Number.isSafeInteger(Number(seq)) || Number(seq) < 1) throw new Error("SQL event sequence is unavailable.");
        const page = await source.events({after: Number(seq) - 1, limit: 1});
        const event = page.events.find(event => String(event.seq) === String(seq));
        if (!event || event.type !== "EXTERNAL_CALL") throw new Error("This exact SQL event was not retained. Preview text cannot be copied as full SQL.");
        return event;
      }
    };
    return source;
  }
  async function hydrateSql(api, graph, snapshotId, isCurrent = () => true) {
    const groups = new Map(), replacements = new Map(), warnings = [];
    const nodes = Array.isArray(graph.nodes) ? graph.nodes.slice(0, 4096) : [];
    const owners = nodes.filter(node => node.kind === "segment");
    for (const node of nodes) {
      if (node.kind !== "dependency-call" || node.evidenceKind !== "jdbc-execution" || !node.sqlDetailsAvailable) continue;
      const matches = owners.filter(owner => ["segmentId", "serviceInstanceId", "service", "segmentKey"].every(field => owner[field] && owner[field] === node[field]));
      if (matches.length !== 1) { warnings.push("SQL detail owner is missing or ambiguous; preview was not analyzed."); continue; }
      const key = JSON.stringify([node.service, node.serviceInstanceId, node.segmentId]);
      if (!groups.has(key)) groups.set(key, {owner: matches[0], candidates: new Map()});
      const candidates = groups.get(key).candidates, seq = String(node.eventSeq);
      if (candidates.has(seq)) warnings.push("SQL detail sequence is ambiguous; preview was not analyzed.");
      candidates.set(seq, candidates.has(seq) ? null : node);
    }
    let pages = 0;
    for (const {owner, candidates} of groups.values()) {
      if (!isCurrent()) throw new Error("Analysis cancelled.");
      if (pages >= 16) { warnings.push("Additional SQL segments exceed the 16-page analysis budget; previews were not fingerprinted."); break; }
      const pending = new Set([...candidates].filter(([, node]) => node).map(([seq]) => seq));
      if (!pending.size) continue;
      try {
        const source = create(api, {...owner, traceId: graph.traceId}, snapshotId);
        await source.overview();
        let after = 0;
        while (pending.size && pages < 16) {
          if (!isCurrent()) throw new Error("Analysis cancelled.");
          const page = await source.events({after, kind: "boundaries"}); pages++;
          for (const event of page.events) {
            const seq = String(event.seq), node = candidates.get(seq), attrs = event.attributes || {};
            if (!node || !pending.has(seq) || event.type !== "EXTERNAL_CALL") continue;
            if (["boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind"]
              .some(field => node[field] != null && String(node[field]) !== String(attrs[field]))) continue;
            const detail = {...node};
            for (const field of ["sql", "sqlTruncated", "sqlSource"]) if (attrs[field] !== undefined) detail[field] = attrs[field];
            replacements.set(node, detail); pending.delete(seq);
          }
          if (!page.hasMore || !(Number(page.nextAfter) > after)) break;
          after = Number(page.nextAfter);
        }
        if (pending.size) warnings.push(`${pending.size} SQL details were unavailable or outside the 16-page analysis budget; compact previews were not fingerprinted.`);
      } catch (error) { if (!isCurrent()) throw error; warnings.push(`SQL details unavailable: ${error.message}`); }
    }
    return {...graph, nodes: (graph.nodes || []).map(node => replacements.get(node) || node),
      partial: !!graph.partial || warnings.length > 0, warnings: [...(graph.warnings || []), ...warnings]};
  }
  return Object.freeze({create, hydrateSql, LIMIT, PAGE_BYTES});
});
