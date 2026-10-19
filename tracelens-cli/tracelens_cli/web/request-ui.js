/* Request discovery only. Pins are explicit, bounded browser snapshots, not a collector. */
(function () {
  "use strict";
  const M = window.TraceLensRequests;
  const $ = (id) => document.getElementById(id);
  const fields = {query: "filter", path: "path-filter", user: "user-filter", site: "site-filter",
    service: "service-filter", method: "method-filter", channel: "channel-filter",
    sinceMinutes: "time-filter", outcome: "outcome-filter", minDuration: "min-duration", maxDuration: "max-duration"};
  const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
  const date = (ms) => ms ? new Date(ms).toLocaleString() : "Unknown";
  const duration = (ms) => ms == null ? "Unknown" : `${Number(ms).toFixed(1)} ms`;
  const service = (s) => s.service || s.applicationId || "local";
  const text = value => typeof value === "string" || typeof value === "number" ? String(value) : "";
  const errorDefaults = () => ({category: "", httpStatus: "", exceptionClass: ""});
  const errorFields = {category: "error-category", httpStatus: "error-http-status", exceptionClass: "error-exception-class"};
  const count = value => Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : 0;
  const quantity = (value, singular) => `${count(value)} ${singular}${count(value) === 1 ? "" : "s"}`;
  function button(label, action, cls) { const b = el("button", label, cls); b.type = "button"; b.onclick = action; return b; }

  class RequestUI {
    constructor(options) {
      this.options = options;
      this.rows = []; this.requests = []; this.pins = []; this.views = [];
      this.mode = "live"; this.expanded = new Set(); this.store = null; this.busy = new Set();
      this.filters = M.defaults(); this.services = []; this.selectedView = "";
      this.errorFilters = errorDefaults(); this.errorDoc = null; this.errorLoading = false; this.errorFailure = "";
      this.errorSequence = 0; this.errorTimer = null; this.errorRequest = null; this.errorDocKey = null; this.viewerMode = null;
      this.bind(); this.writeFilters();
    }
    message(message, error = false) {
      $("list-message").textContent = message || "";
      $("list-message").classList.toggle("hidden", !message);
      $("list-message").classList.toggle("error", error);
    }
    run(action) { Promise.resolve().then(action).catch(e => this.message(e.message, true)); }
    async init() {
      try {
        const context = await this.options.api("/api/viewer-context");
        this.viewerMode = context.mode;
        this.store = window.TraceLensBrowserStore.create(context.namespace);
        await this.refreshStored();
      } catch (e) {
        this.store = null;
        this.message(`Live discovery works, but browser saving is unavailable: ${e.message}`, true);
      }
      $("save-view").disabled = !this.store;
      this.render();
      if (this.mode === "errors") this.run(() => this.refreshErrors());
    }
    async refreshStored() {
      [this.views, this.pins] = await Promise.all([this.store.listViews(), this.store.listPins()]);
      $("saved-view").replaceChildren(new Option("Choose a view…", ""), ...this.views.map(v => new Option(v.name, v.id)));
      $("saved-view").value = this.selectedView;
      $("delete-view").disabled = !$("saved-view").value;
      $("pin-count").textContent = this.pins.length;
      this.render();
    }
    setServices(names) {
      this.services = [...new Set([...names, ...this.rows.map(service)])].sort();
      if (this.filters.service && !this.services.includes(this.filters.service)) this.services.push(this.filters.service);
      $("service-filter").replaceChildren(new Option("All services", ""), ...this.services.map(s => new Option(s, s)));
      $("service-filter").value = this.filters.service;
    }
    setTraces(doc) {
      this.rows = doc.traces || []; this.requests = M.groupTraces(this.rows);
      this.partial = !!doc.partial; this.limited = !!doc.limited;
      this.setServices(this.services);
      this.render();
    }
    displayService(name, segments = []) {
      if (this.viewerMode !== "single" || name !== "default") return name;
      const applications = [...new Set(segments.filter(segment => segment && segment.service === "default")
        .map(segment => text(segment.applicationId)).filter(label => label && label !== "default"))];
      return applications.length === 1 ? applications[0] : name;
    }
    readFilters() {
      for (const [key, id] of Object.entries(fields)) this.filters[key] = $(id).value;
      this.filters.collapse = $("collapse-apis").checked;
    }
    writeFilters() {
      this.setServices(this.services);
      for (const [key, id] of Object.entries(fields)) $(id).value = this.filters[key] ?? "";
      $("collapse-apis").checked = this.filters.collapse !== false;
      for (const [key, id] of Object.entries(errorFields)) $(id).value = this.errorFilters[key] || "";
    }
    changeMode(mode) {
      if (!["live", "errors", "pins"].includes(mode)) return;
      const changed = this.mode !== mode;
      if (changed) { this.errorSequence++; clearTimeout(this.errorTimer); this.errorTimer = null; this.errorLoading = false; }
      this.mode = mode;
      const selected = this.views.find(view => view.id === this.selectedView);
      if (selected && mode !== "pins" && (selected.filters.viewMode === "errors" ? "errors" : "live") !== mode) {
        this.selectedView = ""; $("saved-view").value = ""; $("delete-view").disabled = true;
      }
      if (mode === "errors" && this.errorDocKey !== this.errorQuery()) this.errorDoc = null;
      $("live-tab").setAttribute("aria-pressed", String(mode === "live"));
      $("errors-tab").setAttribute("aria-pressed", String(mode === "errors"));
      $("pins-tab").setAttribute("aria-pressed", String(mode === "pins"));
      $("live-controls").classList.toggle("hidden", mode === "pins");
      $("pin-help").classList.toggle("hidden", mode !== "pins");
      $("error-controls").classList.toggle("hidden", mode !== "errors");
      $("error-help").classList.toggle("hidden", mode !== "errors");
      $("error-state").classList.toggle("hidden", mode !== "errors");
      $("outcome-filter-control").classList.toggle("hidden", mode === "errors");
      $("collapse-apis-control").classList.toggle("hidden", mode === "errors");
      $("service-filter-label").textContent = mode === "errors" ? "Failing service" : "Service";
      $("list-filter-help").textContent = mode === "errors"
        ? "Filters inspect retained failures; they do not change recording. Duration is the root service’s local duration."
        : "Filters select requests; they do not change agent recording. Duration is the root service’s local duration.";
      this.message(""); this.render();
      if (mode === "errors") this.run(() => this.refreshErrors());
      else if (mode === "live" && changed && this.options.refreshLive) this.run(() => this.options.refreshLive());
    }
    filtersChanged() {
      this.selectedView = ""; $("saved-view").value = ""; $("delete-view").disabled = true;
      if (this.mode !== "errors") { this.render(); return; }
      this.errorSequence++; this.errorDoc = null; this.errorFailure = ""; this.errorLoading = true;
      clearTimeout(this.errorTimer);
      this.errorTimer = setTimeout(() => { this.errorTimer = null; this.run(() => this.refreshErrors()); }, 200);
      this.render();
    }
    durationProblem() {
      const invalid = [this.filters.minDuration, this.filters.maxDuration].some(v => v !== "" && (!Number.isFinite(Number(v)) || Number(v) < 0)) ||
        (this.filters.minDuration !== "" && this.filters.maxDuration !== "" && Number(this.filters.minDuration) > Number(this.filters.maxDuration));
      return invalid ? "Enter a valid duration range (minimum must not exceed maximum)." : "";
    }
    errorQuery() {
      const values = {service: this.viewerMode === "single" && this.filters.service ? "default" : this.filters.service,
        method: this.filters.method, path: this.filters.path, user: this.filters.user, site: this.filters.site,
        channel: this.filters.channel, since_minutes: this.filters.sinceMinutes, query: this.filters.query,
        min_duration: this.filters.minDuration, max_duration: this.filters.maxDuration,
        category: this.errorFilters.category, http_status: this.errorFilters.httpStatus, exception_class: this.errorFilters.exceptionClass};
      return "/api/error-groups?" + new URLSearchParams(Object.entries(values).map(([key, value]) => [key, value ?? ""]));
    }
    async refreshErrors() {
      if (this.mode !== "errors") return;
      clearTimeout(this.errorTimer); this.errorTimer = null;
      const status = this.errorFilters.httpStatus;
      const invalidStatus = status !== "" && (!Number.isInteger(Number(status)) || Number(status) < 100 || Number(status) > 599);
      const problem = this.durationProblem() || (invalidStatus ? "Enter an exact HTTP status between 100 and 599." : "");
      if (problem) {
        this.errorSequence++; this.errorLoading = false; this.errorDoc = null; this.errorFailure = problem; this.render(); return;
      }
      const key = this.errorQuery();
      if (this.errorRequest && this.errorRequest.key === key && this.errorRequest.sequence === this.errorSequence) return this.errorRequest.promise;
      const sequence = ++this.errorSequence;
      const current = () => this.mode === "errors" && sequence === this.errorSequence && key === this.errorQuery();
      if (this.errorDocKey !== key) this.errorDoc = null;
      this.errorLoading = true; this.errorFailure = ""; this.render();
      const promise = (async () => {
        try {
          const doc = await Promise.resolve().then(() => this.options.api(key));
          if (!current()) return;
          if (!doc || doc.schemaVersion !== 1 || doc.kind !== "error-groups" || doc.scope !== "retained-only" || !Array.isArray(doc.groups) || !Array.isArray(doc.requests) || !doc.counts || typeof doc.counts !== "object") {
            throw new Error("The viewer returned an unsupported error-groups response.");
          }
          this.errorDoc = doc; this.errorDocKey = key;
        } catch (error) {
          if (current()) { this.errorDoc = null; this.errorFailure = `Could not load retained errors: ${error && error.message || "Unknown error"}`; }
        } finally {
          if (current()) { this.errorLoading = false; this.render(); }
          if (this.errorRequest && this.errorRequest.sequence === sequence) this.errorRequest = null;
        }
      })();
      this.errorRequest = {key, sequence, promise};
      return promise;
    }
    bind() {
      for (const id of [...Object.values(fields), "collapse-apis"]) {
        $(id).addEventListener($(id).tagName === "SELECT" || $(id).type === "checkbox" ? "change" : "input", () => {
          this.readFilters(); this.filtersChanged();
        });
      }
      for (const [key, id] of Object.entries(errorFields)) {
        $(id).addEventListener($(id).tagName === "SELECT" ? "change" : "input", () => {
          this.errorFilters[key] = $(id).value; this.filtersChanged();
        });
      }
      $("live-tab").onclick = () => this.changeMode("live");
      $("errors-tab").onclick = () => this.changeMode("errors");
      $("pins-tab").onclick = () => this.changeMode("pins");
      $("clear-filters").onclick = () => {
        const preserved = {outcome: this.filters.outcome, collapse: this.filters.collapse};
        this.filters = {...M.defaults(), ...(this.mode === "errors" ? preserved : {})};
        if (this.mode === "errors") this.errorFilters = errorDefaults();
        this.writeFilters(); this.filtersChanged();
      };
      $("saved-view").onchange = () => {
        const view = this.views.find(v => v.id === $("saved-view").value);
        if (!view) return;
        this.selectedView = view.id;
        this.filters = Object.fromEntries(Object.entries(M.defaults()).map(([key, value]) => [key, view.filters[key] ?? value]));
        this.errorFilters = {category: view.filters.errorCategory || "", httpStatus: view.filters.errorStatus || "", exceptionClass: view.filters.errorException || ""};
        this.errorSequence++; this.errorDoc = null;
        this.writeFilters(); $("delete-view").disabled = false; this.changeMode(view.filters.viewMode === "errors" ? "errors" : "live");
      };
      $("save-view").onclick = () => this.run(async () => {
        const name = await this.askName("Save current view", "", "Saves filter settings in this browser—not trace data.");
        if (!name) return;
        const filters = {...this.filters, viewMode: this.mode === "errors" ? "errors" : "live"};
        if (this.mode === "errors") Object.assign(filters, {errorCategory: this.errorFilters.category,
          errorStatus: this.errorFilters.httpStatus, errorException: this.errorFilters.exceptionClass});
        const saved = await this.store.saveView(name, filters);
        this.selectedView = saved.id; await this.refreshStored(); this.message(`Saved view “${name}”.`);
      });
      $("delete-view").onclick = () => this.run(async () => {
        if (!this.selectedView || !confirm("Delete this saved view? Trace data is unaffected.")) return;
        await this.store.deleteView(this.selectedView); this.selectedView = ""; await this.refreshStored();
      });
    }
    askName(title, current, help) {
      const dialog = $("name-dialog");
      $("name-title").textContent = title; $("name-input").value = current || "";
      $("name-help").textContent = help; dialog.returnValue = "cancel";
      return new Promise(resolve => {
        dialog.addEventListener("close", () => resolve(dialog.returnValue === "save" ? $("name-input").value.trim() : null), {once: true});
        dialog.showModal(); $("name-input").focus();
      });
    }
    async pin(request) {
      if (!this.store || this.busy.has(request.key)) return;
      const existing = this.pins.find(p => p.id === request.key);
      const name = await this.askName("Pin a capture", existing ? existing.name : request.label || `${request.method || ""} ${request.path || "Request"}`,
        "Save a point-in-time copy, including executed source lines, in this browser only. Max 20 pins, 5 MiB each, 20 MiB total. Active or missing segments will be marked partial.");
      if (!name) return;
      this.busy.add(request.key); this.render(); this.message("Saving observed service segments…");
      try {
        const snapshot = await this.options.snapshot(request);
        await this.store.savePin({...snapshot, id: request.key, name, traceId: request.traceId, request: snapshot.request || request, capturedAt: Date.now()});
        await this.refreshStored(); this.message(`Pinned “${name}” in this browser${snapshot.partial ? " (partial snapshot)" : ""}.`);
      } finally { this.busy.delete(request.key); this.render(); }
    }
    segmentDetails(request) {
      const details = el("details", null, "segment-details");
      const key = "segments:" + request.key;
      details.open = this.expanded.has(key);
      details.ontoggle = () => { if (details.open) this.expanded.add(key); else this.expanded.delete(key); };
      details.append(el("summary", `${request.segments.length} observed segment${request.segments.length === 1 ? "" : "s"}`));
      for (const s of request.segments) {
        const line = el("div");
        line.append(button(`${this.displayService(service(s), [s])} · ${s.method || s.httpMethod || s.entryChannel || ""} ${s.path || s.lensId || ""}`, () => this.run(() => this.options.openSegment(s)), "text-button"));
        line.append(el("small", `HTTP ${s.httpStatus ?? "unknown"} · ${s.status || "unknown"} · ${s.segmentId} · instance ${s.serviceInstanceId || "unknown"}`));
        details.append(line);
      }
      return details;
    }
    requestRow(request, pin, group) {
      const tr = el("tr", null, "request-row");
      const title = el("td");
      if (group) {
        tr.classList.add("group-member", `${group.kind}-group-member`);
        if (group.last) tr.classList.add("group-member-last");
        tr.setAttribute("aria-describedby", group.headerId);
        title.append(el("small", `Request ${group.position} of ${group.total}`, "group-member-label"));
      }
      title.append(button(pin ? pin.name : `${request.method || request.channel || ""} ${request.path || request.label || "Request"}`,
        () => this.run(async () => {
          if (pin) {
            const full = await this.store.getPin(pin.id);
            if (!full) throw new Error("This pinned capture is no longer in browser storage.");
            await this.options.openPin(full);
          } else await this.options.openRequest(request);
        }), "request-link"));
      if (pin) title.append(el("small", `Snapshot saved ${date(pin.capturedAt)}${pin.partial ? " · PARTIAL" : " · observed capture"}`));
      else if (request.label && !request.label.startsWith("auto:")) title.append(el("small", request.label));
      title.append(el("small", `Trace ${request.traceId || "unavailable"}`, "trace-id"));
      if (request.rootMissing) title.append(el("small", "Root missing or ambiguous · representative segment shown", "missing"));
      const services = el("td", request.services.map(name => this.displayService(name, request.segments || [])).join(", "));
      services.title = `Observed services (canonical: ${request.services.join(", ")}); list order is not a causal ordering`;
      if (!pin) services.append(this.segmentDetails(request));
      const identity = el("td", request.user || "unknown"); identity.append(el("small", `Site ${request.site || "unknown"}`));
      const outcome = el("td"); outcome.append(el("span", request.outcome || "unknown", `outcome ${request.outcome}`));
      outcome.append(el("small", request.httpStatus == null ? "HTTP unknown" : `Root HTTP ${request.httpStatus}`));
      if (this.mode === "errors" && !pin && Number(request.httpStatus) >= 200 && Number(request.httpStatus) < 400) {
        outcome.append(el("small", "Failure evidence despite a successful root response · recovery not established", "error-root-note"));
      }
      const actions = el("td");
      if (pin) {
        actions.append(button("Rename", () => this.run(async () => {
          const name = await this.askName("Rename pinned capture", pin.name, "Changes the name in this browser only.");
          if (name) { await this.store.renamePin(pin.id, name); await this.refreshStored(); }
        })));
        actions.append(button("Unpin", () => this.run(async () => {
          if (!confirm(`Remove “${pin.name}” from browser storage? Its saved snapshot will be deleted.`)) return;
          await this.store.deletePin(pin.id); await this.refreshStored();
        })));
      } else {
        const pinned = this.pins.some(p => p.id === request.key);
        const b = button(this.busy.has(request.key) ? "Saving…" : pinned ? "Update pin" : "Pin capture", () => this.run(() => this.pin(request)));
        b.disabled = !this.store || this.busy.has(request.key); actions.append(b);
      }
      tr.append(title, services, identity, outcome, el("td", duration(request.durationMs)), el("td", date(request.startedAtMillis)), actions);
      return tr;
    }
    appendGroupMembers(tbody, requests, kind, headerId, unavailable = false) {
      requests.forEach((request, index) => tbody.append(this.requestRow(request, null, {
        kind, headerId, position: index + 1, total: requests.length,
        last: index === requests.length - 1 && !unavailable,
      })));
      if (unavailable) {
        const row = el("tr", null, `group-member ${kind}-group-member group-unavailable group-member-last`);
        row.setAttribute("aria-describedby", headerId);
        const cell = el("td", "Some request rows are unavailable in this retained result.", "missing");
        cell.colSpan = 7; row.append(cell); tbody.append(row);
      }
      const end = el("tr", null, `group-end ${kind}-group-end`);
      end.setAttribute("aria-describedby", headerId);
      const cell = el("td"); cell.colSpan = 7;
      cell.append(el("small", `End of group · ${quantity(requests.length, "request")}${unavailable ? " shown" : ""}`));
      end.append(cell); tbody.append(end);
    }
    render() {
      const tbody = $("trace-rows"); tbody.replaceChildren();
      this.renderGroupIndex = 0;
      if (this.mode === "errors") { this.renderErrors(tbody); return; }
      if (this.mode === "pins") {
        for (const pin of this.pins) {
          if (pin.request) tbody.append(this.requestRow(pin.request, pin));
        }
        $("results-summary").textContent = `${this.pins.length} named browser snapshots · live filters do not hide pins`;
        $("empty").textContent = "No pinned captures. Find a live request and choose Pin capture to save a named snapshot.";
        $("empty").classList.toggle("hidden", this.pins.length !== 0); return;
      }
      const invalid = !!this.durationProblem();
      const requests = invalid ? [] : M.filterRequests(this.requests, this.filters, Date.now());
      const buckets = M.bucketRequests(requests, this.filters.collapse);
      for (const bucket of buckets) {
        if (bucket.requests.length === 1 || !this.filters.collapse) {
          for (const request of bucket.requests) tbody.append(this.requestRow(request));
          continue;
        }
        const row = el("tr", null, "api-group"); const cell = el("td"); cell.colSpan = 7;
        const expanded = this.expanded.has(bucket.key);
        row.classList.toggle("group-expanded", expanded);
        const b = button(`${expanded ? "▾" : "▸"} ${bucket.label} · ${bucket.requests.length} separate requests`, () => {
          if (expanded) this.expanded.delete(bucket.key); else this.expanded.add(bucket.key); this.render();
        }, "group-button");
        b.id = `request-group-${++this.renderGroupIndex}`;
        b.setAttribute("aria-expanded", String(expanded)); cell.append(b);
        const errors = bucket.requests.filter(r => r.hasErrors).length;
        cell.append(el("small", `${errors} with errors · latest ${date(bucket.latestMillis)} · grouping does not merge requests`));
        row.append(cell); tbody.append(row);
        if (expanded) this.appendGroupMembers(tbody, bucket.requests, "api", b.id);
      }
      $("results-summary").textContent = `${requests.length} matching requests / ${this.requests.length} retained · ${this.rows.length} observed segments${this.partial ? " · Some services are unavailable" : ""}${this.limited ? " · Upstream results limited" : ""}`;
      if (this.filters.site && !this.requests.some(r => r.segments.some(s => s.site))) {
        $("results-summary").textContent += " · Site metadata is not reported by these agents; update the agent to use the site filter. User identities are never guessed as site IDs.";
      }
      $("empty").textContent = invalid ? "Enter a valid duration range (minimum must not exceed maximum)." :
        "No matching requests. Broaden the time window or channel, or reset filters. Only retained traces can be shown.";
      $("empty").classList.toggle("hidden", requests.length !== 0);
    }
    renderErrors(tbody) {
      const state = $("error-state"); state.replaceChildren();
      if (this.errorLoading) state.append(el("p", this.errorDoc ? "Refreshing retained error evidence…" : "Loading retained error evidence…"));
      if (this.errorFailure) state.append(el("p", this.errorFailure, "notice error"));
      const doc = this.errorDoc;
      if (!doc) {
        $("results-summary").textContent = "Retained errors · counts are not an error rate";
        $("empty").textContent = this.errorFailure ? "Error evidence is unavailable. Correct the filters or refresh to try again." : "Loading retained error groups…";
        $("empty").classList.toggle("hidden", this.errorLoading);
        return;
      }
      if (doc.partial) state.append(el("p", "Partial retained evidence — some service data is unavailable.", "missing"));
      if (doc.limited) state.append(el("p", "Results are limited; counts describe the loaded retained evidence only.", "missing"));
      const notices = [...(Array.isArray(doc.errors) ? doc.errors : []), ...(Array.isArray(doc.warnings) ? doc.warnings : [])];
      if (notices.length) {
        const details = el("details"); details.append(el("summary", `${notices.length} evidence warning${notices.length === 1 ? "" : "s"}`));
        for (const notice of notices.slice(0, 20)) {
          const value = text(notice) || (notice && [text(notice.service), text(notice.error) || text(notice.message)].filter(Boolean).join(": "));
          if (value) details.append(el("p", value));
        }
        if (notices.length > 20) details.append(el("p", `${notices.length - 20} additional warnings are present in the response.`));
        state.append(details);
      }
      const requests = new Map(doc.requests.filter(request => request && typeof request.key === "string").map(request => [request.key, request]));
      const categoryLabels = {http4xx: "HTTP 4xx", http5xx: "HTTP 5xx", timeout: "Timeout", execution: "Execution failure"};
      for (const group of doc.groups) {
        if (!group || typeof group.id !== "string") continue;
        const key = "error-group:" + group.id;
        const expanded = this.expanded.has(key);
        const row = el("tr", null, "error-group"); const cell = el("td"); cell.colSpan = 7;
        row.classList.toggle("group-expanded", expanded);
        const groupSegments = (Array.isArray(group.requestKeys) ? group.requestKeys : [])
          .flatMap(requestKey => requests.get(requestKey)?.segments || []);
        const serviceLabel = this.displayService(text(group.service), groupSegments) || "Unknown service";
        const signature = [serviceLabel, [text(group.method), text(group.route)].filter(Boolean).join(" ") || text(group.channel) || "Unknown API"].join(" · ");
        const toggle = button(`${expanded ? "▾" : "▸"} ${signature}`, () => {
          if (this.expanded.has(key)) this.expanded.delete(key); else this.expanded.add(key); this.render();
        }, "group-button error-group-button");
        toggle.title = `Canonical failing service: ${text(group.service) || "Unknown"}`;
        toggle.id = `request-group-${++this.renderGroupIndex}`;
        toggle.setAttribute("aria-expanded", String(expanded)); cell.append(toggle);
        const routeLabel = {template: "Route template", literal: "Exact retained path", label: "Captured label"}[group.routeKind] || "Route unavailable";
        const evidence = [categoryLabels[group.category] || text(group.category) || "Failure", group.httpStatus == null ? "" : `HTTP ${text(group.httpStatus)}`,
          text(group.exceptionClass), text(group.channel), routeLabel].filter(Boolean).join(" · ");
        cell.append(el("small", evidence, "error-signature"));
        cell.append(el("small", `${quantity(group.requestCount, "unique request")} · ${quantity(group.segmentCount, "failing segment")} · first ${date(group.firstSeenMillis)} · latest ${date(group.lastSeenMillis)}`));
        row.append(cell); tbody.append(row);
        if (expanded) {
          const keys = [...new Set(Array.isArray(group.requestKeys) ? group.requestKeys : [])];
          this.appendGroupMembers(tbody, keys.map(requestKey => requests.get(requestKey)).filter(Boolean),
            "error", toggle.id, keys.some(requestKey => !requests.has(requestKey)) || !keys.length);
        }
      }
      const counts = doc.counts;
      $("results-summary").textContent = `${quantity(counts.matchingRequests, "unique matching retained request")} · ${quantity(counts.matchingSegments, "failing segment")} · ${quantity(counts.groups, "signature")} / ${quantity(counts.loadedRequests, "loaded request")} (${quantity(counts.loadedSegments, "segment")}) · Groups may overlap; not an error rate`;
      $("empty").textContent = "No matching retained failures. Broaden the time window or filters. This does not establish that no failures occurred outside retained evidence.";
      $("empty").classList.toggle("hidden", doc.groups.length !== 0);
    }
  }
  window.TraceLensRequestUI = RequestUI;
})();
