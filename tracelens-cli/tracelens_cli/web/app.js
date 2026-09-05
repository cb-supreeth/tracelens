/* TraceLens SPA — buildless vanilla JS. Talks only to the local bridge (/api/*). */
(function () {
  "use strict";

  const state = {
    traces: [],
    filter: "",
    autoRefresh: true,
    currentTrace: null,
    timer: null,
  };

  const $ = (sel) => document.querySelector(sel);

  async function api(path, method) {
    const resp = await fetch(path, { method: method || "GET" });
    if (!resp.ok) throw new Error(`${path} -> ${resp.status}`);
    return resp.json();
  }

  // ---------- status ----------

  async function refreshStatus() {
    try {
      const s = await api("/api/summary");
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

  function fmtTime(ms) {
    return ms ? new Date(ms).toLocaleTimeString() : "—";
  }
  function fmtSize(b) {
    if (b < 1024) return b + "B";
    if (b < 1024 * 1024) return Math.round(b / 1024) + "KB";
    return (b / (1024 * 1024)).toFixed(1) + "MB";
  }

  async function refreshTraces() {
    const doc = await api("/api/traces");
    state.traces = doc.traces || [];
    renderList();
  }

  function renderList() {
    const filter = state.filter.toLowerCase();
    const rows = state.traces
      .filter((t) => !filter ||
        (t.lensId || "").toLowerCase().includes(filter) ||
        (t.user || "").toLowerCase().includes(filter))
      .sort((a, b) => b.lastActivityMillis - a.lastActivityMillis);
    const tbody = $("#trace-rows");
    tbody.innerHTML = "";
    $("#empty").classList.toggle("hidden", rows.length > 0);
    for (const t of rows) {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td>${esc(t.lensId ?? "—")}</td>
        <td>${esc(t.user ?? "—")}</td>
        <td><span class="chip ${t.entryChannel}">${t.entryChannel}</span></td>
        <td>${t.status}</td>
        <td>${t.eventCount}</td>
        <td>${fmtSize(t.approxSizeBytes)}</td>
        <td>${fmtTime(t.startedAtMillis)}</td>
        <td>${fmtTime(t.lastActivityMillis)}</td>`;
      tr.onclick = () => openTrace(t.segmentId);
      tbody.appendChild(tr);
    }
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  // ---------- detail view ----------

  async function openTrace(segmentId) {
    const doc = await api(`/api/render-data/${segmentId}`);
    state.currentTrace = doc;
    renderDetail(doc);
    $("#list-view").classList.add("hidden");
    $("#detail-view").classList.remove("hidden");
  }

  function renderDetail(trace) {
    const a = trace.attributes || {};
    $("#trace-meta").innerHTML =
      `<b>${esc(trace.lensId || "?")}</b> · ${esc(trace.user || "?")} · ` +
      `${trace.entryChannel} · ${trace.status} · ${trace.eventCount} events` +
      (a.httpStatus ? ` · http ${a.httpStatus}` : "") +
      (a.siteId ? ` · site ${esc(a.siteId)} (${esc(a.siteType || "?")})` : "") +
      (a.datadogTraceId ? ` · <span title="Datadog trace">dd:${a.datadogTraceId}</span>` : "") +
      (trace.truncated ? ` · <b>TRUNCATED</b>` : "");

    const tl = $("#timeline");
    tl.innerHTML = "";
    const start = trace.startNanos || 0;
    let depth = 0;
    let lastThread = null;

    for (const e of trace.events || []) {
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
        body = `${indent}◆ ${e.message || "external call"}`;
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

  function showList() {
    state.currentTrace = null;
    $("#detail-view").classList.add("hidden");
    $("#list-view").classList.remove("hidden");
    refreshTraces();
  }

  function tick() {
    refreshStatus();
    if (state.autoRefresh && !state.currentTrace) refreshTraces();
  }

  $("#back").onclick = showList;
  $("#filter").oninput = (e) => { state.filter = e.target.value; renderList(); };
  $("#autorefresh").onchange = (e) => { state.autoRefresh = e.target.checked; };
  $("#reset").onclick = async () => {
    if (confirm("clear all retained traces?")) { await api("/api/reset", "POST"); refreshTraces(); }
  };

  tick();
  state.timer = setInterval(tick, 3000);
})();
