/* Bounded, read-only analysis of retained local JDBC evidence. Never executes SQL. */
(function (root, factory) {
  "use strict";
  const api = factory(typeof module === "object" && module.exports ? require("./trace-graph.js") : root.TraceLensGraph);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensPerformanceModel = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (graphModel) {
  "use strict";
  const defaults = Object.freeze({slowQueryMs: 100, repeatedQueryCount: 3, nPlusOneCount: 5, queryHeavyCount: 50});
  // Reuse the graph's stricter identity/topology validator, including its bounds.
  const limits = Object.freeze({nodes: Math.min(10000, graphModel?.limits?.inputNodes || 4096),
    edges: graphModel?.limits?.inputEdges || 16384, findings: 200, sql: 16384, tokens: 4096});
  const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  const numeric = value => (typeof value === "number" || typeof value === "string" && value.length <= 32
      && /^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value))
    && Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : null;
  const sequence = value => { const number = numeric(value); return Number.isSafeInteger(number) ? number : null; };
  const nonempty = value => typeof value === "string" && value.trim().length > 0;
  const ordered = nodes => [...nodes].sort((a, b) => (sequence(a.eventSeq) ?? Infinity) - (sequence(b.eventSeq) ?? Infinity)
    || compare(a.id, b.id));
  const batch = node => /Batch$/.test(node.operation) || node.sqlSource === "batch"
    || node.batchSize != null || node.sqlParametersStatus === "batch" || node.sqlParameterSets != null;
  const expressionWords = new Set(("select distinct all where and or not xor when then else on having by limit offset " +
    "values set returning as in is like ilike between case using into from join update insert delete fetch first next " +
    "call interval escape at time zone default do with recursive union except intersect").split(" "));
  const valueWords = new Set(["true", "false", "null"]);
  const starts = new Set(["select", "insert", "update", "delete", "with"]);

  // This is a deliberately conservative lexer, not a SQL parser. Only complete,
  // supported templates receive a fingerprint. Values/comments are discarded;
  // captured parameters are never inspected or substituted. Identifier spelling
  // remains significant, so different quoted tables/columns cannot be merged.
  function normalize(node) {
    const sql = node.sql, system = node.system;
    if (typeof sql !== "string" || !sql.trim() || sql.length > limits.sql
        || !["prepared", "statement"].includes(node.sqlSource)
        || node.sqlTruncated != null && node.sqlTruncated !== false && node.sqlTruncated !== "false"
        || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(sql)) return null;
    const tokens = [], stack = [];
    let i = 0, ended = false;
    const add = (text, kind = "symbol") => { tokens.push({text, kind}); };
    const quote = (delimiter, escapes, identifier) => {
      const start = i++;
      while (i < sql.length) {
        if (sql[i] === "\\") {
          if (!escapes) return false; // Session-dependent escaping is unknown.
          i += 2; continue;
        }
        if (sql[i++] === delimiter) {
          if (sql[i] === delimiter) { i++; continue; }
          add(identifier ? sql.slice(start, i) : "?", identifier ? "identifier" : "value");
          return true;
        }
      }
      return false;
    };
    while (i < sql.length) {
      if (tokens.length > limits.tokens) return null;
      const ch = sql[i], next = sql[i + 1];
      if (/\s/.test(ch)) { i++; continue; }
      if (ch === "-" && next === "-" && (system === "postgresql" || !sql[i + 2] || /\s/.test(sql[i + 2]))
          || system === "mysql" && ch === "#") {
        while (i < sql.length && sql[i] !== "\r" && sql[i] !== "\n") i++;
        continue;
      }
      if (ch === "/" && next === "*") {
        if (system === "mysql" && /^(?:!|M!|\+)/.test(sql.slice(i + 2))) return null;
        let depth = 1; i += 2;
        while (i < sql.length && depth) {
          if (sql[i] === "/" && sql[i + 1] === "*") {
            if (system === "mysql") return null;
            depth++; i += 2;
          } else if (sql[i] === "*" && sql[i + 1] === "/") { depth--; i += 2; }
          else i++;
        }
        if (depth) return null;
        continue;
      }
      if (ended) return null; // More than one statement is not one query shape.
      if (ch === ";") { if (stack.length) return null; ended = true; i++; continue; }
      if (system === "postgresql" && ch === "$") {
        const tag = /^\$(?:[A-Za-z_\u0080-\uffff][A-Za-z0-9_\u0080-\uffff]*)?\$/.exec(sql.slice(i));
        if (tag) {
          const end = sql.indexOf(tag[0], i + tag[0].length);
          if (end < 0) return null;
          add("?", "value"); i = end + tag[0].length; continue;
        }
        const bind = /^\$[1-9]\d*/.exec(sql.slice(i));
        if (!bind || /[A-Za-z_$]/.test(sql[i + bind[0].length] || "")) return null;
        add("?", "value"); i += bind[0].length; continue;
      }
      // Recognized prefixed literals: E is PostgreSQL-only; U&, charset
      // introducers, Oracle quoting and other dialect extensions fail closed.
      if (/[eEnNbBxX]/.test(ch) && next === "'") {
        if (/[eE]/.test(ch) && system !== "postgresql") return null;
        i++; if (!quote("'", /[eE]/.test(ch), false)) return null;
        continue;
      }
      if (ch === "'" || ch === '"' || ch === "`") {
        if (ch === '"' && system === "mysql" || ch === "`" && system !== "mysql") return null;
        if (!quote(ch, false, ch !== "'")) return null;
        continue;
      }
      if (/[0-9]/.test(ch) || ch === "." && /[0-9]/.test(next || "")) {
        const number = /^(?:0[xX][0-9a-fA-F]+|0[bB][01]+|(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)/.exec(sql.slice(i));
        if (!number) return null;
        i += number[0].length;
        if (/[A-Za-z_$]/.test(sql[i] || "")) return null;
        add("?", "value"); continue;
      }
      if (/[A-Za-z_]/.test(ch)) {
        const word = /^[A-Za-z_][A-Za-z0-9_$]*/.exec(sql.slice(i))[0]; i += word.length;
        if (sql[i] === "'" || sql[i] === '"' || word.toLowerCase() === "u" && sql[i] === "&") return null;
        const lower = word.toLowerCase();
        add(valueWords.has(lower) ? "?" : system === "postgresql" || expressionWords.has(lower) ? lower : word,
          valueWords.has(lower) ? "value" : "word");
        continue;
      }
      if (ch === "?") {
        if (system === "postgresql" && ["?", "|", "&"].includes(next)) {
          const operator = sql.slice(i).match(/^\?\?(?:[|&])?|^\?[|&]/)[0];
          add(operator); i += operator.length; continue;
        }
        const previous = tokens[tokens.length - 1];
        if (system === "postgresql" && previous && (previous.kind === "value" || previous.kind === "identifier"
            || [")", "]"].includes(previous.text) || previous.kind === "word" && !expressionWords.has(previous.text.toLowerCase()))) return null;
        add("?", "value"); i++; continue;
      }
      if (ch === "(" || ch === "[" && system === "postgresql") { stack.push(ch); add(ch); i++; continue; }
      if (ch === ")" || ch === "]" && system === "postgresql") {
        if (stack.pop() !== (ch === ")" ? "(" : "[")) return null;
        add(ch); i++; continue;
      }
      const operator = /^(?:::|->>|->|#>>|#>|@>|<@|@\?|@@|<=|>=|<>|!=|\|\||&&|[=+*/%,.<>~!^|&-])/.exec(sql.slice(i));
      if (!operator || system === "mysql" && /[@#]/.test(operator[0])) return null;
      add(operator[0]); i += operator[0].length;
    }
    if (stack.length || !tokens.length || tokens.length > limits.tokens || !starts.has(tokens[0].text.toLowerCase())) return null;
    const last = tokens[tokens.length - 1];
    if (tokens.length < 2 || last.kind === "symbol" && !["*", ")", "]"].includes(last.text)
        || last.kind === "word" && expressionWords.has(last.text.toLowerCase())) return null;
    const pattern = tokens.map(token => token.text).join(" ");
    const where = tokens.findIndex(token => token.kind === "word" && token.text.toLowerCase() === "where");
    // Connection validation (SELECT 1), metadata polling and unfiltered table
    // reads are not enough for even this narrow N+1 lookup hypothesis.
    const lookup = tokens[0].text.toLowerCase() === "select"
      && tokens.some(token => token.kind === "word" && token.text.toLowerCase() === "from")
      && where > 0 && tokens.slice(where + 1).some(token => token.kind === "value");
    return pattern.length <= limits.sql ? {pattern, select: lookup} : null;
  }

  function analyze(graph, options) {
    const warnings = ["Only retained local JDBC evidence is analyzed. No matching finding is not a clean bill of health."], findings = [];
    const thresholds = {};
    for (const [key, fallback] of Object.entries(defaults)) {
      const value = options && options[key];
      if (value === undefined) thresholds[key] = fallback;
      else if (typeof value === "number" && Number.isFinite(value)
          && (key === "slowQueryMs" ? value >= 1 && value <= 600000
            : Number.isSafeInteger(value) && value >= (key === "queryHeavyCount" ? 1 : 2) && value <= 1000)) thresholds[key] = value;
      else { thresholds[key] = fallback; warnings.push(`Invalid ${key} threshold; the default was used.`); }
    }
    const result = {findings, queryCount: 0, segmentCount: 0, warnings, thresholds};
    const warn = message => { if (!warnings.includes(message)) warnings.push(message); };
    if (!graph || graph.schemaVersion !== 1 || graph.kind !== "request-flow" || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) {
      warn("Analysis unavailable: a raw schema-version-1 request-flow graph is required."); return result;
    }
    if (graph.nodes.length > limits.nodes || graph.edges.length > limits.edges) {
      warn(`Analysis unavailable: the safety limit is ${limits.nodes} nodes and ${limits.edges} edges; completeness is unknown.`); return result;
    }
    if (typeof graphModel?.groupDatabaseCalls !== "function") {
      warn("Analysis unavailable: the request-graph evidence validator is not loaded."); return result;
    }
    if (graph.coverage?.complete !== true || graph.partial === true || graph.coverage?.partial === true
        || graph.truncated === true || graph.coverage?.truncated === true
        || Array.isArray(graph.coverage?.warnings) && graph.coverage.warnings.length) {
      warn("Capture coverage is partial or unavailable. Unretained queries and uncaptured work cannot be assessed.");
    }
    const originals = new Map(), segments = new Map();
    for (const node of graph.nodes) {
      if (node && nonempty(node.id)) {
        originals.set(node.id, node);
        if (node.kind === "segment") segments.set(node.id, node);
      }
    }
    result.segmentCount = segments.size;
    // The validator does not need SQL/parameter payloads. A narrow projection
    // prevents its display helpers from parsing values or oversized batch rows.
    const fields = ["id", "kind", "ownerId", "service", "serviceInstanceId", "segmentId", "segmentKey", "system", "destination",
      "destinationName", "direction", "boundaryKind", "evidenceKind", "operation", "status", "callKey", "downstreamState", "eventSeq"];
    const projected = graph.nodes.map(node => node && typeof node === "object"
      ? Object.fromEntries(fields.filter(key => Object.prototype.hasOwnProperty.call(node, key)).map(key => [key, node[key]])) : node);
    const groups = graphModel.groupDatabaseCalls({...graph, nodes: projected}).groups;
    const accepted = new Set(groups.flatMap(group => group.nodes.map(node => node.id)));
    let excluded = 0, missingTiming = 0, missingPattern = 0, missingSequence = 0, batched = 0;
    for (const node of graph.nodes) {
      if (node?.evidenceKind === "jdbc-query-limit") warn("A JDBC capture-limit marker is present. Additional executions were not retained; counts are lower bounds.");
      if (node?.evidenceKind === "jdbc-execution" && !accepted.has(node.id)) excluded++;
      if (node?.evidenceKind === "jdbc-group" || node?.presentationKind === "jdbc-group") {
        warn("Grouped presentation nodes cannot be analyzed. Load raw retained JDBC boundaries; query completeness is unknown.");
      }
    }
    const append = (kind, nodes, pattern, explanation, confidence) => {
      nodes = ordered(nodes);
      const first = nodes[0], durations = nodes.map(node => numeric(node.durationMs)), known = durations.filter(value => value !== null);
      const sum = known.reduce((total, value) => total + value, 0);
      const totalDurationMs = known.length === nodes.length && Number.isFinite(sum) ? sum : null;
      const titles = {"slow-query": "Slow JDBC execution", "repeated-query": "Repeated SQL pattern",
        "suspected-n-plus-one": "Suspected N+1 query pattern", "query-heavy": "Query-heavy service segment"};
      findings.push({id: `performance:${kind}:${findings.length + 1}`, kind, title: titles[kind], ownerId: first.ownerId,
        service: first.service ?? segments.get(first.ownerId)?.service ?? null, system: first.system,
        destination: first.destination ?? null, pattern, count: nodes.length, totalDurationMs,
        maxDurationMs: known.length ? Math.max(...known) : null, nodes,
        explanation: explanation + " Durations are cumulative individual JDBC execution time, not request latency or database-server time."
          + (totalDurationMs === null ? " Complete cumulative timing is unavailable." : "")
          + (!nonempty(first.destination) ? " Database destination is unknown; these observations do not establish a single database." : ""), confidence});
    };
    const buckets = groups.map(group => ({...group, nodes: group.nodes.map(node => originals.get(node.id))}));
    for (const group of buckets) {
      const executions = group.nodes.filter(node => node.evidenceKind === "jdbc-execution");
      if (!executions.length) continue;
      result.queryCount += executions.length;
      if (!nonempty(executions[0].destination)) warn("Database destination is unavailable for some executions. Unknown destinations are kept separate from known ones; a shared database is not established.");
      const patterns = new Map();
      const threadSequences = new Map(), ambiguousThreads = new Set();
      for (const node of executions) {
        const time = numeric(node.durationMs);
        if (time === null) missingTiming++;
        const isBatch = batch(node), normalized = isBatch ? null : normalize(node);
        if (isBatch) batched++; else if (!normalized) missingPattern++;
        if (!nonempty(node.thread) || node.thread.length > 4096 || sequence(node.eventSeq) === null) missingSequence++;
        else {
          if (!threadSequences.has(node.thread)) threadSequences.set(node.thread, new Set());
          const coordinates = threadSequences.get(node.thread), coordinate = sequence(node.eventSeq);
          if (coordinates.has(coordinate)) ambiguousThreads.add(node.thread);
          coordinates.add(coordinate);
        }
        if (time !== null && time >= thresholds.slowQueryMs) {
          append("slow-query", [node], normalized?.pattern ?? null,
            `Observed JDBC execution duration meets the ${thresholds.slowQueryMs} ms threshold.${isBatch ? " This is one batch execution, not a count of batch rows." : ""}`, "observed");
        }
        if (normalized) {
          if (!patterns.has(normalized.pattern)) patterns.set(normalized.pattern, {select: normalized.select, nodes: []});
          patterns.get(normalized.pattern).nodes.push(node);
        }
      }
      if (ambiguousThreads.size) warn("Duplicate sequence coordinates prevent N+1 inference for some repeated queries; their ordering is unknown.");
      for (const [pattern, shape] of patterns) {
        const nPlusOneNodes = new Set(), threads = new Map();
        if (shape.select) for (const node of shape.nodes) {
          if (!nonempty(node.thread) || node.thread.length > 4096 || sequence(node.eventSeq) === null || ambiguousThreads.has(node.thread)) continue;
          if (!threads.has(node.thread)) threads.set(node.thread, []);
          threads.get(node.thread).push(node);
        }
        for (const nodes of threads.values()) {
          if (nodes.length < thresholds.nPlusOneCount) continue;
          nodes.forEach(node => nPlusOneNodes.add(node.id));
          append("suspected-n-plus-one", nodes, pattern,
            "The same SELECT shape repeats within one owning service segment and recorded thread, with distinct sequence IDs. This is only an N+1 hypothesis: pagination, retries or an intentional loop can look the same. Inspect the calling code and parent/child data access; repetition alone is not proof.", "heuristic");
        }
        const remaining = shape.nodes.filter(node => !nPlusOneNodes.has(node.id));
        if (remaining.length >= thresholds.repeatedQueryCount) append("repeated-query", remaining, pattern,
          "The retained non-batch executions share a normalized SQL shape within this segment and recorded destination scope. Values are removed; repeated calls do not prove redundant work or an N+1 issue.", "observed");
      }
      if (executions.length >= thresholds.queryHeavyCount) append("query-heavy", executions, null,
        `This segment/destination scope contains at least ${executions.length} retained JDBC executions, meeting the ${thresholds.queryHeavyCount}-execution threshold. Each batch counts once; this is not a count of rows or a proven bottleneck.`, "heuristic");
    }
    if (excluded) warn(`${excluded} JDBC execution(s) were excluded because ownership, topology, driver or identity evidence is unsafe or unsupported; counts are incomplete.`);
    if (missingTiming) warn(`${missingTiming} execution(s) have unavailable or invalid timing; slow-query assessment and cumulative duration are incomplete.`);
    if (missingPattern) warn(`${missingPattern} non-batch execution(s) have unavailable, truncated, ambiguous or unsupported SQL templates; repetition assessment is incomplete.`);
    if (missingSequence) warn(`${missingSequence} execution(s) lack a valid thread or sequence ID; N+1 ordering is unknown for those executions.`);
    if (batched) warn(`${batched} batch execution(s) count once each and are excluded from repeated-query and N+1 inference; batch rows are not queries.`);
    if (!result.queryCount) warn("No validated JDBC executions are available. Database performance is unknown, not healthy by default.");
    // At most one slow finding per execution, one repetition/N+1 membership per
    // non-batch execution and one heavy finding per group: candidate work stays
    // bounded by 2 * input nodes + groups. Rank before clipping so a late, costly
    // execution is not hidden by many earlier low-duration findings.
    findings.sort((a, b) => (b.totalDurationMs ?? b.maxDurationMs ?? -1) - (a.totalDurationMs ?? a.maxDurationMs ?? -1)
      || (b.maxDurationMs ?? -1) - (a.maxDurationMs ?? -1) || b.count - a.count
      || compare(a.kind, b.kind) || compare(a.ownerId, b.ownerId) || compare(a.nodes[0].id, b.nodes[0].id));
    if (findings.length > limits.findings) {
      findings.length = limits.findings;
      warn(`Finding display limited to ${limits.findings}, ranked by cumulative observed JDBC duration; additional matching findings are omitted.`);
    }
    return result;
  }
  return Object.freeze({analyze, defaults, limits});
});
