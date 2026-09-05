"""Render an exported trace as a human-readable timeline with source lines.

Layout goals (FlowLens-inspired, terminal-native):
  - METHOD_ENTER/EXIT indent the call tree
  - thread hops are called out as lane banners (async is where traces get interesting)
  - consecutive duplicate events (loop iterations) collapse into a xN suffix
  - LINE events show the actual source line resolved from the local checkout
"""

from __future__ import annotations

from typing import Any

from . import source

def _short_thread(name: str) -> str:
    # http-nio-8082-exec-3 -> http-exec-3 ; emsAsync-302 stays as-is
    if name.startswith("http-nio-"):
        return "http-exec-" + name.rsplit("-", 1)[-1]
    return name


def _short_class(fqcn: str) -> str:
    return fqcn.rsplit(".", 1)[-1]


def _fmt_ms(nanos: int) -> str:
    return f"{nanos / 1_000_000:8.3f}ms"


def render(trace: dict[str, Any],
           source_root: str | None = None,
           show_source: bool = True,
           collapse: bool = True,
           source_width: int = 96) -> str:
    events = trace.get("events", [])
    start_nanos = trace.get("startNanos", 0)

    out: list[str] = []
    header = (f'trace {trace.get("traceId", "?")[:8]}… "{trace.get("lensId", "?")}"'
              f' — user={trace.get("user", "?")} channel={trace.get("entryChannel", "?")}'
              f' status={trace.get("status", "?")}')
    attrs = trace.get("attributes", {})
    if attrs.get("httpStatus"):
        header += f' http={attrs["httpStatus"]}'
    if trace.get("durationMs") is not None:
        header += f' duration={trace["durationMs"]}ms'
    header += f' events={trace.get("eventCount", len(events))}'
    if trace.get("truncated"):
        header += " [TRUNCATED]"
    out.append(header)
    out.append("─" * min(len(header) + 20, 120))
    if attrs.get("siteId") or attrs.get("siteType"):
        out.append(f"site: {attrs.get('siteId', '?')} ({attrs.get('siteType', '?')})"
                   + (f"  requestId={attrs['requestId']}" if attrs.get("requestId") else ""))
    if attrs.get("datadogTraceId"):
        out.append(f"datadog: trace_id={attrs['datadogTraceId']} "
                   f"(queryable via the Datadog MCP / UI; spans tagged tracelens.id=\"{trace.get('lensId')}\")")

    depth = 0
    last_thread: str | None = None
    i = 0
    n = len(events)
    while i < n:
        event = events[i]
        # collapse a run of identical (type, class, method, line) events
        run = 1
        if collapse:
            j = i + 1
            while j < n and _same_event(events[j], event):
                j += 1
            run = j - i
            i = j - 1
        i += 1

        etype = event.get("type")
        thread = event.get("thread", "?")
        if thread != last_thread:
            out.append(f"── thread: {_short_thread(thread)} " + "─" * 40)
            last_thread = thread

        offset = _fmt_ms(event.get("nanoTime", start_nanos) - start_nanos)
        seq = f"#{event.get('seq', '?')}"
        indent = "  " * depth

        if etype == "METHOD_ENTER":
            loc = f"{_short_class(event.get('className', '?'))}.{event.get('methodName', '?')}"
            out.append(f"{seq:>6} {offset} {indent}▶ {loc}{_run_suffix(run)}")
            depth += 1
        elif etype == "METHOD_EXIT":
            depth = max(0, depth - 1)
            indent = "  " * depth
            loc = f"{_short_class(event.get('className', '?'))}.{event.get('methodName', '?')}"
            out.append(f"{seq:>6} {offset} {indent}◀ {loc}{_run_suffix(run)}")
        elif etype == "LINE":
            loc = f"{_short_class(event.get('className', '?'))}:{event.get('line', '?')}"
            out.append(f"{seq:>6} {offset} {indent}· {loc}{_run_suffix(run)}")
            if show_source:
                line = source.source_line(event.get("className", ""), event.get("line", -1),
                                          source_root)
                if line:
                    if len(line) > source_width:
                        line = line[: source_width - 1] + "…"
                    out.append(f"{'':6} {'':9} {indent}  │ {line}")
        elif etype == "EXCEPTION":
            loc = f"{_short_class(event.get('className', '?'))}.{event.get('methodName', '?')}"
            exc = event.get("attributes", {}).get("exceptionType", "?")
            msg = event.get("message") or ""
            out.append(f"{seq:>6} {offset} {indent}✖ {loc} threw {exc}: {msg}{_run_suffix(run)}")
        elif etype in ("EXTERNAL_CALL", "DB_ROUTE", "MARKER"):
            text = event.get("message") or _attrs_inline(event)
            out.append(f"{seq:>6} {offset} {indent}◆ {etype}: {text}{_run_suffix(run)}")
        else:
            out.append(f"{seq:>6} {offset} {indent}{etype}{_run_suffix(run)}")

    return "\n".join(out)


def _same_event(a: dict[str, Any], b: dict[str, Any]) -> bool:
    return (a.get("type") == b.get("type")
            and a.get("className") == b.get("className")
            and a.get("methodName") == b.get("methodName")
            and a.get("line") == b.get("line")
            and a.get("thread") == b.get("thread"))


def _run_suffix(run: int) -> str:
    return f"  (x{run})" if run > 1 else ""


def _attrs_inline(event: dict[str, Any]) -> str:
    attrs = event.get("attributes", {})
    return " ".join(f"{k}={v}" for k, v in attrs.items())
