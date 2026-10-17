<template>
  <DeviceOperationShell class="precision-config-view" active-tab="config" v-slot="{ device }">
    <DeviceConfigPanel
      :device="device"
      @start="startSelectedDevice"
      @stop="stopSelectedDevice"
      @open-history="openWorkbenchHistory"
      @open-realtime="openWorkbenchRealtime"
    />
  </DeviceOperationShell>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";

import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import DeviceOperationShell from "@/features/device/components/DeviceOperationShell.vue";
import { runtimeOperationMessage } from "@/features/diagnostic/utils/device-runtime-utils";
import { useDeviceStore } from "@/stores/device.store";

interface WorkbenchPointTarget {
  deviceId: string;
  pointRef: string;
  pointName?: string;
  pointLabel?: string;
}

const deviceStore = useDeviceStore();
const router = useRouter();

async function startSelectedDevice(deviceId: string) {
  const result = await deviceStore.startSmart(deviceId);
  if (!result.ok) {
    ElMessage.error(`设备 ${deviceId}：${result.error}`);
    return;
  }
  if (result.refreshError) ElMessage.warning(`设备 ${deviceId} 操作已受理，运行状态暂不可用：${result.refreshError}`);
  else ElMessage.success(`设备 ${deviceId}：${runtimeOperationMessage(result.runtime, "START")}`);
}

async function stopSelectedDevice(deviceId: string) {
  const result = await deviceStore.stop(deviceId);
  if (!result.ok) {
    ElMessage.error(`设备 ${deviceId}：${result.error}`);
    return;
  }
  if (result.refreshError) ElMessage.warning(`设备 ${deviceId} 操作已受理，运行状态暂不可用：${result.refreshError}`);
  else ElMessage.success(`设备 ${deviceId}：${runtimeOperationMessage(result.runtime, "STOP")}`);
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
</script>

<style scoped>
.device-operation-panel.precision-config-view {
  padding: 24px 28px 34px;
}

.precision-config-view :deep(.config-overview) {
  margin: 14px 0 18px;
  padding: 12px 14px;
  border: 1px solid var(--console-border-soft);
  border-radius: 6px;
  background: var(--console-panel);
}

.precision-config-view :deep(.device-config-workbench-pane) {
  display: grid;
  grid-template-columns: minmax(248px, 300px) minmax(0, 1fr);
  align-items: start;
  gap: 14px 18px;
}

.precision-config-view :deep(.run-control-card) {
  grid-column: 1;
  grid-row: 1;
  display: grid;
  padding: 14px;
  gap: 12px;
  border-radius: 6px;
}

.precision-config-view :deep(.runtime-heading) {
  width: 100%;
  padding: 0 0 10px;
  border-right: 0;
  border-bottom: 1px solid var(--console-border-soft);
}

.precision-config-view :deep(.control-status-row) {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
}

.precision-config-view :deep(.run-control-actions) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-left: 0;
  gap: 7px;
}

.precision-config-view :deep(.run-control-actions .el-button) {
  width: 100%;
  margin-left: 0;
}

.precision-config-view :deep(.run-control-actions .el-button:last-child) {
  grid-column: 1 / -1;
}

.precision-config-view :deep(.runtime-note) {
  grid-column: 1;
  grid-row: 2;
  margin: 0;
}

.precision-config-view :deep(.device-config-workbench-pane > .el-alert) {
  grid-column: 1;
  grid-row: 3;
}

.precision-config-view :deep(.protocol-config-card) {
  grid-column: 1;
  grid-row: 4;
}

.precision-config-view :deep(.protocol-config-card > summary) {
  flex-wrap: wrap;
}

.precision-config-view :deep(.device-data-panel) {
  grid-column: 2;
  grid-row: 1 / span 4;
  border-radius: 6px;
}

.precision-config-view :deep(.device-data-panel:has(.embedded-point-editor)) {
  grid-column: 1 / -1;
  grid-row: 5;
}

.precision-config-view :deep(.device-data-topline) {
  padding: 0 14px 10px;
  gap: 0 10px;
}

.precision-config-view :deep(.device-inner-tabbar) {
  max-width: 100%;
  gap: 16px;
  overflow-x: auto;
}

.precision-config-view :deep(.point-data-grid) {
  grid-template-columns: minmax(0, 1fr);
}

.precision-config-view :deep(.compact-point-detail) {
  padding: 16px;
  border-top: 1px solid var(--console-border-soft);
  border-left: 0;
}

.precision-config-view :deep(.point-value-display > strong) {
  overflow-wrap: anywhere;
}

.precision-config-view :deep(.industrial-pagination-row) {
  padding: 10px 14px;
}

.precision-config-view :deep(.industrial-pagination-row .el-pagination) {
  max-width: 100%;
  overflow-x: auto;
}

@media (max-width: 980px) {
  .precision-config-view :deep(.device-config-workbench-pane) {
    grid-template-columns: minmax(0, 1fr);
  }

  .precision-config-view :deep(.device-data-panel) {
    grid-column: 1;
    grid-row: 5;
  }
}

@media (max-width: 600px) {
  .device-operation-panel.precision-config-view {
    padding: 18px 14px 20px;
  }

  .precision-config-view :deep(.control-status-row) {
    grid-template-columns: minmax(0, 1fr);
  }

  .precision-config-view :deep(.run-control-card) {
    padding: 14px;
  }
}
</style>
