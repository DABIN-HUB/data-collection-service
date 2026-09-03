<template>
  <section class="exact-page log-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>日志</h1>
        <span class="heading-online"><i></i>{{ filteredLogs.length }} 条 · 错误 {{ logSummary.error }}</span>
      </div>
      <div class="heading-actions">
        <button type="button" :class="{ 'is-active': logAutoRefresh }" @click="toggleAutoRefresh">
          {{ logAutoRefresh ? '停止自动刷新' : '自动刷新' }}
        </button>
        <button type="button" :disabled="loading" @click="refreshLogs">刷新日志</button>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar log-toolbar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="logLevel" @change="refreshLogs">
            <option value="">全部级别</option>
            <option value="ERROR">错误</option>
            <option value="WARN">警告</option>
            <option value="INFO">信息</option>
            <option value="DEBUG">调试</option>
          </select>
          <select v-model="logDeviceId" @change="refreshLogs">
            <option value="">当前结果内全部设备</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          <input v-model="logLogger" type="text" placeholder="记录器名称 logger" @keydown.enter="refreshLogs" />
          <input v-model="logThread" type="text" placeholder="当前结果内线程名" @keydown.enter="refreshLogs" />
          <input v-model="logKeyword" type="search" placeholder="搜索日志内容、设备、点位或来源" @keydown.enter="refreshLogs" />
          <input v-model.number="logLimit" type="number" min="20" max="2000" step="20" />
          <button type="button" class="primary" :disabled="loading" @click="refreshLogs">查询</button>
        </div>
        <div class="exact-toolbar-group">
          <button type="button" @click="showErrorLogs">错误日志快速定位</button>
          <button type="button" :disabled="exceptionLoading" @click="searchLatestExceptionLogs">最近异常定位</button>
          <button type="button" @click="downloadLogs('txt')">导出文本</button>
          <button type="button" @click="downloadLogs('json')">导出 JSON</button>
        </div>
      </div>
      <p class="log-filter-note">设备和线程条件只在当前返回结果内本地过滤；服务端真实支持的查询参数只有级别、记录器、关键字和数量。</p>

      <div class="exact-diagnostic-cards log-summary-cards">
        <div class="exact-diagnostic-card"><span>当前结果</span><strong>{{ logSummary.total }}</strong></div>
        <div class="exact-diagnostic-card"><span>错误日志</span><strong>{{ logSummary.error }}</strong></div>
        <div class="exact-diagnostic-card"><span>警告日志</span><strong>{{ logSummary.warn }}</strong></div>
        <div class="exact-diagnostic-card"><span>日志器 / 线程</span><strong>{{ logSummary.loggerCount }} / {{ logSummary.threadCount }}</strong></div>
      </div>

      <section class="exact-surface modao-log-panel">
        <div v-if="filteredLogs.length === 0" class="empty-state compact">{{ error || '当前条件下没有可显示日志' }}</div>
        <div v-for="(log, index) in filteredLogs" :key="`${log.timestamp || log.time || index}-${log.logger || '-'}-${log.thread || '-'}`" class="modao-log-row">
          <span class="modao-log-time">{{ formatTime(log.timestamp || log.time) }}</span>
          <strong class="modao-log-level" :class="String(log.level || 'INFO').toUpperCase()">{{ log.level || 'INFO' }}</strong>
          <span class="modao-log-name" :title="String(log.logger || '-')">{{ shortLoggerName(log.logger) }}</span>
          <span class="modao-log-thread" :title="String(log.thread || '-')">{{ log.thread || '-' }}</span>
          <span class="modao-log-message">{{ log.message || log.content || '-' }}</span>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { getExceptionStats } from "@/api/monitor.api";
import { getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import {
  buildLogExportFilename,
  buildLogQueryParams,
  buildLogSearchFromException,
  exportLogRowsAsJson,
  exportLogRowsAsText,
  filterLogRows,
  summarizeLogRows
} from "@/features/log/utils/log-utils";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { ExceptionStatsSnapshot, LogRow } from "@/types/monitor";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();

const logs = ref<LogRow[]>([]);
const logLevel = ref("");
const logDeviceId = ref("");
const logLogger = ref("");
const logThread = ref("");
const logKeyword = ref("");
const logLimit = ref(100);
const logAutoRefresh = ref(false);
const loading = ref(false);
const error = ref("");
const exceptionLoading = ref(false);
let logTimer: number | null = null;

const filteredLogs = computed(() => filterLogRows(logs.value, {
  level: logLevel.value,
  logger: logLogger.value,
  keyword: logKeyword.value,
  deviceId: logDeviceId.value,
  thread: logThread.value
}));
const logSummary = computed(() => summarizeLogRows(filteredLogs.value));

async function loadLogs() {
  if (loading.value) {
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    logs.value = normalizeLogRows(await getOpsLogs(buildLogQueryParams({
      level: logLevel.value,
      logger: logLogger.value,
      keyword: logKeyword.value,
      deviceId: logDeviceId.value,
      thread: logThread.value,
      limit: logLimit.value
    })));
  } catch (caught) {
    logs.value = [];
    error.value = caught instanceof Error ? caught.message : "运行日志加载失败";
  } finally {
    loading.value = false;
  }
}

function refreshLogs() {
  void loadLogs();
}

function toggleAutoRefresh() {
  logAutoRefresh.value = !logAutoRefresh.value;
}

function showErrorLogs() {
  logLevel.value = "ERROR";
  void loadLogs();
}

async function searchLatestExceptionLogs() {
  if (exceptionLoading.value) {
    return;
  }
  exceptionLoading.value = true;
  try {
    const root: ExceptionStatsSnapshot = await getExceptionStats();
    const recent = Array.isArray(root.recent) ? root.recent : [];
    if (!recent.length) {
      ElMessage.warning("当前没有最近异常可用于日志定位");
      return;
    }
    logKeyword.value = buildLogSearchFromException(recent[0]);
    logLevel.value = "";
    void loadLogs();
    ElMessage.info("已按最近异常填充日志搜索条件");
  } catch (caught) {
    ElMessage.warning(caught instanceof Error ? caught.message : "最近异常定位失败");
  } finally {
    exceptionLoading.value = false;
  }
}

function downloadLogs(type: "json" | "txt") {
  if (!filteredLogs.value.length) {
    ElMessage.warning("当前没有可导出的日志");
    return;
  }
  const content = type === "json" ? exportLogRowsAsJson(filteredLogs.value) : exportLogRowsAsText(filteredLogs.value);
  const mime = type === "json" ? "application/json;charset=utf-8" : "text/plain;charset=utf-8";
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = buildLogExportFilename(type);
  anchor.click();
  URL.revokeObjectURL(url);
}

function syncLogTimer() {
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = null;
  }
  if (logAutoRefresh.value) {
    logTimer = window.setInterval(() => {
      void loadLogs();
    }, 5000);
  }
}

function applyRouteQuery() {
  const nextLevel = normalizeRouteQuery(route.query.level);
  const nextDeviceId = normalizeRouteQuery(route.query.deviceId);
  const nextLogger = normalizeRouteQuery(route.query.logger);
  const nextThread = normalizeRouteQuery(route.query.thread);
  const nextKeyword = normalizeRouteQuery(route.query.keyword);
  const nextLimit = Number(normalizeRouteQuery(route.query.limit));
  let changed = false;
  if (logLevel.value !== nextLevel) {
    logLevel.value = nextLevel;
    changed = true;
  }
  if (logDeviceId.value !== nextDeviceId) {
    logDeviceId.value = nextDeviceId;
    changed = true;
  }
  if (logLogger.value !== nextLogger) {
    logLogger.value = nextLogger;
    changed = true;
  }
  if (logThread.value !== nextThread) {
    logThread.value = nextThread;
    changed = true;
  }
  if (logKeyword.value !== nextKeyword) {
    logKeyword.value = nextKeyword;
    changed = true;
  }
  if (Number.isFinite(nextLimit) && nextLimit > 0 && logLimit.value !== nextLimit) {
    logLimit.value = Math.trunc(nextLimit);
    changed = true;
  }
  return changed;
}

function shortLoggerName(logger: unknown): string {
  const value = String(logger || "-");
  const parts = value.split(".").filter(Boolean);
  return parts.length > 2 ? parts.slice(-2).join(".") : value;
}

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = typeof value === "number" ? new Date(value) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function normalizeRouteQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] ?? "") : "";
  }
  return value === undefined || value === null ? "" : String(value);
}

async function initializeLogView() {
  await appStore.initialize();
  applyRouteQuery();
  await deviceStore.refresh();
  await loadLogs();
  syncLogTimer();
}

onMounted(() => {
  void initializeLogView();
});

onBeforeUnmount(() => {
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = null;
  }
});

watch(() => logAutoRefresh.value, syncLogTimer);
watch(() => [route.query.level, route.query.deviceId, route.query.logger, route.query.thread, route.query.keyword, route.query.limit], () => {
  if (applyRouteQuery()) {
    void loadLogs();
  }
});
</script>

<style scoped>
.log-view .modao-log-panel {
  min-height: 420px;
  overflow: auto;
  color: #cbd5e1;
  background: #08131f;
  font-family: "Cascadia Code", Consolas, monospace;
  font-size: 12px;
}

.log-view .modao-log-row {
  display: grid;
  grid-template-columns: 150px 58px minmax(150px, 240px) minmax(120px, 180px) minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 9px 12px;
  border-bottom: 1px solid rgba(45, 74, 122, 0.32);
}

.log-view .modao-log-row:hover {
  background: rgba(45, 74, 122, 0.25);
}

.log-view .modao-log-time,
.log-view .modao-log-name,
.log-view .modao-log-thread {
  color: #71839a;
}

.log-view .modao-log-thread,
.log-view .modao-log-message {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-view .log-toolbar {
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px 10px;
}

.log-view .log-toolbar .exact-toolbar-filters {
  flex: 1 1 auto;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(96px, 0.68fr) minmax(116px, 0.82fr) minmax(128px, 0.92fr) minmax(128px, 0.92fr) minmax(168px, 1.05fr) minmax(72px, 0.5fr) auto;
  gap: 6px 8px;
  align-items: center;
}

.log-view .log-toolbar .exact-toolbar-filters input,
.log-view .log-toolbar .exact-toolbar-filters select {
  min-width: 0;
  width: 100%;
}

.log-view .log-toolbar .exact-toolbar-filters button {
  justify-self: start;
  width: auto;
  min-width: 0;
}

.log-view .log-toolbar .exact-toolbar-group:not(.exact-toolbar-filters) {
  flex: 0 0 auto;
  justify-content: flex-start;
  flex-wrap: nowrap;
  gap: 6px;
}

.log-view .log-filter-note {
  margin: -6px 0 12px;
  color: var(--exact-dim);
  font-size: 12px;
}

.log-view .modao-log-level.ERROR { color: #f87171; }
.log-view .modao-log-level.WARN { color: #fbbf24; }
.log-view .modao-log-level.INFO { color: #60a5fa; }
.log-view .modao-log-level.DEBUG { color: #94a3b8; }
</style>
