/* Explicit incoming-request evidence. Never replayed or included in search summaries. */
(function (root, factory) {
  const copy = typeof module === "object" && module.exports ? require("./evidence-copy.js") : root.TraceLensEvidenceCopy;
  const api = factory(copy);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensRequestData = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (copy) {
  "use strict";
  const statuses = Object.freeze({captured: "Captured", "not-consumed": "Not read by the application",
    incomplete: "Incomplete — application did not consume the complete body", "omitted-too-large": "Omitted — capture size limit",
    "omitted-invalid": "Omitted — invalid or unsupported data", "unsupported-content-type": "Omitted — unsupported content type",
    "unsupported-encoding": "Omitted — unsupported content encoding", "unsupported-container": "Omitted — body capture is not supported by this HTTP server",
    "omitted-capacity": "Omitted — active capture capacity reached",
    "omitted-display-limit": "Not displayed — viewer safety limit; captured data is unchanged"});
  const keys = ["httpQueryParameters", "httpQueryParametersStatus", "httpRequestBody", "httpRequestBodyStatus", "httpRequestBodyFormat"];
  function structured(raw, parameters) {
    const omitted = status => ({data: null, status});
    if (typeof raw !== "string") return omitted("omitted-invalid");
    if (raw.length > 16384) return omitted("omitted-display-limit");
    try {
      const value = JSON.parse(raw);
      function withinDepth(item, depth) {
        if (depth > 32) return false;
        if (item === null || typeof item !== "object") return true;
        return Object.values(item).every(child => withinDepth(child, depth + 1));
      }
      // The raw length bounds keys and node count; do not reject valid agent data
      // using extra viewer-only per-key or node limits.
      if (!withinDepth(value, 0)) return omitted("omitted-display-limit");
      if (parameters && (!value || Array.isArray(value) || typeof value !== "object"
        || !Object.values(value).every(row => row === "[REDACTED]" || Array.isArray(row) && row.every(item => typeof item === "string")))) return omitted("omitted-invalid");
      // Keep evidence as text only; suppress terminal/bidi controls even in imported snapshots.
      // JSON.parse uses binary doubles, including Infinity for valid huge numbers.
      // Bodies retain original numeric tokens, never a numeric JSON roundtrip.
      return {status: "captured", data: (parameters ? JSON.stringify(value, null, 2) : raw).replace(/[\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ")};
    } catch (_) { return omitted("omitted-invalid"); }
  }
  function project(trace) {
    const a = trace?.attributes;
    if (!a || typeof a !== "object" || Array.isArray(a) || !keys.some(key => a[key] != null)) return null;
    const field = (label, statusKey, dataKey, parameters) => {
      const status = Object.prototype.hasOwnProperty.call(statuses, a[statusKey]) ? a[statusKey] : "omitted-invalid";
      return {label, ...(status === "captured" ? structured(a[dataKey], parameters) : {status, data: null})};
    };
    const fields = [];
    if (a.httpQueryParametersStatus != null || a.httpQueryParameters != null)
      fields.push(field("Query parameters", "httpQueryParametersStatus", "httpQueryParameters", true));
    if (a.httpRequestBodyStatus != null || a.httpRequestBody != null) {
      const body = field(a.httpRequestBodyFormat === "form" ? "Request body · form fields"
        : a.httpRequestBodyFormat === "json" ? "Request body · JSON" : "Request body",
        "httpRequestBodyStatus", "httpRequestBody", a.httpRequestBodyFormat === "form");
      if (body.status === "captured" && !["json", "form"].includes(a.httpRequestBodyFormat)) {
        body.status = "omitted-invalid"; body.data = null;
      }
      fields.push(body);
    }
    return {fields, redacted: a.httpPayloadRedacted === true || a.httpPayloadRedacted === "true",
      redactionPolicy: ["enabled", "disabled"].includes(a.httpPayloadRedaction) ? a.httpPayloadRedaction : "unknown"};
  }
  function render(document, trace, {collapsible = true} = {}) {
    const model = project(trace); if (!model) return null;
    const panel = document.createElement(collapsible ? "details" : "section");
    panel.className = `request-data${collapsible ? "" : " request-data-embedded"}`;
    const add = (parent, tag, className, text) => { const node = document.createElement(tag); node.className = className;
      node.textContent = text; parent.append(node); return node; };
    const available = model.fields.filter(field => field.status === "captured").length;
    const displayLimited = model.fields.some(field => field.status === "omitted-display-limit");
    if (collapsible) add(panel, "summary", "request-data-summary", `Request data · ${available ? "captured" : displayLimited ? "not displayed" : "not retained"}`);
    const policyNote = model.redactionPolicy === "disabled"
      ? "Redaction was disabled for this capture (local debugging). Captured fields may include credentials and other sensitive values."
      : model.redactionPolicy === "enabled"
        ? "Best-effort credential-field redaction was enabled for this capture; other values may remain sensitive."
        : "Redaction policy was not recorded for this capture. Captured values may include credentials and other sensitive data.";
    add(panel, "p", "request-data-note", `Incoming request data · local opt-in capture. ${policyNote}${model.redacted ? " Some captured fields were marked redacted; their original values cannot be recovered from this capture." : ""} Review before sharing a full snapshot. This data is never replayed.`);
    for (const field of model.fields) {
      const section = add(panel, "section", "request-data-field", "");
      const header = add(section, "div", "request-data-field-header", "");
      add(header, "h4", "", field.label);
      if (field.data !== null) header.append(copy.button(document, field.data,
        field.label === "Query parameters" ? "Copy query parameters" : "Copy request body"));
      add(section, "p", `request-data-status${field.status === "captured" ? "" : " request-data-omitted"}`, statuses[field.status]);
      if (field.data !== null) add(section, "pre", "request-data-value", field.data);
    }
    return panel;
  }
  return Object.freeze({project, render});
});
