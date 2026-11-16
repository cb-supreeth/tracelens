# A focused TraceLens developer workflow

Use this workflow for a controlled reproduction: check setup → reproduce → find
the request → inspect → save → compare. For a first installation or a quick UI
tour, start with [How to use TraceLens](how-to-use.md).

The current distribution is one Java JAR for agent, viewer, CLI, setup and MCP.
The reader tools do not replay business requests or change application code.
Use the same primary service JSON for CLI, viewer and MCP so their service/source
mappings and private evidence directories agree. Examples below run from an
installation with `cb-app/tracelens.json`; substitute your primary config.

## 1. Check setup

Run `sh setup.sh` for a first installation or **Edit capture settings** for a
managed installation. Select Overview, Methods or Detailed, then review API/package
scope, detail overrides and independent payload/SQL opt-ins. Saved policy is not
proof that the running JVM has loaded it. Runtime actions are separately reviewed;
see [guided setup](guided-setup.md).

In the viewer, **Services & capture settings** checks current connections and
explains how to reopen the dashboard; it does not start a configuration server.
For diagnostics in a terminal:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json doctor
```

`doctor` checks reachability, agent/recording/sink state, instrumentation evidence,
capture scope, retention and source-directory access. It reports unavailable and
unknown fields rather than treating them as healthy. Configured, installed and
transformed adapter states are distinct; transformation does not prove a hook
ran. Empty stores after restart are normal. A readable checkout does not prove
it matches the running build.

Diagnostics are bounded read-only operations. Slow/offline services are reported
without discarding healthy peers; the tool does not enable recording, install
dependencies or restart anything. It is not a performance/privacy certification.
Use actual configured aliases, such as `cb-app`, rather than the checkout name
`chargebee-app`.

## 2. Reproduce and find the request by API

The viewer separates **Requests**, **Saved captures**, **Performance**, and
**Services & capture settings**. In Requests, Live requests, Scheduled jobs and
Errors have their own meanings and filters. Opening saved evidence does not
require a running application.

For a missing capture, open **Start an investigation**, enter the exact backend
path and optional method/service/user/site, then **Check capture readiness**.
Unlike ordinary list substring filters, investigation path/user/site matching
is exact and case-sensitive within one observed segment. Checks distinguish
confirmed exclusions from unknowns; current settings cannot explain every earlier
missing request.

To preserve one fresh reproduction, choose a 60- or 120-second window and **Watch
next request & save**. Wait for **Watching** before making the request. The watch
excludes its baseline and saves only one unambiguous new completed request.
Multiple matches, incomplete discovery or disappearance stop automatic saving.
Cancel/close/navigation stops future polling; if a save is already in flight,
check Saved captures. Browser/service clocks must agree.

The read-only check is also available without a watch:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json --service cb-app diagnose \
  --path /api/v2/features --method GET
```

After reproducing, find the request by API or a recorded reproduction label:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json --service cb-app investigate \
  --method GET --path /api/v2/features --since-minutes 2
java -jar tracelens.jar --config cb-app/tracelens.json investigate \
  --label CRI-123 --outcome error
```

The default selection is unique: multiple matches produce candidates, not a silent
choice or save. Narrow time/path/service/user/label, choose an exact `--trace-id`,
or explicitly request `--selection latest`. A tied/unknown newest timestamp or
incomplete discovery still requires review. Other filters continue to apply to an
exact trace-ID selection.

Filters must match one observed segment, but the result keeps that request's
other observed services. A downstream failure is not hidden by upstream HTTP 200.
Signals guide investigation; they are not an automatic root-cause diagnosis, and
summary outcome filters do not inspect every caught exception/client-boundary error.

Use the actual backend path: `/d/features/test1`, cb-app's encoded feature API and
EMS's `/features/test1` are distinct observed paths, not aliases to rewrite.

### Inspect the selected evidence

Open the request graph, select a fixed card and use **Selected evidence**.
**Open code flow** opens the selected service; **View caller in …** opens upstream
calling context. SQL groups, request data and source actions stay in the inspector
without resizing graph nodes. Unknown locations are not guessed.

Use **Code flow** for reading, **Call tree** for nesting and **Timeline / source**
for retained events. Timeline controls stay pinned, rows can wrap, and selecting a
row opens retained event JSON with a Copy action. Event paging and source jumps
preserve exact segment/instance identity. Missing saved evidence never falls back
to a live service. See [visualization rules](hybrid-visualization.md).

## 3. Save baseline and candidate evidence

Save the baseline before restarting its JVM. Record equivalent inputs/application
state and a known build label; a snapshot name alone is not build provenance.

```sh
java -jar tracelens.jar --config cb-app/tracelens.json --service cb-app capture save features-before \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest \
  --revision baseline-build
```

Change the application, rebuild/restart as necessary and reproduce comparable
inputs. Save the candidate separately:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json --service cb-app capture save features-after \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest \
  --revision candidate-build
java -jar tracelens.jar --config cb-app/tracelens.json capture list
java -jar tracelens.jar --config cb-app/tracelens.json capture compare features-before features-after
```

Names can repeat; use the returned capture ID when a name is ambiguous. New saves
never overwrite or automatically evict prior captures. `--revision` is a supplied
label, not proof of which artifact ran; record uncommitted changes separately.
Comparison works offline after both captures are saved.

### Compare viewer snapshots and use them with CLI/MCP

For the visual workflow, **Save snapshot** on each request. In **Saved captures**,
select the Before capture first and After second, then **Compare selected**.
Alternatively open **Compare & search saved captures → Compare snapshots**.
Review the selected pair and choose **Compare saved snapshots**.

**Behavior review** starts with paired response outcomes and changed external
calls, then method observations. For example, a 404 → 200 response and a before-only
EMS call are separate observed facts; they do not by themselves prove the new guard
intentionally skipped that call. Select a finding to inspect both sides and open
the exact saved service invocation. **Back to comparison** restores the comparison
context and inspection position.

**Changed methods** shows the outermost supported recorded parent by default.
Expand nested differences to inspect children. A parent whose counts stayed equal
can contain changed children; this is not a claim its implementation changed.
Missing, conflicting, shared or recursive parent relationships remain separate.
Grouping changes presentation, not the raw method totals.

External-call headings use exact per-caller `dependencyAliases` from the current
viewer configuration, with the recorded address underneath and caller separate.
Unknown/ambiguous mappings keep their addresses. Labels are configured display
hints, not proof of captured receiver execution or historical service ownership.
They do not change correspondence, raw identities or saved files.

HTTP correspondence uses captured caller, destination, method and literal path;
status is compared separately. Different concrete paths are not automatically
aligned as one route. The review is paired evidence, not a merged chronological
graph. **All differences** retains the detailed method/line/nesting/count report.

Check **Before interpreting changes** for capture completeness, input/scope and
build/provenance gaps. “No longer observed” is not removed/dead code; line-number
movement is not source-aware alignment, and one local timing sample is not a
benchmark. New event descriptors improve overload precision where recorded;
legacy name-only evidence stays less precise.

**Find in these two captures** filters the returned findings, including grouped
child methods and configured external-call names/addresses. **Search both saved
files** performs a separate bounded search of that pair, including retained
projection evidence omitted from the compact comparison. Methods, services and
captured API paths are searchable, not query values or request bodies. This is
not unlimited full-file analysis: read projection/response omission counts.

To use a viewer snapshot through CLI/MCP, expand **Use with CLI/MCP** and choose
**Create CLI/MCP capture**. This explicitly creates a new minimized copy, omitting
source, payloads/headers, user/context and raw exception messages. The full snapshot
is unchanged. Browser-only pins first need **Save to viewer**. The primary JSON's
`viewer.captureDir`, or an explicit global `--capture-dir`, must agree across
viewer, CLI and MCP. There is no automatic synchronization.

### Find code in current and saved evidence

Code flow defaults to **Application logic**. Recognized framework/proxy wrappers
and synthetic accessors are reversibly folded; ordinary getters, incomplete calls
and exception branches stay visible. Use **Reveal** or **All recorded calls**.
Both Code flow and Call tree search retained class/method names, exception types
and destinations, including collapsed nodes. Previous/Next or Enter/Shift+Enter
reveals ancestors; `/` focuses search. **Focus selected branch** retains observed
ancestors/descendants; **Clear search / focus** restores the prior view.

Model search covers at most 200 validated nodes within 16 displayed levels, not
every retained event. Code flow's separate **HTTP observations** search covers
destination/path/status for at most 5,000 unplaced entries, paged 20 at a time;
it does not invent a method owner. Timeline pages can retain events beyond the
execution model, but neither view restores uncaptured/evicted evidence.

**Open local source** uses a known class/observed line under the configured source
root to offer editor links or Copy path. It does not execute a server-side shell,
verify the checkout against the build or restore historical source. **Show source
in timeline** is the separate recorded-evidence action.

In Saved captures, **Search saved evidence** searches viewer files and minimized
CLI captures on demand. CLI/MCP search reads only the minimized store:

```sh
java -jar tracelens.jar --config cb-app/tracelens.json capture search FeatureService \
  --category class --limit 50
```

Categories are `class`, `method`, `exception` and `destination`; omit for all.
Queries are literal, case-insensitive and at most 128 printable characters.
Results default to 50; CLI accepts 1–100. Saved search inspects at most 40 files,
with a 200,000-event aggregate projection budget and 128 KiB result budget.
Chunked files have additional bounded-prefix analysis limits. A miss is not
proof of unused code, complete coverage or change safety.

## 4. Use the same workflow through MCP

Launch the combined JAR with the primary service JSON; reconnect after upgrading:

```sh
java -jar tracelens.jar --config /absolute/path/install/cb-app/tracelens.json mcp
```

| Tool | Purpose |
| --- | --- |
| `tracelens_doctor` | Read-only per-service setup checks |
| `investigate_request` | API/scenario discovery with ambiguity handling |
| `diagnose_capture_request` | Exact-path admission/retention checks, without watch/save |
| `save_request_capture` | Explicitly persist one selected run |
| `list_request_captures` | List minimized local captures offline |
| `compare_request_captures` | Compare two IDs or unique names offline |
| `search_saved_captures` | Bounded search of minimized saved evidence |

Example:

> Find my GET /api/v2/features request from the last two minutes. Let me choose if
> several match. Explain recorded service calls and failures, inspect relevant
> code, and distinguish observed facts from hypotheses. Do not restart anything.

The assistant can carry IDs between tools. Saving is an explicit local write,
not a side effect of investigation. Use the graph/execution tools for deeper
evidence; follow your team's data policy before sending source or traces to a client.

## Storage, privacy and cost

Full viewer snapshots and minimized CLI/MCP captures are separate stores.
Guided setup configures private paths in the primary JSON. Defaults are
`~/.tracelens/viewer-snapshots/<namespace>/` and `~/.tracelens/captures`;
`serve --snapshot-dir` and global `--capture-dir` override their respective roots.

Current paged viewer saves use version-2 compressed chunks: 20 captures,
128 MiB raw / 64 MiB compressed each, with 512 MiB raw / 256 MiB compressed store
quotas. The version-1 viewer store and minimized CLI store retain their separate
20-file, 5 MiB/file and 20 MiB/store limits. Existing evidence is not rewritten.
**Download capture archive / Import capture archive** shares a version-2 ZIP as
a new saved ID; it does not replay the request or contact original services.

Minimized captures process at most 16 segments and 100,000 events in aggregate;
comparison output is capped at 64 KiB. Version-2 offline analysis reads a bounded
prefix with explicit per-segment coverage, not every chunk regardless of size.
Full saved evidence can therefore outlive the smaller analytic projection.
See [storage and read limits](java-capture-storage.md) for exact budgets,
publication/locking and private POSIX-directory requirements.

Raw request data, SQL, bindings and source can be sensitive. Even minimized files
retain identifiers, paths and labels; local permissions are not team access control
or encryption. Review before sharing. Paged reads reduce individual transfer/render
size but do not make instrumentation, exports or analysis free.

CLI exit codes are 0 for completed successful operations/ready setup, 1 for
execution/configuration errors or non-ready setup, and 2 for ambiguous/no-match
investigation/save selection. A completed diagnosis can report no retained match,
and ready setup can still carry nonblocking warnings.
