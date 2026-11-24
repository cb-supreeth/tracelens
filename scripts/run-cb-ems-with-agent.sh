#!/bin/sh
# Dev helper: boot the locally built cb-ems jar with the tracelens agent attached,
# detached with nohup so the process survives the launching shell. Mirrors the env of cb-ems's Makefile `start` target.
# Usage: run-cb-ems-with-agent.sh [agent.jar] [config.json]
set -eu

CB_EMS_DIR="${CB_EMS_DIR:-$HOME/work/cb-ems}"
AGENT_JAR="${1:-$HOME/work/cb-tracelens/tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar}"
CONFIG="${2:-$HOME/work/cb-tracelens/scripts/cb-ems/tracelens.json}"
# The current EMS application requires Java 21; the agent itself also supports Java 8.
JAVA_BIN="/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home/bin/java"
JAR="$(ls "$CB_EMS_DIR"/build/libs/cb-ems-app-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"
LOG="$CB_EMS_DIR/.run/cb-ems-tracelens-spike.out"

[ -n "$JAR" ] || { echo "no cb-ems jar in $CB_EMS_DIR/build/libs — run 'make build' first"; exit 1; }
mkdir -p "$CB_EMS_DIR/.run"

RDS_HOSTNAME=localhost RDS_PORT=5432 RDS_DB_NAME=chargebee_ems \
RDS_USERNAME=cb_ems RDS_PASSWORD=dbpassword \
AWS_PROFILE=cb-ems AWS_REGION=us-east-1 \
nohup "$JAVA_BIN" \
  -Daws.profile.name=cb-ems \
  -Dserver.port=8082 \
  "-javaagent:$AGENT_JAR=standalone" \
  "-Dtracelens.configPath=$CONFIG" \
  -Dtracelens.port=7779 \
  -Dtracelens.enabled=false \
  -jar "$JAR" </dev/null >"$LOG" 2>&1 &
echo "cb-ems started (pid $!), log: $LOG"
