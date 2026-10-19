/* Shared, dependency-free causal request diagram. No timestamps participate in layout. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensGraph = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const LIMITS = Object.freeze({ nodes: 96, columns: 24, edges: 384, inputNodes: 4096, inputEdges: 16384 });
  const BOX = Object.freeze({ width: 264, height: 144, columnGap: 76, rowGap: 34, padding: 44 });
  const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
  const scalar = value => ["string", "number", "boolean"].includes(typeof value) ? String(value) : "";
  const idOf = value => typeof value === "string" && value.length ? value : null;
  const numeric = value => value !== null && value !== undefined && value !== "" &&
    (typeof value === "number" || typeof value === "string") && Number.isFinite(Number(value)) && Number(value) >= 0
    ? Number(value) : null;
  const duration = value => {
    const number = numeric(value);
    return number === null ? "Unavailable" : `${number.toLocaleString(undefined, { maximumFractionDigits: 2 })} ms`;
  };
  const rawApi = node => node.kind === "dependency-call"
    ? [scalar(node.system), scalar(node.operation)].filter(Boolean).join(" · ") || "Operation unavailable"
    : [scalar(node.method), scalar(node.path)].filter(Boolean).join(" ") || scalar(node.label) || "API unavailable";
  const boundaryType = node => node.kind === "segment" ? "SERVICE SEGMENT" : node.kind === "http-call" ? "OUTGOING HTTP"
    : node.boundaryKind === "messaging" ? node.direction === "consume" ? "QUEUE CONSUME"
      : node.direction === "publish" ? "QUEUE PUBLISH" : "MESSAGING BOUNDARY"
    : node.boundaryKind === "database-route" ? "DB ROUTING DECISION"
    : node.evidenceKind === "wrapper-entry" ? "EXTERNAL WRAPPER" : "EXTERNAL BOUNDARY";
  const boundaryEvidence = node => node.kind === "http-call" ? "Caller-side HTTP observation; no remote execution is inferred"
    : node.evidenceKind === "client-boundary" ? node.direction === "consume"
      ? "Local consume boundary; message receipt, producer and processing completion are not inferred"
      : "Local client boundary; broker delivery and remote processing are not implied"
    : node.boundaryKind === "database-route" ? "Pool selection only; no database query or network success implied"
    : node.evidenceKind === "wrapper-entry" ? "Configured wrapper entry only; a network operation is not confirmed"
    : "Boundary recorded; evidence kind unknown; a network operation is not confirmed";
  const failure = node => Number(node.httpStatus) >= 400 || /^(error|failed|failure)$/i.test(scalar(node.status));

  function layout(model) {
    model = model && typeof model === "object" ? model : {};
    const inputNodes = Array.isArray(model.nodes) ? model.nodes : [];
    const inputEdges = Array.isArray(model.edges) ? model.edges : [];
    const byId = new Map();
    let invalidNodes = 0;
    inputNodes.slice(0, LIMITS.inputNodes).forEach(node => {
      if (!node || !idOf(node.id) || !["segment", "http-call", "dependency-call"].includes(node.kind) || byId.has(node.id)) invalidNodes++;
      else byId.set(node.id, node);
    });
    const outgoing = new Map(), incoming = new Map();
    byId.forEach((_, id) => { outgoing.set(id, []); incoming.set(id, []); });
    const validEdges = [];
    let invalidEdges = 0;
    inputEdges.slice(0, LIMITS.inputEdges).forEach((edge, index) => {
      if (!edge || !byId.has(edge.from) || !byId.has(edge.to) || !["outbound", "inbound", "observed-call"].includes(edge.kind)) {
        invalidEdges++;
        return;
      }
      const normalized = { ...edge, id: idOf(edge.id) || `edge-${index}` };
      validEdges.push(normalized);
      outgoing.get(edge.from).push(edge.to);
      incoming.get(edge.to).push(edge.from);
    });
    outgoing.forEach(values => values.sort(compare));
    incoming.forEach(values => values.sort(compare));
    const allIds = [...byId.keys()].sort(compare);
    const roots = [...new Set((Array.isArray(model.roots) ? model.roots : []).filter(id => byId.has(id)))].sort(compare);
    const seeds = [...new Set([...roots, ...allIds.filter(id => !incoming.get(id).length), ...allIds])];
    // Breadth-first selection retains nearby causal context while bounding DOM work.
    const kept = new Set(), queued = new Set();
    for (const seed of seeds) {
      if (kept.size >= LIMITS.nodes) break;
      if (kept.has(seed)) continue;
      const queue = [seed];
      queued.add(seed);
      for (let index = 0; index < queue.length && kept.size < LIMITS.nodes; index++) {
        const id = queue[index];
        kept.add(id);
        for (const next of outgoing.get(id)) if (!queued.has(next)) { queued.add(next); queue.push(next); }
      }
    }
    const chosen = [...kept].sort(compare);
    const chosenEdges = validEdges.filter(edge => kept.has(edge.from) && kept.has(edge.to));
    // Strongly connected components keep malformed/cyclic evidence visible without
    // treating a cycle as a return span or allowing infinite rank expansion.
    let sequence = 0;
    const indexOf = new Map(), low = new Map(), stack = [], onStack = new Set(), components = [];
    function visit(id) {
      indexOf.set(id, sequence); low.set(id, sequence++); stack.push(id); onStack.add(id);
      for (const target of outgoing.get(id)) {
        if (!kept.has(target)) continue;
        if (!indexOf.has(target)) { visit(target); low.set(id, Math.min(low.get(id), low.get(target))); }
        else if (onStack.has(target)) low.set(id, Math.min(low.get(id), indexOf.get(target)));
      }
      if (low.get(id) === indexOf.get(id)) {
        const component = [];
        let member;
        do { member = stack.pop(); onStack.delete(member); component.push(member); } while (member !== id);
        components.push(component.sort(compare));
      }
    }
    chosen.forEach(id => { if (!indexOf.has(id)) visit(id); });
    components.sort((a, b) => compare(a[0], b[0]));
    const componentOf = new Map();
    components.forEach((members, index) => members.forEach(id => componentOf.set(id, index)));
    const nextComponents = components.map(() => new Set());
    const degrees = components.map(() => 0), ranks = components.map(() => 0);
    chosenEdges.forEach(edge => {
      const from = componentOf.get(edge.from), to = componentOf.get(edge.to);
      if (from !== to && !nextComponents[from].has(to)) { nextComponents[from].add(to); degrees[to]++; }
    });
    const ready = components.map((_, index) => index).filter(index => !degrees[index]);
    while (ready.length) {
      ready.sort((a, b) => compare(components[a][0], components[b][0]));
      const component = ready.shift();
      [...nextComponents[component]].forEach(next => {
        ranks[next] = Math.max(ranks[next], ranks[component] + 1);
        if (--degrees[next] === 0) ready.push(next);
      });
    }
    const columns = [];
    chosen.forEach(id => {
      const rank = ranks[componentOf.get(id)];
      if (rank < LIMITS.columns) (columns[rank] ||= []).push(id);
    });
    const slots = new Map();
    columns.forEach(ids => {
      const parentSlot = id => {
        const positions = incoming.get(id).filter(parent => slots.has(parent)).map(parent => slots.get(parent));
        return positions.length ? positions.reduce((a, b) => a + b, 0) / positions.length : Number.MAX_SAFE_INTEGER;
      };
      ids.sort((a, b) => parentSlot(a) - parentSlot(b) || compare(a, b));
      ids.forEach((id, slot) => slots.set(id, slot));
    });
    const nodes = columns.flatMap((ids, rank) => ids.map((id, slot) => ({
      id, node: byId.get(id), rank, slot,
      x: BOX.padding + rank * (BOX.width + BOX.columnGap),
      y: BOX.padding + slot * (BOX.height + BOX.rowGap),
      width: BOX.width, height: BOX.height,
      root: roots.includes(id),
      unlinked: !incoming.get(id).length && !roots.includes(id),
      cyclic: components[componentOf.get(id)].length > 1 || outgoing.get(id).includes(id),
      downstreamObserved: validEdges.some(edge => edge.from === id && edge.kind === "observed-call"),
    })));
    const positioned = new Map(nodes.map(node => [node.id, node]));
    const edgeOccurrences = new Map();
    const drawable = chosenEdges.filter(edge => positioned.has(edge.from) && positioned.has(edge.to))
      .sort((a, b) => compare(a.from, b.from) || compare(a.to, b.to) || compare(a.kind, b.kind) || compare(a.id, b.id));
    const edges = drawable.slice(0, LIMITS.edges).map((edge, edgeIndex) => {
      const from = positioned.get(edge.from), to = positioned.get(edge.to);
      const key = JSON.stringify([edge.from, edge.to]);
      const parallel = edgeOccurrences.get(key) || 0;
      edgeOccurrences.set(key, parallel + 1);
      const offset = Math.min(parallel, 4) * 7;
      const startX = from.x + from.width, startY = from.y + from.height / 2 + offset;
      const targetY = to.y + to.height / 2 + offset;
      const cyclic = from.rank === to.rank;
      let path;
      if (from.id === to.id) {
        path = `M ${startX} ${startY - 22} C ${startX + 46} ${startY - 22}, ${startX + 46} ${startY + 22}, ${startX} ${startY + 22}`;
      } else if (cyclic) {
        const turn = startX + 24 + offset;
        path = `M ${startX} ${startY} C ${turn} ${startY}, ${turn} ${targetY}, ${to.x + to.width} ${targetY}`;
      } else if (to.rank > from.rank + 1) {
        const railY = 12 + (edgeIndex % 4) * 6;
        path = `M ${startX} ${startY} H ${startX + 24 + offset} V ${railY} H ${to.x - 24 - offset} V ${targetY} H ${to.x}`;
      } else {
        const bend = (startX + to.x) / 2 + offset;
        path = `M ${startX} ${startY} C ${bend} ${startY}, ${bend} ${targetY}, ${to.x} ${targetY}`;
      }
      return { ...edge, path, cyclic };
    });
    return {
      nodes, edges,
      width: nodes.length ? Math.max(...nodes.map(node => node.x + node.width)) + BOX.padding + 20 : 0,
      height: nodes.length ? Math.max(...nodes.map(node => node.y + node.height)) + BOX.padding : 0,
      totalNodes: inputNodes.length, totalEdges: inputEdges.length,
      omittedNodes: Math.max(0, inputNodes.length - invalidNodes - nodes.length),
      omittedEdges: Math.max(0, inputEdges.length - invalidEdges - edges.length),
      invalidNodes, invalidEdges,
      cycleCount: components.filter(members => members.length > 1 || outgoing.get(members[0]).includes(members[0])).length,
      limits: LIMITS,
    };
  }

  let renderSequence = 0;
  function render(container, model, options) {
    if (!container || !container.ownerDocument) throw new TypeError("A DOM container is required");
    model = model && typeof model === "object" ? model : {};
    options = options || {};
    const document = container.ownerDocument;
    const previous = container.querySelector(".tl-graph-node[aria-pressed=true]");
    const previousId = previous && previous.dataset.nodeId;
    const restoreFocus = container.contains(document.activeElement) && document.activeElement.classList.contains("tl-graph-node");
    const result = layout(model);
    const uid = `tl-graph-${++renderSequence}`;
    const element = (tag, className, text) => {
      const value = document.createElement(tag);
      if (className) value.className = className;
      if (text !== undefined) value.textContent = text;
      return value;
    };
    const wrapper = element("section", "tl-graph");
    wrapper.setAttribute("aria-label", "Causal request flow");
    const toolbar = element("div", "tl-graph-toolbar");
    const heading = element("div");
    heading.append(element("h3", "tl-graph-title", "Request flow"),
      element("p", "tl-graph-subtitle", "Causal flow, not a shared time axis. Durations are local to each node."));
    const counts = element("div", "tl-graph-counts", `${result.nodes.filter(item => item.node.kind === "segment").length} segments · ${result.nodes.filter(item => item.node.kind === "http-call").length} HTTP calls · ${result.nodes.filter(item => item.node.kind === "dependency-call").length} other boundaries`);
    toolbar.append(heading, counts);
    wrapper.append(toolbar);
    const legend = element("div", "tl-graph-legend");
    legend.append(element("span", "tl-graph-legend-segment", "Service segment"), element("span", "tl-graph-legend-call", "Outgoing HTTP call"), element("span", "tl-graph-legend-dependency", "Queue / external boundary"), element("span", "tl-graph-legend-edge", "Arrows = recorded boundary direction or causal links"));
    wrapper.append(legend);
    const notices = element("div", "tl-graph-notices");
    const addNotice = (text, error) => notices.append(element("p", error ? "tl-graph-notice tl-graph-error" : "tl-graph-notice", text));
    if (model.partial) addNotice("Partial capture — some service segments or downstream evidence may be unavailable.");
    if (scalar(model.completeness)) addNotice(`Capture completeness: ${scalar(model.completeness)}`);
    if (scalar(model.note)) addNotice(scalar(model.note));
    ["warnings", "errors"].forEach(field => {
      const messages = Array.isArray(model[field]) ? model[field] : [];
      const coverage = field === "warnings" && messages.length ? element("details", "tl-graph-coverage") : null;
      if (coverage) coverage.append(element("summary", "", `Coverage notes (${messages.length}) — expand for missing or unobserved evidence`));
      messages.slice(0, 12).forEach(value => {
        const message = scalar(value) || (value && [scalar(value.service), scalar(value.message) || scalar(value.error)].filter(Boolean).join(": "));
        if (message && coverage) {
          coverage.append(element("p", "tl-graph-notice", message));
          if (/limit reached|truncated/i.test(message)) addNotice(message);
        } else if (message) addNotice(message, field === "errors");
      });
      if (coverage) notices.append(coverage);
      if (messages.length > 12) addNotice(`${messages.length - 12} additional ${field} are present in the capture.`);
    });
    if (result.omittedNodes || result.omittedEdges) addNotice(`Diagram limit: showing ${result.nodes.length} of ${result.totalNodes} nodes and ${result.edges.length} of ${result.totalEdges} links. At most ${LIMITS.nodes} nodes, ${LIMITS.columns} causal columns, and ${LIMITS.edges} links are drawn; the source capture is unchanged.`);
    if (result.invalidNodes || result.invalidEdges) addNotice(`${result.invalidNodes} invalid/duplicate nodes and ${result.invalidEdges} invalid links were not drawn. No connections are inferred from metadata.`);
    if (result.cycleCount) addNotice("Cyclic causal evidence is shown with dashed links. These are recorded links, not inferred return paths.");
    if (notices.childNodes.length) wrapper.append(notices);
    if (!result.nodes.length) {
      wrapper.append(element("p", "tl-graph-empty", "No observed request-flow nodes are available in this capture."));
      container.replaceChildren(wrapper);
      return () => wrapper.remove();
    }
    const help = element("p", "tl-graph-help", "Select a node for details. Arrow keys navigate; Enter selects. Scroll to follow branches.");
    help.id = `${uid}-help`;
    wrapper.append(help);
    const viewport = element("div", "tl-graph-viewport");
    viewport.setAttribute("aria-label", "Scrollable causal diagram");
    const canvas = element("div", "tl-graph-canvas");
    canvas.style.width = `${result.width}px`;
    canvas.style.height = `${result.height}px`;
    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("class", "tl-graph-connectors");
    svg.setAttribute("width", String(result.width));
    svg.setAttribute("height", String(result.height));
    svg.setAttribute("aria-hidden", "true");
    const defs = document.createElementNS(svgNS, "defs");
    ["outbound", "inbound", "observed-call"].forEach(kind => {
      const marker = document.createElementNS(svgNS, "marker");
      marker.setAttribute("id", `${uid}-${kind}`);
      marker.setAttribute("viewBox", "0 0 10 10");
      marker.setAttribute("refX", "9"); marker.setAttribute("refY", "5");
      marker.setAttribute("markerWidth", "7"); marker.setAttribute("markerHeight", "7");
      marker.setAttribute("orient", "auto-start-reverse");
      const arrow = document.createElementNS(svgNS, "path");
      arrow.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
      arrow.setAttribute("class", `tl-graph-arrow tl-graph-${kind}`);
      marker.append(arrow); defs.append(marker);
    });
    svg.append(defs);
    result.edges.forEach(edge => {
      const path = document.createElementNS(svgNS, "path");
      path.setAttribute("d", edge.path);
      path.setAttribute("class", `tl-graph-edge tl-graph-${edge.kind}${edge.cyclic ? " tl-graph-cycle" : ""}`);
      path.setAttribute("marker-end", `url(#${uid}-${edge.kind})`);
      svg.append(path);
    });
    canvas.append(svg);
    const details = element("section", "tl-graph-details");
    details.setAttribute("aria-label", "Node details");
    const buttons = new Map();
    const selected = result.nodes.find(item => item.id === previousId) || result.nodes.find(item => item.root) || result.nodes[0];
    let selectedId = selected.id;
    let detailId = null;
    function showDetails(item) {
      if (detailId === item.id) return;
      detailId = item.id;
      const node = item.node;
      const title = element("h4", "tl-graph-detail-title", node.kind === "segment" ? scalar(node.service) || "Service segment" : boundaryType(node));
      const api = element("p", "tl-graph-detail-api", rawApi(node));
      const list = element("dl", "tl-graph-detail-list");
      const field = (name, value) => { list.append(element("dt", "", name), element("dd", "", scalar(value) || "Unavailable")); };
      field(node.kind === "dependency-call" ? "Operation" : "Raw API", rawApi(node));
      field("Local duration", duration(node.durationMs));
      field("Service instance", node.serviceInstanceId);
      field("Status", [scalar(node.status), node.httpStatus != null ? `HTTP ${scalar(node.httpStatus)}` : ""].filter(Boolean).join(" · "));
      field("Segment ID", node.segmentId);
      if (node.kind === "segment") {
        field("Entry channel", node.entryChannel);
        field("Events", numeric(node.eventCount) === null ? "Unavailable" : scalar(node.eventCount));
        field("Capture", node.truncated ? "Truncated — some events were not captured" : "No truncation reported");
      } else if (node.kind === "http-call") {
        if (node.destinationName) field("Destination name", node.destinationName);
        field("Peer", node.peer);
        field("Propagation", node.propagationStatus);
        field("Downstream evidence", item.downstreamObserved ? "Observed service segment" : "Unavailable — no downstream segment is linked");
        if (node.downstreamState) field("Reported downstream state", node.downstreamState);
        if (node.callKey) field("Call key", node.callKey);
        field("Evidence", boundaryEvidence(node));
      } else {
        field("Direction", node.direction);
        field("Destination", node.destinationName || node.destination || node.system);
        if (node.destinationName && node.destination) field("Recorded destination", node.destination);
        field("Observed in", node.service);
        field("Evidence", boundaryEvidence(node));
        field("Remote execution", "Not observed — no remote internals or producer/consumer link is invented");
        if (node.eventSeq != null) field("Event sequence", node.eventSeq);
        if (node.callKey) field("Call key", node.callKey);
      }
      if (node.parentSpanId) field("Parent span ID", node.parentSpanId);
      if (item.root) field("Connectivity", "No incoming recorded link — this does not prove request entry");
      if (item.unlinked) field("Connectivity", "Unlinked evidence — no incoming causal edge is present");
      if (item.cyclic) field("Connectivity", "Part of cyclic recorded evidence");
      details.replaceChildren(title, api, list);
      if (node.kind === "segment" && typeof options.onSelect === "function") {
        const source = element("button", "tl-graph-source", "Open segment sources");
        source.type = "button";
        source.addEventListener("click", () => options.onSelect(node));
        details.append(source);
      }
    }
    function select(item, focus) {
      selectedId = item.id;
      buttons.forEach((button, id) => {
        button.setAttribute("aria-pressed", String(id === selectedId));
        button.tabIndex = id === selectedId ? 0 : -1;
      });
      showDetails(item);
      if (focus) {
        const button = buttons.get(item.id);
        button.focus({ preventScroll: true });
        const left = item.x - 24, top = item.y - 24;
        if (left < viewport.scrollLeft) viewport.scrollLeft = left;
        else if (item.x + item.width + 24 > viewport.scrollLeft + viewport.clientWidth) viewport.scrollLeft = item.x + item.width + 24 - viewport.clientWidth;
        if (top < viewport.scrollTop) viewport.scrollTop = top;
        else if (item.y + item.height + 24 > viewport.scrollTop + viewport.clientHeight) viewport.scrollTop = item.y + item.height + 24 - viewport.clientHeight;
      }
    }
    result.nodes.forEach(item => {
      const node = item.node;
      const button = element("button", `tl-graph-node tl-graph-${node.kind}${failure(node) ? " tl-graph-failed" : ""}`);
      button.type = "button";
      button.dataset.nodeId = item.id;
      button.style.left = `${item.x}px`; button.style.top = `${item.y}px`;
      button.style.width = `${item.width}px`; button.style.height = `${item.height}px`;
      button.setAttribute("aria-describedby", help.id);
      button.setAttribute("aria-label", `${boundaryType(node)}: ${scalar(node.destinationName) || scalar(node.destination) || scalar(node.peer) || scalar(node.service)}, ${rawApi(node)}, local duration ${duration(node.durationMs)}`);
      button.setAttribute("aria-pressed", String(item.id === selectedId));
      button.tabIndex = item.id === selectedId ? 0 : -1;
      const type = boundaryType(node);
      button.append(element("span", "tl-graph-node-kind", `${type}${item.root ? " · NO INCOMING LINK" : ""}`));
      button.append(element("span", "tl-graph-node-name", node.kind === "segment" ? scalar(node.service) || scalar(node.label) || "Unknown service" : scalar(node.destinationName) || scalar(node.destination) || scalar(node.peer) || scalar(node.system) || "Unknown dependency"));
      const api = element("span", "tl-graph-node-api", rawApi(node)); api.title = rawApi(node); button.append(api);
      const status = [node.httpStatus != null ? `HTTP ${scalar(node.httpStatus)}` : scalar(node.status) || "Status unknown", `${duration(node.durationMs)} local`].join(" · ");
      button.append(element("span", "tl-graph-node-metrics", status));
      const context = node.kind === "segment" ? scalar(node.serviceInstanceId) || "Instance unavailable"
        : node.kind === "dependency-call" ? node.evidenceKind === "wrapper-entry" ? "Wrapper only · network unconfirmed" : "Local observation · remote not observed"
        : item.downstreamObserved ? "Downstream observed" : "Remote execution not observed";
      button.append(element("span", "tl-graph-node-context", `${node.truncated ? "Truncated · " : ""}${context}`));
      button.addEventListener("mouseenter", () => showDetails(item));
      button.addEventListener("mouseleave", () => showDetails(result.nodes.find(candidate => candidate.id === selectedId)));
      button.addEventListener("focus", () => select(item, false));
      button.addEventListener("click", () => select(item, false));
      button.addEventListener("keydown", event => {
        let target;
        if (event.key === "Home") target = result.nodes[0];
        else if (event.key === "End") target = result.nodes[result.nodes.length - 1];
        else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
          const direction = event.key === "ArrowUp" ? -1 : 1;
          target = result.nodes.find(candidate => candidate.rank === item.rank && candidate.slot === item.slot + direction);
        } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
          const direction = event.key === "ArrowLeft" ? -1 : 1;
          target = result.nodes.filter(candidate => candidate.rank === item.rank + direction)
            .sort((a, b) => Math.abs(a.y - item.y) - Math.abs(b.y - item.y) || compare(a.id, b.id))[0];
        } else return;
        event.preventDefault();
        if (target) select(target, true);
      });
      buttons.set(item.id, button); canvas.append(button);
    });
    viewport.append(canvas);
    wrapper.append(viewport, details);
    showDetails(selected);
    container.replaceChildren(wrapper);
    if (restoreFocus) select(selected, true);
    return () => wrapper.remove();
  }

  return Object.freeze({ render, layout, limits: LIMITS });
});
