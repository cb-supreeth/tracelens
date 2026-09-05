#!/bin/sh
# Dev helper: boot the locally built cb-ems jar with the tracelens agent attached,
# fully detached (new session via python setsid) so the process survives the
# launching shell. Mirrors the env of cb-ems's Makefile `start` target.
# Usage: run-cb-ems-with-agent.sh [agent.jar] [packages]
set -eu

CB_EMS_DIR="${CB_EMS_DIR:-$HOME/work/cb-ems}"
AGENT_JAR="${1:-$HOME/work/cb-tracelens/tracelens-agent/build/libs/tracelens-agent-0.1.0-SNAPSHOT-agent.jar}"
CONFIG="${2:-$HOME/work/cb-tracelens/scripts/cb-ems-tracelens.json}"
# NB: explicit Corretto 21 — the shell's JAVA_HOME may point at JDK 8 (stale
# orchestrator default), and a Java 8 JVM cannot load Java 21 agent classes.
JAVA_BIN="/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home/bin/java"
JAR="$(ls "$CB_EMS_DIR"/build/libs/cb-ems-app-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"
LOG="$CB_EMS_DIR/.run/cb-ems-tracelens-spike.out"

[ -n "$JAR" ] || { echo "no cb-ems jar in $CB_EMS_DIR/build/libs — run 'make build' first"; exit 1; }
mkdir -p "$CB_EMS_DIR/.run"

RDS_HOSTNAME=localhost RDS_PORT=5432 RDS_DB_NAME=chargebee_ems \
RDS_USERNAME=cb_ems RDS_PASSWORD=dbpassword \
AWS_PROFILE=cb-ems AWS_REGION=us-east-1 \
python3 - "$JAVA_BIN" "$AGENT_JAR" "$CONFIG" "$JAR" "$LOG" <<'EOF'
import subprocess, sys

java_bin, agent_jar, config, jar, log = sys.argv[1:6]
cmd = [
    java_bin,
    "-Daws.profile.name=cb-ems",
    "-Dserver.port=8082",
    f"-javaagent:{agent_jar}",
    f"-Dtracelens.configPath={config}",
    "-jar", jar,
]
with open(log, "wb") as out:
    proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=out,
                            start_new_session=True)
print(f"cb-ems started (pid {proc.pid}), log: {log}")
EOF
