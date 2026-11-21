/* Flow view of one saved-capture comparison. Built only from the returned behavior rows:
   it groups by calling service and never infers call order, causality or downstream execution.
   Class prefix is cfv- because tcf- belongs to the Code flow view on the same page. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensCompareFlow = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MAX_SERVICES = 16, MAX_CALLS = 12, MAX_METHODS = 8, MAX_NESTED = 8, MAX_ACCESSORS = 20;
  const labels = {before_only: "Before only", after_only: "After only", changed: "Changed",
    descendants_changed: "Nested behavior differs", unchanged: "Unchanged",
    inner_changed: "Calls or methods differ", unknown: "Request outcome not returned"};
  const rowStates = ["before_only", "after_only", "changed", "descendants_changed", "unchanged"];
  const ownChange = ["before_only", "after_only", "changed"];
  const text = value => typeof value === "string" ? value.slice(0, 400) : "";
  const count = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : 0;
  const differs = row => row.disposition !== "unchanged";
  // Only plain get*/set* are folded. is*/has* often carry a decision, so they stay visible.
  const accessor = row => /^(get|set)[A-Z0-9_]/.test(text(row.methodName));
  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

  function statusText(status) {
    return [count(status && status.httpStatus) ? `HTTP ${status.httpStatus}` : "", text(status && status.status),
      text(status && status.errorType)].filter(Boolean).join(" · ");
  }
  function outcomeSide(list) {
    const rows = Array.isArray(list) ? list.slice(0, 16) : [];
    if (!rows.length) return "Not observed";
    const values = [...new Set(rows.map(statusText).filter(Boolean))];
    return values.length ? values.slice(0, 2).join("; ") + (values.length > 2 ? "; …" : "") : "Outcome not recorded";
  }
  function callTitle(row) {
    const destination = text(row.destinationName).trim() || text(row.destination);
    // Cache and database calls have no recorded destination; name the system instead.
    return [destination ? "" : text(row.system) || text(row.boundaryKind), text(row.operation), destination].filter(Boolean).join(" ") || "External call";
  }
  function methodTitle(row) {
    const name = text(row.className);
    return `${name.slice(name.lastIndexOf(".") + 1)}.${text(row.methodName)}`;
  }
  // Nested children carry no category; tag them so the host can render them as method rows.
  function nestedOf(row) {
    return (Array.isArray(row.nestedChanges) ? row.nestedChanges : [])
      .filter(child => child && ownChange.includes(child.disposition))
      .map(child => ({...child, category: "methods", service: text(child.service) || row.service}));
  }
  function serviceState(service) {
    const outcomes = service.outcomes.map(row => row.disposition);
    const inner = [...service.calls, ...service.methods].some(differs);
    if (!outcomes.length) return inner ? "inner_changed" : "unknown";
    if (outcomes.every(value => value === "before_only")) return "before_only";
    if (outcomes.every(value => value === "after_only")) return "after_only";
    if (outcomes.some(value => value !== "unchanged")) return "changed";
    return inner ? "inner_changed" : "unchanged";
  }
  function summarize(services) {
    const summary = {outcomes: [], calls: {before_only: 0, after_only: 0, changed: 0}, methods: 0, outermostMethods: 0};
    services.forEach(service => {
      service.outcomes.filter(differs).forEach(row => summary.outcomes.push(row));
      service.calls.forEach(row => { if (row.disposition in summary.calls) summary.calls[row.disposition]++; });
      service.methods.forEach(row => {
        if (differs(row)) summary.outermostMethods++;
        const nested = Array.isArray(row.nestedChanges) ? row.nestedChanges.filter(child => child && ownChange.includes(child.disposition)).length : 0;
        summary.methods += (ownChange.includes(row.disposition) ? 1 : 0) + Math.max(count(row.nestedChangedCount), nested);
      });
    });
    return summary;
  }

  // rows: the validated behavior rows of one comparison, each tagged with its category.
  function buildModel(rows) {
    const services = new Map();
    let unattributed = 0;
    (Array.isArray(rows) ? rows : []).forEach(row => {
      if (!row || !rowStates.includes(row.disposition)) return;
      const name = text(row.service);
      const key = {outcomes: "outcomes", dependencies: "calls", methods: "methods"}[row.category];
      if (!key) return;
      if (!name) { unattributed++; return; }
      if (!services.has(name)) services.set(name, {name, outcomes: [], calls: [], methods: []});
      services.get(name)[key].push(row);
    });
    const all = [...services.values()];
    all.forEach(service => {
      service.state = serviceState(service);
      service.compact = service.state === "unchanged" || service.state === "unknown";
      service.primary = service.outcomes.find(differs) || service.outcomes[0] || null;
      service.changedCalls = service.calls.filter(differs);
      service.commonCalls = service.calls.length - service.changedCalls.length;
      service.changedMethods = service.methods.filter(differs);
      // A configured display name that equals another compared service is the only link drawn.
      service.links = new Map();
      service.changedCalls.forEach(row => {
        const target = text(row.destinationName).trim();
        if (row.destinationNameSource === "configured" && target !== service.name && services.has(target)) service.links.set(row, target);
      });
    });
    // Services that differ come first; services with nothing to show sink to one-line rows.
    const ordered = [...all.filter(service => !service.compact), ...all.filter(service => service.compact)];
    return {services: ordered.slice(0, MAX_SERVICES), servicesOmitted: Math.max(0, all.length - MAX_SERVICES),
      unattributed, summary: summarize(all)};
  }

  // options.onSelect(row, listRow): listRow is the returned row that owns a nested method.
  // options.panel: an element the host fills with the selected row; placed beside the graph.
  function render(container, rows, meta, options) {
    meta = meta || {}; options = options || {};
    const doc = container.ownerDocument, listeners = [], buttons = [], model = buildModel(rows);
    let selected = null;
    const el = (tag, className, content) => {
      const node = doc.createElement(tag);
      if (className) node.className = className;
      if (content !== undefined) node.textContent = String(content);
      return node;
    };
    const press = item => {
      if (selected) selected.setAttribute("aria-pressed", "false");
      selected = item;
      if (item) item.setAttribute("aria-pressed", "true");
    };
    function selectable(item, row, listRow, kind) {
      item.type = "button"; item.setAttribute("aria-pressed", "false");
      buttons.push([row, item, kind]);
      const click = () => {
        press(item);
        if (typeof options.onSelect === "function") options.onSelect(row, listRow || row);
      };
      item.addEventListener("click", click); listeners.push(() => item.removeEventListener("click", click));
      return item;
    }
    function node(kind, state, title, lines, row) {
      const item = el(row ? "button" : "div", `cfv-node cfv-${kind} cfv-state-${state}`);
      item.append(el("strong", "cfv-title", title));
      lines.filter(Boolean).forEach(line => item.append(el("span", "cfv-sub", line)));
      item.append(el("span", "cfv-state", labels[state]));
      return row ? selectable(item, row, null, kind) : item;
    }
    function methodRow(row, listRow) {
      const item = el("button", `cfv-mrow cfv-state-${row.disposition}`);
      item.append(el("span", "cfv-mstate", labels[row.disposition]), el("span", "cfv-mname", methodTitle(row)),
        el("span", "cfv-mcount", `before ${count(row.beforeCount)} · after ${count(row.afterCount)}`));
      item.title = `${text(row.className)}.${text(row.methodName)}`;
      return selectable(item, row, listRow, "method");
    }
    function methodItem(row) {
      const item = el("li", "cfv-method");
      item.append(methodRow(row, row));
      const nested = nestedOf(row), shown = nested.filter(child => !accessor(child)), folded = nested.filter(accessor);
      if (shown.length) {
        const list = el("ul", "cfv-tree");
        shown.slice(0, MAX_NESTED).forEach(child => { const li = el("li", "cfv-method"); li.append(methodRow(child, row)); list.append(li); });
        item.append(list);
      }
      if (folded.length) {
        const details = el("details", "cfv-accessors"), list = el("ul", "cfv-tree");
        details.append(el("summary", "", plural(folded.length, "plain getter or setter", "plain getters and setters")));
        folded.slice(0, MAX_ACCESSORS).forEach(child => { const li = el("li", "cfv-method"); li.append(methodRow(child, row)); list.append(li); });
        details.append(list); item.append(details);
      }
      const hidden = Math.max(0, shown.length - MAX_NESTED) + Math.max(0, folded.length - MAX_ACCESSORS) + count(row.nestedOmitted);
      if (hidden) item.append(el("p", "tct-muted", `${hidden} more nested differences. Search the list below to find them.`));
      return item;
    }
    function headline() {
      const box = el("div", "cfv-headline"), summary = model.summary, parts = [];
      const outcomes = summary.outcomes.slice(0, 2).map(row =>
        `${text(row.service)} response ${outcomeSide(row.before)} → ${outcomeSide(row.after)}`);
      if (summary.outcomes.length > 2) outcomes.push(`${summary.outcomes.length - 2} more responses differ`);
      if (summary.calls.before_only) parts.push(`${plural(summary.calls.before_only, "call", "calls")} no longer observed`);
      if (summary.calls.after_only) parts.push(`${plural(summary.calls.after_only, "call", "calls")} newly observed`);
      if (summary.calls.changed) parts.push(`${plural(summary.calls.changed, "call", "calls")} changed`);
      // Lead with the outermost count so it matches the list's "Changed methods" heading.
      if (summary.outermostMethods) parts.push(plural(summary.outermostMethods, "method differs", "methods differ") +
        (summary.methods > summary.outermostMethods ? ` (${summary.methods} including nested)` : ""));
      if (!outcomes.length && !parts.length) {
        box.append(el("strong", "", "No differences in the returned evidence."));
        return box;
      }
      box.append(el("strong", "", outcomes.length ? outcomes.join(" · ") : "Responses unchanged"));
      if (parts.length) box.append(el("span", "", parts.join(" · ")));
      if (meta.limited) box.append(el("span", "tct-warning", "Bounded comparison, so the real counts may be higher."));
      return box;
    }

    const view = el("section", "cfv");
    view.setAttribute("aria-label", "Flow view of the comparison");
    view.append(headline());
    const legend = el("div", "cfv-legend");
    ["unchanged", "changed", "before_only", "after_only"].forEach(state => legend.append(el("span", `cfv-key cfv-state-${state}`, labels[state])));
    view.append(legend, el("p", "tct-muted", "Grouped by calling service. Saved comparisons do not record call order, so calls are grouped by state, not by time. Not observed means absent from the retained sample."));
    if (model.servicesOmitted) view.append(el("p", "tct-warning", `${model.servicesOmitted} more services are not drawn. Use the list below.`));
    if (model.unattributed) view.append(el("p", "tct-warning", `${model.unattributed} groups have no recorded service and are not drawn.`));
    const layout = el("div", "cfv-layout"), graph = el("div", "cfv-graph");
    if (options.panel) { options.panel.className = `${options.panel.className} cfv-panel`.trim(); layout.append(options.panel); }
    layout.append(graph);
    view.append(layout);
    if (!model.services.length) graph.append(el("p", "tct-muted", "No service evidence was returned for a flow view."));

    model.services.forEach(service => {
      const primary = service.primary;
      const identity = primary ? [text(primary.method), text(primary.path)].filter(Boolean).join(" ") : "";
      if (service.compact) {
        const item = el(primary ? "button" : "div", `cfv-srow cfv-state-${service.state}`);
        const details = [identity, primary ? `${outcomeSide(primary.before)} in both` : "",
          service.calls.length ? `${plural(service.calls.length, "call", "calls")} unchanged` : ""].filter(Boolean).join(" · ");
        item.append(el("strong", "", service.name), el("span", "cfv-sub", details), el("span", "cfv-state", labels[service.state]));
        graph.append(primary ? selectable(item, primary, null, "service") : item);
        return;
      }
      const block = el("div", "cfv-block");
      block.append(node("service", service.state, service.name, [
        identity + (service.outcomes.length > 1 ? ` · +${service.outcomes.length - 1} more` : ""),
        primary ? `${outcomeSide(primary.before)} → ${outcomeSide(primary.after)}` : ""], primary));
      const calls = el("div", "cfv-calls");
      service.changedCalls.slice(0, MAX_CALLS).forEach(row => {
        const line = el("div", "cfv-call-row");
        line.append(node("call", row.disposition, callTitle(row), [text(row.path) || text(row.boundaryKind),
          `before ${count(row.before && row.before.count)} · after ${count(row.after && row.after.count)}`], row));
        const target = service.links.get(row);
        if (target) {
          const linked = model.services.find(item => item.name === target);
          const reference = node("ref", linked ? linked.state : "unknown", `${target} request`, ["Matched by configured name"], linked && linked.primary);
          reference.title = "The destination's configured display name equals this compared service. It does not prove this call reached that request.";
          line.append(reference);
        }
        calls.append(line);
      });
      if (service.changedCalls.length > MAX_CALLS) calls.append(el("p", "tct-muted", `${service.changedCalls.length - MAX_CALLS} more differing calls. Use the list below.`));
      if (service.commonCalls) {
        const line = el("div", "cfv-call-row");
        line.append(node("call", "unchanged", plural(service.commonCalls, "unchanged call", "unchanged calls"), ["Same identity, count and outcome in both captures"], null));
        calls.append(line);
      }
      if (!service.calls.length) calls.append(el("p", "tct-muted", "No external calls returned for this service."));
      block.append(calls);
      if (service.changedMethods.length) {
        const methods = el("details", "cfv-methods"), list = el("ul", "cfv-tree");
        methods.open = true;
        methods.append(el("summary", "", `Methods in ${service.name} · ${service.changedMethods.length} outermost ${service.changedMethods.length === 1 ? "difference" : "differences"}`),
          el("p", "tct-muted", "Unchanged methods are not listed, and methods are not linked to the calls they made."));
        service.changedMethods.slice(0, MAX_METHODS).forEach(row => list.append(methodItem(row)));
        methods.append(list);
        if (service.changedMethods.length > MAX_METHODS) methods.append(el("p", "tct-muted", `${service.changedMethods.length - MAX_METHODS} more differing methods. Use the list below.`));
        block.append(methods);
      }
      graph.append(block);
    });
    container.replaceChildren(view);
    const dispose = () => { listeners.splice(0).forEach(remove => remove()); };
    // Highlights a row's node without reporting a selection; a service node wins over a reference chip.
    dispose.select = row => {
      const current = buttons.find(entry => entry[1] === selected);
      if (current && current[0] === row) return;
      const match = row && (buttons.find(entry => entry[0] === row && entry[2] !== "ref") || buttons.find(entry => entry[0] === row));
      press(match ? match[1] : null);
    };
    return dispose;
  }

  return {buildModel, render};
}));
