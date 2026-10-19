# Finding and keeping a request in the local viewer

The viewer starts with **HTTP requests from the last 30 minutes**. Reproduce an
action in your application, then find its request using the API you already know.
Browser instrumentation or a manually supplied trace ID is not required.
The [use-case guide](use-cases.md) gives end-to-end investigation examples.
The bundled combined registry calls the services `cb-app` and `cb-ems`;
`chargebee-app` remains the cb-app checkout/lifecycle name, not its TraceLens
filter value. See [cb-app setup](cb-app-integration.md) for the optional launcher.
It starts the application, not this separate viewer.

## Find a retained request

Use the service, HTTP method/API path, user, site, time window, outcome, and duration
filters to narrow the list. For example, select the relevant service and `GET`,
enter the feature reference-counts path, then narrow by your user or site. Open the
matching request to inspect its execution timeline and available service calls.
The [request-flow diagram](hybrid-visualization.md) offers a graphical entry point
to the same captured call relationships, with source drill-down and MCP support.
The [Errors view](error-groups.md) groups recurring failure signatures and adds
failing-service, category, status and boundary-exception filters.
Identity and site filters depend on those values having been captured by the
service; a missing value is unknown, not evidence that authentication failed.
Site, normalized-route, and error metadata are additive agent fields. Existing
running JVMs keep the agent version loaded at startup; updating only the viewer
does not add those fields to their traces. Use the updated Java agent on a future
application restart to capture the new metadata. The viewer does not infer a
missing site from a username or API path, or invent a normalized route or error.

In **Live requests**, service, method, API path, user and site must match the
same observed segment. API path matches a case-insensitive substring of the
literal path or a captured route template; user is also a substring, while site
is an exact ID. A cb-app user's identity cannot satisfy an EMS segment's user
filter merely because both belong to the same request. **Trace ID or label** is
a separate request-wide text search, including segment IDs and recorded labels.
The Errors view has different failure/identity matching rules described in its
guide.

The time window uses the request start, not the moment you opened the viewer.
**All retained** removes that time restriction; it cannot restore evicted data.
Outcome distinguishes **Errors in any segment**, **Successful HTTP**, **Active**
and **Unknown**. A downstream error takes precedence over a successful root;
success needs a credible root with HTTP 2xx/3xx. A lifecycle label such as
`COMPLETED`, or a caught exception alone, does not establish business success or
failure.

Duration filters use the locally measured root/request duration displayed for the
row. They do not add durations across services: downstream work can overlap, and
timestamps from different JVMs are not assumed to share a synchronized clock.
If the root is missing or ambiguous, its duration is unknown and cannot satisfy
a minimum or maximum duration filter.

## Grouping and background work

Segments with the same valid distributed trace ID are displayed as one logical
request flow. Missing or invalid trace IDs remain separate observations.
Their service names, local segment IDs, and parent/child span relationships remain
available when you open that flow. A service without TraceLens, an unavailable
agent, or an evicted segment can leave the flow incomplete; the viewer does not
invent the missing execution history.

Collapsing repeated API calls is a separate display choice. Requests with the same
root service, method, channel and captured route template (or exact literal path)
can be shown together to reduce polling noise. Pathless jobs use their captured
labels. IDs are not stripped from paths to guess a route, and requests with an
unknown root are kept in separate display buckets from known-root requests.
Each call keeps its own trace ID, timestamp, outcome and timeline. Expanding a
group reveals the individual requests; it does not merge their executions.
Expanded members have an indented, tinted group region, a **Request N of M**
label and an **End of group** row, so they remain distinct from standalone rows.
API groups use blue accents and error groups red accents.

Use **Channel** to select HTTP requests, scheduled jobs, queue messages, other
work or all channels. In Live requests this selects the request's root/display
channel; queue boundaries within an HTTP request remain part of that request.
It changes what is displayed, not what the agent captures. Existing job and
messaging instrumentation coverage is unchanged, and arbitrary custom jobs or
consumer loops do not become instrumented merely because a channel is selected.

Supported caller-side HTTP and queue observations can appear even when the
receiver has no agent. They establish a local boundary, not the receiver's code
path or completed queue processing. See
[dependency visibility](dependency-visibility-and-change-impact.md) for coverage.

## Saved views and pinned snapshots

A saved view remembers a useful filter combination, such as a particular service,
API and user, together with the Live requests or Errors mode. It saves filters,
not trace data. Reopening a relative time window such as "last 30 minutes" applies
it to the current time; it does not freeze the earlier result list. Up to 20 saved
views are supported; saving the same name updates that view.

Choose **Pin capture** to save a bounded point-in-time snapshot in the current
browser's IndexedDB. It stores retrieved segment events, locally resolved source
lines when available, and the request graph when discovery supplied a matching
model—not only a reference to an agent's in-memory entry. **Pinned captures**
shows these snapshots independently of the live filters. Saved evidence can be
reopened after a reload or service restart while browser storage remains intact.

Pinning reads observed segments on demand; it is not an atomic cross-service
snapshot. Active, truncated, unavailable or omitted segments produce partial
snapshot warnings. Only data retrieved at capture time is saved: a pin is not a
live subscription and cannot recover previously missing evidence. Opening a pin
never silently substitutes newer live data for missing saved segments. Pinning
the same logical request again replaces its earlier browser snapshot.

Pins have these limits:

- At most 20 pinned snapshots.
- At most 64 observed service segments attempted per snapshot.
- At most 5 MiB for one snapshot.
- At most 20 MiB of snapshot data in total.

The viewer reports storage or size failures instead of claiming that an unsaved
snapshot is pinned. Browser storage quotas can impose a tighter limit.

Saved views and pins are separated by the viewer's configured service registry and
source-checkout mapping. Restarting the same services keeps that namespace;
changing an upstream address, service name, source root, or single/federated mode
selects a different namespace. Registry ordering and equivalent URL spellings do
not create a new namespace. The browser receives an opaque hash through
`GET /api/viewer-context`, not the raw upstream URLs or source paths.
For example, renaming a registry entry from `chargebee-app` to `cb-app` selects a
different browser namespace. Existing pins are not migrated or deleted by that
rename; returning to the original mapping and browser origin exposes its namespace
again. Previously saved filter values are not automatically rewritten.

These snapshots belong to that browser profile and viewer origin. They are not a
durable collector, a shared team database, or storage accessible through MCP.
Clearing browser data, using a different browser/profile or viewer origin, or
losing the local browser storage can make pins unavailable. Keep an explicit export
or a named CLI/MCP capture when an investigation needs independently retained
evidence. `capture save/list/compare` and the corresponding MCP tools use bounded
local JSON files that survive JVM restarts, separately from IndexedDB. They are
not automatically displayed under **Pinned captures**, and MCP still cannot read
browser pins. Follow the [developer workflow](developer-workflow.md) for these
explicit saves and offline comparisons.

For a raw, potentially more sensitive segment export instead, this prints one
retained segment as JSON (replace the discovered
service, instance and segment values):

```bash
tracelens --services /absolute/path/services.json --service cb-app \
  --instance INSTANCE export SEGMENT_ID
```

Repeat for other required segments and retain the output appropriately. This is
not a pin-export/import command. **Download graph JSON** saves only the graph
model, not the complete source/event snapshot.

## Inspect the captured work

Open **Flow diagram** for service segments and observed HTTP/queue/external
boundaries. Select a service node and **Open segment sources** for its captured
timeline, or use **Service details** to select a segment. The **Observed external
boundaries** table summarizes dependency metadata above source events.
**Timeline view → External boundaries and exceptions only** hides line/method
noise; HTTP observations stay in the boundary table because they are captured
separately from line events. Neither view creates missing receiver execution or
a synchronized cross-service waterfall. See [visualization modes](hybrid-visualization.md).

## What inspection does

Opening, filtering, grouping, saving a view, or pinning a snapshot reads captured
evidence. The viewer does not automatically retry or replay application requests.
Reproduce an action in the application when you need a new execution. The existing
agent limits and retention settings still govern the live request list; browser
pins do not increase the agent's in-memory retention.

The separate **reset** button is destructive: in single-service mode it clears
live retained traces after confirmation. It is disabled for the read-only
federated viewer. **Reset filters** only resets the current filters. Neither
operation is the same as deleting a browser pin.
