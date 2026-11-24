# TraceLens in cb-app (Java 8 / Tomcat)

Use [standalone agent mode](standalone-agent.md): one agent jar and a JVM startup
option. No Spring dependency, `WEB-INF/lib` installation, `web.xml` registration,
or UI changes are required. The same artifact supports the tested legacy javax
and modern jakarta servlet stacks.

The repository and service-orchestrator call this application `chargebee-app`;
TraceLens uses the shorter service identity `cb-app` in its bundled agent config,
viewer/MCP registry and fallback default. Keep `chargebee-app` in checkout paths
and lifecycle commands, and use `cb-app` when filtering TraceLens by service.

## Supplied files, without a TraceLens checkout

Use the Java setup ZIP: one combined `tracelens.jar`, `setup.sh`, and portable
service configurations. Extract it, run `./setup.sh`, and choose **Set up
TraceLens**. The UI guides you through selecting the working `chargebee-app`
checkout, API paths and capture settings. Refer to
[guided setup](guided-setup.md) only when needed; no separate viewer package or
TraceLens checkout is required.

Choose Overview, Methods, or Detailed; SQL/bindings and incoming request
query/body capture are independent opt-ins. Only configured API paths are
retained. Saving prepares an opt-in `CATALINA_OPTS` hook and service JSON, not an
application build or restart. Review any start/restart separately, including
downtime and loss of in-memory traces. cb-app keeps Java 8 and its normal
application dependencies/authentication.

For later edits or additions, rerun the same script, choose **Edit capture
settings**, load the same installation, then edit cb-app or **Add service**.
The dashboard session expires after 15 minutes. Other supported services can be
configured independently; a failed service does not block valid siblings.
cb-ui-middleware is not a setup option. Do not stack a manual or checkout helper
on top of the guided startup hook.

The sections below retain lower-level and existing checkout integrations for
maintainers; they are not extra steps after the guided setup.

## One JSON file for capture settings

Use the selected service JSON for `httpIncludePaths`, `packages`, identity hooks
and retention together. The checkout-based default is
`chargebee-app/.run/tracelens/tracelens.json`; guided setup instead owns the
selected installation's `cb-app/tracelens.json`. Use the path reported by your
chosen setup, not a second copy. Launchers only wire the agent JAR,
config-file path and debug port; they do not carry a second HTTP allowlist.

The current [new-config template](../scripts/cb-app/tracelens.json) includes
Features, Plans/items, metering/usage, subscriptions and related billing API/page
prefixes. Use that file as the authoritative list, not a copied list in this guide.
Existing installations and downloaded bundles may have narrower scope; setup
preserves the selected JSON. For a deliberately focused Features investigation,
the field could be:

```json
{
  "httpIncludePaths": ["/api/v2/features"]
}
```

This is a field excerpt, not a replacement for the whole file. Preserve your
existing package, identity and retention settings when editing it. A path matches
itself or its descendants; entries are literal prefixes, not wildcard patterns.
`["/"]` permits all otherwise eligible HTTP entries, while `[]` disables retained
HTTP entries. Built-in ignored paths remain excluded. Scheduler/queue capture and
distributed context propagation have separate controls.

Generic agent configuration that omits the field keeps the compatibility default
`["/"]`. For cb-app, set the field explicitly so an upgrade cannot silently
broaden its local capture scope.

Configuration is read at application startup, not hot-reloaded. Editing JSON
does not change an existing JVM. Save evidence,
then choose the normal app restart when ready; reproduce afterward because old
captures are not repaired.

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

This manual **checkout-based** route uses the repository helper, not the supplied
setup ZIP. Choose one hook: do not add this source line alongside a guided-setup
or direct supplied-JAR block.

For a fresh cb-app environment, use its documented `make up` first. Adding
TraceLens to an existing environment does not require rebuilding/seeding databases.

For a new installation, create the app-local config once, preserving it on later
updates:

```bash
cd ~/work/chargebee-app
mkdir -p .run/tracelens
cp -n /absolute/path/to/cb-tracelens/scripts/cb-app/tracelens.json .run/tracelens/tracelens.json
```

If it already exists, edit that file instead of overwriting it. For an older
file without `httpIncludePaths`, add the field with the paths you intend to capture.
Use `TRACELENS_CONFIG=/absolute/path/to/custom/tracelens.json` only to select another file.

Add this to `chargebee-app/tomcat/bin/setenv.sh`, preserving existing settings:

```sh
. /absolute/path/to/cb-tracelens/scripts/cb-app-setenv.sh || exit 1
```

Adjust the checkout path for your machine. The helper adds options only when
`TRACELENS=1`; cb-app preserves `CATALINA_OPTS` but overwrites `JAVA_OPTS`. It selects
`-javaagent:...=standalone` and the app-local `.run/tracelens/tracelens.json` by default.
If your TraceLens checkout is not `$HOME/work/cb-tracelens`, set
`TRACELENS_HOME=/absolute/path/to/cb-tracelens` when starting cb-app as well;
sourcing the helper from another location does not change its default checkout.
For a manual cb-app checkout outside `$HOME/work/chargebee-app`, also select its
app-local JSON explicitly with `TRACELENS_CONFIG`; the helper alone does not infer
that location from the source line.
The helper rejects spaces and shell metacharacters in agent/config paths;
use a checkout/config path compatible with that restriction.

Restart the already-built application with its lifecycle hooks. The `make start`
hook enables jobs and clears Tomcat logs, so save important logs and export
important traces before running it:

```bash
cd ~/work/chargebee-app
make stop
TRACELENS=1 make start
```

The [new-config template](../scripts/cb-app/tracelens.json) includes selected
entitlement, Plans/items, subscription and billing packages, matching HTTP path
prefixes, and 12 explicit custom job types. It is not whole-app coverage.
Your selected existing JSON or downloaded bundle can have a different scope.
Labels are optional, so browser/API clients need no changes. For another investigation, edit the package
scope and HTTP paths in that same JSON before restarting; for example:

```json
{
  "httpIncludePaths": ["/api/v2/features", "/api/v2/item_entitlements"]
}
```

`httpIncludePaths: ["/"]` permits all non-ignored HTTP paths, but a monolith quickly
fills the bounded store. Empty execution timelines can mean the request was
rejected or ran outside the configured Java packages.

### Capture a Chargebee custom scheduled job

The standalone agent supports Chargebee's custom scheduler separately from
Spring `@Scheduled`. Set `chargebeeJobTypes` to the exact runtime job-type names
you want; omission or `[]` disables this custom-job hook. The current cb-app
template selects 12 job types, including `compute_billing_metrics`, renewal,
subscription lifecycle and entitlement-override work. For just the billing-metrics
investigation, a focused selection is:

```json
{
  "chargebeeJobTypes": ["compute_billing_metrics"],
  "instrumentScheduled": false
}
```

Merge these fields into your selected existing JSON; do not replace the whole
file. Add `com.chargebee.app.billing_metrics` to its `packages` for source-line
and method evidence from `BillingMetricsComputeJob` and related code. Additional
called packages need their own scope entries. `instrumentScheduled` controls only
Spring `@Scheduled` methods; keep it `false` to avoid unrelated Spring job noise.
Neither `httpIncludePaths` nor viewer filters enable or disable this custom-job
capture. The template seeds new configurations only, so existing installations
must opt in explicitly.

The hook wraps `AppJobExecutor.exec(SchJob, Long)`, starting a separate `SCHEDULER`
root for each selected dispatch. Its lifetime covers initialization, prechecks
and execution; source events are still limited to configured packages. It records
safe job-type, job-ID and site-ID metadata, not the job payload. There is no
automatic link to the earlier API request that scheduled a database-backed job.

Outcome comes from the executor's observed `audit(SchJob, SchJobRegion,
JobContext, Status, Throwable)` call, not merely a normal method return:

| Observed audit status | TraceLens attempt outcome |
| --- | --- |
| `SUCCEEDED` | `SUCCEEDED` |
| `IGNORED` | `SKIPPED` |
| `FAILED`, `RESCHEDULED`, `RESCHEDULE_FAILED` | `FAILED` |
| No recognized audit outcome | `UNKNOWN` |

`RESCHEDULED` means this attempt failed; a later retry can succeed and gets its
own capture. A `COMPLETED` trace lifecycle alone is not proof of job success.
Open **Scheduled jobs**, select service `cb-app`, and search for
`compute_billing_metrics` to inspect a new retained run.

This feature requires a newly built agent containing the custom scheduler hook.
Replacing JSON beside an older downloaded JAR does not add the instrumentation:
rebuild or obtain the updated shaded agent, preserve your chosen config, and
restart the cb-app JVM when ready. Save important logs and live captures first;
restart clears traces and the normal app lifecycle can enable jobs/clear logs.
Viewer reloads do not activate the hook or backfill old runs.

Tracing does not modify scheduler database records, scheduling/retry behavior or
job results. It adds no job-payload persistence, database or network exporter;
captures use the existing bounded in-memory store and normal retention limits.

### Missing upstream segments on the Plans page

One page can issue several independent requests. In addition to items and item
prices, the Plans UI can call cb-app `/api/v2/meters`, `/api/v2/meters/meta`, and
`/api/v2/usage_events/meta`, which cb-app forwards to EMS. Include these **cb-app
API paths**, not just the browser's `/plans` route, to retain their root segments:

```json
{
  "httpIncludePaths": [
    "/api/v2/features",
    "/api/v2/items",
    "/api/v2/item_families",
    "/api/v2/item_prices",
    "/plans",
    "/api/v2/meters",
    "/api/v2/usage_events"
  ]
}
```

Merge this field into the selected service JSON, then restart when ready.
The path prefixes include their descendants. Keep your other investigation
prefixes when adjusting this list; rerunning setup preserves the existing list.
For meter/usage source events, also include
`com.chargebee.app.api.helper.configs.ubb.meters` and
`com.chargebee.app.api.helper.configs.ubb.usage_events` in your local JSON's
`packages`; package scope and HTTP capture scope are separate controls.

A filtered cb-app request can still propagate tracing context to EMS. EMS then
has a parent ID but no retained upstream segment, so the viewer reports
**Upstream segment unavailable** (older viewers: **Root missing or ambiguous**).
Eviction, an unavailable service, or an uninstrumented upstream can also leave
gaps. TraceLens does not guess a root from the service name. Restart cb-app after
changing its capture scope and repeat the request; old partial traces are not
repaired. Save important logs/captures before restarting. UI filters do not alter
agent capture scope.

The debug port defaults to `7778`, separate from cb-app's application port. To run
multiple instrumented JVMs, give each a different port:

```bash
TRACELENS=1 TRACELENS_PORT=7782 make start
```

The EMS helper already uses `7779`; update the CLI/MCP URL and services registry
if you override either service's agent port.

To disable, restart without `TRACELENS=1`. To uninstall, remove only the TraceLens
source line from `setenv.sh`; preserve other startup settings.

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
      "command": "java",
      "args": ["-jar", "/absolute/path/to/tracelens.jar",
               "--url", "http://localhost:7778", "mcp", "--source-root",
               "/absolute/path/to/chargebee-app"]
    }
  }
}
```

These absolute paths are placeholders. MCP is included in the combined JAR;
replace its path and the source checkout path for your machine. See
[Java MCP](java-distribution.md#mcp) for a multi-service configuration. The source root
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
**Save snapshot** preserves a separate viewer-file snapshot when saving succeeds;
its link works across browsers using the same viewer configuration and storage,
but it does not keep the agent's store alive. Legacy browser pins remain
browser-local. **Use with CLI/MCP** explicitly creates a separate minimized
capture for assistant/terminal workflows; neither kind of full snapshot is read
directly by MCP. See [saved evidence and comparison](developer-workflow.md).

## Boundaries and verification

- Metadata includes method, path, status, timing, content type and declared body
  size. Incoming query parameters and supported Tomcat JSON/form body reads have
  separate default-off capture switches; cookies, auth headers and responses are
  excluded. See [request-data capture](request-data-and-clickhouse.md).
- Identity defaults to an explicit `tracelens-user` or a standard Servlet principal.
  The bundled optional cb-app hooks additionally resolve the custom context as
  described above; they do not make this framework-specific behavior universal.
- The example config explicitly sets `httpClientCaptureEnabled: true`, so
  supported outgoing JDK HTTP and synchronous Apache HttpClient 4 calls can appear
  even without a receiving agent.
  `distributedTracingEnabled: true` additionally enables correlation, with new
  outgoing headers restricted to the exact EMS, Ledger and GO authorities in
  `httpPropagationAllowedDestinations`. Merely observing another destination
  does not allow propagation there. Each receiver independently applies its own
  API capture scope, including callbacks into cb-app.
  See [cross-service tracing](distributed-tracing.md) for cb-app → EMS joins, the
  combined UI/MCP registry, and independent single-service operation.
- The sample opts into [12 explicit custom job types](#capture-a-chargebee-custom-scheduled-job),
  including `compute_billing_metrics`, while disabling Spring `@Scheduled` capture
  (`instrumentScheduled: false`). Job types absent from the selected JSON remain
  out of scope; this is
  not arbitrary scheduler or database-routing coverage.
- The sample disables legacy SQS propagation (`instrumentMessaging: false`),
  but enables `captureMessagingBoundaries: true`.
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

For an investigation recipe after setup, see [use cases](use-cases.md). Restart
cb-app to load an updated agent/config; no application rebuild is needed for
standalone capture changes. Restart the viewer/reconnect MCP for tooling-JAR
changes and reload the browser for updated assets. No upgrade can
recover boundary events that were never recorded in an older trace.
