<template>
  <div class="page-stack diagnostic-workbench">
    <section class="page-title-row">
      <div><span class="page-kicker">系统诊断</span><h2>系统实时状态诊断</h2><p>接口：/health、/monitor/system、/monitor/devices、/monitor/cache、/monitor/perf/detail、/monitor/report、/api/config/summary</p></div>
      <el-button type="primary" :loading="loading" @click="load">运行完整诊断</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <div class="modao-stat-grid">
      <article class="modao-stat-card info"><span>应用服务</span><strong>{{ statusOf('health') }}</strong><small>健康检查</small></article>
      <article class="modao-stat-card success"><span>设备连接</span><strong>{{ statusOf('devices') }}</strong><small>连接指标</small></article>
      <article class="modao-stat-card warning"><span>缓存服务</span><strong>{{ statusOf('cache') }}</strong><small>缓存指标</small></article>
      <article class="modao-stat-card info"><span>云端上报</span><strong>{{ statusOf('report') }}</strong><small>上报链路</small></article>
    </div>

    <section class="exact-surface">
      <div class="exact-surface-head"><h3>诊断建议</h3><span>失败模块不会默认显示健康</span></div>
      <ul class="diagnostic-advice-list"><li v-for="item in diagnosticAdvice" :key="item">{{ item }}</li></ul>
    </section>

    <section class="surface-grid two">
      <article v-for="card in diagnosticCards" :key="card.key" class="surface-card diagnostic-module-card">
        <div class="surface-card-head"><h3>{{ card.title }}</h3><el-tag :type="card.tag" effect="light">{{ card.status }}</el-tag></div>
        <p>{{ card.message }}</p>
        <pre class="json-view compact-json">{{ card.raw }}</pre>
      </article>
    </section>

    <section class="exact-surface"><div class="exact-surface-head"><h3>原始诊断 JSON</h3><span>失败模块会记录错误信息，不展示假正常</span></div><pre class="json-view">{{ diagnosticText }}</pre></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { getConfigSummary } from "@/api/config.api";
import { getHealth } from "@/api/health.api";
import { getCacheMetrics, getCloudReportMetrics, getDeviceConnectionMetrics, getPerformanceDetail, getSystemResources } from "@/api/monitor.api";
import { buildDiagnosticAdvice } from "@/views/ops/ops-utils";

const loading = ref(false);
const error = ref("");
const diagnostic = ref<Record<string, unknown>>({});
const diagnosticText = computed(() => JSON.stringify(diagnostic.value, null, 2));
const diagnosticAdvice = computed(() => buildDiagnosticAdvice(diagnostic.value));
const diagnosticCards = computed(() => Object.entries(diagnostic.value).map(([key, value]) => {
  const body = readRecord(value);
  const status = String(body.status || (Object.keys(body).length ? "已返回" : "未知"));
  return {
    key,
    title: titleOf(key),
    status,
    tag: tagOf(status),
    message: String(body.message || body.error || body.description || "模块已返回诊断数据"),
    raw: JSON.stringify(value, null, 2)
  };
}));

type TagType = "success" | "warning" | "danger" | "info";

function statusOf(key: string): string {
  const item = diagnostic.value[key] as Record<string, unknown> | undefined;
  return item?.status ? String(item.status) : item ? "已返回" : "未知";
}

function tagOf(status: string): TagType {
  const normalized = status.toUpperCase();
  if (["ERROR", "DOWN", "FAIL"].some((flag) => normalized.includes(flag))) {
    return "danger";
  }
  if (["WARN", "DEGRADED", "UNKNOWN"].some((flag) => normalized.includes(flag))) {
    return "warning";
  }
  if (["OK", "UP", "已返回"].some((flag) => normalized.includes(flag))) {
    return "success";
  }
  return "info";
}

function titleOf(key: string): string {
  return {
    health: "健康检查",
    system: "系统资源",
    devices: "设备连接",
    cache: "缓存指标",
    performance: "性能明细",
    report: "云端上报",
    summary: "配置摘要"
  }[key] || key;
}

function readRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

async function settle(name: string, loader: () => Promise<unknown>) {
  try {
    return [name, await loader()] as const;
  } catch (caught) {
    return [name, { status: "ERROR", message: caught instanceof Error ? caught.message : "加载失败" }] as const;
  }
}

async function load() {
  loading.value = true;
  error.value = "";
  const entries = await Promise.all([
    settle("health", getHealth),
    settle("system", getSystemResources),
    settle("devices", getDeviceConnectionMetrics),
    settle("cache", getCacheMetrics),
    settle("performance", getPerformanceDetail),
    settle("report", getCloudReportMetrics),
    settle("summary", getConfigSummary)
  ]);
  diagnostic.value = Object.fromEntries(entries);
  loading.value = false;
}

onMounted(load);
</script>
