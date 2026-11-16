"""Bounded, read-only diagnosis of an exact HTTP backend path.

Running metadata is evidence about current capture eligibility, never proof that
a historical request was excluded, executed, or expired. No event export occurs.
"""

from concurrent.futures import ThreadPoolExecutor
import json
import re
import unicodedata
import urllib.parse
import urllib.request

from .client import TraceLensClient
from .doctor import _NoRedirect, _boolean, _integer, _probe
from .investigation import _candidate, _group


MAX_SUMMARIES = 1000
MAX_CANDIDATES = 20
MAX_TRACE_BYTES = 1024 * 1024
MAX_REPORT_BYTES = 128 * 1024
_PATH_BAD = re.compile(r"[\s?#*\\,\[\]{}]")
_METHOD = re.compile(r"[A-Z][A-Z0-9_-]{0,31}\Z")


def validate_filters(discovery, path, method=None, service=None, user=None, site=None):
    """Validate all arguments before touching an upstream endpoint."""
    result = {}
    for name, value, limit in (("path", path, 2048), ("method", method, 32),
                               ("service", service, 64), ("user", user, 256),
                               ("site", site, 256)):
        if value is not None and (not isinstance(value, str) or len(value) > limit
                                 or any(unicodedata.category(c).startswith("C") for c in value)):
            raise ValueError(f"{name} must be text of at most {limit} characters without controls")
        result[name] = value.strip() or None if value is not None else None
    path = result["path"]
    if (not path or not path.startswith("/") or path.startswith("//")
            or _PATH_BAD.search(path) or any(ord(c) > 126 for c in path)
            or re.search(r"%(?![0-9A-Fa-f]{2})", path)
            or any(unicodedata.category(c).startswith("C") for c in urllib.parse.unquote(path))):
        raise ValueError("path must be an exact absolute backend path without a URL, query, fragment, whitespace or wildcards")
    if result["method"]:
        result["method"] = result["method"].upper()
        if not _METHOD.fullmatch(result["method"]):
            raise ValueError("method must be a single HTTP method token")
    if result["service"] and result["service"] not in discovery.services:
        raise ValueError("unknown configured service")
    return result


def _traces(client):
    # Use a bounded wire read for real clients; test/embedding clients stay
    # injectable. Configured URLs are checked by the diagnostic metadata reader.
    if type(client) is not TraceLensClient:
        return client.traces()
    parsed = urllib.parse.urlsplit(client.base_url)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment
            or any(c.isspace() or ord(c) < 32 for c in client.base_url)):
        raise ValueError("invalid debug endpoint")
    parsed.port
    timeout = client.timeout
    if type(timeout) not in (int, float) or not 0 < timeout <= 5:
        timeout = 5
    req = urllib.request.Request(client.base_url + "/tracelens/traces", method="GET")
    with urllib.request.build_opener(_NoRedirect()).open(req, timeout=timeout) as response:
        data = response.read(MAX_TRACE_BYTES + 1)
    if len(data) > MAX_TRACE_BYTES:
        raise ValueError("summary byte limit")
    return json.loads(data.decode("utf-8"))


def _paths(value):
    if not isinstance(value, list) or len(value) > 64:
        return None
    if any(not isinstance(p, str) or not p.startswith("/") or len(p) > 512
           or _PATH_BAD.search(p) or any(ord(c) < 33 or ord(c) > 126 for c in p)
           for p in value):
        return None
    return value


def _check(checks, service, key, status, message, **details):
    checks.append({"service": service, "id": key, "status": status,
                   "message": message, "details": details})


def _service(discovery, name, path):
    client = discovery.clients[name]
    summary, summary_error = _probe(client, "summary")
    metadata, metadata_error = _probe(client, "metadata")
    checks, warnings = [], set()
    if summary_error or metadata_error:
        warnings.add("diagnostic_evidence_unavailable")
    _check(checks, name, "reachability", "pass" if summary_error is None else "unknown",
           "The configured debug summary endpoint responded." if summary_error is None else
           "The configured debug summary could not be read; check the debug URL, service startup and access policy.",
           reachable=summary_error is None, httpStatus=(summary_error or {}).get("httpStatus"))
    _check(checks, name, "metadata", "pass" if metadata_error is None else "unknown",
           "Running agent metadata was inspected." if metadata_error is None else
           "Running metadata is unavailable; local configuration files are not evidence of effective settings.")
    for key, field in (("agent", "agentActive"), ("recording", "recordingEnabled")):
        value = _boolean(summary.get(field)) if field in summary else _boolean(metadata.get(field))
        if field in metadata and field in summary and _boolean(metadata[field]) != value:
            value = None
        failed = "excluded" if key == "recording" else "fail"
        _check(checks, name, key, "pass" if value is True else failed if value is False else "unknown",
               (f"{field} is enabled for eligible new requests." if value is True else
                f"{field} reports false now; the current capture setup is not ready." if value is False else
                f"{field} is unknown or inconsistent; no capture readiness is assumed."), value=value)
    # Both adapters report effective paths in summary and metadata. A mismatch
    # can be a racing restart; never turn conflicting reads into an exclusion.
    available = [_paths(doc["httpIncludePaths"]) for doc in (summary, metadata) if "httpIncludePaths" in doc]
    paths = available[0] if available and available[0] is not None and all(p == available[0] for p in available) else None
    allowed = None if paths is None else any(
        p.rstrip("/") == "" or path == p.rstrip("/") or path.startswith(p.rstrip("/") + "/") for p in paths)
    _check(checks, name, "http_path", "pass" if allowed is True else "excluded" if allowed is False else "unknown",
           "The exact path is included by the running HTTP entry-path configuration." if allowed is True else
           "The running httpIncludePaths excludes this path. This proves current exclusion, not why a past request is absent."
           if allowed is False else "Effective httpIncludePaths is missing, malformed or inconsistent; inclusion is unknown.",
           httpIncludePaths=paths, included=allowed)
    required = _boolean(metadata.get("requireLabels"))
    _check(checks, name, "labels", "warn" if required is True else "pass" if required is False else "unknown",
           "Running requireLabels is enabled. Reproduce with an approved TraceLens label; user/site filters do not supply a label."
           if required is True else "Running metadata does not require request labels." if required is False else
           "The running label requirement is unknown; an unlabeled request may not be captured.", requireLabels=required)
    packages = metadata.get("packages")
    valid_packages = isinstance(packages, list) and len(packages) <= 64 and all(
        isinstance(p, str) and len(p) <= 256 and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$.]*", p) for p in packages)
    line_capture = _boolean(metadata.get("lineCaptureEnabled"))
    if line_capture is None and valid_packages:
        line_capture = bool(packages)
    _check(checks, name, "scope", "warn" if line_capture is False else "unknown",
           "No line-capture package scope is enabled; HTTP boundary evidence may still be available." if line_capture is False else
           "Package scope alone cannot establish that this API's implementation is instrumented. Verify the backend handler, package scope and compiled line tables.",
           lineCaptureEnabled=line_capture, packageCount=len(packages) if valid_packages else None)
    _check(checks, name, "retention", "unknown",
           "Missing retained data does not prove expiry or non-execution. Idle expiry, capacity eviction, exclusion and an unobserved request remain possible.",
           idleTtlMinutes=_integer(metadata.get("idleTtlMinutes"), positive=True),
           maxTrackedRequests=_integer(summary.get("maxTrackedRequests"), positive=True))
    try:
        doc = _traces(client)
        rows = doc.get("traces") if isinstance(doc, dict) else None
        if not isinstance(rows, list):
            raise ValueError("invalid summaries")
        if len(rows) > MAX_SUMMARIES or doc.get("limited"):
            warnings.add("summary_limit")
        if doc.get("partial") or doc.get("errors"):
            warnings.add("summary_evidence_incomplete")
        selected = []
        for row in rows[:MAX_SUMMARIES]:
            if not isinstance(row, dict):
                warnings.add("malformed_summary")
                continue
            selected.append({**row, "service": name})
    except Exception:
        # Diagnostics expose no endpoint bodies, credentials or exception text.
        selected = []
        warnings.add("summary_evidence_unavailable")
    return {"service": name, "checks": checks}, selected, warnings


def diagnose_request(discovery, path, method=None, service=None, user=None, site=None):
    filters = validate_filters(discovery, path, method, service, user, site)
    names = [filters["service"]] if filters["service"] else list(discovery.services)
    if not 1 <= len(names) <= 32:
        raise ValueError("diagnosis requires 1–32 configured services")
    reports, rows, warnings = [], [], set()
    with ThreadPoolExecutor(max_workers=min(8, len(names))) as pool:
        for report, observed, issues in pool.map(lambda name: _service(discovery, name, filters["path"]), names):
            reports.append(report)
            rows.extend(observed)
            warnings.update(issues)
    if len(rows) > MAX_SUMMARIES:
        warnings.add("summary_limit")
    requests = _group(rows[:MAX_SUMMARIES], warnings)
    matching = [request for request in requests if any(
        segment["channel"] == "HTTP" and segment["path"] == filters["path"]
        and all(not filters[key] or segment[key] == filters[key] for key in ("method", "service", "user", "site"))
        for segment in request["segments"])]
    checks = [check for report in reports for check in report["checks"]]
    result = {"schemaVersion": 1, "kind": "guided-investigation", "scope": "retained-only",
              "filters": filters, "services": reports, "checks": checks,
              "status": "retained_matches" if matching else "no_retained_match",
              "candidates": [_candidate(request) for request in matching[:MAX_CANDIDATES]],
              "counts": {"loadedRequests": len(requests), "matchingRequests": len(matching)},
              "guidance": [
                  "Use the backend API path, not the browser page route. Matching is exact and case-sensitive.",
                  "HTTP prefix inclusion does not override built-in debug, health or static-resource exclusions; these can depend on the running adapter.",
                  "These checks read running metadata and retained summaries only; nothing was enabled, restarted or reconfigured.",
                  "Reproduce one matching request after explicitly arming a 60- or 120-second watch. Close or cancel the watch to stop it.",
                  "A saved capture is point-in-time evidence and can be partial; saving cannot recover already-evicted data.",
              ]}
    if len(matching) > MAX_CANDIDATES:
        warnings.add("candidate_display_limit")
    if any(check["status"] == "unknown" and check["id"] not in ("scope", "retention") for check in checks):
        warnings.add("diagnostic_evidence_unknown")
    # Bounded counts and strings normally fit; trim duplicated detail for a
    # maximum-size registry/path configuration without dropping check outcomes.
    if len(json.dumps(result, ensure_ascii=True).encode("ascii")) > MAX_REPORT_BYTES - 1024:
        warnings.add("report_detail_limit")
        for check in checks:
            check["details"].pop("httpIncludePaths", None)
    result.update(warnings=sorted(warnings), partial=bool(warnings),
                  limited=any("limit" in item for item in warnings))
    result["counts"]["candidatesReturned"] = len(result["candidates"])
    return result
