<template>
  <div class="legacy-page-host">
      <section v-show="activeModule === 'workbench'" id="deviceOperationPanel" class="local-editor local-device-panel local-device-web-dialog device-operation-panel">
        <div class="local-editor-title">
          <div>
            <span class="label-chip">设备配置</span>
            <h3>{{ selectedDevice?.deviceName || '请选择设备' }}</h3>
            <p>{{ selectedDeviceId || '从设备管理列表选择设备' }} · {{ selectedDevice?.protocolType || selectedDevice?.connectionType || '-' }} · {{ deviceAddress(selectedDevice || {}) }}</p>
          </div>
          <div class="local-editor-title-actions">
            <div class="local-editor-stats">
              <div class="local-editor-stat"><strong>{{ selectedOperationStatus }}</strong><span>运行状态</span></div>
              <div class="local-editor-stat"><strong>{{ selectedDevice?.collectionInterval || '-' }}</strong><span>采集周期 ms</span></div>
              <div class="local-editor-stat"><strong>{{ selectedRealtimeRows.length }}</strong><span>实时点位</span></div>
            </div>
            <button type="button" @click="backToDeviceList">返回列表</button>
          </div>
        </div>

        <div class="local-editor-tabs" role="tablist" aria-label="设备操作工作台分区">
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'config' }" @click="workbenchTab = 'config'">
            <span>01</span><strong>工作台</strong><small>点位、实时和日志</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'control' }" @click="workbenchTab = 'control'">
            <span>02</span><strong>批量和协议命令</strong><small>单点、批量和协议命令</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'shadow' }" @click="workbenchTab = 'shadow'">
            <span>03</span><strong>desired、desired_delta</strong><small>reported、desired、delta</small>
          </button>
        </div>

        <div class="local-editor-layout">
          <aside class="local-editor-rail device-operation-rail">
            <div>
              <span class="label-chip">当前设备</span>
              <strong>{{ selectedDevice?.deviceName || selectedDeviceId || '未选择设备' }}</strong>
              <p>配置、控制和影子共用同一个设备上下文；切换分区不会丢失当前选择。</p>
            </div>
            <ol class="local-checklist device-info-list">
              <li :class="selectedDeviceId ? 'is-ok' : 'is-error'"><span>设备已选择</span><strong>{{ selectedDeviceId || '请先选择设备' }}</strong></li>
              <li :class="selectedOperationStatus === 'ONLINE' ? 'is-ok' : 'is-warn'"><span>运行状态</span><strong>{{ selectedOperationStatus }}</strong></li>
              <li :class="selectedRealtimeRows.length > 0 ? 'is-ok' : 'is-warn'"><span>实时点位</span><strong>{{ selectedRealtimeRows.length }} 个</strong></li>
              <li :class="selectedConnectionOk ? 'is-ok' : 'is-warn'"><span>连接状态</span><strong>{{ selectedConnectionText }}</strong></li>
            </ol>
            <div class="device-operation-rail-actions">
              <button type="button" :disabled="!selectedDeviceId || deviceConfigOperatingId === `refresh:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'refresh')">刷新配置</button>
              <button type="button" class="danger" :disabled="!selectedDeviceId || deviceConfigOperatingId === `clear:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'clear')">清理缓存</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceRuntimeStatus">运行状态</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceAlarmHistory">告警历史</button>
            </div>
          </aside>

          <div class="local-editor-body device-operation-body">
            <DeviceConfigPanel v-if="workbenchTab === 'config'" :device="selectedDeviceView" @start="startSelectedDevice" @stop="stopSelectedDevice" @open-history="openWorkbenchHistory" @open-realtime="openWorkbenchRealtime" />
            <ManualShadowPanels v-else :tab="workbenchTab" :device-id="selectedDeviceId" />
          </div>
        </div>
      </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import ManualShadowPanels from "./LegacyManualShadowPanels.vue";
import { clearDeviceConfig, getConfigDevices as getConfigDeviceList, refreshDeviceConfig } from "@/api/config.api";
import { getDeviceRuntime, startDevice, startLocalDevice, stopDevice } from "@/api/device.api";
import { getDeviceRealtimeData, resetAdaptiveConfig } from "@/api/data.api";
import { resolveLegacyModuleByRoutePath, type LegacyModuleKey } from "@/router/route-names";
import { useAppStore } from "@/stores/app.store";
import { normalizeDeviceViewModelWithRuntimeStatus, resolveDeviceStartMode } from "@/stores/device.store";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";

type ModuleKey = LegacyModuleKey;

const appStore = useAppStore();
const route = useRoute();
const router = useRouter();
const activeModule = computed<ModuleKey>(() => {
  const module = resolveLegacyModuleByRoutePath(route.path);
  return module === "control" || module === "shadow" ? "workbench" : module;
});
const devices = ref<DeviceViewModel[]>([]);
const deviceRuntimeMap = ref<Record<string, DeviceRuntimeSnapshot>>({});
const selectedRealtimeRows = ref<RealtimePointRow[]>([]);
const selectedDeviceId = ref("");
const deviceConfigOperatingId = ref("");
const workbenchTab = ref<"config" | "control" | "shadow">("config");

const selectedDevice = computed(() => devices.value.find((device) => deviceIdOf(device) === selectedDeviceId.value));
const selectedDeviceView = computed(() => selectedDevice.value ? normalizeDeviceViewModelWithRuntimeStatus(selectedDevice.value, deviceRuntimeMap.value) : null);
const selectedRuntimeSnapshot = computed(() => selectedDeviceId.value ? (selectedDeviceView.value?.runtime || deviceRuntimeMap.value[selectedDeviceId.value]) : undefined);
const selectedConnectionOk = computed(() => Boolean(selectedRuntimeSnapshot.value?.connected || selectedRuntimeSnapshot.value?.running || selectedRealtimeRows.value.length > 0));
const selectedConnectionText = computed(() => selectedConnectionOk.value ? "正常" : "未知");
const selectedOperationStatus = computed(() => {
  const runtime = selectedRuntimeSnapshot.value;
  if (runtime?.running || runtime?.connected || selectedRealtimeRows.value.length > 0) {
    return "ONLINE";
  }
  return String(selectedDeviceView.value?.status || selectedDevice.value?.status || "未知");
});
onMounted(async () => {
  await appStore.initialize();
  syncWorkbenchTabFromRoute(route.path);
  applyRouteDeviceQuery();
  await loadDevices();
  applyRouteDeviceQuery();
  await loadActiveLegacyModule(activeModule.value);
});

watch(() => route.path, (path) => {
  syncWorkbenchTabFromRoute(path);
  applyRouteDeviceQuery();
  void loadActiveLegacyModule(activeModule.value);
});

watch(() => route.query.deviceId, () => {
  applyRouteDeviceQuery();
  void loadActiveLegacyModule(activeModule.value);
});

async function loadActiveLegacyModule(module: ModuleKey) {
  if (module === "workbench") await loadSelectedRealtime();
}

function syncWorkbenchTabFromRoute(path: string) {
  const module = resolveLegacyModuleByRoutePath(path);
  if (module === "control" || module === "shadow") {
    workbenchTab.value = module;
    return;
  }
  if (module === "workbench") {
    workbenchTab.value = "config";
  }
}

async function loadDevices() {
  try {
    const [deviceResponse, runtimeResponse] = await Promise.allSettled([getConfigDeviceList(), getDeviceRuntime()]);
    if (runtimeResponse.status === "fulfilled") {
      const snapshots = extractArray<DeviceRuntimeSnapshot>(runtimeResponse.value, ["data", "items", "records"]);
      deviceRuntimeMap.value = Object.fromEntries(snapshots.map((item) => [item.deviceId, item]).filter(([deviceId]) => Boolean(deviceId)));
    }
    if (deviceResponse.status !== "fulfilled") {
      throw deviceResponse.reason;
    }
    devices.value = extractArray<DeviceInfo>(deviceResponse.value, ["devices", "data", "items", "records"])
      .map((device) => normalizeDeviceViewModelWithRuntimeStatus(device, deviceRuntimeMap.value));
    if (!selectedDeviceId.value && devices.value.length) selectedDeviceId.value = deviceIdOf(devices.value[0]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置加载失败");
  }
}

async function loadSelectedRealtime() {
  if (!selectedDeviceId.value) return;
  try {
    const response = await getDeviceRealtimeData(selectedDeviceId.value);
    selectedRealtimeRows.value = normalizeRealtimeRows(response, selectedDeviceId.value);
  } catch {
    selectedRealtimeRows.value = [];
  }
}

async function resetSelectedAdaptive() {
  if (!selectedDeviceId.value) return;
  await resetAdaptiveConfig(selectedDeviceId.value);
  ElMessage.success("已重置自适应采集参数");
  await loadSelectedRealtime();
}

async function startSelectedDevice(deviceId: string) {
  const device = devices.value.find((item) => deviceIdOf(item) === deviceId);
  const startAction = resolveDeviceStartMode(device) === "local" ? startLocalDevice : startDevice;
  await startAction(deviceId);
  await loadDevices();
  await loadSelectedRealtime();
}
async function stopSelectedDevice(deviceId: string) { await stopDevice(deviceId); await loadDevices(); await loadSelectedRealtime(); }

async function operateDeviceConfig(deviceId: string, type: DeviceConfigActionType) {
  if (!deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const option = DEVICE_CONFIG_ACTIONS.find((item) => item.type === type);
  const label = option?.label || "配置操作";
  const confirmText = option?.confirmText || "该操作只影响本地配置缓存。";
  try {
    await ElMessageBox.confirm(`确认对设备 ${deviceId} 执行${label}？${confirmText}`, "确认配置操作", {
      confirmButtonText: "确认执行",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  deviceConfigOperatingId.value = `${type}:${deviceId}`;
  try {
    const response = type === "clear" ? await clearDeviceConfig(deviceId) : await refreshDeviceConfig(deviceId);
    const result = normalizeDeviceConfigActionResult(response, deviceId);
    ElMessage.success(buildDeviceConfigActionMessage(type, result));
    await loadDevices();
    if (selectedDeviceId.value === deviceId) {
      await loadSelectedRealtime();
    }
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

function selectDevice(deviceId: string) { selectedDeviceId.value = deviceId; void loadSelectedRealtime(); }

function applyRouteDeviceQuery() {
  const deviceId = routeDeviceId();
  if (deviceId) {
    selectedDeviceId.value = deviceId;
    return;
  }
  if (!selectedDeviceId.value && devices.value.length) {
    selectedDeviceId.value = deviceIdOf(devices.value[0]);
    return;
  }
  if (selectedDeviceId.value && devices.value.length && !devices.value.some((device) => deviceIdOf(device) === selectedDeviceId.value)) {
    selectedDeviceId.value = deviceIdOf(devices.value[0]);
  }
}

function routeDeviceId(): string {
  const value = route.query.deviceId;
  if (Array.isArray(value)) {
    return String(value[0] || "");
  }
  return String(value || "");
}

function backToDeviceList() {
  router.push({
    path: "/device",
    query: selectedDeviceId.value ? { deviceId: selectedDeviceId.value } : {}
  }).catch(() => undefined);
}

function deviceAddress(device: DeviceInfo): string {
  return [device.ipAddress, device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":") || "-";
}

function openDeviceAlarmHistory(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  router.push({ path: "/alarm", query: { deviceId } }).catch(() => undefined);
}

function openDeviceRuntimeStatus(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  router.push({ path: "/diagnostic", query: { deviceId } }).catch(() => undefined);
  ElMessage.info("已切换到运行设备状态面板");
}

function openSelectedDeviceRuntimeStatus() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceRuntimeStatus(selectedDevice.value);
}

function openSelectedDeviceAlarmHistory() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceAlarmHistory(selectedDevice.value);
}

function openWorkbenchHistory(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  selectDevice(target.deviceId);
  router.push({ path: "/history", query: { deviceId: target.deviceId, pointId: target.pointRef } }).catch(() => undefined);
  ElMessage.info(`已切换到历史趋势：${target.pointLabel || target.pointName || target.pointRef}`);
}

function openWorkbenchRealtime(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  router.push({ path: "/realtime", query: { deviceId: target.deviceId, pointId: target.pointRef } }).catch(() => undefined);
  ElMessage.info(`已切换到实时数据：${target.pointLabel || target.pointName || target.pointRef}`);
}

function deviceIdOf(device: DeviceInfo): string { return String(device.deviceId || device.id || device["normalizedId"] || ""); }
function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function extractArray<T>(value: unknown, keys: string[]): T[] { if (Array.isArray(value)) return value as T[]; const record = asRecord(value); for (const key of keys) if (Array.isArray(record[key])) return record[key] as T[]; return []; }
function prettyJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
</script>

<style scoped>
.legacy-page-host {
  display: contents;
}
</style>
