<template>
  <section class="exact-surface device-runtime-panel">
    <div class="exact-surface-head">
      <h2>运行设备状态</h2>
      <span>设备调度与采集器运行态</span>
    </div>
    <div class="exact-toolbar runtime-device-toolbar">
      <div class="exact-toolbar-group exact-toolbar-filters">
        <select v-model="statusDeviceId">
          <option value="">选择设备查看状态</option>
          <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option>
        </select>
        <button type="button" class="primary" :disabled="!statusDeviceId" @click="loadDeviceStatus">查询单设备状态</button>
        <button type="button" :disabled="!statusDeviceId" @click="checkRunningFlag">检查是否运行</button>
      </div>
      <div class="exact-toolbar-group">
        <button type="button" :disabled="loading" @click="loadRuntimeOverview">{{ loading ? '刷新中' : '刷新运行列表' }}</button>
      </div>
    </div>
    <div class="exact-diagnostic-cards runtime-summary-cards">
      <div class="exact-diagnostic-card"><span>配置设备</span><strong>{{ runtimeSummary.total }}</strong></div>
      <div class="exact-diagnostic-card"><span>正在运行</span><strong>{{ runtimeSummary.running }}</strong></div>
      <div class="exact-diagnostic-card"><span>连接正常</span><strong>{{ runtimeSummary.connected }}</strong></div>
      <div class="exact-diagnostic-card"><span>异常/退化</span><strong>{{ runtimeSummary.abnormal }}</strong></div>
    </div>
    <section class="exact-table-card runtime-device-table">
      <table>
        <thead><tr><th>设备</th><th>阶段</th><th>运行</th><th>连接</th><th>重连</th><th>失败次数</th><th>代次</th><th>最近成功</th><th>退化原因</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="runtimeRows.length === 0"><td colspan="10" class="exact-empty">暂无运行态快照，可点击刷新运行列表</td></tr>
          <tr v-for="row in runtimeRows" :key="row.deviceId">
            <td><strong>{{ deviceNameOf(row.deviceId) }}</strong><br><code>{{ row.deviceId }}</code></td>
            <td>{{ row.phase || '-' }}</td>
            <td><span class="status-badge" :class="row.running ? 'is-online' : ''">{{ row.running ? '运行中' : '未运行' }}</span></td>
            <td><span class="status-badge" :class="row.connected ? 'is-online' : 'is-error'">{{ row.connected ? '已连接' : '未连接' }}</span></td>
            <td>{{ row.reconnecting ? '重连中' : '-' }}</td>
            <td>{{ row.consecutiveFailures ?? '-' }}</td>
            <td>{{ row.generation ?? '-' }}</td>
            <td>{{ formatTime(row.lastSuccessfulCollectionAt) }}</td>
            <td>{{ row.degradedReason || '-' }}</td>
            <td><button type="button" @click="selectRuntimeDevice(row.deviceId)">查状态</button></td>
          </tr>
        </tbody>
      </table>
    </section>
    <details class="exact-json-panel" open>
      <summary>单设备状态 JSON</summary>
      <pre class="json-view compact-result-view">{{ prettyJson(statusDetail) }}</pre>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { getDeviceRuntime, getDeviceStatus, getRunningDevices, isDeviceRunning } from "@/api/device.api";
import type { DeviceInfo, DeviceRuntimeSnapshot } from "@/types/device";
import { buildDeviceRuntimeSummary, normalizeDeviceRunningFlag, normalizeDeviceRuntimeRows, normalizeDeviceStatusDetail, normalizeRunningDeviceIds } from "../utils/device-runtime-utils";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId?: string;
}>();
const emit = defineEmits<{
  (event: "select-device", deviceId: string): void;
}>();

const loading = ref(false);
const runtimeRows = ref<DeviceRuntimeSnapshot[]>([]);
const runningDeviceIds = ref<string[]>([]);
const statusDeviceId = ref(props.selectedDeviceId || "");
const statusDetail = ref<unknown>({ message: "请选择设备后查询单设备运行状态" });

const runtimeSummary = computed(() => buildDeviceRuntimeSummary(runtimeRows.value, props.devices.length || runtimeRows.value.length));

async function loadRuntimeOverview() {
  loading.value = true;
  try {
    const [runningResult, runtimeResult] = await Promise.allSettled([getRunningDevices(), getDeviceRuntime()]);
    runningDeviceIds.value = runningResult.status === "fulfilled" ? normalizeRunningDeviceIds(runningResult.value) : [];
    const rows = runtimeResult.status === "fulfilled" ? normalizeDeviceRuntimeRows(runtimeResult.value) : [];
    runtimeRows.value = rows.length ? rows : runningDeviceIds.value.map((deviceId) => ({ deviceId, running: true, connected: true }));
  } finally {
    loading.value = false;
  }
}

async function loadDeviceStatus() {
  if (!statusDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const [statusResult, runningResult] = await Promise.allSettled([getDeviceStatus(statusDeviceId.value), isDeviceRunning(statusDeviceId.value)]);
  const detail = statusResult.status === "fulfilled"
    ? normalizeDeviceStatusDetail(statusResult.value, statusDeviceId.value)
    : normalizeDeviceStatusDetail({ message: "单设备状态查询失败" }, statusDeviceId.value);
  if (runningResult.status === "fulfilled") {
    detail.running = normalizeDeviceRunningFlag(runningResult.value);
    detail.isRunning = detail.running;
  }
  statusDetail.value = detail;
}

async function checkRunningFlag() {
  if (!statusDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const running = normalizeDeviceRunningFlag(await isDeviceRunning(statusDeviceId.value));
  statusDetail.value = { deviceId: statusDeviceId.value, running, message: running ? "设备正在运行" : "设备未运行" };
}

function selectRuntimeDevice(deviceId: string) {
  statusDeviceId.value = deviceId;
  emit("select-device", deviceId);
  void loadDeviceStatus();
}

function deviceIdOf(device: DeviceInfo): string {
  return String(device.deviceId || device.id || "");
}

function deviceNameOf(deviceId: string): string {
  return props.devices.find((device) => deviceIdOf(device) === deviceId)?.deviceName || deviceId;
}

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = new Date(typeof value === "number" ? value : String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

watch(() => props.selectedDeviceId, (deviceId) => {
  if (deviceId) {
    statusDeviceId.value = deviceId;
    void loadDeviceStatus();
  }
});

onMounted(() => {
  void loadRuntimeOverview();
  if (statusDeviceId.value) {
    void loadDeviceStatus();
  }
});
</script>

<style scoped>
.device-runtime-panel {
  margin-top: 16px;
}

.runtime-device-toolbar {
  align-items: center;
}

.runtime-summary-cards {
  margin-top: 14px;
}

.runtime-device-table {
  margin-top: 14px;
}
</style>
