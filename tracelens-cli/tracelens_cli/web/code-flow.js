/* Compact reading view of the existing bounded execution model. No I/O. */
(function (root, factory) {
  "use strict";
  if (typeof module === "object" && module.exports) module.exports = factory(require("./execution-graph.js"), require("./dependency-ui.js"));
  else root.TraceLensCodeFlow = factory(root.TraceLensExecutionGraph, root.TraceLensDependencies);
})(typeof globalThis !== "undefined" ? globalThis : this, function (graph, dependencies) {
  "use strict";
  const text = (value, limit = 256) => typeof value === "string"
    ? value.slice(0, limit).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ") + (value.length > limit ? "…" : "") : "";
  const integer = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
  const count = value => integer(value) || 0;
  const short = value => text(value).split(".").pop() || "Unknown class";
  const duration = node => node.complete !== true ? "incomplete evidence"
    : typeof node.durationMs === "number" && Number.isFinite(node.durationMs) && node.durationMs >= 0
      ? `${node.durationMs.toLocaleString(undefined, {maximumFractionDigits: 3})} ms inclusive` : "duration unavailable";
  const isFailure = node => node.outcome === "threw" || node.eventType === "EXCEPTION";
  const isBoundary = node => node.kind === "marker" && ["DB_ROUTE", "EXTERNAL_CALL"].includes(node.eventType);
  let preferredCallView = "application";
  function classDisplay(value) {
    const original = short(value), proxy = /^(.+)\$\$(?:SpringCGLIB|EnhancerBySpringCGLIB)\$\$[A-Za-z0-9_$]+$/.exec(original);
    return {name: proxy ? proxy[1] : original, proxy: !!proxy};
  }
  function foldReason(item) {
    const node = item.node;
    if (node.kind !== "invocation" || node.complete !== true || node.outcome !== "returned" || item.failures) return null;
    if (classDisplay(node.className).proxy) return "Spring proxy";
    if (/^access\$\d+$/.test(node.methodName || "")) return "Synthetic accessor";
    const wrappers = {
      "org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor": ["intercept"],
      "org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation": ["proceed", "invokeJoinpoint"],
      "org.springframework.aop.framework.ReflectiveMethodInvocation": ["proceed", "invokeJoinpoint"],
      "org.springframework.aop.framework.JdkDynamicAopProxy": ["invoke"],
      "org.springframework.aop.support.AopUtils": ["invokeJoinpointUsingReflection"],
      "org.springframework.cglib.proxy.MethodProxy": ["invoke", "invokeSuper"],
      "java.lang.reflect.Method": ["invoke"]
    };
    if (wrappers[node.className]?.includes(node.methodName) ||
        /^(?:jdk\.internal\.reflect|sun\.reflect)\.(?:(?:Native|Delegating|Direct)MethodAccessorImpl|GeneratedMethodAccessor\d+)$/.test(node.className || "") && ["invoke", "invoke0"].includes(node.methodName)) return "Framework wrapper";
    return null;
  }
  function label(node) {
    if (node.kind === "invocation") return `${classDisplay(node.className).name}.${text(node.methodName) || "Unknown method"}()`;
    if (node.eventType === "EXCEPTION") return `Exception · ${short(node.exceptionType)}`;
    if (node.eventType === "METHOD_EXIT") return "Unmatched method exit";
    const boundary = node.boundary || {};
    if (node.eventType === "DB_ROUTE") return `DB routing decision · ${text(boundary.destination || node.route) || "Unknown route"}`;
    if (dependencies?.jdbcDetails(boundary)) return `${dependencies.isSqlLimit(boundary) ? "SQL capture limit" : "SQL query"} · ${text(boundary.system)} · ${text(boundary.operation)}`;
    if (boundary.evidenceKind === "client-boundary" && ["database", "cache"].includes(boundary.boundaryKind)) return `${boundary.boundaryKind === "cache" ? "Cache" : "Database"} call · ${text(boundary.system) || "Unknown system"} · ${text(boundary.operation) || "Operation unknown"}`;
    if (boundary.boundaryKind === "messaging") return `Queue ${text(boundary.direction) || "boundary"} · ${text(boundary.destination || boundary.system) || "Unknown destination"}`;
    return `External boundary · ${text(boundary.destination || boundary.system || node.system) || "Unknown destination"}`;
  }

  // Reuse the Call tree's validation, cycle handling and hard bounds. We only
  // change presentation: every surviving node retains its same explicit parent.
  function project(model) {
    const result = graph.layout(model), byId = new Map(result.nodes.map(item => [item.id, {...item, children: [], failures: 0, boundaries: 0, availableDescendants: 0}]));
    const order = item => integer(item.node.entry?.index ?? item.node.evidence?.index);
    const sort = items => items.every(item => order(item) !== null) ? items.slice().sort((a, b) => order(a) - order(b)) : items.slice();
    for (const item of byId.values()) item.children = sort(item.node.kind === "invocation"
      ? result.nodes.filter(child => child.parentId === item.id).map(child => byId.get(child.id)) : []).map(child => child.id);
    function aggregate(item) {
      item.failures = isFailure(item.node) ? 1 : 0;
      item.boundaries = isBoundary(item.node) ? 1 : 0;
      for (const id of item.children) {
        const child = byId.get(id); aggregate(child);
        item.availableDescendants += 1 + child.availableDescendants;
        item.failures += child.failures; item.boundaries += child.boundaries;
      }
    }
    const lanes = result.lanes.map(lane => {
      const roots = sort([...byId.values()].filter(item => item.laneId === lane.id && !byId.has(item.parentId)));
      roots.forEach(aggregate);
      let ordinal = 0;
      function number(item) { item.ordinal = ++ordinal; item.children.forEach(id => number(byId.get(id))); }
      roots.forEach(number);
      return {...lane, roots: roots.map(item => item.id), count: ordinal};
    });
    // Reveal roots and their immediate work. Transparent single-child nesting
    // chains get two extra levels, while proxies remain visible and identifiable.
    const defaultOpen = new Set();
    for (const item of byId.values()) {
      const parent = byId.get(item.parentId);
      if (item.children.length && (item.depth === 0 || (item.depth < 3 && parent?.children.length === 1))) defaultOpen.add(item.id);
    }
    const hasLargerLane = lanes.some(lane => lane.count > 3), defaultOpenLanes = new Set();
    for (const lane of lanes) {
      const members = [...byId.values()].filter(item => item.laneId === lane.id);
      const smallLinear = lane.count <= 3 && lane.roots.length === 1 && members.every(item => item.children.length <= 1 && !isFailure(item.node));
      if (!hasLargerLane || !smallLinear) defaultOpenLanes.add(lane.id);
    }
    return {...result, byId, lanes, defaultOpen, defaultOpenLanes};
  }

  function render(container, model, options = {}) {
    if (!container || !container.ownerDocument) throw new TypeError("A DOM container is required");
    model = model && typeof model === "object" ? model : {};
    options = options && typeof options === "object" ? options : {};
    const document = container.ownerDocument, view = project(model), listeners = [], branches = new Map(), evidence = new Map(), laneBlocks = new Map(), rows = new Map(), foldedControls = new Map(), revealedCalls = new Set();
    let stopped = false, visibleLimit = 40, selectedId = null, includedIds = null, normalView = null;
    const element = (tag, cls, value) => {
      const item = document.createElement(tag);
      if (cls) item.className = cls;
      if (value !== undefined) item.textContent = value;
      return item;
    };
    const listen = (item, name, fn) => { item.addEventListener(name, fn); listeners.push(() => item.removeEventListener(name, fn)); };
    const button = (cls, value, fn) => {
      const item = element("button", cls, value); item.type = "button";
      listen(item, "click", () => { if (!stopped) fn(); }); return item;
    };
    const wrapper = element("section", "tcf"); wrapper.setAttribute("aria-label", "Code flow reading view");
    const heading = element("div", "tcf-heading"); heading.append(element("h3", "", "Code flow"));
    const context = [text(options.trace?.service || options.trace?.applicationId), text(options.trace?.httpMethod), text(options.trace?.path)].filter(Boolean).join(" · ");
    if (context) heading.append(element("span", "tcf-context", context));
    if (options.saved === true) heading.append(element("span", "tcf-saved", "Saved · not live"));
    const counts = element("p", "tcf-counts"); counts.setAttribute("aria-live", "polite");
    const notes = element("details", "tcf-notes"), noteList = element("ul");
    const upstreamOmitted = count(model.counts?.nodesOmitted), partial = model.partial || upstreamOmitted || view.omittedNodes || view.invalidLinks || view.cycleLinks;
    notes.append(element("summary", "", `${partial ? "Partial evidence" : "Observed evidence only"} · limits and interpretation`), noteList);
    const note = value => noteList.append(element("li", "", value));
    if (upstreamOmitted) note(`${upstreamOmitted} model nodes omitted upstream. Expand all cannot recover these; the timeline may contain more retained evidence.`);
    if (count(model.counts?.eventsOmitted)) note(`${model.counts.eventsOmitted} events were not processed by reconstruction.`);
    if (view.omittedNodes) note(`${view.omittedNodes} nodes omitted by validation or the 200-node / 16-level reader bounds.`);
    if (view.invalidLinks) note(`${view.invalidLinks} unsupported, missing-parent or cross-thread-name links omitted.`);
    if (view.cycleLinks) note(`${view.cycleLinks} cyclic parent links omitted.`);
    note("Collapsed nodes are still available in this model. Counts of exceptions and boundaries include only available descendants, not omitted evidence.");
    note("Application logic folds only recognized Spring proxies, framework invocation wrappers and synthetic accessors. Ordinary getters, incomplete calls and branches with exception observations stay visible. Folded calls retain their recorded parent and evidence; use Reveal or All recorded calls to inspect them.");
    note("Rows show observed entry order within each thread-name lane; indentation preserves observed nesting, not proof of a direct call. Separate lanes do not establish an asynchronous handoff or shared timeline.");
    if (view.nodes.some(item => integer(item.node.entry?.index ?? item.node.evidence?.index) === null)) note("Some entry positions are unavailable. Sibling groups with missing positions retain the model's received order; no chronology is inferred for those groups.");
    note("Inclusive durations include nested work and waits. Do not add parent and child durations. A method throwing does not necessarily fail the request.");
    for (const warning of (Array.isArray(model.warnings) ? model.warnings : []).slice(0, 32)) note(typeof warning === "string" ? text(warning) : `${text(warning?.code) || "Unknown warning"} (${integer(warning?.count) ?? "?"})`);
    if (Array.isArray(model.warnings) && model.warnings.length > 32) note(`${model.warnings.length - 32} additional warning categories are omitted from this view.`);
    for (const limitation of (Array.isArray(model.limitations) ? model.limitations : []).slice(0, 8)) note(text(limitation, 512));
    heading.append(notes);
    const toolbar = element("div", "tcf-toolbar"); toolbar.setAttribute("aria-label", "Code flow controls");
    // Both headers can wrap. Keep reader controls below the persistent segment
    // navigation too, including when its timeline-only controls change height.
    const stickyHeaders = ["body > header", "#segment-toolbar"].map(selector => document.querySelector?.(selector)).filter(Boolean);
    const browser = document.defaultView;
    const positionToolbar = () => {
      if (stopped) return;
      const heights = stickyHeaders.map(header => header.getBoundingClientRect?.().height)
        .filter(height => typeof height === "number" && Number.isFinite(height));
      const top = heights.length ? heights.reduce((sum, height) => sum + Math.ceil(Math.max(0, height)), 8) :
        parseFloat(browser?.getComputedStyle?.(toolbar)?.top);
      if (heights.length) toolbar.style.top = `${top}px`;
      const toolbarHeight = toolbar.getBoundingClientRect?.().height;
      const offset = (Number.isFinite(top) ? top : 64) +
        (Number.isFinite(toolbarHeight) ? Math.ceil(Math.max(0, toolbarHeight)) : 0) + 8;
      // Scroll targets need the whole sticky stack, including this toolbar's
      // search/status rows, not just the page and segment headers.
      wrapper.style.setProperty?.("--cf-sticky-offset", `${offset}px`);
    };
    positionToolbar();
    if (typeof browser?.ResizeObserver === "function") {
      const observer = new browser.ResizeObserver(positionToolbar);
      [...stickyHeaders, toolbar].forEach(header => observer.observe(header)); listeners.push(() => observer.disconnect());
    } else if (browser?.addEventListener) listen(browser, "resize", positionToolbar);
    const filter = element("select", "tcf-thread-filter"); filter.setAttribute("aria-label", "Filter code flow by thread name");
    const all = element("option", "", "All thread names"); all.value = ""; filter.append(all);
    for (const lane of view.lanes) { const option = element("option", "", lane.threadName); option.value = lane.id; filter.append(option); }
    const callView = element("select", "tcf-call-view"); callView.setAttribute("aria-label", "Code flow detail level");
    for (const [value, title] of [["application", "Application logic"], ["all", "All recorded calls"]]) {
      const option = element("option", "", title); option.value = value; callView.append(option);
    }
    callView.value = ["application", "all"].includes(options.callView) ? options.callView : preferredCallView;
    listen(callView, "change", () => {
      preferredCallView = callView.value === "all" ? "all" : "application";
      callView.value = preferredCallView; revealedCalls.clear(); updateCounts();
      if (typeof options.onCallViewChange === "function") options.onCallViewChange(preferredCallView);
    });
    function applyMode(mode) {
      visibleLimit = mode === "overview" ? 40 : 200;
      for (const [id, branch] of branches) branch.open = mode === "all" || (mode === "overview" && view.defaultOpen.has(id));
      for (const detail of evidence.values()) detail.open = false;
      for (const [id, lane] of laneBlocks) lane.open = mode === "all" || (mode === "overview" && (view.defaultOpenLanes.has(id) || filter.value === id));
      updateCounts();
    }
    toolbar.append(button("tcf-expand-all", "Expand all", () => applyMode("all")),
      button("tcf-collapse-all", "Collapse all", () => applyMode("none")),
      button("tcf-overview", "Overview", () => applyMode("overview")), callView, filter);
    const searchUI = graph.searchControls(document, model, "tcf", {
      onStart() {
        if (!normalView) normalView = {visibleLimit, filter: filter.value, selectedId,
          branches: new Map([...branches].map(([id, el]) => [id, el.open])),
          evidence: new Map([...evidence].map(([id, el]) => [id, el.open])),
          lanes: new Map([...laneBlocks].map(([id, el]) => [id, el.open]))};
      },
      onMatch(id, keyboard) {
        includedIds = null; filter.value = ""; selectedId = id; visibleLimit = 200;
        let item = view.byId.get(id);
        if (item) laneBlocks.get(item.laneId).open = true;
        while (item) { if (branches.has(item.id)) branches.get(item.id).open = true; item = view.byId.get(item.parentId); }
        updateCounts();
        const summary = rows.get(id)?.querySelector(".tcf-row");
        // Search text can wrap the toolbar before ResizeObserver delivers its
        // next callback. Measure now so this navigation uses the new height.
        positionToolbar();
        if (keyboard) summary?.focus?.({preventScroll: true});
        summary?.scrollIntoView?.({block: "nearest", inline: "nearest"});
      },
      onChange() { includedIds = null; updateCounts(); },
      onFocus(id) {
        includedIds = graph.focusIds(model, id); filter.value = ""; visibleLimit = 200;
        for (const member of includedIds) {
          if (branches.has(member)) branches.get(member).open = true;
          const item = view.byId.get(member); if (item) laneBlocks.get(item.laneId).open = true;
        }
        updateCounts();
      },
      onClear() {
        includedIds = null; const saved = normalView; normalView = null;
        if (saved) {
          visibleLimit = saved.visibleLimit; filter.value = saved.filter; selectedId = saved.selectedId;
          for (const [id, open] of saved.branches) branches.get(id).open = open;
          for (const [id, open] of saved.evidence) evidence.get(id).open = open;
          for (const [id, open] of saved.lanes) laneBlocks.get(id).open = open;
        }
        updateCounts();
      }
    });
    listeners.push(() => searchUI.dispose()); toolbar.append(searchUI.element);
    const frame = element("div", "tcf-frame"), body = element("div", "tcf-body");
    const showRemaining = button("tcf-show-remaining", "Show remaining available rows", () => { visibleLimit = 200; updateCounts(); });
    showRemaining.hidden = true;
    frame.append(toolbar, body, showRemaining);
    wrapper.append(heading, counts, frame); container.replaceChildren(wrapper);
    positionToolbar();
    listen(wrapper, "keydown", event => {
      if (event.key === "/" && !["input", "textarea", "select"].includes(String(event.target?.tagName).toLowerCase())) {
        event.preventDefault(); searchUI.focusInput();
      }
    });

    function updateCounts() {
      let shown = 0, foldedShown = 0, potential = 0, selectedTotal = 0, laneCount = 0;
      for (const [id, row] of rows) {
        row.setAttribute("data-search-match", String(searchUI.hasMatch(id)));
        row.setAttribute("data-selected", String(selectedId === id));
        const folded = callView.value === "application" && foldedControls.has(id) && !revealedCalls.has(id) && !searchUI.hasMatch(id) && selectedId !== id;
        row.setAttribute("data-folded", String(folded));
        evidence.get(id).hidden = folded;
        if (foldedControls.has(id)) foldedControls.get(id).hidden = !folded;
        const branch = branches.get(id);
        if (branch) {
          branch.childNodes[0].hidden = folded;
          if (folded) branch.open = true;
        }
      }
      for (const lane of view.lanes) {
        const scopedCount = includedIds ? [...view.byId.values()].filter(item => item.laneId === lane.id && includedIds.has(item.id)).length : lane.count;
        const selected = (!filter.value || filter.value === lane.id) && scopedCount > 0;
        const block = laneBlocks.get(lane.id); if (block) block.hidden = !selected;
        if (!selected) continue;
        selectedTotal += scopedCount; laneCount++;
        const visit = (id, parentVisible) => {
          const available = parentVisible && (!includedIds || includedIds.has(id));
          const visible = available && shown < visibleLimit;
          if (available) potential++;
          const row = rows.get(id);
          if (visible) { if (row.getAttribute("data-folded") === "true") foldedShown++; else shown++; }
          if (row) row.hidden = !visible;
          const childVisible = available && branches.get(id)?.open;
          view.byId.get(id).children.forEach(child => visit(child, childVisible));
        };
        lane.roots.forEach(id => visit(id, block?.open !== false));
      }
      showRemaining.hidden = potential <= shown + foldedShown;
      counts.textContent = `${shown} nodes shown${foldedShown ? ` · ${foldedShown} recorded calls folded` : ""} · ${selectedTotal - potential} collapsed${potential > shown + foldedShown ? ` · ${potential - shown - foldedShown} hidden by overview` : ""} · ${view.nodes.length - selectedTotal} ${includedIds ? "outside focused branch / filtered" : "filtered"} · ${upstreamOmitted + view.omittedNodes} omitted · ${laneCount} thread-name ${laneCount === 1 ? "lane" : "lanes"}`;
      searchUI.setSelection(selectedId);
    }
    function evidenceFields(item, panel) {
      const node = item.node, fields = element("dl", "tcf-fields"), lane = view.lanes.find(value => value.id === item.laneId);
      const field = (key, value) => fields.append(element("dt", "", key), element("dd", "", value));
      field("Thread name", lane?.threadName || "Unknown");
      if (node.className) field("Class", text(node.className));
      if (node.methodName) field("Method", text(node.methodName));
      if (node.kind === "invocation") {
        field("Outcome", text(node.outcome) || "Unknown"); field("Inclusive duration", duration(node));
        if (node.exceptionType) field("Escaping exception", text(node.exceptionType) + " — may be handled by a caller");
      } else {
        const boundary = node.boundary || {};
        if (node.exceptionType) field("Exception", text(node.exceptionType));
        if (boundary.destination || node.route) field("Destination / route", text(boundary.destination || node.route));
        if (boundary.system || node.system) field("System", text(boundary.system || node.system));
        if (boundary.operation) field("Operation", text(boundary.operation));
        if (boundary.status) field("Observation", text(boundary.status));
        field("Evidence", dependencies?.jdbcDetails(boundary) ? boundary.evidenceKind === "jdbc-execution"
          ? "Timed JDBC client execution; a returned call does not establish transaction commit." : dependencies.summaryBoundary(boundary).evidence
          : node.eventType === "DB_ROUTE" ? "Pool selection only; no query or database success implied."
          : node.eventType === "EXCEPTION" ? "Unmatched exception observation; not proof that the request failed."
          : node.eventType === "METHOD_EXIT" ? "Unmatched exit; no invocation or duration is invented."
          : "Local point observation; not a timed call, successful delivery or proof of remote processing.");
      }
      const lines = node.observedLines;
      if (lines && integer(lines.first) > 0 && integer(lines.last) > 0) field("Observed line coordinates", `First ${lines.first}, last ${lines.last} (${integer(lines.count) ?? "unknown"} markers) in ${text(node.className) || "unknown class"}. Not a continuous source range; local source may differ from the captured build.`);
      else field("Observed line coordinates", "No line coordinates associated with this node.");
      const point = node.entry || node.evidence;
      if (point && integer(point.index) !== null) field("Evidence position", `Event index ${point.index}${integer(point.seq) !== null ? ` · sequence ${point.seq}` : ""}`);
      field("Relationship", item.parentId ? "Observed instrumented nesting; intervening uninstrumented calls may be absent." : "No supported parent in this model; not proof of request entry.");
      panel.append(fields);
      if (dependencies?.jdbcDetails(node.boundary)) panel.append(dependencies.renderLazyJdbc(document, node.boundary,
        options.loadBoundaryData ? () => options.loadBoundaryData(node) : null));
      if (typeof options.onRevealEvidence === "function" && (node.entry || node.evidence || lines)) panel.append(button("tcf-source", "Show source in timeline", () => options.onRevealEvidence(node)));
      const location = graph.sourceLocation(node);
      if (location && typeof options.onOpenSource === "function") panel.append(button("tcf-source tcf-open-source", "Open local source", () => options.onOpenSource(location)));
    }
    function row(id) {
      const item = view.byId.get(id), node = item.node;
      const boundaryStyle = node.boundary?.evidenceKind === "client-boundary" && node.boundary?.boundaryKind === "cache" ? " tcf-cache"
        : node.boundary?.boundaryKind === "messaging" ? " tcf-queue" : "";
      const li = element("li", `tcf-node${item.depth >= 3 ? " tcf-deep" : ""}${isFailure(node) ? " tcf-exception" : isBoundary(node) ? ` tcf-boundary${boundaryStyle}` : ""}`);
      li.dataset.nodeId = id; rows.set(id, li);
      const reason = foldReason(item);
      if (reason) {
        const reveal = button("tcf-folded-call", `${reason} · 1 recorded call folded · Reveal`, () => {
          revealedCalls.add(id); updateCounts(); evidence.get(id).querySelector(".tcf-row")?.focus?.({preventScroll: true});
        });
        reveal.title = `${label(node)} · Reveal the original recorded call and nesting`;
        foldedControls.set(id, reveal); li.append(reveal);
      }
      const detail = element("details", "tcf-evidence"), summary = element("summary", "tcf-row");
      evidence.set(id, detail);
      listen(summary, "click", () => { selectedId = id; updateCounts(); });
      const ordinal = element("span", "tcf-ordinal", String(item.ordinal)); ordinal.setAttribute("aria-label", `Observed position ${item.ordinal} in this thread`);
      ordinal.title = "Position among available nodes in this thread, not an event sequence number. Gaps include folded nodes.";
      const title = element("span", "tcf-title"); title.title = node.kind === "invocation" ? `${text(node.className)}.${text(node.methodName)}()` : label(node);
      if (node.kind === "invocation") title.append(element("span", "tcf-class", classDisplay(node.className).name),
        element("span", "tcf-method", `${text(node.methodName) || "Unknown method"}()`));
      else title.textContent = label(node);
      const names = element("span", "tcf-names"); names.append(title);
      if (node.kind === "invocation" && classDisplay(node.className).proxy) names.append(element("span", "tcf-proxy", "Spring proxy"));
      const badges = element("span", "tcf-badges");
      if (item.failures) badges.append(element("span", "tcf-badge tcf-error-badge", `${item.failures} exception ${item.failures === 1 ? "observation" : "observations"}`));
      if (item.boundaries) badges.append(element("span", "tcf-badge tcf-boundary-badge", `${item.boundaries} ${item.boundaries === 1 ? "boundary" : "boundaries"}`));
      const metric = node.kind === "invocation" ? `${text(node.outcome) || "unknown"} · ${duration(node)}`
        : [text(node.boundary?.operation), text(node.boundary?.status)].filter(Boolean).join(" · ") || "Point observation";
      summary.append(ordinal, names, badges, element("span", "tcf-metric", metric), element("span", "tcf-disclosure", "Details"));
      detail.append(summary);
      let populated = false;
      listen(detail, "toggle", () => {
        if (stopped || !detail.open || populated) return;
        populated = true; const panel = element("div", "tcf-inline"); evidenceFields(item, panel); detail.append(panel);
      });
      li.append(detail);
      if (item.children.length) {
        const branch = element("details", "tcf-branch"), list = element("ol", "tcf-list");
        const branchSummary = element("summary", "tcf-branch-toggle", `${item.availableDescendants} nested ${item.availableDescendants === 1 ? "node" : "nodes"}`);
        branchSummary.title = "Expand or collapse the observed nesting below this method; row details stay inline.";
        branches.set(id, branch); branch.open = view.defaultOpen.has(id);
        item.children.forEach(child => list.append(row(child)));
        branch.append(branchSummary, list); listen(branch, "toggle", () => { if (!stopped) updateCounts(); }); li.append(branch);
      }
      return li;
    }
    for (const [index, lane] of view.lanes.entries()) {
      const block = element("details", "tcf-lane"); laneBlocks.set(lane.id, block);
      block.open = view.defaultOpenLanes.has(lane.id);
      block.setAttribute("aria-label", `Thread name: ${lane.threadName}`);
      const laneHeading = element("summary", "tcf-lane-heading");
      laneHeading.append(element("span", "tcf-thread-name", `Thread name: ${lane.threadName}`),
        element("span", "tcf-lane-count", `${lane.count} available ${lane.count === 1 ? "node" : "nodes"}`));
      const roots = lane.roots.map(id => view.byId.get(id)), failures = roots.reduce((sum, item) => sum + item.failures, 0), boundaries = roots.reduce((sum, item) => sum + item.boundaries, 0);
      if (failures) laneHeading.append(element("span", "tcf-badge tcf-error-badge", `${failures} exception ${failures === 1 ? "observation" : "observations"}`));
      if (boundaries) laneHeading.append(element("span", "tcf-badge tcf-boundary-badge", `${boundaries} ${boundaries === 1 ? "boundary" : "boundaries"}`));
      block.append(laneHeading);
      if (index > 0) block.append(element("p", "tcf-handoff", "Separate thread-name lane · no asynchronous handoff is established by this view"));
      const list = element("ol", "tcf-list tcf-roots"); lane.roots.forEach(id => list.append(row(id)));
      block.append(list); listen(block, "toggle", () => { if (!stopped) updateCounts(); }); body.append(block);
    }
    if (!view.nodes.length) body.append(element("p", "tcf-empty", "No observed invocation or marker nodes are available. Check the timeline for other retained evidence."));
    listen(filter, "change", () => {
      if (filter.value && laneBlocks.has(filter.value)) laneBlocks.get(filter.value).open = true;
      updateCounts();
    }); updateCounts();

    const rawCalls = Array.isArray(options.trace?.httpCalls) ? options.trace.httpCalls : [];
    const scannedCalls = rawCalls.slice(0, 5000), calls = scannedCalls.filter(call => call && typeof call === "object" && !Array.isArray(call));
    if (rawCalls.length || options.trace?.httpCallsTruncated) {
      const unplaced = element("details", "tcf-unplaced");
      unplaced.append(element("summary", "", `HTTP boundaries · ${calls.length} unplaced observations`),
        element("p", "", "HTTP snapshots have no invocation owner. They remain separate, not attached to a guessed method or thread."));
      const search = element("input", "tcf-http-search"); search.type = "search"; search.maxLength = 256;
      search.placeholder = "Search HTTP destination, path or status"; search.setAttribute("aria-label", "Search unplaced HTTP observations");
      const status = element("p", "tcf-http-status"); status.setAttribute("role", "status");
      const cards = element("div", "tcf-http-cards"), controls = element("div", "tcf-http-controls");
      let page = 0;
      const previous = button("tcf-http-previous", "Previous HTTP calls", () => { page--; showCalls(); });
      const next = button("tcf-http-next", "Next HTTP calls", () => { page++; showCalls(); });
      const searchable = calls.map(call => ({call, terms: [text(call.httpMethod), text(call.destinationName), text(call.peer),
        text(call.path, 2048), text(call.status), integer(call.httpStatus)].filter(value => value !== null).join(" ").toLowerCase()}));
      function showCalls() {
        const query = search.value.slice(0, 256).trim().toLowerCase();
        const matches = query ? searchable.filter(item => item.terms.includes(query)) : searchable;
        page = Math.min(Math.max(0, page), Math.max(0, Math.ceil(matches.length / 20) - 1));
        const start = page * 20;
        cards.replaceChildren();
        for (const {call} of matches.slice(start, start + 20)) {
          const card = element("article", "tcf-http");
          card.append(element("strong", "", `${text(call.httpMethod) || "HTTP"} · ${text(call.destinationName || call.peer) || "Unknown destination"} · ${text(call.path, 2048) || "Path unavailable"}`),
            element("p", "", `${text(call.status) || "Unknown status"}${integer(call.httpStatus) ? ` · HTTP ${call.httpStatus}` : ""} · remote processing not inferred`));
          if (text(call.destinationName) && text(call.peer) && call.destinationName !== call.peer) card.append(element("p", "", `Recorded address: ${text(call.peer)}`));
          cards.append(card);
        }
        status.textContent = matches.length ? `${start + 1}–${Math.min(start + 20, matches.length)} of ${matches.length} matching HTTP observations · ${calls.length} available. Only the bounded fields shown here are searched.`
          : "No matching retained HTTP observations. This does not establish that a call did not happen.";
        previous.disabled = page === 0; next.disabled = start + 20 >= matches.length;
      }
      listen(search, "input", () => { page = 0; showCalls(); });
      controls.append(search, previous, next); unplaced.append(controls, status, cards); showCalls();
      toolbar.append(button("tcf-http-jump", `HTTP observations (${calls.length})`, () => {
        unplaced.open = true; positionToolbar(); search.focus?.({preventScroll: true});
        unplaced.scrollIntoView?.({block: "start", inline: "nearest"});
      }));
      if (scannedCalls.length > calls.length) unplaced.append(element("p", "", `${scannedCalls.length - calls.length} malformed HTTP entries skipped; they are not counted as observations.`));
      if (rawCalls.length > scannedCalls.length) unplaced.append(element("p", "", `Only the first ${scannedCalls.length} HTTP entries were inspected. Additional observations may be unavailable in this view.`));
      if (options.trace?.httpCallsTruncated) unplaced.append(element("p", "", "HTTP boundary capture was truncated; additional calls may be absent."));
      wrapper.append(unplaced);
    }
    return function cleanup() {
      if (stopped) return;
      stopped = true; listeners.splice(0).forEach(remove => remove()); wrapper.remove();
      branches.clear(); evidence.clear(); laneBlocks.clear(); rows.clear(); foldedControls.clear(); revealedCalls.clear();
    };
  }
  return Object.freeze({render, project});
});
