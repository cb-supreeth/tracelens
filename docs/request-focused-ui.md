# Finding and keeping a request in the local viewer

For the first-run walkthrough, use [How to use TraceLens](how-to-use.md).
This page is the reference for request filters, counts, saved evidence and
navigation. Viewer controls read captured evidence; they do not replay APIs.

## Workspace navigation

- **Requests** contains Live requests, Scheduled jobs, Errors and Start an investigation.
- **Saved captures** contains saved viewer evidence, Before/After selection,
  comparison and saved-evidence search.
- **Performance** analyzes retained JDBC query evidence only when you choose Analyze.
- **Services & capture settings** shows connection checks and instructions to run
  `sh setup.sh` again. Choose **Edit capture settings**, load the installation,
  and review the service's changes. Saving and restart consent remain separate.

The compact connection menu shows recording on, paused, connected (recording
state unknown), or unreachable. A responding agent is not proof every endpoint
is captured, and connection health is separate from request outcome/completeness.
Saved captures work without agents; missing saved data never triggers live fallback.

**What is TraceLens** and the **ⓘ** controls provide navigation help. Hover/focus
for a tip, click/tap to keep it open, and Escape/click outside to dismiss.
Help does not select another view or start recording. The brand returns to Live
requests with filters intact. A saved deep link's back action returns to Saved
captures rather than fetching a new live request.

## Header and counts

Live requests means retained non-scheduler work, not only currently active requests.
Scheduled roots remain in **Scheduled jobs**, including when Live's channel is
**All request channels**. Service-level segment counts can include both.

| Count | Meaning |
| --- | --- |
| Live requests / Scheduled jobs badge | Individual requests/runs matching that tab's filters and time window, not API groups or segments |
| Errors badge | Unique matching requests with summary failure evidence, not the sum of failing segments/groups |
| **N retained · Capture info** | That tab's all-retained-time total, including filtered-out/older work |

For example, 35 retained requests older than the last-30-minutes window produce
**Live requests 0**, **0 matching requests**, and **35 retained**. Increase the time
window or choose **Show all retained** to clear that tab's filters. This does not
recover evicted data, cross into another tab or change capture scope.

Auto-refresh polls retained list data every three seconds, not recording controls.
While a request's Details or a repeated-request/job group is open, results are
buffered instead of replacing inspected rows. **Show latest requests** (or job runs)
applies the latest result and closes those inspections. Filter/tab changes also
apply buffered data. This stabilizes the list, not retention; evidence can expire
before source inspection or saving.

The combined viewer cannot clear agents' retained traces or change recording;
**Clear traces** is disabled. In supported single-service mode clearing is a
separate destructive action with confirmation. **Reset filters** is not deletion.

## Find a retained request

Start with API path, time window, Observed service and outcome. More filters
contains method, user/site, channel, duration and trace/label search. Active chips
remove individual filters; Reset filters restores the current tab defaults.

For **Live requests**:

- Service, method, API path, user and site must match the same observed segment.
  Path matches a case-insensitive substring of the literal path or captured route
  template; user is a substring; site is an exact captured ID.
- Trace ID/label is request-wide, including segment IDs and recorded labels.
- Time uses request start. Duration uses a credible root's local elapsed time,
  not summed cross-service durations. Missing/ambiguous root duration stays unknown.
- A downstream failure takes precedence over root HTTP success. Successful HTTP
  needs a credible root with 2xx/3xx. A COMPLETED lifecycle or caught exception
  alone does not establish business success/failure.

Use actual backend paths, not browser routes. Captured paths are not decoded or
rewritten to guess API identity. Long components are shortened for display only;
Details and filtering retain the full path.

### Search captured request input

**Request data keyword → Search** (or Enter) searches captured query names/values
and request-body text case-insensitively. A matching downstream segment keeps
its whole request visible. SQL, source and response bodies are not searched.

This is an explicit bounded read, not replay/background payload collection.
At most 200 candidate service segments are submitted, with a 12-second/32-MiB
read budget and per-export limits. Coverage reports absent request data,
expired/unavailable segments and unsearched work. Narrow filters when limited.
No match does not prove a value was unused.

Changing filters resets the applied keyword result. List refreshes do not rescan
bodies; choose Search again for new requests. Clearing the keyword restores normal
filtering. Keywords/results are not persisted in saved views or browser storage,
and this filter does not affect other workspaces.

### Check or watch a new reproduction

**Start an investigation** accepts an exact backend path and optional method,
service, user and site. These matches are exact/case-sensitive within one segment,
unlike Live's substring filters. **Check capture readiness** reports Action required,
Cannot verify, or Ready to reproduce; unknown/incomplete admission evidence cannot
produce Ready. It does not diagnose bugs, edit scope, enable recording or restart.

**Check capture for this API**, **Capture my next reproduction**, and setup's
**Open viewer investigation** prepare a draft only. Review the exact path/service
before running a check or watch; a list substring is not a safe exact-match default.

**Watch next request & save** arms a 60- or 120-second window. Reproduce only after
Watching appears. It excludes baseline requests and saves one credible new
completed request. Multiple candidates, incomplete discovery or disappearing
evidence stop automatic saving instead of selecting a replacement. Browser/service
clocks must agree. Cancel, close or navigate away to stop future polling; a save
already in progress may have committed, so check Saved captures.

Readiness is not guaranteed coverage. A timeout does not prove non-execution.
CLI `diagnose` and MCP `diagnose_capture_request` expose read-only checks, not
the browser watch. See [developer workflow](developer-workflow.md).

## Grouping and background work

Valid distributed trace IDs join observed service segments into one request.
Missing/invalid IDs remain separate; unavailable agents, filtering or eviction
can leave a journey incomplete. A known missing cb-app entry is labelled as a gap,
not replaced by a fabricated cb-app block. Independently scheduled jobs need not
originate in cb-app.

**Group repeated APIs** is a display choice: same known root service, method,
channel and captured route template (or exact path) can share a group. IDs are not
stripped to guess routes, and unknown roots stay separate. Expanded members have
Request N of M and End of group labels; their evidence is never merged.

Scheduled jobs groups runs by captured job label/service and has independent
time/service/job-name/outcome filters. Open a run to retain all linked downstream
segments, or save it. The tab does not enable instrumentation. Spring scheduled
capture and Chargebee custom-job capture are distinct opt-ins; see
[custom-job setup](cb-app-integration.md#capture-a-chargebee-custom-scheduled-job).

Errors groups summary failure signatures, including scheduled failures. Its
service/API/status filters target the failing segment, with different identity
matching rules from Live requests. See [Errors](error-groups.md).

## Inspect the captured work

The request heading identifies the exact API and credible root service. **Evidence
to inspect** highlights bounded known failures, missing entry evidence or capture
limits; a quiet summary is not a health verdict. Full IDs/warnings remain in
Request/Segment/Snapshot details, not repeated across the page.

The Flow diagram starts fitted with linked HTTP hops hidden. Select a fixed card
to open **Selected evidence** beside it. Request input, grouped SQL, route events
and caller/source actions live in the inspector without resizing graph nodes.
Use Focus selected, Focus branch, the minimap and zoom; Escape closes details.
Service frames represent separate captured invocations, not one box per deployment.

**Open code flow** opens the selected service; **View caller in …** opens the
recorded upstream context. An external card can use a configured alias or an
unambiguous same-capture naming hint, but its original peer/naming evidence remains
available and a friendly name never invents remote execution.

Code flow's **Application logic** view reversibly folds recognized wrappers/proxies
and synthetic accessors. All recorded calls/Reveal restores them; ordinary getters,
incomplete calls and exception branches remain visible. Model search reveals
folded/collapsed matches. Separate HTTP-observation search does not attach calls
to guessed methods.

Timeline/source uses bounded pages, pinned navigation/filter controls, optional
text wrapping and full retained-event inspection/copy. Source jumps use recorded
anchors. Saved source stays saved; Open local source resolves the current checkout
and does not verify historical revision equivalence. For controls and model
limits, see [visualization](hybrid-visualization.md).

## Saved views and pinned snapshots

A saved view stores browser-local filters and the Live/Jobs/Errors mode, not data.
Relative time windows apply to the current time when reopened. Up to 20 views are
supported; saving the same name updates its filters.

**Save snapshot** from a row's Details or an opened request preserves available
evidence on the viewer's machine. **Viewer file** opens independently of agent
retention and browser profile; **Copy snapshot link** uses a stable local
`#snapshot=UUID`. Localhost on a teammate's machine is their machine, not yours.

Saving is not an atomic cross-service transaction. Active/truncated/unavailable
segments produce partial warnings. Only retrieved data is saved, with a new UUID;
earlier captures are never silently overwritten/evicted. Rename keeps identity;
Delete explicitly removes the selected capture. Missing/corrupt data is an error,
not a live-service fallback.

Current paged captures use version-2 manifests/compressed chunks. Their separate
quotas are 20 captures, 128 MiB raw / 64 MiB compressed each and 512 MiB raw /
256 MiB compressed per store. Older version-1 JSON captures remain readable under
their 20-file, 5 MiB/file, 20 MiB/store limits. Read-only upgrades do not rewrite them.
See [storage](java-capture-storage.md) for private POSIX paths, read budgets and
interrupted-write handling.

**Download capture archive** exports a version-2 ZIP; **Import capture archive**
validates it and creates a new saved ID without original-service reads. Review
request data, SQL, bindings and source before sharing. **Download graph JSON** is
only the bounded graph, not a complete event/source export.

Stores are namespaced by configured service/source mappings, not viewer port.
Changing an upstream address, service alias or source root can select another
namespace without deleting the earlier files. Guided setup preserves its installed
registry; use the same primary JSON when reopening the viewer. Default snapshots
live under `~/.tracelens/viewer-snapshots/<namespace>/`; `serve --snapshot-dir`
overrides the root.

Old **Browser only** pins remain in their original browser. **Save to viewer**
explicitly copies their saved evidence without rereading agents; the original stays
intact. Clearing browser storage can remove those pins/views, not viewer files.

### Compare, project and search saved evidence

Select two viewer captures in Saved captures, Before first, then **Compare selected**.
Review names/API/time/evidence notes and choose **Compare saved snapshots**.
The Behavior review leads with paired outcomes and changed external calls, followed
by method differences. Inspect exact saved evidence, then **Back to comparison**
restores its context. Independent navigation starts a separate investigation.

**Changed methods** groups child differences under the outermost supported recorded
parent. Unchanged parent counts can contain nested differences; missing/conflicting/
recursive nesting remains separate. Search finds children; All differences keeps
flat method/line/count detail. Exact per-caller configured aliases label comparison
calls while retaining their address and caller; aliases do not alter matching.

**Find in these two captures** filters the returned report. **Search both saved
files** independently searches the selected pair's bounded projection, including
observations excluded from the compact report. Both remain bounded; neither
searches query values/request bodies or proves coverage. **Before interpreting
changes** lists input/build/scope gaps: absent observations are not deleted code
or proof of deliberately skipped calls, and timing samples are not benchmarks.

**Search saved evidence** searches viewer and minimized CLI files, not live services
or source trees. Optional categories are class, method, exception and destination.
UI results are capped at 50; at most 40 files/200,000 aggregate projected events and
128 KiB of results are processed, with additional chunked-prefix limits. Missing/
limited results stay explicit. See [search details](developer-workflow.md#find-code-in-current-and-saved-evidence).

**Use with CLI/MCP → Create CLI/MCP capture** explicitly creates a separate minimized
copy with no raw source, payloads/headers, user/context or exception messages.
The original snapshot stays unchanged. CLI/MCP list/compare/search use that separate
store, not full viewer files or browser pins. Keep `viewer.captureDir` or global
`--capture-dir` consistent across tools. This is a local copy, not a team upload.

## Retention is separate from inspection

Agent data remains bounded JVM memory. HTTP retention can have protected channel
shares, but same-channel churn, TTL and restart still remove it; reading a trace
does not refresh its activity TTL. Filters/grouping reduce UI noise, not recording
cost. Save important evidence before restarts, and review capture completeness
rather than assuming that an empty or successful-looking view proves coverage.
