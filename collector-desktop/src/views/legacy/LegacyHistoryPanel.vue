<template>
  <section class="exact-page legacy-history-panel">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>历史趋势</h1>
        <span class="heading-online"><i></i>{{ historyRows.length }} 条 · 对比 {{ comparePointRefs.length }} 个点位</span>
      </div>
    </div>

    <div class="exact-page-body">
      <section class="exact-toolbar history-query-bar">
        <div class="history-filter-main">
          <label class="history-filter-field">
            <span>设备</span>
            <select v-model="deviceId" @change="handleDeviceChange">
              <option value="">选择设备</option>
              <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">
                {{ device.deviceName || deviceIdOf(device) }}
              </option>
            </select>
          </label>
          <label class="history-filter-field">
            <span>点位</span>
            <select v-model="pointRef">
              <option value="">选择点位</option>
              <option v-for="point in points" :key="pointKey(point)" :value="pointKey(point)">
                {{ point.pointName || point.pointCode || point.pointId || point.address }}
              </option>
            </select>
          </label>
          <label class="history-filter-field">
            <span>开始</span>
            <input v-model="startTime" type="datetime-local" title="开始时间" />
          </label>
          <label class="history-filter-field">
            <span>结束</span>
            <input v-model="endTime" type="datetime-local" title="结束时间" />
          </label>
          <label class="history-filter-field is-short">
            <span>条数</span>
            <input v-model.number="limit" type="number" min="10" max="2000" step="10" title="最大条数" />
          </label>
        </div>
        <div class="history-filter-bottom">
          <label class="history-filter-field history-compare-field">
            <span>对比点位</span>
            <select v-model="comparePointRefs" multiple size="3" class="history-compare-select" title="按住 Ctrl/Command 多选对比点位">
              <option v-for="point in comparePointOptions" :key="`compare-${point.key}`" :value="point.key">
                {{ point.label }}
              </option>
            </select>
            <small>可选多个点位做趋势对比</small>
          </label>
          <div class="history-query-actions">
            <button type="button" class="primary" :disabled="loading || !deviceId || !pointRef" @click="loadHistory">查询历史</button>
            <button type="button" class="primary" :disabled="loading || !historySeries.length" @click="downloadHistory">导出趋势</button>
          </div>
        </div>
      </section>

      <div v-if="historySummaryCards.length" class="exact-diagnostic-cards history-summary-cards">
        <div v-for="card in historySummaryCards" :key="card.label" class="exact-diagnostic-card">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.detail }}</small>
        </div>
      </div>

      <div class="surface-grid two">
        <section class="surface-card wide-field">
          <div class="surface-card-head">
            <h3>点位历史曲线</h3>
            <span>{{ selectedPointLabel }}</span>
          </div>
          <div class="history-chart history-chart-dark">
            <svg v-if="historySeries.length" viewBox="0 0 100 40" preserveAspectRatio="none" role="img" aria-label="历史趋势折线">
              <polyline
                v-for="series in historySeries"
                :key="series.key"
                :points="series.points"
                fill="none"
                :stroke="series.color"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <div v-else class="empty-state compact">请选择设备和点位后查询历史数据</div>
          </div>
          <div class="history-legend">
            <span v-for="series in historySeries" :key="series.key">
              <i :style="{ backgroundColor: series.color }"></i>
              {{ series.label }} · {{ series.latestText }}
            </span>
          </div>
          <div class="history-stat-row">
            <span><b>{{ historyRows.length }}</b>主曲线记录数</span>
            <span><b>{{ comparePointRefs.length }}</b>对比点位</span>
            <span><b>{{ historyMin }}</b>最小值</span>
            <span><b>{{ historyMax }}</b>最大值</span>
            <span><b>{{ historyLatest }}</b>最新值</span>
          </div>
        </section>

        <section class="surface-card">
          <div class="surface-card-head">
            <h3>查询结果 JSON</h3>
            <button type="button" :disabled="loading || !deviceId || !pointRef" @click="loadHistory">刷新</button>
          </div>
          <pre class="json-view history-json-view">{{ historyExportText }}</pre>
        </section>
      </div>

      <section class="surface-card history-alarm-card">
        <div class="surface-card-head">
          <h3>相关告警</h3>
          <span>{{ relatedAlarms.length }} 条</span>
        </div>
        <table class="runtime-table history-alarm-table">
          <thead>
            <tr><th>时间</th><th>级别</th><th>设备</th><th>点位</th><th>内容</th></tr>
          </thead>
          <tbody>
            <tr v-if="relatedAlarms.length === 0"><td colspan="5">暂无相关告警</td></tr>
            <tr v-for="alarm in relatedAlarms" :key="`${alarm.alarmId || alarm.id || '-'}-${alarm.timestamp || alarm.occurTime || '-'}`">
              <td>{{ formatHistoryTime({ timestamp: alarm.timestamp || alarm.occurTime }) }}</td>
              <td>{{ alarm.level || alarm.alarmType || '-' }}</td>
              <td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td>
              <td>{{ alarm.pointName || alarm.pointCode || alarm.pointId || '-' }}</td>
              <td>{{ alarm.content || alarm.message || alarm.alarmContent || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="exact-table-card">
        <div class="exact-table-title">
          <h2>历史数据表</h2>
          <span>{{ deviceId || '-' }} / {{ pointRef || '-' }}</span>
        </div>
        <table>
          <thead>
            <tr><th>时间</th><th>值</th><th>质量</th><th>原始记录</th></tr>
          </thead>
          <tbody>
            <tr v-if="historyRows.length === 0"><td colspan="4" class="exact-empty">暂无历史数据</td></tr>
            <tr v-for="(row, index) in historyRows" :key="`${formatHistoryTime(row)}-${index}`">
              <td>{{ formatHistoryTime(row) }}</td>
              <td>{{ displayValue(row) }}</td>
              <td>{{ row.quality || row.qualityCode || '-' }}</td>
              <td><code>{{ compactJson(row) }}</code></td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { getDeviceAlarmHistory, getPointHistory } from "@/api/data.api";
import { getDevicePointsConfig } from "@/api/config.api";
import { normalizeAlarmHistoryRows } from "@/features/alarm/utils/alarm-history-utils";
import { buildHistoryTrendExportText, buildHistoryTrendSeries, buildHistoryTrendSummaryCards } from "./history-trend-utils";
import { normalizeHistoryRows, type HistoryRow } from "@/views/runtime/runtime-utils";
import type { AlarmRow } from "@/types/monitor";
import type { DeviceInfo } from "@/types/device";
import type { DataPoint } from "@/types/point";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId: string;
  selectedPointRef?: string;
}>();

const emit = defineEmits<{
  selectDevice: [deviceId: string];
}>();

const deviceId = ref("");
const pointRef = ref("");
const comparePointRefs = ref<string[]>([]);
const points = ref<DataPoint[]>([]);
const historyRows = ref<HistoryRow[]>([]);
const comparePointRows = ref<Record<string, HistoryRow[]>>({});
const relatedAlarms = ref<AlarmRow[]>([]);
const loading = ref(false);
const limit = ref(200);
const startTime = ref(defaultDateTimeLocal(-60 * 60 * 1000));
const endTime = ref(defaultDateTimeLocal(0));

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
  timeRangeText: historyTimeRangeText.value
}));
const historyExportText = computed(() => buildHistoryTrendExportText({
  deviceId: deviceId.value,
  pointRef: pointRef.value,
  pointLabel: selectedPointLabel.value,
  series: historySeries.value,
  relatedAlarms: relatedAlarms.value
}));

onMounted(() => {
  deviceId.value = props.selectedDeviceId || deviceIdOf(props.devices[0]);
  if (deviceId.value) {
    void loadPoints();
  }
});

watch(() => props.selectedDeviceId, (value) => {
  if (value && value !== deviceId.value) {
    deviceId.value = value;
    void loadPoints();
  }
});

watch(() => props.selectedPointRef, (value) => {
  if (value && value !== pointRef.value) {
    pointRef.value = value;
    void loadHistory();
  }
});

watch(() => props.devices, () => {
  if (!deviceId.value && props.devices.length > 0) {
    deviceId.value = deviceIdOf(props.devices[0]);
    void loadPoints();
  }
});

async function handleDeviceChange() {
  emit("selectDevice", deviceId.value);
  await loadPoints();
}

async function loadPoints() {
  pointRef.value = "";
  comparePointRefs.value = [];
  historyRows.value = [];
  comparePointRows.value = {};
  relatedAlarms.value = [];
  if (!deviceId.value) {
    points.value = [];
    return;
  }
  try {
    const response = await getDevicePointsConfig(deviceId.value);
    points.value = extractPoints(response);
    pointRef.value = props.selectedPointRef && points.value.some((point) => pointKey(point) === props.selectedPointRef)
      ? props.selectedPointRef
      : pointKey(points.value[0]);
  } catch (error) {
    points.value = [];
    ElMessage.warning(error instanceof Error ? error.message : "点位配置加载失败");
  }
}

async function loadHistory() {
  if (!deviceId.value || !pointRef.value) {
    ElMessage.warning("请先选择设备和点位");
    return;
  }
  loading.value = true;
  try {
    const params = {
      startTs: startTime.value ? new Date(startTime.value).getTime() : undefined,
      endTs: endTime.value ? new Date(endTime.value).getTime() : undefined,
      limit: limit.value
    };
    const refs = [pointRef.value, ...comparePointRefs.value.filter((ref) => ref && ref !== pointRef.value)];
    const responses = await Promise.all(refs.map((ref) => getPointHistory(deviceId.value, ref, params)));
    historyRows.value = normalizeHistoryRows(responses[0]);
    comparePointRows.value = Object.fromEntries(refs.slice(1).map((ref, index) => [ref, normalizeHistoryRows(responses[index + 1])]));
    await loadRelatedAlarms();
  } catch (error) {
    historyRows.value = [];
    comparePointRows.value = {};
    relatedAlarms.value = [];
    ElMessage.error(error instanceof Error ? error.message : "历史数据查询失败");
  } finally {
    loading.value = false;
  }
}

async function loadRelatedAlarms() {
  const response = await getDeviceAlarmHistory(deviceId.value, {
    pointCode: pointRef.value,
    pointId: pointRef.value,
    startTs: startTime.value ? new Date(startTime.value).getTime() : undefined,
    endTs: endTime.value ? new Date(endTime.value).getTime() : undefined,
    limit: 20
  });
  relatedAlarms.value = normalizeAlarmHistoryRows(response);
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

function deviceIdOf(device?: DeviceInfo): string {
  return String(device?.deviceId || device?.id || device?.connectionKey || "");
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
</script>