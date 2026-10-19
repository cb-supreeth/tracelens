"""Focused, read-only discovery of one retained request and its observed journey.

Filters must match one observed segment; matching never removes sibling segments
from the request. Discovery is summary-only until an unambiguous request is chosen.
The selected journey uses the existing non-evicting federation export mechanism.
"""

from __future__ import annotations

from collections import defaultdict
import json
import math
import re
import time
import unicodedata

from .error_groups import _normalize, _requests
from .services import Federation
from .visualization import build_visualization


MAX_SUMMARIES = 10000
MAX_CANDIDATES = 20
MAX_NODES = 40
MAX_FAILURES = 20
MAX_MODEL_BYTES = 48 * 1024
_TRACE_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
_OUTCOMES = frozenset(("all", "error", "success", "active", "unknown"))
_NOTE = ("Live retained observations only, not all traffic or proof of non-execution. "
         "Failure signals are not root-cause findings. Durations are local elapsed time; "
         "cross-service clocks are not assumed synchronized.")


def _text(value, limit=256):
    if not isinstance(value, str):
        return None
    value = "".join(" " if unicodedata.category(c).startswith("C") else c for c in value[:limit])
    return value or None


def _validate(discovery, service, method, path, user, label, since_minutes, outcome, selection, trace_id):
    filters = {}
    for name, value, maximum in (("service", service, 64), ("method", method, 32),
                                  ("path", path, 2048), ("user", user, 256), ("label", label, 256)):
        if value is not None and (not isinstance(value, str) or len(value) > maximum
                                 or any(unicodedata.category(c).startswith("C") for c in value)):
            raise ValueError(f"{name} must be text of at most {maximum} characters without controls")
        filters[name] = value.strip() or None if value is not None else None
    if filters["service"] is not None and filters["service"] not in discovery.services:
        raise ValueError("unknown configured service")
    if filters["method"]:
        filters["method"] = filters["method"].upper()
    if trace_id is not None and (not isinstance(trace_id, str) or not _TRACE_ID.fullmatch(trace_id)):
        raise ValueError("invalid trace_id")
    if not (filters["path"] or filters["label"] or trace_id):
        raise ValueError("provide a path, scenario label, or exact trace_id to focus the investigation")
    if (isinstance(since_minutes, bool) or not isinstance(since_minutes, (int, float))
            or not 0 <= since_minutes <= 525600 or not math.isfinite(since_minutes)):
        raise ValueError("since_minutes must be a finite number between 0 and 525600")
    if not isinstance(outcome, str) or outcome not in _OUTCOMES:
        raise ValueError("outcome must be all, error, success, active or unknown")
    if selection not in ("unique", "latest"):
        raise ValueError("selection must be unique or latest")
    filters.update(since_minutes=since_minutes, outcome=outcome, selection=selection, trace_id=trace_id)
    return filters


def _group(rows, warnings):
    """Reuse the request UI's summary/outcome semantics, with exact trace keys."""
    normalized, identities, conflicts = [], defaultdict(set), set()
    if not isinstance(rows, list):
        warnings.add("malformed_summaries")
        return []
    if len(rows) > MAX_SUMMARIES:
        warnings.add("summary_limit")
    for row in rows[:MAX_SUMMARIES]:
        if not isinstance(row, dict) or not isinstance(row.get("traceId"), str) or not _TRACE_ID.fullmatch(row["traceId"]):
            warnings.add("invalid_summary_identity")
            continue
        segment, _ = _normalize(row)
        # Federation accepts UUIDs, W3C IDs and legacy opaque trace IDs. Do not
        # lowercase opaque keys or compare truncated display labels as identities.
        segment["traceId"] = row["traceId"]
        key = (segment["service"], segment["serviceInstanceId"], segment["segmentId"])
        signature = json.dumps(segment, sort_keys=True, ensure_ascii=True)
        if segment["segmentId"]:
            if signature in identities[key]:
                continue
            if identities[key]:
                warnings.add("conflicting_segment_identity")
                conflicts.add(key)
            identities[key].add(signature)
        else:
            warnings.add("missing_segment_identity")
        normalized.append(segment)
    return _requests(normalized, conflicts)


def _matches(request, filters, cutoff):
    if filters["trace_id"] and request["traceId"] != filters["trace_id"]:
        return False
    if filters["outcome"] != "all" and request["outcome"] != filters["outcome"]:
        return False
    for segment in request["segments"]:
        if (segment["startedAtMillis"] is None or segment["startedAtMillis"] < cutoff):
            continue
        if filters["service"] and segment["service"] != filters["service"]:
            continue
        if filters["method"] and segment["method"] != filters["method"]:
            continue
        if any(filters[key] and filters[key].casefold() not in segment[key].casefold()
               for key in ("path", "user", "label")):
            continue
        return True
    return False


def _candidate(request):
    return {"traceId": request["traceId"], "method": request["method"],
            "path": _text(request["path"], 256), "label": _text(request["label"], 128),
            "services": [_text(value, 64) for value in request["services"][:16]],
            "serviceCount": len(request["services"]), "segmentCount": len(request["segments"]),
            "startedAtMillis": request["startedAtMillis"], "durationMs": request["durationMs"],
            "httpStatus": request["httpStatus"], "outcome": request["outcome"],
            "rootMissing": request["rootMissing"], "rootAmbiguous": request["rootAmbiguous"]}


def _upstream_state(doc, warnings):
    if not isinstance(doc, dict):
        warnings.add("malformed_upstream_result")
        return True
    if doc.get("limited"):
        warnings.add("summary_limit")
    if doc.get("partial") or doc.get("errors"):
        warnings.add("upstream_evidence_incomplete")
    if doc.get("warnings"):
        # Never echo upstream error/exception messages or arbitrary warning text.
        warnings.add("upstream_evidence_warnings")
    return bool(doc.get("limited") or doc.get("partial") or doc.get("errors"))


def _failure_evidence(request, journey, warnings):
    result = []
    for segment in request["segments"]:
        signals = []
        if segment["httpStatus"] is not None and segment["httpStatus"] >= 400:
            signals.append("http_error_status")
        if segment["asyncTimeout"]:
            signals.append("async_timeout")
        if segment["status"] in ("FAILED", "ERROR"):
            signals.append("failed_execution_status")
        if segment["hasErrors"] and not signals:
            signals.append("summary_error_flag")
        if segment["hasExceptions"]:
            signals.append("exception_observed_not_necessarily_request_failure")
        if signals:
            result.append({"kind": "segment", "service": segment["service"],
                           "segmentId": segment["segmentId"], "serviceInstanceId": segment["serviceInstanceId"],
                           "httpStatus": segment["httpStatus"], "signals": signals})
    calls = journey.get("httpCalls", [])
    if not isinstance(calls, list):
        warnings.add("malformed_http_calls")
        calls = []
    if len(calls) > MAX_SUMMARIES:
        warnings.add("http_call_scan_limit")
    for call in calls[:MAX_SUMMARIES]:
        if not isinstance(call, dict):
            warnings.add("malformed_http_calls")
            continue
        segment, _ = _normalize(call)
        if segment["hasErrors"]:
            result.append({"kind": "http-call", "service": segment["service"],
                           "method": segment["method"], "path": _text(segment["path"], 256),
                           "httpStatus": segment["httpStatus"],
                           "signals": ["http_error_status" if segment["httpStatus"] is not None
                                       and segment["httpStatus"] >= 400 else "failed_call_status"]})
    if len(result) > MAX_FAILURES:
        warnings.add("failure_evidence_display_limit")
    has_failure = any(item["signals"] != ["exception_observed_not_necessarily_request_failure"] for item in result)
    return result[:MAX_FAILURES], len(result), has_failure


def _journey(journey, warnings):
    # The visualizer already enforces observed ownership and explicit links. Hide
    # raw diagnostics and return only a compact subset of its allowlisted model.
    safe = {key: journey.get(key, []) for key in ("segments", "httpCalls", "externalCalls", "links")}
    safe.update(traceId=journey.get("traceId"), partial=bool(journey.get("partial")), errors=[], warnings=[])
    graph = build_visualization(safe)
    if graph["partial"]:
        warnings.add("journey_evidence_incomplete")
    if len(graph["nodes"]) > MAX_NODES:
        warnings.add("journey_display_limit")
    fields = ("id", "kind", "service", "serviceInstanceId", "segmentId", "method", "path", "status",
              "httpStatus", "durationMs", "truncated", "ownerId", "downstreamState", "system",
              "boundaryKind", "direction", "operation", "destinationName", "evidenceKind")
    nodes = [{key: _text(node[key], 256) if isinstance(node[key], str) else node[key]
              for key in fields if key in node} for node in graph["nodes"][:MAX_NODES]]
    kept = {node["id"] for node in nodes}
    edges = [edge for edge in graph["edges"] if edge["from"] in kept and edge["to"] in kept]
    return {"nodes": nodes, "edges": edges, "nodeCount": len(graph["nodes"]),
            "nodesOmitted": len(graph["nodes"]) - len(nodes),
            "partial": graph["partial"] or len(graph["nodes"]) > len(nodes)}


def _finish(result, warnings):
    result["warnings"] = sorted(warnings)
    result["partial"] = bool(warnings)
    result["limited"] = any("limit" in warning for warning in warnings)
    # A hard JSON byte budget also bounds pathological Unicode/identifier input.
    def size():
        return len(json.dumps(result, ensure_ascii=True, separators=(",", ":")).encode("ascii"))
    while size() > MAX_MODEL_BYTES - 256:
        warnings.add("output_byte_limit")
        detail = result["investigation"]
        if detail and detail["journey"]["nodes"]:
            graph = detail["journey"]
            removed = graph["nodes"].pop()["id"]
            graph["edges"] = [e for e in graph["edges"] if removed not in (e["from"], e["to"])]
            graph["nodesOmitted"] += 1
            graph["partial"] = True
        elif len(result["candidates"]) > 1:
            result["candidates"].pop()
        elif detail and detail["failureEvidence"]:
            detail["failureEvidence"].pop()
        else:
            # The remaining fixed shape and bounded request metadata fit the cap.
            break
        result.update(warnings=sorted(warnings), partial=True, limited=True)
    result["counts"]["candidatesReturned"] = len(result["candidates"])
    return result


def investigate_request(discovery: Federation, *, service=None, method=None, path=None,
                        user=None, label=None, since_minutes=10, outcome="all",
                        selection="unique", trace_id=None) -> dict:
    """Locate and inspect one request without copying IDs between tool calls.

    ``unique`` never silently chooses among requests. ``latest`` is explicit and
    requires complete discovery and a unique, known newest request timestamp.
    Exact IDs skip broad discovery; other supplied filters, including time, still
    apply. All matching is against retained summaries, not arbitrary event text.
    A partial discovery cannot establish uniqueness or latest, even for one row.
    """
    filters = _validate(discovery, service, method, path, user, label, since_minutes,
                        outcome, selection, trace_id)
    warnings = set()
    cutoff = time.time() * 1000 - since_minutes * 60000
    journey = discovery.get_distributed_trace(trace_id) if trace_id else None
    doc = journey if trace_id else discovery.traces(limit=MAX_SUMMARIES)
    incomplete = _upstream_state(doc, warnings)
    doc = doc if isinstance(doc, dict) else {}
    requests = _group(doc.get("segments" if trace_id else "traces", []), warnings)
    incomplete = incomplete or bool(warnings)
    matching = [request for request in requests if _matches(request, filters, cutoff)]
    result = {"schemaVersion": 1, "kind": "request-investigation", "scope": "retained-only",
              "status": "not_found", "selectedTraceId": None, "selection": selection,
              "candidates": [_candidate(request) for request in matching[:MAX_CANDIDATES]],
              "counts": {"loadedRequests": len(requests), "matchingRequests": len(matching),
                         "candidatesReturned": min(len(matching), MAX_CANDIDATES)},
              "investigation": None, "nextSteps": [], "note": _NOTE}
    if len(matching) > MAX_CANDIDATES:
        warnings.add("candidate_display_limit")
    if not matching:
        result["nextSteps"] = ["Reproduce the scenario with recording enabled, or adjust the API/label and time window.",
                               "Not found means not observed in the available retained scope, not that it did not execute."]
        return _finish(result, warnings)
    selected = None
    if trace_id or (len(matching) == 1 and not incomplete):
        selected = matching[0]
    elif selection == "latest" and not incomplete:
        starts = [request["startedAtMillis"] for request in matching]
        if all(start is not None for start in starts) and starts[0] > starts[1]:
            selected = matching[0]
        else:
            warnings.add("latest_timestamp_ambiguous")
    if selected is None:
        result["status"] = "ambiguous"
        result["nextSteps"] = ["Narrow path, scenario label, service, user or time; or explicitly select a candidate trace_id.",
                               "Use selection=latest only when choosing the newest matching request is intended."]
        return _finish(result, warnings)
    result.update(status="selected", selectedTraceId=selected["traceId"])
    if journey is None:
        journey = discovery.get_distributed_trace(selected["traceId"])
        _upstream_state(journey, warnings)
    journey = journey if isinstance(journey, dict) else {}
    fresh = _group(journey.get("segments", []), warnings)
    current = next((request for request in fresh if request["traceId"] == selected["traceId"]), None)
    if current is None:
        warnings.add("selected_request_no_longer_available")
        current = selected
    elif not _matches(current, filters, cutoff):
        warnings.add("selected_request_changed_since_discovery")
    if current["rootMissing"]:
        warnings.add("root_missing_or_ambiguous")
    if any(segment["truncated"] or segment["httpCallsTruncated"] for segment in current["segments"]):
        warnings.add("capture_truncated")
    if any(segment["status"] == "ACTIVE" for segment in current["segments"]):
        warnings.add("active_request_snapshot")
    failures, failure_count, has_failure = _failure_evidence(current, journey, warnings)
    result["investigation"] = {"request": _candidate(current), "journey": _journey(journey, warnings),
                               "failureEvidence": failures, "failureEvidenceCount": failure_count,
                               "hasObservedFailure": has_failure}
    if has_failure:
        result["investigation"]["request"]["outcome"] = "error"
    for index, candidate in enumerate(result["candidates"]):
        if candidate["traceId"] == selected["traceId"]:
            result["candidates"][index] = dict(result["investigation"]["request"])
    result["nextSteps"] = ["Inspect an observed failure segment with get_flow_summary or get_segment_execution using its service and serviceInstanceId.",
                           "Use get_flow for source evidence only when needed; do not infer a root cause from summary signals."]
    return _finish(result, warnings)
