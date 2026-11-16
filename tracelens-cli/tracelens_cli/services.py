"""Read-only federation over an operator-owned, finite service registry.

This is live discovery, not a durable collector. Never accept upstream URLs or
source roots from browser/MCP arguments, and never evict traces while inspecting.
"""

from __future__ import annotations

import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

from .client import DebugApiError, TraceLensClient
from .dependencies import (MAX_EVENTS, MAX_EXTERNAL_CALLS, alias_for, extract_boundaries,
                           sanitize_summary, validate_aliases)


@dataclass(frozen=True)
class Service:
    name: str
    url: str
    source_root: str | None = None
    dependency_aliases: dict[str, str] = field(default_factory=dict)

    def __post_init__(self):
        object.__setattr__(self, "dependency_aliases", validate_aliases(self.dependency_aliases))


def _url_key(url: str):
    parsed = urlsplit(url)
    return (parsed.scheme.lower(), (parsed.hostname or "").lower(),
            parsed.port or (443 if parsed.scheme == "https" else 80), parsed.path.rstrip("/"))


def load_services(path: str) -> list[Service]:
    """Load trusted local configuration; validation prevents accidental bad targets."""
    config_path = Path(path)
    if config_path.stat().st_size > 65536:
        raise ValueError("services config exceeds 64 KiB")
    doc = json.loads(config_path.read_text(encoding="utf-8"))
    rows = doc.get("services") if isinstance(doc, dict) else None
    if not isinstance(rows, list) or not 1 <= len(rows) <= 32:
        raise ValueError("services config requires 1–32 services")
    result, names, urls = [], set(), set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("each service must be an object")
        name, url, root = row.get("name"), row.get("url"), row.get("sourceRoot")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", name):
            raise ValueError("service name must be 1–64 letters, digits, dots, underscores or hyphens")
        if name in names:
            raise ValueError(f"duplicate service name: {name}")
        if not isinstance(url, str) or len(url) > 2048 or any(c.isspace() or ord(c) < 32 for c in url):
            raise ValueError(f"invalid URL for {name}")
        parsed = urlsplit(url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment):
            raise ValueError(f"{name}: use an HTTP(S) base URL without credentials, query or fragment")
        try:
            parsed.port
        except ValueError as exc:
            raise ValueError(f"invalid URL port for {name}") from exc
        canonical = _url_key(url)
        if canonical in urls:
            raise ValueError(f"duplicate upstream URL for {name}; configure each endpoint once")
        if root is not None and (not isinstance(root, str) or len(root) > 4096
                                 or not Path(root).is_absolute() or "\x00" in root):
            raise ValueError(f"{name}: sourceRoot must be an absolute local path")
        names.add(name)
        urls.add(canonical)
        result.append(Service(name, url.rstrip("/"), root,
                              row.get("dependencyAliases", {})))
    return result


def _key(service: str, row: dict) -> str:
    # JSON encoding is unambiguous even for future instance identifiers with '/'.
    return json.dumps([service, row.get("serviceInstanceId"), row.get("segmentId")], separators=(",", ":"))


def _tag(service: Service, row: dict) -> dict:
    return {**row, "service": service.name, "segmentKey": _key(service.name, row)}


def _field(row: dict, name: str):
    return row.get(name) or row.get("attributes", {}).get(name)


class Federation:
    def __init__(self, services: list[Service]):
        if not services or len(services) > 32 or len({s.name for s in services}) != len(services):
            raise ValueError("registry requires 1–32 uniquely named services")
        if len({_url_key(s.url) for s in services}) != len(services):
            raise ValueError("configure each upstream endpoint only once")
        self.services = {s.name: s for s in services}
        self.clients = {s.name: TraceLensClient(s.url) for s in services}

    def _selected(self, name: str | None) -> list[Service]:
        if name is not None:
            if name not in self.services:
                raise ValueError(f"unknown configured service: {name}")
            return [self.services[name]]
        return list(self.services.values())

    def _read(self, operation, service: str | None = None):
        selected = self._selected(service)
        def read_one(item):
            try:
                return item, operation(item, self.clients[item.name]), None
            except (DebugApiError, ValueError, OSError) as exc:
                return item, None, {"service": item.name, "error": str(exc)}
        with ThreadPoolExecutor(max_workers=min(8, len(selected))) as pool:
            return list(pool.map(read_one, selected))

    def traces(self, service: str | None = None, method: str | None = None,
               path: str | None = None, user: str | None = None,
               since_minutes: float | None = None, limit: int = 1000) -> dict:
        if since_minutes is not None and not 0 <= since_minutes <= 525600:
            raise ValueError("since_minutes must be between 0 and 525600")
        if not 1 <= limit <= 10000:
            raise ValueError("limit must be between 1 and 10000")
        traces, errors = [], []
        upstream_partial, upstream_limited = False, False
        for item, doc, error in self._read(lambda s, c: c.traces(), service):
            if error:
                errors.append(error)
            else:
                # A finite merged list is not necessarily a complete upstream
                # observation. Preserve uncertainty before filtering so watchers
                # cannot infer uniqueness from a service's partial result.
                # Do not forward arbitrary upstream error bodies into the UI.
                upstream_partial |= bool(doc.get("partial") or doc.get("errors"))
                upstream_limited |= bool(doc.get("limited"))
                traces.extend(_tag(item, row) for row in doc.get("traces", []))
        cutoff = None if since_minutes is None else time.time() * 1000 - since_minutes * 60000
        rows = [t for t in traces
                if (not method or str(_field(t, "httpMethod")).upper() == method.upper())
                and (not path or path.lower() in str(_field(t, "path") or "").lower())
                and (not user or user.lower() in str(t.get("user") or "").lower())
                and (cutoff is None or t.get("startedAtMillis", t.get("startedAt", 0)) >= cutoff)]
        rows.sort(key=lambda t: t.get("lastActivityMillis", t.get("startedAtMillis", 0)), reverse=True)
        return {"traces": rows[:limit], "count": min(len(rows), limit), "errors": errors,
                "partial": bool(errors) or upstream_partial,
                "limited": upstream_limited or len(rows) > limit, "federated": True}

    def summary(self) -> dict:
        rows, errors = [], []
        for item, doc, error in self._read(lambda s, c: c.summary()):
            if error:
                errors.append(error)
            else:
                rows.append({**doc, "service": item.name})
        return {"federated": True, "readOnly": True, "services": rows,
                "configuredServices": list(self.services), "errors": errors, "partial": bool(errors),
                "traceCount": sum(r.get("traceCount", 0) for r in rows),
                "totalEvents": sum(r.get("totalEvents", 0) for r in rows)}

    def find_by_lens_id(self, lens_id: str) -> dict:
        result = self.traces(limit=10000)
        result["traces"] = [t for t in result["traces"] if t.get("lensId") == lens_id]
        result["count"] = len(result["traces"])
        return result

    def export(self, segment_id: str, evict: bool | None = False,
               service: str | None = None, instance: str | None = None) -> dict:
        if evict:
            raise ValueError("federation exports are read-only")
        if service:
            self._selected(service)
            row = self.clients[service].export(segment_id, evict=False)
            if instance is not None and row.get("serviceInstanceId") != instance:
                raise DebugApiError("service instance changed; refresh the trace list")
            return _tag(self.services[service], row)
        found = self.traces(limit=10000)
        matches = [t for t in found["traces"] if t.get("segmentId") == segment_id
                   and (instance is None or t.get("serviceInstanceId") == instance)]
        if len(matches) != 1 or found["errors"]:
            raise DebugApiError("segment lookup is ambiguous, missing, or incomplete; specify service and instance")
        match = matches[0]
        return self.export(segment_id, service=match["service"], instance=match.get("serviceInstanceId"))

    def source_root(self, service: str) -> str | None:
        return self.services[service].source_root

    def get_distributed_trace(self, trace_id: str) -> dict:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", trace_id):
            raise ValueError("invalid trace id")
        def query(item, client):
            try:
                doc = client.find_by_trace_id(trace_id)
            except DebugApiError as exc:
                if exc.status != 404:
                    raise
                # Compatibility with pre-federation agents: retained summaries suffice.
                doc = client.traces()
            matches = [r for r in doc.get("traces", []) if r.get("traceId") == trace_id]
            segments, errors = [], []
            for row in matches[:512]:
                try:
                    segment = client.export(row["segmentId"], evict=False)
                    if (segment.get("traceId") != trace_id or
                            row.get("serviceInstanceId") != segment.get("serviceInstanceId")):
                        raise DebugApiError("trace or service instance changed during lookup")
                    # The export includes events, but distributed summaries omit
                    # line payloads. Preserve allowlisted boundary evidence before
                    # discarding events; source drill-down fetches its own export.
                    segment["externalCalls"], segment["externalCallsTruncated"] = extract_boundaries(
                        segment.get("events", []), item.dependency_aliases)
                    for call in segment.get("httpCalls", []):
                        if isinstance(call, dict):
                            call["destinationName"] = alias_for(item.dependency_aliases, call.get("peer"))
                    segment.pop("events", None)
                    segments.append(_tag(item, segment))
                except (DebugApiError, OSError, ValueError) as exc:
                    errors.append({"service": item.name, "segmentId": row.get("segmentId"), "error": str(exc)})
            if len(matches) > 512:
                errors.append({"service": item.name, "error": "segment limit reached"})
            return segments, errors
        segments, errors = [], []
        for item, result, error in self._read(query):
            if error:
                errors.append(error)
            else:
                segments.extend(result[0])
                errors.extend(result[1])
        return join_trace(trace_id, segments, errors)


def join_trace(trace_id: str, segments: list[dict], errors: list[dict]) -> dict:
    """Causal graph, never a falsely precise cross-JVM nanoTime merge."""
    summaries, calls, external_calls, links, warnings = [], [], [], [], []
    if len(segments) > 512:
        warnings.append("Distributed segment display limit reached (512); graph is incomplete")
        segments = segments[:512]
    calls_limited = False
    external_limited = False
    by_call = {}
    for segment in segments:
        summary = {k: v for k, v in segment.items()
                   if k not in ("events", "httpCalls", "externalCalls", "contextParams")}
        summaries.append(summary)
        for index, call in enumerate(segment.get("httpCalls", [])):
            if len(calls) >= 5000:
                calls_limited = True
                break
            tagged = {**call, "service": segment["service"], "serviceInstanceId": segment.get("serviceInstanceId"),
                      "ownerSegmentKey": segment["segmentKey"],
                      "callKey": segment["segmentKey"] + ":call:" + str(index), "downstreamSegmentKeys": []}
            calls.append(tagged)
            if call.get("spanId"):
                by_call.setdefault(call["spanId"], []).append(tagged)
        # join_trace also accepts raw local snapshots; federation has already
        # projected these before dropping the much larger line-event payload.
        boundaries = segment.get("externalCalls")
        if boundaries is None:
            boundaries, limited = extract_boundaries(segment.get("events", []))
            if limited:
                warnings.append("External boundary extraction limit reached or malformed events: " + segment["segmentKey"])
        if not isinstance(boundaries, list):
            boundaries = []
            warnings.append("Malformed external boundary summaries: " + segment["segmentKey"])
        malformed_boundary = False
        for index, raw in enumerate(boundaries[:MAX_EXTERNAL_CALLS]):
            if len(external_calls) >= MAX_EXTERNAL_CALLS:
                external_limited = True
                break
            call = sanitize_summary(raw)
            if call is None:
                malformed_boundary = True
                continue
            event_index = raw.get("eventIndex")
            if type(event_index) is not int or not 0 <= event_index < MAX_EVENTS:
                event_index = index
            call.update(service=segment["service"], serviceInstanceId=segment.get("serviceInstanceId"),
                        segmentId=segment.get("segmentId"), ownerSegmentKey=segment["segmentKey"],
                        callKey=segment["segmentKey"] + ":dependency:" + str(event_index))
            external_calls.append(call)
        if malformed_boundary:
            warnings.append("Malformed external boundary summaries omitted: " + segment["segmentKey"])
        external_limited = external_limited or len(boundaries) > MAX_EXTERNAL_CALLS
        if segment.get("truncated"):
            warnings.append("Truncated segment: " + segment["segmentKey"])
        if segment.get("httpCallsTruncated"):
            warnings.append("HTTP call capture truncated: " + segment["segmentKey"])
        if segment.get("externalCallsTruncated"):
            warnings.append("External boundary extraction limit reached or malformed events: " + segment["segmentKey"])
        if segment.get("status") == "ACTIVE":
            warnings.append("Active segment may still produce downstream work: " + segment["segmentKey"])
    if calls_limited:
        warnings.append("Distributed HTTP call display limit reached (5000); graph is incomplete")
    if external_limited:
        warnings.append("Distributed external boundary display limit reached (5000); graph is incomplete")
    if external_calls:
        warnings.append("Dependency boundaries are local observations only; remote execution is not observed")
    if not segments:
        warnings.append("No segments observed in reachable configured services; trace may be unrecorded or evicted")
    for segment in summaries:
        parent = segment.get("parentSpanId")
        if not parent:
            continue
        candidates = by_call.get(parent, [])
        if len(candidates) == 1:
            call = candidates[0]
            call["downstreamSegmentKeys"].append(segment["segmentKey"])
            links.append({"fromCallKey": call["callKey"], "fromSegmentKey": call["ownerSegmentKey"],
                          "toSegmentKey": segment["segmentKey"]})
        else:
            warnings.append(("Ambiguous" if candidates else "Missing") + " parent call for " + segment["segmentKey"])
    for call in calls:
        if not call["downstreamSegmentKeys"]:
            call["downstreamState"] = "not-observed"
            warnings.append("Downstream not observed for " + call["callKey"]
                            + "; may be uninstrumented, filtered, unconfigured, pending or evicted")
        else:
            call["downstreamState"] = "observed"
    return {"traceId": trace_id, "segments": summaries, "httpCalls": calls,
            "externalCalls": external_calls, "links": links,
            "errors": errors, "warnings": warnings, "partial": bool(errors or warnings),
            "completeness": "not-observed" if not segments else ("partial" if errors or warnings else "observed-only"),
            "note": "Live retained evidence only; absence does not prove a service was not called. "
                    "Durations are local; clocks are not assumed synchronized."}
