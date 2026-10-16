<template>
  <header class="topbar app-topbar" :class="{ 'app-topbar--device-list': route.path === '/device' }">
    <div class="app-topbar__navigation">
      <button
        type="button"
        class="app-topbar__menu"
        data-navigation-trigger
        aria-label="打开控制台导航"
        aria-controls="app-navigation"
        :aria-expanded="navigationOpen"
        @click="emit('open-navigation')"
      ><span aria-hidden="true">☰</span></button>
      <span class="app-topbar__page">{{ currentPage }}</span>
      <span class="app-topbar__separator" aria-hidden="true"></span>
      <span class="app-topbar__group">{{ navigationGroups.find(group => group.items.some(item => item.path === route.path || item.activePaths?.includes(route.path)))?.title || '控制台' }}工作空间</span>
    </div>
    <div class="node-status-bar">
      <span class="node-item"><span>本机节点: <strong>{{ nodeIdentity }}</strong></span></span>
      <span class="node-divider"></span>
      <span class="node-item node-health is-unknown"><i></i>{{ systemStatusText }}</span>
      <span class="node-divider node-clock"></span>
      <span class="node-item node-clock"><span>本机时间: <strong>{{ liveClock }}</strong></span></span>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";

import { navigationGroups } from "@/app/navigation";
import { useAppStore } from "@/stores/app.store";

defineProps<{ navigationOpen: boolean }>();
const emit = defineEmits<{ 'open-navigation': [] }>();
const appStore = useAppStore();
const route = useRoute();
const liveClock = ref("--:--:--");
let clockTimer = 0;

const nodeIdentity = computed(() => appStore.platform === "browser" ? "本地浏览器" : `Electron/${appStore.platform}`);
const systemStatusText = computed(() => appStore.initialized ? "客户端就绪" : "初始化中");
const currentPage = computed(() => navigationGroups.flatMap((group) => group.items)
  .find((item) => item.path === route.path || item.activePaths?.includes(route.path))?.label || "控制台");

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
.app-topbar {
  display: flex;
  flex: 0 0 auto;
  min-height: var(--app-topbar-height);
  padding: 0 28px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--app-color-border);
  background: var(--app-color-panel);
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

.app-topbar__navigation {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.app-topbar__page {
  color: var(--console-text-primary);
  font-size: 13px;
  font-weight: 600;
}

.app-topbar__group {
  color: var(--console-text-muted);
  font-size: 12px;
}

.app-topbar__separator {
  height: 16px;
  border-left: 1px solid var(--app-color-border);
}

.app-topbar__menu {
  display: none;
}

@media (width <= 900px) {
  .app-topbar__group,
  .app-topbar__separator {
    display: none;
  }

  .app-topbar--device-list .app-topbar__group,
  .app-topbar--device-list .app-topbar__separator {
    display: inline;
  }

  .app-topbar {
    min-height: 52px;
    padding: 0 12px;
    justify-content: space-between;
    gap: 12px;
  }

  .app-topbar__navigation {
    display: flex;
  }

  .app-topbar__menu {
    display: inline-grid;
    place-items: center;
    width: 44px;
    min-height: 44px;
    flex: 0 0 44px;
  }

  .app-topbar__menu:focus-visible {
    outline: 2px solid var(--app-control-border-focus);
    outline-offset: 2px;
  }

  .app-topbar .node-clock {
    display: none;
  }
}

@media (width <= 640px) {
  .app-topbar .node-item:first-child,
  .app-topbar .node-divider {
    display: none;
  }
}
</style>
