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
  min-height: 46px;
  margin: 12px 0 16px;
  padding: 10px 14px;
  border: 1px solid var(--console-border-soft);
  border-radius: 6px;
  background: var(--console-panel);
}

.precision-config-view :deep(.device-config-workbench-pane) {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.precision-config-view :deep(.workbench-config-rail) {
  grid-column: 1;
  grid-row: 1;
  display: grid;
  min-width: 0;
  gap: 12px;
  align-content: start;
}

.precision-config-view :deep(.run-control-card) {
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
  gap: 8px;
}

.precision-config-view :deep(.state-pill) {
  display: grid;
  grid-template-columns: minmax(78px, auto) minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.precision-config-view :deep(.state-pill dd) {
  min-width: 0;
  justify-content: flex-end;
  text-align: right;
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
  margin: 0;
  padding-top: 10px;
  border-top: 1px solid var(--console-border-soft);
}

.precision-config-view :deep(.workbench-config-rail > .el-alert) {
  margin: 0;
}

.precision-config-view :deep(.protocol-config-card) {
  min-width: 0;
}

.precision-config-view :deep(.protocol-config-card > summary) {
  flex-wrap: wrap;
}

.precision-config-view :deep(.protocol-config-card .schema-actions) {
  display: grid;
  gap: 10px;
  align-items: start;
}

.precision-config-view :deep(.protocol-config-card .schema-toolbar) {
  display: grid;
  gap: 7px;
  grid-template-columns: minmax(0, 1fr);
}

.precision-config-view :deep(.protocol-config-card .schema-toolbar .el-button) {
  width: 100%;
  margin-left: 0;
}

.precision-config-view :deep(.device-data-panel) {
  grid-column: 2;
  grid-row: 1;
  border-radius: 6px;
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
  padding: 14px;
  border-top: 1px solid var(--console-border-soft);
  border-left: 0;
}

.precision-config-view :deep(.compact-detail-fields) {
  grid-template-columns: minmax(180px, 1.3fr) repeat(4, minmax(0, 1fr));
}

.precision-config-view :deep(button.device-inner-tab) {
  min-height: 54px;
  padding: 0;
  color: var(--console-text-muted);
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.precision-config-view :deep(button.device-inner-tab.is-active) {
  color: var(--console-text-secondary);
}

.precision-config-view :deep(button.table-link-button) {
  min-height: 24px;
  padding: 0;
  color: var(--console-info-text);
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.precision-config-view :deep(.compact-detail-actions button) {
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--console-input-border);
  border-radius: 4px;
  background: var(--console-panel);
  box-shadow: none;
}

.precision-config-view :deep(.industrial-pagination-row) {
  padding: 10px 14px;
}

.precision-config-view :deep(.industrial-pagination-row .el-pagination) {
  max-width: 100%;
  overflow-x: auto;
}

@media (max-width: 1300px) and (min-width: 901px) {
  .precision-config-view :deep(.device-config-workbench-pane) {
    grid-template-columns: 236px minmax(0, 1fr);
    gap: 12px;
  }

  .precision-config-view :deep(.compact-detail-fields) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .precision-config-view :deep(.device-config-workbench-pane) {
    grid-template-columns: minmax(0, 1fr);
  }

  .precision-config-view :deep(.device-data-panel) {
    grid-column: 1;
    grid-row: 1;
  }

  .precision-config-view :deep(.workbench-config-rail) {
    grid-column: 1;
    grid-row: 2;
  }

  .precision-config-view :deep(.compact-detail-fields) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .device-operation-panel.precision-config-view {
    padding: 18px 14px 20px;
  }

  .precision-config-view :deep(.run-control-card) {
    padding: 14px;
  }

  .precision-config-view :deep(.compact-detail-fields) {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
