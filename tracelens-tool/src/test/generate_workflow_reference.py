"""Regenerate deterministic Java parity fixtures from the retained Python implementation.

Development/test generation only: no network, real captures, or running services.
Run from the repository root: python3 tracelens-tool/src/test/generate_workflow_reference.py
The Java test suite reads the committed JSON and never requires Python.
"""
import copy
import json
import unicodedata
from pathlib import Path
import sys
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tracelens-cli"))
from tracelens_cli import captures, capture_tools, investigation, guided_investigation
from tracelens_cli.services import join_trace

NOW = 2_000_000_000_000
TRACE = "a" * 32
SEGMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
SNAPSHOT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
cases = []


def add(kind, name, inputs, callback):
    case = dict(kind=kind, name=name, inputs=inputs)
    try:
        case["expected"] = callback()
    except ValueError as exc:
        case["error"] = str(exc)
    cases.append(case)


def row(**changes):
    value = dict(service="app", traceId=TRACE, serviceInstanceId="instance-1",
                 segmentId=SEGMENT, parentSpanId=None, spanId="b" * 16,
                 httpMethod="GET", path="/api/features", httpStatus=200,
                 status="COMPLETED", durationMs=12, startedAtMillis=NOW - 1000,
                 lastActivityMillis=NOW, entryChannel="HTTP", user="Alice",
                 lensId="scenario:upgrade", events=[], eventCount=0, httpCalls=[])
    value.update(changes)
    value["segmentKey"] = json.dumps([value["service"], value["serviceInstanceId"], value["segmentId"]], separators=(",", ":"))
    return value


def events(*specs):
    result = []
    for i, spec in enumerate(specs, 1):
        kind, cls, method, extra = (*spec, {}) if len(spec) == 3 else spec
        result.append(dict(seq=i, type=kind, className=cls, methodName=method,
                           thread="PRIVATE_THREAD", nanoTime=i * 1_000_000, **extra))
    return result


base_events = events(
    ("METHOD_ENTER", "example.Root", "run"),
    ("METHOD_ENTER", "example.ZParent", "child"),
    ("LINE", "example.ZParent", None, {"line": 42}),
    ("METHOD_ENTER", "example.ALeaf", "leaf"),
    ("METHOD_EXIT", "example.ALeaf", "leaf"),
    ("METHOD_EXIT", "example.ZParent", "child"),
    ("METHOD_ENTER", "example.AParent", "child"),
    ("METHOD_ENTER", "example.ZLeaf", "leaf"),
    ("EXCEPTION", "example.ZLeaf", "leaf",
     {"attributes": {"exceptionType": "example.LookupException", "message": "SECRET"}}),
    ("METHOD_EXIT", "example.ZLeaf", "leaf"),
    ("METHOD_EXIT", "example.AParent", "child"),
    ("METHOD_EXIT", "example.Root", "run"),
)
base = row(events=base_events, eventCount=len(base_events))
projections = [
    ("nested-and-caught-exception", base, 100000),
    ("event-budget", base, 5),
    ("no-event-budget", base, 0),
    ("missing-events", {k: v for k, v in base.items() if k != "events"}, 100000),
    ("active-truncated", dict(base, status="ACTIVE", truncated=True, httpCallsTruncated=True), 100000),
    ("invalid-parent", dict(base, parentSegmentId="bad", parentSpanId="bad"), 100000),
    ("attribute-request-fallback", dict(base, httpMethod="", path="", httpStatus=None,
                                      attributes=dict(httpMethod="POST", path="/created?secret=x", httpStatus="201")), 100000),
    ("malformed-events", row(events=[None, {"seq": 3, "type": "LINE", "className": "bad/name", "line": -1},
                                     {"seq": 2, "type": "EXCEPTION", "attributes": {"exceptionType": []}}],
                             eventCount=3), 100000),
    ("malformed-http-calls", dict(base, httpCalls={}), 100000),
    ("huge-duration", dict(base, durationMs=10**400), 100000),
]
for system, boundary, operation in [("redis", "cache", "hget"),
                                   ("clickhouse", "database", "executeQuery"),
                                   ("sqs", "messaging", "sendMessage"),
                                   ("dynamodb", "database", "getItem"),
                                   ("s3", "external", "getObject")]:
    ev = events(("EXTERNAL_CALL", "example.Client", operation,
                 {"attributes": dict(boundaryKind=boundary, system=system, direction="outbound",
                                     operation=operation, status="OBSERVED", evidenceKind="client-boundary",
                                     sql="SECRET", payload="SECRET", key="SECRET")}))
    projections.append((system + "-destinationless", row(events=ev, eventCount=1), 100000))
db_events = events(("DB_ROUTE", "example.Router", "route", {"attributes": {"route": "TEST_MASTER"}}))
projections.append(("db-route-and-http", row(events=db_events, eventCount=1, httpCalls=[
    dict(peer="https://user:SECRET@ems.example:8082/path?token=SECRET", httpMethod="POST",
         path="/features?secret=x", httpStatus="201", status="COMPLETED"),
    dict(peer="bad value", httpMethod="GET", path="/features", httpStatus=503,
         status="ERROR", errorType="example.CallException")]), 100000))
for name, raw, budget in projections:
    add("projection", name, dict(row=raw, budget=budget),
        lambda raw=raw, budget=budget: captures._project_segment("app", raw, budget))


def snapshot(raw=base, **changes):
    value = dict(schemaVersion=1, storage="viewer", id=SNAPSHOT, name="Before",
                 traceId=raw["traceId"], capturedAt=1234567, savedAt=1235000,
                 partial=False, warnings=[],
                 request=dict(traceId=raw["traceId"], label="GET /api/features", services=["app"]),
                 journey=dict(traceId=raw["traceId"], segments=[
                     {key: raw[key] for key in ("service", "traceId", "serviceInstanceId", "segmentId")}]),
                 segments=[copy.deepcopy(raw)])
    value.update(changes)
    return value


for name, snap, budget in [
    ("normal", snapshot(), 100000),
    ("partial", snapshot(partial=True, warnings=["SECRET_WARNING"]), 100000),
    ("limited", snapshot(), 1),
    ("whole-second", snapshot(capturedAt=1000), 100000),
    ("fractional-millis", snapshot(capturedAt=1000.123), 100000),
    ("long-name", snapshot(name="é" * 100), 100000),
    ("unsupported-service", snapshot(row(service="bad/name")), 100000),
    ("unsupported-legacy-id", snapshot(row(segmentId="legacy-id")), 100000),
]:
    add("snapshot", name, dict(snapshot=snap, budget=budget),
        lambda snap=snap, budget=budget: capture_tools.project_viewer_snapshot(snap, max_events=budget))

before = capture_tools.project_viewer_snapshot(snapshot())
after_raw = copy.deepcopy(base)
after_raw["events"][3]["methodName"] = after_raw["events"][4]["methodName"] = "replacement"
after_raw.update(httpStatus=500, durationMs=88, path="/api/other")
after = capture_tools.project_viewer_snapshot(snapshot(after_raw, id="cccccccc-cccc-cccc-cccc-cccccccccccc", name="After"))
for name, first, second in [("same", before, before), ("changed", before, after)]:
    add("comparison", name, dict(before=first, after=second),
        lambda first=first, second=second: captures.compare_captures(first, second))
large = copy.deepcopy(after)
for category, fields in [("methods", ("className", "methodName")),
                         ("nesting", ("parentClass", "parentMethod", "className", "methodName"))]:
    large["segments"][0][category] = [
        dict(**{key: "example." + ("LargeName" * 20) + str(i) for key in fields}, count=1)
        for i in range(500)]
add("comparison", "byte-budget-pruning", dict(before=before, after=large),
    lambda: captures.compare_captures(before, large))


class Discovery:
    def __init__(self, rows, flags=None, journey=None):
        self.rows = rows
        self.flags = flags or {}
        self.services = dict(app=None, ems=None)
        self.journey = journey
        self.journeys = {}
        self.reads = []

    def traces(self, **filters):
        self.reads.append("traces")
        return dict(traces=copy.deepcopy(self.rows), **self.flags)

    def get_distributed_trace(self, trace_id):
        self.reads.append("journey:" + trace_id)
        value = copy.deepcopy(self.journey if self.journey is not None else
                              join_trace(trace_id, [r for r in self.rows if r.get("traceId") == trace_id], []))
        self.journeys[trace_id] = value
        return copy.deepcopy(value)


def investigate(name, rows, filters=None, flags=None, journey=None):
    filters = dict(path="features") if filters is None else filters
    discovery = Discovery(rows, flags, journey)
    inputs = dict(rows=rows, filters=filters, flags=flags or {}, now=NOW)
    with patch.object(investigation.time, "time", return_value=NOW / 1000):
        add("investigation", name, inputs, lambda: investigation.investigate_request(discovery, **filters))
    inputs.update(journeys=discovery.journeys, reads=discovery.reads)


investigate("unique", [base])
investigate("cross-service-failure", [base, row(segmentId="child", service="ems", parentSpanId="b" * 16, httpStatus=503)])
multi = [base, row(segmentId="new", traceId="b" * 32, startedAtMillis=NOW - 500)]
investigate("ambiguous", multi)
investigate("latest", multi, dict(path="features", selection="latest"))
investigate("latest-tie", [base, row(segmentId="two", traceId="b" * 32)], dict(path="features", selection="latest"))
for flag in ("limited", "partial", "errors", "warnings"):
    investigate(flag, [base], flags={flag: ["SECRET"] if flag in ("errors", "warnings") else True})
investigate("exact-id-no-discovery", [row(traceId="Legacy-A")], dict(trace_id="Legacy-A"))
investigate("exact-id-filters", [base], dict(trace_id=TRACE, path="other"))
investigate("same-segment-filter", [base, row(segmentId="child", service="ems", user="Bob", path="/other")],
            dict(service="ems", path="features", user="Alice"))
investigate("filter-combination", [base, row(segmentId="old", traceId="b" * 32, startedAtMillis=NOW - 700000)],
            dict(path="FEATURES", user="ALICE", label="UPGRADE", method="get", service="app"))
investigate("unicode-casefold", [row(user="Straße", lensId="scenario:ﬃ", path="/api/ΟΣ")],
            dict(path="οσ", user="STRASSE", label="FFI"))
investigate("missing-root", [row(parentSpanId="b" * 16, hasExceptions=True)], dict(path="features", outcome="unknown"))
investigate("identical-duplicates", [base, copy.deepcopy(base)])
investigate("conflicting-identity", [base, dict(base, httpStatus=500)])
investigate("expired-selection", [base], journey={"segments": []})
investigate("changed-selection", [base], journey=join_trace(TRACE, [row(path="/changed")], []))
investigate("http-failure", [row(httpCalls=[dict(spanId="outbound", httpStatus=502, status="COMPLETED", httpMethod="GET", path="/dependency")])])
hidden = [row(segmentId=str(i), parentSpanId="b" * 16 if i else None, hasExceptions=True) for i in range(21)]
hidden[0]["httpCalls"] = [dict(spanId="outbound", httpStatus=500)]
investigate("failure-beyond-display-limit", hidden)
investigate("candidate-limit", [row(segmentId=str(i), traceId=f"trace-{i}") for i in range(25)])
for outcome, expected in [("SUCCEEDED", "success"), ("SKIPPED", "skipped"), ("FAILED", "error"),
                          ("RUNNING", "active"), ("UNKNOWN", "unknown"), (None, "unknown")]:
    investigate("scheduler-" + str(outcome), [row(entryChannel="SCHEDULER", httpStatus=None, path="", httpMethod="",
                jobOutcome=outcome, exceptionCount=1)], dict(label="upgrade", outcome=expected))
for i, filters in enumerate([{}, dict(method="GET"), dict(path="features", since_minutes=True),
                             dict(path="features", service="unknown"), dict(trace_id="../bad"),
                             dict(path="features", outcome=[]), dict(path="features", selection="first")]):
    investigate("invalid-" + str(i), [base], filters)


class Client:
    def __init__(self, docs):
        self.docs = docs
    def summary(self):
        return copy.deepcopy(self.docs["summary"])
    def metadata(self):
        return copy.deepcopy(self.docs["metadata"])
    def traces(self):
        return copy.deepcopy(self.docs["traces"])


def diagnosis(name, docs=None, filters=None):
    docs = copy.deepcopy(docs or dict(
        app=dict(summary=dict(agentActive=True, recordingEnabled=True, traceCount=1,
                              maxTrackedRequests=100, httpIncludePaths=["/api"]),
                 metadata=dict(agentActive=True, recordingEnabled=True, requireLabels=False,
                               packages=["example"], idleTtlMinutes=10, httpIncludePaths=["/api"]),
                 traces=dict(traces=[base]))))
    filters = dict(path="/api/features") if filters is None else filters
    discovery = Discovery([])
    discovery.services = {name: None for name in docs}
    discovery.clients = {name: Client(doc) for name, doc in docs.items()}
    add("diagnosis", name, dict(docs=docs, filters=filters),
        lambda: guided_investigation.diagnose_request(discovery, **filters))
    return docs


docs = diagnosis("included")
for path in ("/api", "/apis/features", "/other", "/api/features/child"):
    diagnosis("prefix-" + path, docs, dict(path=path))
for name, changes in [
    ("recording-disabled", {"recordingEnabled": False}),
    ("require-labels", {"requireLabels": True}),
    ("empty-packages", {"packages": []}),
    ("empty-paths", {"httpIncludePaths": []}),
    ("malformed-paths", {"httpIncludePaths": ["/api/*"]}),
    ("unknown-settings", {"agentActive": "true", "recordingEnabled": 1, "requireLabels": None}),
]:
    altered = copy.deepcopy(docs)
    altered["app"]["metadata"].update(changes)
    diagnosis(name, altered)
for state in ("partial", "limited", "errors"):
    altered = copy.deepcopy(docs)
    altered["app"]["traces"][state] = ["SECRET"] if state == "errors" else True
    diagnosis("summaries-" + state, altered)
altered = copy.deepcopy(docs)
altered["app"]["summary"] = {}
altered["app"]["metadata"] = {}
altered["app"]["traces"] = {}
diagnosis("unavailable-documents", altered)
diagnosis("user-exact", docs, dict(path="/api/features", user="alice"))
diagnosis("method-case-normalized", docs, dict(path="/api/features", method="get", user="Alice"))
for i, filters in enumerate([dict(path="/api/features?token=SECRET"), dict(path="/api/%00"),
                            dict(path="//host/path"), dict(path="https://host/path"), dict(path="/api/*"),
                            dict(path="/api/features", method="GET POST"), dict(path="/api/features", site=[])]):
    diagnosis("invalid-" + str(i), docs, filters)


class MemoryStore:
    def __init__(self, records, viewer):
        self.records, self.viewer = records, viewer
    def list(self):
        return {("snapshots" if self.viewer else "captures"): [
            {("id" if self.viewer else "captureId"): r["id" if self.viewer else "captureId"]} for r in self.records]}
    def get(self, identifier):
        return copy.deepcopy(next(r for r in self.records if r["id" if self.viewer else "captureId"] == identifier))


search_pins = [snapshot()]
search_caps = [before]
for name, query, category, limit in [
    ("methods", "child", "method", 50), ("classes", "Parent", "class", 50),
    ("exception", "lookupexception", "exception", 50), ("literal", ".*", None, 50),
    ("limited", "example", None, 2), ("secret-free", "SECRET", None, 50),
]:
    add("search", name, dict(snapshots=search_pins, captures=search_caps, query=query, category=category, limit=limit),
        lambda query=query, category=category, limit=limit: capture_tools.search_saved_evidence(
            MemoryStore(search_pins, True), MemoryStore(search_caps, False), query, category, limit))
for name, raw, _ in projections:
    if "destinationless" not in name:
        continue
    pin = snapshot(raw)
    cap = capture_tools.project_viewer_snapshot(pin)
    system = name.split("-")[0]
    add("search", name, dict(snapshots=[pin], captures=[cap], query=system.upper(), category="destination", limit=50),
        lambda pin=pin, cap=cap, system=system: capture_tools.search_saved_evidence(
            MemoryStore([pin], True), MemoryStore([cap], False), system.upper(), "destination"))

target = ROOT / "tracelens-tool/src/test/resources/workflows-python-reference.json"
target.write_text(json.dumps(dict(generatedBy=__file__.split("cb-tracelens/")[-1], cases=cases),
                            ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
folds = {str(code): chr(code).casefold() for code in range(0x110000)
         if chr(code).casefold() != chr(code).lower()}
fold_target = ROOT / "tracelens-tool/src/main/resources/unicode-casefold.json"
fold_target.write_text(json.dumps(dict(unicodeVersion=unicodedata.unidata_version, mappings=folds),
                                 ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(f"Wrote {len(cases)} offline Python reference cases to {target}")
