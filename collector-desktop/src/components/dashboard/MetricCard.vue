<template>
  <section class="metric-card">
    <div class="metric-icon" :class="tone">
      <el-icon><component :is="icon" /></el-icon>
    </div>
    <div class="metric-copy">
      <span>{{ title }}</span>
      <strong>{{ value }}</strong>
      <small>{{ description }}</small>
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  value: string | number;
  description: string;
  icon: string;
  tone: "blue" | "green" | "orange" | "red" | "gray";
}>();
</script>
