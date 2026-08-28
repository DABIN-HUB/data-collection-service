<template>
  <div class="shell legacy-console theme-anchor modao-exact app-shell">
    <AppSidebar />
    <main class="content app-shell__main">
      <AppTopbar />
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { RouterView } from "vue-router";

import AppSidebar from "@/app/AppSidebar.vue";
import AppTopbar from "@/app/AppTopbar.vue";
import { useAppStore } from "@/stores/app.store";

const appStore = useAppStore();

onMounted(() => {
  document.body.classList.add("theme-anchor", "modao-exact");
  void appStore.initialize();
});

onBeforeUnmount(() => {
  document.body.classList.remove("theme-anchor", "modao-exact");
});
</script>
