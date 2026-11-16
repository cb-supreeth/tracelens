# External dependency visibility and change-impact investigations

Reviewed against repository source on **2026-10-28**. See the
[use-case catalog](use-cases.md) for the complete workflow/status overview and the
[documentation index](README.md) for setup and internals.
HTTP adapter coverage below is based on implementation
and focused tests; it does not imply live-service validation.

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

- **Caller observation:** supported client metadata is visible, such as the
  operation and, where captured by that adapter, destination or local result.
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
| Apache HttpClient 4 synchronous `InternalHttpClient` / `MinimalHttpClient` | Outbound method, sanitized original peer/path, timing through response headers, status/error and guarded propagation result; execute overloads deduplicated | Targets the 4.3–4.5 implementation family; focused tests use 4.5.14. No Apache 5, async, deprecated `AbstractHttpClient` or arbitrary custom execution-chain coverage. |
| AWS SDK v1 SQS `sendMessage` / `sendMessageBatch` request-object overloads | Queue name, operation, submission/error and optional batch count | No message body; no async SDK APIs or SDK v2 adapter. |
| Kafka `KafkaProducer.send` | Topic, submission/error; delegated send overloads are deduplicated | `SUBMITTED` is not delivery acknowledgement. No key, value, record headers or Future inspection. |
| Kafka `KafkaConsumer.poll` | Returned topic metadata and local delivery/error; raw events may contain a whole-poll count | Only inside an already active trace. Count is not per-topic. Does not create a trace for arbitrary poll loops, propagate record context or prove business-handler execution. |
| Queueman `AbstractQueuemanMessageHandler.processMessage` | Local processMessage boundary, known queue identity if supplied, failures escaping that boundary | Destination is `unknown` when not available. The framework may skip business handling or catch its failure internally; normal return does not prove processing/commit success. No arbitrary header enumeration or message body capture. |
| ClickHouse `com.clickhouse.client.api.Client` | Metadata boundaries for supported query/execute/insert/schema operations; separate `captureClickHouseQueries` / `captureClickHouseParameters` opt-ins add canonical native-query SQL and named scalar bindings | SDK 0.9.3 query seam validated locally. No result rows, settings, streaming insert payloads or universal SDK coverage. Metadata-only observations are not SQL executions. See [native query capture](request-data-and-clickhouse.md). |
| Spring Data Redis `org.springframework.data.redis.core.RedisTemplate`, `DefaultValueOperations`, `DefaultHashOperations`, `DefaultSetOperations` | Allowlisted public value/hash/set operations and template key, expiry, publish, callback and pipeline methods; labeled `redis` / `cache` | Inherited `StringRedisTemplate` methods are covered. A Redis-compatible Valkey server uses the same label; the hook does not identify the backend vendor. No cache hit/miss inference. |
| AWS SDK v1 `com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient` | `putItem`, `getItem`, `query`, `scan`, `deleteItem`, `updateItem`, `batchGetItem`, `batchWriteItem`; labeled `dynamodb` / `database` | No table names, keys, request data or result inspection. Not SDK v2 or arbitrary DynamoDB client implementations. |
| AWS SDK v1 `com.amazonaws.services.s3.AmazonS3Client` | `putObject`, `getObject`, `deleteObject`, `listObjects`, `listObjectsV2`; labeled `s3` / `external` | No bucket/object names, paths, content or result inspection. Local `generatePresignedUrl` signing and lifecycle/configuration methods are excluded. |
| Configured external wrappers | Method entry marker, wrapper system and method | A wrapper entry is not proof that a network request happened or succeeded. |
| `DB_ROUTE` | Captured datasource routing marker | Shows route/pool choice, not a SQL query or confirmed database round trip. |
| PostgreSQL/MySQL JDBC (explicit `captureJdbcQueries` opt-in) | SQL template or raw statement text, driver execution timing, return/error, observed batch size; separate `captureJdbcParameters` opt-in for supported typed positional bindings | Raw SQL/bindings are not sanitized. No result rows, connection credentials or JDBC exception messages. Reconstructed values are a display aid, not exact wire SQL. See [scope and limits](jdbc-query-capture.md). |

The four data-store integrations above use an exact class-name and method-name
allowlist, not a package-wide or arbitrary-network matcher. The complete Redis
operation list is in
[`DataStoreBoundaryRuntime`](../tracelens-bridge/src/main/java/com/chargebee/tracelens/bridge/DataStoreBoundaryRuntime.java).
Only public, non-static, non-synthetic concrete methods are instrumented. These
hooks are independent of application line-capture package scope, but still need
an active trace and registered sink; merely creating a client does not create a
trace or boundary.

Metadata-only data-store status describes the local API boundary only (the
separate native ClickHouse query recorder can observe supported future completion):

- `OBSERVED`: the synchronous method returned. It does not prove a cache hit,
  transaction commit, complete stream consumption or remote durability.
- `SUBMITTED`: a method declared to return a Future/CompletionStage returned.
  Futures are not inspected, awaited or replaced; even an already failed or
  cancelled future is not reported as successful completion.
- `ERROR`: an exception escaped that method; only its class name is recorded.
  A failure swallowed within the client cannot be inferred from normal return.

Delegating calls are collapsed to the outer operation per system. Redis
`execute` callbacks and `executePipelined` therefore produce one boundary for
the whole callback/pipeline, not one event per Redis command. These hooks do not
record timings, hit/miss status, result counts or network request counts. Direct
Lettuce/Jedis calls outside the supported Spring template paths, reactive Redis
clients and Spring Cache APIs are not independently instrumented.

Apache HttpClient 5/async, OkHttp, Vert.x HTTP clients, JDBC drivers outside the PostgreSQL/MySQL adapter,
arbitrary sockets and additional queue frameworks need specific adapters before
claiming coverage. A library
may internally use an already-supported client, but that must be verified rather
than assumed. This release does **not** provide universal network interception.

The separate Vert.x 3 HTTP/1 **server** adapter can create request traces and
propagate identity through its supported registered callbacks; focused tests use
3.9.16. This does not add Vert.x outgoing-call observation or injection. In
particular, callback context alone cannot establish an HTTP boundary or link an
in-JVM proxy to its module server. HTTP/2, WebSocket and arbitrary RxJava scheduler
hops remain outside the supported scope. See the
[server adapter limits](standalone-agent.md#vertx-http1-entry-points).

JDK client timing ends at response headers/error/disconnect; Apache synchronous
client timing ends when `doExecute` returns headers or throws, before body
consumption or response-handler work. Neither is streamed-body completion.
A redirect retains the original peer/path rather than producing one
record per redirect. Application scope and entry-path filters still determine
which executions have retained evidence. The current cb-app new-config template
includes selected entitlement, Plans/items, metering, subscription and billing
paths/packages; it is not whole-application impact coverage. An existing service
JSON or downloaded bundle may have a different, narrower list, which setup
preserves. Change entry paths and package scope in that same selected JSON, then
restart the application; do not keep a second allowlist in launch options.
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
  "instrumentMessaging": false,
  "captureDataStoreBoundaries": true
}
```

- If `httpClientCaptureEnabled` is omitted/null, it follows
  `distributedTracingEnabled` for compatibility. Explicit `true` observes
  supported calls even when their destination is not allowlisted. Propagation
  still requires distributed tracing, a valid W3C context, an explicitly trusted
  destination and the existing safe header/redirect checks. Capture `false`
  disables new HTTP observations/injection.
  Apache's supported per-dispatch guard checks redirect targets and removes only
  its owned headers outside the allowlist; without that guard it observes without
  injecting. The original caller request and existing correlation headers are
  not overwritten. See [Apache adapter behavior](distributed-tracing.md#apache-httpclient-4).
- If `captureMessagingBoundaries` is omitted/null, it follows the existing
  `instrumentMessaging` flag. Capture `true` and `instrumentMessaging: false`
  records local boundaries without enabling existing SQS attribute propagation.
  In capture-only mode, a Queueman handler can create a local consumer trace, but
  does not import producer correlation fields.
- `instrumentMessaging: true` retains existing single-message SQS continuation.
  Batch observation does not add batch propagation. Kafka capture does not inject
  headers or infer producer-to-consumer correlation.
- `captureDataStoreBoundaries` defaults to `true` when omitted. Set it to `false`
  to disable the exact ClickHouse, Spring Data Redis, DynamoDB and S3 hooks above.
  It is independent of HTTP/messaging capture, propagation and `capturePayloads`;
  enabling it never adds trace headers or changes client requests.

The selected service JSON is the source of truth for these settings. HTTP
propagation stays restricted to its caller allowlist; observing a Plans, Ledger
or GO call never adds that destination automatically. Setup can edit existing
capture settings but does not discover/widen propagation destinations.

`overview`, `methods` and `detailed` all retain supported boundaries when enabled.
The latter two additionally retain scoped method evidence, with line events only
in `detailed`. Independent SQL/parameter/request-data switches remain opt-in.
Choose the level, paths and optional detail window in the configuration dashboard;
saving and restarting are separate actions. A lower level reduces detail retention,
not all woven callback overhead, and cannot invent calling-code evidence.

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

The saved behavior comparison also uses these current mappings for display.
It marks the name as a configured label, retains the recorded address and caller,
and leaves matching/counts and saved captures unchanged. Unknown or conflicting
mappings keep the address; a label does not prove downstream execution was saved.

`cb-app` is the monolith's TraceLens `applicationId` and registry name, while
`chargebee-app` is its repository and lifecycle/orchestrator name. Keep that
distinction when selecting `service` or configuring `sourceRoot`; the source
directory name is not a correlation identity.

## Use the viewer or MCP

1. Reproduce a specific API/job scenario under capture, then locate its request
   using service, API, time and outcome filters.
2. Open **Flow diagram**. HTTP, queue publish/consume, database/cache client
   boundaries, external wrappers and DB routes have distinct labels. Select a
   boundary to inspect evidence and limits.
3. Select a service card, then choose **Open code flow** in its inspector; switch to **Call tree**
   for nesting or use execution search and **Focus selected branch** to reduce noise.
   The **Observed external boundaries** table brings boundary metadata above
   line-level noise. Graph cards stay fixed-size; grouped DB calls, request data,
   Copy and recorded-caller actions live in the adjacent inspector.
4. Open **Timeline / source**, then select **External boundaries and exceptions only**.
   This filters the display, not recording. HTTP calls remain in the boundary
   table because their capture is separate from line events.
5. Choose **Save snapshot** before restarting a service. It retains the graph and
   captured segment data in bounded viewer files, not a database. **Copy snapshot
   link** opens that evidence across browsers using this local viewer. Legacy
   browser pins can be copied explicitly with **Save to viewer**. Check warnings
   for missing evidence; saving cannot recover already evicted segments.

MCP's existing `get_trace_visualization` / `show_trace_visualization` tools use
the same model. `get_distributed_trace` includes `externalCalls`, while
`get_segment_execution` can show external markers within observed method nesting,
including an optional sanitized `boundary` object with direction, destination,
operation, status and evidence kind. Existing marker fields remain available.

For a focused starting point, `tracelens_doctor` checks setup without changing it;
`diagnose_capture_request` checks one exact backend path against running capture
settings, and the viewer's **Start an investigation** can explicitly watch and
save the next unambiguous completed request.
`investigate_request` selects by API path, scenario label or exact trace ID and
returns a bounded request journey. Ambiguous matches require narrowing the filters
or explicitly asking for the latest request. These tools do not replay requests or
prove a root cause.

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
   **Save snapshot** or `capture save` / `save_request_capture` to preserve their observed code paths
   and dependency boundaries with meaningful baseline names before a restart.
3. Apply the change, rebuild/restart as needed, and replay the same inputs against
   equivalent application state. Save candidate captures under separate names.
4. Select Before/After in **Saved captures → Compare & search saved captures** for two full viewer files,
   or `capture compare` / `compare_request_captures` for two minimized captures, to
   compare the two saved
   runs' method paths, exceptions, outbound operations and results offline.
   The behavior review leads with outcomes and external calls; changed methods
   are summarized at observed parents, with nested changes available on expansion.
   Names use configured service aliases without changing recorded identities.
   Inspect exact evidence and return to the same comparison; review comparability
   warnings before interpreting absence as a deliberately skipped call.
   Use the differences to choose regression and contract tests for the observed
   APIs/services/topics. Single-request timings are not performance benchmarks.
5. Review unexecuted branches, other callers and contracts using source and tests.
   Report these as potential/unknown impact, not as proved safe.

The tools provide individual traces and execution models for this analysis.
Viewer-file snapshots and CLI/MCP capture files support offline baseline/candidate differences
in methods, lines, nesting, dependencies, exceptions and outcomes; see the
[developer workflow](developer-workflow.md). **Search saved evidence** in the viewer
searches both stores; `capture search` / `search_saved_captures` search minimized
CLI files only. These are bounded observed-evidence lookups, **not an automatic
changed-method-to-all-workflows index or an `analyze_change_impact` MCP tool**.
**Use with CLI/MCP** explicitly creates a minimized capture from a viewer file
when an assistant needs it; legacy browser pins first need **Save to viewer**.

A future lightweight impact tool could combine a Git diff, explicit baseline/
after capture files and source references in the Java tool/MCP layer. Its output
should separate:

- **Observed:** a captured workflow executed the changed method/dependency.
- **Potential:** source references/contracts suggest an effect not exercised.
- **Unknown:** missing instrumentation, retention or scenario coverage.

Comparison uses explicitly saved, bounded local evidence; no database is
required. Minimized CLI/MCP captures are projected evidence, not full raw exports:
payloads, headers, raw exception messages, source text, user/context fields and
thread names are omitted. Full viewer snapshots are different: they can retain
events, resolved source and identity fields. New paged snapshots use compressed
chunks, with a bounded per-segment prefix for offline analysis; unsearched raw
details can still be present in the saved file. Review either format before sharing;
paths and dependency/code identifiers can still be sensitive even in a minimized
copy. New provenance includes an agent-JAR digest and effective-config fingerprint,
but a supplied application revision remains a label, not verification of the
running application's source. Missing observations or moved line numbers do not
establish code deletion or change safety.

Durable, team-wide historical usage analysis would be a separate storage and
access-control project. Unobserved code must never be labelled dead solely from
these captures.

## Implementation and operational cost

Java advice invokes the bootstrap bridge in the same JVM. Messaging and
data-store runtimes record small allowlisted maps through `TraceRuntime.onBoundary`
and `TraceSink`; HTTP uses its existing bounded connection-state tracker. These
callbacks do not call a TraceLens server, wait for Kafka acknowledgements or make
additional network requests. Publisher events describe exit from the local send
method; an in-flight send might not have emitted one yet.

The metadata-only data-store implementation adds no ClickHouse, Redis or AWS SDK dependency
to the agent/application. It matches known classes already supplied by the host
application. Its bridge receives only constant owner/method identity and the
escaping exception, not client instances, arguments or results. Exported fields
are `boundaryKind`, `system`, `direction`, `operation`, `evidenceKind`, `status`
and, on error, `errorType`. SQL, cache keys/values, AWS request objects, bucket/
object names, credentials, endpoints and exception messages are not collected
by these hooks. Existing line/exception/source capture has its own data scope.

The Java viewer/tooling projects boundary events before removing raw events from the
journey response. It applies field validation, output limits and display aliases;
the legacy reader implements the same bounded projection.
The existing browser renderer draws the model without a new chart package.
Live capture remains in the bounded JVM store; graph construction happens on
demand in the viewer/MCP process. Explicit CLI/MCP saves copy projected evidence
to local disk, with at most 20 captures, 5 MiB per file and 20 MiB total. Reaching
those limits rejects a save rather than deleting older captures. No database,
collector or third-party service is added.

Retention and output are bounded; overhead is not zero: advice, reflection,
event allocation, export/parse work and UI layout all cost something. No production
overhead claim or representative cb-app performance benchmark has been established here.
Upgraded agents supply compact overviews independently of detail pages; older
agents fall back to bounded full exports only when the overview endpoint is
unsupported. Failed detail reads keep available summaries with explicit partial
status. Federation has a shared 8-worker/64-queued pool and per-operation budgets
of 12 seconds, 512 reads and 32 MiB. See [storage and read budgets](java-capture-storage.md).
The graph permits
at most 128 service segments, 128 HTTP calls and 512 other boundary observations,
with at most 512 edges. The interactive renderer groups SQL observations before
its separate 96-displayed-node limit. Limits are surfaced explicitly; older saved
graphs retain any omissions made when they were saved.

Payloads, message keys/values and arbitrary headers are excluded from the new
metadata projection. URL credentials/query strings are removed; queue/topic,
service, method and path identifiers can still be sensitive. Existing trace
exports/source views have their own data scope. Normal access and AI-data
policies still apply.

## Activate an upgrade

Build the combined distribution with `./gradlew :tracelens-agent:javaBundle`.
Replace the JAR and restart each target JVM when agent hooks or configuration
change. Restart the Java viewer and reconnect MCP for tooling changes; reload
the viewer page for the assets.
New agent captures require a JVM restart and a newly exercised scenario. Old
traces and saved snapshots are not retrofitted with newly supported client events;
a viewer-only restart cannot recover boundaries absent from them.
Standalone `/tracelens/summary` and `/tracelens/metadata`
report the capture/propagation configuration, not proof of every adapter's
successful runtime coverage or that the loaded JAR matches the current source.
Verify with an actual scenario after restart.

See [distributed tracing](distributed-tracing.md),
[cb-app setup](cb-app-integration.md) and
[hybrid visualization](hybrid-visualization.md) for the existing launch workflows.
