<template>
  <section class="exact-page history-view">
    <header class="history-heading">
      <div class="history-title-line">
        <h1>历史趋势</h1>
        <span class="history-badge" :class="{ 'is-warning': Boolean(historyError || historyPartialWarning) }">
          {{ historyPresentation.recordCount }} 条 · 对比 {{ comparePointRefs.length }} 个点位
        </span>
      </div>
      <p>围绕同一设备，查询主点位并对比其他点位</p>
    </header>

    <div class="history-page-body">
      <section class="history-panel history-query-panel" aria-label="历史查询条件">
        <div class="history-query-fields">
          <label class="history-field">
            <span>设备</span>
            <select v-model="deviceId" @change="handleDeviceChange">
              <option value="">选择设备</option>
              <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">
                {{ device.deviceName || deviceIdOf(device) }}
              </option>
            </select>
          </label>
          <label class="history-field">
            <span>点位</span>
            <select v-model="pointRef">
              <option value="">选择点位</option>
              <option v-for="point in points" :key="pointKey(point)" :value="pointKey(point)">
                {{ point.pointName || point.pointCode || point.pointId || point.address }}
              </option>
            </select>
          </label>
          <label class="history-field">
            <span>开始</span>
            <input v-model="startTime" type="datetime-local" title="开始时间" />
          </label>
          <label class="history-field">
            <span>结束</span>
            <input v-model="endTime" type="datetime-local" title="结束时间" />
          </label>
          <label class="history-field history-limit">
            <span>条数</span>
            <input v-model.number="limit" type="number" min="10" max="2000" step="10" title="最大条数" />
          </label>
        </div>
        <div class="history-compare-row">
          <label class="history-field history-compare-field">
            <span>对比点位</span>
            <select v-model="comparePointRefs" multiple size="3" class="history-compare-select" title="按住 Ctrl/Command 多选对比点位">
              <option v-for="point in comparePointOptions" :key="`compare-${point.key}`" :value="point.key">
                {{ point.label }}
              </option>
            </select>
          </label>
          <p>可选多个点位做趋势对比<br /><small>按住 Ctrl / Command 多选</small></p>
          <div class="history-query-actions">
            <button type="button" class="history-button primary" :disabled="historyQueryDisabled || !deviceId || !pointRef" @click="loadHistory">查询历史</button>
            <button type="button" class="history-button" :disabled="loading || !historySeries.length" @click="downloadHistory">导出趋势</button>
          </div>
        </div>
      </section>

      <el-alert v-if="historyCapabilityMessage && !appStore.capabilities?.history.available" class="history-notice" :title="historyCapabilityMessage" type="warning" :closable="false" />
      <el-alert v-if="historyReadStatusText" class="history-notice" :title="historyReadStatusText" :type="historyReadStatusType" :closable="false" />
      <el-alert v-if="historyPartialWarning" class="history-notice" :title="historyPartialWarning" type="warning" :closable="false" />

      <div v-if="historySummaryCards.length" class="history-summary">
        <article v-for="card in historySummaryCards" :key="card.label" :class="{ 'history-time-range': card.label === '时间范围', 'history-value-range': card.label === '数值范围' }">
          <span>{{ card.label }}</span>
          <strong :title="historyPresentation.hasResult || card.label === '时间范围' ? card.value : historyPresentation.stateLabel">
            {{ historyPresentation.hasResult || card.label === '时间范围' ? card.value : '—' }}
          </strong>
          <small>{{ historyPresentation.hasResult || card.label === '时间范围' ? card.detail : historyPresentation.stateLabel }}</small>
        </article>
      </div>

      <div class="history-trend-grid">
        <section class="history-panel history-curve-panel">
          <div class="history-panel-head">
            <h2>点位历史曲线</h2>
            <span>{{ selectedPointLabel }}</span>
          </div>
          <div v-if="historyChartPresentation" class="history-chart-wrap">
            <div class="history-chart-caption"><span>数值</span><span>按采样顺序排列</span></div>
            <svg class="history-chart-svg" viewBox="0 0 780 320" role="img" aria-label="主点位与对比点位历史折线，按各自样本序号排列，使用统一原始数值轴">
              <g class="history-chart-grid">
                <path d="M64 56H744M64 107H744M64 158H744M64 209H744M64 260H744" />
                <line v-for="tick in historyChartPresentation.sampleTicks" :key="tick.index" :x1="tick.x" y1="56" :x2="tick.x" y2="260" class="history-chart-grid-vertical" />
              </g>
              <g class="history-chart-value-labels">
                <text v-for="tick in historyChartPresentation.valueTicks" :key="tick.y" x="50" :y="tick.y + 4" text-anchor="end" :textLength="tick.label.length > 8 ? 48 : undefined" lengthAdjust="spacingAndGlyphs">
                  <title>{{ tick.value }}</title>{{ tick.label }}
                </text>
              </g>
              <g class="history-chart-sample-labels">
                <text v-for="tick in historyChartPresentation.sampleTicks" :key="tick.index" :x="tick.x" y="290" text-anchor="middle">样本 {{ tick.index }}</text>
              </g>
              <svg x="64" y="30.5" width="680" height="255" viewBox="0 0 100 40" preserveAspectRatio="none" class="history-chart-curves">
                <polyline
                  v-for="series in historySeries"
                  :key="series.key"
                  :points="series.points"
                  fill="none"
                  :stroke="series.color"
                  stroke-width="2.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  vector-effect="non-scaling-stroke"
                />
              </svg>
            </svg>
          </div>
          <div v-else class="history-chart-empty">
            <strong>{{ historyPresentation.chartEmptyTitle }}</strong>
            <p>{{ historyPresentation.chartEmptyDetail }}</p>
          </div>
          <div v-if="historyPresentation.hasResult" class="history-legend">
            <span v-for="series in historySeries" :key="series.key">
              <i :style="{ backgroundColor: series.color }"></i>
              {{ series.label }} <b>{{ series.latestText }}</b>
            </span>
          </div>
          <p v-if="historyChartPresentation" class="history-chart-note">横向距离表示各曲线的样本顺序，不表示等时间间隔；序号参考最长曲线，统一原始数值尺度，不换算单位。</p>
          <p v-if="failedComparePointRefs.length" class="history-compare-note">摘要统计成功曲线；标题和下方的对比点位数为已选择数量。</p>
          <div class="history-stat-row">
            <span>主曲线记录数 <b>{{ historyPresentation.recordCount }}</b></span>
            <span>对比点位 <b>{{ comparePointRefs.length }}</b></span>
            <span>最小值 <b>{{ historyMin }}</b></span>
            <span>最大值 <b>{{ historyMax }}</b></span>
            <span>最新值 <b>{{ historyLatest }}</b></span>
          </div>
        </section>

        <section class="history-panel history-json-panel">
          <div class="history-panel-head">
            <h2>查询结果 JSON</h2>
            <button type="button" class="history-button history-json-refresh" :disabled="historyQueryDisabled || !deviceId || !pointRef" @click="loadHistory">刷新</button>
          </div>
          <div class="history-json-body">
            <pre class="history-json-view" tabindex="0" aria-label="完整历史查询 JSON，可独立横向和纵向滚动">{{ historyExportText }}</pre>
          </div>
        </section>
      </div>

      <section class="history-panel history-alarm-panel">
        <div class="history-panel-head">
          <h2>相关告警</h2>
          <span>{{ historyPresentation.alarmSummary }}</span>
        </div>
        <div class="history-table-scroll history-alarm-scroll" tabindex="0" role="region" aria-label="完整相关告警表，可独立滚动">
          <table class="history-alarm-table">
            <thead>
              <tr><th>时间</th><th>级别</th><th>设备</th><th>点位</th><th>内容</th></tr>
            </thead>
            <tbody>
              <tr v-for="alarm in relatedAlarms" :key="`${alarm.alarmId || alarm.id || '-'}-${alarm.timestamp || alarm.occurTime || '-'}`">
                <td class="history-mono">{{ formatHistoryTime({ timestamp: alarm.timestamp || alarm.occurTime }) }}</td>
                <td><span class="history-badge" :class="{ 'is-warning': alarm.level === 'WARN' }">{{ alarm.level || alarm.alarmType || '-' }}</span></td>
                <td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td>
                <td>{{ alarm.pointName || alarm.pointCode || alarm.pointId || '-' }}</td>
                <td>{{ alarm.content || alarm.message || alarm.alarmContent || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="relatedAlarms.length === 0" class="history-table-empty" role="status">{{ historyPresentation.alarmEmpty }}</div>
      </section>

      <section class="history-panel history-data-panel">
        <div class="history-panel-head">
          <h2>历史数据表</h2>
          <span class="history-mono">{{ deviceId || '-' }} / {{ pointRef || '-' }}</span>
        </div>
        <div class="history-table-scroll history-data-scroll" tabindex="0" role="region" aria-label="完整历史记录表，可独立横向和纵向滚动">
          <table class="history-data-table">
            <thead>
              <tr><th>时间</th><th>值</th><th>质量</th><th>原始记录</th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in historyRows" :key="`${formatHistoryTime(row)}-${index}`">
                <td class="history-mono">{{ formatHistoryTime(row) }}</td>
                <td :title="String(displayValue(row))">{{ displayValue(row) }}</td>
                <td>{{ row.quality || row.qualityCode || '-' }}</td>
                <td><code>{{ compactJson(row) }}</code></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="historyRows.length === 0" class="history-table-empty" role="status">{{ historyPresentation.dataEmpty }}</div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { getDeviceAlarmHistory, getPointHistory } from "@/api/data.api";
import { getDevicePointsConfig } from "@/api/config.api";
import { normalizeAlarmHistoryRows } from "@/features/alarm/utils/alarm-history-utils";
import { normalizeHistoryRows, type HistoryRow } from "@/features/history/utils/history-data-utils";
import { resolveHistoryPartialFailure, type HistoryPartialFailureState } from "@/features/history/utils/history-partial-failure";
import {
  buildHistoryDataQueryParams,
  buildHistoryPointsRequestContext,
  buildHistoryPointsRequestSnapshot,
  buildHistoryQueryContext,
  buildHistoryRelatedAlarmQuery,
  isSameHistoryPointsRequestContext,
  isSameHistoryQueryContext,
  shouldDisableHistorySubmit,
  type HistoryQueryContext
} from "@/features/history/utils/history-request-lifecycle";
import { buildHistoryTrendExportText, buildHistoryTrendSeries, buildHistoryTrendSummaryCards } from "@/features/history/utils/history-trend-utils";
import { buildContextualReadStatus, hasLastGoodForContext, shouldClearLastGoodForRequest } from "@/features/request/utils/context-last-good";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { AlarmRow } from "@/types/monitor";
import type { DeviceViewModel } from "@/types/device";
import type { DataPoint } from "@/types/point";

const route = useRoute();
const appStore = useAppStore();
const deviceStore = useDeviceStore();

const deviceId = ref("");
const pointRef = ref("");
const comparePointRefs = ref<string[]>([]);
const points = ref<DataPoint[]>([]);
const historyRows = ref<HistoryRow[]>([]);
const comparePointRows = ref<Record<string, HistoryRow[]>>({});
const relatedAlarms = ref<AlarmRow[]>([]);
const historyError = ref("");
const historyPartialWarning = ref("");
const failedComparePointRefs = ref<string[]>([]);
const relatedAlarmsUnavailable = ref(false);
const loading = ref(false);
const limit = ref(200);
const startTime = ref(defaultDateTimeLocal(-60 * 60 * 1000));
const endTime = ref(defaultDateTimeLocal(0));
const initialized = ref(false);
const lastAppliedRouteKey = ref("__initial__");
const pendingHistoryQueryContext = ref<HistoryQueryContext | null>(null);
const lastSuccessfulHistoryContext = ref<HistoryQueryContext | null>(null);
const historyLastSuccessAt = ref<number | null>(null);

const historyPointsOwner = createLatestRequestOwner(isSameHistoryPointsRequestContext);
const historyQueryOwner = createLatestRequestOwner(isSameHistoryQueryContext);

const devices = computed(() => deviceStore.devices);

const selectedPointLabel = computed(() => {
  const point = points.value.find((item) => pointKey(item) === pointRef.value);
  const base = point ? `${point.pointName || point.pointCode || point.pointId || point.address} · ${point.dataType || "未知类型"}` : "未选择点位";
  return comparePointRefs.value.length ? `${base} · 对比 ${comparePointRefs.value.length} 项` : base;
});

const comparePointOptions = computed(() => points.value
  .map((point) => ({ key: pointKey(point), label: point.pointName || point.pointCode || point.pointId || point.address || "未命名点位" }))
  .filter((item) => item.key && item.key !== pointRef.value));

const historySeries = computed(() => buildHistoryTrendSeries([
  { key: pointRef.value, label: selectedPointLabel.value, rows: historyRows.value },
  ...comparePointRefs.value
    .filter((ref) => ref && ref !== pointRef.value)
    .filter((ref) => !failedComparePointRefs.value.includes(ref))
    .map((ref) => ({ key: ref, label: pointLabelOf(ref), rows: comparePointRows.value[ref] || [] }))
]));

const numericValues = computed(() => historyRows.value.map((row) => Number(displayValue(row))).filter((value) => Number.isFinite(value)));
const historyMin = computed(() => numericValues.value.length ? Math.min(...numericValues.value).toFixed(2) : "-");
const historyMax = computed(() => numericValues.value.length ? Math.max(...numericValues.value).toFixed(2) : "-");
const historyLatest = computed(() => historyRows.value.length ? String(displayValue(historyRows.value[historyRows.value.length - 1])) : "-");
const historyTimeRangeText = computed(() => {
  if (!startTime.value || !endTime.value) {
    return "-";
  }
  return `${startTime.value.replace("T", " ")} ~ ${endTime.value.replace("T", " ")}`;
});
const historySummaryCards = computed(() => buildHistoryTrendSummaryCards({
  deviceId: deviceId.value,
  pointRef: pointRef.value,
  pointLabel: selectedPointLabel.value,
  series: historySeries.value,
  relatedAlarms: relatedAlarms.value,
  relatedAlarmsUnavailable: relatedAlarmsUnavailable.value,
  timeRangeText: historyTimeRangeText.value
}));
const historyExportText = computed(() => buildHistoryTrendExportText({
  deviceId: deviceId.value,
  pointRef: pointRef.value,
  pointLabel: selectedPointLabel.value,
  series: historySeries.value,
  relatedAlarms: relatedAlarms.value,
  relatedAlarmsUnavailable: relatedAlarmsUnavailable.value,
  failedComparePointRefs: failedComparePointRefs.value,
  partialWarning: historyPartialWarning.value
}));
const relatedAlarmsSummaryText = computed(() => relatedAlarmsUnavailable.value ? "暂不可用" : `${relatedAlarms.value.length} 条`);
const relatedAlarmsEmptyText = computed(() => relatedAlarmsUnavailable.value ? "关联告警暂不可用" : "暂无相关告警");
const hasHistoryLastGoodForCurrentContext = computed(() => hasLastGoodForContext(lastSuccessfulHistoryContext.value, currentHistoryQueryContext(), isSameHistoryQueryContext));
const historyReadStatusText = computed(() => buildContextualReadStatus({
  loading: loading.value,
  error: historyError.value,
  lastSuccessfulContext: lastSuccessfulHistoryContext.value,
  currentContext: currentHistoryQueryContext(),
  isSameContext: isSameHistoryQueryContext,
  loadingText: "历史数据加载中...",
  refreshingText: "刷新中 · 当前显示上次成功历史数据",
  staleText: "刷新失败 · 当前显示上次成功历史数据",
  initialErrorPrefix: "历史数据加载失败",
  lastSuccessAt: historyLastSuccessAt.value
}));
const historyReadStatusType = computed(() => historyError.value && hasHistoryLastGoodForCurrentContext.value ? "warning" : "error");
const historyEmptyText = computed(() => historyError.value ? `历史数据加载失败：${historyError.value}` : "暂无历史数据");
const historyQueryDisabled = computed(() => !appStore.capabilities?.history.available || shouldDisableHistorySubmit(
  loading.value,
  pendingHistoryQueryContext.value,
  currentHistoryQueryContext()
));
const historyCapabilityMessage = computed(() => appStore.capabilities?.history.reason || appStore.capabilitiesError);

onMounted(async () => {
  await appStore.initialize();
  await deviceStore.refresh();
  if (appStore.capabilities == null) {
    await appStore.refreshCapabilities();
  }
  await applyRouteQuery({ autoQuery: appStore.capabilities?.history.available === true });
  initialized.value = true;
});

watch(() => [route.query.deviceId, route.query.pointId, route.query.pointRef], () => {
  if (!initialized.value) {
    return;
  }
  void applyRouteQuery({ autoQuery: true });
});

onBeforeUnmount(() => {
  historyPointsOwner.invalidate();
  historyQueryOwner.invalidate();
  loading.value = false;
  pendingHistoryQueryContext.value = null;
});

async function handleDeviceChange() {
  deviceStore.selectDevice(deviceId.value);
  await loadPoints();
}

async function loadPoints(options: { preferredPointRef?: string; autoQuery?: boolean } = {}) {
  const snapshot = buildHistoryPointsRequestSnapshot({
    deviceId: deviceId.value,
    preferredPointRef: options.preferredPointRef,
    autoQuery: options.autoQuery
  });
  pointRef.value = "";
  comparePointRefs.value = [];
  historyRows.value = [];
  comparePointRows.value = {};
  relatedAlarms.value = [];
  clearHistoryLastGoodState();
  clearHistoryFeedbackState();
  if (!snapshot.deviceId) {
    historyPointsOwner.invalidate();
    points.value = [];
    return;
  }
  const ticket = historyPointsOwner.begin(buildHistoryPointsRequestContext(snapshot));
  try {
    const response = await getDevicePointsConfig(snapshot.deviceId);
    const nextPoints = extractPoints(response);
    if (!historyPointsOwner.canCommit(ticket, currentHistoryPointsRequestContext())) {
      return;
    }
    points.value = nextPoints;
    const resolvedPointRef = resolvePointRefFromPoints(nextPoints, snapshot.preferredPointRef);
    pointRef.value = resolvedPointRef || pointKey(nextPoints[0]);
    if (snapshot.autoQuery && resolvedPointRef) {
      await loadHistory();
    }
  } catch (error) {
    if (!historyPointsOwner.canCommit(ticket, currentHistoryPointsRequestContext())) {
      return;
    }
    points.value = [];
    ElMessage.warning(error instanceof Error ? error.message : "点位配置加载失败");
  }
}

async function applyRouteQuery(options: { autoQuery: boolean }) {
  const routeKey = routeQueryKey();
  if (routeKey === lastAppliedRouteKey.value) {
    return;
  }
  lastAppliedRouteKey.value = routeKey;

  const queryDeviceId = firstQueryValue(route.query.deviceId);
  const queryPointRef = firstQueryValue(route.query.pointId) || firstQueryValue(route.query.pointRef);
  const targetDeviceId = resolveTargetDeviceId(queryDeviceId);
  if (!targetDeviceId) {
    historyPointsOwner.invalidate();
    historyQueryOwner.invalidate();
    loading.value = false;
    pendingHistoryQueryContext.value = null;
    deviceId.value = "";
    points.value = [];
    pointRef.value = "";
    comparePointRefs.value = [];
    historyRows.value = [];
    comparePointRows.value = {};
    relatedAlarms.value = [];
    clearHistoryLastGoodState();
    clearHistoryFeedbackState();
    return;
  }

  if (deviceStore.selectedDeviceId !== targetDeviceId) {
    deviceStore.selectDevice(targetDeviceId);
  }

  if (deviceId.value !== targetDeviceId || points.value.length === 0) {
    deviceId.value = targetDeviceId;
    await loadPoints({ preferredPointRef: queryPointRef, autoQuery: options.autoQuery && Boolean(queryPointRef) });
    return;
  }

  const resolvedPointRef = resolvePointRef(queryPointRef);
  if (resolvedPointRef) {
    pointRef.value = resolvedPointRef;
    if (options.autoQuery && queryPointRef) {
      await loadHistory();
    }
  }
}

async function loadHistory() {
  if (appStore.capabilities?.history.available !== true) {
    ElMessage.warning(appStore.capabilities?.history.reason || appStore.capabilitiesError || "当前服务未启用历史存储能力");
    return;
  }
  const requestContext = currentHistoryQueryContext();
  if (!requestContext.deviceId || !requestContext.pointRef) {
    ElMessage.warning("请先选择设备和点位");
    return;
  }
  const ticket = historyQueryOwner.begin(requestContext);
  const canPreserveLastGood = hasLastGoodForContext(lastSuccessfulHistoryContext.value, requestContext, isSameHistoryQueryContext);
  if (shouldClearLastGoodForRequest(lastSuccessfulHistoryContext.value, requestContext, isSameHistoryQueryContext)) {
    clearHistoryInvestigationState();
    clearHistoryLastGoodState();
  }
  loading.value = true;
  pendingHistoryQueryContext.value = requestContext;
  if (canPreserveLastGood) {
    historyError.value = "";
  } else {
    clearHistoryFeedbackState();
  }
  try {
    const params = buildHistoryDataQueryParams(requestContext);
    const mainRequest = getPointHistory(requestContext.deviceId, requestContext.pointRef, params)
      .then((response) => normalizeHistoryRows(response));
    const compareRequests = requestContext.comparePointRefs.map((ref) => ({
      ref,
      request: getPointHistory(requestContext.deviceId, ref, params)
        .then((response) => normalizeHistoryRows(response))
    }));
    const relatedAlarmRequest = loadRelatedAlarms(requestContext);
    const settledResults = await Promise.allSettled([
      mainRequest,
      ...compareRequests.map((item) => item.request),
      relatedAlarmRequest
    ]);
    const nextState = resolveHistoryPartialFailure({
      mainResult: settledResults[0] as PromiseSettledResult<HistoryRow[]>,
      compareResults: compareRequests.map((item, index) => ({
        ref: item.ref,
        result: settledResults[index + 1] as PromiseSettledResult<HistoryRow[]>
      })),
      relatedAlarmsResult: settledResults[settledResults.length - 1] as PromiseSettledResult<AlarmRow[]>,
      pointLabelOf
    });
    if (!historyQueryOwner.canCommit(ticket, currentHistoryQueryContext())) {
      return;
    }
    if (nextState.historyError) {
      applyHistoryMainFailureState(nextState, canPreserveLastGood);
    } else {
      applyHistoryPartialState(nextState);
      lastSuccessfulHistoryContext.value = requestContext;
      historyLastSuccessAt.value = Date.now();
    }
    if (nextState.historyError) {
      ElMessage.error(nextState.historyError);
    } else if (nextState.historyPartialWarning) {
      ElMessage.warning(nextState.historyPartialWarning);
    }
  } catch (error) {
    if (!historyQueryOwner.canCommit(ticket, currentHistoryQueryContext())) {
      return;
    }
    const nextState = buildUnexpectedHistoryFailureState(error);
    applyHistoryMainFailureState(nextState, canPreserveLastGood);
    ElMessage.error(nextState.historyError);
  } finally {
    if (historyQueryOwner.isLatest(ticket)) {
      loading.value = false;
      pendingHistoryQueryContext.value = null;
    }
  }
}

async function loadRelatedAlarms(snapshot: HistoryQueryContext): Promise<AlarmRow[]> {
  const response = await getDeviceAlarmHistory(snapshot.deviceId, buildHistoryRelatedAlarmQuery(snapshot));
  return normalizeAlarmHistoryRows(response);
}

function applyHistoryPartialState(state: HistoryPartialFailureState) {
  historyRows.value = state.historyRows;
  comparePointRows.value = state.comparePointRows;
  relatedAlarms.value = state.relatedAlarms;
  failedComparePointRefs.value = state.failedComparePointRefs;
  relatedAlarmsUnavailable.value = state.relatedAlarmsUnavailable;
  historyError.value = state.historyError;
  historyPartialWarning.value = state.historyPartialWarning;
}

function applyHistoryMainFailureState(state: HistoryPartialFailureState, preserveLastGood: boolean) {
  if (preserveLastGood) {
    historyError.value = state.historyError;
    return;
  }
  applyHistoryPartialState(state);
}

function clearHistoryInvestigationState() {
  historyRows.value = [];
  comparePointRows.value = {};
  relatedAlarms.value = [];
  failedComparePointRefs.value = [];
  relatedAlarmsUnavailable.value = false;
}

function clearHistoryLastGoodState() {
  lastSuccessfulHistoryContext.value = null;
  historyLastSuccessAt.value = null;
}

function clearHistoryFeedbackState() {
  historyError.value = "";
  historyPartialWarning.value = "";
  failedComparePointRefs.value = [];
  relatedAlarmsUnavailable.value = false;
}

function buildUnexpectedHistoryFailureState(error: unknown): HistoryPartialFailureState {
  return {
    historyRows: [],
    comparePointRows: {},
    relatedAlarms: [],
    failedComparePointRefs: [],
    relatedAlarmsUnavailable: false,
    historyError: error instanceof Error && error.message
      ? `主历史查询失败：${error.message}`
      : "主历史查询失败",
    historyPartialWarning: ""
  };
}

function currentHistoryPointsRequestContext() {
  return buildHistoryPointsRequestContext({ deviceId: deviceId.value });
}

function currentHistoryQueryContext() {
  return buildHistoryQueryContext({
    deviceId: deviceId.value,
    pointRef: pointRef.value,
    comparePointRefs: [...comparePointRefs.value],
    startTime: startTime.value,
    endTime: endTime.value,
    limit: limit.value
  });
}

function downloadHistory() {
  if (!historySeries.value.length) {
    return;
  }
  const blob = new Blob([historyExportText.value], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `collector-history-${deviceId.value || "device"}-${pointRef.value || "point"}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function extractPoints(value: unknown): DataPoint[] {
  if (Array.isArray(value)) {
    return value as DataPoint[];
  }
  if (!value || typeof value !== "object") {
    return [];
  }
  const record = value as Record<string, unknown>;
  for (const key of ["points", "data", "items", "records", "rows"]) {
    if (Array.isArray(record[key])) {
      return record[key] as DataPoint[];
    }
    const nested = extractPoints(record[key]);
    if (nested.length > 0) {
      return nested;
    }
  }
  return [];
}

function resolveTargetDeviceId(preferredDeviceId: string): string {
  const matchedDevice = preferredDeviceId ? devices.value.find((device) => deviceMatchesId(device, preferredDeviceId)) : undefined;
  if (matchedDevice) {
    return deviceIdOf(matchedDevice);
  }
  if (preferredDeviceId && devices.value.length === 0) {
    return preferredDeviceId;
  }
  if (deviceStore.selectedDeviceId && (devices.value.length === 0 || devices.value.some((device) => deviceMatchesId(device, deviceStore.selectedDeviceId)))) {
    return deviceStore.selectedDeviceId;
  }
  return deviceIdOf(devices.value[0]);
}

function deviceMatchesId(device: DeviceViewModel, value: string): boolean {
  return [device.deviceId || device.id || device.normalizedId]
    .filter((item) => item !== undefined && item !== null)
    .map(String)
    .includes(value);
}

function resolvePointRef(value?: string): string {
  if (!value) {
    return "";
  }
  const point = points.value.find((item) => pointMatchesRef(item, value));
  return pointKey(point);
}

function resolvePointRefFromPoints(pointList: DataPoint[], value?: string): string {
  if (!value) {
    return "";
  }
  const point = pointList.find((item) => pointMatchesRef(item, value));
  return pointKey(point);
}

function pointMatchesRef(point: DataPoint, value: string): boolean {
  return [point.pointId, point.pointCode, point.id, point.address]
    .filter((item) => item !== undefined && item !== null)
    .map(String)
    .includes(value);
}

function firstQueryValue(value: unknown): string {
  const raw = Array.isArray(value) ? value[0] : value;
  return typeof raw === "string" ? raw : "";
}

function routeQueryKey(): string {
  return [firstQueryValue(route.query.deviceId), firstQueryValue(route.query.pointId), firstQueryValue(route.query.pointRef)].join("|");
}

function deviceIdOf(device?: DeviceViewModel): string {
  return String(device?.deviceId || device?.id || device?.normalizedId || "");
}

function pointKey(point?: DataPoint): string {
  return String(point?.pointId || point?.pointCode || point?.id || point?.address || "");
}

function pointLabelOf(ref: string): string {
  const point = points.value.find((item) => pointKey(item) === ref);
  return point ? `${point.pointName || point.pointCode || point.pointId || point.address || ref} · ${point.dataType || "未知类型"}` : ref;
}

function displayValue(row: HistoryRow): unknown {
  return row.value ?? row.currentValue ?? row.rawValue ?? row.val ?? "-";
}

function formatHistoryTime(row: { timestamp?: number | string; time?: number | string; collectTime?: number | string; createdAt?: number | string }): string {
  const raw = row.timestamp || row.time || row.collectTime || row.createdAt;
  if (!raw) {
    return "-";
  }
  if (typeof raw !== "string" && typeof raw !== "number") {
    return String(raw);
  }
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? String(raw) : date.toLocaleString();
}

function compactJson(value: unknown): string {
  return JSON.stringify(value);
}

function defaultDateTimeLocal(offsetMs: number): string {
  const date = new Date(Date.now() + offsetMs);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 展示派生只读取原状态；首次未成功时不把空数组画成零，保留原有上次成功结果与反馈。
const historyPresentation = computed(() => {
  const hasResult = historyLastSuccessAt.value !== null;
  const capabilityUnavailable = appStore.capabilities?.history.available === false;
  const stateLabel = loading.value ? "历史数据加载中" : historyError.value ? "历史数据加载失败" : capabilityUnavailable ? "历史存储能力不可用" : appStore.capabilities == null ? "历史存储能力待确认" : "尚未查询";
  const dataEmpty = hasResult || historyError.value ? historyEmptyText.value : stateLabel;
  return {
    hasResult,
    stateLabel,
    recordCount: hasResult ? String(historyRows.value.length) : "—",
    alarmSummary: relatedAlarmsUnavailable.value || hasResult ? relatedAlarmsSummaryText.value : loading.value ? "加载中" : "未知",
    alarmEmpty: relatedAlarmsUnavailable.value || hasResult ? relatedAlarmsEmptyText.value : loading.value ? "关联告警加载中" : "关联告警尚未加载，数量未知",
    dataEmpty,
    chartEmptyTitle: hasResult
      ? historySeries.value.some((series) => series.rows.length) ? "当前历史记录无可绘制数值" : "当前范围内暂无历史数据"
      : stateLabel,
    chartEmptyDetail: hasResult
      ? "可调整设备、点位或时间范围；完整记录保留在 JSON 和历史数据表中。"
      : historyError.value ? historyEmptyText.value : capabilityUnavailable
        ? historyCapabilityMessage.value || "保留查询条件，当前不能判断历史数据是否为零。"
        : loading.value ? "主点位与对比点位分别查询，等待当前请求完成。" : "请选择设备和点位后查询历史数据。"
  };
});

// 轴读取成功 series 的原始数值，取值优先级与原 helper 相同；不改任何 curve 点或 formatter。
const historyChartPresentation = computed(() => {
  const valuesBySeries = historySeries.value.map((series) => series.rows
    .map((row) => Number(row.value ?? row.currentValue ?? row.rawValue ?? row.val))
    .filter((value) => Number.isFinite(value)));
  const allValues = valuesBySeries.flat();
  if (!allValues.length) {
    return null;
  }
  const minimum = allValues.reduce((result, value) => Math.min(result, value), allValues[0]);
  const maximum = allValues.reduce((result, value) => Math.max(result, value), allValues[0]);
  const sampleCount = Math.max(...valuesBySeries.map((values) => values.length));
  const tickCount = Math.min(6, sampleCount);
  const sampleTicks = Array.from({ length: tickCount }, (_, index) => {
    const sampleIndex = tickCount === 1 ? 0 : Math.round(index * (sampleCount - 1) / (tickCount - 1));
    return { index: sampleIndex + 1, x: sampleCount === 1 ? 64 : 64 + sampleIndex / (sampleCount - 1) * 680 };
  });
  const valueTicks = Array.from({ length: minimum === maximum ? 1 : 5 }, (_, index) => {
    const fraction = minimum === maximum ? 1 : index / 4;
    const value = fraction === 1 ? minimum : fraction === 0 ? maximum : maximum - (maximum - minimum) * fraction;
    return { value, label: String(value), y: 56 + fraction * 204 };
  });
  return { sampleTicks, valueTicks };
});
</script>

<style scoped src="@/features/history/styles/history-view.css"></style>
