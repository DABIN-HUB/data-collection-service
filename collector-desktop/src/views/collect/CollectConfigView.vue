<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div></div>
      <div class="table-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button @click="exportConfig">导出配置</el-button>
      </div>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <section class="exact-surface">
      <div class="exact-surface-head"><h3>全局采集配置</h3><span>当前运行配置</span></div>
      <pre class="json-view">{{ summaryText }}</pre>
    </section>
    <section class="exact-table-card">
      <div class="exact-table-title"><h3>协议配置列表</h3><span>协议字段由后端 Schema 提供</span></div>
      <el-table :data="protocols" border height="420">
        <el-table-column prop="title" label="协议名称" min-width="160" />
        <el-table-column prop="protocol" label="规范编码" width="170" />
        <el-table-column prop="defaultPort" label="默认端口" width="110" />
        <el-table-column prop="implementationState" label="实现状态" width="130" />
        <el-table-column prop="writeCapability" label="写入能力" width="130" />
        <el-table-column prop="subscriptionCapability" label="订阅能力" width="150" />
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { exportConfigs, getConfigSummary } from "@/api/config.api";
import { listProtocols } from "@/api/protocol.api";
import type { ProtocolSchema } from "@/types/protocol";

const loading = ref(false);
const error = ref("");
const protocols = ref<ProtocolSchema[]>([]);
const summary = ref<unknown>({});
const summaryText = computed(() => JSON.stringify(summary.value, null, 2));

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [protocolList, configSummary] = await Promise.all([listProtocols(), getConfigSummary()]);
    protocols.value = protocolList;
    summary.value = configSummary;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "采集配置加载失败";
  } finally {
    loading.value = false;
  }
}

async function exportConfig() {
  const data = await exportConfigs();
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `collector-config-${Date.now()}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

onMounted(load);
</script>
