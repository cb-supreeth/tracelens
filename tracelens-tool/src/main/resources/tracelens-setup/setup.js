/* Local setup only: requests create a review plan before any explicit install. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else { root.TraceLensSetup = api; api.start(root.document, root.fetch.bind(root)); }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const SERVICES = Object.freeze([
    {key: "cbApp", id: "cb-app", label: "cb-app", alias: "cb-app"},
    {key: "ems", id: "ems", label: "EMS", alias: "cb-ems", launcher: true},
    {key: "plans", id: "plans", label: "Plans", alias: "cb-plans", launcher: true},
    {key: "ledger", id: "ledger", label: "Ledger", alias: "cb-consumption-ledger"},
    {key: "go", id: "go", label: "Grant Orchestrator", alias: "cb-grant-orchestrator"},
  ]);
  const LEVELS = {overview: "Overview — services, dependencies, timing and errors", methods: "Methods — overview plus method entry / exit", detailed: "Detailed — methods plus executed lines"};
  const CAPTURE_GROUPS = [
    {title: "Request & database evidence", help: "These opt-ins are independent of capture level. They can retain sensitive values; no response bodies or database result rows are captured.", fields: [
      ["captureHttpQueryParameters", "check", "Request query parameters", "Capture incoming query-string fields, not request headers."],
      ["captureHttpRequestBodies", "check", "Request bodies", "Capture supported bodies observed by the application; not every body format is available."],
      ["redactHttpPayloads", "check", "Redact sensitive request fields", "Best-effort query/form/JSON redaction. Does not redact SQL or guarantee removal of every secret."],
      ["maxHttpPayloadLength", "number", "Request evidence length limit", "Maximum inspected / retained bytes or characters per field.", 1, 16384],
      ["captureJdbcQueries", "check", "JDBC SQL queries", "Record observed SQL templates and statement text."],
      ["captureJdbcParameters", "check", "JDBC parameter bindings", "Requires JDBC queries. Bound values may be sensitive; previews are display-only."],
      ["captureClickHouseQueries", "check", "Native ClickHouse queries", "Independent of JDBC: observe supported native ClickHouse clients."],
      ["captureClickHouseParameters", "check", "Native ClickHouse bindings", "Requires native ClickHouse queries; available values may be sensitive."],
      ["maxSqlLength", "number", "SQL text length limit", "Characters retained per query.", 1, 16384],
      ["maxSqlQueriesPerRequest", "number", "Query limit per service segment", "Bound retained SQL evidence during a busy request.", 1, 1000],
    ]},
    {title: "Boundaries, errors & background work", help: "Boundary events describe supported client activity without payloads. They do not enable query/body capture.", fields: [
      ["httpClientCaptureEnabled", "check", "Outgoing HTTP boundaries", "Observe supported outgoing clients, even without cross-service propagation."],
      ["distributedTracingEnabled", "check", "Cross-service HTTP correlation", "Allow trace context propagation only to the exact destinations below."],
      ["httpPropagationAllowedDestinations", "list", "Allowed propagation destinations", "Exact application host:port entries, not agent ports or URLs. No wildcards. Configure the receiving service too."],
      ["captureDataStoreBoundaries", "check", "Cache / storage boundaries", "Observe supported cache, database and object-storage calls without payloads."],
      ["captureMessagingBoundaries", "check", "Messaging boundaries", "Observe supported messaging operations without message bodies."],
      ["trackExceptions", "check", "Escaping exceptions", "Exceptions leaving instrumented methods, not every thrown or caught exception."],
      ["instrumentExecutors", "check", "Executor context", "Carry trace context through supported thread-pool hand-offs."],
      ["instrumentScheduled", "check", "Scheduled work", "Create traces for supported scheduled tasks; API paths do not admit or exclude this work."],
      ["chargebeeJobTypes", "list", "Chargebee job types", "Exact type names, one per line. Empty disables job capture; no wildcards. Independent of scheduled tasks."],
    ]},
    {title: "Scoped detail override & expiry", help: "API paths to capture decide which incoming requests are retained. With Overview and nonempty scope, matching paths use Detailed (methods + lines). With Methods or Detailed, scope narrows that level to matching paths. Outside scope or after expiry, capture falls back to overview. Evidence opt-ins stay independent.", fields: [
      ["detailedCapturePaths", "list", "Scoped API path prefixes", "Empty uses the default level for all admitted paths. A nonempty scope overrides the default: Overview becomes Detailed for matches; Methods or Detailed applies only to matches. Other paths use Overview. No URLs or wildcards."],
      ["detailedCaptureUntilEpochMillis", "expiry", "Method / line expiry", "Local date and time. Empty means no expiry. Restart to apply a changed deadline."],
    ]},
    {title: "Advanced: packages, budgets & retention", help: "Budgets bound retained data, not total JVM heap. History evicted or never recorded cannot be recovered later.", fields: [
      ["trackMethods", "check", "Record method entry / exit", "Enables method events in Methods and Detailed. Overview still omits them; this switch does not enable line events."],
      ["packages", "list", "Application package prefixes", "Method / line instrumentation scope. Empty provides metadata-only capture."],
      ["excludedPackages", "list", "Excluded package prefixes", "Narrow exclusions, for example generated models. Do not include secrets."],
      ["maxTrackedBytes", "number", "Total retained-data budget (bytes)", "Must cover the per-segment budget; at most 2 GiB.", 1048576, 2147483648],
      ["maxSegmentBytes", "number", "Per-segment budget (bytes)", "Bounds one service segment's retained detail (1–256 MiB).", 1048576, 268435456],
      ["maxTrackedEvents", "number", "Retained event ceiling", "Global event count; independent of the estimated byte budget.", 1000],
      ["maxTrackedRequests", "number", "Retained segment ceiling", "Old segments are evicted by last recorded activity, not by reading them.", 1, 2147483647],
      ["idleTtlMinutes", "number", "Idle retention (minutes)", "Remove traces with no newly recorded activity for this long.", 1],
      ["httpRetentionPercent", "number", "HTTP retention reservation (%)", "Reserve a share for HTTP segments; 0 uses a shared budget.", 0, 99],
    ]},
  ];
  const CAPTURE_FIELDS = [["captureMode", "level"], ...CAPTURE_GROUPS.flatMap(group => group.fields)];
  const string = value => typeof value === "string" ? value : "";
  const prefixes = value => Array.from(new Set((Array.isArray(value) ? value : string(value).split(/[\n,]/))
    .filter(item => typeof item === "string").map(item => item.trim()).filter(Boolean)));
  const port = (value, label) => {
    const text = String(value).trim();
    if (!/^\d{1,5}$/.test(text) || Number(text) < 1 || Number(text) > 65535) throw Error(label + " must be a whole number from 1 to 65535.");
    return Number(text);
  };
  function capturePolicy(service, now = Date.now()) {
    const capture = service.capture || {}, paths = prefixes(service.httpIncludePaths), scope = prefixes(capture.detailedCapturePaths);
    const compact = values => values.slice(0, 3).map(value => value.length > 80 ? value.slice(0, 77) + "…" : value).join(", ")
      + (values.length > 3 ? " (+" + (values.length - 3) + " more)" : "");
    const mode = capture.captureMode, expiry = Number(capture.detailedCaptureUntilEpochMillis ?? 0), packages = prefixes(capture.packages);
    const detail = mode !== "overview" || scope.length > 0;
    let depth = "Overview — no method/line history";
    if (!Object.hasOwn(LEVELS, mode)) depth = "Unknown capture level; review settings";
    else if (!Number.isFinite(expiry) || expiry < 0) depth = "Invalid detail expiry; review settings";
    else if (expiry > 0 && now > expiry) depth = "Overview now — detail expired (browser clock)";
    else if (detail && !packages.length) depth = "No application method/line history — package scope is empty";
    else if (detail) {
      depth = mode === "methods" ? capture.trackMethods ? "Methods" : "No method/line history — method events are off"
        : capture.trackMethods ? "Methods + lines" : "Executed lines only — method events are off";
      depth += scope.length ? " on " + compact(scope) + "; Overview elsewhere" : " for admitted work";
    }
    if (expiry > 0 && Number.isFinite(new Date(expiry).getTime())) depth += "; deadline " + new Date(expiry).toLocaleString();
    const query = (enabled, bindings) => enabled ? bindings ? "SQL + bindings" : "SQL only" : "off";
    return [paths.length ? "Incoming APIs: " + compact(paths) + " (exact paths + descendants)." : "Incoming HTTP capture disabled — no API paths. Jobs and messaging are independent.",
      "Default level (without scope): " + (Object.hasOwn(LEVELS, mode) ? mode[0].toUpperCase() + mode.slice(1) : "Unknown") + ". "
        + (scope.length ? "Scoped override: " + (mode === "overview" ? "Detailed" : mode === "methods" ? "Methods" : mode === "detailed" ? "Detailed" : "Unknown") + " for matching paths; Overview outside scope. " : "No scoped override. ")
        + "Detail: " + depth + ".",
      "Packages: " + (packages.length ? compact(packages) : "none") + (prefixes(capture.excludedPackages).length ? "; exclusions also apply" : "") + ".",
      "Extra evidence: query parameters " + (capture.captureHttpQueryParameters ? "on" : "off") + " · request bodies " + (capture.captureHttpRequestBodies ? "on" : "off")
        + " · JDBC " + query(capture.captureJdbcQueries, capture.captureJdbcParameters) + " · ClickHouse " + query(capture.captureClickHouseQueries, capture.captureClickHouseParameters) + "."
        + (capture.captureHttpQueryParameters || capture.captureHttpRequestBodies ? " Request redaction: " + (capture.redactHttpPayloads ? "best-effort" : "off") + "." : "")];
  }
  function viewerHandoff(config, outcomes) {
    let viewerPort;
    try { viewerPort = port(config.viewerPort, "Viewer port"); } catch (_) { return ""; }
    const root = "http://127.0.0.1:" + viewerPort + "/";
    const selected = SERVICES.filter(({key}) => config[key]?.enabled && (!Array.isArray(outcomes)
      || outcomes.some(service => service.key === key && ["ready", "installed", "updated", "existing"].includes(service.status))));
    if (!selected.length) return root;
    return root + "#investigate=1" + (selected.length === 1 ? "&service=" + encodeURIComponent(selected[0].alias) : "");
  }
  function requestFrom(input) {
    const installDir = string(input.installDir).trim();
    if (!installDir) throw Error("Choose a local installation directory.");
    const request = {installDir, viewerPort: port(input.viewerPort, "Viewer port")};
    if ("captureJdbcQueries" in input || "captureJdbcParameters" in input || "captureMode" in input) {
      request.captureJdbcQueries = input.captureJdbcQueries === true;
      request.captureJdbcParameters = request.captureJdbcQueries && input.captureJdbcParameters === true;
      request.captureMode = input.captureMode ?? "detailed";
      if (!Object.hasOwn(LEVELS, request.captureMode)) throw Error("Choose Overview, Methods or Detailed capture.");
    }
    for (const {key, launcher} of SERVICES) {
      const service = input[key] || {}, enabled = service.enabled === true, sourceRoot = string(service.sourceRoot).trim();
      if (!enabled) { request[key] = {enabled: false}; continue; }
      const paths = prefixes(service.httpIncludePaths);
      // The server validates each selected service independently. Keep invalid
      // service input for its own outcome instead of blocking other additions.
      const rawPort = service.debugPort == null ? "" : String(service.debugPort).trim();
      const debugPort = /^\d{1,5}$/.test(rawPort) && Number(rawPort) >= 1 && Number(rawPort) <= 65535 ? Number(rawPort) : rawPort;
      request[key] = {enabled, sourceRoot, debugPort, httpIncludePaths: paths};
      if (service.capture && typeof service.capture === "object") {
        const capture = {};
        for (const [field, type] of CAPTURE_FIELDS) {
          if (!Object.hasOwn(service.capture, field)) continue;
          const value = service.capture[field];
          capture[field] = type === "check" ? value === true : type === "list" ? prefixes(value)
            : type === "number" || type === "expiry" ? /^\d+$/.test(String(value).trim()) && Number.isSafeInteger(Number(value)) ? Number(value) : String(value) : value;
        }
        if (capture.captureJdbcQueries === false) capture.captureJdbcParameters = false;
        if (capture.captureClickHouseQueries === false) capture.captureClickHouseParameters = false;
        request[key].capture = capture;
      }
      if (launcher) {
        Object.assign(request[key], {launchJar: string(service.launchJar).trim(), javaHome: string(service.javaHome).trim()});
      }
    }
    const selected = SERVICES.map(({key}) => request[key]).filter(service => service.enabled);
    if (!selected.length) throw Error("Select at least one service before reviewing changes.");
    return request;
  }
  function errorMessage(response, body) {
    const detail = body && typeof body.error === "string" ? body.error : "The local setup server could not complete this request.";
    if (response.status === 409) return detail + " Review the file plan again; files or settings may have changed.";
    if (response.status === 401 || response.status === 403) return detail + " Reopen the setup URL from the terminal to refresh this local session.";
    if (response.status >= 500) return detail + " Check the setup terminal and local directory permissions before trying again.";
    return detail;
  }
  function start(document, fetchRequest) {
    const find = id => document.getElementById(id), token = document.querySelector('meta[name="tracelens-setup-token"]')?.content;
    let step = "landing", flow = "setup", phase = "services", editKey = null, addingService = false, defaultsSnapshot = null,
      busy = false, plan = null, reviewedRequest = null, revision = 0, savedConfig = null, loadedConfig = null,
      lifecyclePlan = null, lifecycleRequest = null, lifecyclePolling = false, lifecycleStatusRevision = 0, connectionRevision = 0;
    const captureControls = new Map(), capturePanels = new Map(), serviceBasics = new Map(), captureDisclosures = [],
      restartChoices = new Map(), loadedExpiries = new Map(), loadErrors = new Map(), connections = new Map();
    const form = find("setup-form"), configuration = find("configuration"), status = find("status"), error = find("error");
    const put = (parent, tag, text, cls) => { const element = document.createElement(tag); element.textContent = text; if (cls) element.className = cls; parent.append(element); return element; };
    const clear = element => element.replaceChildren();
    const message = text => { status.textContent = text; };
    const showError = text => { error.textContent = text; error.hidden = false; error.focus(); };
    const clearError = () => { error.textContent = ""; error.hidden = true; };
    const addField = (parent, id, type, label, help, min, max) => {
      if (type === "number" && min !== undefined) help += " Allowed: " + min + (max === undefined ? " or more." : "–" + max + ".");
      const row = put(parent, "label", "", type === "check" ? "check-row" : "field"); row.setAttribute("for", id);
      const control = document.createElement(type === "list" ? "textarea" : ["level", "launcher"].includes(type) ? "select" : "input");
      control.id = id;
      if (type === "check") control.type = "checkbox";
      else if (type === "expiry") { control.type = "datetime-local"; control.step = "0.001"; }
      else if (type === "number") { control.type = "text"; control.inputMode = "numeric"; }
      else if (type === "list") { control.rows = 2; control.spellcheck = false; }
      else if (type === "level") for (const [value, title] of Object.entries(LEVELS)) { const option = put(control, "option", title); option.value = value; }
      else if (type === "launcher") for (const [value, title] of [["", "Use registered / generated launcher"], ["tomcat", "Register Tomcat launcher"], ["jar", "Register executable JAR launcher"]]) { const option = put(control, "option", title); option.value = value; }
      else { control.type = "text"; control.autocomplete = "off"; control.spellcheck = false; }
      if (type === "check") { row.append(control); const text = put(row, "span", ""); put(text, "strong", label); if (help) put(text, "small", help); }
      else { put(row, "span", label); row.append(control); if (help) { const hint = put(row, "small", help); hint.id = id + "-help"; control.setAttribute("aria-describedby", hint.id); } }
      return control;
    };
    for (const {key, id, label} of SERVICES) {
      const admission = find(id + "-paths").parentElement;
      serviceBasics.set(key, Array.from(find(id + "-fields").children).filter(element => element !== admission));
      const container = put(find(id + "-fields"), "div", "", "capture-settings");
      capturePanels.set(key, container);
      put(container, "h3", "Capture for " + label);
      const controls = new Map(); captureControls.set(key, controls);
      controls.set("captureMode", addField(container, id + "-capture-captureMode", "level", "Default capture level (without a scoped override)",
        "Overview omits method/line history. Methods adds call entry/exit without lines. Detailed adds executed lines and costs the most. A saved path scope can override this choice; check the effective policy below. Evidence and boundary switches stay independent."));
      const policy = put(container, "div", "", "capture-policy-summary"); policy.id = id + "-capture-policy";
      const notice = put(container, "p", "", "restart-warning"); notice.id = id + "-capture-notice";
      const admissionDisclosure = put(container, "details", "", "advanced capture-disclosure");
      put(admissionDisclosure, "summary", "Edit API paths to capture");
      const admissionFields = put(admissionDisclosure, "div", "", "advanced-fields"); admissionFields.append(admission);
      captureDisclosures.push(admissionDisclosure);
      for (const group of CAPTURE_GROUPS) {
        const disclosure = put(container, "details", "", "advanced capture-disclosure"); put(disclosure, "summary", group.title);
        captureDisclosures.push(disclosure);
        const fields = put(disclosure, "div", "", "advanced-fields"); put(fields, "p", group.help, "muted");
        for (const [field, type, title, help, min, max] of group.fields) controls.set(field, addField(fields, id + "-capture-" + field, type, title, help, min, max));
      }
      const state = put(find(id + "-fields"), "p", "", "service-note"); state.id = id + "-configured-state";
    }
    const readCapture = key => Object.fromEntries(CAPTURE_FIELDS.map(([field, type]) => {
      const control = captureControls.get(key).get(field);
      return [field, type === "check" ? control.checked : type === "expiry"
        ? control.value === loadedExpiries.get(key)?.text ? loadedExpiries.get(key).value : control.value ? new Date(control.value).getTime() : 0 : control.value];
    }));
    const renderPolicy = (target, service, label) => {
      clear(target); put(target, "strong", label);
      for (const line of capturePolicy(service)) put(target, "p", line);
    };
    const selectStep = (next, focus = true) => {
      step = next; connectionRevision++;
      for (const name of ["landing", "existing-load", "dashboard", "configure", "review", "done"]) find(name + "-view").hidden = name !== next;
      find("setup-progress").hidden = flow !== "setup" || !["configure", "review", "done"].includes(next);
      const names = ["configure", "review", "done"], active = names.indexOf(next);
      for (const [index, name] of names.entries()) {
        const item = find("step-" + name); item.classList.toggle("complete", index < active);
        if (name === next) item.setAttribute("aria-current", "step"); else item.removeAttribute("aria-current");
      }
      if (focus) find(next + "-heading").focus();
    };
    const syncInputs = () => {
      configuration.disabled = busy;
      const editing = flow === "existing" && editKey !== null, capture = phase === "capture" || editing;
      find("configure-view").classList.toggle("single-service-editor", editing);
      find("workspace-settings").hidden = capture || addingService;
      find("setup-prerequisites").hidden = capture || flow !== "setup" || addingService;
      find("service-selection-heading").hidden = capture;
      find("capture-privacy-note").hidden = !capture;
      find("capture-next-button").hidden = capture;
      find("review-button").hidden = !capture;
      find("services-back-button").hidden = !capture || editing;
      find("editor-back-button").hidden = !editing;
      find("setup-back-button").hidden = editing;
      find("configure-step-detail").textContent = capture ? "Capture levels" : "Services & locations";
      find("configure-eyebrow").textContent = editing ? "SERVICE CONFIGURATION" : capture ? "02 / CAPTURE LEVELS" : "01 / SERVICES & LOCATIONS";
      find("configure-heading").textContent = editing ? "Edit capture settings · " + SERVICES.find(service => service.key === editKey).label : capture ? "Capture levels" : addingService ? "Add a service" : "Services & locations";
      find("configure-description").textContent = editing ? "Only this service is included in your changes. Other services and running processes stay untouched."
        : capture ? "Choose a level for each selected service. Open extra options only when you need them."
          : addingService ? "Add to " + loadedConfig.installDir + ". Existing services and settings are preserved." : "Choose working local services. Capture options come next.";
      find("configure-action-help").textContent = editing ? "Review a before-and-after plan, then save. Restart is a separate action." : "No files are written until you review and confirm.";
      for (const {key, id: name} of SERVICES) {
        const dashboardButton = find(name + "-configure-button");
        if (dashboardButton) dashboardButton.disabled = busy || !!loadedConfig?.[key]?.loadError;
        const installed = addingService && loadedConfig?.[key]?.configured;
        find(name + "-enabled").disabled = editing || capture || loadErrors.has(key) || installed;
        find(name + "-enabled").hidden = capture;
        const enabled = find(name + "-enabled").checked;
        find(name + "-card").hidden = editing ? key !== editKey : installed || (capture && !enabled);
        find(name + "-fields").disabled = !enabled;
        find(name + "-fields").hidden = !enabled;
        find(name + "-card").classList.toggle("selected", enabled);
        capturePanels.get(key).hidden = !capture;
        for (const element of serviceBasics.get(key)) element.hidden = capture;
        find(name + "-configured-state").hidden = !editing;
        const controls = captureControls.get(key);
        const overview = controls.get("captureMode").value === "overview", scoped = prefixes(controls.get("detailedCapturePaths").value).length > 0, notices = [];
        if (overview && scoped) notices.push("Matching paths use Detailed (methods + lines); other paths remain Overview. Clear scope for Overview everywhere. The expiry still limits this detail.");
        if ((!overview || scoped) && !controls.get("trackMethods").checked) notices.push("Method entry / exit is disabled in Advanced. Enable it there to record method history at this level.");
        find(name + "-capture-notice").hidden = !notices.length;
        find(name + "-capture-notice").textContent = notices.join(" ");
        for (const [query, binding] of [["captureJdbcQueries", "captureJdbcParameters"], ["captureClickHouseQueries", "captureClickHouseParameters"]]) {
          if (!controls.get(query).checked) controls.get(binding).checked = false;
          controls.get(binding).disabled = !controls.get(query).checked;
        }
        renderPolicy(find(name + "-capture-policy"), {httpIncludePaths: find(name + "-paths").value, capture: readCapture(key)}, "Policy preview · not applied to a running service");
      }
      find("review-button").disabled = busy;
      find("edit-button").disabled = busy;
      for (const id of ["capture-next-button", "services-back-button", "editor-back-button", "setup-back-button", "load-dashboard", "landing-back", "dashboard-refresh", "dashboard-change-install", "dashboard-add-service", "dashboard-service-actions"]) find(id).disabled = busy;
      find("choose-setup").disabled = busy || !defaultsSnapshot;
      find("choose-existing").disabled = busy || !defaultsSnapshot;
      find("existing-install-dir").disabled = busy;
      find("dashboard-add-service").disabled = busy || !loadedConfig || SERVICES.every(({key}) => loadedConfig[key]?.configured);
      find("dashboard-service-actions").disabled = busy || !loadedConfig || !SERVICES.some(({key}) => loadedConfig[key]?.configured && !loadedConfig[key]?.loadError);
      find("done-edit-button").disabled = busy || lifecyclePolling;
      find("done-service-actions").disabled = busy || lifecyclePolling;
      find("review-ack").disabled = busy || !plan?.files.length;
      find("apply-button").disabled = busy || !plan?.files.length || !find("review-ack").checked;
      let selected = false;
      for (const choice of restartChoices.values()) {
        selected ||= choice.enabled.checked;
        choice.enabled.disabled = busy || lifecyclePolling;
        for (const control of choice.controls.values()) control.disabled = busy || lifecyclePolling || !choice.enabled.checked;
        choice.profileFields.hidden = !choice.controls.get("type").value;
      }
      find("restart-plan-button").disabled = busy || lifecyclePolling || !savedConfig || !selected;
      find("restart-ack").disabled = busy || lifecyclePolling || !lifecyclePlan?.services?.some(service => service.status === "ready");
      find("restart-apply-button").disabled = find("restart-ack").disabled || !find("restart-ack").checked;
    };
    const setBusy = value => { busy = value; document.body.setAttribute("aria-busy", String(value)); syncInputs(); };
    const invalidate = () => { revision++; plan = null; reviewedRequest = null; find("review-ack").checked = false; syncInputs(); };
    const readForm = () => {
      const input = {installDir: find("install-dir").value, viewerPort: find("viewer-port").value};
      for (const {key, id, launcher} of SERVICES) {
        input[key] = {enabled: editKey !== null ? key === editKey : find(id + "-enabled").checked && !(addingService && loadedConfig?.[key]?.configured), sourceRoot: find(id + "-source").value,
          debugPort: find(id + "-port").value, httpIncludePaths: find(id + "-paths").value};
        input[key].capture = readCapture(key);
        if (launcher) Object.assign(input[key], {launchJar: find(id + "-jar").value, javaHome: find(id + "-java").value});
      }
      return requestFrom(input);
    };
    const fillForm = defaults => {
      loadErrors.clear();
      find("install-dir").value = string(defaults.installDir); find("viewer-port").value = defaults.viewerPort ?? "";
      find("viewer-port").readOnly = defaults.configured === true;
      for (const {key, id: name, launcher} of SERVICES) {
        const service = defaults[key] || {};
        if (service.loadError) loadErrors.set(key, string(service.loadError));
        find(name + "-enabled").checked = service.enabled === true && !service.loadError;
        find(name + "-source").value = string(service.sourceRoot);
        find(name + "-source").readOnly = service.configured === true;
        find(name + "-port").value = service.debugPort ?? "";
        find(name + "-port").readOnly = service.configured === true;
        find(name + "-paths").value = prefixes(service.httpIncludePaths).join("\n");
        const capture = service.capture || {};
        for (const [field, type] of CAPTURE_FIELDS) {
          const control = captureControls.get(key).get(field), value = capture[field] ?? defaults[field];
          if (type === "check") control.checked = value === true;
          else if (type === "list") control.value = prefixes(value).join("\n");
          else if (type === "expiry") {
            const date = new Date(Number(value));
            control.value = Number(value) > 0 && Number.isFinite(date.getTime()) ? new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, -1) : "";
            loadedExpiries.set(key, {value: Number(value) || 0, text: control.value});
          } else control.value = value ?? (type === "level" ? "detailed" : "");
        }
        find(name + "-configured-state").textContent = service.configured ? "Configured locally. Only capture settings can be edited here; source, agent port and existing launcher are preserved. Restart is separate."
          + (service.launcherAvailable === false ? " No generated launcher was found; service actions can use a registered supported launcher, or use manual startup." : "") : "Not loaded from an existing installation.";
        let loadIssue = find(name + "-load-error");
        if (!loadIssue) { loadIssue = put(find(name + "-card"), "p", "", "error-message service-load-error"); loadIssue.id = name + "-load-error"; }
        loadIssue.hidden = !service.loadError;
        loadIssue.textContent = service.loadError ? "Could not load this service: " + service.loadError + ". It is excluded from changes. Resolve the file issue and reload." : "";
        if (launcher) {
          find(name + "-jar").value = string(service.launchJar);
          find(name + "-java").value = string(service.javaHome);
          find(name + "-jar").readOnly = service.configured === true;
          find(name + "-java").readOnly = service.configured === true;
        }
      }
      syncInputs();
    };
    const request = async (path, data) => {
      const options = {method: data === undefined ? "GET" : "POST", headers: {"X-TraceLens-Setup": token, "Accept": "application/json"},
        credentials: "same-origin", cache: "no-store", redirect: "error"};
      if (data !== undefined) { options.headers["Content-Type"] = "application/json"; options.body = JSON.stringify(data); }
      let response;
      try { response = await fetchRequest(path, options); } catch (_) { throw Error("Cannot reach the local setup server. Check that its terminal is still running and reopen its setup URL if needed."); }
      let body;
      try { body = await response.json(); } catch (_) { throw Error("The local setup server returned an unreadable response. Check its terminal for details."); }
      if (!response.ok) throw Error(errorMessage(response, body));
      if (!body || typeof body !== "object" || Array.isArray(body)) throw Error("The local setup server returned an unexpected response. Check its terminal for details.");
      return body;
    };
    const renderWarnings = (target, warnings) => {
      clear(target); const items = Array.isArray(warnings) ? warnings.filter(item => typeof item === "string") : [];
      target.hidden = !items.length;
      if (!items.length) return;
      put(target, "h3", "Before you continue"); const list = put(target, "ul", "");
      for (const warning of items) put(list, "li", warning);
    };
    const renderSummary = (target, result, config) => {
      clear(target);
      const services = SERVICES.filter(({key}) => config[key]?.enabled).map(({label}) => label).join(" + ");
      const levels = SERVICES.filter(({key}) => config[key]?.enabled && (target.id !== "done-summary" || !Array.isArray(result.services)
        || result.services.some(service => service.key === key && ["ready", "installed", "updated", "existing"].includes(service.status))))
        .map(({key, label}) => label + ": " + (config[key].capture?.captureMode || config.captureMode || "detailed")).join(" · ");
      for (const [label, value] of [["Selected services", services], ["Install directory", result.installDir || config.installDir], ["Primary viewer config", result.viewerConfig], [target.id === "done-summary" ? "Saved capture levels" : "Capture levels after save", levels]]) {
        if (!value) continue;
        const row = put(target, "div", "", "summary-item"); put(row, "span", label); put(row, "code", string(value));
      }
    };
    const renderServices = (target, services, installed, config) => {
      clear(target);
      target.hidden = !Array.isArray(services) || !services.length;
      if (target.hidden) return;
      put(target, "h3", "Service outcomes");
      const list = put(target, "ul", "", "service-outcomes");
      for (const service of services) {
        const descriptor = SERVICES.find(item => item.key === service.key);
        if (!descriptor) continue;
        const state = ["installed", "updated"].includes(service.status) ? "ready" : ["ready", "existing", "failed"].includes(service.status) ? service.status : "failed";
        const updating = service.operation === "update" || service.status === "updated";
        const row = put(list, "li", "", "service-outcome " + state);
        const header = put(row, "div", "", "service-outcome-heading");
        put(header, "strong", descriptor.label);
        put(header, "span", state === "ready" ? installed ? updating ? "Updated" : "Installed" : updating ? "Ready to update" : "Ready to add"
          : state === "existing" ? "Already configured" : "Needs attention", "outcome-badge");
        put(row, "p", state === "ready" ? installed ? "The reviewed files for this service are in place."
          : "Included in the file plan below."
          : state === "existing" ? "Already configured; no setting changes are needed. Existing files are preserved."
            : string(service.error) || "This service was not included in the install plan. Edit its settings and review again.");
        if (service.restartRequired) put(row, "p", "Restart required to use these settings. Saving alone does not change a running service.", "restart-required");
        if (state !== "failed" && config?.[service.key]?.capture) renderPolicy(put(row, "div", "", "capture-policy-summary"), config[service.key], installed ? "Saved policy · not proof of active capture" : "Policy after save and restart");
        if (Array.isArray(service.configChanges) && service.configChanges.length) {
          const details = put(row, "details", "", "config-changes"); put(details, "summary", service.configChanges.length + " setting changes");
          const changes = put(details, "dl", "");
          for (const change of service.configChanges) {
            put(changes, "dt", string(change.field));
            put(changes, "dd", JSON.stringify(change.before) + " → " + JSON.stringify(change.after));
          }
        }
      }
    };
    const renderCommands = (target, commands) => {
      clear(target);
      if (!Array.isArray(commands) || !commands.length) { put(target, "p", "No launcher command was supplied. Check the generated files for manual next steps.", "muted"); return; }
      for (const command of commands) {
        if (!command || typeof command.command !== "string") continue;
        const item = put(target, "div", "", "command-item"); put(item, "h4", string(command.label) || "Generated command");
        put(item, "pre", command.command);
        const copy = put(item, "button", "Copy command", "button secondary copy-button"); copy.type = "button";
        copy.addEventListener("click", async () => {
          try {
            const clipboard = document.defaultView?.navigator?.clipboard;
            if (!clipboard) throw Error();
            await clipboard.writeText(command.command); copy.textContent = "Copied"; message("Command copied. It has not been run.");
          } catch (_) { message("Clipboard access is unavailable. Select the command text and copy it manually."); }
        });
      }
    };
    const renderPlan = result => {
      renderSummary(find("review-summary"), result, reviewedRequest); renderWarnings(find("review-warnings"), result.warnings);
      renderServices(find("review-services"), result.services, false, reviewedRequest);
      const rows = find("planned-files"); clear(rows);
      find("file-count").textContent = result.files.length + (result.files.length === 1 ? " file" : " files");
      for (const file of result.files) {
        const row = put(rows, "tr", ""), action = put(row, "td", ""), description = put(row, "td", "");
        put(action, "span", string(file.action) || "write", "file-action"); put(description, "code", file.path);
        if (file.description) put(description, "p", string(file.description));
      }
      if (!result.files.length) {
        const row = put(rows, "tr", ""), cell = put(row, "td", "Nothing to save. Existing services are unchanged. Resolve any service issues above, then review again.", "muted");
        cell.setAttribute("colspan", "2");
      }
      renderCommands(find("planned-commands"), result.commands);
    };
    const renderDone = (result, config) => {
      const failed = result.services?.some(service => service.status === "failed");
      const appInstalled = result.services ? result.services.some(service => service.key === "cbApp" && ["ready", "installed"].includes(service.status) && service.operation !== "update") : config.cbApp?.enabled;
      find("done-heading").textContent = failed ? "Ready changes saved. Some need attention." : "Your local configuration is saved.";
      find("done-description").textContent = failed
        ? "Ready changes were saved, but some services did not complete. Review their outcomes and warnings; partial files may remain. Unselected services were preserved. No application has been started or restarted."
        : appInstalled
        ? "The reviewed files are in place. No application has been started or restarted. The cb-app startup files now include the reviewed opt-in TraceLens hook."
        : "The reviewed files are in place. No application has been started or restarted. Your service launchers or IDE options are ready for you to use.";
      renderSummary(find("done-summary"), result, config); renderWarnings(find("done-warnings"), result.warnings);
      renderServices(find("done-services"), result.services, true, config);
      renderCommands(find("done-commands"), result.commands);
      const ideServices = SERVICES.filter(({key}) => key !== "cbApp" && config[key]?.enabled && !config[key]?.launchJar
        && (!result.services || result.services.some(service => service.key === key && ["ready", "installed"].includes(service.status) && service.operation !== "update")));
      find("ide-next-step").hidden = !ideServices.length;
      find("ide-services").textContent = ideServices.map(({label}) => label).join(", ");
      const files = find("installed-files"); clear(files);
      for (const file of Array.isArray(result.files) ? result.files : []) put(files, "li", typeof file === "string" ? file : string(file.path));
      savedConfig = {...config, installDir: result.installDir || config.installDir};
      const viewerLink = find("verify-viewer-link"), viewerUrl = viewerHandoff(savedConfig, result.services);
      viewerLink.hidden = !viewerUrl;
      if (viewerUrl) viewerLink.setAttribute("href", viewerUrl); else viewerLink.removeAttribute("href");
      renderRestartChoices(result, config);
      find("restart-section").hidden = true;
      const changed = (result.services || []).filter(service => service.status === "updated" || (service.status === "ready" && service.operation === "update"));
      find("done-service-actions").hidden = !restartChoices.size;
      if (flow === "existing") for (const service of changed) if (restartChoices.has(service.key)) restartChoices.get(service.key).enabled.checked = true;
      find("installation-next-steps").hidden = flow !== "setup";
      find("done-command-section").hidden = !result.commands?.length;
      find("installed-disclosure").hidden = !result.files?.length;
      if (flow === "existing" && !failed) find("done-description").textContent = "The reviewed settings are saved. No running service was changed. Review service actions for this update, or restart through your existing launcher, then verify a new trace.";
    };
    const invalidateLifecycle = () => {
      lifecyclePlan = null; lifecycleRequest = null; find("restart-ack").checked = false;
      find("restart-review").hidden = true; syncInputs();
    };
    const renderRestartChoices = (result, config) => {
      lifecycleStatusRevision++; lifecyclePolling = false;
      restartChoices.clear(); clear(find("restart-services")); clear(find("restart-result")); invalidateLifecycle();
      for (const {key, id, label} of SERVICES) {
        if (!config[key]?.enabled || (result.services && !result.services.some(service => service.key === key && ["ready", "installed", "updated", "existing"].includes(service.status)))) continue;
        const row = put(find("restart-services"), "div", "", "restart-choice");
        const enabled = addField(row, id + "-restart", "check", label, "Leave unchecked to keep this service untouched.");
        const disclosure = put(row, "details", "", "advanced"); put(disclosure, "summary", "Launcher settings · optional");
        const fields = put(disclosure, "div", "", "advanced-fields");
        put(fields, "p", "Uses the environment inherited from the setup terminal. Prepare dependencies and any required custom environment there first. No shell command or credential editor is provided. Registered profiles are kept locally.", "muted");
        const controls = new Map();
        controls.set("type", addField(fields, id + "-launcher-type", "launcher", "Launcher", "An existing unmanaged process must be stopped manually; then use Start once to establish managed ownership."));
        const profileFields = put(fields, "div", "", "advanced-fields"); profileFields.hidden = true;
        for (const [field, type, title, help] of [
          ["javaHome", "text", "Java home", "Absolute JDK directory. Match the application's required Java version."],
          ["applicationPath", "text", "Tomcat home or application JAR", "Absolute Tomcat directory or ready-built executable JAR. Nothing is built here."],
          ["applicationPort", "number", "Application HTTP port", "Actual application port (1–65535), not its TraceLens agent port. Used to refuse an unmanaged running instance."],
          ["workingDirectory", "text", "Working directory", "Absolute directory from which to launch the application."],
          ["arguments", "list", "Application arguments", "One argument per line; no shell expansion. Do not put credentials in arguments."],
        ]) controls.set(field, addField(profileFields, id + "-launcher-" + field, type, title, help));
        restartChoices.set(key, {enabled, controls, profileFields});
        for (const control of [enabled, ...controls.values()]) {
          control.addEventListener("change", invalidateLifecycle);
          control.addEventListener("input", invalidateLifecycle);
        }
      }
      find("restart-section").hidden = !restartChoices.size; syncInputs();
    };
    const renderLifecycle = (target, result, applied) => {
      clear(target);
      if (typeof result.warning === "string") put(target, "p", result.warning, "restart-warning");
      const list = put(target, "ul", "", "service-outcomes");
      for (const service of result.services || []) {
        const descriptor = SERVICES.find(item => item.key === service.key); if (!descriptor) continue;
        const row = put(list, "li", "", "service-outcome " + (service.status === "running" || service.status === "ready" ? "ready" : "failed"));
        const heading = put(row, "div", "", "service-outcome-heading"); put(heading, "strong", descriptor.label);
        put(heading, "span", service.status === "manual" ? "Manual action required" : service.status === "failed" ? "Needs attention"
          : service.status === "starting" ? "Starting" : service.status === "restarting" ? "Restarting"
          : applied && service.status === "running" ? "Process + TraceLens responding" : service.action === "restart" ? "Will restart" : "Will start", "outcome-badge");
        put(row, "p", string(service.message) || string(service.reason) || "Review this service in the setup terminal.");
      }
    };
    const validateLifecycle = (result, selected, planning) => {
      const allowed = planning ? ["ready", "manual", "failed"] : ["starting", "restarting", "running", "manual", "failed"];
      if (!Array.isArray(result.services) || result.services.length !== selected.length
          || selected.some(key => result.services.filter(service => service?.key === key).length !== 1)
          || result.services.some(service => !allowed.includes(service.status) || (planning && service.status === "ready" && !["start", "restart"].includes(service.action))))
        throw Error("The server returned incomplete service outcomes. Inspect the setup terminal before requesting another action.");
    };
    const showLifecycleResult = result => {
      renderLifecycle(find("restart-result"), result, true);
      const pending = result.services.some(service => ["starting", "restarting"].includes(service.status));
      message(pending ? "Configuration is saved. Waiting for the selected processes and TraceLens endpoints; business readiness is not checked."
        : result.services.some(service => service.status !== "running") ? "Configuration is saved. Some service actions need attention; review the outcomes below."
          : "Selected processes and TraceLens endpoints are responding. This does not confirm application business readiness; verify your flow before sending traffic.");
      return pending;
    };
    const pollLifecycle = async (selected, generation) => {
      if (generation !== lifecycleStatusRevision || step !== "done") return;
      try {
        const result = await request("/api/setup/lifecycle/status", {installDir: selected.installDir, services: selected.services});
        validateLifecycle(result, selected.services, false);
        if (generation !== lifecycleStatusRevision) return;
        lifecyclePolling = showLifecycleResult(result);
        if (lifecyclePolling) (document.defaultView?.setTimeout || setTimeout)(() => pollLifecycle(selected, generation), 2000);
      } catch (failure) {
        lifecyclePolling = false;
        showError(failure.message + " Configuration remains saved. Process status is unconfirmed; inspect the terminal before requesting another action.");
        message("Service status checks stopped. No service action will be retried automatically.");
      } finally { syncInputs(); }
    };
    const closeCaptureOptions = () => { for (const disclosure of captureDisclosures) disclosure.open = false; };
    const openEditor = key => {
      if (busy || !loadedConfig?.[key]?.configured || loadedConfig[key].loadError) return;
      flow = "existing"; phase = "capture"; editKey = key; addingService = false;
      clearError(); invalidate(); fillForm(loadedConfig); closeCaptureOptions();
      for (const service of SERVICES) find(service.id + "-enabled").checked = service.key === key;
      syncInputs(); selectStep("configure"); message("Editing " + SERVICES.find(service => service.key === key).label + " only. Nothing has been saved.");
    };
    const renderDashboard = () => {
      find("dashboard-install").textContent = loadedConfig.installDir;
      const target = find("dashboard-services"); clear(target);
      const configured = SERVICES.filter(({key}) => loadedConfig[key]?.configured);
      for (const {key, id, label} of configured) {
        const service = loadedConfig[key], connection = connections.get(key);
        const card = put(target, "section", "", "card dashboard-service"); card.id = id + "-dashboard-card";
        const heading = put(card, "div", "", "service-outcome-heading"); put(heading, "h3", label);
        put(heading, "span", service.loadError ? "Needs attention" : "Configured", "outcome-badge");
        const level = service.capture?.captureMode;
        put(card, "p", "Saved capture level: " + (LEVELS[level] || "Unknown"), "dashboard-level");
        if (!service.loadError) renderPolicy(put(card, "div", "", "capture-policy-summary"), service, "Saved policy · not proof of active capture");
        const statusText = {connected: "Agent connected", unavailable: "Agent unavailable", mismatch: "Different agent responded", unknown: "Connection not checked"}[connection?.connectionStatus] || "Connection not checked";
        put(card, "p", statusText, "connection-status " + (connection?.connectionStatus || "unknown"));
        if (connection?.connectionMessage) put(card, "p", string(connection.connectionMessage), "muted");
        if (connection?.restartRequired === true) put(card, "p", "Managed process needs a restart to use its saved settings.", "restart-warning");
        else put(card, "p", connection?.status === "running" && connection.restartRequired === false
          ? "No pending configuration restart reported by the managed process. A new trace still needs verification."
          : "Running capture settings are unverified. For manual launches, restart after edits and confirm the agent JAR supports the selected level.", "muted");
        if (service.loadError) put(card, "p", string(service.loadError), "service-load-error");
        const button = put(card, "button", "Edit capture settings", "button secondary"); button.id = id + "-configure-button"; button.type = "button";
        button.disabled = busy || !!service.loadError; button.addEventListener("click", () => openEditor(key));
      }
      if (!configured.length) put(target, "p", "No configured services were found. Add a service to this installation.", "muted");
      syncInputs();
    };
    const refreshConnections = async () => {
      const selected = SERVICES.filter(({key}) => loadedConfig?.[key]?.configured && !loadedConfig[key].loadError).map(({key}) => key);
      if (!selected.length) return;
      const installDir = loadedConfig.installDir, generation = ++connectionRevision;
      find("dashboard-connection-note").textContent = "Checking local agents… You can configure services while this check runs.";
      try {
        const result = await request("/api/setup/lifecycle/status", {installDir, services: selected});
        if (generation !== connectionRevision || step !== "dashboard" || loadedConfig?.installDir !== installDir) return;
        if (!Array.isArray(result.services)) throw Error("No connection outcomes returned.");
        for (const item of result.services) if (selected.includes(item?.key)) connections.set(item.key, item);
        renderDashboard();
        find("dashboard-connection-note").textContent = "Connection is a point-in-time agent check, not application readiness or permission to restart. Reload to check again.";
      } catch (_) {
        if (generation !== connectionRevision || step !== "dashboard" || loadedConfig?.installDir !== installDir) return;
        connections.clear(); renderDashboard();
        find("dashboard-connection-note").textContent = "Connection not checked. Saved configuration is available; reload to try the local agent check again.";
      }
    };
    const showDashboard = () => {
      flow = "existing"; editKey = null; addingService = false; phase = "services";
      invalidate(); fillForm(loadedConfig); renderDashboard(); selectStep("dashboard");
      find("dashboard-connection-note").textContent = connections.size
        ? "Connection is a point-in-time agent check, not application readiness or permission to restart. Reload to check again."
        : "Connection not checked. Saved configuration is available; reload to check local agents.";
    };
    const loadExisting = async () => {
      if (busy || lifecyclePolling) return;
      const installDir = step === "existing-load" ? find("existing-install-dir").value.trim() : step === "done" ? savedConfig?.installDir : loadedConfig?.installDir;
      if (!installDir) { showError("Choose the existing installation directory first."); return; }
      connectionRevision++;
      clearError(); invalidate(); invalidateLifecycle(); setBusy(true); message("Reading the existing configuration. No files or running services are changed.");
      try {
        const current = await request("/api/setup/load", {installDir}); validateLoaded(current);
        if (!current.configured) throw Error("This directory has no wizard-managed installation. Choose Set up TraceLens for a new workspace.");
        loadedConfig = current; connections.clear(); showDashboard();
        message("Saved services loaded. Choose Edit capture settings for one service, or open Service actions separately.");
        refreshConnections();
      } catch (failure) { showError(failure.message); message("Configuration was not loaded. Your displayed values were left unchanged."); }
      finally { setBusy(false); }
    };
    const validateLoaded = current => {
      if (typeof current.installDir !== "string" || !current.installDir || !Number.isInteger(current.viewerPort)
          || SERVICES.some(({key}) => !current[key] || typeof current[key] !== "object" || !current[key].capture || typeof current[key].capture !== "object"))
        throw Error("The server returned an incomplete configuration. Existing form values were not replaced; check the terminal and reload.");
    };
    const loadDefaults = async () => {
      clearError(); find("retry-defaults").hidden = true; setBusy(true); message("Loading local setup defaults…");
      try {
        if (!token || token === "__TRACELENS_SETUP_TOKEN__") throw Error("This page has no valid local setup session. Open the setup URL printed in the TraceLens terminal; do not open the HTML file directly.");
        const defaults = await request("/api/setup/defaults"); defaultsSnapshot = defaults; fillForm(defaults); invalidate();
        find("existing-install-dir").value = string(defaults.installDir); message("Choose new setup or an existing installation. No applications have been changed.");
      } catch (failure) { showError(failure.message); find("retry-defaults").hidden = false; message(""); }
      finally { setBusy(false); if (!find("retry-defaults").hidden) configuration.disabled = true; }
    };
    form.addEventListener("input", () => { if (!busy && step === "configure") { invalidate(); clearError(); } });
    form.addEventListener("change", () => { if (!busy && step === "configure") { invalidate(); clearError(); } });
    find("review-ack").addEventListener("change", syncInputs);
    find("edit-button").addEventListener("click", () => { if (busy) return; invalidate(); clearError(); selectStep("configure"); message("Edit your settings, then review a fresh file plan."); });
    find("retry-defaults").addEventListener("click", loadDefaults);
    find("choose-setup").addEventListener("click", () => {
      if (busy || !defaultsSnapshot) return;
      flow = "setup"; phase = "services"; editKey = null; addingService = false; loadedConfig = null; connections.clear();
      clearError(); invalidate(); fillForm(defaultsSnapshot); closeCaptureOptions(); selectStep("configure"); syncInputs();
      message("Choose your services and local paths. Capture levels come next.");
    });
    const openExistingChoice = () => {
      if (busy) return;
      flow = "existing"; editKey = null; addingService = false; clearError(); invalidate();
      find("existing-install-dir").value = loadedConfig?.installDir || string(defaultsSnapshot?.installDir);
      selectStep("existing-load"); message("Load the installation you want to configure. Nothing is saved or restarted.");
    };
    find("choose-existing").addEventListener("click", openExistingChoice);
    find("dashboard-change-install").addEventListener("click", openExistingChoice);
    find("landing-back").addEventListener("click", () => { if (!busy) { clearError(); invalidate(); selectStep("landing"); message("Choose new setup or an existing installation."); } });
    find("existing-load-form").addEventListener("submit", event => { event.preventDefault(); return loadExisting(); });
    find("dashboard-refresh").addEventListener("click", loadExisting);
    find("done-edit-button").addEventListener("click", loadExisting);
    find("done-service-actions").addEventListener("click", () => {
      if (busy || lifecyclePolling || step !== "done" || find("done-service-actions").hidden) return;
      invalidateLifecycle(); find("restart-section").hidden = false; find("restart-heading").focus();
      message("Choose the services to apply or restart. Successful updates are preselected; new services are not. Review actions before giving separate consent; no service action has been requested.");
    });
    find("editor-back-button").addEventListener("click", () => {
      if (busy || !loadedConfig) return;
      clearError(); showDashboard(); message("Unsaved edits discarded. Your saved service configuration is unchanged.");
    });
    find("setup-back-button").addEventListener("click", () => {
      if (busy) return;
      clearError();
      if (addingService && loadedConfig) { showDashboard(); message("Unsaved additions discarded. Existing services are unchanged."); }
      else { invalidate(); selectStep("landing"); message("Choose new setup or an existing installation."); }
    });
    find("dashboard-add-service").addEventListener("click", () => {
      if (busy || !loadedConfig || SERVICES.every(({key}) => loadedConfig[key]?.configured)) return;
      flow = "setup"; phase = "services"; editKey = null; addingService = true;
      clearError(); invalidate(); fillForm(loadedConfig); closeCaptureOptions();
      for (const {id} of SERVICES) find(id + "-enabled").checked = false;
      syncInputs(); selectStep("configure"); message("Select services to add. Existing services are excluded from this change.");
    });
    find("capture-next-button").addEventListener("click", () => {
      if (busy || step !== "configure" || phase !== "services") return;
      clearError();
      try { if (!form.reportValidity()) return; readForm(); } catch (failure) { showError(failure.message); return; }
      phase = "capture"; closeCaptureOptions(); syncInputs(); selectStep("configure"); message("Choose capture levels. Evidence and advanced settings are optional disclosures.");
    });
    find("services-back-button").addEventListener("click", () => {
      if (busy || editKey !== null) return;
      phase = "services"; invalidate(); syncInputs(); selectStep("configure"); message("Adjust selected services and locations, then continue to capture levels.");
    });
    find("dashboard-service-actions").addEventListener("click", async () => {
      if (busy || !loadedConfig?.configured) return;
      clearError(); invalidate(); setBusy(true); message("Reading saved settings for service actions. Unsaved form edits are not being applied.");
      try {
        const current = await request("/api/setup/load", {installDir: loadedConfig.installDir}); validateLoaded(current);
        const services = SERVICES.filter(({key}) => current[key]?.configured && !current[key]?.loadError).map(({key}) => ({key, status: "existing"}));
        if (!services.length) throw Error("No readable configured services are available. Reload and resolve their configuration issues first.");
        loadedConfig = current;
        renderDone({installDir: current.installDir, files: [], services, warnings: []}, current);
        find("restart-section").hidden = !restartChoices.size;
        find("done-heading").textContent = "Service actions for saved configuration";
        find("done-description").textContent = "These actions use the current on-disk settings for the loaded installation. Unsaved form edits have not been applied. No service has been started or stopped.";
        selectStep("done"); message("Select services, then review their actions. No configuration files have been saved or processes changed.");
      } catch (failure) { showError(failure.message); message("Saved service actions could not be opened. No process action was requested."); }
      finally { setBusy(false); }
    });
    find("restart-ack").addEventListener("change", syncInputs);
    find("restart-plan-button").addEventListener("click", async () => {
      if (busy || lifecyclePolling || !savedConfig) return;
      const services = [], launchers = {};
      for (const [key, choice] of restartChoices) {
        if (!choice.enabled.checked) continue;
        services.push(key);
        if (choice.controls.get("type").value) {
          const profile = {};
          for (const [field, control] of choice.controls) profile[field] = field === "arguments" ? control.value.split(/\r?\n/).filter(value => value.trim())
            : field === "applicationPort" && /^\d+$/.test(control.value.trim()) ? Number(control.value) : control.value.trim();
          launchers[key] = profile;
        }
      }
      if (!services.length) return;
      const selected = {installDir: savedConfig.installDir, services};
      if (Object.keys(launchers).length) selected.launchers = launchers;
      clearError(); clear(find("restart-result")); invalidateLifecycle(); setBusy(true); message("Checking the selected service actions. No services are being started or stopped.");
      try {
        const result = await request("/api/setup/lifecycle/plan", selected);
        if (typeof result.planId !== "string" || !result.planId) throw Error("The server returned an incomplete service action plan. No action was requested.");
        validateLifecycle(result, selected.services, true);
        lifecyclePlan = result; lifecycleRequest = selected;
        renderLifecycle(find("restart-plan"), result, false); find("restart-review").hidden = false;
        message("Review each service action. Manual / unavailable services will not be restarted automatically.");
      } catch (failure) { showError(failure.message); message("Configuration remains saved. Service actions were not requested."); }
      finally { setBusy(false); }
    });
    find("restart-apply-button").addEventListener("click", async () => {
      if (busy || lifecyclePolling || !lifecyclePlan?.services?.some(service => service.status === "ready") || !lifecycleRequest || !find("restart-ack").checked) return;
      const selected = {...lifecycleRequest, planId: lifecyclePlan.planId, confirmed: true};
      clearError(); setBusy(true); message("Running only the separately reviewed service actions. Configuration remains saved.");
      find("done-description").textContent = "The reviewed configuration is saved. Service actions were requested separately; check each outcome below before sending traffic.";
      try {
        const result = await request("/api/setup/lifecycle/apply", selected);
        validateLifecycle(result, selected.services, false);
        lifecyclePolling = showLifecycleResult(result);
        if (lifecyclePolling) {
          const generation = ++lifecycleStatusRevision;
          (document.defaultView?.setTimeout || setTimeout)(() => pollLifecycle(selected, generation), 2000);
        }
      } catch (failure) { showError(failure.message + " No automatic retry will be made; a process may already have started or stopped. Check the terminal and review a new action plan."); message("Configuration remains saved; service action completion is uncertain."); }
      finally { invalidateLifecycle(); setBusy(false); }
    });
    form.addEventListener("submit", async event => {
      event.preventDefault(); if (busy || step !== "configure" || phase !== "capture") return; clearError(); invalidate();
      let config;
      try { if (!form.reportValidity()) return; config = readForm(); } catch (failure) { showError(failure.message); return; }
      const currentRevision = revision; setBusy(true); message("Checking paths and preparing a file plan. Nothing is being installed.");
      try {
        const result = await request("/api/setup/plan", config);
        if (currentRevision !== revision) throw Error("Settings changed while preparing the plan. Review the current configuration again.");
        if (typeof result.planId !== "string" || !result.planId || !Array.isArray(result.files)
            || result.files.some(file => !file || typeof file.path !== "string" || !file.path)) throw Error("The server returned an incomplete file plan. Nothing was installed. Check the setup terminal and review again.");
        if (flow === "setup" && !addingService && (result.existingInstallation === true
            || result.services?.some(service => service.operation === "update" || service.status === "existing")))
          throw Error("This directory already contains configured services. Go Back and choose Edit capture settings to load its saved settings; use Add service from that dashboard. Nothing was saved.");
        reviewedRequest = result.request && typeof result.request === "object" && !Array.isArray(result.request) ? result.request : config;
        plan = result; renderPlan(result); selectStep("review");
        message(!result.files.length ? "No files need saving. Review the service outcomes below."
          : result.services?.some(service => service.status === "failed") ? "Some services need attention. You can save ready changes after reviewing the plan. No files have been written."
            : "Review ready. No files have been written.");
      } catch (failure) { invalidate(); showError(failure.message); message(""); }
      finally { setBusy(false); }
    });
    find("apply-button").addEventListener("click", async () => {
      if (busy || !plan?.files.length || !reviewedRequest || !find("review-ack").checked) return;
      const selectedPlan = plan, config = reviewedRequest;
      clearError(); setBusy(true); message("Saving only the reviewed local files. Applications are not being started or restarted.");
      try {
        const result = await request("/api/setup/apply", {request: config, planId: selectedPlan.planId});
        if (result.installed !== true) throw Error("The server did not confirm installation. Inspect the install directory and setup terminal before reviewing again.");
        const completed = {...result, services: result.services || selectedPlan.services};
        renderDone(completed, config); invalidate(); selectStep("done");
        message(completed.services?.some(service => service.status === "failed")
          ? "Ready changes saved. Some selected services still need attention. No applications were started or restarted."
          : "Configuration saved. No applications were built, started, stopped, or restarted.");
      } catch (failure) {
        invalidate(); selectStep("configure"); showError(failure.message + "\nIf the request reached the server, some files may already have been written. Check the install directory; review a fresh plan before retrying."); message("Installation is not confirmed. No automatic retry will be made.");
      } finally { setBusy(false); }
    });
    selectStep("landing", false); loadDefaults();
  }
  return {requestFrom, prefixes, capturePolicy, viewerHandoff, errorMessage, start};
});
