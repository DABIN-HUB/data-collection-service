<template>
  <footer class="status-bar">
    <span class="status-item status-primary">
      <i :class="runtimeStore.connected ? 'dot good' : 'dot muted'"></i>
      {{ runtimeStore.connected ? '采集服务 ONLINE' : '采集服务 OFFLINE' }}
    </span>
    <span class="status-item">版本：v{{ appStore.appVersion }}</span>
    <span class="status-item status-url">服务：{{ appStore.serverUrl }}</span>
    <span class="status-item">刷新：{{ runtimeStore.generatedAtText }}</span>
    <span class="status-item">设备：{{ deviceStore.onlineCount }}/{{ deviceStore.devices.length }}</span>
    <span class="status-item">平台：{{ appStore.platform }}</span>
    <span class="status-item">后端：{{ appStore.backendManaged ? '桌面托管' : '手动运行' }}</span>
    <span class="status-item status-user">当前用户：{{ appStore.currentUser }}</span>
  </footer>
</template>

<script setup lang="ts">
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import { useRuntimeStore } from "@/stores/runtime.store";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const runtimeStore = useRuntimeStore();
</script>
