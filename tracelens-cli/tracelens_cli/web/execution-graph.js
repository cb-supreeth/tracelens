/* Bounded observed invocation forest. No sequence, direct-call or async edges. */
(function (root, factory) {
  "use strict";
  const api = factory(root, typeof module === "object" && module.exports ? require("./dependency-ui.js") : root.TraceLensDependencies);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensExecutionGraph = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (root, dependencies) {
  "use strict";
  const LIMITS = Object.freeze({nodes: 200, lanes: 64, depth: 16, httpCalls: 20});
  const BOX = Object.freeze({width: 320, height: 132, column: 356, row: 148, padding: 20, laneHeader: 36});
  let nextDetailsId = 0;
  const text = (value, limit = 256) => typeof value === "string"
    ? value.slice(0, limit).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ") + (value.length > limit ? "…" : "") : "";
  const identity = value => typeof value === "string" && value.length > 0 && value.length <= 256 ? value : null;
  const number = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
  const integer = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
  const short = value => text(value).split(".").pop() || "Unknown";
  const milliseconds = value => number(value) === null ? "Duration unavailable" : `${value.toLocaleString(undefined, {maximumFractionDigits: 3})} ms inclusive`;
  function classDisplay(value) {
    const original = short(value);
    const proxy = /^(.+)\$\$(?:SpringCGLIB|EnhancerBySpringCGLIB)\$\$[A-Za-z0-9_$]+$/.exec(original);
    return {name: proxy ? proxy[1] : original, proxy: !!proxy};
  }

  function fitScale(width, height, availableWidth, availableHeight) {
    if (![width, height, availableWidth, availableHeight].every(value => number(value) !== null && value > 0)) return 1;
    return Math.max(0.001, Math.min(1, Math.max(1, availableWidth - 24) / width, Math.max(1, availableHeight - 24) / height));
  }

  // Search only the validated, bounded presentation model, never claim to have
  // searched the raw capture. Literal tokens keep hostile/large queries cheap.
  function search(model, query) {
    const view = layout(model), normalized = text(typeof query === "string" ? query.slice(0, 256) : "").trim().toLowerCase();
    const tokens = normalized.split(/\s+/).filter(Boolean).slice(0, 16);
    const matches = [];
    if (tokens.length) for (const item of view.nodes) {
      const node = item.node, boundary = node.boundary || {};
      const fields = {class: node.className, method: node.methodName, exception: node.exceptionType,
        destination: boundary.destination || node.route, system: boundary.system || node.system,
        operation: boundary.operation};
      const values = Object.entries(fields).map(([name, value]) => [name, text(value, 1024).toLowerCase()]);
      if (tokens.every(token => values.some(([, value]) => value.includes(token)))) {
        matches.push({id: item.id, fields: values.filter(([, value]) => tokens.some(token => value.includes(token))).map(([name]) => name)});
      }
    }
    return {query: normalized, truncated: typeof query === "string" && (query.length > 256 || normalized.split(/\s+/).length > 16),
      matches, searchedNodes: view.nodes.length, omittedNodes: view.omittedNodes + (integer(model?.counts?.nodesOmitted) || 0)};
  }

  function focusIds(model, id) {
    const view = layout(model), byId = new Map(view.nodes.map(item => [item.id, item])), included = new Set();
    if (!byId.has(id)) return included;
    let ancestor = byId.get(id);
    while (ancestor && !included.has(ancestor.id)) {
      included.add(ancestor.id); ancestor = byId.get(ancestor.parentId);
    }
    const visit = item => {
      included.add(item.id);
      for (const child of item.children) if (byId.has(child)) visit(byId.get(child));
    };
    visit(byId.get(id)); return included;
  }

  function sourceLocation(node) {
    const className = node?.className, line = integer(node?.observedLines?.first);
    return typeof className === "string" && className.length <= 256
      && /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/.test(className) && line > 0 && line <= 2147483647
      ? {className, line} : null;
  }

  // Both presentations share the same bounded search and keyboard contract.
  function searchControls(document, model, prefix, handlers) {
    const listeners = [], create = (tag, name, value) => {
      const el = document.createElement(tag); el.className = `${prefix}-${name}`;
      if (value !== undefined) el.textContent = value; return el;
    };
    const listen = (el, name, fn) => { el.addEventListener(name, fn); listeners.push(() => el.removeEventListener(name, fn)); };
    const makeButton = (name, value, fn) => {
      const el = create("button", name, value); el.type = "button"; listen(el, "click", fn); return el;
    };
    let found = search(model, ""), index = -1, selectedId = null, focusedId = null;
    const panel = create("div", "search"), input = create("input", "search-input");
    input.type = "search"; input.maxLength = 256; input.placeholder = "Class, method, exception, destination";
    input.setAttribute("aria-label", "Search retained execution evidence");
    input.title = "Literal words, case-insensitive. Enter: next match; Shift+Enter: previous; Escape: clear search and focus.";
    const status = create("p", "search-status"); status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
    const scope = create("p", "search-scope", `${found.searchedNodes} searchable retained model nodes · ${found.omittedNodes} omitted. Raw events and unplaced HTTP observations are not searched.`);
    const previous = makeButton("search-previous", "Previous match", () => jump(-1, true));
    const next = makeButton("search-next", "Next match", () => jump(1, true));
    const focus = makeButton("focus-branch", "Focus selected branch", () => {
      if (!selectedId) return;
      handlers.onStart(); focusedId = selectedId; update(); handlers.onFocus(selectedId);
    });
    const clear = makeButton("search-clear", "Clear search / focus", () => reset());
    function update() {
      previous.disabled = next.disabled = !found.matches.length;
      focus.disabled = !selectedId; clear.disabled = !found.query && !focusedId;
      status.textContent = focusedId ? "Focused branch: observed ancestors and descendants only; unrelated branches hidden. No asynchronous handoff is inferred."
        : found.query ? found.matches.length ? `${index + 1} of ${found.matches.length} matching model nodes`
          : "No matching retained model nodes. This does not prove the code did not execute."
          : "Search includes collapsed nodes. Select a row or card to focus its observed branch.";
      if (found.truncated) status.textContent += " · query limited to 256 characters / 16 words";
    }
    function jump(delta, keyboard) {
      if (!found.matches.length) return;
      index = (index + delta + found.matches.length) % found.matches.length;
      focusedId = null; update(); handlers.onMatch(found.matches[index].id, keyboard);
    }
    function reset() {
      input.value = ""; found = search(model, ""); index = -1; focusedId = null;
      update(); handlers.onClear();
    }
    listen(input, "input", () => {
      if (!input.value.trim()) { reset(); return; }
      handlers.onStart(); found = search(model, input.value); index = -1; focusedId = null;
      if (found.matches.length) jump(1, false);
      else { update(); handlers.onChange(); }
    });
    listen(input, "keydown", event => {
      if (event.key === "Enter") { event.preventDefault(); jump(event.shiftKey ? -1 : 1, false); }
      else if (event.key === "Escape") { event.preventDefault(); event.stopPropagation?.(); reset(); }
    });
    panel.append(input, previous, next, focus, clear, status, scope); update();
    return {element: panel, clear: reset, focusInput: () => input.focus?.(),
      hasMatch: id => found.matches.some(match => match.id === id),
      setSelection: id => { selectedId = id; update(); },
      dispose: () => listeners.splice(0).forEach(remove => remove())};
  }

  function layout(model, options) {
    model = model && typeof model === "object" ? model : {};
    options = options || {};
    const input = Array.isArray(model.nodes) ? model.nodes : [];
    const raw = input.slice(0, LIMITS.nodes), ids = new Map(), byId = new Map(), laneById = new Map();
    let invalidNodes = 0, invalidLinks = 0, cycleLinks = 0;
    for (const lane of (Array.isArray(model.lanes) ? model.lanes : []).slice(0, LIMITS.lanes)) {
      if (lane && identity(lane.id) && !laneById.has(lane.id)) laneById.set(lane.id, lane);
    }
    for (const node of raw) if (node && identity(node.id)) ids.set(node.id, (ids.get(node.id) || 0) + 1);
    for (const node of raw) {
      if (!node || !identity(node.id) || ids.get(node.id) !== 1 || !["invocation", "marker"].includes(node.kind)
          || !laneById.has(node.laneId)) { invalidNodes++; continue; }
      byId.set(node.id, {id: node.id, node, laneId: node.laneId, parentId: null, children: [], descendantCount: 0});
    }
    for (const item of byId.values()) {
      const node = item.node, parent = byId.get(node.parentId);
      if (node.parentId == null) continue;
      if (node.relationship !== "observed-nesting" || !parent || parent.node.kind !== "invocation" || parent.laneId !== node.laneId) invalidLinks++;
      else item.parentId = parent.id;
    }
    // Each node has at most one parent. Remove cycle edges, never invent a root
    // call or reattach a child to an unrelated visible method.
    const done = new Set(), cyclic = new Set();
    for (const start of byId.keys()) {
      const path = [], positions = new Map(); let id = start;
      while (id && !done.has(id) && !positions.has(id)) {
        positions.set(id, path.length); path.push(id); id = byId.get(id).parentId;
      }
      if (id && positions.has(id)) for (const member of path.slice(positions.get(id))) cyclic.add(member);
      path.forEach(member => done.add(member));
    }
    for (const id of cyclic) { byId.get(id).parentId = null; cycleLinks++; }
    for (const item of byId.values()) if (item.parentId) byId.get(item.parentId).children.push(item.id);
    function count(id) {
      const item = byId.get(id);
      item.descendantCount = item.children.reduce((sum, child) => sum + 1 + count(child), 0);
      return item.descendantCount;
    }
    for (const item of byId.values()) if (!item.parentId) count(item.id);
    const collapsed = options.collapsed instanceof Set ? options.collapsed : new Set();
    const limit = Math.min(LIMITS.nodes, Math.max(1, integer(options.visibleLimit) || LIMITS.nodes));
    const nodes = [], lanes = [];
    let y = BOX.padding, hiddenNodes = 0, depthOmitted = 0;
    for (const lane of laneById.values()) {
      const members = [...byId.values()].filter(item => item.laneId === lane.id);
      if (options.laneId && options.laneId !== lane.id) { hiddenNodes += members.length; continue; }
      if (!members.length) continue;
      const startY = y, laneNodes = [];
      function visit(item, depth, hidden) {
        const overDepth = depth >= LIMITS.depth;
        const overLimit = nodes.length >= limit;
        if (hidden || overLimit) hiddenNodes++;
        else if (overDepth) depthOmitted++;
        else if (options.includedIds instanceof Set && !options.includedIds.has(item.id)) hiddenNodes++;
        else {
          const expanded = item.id === options.expandedId;
          const width = expanded ? Math.max(BOX.width, Math.min(520, number(options.expandedWidth) || BOX.width)) : BOX.width;
          const height = expanded ? Math.max(BOX.height, Math.min(4096, number(options.expandedHeight) || BOX.height)) : BOX.height;
          const positioned = {...item, depth, x: 0, y: 0, width, height};
          nodes.push(positioned); laneNodes.push(positioned);
        }
        for (const child of item.children) visit(byId.get(child), depth + 1, hidden || overLimit || collapsed.has(item.id));
      }
      members.filter(item => !item.parentId).forEach(item => visit(item, 0, false));
      if (laneNodes.length) {
        // Center a parent beside its observed children instead of spending a
        // separate row on every ancestor. Topology and preorder stay unchanged.
        const visible = new Map(laneNodes.map(item => [item.id, item])), heights = new Map();
        const gap = BOX.row - BOX.height;
        function measure(item) {
          const children = item.children.map(id => visible.get(id)).filter(Boolean);
          const childHeight = children.reduce((sum, child) => sum + measure(child), 0) + Math.max(0, children.length - 1) * gap;
          const height = Math.max(item.height, childHeight);
          heights.set(item.id, {height, childHeight, children}); return height;
        }
        function place(item, top) {
          const space = heights.get(item.id);
          item.y = top + (space.height - item.height) / 2;
          let childTop = top + (space.height - space.childHeight) / 2;
          for (const child of space.children) { place(child, childTop); childTop += heights.get(child.id).height + gap; }
        }
        let rowOffset = 0;
        for (const item of laneNodes.filter(item => !visible.has(item.parentId))) {
          const height = measure(item);
          place(item, startY + BOX.laneHeader + rowOffset); rowOffset += height + gap;
        }
        const height = BOX.laneHeader + rowOffset - gap + 16;
        lanes.push({id: lane.id, threadName: text(lane.threadName) || "Unknown thread name", y: startY, height});
        y += height + 24;
      }
    }
    // One expanded evidence card changes row/column spacing, not graph topology.
    const columns = Array(LIMITS.depth).fill(BOX.width), offsets = [BOX.padding];
    for (const item of nodes) columns[item.depth] = Math.max(columns[item.depth], item.width);
    for (let depth = 1; depth < LIMITS.depth; depth++) offsets[depth] = offsets[depth - 1] + columns[depth - 1] + BOX.column - BOX.width;
    for (const item of nodes) item.x = offsets[item.depth];
    const positioned = new Map(nodes.map(item => [item.id, item]));
    const edges = nodes.filter(item => item.parentId && positioned.has(item.parentId)).map(item => {
      const parent = positioned.get(item.parentId), x = parent.x + parent.width, middle = x + 18;
      return {from: parent.id, to: item.id,
        path: `M ${x} ${parent.y + BOX.height / 2} H ${middle} V ${item.y + BOX.height / 2} H ${item.x}`};
    });
    return {nodes, edges, lanes,
      width: nodes.length ? Math.max(...nodes.map(item => item.x + item.width)) + BOX.padding : 0,
      height: nodes.length ? y : 0, totalNodes: input.length,
      omittedNodes: Math.max(0, input.length - raw.length) + invalidNodes + depthOmitted,
      hiddenNodes, invalidNodes, invalidLinks, cycleLinks};
  }

  function label(node) {
    if (node.kind === "invocation") return `${short(node.className)}.${text(node.methodName) || "Unknown method"}`;
    if (node.eventType === "EXCEPTION") return `Exception · ${short(node.exceptionType)}`;
    const boundary = node.boundary || {};
    if (node.eventType === "DB_ROUTE") return `DB route · ${text(boundary.destination || node.route) || "Unknown route"}`;
    if (dependencies?.jdbcDetails(boundary)) return `${dependencies.isSqlLimit(boundary) ? "SQL capture limit" : "SQL query"} · ${text(boundary.system)} · ${text(boundary.operation)}`;
    if (boundary.evidenceKind === "client-boundary" && ["database", "cache"].includes(boundary.boundaryKind)) return `${boundary.boundaryKind === "cache" ? "Cache" : "Database"} call · ${text(boundary.system) || "Unknown system"} · ${text(boundary.operation) || "Operation unknown"}`;
    if (boundary.boundaryKind === "messaging") return `Queue ${text(boundary.direction) || "boundary"} · ${text(boundary.system) || "Unknown system"}`;
    return `${node.eventType === "EXTERNAL_CALL" ? "External boundary" : text(node.eventType) || "Marker"} · ${text(boundary.system || node.system) || short(node.className)}`;
  }

  function render(container, model, options) {
    if (!container || !container.ownerDocument) throw new TypeError("A DOM container is required");
    model = model && typeof model === "object" ? model : {};
    options = options || {};
    const document = container.ownerDocument, staticListeners = [], dynamicListeners = [], detailListeners = [];
    let stopped = false, observer = null, drag = null, scale = 1, autoFit = true, fitAll = false, fullscreen = false;
    let selectedId = null, expandedId = null, laneId = null, visibleLimit = 40, result = null;
    let includedIds = null, normalView = null;
    const collapsed = new Set(), buttons = new Map();
    const element = (tag, cls, value) => {
      const item = document.createElement(tag);
      if (cls) item.className = cls;
      if (value !== undefined) item.textContent = value;
      return item;
    };
    function listen(item, name, handler, dynamic = false) {
      item.addEventListener(name, handler);
      (dynamic === "detail" ? detailListeners : dynamic ? dynamicListeners : staticListeners).push(() => item.removeEventListener(name, handler));
    }
    const button = (cls, title, action, dynamic = false) => {
      const item = element("button", cls, title); item.type = "button";
      listen(item, "click", () => { if (!stopped) action(); }, dynamic); return item;
    };
    const wrapper = element("section", "teg"); wrapper.setAttribute("aria-label", "Observed method execution");
    const heading = element("div", "teg-heading");
    heading.append(element("h3", "", "Call tree"), element("span", "teg-subtitle", "Observed method nesting"));
    const context = [text(options.trace?.service || options.trace?.applicationId), text(options.trace?.lensId)].filter(Boolean).join(" · ");
    if (context) {
      const label = element("span", "teg-context", context); label.title = context; heading.append(label);
    }
    if (options.saved === true) heading.append(element("span", "teg-capture-label", "Saved · not live"));
    const toolbar = element("div", "teg-toolbar");
    toolbar.setAttribute("aria-label", "Execution diagram controls");
    const zoomLabel = element("output", "teg-zoom-label", "100%"); zoomLabel.setAttribute("aria-live", "polite");
    const threadLabel = element("label", "teg-thread-label", "Thread name ");
    const filter = element("select", "teg-thread-filter"); filter.setAttribute("aria-label", "Filter by thread name");
    const all = element("option", "", "All thread names"); all.value = ""; filter.append(all);
    const knownLanes = new Set();
    for (const lane of (Array.isArray(model.lanes) ? model.lanes : []).slice(0, LIMITS.lanes)) {
      if (!lane || !identity(lane.id) || knownLanes.has(lane.id)) continue;
      knownLanes.add(lane.id);
      const item = element("option", "", text(lane.threadName) || "Unknown thread name"); item.value = lane.id; filter.append(item);
    }
    threadLabel.append(filter);
    const overview = () => {
      collapsed.clear();
      for (const item of layout(model).nodes) if (item.depth >= 1 && item.children.length) collapsed.add(item.id);
      visibleLimit = 40;
    };
    const collapseAll = button("teg-collapse-all", "Collapse all", () => {
      collapsed.clear();
      for (const item of layout(model).nodes) if (item.children.length) collapsed.add(item.id);
      expandedId = null; visibleLimit = LIMITS.nodes; autoFit = true; fitAll = true;
      draw();
    });
    collapseAll.title = "Close all branches and inline details, then fit the top-level nodes into view; keep the thread filter.";
    const overviewButton = button("teg-overview", "Overview", () => { optionsMenu.open = false; overview(); autoFit = true; fitAll = false; draw(); });
    overviewButton.title = "Show the first two nesting levels (up to 40 nodes) and restore readable zoom; keep the thread filter.";
    const zoomGroup = element("div", "teg-control-group"); zoomGroup.setAttribute("role", "group"); zoomGroup.setAttribute("aria-label", "Zoom");
    const zoomOut = button("teg-zoom-out", "−", () => zoom(scale / 1.25)); zoomOut.setAttribute("aria-label", "Zoom out");
    const zoomIn = button("teg-zoom-in", "+", () => zoom(scale * 1.25)); zoomIn.setAttribute("aria-label", "Zoom in");
    zoomGroup.append(button("teg-fit", "Fit", () => { autoFit = true; fitAll = true; applyFit(); }), zoomOut, zoomLabel, zoomIn);
    const branchGroup = element("div", "teg-control-group teg-branch-controls"); branchGroup.setAttribute("role", "group"); branchGroup.setAttribute("aria-label", "Branches");
    branchGroup.append(
      button("teg-expand-all", "Expand all", () => { collapsed.clear(); visibleLimit = LIMITS.nodes; autoFit = false; draw(); }),
      collapseAll);
    const optionsMenu = element("details", "teg-options"), optionsPanel = element("div", "teg-options-panel");
    optionsPanel.append(threadLabel, overviewButton, button("teg-zoom-reset", "100%", () => { optionsMenu.open = false; zoom(1); }));
    optionsMenu.append(element("summary", "", "More"), optionsPanel);
    const fullscreenDialog = element("dialog", "teg-fullscreen-dialog"); fullscreenDialog.setAttribute("aria-label", "Execution flow fullscreen");
    const fullscreenButton = button("teg-fullscreen", "Fullscreen", () => fullscreen ? closeFullscreen() : openFullscreen());
    fullscreenButton.setAttribute("aria-pressed", "false");
    const toolbarStatus = element("p", "teg-toolbar-status"); toolbarStatus.setAttribute("role", "status");
    toolbar.append(zoomGroup, branchGroup, optionsMenu, fullscreenButton);
    const searchUI = searchControls(document, model, "teg", {
      onStart() {
        if (!normalView) normalView = {collapsed: new Set(collapsed), laneId, visibleLimit, selectedId, expandedId,
          left: viewport.scrollLeft, top: viewport.scrollTop};
      },
      onMatch(id, focus) {
        includedIds = null; laneId = null; filter.value = ""; visibleLimit = LIMITS.nodes; autoFit = false;
        const byId = new Map(layout(model).nodes.map(item => [item.id, item]));
        let item = byId.get(id);
        while (item) { collapsed.delete(item.id); item = byId.get(item.parentId); }
        selectedId = id; expandedId = id; draw();
        reveal(result.nodes.find(item => item.id === id), focus);
      },
      onChange() { includedIds = null; autoFit = false; draw(); },
      onFocus(id) {
        includedIds = focusIds(model, id); laneId = null; filter.value = ""; visibleLimit = LIMITS.nodes; autoFit = false;
        for (const member of includedIds) collapsed.delete(member);
        draw(id);
      },
      onClear() {
        includedIds = null; autoFit = false;
        const saved = normalView; normalView = null;
        if (saved) {
          collapsed.clear(); saved.collapsed.forEach(id => collapsed.add(id));
          laneId = saved.laneId; filter.value = laneId || ""; visibleLimit = saved.visibleLimit;
          selectedId = saved.selectedId; expandedId = saved.expandedId;
        }
        draw();
        if (saved) { viewport.scrollLeft = saved.left; viewport.scrollTop = saved.top; }
      }
    });
    staticListeners.push(() => searchUI.dispose()); toolbar.append(searchUI.element);
    const counts = element("p", "teg-counts");
    const notes = element("details", "teg-notes");
    heading.append(notes);
    const meta = element("div", "teg-meta"); meta.append(counts);
    const frame = element("div", "teg-frame");
    const viewport = element("div", "teg-viewport");
    viewport.setAttribute("aria-label", "Scrollable execution diagram; drag the background to pan");
    const stage = element("div", "teg-stage"), canvas = element("div", "teg-canvas");
    stage.append(canvas); viewport.append(stage);
    // Keep controls inside the diagram frame, but outside its scroll/zoom layer.
    // The reserved toolbar row avoids covering nodes at any scroll position.
    frame.append(toolbar, viewport);
    const details = element("section", "teg-details"); details.setAttribute("aria-label", "Selected execution evidence");
    details.id = `teg-evidence-${++nextDetailsId}`;
    wrapper.append(heading, meta, toolbarStatus, frame);
    container.replaceChildren(wrapper);
    listen(wrapper, "keydown", event => {
      if (event.key === "/" && !["input", "textarea", "select"].includes(String(event.target?.tagName).toLowerCase())) {
        event.preventDefault(); searchUI.focusInput();
      }
    });

    function closeFullscreen(restoreFocus = true) {
      if (!fullscreen) return;
      fullscreen = false;
      container.append(wrapper);
      if (fullscreenDialog.open) fullscreenDialog.close();
      fullscreenDialog.remove();
      fullscreenButton.textContent = "Fullscreen"; fullscreenButton.setAttribute("aria-pressed", "false");
      if (restoreFocus) fullscreenButton.focus({preventScroll: true});
    }
    function openFullscreen() {
      if (typeof fullscreenDialog.showModal !== "function") {
        toolbarStatus.textContent = "Fullscreen is not available in this browser. The diagram remains usable below."; return;
      }
      optionsMenu.open = false;
      try {
        container.append(fullscreenDialog); fullscreenDialog.append(wrapper); fullscreenDialog.showModal();
        fullscreen = true; toolbarStatus.textContent = "";
        fullscreenButton.textContent = "Exit fullscreen"; fullscreenButton.setAttribute("aria-pressed", "true");
        fullscreenButton.focus({preventScroll: true});
      } catch (_) {
        container.append(wrapper); if (fullscreenDialog.open) fullscreenDialog.close(); fullscreenDialog.remove();
        toolbarStatus.textContent = "Could not open fullscreen. The diagram remains usable below.";
        fullscreenButton.focus({preventScroll: true});
      }
    }
    listen(fullscreenDialog, "cancel", event => { event.preventDefault(); closeFullscreen(); });
    listen(fullscreenDialog, "close", () => closeFullscreen());

    function setScale(value) {
      scale = Math.max(0.001, Math.min(2, value));
      canvas.style.transform = `scale(${scale})`;
      stage.style.width = `${Math.max(1, result.width * scale)}px`;
      stage.style.height = `${Math.max(1, result.height * scale)}px`;
      zoomLabel.textContent = `${Number((scale * 100).toFixed(scale < 0.01 ? 1 : 0))}%`;
    }
    function applyFit() {
      if (stopped || !result) return;
      const availableWidth = viewport.clientWidth || container.clientWidth || 800;
      setScale(fitAll ? fitScale(result.width, result.height, availableWidth, viewport.clientHeight || 480) : 1);
      viewport.scrollLeft = 0; viewport.scrollTop = 0;
    }
    function zoom(value) {
      autoFit = false;
      const old = scale, centerX = (viewport.scrollLeft + viewport.clientWidth / 2) / old;
      const centerY = (viewport.scrollTop + viewport.clientHeight / 2) / old;
      setScale(value);
      viewport.scrollLeft = Math.max(0, centerX * scale - viewport.clientWidth / 2);
      viewport.scrollTop = Math.max(0, centerY * scale - viewport.clientHeight / 2);
    }
    function showDetails(item) {
      detailListeners.splice(0).forEach(remove => remove());
      const node = item.node, fields = element("dl", "teg-fields");
      const field = (key, value) => fields.append(element("dt", "", key), element("dd", "", value));
      field("Thread name", text((Array.isArray(model.lanes) ? model.lanes : []).slice(0, LIMITS.lanes).find(lane => lane && lane.id === node.laneId)?.threadName) || "Unknown");
      field("Class", text(node.className) || "Unavailable");
      if (node.methodName) field("Method", text(node.methodName));
      if (node.kind === "invocation") {
        field("Outcome", text(node.outcome) || "Unknown");
        field("Inclusive duration", node.complete === true ? milliseconds(node.durationMs) : "Unavailable — incomplete nesting evidence");
        if (node.exceptionType) field("Escaping exception", text(node.exceptionType) + " — does not necessarily fail the request");
      } else {
        const boundary = node.boundary || {};
        if (node.exceptionType) field("Exception", text(node.exceptionType));
        if (boundary.destination || node.route) field("Destination / route", text(boundary.destination || node.route));
        if (boundary.operation) field("Operation", text(boundary.operation));
        if (boundary.status) field("Observation", text(boundary.status));
        field("Marker evidence", dependencies?.jdbcDetails(boundary) ? boundary.evidenceKind === "jdbc-execution"
          ? "Timed JDBC client execution; a returned call does not establish transaction commit." : dependencies.summaryBoundary(boundary).evidence
          : node.eventType === "DB_ROUTE" ? "Pool selection only; no query or database success implied."
          : node.eventType === "EXCEPTION" ? "Exception observation with unmatched method entry; it does not necessarily fail the request."
          : node.eventType === "METHOD_EXIT" ? "Unmatched method exit; no invocation or duration is invented."
          : "Point observation, not a timed call, return, or proof of remote processing.");
      }
      const lines = node.observedLines;
      if (lines && integer(lines.first) && integer(lines.last)) field("Observed line coordinates", `${text(node.className) || "Unknown class"}: first ${lines.first}, last ${lines.last} (${integer(lines.count) ?? "unknown"} markers). Not a continuous source range; local source may differ from the captured build.`);
      else field("Observed line coordinates", "No line coordinates associated with this node.");
      field("Relationship", item.parentId ? "Observed instrumented nesting only; intervening uninstrumented calls may be absent." : "No supported parent edge in this view; not proof of a request entry.");
      details.replaceChildren(fields);
      if (dependencies?.jdbcDetails(node.boundary)) details.append(dependencies.renderLazyJdbc(document, node.boundary,
        options.loadBoundaryData ? () => options.loadBoundaryData(node) : null));
      if (typeof options.onRevealEvidence === "function" && (node.entry || node.evidence || lines)) {
        details.append(button("teg-source", "Show source in timeline", () => options.onRevealEvidence(node), "detail"));
      }
      const location = sourceLocation(node);
      if (location && typeof options.onOpenSource === "function") {
        details.append(button("teg-source teg-open-source", "Open local source", () => options.onOpenSource(location), "detail"));
      }
    }
    function markSelection() {
      for (const [id, itemButton] of buttons) {
        itemButton.setAttribute("aria-pressed", String(id === selectedId)); itemButton.tabIndex = id === selectedId ? 0 : -1;
      }
    }
    function reveal(item, focus) {
      if (focus && item) buttons.get(item.id)?.focus({preventScroll: true});
      if (item) {
        const left = item.x * scale, top = item.y * scale;
        if (left < viewport.scrollLeft || item.width * scale > viewport.clientWidth) viewport.scrollLeft = left;
        else if ((item.x + item.width) * scale > viewport.scrollLeft + viewport.clientWidth) viewport.scrollLeft = (item.x + item.width) * scale - viewport.clientWidth;
        if (top < viewport.scrollTop || item.height * scale > viewport.clientHeight) viewport.scrollTop = top;
        else if ((item.y + item.height) * scale > viewport.scrollTop + viewport.clientHeight) viewport.scrollTop = (item.y + item.height) * scale - viewport.clientHeight;
      }
    }
    function select(item, focus, expand = true) {
      const anchor = {x: item.x * scale - viewport.scrollLeft, y: item.y * scale - viewport.scrollTop};
      // Inspecting evidence changes the layout, not the user's chosen zoom.
      // Leave Fit/zoom/Overview as explicit controls for changing the scale.
      autoFit = false;
      selectedId = item.id; expandedId = expand ? item.id : null;
      draw(item.id, {anchor, focus});
    }
    function renderNotes() {
      const modelCounts = model.counts || {}, omitted = integer(modelCounts.nodesOmitted) || 0;
      const warnings = Array.isArray(model.warnings) ? model.warnings : [];
      const limitations = Array.isArray(model.limitations) ? model.limitations : [];
      const partial = model.partial || omitted || result.omittedNodes || result.invalidLinks || result.cycleLinks;
      notes.replaceChildren(element("summary", "", `${partial ? "Partial evidence" : "Observed evidence only"} · limits and interpretation`));
      const list = element("ul");
      const add = value => list.append(element("li", "", value));
      if (omitted) add(`${omitted} model nodes omitted upstream; expanding cannot recover them.`);
      if (integer(modelCounts.eventsOmitted)) add(`${modelCounts.eventsOmitted} events not processed by reconstruction.`);
      if (result.omittedNodes) add(`${result.omittedNodes} nodes omitted by renderer validation / 200-node / 16-level bounds.`);
      if (result.hiddenNodes) add(`${result.hiddenNodes} nodes hidden by overview, collapsed subtrees, thread filter or branch focus; expand, adjust the filter or clear focus.`);
      if (includedIds) add("Branch focus hides unrelated model nodes. Clear search / focus restores the previous view; no missing or asynchronous relationship is inferred.");
      if (result.invalidLinks) add(`${result.invalidLinks} unsupported, missing-parent or cross-lane links omitted.`);
      if (result.cycleLinks) add(`${result.cycleLinks} cyclic parent links omitted; no cyclic call structure inferred.`);
      for (const warning of warnings.slice(0, 32)) add(typeof warning === "string" ? text(warning) : `${text(warning?.code) || "Unknown warning"} (${integer(warning?.count) ?? "?"})`);
      if (warnings.length > 32) add(`${warnings.length - 32} additional warning categories omitted from this view.`);
      for (const limitation of limitations.slice(0, 8)) add(text(limitation, 512));
      if (!limitations.length) add("Nesting is observed, not a complete/direct call graph. Thread names are not stable IDs. No asynchronous links, CPU time or complete coverage are inferred.");
      notes.append(list);
    }
    function draw(focusId, selection) {
      if (stopped) return;
      dynamicListeners.splice(0).forEach(remove => remove()); detailListeners.splice(0).forEach(remove => remove()); buttons.clear();
      result = layout(model, {collapsed, laneId, visibleLimit, includedIds});
      if (!result.nodes.some(item => item.id === expandedId)) expandedId = null;
      counts.textContent = `${result.nodes.length} nodes shown · ${result.hiddenNodes} hidden in this view · ${result.omittedNodes + (integer(model.counts?.nodesOmitted) || 0)} omitted · ${result.lanes.length} thread-name lanes`;
      renderNotes(); canvas.replaceChildren();
      canvas.style.width = `${result.width}px`; canvas.style.height = `${result.height}px`;
      if (!result.nodes.length) {
        selectedId = null; searchUI.setSelection(null);
        canvas.append(element("p", "teg-empty", "No observed invocation or marker nodes are available for this view."));
        canvas.style.width = "100%";
        setScale(1); return;
      }
      const blocks = new Map();
      const expandedWidth = Math.max(BOX.width, Math.min(520, (viewport.clientWidth || 800) / scale - 48));
      for (const item of result.nodes) {
        const node = item.node, block = element("div", "teg-node-wrap");
        block.dataset.nodeId = item.id;
        block.style.left = `${item.x}px`; block.style.top = `${item.y}px`; block.style.width = `${item.width}px`; block.style.height = `${item.height}px`;
        const boundaryStyle = node.kind === "marker" && node.boundary?.evidenceKind === "client-boundary" && node.boundary?.boundaryKind === "cache" ? " teg-cache"
          : node.kind === "marker" && node.boundary?.boundaryKind === "messaging" ? " teg-queue" : "";
        const card = element("button", `teg-node teg-${node.kind}${boundaryStyle}${node.eventType === "EXCEPTION" || node.outcome === "threw" ? " teg-exception" : ""}${searchUI.hasMatch(item.id) ? " teg-search-match" : ""}`);
        card.type = "button"; card.dataset.nodeId = node.id; card.setAttribute("aria-label", label(node));
        card.title = node.kind === "invocation" ? `${text(node.className)}.${text(node.methodName)}()` : label(node);
        if (node.kind === "invocation") {
          const display = classDisplay(node.className), name = element("span", "teg-name");
          const classRow = element("span", "teg-class-row");
          classRow.append(element("span", "teg-class", display.name));
          if (display.proxy) classRow.append(element("span", "teg-proxy", "Spring proxy"));
          name.append(classRow, element("span", "teg-method", `${text(node.methodName) || "Unknown method"}()`));
          card.append(name);
        } else card.append(element("span", "teg-kind", text(node.eventType) || "MARKER"), element("span", "teg-name teg-marker-name", label(node)));
        if (node.kind === "invocation") card.append(element("span", "teg-metric", `${text(node.outcome) || "unknown"} · ${node.complete === true ? milliseconds(node.durationMs) : "incomplete evidence"}`));
        else card.append(element("span", "teg-metric", [text(node.boundary?.operation), text(node.boundary?.status), text(node.boundary?.destination)].filter(Boolean).join(" · ") || "Point observation"));
        if (item.descendantCount) card.append(element("span", "teg-child-count", `${item.descendantCount} nested nodes${collapsed.has(item.id) ? " · collapsed" : ""}`));
        card.setAttribute("aria-expanded", String(item.id === expandedId));
        if (item.id === expandedId) card.setAttribute("aria-controls", details.id);
        listen(card, "click", () => select(result.nodes.find(value => value.id === item.id), true, expandedId !== item.id), true);
        listen(card, "keydown", event => {
          let target = null; const index = result.nodes.findIndex(value => value.id === item.id);
          if (event.key === "ArrowDown") target = result.nodes[index + 1];
          else if (event.key === "ArrowUp") target = result.nodes[index - 1];
          else if (event.key === "ArrowLeft") target = result.nodes.find(value => value.id === item.parentId);
          else if (event.key === "ArrowRight") {
            if (collapsed.has(item.id)) { collapsed.delete(item.id); visibleLimit = LIMITS.nodes; autoFit = false; draw(item.id); }
            target = result.nodes.find(value => value.parentId === item.id);
          } else if (event.key === "Home") target = result.nodes[0];
          else if (event.key === "End") target = result.nodes[result.nodes.length - 1];
          else if (event.key === "Escape" && fullscreen) { event.preventDefault(); closeFullscreen(); return; }
          else if (event.key === "Escape" && expandedId === item.id) { event.preventDefault(); select(result.nodes[index], true, false); return; }
          else return;
          event.preventDefault(); if (target) select(target, true);
        }, true);
        buttons.set(item.id, card); block.append(card);
        if (item.id === expandedId) {
          block.className += " teg-node-expanded";
          block.style.width = `${expandedWidth}px`; block.style.height = "auto";
          showDetails(item); block.append(details);
        }
        if (item.children.length) {
          const toggle = button("teg-collapse", collapsed.has(item.id) ? "+" : "−", () => {
            if (collapsed.has(item.id)) { collapsed.delete(item.id); visibleLimit = LIMITS.nodes; }
            else collapsed.add(item.id);
            autoFit = false;
            draw(item.id);
          }, true);
          toggle.dataset.nodeId = item.id; toggle.setAttribute("aria-label", `${collapsed.has(item.id) ? "Expand" : "Collapse"} ${label(node)} subtree`);
          toggle.setAttribute("aria-expanded", String(!collapsed.has(item.id))); block.append(toggle);
        }
        canvas.append(block); blocks.set(item.id, block);
      }
      // Measure only the selected card, once per interaction. offsetHeight is
      // unscaled, so connector/layout coordinates stay correct at any zoom.
      const expandedBlock = blocks.get(expandedId);
      result = layout(model, {collapsed, laneId, visibleLimit, includedIds, expandedId, expandedWidth,
        expandedHeight: expandedBlock ? expandedBlock.offsetHeight || BOX.height + 340 : BOX.height});
      const layers = [];
      for (const [laneIndex, lane] of result.lanes.entries()) {
        const region = element("div", `teg-lane${laneIndex % 2 ? " teg-lane-alternate" : ""}`); region.style.top = `${lane.y}px`;
        region.style.width = `${result.width}px`; region.style.height = `${lane.height}px`;
        region.append(element("h4", "", `Thread name: ${lane.threadName}`)); layers.push(region);
      }
      const namespace = "http://www.w3.org/2000/svg", svg = document.createElementNS(namespace, "svg");
      svg.setAttribute("class", "teg-connectors"); svg.setAttribute("width", result.width); svg.setAttribute("height", result.height); svg.setAttribute("aria-hidden", "true");
      for (const edge of result.edges) {
        const path = document.createElementNS(namespace, "path"); path.setAttribute("d", edge.path); path.setAttribute("class", "teg-edge"); svg.append(path);
      }
      for (const item of result.nodes) {
        const block = blocks.get(item.id);
        block.style.left = `${item.x}px`; block.style.top = `${item.y}px`;
        block.style.width = `${item.width}px`; block.style.height = `${item.height}px`;
      }
      canvas.replaceChildren(...layers, svg, ...blocks.values());
      canvas.style.width = `${result.width}px`; canvas.style.height = `${result.height}px`;
      const selected = result.nodes.find(item => item.id === (focusId || selectedId)) || result.nodes[0];
      selectedId = selected.id; markSelection(); searchUI.setSelection(selectedId);
      if (autoFit && !selection) applyFit(); else setScale(scale);
      if (selection) {
        viewport.scrollLeft = Math.max(0, selected.x * scale - selection.anchor.x);
        viewport.scrollTop = Math.max(0, selected.y * scale - selection.anchor.y);
        reveal(selected, selection.focus);
      } else if (focusId) reveal(selected, true);
    }
    listen(filter, "change", () => { optionsMenu.open = false; laneId = filter.value || null; autoFit = true; fitAll = false; draw(); });
    const interactive = target => {
      for (let item = target; item && item !== viewport; item = item.parentNode) if (item.classList?.contains("teg-node-wrap") || ["button", "select", "a", "input"].includes(String(item.tagName).toLowerCase())) return true;
      return false;
    };
    listen(viewport, "pointerdown", event => {
      if (event.button !== 0 || interactive(event.target)) return;
      drag = {id: event.pointerId, x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop};
      viewport.setAttribute("data-panning", "true"); viewport.setPointerCapture?.(event.pointerId);
    });
    listen(viewport, "pointermove", event => {
      if (!drag || drag.id !== event.pointerId) return;
      viewport.scrollLeft = drag.left + drag.x - event.clientX; viewport.scrollTop = drag.top + drag.y - event.clientY;
    });
    const stopDrag = () => {
      if (drag && viewport.hasPointerCapture?.(drag.id)) viewport.releasePointerCapture?.(drag.id);
      drag = null; viewport.setAttribute("data-panning", "false");
    };
    listen(viewport, "pointerup", stopDrag); listen(viewport, "pointercancel", stopDrag); listen(viewport, "lostpointercapture", () => { drag = null; });

    // HTTP-call snapshots have no invocation IDs. Keep them separate rather
    // than attaching a plausible-looking destination to an arbitrary method.
    const calls = options.trace && Array.isArray(options.trace.httpCalls) ? options.trace.httpCalls : [];
    if (calls.length || options.trace?.httpCallsTruncated) {
      const boundaries = element("details", "teg-unplaced");
      boundaries.append(element("summary", "", `HTTP boundaries · ${calls.length} unplaced observations`),
        element("p", "teg-muted", "These HTTP calls cannot be placed in the method stack: invocation ownership is unavailable. No method edge or asynchronous link is inferred."));
      for (const call of calls.slice(0, LIMITS.httpCalls)) {
        if (!call || typeof call !== "object") continue;
        const card = element("article", "teg-http-card");
        card.append(element("strong", "", `${text(call.httpMethod) || "HTTP"} ${text(call.peer) || "Unknown peer"}${text(call.path)}`),
          element("p", "", `${text(call.status) || "Unknown status"}${integer(call.httpStatus) ? ` · HTTP ${call.httpStatus}` : ""} · unplaced boundary`));
        boundaries.append(card);
      }
      if (calls.length > LIMITS.httpCalls) boundaries.append(element("p", "", `${calls.length - LIMITS.httpCalls} additional HTTP boundary cards omitted.`));
      if (options.trace.httpCallsTruncated) boundaries.append(element("p", "", "HTTP boundary capture was truncated; additional calls may be absent."));
      wrapper.append(boundaries);
    }
    overview(); draw();
    const onResize = () => {
      if (stopped) return;
      if (expandedId) {
        const active = document.activeElement, sourceFocused = active?.classList?.contains("teg-source");
        const localSourceFocused = active?.classList?.contains("teg-open-source");
        const nodeFocused = active?.classList?.contains("teg-node");
        const item = result.nodes.find(value => value.id === selectedId);
        draw(selectedId, {anchor: {x: item.x * scale - viewport.scrollLeft, y: item.y * scale - viewport.scrollTop}, focus: false});
        if (sourceFocused) details.querySelector(localSourceFocused ? ".teg-open-source" : ".teg-source")?.focus({preventScroll: true});
        else if (nodeFocused) buttons.get(selectedId)?.focus({preventScroll: true});
      } else if (autoFit) applyFit();
    };
    if (typeof root.ResizeObserver === "function") { observer = new root.ResizeObserver(onResize); observer.observe(viewport); }
    else if (typeof root.addEventListener === "function") listen(root, "resize", onResize);
    return function cleanup() {
      if (stopped) return;
      stopped = true; closeFullscreen(false); stopDrag(); observer?.disconnect();
      dynamicListeners.splice(0).forEach(remove => remove()); detailListeners.splice(0).forEach(remove => remove()); staticListeners.splice(0).forEach(remove => remove());
      buttons.clear(); wrapper.remove();
    };
  }
  return Object.freeze({render, layout, fitScale, search, focusIds, sourceLocation, searchControls, limits: LIMITS});
});
