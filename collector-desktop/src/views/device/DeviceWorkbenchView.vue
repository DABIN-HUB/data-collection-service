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
      <button type="button" class="local-editor-tab is-active" @click="openWorkbenchTab('config')">
        <span>01</span><strong>工作台</strong><small>点位、实时和日志</small>
      </button>
      <button type="button" class="local-editor-tab" @click="openWorkbenchTab('control')">
        <span>02</span><strong>批量和协议命令</strong><small>单点、批量和协议命令</small>
      </button>
      <button type="button" class="local-editor-tab" @click="openWorkbenchTab('shadow')">
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
        <DeviceConfigPanel :device="deviceStore.selectedDevice" @start="startSelectedDevice" @stop="stopSelectedDevice" @open-history="openWorkbenchHistory" @open-realtime="openWorkbenchRealtime" />
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
import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import { routePathForWorkbenchTab, type WorkbenchNavigationTab } from "@/router/route-names";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";

interface WorkbenchPointTarget {
  deviceId: string;
  pointRef: string;
  pointName?: string;
  pointLabel?: string;
}

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();
const router = useRouter();

const realtimePreviewRows = ref<RealtimePointRow[]>([]);
const deviceConfigOperatingId = ref("");

const selectedDeviceId = computed(() => deviceStore.selectedDevice?.normalizedId || deviceStore.selectedDeviceId || routeDeviceId());
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
  await deviceStore.refresh();
  applyRouteDevice();
  await loadRealtimePreview();
});

watch(() => route.query.deviceId, () => {
  applyRouteDevice();
  void loadRealtimePreview();
});

async function startSelectedDevice(deviceId: string) {
  await deviceStore.startSmart(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  applyRouteDevice();
  await loadRealtimePreview();
  ElMessage.success("已请求启动设备");
}

async function stopSelectedDevice(deviceId: string) {
  await deviceStore.stop(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  applyRouteDevice();
  await loadRealtimePreview();
  ElMessage.success("已请求停止设备");
}

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
  const queryDeviceId = routeDeviceId();
  if (queryDeviceId) {
    const matchedDeviceId = deviceStore.devices.find((device) => device.normalizedId === queryDeviceId)?.normalizedId;
    if (matchedDeviceId) {
      deviceStore.selectDevice(matchedDeviceId);
      return;
    }
    deviceStore.selectDevice(deviceStore.devices[0]?.normalizedId || queryDeviceId);
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

function routeDeviceId(): string {
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

function openWorkbenchHistory(target: WorkbenchPointTarget) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  deviceStore.selectDevice(target.deviceId);
  router.push({ path: "/history", query: { deviceId: target.deviceId, pointId: target.pointRef } }).catch(() => undefined);
  ElMessage.info(`已切换到历史趋势：${target.pointLabel || target.pointName || target.pointRef}`);
}

function openWorkbenchRealtime(target: WorkbenchPointTarget) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  deviceStore.selectDevice(target.deviceId);
  router.push({ path: "/realtime", query: { deviceId: target.deviceId, pointId: target.pointRef } }).catch(() => undefined);
  ElMessage.info(`已切换到实时数据：${target.pointLabel || target.pointName || target.pointRef}`);
}

function deviceAddress(device: DeviceInfo | DeviceViewModel | null): string {
  if (!device) {
    return "-";
  }
  const address = [device.ipAddress || device["host"], device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":");
  return address || String(device["url"] || "-");
}
</script>
