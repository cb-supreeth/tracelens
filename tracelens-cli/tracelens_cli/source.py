"""Resolve FQCN + line number to the actual source line from a local checkout.

No embeddings, no index — traces carry exact coordinates, so this is a deterministic
file read. Inner classes (com.foo.Bar.Baz) resolve to their outermost file
(com/foo/Bar.java) by trying progressively shorter paths.
"""

from __future__ import annotations

import functools
import os
from pathlib import Path

DEFAULT_SOURCE_ROOT = Path.home() / "work" / "cb-ems"
SOURCE_SETS = ("src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy")


@functools.lru_cache(maxsize=4096)
def _resolve_file(fqcn: str, source_root: str) -> str | None:
    parts = fqcn.split(".")
    # try longest path first, then strip trailing (inner-class) segments
    for end in range(len(parts), 0, -1):
        for source_set in SOURCE_SETS:
            candidate = Path(source_root, source_set, *parts[:end]).with_suffix(".java")
            if candidate.is_file():
                return str(candidate)
    return None


@functools.lru_cache(maxsize=4096)
def _read_lines(path: str) -> tuple[str, ...]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return tuple(f.read().splitlines())
    except OSError:
        return ()


def source_line(fqcn: str, line: int, source_root: str | None = None) -> str | None:
    """The stripped source text at fqcn:line, or None when unresolvable."""
    if line < 1:
        return None
    root = source_root or os.environ.get("TRACELENS_SOURCE_ROOT") or str(DEFAULT_SOURCE_ROOT)
    path = _resolve_file(fqcn, root)
    if path is None:
        return None
    lines = _read_lines(path)
    if line > len(lines):
        return None
    return lines[line - 1].strip()
