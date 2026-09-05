"""HTTP client for the in-service /tracelens/* debug API (stdlib only)."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any


class DebugApiError(RuntimeError):
    pass


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
            raise DebugApiError(f"{method} {url} -> {e.code}: {body[:300]}") from e
        except urllib.error.URLError as e:
            raise DebugApiError(
                f"{method} {url} failed: {e.reason} (is the service up with tracelens enabled?)"
            ) from e

    def summary(self) -> dict:
        return self._request("GET", "/summary")

    def traces(self) -> dict:
        return self._request("GET", "/traces")

    def export(self, trace_id: str) -> dict:
        return self._request("GET", f"/export/{trace_id}")

    def find_by_lens_id(self, lens_id: str) -> dict:
        return self._request("GET", f"/find-by-lens-id/{lens_id}")

    def metadata(self) -> dict:
        return self._request("GET", "/metadata")

    def instrumented_classes(self) -> dict:
        return self._request("GET", "/instrumented-classes")

    def enable(self) -> dict:
        return self._request("POST", "/enable")

    def disable(self) -> dict:
        return self._request("POST", "/disable")

    def reset(self, trace_id: str | None = None) -> dict:
        return self._request("POST", "/reset" + (f"/{trace_id}" if trace_id else ""))
