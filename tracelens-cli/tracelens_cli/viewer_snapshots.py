"""Explicit, bounded, file-backed UI snapshots; never an upstream trace cache.

These full snapshots intentionally include the source/event evidence a developer
chooses to pin. They are distinct from the CLI's summarized capture format.
Only a name may change after creation; reads never contact a service or rebuild
a graph using newer evidence. Each configured viewer gets a separate namespace.
"""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
import re
import time
import uuid
from typing import Any

from .captures import CaptureStore

MAX_COUNT = 20
MAX_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 20 * 1024 * 1024
LIMITS = {"maxCount": MAX_COUNT, "maxBytes": MAX_BYTES, "maxTotalBytes": MAX_TOTAL_BYTES}
_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
_NAMESPACE = re.compile(r"[0-9a-f]{64}\Z")
_CONTROLS = re.compile(r"[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]")


class SnapshotError(ValueError):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def default_snapshot_dir() -> Path:
    return Path.home() / ".tracelens" / "viewer-snapshots"


def strict_json(raw: bytes) -> Any:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def constant(_):
        raise ValueError("invalid numeric constant")

    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique, parse_constant=constant)


def _raw(doc):
    try:
        return json.dumps(doc, ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, RecursionError, OverflowError):
        raise SnapshotError("Snapshot must contain finite JSON data.") from None


def _text(value, limit=256, empty=False):
    if (not isinstance(value, str) or len(value) > limit
            or (not empty and not value.strip()) or _CONTROLS.search(value)):
        raise SnapshotError("Invalid snapshot identity or text field.")
    return value


def _name(value):
    if not isinstance(value, str):
        raise SnapshotError("Enter a name for the snapshot.")
    cleaned = _CONTROLS.sub("", value).strip()
    if not cleaned or len(cleaned) > 100:
        raise SnapshotError("Snapshot names must contain 1–100 characters.")
    return cleaned


def _rows(value, maximum, label):
    if not isinstance(value, list) or len(value) > maximum or any(not isinstance(row, dict) for row in value):
        raise SnapshotError("Invalid snapshot " + label + ".")
    return value


def _identity(row):
    service = _text(row.get("service") or row.get("applicationId"))
    instance = _text(row.get("serviceInstanceId") or "", empty=True)
    segment = _text(row.get("segmentId"))
    if row.get("segmentKey") is not None:
        try:
            key = strict_json(_text(row["segmentKey"], 4096).encode())
        except (ValueError, UnicodeError, RecursionError):
            raise SnapshotError("Invalid snapshot segment identity.") from None
        if key != [service, instance, segment]:
            raise SnapshotError("Conflicting snapshot segment identity.")
    return service, instance, segment


def _warnings(value):
    if not isinstance(value, list) or len(value) > 1024:
        raise SnapshotError("Invalid snapshot warnings.")
    for message in value:
        if not isinstance(message, str) or len(message) > 16384:
            raise SnapshotError("Invalid snapshot warning.")


def _timestamp(value):
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not 0 <= value <= 2**53 - 1 or not math.isfinite(value)):
        raise SnapshotError("Invalid snapshot timestamp.")


def validate_snapshot(doc):
    if not isinstance(doc, dict) or doc.get("schemaVersion") != 1 or doc.get("storage") != "viewer":
        raise SnapshotError("Invalid or unsupported snapshot format.")
    if not isinstance(doc.get("id"), str) or not _UUID.fullmatch(doc["id"]):
        raise SnapshotError("Invalid snapshot ID.")
    if _name(doc.get("name")) != doc["name"]:
        raise SnapshotError("Invalid snapshot name.")
    _timestamp(doc.get("capturedAt"))
    _timestamp(doc.get("savedAt"))
    trace_id = doc.get("traceId")
    if trace_id is not None:
        _text(trace_id)
    if not isinstance(doc.get("partial"), bool):
        raise SnapshotError("Invalid snapshot partial flag.")
    _warnings(doc.get("warnings"))
    request = doc.get("request")
    if not isinstance(request, dict) or request.get("traceId") != trace_id:
        raise SnapshotError("Snapshot request does not match its trace.")
    services = request.get("services", [])
    if not isinstance(services, list) or len(services) > 1024 or any(not isinstance(value, str) for value in services):
        raise SnapshotError("Invalid snapshot request services.")
    journey = doc.get("journey")
    if not isinstance(journey, dict) or (trace_id is not None and journey.get("traceId") != trace_id):
        raise SnapshotError("Snapshot journey does not match its trace.")
    summary_rows = _rows(journey.get("segments"), 1024, "journey segments")
    identities = set()
    for row in summary_rows:
        key = _identity(row)
        if key in identities:
            raise SnapshotError("Duplicate snapshot segment identity.")
        identities.add(key)
        if trace_id is not None and row.get("traceId", trace_id) != trace_id:
            raise SnapshotError("Conflicting snapshot trace identity.")
    exports = _rows(doc.get("segments"), 64, "segment exports")
    if not exports:
        raise SnapshotError("Snapshot needs at least one saved segment export.")
    exported = set()
    for row in exports:
        key = _identity(row)
        if key not in identities or key in exported:
            raise SnapshotError("Saved segment is duplicated or absent from the journey.")
        exported.add(key)
        if trace_id is not None and row.get("traceId", trace_id) != trace_id:
            raise SnapshotError("Conflicting snapshot trace identity.")
        _rows(row.get("events"), 500000, "events")
    for field in ("links", "httpCalls", "externalCalls", "errors"):
        _rows(journey.get(field, []), 8192, field)
    if "visualization" in journey:
        graph = journey["visualization"]
        if (not isinstance(graph, dict) or graph.get("schemaVersion") != 1
                or graph.get("kind") != "request-flow"
                or (trace_id is not None and graph.get("traceId") != trace_id)):
            raise SnapshotError("Invalid saved request-flow graph.")
        nodes = _rows(graph.get("nodes"), 2048, "graph nodes")
        node_ids = set()
        for node in nodes:
            node_id = _text(node.get("id"), 4096)
            if node_id in node_ids or node.get("kind") not in ("segment", "http-call", "dependency-call"):
                raise SnapshotError("Invalid or duplicate saved graph node.")
            node_ids.add(node_id)
            if node["kind"] == "segment" and _identity(node) not in identities:
                raise SnapshotError("Saved graph segment is absent from the journey.")
        for edge in _rows(graph.get("edges"), 8192, "graph edges"):
            if (not isinstance(edge.get("from"), str) or not isinstance(edge.get("to"), str)
                    or edge["from"] not in node_ids or edge["to"] not in node_ids):
                raise SnapshotError("Saved graph edge has a missing endpoint.")
        roots = graph.get("roots", [])
        if not isinstance(roots, list) or any(not isinstance(node, str) or node not in node_ids for node in roots):
            raise SnapshotError("Invalid saved graph roots.")


def _summary(doc, size):
    # Keep lists cheap and omit full events/sources/request segment exports.
    request = {key: value for key, value in doc["request"].items()
               if value is None or isinstance(value, (str, bool, int, float))}
    request["services"] = [value for value in doc["request"].get("services", []) if isinstance(value, str)]
    return {**{key: doc[key] for key in ("id", "name", "traceId", "capturedAt", "savedAt", "partial", "warnings", "storage", "schemaVersion")},
            "request": request, "segmentCount": len(doc["segments"]), "sizeBytes": size}


class ViewerSnapshotStore(CaptureStore):
    """Reuse only CaptureStore's hardened POSIX directory/locking primitives.

    The namespace, validation, format, mutation rules and public API are separate
    from CLI captures. Open-at/no-follow checks reject symlinks in every path
    component, private files reject outside hardlinks, and flock serializes quota
    checks across both threads and viewer processes.
    """

    def __init__(self, root: str | Path, namespace: str):
        if not isinstance(namespace, str) or not _NAMESPACE.fullmatch(namespace):
            raise SnapshotError("Invalid viewer namespace.")
        super().__init__(Path(root).expanduser() / namespace)

    @staticmethod
    def _id(value):
        if not isinstance(value, str) or not _UUID.fullmatch(value):
            raise SnapshotError("Snapshot ID must be a canonical UUID, not a file path.", 404)
        return value + ".json"

    def _read(self, directory, name):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            info = self._private_file(stream.fileno(), self._paired_pending(directory, info))
            if info.st_size > MAX_BYTES:
                raise SnapshotError("Snapshot file exceeds 5 MiB.", 409)
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise SnapshotError("Snapshot file exceeds 5 MiB.", 409)
        try:
            doc = strict_json(raw)
            validate_snapshot(doc)
        except (ValueError, TypeError, RecursionError, OverflowError, UnicodeError):
            raise SnapshotError("Snapshot file is corrupt or unsupported; no live data was substituted.", 409) from None
        if doc["id"] + ".json" != name:
            raise SnapshotError("Snapshot file identity does not match its name.", 409)
        return doc, len(raw)

    def _usage(self, directory):
        names = self._names(directory)
        pending_count, pending_bytes = self._pending_usage(directory)
        rows = [self._read(directory, name) for name in names]
        return rows, len(names) + pending_count, sum(size for _, size in rows) + pending_bytes, pending_count

    def _publish(self, directory, doc, replacing=False):
        raw = _raw(doc)
        if len(raw) > MAX_BYTES:
            raise SnapshotError("Snapshot exceeds the 5 MiB limit; nothing was saved.", 413)
        name = self._id(doc["id"])
        _, count, total, _ = self._usage(directory)
        old_size = self._read(directory, name)[1] if replacing else 0
        if not replacing and count >= MAX_COUNT:
            raise SnapshotError("Viewer already has 20 snapshots; delete one before saving another.", 409)
        if total - old_size + len(raw) > MAX_TOTAL_BYTES:
            raise SnapshotError("Viewer snapshots would exceed 20 MiB; delete a snapshot first.", 409)
        temporary = ".pending-" + str(uuid.uuid4())
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            if replacing:
                os.replace(temporary, name, src_dir_fd=directory, dst_dir_fd=directory)
            else:
                # Atomic no-overwrite publish: an interrupted write cannot expose
                # partial JSON and even a UUID collision never replaces evidence.
                os.link(temporary, name, src_dir_fd=directory, dst_dir_fd=directory, follow_symlinks=False)
                os.unlink(temporary, dir_fd=directory)
            os.fsync(directory)
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass
        return len(raw)

    def save(self, input_doc):
        if not isinstance(input_doc, dict):
            raise SnapshotError("Supply a full snapshot object.")
        now = int(time.time() * 1000)
        doc = {"schemaVersion": 1, "storage": "viewer", "id": str(uuid.uuid4()),
               "name": _name(input_doc.get("name")), "traceId": input_doc.get("traceId"),
               "capturedAt": input_doc.get("capturedAt", now), "savedAt": now,
               "partial": input_doc.get("partial", False), "warnings": input_doc.get("warnings", []),
               "request": input_doc.get("request"), "journey": input_doc.get("journey"),
               "segments": input_doc.get("segments")}
        raw = _raw(doc)
        if len(raw) > MAX_BYTES:
            raise SnapshotError("Snapshot exceeds the 5 MiB limit; nothing was saved.", 413)
        # Detach before acquiring the lock; caller changes cannot mutate evidence.
        doc = strict_json(raw)
        validate_snapshot(doc)
        with self._directory(create=True) as directory, self._lock(directory):
            self._publish(directory, doc)
        return doc

    def list(self):
        rows, total, pending = [], 0, 0
        try:
            with self._directory() as directory, self._lock(directory, write=False):
                records, _, total, pending = self._usage(directory)
                rows = [_summary(doc, size) for doc, size in records]
        except FileNotFoundError:
            pass
        rows.sort(key=lambda row: (row["savedAt"], row["id"]), reverse=True)
        return {"snapshots": rows, "count": len(rows), "limits": dict(LIMITS), "totalBytes": total,
                "warnings": ["Interrupted snapshot files count toward quota; review them manually. No files were automatically deleted."] if pending else []}

    def get(self, snapshot_id):
        name = self._id(snapshot_id)
        try:
            with self._directory() as directory, self._lock(directory, write=False):
                return self._read(directory, name)[0]
        except FileNotFoundError:
            raise SnapshotError("Snapshot not found in this viewer configuration; no live data was substituted.", 404) from None

    def rename(self, snapshot_id, name):
        filename = self._id(snapshot_id)
        clean_name = _name(name)
        try:
            with self._directory() as directory, self._lock(directory):
                doc, _ = self._read(directory, filename)
                doc["name"] = clean_name
                size = self._publish(directory, doc, replacing=True)
                return _summary(doc, size)
        except FileNotFoundError:
            raise SnapshotError("Snapshot not found in this viewer configuration.", 404) from None

    def delete(self, snapshot_id):
        filename = self._id(snapshot_id)
        try:
            with self._directory() as directory, self._lock(directory):
                # Validate file safety and identity before removing this one file.
                self._read(directory, filename)
                os.unlink(filename, dir_fd=directory)
                os.fsync(directory)
        except FileNotFoundError:
            raise SnapshotError("Snapshot not found in this viewer configuration.", 404) from None
        return {"deleted": True, "id": snapshot_id}
