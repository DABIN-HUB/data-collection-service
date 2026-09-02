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
  await deviceStore.startSmart(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  ElMessage.success("已请求启动设备");
}

async function stopSelectedDevice(deviceId: string) {
  await deviceStore.stop(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  ElMessage.success("已请求停止设备");
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
