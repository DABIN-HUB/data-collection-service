<template>
  <section class="monitor-panel log-workbench">
    <div class="panel-toolbar">
      <div>
        <h3>{{ deviceId ? '设备运行日志' : '运行日志' }}</h3>
        <p>日志来自 `/api/ops/logs`，只导出当前已加载的脱敏日志。</p>
      </div>
      <div class="table-actions">
        <el-select v-model="level" placeholder="日志级别" clearable style="width: 120px">
          <el-option label="INFO" value="INFO" />
          <el-option label="WARN" value="WARN" />
          <el-option label="ERROR" value="ERROR" />
          <el-option label="DEBUG" value="DEBUG" />
          <el-option label="TRACE" value="TRACE" />
        </el-select>
        <el-input v-model="logger" placeholder="logger 过滤" clearable style="width: 180px" />
        <el-input v-model="keyword" placeholder="搜索日志内容" clearable :prefix-icon="Search" />
        <el-input-number v-model="limit" :min="50" :max="2000" :step="50" controls-position="right" />
        <el-switch v-model="autoRefresh" active-text="自动刷新" inactive-text="手动" />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button @click="downloadLogs">导出日志</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-table v-loading="loading" :data="filteredRows" height="420" border>
      <el-table-column label="时间" min-width="160"><template #default="{ row }">{{ formatTime(row.timestamp || row.time) }}</template></el-table-column>
      <el-table-column prop="deviceName" label="设备名称" min-width="150" />
      <el-table-column label="级别" width="100"><template #default="{ row }"><el-tag :type="levelType(row.level)" effect="light">{{ row.level || 'INFO' }}</el-tag></template></el-table-column>
      <el-table-column prop="logger" label="Logger" min-width="180" />
      <el-table-column label="日志内容" min-width="320"><template #default="{ row }">{{ row.message || row.content || '-' }}</template></el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import type { LogRow } from "@/types/monitor";
import { exportLogRows } from "@/views/ops/ops-utils";

const props = defineProps<{
  deviceId?: string;
}>();

const loading = ref(false);
const error = ref("");
const rows = ref<LogRow[]>([]);
const level = ref("");
const logger = ref("");
const keyword = ref("");
const limit = ref(200);
const autoRefresh = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

const filteredRows = computed(() => rows.value.filter((row) => {
  const matchesDevice = !props.deviceId || row.deviceId === props.deviceId || row.deviceName === props.deviceId;
  const matchesLogger = !logger.value || String(row.logger || "").toLowerCase().includes(logger.value.toLowerCase());
  return matchesDevice && matchesLogger;
}));

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const response = await getOpsLogs({ level: level.value || undefined, keyword: keyword.value || undefined, limit: limit.value });
    rows.value = normalizeLogRows(response);
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "运行日志加载失败";
  } finally {
    loading.value = false;
  }
}

function downloadLogs() {
  const content = exportLogRows(filteredRows.value);
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `collector-logs-${Date.now()}.txt`;
  link.click();
  URL.revokeObjectURL(url);
  ElMessage.success("已导出当前日志");
}

function syncTimer() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  if (autoRefresh.value) {
    timer = setInterval(() => load(), 5000);
  }
}

function levelType(levelValue?: string): "success" | "warning" | "danger" | "info" {
  if (levelValue === "ERROR") {
    return "danger";
  }
  if (levelValue === "WARN") {
    return "warning";
  }
  if (levelValue === "INFO") {
    return "success";
  }
  return "info";
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

onMounted(() => {
  load();
  syncTimer();
});
onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer);
  }
});
watch(() => [level.value, keyword.value, limit.value], load);
watch(autoRefresh, syncTimer);
</script>
