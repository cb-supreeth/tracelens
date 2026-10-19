"""Shared explicit, local-only developer workflows for CLI and MCP.

The capture directory is operator configuration, never a model-supplied path.
Creating a snapshot is opt-in; discovery and comparisons do not change recording.
"""

from __future__ import annotations

from pathlib import Path

from .captures import CaptureStore, build_capture, compare_captures, validate_capture_inputs
from .investigation import investigate_request


def default_capture_dir() -> Path:
    return Path.home() / ".tracelens" / "captures"


def resolve_capture(store: CaptureStore, reference: str) -> dict:
    """An exact saved name is convenient; duplicate names must not pick a run."""
    if not isinstance(reference, str) or not reference or len(reference) > 128:
        raise ValueError("capture reference must be a capture ID or exact saved name (1–128 characters)")
    rows = store.list()["captures"]
    exact_id = [row for row in rows if row.get("captureId") == reference]
    matches = exact_id or [row for row in rows if row.get("label") == reference]
    if not matches:
        raise ValueError("saved capture not found; list captures in the configured capture directory")
    if len(matches) != 1:
        raise ValueError("saved name is ambiguous; list captures and use an exact captureId")
    return store.get(matches[0]["captureId"])


def save_request_capture(discovery, store: CaptureStore, name: str,
                         revision: str | None = None, **filters) -> dict:
    # Validate labels before even discovering candidates. They are user-provided
    # metadata, not a claim that the running artifact matches a Git checkout.
    validate_capture_inputs("validation", name, revision)
    selection = investigate_request(discovery, **filters)
    if selection.get("status") != "selected":
        return {**selection, "saved": False}
    capture = build_capture(discovery, selection["selectedTraceId"], name, revision)
    metadata = store.save(capture)
    warnings = list(capture.get("warnings", capture.get("quality", {}).get("warnings", [])))
    segment_warnings = [{"service": segment["service"], "segmentId": segment["segmentId"],
                         "warnings": segment.get("warnings", [])}
                        for segment in capture.get("segments", []) if segment.get("warnings")]
    return {"schemaVersion": 1, "status": "saved", "saved": True, "capture": metadata,
            "warnings": warnings, "segmentWarnings": segment_warnings,
            "nextSteps": ["Save a separately reproduced candidate after changing/restarting the application.",
                          "Compare the two capture IDs or unique saved names; no running service is needed.",
                          "Captures are local files, distinct from browser pins. Review before sharing."]}


def compare_saved_captures(store: CaptureStore, before: str, after: str) -> dict:
    return compare_captures(resolve_capture(store, before), resolve_capture(store, after))
