# How to use TraceLens

TraceLens answers: **When I called this API, which code ran and which services
did it call?** Reproduce one request, inspect the recorded evidence, and save it
before changing or restarting the application. This is a local-development tool,
not proof of complete coverage or that a change is safe.

## 1. One-time setup

Have your selected applications and their normal dependencies working locally.
Unpack the current Java bundle and run:

```sh
sh setup.sh
```

Choose **Set up TraceLens**, select services/locations and capture settings, then
review the installation. The supported setup catalog includes cb-app, EMS, Plans,
Ledger and GO; UI middleware is not offered. You need only the selected services.
Follow the UI; consult [guided setup](guided-setup.md) when you need field or
launcher details.

The capture levels are **Overview** (supported services/dependencies, timing and
errors), **Methods** (adds method entry/exit), and **Detailed** (adds executed
lines). HTTP query/body and SQL/binding capture are separate opt-ins. Only admitted
API paths are captured; method/line detail also needs matching package scope.
Read the effective policy preview: a scoped override or expiry can change the
level used for a particular request.

To change settings later, rerun the script, choose **Edit capture settings**, load
the installation and select one service. **Add service** adds another supported
service. Valid peers can be configured even when another service fails. Saving
does not restart an app or prove its JVM loaded the new settings. A supported
runtime action requires separate review/confirmation; unmanaged IDE/custom
processes are not silently adopted or killed. The dashboard expires after
15 minutes; run the script again to reopen it.

## 2. Check setup and open the viewer

After starting selected applications with their agents, use the installed scripts:

```sh
sh /absolute/path/to/install/check-setup.sh
sh /absolute/path/to/install/start-viewer.sh
```

Open the returned URL, normally [the local viewer](http://127.0.0.1:7781).
The viewer, application and agent debug ports are different. Starting the viewer
does not start an application or attach an agent. Setup checks are read-only;
verify capture with a real reproduction next.

| Workspace | Use it for |
| --- | --- |
| **Requests** | Live requests, Scheduled jobs, Errors and guided investigation |
| **Saved captures** | Reopen evidence, select a Before/After pair, compare and search |
| **Performance** | Analyze captured JDBC queries on demand |
| **Services & capture settings** | Check connections and find instructions to reopen setup |

The compact connection menu distinguishes connected, recording, paused and
unreachable services. Connection health is not request success or capture
completeness. **What is TraceLens** and the **ⓘ** controls explain navigation
without changing recording or your evidence.

## 3. Find the request you just made

1. Reproduce through your application's normal authenticated UI or API.
2. In **Requests → Live requests**, narrow by **API path**, time, **Observed
   service** and outcome. Use the backend API path, not the browser page route.
3. Use **More filters** for method, user/site, channel, duration or trace/label.
   **Group repeated APIs** reduces repeated rows; expand a group to choose one run.
4. Open the request's **Flow diagram**.

Live requests means retained evidence, not only currently executing requests.
Its count follows filters/time. **N retained · Capture info** includes older or
filtered-out requests. If nothing matches, increase the time window or choose
**Show all retained**. This clears filters within the current tab; it cannot
recover evicted evidence or enable recording. Scheduled jobs has independent
filters. Errors groups failure signatures and counts unique matching requests,
not the sum of failing service segments.

**Request data keyword → Search** searches captured query names/values and
request-body text, including downstream matches, but not SQL or response bodies.
Read coverage/expiry warnings and narrow filters when limited. Changing filters
resets the keyword result; auto-refresh does not rescan payloads. Saved views
remember filters, not trace data.

While a repeated group or row's **Details** is open, new list results are buffered.
**Show latest requests** applies them; this does not preserve agent retention.

### Capture one fresh reproduction

Open **Start an investigation**, enter the exact backend path and optional
method/service/user/site, then **Check capture readiness**. It checks capture
setup, not application bugs, and does not change configuration.

Choose a 60- or 120-second window and **Watch next request & save**. Reproduce
only after **Watching** appears. It saves one unambiguous new completed request;
multiple matches or incomplete discovery require review. Canceling or leaving
stops future watching. If a save was already in flight, check Saved captures.
Browser/service clocks must agree, and a saved result may be partial.

### Inspect the request flow

The diagram starts fitted with linked HTTP hops hidden. **Show HTTP hops** reveals
caller-side cards; **Hide HTTP hops** restores the compact service view. Each
captured invocation keeps its own service frame/API, so repeated Ledger or cb-app
segments are not automatically duplicates.

Select a fixed-size card to open the adjacent **Selected evidence** inspector.
Details do not grow cards or rearrange the graph. Use **Focus selected**, **Focus
branch**, the minimap and zoom controls. Arrow keys change selection; Escape
closes the inspector. In the inspector:

- **Service:** open **Request data** for captured input and Copy actions, or
  **Open code flow** for that service's execution.
- **DB CALL:** choose a SQL execution to inspect its template, duration, outcome
  and optional bindings. **SQL with captured values** is a debugging reconstruction;
  unknown values remain placeholders. Native ClickHouse uses the grouped DB view.
  **DB routing decisions** are separate pool/route observations, not SQL queries.
- **Cache/queue/external call:** inspect operation/status. **View calling code**
  or **View caller in …** opens recorded caller context, not the destination's
  execution. Missing or ambiguous locations remain unavailable, never guessed.

External HTTP services remain visible outside the caller's frame without a
receiver agent. A configured alias or unambiguous same-capture naming hint can
replace the peer label; inspect its naming evidence/address. A friendly name
does not establish captured receiver execution.

Arrow numbers express supported causal/local order. Decimal branches require
overlapping-call evidence; **?** means unknown order. Durations are local, not
additive cross-service time. Queue **SUBMITTED** does not prove delivery; a cache
boundary alone does not prove hit/miss.

### Read the service's code

**Code flow** is the default reader. **Application logic** reversibly folds
recognized framework/proxy wrappers and synthetic accessors; ordinary getters,
incomplete calls and exception branches remain visible. **Reveal** or **All recorded
calls** restores folded evidence. Search includes collapsed/folded model nodes;
**Focus selected branch** isolates observed nesting.

**Call tree** shows the same bounded model as a diagram. **Timeline / source**
shows retained events one page at a time, with pinned navigation/filter controls.
Use **Wrap event text** for long rows; select a row to inspect or **Copy recorded
event**, then Escape to return. Source jumps load the page containing the recorded
anchor. **Open local source** opens today's configured checkout, which may differ
from the captured build; saved timeline lines remain those preserved with the capture.

Counts distinguish folded/collapsed nodes from omitted evidence. Expansion cannot
restore data excluded by capture/model limits. Model search is separate from
**HTTP observations**, which has a bounded destination/path/status search for
calls without a method owner. Neither searches the entire source repository.

## 4. Keep and compare evidence

Choose **Save snapshot** in the request or row's Details before live evidence
expires. Reopen it in **Saved captures**. **Copy snapshot link** gives a stable
local link; a live `#trace=…` link still depends on agent retention. Missing saved
details never cause a newer live execution to be substituted.

Current paged saves use version-2 compressed chunks; version-1 JSON snapshots
remain readable. **Download capture archive** and **Import capture archive**
share a version-2 ZIP as a new saved ID without contacting original services.
Localhost links alone do not share evidence with teammates. Review request values,
SQL and source before copying/exporting. See [storage limits](java-capture-storage.md).

Select two viewer captures, Before first, then **Compare selected**. Review the
pair and choose **Compare saved snapshots**:

1. Start with paired response outcomes and changed external calls.
2. **Changed methods** shows the outermost supported recorded parent first, with
   child differences expandable. Unchanged parent counts can still contain changed
   children; this is not a source-code diff.
3. Open exact saved service evidence and use **Back to comparison** to return.
4. **Find in these two captures** filters returned findings; **Search both saved
   files** performs bounded pair-scoped search beyond the compact report.

Comparison external-call names use exact per-caller configured aliases, with the
recorded address visible. Names do not alter matching or prove remote execution.
**All differences** retains method/line/count reports. Check **Before interpreting
changes**: scope, inputs/builds and omissions can explain differences. “Not observed
after” does not prove deliberate skipping; one duration difference is not a benchmark.

**Search saved evidence** searches viewer and minimized CLI files, not live services,
request payloads or the source tree. **Use with CLI/MCP** explicitly creates a
separate minimized copy for assistant/terminal work. See the
[developer workflow](developer-workflow.md) for commands and search limits.

## 5. Investigate query performance

Open **Performance**, choose recent requests or one saved capture, set filters and
thresholds, then **Analyze**. Recent-request runs read at most ten newest matching
requests. Analysis does not enable SQL capture or run continuously.

Use **Category** to focus slow, repeated, suspected N+1 or query-heavy findings.
Choose a **Captured occurrence**, then **Open query evidence** to inspect that
exact query and its caller context. The picker is limited to the first 200 retained
query nodes; category changes filter existing results without rereading evidence.

This analyzes PostgreSQL/MySQL JDBC evidence, not native ClickHouse or fleet-wide
traffic. Suspected N+1 is a lead, not a confirmed bug; no findings is not a health
verdict. See [Performance insights](performance-insights.md).

## If something looks wrong

- **No matching request:** increase time, check API admission/package scope and
  run **Check capture readiness**. Viewer filters do not change capture settings.
- **Missing upstream/receiver:** check scope, propagation, connection and retention.
  Restart after config/agent changes and reproduce; old saves stay partial.
- **No source/methods:** check level, packages, debug metadata and matching checkout.
  Overview intentionally has no method/line history.
- **Traces disappeared:** restart, TTL and eviction can remove bounded JVM data.
  Save important evidence promptly.
- **Need setup changes:** reopen `sh setup.sh`; saved and active settings differ
  until the intended JVM is restarted and a fresh request verified.

For assistant use, configure [Java MCP](java-distribution.md#mcp)
with the same primary service JSON as the viewer. Ask it to find your API/time
window, let you choose ambiguous matches, and separate observed facts from guesses.
