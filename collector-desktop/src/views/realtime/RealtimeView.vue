<template>
  <section class="exact-page realtime-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>实时数据查询</h1>
        <span class="heading-online"><i></i>实时采集链路</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="loading" @click="refreshRealtime">立即刷新</button>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar">
        <div class="exact-toolbar-group">
          <button type="button" class="toggle-button" :class="{ 'is-active': realtimeAuto }" @click="realtimeAuto = !realtimeAuto">
            <span></span>自动刷新
          </button>
          <small>默认间隔 5 秒</small>
        </div>
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="realtimeDeviceId" @change="handleRealtimeDeviceChange">
            <option value="">全部设备</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          <input v-model="realtimeKeyword" type="search" placeholder="搜索点位名称、编码或地址" />
        </div>
      </div>
      <small v-if="realtimeError">{{ realtimeError }}</small>
      <div v-if="realtimeDeviceId && deviceHealthSnapshot?.deviceHealth" class="realtime-health-strip">
        <strong>设备健康（最近完整快照）：{{ realtimeDeviceHealthText(deviceHealthSnapshot.deviceHealth) }}</strong>
        <span>传输：{{ realtimeLayerStateText(deviceHealthSnapshot.transportState) }}</span>
        <span>协议：{{ realtimeLayerStateText(deviceHealthSnapshot.protocolState) }}</span>
        <span>采集：{{ realtimeLayerStateText(deviceHealthSnapshot.acquisitionState) }}</span>
        <span>最近尝试：{{ formatTime(deviceHealthSnapshot.lastAttemptAt) }}</span>
        <span>最近有效值：{{ formatTime(deviceHealthSnapshot.lastValueAt) }}</span>
      </div>
      <div v-else-if="!realtimeDeviceId && allDeviceHealth.length" class="realtime-health-strip">
        <span>设备健康（最近完整快照）：</span>
        <span v-for="health in deviceHealthLevels" :key="health">{{ realtimeDeviceHealthText(health) }} {{ allDeviceHealth.filter((device) => device.deviceHealth === health).length }}</span>
      </div>

      <div class="exact-diagnostic-cards realtime-summary-cards">
        <div class="exact-diagnostic-card"><span>实时记录</span><strong>{{ realtimeSummary.total }}</strong></div>
        <div class="exact-diagnostic-card"><span>质量正常</span><strong>{{ realtimeSummary.good }}</strong></div>
        <div class="exact-diagnostic-card"><span>异常/未知</span><strong>{{ realtimeSummary.bad }}</strong></div>
      </div>

      <section class="exact-surface realtime-single-panel">
        <div class="exact-surface-head">
          <h2>单点实时查询</h2>
          <span>按稳定 pointId / 点位编码查询</span>
        </div>
        <div class="exact-toolbar">
          <div class="exact-toolbar-group exact-toolbar-filters">
            <select v-model="realtimeSingleDeviceId">
              <option value="">选择设备</option>
              <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
                {{ device.displayName || device.normalizedId }}
              </option>
            </select>
            <input v-model="realtimeSinglePointId" type="text" placeholder="pointId 或点位编码" />
            <button type="button" class="primary" :disabled="singleRealtimeSubmitDisabled" @click="loadSingleRealtime">
              查询单点
            </button>
          </div>
        </div>
        <small v-if="singleRealtimeError">{{ singleRealtimeError }}</small>
        <pre class="json-view compact-result-view">{{ prettyJson(realtimeSingleResult) }}</pre>
      </section>

      <section class="exact-table-card">
        <div class="exact-toolbar realtime-pagination">
          <div class="exact-toolbar-group">
            <span>共 {{ realtimePageWindow.total }} 条</span>
            <small v-if="realtimePageWindow.total > 0">显示 {{ realtimePageWindow.startIndex }}–{{ realtimePageWindow.endIndex }} 条</small>
            <small v-else>当前没有可分页的数据</small>
          </div>
          <div class="exact-toolbar-group">
            <span>第 {{ realtimePageWindow.totalPages === 0 ? 0 : realtimePageWindow.page }} / {{ realtimePageWindow.totalPages }} 页</span>
          </div>
          <div class="exact-toolbar-group exact-toolbar-filters">
            <select v-model.number="realtimePageSize" @change="handleRealtimePageSizeChange">
              <option v-for="pageSize in REALTIME_PAGE_SIZE_OPTIONS" :key="pageSize" :value="pageSize">每页 {{ pageSize }}</option>
            </select>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page <= 1" @click="goToFirstRealtimePage">首页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page <= 1" @click="goToPreviousRealtimePage">上一页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page >= realtimePageWindow.totalPages" @click="goToNextRealtimePage">下一页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page >= realtimePageWindow.totalPages" @click="goToLastRealtimePage">末页</button>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>点位名称</th>
              <th>设备名称</th>
              <th>数据类型</th>
              <th>寄存器地址</th>
              <th>读写</th>
              <th>缩放</th>
              <th>当前值</th>
              <th>单位</th>
              <th>采集时间</th>
              <th>质量</th>
              <th>状态说明</th>
              <th>处理耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="filteredRealtimeRows.length === 0">
              <td colspan="13" class="exact-empty">选择“全部设备”可聚合查看所有设备实时数据，也可选择单设备过滤</td>
            </tr>
            <tr v-for="row in pagedRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode || row.address}`">
              <td>{{ row.pointName || row.pointCode || '-' }}</td>
              <td>{{ row.deviceName || deviceDisplayName(String(row.deviceId || realtimeDeviceId)) }}</td>
              <td>{{ row.dataType || '-' }}</td>
              <td><code>{{ realtimeAddress(row) }}</code></td>
              <td>{{ row.readWrite || '-' }}</td>
              <td>{{ realtimeScale(row) }}</td>
              <td><strong>{{ realtimeValueText(row) }}</strong></td>
              <td>{{ row.unit || '-' }}</td>
              <td>{{ formatTime(row.timestamp || row.collectTime || row.lastUpdateTime) }}</td>
              <td><span class="quality-badge" :class="realtimeQualityClass(row)">{{ realtimeQualityText(row) }}</span></td>
              <td><span :title="realtimeErrorText(row)">{{ realtimeStatusText(row) }}</span></td>
              <td>{{ realtimeProcessingText(row) }}</td>
              <td><button type="button" @click="pickRealtimePoint(row)">查单点</button></td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { ApiRequestError } from "@/api/http";
import { getPointRealtimeData } from "@/api/data.api";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, CompactRealtimeDeviceStatus, RealtimePointRow } from "@/types/monitor";
import {
  buildRealtimeSummary,
  normalizeSinglePointRealtimeRow,
  realtimeAddress,
  realtimeProcessingText,
  realtimeQualityClass,
  realtimeQualityText,
  realtimeScale,
  realtimeValueText,
  realtimeStatusText,
  realtimeErrorText,
  realtimeDeviceHealthText
} from "@/features/realtime/utils/realtime-utils";
import { loadRealtimeDeltaResponseByContext, loadRealtimeFullResponseByContext } from "@/features/realtime/utils/realtime-load-strategy";
import {
  applyFullRealtimeSnapshot,
  applyRealtimeDelta,
  emptyRealtimeDeltaState,
  resetRealtimeDeltaState,
  shouldRequestFullRealtimeSnapshot,
  type RealtimeLoadSource
} from "@/features/realtime/utils/realtime-delta";
import {
  DEFAULT_REALTIME_PAGE_SIZE,
  REALTIME_PAGE_SIZE_OPTIONS,
  buildRealtimeDeviceNameLookup,
  buildRealtimePageWindow,
  filterRealtimeRows,
  getPagedRealtimeRows,
  normalizeRealtimePageSize,
  resolveRealtimeDeviceName
} from "@/features/realtime/utils/realtime-table-window";
import {
  createLatestRealtimeRequestOwner,
  shouldDisableRealtimeSubmit,
  type RealtimeRequestContext
} from "@/features/realtime/utils/realtime-request-lifecycle";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();

const realtimeAuto = ref(true);
const realtimeDeviceId = ref("");
const realtimeKeyword = ref("");
const realtimeRows = ref<RealtimePointRow[]>([]);
const deviceHealthSnapshot = ref<CompactDeviceRealtimeDataResponse | null>(null);
const allDeviceHealth = ref<CompactRealtimeDeviceStatus[]>([]);
const deviceHealthLevels = ["OFFLINE", "ONLINE_NO_DATA", "ONLINE_PARTIAL", "ONLINE_HEALTHY", "DEGRADED", "UNKNOWN"];
const realtimePage = ref(1);
const realtimePageSize = ref(DEFAULT_REALTIME_PAGE_SIZE);
const realtimeSingleDeviceId = ref("");
const realtimeSinglePointId = ref("");
const realtimeSingleResult = ref<unknown>({ message: "选择设备和点位后查询单点实时数据" });
const loading = ref(false);
const singleLoading = ref(false);
const realtimeError = ref("");
const singleRealtimeError = ref("");
const pendingSingleRealtimeContext = ref<RealtimeRequestContext | null>(null);
let realtimeTimer: number | null = null;
let realtimeDeltaState = emptyRealtimeDeltaState();
const realtimeRequestOwner = createLatestRealtimeRequestOwner();
const singleRealtimeRequestOwner = createLatestRealtimeRequestOwner();
const deviceDisplayNameLookup = computed(() => buildRealtimeDeviceNameLookup(deviceStore.devices));

const displayRealtimeRows = computed(() => realtimeRows.value.map(enrichRealtimeRowWithRuntime));
const filteredRealtimeRows = computed(() => {
  const keyword = realtimeKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return displayRealtimeRows.value;
  }
  return filterRealtimeRows(displayRealtimeRows.value, keyword, deviceDisplayNameLookup.value, realtimeDeviceId.value);
});

const realtimePageWindow = computed(() => buildRealtimePageWindow({
  total: filteredRealtimeRows.value.length,
  page: realtimePage.value,
  pageSize: realtimePageSize.value
}));

const pagedRealtimeRows = computed(() => getPagedRealtimeRows(filteredRealtimeRows.value, realtimePageWindow.value));

const realtimeSummary = computed(() => buildRealtimeSummary(filteredRealtimeRows.value));
const singleRealtimeSubmitDisabled = computed(() => {
  const liveContext = currentSingleRealtimeContext();
  return !liveContext.deviceId
    || !liveContext.pointId
    || shouldDisableRealtimeSubmit(singleLoading.value, pendingSingleRealtimeContext.value, liveContext);
});

async function loadRealtime(source: RealtimeLoadSource = "manual") {
  if (source === "timer" && loading.value) {
    return;
  }
  const requestContext = currentMainRealtimeContext();
  const requestTicket = realtimeRequestOwner.begin(requestContext);
  loading.value = true;
  realtimeError.value = "";
  try {
    if (shouldRequestFullRealtimeSnapshot(source, realtimeDeltaState)) {
      const response = await loadRealtimeFullResponseByContext(requestContext);
      if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
        return;
      }
      const nextState = applyFullRealtimeSnapshot(response, requestContext);
      realtimeRows.value = nextState.rows;
      updateDeviceHealth(response, requestContext.mode);
      realtimeDeltaState = {
        cursor: nextState.cursor,
        successfulDeltaCycles: nextState.successfulDeltaCycles,
        rowIdentityIndex: nextState.rowIdentityIndex
      };
      return;
    }

    const cursor = realtimeDeltaState.cursor;
    if (!cursor) {
      return;
    }
    const deltaResponse = await loadRealtimeDeltaResponseByContext(requestContext, cursor);
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    const deltaResult = applyRealtimeDelta(realtimeRows.value, realtimeDeltaState, deltaResponse, requestContext);
    if (!deltaResult.needsFullResync) {
      realtimeDeltaState.cursor = deltaResult.cursor;
      realtimeDeltaState.successfulDeltaCycles = deltaResult.successfulDeltaCycles;
      return;
    }

    const fullResponse = await loadRealtimeFullResponseByContext(requestContext);
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    const nextState = applyFullRealtimeSnapshot(fullResponse, requestContext);
    realtimeRows.value = nextState.rows;
    updateDeviceHealth(fullResponse, requestContext.mode);
    realtimeDeltaState = {
      cursor: nextState.cursor,
      successfulDeltaCycles: nextState.successfulDeltaCycles,
      rowIdentityIndex: nextState.rowIdentityIndex
    };
  } catch (error) {
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    realtimeError.value = error instanceof Error ? error.message : "实时数据刷新失败";
    realtimeRows.value = [];
    resetRealtimeDeltaState(realtimeDeltaState);
    deviceHealthSnapshot.value = null;
    allDeviceHealth.value = [];
    logRealtimeRequestError(error);
  } finally {
    if (realtimeRequestOwner.isLatest(requestTicket)) {
      loading.value = false;
    }
  }
}

watch(realtimeKeyword, () => {
  if (realtimePage.value !== 1) {
    realtimePage.value = 1;
  }
});

watch(realtimeDeviceId, () => {
  if (realtimePage.value !== 1) {
    realtimePage.value = 1;
  }
});

watch(realtimePageWindow, (nextWindow) => {
  if (nextWindow.page !== realtimePage.value) {
    realtimePage.value = nextWindow.page;
  }
}, { immediate: true });

async function loadSingleRealtime() {
  if (!realtimeSingleDeviceId.value || !realtimeSinglePointId.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  const requestContext = currentSingleRealtimeContext();
  const requestTicket = singleRealtimeRequestOwner.begin(requestContext);
  singleLoading.value = true;
  singleRealtimeError.value = "";
  realtimeSingleResult.value = { message: "查询中" };
  pendingSingleRealtimeContext.value = requestContext;
  try {
    const response = await getPointRealtimeData(requestContext.deviceId, requestContext.pointId || "");
    if (!singleRealtimeRequestOwner.isCurrent(requestTicket, currentSingleRealtimeContext())) {
      return;
    }
    realtimeSingleResult.value = normalizeSinglePointRealtimeRow(response) || response;
  } catch (error) {
    if (!singleRealtimeRequestOwner.isCurrent(requestTicket, currentSingleRealtimeContext())) {
      return;
    }
    singleRealtimeError.value = error instanceof Error ? error.message : "单点实时查询失败";
    logRealtimeRequestError(error);
  } finally {
    if (singleRealtimeRequestOwner.isLatest(requestTicket)) {
      singleLoading.value = false;
      pendingSingleRealtimeContext.value = null;
    }
  }
}

function pickRealtimePoint(row: RealtimePointRow) {
  realtimeSingleDeviceId.value = String(row.deviceId || realtimeDeviceId.value || "");
  realtimeSinglePointId.value = String(row.pointId || row.pointCode || row.address || "");
  if (realtimeSingleDeviceId.value && realtimeSinglePointId.value) {
    void loadSingleRealtime();
  }
}

function refreshRealtime() {
  void loadRealtime("manual");
}

function logRealtimeRequestError(error: unknown) {
  if (isExpectedRealtimeRequestFailure(error)) {
    return;
  }
  console.error(error);
}

function isExpectedRealtimeRequestFailure(error: unknown): boolean {
  if (!(error instanceof ApiRequestError || error instanceof Error)) {
    return false;
  }
  const message = error.message || "";
  return /fetch failed|network error|connection refused|请求采集服务超时|无法连接采集服务/i.test(message);
}

function handleRealtimeDeviceChange() {
  realtimePage.value = 1;
  realtimeRows.value = [];
  deviceHealthSnapshot.value = null;
  allDeviceHealth.value = [];
  resetRealtimeDeltaState(realtimeDeltaState);
  void loadRealtime("device-change");
}

function updateDeviceHealth(response: CompactAllDeviceRealtimeDataResponse | CompactDeviceRealtimeDataResponse, mode: RealtimeRequestContext["mode"]) {
  if (mode === "all") {
    deviceHealthSnapshot.value = null;
    const aggregate = response as CompactAllDeviceRealtimeDataResponse;
    allDeviceHealth.value = (aggregate.devices || []).map((device) => ({
      ...device,
      deviceHealth: device.deviceHealth || "UNKNOWN"
    }));
    return;
  }
  const snapshot = response as CompactDeviceRealtimeDataResponse;
  deviceHealthSnapshot.value = snapshot.deviceHealth ? snapshot : null;
  allDeviceHealth.value = [];
}

function realtimeLayerStateText(state?: string): string {
  const labels: Record<string, string> = {
    UNKNOWN: "未知", CONNECTING: "连接中", CONNECTED: "已连接", DISCONNECTED: "已断开",
    STOPPED: "已停止", NEGOTIATING: "协商中", READY: "就绪", ERROR: "异常",
    IDLE: "空闲", WAITING: "等待", ACTIVE: "活跃", PARTIAL: "部分有效",
    STALE: "旧值", FAILED: "失败"
  };
  return state ? labels[state] || state : "未知";
}

function handleRealtimePageSizeChange() {
  realtimePageSize.value = normalizeRealtimePageSize(realtimePageSize.value);
  realtimePage.value = 1;
}

function goToRealtimePage(page: number) {
  if (realtimePageWindow.value.totalPages === 0) {
    return;
  }
  const nextWindow = buildRealtimePageWindow({
    total: filteredRealtimeRows.value.length,
    page,
    pageSize: realtimePageSize.value
  });
  realtimePage.value = nextWindow.page;
}

function goToFirstRealtimePage() {
  goToRealtimePage(1);
}

function goToPreviousRealtimePage() {
  goToRealtimePage(realtimePageWindow.value.page - 1);
}

function goToNextRealtimePage() {
  goToRealtimePage(realtimePageWindow.value.page + 1);
}

function goToLastRealtimePage() {
  goToRealtimePage(realtimePageWindow.value.totalPages);
}

function syncTimer() {
  if (realtimeTimer) {
    clearInterval(realtimeTimer);
    realtimeTimer = null;
  }
  if (realtimeAuto.value) {
    realtimeTimer = window.setInterval(() => {
      void deviceStore.refresh();
      void loadRealtime("timer");
    }, 5000);
  }
}

function applyRouteQuery() {
  const deviceId = normalizeRouteQuery(route.query.deviceId);
  const pointId = normalizeRouteQuery(route.query.pointId);
  if (!deviceId || !pointId) {
    return;
  }
  realtimeSingleDeviceId.value = deviceId;
  realtimeSinglePointId.value = pointId;
  void loadSingleRealtime();
}

function enrichRealtimeRowWithRuntime(row: RealtimePointRow): RealtimePointRow {
  if (row.failureType !== undefined || row.lastAttemptAt !== undefined || row.lastValueAt !== undefined ||
      ["WAITING", "CONFIG_ERROR", "COMM_ERROR", "MAPPING_ERROR", "DECODE_ERROR"].includes(String(row.realtimeStatus))) {
    return row;
  }
  const runtime = row.deviceId ? deviceStore.runtimeMap[row.deviceId] : undefined;
  if (!runtime) {
    return row;
  }
  const hasValue = row.value !== undefined && row.value !== null
    || row.currentValue !== undefined && row.currentValue !== null
    || row.rawValue !== undefined && row.rawValue !== null;
  if (runtime.starting || runtime.reconnecting) {
    return { ...row, realtimeStatus: "CONNECTING", errorMessage: "正在建立或恢复连接", stale: hasValue };
  }
  if (runtime.connected === false) {
    return { ...row, realtimeStatus: hasValue ? "STALE" : "DISCONNECTED", errorMessage: "连接已断开", stale: hasValue };
  }
  if (Number(runtime.consecutiveFailures || 0) > 0 || runtime.degradedReason) {
    return { ...row, realtimeStatus: hasValue ? "STALE" : "COLLECT_ERROR", errorMessage: runtime.degradedReason || "最近连续采集失败", stale: hasValue };
  }
  return row;
}

function deviceDisplayName(deviceId: string): string {
  return resolveRealtimeDeviceName(deviceDisplayNameLookup.value, deviceId);
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    if (value <= 0) return "-";
    return new Date(value).toLocaleString();
  }
  if (!value) {
    return "-";
  }
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function normalizeRouteQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] ?? "") : "";
  }
  return value === undefined || value === null ? "" : String(value);
}

onMounted(() => {
  void initializeRealtimeView();
});

async function initializeRealtimeView() {
  await appStore.initialize();
  applyRouteQuery();
  await deviceStore.refresh();
  await loadRealtime("init");
  syncTimer();
}

onBeforeUnmount(() => {
  realtimeRequestOwner.invalidate();
  singleRealtimeRequestOwner.invalidate();
  resetRealtimeDeltaState(realtimeDeltaState);
  loading.value = false;
  singleLoading.value = false;
  pendingSingleRealtimeContext.value = null;
  if (realtimeTimer) {
    clearInterval(realtimeTimer);
    realtimeTimer = null;
  }
});

function currentMainRealtimeContext(): RealtimeRequestContext {
  const deviceId = realtimeDeviceId.value.trim();
  return {
    mode: deviceId ? "device" : "all",
    deviceId
  };
}

function currentSingleRealtimeContext(): RealtimeRequestContext {
  return {
    mode: "single",
    deviceId: realtimeSingleDeviceId.value.trim(),
    pointId: realtimeSinglePointId.value.trim()
  };
}

watch(() => realtimeAuto.value, syncTimer);
watch(() => [route.query.deviceId, route.query.pointId], () => {
  applyRouteQuery();
});
</script>

<style scoped>
.realtime-health-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  padding: 10px 14px;
  border: 1px solid var(--exact-border);
  border-radius: 8px;
  color: var(--exact-dim);
  background: var(--exact-panel);
  font-size: 12px;
}

.realtime-health-strip strong {
  color: #e2e8f0;
}
</style>
