/* Read-only HTTP diagnosis and explicitly armed, deadline-bounded capture. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensGuidedInvestigation = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const MAX_ROWS = 1000, POLL_MS = 1000, TRACE_ID = /^[A-Za-z0-9_-]{1,128}$/;
  const obj = v => v && typeof v === "object" && !Array.isArray(v) ? v : {};
  const text = (...values) => values.find(v => typeof v === "string" && v.trim())?.trim() || "";
  const scalar = (...values) => values.map(v => typeof v === "number" && Number.isFinite(v) ? String(v) : v)
    .find(v => typeof v === "string" && v.trim())?.trim() || "";
  const number = (...values) => values.find(v => typeof v === "number" && Number.isFinite(v) && v >= 0) ?? null;
  function validate(input) {
    const result = {};
    for (const [key, limit] of [["path", 2048], ["method", 32], ["service", 64], ["user", 256], ["site", 256]]) {
      const value = input[key] ?? "";
      if (typeof value !== "string" || value.length > limit || /[\p{C}]/u.test(value)) throw new Error("Invalid " + key + " filter.");
      result[key] = value.trim();
    }
    const path = result.path;
    if (!path.startsWith("/") || path.startsWith("//") || /[\s?#*\\,[\]{}]|[^\x21-\x7e]/.test(path)
        || /%(?![\da-f]{2})/i.test(path)) throw new Error("Use an exact backend path, without a URL, query, fragment or wildcards.");
    try { if (/[\p{C}]/u.test(decodeURIComponent(path))) throw new Error(); }
    catch (_) { throw new Error("The backend path has invalid encoding or controls."); }
    result.method = result.method.toUpperCase();
    if (result.method && !/^[A-Z][A-Z0-9_-]{0,31}$/.test(result.method)) throw new Error("Use one HTTP method token.");
    return result;
  }
  function recoveryDraft(filters, context) {
    filters = obj(filters); context = obj(context);
    if (context.mode !== "live" || context.offline || context.saved
        || !["", "HTTP"].includes(String(filters.channel ?? "HTTP").toUpperCase())) return null;
    const bounded = (value, limit) => typeof value === "string" && value.length <= limit && !/[\p{C}]/u.test(value) ? value.trim() : "";
    const method = bounded(filters.method, 32).toUpperCase();
    return {source: "empty-requests", filters: {
      service: bounded(filters.service, 64), method: /^[A-Z][A-Z0-9_-]{0,31}$/.test(method) ? method : "",
      site: bounded(typeof filters.site === "number" && Number.isFinite(filters.site) ? String(filters.site) : filters.site, 256)},
      pathSuggestion: bounded(filters.path, 2048), userSuggestion: bounded(filters.user, 256)};
  }
  function segment(row) {
    const a = obj(row.attributes);
    return {...row, service: text(row.service, row.applicationId, a.applicationId),
      serviceInstanceId: text(row.serviceInstanceId, a.serviceInstanceId), segmentId: text(row.segmentId),
      traceId: row.traceId, method: text(row.httpMethod, row.method, a.httpMethod, a.method).toUpperCase(),
      path: text(row.path, a.path), user: text(row.user, a.user), site: scalar(row.siteId, row.site, a.siteId, a.site),
      channel: text(row.entryChannel, row.channel, a.entryChannel).toUpperCase(),
      status: text(row.status, a.status).toUpperCase(),
      parentSpanId: row.parentSpanId ?? a.parentSpanId, parentSegmentId: row.parentSegmentId ?? a.parentSegmentId,
      startedAtMillis: number(row.startedAtMillis, row.startedAt, a.startedAtMillis),
      httpStatus: number(row.httpStatus, a.httpStatus), durationMs: number(row.durationMs, a.durationMs)};
  }
  const identity = s => JSON.stringify([s.service, s.serviceInstanceId, s.segmentId]);
  function summaries(doc) {
    if (!doc || !Array.isArray(doc.traces) || doc.traces.length > MAX_ROWS || doc.partial || doc.limited
        || (Array.isArray(doc.errors) && doc.errors.length)) throw new Error("Summary discovery is incomplete or limited. Narrow the configured service scope or retry; nothing was saved.");
    const grouped = new Map(), identities = new Map();
    for (const raw of doc.traces) {
      if (!raw || typeof raw !== "object" || Array.isArray(raw) || typeof raw.traceId !== "string" || !TRACE_ID.test(raw.traceId)) {
        throw new Error("A summary has no reliable request identity; automatic saving is stopped.");
      }
      const s = segment(raw), key = identity(s);
      if (!s.segmentId || !s.service || !s.serviceInstanceId) throw new Error("A summary lacks segment ownership; automatic saving is stopped.");
      const signature = JSON.stringify(s);
      if (identities.has(key)) {
        if (identities.get(key) !== signature) throw new Error("Conflicting segment identities were observed; automatic saving is stopped.");
        continue;
      }
      identities.set(key, signature);
      if (!grouped.has(s.traceId)) grouped.set(s.traceId, []);
      grouped.get(s.traceId).push(s);
    }
    return [...grouped].map(([traceId, segments]) => {
      const roots = segments.filter(s => !s.parentSpanId && !s.parentSegmentId);
      const root = roots.length === 1 ? roots[0] : null;
      return {key: "trace:" + traceId, traceId, segments, root, rootMissing: !root, rootAmbiguous: roots.length > 1,
        method: root?.method || "", path: root?.path || "", label: root ? [root.method, root.path].join(" ") : traceId,
        services: [...new Set(segments.map(s => s.service))], startedAtMillis: root?.startedAtMillis ?? null,
        durationMs: root?.durationMs ?? null, httpStatus: root?.httpStatus ?? null,
        outcome: segments.some(s => s.status === "ACTIVE") ? "active" : "unknown"};
    });
  }
  function matches(request, filters) {
    return request.segments.some(s => s.channel === "HTTP" && s.path === filters.path
      && ["method", "service", "user", "site"].every(key => !filters[key] || s[key] === filters[key]));
  }
  function completed(request) {
    return !!request.root && request.segments.every(s => ["COMPLETED", "EXPORTED", "FAILED", "ERROR"].includes(s.status));
  }
  function requestIdentity(request) {
    return JSON.stringify([request.traceId, request.root && identity(request.root), request.startedAtMillis]);
  }
  const CHECK_LABELS = {reachability: "Agent connection", metadata: "Running settings", agent: "Agent enabled",
    recording: "Recording enabled", http_path: "API path included", labels: "Request labels", scope: "Code detail coverage", retention: "Retained evidence"};
  function readiness(doc, services) {
    doc = obj(doc);
    const checks = Array.isArray(doc.checks) ? doc.checks.slice(0, 288).map(obj) : [];
    const required = Object.keys(CHECK_LABELS), limitations = ["scope", "retention"];
    const action = checks.some(check => ["fail", "excluded", "warn"].includes(check.status));
    const incomplete = !services.length || doc.partial !== false || doc.limited !== false || doc.checks?.length > 288
      || (Array.isArray(doc.warnings) && doc.warnings.length > 0) || (Array.isArray(doc.errors) && doc.errors.length > 0)
      || services.some(service => required.some(id => checks.filter(check => check.service === service && check.id === id).length !== 1))
      || checks.some(check => !services.includes(check.service) || !required.includes(check.id) || !text(check.message)
        || (check.status !== "pass" && !(limitations.includes(check.id) && check.status === "unknown")));
    return {checks, state: action ? "action" : incomplete ? "unknown" : "ready",
      headline: action ? "Action required" : incomplete ? "Cannot verify" : "Ready to reproduce",
      next: action ? "Resolve the checks below, then check capture readiness again. Saving settings alone does not apply them to a running service."
        : incomplete ? "Check the unavailable or incomplete evidence below, then retry. No capture readiness is assumed."
          : "Next: choose Watch next request & save. Wait for Watching, then reproduce once using your normal application or API client."};
  }

  function render(container, options) {
    options = options || {};
    if (!container || typeof options.api !== "function") throw new Error("Investigation needs a container and API reader.");
    const document = options.document || container.ownerDocument || globalThis.document;
    const now = options.now || Date.now, later = options.setTimeout || setTimeout, clear = options.clearTimeout || clearTimeout;
    let version = 0, destroyed = false, timer = null, deadlineTimer = null, phase = "idle", reviewRequired = false;
    let serviceNames = [];
    const fields = {};
    function node(tag, content, className) {
      const value = document.createElement(tag);
      if (content !== undefined) value.textContent = content;
      if (className) value.className = className;
      return value;
    }
    const panel = node("section", undefined, "guided-investigation");
    const heading = node("h2", "Investigate a backend API request");
    const intro = node("p", "Check capture setup and look for existing traces for an exact backend API path. This does not diagnose application bugs. Use the actual API path, not a browser page route. Filters match exactly; nothing enables recording or changes agent settings.");
    const form = node("form", undefined, "guided-form");
    for (const [key, label, placeholder] of [["path", "Backend path", "/api/orders/123"], ["method", "HTTP method (optional)", "GET"],
      ["service", "Service (optional)", "All configured services"], ["user", "User (optional)", "Exact captured user"],
      ["site", "Site (optional)", "Exact captured site ID"]]) {
      const wrapper = node("label", label), input = node(key === "service" ? "select" : "input");
      input.name = key; input.maxLength = key === "path" ? 2048 : key === "method" ? 32 : key === "service" ? 64 : 256;
      input.placeholder = placeholder; input.value = ""; input.setAttribute("aria-label", label);
      if (key === "path") input.required = true;
      fields[key] = input; wrapper.append(input); form.append(wrapper);
    }
    const recovery = node("div", undefined, "guided-recovery-review"); recovery.hidden = true;
    const recoveryNotice = node("p"), confirmationLabel = node("label", undefined, "guided-exact-confirmation");
    const confirmation = node("input"); confirmation.type = "checkbox"; confirmation.checked = false;
    confirmation.setAttribute("aria-label", "I reviewed the exact backend path and matching filters");
    confirmationLabel.append(confirmation, node("span", "I reviewed the exact backend path and matching filters. This is not a substring, browser route or route template."));
    recovery.append(recoveryNotice, confirmationLabel); form.append(recovery);
    const buttons = node("div", undefined, "guided-actions"), diagnose = node("button", "Check capture readiness");
    const watch = node("button", "Watch next request & save"), cancel = node("button", "Cancel watch");
    const durationLabel = node("label", "Watch window"), duration = node("select");
    duration.setAttribute("aria-label", "Watch window");
    for (const seconds of [60, 120]) { const option = node("option", seconds + " seconds"); option.value = String(seconds); duration.append(option); }
    duration.value = "60"; durationLabel.append(duration);
    diagnose.type = "submit"; watch.type = "button"; cancel.type = "button"; cancel.disabled = true;
    buttons.append(diagnose, durationLabel, watch, cancel); form.append(buttons);
    const caveat = node("p", "Watch saves one completed, unambiguous new request only. Reproduce after “Watching” appears. Browser and service clocks must agree. Captures may be partial; review warnings before treating them as complete.", "guided-caveat");
    const status = node("p", "Ready to check capture readiness.", "guided-status"); status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite"); status.setAttribute("tabindex", "-1");
    const results = node("div", undefined, "guided-results");
    panel.append(heading, intro, form, caveat, status, results); container.replaceChildren(panel);
    function setServices(services, previous = fields.service.value) {
      serviceNames = [...new Set((Array.isArray(services) ? services : []).slice(0, 32)
        .filter(service => typeof service === "string" && service.length <= 64 && !/[\p{C}]/u.test(service)))];
      fields.service.replaceChildren();
      const all = node("option", "All configured services"); all.value = ""; fields.service.append(all);
      for (const service of serviceNames) {
        const option = node("option", service); option.value = service; fields.service.append(option);
      }
      if (previous && !serviceNames.includes(previous)) {
        const missing = node("option", "Not configured: " + previous); missing.value = previous; missing.disabled = true;
        fields.service.append(missing);
      }
      fields.service.value = previous;
    }
    setServices(options.services || []);
    function controls(busy) {
      for (const input of Object.values(fields)) input.disabled = busy;
      diagnose.disabled = busy; watch.disabled = busy || typeof options.saveTrace !== "function";
      duration.disabled = busy; confirmation.disabled = busy; cancel.disabled = !busy;
    }
    controls(false);
    function stop(message) {
      if (phase === "saving" && message && !/^Saved\b/.test(message)) {
        message = "Watch stopped. A save may already be in progress or committed; check Saved captures.";
      }
      version++;
      if (timer !== null) clear(timer);
      if (deadlineTimer !== null) clear(deadlineTimer);
      timer = deadlineTimer = null; phase = "idle";
      if (!destroyed) { controls(false); if (message) status.textContent = message; }
    }
    function current(token) { return !destroyed && token === version; }
    function input() {
      const filters = validate(Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, v.value])));
      if (filters.service && !serviceNames.includes(filters.service))
        throw new Error("The selected service is not configured here. Choose a configured service before checking or watching.");
      if (reviewRequired && !confirmation.checked) throw new Error("Review the exact backend path and matching filters, then confirm them before checking or watching.");
      return filters;
    }
    for (const input of Object.values(fields)) {
      input.oninput = input.onchange = () => { if (reviewRequired) confirmation.checked = false; };
    }
    function prepare(draft) {
      if (destroyed) return;
      draft = obj(draft); const values = obj(draft.filters);
      const bounded = (value, limit) => typeof value === "string" && value.length <= limit && !/[\p{C}]/u.test(value) ? value.trim() : "";
      const saving = phase === "saving";
      stop(); results.replaceChildren(); reviewRequired = true; recovery.hidden = false; confirmation.checked = false;
      fields.path.value = bounded(draft.pathSuggestion, 2048);
      fields.user.value = bounded(draft.userSuggestion, 256);
      fields.method.value = bounded(values.method, 32).toUpperCase();
      fields.site.value = bounded(values.site, 256);
      setServices(serviceNames, bounded(values.service, 64));
      recoveryNotice.textContent = draft.source === "empty-requests"
        ? "The request list's API and user filters match substrings. Any copied values are suggestions, not confirmed exact matches. Review or replace them below. Only exact service, method and site filters were carried over; time, outcome, duration, trace/label and request-data keyword filters were not."
        : "This setup handoff only opens the investigation form. Enter the exact backend API path and review the selected service; no readiness check or watch has run.";
      const action = draft.intent === "watch" ? watch : diagnose;
      status.textContent = (draft.intent === "watch" ? "Ready to prepare your next reproduction. " : "Ready to review capture readiness. ")
        + (fields.service.value && !serviceNames.includes(fields.service.value) ? "The selected service is not configured here. " : "")
        + "Confirm the exact path and filters, then choose the action. "
        + (saving ? "A previous save may already be in progress or committed; check Saved captures. No new action has run."
          : "No check or watch has run from this form preparation.");
      (fields.path.value ? action : fields.path).focus?.();
    }
    function candidates(rows) {
      if (!rows.length) return;
      const list = node("ul", undefined, "guided-candidates");
      for (const candidate of rows.slice(0, 20)) {
        const item = node("li"), label = [candidate.method, candidate.path, candidate.traceId].filter(Boolean).join(" · ");
        if (typeof options.onOpen === "function") {
          const open = node("button", label); open.type = "button";
          open.onclick = () => { stop(); Promise.resolve(options.onOpen(candidate.traceId)).catch(() => {
            if (!destroyed) status.textContent = "Could not open this retained request. It may no longer be available.";
          }); };
          item.append(open);
        } else item.textContent = label;
        list.append(item);
      }
      results.append(list);
    }
    function inputError(error) { status.textContent = error.message; status.focus?.(); }
    function renderReadiness(doc, filters) {
      const summary = readiness(doc, filters.service ? [filters.service] : serviceNames);
      const headline = node("h3", summary.headline, "guided-readiness-heading");
      headline.setAttribute("data-readiness", summary.state); results.append(headline, node("p", summary.next, "guided-next-action"));
      if (summary.state === "ready") results.append(node("p", "Running admission checks passed at this moment, not a capture guarantee. Built-in debug, health and static-resource exclusions may still apply. Code coverage and retained evidence remain unverified until you inspect a new capture.", "guided-caveat"));
      const passed = summary.checks.filter(check => check.status === "pass");
      const details = node("details", undefined, "guided-check-details");
      details.append(node("summary", passed.length + " passed checks · view details"));
      for (const check of summary.checks) {
        const row = node("div", undefined, "guided-check"), label = CHECK_LABELS[check.id] || "Capture check";
        row.setAttribute("data-status", String(check.status || "unknown"));
        row.append(node("strong", [text(check.service), label].filter(Boolean).join(" · ")), node("p", text(check.message) || "This check returned no explanation. Retry before relying on it."));
        const technical = node("details"); technical.append(node("summary", "Diagnostic identifiers"), node("code", [text(check.id), text(check.status)].filter(Boolean).join(" · ")));
        row.append(technical); (check.status === "pass" ? details : results).append(row);
      }
      if (passed.length) results.append(details);
      const guidance = (Array.isArray(doc.guidance) ? doc.guidance : []).slice(0, 12).filter(message => typeof message === "string");
      if (guidance.length) {
        const reference = node("details", undefined, "guided-check-details"); reference.append(node("summary", "How to interpret this check"));
        for (const message of guidance) reference.append(node("p", message));
        results.append(reference);
      }
      return summary;
    }
    async function runDiagnosis(event) {
      event?.preventDefault(); stop();
      let filters; try { filters = input(); } catch (error) { inputError(error); return; }
      const token = version; controls(true); phase = "diagnosing";
      status.textContent = "Checking capture setup and looking for existing traces…"; results.replaceChildren();
      const query = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
      try {
        const doc = obj(await options.api("/api/guided-investigation?" + query, "GET", 1024 * 1024));
        if (!current(token)) return;
        const summary = renderReadiness(doc, filters);
        candidates(Array.isArray(doc.candidates) ? doc.candidates : []);
        status.textContent = `${summary.headline}. Existing matching requests: ${Array.isArray(doc.candidates) ? doc.candidates.length : 0}.${doc.partial ? " Some capture evidence could not be checked." : ""} No match does not prove a request never ran or that it expired.`;
        controls(false); phase = "idle";
      } catch (_) { if (current(token)) { stop("Cannot verify. Capture readiness check unavailable. Check the configured debug endpoint and try again; nothing was changed."); status.focus?.(); } }
    }
    async function arm() {
      stop();
      let filters; try { filters = input(); } catch (error) { inputError(error); return; }
      const seconds = Number(duration.value);
      if (![60, 120].includes(seconds) || typeof options.saveTrace !== "function") return;
      const token = version, setupDeadline = now() + seconds * 1000;
      controls(true); phase = "arming"; results.replaceChildren();
      status.textContent = "Arming: reading the retained baseline. Wait before reproducing…";
      // The baseline itself is bounded, including an indefinitely pending read.
      deadlineTimer = later(() => { if (current(token)) stop("Watch timed out while arming. Nothing was saved."); }, seconds * 1000);
      let baseline;
      try { baseline = summaries(await options.api("/api/distributed-traces?limit=" + MAX_ROWS, "GET", 1024 * 1024)); }
      catch (error) { if (current(token)) stop(error.message || "Could not read the retained baseline; nothing was saved."); return; }
      if (!current(token) || now() >= setupDeadline) return;
      const existing = new Set(baseline.map(r => r.traceId)), armedAt = now(), deadline = setupDeadline;
      clear(deadlineTimer);
      deadlineTimer = later(() => { if (current(token)) stop("Watch timed out. No unambiguous completed new match was saved; absence does not prove non-execution or expiry."); }, deadline - now());
      phase = "watching"; status.textContent = `Watching for ${Math.ceil((deadline - now()) / 1000)} seconds. Reproduce one matching backend request now.`;
      let selected = null, completeSeen = false;
      async function poll() {
        if (!current(token) || now() >= deadline) return;
        try {
          const requests = summaries(await options.api("/api/distributed-traces?limit=" + MAX_ROWS, "GET", 1024 * 1024));
          if (!current(token) || now() >= deadline) return;
          const matchesNow = requests.filter(r => !existing.has(r.traceId) && matches(r, filters)
            && r.segments.some(s => s.startedAtMillis !== null && s.startedAtMillis > armedAt));
          if (matchesNow.length > 1 || (selected && matchesNow.some(r => r.traceId !== selected.traceId))) {
            candidates(matchesNow); stop("Multiple new matching requests were observed. Nothing was saved. Open a candidate or narrow the exact filters and arm again."); return;
          }
          const request = matchesNow[0];
          if (request) {
            if (!request.root || request.startedAtMillis === null || request.startedAtMillis <= armedAt) {
              completeSeen = false;
              status.textContent = "Matching evidence appeared, but a newly started request root is not established. Waiting; nothing saved.";
            } else {
              const identityNow = requestIdentity(request);
              if (selected && selected.identity !== identityNow) { stop("The selected request identity changed. Nothing was saved; arm again."); return; }
              selected = selected || {traceId: request.traceId, identity: identityNow};
              if (completed(request) && completeSeen) {
                phase = "saving"; status.textContent = "Saving the selected completed request…";
                const isCurrent = () => current(token) && now() < deadline;
                const saved = await options.saveTrace(request, {isCurrent, filters: {...filters}, armedAt, deadline});
                if (!isCurrent()) return;
                stop(saved?.partial ? "Saved a PARTIAL point-in-time capture. Review its warnings; missing evidence was not recovered."
                  : "Saved a point-in-time capture of the selected request. Review completeness and warnings.");
                if (typeof options.onSaved === "function") options.onSaved(saved);
                return;
              }
              completeSeen = completed(request);
              status.textContent = completeSeen ? "One completed new match found. Confirming it remains unambiguous…"
                : "One new matching request is still running. Waiting for completion…";
            }
          } else {
            completeSeen = false;
            if (selected) { stop("The selected request is no longer retained. Nothing was saved and no replacement was selected."); return; }
          }
          if (current(token)) timer = later(poll, Math.min(POLL_MS, deadline - now()));
        } catch (error) { if (current(token)) stop(error.message || "Watch failed; nothing was saved."); }
      }
      timer = later(poll, POLL_MS);
    }
    form.onsubmit = runDiagnosis; watch.onclick = arm; cancel.onclick = () => stop("Watch cancelled. No further capture will be saved.");
    if (options.draft) prepare(options.draft);
    return {destroy() { destroyed = true; stop(); container.replaceChildren(); },
      cancel() { stop("Watch cancelled. No further capture will be saved."); }, setServices, prepare,
      get phase() { return phase; }};
  }
  return {render, validate, recoveryDraft, readiness, summaries, matches, completed, MAX_ROWS};
});
