<template>
  <el-scrollbar class="device-tree-scroll">
    <div v-if="deviceStore.error" class="resource-error">{{ deviceStore.error }}</div>
    <div v-if="groupedDevices.length === 0 && !deviceStore.loading" class="resource-empty">暂无设备配置</div>
    <section v-for="group in groupedDevices" :key="group.name" class="device-group">
      <button class="group-title" type="button">
        <el-icon><Folder /></el-icon>
        <span>{{ group.name }}</span>
        <small>{{ group.devices.length }}</small>
      </button>
      <button
        v-for="device in group.devices"
        :key="device.normalizedId"
        type="button"
        class="device-node"
        :class="{ 'is-active': device.normalizedId === deviceStore.selectedDeviceId }"
        @click="selectDevice(device.normalizedId)"
      >
        <i class="dot" :class="statusClass(device)"></i>
        <span class="device-node-name">{{ device.displayName }}</span>
        <small>{{ device.displayProtocol }}</small>
      </button>
    </section>
  </el-scrollbar>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";

import { resolveDeviceStatus, useDeviceStore } from "@/stores/device.store";
import type { DeviceViewModel } from "@/types/device";

const router = useRouter();
const deviceStore = useDeviceStore();

const groupedDevices = computed(() => {
  const groups = new Map<string, DeviceViewModel[]>();
  for (const device of deviceStore.devices) {
    const devices = groups.get(device.displayGroup) ?? [];
    devices.push(device);
    groups.set(device.displayGroup, devices);
  }
  return Array.from(groups.entries()).map(([name, devices]) => ({ name, devices }));
});

function selectDevice(deviceId: string) {
  deviceStore.selectDevice(deviceId);
  if (router.currentRoute.value.name !== "device") {
    router.push({ name: "device" }).catch(() => undefined);
  }
}

function statusClass(device: DeviceViewModel): string {
  const status = resolveDeviceStatus(device);
  return {
    ONLINE: "good",
    CONNECTING: "warn",
    ERROR: "bad",
    DISABLED: "muted",
    OFFLINE: "muted"
  }[status];
}
</script>
