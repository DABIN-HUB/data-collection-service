<template>
  <section class="exact-page legacy-history-panel">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>历史趋势</h1>
        <span class="heading-online"><i></i>{{ historyRows.length }} 条</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="loading || !deviceId || !pointRef" @click="loadHistory">查询历史</button>
      </div>
    </div>

    <div class="exact-page-body">
      <section class="exact-toolbar history-query-bar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="deviceId" @change="handleDeviceChange">
            <option value="">选择设备</option>
            <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">
              {{ device.deviceName || deviceIdOf(device) }}
            </option>
          </select>
          <select v-model="pointRef">
            <option value="">选择点位</option>
            <option v-for="point in points" :key="pointKey(point)" :value="pointKey(point)">
              {{ point.pointName || point.pointCode || point.pointId || point.address }}
            </option>
          </select>
          <input v-model="startTime" type="datetime-local" title="开始时间" />
          <input v-model="endTime" type="datetime-local" title="结束时间" />
          <input v-model.number="limit" type="number" min="10" max="2000" step="10" title="最大条数" />
        </div>
      </section>

      <div class="surface-grid two">
        <section class="surface-card wide-field">
          <div class="surface-card-head">
            <h3>点位历史曲线</h3>
            <span>{{ selectedPointLabel }}</span>
          </div>
          <div class="history-chart history-chart-dark">
            <svg v-if="chartPolyline" viewBox="0 0 100 40" preserveAspectRatio="none" role="img" aria-label="历史趋势折线">
              <polyline :points="chartPolyline" fill="none" stroke="#38bdf8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <div v-else class="empty-state compact">请选择设备和点位后查询历史数据</div>
          </div>
          <div class="history-stat-row">
            <span><b>{{ historyRows.length }}</b>记录数</span>
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
          <pre class="json-view history-json-view">{{ historyText }}</pre>
        </section>
      </div>

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

import { getDevicePointsConfig } from "@/api/config.api";
import { getPointHistory } from "@/api/data.api";
import { normalizeHistoryRows, type HistoryRow } from "@/views/runtime/runtime-utils";
import type { DeviceInfo } from "@/types/device";
import type { DataPoint } from "@/types/point";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId: string;
}>();

const emit = defineEmits<{
  selectDevice: [deviceId: string];
}>();

const deviceId = ref("");
const pointRef = ref("");
const points = ref<DataPoint[]>([]);
const historyRows = ref<HistoryRow[]>([]);
const loading = ref(false);
const limit = ref(200);
const startTime = ref(defaultDateTimeLocal(-60 * 60 * 1000));
const endTime = ref(defaultDateTimeLocal(0));

const selectedPointLabel = computed(() => {
  const point = points.value.find((item) => pointKey(item) === pointRef.value);
  return point ? `${point.pointName || point.pointCode || point.pointId || point.address} · ${point.dataType || "未知类型"}` : "未选择点位";
});

const numericValues = computed(() => historyRows.value.map((row) => Number(displayValue(row))).filter((value) => Number.isFinite(value)));
const historyMin = computed(() => numericValues.value.length ? Math.min(...numericValues.value).toFixed(2) : "-");
const historyMax = computed(() => numericValues.value.length ? Math.max(...numericValues.value).toFixed(2) : "-");
const historyLatest = computed(() => historyRows.value.length ? String(displayValue(historyRows.value[historyRows.value.length - 1])) : "-");
const historyText = computed(() => JSON.stringify(historyRows.value, null, 2));
const chartPolyline = computed(() => {
  const values = numericValues.value;
  if (values.length < 2) {
    return "";
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  return values.map((value, index) => {
    const x = values.length === 1 ? 0 : (index / (values.length - 1)) * 100;
    const y = 36 - ((value - min) / span) * 32;
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
});

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
  historyRows.value = [];
  if (!deviceId.value) {
    points.value = [];
    return;
  }
  try {
    const response = await getDevicePointsConfig(deviceId.value);
    points.value = extractPoints(response);
    pointRef.value = pointKey(points.value[0]);
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
    const response = await getPointHistory(deviceId.value, pointRef.value, {
      startTime: startTime.value ? new Date(startTime.value).getTime() : undefined,
      endTime: endTime.value ? new Date(endTime.value).getTime() : undefined,
      limit: limit.value
    });
    historyRows.value = normalizeHistoryRows(response);
  } catch (error) {
    historyRows.value = [];
    ElMessage.error(error instanceof Error ? error.message : "历史数据查询失败");
  } finally {
    loading.value = false;
  }
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

function displayValue(row: HistoryRow): unknown {
  return row.value ?? row.currentValue ?? row.rawValue ?? row.val ?? "-";
}

function formatHistoryTime(row: HistoryRow): string {
  const raw = row.timestamp || row.time || row.collectTime || row.createdAt;
  if (!raw) {
    return "-";
  }
  if (typeof raw !== "string" && typeof raw !== "number" && !(raw instanceof Date)) {
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
