/* Request discovery and explicit bounded snapshots; never an automatic collector. */
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
  const jobDefaults = () => ({...M.defaults(), channel: "SCHEDULER"});
  const scheduled = request => request.channel === "SCHEDULER";
  const viewMode = filters => filters.viewMode === "errors" ? "errors"
    : filters.viewMode === "jobs" || filters.channel === "SCHEDULER" ? "jobs" : "live";
  const errorFields = {category: "error-category", httpStatus: "error-http-status", exceptionClass: "error-exception-class"};
  const count = value => Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : 0;
  const quantity = (value, singular) => `${count(value)} ${singular}${count(value) === 1 ? "" : "s"}`;
  // Display-only abbreviation. Never decode IDs or use shortened text as a
  // route template, filter value, grouping key, navigation ID or saved evidence.
  const compactPath = value => text(value).split("/").map(part => {
    const opaque = /^[0-9a-f-]{33,}$/i.test(part) || (/^[A-Za-z0-9+=]{33,}$/.test(part)
      && /[a-z]/.test(part) && /[A-Z]/.test(part) && /[0-9]/.test(part));
    return opaque ? `${part.slice(0, 8)}…${part.slice(-6)}` : part;
  }).join("/");
  const cbApp = name => name === "cb-app" || name === "chargebee-app";
  function button(label, action, cls) { const b = el("button", label, cls); b.type = "button"; b.onclick = action; return b; }

  class RequestUI {
    constructor(options) {
      this.options = options;
      this.rows = []; this.requests = []; this.pins = []; this.views = [];
      this.comparisonSelection = new Set();
      this.comparisonControls = new Map();
      this.mode = "live"; this.expanded = new Set(); this.store = null; this.busy = new Set();
      this.viewerStore = window.TraceLensViewerStore.create(options.api);
      this.viewerReady = false;
      this.storageInitialized = false;
      this.filters = M.defaults(); this.services = []; this.selectedView = "";
      this.requestFilters = this.filters; this.jobFilters = jobDefaults();
      this.errorFilters = errorDefaults(); this.errorDoc = null; this.errorLoading = false; this.errorFailure = "";
      this.errorSequence = 0; this.errorTimer = null; this.errorRequest = null; this.errorDocKey = null; this.viewerMode = null;
      this.visibleInspectionKeys = new Set(); this.pendingTraces = null; this.traceSignature = "";
      this.moreFiltersOpen = false;
      // Payloads stay out of list summaries. Only an explicit search reads them;
      // the browser keeps matching identities, never a second payload index.
      this.keywordSearch = window.TraceLensRequestSearch?.create(options.api);
      this.keywordTraceSignature = "";
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
        this.capabilities = context.capabilities || {};
        this.options.viewerContext?.(context);
        $("snapshot-import-controls").classList.toggle("hidden", this.mode !== "pins" || !this.capabilities.largeTraceDetails);
        this.store = window.TraceLensBrowserStore.create(context.namespace);
      } catch (e) {
        this.store = null;
        this.message(`Browser-only saved views are unavailable: ${e.message}`, true);
      }
      await this.refreshStored();
      this.storageInitialized = true;
      $("save-view").disabled = !this.store;
      this.render();
      if (this.mode === "errors") this.run(() => this.refreshErrors());
    }
    // Saved snapshots are filed per service configuration. When the configuration changes the list
    // starts empty, so say that earlier snapshots exist and offer an explicit copy.
    renderEarlier() {
      const box = $("earlier-snapshots");
      if (!box) return;
      const total = this.mode === "pins" && this.earlierSummary ? count(this.earlierSummary.snapshotCount) : 0;
      box.replaceChildren();
      box.classList.toggle("hidden", !total);
      if (!total) return;
      box.append(el("strong", `${quantity(total, "snapshot")} saved under ${count(this.earlierSummary.configurations) === 1 ? "an earlier viewer configuration" : "earlier viewer configurations"}`),
        el("p", "Saved captures are filed per service configuration. A changed service URL, service list or source path starts a new list. Nothing was deleted."));
      if (!this.earlier) {
        // The full report reads those folders, so it loads only when asked for.
        const show = button("Show earlier snapshots", () => {
          show.disabled = true;
          this.run(async () => {
            try {
              this.earlier = await this.viewerStore.otherConfigurations();
              if (!this.earlier) this.message("This viewer cannot list earlier snapshots. Restart it with the latest version.", true);
              this.renderEarlier();
            } finally { show.disabled = false; }
          });
        });
        box.append(show);
        return;
      }
      const groups = (Array.isArray(this.earlier.configurations) ? this.earlier.configurations : [])
        .filter(group => group && /^[0-9a-f]{64}$/.test(group.namespace) && Array.isArray(group.snapshots) && group.snapshots.length);
      for (const group of groups) {
        const details = el("details"), list = el("ul");
        details.open = groups.length === 1;
        details.append(el("summary", `${quantity(group.snapshots.length, "snapshot")} · last saved ${date(group.latestSavedAt)}`));
        for (const row of group.snapshots.slice(0, 40)) list.append(el("li", `${typeof row.name === "string" ? row.name : "Unnamed snapshot"}${row.large ? " · large" : ""}`));
        const copy = button("Copy into this configuration", () => {
          copy.disabled = true;
          this.run(async () => {
            try {
              const result = await this.viewerStore.copyFrom(group.namespace);
              const copied = Array.isArray(result.copied) ? result.copied.length : 0, skipped = Array.isArray(result.skipped) ? result.skipped : [];
              const reasons = [...new Set(skipped.map(row => row && typeof row.reason === "string" ? row.reason : "").filter(Boolean))].slice(0, 3);
              this.message(`${quantity(copied, "snapshot")} copied. The originals were left in place.${skipped.length ? ` ${skipped.length} not copied: ${reasons.join(" ")}` : ""}`, copied === 0 && skipped.length > 0);
              await this.refreshStored();
            } finally { copy.disabled = false; }
          });
        });
        details.append(list, copy);
        box.append(details);
      }
      if (!groups.length) box.append(el("p", "Those snapshots could not be read."));
      if (this.earlier.omittedConfigurations) box.append(el("p", `${quantity(this.earlier.omittedConfigurations, "older configuration")} not listed.`));
      for (const warning of (Array.isArray(this.earlier.warnings) ? this.earlier.warnings : []).slice(0, 3)) if (typeof warning === "string") box.append(el("p", warning, "warn"));
    }
    async refreshStored() {
      const [views, local, files] = await Promise.allSettled([
        this.store ? this.store.listViews() : Promise.resolve([]),
        this.store ? this.store.listPins() : Promise.resolve([]), this.viewerStore.listPins()]);
      this.views = views.status === "fulfilled" ? views.value : [];
      const browserPins = local.status === "fulfilled" ? local.value.map(pin => ({...pin, storage: "browser"})) : [];
      this.viewerReady = files.status === "fulfilled";
      this.earlierSummary = this.viewerReady && files.value.earlier && typeof files.value.earlier === "object" ? files.value.earlier : null;
      this.pins = [...(this.viewerReady ? files.value.snapshots.map(pin => ({...pin, storage: "viewer"})) : []), ...browserPins]
        .sort((a, b) => (b.savedAt || b.capturedAt || 0) - (a.savedAt || a.capturedAt || 0));
      const problems = [views, local, files].filter(result => result.status === "rejected").map(result => result.reason.message);
      if (this.viewerReady) problems.push(...(files.value.warnings || []));
      if (problems.length) this.message([...new Set(problems)].join(" "), true);
      $("saved-view").replaceChildren(new Option("Choose a view…", ""), ...this.views.map(v => new Option(v.name, v.id)));
      $("saved-view").value = this.selectedView;
      $("delete-view").disabled = !$("saved-view").value;
      this.render();
    }
    setServices(names) {
      this.services = [...new Set([...names, ...this.rows.map(service)])].sort();
      if (this.filters.service && !this.services.includes(this.filters.service)) this.services.push(this.filters.service);
      const signature = JSON.stringify(this.services);
      if (this.serviceOptionsSignature !== signature) {
        $("service-filter").replaceChildren(new Option("All services", ""), ...this.services.map(s => new Option(s, s)));
        this.serviceOptionsSignature = signature;
      }
      $("service-filter").value = this.filters.service;
    }
    setTraces(doc) {
      const signature = JSON.stringify([doc.traces || [], !!doc.partial, !!doc.limited]);
      if (signature === this.traceSignature) {
        this.pendingTraces = null;
        // Relative time filters must still age out unchanged captures, except
        // while the user has deliberately held a row/group open.
        if (!this.visibleInspectionKeys.size) this.render(); else this.renderPending();
        return;
      }
      if ((this.mode === "live" || this.mode === "jobs") && (this.visibleInspectionKeys.size || this.pendingTraces)) {
        // Retain only the latest bounded summary response, never a history or
        // event export. Do not replace focused/expanded rows during polling.
        this.pendingTraces = {doc, signature}; this.renderPending(); return;
      }
      this.acceptTraces(doc, signature); this.render();
    }
    acceptTraces(doc, signature) {
      this.traceSignature = signature;
      this.rows = doc.traces || []; this.requests = M.groupTraces(this.rows);
      this.partial = !!doc.partial; this.limited = !!doc.limited;
      this.setServices(this.services);
    }
    applyPending(render = true) {
      if (!this.pendingTraces) return;
      const pending = this.pendingTraces; this.pendingTraces = null;
      this.expanded.clear(); this.visibleInspectionKeys.clear();
      this.acceptTraces(pending.doc, pending.signature);
      this.renderPending(); if (render) this.render();
    }
    renderPending() {
      const visible = !!this.pendingTraces && (this.mode === "live" || this.mode === "jobs");
      $("pending-updates").classList.toggle("hidden", !visible);
      if (!visible) return;
      const jobs = this.mode === "jobs";
      const keys = new Set(this.requests.map(request => request.key));
      const added = M.groupTraces(this.pendingTraces.doc.traces || [])
        .filter(request => scheduled(request) === jobs && !keys.has(request.key)).length;
      $("pending-update-message").textContent = `${added ? quantity(added, jobs ? "new job run" : "new request") + " available" : "Captured data changed"} · List held while inspecting; older evidence may expire on the services.`;
      $("apply-updates").textContent = jobs ? "Show latest job runs" : "Show latest requests";
    }
    captureInspection(details, key) {
      details.open = this.expanded.has(key);
      if (details.open) this.visibleInspectionKeys.add(key);
      details.ontoggle = () => {
        if (details.isConnected === false) return;
        if (details.open) { this.expanded.add(key); this.visibleInspectionKeys.add(key); }
        else { this.expanded.delete(key); this.visibleInspectionKeys.delete(key); }
      };
    }
    missingIngress(request) {
      return request.channel === "HTTP" && this.services.some(cbApp)
        && !(request.segments || []).some(segment => cbApp(service(segment)) || cbApp(segment.applicationId));
    }
    keywordCandidates() {
      if (this.durationProblem()) return [];
      return M.filterRequests(this.requests.filter(request => !scheduled(request)), this.filters, Date.now());
    }
    resetKeyword(clearInput = true) {
      this.keywordSearch?.reset(); this.keywordTraceSignature = "";
      if (clearInput) $("request-keyword").value = "";
    }
    async searchKeyword() {
      if (this.mode !== "live" || !this.keywordSearch || this.keywordSearch.state.status === "loading") return;
      this.applyPending(false);
      this.keywordTraceSignature = this.traceSignature;
      const pending = this.keywordSearch.search($("request-keyword").value, this.keywordCandidates());
      this.render();
      await pending;
      this.render();
    }
    renderKeywordSearch(candidates) {
      $("request-keyword-control").classList.toggle("hidden", this.mode !== "live");
      const status = $("request-keyword-status"); status.replaceChildren();
      const state = this.keywordSearch?.state || {status: "idle"};
      const draft = $("request-keyword").value.trim();
      const loading = state.status === "loading";
      $("request-keyword-search").disabled = !draft || loading || !!this.durationProblem();
      $("request-keyword-search").textContent = loading ? "Searching…" : "Search";
      $("request-keyword-search").setAttribute("aria-busy", String(loading));
      status.classList.toggle("hidden", this.mode !== "live" || (!draft && state.status === "idle"));
      if (this.mode !== "live") return;
      let message = "Press Search or Enter to filter by captured API parameters and request bodies. Other filters narrow the search.";
      if (loading) message = "Searching retained request data… No API requests are replayed.";
      else if (state.status === "error") message = "Request data search is unavailable. Try Search again, or clear the keyword to show the list.";
      else if (state.status === "ready") {
        const c = state.counts;
        const details = [`Searched captured request data in ${c.withData} of ${c.requested} service segments`];
        if (c.scanned > c.withData) details.push(`${c.scanned - c.withData} without captured data`);
        if (c.unavailable) details.push(`${c.unavailable} unavailable or expired`);
        if (c.unsearched) details.push(`${c.unsearched} not searched — narrow the filters and search again`);
        else if (state.limited) details.push("Search coverage is limited");
        if (state.skipped) details.push(`${state.skipped} missing segment identity — not searched`);
        if (this.keywordSearch.isStale(candidates) || this.keywordTraceSignature !== this.traceSignature)
          details.push("List changed — search again for up-to-date results");
        message = details.join(" · ") + ".";
      }
      status.append(el("span", message));
      if (state.status === "idle" || state.status === "error") status.append(button("Clear keyword", () => {
        this.resetKeyword(); this.render();
      }, "quiet-button"));
    }
    renderFilterChips() {
      const defaults = this.mode === "jobs" ? jobDefaults() : M.defaults();
      const labels = {path: "API", query: this.mode === "jobs" ? "Job / trace" : "Trace / label", user: "User", site: "Site",
        service: this.mode === "errors" ? "Failing service" : "Observed service", method: "Method", channel: "Channel",
        sinceMinutes: "Time", outcome: "Outcome", minDuration: "Min ms", maxDuration: "Max ms"};
      const active = $("active-filters"); active.replaceChildren();
      for (const [key, label] of Object.entries(labels)) {
        if (this.mode === "errors" && key === "outcome") continue;
        const value = this.filters[key];
        if (String(value ?? "") === String(defaults[key] ?? "")) continue;
        const readable = key === "sinceMinutes" ? value ? `Last ${value} min` : "All retained"
          : key === "channel" && !value ? "All request channels" : String(value || "Any");
        const chip = button(`${label}: ${readable} ×`, () => {
          this.filters[key] = defaults[key]; this.writeFilters(); this.filtersChanged();
        }, "filter-chip");
        chip.setAttribute("aria-label", `Reset ${label} filter: ${readable}`); active.append(chip);
      }
      for (const [key, label] of Object.entries({category: "Error category", httpStatus: "HTTP status", exceptionClass: "Exception"})) {
        if (this.mode !== "errors" || !this.errorFilters[key]) continue;
        active.append(button(`${label}: ${this.errorFilters[key]} ×`, () => {
          this.errorFilters[key] = ""; this.writeFilters(); this.filtersChanged();
        }, "filter-chip"));
      }
      if (this.mode === "live" && ["loading", "ready"].includes(this.keywordSearch?.state.status)) {
        const keyword = this.keywordSearch.state.keyword;
        const chip = button(`Request keyword: ${keyword} ×`, () => { this.resetKeyword(); this.render(); }, "filter-chip");
        chip.setAttribute("aria-label", "Clear request data keyword filter"); active.append(chip);
      }
      active.classList.toggle("hidden", !active.childNodes.length);
      const advanced = ["user", "site", "method", "channel", "minDuration", "maxDuration", ...(this.mode === "jobs" ? [] : ["query"])];
      const total = advanced.filter(key => String(this.filters[key] ?? "") !== String(defaults[key] ?? "")).length;
      $("more-filters-toggle").textContent = `More filters${total ? ` · ${total}` : ""}`;
      $("more-filters-toggle").setAttribute("aria-expanded", String(this.moreFiltersOpen));
      $("advanced-filters").classList.toggle("hidden", !this.moreFiltersOpen);
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
      const channels = [["HTTP requests", "HTTP"], [this.mode === "live" ? "All request channels" : "All channels", ""],
        ...(this.mode === "live" ? [] : [["Scheduled jobs", "SCHEDULER"]]), ["Queue messages", "SQS"], ["Other", "OTHER"]];
      $("channel-filter").replaceChildren(...channels.map(([label, value]) => new Option(label, value)));
      const outcomes = [["All outcomes", ""], ["Errors in any segment", "error"],
        ...(this.mode === "jobs" ? [["Succeeded", "success"], ["Skipped", "skipped"]] : [["Successful HTTP", "success"]]), ["Active", "active"],
        [this.mode === "jobs" ? "No confirmed outcome" : "Unknown", "unknown"]];
      $("outcome-filter").replaceChildren(...outcomes.map(([label, value]) => new Option(label, value)));
      for (const [key, id] of Object.entries(fields)) $(id).value = this.filters[key] ?? "";
      $("collapse-apis").checked = this.filters.collapse !== false;
      for (const [key, id] of Object.entries(errorFields)) $(id).value = this.errorFilters[key] || "";
      this.renderFilterChips();
    }
    goHome() {
      // Navigation owns the next live read; returning home never resets filters.
      this.changeMode("live", {refreshLive: false});
    }
    changeMode(mode, {refreshLive = true, filters = null} = {}) {
      if (!["live", "jobs", "errors", "pins", "performance"].includes(mode)) return;
      const changed = this.mode !== mode;
      if (changed || filters) this.resetKeyword();
      if (changed) this.applyPending(false);
      if (changed) { this.errorSequence++; clearTimeout(this.errorTimer); this.errorTimer = null; this.errorLoading = false; }
      if (mode === "jobs" && this.mode !== "jobs") {
        this.requestFilters = this.filters; this.filters = this.jobFilters;
      } else if (this.mode === "jobs" && mode !== "jobs") {
        this.jobFilters = this.filters; this.filters = this.requestFilters;
      }
      if (filters) this.filters = filters;
      if (mode === "jobs") this.filters = {...this.filters, channel: "SCHEDULER", path: "", method: ""};
      else if (mode === "live" && this.filters.channel === "SCHEDULER") this.filters.channel = "HTTP";
      this.mode = mode;
      this.options.onModeChange?.(mode);
      const selected = this.views.find(view => view.id === this.selectedView);
      if (selected && mode !== "pins" && viewMode(selected.filters) !== mode) {
        this.selectedView = ""; $("saved-view").value = ""; $("delete-view").disabled = true;
      }
      if (mode === "errors" && this.errorDocKey !== this.errorQuery()) this.errorDoc = null;
      $("live-tab").setAttribute("aria-pressed", String(mode === "live"));
      $("jobs-tab").setAttribute("aria-pressed", String(mode === "jobs"));
      $("errors-tab").setAttribute("aria-pressed", String(mode === "errors"));
      $("pins-tab").setAttribute("aria-pressed", String(mode === "pins"));
      $("performance-tab").setAttribute("aria-pressed", String(mode === "performance"));
      $("request-source-tabs").classList.toggle("hidden", mode === "pins" || mode === "performance");
      $("investigation-workflow").classList.toggle("hidden", mode === "pins" || mode === "performance");
      $("capture-workflow").classList.toggle("hidden", mode !== "pins");
      $("compare-selection").classList.toggle("hidden", mode !== "pins");
      $("live-controls").classList.toggle("hidden", mode === "pins" || mode === "performance");
      $("pin-help").classList.toggle("hidden", mode !== "pins");
      $("snapshot-import-controls").classList.toggle("hidden", mode !== "pins" || !this.capabilities?.largeTraceDetails);
      $("error-controls").classList.toggle("hidden", mode !== "errors");
      $("error-help").classList.toggle("hidden", mode !== "errors");
      $("error-state").classList.toggle("hidden", mode !== "errors");
      $("outcome-filter-control").classList.toggle("hidden", mode === "errors");
      $("collapse-apis-control").classList.toggle("hidden", mode === "errors");
      for (const id of ["path-filter-control", "method-filter-control", "channel-filter-control"]) $(id).classList.toggle("hidden", mode === "jobs");
      $("list-title").textContent = mode === "performance" ? "Performance insights" : mode === "jobs" ? "Scheduled jobs" : mode === "errors" ? "Errors" : mode === "pins" ? "Saved captures" : "Requests";
      $("list-description").textContent = mode === "jobs" ? "Inspect captured job runs without mixing them into API requests."
        : mode === "performance" ? "Find slow queries and repeated SQL patterns in captured evidence. Local/dev analysis, not continuous monitoring."
        : mode === "pins" ? "Reopen saved evidence from viewer files or this browser, even after live traces expire."
        : "Find an API call, then follow its work across services.";
      $("request-column-header").textContent = mode === "jobs" ? "Job / run" : "Request";
      $("collapse-apis-label").textContent = mode === "jobs" ? "Group repeated jobs" : "Group repeated APIs";
      $("query-filter-label").textContent = mode === "jobs" ? "Job type, job ID, trace ID or label" : "Trace ID or label";
      $("filter").placeholder = mode === "jobs" ? "Search job type, job ID or trace ID…" : "Trace ID, segment ID, or reproduction label";
      $("service-filter-label").textContent = mode === "errors" ? "Failing service" : "Observed service";
      $(mode === "jobs" ? "primary-query-slot" : "advanced-query-slot").append($("query-filter-control"));
      $("advanced-query-slot").classList.toggle("hidden", mode === "jobs");
      $("identity-column-header").classList.toggle("hidden", false);
      $("actions-column-header").classList.toggle("hidden", mode !== "pins");
      $("retention-details").classList.toggle("hidden", mode === "pins" || mode === "errors");
      $("list-filter-help").textContent = mode === "errors"
        ? "Filters inspect retained failures; they do not change recording. Duration is the root service’s local duration."
        : mode === "jobs" ? "Filters select retained job runs; they do not change recording. Linked service segments stay with each run."
        : "Filters select requests; they do not change agent recording. Duration is the root service’s local duration.";
      this.writeFilters();
      this.message(""); this.render();
      if (mode === "errors") this.run(() => this.refreshErrors());
      else if ((mode === "live" || mode === "jobs") && changed && refreshLive && this.options.refreshLive) this.run(() => this.options.refreshLive());
      else if (mode === "pins" && this.storageInitialized) this.run(() => this.refreshStored());
    }
    filtersChanged({resetKeyword = true} = {}) {
      if (resetKeyword) this.resetKeyword(false);
      this.applyPending(false); this.renderFilterChips();
      this.selectedView = ""; $("saved-view").value = ""; $("delete-view").disabled = true;
      if (this.mode !== "errors") { this.render(); return; }
      this.errorSequence++; this.errorDoc = null; this.errorFailure = ""; this.errorLoading = true;
      clearTimeout(this.errorTimer);
      this.errorTimer = setTimeout(() => { this.errorTimer = null; this.run(() => this.refreshErrors()); }, 200);
      this.render();
    }
    durationProblem(filters = this.filters) {
      const invalid = [filters.minDuration, filters.maxDuration].some(v => v !== "" && (!Number.isFinite(Number(v)) || Number(v) < 0)) ||
        (filters.minDuration !== "" && filters.maxDuration !== "" && Number(filters.minDuration) > Number(filters.maxDuration));
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
      $("snapshot-import").onchange = () => this.run(async () => {
        const input = $("snapshot-import"), file = input.files?.[0];
        if (!file) return;
        if (!this.viewerReady || !this.capabilities?.largeTraceDetails) throw new Error("Capture archive import is unavailable on this viewer.");
        input.disabled = true; this.message("Importing saved capture archive…");
        try {
          const saved = await this.viewerStore.importArchive(file);
          await this.refreshStored(); this.message(`Imported “${saved.name}”${saved.partial ? " (partial evidence)" : ""}. No live service was contacted.`);
          if (this.mode === "pins") await this.options.openPin(saved);
        } finally { input.disabled = false; input.value = ""; }
      });
      $("request-keyword").addEventListener("input", () => { this.resetKeyword(false); this.render(); });
      $("request-keyword").addEventListener("keydown", event => {
        if (event.key === "Enter") { event.preventDefault(); this.run(() => this.searchKeyword()); }
      });
      $("request-keyword-search").onclick = () => this.run(() => this.searchKeyword());
      for (const id of [...Object.values(fields), "collapse-apis"]) {
        $(id).addEventListener($(id).tagName === "SELECT" || $(id).type === "checkbox" ? "change" : "input", () => {
          this.readFilters(); this.filtersChanged({resetKeyword: id !== "collapse-apis"});
        });
      }
      for (const [key, id] of Object.entries(errorFields)) {
        $(id).addEventListener($(id).tagName === "SELECT" ? "change" : "input", () => {
          this.errorFilters[key] = $(id).value; this.filtersChanged();
        });
      }
      $("live-tab").onclick = () => this.changeMode("live");
      $("jobs-tab").onclick = () => this.changeMode("jobs");
      $("errors-tab").onclick = () => this.changeMode("errors");
      $("pins-tab").onclick = () => this.changeMode("pins");
      $("performance-tab").onclick = () => this.changeMode("performance");
      $("more-filters-toggle").onclick = () => { this.moreFiltersOpen = !this.moreFiltersOpen; this.renderFilterChips(); };
      $("apply-updates").onclick = () => this.applyPending();
      $("clear-filters").onclick = () => {
        this.resetKeyword();
        const preserved = {outcome: this.filters.outcome, collapse: this.filters.collapse};
        this.filters = {...(this.mode === "jobs" ? jobDefaults() : M.defaults()), ...(this.mode === "errors" ? preserved : {})};
        if (this.mode === "errors") this.errorFilters = errorDefaults();
        this.writeFilters(); this.filtersChanged();
      };
      $("saved-view").onchange = () => {
        const view = this.views.find(v => v.id === $("saved-view").value);
        if (!view) return;
        this.selectedView = view.id;
        const filters = Object.fromEntries(Object.entries(M.defaults()).map(([key, value]) => [key, view.filters[key] ?? value]));
        this.errorFilters = {category: view.filters.errorCategory || "", httpStatus: view.filters.errorStatus || "", exceptionClass: view.filters.errorException || ""};
        this.errorSequence++; this.errorDoc = null;
        $("delete-view").disabled = false; this.changeMode(viewMode(view.filters), {filters});
      };
      $("save-view").onclick = () => this.run(async () => {
        const name = await this.askName("Save current view", "", "Saves metadata filter settings in this browser—not trace data or the request-data keyword.");
        if (!name) return;
        const filters = {...this.filters, viewMode: this.mode === "errors" ? "errors" : this.mode === "jobs" ? "jobs" : "live"};
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
      if (!this.viewerReady) throw new Error("Viewer snapshot storage is unavailable. Refresh or restart the viewer; nothing was saved.");
      if (this.busy.has(request.key)) return;
      const name = await this.askName("Save snapshot", request.label || `${request.method || ""} ${request.path || "Request"}`,
        this.capabilities?.largeTraceDetails ? "Save retained evidence in bounded chunks on this viewer's machine. The saved link and downloadable archive survive service restarts. Storage limits, active segments and missing details are reported explicitly; existing snapshots are never overwritten."
          : "Save a new point-in-time file on this viewer's machine, including executed source lines. Its link works across browsers and after service restarts. Max 20 files, 5 MiB each, 20 MiB total. Existing snapshots are never overwritten. Active or missing segments are marked partial.");
      if (!name) return;
      this.busy.add(request.key); this.render(); this.message("Saving observed service segments…");
      try {
        const snapshot = this.capabilities?.largeTraceDetails ? await this.viewerStore.capture(request, name) : await this.options.snapshot(request);
        const saved = this.capabilities?.largeTraceDetails ? snapshot
          : await this.viewerStore.savePin({...snapshot, name, traceId: request.traceId, request: snapshot.request || request, capturedAt: Date.now()});
        await this.refreshStored(); this.message(`Saved “${name}” on this viewer${snapshot.partial ? " (partial snapshot)" : ""}. Use Copy snapshot link to reopen it in another browser.`);
        await this.options.openPin(saved);
      } finally { this.busy.delete(request.key); this.render(); }
    }
    async saveBrowserPin(pin) {
      if (!this.viewerReady) throw new Error("Viewer snapshot storage is unavailable; the browser pin was not changed.");
      const name = await this.askName("Save browser pin to viewer", pin.name,
        "Copy this saved evidence, including source lines, to a file on the viewer's machine. No live service will be read. The original browser pin is kept.");
      if (!name || this.busy.has(pin.id)) return;
      this.busy.add(pin.id);
      try {
        const full = pin.segments ? pin : await this.store.getPin(pin.id);
        if (!full) throw new Error("The browser snapshot is no longer available.");
        const saved = await this.viewerStore.savePin({...full, name});
        await this.refreshStored();
        this.message(`Saved “${name}” on the viewer; the original browser pin is unchanged.`);
        await this.options.openPin(saved);
      } finally { this.busy.delete(pin.id); }
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
        line.append(el("small", `${s.channel === "HTTP" ? `HTTP ${s.httpStatus ?? "unknown"}` : s.channel || "Unknown channel"} · ${s.status || "unknown"} · ${s.segmentId} · instance ${s.serviceInstanceId || "unknown"}`));
        if (s.captureMode || s.detailsAvailable === false || s.detailTruncated) line.append(el("small",
          [s.captureMode ? `Capture: ${s.captureMode}` : "", s.detailsAvailable === false ? "Details unavailable" : "",
            s.detailTruncated ? "Details incomplete" : ""].filter(Boolean).join(" · ")));
        details.append(line);
      }
      return details;
    }
    requestRow(request, pin, group) {
      // Stored list summaries intentionally omit root/segment objects. Older
      // browser pins may have no request summary at all; keep those captures
      // visible and openable without fetching or substituting live evidence.
      const missingSummary = !request || typeof request !== "object" || Array.isArray(request);
      request = missingSummary ? {} : request;
      const observedServices = Array.isArray(request.services) ? request.services : [];
      const tr = el("tr", null, "request-row");
      const title = el("td");
      if (group) {
        tr.classList.add("group-member", `${group.kind}-group-member`);
        if (group.last) tr.classList.add("group-member-last");
        tr.setAttribute("aria-describedby", group.headerId);
        title.append(el("small", `${this.mode === "jobs" ? "Run" : "Request"} ${group.position} of ${group.total}`, "group-member-label"));
      }
      const rawAPI = [request.method || request.channel || "", request.path || request.label || "Request"].filter(Boolean).join(" ");
      const open = button(pin ? pin.name : scheduled(request) ? request.label || "Scheduled job" : rawAPI,
        () => this.run(async () => {
          if (pin) {
            const full = await (pin.storage === "viewer" ? this.viewerStore : this.store).getPin(pin.id);
            if (!full) throw new Error("This saved capture is no longer available. No live data was substituted.");
            await this.options.openPin(full);
          } else await this.options.openRequest(request);
        }), "request-link");
      if (!pin && !scheduled(request)) {
        open.replaceChildren(el("span", `${request.method || request.channel || ""} `, "http-method"),
          el("span", compactPath(request.path || request.label || "Request"), "request-path"));
        open.title = rawAPI; open.setAttribute("aria-label", `Open request flow: ${rawAPI}`);
      }
      title.append(open);
      if (pin) {
        title.append(el("small", `${pin.storage === "viewer" ? "Viewer file" : "Browser only"} · captured ${date(pin.capturedAt)}${pin.partial ? " · PARTIAL" : " · observed capture"}`));
        if (missingSummary) title.append(el("small", "Request summary unavailable · open saved evidence for details", "missing"));
      } else if (!scheduled(request) && request.label && request.label !== rawAPI && !request.label.startsWith("auto:")) title.append(el("small", request.label));
      // A historical snapshot must not be reclassified using today's services
      // or the absence of segments in its deliberately compact list metadata.
      const ingressMissing = !pin && this.missingIngress(request);
      if (request.rootMissing || ingressMissing) {
        const warning = el("small", ingressMissing ? "cb-app capture missing · downstream evidence only" : request.rootAmbiguous
          ? "Multiple possible roots · representative segment shown"
          : "Upstream segment unavailable · representative segment shown", "missing");
        warning.title = request.rootAmbiguous
          ? "Multiple observed segments have no recorded parent. A unique request root has not been established; the displayed segment is only representative."
          : "The upstream segment may be filtered, evicted, not instrumented, or unavailable because its service is unreachable. The displayed segment is representative, not an identified request root.";
        title.append(warning);
      }
      const services = el("td", observedServices.map(name => this.displayService(name, request.segments || [])).join(", ") || "Unknown");
      services.title = `Observed services (canonical: ${observedServices.join(", ") || "unknown"}); list order is not a causal ordering`;
      const identity = el("td", null, "request-identity-cell");
      identity.append(el("span", text(request.user) || "unknown", "request-user"),
        el("small", `Site ${text(request.site) || "unknown"}`));
      const completedJob = scheduled(request) && !request.rootMissing && request.outcome === "unknown" && request.root?.status === "COMPLETED";
      const outcome = el("td");
      const knownSuccess = request.outcome === "success" && !ingressMissing && !request.rootMissing;
      const jobLabel = scheduled(request) ? ({success: "Succeeded", skipped: "Skipped", error: "Failed", active: "Running",
        unknown: request.jobOutcome ? "Unknown" : null})[request.outcome] : null;
      outcome.append(el("span", jobLabel || (completedJob ? "Completed" : ingressMissing && request.outcome !== "error"
        ? "Partial capture" : knownSuccess ? `HTTP ${request.httpStatus}` : request.outcome || "unknown"), `outcome ${ingressMissing ? "unknown" : request.outcome}`));
      if (!knownSuccess || scheduled(request)) outcome.append(el("small", scheduled(request)
        ? request.jobAuditStatus ? `Audit: ${request.jobAuditStatus}` : completedJob ? "Execution completed · business outcome unknown" : "Scheduled execution"
        : request.rootMissing || ingressMissing ? `Observed HTTP ${request.root?.httpStatus ?? "unknown"}`
        : request.httpStatus == null ? "HTTP unknown" : `Root HTTP ${request.httpStatus}`));
      if (this.mode === "errors" && !pin && Number(request.httpStatus) >= 200 && Number(request.httpStatus) < 400) {
        outcome.append(el("small", "Failure evidence despite a successful root response · recovery not established", "error-root-note"));
      }
      const actions = el("td", null, pin ? "" : "hidden");
      const details = el("details", null, "request-details");
      if (!pin) {
        this.captureInspection(details, "row:" + request.key);
        details.append(el("summary", "Details"));
        const fields = el("dl", null, "metadata-fields");
        for (const [label, value] of [[scheduled(request) ? "Job" : "Exact API", rawAPI],
          ["Trace ID", request.traceId || "unavailable"], ["User", request.user || "unknown"],
          ["Site", request.site || "unknown"], ["Started", date(request.startedAtMillis)],
          ...(scheduled(request) ? [["Job type", request.jobType || "unknown"], ["Job ID", request.jobId || "unknown"],
            ["Scheduler", request.scheduler || "unknown"], ["Job outcome", request.jobOutcome || "unknown"],
            ["Audit status", request.jobAuditStatus || "unknown"], ["Failure type", request.jobFailureType || "none observed"]] : []),
          ["Duration scope", request.rootMissing || ingressMissing ? "Downstream/local evidence only; entry duration is unavailable" : `${service(request.root)} local duration`]]) {
          fields.append(el("dt", label), el("dd", value));
        }
        details.append(fields, this.segmentDetails(request));
      }
      if (pin) {
        const storage = pin.storage === "viewer" ? this.viewerStore : this.store;
        if (pin.storage === "viewer") {
          const choice = el("label", null, "capture-compare-choice"), checkbox = el("input");
          checkbox.type = "checkbox"; checkbox.checked = this.comparisonSelection.has(pin.id);
          checkbox.disabled = !checkbox.checked && this.comparisonSelection.size >= 2;
          checkbox.setAttribute("aria-label", `Select ${pin.name} for comparison`);
          this.comparisonControls.set(pin.id, checkbox);
          checkbox.onchange = () => {
            if (checkbox.checked && this.comparisonSelection.size < 2) this.comparisonSelection.add(pin.id);
            else this.comparisonSelection.delete(pin.id);
            this.render();
            this.comparisonControls.get(pin.id)?.focus?.({preventScroll: true});
          };
          choice.append(checkbox, el("span", checkbox.checked
            ? [...this.comparisonSelection].indexOf(pin.id) === 0 ? "Before" : "After" : "Compare"));
          actions.append(choice);
          actions.append(button("Copy snapshot link", () => this.run(() => this.options.copySnapshotLink(pin.id))));
          if (pin.schemaVersion === 2) {
            const archive = el("a", "Download archive", "snapshot-download"); archive.href = this.viewerStore.downloadUrl(pin.id);
            archive.setAttribute("download", ""); actions.append(archive);
          }
        }
        else {
          const save = button("Save to viewer", () => this.run(() => this.saveBrowserPin(pin)));
          save.disabled = !this.viewerReady; actions.append(save);
        }
        actions.append(button("Rename", () => this.run(async () => {
          const name = await this.askName("Rename saved capture", pin.name, pin.storage === "viewer" ? "Changes the file snapshot's name, not its evidence or link." : "Changes the name in this browser only.");
          if (name) { await storage.renamePin(pin.id, name); await this.refreshStored(); }
        })));
        actions.append(button(pin.storage === "viewer" ? "Delete snapshot" : "Unpin", () => this.run(async () => {
          if (!confirm(`Remove “${pin.name}” from ${pin.storage === "viewer" ? "viewer" : "browser"} storage? Its saved snapshot will be deleted.${pin.storage === "viewer" ? " Its snapshot link will stop working." : ""}`)) return;
          await storage.deletePin(pin.id); await this.refreshStored();
        })));
      } else {
        const b = button(this.busy.has(request.key) ? "Saving…" : "Save snapshot", () => this.run(() => this.pin(request)));
        b.disabled = !this.viewerReady || this.busy.has(request.key); details.append(b);
      }
      if (!pin) title.append(details);
      const timing = el("td", duration(request.durationMs), "request-duration");
      if (ingressMissing && request.durationMs != null) timing.append(el("small", `${service(request.root)} local`));
      const age = request.startedAtMillis == null ? null : Math.max(0, Date.now() - request.startedAtMillis);
      const started = el("td", age == null ? "Unknown" : age < 60000 ? "Just now" : age < 3600000
        ? `${Math.floor(age / 60000)}m ago` : age < 86400000 ? `${Math.floor(age / 3600000)}h ago` : date(request.startedAtMillis), "request-started");
      started.title = date(request.startedAtMillis);
      tr.append(title, services, identity, outcome, timing, started, actions);
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
      cell.append(el("small", `End of group · ${quantity(requests.length, this.mode === "jobs" ? "run" : "request")}${unavailable ? " shown" : ""}`));
      end.append(cell); tbody.append(end);
    }
    render() {
      this.comparisonControls.clear();
      const available = new Set(this.pins.filter(pin => pin.storage === "viewer").map(pin => pin.id));
      for (const id of this.comparisonSelection) if (!available.has(id)) this.comparisonSelection.delete(id);
      $("compare-selection-count").textContent = this.comparisonSelection.size
        ? `${this.comparisonSelection.size}/2 selected · first is Before, second is After`
        : "Select two viewer captures, Before first.";
      $("compare-selected").disabled = this.comparisonSelection.size !== 2;
      $("compare-selected").onclick = () => this.options.compareSelected?.([...this.comparisonSelection]);
      $("clear-comparison-selection").disabled = this.comparisonSelection.size === 0;
      $("clear-comparison-selection").onclick = () => { this.comparisonSelection.clear(); this.render(); };
      this.visibleInspectionKeys.clear();
      this.renderFilterChips(); this.renderPending();
      // Badges describe what each tab will show with its own filters. Use the
      // same accepted data and clock as the rows, including held inspections.
      const retainedLive = this.requests.filter(request => !scheduled(request));
      const retainedJobs = this.requests.filter(scheduled);
      this.options.onPerformanceSources?.({requests: retainedLive, pins: this.pins, partial: this.partial, limited: this.limited});
      for (const id of ["results-heading", "request-table", "workflow-tools"]) $(id).classList.toggle("hidden", this.mode === "performance");
      $("performance-insights").classList.toggle("hidden", this.mode !== "performance");
      const jobCount = retainedJobs.length;
      const requestFilters = this.mode === "jobs" ? this.requestFilters : this.filters;
      const jobFilters = this.mode === "jobs" ? this.filters : this.jobFilters;
      const liveFilters = {...requestFilters, channel: requestFilters.channel === "SCHEDULER" ? "HTTP" : requestFilters.channel};
      const scheduledFilters = {...jobFilters, channel: "SCHEDULER", path: "", method: ""};
      const now = Date.now();
      const matching = (retained, filters) => this.durationProblem(filters) ? [] : M.filterRequests(retained, filters, now);
      const liveCandidates = matching(retainedLive, liveFilters);
      const matchingLive = this.mode === "live" && this.keywordSearch ? this.keywordSearch.filter(liveCandidates) : liveCandidates;
      this.renderKeywordSearch(liveCandidates);
      const matchingJobs = matching(retainedJobs, scheduledFilters);
      $("live-count").textContent = matchingLive.length;
      $("jobs-count").textContent = matchingJobs.length;
      this.renderErrorCount(requestFilters, now);
      $("pin-count").textContent = this.pins.length;
      for (const [id, label, matches, retained, noun] of [
        ["live-tab", "Live requests", matchingLive.length, retainedLive.length, "request"],
        ["jobs-tab", "Scheduled jobs", matchingJobs.length, jobCount, "job run"],
      ]) {
        const description = `${quantity(matches, `matching ${noun}`)} with this tab's filters; ${quantity(retained, `retained ${noun}`)} across all times before filters`;
        $(id).title = description;
        $(id).setAttribute("aria-label", `${label}: ${description}`);
      }
      const tbody = $("trace-rows"); tbody.replaceChildren();
      this.renderGroupIndex = 0;
      if (this.mode === "performance") { $("empty").classList.add("hidden"); return; }
      if (this.mode === "errors") { this.renderErrors(tbody); return; }
      if (this.mode === "pins") {
        for (const pin of this.pins) {
          tbody.append(this.requestRow(pin.request, pin));
        }
        $("results-summary").textContent = `${this.pins.filter(pin => pin.storage === "viewer").length} viewer files · ${this.pins.filter(pin => pin.storage !== "viewer").length} browser-only pins · live filters do not hide saved captures`;
        $("empty").textContent = "No saved captures. Find a live request and choose Save snapshot.";
        $("empty").classList.toggle("hidden", this.pins.length !== 0); this.renderEarlier(); return;
      }
      this.renderEarlier();
      const invalid = !!this.durationProblem();
      const jobs = this.mode === "jobs";
      // Partition whole requests, never individual segments: a job's downstream
      // HTTP work must stay attached to the observed job root (and vice versa).
      const tabRequests = jobs ? retainedJobs : retainedLive;
      const noun = jobs ? "job run" : "request";
      const requests = jobs ? matchingJobs : matchingLive;
      const buckets = M.bucketRequests(requests, this.filters.collapse);
      for (const bucket of buckets) {
        if (bucket.requests.length === 1 || !this.filters.collapse) {
          for (const request of bucket.requests) tbody.append(this.requestRow(request));
          continue;
        }
        const row = el("tr", null, "api-group"); const cell = el("td"); cell.colSpan = 7;
        const expanded = this.expanded.has(bucket.key);
        if (expanded) this.visibleInspectionKeys.add(bucket.key);
        row.classList.toggle("group-expanded", expanded);
        const shownBucket = jobs ? bucket.label : [bucket.requests[0].root.service, bucket.requests[0].method,
          compactPath(bucket.requests[0].root.routeTemplate || bucket.requests[0].path || bucket.requests[0].label)].filter(Boolean).join(" · ");
        const b = button(`${expanded ? "▾" : "▸"} ${shownBucket} · ${bucket.requests.length} separate ${jobs ? "runs" : "requests"}`, () => {
          if (expanded) this.expanded.delete(bucket.key); else this.expanded.add(bucket.key); this.render();
        }, "group-button");
        b.id = `request-group-${++this.renderGroupIndex}`;
        b.title = bucket.label;
        b.setAttribute("aria-expanded", String(expanded)); cell.append(b);
        const errors = bucket.requests.filter(r => r.hasErrors).length;
        cell.append(el("small", `${errors} with errors · latest ${date(bucket.latestMillis)} · grouping does not merge ${jobs ? "runs" : "requests"}`));
        if (this.missingIngress(bucket.requests[0])) cell.append(el("small", "cb-app capture missing · downstream evidence only", "missing"));
        row.append(cell); tbody.append(row);
        if (expanded) this.appendGroupMembers(tbody, bucket.requests, "api", b.id);
      }
      const segmentCount = tabRequests.reduce((total, request) => total + request.segments.length, 0);
      $("results-summary").textContent = `${quantity(requests.length, `matching ${noun}`)}${this.partial ? " · Some services are unavailable" : ""}${this.limited ? " · Upstream results limited" : ""}`;
      const keywordState = !jobs && this.keywordSearch?.state;
      if (keywordState?.status === "loading") $("results-summary").textContent = "Searching request data…";
      else if (keywordState?.status === "error") $("results-summary").textContent = "Request data search unavailable";
      $("retention-label").textContent = `${tabRequests.length} retained · Capture info`;
      $("retention-summary").textContent = `${quantity(tabRequests.length, `retained ${noun}`)} / ${quantity(segmentCount, "captured service segment")} (this tab, all retained times). Tab badges count matching requests or job runs with each tab's filters, not service segments or API groups. Only captured, retained evidence can be shown.`;
      if (this.filters.site && !tabRequests.some(r => r.segments.some(s => s.site))) {
        $("results-summary").textContent += " · Site metadata is not reported by these agents; update the agent to use the site filter. User identities are never guessed as site IDs.";
      }
      const empty = $("empty");
      empty.replaceChildren();
      if (!requests.length && keywordState && keywordState.status !== "idle") {
        const c = keywordState.counts;
        empty.append(el("p", keywordState.status === "loading" ? "Checking captured API parameters and request bodies…"
          : keywordState.status === "error" ? "The search could not be completed. Retry Search or clear the keyword."
          : c.withData ? "No keyword matches in the captured request data searched. Try another keyword or broaden the filters and search again."
          : "No searchable request data in this result. Broaden the time window, or capture a new request with API parameter/body capture enabled."));
        empty.append(button("Clear keyword", () => { this.resetKeyword(); this.render(); }));
        if (!invalid && keywordState.status !== "loading") this.renderRecovery(empty, liveFilters);
        empty.classList.remove("hidden"); return;
      }
      if (!requests.length) {
        empty.append(el("p", invalid ? this.durationProblem() : tabRequests.length
          ? `No matching ${noun}s. Current filters hide all ${quantity(tabRequests.length, `retained ${noun}`)}.`
          : `No retained ${noun}s are available in this tab. Only executions already captured and still retained can be shown.`));
        const activeFilters = jobs ? scheduledFilters : liveFilters;
        const minutes = Number(activeFilters.sinceMinutes);
        if (!invalid && tabRequests.length && Number.isFinite(minutes) && minutes > 0) {
          const cutoff = now - minutes * 60000;
          const olderMatches = matching(tabRequests, {...activeFilters, sinceMinutes: ""})
            .some(request => Number.isFinite(request.startedAtMillis) && request.startedAtMillis < cutoff);
          if (olderMatches) empty.append(el("p", `Try increasing the time window, or select All retained to see older ${noun}s.`));
        }
        if (tabRequests.length) {
          const channels = {HTTP: 0, SCHEDULER: 0, SQS: 0, OTHER: 0};
          for (const request of tabRequests) for (const segment of request.segments) {
            channels[["HTTP", "SCHEDULER", "SQS"].includes(segment.channel) ? segment.channel : "OTHER"]++;
          }
          const labels = {HTTP: "HTTP segment", SCHEDULER: "scheduled-job segment", SQS: "queue-message segment", OTHER: "other/unknown-channel segment"};
          empty.append(el("p", `Retained service segments in this tab (all times): ${Object.entries(channels)
            .filter(([, total]) => total).map(([channel, total]) => quantity(total, labels[channel])).join(" · ")}.`));
          empty.append(el("p", `Show all retained clears filters for this tab, including time. ${jobs ? "Only scheduled job runs are shown." : "Scheduled job runs stay in Scheduled jobs."} It does not change recording or recover expired data.`));
          empty.append(button("Show all retained", () => {
            this.resetKeyword();
            this.filters = {...M.defaults(), channel: jobs ? "SCHEDULER" : "", sinceMinutes: "", collapse: this.filters.collapse};
            this.writeFilters(); this.filtersChanged();
          }));
        }
        if (!jobs && jobCount) {
          empty.append(el("p", `${quantity(jobCount, "scheduled job run")} ${jobCount === 1 ? "is" : "are"} available in Scheduled jobs.`));
          empty.append(button("View scheduled jobs", () => this.changeMode("jobs")));
        }
        if (!invalid) this.renderRecovery(empty, activeFilters);
      }
      empty.classList.toggle("hidden", requests.length !== 0);
    }
    renderRecovery(container, filters) {
      if (typeof this.options.onInvestigate !== "function" || !this.options.canInvestigate?.()) return;
      const draft = window.TraceLensGuidedInvestigation?.recoveryDraft(filters, {mode: this.mode});
      if (!draft) return;
      container.append(el("p", "Check why this HTTP API is not appearing, or prepare a bounded capture of your next reproduction. Both open a form for exact backend path review; neither starts a check or watch."));
      const actions = el("div", undefined, "request-recovery-actions");
      for (const [label, intent] of [["Check capture for this API", "check"], ["Capture my next reproduction", "watch"]]) {
        actions.append(button(label, () => {
          this.run(() => {
            if (this.mode !== "live" || !this.options.canInvestigate?.()) return;
            return this.options.onInvestigate({...draft, intent});
          });
        }));
      }
      container.append(actions);
    }
    renderErrorCount(filters, now) {
      const active = this.mode === "errors";
      const doc = active && this.errorDocKey === this.errorQuery() ? this.errorDoc : null;
      const matches = active ? (doc && Number.isSafeInteger(doc.counts.matchingRequests) && doc.counts.matchingRequests >= 0 ? doc.counts.matchingRequests : null)
        : M.filterErrorRequests(this.requests, filters, this.errorFilters, now).length;
      const loading = active && this.errorLoading;
      const description = matches == null ? (loading ? "Loading matching retained failures" : "Retained error count unavailable")
        : `${quantity(matches, "unique matching retained request")} with failures and the Errors filters; not service segments or error groups${(active ? doc.partial || doc.limited : this.partial || this.limited) ? "; loaded evidence only (partial or limited)" : ""}`;
      $("errors-count").textContent = matches == null ? (loading ? "…" : "—") : matches;
      $("errors-tab").title = description;
      $("errors-tab").setAttribute("aria-label", `Errors: ${description}`);
      $("errors-tab").setAttribute("aria-busy", String(loading));
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
