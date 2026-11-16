/* Explicit, bounded analysis of captured JDBC evidence. No background collection. */
(function (root, factory) {
  const api = factory(root);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensPerformanceUI = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (root) {
  "use strict";
  const MAX_REQUESTS = 10, MAX_FINDINGS = 200, MAX_OCCURRENCES = 200;
  const thresholdFields = [
    ["slowQueryMs", "Slow query ≥ ms", 100, 1, 600000, false],
    ["repeatedQueryCount", "Repeated queries ≥", 3, 2, 1000, true],
    ["nPlusOneCount", "Suspected N+1 ≥", 5, 2, 1000, true],
    ["queryHeavyCount", "Query-heavy segment ≥", 50, 1, 1000, true]
  ];
  const categories = [
    ["slow-query", "Slow queries"], ["repeated-query", "Repeated queries"],
    ["suspected-n-plus-one", "Suspected N+1"], ["query-heavy", "Query-heavy segments"]
  ];
  const text = (value, max = 600) => (typeof value === "string" || typeof value === "number")
    ? String(value).replace(/[\u0000-\u0008\u000b-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, " ").slice(0, max) : "";
  const list = value => Array.isArray(value) ? value : [];
  const count = value => Number.isFinite(value) && value >= 0 ? value : 0;
  const millis = value => typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
  const time = value => value ? new Date(value).toLocaleString() : "Unknown";
  const duration = value => typeof value === "number" && Number.isFinite(value) && value >= 0
    ? `${value.toLocaleString(undefined, {maximumFractionDigits: 2})} ms` : "Unknown";
  const requestName = request => [text(request && request.method, 20), text(request && request.path, 300)].filter(Boolean).join(" ") || "Unknown API";
  const pinKey = pin => `pin:${text(pin && pin.storage, 40)}:${text(pin && pin.id, 200)}`;

  function create(container, options = {}) {
    if (!container || !container.ownerDocument) throw new Error("Performance insights needs a container.");
    const doc = container.ownerDocument;
    const el = (tag, value, cls) => {
      const node = doc.createElement(tag);
      if (value != null) node.textContent = value;
      if (cls) node.className = cls;
      return node;
    };
    const permanent = [], resultListeners = [];
    const bind = (node, event, action, bucket = permanent) => {
      node.addEventListener(event, action); bucket.push(() => node.removeEventListener(event, action));
    };
    const clearListeners = bucket => { bucket.splice(0).forEach(remove => remove()); };
    const button = (label, cls, action, bucket) => {
      const node = el("button", label, cls); node.type = "button";
      if (action) bind(node, "click", action, bucket);
      return node;
    };
    const option = (label, value) => { const node = el("option", label); node.value = value; return node; };
    const field = (label, input) => { const node = el("label", null, "performance-field"); node.append(el("span", label), input); return node; };
    const section = el("section", null, "performance-insights");
    section.setAttribute("aria-label", "Performance insights");
    const heading = el("div", null, "performance-heading");
    const title = el("div");
    title.append(el("h2", "Analyze captured SQL"), el("p", "Choose evidence and thresholds for this analysis.", "performance-muted"));
    heading.append(title, el("span", "On demand · read only", "performance-readonly"));
    const controls = el("div", null, "performance-controls");
    const source = el("select", null, "performance-source");
    source.append(option("Recent captured requests", "recent")); source.value = "recent";
    const path = el("input", null, "performance-path"); path.type = "search"; path.placeholder = "/api/orders"; path.maxLength = 300;
    const windowSelect = el("select", null, "performance-window");
    [["Last 30 minutes", "30"], ["Last 5 minutes", "5"], ["Last hour", "60"], ["Last 24 hours", "1440"], ["All retained", "0"]]
      .forEach(([label, value]) => windowSelect.append(option(label, value)));
    windowSelect.value = "30";
    controls.append(field("Evidence source", source), field("API path contains", path), field("Request time window", windowSelect));
    const thresholds = el("fieldset", null, "performance-thresholds");
    thresholds.append(el("legend", "Detection thresholds"));
    const inputs = {};
    thresholdFields.forEach(([key, label, initial, min, max, integer]) => {
      const input = el("input", null, `performance-${key}`);
      input.type = "number"; input.value = String(initial); input.min = String(min); input.max = String(max); input.step = integer ? "1" : "any";
      inputs[key] = input; thresholds.append(field(label, input));
    });
    const actions = el("div", null, "performance-actions");
    const analyzeButton = button("Analyze", "performance-analyze", analyze);
    const cancelButton = button("Cancel", "performance-cancel", () => cancel("Analysis cancelled. Previous results, if any, are unchanged."));
    cancelButton.hidden = true;
    actions.append(analyzeButton, cancelButton, el("span", "Up to 10 newest matching requests · sequential reads · no polling", "performance-muted"));
    const sourceNote = el("p", null, "performance-source-note performance-muted");
    const status = el("p", "Choose a source, then select Analyze. Opening this panel does not load request details.", "performance-status");
    status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
    const results = el("div", null, "performance-results");
    const intro = el("div", null, "performance-empty");
    intro.append(el("h3", "Inspect captured query evidence"), el("p", "Start with a recent API request or a saved capture. JDBC SQL capture must already be opted in; this panel does not enable instrumentation or record traffic."),
      el("p", "Suspected N+1 is a heuristic, not a confirmed diagnosis. No findings does not mean no performance issues."));
    results.append(intro);
    section.append(heading, controls, thresholds, actions, sourceNote, status, results); container.append(section);

    let sources = {requests: [], pins: [], partial: false, limited: false, updatedAt: Date.now()};
    let visible = false, disposed = false, sequence = 0, busy = false, completed = null;
    let selectedCategory = "";
    const now = () => Date.now();
    const say = (message, error = false) => { status.textContent = message; status.className = `performance-status${error ? " performance-error" : ""}`; };
    function setBusy(value) {
      busy = value; analyzeButton.disabled = value || !visible; cancelButton.hidden = !value;
      section.setAttribute("aria-busy", String(value));
    }
    function cancel(message) {
      if (!busy) return;
      sequence++; setBusy(false); say(message);
    }
    function settingsChanged() {
      cancel("Analysis cancelled because settings changed. Select Analyze to use the new settings.");
      path.disabled = source.value !== "recent"; windowSelect.disabled = source.value !== "recent";
      if (!busy) say("Settings changed. Select Analyze to apply them; previous results remain unchanged.");
      renderSourceNote();
    }
    [source, windowSelect].forEach(node => bind(node, "change", settingsChanged));
    [path, ...Object.values(inputs)].forEach(node => bind(node, "input", settingsChanged));

    function renderSourceNote() {
      sourceNote.textContent = source.value === "recent"
        ? `${sources.requests.length} retained request summaries available. Source list updated ${time(sources.updatedAt)}. Only already captured evidence is inspected.${sources.partial ? " Source list is partial." : ""}${sources.limited ? " Source list reached its retention/response limit." : ""}`
        : "Saved capture only: the stored graph is analyzed as captured, regardless of the live time window. Missing or expired saved evidence never falls back to a live request.";
    }
    function setSources(value = {}) {
      if (disposed) return;
      sources = {requests: list(value.requests).slice(), pins: list(value.pins).filter(pin => pin && typeof pin === "object").slice(),
        partial: !!value.partial, limited: !!value.limited, updatedAt: now()};
      const selected = source.value || "recent";
      source.replaceChildren(option("Recent captured requests", "recent"), ...sources.pins.map(pin =>
        option(`${text(pin.name, 160) || "Saved capture"} · ${text(pin.storage, 40) || "saved"}`, pinKey(pin))));
      if (selected !== "recent" && !sources.pins.some(pin => pinKey(pin) === selected)) source.append(option("Selected saved capture is no longer available", selected));
      source.value = selected;
      renderSourceNote();
      // Completed evidence and an in-flight run's frozen inputs are deliberately not replaced by summary refreshes.
    }
    function setVisible(value) {
      if (disposed) return;
      visible = !!value;
      if (!visible) cancel("Analysis cancelled when leaving Performance. Select Analyze to run it again.");
      setBusy(busy);
    }
    function readThresholds() {
      const value = {};
      for (const [key, label, , min, max, integer] of thresholdFields) {
        const raw = inputs[key].value.trim(), number = Number(raw);
        const valid = raw !== "" && Number.isFinite(number) && number >= min && number <= max && (!integer || Number.isInteger(number));
        inputs[key].setAttribute("aria-invalid", String(!valid));
        if (!valid) { inputs[key].focus(); throw new Error(`${label}: enter ${integer ? "a whole number" : "a number"} from ${min} to ${max}.`); }
        value[key] = number;
      }
      return value;
    }
    async function analyze() {
      if (disposed || !visible || busy) return;
      let values;
      try { values = readThresholds(); } catch (error) { say(error.message, true); return; }
      const model = root.TraceLensPerformanceModel;
      if (!model || typeof model.analyze !== "function") { say("Performance analysis is unavailable. Reload the viewer to load its analysis module.", true); return; }
      const startedAt = now(), sourceValue = source.value;
      const run = {source: "Recent captured requests", sourceAt: sources.updatedAt, startedAt, finishedAt: 0, thresholds: values,
        attempted: 0, scanned: 0, skipped: 0, failures: [], warnings: [], findings: [], queryCount: 0, segmentCount: 0, matching: 0};
      let work;
      if (sourceValue === "recent") {
        const pathFilter = path.value.trim().toLowerCase(), minutes = Number(windowSelect.value);
        const matched = sources.requests.filter(request => request && typeof request === "object"
          && (!pathFilter || text(request.path, 3000).toLowerCase().includes(pathFilter))
          && (!minutes || millis(request.startedAtMillis) >= startedAt - minutes * 60000))
          .sort((a, b) => millis(b.startedAtMillis) - millis(a.startedAtMillis));
        run.matching = matched.length; run.skipped = Math.max(0, matched.length - MAX_REQUESTS);
        run.source += ` · ${pathFilter || "all API paths"} · ${minutes ? `last ${minutes} minutes` : "all retained"}`;
        work = matched.slice(0, MAX_REQUESTS).map(request => ({request, pin: null}));
        if (sources.partial) run.warnings.push("The retained request list is partial; some services or requests may be missing.");
        if (sources.limited) run.warnings.push("The retained request list is limited; older or omitted requests are not analyzed.");
      } else {
        const pin = sources.pins.find(value => pinKey(value) === sourceValue);
        if (!pin) { say("The selected saved capture is unavailable. Choose another source; no live evidence was loaded.", true); return; }
        run.source = `Saved capture: ${text(pin.name, 160) || text(pin.id, 200)}`;
        run.matching = 1; work = [{request: pin.request || null, pin}];
      }
      const token = ++sequence;
      const current = () => !disposed && visible && token === sequence;
      setBusy(true); say(`Analyzing ${work.length} ${sourceValue === "recent" ? "captured requests" : "saved capture"}…`);
      for (const item of work) {
        if (!current()) return;
        run.attempted++;
        say(`Analyzing ${run.attempted} of ${work.length} · ${requestName(item.request)}`);
        try {
          let graph, pin = item.pin, request = item.request;
          if (pin) {
            if (typeof options.loadPin !== "function") throw new Error("Saved capture loading is unavailable.");
            const snapshot = await options.loadPin(pin, {isCurrent: current});
            if (!current()) return;
            graph = snapshot && snapshot.graph;
            if (snapshot && typeof snapshot === "object") pin = snapshot;
            if (snapshot && snapshot.request) request = snapshot.request;
            if (snapshot && (snapshot.partial || snapshot.limited)) run.warnings.push("The saved capture contains partial or limited evidence.");
          } else {
            if (typeof options.loadRequest !== "function") throw new Error("Captured request loading is unavailable.");
            graph = await options.loadRequest(request, {isCurrent: current});
          }
          if (!current()) return;
          if (!graph || typeof graph !== "object" || Array.isArray(graph)) throw new Error(pin ? "Saved graph is missing; no live fallback was attempted." : "Captured request graph is missing or expired.");
          const analysis = model.analyze(graph, values);
          if (!analysis || typeof analysis !== "object") throw new Error("The captured graph could not be analyzed.");
          run.scanned++; run.queryCount += count(analysis.queryCount); run.segmentCount += count(analysis.segmentCount);
          if (graph.partial || graph.limited) run.warnings.push("At least one request graph is partial or limited; findings describe only retained evidence.");
          list(analysis.warnings).forEach(warning => { const message = text(warning, 1000); if (message) run.warnings.push(`${requestName(request)}: ${message}`); });
          list(analysis.findings).forEach(finding => {
            if (finding && typeof finding === "object") run.findings.push({finding, request, pin, graph});
          });
        } catch (error) {
          if (!current()) return;
          run.failures.push(`${item.pin ? text(item.pin.name, 160) || "Saved capture" : requestName(item.request)}: ${text(error && error.message, 400) || "Evidence could not be loaded."}`);
        }
      }
      if (!current()) return;
      run.finishedAt = now();
      run.findings.sort((a, b) => count(b.finding.totalDurationMs) - count(a.finding.totalDurationMs)
        || count(b.finding.maxDurationMs) - count(a.finding.maxDurationMs) || count(b.finding.count) - count(a.finding.count));
      completed = run; selectedCategory = ""; setBusy(false); renderResults();
      say(run.failures.length ? `Analysis finished with ${run.failures.length} failed reads. Results include successfully loaded evidence only.` : "Analysis complete. Results stay fixed until you select Analyze again.");
    }
    function metric(label, value) {
      const box = el("div", null, "performance-metric"); box.append(el("strong", String(value)), el("span", label)); return box;
    }
    function renderResults(focusCategory = false) {
      clearListeners(resultListeners); results.replaceChildren();
      if (!completed) return;
      const run = completed;
      const summary = el("div", null, "performance-summary");
      summary.append(metric("Findings", run.findings.length), metric("Queries observed", run.queryCount), metric("Requests scanned", run.scanned), metric("Segments observed", run.segmentCount));
      const provenance = el("div", null, "performance-provenance");
      provenance.append(el("p", `${run.source} · Analyzed ${time(run.finishedAt)} · Source list ${time(run.sourceAt)}`),
        el("p", `${run.scanned} scanned · ${run.skipped} skipped by the 10-request cap · ${run.failures.length} failed · ${run.matching} matched. Live reads are sequential; saved captures use only their stored graph.`),
        el("p", `Thresholds used: slow ≥ ${run.thresholds.slowQueryMs} ms; repeated ≥ ${run.thresholds.repeatedQueryCount}; suspected N+1 ≥ ${run.thresholds.nPlusOneCount}; query-heavy ≥ ${run.thresholds.queryHeavyCount}.`));
      results.append(summary, provenance);
      const warnings = [...new Set(run.warnings)];
      if (run.skipped) warnings.push(`Only the newest ${MAX_REQUESTS} matching requests were scanned. Narrow the API path or time window to inspect a smaller sample.`);
      if (run.findings.length > MAX_FINDINGS) warnings.push(`Display capped at the highest-ranked ${MAX_FINDINGS} findings. All finding counts above refer to the scanned sample.`);
      warnings.push(...run.failures.map(failure => `Failed read: ${failure}`));
      if (warnings.length) {
        const box = el("details", null, "performance-warnings");
        box.append(el("summary", `Evidence limitations · ${warnings.length}`));
        const items = el("ul"); warnings.slice(0, 100).forEach(warning => items.append(el("li", warning)));
        if (warnings.length > 100) items.append(el("li", `${warnings.length - 100} additional limitations omitted from display.`));
        box.append(items); results.append(box);
      }
      results.append(el("p", "Observed JDBC execution time is not request wall-clock latency, database CPU time, or a benchmark. Cumulative time sums captured calls and may overlap. Categories can describe the same calls; do not add their counts together.", "performance-qualification"));
      if (!run.findings.length) {
        const empty = el("div", null, "performance-empty");
        empty.append(el("h3", !run.scanned ? "No captured evidence analyzed" : "No findings at these thresholds"),
          el("p", !run.matching ? "No retained requests match this API path and time window. Try All retained, adjust the path, or choose a saved capture." : "Only successfully loaded, retained JDBC evidence is analyzed. Failed or expired captures cannot establish whether a request is healthy."),
          el("p", "No findings does not mean no performance issues. SQL capture still requires explicit JDBC opt-in; this panel never enables it. Lower thresholds or capture a representative request before analyzing again."));
        results.append(empty); return;
      }
      const filter = el("select", null, "performance-category");
      filter.append(option(`All categories (${run.findings.length})`, ""));
      const kinds = new Map(categories);
      run.findings.forEach(({finding}) => { if (!kinds.has(finding.kind)) kinds.set(finding.kind, text(finding.kind, 80) || "Other findings"); });
      kinds.forEach((label, kind) => {
        const total = run.findings.filter(row => row.finding.kind === kind).length;
        if (total) filter.append(option(`${label} (${total})`, kind));
      });
      filter.value = selectedCategory;
      bind(filter, "change", () => { selectedCategory = filter.value; renderResults(doc.activeElement === filter); }, resultListeners);
      const resultHead = el("div", null, "performance-result-head");
      resultHead.append(el("h3", "Ranked findings"), field("Category", filter), el("span", "Highest cumulative JDBC time first", "performance-muted"));
      results.append(resultHead);
      const cards = el("ol", null, "performance-findings");
      const shown = run.findings.filter(row => !selectedCategory || row.finding.kind === selectedCategory).slice(0, MAX_FINDINGS);
      shown.forEach((row, index) => cards.append(renderFinding(row, index + 1)));
      results.append(cards);
      if (focusCategory) filter.focus({preventScroll: true});
    }
    function renderFinding(row, rank) {
      const {finding, request, pin, graph} = row;
      const card = el("li", null, "performance-finding");
      const head = el("div", null, "performance-finding-head");
      const heuristic = finding.confidence === "heuristic";
      head.append(el("h4", `${rank}. ${text(finding.title, 200) || "Captured query finding"}`),
        el("span", heuristic ? "Heuristic · investigate" : "Observed evidence", `performance-confidence${heuristic ? " performance-heuristic" : ""}`));
      card.append(head, el("p", requestName(request), "performance-api"),
        el("p", `Service: ${text(finding.service, 160) || "unknown"} · ${text(finding.system, 80) || "database"} · Destination: ${text(finding.destination, 300) || "unknown (not a confirmed single database)"}`, "performance-muted"));
      if (text(finding.pattern, 5000)) card.append(el("pre", text(finding.pattern, 5000), "performance-pattern"));
      else card.append(el("p", "No query pattern available in this finding.", "performance-muted"));
      const metrics = el("dl", null, "performance-finding-metrics");
      [["Captured calls", String(count(finding.count))], ["Max JDBC execution", duration(finding.maxDurationMs)], ["Cumulative JDBC execution", duration(finding.totalDurationMs)]]
        .forEach(([label, value]) => metrics.append(el("dt", label), el("dd", value)));
      card.append(metrics, el("p", text(finding.explanation, 2000) || "Based on retained JDBC execution evidence only.", "performance-explanation"));
      if (heuristic) card.append(el("p", "This pattern suggests an investigation, not proof of N+1 or a database bottleneck. Check the calling code and captured query evidence.", "performance-qualification"));
      const actions = el("div", null, "performance-finding-actions");
      const open = pin ? options.onOpenPin : options.onOpenRequest;
      if (typeof open === "function") actions.append(button(pin ? "Open saved request" : "Open request", "performance-open-request", () => invoke(() => open(pin || request)), resultListeners));
      const nodes = list(finding.nodes).filter(node => node && typeof node === "object" && !Array.isArray(node));
      if (nodes.length && typeof options.onOpenBoundary === "function") {
        const occurrences = nodes.slice(0, MAX_OCCURRENCES);
        let selected = 0;
        if (nodes.length > 1) {
          const picker = el("select", null, "performance-occurrence");
          occurrences.forEach((node, index) => {
            const seq = Number.isSafeInteger(node.eventSeq) && node.eventSeq >= 0 ? ` · event ${node.eventSeq}` : "";
            const thread = text(node.thread, 100);
            picker.append(option(`Occurrence ${index + 1}${seq}${thread ? ` · ${thread}` : ""} · ${duration(node.durationMs)}`, String(index)));
          });
          picker.value = "0";
          bind(picker, "change", () => { selected = Number(picker.value); }, resultListeners);
          actions.append(field("Captured occurrence", picker));
        }
        actions.append(button("Open query evidence", "performance-open-evidence", () => {
          const node = occurrences[selected];
          if (node) invoke(() => options.onOpenBoundary({request, pin, graph, node}));
        }, resultListeners));
        if (nodes.length > 1) actions.append(el("span", `${occurrences.length} of ${nodes.length} retained query nodes available${nodes.length > MAX_OCCURRENCES ? ` · picker limited to the first ${MAX_OCCURRENCES}` : ""}. Occurrence numbers follow retained evidence order, not request-wide chronology.`, "performance-muted"));
      }
      card.append(actions); return card;
    }
    function invoke(action) {
      if (disposed || !visible) return;
      Promise.resolve().then(() => { if (!disposed && visible) return action(); })
        .catch(error => { if (!disposed && visible) say(`Could not open evidence: ${text(error && error.message, 400) || "unavailable"}`, true); });
    }
    function dispose() {
      if (disposed) return;
      disposed = true; sequence++; busy = false; clearListeners(permanent); clearListeners(resultListeners); section.remove(); completed = null;
    }
    renderSourceNote(); setBusy(false);
    return {setSources, setVisible, dispose};
  }
  return {create};
});
