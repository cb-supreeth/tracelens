/* Browser-only saved views and full pinned snapshots. No requests or silent fallback. */
(function (root, factory) {
  "use strict";
  const api = factory(root);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensBrowserStore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (root) {
  "use strict";

  const CONSTANTS = Object.freeze({
    DB_NAME: "tracelens-browser-store", DB_VERSION: 1,
    MAX_NAME_LENGTH: 100, MAX_PINS: 20, MAX_VIEWS: 20,
    MAX_PIN_BYTES: 5 * 1024 * 1024, MAX_TOTAL_BYTES: 20 * 1024 * 1024,
    MAX_FILTER_BYTES: 16 * 1024,
  });

  class BrowserStoreError extends Error {
    constructor(message, code) {
      super(message);
      this.name = "BrowserStoreError";
      this.code = code || "STORAGE_ERROR";
    }
  }

  function fail(message, code) { throw new BrowserStoreError(message, code); }

  function sanitizeName(value) {
    if (typeof value !== "string") fail("Enter a name for this saved item.", "INVALID_NAME");
    const clean = value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, "").trim();
    if (!clean) fail("Enter a name for this saved item.", "INVALID_NAME");
    return Array.from(clean).slice(0, CONSTANTS.MAX_NAME_LENGTH).join("");
  }

  function identifier(value, label, limit) {
    if (typeof value !== "string" || !value.trim() || value.length > limit || /[\u0000-\u001f\u007f]/.test(value)) {
      fail("Invalid " + label + ".", "INVALID_ID");
    }
    return value;
  }

  function serialize(value, label) {
    try {
      const json = JSON.stringify(value, function (key, item) {
        if (["function", "symbol", "bigint", "undefined"].includes(typeof item)
            || (typeof item === "number" && !Number.isFinite(item))) {
          throw new Error("non-JSON value");
        }
        return item;
      });
      if (typeof json !== "string") throw new Error("not serializable");
      return json;
    } catch (error) {
      fail(label + " must contain serializable JSON data (no circular references).", "INVALID_DATA");
    }
  }

  function byteLength(text) {
    if (typeof TextEncoder !== "undefined") return new TextEncoder().encode(text).byteLength;
    // All supported IDB browsers have TextEncoder; retain a correct small helper for older hosts.
    return unescape(encodeURIComponent(text)).length;
  }

  function validateFilters(filters) {
    if (!filters || typeof filters !== "object" || Array.isArray(filters)) {
      fail("Saved view filters must be an object.", "INVALID_FILTERS");
    }
    const json = serialize(filters, "Saved view filters");
    if (byteLength(json) > CONSTANTS.MAX_FILTER_BYTES) fail("Saved view filters are too large.", "INVALID_FILTERS");
    return JSON.parse(json);
  }

  function requestSummary(request) {
    if (!request || typeof request !== "object" || Array.isArray(request)) return null;
    const fields = ["key", "label", "method", "site", "channel", "outcome", "hasErrors", "rootMissing", "rootAmbiguous",
      "segmentId", "segmentKey", "traceId", "lensId", "user", "entryChannel", "status",
      "httpMethod", "path", "httpStatus", "startedAtMillis", "lastActivityMillis", "durationMs", "eventCount",
      "approxSizeBytes", "service", "applicationId", "applicationVersion", "serviceInstanceId", "spanId", "parentSpanId"];
    const summary = {};
    for (const field of fields) {
      const value = request[field];
      if (typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value === null) {
        summary[field] = value;
      }
    }
    summary.services = Array.isArray(request.services) ? request.services.filter((service) => typeof service === "string") : [];
    return summary;
  }

  function preparePin(input, now) {
    if (!input || typeof input !== "object" || Array.isArray(input)) fail("Invalid pinned snapshot.", "INVALID_DATA");
    const capturedAt = input.capturedAt == null ? (now == null ? Date.now() : now) : input.capturedAt;
    if (typeof capturedAt !== "number" || !Number.isFinite(capturedAt) || capturedAt < 0) {
      fail("Snapshot capture time must be a timestamp in milliseconds.", "INVALID_DATA");
    }
    if (input.segments != null && !Array.isArray(input.segments)) fail("Snapshot segments must be an array.", "INVALID_DATA");
    if (input.warnings != null && (!Array.isArray(input.warnings) || input.warnings.some((item) => typeof item !== "string"))) {
      fail("Snapshot warnings must be a list of messages.", "INVALID_DATA");
    }
    const pin = {
      id: identifier(input.id, "snapshot id", 4096), name: sanitizeName(input.name),
      traceId: input.traceId == null ? null : identifier(input.traceId, "trace id", 256),
      request: input.request == null ? null : input.request,
      journey: input.journey == null ? null : input.journey,
      segments: input.segments || [], capturedAt, partial: !!input.partial, warnings: input.warnings || [],
    };
    const json = serialize(pin, "Pinned snapshot");
    const sizeBytes = byteLength(json);
    if (sizeBytes > CONSTANTS.MAX_PIN_BYTES) {
      fail("This snapshot exceeds the 5 MiB browser pin limit. Existing pins were not changed.", "PIN_TOO_LARGE");
    }
    // Clone now, before async work, so caller mutations cannot change the saved record.
    const snapshot = JSON.parse(json);
    const summary = {
      id: snapshot.id, name: snapshot.name, traceId: snapshot.traceId,
      request: requestSummary(snapshot.request), capturedAt: snapshot.capturedAt,
      partial: snapshot.partial, warnings: snapshot.warnings.slice(),
      segmentCount: snapshot.segments.length, sizeBytes,
    };
    return { snapshot, json, summary };
  }

  function validatePinBudget(rows, prepared) {
    const others = rows.filter((row) => row.id !== prepared.summary.id);
    if (others.length >= CONSTANTS.MAX_PINS) {
      fail("This viewer already has 20 pinned snapshots. Unpin one before adding another.", "PIN_LIMIT");
    }
    const total = others.reduce((sum, row) => sum + (Number(row.sizeBytes) || 0), 0) + prepared.summary.sizeBytes;
    if (total > CONSTANTS.MAX_TOTAL_BYTES) {
      fail("Pinned snapshots would exceed this viewer’s 20 MiB limit. Unpin a snapshot first; existing pins were not changed.", "TOTAL_LIMIT");
    }
    return total;
  }

  function friendly(error) {
    if (error instanceof BrowserStoreError) return error;
    if (error && error.name === "QuotaExceededError") {
      return new BrowserStoreError("Browser storage is full. Free browser storage or unpin a snapshot, then retry. Existing saved items were not changed.", "QUOTA_EXCEEDED");
    }
    if (error && ["SecurityError", "InvalidStateError", "NotSupportedError"].includes(error.name)) {
      return new BrowserStoreError("Browser storage is unavailable. Allow site storage or use a browser profile that supports IndexedDB; nothing was saved.", "UNAVAILABLE");
    }
    return new BrowserStoreError("Could not complete the browser storage operation. Existing saved items were not changed. Please retry.", "STORAGE_ERROR");
  }

  function freshId() {
    if (root.crypto && typeof root.crypto.randomUUID === "function") return root.crypto.randomUUID();
    return "view-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
  }

  function create(namespace, options) {
    identifier(namespace, "viewer namespace", 4096);
    let databasePromise = null;

    function database() {
      if (databasePromise) return databasePromise;
      databasePromise = new Promise((resolve, reject) => {
        let indexed;
        try { indexed = options && options.indexedDB !== undefined ? options.indexedDB : root.indexedDB; }
        catch (error) { reject(friendly(error)); return; }
        if (!indexed || typeof indexed.open !== "function") {
          reject(new BrowserStoreError("Browser storage is unavailable. This browser does not provide IndexedDB; nothing was saved.", "UNAVAILABLE"));
          return;
        }
        let settled = false;
        let request;
        try { request = indexed.open(CONSTANTS.DB_NAME, CONSTANTS.DB_VERSION); }
        catch (error) { reject(friendly(error)); return; }
        request.onupgradeneeded = function () {
          const db = request.result;
          for (const name of ["views", "pins", "snapshots"]) {
            if (!db.objectStoreNames.contains(name)) {
              const store = db.createObjectStore(name, { keyPath: ["namespace", "id"] });
              store.createIndex("namespace", "namespace", { unique: false });
            }
          }
        };
        request.onerror = function () { settled = true; reject(friendly(request.error)); };
        request.onblocked = function () {
          settled = true;
          reject(new BrowserStoreError("Browser storage is waiting for another TraceLens tab. Close older tabs and reload this viewer.", "BLOCKED"));
        };
        request.onsuccess = function () {
          const db = request.result;
          if (settled) { db.close(); return; }
          settled = true;
          db.onversionchange = function () { db.close(); databasePromise = null; };
          resolve(db);
        };
      });
      return databasePromise;
    }

    async function transaction(stores, mode, operation) {
      const db = await database();
      return new Promise((resolve, reject) => {
        let tx, result, failure;
        try { tx = db.transaction(stores, mode); }
        catch (error) { reject(friendly(error)); return; }
        function abort(error) {
          failure = friendly(error);
          try { tx.abort(); } catch (ignored) { reject(failure); }
        }
        function guard(action) {
          return function (event) {
            try { action(event); } catch (error) { abort(error); }
          };
        }
        tx.oncomplete = function () { resolve(result); };
        tx.onabort = function () { reject(failure || friendly(tx.error)); };
        tx.onerror = function (event) {
          // Preserve the actual request error (not just the later AbortError).
          failure = failure || friendly(event.target.error || tx.error);
        };
        try { operation(tx, (value) => { result = value; }, guard); }
        catch (error) { abort(error); }
      });
    }

    function cleanRow(row) {
      if (!row) return null;
      const copy = Object.assign({}, row);
      delete copy.namespace;
      return copy;
    }

    function list(storeName, field) {
      return transaction([storeName], "readonly", (tx, result, guard) => {
        tx.objectStore(storeName).index("namespace").getAll(namespace).onsuccess = guard((event) => {
          const rows = event.target.result.map(cleanRow);
          // Older saved summaries may predate logical request display fields.
          // Normalize them without loading event payloads or mutating stored pins.
          if (storeName === "pins") for (const row of rows) row.request = requestSummary(row.request);
          result(rows.sort((a, b) => (b[field] || 0) - (a[field] || 0)));
        });
      });
    }

    function writePin(tx, prepared, result, guard) {
      const metadata = tx.objectStore("pins");
      metadata.index("namespace").getAll(namespace).onsuccess = guard((event) => {
        validatePinBudget(event.target.result, prepared);
        const now = Date.now();
        const old = event.target.result.find((row) => row.id === prepared.summary.id);
        const summary = Object.assign({}, prepared.summary, { savedAt: old ? old.savedAt : now, updatedAt: now });
        metadata.put(Object.assign({ namespace }, summary));
        tx.objectStore("snapshots").put({ namespace, id: prepared.snapshot.id, json: prepared.json });
        result(summary);
      });
    }

    return Object.freeze({
      listViews() { return list("views", "updatedAt"); },
      async saveView(name, filters) {
        const safeName = sanitizeName(name), safeFilters = validateFilters(filters);
        return transaction(["views"], "readwrite", (tx, result, guard) => {
          const store = tx.objectStore("views");
          store.index("namespace").getAll(namespace).onsuccess = guard((event) => {
            const rows = event.target.result, old = rows.find((row) => row.name === safeName);
            if (!old && rows.length >= CONSTANTS.MAX_VIEWS) fail("This viewer already has 20 saved views. Delete a view before adding another.", "VIEW_LIMIT");
            const now = Date.now();
            const view = { id: old ? old.id : freshId(), name: safeName, filters: safeFilters,
              savedAt: old ? old.savedAt : now, updatedAt: now };
            store.put(Object.assign({ namespace }, view));
            result(view);
          });
        });
      },
      async deleteView(id) {
        identifier(id, "view id", 4096);
        return transaction(["views"], "readwrite", (tx, result) => {
          tx.objectStore("views").delete([namespace, id]); result(true);
        });
      },
      listPins() { return list("pins", "updatedAt"); },
      async getPin(id) {
        identifier(id, "snapshot id", 4096);
        return transaction(["snapshots"], "readonly", (tx, result, guard) => {
          tx.objectStore("snapshots").get([namespace, id]).onsuccess = guard((event) => {
            result(event.target.result ? JSON.parse(event.target.result.json) : null);
          });
        });
      },
      async savePin(input) {
        const prepared = preparePin(input);
        return transaction(["pins", "snapshots"], "readwrite", (tx, result, guard) => writePin(tx, prepared, result, guard));
      },
      async renamePin(id, name) {
        identifier(id, "snapshot id", 4096);
        const safeName = sanitizeName(name);
        return transaction(["pins", "snapshots"], "readwrite", (tx, result, guard) => {
          tx.objectStore("snapshots").get([namespace, id]).onsuccess = guard((event) => {
            if (!event.target.result) fail("This pinned snapshot no longer exists. Refresh the saved list.", "NOT_FOUND");
            const pin = JSON.parse(event.target.result.json);
            pin.name = safeName;
            writePin(tx, preparePin(pin), result, guard);
          });
        });
      },
      async deletePin(id) {
        identifier(id, "snapshot id", 4096);
        return transaction(["pins", "snapshots"], "readwrite", (tx, result) => {
          tx.objectStore("pins").delete([namespace, id]);
          tx.objectStore("snapshots").delete([namespace, id]);
          result(true);
        });
      },
    });
  }

  return Object.freeze({ create, CONSTANTS, BrowserStoreError, sanitizeName, byteLength,
    validateFilters, preparePin, validatePinBudget, requestSummary });
});
