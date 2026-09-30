# Task 03 Final Audit

Date: 2026-09-09  
Branch: `feature_2.0`  
Revision observed: `fdddd26`  
Project: `data-collection-service`

## 1. Task 03 Goal

Task 03 validates desktop page-state and failure UX after the reliability/performance foundations from Task 01 and Task 02. The final acceptance standard is not visual uniformity and not P2=0. Task 03 is complete when there are no known P0/P1 page-state correctness or field-usage blockers:

- wrong-context data commit: none known;
- wrong-device write/action attribution: none known;
- same-context important refresh failure destroying last-good: none known;
- critical initial failure masquerading as success-empty: none known;
- stale request clearing current loading: none known;
- frontend write cancellation/retry of backend side effects: none known.

## 2. Task Matrix

| Task | Goal | Status | Evidence |
| ---- | ---- | ------ | -------- |
| 03.1 | Page-state/failure UX inventory | PASS | Baseline audit in `PAGE-STATE-FAILURE-UX-BASELINE.md`. |
| 03.2 | Shadow ownership and last-good | PASS | `shadow-request-state` targeted regression and final source audit confirm read/write target ownership. |
| 03.3 | History/Alarm/Log context-aware last-good | PASS | `context-last-good`, history/alarm/log lifecycle regressions pass. |
| 03.4 | Operational/action failure UX | PASS | Control, preview, cloud, diagnostic, network, edge targeted regressions pass. |
| 03.5 | Final regression and audit | PASS | Targeted 18-file regression, full frontend verification, source audit, secret scan, diff check, and real backend smoke pass. |

## 3. Final Page State Matrix

| Page | Initial Loading | Ready | Empty | Refreshing | Stale | Degraded | Error | Action Pending | Final |
| ---- | --------------- | ----- | ----- | ---------- | ----- | -------- | ----- | -------------- | ----- |
| Login | PASS | PASS | N/A | PASS | N/A | N/A | PASS | PASS | PASS |
| Dashboard | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| Device List | PASS | PASS | PASS | PASS | PASS WITH P2 | N/A | PASS | PASS WITH P2 | PASS WITH P2 |
| Device Workbench | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS WITH P2 | PASS WITH P2 |
| Collection | PASS | PASS | PASS | PASS | PASS WITH P2 | PASS | PASS | PASS | PASS WITH P2 |
| Control | PASS | PASS | N/A | N/A | N/A | N/A | PASS | PASS | PASS |
| Realtime | PASS WITH P2 | PASS | PASS WITH P2 | PASS | PASS | PASS | PASS | PASS | PASS WITH P2 |
| History | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| Alarm | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| Cloud | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| Diagnostic | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| Log | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| Network | PASS | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS |
| Shadow | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |

## 4. Findings Matrix

| Severity | Open | Closed | Deferred |
| -------- | ---: | -----: | -------: |
| P0 | 0 | 1 | 0 |
| P1 | 0 | 5 | 0 |
| P2 | 0 | 5 | 5 |

P0/P1 open count is `0`; Task 03 meets the completion gate. P2 items remain documented because they are not correctness blockers and do not require page-state architecture changes.

## 5. 03.1 Findings

Task 03.1 established the routed-page baseline and identified the main risk classes: Shadow wrong-context ownership, investigation-page last-good loss, operational action attribution, preview/diagnostic/cloud ambiguity, and residual P2 polish.

## 6. 03.2 Shadow Closure

Final regression confirms:

- shadow read A→B, delta read A→B, and history read A→B are generation/context guarded;
- same-context refresh failure preserves last-good section data with visible stale state;
- `saveDesired` and `clearDesired` capture submitted `deviceId` and keep backend side effect authoritative;
- desired form resets on device change;
- stale request finalizers cannot clear current loading ownership.

Status: PASS.

## 7. 03.3 Investigation Closure

Final regression confirms:

- History same-context main failure retains coherent last-good main rows, compare rows, and related alarms; new context clears old investigation data; partial dependency failures remain DEGRADED, not ERROR.
- Alarm same-context history failure retains alarms and acknowledgement state; new query context does not reuse old alarms; acknowledgement degradation is independent.
- Log same `LogServerQueryContext` failure retains logs; new server context clears logs; device/thread visible-only filters do not invalidate server last-good; timer overlap remains skipped.

Status: PASS.

## 8. 03.4 Operational/Action Closure

Final regression confirms:

- Control single/batch/command capture immutable submission target/payload and show result/error/pending attribution.
- Device Workbench realtime preview differentiates unavailable/stale from true empty and blocks A→B stale commits.
- Cloud classifier uses only existing contract evidence for DISABLED/READY/DEGRADED/UNAVAILABLE.
- Diagnostic running-status failure is unavailable, not stopped; export success/failure and optional source degradation are visible.
- Network and EdgeTelemetry results identify submitted target after form changes.

Status: PASS.

## 9. Silent Catch Audit

| Location | Classification | Blocking |
| -------- | -------------- | -------- |
| `features/collection/components/ConfigOpsPanel.vue:100` | intentional optional initial sync-status hint | no, P2 deferred |
| router navigation `.catch(() => undefined)` calls | intentional duplicate/aborted navigation suppression | no |
| `views/alarm/AlarmView.vue` navigation helpers | intentional troubleshooting navigation suppression | no |
| Edge raw preview parse catch | intentional best-effort preview while input is incomplete | no |
| Diagnostic optional alarm/log sample catches | handled degraded package warning | no |

No problematic silent catch remains for a critical read/write path.

## 10. Empty/Error Audit

Final static audit classifies destructive clears as either success-empty, initial/new-context clear, or optional-data clear. Problematic clear-on-refresh count: `0`.

Representative closures:

- History same-context failure no longer clears current investigation snapshot.
- Alarm same-context failure no longer clears alarms or ack state.
- Log same-server-context failure no longer clears logs.
- Device preview same-device failure no longer clears last-good rows.
- Realtime periodic full/delta failure retains rows and writes visible error.

## 11. Realtime 100k Decision

Known Task 02.5 result: 100k compact full median was approximately 17 seconds. Final audit outcome:

- initial full: PASS WITH P2 because loading exists, but copy is still relatively weak for a long initial full;
- periodic full: PASS because last-good rows remain visible during refresh/resync;
- failure: PASS because rows are retained and `realtimeError` is visible;
- severity: P2 DEFERRED, not P1, because no data-loss or wrong-context blocker is present and Task 03 did not modify realtime architecture.

No changes were made to poll interval, delta cadence, cursor, compact endpoints, page size, or max render rows.

## 12. Deferred P2

### Realtime 100k initial-full wording

Issue: first 100k full load can take about 17 seconds and copy is still generic.  
Current behavior: table stays in loading state, but empty text is not context-specific.  
Why not blocking: periodic full/failure retain rows; no wrong-context/data-loss issue.  
Why deferred: fixing is UI copy/polish, not core correctness.  
Recommended future task: Task 05 or a later UX polish pass.

### Device store global operating

Issue: `deviceStore.operating` remains a conservative global lock.  
Current behavior: prevents concurrent operations broadly.  
Why not blocking: avoids duplicate unsafe writes and does not misattribute success.  
Why deferred: per-device operation scheduling is a feature/UX enhancement.  
Recommended future task: Device Workbench enhancement.

### Control cross-action concurrency

Issue: different Control action types have independent pending flags and can theoretically overlap.  
Current behavior: every result/error has immutable target attribution.  
Why not blocking: no same write is duplicated automatically; backend writes are not retried/cancelled.  
Why deferred: a global action queue would exceed Task 03 scope.  
Recommended future task: Device Workbench enhancement.

### ConfigOpsPanel optional initial sync hint

Issue: initial sync-status hint uses best-effort `.catch(() => undefined)`.  
Current behavior: explicit user-triggered sync/import/export failures are handled; only initial background hint is hidden.  
Why not blocking: optional background state, not critical page data.  
Why deferred: changing it would be polish outside final acceptance.  
Recommended future task: Task 05 observability or Device Workbench enhancement.

### Cloud future richer backend status

Issue: current UI can only classify disabled/degraded from existing fields.  
Current behavior: does not invent disabled when no `enabled=false` or explicit disabled status/state exists.  
Why not blocking: unsupported semantics are represented conservatively as unknown/unavailable/degraded, not false success.  
Why deferred: richer disabled/degraded detail needs backend contract design.  
Recommended future task: Task 05 observability.

## 13. Verification Evidence

Targeted Task 03/Task 01/Task 02 regression:

```text
npm --prefix collector-desktop test -- src/features/shadow/utils/shadow-request-state.test.ts src/features/request/utils/context-last-good.test.ts src/features/history/utils/history-request-lifecycle.test.ts src/features/alarm/utils/alarm-request-lifecycle.test.ts src/features/log/utils/log-request-lifecycle.test.ts src/features/action/utils/action-result-context.test.ts src/features/control/utils/control-utils.test.ts src/features/device/utils/device-request-lifecycle.test.ts src/features/cloud/utils/cloud-report-utils.test.ts src/features/diagnostic/utils/diagnostic-utils.test.ts src/features/diagnostic/utils/device-runtime-utils.test.ts src/features/network/utils/network-utils.test.ts src/features/network/utils/edge-telemetry-utils.test.ts src/features/request/utils/latest-request-owner.test.ts src/features/realtime/utils/realtime-request-lifecycle.test.ts src/features/realtime/utils/realtime-load-strategy.test.ts src/features/realtime/utils/realtime-table-window.test.ts src/features/realtime/utils/realtime-delta.test.ts
```

Result: `18 passed (18)` test files, `166 passed (166)` tests.

Full frontend verification:

```text
npm --prefix collector-desktop run typecheck && npm --prefix collector-desktop test && npm --prefix collector-desktop run build && npm --prefix collector-desktop run build:web && npm --prefix collector-desktop run verify
```

Result: PASS. Full frontend test suite reported `70 passed (70)` test files and `504 passed (504)` tests; `build:web` synced `57` desktop web-console files.

Real backend smoke:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-console-real-smoke.ps1 -Port 19091
```

Result: `REAL BACKEND SMOKE PASSED`; dashboard TDengine-dependent alarm/storage probes were DEGRADED as expected and non-blocking under the smoke rules.

Source/diff/security gates:

```text
node scripts/audit-source-language.mjs
node scripts/scan-config-secrets.mjs
git diff --check
```

Result: source audit `{ ok: true, count: 0 }`; production config secret scan passed; diff check passed.

## 14. Final Decision

Task 03.5: PASS / COMPLETE  
Task 03 — Page State & Failure UX: PASS / COMPLETE  
Open P0: 0  
Open P1: 0

Next recommended work: `Task 05.1 — Observability Baseline & Gap Audit`.
