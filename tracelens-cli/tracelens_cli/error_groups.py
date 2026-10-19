"""Pure retained-summary error grouping shared by HTTP and MCP.

Groups describe similar observed failure signals, not root causes or recovered
requests. No detail exports, event scans, network calls or source reads occur.
Pass ``now_ms`` for reproducible time-window filtering; its default is wall time.
"""

from __future__ import annotations

from collections import defaultdict
import hashlib
import json
import math
import re
import time
import unicodedata


MAX_SEGMENTS = 10000
MAX_ERRORS = 64
MAX_WARNINGS = 128
MAX_TEXT = 256
MAX_PATH = 2048
MAX_KEY = 4096
CATEGORIES = frozenset(("http4xx", "http5xx", "timeout", "execution"))
_GROUP_ID = re.compile(r"eg_[0-9a-f]{24}\Z")
_TRACE_ID = re.compile(r"(?:[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z")
_DECIMAL = re.compile(r"\d+(?:\.\d+)?\Z")
_CLASS = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*\Z")
_FILTER_TEXT = {"service": 256, "method": 32, "path": MAX_PATH, "category": 32,
                "exception_class": 256, "user": 256, "site": 256, "channel": 64, "query": 256}
_FILTER_NUMERIC = frozenset(("since_minutes", "min_duration", "max_duration", "http_status"))
_SCOPE_WARNING = ("Counts cover loaded retained evidence only, not all traffic or an error rate. "
                  "Requests can occur in multiple groups; group request counts are not additive.")
_SUMMARY_WARNING = "Summary grouping does not index arbitrary exception events or HTTP-client-only failures."


def _text(value, limit=MAX_TEXT):
    if not isinstance(value, str):
        return ""
    cleaned = "".join(" " if unicodedata.category(char).startswith("C") else char
                      for char in value[:limit + 1]).strip()
    return cleaned[:limit - 1] + "…" if len(cleaned) > limit else cleaned


def _number(value):
    """The request list's nonnegative numeric metadata semantics."""
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    if isinstance(value, str) and not _DECIMAL.fullmatch(value.strip()):
        return None
    try:
        result = float(value)
    except (ValueError, OverflowError):
        return None
    return result if math.isfinite(result) and result >= 0 else None


def _scalar(value):
    if isinstance(value, str):
        return _text(value)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            numeric = float(value)
            if math.isfinite(numeric):
                return str(int(numeric)) if numeric.is_integer() else str(numeric)
        except OverflowError:
            pass
    return ""


def _first_text(*values, limit=MAX_TEXT):
    return next((result for value in values if (result := _text(value, limit))), "")


def _first_number(*values):
    return next((result for value in values if (result := _number(value)) is not None), None)


def _first_scalar(*values):
    return next((result for value in values if (result := _scalar(value))), "")


def _true(value):
    return value is True or _text(value).lower() == "true"


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def validate_filters(filters=None) -> dict:
    """Validate before fetching upstream summaries; accepts HTTP numeric strings."""
    if filters is None:
        filters = {}
    if not isinstance(filters, dict):
        raise ValueError("error group filters must be an object")
    unknown = set(filters) - set(_FILTER_TEXT) - _FILTER_NUMERIC
    if unknown:
        raise ValueError("unknown error group filter")
    result = {}
    for name, limit in _FILTER_TEXT.items():
        value = filters.get(name)
        if value is None:
            result[name] = None
            continue
        if not isinstance(value, str) or len(value) > limit or any(unicodedata.category(c).startswith("C") for c in value):
            raise ValueError(f"{name} must be text of at most {limit} characters without control characters")
        result[name] = value.strip() or None
    if result["category"] == "all":
        result["category"] = None
    if result["category"] is not None and result["category"] not in CATEGORIES:
        raise ValueError("category must be http4xx, http5xx, timeout or execution")
    for name in ("method", "channel"):
        if result[name]:
            result[name] = result[name].upper()
    for name in sorted(_FILTER_NUMERIC):
        value = filters.get(name, 30 if name == "since_minutes" else None)
        if value is None or value == "":
            result[name] = None
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float, str)):
            raise ValueError(f"{name} must be a finite nonnegative number")
        if isinstance(value, str) and (len(value) > 64 or not value.strip()):
            raise ValueError(f"{name} must be a finite nonnegative number")
        try:
            number = float(value)
        except (ValueError, OverflowError):
            raise ValueError(f"{name} must be a finite nonnegative number") from None
        if not math.isfinite(number) or number < 0:
            raise ValueError(f"{name} must be a finite nonnegative number")
        if name == "since_minutes" and number > 525600:
            raise ValueError("since_minutes must be between 0 and 525600, or null for all retained")
        if name == "http_status":
            if not number.is_integer() or not 100 <= number <= 599:
                raise ValueError("http_status must be an integer between 100 and 599")
            number = int(number)
        result[name] = number
    if (result["min_duration"] is not None and result["max_duration"] is not None
            and result["min_duration"] > result["max_duration"]):
        raise ValueError("min_duration must not exceed max_duration")
    return result


def validate_group_id(group_id) -> str:
    if not isinstance(group_id, str) or not _GROUP_ID.fullmatch(group_id):
        raise ValueError("invalid error group id")
    return group_id


def _trace_id(value):
    value = _text(value, 128).lower()
    return value if _TRACE_ID.fullmatch(value) and set(value.replace("-", "")) != {"0"} else None


def _parent(row, attrs, name):
    value = row.get(name)
    if value is None or value == "":
        value = attrs.get(name)
    return "" if value is None or value == "" else _text(value) or "[unknown parent]"


def _normalize(row):
    attrs = row.get("attributes") if isinstance(row.get("attributes"), dict) else {}
    status = _first_text(row.get("status"), attrs.get("status")).upper()
    code = _first_number(row.get("httpStatus"), attrs.get("httpStatus"))
    http_status = int(code) if code is not None and code.is_integer() and 100 <= code <= 599 else None
    started = _first_number(row.get("startedAtMillis"), row.get("startedAt"), attrs.get("startedAtMillis"), attrs.get("startedAt"))
    boundary = _first_text(row.get("filterException"), attrs.get("filterException"))
    timeout = _true(row.get("asyncTimeout")) or _true(attrs.get("asyncTimeout"))
    exception_count = _first_number(row.get("exceptionCount"), attrs.get("exceptionCount")) or 0
    has_errors = (http_status is not None and http_status >= 400) or status in ("FAILED", "ERROR") or row.get("hasErrors") is True or timeout
    boundary_evidence = bool(boundary and boundary.lower() != "false")
    exception_class = boundary if boundary.lower() not in ("true", "false", "null", "none") and _CLASS.fullmatch(boundary) else None
    method = _first_text(row.get("httpMethod"), row.get("method"), attrs.get("httpMethod"), attrs.get("method"), limit=32).upper()
    path = _first_text(row.get("path"), attrs.get("path"), limit=MAX_PATH)
    route_values = (row.get("routeTemplate"), row.get("httpRoute"), row.get("httpRouteTemplate"),
                    attrs.get("routeTemplate"), attrs.get("httpRoute"), attrs.get("httpRouteTemplate"), attrs.get("http.route"), attrs.get("route"))
    template = _first_text(*route_values, limit=MAX_PATH)
    label = _first_text(row.get("lensId"), row.get("label"), attrs.get("lensId"))
    channel = _first_text(row.get("entryChannel"), row.get("channel"), attrs.get("entryChannel"), limit=64).upper() or "UNKNOWN"
    user = _first_text(row.get("user"), attrs.get("user"))
    site = _first_scalar(row.get("siteId"), row.get("site"), attrs.get("siteId"), attrs.get("site"))
    node = {"traceId": _trace_id(row.get("traceId")), "segmentId": _text(row.get("segmentId")),
            "segmentKey": _text(row.get("segmentKey"), MAX_KEY) or None,
            "service": _first_text(row.get("service"), row.get("applicationId"), attrs.get("applicationId")) or "unknown",
            "serviceInstanceId": _first_text(row.get("serviceInstanceId"), attrs.get("serviceInstanceId")),
            "applicationId": _first_text(row.get("applicationId"), attrs.get("applicationId")),
            "parentSpanId": _parent(row, attrs, "parentSpanId"), "parentSegmentId": _parent(row, attrs, "parentSegmentId"),
            "spanId": _first_text(row.get("spanId"), attrs.get("spanId")),
            "method": method, "httpMethod": method, "path": path, "routeTemplate": template,
            "label": label, "lensId": label, "channel": channel, "entryChannel": channel,
            "status": status, "httpStatus": http_status, "user": user, "site": site, "siteId": site,
            "startedAtMillis": started,
            "lastActivityMillis": _first_number(row.get("lastActivityMillis"), attrs.get("lastActivityMillis"), row.get("endedAt"), started),
            "durationMs": _first_number(row.get("durationMs"), attrs.get("durationMs")),
            "eventCount": min(_first_number(row.get("eventCount")) or 0, 9007199254740991),
            "hasErrors": bool(has_errors), "hasExceptions": bool(row.get("hasExceptions") is True or exception_count > 0 or boundary_evidence),
            "filterException": exception_class or ("true" if boundary_evidence else ""),
            "exceptionClass": exception_class, "asyncTimeout": timeout,
            "truncated": row.get("truncated") is True, "httpCallsTruncated": row.get("httpCallsTruncated") is True,
            "httpCallCount": _first_number(row.get("httpCallCount"))}
    # A clipped route must not collapse distinct long literal paths/templates.
    # Only its digest is used internally; no additional raw text is returned.
    raw_route_values = route_values if template else (row.get("path"), attrs.get("path")) if path else (row.get("lensId"), row.get("label"), attrs.get("lensId"))
    raw_route = next((value for value in raw_route_values if isinstance(value, str) and value.strip()), "")
    route_identity = hashlib.sha256(raw_route.encode("utf-8", errors="surrogatepass")).hexdigest()
    return node, route_identity


def _segment_order(segment):
    return (segment["startedAtMillis"] if segment["startedAtMillis"] is not None else math.inf,
            segment["service"], segment["serviceInstanceId"], segment["segmentId"], _canonical(segment))


def _request_order(request):
    return (-(request["startedAtMillis"] if request["startedAtMillis"] is not None else -math.inf), request["key"])


def _requests(segments, conflicting_identities):
    groups, occurrences = defaultdict(list), defaultdict(int)
    for segment in sorted(segments, key=_segment_order):
        if segment["traceId"]:
            key = "trace:" + segment["traceId"]
        else:
            base = "segment:" + json.dumps([segment["service"], segment["serviceInstanceId"], segment["segmentId"]], ensure_ascii=False, separators=(",", ":"))
            occurrence = occurrences[base]
            occurrences[base] += 1
            key = base + (f":observation:{occurrence}" if occurrence else "")
        groups[key].append(segment)
    requests = []
    for key, members in groups.items():
        roots = [s for s in members if not s["parentSpanId"] and not s["parentSegmentId"]]
        conflicting_root = any((s["service"], s["serviceInstanceId"], s["segmentId"]) in conflicting_identities for s in roots)
        missing = len(roots) != 1 or conflicting_root
        root = roots[0] if roots else members[0]
        errors = any(s["hasErrors"] for s in members)
        active = any(s["status"] == "ACTIVE" for s in members)
        success = not missing and root["httpStatus"] is not None and 200 <= root["httpStatus"] < 400
        starts = [s["startedAtMillis"] for s in members if s["startedAtMillis"] is not None]
        activity = [s["lastActivityMillis"] for s in members if s["lastActivityMillis"] is not None]
        requests.append({"key": key, "traceId": members[0]["traceId"], "segments": members, "root": root,
                         "rootMissing": missing, "rootAmbiguous": len(roots) > 1,
                         "label": root["label"] or " ".join(p for p in (root["method"], root["path"]) if p) or root["channel"] + " request",
                         "method": root["method"], "path": root["path"], "user": root["user"], "site": root["site"],
                         "services": sorted({s["service"] for s in members}), "channel": root["channel"],
                         "startedAtMillis": root["startedAtMillis"] if not missing and root["startedAtMillis"] is not None else min(starts) if starts else None,
                         "lastActivityMillis": max(activity) if activity else None,
                         "durationMs": None if missing else root["durationMs"], "httpStatus": None if missing else root["httpStatus"],
                         "outcome": "error" if errors else "active" if active else "success" if success else "unknown",
                         "hasErrors": errors, "hasExceptions": any(s["hasExceptions"] for s in members),
                         "eventCount": min(sum(s["eventCount"] for s in members), 9007199254740991)})
    return sorted(requests, key=_request_order)


def _category(segment):
    if segment["asyncTimeout"]:
        return "timeout"
    code = segment["httpStatus"]
    if code is not None and 500 <= code <= 599:
        return "http5xx"
    if code is not None and 400 <= code <= 499:
        return "http4xx"
    return "execution" if segment["hasErrors"] else None


def _contains(value, query):
    return query.lower() in str(value or "").lower()


def _request_matches(request, filters, now_ms):
    started = request["startedAtMillis"]
    if filters["since_minutes"] is not None and (started is None or started < now_ms - filters["since_minutes"] * 60000):
        return False
    duration = request["durationMs"]
    if filters["min_duration"] is not None and (duration is None or duration < filters["min_duration"]):
        return False
    if filters["max_duration"] is not None and (duration is None or duration > filters["max_duration"]):
        return False
    if not any((not filters["user"] or _contains(s["user"], filters["user"]))
               and (not filters["site"] or s["site"] == filters["site"]) for s in request["segments"]):
        return False
    query = filters["query"]
    if query and not any(_contains(value, query) for value in [request["traceId"], request["label"],
                        *(s.get(name) for s in request["segments"] for name in
                          ("segmentId", "service", "serviceInstanceId", "method", "path", "routeTemplate", "label", "user", "site"))]):
        return False
    return True


def _segment_matches(segment, category, filters):
    return (bool(category) and (not filters["service"] or segment["service"] == filters["service"])
            and (not filters["method"] or segment["method"] == filters["method"])
            and (not filters["channel"] or segment["channel"] == filters["channel"])
            and (not filters["path"] or _contains(segment["path"], filters["path"]) or _contains(segment["routeTemplate"], filters["path"]))
            and (not filters["category"] or category == filters["category"])
            and (filters["http_status"] is None or segment["httpStatus"] == filters["http_status"])
            and (not filters["exception_class"] or _contains(segment["exceptionClass"], filters["exception_class"])))


def build_error_groups(doc, filters=None, now_ms=None) -> dict:
    """Group bounded summary evidence; keep all siblings of matching requests."""
    filters = validate_filters(filters)
    clock = time.time_ns() / 1_000_000 if now_ms is None else _number(now_ms)
    if clock is None:
        raise ValueError("now_ms must be a finite nonnegative timestamp")
    warnings = {_SCOPE_WARNING, _SUMMARY_WARNING}
    incomplete = False

    def warn(message):
        nonlocal incomplete
        warnings.add(message)
        incomplete = True

    if not isinstance(doc, dict):
        warn("Malformed summary document; no missing evidence inferred")
        doc = {}
    rows = doc.get("traces", [])
    if not isinstance(rows, list):
        warn("Malformed trace summaries omitted")
        rows = []
    limited = bool(doc.get("limited")) or len(rows) > MAX_SEGMENTS
    if limited:
        warn("Retained summary results are limited; counts exclude omitted segments and requests")
    if len(rows) > MAX_SEGMENTS:
        warn(f"Error grouping input limit reached ({MAX_SEGMENTS} segments)")
    errors = []
    raw_errors = doc.get("errors", [])
    if not isinstance(raw_errors, list):
        warn("Malformed upstream error metadata omitted")
        raw_errors = []
    for error in raw_errors[:MAX_ERRORS]:
        if not isinstance(error, dict):
            warn("Malformed upstream error entry omitted")
            continue
        errors.append({"service": _text(error.get("service")) or None,
                       "error": _text(error.get("error"), 512) or "Unknown upstream error"})
    if len(raw_errors) > MAX_ERRORS:
        warn(f"Upstream error display limit reached ({MAX_ERRORS})")
    if errors or doc.get("partial"):
        warn("Some upstream evidence is unavailable or partial; upstream errors are not application error groups")
    raw_warnings = doc.get("warnings", [])
    if isinstance(raw_warnings, list):
        for value in raw_warnings[:MAX_WARNINGS]:
            if message := _text(value, 512):
                warn(message)
            else:
                warn("Malformed upstream warning omitted")
        if len(raw_warnings) > MAX_WARNINGS:
            warn("Upstream warning display limit reached")
    else:
        warn("Malformed upstream warnings omitted")

    segments, route_identities = [], {}
    identities, conflicting_identities = defaultdict(set), set()
    for row in rows[:MAX_SEGMENTS]:
        if not isinstance(row, dict):
            warn("Malformed trace summary entries omitted")
            continue
        segment, route_identity = _normalize(row)
        if segment["segmentId"]:
            identity = (segment["service"], segment["serviceInstanceId"], segment["segmentId"])
            signature = _canonical([segment, route_identity])
            if signature in identities[identity]:
                warn("Duplicate identical retained segment summaries were deduplicated")
                continue
            if identities[identity]:
                warn("Conflicting summaries for one segment are retained; root identification may be ambiguous")
                conflicting_identities.add(identity)
            identities[identity].add(signature)
        segments.append(segment)
        route_identities[id(segment)] = route_identity
        if segment["traceId"] is None:
            warn("Some summaries have no valid trace ID; unidentified observations are not merged into logical requests")
        if not segment["segmentId"]:
            warn("Some summaries lack segment identifiers; their observed entries are counted separately")
        if segment["truncated"] or segment["httpCallsTruncated"]:
            warn("Some retained segments are truncated; additional error evidence may be unavailable")
        if segment["status"] == "ACTIVE":
            warn("Some retained segments are active; recorded outcomes can still change")
    requests = _requests(segments, conflicting_identities)
    groups, matching, matching_segments = {}, [], set()
    for request in requests:
        if not _request_matches(request, filters, clock):
            continue
        matched = False
        for segment in request["segments"]:
            category = _category(segment)
            if not _segment_matches(segment, category, filters):
                continue
            matched = True
            segment_identity = ((segment["service"], segment["serviceInstanceId"], segment["segmentId"])
                                if segment["segmentId"] else ("unidentified", id(segment)))
            matching_segments.add(segment_identity)
            route = segment["routeTemplate"] or segment["path"] or segment["label"]
            route_kind = "template" if segment["routeTemplate"] else "literal" if segment["path"] else "label" if segment["label"] else "unknown"
            signature = [segment["service"], segment["channel"], segment["method"], route_kind,
                         route_identities[id(segment)], category, segment["httpStatus"], segment["exceptionClass"]]
            group_id = "eg_" + hashlib.sha256(_canonical(signature).encode()).hexdigest()[:24]
            if group_id not in groups:
                groups[group_id] = {"id": group_id, "service": segment["service"], "channel": segment["channel"],
                    "method": segment["method"], "route": route, "routeKind": route_kind, "category": category,
                    "httpStatus": segment["httpStatus"], "exceptionClass": segment["exceptionClass"],
                    "requestCount": 0, "segmentCount": 0, "firstSeenMillis": None, "lastSeenMillis": None,
                    "requestKeys": set(), "_segmentIdentities": set()}
            group = groups[group_id]
            group["_segmentIdentities"].add(segment_identity)
            group["requestKeys"].add(request["key"])
            started = request["startedAtMillis"]
            if started is not None:
                group["firstSeenMillis"] = started if group["firstSeenMillis"] is None else min(group["firstSeenMillis"], started)
                group["lastSeenMillis"] = started if group["lastSeenMillis"] is None else max(group["lastSeenMillis"], started)
        if matched:
            matching.append(request)
    result_groups = list(groups.values())
    for group in result_groups:
        group["segmentCount"] = len(group.pop("_segmentIdentities"))
        group["requestKeys"] = sorted(group["requestKeys"])
        group["requestCount"] = len(group["requestKeys"])
    result_groups.sort(key=lambda g: (-g["requestCount"], -(g["lastSeenMillis"] if g["lastSeenMillis"] is not None else -math.inf), g["id"]))
    messages = sorted(warnings, key=lambda s: (s != _SCOPE_WARNING, "limit" not in s.lower(), s))
    if len(messages) > MAX_WARNINGS:
        messages = messages[:MAX_WARNINGS - 1] + ["Warning display limit reached; additional warnings omitted"]
    return {"schemaVersion": 1, "kind": "error-groups", "scope": "retained-only", "partial": bool(incomplete),
            "limited": limited, "errors": sorted(errors, key=_canonical), "warnings": messages,
            "counts": {"loadedSegments": len(segments), "loadedRequests": len(requests),
                       "matchingRequests": len(matching), "matchingSegments": len(matching_segments), "groups": len(result_groups)},
            "groups": result_groups, "requests": matching}


def select_error_group(model: dict, group_id: str) -> dict:
    """Select one current group without new I/O or broadening the filter scope."""
    validate_group_id(group_id)
    group = next((group for group in model["groups"] if group["id"] == group_id), None)
    keys = set(group["requestKeys"]) if group else set()
    requests = [request for request in model["requests"] if request["key"] in keys]
    warnings = list(model["warnings"])
    if group is None:
        warnings.append("Error group not observed in the current retained/filter scope; evidence may have changed or expired")
    return {"schemaVersion": 1, "kind": "error-group", "scope": "retained-only", "groupId": group_id,
            "group": group, "requests": requests, "partial": model["partial"], "limited": model["limited"],
            "errors": model["errors"], "warnings": warnings,
            "counts": {**model["counts"], "matchingRequests": len(requests),
                       "matchingSegments": group["segmentCount"] if group else 0, "groups": 1 if group else 0}}
