# TraceLens documentation

Current feature overview updated on **2026-10-28**. The root
[README](../README.md) is the concise overview; these guides provide the detail.
"Implemented" means present in source, not automatically activated in an existing
service JVM or validated for production. Roadmap ideas are labelled separately.

The current naming convention is **`cb-app` for the TraceLens application/service
identity** and **`chargebee-app` for the repository/orchestrator name**. The Java
fallback, bundled agent JSON and viewer/MCP registry now agree on `cb-app`;
explicitly configured application names are still respected.

## Choose a reading path

- **Give a product demo:** [verified demo guide](demo-guide.md) covers a short
  narrative, local saved examples, interaction checklist and evidence limits.
- **Configure local cb-app, EMS, Plans, Ledger or GO (or add services later):** [guided setup](guided-setup.md) →
  [find a reproduced request](how-to-use.md#3-find-the-request-you-just-made).
- **Share the Java bundle:** [distribution/manual reference](java-distribution.md) →
  [saved-data compatibility and safety](java-capture-storage.md).
- **New developer:** [how to use TraceLens](how-to-use.md) →
  [focused workflow](developer-workflow.md) → [use cases](use-cases.md).
- **Working on cb-app/EMS:** [cb-app setup](cb-app-integration.md) →
  [distributed tracing](distributed-tracing.md) →
  [dependency visibility/change impact](dependency-visibility-and-change-impact.md).
- **Starting or restarting cb-app with tracing:**
  [guided setup](guided-setup.md), or the
  [manual startup hook](cb-app-integration.md#enable-in-an-already-built-local-cb-app).
- **Understand it as an author:** [codebase walkthrough](codebase-walkthrough.md) →
  [Byte Buddy internals](byte-buddy-instrumentation.md) →
  [visualization evidence rules](hybrid-visualization.md).
- **AI-assisted investigation or sharing:** [sharing/MCP configuration](sharing-and-configuration.md) →
  [impact-analysis skill](impact-analysis-skill.md) →
  [hybrid visualization](hybrid-visualization.md) → [use-case workflows](use-cases.md).
- **Investigate and compare a reproduction:** [diagnose/watch a new request](developer-workflow.md#2-reproduce-and-find-the-request-by-api) →
  [compare saved evidence](developer-workflow.md#3-save-baseline-and-candidate-evidence) →
  [search or focus code](developer-workflow.md#find-code-in-current-and-saved-evidence).
- **Inspect database activity:** [opt-in SQL and parameter capture](jdbc-query-capture.md) →
  grouped **DB CALL** cards → adjacent inspector with operation/table previews, original SQL/typed bindings
  and recorded calling code. Reconstructed SQL is a debugging aid, not exact wire SQL.
- **Investigate slow/repeated SQL:** [Performance insights](performance-insights.md)
  analyzes a bounded selection on demand; N+1 findings remain hypotheses.
- **Inspect incoming API data or ClickHouse SQL:**
  [bounded request-data and native-query opt-ins](request-data-and-clickhouse.md).
- **Plan shared-dev/sandbox impact analysis:** [roadmap item 7](roadmap.md#7-shared-devsandbox-code-to-flow-impact-map)
  describes a future build-aware method/API-to-flow map to focus regression tests
  and reduce blind sandbox waiting, not replace tests or prove safety.

## Guide index

| Guide | Owns the detailed explanation of |
| --- | --- |
| [Demo guide](demo-guide.md) | Verified local examples across services, SQL, cache, SQS and ClickHouse; presentation order and honest capability boundaries |
| [Guided local setup](guided-setup.md) | Per-service capture dashboard on macOS/Linux, backed-up edits/additions, separately confirmed local runtime actions and post-start verification |
| [Java-only distribution](java-distribution.md) | Combined JAR/setup ZIP and service configs, manual attachment and Java viewer/CLI/MCP |
| [Java capture storage](java-capture-storage.md) | Paged V2 captures, archive import/export, legacy compatibility, quotas, private files and concurrency |
| [Java workflow validation](java-parity-validation.md) | Historical validation checkpoints, reproducible test commands and remaining limits |
| [Local SQL query capture](jdbc-query-capture.md) | Opt-in PostgreSQL/MySQL SQL and bounded parameters, grouped DB calls, reconstructed-value previews, original templates/typed bindings, calling code and sharing cautions |
| [Request data and native ClickHouse SQL](request-data-and-clickhouse.md) | Incoming query/body opt-ins, diagram disclosures, copying and keyword search; native ClickHouse queries/bindings and metadata-only fallbacks |
| [Performance insights](performance-insights.md) | On-demand slow/repeated-query and suspected N+1 findings, thresholds, evidence navigation and sampling limits |
| [How to use TraceLens](how-to-use.md) | Short onboarding path: first setup, viewer, API discovery, assistant prompts, before/after captures and troubleshooting |
| [Developer workflow](developer-workflow.md) | Diagnose/watch, viewer and CLI/MCP capture comparison, saved-evidence search, execution focus and local-editor navigation |
| [Impact-analysis skill](impact-analysis-skill.md) | Shared Cursor/Codex skill setup, local code + runtime + documentation + test evidence, before/after scenarios and pilot evaluation |
| [Productivity pilot](productivity-pilot.md) | Proposed adoption/time-saving gates, evaluation scorecard and overhead-validation plan |
| [Roadmap](roadmap.md) | Current Java/investigation/SQL workflows and planned impact reports, portable review bundles, dependency diagnostics and bounded dev/sandbox evidence with a build-aware method/API-to-flow index |
| [Use cases](use-cases.md) | Developer questions, available/manual/planned workflows, prerequisites and honest limits |
| [Standalone agent](standalone-agent.md) | Recommended agent-only startup, supported servlet/runtime seams and debug API |
| [cb-app integration](cb-app-integration.md) | Java 8/Tomcat setup, optional Makefile launcher, restart/log implications, identity and legacy adapter alternative |
| [Local credit-stack integration](credit-stack-integration.md) | Ledger/Grant Orchestrator setup, capture bounds and correlation limits; UI middleware is excluded |
| [Distributed tracing](distributed-tracing.md) | Independent/combined cb-app and EMS, W3C IDs, propagation trust, ports and joining |
| [Sharing and configuration](sharing-and-configuration.md) | Install/build/distribute artifacts, configure another service and connect MCP |
| [Codebase walkthrough](codebase-walkthrough.md) | Modules, capture-to-export/source flow, architecture and implementation details |
| [Byte Buddy instrumentation](byte-buddy-instrumentation.md) | Bytecode vs source, line tables, advice/callbacks, scopes and performance/failure caveats |
| [Request-focused UI](request-focused-ui.md) | Viewer navigation, request investigation, grouped APIs/jobs, saved views/files, comparison/search and legacy browser pins |
| [Error groups](error-groups.md) | Failure categories/signatures, request membership, filters and MCP error tools |
| [Hybrid visualization](hybrid-visualization.md) | Request graph, segment execution flow/source drill-down and MCP visualization options |
| [Dependency visibility and change impact](dependency-visibility-and-change-impact.md) | External HTTP/queue coverage, untraced receivers, evidence semantics and manual impact workflow |
| [Optional UI action integration](ui-action-integration.md) | App-supplied action/session labels; not required for API-based discovery |

## Important distinctions

| Often confused | Current meaning |
| --- | --- |
| Agent vs viewer/MCP | Capture/store are inside the service JVM; viewer/MCP is a separate reader process |
| `cb-app` vs `chargebee-app` | TraceLens identity/filter name vs repository/orchestrator name; supplied registry has no automatic alias |
| Standalone vs application adapter | Standalone is recommended; Spring/javax adapters remain optional legacy modes |
| Application port vs debug port | Local examples use app 8080 / EMS 8082, debug 7778 / 7779, combined viewer 7781 |
| In-memory vs persisted | JVM retention is transient; Save snapshot writes paged, compressed V2 evidence with stable links and ZIP export/import. Legacy JSON and browser pins remain distinct; Use with CLI/MCP explicitly creates a separate minimized file. No server DB |
| Capture vs propagation | Recording a supported local boundary does not automatically trust a destination or inject headers |
| DB route vs SQL execution | Datasource routing is not a query count; opt-in JDBC events observe driver calls, not transaction commit |
| SQL preview vs captured evidence | The viewer substitutes only known, safely mapped values for readability; original SQL and typed bindings remain separate, and uncertain values stay `?` |
| Request graph vs execution model | Graph connects observed boundaries/segments; execution model reconstructs bounded local method nesting |
| Observation vs completeness | Missing evidence is not proof that code/dependencies were unused or a change is safe |
| UI filter vs capture scope | Filters change presentation; agent scope/settings control instrumentation/capture |
| Source update vs running version | Rebuild the shaded jar after Java changes and restart the target JVM; restarting only the viewer does not update instrumentation |
| JSON change vs rebuild | Capture settings are read at JVM startup; editing the selected JSON needs an app restart, not an app rebuild solely for the config change |
| Checkout vs installed viewer | Rebuild/replace the combined JAR and restart viewer/MCP for tooling/UI changes. Previously downloaded JARs do not update themselves; a version string alone does not prove the latest build is loaded |

## Maintaining these guides

Verify behavior against source, configuration and tests before changing a claim.
Use setup guides for commands, the coverage guide for client support and the
use-case catalog for workflow status; link rather than duplicate long procedures.
Keep local examples explicit, credentials out of examples, restart requirements
visible and production/roadmap promises separate from implemented behavior.

Do not delete the optional-action or legacy-adapter guides just because they are
not the recommended default. They describe still-supported opt-in paths.
