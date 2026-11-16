"""On-demand, offline tools over explicit saved evidence.

Viewer pins deliberately retain full exports; CLI captures deliberately do not.
Conversion is explicit and uses the capture module's allowlisted projection,
never a live discovery client or a filesystem path supplied by a request.
"""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import uuid

from . import captures
from .viewer_snapshots import MAX_BYTES, validate_snapshot


MAX_RESULTS = 100
MAX_SEARCH_BYTES = 128 * 1024
MAX_SEARCH_RECORDS = 40
MAX_SEARCH_EVENTS = 200_000
SEARCH_CATEGORIES = ("class", "method", "exception", "destination")
SEARCH_LIMITATIONS = [
    "Only explicitly saved viewer snapshots and CLI captures are searched; no live service or source tree is read.",
    "A match is retained observed evidence, not coverage, a complete call graph, or proof that a change is safe.",
    "Results omit raw source, payloads, headers, user/context fields and exception messages. Code identifiers, paths, destinations and developer labels can still be sensitive.",
    "Duration is a local recorded sample including waits and instrumentation, not a performance benchmark.",
]


def project_viewer_snapshot(snapshot, label=None, revision=None, *, max_events=None):
    """Return a current Capture from a saved viewer document without writing.

    The pin ID and original capture time identify this ephemeral projection.
    Export assigns a new capture ID when publishing to the separate CLI store.
    Unsupported identities are rejected instead of being fabricated or refetched.
    """
    validate_snapshot(snapshot)
    if len(captures._bytes(snapshot)) > MAX_BYTES:
        raise ValueError("Viewer snapshot exceeds the projection byte limit.")
    name = snapshot["name"][:80] if label is None else label
    captures.validate_capture_inputs(snapshot["traceId"], name, revision)
    if max_events is None:
        max_events = captures.MAX_EVENTS
    if type(max_events) is not int or not 0 <= max_events <= captures.MAX_EVENTS:
        raise ValueError("Invalid projection event budget.")
    try:
        created = datetime.fromtimestamp(snapshot["capturedAt"] / 1000, timezone.utc).isoformat()
    except (ValueError, OverflowError, OSError):
        raise ValueError("Snapshot capture time cannot be represented in a CLI capture.") from None
    result = {"schemaVersion": 1, "kind": "request-capture", "captureId": snapshot["id"],
              "label": name, "revision": revision, "traceId": snapshot["traceId"],
              "createdAt": created, "segments": [], "warnings": [], "partial": False,
              "limitations": list(captures.LIMITATIONS)}
    warnings = set()
    if snapshot["partial"]:
        warnings.add("viewer_snapshot_partial")
    if snapshot["warnings"]:
        # Full pin warnings can contain upstream errors and private context.
        warnings.add("viewer_snapshot_warnings_present")
    if len(snapshot["journey"]["segments"]) != len(snapshot["segments"]):
        warnings.add("viewer_snapshot_missing_exports")
    if len(snapshot["segments"]) > captures.MAX_SEGMENTS:
        warnings.add("segment_limit")
    remaining = max_events
    for row in snapshot["segments"][:captures.MAX_SEGMENTS]:
        service = row.get("service") or row.get("applicationId")
        if (not captures._matches(service, captures._SERVICE)
                or not captures._matches(row.get("segmentId"), captures._UUID)
                or not captures._matches(row.get("serviceInstanceId"), captures._ID)):
            raise ValueError("Saved segment identities are unsupported by the CLI capture format; no live data was substituted.")
        segment = captures._project_segment(service, row, remaining)
        remaining -= segment["eventsProcessed"]
        result["segments"].append(segment)
        if len(captures._bytes(result)) > captures.MAX_CAPTURE_BYTES - 16384:
            result["segments"].pop()
            warnings.add("capture_byte_limit")
            break
    if not result["segments"]:
        raise ValueError("No saved segment evidence fits the CLI capture format.")
    roots = [row for row in result["segments"]
             if row["parentSpanId"] is None and row["parentSegmentId"] is None]
    if len(roots) != 1:
        warnings.add("root_missing_or_ambiguous")
    result["warnings"] = sorted(warnings)
    result["partial"] = bool(warnings) or any(row["partial"] for row in result["segments"])
    captures._validate_capture(result)
    return result


def compare_saved_snapshots(snapshot_store, before, after):
    """Compare exactly two IDs in this viewer's store, without a live fallback."""
    first = project_viewer_snapshot(snapshot_store.get(before))
    second = project_viewer_snapshot(snapshot_store.get(after))
    result = captures.compare_captures(first, second)
    result["sourceSnapshots"] = {
        "before": {"snapshotId": before, "name": first["label"]},
        "after": {"snapshotId": after, "name": second["label"]},
    }
    result["projection"] = "Saved exports only; source and raw runtime context are excluded."
    # Keep the comparator's advertised byte bound after adding pin references.
    while len(captures._bytes(result)) > captures.MAX_COMPARISON_BYTES:
        available = [(len(result[category][kind]), category, kind)
                     for category in ("methods", "lines", "nesting", "dependencies", "exceptions", "outcomes")
                     for kind in ("added", "noLongerObserved", "countChanged")
                     if result[category][kind]]
        if not available:
            raise ValueError("Comparison metadata exceeds the response byte limit.")
        _, category, kind = max(available)
        result[category][kind].pop()
        result[category]["returned"] -= 1
        result[category]["omitted"] += 1
        result["limited"] = True
    return result


def export_to_capture_store(snapshot_store, capture_store, snapshot_id, label=None, revision=None):
    """Explicitly publish one minimized copy; neither store's evidence changes."""
    # Reject explicit invalid labels before touching either store.
    if label is not None or revision is not None:
        captures.validate_capture_inputs("validation", "snapshot" if label is None else label, revision)
    capture = project_viewer_snapshot(snapshot_store.get(snapshot_id), label, revision)
    capture["captureId"] = str(uuid.uuid4())
    metadata = capture_store.save(capture)
    return {"schemaVersion": 1, "status": "saved", "saved": True,
            "sourceSnapshotId": snapshot_id, "capture": metadata,
            "warnings": capture["warnings"],
            "segmentWarnings": [{"service": row["service"], "segmentId": row["segmentId"],
                                 "warnings": row["warnings"]}
                                for row in capture["segments"] if row["warnings"]],
            "limitations": list(captures.LIMITATIONS)}


def _identities(segment, category):
    """Yield only allowlisted counters, never entire exports or source records."""
    if category == "class":
        classes = Counter()
        for row in segment["methods"]:
            classes[row["className"]] += row["count"]
        for name, count in sorted(classes.items()):
            yield {"className": name, "count": count, "evidenceKind": "method-entry"}
        lines = Counter()
        for row in segment["lines"]:
            if row["className"] not in classes:
                lines[row["className"]] += row["count"]
        for name, count in sorted(lines.items()):
            yield {"className": name, "count": count, "evidenceKind": "line-observation"}
    elif category == "method":
        for row in segment["methods"]:
            yield {**row, "evidenceKind": "method-entry"}
    elif category == "exception":
        for row in segment["exceptions"]:
            yield {**row, "evidenceKind": "exception-observation"}
    else:
        for row in segment["dependencies"]:
            # A client hook may identify its system/operation without a safe
            # destination. Keep that explicit evidence; never fill in a target
            # from code names or discard it because the target is unknown.
            if any(row.get(key) for key in ("destination", "system", "operation")):
                yield {key: row[key] for key in
                       ("destination", "count", "boundaryKind", "system", "direction",
                        "operation", "status", "evidenceKind")}


def search_saved_evidence(snapshot_store, capture_store, query, category=None, limit=50):
    """Bounded literal lookup in saved files, executed only when requested.

    A single aggregate event budget limits projecting full viewer pins. Stored
    CLI captures are already minimized. Files are visited in each store's
    newest-first order; a capped result is never advertised as a complete index.
    A None store is skipped; CLI-only callers never infer a viewer namespace.
    The destination category includes explicit dependency systems/operations,
    including boundaries without a captured destination.
    """
    if (not isinstance(query, str) or not 1 <= len(query) <= 128 or not query.strip()
            or not query.isprintable()):
        raise ValueError("Search query must contain 1–128 printable characters.")
    if category not in (None, "all", *SEARCH_CATEGORIES):
        raise ValueError("Search category must be class, method, exception or destination.")
    if type(limit) is not int or not 1 <= limit <= MAX_RESULTS:
        raise ValueError("Search limit must be between 1 and 100.")
    categories = SEARCH_CATEGORIES if category in (None, "all") else (category,)
    needle = query.strip().casefold()
    result = {"schemaVersion": 1, "kind": "saved-evidence-search", "query": query.strip(),
              "category": category or "all", "results": [], "returned": 0,
              "matched": 0, "recordsScanned": 0, "recordsAvailable": 0,
              "eventsProcessed": 0, "limited": False, "warnings": [],
              "limits": {"maxResults": limit, "maxRecords": MAX_SEARCH_RECORDS,
                         "maxEvents": MAX_SEARCH_EVENTS, "maxBytes": MAX_SEARCH_BYTES},
              "limitations": list(SEARCH_LIMITATIONS)}
    warnings, remaining = set(), MAX_SEARCH_EVENTS
    sources = []
    for storage, store, key in (("viewer", snapshot_store, "snapshots"),
                                ("capture", capture_store, "captures")):
        if store is None:
            continue
        try:
            rows = store.list()[key]
        except (ValueError, OSError):
            warnings.add(storage + "_store_unavailable")
            result["limited"] = True
            continue
        result["recordsAvailable"] += len(rows)
        sources.extend((storage, store, row) for row in rows)
    if len(sources) > MAX_SEARCH_RECORDS:
        warnings.add("record_scan_limit")
        result["limited"] = True
    for storage, store, metadata in sources[:MAX_SEARCH_RECORDS]:
        identifier = metadata["id"] if storage == "viewer" else metadata["captureId"]
        if storage == "viewer" and remaining <= 0:
            warnings.add("viewer_event_scan_limit")
            result["limited"] = True
            continue
        try:
            if storage == "viewer":
                capture = project_viewer_snapshot(store.get(identifier),
                                                  max_events=min(remaining, captures.MAX_EVENTS))
                processed = sum(row["eventsProcessed"] for row in capture["segments"])
                remaining -= processed
                result["eventsProcessed"] += processed
            else:
                capture = store.get(identifier)
        except (ValueError, OSError):
            # Do not echo potentially sensitive filesystem or pin error text.
            warnings.add(storage + "_record_unavailable_or_unsupported")
            result["limited"] = True
            continue
        result["recordsScanned"] += 1
        if capture["partial"]:
            warnings.add("partial_saved_evidence")
        if any("event_processing_limit" in row["warnings"] for row in capture["segments"]):
            warnings.add("viewer_event_scan_limit")
            result["limited"] = True
        reference = {"storage": storage, "snapshotId" if storage == "viewer" else "captureId": identifier,
                     "label": capture["label"], "traceId": capture["traceId"],
                     "createdAt": capture["createdAt"], "partial": capture["partial"]}
        for segment in capture["segments"]:
            for selected in categories:
                for identity in _identities(segment, selected):
                    text = " ".join(str(identity.get(key) or "") for key in
                                    ("className", "methodName", "exceptionType", "destination",
                                     "system", "operation"))
                    if needle not in text.casefold():
                        continue
                    result["matched"] += 1
                    if len(result["results"]) < limit:
                        result["results"].append({**reference, "category": selected,
                                                  "service": segment["service"],
                                                  "segmentId": segment["segmentId"],
                                                  "request": dict(segment["request"]), **identity})
    result["warnings"] = sorted(warnings)
    result["returned"] = len(result["results"])
    result["limited"] |= result["matched"] > result["returned"]
    while len(captures._bytes(result)) > MAX_SEARCH_BYTES and result["results"]:
        result["results"].pop()
        result["returned"] = len(result["results"])
        result["limited"] = True
    return result
