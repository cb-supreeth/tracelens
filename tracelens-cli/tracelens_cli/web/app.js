/* TraceLens SPA — buildless vanilla JS. Talks only to the local bridge (/api/*). */
(function () {
  "use strict";

  const state = {
    autoRefresh: true,
    currentTrace: null,
    currentJourney: null,
    federated: false,
    pin: null,
    journeyMode: "diagram",
    graphCleanup: null,
    insightsCleanup: null,
    executionCleanup: null,
    executionRendererMode: null,
    executionVersion: 0,
    navigationVersion: 0,
    snapshotLoading: false,
    detailMode: "flow",
    serviceNames: [],
    capabilities: {},
    viewerMode: null,
    workspace: "requests",
    comparisonContext: null,
  };

  // A snapshot's model is computed once on demand, never refreshed from a live
  // segment behind the user's back (especially for historical browser pins).
  const executionModels = new WeakMap();
  const detailSources = new WeakMap(), legacyWindows = new WeakMap(), legacyNotices = new WeakMap();
  let guidedInvestigation = null, captureTools = null, performanceInsights = null;
  let journeyNavigationFeedback = null;

  const $ = (sel) => document.querySelector(sel);

  async function api(path, method, maxBytes, body, contentType) {
    const config = {method: method || "GET"};
    if (body !== undefined) { config.headers = {"Content-Type": contentType || "application/json"}; config.body = contentType ? body : JSON.stringify(body); }
    const resp = await fetch(path, config);
    if (!resp.ok) {
      const problem = await resp.json().catch(() => ({}));
      const error = new Error(typeof problem.error === "string" ? problem.error : `${path} -> ${resp.status}`);
      error.status = resp.status;
      throw error;
    }
    if (maxBytes && resp.body?.getReader) {
      const reader = resp.body.getReader();
      const chunks = []; let size = 0;
      while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > maxBytes) {
          await reader.cancel();
          const error = new Error(maxBytes === 5 * 1024 * 1024 ? "This snapshot exceeds the 5 MiB limit; nothing was saved."
            : "This detail response exceeds its bounded transfer limit. No partial payload was displayed.");
          error.code = "PIN_TOO_LARGE"; throw error;
        }
        chunks.push(value);
      }
      const body = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
      return JSON.parse(new TextDecoder().decode(body));
    }
    const doc = await resp.json();
    if (maxBytes && new TextEncoder().encode(JSON.stringify(doc)).byteLength > maxBytes) throw new Error("Detail response exceeds its bounded transfer limit.");
    return doc;
  }

  function notice(message) {
    $("#notice").textContent = message || "";
    $("#notice").classList.toggle("hidden", !message);
  }

  function safely(action) {
    Promise.resolve().then(action).catch((error) => notice(error.message));
  }

  function disposeInsights() {
    state.insightsCleanup?.(); state.insightsCleanup = null;
  }

  // ---------- status ----------

  function serviceStatus(name, summary) {
    const chip = document.createElement("span");
    const recording = summary?.recordingEnabled;
    const label = !summary ? "unreachable" : recording === true ? "recording on" :
      recording === false ? "paused" : "connected";
    chip.className = "service-status " + (!summary ? "bad" : recording === true ? "ok" : "dim");
    chip.textContent = `${name} · ${label}`;
    const details = !summary ? ["Cannot reach this service's TraceLens API; its traces may be unavailable."] : [
      "TraceLens API connected. Recording applies only to the service's configured capture scope. Connectivity does not verify that newly saved settings are active; use Check capture readiness for the intended API.",
      recording === false ? "New recording is paused; retained evidence can still be viewed." : "",
      Number.isFinite(summary.traceCount) ? `${summary.traceCount} retained service segments across all channels and times, before UI filters.` : "",
      summary.agentActive === false ? "Line agent inactive; request-level evidence only." : "",
      summary.failedClasses > 0 ? `${summary.failedClasses} class instrumentation failures reported; coverage may be incomplete.` : ""
    ];
    chip.title = details.filter(Boolean).join(" ");
    return chip;
  }

  function connectionStatus(names, services, errors = []) {
    $("#status").replaceChildren(...names.map(name => serviceStatus(name, services.find(row => row.service === name))));
    if (!names.length) $("#status").textContent = "No services configured";
    const unavailable = names.filter(name => !services.some(row => row.service === name)).length;
    $("#connection-summary").textContent = !names.length ? "No services configured" :
      unavailable ? `${names.length - unavailable}/${names.length} services connected` : `${names.length} services connected`;
    $("#connection-summary").title = "Current connection health — not request outcomes or capture completeness";
    $("#services-status").replaceChildren(...names.map(name => serviceStatus(name, services.find(row => row.service === name))));
    for (const error of errors.slice(0, 32)) {
      const detail = document.createElement("p"); detail.className = "settings-note";
      detail.textContent = `${error.service || "Service"}: ${error.error || "Connection unavailable"}`;
      $("#services-status").append(detail);
    }
  }

  function workspaceSelection(mode) {
    state.workspace = mode;
    $("#requests-workspace").setAttribute("aria-pressed", String(mode === "requests"));
    $("#services-workspace").setAttribute("aria-pressed", String(mode === "services"));
    $("#pins-tab").setAttribute("aria-pressed", String(mode === "pins"));
    $("#performance-tab").setAttribute("aria-pressed", String(mode === "performance"));
    $("#services-view").classList.toggle("hidden", mode !== "services");
  }

  function clearComparisonContext() {
    state.comparisonContext = null;
    $("#comparison-return").classList.add("hidden");
  }

  async function refreshStatus() {
    const version = state.navigationVersion;
    try {
      const s = await api("/api/summary");
      if (state.pin || savedLocation() || version !== state.navigationVersion) return;
      state.federated = !!s.federated;
      $("#reset").disabled = state.federated || !!s.readOnly;
      $("#reset").title = state.federated ? "The combined viewer cannot clear service traces or change recording. Services can still record; local snapshots can still be saved." :
        s.readOnly ? "Clearing traces is unavailable for this read-only connection; services can still record." : "Clear this service's retained traces after confirmation";
      if (s.federated) {
        const services = s.services || [];
        state.serviceNames = [...new Set([...(s.configuredServices || []), ...services.map(row => row.service)])];
        connectionStatus(state.serviceNames, services, s.errors || []);
        requestUI.setServices(state.serviceNames);
        guidedInvestigation?.setServices(state.serviceNames);
        return;
      }
      state.serviceNames = [s.applicationId || "Service"];
      guidedInvestigation?.setServices([]);
      connectionStatus(state.serviceNames, [{...s, service: state.serviceNames[0]}]);
    } catch (e) {
      if (state.pin || savedLocation() || version !== state.navigationVersion) return;
      connectionStatus(state.serviceNames.length ? state.serviceNames : ["Service"], [], [{error: e.message}]);
      $("#reset").disabled = true;
      $("#reset").title = "Cannot clear traces while service status is unavailable";
    }
  }

  // ---------- list view ----------

  async function refreshTraces() {
    // Errors polling uses the shared summary-only grouping endpoint instead of
    // issuing a second trace-list read or fetching every request's line events.
    if (requestUI.mode === "errors") return requestUI.refreshErrors();
    const doc = await api("/api/traces");
    if (state.pin || savedLocation()) return;
    requestUI.setTraces(doc);
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  // ---------- detail view ----------

  function chunked(pin = state.pin) {
    return !!window.TraceLensLargeTrace && (pin ? pin.schemaVersion === 2 && pin.storage === "viewer" : state.capabilities.largeTraceDetails === true);
  }

  async function segmentOverview(identity, pin = state.pin) {
    const source = window.TraceLensLargeTrace.create(api, identity, pin?.id);
    try {
      const trace = await source.overview();
      detailSources.set(trace, source);
      return trace;
    } catch (error) {
      if (pin || ![404, 405, 501].includes(error.status)) throw error;
      const params = new URLSearchParams();
      if (identity.service) params.set("service", identity.service);
      if (identity.serviceInstanceId) params.set("instance", identity.serviceInstanceId);
      let trace = await api(`/api/render-data/${encodeURIComponent(identity.segmentId)}?${params}`, "GET", 5 * 1024 * 1024);
      if (!trace.service && identity.service && state.viewerMode === "single") trace = {...trace, service: identity.service};
      for (const field of ["segmentId", "traceId", "serviceInstanceId", "service"]) {
        if (identity[field] && trace[field] !== identity[field]) throw new Error("Legacy segment identity changed. No other request was substituted.");
      }
      legacyNotices.set(trace, "Paged details are unavailable on this agent. This legacy read is limited to 5 MiB and 500 rendered events per window. Upgrade the agent for larger details and chunked captures.");
      return trace;
    }
  }

  async function loadBoundaryData(trace, seq) {
    const source = detailSources.get(trace);
    const matches = source ? [await source.boundary(seq)] : (trace.events || []).filter(event => String(event?.seq) === String(seq));
    const metadata = matches.length === 1 && window.TraceLensDependencies.boundaryMetadata(matches[0]);
    if (!metadata) throw new Error("The exact SQL detail is unavailable. No neighboring query was substituted.");
    return metadata;
  }

  function journeyFeedback(title, message, retry, stayOnDiagram = false) {
    if (!journeyNavigationFeedback) {
      journeyNavigationFeedback = document.createElement("div");
      journeyNavigationFeedback.className = "notice segment-navigation-notice";
      journeyNavigationFeedback.setAttribute("role", "alert");
      journeyNavigationFeedback.setAttribute("tabindex", "-1");
      $("#journey-meta").append(journeyNavigationFeedback);
    }
    const panel = journeyNavigationFeedback;
    panel.hidden = false;
    const heading = document.createElement("strong"), explanation = document.createElement("p");
    heading.textContent = title; explanation.textContent = message;
    const back = document.createElement("button"); back.type = "button";
    back.textContent = stayOnDiagram ? "Dismiss" : "Back to requests";
    back.onclick = stayOnDiagram ? () => { panel.hidden = true; } : showList;
    panel.replaceChildren(heading, explanation, back);
    if (retry) {
      const button = document.createElement("button"); button.type = "button"; button.textContent = "Retry service";
      button.onclick = () => safely(retry); panel.append(button);
    }
    panel.scrollIntoView?.({block: "center"}); panel.focus?.({preventScroll: true});
  }

  async function openTrace(segmentId, service, instance, navigation = {}) {
    workspaceSelection(state.pin ? "pins" : "requests");
    cancelInvestigation();
    performanceInsights?.setVisible(false);
    const version = ++state.navigationVersion;
    const pin = state.pin;
    if (journeyNavigationFeedback) journeyNavigationFeedback.hidden = true;
    const params = new URLSearchParams();
    if (service) params.set("service", service);
    if (instance) params.set("instance", instance);
    let doc;
    try {
      doc = navigation.evidence || (chunked(pin) ? await segmentOverview({segmentId, service, serviceInstanceId: instance}, pin) : pin ? pin.segments.find(s => s.segmentId === segmentId &&
        (!service || (s.service || "local") === service) && (!instance || s.serviceInstanceId === instance)) :
        await api(`/api/render-data/${encodeURIComponent(segmentId)}?${params}`, "GET", 5 * 1024 * 1024));
      if (!pin && !doc) { const error = new Error("No segment evidence returned"); error.status = 404; throw error; }
    } catch (error) {
      // A stale failure must not overwrite a newer selection or a return to Requests.
      if (version !== state.navigationVersion || state.pin !== pin) return;
      if (!pin && state.currentJourney) {
        const missing = error.status === 404 || error.status === 410;
        journeyFeedback(`${service || "Service"} code flow unavailable`,
          missing ? "This live segment is no longer available. It may have expired, been cleared, or belong to a restarted service. Return to Requests and reproduce the API call, then save a snapshot to keep its evidence. No other request was substituted."
            : `The service's execution evidence could not be loaded: ${error.message}. Check the service connection and retry, or return to Requests and reproduce the API call. No other request was substituted.`,
          () => openTrace(segmentId, service, instance, navigation));
        return;
      }
      throw error;
    }
    if (version !== state.navigationVersion || state.pin !== pin) return;
    if (!doc) throw new Error("This segment was not included in the pinned snapshot. No live data was substituted.");
    if (navigation.traceId && (doc.traceId !== navigation.traceId || doc.segmentId !== segmentId ||
        instance && doc.serviceInstanceId !== instance || service && doc.service && doc.service !== service)) {
      throw new Error("The returned segment does not match this boundary's captured service/request. No calling code was substituted.");
    }
    disposeExecution();
    disposeInsights();
    state.currentTrace = doc;
    state.detailMode = navigation.mode === "timeline" ? "timeline" : "flow";
    $("#back").textContent = state.currentJourney ? "← request flow" : "← requests";
    $("#segment-back").textContent = $("#back").textContent;
    snapshotBanner();
    $("#list-view").classList.add("hidden");
    $("#journey-view").classList.add("hidden");
    $("#detail-view").classList.remove("hidden");
    renderDetail(doc);
    $("#detail-view").scrollIntoView?.({block: "start"});
    $(state.detailMode === "timeline" ? "#execution-timeline-tab" : "#execution-flow-tab").focus?.({preventScroll: true});
    return doc;
  }

  async function openJourney(traceId) {
    clearComparisonContext(); workspaceSelection("requests");
    cancelInvestigation();
    performanceInsights?.setVisible(false);
    const version = ++state.navigationVersion;
    state.pin = null;
    const doc = await api(`/api/distributed-trace/${encodeURIComponent(traceId)}`);
    if (version !== state.navigationVersion) return;
    state.currentJourney = doc;
    state.currentTrace = null;
    setTraceHash(traceId);
    savedControls(false);
    renderJourney();
    return {version: state.navigationVersion, journey: state.currentJourney, pin: state.pin};
  }

  function setTraceHash(traceId) {
    if (!window.location || !window.history) return;
    const hash = traceId ? "#" + new URLSearchParams({trace: traceId}) : "";
    window.history.replaceState(null, "", window.location.pathname + window.location.search + hash);
  }

  function setSnapshotHash(id) {
    if (!window.location || !window.history) return;
    window.history.replaceState(null, "", window.location.pathname + window.location.search + window.TraceLensViewerStore.snapshotHash(id));
  }

  async function copySnapshotLink(id) {
    const link = window.location.href.split("#")[0] + window.TraceLensViewerStore.snapshotHash(id);
    await navigator.clipboard.writeText(link);
    notice("Snapshot link copied. It opens the saved file in any browser using this viewer, not live service data. This is a local link, not a public share link.");
  }

  function selectJourneyMode(mode) {
    $("#journey-share").open = false;
    state.journeyMode = mode;
    $("#journey-diagram-tab").setAttribute("aria-pressed", String(mode === "diagram"));
    $("#journey-details-tab").setAttribute("aria-pressed", String(mode === "details"));
    $("#journey-graph").classList.toggle("hidden", mode !== "diagram");
    $("#journey-tree").classList.toggle("hidden", mode !== "details");
  }

  function metadataDisclosure(label, fields, notes = []) {
    const details = document.createElement("details");
    details.className = "viewer-metadata"; details.open = false;
    const summary = document.createElement("summary"); summary.textContent = label;
    const list = document.createElement("dl"); list.className = "metadata-fields";
    for (const [key, value] of fields) {
      const term = document.createElement("dt"), description = document.createElement("dd");
      term.textContent = key; description.textContent = value ?? "Unknown";
      list.append(term, description);
    }
    details.append(summary, list);
    if (notes.length) {
      const note = document.createElement("p"); note.className = "metadata-notes";
      note.textContent = notes.filter(Boolean).join("\n"); details.append(note);
    }
    return details;
  }

  function observedIdentity(segment) {
    return window.TraceLensRequests.groupTraces([segment])[0]?.root || null;
  }

  function requestIdentity(doc) {
    const groups = window.TraceLensRequests.groupTraces(doc.segments || []);
    const request = groups.length === 1 ? groups[0] : null;
    const segment = request?.root || groups[0]?.root || null;
    // A linked child cannot become the entry point just because old snapshots
    // omitted its parent fields. A service name alone never establishes a root.
    const incoming = (doc.links || []).some(link => segment?.segmentKey && link.toSegmentKey === segment.segmentKey);
    const appNames = ["cb-app", "chargebee-app"];
    const gateway = state.serviceNames.find(name => appNames.includes(name));
    const gatewayMissing = gateway && segment?.channel === "HTTP" &&
      !groups.some(group => group.segments.some(row => appNames.includes(row.service))) ? gateway : null;
    const known = !!request && !request.rootMissing && !incoming && !gatewayMissing;
    return {segment, known, gatewayMissing};
  }

  function identityTitle(segment) {
    if (!segment) return "Request API unavailable";
    if (segment.channel === "SCHEDULER") return segment.label || "Unnamed scheduled job";
    if (segment.channel === "HTTP" || segment.path || segment.method) {
      return `${segment.method || "HTTP"} ${segment.path || "(API path not captured)"}`;
    }
    return segment.label || `${segment.channel || "Unknown channel"} execution`;
  }

  function renderIdentity(container, segment, {known = true, local = false, context = null, gatewayMissing = null} = {}) {
    container.replaceChildren();
    const heading = document.createElement("h1");
    heading.className = "request-identity-title";
    heading.textContent = identityTitle(segment);
    heading.title = heading.textContent;
    const eyebrow = document.createElement("div"); eyebrow.className = "request-identity-eyebrow";
    const kind = segment?.channel === "SCHEDULER" ? known ? "Scheduled job" : "Observed job" :
      segment && (segment.channel === "HTTP" || segment.path || segment.method)
        ? local ? "Service API" : known ? "Request API" : "Observed API" : "Observed execution";
    eyebrow.textContent = kind + (segment ? ` · ${segment.service}` : "");
    container.append(eyebrow, heading);
    if (context?.segment && (!local || context.segment.segmentId !== segment?.segmentId || context.segment.service !== segment?.service)) {
      const parent = document.createElement("p"); parent.className = "request-identity-context";
      parent.textContent = `${context.known ? "Request" : "Observed request context"}: ${identityTitle(context.segment)} · ${context.segment.service}`;
      container.append(parent);
    }
    if (!local && !known && segment) {
      const note = document.createElement("p"); note.className = "request-identity-note";
      note.textContent = gatewayMissing ? `${gatewayMissing} entry segment unavailable. Showing the observed downstream API.` :
        "Request entry is missing or ambiguous. Showing an observed execution, not an inferred entry point.";
      container.append(note);
    }
  }

  // Older snapshots retained caller-local HTTP timing in journey.httpCalls but
  // not in the saved graph projection. Add it only to a display clone after an
  // exact, unique call/owner match. Never reconstruct links or rewrite a capture.
  function graphWithHttpTiming(doc) {
    const graph = doc?.visualization;
    if (!graph || !Array.isArray(graph.nodes) || graph.nodes.length > 4096 || !Array.isArray(graph.edges)
        || graph.edges.length > 16384 || !Array.isArray(doc.httpCalls) || doc.httpCalls.length > 5000
        || !Array.isArray(doc.segments) || doc.segments.length > 512
        || graph.traceId != null && doc.traceId != null && graph.traceId !== doc.traceId) return graph;
    const text = value => typeof value === "string" && value.length > 0;
    const finite = value => (typeof value === "number" || typeof value === "string" && /^\d+(?:\.\d+)?$/.test(value))
      && Number.isFinite(Number(value)) && Number(value) >= 0 && Number(value) <= Number.MAX_SAFE_INTEGER ? Number(value) : null;
    const byId = new Map(), calls = new Map(), owners = new Map(), incoming = new Map(), graphCalls = new Map();
    for (const node of graph.nodes) {
      if (!node || !text(node.id) || byId.has(node.id)) return graph;
      byId.set(node.id, node);
      if (node.kind === "http-call") graphCalls.set(node.callKey, (graphCalls.get(node.callKey) || 0) + 1);
    }
    for (const edge of graph.edges) {
      if (!edge || !byId.has(edge.from) || !byId.has(edge.to)) return graph;
      if (!incoming.has(edge.to)) incoming.set(edge.to, []);
      incoming.get(edge.to).push(edge);
    }
    for (const row of doc.segments) if (row && text(row.segmentKey)) {
      owners.set(row.segmentKey, owners.has(row.segmentKey) ? null : row);
    }
    for (const row of doc.httpCalls) if (row && text(row.callKey)) {
      calls.set(row.callKey, calls.has(row.callKey) ? null : row);
    }
    let changed = false;
    const nodes = graph.nodes.map(node => {
      if (node.kind !== "http-call" || !text(node.callKey) || graphCalls.get(node.callKey) !== 1) return node;
      const call = calls.get(node.callKey), owner = byId.get(node.ownerId), links = incoming.get(node.id) || [];
      if (!call || owner?.kind !== "segment" || links.length !== 1 || links[0].kind !== "outbound" || links[0].from !== owner.id) return node;
      const capturedOwner = owners.get(owner.segmentKey);
      if (!capturedOwner || ["service", "serviceInstanceId", "segmentId", "segmentKey"].some(key =>
        !text(owner[key]) || node[key] !== owner[key] || capturedOwner[key] !== owner[key])
        || call.ownerSegmentKey !== owner.segmentKey || call.service !== owner.service || call.serviceInstanceId !== owner.serviceInstanceId
        || call.segmentId != null && call.segmentId !== owner.segmentId
        || call.traceId != null && doc.traceId != null && call.traceId !== doc.traceId
        || [["method", "httpMethod"], ["path", "path"], ["peer", "peer"], ["status", "status"]].some(([key, source]) =>
          node[key] != null && call[source] != null && node[key] !== call[source])) return node;
      const extra = {}, start = finite(call.startedAtMillis ?? call.startedAt);
      if (node.startedAtMillis == null && start !== null && Number.isSafeInteger(start)) extra.startedAtMillis = start;
      if (node.durationMs == null) {
        const nanos = finite(call.durationNanos), elapsed = nanos === null ? finite(call.durationMs) : nanos / 1000000;
        if (elapsed !== null) extra.durationMs = elapsed;
      }
      if (node.timingBoundary == null && typeof call.timingBoundary === "string" && /^[A-Za-z0-9-]{1,64}$/.test(call.timingBoundary)) extra.timingBoundary = call.timingBoundary;
      if (!Object.keys(extra).length) return node;
      changed = true; return {...node, ...extra};
    });
    return changed ? {...graph, nodes} : graph;
  }

  function renderJourney() {
    performanceInsights?.setVisible(false);
    state.navigationVersion++;
    disposeExecution();
    disposeInsights();
    const doc = state.currentJourney;
    journeyNavigationFeedback = null;
    snapshotBanner();
    $("#list-view").classList.add("hidden");
    $("#detail-view").classList.add("hidden");
    $("#journey-view").classList.remove("hidden");
    if (state.graphCleanup) { state.graphCleanup(); state.graphCleanup = null; }
    const graph = $("#journey-graph"); graph.replaceChildren();
    const hasGraph = !!doc.visualization && !!window.TraceLensGraph;
    const warnings = [...(doc.errors || []).map((e) => `${e.service}: ${e.error}`), ...(doc.warnings || [])];
    const liveEvidenceNote = "Refreshing the page reloads retained service segments. Older segments may expire or be lost on restart. Save a snapshot to preserve the evidence still available.";
    const notes = [doc.note, ...warnings,
      "Observed call relationships, not a shared time axis. Durations are local and must not be added across services."];
    if (!state.pin) notes.push(liveEvidenceNote);
    if (!hasGraph) notes.push(state.pin ? "No diagram was saved for this capture. Its service details remain available; no live data has been substituted."
      : "No request diagram is available from currently retained service evidence.");
    const identity = requestIdentity(doc);
    renderIdentity($("#journey-identity"), identity.segment, identity);
    const summary = document.createElement("span"); summary.className = "viewer-meta-summary";
    summary.textContent = `${doc.segments.length} service segments · ${doc.partial ? "PARTIAL" : doc.completeness || "unknown completeness"}`;
    if (!state.pin) {
      summary.textContent += " · Live retained evidence";
      summary.title = liveEvidenceNote;
    }
    $("#journey-meta").replaceChildren(summary, metadataDisclosure(`Request details${warnings.length ? ` · ${warnings.length} warning${warnings.length === 1 ? "" : "s"}` : ""}`,
      [["Trace ID", doc.traceId], [identity.known ? "Request API / job" : "Observed API / job", identityTitle(identity.segment)],
        ["Observed service", identity.segment?.service], ["Entry identification", identity.known ? "Single captured root" : "Missing or ambiguous"],
        ["Service segments", doc.segments.length], ["Completeness", doc.completeness]], notes));
    const insights = $("#journey-insights");
    if (insights) {
      insights.replaceChildren(); insights.hidden = true;
      const view = window.TraceLensRequestInsights?.render(insights, doc, {
        entry: identity,
        onOpenSegment: segment => openTrace(segment.segmentId,
          state.pin || state.federated ? segment.service : null, segment.serviceInstanceId, {traceId: doc.traceId}),
        onOpenBoundary: node => openBoundaryCode(node)
      });
      state.insightsCleanup = view?.dispose;
    }
    $("#journey-diagram-tab").disabled = !hasGraph;
    $("#journey-download").disabled = !hasGraph;
    $("#journey-copy-link").disabled = !!state.pin && state.pin.storage !== "viewer";
    $("#journey-copy-link").textContent = state.pin?.storage === "viewer" ? "Copy snapshot link" : "Copy live request link";
    $("#journey-copy-link").title = state.pin?.storage === "viewer" ? "Opens the saved file even after live traces expire" : "Works only while the live trace is retained";
    $("#journey-save").classList.toggle("hidden", state.pin?.storage === "viewer");
    $("#journey-save").textContent = state.pin ? "Save to viewer" : "Save snapshot";
    $("#journey-save").title = state.pin ? "Save this capture as a file in the viewer" :
      "Save currently retained evidence as a snapshot that survives service restarts. Already expired segments cannot be recovered.";
    if (hasGraph) {
      const pin = state.pin, version = state.navigationVersion;
      state.graphCleanup = window.TraceLensGraph.render(graph, graphWithHttpTiming(doc), {
        onSelect: node => safely(() => openTrace(node.segmentId,
          state.pin || state.federated ? node.service : null, node.serviceInstanceId)),
        onOpenBoundary: node => safely(() => openBoundaryCode(node)),
        loadRequestData: node => loadJourneySegment(doc, pin, version, node, "request-data"),
        loadBoundaryData: chunked(pin) ? async node => {
          const trace = await loadJourneySegment(doc, pin, version, node);
          return {...node, ...await loadBoundaryData(trace, node.eventSeq)};
        } : undefined
      });
    }
    selectJourneyMode(hasGraph ? state.journeyMode : "details");
    const tree = $("#journey-tree");
    tree.replaceChildren();
    const segments = new Map(doc.segments.map((s) => [s.segmentKey, s]));
    const children = new Set(doc.links.map((link) => link.toSegmentKey));
    const rendered = new Set();
    function segmentCard(segment, container) {
      if (rendered.has(segment.segmentKey)) return;
      rendered.add(segment.segmentKey);
      const card = document.createElement("div");
      card.className = "service-segment";
      const button = document.createElement("button");
      button.className = "segment-link";
      button.textContent = `${segment.service} · ${segment.lensId || "HTTP"} · ${segment.status} · ${segment.durationMs ?? "?"} ms · execution flow →`;
      button.onclick = () => safely(() => openTrace(segment.segmentId, segment.service, segment.serviceInstanceId));
      const identity = document.createElement("small");
      identity.textContent = `instance ${segment.serviceInstanceId || "unknown"} · segment ${segment.segmentId} · user ${segment.user || "unknown"}`;
      card.append(button, identity);
      for (const call of doc.httpCalls.filter((c) => c.ownerSegmentKey === segment.segmentKey)) {
        const callNode = document.createElement("div");
        callNode.className = "http-call";
        const label = document.createElement("div");
        const duration = call.durationNanos == null ? "?" : (call.durationNanos / 1e6).toFixed(2);
        label.textContent = `HTTP OUT → ${call.httpMethod || "HTTP"} ${call.destinationName || call.peer || "Unknown peer"}${call.path || ""} · ${call.httpStatus || call.status || "pending"} · ${duration} ms · ${call.propagationStatus || "unknown propagation"}`;
        if (call.peer) label.title = `Peer: ${call.peer}`;
        callNode.appendChild(label);
        if (!call.downstreamSegmentKeys.length) {
          const missing = document.createElement("small");
          missing.className = "missing";
          missing.textContent = "Downstream not observed (unconfigured, uninstrumented, pending, filtered or evicted)";
          callNode.appendChild(missing);
        }
        for (const key of call.downstreamSegmentKeys) {
          if (segments.has(key)) segmentCard(segments.get(key), callNode);
        }
        card.appendChild(callNode);
      }
      for (const call of (doc.externalCalls || []).filter(c => c.ownerSegmentKey === segment.segmentKey)) {
        const boundary = document.createElement("div");
        boundary.className = "http-call dependency-call";
        const view = window.TraceLensDependencies.summaryBoundary(call);
        const arrow = call.boundaryKind === "messaging" ? view.direction === "consume" ? " ←"
          : ["publish", "outbound"].includes(view.direction) ? " →" : "" : "";
        const label = document.createElement("div");
        label.textContent = `${view.kind}${arrow} ${view.destination} · ${view.operation} · ${view.status}`;
        const evidence = document.createElement("small");
        evidence.textContent = view.evidence;
        boundary.append(label, evidence); card.append(boundary);
      }
      container.appendChild(card);
    }
    for (const segment of doc.segments.filter((s) => !children.has(s.segmentKey))) segmentCard(segment, tree);
    // Malformed/cyclic or ambiguous context is still displayed, never silently dropped.
    for (const segment of doc.segments) segmentCard(segment, tree);
    if (!doc.segments.length) {
      tree.textContent = "No segments retained on reachable configured services.";
      if (!state.pin) journeyFeedback("Live request unavailable",
        "No service segments are currently available for this trace. It may not have been recorded, its evidence may have expired or been cleared, or a service may be unavailable. Return to Requests and reproduce the API call, or open a saved capture. Live links do not preserve evidence.");
    }
  }

  function renderDetail(trace) {
    $("#boundary-code-context").replaceChildren();
    $("#boundary-code-context").classList.add("hidden");
    const a = trace.attributes || {};
    // Use this opened segment, including caller-code navigation, never the
    // journey's entry service or the list of recording agents in the header.
    const service = [trace.service, trace.applicationId, a.applicationId]
      .find(value => typeof value === "string" && value.trim())?.trim() || "Unknown service";
    $("#segment-service-name").textContent = service;
    $("#segment-toolbar-service").textContent = service;
    const segment = observedIdentity(trace);
    renderIdentity($("#trace-identity"), segment, {local: true,
      context: state.currentJourney?.traceId === trace.traceId ? requestIdentity(state.currentJourney) : null});
    const summary = document.createElement("span"); summary.className = "viewer-meta-summary";
    summary.textContent = `${service} · ${trace.entryChannel || "Unknown channel"} · ${trace.status || "Unknown status"} · ${trace.eventCount ?? "?"} events` +
      (a.httpStatus ? ` · HTTP ${a.httpStatus}` : "") + (trace.truncated ? " · TRUNCATED" : trace.detailTruncated ? " · DETAILS INCOMPLETE" : "") +
      (trace.captureMode ? ` · ${trace.captureMode}` : "");
    const fields = [["Service", service], ["Lens", trace.lensId || "?"], ["User", trace.user || "?"],
      ["API / job", identityTitle(segment)],
      ["Channel", trace.entryChannel], ["Status", trace.status], ["Events", trace.eventCount],
      ["Trace ID", trace.traceId || "?"], ["Segment ID", trace.segmentId || "?"]];
    if (trace.serviceInstanceId) fields.push(["Service instance", trace.serviceInstanceId]);
    if (a.httpStatus) fields.push(["HTTP status", a.httpStatus]);
    if (segment.channel === "SCHEDULER") {
      for (const [label, key] of [["Job type", "jobType"], ["Job ID", "jobId"], ["Job outcome", "jobOutcome"],
        ["Audit status", "jobAuditStatus"], ["Failure type", "jobFailureType"], ["Scheduler", "scheduler"]]) {
        if (segment[key]) fields.push([label, segment[key]]);
      }
    }
    if (a.siteId) fields.push(["Site", `${a.siteId} (${a.siteType || "?"})`]);
    if (a.datadogTraceId) fields.push(["Datadog trace", a.datadogTraceId]);
    $("#trace-meta").replaceChildren(summary, metadataDisclosure("Segment details", fields));
    const source = detailSources.get(trace);
    if (legacyNotices.has(trace)) {
      const legacyNote = document.createElement("p"); legacyNote.className = "muted";
      legacyNote.textContent = legacyNotices.get(trace); $("#trace-meta").append(legacyNote);
    }
    if (source) {
      const detailState = document.createElement("p"); detailState.className = "muted";
      detailState.textContent = [trace.detailsAvailable === false ? "Detailed events are unavailable; the compact request flow remains available." : "Details load on demand in bounded pages; only the visible window is rendered.",
        trace.codeDetailAvailable === false ? "Method/line detail was not captured in this capture mode." : "",
        ...(Array.isArray(trace.dropReasons) ? trace.dropReasons : Object.entries(trace.dropReasons || {}).map(([reason, count]) => `${reason}: ${count}`)),
        ...(trace.warnings || [])].filter(Boolean).join(" ");
      $("#trace-meta").append(detailState);
    }
    const requestData = source ? lazyRequestData(trace, source) : window.TraceLensRequestData?.render(document, trace);
    if (requestData) $("#trace-meta").append(requestData);

    renderDependencies(trace);
    renderTimeline(trace);
    selectDetailMode(state.detailMode);
  }

  function lazyRequestData(trace, source) {
    const panel = document.createElement("details"), title = document.createElement("summary"), content = document.createElement("div");
    panel.className = "request-data"; title.textContent = "Request data · load captured values";
    panel.append(title, content);
    let loaded = false;
    panel.ontoggle = async () => {
      if (!panel.open || loaded) return;
      loaded = true; content.textContent = "Loading captured request data…";
      try {
        const doc = await source.requestData();
        content.replaceChildren();
        const rendered = window.TraceLensRequestData?.render(document, doc, {collapsible: false});
        if (rendered) content.append(rendered); else content.textContent = "Request data was not retained for this segment.";
      } catch (error) { content.textContent = error.message; loaded = false; }
    };
    return panel;
  }

  async function loadTimeline(trace, query = {}, direction) {
    const source = detailSources.get(trace);
    if (!source) return;
    const focused = document.activeElement;
    const restorePagingFocus = $("#timeline-pages")?.contains?.(focused);
    const pending = source.show(query, direction);
    renderTimeline(trace);
    try { const page = await pending; if (page && state.currentTrace === trace) notice(""); }
    catch (error) { if (state.currentTrace === trace) notice(error.message); }
    if (state.currentTrace === trace) {
      renderTimeline(trace);
      if (restorePagingFocus && (document.activeElement === document.body || document.activeElement === focused)) {
        (focused.disabled ? $("#timeline-mode") : focused).focus?.({preventScroll: true});
      }
    }
  }

  function renderTimeline(trace) {
    const boundariesOnly = $("#timeline-mode").value === "boundaries";
    const tl = $("#timeline");
    tl.innerHTML = "";
    tl.classList.toggle("wrap-events", $("#timeline-wrap").checked);
    const source = detailSources.get(trace), page = source?.page;
    const kind = boundariesOnly ? "boundaries" : "all";
    const filtered = source ? page?.events || [] : (trace.events || []).map((event, index) => ({...event, eventIndex: index}))
      .filter(event => !boundariesOnly || ["EXTERNAL_CALL", "DB_ROUTE", "EXCEPTION"].includes(event.type));
    const offset = source ? 0 : legacyWindows.get(trace) || 0;
    const events = source ? filtered : filtered.slice(offset, offset + 500);
    let paging = $("#timeline-pages");
    if (!paging) { paging = document.createElement("div"); paging.id = "timeline-pages"; $("#timeline-controls").append(paging); }
    if (!paging.timelineControls) {
      paging.timelineControls = {
        previous: document.createElement("button"), first: document.createElement("button"),
        next: document.createElement("button"), range: document.createElement("span")
      };
      const {range, previous, first, next} = paging.timelineControls;
      paging.replaceChildren(range, previous, first, next);
    }
    const {previous, first, next, range} = paging.timelineControls;
    previous.textContent = "Previous page"; next.textContent = "Next page";
    first.textContent = "First page"; first.type = previous.type = next.type = "button";
    paging.className = "timeline-pages";
    previous.disabled = source ? !source.history.length || source.loading : offset === 0;
    next.disabled = source ? !page?.hasMore || source.loading : offset + 500 >= filtered.length;
    first.disabled = source ? !page?.after || source.loading : offset === 0;
    range.setAttribute("aria-live", "polite");
    range.textContent = source ? source.loading ? "Loading bounded event page…" : !page ? "Timeline not loaded — open Timeline to inspect retained events."
      : `${events.length} events in this window · sequence ${events[0]?.seq ?? "—"}–${events.at(-1)?.seq ?? "—"} · captured through ${page.through}${page.partial ? " · PARTIAL" : ""}${page.warnings?.length ? " · " + page.warnings.join(" · ") : ""}`
      : `Events ${filtered.length ? offset + 1 : 0}–${Math.min(offset + 500, filtered.length)} of ${filtered.length} retained`;
    const legacyPage = nextOffset => {
      const focused = document.activeElement;
      legacyWindows.set(trace, nextOffset); renderTimeline(trace);
      if ([previous, first, next].includes(focused) && focused.disabled) $("#timeline-mode").focus?.({preventScroll: true});
    };
    previous.onclick = () => source ? safely(() => loadTimeline(trace, {after: source.history.at(-1), kind}, "previous"))
      : legacyPage(Math.max(0, offset - 500));
    next.onclick = () => source ? safely(() => loadTimeline(trace, {after: page.nextAfter, kind}, "next"))
      : legacyPage(offset + 500);
    first.onclick = () => source ? safely(() => loadTimeline(trace, {after: 0, kind}))
      : legacyPage(0);
    const start = trace.startNanos || 0;
    const threadStacks = new Map();
    let lastThread = null, lastThreadName = null, previousSeq = null;

    for (const [localIndex, e] of events.entries()) {
      if (!e || typeof e !== "object") { threadStacks.clear(); previousSeq = null; continue; }
      const eventIndex = Number.isInteger(e.eventIndex) ? e.eventIndex : localIndex + offset;
      if (boundariesOnly && !["EXTERNAL_CALL", "DB_ROUTE", "EXCEPTION"].includes(e.type)) continue;
      const sequence = typeof e.seq === "string" && /^[0-9]+$/.test(e.seq) ? Number(e.seq) : e.seq;
      const validSequence = Number.isSafeInteger(sequence) && sequence > 0;
      if (e.detailGapBefore === true || !validSequence || previousSeq !== null && sequence !== previousSeq + 1) threadStacks.clear();
      previousSeq = validSequence ? sequence : null;
      const threadId = Number.isSafeInteger(e.threadId) && e.threadId > 0 ? e.threadId : null;
      const thread = e.threadId != null ? threadId === null ? null : "id:" + threadId
        : typeof e.thread === "string" && e.thread.trim() ? "name:" + e.thread : null;
      if (thread === null) threadStacks.clear();
      else if (!threadStacks.has(thread)) threadStacks.set(thread, []);
      const stack = threadStacks.get(thread) || [];
      const descriptor = window.TraceLensDependencies.methodDescriptor(e.methodDescriptor);
      const method = typeof e.className === "string" && e.className && typeof e.methodName === "string" && e.methodName
        && (e.methodDescriptor == null || descriptor !== null) ? JSON.stringify([e.className, e.methodName, descriptor]) : null;
      if (e.type === "METHOD_EXIT" || e.type === "EXCEPTION") {
        const position = method === null ? -1 : stack.lastIndexOf(method);
        stack.length = Math.max(0, position);
      } else if (e.type === "METHOD_ENTER" && method === null) stack.length = 0;
      const depth = stack.length;
      if (thread !== lastThread || e.thread !== lastThreadName) {
        const lane = document.createElement("div");
        lane.className = "lane";
        lane.textContent = `── thread: ${shortThread(e.thread)}${threadId === null ? "" : " · runtime #" + threadId} ──`;
        tl.appendChild(lane);
        lastThread = thread; lastThreadName = e.thread;
      }
      const row = document.createElement("div");
      const offsetMs = (((e.nanoTime || start) - start) / 1e6).toFixed(3);
      const indent = "  ".repeat(Math.max(0, depth));
      let cls = "ev", body = "";

      if (e.type === "METHOD_ENTER") {
        cls += " enter";
        body = `${indent}▶ ${shortClass(e.className)}.${e.methodName}`;
        if (thread !== null && method !== null && validSequence) stack.push(method);
      } else if (e.type === "METHOD_EXIT") {
        cls += " exit";
        body = `${indent}◀ ${shortClass(e.className)}.${e.methodName}`;
      } else if (e.type === "LINE") {
        body = `${indent}· ${shortClass(e.className)}:${e.line}`;
      } else if (e.type === "EXCEPTION") {
        cls += " exception";
        const exc = (e.attributes && e.attributes.exceptionType) || "?";
        body = `${indent}✖ ${shortClass(e.className)}.${e.methodName} threw ${exc}` +
               (e.message ? `: ${e.message}` : "");
      } else if (e.type === "EXTERNAL_CALL") {
        cls += " external";
        const boundary = window.TraceLensDependencies.eventBoundary(e);
        const metadata = window.TraceLensDependencies.boundaryMetadata(e);
        const operation = window.TraceLensDependencies.isSqlExecution(metadata) ? window.TraceLensDependencies.sqlTitle(metadata) : boundary.operation;
        body = `${indent}◆ [${boundary.kind}] ${boundary.destination} · ${operation} · ${boundary.status}`;
      } else if (e.type === "DB_ROUTE") {
        cls += " route";
        body = `${indent}◆ ${e.message || "db route"}`;
      } else {
        cls += " marker";
        body = `${indent}◆ ${e.message || e.type}`;
      }

      row.className = cls;
      row.dataset.eventIndex = String(eventIndex);
      row.tabIndex = 0;
      row.setAttribute("role", "button");
      row.setAttribute("aria-label", `Inspect event ${e.seq}: ${body.trim()}`);
      row.title = "Inspect the full recorded event";
      let eventDetail = null, closeEvent = null;
      const inspect = () => {
        if (eventDetail) { closeEvent.focus?.({preventScroll: true}); return; }
        const detail = document.createElement("section"); detail.className = "timeline-event-detail";
        eventDetail = detail;
        detail.setAttribute("aria-label", `Full recorded event ${e.seq}`);
        const heading = document.createElement("strong"); heading.textContent = `Event #${e.seq} · ${e.type}`;
        const close = document.createElement("button"); close.type = "button"; close.textContent = "Close event";
        closeEvent = close;
        close.onclick = () => { detail.remove(); eventDetail = null; row.focus?.({preventScroll: true}); };
        const content = document.createElement("pre"), text = JSON.stringify(e, null, 2);
        content.textContent = text;
        detail.append(heading, close);
        const copy = window.TraceLensEvidenceCopy?.button(document, text, "Copy recorded event");
        if (copy) detail.append(copy);
        detail.append(content);
        row.after(detail); close.focus?.({preventScroll: true});
        detail.onkeydown = event => { if (event.key === "Escape") { event.preventDefault(); close.onclick(); } };
      };
      row.onclick = inspect;
      row.onkeydown = event => {
        if (event.key === "Enter" || event.key === " ") { event.preventDefault(); inspect(); }
      };
      row.innerHTML = `<span class="seq">#${esc(e.seq)}</span>` +
                      `<span class="t">+${offsetMs}ms</span>` +
                      `<span class="body">${esc(body)}</span>`;
      tl.appendChild(row);
      const jdbcBoundary = e.type === "EXTERNAL_CALL" ? window.TraceLensDependencies.boundaryMetadata(e) : null;
      const jdbcPanel = jdbcBoundary && window.TraceLensDependencies.renderJdbc(document, jdbcBoundary);
      if (jdbcPanel) {
        const disclosure = document.createElement("details"); disclosure.className = "timeline-jdbc";
        const summary = document.createElement("summary");
        summary.textContent = window.TraceLensDependencies.isSqlLimit(jdbcBoundary) ? "SQL capture limit reached"
          : `${window.TraceLensDependencies.sqlTitle(jdbcBoundary)} · SQL details${jdbcBoundary.sqlTruncated ? " · SQL truncated" : ""}`;
        disclosure.append(summary, jdbcPanel); tl.appendChild(disclosure);
      }

      if (e.sourceLine) {
        const src = document.createElement("div");
        src.className = "ev";
        src.dataset.sourceEventIndex = String(eventIndex);
        src.innerHTML = `<span class="seq"></span><span class="t"></span>` +
                        `<span class="src">${esc(indent + "  " + e.sourceLine)}</span>`;
        tl.appendChild(src);
      }
    }
    if (boundariesOnly && !tl.childNodes.length) tl.textContent = "No external event markers or exceptions were retained. HTTP observations, when available, are listed above; absence is not proof that no dependency was used.";
  }

  function disposeExecution() {
    state.executionVersion++;
    if (state.executionCleanup) state.executionCleanup();
    state.executionCleanup = null;
    state.executionRendererMode = null;
  }

  function selectDetailMode(mode) {
    if (mode !== "timeline" && state.executionCleanup && state.executionRendererMode !== mode) disposeExecution();
    state.detailMode = mode;
    $("#execution-flow-tab").setAttribute("aria-pressed", String(mode === "flow"));
    $("#execution-tree-tab").setAttribute("aria-pressed", String(mode === "tree"));
    $("#execution-timeline-tab").setAttribute("aria-pressed", String(mode === "timeline"));
    $("#execution-graph").classList.toggle("hidden", mode === "timeline");
    $("#timeline-panel").classList.toggle("hidden", mode !== "timeline");
    $("#timeline-controls").classList.toggle("hidden", mode !== "timeline");
    window.TraceLensStickyToolbar?.controller?.update();
    if (mode !== "timeline" && state.currentTrace && !state.executionCleanup) showExecution(state.currentTrace, mode);
    const source = detailSources.get(state.currentTrace);
    const trace = state.currentTrace;
    if (mode === "timeline" && source && !source.page && !source.loading) safely(() => loadTimeline(trace));
  }

  function executionInput(trace) {
    // Source text, messages, request headers/payloads and user context are not
    // needed for reconstruction. Explicit local JDBC SQL capture is the only
    // bounded raw-text exception; never forward arbitrary event attributes.
    const pick = (object, keys) => Object.fromEntries(keys.filter(key => object[key] !== undefined)
      .map(key => [key, object[key]]));
    const result = pick(trace, ["segmentId", "traceId", "service", "serviceInstanceId", "applicationId",
      "applicationVersion", "status", "entryChannel", "eventCount", "truncated"]);
    result.events = (trace.events || []).map(event => {
      if (!event || typeof event !== "object" || Array.isArray(event)) return null;
      const projected = pick(event, ["type", "seq", "nanoTime", "thread", "threadId", "className", "methodName", "methodDescriptor", "line", "detailGapBefore"]);
      const attrs = event.attributes;
      if (attrs && typeof attrs === "object" && !Array.isArray(attrs)) {
        projected.attributes = pick(attrs, ["exceptionType", "system", "route", "detail", "boundaryKind",
          "direction", "destination", "operation", "status", "evidenceKind", "errorType"]);
        const jdbc = event.type === "EXTERNAL_CALL" && window.TraceLensDependencies.jdbcDetails(attrs);
        if (jdbc) Object.assign(projected.attributes, jdbc);
      }
      return projected;
    });
    return result;
  }

  async function executionModel(trace) {
    if (!executionModels.has(trace)) {
      const pending = (async () => {
        const source = detailSources.get(trace);
        if (source) return source.execution();
        const body = JSON.stringify({trace: executionInput(trace), maxNodes: 200});
        if (new TextEncoder().encode(body).byteLength > 5 * 1024 * 1024) {
          throw new Error("Snapshot exceeds the 5 MiB flow reconstruction limit. Use the Timeline for retained evidence.");
        }
        const response = await fetch("/api/segment-execution", {
          method: "POST", headers: {"Content-Type": "application/json"}, body
        });
        if (!response.ok) throw new Error(`Flow reconstruction unavailable (HTTP ${response.status}).`);
        const model = await response.json();
        if (model.kind !== "segment-execution" || !Array.isArray(model.nodes) || !Array.isArray(model.lanes)) {
          throw new Error("Viewer returned an unsupported execution model.");
        }
        return model;
      })();
      executionModels.set(trace, pending);
      pending.catch(() => executionModels.delete(trace));
    }
    return executionModels.get(trace);
  }

  async function showExecution(trace, mode) {
    const version = ++state.executionVersion;
    const container = $("#execution-graph");
    container.textContent = mode === "tree" ? "Building observed call tree…" : "Building observed code flow…";
    container.setAttribute("aria-busy", "true");
    try {
      const renderer = mode === "tree" ? window.TraceLensExecutionGraph : window.TraceLensCodeFlow;
      if (!renderer) throw new Error("Execution renderer unavailable. Reload the updated viewer.");
      const model = await executionModel(trace);
      if (version !== state.executionVersion || state.currentTrace !== trace) return;
      // Both presentations reuse the same evidence/model. A delayed response
      // must not mount the old mode or lay out a hidden call-tree canvas.
      if (state.detailMode !== mode) { container.textContent = ""; return; }
      container.replaceChildren();
      state.executionCleanup = renderer.render(container, model, {
        trace, saved: !!state.pin, onRevealEvidence: node => revealExecutionEvidence(trace, node).catch(error => notice(error.message)),
        loadBoundaryData: detailSources.has(trace) ? node => loadBoundaryData(trace, node.evidence?.seq ?? node.boundary?.eventSeq) : undefined,
        onOpenSource: coordinate => safely(() => openSourceLocation(trace, coordinate))
      });
      state.executionRendererMode = mode;
    } catch (error) {
      if (version !== state.executionVersion || state.currentTrace !== trace) return;
      container.textContent = `${error.message} The captured Timeline is still available.`;
    } finally {
      if (version === state.executionVersion) container.setAttribute("aria-busy", "false");
    }
  }

  async function revealExecutionEvidence(trace, node) {
    if (state.currentTrace !== trace) return;
    const evidence = node.observedLines?.firstEvidence || node.entry || node.evidence;
    const index = evidence?.index;
    const source = detailSources.get(trace);
    if (!Number.isInteger(index) || index < 0 || !source && index >= (trace.events || []).length) return;
    $("#timeline-mode").value = "all";
    if (source) await loadTimeline(trace, {aroundSeq: evidence.seq ?? index + 1, kind: "all"});
    else legacyWindows.set(trace, Math.floor(index / 500) * 500);
    if (state.currentTrace !== trace) return;
    renderTimeline(trace);
    selectDetailMode("timeline");
    const row = $("#timeline").querySelector(`[data-event-index="${index}"]`);
    if (!row) { if (source) notice("The requested source event is not available in the retained page. No neighboring event was substituted; review the page's omission warnings."); return; }
    row.classList.add("evidence-highlight");
    const sourceRow = $("#timeline").querySelector(`[data-source-event-index="${index}"]`);
    if (sourceRow) sourceRow.classList.add("evidence-highlight");
    row.focus({preventScroll: true});
    row.scrollIntoView({block: "center"});
  }

  // Read one exact captured invocation without changing the selected view.
  // Saved captures never fall back to a live service, even if its name matches.
  async function loadJourneySegment(journey, pin, version, node, detail) {
    const current = () => state.currentJourney === journey && state.pin === pin && state.navigationVersion === version;
    if (!current()) throw new Error("The request view changed. Reopen the request data in the current diagram.");
    const owners = (journey.segments || []).filter(segment => segment.segmentId === node.segmentId &&
      segment.segmentKey === node.segmentKey && segment.service === node.service &&
      segment.serviceInstanceId === node.serviceInstanceId);
    if (owners.length !== 1) throw new Error("The captured service segment is missing or ambiguous. No other request was substituted.");
    const owner = owners[0];
    let trace;
    if (chunked(pin)) {
      trace = await segmentOverview(owner, pin);
      if (detail === "request-data" && detailSources.has(trace)) trace = await detailSources.get(trace).requestData();
    } else if (pin) {
      const matches = (pin.segments || []).filter(segment => segment.segmentId === owner.segmentId &&
        (segment.service || "local") === owner.service && segment.serviceInstanceId === owner.serviceInstanceId);
      if (matches.length !== 1) throw new Error("This exact segment was not included in the saved capture. No live data was substituted.");
      trace = matches[0];
    } else {
      const params = new URLSearchParams({service: owner.service, instance: owner.serviceInstanceId});
      try { trace = await api(`/api/render-data/${encodeURIComponent(owner.segmentId)}?${params}`, "GET", 5 * 1024 * 1024); }
      catch (error) {
        if (error.status === 404 || error.status === 410) throw new Error("This service segment is no longer retained. Reproduce the request and save a fresh capture. No other request was substituted.");
        throw error;
      }
    }
    if (!current()) throw new Error("The request view changed. Reopen the request data in the current diagram.");
    if (!trace || trace.traceId !== journey.traceId || trace.segmentId !== owner.segmentId ||
        trace.serviceInstanceId !== owner.serviceInstanceId || (trace.service || "local") !== owner.service ||
        trace.segmentKey && trace.segmentKey !== owner.segmentKey) {
      throw new Error("The returned segment does not match this captured service/request. No other request was substituted.");
    }
    return trace;
  }

  async function openBoundaryCode(node) {
    const journey = state.currentJourney;
    if (!journey || !["dependency-call", "http-call"].includes(node?.kind) || !window.TraceLensBoundarySource) return;
    // A dependency belongs to one explicitly captured segment. Never route by
    // destination name, host, class name or a similarly named live service.
    const owners = (journey.segments || []).filter(segment =>
      segment.segmentId === node.segmentId && segment.segmentKey === node.segmentKey &&
      segment.service === node.service && segment.serviceInstanceId === node.serviceInstanceId);
    if (owners.length !== 1) throw new Error("The owning service segment is missing or ambiguous. Calling code cannot be located safely.");
    const owner = owners[0];
    let httpCall = null;
    if (node.kind === "http-call") {
      // callKey is an exact journey-local identity, never a destination lookup.
      const calls = Array.isArray(journey.httpCalls) ? journey.httpCalls : [];
      const matches = calls.length <= 5000 && typeof node.callKey === "string"
        ? calls.filter(call => call && call.callKey === node.callKey) : [];
      if (matches.length !== 1 || matches[0].ownerSegmentKey !== owner.segmentKey ||
          matches[0].service !== owner.service || matches[0].serviceInstanceId !== owner.serviceInstanceId ||
          typeof matches[0].spanId !== "string" || !/^[0-9a-f]{16}$/.test(matches[0].spanId)) {
        throw new Error("The exact outgoing HTTP call is missing or ambiguous. Calling code cannot be located safely.");
      }
      httpCall = matches[0];
    }
    const pin = state.pin, version = state.navigationVersion;
    let trace;
    try { trace = await loadJourneySegment(journey, pin, version, node); }
    catch (error) {
      if (state.currentJourney === journey && state.pin === pin && state.navigationVersion === version)
        journeyFeedback(`Calling code in ${owner.service} unavailable`, error.message, null, true);
      return;
    }
    let evidenceTrace = trace;
    const detailSource = detailSources.get(trace);
    if (detailSource) {
      const anchor = httpCall ? httpCall.callingEventSeq : node.eventSeq;
      if (anchor) {
        const page = await detailSource.show({aroundSeq: anchor, kind: "all"});
        if (state.currentJourney !== journey || state.pin !== pin || state.navigationVersion !== version) return;
        evidenceTrace = {...trace, events: page.events};
      }
    }
    if (!state.pin && !trace.service && owner.service === "local") {
      // Single-service render-data is an untagged agent export. Bind the
      // configured "local" owner only after confirming the viewer's mode.
      const viewer = await api("/api/viewer-context");
      if (state.currentJourney !== journey || state.pin !== pin || state.navigationVersion !== version) return;
      if (viewer.mode === "single") evidenceTrace = {...trace, service: "local"};
    }
    let context;
    if (httpCall) {
      const calls = Array.isArray(trace.httpCalls) ? trace.httpCalls : [];
      const matches = calls.length <= 5000 ? calls.filter(call => call && call.spanId === httpCall.spanId) : [];
      if (matches.length !== 1 || ["httpMethod", "path", "peer"].some(field => matches[0][field] !== httpCall[field])) {
        context = {note: "The exact outgoing HTTP call is no longer available in this captured segment. No other call or source line was substituted."};
      } else context = window.TraceLensBoundarySource.resolveHttp(evidenceTrace, matches[0]);
    } else context = window.TraceLensBoundarySource.resolve(evidenceTrace, node);
    if (detailSource) {
      const toGlobal = index => Number.isInteger(index) ? evidenceTrace.events?.[index]?.eventIndex ?? index : index;
      context = {...context, eventIndex: toGlobal(context.eventIndex), sourceIndex: toGlobal(context.sourceIndex)};
    }
    if (httpCall && !context.coordinate && !(detailSource && Number.isInteger(context.eventIndex))) {
      journeyFeedback(`Calling code in ${owner.service} unavailable`, context.note +
        " To explore the receiving service instead, select its card to open its own code flow.", null, true);
      return;
    }
    const opened = await openTrace(owner.segmentId, owner.service,
      owner.serviceInstanceId, {mode: "timeline", traceId: journey.traceId, evidence: trace});
    if (!opened || state.currentTrace !== trace || state.currentJourney !== journey) return;
    const panel = $("#boundary-code-context");
    panel.replaceChildren(); panel.classList.remove("hidden");
    const heading = document.createElement("strong");
    heading.textContent = context.coordinate
      ? `Observed calling context · ${shortClass(context.coordinate.className)}${context.methodName ? "." + context.methodName + "()" : ""}:${context.coordinate.line}`
      : "Calling code unavailable";
    const note = document.createElement("span"); note.textContent = context.note;
    panel.append(heading, note);
    if (context.coordinate) {
      const source = document.createElement("button"); source.textContent = "Open local source";
      source.onclick = () => safely(() => openSourceLocation(trace, context.coordinate));
      panel.append(source);
    }
    if (!Number.isInteger(context.eventIndex)) {
      panel.scrollIntoView?.({block: "center"});
      return;
    }
    $("#timeline-mode").value = "all";
    if (!detailSource) legacyWindows.set(trace, Math.floor((context.sourceIndex ?? context.eventIndex) / 500) * 500);
    renderTimeline(trace);
    const index = Number.isInteger(context.sourceIndex) ? context.sourceIndex : context.eventIndex;
    for (const value of new Set([index, context.eventIndex])) {
      const row = $("#timeline").querySelector(`[data-event-index="${value}"]`);
      row?.classList.add("evidence-highlight");
      $("#timeline").querySelector(`[data-source-event-index="${value}"]`)?.classList.add("evidence-highlight");
    }
    const row = $("#timeline").querySelector(`[data-event-index="${index}"]`);
    if (row && context.coordinate) {
      // Keep the explicit editor action beside the highlighted context even for
      // late events whose segment header is far above the current scroll.
      const source = document.createElement("button");
      source.className = "boundary-code-source";
      source.textContent = "Open local source";
      source.title = context.note;
      source.onclick = () => safely(() => openSourceLocation(trace, context.coordinate));
      row.append(source);
    }
    row?.focus({preventScroll: true});
    row?.scrollIntoView({block: "center"});
  }

  function dependencyDisplayTrace(trace) {
    const calls = Array.isArray(trace.httpCalls) ? trace.httpCalls.slice(0, 5000) : [];
    const name = value => typeof value === "string" && /^[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}$/.test(value) ? value : "";
    const peer = value => typeof value === "string" && value.length <= 256 &&
      /^(?:[A-Za-z0-9._-]+|\[[A-Fa-f0-9:.%_-]+\])(?::[0-9]{1,5})?$/.test(value) ? value : "";
    const journey = state.currentJourney;
    const segments = journey && trace.traceId && journey.traceId === trace.traceId && Array.isArray(journey.segments)
      ? journey.segments.slice(0, 512).filter(row => row && typeof row === "object") : [];
    // A name belongs to this exact captured caller, not every call to the same
    // host. In particular, never consult live discovery to enrich a saved file.
    const owners = segments.filter(row => row.segmentId === trace.segmentId &&
      row.serviceInstanceId === trace.serviceInstanceId && (!trace.service || row.service === trace.service) &&
      (trace.segmentKey ? row.segmentKey === trace.segmentKey : !!trace.serviceInstanceId));
    const owner = owners.length === 1 ? owners[0].segmentKey : null;
    const indexed = new Map(), targets = new Map(), spans = new Map();
    for (const row of segments) {
      if (typeof row.segmentKey === "string") targets.set(row.segmentKey, targets.has(row.segmentKey) ? null : row);
    }
    for (const call of calls) {
      if (call && typeof call.spanId === "string") spans.set(call.spanId, (spans.get(call.spanId) || 0) + 1);
    }
    for (const call of owner && Array.isArray(journey.httpCalls) ? journey.httpCalls.slice(0, 5000) : []) {
      if (!call || call.ownerSegmentKey !== owner || typeof call.spanId !== "string" || !call.spanId) continue;
      indexed.set(call.spanId, indexed.has(call.spanId) ? null : call);
    }
    return {...trace, httpCalls: calls.map(call => {
      if (!call || typeof call !== "object") return call;
      const matched = spans.get(call.spanId) === 1 ? indexed.get(call.spanId) : null;
      const observed = matched && matched.peer === call.peer ? matched : null;
      const address = peer(call.peer), explicitName = name(call.destinationName), observedName = name(observed?.destinationName);
      let destinationName = (explicitName !== address ? explicitName : "") || (observedName !== address ? observedName : "");
      if (!destinationName && observed && Array.isArray(observed.downstreamSegmentKeys) && observed.downstreamSegmentKeys.length === 1) {
        destinationName = name(targets.get(observed.downstreamSegmentKeys[0])?.service);
      }
      return {...call, destinationName: destinationName && address && destinationName !== address
        ? `${destinationName} (${address})` : destinationName || address || "Unknown peer"};
    })};
  }

  function renderDependencies(trace) {
    const container = document.createElement("details");
    container.open = false;
    $("#dependency-summary").replaceChildren(container);
    const summary = window.TraceLensDependencies.collect(dependencyDisplayTrace(trace));
    summary.scanLimited ||= Array.isArray(trace.httpCalls) && trace.httpCalls.length > 5000;
    const title = document.createElement("summary");
    title.textContent = `Observed external boundaries (${summary.total}${summary.scanLimited ? "+" : ""})` +
      (summary.queryCount ? ` · ${summary.queryCount} retained SQL ${summary.queryCount === 1 ? "execution" : "executions"}` : "") +
      (summary.queryLimitCount ? " · SQL capture limit reached" : "") +
      (trace.httpCallsTruncated || trace.truncated ? " · capture truncated" : "");
    const note = document.createElement("p");
    note.className = "muted";
    note.textContent = "HTTP, queue, database and wrapper observations are separated from line-level noise. This list is not a synchronized timeline or a complete dependency inventory. Opted-in SQL and captured bindings are available in the flow cards and timeline; result rows are not captured.";
    container.append(title, note);
    const table = document.createElement("table");
    const header = document.createElement("tr");
    for (const label of ["Boundary", "Destination / system", "Operation", "Observation"]) {
      const cell = document.createElement("th"); cell.textContent = label; header.append(cell);
    }
    const head = document.createElement("thead"); head.append(header); table.append(head);
    const body = document.createElement("tbody");
    for (const row of summary.rows) {
      const tr = document.createElement("tr");
      for (const value of [row.kind, row.destination, row.operation, row.status]) {
        const td = document.createElement("td"); td.textContent = value; tr.append(td);
      }
      tr.title = row.evidence + (row.propagation ? " · Propagation: " + row.propagation : "");
      body.append(tr);
    }
    table.append(body);
    if (summary.rows.length) container.append(table);
    else { const empty = document.createElement("p"); empty.textContent = "No boundary observations retained. Unsupported clients, missing trace context or capture limits can leave gaps."; container.append(empty); }
    if (summary.omitted || summary.scanLimited) {
      const warning = document.createElement("p"); warning.textContent = `Bounded view: ${summary.omitted} additional observed boundaries not displayed${summary.scanLimited ? "; input scan limit reached" : ""}.`; container.append(warning);
    }
    if (trace.httpCallsTruncated || trace.truncated) { const warning = document.createElement("p"); warning.textContent = "Capture truncated: some dependency observations may be missing."; container.append(warning); }
  }

  function shortThread(name) {
    if (!name) return "?";
    if (name.startsWith("http-nio-")) return "http-exec-" + name.split("-").pop();
    return name;
  }
  function shortClass(fqcn) {
    return (fqcn || "?").split(".").pop();
  }

  // ---------- wiring ----------

  $("#timeline-wrap").onchange = () => $("#timeline").classList.toggle("wrap-events", $("#timeline-wrap").checked);
  $("#timeline-mode").onchange = () => {
    if (!state.currentTrace) return;
    if (detailSources.has(state.currentTrace)) {
      safely(() => loadTimeline(state.currentTrace, {after: 0, kind: $("#timeline-mode").value === "boundaries" ? "boundaries" : "all"}));
      return;
    }
    legacyWindows.set(state.currentTrace, 0);
    renderTimeline(state.currentTrace);
    $("#timeline-panel").scrollIntoView?.({block: "start"});
  };
  $("#segment-top").onclick = () => {
    $("#detail-view").scrollIntoView?.({block: "start"});
  };
  $("#execution-flow-tab").onclick = () => selectDetailMode("flow");
  $("#execution-tree-tab").onclick = () => selectDetailMode("tree");
  $("#execution-timeline-tab").onclick = () => selectDetailMode("timeline");

  function snapshotBanner() {
    for (const banner of document.querySelectorAll(".snapshot-banner")) {
      banner.classList.toggle("hidden", !state.pin);
      banner.replaceChildren();
      if (!state.pin) continue;
      const pin = state.pin, storage = pin.storage === "viewer" ? "Saved viewer snapshot" : "Browser-only snapshot";
      const status = document.createElement("strong"); status.className = "snapshot-state";
      status.textContent = `${storage} · not live${pin.partial ? " · PARTIAL" : ""}`;
      const name = document.createElement("span"); name.className = "snapshot-name";
      name.textContent = pin.name; name.title = pin.name;
      const warnings = pin.warnings || [];
      const fields = [["Capture name", pin.name], ["Captured", new Date(pin.capturedAt).toLocaleString()], ["Storage", storage]];
      if (pin.id) fields.push(["Snapshot ID", pin.id]);
      banner.append(status, name, metadataDisclosure(`Snapshot details${warnings.length ? ` · ${warnings.length} warning${warnings.length === 1 ? "" : "s"}` : ""}`,
        fields, ["This is not live data.", ...warnings]));
      if (pin.schemaVersion === 2 && pin.storage === "viewer") {
        const download = document.createElement("a"); download.textContent = "Download capture archive";
        download.href = `/api/snapshots/${window.TraceLensViewerStore.identifier(pin.id)}/download`;
        download.setAttribute("download", ""); banner.append(download);
      }
    }
    // The back button already returns to a loaded request flow. Offer discovery
    // only for a live segment opened directly, never for incomplete saved data.
    const canDiscoverJourney = !state.currentJourney && !state.pin && !!state.currentTrace?.traceId;
    $("#view-journey").classList.toggle("hidden", !canDiscoverJourney);
    $("#view-journey").disabled = !canDiscoverJourney;
  }

  async function captureSnapshot(request) {
    const federated = state.federated;
    let journey = null;
    const warnings = [];
    if (request.traceId) {
      try { journey = await api(`/api/distributed-trace/${encodeURIComponent(request.traceId)}`); }
      catch (e) { warnings.push(`Cross-service lookup unavailable: ${e.message}`); }
    }
    if (journey && !journey.segments.length) { warnings.push("Cross-service lookup had no retained segments."); journey = null; }
    // In single-service mode the list uses the application's name, while
    // discovery tags that same upstream as "default". Those are not separate
    // services: compare instance + segment within one canonical upstream scope.
    // Federated mode must retain service identity to avoid cross-service merges.
    const identity = s => JSON.stringify([federated ? s.service || s.applicationId || "local" : "default",
      s.serviceInstanceId || "", s.segmentId]);
    // Discovery may have raced with eviction. Still try every segment already
    // observed in the request list, without inventing links between them.
    const observed = [...new Map([...(request.segments || []), ...(journey ? journey.segments : [])]
      .map(s => [identity(s), s])).values()];
    const segments = [];
    let next = 0, bytes = 0, fatal = null;
    const maxBytes = window.TraceLensBrowserStore.CONSTANTS.MAX_PIN_BYTES;
    if (observed.length > 64) warnings.push("Snapshot limited to 64 observed segments.");
    async function worker() {
      while (!fatal && next < Math.min(observed.length, 64)) {
        const s = observed[next++];
        const params = new URLSearchParams();
        if (federated && s.service) params.set("service", s.service);
        if (s.serviceInstanceId) params.set("instance", s.serviceInstanceId);
        try {
          const doc = await api(`/api/render-data/${encodeURIComponent(s.segmentId)}?${params}`, "GET", maxBytes);
          if (doc.segmentId !== s.segmentId || (request.traceId && doc.traceId !== request.traceId) ||
              (s.serviceInstanceId && doc.serviceInstanceId !== s.serviceInstanceId)) {
            throw new Error("Service instance changed during capture");
          }
          bytes += new TextEncoder().encode(JSON.stringify(doc)).byteLength;
          if (bytes > maxBytes) {
            fatal = new Error("Combined service snapshots exceed the 5 MiB limit; nothing was saved.");
            break;
          }
          segments.push({...doc, service: s.service || doc.service || doc.applicationId || "local"});
          if (doc.status === "ACTIVE") warnings.push(`Segment ${s.segmentId} was still active when saved.`);
          if (doc.truncated || doc.httpCallsTruncated) warnings.push(`Segment ${s.segmentId} was truncated.`);
        } catch (e) {
          if (e.code === "PIN_TOO_LARGE") { fatal = e; break; }
          warnings.push(`Segment ${s.segmentId} could not be saved: ${e.message}`);
        }
      }
    }
    await Promise.all(Array.from({length: Math.min(3, observed.length)}, worker));
    if (fatal) throw fatal;
    if (!segments.length) throw new Error("No execution snapshots could be retrieved; nothing was pinned. The request may have been evicted.");
    const summaries = segments.map(({events, httpCalls, ...summary}) => summary);
    if (!journey) {
      warnings.push("Causal links unavailable; saved segments are shown independently.");
      journey = {traceId: request.traceId || "unavailable", segments: summaries.map(s => ({...s, segmentKey: identity(s)})),
        links: [], httpCalls: [], errors: [], partial: true, completeness: "partial snapshot"};
    } else {
      const linked = new Set(journey.segments.map(identity));
      const extra = summaries.filter(s => !linked.has(identity(s)));
      if (extra.length) {
        warnings.push("Some saved segments were absent from link discovery and are shown independently.");
        journey = {...journey, segments: [...journey.segments, ...extra.map(s => ({...s, segmentKey: identity(s)}))]};
        // Keep one graph-building implementation: do not show a stale saved
        // diagram that omits additional segments or invent browser-side links.
        delete journey.visualization;
      }
    }
    if (journey) {
      warnings.push(...(journey.warnings || []), ...(journey.errors || []).map(e => `${e.service}: ${e.error}`));
      // Keep discovery links as observed; unsaved segments remain visibly unavailable.
      journey = {...journey, warnings: [...new Set(warnings)], note: "Point-in-time snapshot; service snapshots may have been captured at different instants."};
    }
    const partial = !!(warnings.length || (journey && journey.partial) || segments.length < request.segments.length);
    if (journey && partial) { journey.partial = true; journey.completeness = "partial snapshot"; }
    if (journey.visualization && partial) {
      journey.visualization = {...journey.visualization, partial: true, completeness: "partial snapshot",
        warnings: [...new Set([...(journey.visualization.warnings || []), ...warnings])]};
    }
    const refreshed = window.TraceLensRequests.groupTraces(summaries).find(r => r.traceId === request.traceId);
    return {journey, segments, partial, warnings: [...new Set(warnings)], request: refreshed ? {...refreshed, key: request.key} : request};
  }

  async function openPin(pin) {
    clearComparisonContext(); workspaceSelection("pins");
    cancelInvestigation();
    performanceInsights?.setVisible(false);
    state.navigationVersion++;
    state.snapshotLoading = false;
    if (pin.storage === "viewer") setSnapshotHash(pin.id); else setTraceHash(null);
    state.pin = pin; state.currentTrace = null;
    // Older captures may contain a known cache wrapper invocation in their
    // saved timeline but no SDK boundary in the saved diagram. Enrich only the
    // displayed projection; keep the original capture and its evidence intact.
    state.currentJourney = window.TraceLensCacheEvidence?.augmentJourney(pin.journey, pin.segments) || pin.journey;
    savedControls(true);
    $("#status").textContent = `Saved snapshot · ${pin.storage === "viewer" ? "viewer file" : "browser only"} · not live`;
    if (pin.journey) renderJourney();
    else { const s = pin.segments[0]; await openTrace(s.segmentId, s.service, s.serviceInstanceId); }
    return {version: state.navigationVersion, journey: state.currentJourney, pin: state.pin};
  }

  function cancelInvestigation() {
    guidedInvestigation?.cancel();
  }

  function mountInvestigation() {
      if (guidedInvestigation || !window.TraceLensGuidedInvestigation) return;
      guidedInvestigation = window.TraceLensGuidedInvestigation.render($("#guided-investigation"), {
        api, services: state.federated ? state.serviceNames : [],
        onOpen: traceId => safely(() => openJourney(traceId)),
        saveTrace: async (request, guard) => {
          if (state.capabilities.largeTraceDetails) {
            if (!guard.isCurrent()) throw new Error("Watch cancelled or expired before saving; nothing was saved.");
            const saved = await requestUI.viewerStore.capture(request, `Investigation · ${guard.filters.method || ""} ${guard.filters.path}`.slice(0, 100));
            await requestUI.refreshStored(); captureTools?.refresh?.();
            return saved;
          }
          const snapshot = await captureSnapshot(request);
          if (!guard.isCurrent()) throw new Error("Watch cancelled or expired before saving; nothing was saved.");
          // This check immediately precedes the only persistent write. Polling
          // and diagnosis cannot save, alter agent scope, or replay requests.
          const saved = await requestUI.viewerStore.savePin({...snapshot,
            name: `Investigation · ${guard.filters.method || ""} ${guard.filters.path}`.slice(0, 100),
            traceId: request.traceId, request: snapshot.request || request, capturedAt: Date.now()});
          await requestUI.refreshStored();
          captureTools?.refresh?.();
          return saved;
        },
        onSaved: saved => notice(`Investigation snapshot saved${saved.partial ? " (partial evidence)" : ""}. Open it in Saved captures.`)
      });
  }

  function prepareInvestigation(draft) {
    if (state.pin || savedLocation() || state.snapshotLoading || state.currentJourney || state.currentTrace
        || !["single", "federated"].includes(state.viewerMode) || requestUI.mode !== "live") return;
    const panel = $("#investigation-panel");
    if (!panel) return;
    panel.open = true;
    mountInvestigation();
    const filters = draft?.filters;
    if (!state.federated && filters?.service && state.serviceNames.length === 1 && filters.service === state.serviceNames[0]) {
      draft = {...draft, filters: {...filters, service: ""}};
    }
    guidedInvestigation?.prepare(draft);
    panel.scrollIntoView?.({block: "start"});
  }

  function mountWorkflowTools() {
    const investigationPanel = $("#investigation-panel"), savedPanel = $("#capture-tools-panel");
    investigationPanel?.addEventListener?.("toggle", () => {
      if (!investigationPanel.open) {
        guidedInvestigation?.destroy(); guidedInvestigation = null; return;
      }
      mountInvestigation();
    });
    savedPanel?.addEventListener?.("toggle", mountCaptureTools);
  }

  function mountCaptureTools() {
      if (!$("#capture-tools-panel").open) { captureTools?.(); captureTools = null; return; }
      if (captureTools || !window.TraceLensCaptureTools) return;
      captureTools = window.TraceLensCaptureTools.render($("#capture-tools"), {
        api: (path, method, body) => api(path, method, undefined, body),
        onOpenEvidence: (reference, guard) => openComparisonEvidence(reference, guard?.isCurrent, guard?.context),
        onOpenSnapshot: id => safely(async () => {
          cancelInvestigation();
          const version = ++state.navigationVersion;
          const pin = await requestUI.viewerStore.getPin(id);
          if (version !== state.navigationVersion) return;
          if (pin.id !== id || pin.storage !== "viewer") throw new Error("Invalid saved snapshot response.");
          await openPin(pin);
        })
      });
  }

  async function openComparisonEvidence(reference, isCurrent = () => true, context = {}) {
    if (!isCurrent()) return;
    if (!reference || ["snapshotId", "service", "segmentId", "serviceInstanceId"].some(key =>
      typeof reference[key] !== "string" || !reference[key] || reference[key].length > 256)) {
      throw new Error("Incomplete saved evidence identity. No other request was substituted.");
    }
    cancelInvestigation();
    const version = ++state.navigationVersion;
    const pin = await requestUI.viewerStore.getPin(reference.snapshotId);
    if (!isCurrent() || version !== state.navigationVersion) return;
    if (pin.id !== reference.snapshotId || pin.storage !== "viewer") throw new Error("Invalid saved snapshot response.");
    const matches = (pin.segments || []).filter(segment => segment.service === reference.service &&
      segment.segmentId === reference.segmentId && segment.serviceInstanceId === reference.serviceInstanceId);
    if (matches.length !== 1) throw new Error("The exact saved service evidence is missing or ambiguous. No live data was substituted.");
    const opened = await openPin(pin);
    if (!isCurrent() || state.pin !== pin || state.navigationVersion !== opened.version) return;
    const trace = await openTrace(reference.segmentId, reference.service, reference.serviceInstanceId, {traceId: pin.traceId});
    if (!isCurrent() || !trace || state.pin !== pin || state.currentTrace !== trace) return;
    state.comparisonContext = {scroll: context.scroll ?? window.scrollY ?? 0, focus: context.focus};
    $("#comparison-context").textContent = `${context.side === "after" ? "After" : "Before"} · ${reference.service}${context.label ? " · " + context.label : ""}`;
    $("#comparison-return").classList.remove("hidden");
    const panel = $("#boundary-code-context");
    const heading = document.createElement("strong"), note = document.createElement("span");
    heading.textContent = "Saved comparison evidence";
    note.textContent = "Exact captured service invocation. This is saved evidence, not the current running service.";
    panel.replaceChildren(heading, note); panel.classList.remove("hidden");
    if (typeof reference.className !== "string" || typeof reference.methodName !== "string") return;
    const descriptor = reference.methodDescriptor == null ? null : reference.methodDescriptor;
    if (descriptor !== null && (typeof descriptor !== "string" || !descriptor.length || descriptor.length > 2048)) return;
    const method = `${reference.className.slice(0, 512)}.${reference.methodName.slice(0, 256)}${descriptor || "()"}`;
    note.textContent = `${method} · Locating retained method evidence…`;
    try {
      const model = await executionModel(trace);
      if (!isCurrent() || state.pin !== pin || state.currentTrace !== trace) return;
      const nodes = (model.nodes || []).filter(node => node.kind === "invocation" &&
        node.className === reference.className && node.methodName === reference.methodName &&
        (node.methodDescriptor || null) === descriptor);
      if (nodes.length === 1 && Number.isInteger(nodes[0].entry?.index)) {
        note.textContent = `${method} · Highlighting a retained invocation. Current source may differ from the captured build; no cross-run invocation alignment is implied.`;
        await revealExecutionEvidence(trace, nodes[0]);
      } else note.textContent = `${method} · ${nodes.length ? "Multiple retained invocations match; none was selected automatically." : "This method is outside the available execution model."} Use Code flow search or Timeline to inspect saved observations; no neighboring method was substituted.`;
    } catch (error) {
      if (isCurrent() && state.pin === pin && state.currentTrace === trace) note.textContent = `${method} · Method detail unavailable: ${error.message}`;
    }
  }

  async function openSourceLocation(trace, coordinate) {
    const version = state.navigationVersion;
    const params = new URLSearchParams({className: coordinate.className, line: String(coordinate.line)});
    // Saved deep links deliberately skip live status reads. Obtain only the
    // viewer's configured mode instead of relying on live navigation state.
    const context = await api("/api/viewer-context");
    if (context.mode === "federated" && trace.service) params.set("service", trace.service);
    const location = await api("/api/source-location?" + params);
    if (version !== state.navigationVersion || state.currentTrace !== trace) return;
    const dialog = document.createElement("dialog");
    dialog.className = "source-location-dialog";
    dialog.setAttribute("aria-label", "Open current local source");
    const title = document.createElement("h2"); title.textContent = "Open current local source";
    const note = document.createElement("p"); note.textContent = location.note;
    const path = document.createElement("code"); path.textContent = `${location.path}:${location.line}`;
    const actions = document.createElement("div"); actions.className = "source-location-actions";
    for (const [key, label, protocol] of [["cursor", "Cursor", "cursor:"], ["vscode", "VS Code", "vscode:"], ["idea", "IntelliJ IDEA", "idea:"]]) {
      const href = location.editors?.[key];
      if (typeof href !== "string" || !href.startsWith(protocol + "//")) continue;
      const link = document.createElement("a"); link.textContent = label; link.href = href;
      actions.append(link);
    }
    const copy = document.createElement("button"); copy.textContent = "Copy path";
    copy.onclick = () => safely(async () => {
      await navigator.clipboard.writeText(`${location.path}:${location.line}`); copy.textContent = "Copied";
    });
    const close = document.createElement("button"); close.textContent = "Close"; close.onclick = () => dialog.close();
    actions.append(copy, close); dialog.append(title, note, path, actions);
    dialog.addEventListener("close", () => dialog.remove(), {once: true});
    document.body.append(dialog); dialog.showModal();
  }

  async function openPerformanceRequest(request) {
    state.pin = null;
    if (request.traceId) return await openJourney(request.traceId);
    else {
      state.currentJourney = null;
      const s = request.segments[0]; await openTrace(s.segmentId, state.federated ? s.service : null, s.serviceInstanceId);
    }
  }

  async function navigatePerformance(action) {
    try { return await action(); }
    finally {
      // Failed or superseded reads may leave the list visible. Do not strand
      // its controller in the hidden state set at navigation start.
      performanceInsights?.setVisible(requestUI.mode === "performance" && !$("#list-view").classList.contains("hidden"));
    }
  }

  const requestUI = new window.TraceLensRequestUI({api, snapshot: captureSnapshot, refreshLive: refreshTraces, copySnapshotLink,
    compareSelected: ids => safely(async () => {
      const panel = $("#capture-tools-panel"); panel.open = true;
      mountCaptureTools();
      await captureTools?.selectPair?.(ids);
      panel.scrollIntoView?.({block: "start"});
    }),
    viewerContext: context => { state.capabilities = context.capabilities || {}; state.viewerMode = context.mode; },
    onInvestigate: prepareInvestigation,
    canInvestigate: () => !state.pin && !savedLocation() && !state.snapshotLoading && !state.currentJourney && !state.currentTrace
      && ["single", "federated"].includes(state.viewerMode),
    onModeChange: mode => {
      state.navigationVersion++;
      cancelInvestigation(); performanceInsights?.setVisible(mode === "performance");
      workspaceSelection(["pins", "performance"].includes(mode) ? mode : "requests");
    },
    onPerformanceSources: sources => performanceInsights?.setSources(sources),
    openRequest: async request => {
      state.pin = null;
      if (request.traceId) await openJourney(request.traceId);
      else {
        state.currentJourney = null;
        const s = request.segments[0]; await openTrace(s.segmentId, state.federated ? s.service : null, s.serviceInstanceId);
      }
    },
    openSegment: async s => { state.pin = null; state.currentJourney = null; await openTrace(s.segmentId, state.federated ? s.service : null, s.serviceInstanceId); },
    openPin
  });

  // No event exports on tab entry/polling: load a bounded graph only on Analyze.
  // Saved analyses use the stored visualization, never a live substitute.
  async function performanceRead(path) {
    try { return await api(path, "GET", 5 * 1024 * 1024); }
    catch (error) {
      if (error.code === "PIN_TOO_LARGE") throw new Error("This evidence exceeds the 5 MiB analysis read limit. It was not analyzed.");
      throw error;
    }
  }
  async function loadPerformancePin(summary, guard) {
    const pin = summary.storage === "viewer"
      ? await performanceRead(`/api/snapshots/${window.TraceLensViewerStore.identifier(summary.id)}`)
      : await requestUI.store?.getPin(summary.id);
    if (!pin || pin.id !== summary.id || summary.storage === "viewer" && pin.storage !== "viewer")
      throw new Error("This saved capture is unavailable. No live evidence was substituted.");
    if (!pin.journey?.visualization) throw new Error("No SQL request graph was saved in this capture. No live evidence was substituted.");
    if (pin.journey.visualization.traceId !== pin.traceId) throw new Error("Saved graph identity does not match this capture. It was not analyzed.");
    const graph = chunked(pin) ? await window.TraceLensLargeTrace.hydrateSql(api, pin.journey.visualization, pin.id, guard?.isCurrent)
      : pin.journey.visualization;
    return {...pin, graph};
  }

  if (window.TraceLensPerformanceUI) {
    performanceInsights = window.TraceLensPerformanceUI.create($("#performance-insights"), {
      loadRequest: async (request, guard) => {
        if (!request.traceId) throw new Error("This request has no trace identity for graph analysis. Open its retained segment instead.");
        const graph = await performanceRead(`/api/trace-visualization/${encodeURIComponent(request.traceId)}`);
        if (graph.traceId !== request.traceId) throw new Error("Returned graph identity does not match this request. It was not analyzed.");
        return chunked(null) ? window.TraceLensLargeTrace.hydrateSql(api, graph, undefined, guard?.isCurrent) : graph;
      },
      loadPin: loadPerformancePin,
      onOpenRequest: request => safely(() => navigatePerformance(() => openPerformanceRequest(request))),
      onOpenPin: pin => safely(() => navigatePerformance(() => openPin(pin))),
      onOpenBoundary: ({request, pin, node}) => safely(() => navigatePerformance(async () => {
        const opened = pin ? await openPin(pin) : await openPerformanceRequest(request);
        // Navigation and rendering may each advance the version. Use the
        // successful navigation's actual state, not an assumed increment.
        if (!opened || state.navigationVersion !== opened.version
            || state.currentJourney !== opened.journey || state.pin !== opened.pin) return;
        await openBoundaryCode(node);
      }))
    });
    performanceInsights.setVisible(false);
  }

  function showList({readLive = true, preserveComparison = false} = {}) {
    cancelInvestigation();
    state.navigationVersion++;
    state.snapshotLoading = false;
    disposeExecution();
    disposeInsights();
    setTraceHash(null);
    if (state.graphCleanup) { state.graphCleanup(); state.graphCleanup = null; }
    state.pin = null;
    state.currentTrace = null;
    state.currentJourney = null;
    savedControls(false);
    $("#detail-view").classList.add("hidden");
    $("#list-view").classList.remove("hidden");
    $("#journey-view").classList.add("hidden");
    $("#services-view").classList.add("hidden");
    workspaceSelection(["pins", "performance"].includes(requestUI.mode) ? requestUI.mode : "requests");
    if (!preserveComparison) state.comparisonContext = null;
    $("#comparison-return").classList.add("hidden");
    performanceInsights?.setVisible(requestUI.mode === "performance");
    if (readLive) { refreshStatus(); safely(refreshTraces); }
    else {
      $("#connection-summary").textContent = "Connection status not checked";
      $("#status").textContent = "Open Services & capture settings to check current connections.";
    }
  }

  function savedLocation() {
    return new URLSearchParams(window.location?.hash?.slice(1) || "").has("snapshot");
  }

  function savedControls(saved) {
    $("#journey-back").textContent = saved ? "← Saved captures" : "← Requests";
    $("#autorefresh").disabled = saved;
    $("#autorefresh").checked = !saved && state.autoRefresh;
    $("#autorefresh").title = saved ? "Saved evidence does not poll live services" : "Refresh live requests";
    if (saved) {
      $("#connection-summary").textContent = "Saved capture · offline evidence";
      $("#reset").disabled = true;
      $("#reset").title = "Live reset is unavailable while viewing a saved snapshot";
    }
  }

  function tick() {
    if (state.pin || savedLocation()) return;
    refreshStatus();
    if (state.autoRefresh && state.workspace === "requests" && !state.currentTrace && !state.currentJourney) safely(refreshTraces);
  }

  $("#back").onclick = $("#segment-back").onclick = () => {
    state.currentTrace = null; state.currentJourney ? renderJourney() : backToList();
  };
  $("#home").onclick = () => {
    requestUI.goHome();
    showList();
    $("#list-view").scrollIntoView?.({block: "start"});
    $("#live-tab").focus?.({preventScroll: true});
  };
  function openWorkspace(mode) {
    showList({readLive: false});
    requestUI.changeMode?.(mode === "services" || mode === "requests" ? "live" : mode, {refreshLive: false});
    workspaceSelection(mode);
    if (mode === "requests") { refreshStatus(); safely(refreshTraces); }
    if (mode === "services") { $("#list-view").classList.add("hidden"); refreshStatus(); }
    $("#workspace-navigation").scrollIntoView?.({block: "start"});
  }
  function backToList() {
    if (state.pin || savedLocation()) openWorkspace("pins");
    else showList();
  }
  $("#requests-workspace").onclick = () => openWorkspace("requests");
  $("#services-workspace").onclick = () => openWorkspace("services");
  $("#back-to-comparison").onclick = () => {
    const context = state.comparisonContext;
    showList({readLive: false, preserveComparison: true});
    requestUI.changeMode?.("pins", {refreshLive: false}); workspaceSelection("pins");
    $("#capture-tools-panel").open = true;
    context?.focus?.focus?.({preventScroll: true});
    window.scrollTo?.({top: context?.scroll || 0});
  };
  $("#journey-back").onclick = backToList;
  $("#view-journey").onclick = () => safely(() => {
    if (!state.pin && !state.currentJourney && state.currentTrace?.traceId) return openJourney(state.currentTrace.traceId);
  });
  $("#journey-diagram-tab").onclick = () => selectJourneyMode("diagram");
  $("#journey-details-tab").onclick = () => selectJourneyMode("details");
  $("#journey-share").onkeydown = (event) => {
    if (event.key !== "Escape") return;
    $("#journey-share").open = false;
    $("#journey-share-toggle").focus();
    event.preventDefault();
  };
  $("#journey-share").onfocusout = (event) => {
    if (event.relatedTarget && !$("#journey-share").contains(event.relatedTarget)) $("#journey-share").open = false;
  };
  if (window.addEventListener) window.addEventListener("click", (event) => {
    const share = $("#journey-share");
    if (share.open && !share.contains(event.target)) share.open = false;
  });
  $("#journey-save").onclick = () => safely(async () => {
    if (state.pin) return requestUI.saveBrowserPin(state.pin);
    const request = window.TraceLensRequests.groupTraces(state.currentJourney?.segments || [])[0];
    if (!request) throw new Error("No retained request is available to save.");
    await requestUI.pin(request);
  });
  $("#journey-copy-link").onclick = () => safely(async () => {
    $("#journey-share").open = false;
    $("#journey-share-toggle").focus({preventScroll: true});
    if (state.pin?.storage === "viewer") return copySnapshotLink(state.pin.id);
    if (state.pin || !state.currentJourney) return;
    setTraceHash(state.currentJourney.traceId);
    await navigator.clipboard.writeText(window.location.href);
    notice("Request link copied. It works while this trace is retained in the configured services; it does not share a pinned snapshot.");
  });
  $("#journey-download").onclick = () => {
    $("#journey-share").open = false;
    $("#journey-share-toggle").focus({preventScroll: true});
    const graph = state.currentJourney && state.currentJourney.visualization;
    if (!graph) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(graph, null, 2)], {type: "application/json"}));
    const anchor = document.createElement("a"); anchor.href = url;
    anchor.download = "tracelens-flow-" + String(graph.traceId || "unknown").replace(/[^a-zA-Z0-9_-]/g, "_") + ".json";
    anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  $("#refresh").onclick = () => safely(async () => {
    if (!state.pin && !savedLocation()) { await refreshStatus(); await refreshTraces(); }
    await requestUI.refreshStored();
  });
  $("#autorefresh").onchange = (e) => { state.autoRefresh = e.target.checked; };
  $("#reset").onclick = async () => {
    if (state.pin || savedLocation() || $("#reset").disabled) return;
    if (confirm("clear all retained traces?")) { await api("/api/reset", "POST"); refreshTraces(); }
  };

  const viewerReady = Promise.resolve(requestUI.init());
  viewerReady.then(() => {
    $("#pins-tab").onclick = () => openWorkspace("pins");
    $("#performance-tab").onclick = () => openWorkspace("performance");
  });
  mountWorkflowTools();
  tick();
  async function openHashTrace() {
    clearComparisonContext();
    cancelInvestigation();
    const params = new URLSearchParams(window.location?.hash?.slice(1) || "");
    const snapshot = params.get("snapshot");
    if (params.has("snapshot")) {
      workspaceSelection("pins");
      performanceInsights?.setVisible(false);
      const version = ++state.navigationVersion;
      state.snapshotLoading = true;
      savedControls(true);
      state.pin = null; state.currentTrace = null; state.currentJourney = null;
      disposeExecution();
      disposeInsights();
      if (state.graphCleanup) { state.graphCleanup(); state.graphCleanup = null; }
      $("#list-view").classList.add("hidden");
      $("#detail-view").classList.add("hidden");
      $("#journey-view").classList.remove("hidden");
      $("#journey-graph").replaceChildren(); $("#journey-tree").replaceChildren();
      $("#journey-insights")?.replaceChildren();
      if ($("#journey-insights")) $("#journey-insights").hidden = true;
      $("#journey-identity").replaceChildren();
      $("#journey-meta").textContent = "Loading saved snapshot…";
      $("#status").textContent = "Saved snapshot · loading file · not live";
      $("#journey-copy-link").disabled = true; $("#journey-download").disabled = true;
      $("#journey-save").classList.add("hidden");
      notice(""); snapshotBanner();
      try {
        const id = window.TraceLensViewerStore.identifier(snapshot);
        // Saved links never substitute current live evidence or a previous view.
        const pin = await api(`/api/snapshots/${id}`);
        if (version !== state.navigationVersion) return;
        if (pin.id !== id || pin.storage !== "viewer") throw new Error("Invalid saved snapshot response.");
        await openPin(pin);
      } catch (error) {
        if (version !== state.navigationVersion) return;
        $("#journey-meta").textContent = "Saved snapshot unavailable";
        $("#status").textContent = "Saved snapshot · unavailable · no live substitution";
        notice(error.message);
      }
      return;
    }
    const trace = params.get("trace");
    if (trace) {
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(trace)) throw new Error("Invalid trace ID in request link.");
      await openJourney(trace);
    } else if (params.get("investigate") === "1") {
      const service = params.get("service") || "";
      if (state.pin || state.currentJourney || state.currentTrace || state.snapshotLoading) showList();
      requestUI.goHome();
      const version = state.navigationVersion;
      const handoffLocation = window.location.hash;
      await refreshStatus();
      if (version !== state.navigationVersion || window.location.hash !== handoffLocation) return;
      if (service && !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(service)) throw new Error("Invalid service in setup handoff. Open Start an investigation and select a configured service.");
      if (!["single", "federated"].includes(state.viewerMode)) {
        notice("Live capture setup is unavailable in this viewer. Open a configured local viewer to verify your first trace.");
        return;
      }
      if (service && !state.serviceNames.includes(service)) {
        notice(`The setup service ${service} is not listed by this viewer. Select the intended configured service before checking capture readiness.`);
      }
      prepareInvestigation({source: "setup", intent: "check", filters: {
        service
      }});
    } else if (state.pin || state.currentJourney || state.currentTrace || state.snapshotLoading) backToList();
  }
  if (window.addEventListener) window.addEventListener("hashchange", () => safely(openHashTrace));
  safely(async () => { await viewerReady; await openHashTrace(); });
  setInterval(tick, 3000);
})();
