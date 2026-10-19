# Sharing and configuring TraceLens

The recommended installation is [standalone agent mode](standalone-agent.md).
Share one shaded Java-agent jar and the CLI/MCP installation instructions. The
agent runs inside the target JVM and supplies its own store and loopback debug
API; the application needs no TraceLens dependency, filter registration or browser
integration. A shared `tracelens.json` is optional.
For investigation recipes, see [use cases](use-cases.md); for the whole guide
set, see the [documentation index](README.md).

The same artifact supports the tested Java 8 / Tomcat 7 (`javax.servlet`) and
Java 21 / Tomcat 10 (`jakarta.servlet`) setups. Other runtimes and servers need
compatibility verification; attachment does not imply automatic HTTP support for
non-servlet servers such as Netty/WebFlux. See the
[compatibility table](standalone-agent.md#compatibility-boundary).

```text
Target service
  1. One shaded Java agent attached at JVM startup with =standalone
  2. Package scope for line capture; optional JSON configuration

Developer machine
  3. TraceLens CLI, browser viewer or stdio MCP pointed at the agent's debug port
```

For early adoption, share TraceLens as this internal repository plus built artifacts.
For wider adoption, distribute a versioned shaded agent jar through an internal
artifact repository and the Python CLI through an internal package index. Maven
modules are needed only for the optional legacy application-adapter setup below.

## Local setup from a checkout

### 1. Build or obtain the agent

```bash
cd ~/work/cb-tracelens

./gradlew :tracelens-agent:shadowJar
```

`~/work/...` paths in this guide are examples, not installation requirements.
This builds the only valid Java-agent artifact:

```text
tracelens-agent/build/libs/tracelens-agent-0.1.1-SNAPSHOT-agent.jar
```

Build with JDK 21, or use a supplied built jar on the supported target JDK. No
`publishToMavenLocal`, Maven repository change or application rebuild is needed
for standalone installation. Do not use the unclassified, thin agent jar with
`-javaagent`.

### 2. Attach it at startup

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
  -Dtracelens.applicationId=my-service \
  -Dtracelens.packages=com.example.service \
  -jar service.jar
```

Use your launcher's JVM options for applications that do not launch with `java
-jar`. For example, Tomcat startup scripts accept `CATALINA_OPTS`. Restart the
application to attach the agent; live attachment is not the tested installation
path. `-Dtracelens.standalone=true` is equivalent to `=standalone`.

`tracelens.packages` accepts comma-separated application packages. Omitting it
and the JSON config leaves HTTP capture at metadata level, without application
line events; it does not weave every dependency. Add a narrow package scope for
executed source-line markers. Compiled classes need line-number information;
reaching a marker is not proof that every operation on that line completed.

The agent's debug API uses `http://127.0.0.1:7778`, independent of the application's
HTTP port. Give each traced JVM a different debug port, for example
`-Dtracelens.port=7779` for a second service. A busy debug port disables the
standalone runtime with a startup message while the application continues.

#### Service identity

Without a JSON file, the agent's current source defaults `applicationId` to
`cb-app`; other applications should set `-Dtracelens.applicationId` explicitly.
With a JSON file, `applicationId` is required unless supplied by that system
property, which overrides the file. A changed source default does not rename an
explicit identity, an existing jar, a running JVM or older captures.

The optional legacy Spring starter, when synthesizing configuration without a
JSON file, uses explicit `tracelens.application-id`, then
`spring.application.name`, then `cb-app`. The standalone agent does not read
Spring's application properties. The cb-app repository and lifecycle service are
named `chargebee-app`; its bundled TraceLens config and registry use `cb-app`.
See the [cb-app integration guide](cb-app-integration.md), including its optional
`Makefile.tracelens` launcher template and restart caveats.

### 3. Optionally share `tracelens.json`

Use a JSON file when the team needs shared instrumentation and retention settings,
for example `local-setup/tracelens.json`:

```json
{
  "applicationId": "cb-ems",
  "applicationVersion": "LOCAL",
  "packages": ["com.chargebee.ems"],
  "excludedPackages": ["com.chargebee.ems.api.model"],
  "blacklistedNodes": ["ToStringBuilder"],

  "capturePayloads": false,
  "maxTrackedEvents": 500000,
  "maxTrackedRequests": 200,
  "idleTtlMinutes": 60,
  "evictOnExport": false,

  "trackMethods": true,
  "trackExceptions": true,
  "instrumentExecutors": true,
  "instrumentMessaging": false,
  "captureMessagingBoundaries": true,
  "instrumentScheduled": true,

  "requireLabels": false,
  "httpClientCaptureEnabled": true,
  "distributedTracingEnabled": false,
  "httpPropagationAllowedDestinations": []
}
```

[`scripts/cb-ems-tracelens.json`](../scripts/cb-ems-tracelens.json) is a fuller
`cb-ems` example. The example above chooses local HTTP/messaging observation
without adding trace headers or SQS attributes. The checked-in EMS config instead
enables distributed HTTP context, retains the default messaging behavior and sets
`evictOnExport: true`; it is not identical to this example.

Select the file at startup. When provided, it replaces the minimal standalone
package configuration. `-Dtracelens.applicationId` and
`-Dtracelens.applicationVersion` still override the corresponding JSON fields;
`-Dtracelens.packages` does not override a selected file:

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar=standalone \
  -Dtracelens.configPath=/absolute/path/to/local-setup/tracelens.json \
  -jar service.jar
```

Start with a narrow package scope. Instrumenting an entire service package may
be appropriate for a controlled local session, but is not necessarily a shared
environment default. `-Dtracelens.http.includePaths=/api/orders,/api/invoices`
can further restrict HTTP capture by path prefix. This setting does not disable
separate scheduled-job or messaging hooks.

### Capture controls and defaults

These are code defaults, not promises about the scope of a particular service's
sample configuration. Changes to these settings require a JVM restart.

| JSON setting | Default when omitted | Effect and boundary |
| --- | --- | --- |
| `distributedTracingEnabled` | `false` | Incoming W3C HTTP context and permission for guarded outbound correlation, not universal network capture. |
| `httpClientCaptureEnabled` | Follows `distributedTracingEnabled` | Explicit `true` observes supported JDK HTTP calls under an active trace, including uninstrumented or non-allowlisted receivers. `false` disables new client observations/injection. |
| `httpPropagationAllowedDestinations` | `[]` | Exact trusted host/port authorities for injecting headers. Observation does not expand this list. |
| `instrumentMessaging` | `true` | Existing single-message SQS attribute propagation / Queueman continuation, not Kafka or arbitrary queue propagation. |
| `captureMessagingBoundaries` | Follows `instrumentMessaging` | Metadata-only observation for supported SQS, Kafka and Queueman boundaries. Set `true` with `instrumentMessaging: false` for capture-only operation. |
| `instrumentExecutors` | `true` | Supported JDK thread-pool hand-offs, not every async/reactive framework. |
| `instrumentScheduled` | `true` | Spring `@Scheduled` methods in the configured package scope, not every scheduler. |
| `trackMethods`, `trackExceptions` | `true` | Method/exception events within instrumented scope. |
| `capturePayloads` | `false` | Standalone HTTP does not capture request/response bodies. |
| `requireLabels` | `false` | Require a nonblank label and user at HTTP entry; not a global scheduler/queue capture gate or access control. |
| `maxTrackedRequests`, `maxTrackedEvents`, `idleTtlMinutes` | `200`, `500000`, `60` | Bounded JVM retention, not durable history. |
| `evictOnExport` | `true` for JSON; `false` for standalone fallback | Raw export policy. CLI/viewer/MCP reads explicitly use `evict=false`. |

HTTP propagation additionally requires valid context, no conflicting existing
headers and the tested redirect guard. Metadata observation can still work when
the guard is unavailable. A captured send is not delivery confirmation, Kafka
poll needs an active trace, and matching queue/topic names does not prove a
producer/consumer link. See [distributed tracing](distributed-tracing.md) and
[dependency coverage](dependency-visibility-and-change-impact.md).

### 4. Install the CLI/viewer/MCP

The Python package requires Python 3.10+ and has no runtime dependencies. Its
current package version is `0.1.0`, independent of the Java artifact version
`0.1.1-SNAPSHOT`. With `uv` available:

```bash
cd /absolute/path/to/cb-tracelens/tracelens-cli
uv sync
uv run tracelens --url http://127.0.0.1:7778 summary
uv run tracelens --url http://127.0.0.1:7778 serve --port 7777 --source-root /absolute/path/to/service
```

Open the viewer at `http://127.0.0.1:7777`. If using an installed executable or
the generated `.venv/bin/tracelens`, omit `uv run`. Always pass an explicit agent
URL: the CLI's legacy default remains `http://localhost:8082`.

| Endpoint | Example port | Owner |
| --- | --- | --- |
| Application HTTP | `8082` for the EMS helper | Your application; use normal authentication. |
| Agent debug API | `7778` by default; EMS helper uses `7779` | One standalone agent per JVM. |
| Browser viewer | `7777` by default | Python `serve` process on loopback. |
| MCP | No listening port | Python stdio process launched by the client. |

For independent or combined services, see the
[single-service and registry commands](distributed-tracing.md#run-one-ui-or-mcp-for-both).
The checked-in [EMS launch helper](../scripts/run-cb-ems-with-agent.sh) now uses
standalone mode at `7779` and disables the legacy starter setting. It assumes a
particular local Java 21 installation, checkout and already-built app; review and
adapt those workstation-local assumptions before using it elsewhere. It is not
an environment provisioning script.

## Verify capture

First, verify wiring and agent scope:

```bash
curl http://127.0.0.1:7778/tracelens/summary
```

The useful fields are:

```text
mode: standalone
recordingEnabled: true
agentActive: true
bridge.sinkRegistered: true
bridge.httpListenerRegistered: true
```

`lineCaptureEnabled` reports whether a package scope is configured.
`instrumentedClasses` can remain zero until application classes load; inspect it
after a request through the configured code. `bridge.capturedRequests` should
increase after a supported HTTP request is captured. These counters distinguish
successful attachment from actual end-to-end capture.

Make a normal authenticated request to the application's port. TraceLens creates
labels such as `auto:GET /some-endpoint`, so knowing the API is enough to find the
request. You can also label a reproduction explicitly:

```bash
curl \
  -H 'tracelens-id: CRI-123' \
  -H 'tracelens-user: engineer@chargebee.com' \
  http://localhost:8082/some-endpoint
```

Find and render the retained segment:

```bash
cd ~/work/cb-tracelens/tracelens-cli

uv run tracelens --url http://127.0.0.1:7778 find CRI-123
uv run tracelens --url http://127.0.0.1:7778 find 'auto:GET /some-endpoint'
uv run tracelens --url http://127.0.0.1:7778 render <segment-id> --source-root /absolute/path/to/service
```

The example application request uses port `8082`; replace it with your service's
port. CLI and MCP use the separate agent port. `X-TraceLens-Id` normally appears on
captured responses, but API-label lookup also works when a container-generated
error response clears that header.

## MCP configuration

Run the MCP process locally:

```bash
cd ~/work/cb-tracelens/tracelens-cli
uv run tracelens --url http://127.0.0.1:7778 mcp --source-root /absolute/path/to/service
```

Configure the equivalent command in your stdio MCP client. For clients that
accept an `mcpServers` JSON object, the example is:

```json
{
  "mcpServers": {
    "tracelens": {
      "command": "uv",
      "args": [
        "--directory",
        "/absolute/path/to/cb-tracelens/tracelens-cli",
        "run",
        "tracelens",
        "--url",
        "http://127.0.0.1:7778",
        "mcp",
        "--source-root",
        "/absolute/path/to/service"
      ]
    }
  }
}
```

Replace the paths and adapt the configuration format to your client. The agent
does not require an IDE-specific plugin; MCP is a separate local reader process,
and browser rendering of optional MCP Apps depends on the client's capabilities.

The MCP client can then use `flow_status`, `search_flows`, `get_flow_summary`,
`get_segment_execution`, and `get_flow` as runtime context while it examines source code.
For example: “Find the latest GET /some-endpoint request and explain what code
ran.” MCP reads use `evict=false`; retained traces still expire or are evicted
when the in-memory limits are reached. Captures do not survive a JVM restart.
Browser pins are separate local snapshots, not an agent retention guarantee;
MCP cannot read those pins.

For an on-demand method diagram, ask it to discover an exact segment UUID and
visualize `get_segment_execution(segment_id, service, instance)`; this adds no
renderer to the application. Single-service setups omit `service` (or use
`default`); combined setups use a configured alias. See
[execution evidence and size limits](hybrid-visualization.md#mcp-first-segment-execution-visualization).
Reconnect the MCP process after upgrading to discover this tool; do not restart
the instrumented application just for this MCP-side change.
`get_trace_visualization` returns graph data; `show_trace_visualization` requests
an MCP Apps presentation with text/Mermaid/viewer fallback. This describes the
server's implemented protocol, not a guarantee about any particular client's
current app-rendering support. The optional
`mcp --viewer-url http://127.0.0.1:7777` supplies a viewer link; start the matching
`serve` process separately, because that flag does not launch one.

## Optional legacy application-adapter setup

Existing installations may keep an application-owned store and debug API. This
path requires an application dependency and rebuild, and is separate from the
recommended standalone setup. Do not enable both stores in one JVM.

For a Spring Boot service using the existing Java 21 starter:

```bash
cd ~/work/cb-tracelens
./gradlew :tracelens-agent:shadowJar publishToMavenLocal
```

Add the locally published starter to the service build:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    runtimeOnly "com.chargebee:tracelens-spring-boot-starter:0.1.1-SNAPSHOT"
}
```

Enable it in the local/dev profile:

```yaml
tracelens:
  enabled: true
  config-path: /absolute/path/to/local-setup/tracelens.json
```

Attach the agent **without** `=standalone` and give it the same config file:

```bash
java \
  -javaagent:/absolute/path/tracelens-agent-0.1.1-SNAPSHOT-agent.jar \
  -Dtracelens.configPath=/absolute/path/to/local-setup/tracelens.json \
  -jar service.jar
```

For this legacy path, point CLI/MCP at the application's HTTP port, such as
`http://localhost:8082`, and protect the application-hosted `/tracelens/*` routes.
The [cb-app guide](cb-app-integration.md) covers the older javax adapter and
migration to standalone mode.

The updated Spring and javax adapters back off when standalone mode is selected.
If an older adapter is installed, remove its registration/dependency or disable
its legacy `tracelens.enabled` setting before attaching in standalone mode.
The current EMS launch helper is standalone, not an example of this legacy path.

## Recommended distribution model

### Early pilot

Start with the [focused developer workflow](developer-workflow.md): `doctor`,
API/scenario investigation and explicit before/after captures. Reconnect the MCP
process after upgrading to discover the five workflow tools. The CLI/MCP file
store defaults to `~/.tracelens/captures`; use the global `--capture-dir` launch
option to choose a private directory shared by those local reader processes.
Browser pins are a different store. Evaluate the POC using the
[productivity pilot scorecard](productivity-pilot.md), not feature count.

```text
Private repository
  + release notes
  + checked-in sample configuration
  + service integration guide
  + local/development use only
```

### Team adoption

```text
CodeArtifact
  ├─ versioned shaded -agent.jar artifact
  └─ optional legacy core, bridge, starter and javax adapter artifacts

Internal Python package index
  └─ versioned CLI package
```

A service can use startup properties for a local trial. For a repeatable team
configuration, keep a small service-owned `tracelens.json` with strict labels and
a narrow instrumentation scope in shared environments.

## Shared-environment safeguards

Before enabling TraceLens in predev or production-like environments:

- Set `requireLabels=true` to gate HTTP capture on a supplied label/user. This is
  not authorization and does not gate all scheduler/Queueman lifecycle traces;
  separately scope or disable unwanted background instrumentation.
- Keep `capturePayloads=false`.
- Keep the standalone debug API on direct loopback access; do not expose it through
  a reverse proxy. Protect or omit application-hosted debug endpoints in legacy mode.
- Use conservative retention caps and a short TTL.
- Set `applicationVersion` to a reproducible build/version or commit label;
  automatic Git checkout matching is not provided.
- Maintain a compatibility matrix for agent, JDK, and framework versions.
- Do not enable unrestricted, always-on line-level capture in production.

Standalone mode collects no request bodies, authentication headers, cookies or raw
query values. It does not provide durable storage, central access control or a
production rollout by itself. Automated artifact publishing and a maintained
compatibility matrix remain distribution work.
User/site labels, paths, exception/source views and queue/topic identifiers can
still be sensitive; existing access and AI-data policies apply to exports and MCP.

After an upgrade, rebuild/restart each target JVM for agent/config changes,
restart the viewer/reconnect MCP for Python changes, and reload browser assets.
Export or successfully pin important captures first. These operations do not
recover events that were absent from earlier captures. Instrumentation, event
allocation, export and rendering have overhead; representative performance and
production-safety verification remain deployment work.
