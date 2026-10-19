import json
import re
import threading
import urllib.request

import pytest

from tracelens_cli import server
from tracelens_cli.services import Service


def _context(upstream="http://localhost:7778", root=None, services=None):
    return server._viewer_context(upstream, root, services)


def _fetch_context(upstream, root, services=None):
    app = server.make_server(0, upstream, root, services)
    worker = threading.Thread(target=lambda: app.serve_forever(poll_interval=0.01), daemon=True)
    worker.start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{app.server_port}/api/viewer-context", timeout=2) as response:
            assert response.status == 200
            return json.load(response)
    finally:
        app.shutdown()
        app.server_close()
        worker.join(timeout=2)


def test_endpoint_is_local_opaque_and_stable_across_viewer_restarts(tmp_path, monkeypatch):
    def no_upstream(*args, **kwargs):
        raise AssertionError("viewer context must not contact upstream services")

    monkeypatch.setattr(server.Federation, "summary", no_upstream)
    monkeypatch.setattr(server.Federation, "traces", no_upstream)
    upstream = "http://localhost:1/private-agent"
    first = _fetch_context(upstream, str(tmp_path))
    second = _fetch_context(upstream, str(tmp_path))
    assert first == second
    assert first["mode"] == "single"
    assert first["services"] == ["local"]
    assert re.fullmatch("[0-9a-f]{64}", first["namespace"])
    assert set(first) == {"namespace", "mode", "services"}
    assert upstream not in json.dumps(first)
    assert str(tmp_path) not in json.dumps(first)


@pytest.mark.parametrize("left,right", [
    ("http://LOCALHOST:80/", "http://localhost"),
    ("https://EXAMPLE.test:443/agent/", "https://example.test/agent"),
    ("http://[::1]:7778/", "http://[::1]:7778"),
])
def test_equivalent_upstream_spellings_share_namespace(left, right, tmp_path):
    assert _context(left, str(tmp_path)) == _context(right, str(tmp_path))


def test_actual_upstream_and_checkout_changes_get_distinct_namespaces(tmp_path):
    baseline = _context(root=str(tmp_path / "checkout"))["namespace"]
    variants = [
        _context("http://localhost:7779", str(tmp_path / "checkout")),
        _context("https://localhost:7778", str(tmp_path / "checkout")),
        _context("http://localhost:7778/agent", str(tmp_path / "checkout")),
        _context(root=str(tmp_path / "another-checkout")),
    ]
    assert all(item["namespace"] != baseline for item in variants)
    assert _context(root=str(tmp_path / "unused" / ".." / "checkout"))["namespace"] == baseline


def test_single_effective_default_root_matches_source_resolver(tmp_path, monkeypatch):
    first_root, second_root = str(tmp_path / "first"), str(tmp_path / "second")
    monkeypatch.setenv("TRACELENS_SOURCE_ROOT", first_root)
    first = _context()
    assert first == _context(root=first_root)
    monkeypatch.setenv("TRACELENS_SOURCE_ROOT", second_root)
    assert _context() != first
    assert _context(root=first_root) == first
    monkeypatch.delenv("TRACELENS_SOURCE_ROOT")
    monkeypatch.setattr(server.source, "DEFAULT_SOURCE_ROOT", tmp_path / "default")
    assert _context() == _context(root=str(tmp_path / "default"))


def test_federation_registry_reordering_is_stable_but_mapping_changes_are_not(tmp_path):
    one = Service("orders", "http://localhost:7778", str(tmp_path / "orders"))
    two = Service("billing", "http://localhost:7779", str(tmp_path / "billing"))
    context = _context(services=[one, two])
    assert context == _context(services=[two, one])
    assert context["mode"] == "federated"
    assert context["services"] == ["billing", "orders"]
    variants = [
        [one],
        [one, Service("renamed", two.url, two.source_root)],
        [one, Service(two.name, "http://localhost:7780", two.source_root)],
        [one, Service(two.name, two.url, str(tmp_path / "billing-v2"))],
    ]
    assert all(_context(services=items)["namespace"] != context["namespace"] for items in variants)
    assert _context(services=[Service("local", one.url, one.source_root)])["namespace"] != _context(
        one.url, one.source_root)["namespace"]


def test_federated_endpoint_ignores_unused_global_upstream_and_source_defaults(tmp_path, monkeypatch):
    services = [Service("orders", "http://localhost:1/private-upstream", str(tmp_path / "orders")),
                Service("no-source", "http://localhost:2", None)]
    monkeypatch.setenv("TRACELENS_SOURCE_ROOT", str(tmp_path / "unused-default"))
    first = _fetch_context("http://unused-one", str(tmp_path / "unused-one"), services)
    monkeypatch.setenv("TRACELENS_SOURCE_ROOT", str(tmp_path / "different-unused-default"))
    second = _fetch_context("http://unused-two", str(tmp_path / "unused-two"), services)
    assert first == second
    assert first == _context(services=services)
    assert str(tmp_path) not in json.dumps(first)
    assert "private-upstream" not in json.dumps(first)
