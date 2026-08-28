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
