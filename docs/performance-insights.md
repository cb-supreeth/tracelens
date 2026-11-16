# Performance insights

Implemented in the viewer for **local/dev, on-demand investigation**. This is not
continuous traffic monitoring, a production profiler or an automatic optimizer.
It reads retained PostgreSQL/MySQL JDBC evidence without enabling recording,
changing capture scope, running SQL, replaying requests or saving new files.
Native ClickHouse query records, cache calls, HTTP calls and DB-routing events
are visible elsewhere in the viewer but are not inputs to this JDBC analysis.

## Use the tab

1. Enable [local SQL capture](jdbc-query-capture.md) deliberately and reproduce
   the API through cb-app. Parameter capture is **not** required for this analysis.
2. Open the **Performance** workspace.
3. Choose **Recent captured requests**, narrow **API path contains** and
   **Request time window**, or select one saved capture. These filters are
   independent of the Live requests tab. Scheduled-job runs are not in the recent
   request source; an explicitly saved job capture can be selected individually.
4. Set thresholds and click **Analyze**. Opening the tab or auto-refreshing request
   summaries does not analyze or fetch detailed graphs automatically.
5. Review findings, scan counts and warnings; use **Category** to narrow the list.
   **Open request** returns to its service graph. For a repeated pattern, choose a
   **Captured occurrence** before **Open query evidence** to inspect that query
   in its owning service's timeline, with calling context when available. The
   picker retains at most 200 query nodes in evidence order, not request-wide
   chronology. A saved finding stays on saved evidence; missing saved data never
   substitutes live data.

Results stay fixed until another analysis completes. **Cancel**, changing inputs
or leaving the tab invalidates pending results and prevents further reads in that
run; a read already in flight may finish. Previous results remain available and
show the thresholds, source and time used, rather than silently changing.

## What findings mean

| Finding | Default threshold | Interpretation |
| --- | --- | --- |
| Slow query | JDBC execution ≥ 100 ms | Recorded driver-call duration exceeds the chosen threshold |
| Repeated query | Same normalized SQL shape ≥ 3 times | Repetition within one service segment, driver and destination scope; not necessarily redundant work |
| Suspected N+1 | Same SELECT shape ≥ 5 times | Repeated filtered SELECT with values/binds in one recorded thread with distinct event sequence IDs; inspect for per-item data access |
| Query-heavy segment | ≥ 50 executions per segment/destination scope | High retained call count, not proof of a bottleneck |

N+1 candidates replace the repeated-query finding for the same observations.
Pagination, retries and intentional loops can resemble N+1. Missing thread/order
evidence prevents that inference. Batch calls count as **one execution**, not one
query per bound row; batches are excluded from repetition/N+1 detection.
DB routing decisions are not SQL executions and do not count.

Query patterns remove supported literals and comments without substituting
captured parameter values. Unsupported, ambiguous or truncated SQL is excluded
from repetition analysis with a warning; its valid duration can still produce a
slow-query finding. This conservative normalizer is **not** a sharing sanitizer:
table/column identifiers may be sensitive, and original SQL/bindings remain in the
underlying graph, snapshot and query details.

Known database destinations are never merged with different or unknown ones.
An unknown destination bucket remains scoped to its owning service and driver;
it does not establish that calls reached one database. The UI reports that gap.

## Scope and limits

- Each run reads at most **10 newest matching requests sequentially**, or one
  chosen saved capture, with a **5 MiB per HTTP response** limit.
- On the paged protocol, **Analyze** explicitly loads bounded boundary pages to
  obtain query templates: at most **16 pages per analysis**, normally 500 events /
  256 KiB per page. Pages and bindings are not retained by the analysis; only
  matched SQL templates and required flags are carried forward. Compact previews
  are never treated as complete SQL for normalization. A page/capture limit leaves
  an explicit partial result, not a claim that all queries were analyzed.
- Graph validation accepts at most **4,096 nodes / 16,384 edges**; excessive graphs
  are not analyzed. Existing server projection and agent retention limits still
  apply, including SQL capture-limit markers and omitted boundaries.
- At most **200 findings per graph** and **200 highest-ranked findings in the
  panel** are shown. Counts and warnings distinguish analyzed evidence from skips,
  failed reads and omitted findings. Findings overlap: do not add category totals
  as if they were distinct queries.
- Ranking uses cumulative known JDBC duration, then call count. This is an
  investigation ordering, not predicted savings or an impact score.
- Cumulative JDBC duration is not request elapsed time, database-server CPU,
  transaction duration or confirmed commit. It does not include all result
  consumption and must not be summed across findings or services as latency.
- No findings means **no threshold matches in the retained evidence**, not that
  performance is healthy or the workload is fully covered.
- Retained and saved requests are selected samples, not all traffic. There are no
  fleet-level rates, percentiles, baselines, background aggregation or alerts.

Only the viewer needs to be replaced/restarted for this UI feature. A service
restart is needed if its SQL capture configuration or agent must change; old
captures cannot gain evidence they did not record.

## High-traffic monitoring

Continuous environment-wide monitoring is a [future phase](roadmap.md#8-high-traffic-performance-monitoring).
It needs bounded metadata/fingerprint aggregation, explicit sampling, privacy
controls, build-aware baselines and measured overhead before shared deployment.
The local SQL-debugging defaults are not a high-traffic collection policy.

## Measuring agent overhead

The optional local harness runs four separate JVMs with identical heap settings:
no agent, overview, methods, and detailed/line capture. It uses synthetic CPU work
and one synthetic boundary per request, with the same trace lifecycle harness.
It does not call services, write captures, or use application credentials/data.

```bash
./gradlew :tracelens-agent:benchmarkModes -PtraceLensBenchmarkIterations=10000
```

Each `TRACELENS_BENCHMARK` JSON line reports throughput, p50/p95/p99 request time,
thread-allocated bytes when supported, GC counts/time, retained events, intentional
detail omissions, observer failures and a work checksum. Run multiple warmed forks
on an otherwise idle machine; retain the JDK/build/config and raw output. The default
1,000 measured requests is a harness smoke run, not a reliable overhead estimate.
This is not JMH, a real database/network load test, a concurrent workload, or a
fleet percentile. Before shared-environment rollout, measure representative cb-app,
EMS, GO and Ledger flows at expected concurrency, including slow dependencies,
retention saturation and viewer reads. Set an agreed overhead budget from those
results; unit-test success does not establish it.
