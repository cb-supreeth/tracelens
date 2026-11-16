# Source from chargebee-app/tomcat/bin/setenv.sh. No effect unless TRACELENS=1.
# JAVA_OPTS is overwritten by cb-app's catalina.sh; CATALINA_OPTS is preserved.
if [ "${TRACELENS:-0}" = "1" ]; then
    TRACELENS_HOME="${TRACELENS_HOME:-$HOME/work/cb-tracelens}"
    TRACELENS_VERSION="${TRACELENS_VERSION:-0.1.1-SNAPSHOT}"
    TRACELENS_AGENT="${TRACELENS_AGENT:-$TRACELENS_HOME/tracelens-agent/build/libs/tracelens-agent-$TRACELENS_VERSION-agent.jar}"
    TRACELENS_CONFIG="${TRACELENS_CONFIG:-$HOME/work/chargebee-app/.run/tracelens/tracelens.json}"
    if [ "${TRACELENS_HTTP_PATHS+x}" = "x" ]; then
        echo "TraceLens capture paths now belong in JSON httpIncludePaths. Run make up-tracelens to migrate the old managed setenv block, or remove the obsolete TRACELENS_HTTP_PATHS override." >&2
        return 1
    fi
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
    case "$TRACELENS_AGENT$TRACELENS_CONFIG" in
        *[!a-zA-Z0-9_./,:@-]*)
            echo "TraceLens paths must use letters, digits, _, ., /, comma, colon, @ or -." >&2
            return 1 ;;
    esac
    # Refuse a second attachment; sourcing this helper twice installs duplicate
    # transformers even if the standalone runtime already owns a store.
    for TRACELENS_EXISTING_OPTS in "${CATALINA_OPTS:-}" "${JAVA_OPTS:-}" "${JAVA_TOOL_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}"; do
        case "$TRACELENS_EXISTING_OPTS" in
            *-Dtracelens.http.includePaths*)
                echo "Remove obsolete -Dtracelens.http.includePaths; configure httpIncludePaths in the service JSON instead." >&2
                return 1 ;;
            *-javaagent:*tracelens*|*"-javaagent:$TRACELENS_AGENT"*)
                echo "TraceLens is already attached in JVM options; keep only one startup hook." >&2
                return 1 ;;
        esac
    done
    unset TRACELENS_EXISTING_OPTS
    CATALINA_OPTS="${CATALINA_OPTS:-} -javaagent:$TRACELENS_AGENT=standalone -Dtracelens.configPath=$TRACELENS_CONFIG -Dtracelens.port=$TRACELENS_PORT"
    export CATALINA_OPTS
fi
