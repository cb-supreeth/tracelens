"""Exercise the optional launcher without starting Tomcat or the orchestrator."""

import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "scripts" / "Makefile.cb-app-tracelens"


def test_bundled_service_identity_matches_registry_not_repository_name():
    agent_config = json.loads((ROOT / "scripts/cb-app-tracelens.json").read_text())
    registry = json.loads((ROOT / "scripts/services-local.json").read_text())
    app = next(service for service in registry["services"] if service["name"] == "cb-app")
    assert agent_config["applicationId"] == app["name"] == "cb-app"
    assert Path(app["sourceRoot"]).name == "chargebee-app"


@pytest.fixture
def checkout(tmp_path):
    app = tmp_path / "chargebee-app"
    app.mkdir()
    (app / "local-setup").mkdir()
    (app / "tomcat/bin").mkdir(parents=True)
    lens = tmp_path / "cb-tracelens"
    (lens / "scripts").mkdir(parents=True)
    (lens / "tracelens-agent/build/libs").mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/cb-app-setenv.sh", lens / "scripts/cb-app-setenv.sh")
    (lens / "scripts/cb-app-tracelens.json").write_text("{}")
    (lens / "tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar").touch()
    (app / "tomcat/bin/setenv.sh").write_text(
        '. "$TRACELENS_HOME/scripts/cb-app-setenv.sh" || exit 1\n'
    )
    (app / "Makefile").write_text(
        ".PHONY: up start ensure-orchestrator\n"
        "up start:\n"
        "\t@printf 'LIFECYCLE=%s TRACELENS=%s PORT=%s PATHS=%s CONFIG=%s\\n' "
        "'$@' \"$$TRACELENS\" \"$$TRACELENS_PORT\" "
        '\"$$TRACELENS_HTTP_PATHS\" \"$$TRACELENS_CONFIG\"\n'
        "\t@printf 'BEFORE_OPTS=%s\\n' \"$${CATALINA_OPTS-}\"\n"
        "\t@. tomcat/bin/setenv.sh && printf 'AFTER_OPTS=%s\\n' \"$$CATALINA_OPTS\"\n"
        "ensure-orchestrator:\n"
        "\t@echo LIFECYCLE=ensure-orchestrator\n"
    )
    return app, lens


def run_make(cwd, *args, extra_env=None):
    env = {
        key: value for key, value in os.environ.items()
        if not key.startswith("TRACELENS")
        and key not in {"CATALINA_OPTS", "MAKEFLAGS", "MFLAGS", "MAKELEVEL", "MAKEOVERRIDES"}
    }
    env.update(extra_env or {})
    return subprocess.run(
        ["make", "--no-print-directory", "-f", str(WRAPPER), *args],
        cwd=cwd, env=env, text=True, capture_output=True, timeout=10,
    )


def test_help_needs_no_checkout(tmp_path):
    result = run_make(tmp_path, "help")
    assert result.returncode == 0, result.stderr
    assert "healthy JVMs are reused" in result.stdout


def test_check_is_read_only(checkout):
    app, _ = checkout
    result = run_make(app, "check")
    assert result.returncode == 0, result.stderr
    assert "checks passed" in result.stdout
    assert "LIFECYCLE=" not in result.stdout


def test_default_up_attaches_once_and_preserves_existing_options(checkout):
    app, _ = checkout
    result = run_make(app, extra_env={"CATALINA_OPTS": "-Xmx1g"})
    assert result.returncode == 0, result.stderr
    assert "LIFECYCLE=up TRACELENS=1 PORT=7778 PATHS=/api/v2/features" in result.stdout
    assert "BEFORE_OPTS=-Xmx1g\n" in result.stdout
    assert "AFTER_OPTS=-Xmx1g -javaagent:" in result.stdout
    assert result.stdout.count("-javaagent:") == 1


def test_up_forces_opt_in_even_with_conflicting_outer_variable(checkout):
    app, _ = checkout
    result = run_make(app, "up", "TRACELENS=0")
    assert result.returncode == 0, result.stderr
    assert "LIFECYCLE=up TRACELENS=1" in result.stdout


def test_overrides_reach_the_real_startup_hook(checkout):
    app, lens = checkout
    custom = lens.with_name("custom-tracelens")
    lens.rename(custom)
    (custom / "scripts/custom.json").write_text("{}")
    (custom / "tracelens-agent/build/libs/tracelens-agent-test-agent.jar").touch()
    result = run_make(
        app, "up", f"TRACELENS_HOME={custom}", "TRACELENS_VERSION=test",
        f"TRACELENS_CONFIG={custom}/scripts/custom.json",
        "TRACELENS_PORT=7782", "TRACELENS_HTTP_PATHS=/api/v2/features,/api/v2/items",
    )
    assert result.returncode == 0, result.stderr
    assert "PORT=7782 PATHS=/api/v2/features,/api/v2/items" in result.stdout
    assert f"-javaagent:{custom}/tracelens-agent/build/libs/tracelens-agent-test-agent.jar" in result.stdout
    assert f"-Dtracelens.configPath={custom}/scripts/custom.json" in result.stdout


def test_restart_delegates_in_order_without_full_up(checkout):
    app, _ = checkout
    result = run_make(app, "restart")
    assert result.returncode == 0, result.stderr
    assert "clears existing Tomcat logs" in result.stdout
    assert result.stdout.index("LIFECYCLE=ensure-orchestrator") < result.stdout.index("LIFECYCLE=start")
    assert "LIFECYCLE=start TRACELENS=1" in result.stdout
    assert "LIFECYCLE=up" not in result.stdout
    assert result.stdout.count("-javaagent:") == 1


@pytest.mark.parametrize("missing", [
    "Makefile", "local-setup", "tomcat/bin/setenv.sh",
    "../cb-tracelens/scripts/cb-app-setenv.sh",
    "../cb-tracelens/scripts/cb-app-tracelens.json",
    "../cb-tracelens/tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar",
])
def test_missing_prerequisite_blocks_lifecycle(checkout, missing):
    app, _ = checkout
    path = app / missing
    path.rmdir() if path.is_dir() else path.unlink()
    result = run_make(app, "up")
    assert result.returncode != 0
    assert "LIFECYCLE=" not in result.stdout


def test_commented_out_hook_does_not_pass_check(checkout):
    app, _ = checkout
    (app / "tomcat/bin/setenv.sh").write_text('# . "$TRACELENS_HOME/scripts/cb-app-setenv.sh"\n')
    result = run_make(app, "up")
    assert result.returncode != 0
    assert "not wired" in result.stderr
    assert "LIFECYCLE=" not in result.stdout


@pytest.mark.parametrize("port", ["not-a-port", "-1", "65536", "99999999999999999999999999999"])
def test_invalid_port_blocks_lifecycle(checkout, port):
    app, _ = checkout
    result = run_make(app, "up", f"TRACELENS_PORT={port}")
    assert result.returncode != 0
    assert "LIFECYCLE=" not in result.stdout


@pytest.mark.parametrize("setting", [
    "TRACELENS_HOME=../cb-tracelens",
    "TRACELENS_CONFIG=../cb-tracelens/scripts/cb-app-tracelens.json",
])
def test_relative_path_overrides_are_rejected_before_cwd_changes(checkout, setting):
    app, _ = checkout
    result = run_make(app, "up", setting)
    assert result.returncode != 0
    assert "must be absolute paths" in result.stderr
    assert "LIFECYCLE=" not in result.stdout


@pytest.mark.parametrize("port", ["0", "65536", "invalid", "99999999999999999999999999999"])
def test_status_rejects_invalid_port_without_network(tmp_path, port):
    result = run_make(tmp_path, "status", f"TRACELENS_PORT={port}")
    assert result.returncode != 0
    assert "65535" in result.stderr
