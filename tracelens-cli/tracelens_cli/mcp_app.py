"""Static, offline MCP Apps resource; tool data is delivered by the host bridge.

The resource uses only fixed package assets. Neither resource URIs nor tool input
can select filesystem paths, asset URLs, upstreams, or a source checkout.
"""

from __future__ import annotations

import ipaddress
import re
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

APP_RESOURCE_URI = "ui://tracelens/request-flow.html"
APP_MIME_TYPE = "text/html;profile=mcp-app"
WEB_DIR = Path(__file__).parent / "web"


def validate_viewer_url(value: str | None) -> str | None:
    """Accept only a fixed HTTP(S) origin supplied by the local operator."""
    if value is None:
        return None
    if (not isinstance(value, str) or not value or len(value) > 2048
            or any(ord(char) < 33 or ord(char) == 127 for char in value)
            or "\\" in value or "?" in value or "#" in value):
        raise ValueError("viewer URL must be an HTTP(S) origin")
    try:
        parsed = urlsplit(value)
        host, port = parsed.hostname, parsed.port
        if (parsed.scheme not in ("http", "https") or not host
                or parsed.username is not None or parsed.password is not None
                or parsed.path not in ("", "/") or parsed.query or parsed.fragment
                or parsed.netloc.endswith(":")
                or (port is not None and port < 1)):
            raise ValueError
        if ":" in host:
            ipaddress.IPv6Address(host)
        elif not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host):
            raise ValueError
    except ValueError as exc:
        raise ValueError("viewer URL must be an HTTP(S) origin without credentials, path, query or fragment") from exc
    return urlunsplit((parsed.scheme, parsed.netloc.lower(), "/", "", ""))


def app_resource_info() -> dict:
    return {"uri": APP_RESOURCE_URI, "name": "tracelens-request-flow",
            "description": "Read-only causal request graph. Bundled assets; no external network access.",
            "mimeType": APP_MIME_TYPE}


def app_resource() -> dict:
    # These are compile-time asset names, never derived from a request.
    css = (WEB_DIR / "trace-graph.css").read_text(encoding="utf-8")
    renderer = (WEB_DIR / "trace-graph.js").read_text(encoding="utf-8")
    bridge = (WEB_DIR / "mcp-app.js").read_text(encoding="utf-8")
    # Avoid accidental HTML end-tag termination inside trusted local JS/CSS.
    css = re.sub(r"</style", r"<\\/style", css, flags=re.IGNORECASE)
    renderer = re.sub(r"</script", r"<\\/script", renderer, flags=re.IGNORECASE)
    bridge = re.sub(r"</script", r"<\\/script", bridge, flags=re.IGNORECASE)
    html = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src data:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'">
<title>TraceLens request flow</title><style>
body { margin: 0; padding: 12px; font: 14px system-ui, sans-serif; background: #101827; color: #e2e8f0; }
html[data-theme="light"] body { background: #f8fafc; color: #172033; }
#app-status, .app-help { line-height: 1.5; overflow-wrap: anywhere; }
.app-help { font-size: 12px; opacity: .8; }
""" + css + """</style></head><body>
<p id="app-status" role="status" aria-live="polite">Connecting to the MCP Apps host…</p>
<main id="request-graph" aria-label="Causal request flow"></main>
<p class="app-help">Read-only retained evidence. Select a segment to inspect its identity; ask the agent to call get_flow with that segment ID, service and instance for source lines. This view never replays requests or changes recording.</p>
<script>""" + renderer + "</script><script>" + bridge + "</script></body></html>"
    return {**app_resource_info(), "text": html,
            "_meta": {"ui": {"prefersBorder": True,
                              "csp": {"connectDomains": [], "resourceDomains": [], "frameDomains": []}}}}
