# TraceLens demo guide

Fresh local examples captured on **2026-10-26**. The links below open saved
viewer snapshots on the demo machine; `127.0.0.1` links are not shared hosted
captures and will not resolve to these records on a teammate's machine.
Snapshots retain recorded evidence and do not replay business requests.

All nine originals were reopened and verified on **2026-10-28**. Find the
numbered **01–09** examples in **Saved captures**; no application services need
to be running to view them. These are the original recordings, not new API runs.

## The story to tell

> Start with an API request, see which services and external systems it reached,
> then follow a recorded call back to code and inspect the request or query that
> explains it. Save the evidence so another investigation does not depend on a
> transient in-memory trace.

Use the compact request flow first: **HTTP hops are hidden and the diagram fits
by default**. Expand HTTP hops when showing propagation or caller-side timing.
Each observed invocation has its own service frame. Two Ledger API invocations
are two recorded segments, not necessarily duplicate instrumentation.

## Suggested run of show

| Example | Open | What to demonstrate |
| --- | --- | --- |
| Feature creation | [01 · Feature create](http://127.0.0.1:7781/#snapshot=a4d876fe-d105-469d-8304-d2e2fc3d8be4) | cb-app → EMS, grouped MySQL/PostgreSQL calls, and SQS publish. This sample has 45 retained MySQL and 45 PostgreSQL executions; the queue publish is recorded as `SUBMITTED`. |
| Cache miss | [02 · Cache MISS](http://127.0.0.1:7781/#snapshot=66bea93d-237e-4081-b768-b98cf7f85b96) | EMS reaches the cache-put branch (recorded line 804), with 7 retained SQL executions. |
| Cache hit | [03 · Cache HIT](http://127.0.0.1:7781/#snapshot=f14d19f3-a0c5-4925-91ae-e6849d448486) | EMS reaches the cached-return branch (recorded line 794), with 3 retained SQL executions. Compare the recorded code paths, not just elapsed times. |
| ClickHouse | [04 · Usage events](http://127.0.0.1:7781/#snapshot=b80e5525-5196-468b-ac60-7c1a497cf9e7) | Select the grouped ClickHouse card and inspect all 3 native queries in Selected evidence. SQL text is retained without truncation; values are inline, so separate bindings are correctly marked not applicable. |
| Successful promotional grant | [05 · cb-app → GO → Ledger](http://127.0.0.1:7781/#snapshot=7d41f475-8a78-4f6f-b05a-415bc456f390) | 9 linked segments across cb-app, GO, Ledger and EMS, including both custom-pricing-unit callbacks. All 169 boundary records (167 SQL executions) are retained in this graph. All recorded HTTP outcomes are 200; the test account's final balance was verified as 10 credits. |
| Verify the resulting balance | [06 · Ledger balance](http://127.0.0.1:7781/#snapshot=f6b7a190-f65e-44e9-907b-d530257aba24) | A read-back confirms the 10-credit balance. All 12 retained Ledger SQL executions include captured bound values. |
| Optional dependency failure | [07 · Plans authentication failure](http://127.0.0.1:7781/#snapshot=ad61bd39-64e3-4d59-bc76-5c55af1fe4f1) | An outgoing dependency can be useful evidence even when its internal execution is unavailable. Do not present this as a working Plans business flow. |
| Performance investigation | [08 · SQL-heavy subscriptions](http://127.0.0.1:7781/#snapshot=82fcd4d8-7ddd-4a74-92e2-a91eab60ba47) | 70 retained SQL executions. Select this saved capture in Performance, run Analyze and inspect slow/repeated-query and suspected N+1 findings using the displayed thresholds. |
| Hidden downstream failure | [09 · Downstream failure](http://127.0.0.1:7781/#snapshot=c08dead9-65a5-4ec0-bf80-d27a64467617) | The outer cb-app response was HTTP 200 while a nested GO operation failed. Explain why the request graph matters beyond the top-level status. This is not a successful grant example. |

## Interaction checklist

1. Select a service card, then open **Request data** in **Selected evidence**.
   Show captured API query/form/JSON values and **Copy** without exposing
   credentials on a projected screen. Cards stay fixed as inspector details open;
   demonstrate **Focus selected**, **Focus branch**, the minimap and Escape.
2. Select a **DB CALL** group. In the inspector, use the operation/table preview
   to choose a query, inspect SQL and typed bindings, then copy values. Reconstructed
   SQL is a readable debugging aid; the original template and bindings remain
   separate evidence.
   In the grant example, GO has 17 SQL executions (16 with bound values), and
   Ledger has 47 (39 with bound values). A statement with inline values or no
   placeholders need not have a separate binding list.
   For GO's `grant_transactions` batch insert, expand **Captured parameters** to
   show the recorded values. Its `setObject` runtime types were not retained, so
   the reconstructed SQL conservatively keeps `?` rather than guessing literal
   types; this does not mean the parameter values are absent.
3. Use **View caller in …** to inspect the upstream code that initiated a call.
   Use **Open code flow** to inspect the destination service's captured execution.
   If a caller location was not recorded, show that limitation instead of guessing.
4. Switch between **Code flow**, **Call tree** and **Timeline / source**. Show
   **Application logic → All recorded calls** to reveal folded wrapper calls.
   Search for a method, focus a branch, and use the sticky controls. In Timeline,
   wrap a long row, inspect/copy its retained event and close with Escape. Saved
   source lines are the preserved evidence; editor links open the current checkout.
5. In **Saved captures**, select the two cache examples (Before first), then
   **Compare selected → Compare saved snapshots**. Review paired outcomes/calls
   and parent-grouped method differences; open saved evidence and use **Back to
   comparison**. A cache boundary alone does not prove a hit;
   these labels are supported by the recorded application branches. The lower
   retained SQL count is observed in these samples, not a general performance
   guarantee. `Redis / Valkey` describes a compatible client/protocol when the
   server implementation was not independently identified.
6. In **Performance**, choose a saved capture and click **Analyze**. Focus a
   **Category**, select a **Captured occurrence** and open that exact query's
   evidence. Suspected N+1 means a pattern worth checking,
   not a confirmed defect. This currently analyzes PostgreSQL/MySQL JDBC records,
   not native ClickHouse queries or fleet-wide traffic.
7. Return to **Saved captures** to show stable evidence and saved/pair search.
   For live requests, keyword filtering searches only the captured request fields
   that were actually retained; it is not a search over response bodies or secrets
   that were never captured.

These nine recordings are legacy version-1 snapshots. Use them as-is; a viewer
upgrade does not convert them or restore data omitted at capture time. Demonstrate
**Download capture archive / Import capture archive** with a separate version-2
capture if needed, not by claiming the original demo files have that format.

## Keep the claims accurate

- Queue `SUBMITTED` proves the observed local publish boundary, not downstream
  consumer execution, delivery or exactly-once processing.
- Arrow numbers express supported observed order. `1.1` / `1.2` require positive
  overlap evidence; `?` means ordering could not be established. Labels are not a
  global synchronized timeline across service clocks.
- A known service name on an external card identifies the destination; it does
  not invent a missing receiver segment. Its API must be in that JVM's capture
  scope, propagation must be allowed and the retained evidence must be readable.
- SQL, parameters and request bodies are opt-in and bounded. Their presence in a
  fresh local sample does not imply they were captured in every earlier snapshot.
- Saved examples are selected local observations. They do not establish complete
  code coverage, production overhead, continuous monitoring or safe-change proof.
- Review raw request/query values before exporting captures or sharing a screen.
  Copying retained data does not sanitize it.

For developer installation use [guided setup](guided-setup.md). For the longer-term
shared-dev/sandbox impact map and bounded high-traffic analysis see the
[roadmap](roadmap.md), not the current demo claims.
