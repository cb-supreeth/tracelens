# A focused TraceLens developer workflow

New to TraceLens? Start with the shorter [how-to-use guide](how-to-use.md).
This page covers the detailed workflow, evidence limits and configuration options.

Implemented in the Python CLI/MCP layer. No new JVM callbacks, database,
background collector, frontend dependency or application integration is required.
These tools read agents already attached to the services; they do not launch the
services, replay API requests or make code changes.

The goal is one useful investigation inside the developer's existing workflow:
check setup → reproduce → find the request → inspect evidence → save/compare if needed.
Use the [pilot scorecard](productivity-pilot.md) to check whether this saves time in practice.

## 1. Check setup

Install the CLI using the [sharing guide](sharing-and-configuration.md#4-install-the-cliviewermcp).
For cb-app agent setup and the optional local launcher, use the
[cb-app integration guide](cb-app-integration.md#optional-makefile-launcher).
The checkout and service-orchestrator name is `chargebee-app`; the bundled
TraceLens service/filter name is `cb-app`. Keep those names distinct when
configuring source paths and selecting captured services.

The optional launcher is copied into `chargebee-app` as `Makefile.tracelens`.
Its `check` validates startup wiring; `status` reads the agent summary. `up`
uses the normal application lifecycle and reuses a healthy JVM, so it does not
attach an agent to an already-running process. `restart` reloads the built app
with tracing and jobs enabled and clears existing Tomcat logs. Save relevant
logs and captures first; the integration guide is the installation source of truth.

From the repository root, with `tracelens` available on PATH:

```bash
tracelens --services scripts/services-local.json doctor
```

For one service:

```bash
tracelens --url http://127.0.0.1:7778 doctor --source-root /absolute/path/to/service
```

`doctor` checks debug API reachability, agent/recording/sink status, transformation
counters, line-capture scope, retention and configured source-directory access.
It returns actionable suggestions for each service, including offline services.
Unknown/missing fields are not treated as zero or healthy. Empty stores after a
restart are normal. It does not enable recording, install dependencies or restart
anything. A readable checkout is not proof it matches the running build.

The local example registry contains workstation-specific source paths; adapt it
before sharing. Use debug ports (local cb-app `7778`, EMS `7779`), not the browser
page URL. In single-service mode the configured alias is `default`, even if the
application reports `applicationId: cb-app`. An updated source default or config
does not rename old captures or prove a running JVM loaded a rebuilt agent.
Each real diagnostic HTTP response is limited to 64 KiB.

## 2. Reproduce and find the request by API

Perform your normal UI action or API call, then:

```bash
tracelens --services scripts/services-local.json --service cb-app investigate \
  --method GET --path /api/v2/features --since-minutes 2
```

For a recorded reproduction label:

```bash
tracelens --services scripts/services-local.json investigate --label CRI-123 --outcome error
```

The default is `--selection unique`. Several matches return `status: ambiguous`
and a compact candidate list; nothing is silently selected or saved. Narrow the
time, literal path, service, user or label. If you explicitly mean the latest run,
add `--selection latest`. A tied/unknown newest timestamp or incomplete discovery
still needs an explicit choice. `--trace-id TRACE_ID` selects an exact request;
other supplied filters, including the time window, continue to apply.

Filters must match one observed service segment, but the returned investigation
preserves its request's other observed segments. It includes a small journey,
failure evidence and suggested next tools. A downstream HTTP failure is not
hidden by a successful upstream response. Signals are evidence to investigate,
not an automatic root-cause diagnosis. Summary outcome filtering does not scan
all raw event streams for caught exceptions or every client-boundary failure.

Use the backend API path, not the browser route: `/d/features/test1` can trigger
cb-app `/api/v2/features/dGVzdDE=` and EMS `/features/test1`. These are distinct
observed paths, not aliases TraceLens should rewrite.

The result caps candidate and graph output. Existing live federation reads still
fetch retained summary lists and selected exports before projection; display
limits are not universal upstream byte limits.

## 3. Save baseline and candidate evidence

Save **before restarting** the baseline JVM:

```bash
tracelens --services scripts/services-local.json --service cb-app capture save features-before \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest \
  --revision baseline-build
```

Make the code change, rebuild/restart as needed, reproduce with equivalent inputs
and application state, then save a separate candidate:

```bash
tracelens --services scripts/services-local.json --service cb-app capture save features-after \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest \
  --revision candidate-build

tracelens capture list
tracelens capture compare features-before features-after
```

`--revision` is an explicit build label, not a claim that the current Git HEAD is
the artifact running in the JVM. Use the known build/revision and record any
uncommitted changes yourself. The label starts with a letter/digit and accepts
letters, digits, dots, slashes, underscores and hyphens (up to 128 characters).
Snapshot comparison is offline: the services can
be restarted or stopped after both captures are saved.

Captures use generated IDs. Names can repeat; an ambiguous name requires its
exact `captureId` from `capture list`. Existing files are never overwritten or
automatically evicted. A quota error requires intentionally archiving/removing
unneeded files, or choosing another explicit capture directory.

### What is compared

- Observed methods and counts, keyed by service, class and method name.
- Observed line markers and counts; line-number movement is **not** source-aware mapping.
- Bounded observed nesting and external dependency evidence.
- Captured outcomes and local timing observations, with evidence-quality warnings.

“No longer observed” does **not** mean removed or dead code. Method overloads are
not distinguished by the current event format. Scope differences, input changes,
missing services, active requests, truncation and unreported global event drops
can all change the evidence. One timing difference is not a performance benchmark.
There is no automatic changed-method-to-all-workflows index or guarantee of a
change's complete impact.

## 4. Use the same workflow through MCP

Reconnect the MCP process after upgrading. To choose the local capture directory,
add the global launch option **before** `mcp`:

```bash
tracelens --services /absolute/path/services.json \
  --capture-dir /absolute/path/private-tracelens-captures mcp
```

The existing MCP connection exposes five additional tools:

| Tool | Purpose |
| --- | --- |
| `tracelens_doctor` | Read-only setup checks and suggestions |
| `investigate_request` | API/scenario discovery, ambiguity handling and compact evidence |
| `save_request_capture` | Explicitly persist one selected run under a human name |
| `list_request_captures` | List saved local runs, offline |
| `compare_request_captures` | Compare two capture IDs or unique exact names, offline |

Example prompts for an assistant connected to TraceLens:

> Check whether TraceLens is ready for cb-app and EMS. Explain any setup issues;
> do not restart or change anything.

> I just reproduced GET /api/v2/features in cb-app. Find it within the last two
> minutes. If there is more than one request, show me the candidates. Explain the
> observed service path and any failure evidence, then inspect only relevant code.

> Save the latest matching features request as features-before, with revision
> baseline-build. After I reproduce the changed build, save features-after and
> compare them. Separate observed changes from unknown coverage and potential
> effects inferred from source.

The assistant can carry IDs between tool calls; developers do not have to copy
them manually. Saving is an explicit local write, not an automatic side effect
of investigation. Compare/list accept saved names/IDs only, not arbitrary files.
Use existing graph/execution tools for visualization; no new dashboard is required.

## Storage, privacy and cost

Default snapshot directory: `~/.tracelens/captures`; override using the global
`--capture-dir`. This is separate from browser IndexedDB pins. MCP cannot read
browser pins, but it can list/compare these explicitly saved local snapshots.
CLI and MCP must use the same directory to share captures.

The file store currently requires a POSIX filesystem (macOS/Linux). Its directory
must be owned by the developer and private (`0700`); newly created directories
and files use private permissions. Symlinked paths are rejected. Limits are 20
captures, 5 MiB per capture and 20 MiB total; snapshots process at most 16 segments
and 100,000 events in aggregate. Comparisons are capped at 64 KiB with explicit
omission counts. These bounds are evidence budgets, not completeness guarantees.
The snapshot-building stage also limits each agent response to 8 MiB, aggregate
reads to 32 MiB and requests to 64. These limits do not apply retroactively to the
preceding request-selection step, which uses the existing live discovery/journey
reads described above.

Snapshots persist a bounded projection, not a complete raw trace: no request
payloads, logged-in identity, source text or raw exception messages. Paths,
class names, queue/topic names and your labels can still be sensitive. Review
files before sharing; local file permissions are not team RBAC or encryption.
The raw export is read in memory before projection, so this is not a guarantee
that sensitive data never passes through the local Python process.

On-demand reads incur agent export/serialization and Python processing costs.
No new callbacks or always-on collection were added to the application. This
does not measure or eliminate the overhead of the existing instrumentation.

CLI exit codes: `0` for successful selection/save/list/compare or ready setup,
`1` for execution/configuration errors or non-ready setup, `2` for an investigation
or save needing a narrower selection/no retained match. A doctor report can be
ready for capture while still reporting nonblocking warnings.
