<template>
  <section id="deviceOperationPanel" class="local-editor local-device-panel local-device-web-dialog device-operation-panel">
    <div class="local-editor-title">
      <div>
        <span class="label-chip">设备配置</span>
        <h3>{{ selectedDeviceName }}</h3>
        <p>{{ selectedDeviceId || "从设备管理列表选择设备" }} · {{ selectedDeviceProtocol }} · {{ deviceAddress(deviceStore.selectedDevice) }}</p>
      </div>
      <div class="local-editor-title-actions">
        <div class="local-editor-stats">
          <div class="local-editor-stat"><strong>{{ selectedOperationStatus }}</strong><span>运行状态</span></div>
          <div class="local-editor-stat"><strong>{{ deviceStore.selectedDevice?.collectionInterval || "-" }}</strong><span>采集周期 ms</span></div>
          <div class="local-editor-stat"><strong>{{ realtimePreviewRows.length }}</strong><span>实时点位</span></div>
        </div>
        <button type="button" @click="backToDeviceList">返回列表</button>
      </div>
    </div>

    <div class="local-editor-tabs" role="tablist" aria-label="设备操作工作台分区">
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'config' }" @click="openWorkbenchTab('config')">
        <span>01</span><strong>工作台</strong><small>点位、实时和日志</small>
      </button>
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'control' }" @click="openWorkbenchTab('control')">
        <span>02</span><strong>批量和协议命令</strong><small>单点、批量和协议命令</small>
      </button>
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'shadow' }" @click="openWorkbenchTab('shadow')">
        <span>03</span><strong>设备影子</strong><small>reported、desired、delta</small>
      </button>
    </div>

    <div class="local-editor-layout">
      <aside class="local-editor-rail device-operation-rail">
        <div>
          <span class="label-chip">当前设备</span>
          <strong>{{ selectedDeviceName }}</strong>
          <p>配置、控制和影子通过路由共享同一个设备上下文；切换分区不会丢失当前选择。</p>
        </div>
        <ol class="local-checklist device-info-list">
          <li :class="selectedDeviceId ? 'is-ok' : 'is-error'"><span>设备已选择</span><strong>{{ selectedDeviceId || "请先选择设备" }}</strong></li>
          <li :class="selectedOperationStatus === 'ONLINE' ? 'is-ok' : 'is-warn'"><span>运行状态</span><strong>{{ selectedOperationStatus }}</strong></li>
          <li :class="realtimePreviewRows.length > 0 ? 'is-ok' : 'is-warn'"><span>实时点位</span><strong>{{ realtimePreviewRows.length }} 个</strong></li>
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
        <slot :device-id="selectedDeviceId" :device="deviceStore.selectedDevice" :realtime-preview-rows="realtimePreviewRows" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { clearDeviceConfig, refreshDeviceConfig } from "@/api/config.api";
import { getDeviceRealtimeData } from "@/api/data.api";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import { routePathForWorkbenchTab, type WorkbenchNavigationTab } from "@/router/route-names";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";

const props = defineProps<{ activeTab: WorkbenchNavigationTab }>();

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();
const router = useRouter();

const realtimePreviewRows = ref<RealtimePointRow[]>([]);
const deviceConfigOperatingId = ref("");

const activeTab = computed(() => props.activeTab);
const selectedDeviceId = computed(() => deviceStore.selectedDevice?.normalizedId || deviceStore.selectedDeviceId || queryDeviceId());
const selectedDeviceName = computed(() => deviceStore.selectedDevice?.displayName || selectedDeviceId.value || "请选择设备");
const selectedDeviceProtocol = computed(() => deviceStore.selectedDevice?.displayProtocol || String(deviceStore.selectedDevice?.protocolType || deviceStore.selectedDevice?.connectionType || "-"));
const selectedRuntimeSnapshot = computed<DeviceRuntimeSnapshot | undefined>(() => {
  const deviceId = selectedDeviceId.value;
  return deviceStore.selectedDevice?.runtime || (deviceId ? deviceStore.runtimeMap[deviceId] : undefined);
});
const selectedConnectionOk = computed(() => Boolean(selectedRuntimeSnapshot.value?.connected || selectedRuntimeSnapshot.value?.running || realtimePreviewRows.value.length > 0));
const selectedConnectionText = computed(() => selectedConnectionOk.value ? "正常" : "未知");
const selectedOperationStatus = computed(() => {
  const runtime = selectedRuntimeSnapshot.value;
  if (runtime?.running || runtime?.connected || realtimePreviewRows.value.length > 0) {
    return "ONLINE";
  }
  return String(deviceStore.selectedDevice?.status || deviceStore.selectedDevice?.["runtimeStatus"] || "未知");
});

onMounted(async () => {
  await appStore.initialize();
  applyRouteDevice();
  await deviceStore.refresh();
  applyRouteDevice();
  await loadRealtimePreview();
});

watch(() => route.query.deviceId, () => {
  applyRouteDevice();
  void loadRealtimePreview();
});

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
    await deviceStore.refresh();
    applyRouteDevice();
    await loadRealtimePreview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置操作失败");
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

async function loadRealtimePreview() {
  const deviceId = selectedDeviceId.value;
  if (!deviceId) {
    realtimePreviewRows.value = [];
    return;
  }
  try {
    realtimePreviewRows.value = normalizeRealtimeRows(await getDeviceRealtimeData(deviceId), deviceId);
  } catch {
    realtimePreviewRows.value = [];
  }
}

function applyRouteDevice() {
  const routeQueryDeviceId = queryDeviceId();
  if (routeQueryDeviceId) {
    const matchedDeviceId = deviceStore.devices.find((device) => device.normalizedId === routeQueryDeviceId)?.normalizedId;
    if (matchedDeviceId) {
      deviceStore.selectDevice(matchedDeviceId);
      return;
    }
    deviceStore.selectDevice(deviceStore.devices[0]?.normalizedId || routeQueryDeviceId);
    return;
  }
  if (deviceStore.selectedDevice) {
    return;
  }
  const firstDeviceId = deviceStore.devices[0]?.normalizedId || "";
  if (firstDeviceId) {
    deviceStore.selectDevice(firstDeviceId);
  }
}

function queryDeviceId(): string {
  const value = route.query.deviceId;
  if (Array.isArray(value)) {
    return String(value[0] || "");
  }
  return String(value || "");
}

function openWorkbenchTab(tab: WorkbenchNavigationTab) {
  router.push({
    path: routePathForWorkbenchTab(tab),
    query: selectedDeviceId.value ? { deviceId: selectedDeviceId.value } : {}
  }).catch(() => undefined);
}

function backToDeviceList() {
  router.push({
    path: "/device",
    query: selectedDeviceId.value ? { deviceId: selectedDeviceId.value } : {}
  }).catch(() => undefined);
}

function openSelectedDeviceRuntimeStatus() {
  if (!selectedDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  router.push({ path: "/diagnostic", query: { deviceId: selectedDeviceId.value } }).catch(() => undefined);
}

function openSelectedDeviceAlarmHistory() {
  if (!selectedDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  router.push({ path: "/alarm", query: { deviceId: selectedDeviceId.value } }).catch(() => undefined);
}

function deviceAddress(device: DeviceInfo | DeviceViewModel | null): string {
  if (!device) {
    return "-";
  }
  const address = [device.ipAddress || device["host"], device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":");
  return address || String(device["url"] || "-");
}
</script>

<style scoped>
.device-operation-panel {
  --panel-line: var(--console-border-soft, #1e3a5f);
  --panel-muted: var(--console-text-muted, #8aa0b8);
  --panel-text: var(--console-text-primary, #e5edf8);
  --console-radius-card: 6px;
  --console-radius-control: 4px;
  display: grid;
  min-height: calc(100vh - 96px);
  max-height: calc(100vh - 96px);
  grid-template-rows: auto auto minmax(0, 1fr);
  overflow: hidden;
  color: var(--console-text-secondary);
  border: 1px solid var(--panel-line);
  border-radius: 18px;
  background: var(--console-bg, #08131f);
  box-shadow: none;
}

.device-operation-panel .local-editor-title {
  grid-row: 1;
  display: flex;
  min-height: 76px;
  padding: 0 16px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  color: var(--panel-text);
  border-bottom: 1px solid var(--panel-line);
  background: linear-gradient(180deg, var(--console-panel) 0%, var(--console-bg-soft) 100%);
}

.local-editor-title h3,
.local-editor-title p {
  margin: 0;
}

.local-editor-title h3 {
  margin-top: 3px;
  color: var(--console-text-primary);
  font-size: 18px;
  font-weight: 800;
  line-height: 1.18;
}

.local-editor-title p {
  margin-top: 3px;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.2;
}

.label-chip {
  display: inline-flex;
  width: fit-content;
  min-height: 22px;
  padding: 2px 7px;
  align-items: center;
  border: 1px solid rgba(59, 130, 246, 0.34);
  border-radius: 999px;
  color: #bfdbfe;
  background: rgba(37, 99, 235, 0.18);
  font-size: 11px;
  font-weight: 800;
  line-height: 1.2;
}

.local-editor-title-actions,
.local-editor-stats {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: nowrap;
}

.local-editor-stat {
  width: 96px;
  min-width: 96px;
  min-height: 46px;
  padding: 6px 9px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-card);
  background: var(--console-panel-soft);
  text-align: left;
}

.local-editor-stat strong,
.local-editor-stat span {
  display: block;
}

.local-editor-stat strong {
  max-width: 100%;
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: 15px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-stat span {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: 11px;
}

.device-operation-panel .local-editor-tabs {
  grid-row: 2;
  display: flex;
  min-width: 0;
  min-height: 58px;
  padding: 7px 16px;
  gap: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 1px solid var(--panel-line);
  background: var(--console-bg-soft, #0d1a2a);
}

.local-editor-tab {
  position: relative;
  flex: 1 0 188px;
  min-height: 44px;
  padding: 7px 10px 7px 42px;
  color: var(--console-text-dim);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-card);
  background: var(--console-panel);
  text-align: left;
}

.local-editor-tab:hover {
  color: var(--console-text-secondary);
  border-color: var(--console-border-active);
  background: var(--console-panel-soft);
}

.local-editor-tab::after {
  display: none;
}

.local-editor-tab > span {
  position: absolute;
  top: 9px;
  left: 10px;
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border-soft);
  border-radius: 50%;
  background: var(--console-bg);
  font-size: 11px;
  font-weight: 800;
}

.local-editor-tab strong,
.local-editor-tab small {
  display: block;
}

.local-editor-tab strong {
  color: var(--console-text-secondary);
  font-size: 13px;
  line-height: 1.15;
}

.local-editor-tab small {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: 11px;
  line-height: 1.1;
}

.local-editor-tab.is-active {
  color: var(--console-text-primary);
  border-color: var(--console-primary-hover);
  background: rgba(59, 130, 246, 0.16);
}

.local-editor-tab.is-active > span {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.device-operation-panel .local-editor-layout {
  grid-row: 3;
  display: grid;
  min-height: 0;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 0;
  align-items: stretch;
  overflow: hidden;
  background: var(--console-bg);
}

.local-editor-rail {
  display: flex;
  min-width: 0;
  padding: 12px;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
  border-right: 1px solid var(--panel-line);
  background: var(--console-panel);
}

.local-editor-rail strong,
.local-editor-rail p {
  display: block;
}

.local-editor-rail strong {
  margin-top: 7px;
  color: var(--console-text-primary);
  font-size: 14px;
}

.local-editor-rail p {
  margin: 5px 0 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.35;
}

.local-checklist {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 6px;
  list-style: none;
}

.device-info-list li {
  display: grid;
  min-height: 44px;
  padding: 7px 9px;
  gap: 3px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.device-info-list li span {
  color: var(--console-text-dim);
  font-size: 11px;
  line-height: 1.15;
}

.device-info-list li strong {
  margin: 0;
  color: var(--console-text-secondary);
  font-size: 12px;
  line-height: 1.2;
  word-break: break-all;
}

.device-info-list li.is-ok strong,
.is-success {
  color: #34d399;
}

.device-info-list li.is-warn strong,
.is-warning {
  color: #fb923c;
}

.device-info-list li.is-error strong,
.is-danger {
  color: #f87171;
}

.device-operation-rail-actions {
  display: grid;
  gap: 7px;
}

.device-operation-body {
  display: block;
  min-width: 0;
  min-height: 0;
  padding: 12px 16px;
  overflow: auto;
  background: var(--console-bg);
}

@media (max-width: 1240px) {
  .device-operation-panel .local-editor-layout {
    grid-template-columns: 1fr;
  }
}
</style>
