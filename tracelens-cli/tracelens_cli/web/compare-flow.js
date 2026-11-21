/* Flow view of one saved-capture comparison. Built only from the returned behavior rows:
   it groups by calling service and never infers call order, causality or downstream execution. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensCompareFlow = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MAX_SERVICES = 16, MAX_CALLS = 12, MAX_METHODS = 8, MAX_NESTED = 8;
  const labels = {before_only: "Before only", after_only: "After only", changed: "Changed",
    descendants_changed: "Nested behavior differs", unchanged: "Unchanged",
    inner_changed: "Calls or methods differ", unknown: "Request outcome not returned"};
  const rowStates = ["before_only", "after_only", "changed", "descendants_changed", "unchanged"];
  const text = value => typeof value === "string" ? value.slice(0, 400) : "";
  const count = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : 0;
  const differs = row => row.disposition !== "unchanged";

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
  function serviceState(service) {
    const outcomes = service.outcomes.map(row => row.disposition);
    const inner = [...service.calls, ...service.methods].some(differs);
    if (!outcomes.length) return inner ? "inner_changed" : "unknown";
    if (outcomes.every(value => value === "before_only")) return "before_only";
    if (outcomes.every(value => value === "after_only")) return "after_only";
    if (outcomes.some(value => value !== "unchanged")) return "changed";
    return inner ? "inner_changed" : "unchanged";
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
    return {services: all.slice(0, MAX_SERVICES), servicesOmitted: Math.max(0, all.length - MAX_SERVICES), unattributed};
  }

  function render(container, rows, meta, options) {
    meta = meta || {}; options = options || {};
    const doc = container.ownerDocument, listeners = [], buttons = [], model = buildModel(rows);
    let selected = null;
    const press = item => {
      if (selected) selected.setAttribute("aria-pressed", "false");
      selected = item;
      if (item) item.setAttribute("aria-pressed", "true");
    };
    const el = (tag, className, content) => {
      const node = doc.createElement(tag);
      if (className) node.className = className;
      if (content !== undefined) node.textContent = String(content);
      return node;
    };
    function node(kind, state, title, lines, row) {
      const item = el(row ? "button" : "div", `tcf-node tcf-${kind} tcf-state-${state}`);
      item.append(el("strong", "tcf-title", title));
      lines.filter(Boolean).forEach(line => item.append(el("span", "tcf-sub", line)));
      item.append(el("span", "tcf-state", labels[state]));
      if (!row) return item;
      item.type = "button"; item.setAttribute("aria-pressed", "false");
      buttons.push([row, item, kind]);
      const click = () => {
        press(item);
        if (typeof options.onSelect === "function") options.onSelect(row);
      };
      item.addEventListener("click", click); listeners.push(() => item.removeEventListener("click", click));
      return item;
    }
    function methodItem(row, parent) {
      const item = el("li", "tcf-method");
      const button = node("method-node", row.disposition, methodTitle(row),
        [`before ${count(row.beforeCount)} · after ${count(row.afterCount)}`], parent || row);
      button.title = `${text(row.className)}.${text(row.methodName)}`;
      item.append(button);
      if (parent) return item;
      const nested = (Array.isArray(row.nestedChanges) ? row.nestedChanges : [])
        .filter(child => child && ["before_only", "after_only", "changed"].includes(child.disposition));
      if (nested.length) {
        const list = el("ul", "tcf-tree");
        nested.slice(0, MAX_NESTED).forEach(child => list.append(methodItem(child, row)));
        item.append(list);
      }
      const hidden = Math.max(0, nested.length - MAX_NESTED) + count(row.nestedOmitted);
      if (hidden) item.append(el("p", "tct-muted", `${hidden} more nested differences. Select this method to see them in the list.`));
      return item;
    }

    const view = el("section", "tcf");
    view.setAttribute("aria-label", "Flow view of the comparison");
    view.append(el("p", "tct-muted", "Grouped by calling service. Saved comparisons do not record call order, so calls are grouped by state, not by time. Before and after are separate runs; not observed means absent from the retained sample."));
    const legend = el("div", "tcf-legend");
    ["unchanged", "changed", "before_only", "after_only"].forEach(state => legend.append(el("span", `tcf-key tcf-state-${state}`, labels[state])));
    view.append(legend);
    if (meta.limited) view.append(el("p", "tct-warning", "Bounded comparison: some services, calls or methods may be missing from this view."));
    if (model.servicesOmitted) view.append(el("p", "tct-warning", `${model.servicesOmitted} more services are not drawn. Use the list below.`));
    if (model.unattributed) view.append(el("p", "tct-warning", `${model.unattributed} groups have no recorded service and are not drawn.`));
    if (!model.services.length) view.append(el("p", "tct-muted", "No service evidence was returned for a flow view."));

    model.services.forEach(service => {
      const block = el("div", "tcf-block"), primary = service.primary;
      const identity = primary ? [text(primary.method), text(primary.path)].filter(Boolean).join(" ") : "";
      block.append(node("service", service.state, service.name, [
        identity + (service.outcomes.length > 1 ? ` · +${service.outcomes.length - 1} more` : ""),
        primary ? `${outcomeSide(primary.before)} → ${outcomeSide(primary.after)}` : ""], primary));
      const calls = el("div", "tcf-calls");
      service.changedCalls.slice(0, MAX_CALLS).forEach(row => {
        const line = el("div", "tcf-call-row");
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
        const line = el("div", "tcf-call-row");
        line.append(node("call", "unchanged", `${service.commonCalls} unchanged ${service.commonCalls === 1 ? "call" : "calls"}`, ["Same identity, count and outcome in both captures"], null));
        calls.append(line);
      }
      if (!service.calls.length) calls.append(el("p", "tct-muted", "No external calls returned for this service."));
      block.append(calls);
      if (service.changedMethods.length) {
        const methods = el("details", "tcf-methods"), list = el("ul", "tcf-tree");
        methods.append(el("summary", "", `Methods in ${service.name} · ${service.changedMethods.length} outermost ${service.changedMethods.length === 1 ? "difference" : "differences"}`),
          el("p", "tct-muted", "Outermost recorded parents with their nested differences. Unchanged methods are not listed, and methods are not linked to the calls they made."));
        service.changedMethods.slice(0, MAX_METHODS).forEach(row => list.append(methodItem(row)));
        methods.append(list);
        if (service.changedMethods.length > MAX_METHODS) methods.append(el("p", "tct-muted", `${service.changedMethods.length - MAX_METHODS} more differing methods. Use the list below.`));
        block.append(methods);
      }
      view.append(block);
    });
    container.replaceChildren(view);
    const dispose = () => { listeners.splice(0).forEach(remove => remove()); };
    // Keeps the highlight in step with a focus set or cleared outside this view.
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
