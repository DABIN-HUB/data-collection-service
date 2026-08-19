<template>
  <router-view />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";

import { router } from "@/router";

let removeNavigateListener: (() => void) | null = null;

onMounted(() => {
  removeNavigateListener = window.collectorDesktop?.onNavigate((path) => {
    router.push(path).catch(() => undefined);
  }) || null;
});

onBeforeUnmount(() => {
  removeNavigateListener?.();
});
</script>
