# External dependency visibility and change-impact investigations

Reviewed against repository source on **2026-10-19**. See the
[use-case catalog](use-cases.md) for the complete workflow/status overview and the
[documentation index](README.md) for setup and internals.

TraceLens can answer **which dependencies and workflows were observed in a
captured execution**. It cannot promise that these are all possible effects of a
code change. This distinction applies even when every service has an agent:
unexecuted branches, unsampled requests, expired traces and unsupported clients
remain outside the evidence.

## See dependencies without installing TraceLens in every receiver

The caller's agent observes a supported client boundary. That is enough to show
an external node, even if the receiver has no agent or its debug API is unavailable.
For example, a cb-app request can show an HTTP call to EMS, another HTTP call to
Plans and a Kafka publish without tracing any of those receivers.

- **Caller observation:** destination, operation and the local result are visible.
- **Linked receiver segment:** when explicit trace/span correlation is available,
  the graph also shows the receiver's captured execution.
- **Remote execution not observed:** the caller boundary remains visible. This
  does not mean the receiver is offline, and does not prove it processed the call.

Service aliases are display labels, not correlation evidence. Matching topic
names, URLs or timestamps never creates a fabricated producer/consumer link.
Queue-consume arrows point into the local segment; publish arrows point out of
it. They describe boundary ownership, not a synchronized global timeline.

## Implemented coverage

| Integration | What is observed | Important limit |
| --- | --- | --- |
| JDK `HttpURLConnection` / HTTPS | Outbound method, sanitized peer/path, local timing, status/error, propagation result | Current client weaving is installed in standalone mode and requires an active trace. Does not cover every HTTP library. |
| AWS SDK v1 SQS `sendMessage` / `sendMessageBatch` request-object overloads | Queue name, operation, submission/error and optional batch count | No message body; no async SDK APIs or SDK v2 adapter. |
| Kafka `KafkaProducer.send` | Topic, submission/error; delegated send overloads are deduplicated | `SUBMITTED` is not delivery acknowledgement. No key, value, record headers or Future inspection. |
| Kafka `KafkaConsumer.poll` | Returned topic metadata and local delivery/error; raw events may contain a whole-poll count | Only inside an already active trace. Count is not per-topic. Does not create a trace for arbitrary poll loops, propagate record context or prove business-handler execution. |
| Queueman `AbstractQueuemanMessageHandler.processMessage` | Local processMessage boundary, known queue identity if supplied, failures escaping that boundary | Destination is `unknown` when not available. The framework may skip business handling or catch its failure internally; normal return does not prove processing/commit success. No arbitrary header enumeration or message body capture. |
| Configured external wrappers | Method entry marker, wrapper system and method | A wrapper entry is not proof that a network request happened or succeeded. |
| `DB_ROUTE` | Captured datasource routing marker | Shows route/pool choice, not a SQL query or confirmed database round trip. |

Apache HttpClient, OkHttp, JDBC query spans, arbitrary sockets and additional
queue frameworks need specific adapters before claiming coverage. A library
may internally use an already-supported client, but that must be verified rather
than assumed. This release does **not** provide universal network interception.

HTTP timing ends at response headers/error/disconnect, not streamed-body
completion. A redirect retains the original peer/path rather than producing one
record per redirect. Application scope and entry-path filters still determine
which executions have an active trace. The local cb-app example currently scopes
line capture to entitlements and defaults HTTP entry capture to
`/api/v2/features`; it is not whole-application impact coverage.
The local cb-app and EMS setup uses standalone mode. This change does not retrofit
HTTP client weaving into legacy application-adapter startup.

## Configuration: observation is separate from propagation

For local observations without adding cross-service trace headers or SQS
attributes:

```json
{
  "httpClientCaptureEnabled": true,
  "distributedTracingEnabled": false,
  "captureMessagingBoundaries": true,
  "instrumentMessaging": false
}
```

- If `httpClientCaptureEnabled` is omitted/null, it follows
  `distributedTracingEnabled` for compatibility. Explicit `true` observes
  supported calls even when their destination is not allowlisted. Propagation
  still requires distributed tracing, a valid W3C context, an explicitly trusted
  destination and the existing safe header/redirect checks. Capture `false`
  disables new HTTP observations/injection.
- If `captureMessagingBoundaries` is omitted/null, it follows the existing
  `instrumentMessaging` flag. Capture `true` and `instrumentMessaging: false`
  records local boundaries without enabling existing SQS attribute propagation.
  In capture-only mode, a Queueman handler can create a local consumer trace, but
  does not import producer correlation fields.
- `instrumentMessaging: true` retains existing single-message SQS continuation.
  Batch observation does not add batch propagation. Kafka capture does not inject
  headers or infer producer-to-consumer correlation.

The local cb-app config explicitly enables capture-only messaging while leaving
`instrumentMessaging: false`. HTTP header propagation remains restricted to its
existing EMS allowlist; observing a Plans call does not add Plans to that list.
EMS's existing messaging setting is unchanged.

Optional names belong in the viewer/MCP services configuration, per caller:

```json
{
  "services": [{
    "name": "cb-app",
    "url": "http://127.0.0.1:7778",
    "sourceRoot": "/path/to/chargebee-app",
    "dependencyAliases": {
      "127.0.0.1:8082": "cb-ems",
      "127.0.0.1:8081": "cb-plans",
      "billing-events": "Billing events topic"
    }
  }]
}
```

These are exact, validated target-to-name mappings, not substring rules. They
do not create outbound requests, expand trusted destinations or require the
named dependency to be a configured TraceLens service.

`cb-app` is the monolith's TraceLens `applicationId` and registry name, while
`chargebee-app` is its repository and lifecycle/orchestrator name. Keep that
distinction when selecting `service` or configuring `sourceRoot`; the source
directory name is not a correlation identity.

## Use the viewer or MCP

1. Reproduce a specific API/job scenario under capture, then locate its request
   using service, API, time and outcome filters.
2. Open **Flow diagram**. HTTP, queue publish/consume, external wrappers and DB
   routes have distinct labels. Select a boundary to inspect evidence and limits.
3. Open the actual service node's **Open segment sources**. The **Observed
   external boundaries** table brings boundary metadata above line-level noise.
4. Select **External boundaries and exceptions only** in **Timeline view**.
   This filters the display, not recording. HTTP calls remain in the boundary
   table because their capture is separate from line events.
5. Pin an important capture before restarting a service. New pins retain a bounded
   snapshot of the graph and captured segment data; pins use browser-local
   IndexedDB, not a shared service database. Check warnings for missing evidence.

MCP's existing `get_trace_visualization` / `show_trace_visualization` tools use
the same model. `get_distributed_trace` includes `externalCalls`, while
`get_segment_execution` can show external markers within observed method nesting,
including an optional sanitized `boundary` object with direction, destination,
operation, status and evidence kind. Existing marker fields remain available.

For a focused starting point, `tracelens_doctor` checks setup without changing it;
`investigate_request` selects by API path, scenario label or exact trace ID and
returns a bounded request journey. Ambiguous matches require narrowing the filters
or explicitly asking for the latest request. Neither tool replays requests or
proves a root cause.

Example investigation prompt:

> Find my latest captured feature request. Show its request-flow graph, including
> HTTP, Kafka and Queueman boundaries. Distinguish recorded client calls, wrapper
> entries and linked receiver execution. Do not interpret missing receiver traces
> as downtime or queue submission as completed consumption.

## How this helps with a code change

Suppose a shared entitlement method changes. A useful investigation has two
directions: **which entry points reached this method**, and **which downstream
operations were reached from those executions**.

Today an engineer can:

1. Identify changed methods from the diff and inspect static references/callers.
2. Exercise representative API and job scenarios before the change. Use
   `capture save` / `save_request_capture` to preserve their observed code paths
   and dependency boundaries with meaningful baseline names before a restart.
3. Apply the change, rebuild/restart as needed, and replay the same inputs against
   equivalent application state. Save candidate captures under separate names.
4. Use `capture compare` / `compare_request_captures` to compare the two saved
   runs' method paths, exceptions, outbound operations and results offline.
   Use the differences to choose regression and contract tests for the observed
   APIs/services/topics. Single-request timings are not performance benchmarks.
5. Review unexecuted branches, other callers and contracts using source and tests.
   Report these as potential/unknown impact, not as proved safe.

The tools provide individual traces and execution models for this analysis.
Explicit CLI/MCP capture files now support offline baseline/candidate differences
in methods, lines, nesting, dependencies, exceptions and outcomes; see the
[developer workflow](developer-workflow.md). **There is not yet an automatic
changed-method-to-all-workflows index, capture-diff viewer UI or
`analyze_change_impact` MCP tool.** Browser pins remain separate and unreadable
by MCP; use the explicit file-capture workflow when the assistant needs both runs.

A future lightweight impact tool could combine a Git diff, explicit baseline/
after capture files and source references in the Python/MCP layer. Its output
should separate:

- **Observed:** a captured workflow executed the changed method/dependency.
- **Potential:** source references/contracts suggest an effect not exercised.
- **Unknown:** missing instrumentation, retention or scenario coverage.

The initial comparison already uses explicitly saved, bounded local JSON files;
no database is required. These files are projected evidence, not full raw exports:
payloads, headers, raw exception messages, source text, user/context fields and
thread names are omitted. Paths and dependency/code identifiers can still be
sensitive. A revision is an operator-supplied label, not verification of the
running build; missing observations or moved line numbers do not establish code
deletion or change safety.

Durable, team-wide historical usage analysis would be a separate storage and
access-control project. Unobserved code must never be labelled dead solely from
these captures.

## Implementation and operational cost

Java advice invokes the bootstrap bridge in the same JVM. The new messaging
runtime records small allowlisted maps through `TraceRuntime.onBoundary` and
`TraceSink`; HTTP uses its existing bounded connection-state tracker. These
callbacks do not call a TraceLens server, wait for Kafka acknowledgements or make
additional network requests. Publisher events describe exit from the local send
method; an in-flight send might not have emitted one yet.

The Python layer projects boundary events before removing raw events from the
journey response. It applies field validation, output limits and display aliases.
The existing browser renderer draws the model without a new chart package.
Live capture remains in the bounded JVM store; graph construction happens on
demand in the viewer/MCP process. Explicit CLI/MCP saves copy projected evidence
to local disk, with at most 20 captures, 5 MiB per file and 20 MiB total. Reaching
those limits rejects a save rather than deleting older captures. No database,
collector or third-party service is added.

Retention and output are bounded; overhead is not zero: advice, reflection,
event allocation, export/parse work and UI layout all cost something. No production
overhead claim or representative cb-app performance benchmark has been established here.
Exports still fetch full retained segments before projection. The graph permits
at most 128 service segments, 128 HTTP calls and 128 other boundary nodes; the
interactive renderer has its own 96-node limit. Limits are surfaced explicitly.

Payloads, message keys/values and arbitrary headers are excluded from the new
metadata projection. URL credentials/query strings are removed; queue/topic,
service, method and path identifiers can still be sensitive. Existing trace
exports/source views have their own data scope. Normal access and AI-data
policies still apply.

## Activate an upgrade

Build the shaded agent with `./gradlew :tracelens-agent:shadowJar`, then restart
each target JVM with its existing TraceLens launch setup. Restart the viewer and
reconnect MCP to load the Python changes; reload the viewer page for the assets.
New agent captures require a JVM restart. A viewer-only restart cannot recover
boundaries absent from old traces. Standalone `/tracelens/summary` and `/tracelens/metadata`
report the capture/propagation configuration, not proof of every adapter's
successful runtime coverage or that the loaded JAR matches the current source.
Verify with an actual scenario after restart.

See [distributed tracing](distributed-tracing.md),
[cb-app setup](cb-app-integration.md) and
[hybrid visualization](hybrid-visualization.md) for the existing launch workflows.
