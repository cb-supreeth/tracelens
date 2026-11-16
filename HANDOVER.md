# TraceLens handover

## Latest session — 2026-11-12 — continuation guide

**Scope:** TraceLens is a local-first Java execution tracer for investigating
reproduced requests across Java services. It is a proof of concept for local
development and controlled development/sandbox investigations; it is not a
production APM service or a coverage guarantee.

**Repository:** `/Users/supreeth/work/cb-tracelens`

**Audience:** the next engineer or LLM taking over TraceLens work.

## Latest session — 2026-11-13 — demo recording prep (model: Opus 5.5)

**Done:** Wrote `build/presentation/TraceLens-10-Minute-Recording-Script.docx`
(six-chapter Trainn script, built from the 30-minute guide's styles, with links
to the saved captures) and created the Notion page "TraceLens FAQ" under the
TraceLens page. Backed up `~/.tracelens/setup/viewer-snapshots` to
`~/.tracelens/setup/viewer-snapshots.backup-20261113` (71 files, identical).

**Verified:** In the current build (`tracelens-agent-0.1.1-SNAPSHOT-agent.jar`,
run on port 7792 against the real captures), chapters 1–4 were clicked through:
09's "Evidence to inspect" panel, the UBB-4005 comparison ("What changed?":
404→200, EMS `subscription_entitlements` 404→not observed,
`attributeUsagesToItems` 2→1 invocations), example 01 query #237 → "View
calling code" → `FeatureValidator:325`, and the 02/03 cache cards. The
`attributeUsagesToItems` guard was confirmed in chargebee-app merge
`cd5855316e85`.

**Broken or risky:** `~/.tracelens/setup/start-viewer.sh` still starts the
28 October build, whose comparison shows "Dependencies 0 changes" and cannot
tell the UBB-4005 story; the script says to start the current build instead.
Chapter 6 (`setup.sh` screen) and the recording-size layout are unrehearsed.
The FAQ's per-service "turn it off" answer is verified for cb-app only.

**Do not touch:** The saved captures and their backup; the user's viewer in
terminal tab 0.

**Update (same day):** The user now runs the current build on port 7781.
Added script chapter 4, "One request across GO and Ledger" (example 05),
making seven chapters (about 714 spoken words). Rehearsed in the viewer: GO
MySQL card → #1460 `grant_transactions` UPDATE → captured parameter #6
`tl-go-demo-1026-promo-ok` → View calling code →
`GrantTransactionRepositoryImpl.updateAll():327`, with
`PromotionalGrantServiceImpl:159/166/167` just above. Call order taken from
the snapshot's segment parents: GO calls cb-app `active_credits`, Ledger
`accounts`, cb-app `custom_pricing_units`, then Ledger `allocations`, and
Ledger calls back to cb-app `custom_pricing_units`. Chapter 7 (`setup.sh`)
is still unrehearsed.

**Update 2026-11-16 — Notion "How TraceLens works" rewritten for engineers:**
Deep technical version: 30-second model with mermaid diagram, module map, life of
one request (sequence diagram), instrumentation toggles (matchers, advice,
`LineEmittingVisitor`, bootstrap bridge), context/executor propagation, event
types, `TraceStore` limits, boundaries, debug API and its loopback guards,
reader/storage, overhead, "reading absence" table, known limits. Facts checked
against `TraceLensConfig`, `TraceStore`, `StandaloneRuntime`, `TraceSink`
(JDBC is `EXTERNAL_CALL`). Previous text is in Notion version history.
Not checked: whether Notion shows the mermaid blocks as diagrams by default.

**Update 2026-11-13 (later) — Notion refresh (user approved "apply all"):**
Checked every Notion claim against the working tree, then restructured the
pages. New page "What can I use TraceLens for?" (from `docs/use-cases.md`).
Rewrote the TraceLens home page and the setup page (renamed "Get started with
TraceLens"). Corrected "How TraceLens works" and the FAQ: capture levels are
Overview / Methods / Detailed in the UI, two-step compare ("What changed?"),
SQS single-send-only propagation, Spring Data Redis / AWS SDK v1 scope, guided
capture paths under `~/.tracelens/setup/`, MCP `--mcp-expose-sql`. The EMS page
and technical reference now redirect. Rebuilt `javaBundle` (JAR from 11-12,
includes `get_segment_sql`) and uploaded it as `tracelens-setup-2026-11-13.zip`,
replacing the 10-28 ZIP. The 10-28 ZIP backup is in the session scratchpad only.
Not done: screenshots on the home page; the impact-analysis skill ZIP is still
the 10-19 package. README, this file and `docs/` now use Overview / Methods / Detailed.

## Latest session — 2026-11-12 — opt-in SQL over MCP (model: claude-fable-5-2)

**Done:** Added the MCP tool `get_segment_sql`, which returns captured SQL,
bindings and the innermost observed method for one segment. It is listed and
callable only when the operator sets `viewer.mcpExposeSql: true` or passes
`--mcp-expose-sql`; the default is off and every other MCP tool still strips
SQL. Files: `ToolConfig.java`, `McpServer.java`, `mcp-tools.json`,
`McpServerTest.java`, README MCP table, `docs/jdbc-query-capture.md`,
`docs/java-distribution.md`.

**Verified:** `./gradlew :tracelens-tool:test` — 1,538 tests, 0 failures, 0
skipped, including three new `McpServerTest` cases (hidden/rejected when off,
SQL + bindings + filter + paging when on, 64 KiB budget). No service was
started and no real MCP client was connected.

**In progress:** Nothing.

**Broken or risky:** Enabling the flag sends SQL literals and bound values to
the model provider; bound values can carry prompt-injection text. Not verified:
a real agent calling the tool against live cb-app/cb-ems, the Java 8 tool smoke,
the combined-JAR bundle, and that guided setup edits preserve
`viewer.mcpExposeSql`. The Notion pages are not updated. The legacy Python MCP
server has no equivalent tool.

**Next:** Rebuild the combined JAR, register the MCP server in Claude Code and
run a real smoke test (`tracelens_doctor`, `investigate_request`,
`get_segment_sql`) against a local reproduction with synthetic data.

**Do not touch:** Credentials, saved captures, unrelated dirty worktree paths,
running services.

**Update 2026-11-12 (later, same session) — packaged smoke:**
`./gradlew :tracelens-agent:shadowJar :tracelens-agent:java8ToolSmoke
:tracelens-agent:java8Smoke` passed. The packaged
`tracelens-agent-0.1.1-SNAPSHOT-agent.jar` was then run as a real `mcp` stdio
process against a stub debug API serving a synthetic segment: with the flag off
it listed 22 tools and rejected `get_segment_sql`; with `--mcp-expose-sql` it
printed the stderr warning, listed 23 tools and returned the filtered SQL,
bindings and enclosing method. Still not done: a live run. The local cb-app
(8080) and cb-ems (8082) were running from the `ubb4197` worktrees without
`-javaagent`, started by another session, so they were left untouched. A live
run needs those services restarted with the agent and `captureJdbcQueries` /
`captureJdbcParameters` on, which needs explicit approval.

**Update 2026-11-12 19:55 IST — live cb-app run (user approved the restart):**
Restarted cb-app from `~/work/wt/chargebee-app-ubb4197` with
`make start TRACELENS=1`. The Tomcat `setenv.sh` there attaches the installed
agent `~/.tracelens/setup/large-trace-20261028/tracelens.jar` with
`~/.tracelens/setup/cb-app/tracelens.json` (JDBC queries + parameters on, debug
port 7778). The MCP process was the newly built
`tracelens-agent-0.1.1-SNAPSHOT-agent.jar` with `--mcp-expose-sql`, driven by
hand-written JSON-RPC over stdio. One unauthenticated
`GET /api/v2/subscriptions` (401, label `mcp-live-smoke`) was captured.
`tracelens_doctor`, `flow_status`, `investigate_request` and `list_flows` found
it; `get_segment_sql` returned all 5 MySQL statements with real bindings (6.7 KB),
the `contains` filter matched 1, `get_segment_execution` and `get_flow` still
contained no SQL, and without the flag the tool was "unknown". Not covered:
`enclosingMethod` was null for every query because none ran inside an
instrumented method on this 401 path; an authenticated request that writes
data; cb-ems; an AI agent choosing the tool itself. cb-app is left running
with the agent attached; `make start` without `TRACELENS=1` removes it.
Correction to an earlier review note: cb-app jobs can be trace entry points
through `chargebeeJobTypes` (the installed config lists
`compute_billing_metrics`), so that was not a gap.

**Update 2026-11-12 (after the live run):** cb-app was restarted again with
plain `make start` at the user's request. It is healthy, has no TraceLens
`-javaagent`, and the debug port 7778 is closed. The "left running with the
agent attached" note above no longer applies.

## Start here

Read these before changing behavior:

1. [README.md](README.md) for the supported product surface and architecture.
2. [Documentation index](docs/README.md) for the canonical guide for each topic.
3. [Productivity pilot and prior implementation handoffs](docs/productivity-pilot.md)
   for dated decisions, prior verification, limits, and deliberate non-goals.
4. [Developer workflow](docs/developer-workflow.md) and
   [request-focused UI](docs/request-focused-ui.md) for the intended investigation
   and saved-comparison workflows.
5. [Guided setup](docs/guided-setup.md), [Java distribution](docs/java-distribution.md),
   and [local credit-stack integration](docs/credit-stack-integration.md) before
   changing service setup, runtime actions, or the bundle.

Treat dates on earlier handoffs as historical evidence. Re-run relevant checks
before describing an old result as current.

## Notion documentation and release bundle

TraceLens also has a small Notion documentation set for engineers who need a
shareable product overview or local-installation help. The known parent product
page is [TraceLens](https://app.notion.com/p/chargebeeknowtion/TraceLens-3fe3592792eb8175b8baca540528a8e3?source=copy_link); its child pages include the
setup guide, **How TraceLens works**, and service-specific installation guidance
such as [TraceLens installation — cb-ems](https://app.notion.com/p/chargebeeknowtion/TraceLens-installation-cb-ems-3fe3592792eb812fb3dff188cf510548?source=copy_link).

When a change affects user-facing behavior, setup, the downloadable bundle, or
what the viewer claims to show, update the matching Notion page **after** the
repository guide and source are verified. Keep Notion concise and first-time-user
oriented; it should point people to the download and setup dashboard rather than
duplicate every maintainer command. The repository documentation remains the
technical source of truth.

Before replacing an attachment, confirm the exact bundle being uploaded. The
intended shareable setup archive contains only the combined `tracelens.jar`,
`setup.sh`, and portable configuration files—never credentials, local captures,
or customer/business data. Do not assume a Notion page or ZIP is current merely
because it has the expected title; check its date/version and contents.

## Product model

- A shaded Java agent runs **inside each target service JVM**. Byte Buddy hooks
  record selected execution, request, dependency, database, cache, queue, and
  supported job evidence into a bounded in-memory store.
- The combined TraceLens JAR runs the **viewer, CLI, and MCP server in a separate
  Java process**. It reads retained evidence through each service's loopback debug
  API. The viewer never receives instrumentation callbacks directly.
- A `traceId` can correlate an observed distributed flow; each service's retained
  local timeline is a `segmentId`. Missing segments or calls are evidence gaps,
  not proof that code did not run.
- Live evidence is transient. **Save snapshot** creates private local viewer files;
  compare/search operates on those saved files and must not silently substitute
  live service data.

## Current implemented experience

The current source implements these main capabilities:

- **Four viewer workspaces:** Requests, Saved captures, Performance, and Services
  & capture settings.
- **Three capture levels:** Overview, Methods, and Detailed. Request
  data, SQL/bindings, ClickHouse, cache, queues, dependencies, and exceptions are
  separate opt-ins where supported.
- A fitted request graph with HTTP hops hidden by default, zoom/fit controls,
  service frames, compact cards, caller/code navigation, grouped DB cards, cache
  and queue cards, external-service cards, and recorded call-order labels.
- Incoming request query/body capture, exact bounded keyword search, SQL and
  ClickHouse query/binding inspection, and clipboard controls. SQL previews are
  reconstructed debugging aids; original templates and typed bindings remain the
  authoritative retained form.
- On-demand Performance insights for a bounded selection of captured requests:
  slow queries, repeated SQL, possible N+1 patterns, and query-heavy segments.
  These are leads for investigation, not traffic-wide performance claims.
- Saved before/after comparison centered on outcomes, changed external calls, and
  parent-grouped method differences. Nested differences remain inspectable; a
  missing observation means “not observed in this capture,” never “deleted.”
- A Java-only bundle with `tracelens.jar`, `setup.sh`, and portable configs.
  Setup supports initial configuration and later edits, with start/restart shown
  and separately confirmed. Supported guided service choices are cb-app, cb-ems,
  cb-plans, cb-consumption-ledger, and cb-grant-orchestrator. cb-ui-middleware is
  intentionally excluded.

See the root README's **What works today** section for the supported client seams
and all evidence limitations. Do not broaden those claims merely because a card
is visible in the UI.

## Key source areas

| Area | Main location | Responsibility |
| --- | --- | --- |
| Agent weaving | `tracelens-agent/` | Byte Buddy instrumentation and shaded agent artifact |
| Bootstrap/runtime bridge | `tracelens-bridge/` | Thread context, callbacks, bounded runtime recording |
| Core model/config/export | `tracelens-core/` | Events, capture levels, configuration, projections and export |
| Java tool/server | `tracelens-tool/` | Viewer/CLI/MCP, local debug reads, snapshots, setup service |
| Browser viewer | `tracelens-cli/tracelens_cli/web/` | Plain HTML/CSS/JS UI and graph/inspection behavior |
| Browser tests | `tracelens-cli/tests/*.test.cjs` | Node built-in test-runner regressions |
| Guided setup resources | `tracelens-tool/src/main/resources/tracelens-setup/` | Setup/configuration dashboard assets |
| Local service scripts | `scripts/` | Optional local cb-app, EMS, Ledger, GO and related launch helpers |

The project deliberately uses plain browser JavaScript and JDK HTTP server code;
do not add a framework or third-party dependency for a localized UI improvement
without first showing why the existing approach cannot meet the need.

## Configuration and runtime rules

- `cb-app` is the TraceLens application/service identity; `chargebee-app` is the
  repository/orchestrator name. They refer to the same local application in
  supplied examples, but are not interchangeable command arguments.
- Editing `tracelens.json` changes what the **next JVM start** captures. It needs
  a restart, not an application rebuild solely for JSON edits.
- Java/agent/tooling code changes require building a new shaded/combined artifact
  and restarting the affected process. Restarting only the viewer does not update
  instrumentation in target JVMs.
- A failed connection to one configured service must not block the viewer or the
  independently configured services. Preserve this failure isolation.
- The setup dashboard is intentionally not an always-running configuration API.
  It uses an expiring local session and makes restart impact visible before an
  explicit local runtime action.
- Do not read, print, commit, or copy credentials. Do not use real production
  data as a substitute for a local reproduction.

## Known limitations and evidence rules

- The agent is fail-open: observer failures must never replace or alter the
  application result.
- All runtime and viewer work is bounded. Large traces may be partial, pruned, or
  summarize only a prefix; surface that condition instead of silently treating it
  as complete.
- A recorded outbound HTTP/SQS/cache/database call does not prove remote execution,
  queue consumption, cache hit, transaction commit, complete SQL semantics, or a
  global causal order.
- Cache client evidence cannot reliably distinguish Redis from Valkey by itself.
- External service aliases are configured display labels, not proof that the
  named remote service executed. Keep the original address visible.
- Request/SQL data may be sensitive even in local environments. Capture only when
  deliberately enabled; saved files are private local artifacts.
- The currently supported HTTP/driver/client seams are documented in
  [dependency visibility](docs/dependency-visibility-and-change-impact.md) and
  [SQL capture](docs/jdbc-query-capture.md). Unsupported clients must remain
  explicitly unknown rather than inferred.
- TraceLens complements logs, tests, and APM. It cannot prove that a code change
  is safe or identify all affected flows.

## Saved demo evidence

The retained local demo guide is [docs/demo-guide.md](docs/demo-guide.md). It
includes links for feature creation, cache miss/hit, ClickHouse, successful Grant
Orchestrator flow, Ledger balance, a Plans failure, performance evidence, hidden
downstream failure, and the before/after UBB-4005 comparison pair.

Those links and snapshots are local-machine artifacts from October 2026. Confirm
the snapshot store and viewer are available before relying on them in a demo;
never recreate business data merely to make an old link work.

## Verification baseline (historical)

The most recent recorded source-level verification is dated **2026-10-28** in
[docs/productivity-pilot.md](docs/productivity-pilot.md):

- Full Node viewer suite: 875 tests passed for the UX milestone; later focused
  comparison work recorded 853 tests passed.
- Gradle tool/agent bundle run: 1,535 Java tool tests passed for the UX milestone.
- Architecture hardening recorded 1,875 Java tests passed, six opt-in real-database
  tests skipped, plus Java 8 agent/tool smoke tests and synthetic benchmark checks.

These numbers are not a current build result. At the start of a new change, run
the smallest relevant test file first, then the appropriate broader suite. For a
viewer change, begin with:

```bash
node --test tracelens-cli/tests/<relevant-test>.test.cjs
```

For a Java change, use the targeted Gradle project/test first. Before preparing a
bundle, run the documented commands in [README.md](README.md#build-and-run) for
the affected surface. Do not disable, skip, or weaken an existing test to obtain
a green result.

## Worktree safety

This repository has a large, intentionally preserved dirty worktree from the
TraceLens POC. It includes modified, deleted, and untracked files across the
agent, bridge, core, tool, browser viewer, tests, docs, scripts, sample fixtures,
and generated local run records.

Before editing:

1. Run `git status --short` and inspect only the paths relevant to the requested
   behavior.
2. Preserve existing changes, including untracked files, unless the user names an
   exact target for removal.
3. Never use `git reset --hard`, `git checkout --`, `git clean`, broad recursive
   deletion, or force pushes to make the tree look clean.
4. Keep a feature change scoped. Review `git diff --check` and the precise diff
   before claiming completion.
5. Do not commit, push, restart services, mutate local business data, or replace
   saved captures without explicit user authorization.

## Recommended next-agent sequence

1. Restate the specific developer problem to solve and classify it as UI-only,
   viewer/tool model, agent instrumentation, configuration/setup, or local-service
   verification.
2. Read the canonical guide and existing tests nearest to that surface. Search for
   prior decisions in `docs/productivity-pilot.md` before redesigning a settled
   constraint such as bounded storage, evidence uncertainty, or restart consent.
3. Make the smallest change that preserves the data/evidence contract. Add or
   update a focused regression test in the same layer.
4. Run targeted verification, then a proportionate broader check. For browser/UI
   work, inspect the feature in the viewer when a local preview is available.
5. State exactly what was verified, what was not, whether live services were
   touched, and which assumptions remain. Update this file with a new dated entry
   at the top when handing off meaningful work.

## Handoff update template

Add a new section immediately below this introduction when handing work onward:

```markdown
## Latest session — YYYY-MM-DD — short scope

**Done:** Concrete behavior/files changed.

**Verified:** Exact commands, meaningful result, and any visual or real-service
check actually performed.

**In progress:** The next smallest safe task.

**Broken or risky:** Known gaps, evidence limits, dependencies, and unverified
assumptions.

**Next:** One or two concrete follow-up actions.

**Do not touch:** User data, credentials, unrelated dirty paths, services, saved
captures, or other exact items that must remain unchanged.
```

## Current handoff

**Done:** Created this repository-level continuation guide using the established
quantity-proration handover structure: clear completed state, verification
evidence, risks, next actions, and preservation requirements.

**Verified:** The guide links to the current repository README and canonical
TraceLens guides. No source code, configuration, service process, capture, or
saved data was changed for this documentation-only task.

**In progress:** No specific implementation task is active from this handoff.

**Broken or risky:** Historical verification and demo snapshots must be treated
as dated evidence until a new agent reruns the relevant checks. The existing
worktree is intentionally large and dirty.

**Next:** Begin from the requested developer problem, preserve the dirty tree,
and follow the recommended sequence above.

**Do not touch:** Credentials, production/shared data, unrelated worktree paths,
application runtime state, or saved captures without explicit authorization.
