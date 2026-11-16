# TraceLens roadmap

Updated **2026-10-28**. Optimize for developer time saved and easy adoption, not
feature count. No database, background indexer or production APM platform is
required for the current workflow. See [pilot success criteria](productivity-pilot.md).

## Implemented: Java workflow, investigation and local SQL

Items 1–3 and the SQL capabilities below are implemented in this checkout, with
automated regression coverage. The current Java distribution uses one shaded
JAR for the agent and, in a separate JVM, the viewer/CLI/MCP; no Python or wheel
is required. Previously downloaded artifacts do not gain changes automatically.
Rebuild or replace the JAR and restart viewer/MCP processes for tooling/UI
updates. New agent hooks or capture-configuration changes also require restarting
the target application JVM with the intended JAR/configuration. A version string
alone is not proof that an installed bundle contains this checkout's changes.
See [Java distribution](java-distribution.md).

**Multi-service local setup and evidence navigation are implemented.** The
15-minute setup UI configures cb-app, EMS, Plans, Ledger and GO independently,
separates first-time setup from editing an existing installation,
loads/edits per-service capture settings with backups, supports incremental
additions, and preserves unrelated settings/captures. It reports per-service
failures and offers separately confirmed start/restart of registered Tomcat/JAR
children; existing IDE/custom processes are not adopted. It does not provision
or build applications; UI middleware is not offered. Developers can select
**Overview**, **Methods** or **Detailed**, independent evidence
opt-ins, scoped detail paths and a timed detail window. Saving a policy does not
apply it to a running JVM; readiness and running verification remain distinct.

The viewer has Requests, Saved captures, Performance and Services/capture settings
workspaces. Request graphs default to compact HTTP hops and Fit, retain one frame
per captured invocation, and show conservative causal/local arrow-order labels.
Fixed cards open an adjacent inspector, with focus controls and a bounded minimap.
Incoming query/body capture, copying, bounded request-data
keyword search, recorded caller navigation, distinct cache/queue cards and native
ClickHouse SQL/named-binding opt-ins are also available. None of these imply
complete path coverage, automatic cache-vendor detection or high-traffic readiness.
See [guided setup](guided-setup.md) and
[request/ClickHouse evidence](request-data-and-clickhouse.md).

**Large-trace paging and local persistence are implemented.** Upgraded agents
serve compact overviews separately from bounded detail pages. New viewer saves
use version-2 manifests/compressed chunks, support ZIP download/import, and pin
saved identities/source without silently reading live data. Legacy JSON snapshots
remain readable. Offline analysis uses a bounded prefix, not a complete streaming
index; see [formats, quotas and concurrency](java-capture-storage.md).

**Performance insights is implemented for local/dev on-demand use.** The viewer
checks up to ten newest matching captured requests or one selected saved graph
for slow JDBC executions, repeated SQL patterns, suspected N+1 and query-heavy
segments. It has adjustable thresholds, bounded reads, source/timestamp/partial
warnings, category filters and an occurrence picker linking exact retained
query/calling-context evidence. It does not enable
capture, save results automatically, collect continuous metrics or establish a
confirmed N+1 defect. See [Performance insights](performance-insights.md).

1. **Guided API investigation.** **Start an investigation** checks an exact
   backend path against running metadata and retained summaries. An explicitly
   armed 60/120-second **Watch next request & save** excludes old requests and
   saves one unambiguous newly completed request; cancellation, ambiguity and
   missing evidence remain explicit. CLI `diagnose` and MCP
   `diagnose_capture_request` expose read-only checks, not a background watch.
   No silent enablement, scope change, restart or request replay.
2. **Visual before/after comparison.** **Compare & search saved captures**
   compares an ordered Before/After pair offline. It leads with request outcomes,
   external calls and parent-level changed methods, with nested changes expandable.
   Service aliases label known destinations without rewriting matching identities;
   comparability gaps remain explicit. Inspecting evidence retains comparison
   context and a return action.
   **Use with CLI/MCP** explicitly creates a separate minimized copy, omitting
   source, user/context, headers, payloads and raw exception messages; it does
   not migrate or modify the full snapshot. Use the same `--capture-dir` across
   viewer, CLI and MCP. “No longer observed” is not deletion; a timing sample
   is not a performance benchmark.
3. **Find code in observed executions.** **Code flow** and **Call tree** search
   the bounded current model, reveal collapsed matches, and focus observed
   ancestors/descendants without invented async links or zoom changes. Reversible
   application-logic folding reduces wrapper noise; unplaced HTTP has its own
   search section and never gains an invented parent.
   **Open local source** targets the current checkout, not a verified captured
   build. **Search saved evidence** looks through full viewer files and minimized
   captures on demand; CLI `capture search` and MCP `search_saved_captures`
   read only minimized captures. Searches are bounded. No match means no
   match in searched evidence, not non-execution, unused code or full coverage.

**Local SQL inspection is also available.** PostgreSQL and MySQL JDBC capture
is opt-in, with a separate opt-in for bounded parameter values. The viewer groups
executions into **DB CALL** cards by service segment and recorded destination,
labels queries by SQL operation/table, and links to recorded calling context.
**SQL with captured values** fills only safely mapped, known values; the original
template and numbered, typed bindings remain available in disclosures. Missing,
unsupported or truncated values remain `?`; ambiguous mapping retains the
template. This is a reconstructed debugging preview, not exact wire SQL or proof
of transaction commit. Raw SQL/bindings can be sensitive. See
[SQL capture and its limits](jdbc-query-capture.md).

See the [developer workflow](developer-workflow.md) for commands and storage
boundaries, and the [viewer guide](request-focused-ui.md) for controls and limits.

## Planned extensions — not implemented

### 4. Evidence-backed change-impact reports

Extend the existing [instruction-only impact skill](impact-analysis-skill.md)
with repeatable reports tied to a Git diff and selected scenarios:

- **Observed reachability:** saved executions reached the changed code.
- **Potential impact:** source callers, consumers or documented contracts suggest
  additional affected flows.
- **Verified behavior:** supplied test assertions establish an outcome.
- **Unknown:** unexercised scenarios, unsupported instrumentation, ambiguous
  source/build identity or missing evidence prevent a conclusion.

Recommend concrete regression scenarios with code/runtime/documentation
references. Link actual test results when available. Do not invent a percentage
“safe to merge” score or equate a changed code path with a changed business result.
Start with bounded local files and on-demand analysis; a global dependency index
is not a prerequisite. Test/CI guardrails require validated, stable assertions and
separate agreement about enforcement.

### 5. Portable investigation bundles

Single-capture ZIP export/import is already implemented. Extend that into a
multi-artifact investigation handoff containing selected
baseline/candidate evidence, comparison summaries, reproduction notes, build/config
identifiers, supplied test results and coverage gaps. Recipients should be able
to inspect the bundle without the sender's services running.

Preview contents before export and make source/identity inclusion explicit.
Validate imported schemas, sizes and paths; never execute imported content.
Do not include credentials or captured payloads by default. Infrastructure names,
paths and developer notes may still be sensitive. No automatic upload, public
localhost link or central storage service is implied.

### 6. Selective dependency diagnostics

Add narrowly scoped diagnostics when real pilot investigations justify them:

- Repeated calls to the same downstream operation.
- Unexpected dependency activity after a code change.
- Caller-local timing across comparable repeated runs.
- Extend the implemented on-demand query-pattern checks with privacy-reviewed
  aggregation and measured JDBC overhead. Local PostgreSQL/MySQL SQL, bounded
  parameters, grouped inspection and execution timing are already implemented;
  see [SQL capture](jdbc-query-capture.md) and [Performance insights](performance-insights.md).
  The local normalizer is not an export sanitizer. Repetition is not proof of N+1
  or a performance regression.

`DB_ROUTE` currently records datasource selection, **not** SQL execution, query
count or successful persistence. Do not infer N+1 queries from route counts.
Queue submission is not delivery or completed consumption. Additional JDBC drivers,
HTTP/queue clients and deeper async correlation require specific adapters,
compatibility/privacy tests and measured overhead; matching names/times must
never fabricate causal links.

### 7. Shared-dev/sandbox code-to-flow impact map

**Future roadmap — proposed, not implemented.** Extend individual-request
inspection into continuous, bounded evidence collection for explicitly selected
dev/sandbox services and a shared, build-aware map of which observed APIs, jobs
and consumers use a method. A developer could select a method, API or Git diff,
inspect related flows and representative executions, and choose focused
regression tests. This builds on item 4; it is not a prerequisite for local
impact reports or authorization to deploy the current debug endpoints into a
shared environment.

#### Reduce blind sandbox waiting

Developers may leave critical features in sandboxes for long periods to catch
unexpected effects on other flows. Elapsed time alone does not identify which
scenarios actually exercised changed code, which related flows were missed, or
whether observed behavior meets its assertions.

The proposed workflow would keep the method/API-to-flow map current as selected
dev/sandbox scenarios run, relate a verified candidate build to its baseline, and
surface potentially affected flows plus the evidence and coverage gaps behind
each suggestion. Combine ordinary traffic with deliberate regression scenarios;
use representative examples to investigate a surprising relationship and turn
it into an explicit test when appropriate. Continuous collection is bounded by
scope, capture/sampling budgets and retention—not a promise to keep every
request or every SQL value indefinitely.

The goal is to focus regression testing and reduce blind waiting for incidental
traffic to reveal an affected flow. It does **not** eliminate tests, required
soak periods or release review, certify a feature as safe, or prove that
unobserved flows are unaffected. Any reduction in sandbox wait time must be
validated against known regressions and agreed release criteria; it is not an
automatic promotion or “safe to merge” decision.

#### Representative execution history

- Target **10–50 representative executions per flow**, configurable and subject
  to per-build/environment retention, expiry and total storage limits. This is
  a proposed starting budget, not an implemented default or coverage guarantee.
- Prefer distinct observed code/dependency paths, normal successes, different
  failures, slow cases and relevant configuration/feature-flag scenarios—not
  just the newest, most frequent or slowest requests. Deduplicate equivalent
  examples and report when the budget leaves variants without retained examples.
- Identify HTTP flows by entry service, method and normalized route rather than
  raw resource IDs. Keep jobs and consumers as separate entry points; link
  multi-request business scenarios through explicit test/scenario identifiers.
  Cross-service and async relationships still require recorded correlation.

#### Persistent, versioned method-to-flow index

- Store compact observed method-to-flow relationships separately from detailed
  examples, with observation counts, first/last seen, evidence references and
  capture/sampling limitations. Counts describe observed evidence, not unsampled
  traffic totals.
- Let summaries outlive individual examples under their own explicit retention
  policy. Expired examples must be marked unavailable, not presented as
  inspectable evidence. Do not erase an entire observed relationship merely
  because an example rotates out of the 10–50-request set.
- Use each service's verified deployed build/artifact identity, capture-config
  identity and full method signatures, including overloads. Keep builds separate
  and map diffs across revisions explicitly; local Git HEAD or a supplied version
  label alone is not proof of the code that ran.
- Combine runtime observations with static callers, API/event contracts and
  shared database/cache dependencies. Distinguish **observed reachability**,
  **potential impact**, **test-verified behavior** and **unknown coverage**.
  Reaching a changed method does not by itself establish a changed outcome.

#### Coverage and shared-dev readiness

Supplement ordinary dev traffic with deliberate integration/regression scenarios
and record their IDs. Expose known entry points/scenarios without observations,
stale builds, unsupported adapters, dropped/truncated evidence and sampling gaps.
Do not equate agent installation or many retained requests with complete path
coverage, or interpret an unobserved flow as safe to change.

Shared use requires new collection, durable storage/indexing, authenticated
access, environment isolation, redaction/data minimization, retention/deletion
controls and operational ownership. Do not expose today's loopback debug API as
an unauthenticated team service. Measure latency, throughput, CPU, heap/GC,
startup and capture loss before expanding scope; retaining fewer examples
reduces storage, not necessarily instrumentation cost. The existing local
workflow remains available without this shared infrastructure.

#### Proposed first milestone

Pilot **cb-app → cb-ems** with a few named API flows and test scenarios, including
a critical feature and known indirect regression cases. Establish build/symbol
identity, collect bounded diverse examples over an agreed dev/sandbox observation
window, build the reverse index, then validate method/API/diff impact reports
against known changes and explicit regression results. Track missed relationships,
false impact suggestions, coverage gaps, time to discover affected flows,
regression-selection usefulness, investigation time, storage and overhead. Compare
with the existing sandbox workflow before claiming reduced wait time. Expand to
more APIs, jobs and consumers only after the pilot meets agreed usefulness,
privacy and performance gates. No automatic mutating request replay,
merge-blocking decision or production rollout is included.

### 8. High-traffic performance monitoring

**Future roadmap — not implemented.** Evolve the on-demand Performance insights
tab into bounded monitoring for explicitly approved dev/sandbox environments
before considering higher-traffic deployments. Keep full method/line/SQL-value
capture limited to selected requests or short investigations.

- Aggregate normalized operation/query fingerprints with bounded cardinality,
  per-service budgets, explicit sampling and observable drop/backpressure counts.
- Track query counts, caller-local duration distributions and repeated-query
  candidates by API/service/build over time; retain a small representative set of
  slow/error/N+1-candidate examples with drill-down evidence.
- Compare equivalent build/workload baselines and track suspected regressions;
  do not derive traffic-wide percentiles or rates from biased retained samples.
- Add allowlists, default literal/parameter minimization, retention, access
  controls and reviewed sharing rules before shared storage or alert delivery.
- Benchmark throughput, latency, CPU, heap/GC, network and storage against
  agent-off baselines. Bound cost under overload and test failure isolation
  before claiming high-traffic readiness.

This requires collection and storage work, not just another UI tab. The current
local debugging configuration is not a recommendation to trace all methods,
lines or SQL values for every request on a busy environment.

## Supporting work and boundaries

- Extend the implemented recording provenance (agent-JAR digest/effective-config
  fingerprint), optional method descriptors and runtime-local thread tokens with
  verified application-build/source alignment. A version label, viewer checkout
  or local HEAD is not proof of the running application's revision. Legacy
  name-only methods remain ambiguous; thread tokens are not cross-JVM identities.
- Complete streaming analysis beyond today's bounded saved-event prefixes only
  when pilot cases justify the indexing, storage and resource-cost trade-offs.
- Measure capture loss, latency, throughput, CPU, heap/GC and startup cost under
  representative agent-off, narrow-scope and broader-scope workloads.
- Keep shared history/indexing optional and gated by pilot value, measured
  overhead and access/privacy controls, as proposed in item 7. Continue to defer
  a full documentation mirror, automatic replay of mutating APIs and production
  rollout; those are not implied by the shared-dev proposal.
- Long-window “not observed” findings may be review candidates, never automatic
  proof of dead code.

Choose the next extension based on successful investigations, missed/false
findings, setup friction and independent reuse—not on diagram size.
