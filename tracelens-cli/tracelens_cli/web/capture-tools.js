/* Explicit offline comparison, conversion and lookup. No polling or live fallback. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensCaptureTools = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const categories = [["methods", "Methods"], ["dependencies", "Dependencies"], ["outcomes", "Outcomes"],
    ["exceptions", "Exceptions"], ["lines", "Lines"], ["nesting", "Observed nesting"]];
  const changes = [["added", "Newly observed"], ["noLongerObserved", "No longer observed"],
    ["countChanged", "Count changed"]];
  const messages = {
    scope_and_inputs_not_verified: "Scenario, inputs and recording scope have not been verified to match.",
    observations_do_not_prove_coverage: "Observed differences do not prove coverage or code deletion.",
    partial_capture_evidence: "One or both snapshots contain partial evidence.",
    runtime_build_labels_missing: "Runtime build labels are missing; a snapshot name is not a verified revision.",
    capture_configuration_differs: "Capture settings differ between these saved captures; differences in recording scope can affect the observations.",
    agent_build_differs: "Agent builds differ between these saved captures; instrumentation changes can affect the observations.",
    service_or_segment_counts_differ: "Service scope or segment counts differ between snapshots.",
    request_paths_or_methods_differ: "Request paths or HTTP methods differ between snapshots.",
    viewer_snapshot_partial: "The saved viewer snapshot was marked partial.",
    viewer_snapshot_missing_exports: "Some saved journey segments lack full exports.",
    viewer_snapshot_warnings_present: "The full saved snapshot contains additional evidence warnings.",
    viewer_event_scan_limit: "The saved-event scan budget was reached; some observations were not searched.",
    partial_saved_evidence: "Some searched evidence is partial.",
    viewer_store_unavailable: "The viewer snapshot store could not be read.",
    capture_store_unavailable: "The CLI capture store could not be read.",
    viewer_record_unavailable_or_unsupported: "A saved snapshot could not be projected; no live evidence was substituted.",
    capture_record_unavailable_or_unsupported: "A CLI capture could not be read.",
    absence_does_not_prove_skip: "An observation missing from one capture does not prove the call was deliberately skipped.",
    correspondence_is_not_execution_order: "Paired identities do not establish the same invocation order or a cross-run causal link.",
    source_and_runtime_build_not_verified: "Source text and runtime revisions have not been verified to match these captures.",
    request_identity_incomplete: "Some request methods or paths are missing; those requests could not be matched reliably.",
    snapshot_http_owner_unavailable_or_ambiguous: "Some saved HTTP calls have missing or ambiguous service ownership and were not attributed.",
    snapshot_http_call_identity_ambiguous: "Some saved HTTP call identities are ambiguous and were not compared.",
    mixed_method_identity_precision: "Some matching method names have descriptors in only part of the evidence. They remain separate; an overload is not guessed."
  };
  const number = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : 0;
  const text = value => typeof value === "string" ? value.slice(0, 1600) : "";
  const warning = value => messages[value] || text(value).replace(/_/g, " ");
  function el(doc, tag, className, content) {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = String(content);
    return node;
  }
  function summarizeComparison(value) {
    return categories.map(([key, label]) => {
      const row = value && value[key] || {}, totals = row.totals || {};
      return {key, label, added: number(totals.added), noLongerObserved: number(totals.noLongerObserved),
        countChanged: number(totals.countChanged), omitted: number(row.omitted)};
    });
  }
  function identity(row) {
    const code = row.className ? `${text(row.className)}${row.methodName ? "." + text(row.methodName) : ""}${text(row.methodDescriptor)}` : "";
    const parent = row.parentClass ? `${text(row.parentClass)}.${text(row.parentMethod)}${text(row.parentMethodDescriptor)} → ` : "";
    const details = [row.service, parent + code, row.line ? `line ${number(row.line)}` : "",
      row.exceptionType, row.boundaryKind, row.system, row.direction, row.destination,
      row.operation, row.method, row.path, row.status, row.httpStatus ? `HTTP ${number(row.httpStatus)}` : ""];
    return details.filter(value => typeof value === "string" && value).map(text).join(" · ");
  }
  function warnings(doc, container, values) {
    const rows = Array.isArray(values) ? values.slice(0, 64) : [];
    if (!rows.length) return;
    const list = el(doc, "ul", "tct-warnings");
    rows.forEach(value => list.append(el(doc, "li", "", warning(value))));
    container.append(list);
  }
  function renderRawComparison(container, value, nested) {
    const doc = container.ownerDocument;
    container.replaceChildren();
    if (!value || value.kind !== "capture-comparison") {
      container.append(el(doc, "p", "tct-error", "Comparison evidence is unavailable."));
      return;
    }
    const before = value.before || {}, after = value.after || {};
    if (!nested) {
      container.append(el(doc, "h3", "", `${text(before.label)} → ${text(after.label)}`),
        el(doc, "p", "tct-muted", `${number(before.segmentCount)} baseline segments → ${number(after.segmentCount)} candidate segments. Saved evidence only.`));
      warnings(doc, container, value.warnings);
    }
    if (value.limited) container.append(el(doc, "p", "tct-warning", "This comparison is bounded; some differences or warnings are omitted."));
    const summary = el(doc, "div", "tct-difference-summary");
    summary.setAttribute("aria-label", "Observed difference summary");
    const legend = el(doc, "p", "tct-legend", "Newly observed / No longer observed / Count changed");
    summary.append(legend);
    const summaryRows = summarizeComparison(value);
    const largestTotal = Math.max(1, ...summaryRows.map(row => row.added + row.noLongerObserved + row.countChanged));
    summaryRows.forEach(row => {
      const item = el(doc, "div", "tct-summary-row");
      item.append(el(doc, "span", "tct-summary-label", row.label));
      const bar = el(doc, "div", "tct-bar");
      bar.setAttribute("aria-hidden", "true");
      changes.forEach(([key]) => {
        const part = el(doc, "span", `tct-bar-${key}`);
        part.style.width = (row[key] / largestTotal * 100) + "%";
        bar.append(part);
      });
      item.append(bar, el(doc, "span", "tct-summary-counts",
        `${row.added} new · ${row.noLongerObserved} no longer · ${row.countChanged} count changes`));
      summary.append(item);
    });
    container.append(summary, el(doc, "p", "tct-muted",
      "“No longer observed” means absent from this retained sample, not deleted code. Line shifts, different inputs and recording gaps can change the result."));
    categories.forEach(([key, label]) => {
      const row = value[key] || {}, details = el(doc, "details", "tct-differences");
      const totals = row.totals || {}, total = changes.reduce((sum, [kind]) => sum + number(totals[kind]), 0);
      details.append(el(doc, "summary", "", `${label} · ${total} changed identities`));
      if (!total) details.append(el(doc, "p", "tct-muted", "No observed differences in this category."));
      changes.forEach(([kind, title]) => {
        const rows = Array.isArray(row[kind]) ? row[kind].slice(0, 200) : [];
        if (!rows.length) return;
        details.append(el(doc, "h4", "", `${title} (${number(totals[kind])})`));
        const list = el(doc, "ul", "tct-change-list");
        rows.forEach(change => {
          const item = el(doc, "li", `tct-change-${kind}`);
          item.append(el(doc, "span", "", identity(change)),
            el(doc, "span", "tct-count", `${number(change.beforeCount)} → ${number(change.afterCount)}`));
          list.append(item);
        });
        details.append(list);
      });
      if (number(row.omitted)) details.append(el(doc, "p", "tct-warning", `${number(row.omitted)} difference rows omitted by the response limit.`));
      container.append(details);
    });
    const timing = el(doc, "details", "tct-timing");
    timing.append(el(doc, "summary", "", "Local duration samples — not a benchmark"),
      el(doc, "p", "tct-muted", "Local durations include waits and instrumentation. A single before/after request does not establish a performance improvement or regression."));
    ["before", "after"].forEach(side => {
      const rows = value.durationSamples && value.durationSamples[side];
      timing.append(el(doc, "h4", "", side === "before" ? "Baseline" : "Candidate"));
      (Array.isArray(rows) ? rows : []).slice(0, 16).forEach(row => {
        timing.append(el(doc, "p", "", `${identity(row)} · ${typeof row.durationMs === "number" ? number(row.durationMs) + " ms" : "duration unavailable"}`));
      });
    });
    container.append(timing);
    const evidence = el(doc, "details", "tct-evidence-warnings");
    evidence.append(el(doc, "summary", "", "Evidence limits and snapshot warnings"));
    ["before", "after"].forEach(side => {
      const detail = value.evidenceWarnings && value.evidenceWarnings[side];
      if (!detail) return;
      evidence.append(el(doc, "h4", "", side === "before" ? "Baseline" : "Candidate"));
      warnings(doc, evidence, detail.warnings);
      (detail.segments || []).slice(0, 16).forEach(segment => {
        evidence.append(el(doc, "p", "tct-muted", text(segment.service) + " · " + text(segment.segmentId)));
        warnings(doc, evidence, segment.warnings);
        if (number(segment.warningsOmitted)) evidence.append(el(doc, "p", "", `${number(segment.warningsOmitted)} more warnings omitted.`));
      });
      if (number(detail.warningsOmitted)) evidence.append(el(doc, "p", "", `${number(detail.warningsOmitted)} more warnings omitted.`));
    });
    (value.limitations || []).slice(0, 20).forEach(value => evidence.append(el(doc, "p", "tct-muted", text(value))));
    container.append(evidence);
  }

  const dispositions = {before_only: "Before only", after_only: "After only", changed: "Changed", descendants_changed: "Nested behavior differs", unchanged: "Common context"};
  function behaviorRows(value) {
    const behavior = value && value.behavior;
    if (!behavior || behavior.schemaVersion !== 1) return null;
    return ["outcomes", "dependencies", "methods"].flatMap(category =>
      (Array.isArray(behavior[category]) ? behavior[category] : []).slice(0, 200)
        .filter(row => row && dispositions[row.disposition])
        .map(row => ({...row, category})));
  }
  function behaviorIdentity(row) {
    if (row.category === "methods") return `${text(row.className)}.${text(row.methodName)}${text(row.methodDescriptor) || "()"}`;
    if (row.category === "outcomes") return [row.method, row.path].map(text).filter(Boolean).join(" ") || "Request outcome";
    return [row.operation, text(row.destinationName).trim() || row.destination, row.path].map(text).filter(Boolean).join(" · ") ||
      [row.boundaryKind, row.system].map(text).filter(Boolean).join(" · ") || "External call";
  }
  function behaviorSearch(row) {
    return [row.service, behaviorIdentity(row), row.destination, row.boundaryKind, row.system, row.direction,
      row.evidenceKind, dispositions[row.disposition]].map(text).join(" ").toLowerCase();
  }
  function nestedMethodChanges(row) {
    return row.category === "methods" && Array.isArray(row.nestedChanges) ? row.nestedChanges.slice(0, 200)
      .filter(child => child && ["before_only", "after_only", "changed"].includes(child.disposition))
      .map(child => ({...child, category: "methods"})) : [];
  }
  function matchesBehavior(row, query) {
    return !query || query.split(/\s+/).every(word => behaviorSearch(row).includes(word));
  }
  function statusText(row) {
    return [number(row.httpStatus) ? `HTTP ${number(row.httpStatus)}` : "", text(row.status), text(row.errorType)].filter(Boolean).join(" · ") || "Outcome not recorded";
  }
  function behaviorSide(row, side) {
    const value = row[side];
    if (row.category === "outcomes") {
      const rows = Array.isArray(value) ? value.slice(0, 16) : [];
      return rows.length ? [...new Set(rows.map(statusText))].join("; ") : "Not observed in this capture";
    }
    const count = row.category === "methods" ? row[side + "Count"] : value && value.count;
    if (typeof count !== "number" || !Number.isFinite(count) || count < 0) return "Observation count unavailable";
    return count ? `${count} recorded ${row.category === "methods" ? (count === 1 ? "invocation" : "invocations") : (count === 1 ? "call" : "calls")}` : "Not observed in this capture";
  }
  function dependencyOutcome(row, side) {
    const value = row[side];
    if (value?.count === 0) return "Not observed in retained capture";
    const statuses = Array.isArray(value?.statuses) ? value.statuses.slice(0, 8) : [];
    return statuses.length ? [...new Set(statuses.map(statusText))].join("; ") : behaviorSide(row, side);
  }
  function findingPriority(row) {
    const statuses = [row.before, row.after].flatMap(side => Array.isArray(side) ? side : side?.statuses || []);
    return (statuses.some(status => number(status.httpStatus) >= 400 || status.status === "ERROR") ? 10 : 0)
      + (row.disposition === "before_only" || row.disposition === "after_only" ? 2 : 0);
  }
  function renderComparison(container, value, options) {
    options = options || {};
    const rows = behaviorRows(value);
    if (!value || value.kind !== "capture-comparison" || !rows) {
      renderRawComparison(container, value);
      return () => {};
    }
    const doc = container.ownerDocument, listeners = [], rowListeners = [], pairSearchListeners = [];
    let disposed = false, evidenceGeneration = 0, pairSearchGeneration = 0, focusedRow = null;
    const listen = (node, type, callback, collection = listeners) => {
      node.addEventListener(type, callback); collection.push(() => node.removeEventListener(type, callback));
    };
    const clearRows = () => { evidenceGeneration++; rowListeners.splice(0).forEach(remove => remove()); };
    const behavior = value.behavior, before = value.before || {}, after = value.after || {};
    const review = el(doc, "section", "tct-behavior");
    review.setAttribute("aria-label", "Before and after behavior review");
    const header = el(doc, "div", "tct-review-heading");
    header.append(el(doc, "h3", "", "What changed?"), el(doc, "span", "tct-saved-label", "Saved evidence only"));
    review.append(header, el(doc, "p", "tct-muted", "Compare recorded behavior first. Before and after are separate runs; matching rows do not imply the same call sequence."));
    const pair = el(doc, "div", "tct-pair-header");
    [["Before", before.label], ["After", after.label]].forEach(([title, label]) => {
      const cell = el(doc, "div", "tct-pair-label");
      cell.append(el(doc, "strong", "", title), el(doc, "span", "", text(label) || "Unnamed snapshot")); pair.append(cell);
    });
    review.append(pair);
    const findings = el(doc, "div", "tct-findings");
    const changedRows = rows.filter(row => row.disposition !== "unchanged");
    ["outcomes", "dependencies", "methods"].forEach(category => {
      const group = changedRows.filter(item => item.category === category);
      const row = [...group].sort((a, b) => findingPriority(b) - findingPriority(a))[0];
      if (!row) return;
      const card = el(doc, "article", "tct-finding");
      card.append(el(doc, "span", "tct-finding-label", category === "outcomes" ? "Request response" : category === "dependencies" ? "External call" : "Method observation"),
        el(doc, "strong", "", category === "outcomes" ? `${behaviorSide(row, "before")} → ${behaviorSide(row, "after")}` : category === "dependencies"
          ? `${dependencyOutcome(row, "before")} → ${dependencyOutcome(row, "after")}` : dispositions[row.disposition]),
        el(doc, "span", "tct-muted", `${text(row.service)} · ${behaviorIdentity(row)}`));
      const inspect = el(doc, "button", "tct-inspect-difference", "Inspect difference"); inspect.type = "button";
      inspect.setAttribute("aria-label", `Inspect difference: ${text(row.service)} ${behaviorIdentity(row)}`);
      listen(inspect, "click", () => {
        search.value = ""; clearPairSearch(); focusedRow = row; draw();
        const selected = body.querySelector(".tct-behavior-row");
        if (selected) { selected.focus(); if (typeof selected.scrollIntoView === "function") selected.scrollIntoView({block: "nearest"}); }
      });
      card.append(inspect);
      findings.append(card);
    });
    review.append(findings);
    const flowApi = (typeof globalThis !== "undefined" ? globalThis : {}).TraceLensCompareFlow;
    let disposeFlow = () => {};
    if (flowApi && typeof flowApi.render === "function") {
      const flow = el(doc, "details", "tct-flow"), flowBody = el(doc, "div", "tct-flow-body");
      flow.append(el(doc, "summary", "", "Flow view · services, calls and methods"), flowBody);
      let drawn = false;
      listen(flow, "toggle", () => {
        if (!flow.open || drawn || disposed) return;
        drawn = true;
        disposeFlow = flowApi.render(flowBody, rows, {limited: Boolean(behavior.limited || value.limited)}, {onSelect: row => {
          search.value = ""; clearPairSearch(); focusedRow = row; draw();
          const selected = body.querySelector(".tct-behavior-row");
          if (selected && typeof selected.scrollIntoView === "function") selected.scrollIntoView({block: "nearest"});
        }});
      });
      review.append(flow);
    }
    const quality = el(doc, "details", "tct-quality");
    const qualityWarnings = [...new Set([...(Array.isArray(value.warnings) ? value.warnings : []), ...(Array.isArray(behavior.warnings) ? behavior.warnings : [])])];
    const flags = [];
    if (qualityWarnings.includes("partial_capture_evidence")) flags.push("partial captures");
    if (qualityWarnings.includes("runtime_build_labels_missing") || qualityWarnings.includes("source_and_runtime_build_not_verified")) flags.push("revision unverified");
    if (qualityWarnings.includes("request_identity_incomplete")) flags.push("request matching limited");
    if (qualityWarnings.includes("capture_configuration_differs")) flags.push("capture settings differ");
    if (qualityWarnings.includes("agent_build_differs")) flags.push("agent builds differ");
    if (behavior.limited || value.limited) flags.push("bounded results");
    const comparability = el(doc, "div", "tct-comparability");
    comparability.setAttribute("aria-label", "Comparison confidence");
    comparability.append(el(doc, "strong", "", "Before interpreting changes"),
      el(doc, "span", "", [...flags, "input equivalence unverified"].join(" · ")));
    review.append(comparability);
    quality.append(el(doc, "summary", "", `Evidence quality & comparison limits${flags.length ? " · " + flags.join(" · ") : ""}`),
      el(doc, "p", "tct-muted", "Not observed means absent from the retained sample—not deliberately skipped, deleted code, or proof that the fix is correct. Verify equivalent inputs and a normal-path regression case separately."));
    warnings(doc, quality, qualityWarnings);
    if (behavior.limited || value.limited) quality.append(el(doc, "p", "tct-warning", "Bounded comparison: additional evidence or differences may be omitted."));
    ["outcomes", "dependencies", "methods"].forEach(category => {
      if (number(behavior.omitted && behavior.omitted[category])) quality.append(el(doc, "p", "tct-warning",
        `${number(behavior.omitted[category])} ${category} groups omitted from the behavior review.`));
    });
    review.append(quality);
    const searchForm = el(doc, "form", "tct-pair-search-form");
    const searchLabel = el(doc, "label", "tct-field tct-pair-search-field");
    const search = el(doc, "input", "tct-pair-search"); search.type = "search"; search.maxLength = 128;
    search.placeholder = "e.g. UsageEntitlementClient fetchEntitlements or an EMS host";
    searchLabel.append(el(doc, "span", "", "Find in these two captures"), search);
    const searchFiles = el(doc, "button", "tct-pair-search-files", "Search both saved files"); searchFiles.type = "submit";
    searchFiles.disabled = typeof options.api !== "function";
    searchForm.append(searchLabel, searchFiles);
    const searchStatus = el(doc, "p", "tct-pair-search-status tct-muted"); searchStatus.setAttribute("role", "status");
    const pairSearchStatus = el(doc, "p", "tct-pair-file-status tct-muted"); pairSearchStatus.setAttribute("role", "status");
    const pairResults = el(doc, "div", "tct-pair-file-results");
    const navigationStatus = el(doc, "p", "tct-evidence-status"); navigationStatus.setAttribute("role", "status");
    const focusBar = el(doc, "div", "tct-focus-bar"), focusLabel = el(doc, "span", "tct-focus-label");
    const clearFocus = el(doc, "button", "tct-clear-focus", "Show all changes"); clearFocus.type = "button";
    listen(clearFocus, "click", () => { focusedRow = null; draw(); });
    focusBar.append(focusLabel, clearFocus);
    const body = el(doc, "div", "tct-behavior-rows");
    review.append(searchForm, searchStatus, pairSearchStatus, pairResults, focusBar, body, navigationStatus);
    const maximum = {outcomes: 4, dependencies: 8, methods: 8};
    function evidenceRefs(row, side) {
      const expected = options[side + "SnapshotId"] || (value.sourceSnapshots && value.sourceSnapshots[side] || {}).snapshotId;
      const source = row.category === "dependencies" ? row[side] && row[side].evidence : row[side];
      return (Array.isArray(source) ? source : []).slice(0, 16).flatMap(ref => {
        if (!ref || !expected || ref.snapshotId !== expected || ref.service !== row.service ||
          !["snapshotId", "service", "segmentId", "serviceInstanceId"].every(key => typeof ref[key] === "string" && ref[key].length > 0 && ref[key].length <= 256)) return [];
        const selected = {snapshotId: ref.snapshotId, service: ref.service, segmentId: ref.segmentId, serviceInstanceId: ref.serviceInstanceId};
        if (row.category === "methods") {
          if (ref.className !== row.className || ref.methodName !== row.methodName || !text(ref.className) || !text(ref.methodName)) return [];
          if ((ref.methodDescriptor || null) !== (row.methodDescriptor || null)) return [];
          selected.className = text(ref.className); selected.methodName = text(ref.methodName);
          if (ref.methodDescriptor != null) {
            if (typeof ref.methodDescriptor !== "string" || !ref.methodDescriptor.length || ref.methodDescriptor.length > 2048) return [];
            selected.methodDescriptor = ref.methodDescriptor;
          }
        }
        return [selected];
      });
    }
    function sideCell(row, side) {
      const cell = el(doc, "div", `tct-evidence-cell tct-evidence-${side}`);
      cell.append(el(doc, "span", "tct-side-label", side === "before" ? "Before" : "After"), el(doc, "strong", "", behaviorSide(row, side)));
      if (row.category === "dependencies" && row[side] && Array.isArray(row[side].statuses)) {
        row[side].statuses.slice(0, 8).forEach(status => cell.append(el(doc, "span", "tct-muted", `${statusText(status)} · ${number(status.count)} recorded`)));
        const omitted = Math.max(0, row[side].statuses.length - 8) + number(row[side].statusesOmitted);
        if (omitted) cell.append(el(doc, "span", "tct-warning", `${omitted} additional status groups not shown.`));
      }
      if (row.category === "outcomes" && Array.isArray(row[side]) && row[side].length > 16) cell.append(el(doc, "span", "tct-warning", `${row[side].length - 16} additional segment outcomes not shown.`));
      const refs = evidenceRefs(row, side);
      const originalRefs = row.category === "dependencies" ? row[side] && row[side].evidence : row[side];
      if (Array.isArray(originalRefs) && originalRefs.length > 16) cell.append(el(doc, "span", "tct-warning", `${originalRefs.length - 16} additional segment references not shown.`));
      if (refs.length && typeof options.onOpenEvidence === "function") {
        refs.forEach((ref, index) => {
          const button = el(doc, "button", "tct-open-evidence", `${row.category === "methods" ? "Inspect method evidence" : "Inspect service evidence"}${refs.length > 1 ? ` · segment ${index + 1}` : ""}`);
          button.type = "button";
          button.setAttribute("aria-label", `${button.textContent}, ${side}, ${text(row.service)}, ${behaviorIdentity(row)}`);
          listen(button, "click", async () => {
            if (disposed || button.disabled) return;
            const generation = ++evidenceGeneration;
            const isCurrent = () => !disposed && generation === evidenceGeneration;
            button.disabled = true; navigationStatus.textContent = "Opening saved evidence…";
            try {
              await options.onOpenEvidence(ref, {isCurrent, context: {side, label: behaviorIdentity(row), focus: button,
                scroll: doc.defaultView?.scrollY || 0}});
              if (isCurrent()) navigationStatus.textContent = "Saved evidence opened.";
            } catch (error) {
              if (isCurrent()) navigationStatus.textContent = text(error && error.message) || "The exact saved evidence could not be opened. No other service was substituted.";
            } finally { if (!disposed) button.disabled = false; }
          }, rowListeners);
          cell.append(button);
        });
      }
      return cell;
    }
    function pairRow(row, nested) {
      const item = el(doc, "article", `tct-behavior-row tct-disposition-${row.disposition}`);
      item.tabIndex = -1;
      const title = el(doc, "div", "tct-row-heading");
      title.append(el(doc, "span", "tct-disposition", dispositions[row.disposition]), el(doc, "strong", "", behaviorIdentity(row)),
        el(doc, "span", "tct-row-service", row.category === "dependencies" && text(row.service) ? `From ${text(row.service)}` : text(row.service)));
      if (row.category === "dependencies" && text(row.destinationName).trim() && text(row.destinationName).trim() !== text(row.destination)) {
        const configured = row.destinationNameSource === "configured";
        const address = el(doc, "span", "tct-row-destination", `${text(row.destination)}${configured ? " · configured label" : ""}`);
        address.title = configured ? "Service name from the current viewer configuration for this recorded address. It does not establish that downstream execution was captured." : "Recorded destination address";
        title.append(address);
      }
      item.append(title);
      const cells = el(doc, "div", "tct-evidence-pair"); cells.append(sideCell(row, "before"), sideCell(row, "after")); item.append(cells);
      if (!nested && row.category === "methods") {
        const children = nestedMethodChanges(row), originalCount = Array.isArray(row.nestedChanges) ? row.nestedChanges.length : 0;
        const omitted = Math.max(number(row.nestedOmitted), number(row.nestedChangedCount) - originalCount) + Math.max(0, originalCount - children.length);
        const total = Math.max(number(row.nestedChangedCount), originalCount + number(row.nestedOmitted));
        if (total) {
          const details = el(doc, "details", "tct-nested-methods");
          const query = search.value.trim().slice(0, 128).toLowerCase();
          const matchingChildren = query ? children.filter(child => matchesBehavior(child, query)) : [];
          const selected = matchingChildren.length ? matchingChildren : children;
          details.open = matchingChildren.length > 0;
          details.append(el(doc, "summary", "", `${total} nested method ${total === 1 ? "difference" : "differences"}${matchingChildren.length ? ` · ${matchingChildren.length} match search` : ""}`));
          const contents = el(doc, "div", "tct-nested-method-content"); details.append(contents);
          let rendered = false;
          function expandNested() {
            if (!details.open || rendered) return;
            rendered = true;
            contents.append(el(doc, "p", "tct-muted", "Grouped by recorded method nesting, not source-code edits or a shared execution order."));
            const list = el(doc, "div", "tct-nested-method-list"); contents.append(list);
            let shown = 0;
            const more = el(doc, "button", "tct-more-nested-methods"); more.type = "button";
            function appendRows(amount) {
              const end = Math.min(selected.length, shown + amount);
              selected.slice(shown, end).forEach(child => list.append(pairRow(child, true))); shown = end;
              more.hidden = shown >= selected.length;
              more.textContent = `Show more nested differences (${selected.length - shown} remaining)`;
            }
            appendRows(8);
            if (selected.length > 8) { listen(more, "click", () => appendRows(20), rowListeners); contents.append(more); }
            if (omitted) contents.append(el(doc, "p", "tct-warning", `${omitted} nested method differences omitted from this review. Search both saved files to look for additional retained evidence.`));
          }
          listen(details, "toggle", expandNested, rowListeners); expandNested(); item.append(details);
        }
      }
      return item;
    }
    function draw() {
      clearRows(); body.replaceChildren();
      const query = search.value.trim().slice(0, 128).toLowerCase();
      focusBar.hidden = !focusedRow;
      if (typeof disposeFlow.select === "function") disposeFlow.select(focusedRow);
      focusLabel.textContent = focusedRow ? `Focused difference: ${text(focusedRow.service)} · ${behaviorIdentity(focusedRow)}` : "";
      const matching = rows.filter(row => (!focusedRow || row === focusedRow) && (matchesBehavior(row, query) || nestedMethodChanges(row).some(child => matchesBehavior(child, query))));
      const changes = matching.filter(row => row.disposition !== "unchanged"), common = matching.filter(row => row.disposition === "unchanged");
      searchStatus.textContent = query ? `${matching.length} matching groups in this returned comparison. Literal search; no other captures or request payloads are searched.` :
        "Changes first · Search covers returned method, call and request identities from this pair only—not source text or request payloads.";
      [["outcomes", "Request outcomes"], ["dependencies", "External calls"], ["methods", "Changed methods"]].forEach(([category, title]) => {
        const selected = changes.filter(row => row.category === category);
        if (!selected.length) return;
        const section = el(doc, "section", `tct-behavior-${category}`);
        section.append(el(doc, "h4", "", `${title} · ${selected.length}`));
        if (category === "methods" && selected.some(row => number(row.nestedChangedCount))) section.append(el(doc, "p", "tct-muted", "Showing outermost observed parents. Expand nested method differences to inspect their children; a parent's own invocation count may be unchanged."));
        selected.slice(0, maximum[category]).forEach(row => section.append(pairRow(row)));
        if (selected.length > maximum[category]) {
          const more = el(doc, "button", "tct-more-behavior", `Show more ${title.toLowerCase()} (${selected.length - maximum[category]} remaining)`); more.type = "button";
          listen(more, "click", () => { maximum[category] += 20; draw(); }, rowListeners); section.append(more);
        }
        body.append(section);
      });
      if (!changes.length && !focusedRow) body.append(el(doc, "p", "tct-muted", query ? "No changed groups match this search. Common recorded context may appear below." : "No behavior differences in the returned evidence. This does not establish identical behavior or full coverage."));
      if (common.length) {
        const details = el(doc, "details", "tct-common-context"); details.open = Boolean(query) || Boolean(focusedRow);
        details.append(el(doc, "summary", "", `Common recorded context · ${common.length} groups`), el(doc, "p", "tct-muted", "Matching request/call identities and recorded counts or outcomes. This does not establish identical inputs, timing, source, or execution order."));
        common.slice(0, 40).forEach(row => details.append(pairRow(row)));
        if (common.length > 40) details.append(el(doc, "p", "tct-muted", `${common.length - 40} more common groups. Narrow the pair search to inspect them.`));
        body.append(details);
      }
      if (!matching.length) body.append(el(doc, "p", "tct-warning", "No matching identities in the returned comparison. Bounded or missing evidence is not proof of absence."));
    }
    function clearPairSearch() {
      pairSearchGeneration++; pairSearchListeners.splice(0).forEach(remove => remove()); pairResults.replaceChildren(); pairSearchStatus.textContent = "";
      body.hidden = false; searchStatus.hidden = false;
      searchFiles.disabled = typeof options.api !== "function";
    }
    listen(search, "input", () => { focusedRow = null; clearPairSearch(); maximum.outcomes = 4; maximum.dependencies = 8; maximum.methods = 8; draw(); });
    listen(searchForm, "submit", async event => {
      event.preventDefault();
      const query = search.value.trim().slice(0, 128);
      if (!query || searchFiles.disabled) return;
      const snapshotIds = ["before", "after"].map(side => options[side + "SnapshotId"] || (value.sourceSnapshots && value.sourceSnapshots[side] || {}).snapshotId);
      if (snapshotIds.some(id => typeof id !== "string" || !id) || snapshotIds[0] === snapshotIds[1]) {
        pairSearchStatus.textContent = "The selected snapshot identities are unavailable. No other captures were searched."; return;
      }
      focusedRow = null; focusBar.hidden = true; clearPairSearch(); const generation = ++pairSearchGeneration; searchFiles.disabled = true;
      pairSearchStatus.textContent = "Searching only these two saved files…";
      try {
        const result = await options.api("/api/capture-tools/search", "POST", {query, category: "all", limit: 50, snapshotIds});
        if (disposed || generation !== pairSearchGeneration) return;
        if (!result || result.scope !== "selected-snapshots" || !Array.isArray(result.snapshotIds) || result.snapshotIds.length !== 2 || result.snapshotIds.some((id, i) => id !== snapshotIds[i])) {
          throw new Error("This viewer did not confirm pair-scoped search. Results were not shown; update the viewer to search only these two saved files.");
        }
        body.hidden = true; searchStatus.hidden = true;
        pairSearchStatus.textContent = `${number(result.returned)} returned observations · ${number(result.recordsScanned)} of 2 selected saved files searched.${result.limited ? " Bounded or incomplete search; missing results are not proof of absence." : " Retained evidence only; missing results do not prove code was skipped."}`;
        warnings(doc, pairResults, result.warnings);
        const columns = el(doc, "div", "tct-evidence-pair");
        snapshotIds.forEach((id, index) => {
          const column = el(doc, "section", "tct-pair-search-column"); column.append(el(doc, "h4", "", index ? "After — search results" : "Before — search results"));
          const matches = (Array.isArray(result.results) ? result.results : []).slice(0, 100).filter(row => row && row.storage === "viewer" && row.snapshotId === id);
          if (!matches.length) column.append(el(doc, "p", "tct-muted", "No matching observation returned for this capture. Check the search limits before interpreting absence."));
          matches.forEach(row => {
            const item = el(doc, "article", "tct-result");
            const display = {...row, path: text(row.path).split(/[?#]/)[0], destination: text(row.destination).split(/[?#]/)[0]};
            item.append(el(doc, "h3", "", identity(display)), el(doc, "p", "tct-muted", `${text(row.service)} · ${text(row.category)} · ${number(row.count)} recorded observations`));
            const requestPath = text(row.requestPath || row.request && row.request.path).split(/[?#]/)[0];
            if (requestPath) item.append(el(doc, "p", "tct-muted", `Request: ${[text(row.request && row.request.method), requestPath].filter(Boolean).join(" ")}`));
            const ref = {snapshotId: id, service: row.service, segmentId: row.segmentId, serviceInstanceId: row.serviceInstanceId};
            if (row.category === "method") {
              ref.className = text(row.className); ref.methodName = text(row.methodName);
              if (typeof row.methodDescriptor === "string" && row.methodDescriptor.length <= 2048) ref.methodDescriptor = row.methodDescriptor;
            }
            const valid = ["snapshotId", "service", "segmentId", "serviceInstanceId"].every(key => typeof ref[key] === "string" && ref[key].length > 0 && ref[key].length <= 256) &&
              (row.category !== "method" || Boolean(ref.className && ref.methodName) &&
                (row.methodDescriptor == null || typeof row.methodDescriptor === "string" && row.methodDescriptor.length > 0 && row.methodDescriptor.length <= 2048));
            if (valid && typeof options.onOpenEvidence === "function") {
              const button = el(doc, "button", "tct-open-pair-result", row.category === "method" ? "Inspect method evidence" : "Inspect service evidence"); button.type = "button";
              listen(button, "click", async () => {
                if (disposed || button.disabled) return;
                const navigation = ++evidenceGeneration;
                const isCurrent = () => !disposed && generation === pairSearchGeneration && navigation === evidenceGeneration;
                button.disabled = true;
                try { await options.onOpenEvidence(ref, {isCurrent, context: {side: index ? "after" : "before", label: identity(display), focus: button,
                  scroll: doc.defaultView?.scrollY || 0}}); }
                catch (error) { if (isCurrent()) pairSearchStatus.textContent = text(error && error.message) || "Exact saved evidence could not be opened."; }
                finally { if (!disposed) button.disabled = false; }
              }, pairSearchListeners);
              item.append(button);
            }
            column.append(item);
          });
          columns.append(column);
        });
        pairResults.append(columns);
      } catch (error) {
        if (!disposed && generation === pairSearchGeneration) pairSearchStatus.textContent = text(error && error.message) || "Pair search failed. No global search was substituted.";
      } finally { if (!disposed && generation === pairSearchGeneration) searchFiles.disabled = false; }
    });
    const advanced = el(doc, "details", "tct-all-differences");
    advanced.append(el(doc, "summary", "", "All differences · counts, lines & raw identities"));
    const raw = el(doc, "div", "tct-raw-comparison"); renderRawComparison(raw, value, true); advanced.append(raw);
    container.replaceChildren(review, advanced); draw();
    return () => { disposed = true; pairSearchGeneration++; disposeFlow(); clearRows(); pairSearchListeners.splice(0).forEach(remove => remove()); listeners.splice(0).forEach(remove => remove()); };
  }

  // options.api(path, method, body) is the host's bounded same-origin JSON API.
  // No request is made to a live trace, even when a saved file is missing.
  function render(container, options) {
    options = options || {};
    const doc = container.ownerDocument, listeners = [], searchListeners = [];
    let disposed = false, listGeneration = 0, compareGeneration = 0, searchGeneration = 0, comparisonCleanup = null;
    let snapshots = [], currentTab = 0, compareBusy = false, projectBusy = false, pairGeneration = 0;
    const listen = (node, type, callback, collection = listeners) => {
      node.addEventListener(type, callback);
      collection.push(() => node.removeEventListener(type, callback));
    };
    const call = (path, method, body) => {
      if (typeof options.api !== "function") return Promise.reject(new Error("Saved-evidence API is unavailable."));
      return Promise.resolve().then(() => options.api(path, method, body));
    };
    const root = el(doc, "section", "tct-tools");
    const heading = el(doc, "h2", "", "Saved evidence tools");
    const status = el(doc, "p", "tct-status");
    status.setAttribute("role", "status");
    root.append(heading, el(doc, "p", "tct-muted",
      "Compare two viewer snapshots, find saved observations, or explicitly create a minimized copy for CLI/MCP. No service connection is needed."), status);
    const nav = el(doc, "div", "tct-tabs");
    nav.setAttribute("role", "tablist"); nav.setAttribute("aria-label", "Saved evidence tools");
    const panels = [el(doc, "section", "tct-compare-panel"), el(doc, "section", "tct-search-panel")];
    const tabs = ["Compare snapshots", "Search saved evidence"].map((label, index) => {
      const button = el(doc, "button", "tct-tab", label); button.type = "button";
      button.setAttribute("role", "tab"); button.id = `tct-tab-${index}`;
      panels[index].id = `tct-panel-${index}`;
      button.setAttribute("aria-controls", panels[index].id);
      panels[index].setAttribute("role", "tabpanel");
      panels[index].setAttribute("aria-labelledby", button.id);
      listen(button, "click", () => selectTab(index));
      listen(button, "keydown", event => {
        if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        selectTab(event.key === "Home" ? 0 : event.key === "End" ? 1 : 1 - currentTab);
        tabs[currentTab].focus();
      });
      nav.append(button);
      return button;
    });
    function selectTab(index) {
      currentTab = index;
      tabs.forEach((tab, i) => { tab.setAttribute("aria-selected", String(i === index)); tab.tabIndex = i === index ? 0 : -1; panels[i].hidden = i !== index; });
    }
    root.append(nav, ...panels);
    const selectors = el(doc, "div", "tct-selectors");
    function field(parent, title, tag, className) {
      const label = el(doc, "label", "tct-field");
      const input = el(doc, tag, className);
      label.append(el(doc, "span", "", title), input); parent.append(label);
      return input;
    }
    const baseline = field(selectors, "Baseline snapshot", "select", "tct-baseline");
    const candidate = field(selectors, "Candidate snapshot", "select", "tct-candidate");
    const compare = el(doc, "button", "tct-compare", "Compare saved snapshots"); compare.type = "button";
    const refreshButton = el(doc, "button", "tct-refresh", "Refresh saved list"); refreshButton.type = "button";
    selectors.append(compare, refreshButton);
    const compareStatus = el(doc, "p", "tct-compare-status"); compareStatus.setAttribute("role", "status");
    const comparison = el(doc, "div", "tct-comparison");
    const pairMetadata = el(doc, "div", "tct-selection-metadata");
    function describePair() {
      pairMetadata.replaceChildren();
      [["Before", baseline], ["After", candidate]].forEach(([side, select]) => {
        const saved = snapshots.find(row => row.id === select.value);
        if (!saved) return;
        const card = el(doc, "div", "tct-selection-card");
        const request = saved.request || {};
        card.append(el(doc, "strong", "", side), el(doc, "span", "", text(saved.name)),
          el(doc, "span", "tct-muted", [text(request.method), text(request.path)].filter(Boolean).join(" ") || "Request identity unavailable in list"),
          el(doc, "span", "tct-muted", Number.isFinite(saved.capturedAt) ? new Date(saved.capturedAt).toLocaleString() : "Capture time unavailable"),
          el(doc, "span", "tct-muted", `${saved.partial ? "Partial capture" : "Completeness checked when compared"} · revision not verified by name`));
        pairMetadata.append(card);
      });
    }
    function clearComparison() { if (comparisonCleanup) comparisonCleanup(); comparisonCleanup = null; comparison.replaceChildren(); }
    panels[0].append(selectors, pairMetadata, compareStatus, comparison);
    const exportBox = el(doc, "details", "tct-export");
    exportBox.append(el(doc, "summary", "", "Use with CLI/MCP"),
      el(doc, "p", "tct-muted", "Create a separate minimized capture from the selected candidate. Source, user/context fields, headers, payloads and raw exception messages are excluded. Paths, destinations, code identifiers and labels can still be sensitive. The full viewer snapshot is unchanged."));
    const exportFields = el(doc, "div", "tct-export-fields");
    const label = field(exportFields, "Capture label (optional)", "input", "tct-export-label"); label.maxLength = 80;
    const revision = field(exportFields, "Revision label (optional, not verified)", "input", "tct-export-revision"); revision.maxLength = 128;
    const project = el(doc, "button", "tct-project", "Create CLI/MCP capture"); project.type = "button";
    const projectStatus = el(doc, "p", "tct-project-status"); projectStatus.setAttribute("role", "status");
    exportFields.append(project); exportBox.append(exportFields, projectStatus);
    panels[0].append(exportBox);
    function updateButtons() {
      compare.disabled = compareBusy || !baseline.value || !candidate.value || baseline.value === candidate.value;
      project.disabled = projectBusy || !candidate.value;
    }
    [baseline, candidate].forEach(node => listen(node, "change", () => {
      pairGeneration++;
      compareGeneration++; compareBusy = false; clearComparison(); compareStatus.textContent = "";
      updateButtons(); describePair();
    }));
    listen(compare, "click", async () => {
      if (compare.disabled) return;
      const generation = ++compareGeneration;
      compareBusy = true; updateButtons(); clearComparison(); compareStatus.textContent = "Comparing saved observations…";
      try {
        const result = await call("/api/capture-tools/compare", "POST", {before: baseline.value, after: candidate.value});
        if (disposed || generation !== compareGeneration) return;
        comparisonCleanup = renderComparison(comparison, result, {api: options.api, onOpenEvidence: options.onOpenEvidence,
          beforeSnapshotId: baseline.value, afterSnapshotId: candidate.value}); compareStatus.textContent = "Comparison uses only the selected saved files.";
      } catch (error) {
        if (!disposed && generation === compareGeneration) compareStatus.textContent = text(error.message) || "Comparison failed. No live data was substituted.";
      } finally {
        if (!disposed && generation === compareGeneration) { compareBusy = false; updateButtons(); }
      }
    });
    listen(project, "click", async () => {
      if (project.disabled) return;
      const snapshotId = candidate.value;
      const body = {snapshotId};
      if (label.value.trim()) body.label = label.value.trim();
      if (revision.value.trim()) body.revision = revision.value.trim();
      projectBusy = true; updateButtons(); projectStatus.textContent = "Creating a minimized saved copy…";
      try {
        const result = await call("/api/capture-tools/project", "POST", body);
        if (disposed) return;
        const id = result.capture && result.capture.captureId;
        if (!result.saved || !id) throw new Error("Capture creation was not confirmed.");
        projectStatus.textContent = `Created CLI/MCP capture ${text(id)}. Use tracelens capture list or MCP list_request_captures with the same configured capture directory. The viewer snapshot is unchanged.`;
      } catch (error) {
        if (!disposed) projectStatus.textContent = text(error.message) || "Capture creation failed.";
      } finally {
        if (!disposed) { projectBusy = false; updateButtons(); }
      }
    });
    const form = el(doc, "form", "tct-search-form");
    const query = field(form, "Class, method, exception type or destination", "input", "tct-query");
    query.type = "search"; query.maxLength = 128; query.required = true;
    const category = field(form, "Evidence category", "select", "tct-search-category");
    [["all", "All evidence"], ["class", "Class"], ["method", "Method"], ["exception", "Exception"], ["destination", "Destination"]].forEach(([value, title]) => {
      const option = el(doc, "option", "", title); option.value = value; category.append(option);
    });
    category.value = "all";
    const search = el(doc, "button", "tct-search", "Search saved files"); search.type = "submit"; form.append(search);
    const searchStatus = el(doc, "p", "tct-search-status"); searchStatus.setAttribute("role", "status");
    const results = el(doc, "div", "tct-search-results");
    panels[1].append(el(doc, "p", "tct-muted",
      "An on-demand literal search of explicit viewer snapshots and CLI captures. No source text is returned. Saved evidence is not coverage; a missing match does not prove that code is unused."),
    form, searchStatus, results);
    listen(form, "submit", async event => {
      event.preventDefault();
      if (!query.value.trim() || search.disabled) return;
      const generation = ++searchGeneration; search.disabled = true;
      searchListeners.splice(0).forEach(remove => remove()); results.replaceChildren();
      searchStatus.textContent = "Searching saved evidence…";
      try {
        const result = await call("/api/capture-tools/search", "POST", {query: query.value.trim(), category: category.value, limit: 50});
        if (disposed || generation !== searchGeneration) return;
        searchStatus.textContent = `${number(result.returned)} results · ${number(result.recordsScanned)} saved files searched.${result.limited ? " Bounded or incomplete results; additional evidence may be omitted." : ""}`;
        warnings(doc, results, result.warnings);
        const rows = Array.isArray(result.results) ? result.results.slice(0, 100) : [];
        if (!rows.length) results.append(el(doc, "p", "tct-muted", "No matching observations in the searched saved evidence. This is not proof of absence."));
        rows.forEach(row => {
          const item = el(doc, "article", "tct-result");
          item.append(el(doc, "h3", "", identity(row)),
            el(doc, "p", "tct-muted", `${row.storage === "viewer" ? "Viewer snapshot" : "CLI capture"} · ${text(row.label)} · ${text(row.category)} · ${number(row.count)} observations${row.partial ? " · partial evidence" : ""}`));
          if (row.request) item.append(el(doc, "p", "tct-muted",
            `${identity(row.request)}${typeof row.request.durationMs === "number" ? " · " + number(row.request.durationMs) + " ms local sample (not a benchmark)" : ""}`));
          if (row.storage === "viewer" && row.snapshotId && typeof options.onOpenSnapshot === "function") {
            const open = el(doc, "button", "tct-open-snapshot", "Open saved snapshot"); open.type = "button";
            listen(open, "click", () => options.onOpenSnapshot(row.snapshotId), searchListeners); item.append(open);
          } else if (row.captureId) item.append(el(doc, "code", "tct-capture-id", text(row.captureId)));
          results.append(item);
        });
      } catch (error) {
        if (!disposed && generation === searchGeneration) searchStatus.textContent = text(error.message) || "Saved evidence search failed.";
      } finally {
        if (!disposed && generation === searchGeneration) search.disabled = false;
      }
    });
    async function refresh() {
      if (disposed) return;
      const generation = ++listGeneration; refreshButton.disabled = true; status.textContent = "Loading saved-file list…";
      try {
        const result = await call("/api/capture-tools", "GET");
        if (disposed || generation !== listGeneration) return;
        snapshots = (Array.isArray(result.snapshots) ? result.snapshots : []).slice(0, 20);
        let selectionChanged = false;
        [baseline, candidate].forEach((select, index) => {
          const previous = select.value;
          select.replaceChildren();
          const prompt = el(doc, "option", "", "Choose a saved snapshot"); prompt.value = ""; select.append(prompt);
          snapshots.forEach(row => { const option = el(doc, "option", "", text(row.name)); option.value = row.id; select.append(option); });
          select.value = snapshots.some(row => row.id === previous) ? previous : (snapshots[index === 0 ? 1 : 0] || {}).id || "";
          selectionChanged ||= select.value !== previous;
        });
        if (selectionChanged) {
          compareGeneration++; compareBusy = false; clearComparison(); compareStatus.textContent = "";
        }
        status.textContent = snapshots.length ? `${snapshots.length} viewer snapshots · ${(Array.isArray(result.captures) ? result.captures : []).length} CLI captures. Lists are read-only.` :
          "No saved viewer snapshots yet. Save a request snapshot first; CLI captures can still be searched.";
        updateButtons(); describePair();
      } catch (error) {
        if (!disposed && generation === listGeneration) status.textContent = text(error.message) || "Saved-file list could not be read.";
      } finally {
        if (!disposed && generation === listGeneration) refreshButton.disabled = false;
      }
    }
    listen(refreshButton, "click", refresh);
    container.replaceChildren(root); selectTab(0); updateButtons(); refresh();
    function cleanup() {
      disposed = true; listGeneration++; compareGeneration++; searchGeneration++;
      if (comparisonCleanup) comparisonCleanup(); comparisonCleanup = null;
      listeners.splice(0).forEach(remove => remove()); searchListeners.splice(0).forEach(remove => remove());
    }
    cleanup.refresh = refresh;
    cleanup.selectPair = async ids => {
      if (disposed || !Array.isArray(ids) || ids.length !== 2 || ids[0] === ids[1]) return;
      const generation = ++pairGeneration;
      await refresh();
      if (disposed || generation !== pairGeneration || ids.some(id => !snapshots.some(row => row.id === id))) return;
      baseline.value = ids[0]; candidate.value = ids[1]; selectTab(0);
      compareGeneration++; compareBusy = false; clearComparison(); describePair(); updateButtons();
      compareStatus.textContent = "Selection order: Before, then After. Review the pair and choose Compare saved snapshots.";
      compare.focus();
    };
    return cleanup;
  }
  return {render, renderComparison, summarizeComparison};
}));
