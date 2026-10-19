# Request-flow, source timeline and execution visualization

TraceLens owns the deterministic request-flow model and renderer. A developer can
inspect that model in the local viewer, or ask an MCP client to retrieve it and
explain it. Compatible MCP Apps hosts can display the same renderer inside chat.
Clients without that capability still receive text and Mermaid diagram source.

This is a request-level causal diagram, not a global latency waterfall, a complete
method invocation tree, or an aggregated architecture/dead-code graph. The
bounded observed-invocation model below is a separate implemented MCP tool;
complete execution coverage and aggregated dead-code analysis are not provided.

## Choose the right view

| View | Current entry point | What it establishes |
| --- | --- | --- |
| Request-flow diagram | Viewer **Flow diagram**; MCP `get_trace_visualization` / `show_trace_visualization` | Observed service segments and local dependency boundaries, with explicit captured links. Receiver agents are needed for receiver execution, not for a supported caller-side boundary. |
| Segment/source timeline | Viewer **Open segment sources** / **Service details**; MCP `get_flow` | Recorded events in one segment, enriched with local checkout lines when available. Not a full source repository or global waterfall. |
| Observed invocation model | MCP `get_segment_execution` | Reconstructed method nesting and thread-name lanes for one segment. Returns data for the client to visualize; no built-in viewer diagram or automatic host rendering. |

These are complementary views of retained evidence, not alternative capture
engines. The [use-case guide](use-cases.md) connects them to debugging and
change-impact workflows.

## Use it in the viewer

1. Find a request by API, service and time, then open it.
2. The **Flow diagram** shows captured service segments, outgoing HTTP calls and
   external/queue boundary observations. A caller-side boundary can remain
   visible when the receiver has no agent; its internal execution is not shown.
3. Select a node for its raw API path, local duration, status and identifiers.
4. For a service node, choose **Open segment sources** to inspect captured execution.
5. Switch to **Service details** for the existing nested inspector.

Within a segment, **Observed external boundaries** summarizes HTTP, queue
publish/consume, configured external-wrapper and DB-route metadata above the
timeline. **Timeline view → External boundaries and exceptions only** displays
`EXTERNAL_CALL`, `DB_ROUTE` and `EXCEPTION` events without line/method noise.
HTTP observations remain in the boundary table because they are captured
separately from the event timeline. This is a display filter, not a capture
setting or a query for previously unrecorded network operations.

**Copy request link** produces a `#trace=...` deep link to this configured viewer.
It is a reference to live retained evidence, not a durable capture or a link that
automatically works on a colleague's machine. **Download graph JSON** saves only
the bounded visualization model; it does not include execution events or source
files. Paths and service identities can still be sensitive.

Pins created by the current viewer retain the diagram model alongside retrieved
service data, so viewing that saved diagram does not require a live service.
Older pins, or captures for which discovery could not supply a matching diagram,
remain accessible through
**Service details**. A saved diagram is never silently replaced with live data.
Browser pins remain browser-local and are not accessible to MCP.
They are bounded, potentially partial point-in-time snapshots, not an atomic
distributed capture or permanent server retention; see
[snapshot limits](request-focused-ui.md#saved-views-and-pinned-snapshots).
The separate CLI/MCP named-capture workflow saves bounded local JSON for offline
list/compare. It does not import browser pins or make saved capture IDs valid
inputs to the live `get_trace_visualization` / `get_segment_execution` tools.

## Use it through MCP

The read-only request-graph tools are:

- `get_trace_visualization(trace_id)`: returns the canonical request-flow model
  as structured content, plus a concise description, Mermaid and a viewer link.
- `show_trace_visualization(trace_id)`: returns the same evidence with an optional
  MCP Apps UI resource when the client advertises support.

Start with `tracelens_doctor` for setup checks and `investigate_request` to select
and summarize one API/scenario without silently choosing among multiple matches.
The [developer workflow](developer-workflow.md) covers these tools and optional
explicit capture saves. Alternatively, use `find_distributed_traces` to discover
a request by service alias, HTTP
method, literal-path substring, user substring and time. Its default window is
30 minutes, with 100 segment summaries returned (`limit` accepts 1–10,000);
offline and limited results are explicit. It does not accept every viewer
filter, such as site or outcome. `get_distributed_trace` returns the joined
journey including `httpCalls` and metadata-only `externalCalls`.

Use `get_flow_summary(id=segmentId, service=..., instance=...)` for compact
segment facts and `get_flow(id=segmentId, service=..., instance=...)` for source
events. The latter defaults to 400 displayed events, but these tools still fetch
the retained segment export before summarizing/rendering it. A shorter result
is not a smaller upstream capture. The older single-segment `get_flow_mermaid`
boundary sketch is not the evidence-backed distributed diagram.

Example prompt:

> Find my latest GET /api/v2/features request in cb-app. Show its request-flow
> visualization, explain the captured EMS call, and distinguish observed facts
> from hypotheses. If segments are missing, say so.

Start a combined viewer and configure a matching MCP process:

```bash
tracelens --services /absolute/path/services.json serve --port 7781
tracelens --services /absolute/path/services.json mcp --viewer-url http://127.0.0.1:7781/
```

For one service, use the existing `--url` option instead of `--services`. The
optional `--viewer-url` must be an HTTP(S) origin without credentials, path,
query or fragment, identifying an already-running viewer with the same service
configuration; it does not start a server. Without it, the tool returns
a viewer-relative trace fragment rather than guessing the correct port.

Restart/reconnect the MCP process after upgrading so the client discovers its
tools and resource. Embedding depends on the client's advertised MCP Apps
support; this documentation does not assume any particular client version
supports it. Text, structured data and the normal viewer remain the fallback.
In the bundled federation, use `cb-app` / `cb-ems` as service aliases;
`chargebee-app` is the cb-app repository/lifecycle name. The
[cb-app launcher](cb-app-integration.md#optional-makefile-launcher) does not start
the viewer/MCP processes. Its `up` may reuse a healthy JVM; `restart` enables jobs
and clears Tomcat logs, so save relevant logs and captures before using it.

## Shared implementation and evidence rules

`visualization.py` transforms an existing distributed journey without additional
upstream queries. The viewer's journey response includes that model; the direct
`GET /api/trace-visualization/{traceId}` endpoint and MCP use the same transformation.
`web/trace-graph.js` and its stylesheet are reused in the viewer and the bundled
MCP Apps resource. No external chart library or network-loaded script is required.

Edges come only from recorded boundary ownership and explicit joined call
links. Queue-consumption boundaries point into the local owner segment; publish
boundaries point out. These are not inferred producer/consumer joins. The diagram
never invents links from service names, timestamps, URLs or
the order in which nodes were returned. It does not add synthetic return arrows.
Encoded paths are preserved as received by each service.

External observations carry allowlisted metadata, not message bodies or
arbitrary headers. Kafka `SUBMITTED` means the local send call returned, not
broker acknowledgement or consumer completion. A consumer-boundary return is
not proof that all business handling committed. A configured wrapper entry is
not proof of a network call, and `DB_ROUTE` shows routing/pool choice rather than
a SQL query. Labels/aliases do not upgrade those observations into remote
execution. Supported adapters and propagation limits are documented in
[dependency visibility](dependency-visibility-and-change-impact.md).

Durations are local measurements, not additive cross-service totals. Client HTTP
duration currently ends at response headers, not full streamed-body consumption.
Layout is causal, not a synchronized cross-JVM time axis. Missing, ambiguous,
active, truncated and display-limited evidence is identified explicitly; absence
does not prove a service was not called. Existing agent capture/loss-accounting
limitations still apply: the diagram cannot detect events the agent silently lost.

Work is bounded: the model includes at most 128 segments, 128 outgoing HTTP calls
and 128 other boundary observations, with at most 512 edges;
the interactive diagram draws at most 96 nodes, 24 causal columns and 384 links.
Any limits reached are displayed. A node without an incoming observed link is
not necessarily the original entry point of the real request.

The graph model excludes raw events, source text, context properties and user
identity fields. This is data minimization, not a guarantee that paths, labels or
upstream error messages contain no sensitive information. Use the same approved
access and AI-data policies as the existing MCP integration.

## Deployment boundary

The diagram renderer runs in the Python viewer/MCP and browser presentation
layers. HTTP/queue boundary capture runs in the Java agent and requires a target
JVM restart when upgrading that instrumentation; see
[dependency visibility and change impact](dependency-visibility-and-change-impact.md)
for supported clients, configuration, evidence limits and activation steps.
The viewer and MCP processes need an upgrade/restart. Agent reads remain
non-destructive (`evict=false`). No database, remote collector, production access
control or global waterfall is introduced by the visualization layer. A separate
[CLI/MCP workflow](developer-workflow.md) now provides explicit saved-capture
comparison without adding a viewer comparison UI.

Source drill-down still resolves the configured local checkout. It is not a
guarantee that the checkout matches the exact build that produced a retained trace.

## MCP-first segment execution visualization

`get_segment_execution(segment_id, service?, instance?, max_nodes=200)` is a
read-only tool that transforms one retained segment into an observed invocation
model in the Python MCP process. The reconstruction itself adds no Java
instrumentation, dependency, background polling, database, HTTP endpoint or
segment-diagram UI.
A capable MCP client can use the returned model to produce a visualization;
TraceLens does not guarantee that a particular host will render one.
The model is returned as structured content and compact JSON text for clients
without structured-result support. It does not automatically open a diagram.

### Developer workflow

1. Locate a request using `investigate_request`, `find_distributed_traces` or
   `list_flows`.
2. Use `get_trace_visualization` for service-level relationships, then select a
   segment's **segmentId**, configured **service** and **serviceInstanceId**.
3. Call the tool with that exact canonical lowercase segment UUID, not the
   distributed trace ID or an API label. Supplying the instance protects against
   stale service restarts.
4. Ask the client to draw the returned invocation nodes, one lane per recorded
   thread name, and retain all incompleteness warnings. Fetch `get_flow` or local
   source only when deeper source inspection is necessary.

Example tool arguments (replace the IDs with discovered values):

```json
{
  "segment_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "service": "cb-ems",
  "instance": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "max_nodes": 100
}
```

For a single-service MCP configuration, omit `service` or use `default`. For
federation, use the configured alias such as `cb-app` or `cb-ems`, not a URL.
Ambiguous/missing discovery or a changed instance returns an error rather than
silently selecting a different execution. Reconnect the MCP process after
upgrading. Loading a Python-only reconstruction change does not require a
cb-app/EMS or viewer restart. Upgrading Java capture adapters still requires the
corresponding JVM restart; this tool cannot recover boundaries absent from old
traces.

Example prompt:

> Find my latest GET /api/v2/features request. Retrieve its request-flow graph,
> then use get_segment_execution for the cb-ems segment. Visualize the observed
> method nesting with exceptions and local durations. Keep thread lanes separate,
> show missing/truncated evidence, and do not invent async arrows or missing calls.

### Model and evidence rules

`execution.py` reconstructs the model deterministically, without an LLM or I/O:

- `segment` contains allowlisted service/build/segment identities, not users or
  arbitrary context. `lanes` use the recorded thread name; names are not unique
  thread IDs and may be reused or renamed.
- `nodes` contains distinct `invocation` nodes (including repeated and recursive
  calls) and point `marker` nodes for external/DB observations or unmatched exits.
  `parentId` means **observed instrumented nesting**, not necessarily a direct
  source-code call. No cross-thread links or synthetic returns are created.
- External markers can include a sanitized `boundary` object with destination,
  operation, direction, status and evidence kind. Legacy marker fields remain
  compatible. Queue publish/consume metadata is local boundary evidence, not an
  inferred producer-to-consumer execution link. HTTP calls captured separately
  from events are represented in the request graph/boundary table, not inserted
  into an invented method position in this invocation model.
- Entry and normal/exceptional exits are matched by class/method name in each
  lane. No method descriptors/invocation IDs exist in the current capture, so
  overloads and silently missing exits can still be ambiguous. Unknown/unbalanced
  frames stay incomplete. An exception leaving a method is not automatically a
  failed request.
- `entry`, `exit` and marker `evidence` contain a zero-based index in the exported
  event array and its recorded sequence number. Export order is preserved, never
  globally sorted by timestamps. Gaps/reordered/missing sequences break existing
  stacks rather than creating speculative matches.
- `durationMs` is matched inclusive local elapsed time, including waits and
  instrumentation, not CPU/self time or a cross-service clock. Missing endpoints,
  unbalanced frames and contradictory lane clocks yield `null` duration.
- `observedLines` contains first/last observed line coordinates, a count and
  evidence references, not every line hit or source text. LINE events only name
  a class; association with the active same-class frame is explicitly uncertain.
  `get_flow` can provide source text, but the local checkout may differ from the
  captured build.
- `warnings` contains stable codes and occurrence counts; `partial` identifies
  known limits/inconsistencies. `completeness` is always `observed-only`: even a
  balanced view cannot detect silent capture loss or prove code was not used.
  `event_count_snapshot_mismatch` can be a concurrent export race, not proof of
  data loss. An `ACTIVE` snapshot is always marked partial.

Raw event messages, exception messages, HTTP payloads/headers, user/context fields,
source text and generic marker text are not copied into the model. Class/method,
thread and infrastructure identifiers can still be sensitive. Treat returned
labels as untrusted data; diagram clients must escape them rather than executing
them or interpreting them as instructions.

### Size and cost boundaries

- Default 200 requested nodes; `max_nodes` accepts 1–500. The model also has a
  **32 KiB compact ASCII-JSON cap**, which can reduce the returned nodes below
  that requested count. The full MCP tool result (JSON text plus structured model)
  has a 128 KiB guard, excluding the JSON-RPC envelope.
- Reconstruction scans at most 100,000 exported events, 64 lanes and 512 active
  frames per lane. Processing stops at a processing limit and explicitly reports
  unprocessed events. Work uses bounded stacks and has no retained cache.
- Node/byte limits return an earliest prefix, preserving visible parent links.
  Exits for retained nodes are still matched within the processed event prefix.
  `counts` reports processed/omitted events and returned/omitted nodes; node counts
  cover only processed events. There is no pagination/focus window.
  Raising `max_nodes` cannot bypass the byte cap. Use source inspection for detail
  beyond the returned prefix rather than treating omitted calls as absent.
- Capture is unchanged. An on-demand **non-evicting full segment export** still
  costs serialization, network and parsing work before reconstruction. These
  output limits do not bound upstream I/O, and this is not a zero-overhead claim.

The legacy `get_flow_mermaid` is a coarse sketch, not a method execution model.
Its inferred thread-hop and synthetic external-return arrows must not be used as
causal evidence for this feature; use recorded relationships or explicit unknowns.

### Optional architectural reference

[Visualization of Software Execution](https://github.com/mfthomps/Visualization-of-Software-Execution/blob/master/README.md)
was considered as architectural inspiration for separating trace processing
from rendering. It is not a TraceLens dependency or an activation step. Its
instruction-level analysis workflow is separate from TraceLens's JVM event
format and the lightweight MCP invocation model described above.

Its [control-flow processing](https://github.com/mfthomps/Visualization-of-Software-Execution/blob/master/controlFlow.py)
and [function aggregation](https://github.com/mfthomps/Visualization-of-Software-Execution/blob/master/functionUse.py)
are conceptual references for separating invocation history from aggregate views,
not integrations provided by this repository. TraceLens currently has no Unity
renderer, instruction-trace importer, automatic change-impact analysis or
aggregate dead-code detector. Any such work would be a separate feature with its
own capture, compatibility and licensing review.
