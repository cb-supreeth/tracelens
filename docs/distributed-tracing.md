# Cross-service HTTP tracing

TraceLens can run on **one service independently** or on **multiple services together**.
There is no runtime dependency on a central TraceLens server or on another agent.
The current implementation supports standalone-agent servlet servers (javax and jakarta)
and outgoing JDK `HttpURLConnection`, including its HTTPS delegate, on tested Java 8/21
runtimes. It does not promise arbitrary client-library or JVM-version compatibility.
Local outgoing-call observation does not require propagation or an agent on the
receiver. See [dependency visibility](dependency-visibility-and-change-impact.md)
for HTTP/queue boundary coverage and [use cases](use-cases.md) for investigations.

## The three setups

| Setup | Agent installation | Viewer |
| --- | --- | --- |
| cb-app only | Agent on cb-app; local debug port 7778 | `--url http://127.0.0.1:7778` |
| EMS only | Agent on EMS; local debug port 7779 | `--url http://127.0.0.1:7779` |
| Both | One agent in each JVM, different debug ports | `--services /absolute/path/services.json` |

An uninstrumented downstream still receives an ordinary HTTP request. An instrumented
downstream with no incoming trace context starts its own root. A missing/offline agent
does not prevent another application or its single-service viewer from working.

`cb-app` is the TraceLens identity and registry name; `chargebee-app` is the
repository and lifecycle/orchestrator service name. Use the latter for checkout
paths and lifecycle commands. The [cb-app setup guide](cb-app-integration.md)
covers the startup hook and optional launcher; neither starts EMS or the viewer.

The example configs `scripts/cb-app-tracelens.json` and `scripts/cb-ems-tracelens.json`
explicitly enable both HTTP client observation and distributed context. cb-app
permits propagation to the local EMS authority; EMS has an empty outgoing
allowlist. Both configurations also work independently. Set
`distributedTracingEnabled` to `false` for local-only IDs and no new HTTP
propagation; retaining `httpClientCaptureEnabled: true` still observes supported
outgoing calls. When omitted, distributed tracing defaults to `false`, and HTTP
capture defaults to the distributed-tracing setting.

## Configure the agents

Add to each service's TraceLens config:

```json
{
  "httpClientCaptureEnabled": true,
  "distributedTracingEnabled": true,
  "httpPropagationAllowedDestinations": ["127.0.0.1:8082", "localhost:8082"]
}
```

This is an excerpt, not a complete agent config. Retain `applicationId`, version,
package scope, identity hooks and memory limits. The destination list is per caller:
use `[]` when this service must not inject outbound HTTP headers. That does not
disable its client-boundary observations. Exact hosts and ports are required; no wildcard,
URL path, credentials, or arbitrary request-supplied target is accepted. Host-only
entries refer to default ports. Update the list for the actual address the client uses.

`httpClientCaptureEnabled: false` prevents new client observations and injection
even if distributed mode is enabled; incoming server correlation can still work.
Observation `true` does not imply permission to propagate: injection also requires
distributed mode, valid context, an allowlisted authority, no existing header
ownership conflict, and the tested JDK redirect guard. Metadata-only observation
can remain available when that guard is unavailable.

Attach the shaded agent with `=standalone`, the service-specific config path, and a
distinct `-Dtracelens.port`. Do not enable a legacy Spring/servlet TraceLens adapter
at the same time. Restart the JVM after changing the agent or configuration. Back up
important traces with `?evict=false` before a restart: storage is still in memory.

Existing cb-app launch integration uses `TRACELENS=1`; its `TRACELENS_HTTP_PATHS`
defaults to `/api/v2/features`. That limits retained detailed traces, not the transport
mechanism. In distributed mode excluded/disabled requests may carry a lightweight
context without retaining a local timeline. Recording controls remain local: disabling
one agent's recording does not disable recording in another service.

## Run one UI or MCP for both

Create a trusted local registry, using absolute source paths appropriate to your machine:

```json
{
  "services": [
    {"name": "cb-app", "url": "http://127.0.0.1:7778", "sourceRoot": "/path/to/chargebee-app"},
    {"name": "cb-ems", "url": "http://127.0.0.1:7779", "sourceRoot": "/path/to/cb-ems"}
  ]
}
```

[The checked-in registry](../scripts/services-local.json) is a workstation-specific
template; copy it to your local `services.json` and replace the absolute source
paths before using the commands below. Service names and upstream URLs must each
be unique. A registry name is a local viewer/MCP alias, not an override of the
agent's `applicationId`; the bundled names deliberately match the agent configs.
Separate instances can use separate names and debug URLs. Source roots are selected
from this operator-owned registry, never from trace data or browser requests. Use the checkout
matching the captured application version; TraceLens does not automatically check out
historical commits.

The checked-in example also contains optional `dependencyAliases` for known
HTTP authorities. Those labels make observed dependencies readable; they do not
install agents, authorize propagation, or prove a connected receiver trace.

From the repo root, after installing the [Python CLI](sharing-and-configuration.md#4-install-the-cliviewermcp):

```bash
# Both: one read-only combined viewer
tracelens --services /absolute/path/services.json serve --port 7781

# Both: one stdio MCP connection
tracelens --services /absolute/path/services.json mcp

# Either service independently; these commands remain supported
tracelens --url http://127.0.0.1:7778 serve --port 7777 --source-root /path/to/chargebee-app
tracelens --url http://127.0.0.1:7779 serve --port 7780 --source-root /path/to/cb-ems

# Search by known API rather than needing a trace ID first
tracelens --services /absolute/path/services.json find-distributed --method GET --path /api/v2/features --since-minutes 30
tracelens --services /absolute/path/services.json get-distributed <trace-id>
```

For an existing MCP configuration, use the installed `tracelens` executable; set arguments
to `["--services", "/absolute/path/services.json", "mcp"]`. The discovery tools are
`find_distributed_traces` and `get_distributed_trace`; existing single-segment tools
continue to work. Include the service/instance when a segment lookup is ambiguous.
Global CLI options precede the subcommand, for example
`tracelens --services /absolute/path/services.json --service cb-app --instance <instance-id> render <segment-id>`.
Use `get_segment_execution` for an observed method model,
`get_trace_visualization` for request/dependency graph data, or
`show_trace_visualization` for the same graph with an optional MCP Apps presentation.
Presentation depends on the client's capabilities; the server also returns
text/Mermaid and a viewer location. See [visualization](hybrid-visualization.md).

The combined viewer labels services, shows HTTP call relationships, and opens each
segment in the existing line-level view using that service's source root. It reports
partial results when a service is unreachable, a segment is absent, or buffers truncate
data. A missing child is **not evidence that the call did not happen**. All inspection
exports use `evict=false`. Combined mode does not fan out reset/enable/disable commands;
use an explicit single-service connection for mutations.

New browser pins retain a separate browser-local graph/segment snapshot when
saving succeeds. They are not durable JVM storage or accessible through MCP, and
can be lost with browser-site data. Export/pin important evidence before restarting
an agent; non-evicting reads do not defeat TTL, capacity limits or reset.

For evidence that CLI and MCP can reuse after both JVMs stop, explicitly save
selected requests with `capture save` / `save_request_capture`. These bounded
local JSON files are separate from browser pins and support offline before/after
comparison. They are not automatic, atomic cross-service history or a database.
See the [focused developer workflow](developer-workflow.md#3-save-baseline-and-candidate-evidence).

## Wire context and data model

- `traceId`: shared 32-character lower-case hexadecimal W3C trace ID in distributed HTTP mode.
- `segmentId`: independent UUID used to export one service's line timeline. Never reuse
  a caller's segment as the callee's store key.
- `spanId`: server request span or outgoing client operation span, 16 hexadecimal characters.
- `parentSpanId`: remote server segment points to the caller's client span.
- `serviceInstanceId`: random runtime instance ID, changes at restart.
- `httpCalls`: bounded outgoing-call metadata on the owning segment (maximum 64 calls).

Exports have schema version 2 and retain existing fields. Older UUID-based trace exports
remain readable. Existing SQS `parentSegmentId` semantics are unchanged. No HTTP user,
action, or session labels are automatically transported to another service.

`X-TraceLens-Id` remains the local segment ID. `X-TraceLens-Trace-Id` additionally exposes
the shared ID. `GET /tracelens/find-by-trace-id/<id>` returns retained local segments;
the viewer queries its configured services and joins the explicit span links.

W3C version-00 `traceparent` is validated, including nonzero IDs and length. Invalid or
unsupported versions create a fresh root; invalid `tracestate` is discarded. Correlation
headers are untrusted diagnostic input, never authentication or permission to capture
payloads. The sampled flag is preserved; newly created roots use `00`. TraceLens's bounded
line recording policy is independent of another tracing vendor's sampled flag.

Client timing runs until response headers are available, not until a streamed body is
fully consumed. Client durations are monotonic within their JVM. Cross-JVM ordering uses
parent links; do not subtract raw `nanoTime` values or assume synchronized wall clocks.
Retries made as separate connections get separate call spans. Internal JDK retries and
redirects on one connection are one logical client operation, not separate attempt spans.

## Safety and current limits

- Agent hooks do not inspect bodies, query values, authorization headers or cookies.
  Only validated W3C correlation headers are injected. User/site identity is resolved
  independently in each service; a downstream service need not know the browser user.
- Existing outgoing `traceparent`/`tracestate` is preserved. Conflicting ownership is
  reported as `CONFLICT`, not silently overwritten or claimed as a connected trace.
  Integration with production Datadog/Brave ownership must be verified separately;
  this is not a completed bridge to every vendor's active span API.
- Redirects outside the allowlist strip only headers injected by TraceLens. Propagation
  is skipped if the tested JDK serialization guard is unavailable. In the exceptional
  case that an already-injected header cannot be removed before an external redirect,
  that redirect fails with an IOException rather than leaking those headers. Other
  HTTP observer failures are suppressed. Basic application line/method callbacks
  have different failure behavior; this is not a universal fail-open guarantee.
  Test the supported JVM before rollout.
- Messaging observation is separate: `captureMessagingBoundaries` follows
  `instrumentMessaging` when omitted/null. Capture `true` with messaging
  instrumentation `false` observes supported local boundaries without SQS
  correlation attributes. Kafka send/poll does not propagate context or establish
  producer/consumer links, and submission is not confirmed delivery. See
  [implemented coverage](dependency-visibility-and-change-impact.md#implemented-coverage).
- Other HTTP clients (Apache, OkHttp, Java 11 HttpClient, reactive clients), complete
  async-framework coverage, and distributed HTTP in legacy adapter mode are not included.
- Storage is bounded and **not durable**. No collector/database or production rollout
  is included in this milestone. Production requires authenticated collection/access,
  retention policy, representative overhead benchmarks, and tracing-vendor coexistence.

## Verification

```bash
JAVA_HOME=/path/to/jdk21 ./gradlew test :samples:standalone-smoke:standaloneSmoke :samples:standalone-smoke:distributedSmoke
cd tracelens-cli
uv run --extra test pytest -q
```

The distributed smoke suite launches isolated Java 8/Tomcat 7 and Java 21/Tomcat 10
processes, exercises both-agent and individual-agent topologies, and checks HTTP
correlation, POST-before-output injection, errors/timeouts, concurrency, malformed
headers, existing header ownership and redirects. These are separate from any real
cb-app/EMS deployment verification.

Agent or JSON configuration changes require rebuilding/restarting the target JVM
as appropriate. Restart the viewer and reconnect MCP for Python-side updates;
reload the browser for asset changes. These operations do not backfill old traces.
