# TraceLens in cb-app (Java 8 / Tomcat)

Use [standalone agent mode](standalone-agent.md): one agent jar and a JVM startup
option. No Spring dependency, `WEB-INF/lib` installation, `web.xml` registration,
or UI changes are required. The same artifact supports the tested legacy javax
and modern jakarta servlet stacks.

The repository and service-orchestrator call this application `chargebee-app`;
TraceLens uses the shorter service identity `cb-app` in its bundled agent config,
viewer/MCP registry and fallback default. Keep `chargebee-app` in checkout paths
and lifecycle commands, and use `cb-app` when filtering TraceLens by service.

## Build

Build with JDK 21; the bridge, core and agent compile with `--release 8`:

```bash
cd ~/work/cb-tracelens
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :tracelens-agent:shadowJar
```

The checkout path and `/usr/libexec/java_home` command above are local macOS
examples. Use your checkout and JDK 21 installation on another machine.

The delivery artifact is:

```text
tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar
```

It bundles relocated dependencies and injects a dependency-free bootstrap bridge.
Do not install a Spring starter, adapter, or extra Jackson jars in cb-app.

## Enable in an already built local cb-app

For a fresh cb-app environment, use its documented `make up` first. Adding
TraceLens to an existing environment does not require rebuilding/seeding databases.

Add this to `chargebee-app/tomcat/bin/setenv.sh`, preserving existing settings:

```sh
. /absolute/path/to/cb-tracelens/scripts/cb-app-setenv.sh || exit 1
```

Adjust the checkout path for your machine. The helper adds options only when
`TRACELENS=1`; cb-app preserves `CATALINA_OPTS` but overwrites `JAVA_OPTS`. It selects
`-javaagent:...=standalone` and the bundled cb-app configuration.
If your TraceLens checkout is not `$HOME/work/cb-tracelens`, set
`TRACELENS_HOME=/absolute/path/to/cb-tracelens` when starting cb-app as well;
sourcing the helper from another location does not change its default checkout.
The helper rejects spaces and shell metacharacters in agent/config paths and
HTTP path prefixes; use a checkout/config path compatible with that restriction.

Restart the already-built application with its lifecycle hooks. The `make start`
hook enables jobs and clears Tomcat logs, so save important logs and export
important traces before running it:

```bash
cd ~/work/chargebee-app
make stop
TRACELENS=1 make start
```

The defaults in [cb-app-tracelens.json](../scripts/cb-app-tracelens.json) instrument
the entitlement helper/client packages. HTTP tracing is limited to
`/api/v2/features` and its descendants. Labels are optional, so browser/API clients
need no changes. For another investigation, adjust the package scope in the JSON
and the HTTP path prefixes before restarting:

```bash
TRACELENS=1 TRACELENS_HTTP_PATHS=/api/v2/features,/api/v2/item_entitlements make start
```

`TRACELENS_HTTP_PATHS=/` traces all non-ignored HTTP paths, but a monolith quickly
fills the bounded store. Empty execution timelines can mean the request was
rejected or ran outside the configured Java packages.

The debug port defaults to `7778`, separate from cb-app's application port. To run
multiple instrumented JVMs, give each a different port:

```bash
TRACELENS=1 TRACELENS_PORT=7782 make start
```

The EMS helper already uses `7779`; update the CLI/MCP URL and services registry
if you override either service's agent port.

To disable, restart without `TRACELENS=1`. To uninstall, remove only the TraceLens
source line from `setenv.sh`; preserve other startup settings.

### Optional Makefile launcher

After building the agent and adding the `setenv.sh` hook above, copy the
[launcher template](../scripts/Makefile.cb-app-tracelens) into cb-app as a separate
Makefile. The template is not installed by the TraceLens build. This leaves the
existing `Makefile` and its normal startup unchanged:

```bash
cd ~/work/chargebee-app
cp -n ../cb-tracelens/scripts/Makefile.cb-app-tracelens Makefile.tracelens
make -f Makefile.tracelens check
make -f Makefile.tracelens up
```

`up` delegates to the existing `make up`/service-orchestrator lifecycle with
`TRACELENS=1`. It can run the normal build/fixture setup; use it with the same
care as normal cb-app bring-up. An already-healthy JVM is reused, so `up` cannot
attach TraceLens to an application that is already running without it. To load
the agent or new configuration into an already-built app, use:

```bash
make -f Makefile.tracelens restart
make -f Makefile.tracelens status
```

`restart` delegates to the existing `make start` hook, which restarts Tomcat with
jobs enabled and clears existing Tomcat logs. It does not invoke the full build
or fixture setup. Save important logs and trace captures before restarting.
`status` reads the agent summary from port `7778` by default. Neither command
starts EMS or the separate TraceLens viewer. Use normal `make down` to stop cb-app.

The wrapper defaults to a sibling `cb-tracelens` checkout. Override its settings
on the command line; `TRACELENS_HOME` and `TRACELENS_CONFIG` must be absolute paths
because Tomcat changes its working directory during startup:

```bash
make -f Makefile.tracelens restart \
  TRACELENS_HOME=/absolute/path/to/cb-tracelens \
  TRACELENS_HTTP_PATHS=/api/v2/features,/api/v2/item_entitlements \
  TRACELENS_PORT=7782
```

`check` checks for the source hook, readable agent/config files and supported
startup settings without changing any service. It does not parse the JSON, test
debug-port availability, or prove that the application started or instrumentation
is working; use `status` and an authenticated request after startup. A fresh,
unbuilt cb-app checkout still needs its normal initial setup before adding this
opt-in launcher. `make -f Makefile.tracelens help`
lists the available targets and overrides.

## Verify and inspect by API

```bash
curl --fail http://localhost:7778/tracelens/summary
curl --fail http://localhost:7778/tracelens/metadata
curl --fail http://localhost:7778/tracelens/traces
```

Expect `mode=standalone`, `applicationId=cb-app`, `agentActive=true`,
`bridge.sinkRegistered=true` and `bridge.httpListenerRegistered=true` with the
bundled config and no identity override. These checks describe the running JVM,
not merely the source tree or the presence of a built jar. Make an authenticated
request to a selected API using your normal client. Normal responses include
`X-TraceLens-Id`; the trace
label is `auto:<METHOD> <path>`. Tomcat-generated errors may clear the header, but
API-label lookup still works. For example:

```text
auto:GET /api/v2/features/user-licenses/reference_counts
```

Use a separate MCP entry pointing to the agent's port, not cb-app's port:

```json
{
  "mcpServers": {
    "tracelens-cb-app": {
      "command": "/absolute/path/to/cb-tracelens/tracelens-cli/.venv/bin/tracelens",
      "args": ["--url", "http://localhost:7778", "mcp", "--source-root",
               "/absolute/path/to/chargebee-app"]
    }
  }
}
```

These absolute paths are placeholders. Install the
[CLI/MCP](sharing-and-configuration.md#4-install-the-cliviewermcp) and replace
both the executable and source checkout paths for your machine. The source root
must match the application's captured version; there is no automatic historical
checkout.

Ask your MCP client: “List the latest cb-app flows containing
`/api/v2/features/user-licenses/reference_counts`, then summarize the most recent
matching request.” `list_flows(lens_id=...)` searches the API label and supplies
the segment id for `get_flow_summary` or `get_flow`. Use
`/tracelens/export/<id>?evict=false` for non-destructive direct inspection.
The CLI accepts one source root per service, not repeated `--source-root` options.
Framework code in a sibling checkout may remain unresolved under the selected root;
inspect that checkout separately rather than assuming the viewer can resolve it.

The debug API is agent-owned and never passes through cb-app routing or changes
its authentication. It accepts direct loopback requests only, rejecting forwarded
and cross-origin browser requests. Point CLI/MCP at localhost, not a tenant's
`*.localcb.in` host. This is a local-development safeguard, not production auth.

## Migrating from the earlier javax adapter setup

The new helper selects standalone mode. Remove the earlier TraceLens filter and
its mapping from `webapp/WEB-INF/web.xml`, and remove only the previously copied
`tracelens-servlet-javax-*-adapter.jar` from `WEB-INF/lib`. Keep the setenv source
line. Point CLI/MCP to `7778` instead of `8080`.

Updated adapters back off automatically in standalone mode, but older built jars
may not. Never deliberately enable both runtime modes. The optional
`tracelens-servlet-javax` module and [filter snippet](../scripts/cb-app-filter.xml)
remain available for explicit adapter integrations; they are not required here.

## Logged-in users and API callers

The bundled cb-app config opts into generic `httpIdentityHooks`. The framework
names and getter mappings live in configuration, not the agent implementation.
No cb-app or middleware source changes are needed:

- Successful API authentication: `ApiURLConfig.validateApiRequest(model)` captures
  `model.site().id()` while the API model is still available. Normal API-key calls
  display `apicall_<siteId>`, not the key owner's email.
- Calls authenticated as `cb-ui-middleware` additionally use the already-resolved
  `model.getMsOriginUser().email()`, displaying `<email>_<siteId>`. A raw origin-user
  header is not treated as proof of a logged-in user. The service-name allowlist
  is safe here because cb-app has already verified that service's credentials.
- Classic UI actions use `AppActionModel.exec()` on successful return, with
  `currentUser().email()` and `currentSite().id()`. Pages without both values remain
  unlabelled by this mapping.
- Explicit `tracelens-user` labels still win. Failed authentication stays
  `anonymous` unless another existing identity source supplies a label.

This must happen before the framework clears its thread-local request context;
reading only the standard servlet principal at filter exit is insufficient.
Exports include `siteId` and `identitySource` when a hook resolves identity.

Hooks are optional, bounded, and run only after the named method returns without
throwing. They invoke configured public, zero-argument getters on existing
objects; null/missing getters fail open. Configure only side-effect-free getters
and a verified post-authentication lifecycle point. Never configure secrets,
authentication functions, database lookups, or payload getters. These labels are
diagnostic metadata, not an authorization mechanism. Test mappings again when
the application's authentication framework changes.

Restart the JVM after installing the rebuilt agent/config. Existing traces are
not backfilled, and a restart clears the in-memory store; export important traces
with `?evict=false` first. A direct API-key test cannot verify the UI-user branch:
also perform a normal logged-in UI action through middleware.
Browser pins preserve a separate local snapshot when saving succeeds; they do
not keep the agent's store alive, transfer between browser profiles, or make
that snapshot available through MCP.

## Boundaries and verification

- Metadata includes method, path, status, timing, content type and declared body
  size. Request bodies, cookies, auth headers and raw query values are not captured.
- Identity defaults to an explicit `tracelens-user` or a standard Servlet principal.
  The bundled optional cb-app hooks additionally resolve the custom context as
  described above; they do not make this framework-specific behavior universal.
- The example config explicitly sets `httpClientCaptureEnabled: true`, so
  supported outgoing JDK HTTP calls can appear even without a receiving agent.
  `distributedTracingEnabled: true` additionally enables correlation, with new
  outgoing headers restricted to `localhost:8082` / `127.0.0.1:8082`. Merely
  observing another destination does not allow propagation there.
  See [cross-service tracing](distributed-tracing.md) for cb-app → EMS joins, the
  combined UI/MCP registry, and independent single-service operation.
- Custom cb-app scheduler/database routing hooks are not Spring hooks. The sample
  disables scheduled-job hooks and legacy SQS propagation
  (`instrumentMessaging: false`), but enables `captureMessagingBoundaries: true`.
  This captures supported SQS/Kafka/Queueman boundaries without claiming arbitrary
  queue or job coverage.
  Kafka poll requires an active trace; Queueman can create a local consumer trace
  without importing producer correlation. See the
  [coverage and evidence limits](dependency-visibility-and-change-impact.md).
- The store is bounded in-memory and shared per JVM. Restarts clear traces.
  There is no durable collector or production deployment in this setup.

With JDK 8 and JDK 21 installed:

```bash
./gradlew test :samples:standalone-smoke:standaloneSmoke
```

These tests use ordinary servlets and filters with the actual shaded agent, no
TraceLens app dependency or registration, on isolated Java 8/Tomcat 7.0.54 and
Java 21/Tomcat 10.1 instances. They do not start the real cb-app or prove its
customized runtime has been verified end-to-end.

For an investigation recipe after setup, see [use cases](use-cases.md). Rebuild
and restart cb-app to load agent/config changes; restart the viewer/reconnect MCP
for Python changes and reload the browser for updated assets. No upgrade can
recover boundary events that were never recorded in an older trace.
