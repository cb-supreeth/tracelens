# Historical Java migration and deployment validation

This page records dated validation checkpoints, not current setup instructions
or a claim that these results were rerun for the latest build. For a fresh
installation, use the Java ZIP and `./setup.sh` described in
[guided setup](guided-setup.md). Current delivery contains one combined JAR,
the setup script and portable service configurations. Older artifact lists and
reference-tool commands below describe their stated checkpoint only.

## Local deployment verification — 2026-10-28

- Installed the tested JAR in a new immutable local directory; changed only
  the JAR paths in cb-app's managed startup hook and the EMS/viewer launchers.
  cb-app was stopped before this operation and was started; EMS and the viewer
  were restarted. No application rebuild or tracing-config changes were needed.
- Both agents advertise projection version 1 and the overview, event-page and
  request-data capabilities; the viewer advertises large-trace protocol 1.
- An authenticated, read-only `GET /api/v2/features?limit=2` returned HTTP 200
  and linked cb-app to EMS `POST /features/search`. The trace retained **919
  events** (83 cb-app, 836 EMS), **55 SQL executions**, and 49 SQL events with
  captured bindings. Neither segment reported dropped detail callbacks.
- Every event page stayed below the requested 256 KiB bound. The new version-2
  capture preserved all events: canonical saved/live event hashes matched at
  the same high-water marks. Its 430,882 raw bytes compressed to 35,012 bytes.
  Saved request data, execution models and pinned source lines were readable.
- Browser checks covered the flow diagram, lazy query parameters, SQL with
  captured values, a SQL-event jump, next-page timeline navigation, code flow,
  and reopening the saved capture. The framework `set_config` call correctly
  reports that no reliable exact calling source line was captured.
- All **12 pre-existing captures**, 56 storage-file hashes, both configurations
  and the viewer namespace remained unchanged. One verification capture was
  added: `8fc39fda-335a-476d-acef-bc28839239c0`.
- Limits: GO and Ledger were already offline and were left untouched, producing
  explicit federation warnings. EMS's execution display reached its 200-node
  model limit while processing all 836 events; raw saved events remain complete.
  This real trace is below 12 MiB; the larger-path evidence remains the isolated
  38,138-event test below, not a replay of the original cart-creation request.

The local verifier and count/hash-only evidence are in
`.run/verify-large-trace-deployment-20261028.cjs` and
`.run/large-trace-deployment-20261028/`. The latter also holds launcher backups
and the pre-deployment manifest; old installed JARs were retained as backups.

## Large-trace update — 2026-10-27

- **1,729 Java tests passed** across core, bridge, agent, Spring starter and
  tooling. Six opt-in real-database integration cases were skipped because
  `TRACELENS_JDBC_SMOKE` was not enabled; these are not counted as passes.
- **738 browser/UI tests passed**. The packaged Java 8 CLI/MCP/viewer smoke also
  passed, and the Java distribution was rebuilt.
- An isolated synthetic **38,138-event** trace saved through the browser without
  a full export. Roughly 25 MiB of raw evidence compressed to 591 KiB. Saved
  request data, full SQL/bindings, late-event navigation and code-flow rendering
  were checked with the synthetic agent offline.
- Regression tests cover bounded UTF-8 pages, omission gaps, retained service
  identity, byte-budget exhaustion, source-text pinning, corrupt/imported chunks,
  quota enforcement, legacy reads and manifest validation before publication.
- At this test checkpoint the installed JAR, running viewer, cb-app and EMS were
  left unchanged pending approval. Deployment and fresh integration verification
  followed on October 28 (above). Historical captures cannot recover previously
  omitted data.

Commands used:

```sh
./gradlew :tracelens-core:test :tracelens-bridge:test :tracelens-agent:test \
  :tracelens-spring-boot-starter:test :tracelens-servlet-javax:test \
  :tracelens-tool:test :tracelens-agent:javaBundle \
  :tracelens-agent:java8ToolSmoke --offline --console=plain
node --test tracelens-cli/tests/*.test.cjs
```

This is functional and bounded-storage validation, not a production load or
JVM-overhead benchmark. See [large-trace storage](java-capture-storage.md) for
the new limits and [capture controls](sharing-and-configuration.md#capture-controls-and-defaults)
for startup-only overview/methods/detailed configuration.

## Historical migration baseline — 2026-10-20

Validated on **2026-10-20** against the Python implementation that this checkout
then retained. That implementation, its tests and the fixture generators were
removed on 2026-11-24 (recover them from git history before that date if a
fixture must be regenerated). The Java runtime and its regression suite do not
require Python; the parity fixtures are committed and frozen.

## Migration-baseline result — 2026-10-20

- **1,334 Java tooling tests passed**, including frozen Python-reference output
  comparisons and isolated HTTP/storage integration tests.
- **1,237 Python reference tests passed**.
- **422 browser/UI tests passed** against the shared UI source.
- Repository-wide `test`, Java 8 agent smoke, and the extended Java 8 packaged
  tooling smoke tasks passed.
- At that checkpoint the distribution contained exactly **`tracelens.jar` and
  `tracelens.json`**; this is not the current setup ZIP's file list.

These results validate the workflows and edge cases below, not every possible
application, dependency version, filesystem, or workload. This was not an
overhead benchmark or an application-level replay of every Chargebee API.

## Workflow coverage

| Workflow | Checks |
| --- | --- |
| Service setup and diagnosis | Healthy, disabled, offline, legacy/missing metadata, malformed counts/booleans, instance changes, retention limits, source configuration, and bounded 32-service reports. Diagnostics use read-only probes. |
| Request discovery and investigation | API/method/user/service/time selection; ambiguous and missing requests; latest selection; scheduler metadata; partial evidence and guided diagnosis; invalid filters rejected before service reads. |
| Cross-service request flow | Explicit span joins, duplicate/ambiguous call spans, unavailable downstream services, segment/instance collisions, legacy endpoint fallback, and separate configured source roots. No inferred remote execution. |
| DB, cache, queue and external systems | DB routing, SQS, Redis/cache wrappers, ClickHouse, DynamoDB and S3 observations; per-event identities, wrapper/client deduplication, privacy projection and graph limits. This checks captured evidence handling, not new live calls to every system. |
| Execution flow and timeline/source | Method nesting, multiple thread-name lanes, gaps/unmatched events, partial/truncated evidence, node/event/depth/byte limits, repeat collapsing, Unicode source text, and current-checkout resolution. |
| Errors | Successful roots with failing downstream segments, HTTP and scheduler failures, category/status/duration filters, grouping identity, summary-only reads and incomplete discovery. |
| Saved viewer captures | Save, list, read, rename, delete, missing IDs, restart persistence, legacy files, private-store checks, quotas and source-text preservation. Missing saved evidence never falls back to live data. |
| Minimized captures, comparison and search | Capture selection, identity checks, immutable publication, interrupted-write visibility, concurrent quotas, projection/privacy, deterministic sorting and trimming, literal Unicode search and unavailable-store warnings. |
| CLI | Read commands, explicit recording mutations, non-evicting exports, source/no-source rendering, option validation, literal labels after `--`, investigation exit status, offline commands and tail polling/deduplication/interruption. |
| MCP | All 22 tool schemas, initialization/notifications, stdio framing, action grouping, labels/UUIDs, stale identity failures, bounded output, federation, source ownership and the bundled offline visualization resource. |
| Viewer protocol and persistence identity | HTML/assets, same-origin checks, strict JSON/body limits, offline execution POST, configuration namespace stability, encoded URL paths, saved tools and read-only route behavior. |
| Packaged Java 8 runtime | Actual shaded JAR child processes: CLI, MCP, viewer HTTP/assets, source lookup/enrichment, non-evicting export, execution model POST and complete isolated snapshot lifecycle. |

### Reference fixtures

`ParityEvidenceSuiteTest` checks **992** frozen Python outputs, with another
**97** workflow outputs in `PythonWorkflowParityTest`. The pre-existing
`PythonEvidenceParityTest` retains 36 reference cases. Golden comparisons check
model fields and array ordering, not just whether an operation returned
success. Numeric values are compared by value across JSON number representations.

Fixtures contain synthetic test data only. They were produced by two generators
(`generate_evidence_parity.py`, `generate_workflow_reference.py`) that were removed
with the Python implementation.

The evidence generator invoked Python tests with isolated loopback HTTP fixtures.
It skipped non-JSON inputs, tests that temporarily patch production constants, and
very large golden documents. Dedicated Java tests exercise the actual production
limits instead: 100,000 events, 512 levels, 64 thread-name lanes, dependency and
graph limits, 10,000 error summaries, and 32/128 KiB execution model budgets.

## Regressions corrected during validation

- Invalid error filters could contact agents before validation.
- Encoded upstream paths could collide with decoded paths in service identity
  and the viewer's saved-data namespace.
- Legacy exports with no `events` field could incorrectly appear truncated.
- Capture nesting counters and bounded comparison results could differ in
  ordering or which entries were trimmed.
- Case-folded search and request selection differed for Unicode text.
- Empty/false request-field fallback and some Unicode metadata validation
  differed from the Python projection.
- Literal `--help`/`--version` labels after `--` were treated as CLI switches;
  boolean flags also accepted assigned values incorrectly.
- Timeline repeat collapsing differed for missing versus null fields, and
  source truncation could split a Unicode code point.
- MCP action ordering differed when timestamps tied.
- Diagnostic reports could confuse an HTTP 502 or malformed JSON response with
  an unreachable endpoint.
- Out-of-range source-line numbers could wrap into a different integer
  coordinate during enrichment.

## Existing-data check

A separate viewer using the rebuilt JAR read the same local configuration as the
running combined viewer. Its namespace and all **three saved captures** matched
the existing viewer's JSON exactly. Summary, request-discovery and error-group
routes were readable; discovery returned 200 retained rows with `partial=false`.
No real capture was created, renamed, deleted or evicted by this check. Neither
cb-app nor EMS was restarted.

After validation, the three Java viewer processes (ports 7777, 7780 and 7781)
were restarted using an immutable copy of the tested JAR. Each retained its
previous namespace and returned a successful summary response; the combined
viewer still listed all three saved captures.

Automated tests use temporary private directories and synthetic local agents;
they do not mutate a developer's capture stores or application services.

## Re-run

```bash
./gradlew test \
  :tracelens-agent:java8Smoke \
  :tracelens-agent:java8ToolSmoke \
  :tracelens-agent:javaBundle --console=plain

node --test tracelens-cli/tests/*.test.cjs
```

The Gradle build uses JDK 21; the smoke tasks also require a locally installed
Java 8 JDK. HTTP integration tests need permission to bind ephemeral loopback
ports. Golden Java tests run from committed fixtures without a Python process.

## Deliberate differences and remaining limits

- The JAR command is `java -jar tracelens.jar …`. An existing installed
  `tracelens` Python command is not silently replaced.
- Java rejects malformed/duplicate JSON and invalid typed inputs rather than
  reproducing Python coercion accidents, such as accepting a boolean schema
  version as the integer `1`.
- Network reads and model construction remain bounded. Partial evidence is
  reported rather than treated as complete or repaired with a different live
  request.
- Keep **Python and Java writers separate**. Their filesystem locks are not
  interoperable. Local POSIX storage and trusted private-directory requirements
  still apply; this validation is not Windows storage certification. See
  [Java capture storage](java-capture-storage.md).
- Source correspondence remains unverified. Current local source may differ
  from the running or historically captured build.
- Snapshot files preserve their recorded evidence. Runtime counters, durations,
  JVM identities and application retention naturally change between live reads;
  equality of two independent live requests is not a parity requirement.

See [the Java distribution guide](java-distribution.md) for installation and
viewer restart instructions.
