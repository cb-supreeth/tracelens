# Standalone agent: attach one jar

Standalone mode removes application dependencies and filter registration from
installation. The agent starts its own bounded store, lifecycle handlers and
loopback debug API. Request instrumentation detects `javax.servlet` and
`jakarta.servlet` interfaces and the supported Vert.x 3 HTTP/1 implementation
as classes load. Application source, browser code,
build files and `web.xml` do not need TraceLens changes.

For a prebuilt ZIP, run `./setup.sh` and follow [guided setup](guided-setup.md).
The same combined `tracelens.jar` provides the agent and separate setup/viewer/CLI/MCP
processes. Use **Set up TraceLens** first, then **Edit capture settings** on later
runs to edit or add supported services. Saving is separate from explicitly
reviewed starts/restarts. This page is the manual agent reference, not additional
steps to layer over guided setup; see [use cases](use-cases.md) for investigations and
the [documentation index](README.md) for the other guides. Java 8+ is the agent's
bytecode baseline, not a claim that every later JVM/framework combination is tested.

## Start

Use `tracelens.jar` from the Java ZIP. Maintainers can build the equivalent shaded
artifact with JDK 21:

```bash
./gradlew :tracelens-agent:shadowJar
```

Then attach it at application startup:

```bash
java -javaagent:/opt/tracelens/tracelens.jar=standalone \
  -Dtracelens.applicationId=orders \
  -Dtracelens.packages=com.example.orders \
  -jar orders.jar
```

For launcher scripts or containers, pass the same options using the launcher's
supported JVM options or `JAVA_TOOL_OPTIONS`. Set these on the application process,
not globally on unrelated build tools. Tomcat's `CATALINA_OPTS` is appropriate for
its startup scripts. Agent startup requires a JVM restart; live attachment is not
part of the tested standalone installation path.

`=standalone` is intentional. Attaching the jar without it retains the existing
application-adapter mode. `-Dtracelens.standalone=true` is an equivalent switch.

## Configuration

| Option | Meaning |
| --- | --- |
| `tracelens.applicationId` | Service name shown in metadata/exports; defaults to `cb-app` when no JSON config is supplied. Explicit names override the default. |
| `tracelens.applicationVersion` | Exported build/version label; defaults to `LOCAL`. |
| `tracelens.packages` | Comma-separated application packages for line/method capture. |
| `tracelens.configPath` | Optional existing JSON configuration; overrides the minimal package fallback. |
| `tracelens.port` | Separate loopback debug port; defaults to `7778`. Use a different port per JVM. `0` selects an ephemeral port, printed at startup. |

`tracelens.applicationId` and `tracelens.applicationVersion` override the same
JSON fields. `tracelens.packages` is only a fallback when no config file is
selected; it does not override that file's package scope.
Keep repeatable capture settings in that one service JSON: `httpIncludePaths`,
`packages`, identity hooks and retention. Launchers select the agent JAR, JSON
path and debug port; do not maintain an HTTP path list in launcher variables or
JVM options as a second source.

`captureMode` selects **Overview** (`overview`, dependency/error evidence),
**Methods** (`methods`, adds entry/exit without lines), or **Detailed**
(`detailed`, adds executed lines). Package scope and the independent `trackMethods` switch still
apply. Incoming HTTP query/body data, JDBC SQL/bindings and native ClickHouse
SQL/bindings are separate opt-ins. Narrow detail with `detailedCapturePaths` and
an optional absolute `detailedCaptureUntilEpochMillis`; outside that scope or
after expiry, capture depth falls back to Overview. These settings do not widen
HTTP admission or trusted propagation destinations. See the
[capture controls](sharing-and-configuration.md#capture-controls-and-defaults)
for default-versus-scoped behavior and bounds.

For example, merge this field into the selected config, preserving its other settings:

```json
{
  "httpIncludePaths": ["/api/orders", "/api/invoices"]
}
```

`httpIncludePaths` is an array of literal path prefixes. A prefix matches the
exact path or its descendants, not a wildcard pattern. Omission defaults to
`["/"]` for compatibility; an explicit `[]` disables retained HTTP entries.
Built-in ignored paths remain excluded even with `["/"]`. This does not disable
separate scheduler/queue hooks or guarantee that distributed context stops
propagating through excluded requests. Restart the target JVM after changing
the JSON; configuration is not hot-reloaded. For older cb-app launcher settings,
follow the [one-time migration](cb-app-integration.md#one-json-file-for-capture-settings).

JSON configurations still require an `applicationId` (or a system-property
override). Existing explicit names such as `cb-app` and `cb-ems` are unchanged.
`cb-app` is the TraceLens identity used in the bundled agent config and service
registry; `chargebee-app` is the repository/orchestrator name, not a different
service. Other applications should explicitly set their own identity. The Spring
starter, when synthesizing configuration without a JSON file, honors explicit
`tracelens.application-id`, then `spring.application.name`, before falling back
to `cb-app`. Standalone mode does not read Spring application properties.

These defaults describe the current source, not a deployed artifact. Rebuild the
agent and restart the target JVM to pick up a changed fallback; explicit JSON or
property identities and existing captures are not renamed. Check `applicationId`
in `/tracelens/summary` or `/tracelens/metadata` to confirm the running identity.

With no config and no packages, HTTP capture has metadata but no application
line events. The agent does not automatically weave every dependency in a large
application. Configure the relevant packages for executed source lines; the API
reports whether line capture is configured. Class files must contain line-number
information for source-line markers; reaching a marker does not prove that all
operations on that line completed.

The default local store is bounded to 200 retained traces and 500,000 events,
with a 60-minute idle TTL; it is not persistent. Standalone minimal config uses
`evictOnExport=false`. JSON configuration defaults to `evictOnExport=true` when
omitted; CLI/viewer/MCP inspection explicitly requests `evict=false`. Capacity,
TTL, reset and JVM restart can still remove live-store evidence. **Save snapshot**
preserves an explicit copy in the viewer's local file store, separate from agent
memory. Legacy browser pins remain browser-local until explicitly copied with
**Save to viewer**. Neither extends agent retention; CLI/MCP use a separate
minimized capture store. See [saved evidence workflows](developer-workflow.md).

Outbound observation and propagation are separate JSON options:

- `httpClientCaptureEnabled: true` observes supported JDK and synchronous Apache
  HttpClient 4 calls under an
  active trace, even without a downstream agent or an allowlisted destination.
  If omitted/null it follows `distributedTracingEnabled` (default `false`).
- `distributedTracingEnabled: true` enables incoming W3C context and, when HTTP
  capture is enabled, guarded outgoing propagation only to
  `httpPropagationAllowedDestinations`. It does not authorize arbitrary destinations.
- `captureMessagingBoundaries` controls supported local queue metadata; omitted/null
  follows `instrumentMessaging` (default `true`). Explicit capture `true` with
  `instrumentMessaging: false` observes boundaries without legacy SQS propagation.
  Neither flag provides universal queue coverage or Kafka context propagation.
- `captureDataStoreBoundaries` defaults to `true` when omitted. It observes the
  exact supported ClickHouse, Spring Data Redis, AWS SDK v1 DynamoDB and S3 client
  methods under an active trace, independently of the HTTP/messaging options and
  application line-capture package scope. Set it to `false` in the selected JSON
  to disable these hooks; no request headers or propagation are added.

See [configuration defaults](sharing-and-configuration.md#capture-controls-and-defaults)
and [dependency coverage](dependency-visibility-and-change-impact.md).

### Vert.x HTTP/1 entry points

The standalone adapter targets Vert.x 3 `HttpServerImpl`, `HttpServerRequestImpl`
and `HttpServerResponseImpl`; focused tests use **Vert.x 3.9.16**. It applies the
same `httpIncludePaths`, distributed-context and retention settings as servlet
entries. Health/static exclusions still apply. Captures identify the adapter as
`httpAdapter=vertx3-http1` and include `httpServerPort` to distinguish a local
proxy from its module servers.

Request identity is scoped to registered handlers, never stored on the shared
event-loop/Vert.x context. Supported continuations include request body handlers,
HTTP client response/body callbacks, `runOnContext`, `executeBlocking`, one-shot
`setTimer` callbacks and `FutureImpl.onComplete`. Previous thread context is
restored after each callback. The response registry uses weak keys, is capped at
1,024 in-flight entries, and removes entries on response end, close or error.
An uncommitted default status is not reported as a successful HTTP 200 after an
abort. `timingBoundary=response-end-submitted` means response end was submitted,
not that bytes reached the client.

This is **server capture and bounded callback propagation**, not a Vert.x client
span/injection adapter. An in-JVM proxy and its module request are separate
requests; only actual incoming W3C headers provide remote context. Shared paths,
threads or process IDs do not establish a causal link. HTTP/2, WebSocket,
`setPeriodic` and arbitrary RxJava schedulers are not covered. Other Vert.x
versions and live application flows require their own validation.

`/tracelens/summary` exposes `vertxHttpListenerRegistered`,
`vertxCapturedRequests` and `vertxInFlightRequests` under bridge diagnostics.
These counters confirm adapter activity, not complete business-flow coverage.

### Data-store client coverage

The automatic hooks use an explicit class/method allowlist:

- `com.clickhouse.client.api.Client`: `query`, `queryRecords`, `execute`, `insert`,
  `getTableSchema` and `getTableSchemaFromQuery`.
- Spring Data Redis `org.springframework.data.redis.core.RedisTemplate`,
  `DefaultValueOperations`, `DefaultHashOperations` and `DefaultSetOperations`:
  allowlisted value/hash/set, key, expiry, publish, callback and pipeline methods.
  Inherited `StringRedisTemplate` methods are included. Redis-compatible Valkey
  calls are labeled `redis`; the hook does not infer the backend vendor.
- `com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient`: `putItem`, `getItem`,
  `query`, `scan`, `deleteItem`, `updateItem`, `batchGetItem` and `batchWriteItem`.
- `com.amazonaws.services.s3.AmazonS3Client`: `putObject`, `getObject`,
  `deleteObject`, `listObjects` and `listObjectsV2`. Local presigned-URL generation
  is not an external call and is excluded.

See the [coverage details](dependency-visibility-and-change-impact.md#implemented-coverage)
for the full Redis registry and limits. This adds no client SDK dependencies and
does not create clients or network requests. It is not universal external-system
discovery: arbitrary JDBC drivers, AWS APIs/SDK v2, direct Lettuce/Jedis, reactive clients
and other library implementations need their own adapters.

PostgreSQL/MySQL JDBC SQL capture is a separate **default-off** opt-in through
`captureJdbcQueries`. Unlike these metadata-only hooks, it can record raw SQL
literals and client execution timing. See [local SQL query capture](jdbc-query-capture.md)
for configuration, bounds, supported drivers and sharing precautions.

Native ClickHouse SQL is an independent default-off opt-in through
`captureClickHouseQueries`, with `captureClickHouseParameters` for actual named
bindings. See [request data and ClickHouse SQL](request-data-and-clickhouse.md).
The metadata-only behavior below describes calls when SQL capture is not enabled;
native SQL capture observes completion and suppresses duplicate query metadata.

Events contain system, operation, boundary kind/direction, evidence kind, status
and optional exception class only. No SQL, keys/values, request/response payloads,
bucket/object names, endpoints, credentials or exception messages are read by
these hooks. They do not capture timings or cache hit/miss status.
`OBSERVED` means synchronous return, not a hit, commit, stream completion or
remote durability; `SUBMITTED` means an async-returning method returned, not that
its future succeeded. Futures are neither inspected nor awaited. `ERROR` records
only an exception escaping the observed method. Nested calls for one system are
collapsed, so a Redis callback/pipeline is one boundary, not an exact command
count. Existing trace/source/exception capture retains its separate data scope.

Rebuild the agent, restart the target JVM and take a new capture to see these
events. Existing traces and saved snapshots cannot be retrofitted by a viewer
reload or upgrade.

## Query by API

```bash
curl http://localhost:7778/tracelens/summary
curl http://localhost:7778/tracelens/traces
java -jar /path/to/tracelens.jar --url http://127.0.0.1:7778 --source-root /path/to/service mcp
```

The same combined JAR runs MCP in a separate process; do not attach `-javaagent`
to that reader process. For a generated multi-service setup, use the primary
`--config` from `start-viewer.sh` instead of a global URL/source root. See
[Java CLI/MCP setup](java-distribution.md).

Ask your MCP client to list flows matching `GET /api/orders`, select the relevant
time/user, and summarize the returned segment. HTTP request labels are formed
as `auto:<METHOD> <path>`. Labels with spaces, hyphens, slashes and trailing slashes
are supported by the CLI. A successful response normally has `X-TraceLens-Id`;
container-generated error responses can clear it, so API-label lookup remains
the reliable starting point.

The debug API lives on the agent's port. It does not install an application route
or bypass the application's authentication filters. It accepts direct loopback
requests and rejects cross-origin browser/forwarded requests. Incoming query
parameters and application-read Tomcat JSON/form bodies are available through
separate default-off, bounded capture switches. Auth headers, cookies and response
bodies are not collected. See [request data capture](request-data-and-clickhouse.md)
for redaction and omission rules. Optional action headers still work from any client.

The standalone API routes are:

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/tracelens/summary`, `/tracelens/metadata` | Runtime/store status and effective configuration. |
| GET | `/tracelens/traces`, `/tracelens/instrumented-classes` | Retained segment summaries and bounded class diagnostics. |
| GET | `/tracelens/find-by-lens-id/<encoded-label>` | Exact label lookup; CLI `find` encodes the label. |
| GET | `/tracelens/find-by-trace-id/<trace-id>` | Retained local segments for a trace ID. |
| GET | `/tracelens/export/<segment-id>?evict=false` | Read one retained segment without evicting it. |
| GET | `/tracelens/overview/<segment-id>` | Bounded metadata, HTTP calls and dependency/error previews; no full timeline or request payload. |
| GET | `/tracelens/events/<segment-id>?after=0&through=N&limit=500&maxBytes=262144&kind=all` | Non-evicting bounded event page. Obtain `through` from the overview, then follow `nextAfter` while `hasMore` is true. `kind` also accepts `methods` or `boundaries`. |
| GET | `/tracelens/request-data/<segment-id>` | Separately load the bounded retained request attributes, when captured. |
| POST | `/tracelens/enable`, `/tracelens/disable` | Change recording locally, not instrumentation/configuration. |
| POST or DELETE | `/tracelens/reset`, `/tracelens/reset/<segment-id>` | Delete all retained traces or one retained segment. |

Summary/metadata advertise `overview-v1`, `events-v1` and `request-data-v1`
capabilities. Page limits constrain encoded UTF-8 bytes as well as event count;
the maximum event response is 1 MiB. Pages carry trace, segment and service-instance
identity. Pin these plus `through` rather than following a request after a
restart. `detailGapBefore`, `detailTruncated` and omitted-detail/drop metadata
describe missing evidence; a consecutive retained sequence alone does not prove
complete execution. See [large captures](java-capture-storage.md#large-traces-and-paged-details).

## Compatibility boundary

One artifact can cover many **supported JVM applications**. It cannot promise
arbitrary languages, frameworks and future Java versions without testing them.

| Stack | Verification / behavior |
| --- | --- |
| Java 8 + Tomcat 7.0.54 / javax Servlet 3.0 | Standalone integration smoke test; matches the local cb-app servlet generation. |
| Java 21 + Tomcat 10.1 / jakarta Servlet | Standalone integration smoke test, including modern servlet filters. |
| Other servlet containers / intermediate JDKs | Interface-based hooks may apply, but require their own compatibility tests before claiming support. |
| Spring MVC | Uses servlet hooks. The current TraceLens Spring starter backs off when standalone mode is selected. |
| Vert.x 3 HTTP/1 | Focused real-client tests on 3.9.16 cover entry/completion, supported callbacks, overlapping requests and disconnect cleanup. See the bounded scope above; this is not blanket reactive-framework support. |
| WebFlux / arbitrary Netty servers / other Vert.x protocols | No automatic HTTP entry adapter. Do not assume servlet or Vert.x HTTP/1 support covers these servers. |
| Custom schedulers / non-servlet applications | Existing supported agent hooks may apply; otherwise an entry-point adapter is needed. |
| Node.js / Python / .NET / native images | Require runtime-specific instrumentation, not this Java agent. |

HTTP status, request timing, failures, line events within configured scope and
supported executor propagation are available. Spring's best-matching route is
recorded when present; normalized route templates are not universal. Business-specific
user/site identity, outgoing HTTP trace propagation and durable collection are
separate capabilities, not guaranteed by attachment.
Outgoing JDK HTTP/HTTPS and synchronous Apache HttpClient 4 correlation is
available as an explicit opt-in; see
[distributed tracing](distributed-tracing.md) for supported clients, the destination
allowlist, and combined or independent-service UI/MCP configuration.
Supported SQS, Kafka and Queueman metadata boundaries are described separately in
[dependency visibility](dependency-visibility-and-change-impact.md#implemented-coverage).
Kafka poll requires an active trace, a successful send return is not delivery
confirmation, and a queue name is not proof of a correlated receiver execution.

When startup configuration or the debug port is unavailable, standalone mode logs
the failure and lets the application continue. An unsupported HTTP framework
can result in no captured requests; check status and request counters instead of
assuming an attached agent proves end-to-end coverage.

## Existing installations

Do not run two TraceLens stores in one JVM. Updated Spring and javax adapters
back off when `tracelens.standalone=true` is set by the agent. For an older adapter,
remove its application registration/dependency or disable its legacy
`tracelens.enabled` setting before using standalone mode. Point MCP to the new
agent port, not the old application port. No production deployment is implied.

The cb-app startup helper now selects standalone mode. Its earlier installed
adapter and `web.xml` registration can be removed; only the JVM option hook is
needed. See [cb-app setup](cb-app-integration.md).
Its [optional Makefile launcher](cb-app-integration.md#optional-makefile-launcher)
is a template to copy explicitly: `up` can reuse an already-healthy JVM, while
`restart` loads the agent/config into an already-built app and clears Tomcat logs.

Restart the target JVM after replacing the agent JAR or changing its configuration;
editing JSON alone does not require an application rebuild. Replace/restart the
Java viewer JAR and reconnect MCP after tooling upgrades, and reload the browser
for asset changes. Viewer/MCP upgrades cannot backfill missing events in old captures.

## Verification

```bash
./gradlew test :samples:standalone-smoke:standaloneSmoke
```

Standalone smoke applications contain ordinary filters and servlets only. The
tests attach the delivered agent and verify API lookup, execution events,
filter-only handlers, nested calls without duplicate requests, failures, async
completion, and cleanup on actual Java 8 and Java 21 runtimes. Neither test
application receives a TraceLens adapter jar or filter registration.

Focused adapter regressions can be run independently:

```bash
./gradlew :tracelens-agent:test --tests '*VertxHttpWeavingTest' --tests '*ApacheHttpClientWeavingTest'
```

These use real Vert.x 3.9.16 and Apache HttpClient 4.5.14 clients/servers in tests;
their dependencies are not bundled in the delivered agent. Passing these tests
does not establish live middleware, Ledger or Grant Orchestrator coverage.
