"""HTTP client for the in-service /tracelens/* debug API (stdlib only)."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


MAX_TRACE_SUMMARY_BYTES = 1024 * 1024
MAX_TRACE_SUMMARY_TIMEOUT = 5.0


class _SummaryNoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class DebugApiError(RuntimeError):
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


class TraceLensClient:
    def __init__(self, base_url: str = "http://localhost:8082", timeout: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _request(self, method: str, path: str) -> Any:
        url = f"{self.base_url}/tracelens{path}"
        req = urllib.request.Request(url, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            raise DebugApiError(f"{method} {url} -> {e.code}: {body[:300]}", e.code) from e
        except urllib.error.URLError as e:
            raise DebugApiError(
                f"{method} {url} failed: {e.reason} (is the service up with tracelens enabled?)"
            ) from e

    def summary(self) -> dict:
        return self._request("GET", "/summary")

    def traces(self, max_bytes: int = MAX_TRACE_SUMMARY_BYTES) -> dict:
        """Read summaries with a hard wire bound and no redirected endpoint.

        Federation keeps the no-argument call so injectable mock clients remain
        compatible. A caller may tighten, but never enlarge, the byte budget.
        Failures intentionally omit URLs, upstream bodies and exception text.
        """
        if type(max_bytes) is not int or not 1 <= max_bytes <= MAX_TRACE_SUMMARY_BYTES:
            raise ValueError("Trace summary byte limit must be between 1 and 1048576.")
        timeout = self.timeout
        if type(timeout) not in (int, float) or not 0 < timeout <= MAX_TRACE_SUMMARY_TIMEOUT:
            timeout = MAX_TRACE_SUMMARY_TIMEOUT
        try:
            req = urllib.request.Request(self.base_url + "/tracelens/traces", method="GET")
            with urllib.request.build_opener(_SummaryNoRedirect()).open(req, timeout=timeout) as response:
                raw = response.read(max_bytes + 1)
        except urllib.error.HTTPError as exc:
            status = exc.code
            exc.close()  # Never read a potentially large or sensitive error body.
            raise DebugApiError("Trace summary endpoint returned an HTTP error.", status) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise DebugApiError("Trace summary endpoint is unavailable.") from None
        if len(raw) > max_bytes:
            raise DebugApiError("Trace summaries exceed the response byte limit.")
        def reject_constant(_):
            raise ValueError("Non-finite summary value")
        try:
            document = json.loads(raw.decode("utf-8"), parse_constant=reject_constant)
            if (not isinstance(document, dict) or not isinstance(document.get("traces"), list)
                    or any(not isinstance(row, dict) for row in document["traces"])):
                raise ValueError("Invalid trace summaries")
        except (ValueError, UnicodeError, RecursionError, OverflowError):
            raise DebugApiError("Trace summary endpoint returned invalid JSON evidence.") from None
        return document

    def export(self, trace_id: str, evict: bool | None = None) -> dict:
        path = f"/export/{urllib.parse.quote(trace_id, safe='')}"
        if evict is not None:
            path += f"?evict={'true' if evict else 'false'}"
        return self._request("GET", path)

    def find_by_lens_id(self, lens_id: str) -> dict:
        # This endpoint consumes the entire suffix. Preserve literal slashes:
        # Tomcat rejects encoded slashes by default, while labels contain routes.
        encoded = urllib.parse.quote(lens_id, safe="/")
        return self._request("GET", f"/find-by-lens-id/{encoded}")

    def find_by_trace_id(self, trace_id: str) -> dict:
        return self._request("GET", "/find-by-trace-id/" + urllib.parse.quote(trace_id, safe=""))

    def metadata(self) -> dict:
        return self._request("GET", "/metadata")

    def instrumented_classes(self) -> dict:
        return self._request("GET", "/instrumented-classes")

    def enable(self) -> dict:
        return self._request("POST", "/enable")

    def disable(self) -> dict:
        return self._request("POST", "/disable")

    def reset(self, trace_id: str | None = None) -> dict:
        path = "/reset" + (f"/{urllib.parse.quote(trace_id, safe='')}" if trace_id else "")
        return self._request("POST", path)
