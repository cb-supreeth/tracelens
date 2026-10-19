"""HTTP client for the in-service /tracelens/* debug API (stdlib only)."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


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

    def traces(self) -> dict:
        return self._request("GET", "/traces")

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
