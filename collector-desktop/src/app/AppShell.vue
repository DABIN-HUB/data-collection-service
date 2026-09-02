<template>
  <div class="shell app-shell">
    <AppSidebar />
    <main class="content app-shell__main">
      <AppTopbar />
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from "vue";
import { RouterView } from "vue-router";

import AppSidebar from "@/app/AppSidebar.vue";
import AppTopbar from "@/app/AppTopbar.vue";
import { useAppStore } from "@/stores/app.store";

const appStore = useAppStore();

onMounted(() => {
  void appStore.initialize();
});
</script>

<style scoped>
.app-shell {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--console-text-secondary);
  background: var(--console-bg);
}

.app-shell__main {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overflow: hidden;
}

.app-shell :deep(button:not(.el-button)) {
  min-height: 30px;
  padding: 0 12px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border);
  border-radius: var(--console-radius-md);
  background: var(--console-panel-soft);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.app-shell :deep(button:not(.el-button):hover) {
  color: var(--console-text-primary);
  border-color: var(--console-primary-hover);
  background: rgba(59, 130, 246, 0.14);
}

.app-shell :deep(button:not(.el-button).primary) {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.app-shell :deep(button:not(.el-button).danger) {
  color: #fecaca;
  border-color: rgba(239, 68, 68, 0.5);
  background: rgba(239, 68, 68, 0.12);
}

.app-shell :deep(button:not(.el-button):disabled) {
  cursor: not-allowed;
  opacity: 0.55;
}

.app-shell :deep(input:not(.el-input__inner)),
.app-shell :deep(select),
.app-shell :deep(textarea:not(.el-textarea__inner)) {
  min-height: 30px;
  padding: 0 10px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-md);
  outline: none;
  background: var(--console-input-bg);
  font: inherit;
  font-size: 12px;
}

.app-shell :deep(textarea:not(.el-textarea__inner)) {
  height: auto;
  padding: 10px;
  resize: vertical;
}

.app-shell :deep(input:not(.el-input__inner):focus),
.app-shell :deep(select:focus),
.app-shell :deep(textarea:not(.el-textarea__inner):focus) {
  border-color: var(--console-primary-hover);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.16);
}
</style>
