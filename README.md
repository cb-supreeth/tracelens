# TraceLens

TraceLens is a local-first execution tracer and runtime-context provider for Java
services. It started with `cb-ems` and also supports the local `cb-app` setup.
A Java agent records observed methods, source-line markers and supported external
boundaries inside the service JVM. A separate Python CLI/viewer/MCP process reads
retained evidence through a local debug API.

> For this reproduction, which code ran, on which recorded threads, and which
> supported services, queues or database routes did it touch?

TraceLens complements KVL logs and APM tools; it does not replace their durable
production record. It is not a production-ready APM platform or a guarantee of
complete execution coverage.

**Documentation reviewed against repository source: 2026-10-19.** Editing or
building this repo does not upgrade already-running application JVMs. This is a
source-level capability review, not certification of a deployed build or its
production overhead.

## Start here

- [How to use TraceLens](docs/how-to-use.md): short first-run guide for developers—
  setup, find a request, ask an assistant and compare before/after runs.
- [Focused developer workflow](docs/developer-workflow.md): diagnose setup, find a
  reproduced API, save before/after evidence and compare it through CLI or MCP.
- [Productivity pilot](docs/productivity-pilot.md): validate time saved and adoption
  before expanding the POC; proposed targets, not measured results.
- [Use-case catalog](docs/use-cases.md): supported workflows, manual investigations,
  prerequisites, limitations and roadmap ideas.
- [Documentation index](docs/README.md): reading paths and every detailed guide.
- [Standalone setup](docs/standalone-agent.md): recommended agent-only installation.
- [cb-app setup and optional Makefile](docs/cb-app-integration.md#optional-makefile-launcher):
  opt-in local startup using cb-app's existing lifecycle.
- [Codebase walkthrough](docs/codebase-walkthrough.md) and
  [Byte Buddy internals](docs/byte-buddy-instrumentation.md): how capture works.
- [Dependency visibility and change impact](docs/dependency-visibility-and-change-impact.md):
  external calls without receiver agents and realistic impact investigations.

## Current architecture and tech stack

```text
Target service JVM
  agent bytecode hooks → bootstrap bridge / thread context → bounded Java store
                                                             ↓ local debug API
Separate Python CLI / viewer / stdio MCP process
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
| Viewer/MCP | Python 3.10+ standard library; stdio MCP, no Python runtime dependencies |
| Browser | Plain HTML/CSS/JavaScript and SVG; no frontend framework/chart dependency |
| Build/tests | Gradle/Shadow, JUnit/AssertJ, pytest and Node's built-in test runner |

The Java artifact version is `0.1.1-SNAPSHOT`; Python package metadata currently
uses `0.1.0`. These are separate package versions, not deployed-version evidence.

## What works today

- Scoped compiled Java line markers, method entry/normal exit and exceptions
  escaping instrumented methods.
- Supported servlet request capture, identity hooks and executor context continuation.
- In-scope scheduled jobs and Spring database-routing markers (not SQL tracing).
- Opt-in HTTP correlation across configured services; each service also works alone.
- Caller-side HTTP and SQS/Kafka/Queueman boundary observations, even when a receiver
  has no agent. Capture and propagation are separately configurable.
- Request-focused filtering, repeated-API grouping, error groups and saved views.
- Browser-local pinned captures, file exports and request-flow diagrams.
- MCP discovery, summaries, source-aware timelines and bounded execution models
  for client-generated visualizations.
- Read-only setup diagnostics and ambiguity-safe API/scenario investigation.
- Explicit local evidence snapshots and offline before/after comparison of
  observed methods, lines, nesting, dependencies, exceptions and outcomes.

These are **observed evidence**, not a complete call graph. A wrapper entry does
not prove network success; a queue submission does not prove delivery. See the
[client coverage matrix](docs/dependency-visibility-and-change-impact.md#implemented-coverage).
Apache HttpClient, OkHttp, arbitrary sockets and other unsupported clients are
not automatically covered.

### Storage and identity

The bundled TraceLens application ID and registry service name are **`cb-app`**;
use `--service cb-app` with the supplied registry. **`chargebee-app`** is the
repository directory and service-orchestrator name, so checkout paths and
startup commands retain that name. These are two names for the same application,
not two services. The no-JSON agent fallback is `cb-app`; explicit application
IDs still win, and the Spring starter also prefers `spring.application.name`
over its final fallback. Other applications should set their own identity.

Agent data lives in bounded JVM memory and can disappear on restart, eviction or
reset. There is **no server database or automatic remote collector**. Explicit
viewer pins use browser-local IndexedDB; CLI exports/downloads are files. Pins
are not team-wide history and cannot be read by MCP. The separate CLI/MCP capture
store uses explicit, bounded local JSON snapshots in `~/.tracelens/captures`
(overridable with `--capture-dir`); both CLI and MCP can compare those offline.

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
in a real Java 8 JVM.

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

For an already configured cb-app checkout, the optional
[Makefile template](scripts/Makefile.cb-app-tracelens) can be copied into
`chargebee-app/Makefile.tracelens`; follow the
[installation steps](docs/cb-app-integration.md#optional-makefile-launcher) first.
It exposes `check`, `up`, `restart`, `status` and `help`. `up` delegates to the
normal lifecycle and reuses healthy JVMs; `restart` reloads the agent into an
already-built app, clears Tomcat logs and enables jobs. It does not build the
TraceLens agent or start EMS/viewer/MCP. The template's presence here does not
mean it has been installed in another checkout.

The Spring Boot starter and javax servlet adapter remain supported **optional
legacy integrations**. Their activation/configuration differs from standalone.
Do not combine two runtime owners in one JVM. See
[sharing and configuration](docs/sharing-and-configuration.md).

## CLI, viewer and MCP

From `tracelens-cli`:

```bash
uv run tracelens --url http://127.0.0.1:7778 summary
uv run tracelens --url http://127.0.0.1:7778 doctor
uv run tracelens --url http://127.0.0.1:7778 investigate --path /api/v2/features --since-minutes 2
uv run tracelens --url http://127.0.0.1:7778 list
uv run tracelens --url http://127.0.0.1:7778 find CRI-123
uv run tracelens --url http://127.0.0.1:7778 render SEGMENT_UUID --source-root /path/to/service
uv run tracelens --url http://127.0.0.1:7778 serve --port 7777 --source-root /path/to/service
uv run tracelens --url http://127.0.0.1:7778 mcp --source-root /path/to/service
```

Replace placeholders. Pass `--url` explicitly: the CLI's compatibility default is
still port `8082`, not the standalone debug port. Without an explicit source
root, single-service source lookup uses `TRACELENS_SOURCE_ROOT` or
`~/work/cb-ems`. A local checkout is not proof of the captured build's source.

For several services, use `--services /absolute/path/services.json` instead of
`--url`. The registry chooses allowed service URLs, source roots and optional
display aliases; federation is read-only.

| Need | MCP tools |
| --- | --- |
| Setup checks with next steps | `tracelens_doctor`; `flow_status` for raw status |
| Focused API/scenario investigation | `investigate_request` (unique selection by default) |
| Broader retained discovery | `find_distributed_traces`, `list_flows`, `search_flows` |
| Save and compare explicit runs | `save_request_capture`, `list_request_captures`, `compare_request_captures` |
| Inspect a cross-service journey | `get_distributed_trace` |
| Shared request-flow graph | `get_trace_visualization`, `show_trace_visualization` |
| Observed method nesting and boundaries | `get_segment_execution` |
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
operator-configured local capture store, not browser pins or arbitrary files.
Saving requires an explicit request; comparisons are offline and observed-only.
See the [end-to-end workflow](docs/developer-workflow.md) for commands and limits.

## Configuration, retention and safety

The authoritative settings are in the effective configuration, not a screenshot
from an older process. Key controls include:

| Control | Purpose |
| --- | --- |
| `packages`, `excludedPackages`, `blacklistedNodes` | Application bytecode scope; `modules` is not used by the current class matcher |
| `trackMethods`, `trackExceptions` | Method/exception detail; neither disables line weaving |
| `instrumentExecutors`, `instrumentScheduled` | Supported context/job hooks |
| `captureMessagingBoundaries` | Local messaging observation; omitted/null follows `instrumentMessaging` |
| `instrumentMessaging` | Existing SQS single-message propagation/Queueman continuation |
| `httpClientCaptureEnabled` | Standalone supported HTTP-client observation; omitted/null follows `distributedTracingEnabled` |
| `distributedTracingEnabled`, `httpPropagationAllowedDestinations` | W3C correlation and exact trusted propagation targets, not a display filter |
| `externalCallClasses`, `httpIdentityHooks` | Wrapper-entry markers and explicitly configured identity seams |
| `requireLabels` | Gate retained HTTP capture on labels; not authentication or a universal job/propagation gate |
| `maxTrackedRequests`, `maxTrackedEvents`, `idleTtlMinutes` | Retention limits |
| `evictOnExport` | Raw debug export's destructive-read default |

Core defaults are 200 retained segments, 500,000 total events and a 60-minute
activity-based TTL, with a 50,000-event per-segment cap. Local configs override
these. Event counts are not measured heap-byte limits or callback-work budgets.
Reads do not refresh activity TTL. Per-segment truncation is reported; global
event-cap drops are not fully disclosed by current capture flags.

Standalone endpoints use `/tracelens/summary`, `/traces`, `/metadata`,
`/instrumented-classes`, `/export/{segmentId}`, `/find-by-lens-id/{label}` and
`/find-by-trace-id/{traceId}` under the same `/tracelens` prefix. Mutating
`enable`, `disable` and `reset` controls also exist. Inspect the
[standalone guide](docs/standalone-agent.md) before using them.

CLI/UI/MCP inspection exports request `evict=false`; a direct debug export can
evict according to config. Reset discards retained evidence. Disabling recording
does not remove woven callbacks or universally stop existing-context work.

Agent source changes require rebuilding the shaded jar and restarting the target
JVM with that artifact; configuration changes also require a target-JVM restart.
A restart alone does not rebuild source, and an unchanged `LOCAL`/snapshot
version label does not prove the newest build was loaded. Python changes require
viewer/MCP restart or reconnect; browser asset changes require reload. A viewer
upgrade cannot recover missing observations from old captures.

## Production posture and roadmap

No representative production overhead budget, universal fail-open guarantee,
built-in production RBAC or complete privacy guarantee is established. Source,
paths, queue/topic names, identities and exception messages may be sensitive even
with payload capture disabled. Production use needs security/privacy review,
benchmarks, compatibility validation and operational/access controls first.

Implemented capabilities are listed above and in [use cases](docs/use-cases.md).
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
