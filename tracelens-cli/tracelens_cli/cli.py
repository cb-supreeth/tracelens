"""tracelens CLI: list, export and render execution traces from a running service.

Examples:
  tracelens summary
  tracelens list
  tracelens --url http://127.0.0.1:7778 render <segment-id>
  tracelens render <segment-id> --no-source --full
  tracelens export <segment-id> > flow.json
  tracelens tail                         # watch for new traces
  tracelens reset

Pass --url for the actual debug port (standalone defaults to 7778; this CLI retains
the legacy 8082 URL default). Segment operations use the local segment UUID, not
a distributed trace ID. See docs/use-cases.md and docs/sharing-and-configuration.md.
"""

from __future__ import annotations

import argparse
import json
import sys
import time

from .client import DebugApiError, TraceLensClient
from .render import render
from .services import Federation, Service, load_services


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tracelens",
                                     description="TraceLens debug-API CLI")
    connection = parser.add_mutually_exclusive_group()
    connection.add_argument("--url", default="http://localhost:8082",
                        help="service base URL (default: http://localhost:8082)")
    connection.add_argument("--services", help="operator-owned JSON service registry (read-only federation)")
    parser.add_argument("--service", help="configured service name for request filtering or a segment lookup")
    parser.add_argument("--instance", help="expected service instance id for a segment lookup")
    parser.add_argument("--capture-dir", help="local saved-evidence directory (default: ~/.tracelens/captures)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("summary", help="store + agent status")
    sub.add_parser("list", help="list retained traces")

    p_doctor = sub.add_parser("doctor", help="read-only setup checks with actionable next steps")
    p_doctor.add_argument("--source-root", help="optional local source checkout to check in single-service mode")
    p_investigate = sub.add_parser("investigate", help="find one API/scenario and summarize observed evidence")
    _request_arguments(p_investigate)
    p_capture = sub.add_parser("capture", help="explicit local evidence snapshots; no database")
    capture_commands = p_capture.add_subparsers(dest="capture_command", required=True)
    p_save = capture_commands.add_parser("save", help="save selected request evidence before restart/eviction")
    p_save.add_argument("name", help="human name for this run, e.g. features-before")
    p_save.add_argument("--revision", help="operator-supplied build/revision label; not inferred from the checkout")
    _request_arguments(p_save)
    capture_commands.add_parser("list", help="list local captures without contacting any service")
    p_compare = capture_commands.add_parser("compare", help="compare two saved runs, offline")
    p_compare.add_argument("before", help="capture ID or unique exact saved name")
    p_compare.add_argument("after", help="capture ID or unique exact saved name")

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

    p_search = sub.add_parser("find-distributed", help="search retained API requests across services")
    for name in ("method", "path", "user"):
        p_search.add_argument("--" + name)
    p_search.add_argument("--since-minutes", type=float, default=30)
    p_search.add_argument("--limit", type=int, default=100)
    p_join = sub.add_parser("get-distributed", help="joined service segments and causal HTTP calls")
    p_join.add_argument("trace_id")

    p_tail = sub.add_parser("tail", help="watch for new traces")
    p_tail.add_argument("--interval", type=float, default=2.0)

    p_serve = sub.add_parser("serve", help="local web UI (bridge on 127.0.0.1)")
    p_serve.add_argument("--port", type=int, default=7777)
    p_serve.add_argument("--source-root", default=None,
                         help="local checkout for source lines (default: ~/work/cb-ems)")

    p_mcp = sub.add_parser("mcp", help="MCP server (stdio) — query flows from Cursor/agents")
    p_mcp.add_argument("--source-root", default=None,
                       help="local checkout for source lines (default: ~/work/cb-ems)")
    p_mcp.add_argument("--viewer-url", default=None,
                       help="operator-configured HTTP(S) viewer origin for graph links (no credentials, path, query or fragment)")

    p_reset = sub.add_parser("reset", help="clear retained traces")
    p_reset.add_argument("trace_id", nargs="?")

    sub.add_parser("enable", help="enable recording")
    sub.add_parser("disable", help="disable recording")
    sub.add_parser("metadata", help="effective config + agent status")

    args = parser.parse_args(argv)
    try:
        args.service_registry = load_services(args.services) if args.services else None
        client = Federation(args.service_registry) if args.services else TraceLensClient(args.url)
        return _dispatch(args, client)
    except (DebugApiError, ValueError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


def _dispatch(args, client: TraceLensClient) -> int:
    cmd = args.command
    federation = isinstance(client, Federation)
    if federation and cmd in ("reset", "enable", "disable", "metadata"):
        raise ValueError("this command requires single-service --url mode; federation is read-only")
    def export():
        if federation:
            return client.export(args.trace_id, service=args.service, instance=args.instance)
        return client.export(args.trace_id, evict=False)
    if cmd in ("doctor", "investigate", "capture"):
        from .captures import CaptureStore
        from .doctor import run_doctor
        from .investigation import investigate_request
        from .workflow import compare_saved_captures, default_capture_dir, save_request_capture
        discovery = client if federation else Federation([
            Service("default", args.url, getattr(args, "source_root", None))])
        if cmd == "doctor":
            result = run_doctor(discovery)
            _print_json(result)
            return 0 if result["ready"] else 1
        if cmd == "investigate":
            result = investigate_request(discovery, **_request_filters(args))
            _print_json(result)
            return 0 if result["status"] == "selected" else 2
        store = CaptureStore(args.capture_dir or default_capture_dir())
        if args.capture_command == "list":
            _print_json(store.list())
        elif args.capture_command == "compare":
            _print_json(compare_saved_captures(store, args.before, args.after))
        else:
            result = save_request_capture(discovery, store, args.name, args.revision, **_request_filters(args))
            _print_json(result)
            return 0 if result["saved"] else 2
    elif cmd == "summary":
        _print_json(client.summary())
    elif cmd == "list":
        _print_traces(client.traces())
    elif cmd == "export":
        _print_json(export())
    elif cmd == "render":
        trace = export()
        print(render(trace,
                     source_root=client.source_root(trace["service"]) if federation else args.source_root,
                     show_source=not args.no_source and (not federation or client.source_root(trace["service"]) is not None),
                     collapse=not args.full))
    elif cmd == "find":
        _print_traces(client.find_by_lens_id(args.lens_id))
    elif cmd in ("find-distributed", "get-distributed"):
        discovery = client if federation else Federation([Service("default", args.url)])
        if cmd == "get-distributed":
            _print_json(discovery.get_distributed_trace(args.trace_id))
        else:
            _print_json(discovery.traces(service=args.service, method=args.method, path=args.path,
                                        user=args.user, since_minutes=args.since_minutes, limit=args.limit))
    elif cmd == "tail":
        return _tail(client, args.interval)
    elif cmd == "serve":
        from . import server
        return server.serve(args.port, args.url, args.source_root, args.service_registry)
    elif cmd == "mcp":
        from . import mcp_server
        return mcp_server.serve(args.url, args.source_root, args.service_registry, args.viewer_url, args.capture_dir)
    elif cmd == "reset":
        _print_json(client.reset(args.trace_id))
    elif cmd == "enable":
        _print_json(client.enable())
    elif cmd == "disable":
        _print_json(client.disable())
    elif cmd == "metadata":
        _print_json(client.metadata())
    return 0


def _request_arguments(parser) -> None:
    parser.add_argument("--trace-id", help="exact retained trace ID; bypass API discovery")
    parser.add_argument("--method", help="HTTP method")
    parser.add_argument("--path", help="literal API path substring, not the browser page URL")
    parser.add_argument("--user", help="user substring")
    parser.add_argument("--label", help="recorded reproduction/scenario label filter")
    parser.add_argument("--since-minutes", type=float, default=10)
    parser.add_argument("--outcome", choices=("all", "error", "success", "active", "unknown"), default="all")
    parser.add_argument("--selection", choices=("unique", "latest"), default="unique",
                        help="unique refuses ambiguous matches; latest is an explicit opt-in")


def _request_filters(args) -> dict:
    return {name: getattr(args, name) for name in (
        "service", "method", "path", "user", "label", "since_minutes", "outcome", "selection", "trace_id")}


def _print_json(doc) -> None:
    print(json.dumps(doc, indent=2))


def _print_traces(doc: dict) -> None:
    for error in doc.get("errors", []):
        print("PARTIAL: " + json.dumps(error), file=sys.stderr)
    traces = doc.get("traces", [])
    if not traces:
        print("no traces retained")
        return
    print(f"{'TRACE ID':38} {'LENS ID':28} {'USER':24} {'CH':5} {'STATUS':10} {'EVENTS':>7} {'SIZE':>8} SERVICE / INSTANCE")
    for t in traces:
        size = _human(t.get("approxSizeBytes", 0))
        print(f"{t.get('traceId', '?'):38} {str(t.get('lensId'))[:28]:28} "
              f"{str(t.get('user'))[:24]:24} {t.get('entryChannel', '?'):5} "
              f"{t.get('status', '?'):10} {t.get('eventCount', 0):>7} {size:>8} "
              f"{t.get('service', '-')} / {t.get('serviceInstanceId', '-')}")


def _tail(client: TraceLensClient, interval: float) -> int:
    print("watching for traces (Ctrl-C to stop)...")
    seen: set[str] = set()
    try:
        while True:
            for t in client.traces().get("traces", []):
                tid = t.get("traceId")
                identity = t.get("segmentKey") or json.dumps([t.get("service"), t.get("serviceInstanceId"), t.get("segmentId") or tid])
                if tid and identity not in seen:
                    seen.add(identity)
                    print(f"{t.get('entryChannel', '?'):5} {tid[:8]}… "
                          f"service={t.get('service', '-')} instance={t.get('serviceInstanceId', '-')} "
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
