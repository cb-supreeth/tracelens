"""Bootstrap regressions without cloning a real repo, building Java, or starting cb-app."""

import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import sys
import zipfile

import pytest


ROOT = Path(__file__).resolve().parents[2]
FRAGMENT = ROOT / "scripts/cb-app-up-tracelens.mk"
SPEC = importlib.util.spec_from_file_location("cb_app_bootstrap_under_test", ROOT / "scripts/bootstrap_cb_app.py")
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


@pytest.fixture
def checkout(tmp_path, monkeypatch):
    app, lens = tmp_path / "chargebee-app", tmp_path / "cb-tracelens"
    (app / "tomcat/bin").mkdir(parents=True)
    (app / "Makefile").write_text(".PHONY: up start health\nup start health:\n\t@false\n")
    hook = app / "tomcat/bin/setenv.sh"
    hook.write_text('#!/bin/sh\nCATALINA_OPTS="${CATALINA_OPTS:-} -Xmx1g"\nexport CUSTOM_SETTING=preserved\n')
    hook.chmod(0o640)
    (lens / ".git").mkdir(parents=True)
    (lens / "scripts").mkdir()
    (lens / "scripts/cb-app").mkdir()
    (lens / "gradlew").write_text("#!/bin/sh\nexit 99\n")
    (lens / "build.gradle").write_text("allprojects {\n    version = '0.1.1-SNAPSHOT'\n}\n")
    template = {"applicationId": "cb-app", "packages": ["com.example"], "applicationVersion": "LOCAL",
                "httpIncludePaths": ["/api/v2/features"]}
    (lens / "scripts/cb-app/tracelens.json").write_text(json.dumps(template))
    state = app / ".run/tracelens"
    calls = {"build": [], "summary": [], "start": [], "ownership": []}

    def build(path, env):
        calls["build"].append((path, dict(env)))
        return bootstrap.agent_path(path)

    def summary(port):
        calls["summary"].append(port)
        return None

    monkeypatch.setattr(bootstrap, "build_agent", build)
    monkeypatch.setattr(bootstrap, "summary", summary)
    monkeypatch.setattr(bootstrap, "port_in_use", lambda port: False)
    monkeypatch.setattr(bootstrap, "require_native_listener", lambda app, port: calls["ownership"].append((app, port)))
    monkeypatch.setattr(bootstrap, "start_app", lambda *args: calls["start"].append(args))
    return {"app": app, "lens": lens, "hook": hook, "state": state, "calls": calls, "template": template}


def settings(tmp_path):
    return {"TRACELENS_HOME": str(tmp_path / "cb-tracelens"),
            "TRACELENS_AGENT": str(tmp_path / "agent.jar"),
            "TRACELENS_CONFIG": str(tmp_path / "tracelens.json"),
            "TRACELENS_PORT": "7778"}


def ready_summary(instance="fresh", **changes):
    return {"mode": "standalone", "applicationId": "cb-app", "port": 7778,
            "agentActive": True, "recordingEnabled": True, "packages": ["com.example"],
            "httpIncludePaths": ["/api/v2/features"],
            "serviceInstanceId": instance,
            "bridge": {"sinkRegistered": True, "httpListenerRegistered": True}, **changes}


def test_config_preserves_existing_customization(tmp_path):
    template, existing = tmp_path / "template.json", tmp_path / "custom.json"
    template.write_text(json.dumps({"applicationId": "cb-app", "packages": ["template"],
                                    "httpIncludePaths": ["/api/v2/features"]}))
    custom = {"applicationId": "cb-app", "packages": ["custom"], "requireLabels": True,
              "httpIncludePaths": ["/subscriptions", "/search/subscriptions"],
              "httpPropagationAllowedDestinations": ["localhost:8082"], "customKey": {"keep": 1}}
    original = json.dumps(custom, separators=(",", ":"))
    existing.write_text(original)
    assert bootstrap.configured_json(template, existing) == custom
    assert existing.read_text() == original
    assert bootstrap.configured_json(template, tmp_path / "missing.json")["packages"] == ["template"]


@pytest.mark.parametrize("value", [[], {"applicationId": "chargebee-app", "packages": ["a"]},
                                  {"applicationId": "cb-app", "packages": []},
                                  {"applicationId": "cb-app", "packages": [None]}])
def test_invalid_config_is_rejected(tmp_path, value):
    path = tmp_path / "config.json"
    path.write_text(json.dumps(value))
    with pytest.raises(bootstrap.SetupError):
        bootstrap.configured_json(path, path)


@pytest.mark.parametrize("paths", [None, "/subscriptions", {}, [None], ["relative"], [""],
                                  ["/a?x=1"], ["/a,*"], ["/a b"], ["/" + "a" * 512], ["/a"] * 65])
def test_invalid_json_capture_paths_fail_without_replacing_the_source(tmp_path, paths):
    config = tmp_path / "config.json"
    original = json.dumps({"applicationId": "cb-app", "packages": ["com.example"], "httpIncludePaths": paths})
    config.write_text(original)
    with pytest.raises(bootstrap.SetupError, match="httpIncludePaths"):
        bootstrap.configured_json(config, config)
    assert config.read_text() == original


@pytest.mark.parametrize("paths", [[], ["/"], ["/a"] * 63 + ["/" + "a" * 511]])
def test_explicit_empty_all_and_bounded_json_path_lists_are_preserved(tmp_path, paths):
    config = tmp_path / "config.json"
    original = json.dumps({"applicationId": "cb-app", "packages": ["com.example"], "httpIncludePaths": paths})
    config.write_text(original)
    assert bootstrap.configured_json(config, config)["httpIncludePaths"] == paths
    assert config.read_text() == original


def test_registry_updates_only_app_connection_and_preserves_other_settings(tmp_path):
    path = tmp_path / "services.json"
    value = {"custom": "keep", "services": [
        {"name": "cb-ems", "url": "http://localhost:7779", "sourceRoot": "/ems"},
        {"name": "cb-app", "url": "http://old:1", "sourceRoot": "/old", "dependencyAliases": {"a": "b"}},
    ]}
    original = json.dumps(value)
    path.write_text(original)
    result = bootstrap.registry_json(path, Path("/work/chargebee-app"), 7788)
    assert result["custom"] == "keep"
    assert result["services"][0] == value["services"][0]
    assert result["services"][1] == {**value["services"][1], "url": "http://127.0.0.1:7788",
                                     "sourceRoot": "/work/chargebee-app"}
    assert path.read_text() == original  # Pure projection, not a write.
    path.write_text(json.dumps(result))
    assert bootstrap.registry_json(path, Path("/work/chargebee-app"), 7788) == result


def test_registry_rejects_duplicate_app_entries(tmp_path):
    path = tmp_path / "services.json"
    path.write_text(json.dumps({"services": [{"name": "cb-app"}, {"name": "cb-app"}]}))
    with pytest.raises(bootstrap.SetupError, match="Duplicate"):
        bootstrap.registry_json(path, tmp_path, 7778)


@pytest.mark.parametrize("legacy", ["", '. "$TRACELENS_HOME/scripts/cb-app-setenv.sh"\n',
                                     '. "/old/scripts/cb-app-setenv.sh" || exit 1\n'])
def test_setenv_merge_migrates_one_hook_idempotently_and_preserves_shell(tmp_path, legacy):
    before = '#!/bin/sh\nexport CUSTOM="keep exactly"\n'
    after = 'CATALINA_OPTS="${CATALINA_OPTS:-} -Xmx1g"\n'
    merged = bootstrap.wired_setenv(before + legacy + after, settings(tmp_path))
    assert merged.count(bootstrap.BEGIN) == merged.count(bootstrap.END) == 1
    assert merged.count('. "$TRACELENS_HOME/scripts/cb-app-setenv.sh"') == 1
    assert before in merged and after in merged
    assert bootstrap.wired_setenv(merged, settings(tmp_path)) == merged
    subprocess.run(["sh", "-n"], input=merged, text=True, check=True, capture_output=True)


@pytest.mark.parametrize("old", [
    '. "/a/cb-app-setenv.sh"\n. "/b/cb-app-setenv.sh"\n',
    'CATALINA_OPTS="-javaagent:/a/tracelens-agent.jar=standalone"\n',
    'if true; then . "/a/cb-app-setenv.sh"; fi\n',
    bootstrap.BEGIN + "\n" + bootstrap.BEGIN + "\n" + bootstrap.END + "\n",
    bootstrap.END + "\n" + bootstrap.BEGIN + "\n",
])
def test_setenv_merge_rejects_duplicate_manual_or_ambiguous_hooks(tmp_path, old):
    with pytest.raises(bootstrap.SetupError):
        bootstrap.wired_setenv(old, settings(tmp_path))


def test_managed_block_plus_extra_hook_is_rejected(tmp_path):
    managed = bootstrap.wired_setenv("", settings(tmp_path))
    with pytest.raises(bootstrap.SetupError, match="Multiple"):
        bootstrap.wired_setenv(managed + '. "/other/cb-app-setenv.sh"\n', settings(tmp_path))


@pytest.mark.parametrize("value", ["relative/path", "/tmp/with space", "/tmp/evil;echo", "/tmp/$(command)"])
def test_unsafe_paths_are_rejected(value):
    with pytest.raises(bootstrap.SetupError):
        bootstrap.safe_path(value, "test")


def test_prepare_only_preserves_custom_files_and_is_idempotent(checkout):
    app, lens, state, hook = (checkout[key] for key in ("app", "lens", "state", "hook"))
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    custom = {**checkout["template"], "packages": ["custom.scope"], "requireLabels": True,
              "httpIncludePaths": ["/subscriptions", "/search/subscriptions"]}
    config_text = json.dumps(custom, separators=(",", ":"))
    config.write_text(config_text)
    registry = state / "services.json"
    registry.write_text(json.dumps({"services": [{"name": "cb-ems", "url": "http://localhost:7779"}], "custom": True}))
    old = hook.read_text()
    env = {"TRACELENS_PREPARE_ONLY": "1", "TRACELENS_PORT": "7788"}
    bootstrap.setup(app, lens, env)
    first_hook = hook.read_text()
    backups = list(state.glob("*.bak"))
    assert len(backups) == 1 and backups[0].read_text() == old
    assert config.read_text() == config_text
    assert stat.S_IMODE(hook.stat().st_mode) == 0o640
    assert old in first_hook
    assert "TRACELENS_HTTP_PATHS" not in first_hook
    assert "tracelens.http.includePaths" not in first_hook
    saved_registry = json.loads(registry.read_text())
    assert saved_registry["custom"] is True
    assert saved_registry["services"][0]["name"] == "cb-ems"
    assert saved_registry["services"][1]["url"] == "http://127.0.0.1:7788"
    bootstrap.setup(app, lens, env)
    assert hook.read_text() == first_hook
    assert config.read_text() == config_text
    assert list(state.glob("*.bak")) == backups
    assert len(checkout["calls"]["build"]) == 2
    assert checkout["calls"]["summary"] == checkout["calls"]["start"] == []
    assert checkout["calls"]["ownership"] == []


def legacy_path_hook(checkout, assignment='TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-/legacy,/search/subscriptions}"'):
    """A previously generated managed block, not the new writer's output."""
    original = checkout["hook"].read_text()
    managed = (bootstrap.BEGIN + '\nif [ "${TRACELENS:-0}" = "1" ]; then\n'
               + f'    {assignment}\n'
               + '    . "$TRACELENS_HOME/scripts/cb-app-setenv.sh" || exit 1\nfi\n'
               + bootstrap.END + "\n")
    checkout["hook"].write_text(original + managed)
    return original + managed


def test_new_setup_initializes_paths_from_template_in_json_only(checkout):
    bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    config = json.loads((checkout["state"] / "tracelens.json").read_text())
    assert config == checkout["template"]
    assert "TRACELENS_HTTP_PATHS" not in checkout["hook"].read_text()
    assert "tracelens.http.includePaths" not in checkout["hook"].read_text()
    assert checkout["calls"]["start"] == checkout["calls"]["summary"] == []


def test_default_setup_renames_legacy_config_after_build_preserving_bytes_mode_and_customization(checkout, monkeypatch):
    state = checkout["state"]
    state.mkdir(parents=True)
    legacy, current = state / "cb-app.json", state / "tracelens.json"
    custom = {**checkout["template"], "packages": ["custom.scope"], "requireLabels": True,
              "httpIncludePaths": ["/subscriptions"], "custom": {"preserved": True}}
    original = (json.dumps(custom, indent=2) + "\n").replace("\n", "\r\n").encode()
    legacy.write_bytes(original)
    legacy.chmod(0o640)
    checkout["hook"].write_text(bootstrap.wired_setenv(checkout["hook"].read_text(), {
        **settings(checkout["app"]), "TRACELENS_CONFIG": str(legacy)}))
    old_hook = checkout["hook"].read_text()

    def build(path, env):
        if not checkout["calls"]["build"]:
            assert legacy.read_bytes() == original and not current.exists()
            assert checkout["hook"].read_text() == old_hook
        checkout["calls"]["build"].append((path, dict(env)))
        return bootstrap.agent_path(path)

    monkeypatch.setattr(bootstrap, "build_agent", build)
    env = {"TRACELENS_PREPARE_ONLY": "1"}
    bootstrap.setup(checkout["app"], checkout["lens"], env)
    assert not legacy.exists()
    assert current.read_bytes() == original
    assert stat.S_IMODE(current.stat().st_mode) == 0o640
    assert str(current) in checkout["hook"].read_text()
    assert str(legacy) not in checkout["hook"].read_text()
    new_hook, backups = checkout["hook"].read_text(), set(state.glob("*.bak"))
    bootstrap.setup(checkout["app"], checkout["lens"], env)
    assert current.read_bytes() == original and not legacy.exists()
    assert checkout["hook"].read_text() == new_hook
    assert set(state.glob("*.bak")) == backups
    assert checkout["calls"]["start"] == checkout["calls"]["summary"] == []


@pytest.mark.parametrize("same_contents", [False, True])
def test_default_setup_rejects_both_config_names_without_guessing(checkout, same_contents):
    state = checkout["state"]
    state.mkdir(parents=True)
    legacy, current = state / "cb-app.json", state / "tracelens.json"
    original = json.dumps(checkout["template"])
    other = original if same_contents else json.dumps({**checkout["template"], "httpIncludePaths": ["/subscriptions"]})
    legacy.write_text(original); current.write_text(other)
    old_hook = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="Both cb-app.json and tracelens.json"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert legacy.read_text() == original and current.read_text() == other
    assert checkout["hook"].read_bytes() == old_hook
    assert not (state / "services.json").exists() and not list(state.glob("*.bak"))
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


def test_legacy_config_is_not_renamed_when_build_fails(checkout, monkeypatch):
    state = checkout["state"]
    state.mkdir(parents=True)
    legacy, current = state / "cb-app.json", state / "tracelens.json"
    original = json.dumps({**checkout["template"], "custom": True})
    legacy.write_text(original)
    old_hook = checkout["hook"].read_bytes()

    def fail(*args):
        raise subprocess.CalledProcessError(1, ["fake-gradle"])

    monkeypatch.setattr(bootstrap, "build_agent", fail)
    with pytest.raises(subprocess.CalledProcessError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert legacy.read_text() == original and not current.exists()
    assert checkout["hook"].read_bytes() == old_hook
    assert not (state / "services.json").exists() and not list(state.glob("*.bak"))
    assert checkout["calls"]["start"] == []


@pytest.mark.parametrize("selected", ["legacy", "current", "custom"])
def test_explicit_config_override_keeps_all_existing_files_and_skips_automatic_rename(checkout, selected):
    state = checkout["state"]
    state.mkdir(parents=True)
    paths = {"legacy": state / "cb-app.json", "current": state / "tracelens.json",
             "custom": checkout["app"] / "custom-capture.json"}
    contents = {}
    for label, path in paths.items():
        contents[label] = json.dumps({**checkout["template"], "httpIncludePaths": ["/" + label], "custom": label})
        path.write_text(contents[label])
    bootstrap.setup(checkout["app"], checkout["lens"], {
        "TRACELENS_PREPARE_ONLY": "1", "TRACELENS_CONFIG": str(paths[selected])})
    assert all(path.read_text() == contents[label] for label, path in paths.items())
    assert str(paths[selected]) in checkout["hook"].read_text()
    assert checkout["calls"]["start"] == checkout["calls"]["summary"] == []


def test_config_rename_and_literal_http_path_migration_preserve_old_custom_settings(checkout):
    state = checkout["state"]
    state.mkdir(parents=True)
    legacy, current = state / "cb-app.json", state / "tracelens.json"
    custom = {key: value for key, value in checkout["template"].items() if key != "httpIncludePaths"}
    custom.update(requireLabels=True, custom={"keep": 1})
    original = json.dumps(custom)
    legacy.write_text(original)
    legacy_path_hook(checkout)
    bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert not legacy.exists()
    assert json.loads(current.read_text()) == {**custom, "httpIncludePaths": ["/legacy", "/search/subscriptions"]}
    assert list(state.glob("cb-app-config-*.json.bak"))[0].read_text() == original
    assert "TRACELENS_HTTP_PATHS" not in checkout["hook"].read_text()
    assert str(current) in checkout["hook"].read_text()


@pytest.mark.parametrize("change", ["new_destination", "edited_legacy"])
def test_config_rename_refuses_files_changed_during_build(checkout, monkeypatch, change):
    state = checkout["state"]
    state.mkdir(parents=True)
    legacy, current = state / "cb-app.json", state / "tracelens.json"
    original = json.dumps(checkout["template"])
    modified = json.dumps({**checkout["template"], "custom": "concurrent edit"})
    legacy.write_text(original)
    old_hook = checkout["hook"].read_bytes()

    def build(path, env):
        (current if change == "new_destination" else legacy).write_text(modified)
        return bootstrap.agent_path(path)

    monkeypatch.setattr(bootstrap, "build_agent", build)
    with pytest.raises(bootstrap.SetupError, match="changed during the build"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert legacy.read_text() == (original if change == "new_destination" else modified)
    if change == "new_destination":
        assert current.read_text() == modified
    else:
        assert not current.exists()
    assert checkout["hook"].read_bytes() == old_hook
    assert not (state / "services.json").exists()
    assert checkout["calls"]["start"] == []


def test_missing_json_paths_without_legacy_scope_fail_instead_of_defaulting_to_template_or_all(checkout):
    state = checkout["state"]
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    custom = {key: value for key, value in checkout["template"].items() if key != "httpIncludePaths"}
    original = json.dumps(custom)
    config.write_text(original)
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="httpIncludePaths"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert config.read_text() == original
    assert checkout["hook"].read_bytes() == old
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("existing", [False, True])
def test_legacy_managed_paths_migrate_to_missing_json_field_without_template_broadening(checkout, existing):
    state = checkout["state"]
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    custom = {**checkout["template"], "requireLabels": True, "customKey": {"preserve": 1}}
    custom.pop("httpIncludePaths")
    if existing:
        config.write_text(json.dumps(custom))
    original_config = config.read_text() if existing else None
    old = legacy_path_hook(checkout)
    bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    expected = custom if existing else checkout["template"]
    assert json.loads(config.read_text()) == {**expected, "httpIncludePaths": ["/legacy", "/search/subscriptions"]}
    assert list(state.glob("setenv-*.sh.bak"))[0].read_text() == old
    json_backups = list(state.glob("cb-app-config-*.json.bak"))
    assert len(json_backups) == int(existing)
    if existing:
        assert json_backups[0].read_text() == original_config
    backups = set(state.glob("*.bak"))
    new_hook, new_config = checkout["hook"].read_text(), config.read_text()
    assert "TRACELENS_HTTP_PATHS" not in new_hook
    assert "tracelens.http.includePaths" not in new_hook
    bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["hook"].read_text() == new_hook
    assert config.read_text() == new_config
    assert set(state.glob("*.bak")) == backups
    assert checkout["calls"]["start"] == checkout["calls"]["summary"] == []


def test_matching_legacy_paths_remove_only_obsolete_hook_and_preserve_json_bytes(checkout):
    state = checkout["state"]
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    custom = {**checkout["template"], "httpIncludePaths": ["/legacy", "/search/subscriptions"], "requireLabels": True}
    original = json.dumps(custom, separators=(",", ":"))
    config.write_text(original)
    legacy_path_hook(checkout)
    bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert config.read_text() == original
    assert "TRACELENS_HTTP_PATHS" not in checkout["hook"].read_text()


def test_migration_does_not_write_json_or_drop_legacy_scope_if_build_fails(checkout, monkeypatch):
    state = checkout["state"]
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    original = json.dumps({key: value for key, value in checkout["template"].items() if key != "httpIncludePaths"})
    config.write_text(original)
    old = legacy_path_hook(checkout)

    def fail(*args):
        raise subprocess.CalledProcessError(1, ["fake-gradle"])

    monkeypatch.setattr(bootstrap, "build_agent", fail)
    with pytest.raises(subprocess.CalledProcessError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert config.read_text() == original
    assert checkout["hook"].read_text() == old
    assert not list(state.glob("*.bak"))
    assert not (state / "services.json").exists()
    assert checkout["calls"]["start"] == []


@pytest.mark.parametrize("paths", [["/different"], ["/"], []])
def test_conflicting_json_and_legacy_paths_fail_without_overwriting_or_broadening(checkout, paths):
    state = checkout["state"]
    state.mkdir(parents=True)
    config = state / "tracelens.json"
    original = json.dumps({**checkout["template"], "httpIncludePaths": paths})
    config.write_text(original)
    old = legacy_path_hook(checkout)
    with pytest.raises(bootstrap.SetupError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["hook"].read_text() == old
    assert config.read_text() == original
    assert not (state / "services.json").exists()
    assert not list(state.glob("*.bak"))
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("assignment", [
    'TRACELENS_HTTP_PATHS="$OTHER_PATHS"',
    'TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-$OTHER_PATHS}"',
    'TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-}"',
    'TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-/a,,/b}"',
    'TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-/a,relative}"',
    'TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-/a}"\n    TRACELENS_HTTP_PATHS="/b"',
])
def test_ambiguous_legacy_path_assignments_are_not_evaluated_or_silently_removed(checkout, assignment):
    old = legacy_path_hook(checkout, assignment)
    with pytest.raises(bootstrap.SetupError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["hook"].read_text() == old
    assert not (checkout["state"] / "tracelens.json").exists()
    assert not list(checkout["state"].glob("*.bak"))
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("value", ["", "/api/v2/features", "/", "/subscriptions,/search/subscriptions"])
def test_obsolete_environment_paths_are_rejected_even_when_empty_or_valid(checkout, value):
    before = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="httpIncludePaths"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1", "TRACELENS_HTTP_PATHS": value})
    assert checkout["hook"].read_bytes() == before
    assert not (checkout["state"] / "tracelens.json").exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("key", bootstrap.JAVA_OPTIONS)
def test_obsolete_jvm_capture_paths_are_rejected_before_build(checkout, key):
    before = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="httpIncludePaths"):
        bootstrap.setup(checkout["app"], checkout["lens"], {
            "TRACELENS_PREPARE_ONLY": "1", key: "-Xmx1g -Dtracelens.http.includePaths=/subscriptions"})
    assert checkout["hook"].read_bytes() == before
    assert not (checkout["state"] / "tracelens.json").exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("line", [
    'export TRACELENS_HTTP_PATHS="/manual"\n',
    'CATALINA_OPTS="${CATALINA_OPTS:-} -Dtracelens.http.includePaths=/manual"\n',
])
def test_manual_legacy_capture_scope_outside_managed_block_requires_explicit_resolution(checkout, line):
    checkout["hook"].write_text(checkout["hook"].read_text() + line)
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="httpIncludePaths"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["hook"].read_bytes() == old
    assert not (checkout["state"] / "tracelens.json").exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


def test_build_failure_does_not_change_configuration_or_hook(checkout, monkeypatch):
    state, hook = checkout["state"], checkout["hook"]
    state.mkdir(parents=True)
    registry = state / "services.json"
    registry.write_text('{"services": [], "custom": true}\n')
    before = hook.read_bytes(), registry.read_bytes()

    def fail(*args):
        raise subprocess.CalledProcessError(1, ["fake-gradle"])

    monkeypatch.setattr(bootstrap, "build_agent", fail)
    with pytest.raises(subprocess.CalledProcessError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert (hook.read_bytes(), registry.read_bytes()) == before
    assert not (state / "tracelens.json").exists()
    assert not list(state.glob("*.bak"))
    assert checkout["calls"]["start"] == []


@pytest.mark.parametrize("key,value", [
    ("TRACELENS_PORT", "0"), ("TRACELENS_PORT", "65536"), ("TRACELENS_PORT", "no"),
    ("TRACELENS_START_TIMEOUT", "0"), ("TRACELENS_START_TIMEOUT", "1801"),
    ("TRACELENS_HTTP_PATHS", "relative"), ("TRACELENS_HTTP_PATHS", "/a,relative"),
    ("TRACELENS_HTTP_PATHS", "/a;echo"), ("TRACELENS_CONFIG", "relative.json"),
])
def test_invalid_setup_options_fail_before_build(checkout, key, value):
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError):
        bootstrap.setup(checkout["app"], checkout["lens"], {key: value, "TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["hook"].read_bytes() == old
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("key", bootstrap.JAVA_OPTIONS)
def test_existing_environment_attachment_fails_before_build(checkout, key):
    with pytest.raises(bootstrap.SetupError, match="Existing TraceLens attachment"):
        bootstrap.setup(checkout["app"], checkout["lens"], {key: "-Xmx1g -javaagent:/a/TraceLens-agent.jar"})
    assert checkout["calls"]["build"] == []


@pytest.mark.parametrize("target", ["config", "legacy-config", "registry", "hook", "lock", "directory"])
@pytest.mark.parametrize("dangling", [False, True])
def test_setup_rejects_symlinked_targets_before_build(checkout, tmp_path, target, dangling):
    state = checkout["state"]
    state.mkdir(parents=True)
    if target == "directory":
        path = state
        path.rmdir()
        destination = tmp_path / "elsewhere-dir"
        if not dangling:
            destination.mkdir()
    else:
        path = {"config": state / "tracelens.json", "legacy-config": state / "cb-app.json", "registry": state / "services.json",
                "hook": checkout["hook"], "lock": state / "setup.lock"}[target]
        if path.exists():
            path.unlink()
        destination = tmp_path / "elsewhere-file"
        if not dangling:
            value = {"services": []} if target == "registry" else checkout["template"]
            destination.write_text(json.dumps(value))
    path.symlink_to(destination, target_is_directory=target == "directory")
    with pytest.raises(bootstrap.SetupError):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []
    assert path.is_symlink()


def test_setup_passes_prior_instance_and_preserves_application_environment(checkout, monkeypatch):
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary("before"))
    env = {"JAVA_HOME": "/app/java8", "JRE_HOME": "/app/jre8", "PATH": "/app/bin"}
    bootstrap.setup(checkout["app"], checkout["lens"], env)
    app, child, port, config, previous, timeout = checkout["calls"]["start"][0]
    assert app == checkout["app"] and port == 7778 and previous == "before" and timeout == 180
    assert config == checkout["template"]
    assert child["JAVA_HOME"] == "/app/java8" and child["TRACELENS"] == "1"
    assert child["TRACELENS_CONFIG"] == str(checkout["state"] / "tracelens.json")
    assert "TRACELENS_HTTP_PATHS" not in child
    assert env == {"JAVA_HOME": "/app/java8", "JRE_HOME": "/app/jre8", "PATH": "/app/bin"}
    assert checkout["calls"]["ownership"] == [(checkout["app"], 8080), (checkout["app"], 7778)]


def test_foreign_debug_service_blocks_build(checkout, monkeypatch):
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary(applicationId="cb-ems"))
    with pytest.raises(bootstrap.SetupError, match="something other"):
        bootstrap.setup(checkout["app"], checkout["lens"], {})
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


def test_occupied_non_tracelens_port_blocks_build_and_writes(checkout, monkeypatch):
    monkeypatch.setattr(bootstrap, "port_in_use", lambda port: True)
    before = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="occupied"):
        bootstrap.setup(checkout["app"], checkout["lens"], {})
    assert checkout["hook"].read_bytes() == before
    assert not (checkout["state"] / "tracelens.json").exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.mark.parametrize("env,make_setting,expected", [
    ({}, "", 8080), ({}, "STATUS_URL ?= http://localhost:8083/health\n", 8083),
    ({"STATUS_URL": "https://localhost/health"}, "", 443),
    ({"STATUS_URL": "http://localhost:8084/health"}, "STATUS_URL ?= http://localhost:8083/\n", 8084),
])
def test_application_port_reads_environment_then_makefile(tmp_path, env, make_setting, expected):
    (tmp_path / "Makefile").write_text(make_setting)
    assert bootstrap.application_port(tmp_path, env) == expected


def test_business_http_port_cannot_be_used_for_agent(checkout):
    with pytest.raises(bootstrap.SetupError, match="business HTTP port"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"STATUS_URL": "http://localhost:7778/health"})
    assert checkout["calls"]["build"] == []
    assert not checkout["state"].exists()


@pytest.mark.parametrize("flags", ["--image chargebee-app", "--image cb-app", "--image=chargebee-app",
                                  "--image=cb-app", "--image cb-ems,chargebee-app", "--image=cb-app,cb-ems"])
def test_source_flags_reject_cb_app_images(flags):
    with pytest.raises(bootstrap.SetupError, match="native cb-app"):
        bootstrap.source_flags(flags)


@pytest.mark.parametrize("flags", ["", "--image cb-ems --verbose", "--source other --label 'value with spaces'",
                                  "--image=other,cb-ems"])
def test_source_flags_preserve_other_options_and_add_native_app(flags):
    assert shlex.split(bootstrap.source_flags(flags)) == shlex.split(flags) + ["--source", "chargebee-app"]


def test_image_flag_is_rejected_before_setup_mutations(checkout):
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="native cb-app"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"ORCH_FLAGS": "--image=cb-ems,chargebee-app"})
    assert checkout["hook"].read_bytes() == old
    assert not checkout["state"].exists()
    assert checkout["calls"]["build"] == checkout["calls"]["ownership"] == []


def test_foreign_business_listener_is_rejected_before_build_or_writes(checkout, monkeypatch):
    def reject(app, port):
        raise bootstrap.SetupError("foreign listener fixture")

    monkeypatch.setattr(bootstrap, "require_native_listener", reject)
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="foreign listener"):
        bootstrap.setup(checkout["app"], checkout["lens"], {})
    assert checkout["hook"].read_bytes() == old
    assert not checkout["state"].exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


def test_readable_agent_on_foreign_process_is_rejected_before_build(checkout, monkeypatch):
    def ownership(app, port):
        if port == 7778:
            raise bootstrap.SetupError("foreign agent fixture")

    monkeypatch.setattr(bootstrap, "require_native_listener", ownership)
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary("before"))
    old = checkout["hook"].read_bytes()
    with pytest.raises(bootstrap.SetupError, match="foreign agent"):
        bootstrap.setup(checkout["app"], checkout["lens"], {})
    assert checkout["hook"].read_bytes() == old
    assert not (checkout["state"] / "tracelens.json").exists()
    assert checkout["calls"]["build"] == checkout["calls"]["start"] == []


@pytest.fixture
def ownership_probes(monkeypatch):
    monkeypatch.setattr(bootstrap.shutil, "which", lambda command: "/fixture/lsof")
    monkeypatch.setattr(bootstrap, "port_in_use", lambda port: False)
    monkeypatch.setattr(bootstrap, "listener_pids", lambda port: set())
    calls = []

    def run(args, **kwargs):
        calls.append((args, kwargs))
        return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

    monkeypatch.setattr(bootstrap, "run", run)
    return calls


def test_native_listener_allows_empty_port_without_process_probe(ownership_probes):
    bootstrap.require_native_listener(Path("/work/chargebee-app"), 8080)
    assert ownership_probes == []


def test_native_listener_requires_lsof(ownership_probes, monkeypatch):
    monkeypatch.setattr(bootstrap.shutil, "which", lambda command: None)
    with pytest.raises(bootstrap.SetupError, match="lsof is required"):
        bootstrap.require_native_listener(Path("/work/chargebee-app"), 8080)
    assert ownership_probes == []


@pytest.mark.parametrize("pids,occupied", [(set(), True), ({"12", "13"}, True)])
def test_native_listener_rejects_unknown_or_multiple_owners(ownership_probes, monkeypatch, pids, occupied):
    monkeypatch.setattr(bootstrap, "listener_pids", lambda port: pids)
    monkeypatch.setattr(bootstrap, "port_in_use", lambda port: occupied)
    with pytest.raises(bootstrap.SetupError, match="different checkout/container/process"):
        bootstrap.require_native_listener(Path("/work/chargebee-app"), 8080)


@pytest.mark.parametrize("command,accepted", [
    ("/jdk/bin/java -Dcatalina.base=/work/chargebee-app/tomcat org.apache.catalina.startup.Bootstrap start", True),
    ("com.docker.backend -port 8080", False),
    ("/jdk/bin/java -Dcatalina.base=/other/chargebee-app/tomcat org.apache.catalina.startup.Bootstrap start", False),
    ("/jdk/bin/java -Dcatalina.base=/work/chargebee-app/tomcat SomeOtherMain", False),
    ("/jdk/bin/java -Dcatalina.base=/work/chargebee-app/tomcat-old org.apache.catalina.startup.Bootstrap start", False),
    ("/jdk/bin/java -Dnote=/work/chargebee-app/tomcat -Dcatalina.base=/other/tomcat org.apache.catalina.startup.Bootstrap start", False),
])
def test_native_listener_checks_exact_tomcat_checkout(ownership_probes, monkeypatch, command, accepted):
    monkeypatch.setattr(bootstrap, "listener_pids", lambda port: {"12"})

    def run(args, **kwargs):
        ownership_probes.append((args, kwargs))
        return subprocess.CompletedProcess(args, 0, stdout=command, stderr="")

    monkeypatch.setattr(bootstrap, "run", run)
    if accepted:
        bootstrap.require_native_listener(Path("/work/chargebee-app"), 8080)
    else:
        with pytest.raises(bootstrap.SetupError, match="different checkout/container/process"):
            bootstrap.require_native_listener(Path("/work/chargebee-app"), 8080)
    assert ownership_probes[0] == (["ps", "-p", "12", "-o", "args="], {"capture": True, "timeout": 5})


@pytest.mark.parametrize("app_pids,agent_pids,expected", [
    ({"12"}, {"12"}, True), ({"12"}, {"13"}, False), (set(), set(), False),
    ({"12", "13"}, {"12", "13"}, False), ({"12"}, set(), False),
])
def test_same_jvm_requires_one_identical_owner(monkeypatch, app_pids, agent_pids, expected):
    calls = []

    def pids(port):
        calls.append(port)
        return app_pids if port == 8080 else agent_pids

    monkeypatch.setattr(bootstrap, "listener_pids", pids)
    assert bootstrap.same_jvm(8080, 7778) is expected
    assert calls == [8080, 7778]


def test_resolved_checkout_paths_are_revalidated(checkout, tmp_path):
    renamed = tmp_path / "directory with spaces"
    checkout["lens"].rename(renamed)
    checkout["lens"].symlink_to(renamed, target_is_directory=True)
    with pytest.raises(bootstrap.SetupError, match="Resolved TRACELENS_HOME"):
        bootstrap.setup(checkout["app"], checkout["lens"], {"TRACELENS_PREPARE_ONLY": "1"})
    assert checkout["calls"]["build"] == []


def test_build_agent_uses_isolated_java21_environment(tmp_path, monkeypatch):
    lens = tmp_path / "lens"
    lens.mkdir()
    (lens / "build.gradle").write_text("version = 'test-version'\n")
    jar = bootstrap.agent_path(lens)
    jar.parent.mkdir(parents=True)
    calls = []

    def build(args, **kwargs):
        calls.append((args, kwargs))
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Premain-Class: com.chargebee.tracelens.agent.TraceLensAgent\n")
            archive.writestr("bridge/tracelens-bridge.jar.dat", b"fixture")

    monkeypatch.setattr(bootstrap, "java21_home", lambda env: "/jdk21")
    monkeypatch.setattr(bootstrap, "run", build)
    env = {"JAVA_HOME": "/jdk8", "JRE_HOME": "/jre8", "PATH": "/original/bin", "CUSTOM": "preserved"}
    assert bootstrap.build_agent(lens, env) == jar
    args, options = calls[0]
    assert args == [str(lens / "gradlew"), ":tracelens-agent:shadowJar", "--no-daemon", "--console=plain"]
    assert options["cwd"] == lens
    assert options["env"] == {"JAVA_HOME": "/jdk21", "PATH": "/jdk21/bin:/original/bin", "CUSTOM": "preserved"}
    assert env == {"JAVA_HOME": "/jdk8", "JRE_HOME": "/jre8", "PATH": "/original/bin", "CUSTOM": "preserved"}


@pytest.mark.parametrize("changes", [{"mode": "legacy"}, {"applicationId": "other"}, {"port": 7779},
                                    {"agentActive": False}, {"recordingEnabled": False}, {"packages": []},
                                    {"serviceInstanceId": "before"}, {"serviceInstanceId": ""},
                                    {"bridge": {}}, {"bridge": {"sinkRegistered": True, "httpListenerRegistered": False}}])
def test_readiness_requires_new_instance_and_expected_runtime(changes):
    assert not bootstrap.ready(ready_summary(**changes), 7778, ["com.example"], "before")


def test_fresh_instance_can_be_ready_before_application_classes_load():
    assert bootstrap.ready(ready_summary(instrumentedClasses=0), 7778, ["com.example"], "before")
    assert not bootstrap.ready(None, 7778, ["com.example"], None)


@pytest.mark.parametrize("reported", [["/different"], [], None, "/api/v2/features"])
def test_readiness_rejects_effective_capture_paths_that_do_not_match_json(reported):
    assert not bootstrap.ready(ready_summary(httpIncludePaths=reported), 7778, ["com.example"], "before",
                               http_include_paths=["/api/v2/features"])
    assert bootstrap.ready(ready_summary(), 7778, ["com.example"], "before",
                           http_include_paths=["/api/v2/features"])


@pytest.fixture
def lifecycle(monkeypatch):
    calls = []
    monkeypatch.setattr(bootstrap, "run", lambda args, **kwargs: calls.append((args, kwargs)))
    ticks = iter(range(100))
    monkeypatch.setattr(bootstrap.time, "monotonic", lambda: next(ticks))
    monkeypatch.setattr(bootstrap.time, "sleep", lambda seconds: None)
    monkeypatch.setattr(bootstrap, "same_jvm", lambda app_port, agent_port: True)
    return calls


def test_fresh_up_needs_no_restart_and_keeps_java8_environment(lifecycle, monkeypatch):
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary())
    env = {"JAVA_HOME": "/jdk8", "JRE_HOME": "/jre8", "TRACELENS_PORT": "7778", "CB_AWS_PROFILE": "custom",
           "ORCH_FLAGS": "--image cb-ems"}
    bootstrap.start_app(Path("/app"), env, 7778, {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 10)
    assert [args[4] for args, _ in lifecycle] == ["up", "health"]
    for args, options in lifecycle:
        assert "TRACELENS=1" in args and "TRACELENS_PORT=7778" in args
        assert "JRE_HOME=" in args
        assert "ORCH_FLAGS=--image cb-ems --source chargebee-app" in args
        assert options["env"]["JAVA_HOME"] == "/jdk8"
        assert "JRE_HOME" not in options["env"]
        assert options["env"]["AWS_PROFILE"] == "custom"
    assert 0 < lifecycle[-1][1]["timeout"] <= 10
    assert env["JRE_HOME"] == "/jre8"


def test_reused_up_falls_back_to_start_and_then_health(lifecycle, monkeypatch):
    replies = iter([ready_summary("before"), ready_summary("fresh")])
    monkeypatch.setattr(bootstrap, "summary", lambda port: next(replies))
    bootstrap.start_app(Path("/app"), {}, 7778, {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 10)
    assert [args[4] for args, _ in lifecycle] == ["up", "start", "health"]


def test_readiness_timeout_does_not_repeat_restart(lifecycle, monkeypatch):
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary("before"))
    with pytest.raises(bootstrap.SetupError, match="readiness failed"):
        bootstrap.start_app(Path("/app"), {}, 7778, {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 3)
    assert [args[4] for args, _ in lifecycle] == ["up", "start"]


@pytest.mark.parametrize("failed_target", ["up", "start", "health"])
def test_lifecycle_failures_are_not_reported_ready(lifecycle, monkeypatch, failed_target):
    def run(args, **kwargs):
        lifecycle.append((args, kwargs))
        if args[4] == failed_target:
            raise subprocess.CalledProcessError(1, args)

    monkeypatch.setattr(bootstrap, "run", run)
    replies = iter([ready_summary("before")] + [ready_summary()] * 20)
    monkeypatch.setattr(bootstrap, "summary", lambda port: next(replies))
    expected = bootstrap.SetupError if failed_target == "health" else subprocess.CalledProcessError
    with pytest.raises(expected):
        bootstrap.start_app(Path("/app"), {}, 7778, {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 3)
    targets = [args[4] for args, _ in lifecycle]
    assert targets.count("up") == 1 and targets.count("start") <= 1
    if failed_target == "up":
        assert targets == ["up"]
    else:
        assert targets[:2] == ["up", "start"]


def test_health_timeout_is_bounded_and_reported_as_readiness_failure(lifecycle, monkeypatch):
    def run(args, **kwargs):
        lifecycle.append((args, kwargs))
        if args[4] == "health":
            assert 0 < kwargs["timeout"] <= 10
            raise subprocess.TimeoutExpired(args, kwargs["timeout"])

    monkeypatch.setattr(bootstrap, "run", run)
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary())
    with pytest.raises(bootstrap.SetupError, match="readiness failed"):
        bootstrap.start_app(Path("/app"), {}, 7778, {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 3)
    assert [args[4] for args, _ in lifecycle] == ["up", "health"]


def test_fresh_agent_on_different_jvm_is_not_ready(lifecycle, monkeypatch):
    probes = []

    def different_jvm(app_port, agent_port):
        probes.append((app_port, agent_port))
        return False

    monkeypatch.setattr(bootstrap, "same_jvm", different_jvm)
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary())
    with pytest.raises(bootstrap.SetupError, match="readiness failed"):
        bootstrap.start_app(Path("/app"), {"STATUS_URL": "http://localhost:8086/health"}, 7778,
                            {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 3)
    assert probes and all(pair == (8086, 7778) for pair in probes)
    assert [args[4] for args, _ in lifecycle] == ["up"]


def test_start_app_does_not_report_readiness_for_effective_paths_different_from_json(lifecycle, monkeypatch):
    monkeypatch.setattr(bootstrap, "summary", lambda port: ready_summary(httpIncludePaths=["/unexpected"]))
    with pytest.raises(bootstrap.SetupError, match="readiness failed"):
        bootstrap.start_app(Path("/app"), {}, 7778,
                            {"packages": ["com.example"], "httpIncludePaths": ["/api/v2/features"]}, "before", 3)
    assert [args[4] for args, _ in lifecycle] == ["up", "start"]


@pytest.fixture
def shell_helper_environment(tmp_path):
    jar, config = tmp_path / "agent.jar", tmp_path / "config.json"
    jar.touch()
    config.write_text(json.dumps({"httpIncludePaths": ["/subscriptions"]}))
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("TRACELENS") and key not in bootstrap.JAVA_OPTIONS}
    env.update(TRACELENS="1", TRACELENS_AGENT=str(jar), TRACELENS_CONFIG=str(config),
               TRACELENS_PORT="7778", CATALINA_OPTS="-Xmx1g", HELPER=str(ROOT / "scripts/cb-app-setenv.sh"))
    return env


def test_shell_helper_refuses_duplicate_source_without_altering_existing_options(shell_helper_environment):
    result = subprocess.run(["sh", "-c", '''set -eu
. "$HELPER"
FIRST_OPTS="$CATALINA_OPTS"
if . "$HELPER"; then
    echo duplicate-source-was-accepted >&2
    exit 9
fi
[ "$FIRST_OPTS" = "$CATALINA_OPTS" ]
printf '%s\n' "$CATALINA_OPTS"
'''], env=shell_helper_environment, capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    assert "already attached" in result.stderr
    assert result.stdout.startswith("-Xmx1g -javaagent:")
    assert result.stdout.count("-javaagent:") == 1
    assert "tracelens.http.includePaths" not in result.stdout
    assert "TRACELENS_HTTP_PATHS" not in result.stdout
    assert "-Dtracelens.configPath=" in result.stdout


@pytest.mark.parametrize("key,value", [
    ("TRACELENS_HTTP_PATHS", ""), ("TRACELENS_HTTP_PATHS", "/subscriptions"),
    *[(key, "-Dtracelens.http.includePaths=/subscriptions") for key in bootstrap.JAVA_OPTIONS],
])
def test_shell_helper_rejects_obsolete_path_overrides_without_mutating_jvm_options(shell_helper_environment, key, value):
    env = {**shell_helper_environment, key: value}
    result = subprocess.run(["sh", "-c", '''set -eu
BEFORE_OPTS="${CATALINA_OPTS:-}"
if . "$HELPER"; then
    echo obsolete-path-override-was-accepted >&2
    exit 9
fi
[ "$BEFORE_OPTS" = "${CATALINA_OPTS:-}" ]
'''], env=env, capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    assert "httpIncludePaths" in result.stderr
    assert "obsolete-path-override-was-accepted" not in result.stderr


@pytest.fixture
def make_fixture(tmp_path):
    if shutil.which("make") is None:
        pytest.skip("make is not installed")
    app, lens, binaries = tmp_path / "chargebee-app", tmp_path / "cb-tracelens", tmp_path / "bin"
    app.mkdir()
    binaries.mkdir()
    log = tmp_path / "calls.jsonl"
    mock = (
        f"#!{sys.executable}\n"
        "import json, os, pathlib, sys\n"
        "if pathlib.Path(sys.argv[0]).name == 'mock-python' and sys.argv[1:2] == ['-c']:\n"
        "    sys.exit(int(os.environ.get('MOCK_PROBE_FAIL', '0')))\n"
        "with open(os.environ['MOCK_CALLS'], 'a') as stream:\n"
        "    stream.write(json.dumps([pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]) + '\\n')\n"
        "if pathlib.Path(sys.argv[0]).name == 'git':\n"
        "    assert sys.argv[1:3] == ['clone', '--'], sys.argv\n"
        "    if os.environ.get('MOCK_GIT_FAIL'):\n"
        "        sys.exit(2)\n"
        "    target = pathlib.Path(sys.argv[-1])\n"
        "    (target / 'scripts').mkdir(parents=True)\n"
        "    (target / 'scripts/bootstrap_cb_app.py').write_text('# fixture only\\n')\n"
    )
    for name in ("git", "mock-python"):
        path = binaries / name
        path.write_text(mock)
        path.chmod(0o700)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("TRACELENS") and key not in {"MAKEFLAGS", "MFLAGS", "MAKELEVEL", "MAKEOVERRIDES"}}
    env.update(PATH=str(binaries) + os.pathsep + env.get("PATH", ""), MOCK_CALLS=str(log),
               TRACELENS_HOME=str(lens), TRACELENS_PYTHON=str(binaries / "mock-python"),
               TRACELENS_REPO="fixture://no-network")

    def invoke(*args, extra_env=None):
        return subprocess.run(["make", "--no-print-directory", "-f", str(FRAGMENT), *args, "up-tracelens"],
                              cwd=app, env={**env, **(extra_env or {})}, capture_output=True, text=True, timeout=10)

    return invoke, log, app, lens


def test_make_clones_missing_checkout_only_once(make_fixture):
    invoke, log, app, lens = make_fixture
    for _ in range(2):
        result = invoke()
        assert result.returncode == 0, result.stderr
    calls = [json.loads(line) for line in log.read_text().splitlines()]
    assert [call[0] for call in calls] == ["git", "mock-python", "mock-python"]
    assert calls[0] == ["git", "clone", "--", "fixture://no-network", str(lens)]
    assert calls[1][1:] == [str(lens / "scripts/bootstrap_cb_app.py"), "--app-root", str(app)]


def test_make_dry_run_does_not_clone_or_execute_python(make_fixture):
    invoke, log, _, lens = make_fixture
    result = invoke("-n")
    assert result.returncode == 0, result.stderr
    assert "git clone" in result.stdout
    assert not log.exists() and not lens.exists()


def test_make_rejects_existing_incomplete_checkout_without_replacing_it(make_fixture):
    invoke, log, _, lens = make_fixture
    lens.mkdir()
    sentinel = lens / "local-work"
    sentinel.write_text("preserve")
    result = invoke()
    assert result.returncode != 0
    assert "incomplete or too old" in result.stderr
    assert sentinel.read_text() == "preserve" and not log.exists()


def test_make_clone_failure_does_not_run_bootstrap(make_fixture):
    invoke, log, _, lens = make_fixture
    result = invoke(extra_env={"MOCK_GIT_FAIL": "1"})
    assert result.returncode != 0
    assert "Cannot clone TraceLens" in result.stderr
    calls = [json.loads(line) for line in log.read_text().splitlines()]
    assert [call[0] for call in calls] == ["git"]
    assert not lens.exists()


def test_make_rejects_unsupported_explicit_python_before_clone(make_fixture):
    invoke, log, _, lens = make_fixture
    result = invoke(extra_env={"MOCK_PROBE_FAIL": "1"})
    assert result.returncode != 0
    assert "Python 3.10+ is required" in result.stderr
    assert not log.exists() and not lens.exists()
