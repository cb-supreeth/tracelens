/* Loaded request metadata only: no event expansion, diagnosis or background work. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensRequestInsights = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const LIMITS = Object.freeze({segments: 128, links: 1024, calls: 1024, nodes: 4096, items: 12, visible: 3, captures: 8});
  const object = value => value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const array = value => Array.isArray(value) ? value : [];
  const text = (value, max = 96) => typeof value === "string"
    ? value.slice(0, max).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ").trim() : "";
  const key = value => typeof value === "string" && value.length > 0 && value.length <= 512
    && !/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(value) ? value : null;
  const flag = value => value === true || value === "true";
  const absent = value => value === false || value === "false";
  const integer = value => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value
    : typeof value === "string" && value.length <= 16 && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
  const statusCode = value => { const code = integer(value); return code >= 100 && code <= 599 ? code : null; };
  const field = (row, name) => row[name] ?? object(row.attributes)[name];
  const name = row => text(row.service || row.applicationId || object(row.attributes).applicationId) || "Unknown service";
  function identity(row) {
    const result = {};
    for (const field of ["segmentKey", "segmentId", "service", "serviceInstanceId", "traceId"]) {
      if (key(row[field])) result[field] = row[field];
    }
    return result;
  }
  function sameSegment(a, b) {
    return !!key(a.segmentId) && a.segmentId === b.segmentId && !!key(a.service) && a.service === b.service
      && !!key(a.serviceInstanceId) && a.serviceInstanceId === b.serviceInstanceId
      && (!a.segmentKey || !b.segmentKey || a.segmentKey === b.segmentKey)
      && (!a.traceId || !b.traceId || a.traceId === b.traceId);
  }
  function failure(row, http = true) {
    const code = http ? statusCode(field(row, "httpStatus")) : null;
    if (code >= 400) return "HTTP " + code;
    if (flag(field(row, "asyncTimeout"))) return "an explicit timeout";
    const status = text(field(row, "status")).toUpperCase();
    if (status === "FAILED" || status === "ERROR") return "status " + status;
    if (text(field(row, "entryChannel") || row.channel).toUpperCase() === "SCHEDULER"
        && text(field(row, "jobOutcome")).toUpperCase() === "FAILED") return "a failed job outcome";
    return null;
  }
  function summarize(input, options = {}) {
    const doc = object(input), entry = object(options.entry), rawSegments = array(doc.segments);
    const segments = [], items = [], captures = [];
    let order = 0, omittedInputs = 0, unattributed = 0;
    const take = (rows, limit) => { omittedInputs += Math.max(0, rows.length - limit); return rows.slice(0, limit); };
    const add = (priority, kind, title, detail, segment = null, boundary = null) => {
      items.push({priority, kind, title, detail, segment, boundary, order: order++});
    };
    for (const raw of take(rawSegments, LIMITS.segments)) {
      const row = object(raw);
      if (!raw || typeof raw !== "object" || Array.isArray(raw)) continue;
      if (key(doc.traceId) && key(row.traceId) && doc.traceId !== row.traceId) { unattributed++; continue; }
      segments.push(row);
    }
    const uniqueKeys = new Map();
    for (const segment of segments) if (key(segment.segmentKey)) uniqueKeys.set(segment.segmentKey,
      uniqueKeys.has(segment.segmentKey) ? null : segment);
    const completeSegments = rawSegments.length <= LIMITS.segments;
    const target = row => completeSegments && segments.filter(segment => sameSegment(segment, row)).length === 1
      && (!row.segmentKey || uniqueKeys.get(row.segmentKey) === row) ? identity(row) : null;
    const entryMatches = segments.filter(segment => sameSegment(segment, object(entry.segment)));
    let root = entry.known === true && completeSegments && entryMatches.length === 1 && target(entryMatches[0]) ? entryMatches[0] : null;
    const links = take(array(doc.links), LIMITS.links), children = new Map(), descendants = new Set();
    for (const raw of links) {
      const link = object(raw), from = uniqueKeys.get(link.fromSegmentKey), to = uniqueKeys.get(link.toSegmentKey);
      if (!from || !to || from === to) continue;
      if (!children.has(from)) children.set(from, []);
      children.get(from).push(to);
      if (to === root) root = null;
    }
    const rootCode = root ? statusCode(field(root, "httpStatus")) : null;
    const rootSucceeded = rootCode >= 200 && rootCode < 400;
    // Only explicit captured links establish downstream order; service names and clocks never do.
    if (root && array(doc.links).length <= LIMITS.links) {
      const queue = [root], visited = new Set(queue);
      for (let index = 0; index < queue.length; index++) for (const child of children.get(queue[index]) || []) {
        if (visited.has(child)) continue;
        visited.add(child); descendants.add(child); queue.push(child);
      }
    }
    for (const segment of segments) {
      const reason = failure(segment), code = statusCode(field(segment, "httpStatus"));
      if (reason) {
        const nested = rootSucceeded && code >= 400 && descendants.has(segment);
        add(nested ? 0 : 1, nested ? "downstream-http-failure" : "segment-failure",
          nested ? `Entry HTTP ${rootCode}; ${name(segment)} returned HTTP ${code}` : `${name(segment)} reported ${reason}`,
          nested ? "A linked downstream segment recorded this failure. The entry response does not establish recovery or business success."
            : "Inspect this service's recorded evidence. This observation does not identify the root cause.", target(segment));
      }
      const missing = absent(field(segment, "detailsAvailable")), truncated = flag(field(segment, "truncated")) || flag(field(segment, "detailTruncated"))
        || integer(field(segment, "omittedDetailCallbacks")) > 0;
      if (missing || truncated) add(3, missing ? "details-unavailable" : "details-truncated",
        `${name(segment)}: ${missing ? "execution details unavailable" : "execution detail is incomplete"}`,
        missing ? "Its loaded summary remains available; missing code cannot be inspected from this evidence."
          : "Some execution detail was not retained. Opening the service can show the evidence that remains, not recover omissions.",
        missing ? null : target(segment));
      else if (flag(field(segment, "overviewTruncated")) || flag(field(segment, "overviewMetadataTruncated")) || flag(field(segment, "httpCallsTruncated"))
          || flag(field(segment, "externalCallsTruncated"))) add(4, "overview-limited", `${name(segment)}: overview is limited`,
        "The loaded request summary does not include every observation. Inspect service details and their reported limits.", target(segment));
      const mode = ["overview", "methods", "detailed"].includes(field(segment, "captureMode")) ? field(segment, "captureMode") : null;
      const codeDetail = field(segment, "codeDetailAvailable");
      if (mode || flag(codeDetail) || absent(codeDetail) || missing || truncated) {
        const notes = [];
        if (mode) notes.push("Reported capture: " + {overview: "Overview", methods: "Methods", detailed: "Detailed"}[mode]);
        if (missing) notes.push("execution details unavailable");
        else if (absent(codeDetail)) notes.push("method / line detail unavailable");
        else if (mode === "methods") notes.push("line events excluded by this mode");
        else if (mode === "overview") notes.push("method / line availability is not established by this mode alone");
        if (truncated) notes.push("detail incomplete");
        if (!mode && flag(codeDetail)) notes.push("code detail reported available; capture mode unknown");
        captures.push({service: name(segment), detail: notes.join(" · "), segment: missing ? null : target(segment)});
      }
    }
    const graph = object(doc.visualization), foreignGraph = key(graph.traceId) && key(doc.traceId) && graph.traceId !== doc.traceId;
    const graphNodes = foreignGraph ? [] : take(array(graph.nodes), LIMITS.nodes), byCall = new Map();
    if (foreignGraph && array(graph.nodes).length) unattributed++;
    for (const node of graphNodes) if (key(object(node).callKey)) byCall.set(node.callKey, byCall.has(node.callKey) ? null : node);
    const owner = call => {
      if (!completeSegments || key(call.traceId) && key(doc.traceId) && call.traceId !== doc.traceId) return null;
      const segment = uniqueKeys.get(call.ownerSegmentKey || call.segmentKey);
      if (!segment || !target(segment) || call.service !== segment.service || call.serviceInstanceId !== segment.serviceInstanceId
          || call.segmentId != null && call.segmentId !== segment.segmentId) return null;
      return segment;
    };
    const boundaryTarget = (call, segment, kind) => {
      if (array(graph.nodes).length > LIMITS.nodes || foreignGraph) return null;
      if (kind === "http-call" && (!Array.isArray(doc.httpCalls) || typeof call.spanId !== "string"
          || !/^[0-9a-f]{16}$/.test(call.spanId))) return null;
      const node = byCall.get(call.callKey);
      if (!node || node.kind !== kind || !sameSegment(node, segment) || node.segmentKey !== segment.segmentKey
          || node.status !== call.status || kind === "http-call" && node.httpStatus !== call.httpStatus) return null;
      const result = {...identity(segment), kind};
      for (const field of ["id", "callKey", "ownerId"]) {
        if (key(node[field])) result[field] = node[field];
        else if (node[field] != null) return null;
      }
      if (integer(node.eventSeq) > 0) result.eventSeq = integer(node.eventSeq);
      if (kind === "dependency-call") {
        if (!result.eventSeq || result.eventSeq !== integer(call.eventSeq)) return null;
        for (const field of ["boundaryKind", "evidenceKind", "status"]) {
          if (node[field] !== call[field]) return null;
          result[field] = call[field];
        }
      }
      return result;
    };
    const failures = new Map();
    const inspectCalls = (rows, http) => {
      const bounded = take(rows, LIMITS.calls), counts = new Map();
      for (const row of bounded) if (key(object(row).callKey)) counts.set(row.callKey, (counts.get(row.callKey) || 0) + 1);
      for (const raw of bounded) {
        const call = object(raw), boundaryKind = text(call.boundaryKind);
        if (!http && (!["database", "cache", "messaging", "external"].includes(boundaryKind)
            || !["client-boundary", "jdbc-execution", "clickhouse-query"].includes(call.evidenceKind))) continue;
        const reason = failure(call, http); if (!reason) continue;
        const segment = owner(call);
        if (!segment || !key(call.callKey) || counts.get(call.callKey) !== 1) { unattributed++; continue; }
        const groupKey = JSON.stringify([segment.segmentKey, http ? "http" : boundaryKind, reason]);
        const existing = failures.get(groupKey);
        if (existing) { existing.count++; existing.boundary = null; continue; }
        failures.set(groupKey, {segment, reason, count: 1, kind: http ? "outgoing HTTP" : boundaryKind,
          boundary: rows.length <= LIMITS.calls ? boundaryTarget(call, segment, http ? "http-call" : "dependency-call") : null});
      }
    };
    inspectCalls(Array.isArray(doc.httpCalls) ? doc.httpCalls : graphNodes.filter(node => object(node).kind === "http-call"), true);
    inspectCalls(Array.isArray(doc.externalCalls) ? doc.externalCalls : graphNodes.filter(node => object(node).kind === "dependency-call"), false);
    for (const value of failures.values()) add(2, "dependency-failure",
      `${name(value.segment)}: ${value.count === 1 ? "an" : value.count} ${value.kind} ${value.count === 1 ? "call reported" : "calls reported"} ${value.reason}`,
      "This is a caller-side observation, not proof of the dependency's internal execution or the request's final business outcome.",
      target(value.segment), value.boundary);
    if ((segments.length && entry.known === false) || doc.rootMissing === true) add(3, "entry-unknown",
      entry.gatewayMissing ? `${text(entry.gatewayMissing)} entry is not observed` : "Request entry is missing or ambiguous",
      "The displayed API is an observed segment, not an established request entry. Follow the available service evidence without inferring the missing path.");
    else if (entry.known === true && !root) add(3, "entry-unconfirmed", "Request entry could not be confirmed",
      "The supplied entry does not uniquely match the loaded segment identities. No downstream ordering is inferred here.");
    if (Array.isArray(doc.segments) && !segments.length) add(3, "no-segments", "No service segments are available",
      "There is no loaded service evidence to inspect for this request.");
    const errors = take(array(doc.errors), LIMITS.segments);
    const unavailableServices = new Set(errors.map(error => text(object(error).service)).filter(Boolean));
    for (const service of unavailableServices) add(3, "service-unavailable", `${service}: some evidence could not be loaded`,
      "The request lookup reported unavailable evidence. Its cause and the unobserved execution remain unknown.");
    if ((doc.partial === true || doc.limited === true) && !items.some(item => item.priority >= 3)) add(4, "partial-request", "Request evidence is partial or limited",
      "Open Request details or the diagram's Partial evidence notes for the reported limitations.");
    if (unattributed) add(3, "unattributed-evidence", "Some observations cannot be attributed safely",
      "Conflicting or missing segment identities prevent a reliable service link. No similarly named service was substituted.");
    items.sort((a, b) => a.priority - b.priority || a.order - b.order);
    const omittedItems = Math.max(0, items.length - LIMITS.items), omittedCaptures = Math.max(0, captures.length - LIMITS.captures);
    return {items: items.slice(0, LIMITS.items).map(({priority, order, ...item}) => item), captures: captures.slice(0, LIMITS.captures),
      segmentsInspected: segments.length, omittedInputs, omittedItems, omittedCaptures,
      limited: omittedInputs > 0 || omittedItems > 0 || omittedCaptures > 0};
  }
  function render(container, request, options = {}) {
    if (!container?.ownerDocument) throw Error("Request insights needs a container.");
    const summary = summarize(request, options), doc = container.ownerDocument, listeners = [];
    let disposed = false;
    const el = (tag, value, cls) => { const node = doc.createElement(tag); if (value) node.textContent = value; if (cls) node.className = cls; return node; };
    const section = el("section", null, "request-insights"); section.setAttribute("aria-label", "Request evidence to inspect");
    const feedback = el("p", null, "request-insights-feedback"); feedback.setAttribute("role", "status"); feedback.hidden = true;
    const action = (parent, item) => {
      const boundary = item.boundary && typeof options.onOpenBoundary === "function";
      const callback = boundary ? options.onOpenBoundary : options.onOpenSegment, target = boundary ? item.boundary : item.segment;
      if (!target || typeof callback !== "function") return;
      const button = el("button", boundary ? "Inspect calling evidence" : `Inspect ${text(target.service) || "service"}`, "request-insights-action");
      button.type = "button"; button.setAttribute("aria-label", (boundary ? "Inspect calling evidence: " : "Inspect service evidence: ") + item.title);
      const click = async () => {
        if (disposed || button.disabled) return;
        button.disabled = true; feedback.hidden = true;
        try { await callback({...target}); }
        catch (_) { if (!disposed) { feedback.textContent = "This evidence could not be opened. The request summary is unchanged; no other service was substituted."; feedback.hidden = false; } }
        finally { if (!disposed) button.disabled = false; }
      };
      button.addEventListener("click", click); listeners.push(() => button.removeEventListener("click", click)); parent.append(button);
    };
    const row = item => {
      const node = el("li", null, "request-insights-item"); node.append(el("strong", item.title), el("p", item.detail)); action(node, item); return node;
    };
    if (summary.items.length) {
      section.append(el("h2", "Evidence to inspect"));
      const list = el("ul"); summary.items.slice(0, LIMITS.visible).forEach(item => list.append(row(item))); section.append(list);
      if (summary.items.length > LIMITS.visible) {
        const details = el("details", null, "request-insights-more"), more = el("ul");
        details.append(el("summary", `${summary.items.length - LIMITS.visible} more observations`));
        summary.items.slice(LIMITS.visible).forEach(item => more.append(row(item))); details.append(more); section.append(details);
      }
    }
    if (summary.captures.length) {
      const details = el("details", null, "request-insights-capture"); details.append(el("summary", "Capture detail by service"));
      const list = el("ul");
      for (const capture of summary.captures) { const row = el("li"); row.append(el("strong", capture.service), el("span", " — " + capture.detail)); list.append(row); }
      details.append(list); section.append(details);
    }
    if (summary.limited) section.append(el("p", "This compact summary is bounded; additional observations or capture details are not listed here. Inspect the request flow for more evidence.", "request-insights-limit"));
    const visible = summary.items.length > 0 || summary.captures.length > 0 || summary.limited;
    if (visible) section.append(feedback);
    container.replaceChildren(...(visible ? [section] : []));
    container.hidden = !visible;
    return {summary, dispose() { if (disposed) return; disposed = true; listeners.splice(0).forEach(remove => remove()); section.remove(); }};
  }
  return Object.freeze({summarize, render, LIMITS});
});
