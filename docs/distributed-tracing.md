# Cross-service HTTP tracing

TraceLens can run on **one service independently** or on **multiple services together**.
There is no runtime dependency on a central TraceLens server or on another agent.
The current implementation supports standalone-agent servlet servers (javax and jakarta),
a bounded Vert.x 3 HTTP/1 server adapter, outgoing JDK `HttpURLConnection`
(including its HTTPS delegate), and synchronous Apache HttpClient 4. Servlet/JDK
smoke tests cover Java 8/21; the new framework-specific tests use Vert.x 3.9.16 and
Apache HttpClient 4.5.14. This does not promise arbitrary framework, client-library
or JVM-version compatibility, or establish live-service validation.
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

The service templates [cb-app/tracelens.json](../scripts/cb-app/tracelens.json) and
[cb-ems/tracelens.json](../scripts/cb-ems/tracelens.json)
explicitly enable both HTTP client observation and distributed context. cb-app
permits propagation to explicitly configured local service authorities; EMS has
its own caller allowlist. Inspect the selected JSON rather than assuming a
downloaded bundle or existing installation has the latest destinations. Both
configurations also work independently. Set
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
ownership conflict, and the relevant tested transport/redirect guard. Metadata-only
observation can remain available when that guard is unavailable.

Attach the shaded agent with `=standalone`, the service-specific config path, and a
distinct `-Dtracelens.port`. Do not enable a legacy Spring/servlet TraceLens adapter
at the same time. Restart the JVM after changing the agent or configuration. Use
**Save snapshot** before a restart: agent retention is in memory. An inspection
export with `?evict=false` does not itself persist evidence or prevent expiry.

Existing cb-app launch integration uses `TRACELENS=1`; its selected service JSON
owns `httpIncludePaths` along with packages, identity and retention. New cb-app
configs include selected feature/entitlement, Plans/items, subscription/billing
and prepaid-credit paths; the selected template is the source of truth. An
existing custom list is preserved by setup. Edit that JSON, or use the setup
configuration dashboard, and restart the application to change
capture scope. Do not duplicate the list in a launcher variable or JVM property.
Omission uses `["/"]`, while `[]` disables retained HTTP entry capture; literal
prefixes match a path or its descendants, with built-in exclusions still applied.
These controls limit retention, not the transport mechanism. In distributed mode
excluded/disabled requests may carry a lightweight context without retaining a
local timeline. Recording controls remain local: disabling one agent's recording
does not disable recording in another service.

The three capture levels are independent of these entry/propagation rules:
`overview` retains supported boundaries and outcomes, `methods` also retains
scoped method entry/exit events, and `detailed` adds line events. SQL, parameters
and incoming request data remain separate opt-ins in every level. Narrow paths
and a timed detail window can fall back to overview; this is retention policy,
not removal of already-woven callbacks. See [capture settings](guided-setup.md).

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

Use the combined [Java distribution](java-distribution.md) in a separate JVM:

```bash
# Both: one read-only combined viewer
java -jar /path/to/tracelens.jar --services /absolute/path/services.json serve --port 7781

# Both: one stdio MCP connection
java -jar /path/to/tracelens.jar --services /absolute/path/services.json mcp

# Either service independently; these commands remain supported
java -jar /path/to/tracelens.jar --url http://127.0.0.1:7778 serve --port 7777 --source-root /path/to/chargebee-app
java -jar /path/to/tracelens.jar --url http://127.0.0.1:7779 serve --port 7780 --source-root /path/to/cb-ems

# Search by known API rather than needing a trace ID first
java -jar /path/to/tracelens.jar --services /absolute/path/services.json find-distributed --method GET --path /api/v2/features --since-minutes 30
java -jar /path/to/tracelens.jar --services /absolute/path/services.json get-distributed <trace-id>
```

For an MCP configuration, use `java` as the command; set arguments to
`["-jar", "/path/to/tracelens.jar", "--services", "/absolute/path/services.json", "mcp"]`.
The discovery tools are
`find_distributed_traces` and `get_distributed_trace`; existing single-segment tools
continue to work. Include the service/instance when a segment lookup is ambiguous.
Global CLI options precede the subcommand, for example
`java -jar /path/to/tracelens.jar --services /absolute/path/services.json --service cb-app --instance <instance-id> render <segment-id>`.
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

HTTP call records retain a bounded start-time calling-context anchor:
`callingContextStatus`, and, when available, `callingEventSeq`, `callingThread`
and runtime-local `callingThreadId`.
The core recorder attaches these at the first synchronous `ACTIVE` client record
and preserves them through response/completion updates. A completion thread cannot
replace the caller. Missing method/line evidence, known event truncation, or an
unobserved start leaves the location unavailable. These fields reference existing
events; they do not add line events, request bodies, or remote execution evidence.

The diagram inspector's **View caller in …** action resolves the exact caller segment and
HTTP span before reconstructing its active instrumented frame. It works from an
outgoing HTTP box, an untraced external-service box, or a linked downstream service
card, including folded hops. The service card's **Open code flow** remains the
downstream execution view. Source is observed context, not proof of an exact
callsite; newer thread tokens distinguish same-named threads only within their
runtime, legacy names may be ambiguous, and local source must match the captured build.
Older captures cannot acquire this anchor retroactively. Restart with the updated
agent and reproduce the request to collect it.

**Save snapshot** retains a separate graph/segment snapshot in the local viewer's
file store, including resolved source lines. **Copy snapshot link** reopens that
saved evidence across browsers using the same viewer configuration and storage;
it is not a public share link. Legacy browser-only pins remain in their original
browser unless explicitly copied with **Save to viewer**. Save important evidence
before restarting an agent; non-evicting reads do not defeat TTL, capacity limits
or reset.

For evidence that CLI and MCP can reuse after both JVMs stop, explicitly save
selected requests with `capture save` / `save_request_capture`. These bounded
local JSON files are separate from full viewer snapshots and legacy browser pins.
The viewer's **Saved captures** workspace can select a Before/After pair for
**Compare & search saved captures**, or explicitly create a minimized CLI capture
with **Use with CLI/MCP**.
CLI/MCP comparisons use the resulting capture IDs, not snapshot UUIDs. These are
not automatic, atomic cross-service history or a database.
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

### Apache HttpClient 4

The adapter targets synchronous `InternalHttpClient` and `MinimalHttpClient`
from the 4.3–4.5 implementation family; **4.5.14** is verified by focused tests.
Their public `execute` overloads, including `ResponseHandler` variants, converge
on one `doExecute` boundary. It records one logical operation through response
headers or an escaping exception, not body consumption or response-handler work.
Internal retries and redirects are not separate spans. Metadata retains the
original peer/path; query strings and matrix parameters are removed, and errors
record class names only. No request/response body, credentials or arbitrary
headers are read.

The existing HTTP capture/distributed/allowlist settings apply. A per-dispatch
guard on `MainClientExec` / `MinimalClientExec` checks each actual target, including
redirects. It injects into transport wrappers, not the caller's original request,
and strips only TraceLens-owned headers before a disallowed redirect. Existing
correlation headers are preserved as a conflict. Wrapper ownership is bounded to
128 objects per logical operation and cleaned up after execution. If stripping
cannot be guaranteed after injection, dispatch fails rather than forwarding
owned headers to an untrusted destination. Without the supported dispatch guard,
observation can remain available but propagation is `GUARD_UNAVAILABLE`.

Apache HttpClient 5, async clients, deprecated `AbstractHttpClient` implementations
and custom execution chains are outside this adapter's tested scope. Merely using
Spring `RestTemplate` does not guarantee coverage; its actual transport matters.

### Vert.x 3 HTTP/1

The server adapter accepts validated incoming W3C context and creates independent
segment IDs. Its registered callback wrappers keep request identity across the
supported body, worker, one-shot timer, future and HTTP-response callbacks while
restoring the previous thread context afterward. It never assigns a request's
identity to the shared event loop. Completion is response-end submission, not
wire delivery; disconnect/error cleanup is bounded. See the
[standalone scope](standalone-agent.md#vertx-http1-entry-points).

There is **no Vert.x outgoing span or header-injection adapter** in this change.
Capturing a Vert.x client's callback is context continuation, not evidence of a
captured outgoing HTTP call. An in-process proxy and its module server therefore
start separate roots when no actual incoming carrier is present. Do not join them
by path, time or process. HTTP/2, WebSocket and arbitrary RxJava scheduler hops are
not covered; tests exercise Vert.x 3.9.16, not every reactive application.

## Safety and current limits

- HTTP correlation hooks do not inspect bodies, query values, authorization headers
  or cookies. Separate incoming servlet query/Tomcat body opt-ins can retain
  bounded request data; they never enable Authorization/cookie-header capture.
  Only validated W3C correlation headers are injected. User/site identity is resolved
  independently in each service; a downstream service need not know the browser user.
- Existing outgoing `traceparent`/`tracestate` is preserved. Conflicting ownership is
  reported as `CONFLICT`, not silently overwritten or claimed as a connected trace.
  Integration with production Datadog/Brave ownership must be verified separately;
  this is not a completed bridge to every vendor's active span API.
- For JDK calls, redirects outside the allowlist strip only headers injected by
  TraceLens. Propagation is skipped if the tested JDK serialization guard is unavailable. In the exceptional
  case that an already-injected header cannot be removed before an external redirect,
  that redirect fails with an IOException rather than leaking those headers. Other
  HTTP observer failures are suppressed. Basic application line/method/exception
  observer failures are also contained and counted; resource exhaustion and
  initialization/transport failures still prevent a universal fail-open guarantee.
  Test the supported JVM before rollout. Apache uses its separate per-dispatch
  guard described above.
- Messaging observation is separate: `captureMessagingBoundaries` follows
  `instrumentMessaging` when omitted/null. Capture `true` with messaging
  instrumentation `false` observes supported local boundaries without SQS
  correlation attributes. Kafka send/poll does not propagate context or establish
  producer/consumer links, and submission is not confirmed delivery. See
  [implemented coverage](dependency-visibility-and-change-impact.md#implemented-coverage).
- SQS propagation uses bounded, allowlisted correlation attributes and skips
  conflicts or requests without enough attribute slots. It does not read or count
  the message body: adding attributes to a message already near SQS's total payload
  ceiling can still cause the send to be rejected. This is not a universal
  no-behavior-change guarantee for network propagation. Supported synchronous sends
  restore the prior attribute contents only if the injected map is unchanged;
  application mutation during the send is preserved and may leave injected fields,
  causing a later reuse to skip propagation because of the conflict.
- Other HTTP clients (Apache 5/async, OkHttp, Java 11 HttpClient, Vert.x/reactive clients), complete
  async-framework coverage, and distributed HTTP in legacy adapter mode are not included.
- Agent storage is bounded and **not durable**. Explicit viewer snapshots and
  minimized CLI captures persist separately on local disk; see [storage limits](java-capture-storage.md).
  No collector/database or production rollout is included. Production requires authenticated collection/access,
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

`VertxHttpWeavingTest` separately checks real 3.9.16 HTTP/1 completion, concurrent
callback isolation, incoming context, exclusions and disconnect cleanup.
`ApacheHttpClientWeavingTest` checks real 4.5.14 synchronous clients, header
ownership, redirect guards, failures, metadata privacy and overload deduplication.
These focused regressions are not evidence of live credit-stack flow validation.

An updated agent JAR or JSON capture configuration requires a target-JVM restart;
editing JSON alone does not require an application rebuild. Rebuild/replace the
combined JAR and restart the Java viewer/reconnect MCP for tooling updates;
reload the browser for asset changes. These operations do not backfill old traces.
