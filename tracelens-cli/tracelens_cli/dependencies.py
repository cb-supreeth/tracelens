"""Small, payload-free projections of explicitly observed dependency boundaries.

These are local boundary observations, not spans from a remote receiver. Neither
destinations nor aliases participate in correlation or reachability decisions.
"""

from __future__ import annotations

import json
import math
import re
import unicodedata
from urllib.parse import urlsplit, urlunsplit


MAX_EVENTS = 100_000
MAX_EXTERNAL_CALLS = 5000
MAX_ALIASES = 128
MAX_DESTINATION = 512
MAX_WRAPPER_THREADS = 64
MAX_WRAPPER_DEPTH = 512
MAX_SQL_CHARS = 16384
MAX_SQL_PARAMETERS = 128
MAX_SQL_PARAMETER_CHARS = 512
MAX_SQL_PARAMETER_JSON_CHARS = 8192
MAX_SQL_PARAMETER_SETS = 10
_SQL_PARAMETER_TYPES = frozenset((
    "Null", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "BigDecimal", "String", "Bytes",
    "Date", "Time", "Timestamp", "AsciiStream", "UnicodeStream", "BinaryStream", "Object", "CharacterStream",
    "Ref", "Blob", "Clob", "Array", "URL", "RowId", "NString", "NCharacterStream", "NClob", "SQLXML"))
_SQL_PRIVATE_FIELDS = frozenset(("sql", "sqlParameters", "sqlParameterSets", "sqlParametersStatus",
                                 "sqlParametersTruncated", "sqlParameterSetsOmitted"))
_CACHE_WRAPPER_CLASS = "com.chargebee.ems.config.cache.ValkeyCacheService"
_CACHE_WRAPPER_OPERATIONS = ("get", "put", "evict")
_CACHE_CLIENT_OPERATION = {"get": "get", "put": "set", "evict": "delete"}
_TOKEN = re.compile(r"[A-Za-z0-9_$][A-Za-z0-9_.$:-]{0,127}\Z")
_OPERATION = re.compile(r"[A-Za-z_$][A-Za-z0-9_.$:-]{0,127}\Z")
_TARGET = re.compile(r"[A-Za-z0-9_./:$\[\]-]+\Z")
_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}\Z")
_THREAD = re.compile(r"[A-Za-z0-9_.$:#/\[\] -]{1,128}\Z")
_JAVA_CLASS = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\Z")
_JAVA_METHOD = re.compile(r"(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)\Z")


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


def _cache_wrapper_operation(event):
    """An audited application wrapper, never class-name guessing or remote proof."""
    if (event.get("type") == "METHOD_ENTER" and event.get("className") == _CACHE_WRAPPER_CLASS
            and event.get("methodName") in _CACHE_WRAPPER_OPERATIONS):
        return event["methodName"]
    return None


def jdbc_execution(attrs):
    return (isinstance(attrs, dict) and attrs.get("boundaryKind") == "database"
            and attrs.get("system") in ("postgresql", "mysql") and attrs.get("direction") == "outbound"
            and attrs.get("evidenceKind") == "jdbc-execution"
            and attrs.get("operation") in ("execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                                            "executeBatch", "executeLargeBatch")
            and attrs.get("status") in ("OBSERVED", "ERROR"))


def jdbc_limit(attrs):
    return (isinstance(attrs, dict) and attrs.get("boundaryKind") == "database"
            and attrs.get("system") in ("postgresql", "mysql") and attrs.get("direction") == "outbound"
            and attrs.get("evidenceKind") == "jdbc-query-limit" and attrs.get("operation") == "query-limit"
            and attrs.get("status") == "TRUNCATED")


def _utf16_length(value):
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def _sql_text(value):
    if not isinstance(value, str) or not value:
        return None
    length, output = 0, []
    for char in value:
        length += 2 if ord(char) > 65535 else 1
        if length > MAX_SQL_CHARS:
            break
        output.append(char if char in "\n\r\t" or unicodedata.category(char) not in ("Cc", "Cf", "Cs", "Co", "Cn") else " ")
    return "".join(output)


def _bounded_integer(value, maximum):
    if isinstance(value, str) and len(value) <= 10 and value.isascii() and value.isdecimal():
        value = int(value)
    return value if type(value) is int and 0 <= value <= maximum else None


def _duration(value):
    if isinstance(value, str):
        if len(value) > 32 or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?", value):
            return None
        value = float(value)
    try:
        return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None
    except OverflowError:
        return None


def _true_flag(value):
    return value is True or value == "true"


def _parameter_json(value):
    def strict_object(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError("duplicate parameter key")
            result[key] = item
        return result
    if isinstance(value, str):
        if _utf16_length(value) > MAX_SQL_PARAMETER_JSON_CHARS:
            return None
        try:
            value = json.loads(value, object_pairs_hook=strict_object)
        except (ValueError, RecursionError):
            return None
    return value if isinstance(value, list) else None


def _parameter_json_length(value):
    return _utf16_length(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False))


def _parameter_array(raw):
    rows = _parameter_json(raw)
    if rows is None or len(rows) > MAX_SQL_PARAMETERS:
        return None
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            return None
        index, kind, status, value = row.get("index"), row.get("type"), row.get("status"), row.get("value")
        if (type(index) is not int or not 1 <= index <= MAX_SQL_PARAMETERS or index in result
                or not isinstance(kind, str) or kind not in _SQL_PARAMETER_TYPES
                or status not in ("captured", "null", "unsupported", "truncated")):
            return None
        if status in ("null", "unsupported"):
            if "value" not in row or value is not None:
                return None
        elif not isinstance(value, str) or _utf16_length(value) > MAX_SQL_PARAMETER_CHARS:
            return None
        captured = "" if value == "" else _sql_text(value)
        # Display-safe replacements are not exact binding evidence.
        if isinstance(value, str) and captured != value:
            status = "truncated"
        result[index] = {"index": index, "type": kind, "value": captured, "status": status}
    ordered = [result[index] for index in sorted(result)]
    return ordered if _parameter_json_length(ordered) <= MAX_SQL_PARAMETER_JSON_CHARS else None


def _sql_parameters(attrs):
    raw_status = attrs.get("sqlParametersStatus")
    known_status = raw_status in ("captured", "unavailable", "batch", "not-applicable")
    status = raw_status if known_status else "unavailable"
    has_parameters, has_sets = attrs.get("sqlParameters") is not None, attrs.get("sqlParameterSets") is not None
    parameters, sets = None, None
    invalid = (not known_status and (has_parameters or has_sets) or has_parameters and has_sets
               or status == "not-applicable" and (has_parameters or has_sets)
               or status == "captured" and has_sets or status == "batch" and has_parameters)
    omitted = _bounded_integer(attrs.get("sqlParameterSetsOmitted"), 2 ** 31 - 1)
    invalid = invalid or attrs.get("sqlParameterSetsOmitted") is not None and omitted is None
    if not invalid and (status == "batch" or status == "unavailable" and has_sets):
        raw = _parameter_json(attrs.get("sqlParameterSets"))
        if raw is None or len(raw) > MAX_SQL_PARAMETER_SETS:
            invalid = True
        else:
            sets = [_parameter_array(row) if isinstance(row, list) else None for row in raw]
            invalid = any(row is None for row in sets) or _parameter_json_length(sets) > MAX_SQL_PARAMETER_JSON_CHARS
    elif not invalid and status != "not-applicable":
        if attrs.get("sqlParameters") is not None:
            parameters = _parameter_array(attrs["sqlParameters"])
            invalid = parameters is None
        else:
            invalid = status == "captured"
    if invalid:
        parameters, sets, status = None, None, "unavailable"
    entry_truncated = any(row["status"] == "truncated" for row in parameters or []) or any(
        row["status"] == "truncated" for parameter_set in sets or [] for row in parameter_set)
    return {"sqlParameters": parameters, "sqlParameterSets": sets, "sqlParametersStatus": status,
            "sqlParametersTruncated": invalid or entry_truncated or _true_flag(attrs.get("sqlParametersTruncated")) or bool(omitted),
            "sqlParameterSetsOmitted": omitted}


def without_sql(value):
    """Detach raw SQL and binding data before compact capture or MCP projection."""
    if isinstance(value, dict):
        return {key: without_sql(item) for key, item in value.items() if key not in _SQL_PRIVATE_FIELDS}
    if isinstance(value, list):
        return [without_sql(item) for item in value]
    return value


def boundary_summary(event):
    """Allowlist one event; legacy wrappers prove entry, not a successful request."""
    if not isinstance(event, dict):
        return None
    wrapper_operation = _cache_wrapper_operation(event)
    if event.get("type") not in ("EXTERNAL_CALL", "DB_ROUTE") and not wrapper_operation:
        return None
    attrs = event.get("attributes")
    attrs = attrs if isinstance(attrs, dict) else {}
    if wrapper_operation:
        # Older captures retain application method entries but predate native
        # Redis client hooks. Preserve this weaker evidence as such: neither the
        # return value nor a cache hit/miss or network operation is inferred.
        result = {"boundaryKind": "cache", "system": "redis", "direction": "outbound",
                  "destination": None, "operation": wrapper_operation,
                  "status": "OBSERVED", "evidenceKind": "wrapper-entry", "errorType": None}
    elif event["type"] == "DB_ROUTE":
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
                  ("messaging", "external", "database", "cache") else "external",
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
    if event["type"] == "EXTERNAL_CALL" and jdbc_execution(attrs):
        sql = _sql_text(attrs.get("sql"))
        result.update(evidenceKind="jdbc-execution", sql=sql,
                      sqlTruncated=_true_flag(attrs.get("sqlTruncated")) or
                      sql is not None and sql != attrs.get("sql"),
                      sqlSource=attrs.get("sqlSource") if attrs.get("sqlSource") in
                      ("statement", "prepared", "batch", "unavailable") else None,
                      durationMs=_duration(attrs.get("durationMs")), batchSize=_bounded_integer(attrs.get("batchSize"), 2 ** 31 - 1),
                      errorType=_token(attrs.get("errorType"), _JAVA_CLASS))
        result.update(_sql_parameters(attrs))
    elif event["type"] == "EXTERNAL_CALL" and jdbc_limit(attrs):
        result.update(evidenceKind="jdbc-query-limit", status="TRUNCATED",
                      queryLimit=_bounded_integer(attrs.get("queryLimit"), 1000) or None)
    result.update(eventSeq=_sequence(event.get("seq")), thread=_token(event.get("thread"), _THREAD),
                  downstreamState="not-observed")
    return result


def extract_boundaries(events, aliases=None):
    """Return bounded summaries and a visible incompleteness flag before dropping events."""
    if not isinstance(events, list):
        return [], True
    calls = {}  # Event-index keyed; suppress only a wrapper actually enclosing its typed client.
    limited = len(events) > MAX_EVENTS
    stacks = {}
    previous_seq = None
    nesting_enabled = True

    def reset_stacks():
        for stack in stacks.values():
            stack.clear()

    def identity(value, pattern, max_length):
        return value if isinstance(value, str) and len(value) <= max_length and pattern.fullmatch(value) else None

    for index, event in enumerate(events[:MAX_EVENTS]):
        if not isinstance(event, dict):
            reset_stacks()
            previous_seq = None
            continue
        summary = boundary_summary(event)
        seq = _sequence(event.get("seq"))
        if seq is None or previous_seq is not None and seq != previous_seq + 1:
            reset_stacks()
        previous_seq = seq
        thread = _token(event.get("thread"), _THREAD)
        if thread is None:
            # Missing identity can hide an enter/exit from any known name lane.
            reset_stacks()
        elif nesting_enabled:
            if thread not in stacks:
                if len(stacks) >= MAX_WRAPPER_THREADS:
                    limited, nesting_enabled = True, False
                    reset_stacks()
                else:
                    stacks[thread] = []
            if nesting_enabled:
                stack = stacks[thread]
                event_type = event.get("type")
                cls = identity(event.get("className"), _JAVA_CLASS, 4096)
                method = identity(event.get("methodName"), _JAVA_METHOD, 256)
                if event_type == "METHOD_ENTER":
                    if not cls or not method or seq is None:
                        stack.clear()
                    elif len(stack) >= MAX_WRAPPER_DEPTH:
                        limited, nesting_enabled = True, False
                        reset_stacks()
                    else:
                        stack.append({"key": (cls, method), "index": index,
                                      "wrapper": _cache_wrapper_operation(event)})
                elif event_type in ("METHOD_EXIT", "EXCEPTION"):
                    position = next((offset for offset in range(len(stack) - 1, -1, -1)
                                     if seq is not None and stack[offset]["key"] == (cls, method)), None)
                    if position is None:
                        stack.clear()
                    else:
                        del stack[position:]
                elif (summary is not None and seq is not None and summary["boundaryKind"] == "cache"
                      and summary["system"] == "redis" and summary["evidenceKind"] == "client-boundary"
                      and summary["direction"] == "outbound"
                      and summary["status"] in ("ATTEMPTED", "SUBMITTED", "OBSERVED", "ERROR")):
                    for frame in reversed(stack):
                        if (frame["wrapper"] and
                                _CACHE_CLIENT_OPERATION[frame["wrapper"]] == summary["operation"]):
                            calls.pop(frame["index"], None)
                            break
        if summary is None:
            continue
        if len(calls) >= MAX_EXTERNAL_CALLS:
            limited = True
            break
        summary.update(eventIndex=index, destinationName=alias_for(aliases, summary["destination"]))
        calls[index] = summary
    return list(calls.values()), limited


def sanitize_summary(row):
    """Revalidate stored/public summary rows instead of trusting extra fields."""
    if not isinstance(row, dict):
        return None
    if row.get("boundaryKind") == "database-route":
        event = {"type": "DB_ROUTE", "attributes": {"route": row.get("destination")}}
    else:
        event = {"type": "EXTERNAL_CALL", "attributes": {key: row.get(key) for key in (
            "boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind", "errorType",
            "sql", "sqlTruncated", "sqlSource", "durationMs", "batchSize", "queryLimit", "sqlParameters", "sqlParameterSets",
            "sqlParametersStatus", "sqlParametersTruncated", "sqlParameterSetsOmitted")}}
    event.update(seq=row.get("eventSeq"), thread=row.get("thread"))
    result = boundary_summary(event)
    result["destinationName"] = _token(row.get("destinationName"), _NAME)
    return result
