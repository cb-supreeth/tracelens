# Request-flow, source timeline and execution visualization

TraceLens renders retained execution evidence in complementary views. None is a
global latency waterfall, a complete call graph or a deployment architecture map.

| View | Open it | What it shows |
| --- | --- | --- |
| Request flow | Open a request's **Flow diagram** | Captured service invocations and supported local dependencies with explicit links |
| Code flow | Select a service card → **Open code flow** | Compact reader for one invocation's observed method nesting |
| Call tree | Select **Call tree** in that service | The same bounded invocation model as a diagram |
| Timeline / source | Select **Timeline / source** | Retained events and available source lines for one invocation |

Start in **Requests** for live retained work or **Saved captures** for fixed
evidence. Performance analysis and service/setup checks have their own workspaces.
See [request discovery](request-focused-ui.md) and [use cases](use-cases.md).

## Use it in the viewer

1. Open the request. The graph starts fitted with unambiguous linked HTTP hops
   hidden, leaving service connections and grouped local dependencies.
2. Select a fixed-size card. The adjacent **Selected evidence** inspector holds
   SQL, request data, boundary details and navigation actions; expanding details
   does not resize cards or rearrange the graph.
3. Choose **Open code flow** for the selected service's execution. Choose **View
   caller in …** to inspect the upstream context instead. If several callers are
   linked, select the specific recorded call.
4. Use **← request flow** to return. **Service details** provides the alternative
   nested segment inspector.

**Fit**, zoom and **100%** remain outside the scrolling canvas. **Focus selected**
centers the card; **Focus branch** fits it and its recorded descendants. The
minimap shows the viewport, not additional evidence. Arrow keys change selection,
Enter/Space activates a focused control and Escape closes the inspector. Opening
the graph or changing HTTP-hop mode fits it; inspecting details preserves the
chosen camera. The inspector adapts to narrow screens without growing the graph.

### HTTP hops, frames and service names

**Show HTTP hops** reveals caller-side HTTP cards. **Hide HTTP hops** folds only
an unambiguous owner → HTTP → captured-receiver link into a direct connection.
Caller and receiver timing/evidence remain distinct; folding does not remove
canonical nodes from exports or merge calls that share a service name.

A frame groups one captured invocation with its explicitly owned local
observations. Two Ledger calls keep separate APIs, SQL and source links. Repeated
frames show their API to distinguish them. Frames do not claim database ownership
or complete service architecture; ambiguous/shared/cyclic relationships use a
conservative layout instead of guessed ownership.

When receiver execution is unavailable, a dashed **External service** card remains
outside its caller's frame, even in compact mode. A configured destination alias
takes precedence. Otherwise a unique service observed at that exact peer in the
same capture can supply a labelled naming hint. Unknown/ambiguous peers retain
their address; inspect the original peer and naming evidence. A name does not
create a receiver segment or prove historical ownership. No live lookup is used
to enrich saved evidence.

HTTP arrow numbers express supported causal/local stages: **1, 2, 3** for a
chain; **1.1, 1.2** only for positively overlapping caller-local intervals.
Sibling arrows alone are not proof of parallel work. **?** means order is unknown.
Service clocks are not compared; these labels are not a synchronized timeline.
Showing/hiding a hop preserves its stage. Grouped DB calls have no single
execution position and are not numbered.

### Inspect dependencies and request data

Select a dependency card, then inspect its evidence in the side panel:

- Purple **DB CALL** groups retained JDBC or native ClickHouse executions by their
  captured owner and destination. Choose an execution for query text, local timing,
  outcome and optional bindings. **SQL with captured values** is a reconstruction,
  not the original driver statement; unknown/truncated types remain placeholders.
- **DB routing decisions** count route/pool selections, not queries. The inspector
  lists individual event/thread observations and their separate caller actions.
- Cyan **CACHE CALL** is an explicit client boundary. **CACHE ACCESS** is a recorded
  known cache-wrapper fallback, not proof of network activity. Neither establishes
  a hit/miss; a human snapshot label is not runtime evidence.
- Orange queue publish/consume evidence is local. **SUBMITTED** does not establish
  delivery, consumer completion or transaction commit.
- A service's **Request data** shows only opted-in retained query/body evidence.
  SQL, bindings and request data have Copy actions; copying does not sanitize them.

**View calling code** opens the exact owning segment's retained timeline. Where
supported, it highlights the boundary and reliable nearby instrumented source
context. This is observed context, not proof of an exact callsite. Missing or
ambiguous event identity remains unavailable rather than selecting another event.
For HTTP, **View caller in …** and **Open code flow** deliberately distinguish
the caller's context from the destination's execution.

**Open local source** offers editor links to the current configured checkout;
it does not restore historical source. Saved timelines use preserved source and
never fetch a newer service execution to fill gaps. See [query evidence](jdbc-query-capture.md)
and [request data / ClickHouse](request-data-and-clickhouse.md).

### Segment execution flow

**Code flow** reads captured methods in separate recorded thread lanes.
**Application logic** reversibly folds recognized Spring proxies, framework
invocation wrappers and synthetic accessors. Ordinary getters, incomplete calls
and branches with exceptions remain visible. **Reveal** restores one folded call;
**All recorded calls** restores all available calls. Folding preserves parentage,
children and evidence, and search reveals matching folded calls.

The overview initially shows at most 40 rows/nodes. **Expand all**, **Collapse all**,
thread filtering and **Show remaining available rows** change presentation only.
Counts distinguish folded, collapsed, filtered and omitted evidence. Omitted
model/events cannot be recovered by expanding the UI.

**Call tree** uses the same reconstructed nodes in a nesting diagram. Unlike the
request graph's fixed cards, this view retains inline method/marker details.
Selecting one opens thread, outcome, local duration, source coordinates and
**Show source in timeline**. Subtree controls are separate. Expand/search preserves
zoom; **Collapse all** fits the remaining roots and **Overview** restores a compact
two-level view. **Fullscreen** and Escape preserve the embedded diagram state.

Both views search the bounded model's class/method names, exception types and
boundary fields, including collapsed nodes. **Previous match / Next match** or
Enter/Shift+Enter reveals ancestors. `/` focuses search; Escape clears search/focus.
**Focus selected branch** keeps validated ancestors and descendants; **Clear search /
focus** restores the earlier view without resetting diagram zoom.

The browser searches at most 200 validated nodes within 16 displayed levels.
Unplaced HTTP calls are not assigned a guessed method owner: **Code flow → HTTP
observations** has a separate destination/path/status search, with 20 observations
per page and at most 5,000 entries inspected. It searches only retained fields,
not request bodies, omitted events or the source tree. A search miss is not proof
that code or a call did not run.

### Timeline and large captures

**Code flow / Call tree / Timeline / source**, the distinct service name and
timeline filter stay pinned below the header. **↑ Top** returns to segment details.
The **Observed external boundaries** table starts collapsed. Its HTTP observations
are separate from line events; **External boundaries and exceptions only** filters
the timeline, not recording.

The timeline displays one bounded page at a time with previous/next/first controls.
Paged-agent defaults are 500 events / 256 KiB, with a 1 MiB response ceiling;
legacy retained arrays are also displayed in windows. **View calling code** loads
the page around the recorded anchor. Enable **Wrap event text**, select a row for
its retained event JSON and **Copy recorded event**, then Escape/Close returns
focus. “Full event” means what was retained, not recovery of omitted data.

The current Java reader separates compact overview from event pages. Missing or
oversized details do not silently remove otherwise available overview evidence
or substitute a different request. Code-flow reconstruction carries bounded
stacks across transport pages, but its analysis/display limits still apply.
See [large-trace budgets](java-capture-storage.md#large-traces-and-paged-details).

## Save, compare and share the view

A live `#trace=…` link rereads retained services after reload. Restart, TTL or
eviction can remove segments; keeping a graph open is not durable storage.
**Save snapshot** captures available evidence explicitly. In **Saved captures**,
**Copy snapshot link** gives a stable local `#snapshot=UUID` link. Missing or corrupt
saved data is an error, never a live-data fallback.

Current paged saves use a version-2 manifest plus compressed chunks, including
source lines resolved within capture budgets. Older version-1 JSON captures remain
readable and unchanged. **Download capture archive** / **Import capture archive**
round-trip a version-2 ZIP under a new ID. See [storage quotas](java-capture-storage.md):
version-2 allows 20 captures, 128 MiB raw / 64 MiB compressed per capture;
the legacy store retains its 5 MiB/file and 20 MiB/store limits. Capacity rejects
new saves rather than evicting old ones. A capture may be partial and is not an
atomic cross-service transaction.

**Download graph JSON** contains the bounded graph, not complete events/source.
Localhost links do not provide remote team access. Review SQL, request values,
identifiers and source before sharing archives. Existing **Browser only** pins can
be explicitly copied with **Save to viewer**; saved filter views save no evidence.

Select two captures, Before first, and **Compare selected**. The behavior-first
review pairs outcomes and external calls, then groups changed methods under their
supported recorded parent. **Back to comparison** preserves context after inspecting
exact saved evidence. **All differences** retains the raw count/line report.
Configured comparison aliases are exact per caller, retain the address and do not
change correspondence. Pair/all-saved search is bounded, not a full corpus index.
See the [comparison workflow](developer-workflow.md#3-save-baseline-and-candidate-evidence).

## Use it through MCP

The same Java distribution runs viewer, CLI and MCP. Use the primary service JSON
from the installation so service/source mappings and evidence stores agree:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json serve --port 7781
java -jar tracelens.jar --config cb-app/tracelens.json mcp --viewer-url http://127.0.0.1:7781/
```

Use your primary config for other service selections. `--viewer-url` identifies
an already-running viewer origin; it does not start one. Reconnect MCP after a
tooling upgrade. Agent instrumentation changes require the corresponding JVM
restart, while renderer-only changes require only viewer/MCP refresh.

Start with `tracelens_doctor` and `investigate_request`; do not silently choose
among ambiguous requests. `get_trace_visualization(trace_id)` returns the request
model, concise text, Mermaid and a viewer link. `show_trace_visualization` can
also provide the shared renderer to an MCP Apps-compatible host. Other hosts
retain structured/text output; embedded rendering is not guaranteed.

Use `get_flow_summary` and `get_flow` for a specific segment, supplying service
and instance identity. The separate minimized CLI/MCP capture store supports
offline list/compare/search; **Use with CLI/MCP** creates an explicit minimized
copy of a viewer capture. A snapshot UUID is not a live trace or segment ID.

## MCP-first segment execution visualization

`get_segment_execution(segment_id, service?, instance?, max_nodes=200)` returns
one segment's bounded observed-invocation model. Select the exact segment UUID,
configured service alias and instance from discovery, not the distributed trace ID.
A missing/ambiguous identity is an error, not permission to choose another run.

The Java `Execution` model is deterministic, with no LLM reconstruction:

- Nodes represent distinct retained invocations or markers; parent links mean
  observed instrumented nesting, not necessarily direct source calls.
- New events can supply JVM method descriptors and runtime-local thread IDs;
  legacy events retain less precise name-based matching. Neither provides
  invocation IDs or establishes cross-thread causality.
- Entry/exit matching preserves exported sequence order. Gaps, inconsistent
  clocks and incomplete frames produce warnings/unknown durations.
- Durations are inclusive local elapsed time, including waits and instrumentation,
  not CPU/self time. Line association uses recorded coordinates, not source-aware
  cross-build mapping.
- Native ClickHouse async completion is not assigned a guessed caller method.
  HTTP observations without invocation ownership remain separate.
- Identifiers are untrusted data. Source, arbitrary payload/context and raw
  exception messages are excluded from the compact model; identifiers and opted-in
  query details may still be sensitive.

Reconstruction scans at most 100,000 events, 64 lanes and 512 active frames per lane.
Requested nodes default to 200 (`max_nodes` accepts 1–500); the browser's rendering
limits can be lower. The MCP model has a 32 KiB cap and the viewer model 128 KiB.
Limits return bounded prefixes with omission counts; increasing nodes does not
bypass byte/event limits. Paged reads keep transport bounded, not analysis unlimited.
Legacy full-export paths retain their separate byte bounds. See
[storage and read budgets](java-capture-storage.md).

## Shared evidence rules

Edges come only from recorded ownership and explicit correlation, not similar
URLs, service names or timestamps. Queue publish/consume direction does not by
itself join producer and consumer execution. Caller HTTP duration ends at the
captured client boundary, not necessarily full body consumption.

The request model caps segments/HTTP calls at 128 each, other boundaries at 512
and edges at 512. The browser groups SQL before applying its 96-node, 24-column
and 384-link display limits. Limits stay visible; old saved projections do not
regain omitted evidence after an upgrade.

An observed failure is not an automatic root-cause diagnosis. An absent call is
not proof of deliberate skipping, unused code or complete coverage. Use source,
tests and controlled reproductions alongside TraceLens. No remote collector,
production access control or zero-overhead guarantee is supplied by these views.
