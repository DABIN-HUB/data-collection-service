<template>
  <DeviceOperationShell active-tab="config" v-slot="{ device }">
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
