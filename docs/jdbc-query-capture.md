# Local SQL query capture

Current capability guide, updated **2026-10-28**.

TraceLens can observe PostgreSQL JDBC calls in cb-ems and MySQL Connector/J calls
in cb-app, Ledger and Grant Orchestrator at supported driver seams. This is a
separate, **default-off** local-development feature. It
observes the application's existing connections; TraceLens does not connect to
the database, ask for database credentials, run queries, or change transactions.
SQL execution capture and parameter capture are separate opt-ins; a DB routing
marker alone is not evidence that a query executed.

## Enable it

The repo's local development configurations in `scripts/cb-app/tracelens.json`
and `scripts/cb-ems/tracelens.json` opt in to raw SQL and parameter capture. The
portable configs in `scripts/java-bundle/` keep both off until a developer
explicitly enables them. No other capture scopes are widened by these settings.

Use **Edit capture settings** in the setup/configuration dashboard to select SQL and
parameter capture per service, review the proposed policy, then save. Restart
is a separate confirmed action. The equivalent settings in each service's own
`tracelens.json` are:

```json
"captureJdbcQueries": true,
"captureJdbcParameters": true,
"maxSqlLength": 4096,
"maxSqlQueriesPerRequest": 100
```

Restart that application's JVM with the new JAR and JSON, and restart the Java
viewer with the new JAR for the SQL UI. The application itself does not need a
rebuild just to load the new agent/configuration. Reproduce the action through
cb-app; HTTP requests must still match the respective services' capture scopes.
`captureJdbcParameters` is a separate, default-off opt-in; it only takes effect
when `captureJdbcQueries` is also true. Existing captures cannot gain SQL or
parameter values retroactively. `/tracelens/summary` reports the
configured switch and limits; this is configuration status, not proof that a
particular driver/query was observed.

SQL and binding capture work independently of the `overview`, `methods` and
`detailed` levels. Overview can retain SQL without application method/line
events; method/line scope is needed for the corresponding calling-code context.

**Raw SQL is intentionally not sanitized.** Literal values, SQL comments, tenant
names and other sensitive text can appear. `capturePayloads: false` does not
disable SQL text when `captureJdbcQueries` is true. Keep this feature local and
review saved captures before sharing. With `captureJdbcParameters`, scalar values
passed to positional JDBC setters are retained too, including sensitive values
if the application binds them. Result rows, connection credentials and JDBC
exception messages are not inspected. A prepared statement appears as its SQL
template with `?` placeholders and a separate numbered, typed parameter list in
the retained evidence. The viewer reconstructs a **SQL with captured values**
preview for readability; it never executes that preview or rewrites the evidence.
This is not a claim about the exact SQL sent over the wire.

## Read the query

### Grouped database calls

Open the request's **Flow diagram** and select a **DB CALL** card to inspect its
calls in the adjacent inspector; the graph cards keep a fixed size. SQL executions
are grouped by database system and recorded destination metadata within each
service segment, with a query count and any failures or capture-limit warnings
visible on the card. The current JDBC hook records the database system, not a
connection host or database name, so a group is not proof that every call used
the same connection. List
previews name the SQL operation and primary tables (for example,
`SELECT · ems.features_v2`), without guessing the business purpose or an ORM
named-query identifier. These are bounded lexical summaries, not full SQL
parsing; unsupported statements use a generic label. Event number, binding count,
timing and outcome appear underneath; JDBC names such as `executeQuery` remain
available inside the details.
Long lists have Previous/Next controls (50 calls per page); grouping does not
discard queries or combine their timings. Separate service invocations keep
their own groups, even when they have the same destination metadata.

### Reconstructed values and original evidence

Expand an individual call to see **SQL with captured values** by default when
safe captured values can be reconstructed. Otherwise the viewer shows **SQL
template** or **Recorded SQL**. After substitution, **Original SQL template**
retains the template in a disclosure; **Captured parameters** retains available
numbered, typed bindings separately. The preview fills safely mapped, complete
values without rewriting the evidence. Parameters are associated by JDBC index
(`#1`, `#2`, etc.), not by their display order. Each retained prepared-batch
parameter set has its own preview and keeps the original template available;
it is not flattened into one final binding set.

Use **Copy** beside the SQL heading to copy the displayed query, including the
reconstructed values when shown. The original template and captured parameters
have separate copy actions; batch entries stay separate. Native ClickHouse
queries and their named parameters use the same controls. Copying never runs a
query, and existing incomplete/truncated evidence warnings still apply.

Missing, unsupported, truncated or display-normalized values remain `?` rather
than plausible replacements. Uncertain placeholder mapping (for example
ambiguous dialect syntax) keeps the template unchanged and explains why.
Question marks inside quoted text, identifiers and comments are not substituted.
Ambiguous `setObject` types are not guessed. An older snapshot does not acquire
values from current execution. The preview is a debugging aid, not exact wire
SQL or executable SQL guaranteed to reproduce the original request.

### Navigate to the caller

The same query evidence is available in **Code flow** and **Timeline / source**.
Use **View calling code** to navigate to that individual event's retained
active-method/line context when it exists; this is not a guaranteed exact/direct
JDBC callsite. With method/line capture absent or incomplete, SQL can still be
recorded but exact
source context is not invented. Source navigation uses the configured checkout
or explicitly saved source; current checkout text is not proof of the source
that produced a historical execution.

DB routing remains separate: `TEST_MASTER`/`TEST_SLAVE` selects a datasource; it
is not an SQL query count. An execution returning normally does not prove that
the surrounding transaction committed. Timing measures the driver's execution
call, not isolated database-server time, all result fetching, or commit latency.
An error event means the JDBC call threw; the application can still catch it.

## Scope and limits

- PostgreSQL `org.postgresql.jdbc.*` and MySQL Connector/J driver classes are
  explicitly instrumented, not every implementation of JDBC. The driver is
  supplied by the application, not bundled into the production agent.
- Statement execution, prepared statements and their execution batches are
  observed under an active captured service segment. Preparing a statement
  alone is not counted as executing it. Statements prepared before a request
  can be associated with their later execution; reused statements belong to the
  trace active when they execute.
- Nested driver calls are deduplicated. A batch is one execution boundary, with
  batch size where observed, not separate timings for every row. With parameter
  capture enabled, prepared batches retain bounded per-entry binding snapshots;
  mixed plain/prepared batches report unavailable bindings rather than guessing
  which template each set belongs to.
  Same-thread driver nesting is treated as one execution; custom JDBC
  interceptors that issue another database query inside that call are not
  represented as separate executions.
- `maxSqlLength` accepts 1–16,384 characters; `maxSqlQueriesPerRequest` accepts
  1–1,000. Defaults are 4,096 and 100, respectively. The query cap is per service
  segment, not a global synchronized cross-service budget. Limit/truncation
  notices indicate incomplete SQL evidence; normal trace-retention limits also
  still apply.
- Prepared-statement/template bookkeeping is weak-reference and size bounded.
  When an association cannot be retained, a query can have unavailable SQL;
  `Statement.toString()` is never used to recover text or parameter values.
- Parameter capture supports bounded scalar values and nulls. It never consumes
  streams, LOBs, result sets, or arbitrary objects' `toString()` methods. JDBC
  arrays and other unsupported values are labeled, not serialized. Limits are
  128 positional parameters, 512 characters per value, 8,192 serialized JSON
  characters per execution and 10 retained batch parameter sets. Omitted sets
  and truncated values are marked. Setters must return successfully before
  their value is recorded; rebinding, clearing and reusing statements preserve
  the values observed at each execution.
- Statement associations are capped at 1,024. Quota bookkeeping is capped at
  4,096 service segments and retains inactive entries for 30 minutes; at
  capacity, new segments cannot record SQL until inactive slots expire. This
  bounds agent memory and is another reason this mode is for local debugging,
  not continuous capture of every request in a shared environment.
- Skipped HTTP requests and untraced work do not emit SQL events. This does not
  automatically create job traces, instrument arbitrary async frameworks or
  follow database-side stored-procedure statements/triggers.
- ClickHouse's native client is a separate integration. This JDBC option does not
  capture its SQL; use the independent
  [native ClickHouse opt-in](request-data-and-clickhouse.md#native-clickhouse-queries).

## Storage and sharing

SQL execution events use the existing bounded in-memory trace store. Raw agent
exports, query details and explicit viewer snapshots can include raw SQL text and
enabled parameter values. The paged overview is compact; full SQL/bindings load
on demand from that exact live segment or saved capture. Legacy full graph exports
can also contain raw SQL. Do not assume exported files are safe to share simply
because HTTP payload capture is off. See [snapshot formats and quotas](java-capture-storage.md).
Compact CLI captures and model-facing MCP summaries omit SQL text and parameter
values; they are not a way to preserve the full query evidence.

### SQL over MCP (operator opt-in)

By default no MCP tool returns SQL text or bound values. An operator can enable
one dedicated tool, `get_segment_sql`, for the tooling process:

```json
{ "viewer": { "mcpExposeSql": true } }
```

or pass `--mcp-expose-sql` on the `mcp` command line. The setting is read only
from the local configuration file or command line, never from MCP arguments.
While it is off the tool is not listed and a call is rejected before any service
read.

When enabled, `get_segment_sql(segment_id, service, instance, contains, offset,
limit)` returns, for one exact retained segment and in recorded order: the SQL
text (clipped to 2,000 characters per query), the captured bindings, status,
client-side duration, batch size, error type and the innermost observed
instrumented method. `contains` is a literal case-insensitive filter on the SQL
text, for example a table name. A call returns at most 50 queries and 64 KiB,
with explicit omission counts. All other MCP tools still omit SQL.

Enabling this sends SQL literals and bound values to the connected MCP client
and its model provider. Treat it as a deliberate, local-reproduction-only
choice, and use synthetic data. The service still needs `captureJdbcQueries`
(and `captureJdbcParameters` for bindings); an empty result is not proof that no
query ran. Returned values are recorded application data and may contain text
aimed at the model; the response labels them as data, which reduces but does not
remove that risk.

There is additional CPU and memory cost for callbacks, bounded SQL strings and
statement tracking. These tests establish behavior, not a production performance
budget. Disable `captureJdbcQueries` and restart the service to stop collecting
new SQL; changing the flag does not remove SQL from previously saved snapshots.
Disable only `captureJdbcParameters` and restart when SQL templates are still
needed but new bound values should no longer be collected. SQL text can itself
contain literals, so that choice is not a sanitization guarantee.

The future [shared-dev/sandbox impact map](roadmap.md#7-shared-devsandbox-code-to-flow-impact-map)
does not make today's SQL capture a continuous shared collector or authorize
central retention of raw SQL/bindings. Its data-minimization, access and overhead
requirements must be established separately.

## Regression verification

The test suites cover opt-in/config limits, disabled and skipped traces,
prepared-statement reuse, exceptions, nested execution, batch handling,
truncation, strict Java/JS projections and HTML-safe SQL display. The optional
`JdbcDatabaseIntegrationTest` exercises real drivers against disposable local
PostgreSQL/MySQL databases, never Chargebee application databases. It is gated by
`TRACELENS_JDBC_SMOKE=true` and explicit loopback test URLs in
`TRACELENS_POSTGRES_URL` and `TRACELENS_MYSQL_URL`.

Validation used PostgreSQL 14 with JDBC 42.7.5 and MySQL 8.0.40 with Connector/J
8.0.31, including MySQL client- and server-prepared modes with batch rewriting
enabled. The packaged Java 8 smoke also checks bootstrap bridge loading and SQL
retention in the trace store. This is driver/integration coverage, not a replay
of all cb-app/EMS business flows or a claim about every driver version.

The local feature-list smoke (`GET /api/v2/features?limit=1` through cb-app)
also verified the full viewer path: 41 MySQL executions and 8 PostgreSQL
executions appeared under two DB-call groups. Forty MySQL calls and six
PostgreSQL calls contained captured values. The saved PostgreSQL paginated query
now displays `offset 0 rows fetch first 1 rows only` by default, while keeping
its original `?` template and `#1 Int = 0` / `#2 Int = 1` bindings in disclosures.
The saved MySQL call also showed its captured-value preview and original-template
disclosure. This is one observed local scenario, not exhaustive
application coverage.
