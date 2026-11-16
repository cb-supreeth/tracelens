"""Record the Python test suite's pure evidence-model results for Java parity.

Maintenance only: run with tracelens-cli's test environment from the repository
root. The Java tests and shipped JAR do not require Python. The output contains
synthetic test data only; no running services or capture stores are read.
"""
from __future__ import annotations

import copy
import functools
import hashlib
import inspect
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tracelens-cli"))

import pytest
from tracelens_cli import dependencies, error_groups, execution, services, visualization

fixtures = []
seen = set()
counts = {}
skipped = {}


def record(module, name, operation):
    original = getattr(module, name)
    signature = inspect.signature(original)

    @functools.wraps(original)
    def wrapped(*args, **kwargs):
        bound = signature.bind(*args, **kwargs)
        bound.apply_defaults()
        arguments = copy.deepcopy(dict(bound.arguments))
        result = original(*args, **kwargs)
        # Tests explicitly monkeypatch limits to small values. They do not
        # describe the production contract and need dedicated Java limit tests.
        if operation == "execution" and (
            execution.MAX_EVENTS != 100000
            or execution.MAX_LANES != 64
            or execution.MAX_DEPTH != 512
        ):
            return result
        if operation == "errors" and arguments["now_ms"] is None:
            return result
        # Callers may legitimately enrich the returned boundary after this call.
        # Freeze its return value now, rather than recording their later writes.
        fixture = {"operation": operation, "arguments": arguments, "expected": copy.deepcopy(result)}
        try:
            encoded = json.dumps(fixture, sort_keys=True, ensure_ascii=True,
                                 separators=(",", ":"), allow_nan=False)
        except (TypeError, ValueError):
            return result  # Non-JSON values cannot arrive over the HTTP contract.
        if len(encoded) > 110000:
            skipped[operation] = skipped.get(operation, 0) + 1
            return result
        key = hashlib.sha256(encoded.encode()).hexdigest()
        if key not in seen:
            seen.add(key)
            counts[operation] = counts.get(operation, 0) + 1
            case = os.environ.get("PYTEST_CURRENT_TEST", name).split(" (")[0]
            fixture["name"] = f"{operation}: {case} [{counts[operation]}]"
            fixtures.append(fixture)
        return result

    setattr(module, name, wrapped)


record(dependencies, "extract_boundaries", "dependencies")
record(dependencies, "boundary_summary", "boundary")
record(dependencies, "sanitize_summary", "sanitize")
record(visualization, "build_visualization", "visualization")
record(visualization, "to_mermaid", "mermaid")
record(execution, "build_segment_execution", "execution")
record(error_groups, "build_error_groups", "errors")
record(services, "join_trace", "join")

result = pytest.main([
    "-q",
    *[str(ROOT / "tracelens-cli/tests" / f"test_{name}.py")
      for name in ("dependencies", "execution", "visualization", "error_groups", "services")],
])
if result:
    raise SystemExit(result)
output = ROOT / "tracelens-tool/src/test/resources/parity-evidence-python-suite.json"
output.write_text(json.dumps(fixtures, ensure_ascii=True, separators=(",", ":")) + "\n")
print(f"Recorded {len(fixtures)} unique models: {counts}; oversized calls skipped: {skipped}")
