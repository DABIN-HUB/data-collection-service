<template>
  <aside class="sidebar app-sidebar">
    <div class="sidebar-top">
      <div class="brand">
        <span class="brand-mark"><img :src="factoryIcon" alt="" /></span>
        <div class="brand-copy"><strong>数据采集工作台</strong><small>工业数据控制台</small></div>
      </div>
      <nav class="section-nav" aria-label="控制台主导航">
        <div v-for="group in navigationGroups" :key="group.title" class="nav-group">
          <span class="nav-group-title">{{ group.title }}</span>
          <RouterLink
            v-for="item in group.items"
            :key="item.key"
            :to="item.path"
            :class="{ 'is-active': isNavigationActive(item) }"
            @click="emit('navigate')"
          >
            <span class="nav-glyph" aria-hidden="true"><img :src="item.icon" alt="" /></span><span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>
    </div>

    <div class="sidebar-bottom">
      <div class="workspace-label">
        <span class="node-square" aria-hidden="true"></span>
        <div>本机采集节点<small>{{ appStore.platform === 'browser' ? '本地浏览器' : `Electron/${appStore.platform}` }} / {{ appStore.initialized ? '客户端就绪' : '初始化中' }}</small></div>
      </div>
      <div class="system-status" :class="systemStatusClass"><i></i><span>{{ systemStatusText }}<small>仅客户端状态</small></span></div>
      <details class="sidebar-token-drawer">
        <summary>运维令牌与连接</summary>
        <div class="top-token-panel">
          <label>接口访问令牌</label>
          <input v-model="tokenInput" type="password" placeholder="请输入接口令牌" autocomplete="off" @keyup.enter="saveToken" />
          <button type="button" @click="saveToken">保存令牌</button>
        </div>
      </details>
      <RouterLink to="/login" class="sidebar-connection" @click="emit('navigate')">服务连接页</RouterLink>
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
const emit = defineEmits<{ navigate: [] }>();

const systemStatusText = computed(() => appStore.initialized ? "客户端就绪" : "初始化中");
const systemStatusClass = computed(() => "is-unknown");

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
  padding: 24px 14px 18px;
  flex-direction: column;
  justify-content: space-between;
  gap: 16px;
  overflow: hidden;
  color: var(--console-text-secondary);
  border-right: 1px solid var(--console-border);
  background: var(--app-color-sidebar);
}

.sidebar-top {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 0;
}

.brand {
  display: flex;
  min-height: 42px;
  flex-direction: column;
  align-items: flex-start;
  gap: 14px;
  padding: 0 12px 25px;
  border-bottom: 1px solid rgb(255 255 255 / 11%);
}

.brand-mark {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  color: #9ee0d9;
  border: 1px solid #7ac4c1;
  border-radius: 0;
  background: transparent;
}

.brand-mark img {
  display: block;
  width: 25px;
  height: 25px;
}

.brand-copy strong {
  display: block;
  color: var(--console-text-primary);
  font-size: 16px;
  font-weight: 650;
  line-height: 1.4;
}

.brand-copy small {
  display: block;
  margin-top: 4px;
  color: var(--console-text-dim);
  font-size: 11px;
}

.workspace-label {
  display: flex;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  color: var(--console-text-secondary);
  font-size: 12px;
}

.workspace-label small {
  display: block;
  margin-top: 2px;
  color: #9eb2bf;
  font-size: 10px;
}

.node-square {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border: 2px solid #b2cbd2;
}

.section-nav {
  display: block;
  min-height: 0;
  padding-top: 18px;
  flex: 1 1 auto;
  overflow: auto;
}

.nav-group,
.nav-group + .nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-group + .nav-group {
  margin-top: 18px;
}

.nav-group-title {
  display: block;
  padding: 0 13px;
  margin-bottom: 3px;
  color: var(--console-text-dim);
  font-size: 11px;
  font-weight: 500;
}

.section-nav a {
  position: relative;
  display: flex;
  min-height: 39px;
  padding: 0 12px;
  align-items: center;
  gap: 11px;
  color: var(--console-text-muted);
  border: 1px solid transparent;
  border-radius: 3px;
  text-decoration: none;
}

.section-nav a:hover {
  color: var(--console-text-primary);
  border-color: transparent;
  background: rgb(255 255 255 / 8%);
}

.section-nav a.is-active {
  color: var(--console-text-primary);
  border-color: transparent;
  background: rgb(255 255 255 / 8%);
}

.section-nav a.is-active::before {
  position: absolute;
  inset: 0 auto 0 -1px;
  width: 2px;
  border-radius: 0;
  background: #65c5c0;
  content: "";
}

.nav-glyph {
  display: grid;
  width: 17px;
  height: 17px;
  place-items: center;
}

.nav-glyph img {
  display: block;
  width: 18px;
  height: 18px;
}

.section-nav a.is-active .nav-glyph img,
.section-nav a:hover .nav-glyph img {
  filter: brightness(0) invert(1);
}

.sidebar-bottom {
  display: grid;
  flex: 0 0 auto;
  gap: 10px;
  padding-top: 15px;
  border-top: 1px solid var(--console-border-soft);
}

.system-status {
  display: flex;
  min-height: 34px;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  color: var(--console-text-muted);
  border: 0;
  border-radius: var(--console-radius-lg);
  background: transparent;
  font-size: 12px;
}

.system-status i {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: currentColor;
}

.system-status.is-unknown {
  color: var(--console-text-muted);
}

.system-status small {
  margin-left: 5px;
  color: var(--console-text-dim);
  font-size: 9px;
}

.sidebar-connection {
  padding: 5px 10px;
  color: var(--console-text-muted);
  font-size: 11px;
  text-decoration: none;
}

.sidebar-connection:hover {
  color: var(--console-text-primary);
}

.sidebar-token-drawer {
  color: var(--console-text-muted);
  font-size: 12px;
}

.sidebar-token-drawer summary {
  display: flex;
  min-height: 32px;
  padding: 0 10px;
  align-items: center;
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

.top-token-panel input {
  width: 100%;
  min-width: 0;
}

.section-nav a:focus-visible,
.sidebar-connection:focus-visible,
.sidebar-token-drawer summary:focus-visible,
.top-token-panel button:focus-visible {
  outline: 2px solid var(--app-control-border-focus);
  outline-offset: 2px;
}

@media (width <= 900px) {
  .app-sidebar {
    width: 100%;
    min-width: 0;
    padding: 12px 16px 16px;
    border-right: 0;
  }

  .section-nav a {
    min-height: 40px;
  }
}
</style>
