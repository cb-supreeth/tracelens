# Java distribution and guided setup

For local **cb-app, EMS, Plans, Ledger, or Grant Orchestrator** tracing, start
with the [guided setup](guided-setup.md). Select only the services you need;
cb-app is the initial selection. The Java ZIP contains one combined JAR, a setup
launcher and portable service configurations:

```text
tracelens.jar
setup.sh
tracelens.json          # portable cb-app template
ems/tracelens.json      # portable EMS template
```

From the extracted directory, run `./setup.sh`, or
`java -jar tracelens.jar setup`. Setup opens a short-lived loopback UI; review the
plan before applying it. Choose **Set up TraceLens** for a first installation or
**Edit capture settings** → **Load services** for a saved installation. The same
script supports later edits and **Add service**; there is no second setup tool.
The loopback session expires 15 minutes after launch. It supports native
macOS/Linux installations and refuses
unrecognized nonempty destinations rather than adopting or overwriting them.
UI middleware is not an installer option. Saving configuration never starts or
restarts an application; an optional separate confirmation can manage an explicit
Tomcat/JAR child. Setup does not build applications, provision dependencies, or
edit IDE configurations. Use the manual reference below for installations not
managed by the wizard.

The same shaded JAR has two entry points. `-javaagent:...=standalone` records
evidence inside the application JVM. `java -jar ...` runs setup, the viewer, CLI,
or stdio MCP server in a separate JVM. Preparing setup files does not attach an
agent, and attaching an agent does not start the web viewer.

Only the combined JAR and a supported JDK are needed to run the delivered tooling.
Manual tooling can omit a source checkout;
guided setup takes existing application checkout roots. Java 8 is the minimum
runtime for setup/viewer/CLI/MCP. cb-app retains Java 8; optional generated
EMS/Plans launchers require an explicit Java 21 home and an existing built app
JAR. Ledger/GO receive VM options for their existing application runtime/Tomcat
launcher, not an executable-JAR launcher.
This is not a claim of compatibility with every JVM,
container, framework, or client-library version.

Choose **Overview**, **Methods**, or **Detailed** per service. SQL,
bindings and incoming request query/body data are separate opt-ins; only allowed
API paths are retained. Capture edits require a JVM restart, not an application
rebuild. Restart review/consent is separate from saving, and failed services do
not prevent valid siblings from being configured or started.

The [historical validation report](java-parity-validation.md) records earlier
regression coverage and packaged Java 8 checks; it is not an installation guide.

## Before setup

Check the [guided setup prerequisites](guided-setup.md#prerequisites) first:

- If selecting cb-app, verify it works locally on Java 8: log in successfully and reproduce
  the target flow **without TraceLens**, using its normal dependencies and test data.
- Verify each additional selected service works normally: EMS/Plans use Java 21;
  Ledger/GO retain their existing application runtime and launcher. Check
  cb-app's connection when testing a flow through it. Unselected services are
  not prerequisites for setup.
- Have the selected applications' matching checkout/source paths and an existing
  JDK 8+ for setup/viewer. TraceLens does not provision apps, dependencies or credentials.

Apps need not stay running during the wizard. Confirm the normal workflow first,
prepare the tracing files, then stop/restart deliberately to attach the agent.

## Build or unpack

Maintainers build the distribution with JDK 21:

```bash
./gradlew :tracelens-agent:javaBundle
```

The current output is
`build/distributions/tracelens-java-0.1.1-SNAPSHOT.zip`. The JAR is the same
agent artifact produced by `:tracelens-agent:shadowJar`, renamed inside the ZIP.
The portable configurations come from
[`scripts/java-bundle/tracelens.json`](../scripts/java-bundle/tracelens.json) and
[`scripts/java-bundle/ems/tracelens.json`](../scripts/java-bundle/ems/tracelens.json).
The combined JAR also embeds the installer templates for Plans, Ledger, and GO;
they do not need separate downloads.
Unpack the ZIP into a directory you control. For guided setup, run `./setup.sh`
there and choose a separate new/empty installation directory, or reuse a
recognized guided directory to load/edit settings or add services. Setup generates
`start-viewer.sh`, `check-setup.sh`, selected-service files and explicit launch
instructions; a written-file confirmation is not a connectivity/capture check.
The primary generated service JSON owns `viewer.services`, local source roots
and private saved-data paths; there is no separate `viewer.json`.

### Add services or edit capture settings later

Run setup again, choose the same guided installation directory and load its
settings. Review each service's outcome: **ready** identifies an addition or a
reviewed capture update, **existing** means no change, and **failed** includes an
actionable problem. Updates preserve unknown JSON and back up overwritten configs;
they do not rewrite existing launcher bytes. One invalid service does not block
ready siblings. Unchecked services already in the registry remain there.
An empty plan has nothing to save, but loaded configured services can still use
the separately reviewed runtime actions. Apply can also report a per-service write
failure; partial files are preserved for inspection, not silently deleted.

Extending a managed installation preserves the existing viewer port, saved-data
paths, and snapshot namespace. Setup pins the previous namespace as
`viewer.namespace` before extending the registry. It backs up the shared files
it updates. If the bundle contains a newer JAR, setup can stage a separate
versioned tooling JAR and update the exact generated viewer/check launchers;
customized launchers require manual review. Existing agent JARs and application
launchers are not upgraded or restarted by this operation.

The remaining commands are the **manual installation/reference path**, not extra
steps to layer over a generated setup. The examples below use
`/opt/tracelens`; replace it with the actual absolute extraction directory. Do
not assume that path exists or that administrator access is required.

```bash
java -jar /opt/tracelens/tracelens.jar --version
java -jar /opt/tracelens/tracelens.jar --help
```

## One configuration, two processes

The portable example derives from the existing
[`scripts/cb-app/tracelens.json`](../scripts/cb-app/tracelens.json). It keeps the
curated cb-app API/package scope (including `/api/v2/custom_pricing_units` for
callbacks), identity hooks, job-type allowlist, bounded
retention, and non-evicting exports. It adds this viewer section without embedding
any developer's checkout or home-directory path:

```json
{
  "viewer": {
    "upstream": "http://127.0.0.1:7778",
    "port": 7781,
    "viewerUrl": "http://127.0.0.1:7781"
  }
}
```

This is a section to merge into the full example, not a replacement for the
agent configuration. The agent reads the top-level capture settings and ignores
`viewer`; the tooling reads `viewer` and uses `applicationId` as the configured
service name when no service registry is supplied.

For another application, change `applicationId`, `packages`, `httpIncludePaths`,
and any application-specific hooks/job types before using the example. Do not
copy cb-app's identity hooks into an unrelated application. HTTP path filters
are literal backend path prefixes, not URLs or wildcard expressions. The example
does not capture every API or every application package.

The separate portable [EMS configuration](../scripts/java-bundle/ems/tracelens.json)
uses the same JAR. Its 29 explicit path prefixes form a bounded superset of the
known downstream EMS route families for the shipped cb-app scope, including
implemented internal entitlement/grant helper routes;
the EMS paths are **not** obtained by simply removing `/api/v2` from cb-app paths.

| EMS prefixes | Flow through cb-app |
| --- | --- |
| `/features`, `/feature_availability_metrics`, `/features_transfer` | Feature create/read/search/update, metrics and transfer checks. |
| `/custom_pricing_units` | Credit-unit lookup/search callbacks, including cb-app requests originating from GO or Ledger. |
| `/items`, `/entitlements`, `/entitlement_versions` | Item/plan entitlements, entitlement upserts and versioned reads. Item entitlements use `/items/{id}/item_entitlements`, not `/item_entitlements`. |
| `/subscriptions`, `/subscription_entitlements`, `/entitlement_overrides/validate`, `/customers`, `/charge_entitlements` | Subscription/customer entitlements, overrides, availability and one-off-charge operations. |
| `/subscription`, `/internal/effective_entitlements`, `/internal/meters` | Billing/usage summaries, effective-entitlement retrieval and estimates, including subscription/ramp-related helpers. Usage summary uses singular `/subscription/{id}/usage_summary`. |
| `/meters`, `/meteredFeatures`, `/metered_features`, `/usage_events`, `/failed_usage_events` | Meter lifecycle/preview operations and usage-event list/meta/chart helpers. Both metered-feature spellings are case-sensitive and required. |
| `/grant_configurations`, `/grant_configuration_versions`, `/grant_configuration_overrides/validate`, `/internal/effective_grant_configurations` | Prepaid-credit/grant-related subscription and estimate helpers. |
| `/admin/entitlement_settings`, `/admin/fam_manual_calc_requests`, `/admin/fam_auto_calc_requests`, `/admin/subscription_item_sync/summary`, `/admin/subscription_meter_entitlements/initial_sync` | Explicit helper endpoints used by the `/entitlements` UI; this does not enable all `/admin` paths. |

The source audit uses cb-app's `api/helper/ms_col_mapper/MicroserviceURLMapper.java`,
its `api/helper/configs/entitlements`, `configs/ubb`, and `configs/invoice_config`
clients, plus EMS's implemented `api/*Api.java` mappings. The portable-config
tests exercise the actual JSON through `HttpTraceRuntime`, including concrete
downstream requests, servlet contexts, HTTP methods and negative lookalike paths.
This validates capture selection, **not a live replay or proof of every business
branch**. Reconcile this list when downstream APIs change.

Entries match an exact path or slash-delimited descendants across HTTP methods;
they are not wildcard templates. They apply to incoming EMS requests, not to the
originating cb-app API: another caller using the same allowed EMS path can also
be captured. Unrelated health, sandbox and admin paths stay excluded.

Not every cb-app request goes to EMS. Catalogue CRUD can call **cb-plans**
(the `microservices.items` destination); `/api/v2/events` does not imply an EMS
`/events` endpoint, and usage ingestion can use a separate ingestion service.
This EMS configuration cannot capture those services' internals. They require
their own instrumentation and matching configurations, and correlation requires
the appropriate destination allowlist and viewer registration.

Keep each service's own `tracelens.json`: the EMS file uses `com.chargebee.ems`, omits cb-app identity
hooks, and points the viewer to the EMS debug port `7779`. It retains 100
segments, 100,000 events, and a 30-minute idle TTL with payload and scheduled-job
capture disabled. Reuse the existing JAR and restart EMS after changing its
capture settings; no application rebuild is required. Wider scope can use the
same retention budget faster.

The cb-app sample explicitly keeps:

- `capturePayloads: false`, `evictOnExport: false`, 200 retained segments,
  500,000 events, a 60-minute idle TTL, and 75% HTTP retention allocation.
- Executor propagation, method/exception recording, local messaging boundaries,
  and supported data-store client boundaries. General scheduled interception
  and legacy SQS propagation are disabled; the named Chargebee job types remain
  explicitly enabled.
- HTTP client observation and distributed tracing, with propagation permitted
  to the explicit local EMS (`8082`), Ledger (`9187`), and GO (`7087`)
  authorities in the file. This does not install services, attach their agents,
  or configure them in the viewer. For a strictly local-only setup, set `distributedTracingEnabled` to
  `false` and the destination allowlist to `[]`; HTTP client observation can
  remain enabled independently. Adding other destinations is an explicit
  operator decision, not an automatic discovery step.

`capturePayloads: false` is not a general PII filter. The cb-app identity hooks
can record user email/site identity; trace metadata, paths, exception messages,
and explicitly pinned source text can also be sensitive. Review evidence before
sharing it. See [capture controls and defaults](sharing-and-configuration.md#capture-controls-and-defaults).

The portable configs leave `captureJdbcQueries: false`. For local MySQL/PostgreSQL
debugging, enable it explicitly to see raw SQL, execution timing and calling
context. SQL literals are not sanitized. The separate `captureJdbcParameters`
opt-in captures bounded scalar bindings; result rows are not captured.
See [local SQL query capture](jdbc-query-capture.md) before enabling or
sharing query evidence.

### Viewer settings

| Setting | Meaning |
| --- | --- |
| `upstream` | Debug API base URL, initially `http://127.0.0.1:7778`; not the application's business port. |
| `port` | Viewer listener port, initially `7781`; the listener binds to loopback. |
| `sourceRoot` | Optional absolute local checkout path for source display in the one-service configuration. Omitted in the portable file. |
| `viewerUrl` | Optional HTTP(S) origin used in MCP graph links; no credentials, path, query, or fragment. It does not start the viewer. |
| `captureDir` | Optional absolute private directory for minimized CLI/MCP captures. Default: `~/.tracelens/captures`. |
| `snapshotDir` | Optional absolute private root for viewer snapshots. Default: `~/.tracelens/viewer-snapshots`, with a configuration-specific namespace beneath it. |
| `namespace` | Optional explicit snapshot namespace: 64 lowercase hexadecimal characters. Managed extensions preserve the previous namespace here; without it, the viewer derives one from its mode and service mappings. |
| `services` | Optional bounded service registry, replacing the one-service mapping. Each entry has `name`, `url`, and optional `sourceRoot`/`dependencyAliases`. |

JSON values are literal: `~`, `$HOME`, and other environment variables are not
expanded in `viewer.sourceRoot`, `viewer.captureDir`, or `viewer.snapshotDir`.
Use real absolute paths, not symlink aliases, for saved-evidence storage.
See [Java capture storage](java-capture-storage.md) for filesystem requirements.

Launch flags override matching viewer settings:
`--url` (alias `--base-url`), `--services` (alias `--services-file`),
`--source-root`, `--port`, `--capture-dir`, `--snapshot-dir`, and `--viewer-url`.
`--url` and `--services` cannot be supplied together. For multi-service source
display, configure each service's `sourceRoot`; a global source root is not a
replacement for per-service mappings.

The combined configuration uses the read-only federation path even when it
contains one service. Explicit `--url` selects single-service mode instead.
This distinction matters for recording controls and `metadata` below.

## Attach to the application

For an ordinary executable application:

```bash
java -javaagent:/opt/tracelens/tracelens.jar=standalone \
  -Dtracelens.configPath=/opt/tracelens/tracelens.json \
  -Dtracelens.port=7778 \
  -jar /absolute/path/application.jar
```

For cb-app/Tomcat, add these JVM options to its existing supported startup
configuration, preserving all unrelated options:

```text
-javaagent:/opt/tracelens/tracelens.jar=standalone
-Dtracelens.configPath=/opt/tracelens/tracelens.json
-Dtracelens.port=7778
```

Use the existing managed launcher integration when one is already installed;
replace its TraceLens JAR/config references rather than stacking a second agent
attachment. Do not add an adapter JAR/filter as part of this standalone setup.
Do not set agent options globally on unrelated JVMs, including the viewer JVM.
See [cb-app installation and migration](cb-app-integration.md).

`=standalone` and `-Dtracelens.configPath=...` are separate mechanisms.
`-javaagent:tracelens.jar=config=...` is not supported.
Changing the viewer's `upstream` does not change the agent's listener; if you
change `-Dtracelens.port`, update `viewer.upstream` to match. Agent capture
settings are loaded at application startup: restart that application JVM after
changing them. Restart the viewer/MCP process after changing its launch settings.

## Start the viewer

Optionally add an absolute `viewer.sourceRoot` to the same JSON, then run:

```bash
java -jar /opt/tracelens/tracelens.jar \
  --config /opt/tracelens/tracelens.json serve
```

Open [the local viewer](http://127.0.0.1:7781). The application agent must already
be running to show live evidence; offline saved evidence does not require it.
For a temporary checkout override:

```bash
java -jar /opt/tracelens/tracelens.jar \
  --config /opt/tracelens/tracelens.json \
  --source-root /absolute/path/chargebee-app serve
```

The viewer serves the bundled web UI; no separate frontend install is needed.
Source rendering reads the current configured checkout. It does not infer the
running build's Git revision, retrieve historical source, or establish that a
line in today's checkout matches an older capture. Saved source is available
only when it was explicitly included in that snapshot. Without a source root,
the combined-config/federated view remains usable but does not assume a checkout.
Legacy single-service `--url` render/MCP mode retains its
`TRACELENS_SOURCE_ROOT` or `~/work/cb-ems` fallback; set `--source-root` explicitly
when using it for cb-app.

The viewer and debug API are local development tools, not public authenticated
services. Keep them on loopback; do not expose them through an unrestricted
tunnel or reverse proxy.

## CLI and saved evidence

Run these from the extraction directory, or replace the filenames with absolute
paths:

```bash
java -jar tracelens.jar --config tracelens.json summary
java -jar tracelens.jar --config tracelens.json list
java -jar tracelens.jar --config tracelens.json doctor
java -jar tracelens.jar --config tracelens.json investigate --path /api/v2/features
java -jar tracelens.jar --config tracelens.json diagnose --path /api/v2/features
java -jar tracelens.jar --config tracelens.json find "auto:GET /api/v2/features"
```

`investigate` refuses ambiguous matches by default; add user/scenario/time
filters, select an exact retained `--trace-id`, or explicitly choose
`--selection latest`. Its exit status is `0` for a selected request and `2` for
an unresolved selection. `doctor` returns `1` when checks are not ready.

Use a retained local **segment UUID** for `export` and `render`, and a
**distributed trace ID** for `get-distributed`:

```text
java -jar tracelens.jar --config tracelens.json export SEGMENT_UUID
java -jar tracelens.jar --config tracelens.json render SEGMENT_UUID --no-source
java -jar tracelens.jar --config tracelens.json get-distributed TRACE_ID
java -jar tracelens.jar --config tracelens.json find-distributed --method GET --path /api/v2/features
java -jar tracelens.jar --config tracelens.json tail --interval 2
```

Replace the uppercase placeholders with IDs returned by discovery. `--service`
and `--instance` disambiguate segment exports when needed. Export/render
inspection is non-evicting. `render --full` disables repeat collapsing;
`--no-source` prevents source-line reads.

Explicit minimized captures survive application restarts and can be compared
offline:

```bash
java -jar tracelens.jar --config tracelens.json capture save features-before \
  --path /api/v2/features --selection latest --revision baseline
java -jar tracelens.jar --config tracelens.json capture list
java -jar tracelens.jar --config tracelens.json capture search Feature --category class --limit 50
```

After deliberately reproducing and saving `features-after`, compare:

```bash
java -jar tracelens.jar --config tracelens.json capture compare features-before features-after
```

Saved names can repeat; use capture IDs when a name is ambiguous. A revision is
an operator-supplied label, not verified runtime/Git identity. Comparisons show
observed differences, not dead-code proof, complete coverage, or a performance
regression verdict. Viewer snapshots and minimized CLI/MCP captures are
different stores with different data scopes.

For an intentional recording mutation, explicitly select one debug endpoint:

```text
java -jar tracelens.jar --url http://127.0.0.1:7778 metadata
java -jar tracelens.jar --url http://127.0.0.1:7778 enable
java -jar tracelens.jar --url http://127.0.0.1:7778 disable
java -jar tracelens.jar --url http://127.0.0.1:7778 reset [SEGMENT_UUID]
```

These are available commands, not setup steps to run indiscriminately. `reset`
removes live retained evidence; `enable`/`disable` change recording.
Federated/combined-config mode rejects these commands, including `metadata`.
MCP does not expose recording-control/reset tools.

<a id="mcp-without-python"></a>

## MCP

Configure your MCP client to launch the JAR. For clients accepting the common
`mcpServers` shape:

```json
{
  "mcpServers": {
    "tracelens": {
      "command": "java",
      "args": [
        "-jar",
        "/opt/tracelens/tracelens.jar",
        "--config",
        "/opt/tracelens/tracelens.json",
        "mcp"
      ]
    }
  }
}
```

Replace the paths and, if necessary, use the absolute path to your Java 21
executable. Do not pass `-javaagent` to this tooling process. Stdout is
newline-delimited JSON-RPC only; startup diagnostics and storage warnings go to
stderr. The server negotiates MCP `2025-06-18` or legacy `2024-11-05` and
advertises the same 22 tools (23 when the operator enables the opt-in
[`get_segment_sql`](jdbc-query-capture.md#sql-over-mcp-operator-opt-in)):

| Workflow | Tools |
| --- | --- |
| Setup/request selection | `tracelens_doctor`, `investigate_request`, `diagnose_capture_request` |
| Explicit local captures | `save_request_capture`, `list_request_captures`, `compare_request_captures`, `search_saved_captures` |
| Request discovery/graphs | `find_distributed_traces`, `get_distributed_trace`, `get_trace_visualization`, `show_trace_visualization` |
| Segment/error evidence | `get_segment_execution`, `list_error_groups`, `get_error_group` |
| Existing flow/action tools | `find_actions`, `get_action`, `list_flows`, `get_flow`, `get_flow_summary`, `get_flow_mermaid`, `search_flows`, `flow_status` |

All inspect retained/saved evidence; only `save_request_capture` writes a local
capture. MCP tools cannot choose arbitrary upstream URLs, source checkouts, or
storage directories. The optional MCP Apps resource is bundled and offline;
unsupported hosts retain text/Mermaid output. The execution model is capped at
500 nodes/32 KiB, with explicit omissions. Error-group lists return at most 50
groups; details return at most 20 whole member requests. Missing retained
evidence is never proof that execution did not happen.

## Optional multiple services

Guided setup prepares a registry for the selected cb-app/EMS/Plans/Ledger/GO
services. For a manual installation, register each service's agent debug
endpoint with an explicit registry such as:

```json
{
  "viewer": {
    "port": 7781,
    "viewerUrl": "http://127.0.0.1:7781",
    "services": [
      {
        "name": "cb-app",
        "url": "http://127.0.0.1:7778",
        "sourceRoot": "/absolute/path/chargebee-app"
      },
      {
        "name": "cb-ems",
        "url": "http://127.0.0.1:7779",
        "sourceRoot": "/absolute/path/cb-ems"
      }
    ]
  }
}
```

Those are example debug ports; launch each agent accordingly. The propagation
allowlist concerns the downstream **business HTTP address** such as `8082`,
not its debug port such as `7779`. A registry entry does not install an agent or
enable propagation. Omit unused services and source roots rather than leaving
fake paths. Offline services are reported as incomplete evidence, not silently
treated as healthy or never called. See [distributed tracing](distributed-tracing.md).

## Updates and storage limitations

Use `./setup.sh` → **Edit capture settings** to review changes to a recognized
guided installation. Saving is not a hot reload; use separately reviewed runtime
actions or the application's normal launcher to restart the affected JVM.
Preserve customized settings and saved evidence. Replacing a JAR on disk does
not upgrade a running agent or viewer. There is no automatic general agent
upgrade, uninstall, or migration of arbitrary manual installations.

Restart the viewer/MCP with the intended JAR and the same registry/store paths.
Run `doctor`, confirm agent identity, reproduce an allowed request, and inspect
the captured evidence. Connectivity alone is not flow coverage.

Use one compatible writer family per private capture/snapshot store; shared JSON
formats are not a cross-runtime locking guarantee. See
[storage/security details](java-capture-storage.md) for ownership and locking limits.
Without an explicit `viewer.namespace`, changing service mappings or viewer mode
can select a different snapshot namespace; it does not delete the old namespace.
Managed setup extensions preserve the previous namespace instead. Keep that
generated value when editing the registry.

The Java private stores require supported local POSIX ownership, permissions,
link-count, and atomic-filesystem operations. They fail closed on unsupported
providers; Windows/non-POSIX persistent storage is not claimed. Use a trusted
private hierarchy: NIO path checks do not eliminate hostile same-user
parent-directory replacement. Setup does not repair permissions or delete old
saved evidence automatically.

## Verification

The build checks Java 8 bytecode compatibility. A real Java 8 installation can
also run the delivered-artifact smoke tests:

```bash
./gradlew :tracelens-tool:test \
  :tracelens-agent:java8ToolSmoke \
  :tracelens-agent:java8Smoke
```

`java8ToolSmoke` launches the shaded JAR's help/version, stdio handshake, all
22 tool definitions, offline MCP App resource, and offline capture listing.
`java8Smoke` exercises the existing agent/bootstrap path. Neither substitutes
for reproducing a request on the intended application's JVM and supported
framework/client versions.
