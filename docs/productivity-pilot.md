# TraceLens productivity pilot

This POC succeeds only if developers solve real problems faster than with their
existing IDE assistant, source navigation and logs. Feature count and resemblance
to a commercial product are not acceptance criteria.

## Proposed pilot, not measured results

Run a two-week pilot with 3–5 developers. These are starting targets to agree
with the team, not claims about current performance:

- First useful capture within 10 minutes after application/build prerequisites
  are available. Count setup friction separately from application startup time.
- Routine API investigation without manually copying trace IDs or editing configuration.
- A meaningful median reduction in investigation time; initially aim for roughly
  25% on comparable tasks, while retaining correctness.
- Independent voluntary reuse after the introductory walkthrough.
- No application correctness regressions and measured, agreed runtime overhead.

Prioritize only three tasks: explain a reproduced failure, understand an observed
path/dependency, and compare a controlled before/after scenario. Use the
[implemented CLI/MCP workflow](developer-workflow.md).

For the local cb-app pilot, follow the [integration guide](cb-app-integration.md)
once, then use the optional launcher's `check`/`status` and `tracelens doctor`
to record setup friction separately from investigation. `chargebee-app` is the
checkout/lifecycle name; `cb-app` is the bundled TraceLens service alias.
Do not count a source/config update as a successful rollout: verify the running
agent and a newly reproduced request. The launcher's `up` can reuse an existing
healthy JVM; `restart` clears Tomcat logs and enables jobs, so preserve evidence
and choose the intended lifecycle operation before the measured task.

## Record one row per task

Copy this table into your team's agreed review document; no automatic analytics,
developer surveillance or telemetry is enabled by this implementation.

| Task/scenario | Tooling used | Setup minutes | Investigation minutes | Correct result verified by | Useful evidence | Friction / misleading evidence | Would reuse? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Example: feature API failure | Existing IDE + logs, or IDE + TraceLens | Not measured | Not measured | Test / reproduction / peer review | Not measured | Not measured | Not measured |

Use comparable tasks and rotate tool/order assignments where practical. Repeating
the same problem after learning the answer is not a fair speed comparison.
Record failures, abandoned attempts and cases where existing logs were sufficient.
Do not turn a small POC sample into a statistically established organization-wide
productivity claim.

## Measure existing instrumentation separately

Use an approved local/test workload, the same application build and inputs, a
warmup, multiple measured runs, and both agent-off and intended-agent-config runs.
Record latency distribution, throughput, CPU, heap/GC, errors, startup time and
capture loss/limits. Keep database/network state comparable and report variation.
Compare no-agent, narrow-scope capture and any broader proposed scope separately.

Agree the acceptable budget **before** rollout. These workflow changes introduce
no new Java callbacks; that does not establish that existing tracing is cheap.
Doctor readiness is not a performance, privacy or production-readiness certificate.

## Decide at the end

- **Continue** when developers independently reuse it and correct investigations
  demonstrably save net time, including setup and maintenance.
- **Narrow the scope** when one workflow helps but others add complexity or noise.
- **Stop/pivot** when benefits do not exceed integration, overhead and maintenance
  costs. Record the evidence instead of adding features to justify prior effort.

Defer a database platform, global architecture analytics, dead-code suggestions,
document ingestion and production rollout until the core workflow demonstrates
value. Automated source-aware impact analysis and durable team history remain
separate future work, not requirements for this pilot.
