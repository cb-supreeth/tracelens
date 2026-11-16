# Request data and native ClickHouse SQL

These are separate **default-off, local-debugging opt-ins**. Configure them per
service in the setup/configuration dashboard, or edit that service's
`tracelens.json`. Review/save and confirmed restart are separate actions. Use a
current combined JAR for the agent and viewer. No application
rebuild is required; existing captures cannot gain data that was not recorded.

## API query parameters and request bodies

```json
{
  "captureHttpQueryParameters": true,
  "captureHttpRequestBodies": true,
  "redactHttpPayloads": true,
  "maxHttpPayloadLength": 4096
}
```

Only retained requests admitted by `httpIncludePaths` and recording/label rules
are eligible. The legacy `capturePayloads` option does not enable these switches.
They are independent of `captureMode`: overview, methods and detailed use the
same explicit HTTP evidence opt-ins and bounds.
Query capture applies to incoming servlet requests. Body capture supports Tomcat
request reads used by the standalone agent (including cb-app and embedded-Tomcat
EMS); it does not claim Vert.x, arbitrary servlet-container or outgoing HTTP body
coverage.

TraceLens observes body bytes/characters **as the application reads them**. It
does not eagerly consume the stream, replace it or force parameter parsing.
Supported body formats are JSON and UTF-8 URL-encoded forms. Multipart uploads, binary
content, unsupported encodings, malformed data and over-limit input are omitted.
An unread or partially consumed body is labeled accordingly, not presented as a
complete request. The limit is configurable from 1 to 16,384; the default is 4,096.

By default, known credential fields are redacted, including nested JSON and form/query keys.
Authorization/cookie headers and response bodies are not collected. Redaction is
best-effort, **not a guarantee that all personal or business data is removed**;
unusual field names and ordinary values can still be sensitive. Review captures
before sharing and keep these options off in shared/high-traffic environments.

For **local debugging with unredacted fields**, set `"redactHttpPayloads": false`
alongside the capture switches. This preserves credential-named and other fields
in query parameters and JSON/form bodies. Parsing, format, depth and size limits
still apply; this does not add Authorization/cookie header or response capture.
Use an updated JAR and restart the service; no application rebuild is needed.
The request-data details explain the capture policy without adding a badge to
the summary. Full exports and saved snapshots can contain credentials, so keep
them local and review them before sharing.
The shared templates keep redaction enabled. Old redacted snapshots cannot be
unredacted retroactively, and changing the setting does not rewrite saved data.

Select a service card in the request-flow diagram, then expand **Request data**
in the adjacent inspector to inspect that invocation's captured query parameters,
body and omission status. The graph cards stay fixed-size while details change;
closing the inspector returns focus to the selected card. The same disclosure is
available in the service's detail/code view. **Copy** beside each query-parameters or request-body
heading copies exactly the displayed data to your clipboard, without replaying
the request. Omitted data has no copy action. It loads only the selected invocation; saved captures
use their saved evidence and never substitute live request data. Full exports and saved viewer snapshots
preserve this evidence. Compact/model-facing summaries do not preserve request
payloads. Turning the switches off stops future collection after restart; it
does not erase existing saved evidence.

To find a request by its captured inputs, use **Live requests → Request data
keyword** and press **Search** or Enter. Names and values in incoming query
parameters and supported request bodies are searched case-insensitively across
the request's captured services. The other list filters narrow the candidates.
The viewer returns matching segment identities, not payloads, to the list;
compact discovery/MCP summaries remain payload-free. Search is explicit and
bounded, and reports missing/expired or unsearched data. Auto-refresh does not
repeat it, and saved views do not store the keyword. See the
[request guide](request-focused-ui.md#find-a-retained-request) for limits.

**View caller in cb-app** (or another recorded caller) is different from
**Open code flow**: the former opens the upstream code that initiated the HTTP
call; the latter opens the receiving service's execution. Caller navigation
requires a recorded start-time anchor and usable method/line evidence. If those
are absent, the viewer stays on the diagram and explains the missing evidence
instead of opening an unrelated timeline. Updating the agent and reproducing the
request can capture that location; existing snapshots cannot gain it retroactively.

## Native ClickHouse queries

```json
{
  "captureClickHouseQueries": true,
  "captureClickHouseParameters": true,
  "maxSqlLength": 4096,
  "maxSqlQueriesPerRequest": 100
}
```

This is independent of `captureJdbcQueries` / `captureJdbcParameters`. It targets
the native `com.clickhouse.client.api.Client` query API used by EMS (SDK 0.9.3).
The canonical query overload is observed once even when `execute`, `queryRecords`
or schema helpers delegate to it. Unsupported APIs and streaming inserts are not
claimed as query capture. Metadata-only boundaries remain available separately.

Captured SQL appears under the service's grouped **DB CALL → ClickHouse** card.
Select it to open the inspector's call list, then expand a query for its preview
and recorded SQL. Actual named
scalar bindings can be retained with the second opt-in; `QuerySettings` are not
query parameters. EMS currently builds many values directly into SQL, so those
values appear in the SQL itself rather than as a separate parameter list. Native
named bindings are shown separately, not falsely substituted as JDBC `?` values.

Metadata-only ClickHouse client calls (including older snapshots) also share one
**DB CALL → ClickHouse** card per service invocation and recorded destination.
The inspector lists each operation, recorded status and **View calling code**
action. These are counted as **client observations**, not SQL executions;
mixed captures keep those counts separate. No SQL is reconstructed for older
metadata-only calls. Different recorded destinations stay separate, and an
unspecified destination does not prove that calls reached the same server. This
grouping is display-only: raw events and exports are unchanged.

**SQL text is raw and can contain sensitive literals.** No result rows,
connection credentials, client settings, inserted data streams or arbitrary object representations are
captured. SQL length and query counts are bounded; limit notices describe missing
evidence, not absent execution.
The native-query quota uses the configured numeric limit independently of JDBC's
quota; it is not a combined cross-driver SQL budget.
Named bindings are bounded to 128 parameters, 512 characters per scalar and
8,192 serialized JSON characters. Bookkeeping retains at most 1,024 pending
queries and 4,096 segment quotas, with 30-minute inactive quota expiry. Limits
can omit evidence; they do not stop the application's query from running.

Asynchronous queries are observed on completion without waiting or replacing the
application's future. Client elapsed time is not pure database CPU time, and
successful completion does not prove transaction commit or that all result rows
were consumed. If completion cannot be observed, the query stays **SUBMITTED**
with no duration; the viewer does not infer success. An async completion thread is
not evidence of the original calling source location. Existing JDBC-focused
Performance insights do not automatically
classify native ClickHouse queries as N+1/slow-query findings.

See [JDBC capture](jdbc-query-capture.md) for PostgreSQL/MySQL and
[standalone coverage](standalone-agent.md) for metadata-only external calls.

## Local verification — 2026-10-26

- With `redactHttpPayloads: false`, read-only usage-events and feature-list
  requests through cb-app both returned HTTP 200. A synthetic `debug_token`
  was retained unchanged in the cb-app and EMS usage-events query evidence.
  Both agents reported redaction disabled. The fresh HTTP start-time anchor
  resolved the cb-app caller to `MsConnectionHandler.hit():107`; older snapshots
  remain unchanged. Raw-mode parsing/size limits, snapshot privacy projections,
  and Java 8 startup were also regression-tested with synthetic values.
- A read-only `GET /api/v2/features?limit=2` through cb-app returned HTTP 200
  and linked to EMS's `POST /features/search`. The cb-app query parameter and
  EMS JSON body (`limit`, `filter`, `sort`) were retained and displayed under
  **Request data**. Payloads were absent from the distributed-summary projection.
- EMS's actual ClickHouse SDK 0.9.3 was exercised against the local database with
  four constant-only `SELECT` queries. Native SQL and named bindings were captured
  once per query, returned values were unchanged, and disabling capture restored
  metadata-only evidence. No business data was changed by these probes.
- The affected Java suites passed 1,655 tests; six environment-gated JDBC database
  tests were skipped. All 557 browser-model tests and both Java 8 agent/tool smoke
  checks passed. Real Tomcat 9 and 10 tests cover passive body reads and omission
  cases; this does not establish support for every servlet container or SDK.

The installed cb-app and EMS capture switches were enabled for this local check;
shared configuration templates remain default-off. Existing saved captures were
preserved. Ledger/GO feature gates, routing and business data were not changed;
these probes do not establish a successful cb-app → Ledger/GO business flow.
