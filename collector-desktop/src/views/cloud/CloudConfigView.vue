<template>
  <div class="page-stack cloud-workbench">
    <section class="page-title-row">
      <div></div>
      <el-button :loading="loading" @click="load">刷新链路</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-alert v-if="reportSummary.riskLevel !== 'LOW'" :title="riskText" type="warning" :closable="false" />
    <div class="modao-stat-grid">
      <article class="modao-stat-card success"><span>上报链路</span><strong>{{ textOf(report.statusText || report.status) }}</strong><small>当前综合状态</small></article>
      <article class="modao-stat-card info"><span>待发送消息</span><strong>{{ reportSummary.pending }}</strong><small>可靠队列等待量</small></article>
      <article class="modao-stat-card warning"><span>等待 ACK</span><strong>{{ reportSummary.pendingAck }}</strong><small>已发送待确认</small></article>
      <article class="modao-stat-card danger"><span>隔离消息</span><strong>{{ reportSummary.isolated }}</strong><small>需要人工检查</small></article>
    </div>

    <div class="surface-grid two">
      <section class="surface-card"><div class="surface-card-head"><h3>Outbox</h3><span class="surface-note">可靠消息队列</span></div><pre class="json-view compact-json">{{ JSON.stringify(outbox, null, 2) }}</pre></section>
      <section class="surface-card"><div class="surface-card-head"><h3>ACK Runtime</h3><span class="surface-note">待确认运行态</span></div><pre class="json-view compact-json">{{ JSON.stringify(ackRuntime, null, 2) }}</pre></section>
    </div>

    <section class="exact-table-card">
      <div class="exact-table-title"><h3>上报处理器</h3><span>{{ processors.length }} 个处理器</span></div>
      <el-table :data="processors" border>
        <el-table-column label="名称" min-width="160"><template #default="{ row }">{{ row.name || row.type || row.channel || '-' }}</template></el-table-column>
        <el-table-column label="状态" min-width="120"><template #default="{ row }">{{ row.status || row.enabled || '-' }}</template></el-table-column>
        <el-table-column label="目标" min-width="180"><template #default="{ row }">{{ row.endpoint || row.host || row.url || '-' }}</template></el-table-column>
        <el-table-column label="原始数据" min-width="260"><template #default="{ row }"><code>{{ JSON.stringify(row) }}</code></template></el-table-column>
      </el-table>
    </section>

    <section class="exact-surface"><div class="exact-surface-head"><h3>上报链路原始数据</h3><span>策略、处理器、风险、Outbox 和 ACK</span></div><pre class="json-view">{{ reportText }}</pre></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { getCloudReportMetrics } from "@/api/monitor.api";
import { summarizeReportMetrics } from "@/views/ops/ops-utils";

const loading = ref(false);
const error = ref("");
const report = ref<Record<string, unknown>>({});
const outbox = computed(() => (report.value.outbox || {}) as Record<string, unknown>);
const ackRuntime = computed(() => (report.value.ackRuntime || {}) as Record<string, unknown>);
const processors = computed(() => normalizeProcessors(report.value));
const reportSummary = computed(() => summarizeReportMetrics(report.value));
const riskText = computed(() => reportSummary.value.riskLevel === "HIGH"
  ? "云端上报存在高风险：存在隔离消息或大量待 ACK 消息"
  : "云端上报存在积压：请关注 Outbox 和 ACK 运行态");
const reportText = computed(() => JSON.stringify(report.value, null, 2));

function textOf(value: unknown): string {
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

function normalizeProcessors(value: Record<string, unknown>): Record<string, unknown>[] {
  for (const key of ["processors", "handlers", "channels", "reporters"]) {
    if (Array.isArray(value[key])) {
      return value[key] as Record<string, unknown>[];
    }
  }
  return [];
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    report.value = await getCloudReportMetrics() as Record<string, unknown>;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "云平台链路加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
