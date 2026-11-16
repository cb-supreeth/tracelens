"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const M = require("../tracelens_cli/web/request-model.js");

const NOW = 2_000_000_000_000;
const TRACE = "1".repeat(32);
const row = (extra = {}) => ({traceId: TRACE, segmentId: "root", service: "cb-app",
  serviceInstanceId: "app-1", entryChannel: "HTTP", httpMethod: "GET", path: "/api/features",
  status: "COMPLETED", httpStatus: 200, startedAtMillis: NOW - 1000,
  durationMs: 40, user: "alice", siteId: "42", ...extra});
const child = (extra = {}) => row({segmentId: "child", service: "cb-ems", serviceInstanceId: "ems-1",
  parentSegmentId: "root", path: "/features", httpStatus: 503, durationMs: 10, ...extra});
const grouped = (...rows) => M.groupTraces(rows);
const match = (requests, filters = {}, errors = {}) => M.filterErrorRequests(requests,
  {...M.defaults(), ...filters}, {category: "", httpStatus: "", exceptionClass: "", ...errors}, NOW);
const freeze = value => {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value); Object.values(value).forEach(freeze);
  }
  return value;
};

test("Errors counts unique linked requests, not failing segments or overlapping signatures", () => {
  const requests = grouped(row(), child(), child({segmentId: "other", service: "ledger", httpStatus: 500}),
    row({traceId: "2".repeat(32), httpStatus: 404}), row({traceId: "3".repeat(32)}));
  const result = match(requests);
  assert.equal(result.length, 2);
  const linked = result.find(request => request.traceId === TRACE);
  assert.equal(linked.httpStatus, 200, "A successful root does not erase an observed downstream failure");
  assert.equal(linked.segments.length, 3, "Matching must preserve successful sibling segments");
  assert.ok(requests.includes(linked), "Filtering returns the original request, not a synthesized group");
});

test("service, method, path and channel must match the same failing segment", () => {
  const requests = grouped(row({httpMethod: "POST", path: "/entry"}),
    child({routeTemplate: "/features/{id}"}));
  for (const filters of [{service: "cb-app"}, {service: "cb-ems", method: "POST"},
    {service: "cb-ems", path: "/entry"}, {service: "cb-ems", channel: "SQS"}]) {
    assert.equal(match(requests, filters).length, 0, JSON.stringify(filters));
  }
  assert.equal(match(requests, {service: "cb-ems", method: "get", path: "/FEATURES/{id}", channel: "http"}).length, 1);
});

test("failure category, exact status and boundary exception filters share one failure", () => {
  const requests = grouped(row(), child({filterException: "java.net.SocketTimeoutException"}),
    row({traceId: "2".repeat(32), httpStatus: 404}));
  assert.equal(match(requests, {}, {category: "http5xx", httpStatus: "503", exceptionClass: "socketTIMEOUT"}).length, 1);
  assert.equal(match(requests, {}, {category: "http4xx", httpStatus: "503"}).length, 0);
  assert.equal(match(requests, {}, {httpStatus: "500"}).length, 0);
  assert.equal(match(requests, {}, {exceptionClass: "MissingException"}).length, 0);
  for (const invalid of ["503.5", "-1", "600", "invalid"]) {
    assert.equal(match(requests, {}, {httpStatus: invalid}).length, 0, invalid);
  }
});

test("explicit timeouts precede HTTP status while failed job outcomes count as execution failures", () => {
  const timeout = grouped(row({httpStatus: 504, asyncTimeout: true}));
  assert.equal(match(timeout, {}, {category: "timeout", httpStatus: "504"}).length, 1);
  assert.equal(match(timeout, {}, {category: "http5xx"}).length, 0);
  const jobs = grouped(row({entryChannel: "SCHEDULER", httpStatus: null, httpMethod: "", path: "",
    jobOutcome: "FAILED", jobFailureType: "com.chargebee.JobFailure"}));
  assert.equal(match(jobs, {channel: "SCHEDULER"}, {category: "execution", exceptionClass: "JobFailure"}).length, 1);
});

test("time and duration filters use the root request, not the selected downstream failure", () => {
  const requests = grouped(row({startedAtMillis: NOW - 31 * 60000, durationMs: 80}),
    child({startedAtMillis: NOW - 100, durationMs: 5}));
  assert.equal(match(requests).length, 0);
  assert.equal(match(requests, {sinceMinutes: "", minDuration: "70", maxDuration: "90"}).length, 1);
  assert.equal(match(requests, {sinceMinutes: "", maxDuration: "10"}).length, 0);
  const missingRoot = grouped(child());
  assert.equal(match(missingRoot, {minDuration: "1"}).length, 0, "Unknown root duration cannot meet a bound");
  assert.equal(match(missingRoot).length, 1, "Missing root is not itself a reason to hide an observed failure");
});

test("user and site may match a successful sibling but must both match that same sibling", () => {
  const requests = grouped(row({user: "Root-User", siteId: "42"}), child({user: "child-user", siteId: "43"}));
  assert.equal(match(requests, {service: "cb-ems", user: "ROOT-user", site: "42"}).length, 1);
  assert.equal(match(requests, {service: "cb-ems", user: "Root-User", site: "43"}).length, 0);
  assert.equal(match(requests, {service: "cb-ems", user: "child", site: "43"}).length, 1);
});

test("HTTP failure inside a scheduled run matches HTTP Errors even though the root is a job", () => {
  const requests = grouped(row({entryChannel: "SCHEDULER", httpStatus: null, path: "", httpMethod: "",
    jobOutcome: "SUCCEEDED", lensId: "Nightly cleanup"}), child());
  assert.equal(requests[0].channel, "SCHEDULER");
  assert.equal(match(requests, {channel: "HTTP"}).length, 1);
  assert.equal(match(requests, {channel: "SCHEDULER"}).length, 0);
  assert.equal(match(requests, {channel: ""}).length, 1);
});

test("recovered exceptions, exception messages and unavailable services do not invent failures", () => {
  const recovered = grouped(row({exceptionCount: 4, filterException: "java.net.SocketTimeoutException"}));
  assert.equal(match(recovered).length, 0);
  assert.equal(match(recovered, {}, {exceptionClass: "SocketTimeout"}).length, 0);
  const rawMessage = grouped(row({httpStatus: 500, filterException: "private message containing TokenException"}));
  assert.equal(match(rawMessage).length, 1);
  assert.equal(match(rawMessage, {}, {exceptionClass: "TokenException"}).length, 0);
  const marker = grouped(row({httpStatus: 500, filterException: "true"}));
  assert.equal(match(marker, {}, {exceptionClass: "true"}).length, 0);
  assert.equal(match([]).length, 0);
});

test("Errors ignores Live-only outcome/collapse and uses literal request-wide text search", () => {
  const requests = grouped(row({lensId: "case [abc]"}), child());
  assert.equal(match(requests, {outcome: "success", collapse: false, query: "[abc]"}).length, 1);
  assert.equal(match(requests, {query: "CB-EMS"}).length, 1);
  assert.equal(match(requests, {query: "case.*"}).length, 0);
  assert.equal(match(requests, {minDuration: "90", maxDuration: "10"}).length, 0);
});

test("duplicate failures remain one logical request while unidentified observations remain separate", () => {
  const duplicate = child();
  const requests = grouped(row(), duplicate, {...duplicate});
  assert.equal(match(requests).length, 1);
  const unidentified = grouped(row({traceId: "", segmentId: "", httpStatus: 500}), row({traceId: "", segmentId: "", httpStatus: 500}));
  assert.equal(unidentified.length, 2);
  assert.equal(match(unidentified).length, 2, "Without a valid trace identity, matching must not infer shared causality");
});

test("filtering and repeated polling are pure and retain request objects", () => {
  const requests = freeze(grouped(row(), child()));
  const filters = freeze({...M.defaults(), service: "cb-ems", outcome: "success"});
  const errors = freeze({category: "http5xx", httpStatus: "503", exceptionClass: ""});
  const before = JSON.stringify([requests, filters, errors]);
  for (let i = 0; i < 3; i++) {
    const result = M.filterErrorRequests(requests, filters, errors, NOW);
    assert.equal(result.length, 1); assert.equal(result[0], requests[0]);
  }
  assert.equal(JSON.stringify([requests, filters, errors]), before);
  assert.equal(M.filterErrorRequests(requests, filters, errors, NOW + 31 * 60000).length, 0);
});
