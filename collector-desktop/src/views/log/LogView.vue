<template>
  <section class="exact-page log-view">
    <div class="section-heading log-heading">
      <div>
        <div class="heading-title-line">
          <h1>日志</h1>
          <span class="log-result-badge">{{ hasLogLastGoodForCurrentServerContext ? `${filteredLogs.length} 条 · 错误 ${logSummary.error}` : '数量未知 · 当前条件尚未成功读取' }}</span>
        </div>
        <p>查询运行记录、定位异常并导出当前结果。</p>
      </div>
      <div class="heading-actions">
        <button type="button" class="log-auto-refresh" :class="{ 'is-active': logAutoRefresh }" :aria-pressed="logAutoRefresh" @click="toggleAutoRefresh">
          <span class="log-toggle-track" aria-hidden="true"><i></i></span>
          {{ logAutoRefresh ? '停止自动刷新' : '自动刷新' }} · 5 秒
        </button>
        <button type="button" :disabled="logQueryDisabled" @click="refreshLogs">刷新日志</button>
      </div>
    </div>

    <div class="exact-page-body log-body">
      <section class="exact-surface log-query-panel" aria-labelledby="log-query-title">
        <div class="log-panel-heading">
          <h2 id="log-query-title">查询条件</h2>
        </div>
        <div class="log-query-fields">
          <div class="log-field log-field-level">
            <label for="log-level">日志级别</label>
            <select id="log-level" v-model="logLevel" @change="refreshLogs">
              <option value="">全部级别</option>
              <option value="ERROR">错误</option>
              <option value="WARN">警告</option>
              <option value="INFO">信息</option>
              <option value="DEBUG">调试</option>
            </select>
          </div>
          <div class="log-field log-field-device">
            <label for="log-device">设备</label>
            <select id="log-device" v-model="logDeviceId">
              <option value="">当前结果内全部设备</option>
              <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
                {{ device.displayName || device.normalizedId }}
              </option>
            </select>
          </div>
          <div class="log-field log-field-keyword">
            <label for="log-keyword">关键字</label>
            <input id="log-keyword" v-model="logKeyword" type="search" placeholder="搜索日志内容、设备、点位或来源" @keydown.enter="refreshLogs" />
          </div>
          <button type="button" class="primary" :disabled="logQueryDisabled" @click="refreshLogs">查询</button>
        </div>
        <details class="log-more-filters">
          <summary>更多筛选{{ logLogger || logThread || logLimit !== 100 ? '（已设置）' : '' }}</summary>
          <div class="log-query-fields">
            <div class="log-field log-field-logger">
              <label for="log-logger">记录器名称</label>
              <input id="log-logger" v-model="logLogger" type="text" placeholder="记录器名称 logger" @keydown.enter="refreshLogs" />
            </div>
            <div class="log-field log-field-thread">
              <label for="log-thread">线程</label>
              <input id="log-thread" v-model="logThread" type="text" placeholder="当前结果内线程名" />
            </div>
            <div class="log-field log-field-limit">
              <label for="log-limit">返回数量（条）</label>
              <input id="log-limit" v-model.number="logLimit" type="number" min="20" max="2000" step="20" />
            </div>
          </div>
        </details>
        <div class="log-secondary-actions">
          <div class="log-action-group" role="group" aria-label="异常定位">
            <button type="button" @click="showErrorLogs">错误日志快速定位</button>
            <button type="button" :disabled="exceptionLoading" @click="searchLatestExceptionLogs">最近异常定位</button>
          </div>
          <div class="log-action-group" role="group" aria-label="导出当前结果">
            <button type="button" :disabled="!canExportLogs" aria-describedby="log-export-note" @click="downloadLogs('txt')">导出文本</button>
            <button type="button" :disabled="!canExportLogs" aria-describedby="log-export-note" @click="downloadLogs('json')">导出 JSON</button>
          </div>
        </div>
        <div class="log-query-notes">
          <p class="log-filter-note">级别、记录器、关键字和数量用于查询；设备与线程只筛选当前返回结果。</p>
          <p id="log-export-note" class="log-export-note">{{ logExportNote }}</p>
        </div>
      </section>
      <div class="log-source-block">
      <el-alert v-if="logReadStatusText" class="log-read-status" :title="logReadStatusText" :type="logReadStatusType" :closable="false" show-icon>
        <div class="log-recovery-actions">
          <button v-if="hasCurrentLogError" type="button" :disabled="logQueryDisabled" @click="refreshLogs">重试读取</button>
          <RouterLink to="/diagnostic">查看接口 / 权限状态</RouterLink>
          <span>只读诊断入口，不修改访问凭据。</span>
        </div>
      </el-alert>
      <OperationSourceStatus
        class="log-source-status"
        :state="logSourceState"
        :source="logSourceText"
        :last-attempt-at="logDisplayedLastAttemptAt"
        :last-success-at="hasLogLastGoodForCurrentServerContext ? logLastSuccessAt : null"
        :detail="logSourceDetail"
      />
      </div>

      <div class="exact-diagnostic-cards log-summary-cards" :class="{ 'is-unknown': !hasLogLastGoodForCurrentServerContext }" aria-label="当前条件的可见日志摘要">
        <div class="exact-diagnostic-card"><span>当前结果</span><strong>{{ hasLogLastGoodForCurrentServerContext ? logSummary.total : '未知' }}</strong></div>
        <div class="exact-diagnostic-card log-summary-error"><span>错误日志</span><strong>{{ hasLogLastGoodForCurrentServerContext ? logSummary.error : '未知' }}</strong></div>
        <div class="exact-diagnostic-card log-summary-warn"><span>警告日志</span><strong>{{ hasLogLastGoodForCurrentServerContext ? logSummary.warn : '未知' }}</strong></div>
        <div class="exact-diagnostic-card"><span>日志器 / 线程</span><strong>{{ hasLogLastGoodForCurrentServerContext ? `${logSummary.loggerCount} / ${logSummary.threadCount}` : '未知' }}</strong></div>
      </div>

      <section class="exact-surface modao-log-panel" aria-labelledby="log-stream-title">
        <div class="log-panel-heading log-stream-heading">
          <h2 id="log-stream-title">运行日志</h2>
          <span>消息完整换行显示 · 可在日志区内滚动</span>
        </div>
        <div class="log-stream-scroll" tabindex="0" role="region" aria-label="运行日志列表，可横向和纵向滚动">
          <div class="log-stream-table" role="table" aria-label="当前可见运行日志">
            <div class="log-column-head" role="row">
              <span role="columnheader">时间</span>
              <span role="columnheader">级别</span>
              <span role="columnheader">记录器</span>
              <span role="columnheader">线程</span>
              <span role="columnheader">日志消息</span>
            </div>
            <div v-for="(log, index) in filteredLogs" :key="`${log.timestamp || log.time || index}-${log.logger || '-'}-${log.thread || '-'}`" class="modao-log-row" role="row">
              <span class="modao-log-time" role="cell">{{ formatTime(log.timestamp || log.time) }}</span>
              <strong class="modao-log-level" :class="String(log.level || 'INFO').toUpperCase()" role="cell">{{ log.level || 'INFO' }}</strong>
              <span class="modao-log-name" :title="String(log.logger || '-')" role="cell">{{ shortLoggerName(log.logger) }}</span>
              <span class="modao-log-thread" :title="String(log.thread || '-')" role="cell">{{ log.thread || '-' }}</span>
              <span class="modao-log-message" :title="String(log.message || log.content || '-')" role="cell">{{ log.message || log.content || '-' }}</span>
            </div>
          </div>
          <div v-if="filteredLogs.length === 0" class="empty-state compact log-empty">{{ logEmptyText }}</div>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { RouterLink, useRoute } from "vue-router";

import { ApiRequestError } from "@/api/http";
import { getExceptionStats } from "@/api/monitor.api";
import { getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import {
  buildLogExportFilename,
  buildLogQueryParams,
  buildLogSearchFromException,
  exportLogRowsAsJson,
  exportLogRowsAsText,
  filterLogRows,
  summarizeLogRows
} from "@/features/log/utils/log-utils";
import {
  buildLogServerQueryContext,
  buildLogVisibleQueryContext,
  isSameLogServerQueryContext,
  isSameLogVisibleQueryContext,
  shouldDisableLogSubmit,
  shouldSkipLogTimerTick,
  type LogServerQueryContext
} from "@/features/log/utils/log-request-lifecycle";
import { buildContextualReadStatus, hasLastGoodForContext, shouldClearLastGoodForRequest } from "@/features/request/utils/context-last-good";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
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
const pendingLogQueryContext = ref<LogServerQueryContext | null>(null);
const pendingLogAttemptAt = ref<number | null>(null);
const lastSuccessfulLogServerContext = ref<LogServerQueryContext | null>(null);
const logLastSuccessAt = ref<number | null>(null);
// 仅供呈现：尝试时间与访问分类也受原请求所有权约束，不另建日志缓存。
const lastAttemptedLogServerContext = ref<LogServerQueryContext | null>(null);
const logLastAttemptAt = ref<number | null>(null);
const logFailureHttpStatus = ref<number | null>(null);
let logTimer: number | null = null;
let isViewMounted = false;

const logQueryOwner = createLatestRequestOwner(isSameLogServerQueryContext);
const exceptionLookupOwner = createLatestRequestOwner(isSameLogVisibleQueryContext);

const filteredLogs = computed(() => filterLogRows(logs.value, {
  level: logLevel.value,
  logger: logLogger.value,
  keyword: logKeyword.value,
  deviceId: logDeviceId.value,
  thread: logThread.value
}));
const logSummary = computed(() => summarizeLogRows(filteredLogs.value));
const logQueryDisabled = computed(() => shouldDisableLogSubmit(
  loading.value,
  pendingLogQueryContext.value,
  currentLogServerQueryContext()
));
const hasLogLastGoodForCurrentServerContext = computed(() => hasLastGoodForContext(lastSuccessfulLogServerContext.value, currentLogServerQueryContext(), isSameLogServerQueryContext));
const hasCurrentLogAttempt = computed(() => isSameLogServerQueryContext(lastAttemptedLogServerContext.value, currentLogServerQueryContext()));
const isCurrentLogLoading = computed(() => loading.value && isSameLogServerQueryContext(pendingLogQueryContext.value, currentLogServerQueryContext()));
const logDisplayedLastAttemptAt = computed(() => isCurrentLogLoading.value ? pendingLogAttemptAt.value : hasCurrentLogAttempt.value ? logLastAttemptAt.value : null);
const hasCurrentLogError = computed(() => hasCurrentLogAttempt.value && Boolean(error.value));
const logSafeFailureText = computed(() => {
  if (logFailureHttpStatus.value === 401 || logFailureHttpStatus.value === 403) {
    return `权限不足（HTTP ${logFailureHttpStatus.value}），请检查接口访问权限`;
  }
  return "接口读取失败，请检查接口访问权限或连接";
});
const logReadStatusText = computed(() => buildContextualReadStatus({
  loading: isCurrentLogLoading.value,
  error: hasCurrentLogError.value ? logSafeFailureText.value : "",
  lastSuccessfulContext: lastSuccessfulLogServerContext.value,
  currentContext: currentLogServerQueryContext(),
  isSameContext: isSameLogServerQueryContext,
  loadingText: "日志加载中...",
  refreshingText: "日志刷新中 · 当前显示上次成功日志",
  staleText: "日志刷新失败 · 当前显示上次成功日志",
  initialErrorPrefix: "日志加载失败"
}));
const logReadStatusType = computed(() => isCurrentLogLoading.value ? "info" : hasLogLastGoodForCurrentServerContext.value ? "warning" : "error");
const logEmptyText = computed(() => {
  if (!hasLogLastGoodForCurrentServerContext.value) {
    return isCurrentLogLoading.value ? "当前条件的日志加载中" : "当前条件尚未成功加载日志";
  }
  return logs.value.length === 0 ? "本次成功查询暂无日志记录" : "当前结果中没有匹配设备或线程等筛选条件的日志";
});
const canExportLogs = computed(() => hasLogLastGoodForCurrentServerContext.value && filteredLogs.value.length > 0);
const logExportNote = computed(() => {
  if (!hasLogLastGoodForCurrentServerContext.value) return "当前条件尚未成功读取，无当前可导出日志。";
  if (!filteredLogs.value.length) return "当前可见结果为空，暂无可导出日志。";
  if (hasCurrentLogError.value || isCurrentLogLoading.value) return "导出显示中的上次成功结果，仅包含当前本地筛选后的日志。";
  return "导出当前可见结果，仅包含当前本地筛选后的日志。";
});
const logSourceState = computed<"unknown" | "empty" | "loading" | "ready" | "degraded" | "error">(() => {
  if (isCurrentLogLoading.value) return "loading";
  if (hasCurrentLogError.value) return hasLogLastGoodForCurrentServerContext.value ? "degraded" : "error";
  if (!hasLogLastGoodForCurrentServerContext.value) return "unknown";
  return logs.value.length === 0 ? "empty" : "ready";
});
const logSourceText = computed(() => {
  const context = currentLogServerQueryContext();
  const level = ({ ERROR: "错误", WARN: "警告", INFO: "信息", DEBUG: "调试" } as Record<string, string>)[context.level] || (context.level ? "指定级别" : "全部级别");
  return `运行日志接口 · ${level}，记录器${context.logger ? '已设置' : '不限'}，关键字${context.keyword ? '已设置' : '不限'}，最多 ${context.limit ?? '未指定'} 条`;
});
const logSourceDetail = computed(() => {
  if (!hasLogLastGoodForCurrentServerContext.value) {
    return logs.value.length ? "当前服务端条件尚未成功读取；此前返回的可见行不代表当前条件的查询结果。" : "当前服务端条件尚未成功读取，日志数量与级别分布未知。";
  }
  if (hasCurrentLogError.value || isCurrentLogLoading.value) return "当前显示同一服务端条件的上次成功数据；设备与线程仍只筛选这些返回结果。";
  return "时间属于当前服务端条件的成功读取；设备与线程为本地筛选，自动刷新 5 秒是设置间隔，不是数据更新时间。";
});

async function loadLogs(options: { fromTimer?: boolean } = {}) {
  if (!isViewMounted) {
    return;
  }
  const requestContext = currentLogServerQueryContext();
  if (options.fromTimer && shouldSkipLogTimerTick(loading.value, pendingLogQueryContext.value, requestContext)) {
    return;
  }
  const ticket = logQueryOwner.begin(requestContext);
  const canPreserveLastGood = hasLastGoodForContext(lastSuccessfulLogServerContext.value, requestContext, isSameLogServerQueryContext);
  if (shouldClearLastGoodForRequest(lastSuccessfulLogServerContext.value, requestContext, isSameLogServerQueryContext)) {
    logs.value = [];
    lastSuccessfulLogServerContext.value = null;
    logLastSuccessAt.value = null;
  }
  loading.value = true;
  error.value = "";
  pendingLogQueryContext.value = requestContext;
  const attemptAt = Date.now();
  pendingLogAttemptAt.value = attemptAt;
  try {
    const nextLogs = normalizeLogRows(await getOpsLogs(buildLogQueryParams(requestContext)));
    if (!logQueryOwner.canCommit(ticket, currentLogServerQueryContext())) {
      return;
    }
    logs.value = nextLogs;
    lastSuccessfulLogServerContext.value = requestContext;
    logLastSuccessAt.value = Date.now();
    lastAttemptedLogServerContext.value = requestContext;
    logLastAttemptAt.value = attemptAt;
    logFailureHttpStatus.value = null;
  } catch (caught) {
    if (!logQueryOwner.canCommit(ticket, currentLogServerQueryContext())) {
      return;
    }
    if (!canPreserveLastGood) {
      logs.value = [];
    }
    error.value = caught instanceof Error ? caught.message : "运行日志加载失败";
    lastAttemptedLogServerContext.value = requestContext;
    logLastAttemptAt.value = attemptAt;
    logFailureHttpStatus.value = caught instanceof ApiRequestError ? caught.httpStatus ?? null : null;
  } finally {
    if (logQueryOwner.isLatest(ticket)) {
      loading.value = false;
      pendingLogQueryContext.value = null;
      pendingLogAttemptAt.value = null;
    }
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
  const requestContext = currentLogVisibleQueryContext();
  const ticket = exceptionLookupOwner.begin(requestContext);
  exceptionLoading.value = true;
  try {
    const root: ExceptionStatsSnapshot = await getExceptionStats();
    if (!exceptionLookupOwner.canCommit(ticket, currentLogVisibleQueryContext())) {
      return;
    }
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
    if (!exceptionLookupOwner.canCommit(ticket, currentLogVisibleQueryContext())) {
      return;
    }
    ElMessage.warning(caught instanceof Error ? caught.message : "最近异常定位失败");
  } finally {
    if (exceptionLookupOwner.isLatest(ticket)) {
      exceptionLoading.value = false;
    }
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
      void loadLogs({ fromTimer: true });
    }, 5000);
  }
}

function applyRouteQuery() {
  const previousServerContext = currentLogServerQueryContext();
  const previousVisibleContext = currentLogVisibleQueryContext();
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
  if (!changed) {
    return { changed: false, serverChanged: false };
  }
  return {
    changed: true,
    serverChanged: !isSameLogServerQueryContext(previousServerContext, currentLogServerQueryContext())
      && !isSameLogVisibleQueryContext(previousVisibleContext, currentLogVisibleQueryContext()) ? true : !isSameLogServerQueryContext(previousServerContext, currentLogServerQueryContext())
  };
}

function currentLogServerQueryContext() {
  return buildLogServerQueryContext({
    level: logLevel.value,
    logger: logLogger.value,
    keyword: logKeyword.value,
    limit: logLimit.value
  });
}

function currentLogVisibleQueryContext() {
  return buildLogVisibleQueryContext({
    level: logLevel.value,
    logger: logLogger.value,
    keyword: logKeyword.value,
    limit: logLimit.value,
    deviceId: logDeviceId.value,
    thread: logThread.value
  });
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
  if (!isViewMounted) return;
  applyRouteQuery();
  await deviceStore.refresh();
  if (!isViewMounted) return;
  await loadLogs();
  if (!isViewMounted) return;
  syncLogTimer();
}

onMounted(() => {
  isViewMounted = true;
  void initializeLogView();
});

onBeforeUnmount(() => {
  isViewMounted = false;
  logQueryOwner.invalidate();
  exceptionLookupOwner.invalidate();
  loading.value = false;
  exceptionLoading.value = false;
  pendingLogQueryContext.value = null;
  pendingLogAttemptAt.value = null;
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = null;
  }
});

watch(() => logAutoRefresh.value, syncLogTimer);
watch(() => [route.query.level, route.query.deviceId, route.query.logger, route.query.thread, route.query.keyword, route.query.limit], () => {
  const routeChange = applyRouteQuery();
  if (routeChange.serverChanged) {
    void loadLogs();
  }
});
</script>

<style scoped src="@/features/log/styles/log-view.css"></style>
