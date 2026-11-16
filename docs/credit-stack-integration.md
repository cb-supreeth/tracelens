# TraceLens for the local credit stack

The [guided installer](guided-setup.md) supports **cb-app, EMS, Plans, Ledger,
and Grant Orchestrator**. Each JVM uses the same combined TraceLens JAR with its
own `tracelens.json` and loopback debug port. Selecting a service prepares tracing
files; it does not provision, build, start, or restart that application.

UI middleware is not a setup option and is not part of this tracing workflow.
Earlier middleware captures and validation records below are historical evidence.

| Service | Example application port | TraceLens port | Configuration |
| --- | --- | --- | --- |
| cb-app | 8080 | 7778 | [`scripts/cb-app/tracelens.json`](../scripts/cb-app/tracelens.json) |
| EMS | 8082 | 7779 | [`scripts/cb-ems/tracelens.json`](../scripts/cb-ems/tracelens.json) |
| Plans | Use its configured local business port | 7786 | [Embedded installer template](../tracelens-tool/src/main/resources/tracelens-setup/cb-plans.json) |
| Consumption Ledger | 9187 | 7783 | [`scripts/cb-consumption-ledger/tracelens.json`](../scripts/cb-consumption-ledger/tracelens.json) |
| Grant Orchestrator | 7087 | 7784 | [`scripts/cb-grant-orchestrator/tracelens.json`](../scripts/cb-grant-orchestrator/tracelens.json) |

## Guided setup

Run `./setup.sh` from the extracted bundle and choose **Set up TraceLens**.
Select only the services you already run locally, then follow the UI's review
and launch instructions. Return to the [setup reference](guided-setup.md) if
needed. Choose Overview, Methods, or Detailed per service; SQL/bindings
and incoming request parameters/bodies are separate opt-ins. Only configured API
paths are retained. The loopback session expires after 15 minutes; rerun the
script to continue. EMS/Plans can use an optional executable-JAR
launcher with an explicit Java 21 home. Ledger/GO receive `vm-options.txt` to add
to their existing application runtime/Tomcat launcher; cb-app retains its Java 8
startup integration. No application restart is performed automatically.

To add services or edit capture settings later, rerun the same script, choose
**Edit capture settings**, load the same recognized installation, and use a
service's editor or **Add service**. Capture edits preserve unrelated
JSON and create backups; application launchers and unchecked services remain
unchanged. Review **ready** additions/updates, unchanged **existing**, and
**failed** outcomes independently: a bad service does not block ready siblings.
Optional post-save runtime profiles can start/restart supported Tomcat/JAR
children only after a separate review and downtime/in-memory-trace-loss consent;
existing IDE/custom processes are not adopted. If a service
fails during file creation, inspect the reported partial files before retrying.
Global/shared-file errors still stop installation.

Managed extensions keep the viewer port, saved-data paths, and previous snapshot
namespace, pinning it as `viewer.namespace` before changing the registry. Shared
file updates are backed up. A newer bundle may add a separate tooling JAR and
update generated viewer/check launchers, but not existing agent JARs or
application launchers. Custom viewer/check launchers require manual review for
that upgrade. Arbitrary nonempty directories are not adopted by setup.

## Manual attach and register

Build the combined JAR with JDK 21 (`./gradlew :tracelens-agent:shadowJar`).
Use the built `tracelens-agent-0.1.1-SNAPSHOT-agent.jar`, not the thin library JAR.
Add these JVM options to the selected service's existing launcher, substituting
its paths and debug port:

```text
-javaagent:/path/to/tracelens.jar=standalone
-Dtracelens.configPath=/path/to/service/tracelens.json
-Dtracelens.port=7783
```

Restart only that service. Confirm its identity and `recordingEnabled` through
`http://127.0.0.1:7783/tracelens/summary`; attachment alone does not prove coverage.
Run a permitted read-only request and confirm a retained segment with execution
events. Application authentication continues to apply.

Add each selected service's name, loopback debug URL, and matching checkout root
to the viewer's `viewer.services` registry. The guided installer does this for
successful additions. Registering a service does not attach its agent.
Manual registries without `viewer.namespace` still derive a namespace from their
mappings and viewer mode: changing them can select an empty namespace without
deleting the old snapshots. Preserve/export evidence before such changes.
Restart the viewer after changing its registry. No additional viewer process or
application adapter is required.

## What is captured

- **Ledger and Grant Orchestrator:** `/api/v2` and `/micro_service` requests;
  method/line events in the selected application packages; supported outgoing
  JDK/Apache HTTP calls; supported database, cache, and queue boundaries.
- **Plans:** the installer template selects `/items`, `/item_prices`,
  `/item_families`, and `/custom_pricing_units`, with method events in
  `com.chargebee.items`. These are capture defaults, not proof of live coverage.
- **SQL:** the Ledger/GO reference templates enable local MySQL/PostgreSQL query
  capture, with at most 100 query
  records per request and 4,096 characters per SQL text. Parameter capture is
  disabled in the portable templates; local overrides can opt in with
  `captureJdbcParameters: true`. SQL literals can still contain sensitive information; review saved
  evidence before sharing. The wizard uses the reviewed SQL opt-ins for newly
  added services and preserves existing services' choices. Result rows are not captured.
- **Bounds:** the additional-service templates retain at most 100 requests and 100,000 events per JVM, with a
  30-minute idle TTL. Payload capture is disabled in the portable templates;
  export does not evict traces.
  Buffers remain in JVM memory and are lost on restart.

These settings are development defaults, not high-traffic deployment guidance.
Each application's supported runtime is separate from the JDK used to build
TraceLens; attaching an agent does not change that runtime.

## Correlation and limitations

Incoming W3C context is supported. Outgoing context requires a supported client
and a destination in that calling service's
`httpPropagationAllowedDestinations`. Adding a service to the viewer does not
change upstream capture or propagation configuration. For example, a cb-app
configuration that allows only EMS will not automatically produce linked
cb-app → Ledger traces after Ledger is registered.

The cb-app development and bundle templates now include
`/api/v2/ledger_account_balances`, `/api/v2/ledger_operations` (including
`/capture`, `/allocate`, and other descendants), `/api/v2/grant_blocks`,
`/api/v2/promotional_grants`, and `/api/v2/custom_pricing_units` for callbacks.
They retain the existing EMS destinations and add
exact loopback/localhost and `mannar-test.localcb.in` authorities on Ledger's
9187 and GO's 7087 ports. Substitute your own exact local site hostname/ports;
wildcard subdomains are not supported. The application must actually send to
one of those authorities: this allowlist does not change cb-app routing.

cb-app's Ledger/GO proxies use Apache HttpClient 4, so an older installed JAR
without that adapter needs updating as well as the JSON. Restart cb-app after
either change; no application rebuild is required. Other services need not be
restarted if already using the compatible agent. Keep authentication, prepaid
feature gates and application job settings unchanged: recording an API does not
enable it. An API rejected before a downstream call correctly has no Ledger/GO
segment, even with valid tracing configuration.

Apache support covers synchronous HttpClient 4 `InternalHttpClient` and
`MinimalHttpClient` (tested with 4.5.14), not Apache 5 or async clients.
Other HTTP/reactive clients need their own compatibility verification; the
[standalone reference](standalone-agent.md#compatibility-boundary) records those
boundaries. Enabling a service in setup does not expand client instrumentation.

The portable configs disable scheduled-entry tracing and message-consumer
instrumentation. This is not an application worker switch: service job execution,
Kafka consumers, feature flags, and prepaid-credit workflows remain controlled
by each service's own configuration. Attaching TraceLens does not enable them.

## Regression checks

```bash
./gradlew :tracelens-bridge:test :tracelens-core:test :tracelens-agent:test \
  :tracelens-agent:shadowJar :tracelens-agent:java8Smoke \
  :tracelens-agent:java8ToolSmoke
```

Focused coverage lives in `CreditServicesConfigTest`,
`ApacheHttpClientWeavingTest`, and `VertxHttpWeavingTest`. Framework tests cover
real clients/servers, concurrent requests, header ownership, redirects,
exceptions, disconnects, scoped context restoration, and bounded in-flight state.
They do not replace authenticated, end-to-end prepaid-credit business-flow tests.

## Initial local validation — 2026-10-26

This is a historical validation record, including middleware tracing that is no
longer enabled in the current local setup.

The isolated development stack was restarted with the new JAR and checked using
read-only requests. Application authentication was retained; existing credentials
were used locally without printing them. The final samples produced:

| Service | Probe | Observed evidence |
| --- | --- | --- |
| Ledger | `GET /micro_service/accounts/unit_subscription_count` | HTTP 200, 6 method entries, 4 MySQL executions |
| Grant Orchestrator | `GET /micro_service/sandbox/time_machine_status` | HTTP 200, 4 method entries, 6 MySQL executions |
| Middleware | Unknown EMS route and unauthenticated metrics route | Expected 404/401, separate proxy/module traces, method/line events |

All three reported zero failed instrumentations. All ten middleware module health
checks returned 200; a health probe did not add retained traces, and the Vert.x
in-flight registry returned to zero. Counts describe these samples, not guarantees
for future requests. The viewer rendered Ledger's grouped MySQL calls and resolved
source roots for all three services. Its 15 existing saved captures were preserved.

Existing cb-app/EMS processes and capture settings were unchanged; Plans remained
uninstrumented. Service job execution and GO's Kafka consumer stayed disabled.
No prepaid feature flags or business data were changed for validation. This does
not establish authenticated UI flows, worker/event processing, or end-to-end
cb-app → Ledger/GO coverage.

### cb-app capture follow-up — 2026-10-26

After the initial probes, cb-app's local configuration was updated with the four
credit API prefixes and the exact Ledger/GO propagation destinations above. Its
agent was upgraded to include Apache HttpClient 4 support and only cb-app was
restarted; the existing EMS agent, viewer registry, saved captures, retention and
SQL settings were preserved.

An authenticated, read-only `GET /api/v2/ledger_operations` request appeared in
both the cb-app agent and the viewer with 65 events and 8 method entries. It
returned HTTP 400 because credit units were disabled for the local site, before
any outgoing HTTP call. This verifies upstream capture, not end-to-end
cb-app → Ledger/GO propagation. Feature flags and business data were not changed.
Also verify cb-app's effective service URLs before an end-to-end test: its code
defaults use different ports from this isolated stack; tracing allowlists do not
override routing.

The focused config/path tests, Apache HttpClient weaving test and Java 8 agent
smoke check passed. At the time of this probe, API request-data and native
ClickHouse SQL capture were not enabled. They now have independent, default-off
options documented in [request data and ClickHouse SQL](request-data-and-clickhouse.md);
the legacy payload flag and JDBC SQL flag do not enable them.

### GO SQL parameters and live-trace expiry — 2026-10-26

A local-only GO configuration now opts into `captureJdbcParameters`; the shared
GO template remains default-off for parameters. After restarting only GO, a
read-only `GET /micro_service/sandbox/time_machine_status?domain=mannar-test`
returned HTTP 200 with five SQL executions, four containing bound parameters.
The viewer displayed reconstructed SQL with captured values, the original SQL
template and the typed parameter list. Clicking the GO service card opened its
code flow. Jobs and Kafka consumption remained disabled; this was a direct GO
probe, not a cb-app → GO request.

Live trace links do not preserve evidence. An idle-expired GO trace was no longer
available for code navigation, even though an already-open browser still showed
its earlier diagram. The viewer now keeps an explicit unavailable/expired
message visible, with recovery actions, instead of losing the error during
auto-refresh. Save a snapshot while evidence is available to retain SQL,
parameters and source for later inspection. Enabling parameter capture cannot
add values retroactively to older traces.

### Later local GO/Ledger capture verification — 2026-10-26

Both isolated local backends were upgraded to the current combined JAR and
restarted individually. Their private configurations now enable
`captureHttpQueryParameters`, `captureHttpRequestBodies`, and
`captureJdbcParameters`, with `maxHttpPayloadLength: 4096` and
`redactHttpPayloads: false` for this explicitly opted-in local debugging setup.
The portable templates were not changed. Request data can contain sensitive
values; review captures before sharing. Bodies are captured only when the
application consumes supported JSON/form content, not by pre-reading requests.

Verification covered:

- Ledger's read-only unit/subscription-count request: HTTP 200, incoming query
  parameters retained, five MySQL executions, four with bound values.
- GO's read-only time-machine-status request: HTTP 200, incoming query parameters
  retained, four MySQL executions with bound values.
- JSON body capture in Ledger and form-body capture in GO: fixed invalid inputs
  produced the expected HTTP 400 validation errors before grant/time-machine
  business operations. Captured dummy values matched the submitted bodies.
- The viewer's request-data projection and keyword search found both body probes.
- Authenticated `GET /api/v2/ledger_operations` returned HTTP 200 with an empty
  list and linked cb-app and Ledger segments, injected HTTP context, and an
  observed downstream call. Both upgraded agents reported zero failed classes.

These are capture checks, not end-to-end grant creation, consumption, or worker
coverage. Prior local prepaid-credit settings and GO workers were preserved;
Ledger jobs remained disabled. cb-app, EMS, and the viewer were not restarted
for this upgrade. Earlier snapshots cannot gain missing fields retroactively;
new captures use the updated settings.

### Custom-pricing-unit capture follow-up — 2026-10-26

A read-only cb-app `GET /api/v2/custom_pricing_units/` returned HTTP 200 and was
retained after adding the prefix. Its outgoing EMS
`POST /custom_pricing_units/search` returned HTTP 200 with injected context, but
the existing EMS capture scope excluded that route, so no EMS receiver segment
was observed. This verifies the new cb-app prefix and outgoing observation,
not a replay of a GO callback. Read-only grant-block and ledger-account-balance
requests also returned HTTP 200 with linked cb-app/Ledger segments. No GO grant
writes were performed in this follow-up.

The portable EMS configuration and embedded setup template now also include
`/custom_pricing_units` (29 prefixes total). This addresses that receiver-scope
gap for newly started JVMs; it does not repair old captures. The current demo
verification and retained examples are tracked in the [demo guide](demo-guide.md).

### Fresh successful multi-service grant demo — 2026-10-26

A new local promotional-grant reproduction linked **nine service segments**
across cb-app, Grant Orchestrator, Ledger and EMS, including the custom-pricing-unit
callbacks. All retained HTTP outcomes were 200. A separate balance read-back
verified **10 test credits** and retained 12 Ledger SQL executions with bound
values. The [demo guide](demo-guide.md) links both saved examples alongside a
separate downstream-failure example; the successful HTTP status alone is not used
as proof that the balance changed.

The final saved grant graph retains all 169 boundary records (167 SQL executions),
including GO's 17 queries and Ledger's 47. This was re-saved after raising the
bounded request-flow dependency projection to 512; earlier capped snapshots do
not gain missing graph nodes automatically.

This verifies the exercised synchronous grant/read-back path, not every grant
variant, consumption workflow, background worker or asynchronous event consumer.
