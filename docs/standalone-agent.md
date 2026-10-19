# Standalone agent: attach one jar

Standalone mode removes application dependencies and filter registration from
installation. The agent starts its own bounded store, lifecycle handlers and
loopback debug API. Request instrumentation detects `javax.servlet` and
`jakarta.servlet` interfaces as classes load. Application source, browser code,
build files and `web.xml` do not need TraceLens changes.

Start here for installation; see [use cases](use-cases.md) for investigations and
the [documentation index](README.md) for the other guides. Java 8+ is the agent's
bytecode baseline, not a claim that every later JVM/framework combination is tested.

## Start

Build the single delivery artifact with JDK 21:

```bash
./gradlew :tracelens-agent:shadowJar
```

Then attach it at application startup:

```bash
java -javaagent:/opt/tracelens/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
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
| `tracelens.http.includePaths` | Optional comma-separated HTTP path prefixes, e.g. `/api/v2/features`. Defaults to all supported HTTP paths apart from noise exclusions. |

`tracelens.applicationId` and `tracelens.applicationVersion` override the same
JSON fields. `tracelens.packages` is only a fallback when no config file is
selected; it does not override that file's package scope.
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
TTL, reset and JVM restart can still remove live-store evidence. Browser pins
are separate browser-local snapshots, not durable agent retention or MCP storage.

Outbound observation and propagation are separate JSON options:

- `httpClientCaptureEnabled: true` observes supported JDK HTTP calls under an
  active trace, even without a downstream agent or an allowlisted destination.
  If omitted/null it follows `distributedTracingEnabled` (default `false`).
- `distributedTracingEnabled: true` enables incoming W3C context and, when HTTP
  capture is enabled, guarded outgoing propagation only to
  `httpPropagationAllowedDestinations`. It does not authorize arbitrary destinations.
- `captureMessagingBoundaries` controls supported local queue metadata; omitted/null
  follows `instrumentMessaging` (default `true`). Explicit capture `true` with
  `instrumentMessaging: false` observes boundaries without legacy SQS propagation.
  Neither flag provides universal queue coverage or Kafka context propagation.

See [configuration defaults](sharing-and-configuration.md#capture-controls-and-defaults)
and [dependency coverage](dependency-visibility-and-change-impact.md).

## Query by API

```bash
curl http://localhost:7778/tracelens/summary
curl http://localhost:7778/tracelens/traces
tracelens --url http://localhost:7778 mcp --source-root /path/to/service
```

Install the [Python CLI/MCP](sharing-and-configuration.md#4-install-the-cliviewermcp)
first. Always pass `--url` for standalone: the CLI's legacy default is still
`http://localhost:8082`, not the agent's `7778`.

Ask your MCP client to list flows matching `GET /api/orders`, select the relevant
time/user, and summarize the returned segment. HTTP request labels are formed
as `auto:<METHOD> <path>`. Labels with spaces, hyphens, slashes and trailing slashes
are supported by the CLI. A successful response normally has `X-TraceLens-Id`;
container-generated error responses can clear it, so API-label lookup remains
the reliable starting point.

The debug API lives on the agent's port. It does not install an application route
or bypass the application's authentication filters. It accepts direct loopback
requests and rejects cross-origin browser/forwarded requests. Request bodies,
auth headers, cookies and raw query values are not collected by the automatic
HTTP integration. Optional action headers still work from any client.

The standalone API routes are:

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/tracelens/summary`, `/tracelens/metadata` | Runtime/store status and effective configuration. |
| GET | `/tracelens/traces`, `/tracelens/instrumented-classes` | Retained segment summaries and bounded class diagnostics. |
| GET | `/tracelens/find-by-lens-id/<encoded-label>` | Exact label lookup; CLI `find` encodes the label. |
| GET | `/tracelens/find-by-trace-id/<trace-id>` | Retained local segments for a trace ID. |
| GET | `/tracelens/export/<segment-id>?evict=false` | Read one retained segment without evicting it. |
| POST | `/tracelens/enable`, `/tracelens/disable` | Change recording locally, not instrumentation/configuration. |
| POST or DELETE | `/tracelens/reset`, `/tracelens/reset/<segment-id>` | Delete all retained traces or one retained segment. |

## Compatibility boundary

One artifact can cover many **supported JVM applications**. It cannot promise
arbitrary languages, frameworks and future Java versions without testing them.

| Stack | Verification / behavior |
| --- | --- |
| Java 8 + Tomcat 7.0.54 / javax Servlet 3.0 | Standalone integration smoke test; matches the local cb-app servlet generation. |
| Java 21 + Tomcat 10.1 / jakarta Servlet | Standalone integration smoke test, including modern servlet filters. |
| Other servlet containers / intermediate JDKs | Interface-based hooks may apply, but require their own compatibility tests before claiming support. |
| Spring MVC | Uses servlet hooks. The current TraceLens Spring starter backs off when standalone mode is selected. |
| WebFlux / Netty / Vert.x HTTP | No automatic HTTP boundary adapter yet. Do not assume servlet support covers reactive servers. |
| Custom schedulers / non-servlet applications | Existing supported agent hooks may apply; otherwise an entry-point adapter is needed. |
| Node.js / Python / .NET / native images | Require runtime-specific instrumentation, not this Java agent. |

HTTP status, request timing, failures, line events within configured scope and
supported executor propagation are available. Spring's best-matching route is
recorded when present; normalized route templates are not universal. Business-specific
user/site identity, outgoing HTTP trace propagation and durable collection are
separate capabilities, not guaranteed by attachment.
Outgoing JDK HTTP/HTTPS correlation is now available as an explicit opt-in; see
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

Rebuild and restart the target JVM after agent/configuration changes. Restart the
viewer and reconnect MCP after Python-side upgrades, and reload the browser for
asset changes. Viewer/MCP upgrades cannot backfill missing events in old captures.

## Verification

```bash
./gradlew test :samples:standalone-smoke:standaloneSmoke
```

Standalone smoke applications contain ordinary filters and servlets only. The
tests attach the delivered agent and verify API lookup, execution events,
filter-only handlers, nested calls without duplicate requests, failures, async
completion, and cleanup on actual Java 8 and Java 21 runtimes. Neither test
application receives a TraceLens adapter jar or filter registration.
