"""Bounded, payload-free request-flow views of retained distributed evidence.

Only explicit call ownership and ``journey.links`` establish edges. In
particular, timestamps, service names, URLs and parent span IDs never establish
causality here. This module has no I/O and does not modify its input.
"""

from __future__ import annotations

from collections import Counter, defaultdict, deque
import hashlib
import json
import math
import re
import unicodedata

from .dependencies import sanitize_summary


MAX_SEGMENTS = 128
MAX_CALLS = 128
MAX_DEPENDENCIES = 128
MAX_EDGES = 512
MAX_INPUT_ROWS = 8192
MAX_WARNINGS = 128
MAX_ERRORS = 64
MAX_LABEL = 180
MAX_TEXT = 256
MAX_PATH = 1024
MAX_KEY = 4096
NOTE = ("Observed evidence only; absence does not prove a service was not called. "
        "Durations are local milliseconds; clocks are not assumed synchronized. "
        "Arrows show explicit boundary direction or observed HTTP call links, not responses. "
        "Dependency boundaries do not establish remote execution or message receipt.")
_NODE_ID = re.compile(r"n_[scd]_[0-9a-f]{24}(?:_[0-9]+)?\Z")


def _text(value, limit=MAX_TEXT):
    """Accept text only: do not stringify arbitrary payload containers."""
    if not isinstance(value, str):
        return None
    # Include bidi/format controls, not only ASCII newlines. Limit work on strings
    # before normalizing; the extra character determines whether to show ellipsis.
    cleaned = "".join(" " if unicodedata.category(c).startswith("C") else c
                      for c in value[:limit + 1]).strip()
    return cleaned[:limit - 1] + "…" if len(cleaned) > limit else cleaned or None


def _key(value):
    # Identity comparisons use exact original keys, never their display spelling.
    return value if isinstance(value, str) and value.strip() and len(value) <= MAX_KEY else None


def _number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        result = float(value)
    except (ValueError, OverflowError):
        return None
    return result if math.isfinite(result) and result >= 0 else None


def _integer(value):
    result = _number(value)
    return int(result) if result is not None and result.is_integer() else None


def _field(row, name, transform=_text):
    value = transform(row.get(name))
    attrs = row.get("attributes")
    return value if value is not None else transform(attrs.get(name)) if isinstance(attrs, dict) else None


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"))


def _id(kind, source):
    return f"n_{kind}_" + hashlib.sha256(source.encode("utf-8", errors="surrogatepass")).hexdigest()[:24]


def _rows(doc, name, warn):
    rows = doc.get(name, [])
    if not isinstance(rows, list):
        warn(f"Malformed {name}: expected a list; no {name} inferred")
        return []
    if len(rows) > MAX_INPUT_ROWS:
        warn(f"Input {name} limit reached ({MAX_INPUT_ROWS}); evidence is incomplete")
    result = []
    for row in rows[:MAX_INPUT_ROWS]:
        if isinstance(row, dict):
            result.append(row)
        else:
            warn(f"Malformed {name} entry omitted")
    return result


def _base_node(row, kind):
    http_status = _field(row, "httpStatus", _integer)
    duration = _number(row.get("durationMs"))
    if kind == "http-call":
        nanos = _number(row.get("durationNanos"))
        duration = nanos / 1_000_000 if nanos is not None else duration
    return {"kind": kind, "label": "", "service": _text(row.get("service")),
            "serviceInstanceId": _text(row.get("serviceInstanceId")),
            "segmentId": _text(row.get("segmentId")),
            "segmentKey": _text(row.get("segmentKey"), MAX_KEY),
            "method": _field(row, "httpMethod", lambda x: _text(x, 32)),
            # Paths are already application strings, never base64-decoded here.
            "path": _field(row, "path", lambda x: _text(x, MAX_PATH)),
            "status": _text(row.get("status")),
            "httpStatus": http_status if http_status is not None and 100 <= http_status <= 599 else None,
            "durationMs": duration, "entryChannel": _field(row, "entryChannel"),
            "eventCount": _integer(row.get("eventCount")), "truncated": row.get("truncated") is True}


def _label(node):
    if node["kind"] == "segment":
        route = " ".join(p for p in (node["method"] or node["entryChannel"], node["path"]) if p)
        return _text(f"{node['service'] or 'Unknown service'} · {route or 'Segment'}", MAX_LABEL)
    if node["kind"] == "dependency-call":
        if node["boundaryKind"] == "database-route":
            prefix = "Database route"
        elif node["evidenceKind"] == "wrapper-entry":
            prefix = "Wrapper entry"
        else:
            prefix = "Messaging" if node["boundaryKind"] == "messaging" else "External boundary"
        detail = " ".join(value for value in (node["system"], node["operation"] or node["direction"]) if value)
        target = node.get("destinationName") or node["destination"]
        return _text(" · ".join(value for value in (prefix, detail, target) if value), MAX_LABEL)
    destination = (node.get("peer") or "") + (node["path"] or "")
    if node.get("destinationName"):
        destination = node["destinationName"] + " · " + destination
    return _text(f"HTTP {node['method'] or 'call'} {destination}".strip(), MAX_LABEL)


def _prepare(rows, kind, source_field, limit, warn):
    """Keep deterministic identities even for missing or duplicated source keys."""
    records = []
    keys = Counter(_key(row.get(source_field)) for row in rows)
    for row in rows:
        source_key = _key(row.get(source_field))
        if kind == "dependency-call":
            boundary = sanitize_summary(row)
            node = _base_node({**{key: row.get(key) for key in ("service", "serviceInstanceId")},
                               "status": boundary["status"]}, kind)
            node.update(boundary)
        else:
            node = _base_node(row, kind)
        if kind == "segment":
            node["parentSpanId"] = _text(row.get("parentSpanId"))
        else:
            node.update(callKey=_text(row.get("callKey"), MAX_KEY), ownerId=None,
                        segmentId=None, segmentKey=_text(row.get("ownerSegmentKey"), MAX_KEY),
                        downstreamState="not-observed")
            if kind == "http-call":
                node.update(peer=_text(row.get("peer")), propagationStatus=_text(row.get("propagationStatus")),
                            destinationName=_text(row.get("destinationName"), 128))
        node["label"] = _label(node)
        # Exact ownership is identity-relevant when duplicate call keys exist;
        # display cleanup must not make two different source owners identical.
        signature = _canonical([node, _key(row.get("ownerSegmentKey"))] if kind != "segment" else node)
        if source_key is None:
            warn(f"Missing or malformed {source_field}; node cannot resolve links by that key")
            identity = "missing:" + signature
        elif keys[source_key] > 1:
            warn(f"Duplicate {source_field}: {_text(source_key)}; ambiguous links omitted")
            identity = "duplicate:" + _canonical([source_key, signature])
        else:
            identity = "key:" + source_key
        records.append((source_key, row, node, identity, signature))
    records.sort(key=lambda r: (r[0] or "", r[4]))
    if len(records) > limit:
        warn(f"Visualization {kind} limit reached ({limit}); evidence is incomplete")
    selected, instances = [], Counter()
    for source_key, row, node, identity, _ in records[:limit]:
        node_id = _id({"segment": "s", "http-call": "c", "dependency-call": "d"}[kind], identity)
        instances[node_id] += 1
        node["id"] = node_id if instances[node_id] == 1 else f"{node_id}_{instances[node_id]}"
        selected.append((source_key, row, node))
    lookup = {key: node for key, _, node in selected if key is not None and keys[key] == 1}
    return selected, lookup


def build_visualization(journey: dict) -> dict:
    """Transform a ``services.join_trace`` result into schema-version 1.

    Invalid or ambiguous evidence remains visible through warnings and isolated
    nodes, never guessed edges. Cycles remain explicit evidence but are flagged;
    ``roots`` means zero incoming edges, not an invented causal starting point.
    """
    warnings = set()
    warn = warnings.add
    if not isinstance(journey, dict):
        warn("Malformed journey: expected an object")
        journey = {}
    upstream_warnings = journey.get("warnings", [])
    if isinstance(upstream_warnings, list):
        for value in upstream_warnings[:MAX_INPUT_ROWS]:
            message = _text(value, 512)
            if message:
                warn(message)
            else:
                warn("Malformed upstream warning omitted")
        if len(upstream_warnings) > MAX_INPUT_ROWS:
            warn("Upstream warning input limit reached; evidence is incomplete")
    else:
        warn("Malformed upstream warnings omitted")

    errors = []
    for error in _rows(journey, "errors", warn):
        # Error metadata is allowlisted too: arbitrary backend payloads stay out.
        item = {"service": _text(error.get("service")),
                "error": _text(error.get("error"), 512) or "Unknown upstream error"}
        for name in ("segmentId", "serviceInstanceId"):
            if (value := _text(error.get(name))) is not None:
                item[name] = value
        errors.append(item)
    errors.sort(key=_canonical)
    if len(errors) > MAX_ERRORS:
        warn(f"Error display limit reached ({MAX_ERRORS}); additional failures omitted")
    errors = errors[:MAX_ERRORS]

    segments, segment_by_key = _prepare(_rows(journey, "segments", warn), "segment", "segmentKey",
                                        MAX_SEGMENTS, warn)
    calls, call_by_key = _prepare(_rows(journey, "httpCalls", warn), "http-call", "callKey", MAX_CALLS, warn)
    dependencies, _ = _prepare(_rows(journey, "externalCalls", warn), "dependency-call", "callKey",
                               MAX_DEPENDENCIES, warn)
    nodes = [node for _, _, node in segments + calls + dependencies]
    if not segments:
        warn("No retained service segments observed; trace may be unrecorded, unavailable or evicted")
    for _, row, node in segments:
        if node["truncated"]:
            warn(f"Truncated segment: {node['id']}")
        if row.get("httpCallsTruncated") is True:
            warn(f"HTTP call capture truncated: {node['id']}")
        if row.get("externalCallsTruncated") is True:
            warn(f"External boundary extraction limit reached or malformed events: {node['id']}")
        if node["status"] == "ACTIVE":
            warn(f"Active segment may still produce downstream work: {node['id']}")

    edge_tuples = set()
    owner_keys = {}
    for key, row, node in calls:
        owner_key = _key(row.get("ownerSegmentKey"))
        owner = segment_by_key.get(owner_key)
        if owner is None:
            warn(f"Missing or ambiguous owner for HTTP call: {node['id']}; ownership not inferred")
        else:
            node["ownerId"] = owner["id"]
            node["segmentId"] = owner["segmentId"]
            edge_tuples.add((owner["id"], node["id"], "outbound"))
        if key is not None and call_by_key.get(key) is node:
            owner_keys[key] = owner_key

    for _, row, node in dependencies:
        owner = segment_by_key.get(_key(row.get("ownerSegmentKey")))
        if owner is None:
            warn(f"Missing or ambiguous owner for dependency boundary: {node['id']}; ownership not inferred")
            continue
        node["ownerId"], node["segmentId"] = owner["id"], owner["segmentId"]
        if node["direction"] == "consume":
            edge_tuples.add((node["id"], owner["id"], "inbound"))
        elif node["direction"] in ("publish", "outbound"):
            edge_tuples.add((owner["id"], node["id"], "outbound"))
        else:
            warn(f"Missing or malformed dependency direction: {node['id']}; direction not inferred")
    if dependencies:
        warn("Dependency boundaries are local observations only; remote execution is not observed")

    observed = set()
    for link in _rows(journey, "links", warn):
        call_key, target_key = _key(link.get("fromCallKey")), _key(link.get("toSegmentKey"))
        call, target = call_by_key.get(call_key), segment_by_key.get(target_key)
        if call is None or target is None:
            warn("Observed link has a missing, omitted or ambiguous call/segment target; link omitted")
            continue
        if (call["ownerId"] is None or _key(link.get("fromSegmentKey")) is None
                or _key(link.get("fromSegmentKey")) != owner_keys.get(call_key)):
            warn("Observed link has missing or conflicting ownership; link omitted")
            continue
        observed.add((call["id"], target["id"], "observed-call"))
    parents = defaultdict(set)
    for source, target, _ in observed:
        parents[target].add(source)
    for source, target, kind in sorted(observed):
        if len(parents[target]) > 1:
            warn(f"Ambiguous parent calls for segment: {target}; observed links omitted")
        else:
            edge_tuples.add((source, target, kind))
    edges = sorted(edge_tuples, key=lambda e: (e[2] not in ("outbound", "inbound"), e))
    if len(edges) > MAX_EDGES:
        warn(f"Visualization edge limit reached ({MAX_EDGES}); evidence is incomplete")
    edges = edges[:MAX_EDGES]
    linked_calls = {source for source, _, kind in edges if kind == "observed-call"}
    linked_segments = {target for _, target, kind in edges if kind == "observed-call"}
    for _, row, node in segments:
        if node["parentSpanId"] and node["id"] not in linked_segments:
            warn(f"Parent call not resolved for segment: {node['id']}; parentSpanId alone is not an edge")
    for _, _, node in calls:
        if node["id"] in linked_calls:
            node["downstreamState"] = "observed"
        else:
            warn(f"Downstream not observed for HTTP call: {node['id']}; reachability is unknown")

    incoming = {node["id"]: 0 for node in nodes}
    outgoing = defaultdict(list)
    for source, target, _ in edges:
        incoming[target] += 1
        outgoing[source].append(target)
    roots = sorted(node_id for node_id, count in incoming.items() if count == 0)
    queue, visited = deque(roots), 0
    while queue:
        source = queue.popleft()
        visited += 1
        for target in outgoing[source]:
            incoming[target] -= 1
            if incoming[target] == 0:
                queue.append(target)
    if visited != len(nodes):
        warn("Cyclic retained call links detected; no causal ordering inferred for the cycle")

    partial = bool(journey.get("partial") or journey.get("completeness") in ("partial", "not-observed")
                   or warnings or errors)
    # Do not bury evidence/display limits behind a page of unknown downstreams.
    messages = sorted(warnings, key=lambda message: ("limit reached" not in message.lower(),
                                                     message.startswith("Downstream not observed"), message))
    if len(messages) > MAX_WARNINGS:
        omitted = len(messages) - (MAX_WARNINGS - 1)
        messages = messages[:MAX_WARNINGS - 1] + [f"Warning display limit reached; {omitted} additional warnings omitted"]
    return {"schemaVersion": 1, "kind": "request-flow", "traceId": _text(journey.get("traceId")),
            "partial": partial, "completeness": "not-observed" if not segments else "partial" if partial else "observed-only",
            "note": NOTE, "warnings": messages, "errors": errors, "nodes": nodes,
            "edges": [{"id": "e_" + hashlib.sha256(_canonical(edge).encode()).hexdigest()[:24],
                       "from": edge[0], "to": edge[1], "kind": edge[2]} for edge in edges], "roots": roots}


def _mermaid_text(value):
    # Numeric entities cannot close the quoted source label. Use visible Unicode
    # counterparts for HTML delimiters as well: even an HTML-label renderer that
    # decodes entities must never receive an actual tag from our label. The exact
    # route remains available in the canonical model, unaffected by this export.
    text = _text(value, MAX_LABEL + 128) or "Unknown"
    text = text.translate(str.maketrans({"<": "‹", ">": "›", "&": "＆"}))
    return "".join(c if c.isalnum() or c in " /._-·…" else f"#{ord(c)};" for c in text)


def to_mermaid(model: dict) -> str:
    """Return safe Mermaid flowchart source, without HTML or execution directives."""
    lines = ["flowchart LR", "  %% Retained evidence only; durations are local, not a synchronized timeline."]
    known = set()
    source_nodes = model.get("nodes", []) if isinstance(model, dict) else []
    for node in source_nodes[:MAX_SEGMENTS + MAX_CALLS + MAX_DEPENDENCIES] if isinstance(source_nodes, list) else []:
        if not isinstance(node, dict):
            continue
        node_id = node.get("id")
        if not isinstance(node_id, str) or not _NODE_ID.fullmatch(node_id) or node_id in known:
            continue
        known.add(node_id)
        label = _text(node.get("label"), MAX_LABEL) or "Unknown"
        if (status := _text(node.get("status"), 32)) is not None:
            label += " | " + status
        http_status = _integer(node.get("httpStatus"))
        if http_status is not None:
            label += f" | HTTP {http_status}"
        if (duration := _number(node.get("durationMs"))) is not None:
            label += f" | {duration:g} ms local"
        if node.get("kind") == "http-call" and node.get("downstreamState") != "observed":
            label += " | downstream not observed"
        if node.get("kind") == "dependency-call":
            label += " | " + (_text(node.get("evidenceKind"), 32) or "boundary evidence unknown")
            label += " | remote execution not observed"
        if node.get("truncated") is True:
            label += " | TRUNCATED"
        lines.append(f'  {node_id}["{_mermaid_text(label)}"]')
    source_edges = model.get("edges", []) if isinstance(model, dict) else []
    for edge in source_edges[:MAX_EDGES] if isinstance(source_edges, list) else []:
        if not isinstance(edge, dict):
            continue
        source, target, kind = edge.get("from"), edge.get("to"), edge.get("kind")
        if not isinstance(source, str) or not isinstance(target, str) or source not in known or target not in known:
            continue
        if kind in ("outbound", "inbound", "observed-call"):
            label = "observed call" if kind == "observed-call" else kind
            lines.append(f"  {source} -->|{label}| {target}")
    return "\n".join(lines)
