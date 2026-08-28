<template>
  <header class="topbar app-topbar">
    <div class="node-status-bar">
      <span class="node-item"><span>本机节点: <strong>{{ nodeIdentity }}</strong></span></span>
      <span class="node-divider"></span>
      <span class="node-item node-health" :class="systemStatusClass"><i></i>服务状态: {{ systemStatusText }}</span>
      <span class="node-divider"></span>
      <span class="node-item"><span>时间: <strong>{{ liveClock }}</strong></span></span>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";

import { useAppStore } from "@/stores/app.store";

const appStore = useAppStore();
const liveClock = ref("--:--:--");
let clockTimer = 0;

const nodeIdentity = computed(() => appStore.platform === "browser" ? "本地浏览器" : `Electron/${appStore.platform}`);
const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const systemStatusClass = computed(() => appStore.initialized ? "is-online" : "is-unknown");

onMounted(() => {
  tickClock();
  clockTimer = window.setInterval(tickClock, 1000);
});

onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
});

function tickClock() {
  liveClock.value = new Date().toLocaleTimeString();
}
</script>

<style scoped>
body.modao-exact .legacy-console .app-topbar {
  display: flex;
  min-height: var(--app-topbar-height);
  padding: 0 var(--app-space-6);
  align-items: center;
  justify-content: flex-end;
  border-bottom: 1px solid var(--app-color-border);
  background: var(--app-color-bg);
}

.app-topbar .node-status-bar {
  display: flex;
  align-items: center;
  gap: var(--app-space-3);
  color: var(--app-color-text-muted);
  font-size: 12px;
}

.app-topbar .node-item {
  display: inline-flex;
  align-items: center;
  gap: var(--app-space-2);
  white-space: nowrap;
}

.app-topbar .node-item strong {
  color: var(--app-color-text-secondary);
  font-weight: 600;
}

.app-topbar .node-divider {
  width: 1px;
  height: 14px;
  background: var(--app-color-border-soft);
}
</style>
