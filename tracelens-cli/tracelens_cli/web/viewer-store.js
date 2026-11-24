/* Explicit viewer-file snapshots. No live reads, browser fallback, or automatic saves. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensViewerStore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  function identifier(id) {
    if (typeof id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) {
      throw new Error("Invalid saved snapshot ID.");
    }
    return id;
  }
  function snapshotHash(id) { return "#snapshot=" + identifier(id); }
  function create(api) {
    const path = id => "/api/snapshots/" + identifier(id);
    return Object.freeze({
      async listPins() {
        const doc = await api("/api/snapshots");
        if (!doc || !Array.isArray(doc.snapshots)) throw new Error("Viewer snapshot storage is unavailable. Restart the viewer with the latest version.");
        return doc;
      },
      // Snapshots saved under other service configurations of this viewer. An older viewer has no such route.
      async otherConfigurations() {
        try {
          const doc = await api("/api/snapshots/other-configurations");
          return doc && Array.isArray(doc.configurations) ? doc : null;
        } catch (error) { return null; }
      },
      copyFrom(namespace) {
        if (typeof namespace !== "string" || !/^[0-9a-f]{64}$/.test(namespace)) throw new Error("Invalid saved configuration.");
        return api("/api/snapshots/copy-from", "POST", null, {namespace});
      },
      getPin(id) { return api(path(id)); },
      savePin(snapshot) { return api("/api/snapshots", "POST", null, {snapshot}); },
      capture(request, name) {
        const summary = {};
        for (const field of ["traceId", "label", "method", "path", "channel", "outcome", "status", "httpStatus",
          "startedAtMillis", "durationMs", "hasErrors", "rootMissing", "rootAmbiguous", "user", "site",
          "jobType", "jobId", "jobOutcome", "jobAuditStatus", "jobFailureType", "scheduler"]) {
          const value = request[field];
          if (typeof value === "string" || typeof value === "boolean" || typeof value === "number" && Number.isFinite(value)) summary[field] = value;
        }
        summary.services = Array.isArray(request.services) ? request.services.filter(value => typeof value === "string") : [];
        const body = {traceId: request.traceId, name};
        if (new TextEncoder().encode(JSON.stringify(summary)).byteLength <= 12 * 1024) body.request = summary;
        return api("/api/snapshots/capture", "POST", null, body);
      },
      downloadUrl(id) { return path(id) + "/download"; },
      importArchive(file) {
        if (!file || !Number.isSafeInteger(file.size) || file.size < 1 || file.size > 66 * 1024 * 1024) throw new Error("Choose a capture ZIP archive no larger than 66 MiB.");
        return api("/api/snapshots/import", "POST", 2 * 1024 * 1024, file, "application/zip");
      },
      renamePin(id, name) { return api(path(id) + "/rename", "POST", null, {name}); },
      deletePin(id) { return api(path(id), "DELETE"); },
    });
  }
  return Object.freeze({create, snapshotHash, identifier});
});
