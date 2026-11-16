"""CLI and MCP identify the same installed distribution, including source use."""

from importlib import metadata
from pathlib import Path
import re
import runpy

import pytest

import tracelens_cli
from tracelens_cli import cli
from tracelens_cli.mcp_server import TraceLensMcpServer


def test_package_version_uses_installed_metadata(monkeypatch):
    queried = []

    def version(name):
        queried.append(name)
        return "9.8.7"

    monkeypatch.setattr(metadata, "version", version)
    package = runpy.run_path(tracelens_cli.__file__)

    assert queried == ["tracelens-cli"]
    assert package["__version__"] == "9.8.7"


def test_source_version_fallback_matches_project_and_lock(monkeypatch):
    def missing_version(name):
        raise metadata.PackageNotFoundError(name)

    monkeypatch.setattr(metadata, "version", missing_version)
    package = runpy.run_path(tracelens_cli.__file__)
    project_root = Path(tracelens_cli.__file__).resolve().parent.parent
    for filename in ("pyproject.toml", "uv.lock"):
        match = re.search(r'name = "tracelens-cli"\nversion = "([^"]+)"',
                          (project_root / filename).read_text())
        assert match is not None
        assert package["__version__"] == match.group(1)


def test_cli_version_exits_without_service_or_registry_access(monkeypatch, capsys):
    def unexpected_access(*args, **kwargs):
        pytest.fail("--version must not access a service or registry")

    monkeypatch.setattr(cli, "TraceLensClient", unexpected_access)
    monkeypatch.setattr(cli, "load_services", unexpected_access)

    with pytest.raises(SystemExit) as exited:
        cli.main(["--services", "/nonexistent/services.json", "--version"])

    assert exited.value.code == 0
    captured = capsys.readouterr()
    assert captured.out == f"tracelens {tracelens_cli.__version__}\n"
    assert captured.err == ""


def test_mcp_initialize_reports_package_version():
    server = TraceLensMcpServer("http://unused", None)
    response = server.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})

    assert response["result"]["serverInfo"] == {
        "name": "tracelens", "version": tracelens_cli.__version__,
    }
