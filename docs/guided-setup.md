# Guided local setup

## Start here

Download and extract the Java setup ZIP, then run `./setup.sh` from that folder.
The UI guides you through the remaining steps; return to this page only when you
need prerequisites, an explanation of a setting, or troubleshooting.

- First time: choose **Set up TraceLens**, select your already-working local
  services and capture level, review the plan, then save.
- Later: run the same script, choose **Edit capture settings**, load the same
  installation, and edit one service or **Add service**.
- Saving writes configuration only. Review any start/restart separately; the UI
  warns about downtime and loss of in-memory traces before you confirm.
- Open the viewer, reproduce an allowed API, and inspect the evidence. A saved
  configuration or connected agent alone does not prove that a flow was captured.

One combined `tracelens.jar` supplies the agent, setup UI, viewer, CLI and MCP.
Supported setup services are cb-app, EMS, Plans, Consumption Ledger and Grant
Orchestrator; **cb-ui-middleware is not included**. Each selected service has its
own configuration and debug port. A failed service does not block valid siblings;
shared installation-file errors still require attention. The local dashboard
session expires 15 minutes after launch; rerun the script to continue.

<details>
<summary>Maintainer design notes — 2026-10-28</summary>

The local dashboard edits existing per-service capture
settings and offers a separately confirmed start/restart. Capture depth is
Overview, Methods, or Detailed; payload evidence remains independently
opt-in. Reviewed updates preserve unrelated JSON and create private backups.
This avoids replacing customized configurations with fresh templates.

Setup and configuration share one launch script and backend, but use separate
screens: a short installation flow and a compact service dashboard. Two scripts
would create another entry point to maintain without separating the underlying
configuration rules. Per-service editors and collapsed advanced options reduce
routine choices; adding a service and restarting a runtime remain explicit actions.
Revisit separate tools if their permissions or deployment model diverge.

Restart management uses a private local supervisor that retains the child
process handle. It does not discover and kill an application by port or PID:
that alternative could stop the wrong process and cannot reconstruct an IDE's
environment. An existing unmanaged application must first be stopped through
its normal launcher. Supported explicit Tomcat/JAR profiles can then be started
and subsequently restarted by the dashboard. Environment is inherited from the
setup terminal, never collected or persisted by the dashboard. A failed service
does not prevent other selected services from being configured or started.

The dashboard remains loopback-only with same-origin/token authorization and an
absolute 15-minute session. Local same-user filesystem access remains trusted;
this is not a remote or production process manager. Review this design before
adding remote control, IDE adoption, containers, or secret/environment storage.

</details>

Use guided setup to configure **cb-app, EMS, Plans, Consumption Ledger or Grant
Orchestrator independently** against existing, normally working native checkouts
on macOS or Linux. Start with any supported service; rerun setup later to load
and edit its saved capture settings or add others to the same installation.
The same `./setup.sh` opens a landing choice between **Set up TraceLens** and
**Edit capture settings**; first-time installation and routine capture
edits have separate screens, not separate launch commands.
An invalid selected service does not prevent valid services from being configured.
Saving a reviewed configuration writes files only. A separate, explicitly
confirmed action can start or restart a registered local runtime; it does not
build or provision applications. Windows and non-POSIX installations are not supported.

## Prerequisites

Confirm the application workflow works **without TraceLens first**:

- **cb-app:** have a working native local `chargebee-app` checkout using Java 8.
  Start it with your normal workflow, log in successfully, and reproduce the
  target UI/API flow with its expected result before adding tracing.
- **Dependencies and data:** have the application's normal databases, dependent
  services, configuration, credentials and test data ready. Use your existing
  authenticated UI/API client; TraceLens does not create users or prepare a
  scenario's data.
- **Other selected services:** have their normal local workflow working first.
  EMS and Plans use Java 21 for the optional executable-JAR launchers. Ledger
  and Grant Orchestrator use their existing servlet/Tomcat runtime and launch
  workflow. Setup supplies tracing VM options; optional dashboard runtime
  profiles can explicitly select a working Tomcat or executable-JAR launch.
  For cross-service investigation, configure the caller to use the intended
  local downstream instance and confirm that route without tracing first.
  Unselected services are not prerequisites.
- **Source and runtimes:** know the real checkout paths for the selected services
  and use source corresponding to the application build you will run. Have JDK
  8+ available for setup/viewer, independently of the application's runtime.
  Source display reads the selected checkout; it does not verify build identity.

The applications **do not need to remain running during the wizard**. Once the
untraced workflow is confirmed, run setup to prepare files, then stop/restart
the selected applications deliberately with the generated tracing options.
Save any needed logs or live evidence before a restart. TraceLens does not
provision dependencies, build applications or configure credentials. A dashboard
start/restart is optional and separately reviewed after configuration is saved.

## 1. Unpack and open setup

The Java ZIP contains:

```text
tracelens.jar
setup.sh
tracelens.json          # portable cb-app template
ems/tracelens.json      # portable EMS template
```

Keep the extracted files together in a real absolute bundle path without spaces,
quotes or shell syntax. From that directory, use an existing JDK 8 or newer:

```sh
./setup.sh
```

The equivalent direct command is:

```sh
java -jar tracelens.jar setup
```

Setup prints a loopback URL and attempts to open it in your browser. If a browser
does not open, visit the printed URL on the same machine. If extraction did not
preserve the script's executable permission, use `sh setup.sh`. To choose a different
setup port or open the browser yourself:

```sh
./setup.sh --port 7782 --no-browser
# Equivalent: java -jar tracelens.jar setup --port 7782 --no-browser
```

The loopback-only setup session expires after an absolute 15 minutes and uses
same-origin/token authorization. Ctrl+C stops the dashboard, not a separately
running managed-service supervisor. Reopen setup after expiry; managed services
can remain running across dashboard sessions. Do not publish or tunnel this page.
Port `7782` is the setup UI, not the viewer, agent debug API or application.

The JDK used for setup/viewer is separate from the target application's JDK.
cb-app still uses Java 8 and its normal application environment. The optional EMS
and Plans launchers require an explicitly selected Java 21 installation. Setup does not
install JDKs, databases, dependencies or credentials, and does not build apps or
edit IDE run configurations.

## 2. Choose setup or configuration, then review the plan

For a first installation, choose **Set up TraceLens**. The short **Services &
locations** stage selects the services, checkouts, install directory and ports;
**Capture levels** selects capture policy before review/install.

For an existing guided installation, choose **Edit capture settings**, enter its
directory and choose **Load services**. The compact dashboard lists configured services instead
of opening every settings form. Use a service's **Edit capture settings** action to edit
only that service, **Add service** to extend the installation, and **Reload
configuration** to reread saved settings. Evidence, detail scope, boundaries and
advanced groups stay collapsed until needed. **Service actions** are separate
from capture editing and still require their own review/confirmation.
Each card reports a bounded, local agent identity check independently of process
ownership. An unavailable agent does not prevent editing its configuration or
working with other services. Connected does not mean application-ready, and
does not grant permission to restart an unmanaged process. Reload to refresh
these point-in-time checks.

Each service shows its **saved policy**: incoming API scope, effective detail
depth, package scope and independent SQL/request-data switches. Editors show a
preview, not a claim about the running process. Manual launches remain
unverified; managed processes can report a pending configuration restart, but
neither connectivity nor a successful restart proves that a particular API is
captured. Expiry previews use the browser's clock.

The editor distinguishes **Default capture level (without a scoped override)**
from **Scoped detail override & expiry**. Its visible policy preview describes
both, so selecting Overview does not hide a saved scope that promotes matching
paths to Detailed. **API paths to capture** controls admission independently of
the detail level and SQL/request-data opt-ins.

In either flow, review the applicable settings:

1. Choose a dedicated new/empty installation directory for setup, or load an
   existing guided directory in the configuration flow. Loading restores each
   service's capture settings, API paths, source root and agent port. Select only
   the services to configure; loading or selecting them does not start anything.
   For new services, enter their existing checkout roots. For
   cb-app, use the `chargebee-app` checkout containing `tomcat/bin/catalina.sh`.
   Source-root paths can contain spaces or apostrophes; they must still be real,
   absolute normalized paths, not symlink aliases.
2. Review each service's backend API path prefixes. These match an exact path or
   slash-delimited descendants across HTTP methods, not browser URLs or wildcard
   expressions. EMS downstream paths differ from cb-app paths; the supplied
   templates provide the initial scopes. An empty list (`httpIncludePaths: []`)
   disables retained HTTP capture for scheduler/messaging-only investigations;
   it does not disable those separate hooks.
3. Choose distinct agent debug ports and a viewer port. Defaults are cb-app
   `7778`, EMS `7779`, Plans `7786`, Ledger `7783`, GO `7784` and viewer `7781`.
   These do not change application business ports or provision a cross-service
   connection. An existing guided installation retains its viewer port.
4. Keep the installation directory separate from the extracted ZIP.
   Use real absolute paths without spaces, shell syntax or symlink aliases. Do
   not choose a repository root, home directory or filesystem root.
5. Choose capture depth separately for each service: **Overview**
   (`overview`) retains configured dependency/error observations; **Methods**
   (`methods`) adds method entry/exit without source-line events; **Detailed —
   methods plus executed lines** (`detailed`) includes both. `trackMethods` is a
   separate advanced switch and must remain enabled when method events are wanted. HTTP query
   parameters and request bodies are independent opt-ins, not capture-depth
   presets. JDBC SQL/bindings and native ClickHouse SQL/bindings are also
   independent opt-ins; bindings require that engine's query capture.
   Review package exclusions, jobs/executors, HTTP propagation destinations,
   dependency boundaries and retention budgets before widening capture.
   SQL literals, bindings and request data can be sensitive;
   `capturePayloads: false` does not sanitize them.
6. For an optional generated EMS or Plans shell launcher, supply both an existing built executable app JAR
   and its Java 21 home, using paths without spaces, quotes or shell syntax.
   Otherwise setup produces VM options to paste into your existing IDE or other
   supported launch configuration. Ledger and GO receive VM-options files for
   their existing servlet/Tomcat launchers; optional dashboard runtime profiles
   are a separate post-save action. The stricter path rule is for bundle/install,
   JAR/config and explicit launcher-runtime paths, not source-root names.

Review each service's outcome, proposed file paths, field-level before/after
changes, ports, warnings and any cb-app startup-hook change, then save explicitly.
Previewing is not installation. Ready additions install a service; ready updates
change only the reviewed capture fields and API paths. Unchanged services remain
unchanged. Services needing attention are skipped while valid changes proceed. An offline
service can still be configured: setup does not require it to answer a network
check. Configuration-only changes require a JVM restart, not an application rebuild.

Existing guided installations are recognized by their setup state or the exact
earlier generated viewer/check launcher layout. Other nonempty destinations are
refused. Setup never adopts unknown files or removes a previous integration to
make room. Existing registered services remain registered even when unchecked;
the dashboard does not uninstall them. Existing source roots, ports and launchers
are not silently rewritten by capture edits. A new cb-app attachment
still refuses `setenv.sh` that already contains an unrecognized TraceLens hook.
If inputs or relevant files change after preview, review a fresh plan.

See [capture controls](sharing-and-configuration.md#capture-controls-and-defaults)
and [SQL capture](jdbc-query-capture.md) before widening scope. Wider capture can
consume the same bounded retention budget sooner.

For a narrower investigation, set `detailedCapturePaths` and
`detailedCaptureUntilEpochMillis` under **Scoped detail override & expiry** before
restarting. Nonempty path scopes restrict method/line depth to matching exact
paths or descendants; unmatched paths fall back to overview. Methods stays
methods inside its scope; Overview with matching paths uses Detailed for those
matches. An empty list uses the selected default level. Expiry is
an absolute epoch-millisecond timestamp, not a duration; `0` means no time limit.
After expiry, depth falls back to overview. These are startup
settings, not a live configuration switch; details omitted earlier cannot be
recovered afterwards. SQL capture remains a separate deliberate opt-in.

## 3. What applying the plan writes

After saving, **Next: apply the saved settings** shows one progression: Saved →
Apply / restart explicitly → Open viewer → Reproduce once → Inspect evidence.
Only the file save is confirmed here. Start/restart deliberately, start the
viewer, check capture readiness for an exact backend API, then watch one
reproduction and inspect its evidence. **Open viewer
investigation** prepares a draft in the configured loopback viewer, preselecting
one successfully configured service when unambiguous. It does not start the
viewer, send application traffic or arm a watch. Review and confirm the exact
filters before checking or watching.

For saved configuration updates, **Review apply / restart actions**
preselects only successfully changed services. It opens the separate action
review; the existing plan and restart-consent requirements still apply. You can
instead restart through your normal launcher. A failed service does not prevent
verification of a successfully configured service.

In the viewer, **Check capture readiness** shows **Action required** for failed,
excluded or warning checks, and **Cannot verify** for missing, partial or unknown
admission evidence. **Ready to reproduce** means the running admission checks
passed at that moment—not that code coverage or a new capture is guaranteed.
Scope and retention limitations stay visible; passing details are collapsed.

Depending on the selected services, the installation layout is:

```text
<install-dir>/
  tracelens.jar
  cb-app/tracelens.json
  cb-app/setenv.sh.before-tracelens  # backup when setenv.sh already existed
  ems/tracelens.json
  ems/vm-options.txt
  plans/tracelens.json
  plans/vm-options.txt
  ledger/tracelens.json
  ledger/vm-options.txt
  go/tracelens.json
  go/vm-options.txt
  start-cb-app.sh
  start-cb-ems.sh                   # only with explicit built JAR + Java 21 home
  start-cb-plans.sh                 # only with explicit built JAR + Java 21 home
  start-viewer.sh
  check-setup.sh
  setup-state.json                 # identifies this guided installation
```

Only newly configured services receive new service-specific files. Existing
service updates preserve unrelated/unknown JSON fields and produce an exact
private backup before replacing a changed config, for example
`setup-backups/<unique-id>/ems/tracelens.json.before`. A config-only update does
not rewrite launchers, viewer state or agent JARs. A fresh
installation uses one combined agent/tooling JAR. The generated primary
JSON belongs to the first successfully configured service in the order cb-app,
EMS, Plans, Ledger, GO. It contains `viewer.services`, source roots and private
capture/snapshot directory settings. **No separate `viewer.json` is needed.**
The generated viewer/check scripts select this JSON for you. Private saved-data
directories are used when saving evidence; setup does not manufacture captures.

When adding services, setup preserves the primary config's capture settings,
existing registry entries, source roots, storage paths and viewer port. It only
extends that registry and pins its existing `viewer.namespace` so saved snapshots
and browser-saved state do not move to a different namespace. Private copies of
updated files are kept under `setup-backups/<unique-id>/`.

When adding services from a newer bundle, setup preserves the existing agent
JAR and application launchers. A content-versioned combined JAR is placed under
`viewer/` for the viewer and the newly added services. Only exact generated
viewer/check launchers are backed up and updated to reference it; customized
viewer launchers require manual review and are not overwritten. This is not a
running-agent upgrade, and applying the configuration plan does not restart the
viewer or applications. Manual launchers retain their original bytes and agent
paths: update their agent bundle explicitly before using newly supported modes
such as `methods`. Dashboard-managed starts use a private versioned copy of the
current bundle instead.

For cb-app, setup preserves and backs up an existing `tomcat/bin/setenv.sh`, then
appends a managed block gated by `TRACELENS=1`. The block appends agent options to
`CATALINA_OPTS`; it does not replace normal application options with a new
`JAVA_OPTS`. Normal launches without that opt-in do not activate the new hook.
Do not add a second agent option or a legacy TraceLens application adapter.

If one service fails while files are written, other selected services continue;
only successfully configured new services enter the shared registry. Review the
individual results. Preserve any partial service files and backups for inspection.
Setup never deletes partial output automatically; a conflicting service directory
must be reviewed before retrying that service. A shared registry/filesystem failure
can still stop finalization and is reported rather than claimed as success.

## 4. Start deliberately, then verify

**“Files written” is not “services connected” or “request captured.”** Saving
configuration does not start/restart an application. Neither configuration nor
runtime actions build applications or reproduce a business API. Save needed
live evidence before any deliberate restart; it lives
in the application JVM and is lost when that JVM exits.

### Optional dashboard-managed start/restart

After saving, or loading an existing installation even when its capture settings
need no changes, open **Service actions** and register the selected service's
runtime profile. Choose
`type: tomcat` or `type: jar`, an installed `javaHome`, `applicationPath` (Tomcat
root containing `bin/catalina.sh`, or an existing executable JAR),
`workingDirectory`, and the application's actual HTTP `applicationPort`.
Optional `arguments` are application arguments for JAR launches, not JVM flags;
Tomcat uses `catalina.sh run` and does not accept extra lifecycle arguments.
Use the application's supported JDK and normal prepared environment. Tomcat
profiles require trusted `catalina.sh`/`setenv.sh` startup scripts that preserve
the foreground child through `exec`; a customized launcher that forks or
daemonizes the JVM is not a supported ownership boundary. Enter the real
application port: this field is neither discovered nor used to change the
application's listener, and an incorrect value weakens collision detection.

Review the separate Start/Restart plan and explicitly confirm the interruption
warning. Both application and agent ports are checked. An existing IDE/custom
process is never adopted or killed by PID/port: stop it through its normal
launcher first, then review **Start**. A launcher being available does not mean
the dashboard owns its running process.

Each service has a private persistent supervisor that retains its child
`Process` handle, allowing subsequent dashboard sessions to restart only that
owned process. The first launch inherits the setup terminal's environment;
subsequent restarts inherit that supervisor's environment, not a newly opened
dashboard terminal's environment. There is no credential/environment editor and
no stored environment snapshot. Do not put secrets in persisted application
arguments. IDE-only settings are not imported.

A managed restart can select the current versioned agent JAR for the child,
but the already-running supervisor continues executing its original code.
Future supervisor-code upgrades therefore need deliberate operator-managed
recreation of that supervisor and its runtime; reopening the dashboard or
restarting only the child does not upgrade the supervisor. Preserve logs and
review ownership/state before that lifecycle change.

Restart requests graceful termination and waits up to 20 seconds. If the child
does not exit, it is not force-killed and no second process starts. After launch,
the supervisor waits up to three minutes for the matching TraceLens agent
connection. **Running/connected is not application health or business readiness**:
check the normal health/login/API workflow separately. Status refreshes recheck
both the child and its agent connection; a lost connection is not reported as
healthy merely because an earlier startup succeeded. Each service reports its
own starting/running/manual/failed result; one failure does not stop siblings.
An agent-connection timeout does not force-stop a still-running application.

Profiles, versioned agent copies and logs live under
`<install-dir>/managed-services/<service-key>/`, such as `ems` or `cbApp`.
`application.log` and `supervisor.log` are private local files; application output
can contain sensitive data, so review them before sharing. Closing the dashboard
or its 15-minute session does not undo saved configuration or stop these children.

### Existing manual launchers

Use the commands returned by the plan, replacing the example installation path:

```sh
# In an environment with cb-app's normal settings and a Java 8 JAVA_HOME:
sh /absolute/path/to/install/start-cb-app.sh
```

The cb-app launcher validates Java 8, runs the existing Tomcat launcher in the
foreground with `TRACELENS=1`, and retains the normal app startup configuration.
On macOS it can locate Java 8 when `JAVA_HOME` is unset; on Linux select an
existing Java 8 home explicitly. It is not a build/provisioning command. If an app
instance is already running, stop it intentionally using your normal workflow
before starting another. These generated shell launchers are separate from the
dashboard supervisor and do not register process ownership with it.

For EMS or Plans, either run its generated `start-cb-ems.sh` or
`start-cb-plans.sh` in the normal application environment, or copy its
`ems/vm-options.txt` or `plans/vm-options.txt` into the existing IDE/launcher **VM
options**, before `-jar` when launching an executable JAR. Do not paste them as
application arguments. Keep your existing profiles and environment settings.
The optional launcher inherits environment variables but does not import IDE
JVM options or expand `JAVA_OPTS`. Arguments passed to it follow `-jar` and are
application arguments, not JVM flags. If the service needs additional JVM options, keep
using your existing IDE/launcher and add the generated TraceLens VM options there.
For Ledger and GO, add `ledger/vm-options.txt` or `go/vm-options.txt` to the
existing servlet/Tomcat JVM launch configuration, keeping its normal runtime,
profiles, environment and credentials. Restart only the service you are enabling.

In separate terminals:

```sh
sh /absolute/path/to/install/start-viewer.sh
sh /absolute/path/to/install/check-setup.sh
```

If the viewer is already running, restart it deliberately with `start-viewer.sh`
to load newly added registry entries and any versioned JAR. Existing service JVMs
do not need to restart simply because another service was added.

Open the returned viewer URL (normally `http://127.0.0.1:7781`). The check script
runs read-only diagnostics against the selected services; resolve offline agents,
wrong identities, ports or configuration before investigating. It does not start
an application or prove that an API was captured.

Reproduce an allowed request through the intended entry service using your
normal authenticated workflow. Find that recent request in the viewer and
confirm the services actually called. A successful check or a direct downstream
request does not prove cross-service propagation. Configure both caller and
callee capture scopes and permitted trace-ID propagation destinations for the
intended route; adding a viewer entry alone does not instrument a process.
See [finding a reproduced request](how-to-use.md#3-find-the-request-you-just-made).

## Optional: inspect query performance

For a SQL-performance investigation, select **JDBC SQL queries** in the
dashboard for each relevant service; load saved settings first for an existing
installation. **JDBC parameter bindings** is optional and is not required
for Performance insights. Save the reviewed changes, then explicitly restart
that application through its normal launcher or managed profile before
reproducing the allowlisted request. A configuration-only change needs no app
rebuild. SQL text can contain sensitive literals even when parameters and
payloads are disabled; use local test data and review saved evidence before sharing.

In the viewer, open **Performance**. Choose recent captured
requests with its independent API/time filters, or one saved capture, then click
**Analyze**. The on-demand scan reads at most ten newest matching requests
sequentially or one saved graph. It does not turn on recording, execute SQL,
replay requests, save files or continuously monitor traffic.

Defaults flag JDBC executions of at least **100 ms**, repeated SQL shapes at
**3 calls**, suspected N+1 patterns at **5 calls**, and query-heavy
segment/destination scopes at **50 executions**. Thresholds are adjustable.
When a finding has multiple retained queries, select a **Captured occurrence**
before **Open query evidence** to inspect that query in its service timeline with
calling context when available. The picker is bounded and does not establish
request-wide chronology. Suspected N+1 is a lead to review,
not proof of a defect; retries, pagination and intentional loops can look similar.
No findings does not establish healthy performance or complete capture.

The current combined JAR includes the tab. A viewer-only update requires
restarting the viewer with the new JAR and existing config, not rebuilding or
restarting applications. Agent/capture changes require the affected JVM to
restart. Do not overwrite a shared JAR while applications are using it. Old
snapshots cannot gain SQL evidence they never recorded. See
[Performance insights](performance-insights.md) for analysis limits and interpretation.

## Later changes and manual setups

Rerun setup, load the existing guided directory, and edit each selected service's
capture settings. Review the before/after changes and private backups, then
restart that application JVM intentionally. Direct edits to the selected
`tracelens.json` remain supported. Restart the viewer after
changing its ports, service registry, source roots or saved-data directories.
When a port changes, keep the agent launch options and viewer service URL aligned.
Replacing a JAR on disk does not upgrade an already-running process.

Rerun setup to add services incrementally or reload existing settings. Unchecked
or unchanged registered services are preserved. Runtime profiles and lifecycle
actions remain separately reviewed from capture edits. The dashboard is not an
uninstall or general agent-upgrade tool, and does not
automatically migrate arbitrary manual installations. Preserve custom settings
and saved evidence when reviewing an existing integration.
For manual attachment, CLI/MCP commands and storage details, use the
[Java distribution reference](java-distribution.md) and
[private capture-storage guide](java-capture-storage.md).

## Troubleshooting

- **Preview changed:** a selected input or relevant file changed after review.
  Load/review again; do not overwrite an unreviewed edit.
- **Manual / port already in use:** stop the existing IDE/custom instance normally,
  confirm the correct application and agent ports, then review managed Start.
  Unverifiable old supervisor state requires manual inspection, not PID adoption.
- **Failed stop or connection timeout:** inspect the service's private logs.
  A 20-second stop timeout never force-kills; a three-minute agent timeout does
  not establish application failure or readiness. Correct its normal runtime,
  environment and agent configuration before retrying.
- **Capture saved but evidence unchanged:** restart the affected JVM. For manual
  launches, verify the agent path points to the current bundle; refreshing the
  viewer does not upgrade an agent or recover detail that was never recorded.
- **Session expired:** rerun `./setup.sh` and load the same installation.
  Saved configs, backups and separately managed processes remain available.

## Historical validation handoff — 2026-10-28

These are earlier implementation checkpoints, not a current installed-version
or live-service health report. Later verification is recorded in
[the project handoff](productivity-pilot.md).

### Setup / configuration UI split

- **Done:** one script now opens distinct setup and existing-service flows;
  routine edits are scoped to one service, advanced options are collapsed, and
  connection checks remain independent from ownership and runtime actions.
- **Evidence:** `./gradlew :tracelens-tool:test` passed all 1,461 tooling tests;
  `node --test tracelens-cli/tests/*.test.cjs` passed all 760 tests, including
  41 setup UI tests. Shell syntax and diff whitespace checks passed.
- **Packaged check:** disposable cb-app/EMS install, reload, EMS-only Methods
  update and Plans addition passed through the built JAR's setup API. Existing
  capture settings were preserved; offline agents were reported independently.
  Lifecycle plans were reviewed without applying them. ZIP/JAR/UI assets match.
- **Left:** interactive visual verification, including narrow-screen layout,
  was blocked by Chrome's `ERR_BLOCKED_BY_CLIENT`; no visual pass is claimed.
  The rebuilt ZIP must be opened to use this UI; no real services were changed.
- **Risks / assumptions:** the existing trusted-local, native-launcher scope
  still applies. Connection is a point-in-time agent identity check, not business
  readiness or process ownership. No dependencies or build settings were changed.

### Earlier capture / runtime validation

- **Done:** three capture levels, reusable per-service edits, backups, independent
  outcomes and separately confirmed managed start/restart are packaged in the Java ZIP.
- **Evidence:** `./gradlew test :tracelens-agent:java8Smoke :tracelens-agent:java8ToolSmoke`
  succeeded (1,771 Java tests passed; six optional JDBC integration tests skipped).
  `node --test tracelens-cli/tests/*.test.cjs` passed all 752 tests.
- **Browser check:** save, reload, edit, preview and save again worked against
  disposable cb-app/EMS directories. Runtime integration tests used disposable
  processes, including a Java 8 supervisor. No real application was restarted.
- **Left to apply:** launch the new bundle, load the intended installation, review
  settings and explicitly choose any runtime action; existing processes are not upgraded by packaging.
- **Assumptions and limits:** trusted local native Tomcat/executable-JAR workflows,
  accurate application ports and an already-working application environment.
  IDE processes are not adopted; supervisor-code upgrades require deliberate recreation.
