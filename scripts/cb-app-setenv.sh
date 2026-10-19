# Source from chargebee-app/tomcat/bin/setenv.sh. No effect unless TRACELENS=1.
# JAVA_OPTS is overwritten by cb-app's catalina.sh; CATALINA_OPTS is preserved.
if [ "${TRACELENS:-0}" = "1" ]; then
    TRACELENS_HOME="${TRACELENS_HOME:-$HOME/work/cb-tracelens}"
    TRACELENS_VERSION="${TRACELENS_VERSION:-0.1.1-SNAPSHOT}"
    TRACELENS_AGENT="$TRACELENS_HOME/tracelens-agent/build/libs/tracelens-agent-$TRACELENS_VERSION-agent.jar"
    TRACELENS_CONFIG="${TRACELENS_CONFIG:-$TRACELENS_HOME/scripts/cb-app-tracelens.json}"
    TRACELENS_HTTP_PATHS="${TRACELENS_HTTP_PATHS:-/api/v2/features}"
    TRACELENS_PORT="${TRACELENS_PORT:-7778}"
    case "$TRACELENS_PORT" in
        *[!0-9]*|"")
            echo "TraceLens port must be a number from 0 to 65535." >&2
            return 1 ;;
    esac
    if [ "$TRACELENS_PORT" -gt 65535 ]; then
        echo "TraceLens port must be a number from 0 to 65535." >&2
        return 1
    fi
    if [ ! -r "$TRACELENS_AGENT" ] || [ ! -r "$TRACELENS_CONFIG" ]; then
        echo "TraceLens agent/config missing. Build :tracelens-agent:shadowJar first." >&2
        return 1
    fi
    # Tomcat evaluates this options string. Restrict paths to avoid shell expansion
    # surprises; the standard ~/work checkout paths meet this requirement.
    case "$TRACELENS_AGENT$TRACELENS_CONFIG$TRACELENS_HTTP_PATHS" in
        *[!a-zA-Z0-9_./,:@-]*)
            echo "TraceLens paths must use letters, digits, _, ., /, comma, colon, @ or -." >&2
            return 1 ;;
    esac
    CATALINA_OPTS="${CATALINA_OPTS:-} -javaagent:$TRACELENS_AGENT=standalone -Dtracelens.configPath=$TRACELENS_CONFIG -Dtracelens.http.includePaths=$TRACELENS_HTTP_PATHS -Dtracelens.port=$TRACELENS_PORT"
    export CATALINA_OPTS
fi
