# TraceLens productivity pilot

## Engineering presentation guide — handoff, 2026-10-28

- Done: created the editable [engineering demo guide](../build/presentation/TraceLens-Engineering-Demo-Guide.docx), with a 30-minute run of show, presenter talk tracks, click paths, expected observations, a 10-minute route, Q&A, optional live investigation, fallback steps and all nine demo links plus the fix-comparison pair.
- Verified: rendered and visually reviewed all 12 pages; checked 11 distinct capture destinations and six existing local reference links; independently reviewed factual claims against current docs. The final DOCX passes structural/text checks; no application code or runtime tests were needed for this document-only task.
- Left: rehearse the linked examples in the viewer before presenting. Port 7781 was not reachable during preparation, so this session did not re-verify capture availability or live UI behavior.
- Risks: localhost links work only with the presenter's saved store; the nine original demos are legacy snapshots. The guide keeps observation separate from coverage, performance causality and verified build/input equivalence, and warns against projecting sensitive retained data.
- Assumptions: a 30-minute engineering demo plus 10 minutes of Q&A, using saved examples rather than new business requests. No application restart, configuration change, capture mutation, Notion update or commit was performed.

## Documentation synchronization — handoff, 2026-10-28

- Done: updated the root README and all 27 guides in the [documentation index](README.md) for the current setup/configuration dashboard, three capture levels, four viewer workspaces, fixed-card inspector, behavior-first comparison, Performance insights and V2 storage. Simplified the Notion product, setup and How TraceLens works pages; kept EMS as a shared-guide pointer and consolidated the obsolete internals walkthrough into a technical reference. Replaced the setup attachment with `tracelens-setup-2026-10-28.zip` from `build/distributions/tracelens-java-0.1.1-SNAPSHOT.zip`; preserved product child pages and the dated instruction-only skill attachment.
- Verified: 28 Markdown files, 396 local links, 92 anchors and 17 complete JSON examples checked with zero errors; `git diff --check -- README.md docs` and bundle `unzip -t` passed. The eight targeted Node UI test files below passed 270 tests, zero failed/skipped. Notion returned successful updates without unresolved warnings; the ZIP upload returned `uploaded` for 10,479,630 bytes, containing only the combined JAR, setup script and two portable configs.
- Left: none for this documentation/download synchronization. Installing the new bundle into application JVMs or generating fresh captures was not part of this request; the existing nine demo recordings remain the original October 26 evidence.
- Risks: new V2 archive actions do not apply to the nine legacy JSON demos. Bounded/partial evidence cannot prove coverage, a skipped branch or performance causality. Historical validation records and still-supported optional adapters remain explicitly labeled references, not recommended first-time setup or claims about currently running agents.
- Assumptions: “all docs” means the repository's user/maintainer guides and the five related TraceLens Notion pages, not unrelated workspace pages or rewriting historical decisions. Existing source/configuration changes and deletions were preserved; no application, business data or saved capture was changed, and no commit was made.

Targeted validation command:

```bash
node --test tracelens-cli/tests/workflow-app.test.cjs tracelens-cli/tests/trace-graph.test.cjs tracelens-cli/tests/code-flow.test.cjs tracelens-cli/tests/performance-ui.test.cjs tracelens-cli/tests/capture-tools.test.cjs tracelens-cli/tests/saved-captures-ui.test.cjs tracelens-cli/tests/error-count-model.test.cjs tracelens-cli/tests/large-trace.test.cjs
```

Retained command output: `tests 270; pass 270; fail 0; cancelled 0; skipped 0; todo 0` (exit 0).

This POC succeeds only if developers solve real problems faster than with their
existing IDE assistant, source navigation and logs. Feature count and resemblance
to a commercial product are not acceptance criteria.

## Proposed pilot, not measured results

Run a two-week pilot with 3–5 developers. These are starting targets to agree
with the team, not claims about current performance:

- First useful capture within 10 minutes after application/build prerequisites
  are available. Count setup friction separately from application startup time.
- Routine API investigation without manually copying trace IDs or editing configuration.
- A meaningful median reduction in investigation time; initially aim for roughly
  25% on comparable tasks, while retaining correctness.
- Independent voluntary reuse after the introductory walkthrough.
- No application correctness regressions and measured, agreed runtime overhead.

Prioritize only three tasks: explain a reproduced failure, understand an observed
path/dependency, and compare a controlled before/after scenario. Use the
[implemented viewer/CLI/MCP workflow](developer-workflow.md). The viewer now
supports guided diagnosis/watch, offline snapshot comparison and bounded
execution/saved-evidence search. Keep [planned extensions](roadmap.md) separate
from capabilities included in the installed build.

For the local pilot, follow [guided setup](guided-setup.md), then use its generated
`check-setup.sh` and the viewer's service readiness checks to record setup friction
separately from investigation. `chargebee-app` is the checkout/lifecycle name;
`cb-app` is the bundled TraceLens service alias. Do not count a saved configuration
as a successful rollout: verify the running agent and a newly reproduced request.
Save needed evidence before a separately reviewed restart. Existing checkout
launcher users should follow the [integration guide](cb-app-integration.md) for
their launcher's distinct restart/log/job effects.

## Demo captures reopened — handoff, 2026-10-28

- Done: found and reopened all nine original numbered demo snapshots; started the rebuilt Java viewer on loopback port 7781 using the existing snapshot namespace. All 15 saved captures were preserved, with no duplicates or business API calls.
- Verified: all nine snapshot GETs returned 200 with matching IDs and nonempty segments; file validation found retained events across 25 segments. Browser checks confirmed the nine-segment cb-app/EMS/GO/Ledger graph and all nine named entries in Saved captures.
- Left: none for reopening the original demo set. Fresh recapture and persistent replacement of the installed launcher/JAR were not performed.
- Risks: these are original 2026-10-26 partial recordings, not fresh requests or complete coverage. Plans is an authentication-failure example; the hidden downstream-failure sample is not a successful grant. Source display still depends on a matching checkout.
- Assumptions: the requested demo set means the earlier nine scenarios; reuse avoids duplicate local grants and feature creation. Current service configurations and application processes were left untouched.

## Investigation workspace UX — decision, 2026-10-28

### Implementation handoff — 2026-10-28

- Done: four workspace destinations, compact connection status, fixed graph cards with an adjacent inspector/focus/minimap, ordered two-capture selection and comparison return context, reversible application-logic folding, separate unplaced-HTTP search, Performance category/occurrence controls, full timeline event inspection, and clearer setup policy/readiness guidance; Java ZIP rebuilt.
- Verified: `node --test tracelens-cli/tests/*.test.cjs` passed 875 tests; `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home ./gradlew :tracelens-tool:test :tracelens-agent:javaBundle --offline` passed 1,535 Java tool tests (zero skipped) and built the bundle. Packaged viewer/setup assets match source. Browser checks exercised synthetic saved comparison → exact evidence → comparison return, saved-list navigation, timeline details/Escape, the graph inspector at normal and narrow widths, and the packaged setup's Methods-only policy preview without saving. `git diff --check` passed.
- Left: install the rebuilt bundle into the regular viewer and verify a fresh real-service request when explicitly requested. This implementation did not restart applications, change live configuration, alter existing captures, or update Notion. The synthetic preview uses isolated storage and loopback port 17881; its setup-check server was stopped.
- Risks: bounded/partial captures still cannot establish coverage, input equivalence, skipped code or performance causality. Folding changes presentation only; unplaced HTTP stays unattributed. Initial verification caught stale pair-selection/focus/navigation issues, a hidden-panel CSS conflict and narrow-screen inspector issues; fixes and regressions are included. No tests or safety checks were disabled, and no dependency was added.
- Assumptions: this is the approved local-development UI/UX milestone, not a shared-environment rollout or runtime-overhead claim. Existing unrelated worktree changes and deletions were preserved; no commit was made.

Touched UX surfaces, on top of the existing worktree:

- Viewer under `tracelens-cli/tracelens_cli/web/`: `app.js`, `index.html`, `request-ui.js`, `style.css`, `trace-graph.js` / `.css`, `capture-tools.js` / `.css`, `code-flow.js` / `.css`, `performance-ui.js`, `performance.css`, and `guided-investigation.js` / `.css`.
- Setup resources under `tracelens-tool/src/main/resources/tracelens-setup/`: `setup.html`, `setup.js`, and `setup.css`; existing lifecycle consent, same-origin protections and absolute session expiry remain intact.
- Tests under `tracelens-cli/tests/`: graph/sequence, code-flow, Performance UI, setup-wizard, guided-investigation, saved-captures UI, capture-tools, workflow-app, segment-flow UI, context-help and help UI regressions.
- Documentation: `docs/guided-setup.md`, `docs/request-focused-ui.md`, this handoff, and the viewer's What is TraceLens guide. Reviewed the touched UX sections and packaged assets; the much larger pre-existing repository diff is not part of this milestone.

Keep captured evidence and backend contracts unchanged while making the viewer
task-oriented: Requests, Saved captures, Performance, and Services/capture settings.
Use compact graph nodes with an adjacent inspector instead of growing nodes;
preserve camera, selection, exact ownership and lazy detail reads. Keep comparison
context when inspecting saved evidence and show comparability gaps before drawing
conclusions. Application-logic folding is reversible presentation, not deletion.

The alternative of adding more inline controls and help paragraphs was rejected:
it increases layout movement and makes developers reconstruct the next action.
Reuse existing bounded models, request/SQL renderers, setup lifecycle consent and
the expiring setup session; do not create an always-running configuration endpoint.
Untrusted labels remain text, stale asynchronous detail must never replace the
current selection, and missing evidence stays unknown. First expected failures
are oversized flows, unavailable details and expired setup sessions; each must
retain a clear next action without live-data substitution or automatic restart.
No new dependencies, instrumentation, service configuration or live deployment
are part of this implementation. Revisit after the three pilot investigation tasks.

## Architecture hardening — decision, 2026-10-28

### Implementation handoff — 2026-10-28

- Done: fail-open callbacks, bounded propagation, identity-preserving executor hooks, strong event identity/provenance, bounded reads/source caching, concurrent chunk staging, diagnostics and one setup-service catalog; Java ZIP rebuilt without installing it.
- Verified: 1,875 Java tests passed (six opt-in real-database tests skipped), 853 Node tests passed, Java 8 agent/servlet/packaged CLI-viewer-MCP smoke checks passed, and the four-mode synthetic benchmark completed. Cross-JVM download/delete/rename and missing-lock cleanup regressions passed; `git diff --check` is clean.
- Left: fresh real-service rollout verification, representative concurrency/overhead measurements, full streaming analysis beyond bounded prefixes, and the shared-environment collector/authentication/retention milestone. Running applications, configurations, saved captures and Notion were not changed.
- Risks: implicit executor propagation deliberately skips unsupported executor/queue shapes and direct queue mutation; overload can quarantine correlation. Opt-in SQS attributes can still exceed total message capacity near its ceiling. Legacy identities and missing invocation tokens retain ambiguity; metadata does not establish execution coverage.
- Assumptions: this is a local-development hardening release, not approval for shared or production deployment. The initial broader run exposed a diagnostics-size bug plus three test-fixture problems; these were corrected and the complete suites rerun without weakening protections or disabling tests.

Implementation inventory (on top of the pre-existing worktree changes):

- Agent/bridge: `TraceRuntime`, `TraceSnapshot`, `ExecutorTraceRuntime`, `AgentStatus`, `TraceLensAgent`, `MethodTraceAdvice`, `ExecutorTraceAdvice`, `SqsProducerAdvice`, `DbRouteAdvice`, `ExternalCallAdvice`, `ScheduledJobAdvice`, `JdbcInstrumentation`, `ClickHouseInstrumentation`, and `DataStoreInstrumentation`.
- Core: `TraceEvent`, `RuntimeThreadIdentity`, `TraceSink`, `Trace`, `TraceExporter`, `TraceProjection`, and `CaptureProvenance`.
- Tool: `BoundedReads`, `BoundedEventPages`, `DebugClient`, `Federation`, `Source`, `ViewerServer`, `CaptureStore`, `ChunkedSnapshotStore`, `Captures`, `CaptureTools`, `BehaviorComparison`, `Execution`, `Dependencies`, `Doctor`, `SetupServiceCatalog`, `SetupInstaller`, and `SetupServiceLifecycle`.
- Viewer: `app.js`, `capture-tools.js`, `boundary-source.js`, and `dependency-ui.js` under `tracelens-cli/tracelens_cli/web/`; corresponding timeline, caller-lookup and comparison regressions under `tracelens-cli/tests/`.
- Verification: new identity/provenance, callback failure, executor capacity, bounded reads, source caching, capture architecture/concurrency/process-lock/lock-lifetime, Doctor integration and setup-catalog tests; updated Java 8 agent smoke assertion. `tracelens-agent/build.gradle` adds an isolated benchmark source set with `BenchmarkMain` and `SyntheticWorkload`; no new third-party dependency.
- Documentation: this page, `byte-buddy-instrumentation.md`, `codebase-walkthrough.md`, `distributed-tracing.md`, `developer-workflow.md`, `java-capture-storage.md`, and `performance-insights.md` in the existing `docs/` directory. Existing unrelated edits/deletions were preserved; no commit was made.

The smoke benchmark retained 0 / 1 / 43 / 146 events per request for agent-off /
overview / methods / detailed respectively, with identical workload checksums
and zero observer failures. These 1,000-request synthetic runs validate the harness,
not a service overhead claim. Run instructions and measurement limitations are in
[Performance insights](performance-insights.md#measuring-agent-overhead).

Harden the existing local-first modules before adding a collector or a new storage
engine. Preserve one Java distribution, saved evidence, existing capture limits,
and backward-compatible readers. Observer failures must not replace application
results; propagation carries a bounded identity contract, not diagnostic payloads.
Executor propagation must preserve task contracts or explicitly omit unsupported
cases. Prefer a visible evidence gap to changing application scheduling.

Bound complete viewer operations as well as individual reads. Stage immutable
snapshot chunks outside the publication lock, reserve capacity conservatively,
and publish only after rechecking quotas. A failed or interrupted write remains
private and cannot appear as a completed capture. Treat imported labels, events,
chunks and source paths as untrusted; retain ownership, integrity and size checks.
Use operation-local source caches rather than stale global checkout caches.

Add optional strong event identities while retaining legacy uncertainty. Keep
raw chunks separate from bounded analytic projections; do not mistake a prefix
for complete coverage. Runtime capability evidence must distinguish configured
support from actually observed instrumentation. Overhead improvements require
agent-off/overview/method/line measurements, not a claim based on unit tests.

Alternative rejected: raising all limits, rewriting the UI, or adding a distributed
database now. Those increase cost without first addressing observer safety and
bounded work. Shared-environment collection, authentication and retention remain
a separate deployment milestone; no local applications are restarted by this
implementation. Revisit after representative overhead measurements and pilot
feedback.

## First useful trace milestone — decision, 2026-10-28

Prioritize activation and evidence interpretation before adding capture types or
another top-level view. Reuse the existing readiness/watch controls from setup
and empty request lists, make saved capture scope explicit, and summarize
observed failures and evidence gaps beside the request. The alternative—more
help text and independent workflow screens—would leave developers assembling
the same investigation manually. Revisit after the pilot identifies remaining
friction; the broader service overview and investigation-report work is separate.

The setup handoff only opens an investigation draft. A list's substring search
must be reviewed as an exact backend path before checks or watching. Neither a
link nor a helper starts recording, replays a request, changes configuration or
restarts a service. Restart review and consent remain separate. Saved settings,
agent connectivity and proven running settings are not interchangeable.

Request insights use bounded, already-loaded metadata, not a new event scan or
background payload read. Captured labels are untrusted text; navigation must
retain exact segment/owner identity. Partial, malformed, unavailable or oversized
evidence must remain unknown rather than become a healthy verdict or a root-cause
claim. Snapshot navigation never substitutes current service evidence. These
constraints protect against misleading captures and avoid additional collection
or analysis overhead; they do not establish the agent's runtime overhead.

### Implementation handoff — 2026-10-28

- Done: setup policy/verification guidance, explicit investigation drafts, and bounded request-evidence summaries; combined Java ZIP rebuilt.
- Verified: 816 Node tests and 1,461 Java tool tests passed; browser checks covered draft review, exact saved-service navigation, and the packaged setup's Methods preview without saving configuration.
- Left: deploy the rebuilt viewer/setup bundle and verify a fresh application request; broader PM-review items remain separate milestones. No live services, configurations, captures or Notion pages changed.
- Risks: one intermediate Java run failed an existing lifecycle fixture; focused and full reruns passed. Released ephemeral-port reuse is suspected, not confirmed. No tests or protection checks were weakened.
- Assumptions: this milestone is for the existing local-development workflow. Browser evidence used a synthetic request; runtime overhead, production safety and a real-service rollout are not established by these checks.

Changed surfaces for this milestone (on top of the existing worktree):

- Viewer: `web/app.js`, `web/index.html`, `web/request-ui.js`, `web/guided-investigation.js`, `web/guided-investigation.css`, and new `web/request-insights.js` / `web/request-insights.css` under `tracelens-cli/tracelens_cli/`.
- Setup: `setup.html`, `setup.js`, `setup.css` under `tracelens-tool/src/main/resources/tracelens-setup/`.
- Tests: `workflow-app.test.cjs`, `guided-investigation.test.cjs`, `setup-wizard.test.cjs`, and new `request-recovery.test.cjs` / `request-insights.test.cjs` under `tracelens-cli/tests/`.
- Documentation: this page, `docs/guided-setup.md`, and `docs/request-focused-ui.md`. No new dependencies, instrumentation hooks or backend/configuration contracts.

## Saved behavior comparison — decision, 2026-10-28

### External-call names handoff — 2026-10-28

- **Done:** behavior-review external calls use exact, per-caller configured display names; recorded addresses stay visible, caller ownership is separate, and filtering accepts either name or address. Unknown/ambiguous mappings retain addresses. Only the isolated preview on 7791 was restarted, using the existing `scripts/services-local.json` aliases.
- **Verified:** 1,482 Java tool tests and 842 JavaScript tests passed; combined JAR build and whitespace check passed. The actual UBB-4005 pair returned `cb-ems` for 8082 and `cb-plans` for 8081, HTTP 200 and 65,449 response bytes. Counts and raw method totals remained unchanged; served assets match the checkout.
- **Left:** final browser visual QA could not be completed: the prior preview tab was closed during work and opening a new tab returned `ERR_BLOCKED_BY_CLIENT`. No browser protection was bypassed. Preview: `http://127.0.0.1:7791/`; the main viewer on 7781 and applications are unchanged.
- **Risks:** labels reflect current explicit configuration, not captured downstream execution or verified historical service ownership. The UI marks configured labels; saved files, raw comparison identities and evidence links are unchanged. No aliases are inferred from ports or debug endpoint URLs.
- **Assumptions / files:** the existing local alias map applies to this local pair. Changed five Java files (`BehaviorComparison.java`, `CaptureTools.java`, `ViewerServer.java`, `BehaviorComparisonTest.java`, `LargeTraceViewerTest.java`), `web/capture-tools.js`, `web/capture-tools.css`, `tests/capture-tools.test.cjs`, `docs/developer-workflow.md`, `docs/dependency-visibility-and-change-impact.md`, this note, and the isolated temporary preview configuration. No new dependencies, live-service settings, instrumentation or Notion changes.

### External-call display names — decision, 2026-10-28

Use the viewer's existing exact, per-caller `dependencyAliases` to label external
calls in the behavior comparison. Older saved captures may retain only addresses;
guessing a service from its port or treating today's label as historical execution
would be misleading. Keep the captured address visible, mark configured labels,
and retain the original comparison identities, counts and saved evidence links.
Unknown or conflicting mappings remain addresses. This display-only lookup adds
no service requests and never edits captures; labels are included within the
existing response budget. Revisit if capture-time alias reconciliation is added.

### Parent-method grouping — decision, 2026-10-28

Show changed method observations under their outermost supported recorded parent,
with nested differences collapsed by default. A flat list overstates independent
changes when one branch accounts for many child methods. Parent counts can remain
unchanged while descendants differ, so label that case as nested differences,
not a changed parent implementation. Keep the raw method comparison and pair search.
Use only unique, consistent captured nesting that accounts for the child's recorded
invocations; leave ambiguous, missing and recursive parent relationships separate.
Group before response pruning, preserve exact saved evidence references, and expose
nested omissions. This is presentation of observed nesting, not a source-code diff
or proof of the full call graph; revisit when invocation-level alignment is available.

The grouped review reconstructs nesting from the same saved event prefix using
the viewer's existing 128 KiB model budget. The compact 32 KiB projection was
discarding supported parent relationships in the pilot pair. Keep that compact
projection unchanged for the raw comparison, exports and search; no live reads
or new instrumentation are needed to recover the additional saved nesting.
Enrich only nesting for matching saved service/segment/instance identities; the
compact projection remains authoritative for segments and other observations.
Reserve up to 48 KiB of the existing 64 KiB comparison response for the grouped
review so parent rows do not crowd out every expandable child. Advanced rows
may be pruned further; their totals and explicit omission counts remain intact.

### Parent-grouping handoff — 2026-10-28

- **Done:** changed methods show outermost supported recorded parents, with lazy child disclosures and child-aware search. An unchanged parent is labelled as containing nested differences. Expanded saved nesting is merged by exact identity without changing compact capture scope; raw totals and the 64 KiB response ceiling are preserved.
- **Verified:** 1,480 Java viewer tests and 839 JavaScript tests passed; combined JAR build and whitespace check passed. The isolated preview's real comparison endpoint returned 27 parent/standalone groups for the UBB-4005 pair's 107 raw method differences, with 45 child rows retained and 36 explicitly omitted. Its served JavaScript/CSS match the checkout. `CartEstimate.toJson()` retains all 15 nested differences in this response.
- **Left:** browser visual verification remains blocked by Chrome `ERR_BLOCKED_BY_CLIENT`; no screenshot or visual QA success is claimed. Updated preview: `http://127.0.0.1:7791/`. The main viewer on 7781, applications, configurations and original captures were not changed.
- **Risks:** missing, shared, changing or recursive parent relationships remain separate. Nesting is observed instrumentation evidence, not proof of a direct caller or a source edit; large results still have explicit omissions. An earlier full test attempt hit an unrelated ephemeral-port bind failure; the subsequent full runs passed without weakening tests.
- **Assumptions / files:** this remains a preview-only local workflow. This step changed `BehaviorComparison.java`, `CaptureTools.java`, `Captures.java`, `BehaviorComparisonTest.java`, `web/capture-tools.js`, `web/capture-tools.css`, `tests/capture-tools.test.cjs`, `web/index.html`, `docs/developer-workflow.md`, `docs/request-focused-ui.md` and this note. No new dependencies or instrumentation hooks; unrelated worktree edits were preserved.

### Implementation handoff — 2026-10-28

- **Done:** added paired outcomes, dependency and method findings, focused inspection, selected-pair saved search, exact saved-service navigation, compact limits and an advanced count report. Restored missing saved HTTP observations using exact owner identity; no new agent hooks or dependencies.
- **Verified:** 1,470 Java viewer tests and 835 JavaScript tests passed; JAR build and diff whitespace check passed. Read-only checks against the two UBB-4005 saved copies returned HTTP 404 → 200, the EMS entitlement call before-only, and pair-scoped path/method search results only in the before capture.
- **Left:** browser visual verification is blocked by Chrome `ERR_BLOCKED_BY_CLIENT`. The isolated viewer is on `http://127.0.0.1:7791/`; the existing viewer on 7781 and applications were not restarted or replaced. No screenshot or visual QA success is claimed.
- **Risks:** comparison remains bounded saved evidence, with literal path correspondence and class/method identities (not overload-aware invocation alignment). Missing observations do not prove deliberate skipping; current source/revision equivalence remains unverified. Explicit decision markers and an execution-path diff are deferred.
- **Assumptions:** preview-only deployment and copies of the two selected captures were sufficient for this iteration. Changed files: `CaptureTools.java`, new `BehaviorComparison.java`, `ViewerServer.java`, `BehaviorComparisonTest.java`, `LargeTraceViewerTest.java`, `capture-tools.js`, `capture-tools.css`, `capture-tools.test.cjs`, `app.js`, `workflow-app.test.cjs`, `index.html`, `docs/developer-workflow.md`, `docs/request-focused-ui.md` and this document. Existing unrelated worktree changes remain untouched.

Lead a before/after review with paired response outcomes and observed dependency
changes, followed by focused method evidence and search within the selected pair.
Keep the existing count/line report behind an advanced disclosure. A count-first
report makes the developer reconstruct the behavioral difference; a merged graph
would risk presenting two executions as one causal path.

Restore saved HTTP observations to the offline projection using exact captured
ownership. Compare status separately from call identity, preserve literal paths,
and retain exact saved segment references for navigation. Repeated or ambiguous
matches remain explicit; absence means not observed, never deleted code or proof
that a guard skipped a call. Explicit guard/decision instrumentation is deferred.

The implementation uses bounded saved metadata and existing projections, with no
live-service fallback, replay, new collection hooks or payload search. Untrusted
capture labels remain text. Missing chunks, truncated projections, mismatched
scope/revisions and unavailable evidence must remain visible. Matching identifiers
is not proof of equivalent inputs, equivalent source or regression safety. Revisit
after controlled before/after pilot scenarios expose remaining ambiguity.

## Record one row per task

Copy this table into your team's agreed review document; no automatic analytics,
developer surveillance or telemetry is enabled by this implementation.

| Task/scenario | Tooling used | Setup minutes | Investigation minutes | Correct result verified by | Useful evidence | Friction / misleading evidence | Would reuse? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Example: feature API failure | Existing IDE + logs, or IDE + TraceLens | Not measured | Not measured | Test / reproduction / peer review | Not measured | Not measured | Not measured |

Use comparable tasks and rotate tool/order assignments where practical. Repeating
the same problem after learning the answer is not a fair speed comparison.
Record failures, abandoned attempts and cases where existing logs were sufficient.
Do not turn a small POC sample into a statistically established organization-wide
productivity claim.

## Measure existing instrumentation separately

Use an approved local/test workload, the same application build and inputs, a
warmup, multiple measured runs, and both agent-off and intended-agent-config runs.
Record latency distribution, throughput, CPU, heap/GC, errors, startup time and
capture loss/limits. Keep database/network state comparable and report variation.
Compare no-agent, narrow-scope capture and any broader proposed scope separately.

Agree the acceptable budget **before** rollout. The investigation/comparison/search
viewer/CLI changes introduce no new Java callbacks; that does not establish that
existing tracing or separately added agent hooks are cheap.
Doctor readiness is not a performance, privacy or production-readiness certificate.

## Decide at the end

- **Continue** when developers independently reuse it and correct investigations
  demonstrably save net time, including setup and maintenance.
- **Narrow the scope** when one workflow helps but others add complexity or noise.
- **Stop/pivot** when benefits do not exceed integration, overhead and maintenance
  costs. Record the evidence instead of adding features to justify prior effort.

Defer a database platform, global architecture analytics, dead-code suggestions,
document ingestion and production rollout until the core workflow demonstrates
value. Automated source-aware impact analysis and durable team history remain
separate future work, not requirements for this pilot.
