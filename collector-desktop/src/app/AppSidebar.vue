<template>
  <aside class="sidebar app-sidebar">
    <div class="sidebar-top">
      <div class="brand">
        <span class="brand-mark"><img :src="factoryIcon" alt="" /></span>
        <div class="brand-copy"><strong>工业数据控制台</strong></div>
      </div>
      <nav class="section-nav" aria-label="控制台主导航">
        <div v-for="group in navigationGroups" :key="group.title" class="nav-group">
          <span class="nav-group-title">{{ group.title }}</span>
          <RouterLink
            v-for="item in group.items"
            :key="item.key"
            :to="item.path"
            :class="{ 'is-active': isNavigationActive(item) }"
          >
            <span class="nav-glyph" aria-hidden="true"><img :src="item.icon" alt="" /></span><span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>
    </div>

    <div class="sidebar-bottom">
      <div class="system-status" :class="systemStatusClass"><i></i><span>{{ systemStatusText }}</span></div>
      <details class="sidebar-token-drawer">
        <summary>运维令牌</summary>
        <div class="top-token-panel">
          <label>接口访问令牌</label>
          <input v-model="tokenInput" type="password" placeholder="请输入接口令牌" autocomplete="off" @keyup.enter="saveToken" />
          <button type="button" @click="saveToken">保存令牌</button>
        </div>
      </details>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";

import factoryIcon from "@/assets/legacy-icons/factory.svg";
import { navigationGroups, type AppNavigationItem } from "@/app/navigation";
import { useAppStore } from "@/stores/app.store";

const appStore = useAppStore();
const route = useRoute();
const tokenInput = ref("");

const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const systemStatusClass = computed(() => appStore.initialized ? "is-online" : "is-unknown");

onMounted(() => {
  tokenInput.value = appStore.token;
});

watch(() => appStore.token, (token) => {
  tokenInput.value = token;
});

function isNavigationActive(item: AppNavigationItem): boolean {
  return route.path === item.path || Boolean(item.activePaths?.includes(route.path));
}

function saveToken() {
  appStore.setToken(tokenInput.value, true);
  ElMessage.success("令牌已保存");
}
</script>

<style scoped>
.app-sidebar {
  position: relative;
  display: flex;
  width: var(--app-sidebar-width);
  min-width: var(--app-sidebar-width);
  height: 100%;
  min-height: 0;
  padding: 16px 14px;
  flex-direction: column;
  justify-content: space-between;
  gap: 16px;
  overflow: hidden;
  color: var(--console-text-secondary);
  border-right: 1px solid var(--console-border);
  background: var(--console-sidebar);
}

.sidebar-top {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 16px;
}

.brand {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel-soft);
}

.brand-mark img {
  display: block;
  width: 22px;
  height: 22px;
}

.brand-copy strong {
  display: block;
  color: var(--console-text-primary);
  font-size: 15px;
  line-height: 1.2;
}

.section-nav {
  display: block;
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
}

.nav-group,
.nav-group + .nav-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nav-group + .nav-group {
  margin-top: 14px;
}

.nav-group-title {
  display: block;
  padding: 0 8px;
  color: var(--console-text-dim);
  font-size: 11px;
  font-weight: 700;
}

.section-nav a {
  display: flex;
  min-height: 38px;
  padding: 0 10px;
  align-items: center;
  gap: 9px;
  color: var(--console-text-muted);
  border: 1px solid transparent;
  border-radius: var(--console-radius-lg);
  text-decoration: none;
}

.section-nav a:hover {
  color: var(--console-text-primary);
  border-color: var(--console-border-soft);
  background: rgba(59, 130, 246, 0.08);
}

.section-nav a.is-active {
  color: var(--console-text-primary);
  border-color: var(--console-primary-hover);
  background: rgba(37, 99, 235, 0.18);
}

.nav-glyph {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
}

.nav-glyph img {
  display: block;
  width: 18px;
  height: 18px;
}

.sidebar-bottom {
  display: grid;
  flex: 0 0 auto;
  gap: 10px;
}

.system-status {
  display: flex;
  min-height: 34px;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel);
  font-size: 12px;
}

.system-status i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
}

.system-status.is-online {
  color: var(--console-success);
}

.system-status.is-unknown {
  color: var(--console-text-muted);
}

.sidebar-token-drawer {
  color: var(--console-text-muted);
  font-size: 12px;
}

.sidebar-token-drawer summary {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel);
  cursor: pointer;
  list-style: none;
}

.sidebar-token-drawer summary::-webkit-details-marker {
  display: none;
}

.sidebar-token-drawer summary:hover,
.sidebar-token-drawer[open] summary {
  color: var(--console-text-primary);
  border-color: var(--console-border-active);
}

.top-token-panel {
  display: grid;
  margin-top: 8px;
  padding: 10px;
  gap: 8px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-bg-soft);
}

.top-token-panel label {
  color: var(--console-text-muted);
  font-size: 12px;
}
</style>
