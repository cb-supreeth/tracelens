"""Bounded, read-only setup diagnostics for the configured service registry.

Readiness means the basic capture plumbing reports healthy, not that an API,
every class, line number, or remote service has been observed. A report with
``partial=True`` has unavailable or unknown diagnostic evidence. Known unhealthy
settings can make ``ready=False`` without making the evidence partial.
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .client import DebugApiError, TraceLensClient
from .services import Federation, Service


MAX_RESPONSE_BYTES = 65536
MAX_REPORT_BYTES = 96 * 1024
MAX_WORKERS = 8
MAX_TIMEOUT_SECONDS = 5.0
MAX_INTEGER = (1 << 63) - 1
_SUMMARY_FIELDS = {"agentActive", "recordingEnabled", "traceCount", "totalEvents", "bridge"}
_METADATA_FIELDS = {"agentActive", "packages", "idleTtlMinutes", "trackMethods", "applicationId"}
_CORE_CHECKS = {"debug_api", "agent", "recording", "sink", "instrumentation", "scope"}
_PORT_GUIDANCE = (
    "Use the TraceLens debug base URL, not the application UI URL. With the repository's "
    "standalone examples cb-app uses port 7778 and cb-ems 7779; legacy adapters may expose "
    "/tracelens on the application port. Confirm your configured debug port."
)
_SCOPE_GUIDANCE = (
    "Reproduce one API request inside the configured package scope. Check package exclusions, "
    "HTTP entry-path filters and requireLabels. For missing line events, compile the running "
    "classes with line-number debug information and use the matching source checkout. "
    "Restart the service after changing agent configuration or bytecode."
)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A diagnostic must not silently probe a URL outside the selected registry.
        return None


def _integer(value, *, positive=False):
    if type(value) is int and (1 if positive else 0) <= value <= MAX_INTEGER:
        return value
    return None


def _boolean(value):
    return value if type(value) is bool else None


def _endpoint(url):
    """Never echo credentials, arbitrary path values, query strings or fragments."""
    try:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            return "configured debug endpoint"
        host = parsed.hostname
        if ":" in host:
            host = "[" + host + "]"
        port = ":" + str(parsed.port) if parsed.port is not None else ""
        result = parsed.scheme + "://" + host + port
        return result if len(result) <= 256 and not any(ord(c) < 32 for c in result) else "configured debug endpoint"
    except (TypeError, ValueError):
        return "configured debug endpoint"


def _bounded_read(client, endpoint):
    # Retain injectable fake clients for tests/embedding. The actual HTTP client
    # gets a local bounded reader: TraceLensClient's general reads are unbounded.
    if type(client) is not TraceLensClient:
        return getattr(client, endpoint)()
    parsed = urllib.parse.urlsplit(client.base_url)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment
            or any(ord(c) < 32 or c.isspace() for c in client.base_url)):
        raise ValueError("invalid debug base URL")
    parsed.port  # Reject malformed ports without exposing the supplied URL.
    request = urllib.request.Request(client.base_url + "/tracelens/" + endpoint, method="GET")
    timeout = client.timeout
    if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or not 0 < timeout <= MAX_TIMEOUT_SECONDS:
        timeout = MAX_TIMEOUT_SECONDS
    opener = urllib.request.build_opener(_NoRedirect())
    with opener.open(request, timeout=timeout) as response:
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise ValueError("debug response exceeds diagnostic byte limit")
        return json.loads(body.decode("utf-8"))


def _probe(client, endpoint):
    try:
        doc = _bounded_read(client, endpoint)
        expected = _SUMMARY_FIELDS if endpoint == "summary" else _METADATA_FIELDS
        if not isinstance(doc, dict) or not any(field in doc for field in expected):
            return {}, {"kind": "malformed", "httpStatus": None}
        return doc, None
    except (DebugApiError, urllib.error.HTTPError) as exc:
        status = _integer(getattr(exc, "status", None)) or _integer(getattr(exc, "code", None))
        if status is not None and not 100 <= status <= 599:
            status = None
        if isinstance(exc, urllib.error.HTTPError):
            exc.close()
        return {}, {"kind": "http" if status else "unreachable", "httpStatus": status}
    except (OSError, urllib.error.URLError):
        return {}, {"kind": "unreachable", "httpStatus": None}
    except (ValueError, TypeError, AttributeError, RecursionError):
        return {}, {"kind": "malformed", "httpStatus": None}


def _check(checks, check_id, status, message, fix=None, **details):
    result = {"id": check_id, "status": status, "message": message}
    if fix:
        result["fix"] = fix
    if details:
        result["details"] = details
    checks.append(result)


def _probe_check(checks, check_id, error):
    endpoint = "summary" if check_id == "debug_api" else "metadata"
    if error is None:
        _check(checks, check_id, "pass", "Read /tracelens/" + endpoint + ".")
        return
    status = error["httpStatus"]
    if status in (401, 403):
        fix = "Check the debug endpoint's access policy with its owner; do not disable authentication to make diagnostics pass."
    elif status == 404 and endpoint == "metadata":
        fix = "This may be an older adapter. Summary checks still work; use a compatible agent/adapter for complete metadata."
    else:
        fix = _PORT_GUIDANCE + " Start or restart the intended service with its TraceLens agent if it is not running."
    _check(checks, check_id, "fail" if check_id == "debug_api" else "unknown",
           "Could not inspect /tracelens/" + endpoint + "; no response body or exception details are exposed.",
           fix, **error)


def _source_check(checks, root):
    if root is None:
        _check(checks, "source", "warn", "No sourceRoot configured; recording does not require a source checkout.",
               "Set this service's sourceRoot to the matching local checkout for source navigation and line context.",
               configured=False)
        return
    try:
        path = Path(root)
        directory = path.is_absolute() and path.is_dir()
        readable = directory and os.access(path, os.R_OK | os.X_OK)
        if readable:
            # Open only the directory, without enumerating files or reading code.
            with os.scandir(path):
                pass
        _check(checks, "source", "pass" if readable else "fail",
               "Configured source directory is readable; build/source correspondence is not verified."
               if readable else "Configured sourceRoot is not an accessible local directory.",
               None if readable else "Point sourceRoot at an existing readable checkout for this service; no source files were changed.",
               configured=True, readable=bool(readable))
    except (OSError, ValueError, TypeError):
        _check(checks, "source", "fail", "Configured sourceRoot cannot be accessed.",
               "Check that sourceRoot is an absolute directory path and this process can read and traverse it.",
               configured=True, readable=False)


def _service_report(service: Service, client):
    summary, summary_error = _probe(client, "summary")
    metadata, metadata_error = _probe(client, "metadata")
    bridge = summary.get("bridge")
    bridge = bridge if isinstance(bridge, dict) else {}
    checks = []
    _probe_check(checks, "debug_api", summary_error)
    _probe_check(checks, "metadata", metadata_error)

    agent = _boolean(summary.get("agentActive"))
    metadata_agent = _boolean(metadata.get("agentActive"))
    if "agentActive" not in summary:
        agent = metadata_agent
    elif metadata_agent is not None and agent != metadata_agent:
        agent = None
    facts = {
        "agentActive": agent,
        "recordingEnabled": _boolean(summary.get("recordingEnabled")),
        "sinkRegistered": _boolean(bridge.get("sinkRegistered")),
        "instrumentedClasses": _integer(summary.get("instrumentedClasses")),
        "failedClasses": _integer(summary.get("failedClasses")),
        "traceCount": _integer(summary.get("traceCount")),
        "totalEvents": _integer(summary.get("totalEvents")),
        "maxTrackedRequests": _integer(summary.get("maxTrackedRequests"), positive=True),
        "maxTrackedEvents": _integer(summary.get("maxTrackedEvents"), positive=True),
        "idleTtlMinutes": _integer(metadata.get("idleTtlMinutes"), positive=True),
        "lineCaptureEnabled": _boolean(metadata.get("lineCaptureEnabled"))
        if "lineCaptureEnabled" in metadata else _boolean(summary.get("lineCaptureEnabled")),
        "requireLabels": _boolean(metadata.get("requireLabels")),
        "emitted": _integer(bridge.get("emitted")),
        "droppedNoSink": _integer(bridge.get("droppedNoSink")),
        "droppedNoTrace": _integer(bridge.get("droppedNoTrace")),
    }
    for check_id, field, healthy, fix in (
        ("agent", "agentActive", "Agent premain reports active; this does not prove coverage of the desired classes or API.",
         "Attach the built TraceLens agent with -javaagent to the service JVM and restart it; a debug adapter dependency alone does not weave bytecode."),
        ("recording", "recordingEnabled", "Recording is enabled for new eligible segments.",
         "Review the intended recording setting and enable recording through the approved service setup; doctor never changes it."),
        ("sink", "sinkRegistered", "The in-JVM event sink is registered.",
         "Check standalone startup or the legacy adapter's initialization. Use one store owner per JVM and restart with matching TraceLens artifacts."),
    ):
        value = facts[field]
        _check(checks, check_id, "pass" if value is True else "fail" if value is False else "unknown",
               healthy if value is True else field + " reports false." if value is False else field + " is unavailable, malformed, or inconsistent.",
               None if value is True else fix, value=value)

    transformed, failed = facts["instrumentedClasses"], facts["failedClasses"]
    instrumentation_status = "unknown" if transformed is None or failed is None else "warn" if failed > 0 or transformed == 0 else "pass"
    _check(checks, "instrumentation", instrumentation_status,
           "Main-transformer counters do not cover every specialized hook or prove line-number coverage.",
           None if instrumentation_status == "pass" else "Check agent startup diagnostics and package scope, then reproduce the target API. Zero transformed classes can mean the scoped classes have not loaded yet. " + _SCOPE_GUIDANCE,
           instrumentedClasses=transformed, failedClasses=failed)

    packages = metadata.get("packages")
    valid_packages = (isinstance(packages, list) and len(packages) <= 64
                      and all(isinstance(p, str) and len(p) <= 256
                              and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*\.?", p)
                              for p in packages))
    package_count = len(packages) if valid_packages else None
    if "lineCaptureEnabled" not in metadata and "lineCaptureEnabled" not in summary and valid_packages:
        facts["lineCaptureEnabled"] = bool(package_count)
    line_capture = facts["lineCaptureEnabled"]
    _check(checks, "scope", "pass" if line_capture is True else "warn" if line_capture is False else "unknown",
           "Line capture is configured; a matching request and compiled line tables are still required."
           if line_capture is True else "No line-capture package scope is configured." if line_capture is False
           else "Line-capture scope is unknown; no coverage is assumed.",
           None if line_capture is True else _SCOPE_GUIDANCE,
           lineCaptureEnabled=line_capture, packageCount=package_count)

    captures = facts["traceCount"], facts["totalEvents"]
    capture_status = "unknown" if None in captures else "warn" if 0 in captures else "pass"
    _check(checks, "captures", capture_status,
           "Retained segments and events exist; this does not prove the desired API or lines were captured."
           if capture_status == "pass" else "No retained segments or events yet; fresh or evicted stores are normal."
           if capture_status == "warn" else "Retained segment/event counts are unavailable or malformed.",
           None if capture_status == "pass" else _SCOPE_GUIDANCE, traceCount=captures[0], totalEvents=captures[1])

    for check_id, count_key, cap_key in (
        ("request_retention", "traceCount", "maxTrackedRequests"),
        ("event_retention", "totalEvents", "maxTrackedEvents"),
    ):
        count, cap = facts[count_key], facts[cap_key]
        status = "unknown" if count is None or cap is None else "warn" if count >= cap else "pass"
        _check(checks, check_id, status,
               "Retained " + ("segments" if check_id == "request_retention" else "events") + " are bounded in JVM memory, not a database.",
               "Capture/export the target request promptly, narrow recording scope, and review retention limits; do not raise memory limits blindly."
               if status == "warn" else "Check compatible summary retention fields; missing values are not treated as zero."
               if status == "unknown" else None, count=count, capacity=cap)
    ttl = facts["idleTtlMinutes"]
    _check(checks, "retention_ttl", "pass" if ttl is not None else "unknown",
           "TTL is based on trace activity; reading a trace does not keep it alive."
           if ttl is not None else "Activity-based retention TTL is unavailable or malformed.",
           None if ttl is not None else "Check idleTtlMinutes in the service's agent/adapter configuration.", minutes=ttl)
    if facts["requireLabels"] is True:
        _check(checks, "http_labels", "warn", "HTTP capture requires a reproduction label.",
               "Supply the configured tracelens-id request header when reproducing; UI filters do not enable recording.")
    _source_check(checks, service.source_root)

    instance_a, instance_b = summary.get("serviceInstanceId"), metadata.get("serviceInstanceId")
    changed_instance = (isinstance(instance_a, str) and isinstance(instance_b, str)
                        and instance_a != instance_b)
    if changed_instance:
        _check(checks, "instance", "unknown", "The service instance changed between diagnostic reads.",
               "Wait for the service restart to complete and run diagnostics again.")
    ready = all(c["status"] == "pass" for c in checks if c["id"] in _CORE_CHECKS) and not changed_instance
    partial = bool(summary_error or metadata_error or any(c["status"] == "unknown" for c in checks))
    service_name = service.name if isinstance(service.name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", service.name) else "configured-service"
    service_status = "unavailable" if summary_error else "attention" if any(c["status"] != "pass" for c in checks) else "ready"
    return {"service": service_name, "endpoint": _endpoint(service.url), "ready": ready, "status": service_status,
            "partial": partial, "facts": facts, "checks": checks}


def _compact_report(report):
    # One actionable instruction can apply to many services/checks. Reference it
    # instead of copying paragraphs up to 32 times into an MCP/CLI response.
    guidance = {}
    next_steps = []
    for service in report["services"]:
        for check in service["checks"]:
            fix = check.pop("fix", None)
            if fix:
                if fix not in guidance:
                    step_id = "step-" + str(len(next_steps) + 1)
                    guidance[fix] = step_id
                    next_steps.append({"id": step_id, "instruction": fix})
                check["fixId"] = guidance[fix]
    report["nextSteps"] = next_steps
    report["omissions"] = {"checkDetails": 0, "checkMessages": 0}

    def size():
        return len(json.dumps(report, ensure_ascii=True, allow_nan=False).encode("utf-8"))

    if size() > MAX_REPORT_BYTES:
        # Details duplicate most facts. Keep statuses and fixes for every
        # service, and preserve probe HTTP status/error kind as diagnostic facts.
        for service in report["services"]:
            for check in service["checks"]:
                if "details" in check and check["id"] not in ("debug_api", "metadata"):
                    del check["details"]
                    report["omissions"]["checkDetails"] += 1
    if size() > MAX_REPORT_BYTES:
        # A final bounded fallback still retains all measured facts, checks,
        # statuses and actionable guidance; only explanatory prose is omitted.
        for service in reversed(report["services"]):
            for check in reversed(service["checks"]):
                if "message" in check:
                    del check["message"]
                    report["omissions"]["checkMessages"] += 1
            if size() <= MAX_REPORT_BYTES:
                break
    return report


def run_doctor(discovery: Federation) -> dict:
    """Inspect a finite operator-owned registry without changing apps or traces.

    Every service gets two GET probes (summary and metadata), even if one fails.
    Response bodies/errors are never copied into the report. Real HTTP probes
    cap each response at 64 KiB, disallow redirects, and use at most a 5-second
    socket timeout. Source checks inspect only configured directory access.
    """
    services = list(discovery.services.values())
    if not 1 <= len(services) <= 32:
        raise ValueError("doctor requires 1–32 configured services")
    with ThreadPoolExecutor(max_workers=min(MAX_WORKERS, len(services))) as pool:
        reports = list(pool.map(lambda item: _service_report(item, discovery.clients[item.name]), services))
    ready = all(report["ready"] for report in reports)
    all_offline = all(next(c for c in report["checks"] if c["id"] == "debug_api")["status"] == "fail"
                      for report in reports)
    attention = any(c["status"] != "pass" for report in reports for c in report["checks"])
    return _compact_report({
        "schemaVersion": 1, "readOnly": True, "ready": ready,
        "partial": any(report["partial"] for report in reports),
        "status": "unavailable" if all_offline else "attention" if attention else "ready",
        "services": reports,
        "limits": {"maxServices": 32, "maxResponseBytes": MAX_RESPONSE_BYTES,
                   "maxReportBytes": MAX_REPORT_BYTES,
                   "maxWorkers": MAX_WORKERS, "socketTimeoutSeconds": MAX_TIMEOUT_SECONDS},
        "caveat": "This is a setup snapshot, not a performance benchmark, production-safety assessment, or proof of complete execution coverage.",
    })
