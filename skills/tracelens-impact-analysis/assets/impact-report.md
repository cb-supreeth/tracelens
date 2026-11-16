# Change-impact report structure

Use in the response; omit empty sections and keep proportional to the change.
Save a file only when requested. Replace guidance with actual findings, not
boilerplate or unsupported certainty.

## Summary

State intended change, most important potentially affected flows, and next
regression checks. Identify prospective, static-only, or runtime/test-supported
analysis. Do not declare production safety.

## Scope and evidence

- Repositories, diff/base/revision, changed symbols/contracts.
- Relevant API/catalog version, site/flags, assumptions.
- Runtime scenarios, trace/segment/capture IDs, instances, timestamps, and
  build-match status (verified/mismatched/unknown with basis).
- Documentation pages/sections, versions, retrieval date; unavailable sources.
- Tests inspected/run, exact commands/results, or why not run.
- Limits: omitted scope, partial/evicted/ambiguous captures, unsupported
  boundaries, source/build mismatches, unresolved overloads or consumers.

## Affected flows

| Flow / entry point or consumer | Changed code / contract and possible effect | Evidence classification | Supporting evidence and gaps | Focused regression assertion |
| --- | --- | --- | --- | --- |

For each populated row:

- Classify as Observed reachability, Potential impact, Verified behavior
  change, and/or Unknown coverage, with a basis.
- Link file/line and revision, trace/capture/segment evidence, test assertions/
  results, and official documentation sections.
- Explain a mechanism, not merely a list of possibly related systems.
- Separate risk/priority from evidence strength. Missing observations do not
  establish an unaffected flow or dead code.

## Before/after findings (when available)

Separate path/count/dependency differences from verified result changes. State
whether builds, inputs, configuration, data state, and capture scope were
comparable. Empty method deltas do not prove symbol execution or safety; single
timing samples are not performance benchmarks.

## Regression plan and open questions

Prioritize scenarios with setup/configuration, action, and expected assertion.
Identify existing tests to run or missing tests to add. Label proposed versus
executed tests; queue publish does not verify consumption and DB routing does
not verify persistence.

Finish with the smallest missing evidence/user choice needed to improve the
analysis and potentially affected flows not exercised. Do not turn evidence
collection into unauthorized replays, configuration changes, or data writes.
