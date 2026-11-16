#!/usr/bin/env python3
"""Developer-only setup for `make up-tracelens`; no third-party Python packages."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import zipfile


BEGIN = "# BEGIN TRACELENS MANAGED SETUP"
END = "# END TRACELENS MANAGED SETUP"
SAFE_PATH = re.compile(r"/[a-zA-Z0-9_./:@-]+\Z")
SAFE_PATHS = re.compile(r"/[a-zA-Z0-9_./,:@-]*\Z")
LEGACY_HTTP_PROPERTY = "-Dtracelens.http.includePaths"
JAVA_OPTIONS = ("CATALINA_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")


class SetupError(Exception):
    pass


def run(args, *, cwd=None, env=None, capture=False, timeout=None):
    return subprocess.run(args, cwd=cwd, env=env, check=True, text=True,
                          timeout=timeout,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None)


def safe_path(value, label):
    if not SAFE_PATH.fullmatch(str(value)):
        raise SetupError(f"{label} must be absolute and contain no spaces or shell metacharacters.")
    return Path(value)


def plain_file(path):
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise SetupError(f"Refusing non-regular file: {path}")


def read_object(path):
    plain_file(path)
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise SetupError(f"Cannot read JSON object: {path}") from exc
    if not isinstance(value, dict):
        raise SetupError(f"Expected a JSON object: {path}")
    return value


def atomic_write(path, text, mode=0o600):
    plain_file(path)
    if path.exists() and path.read_text() == text:
        return
    if path.exists():
        mode = path.stat().st_mode & 0o777
    fd, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(text)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def json_text(value):
    return json.dumps(value, indent=2) + "\n"


def java21_home(env):
    explicit = env.get("TRACELENS_BUILD_JAVA_HOME")
    candidates = [explicit] if explicit else [env.get("JAVA_HOME")]
    if not explicit:
        if Path("/usr/libexec/java_home").is_file():
            probe = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True)
            if probe.returncode == 0:
                candidates.append(probe.stdout.strip())
        javac = shutil.which("javac")
        if javac:
            candidates.append(str(Path(javac).resolve().parent.parent))
    for candidate in candidates:
        if not candidate or not (Path(candidate) / "bin/javac").is_file():
            continue
        try:
            probe = run([str(Path(candidate) / "bin/java"), "-version"], capture=True)
            if re.search(r'version "21(?:[.\"]|-)', probe.stdout + probe.stderr):
                return str(Path(candidate).resolve())
        except (OSError, subprocess.CalledProcessError):
            pass
    raise SetupError("JDK 21 is needed to build TraceLens. Install it and set TRACELENS_BUILD_JAVA_HOME; cb-app still uses Java 8.")


def agent_path(lens):
    match = re.search(r"^\s*version\s*=\s*['\"]([A-Za-z0-9_.-]+)['\"]", (lens / "build.gradle").read_text(), re.M)
    if not match:
        raise SetupError("Cannot resolve TraceLens artifact version from build.gradle.")
    return lens / f"tracelens-agent/build/libs/tracelens-agent-{match[1]}-agent.jar"


def build_agent(lens, env):
    build_env = dict(env)
    build_env["JAVA_HOME"] = java21_home(env)
    build_env["PATH"] = build_env["JAVA_HOME"] + "/bin:" + env.get("PATH", "")
    build_env.pop("JRE_HOME", None)
    print("Building the shaded TraceLens agent with JDK 21...", flush=True)
    run([str(lens / "gradlew"), ":tracelens-agent:shadowJar", "--no-daemon", "--console=plain"], cwd=lens, env=build_env)
    jar = agent_path(lens)
    try:
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode()
            if "Premain-Class: com.chargebee.tracelens.agent.TraceLensAgent" not in manifest:
                raise SetupError("Built artifact has no TraceLens agent manifest.")
            if "bridge/tracelens-bridge.jar.dat" not in archive.namelist():
                raise SetupError("Built artifact is missing its bootstrap bridge.")
    except (OSError, KeyError, zipfile.BadZipFile) as exc:
        raise SetupError(f"Built shaded agent is missing or invalid: {jar}") from exc
    return jar


def validate_http_paths(paths):
    if (not isinstance(paths, list) or len(paths) > 64
            or any(not isinstance(path, str) or not path.startswith("/") or len(path) > 512
                   or any(ord(char) < 33 or ord(char) > 126 or char in "?#*,\\[]{}" for char in path)
                   for path in paths)):
        raise SetupError("httpIncludePaths must be a JSON array of literal absolute path prefixes (no whitespace, query, fragment or wildcard); [] captures no HTTP requests.")


def legacy_http_paths(old):
    """Read only our former generated literal; never evaluate user shell code."""
    if BEGIN in old or END in old:
        if old.count(BEGIN) != 1 or old.count(END) != 1 or old.index(END) < old.index(BEGIN):
            raise SetupError("Ambiguous managed TraceLens block in setenv.sh; resolve it manually.")
        start, end = old.index(BEGIN), old.index(END) + len(END)
        managed, outside = old[start:end], old[:start] + old[end:]
    else:
        managed, outside = "", old
    active = lambda value: [line for line in value.splitlines() if not line.lstrip().startswith("#")]
    if any(LEGACY_HTTP_PROPERTY in line for line in active(old)):
        raise SetupError("Remove obsolete -Dtracelens.http.includePaths from setenv.sh and put the capture paths in JSON httpIncludePaths.")
    if any("TRACELENS_HTTP_PATHS" in line for line in active(outside)):
        raise SetupError("Manual TRACELENS_HTTP_PATHS in setenv.sh is ambiguous; move its paths to JSON httpIncludePaths and remove the shell setting.")
    lines = [line.strip() for line in active(managed) if "TRACELENS_HTTP_PATHS" in line]
    if not lines:
        return None
    match = re.fullmatch(r'TRACELENS_HTTP_PATHS="\$\{TRACELENS_HTTP_PATHS:-([^"{}$]+)\}"', lines[0])
    if len(lines) != 1 or not match or not SAFE_PATHS.fullmatch(match[1]):
        raise SetupError("Cannot safely migrate the managed TRACELENS_HTTP_PATHS setting; put its paths in JSON httpIncludePaths and remove the obsolete setting.")
    paths = match[1].split(",")
    validate_http_paths(paths)
    return paths


def configured_json(template, destination, legacy_paths=None):
    # Once created, the developer owns all capture/scope/identity settings.
    plain_file(destination)
    config = read_object(destination if destination.exists() else template)
    if config.get("applicationId") != "cb-app":
        raise SetupError("cb-app config must use applicationId 'cb-app'. Existing JSON was not overwritten.")
    packages = config.get("packages")
    if not isinstance(packages, list) or not packages or not all(isinstance(p, str) and p for p in packages):
        raise SetupError("Configure a nonempty packages list for line capture in the cb-app JSON.")
    if not destination.exists() and legacy_paths is not None:
        # The template is only a starting point; preserve a known deployed scope.
        config["httpIncludePaths"] = legacy_paths
    if "httpIncludePaths" not in config:
        if legacy_paths is None:
            raise SetupError(f"Add an explicit httpIncludePaths array to {destination if destination.exists() else template}; capture scope will not be guessed.")
        config["httpIncludePaths"] = legacy_paths
    validate_http_paths(config["httpIncludePaths"])
    normalized = lambda paths: {path.rstrip("/") or "/" for path in paths}
    if legacy_paths is not None and normalized(config["httpIncludePaths"]) != normalized(legacy_paths):
        raise SetupError("Conflicting httpIncludePaths JSON and managed TRACELENS_HTTP_PATHS. Choose the desired paths in JSON and remove the obsolete shell setting before setup.")
    return config


def registry_json(path, app, port):
    plain_file(path)
    registry = read_object(path) if path.exists() else {"services": []}
    services = registry.get("services")
    if not isinstance(services, list) or not all(isinstance(s, dict) for s in services):
        raise SetupError("Expected services array in local TraceLens registry.")
    matches = [s for s in services if s.get("name") == "cb-app"]
    if len(matches) > 1:
        raise SetupError("Duplicate cb-app entries in local TraceLens registry.")
    entry = matches[0] if matches else {"name": "cb-app"}
    entry.update(url=f"http://127.0.0.1:{port}", sourceRoot=str(app))
    if not matches:
        services.append(entry)
    return registry


def wired_setenv(old, settings):
    if "TRACELENS_HTTP_PATHS" in settings:
        raise SetupError("Capture paths belong only in JSON httpIncludePaths, not managed launcher settings.")
    block = BEGIN + '\nif [ "${TRACELENS:-0}" = "1" ]; then\n'
    for key, value in settings.items():
        block += f'    {key}="${{{key}:-{value}}}"\n'
    block += '    . "$TRACELENS_HOME/scripts/cb-app-setenv.sh" || exit 1\nfi\n' + END + "\n"
    if BEGIN in old or END in old:
        if old.count(BEGIN) != 1 or old.count(END) != 1:
            raise SetupError("Ambiguous managed TraceLens block in setenv.sh; resolve it manually.")
        start, end = old.index(BEGIN), old.index(END) + len(END)
        if end < start:
            raise SetupError("Malformed managed TraceLens block in setenv.sh.")
        if old[end:end + 1] == "\n":
            end += 1
        before, after = old[:start], old[end:]
        if any("cb-app-setenv.sh" in line and not line.lstrip().startswith("#") for line in (before + after).splitlines()):
            raise SetupError("Multiple TraceLens startup hooks; keep only one.")
    else:
        lines = old.splitlines(keepends=True)
        hooks = []
        for i, line in enumerate(lines):
            if "cb-app-setenv.sh" not in line or line.lstrip().startswith("#"):
                continue
            words = shlex.split(line, comments=True)
            if (len(words) not in (2, 5) or words[0] != "." or not words[1].endswith("/cb-app-setenv.sh")
                    or (len(words) == 5 and words[2:] != ["||", "exit", "1"])):
                raise SetupError("Unrecognized TraceLens hook in setenv.sh; resolve it manually.")
            hooks.append(i)
        if len(hooks) > 1:
            raise SetupError("Multiple TraceLens startup hooks; keep only one.")
        if hooks:
            before, after = "".join(lines[:hooks[0]]), "".join(lines[hooks[0] + 1:])
        else:
            before, after = old + ("\n" if old and not old.endswith("\n") else ""), ""
    if any("-javaagent:" in line and "tracelens" in line.lower() and not line.lstrip().startswith("#")
           for line in (before + after).splitlines()):
        raise SetupError("Manual TraceLens -javaagent options found in setenv.sh; choose one attachment mechanism.")
    return before + block + after


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def summary(port):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(f"http://127.0.0.1:{port}/tracelens/summary", timeout=2) as response:
            raw = response.read(65537)
        if len(raw) > 65536:
            raise ValueError("oversized response")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("not an object")
        return value
    except (OSError, ValueError, urllib.error.URLError):
        return None


def port_in_use(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def application_port(app, env):
    url = env.get("STATUS_URL")
    if not url:
        makefile = app / "Makefile"
        match = re.search(r"^STATUS_URL\s*\??=\s*(\S+)", makefile.read_text(), re.M) if makefile.is_file() else None
        url = match[1] if match else "http://127.0.0.1:8080"
    parsed = urlsplit(url)
    return parsed.port or (443 if parsed.scheme == "https" else 80)


def source_flags(flags):
    words = shlex.split(flags)
    for index, word in enumerate(words):
        image = word.split("=", 1)[1] if word.startswith("--image=") else (
            words[index + 1] if word == "--image" and index + 1 < len(words) else "")
        if "chargebee-app" in image.split(",") or "cb-app" in image.split(","):
            raise SetupError("up-tracelens attaches to native cb-app, not its Docker image; remove the --image flag for cb-app.")
    return shlex.join(words + ["--source", "chargebee-app"])


def listener_pids(port):
    try:
        result = run(["lsof", "-nP", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"], capture=True, timeout=5)
        return {line for line in result.stdout.splitlines() if line.isdigit()}
    except subprocess.CalledProcessError as exc:
        if exc.returncode == 1:
            return set()
        raise


def require_native_listener(app, port):
    if not shutil.which("lsof"):
        raise SetupError("lsof is required to verify the app and TraceLens share the same JVM.")
    pids = listener_pids(port)
    if not pids and not port_in_use(port):
        return
    if len(pids) == 1:
        command = run(["ps", "-p", next(iter(pids)), "-o", "args="], capture=True, timeout=5).stdout
        words = shlex.split(command)
        bases = [word.split("=", 1)[1] for word in words if word.startswith("-Dcatalina.base=")]
        if (len(bases) == 1 and Path(bases[0]).is_absolute()
                and Path(bases[0]).resolve() == (app / "tomcat").resolve()
                and "org.apache.catalina.startup.Bootstrap" in words):
            return
    raise SetupError(f"Port {port} is owned by a different checkout/container/process. Stop it explicitly before native TraceLens setup; it was not stopped.")


def same_jvm(app_port, agent_port):
    app_pids, agent_pids = listener_pids(app_port), listener_pids(agent_port)
    return len(app_pids) == 1 and app_pids == agent_pids


def ready(value, port, packages, previous_id, http_include_paths=None):
    return bool(value and value.get("mode") == "standalone" and value.get("applicationId") == "cb-app"
                and value.get("port") == port and value.get("agentActive") is True
                and value.get("recordingEnabled") is True and value.get("packages") == packages
                and (http_include_paths is None or value.get("httpIncludePaths") == http_include_paths)
                and value.get("serviceInstanceId") and value["serviceInstanceId"] != previous_id
                and isinstance(value.get("bridge"), dict)
                and value["bridge"].get("sinkRegistered") is True
                and value["bridge"].get("httpListenerRegistered") is True)


def start_app(app, env, port, config, previous_id, timeout):
    child_env = dict(env)
    child_env.pop("JRE_HOME", None)  # The orchestrator chooses cb-app's declared Java 8.
    child_env.setdefault("AWS_PROFILE", child_env.get("CB_AWS_PROFILE", "cb-auth"))
    make = env.get("TRACELENS_MAKE", "make")
    command = [make, "--no-print-directory", "-C", str(app)]
    business_port = application_port(app, env)
    overrides = [f"{key}={value}" for key, value in child_env.items() if key.startswith("TRACELENS_")]
    # An outer `make ... JRE_HOME=...` survives env deletion via MAKEFLAGS.
    overrides.extend(["TRACELENS=1", "JRE_HOME="])
    overrides.append(f"ORCH_FLAGS={source_flags(env.get('ORCH_FLAGS', ''))}")
    print("Running the normal cb-app make up lifecycle (an unready app may require its normal build/fixtures).", flush=True)
    run(command + ["up"] + overrides, env=child_env)
    if not ready(summary(port), port, config["packages"], previous_id, config["httpIncludePaths"]):
        print("Reloading cb-app through make start (no full build). This clears Tomcat logs and enables jobs.", flush=True)
        run(command + ["start"] + overrides, env=child_env)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if ready(summary(port), port, config["packages"], previous_id, config["httpIncludePaths"]) and same_jvm(business_port, port):
            try:
                run(command + ["health"] + overrides, env=child_env, capture=True,
                    timeout=max(0.1, min(10, deadline - time.monotonic())))
                print(f"cb-app and TraceLens are ready: http://127.0.0.1:{port}/tracelens/summary", flush=True)
                return
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
        time.sleep(2)
    raise SetupError("cb-app/TraceLens readiness failed. Inspect Tomcat logs and the debug port; no automatic rebuild or DB reset was attempted after restart.")


def setup(app, lens, env):
    app = safe_path(safe_path(app, "App root").resolve(), "Resolved app root")
    lens = safe_path(safe_path(lens, "TRACELENS_HOME").resolve(), "Resolved TRACELENS_HOME")
    if not (app / "Makefile").is_file() or not (app / "tomcat/bin").is_dir():
        raise SetupError("Run from a chargebee-app checkout with Makefile and tomcat/bin.")
    if not (lens / ".git").exists() or not (lens / "gradlew").is_file():
        raise SetupError("TRACELENS_HOME must be an existing cb-tracelens Git checkout.")
    if "TRACELENS_HTTP_PATHS" in env:
        raise SetupError("TRACELENS_HTTP_PATHS is no longer supported. Edit httpIncludePaths in the selected service JSON; unset the environment/make variable.")
    for key in JAVA_OPTIONS:
        if LEGACY_HTTP_PROPERTY in env.get(key, ""):
            raise SetupError(f"Remove obsolete -Dtracelens.http.includePaths from {key}; configure httpIncludePaths in JSON instead.")
        if re.search(r"-javaagent:\S*tracelens", env.get(key, ""), re.I):
            raise SetupError(f"Existing TraceLens attachment in {key}; remove it before using managed setup.")
    try:
        port = int(env.get("TRACELENS_PORT", "7778"))
        timeout = int(env.get("TRACELENS_START_TIMEOUT", "180"))
        if not 1 <= port <= 65535 or not 1 <= timeout <= 1800:
            raise ValueError()
    except ValueError as exc:
        raise SetupError("TRACELENS_PORT must be 1..65535 and TRACELENS_START_TIMEOUT 1..1800 seconds.") from exc
    if port == application_port(app, env):
        raise SetupError("TRACELENS_PORT must differ from cb-app's business HTTP port.")
    source_flags(env.get("ORCH_FLAGS", ""))  # Validate before building or changing files.
    if env.get("TRACELENS_PREPARE_ONLY") != "1":
        require_native_listener(app, application_port(app, env))
    for directory in (app / ".run", app / ".run/tracelens"):
        if directory.is_symlink():
            raise SetupError(f"Refusing symlinked setup directory: {directory}")
        directory.mkdir(exist_ok=True)
    state = app / ".run/tracelens"
    lock_path = state / "setup.lock"
    plain_file(lock_path)
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise SetupError("Another TraceLens setup is running for this app.") from exc
        config_path = safe_path(env.get("TRACELENS_CONFIG", str(state / "tracelens.json")), "TRACELENS_CONFIG")
        if not config_path.parent.is_dir():
            raise SetupError("TRACELENS_CONFIG parent directory must already exist.")
        source_config_path = config_path
        source_config_bytes = None
        if "TRACELENS_CONFIG" not in env:
            legacy_config_path = state / "cb-app.json"
            plain_file(config_path)
            plain_file(legacy_config_path)
            if legacy_config_path.exists():
                if config_path.exists():
                    raise SetupError("Both cb-app.json and tracelens.json exist in the setup directory; choose one explicitly with TRACELENS_CONFIG or resolve the conflict before setup.")
                source_config_path = legacy_config_path
                source_config_bytes = legacy_config_path.read_bytes()
        hook = app / "tomcat/bin/setenv.sh"
        plain_file(hook)
        old = hook.read_text() if hook.exists() else ""
        config = configured_json(lens / "scripts/cb-app/tracelens.json", source_config_path, legacy_http_paths(old))
        registry_path = state / "services.json"
        registry = registry_json(registry_path, app, port)
        jar = agent_path(lens)
        settings = {"TRACELENS_HOME": str(lens), "TRACELENS_AGENT": str(jar),
                    "TRACELENS_CONFIG": str(config_path), "TRACELENS_PORT": str(port)}
        updated = wired_setenv(old, settings)
        subprocess.run(["sh", "-n"], input=updated, text=True, check=True)
        before = summary(port) if env.get("TRACELENS_PREPARE_ONLY") != "1" else None
        if env.get("TRACELENS_PREPARE_ONLY") != "1" and not before and port_in_use(port):
            raise SetupError(f"Port {port} is occupied but has no readable TraceLens summary; choose another port.")
        if before and (before.get("applicationId") != "cb-app" or before.get("mode") != "standalone"
                       or not before.get("serviceInstanceId")):
            raise SetupError(f"Port {port} is serving something other than cb-app standalone TraceLens; choose another port.")
        if before:
            require_native_listener(app, port)
        build_agent(lens, env)
        if source_config_path != config_path:
            plain_file(source_config_path)
            plain_file(config_path)
            if config_path.exists() or source_config_path.read_bytes() != source_config_bytes:
                raise SetupError("The TraceLens config changed during the build; no legacy config was renamed. Resolve the files and retry setup.")
            source_config_path.rename(config_path)
            print(f"Renamed legacy config {source_config_path} to {config_path}; existing contents were preserved.", flush=True)
        if not config_path.exists():
            atomic_write(config_path, json_text(config))
        elif read_object(config_path) != config:
            backup = state / f"cb-app-config-{time.time_ns()}.json.bak"
            atomic_write(backup, config_path.read_text())
            atomic_write(config_path, json_text(config))
            print(f"Migrated HTTP capture paths into {config_path}; previous JSON preserved at {backup}", flush=True)
        atomic_write(registry_path, json_text(registry))
        if old != updated and hook.exists():
            backup = state / f"setenv-{time.time_ns()}.sh.bak"
            atomic_write(backup, old)
            print(f"Preserved previous setenv.sh at {backup}", flush=True)
        atomic_write(hook, updated, mode=0o644)
        print(f"TraceLens config: {config_path}\nViewer/MCP registry: {registry_path}", flush=True)
        if env.get("TRACELENS_PREPARE_ONLY") == "1":
            print("Preparation complete; no application lifecycle commands were run.", flush=True)
            return
        child_env = dict(env, **settings, TRACELENS="1")
        start_app(app, child_env, port, config, before.get("serviceInstanceId") if before else None, timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-root", required=True)
    args = parser.parse_args()
    try:
        if sys.version_info < (3, 10):
            raise SetupError("Python 3.10+ is required.")
        setup(args.app_root, Path(__file__).resolve().parents[1], os.environ)
    except (SetupError, OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"TraceLens setup failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
