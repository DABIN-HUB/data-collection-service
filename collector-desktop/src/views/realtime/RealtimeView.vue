<template>
  <section class="exact-page realtime-view">
    <div class="realtime-heading">
      <div>
        <div class="realtime-title-line">
          <h1>实时数据查询</h1>
          <span class="realtime-heading-badge">实时采集链路</span>
        </div>
        <p>设备健康与点位质量分开展示</p>
      </div>
      <div class="realtime-heading-actions">
        <button type="button" :disabled="loading" @click="refreshRealtime">立即刷新</button>
      </div>
    </div>

    <div class="exact-page-body realtime-body">
      <div class="realtime-query-toolbar">
        <div class="realtime-filters">
          <label class="realtime-field">
            <span>设备范围</span>
          <select v-model="realtimeDeviceId" class="realtime-device-filter" aria-label="实时数据设备" @change="handleRealtimeDeviceChange">
            <option value="">全部设备</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          </label>
          <label class="realtime-field">
            <span>质量状态</span>
            <select v-model="realtimeQualityFilter" class="realtime-quality-filter" aria-label="实时数据质量状态" @change="realtimePage = 1">
              <option value="ALL">全部质量</option>
              <option value="GOOD">质量正常</option>
              <option value="BAD">异常 / 失败</option>
              <option value="UNKNOWN">未知 / 未评估</option>
            </select>
          </label>
          <label class="realtime-field realtime-keyword-field">
            <span>点位搜索</span>
          <input v-model="realtimeKeyword" class="realtime-search" type="search" aria-label="搜索点位" placeholder="搜索点位名称、编码或地址" />
          </label>
        </div>
        <div class="realtime-auto-group">
          <button type="button" class="realtime-auto" :class="{ 'is-active': realtimeAuto }" :aria-pressed="realtimeAuto" @click="realtimeAuto = !realtimeAuto">
            <span class="realtime-toggle-track" aria-hidden="true"><i></i></span>自动刷新
          </button>
          <small>默认间隔 5 秒</small>
        </div>
      </div>

      <div v-if="realtimeError" class="realtime-notice realtime-notice-error" role="alert">
        <strong>实时数据刷新失败</strong>
        <span>{{ realtimeError }}</span>
        <span>{{ realtimeRows.length || deviceHealthSnapshot || allDeviceHealth.length ? '保留最近一次成功快照，当前刷新未提交新数据。' : '尚无可显示的成功快照，请检查服务后立即刷新。' }}</span>
      </div>
      <div v-else-if="loading" class="realtime-notice" role="status">
        <template v-if="realtimeRows.length || deviceHealthSnapshot || allDeviceHealth.length || realtimeDeltaState.cursor">
          正在刷新实时数据，当前显示已有记录与最近完整健康快照。
        </template>
        <template v-else>正在加载实时数据，等待完整快照。</template>
      </div>
      <div v-else-if="!realtimeRows.length && !deviceHealthSnapshot && !allDeviceHealth.length && !realtimeDeltaState.cursor" class="realtime-notice" role="status">
        等待实时数据完整快照，记录数暂未知。
      </div>
      <div v-else-if="realtimeSummary.bad > 0" class="realtime-notice realtime-notice-warning">
        部分点位存在异常、未知状态或旧值；下方保留原值、质量与状态说明。此状态不表示查询请求失败。
      </div>

      <div v-if="realtimeDeviceId && deviceHealthSnapshot?.deviceHealth" class="realtime-health-strip">
        <span class="realtime-health-title">设备健康<small>最近完整快照</small></span>
        <div class="realtime-device-health">
          <strong :class="{ 'realtime-good': deviceHealthSnapshot.deviceHealth === 'ONLINE_HEALTHY', 'realtime-warning': ['ONLINE_PARTIAL', 'DEGRADED'].includes(deviceHealthSnapshot.deviceHealth) }">
            {{ deviceHealthSnapshot.deviceHealth === 'UNKNOWN' ? '未知' : realtimeDeviceHealthText(deviceHealthSnapshot.deviceHealth) }}
          </strong>
          <span>传输：{{ realtimeLayerStateText(deviceHealthSnapshot.transportState) }}</span>
          <span>协议：{{ realtimeLayerStateText(deviceHealthSnapshot.protocolState) }}</span>
          <span>采集：{{ realtimeLayerStateText(deviceHealthSnapshot.acquisitionState) }}</span>
          <span>最近尝试：{{ formatTime(deviceHealthSnapshot.lastAttemptAt) }}</span>
          <span>最近有效值：{{ formatTime(deviceHealthSnapshot.lastValueAt) }}</span>
        </div>
      </div>
      <div v-else-if="!realtimeDeviceId && allDeviceHealth.length" class="realtime-health-strip">
        <span class="realtime-health-title">设备健康<small>最近完整快照</small></span>
        <div class="realtime-health-levels">
          <span v-for="health in deviceHealthLevels" :key="health" :class="{ 'realtime-good': health === 'ONLINE_HEALTHY', 'realtime-warning': ['ONLINE_PARTIAL', 'DEGRADED'].includes(health) }">
            {{ health === 'UNKNOWN' ? '未知' : realtimeDeviceHealthText(health) }} <b>{{ allDeviceHealth.filter((device) => device.deviceHealth === health).length }}</b>
          </span>
        </div>
      </div>
      <div v-else class="realtime-health-strip">
        <span class="realtime-health-title">设备健康<small>最近完整快照</small></span>
        <span class="realtime-health-unavailable">{{ realtimeError ? '健康快照不可用' : loading ? '正在读取完整健康快照' : '暂无完整健康快照' }}</span>
      </div>

      <div class="realtime-summary" aria-label="当前筛选结果的点位质量摘要">
        <template v-if="!realtimeRows.length && !deviceHealthSnapshot && !allDeviceHealth.length && !realtimeDeltaState.cursor">
          <div><span>实时记录</span><strong>—</strong></div>
          <div><span>质量正常</span><strong>—</strong></div>
          <div><span>异常/未知</span><strong>—</strong></div>
        </template>
        <template v-else>
          <div><span>实时记录</span><strong>{{ realtimeSummary.total }}</strong></div>
          <div><span>质量正常</span><strong class="realtime-good">{{ realtimeSummary.good }}</strong></div>
          <div><span>异常/未知</span><strong :class="{ 'realtime-warning': realtimeSummary.bad > 0 }">{{ realtimeSummary.bad }}</strong></div>
        </template>
      </div>

      <section class="realtime-panel realtime-table-panel" aria-labelledby="realtime-records-title" :aria-busy="loading">
        <div class="realtime-panel-head">
          <h2 id="realtime-records-title">点位实时记录</h2>
          <span>当前值、质量、状态与处理耗时</span>
        </div>
        <div v-if="filteredRealtimeRows.length > 0" class="realtime-table-scroll" tabindex="0" role="region" aria-label="点位实时记录，可纵向及横向滚动">
          <table class="realtime-data-table">
            <colgroup>
              <col class="realtime-col-point" />
              <col class="realtime-col-device" />
              <col class="realtime-col-type" />
              <col class="realtime-col-address" />
              <col class="realtime-col-access" />
              <col class="realtime-col-scale" />
              <col class="realtime-col-value" />
              <col class="realtime-col-unit" />
              <col class="realtime-col-time" />
              <col class="realtime-col-quality" />
              <col class="realtime-col-status" />
              <col class="realtime-col-cost" />
              <col class="realtime-col-action" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">点位名称</th>
                <th scope="col">设备名称</th>
                <th scope="col">数据类型</th>
                <th scope="col">寄存器地址</th>
                <th scope="col">读写</th>
                <th scope="col">缩放</th>
                <th scope="col">当前值</th>
                <th scope="col">单位</th>
                <th scope="col">采集时间</th>
                <th scope="col">质量</th>
                <th scope="col">状态说明</th>
                <th scope="col">处理耗时</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pagedRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode || row.address}`" :class="{ 'realtime-row-warning': row.stale === true }">
                <td class="realtime-point-name">
                  <strong>{{ row.pointName || row.pointCode || '-' }}</strong>
                  <small v-if="row.pointCode">{{ row.pointCode }}</small>
                </td>
                <td>{{ row.deviceName || deviceDisplayName(String(row.deviceId || realtimeDeviceId)) }}</td>
                <td class="realtime-mono">{{ row.dataType || '-' }}</td>
                <td><code>{{ realtimeAddress(row) }}</code></td>
                <td>{{ row.readWrite || '-' }}</td>
                <td class="realtime-mono">{{ realtimeScale(row) }}</td>
                <td class="realtime-value realtime-mono" :class="{ 'realtime-warning': row.stale === true }"><strong>{{ realtimeValueText(row) }}</strong></td>
                <td>{{ row.unit || '-' }}</td>
                <td class="realtime-time realtime-mono">{{ formatTime(row.timestamp || row.collectTime || row.lastUpdateTime) }}</td>
                <td><span class="quality-badge" :class="realtimeQualityClass(row)"><i aria-hidden="true"></i>{{ realtimeQualityText(row) }}</span></td>
                <td class="realtime-point-status" :class="{ 'realtime-warning': row.stale === true }">
                  <span :title="realtimeErrorText(row)">{{ realtimeStatusText(row) }}</span>
                  <small v-if="realtimeErrorText(row) !== realtimeStatusText(row)">{{ realtimeErrorText(row) }}</small>
                </td>
                <td class="realtime-mono">{{ realtimeProcessingText(row) }}</td>
                <td><button type="button" class="realtime-point-action" @click="pickRealtimePoint(row)">查单点</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="realtime-empty" role="status">
          <template v-if="realtimeError">
            <strong>实时数据暂不可用</strong>
            <p>请求失败，无法显示当前记录及最近完整健康快照。</p>
          </template>
          <template v-else-if="!realtimeRows.length && !deviceHealthSnapshot && !allDeviceHealth.length && !realtimeDeltaState.cursor">
            <strong>{{ loading ? '正在加载点位数据' : '等待点位数据' }}</strong>
            <p>完整快照尚未加载，记录数暂未知。</p>
          </template>
          <template v-else-if="realtimeKeyword.trim()">
            <strong>没有匹配的点位</strong>
            <p>可调整名称、编码或地址关键词，查看当前设备的实时记录。</p>
          </template>
          <template v-else>
            <strong>当前没有实时记录</strong>
            <p>选择“全部设备”可聚合查看所有设备实时数据，也可选择单设备过滤。</p>
          </template>
        </div>
        <div class="realtime-pagination">
          <div class="realtime-page-count">
            <template v-if="!realtimeRows.length && !deviceHealthSnapshot && !allDeviceHealth.length && !realtimeDeltaState.cursor">
              <span>{{ realtimeError ? '记录数暂不可用' : '记录数暂未知' }}</span>
            </template>
            <template v-else>
              <span>共 {{ realtimePageWindow.total }} 条</span>
              <small v-if="realtimePageWindow.total > 0">显示 {{ realtimePageWindow.startIndex }}–{{ realtimePageWindow.endIndex }} 条</small>
              <small v-else>当前没有可分页的数据</small>
            </template>
          </div>
          <div class="realtime-page-actions">
            <span v-if="realtimeError || (!realtimeRows.length && !deviceHealthSnapshot && !allDeviceHealth.length && !realtimeDeltaState.cursor)">页数暂未知</span>
            <span v-else>第 {{ realtimePageWindow.totalPages === 0 ? 0 : realtimePageWindow.page }} / {{ realtimePageWindow.totalPages }} 页</span>
            <select v-model.number="realtimePageSize" aria-label="每页条数" @change="handleRealtimePageSizeChange">
              <option v-for="pageSize in REALTIME_PAGE_SIZE_OPTIONS" :key="pageSize" :value="pageSize">每页 {{ pageSize }}</option>
            </select>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page <= 1" @click="goToFirstRealtimePage">首页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page <= 1" @click="goToPreviousRealtimePage">上一页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page >= realtimePageWindow.totalPages" @click="goToNextRealtimePage">下一页</button>
            <button type="button" :disabled="realtimePageWindow.totalPages === 0 || realtimePageWindow.page >= realtimePageWindow.totalPages" @click="goToLastRealtimePage">末页</button>
          </div>
        </div>
      </section>

      <div class="realtime-source-context" role="note">
        <strong>实时读取来源</strong>
        <p>完整快照 / 增量轮询；设备健康来自最近完整快照，不能用点位质量代替。当前页面不宣称 WebSocket 可用。</p>
        <span>范围：{{ realtimeDeviceId ? deviceDisplayName(realtimeDeviceId) : '全部设备' }} · 自动刷新{{ realtimeAuto ? '开启' : '关闭' }}</span>
      </div>

      <details class="realtime-panel realtime-single-panel" aria-labelledby="realtime-single-title" :aria-busy="singleLoading" :open="singleLoading || Boolean(realtimeSingleResult) || Boolean(singleRealtimeError)">
        <summary class="realtime-panel-head">
          <h2 id="realtime-single-title">单点实时查询</h2>
          <span>按稳定 pointId / 点位编码查询</span>
        </summary>
        <div class="realtime-single-body">
          <div class="realtime-single-form">
            <label class="realtime-field">
              <span>设备</span>
              <select v-model="realtimeSingleDeviceId">
                <option value="">选择设备</option>
                <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
                  {{ device.displayName || device.normalizedId }}
                </option>
              </select>
            </label>
            <label class="realtime-field">
              <span>点位引用</span>
              <input v-model="realtimeSinglePointId" type="text" placeholder="pointId 或点位编码" />
            </label>
            <button type="button" class="primary" :disabled="singleRealtimeSubmitDisabled" @click="loadSingleRealtime">
              查询单点
            </button>
            <div v-if="singleRealtimeError" class="realtime-notice realtime-notice-error realtime-single-error" role="alert">
              {{ singleRealtimeError }}
            </div>
          </div>
          <pre class="realtime-single-result" tabindex="0" aria-label="单点实时查询完整结果">{{ prettyJson(realtimeSingleResult) }}</pre>
        </div>
      </details>
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
const realtimeQualityFilter = ref<"ALL" | "GOOD" | "BAD" | "UNKNOWN">("ALL");
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
  const rows = keyword
    ? filterRealtimeRows(displayRealtimeRows.value, keyword, deviceDisplayNameLookup.value, realtimeDeviceId.value)
    : displayRealtimeRows.value;
  if (realtimeQualityFilter.value === "ALL") {
    return rows;
  }
  return rows.filter((row) => {
    const qualityClass = realtimeQualityClass(row);
    const group = qualityClass === "is-good" ? "GOOD" : qualityClass === "is-bad" ? "BAD" : "UNKNOWN";
    return group === realtimeQualityFilter.value;
  });
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

<style scoped src="@/features/realtime/styles/realtime-view.css"></style>
