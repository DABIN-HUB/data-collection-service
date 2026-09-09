# Task 03.1 — Page State & Failure UX Baseline

Date: 2026-09-09
Branch: `feature_2.0`
Revision audited: `ad3100f`
Scope: frontend routed-page audit/inventory only. Production Vue/TS/Java behavior diff: `0`.

Task 01 and Task 02 are treated as accepted baselines. This document does not redesign request ownership, realtime cursor/delta, compact realtime contract, realtime pagination, or Task 01.5 History/Alarm/Dashboard correctness. It records current page-state/failure UX and prioritizes Task 03 follow-up work.

## Task 03.2 RESOLVED — Shadow Context Ownership & Last-Good State

- Date: 2026-09-09.
- Scope: `collector-desktop/src/features/shadow/components/ShadowPanel.vue` and Shadow-only lifecycle helpers/tests.
- Production backend diff: `0`; HTTP path/DTO/API contract unchanged.
- Shadow wrong-context P0: CLOSED.

### Before / After

Before:

```text
A request starts
→ user switches to B
→ A response overwrites B ShadowPanel state
```

After:

```text
request captured A
live context=B
canCommit=false
A response is discarded from B UI
```

Write side effects are not cancelled or retried. Only the returned write response/error/post-state is guarded from committing into a different live device panel.

### Last-Good behavior

Before:

```text
shadow refresh failure → shadow={error}
delta refresh failure → delta={error}
history refresh failure → rows=[]
```

After:

```text
same-context refresh failure
→ last-good shadow/delta/history rows retained
→ persistent section error/stale marker shown
```

Success with `history=[]` remains the normal `EMPTY` state; failure with no last-good rows remains `ERROR` and no longer looks like success-empty.

## 1. State vocabulary

| State | UX meaning | Recommended presentation |
| --- | --- | --- |
| `IDLE` | Page/control is available but no read/action has been requested for the current context. | Show explicit prompt such as “select a device” or “click to query”. |
| `INITIAL_LOADING` | Current context has no successful data yet and a request is in-flight. | Skeleton/loading placeholder; not an empty-success state. |
| `READY` | Current context has successful data and no known refresh failure. | Display current data and normal controls. |
| `EMPTY` | Request succeeded and effective result count is zero. | Explicit empty business result; do not use error styling. |
| `REFRESHING` | Last-good data exists and a new read request is in-flight. | Keep old data visible and show a lightweight updating indicator. |
| `STALE` | Last-good data exists and the latest refresh failed. | Keep old data visible and persistently explain that it is not latest. |
| `DEGRADED` | Page has multiple independent data sources and only some failed. | Preserve successful sections and show source-level warning. |
| `ERROR` | Current context has no usable data and a critical request failed. | Persistent inline error plus retry/manual refresh; never toast-only. |

These terms are an audit language, not a requirement that every page must implement a single eight-state enum.

## 2. Page matrix

| Page | Initial Loading | Ready | Empty | Refreshing | Stale | Degraded | Error | Retry | Action Pending |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Login | Form visible; `testing` drives button loading during test/login (`views/auth/LoginView.vue:88-102`). | Connected message stored inline via `message` (`:24`, `:93-95`). | N/A; login has no row data. | Same `testing` flag blocks submit/test while preserving form input. | N/A; no last-good read dataset. | N/A. | Inline `el-alert` with connection/server error (`:24`, `:96-100`). | Test connection/login button retries current URL/token. | `testing` guards both test and enter buttons; token field preserved. |
| Dashboard | `dashboardLoading` with existing layout and per-source idle/default values (`views/dashboard/DashboardView.vue:17-18`, `:385-425`). | Multi-widget dashboard from successful source values. | Recent alarms/risk devices use distinct empty text (`:41`, `:61`, `:257-266`). | Per-source states go `loading` without clearing committed values (`features/dashboard/utils/dashboard-metric-state.ts:38-47`, `views/dashboard/DashboardView.vue:394-415`). | Source-level stale when `status=error && lastSuccessAt!=null` (`dashboard-metric-state.ts:54-59`). | PASS: failed source names become `dashboardPartialWarning` while successful cards remain (`DashboardView.vue:409-415`). | Fatal only when all sources fail or init fails (`:409-419`). | “刷新全部” retries latest cycle. | Open-local-editor save triggers reload; no broad write workflow in page body. |
| Device List | Device cards area shows computed loading text only when list is empty (`features/device/utils/device-list-utils.ts:15-18`). | Device cards with filters/actions. | Distinguishes no config vs no filter match (`device-list-utils.ts:18`). | `deviceStore.refresh()` preserves existing `devices` on read failure (`stores/device.store.ts:37-75`); refresh button itself has no spinner. | Read failure preserves old store devices and `deviceStore.error`, but page mainly uses toast/actions; stale timestamp not surfaced. | Runtime metrics are optional inside store; runtime failure keeps previous runtime map (`device.store.ts:50-55`). | If no last-good devices, exact-empty shows error text (`DeviceListView.vue:39`, `device-list-utils.ts:7-14`). | Refresh list/manual sync available. | Store-level `operating` globally disables start/stop/sync; config action uses per-device id; import/export have own flags. |
| Device Workbench | Shell shows selected-device prompt/stats; initial device refresh + realtime preview (`features/device/components/DeviceOperationShell.vue:106-112`). | Selected device panel and child config/control/shadow content. | No selected device is explicit in rail (`:7`, `:39`). | Realtime preview refresh has no visual loading; previous preview retained until failure/success. | Preview failure clears preview rows silently (`:155-174`); selected device itself remains. | Child panels independent; no page-level degraded banner. | Device refresh errors are inherited from store but shell has no persistent inline read error. | Back/list/tab nav; child panels own retry buttons. | Config refresh/clear guarded per `deviceConfigOperatingId`; start/stop in wrapper are not per-target guarded here. |
| Collection | Overview stays visible; `collectionLoading` disables refresh button (`views/collection/CollectionView.vue:9`, `:115-127`). | Summary + protocol table + ConfigOpsPanel. | Protocol table has explicit empty state (`:39`). | Uses `Promise.allSettled`; successful sections remain available (`:115-127`). | Config summary failure sets `configSummary=null`; device/protocol store errors are shown via aggregate alert (`:90`, `:129-136`). | PASS for read split: device/protocol/summary failures are aggregated as warning. | Inline warning `el-alert` (`:14`). | “刷新概览” retries all reads. | ConfigOpsPanel has import/export/sync flags and toast feedback; initial sync hint is optional P2 (`features/collection/components/ConfigOpsPanel.vue:100`). |
| Control | Wrapper shell selects device, then `ControlPanel` is idle with JSON forms (`views/control/ControlView.vue:1-12`, `features/control/components/ControlPanel.vue:72-82`). | Result JSON shows latest command result. | Not row-based; empty means no command yet. | N/A for read; write pending per command. | N/A. | N/A. | Write/parse failures persist in result JSON plus toast (`ControlPanel.vue:154-158`). | Manual resubmit only; no blind auto retry. | Per-action pending exists, but write results have no target-device commit guard if route/device changes while the write is in-flight (`ControlPanel.vue:91-97`, `:114-120`, `:137-143`) — P1. |
| Realtime | Initial full uses `loading` and empty table prompt; table remains rendered (`views/realtime/RealtimeView.vue:9`, `:100-101`, `:216-276`). | Compact full/delta rows, summary, filter and paged table. | Empty table text is generic select-all/select-device prompt (`:100-101`), not context-specific success-empty. | Timer/manual set page-level `loading`; rows are not cleared before request, so last-good rows remain (`:216-276`). | On refresh failure rows remain and `realtimeError` is inline (`:31`, `:266-271`). | Reset/full resync is treated as refresh path, not separate UX. | Initial failure produces empty prompt + small inline error; no dedicated retry panel. | “立即刷新”; auto timer. | Single-point query has separate loading/error and owner (`:297-324`). |
| History | Initial selected device/point loading clears current query data on context change (`views/history/HistoryView.vue:301-337`). | Chart/table/summary from main history. | Main table says “暂无历史数据”; chart prompt says select device/point (`:91`, `:149`). | Query loading uses latest owner but replaces main state with current query result after settle (`:385-440`). | For same-context refresh failure, main failure state clears rows (`:465-476`); no last-good retention. | PASS for optional compare/related alarms: partial warning and unavailable marker (`:404-426`, `:448-455`). | Persistent `el-alert` for main error (`:61`, `:423-435`). | Query/refresh button retries current context. | Export disabled while loading or no series; no destructive writes. |
| Alarm | Initial list load has latest owner and inline table empty/error text (`views/alarm/AlarmView.vue:202-246`). | Alarm rows with acknowledgement state. | Empty row text differentiates `error || '暂无符合条件的告警历史'` (`:66-67`). | Load sets `loading` but does not clear old rows until success-empty or failure (`:202-246`). | Refresh failure clears alarms (`:234-236`), so no last-good alarms after failed same-context refresh. | PASS for acknowledgement status degradation: `ackStatusWarning` + row presentation (`:380-421`). | Persistent table text via `error`, plus warning for ack status (`:40`, `:66-67`). | Refresh button; manual ack status retry. | Ack button guarded by `acknowledgingAlarmId`; bulk ack-status guarded by `ackStatusLoading`. |
| Cloud | Initial load button shows “刷新中…”; layout remains with UNKNOWN defaults (`views/cloud/CloudView.vue:9`, `:81-112`). | Cloud metrics loaded and timestamp updated. | Empty/default computed rows show unknown/zero-like operational state; no explicit disabled/empty banner. | Existing `reportMetrics` is not cleared before refresh; last-good visible during request. | Refresh failure preserves last-good metrics and shows inline `cloud-error`, but no stale timestamp semantics (`:15`, `:101-112`). | Cloud utils show status/risk rows, but read source is single aggregate; disabled/degraded/error distinction depends on payload. | If initial failure, inline error plus unknown/default cards. | Refresh link retries aggregate metric. | No writes on this page. |
| Diagnostic | Initial run sets `loading`, keeps default/raw panels visible (`views/diagnostic/DiagnosticView.vue:148-197`). | Diagnostic cards/rows/raw JSON from successful sources. | No explicit “no diagnostic rows” row, but rows are computed from defaults. | Re-run uses `Promise.allSettled`; successful source refs are updated, failures leave previous values. | Partial source failures preserve previous values implicitly, with `partialWarning`; source-level stale timestamps not shown. | PASS: one failed probe does not fail whole page (`:161-191`). | Fatal only if all metrics and device list fail (`:192-194`); persistent inline message. | “运行完整诊断” retries all probes. | Diagnostic package export has `exporting` guard; sample log/alarm failures are intentional best-effort empty arrays (`:233-247`). |
| Log | Initial load uses `loading`; empty panel displays `error || no logs` (`views/log/LogView.vue:133-159`). | Log rows, local filters and summary. | Success-empty shows “当前条件下没有可显示日志” (`:55`). | Auto/manual refresh uses latest owner and timer overlap skip (`:133-159`, `:224-233`). | Refresh failure clears `logs` (`:148-153`), losing last-good logs. | Recent-exception lookup is optional; failure is toast-only and does not affect log rows (`:175-205`). | Initial failure is persistent through empty panel text, but visually shares empty-state component (`:55`, `:152-153`). | Query/refresh and auto-refresh. | Export and exception lookup have independent guards. |
| Network | Page is idle by default; no automatic diagnostic request (`views/network/NetworkView.vue:123-130`). | Latest diagnostic result and history visible. | Explicit “尚未执行网络检测” and “暂无网络检测历史” (`:51`, `:76-77`). | Running a diagnostic keeps previous result until replaced by result/failure. | Failure is represented as a failed diagnostic result appended to history, not a separate page stale state (`:150-170`). | EdgeTelemetryPanel is independent operational subpanel. | Network diagnostic failures persist in result JSON/history plus toast (`:155-170`). | Manual “开始检测”. | PASS: diagnose and edge telemetry each have own pending flags; result is target-specific enough for manual diagnostics. |
| Shadow | Wrapper shell plus `ShadowPanel`; idle prompts for selected device (`views/shadow/ShadowView.vue:1-12`, `features/shadow/components/ShadowPanel.vue`). | Shadow/delta/history sections independently loaded with independent read owners. | History success `[]` remains “暂无影子历史”; initial failure shows read-failure text. | Section-level loading flags; stale request finally cannot clear newer section loading. | Same-context refresh failure keeps last-good shadow/delta/history and shows persistent stale/error status. | Bundle read still uses `Promise.allSettled`, so one section failure does not block others. | Section status text distinguishes initial error from stale last-good failure. | Per-section read buttons. | Desired save/clear capture target `deviceId`; write side effect completes, but stale response/error cannot overwrite another live device panel — P0 CLOSED. |

## 3. Failure matrix

| Page | Operation | Failure Today | Last-Good Preserved | Inline Error | Toast | Retry | Severity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Login | `testServerConnection` / `runtimeStore.refresh` | Sets `runtimeStore.error` and inline alert (`LoginView.vue:88-102`). | Form values preserved. | Yes. | No. | Test/login button. | PASS |
| Dashboard | multi-source refresh | Source failure recorded; all-source failure becomes fatal (`DashboardView.vue:394-419`). | Yes per source. | Yes. | Only protocol open-local-editor path uses toast. | Refresh all. | PASS |
| Device List | `deviceStore.refresh` | Store sets `error`; list remains if previous devices exist (`device.store.ts:37-75`). | Yes. | Only when list empty; otherwise no stale banner. | Some actions toast store error. | Refresh. | P2 |
| Device Workbench | realtime preview | Catch clears preview rows silently (`DeviceOperationShell.vue:155-174`). | No for preview. | No. | No. | Implicit via route/refresh config; no preview retry. | P2 |
| Collection | device/protocol/config summary | `Promise.allSettled`; aggregate warning from store/config errors (`CollectionView.vue:90`, `:115-136`). | Mixed: config summary reset, device/protocol store preserve according to store. | Yes. | ConfigOpsPanel actions toast. | Refresh overview. | PASS/P2 |
| Control | write single/batch/command | Result JSON becomes `{ error }`, toast error (`ControlPanel.vue:154-158`). | Inputs preserved. | Yes via result panel. | Yes. | Manual only. | PASS |
| Realtime | main full/delta refresh | Catch only sets `realtimeError`, does not clear `realtimeRows` (`RealtimeView.vue:216-276`). | Yes. | Yes small inline. | No. | Immediate refresh/timer. | PASS/P2 |
| Realtime | single-point read | Catch sets `singleRealtimeError`; previous single result remains (`RealtimeView.vue:297-324`). | Yes. | Yes. | Validation warning only. | Query button/pick row. | PASS |
| History | main history query | Main failure state sets `historyRows=[]` (`HistoryView.vue:465-476`). | No. | Yes. | Yes. | Query/refresh. | P1 |
| History | compare/related alarms | Optional failures become partial warning/unavailable (`HistoryView.vue:404-426`, `:448-455`). | Main preserved if main succeeds. | Yes. | Yes warning. | Query/refresh. | PASS |
| Alarm | alarm history refresh | Catch clears `alarms=[]` and sets error (`AlarmView.vue:230-236`). | No. | Yes in table. | No for load. | Refresh. | P1 |
| Alarm | acknowledgement-status refresh | Failure sets `ackStatusUnavailable`/warning without clearing alarm list (`AlarmView.vue:407-421`). | Yes. | Yes. | Manual warning. | Manual retry. | PASS |
| Cloud | report metrics | Catch sets `error`; does not clear `reportMetrics` (`CloudView.vue:101-112`). | Yes. | Yes. | No. | Refresh. | P2 |
| Diagnostic | metric probes | Failed probes collected in `failures`; successful refs retained/updated (`DiagnosticView.vue:161-197`). | Yes implicit. | Yes partial warning. | No. | Run diagnostic. | PASS/P2 |
| Log | ops log query | Catch clears `logs=[]`, sets `error` (`LogView.vue:148-153`). | No. | Yes but same empty block. | No. | Query/auto refresh. | P1 |
| Network | diagnose | Failure normalized as negative diagnostic result and added to history (`NetworkView.vue:155-170`). | Previous history preserved; current result replaced with explicit failure. | Yes. | Yes. | Start diagnose. | PASS |
| Shadow | shadow/delta/history read | Task 03.2 adds independent read owners plus captured target `deviceId`; stale read result/error cannot commit after device switch. | Yes for same-context refresh failure; last-good is context-scoped and reset on device change. | Yes, section-level stale/error status. | Yes. | Per-section read. | RESOLVED for Shadow wrong-context P0 and Shadow last-good loss. |

## 4. Write/action matrix

| Page | Action | Pending Guard | Double Submit | Success Feedback | Failure Feedback | Authoritative Refresh |
| --- | --- | --- | --- | --- | --- | --- |
| Login | test/login | `testing` (`LoginView.vue:88-102`). | Guarded. | Inline alert. | Inline alert. | Runtime refresh during test. |
| Device List | start/stop/sync | Global `deviceStore.operating` (`DeviceListView.vue:34`, `:60-61`). | Guarded globally, not per device. | Toast. | Toast. | Store `operate()` calls `refresh()` (`device.store.ts:114-125`). |
| Device List | refresh/clear config cache | `deviceConfigOperatingId` per operation/device (`DeviceListView.vue:62-63`, `:227-244`). | Guarded for same op/device. | Toast. | Toast. | `deviceStore.refresh()` after success. |
| Device List | delete local | Confirm dialog; no per-delete pending visible (`DeviceListView.vue:201-215`). | Mostly guarded by store global operating after call starts. | Toast. | Toast. | Store operation refreshes. |
| Device List | import/export config | `configFileImporting` / `configFileExporting` (`:110-112`, `:252-318`). | Guarded. | Toast/download. | Toast. | Import refreshes list. |
| Device Workbench | start/stop | No page-local pending; relies on store behavior from caller (`DeviceWorkbenchView.vue:31-47`). | Store global operating only if UI button binds it in child. | Toast. | Toast. | Store refresh. |
| Device Workbench | config refresh/clear | Confirm + `deviceConfigOperatingId` (`DeviceOperationShell.vue:123-153`). | Guarded per op/device. | Toast. | Toast. | Store refresh + preview reload. |
| Collection | ConfigOpsPanel import/export/sync | Component-local flags (`ConfigOpsPanel.vue:108-175`). | Guarded. | Toast. | Toast. | Parent refresh on imported/synced. |
| Control | write single/batch/command | Separate `singleWriting`, `batchWriting`, `commandExecuting` (`ControlPanel.vue:78-143`). | Guarded per action. | Toast + result JSON. | Toast + result JSON. | No extra read refresh; write API response is shown. |
| Alarm | acknowledge | `acknowledgingAlarmId` (`AlarmView.vue:281-300`). | Guarded per alarm button. | Toast + row update. | Toast. | Applies acknowledgement response locally. |
| Diagnostic | export package | `exporting` (`DiagnosticView.vue:204-230`). | Guarded. | Toast/download. | No explicit catch; failure would bubble to console/runtime. | N/A. |
| Network | diagnose | `networkOperating` (`NetworkView.vue:135-172`). | Guarded. | Result panel/history. | Result panel/history + toast. | N/A. |
| Network | edge telemetry submit | `submitting` (`EdgeTelemetryPanel.vue:129-146`). | Guarded. | Toast + response JSON. | Toast + response JSON. | N/A. |
| Shadow | save/clear desired | `savingDesired` plus captured `savingDesiredDeviceId`; write side effect is allowed to finish. | Guarded globally for this panel; concurrent multi-device writes remain deferred. | Target-specific toast identifies submitted device; current panel updates only if live device still matches target. | Target-specific toast; stale write failure cannot overwrite current device read state. | Response commits to `shadow` only for the same live device; `clearDesired` cannot clear another device form. |

## 5. Silent catch audit

Search scope: `collector-desktop/src/views`, `collector-desktop/src/features`, `collector-desktop/src/stores`; patterns included `.catch(() => undefined)`, `.catch(() => null)`, `catch {}`, `catch (_)`, plus manual catch blocks that intentionally return fallback data.

Total explicit swallow/navigation catches found: `15`.

| File | Lines | Classification | Rationale |
| --- | --- | --- | --- |
| `features/collection/components/ConfigOpsPanel.vue` | `100` | INTENTIONAL BEST-EFFORT / P2 | Initial sync-status hint only; previously known residual remains optional. |
| `features/device/components/DeviceOperationShell.vue` | `213`, `220`, `228`, `236` | INTENTIONAL BEST-EFFORT | `router.push(...).catch(() => undefined)` for navigation duplicate/abort; no data load hidden. |
| `views/alarm/AlarmView.vue` | `305`, `323` | INTENTIONAL BEST-EFFORT | Navigation to Log/Network after user troubleshoot action. |
| `views/auth/LoginView.vue` | `113` | BACKGROUND OPTIONAL | External docs open failure intentionally non-blocking. |
| `views/dashboard/DashboardView.vue` | `507` | INTENTIONAL BEST-EFFORT | Navigation to Device page. |
| `views/device/DeviceListView.vue` | `344`, `350`, `356`, `362` | INTENTIONAL BEST-EFFORT | Navigation to related routed pages. |
| `views/device/DeviceWorkbenchView.vue` | `54`, `63` | INTENTIONAL BEST-EFFORT | Navigation to History/Realtime. |

Additional `catch {}` blocks:

| File | Lines | Classification | Rationale |
| --- | --- | --- | --- |
| `views/diagnostic/DiagnosticView.vue` | `155-159`, `233-247` | DEGRADED / BACKGROUND OPTIONAL | App init or export samples fail into partial diagnostic/samples `[]`; acceptable, but export sample failures are not named in package UI. |
| `features/device/components/DeviceOperationShell.vue` | `137-139`, `169-174` | Confirm cancel intentional; preview failure is BAD SILENT FAILURE / P2 | Preview failure clears rows with no inline/toast. |
| `features/network/components/EdgeTelemetryPanel.vue` | `171-178` | INTENTIONAL BEST-EFFORT | Raw JSON preview not overwritten while input incomplete. |
| `views/alarm/AlarmView.vue` | `407-416` | HANDLED DEGRADED | Ack-status failure becomes persistent warning. |
| `features/shadow/components/ShadowPanel.vue` | Task 03.2 updated read/write handlers | RESOLVED | Shadow history failures now keep last-good rows and set persistent section error/stale status. |

No `catch (_` or `.catch(() => null)` occurrences were found in the audited frontend scope.

## 6. Toast-only error audit

Important read-operation failures that are not only toast:
- Login connection failure has persistent `el-alert`.
- Dashboard read failures have persistent fatal/partial alert and source states.
- Device List initial no-data failure has persistent exact-empty text.
- Collection has persistent warning alert.
- Realtime main/single failures have inline `<small>` messages.
- History and Alarm have persistent error/partial alerts/table text.
- Cloud and Diagnostic have persistent inline messages.
- Network failures are persisted in result/history.

Toast-only or weak persistence items:
- Device Workbench realtime preview failure is silently swallowed and preview rows are cleared (`DeviceOperationShell.vue:169-174`) — P2.
- `DeviceRuntimePanel.checkRunningFlag()` has no catch; failure is not converted to persistent UI (`features/diagnostic/components/DeviceRuntimePanel.vue:105-112`) — P2.
- Diagnostic package export has `finally` but no catch; export/sample failures can leave no inline explanation (`DiagnosticView.vue:204-230`) — P2.
- Shadow history failure toast-only/stale-row loss — RESOLVED in Task 03.2 with persistent section error/stale status and last-good row retention.

## 7. Empty/error and clear-before-load findings

| Page | Evidence | Classification |
| --- | --- | --- |
| Log | `catch` sets `logs.value=[]`; template renders `error || '当前条件下没有可显示日志'` in an empty-state block (`LogView.vue:55`, `:148-153`). | EMPTY/ERROR CONFLATION / P1: text differs, but failure destroys last-good logs and uses empty visual container. |
| Alarm | Failure clears `alarms=[]`; table row uses `error || empty` (`AlarmView.vue:66-67`, `:230-236`). | LAST-GOOD LOSS / P1; error text visible, but refresh failure blanks table. |
| History | Context change intentionally clears old context rows (`HistoryView.vue:301-312`, `:347-363`); main query failure applies empty failure state (`:465-476`). | Context clear is correct; same-context refresh failure last-good loss is P1. |
| Realtime | Refresh failure does not clear `realtimeRows`; initial empty prompt is generic (`RealtimeView.vue:216-276`, `:100-101`). | Last-good PASS; initial empty wording P2. |
| Shadow history | Task 03.2 keeps last-good rows on same-context failure; success-empty `[]` remains “暂无影子历史”, initial failure shows read-failure text. | RESOLVED for Shadow; History/Alarm/Log remain P1. |
| Device Store | Read failure sets `error` but does not clear `devices` (`device.store.ts:66-75`). | Last-good PASS. |

## 8. Realtime 100k long-full UX conclusion

Known accepted Task 02.5 real JAR measurement: 100k compact full median is approximately 17 seconds.

Current Realtime page behavior from code:

| Case | Current behavior | Conclusion |
| --- | --- | --- |
| Initial FULL ~17s | `initializeRealtimeView()` refreshes devices then awaits `loadRealtime('init')`; `loadRealtime` sets page-level `loading=true`; table remains mounted but with no rows, so user sees the generic empty prompt and disabled immediate-refresh button (`RealtimeView.vue:216-276`, `:427-436`, `:100-101`). | P2: not data-loss, but initial 17s has weak skeleton/progress semantics. |
| Controls during initial full | “立即刷新” disabled by `loading`; filters/page controls remain rendered, but there are no rows yet. | PASS/P2. |
| Summary during initial full | Summary computes from empty filtered rows until full commits. | P2 if users expect explicit “loading 100k snapshot”. |
| Periodic FULL after last-good rows | Timer calls `loadRealtime('timer')`; rows are not cleared; only `loading=true` and refresh disabled until completion (`:216-276`, `:378-386`). | PASS: last-good table remains visible during 17s full. |
| User interaction during periodic full | Existing rows remain; keyword/page watchers operate locally over `realtimeRows`; refresh button disabled while same request pending. | PASS: user can still filter/page existing last-good rows. |
| Delta empty/small | Delta path updates cursor/cycle and does not replace row array for empty delta (`features/realtime/utils/realtime-delta.ts:67-107`). | PASS: no unnecessary blanking. |
| Delta/full failure | Catch sets `realtimeError` and leaves rows intact (`RealtimeView.vue:266-271`). | PASS/STALE, but stale timestamp not displayed; severity P2. |
| Reset -> Full | `applyRealtimeDelta(...).needsFullResync` triggers full in same request without blanking rows first (`RealtimeView.vue:248-265`). | PASS: resync does not blank table before success. |

Realtime severity: `P2` overall, not `P1`, because last-good 100k rows are preserved and local filtering/pagination remain usable during periodic full. Recommended follow-up is clearer initial loading/stale/resync copy, not performance architecture changes.

## 9. Findings

### Finding 1
Page: History  
File: `collector-desktop/src/views/history/HistoryView.vue:385-440`, `:465-476`  
Operation: main history query / refresh  
Current behavior: latest-request owner protects context, and partial optional compare/related alarms are handled, but main history failure applies a failure state with `historyRows: []`.  
Failure scenario: user has a successful chart/table, then same-context refresh fails due network/backend timeout.  
User impact: last-good trend disappears; page becomes error/empty instead of STALE.  
Severity: P1  
Recommended change: preserve last successful main history rows for same-context refresh failure; show persistent stale warning and retry. Keep context-change clearing behavior.  
Recommended task: Task 03.3 — Investigation Pages Last-Good & Stale UX.

### Finding 2
Page: Alarm  
File: `collector-desktop/src/views/alarm/AlarmView.vue:202-246`  
Operation: alarm history query / refresh  
Current behavior: read is latest-owner safe, but catch clears `alarmAcknowledgements` and `alarms` and sets `error`.  
Failure scenario: user has alarm history rows, then manual refresh or filter refresh fails.  
User impact: last-good alarm list is lost; failure is not a stale state.  
Severity: P1  
Recommended change: preserve last-good alarm rows for same-context refresh failures; keep explicit error/stale banner and leave acknowledgement degradation separate.  
Recommended task: Task 03.3 — Investigation Pages Last-Good & Stale UX.

### Finding 3
Page: Log  
File: `collector-desktop/src/views/log/LogView.vue:133-159`, `:55`  
Operation: ops log query / auto refresh  
Current behavior: latest-owner safe and timer overlap protected, but catch clears `logs=[]`; template renders error in an empty-state block.  
Failure scenario: user has logs, auto-refresh fails once.  
User impact: log stream blanks, losing investigative context.  
Severity: P1  
Recommended change: preserve last-good logs on refresh failure and show stale/last-updated banner; only show ERROR-empty when there was no successful result for current server query.  
Recommended task: Task 03.3 — Investigation Pages Last-Good & Stale UX.

### Finding 4
Page: Realtime  
File: `collector-desktop/src/views/realtime/RealtimeView.vue:216-276`, `:100-101`  
Operation: 100k initial compact full  
Current behavior: table stays mounted; `loading=true`; no rows are visible until full returns; empty copy says select all/single device rather than “loading full snapshot”.  
Failure scenario: first 100k full takes about 17 seconds.  
User impact: not data loss, but user may confuse long initial loading with empty/unselected state.  
Severity: P2  
Recommended change: add explicit initial-full loading/resync/stale copy; preserve existing full/delta architecture and 5s polling.  
Recommended task: Task 03.5 — Task 03 Regression & Final Audit or later scoped UX copy pass.

### Finding 5
Page: Device Workbench  
File: `collector-desktop/src/features/device/components/DeviceOperationShell.vue:155-174`  
Operation: realtime preview read  
Current behavior: preview uses latest owner, but catch clears preview rows with no inline error/toast.  
Failure scenario: selected device preview request fails.  
User impact: rail says zero realtime points / unknown connection, indistinguishable from valid no-data.  
Severity: P2  
Recommended change: add lightweight preview unavailable/stale marker; do not block child panels.  
Recommended task: Task 03.3 — Device / Action Failure UX.

### Finding 6
Page: Device List / Device Workbench  
File: `collector-desktop/src/views/device/DeviceListView.vue:60-63`, `collector-desktop/src/stores/device.store.ts:114-125`, `DeviceWorkbenchView.vue:31-47`  
Operation: start/stop/sync device operations  
Current behavior: global `deviceStore.operating` guards broad device operations; config refresh/clear has per-device guard.  
Failure scenario: one device start/stop request is pending while operator wants to act on another device; or rapid per-device feedback should identify target.  
User impact: conservative global lock is safe but coarse; target ownership/pending copy is weak.  
Severity: P2  
Recommended change: introduce per-target action pending where repeated-device double-submit risk exists; keep writes authoritative and no blind retry.  
Recommended task: Task 03.3 — Device / Action Failure UX.

### Finding 7
Page: Cloud  
File: `collector-desktop/src/views/cloud/CloudView.vue:87-95`, `:101-112`  
Operation: cloud report metrics read  
Current behavior: single aggregate read preserves last metrics and inline error, but disabled/degraded/error distinctions are mostly derived from payload rows and no stale timestamp is shown on failure.  
Failure scenario: cloud backend disabled vs cloud metrics endpoint unavailable.  
User impact: operator may not distinguish intentionally disabled from degraded/unavailable quickly.  
Severity: P2  
Recommended change: add explicit disabled/degraded/unavailable labels after confirming payload semantics.  
Recommended task: Task 03.4 — Operational Pages Degraded UX.

### Finding 8
Page: Shadow  
File: `collector-desktop/src/features/shadow/components/ShadowPanel.vue:102-156`, `:173-213`  
Operation: shadow/delta/history reads; save/clear desired writes  
Current behavior after Task 03.2: `watch(() => props.deviceId)` invalidates all three read owners, resets context-scoped UI state, and all read/write requests capture target `deviceId` before `await`; stale read/write responses cannot commit to another live device panel.
Failure scenario: user starts read-all or desired save/clear for device A, then route/shell changes to device B before A request resolves.  
User impact: B Shadow panel can display A shadow/delta/history or A write result/error; this is wrong-context display for device-scoped operational data.  
Severity: P0 CLOSED in Task 03.2
Resolved change: added per-section read owners, captured `deviceId`/payload before `await`, guarded read commits/loading finalization, and guarded write UI commits while keeping backend side effects authoritative with target-specific toast feedback.
Recommended task: closed; residual multi-device concurrent write support is P2 deferred, not Task 03.2 scope.

### Finding 8b
Page: Shadow  
File: `collector-desktop/src/features/shadow/components/ShadowPanel.vue:115-153`  
Operation: shadow/delta/history refresh failure  
Current behavior after Task 03.2: same-context shadow/delta/history refresh failures keep last-good section data/rows and set persistent section error/stale text; context change still clears context-scoped last-good data.
Failure scenario: user has a successful shadow snapshot, then refreshes one section and that request fails.  
User impact: last-good snapshot/history is lost or hidden; history failure can look like a successful empty history.  
Severity: CLOSED for Shadow in Task 03.2
Resolved change: preserve last-good data per source and show persistent stale/unavailable marker; keep bundle partial failures as degraded rather than empty.
Recommended task: closed for Shadow; History/Alarm/Log last-good work moves to Task 03.3.

### Finding 9
Page: Diagnostic  
File: `collector-desktop/src/views/diagnostic/DiagnosticView.vue:161-197`, `:204-230`, `features/diagnostic/components/DeviceRuntimePanel.vue:105-112`  
Operation: diagnostic export and single running-flag check  
Current behavior: multi-source diagnostic read is good; export lacks catch; running-flag check lacks catch.  
Failure scenario: browser download/build payload throws; or `isDeviceRunning` rejects.  
User impact: action failure may be console-only or unhandled instead of persistent action result.  
Severity: P2  
Recommended change: add action-level failure result/toast where missing; do not change diagnostic multi-source model.  
Recommended task: Task 03.4 — Operational Pages Degraded UX.

### Finding 10
Page: Control / Network  
File: `collector-desktop/src/features/control/components/ControlPanel.vue:91-97`, `:114-120`, `:137-143`; `collector-desktop/src/views/network/NetworkView.vue:135-171`; `collector-desktop/src/features/network/components/EdgeTelemetryPanel.vue:129-145`  
Operation: device/target-scoped action result commit  
Current behavior: actions have pending flags, but response assignment uses the current component result panel without recording/displaying the submitted target snapshot.  
Failure scenario: user submits A device/target, edits the form or navigates to B before A returns.  
User impact: A response can be visually associated with B/current form. For Control this is device-scoped command feedback, so treat as P1; Network/EdgeTelemetry are diagnostic/debug actions, so P2 unless a real write duplication appears.  
Severity: P1/P2  
Recommended change: capture submitted target/payload and include operation/target/timestamp in committed result; for device-scoped writes, gate UI overwrite on target still matching current context.  
Recommended task: Task 03.3 — Device / Action Failure UX.

## 10. Top priority

Priority 1: Shadow wrong-context read/write ownership — CLOSED in Task 03.2. ShadowPanel now captures target device, uses section-specific read owners, invalidates on device change, and guards write UI commits.

Priority 2: Last-good/stale semantics for read-heavy investigation pages: History, Alarm, Log. These are P1 because refresh failure can destroy useful existing rows and are the next Task 03 scope.

Priority 3: Realtime 100k long-full UX copy plus device/action and operational P2/P1 polish: Realtime initial/resync text, Control target-result ownership, Device Workbench preview silent failure, per-target action pending clarity, Cloud disabled/degraded labels, Diagnostic action errors.

## 11. Pages already good enough for Task 03.1 baseline

- Login: form values preserved, inline error, retry via test/login.
- Dashboard: already has source-level metric states, partial warning, stale semantics, latest cycle.
- Collection: read aggregation is warning-based and non-blocking; ConfigOpsPanel residual initial sync hint remains P2 optional.
- Control: write actions have precise pending flags and persistent result JSON; no blind retry required.
- Network: diagnostics persist failures as result/history, not as empty data.
- Realtime for periodic 100k full: last-good rows remain usable during long full; only copy/stale indicator improvements are recommended.

## 12. Recommended Task 03 roadmap

| Task | Recommended scope | Reason |
| --- | --- | --- |
| 03.2 — Shadow Context Ownership & Last-Good State | RESOLVED: ShadowPanel P0 read/write target ownership is closed, and Shadow same-context refresh failure now retains last-good section data. | P0 wrong-context device shadow display/write feedback was the only Task 03.1 P0. |
| 03.3 — Investigation Pages Last-Good & Stale UX | Apply the minimal last-good/stale pattern to History, Alarm, and Log only. | These read-heavy investigation pages still lose useful existing rows on refresh failure. |
| 03.4 — Operational Pages Degraded UX | Cloud disabled/degraded/error labels; Diagnostic action-level failures; DeviceRuntimePanel inline error; optional sample/export error presentation. | Operational panels already degrade partially but need clearer semantics. |
| 03.5 — Task 03 Regression & Final Audit | Frontend typecheck/test/verify, targeted regression tests only where production behavior changed, and final page-state audit. | Close Task 03 without broad production behavior drift. |

## 13. Regression baseline for this audit

Commands run after Task 03.1 audit document creation and Task 03.2 Shadow closure:

```text
npm --prefix collector-desktop run typecheck
npm --prefix collector-desktop test
npm --prefix collector-desktop run build
npm --prefix collector-desktop run build:web
npm --prefix collector-desktop run verify
git diff --check
```

Results:

| Command | Result |
| --- | --- |
| `npm --prefix collector-desktop run typecheck` | PASS |
| `npm --prefix collector-desktop test` | PASS after Task 03.2 |
| `npm --prefix collector-desktop run build` | PASS after Task 03.2 |
| `npm --prefix collector-desktop run build:web` | PASS after Task 03.2 |
| `npm --prefix collector-desktop run verify` | PASS after Task 03.2 |
| `git diff --check` | PASS after Task 03.2 |

Build notes: Vite emitted existing large-chunk / Rollup annotation warnings only; the command exited `0`.

## 14. Changed files

- `collector-desktop/docs/production-readiness/PAGE-STATE-FAILURE-UX-BASELINE.md`
- `collector-desktop/src/features/shadow/components/ShadowPanel.vue`
- `collector-desktop/src/features/shadow/utils/shadow-request-state.ts`
- `collector-desktop/src/features/shadow/utils/shadow-request-state.test.ts`
- `collector-boot/src/main/resources/static/desktop/**` — generated web-console assets refreshed by required `npm --prefix collector-desktop run build:web`; Java/backend API code unchanged.
