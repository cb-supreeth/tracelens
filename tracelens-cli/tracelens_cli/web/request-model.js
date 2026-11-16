/* Request discovery model, shared by the buildless UI and Node's built-in tests.
 * Grouping is causal only when a valid shared trace id exists. Buckets are merely
 * a presentation convenience: they never combine distinct requests or events.
 */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensRequests = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function defaults() {
    return { query: "", path: "", user: "", site: "", service: "", method: "",
      channel: "HTTP", sinceMinutes: "30", outcome: "", minDuration: "", maxDuration: "", collapse: true };
  }

  function object(value) { return value && typeof value === "object" && !Array.isArray(value) ? value : {}; }
  function text(value) { return typeof value === "string" ? value.trim() : ""; }
  function scalar(value) { return typeof value === "number" && Number.isFinite(value) ? String(value) : text(value); }
  function firstText(...values) { return values.map(text).find(Boolean) || ""; }
  function firstScalar(...values) { return values.map(scalar).find(Boolean) || ""; }
  function trueFlag(value) { return value === true || text(value).toLowerCase() === "true"; }
  function numeric(value) {
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (typeof value !== "string" || !/^\d+(?:\.\d+)?$/.test(value.trim())) return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }
  function firstNumber(...values) {
    for (const value of values) { const number = numeric(value); if (number !== null && number >= 0) return number; }
    return null;
  }
  function validTraceId(value) {
    const id = text(value).toLowerCase();
    if (/^[0-9a-f]{32}$/.test(id) && !/^0+$/.test(id)) return id;
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)
        && !/^0+$/.test(id.replace(/-/g, ""))) return id;
    return null;
  }
  function parentValue(row, attrs, name) {
    const value = row[name] == null || row[name] === "" ? attrs[name] : row[name];
    // A malformed parent is not evidence that this was a root request.
    return value == null || value === "" ? "" : (text(value) || "[unknown parent]");
  }
  function normalize(row) {
    const attrs = object(row.attributes);
    const status = firstText(row.status, attrs.status).toUpperCase();
    const code = firstNumber(row.httpStatus, attrs.httpStatus);
    const httpStatus = code !== null && Number.isInteger(code) && code >= 100 && code <= 599 ? code : null;
    const started = firstNumber(row.startedAtMillis, row.startedAt, attrs.startedAtMillis, attrs.startedAt);
    const filterException = firstText(row.filterException, attrs.filterException);
    const boundaryException = !!filterException && filterException.toLowerCase() !== "false";
    const asyncTimeout = trueFlag(row.asyncTimeout) || trueFlag(attrs.asyncTimeout);
    const exceptions = firstNumber(row.exceptionCount, attrs.exceptionCount) || 0;
    const channel = firstText(row.entryChannel, row.channel, attrs.entryChannel).toUpperCase() || "UNKNOWN";
    const jobOutcome = firstText(row.jobOutcome, attrs.jobOutcome).toUpperCase() || null;
    // A caught exception may be normal recovery. Even filterException can be
    // captured at a nested boundary before an outer layer recovers with HTTP200.
    // Only explicit timeout/failure or HTTP error status establish failure.
    const hasErrors = httpStatus >= 400 || status === "FAILED" || status === "ERROR" || row.hasErrors === true
      || asyncTimeout || (channel === "SCHEDULER" && jobOutcome === "FAILED");
    const hasExceptions = row.hasExceptions === true || exceptions > 0 || boundaryException
      || (Array.isArray(row.events) && row.events.some((event) => object(event).type === "EXCEPTION"));
    return { ...row, attributes: attrs, traceId: validTraceId(row.traceId),
      service: firstText(row.service, row.applicationId, attrs.applicationId) || "unknown",
      serviceInstanceId: firstText(row.serviceInstanceId, attrs.serviceInstanceId),
      segmentId: firstText(row.segmentId),
      method: firstText(row.httpMethod, row.method, attrs.httpMethod, attrs.method).toUpperCase(),
      path: firstText(row.path, attrs.path),
      routeTemplate: firstText(row.routeTemplate, row.httpRoute, row.httpRouteTemplate,
        attrs.routeTemplate, attrs.httpRoute, attrs.httpRouteTemplate, attrs["http.route"], attrs.route),
      user: firstText(row.user, attrs.user),
      site: firstScalar(row.siteId, row.site, attrs.siteId, attrs.site),
      channel, jobOutcome,
      jobType: firstText(row.jobType, attrs.jobType) || null,
      jobId: firstScalar(row.jobId, attrs.jobId) || null,
      jobAuditStatus: firstText(row.jobAuditStatus, attrs.jobAuditStatus).toUpperCase() || null,
      jobFailureType: firstText(row.jobFailureType, attrs.jobFailureType) || null,
      scheduler: firstText(row.scheduler, attrs.scheduler) || null,
      parentSpanId: parentValue(row, attrs, "parentSpanId"),
      parentSegmentId: parentValue(row, attrs, "parentSegmentId"),
      label: firstText(row.lensId, row.label, attrs.lensId), status, httpStatus, hasErrors, hasExceptions,
      filterException, asyncTimeout,
      startedAtMillis: started,
      lastActivityMillis: firstNumber(row.lastActivityMillis, attrs.lastActivityMillis, row.endedAt, started),
      durationMs: firstNumber(row.durationMs, attrs.durationMs),
      eventCount: firstNumber(row.eventCount) || 0 };
  }
  function segmentOrder(a, b) {
    return (a.startedAtMillis ?? Infinity) - (b.startedAtMillis ?? Infinity)
      || a.service.localeCompare(b.service) || a.serviceInstanceId.localeCompare(b.serviceInstanceId)
      || a.segmentId.localeCompare(b.segmentId);
  }
  function requestOrder(a, b) {
    return (b.startedAtMillis ?? -Infinity) - (a.startedAtMillis ?? -Infinity) || a.key.localeCompare(b.key);
  }

  function groupTraces(rows) {
    const groups = new Map();
    const fallbacks = new Map();
    for (const row of Array.isArray(rows) ? rows : []) {
      if (!row || typeof row !== "object" || Array.isArray(row)) continue;
      const segment = normalize(row);
      let key;
      if (segment.traceId) key = "trace:" + segment.traceId;
      else {
        // Missing/invalid trace ids cannot establish causality. Even duplicate
        // unidentified rows stay separate instead of silently hiding evidence.
        const base = "segment:" + JSON.stringify([segment.service, segment.serviceInstanceId, segment.segmentId]);
        const occurrence = fallbacks.get(base) || 0;
        fallbacks.set(base, occurrence + 1);
        key = base + (occurrence ? ":observation:" + occurrence : "");
      }
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(segment);
    }
    return [...groups].map(([key, segments]) => {
      segments.sort(segmentOrder);
      const roots = segments.filter((segment) => !segment.parentSpanId && !segment.parentSegmentId);
      const rootMissing = roots.length !== 1;
      const root = roots.length === 1 ? roots[0] : (roots[0] || segments[0]);
      const hasErrors = segments.some((segment) => segment.hasErrors);
      const active = segments.some((segment) => segment.status === "ACTIVE"
        || (segment.channel === "SCHEDULER" && segment.jobOutcome === "RUNNING"));
      // Success requires affirmative root evidence. Scheduler lifecycle labels
      // (COMPLETED/EXPORTED) and exception events do not establish job outcomes.
      const job = root.channel === "SCHEDULER";
      const successful = !rootMissing && (job ? root.jobOutcome === "SUCCEEDED"
        : root.httpStatus >= 200 && root.httpStatus < 400);
      const skipped = !rootMissing && job && root.jobOutcome === "SKIPPED";
      const observedStarts = segments.map((segment) => segment.startedAtMillis).filter((n) => n !== null);
      const activity = segments.map((segment) => segment.lastActivityMillis).filter((n) => n !== null);
      return { key, traceId: segments[0].traceId, segments, root, rootMissing, rootAmbiguous: roots.length > 1,
        label: root.label || (job && root.jobType) || [root.method, root.path].filter(Boolean).join(" ") || root.channel + " request",
        method: root.method, path: root.path, user: root.user, site: root.site,
        jobType: root.jobType, jobId: root.jobId, jobOutcome: rootMissing ? null : root.jobOutcome,
        jobAuditStatus: root.jobAuditStatus, jobFailureType: root.jobFailureType, scheduler: root.scheduler,
        services: [...new Set(segments.map((segment) => segment.service))].sort(), channel: root.channel,
        startedAtMillis: !rootMissing && root.startedAtMillis !== null ? root.startedAtMillis
          : (observedStarts.length ? Math.min(...observedStarts) : null),
        lastActivityMillis: activity.length ? Math.max(...activity) : null,
        durationMs: rootMissing ? null : root.durationMs,
        httpStatus: rootMissing ? null : root.httpStatus,
        outcome: hasErrors ? "error" : active ? "active" : successful ? "success" : skipped ? "skipped" : "unknown",
        hasErrors, hasExceptions: segments.some((segment) => segment.hasExceptions),
        eventCount: segments.reduce((sum, segment) => sum + segment.eventCount, 0) };
    }).sort(requestOrder);
  }

  function includes(value, query) { return String(value || "").toLowerCase().includes(query.toLowerCase()); }
  function numberFilter(value) { return value == null || value === "" ? { value: null } : { value: numeric(value), required: true }; }

  function filterRequests(requests, suppliedFilters, now) {
    const filters = { ...defaults(), ...object(suppliedFilters) };
    const clock = firstNumber(now) ?? Date.now();
    const since = numberFilter(filters.sinceMinutes), min = numberFilter(filters.minDuration), max = numberFilter(filters.maxDuration);
    if ([since, min, max].some((filter) => filter.required && filter.value === null)
        || (min.value !== null && max.value !== null && min.value > max.value)) return [];
    const query = text(filters.query), path = text(filters.path), user = text(filters.user), site = scalar(filters.site);
    const service = text(filters.service), method = text(filters.method).toUpperCase(), channel = text(filters.channel).toUpperCase();
    const outcome = text(filters.outcome).toLowerCase();
    return (Array.isArray(requests) ? requests : []).filter((request) => {
      if (!request || !Array.isArray(request.segments)) return false;
      if (channel && request.channel !== channel) return false;
      if (outcome && request.outcome !== outcome) return false;
      if (since.value !== null && (request.startedAtMillis === null || request.startedAtMillis < clock - since.value * 60000)) return false;
      if (min.value !== null && (request.durationMs === null || request.durationMs < min.value)) return false;
      if (max.value !== null && (request.durationMs === null || request.durationMs > max.value)) return false;
      // An EMS path must not match the cb-app user's row merely because they are
      // in one trace. All structured segment selectors must match a single row.
      if (!request.segments.some((segment) => (!service || segment.service === service)
          && (!method || segment.method === method)
          && (!path || includes(segment.path, path) || includes(segment.routeTemplate, path))
          && (!user || includes(segment.user, user)) && (!site || segment.site === site))) return false;
      if (query && ![request.traceId, request.label, ...request.segments.flatMap((segment) =>
        [segment.segmentId, segment.service, segment.serviceInstanceId, segment.method, segment.path,
          segment.routeTemplate, segment.label, segment.user, segment.site, segment.jobType, segment.jobId,
          segment.jobAuditStatus, segment.jobFailureType, segment.scheduler])]
        .some((value) => includes(value, query))) return false;
      return true;
    }).sort(requestOrder);
  }

  // Summary-only badge projection. Match error-groups' failing-segment filters,
  // not the ordinary list's observed-service/root-channel selectors. The open
  // Errors tab still uses its server response as the authoritative count.
  function filterErrorRequests(requests, suppliedFilters, suppliedErrors, now) {
    const filters = {...defaults(), ...object(suppliedFilters)};
    const errors = object(suppliedErrors);
    const status = numberFilter(errors.httpStatus);
    if (status.required && (!Number.isInteger(status.value) || status.value < 100 || status.value > 599)) return [];
    const service = text(filters.service), method = text(filters.method).toUpperCase();
    const path = text(filters.path), channel = text(filters.channel).toUpperCase(), query = text(filters.query);
    const category = text(errors.category), exception = text(errors.exceptionClass);
    const className = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/;
    const candidates = filterRequests(requests, {...filters, service: "", method: "", path: "", channel: "", outcome: "", query: ""}, now);
    return candidates.filter(request => {
      if (query && ![request.traceId, request.label, ...request.segments.flatMap(segment =>
        [segment.segmentId, segment.service, segment.serviceInstanceId, segment.method, segment.path,
          segment.routeTemplate, segment.label, segment.user, segment.site])].some(value => includes(value, query))) return false;
      return request.segments.some(segment => {
        const kind = segment.asyncTimeout ? "timeout" : segment.httpStatus >= 500 ? "http5xx"
          : segment.httpStatus >= 400 ? "http4xx" : segment.hasErrors ? "execution" : "";
        const boundary = text(segment.filterException);
        const boundaryClass = !["true", "false", "null", "none"].includes(boundary.toLowerCase()) && className.test(boundary) ? boundary : "";
        const failure = text(segment.jobFailureType);
        const exceptionClass = boundaryClass || (segment.channel === "SCHEDULER" && segment.jobOutcome === "FAILED" && className.test(failure) ? failure : "");
        return kind && (!service || segment.service === service) && (!method || segment.method === method)
          && (!channel || segment.channel === channel)
          && (!path || includes(segment.path, path) || includes(segment.routeTemplate, path))
          && (!category || category === "all" || kind === category)
          && (!status.required || segment.httpStatus === status.value)
          && (!exception || includes(exceptionClass, exception));
      });
    });
  }

  function bucketRequests(requests, collapse) {
    const buckets = new Map();
    for (const request of Array.isArray(requests) ? requests : []) {
      if (!request || !request.root) continue;
      const root = request.root;
      const route = root.routeTemplate || root.path;
      // Jobs often have no HTTP path. Their captured labels identify display
      // buckets; distinct job labels must not disappear into one blank route.
      const bucketIdentity = route || root.label || request.label;
      // No guessed ID stripping: /features/123 and /features/456 are distinct
      // unless an actual route template was captured by the application.
      const key = collapse ? "endpoint:" + JSON.stringify([root.service, root.method,
        root.routeTemplate ? "template" : root.path ? "literal" : "label",
        bucketIdentity, request.channel, request.rootMissing]) : "request:" + request.key;
      if (!buckets.has(key)) buckets.set(key, { key,
        label: collapse ? [root.service, root.method, route || request.label, request.channel].filter(Boolean).join(" · ") : request.label,
        requests: [], latestMillis: null });
      const bucket = buckets.get(key);
      bucket.requests.push(request);
      if (request.startedAtMillis !== null && (bucket.latestMillis === null || request.startedAtMillis > bucket.latestMillis)) {
        bucket.latestMillis = request.startedAtMillis;
      }
    }
    for (const bucket of buckets.values()) bucket.requests.sort(requestOrder);
    return [...buckets.values()].sort((a, b) => (b.latestMillis ?? -Infinity) - (a.latestMillis ?? -Infinity) || a.key.localeCompare(b.key));
  }

  return { defaults, groupTraces, filterRequests, filterErrorRequests, bucketRequests };
});
