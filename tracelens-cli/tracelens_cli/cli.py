"""tracelens CLI: list, export and render execution traces from a running service.

Examples:
  tracelens summary
  tracelens list
  tracelens render <trace-id>            # timeline with source lines
  tracelens render <trace-id> --no-source --full
  tracelens export <trace-id> > flow.json
  tracelens tail                         # watch for new traces
  tracelens reset
"""

from __future__ import annotations

import argparse
import json
import sys
import time

from .client import DebugApiError, TraceLensClient
from .render import render


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tracelens",
                                     description="TraceLens debug-API CLI")
    parser.add_argument("--url", default="http://localhost:8082",
                        help="service base URL (default: http://localhost:8082)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("summary", help="store + agent status")
    sub.add_parser("list", help="list retained traces")

    p_export = sub.add_parser("export", help="raw export JSON for a trace")
    p_export.add_argument("trace_id")

    p_render = sub.add_parser("render", help="human timeline with source lines")
    p_render.add_argument("trace_id")
    p_render.add_argument("--source-root", default=None,
                          help="local checkout to resolve sources (default: ~/work/cb-ems)")
    p_render.add_argument("--no-source", action="store_true")
    p_render.add_argument("--full", action="store_true", help="do not collapse repeats")

    p_find = sub.add_parser("find", help="find traces by lens id (label)")
    p_find.add_argument("lens_id")

    p_tail = sub.add_parser("tail", help="watch for new traces")
    p_tail.add_argument("--interval", type=float, default=2.0)

    p_serve = sub.add_parser("serve", help="local web UI (bridge on 127.0.0.1)")
    p_serve.add_argument("--port", type=int, default=7777)
    p_serve.add_argument("--source-root", default=None,
                         help="local checkout for source lines (default: ~/work/cb-ems)")

    p_reset = sub.add_parser("reset", help="clear retained traces")
    p_reset.add_argument("trace_id", nargs="?")

    sub.add_parser("enable", help="enable recording")
    sub.add_parser("disable", help="disable recording")
    sub.add_parser("metadata", help="effective config + agent status")

    args = parser.parse_args(argv)
    client = TraceLensClient(args.url)

    try:
        return _dispatch(args, client)
    except DebugApiError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


def _dispatch(args, client: TraceLensClient) -> int:
    cmd = args.command
    if cmd == "summary":
        _print_json(client.summary())
    elif cmd == "list":
        _print_traces(client.traces())
    elif cmd == "export":
        _print_json(client.export(args.trace_id))
    elif cmd == "render":
        trace = client.export(args.trace_id)
        print(render(trace,
                     source_root=args.source_root,
                     show_source=not args.no_source,
                     collapse=not args.full))
    elif cmd == "find":
        _print_traces(client.find_by_lens_id(args.lens_id))
    elif cmd == "tail":
        return _tail(client, args.interval)
    elif cmd == "serve":
        from . import server
        return server.serve(args.port, args.url, args.source_root)
    elif cmd == "reset":
        _print_json(client.reset(args.trace_id))
    elif cmd == "enable":
        _print_json(client.enable())
    elif cmd == "disable":
        _print_json(client.disable())
    elif cmd == "metadata":
        _print_json(client.metadata())
    return 0


def _print_json(doc) -> None:
    print(json.dumps(doc, indent=2))


def _print_traces(doc: dict) -> None:
    traces = doc.get("traces", [])
    if not traces:
        print("no traces retained")
        return
    print(f"{'TRACE ID':38} {'LENS ID':28} {'USER':24} {'CH':5} {'STATUS':10} {'EVENTS':>7} {'SIZE':>8}")
    for t in traces:
        size = _human(t.get("approxSizeBytes", 0))
        print(f"{t.get('traceId', '?'):38} {str(t.get('lensId'))[:28]:28} "
              f"{str(t.get('user'))[:24]:24} {t.get('entryChannel', '?'):5} "
              f"{t.get('status', '?'):10} {t.get('eventCount', 0):>7} {size:>8}")


def _tail(client: TraceLensClient, interval: float) -> int:
    print("watching for traces (Ctrl-C to stop)...")
    seen: set[str] = set()
    try:
        while True:
            for t in client.traces().get("traces", []):
                tid = t.get("traceId")
                if tid and tid not in seen:
                    seen.add(tid)
                    print(f"{t.get('entryChannel', '?'):5} {tid[:8]}… "
                          f"lens={t.get('lensId')} user={t.get('user')} "
                          f"events={t.get('eventCount')} status={t.get('status')}")
            time.sleep(interval)
    except KeyboardInterrupt:
        return 0


def _human(size: float) -> str:
    if size < 1024:
        return f"{int(size)}B"
    if size < 1024 * 1024:
        return f"{size / 1024:.0f}KB"
    return f"{size / (1024 * 1024):.1f}MB"


if __name__ == "__main__":
    sys.exit(main())
