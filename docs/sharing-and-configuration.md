# Sharing and configuring TraceLens

## Current Java bundle: guided local setup

Share the Java ZIP containing `tracelens.jar`, `setup.sh`, the portable cb-app
`tracelens.json`, and `ems/tracelens.json`. The same JAR runs the agent and, in a
separate Java process, setup/viewer/CLI/MCP. Recipients do not need to build
TraceLens or add application dependencies.

For a fresh TraceLens installation against normally working native macOS/Linux
apps, unpack the ZIP and run:

```sh
./setup.sh
# Or: java -jar tracelens.jar setup --port 7782 --no-browser
```

Setup uses an absolute 15-minute loopback-only, same-origin/token-authorized
session. Choose **Set up TraceLens** for a first installation and select cb-app,
EMS, Plans, Ledger or GO roots, API prefixes and ports. For later changes, choose
**Edit capture settings**, **Load services**, then a service editor or **Add service**.
Use the same recognized guided installation directory. Review field-level
before/after changes before saving. Choose Overview (`overview`), Methods
(`methods`), or Detailed (`detailed`) separately per service. Incoming
HTTP query parameters/bodies, JDBC SQL/bindings and native ClickHouse SQL/bindings
are independent opt-ins; bindings require their engine's query capture. The
generated service JSON owns the viewer registry, source roots and private store
paths; there is no separate `viewer.json`. It produces viewer/check scripts and
selected-service launch instructions, including a backed-up, opt-in cb-app
`CATALINA_OPTS` hook. EMS/Plans launchers are optional and require an explicit
built app JAR and Java 21 home; otherwise use generated VM-options files.
Ledger/GO use their existing application launchers with the supplied VM options.
UI middleware is not a setup option.

Setup/viewer require JDK 8+. cb-app retains Java 8 and its normal environment.
Unknown nonempty destinations or unrecognized cb-app hooks are refused. Capture
updates preserve unrelated/unknown JSON, source roots, ports and launcher bytes,
and back up overwritten configs under `setup-backups/<unique-id>/`. Config-only
changes need no app rebuild, but require a deliberate JVM restart to take effect.
Additions retain registry entries and the saved capture namespace. A failed
service does not block valid siblings; shared-file errors remain explicit.

Saving configuration never starts or restarts an application. After saving, or
loading unchanged settings, an optional separate review/confirmation can start
or restart explicit Tomcat/executable-JAR profiles (`type`, `javaHome`,
`applicationPath`, `workingDirectory`, `applicationPort`, and JAR `arguments`).
A private persistent supervisor retains the child process handle across dashboard
reruns. Existing IDE/custom processes are not adopted: stop them normally before
managed Start. Both application and agent ports are checked; graceful stop waits
20 seconds without force-killing, and startup waits up to three minutes for the
agent connection, not application health. Results are independent per service.

The supervisor inherits the setup terminal's environment on first launch and
retains that environment for its restarts. There is no credential editor,
persisted environment snapshot, application build, dependency installation or
IDE-settings import. Avoid secrets in saved arguments. Private
`managed-services/<service-key>/application.log` and `supervisor.log` may contain
sensitive application output. Manual launcher bytes and old agent paths remain
unchanged; select an updated agent bundle explicitly for new modes such as
`methods`. Managed profiles use a private versioned current agent copy.
Run normal application-health checks and reproduce an allowed request through
the intended entry service before claiming a captured flow. Closing/expiring the
dashboard does not stop managed services. Windows is not supported by guided setup.
See the [guided setup walkthrough](guided-setup.md) and
[manual Java distribution reference](java-distribution.md).

Keep saved evidence private. Do not run Java and legacy tooling writers
concurrently against the same saved-data directories; see
[storage details](java-capture-storage.md).

## Using a supplied JAR (no TraceLens checkout)

Use the Java ZIP and `./setup.sh` for a first installation; follow the UI and
return to [guided setup](guided-setup.md) only for details or troubleshooting.
There is one combined JAR for all supported services, not one JAR per application
or a separate viewer package. Each traced JVM uses its own service configuration
and debug port. The application still needs its normal runtime, dependencies,
credentials and test data.

For manual attachment, use the
[Java distribution reference](java-distribution.md#attach-to-the-application).
Do not layer manual startup hooks over a guided installation or attach the agent
twice. Generic standalone mode can omit JSON, but use one service-owned JSON for
repeatable API, package, retention and payload settings. A viewer registry entry
alone neither installs an agent nor enables cross-service correlation.

The same artifact supports the tested Java 8 / Tomcat 7 (`javax.servlet`) and
Java 21 / Tomcat 10 (`jakarta.servlet`) setups. Other runtime/client versions need
compatibility verification; attachment is not universal HTTP instrumentation.
See the [compatibility boundary](standalone-agent.md#compatibility-boundary).

## Local setup from a checkout

This is a maintainer/manual reference, not a prerequisite for using the ZIP.
Build the current distribution with JDK 21:

```bash
./gradlew :tracelens-agent:javaBundle
```

Distribute `build/distributions/tracelens-java-0.1.1-SNAPSHOT.zip`; recipients
unpack it and run `./setup.sh`. Existing installations of the optional cb-app
`make up-tracelens` integration can continue using its
[separate lifecycle reference](cb-app-integration.md#managed-setup-with-make-up-tracelens),
but should not layer it over a guided startup hook. It builds and runs normal
application lifecycle commands with different side effects; it is not the
configuration dashboard.

The lower-level standalone options below remain available for deliberate manual
integrations. They are not extra steps after guided setup.

### 1. Build or obtain the agent

```bash
cd ~/work/cb-tracelens

./gradlew :tracelens-agent:shadowJar
```

`~/work/...` paths in this guide are examples, not installation requirements.
This builds the shaded artifact (the ZIP ships the same artifact as `tracelens.jar`):

```text
tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar
```

Build with JDK 21, or use a supplied built jar on the supported target JDK. No
`publishToMavenLocal`, Maven repository change or application rebuild is needed
for standalone installation. Do not use the unclassified, thin agent jar with
`-javaagent`.

### 2. Attach it at startup

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
  -Dtracelens.applicationId=my-service \
  -Dtracelens.packages=com.example.service \
  -jar service.jar
```

Use your launcher's JVM options for applications that do not launch with `java
-jar`. For example, Tomcat startup scripts accept `CATALINA_OPTS`. Restart the
application to attach the agent; live attachment is not the tested installation
path. `-Dtracelens.standalone=true` is equivalent to `=standalone`.

`tracelens.packages` accepts comma-separated application packages. Omitting it
and the JSON config leaves HTTP capture at metadata level, without application
line events; it does not weave every dependency. Add a narrow package scope for
executed source-line markers. Compiled classes need line-number information;
reaching a marker is not proof that every operation on that line completed.

The agent's debug API uses `http://127.0.0.1:7778`, independent of the application's
HTTP port. Give each traced JVM a different debug port, for example
`-Dtracelens.port=7779` for a second service. A busy debug port disables the
standalone runtime with a startup message while the application continues.

#### Service identity

Without a JSON file, the agent's current source defaults `applicationId` to
`cb-app`; other applications should set `-Dtracelens.applicationId` explicitly.
With a JSON file, `applicationId` is required unless supplied by that system
property, which overrides the file. A changed source default does not rename an
explicit identity, an existing jar, a running JVM or older captures.

The optional legacy Spring starter, when synthesizing configuration without a
JSON file, uses explicit `tracelens.application-id`, then
`spring.application.name`, then `cb-app`. The standalone agent does not read
Spring's application properties. The cb-app repository and lifecycle service are
named `chargebee-app`; its bundled TraceLens config and registry use `cb-app`.
See the [cb-app integration guide](cb-app-integration.md), including managed
`make up-tracelens`, the older optional `Makefile.tracelens` wrapper and lifecycle
caveats.

### 3. Optionally share `tracelens.json`

Use a JSON file when the team needs shared instrumentation and retention settings,
for example `local-setup/tracelens.json`:

```json
{
  "applicationId": "cb-ems",
  "applicationVersion": "LOCAL",
  "packages": ["com.chargebee.ems"],
  "httpIncludePaths": ["/api/orders", "/api/invoices"],
  "excludedPackages": ["com.chargebee.ems.api.model"],
  "blacklistedNodes": ["ToStringBuilder"],

  "capturePayloads": false,
  "maxTrackedEvents": 500000,
  "maxTrackedRequests": 200,
  "httpRetentionPercent": 75,
  "idleTtlMinutes": 60,
  "evictOnExport": false,

  "trackMethods": true,
  "trackExceptions": true,
  "instrumentExecutors": true,
  "instrumentMessaging": false,
  "captureMessagingBoundaries": true,
  "instrumentScheduled": true,

  "requireLabels": false,
  "httpClientCaptureEnabled": true,
  "distributedTracingEnabled": false,
  "httpPropagationAllowedDestinations": []
}
```

[`scripts/cb-ems/tracelens.json`](../scripts/cb-ems/tracelens.json) is a fuller
`cb-ems` example. The example above chooses local HTTP/messaging observation
without adding trace headers or SQS attributes. The development EMS config instead
enables distributed HTTP context, JDBC SQL/bindings and broader API scope. It is
not the portable [bundle template](../scripts/java-bundle/ems/tracelens.json),
which keeps query capture opt-in and a curated API scope. Review the selected
configuration rather than assuming these examples are interchangeable.

Select the file at startup. When provided, it replaces the minimal standalone
package configuration. `-Dtracelens.applicationId` and
`-Dtracelens.applicationVersion` still override the corresponding JSON fields;
`-Dtracelens.packages` does not override a selected file:

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
  -Dtracelens.configPath=/absolute/path/to/local-setup/tracelens.json \
  -jar service.jar
```

Start with a narrow package scope and select HTTP entry paths with
`httpIncludePaths` in the same JSON. The paths above are illustrative; use the
actual APIs of your service. A literal prefix matches the path or its descendants,
not wildcard syntax. Omission defaults to `["/"]`; explicit `[]` disables retained
HTTP entry capture. Built-in noise exclusions still apply. This setting does not
disable separate scheduled-job or messaging hooks or incoming/outgoing
distributed context. Instrumenting an entire service may suit a controlled local
session, but is not necessarily a shared-environment default. Config changes need
an application JVM restart; neither setup reruns nor viewer reloads hot-reload it.

### Capture controls and defaults

These are code defaults, not promises about the scope of a particular service's
sample configuration. Changes to these settings require a JVM restart.

| JSON setting | Default when omitted | Effect and boundary |
| --- | --- | --- |
| `httpIncludePaths` | `["/"]` | Retain eligible HTTP entries matching a literal path or descendant; `[]` disables retained HTTP entries. Built-in ignored paths remain excluded. Edit per service in the dashboard or selected JSON and restart the JVM. |
| `distributedTracingEnabled` | `false` | Incoming W3C HTTP context and permission for guarded outbound correlation, not universal network capture. |
| `httpClientCaptureEnabled` | Follows `distributedTracingEnabled` | Explicit `true` observes supported JDK HTTP calls under an active trace, including uninstrumented or non-allowlisted receivers. `false` disables new client observations/injection. |
| `httpPropagationAllowedDestinations` | `[]` | Exact trusted host/port authorities for injecting headers. Observation does not expand this list. |
| `instrumentMessaging` | `true` | Existing single-message SQS attribute propagation / Queueman continuation, not Kafka or arbitrary queue propagation. |
| `captureMessagingBoundaries` | Follows `instrumentMessaging` | Metadata-only observation for supported SQS, Kafka and Queueman boundaries. Set `true` with `instrumentMessaging: false` for capture-only operation. |
| `captureDataStoreBoundaries` | `true` | Known ClickHouse, Spring Data Redis/Valkey, AWS v1 DynamoDB and S3 client operations under an active trace. Metadata only; no SQL, keys, values, destinations or credentials. Async submission/synchronous return does not prove remote completion. See [coverage](dependency-visibility-and-change-impact.md). |
| `captureJdbcQueries` | `false` | Local opt-in PostgreSQL/MySQL JDBC execution capture. Raw SQL literals are not sanitized; result rows, connection credentials and JDBC exception messages are excluded. Independent of `capturePayloads` and `captureDataStoreBoundaries`. Requires an updated agent and a JVM restart. See [SQL capture](jdbc-query-capture.md). |
| `captureJdbcParameters` | `false` | Bounded supported positional bindings; effective only with JDBC query capture. Unsupported/partial values stay explicit rather than guessed. |
| `captureClickHouseQueries`, `captureClickHouseParameters` | `false`, `false` | Native ClickHouse SQL and optional named bindings, independent of JDBC switches. No result rows or streaming-insert data. See [native capture](request-data-and-clickhouse.md). |
| `captureHttpQueryParameters`, `captureHttpRequestBodies` | `false`, `false` | Incoming servlet query parameters and supported Tomcat JSON/form bodies read by the application. Independent of capture depth and the legacy payload flag; no header/response capture. |
| `redactHttpPayloads`, `maxHttpPayloadLength` | `true`, `4096` | Best-effort known-sensitive-field redaction and bounded request data; explicit local raw mode is available. Not a universal privacy sanitizer. |
| `maxSqlLength`, `maxSqlQueriesPerRequest` | `4096`, `100` | SQL characters per event (1–16384) and executions per service segment (1–1000), plus a limit notice. Existing event/segment retention limits still apply. |
| `instrumentExecutors` | `true` | Supported JDK thread-pool hand-offs, not every async/reactive framework. |
| `instrumentScheduled` | `true` | Spring `@Scheduled` methods in the configured package scope, not every scheduler. |
| `chargebeeJobTypes` | `[]` | Exact runtime job-type names for Chargebee `AppJobExecutor` dispatches, e.g. `["compute_billing_metrics"]`; empty disables custom-job capture. Independent of `instrumentScheduled` and HTTP path filters. Each selected dispatch starts a separate scheduler root; source evidence still needs configured packages. Requires an updated agent build and JVM restart. See [custom-job outcomes and limits](cb-app-integration.md#capture-a-chargebee-custom-scheduled-job). |
| `trackMethods`, `trackExceptions` | `true` | Independent method/exception event switches within instrumented scope. Choosing `methods` or `detailed` does not silently turn a disabled `trackMethods` back on. |
| `captureMode` | `detailed` | `overview` retains configured dependency/error observations without method/line events; `methods` adds method entry/exit but no source-line events; `detailed` adds lines too. HTTP evidence and SQL remain separate opt-ins. Callbacks still execute; this is not zero-overhead tracing. |
| `detailedCapturePaths` | `[]` | Up to 32 literal HTTP path prefixes, each at most 512 characters. Nonempty scope falls back to overview outside exact/descendant matches. Matching paths use methods in `methods` mode, otherwise detailed (including an Overview-to-Detailed scoped override). Empty uses `captureMode`. No wildcards, query strings or whitespace. |
| `detailedCaptureUntilEpochMillis` | `0` | Absolute epoch-millisecond cutoff, not a duration from startup; `0` has no expiry. After expiry all depth modes/path overrides fall back to overview. Omitted detail cannot be backfilled. |
| `capturePayloads` | `false` | Legacy switch; does not enable standalone request-data capture. Use the separate HTTP query/body settings above. Response bodies are not captured. |
| `requireLabels` | `false` | Require a nonblank label and user at HTTP entry; not a global scheduler/queue capture gate or access control. |
| `maxTrackedRequests`, `maxTrackedEvents`, `idleTtlMinutes` | `200`, `500000`, `60` | Bounded JVM retention, not durable history. |
| `maxSegmentBytes`, `maxTrackedBytes` | `33554432`, `268435456` | Conservative estimated retained-data limits per segment and process, not measured JVM heap. Segment range 1–256 MiB; global limit must be at least the segment limit and at most 2 GiB. Metadata and dependency/error capacity are reserved. |
| `httpRetentionPercent` | `75` | Protects HTTP from scheduler/consumer churn. Borrowable segment slots, fixed event budgets: at defaults 150 HTTP / 50 background slots and 375000 / 125000 events. `0` restores shared retention; `1`–`99` reserves both channels. Same-channel churn and idle expiry still apply. Restart with the rebuilt agent to activate. |
| `evictOnExport` | `true` for JSON; `false` for standalone fallback | Raw export policy. CLI/viewer/MCP reads explicitly use `evict=false`. |

For the legacy Spring starter without a JSON config file, the equivalent setting
is `tracelens.http-retention-percent: 75`. JSON config takes precedence when a
config path is supplied. The summary endpoint exposes the effective
`httpRetentionPercent` and a `retention` object containing channel counts, budgets
and eviction/drop counters. An older running agent will not expose these fields;
refreshing the browser alone cannot activate a newly built agent policy.

For a bounded investigation, choose methods or detailed, add only the target
HTTP prefixes to `detailedCapturePaths`, and set an explicit future expiry before
restarting the application. Overview with matching scoped paths promotes those
requests to Detailed. Outside the path scope or after expiry, depth falls back to
overview; independent HTTP/SQL evidence switches are not reset. Mode choices and
budgets are local to each service;
configure downstream services too if their method detail is needed. These
controls do not change HTTP admission, trusted propagation destinations or SQL
capture opt-ins. Missing detail stays explicit in `dropReasons` and
`omittedDetailCallbacks`; it must not be interpreted as an unexecuted path.

HTTP propagation additionally requires valid context, no conflicting existing
headers and the tested redirect guard. Metadata observation can still work when
the guard is unavailable. A captured send is not delivery confirmation, Kafka
poll needs an active trace, and matching queue/topic names does not prove a
producer/consumer link. See [distributed tracing](distributed-tracing.md) and
[dependency coverage](dependency-visibility-and-change-impact.md).

<a id="4-install-the-cliviewermcp"></a>

### 4. Run the viewer, CLI or MCP

These tools are already included in `tracelens.jar`; no separate package install
is needed. Guided setup produces `start-viewer.sh` and `check-setup.sh`. For a
manual installation, replace the example paths and run:

```bash
java -jar /absolute/path/to/tracelens.jar --version
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 doctor --source-root /absolute/path/to/service
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 serve --port 7781 --source-root /absolute/path/to/service
```

Open [the viewer](http://127.0.0.1:7781) and keep that viewer process running.
Use the generated multi-service configuration instead of the single-service
example when guided setup manages your registry.

| Endpoint | Example port | Owner |
| --- | --- | --- |
| Application HTTP | `8080` for cb-app; `8082` for EMS | Your application; use normal authentication. |
| Agent debug API | `7778` for cb-app; `7779` for EMS | One standalone agent per JVM. |
| Browser viewer | `7781` | Separate Java viewer on loopback. |
| MCP | No listening port | Separate Java stdio process launched by its client. |

<a id="refresh-an-installed-global-cli"></a>

### Update tooling or capture settings

Do not assume a command named `tracelens` uses the supplied bundle. Run
`java -jar /absolute/path/to/tracelens.jar --version` and `--help` explicitly.
For guided installations, rerun the current bundle's setup script and load the
same installation before reviewing updates. Existing application launchers and
agent paths are not silently replaced; follow the
[update boundaries](guided-setup.md#later-changes-and-manual-setups).

A tooling-only update requires a viewer restart/MCP reconnect using the intended
JAR and existing registry/store paths, not an application restart. Agent or
capture-policy changes require restarting the affected application JVM. Preserve
saved evidence and customized settings; a different executable or browser refresh
does not update an already-running process.

## Verify capture

First, verify wiring and agent scope:

```bash
curl http://127.0.0.1:7778/tracelens/summary
```

The useful fields are:

```text
mode: standalone
recordingEnabled: true
agentActive: true
bridge.sinkRegistered: true
bridge.httpListenerRegistered: true
```

`lineCaptureEnabled` reports whether a package scope is configured.
`instrumentedClasses` can remain zero until application classes load; inspect it
after a request through the configured code. `bridge.capturedRequests` should
increase after a supported HTTP request is captured. These counters distinguish
successful attachment from actual end-to-end capture.

Make a normal authenticated request to the application's port. TraceLens creates
labels such as `auto:GET /some-endpoint`, so knowing the API is enough to find the
request. You can also label a reproduction explicitly:

```bash
curl \
  -H 'tracelens-id: CRI-123' \
  -H 'tracelens-user: engineer@chargebee.com' \
  http://localhost:8082/some-endpoint
```

Find and render the retained segment:

```bash
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 find CRI-123
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 find 'auto:GET /some-endpoint'
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 render <segment-id> --source-root /absolute/path/to/service
```

The example application request uses port `8082`; replace it with your service's
port. CLI and MCP use the separate agent port. `X-TraceLens-Id` normally appears on
captured responses, but API-label lookup also works when a container-generated
error response clears that header.

## MCP configuration

Run the MCP process locally:

```bash
java -jar /absolute/path/to/tracelens.jar --url http://127.0.0.1:7778 mcp --source-root /absolute/path/to/service
```

Configure the equivalent command in your stdio MCP client. For clients that
accept an `mcpServers` JSON object, the example is:

```json
{
  "mcpServers": {
    "tracelens": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/tracelens.jar",
        "--url",
        "http://127.0.0.1:7778",
        "mcp",
        "--source-root",
        "/absolute/path/to/service"
      ]
    }
  }
}
```

Replace the paths and adapt the configuration format to your client. The agent
does not require an IDE-specific plugin; MCP is a separate local reader process,
and browser rendering of optional MCP Apps depends on the client's capabilities.

The MCP client can then use `flow_status`, `search_flows`, `get_flow_summary`,
`get_segment_execution`, and `get_flow` as runtime context while it examines source code.
For example: “Find the latest GET /some-endpoint request and explain what code
ran.” MCP reads use `evict=false`; retained traces still expire or are evicted
when the in-memory limits are reached. Live agent evidence does not survive a JVM
restart. Full viewer snapshots, legacy browser pins and minimized CLI captures
are separate stores. MCP reads the minimized capture store, not raw viewer files
or browser pins. **Compare & search saved captures → Use with CLI/MCP** explicitly
creates a minimized copy of a selected viewer snapshot; use its new capture ID.

For an on-demand method diagram, ask it to discover an exact segment UUID and
visualize `get_segment_execution(segment_id, service, instance)`; this adds no
renderer to the application. Single-service setups omit `service` (or use
`default`); combined setups use a configured alias. See
[execution evidence and size limits](hybrid-visualization.md#mcp-first-segment-execution-visualization).
Reconnect the MCP process after upgrading to discover this tool; do not restart
the instrumented application just for this MCP-side change.
`get_trace_visualization` returns graph data; `show_trace_visualization` requests
an MCP Apps presentation with text/Mermaid/viewer fallback. This describes the
server's implemented protocol, not a guarantee about any particular client's
current app-rendering support. The optional
`mcp --viewer-url http://127.0.0.1:7781` supplies a viewer link; start the matching
`serve` process separately, because that flag does not launch one.

## Optional legacy application-adapter setup

Existing installations may keep an application-owned store and debug API. This
path requires an application dependency and rebuild, and is separate from the
recommended standalone setup. Do not enable both stores in one JVM.

For a Spring Boot service using the existing Java 21 starter:

```bash
cd ~/work/cb-tracelens
./gradlew :tracelens-agent:shadowJar publishToMavenLocal
```

Add the locally published starter to the service build:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    runtimeOnly "com.chargebee:tracelens-spring-boot-starter:0.1.1-SNAPSHOT"
}
```

Enable it in the local/dev profile:

```yaml
tracelens:
  enabled: true
  config-path: /absolute/path/to/local-setup/tracelens.json
```

Attach the agent **without** `=standalone` and give it the same config file:

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar \
  -Dtracelens.configPath=/absolute/path/to/local-setup/tracelens.json \
  -jar service.jar
```

For this legacy path, point CLI/MCP at the application's HTTP port, such as
`http://localhost:8082`, and protect the application-hosted `/tracelens/*` routes.
The [cb-app guide](cb-app-integration.md) covers the older javax adapter and
migration to standalone mode.

The updated Spring and javax adapters back off when standalone mode is selected.
If an older adapter is installed, remove its registration/dependency or disable
its legacy `tracelens.enabled` setting before attaching in standalone mode.
The current EMS launch helper is standalone, not an example of this legacy path.

## Recommended distribution model

### Early pilot

Start with the [focused developer workflow](developer-workflow.md): `doctor`,
exact-path `diagnose`, API/scenario investigation, explicit before/after captures
and offline saved-evidence search. The viewer adds guided next-request saving,
offline comparison and execution search/focus. Reconnect the MCP
process after upgrading to discover the current workflow tools. The CLI/MCP file
store defaults to `~/.tracelens/captures`; use the global `--capture-dir` launch
option to choose a private directory shared by those local reader processes.
If using the viewer's explicit **Use with CLI/MCP** action, start `serve` with
that same global `--capture-dir`; `serve --snapshot-dir` selects a different,
full-snapshot store. Browser pins remain browser-local. Evaluate the POC using the
[productivity pilot scorecard](productivity-pilot.md), not feature count.

```text
Private repository
  + release notes
  + checked-in sample configuration
  + service integration guide
  + local/development use only
```

### Team adoption

```text
Private versioned Java setup ZIP
  ├─ tracelens.jar (agent + setup + viewer + CLI + MCP)
  ├─ setup.sh
  ├─ tracelens.json
  └─ ems/tracelens.json
```

A service can use startup properties for a local trial. For a repeatable team
configuration, keep a small service-owned `tracelens.json` with strict labels and
a narrow instrumentation scope in shared environments.

## Shared-environment safeguards

Before enabling TraceLens in predev or production-like environments:

- Set `requireLabels=true` to gate HTTP capture on a supplied label/user. This is
  not authorization and does not gate all scheduler/Queueman lifecycle traces;
  separately scope or disable unwanted background instrumentation.
- Keep request query/body, JDBC bindings and native ClickHouse bindings disabled
  unless explicitly reviewed. SQL text itself can contain literals;
  `capturePayloads=false` does not sanitize these independent capture options.
- Keep the standalone debug API on direct loopback access; do not expose it through
  a reverse proxy. Protect or omit application-hosted debug endpoints in legacy mode.
- Use conservative retention caps and a short TTL.
- Set `applicationVersion` to a reproducible build/version or commit label;
  automatic Git checkout matching is not provided.
- Maintain a compatibility matrix for agent, JDK, and framework versions.
- Do not enable unrestricted, always-on line-level capture in production.

Standalone mode can record incoming query values and supported request-body reads
when enabled, in addition to optional SQL text/bindings. Headers, cookies and
responses are not captured by those options. The in-JVM store is not durable;
explicit saved captures use private viewer storage. Neither provides central
access control or a production rollout by itself. Automated artifact publishing and a maintained
compatibility matrix remain distribution work.
User/site labels, paths, exception/source views and queue/topic identifiers can
still be sensitive; existing access and AI-data policies apply to exports and MCP.

After an upgrade, restart affected application JVMs for agent/config changes;
no application rebuild is needed for standalone capture changes. Restart the
viewer/reconnect MCP for tooling-JAR changes, and reload browser assets.
Export or successfully save important snapshots first. These operations do not
recover events that were absent from earlier captures. Instrumentation, event
allocation, export and rendering have overhead; representative performance and
production-safety verification remain deployment work.
