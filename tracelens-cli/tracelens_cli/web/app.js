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
  };

  const $ = (sel) => document.querySelector(sel);

  async function api(path, method, maxBytes) {
    const resp = await fetch(path, { method: method || "GET" });
    if (!resp.ok) throw new Error(`${path} -> ${resp.status}`);
    if (maxBytes) {
      const reader = resp.body.getReader();
      const chunks = []; let size = 0;
      while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > maxBytes) {
          await reader.cancel();
          const error = new Error("This snapshot exceeds the 5 MiB browser pin limit; nothing was saved.");
          error.code = "PIN_TOO_LARGE"; throw error;
        }
        chunks.push(value);
      }
      const body = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
      return JSON.parse(new TextDecoder().decode(body));
    }
    return resp.json();
  }

  function notice(message) {
    $("#notice").textContent = message || "";
    $("#notice").classList.toggle("hidden", !message);
  }

  function safely(action) {
    Promise.resolve().then(action).catch((error) => notice(error.message));
  }

  // ---------- status ----------

  async function refreshStatus() {
    try {
      const s = await api("/api/summary");
      state.federated = !!s.federated;
      $("#reset").disabled = !!s.readOnly;
      $("#reset").title = s.readOnly ? "Federated view is read-only" : "clear retained traces";
      if (s.federated) {
        $("#status").textContent = `${s.services.length}/${s.configuredServices.length} services · ${s.traceCount} traces · read-only`;
        requestUI.setServices(s.configuredServices);
        notice((s.errors || []).map((e) => `${e.service}: ${e.error}`).join(" · "));
        return;
      }
      const agent = s.agentActive
        ? `<span class="ok">agent ✓ ${s.instrumentedClasses} classes</span>`
        : `<span class="bad">agent ✗ (request-level only)</span>`;
      const rec = s.recordingEnabled
        ? `<span class="ok">recording</span>`
        : `<span class="bad">paused</span>`;
      $("#status").innerHTML = `${rec} · ${agent} · ${s.traceCount} traces · ${s.totalEvents} events`;
    } catch (e) {
      $("#status").innerHTML = `<span class="bad">service unreachable</span>`;
    }
  }

  // ---------- list view ----------

  async function refreshTraces() {
    // Errors polling uses the shared summary-only grouping endpoint instead of
    // issuing a second trace-list read or fetching every request's line events.
    if (requestUI.mode === "errors") return requestUI.refreshErrors();
    const doc = await api("/api/traces");
    requestUI.setTraces(doc);
    notice((doc.errors || []).map(e => `${e.service}: ${e.error}`).join(" · "));
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  // ---------- detail view ----------

  async function openTrace(segmentId, service, instance) {
    const params = new URLSearchParams();
    if (service) params.set("service", service);
    if (instance) params.set("instance", instance);
    const doc = state.pin ? state.pin.segments.find(s => s.segmentId === segmentId &&
      (!service || (s.service || "local") === service) && (!instance || s.serviceInstanceId === instance)) :
      await api(`/api/render-data/${encodeURIComponent(segmentId)}?${params}`);
    if (!doc) throw new Error("This segment was not included in the pinned snapshot. No live data was substituted.");
    state.currentTrace = doc;
    snapshotBanner();
    renderDetail(doc);
    $("#list-view").classList.add("hidden");
    $("#journey-view").classList.add("hidden");
    $("#detail-view").classList.remove("hidden");
  }

  async function openJourney(traceId) {
    state.pin = null;
    state.currentJourney = await api(`/api/distributed-trace/${encodeURIComponent(traceId)}`);
    state.currentTrace = null;
    setTraceHash(traceId);
    renderJourney();
  }

  function setTraceHash(traceId) {
    if (!window.location || !window.history) return;
    const hash = traceId ? "#" + new URLSearchParams({trace: traceId}) : "";
    window.history.replaceState(null, "", window.location.pathname + window.location.search + hash);
  }

  function selectJourneyMode(mode) {
    state.journeyMode = mode;
    $("#journey-diagram-tab").setAttribute("aria-pressed", String(mode === "diagram"));
    $("#journey-details-tab").setAttribute("aria-pressed", String(mode === "details"));
    $("#journey-graph").classList.toggle("hidden", mode !== "diagram");
    $("#journey-tree").classList.toggle("hidden", mode !== "details");
    // The diagram already renders coverage notes; avoid duplicate warning walls.
    $("#journey-warnings").classList.toggle("hidden", mode === "diagram");
  }

  function renderJourney() {
    const doc = state.currentJourney;
    snapshotBanner();
    $("#list-view").classList.add("hidden");
    $("#detail-view").classList.add("hidden");
    $("#journey-view").classList.remove("hidden");
    $("#journey-meta").textContent = `trace ${doc.traceId} · ${doc.segments.length} service segments · ${doc.completeness}`;
    $("#journey-warnings").textContent = [doc.note, ...(doc.errors || []).map((e) => `${e.service}: ${e.error}`), ...(doc.warnings || [])].join("\n");
    if (state.graphCleanup) { state.graphCleanup(); state.graphCleanup = null; }
    const graph = $("#journey-graph"); graph.replaceChildren();
    const hasGraph = !!doc.visualization && !!window.TraceLensGraph;
    $("#journey-diagram-tab").disabled = !hasGraph;
    $("#journey-download").disabled = !hasGraph;
    $("#journey-copy-link").disabled = !!state.pin;
    if (hasGraph) {
      state.graphCleanup = window.TraceLensGraph.render(graph, doc.visualization, {
        onSelect: node => safely(() => openTrace(node.segmentId,
          state.pin || state.federated ? node.service : null, node.serviceInstanceId))
      });
    } else {
      $("#journey-warnings").textContent += "\nNo diagram was saved for this capture. Its service details remain available; no live data has been substituted.";
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
      button.textContent = `${segment.service} · ${segment.lensId || "HTTP"} · ${segment.status} · ${segment.durationMs ?? "?"} ms · source lines →`;
      button.onclick = () => safely(() => openTrace(segment.segmentId, segment.service, segment.serviceInstanceId));
      const identity = document.createElement("small");
      identity.textContent = `instance ${segment.serviceInstanceId || "unknown"} · segment ${segment.segmentId} · user ${segment.user || "unknown"}`;
      card.append(button, identity);
      for (const call of doc.httpCalls.filter((c) => c.ownerSegmentKey === segment.segmentKey)) {
        const callNode = document.createElement("div");
        callNode.className = "http-call";
        const label = document.createElement("div");
        const duration = call.durationNanos == null ? "?" : (call.durationNanos / 1e6).toFixed(2);
        label.textContent = `HTTP OUT → ${call.destinationName ? call.destinationName + " · " : ""}${call.httpMethod || "HTTP"} ${call.peer || ""}${call.path || ""} · ${call.httpStatus || call.status || "pending"} · ${duration} ms · ${call.propagationStatus || "unknown propagation"}`;
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
    if (!doc.segments.length) tree.textContent = "No segments retained on reachable configured services.";
  }

  function renderDetail(trace) {
    const a = trace.attributes || {};
    $("#trace-meta").innerHTML =
      `<b>${esc(trace.service || trace.applicationId || "local")} · ${esc(trace.lensId || "?")}</b> · ${esc(trace.user || "?")} · ` +
      `${esc(trace.entryChannel)} · ${esc(trace.status)} · ${trace.eventCount} events` +
      (a.httpStatus ? ` · http ${esc(a.httpStatus)}` : "") +
      (a.siteId ? ` · site ${esc(a.siteId)} (${esc(a.siteType || "?")})` : "") +
      (a.datadogTraceId ? ` · <span title="Datadog trace">dd:${esc(a.datadogTraceId)}</span>` : "") +
      (trace.truncated ? ` · <b>TRUNCATED</b>` : "") +
      `<small>trace ${esc(trace.traceId || "?")} · segment ${esc(trace.segmentId || "?")}</small>`;

    renderDependencies(trace);
    const boundariesOnly = $("#timeline-mode").value === "boundaries";
    const tl = $("#timeline");
    tl.innerHTML = "";
    const start = trace.startNanos || 0;
    let depth = 0;
    let lastThread = null;

    for (const e of trace.events || []) {
      if (boundariesOnly && !["EXTERNAL_CALL", "DB_ROUTE", "EXCEPTION"].includes(e.type)) continue;
      if (e.thread !== lastThread) {
        const lane = document.createElement("div");
        lane.className = "lane";
        lane.textContent = `── thread: ${shortThread(e.thread)} ──`;
        tl.appendChild(lane);
        lastThread = e.thread;
      }
      const row = document.createElement("div");
      const offsetMs = (((e.nanoTime || start) - start) / 1e6).toFixed(3);
      const indent = "  ".repeat(Math.max(0, depth));
      let cls = "ev", body = "";

      if (e.type === "METHOD_ENTER") {
        cls += " enter";
        body = `${indent}▶ ${shortClass(e.className)}.${e.methodName}`;
        depth++;
      } else if (e.type === "METHOD_EXIT") {
        depth = Math.max(0, depth - 1);
        cls += " exit";
        body = `${"  ".repeat(depth)}◀ ${shortClass(e.className)}.${e.methodName}`;
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
        body = `${indent}◆ [${boundary.kind}] ${boundary.destination} · ${boundary.operation} · ${boundary.status}`;
      } else if (e.type === "DB_ROUTE") {
        cls += " route";
        body = `${indent}◆ ${e.message || "db route"}`;
      } else {
        cls += " marker";
        body = `${indent}◆ ${e.message || e.type}`;
      }

      row.className = cls;
      row.innerHTML = `<span class="seq">#${e.seq}</span>` +
                      `<span class="t">+${offsetMs}ms</span>` +
                      `<span class="body">${esc(body)}</span>`;
      tl.appendChild(row);

      if (e.sourceLine) {
        const src = document.createElement("div");
        src.className = "ev";
        src.innerHTML = `<span class="seq"></span><span class="t"></span>` +
                        `<span class="src">${esc(indent + "  " + e.sourceLine)}</span>`;
        tl.appendChild(src);
      }
    }
    if (boundariesOnly && !tl.childNodes.length) tl.textContent = "No external event markers or exceptions were retained. HTTP observations, when available, are listed above; absence is not proof that no dependency was used.";
  }

  function renderDependencies(trace) {
    const container = $("#dependency-summary");
    container.replaceChildren();
    const summary = window.TraceLensDependencies.collect(trace);
    const title = document.createElement("h3");
    title.textContent = `Observed external boundaries (${summary.total}${summary.scanLimited ? "+" : ""})`;
    const note = document.createElement("p");
    note.className = "muted";
    note.textContent = "HTTP, queue and wrapper observations are separated from line-level noise. This list is not a synchronized timeline or a complete dependency inventory. No payloads are shown here.";
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

  $("#timeline-mode").onchange = () => { if (state.currentTrace) renderDetail(state.currentTrace); };

  function snapshotBanner() {
    for (const banner of document.querySelectorAll(".snapshot-banner")) {
      banner.classList.toggle("hidden", !state.pin);
      banner.textContent = state.pin ? `Pinned browser snapshot: ${state.pin.name} · saved ${new Date(state.pin.capturedAt).toLocaleString()}${state.pin.partial ? " · PARTIAL" : ""}. This is not live data. ${(state.pin.warnings || []).join(" ")}` : "";
    }
    $("#view-journey").disabled = !!state.pin && !state.pin.journey;
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
          if (doc.segmentId !== s.segmentId || (s.serviceInstanceId && doc.serviceInstanceId !== s.serviceInstanceId)) {
            throw new Error("Service instance changed during capture");
          }
          bytes += new TextEncoder().encode(JSON.stringify(doc)).byteLength;
          if (bytes > maxBytes) {
            fatal = new Error("Combined service snapshots exceed the 5 MiB browser pin limit; nothing was saved.");
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
      journey = {...journey, warnings: [...new Set(warnings)], note: "Point-in-time browser snapshot; service snapshots may have been captured at different instants."};
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

  const requestUI = new window.TraceLensRequestUI({api, snapshot: captureSnapshot, refreshLive: refreshTraces,
    openRequest: async request => {
      state.pin = null;
      if (request.traceId) await openJourney(request.traceId);
      else {
        state.currentJourney = null;
        const s = request.segments[0]; await openTrace(s.segmentId, state.federated ? s.service : null, s.serviceInstanceId);
      }
    },
    openSegment: async s => { state.pin = null; state.currentJourney = null; await openTrace(s.segmentId, state.federated ? s.service : null, s.serviceInstanceId); },
    openPin: async pin => {
      setTraceHash(null);
      state.pin = pin; state.currentTrace = null; state.currentJourney = pin.journey;
      if (pin.journey) renderJourney();
      else { const s = pin.segments[0]; await openTrace(s.segmentId, s.service, s.serviceInstanceId); }
    }
  });

  function showList() {
    setTraceHash(null);
    if (state.graphCleanup) { state.graphCleanup(); state.graphCleanup = null; }
    state.pin = null;
    state.currentTrace = null;
    state.currentJourney = null;
    $("#detail-view").classList.add("hidden");
    $("#list-view").classList.remove("hidden");
    $("#journey-view").classList.add("hidden");
    safely(refreshTraces);
  }

  function tick() {
    refreshStatus();
    if (state.autoRefresh && !state.currentTrace && !state.currentJourney) safely(refreshTraces);
  }

  $("#back").onclick = () => { state.currentTrace = null; state.currentJourney ? renderJourney() : showList(); };
  $("#journey-back").onclick = showList;
  $("#view-journey").onclick = () => safely(() => state.pin ? renderJourney() : openJourney(state.currentTrace.traceId));
  $("#journey-diagram-tab").onclick = () => selectJourneyMode("diagram");
  $("#journey-details-tab").onclick = () => selectJourneyMode("details");
  $("#journey-copy-link").onclick = () => safely(async () => {
    if (state.pin || !state.currentJourney) return;
    setTraceHash(state.currentJourney.traceId);
    await navigator.clipboard.writeText(window.location.href);
    notice("Request link copied. It works while this trace is retained in the configured services; it does not share a pinned snapshot.");
  });
  $("#journey-download").onclick = () => {
    const graph = state.currentJourney && state.currentJourney.visualization;
    if (!graph) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(graph, null, 2)], {type: "application/json"}));
    const anchor = document.createElement("a"); anchor.href = url;
    anchor.download = "tracelens-flow-" + String(graph.traceId || "unknown").replace(/[^a-zA-Z0-9_-]/g, "_") + ".json";
    anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  $("#refresh").onclick = () => safely(async () => { await refreshStatus(); await refreshTraces(); if (requestUI.store) await requestUI.refreshStored(); });
  $("#autorefresh").onchange = (e) => { state.autoRefresh = e.target.checked; };
  $("#reset").onclick = async () => {
    if (confirm("clear all retained traces?")) { await api("/api/reset", "POST"); refreshTraces(); }
  };

  requestUI.init();
  tick();
  async function openHashTrace() {
    const trace = new URLSearchParams(window.location?.hash?.slice(1) || "").get("trace");
    if (trace) {
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(trace)) throw new Error("Invalid trace ID in request link.");
      await openJourney(trace);
    }
  }
  if (window.addEventListener) window.addEventListener("hashchange", () => safely(openHashTrace));
  safely(openHashTrace);
  setInterval(tick, 3000);
})();
