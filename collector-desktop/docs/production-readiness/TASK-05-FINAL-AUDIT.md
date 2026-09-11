# Task 05.5 — Observability Regression & Final Audit

Date: 2026-09-11
Branch: `feature_2.0`
Revision: `ea724d9`

## 1. Scope

Task 05.5 is a final audit and regression gate for Task 05 observability work. No P0/P1 blocker was found, so this pass did not change production Java, Vue, TypeScript, API contracts, or dependencies. Documentation was added/updated only.

| Area | Result |
| --- | --- |
| Production Java diff in this pass | 0 |
| Production frontend diff in this pass | 0 |
| API diff in this pass | 0 |
| Dependency diff in this pass | 0 |
| Detailed audit artifact | `collector-desktop/docs/production-readiness/TASK-05-FINAL-AUDIT.md` |

## 2. Task Matrix

| Task | Purpose | Status |
| --- | --- | --- |
| 05.1 | Baseline & gap audit | PASS |
| 05.2 | Correlation/access/file logs | PASS |
| 05.3 | Pipeline metrics/health/security | PASS |
| 05.3-R1 | Cloud snapshot read boundary | PASS |
| 05.4 | Operational diagnostic surface | PASS |
| 05.4-R1 | Desktop pipeline contract | PASS |
| 05.5 | Final audit | PASS |

## 3. Final Severity Summary

| Severity | Open | Closed | Deferred |
| --- | ---: | ---: | ---: |
| P0 | 0 | 0 | 0 |
| P1 | 0 | 5 | 0 |
| P2 | 0 | 3 | 3 |

Task 05 completion decision is based on static contract checks, runtime verification, security/auth checks, bounded-memory review, and incident walkthroughs, not test counts alone.

## 4. Final Finding Matrix

| Finding | Original Severity | Status | Evidence |
| --- | --- | --- | --- |
| OBS-P1-01 | P1 | CLOSED | Request correlation chain verified by focused tests and runtime `X-Request-Id` checks. |
| OBS-P1-02 | P1 | CLOSED | Access log verified for normal and denied protected requests, with request ID and redacted query values. |
| OBS-P1-03 | P1 | CLOSED | Rolling file logging verified with executable JAR and bounded rolling policy. |
| OBS-P1-04 | P1 | CLOSED | Runtime auth matrix confirms health public, metrics/prometheus protected without token, VIEW allowed. |
| OBS-P1-05 | P1 | CLOSED | `/monitor/pipeline`, Prometheus `collector_pipeline_*`, executor/stage contract, and smoke passed. |
| OBS-P2-01 | P2 | CLOSED | Liveness/readiness/business health boundaries verified; disabled optional integrations do not break readiness. |
| OBS-P2-02 | P2 | CLOSED | ExceptionMonitor recent/device/category boundedness, overflow, sanitizer, requestId, context bounds verified. |
| OBS-P2-03 | P2 | CLOSED | Human `/monitor/**`/Desktop diagnostics and machine Prometheus contracts remain separated with low-cardinality metrics. |

No new P0/P1 findings were opened in Task 05.5.

## 5. Request Correlation

| Check | Result |
| --- | --- |
| Incoming request ID | Runtime `X-Request-Id: obs-final-001` echoed as response `X-Request-Id=obs-final-001`. |
| Generated request ID | Runtime request without header returned non-blank `X-Request-Id`. |
| Validation | `RequestCorrelationFilterTest` passed; unsafe/control/over-length IDs remain covered. |
| Request attribute | Covered by request correlation focused regression. |
| MDC visible in chain | Covered by focused tests and log output with `[req:...]`. |
| Cleanup | Tests cover normal and exception-path cleanup/restore, preventing thread pollution. |
| Boundary | Only synchronous HTTP request correlation is supported; distributed tracing and generic async MDC propagation are not claimed. |

Background collection, protocol subscription, history replay, and cloud retry remain diagnosed by device/point/protocol/pipeline stage context rather than HTTP request ID. This is expected and not a P1 blocker.

## 6. Access Logging

| Check | Result |
| --- | --- |
| Context path normalization | `/collector/api/...` is logged/matched as `/api/...`; existing regression passed. |
| Normal access log | Runtime VIEW `/monitor/runtime` returned 200 and access logging remained active. |
| High-risk denied request | Runtime unauthenticated `POST /api/config/import?...` returned 401, no controller side effect executed. |
| Denied request correlation | Runtime denied responses included non-blank `X-Request-Id`. |
| Query redaction | Runtime file log contained `token=***` and `password=***`; sentinel values were absent from file log. |
| Header redaction | `LogFilterTest` passed for sensitive headers including authorization/token/cookie/signature/credential/api-key cases. |
| Body | Access log records request/response byte counts and does not log request body contents. |
| Prometheus access storm | `/actuator/**` remains access-log excluded; 20 authenticated scrapes returned 200 without generating a client access storm. |

## 7. File / Operation Logs

| Check | Result |
| --- | --- |
| STDOUT | Smoke scripts start executable JAR and report PASS through console output. |
| Rolling file | Runtime JAR with temporary `--logging.file.name=<temp>/collector.log` created a non-empty file log. |
| File contents | Runtime file contained startup/runtime events. |
| Bound | `application.yml` rolling policy: `max-size=10MB`, `max-history=30`, `total-size-cap=300MB`. |
| OperationLogger bound | `MAX_ENTRY_COUNT=2000`, `MAX_MESSAGE_LENGTH=4000`, query limit bounded to `MAX_QUERY_LIMIT=1000`. |
| OperationLogger requestId | Runtime `/api/ops/logs?keyword=obs-final-high-risk` found the denied request log entry. |
| Memory bound | OperationLogger retains only bounded recent entries. |

Electron packaged writable log directory remains P2 deferred to Task 06; Java standalone/server file logging is closed for Task 05.

## 8. Actuator Security and Health

### Runtime auth matrix

| Endpoint | Anonymous | VIEW |
| --- | ---: | ---: |
| `/health` | 200 | 200 |
| `/actuator/health` | 200 | 200 |
| `/actuator/health/liveness` | 200 | 200 |
| `/actuator/health/readiness` | 200 | 200 |
| `/actuator/metrics` | 401 | 200 |
| `/actuator/metrics/jvm.memory.used` | 401 | 200 |
| `/actuator/prometheus` | 401 | 200 |
| `/monitor/runtime` | 401 | 200 |
| `/monitor/system` | 401 | 200 |
| `/monitor/errors` | 401 | 200 |
| `/monitor/pipeline` | 401 | 200 |
| `/monitor/report` | 401 | 200 |
| `/monitor/storage` | 401 | 200 |
| `/api/ops/logs` | 401 | 200 |

### Health contract

| Endpoint | Meaning | Final status |
| --- | --- | --- |
| `/actuator/health/liveness` | process/application liveness | PASS |
| `/actuator/health/readiness` | application readiness | PASS |
| `/actuator/health` | Actuator aggregate | PASS |
| `/health` | business/console health | PASS |
| `/monitor/**` | operator diagnostic | PASS |

Runtime isolated environment with TDengine/Cloud/Redis stream disabled returned liveness/readiness 200; pipeline stages represented disabled dependencies as `DISABLED`, not P1 failures.

## 9. Pipeline Contract

| Area | Final status |
| --- | --- |
| Endpoint | Runtime `/monitor/pipeline` VIEW returned 200. |
| Top-level keys | `status`, `generatedAt`, `ingress`, `stream`, `history`, `cloud`, `executors`, `risks`. |
| Status vocabulary | `HEALTHY`, `WARNING`, `DANGER`, `UNKNOWN`, `DISABLED`. |
| Ingress | Runtime contract includes Redis pending/processing/dead-letter and local pending/capacity/utilization. |
| Stream | Runtime contract includes buffer size/capacity/utilization and admission/drop/failure counters. |
| History | Runtime contract includes Redis backlog/dead-letter, local queue, replay, dropped/rejected, live flush utilization. |
| Cloud | Runtime contract includes enabled/status/pending/isolated/oldest age. |
| Executors | Runtime exposed five logical executors: `cache`, `stream`, `streamWriter`, `history`, `report`. |
| Risks | Existing risk list translation and first-8 display logic retained. |
| Partial source failure | Unit tests cover stage `UNKNOWN` with endpoint still 200 and other stages retained. |

Task 05.4-R1 reconfirmed Desktop TypeScript DTOs mirror the Java DTO exactly and no generic `PipelineStageSnapshot` is used for pipeline stage mapping.

## 10. Pipeline Cost / Cardinality

| Check | Result |
| --- | --- |
| TTL | Pipeline snapshot cache remains 5 seconds. |
| Raw source calls | Unit tests cover refresh source boundaries. |
| Cloud single-read boundary | 05.3-R1 tests confirm pipeline refresh uses `CloudOutboxService.snapshot()` at most once. |
| Executor observation | Pipeline uses `SystemResourceMonitorService.getThreadPools()`, not full `getResources()`. |
| `/monitor/system` Cloud read | Uses one coherent `CloudOutboxSnapshot`. |
| Gauge cost | Prometheus binder reads cached immutable pipeline snapshot. |
| Metric names | Runtime Prometheus contained all required `collector_pipeline_*` metrics. |
| Labels | Runtime pipeline metric lines had no `deviceId`, `pointId`, `requestId`, `messageId`, exception message, full URL, or dynamic logger labels. |
| Repeated scrape | 20 authenticated Prometheus scrapes returned 200. |

Required metric family names observed: `collector_pipeline_enabled`, `collector_pipeline_status`, `collector_pipeline_queue_size`, `collector_pipeline_queue_capacity`, `collector_pipeline_queue_utilization`, `collector_pipeline_backlog`, `collector_pipeline_rejected`, `collector_pipeline_dropped`, `collector_pipeline_failures`, `collector_pipeline_oldest_age`.

## 11. Exception Monitor

| Check | Result |
| --- | --- |
| Recent capacity | `MAX_RECENT=100`; focused tests passed. |
| Device capacity | `MAX_DEVICE_COUNTER_KEYS=4096`; focused tests passed. |
| Category capacity | `MAX_CATEGORY_COUNTER_KEYS=64`; focused tests passed. |
| Overflow | `otherDeviceExceptions` and `otherCategoryExceptions` preserve overflow counts. |
| Concurrency | High-concurrency unique device ID test passed without exceeding hard bound. |
| Recent exception fields | `deviceId`, `pointId`, `category`, `exceptionType`, `message`, `requestId`, `timestamp`. |
| Message safety | Sensitive message values are sanitized before `/monitor/errors` / Desktop exposure. |
| Message bound | Exception message hard bound is 1024 chars. |
| Context bounds | `deviceId <= 256`, `pointId <= 256`, `requestId <= 128`. |
| Background behavior | Missing HTTP MDC yields empty requestId, not a synthetic request ID. |
| Stack trace | `/monitor/errors` does not expose Throwable, stackTrace, or cause chain. |
| Endpoint auth | Runtime `/monitor/errors` anonymous 401, VIEW 200. |
| Compatibility | Original fields plus additive boundedness metadata present at runtime. |

`ExceptionMonitorServiceTest`: 8 tests, 0 failures, 0 errors.

## 12. Desktop Diagnostic

| Check | Result |
| --- | --- |
| Pipeline contract | Frontend uses exact stage-specific pipeline interfaces. |
| Ingress mapping | Uses `localPending/localCapacity/localUtilization`, `redisPending`, `redisDeadLetter`. |
| Stream mapping | Uses `bufferSize/bufferCapacity/bufferUtilization`, admission rejected/dropped, Redis/writer failures. |
| History mapping | Uses `localPending/localCapacity/localUtilization`, Redis backlog/dead-letter, live flush utilization. |
| Cloud mapping | Uses `pending`, `isolated`, `oldestMessageAgeMillis`; no Cloud queue utilization is invented. |
| Unknown | `-1` values render as unknown/`-`, not `0` or `-100%`. |
| Exceptions | Shows Top Category, Top Devices, overflow, recent exception type/requestId/message. |
| RequestId navigation | Existing requestId action navigates to Log route keyword search. |
| Empty requestId | Background exceptions do not show meaningless log action. |
| Export | `diagnosticRaw` contains pipeline and sanitized exception data. |
| Partial failure | `Promise.allSettled`/partial warning/last-good pipeline behavior retained. |

Frontend targeted diagnostic/log tests: 24 tests passed.

## 13. Runtime Incident Walkthrough

| Incident | First signal | Drill-down | Result |
| --- | --- | --- | --- |
| HTTP denied request | 401 with `X-Request-Id` | access log + `/api/ops/logs?keyword=<requestId>` | PASS |
| JVM down | `/actuator/health/liveness` | stdout/file log | PASS |
| App not ready | `/actuator/health/readiness` | file log + runtime config | PASS |
| CPU high | `/monitor/system` | thread pool/system details + logs | PARTIAL |
| Heap high | Actuator JVM metric + `/monitor/system` | JVM metrics + logs | PARTIAL |
| Queue saturation | `/monitor/pipeline` + Prometheus | stage/executor detail | PASS |
| Redis issue | pipeline/cache UNKNOWN/degraded signals | Lettuce warnings + monitor | PASS |
| TDengine issue | storage/history disabled/degraded | `/monitor/storage` and history status | PARTIAL |
| Device disconnect | device/runtime + exceptions | deviceId + logs | PARTIAL |
| Point read error | ExceptionMonitor | deviceId/pointId/exceptionType/requestId when HTTP-correlated | PASS |
| Cloud backlog | pipeline cloud/report outbox | pending/isolated/oldest age | PASS |

## 14. ExceptionReporter Coverage Final Audit

| Area | Reporter status | Rationale |
| --- | --- | --- |
| Connection | Reported | `ConnectionManager` records connection failures. |
| Collector | Reported | `BaseCollector` records connect/read/write/subscribe/command failures. |
| Cache | Reported | `MultiLevelCacheManager` reports cache failure paths. |
| Ingress | Not duplicated | Dedicated queue/drop/dead-letter/pipeline metrics provide signal. |
| Stream | Not duplicated | Dedicated buffer/admission/writer/Redis failure counters provide signal. |
| History | Not duplicated | Dedicated history buffer/replay/drop/status metrics provide signal. |
| Cloud | Not duplicated | Outbox pending/isolated/oldest/report diagnostics provide signal. |
| Protocol | Covered through collectors | Protocol collectors report through base collector failure paths. |
| HTTP | Not blanket-reported | Request correlation/access logs/status/OperationLogger are the primary HTTP diagnostic path. |
| Monitoring itself | Not recursively reported | Avoids monitor -> reporter -> monitor recursion. |

No requirement exists for 100% exception reporter coverage when a path already has queue metrics, dead letter, rejected/dropped counters, pipeline status, or health indicators.

## 15. Memory / Cardinality Audit

| Structure | Bound | Final status |
| --- | ---: | --- |
| OperationLogger recent | 2000 | PASS |
| OperationLogger message | 4000 chars | PASS |
| OperationLogger query limit | 1000 | PASS |
| Exception recent | 100 | PASS |
| Exception byDevice | 4096 | PASS |
| Exception byCategory | 64 | PASS |
| Pipeline cached snapshot | 1 current snapshot | PASS |
| Micrometer pipeline tags | fixed `stage` / `type` cardinality | PASS |

Search/audit of Task 05 stateful structures did not identify a new P0/P1 unbounded service-lifetime map/list/cache introduced by observability work.

## 16. Regression Evidence

### Backend

| Command / group | Result |
| --- | --- |
| `cmd.exe /c "mvn -B -ntp -DforkCount=0 verify"` | PASS / BUILD SUCCESS |
| `cmd.exe /c "mvn -DskipTests package"` | PASS / BUILD SUCCESS |
| RequestCorrelationFilterTest | 5 tests passed |
| LogFilterTest | 7 tests passed |
| FilterOrderConfigurationTest | 1 test passed |
| AuthFilterTest | 12 tests passed |
| OperationLoggerTest | 3 tests passed |
| SystemResourceMonitorServiceTest | 8 tests passed |
| PipelineBackpressureMonitorServiceTest | 13 tests passed |
| PipelineMetricsBinderTest | 3 tests passed |
| ExceptionMonitorServiceTest | 8 tests passed |
| MonitorControllerTest | 3 tests passed |

### Frontend

| Command / group | Result |
| --- | --- |
| `cmd.exe /c "npm --prefix collector-desktop run typecheck"` | PASS |
| `cmd.exe /c "npm --prefix collector-desktop test"` | PASS — 70 files / 507 tests |
| `cmd.exe /c "npm --prefix collector-desktop test -- diagnostic-detail-utils diagnostic-utils log-request-lifecycle"` | PASS — 3 files / 24 tests |
| `cmd.exe /c "npm --prefix collector-desktop run build"` | PASS |
| `cmd.exe /c "npm --prefix collector-desktop run build:web"` | PASS — 57 web files synced |
| `cmd.exe /c "npm --prefix collector-desktop run verify"` | PASS |

### Runtime and smoke

| Check | Result |
| --- | --- |
| Current executable JAR | Repackaged successfully after latest build:web. |
| Runtime endpoint/auth matrix | PASS |
| Runtime pipeline contract | PASS |
| Runtime query redaction | PASS |
| Runtime OperationLogger requestId search | PASS |
| Runtime Prometheus repeated scrape | PASS — 20/20 returned 200 |
| Correlation smoke | PASS — `OBSERVABILITY CORRELATION SMOKE PASSED` |
| Pipeline smoke | PASS — `OBSERVABILITY PIPELINE SMOKE PASSED` |
| Real backend smoke | PASS — `REAL BACKEND SMOKE PASSED` |

## 17. Quality Gates

| Gate | Result |
| --- | --- |
| `node scripts/audit-source-language.mjs` | PASS — `ok=true`, `count=0` |
| `node scripts/scan-config-secrets.mjs` | PASS — production config secret scan passed |
| Changed/untracked file secret scan | PASS — no hits |
| `git diff --check` | PASS |
| Backend production diff for Task 05.5 | 0 |
| Frontend production diff for Task 05.5 | 0 |
| API/dependency diff for Task 05.5 | 0 |

## 18. Deferred P2

| Item | Status | Owner / phase |
| --- | --- | --- |
| Trusted proxy client IP | P2 DEFERRED | Task 06 Security |
| Generic async MDC propagation | P2 DEFERRED | Future requirement only if scoped |
| Electron final writable log directory | P2 DEFERRED | Task 06 Electron Delivery/Security |

These are not Task 05 failures because open P0 = 0 and open P1 = 0.

## 19. Final Decision

| Item | Decision |
| --- | --- |
| Task 05.5 | PASS / COMPLETE |
| Task 05 — Observability | PASS / COMPLETE |
| Open P0 | 0 |
| Open P1 | 0 |
| P2 | Deferred items documented with future owners |

Task 05 supports field-deployment observability without claiming production certification, zero monitoring risk, full distributed tracing, or universal incident diagnosis.

## 20. Next

Task 06.1 — Electron Delivery & Security Baseline Audit
