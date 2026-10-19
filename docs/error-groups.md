# Investigating grouped errors

The **Errors** tab groups similar observed failures from retained trace summaries.
It is a discovery aid, not an incident tracker or production-wide error-rate
dashboard. It is implemented in the viewer/Python layer and adds no database or
Java instrumentation. Richer route/site/boundary-exception fields still require
the corresponding agent version to have captured them; an older trace cannot
gain them from a viewer upgrade.

## Workflow

1. Open **Errors** beside **Live requests** and **Pinned captures**.
2. Choose a time window and, optionally, a failing service or API.
3. Narrow by failure category, exact HTTP status or recorded boundary exception
   class. Change the channel to include scheduled jobs or queue work.
4. Expand an error group to see its individual requests.
5. Open a request's flow diagram or expand its service segments to inspect code.
   Pin an interesting request for a browser-local point-in-time snapshot.
6. Use **Save current view** to save the Errors tab and its filters in this browser.
   Selecting that saved view restores the mode, service and failure filters.

Expanded error-group members use a red-accented region, **Request N of M** labels
and an **End of group** row. These are separate request executions, not merged
events. For application setup rather than error filtering, use the
[cb-app integration guide](cb-app-integration.md); the launcher does not start
the viewer or guarantee that a newly configured agent is already loaded.

API, method and channel selectors apply to the **failing segment** in this mode.
Selecting cb-app and HTTP 503 does not match a successful cb-app segment merely
because EMS returned 503. User and site can match another observed segment in the
request, but both must match the same segment. This lets a root UI user's identity
find a downstream failure without guessing identities across services.

Time filters use the request start; duration filters use a credible root's local
duration. Unknown root durations cannot satisfy a specified duration bound.
Free-text search is request-wide. Outcome and repeated-API collapsing apply to
Live requests; Errors uses its own grouping and failure filters.

## What makes a group

Each stable fingerprint contains:

- Failing service, channel and HTTP method.
- Captured route template, or exact literal path when no template exists.
  Pathless work uses its captured label. No IDs are stripped or base64 paths
  decoded to guess a route.
- Failure category and exact HTTP status, when present.
- Recorded request-boundary exception class, when available.

Categories are **HTTP 4xx**, **HTTP 5xx**, **timeout**, and **execution failure**.
An explicit timeout takes precedence even when the segment also returned 504.
Otherwise HTTP errors take precedence over generic FAILED or ERROR lifecycle
states. A segment contributes one primary category.

Trace IDs, JVM instances, users and raw exception messages do not define the
fingerprint. Identical fingerprints indicate similar evidence, not a proven common
root cause. Summaries do not provide generalized stacks for stack-based grouping.

## Counts and incomplete evidence

- **Requests** counts unique logical requests within a group.
- **Failing segments** counts matching service-local failure observations.
- **First/latest** refers to retained observations, not the true first occurrence
  in production.
- A request may belong to multiple groups. Group request counts are not additive;
  the overall matching-request count deduplicates across groups.
- Root HTTP 200 plus a downstream error remains a request with error evidence.
  This does not establish recovery, retries or the underlying bug.
- A boundary exception alone is not automatically an error. Unknown outcomes
  and missing roots are not failures.
- Arbitrary exception events and HTTP-client-only failures are not indexed by
  these summary groups; inspect a request's flow/events for that deeper evidence.
- Queue submission/consumption markers are dependency evidence, not by themselves
  error-group members or proof of end-to-end delivery. A retained segment needs
  one of the failure categories above to contribute.
- Unavailable services appear as data-availability warnings, not application
  error groups. Missing evidence does not prove there were no errors.

Grouping uses up to 1,000 recent retained segment summaries across configured
services, selected after the upstream summary lists are fetched. This limit is
not a cap on upstream response bytes. Retention, eviction and the selection limit
can omit part of a request.
Partial/limited results are reported. The view does not export every trace's
events to fill gaps. Counts are not an error rate or historical aggregate.

## Shared API and MCP

`GET /api/error-groups` and MCP use the same Python grouping model. Filters:
`service`, `method`, `path`, `channel`, `category`, `http_status`, `exception_class`,
`user`, `site`, `since_minutes`, `query`, `min_duration`, `max_duration`.

`since_minutes` defaults to 30; an empty HTTP value or MCP null selects all retained
observations. Categories are `http4xx`, `http5xx`, `timeout`, `execution`, or no
restriction. Service names are registry aliases; single-service MCP uses `default`.
The bundled federation uses `cb-app` and `cb-ems`. `chargebee-app` is a checkout
and lifecycle name, not that registry's service filter; an application's
`applicationId` does not override its configured registry alias.

Read-only tools:

- `list_error_groups`: find recurring signatures, without loading member requests
  into the assistant's context. Returns up to 50 groups, ordered by request count
  then latest occurrence. Long route/class display labels are explicitly clipped;
  group IDs and counts are unchanged.
- `get_error_group`: inspect a group ID and up to 20 most-recent member requests,
  preserving all observed service siblings for each returned request.

Both MCP tools report omitted counts and keep full-snapshot aggregate counts.
Narrow the service, API, time or other filters when results are limited. Detail
is bounded by request count, not by serialized bytes; a request with many service
segments can still be large. HTTP/UI retains the full bounded summary model.

Use the same filters with `get_error_group` that produced the group list. Each
call evaluates a fresh retained snapshot; counts and membership may change
between calls. Then use `get_trace_visualization(trace_id=...)` for a selected
request or `get_flow(id=segmentId, service=..., instance=...)` for one segment's
source timeline. `get_segment_execution(segment_id=..., service=..., instance=...)`
provides the separate MCP invocation model. Example:

> List EMS HTTP 5xx error groups from the last 30 minutes. Show one representative
> request flow and explain what the captured evidence does and does not establish.

Reconnect MCP after upgrading. A group ID is a signature, not a stored incident;
members change as requests arrive or are evicted. Browser pins remain separate
and are not accessible through MCP.
To preserve a chosen request for a later terminal/assistant investigation, use
`save_request_capture` with its exact request trace ID, then the local capture
list/compare tools. This is an explicit bounded JSON save, not persistent error
group history or a browser-pin import. The
[developer workflow](developer-workflow.md) explains time/filter selection and
capture storage; save needed evidence before restarting the application.

Polling uses summary metadata only. Events/source lines are read on explicit
drill-down or pinning. Payloads, raw headers and source events are excluded from
group responses; API paths, user identities and service error text may still be
sensitive and must follow existing access/data policies.

See [request discovery and browser snapshots](request-focused-ui.md),
[visualization modes](hybrid-visualization.md), and the
[use-case guide](use-cases.md) for the next investigation step.
