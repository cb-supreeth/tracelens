/* Metadata-only dependency summaries. No request replay or inferred remote work. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensDependencies = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const text = value => typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ").slice(0, 256) : "";
  // Keep raw-event projection aligned with dependencies.py. Clipping free text
  // is not a substitute for rejecting payload-like metadata or URL credentials.
  const token = (value, pattern = /^[A-Za-z0-9_$][A-Za-z0-9_.$:-]{0,127}$/) =>
    typeof value === "string" && value.length <= 128 && !/[^\x20-\x7e]/.test(value) && pattern.test(value) ? value : null;
  const operation = value => token(value, /^[A-Za-z_$][A-Za-z0-9_.$:-]{0,127}$/);
  const displayName = value => token(value, /^[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}$/);
  function destination(value) {
    if (typeof value !== "string" || !value || value.length > 2048 || /[^\x21-\x7e]/.test(value)) return null;
    if (value.includes("://")) {
      const parsed = /^(https?):\/\/([^/?#]*)([^?#]*)(?:\?[^#]*)?(?:#.*)?$/i.exec(value);
      if (!parsed) return null;
      const authority = parsed[2].slice(parsed[2].lastIndexOf("@") + 1);
      const hostPort = authority.startsWith("[") ? /^(\[[^\]]+\])(?::([0-9]*))?$/.exec(authority)
        : /^([^:]+)(?::([0-9]*))?$/.exec(authority);
      if (!hostPort) return null;
      if (authority.startsWith("[")) {
        try { new URL(parsed[1] + "://" + authority); } catch (_) { return null; }
      }
      const port = hostPort[2] ? Number(hostPort[2]) : null;
      if (port !== null && (!Number.isInteger(port) || port > 65535)) return null;
      value = parsed[1].toLowerCase() + "://" + hostPort[1].toLowerCase() +
        (port === null ? "" : ":" + port) + parsed[3];
    }
    return value.length <= 512 && /^[A-Za-z0-9_./:$\[\]-]+$/.test(value) ? value : null;
  }
  function boundaryMetadata(event) {
    if (!event || !["EXTERNAL_CALL", "DB_ROUTE"].includes(event.type)) return null;
    const a = event.attributes && typeof event.attributes === "object" && !Array.isArray(event.attributes) ? event.attributes : {};
    if (event.type === "DB_ROUTE") return {boundaryKind: "database-route", system: "database", direction: "outbound",
      destination: destination(a.route), operation: "route", status: "OBSERVED", evidenceKind: "routing-marker"};
    if (!Object.prototype.hasOwnProperty.call(a, "boundaryKind")) return {boundaryKind: "external", system: token(a.system),
      direction: "outbound", destination: null, operation: operation(a.detail), status: "OBSERVED", evidenceKind: "wrapper-entry"};
    return {boundaryKind: ["messaging", "external"].includes(a.boundaryKind) ? a.boundaryKind : "external",
      system: token(a.system), direction: ["publish", "consume", "outbound"].includes(a.direction) ? a.direction : null,
      destination: destination(a.destination), operation: operation(a.operation),
      status: ["ATTEMPTED", "SUBMITTED", "OBSERVED", "ERROR"].includes(a.status) ? a.status : null,
      evidenceKind: ["client-boundary", "wrapper-entry"].includes(a.evidenceKind) ? a.evidenceKind : null};
  }
  function boundaryView(boundary, alias) {
    const messaging = boundary.boundaryKind === "messaging", database = boundary.boundaryKind === "database-route";
    const client = boundary.evidenceKind === "client-boundary", wrapper = boundary.evidenceKind === "wrapper-entry";
    return {kind: database ? "DB ROUTE" : messaging ? boundary.direction === "consume" ? "QUEUE CONSUME"
      : boundary.direction === "publish" ? "QUEUE PUBLISH" : "MESSAGING BOUNDARY" : wrapper ? "EXTERNAL WRAPPER" : "EXTERNAL BOUNDARY",
      direction: boundary.direction, evidenceKind: boundary.evidenceKind,
      destination: [database ? null : boundary.system, displayName(alias) || boundary.destination].filter(Boolean).join(" · ") || (database ? "Unknown route" : "Unknown dependency"),
      operation: database ? "Routing decision" : boundary.operation || (wrapper ? "Wrapper entry" : "Operation unknown"),
      status: boundary.status || "Unknown",
      evidence: database ? "Pool selection only; no query or network success implied"
        : client ? boundary.direction === "consume" ? "Local consume boundary; message receipt, producer and processing completion are not inferred"
          : "Observed client boundary; delivery and remote processing not implied"
        : wrapper ? "Configured wrapper entry; a network operation is not confirmed"
          : "Boundary recorded; evidence kind unknown; a network operation is not confirmed"};
  }
  function eventBoundary(event) {
    const boundary = boundaryMetadata(event);
    return boundary ? boundaryView(boundary) : null;
  }
  function summaryBoundary(row) {
    row = row && typeof row === "object" && !Array.isArray(row) ? row : {};
    const database = row.boundaryKind === "database-route";
    const attributes = database ? {route: row.destination} : Object.fromEntries(
      ["boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind"].map(key => [key, row[key]]));
    return boundaryView(boundaryMetadata({type: database ? "DB_ROUTE" : "EXTERNAL_CALL", attributes}), row.destinationName);
  }
  function collect(trace, maxRows = 200) {
    trace = trace && typeof trace === "object" ? trace : {};
    const limit = Math.min(500, Math.max(1, Number.isInteger(maxRows) ? maxRows : 200));
    const rows = [];
    let total = 0;
    const add = row => { total++; if (rows.length < limit) rows.push(row); };
    const calls = Array.isArray(trace.httpCalls) ? trace.httpCalls : [];
    for (const call of calls.slice(0, 5000)) {
      if (!call || typeof call !== "object") continue;
      add({kind: "HTTP OUT", destination: text(call.destinationName) || text(call.peer) || "Unknown peer",
        operation: [text(call.httpMethod), text(call.path)].filter(Boolean).join(" "),
        status: [text(call.status), /^\d{3}$/.test(String(call.httpStatus)) ? "HTTP " + call.httpStatus : ""].filter(Boolean).join(" · ") || "Unknown",
        evidence: "Caller-side observation; remote execution requires a linked service segment", propagation: text(call.propagationStatus)});
    }
    const events = Array.isArray(trace.events) ? trace.events : [];
    for (const event of events.slice(0, 100000)) {
      const boundary = eventBoundary(event);
      if (boundary) add(boundary);
    }
    return {rows, total, omitted: total - rows.length, scanLimited: calls.length > 5000 || events.length > 100000};
  }
  return Object.freeze({collect, eventBoundary, summaryBoundary, boundaryMetadata});
});
