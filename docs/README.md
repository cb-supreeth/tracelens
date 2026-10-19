# TraceLens documentation

Reviewed against the repository implementation on **2026-10-19**. The root
[README](../README.md) is the concise overview; these guides provide the detail.
"Implemented" means present in source, not automatically activated in an existing
service JVM or validated for production. Roadmap ideas are labelled separately.

The current naming convention is **`cb-app` for the TraceLens application/service
identity** and **`chargebee-app` for the repository/orchestrator name**. The Java
fallback, bundled agent JSON and viewer/MCP registry now agree on `cb-app`;
explicitly configured application names are still respected.

## Choose a reading path

- **New developer:** [how to use TraceLens](how-to-use.md) →
  [focused workflow](developer-workflow.md) → [use cases](use-cases.md).
- **Working on cb-app/EMS:** [cb-app setup](cb-app-integration.md) →
  [distributed tracing](distributed-tracing.md) →
  [dependency visibility/change impact](dependency-visibility-and-change-impact.md).
- **Starting or restarting cb-app with tracing:**
  [optional Makefile installation and commands](cb-app-integration.md#optional-makefile-launcher).
  The launcher is a template in this repo, not an automatically installed app dependency.
- **Understand it as an author:** [codebase walkthrough](codebase-walkthrough.md) →
  [Byte Buddy internals](byte-buddy-instrumentation.md) →
  [visualization evidence rules](hybrid-visualization.md).
- **AI-assisted investigation or sharing:** [sharing/MCP configuration](sharing-and-configuration.md) →
  [hybrid visualization](hybrid-visualization.md) → [use-case workflows](use-cases.md).

## Guide index

| Guide | Owns the detailed explanation of |
| --- | --- |
| [How to use TraceLens](how-to-use.md) | Short onboarding path: first setup, viewer, API discovery, assistant prompts, before/after captures and troubleshooting |
| [Developer workflow](developer-workflow.md) | Setup diagnostics, request investigation, explicit local capture and offline comparison through CLI/MCP |
| [Productivity pilot](productivity-pilot.md) | Proposed adoption/time-saving gates, evaluation scorecard and overhead-validation plan |
| [Use cases](use-cases.md) | Developer questions, available/manual/planned workflows, prerequisites and honest limits |
| [Standalone agent](standalone-agent.md) | Recommended agent-only startup, supported servlet/runtime seams and debug API |
| [cb-app integration](cb-app-integration.md) | Java 8/Tomcat setup, optional Makefile launcher, restart/log implications, identity and legacy adapter alternative |
| [Distributed tracing](distributed-tracing.md) | Independent/combined cb-app and EMS, W3C IDs, propagation trust, ports and joining |
| [Sharing and configuration](sharing-and-configuration.md) | Install/build/distribute artifacts, configure another service and connect MCP |
| [Codebase walkthrough](codebase-walkthrough.md) | Modules, capture-to-export/source flow, architecture and implementation details |
| [Byte Buddy instrumentation](byte-buddy-instrumentation.md) | Bytecode vs source, line tables, advice/callbacks, scopes and performance/failure caveats |
| [Request-focused UI](request-focused-ui.md) | Discovery, filtering, repeated APIs, saved views, browser-local captures and noise |
| [Error groups](error-groups.md) | Failure categories/signatures, request membership, filters and MCP error tools |
| [Hybrid visualization](hybrid-visualization.md) | Shared request graph, MCP Apps fallback and client-generated segment-execution diagrams |
| [Dependency visibility and change impact](dependency-visibility-and-change-impact.md) | External HTTP/queue coverage, untraced receivers, evidence semantics and manual impact workflow |
| [Optional UI action integration](ui-action-integration.md) | App-supplied action/session labels; not required for API-based discovery |

## Important distinctions

| Often confused | Current meaning |
| --- | --- |
| Agent vs viewer/MCP | Capture/store are inside the service JVM; viewer/MCP is a separate reader process |
| `cb-app` vs `chargebee-app` | TraceLens identity/filter name vs repository/orchestrator name; supplied registry has no automatic alias |
| Standalone vs application adapter | Standalone is recommended; Spring/javax adapters remain optional legacy modes |
| Application port vs debug port | Local examples use app 8080 / EMS 8082, debug 7778 / 7779, combined viewer 7781 |
| In-memory vs persisted | JVM retention is transient; pins use browser IndexedDB, explicit CLI/MCP snapshots and exports use files; no server DB |
| Capture vs propagation | Recording a supported local boundary does not automatically trust a destination or inject headers |
| Request graph vs execution model | Graph connects observed boundaries/segments; execution model reconstructs bounded local method nesting |
| Observation vs completeness | Missing evidence is not proof that code/dependencies were unused or a change is safe |
| UI filter vs capture scope | Filters change presentation; agent scope/settings control instrumentation/capture |
| Source update vs running version | Rebuild the shaded jar after Java changes and restart the target JVM; restarting only the viewer does not update instrumentation |

## Maintaining these guides

Verify behavior against source, configuration and tests before changing a claim.
Use setup guides for commands, the coverage guide for client support and the
use-case catalog for workflow status; link rather than duplicate long procedures.
Keep local examples explicit, credentials out of examples, restart requirements
visible and production/roadmap promises separate from implemented behavior.

Do not delete the optional-action or legacy-adapter guides just because they are
not the recommended default. They describe still-supported opt-in paths.
