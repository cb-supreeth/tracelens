# TraceLens use-case catalog

Reviewed against the repository implementation on **2026-10-28**. This is a
catalog of supported workflows and relevant future ideas, not a claim of complete
execution coverage or of what is installed in a currently running application.

TraceLens answers: **what did this captured execution actually observe?**
Use source, tests, logs and service contracts alongside it to answer why something
happened or what could happen in a different execution.

Use the current Java bundle and [guided setup](guided-setup.md). The same script
handles first-time setup and later capture-setting changes. Saving settings is
not proof that the running JVM loaded them; verify a fresh reproduction after
the intended restart. In the viewer, **Requests**, **Saved captures**,
**Performance**, and **Services & capture settings** separate the main tasks.

## Status key

- **Available:** implemented tools/views support the workflow within stated scope.
- **Available with setup:** requires a supported integration, explicit configuration
  or optional application labels; not automatically enabled everywhere.
- **Manual investigation:** current evidence helps, but there is no dedicated
  automated analysis/comparison engine for the question.
- **Roadmap:** proposed; do not present it as available functionality.

## At a glance

| Developer need | Status | Starting point |
| --- | --- | --- |
| Understand an unfamiliar feature/codebase | Available | [Execution walkthrough](#1-understand-an-unfamiliar-feature) |
| Find the request behind a page or button without knowing its trace ID | Available | [Request discovery](#2-find-the-request-behind-a-ui-action) |
| Diagnose a missing capture and save one fresh reproduction | Available: explicitly armed viewer watch | [Guided investigation](#2-find-the-request-behind-a-ui-action) |
| Debug a reproduced CRI/bug | Available | [Failure investigation](#3-debug-a-reproduced-cri-or-bug) |
| Group repeated errors and choose a representative request | Available | [Error triage](#4-triage-repeated-errors) |
| Follow a request across cb-app and EMS | Available with setup | [Cross-service tracing](#5-follow-a-request-across-services) |
| See calls to services without TraceLens | Available with setup | [External dependencies](#6-see-external-dependencies-without-receiver-agents) |
| Understand queue publishing/consumption | Available with setup | [Messaging](#7-investigate-messaging-boundaries) |
| Investigate executor work and scheduled jobs | Available with setup | [Background work](#8-investigate-background-and-executor-work) |
| Understand datasource routing | Available with setup | [Database routes](#9-check-database-routing-decisions) |
| Find slow/repeated JDBC queries and suspected N+1 patterns | Available: on-demand bounded analysis | [Timing and repetition](#10-investigate-slowness-and-repeated-work) |
| Compare a feature before and after changes | Available: explicit local captures; impact interpretation remains manual | [Before/after](#11-compare-before-and-after-a-change) |
| Find a method, exception or destination in saved work | Available: bounded, on-demand evidence search | [Saved evidence search](#11-compare-before-and-after-a-change) |
| Assess which workflows might be affected by a method change | Manual investigation | [Change impact](#12-assess-change-impact-and-choose-regression-tests) |
| Verify a reproduction/test exercised intended code | Manual investigation | [Test evidence](#13-check-what-a-test-or-reproduction-exercised) |
| Give Cursor/Codex runtime context and visualization data | Available | [MCP workflows](#14-provide-runtime-context-to-an-ai-assistant) |
| Reduce UI noise and preserve an investigation | Available | [Focus and retention](#15-reduce-noise-and-save-an-investigation) |
| Share evidence with another developer | Available: version-2 capture archive export/import | [Handoffs](#16-share-a-reproduction-or-review-evidence) |
| Group several requests into a named browser action | Available with optional app changes | [Action labels](#17-group-requests-by-business-action) |
| Check whether instrumentation/identity/configuration is working | Available | [Setup diagnostics](#18-diagnose-the-tracelens-setup-itself) |
| Use evidence while designing a new business requirement | Manual investigation | [Feature planning](#19-plan-a-feature-using-existing-runtime-behavior) |
| Investigate production safely | Not established as production-ready | [Production constraints](#production-use-is-a-separate-readiness-decision) |

## 1. Understand an unfamiliar feature

**Question:** “When I retrieve a feature, which controller, helper and service
methods execute, and which external operations are involved?”

Run one representative request, select its service card and choose **Open code
flow** in **Selected evidence**. Request-graph cards stay fixed while the
adjacent inspector shows details. **Code flow** reads observed methods in thread sections;
**Call tree** visualizes the same bounded nesting. Search class/method names,
exception types or destinations, reveal collapsed matches, and use **Focus
selected branch** to isolate observed ancestors and descendants.
**Show source in timeline** opens retained source evidence; **Open local source**
offers editor links to today's configured checkout, not a restored historical
revision. MCP `get_segment_execution` provides the same kind of bounded execution
model for an assistant. Follow implicated files rather than reading the entire
repository without a starting point.

**Useful result:** an evidence-backed walkthrough of that scenario for onboarding,
code review or debugging. **Limit:** observed nesting is not necessarily a direct
source call; excluded code, overload ambiguity and missing events can hide detail.
Source lookup uses a checkout, which may not match the captured build.
Execution search is bounded to the available model, not all raw events; no match
does not mean a method never ran.

Read [the codebase walkthrough](codebase-walkthrough.md) and
[execution-model rules](hybrid-visualization.md#mcp-first-segment-execution-visualization).

## 2. Find the request behind a UI action

**Question:** “I clicked a button and know the API, but not the trace ID.”

Filter Live requests by service, HTTP method, API path and a narrow time window;
add recorded user/site or outcome where available. Expand a repeated-API group
to select the individual request. CLI `investigate` or MCP `investigate_request`
can find and summarize it, refusing ambiguous matches by default. Use
`find_distributed_traces` when a broader candidate list is useful.
No frontend changes or pre-known trace ID are required for this workflow.

For a fresh reproduction, use **Start an investigation → Check capture readiness** with the
exact backend path and optional method/service/user/site. Checks do not change
recording or scope. **Watch next request & save** arms a 60/120-second window;
reproduce only after **Watching** appears. It excludes existing requests and
saves one unambiguous newly completed request, not an arbitrary latest match.
Cancellation, timeout and incomplete discovery remain explicit. Review partial
snapshot warnings; a watch is not proof of complete coverage.

For example, browser navigation `/d/features/test1`, cb-app's
`/api/v2/features/dGVzdDE=` and EMS's `/features/test1` can be different paths for
one business operation. Search the actual server API, not just the address bar.

**Limit:** timing/path similarity is not proof of which browser action caused a
request. For explicit action grouping, use [optional action labels](#17-group-requests-by-business-action).

## 3. Debug a reproduced CRI or bug

Label an authorized reproduction, for example `tracelens-id: CRI-123`, then locate
its retained segment(s) with `investigate_request` or the viewer. Use
`get_flow_summary` for the relevant segment, inspect exceptions and boundaries,
and fetch relevant source detail with `get_flow`.

**Useful result:** where observed execution diverged from the intended behavior,
which methods an exception escaped, and which dependencies were involved.
Correlate with existing KVL/APM evidence when payload/state details are needed.

**Limit:** TraceLens does not replay requests or capture arbitrary local variables,
method arguments, queue bodies or database result rows. Incoming API query/body
evidence and SQL/bindings are separate bounded opt-ins, visible with Copy actions
in the inspector. An exception event does not itself mean the final HTTP request
failed; another frame may have handled it.

## 4. Triage repeated errors

Use **Requests → Errors**, filter the relevant API/service/time/category/status and
expand a group. Inspect representative requests rather than every repeated row.
MCP provides `list_error_groups` and `get_error_group` over retained summaries.
Scheduled failures remain here even though normal job browsing has its own tab;
select **Channel → Scheduled jobs** for those failure groups.

**Useful result:** distinguish recurring HTTP 4xx/5xx errors, explicit timeouts
and execution failures, with recorded boundary-exception classes where available;
prioritize a reproducible signature. Inspect individual flows for other captured
exceptions rather than expecting every exception event to become an error group.
**Limit:** a signature is not an AI root-cause diagnosis. Groups use currently
retained evidence, not historical incident frequencies. One request can belong
to multiple signatures, so do not sum group counts into unique requests.

See [error groups and filters](error-groups.md).

## 5. Follow a request across services

Configure both standalone agents and the viewer/MCP service registry. Enable W3C
HTTP tracing with exact trusted propagation destinations. An observed caller
HTTP span and an explicitly correlated receiver segment form a linked journey.
The [cb-app setup](cb-app-integration.md) and
[distributed-tracing guide](distributed-tracing.md) cover activation; starting
cb-app alone does not start EMS or the combined viewer.

**Example:** cb-app receives a Features API call and calls EMS. Open one request
graph, then inspect the local execution in either service. Each service can also
be used independently; both agents are not required merely to capture one side.

**Limit:** only supported clients/context seams correlate automatically. Repeated
calls keep separate captured invocation frames; they are not merged by service
name. Paths
remain as each service observed them. Node durations are local, not additive
cross-service time. See [distributed tracing](distributed-tracing.md).

## 6. See external dependencies without receiver agents

Enable supported caller-side capture and inspect **Flow diagram** or the segment's
**Observed external boundaries** table. Exact display aliases can name a recorded
authority as EMS or Plans even if that receiver is not configured in the viewer.

**Useful result:** “This execution made an observed HTTP call to Plans,” without
installing TraceLens in Plans. A missing receiver appears as **Remote execution
not observed**, rather than disappearing from the diagram.

**Limit:** no remote source/timing/processing is invented. “Not observed” does not
mean offline. JDK HTTP/HTTPS and supported synchronous Apache HttpClient 4 paths
are covered, along with the documented Vert.x client paths; other libraries are
not automatically covered. Wrapper entries and DB routing markers
are labelled separately from confirmed client boundaries.

See [the coverage matrix](dependency-visibility-and-change-impact.md#implemented-coverage).

## 7. Investigate messaging boundaries

With supported hooks, inspect SQS single/batch sends, Kafka sends/scoped polls and
Queueman `processMessage` boundaries. Publish and consume labels/arrows distinguish
direction; the timeline filter can hide unrelated line events.

**Useful result:** which queue/topic was observed, which local operation occurred
and whether a failure escaped that boundary. With existing SQS single-message
propagation enabled, Queueman can continue producer correlation in a new segment.
Capture-only mode does not add SQS message attributes.

**Limits:** `SUBMITTED` does not mean acknowledged/delivered/processed. Kafka poll
capture requires an existing active trace and does not instrument arbitrary record
handlers or inject Kafka headers. Queueman may handle a failure internally or skip
business handling. Queue names can be unknown; matching topic names never proves
two observations belong to the same message.

## 8. Investigate background and executor work

Use supported executor context continuation to inspect work performed on recorded
thread lanes. Capture in-scope Spring `@Scheduled` methods when
`instrumentScheduled` is enabled. Chargebee's custom `Job` scheduler instead
needs the separate exact-name `chargebeeJobTypes` opt-in and matching method/line
package scope; enabling Spring scheduled tracing alone does not cover it.
See [custom-job setup](cb-app-integration.md#capture-a-chargebee-custom-scheduled-job).
Each selected custom dispatch is a new scheduler-rooted attempt, not an automatic
continuation of its scheduling API request. Known audit outcomes distinguish
success, skip and failure; an unrecognized outcome remains unknown.
For either supported job mechanism,
discover them in the **Scheduled jobs** tab or with
`list_flows(channel="SCHEDULER")`. The tab groups repeated runs by job label and
service and has independent service/time/job-name/outcome filters. Expand a group
and open one run to retain all linked observed service segments in its graph and
source views, or save a snapshot. Scheduler-rooted captures stay out of Live
requests even under All request channels/Show all retained; those controls remain scoped
to the active Live requests or Scheduled jobs tab.

**Useful result:** code paths/errors from jobs and supported asynchronous work,
not only servlet requests. **Limit:** new runtime-local thread IDs improve lane
identity; legacy names remain ambiguous, and neither proves a handoff. Raw
threads, common-pool/reactive/custom executors and arbitrary
schedulers are not universally covered. No synthetic cross-thread causal links
are added by the execution model. `COMPLETED` is not proof of business success.
The tab changes presentation only; capture requires the appropriate loaded agent
and JSON. It does not add scheduler coverage or alter recording by itself.

## 9. Check database-routing decisions

For an instrumented Spring routing datasource, inspect `DB_ROUTE` markers and
their place in the observed execution. This can help investigate why code chose
a read/write/tenant route when that route is recorded.

**Limit:** this is pool/route selection, not SQL tracing, query parameters, query
plans, row changes or proof that the database operation completed.

For PostgreSQL/MySQL SQL text and client execution timing, separately enable
[local SQL query capture](jdbc-query-capture.md). This adds query evidence, not
query plans, result rows or proof of transaction commit. Raw SQL is not sanitized.

## 10. Investigate slowness and repeated work

Open **Performance**, choose recent captured requests or one saved capture, set
thresholds and **Analyze**. Recent runs inspect at most ten newest matching
requests. **Category** focuses slow queries, repeated queries, suspected N+1 or
query-heavy segments without rereading evidence. Choose a **Captured occurrence**
and **Open query evidence** to inspect the exact retained call; the picker is
bounded to the first 200 query nodes.

**Useful result:** ranked JDBC patterns and specific calls to investigate, with
captured SQL, timing and optional bindings. This analyzes PostgreSQL/MySQL JDBC
evidence, not native ClickHouse or fleet-wide traffic, and does not enable capture.
Method/HTTP timing can provide additional leads; verify with controlled tests.

**Limit:** suspected N+1 is a heuristic, not proof of a bug. No findings is not a
health verdict. Inclusive local duration includes waits/tracing overhead, not
CPU/self time, and cumulative query time can overlap. One before/after sample
is not a benchmark. See [Performance insights](performance-insights.md).

## 11. Compare before and after a change

1. Run a representative scenario on the baseline; record build identity/input
   assumptions and save an explicit named capture before a JVM restart.
2. Apply the change, rebuild/restart and replay comparable inputs/application state.
3. Save the candidate separately and compare observed paths, exceptions, statuses
   and external dependencies. Interpret the differences with source and tests.

**Available today:** in **Saved captures**, select two viewer snapshots, Before
first, then **Compare selected** and **Compare saved snapshots**. Behavior review
pairs response outcomes and external calls before method differences. Changed
methods group under their outermost supported recorded parent, with child
differences expandable. Search finds nested children. Exact per-caller configured
aliases label destinations without changing the recorded address or matching.
Open the exact saved invocation, then **Back to comparison** to restore context.
**All differences** keeps the flat detailed report. Alternatively use
`capture save/list/compare` in the CLI and
`save_request_capture`, `list_request_captures`, `compare_request_captures` in MCP.
These reuse the same bounded comparison engine and work after JVM restart.
Full viewer files and minimized CLI captures remain separate. **Use with
CLI/MCP → Create CLI/MCP capture** explicitly copies a selected viewer snapshot
to a new minimized capture without changing its original.

Find prior evidence with **Search saved evidence** across viewer files and
minimized captures, or `capture search` / `search_saved_captures` across the
minimized store only. Search is literal, bounded and on demand, not a global
usage index or a coverage guarantee. See the [complete workflow](developer-workflow.md).

**Example:** a baseline EMS call returned 404; the candidate returned 200 and that
call was not observed. This makes the behavioral difference visible, but proving
that a new guard deliberately skipped it still requires source/test evidence.
Review input/build/scope gaps before interpreting absence.

**Not implemented:** automatic source-aware cross-build line alignment,
changed-method-to-all-workflows impact analysis or a graph-overlay diff. Parent
grouping is observed nesting, not proof the parent's implementation changed.
Line numbers can shift; missing observations do not establish removed/dead code.

## 12. Assess change impact and choose regression tests

Combine a changed-method/source-reference review with representative captured
workflows. Identify executions that reached changed code and the downstream
operations those executions observed; prioritize regression/contract tests for
those APIs, jobs, services and topics.

Use the [impact-analysis skill](impact-analysis-skill.md) to guide this manual
workflow. Separate **observed** runtime evidence, **potential** effects from
code/contracts, **verified** outcomes from supplied test assertions, and
**unknown** gaps in workload, build identity or capture coverage.
There is no automatic changed-method-to-all-workflows index or
`analyze_change_impact` MCP tool yet. Absence from traces cannot establish that
a change is safe or a method is dead.

See [the impact workflow](dependency-visibility-and-change-impact.md#how-this-helps-with-a-code-change).

## 13. Check what a test or reproduction exercised

Run a controlled scenario with a recognizable label and inspect the observed
method/line path, request outcome and expected external boundary. Repeat after a
fix to check whether the intended path was exercised and the symptom changed.

**Useful result:** supporting evidence for test review, missing-scenario discovery
and a reproducible bug-fix handoff. **Limit:** TraceLens is not a test runner,
assertion generator, branch-coverage engine or business-correctness oracle. No
event is not proof of non-execution, and a line marker does not prove every
operation on that source line completed.

## 14. Provide runtime context to an AI assistant

Connect an MCP client to the appropriate local service registry. A good sequence
is `tracelens_doctor` → `investigate_request` → summary/request graph → the exact
segment's execution/source detail. `flow_status` and `find_distributed_traces`
remain available for lower-level status and discovery. Supply service/instance
identity where needed; use the bundled alias `cb-app`, not the checkout name
`chargebee-app`, in federated tool filters.

Example prompt:

> Find my latest captured GET /api/v2/features request in cb-app. Show its
> request-flow graph, including external boundaries with no receiver trace.
> Inspect the relevant segment's observed method nesting. Separate facts from
> hypotheses and keep missing/truncated evidence visible.

The viewer owns a small deterministic request graph. Compatible MCP Apps clients
can show the same renderer; other clients receive structured/text/Mermaid output.
`get_segment_execution` provides a bounded local model for the client; the normal
viewer also offers **Code flow** and **Call tree**. Missing-capture checks use
`diagnose_capture_request`; explicit local `save_request_capture` and offline
`compare_request_captures` / `search_saved_captures` support before/after analysis.
None of these require an LLM inside the JVM.

**Limit:** client capabilities vary; data retrieval does not guarantee a diagram
will render. Source/trace data sent to an assistant follows the team's AI/data
policy. See [hybrid visualization](hybrid-visualization.md).

## 15. Reduce noise and save an investigation

Filter API/service/time/user/site/outcome, collapse repeated APIs and use Errors
for failure-focused triage. Scheduled runs have their own grouped tab and independent
filters; Live requests does not include scheduler-rooted captures. **Show all retained**
stays within the active Live requests or Scheduled jobs tab. Save a view to reuse
filters. Open a segment and use
**External boundaries and exceptions only** to focus on integration activity.

Use **Save snapshot** on a selected request or job run before it expires or the
service restarts. It saves bounded evidence to viewer-local files; older browser
pins remain separate browser-local records. Saving is not an atomic cross-service
capture and can be partial; inspect warnings. Viewer files persist across service
restarts, while browser-only pins still depend on that browser's storage.
Saved views retain filters, not traces. Live links stop being useful after eviction.
For terminal/assistant access after restart, explicitly use `capture save`,
`save_request_capture` or the viewer's **Use with CLI/MCP** action. Those separate
minimized JSON files support offline list/compare/search; a viewer file is not
automatically made available to MCP. Use the same configured capture directory
across viewer/CLI/MCP. Choose the
[storage workflow](developer-workflow.md#storage-privacy-and-cost) that matches
the handoff you need.

**Limit:** UI filters reduce display noise, not agent recording cost. Narrow agent
scope/entry filters or use appropriate capture controls separately.
See [the request-focused viewer guide](request-focused-ui.md).

## 16. Share a reproduction or review evidence

For a version-2 saved capture, choose **Download capture archive** and send the
ZIP through an approved channel. The recipient uses **Saved captures → Import
capture archive** in a current viewer. Import creates a new saved ID, without
replaying the request or contacting the original applications. Older version-1
JSON snapshots remain readable but do not gain archive export automatically.

**Download graph JSON** is only the bounded graph, not all events/source. A copied
localhost link is not a public share link. Named CLI/MCP captures are separate
minimized files for offline comparison/search; **Use with CLI/MCP** creates a local
copy, not a team upload. Review request values, SQL/bindings, source and identifiers
before sharing any format. See [storage](java-capture-storage.md).

See [sharing and configuration](sharing-and-configuration.md).

## 17. Group requests by business action

An optional application/browser wrapper can attach a common
`tracelens-action-id`, descriptive `tracelens-action-name` and opaque session ID to
requests caused by one action. Use `find_actions` and `get_action` to discover
retained members without knowing their trace IDs.

**Useful result:** distinguish two similar workflows or gather a multi-request
page interaction. **Limit:** labels must actually be supplied; TraceLens does not
automatically observe browser clicks. Labels group intent, not causality.
HTTP W3C propagation does not automatically forward all action/user fields.
No frontend integration is required for the normal API-based workflow.

See [optional UI action integration](ui-action-integration.md).

## 18. Diagnose the TraceLens setup itself

Start with **Services & capture settings**, the generated `check-setup.sh`, or
`java -jar tracelens.jar --config cb-app/tracelens.json doctor` for bounded,
read-only per-service checks. Substitute your primary config. Use MCP
`tracelens_doctor` for the same workflow. Reopen `sh setup.sh` to review configuration
changes; saving settings and restarting a supported service are separate actions.
See the [developer workflow](developer-workflow.md). Readiness is not proof of
runtime coverage or acceptable overhead.

Inspect `summary`, `metadata`, instrumented-class diagnostics and a known test
request. Check package/entry scope, effective capture/propagation settings, service
instance identity, retention, context drops and the actual supported client library.
For one exact backend path, use viewer **Start an investigation → Check capture readiness**,
CLI `diagnose --path ...` or MCP `diagnose_capture_request`. They distinguish
confirmed exclusions from unknowns using current running metadata and retained
summaries; current settings cannot establish why an earlier request is absent.

**Useful result:** distinguish stale agent/configuration, wrong port/source root,
missing context and expired data from a product bug. Verify authenticated identity
hooks rather than assuming every app exposes its user/site as a servlet principal.

**Limit:** `agentActive` and a class count do not prove every desired hook ran or
that the newest artifact is loaded. Summary failure counters are not a complete
audit of every infrastructure adapter. New Java capture needs a JVM restart;
viewer/MCP reconnect alone only updates the consumer layer.

## 19. Plan a feature using existing runtime behavior

Exercise related business scenarios, inspect existing entry points, methods and
dependencies, then combine that evidence with source/contracts and separately
provided API/user documentation. This can make requirement discussions and
implementation planning more concrete.

**Limit:** observations describe existing exercised behavior, not the required
business semantics. Automatic API-doc/user-doc ingestion, versioned business
knowledge retrieval and generated impact reports are roadmap ideas, not current
MCP features.

## Production use is a separate readiness decision

An approved targeted production reproduction is a possible future deployment
use case, not something this catalog certifies safe today. Required work includes
representative overhead/compatibility tests, privacy/InfoSec review, access controls,
retention and incident procedures, and stronger capture-loss/failure isolation.
Do not replace durable KVL/APM signals with line-level tracing.

## Roadmap use cases — not implemented

The [prioritized roadmap](roadmap.md) is the source of truth for proposed work.
Version-2 local archive sharing and bounded query analysis already exist; a shared
dev/sandbox workflow corpus, change-impact reports and fleet-wide analysis do not.
The table below lists broader ideas, not additional current features.

| Idea | Evidence/infrastructure needed |
| --- | --- |
| Source-aware cross-build comparison | Explicit local snapshot diffs are implemented; verified build identity and source-aware line/method alignment remain future work |
| Changed-method impact report | Git/source references plus a representative observed-workflow corpus; distinguish potential from proven effects |
| Long-term method/API/job usage and cleanup candidates | Durable selected captures, workload/retention coverage and static validation; never equate unobserved with dead |
| Team-wide dependency/architecture map | Aggregation across scenarios/builds, deduplication, provenance and coverage boundaries |
| API docs + user/business docs as AI context | Versioned approved sources, retrieval, access control and citations alongside runtime evidence |
| CI regression/coverage guardrails | A test harness and explicit stable assertions over controlled scenarios; no such CI policy exists today |
| Historical error/incident similarity | Selected retained evidence and privacy-aware indexing; no vector DB is currently present |
| Central team capture sharing | Local archive import/export exists; a shared collector still needs protected storage, retention and access policies |
| Additional protocol/runtime coverage | Adapters for unsupported clients/frameworks, tested context propagation and compatibility |

Explicit local files now support the initial comparison workflow.
A DB is useful only when durable shared history/query needs justify it. An LLM
does not turn incomplete traces into complete coverage.

## Common prerequisites and non-guarantees

- A supported agent/hook, active capture context, appropriate scope and retained
  data must all be present. The [current cb-app template](../scripts/cb-app/tracelens.json)
  includes selected Features, Plans/items, subscriptions and billing-related
  paths/packages and custom job types, not all business code. Existing local
  configs and older downloaded bundles may differ.
  Edit `httpIncludePaths` and packages together in the selected service JSON,
  preserve existing investigation paths, and restart the app when ready. Launch
  flags and viewer filters are not additional sources of capture scope.
- A receiver agent is needed to inspect receiver internals, not to display a
  supported caller observation.
- Source lines come from bytecode debug metadata; human source comes from the
  configured checkout. Missing debug info or mismatched builds limits interpretation.
- Agent retention is bounded in-memory. Browser pins and explicit files provide
  selected local persistence, not complete historical coverage.
- There is no universal HTTP/queue interception, CPU profiler, arbitrary-variable
  debugger, request replay, automatic code-impact engine or dead-code proof.
- Capture and export have overhead. No fixed production CPU/memory/latency promise
  has been established. Payload exclusion does not make all identifiers/messages safe.

For setup and internals, return to the [documentation index](README.md).
