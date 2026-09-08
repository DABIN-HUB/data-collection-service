# Task 02.1 Realtime Scale Baseline & Bottleneck Inventory

生成时间：2026-09-07 11:35 +0800

## 1. Scope

本文件只建立 `Task 02 — Realtime & Large Data Performance` 的测量基线和瓶颈清单，不引入生产性能架构改造。

本轮 production behavior diff 预期为 0：不新增 API contract，不修改 Java/Vue/TS 生产逻辑，不实现 delta、WebSocket backend、虚拟表格、分页或 compact DTO。

## 2. Baseline

- branch: `feature_2.0`
- start commit: `b37171bd952ac1da32ee25956d0715c485373479`
- working tree at start: clean
- Task 01 status: `PASS / COMPLETE`

## 3. Environment

Timing 数字只代表本机 Node/Vitest benchmark 环境，用于相对排序，不作为 SLA。

| Item | Value |
|---|---|
| OS | Windows 10 amd64 |
| CPU | Intel Core i7-10700 |
| Physical cores | 8 |
| Logical cores | 16 |
| RAM | 约 31.7 GiB |
| Node | v22.23.2 |
| npm | 12.0.2 |
| Maven | 3.6.3 |
| Maven Java | 17.0.15 |
| Default `java -version` on PATH | 21.0.7 |

## 4. Current Realtime Architecture

```text
RealtimeView
    ↓
loadRealtimeRowsByContext
    ↓
GET /api/data/realtime
    ↓
RealtimeDataQueryApplicationService.getAllRealtimeData()
    ↓
ConfigManager.getAllDeviceIds()
    ↓
ConfigManager.getDataPoints(device)
    ↓
build all CacheKey
    ↓
cacheManager.getAll(allCacheKeys)
    ↓
per point:
pointRuntimeStateService.snapshot(...)
PointRealtimePayload.fromPoint(...)
applyCachedValue(...)
    ↓
AllDeviceRealtimeDataResponse
    ↓
Jackson JSON serialize
    ↓
HTTP
    ↓
Axios JSON parse
    ↓
normalizeAllDeviceRealtimeRows()
    ↓
normalizeRealtimeRows()
    ↓
realtimeRows.value = rows
    ↓
filteredRealtimeRows
    ↓
buildRealtimeSummary()
    ↓
native <table>
v-for every filtered row
```

Current facts:

- polling: `5000 ms`
- request count: all-device mode uses `1 × GET /api/data/realtime`
- backend cache: single `cacheManager.getAll(allCacheKeys)` bulk cache call
- response: full `AllDeviceRealtimeDataResponse` snapshot
- frontend: full JSON parse + full normalization per refresh
- render: native `<table>` with `v-for` over every filtered row

## 5. Scale Dataset

Synthetic aggregate follows the current real contract:

- aggregate: `status`, `deviceCount`, `dataCount`, `devices[]`, `timestamp`
- device: `status`, `deviceId`, `dataCount`, `data`, `timestamp`
- data: `Record<pointId, PointRealtimePayload>`
- payload width: representative rich point payload, not `{ value: 1 }` only
- additionalConfig: included with typical protocol fields, but not intentionally extreme

| Dataset | Devices | Points/device | Total points |
|---:|---:|---:|---:|
| 10k | 10 | 1,000 | 10,000 |
| 50k | 50 | 1,000 | 50,000 |
| 100k | 100 | 1,000 | 100,000 |

Single-device baseline:

| Dataset | Devices | Points/device | Total points |
|---:|---:|---:|---:|
| single 1k | 1 | 1,000 | 1,000 |
| single 5k | 1 | 5,000 | 5,000 |
| single 10k | 1 | 10,000 | 10,000 |

Benchmark command:

```bash
cd collector-desktop
npx vitest bench src/features/realtime/utils/realtime-scale.bench.ts --run --outputJson <output-json>
```

Benchmark settings:

- benchmark-only fixture: `src/features/realtime/utils/realtime-scale-fixture.ts`
- benchmark file: `src/features/realtime/utils/realtime-scale.bench.ts`
- samples: heavy operations use 8 samples; lighter operations have more samples from Vitest/Tinybench
- reported timing: median ms
- no hard threshold or CI SLA

## 6. Payload Baseline

Raw JSON size is measured by `JSON.stringify(...)` plus UTF-8 byte length. Compression is not measured in this baseline.

| Points | Devices | Raw JSON bytes | MiB / refresh | Bytes / point | JSON stringify median | JSON parse median |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 10 | 15,135,252 | 14.43 MiB | 1,513.5 | 99.27 ms | 74.76 ms |
| 50,000 | 50 | 75,809,163 | 72.30 MiB | 1,516.2 | 558.46 ms | 445.03 ms |
| 100,000 | 100 | 151,651,557 | 144.63 MiB | 1,516.5 | 1,199.98 ms | 858.59 ms |

Five-second polling raw transfer model:

Formula:

```text
MiB / refresh = rawBytes / 1024 / 1024
MiB / second = MiB / refresh / 5
MiB / minute = MiB / second × 60
GiB / hour = rawBytes × (1 / 5) × 3600 / 1024³
```

| Points | MiB / refresh | MiB / second | MiB / minute | GiB / hour |
|---:|---:|---:|---:|---:|
| 10,000 | 14.43 | 2.89 | 173.21 | 10.15 |
| 50,000 | 72.30 | 14.46 | 867.57 | 50.83 |
| 100,000 | 144.63 | 28.93 | 1,735.51 | 101.69 |

Compression note:

- `server.compression.enabled=true`
- mime types include `application/json`
- `server.compression.min-response-size=2048`
- 本轮未测 gzip / compressed wire size；以上均为 raw JSON size。

## 7. Backend Complexity Inventory

GOOD already established in Task 01.4A:

- all-device realtime remains `1` aggregate endpoint call from frontend
- backend aggregate uses `1` bulk cache lookup: `cacheManager.getAll(allCacheKeys)`
- no per-device frontend HTTP fan-out

Current O(P) backend work per aggregate refresh:

| Step | Scale model | 100k implication |
|---|---:|---:|
| `ConfigManager.getAllDeviceIds()` | O(D) | 100 device ids |
| `ConfigManager.getDataPoints(device)` | O(D) calls, O(P) returned points | 100 calls, 100k point configs |
| `buildCacheKeys(deviceId, dataPoints)` | O(P) | ~100k `CacheKey` entries |
| `cacheManager.getAll(allCacheKeys)` | 1 bulk call over P keys | 1 call, 100k keys |
| `pointRuntimeStateService.snapshot(...)` | O(P) | ~100k runtime snapshot accesses |
| `PointRealtimePayload.fromPoint(...)` | O(P) | ~100k DTO payload allocations |
| `payload.applyCachedValue(...)` | O(P) | ~100k value/quality metadata applications |
| Jackson serialize | O(payload bytes) | ~145 MiB raw JSON serialize |

Likely cost candidates:

- Backend CPU / allocation: O(P) payload construction and runtime snapshot access.
- Network / serialization: raw payload width dominates at 50k/100k.
- Allocation pressure candidate: each 100k refresh creates large Maps/Lists, ~100k payload objects, serialized JSON, and later ~100k frontend row objects.

Not claimed:

- No GC bottleneck is confirmed here; profiler evidence is required in a later task.

## 8. Frontend CPU Benchmark

All-device mode:

| Points | Devices | Normalize median | Summary median | Filter no keyword | Filter many matches | Filter zero matches | Device-name fallback filter |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 10 | 31.79 ms | 0.67 ms | ~0.00 ms | 1.63 ms | 3.30 ms | 4.36 ms |
| 50,000 | 50 | 173.73 ms | 4.48 ms | ~0.00 ms | 14.60 ms | 24.25 ms | 31.74 ms |
| 100,000 | 100 | 335.45 ms | 9.69 ms | ~0.00 ms | 32.21 ms | 41.58 ms | 76.41 ms |

Single-device mode:

| Points | Raw JSON bytes | MiB / refresh | Bytes / point | Normalize median | Summary median | Filter many matches | Filter zero matches |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 1,510,526 | 1.44 | 1,510.5 | 2.25 ms | 0.06 ms | 0.12 ms | 0.24 ms |
| 5,000 | 7,565,503 | 7.22 | 1,513.1 | 16.43 ms | 0.29 ms | 0.64 ms | 1.29 ms |
| 10,000 | 15,134,232 | 14.43 | 1,513.4 | 35.87 ms | 0.55 ms | 1.33 ms | 2.93 ms |

Observed behavior:

- `normalizeAllDeviceRealtimeRows()` is O(P) and allocates one normalized row object per point via `Object.entries(...).map(...)`.
- `buildRealtimeSummary()` is O(P), currently implemented as `rows.filter(isGoodQuality).length`.
- `filteredRealtimeRows` no-keyword path returns the current rows reference and is effectively O(1).
- active keyword filtering is O(P) and scans `pointName`, `pointCode`, `address`, and `deviceName` fallback source.
- all-device vs single-device at the same 10k scale is close; the dominant CPU cost is point count and payload shape, not device count alone.

## 9. DOM / Render Baseline

Current rendered-row bound:

```text
Does RealtimeView currently have a bounded rendered-row count?
NO
```

Current implementation:

```vue
<tr v-for="row in filteredRealtimeRows">
```

There is no current pagination, virtualization, windowing, row limit, cursor, or summary-only mode in RealtimeView.

The table has 12 columns. Lower-bound DOM estimate, excluding `strong`, `span`, `code`, `button`, text nodes, Vue component bookkeeping, style/layout/paint cost:

| Points | Rendered rows | `<td>` lower bound |
|---:|---:|---:|
| 10,000 | 10,000 | 120,000 |
| 50,000 | 50,000 | 600,000 |
| 100,000 | 100,000 | 1,200,000 |

Boundary:

- Vitest/Node benchmark measures JSON generation/parse, normalization, summary, and filter CPU.
- It does not measure Chrome DOM render time, Vue patch time, layout, paint, or user input responsiveness.
- DOM/render requires browser profiling in a later task.

## 10. Device Lookup Conditional Hotspot

Current implementation:

```ts
function deviceDisplayName(deviceId: string): string {
  return deviceStore.devices.find((device) => device.normalizedId === deviceId)?.displayName || deviceId || "-";
}
```

Hot paths:

- filter: `row.deviceName || deviceDisplayName(...)`
- table render: `row.deviceName || deviceDisplayName(...)`

Current condition:

- `PointRealtimePayload.fromPoint()` usually includes `deviceName`.
- Therefore this is not always active.
- Correct classification: `CONDITIONAL O(P×D) HOTSPOT` when `row.deviceName` is absent.

Worst-case theoretical upper bound:

- 100k points / 100 devices
- if fallback fires for every row: `100,000 × up to 100 device comparisons ≈ 10,000,000 comparisons / pass`
- if both active filter and render fallback fire, the same class of lookup can occur more than once per refresh / user input change

Synthetic fallback benchmark, zero-match keyword:

| Points | Devices | Median |
|---:|---:|---:|
| 10,000 | 10 | 4.36 ms |
| 50,000 | 50 | 31.74 ms |
| 100,000 | 100 | 76.41 ms |

## 11. DTO Width

Current Java `PointRealtimePayload` fields: `57`.

### Realtime table required / currently used fields

The table and row actions currently use these fields or aliases:

```text
pointId
pointCode
pointName
deviceId
deviceName
dataType
address / registerAddress / pointAddress
readWrite
scalingFactor / scale / factor
value / currentValue / rawValue
unit
timestamp / collectTime / lastUpdateTime
qualityLevel / qualityDescription / quality / qualityCode / status
qualityAvailable
qualityAcceptable
processSuccess
processCostMs / processingTime / costMs / elapsedMs
```

Fields present in Java DTO and used by the current table path:

```text
pointId
pointCode
pointName
deviceId
deviceName
dataType
address
readWrite
scalingFactor
value
rawValue
unit
timestamp
lastUpdateTime
qualityLevel
qualityDescription
quality
status
qualityAvailable
qualityAcceptable
processSuccess
processingTime
```

### Detail-only / not currently required by Realtime table

```text
id
unitId
commonAddress
pointAlias
groupId
offset
deadband
minValue
maxValue
collectionMode
priority
cacheEnabled
cacheDuration
alarmEnabled
createTime
updateTime
precision
remark
additionalConfig
baseCollectionInterval
currentCollectionInterval
minCollectionInterval
maxCollectionInterval
pointChangeThreshold
stableCount
lastValue
changeRate
lastAdjustTime
processedValue
hasCachedValue
processMessage
skipped
processorName
processingTimeAvailable
metadata
```

02.1 conclusion:

- Current aggregate snapshot carries many fields that the realtime table does not need.
- Do not remove or split fields in 02.1.
- This is evidence for a later compact snapshot contract / field selection task.

## 12. Refresh Model

Current model:

```text
FULL SNAPSHOT POLLING
```

- interval: 5 seconds
- every refresh sends full aggregate snapshot
- no `since`, `version`, `cursor`, `delta`, `changedOnly`, `ETag`, `revision`
- frontend replaces `realtimeRows.value` with newly normalized rows

Browser work per full refresh:

| Area | Scale |
|---|---|
| Network | O(P), proportional to payload bytes |
| JSON parse | O(payload bytes) |
| Normalize | O(P), new row object graph |
| Filter | O(P) when keyword active |
| Summary | O(P) |
| Vue reactive replacement | O(P)-scale object graph |
| DOM patch/render | up to O(P), currently unbounded |

Five-second effect at 100k:

- raw payload: 144.63 MiB / refresh
- JSON stringify median: 1,199.98 ms
- JSON parse median: 858.59 ms
- normalize median: 335.45 ms
- active filter zero-match median: 41.58 ms
- DOM lower bound: 100k rows + 1.2m cells

## 13. Hotspot Ranking

### P0

1. Unbounded native table DOM rendering
   - Evidence: rendered rows equal `filteredRealtimeRows.length`; no row bound.
   - Scale: 100k rows and at least 1.2m `<td>` nodes before nested nodes.
   - Why it matters: payload/CPU optimizations cannot make a browser smoothly display 100k native table rows without bounding render work.
   - Recommended next task: `02.2 Frontend Realtime Render Bounding & Lookup Optimization`.

2. Full rich payload snapshot every 5 seconds
   - Evidence: synthetic representative aggregate is 144.63 MiB raw JSON at 100k, about 101.69 GiB/hour raw if refreshed every 5 seconds.
   - Scale: raw bytes per point about 1.5 KiB with current rich DTO shape.
   - Why it matters: even before DOM, serialization + parse + network payload dominate the refresh cycle.
   - Recommended next task: `02.3 Compact Realtime Snapshot Contract`.

### P1

1. JSON serialize / parse cost
   - Evidence: 100k JSON stringify median 1,199.98 ms; JSON parse median 858.59 ms in Node benchmark.
   - Scale: O(payload bytes).
   - Why it matters: consumes a large portion of a 5-second polling window before frontend logic/render.
   - Recommended next task: compact payload and later browser profiling.

2. Frontend normalization allocation
   - Evidence: 100k `normalizeAllDeviceRealtimeRows()` median 335.45 ms; creates new rows per refresh.
   - Scale: one normalized row allocation per point.
   - Why it matters: adds recurring CPU and allocation pressure after JSON parse.
   - Recommended next task: render bounding first, then normalize/row identity optimization.

3. Backend per-point DTO/runtime work
   - Evidence: current code does `snapshot + fromPoint + applyCachedValue` per point despite single bulk cache lookup.
   - Scale: ~100k payload objects and runtime snapshot accesses per 100k refresh.
   - Why it matters: backend can become CPU/allocation bottleneck after request-count N+1 has been removed.
   - Recommended next task: compact response and backend allocation profiling.

4. Conditional `deviceDisplayName()` O(P×D)
   - Evidence: fallback uses `deviceStore.devices.find(...)`; benchmark mirror is 76.41 ms at 100k/100 when `deviceName` is missing.
   - Scale: worst case about 10m device comparisons per pass.
   - Why it matters: normally masked by `deviceName`, but can regress sharply if compact DTO omits names without adding a lookup map.
   - Recommended next task: include lookup-map rule in 02.2 / 02.3.

### P2

1. Summary and active keyword filter O(P)
   - Evidence: 100k summary median 9.69 ms; active filter median 32.21–41.58 ms in Node.
   - Scale: O(P) per recompute / keyword change.
   - Why it matters: not the top bottleneck relative to DOM and payload, but user search at 100k still has visible CPU cost.
   - Recommended next task: evaluate after render bounding and compact payload.

2. Existing soak scripts are not UI realtime baseline
   - Evidence: `scripts/soak/**` targets telemetry pipeline, Redis/TDengine/MQTT/outbox/ACK soak.
   - Scale: useful for backend pipeline later, not a substitute for RealtimeView UI payload/DOM baseline.
   - Recommended next task: keep separate from UI scale benchmark.

## 14. Priority: DOM vs Payload vs Backend CPU

Priority 1: Browser DOM/render bounding

- Evidence: 100k rows implies at least 1.2m `<td>` nodes plus nested nodes, and RealtimeView has no rendered-row bound.
- Reason: without bounding DOM, 100k full table rendering is structurally unsafe regardless of backend/cache improvements.

Priority 2: Full snapshot payload / compact realtime contract

- Evidence: 100k aggregate is 144.63 MiB raw per refresh; JSON stringify median 1.20s and parse median 0.86s; 5-second raw transfer model is ~101.69 GiB/hour.
- Reason: after render is bounded, network/serialization/parse become the next largest recurring cost.

Priority 3: Backend per-point allocation and change-aware refresh

- Evidence: backend request count and cache lookup are already fixed, but still does O(P) runtime snapshot + DTO allocation + full serialize every 5 seconds.
- Reason: should be optimized after agreeing the compact/delta contract, otherwise backend optimization may preserve an oversized response shape.

## 15. Proposed Task 02 Roadmap

- 02.2: Frontend Realtime Render Bounding & Lookup Optimization
  - Bound rendered rows without changing backend contract.
  - Add device-name lookup map before any compact response can remove `deviceName` safely.
  - Preserve latest-request-wins and HTTP fallback.
- 02.3: Compact Realtime Snapshot Contract
  - Design table-focused compact payload / field selection from DTO-width evidence.
  - Keep existing rich/detail path separate and backward compatible.
- 02.4: Change-Aware Realtime Refresh
  - Evaluate `since/version/cursor/delta/changedOnly/ETag/revision` style refresh after compact contract exists.
  - Do not conflate with backend WebSocket implementation unless explicitly scoped.
- 02.5: Large-Scale Realtime Regression & Soak
  - Add repeatable browser profiling / real backend scale smoke around agreed 10k/50k/100k budgets.
  - Keep telemetry pipeline soak separate from UI realtime scale baseline.

## 16. Regression Verification

本轮只新增 benchmark-only TypeScript fixture/bench 与文档，没有修改 production Java、production Vue、production TypeScript、API contract 或运行行为。

| Check | Command | Result |
|---|---|---|
| Frontend typecheck | `npm --prefix collector-desktop run typecheck` | PASS |
| Frontend regression tests | `npm --prefix collector-desktop test` | PASS, 65 files / 449 tests |
| Frontend build | `npm --prefix collector-desktop run build` | PASS |
| Frontend build:web | `npm --prefix collector-desktop run build:web` | PASS, 55 files synced |
| Frontend verify | `npm --prefix collector-desktop run verify` | PASS |
| Realtime scale benchmark | `npx vitest bench src/features/realtime/utils/realtime-scale.bench.ts --run --outputJson <output-json>` | PASS |
| Java related test | `cmd.exe /c mvn -B -ntp -DforkCount=0 -pl collector-application -am test` | PASS, 141 tests |
| Git diff check | `git diff --check` | PASS |

Benchmark output was written to a local temp file and is intentionally not committed.

## 17. Acceptance Checklist

- [x] 10k baseline measured
- [x] 50k baseline measured
- [x] 100k baseline measured
- [x] payload bytes measured
- [x] bytes / point calculated
- [x] 5-second bandwidth calculated
- [x] normalize benchmark measured
- [x] summary benchmark measured
- [x] filter cost measured and modeled
- [x] single-device scale measured
- [x] backend complexity documented
- [x] full snapshot refresh model documented
- [x] current DTO width documented
- [x] RealtimeView required field subset documented
- [x] DOM row count documented
- [x] DOM cell lower bound documented
- [x] `deviceDisplayName` conditional P×D documented
- [x] Node benchmark vs browser render boundary documented
- [x] P0/P1/P2 hotspot ranking created
- [x] Priority 1/2/3 given
- [x] no premature optimization implemented
- [x] npm test passed
- [x] npm verify passed
- [x] diff check passed

## 18. Task 02.2 RESOLVED

02.2 只修复前端 DOM 渲染边界和设备名称查找热点，没有修改后端协议、payload contract、delta/WebSocket 或依赖。

### Before

- `rendered rows = filteredRealtimeRows.length`
- 100k 场景：`100,000 tr`，`1,200,000 td` 下限
- `deviceDisplayName()` 使用 `deviceStore.devices.find(...)`
- fallback 热点为 `CONDITIONAL O(P×D)`

### After

- `rendered rows = pagedRealtimeRows.length`
- 默认每页 `200`，最大 `500`
- `pagedRealtimeRows.length <= 500`
- 默认 DOM 上限：`<= 200 tr` / `<= 2,400 td`
- 最大 DOM 上限：`<= 500 tr` / `<= 6,000 td`
- `deviceDisplayNameLookup = computed(() => buildRealtimeDeviceNameLookup(deviceStore.devices))`
- `deviceDisplayName()` 现在走 `Map.get(...)`
- 查找复杂度：build `O(D)`，resolve `O(1)`

### Post-02.2 benchmark notes

| Item | Median |
|---|---:|
| old 100k fallback linear | `76.41 ms` |
| new 100k fallback map | `42.50 ms` |
| 100k render window slice pageSize=500 | `0.0003 ms` |

注：render window slice 只衡量 JS slice / window helper，不代表浏览器 DOM 渲染时间。

### Residual risks after 02.2

- raw payload 100k 仍是 `151,651,557` bytes / `144.63 MiB`
- stringify / parse / normalize / filter / summary / backend per-point allocation 仍然存在
- 这些进入 `Task 02.3+`

### 02.2 checklist

- [x] render uses paged/windowed rows
- [x] default rendered rows <= 200
- [x] maximum rendered rows <= 500
- [x] no "show all" option
- [x] full realtimeRows preserved
- [x] filtering happens before pagination
- [x] summary uses full filtered results
- [x] keyword / device / page-size change resets page 1
- [x] same-context timer refresh preserves page if valid
- [x] refresh clamps page if dataset shrinks
- [x] paging causes zero HTTP requests
- [x] single-device mode also bounded
- [x] row object identity is preserved
- [x] row key is stable, not page index
- [x] deviceStore.devices.find removed from realtime hot path
- [x] device display lookup uses computed Map
- [x] lookup build = O(D)
- [x] lookup resolve = O(1)
- [x] no per-row Map rebuild
- [x] no 100k decorated row copies
- [x] request lifecycle unchanged
- [x] polling remains 5000 ms
- [x] backend contract unchanged
- [x] no compact DTO yet
- [x] no delta yet
- [x] no WebSocket backend
- [x] no dependency added
- [x] 100k structural render test passes
- [x] lifecycle regression passes
- [x] typecheck passes
- [x] tests pass
- [x] build passes
- [x] build:web passes
- [x] verify passes

## 19. Task 02.3 RESOLVED

02.3 新增实时表格专用 compact RAW contract，并让 `RealtimeView` 通过 load strategy 使用 compact endpoints。原 rich endpoints 继续保留给 `PointEditor`、`RealtimeDataPanel`、单点查询和详情消费方。

### R1 deviceId hardening

compact row 的 `deviceId` 现在由本次查询上下文提供，而不是依赖 `DataPoint.deviceId`。这样即使配置对象中的 `deviceId` 为空或脏值，`rows[].deviceId` 仍然保持当前设备上下文，且 compact 查询不会修改 `DataPoint`。

### Compact contract

| Endpoint | Response | Notes |
|---|---|---|
| `GET /api/data/realtime/compact` | `CompactAllDeviceRealtimeDataResponse` | flat `rows[]` + lightweight `devices[]` |
| `GET /api/data/device/{deviceId}/compact` | `CompactDeviceRealtimeDataResponse` | single-device `rows[]` |

Compact point fields:

```text
pointId, pointCode, pointName,
deviceId,
dataType, address, readWrite, scalingFactor, unit,
value,
status,
quality, qualityDescription, qualityLevel, qualityAcceptable, qualityAvailable,
processSuccess, processingTime,
lastUpdateTime
```

Omitted from compact point:

```text
deviceName,
rawValue, processedValue, lastValue,
additionalConfig, metadata,
currentCollectionInterval, stableCount, changeRate, lastAdjustTime,
createTime, updateTime, remark, processorName
```

### Backend complexity after compact

| Item | Rich path | Compact path |
|---|---|---|
| Cache lookup | `1 × cacheManager.getAll(allCacheKeys)` | `1 × cacheManager.getAll(allCacheKeys)` |
| Runtime snapshot | `P × pointRuntimeStateService.snapshot(...)` | `0 × runtime snapshot` |
| Point payload | `P × PointRealtimePayload` | `P × CompactRealtimePointPayload` |
| Serialization | rich DTO bytes | compact DTO bytes |

Compact path 仍为 full snapshot polling，只降低每轮 payload width 和 runtime snapshot allocation，不宣称 GC 或浏览器渲染耗时已解决。

### Post-02.3 compact payload benchmark

Benchmark command:

```bash
cd collector-desktop
npx vitest bench src/features/realtime/utils/realtime-scale.bench.ts --run --outputJson <temporary-output-json>
```

| Points | Rich MiB | Compact MiB | Reduction | Rich parse | Compact parse |
| -----: | -------: | ----------: | --------: | ---------: | ------------: |
| 10k | 14.43 | 4.19 | 70.97% | 74.76 ms | 15.41 ms |
| 50k | 72.30 | 20.95 | 71.02% | 445.03 ms | 104.48 ms |
| 100k | 144.63 | 41.90 | 71.03% | 858.59 ms | 208.39 ms |

| Points | Rich bytes | Compact bytes | Compact bytes/point | Rich stringify reference | Compact stringify |
| -----: | ---------: | ------------: | ------------------: | ----------------------: | ----------------: |
| 10k | 15,135,252 | 4,393,516 | 439.35 | 99.27 ms | 17.85 ms |
| 50k | 75,809,163 | 21,967,169 | 439.34 | 558.46 ms | 107.61 ms |
| 100k | 151,651,557 | 43,934,239 | 439.34 | 1,199.98 ms | 202.90 ms |

100k compact raw payload reduction is `71.03%`, satisfying the deterministic acceptance target `>=40%`.

Compact row extraction keeps `response.rows` references and is `O(1)`:

| Points | Row extraction median |
| -----: | --------------------: |
| 10k | 0.0001 ms |
| 50k | 0.0001 ms |
| 100k | 0.0001 ms |

注：上述 timing 数字来自 Node/Vitest benchmark，不代表浏览器 DOM 渲染时间。

### Five-second raw network model after compact

| Dataset | MiB/refresh | MiB/s | MiB/min | GiB/hour |
|---:|---:|---:|---:|---:|
| 100k rich baseline | 144.63 | 28.93 | 1,735.56 | 101.69 |
| 100k compact | 41.90 | 8.38 | 502.79 | 29.46 |

The raw 100k hourly model drops from `101.69 GiB/hour` to `29.46 GiB/hour`, while `FULL SNAPSHOT POLLING` remains open for 02.4.

### 02.3 verification targets

- [x] rich endpoints remain separate and backward compatible
- [x] rich `PointRealtimePayload` remains unchanged
- [x] compact aggregate endpoint added as RAW DTO
- [x] compact device endpoint added as RAW DTO
- [x] compact point omits `deviceName`, `additionalConfig`, `metadata` and adaptive runtime fields
- [x] compact aggregate uses flat `rows[]` and lightweight `devices[]`
- [x] all-device compact keeps one bulk cache lookup
- [x] compact path does not call `pointRuntimeStateService.snapshot(...)`
- [x] `ProcessResult` value / quality / processing time / collect time semantics are preserved
- [x] plain cached value uses `value=cachedValue` and `qualityAvailable=false`
- [x] RealtimeView all/device mode uses compact API through `loadRealtimeRowsByContext`
- [x] row extraction returns original row references
- [x] 02.2 filter, summary, lifecycle and render-window tests remain covered
- [x] no delta, WebSocket or dependency change introduced

## 20. Task 02.4 RESOLVED

02.4 在 compact RAW contract 上继续保留 5 秒 HTTP polling，但将普通 timer refresh 改为 cursor-based delta 查询。full compact snapshot 仍是正确性权威，delta 只是低变化率场景下减少 network / JSON / frontend merge work 的优化。

### Change-aware architecture

```text
first load / manual / device change / reset / periodic safety
  -> FULL compact snapshot
     capture(snapshotId, configEpoch, revision) before cache read
     return rows[] + cursor

normal timer with valid cursor
  -> DELTA compact rows
     validate snapshotId + configEpoch + sinceRevision
     scan tracker point states
     read changed cache keys only
     merge by deviceId + pointId index
```

Tracker state:

```text
snapshotId: one UUID per Spring Boot process
configEpoch: starts at 1, increments on ConfigUpdateEvent
revision: global AtomicLong
pointStates: PointKey(deviceId, pointId) -> latest semantic fingerprint + latestRevision
```

The tracker stores no `CompactRealtimePointPayload`, rich payload, `ProcessResult` clone, JSON snapshot, browser session state, Redis stream cursor, database changelog, or previous per-client response.

### Fingerprint semantics

Plain cached value:

- includes Java value type and content
- same value repeated does not increment revision
- changed value increments revision
- arrays, collections and maps use deterministic content-aware traversal rather than object identity hash

`ProcessResult` cached value includes compact-visible dynamic fields:

```text
finalValue,
quality,
qualityDescription,
qualityLevel,
qualityAcceptable,
success,
processingTime,
COLLECT_TIME
```

`COLLECT_TIME` is intentionally part of the fingerprint. If a collector writes a new `ProcessResult` with the same value but a changed collect time, the compact row changed semantically and the tracker increments revision. High-change/all-points-changing scenarios may therefore correctly fall back to full compact.

### Delta endpoints

| Endpoint | Params | Response |
|---|---|---|
| `GET /api/data/realtime/compact/delta` | `snapshotId`, `configEpoch`, `sinceRevision` | `CompactRealtimeDeltaResponse` RAW DTO |
| `GET /api/data/device/{deviceId}/compact/delta` | `snapshotId`, `configEpoch`, `sinceRevision` | `CompactRealtimeDeltaResponse` RAW DTO |

Reset conditions return HTTP 200 RAW DTO with `status=success`, `resetRequired=true`, and empty `rows[]`:

```text
SNAPSHOT_MISMATCH
CONFIG_CHANGED
CURSOR_INVALID
DELTA_TOO_LARGE
ROW_IDENTITY_MISMATCH
```

### Backend complexity after 02.4

| Mode | Configured points | Tracker work | Cache read | Payload build | Runtime snapshot |
|---|---:|---:|---:|---:|---:|
| Full compact | P | capture O(1) | `1 × getAll(P keys)` | O(P) | 0 |
| Delta C | P | scan O(P) point states | `1 × getAll(C keys)` | O(C) | 0 |

Delta intentionally does not preserve revision history. Each point only keeps latest fingerprint/revision because the client only needs to know whether the row changed since its cursor. The current backend still scans O(P) tracker states to select changed keys; it no longer reads all cache values or builds all payload rows when `C` is small.

### Frontend behavior after 02.4

- initial load: full compact
- timer with valid cursor: delta compact
- manual refresh: full compact
- device context change: clear cursor/index and full compact
- periodic safety resync: full after 12 successful delta cycles, about 60 seconds at 5 seconds polling
- empty delta: preserves `realtimeRows` array reference and row object references; advances cursor/cycle only
- changed delta: uses stable `Map(deviceId + pointId -> index)` from the last full snapshot and replaces only changed array slots
- unknown delta row: triggers full resync instead of `push()`
- delta HTTP failure: keeps existing rows and cursor, shows refresh error, and retries from same cursor later
- stale delta/reset/full race: latest-request-wins/context snapshot still protects commits and loading ownership
- pagination: default 200 / max 500 unchanged

### Post-02.4 delta benchmark

100k compact full baseline remains:

```text
43,934,239 bytes
41.90 MiB / refresh
```

Delta payload benchmark uses the same compact row fixture and a synthetic RAW delta DTO. Timing is Node/Vitest median ms and is not browser DOM time.

| 100k scenario | Changed rows | Mode | Raw bytes | MiB | % of full compact | JSON stringify | JSON parse |
|---:|---:|---|---:|---:|---:|---:|---:|
| 0% | 0 | delta | 187 | 0.0002 | 0.0004% | 0.0008 ms | 0.0010 ms |
| 1% | 1,000 | delta | 418,463 | 0.40 | 0.95% | 1.75 ms | 1.48 ms |
| 10% | 10,000 | delta | 4,182,924 | 3.99 | 9.52% | 18.17 ms | 15.53 ms |
| 20% | 20,000 | delta | 8,365,657 | 7.98 | 19.04% | 37.70 ms | 33.39 ms |
| 50% | 50,000 | full fallback | 43,934,239 | 41.90 | 100.00% | full selected | full selected |
| 100% | 100,000 | full fallback | 43,934,239 | 41.90 | 100.00% | full selected | full selected |

The configured hard cap is `MAX_DELTA_ROWS=20,000`, so 50% and 100% 100k scenarios choose full compact rather than constructing an oversized delta.

### Delta apply benchmark

Index-based merge benchmark over a 100k base array:

| Changed rows | Apply median |
|---:|---:|
| 100 | 0.47 ms |
| 1,000 | 1.59 ms |
| 10,000 | 8.29 ms |

The benchmark applies changes through the stable row identity `Map` and does not rebuild a `Map(realtimeRows.map(...))` every delta cycle.

### 02.4 verification targets

- [x] full compact endpoints remain
- [x] rich endpoints unchanged
- [x] snapshotId/configEpoch/revision added to full compact responses
- [x] tracker memory is O(P), not O(P × clients)
- [x] no per-client server snapshot
- [x] semantic fingerprint implemented and tested
- [x] same plain value does not revision++
- [x] changed plain value revision++
- [x] `ProcessResult` compact-visible changes revision++
- [x] `COLLECT_TIME` change revision++
- [x] failed cache write does not mark revision delivered
- [x] tracker failure cannot break telemetry cache stage
- [x] config event invalidates epoch and clears point states
- [x] full captures marker before cache read
- [x] delta aggregate/device endpoints exist
- [x] delta is RAW DTO
- [x] snapshot/config/invalid revision reset paths covered
- [x] small delta reads only changed cache keys and uses one bulk `getAll`
- [x] delta runtime snapshot count is zero
- [x] missing config forces full resync
- [x] too-large delta forces full resync
- [x] frontend first/manual/context/safety full paths covered
- [x] timer delta path covered
- [x] empty delta preserves rows and row object references
- [x] changed delta replaces only changed slots through stable index Map
- [x] unknown row triggers full resync
- [x] delta failure retains old rows/cursor
- [x] stale delta and reset/full race protected by request ownership
- [x] render bound, filtering and summary semantics preserved
- [x] 1% / 10% / 20% delta measured
- [x] 50% / 100% full fallback recorded

## 21. Task 02.4-R1 — Epoch Boundary Closure

### Before

- Delta validated `configEpoch=1` at query start.
- A `ConfigUpdateEvent` during the same query could advance the tracker to `configEpoch=2`.
- Success response assembly could accidentally read the newer tracker state and return `resetRequired=false` with the wrong epoch.
- Client cursor could be upgraded to the new epoch while still holding old rows.

### After

- One Delta query captures one immutable `SnapshotCursor` boundary.
- Cursor validation is tied to that same boundary.
- `upperRevision` always comes from `boundary.revision`.
- Success responses always reuse the original boundary cursor:
  - `snapshotId = boundary.snapshotId`
  - `configEpoch = boundary.configEpoch`
  - `revision = boundary.revision`
- A config change during the query now resolves to `resetRequired=true` and discards any already-built rows.
- A config change after the final compatibility check remains safe: the response keeps the old boundary, and the next poll detects `CONFIG_CHANGED` and performs full resync.

### Regression coverage

- empty delta race -> `CONFIG_CHANGED` reset
- non-empty delta race -> `CONFIG_CHANGED` reset with already-built rows discarded
- revision-only advance -> success with boundary revision unchanged
- tracker boundary helper -> snapshot/config boundary remains valid while revision advances

### Verification note

- `realtime-scale.bench.ts` reran after this fix; baseline benchmark behavior remained intact.
