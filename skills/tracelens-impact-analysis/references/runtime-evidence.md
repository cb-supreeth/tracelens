# Using existing TraceLens evidence

Discover live tool names/schemas first; clients may prefix the names below.
Do not fabricate `analyze_change_impact` or `find_captures_by_symbol`. Installing
a skill does not connect MCP or attach an agent; remote clients may not reach
developer-local services.

## Small read-only investigation

1. **`tracelens_doctor`**: check services, recording, capture scope, source
   configuration, and retention warnings. Health does not prove an endpoint is
   captured or the running build matches Git.
   When an expected API capture is missing, **`diagnose_capture_request(path,
   method?, service?, user?, site?)`** checks an exact backend path against
   current metadata and bounded retained summaries. It does not change scope,
   restart, replay, watch or save. Current eligibility does not establish what
   settings applied to an earlier request; absent evidence does not prove expiry.
2. **`investigate_request`**: select exact distributed `trace_id`, or literal
   backend `path`/recorded `label`, narrowed by `service`, `method`,
   `since_minutes`, `user`, or `outcome`. At least one of path, label, or trace ID
   is required. Default `selection="unique"` refuses ambiguity. Narrow candidates
   or ask; use `latest` only when the user explicitly wants the latest run,
   never to conceal ambiguity.
3. **`get_distributed_trace(trace_id)`**: inspect observed services and explicit
   outbound/parent links. **`get_trace_visualization(trace_id)`** can provide a
   compact graph when useful. Visualization is optional, not a replacement for
   evidence or a dependency to install.
4. Follow selected segment IDs with **`get_flow_summary`**,
   **`get_segment_execution`**, and, when source is needed, **`get_flow`**.
   Read each actual schema.
   - `get_segment_execution(segment_id, service?, instance?, max_nodes?)` takes
     a canonical segment UUID, **not** a distributed trace ID. `max_nodes`
     defaults to 200, maximum 500; returned limits still apply.
   - `get_flow(id=segmentId, service=service, instance=serviceInstanceId,
     max_events=...)` provides a bounded source-linked timeline.
   - Use the configured MCP service alias, which can be `default` even when
     application metadata says `cb-app`. Preserve instance IDs across restarts
     and replicas.
   - `search_flows` searches reproduction labels, **not Java symbols**.

A browser page can call cb-app `GET /api/v2/features/...`, which calls EMS
`GET /features/...`. Select the actual API from code/network evidence, not an
assumed browser/public-docs route. Even where all APIs pass through cb-app, an
EMS-only capture does not establish the missing cb-app segment or its duration.

## Existing before/after captures

- **`list_request_captures`** lists CLI/MCP capture metadata, not a global symbol
  index. It does not directly read viewer snapshots or browser pins.
- **`search_saved_captures(query, category?, limit?)`** searches the minimized
  capture store offline for literal case-insensitive class, method, exception-type
  or destination evidence. Use category `class`, `method`, `exception` or
  `destination` when appropriate; query length is 1–128 characters, default
  result limit 50, maximum 100. It returns bounded matches and scan/omission
  notes, not a whole-repository symbol index. No match means not observed in
  the searched evidence, not unused or unaffected code. Discover this tool
  before relying on it; older installations may not expose it.
- **`compare_request_captures(before, after)`** compares exact capture IDs or
  unique names offline. It returns observed method/line/nesting/dependency/
  exception/outcome deltas, warnings, and local duration samples.
- Method output is a **delta**, not a full unchanged-method list. An empty delta
  does not establish that a symbol ran in either capture. Use saved-evidence
  search and retain the exact returned capture/segment identity, or inspect
  exact live segments or an explicitly supplied local capture file. If those
  sources cannot establish execution, report the gap.
- **`save_request_capture(name, ...filters, revision?)`** is an explicit local
  write; use only when requested. Select the right run first. Save the baseline
  before required segments are evicted or the service restarts. A supplied
  `revision` is a label, not verification of the running artifact.
- CLI/MCP captures are bounded summarized JSON, not a database or raw archive.
  They omit payloads, source text, user identity, and raw exception messages by
  design, but paths/names can still be sensitive.

The updated viewer compares two viewer-file snapshots offline using
**Compare & search saved captures**. Its **Use with CLI/MCP** action explicitly
creates a separate minimized capture in the CLI/MCP store; it does not modify
the original, automatically convert snapshots, or substitute live reads.
This is a local write, so request it only with appropriate authorization.
Viewer, CLI and MCP must use the same `--capture-dir` when overriding the
default. Legacy browser-only pins need **Save to viewer** first. The browser's
saved-evidence search can search both stores; `search_saved_captures` searches
only minimized captures, never full viewer event/source files.

Record scenario, entry API/job, builds, inputs, site/flags, data state, capture
scope, and completeness. Unknown/unequal conditions weaken attribution. A path
change is an observed execution difference; use result assertions/tests for
behavior change. One duration sample is not a performance benchmark; do not
add durations across services.

## Interpret conservatively

- **Symbols/source:** current method identity is class + method name without
  overload descriptors. Use static context where possible; do not claim a
  specific overload from the name alone. Lines depend on the compiled artifact.
  Source from a different build may be stale; shifted lines are not a semantic
  diff. Separate verified build provenance from labels/assumptions. The viewer's
  **Open local source** opens the current checkout, not historical snapshot
  source; use **Show source in timeline** for captured/resolved source evidence.
- **Completeness:** allowlists, sampling/retention where applicable, event/node/
  byte limits, unsupported instrumentation, uninstrumented libraries, and
  missing segments restrict conclusions. Preserve warnings, omitted counts,
  and partial/truncated status. A rendered graph need not be complete.
- **Nesting:** recorded thread-name lanes do not establish a full static call
  graph or cross-thread happens-before relationships. Intervening uninstrumented
  calls can be absent. Thread relationships require explicit links, not adjacent
  events or similar timestamps.
- **HTTP:** outbound observations prove a caller-side boundary. Correlate
  downstream segments via explicit span/parent evidence, not destination name
  alone. Missing downstream evidence does not prove downtime. HTTP status alone
  does not verify a business result.
- **DB route:** `DB_ROUTE` is pool/routing selection (such as `TEST_MASTER`), not
  SQL, a network roundtrip, successful commit, or proof of data change. Repeated
  routing events need not be duplicate bugs.
- **Queues:** SQS/Kafka submission does not prove delivery, consumption, or
  handler success. Polling does not prove business processing. Queueman/framework
  boundaries may return normally after skipped/caught work. Analyze producer and
  consumer contracts/tests independently and flag missing consumer evidence.
  Do not infer async edges without recorded correlation.
- **Wrappers:** entry is not automatically network evidence. Adapter support
  depends on deployment/version; do not promise universal HTTP client, JDBC,
  SDK version, or framework coverage.
- **Jobs:** Spring `@Scheduled` and Chargebee's custom Job dispatcher are separate
  hooks. Custom Job capture requires an updated agent and exact
  `chargebeeJobTypes` configuration; `instrumentScheduled` alone does not cover
  it. A listed job type or a completed dispatcher is not proof of a successful
  business operation. Inspect the recorded run and its outcome/limits.

## Preserve provenance

For important claims retain service alias/application name, instance ID,
trace ID, segment ID, capture ID/name/time, event/source coordinates, and
warnings. Reference local files with lines and revision. Mark runtime-build
match verified, mismatched, or unknown with its basis; never promote a label
into verification.

Keep evidence bounded. Never silently replace ambiguous, evicted, or unavailable
runs with another scenario. Give a precise next capture/test request when
current evidence cannot answer the question.
