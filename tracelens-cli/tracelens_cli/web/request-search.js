/* Explicit, bounded search of captured request data. Polling never reads payloads. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensRequestSearch = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const LIMIT = 200;
  const countNames = ["requested", "scanned", "withData", "matched", "unavailable", "unsearched"];
  const emptyCounts = () => Object.fromEntries(countNames.map(name => [name, 0]));
  const object = value => value !== null && typeof value === "object" && !Array.isArray(value);
  const present = value => typeof value === "string" && value.trim().length > 0;
  function identity(segment) {
    if (!object(segment) || !["service", "serviceInstanceId", "segmentId"].every(name => present(segment[name]))) return null;
    if (segment.traceId != null && segment.traceId !== "" && !present(segment.traceId)) return null;
    const result = {service: segment.service, serviceInstanceId: segment.serviceInstanceId, segmentId: segment.segmentId};
    if (present(segment.traceId)) result.traceId = segment.traceId;
    return result;
  }
  const identityKey = item => JSON.stringify([item.service, item.serviceInstanceId, item.segmentId, item.traceId || ""]);
  function candidates(requests) {
    const unique = new Map(), owners = new Map(), skipped = new Set();
    for (const request of Array.isArray(requests) ? requests : []) {
      if (!object(request) || !present(request.key) || !Array.isArray(request.segments)) continue;
      for (const [index, segment] of request.segments.entries()) {
        const item = identity(segment);
        if (!item) { skipped.add(JSON.stringify([request.key, index])); continue; }
        const key = identityKey(item);
        if (!unique.has(key)) unique.set(key, item);
        if (!owners.has(key)) owners.set(key, new Set());
        owners.get(key).add(request.key);
      }
    }
    return {unique, owners, skipped: skipped.size,
      signature: JSON.stringify([...unique.keys(), ...[...skipped].map(key => `missing:${key}`)].sort())};
  }
  function validate(response, submitted) {
    if (!object(response) || response.schemaVersion !== 1 || response.kind !== "request-data-search"
        || !Array.isArray(response.matches) || response.matches.length > submitted.size
        || !object(response.counts) || typeof response.limited !== "boolean") throw Error("Invalid search response");
    const counts = {};
    for (const name of countNames) {
      const value = response.counts[name];
      if (!Number.isInteger(value) || value < 0 || value > submitted.size) throw Error("Invalid search count");
      counts[name] = value;
    }
    if (counts.requested !== submitted.size || counts.matched !== response.matches.length
        || counts.withData > counts.scanned || counts.matched > counts.withData
        || counts.requested !== counts.scanned + counts.unavailable + counts.unsearched) throw Error("Inconsistent search count");
    const matches = new Set();
    for (const match of response.matches) {
      const item = identity(match), key = item && identityKey(item);
      if (!key || !submitted.has(key) || matches.has(key) || !Array.isArray(match.fields)
          || !match.fields.length || match.fields.length > 2
          || new Set(match.fields).size !== match.fields.length
          || !match.fields.every(field => field === "query" || field === "body")) throw Error("Invalid search match");
      matches.add(key);
    }
    return {matches, counts, limited: response.limited};
  }
  function create(api) {
    let generation = 0, pending = null, matchedSegments = new Set(), matchingOwners = new Map();
    let current = {status: "idle", keyword: "", counts: emptyCounts(), skipped: 0, limited: false, error: "", requestKeys: new Set(), signature: ""};
    const snapshot = () => ({...current, counts: {...current.counts}, requestKeys: new Set(current.requestKeys)});
    function reset() {
      generation++; pending = null; matchedSegments = new Set(); matchingOwners = new Map();
      current = {status: "idle", keyword: "", counts: emptyCounts(), skipped: 0, limited: false, error: "", requestKeys: new Set(), signature: ""};
      return snapshot();
    }
    function search(keyword, requests) {
      if (typeof keyword === "string" && !keyword.trim()) { reset(); return Promise.resolve(snapshot()); }
      if (pending) return pending;
      if (typeof keyword !== "string" || keyword.trim().length > 256) {
        reset(); current.status = "error"; current.error = "Enter a keyword of 256 characters or fewer.";
        return Promise.resolve(snapshot());
      }
      const normalized = keyword.trim(), found = candidates(requests), submitted = new Map([...found.unique].slice(0, LIMIT));
      const omitted = found.unique.size - submitted.size, turn = ++generation;
      matchedSegments = new Set(); matchingOwners = new Map();
      current = {status: "loading", keyword: normalized, counts: {...emptyCounts(), requested: found.unique.size, unsearched: omitted}, skipped: found.skipped,
        limited: omitted > 0, error: "", requestKeys: new Set(), signature: found.signature};
      if (!submitted.size) { current.status = "ready"; return Promise.resolve(snapshot()); }
      const task = (async () => {
        try {
          const response = await Promise.resolve().then(() => turn === generation ? api("/api/request-search", "POST", undefined,
            {keyword: normalized, segments: [...submitted.values()]}) : null);
          if (turn !== generation) return snapshot();
          const result = validate(response, submitted), keys = new Set();
          matchedSegments = result.matches;
          for (const key of matchedSegments) {
            matchingOwners.set(key, found.owners.get(key));
            for (const owner of found.owners.get(key)) keys.add(owner);
          }
          current = {...current, status: "ready", requestKeys: keys, limited: result.limited || omitted > 0,
            counts: {...result.counts, requested: found.unique.size, unsearched: result.counts.unsearched + omitted}};
        } catch (_) {
          if (turn !== generation) return snapshot();
          matchedSegments = new Set(); matchingOwners = new Map();
          current = {...current, status: "error", requestKeys: new Set(), error: "Request-data search could not be completed. Try again."};
        } finally { if (turn === generation) pending = null; }
        return snapshot();
      })();
      pending = task;
      return task;
    }
    function filter(requests) {
      const rows = Array.isArray(requests) ? requests : [];
      if (current.status === "idle") return rows;
      if (current.status !== "ready") return [];
      return rows.filter(request => object(request) && current.requestKeys.has(request.key)
        && Array.isArray(request.segments) && request.segments.some(segment => {
          const item = identity(segment), key = item && identityKey(item);
          return key && matchedSegments.has(key) && matchingOwners.get(key)?.has(request.key);
        }));
    }
    return Object.freeze({search, reset, filter,
      isStale: requests => current.status !== "idle" && candidates(requests).signature !== current.signature,
      get state() { return snapshot(); }});
  }
  return Object.freeze({create});
});
