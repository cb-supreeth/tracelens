# How to use TraceLens

TraceLens helps you answer: **“When I called this API, which code ran and which
services did it call?”** Use it to understand a feature, debug a reproduction or
compare the code path before and after a change.

Start with one service. This guide uses **cb-app**; its repository is named
**chargebee-app**. Examples assume your checkouts are under `~/work`; adjust paths
for your machine. TraceLens is a local-development POC, not a production setup.

## 1. One-time setup

You need the TraceLens checkout, a normally working application, Python 3.10+
and JDK 21 to build the agent. Building the agent with Java 21 does not change
cb-app's own Java 8 runtime.

**Attach the agent first.** Follow [cb-app setup](cb-app-integration.md): build
the shaded agent, add the opt-in `setenv.sh` hook, and install the optional
`Makefile.tracelens` launcher. It is not installed automatically.
Then, from your already-built cb-app checkout:

```bash
cd ~/work/chargebee-app
make -f Makefile.tracelens check
make -f Makefile.tracelens restart
make -f Makefile.tracelens status
```

Restart clears Tomcat logs, clears live traces and enables jobs; save anything
important first. `make ... up` reuses a healthy JVM, so it cannot attach the agent
to an application already running without it.

**Install the CLI/viewer.** For a new Python environment:

```bash
cd ~/work/cb-tracelens/tracelens-cli
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e .
```

If the CLI is already installed in this environment, just activate it. In each
new terminal, return to this directory and run `. .venv/bin/activate` before
using the commands below.

For **EMS instead**, use its [agent setup](sharing-and-configuration.md#2-attach-it-at-startup),
service config and debug port **7779**, with `~/work/cb-ems` as the source root.
For another Java service, follow [standalone setup](standalone-agent.md).

## 2. Check setup and open the viewer

```bash
tracelens --url http://127.0.0.1:7778 doctor --source-root "$HOME/work/chargebee-app"
tracelens --url http://127.0.0.1:7778 serve --port 7777 --source-root "$HOME/work/chargebee-app"
```

Resolve any blocking issues reported by `doctor`, then open
[the viewer](http://127.0.0.1:7777). Leave the `serve` command running; Ctrl+C stops
only the viewer, not your application.

**7778 is the agent's debug port; 7777 is the viewer.** Neither is cb-app's normal
application port. Starting the viewer does not start the application or attach
the agent. To view cb-app and EMS together, follow the
[two-service registry setup](distributed-tracing.md#run-one-ui-or-mcp-for-both).

## 3. Find the request you just made

1. Use your application's normal logged-in UI or authenticated API client.
2. In TraceLens, filter by the backend API path, a short time window and your
   user when available. Enable **Collapse repeated APIs** to reduce clutter.
3. Open the matching request. Inspect its request-flow graph, then open a service
   segment to inspect observed methods and source lines. Use **Errors** for repeated failures.

For the bundled cb-app config, start with `/api/v2/features`; other API paths
may need a scope change. A browser route such as `/d/features/test1` is not
necessarily the API path. Each service shows the path it actually received.

You can also find the request from another terminal without knowing a trace ID:

```bash
tracelens --url http://127.0.0.1:7778 investigate \
  --method GET --path /api/v2/features --since-minutes 2
```

Multiple matches produce a candidate list. Narrow the path/time, choose a returned
trace ID, or explicitly add `--selection latest` if that is the run you want.

## 4. Ask Cursor or Codex

Connect your client once using [MCP configuration](sharing-and-configuration.md#mcp-configuration).
Use the installed `tracelens` executable's absolute path, the same agent URL and
the correct source root. Reconnect after updating TraceLens.

Then ask:

> Check that TraceLens is ready. I just called GET /api/v2/features. Find my
> request from the last two minutes, explain the code path and show its request
> flow. If several requests match, let me choose. Separate observed facts from guesses.

The assistant can discover the IDs itself. Embedded diagrams depend on the
client; the normal viewer and text results remain available. With the bundled
multi-service registry, filter by `cb-app`, not `chargebee-app`.

## 5. Compare before and after a code change

Immediately after reproducing the **old** behavior, save its latest matching run:

```bash
tracelens --url http://127.0.0.1:7778 capture save features-before \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest
```

Change the code, rebuild/restart the application and repeat the same action with
equivalent inputs and data. Then:

```bash
tracelens --url http://127.0.0.1:7778 capture save features-after \
  --method GET --path /api/v2/features --since-minutes 2 --selection latest
tracelens capture compare features-before features-after
```

Saved captures are bounded local JSON files in `~/.tracelens/captures`, not a DB.
They survive application restarts and comparisons work offline. Use unique names
for each experiment; `tracelens capture list` shows IDs if names repeat.
Browser **Pin capture** is separate storage and cannot be read by MCP.
See [comparison details and limits](developer-workflow.md#3-save-baseline-and-candidate-evidence).

## If something looks wrong

- **Cannot connect:** run `doctor`; check that the app was restarted with the
  agent and that you used its debug port, not the application/viewer port.
- **No matching request:** check authentication, API path, time window and capture
  scope. The bundled cb-app setup captures `/api/v2/features` by default.
- **Request exists but source lines are missing:** check Java package scope,
  compiled line information and the source checkout. It must match the running build.
- **Traces vanished:** live data is kept in bounded JVM memory. Save important
  evidence before restart or eviction. A saved view preserves filters, not traces.
- **A dependency has no detailed trace:** its caller-side boundary may be visible
  without a receiver agent; that does not mean its execution was fully captured.

Missing evidence does **not** prove dead code or a safe change. Keep using tests,
source review and normal logs. Review captures before sharing: paths, identifiers
and source information can be sensitive.
