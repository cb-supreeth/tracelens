#!/bin/sh
# Launch the local configuration UI using the same JAR shipped in this bundle.
# Rerun and select an existing guided installation to add another supported service.
set -eu
TRACELENS_BUNDLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TRACELENS_JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then
    TRACELENS_JAVA="$JAVA_HOME/bin/java"
fi
if ! command -v "$TRACELENS_JAVA" >/dev/null 2>&1; then
    echo 'Java was not found. Install a JDK (8 or later), or set JAVA_HOME to your existing JDK.' >&2
    exit 1
fi
if [ ! -f "$TRACELENS_BUNDLE_DIR/tracelens.jar" ]; then
    echo 'Keep setup.sh next to tracelens.jar after extracting the complete setup ZIP.' >&2
    exit 1
fi
exec "$TRACELENS_JAVA" -jar "$TRACELENS_BUNDLE_DIR/tracelens.jar" setup "$@"
