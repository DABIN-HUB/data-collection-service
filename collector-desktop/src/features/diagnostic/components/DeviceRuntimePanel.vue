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
        <button type="button" class="primary" :disabled="!statusDeviceId || statusLoading" @click="loadDeviceStatus">{{ statusLoading ? `正在查询设备 ${statusCheckTargetId}` : '查询单设备状态' }}</button>
        <button type="button" :disabled="!statusDeviceId || runningCheckLoading" @click="checkRunningFlag">{{ runningCheckLoading ? `正在检查设备 ${statusCheckTargetId}` : '检查是否运行' }}</button>
      </div>
      <div class="exact-toolbar-group">
        <button type="button" :disabled="loading" @click="loadRuntimeOverview">{{ loading ? '刷新中' : '刷新运行列表' }}</button>
      </div>
    </div>
    <p v-if="overviewError" class="runtime-status-error" role="alert">运行快照不可用/已过期：{{ overviewError }}</p>
    <div class="exact-diagnostic-cards runtime-summary-cards">
      <div class="exact-diagnostic-card"><span>配置设备</span><strong>{{ runtimeSummary.total }}</strong></div>
      <div class="exact-diagnostic-card"><span>正在运行</span><strong>{{ overviewError ? '未知/过期' : runtimeSummary.running }}</strong></div>
      <div class="exact-diagnostic-card"><span>连接正常</span><strong>{{ overviewError ? '未知/过期' : runtimeSummary.connected }}</strong></div>
      <div class="exact-diagnostic-card"><span>异常/退化</span><strong>{{ overviewError ? '未知/过期' : runtimeSummary.abnormal }}</strong></div>
    </div>
    <section class="exact-table-card runtime-device-table">
      <table>
        <thead><tr><th>设备</th><th>阶段</th><th>期望状态</th><th>传输 / 协议</th><th>采集健康</th><th>点位质量</th><th>代次</th><th>最近有效样本</th><th>退化原因</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="runtimeRows.length === 0"><td colspan="10" class="exact-empty">暂无运行态快照，可点击刷新运行列表</td></tr>
          <tr v-for="row in runtimeRows" :key="row.deviceId">
            <td><strong>{{ deviceNameOf(row.deviceId) }}</strong><br><code>{{ row.deviceId }}</code></td>
            <td>{{ runtimePresentation(row, Boolean(deviceStore.runtimeErrors[row.deviceId])).lifecycle }}</td>
            <td>{{ runtimePresentation(row).desired }}</td>
            <td>{{ runtimePresentation(row, Boolean(deviceStore.runtimeErrors[row.deviceId])).transportProtocol }}</td>
            <td>{{ runtimePresentation(row, Boolean(deviceStore.runtimeErrors[row.deviceId])).health }}</td>
            <td>{{ runtimePresentation(row).points }}</td>
            <td>{{ row.generation ?? '-' }}</td>
            <td>{{ runtimePresentation(row).lastValid }}</td>
            <td>{{ deviceStore.runtimeErrors[row.deviceId] || runtimePresentation(row).reason || '-' }}</td>
            <td><button type="button" @click="selectRuntimeDevice(row.deviceId)">查状态</button></td>
          </tr>
        </tbody>
      </table>
    </section>
    <details class="exact-json-panel" open>
      <summary>单设备状态 JSON <span v-if="statusError" class="runtime-status-error">{{ statusError }}</span></summary>
      <pre class="json-view compact-result-view">{{ prettyJson(statusDetail) }}</pre>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { isDeviceRunning } from "@/api/device.api";
import { buildDeviceRequestContext, isSameDeviceRequestContext } from "@/features/device/utils/device-request-lifecycle";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
import { canonicalDeviceId, useDeviceStore } from "@/stores/device.store";
import type { DeviceInfo } from "@/types/device";
import { buildDeviceRuntimeSummary, buildUnavailableRunningFlagDetail, normalizeDeviceRunningFlag, normalizeDeviceStatusDetail, runtimePresentation } from "../utils/device-runtime-utils";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId?: string;
}>();
const emit = defineEmits<{
  (event: "select-device", deviceId: string): void;
}>();

const deviceStore = useDeviceStore();
const loading = computed(() => deviceStore.loading);
const runtimeRows = computed(() => Object.values(deviceStore.runtimeMap));
const overviewError = computed(() => deviceStore.error || (Object.values(deviceStore.runtimeErrors).filter(Boolean).join("；")));
const statusDeviceId = ref(props.selectedDeviceId || "");
const statusDetail = ref<unknown>({ message: "请选择设备后查询单设备运行状态" });
const statusLoading = ref(false);
const runningCheckLoading = ref(false);
const statusCheckTargetId = ref("");
const statusError = ref("");

const statusRequestOwner = createLatestRequestOwner(isSameDeviceRequestContext);
const runningFlagOwner = statusRequestOwner;

const runtimeSummary = computed(() => buildDeviceRuntimeSummary(runtimeRows.value, props.devices.length || runtimeRows.value.length));

async function loadRuntimeOverview() {
  await deviceStore.refresh();
}

async function loadDeviceStatus() {
  if (!statusDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const targetDeviceId = statusDeviceId.value;
  const requestContext = buildDeviceRequestContext(targetDeviceId);
  const ticket = statusRequestOwner.begin(requestContext);
  statusLoading.value = true;
  statusCheckTargetId.value = targetDeviceId;
  statusError.value = "";
  try {
    const [statusResult, runningResult] = await Promise.allSettled([deviceStore.refreshRuntime(targetDeviceId), isDeviceRunning(targetDeviceId)]);
    if (!statusRequestOwner.canCommit(ticket, buildDeviceRequestContext(statusDeviceId.value))) {
      return;
    }
    const runtimeError = statusResult.status === "rejected" ? statusResult.reason : statusResult.value;
    const detail = normalizeDeviceStatusDetail(deviceStore.runtimeMap[targetDeviceId] || { deviceId: targetDeviceId }, targetDeviceId);
    if (runtimeError) statusError.value = runtimeError instanceof Error ? runtimeError.message : String(runtimeError);
    if (runningResult.status === "fulfilled") {
      detail.running = normalizeDeviceRunningFlag(runningResult.value);
      detail.isRunning = detail.running;
    } else {
      detail.running = undefined;
      detail.isRunning = undefined;
      detail.message = "运行状态：暂不可用";
      statusError.value = runningResult.reason instanceof Error ? runningResult.reason.message : "运行状态检查失败";
    }
    statusDetail.value = detail;
    if (statusResult.status === "rejected") {
      statusError.value = statusResult.reason instanceof Error ? statusResult.reason.message : "单设备状态查询失败";
    }
  } finally {
    if (statusRequestOwner.isLatest(ticket)) {
      statusLoading.value = false;
      statusCheckTargetId.value = "";
    }
  }
}

async function checkRunningFlag() {
  if (!statusDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const targetDeviceId = statusDeviceId.value;
  const requestContext = buildDeviceRequestContext(targetDeviceId);
  const ticket = runningFlagOwner.begin(requestContext);
  runningCheckLoading.value = true;
  statusCheckTargetId.value = targetDeviceId;
  statusError.value = "";
  try {
    const running = normalizeDeviceRunningFlag(await isDeviceRunning(targetDeviceId));
    if (!runningFlagOwner.canCommit(ticket, buildDeviceRequestContext(statusDeviceId.value))) {
      return;
    }
    statusDetail.value = { deviceId: targetDeviceId, running, isRunning: running, message: running ? "设备正在运行" : "设备未运行" };
  } catch (caught) {
    if (!runningFlagOwner.canCommit(ticket, buildDeviceRequestContext(statusDeviceId.value))) {
      return;
    }
    const message = caught instanceof Error ? caught.message : "运行状态检查失败";
    statusError.value = message;
    statusDetail.value = buildUnavailableRunningFlagDetail(targetDeviceId, message);
    ElMessage.error(`设备 ${targetDeviceId} 运行状态检查失败`);
  } finally {
    if (runningFlagOwner.isLatest(ticket)) {
      runningCheckLoading.value = false;
      statusCheckTargetId.value = "";
    }
  }
}

function selectRuntimeDevice(deviceId: string) {
  statusDeviceId.value = deviceId;
  emit("select-device", deviceId);
  void loadDeviceStatus();
}

function deviceIdOf(device: DeviceInfo): string {
  return canonicalDeviceId(device);
}

function deviceNameOf(deviceId: string): string {
  return props.devices.find((device) => deviceIdOf(device) === deviceId)?.deviceName || deviceId;
}



function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

watch(() => props.selectedDeviceId, (deviceId) => {
  if (deviceId) {
    statusRequestOwner.invalidate();
    runningFlagOwner.invalidate();
    statusLoading.value = false;
    runningCheckLoading.value = false;
    statusCheckTargetId.value = "";
    statusError.value = "";
    statusDeviceId.value = deviceId;
    void loadDeviceStatus();
  }
});

watch(statusDeviceId, () => {
  statusRequestOwner.invalidate();
  statusLoading.value = false;
  runningCheckLoading.value = false;
  statusError.value = "";
  statusDetail.value = { message: "请选择设备后查询单设备运行状态" };
}, { flush: "sync" });
onBeforeUnmount(() => { statusRequestOwner.invalidate(); });

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

.runtime-status-error {
  margin-left: 8px;
  color: #fecaca;
  font-size: 12px;
}
</style>
