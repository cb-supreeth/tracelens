/* Offline MCP Apps bridge, protocol 2026-01-26.
 * Spec: github.com/modelcontextprotocol/ext-apps/specification/2026-01-26/apps.mdx
 * No fetch, tool calls, source reads, persistence, or application mutations.
 */
(function () {
  "use strict";
  const VERSION = "2026-01-26";
  const INIT_ID = "tracelens-ui-initialize";
  const status = document.getElementById("app-status");
  const container = document.getElementById("request-graph");
  const parent = window.parent;
  let ready = false, closed = false, parentOrigin = null, cleanup = null;
  let observer = null, resizeFrame = null, timer = null, lastSize = "";
  const record = value => !!value && typeof value === "object" && !Array.isArray(value);
  const id = value => typeof value === "string" && value.length > 0 && value.length <= 256;

  function say(message) { status.textContent = message; }
  function post(message) {
    if (closed) return;
    // Sandboxed opaque origins require '*'. Only the immediate parent is ever addressed.
    parent.postMessage({jsonrpc: "2.0", ...message}, parentOrigin && parentOrigin !== "null" ? parentOrigin : "*");
  }
  function clearGraph() {
    if (typeof cleanup === "function") cleanup();
    cleanup = null;
    container.replaceChildren();
  }
  function close() {
    clearTimeout(timer);
    if (observer) observer.disconnect();
    if (resizeFrame !== null) cancelAnimationFrame(resizeFrame);
    window.removeEventListener("message", receive);
    window.removeEventListener("resize", resize);
    window.removeEventListener("pagehide", close);
    clearGraph();
    closed = true;
    ready = false;
  }
  function resize() {
    if (!ready || closed || resizeFrame !== null) return;
    resizeFrame = requestAnimationFrame(function () {
      resizeFrame = null;
      if (!ready || closed) return;
      const width = Math.max(1, Math.ceil(document.documentElement.clientWidth));
      const height = Math.max(1, Math.ceil(document.body.scrollHeight));
      const key = width + ":" + height;
      if (key === lastSize) return;
      lastSize = key;
      post({method: "ui/notifications/size-changed", params: {width, height}});
    });
  }
  function theme(context) {
    if (record(context) && (context.theme === "light" || context.theme === "dark")) {
      document.documentElement.dataset.theme = context.theme;
    }
    // Host CSS/fonts are intentionally not injected: the resource has no network dependencies.
  }
  function validModel(model) {
    if (!record(model) || model.schemaVersion !== 1 || model.kind !== "request-flow"
        || !id(model.traceId) || !Array.isArray(model.nodes) || model.nodes.length > 6000
        || !Array.isArray(model.edges) || model.edges.length > 12000
        || !Array.isArray(model.roots) || model.roots.length > 6000
        || !Array.isArray(model.warnings) || model.warnings.length > 2000) return false;
    const ids = new Set();
    for (const node of model.nodes) {
      if (!record(node) || !id(node.id) || ids.has(node.id)
          || !["segment", "http-call", "dependency-call"].includes(node.kind)) return false;
      ids.add(node.id);
    }
    for (const edge of model.edges) {
      if (!record(edge) || !id(edge.id) || !ids.has(edge.from) || !ids.has(edge.to)
          || !["outbound", "inbound", "observed-call"].includes(edge.kind)) return false;
    }
    if (!model.roots.every(root => ids.has(root))) return false;
    try { return JSON.stringify(model).length <= 4 * 1024 * 1024; }
    catch (_) { return false; }
  }
  function renderResult(result) {
    clearGraph();
    if (!record(result) || result.isError) {
      say("Trace lookup failed. Use the tool's text response for details; no live data was substituted.");
    } else if (!validModel(result.structuredContent)) {
      say("This host did not provide a supported request graph. Use get_trace_visualization or the text/Mermaid fallback.");
    } else if (!window.TraceLensGraph || typeof window.TraceLensGraph.render !== "function") {
      say("The bundled renderer is unavailable. Use the tool's text/Mermaid fallback.");
    } else {
      try {
        // Shared renderer owns interaction. Omitting onSelect prevents unavailable source actions.
        cleanup = window.TraceLensGraph.render(container, result.structuredContent, {});
        say(result.structuredContent.partial ? "Partial retained request evidence — inspect the warnings."
          : "Retained request evidence — causal links, not a synchronized cross-service timeline.");
      } catch (_) {
        clearGraph();
        say("The graph could not be rendered. Use the tool's text/Mermaid fallback.");
      }
    }
    resize();
  }
  function receive(event) {
    if (closed || event.source !== parent || (parentOrigin !== null && event.origin !== parentOrigin)) return;
    const message = event.data;
    if (!record(message) || message.jsonrpc !== "2.0") return;
    if (!ready) {
      if (message.id !== INIT_ID || "method" in message) return;
      clearTimeout(timer);
      const result = message.result;
      if (message.error || !record(result) || result.protocolVersion !== VERSION
          || !record(result.hostCapabilities) || !record(result.hostInfo)) {
        say("This host does not support the required MCP Apps bridge. Use the tool's text/Mermaid fallback.");
        close();
        return;
      }
      parentOrigin = event.origin;
      ready = true;
      theme(result.hostContext);
      post({method: "ui/notifications/initialized", params: {}});
      say("Waiting for the request graph from the host…");
      if (typeof ResizeObserver === "function") {
        observer = new ResizeObserver(resize);
        observer.observe(document.body);
        observer.observe(document.documentElement);
      }
      window.addEventListener("resize", resize);
      resize();
      return;
    }
    if (typeof message.method !== "string") return;
    const request = typeof message.id === "string" || typeof message.id === "number";
    if (message.method === "ui/resource-teardown" && request) {
      post({id: message.id, result: {}});
      say("Request view closed.");
      close();
    } else if (message.method === "ping" && request) {
      post({id: message.id, result: {}});
    } else if (request) {
      post({id: message.id, error: {code: -32601, message: "Unsupported read-only view method"}});
    } else if (message.method === "ui/notifications/tool-result") {
      renderResult(message.params);
    } else if (message.method === "ui/notifications/tool-cancelled") {
      clearGraph();
      say("Trace lookup was cancelled. No request was replayed.");
      resize();
    } else if (message.method === "ui/notifications/host-context-changed") {
      theme(message.params);
      resize();
    }
  }

  if (parent === window) {
    say("Open this view through an MCP Apps host. The standalone TraceLens viewer also supports this graph.");
    return;
  }
  window.addEventListener("message", receive);
  window.addEventListener("pagehide", close);
  timer = setTimeout(function () {
    say("The MCP Apps host did not respond. Use the tool's text/Mermaid fallback or standalone viewer.");
    close();
  }, 8000);
  post({id: INIT_ID, method: "ui/initialize", params: {
    protocolVersion: VERSION, appInfo: {name: "TraceLens request flow", version: "1.0.0"},
    appCapabilities: {availableDisplayModes: ["inline"]}
  }});
})();
