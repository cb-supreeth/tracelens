# TraceLens

TraceLens is a local-first execution tracer and runtime-context provider for Java
services. Guided local setup supports `cb-app`, `cb-ems`, `cb-plans`,
`cb-consumption-ledger` and `cb-grant-orchestrator`, independently or together.
A Java agent records observed methods, source-line markers and supported external
boundaries inside the service JVM. The same shaded JAR runs the
CLI/viewer/MCP in a **separate Java process**, reading retained evidence through
a local debug API. Share one combined JAR with the setup script and service
configuration templates; no TraceLens source checkout is needed to use the bundle.

> For this reproduction, which code ran, on which recorded threads, and which
> supported services, queues or database routes did it touch?

TraceLens complements KVL logs and APM tools; it does not replace their durable
production record. It is not a production-ready APM platform or a guarantee of
complete execution coverage.

**Feature overview updated against repository source: 2026-10-28.** Editing or
building this repo does not upgrade already-running application JVMs. This is a
source-level capability review, not certification of a deployed build or its
production overhead.

## Start here

- [Guided local setup](docs/guided-setup.md): unpack the Java ZIP, run
  `sh setup.sh`, then choose **Set up TraceLens** or **Edit capture settings**.
  First-time setup stays short; the compact dashboard offers per-service
  **Edit capture settings** and **Add service**, with advanced settings collapsed and runtime
  start/restart separately reviewed and confirmed.
- [Java distribution reference](docs/java-distribution.md): one combined JAR,
  portable service configs, manual attachment and Java viewer/CLI/MCP commands.
- [How to use TraceLens](docs/how-to-use.md): short first-run guide for developers—
  setup, find a request, ask an assistant and compare before/after runs.
- [Focused developer workflow](docs/developer-workflow.md): diagnose setup, find a
  reproduced API, save before/after evidence, compare it in the viewer or CLI/MCP,
  and search the observed execution.
- [Cursor/Codex impact-analysis skill](docs/impact-analysis-skill.md): combine local
  code/tests, TraceLens evidence and Chargebee documentation to identify potential
  effects and propose regression tests, with explicit coverage limits.
- [Productivity pilot](docs/productivity-pilot.md): validate time saved and adoption
  before expanding the POC; proposed targets, not measured results.
- [Use-case catalog](docs/use-cases.md): supported workflows, manual investigations,
  prerequisites, limitations and roadmap ideas.
- [Documentation index](docs/README.md): reading paths and every detailed guide.
- [Standalone setup](docs/standalone-agent.md): recommended agent-only installation.
- [cb-app setup with make up-tracelens](docs/cb-app-integration.md#managed-setup-with-make-up-tracelens):
  build/setup and opt-in startup using cb-app's existing lifecycle.
- [Codebase walkthrough](docs/codebase-walkthrough.md) and
  [Byte Buddy internals](docs/byte-buddy-instrumentation.md): how capture works.
- [Dependency visibility and change impact](docs/dependency-visibility-and-change-impact.md):
  external calls without receiver agents and realistic impact investigations.

## Current architecture and tech stack

```text
Target service JVM
  agent bytecode hooks → bootstrap bridge / thread context → bounded Java store
                                                             ↓ local debug API
Separate Java CLI / viewer / stdio MCP process (same JAR, different JVM)
  → request discovery, evidence models, source lookup
  → browser UI or an MCP client such as Cursor/Codex
```

Callbacks execute in the target JVM; they are not network calls to the viewer.
Exporting evidence from the debug API is a separate read operation.

| Layer | Implementation |
| --- | --- |
| Capture | Java instrumentation API, Byte Buddy and its shaded ASM visitor API |
| Runtime | Bootstrap bridge, thread-local context, bounded in-memory store, Jackson export |
| Standalone debug API | JDK HTTP server on loopback |
| Viewer/MCP | Java 8+ tooling, JDK HTTP server, Jackson, stdio MCP |
| Browser | Plain HTML/CSS/JavaScript and SVG; no frontend framework/chart dependency |
| Build/tests | Gradle/Shadow, JUnit/AssertJ and Node's built-in test runner; pytest for the cb-app bootstrap scripts |

The combined artifact version is `0.1.1-SNAPSHOT`. A version label alone does not
prove which build an existing JVM is running.

## Navigate the viewer

- **Requests:** retained live requests, scheduled jobs and error groups. Narrow
  by API/time/service, or explicitly search captured request inputs.
- **Saved captures:** reopen evidence, select **Before** then **After** for
  **Compare selected**, or search saved evidence without a live service.
- **Performance:** analyze captured JDBC queries on demand; inspect individual
  occurrences and their calling context.
- **Services & capture settings:** inspect connectivity and capture readiness;
  use the setup dashboard for reviewed configuration changes.

Request graphs open fitted with HTTP hops hidden. Select a compact card to read
request/query data or open code in its adjacent inspector. Zoom, branch focus
and the minimap help with larger flows. The [demo guide](docs/demo-guide.md)
links nine retained local examples; its localhost links work only on that machine.

## What works today

- Three retained-detail levels: **Overview**, **Methods**, and **Detailed**
  (`overview`, `methods`, `detailed`), with independent dependency/request/SQL opt-ins.
  Supported exception capture is configured separately.
- Supported servlet request capture, identity hooks and executor context continuation.
- Scoped Spring scheduled jobs, opt-in Chargebee custom-job dispatches and Spring
  database-routing markers (distinct from SQL execution).
- Opt-in MySQL/PostgreSQL SQL execution capture, with a separate opt-in for
  bounded parameter values. Grouped **DB CALL** cards show operation/table titles,
  timing, failures and recorded calling code. **SQL with captured values** is a
  reconstructed preview; original templates and typed bindings remain available,
  and uncertain/truncated values stay `?`. See [SQL capture](docs/jdbc-query-capture.md).
- Opt-in HTTP correlation across configured services; each service also works alone.
- Caller-side HTTP and SQS/Kafka/Queueman boundary observations, even when a receiver
  has no agent. Capture and propagation are separately configurable.
- Compact, automatically fitted service diagrams with optional HTTP-hop detail,
  captured call-order labels, per-invocation service frames and recorded caller
  navigation. Decimal labels require positive caller-local overlap evidence;
  unknown order stays `?`, not a fabricated global timeline.
- Distinct cache, queue and database cards; native ClickHouse SQL/named-parameter
  capture has separate opt-ins. Redis-compatible client evidence does not by
  itself distinguish Redis from Valkey or establish a cache hit.
- Optional incoming API query/body capture, a **Request data** section in the
  selected invocation's inspector, clipboard controls and explicit bounded request-data
  keyword search. SQL and request-body capture are independent controls.
- Request-focused filtering, repeated-API grouping, error groups and saved views.
- [Performance insights](docs/performance-insights.md): explicitly analyze up to
  ten recent captured requests or one saved capture for slow JDBC calls, repeated
  SQL, suspected N+1 and query-heavy segments. No background monitoring; findings
  are investigation leads, not confirmed bottlenecks or traffic-wide metrics.
- Explicit viewer-file snapshots with stable links, file exports and request-flow
  diagrams; existing browser-only pins remain accessible.
- MCP discovery, summaries, source-aware timelines and bounded execution models
  for client-generated visualizations.
- Read-only setup diagnostics and ambiguity-safe API/scenario investigation.
- Guided exact-path capture diagnosis and a bounded, explicitly armed next-request
  watch that saves an unambiguous completed request; no automatic API replay.
- Behavior-first saved comparison: before/after outcomes, external calls,
  parent-grouped method differences and links back to exact saved evidence.
  Raw method/line/nesting differences remain available. Configured service labels
  retain the recorded address and do not establish remote execution.
- Bounded saved-evidence lookup, execution search/branch focus, and explicit local
  editor links with current-checkout warnings.

These are **observed evidence**, not a complete call graph. A wrapper entry does
not prove network success; a queue submission does not prove delivery. See the
[client coverage matrix](docs/dependency-visibility-and-change-impact.md#implemented-coverage).
JDK HTTP/HTTPS and synchronous Apache HttpClient 4 are supported at documented
seams. Apache 5/async, OkHttp, arbitrary sockets and other unsupported clients
are not automatically covered.

### Storage and identity

The bundled TraceLens application ID and registry service name are **`cb-app`**;
use `--service cb-app` with the supplied registry. **`chargebee-app`** is the
repository directory and service-orchestrator name, so checkout paths and
startup commands retain that name. These are two names for the same application,
not two services. The no-JSON agent fallback is `cb-app`; explicit application
IDs still win, and the Spring starter also prefers `spring.application.name`
over its final fallback. Other applications should set their own identity.

Agent data lives in bounded JVM memory and can disappear on restart, eviction or
reset. There is **no database or automatic remote collector**. **Save snapshot**
explicitly stores the available viewer evidence in private files under
`~/.tracelens/viewer-snapshots/<namespace>/`; `serve --snapshot-dir /path/to/store`
overrides the root. **Saved captures → Copy snapshot link** produces a stable
`#snapshot=UUID` link that works across browsers on the same machine/viewer
configuration, even after services restart or live evidence expires. Save while
all required segments are still available. New captures use bounded compressed
chunks and support ZIP download/import; older single-JSON snapshots remain
readable. Raw storage and bounded graph/comparison projections have separate
limits; saving more events does not imply every view analyzes every event.
See [storage formats and quotas](docs/java-capture-storage.md). New saves are
rejected at a quota, never silently evicted.

These files include saved events and resolved source lines and may be sensitive.
They are not a shared production history service. Existing browser-only pins stay
in IndexedDB until explicitly copied with **Save to viewer**; that action keeps
the original. Saved filter views remain browser-local. The separate CLI/MCP
capture store uses summarized JSON in `~/.tracelens/captures` (overridable with
`--capture-dir`) for offline comparisons. **Compare & search saved captures**
compares viewer snapshots offline; its **Use with CLI/MCP** action explicitly
creates a minimized copy in the CLI store, excluding source text, user/context
fields and exception messages. Originals remain unchanged; conversion is never
automatic. See [investigation, comparison and code search](docs/developer-workflow.md)
and [snapshot limits](docs/request-focused-ui.md#saved-views-and-pinned-snapshots).

A `traceId` correlates a logical flow; a `segmentId` identifies a retained local
timeline. Distributed HTTP uses a W3C trace ID and separate segment UUIDs.
An outbound HTTP observation has its own span ID. With enabled SQS propagation,
a Queueman consumer can continue the producer's trace in a new segment;
capture-only consumers can instead start independent local traces.

A `tracelens-id` is a human reproduction label, not the distributed trace ID.
Optional action IDs group multiple app-labelled requests without making them a
single causal request. See [request discovery](docs/request-focused-ui.md) and
[optional UI action labels](docs/ui-action-integration.md).

## Build and run

Receiving a prebuilt ZIP? Start with [guided setup](docs/guided-setup.md), not
the maintainer build commands below. JDK 8+ runs setup/viewer; the applications
keep their own supported runtimes and existing environment. Saving setup prepares
files only; optional start/restart is separately reviewed and confirmed for an
explicit local runtime. Setup does not build or provision applications.

Build/unit tests require JDK 21. Agent, bridge, core and javax adapter target Java
8+; the legacy Spring Boot starter uses Java 21. Compatibility is tested for
specific seams/fixtures, not every application or Java library version.

From the repository root:

```bash
./gradlew test :tracelens-agent:shadowJar
node --test tracelens-cli/tests/*.test.cjs

cd tracelens-cli
uv run --extra test pytest -q
```

Node is needed for browser-logic tests, not to run the viewer. With an installed
Java 8 toolchain, `./gradlew :tracelens-agent:java8Smoke` exercises the shaded agent
in a real Java 8 JVM. `:tracelens-agent:java8ToolSmoke` exercises the same artifact
as a CLI/MCP process. `:tracelens-agent:javaBundle` creates the guided-setup ZIP
in `build/distributions/`: `tracelens.jar`, `setup.sh`, portable cb-app
`tracelens.json`, and `ems/tracelens.json`.

Attach the **shaded `-agent.jar`**, not the thin jar:

```bash
java -javaagent:/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
  -Dtracelens.applicationId=my-service -Dtracelens.packages=com.example.app \
  -jar my-service.jar
```

Standalone mode supplies its store and debug API at `http://127.0.0.1:7778`.
No application starter, servlet filter edit or frontend integration is required.
Empty package scope means no application line weaving; supported request metadata
and enabled boundary hooks can still work. Use a different `tracelens.port` for
another JVM.

For cb-app and EMS launch/configuration commands, follow
[cb-app integration](docs/cb-app-integration.md) and
[distributed tracing](docs/distributed-tracing.md).
The checked-in local setup uses cb-app debug port `7778`, EMS debug port `7779`
and combined viewer port `7781`; application ports are different.
Scripts contain machine-local defaults, not a portable production deployment.

For a received ZIP, use [guided setup](docs/guided-setup.md). Rerun the same
script to edit capture settings or add cb-app, EMS, Plans, Ledger or GO; UI
middleware is not offered. Review each service's changes before saving and
confirm any supported start/restart separately. A saved configuration is not
proof that the service is running with it.

For checkout-based cb-app workflows, the optional
[Makefile integration](docs/cb-app-integration.md#managed-setup-with-make-up-tracelens)
documents `make up-tracelens`, prerequisites, startup-hook ownership and restart
effects. Do not layer guided setup over an existing managed hook. Use
[manual attachment](docs/java-distribution.md) for reviewed existing launchers.

The Spring Boot starter and javax servlet adapter remain supported **optional
legacy integrations**. Their activation/configuration differs from standalone.
Do not combine two runtime owners in one JVM. See
[sharing and configuration](docs/sharing-and-configuration.md).

## CLI, viewer and MCP

For a manual setup from the extracted Java-only bundle:

```bash
java -jar tracelens.jar --config tracelens.json doctor
java -jar tracelens.jar --config tracelens.json serve
java -jar tracelens.jar --config tracelens.json mcp
```

For a guided installation, run these from the installation directory and use
`--config cb-app/tracelens.json` (or `--config ems/tracelens.json` for EMS-only).
The generated `start-viewer.sh` and `check-setup.sh` already select the right JSON.

The optional `viewer` section in the same JSON owns connection/source settings.
Existing `--services` registries remain supported. See
[setup and commands](docs/java-distribution.md) and
[storage guarantees](docs/java-capture-storage.md).

### Assistant tools

Connect MCP to the same combined JAR and service configuration used by the viewer.
For several services, the registry selects debug URLs, source roots and optional
display aliases. Reading a trace does not mutate application data; explicit
capture saves, renames and deletion act on local evidence files.

| Need | MCP tools |
| --- | --- |
| Setup checks with next steps | `tracelens_doctor`; `flow_status` for raw status |
| Exact backend API capture readiness | `diagnose_capture_request` |
| Focused API/scenario investigation | `investigate_request` (unique selection by default) |
| Broader retained discovery | `find_distributed_traces`, `list_flows`, `search_flows` |
| Save and compare explicit runs | `save_request_capture`, `list_request_captures`, `compare_request_captures` |
| Search minimized saved evidence offline | `search_saved_captures` |
| Inspect a cross-service journey | `get_distributed_trace` |
| Shared request-flow graph | `get_trace_visualization`, `show_trace_visualization` |
| Observed method nesting and boundaries | `get_segment_execution` |
| Captured SQL and bindings for one segment (operator opt-in, off by default) | `get_segment_sql`; see [SQL over MCP](docs/jdbc-query-capture.md#sql-over-mcp-operator-opt-in) |
| Compact summary, then source timeline | `get_flow_summary`, `get_flow` |
| Repeated failure signatures | `list_error_groups`, `get_error_group` |
| Optional app-labelled UI actions | `find_actions`, `get_action` |

`get_flow_mermaid` remains a legacy coarse sketch; inferred thread hops/return
arrows are not causal evidence. Prefer the canonical graph/execution tools.
Embedding depends on client capabilities; text/structured evidence and the viewer
remain the fallback. See [hybrid visualization](docs/hybrid-visualization.md).

Use progressive disclosure: locate a request, inspect its summary/graph, then
request the relevant segment/source detail. Do not load every raw event into an
LLM context. Live tools read retained data; capture tools read/write only the
operator-configured CLI capture store, not raw viewer snapshots, browser pins or
arbitrary files. The viewer's explicit **Use with CLI/MCP** action creates a
separate minimized capture those tools can read; use the returned capture ID.
Saving requires an explicit request; comparisons are offline and observed-only.
See the [end-to-end workflow](docs/developer-workflow.md) for commands and limits.

## Configuration, retention and safety

The authoritative settings are in the effective configuration, not a screenshot
from an older process. Key controls include:

| Control | Purpose |
| --- | --- |
| `httpIncludePaths` | HTTP entry path prefixes in the selected service JSON; exact path or descendant, not wildcard syntax. Omitted defaults to `["/"]`; `[]` disables retained HTTP entries; built-in ignored paths still apply. |
| `packages`, `excludedPackages`, `blacklistedNodes` | Application bytecode scope; `modules` is not used by the current class matcher |
| `trackMethods`, `trackExceptions` | Separate method/exception retention switches; use `captureMode` to choose the retained detail level. These switches do not remove woven callbacks. |
| `captureMode` | `overview` retains configured dependency/error observations; `methods` adds method entry/exit without lines; `detailed` (default) includes lines. HTTP evidence and SQL are independent opt-ins; `trackMethods` remains a separate switch. Callbacks remain woven. |
| `detailedCapturePaths`, `detailedCaptureUntilEpochMillis` | Optional exact/descendant path scope and absolute expiry (`0` means no expiry); unmatched/expired scope falls back to overview. Matching paths retain methods in methods mode, otherwise detailed, including legacy overview promotion. Omitted events are not restored. |
| `instrumentExecutors`, `instrumentScheduled` | Supported context/job hooks |
| `chargebeeJobTypes` | Exact Chargebee custom Job types to capture; separate from Spring `@Scheduled`, `[]` disables this hook |
| `captureMessagingBoundaries` | Local messaging observation; omitted/null follows `instrumentMessaging` |
| `captureDataStoreBoundaries` | Default `true`: metadata-only calls for supported ClickHouse, Spring Data Redis/Valkey, AWS v1 DynamoDB and S3 clients; `false` opts out. No SQL, keys, values or remote-success inference. |
| `captureJdbcQueries` | Default `false`: local opt-in PostgreSQL/MySQL raw SQL, driver execution timing and calling-context evidence. No result rows; parameter values require the separate opt-in below. See [SQL capture](docs/jdbc-query-capture.md). |
| `captureJdbcParameters` | Default `false`, effective only with `captureJdbcQueries`: bounded positional scalar/null bindings. SQL and bindings can be sensitive; `capturePayloads: false` does not disable them. |
| `captureClickHouseQueries`, `captureClickHouseParameters` | Separate default-off native ClickHouse SQL/named-binding capture; independent of JDBC. See [native query limits](docs/request-data-and-clickhouse.md#native-clickhouse-queries). |
| `captureHttpQueryParameters`, `captureHttpRequestBodies` | Separate default-off incoming request-data opt-ins; supported bodies are observed as the application reads them, not eagerly consumed. |
| `redactHttpPayloads`, `maxHttpPayloadLength` | Default `true` / `4096`: best-effort known-field redaction and bounded request data. Explicit local raw mode does not add header/response capture. |
| `maxSqlLength`, `maxSqlQueriesPerRequest` | Defaults `4096`, `100`: bounded SQL characters per execution and executions per service segment; truncation/limit notices indicate incomplete evidence. |
| `instrumentMessaging` | Existing SQS single-message propagation/Queueman continuation |
| `httpClientCaptureEnabled` | Standalone supported HTTP-client observation; omitted/null follows `distributedTracingEnabled` |
| `distributedTracingEnabled`, `httpPropagationAllowedDestinations` | W3C correlation and exact trusted propagation targets, not a display filter |
| `externalCallClasses`, `httpIdentityHooks` | Wrapper-entry markers and explicitly configured identity seams |
| `requireLabels` | Gate retained HTTP capture on labels; not authentication or a universal job/propagation gate |
| `maxTrackedRequests`, `maxTrackedEvents`, `idleTtlMinutes` | Retention limits |
| `maxSegmentBytes`, `maxTrackedBytes` | Default 32 MiB per segment and 256 MiB per process: conservative retained-data accounting, including reserved metadata. These are not measurements or guarantees of total JVM heap usage. |
| `httpRetentionPercent` | Default `75`: protect HTTP retention from scheduler/consumer churn; `0` restores shared retention |
| `evictOnExport` | Raw debug export's destructive-read default |

Keep capture settings in one JSON per app. Launchers wire the JAR, selected JSON
path and debug port, not a second HTTP allowlist. Changes require an application
JVM restart; there is no configuration hot reload. HTTP entry selection does not
disable separate background hooks or distributed context propagation.

Core defaults are 200 retained segments, 500,000 total events and a 60-minute
activity-based TTL, with a 50,000-event per-segment cap. Local configs override
these. With the default 75% HTTP reservation, segment slots have borrowable
shares (150 HTTP / 50 background at default capacity), while event budgets are
fixed (375,000 HTTP / 125,000 background). Background churn cannot consume the
HTTP event budget or evict HTTP segments below their reserved share. Other HTTP
requests, TTL, reset and restart can still remove HTTP evidence. This is bounded
protection, not durable retention; use **Save snapshot** for an important run.
The new policy requires rebuilding the agent and restarting each target JVM.
Event counts alone do not bound memory. Byte budgets now constrain retained
evidence too, with capacity reserved for dependency/error observations after
method/line detail fills. The reserve is finite: dependencies can also be omitted
when all capacity is exhausted. Capture mode, detail truncation and `dropReasons`
are reported; omitted detail is never presented as proof that a call did not occur.
Reads do not refresh activity TTL. See [large capture storage and paging](docs/java-capture-storage.md#large-traces-and-paged-details).

Standalone endpoints use `/tracelens/summary`, `/traces`, `/metadata`,
`/instrumented-classes`, `/export/{segmentId}`, `/find-by-lens-id/{label}` and
`/find-by-trace-id/{traceId}` under the same `/tracelens` prefix. Mutating
`enable`, `disable` and `reset` controls also exist. Inspect the
[standalone guide](docs/standalone-agent.md) before using them.

The Java viewer uses `/overview/{segmentId}`, `/events/{segmentId}` and
`/request-data/{segmentId}` for upgraded agents. The request diagram does not
download the full timeline. Details are loaded in bounded pages; new snapshots
store compressed chunks, while existing version-1 JSON captures remain readable.

CLI/UI/MCP inspection exports request `evict=false`; a direct debug export can
evict according to config. Reset discards retained evidence. Disabling recording
does not remove woven callbacks or universally stop existing-context work.

Agent source changes require rebuilding the shaded jar and restarting the target
JVM with that artifact; configuration changes also require a target-JVM restart.
A restart alone does not rebuild source, and an unchanged `LOCAL`/snapshot
version label does not prove the newest build was loaded. Java tooling/UI changes
require replacing the combined JAR and restarting viewer/MCP. Browser asset changes also require
reload. A viewer upgrade cannot recover missing observations from old captures.

## Production posture and roadmap

No representative production overhead budget, universal fail-open guarantee,
built-in production RBAC or complete privacy guarantee is established. Source,
paths, queue/topic names, identities and exception messages may be sensitive even
with payload capture disabled. Opt-in raw SQL and captured bindings can also
contain sensitive values; disabling payload capture does not sanitize them.
Production use needs security/privacy review,
benchmarks, compatibility validation and operational/access controls first.

Implemented capabilities are listed above and in [use cases](docs/use-cases.md).
See the [prioritized roadmap](docs/roadmap.md) for the current investigation
workflow work and planned evidence-backed impact reports, portable investigation
bundles, selective dependency diagnostics and a shared-dev/sandbox, build-aware
method/API-to-flow impact map with bounded continuous evidence and representative
execution history. The proposed map aims to focus regression testing and reduce
blind sandbox waiting for unexpected affected flows; it does not replace tests,
required soak periods or release review, or establish that a change is safe.
The following remain **ideas, not available product features**:

- Source-aware cross-build alignment and automatic changed-method impact reports
  across scenarios. Explicit local snapshot diffs are already implemented;
  complete blast-radius analysis is not.
- Shared, long-term selected-execution history with verified build/config identity,
  retention and access controls; queries for observed API/job/method/error-path usage.
- Long-window "not observed" review candidates, never proof that code is dead.
- API docs, user/business documentation and source context retrieval alongside
  runtime evidence, with versioning and source attribution.
- Additional HTTP/queue/client adapters and more complete async context coverage.
- Benchmarks, capture-loss accounting, production hardening and repeatable team
  distribution.

The implemented comparison workflow uses explicit JSON capture files, without a
database. For later shared history, one possible design is
compressed trace JSON in object storage plus a relational metadata/execution
index (build/Git identity, scenarios, methods, lines, exceptions and boundaries).
This is a proposal, not a deployed backend. Exact trace coordinates do not need a
vector database; embeddings could later help fuzzy incident/doc search over
compact approved summaries. Do not embed raw event streams by default.
