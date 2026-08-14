<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">告警管理</span>
        <h2>全局告警中心</h2>
        <p>查看、过滤和确认所有设备的告警记录。</p>
      </div>
    </section>
    <AlarmTablePanel />
  </div>
</template>

<script setup lang="ts">
import AlarmTablePanel from "@/components/alarm/AlarmTablePanel.vue";
</script>
