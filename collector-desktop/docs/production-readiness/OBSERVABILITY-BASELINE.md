# Task 05.1 — Observability Baseline & Gap Audit

Date: 2026-09-09; Task 05.2 update: 2026-09-10; Task 05.3 update: 2026-09-10
Branch: feature_2.0
Revision observed: ca98a0d; Task 05.2 verification revision observed: 63dd1fa; Task 05.3 verification revision observed: working tree on feature_2.0
Scope: 05.1 inventory + runtime verification + gap analysis + prioritization; 05.2 request correlation/access/file logging resolution notes; 05.3 actuator exposure hardening, probes, pipeline backpressure snapshot, and alert-grade Prometheus metrics.

## 1. Scope Decision

Task 05.1 production Java diff: 0
Task 05.1 production Vue/TS diff: 0
Task 05.2 backend production diff: request correlation filter/config, access logging, OperationLogger requestId, and Logback/file logging configuration.
Task 05.2 frontend production diff: 0
Task 05.3 backend production diff: actuator exposure/probe config, auth permit/access rules, `/monitor/pipeline`, pipeline snapshot cache, queue capacity/utilization, cloud outbox snapshot, low-cardinality `collector.pipeline.*` metrics.
Task 05.3 frontend production diff: 0
Dependency diff: 0

Task 05.1 only added this baseline document. Task 05.2 updated backend HTTP correlation/access/file logging and this document. Neither task introduces OpenTelemetry, Zipkin, Jaeger, Tempo, Loki, ELK, Sentry, SkyWalking, a new Prometheus client, or a new dashboard framework.

## 2. Current Architecture Map

```text
HTTP request
├── RequestCorrelationFilter
│   ├── resolve/preserve/generate X-Request-Id
│   ├── request attribute + response header
│   └── MDC requestId with scoped restore/remove cleanup
├── LogFilter
│   ├── configured include: /api/** after context-path normalization
│   ├── configured exclude: /health, /actuator/** after context-path normalization
│   ├── access log message: config_access ... requestId/method/path/query/status/duration/ip/principal/device/risk
│   └── dedicated logger: collector.access INFO/WARN, independent of com.wangbin.collector WARN
├── AuthFilter
│   ├── permitAll: /health, /actuator/health, /actuator/health/**, /desktop/**, swagger docs
│   ├── token/signature/IP authorization for /api/** and /monitor/**
│   └── Micrometer counter: collector.auth.requests(result,type)
├── Controller
│   ├── /health -> SystemHealthService
│   ├── /monitor/** -> monitor services + runtime application aggregator
│   ├── /api/ops/logs -> OperationLogger in-memory appender
│   └── other /api/** business controllers
├── Application
│   ├── ConsoleRuntimeStatusApplicationService aggregates cache/devices/system/errors/performance/report/storage
│   └── OpsConsoleApplicationService queries logs, alarm ack, network diagnose
├── Runtime / Collector
│   ├── CollectionScheduler and PerformanceStatsSnapshot
│   ├── CollectionManager / ConnectionManager
│   └── protocol collectors and connection adapters
├── Telemetry pipeline
│   ├── CacheTelemetryPostProcessStage
│   ├── StreamTelemetryPostProcessStage
│   ├── HistoryTelemetryPostProcessStage
│   └── ReportTelemetryPostProcessStage
├── Cache
│   ├── MultiLevelCacheManager
│   ├── local Caffeine
│   └── Redis cache
├── Redis Stream
│   ├── telemetry stream service
│   └── telemetry ingress buffer
├── History
│   ├── TDengine monitor
│   ├── HistoryWriteBuffer
│   └── HistoryBufferHealthIndicator
└── Cloud
    ├── ReportManager / handlers
    ├── CloudOutboxService
    ├── CloudReportMonitorService
    └── CloudOutboxHealthIndicator

Machine-facing:
├── /actuator/health
├── /actuator/metrics
└── /actuator/prometheus

Console/Human-facing:
├── /health
├── /monitor/**
└── /api/ops/logs
```

## 3. Dependency / Configuration Inventory

| Area | Evidence | Current State |
| --- | --- | --- |
| Actuator dependency | `collector-boot/pom.xml`, `collector-monitor/pom.xml` | `spring-boot-starter-actuator` present |
| Micrometer core | `collector-boot/pom.xml`, `collector-monitor/pom.xml` | `micrometer-core` present |
| Prometheus registry | `collector-monitor/pom.xml` | `micrometer-registry-prometheus` present |
| Actuator exposure | `collector-boot/src/main/resources/application.yml` | includes `health,info,metrics,prometheus`; base path `/actuator` |
| Health details | `application.yml` | `show-details: ${MANAGEMENT_HEALTH_DETAILS:never}` |
| Probes | runtime + config | `/actuator/health/liveness` and `/actuator/health/readiness` returned 404; no probes config found |
| File logging intent | `application.yml` | `logging.file.name: logs/collector.log`; max size/history/total-size-cap configured |
| Effective Logback | `logback-spring.xml` | STDOUT retained; bounded `RollingFileAppender` writes `${logging.file.name}` |
| Tracing dependencies | POM search | no OpenTelemetry / Zipkin / Jaeger / SkyWalking / Sentry / Loki dependency found |

## 4. Runtime Verification Setup

Current executable JAR was rebuilt with:

```text
D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd -B -ntp -DskipTests package
```

Result: BUILD SUCCESS, current boot jar regenerated.

Runtime command:

```text
java -jar collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar --server.port=19092 --collector.config.sync-enabled=false --collector.report.enabled=false --telemetry.tdengine.enabled=false
```

Runtime environment notes:
- context path: `/collector`
- TDengine disabled for isolated local audit
- cloud report disabled for isolated local audit
- Redis was reachable locally during audit, so cache/lettuce metrics were visible
- no external TDengine/MQTT installation was required for 05.1

## 5. Runtime Endpoint Matrix

| Endpoint | No Auth | Valid Auth | Result | Notes |
| --- | ---: | ---: | --- | --- |
| `/health` | 200 | 200 | JSON with `status`, `timestamp`, `components` | permit-all; custom health, not actuator |
| `/actuator` | 200 | 200 | links | permit-all |
| `/actuator/health` | 200 | 200 | `{status}` only | default health details are hidden |
| `/actuator/health/liveness` | 404 | 404 | absent | liveness probes not enabled/exposed |
| `/actuator/health/readiness` | 404 | 404 | absent | readiness probes not enabled/exposed |
| `/actuator/metrics` | 200 | 200 | 65 metric names | permit-all |
| `/actuator/prometheus` | 200 | 200 | Prometheus text when `Accept: text/plain` | permit-all; 78 HELP names observed |
| `/actuator/metrics/http.server.requests` | 200 | 200 | measurements + tags | tags include method/status/uri/outcome/exception/error |
| `/actuator/metrics/collector.auth.requests` | 200 after auth traffic | 200 | result/type tags | Prometheus name `collector_auth_requests_total` |
| `/monitor/runtime` | 401 | 200 | aggregate runtime snapshot | requires VIEW token |
| `/monitor/cache` | 401 | 200 | cache metrics snapshot | local + Redis detail visible |
| `/monitor/devices` | 401 | 200 | device connection snapshot | no configured devices in local runtime |
| `/monitor/performance` | 401 | 200 | collector metrics list | empty in no-device runtime |
| `/monitor/system` | 401 | 200 | JVM/system/thread-pool snapshot | requires VIEW token |
| `/monitor/errors` | 401 | 200 | exception stats snapshot | in-memory counters/recent |
| `/monitor/report` | 401 | 200 | cloud report metrics | disabled status visible |
| `/monitor/storage` | 401 | 200 | storage metrics | TDengine disabled visible |
| `/monitor/perf/detail` | 401 | 200 | scheduler performance detail | scheduler internals, not per-collector DTO list |
| `/api/ops/logs?limit=20` | 401 | 200 | in-memory log buffer | requires VIEW token |

## 6. Endpoint Inventory

| Endpoint | Source | Data | Cost | Auth | Failure Behavior | Useful For |
| --- | --- | --- | --- | --- | --- | --- |
| `/health` | `HealthController` + `SystemHealthService` | business health components | low | public | component can be DOWN/DEGRADED/UNKNOWN | user/console diagnosis |
| `/actuator/health` | Spring Actuator | machine health status only by default | low | public | actuator status only | liveness-ish today, but not readiness |
| `/actuator/metrics` | Actuator/Micrometer | metric name catalog | low | public | 404 for absent metric names | machine metric discovery |
| `/actuator/prometheus` | Prometheus registry | scrape text | medium | public | media-type sensitive if JSON accept is forced | Prometheus scrape |
| `/monitor/runtime` | `ConsoleRuntimeStatusApplicationService` | aggregate runtime components + raw snapshots | medium | token VIEW | component read failures become component ERROR | console overview |
| `/monitor/cache` | `CacheMonitorService` | reads/writes/deletes/misses/hit rates/cache health | low/medium | token VIEW | health fallback UNKNOWN on cache-health read exception | Redis/local cache diagnosis |
| `/monitor/devices` | `DeviceMonitorService` | connection counts, expected, healthy/warn/danger, missing, connection list | medium | token VIEW | snapshot only | device connection diagnosis |
| `/monitor/performance` | `PerformanceMonitorService` + `CollectionManager` | per-collector processed points, pps, success rate, latency, protocol metrics | medium | token VIEW | empty list if no collectors | collection throughput overview |
| `/monitor/system` | `SystemResourceMonitorService` | heap/non-heap/CPU/thread count/thread pools/cloud outbox counters | medium | token VIEW | unavailable executor -> -1 snapshot | CPU/memory/thread-pool diagnosis |
| `/monitor/errors` | `ExceptionMonitorService` | total/category/device/recent exception counters | low | token VIEW | in-memory only | recent operational exceptions |
| `/monitor/report` | `CloudReportMonitorService` | enabled/status/config coverage/executor/outbox/batch/ack/payload/risks | medium | token VIEW | config snapshot failure becomes risk/status | cloud reporting diagnosis |
| `/monitor/storage` | `TdengineMonitorService` | enabled/status/message/responseTime | low/medium | token VIEW | disabled/down/error status | TDengine availability diagnosis |
| `/monitor/perf/detail` | `CollectionScheduler` | time slices, overload, slow devices, rejected counts, reconnect counters | medium | token VIEW | scheduler snapshot only | scheduler bottleneck diagnosis |
| `/api/ops/logs` | `OpsController` + `OpsConsoleApplicationService` + `OperationLogger` | in-memory sanitized logs | low | token VIEW | no persistence; restart clears buffer | console log drilldown |

## 7. Metrics Inventory

| Category | Existing Evidence | Current Coverage |
| --- | --- | --- |
| JVM | `/actuator/metrics`, `/actuator/prometheus` | memory, GC, buffers, classes, threads |
| Process/System | `/actuator/metrics`, `/monitor/system` | process CPU, system CPU, uptime, memory; custom system snapshot includes physical memory |
| HTTP | `http.server.requests` | method/status/uri/outcome/exception/error; URI templates observed for controller paths |
| Auth | `AuthFilter` + runtime metric | custom counter `collector.auth.requests`; Prometheus `collector_auth_requests_total`; tags `result`, `type` |
| Hikari/JDBC | runtime metric list | `hikaricp.*`, `jdbc.connections.*` visible |
| Redis/Lettuce | runtime metric list | lettuce command completion/firstresponse visible; remote/local/command tags |
| Logback | runtime metric list | `logback.events` by level |
| Executor | Actuator metrics | Spring-managed executors expose `executor.*` for selected TaskExecutors only |
| System thread pools | `/monitor/system` | 17 named pools expose core/max/active/queue/completed/rejected in custom DTO |
| Device | `/monitor/devices`, `/monitor/performance` | custom DTOs, not custom Micrometer gauges/counters |
| Collector | `/monitor/performance`, `/monitor/perf/detail` | throughput/success/latency per collector plus scheduler rejected/reconnect counters |
| Cache | `/monitor/cache` | reads/writes/deletes/misses/access/hit rates/local+Redis stats; no dedicated custom Prometheus metrics found |
| Storage | `/monitor/storage`, health indicators | TDengine enabled/status/latency; detailed write/query/buffer metrics not exposed in main monitor DTO |
| Cloud | `/monitor/report`, `/monitor/system` | outbox count/isolation/oldest age, report executor queue/capacity/rejected, handler stats |
| Exception | `/monitor/errors` | in-memory counters/recent; not exported as custom Prometheus metric |
| Trace | dependency/code search | no tracing bridge / MDC / OTel propagation found |

Custom Micrometer found in production code:

| Metric | Type | Tags | Source | Cardinality |
| --- | --- | --- | --- | --- |
| `collector.auth.requests` | counter | `result`, `type` | `AuthFilter` | bounded: allowed/denied and auth type |

No production custom metric tags using `pointId`, `requestId`, full dynamic path, or exception message were found. `deviceId` appears in custom monitor DTOs and exception maps, not as a Prometheus tag in audited code.

## 8. Pipeline Coverage

| Stage | Throughput | Latency | Error | Queue | Drop | Health |
| --- | --- | --- | --- | --- | --- | --- |
| Collection | `/monitor/performance` processedPoints / pointsPerSecond | averageLatencyMs | successRate + protocolMetrics | scheduler detail rejected counts | not explicit as drop count | runtime component + scheduler detail |
| Processor | partial via scheduler/process rejected | not stage-specific | processRejectedCount | `dataProcessorExecutor` queue/rejected | not explicit | thread pool only |
| Cache | reads/writes/access/hit/miss | no latency | `totalErrors` per level | `cacheAsyncExecutor` queue/rejected; telemetry cache stage queue/rejected | not explicit | cache health overallStatus |
| Redis Stream | no endpoint-level throughput except Actuator lettuce commands | lettuce latency | command failures not directly summarized | stream stage/write executor queue/rejected; Redis stream buffer capacity from config | ingress buffer has code-level metrics but not main monitor endpoint | partial via runtime/Redis health |
| History | storage enabled/status/responseTime | TDengine ping responseTime; batch metrics exist in code/tests | storage status/message; health indicator | history stage executor; history buffer health if TDengine enabled | buffer outcome metrics exist but not in monitor DTO | actuator health component + storage monitor |
| Cloud | handlers statistics + report config coverage | handler stats if handler supplies | risks/status/outbox isolated | report executor queue/capacity/rejected; outbox pending/isolated/oldest | isolated outbox visible | `/monitor/report` and actuator cloudOutbox component |

## 9. Queue Saturation Visibility

| Queue | Capacity | Current Size Visible | Rejected Count | Warning Threshold | Endpoint |
| --- | ---: | --- | --- | --- | --- |
| `telemetryCacheStageExecutor` | config 2000 | yes via `/monitor/system` queueSize | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `telemetryStreamStageExecutor` | config 2000 | yes | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `telemetryStreamWriteExecutor` | implementation-specific | yes | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `telemetryHistoryStageExecutor` | config 5000 | yes | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `telemetryReportStageExecutor` | config 5000 | yes | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `cacheAsyncExecutor` | code/config executor | yes | yes | no explicit threshold in DTO | `/monitor/system`, Actuator `executor.*` |
| `stream buffer` | config 10000 local ingress, Redis stream max-length 200 | code-level metrics exist; not in main `/monitor/**` inventory response | partial/code-level | not visible in endpoint matrix | gap |
| `history write buffer` | config 10000 local queue | health indicator and code-level metrics exist; not in `/monitor/storage` response | code-level | health warning at 90% local usage | actuator health if indicator registered; not detailed endpoint |
| `reportExecutor` | code 5000 | yes via `/monitor/report` and `/monitor/system` | yes | report monitor warns at 70%, errors at 90% | `/monitor/report` |
| `cloud outbox` | repository-dependent | pending/isolated/oldest age | attempts/ACK details partially handler/outbox-dependent | isolated count causes health down | `/monitor/report`, `/monitor/system`, actuator health |
| `Redis stream buffer` | max-length 200 | not as current length in main monitor DTO | xadd failure not in `/monitor/**` | not visible | gap |

## 10. Health Inventory and Semantics

### `/health` custom business health

Source: `SystemHealthService`.

| Component | Runtime Status Observed | Rule / Meaning |
| --- | --- | --- |
| `collectionService` | DOWN | `CollectionServiceHealthTracker` current status; local runtime had no running collection |
| `cache` | UP | `MultiLevelCacheManager.getHealthStatus().overallStatus` parsed into `UP/DOWN/DEGRADED/UNKNOWN` |
| `connections` | UNKNOWN | no connections and no expected running devices -> UNKNOWN; active=0 with expected devices would degrade/down based on rules |
| `application` | UP | application process is running |

Runtime `/health` returned overall `DOWN` while `/actuator/health` returned `UP`; therefore they are not the same semantic model.

### Actuator health

- Default `/actuator/health`: `{status: UP}` only.
- `MANAGEMENT_HEALTH_DETAILS` default is `never`, so custom components are hidden by default.
- Health indicators exist in code: `historyBuffer`, `cloudOutbox`, `alarmState`, `configSync`.
- Liveness/readiness probe endpoints were absent: `/actuator/health/liveness` 404, `/actuator/health/readiness` 404.

### Recommended semantic split

| Use Case | Better Endpoint Today | Reason |
| --- | --- | --- |
| K8s liveness | `/actuator/health` | machine-facing process health; custom `/health` can be DOWN when service is stopped but process is still live |
| K8s readiness | GAP | no readiness probe; `/health` is too business-degraded and `/actuator/health` hides details |
| User diagnosis | `/health` + `/monitor/runtime` | component detail and business status available |
| Prometheus scrape | `/actuator/prometheus` | machine-facing scrape format |

## 11. Logging Inventory

| Log Surface | Current Behavior | Runtime Evidence | Assessment |
| --- | --- | --- | --- |
| Console logs | effective Logback STDOUT appender only | runtime stdout contained startup WARN/INFO and actuator media-type ERROR | PASS for console/stdout deployment |
| File logs | `application.yml` config exists | no `logs/collector.log` created in repo root or target path; Logback has no file/rolling appender | P1 gap for non-stdout deployments |
| Operation logs | root Logback appender attached in memory | `/api/ops/logs` returned startup WARN/ERROR entries | PASS, bounded and queryable |
| Access logs | `LogFilter` configured for `/api/**` | runtime API calls under `/collector/api/**` produced no `config_access` in stdout or ops logs | P1 mismatch |
| High-risk access logs | should log WARN if path matches risk rule | invalid high-risk POST returned 400 but no `config_access` entry due same context-path mismatch | P1 mismatch |
| Protocol/device logs | many protocol/runtime `log.warn/error` calls | console/ops buffer capture enabled events | PARTIAL: not all feed `ExceptionMonitorService` |

### OperationLogger limits and safety

| Property | Value |
| --- | ---: |
| `MAX_ENTRY_COUNT` | 2000 |
| `MAX_MESSAGE_LENGTH` | 4000 |
| `DEFAULT_QUERY_LIMIT` | 200 |
| `MAX_QUERY_LIMIT` | 1000 |

Sanitization regex coverage verified by existing `OperationLoggerTest` includes sensitive-key assignment, password-style assignment, and bearer-auth phrase redaction.

Static regex also covers sensitive field names such as password/passwd/pwd/token/secret/deviceKey/accessKey/authorization and bearer-style tokens. Runtime query did not expose sensitive credential literals in observed entries.

## 12. Correlation Inventory

| Layer | Request ID | Device ID | Point ID | Operation ID |
| --- | --- | --- | --- | --- |
| HTTP access | supported after Task 05.2 via `RequestCorrelationFilter` attribute/MDC and `collector.access` log entries | intended from query/path | no generic point parsing | none |
| Controller | synchronous web-filter-chain requests can see MDC `requestId`; explicit method params unchanged | method params include device/point in many controllers | method params where present | none |
| Application | synchronous HTTP call stack inherits MDC `requestId`; explicit params unchanged | explicit business params | explicit business params | no cross-layer operation id |
| Collection | no request id | device id common in logs/metrics | point id in collector exceptions | no HTTP operation id |
| Protocol | no request id | device id in many logs | point id/address in many logs | no HTTP operation id |
| Cache | no request id | exceptionReporter resourceId/device-ish key | no general point id | none |
| History | no request id | buffer metrics and logs include device in some paths | point id in some paths | none |
| Cloud | no request id | target/device visible in logs/outbox/report | point code visible in logs/handler stats | message id/outbox id exists in cloud semantics, not HTTP request id |

Task 05.2 runtime checks:
- incoming `X-Request-Id: obs-052-runtime-001` was preserved in the response header.
- missing request ID generated a non-empty response `X-Request-Id`.
- `/api/ops/logs?keyword=obs-052-runtime-001` found the correlated OperationLogger entry.
- access logs emitted `requestId=obs-052-runtime-001` in both stdout and bounded file output.

Conclusion: HTTP synchronous request correlation is now supported for requests that pass through the web filter chain. Long-running background collection remains correlated by deviceId/pointId/protocol/outbox identifiers; generic async MDC propagation and distributed tracing are not implemented in Task 05.2.

## 13. Exception Monitoring

Source: `ExceptionMonitorService` implements `ExceptionReporter`.

| Capability | Current Behavior |
| --- | --- |
| total counter | `LongAdder totalCounter` |
| category counter | `ConcurrentHashMap<String, LongAdder>` categorized by exception class/message heuristics |
| device counter | `ConcurrentHashMap<String, LongAdder>` keyed by deviceId |
| recent buffer | `ArrayDeque`, `MAX_RECENT = 100` |
| persistence | none; restart clears counters/recent |
| message safety | raw `throwable.getMessage()` stored; no explicit sanitization or length bound |
| memory boundedness | recent is bounded; category/device maps are not bounded |

Observed `exceptionReporter.record(...)` production coverage:
- connection manager connection failures
- cache manager failures
- base collector read failures

Critical operational catch paths with logs but no exception reporter include:
- application-level realtime/history/control/device-console query/action failures
- many protocol-specific write/read/subscription failures
- cloud report handler failures and outbox isolation events
- TDengine/history batch fallback/drop paths
- scheduler lifecycle/reconnect/maintenance errors

This does not mean every `log.warn/error` should become an exception record. It does mean `/monitor/errors` is currently a partial operational error lens, not a complete incident ledger.

## 14. Security / Data Safety

| Area | Current State | Classification |
| --- | --- | --- |
| `/actuator/health` | public and details hidden by default | SAFE PUBLIC |
| `/actuator/metrics` | public, exposes metric names/tags including URI templates, pool names, Redis remote/local tags | P1 EXPOSURE for production unless network-isolated |
| `/actuator/prometheus` | public, exposes full scrape | P1 EXPOSURE for production unless network-isolated |
| `/monitor/**` | token-protected VIEW | AUTHENTICATED |
| `/api/ops/logs` | token-protected VIEW | AUTHENTICATED |
| access query logging | raw query string truncated only; no query parameter redaction before logging | SECURITY / OBSERVABILITY GAP |
| additionalHeaders | configurable allow-list but no sensitive header denylist/redaction | SECURITY / OBSERVABILITY GAP if enabled with secrets |
| body logging | only request/response byte size, not body content | PASS |
| OperationLogger message | sanitizes sensitive message content and length-bounds message | PASS |
| client IP | `LogFilter` directly trusts `X-Forwarded-For`/`X-Real-IP`; `AuthFilter` has trusted proxy logic for IP auth | Task 06 Security candidate; audit IP can be spoofed |

## 15. Cardinality / Boundedness

| Structure | Key | Bound | Long-run Risk |
| --- | --- | --- | --- |
| OperationLogger entries | log entries | 2000 entries; 4000 chars/message | LOW |
| OperationLogger query | returned entries | max 1000 | LOW |
| Exception recent | recent exception summaries | 100 | LOW |
| Exception category counter | exception category | unbounded distinct categories, but generally class/message-category bounded | LOW/MEDIUM |
| Exception device counter | deviceId | unbounded | MEDIUM; many dynamic device IDs can grow until restart |
| Exception message | throwable message | no length bound/sanitization before snapshot | MEDIUM security/memory/reporting risk |
| Micrometer auth metric | result,type | bounded | LOW |
| HTTP metrics uri | URI template | runtime templates observed; unknown paths collapse to UNKNOWN | LOW |
| Lettuce metric tags | remote/local/command | bounded by configured Redis endpoints/commands | LOW |
| Executor metrics name | executor bean name | bounded | LOW |
| Custom monitor DTO connection list | active/configured connections | bounded by configured devices | expected operational scale |
| Cloud target keys | cloud target identities in `/monitor/report` | bounded by config but can expose identity cardinality | MEDIUM exposure/response-size risk at very large config |

## 16. Troubleshooting Matrix

| Incident | Existing Evidence | Missing Evidence | Can Diagnose Today |
| --- | --- | --- | --- |
| CPU high | `/monitor/system`, `process.cpu.usage`, `system.cpu.usage` | per-task/per-stage CPU attribution | PARTIAL |
| Heap high | JVM metrics + `/monitor/system` heap/non-heap | object/queue memory attribution | PARTIAL |
| Thread pool saturation | `/monitor/system` active/queue/rejected for 17 pools; Actuator executor metrics for selected pools | queue capacity missing in `/monitor/system`; warning thresholds mostly absent | PARTIAL |
| Redis failure | `/monitor/cache` Redis health/stats; lettuce metrics | command failure summary and stream backlog not unified | PARTIAL |
| TDengine failure | `/monitor/storage`; `historyBuffer` health indicator exists | detailed write success/fail/query/buffer exposed together | PARTIAL |
| Device disconnect | `/monitor/devices`, `/health` connections, runtime risks | per-device timeline/history after restart | PARTIAL |
| Point read failure | `ExceptionReporter` from `BaseCollector`; protocol logs | coverage incomplete for protocol-specific and application paths; point history not queryable by incident | PARTIAL |
| Cloud backlog | `/monitor/report` outbox pending/isolated/oldest, report executor, handlers | ACK success/failure and attempts not consistently visible as metrics | PARTIAL |
| HTTP 5xx/failure | Actuator `http.server.requests`; GlobalExceptionHandler logs; ops logs | request ID not returned/propagated; access log absent under context path | NO/PARTIAL |

## 17. Frontend Observability Surface

| Surface | Existing Evidence |
| --- | --- |
| Dashboard | uses monitor/runtime/cache/devices/performance/system/errors/report/storage data through `monitor.api.ts` and page adapters |
| Diagnostic | displays runtime/detail/operational diagnostic panels |
| Log | uses `/api/ops/logs` with filters and server max limit |
| Cloud | uses `/monitor/report` to show enabled/ready/degraded/unavailable after Task 03 |
| Device runtime | runtime/status panels use device and monitor APIs |
| Health probe | desktop HTTP module has `/health` check |

Frontend has a reasonable human-facing observability surface for dashboards and logs, but it currently depends on server monitor/log semantics that have the access-log and correlation gaps documented above.

## 18. Findings

### P0

None found.

### P1

Finding ID: OBS-P1-01
Area: Request correlation
Status: CLOSED in Task 05.2
Current behavior: dedicated `RequestCorrelationFilter` resolves incoming/generated `X-Request-Id` before access/auth/controller execution, stores it in a stable request attribute, writes response header, sets MDC key `requestId`, and restores/removes only that MDC key in `finally`.
Evidence: `scripts/run-observability-correlation-smoke.ps1` against the current executable JAR preserved `obs-052-runtime-001` in the response header, generated a non-empty ID when absent, and found the request ID through `/api/ops/logs?keyword=obs-052-runtime-001`; backend regression includes incoming/generated/invalid/exception/MDC cleanup cases.
Operational impact: HTTP synchronous controller/application/access logs are now correlatable by requestId; this is HTTP request correlation, not distributed tracing or generic async MDC propagation.
Severity: P1
Resolution Task: 05.2 — Request Correlation & Access Logging

Finding ID: OBS-P1-02
Area: Access logging
Status: CLOSED in Task 05.2
Current behavior: `LogFilter` normalizes `requestURI - contextPath` to application path before include/exclude/high-risk matching; `/collector/api/**` is logged as `/api/**`, while `/health` and `/actuator/**` remain excluded. Access logging uses dedicated logger `collector.access` at INFO, independent of business package WARN.
Evidence: runtime smoke emitted normal `config_access` for `/api/ops/logs`, emitted WARN/high-risk `config_access` for unauthenticated `POST /api/config/import` with status 401 before controller side effects, and verified query/header redaction; regression covers `/collector` context-path normalization, high-risk matching, denied auth, principal capture, and redaction.
Operational impact: HTTP access audit trail is present for normal and denied/high-risk API requests without lowering `com.wangbin.collector` package logging below WARN.
Severity: P1
Resolution Task: 05.2 — Request Correlation & Access Logging

Finding ID: OBS-P1-03
Area: File logging
Status: CLOSED in Task 05.2
Current behavior: console logging is retained and a bounded `RollingFileAppender` writes to `logging.file.name` (`logs/collector.log` by default), with `logging.file.max-size`, `logging.file.max-history`, and `logging.file.total-size-cap` controlling retention.
Evidence: runtime smoke started the current executable JAR with `--logging.file.name=<temporary>/collector.log`, generated normal and high-risk access events, confirmed stdout was non-empty, confirmed `collector.log` existed with size > 0, and confirmed access/requestId/redacted query entries in both stdout and file.
Operational impact: server/local standalone deployments now have both stdout collection and bounded on-disk logs. Electron final writable log directory remains a Task 06 delivery concern.
Severity: P1
Resolution Task: 05.2 — Request Correlation & Access Logging

Finding ID: OBS-P1-04
Area: Actuator production exposure
Current behavior: `/actuator/metrics` and `/actuator/prometheus` are permit-all.
Evidence: no-auth runtime calls returned 200; Prometheus scrape exposed JVM/HTTP/Hikari/Lettuce/auth/executor metrics and Redis remote/local tags.
Operational impact: useful machine metrics are publicly reachable on the service port unless deployment network policy isolates them.
Severity: P1 exposure unless proven internal-only.
Recommended repair: keep `/actuator/health` public but require auth/internal network for metrics/prometheus, or document ingress/network isolation.
Recommended Task: 05.3 — Core Pipeline Metrics & Health

Finding ID: OBS-P1-05
Area: Critical queue/backlog visibility
Current behavior: many executor queues/rejected counts are visible, but stream buffer, Redis stream backlog, and history buffer detailed metrics are not unified in `/monitor/**`; `/monitor/system` lacks queue capacity and threshold status for most pools.
Evidence: `/monitor/system` shows queueSize/rejected for 17 pools; `/monitor/report` shows capacity/usage for report executor; stream/history buffer metrics exist in code/tests but are not present in main endpoint matrix.
Operational impact: saturation can be partially inferred but critical telemetry loss/backpressure can still require code-level or test-only knowledge.
Severity: P1/P2 boundary; classify P1 for core telemetry pipeline operations.
Recommended repair: expose a small pipeline/backpressure section with capacity, current size, rejected/drop/deferred counts, and thresholds.
Recommended Task: 05.3 — Core Pipeline Metrics & Health

### P2

Finding ID: OBS-P2-01
Area: Health model split
Current behavior: `/health` and `/actuator/health` intentionally or accidentally diverge; local runtime had `/health=DOWN` while `/actuator/health=UP`.
Evidence: runtime matrix.
Operational impact: useful split, but undocumented and no readiness endpoint.
Severity: P2
Recommended repair: define machine-facing vs console-facing semantics and add readiness in a later scoped task.
Recommended Task: 05.3 — Core Pipeline Metrics & Health

Finding ID: OBS-P2-02
Area: Exception monitor boundedness/safety
Current behavior: recent buffer is bounded, but device/category maps are unbounded and exception messages are raw/unbounded.
Evidence: `ExceptionMonitorService` source.
Operational impact: long-running high-cardinality environments can grow counters and may expose sensitive exception text.
Severity: P2 unless active secret logging is proven.
Recommended repair: length-bound/sanitize messages and cap/device-counter policy.
Recommended Task: 05.4 — Operational Diagnostic Surface

Finding ID: OBS-P2-03
Area: Custom monitor vs Micrometer model
Current behavior: rich `/monitor/**` DTOs and Actuator metrics coexist, but not all custom DTO facts are mirrored into Prometheus.
Evidence: custom monitor endpoint matrix and Micrometer search.
Operational impact: console can diagnose more than Prometheus alerts can see.
Severity: P2
Recommended repair: choose a small alert-grade metric subset rather than duplicating every DTO field.
Recommended Task: 05.3 — Core Pipeline Metrics & Health

Finding ID: OBS-P2-04
Area: Client IP trust in access logs
Current behavior: access log IP reads `X-Forwarded-For`/`X-Real-IP` directly; AuthFilter has more careful trusted proxy handling for IP auth.
Evidence: `LogFilter.resolveClientIp`; `AuthFilterTest.shouldIgnoreForwardedAddressFromUntrustedProxy`.
Operational impact: audit IP can be spoofed outside trusted-proxy deployments.
Severity: P2 here; likely Task 06 Security if used for security audit.
Recommended repair: align access-log IP with trusted proxy config.
Recommended Task: Task 06 Security or 05.2 if bundled with access logging.

## 19. Existing Capabilities Already Good Enough

- Actuator + Micrometer + Prometheus registry are already present and functional.
- JVM/process/system/Hikari/Lettuce/HTTP metrics are available without adding dependencies.
- Auth metrics are already low-cardinality and exported as `collector_auth_requests_total`.
- `/monitor/runtime` provides a useful human-facing aggregate across cache/devices/system/errors/performance/report/storage.
- `/monitor/system` already enumerates many named thread pools including telemetry cache/stream/history/report executors.
- `/monitor/report` has relatively strong cloud/outbox/executor/config coverage.
- OperationLogger is bounded, queryable, and sanitizes key sensitive message patterns.
- Task 05.2 adds `requestId` to OperationLogger entries and keyword search, while retaining entry/message/query bounds and sensitive message sanitization.
- Custom `/health` exposes business components better than default Actuator health.

## 20. Top Priorities

Priority 1: 05.2 — Request Correlation & Access Logging — COMPLETE
- HTTP request ID response/MDC/business-log propagation is implemented for synchronous web-filter-chain requests.
- Access log context-path matching and dedicated access logger visibility are implemented.
- Console + bounded rolling file logging is implemented and runtime-verified.

Priority 2: 05.3 — Core Pipeline Metrics & Health
- Harden actuator metrics/prometheus exposure or document internal-only deployment.
- Add readiness semantics.
- Expose alert-grade queue/backpressure metrics for telemetry stream/history/cache/cloud.

Priority 3: 05.4 — Operational Diagnostic Surface
- Improve exception monitor coverage for critical operational paths only.
- Bound/sanitize exception messages and device counters.
- Align frontend incident views with the machine/human observability split.

## 21. Recommended Task 05 Roadmap

05.2 — Request Correlation & Access Logging — RESOLVED
- Request ID response header + MDC + request attribute.
- Access log context path fix and high-risk/denied-auth coverage.
- Dedicated `collector.access` logger policy.
- Console + bounded rolling file logging.
- Query/header redaction policy for access logs.

05.3 — Core Pipeline Metrics & Health
- Readiness endpoint semantics.
- Metrics/prometheus exposure posture.
- Alert-grade pipeline queue/backpressure metrics.
- Prometheus metric subset and cardinality guard.

05.4 — Operational Diagnostic Surface
- Exception monitor coverage for selected critical paths.
- Exception message sanitization/length bound.
- Console incident mapping improvements.

05.5 — Observability Regression & Final Audit
- Full observability smoke.
- Access/correlation regression.
- Prometheus cardinality/exposure audit.
- Final Health/Metrics/Logs/Correlation decision.

## 22. Production Exposure Classification

| Surface | Current Exposure | Recommended Meaning |
| --- | --- | --- |
| `/health` | PUBLIC | console/user/business status; not liveness by itself |
| `/actuator/health` | PUBLIC | machine liveness-style status |
| `/actuator/metrics` | PUBLIC | should be INTERNAL or AUTHENTICATED in production |
| `/actuator/prometheus` | PUBLIC | should be INTERNAL Prometheus scrape only |
| `/monitor/**` | AUTHENTICATED | console/human operational details |
| `/api/ops/logs` | AUTHENTICATED | sanitized in-memory operational logs |

## 23. Acceptance Answers

| Question | Answer |
| --- | --- |
| 现在有哪些监控能力已经很好？ | Actuator/Micrometer baseline, JVM/HTTP/Hikari/Lettuce metrics, auth counter, `/monitor/runtime`, `/monitor/system`, `/monitor/report`, bounded OperationLogger |
| 哪些 critical pipeline 已经有指标？ | collection throughput/latency/success, cache hit/miss/errors, report executor/outbox, TDengine status, named executor queue/rejected |
| 哪些 critical pipeline 完全看不到？ | no complete unified stream/history buffer/backpressure endpoint; Redis stream backlog not in main monitor DTO |
| 日志是否真正落盘？ | Yes after Task 05.2. STDOUT remains enabled and bounded rolling file logging writes `logging.file.name`; runtime file creation was verified with the current JAR. Electron final writable log directory is deferred to Task 06 delivery. |
| access log 默认是否真正输出？ | Yes after Task 05.2. `/collector/api/**` is normalized to `/api/**`, `collector.access` logs INFO independently of business package WARN, and high-risk 401 access audit is runtime-verified. |
| requestId 能不能端到端关联？ | HTTP synchronous request correlation is supported after Task 05.2: response header, request attribute, MDC, access log, and OperationLogger keyword search. Generic async/background correlation and distributed tracing are not implemented. |
| Prometheus 有哪些 custom metric？ | `collector_auth_requests_total` from `collector.auth.requests`; other observed metrics are Spring/JVM/Tomcat/Hikari/Lettuce/Logback/executor defaults |
| health 是否能区分 liveness/readiness/business degraded？ | Business degraded exists in custom `/health` and `/monitor/runtime`; liveness can use actuator basic health; readiness is missing |
| monitor endpoint 和 actuator 应如何分工？ | Actuator for machine health/metrics/prometheus; `/health` + `/monitor/**` + `/api/ops/logs` for console/human diagnosis |
| 最大的 3 个生产排障缺口是什么？ | request correlation/access logs, durable/effective logging model, actuator exposure + core pipeline backpressure gaps |

## 24. Verification Commands

Runtime:

```text
D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd -B -ntp -DskipTests package
java -jar collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar --server.port=19092 --collector.config.sync-enabled=false --collector.report.enabled=false --telemetry.tdengine.enabled=false
```

Runtime endpoints verified:

```text
/health
/actuator
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/metrics
/actuator/prometheus
/actuator/metrics/http.server.requests
/actuator/metrics/collector.auth.requests
/monitor/runtime
/monitor/cache
/monitor/devices
/monitor/performance
/monitor/system
/monitor/errors
/monitor/report
/monitor/storage
/monitor/perf/detail
/api/ops/logs
```

Static source checks performed during audit:

```text
MDC.put / MDC.get / MDC.remove / MDC.clear
MeterRegistry / counter / timer / gauge
exceptionReporter.record(...)
log.warn / log.error critical paths
HealthIndicator implementations
logback FileAppender / RollingFileAppender
```

Verification results:

| Check | Result |
| --- | --- |
| Current JAR build | PASS — Maven reactor package with skipped tests completed BUILD SUCCESS |
| Frontend typecheck | PASS — `npm --prefix collector-desktop run typecheck` |
| Frontend tests | PASS — `70 files / 504 tests` |
| Monitor/web targeted tests | PASS after Task 05.2 — `collector-monitor,collector-web -am test` ran 68 tests with BUILD SUCCESS |
| Runtime endpoint smoke | PASS — endpoints in the runtime matrix were exercised against port `19092` |
| Runtime auth smoke | PASS — no-token `/monitor/**` returned 401; valid VIEW token returned 200 |
| Runtime request-id check | PASS after Task 05.2 — incoming ID preserved, generated ID returned, MDC-backed access log and OperationLogger search verified |
| Runtime file-log check | PASS after Task 05.2 — `--logging.file.name=<temporary>/collector.log` created a non-empty bounded rolling file with access entries |
| Source language audit | PASS — `{ ok: true, count: 0 }` |
| Production config secret scan | PASS |
| Whitespace diff check | PASS — `git diff --check` |

## 25. Task 05.3 RESOLVED — Core Pipeline Metrics & Health

Task 05.3 closes OBS-P1-04, OBS-P1-05, OBS-P2-01, and OBS-P2-03. It does not modify ExceptionMonitor boundedness/message coverage, which remains deferred to Task 05.4.

### Exposure Contract

| Endpoint | Anonymous | VIEW | Intended Consumer |
| --- | ---: | ---: | --- |
| `/health` | 200 | 200 | Desktop/user business health |
| `/actuator/health` | 200 | 200 | machine aggregate health |
| `/actuator/health/liveness` | 200 | 200 | K8s/process liveness |
| `/actuator/health/readiness` | 200 | 200 | K8s/load balancer readiness |
| `/actuator/metrics` | 401 | 200 | operator metric discovery |
| `/actuator/metrics/**` | 401 | 200 | operator metric detail |
| `/actuator/prometheus` | 401 | 200 | protected Prometheus scraper |
| `/monitor/pipeline` | 401 | 200 | console/operator diagnostic detail |

Public health is intentionally limited to `/health`, `/actuator/health`, and `/actuator/health/**`. `/actuator`, `/actuator/info`, `/actuator/metrics`, and `/actuator/prometheus` are no longer covered by a broad `/actuator/**` permit-all rule. Actuator metrics/prometheus access is explicitly mapped to VIEW so a future default-scope change does not silently weaken exposure semantics.

### Health Contract

| Surface | Meaning | Components | Disabled Integration Behavior |
| --- | --- | --- | --- |
| Liveness | Process/JVM/Spring application is alive | `livenessState` only | Redis/TDengine/cloud/config sync/device availability do not participate |
| Readiness | Spring application can receive HTTP traffic | `readinessState` only | optional disabled integrations do not make readiness DOWN |
| Actuator aggregate | Spring Boot aggregate health for machine visibility | existing HealthIndicators as Spring aggregates them | HTTP status is mapped to 200 so public aggregate health remains queryable even when business dependencies are DOWN |
| Business health | Console/user component state | `/health` and `/monitor/runtime` | degraded/disabled/unknown external components remain visible here |
| Pipeline health | Current queue/backpressure status | `/monitor/pipeline` and `collector.pipeline.*` | optional disabled stages are `DISABLED`, not `DANGER` |

### Pipeline Matrix

| Stage | Queue | Capacity | Utilization | Reject | Drop | Backlog | Health |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| Ingress | local ingress queue | `localCapacity` | `localUtilization` | `rejectedTasks`, `rejectedItems` | `droppedItems` | Redis pending/processing/dead-letter | `HEALTHY/WARNING/DANGER/UNKNOWN/DISABLED` |
| Stream | stream write buffer | `bufferCapacity` | `bufferUtilization` | `admissionRejected` | `admissionDropped`, `shutdownDroppedRows` | buffer size / Redis write evidence | same vocabulary |
| History | history local queue + live flush | `localCapacity` | `localUtilization`, `liveFlushQueueUtilization` | rejected buffered counters | write/rejected/batch fallback dropped rows | Redis pending/processing/dead-letter | same vocabulary |
| Cloud | cloud outbox | repository-dependent | n/a | n/a | n/a | pending, isolated, oldest age | same vocabulary |
| cache executor | `telemetryCacheStageExecutor` | actual queue size + remaining capacity | 0.0-1.0 or -1 | `rejectedCount` | n/a | queue size | same vocabulary |
| stream executor | `telemetryStreamStageExecutor` | actual queue size + remaining capacity | 0.0-1.0 or -1 | `rejectedCount` | n/a | queue size | same vocabulary |
| stream writer executor | `telemetryStreamWriteExecutor` | actual queue size + remaining capacity | 0.0-1.0 or -1 | `rejectedCount` | n/a | queue size | same vocabulary |
| history executor | `telemetryHistoryStageExecutor` | actual queue size + remaining capacity | 0.0-1.0 or -1 | `rejectedCount` | n/a | queue size | same vocabulary |
| report executor | `telemetryReportStageExecutor` | actual queue size + remaining capacity | 0.0-1.0 or -1 | `rejectedCount` | n/a | queue size | same vocabulary |

Queue capacity is computed from `ThreadPoolExecutor.getQueue().size() + remainingCapacity()`. Unbounded, unknown, or unsupported queues use `queueCapacity=-1` and `queueUtilization=-1`; zero-capacity queues do not divide by zero.

### Pipeline Snapshot Contract

`GET /monitor/pipeline` returns a human/operator DTO with:

```text
status, generatedAt, ingress, stream, history, cloud, executors, risks
```

Status vocabulary is fixed: `HEALTHY`, `WARNING`, `DANGER`, `UNKNOWN`, `DISABLED`. Current queue pressure thresholds are `<70% HEALTHY`, `>=70% WARNING`, `>=90% DANGER`. Redis `-1` values remain `UNKNOWN`, not zero. Dead-letter and cloud isolation are `DANGER` risks. Historical cumulative counters such as dropped/rejected/failures are displayed as evidence but do not permanently force current DANGER by themselves.

Snapshot refresh uses a bounded TTL cache (`5s`). One refresh calls each source at most once: ingress metrics, stream metrics, history metrics, cloud snapshot, and the thread-pool-only system resource accessor. Prometheus gauges read only the cached immutable snapshot; per-gauge Redis/percentile/runtime calls are avoided. Source failures are isolated to `UNKNOWN` stage snapshots and do not make `/monitor/pipeline` return 500 or affect telemetry runtime behavior.

### Prometheus Metric Contract

| Metric | Type | Tags | Meaning | Cardinality |
| --- | --- | --- | --- | --- |
| `collector.pipeline.enabled` | Gauge | `stage`, `type` | stage/executor enabled as 1/0 | fixed stage/type enums |
| `collector.pipeline.status` | Gauge | `stage`, `type` | one-hot status by `HEALTHY/WARNING/DANGER/UNKNOWN/DISABLED` | fixed stage/status enums |
| `collector.pipeline.queue.size` | Gauge | `stage`, `type` | current pipeline/executor queue size | fixed stage/queue enums |
| `collector.pipeline.queue.capacity` | Gauge | `stage`, `type` | bounded queue capacity or -1 unknown | fixed stage/queue enums |
| `collector.pipeline.queue.utilization` | Gauge | `stage`, `type` | 0.0-1.0 utilization or -1 unknown | fixed stage/queue enums |
| `collector.pipeline.backlog` | Gauge | `stage`, `type` | current backlog such as Redis pending/cloud pending | fixed stage/type enums |
| `collector.pipeline.rejected` | Gauge | `stage`, `type` | rejected evidence snapshot value | fixed stage/type enums |
| `collector.pipeline.dropped` | Gauge | `stage`, `type` | dropped evidence snapshot value | fixed stage/type enums |
| `collector.pipeline.failures` | Gauge | `stage`, `type` | write/loop/dead-letter/replay failure evidence | fixed stage/type enums |
| `collector.pipeline.oldest.age` | Gauge | `stage`, `type` | cloud oldest outbox age in milliseconds | fixed stage/type enums |

Allowed tag keys are `stage` and `type`. Stage values are fixed: `ingress`, `stream`, `history`, `cloud`, `cache_executor`, `stream_executor`, `stream_writer_executor`, `history_executor`, `report_executor`. Forbidden dynamic labels remain prohibited: `deviceId`, `pointId`, `requestId`, `messageId`, exception message, full URL, dynamic queue key, dynamic logger.

### 05.3 Verification Results

| Check | Result |
| --- | --- |
| Backend monitor/web tests | PASS — `mvn -DforkCount=0 -pl collector-monitor,collector-web -am test`, 71 tests, BUILD SUCCESS |
| Frontend typecheck | PASS — `npm --prefix collector-desktop run typecheck` |
| Frontend tests | PASS — `npm --prefix collector-desktop test` |
| Frontend verify | PASS — `npm --prefix collector-desktop run verify` |
| Current executable JAR package | PASS — `mvn -DskipTests package`, BUILD SUCCESS |
| Original real smoke | PASS — `REAL BACKEND SMOKE PASSED` |
| Correlation smoke | PASS — `OBSERVABILITY CORRELATION SMOKE PASSED`; startup probe now uses `/actuator/health/liveness` |
| Pipeline smoke | PASS — `OBSERVABILITY PIPELINE SMOKE PASSED` |
| Runtime public health | PASS — no-auth `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` returned HTTP 200 |
| Runtime protected metrics | PASS — no-auth `/actuator/metrics`, `/actuator/metrics/jvm.memory.used`, `/actuator/prometheus` returned 401 with `X-Request-Id`; valid VIEW returned 200 |
| Runtime pipeline endpoint | PASS — no-auth `/monitor/pipeline` returned 401; valid VIEW returned 200 with all required sections |
| Runtime isolated dependencies | PASS — TDengine/cloud disabled while liveness/readiness remained UP; pipeline history/cloud reported `DISABLED` |
| Runtime Prometheus custom metrics | PASS — protected scrape contained `collector_pipeline_*` metrics |
| High-cardinality label check | PASS — `collector_pipeline_*` scrape contained no `deviceId`, `pointId`, `requestId`, or `messageId` labels |
| Monitoring storm | PASS — 20 authenticated Prometheus scrapes returned 200 without 5xx |
| Source language audit | PASS — changed/untracked source files UTF-8 and no debug markers |
| Secret scan | PASS — changed/untracked source files scan found no credentials/secrets; fake smoke sentinel remains non-secret test input |
| Diff check | PASS — `git diff --check` exit 0 |

### 05.3 Findings Status

| Finding | Status | Resolution |
| --- | --- | --- |
| OBS-P1-01 | CLOSED | 05.2 request correlation remains verified by correlation smoke |
| OBS-P1-02 | CLOSED | 05.2 access logging remains verified by correlation smoke |
| OBS-P1-03 | CLOSED | 05.2 rolling file logging remains verified by correlation smoke |
| OBS-P1-04 | CLOSED | metrics/prometheus no longer public; public health remains public |
| OBS-P1-05 | CLOSED | `/monitor/pipeline`, queue utilization/capacity, cloud/ingress/stream/history/executor snapshots, cache-backed Prometheus subset implemented and verified |
| OBS-P2-01 | CLOSED | liveness/readiness/business/pipeline health contracts split and verified |
| OBS-P2-03 | CLOSED | rich human DTO plus small low-cardinality Prometheus subset implemented |
| OBS-P2-02 | OPEN | deferred to Task 05.4 Operational Diagnostic Surface |

Task 05.3 is PASS / COMPLETE. Task 05 overall remains NOT COMPLETE until Task 05.4 and Task 05.5 are complete.

## 26. Task 05.3-R1 — Cloud Snapshot Read Boundary Closure

Task 05.3-R1 closes the duplicate Cloud Outbox read boundary discovered after the main Task 05.3 implementation.

### Previous duplicate path

```text
PipelineBackpressureMonitorService.refresh()
├─ collectCloud()
│  └─ CloudOutboxService.snapshot()
└─ collectExecutors()
   └─ SystemResourceMonitorService.getResources()
      ├─ CloudOutboxService.getPendingCount()
      ├─ CloudOutboxService.getIsolatedCount()
      └─ CloudOutboxService.getOldestMessageAgeMillis()
```

The earlier cache tests mocked `SystemResourceMonitorService`, so they proved direct cloud snapshot reads were cached but did not cover the full production boundary where executor observation indirectly re-read cloud outbox state.

### Repair

- `SystemResourceMonitorService.getThreadPools()` was added as a thread-pool-only accessor. It only calls the existing thread-pool collector and does not read Cloud Outbox, CPU, memory, physical memory, or thread MXBean sources.
- `PipelineBackpressureMonitorService.collectExecutors()` now uses `getThreadPools()` instead of `getResources()`.
- `SystemResourceMonitorService.getResources()` now reads one coherent `CloudOutboxSnapshot` and populates `outboxPendingCount`, `outboxIsolatedCount`, and `outboxOldestMessageAgeMillis` from that immutable snapshot instead of calling three separate cloud getters.

### One-source-call boundary

```text
refresh()
├─ ingressBuffer.metrics()          <= 1
├─ streamWriteBuffer.metrics()      <= 1
├─ historyWriteBuffer.metrics()     <= 1
├─ cloudOutboxService.snapshot()    <= 1
└─ systemResourceMonitorService.getThreadPools() <= 1
```

Pipeline TTL remains 5 seconds. Pipeline statuses, thresholds, Prometheus metric names, and Prometheus tags are unchanged.

### Test evidence

- `SystemResourceMonitorServiceTest` verifies `getThreadPools()` returns thread-pool snapshots without Cloud Outbox interaction.
- `SystemResourceMonitorServiceTest` verifies `getResources()` calls `CloudOutboxService.snapshot()` once and does not call `getPendingCount()`, `getIsolatedCount()`, or `getOldestMessageAgeMillis()`.
- `PipelineBackpressureMonitorServiceTest` verifies a full cache-miss refresh reads cloud snapshot exactly once, does not call `SystemResourceMonitorService.getResources()`, and does not call legacy cloud monitoring getters.
- `PipelineBackpressureMonitorServiceTest` verifies repeated `getSnapshot()` calls inside TTL keep total cloud snapshot calls at one.
- `PipelineBackpressureMonitorServiceTest` verifies TTL expiry permits exactly one additional cloud snapshot call for the new refresh.
- `PipelineBackpressureMonitorServiceTest` verifies cloud failure becomes `UNKNOWN` without an indirect retry through executor observation.
- `PipelineBackpressureMonitorServiceTest` verifies executor source failure keeps the independently collected cloud result.
- `PipelineMetricsBinderTest` remains compatible; multiple gauge reads continue to use the cached pipeline snapshot and do not change metric contract/cardinality.

### Runtime evidence

- `/monitor/system` contract remains unchanged: `outboxPendingCount`, `outboxIsolatedCount`, `outboxOldestMessageAgeMillis`, and `threadPools` are still present.
- `/monitor/pipeline` contract remains unchanged: `status`, `generatedAt`, `ingress`, `stream`, `history`, `cloud`, `executors`, and `risks` are still present.
- `collector_pipeline_*` Prometheus metrics remain compatible; no metric names or tags changed.
- Actuator health/metrics/prometheus security remains unchanged from Task 05.3.

Task 05.3-R1 is PASS / COMPLETE. Task 05.3 remains PASS / COMPLETE. Task 05 overall remains NOT COMPLETE until Task 05.4 and Task 05.5 are complete.
