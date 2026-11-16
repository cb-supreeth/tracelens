# Optional UI action integration

The normal [request-focused workflow](request-focused-ui.md) finds retained
requests by API, service, user/site and time without any frontend change. This
optional integration adds a named **action ID** when one intentional operation
starts several API requests, such as a wizard submission or a multi-request save.
TraceLens does not install browser instrumentation, wrap application `fetch`, or
automatically identify button clicks. The application must opt in and send these
headers; the viewer and MCP server do not require this integration.

An action ID groups user intent across requests. It is not a distributed trace
ID or proof that every downstream execution was captured. Independent requests
in one action may still have different trace IDs.

## Frontend contract

At the beginning of a meaningful operation, create an opaque UUID or ULID and
hold it only for the requests that belong to that operation. Do not send cookies,
tokens, payloads, or PII in these headers.

```ts
type TraceLensAction = {
  id: string;
  name: string;       // e.g. "update-user-licence"
  sessionId?: string; // optional random per browser session
};

function startTraceLensAction(name: string): TraceLensAction {
  return {
    id: crypto.randomUUID(),
    name,
    sessionId: sessionStorage.tracelensSessionId
      ?? (sessionStorage.tracelensSessionId = crypto.randomUUID()),
  };
}

async function tracedFetch(action: TraceLensAction, input: RequestInfo, init: RequestInit = {}) {
  const headers = new Headers(init.headers ?? (input instanceof Request ? input.headers : undefined));
  headers.set("tracelens-action-id", action.id);
  headers.set("tracelens-action-name", action.name);
  if (action.sessionId) headers.set("tracelens-session-id", action.sessionId);
  return fetch(input, { ...init, headers });
}
```

Use the same `action` object for follow-up API calls that are part of the button
click or wizard step. Start a new action for a distinct click, page navigation, or
form submission. Do not automatically attach it to polling, analytics, static
assets, or background refreshes.
Apply this wrapper only to your application's trusted API destinations; do not
leak action/session identifiers to arbitrary third-party requests. The example
is application-side code to adapt deliberately, not an automatically installed SDK.

If the UI and API are cross-origin, allow the three request headers in the API
CORS configuration and expose `X-TraceLens-Id`, `X-TraceLens-Action-Id`, and
`X-TraceLens-Action-Name` if the browser needs to read the response headers.

The supported servlet/standalone HTTP boundaries retain these fields when the
request is eligible for capture. Sending headers does not override capture
scope, recording state or retention limits. Supported executor/SQS continuation
can carry action metadata when configured. HTTP W3C propagation carries correlation
IDs, not these action/user fields automatically; cross-service action labels need
separate deliberate forwarding. Metadata-only Kafka publish/consume
observations, for example, do not add record-header propagation. See
[dependency visibility](dependency-visibility-and-change-impact.md).

## Using it through MCP

After configuring TraceLens MCP in a capable client and reproducing an issue,
ask it:

```text
Find my latest update-user-licence action and explain the failed flow.
```

A suitable tool sequence is:

1. `find_actions(action_name="update-user-licence", user=..., since_minutes=30)`.
2. `get_action(action_id=...)` to list the currently retained member segments.
3. `get_flow_summary(id=segmentId, service=..., instance=...)` for compact facts.
4. `get_flow(id=segmentId, service=..., instance=..., max_events=400)` for relevant
   line-level evidence, or `get_segment_execution(segment_id=segmentId, ...)`
   for an observed invocation model. Use discovered service/instance values.

`find_actions` uses action-name/user substrings and recent **last activity**,
defaults to 30 minutes and 20 returned action groups, and orders newest activity
first. `get_action` selects an exact action ID from a fresh retained summary read;
it is not a frozen record of all requests that ever belonged to that action.
Both work within the summary set returned by the configured services; federation
uses its default 1,000-summary discovery limit for these action tools.
The returned lifecycle status is not a complete business-outcome diagnosis.
Neither tool reads saved files or browser pins. An empty result may mean headers were absent,
capture was not enabled for the entry point, the time filter excluded the action,
or data is no longer retained.

For cross-service evidence, discover the distributed trace ID and use
`get_trace_visualization(trace_id=...)` or `get_distributed_trace(trace_id=...)`.
An action ID alone does not create causal links. Host-side rendering is optional;
the tools remain usable as text/structured evidence.

## What is persisted today

Action fields are retained in the same bounded in-memory TraceLens store as the
trace. There is no database-backed action history or durable collector. An
explicit **Save snapshot** keeps one retrieved request in the viewer's private
local store. New captures use [paged V2 files](java-capture-storage.md) with ZIP
download/import; existing JSON snapshots remain readable. Legacy browser-only
pins stay in that browser until **Save to viewer**
is chosen. Neither freezes the whole action or automatically makes the full
snapshot available through MCP.

CLI/MCP now also support [explicit saved request captures](developer-workflow.md#3-save-baseline-and-candidate-evidence)
in minimized local JSON files, separate from full viewer snapshots and browser
pins. After discovering an action's
member requests, save each desired distributed trace separately with
`save_request_capture(name=..., trace_id=...)` before restart or eviction. These
are bounded, sanitized execution snapshots, not an archive of all action fields
or an atomic multi-request action capture. `list_request_captures`,
`compare_request_captures` and `search_saved_captures` work offline on that
minimized capture store; `find_actions` and `get_action` still query live
retained summaries, not saved files.

In **Saved captures**, select **Before** and **After**, then **Compare selected**
to compare two viewer snapshots offline. **Use with CLI/MCP** explicitly creates a new minimized copy
of a selected viewer snapshot in the CLI/MCP store without changing the original.
Use the same `--capture-dir` for viewer and CLI/MCP when overriding its default.
Saving or searching an individual request still does not establish the complete
set of requests belonging to its UI action.

Durable shared action history is a future storage design, not a setup step for
this feature. If built, asynchronous summary/timeline export would need explicit
redaction, sampling, retention and access controls; it should not add synchronous
database writes to the request path.
