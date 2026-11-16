"""Explicit, local, payload-free request evidence and conservative comparison.

Nothing here changes recording or adds a collector. Files are private, bounded,
operator-owned snapshots; labels/revisions are supplied by the developer, not
proof of the build currently running. Absence is never proof of dead code.
"""

from __future__ import annotations

from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
try:
    import fcntl
except ImportError:  # Reading/recording traces remains usable on non-POSIX hosts.
    fcntl = None
import json
import math
import os
from pathlib import Path
import re
import stat
import urllib.error
import urllib.parse
import urllib.request
import uuid

from .client import DebugApiError, TraceLensClient
from .dependencies import boundary_summary, destination, without_sql
from .execution import build_segment_execution


MAX_CAPTURES = 20
MAX_CAPTURE_BYTES = 5 * 1024 * 1024
MAX_STORE_BYTES = 20 * 1024 * 1024
MAX_SEGMENTS = 16
MAX_EVENTS = 100_000
MAX_METHODS = 2000
MAX_LINES = 10_000
MAX_DEPENDENCIES = 500
MAX_API_BYTES = 8 * 1024 * 1024
MAX_FETCH_BYTES = 32 * 1024 * 1024
MAX_API_REQUESTS = 64
MAX_COMPARISON_BYTES = 64 * 1024
MAX_CHANGES = 200
_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
_IDENTIFIER = re.compile(r"[A-Za-z_$][A-Za-z0-9_.$]{0,255}\Z")
_SERVICE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\Z")
_REVISION = re.compile(r"[A-Za-z0-9][A-Za-z0-9_./-]{0,127}\Z")
_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
LIMITATIONS = [
    "Observed retained evidence only; uninstrumented code, sampling, eviction and silent global event drops may be absent without a flag. Completeness is never proven.",
    "Methods lack descriptors/invocation IDs; overloads can be combined. Lines are class-level compiled coordinates; line shifts are not code deletion.",
    "Nesting is a bounded observed prefix using thread names, not a complete/direct call graph or inferred asynchronous causality.",
    "Only explicit local snapshots persist. They are not atomic across services and do not provide historical coverage or prove dead code or a safe change.",
    "Payloads, headers, raw exception messages, source text, user/context fields and thread names are excluded. Paths, destinations, code identifiers and developer labels can still be sensitive.",
    "Revision is a developer-supplied label, not verified runtime build identity. Use the same scenario, inputs, scope and recording settings for a meaningful comparison.",
    "Local duration samples include waits and instrumentation; one before/after request is not a performance benchmark.",
]


def _bytes(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False,
                      separators=(",", ":"), sort_keys=True).encode("ascii")


def _matches(value, pattern):
    return value if isinstance(value, str) and pattern.fullmatch(value) else None


def _integer(value, minimum=0, maximum=2 ** 63 - 1):
    return value if type(value) is int and minimum <= value <= maximum else None


def _number(value):
    # Range-check Python's arbitrary-size integers before float conversion.
    return value if type(value) in (int, float) and 0 <= value <= 1e15 and math.isfinite(value) else None


def validate_capture_inputs(trace_id, label, revision=None):
    """Validate user input before discovery/network or filesystem writes."""
    if not _matches(trace_id, _ID):
        raise ValueError("trace_id must contain 1–128 ASCII letters, digits, underscores or hyphens")
    if (not isinstance(label, str) or not 1 <= len(label) <= 80
            or not label.strip() or not label.isprintable()):
        raise ValueError("capture label must contain 1–80 printable characters and not be blank")
    if revision is not None and not _matches(revision, _REVISION):
        raise ValueError("revision must contain 1–128 ASCII letters, digits, dots, slashes, underscores or hyphens and start with a letter or digit")


def _path(value):
    if not isinstance(value, str) or len(value) > 2048 or not value.startswith("/"):
        return None
    value = value.split("?", 1)[0].split("#", 1)[0]
    if len(value) > 512 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        return None
    return value


def _http_status(value):
    if isinstance(value, str) and re.fullmatch(r"[1-5][0-9]{2}", value):
        value = int(value)
    return _integer(value, 100, 599)


def _request(row):
    attrs = row.get("attributes")
    attrs = attrs if isinstance(attrs, dict) else {}
    method = row.get("httpMethod") or attrs.get("httpMethod")
    return {"method": method if method in ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT") else None,
            "path": _path(row.get("path") or attrs.get("path")),
            "status": row.get("status") if row.get("status") in ("ACTIVE", "COMPLETED", "ERROR", "FAILED", "TIMED_OUT", "TIMEOUT") else None,
            "httpStatus": _http_status(row.get("httpStatus") or attrs.get("httpStatus")),
            "durationMs": _number(row.get("durationMs"))}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _Reader:
    """Bound bytes before JSON parsing, including aggregate network consumption."""
    def __init__(self):
        self.remaining = MAX_FETCH_BYTES
        self.requests = 0

    def read(self, client, operation, argument):
        if self.requests >= MAX_API_REQUESTS or self.remaining <= 0:
            raise ValueError("capture_network_budget")
        self.requests += 1
        cap = min(MAX_API_BYTES, self.remaining)
        if isinstance(client, TraceLensClient):
            suffix = ("/find-by-trace-id/" + urllib.parse.quote(argument, safe="")
                      if operation == "find_by_trace_id" else "/traces" if operation == "traces"
                      else "/export/" + urllib.parse.quote(argument, safe="") + "?evict=false")
            request = urllib.request.Request(client.base_url + "/tracelens" + suffix, method="GET")
            try:
                with urllib.request.build_opener(_NoRedirect()).open(request, timeout=min(client.timeout, 5.0)) as response:
                    raw = response.read(cap + 1)
            except urllib.error.HTTPError as exc:
                # Do not retain upstream error bodies, credentials, URLs or messages.
                exc.close()
                raise DebugApiError("capture upstream HTTP error", exc.code) from None
            except (urllib.error.URLError, TimeoutError, OSError):
                raise DebugApiError("capture upstream unavailable") from None
            self.remaining -= len(raw)
            if len(raw) > cap:
                raise ValueError("capture_response_byte_limit")
            try:
                result = json.loads(raw)
            except (ValueError, RecursionError):
                raise ValueError("capture_invalid_json") from None
        else:
            # Deliberate fake-client seam for deterministic tests. Production
            # Federation clients always use the bounded transport above.
            result = (client.export(argument, evict=False) if operation == "export"
                      else client.traces() if operation == "traces"
                      else client.find_by_trace_id(argument))
            raw = _bytes(result)
            self.remaining -= len(raw)
            if len(raw) > cap:
                raise ValueError("capture_response_byte_limit")
        if not isinstance(result, dict):
            raise ValueError("capture_invalid_document")
        return result


def _rows(counter, names, maximum):
    return [{**dict(zip(names, key)), "count": count}
            for key, count in sorted(counter.items(), key=lambda item: _bytes(item[0]))[:maximum]]


def _dependency(event):
    row = boundary_summary(event)
    if row is None:
        return None
    return {key: row.get(key) for key in (
        "boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind", "errorType")}


def _http_dependency(row):
    if not isinstance(row, dict):
        return None
    result = {"boundaryKind": "http", "system": "http", "direction": "outbound",
              "destination": destination(row.get("peer")), "operation": _request(row)["method"],
              "path": _path(row.get("path")), "httpStatus": _http_status(row.get("httpStatus")),
              "status": row.get("status") if row.get("status") in ("ACTIVE", "COMPLETED", "ERROR", "ATTEMPTED") else None,
              "evidenceKind": "client-boundary", "errorType": _matches(row.get("errorType"), _IDENTIFIER)}
    return result


def _project_segment(service, row, remaining_events):
    warnings = set()
    parent_segment = _matches(row.get("parentSegmentId"), _UUID)
    parent_span = _matches(row.get("parentSpanId"), re.compile(r"[0-9a-f]{16}\Z"))
    if ((row.get("parentSegmentId") is not None and parent_segment is None)
            or (row.get("parentSpanId") is not None and parent_span is None)):
        warnings.add("invalid_parent_identity")
    events = row.get("events")
    if not isinstance(events, list):
        warnings.add("missing_events")
        events = []
    available = len(events)
    events = events[:remaining_events]
    if available > len(events):
        warnings.add("event_processing_limit")
    if row.get("eventCount") != available:
        warnings.add("event_count_snapshot_mismatch")
    if row.get("truncated") is True:
        warnings.add("agent_segment_truncated")
    if row.get("httpCallsTruncated") is True:
        warnings.add("agent_http_calls_truncated")
    if row.get("status") == "ACTIVE":
        warnings.add("active_snapshot")
    methods, lines, exceptions, dependencies = Counter(), Counter(), Counter(), Counter()
    previous_seq = None
    for event in events:
        if not isinstance(event, dict):
            warnings.add("malformed_event")
            continue
        seq = _integer(event.get("seq"), 1)
        if seq is None or (previous_seq is None and seq != 1) or (previous_seq is not None and seq != previous_seq + 1):
            warnings.add("sequence_missing_gap_or_reordering")
        previous_seq = seq
        cls = _matches(event.get("className"), _IDENTIFIER)
        method = _matches(event.get("methodName"), _IDENTIFIER)
        kind = event.get("type")
        if kind in ("METHOD_ENTER", "METHOD_EXIT", "EXCEPTION") and (not cls or not method):
            warnings.add("invalid_code_identity_omitted")
        if kind == "LINE" and (not cls or _integer(event.get("line"), 1, 2 ** 31 - 1) is None):
            warnings.add("invalid_line_identity_omitted")
        if kind == "METHOD_ENTER" and cls and method:
            if (cls, method) in methods or len(methods) < MAX_METHODS:
                methods[cls, method] += 1
            else:
                warnings.add("method_identity_limit")
        elif kind == "LINE" and cls and _integer(event.get("line"), 1, 2 ** 31 - 1):
            key = (cls, event["line"])
            if key in lines or len(lines) < MAX_LINES:
                lines[key] += 1
            else:
                warnings.add("line_identity_limit")
        elif kind == "EXCEPTION":
            attrs = event.get("attributes")
            exc = _matches(attrs.get("exceptionType"), _IDENTIFIER) if isinstance(attrs, dict) else None
            if exc:
                if (exc,) in exceptions or len(exceptions) < MAX_METHODS:
                    exceptions[exc,] += 1
                else:
                    warnings.add("exception_identity_limit")
            else:
                warnings.add("invalid_exception_type_omitted")
        boundary = _dependency(event)
        if boundary:
            key = _bytes(boundary).decode("ascii")
            if key in dependencies or len(dependencies) < MAX_DEPENDENCIES:
                dependencies[key] += 1
            else:
                warnings.add("dependency_identity_limit")
    http_calls = row.get("httpCalls", [])
    if not isinstance(http_calls, list):
        http_calls = []
        warnings.add("malformed_http_calls")
    if len(http_calls) > MAX_DEPENDENCIES:
        warnings.add("http_processing_limit")
    for raw in http_calls[:MAX_DEPENDENCIES]:
        call = _http_dependency(raw)
        if call:
            key = _bytes(call).decode("ascii")
            if key in dependencies or len(dependencies) < MAX_DEPENDENCIES:
                dependencies[key] += 1
            else:
                warnings.add("dependency_identity_limit")
    # Reuse the existing conservative reconstruction, then discard names of
    # threads, raw messages, arbitrary node text and all runtime identity fields.
    model = build_segment_execution(without_sql({"events": events, "eventCount": len(events)}), max_nodes=500)
    warnings.update("nesting_" + item["code"] for item in model["warnings"])
    nodes = {node["id"]: node for node in model["nodes"] if node["kind"] == "invocation"}
    nesting = Counter()
    for node in nodes.values():
        parent = nodes.get(node.get("parentId"))
        if parent:
            key = (parent.get("className"), parent.get("methodName"), node.get("className"), node.get("methodName"))
            if all(_matches(value, _IDENTIFIER) for value in key):
                nesting[key] += 1
    return {"service": service, "serviceInstanceId": row["serviceInstanceId"],
            "segmentId": row["segmentId"],
            "spanId": row.get("spanId") if _matches(row.get("spanId"), re.compile(r"[0-9a-f]{16}\Z")) else None,
            "parentSpanId": parent_span, "parentSegmentId": parent_segment,
            "request": _request(row), "eventsAvailable": available, "eventsProcessed": len(events),
            "methods": _rows(methods, ("className", "methodName"), MAX_METHODS),
            "lines": _rows(lines, ("className", "line"), MAX_LINES),
            "exceptions": _rows(exceptions, ("exceptionType",), MAX_METHODS),
            "nesting": _rows(nesting, ("parentClass", "parentMethod", "className", "methodName"), 500),
            "dependencies": [{**json.loads(key), "count": value} for key, value in sorted(dependencies.items())],
            "warnings": sorted(warnings), "partial": bool(warnings)}


def build_capture(discovery, trace_id: str, label: str, revision: str | None = None) -> dict:
    """Capture retained evidence once, without eviction or application writes."""
    validate_capture_inputs(trace_id, label, revision)
    services = discovery.services
    if not isinstance(services, dict) or not 1 <= len(services) <= 32:
        raise ValueError("capture requires a finite configured Federation")
    if any(not _matches(name, _SERVICE) for name in services):
        raise ValueError("capture requires valid configured service names")
    capture = {"schemaVersion": 1, "kind": "request-capture", "captureId": str(uuid.uuid4()),
               "label": label, "revision": revision, "traceId": trace_id,
               "createdAt": datetime.now(timezone.utc).isoformat(), "segments": [],
               "warnings": [], "partial": False, "limitations": list(LIMITATIONS)}
    warnings, reader, remaining = set(), _Reader(), MAX_EVENTS
    for service in sorted(services):
        try:
            client = discovery.clients[service]
            try:
                found = reader.read(client, "find_by_trace_id", trace_id)
            except DebugApiError as exc:
                if exc.status != 404:
                    raise
                found = reader.read(client, "traces", None)
            rows = found.get("traces")
            if not isinstance(rows, list):
                raise ValueError("capture_invalid_summaries")
            if found.get("limited") or found.get("partial"):
                warnings.add(service + ":summary_incomplete")
            seen = set()
            for summary in rows:
                if not isinstance(summary, dict) or summary.get("traceId") != trace_id:
                    continue
                if len(capture["segments"]) >= MAX_SEGMENTS:
                    warnings.add("segment_limit")
                    break
                segment_id = _matches(summary.get("segmentId"), _UUID)
                instance = _matches(summary.get("serviceInstanceId"), _ID)
                if not segment_id or not instance:
                    warnings.add(service + ":invalid_segment_identity")
                    continue
                if (segment_id, instance) in seen:
                    warnings.add(service + ":duplicate_segment_summary")
                    continue
                seen.add((segment_id, instance))
                try:
                    row = reader.read(client, "export", segment_id)
                    if row.get("traceId") != trace_id or row.get("segmentId") != segment_id or row.get("serviceInstanceId") != instance:
                        raise ValueError("capture_instance_or_trace_changed")
                    segment = _project_segment(service, row, remaining)
                    remaining -= segment["eventsProcessed"]
                    capture["segments"].append(segment)
                    if len(_bytes(capture)) > MAX_CAPTURE_BYTES - 16384:
                        capture["segments"].pop()
                        warnings.add("capture_byte_limit")
                except (DebugApiError, OSError, ValueError, RecursionError):
                    warnings.add(service + ":segment_unavailable_or_invalid")
        except (DebugApiError, OSError, ValueError, RecursionError):
            warnings.add(service + ":discovery_unavailable_or_invalid")
    if not capture["segments"]:
        raise ValueError("No retained segments could be captured; reproduce the request and check service availability and instance identity")
    roots = [row for row in capture["segments"] if row["parentSpanId"] is None and row["parentSegmentId"] is None]
    if len(roots) != 1:
        warnings.add("root_missing_or_ambiguous")
    capture["warnings"] = sorted(warnings)
    capture["partial"] = bool(warnings) or any(row["partial"] for row in capture["segments"])
    return capture


def _metadata(capture, size):
    return {key: capture[key] for key in ("captureId", "label", "revision", "traceId", "createdAt", "partial")} | {
        "segmentCount": len(capture["segments"]), "sizeBytes": size}


def _validate_capture(capture):
    """Reject unexpected fields: callers cannot smuggle raw exports into files."""
    top = {"schemaVersion", "kind", "captureId", "label", "revision", "traceId", "createdAt", "segments", "warnings", "partial", "limitations"}
    if not isinstance(capture, dict) or set(capture) != top or capture["schemaVersion"] != 1 or capture["kind"] != "request-capture":
        raise ValueError("invalid capture schema")
    validate_capture_inputs(capture["traceId"], capture["label"], capture["revision"])
    if not _matches(capture["captureId"], _UUID):
        raise ValueError("invalid capture id")
    if not isinstance(capture["createdAt"], str) or len(capture["createdAt"]) > 40:
        raise ValueError("invalid capture timestamp")
    try:
        stamp = datetime.fromisoformat(capture["createdAt"])
        if stamp.tzinfo is None:
            raise ValueError()
    except ValueError:
        raise ValueError("invalid capture timestamp") from None
    if capture["limitations"] != LIMITATIONS or type(capture["partial"]) is not bool:
        raise ValueError("invalid capture evidence limits")
    def check_warnings(values):
        if (not isinstance(values, list) or len(values) > 256 or
                any(not isinstance(v, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", v) for v in values)):
            raise ValueError("invalid capture warnings")
    check_warnings(capture["warnings"])
    segments = capture["segments"]
    if not isinstance(segments, list) or not 1 <= len(segments) <= MAX_SEGMENTS:
        raise ValueError("invalid capture segments")
    for row in segments:
        expected = {"service", "serviceInstanceId", "segmentId", "spanId", "parentSpanId", "parentSegmentId", "request", "eventsAvailable", "eventsProcessed", "methods", "lines", "exceptions", "nesting", "dependencies", "warnings", "partial"}
        if not isinstance(row, dict) or set(row) != expected:
            raise ValueError("invalid capture segment schema")
        if (not _matches(row["service"], _SERVICE) or not _matches(row["serviceInstanceId"], _ID)
                or not _matches(row["segmentId"], _UUID)):
            raise ValueError("invalid capture segment identity")
        if any(value is not None and not _matches(value, re.compile(r"[0-9a-f]{16}\Z")) for value in (row["spanId"], row["parentSpanId"])):
            raise ValueError("invalid capture span identity")
        if row["parentSegmentId"] is not None and not _matches(row["parentSegmentId"], _UUID):
            raise ValueError("invalid capture parent segment identity")
        request = row["request"]
        if not isinstance(request, dict) or set(request) != {"method", "path", "status", "httpStatus", "durationMs"} or _request({**request, "httpMethod": request.get("method")}) != request:
            raise ValueError("invalid capture request metadata")
        if _integer(row["eventsAvailable"]) is None or _integer(row["eventsProcessed"], 0, MAX_EVENTS) is None or row["eventsProcessed"] > row["eventsAvailable"]:
            raise ValueError("invalid capture event counts")
        check_warnings(row["warnings"])
        if type(row["partial"]) is not bool or row["partial"] != bool(row["warnings"]):
            raise ValueError("invalid capture partial flag")
        for category, keys, maximum in (("methods", {"className", "methodName"}, MAX_METHODS),
                                        ("lines", {"className", "line"}, MAX_LINES),
                                        ("exceptions", {"exceptionType"}, MAX_METHODS),
                                        ("nesting", {"parentClass", "parentMethod", "className", "methodName"}, 500)):
            values = row[category]
            if not isinstance(values, list) or len(values) > maximum:
                raise ValueError("capture evidence limit")
            for value in values:
                if not isinstance(value, dict) or set(value) != keys | {"count"} or _integer(value["count"], 1, MAX_EVENTS) is None:
                    raise ValueError("invalid capture evidence")
                if any((_integer(value[key], 1, 2 ** 31 - 1) is None if key == "line" else not _matches(value[key], _IDENTIFIER)) for key in keys):
                    raise ValueError("invalid capture code identity")
        if not isinstance(row["dependencies"], list) or len(row["dependencies"]) > MAX_DEPENDENCIES:
            raise ValueError("capture dependency limit")
        for value in row["dependencies"]:
            if not isinstance(value, dict) or _integer(value.get("count"), 1, MAX_EVENTS + MAX_DEPENDENCIES) is None:
                raise ValueError("invalid capture dependency")
            raw = {key: v for key, v in value.items() if key != "count"}
            if raw.get("boundaryKind") == "http":
                projected = _http_dependency({**raw, "peer": raw.get("destination"), "httpMethod": raw.get("operation")})
            elif raw.get("boundaryKind") == "database-route":
                projected = _dependency({"type": "DB_ROUTE", "attributes": {"route": raw.get("destination")}})
            else:
                projected = _dependency({"type": "EXTERNAL_CALL", "attributes": raw})
            if projected != raw:
                raise ValueError("invalid capture dependency metadata")
    if sum(row["eventsProcessed"] for row in segments) > MAX_EVENTS:
        raise ValueError("capture event processing limit")
    if capture["partial"] != (bool(capture["warnings"]) or any(row["partial"] for row in segments)):
        raise ValueError("invalid capture partial flag")
    if len(_bytes(capture)) > MAX_CAPTURE_BYTES:
        raise ValueError("capture exceeds 5 MiB")


class CaptureStore:
    """Private POSIX file store. Never overwrite or automatically remove captures."""
    def __init__(self, root: str | Path):
        self.root = Path(os.path.abspath(os.path.expanduser(str(root))))

    @contextmanager
    def _directory(self, create=False):
        if fcntl is None or not hasattr(os, "O_NOFOLLOW"):
            raise ValueError("private capture storage currently requires a POSIX filesystem")
        # Walk with dir_fd/O_NOFOLLOW: reject links in every path component,
        # including races replacing a checked directory before it is opened.
        fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
        try:
            for component in self.root.parts[1:]:
                if create:
                    try:
                        os.mkdir(component, 0o700, dir_fd=fd)
                    except FileExistsError:
                        pass
                next_fd = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = next_fd
            info = os.fstat(fd)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
                raise ValueError("capture directory must be owned by you and private (mode 0700)")
            yield fd
        finally:
            os.close(fd)

    @contextmanager
    def _lock(self, directory, write=True):
        try:
            flags = (os.O_RDWR if write else os.O_RDONLY) | os.O_NOFOLLOW | os.O_NONBLOCK
            if write:
                # macOS can return ENOENT to concurrent O_CREAT|O_NOFOLLOW
                # callers even when another caller just created the file.
                # Separate exclusive creation from opening an existing inode;
                # only EEXIST is recoverable, never a missing/replaced path.
                try:
                    fd = os.open(".lock", flags | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=directory)
                except FileExistsError:
                    fd = os.open(".lock", flags, dir_fd=directory)
            else:
                fd = os.open(".lock", flags, dir_fd=directory)
        except FileNotFoundError:
            if write:
                raise
            yield  # A manually copied store need not have a lock yet.
            return
        try:
            self._private_file(fd)
            fcntl.flock(fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            yield
        finally:
            os.close(fd)

    @staticmethod
    def _private_file(fd, paired_pending=False):
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) & 0o077
                or info.st_nlink != (2 if paired_pending else 1)):
            raise ValueError("capture files must be private, owned regular files without links")
        return info

    @staticmethod
    def _pending_name(name):
        return name.startswith(".pending-") and _matches(name[len(".pending-"):], _UUID)

    def _paired_pending(self, directory, info):
        """Recognize only the two-name crash window of our atomic publication.

        A committed JSON and its one same-inode pending name are safe to read;
        no outside hardlink can exist when st_nlink is exactly two. No cleanup
        is inferred or performed by reads. Interrupted files consume quota.
        """
        if info.st_nlink != 2:
            return False
        matches, examined = 0, 0
        with os.scandir(directory) as entries:
            for entry in entries:
                if self._pending_name(entry.name):
                    examined += 1
                    if examined > MAX_CAPTURES:
                        raise ValueError("too many interrupted capture files; review them manually")
                    other = entry.stat(follow_symlinks=False)
                    if (stat.S_ISREG(other.st_mode) and other.st_dev == info.st_dev
                            and other.st_ino == info.st_ino):
                        matches += 1
        return matches == 1

    def _pending_usage(self, directory):
        count, total = 0, 0
        with os.scandir(directory) as entries:
            for entry in entries:
                if not self._pending_name(entry.name):
                    continue
                count += 1
                if count > MAX_CAPTURES:
                    raise ValueError("too many interrupted capture files; review them manually")
                info = entry.stat(follow_symlinks=False)
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                        or stat.S_IMODE(info.st_mode) & 0o077 or info.st_nlink not in (1, 2)):
                    raise ValueError("unsafe interrupted capture file; review it manually")
                # Conservatively count even a paired inode twice: storage
                # limits must not be bypassed by crashes before/after publish.
                total += info.st_size
        return count, total

    def _names(self, directory):
        names = []
        with os.scandir(directory) as entries:
            for entry in entries:
                if entry.name.endswith(".json") and _matches(entry.name[:-5], _UUID):
                    names.append(entry.name)
                    if len(names) > MAX_CAPTURES:
                        raise ValueError("capture store exceeds the 20-capture limit; move unwanted captures manually")
        return sorted(names)

    def _read(self, directory, name):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            info = self._private_file(stream.fileno(), self._paired_pending(directory, info))
            if info.st_size > MAX_CAPTURE_BYTES:
                raise ValueError("capture file exceeds 5 MiB")
            raw = stream.read(MAX_CAPTURE_BYTES + 1)
        if len(raw) > MAX_CAPTURE_BYTES:
            raise ValueError("capture file exceeds 5 MiB")
        try:
            capture = json.loads(raw)
            _validate_capture(capture)
        except (ValueError, TypeError, RecursionError):
            raise ValueError("invalid or unsupported capture file") from None
        if capture["captureId"] + ".json" != name:
            raise ValueError("capture file identity does not match filename")
        return capture, len(raw)

    def save(self, capture):
        _validate_capture(capture)
        raw = _bytes(capture)
        name = capture["captureId"] + ".json"
        with self._directory(create=True) as directory, self._lock(directory):
            names = self._names(directory)
            pending_count, pending_bytes = self._pending_usage(directory)
            if name in names:
                raise ValueError("capture already exists; existing captures are never overwritten")
            if len(names) + pending_count >= MAX_CAPTURES:
                raise ValueError("capture limit reached (20); move unwanted captures manually")
            total = pending_bytes + sum(self._read(directory, existing)[1] for existing in names)
            if total + len(raw) > MAX_STORE_BYTES:
                raise ValueError("capture storage limit reached (20 MiB); move unwanted captures manually")
            temporary = ".pending-" + str(uuid.uuid4())
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                # Atomic publish without overwrite. A crash can leave only an
                # ignored private pending file, never a partially written capture.
                os.link(temporary, name, src_dir_fd=directory, dst_dir_fd=directory, follow_symlinks=False)
                os.unlink(temporary, dir_fd=directory)
                os.fsync(directory)
            finally:
                try:
                    os.unlink(temporary, dir_fd=directory)
                except FileNotFoundError:
                    pass
        return _metadata(capture, len(raw))

    def list(self):
        rows = []
        pending_count, pending_bytes = 0, 0
        try:
            with self._directory() as directory:
                with self._lock(directory, write=False):
                    pending_count, pending_bytes = self._pending_usage(directory)
                    for name in self._names(directory):
                        capture, size = self._read(directory, name)
                        rows.append(_metadata(capture, size))
        except FileNotFoundError:
            pass
        rows.sort(key=lambda row: (row["createdAt"], row["captureId"]), reverse=True)
        return {"captures": rows, "count": len(rows), "limits": {"maxCaptures": MAX_CAPTURES,
                "maxCaptureBytes": MAX_CAPTURE_BYTES, "maxTotalBytes": MAX_STORE_BYTES},
                "totalBytes": pending_bytes + sum(row["sizeBytes"] for row in rows),
                "pendingFiles": pending_count, "pendingBytes": pending_bytes,
                "warnings": ["Interrupted capture files count toward quota; review/move them manually. No automatic deletion is performed."] if pending_count else []}

    def get(self, capture_id):
        if not _matches(capture_id, _UUID):
            raise ValueError("capture_id must be a canonical lowercase UUID, not a file path")
        try:
            with self._directory() as directory:
                with self._lock(directory, write=False):
                    return self._read(directory, capture_id + ".json")[0]
        except FileNotFoundError:
            raise ValueError("capture not found") from None


def _aggregate(capture, category):
    counts = Counter()
    for segment in capture["segments"]:
        for row in segment[category]:
            key = _bytes({"service": segment["service"], **{k: v for k, v in row.items() if k != "count"}}).decode("ascii")
            counts[key] += row["count"]
    return counts


def _delta(before, after):
    result = {"added": [], "noLongerObserved": [], "countChanged": [], "totals": {}}
    sets = (("added", sorted(after.keys() - before.keys())),
            ("noLongerObserved", sorted(before.keys() - after.keys())),
            ("countChanged", sorted(key for key in before.keys() & after.keys() if before[key] != after[key])))
    for name, keys in sets:
        result["totals"][name] = len(keys)
        result[name] = [{**json.loads(key), "beforeCount": before[key], "afterCount": after[key]}
                        for key in keys[:MAX_CHANGES]]
    result["unchangedIdentities"] = sum(before[key] == after[key] for key in before.keys() & after.keys())
    return result


def compare_captures(before: dict, after: dict) -> dict:
    """Compare observed counters, never claim deleted code or production safety."""
    _validate_capture(before)
    _validate_capture(after)
    warnings = {"scope_and_inputs_not_verified", "observations_do_not_prove_coverage"}
    if before["partial"] or after["partial"]:
        warnings.add("partial_capture_evidence")
    if not before["revision"] or not after["revision"]:
        warnings.add("runtime_build_labels_missing")
    if Counter(s["service"] for s in before["segments"]) != Counter(s["service"] for s in after["segments"]):
        warnings.add("service_or_segment_counts_differ")
    scenarios = lambda capture: {(s["service"], s["request"]["method"], s["request"]["path"]) for s in capture["segments"]}
    if scenarios(before) != scenarios(after):
        warnings.add("request_paths_or_methods_differ")
    result = {"schemaVersion": 1, "kind": "capture-comparison", "completeness": "observed-only",
              "before": _metadata(before, len(_bytes(before))), "after": _metadata(after, len(_bytes(after))),
              "warnings": sorted(warnings), "limitations": list(LIMITATIONS),
              "partial": bool(before["partial"] or after["partial"]), "limited": False,
              "maxBytes": MAX_COMPARISON_BYTES}
    result["evidenceWarnings"] = {}
    for side, capture in (("before", before), ("after", after)):
        result["evidenceWarnings"][side] = {
            "warnings": capture["warnings"][:8], "warningsOmitted": max(0, len(capture["warnings"]) - 8),
            "segments": [{"service": row["service"], "segmentId": row["segmentId"],
                          "warnings": row["warnings"][:4], "warningsOmitted": max(0, len(row["warnings"]) - 4)}
                         for row in capture["segments"] if row["warnings"]]}
    for category in ("methods", "lines", "nesting", "dependencies", "exceptions"):
        result[category] = _delta(_aggregate(before, category), _aggregate(after, category))
    def outcomes(capture):
        counts = Counter()
        for segment in capture["segments"]:
            row = {"service": segment["service"], **{k: v for k, v in segment["request"].items() if k != "durationMs"}}
            counts[_bytes(row).decode("ascii")] += 1
        return counts
    result["outcomes"] = _delta(outcomes(before), outcomes(after))
    result["durationSamples"] = {name: [{"service": s["service"], **s["request"]} for s in capture["segments"]]
                                 for name, capture in (("before", before), ("after", after))}
    categories = ("methods", "lines", "nesting", "dependencies", "exceptions", "outcomes")
    def finish():
        for category in categories:
            row = result[category]
            row["returned"] = sum(len(row[key]) for key in ("added", "noLongerObserved", "countChanged"))
            row["omitted"] = sum(row["totals"].values()) - row["returned"]
        result["limited"] = any(result[key]["omitted"] for key in categories) or any(
            details["warningsOmitted"] or any(row["warningsOmitted"] for row in details["segments"])
            for details in result["evidenceWarnings"].values())
    finish()
    # Largest list first keeps every category represented under the global byte
    # bound. At most 3600 small rows, so pruning remains predictably bounded.
    while len(_bytes(result)) > MAX_COMPARISON_BYTES:
        candidates = [(len(result[category][key]), category, key) for category in categories
                      for key in ("added", "noLongerObserved", "countChanged") if result[category][key]]
        if not candidates:
            raise ValueError("comparison metadata exceeds byte limit")
        _, category, key = max(candidates)
        values = result[category][key]
        del values[max(0, len(values) - max(1, len(values) // 4)):]
        finish()
    return result
