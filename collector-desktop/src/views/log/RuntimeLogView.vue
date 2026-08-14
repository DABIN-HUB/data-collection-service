<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">运行日志</span>
        <h2>全局运行日志</h2>
        <p>查看采集服务最近脱敏运行日志，支持级别和关键字过滤。</p>
      </div>
    </section>
    <LogPanel />
  </div>
</template>

<script setup lang="ts">
import LogPanel from "@/components/log/LogPanel.vue";
</script>
