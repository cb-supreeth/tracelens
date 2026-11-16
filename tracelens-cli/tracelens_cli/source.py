"""Resolve FQCN + line number to the actual source line from a local checkout.

No embeddings — traces carry exact coordinates, so this is a deterministic file
read. Source directories are discovered once per checkout, without recursively
scanning the repository. Both Maven/Gradle and Ant module layouts are supported.
Inner classes (com.foo.Bar.Baz or com.foo.Bar$Baz) resolve to Bar.java.
"""

from __future__ import annotations

import functools
import os
import re
from pathlib import Path
from urllib.parse import quote, urlencode

DEFAULT_SOURCE_ROOT = Path.home() / "work" / "cb-ems"
SOURCE_SETS = ("src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy")


@functools.lru_cache(maxsize=128)
def _source_directories(source_root: str) -> tuple[Path, ...]:
    root = Path(source_root)
    try:
        modules = sorted(path for path in root.iterdir()
                         if path.is_dir() and not path.name.startswith("."))
    except OSError:
        return ()
    directories = []
    for base in (root, *modules):
        for source_set in (*SOURCE_SETS, "src"):
            candidate = base / source_set
            if candidate.is_dir():
                directories.append(candidate)
    # Also allow --source-root to point directly at a Java source directory.
    directories.append(root)
    return tuple(dict.fromkeys(directories))


@functools.lru_cache(maxsize=4096)
def _resolve_file(fqcn: str, source_root: str) -> str | None:
    if not isinstance(fqcn, str) or len(fqcn) > 256 or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*", fqcn):
        return None
    root = Path(source_root).resolve()
    parts = fqcn.split("$", 1)[0].split(".")
    # try longest path first, then strip trailing (inner-class) segments
    for end in range(len(parts), 0, -1):
        for directory in _source_directories(source_root):
            candidate = directory.joinpath(*parts[:end]).with_suffix(".java")
            if candidate.is_file() and root in candidate.resolve().parents:
                return str(candidate.resolve())
    return None


@functools.lru_cache(maxsize=4096)
def _read_version(path: str, mtime_ns: int, size: int, inode: int) -> tuple[str, ...]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return tuple(f.read(4 * 1024 * 1024 + 1).splitlines())
    except OSError:
        return ()

def _read_lines(path: str) -> tuple[str, ...]:
    try:
        info = Path(path).stat()
        if info.st_size > 4 * 1024 * 1024:
            return ()
        return _read_version(path, info.st_mtime_ns, info.st_size, info.st_ino)
    except OSError:
        return ()


def source_location(fqcn: str, line: int, source_root: str) -> dict | None:
    """Resolve only an existing source coordinate within an operator-owned root.

    Editor URLs are returned, never executed. These refer to current source, not
    a verified historical build; the UI must keep that distinction visible.
    """
    if type(line) is not int or not 1 <= line <= 1_000_000:
        raise ValueError("Source line must be an integer from 1 to 1000000.")
    path = _resolve_file(fqcn, source_root)
    if not path:
        return None
    root = Path(source_root).resolve()
    resolved = Path(path).resolve()
    if root not in resolved.parents or not resolved.is_file():
        return None
    if line > len(_read_lines(str(resolved))):
        return None
    encoded = quote(str(resolved), safe="/")
    return {"className": fqcn, "line": line, "path": str(resolved),
            "relativePath": str(resolved.relative_to(root)), "sourceCorrespondence": "unverified",
            "note": "Current local checkout; correspondence with the captured running build is not verified.",
            "editors": {"vscode": f"vscode://file{encoded}:{line}",
                        "cursor": f"cursor://file{encoded}:{line}",
                        "idea": "idea://open?" + urlencode({"file": str(resolved), "line": line})}}

def source_line(fqcn: str, line: int, source_root: str | None = None) -> str | None:
    """The stripped source text at fqcn:line, or None when unresolvable."""
    if type(line) is not int or line < 1:
        return None
    root = source_root or os.environ.get("TRACELENS_SOURCE_ROOT") or str(DEFAULT_SOURCE_ROOT)
    path = _resolve_file(fqcn, root)
    if path is None:
        return None
    if Path(root).resolve() not in Path(path).resolve().parents:
        return None
    lines = _read_lines(path)
    if line > len(lines):
        return None
    return lines[line - 1].strip()
