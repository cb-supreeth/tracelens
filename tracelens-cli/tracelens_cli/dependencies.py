"""Small, payload-free projections of explicitly observed dependency boundaries.

These are local boundary observations, not spans from a remote receiver. Neither
destinations nor aliases participate in correlation or reachability decisions.
"""

from __future__ import annotations

import re
from urllib.parse import urlsplit, urlunsplit


MAX_EVENTS = 100_000
MAX_EXTERNAL_CALLS = 5000
MAX_ALIASES = 128
MAX_DESTINATION = 512
_TOKEN = re.compile(r"[A-Za-z0-9_$][A-Za-z0-9_.$:-]{0,127}\Z")
_OPERATION = re.compile(r"[A-Za-z_$][A-Za-z0-9_.$:-]{0,127}\Z")
_TARGET = re.compile(r"[A-Za-z0-9_./:$\[\]-]+\Z")
_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}\Z")
_THREAD = re.compile(r"[A-Za-z0-9_.$:#/\[\] -]{1,128}\Z")


def _token(value, pattern=_TOKEN):
    return value if isinstance(value, str) and pattern.fullmatch(value) else None


def destination(value):
    """Retain bounded target identifiers, excluding URL credentials and queries."""
    if not isinstance(value, str) or not value or len(value) > 2048:
        return None
    if any(ord(char) < 33 or ord(char) > 126 for char in value):
        return None
    if "://" in value:
        try:
            parsed = urlsplit(value)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                return None
            host = parsed.hostname
            if ":" in host:
                host = "[" + host + "]"
            if parsed.port is not None:
                host += ":" + str(parsed.port)
            value = urlunsplit((parsed.scheme, host, parsed.path, "", ""))
        except ValueError:
            return None
    if len(value) > MAX_DESTINATION or not _TARGET.fullmatch(value):
        return None
    return value


def validate_aliases(value):
    """Validate operator-owned exact display aliases; never accept arbitrary URLs."""
    if not isinstance(value, dict) or len(value) > MAX_ALIASES:
        raise ValueError(f"dependencyAliases must be an object with at most {MAX_ALIASES} entries")
    for target, name in value.items():
        if not isinstance(target, str) or destination(target) != target or not _token(name, _NAME):
            raise ValueError("dependencyAliases requires bounded credential-free exact targets and display names")
    return dict(value)


def alias_for(aliases, target):
    if not isinstance(aliases, dict) or not isinstance(target, str):
        return None
    return _token(aliases.get(target), _NAME)


def _sequence(value):
    if isinstance(value, str) and len(value) <= 19 and value.isascii() and value.isdecimal():
        value = int(value)
    return value if type(value) is int and 0 < value <= 2 ** 63 - 1 else None


def boundary_summary(event):
    """Allowlist one event; legacy wrappers prove entry, not a successful request."""
    if not isinstance(event, dict) or event.get("type") not in ("EXTERNAL_CALL", "DB_ROUTE"):
        return None
    attrs = event.get("attributes")
    attrs = attrs if isinstance(attrs, dict) else {}
    if event["type"] == "DB_ROUTE":
        result = {"boundaryKind": "database-route", "system": "database", "direction": "outbound",
                  "destination": destination(attrs.get("route")), "operation": "route",
                  "status": "OBSERVED", "evidenceKind": "routing-marker", "errorType": None}
    elif "boundaryKind" not in attrs:
        # detail historically contains the wrapper method, but is not generally
        # safe free text. Only a method-like token may be projected as operation.
        result = {"boundaryKind": "external", "system": _token(attrs.get("system")),
                  "direction": "outbound", "destination": None,
                  "operation": _token(attrs.get("detail"), _OPERATION),
                  "status": "OBSERVED", "evidenceKind": "wrapper-entry", "errorType": None}
    else:
        result = {"boundaryKind": attrs.get("boundaryKind") if attrs.get("boundaryKind") in
                  ("messaging", "external") else "external",
                  "system": _token(attrs.get("system")),
                  "direction": attrs.get("direction") if attrs.get("direction") in
                  ("publish", "consume", "outbound") else None,
                  "destination": destination(attrs.get("destination")),
                  "operation": _token(attrs.get("operation"), _OPERATION),
                  "status": attrs.get("status") if attrs.get("status") in
                  ("ATTEMPTED", "SUBMITTED", "OBSERVED", "ERROR") else None,
                  "evidenceKind": attrs.get("evidenceKind") if attrs.get("evidenceKind") in
                  ("client-boundary", "wrapper-entry") else None,
                  "errorType": _token(attrs.get("errorType"))}
    result.update(eventSeq=_sequence(event.get("seq")), thread=_token(event.get("thread"), _THREAD),
                  downstreamState="not-observed")
    return result


def extract_boundaries(events, aliases=None):
    """Return bounded summaries and a visible incompleteness flag before dropping events."""
    if not isinstance(events, list):
        return [], True
    calls = []
    limited = len(events) > MAX_EVENTS
    for index, event in enumerate(events[:MAX_EVENTS]):
        summary = boundary_summary(event)
        if summary is None:
            continue
        if len(calls) >= MAX_EXTERNAL_CALLS:
            limited = True
            break
        summary.update(eventIndex=index, destinationName=alias_for(aliases, summary["destination"]))
        calls.append(summary)
    return calls, limited


def sanitize_summary(row):
    """Revalidate stored/public summary rows instead of trusting extra fields."""
    if not isinstance(row, dict):
        return None
    if row.get("boundaryKind") == "database-route":
        event = {"type": "DB_ROUTE", "attributes": {"route": row.get("destination")}}
    else:
        event = {"type": "EXTERNAL_CALL", "attributes": {key: row.get(key) for key in (
            "boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind", "errorType")}}
    event.update(seq=row.get("eventSeq"), thread=row.get("thread"))
    result = boundary_summary(event)
    result["destinationName"] = _token(row.get("destinationName"), _NAME)
    return result
