# Task 02 Final Acceptance — Realtime & Large Data Performance

Date: 2026-09-08
Revision: `402ee1c`
Branch: `feature_2.0`

Scope: Task 02.1 through Task 02.5 realtime large-data readiness only. This document does not certify the entire collection client as production ready.

## Task matrix

| Task | Goal | Result | Evidence | Residual Risk | Blocking |
| ---- | ---- | ------ | -------- | ------------- | -------- |
| Task 02.1 | Remove all-device polling N+1 from realtime transport | PASS / COMPLETE | Aggregate realtime endpoint and regression history in `REALTIME-SCALE-BASELINE.md`; full frontend verify rerun PASS | Raw rich payload remains large by design | No |
| Task 02.2 | Bound browser render rows | PASS / COMPLETE | `realtime-table-window.test.ts` and `npm --prefix collector-desktop run verify` PASS; max page remains 500 | Real Chrome DOM profiling is not automated | No |
| Task 02.3 | Add compact realtime RAW DTO | PASS / COMPLETE | Compact contract benchmark retained; real HTTP compact full measured at 10k/50k/100k | Full compact remains full snapshot polling | No |
| Task 02.4 | Add change-aware compact delta under 5s polling | PASS / COMPLETE | `RealtimeScaleSoakIT` delta matrix PASS; frontend delta tests PASS | Backend changed-key selection still scans tracker O(P) | No |
| Task 02.5 | Large-scale realtime regression and 15-minute soak on current executable JAR | PASS / COMPLETE | `scripts/run-realtime-scale-soak.ps1` PASS, output `target/realtime-scale-soak/20260908-144231`; backend/frontend gates PASS | Full 100k HTTP takes ~17s on this machine; not a blocker because full has no hard ms SLA | No |

## Real JAR scale results

Command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-realtime-scale-soak.ps1 -JarPath collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar -Points 100000 -Devices 100 -DurationSeconds 900 -Clients 3 -PollIntervalSeconds 5 -Port 19091 -Token ops-token
```

Output: `REALTIME SCALE SOAK PASSED`.

| Points | Devices | Real Full HTTP Size | Full Median | Full Max | Result |
| -----: | ------: | ------------------: | ----------: | -------: | ------ |
| 10k | 10 | 2,480,934 bytes / 2.366 MiB | 1,625.67 ms | 1,655.83 ms | PASS |
| 50k | 50 | 12,403,934 bytes / 11.8293 MiB | 7,954.12 ms | 8,381.27 ms | PASS |
| 100k | 100 | 24,807,687 bytes / 23.6585 MiB | 16,995.79 ms | 17,569.12 ms | PASS |

Contract verified per stage: RAW DTO, `status=success`, nonblank `snapshotId`, `configEpoch >= 1`, `revision >= 0`, `rows[]`, `devices[]`, no `ApiResult.code/data` envelope. Device status at 100k: 100 devices, each `status=success`, `dataCount=1000`. Sampled first/middle/last compact rows retained required fields and omitted rich/adaptive fields.

## Delta scale results

Source: explicit backend harness `RealtimeScaleSoakIT` using real `RealtimeChangeTracker` and real `RealtimeDataQueryApplicationService` with deterministic config/cache doubles.

| Change | Rows | Mode | Cache Keys | Response Size | Median | Result |
| -----: | ---: | ---- | ---------: | ------------: | -----: | ------ |
| 0% | 0 | delta | 0 | 198 bytes | 4 ms | PASS |
| 1% | 1,000 | delta | 1,000 | 262,200 bytes | 5 ms | PASS |
| 10% | 10,000 | delta | 10,000 | 2,620,201 bytes | 22 ms | PASS |
| 20% | 20,000 | delta | 20,000 | 5,240,201 bytes | 20 ms | PASS |
| 20,001 | 20,001 | full reset | 0 | 25,896,771 bytes full reference | 7 ms | PASS |
| 50% | 50,000 | full reset | 0 | 25,896,771 bytes full reference | 11 ms | PASS |
| 100% | 100,000 | full reset | 0 | 25,896,771 bytes full reference | 21 ms | PASS |

Engineering gate: 0%/1%/10% median all stayed below 5,000 ms polling interval. 20,001/50%/100% correctly used `DELTA_TOO_LARGE` full reset protocol and did not build oversized delta payloads.

## Soak results

| Item | Result |
| ---- | ------ |
| Duration | 900 seconds configured |
| Clients | 3 independent local cursors |
| Poll cycles | 91 |
| Full requests | 37 |
| Delta requests | 255 |
| Client full/delta sequence | initial full per client, then periodic full after 12 successful deltas |
| Total requests | 696 including create/delete/cleanup probes |
| Non-cleanup HTTP 2xx | 396 |
| Non-cleanup HTTP 4xx | 0 |
| HTTP 5xx | 0 |
| JSON parse failures | 0 |
| Unexpected resets | 0 |
| Expected resets | 3 |
| Process crashes | 0 |
| Delta response bytes | 209–240 bytes during empty delta soak |
| Delta duration | median 21.04 ms, max 163.89 ms |

The aggregate `http4xx=199` in `summary.json` came from safe cleanup DELETE probes against absent fixed-prefix devices. They were outside the normal realtime soak path and were treated by the script as cleanup-safe statuses. Non-cleanup HTTP 4xx was 0.

## Resource trend

| Resource | Start | Peak | End | Conclusion |
| -------- | ----: | ---: | --: | ---------- |
| JVM heap used | 148,656,136 bytes | 411,313,664 bytes | 181,853,144 bytes | no obvious unbounded growth observed |
| JVM non-heap used | 113,157,928 bytes | 119,735,584 bytes | 119,735,584 bytes | stable after warmup |
| GC count/time | 1 / 0.006s start | 46 / 0.242s before restart | restart snapshot ended separately | no GC thrashing observed |
| Live threads | 41 | 74 | 42 | no persistent thread growth observed |
| CPU | sampled from `/actuator/prometheus` | low after request bursts | low after restart | no sustained CPU runaway observed |

Memory statement is intentionally conservative: the run supports “no obvious unbounded growth observed”, not “no memory leak”.

## Recovery

| Scenario | Evidence | Result |
| -------- | -------- | ------ |
| Config update | Changed `pointName` for fixed-prefix device; old cursor delta returned `CONFIG_CHANGED` | PASS |
| Full resync | Full compact after config update saw new `pointName` | PASS |
| Device delete | Deleted `realtime-scale-soak-100`; old cursor delta returned `CONFIG_CHANGED` | PASS |
| Full after delete | `deviceCount=99`, `dataCount=99000`, deleted device rows absent | PASS |
| Server restart | JVM stopped and restarted by script; old snapshot delta returned `SNAPSHOT_MISMATCH` | PASS |
| Full after restart | New full returned a different `snapshotId` | PASS |

## Frontend regression

| Area | Evidence | Result |
| ---- | -------- | ------ |
| 100k row index | `realtime-table-window.test.ts`, `realtime-delta.test.ts` | PASS |
| Delta merge | 1,000 / 10,000 / 20,000 changed-row merge coverage | PASS |
| Render bound | default <= 200, max <= 500 retained | PASS |
| Empty delta | rows array/reference preservation covered | PASS |
| Unknown row | full resync instead of push covered | PASS |
| Lifecycle | stale/reset/full race and request-owner coverage in verify suite | PASS |
| Benchmark | `realtime-scale.bench.ts` rerun at current HEAD | PASS |

## Verification commands

| Gate | Command | Result |
| ---- | ------- | ------ |
| Frontend typecheck | `npm --prefix collector-desktop run typecheck` | PASS |
| Frontend tests | `npm --prefix collector-desktop test` | PASS, 67 files / 472 tests |
| Frontend build | `npm --prefix collector-desktop run build` | PASS |
| Frontend build:web | `npm --prefix collector-desktop run build:web` | PASS |
| Frontend verify | `npm --prefix collector-desktop run verify` | PASS, 67 files / 472 tests plus lint/stylelint/typecheck/build/build:web |
| Backend relevant scale harness | `cmd.exe /c mvn -B -ntp -DforkCount=0 -pl collector-application -am -Dtest=RealtimeScaleSoakIT -Dsurefire.failIfNoSpecifiedTests=false test` | PASS |
| Backend nofork verify | `cmd.exe /c mvn -B -ntp -DforkCount=0 verify` | PASS |
| P0 regression | `cmd.exe /c mvn -B -ntp -DforkCount=0 -Pp0-regression test` | PASS |
| Current executable JAR package | Maven verify/package produced `collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar` | PASS |
| Original real backend smoke | `scripts/run-console-real-smoke.ps1` on port 19090 | PASS |
| New realtime scale soak | `scripts/run-realtime-scale-soak.ps1` on port 19091 | PASS |
| Benchmark | `npm --prefix collector-desktop exec vitest bench src/features/realtime/utils/realtime-scale.bench.ts -- --run --outputJson ../target/realtime-scale-bench-02-5.json` | PASS |
| Source language audit | Changed source/script files checked for mojibake | PASS |
| Secret scan | Changed script/docs checked; only fixed default token string `ops-token` present | PASS |
| Diff check | `git diff --check` | PASS |

Note: an attempted `mvn clean verify` while the first long soak was still running failed because Maven clean could not delete the active `target/realtime-scale-soak/.../backend-stdout.log`. This was an execution-order issue, not a code failure; the final backend nofork verify completed successfully after the soak.

## Findings

### BLOCKER

- None.

### DEFERRED

- Backend tracker selection remains O(P); cache read and payload build remain O(C) for small changes.
- 50% / 100% changes intentionally fall back to full compact via `DELTA_TOO_LARGE`.
- HTTP polling remains; no WebSocket/SSE/STOMP/Socket.IO work was started.
- Real browser DOM profiling is not automated in Task 02.5.
- Full Redis/TDengine/MQTT/PLC production SLA is covered by the separate `scripts/soak/**` chain, not by this realtime-specific acceptance.

### INFORMATIONAL

- Full 100k compact HTTP response in the real JAR path measured 23.6585 MiB, lower than the synthetic compact fixture baseline because the real fixture intentionally avoids metadata/additionalConfig/remark inflation.
- Full 100k requests are slow on this workstation (~17s median), but completed without timeout, OOM, 5xx, crash, or cursor corruption.

## Repairs during 02.5

- Added realtime-specific PowerShell soak harness.
- Added explicit backend scale harness.
- Added frontend 100k delta merge regression coverage.
- Minimal script repair: wrapped PowerShell `Where-Object` results in arrays for scalar-safe `.Count` handling during recovery checks.

No new runtime architecture, protocol, cache framework, dependency, polling interval, or `MAX_DELTA_ROWS` change was introduced.

## Final decision

| Gate | Decision |
| ---- | -------- |
| Task 02.5 | PASS / COMPLETE |
| Task 02 — Realtime & Large Data Performance | PASS / COMPLETE |
| Realtime large-data path | READY FOR FIELD TRIAL |

## Next

Task 03 — Page State & Failure UX
