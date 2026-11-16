# Impact analysis with Cursor or Codex

Use the shared **`tracelens-impact-analysis`** skill to answer:

> What could this code change affect, what evidence supports that, and what
> should I test?

The skill combines the local Git diff and code, existing tests, TraceLens runtime
evidence, and relevant Chargebee product/API documentation. It is an
instruction-only workflow for the assistant: it adds no JVM callbacks, database,
background collection, MCP tools or new viewer UI. It does not automatically
install or start TraceLens, change capture scope, run business requests, or edit
your application.

## Enable it in your project

The canonical, portable package is
[`skills/tracelens-impact-analysis/`](../skills/tracelens-impact-analysis/SKILL.md).
Both [Codex](https://learn.chatgpt.com/docs/build-skills) and
[Cursor](https://cursor.com/docs/skills) support project skills in `.agents/skills/`.
The same package is used by both clients; do not maintain separate copies of its
instructions for each IDE.

The package is **not automatically installed or discovered** from `skills/`.
To enable it in **chargebee-app**, **cb-ems**, another service, or this TraceLens
checkout, use a project where you are authorized to modify `.agents/`:

1. Explicitly copy the **entire** `tracelens-impact-analysis` folder from this
   repository's `skills/` into the target repository's `.agents/skills/`.
   Create the parent directory if needed. Include `references/` and `assets/`,
   not only `SKILL.md`.
2. If the destination folder already exists, stop and review the differences;
   do not overwrite a customized skill. Keep this repository's package as the
   source for reviewed updates. Copying is manual, not an automatic installation
   or synchronization process.
3. Open the service project in your client and confirm that
   `tracelens-impact-analysis` appears in its available skills. Reload the project
   or start a new conversation if skill discovery has not refreshed.
4. Separately connect TraceLens using the existing
   [MCP configuration guide](sharing-and-configuration.md#mcp-configuration).
   For multiple services, use the
   [service registry setup](distributed-tracing.md#run-one-ui-or-mcp-for-both).
  An installed skill is **not** an MCP connection or an attached Java agent.

Before installation, you can also give your assistant the `SKILL.md` file path
and ask it to follow that workflow and its linked references for a specific
change. This is an explicit file-based workflow, not automatic skill discovery.

The copied structure is:

```text
<service-repository>/.agents/skills/tracelens-impact-analysis/
  SKILL.md
  references/
    runtime-evidence.md
    documentation.md
  assets/
    impact-report.md
```

Give the assistant access to the relevant service checkouts and a browsing or
documentation-reading tool. Product documentation starts at
[Chargebee docs](https://www.chargebee.com/docs); public API documentation starts
at [API getting started](https://apidocs.chargebee.com/docs/api/getting-started).
The skill's documentation reference defines targeted retrieval and version
checks; there is no need to mirror all documentation into this repository.

If MCP is unavailable, the assistant can still review local code/tests and
available documentation, but must label runtime coverage as unknown. If browsing
is unavailable, it must mark documentation unverified rather than inventing
citations. If neither is available, it can produce a clearly labelled static
code/test analysis and a list of evidence to collect next.

## Ask for an analysis

Invoke **`$tracelens-impact-analysis` in Codex**, or
**`/tracelens-impact-analysis` in Cursor**, followed by the request. For example:

> Analyze my uncommitted billing_metrics changes in this checkout. Use local
> callers, consumers and tests, relevant Chargebee product/API documentation, and
> available TraceLens evidence from cb-app and EMS. Identify potentially affected
> APIs, jobs and queue flows, explain the evidence for each, and recommend
> regression tests. Do not change code, restart services, save captures or replay
> requests. Tell me what is unknown, including whether the running build matches
> my checkout.

You can instead specify an exact diff range, changed files, a business scenario,
an API path, or saved capture IDs. State the relevant API/Product Catalog version
and feature/site configuration when known. Do not include credentials or customer
payloads in the prompt. For an initial review, the assistant does not need a
complete trace history or a capture from every candidate flow.

The report follows the shared
[report template](../skills/tracelens-impact-analysis/assets/impact-report.md),
with affected flows, linked evidence, uncertainties and concrete tests. It uses
four distinct evidence categories:

| Category | Meaning |
| --- | --- |
| Observed reachability | A specific captured scenario executed the changed code; this alone does not prove a changed business result. |
| Potential impact | Static callers, consumers, contracts or documentation suggest an effect that has not been verified. |
| Verified behavior change | Comparable executions or tests establish a changed result; a different code path alone is insufficient. |
| Unknown | Missing evidence, unsupported boundaries, capture limits, version ambiguity or other gaps prevent a conclusion. |

Jobs and queue consumers remain in scope even though the viewer separates
scheduled jobs from HTTP requests. Response schemas, events, shared data and
configuration can affect consumers without a direct Java caller relationship.
Documentation is evidence of a public/business contract, not proof of an internal
implementation mapping. The assistant must cite the code that supports that
mapping or label it as a hypothesis.

## Validate with named before/after scenarios

Use the existing [capture workflow](developer-workflow.md#3-save-baseline-and-candidate-evidence)
and [MCP tools](developer-workflow.md#4-use-the-same-workflow-through-mcp); the skill
does not introduce another capture store.

1. Pick one scenario with explicit inputs, expected results and relevant
   configuration, such as `feature-create-standard-site`.
2. Reproduce the baseline yourself, then explicitly ask the assistant to save
   the selected run as `feature-create-standard-site-before` with the known
   baseline build label. Save while all required segments are still retained and
   **before** restarting. Resolve ambiguous matches rather than guessing.
3. Change/rebuild/restart through your normal workflow. Reproduce the same
   scenario with equivalent data and configuration, then explicitly save the
   candidate as `feature-create-standard-site-after` with its known build label.
4. Ask the skill to compare the saved runs, review the code diff and tests, and
   update the impact report. Use capture IDs if names are not unique.

For example:

> Compare the saved feature-create-standard-site-before and
> feature-create-standard-site-after captures against my current diff. The
> baseline and candidate used the same scenario and site settings. Explain which
> differences are observed, which behavior changes tests verify, and which other
> flows still need testing. Do not replay or change anything.

A supplied revision is a **label**, not verification of the running artifact.
Record any build/checkout mismatch. New evidence can carry method descriptors;
older captures without them cannot reliably distinguish overloads. Moved line
numbers are not a source-aware diff. Missing methods
are not proof of dead code; unchanged captures are not proof a change is safe.
CLI/MCP captures are bounded summarized files, separate from the viewer's
**Save snapshot** files and browser pins. In **Saved captures**,
**Use with CLI/MCP** explicitly creates a
minimized CLI capture from a selected viewer snapshot. It excludes source text,
user/context fields and exception messages, preserves the original, and does
not fetch live replacements. Use the returned capture ID and the same configured
capture directory in CLI/MCP. See the linked workflow for quotas, privacy and
completeness limits.

To find relevant prior runs, `search_saved_captures` searches literal class,
method, exception-type or destination evidence in the minimized capture store.
The viewer's **Search saved evidence** also searches its full saved snapshots;
use the explicit projection above before asking MCP to compare one. Both are
bounded lookups, not a complete code-coverage index. A missing match does not
establish that a method is unused. Large saved captures use a bounded analysis
projection; review its completeness/omission warnings. For new reproductions, **Start an investigation**
can diagnose an exact backend path and explicitly watch/save one new completed
request; it never runs the business request or changes recording on your behalf.

Analysis is read-only by default. Capturing files, running tests with side
effects, reproducing mutating APIs, restarting services, broadening capture scope
or editing code needs a separate appropriate request. Public documentation
queries must not contain internal source, customer data, credentials or captured
identifiers. Existing company AI/data policies still apply to local evidence
sent to the assistant.

## Pilot before expanding it

Try a small set of real changes whose affected behavior reviewers can establish:
one straightforward API change, one shared helper or response contract change,
and one job/queue or cross-service change. Include a case with intentionally
missing runtime evidence.

- Compare normal AI-assisted code review with the same review plus this skill,
  using the same diff and independently established expected impacts.
- Record time to the first useful test plan and total investigation time,
  including capture/setup effort.
- Count useful additional findings, false alarms and missed known impacts; have
  a developer review evidence quality rather than accepting the report itself
  as ground truth.
- Check that unknowns remain explicit and that the report helps choose actual
  regression tests instead of merely producing a longer list of possibilities.
- Repeat on new changes before claiming productivity gains. Use the existing
  [productivity pilot scorecard](productivity-pilot.md) for broader adoption and
  instrumentation-overhead evaluation.

The first version uses existing runtime tools and static inspection to find
candidate flows. There is no automatic changed-symbol-to-all-saved-workflows
index. Add such a capability only if the pilot shows that discovery is a genuine
bottleneck; this skill does not guarantee complete impact analysis or production
readiness.

## Package validation

Validated on 2026-10-19 with the skill-format validator, local-link checks, and
two independent simulated reviews: one with partial runtime evidence and
supplied test results, and one with static code/tests only. Both identified a
shared helper's potential effect on a scheduled export and kept unverified
consumer coverage explicit. The partial-evidence case did not treat HTTP 200,
empty method deltas, DB routing, or queue submission as end-to-end verification.

These are instruction-level fixture checks, not a live Cursor/Codex discovery
test, a real Chargebee change-impact pilot, or measured productivity gains.
Client installation and connection should be verified using the setup above.
