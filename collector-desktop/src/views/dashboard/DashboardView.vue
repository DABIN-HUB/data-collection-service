<template>
  <section class="overview-section dashboard-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>控制台总览</h1>
        <span class="heading-online"><i></i>设备连接 <b>{{ deviceConnectionText }}</b></span>
      </div>
      <div class="heading-actions">
        <button type="button" class="primary" :disabled="dashboardLoading" @click="refreshDashboard">刷新全部</button>
        <button type="button" @click="goToDevice">设备管理</button>
        <button type="button" class="primary" @click="openLocalEditor">新增本地设备</button>
        <span class="heading-note">{{ lastRefreshText }}</span>
      </div>
    </div>

    <div class="dashboard-alerts">
      <el-alert v-if="dashboardError" :title="dashboardError" type="error" :closable="false" />
      <el-alert v-else-if="dashboardPartialWarning" :title="dashboardPartialWarning" type="warning" :closable="false" />
    </div>

    <div class="overview-cards">
      <article v-for="card in overviewCards" :key="card.label" class="card metric-card">
        <small>{{ card.label }}</small>
        <div v-if="card.ring" class="cache-ring" aria-hidden="true"></div>
        <strong>{{ card.value }}</strong>
        <div v-if="card.meta" class="card-meta">
          <span v-for="item in card.meta" :key="String(item[0])">{{ item[0] }} {{ item[1] }}</span>
        </div>
        <div v-else class="card-subtext">{{ card.subtext }}</div>
      </article>
    </div>

    <div class="home-dashboard">
      <div class="home-dashboard-row home-dashboard-primary">
        <section class="home-panel home-panel-large">
          <div class="home-panel-head">
            <div><h2>全局告警最近记录</h2></div>
            <span class="home-panel-badge">{{ recentAlarmBadge }}</span>
          </div>
          <div class="home-event-list">
            <div v-if="recentAlarms.length === 0" class="empty-state compact">{{ recentAlarmEmptyText }}</div>
            <div v-for="(alarm, index) in recentAlarms" :key="alarmListKey(alarm, index)" class="home-event-row" :class="alarmToneClass(alarm)">
              <div class="home-event-main">
                <strong>{{ alarmMessage(alarm) }}</strong>
                <span>{{ alarm.deviceName || alarm.deviceId || '-' }} / {{ alarm.pointName || alarm.pointCode || '-' }}</span>
              </div>
              <div class="home-event-meta">
                <b>{{ alarmLevelText(alarm.level || alarm.alarmType) }}</b>
                <span>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="home-panel">
          <div class="home-panel-head">
            <div><h2>设备异常风险</h2></div>
            <span class="home-panel-badge">{{ riskDeviceBadge }}</span>
          </div>
          <div class="home-risk-list">
            <div v-if="riskDevices.length === 0" class="empty-state compact">{{ riskDeviceEmptyText }}</div>
            <div v-for="device in riskDevices" :key="device.normalizedId" class="home-risk-row" :class="riskToneClass(device)">
              <span class="risk-dot"></span>
              <div>
                <strong>{{ device.displayName || device.normalizedId }}</strong>
                <p>{{ riskDescription(device) }}</p>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="home-dashboard-row home-dashboard-observability">
        <section class="home-panel home-panel-report">
          <div class="home-panel-head">
            <div><h2>数据上报链路拓扑</h2></div>
            <span class="home-panel-badge">{{ reportState }}</span>
          </div>
          <div class="pipeline-steps">
            <div class="topology-flow" aria-label="数据采集与上报拓扑">
              <div class="topology-node" :class="collectorToneClass" :title="collectorDetail">
                <span class="topology-icon">采</span>
                <strong>采集器</strong>
                <span class="topology-status-dots"><i :class="runningToneClass"></i><i :class="collectorToneClass"></i></span>
              </div>
              <span class="topology-connector" aria-hidden="true"></span>
              <div class="topology-node" :class="gatewayToneClass" :title="gatewayDetail">
                <span class="topology-icon">网</span>
                <strong>边缘网关</strong>
                <small>{{ nodeIdentity }}</small>
              </div>
              <span class="topology-connector" aria-hidden="true"></span>
              <div class="topology-storage-stack">
                <div class="topology-storage-pill" title="Redis 缓存状态">
                  <span>Redis 缓存</span><i class="status-dot" :class="cacheToneClass"></i>
                </div>
                <div class="topology-storage-pill" title="TDengine 历史存储状态">
                  <span>TDengine</span><i class="status-dot" :class="storageToneClass"></i>
                </div>
              </div>
              <span class="topology-connector" aria-hidden="true"></span>
              <div class="topology-node" :class="cloudToneClass" :title="reportState">
                <span class="topology-icon">云</span>
                <strong>云平台</strong>
                <small>{{ reportState }}</small>
              </div>
            </div>
          </div>
        </section>

        <section class="home-panel home-panel-runtime">
          <div class="home-panel-head">
            <div><h2>系统资源与线程池</h2></div>
            <span class="home-panel-badge">{{ runtimeState }}</span>
          </div>
          <div class="home-resource-list">
            <div class="resource-dashboard">
              <div class="resource-gauges">
                <div v-for="gauge in resourceGauges" :key="gauge.label" class="resource-gauge">
                  <div class="resource-ring" :class="`is-${gauge.tone}`" :style="{ '--resource-progress': `${gauge.degrees}deg` }"></div>
                  <span>{{ gauge.label }}</span>
                  <strong>{{ gauge.value }}</strong>
                </div>
              </div>
              <div class="resource-runtime-summary" :title="resourceSummary.title">
                <div><span>活跃线程:</span><strong>{{ resourceSummary.activeThreads }} / {{ resourceSummary.maxThreads }}</strong></div>
                <div><span>队列积压:</span><strong :class="{ 'is-warn': resourceSummary.queuedTasks !== '-' && Number(resourceSummary.queuedTasks) > 0 }">{{ resourceSummary.queuedTasks }}</strong></div>
                <div class="resource-load-track"><i :style="{ width: resourceSummary.threadUsage }"></i></div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>

    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocolStore.protocols" @saved="handleLocalSaved" />
  </section>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { getRecentAlarms } from "@/api/data.api";
import { getCacheMetrics, getCloudReportMetrics, getPerformanceDetail, getRuntimeStatus, getStorageMetrics, getSystemResources } from "@/api/monitor.api";
import LocalDeviceEditor from "@/features/device/components/LocalDeviceEditor.vue";
import {
  buildDashboardFatalError,
  buildDashboardInitializeError,
  buildDashboardPartialWarning,
  createDashboardMetricState,
  DASHBOARD_METRIC_LABELS,
  isDashboardMetricStale,
  isDashboardMetricUnavailable,
  runDashboardMetric,
  type DashboardMetricKey,
  type DashboardMetricState
} from "@/features/dashboard/utils/dashboard-metric-state";
import { createDashboardRefreshCycle } from "@/features/dashboard/utils/dashboard-request-lifecycle";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import { useProtocolStore } from "@/stores/protocol.store";
import type { DeviceViewModel } from "@/types/device";
import { normalizeAlarmHistoryRows } from "@/features/alarm/utils/alarm-history-utils";
import { buildAlarmIdentity } from "@/features/alarm/utils/alarm-utils";
import type { AlarmRow, CacheMetricsSnapshot, CloudReportMetricsResponse, ConsoleRuntimeStatusSnapshot, PerformanceStatsSnapshot, StorageMetricsSnapshot, SystemResourceSnapshot } from "@/types/monitor";
import type { LocalDeviceBundle } from "@/features/device/utils/local-device-utils";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const protocolStore = useProtocolStore();
const router = useRouter();

const recentAlarms = ref<AlarmRow[]>([]);
const reportMetrics = ref<CloudReportMetricsResponse | null>(null);
const runtimeStatus = ref<ConsoleRuntimeStatusSnapshot | null>(null);
const systemResource = ref<SystemResourceSnapshot | null>(null);
const cacheMetrics = ref<CacheMetricsSnapshot | null>(null);
const storageMetrics = ref<StorageMetricsSnapshot | null>(null);
const performanceDetail = ref<PerformanceStatsSnapshot | null>(null);
const lastRefresh = ref<Date | null>(null);
const dashboardLoading = ref(false);
const dashboardError = ref("");
const dashboardPartialWarning = ref("");
const localEditorVisible = ref(false);
const editingBundle = ref<LocalDeviceBundle | null>(null);

const dashboardRefreshCycle = createDashboardRefreshCycle();
const dashboardMetricStates = reactive<Record<DashboardMetricKey, DashboardMetricState>>({
  devices: createDashboardMetricState(),
  alarms: createDashboardMetricState(),
  report: createDashboardMetricState(),
  runtime: createDashboardMetricState(),
  systemResource: createDashboardMetricState(),
  cache: createDashboardMetricState(),
  storage: createDashboardMetricState(),
  performance: createDashboardMetricState()
});

const dashboardSourceKeys: DashboardMetricKey[] = ["devices", "alarms", "report", "runtime", "systemResource", "cache", "storage", "performance"];

const nodeIdentity = computed(() => appStore.platform === "browser" ? "本地浏览器" : `Electron/${appStore.platform}`);
const deviceState = computed(() => dashboardMetricStates.devices);
const alarmState = computed(() => dashboardMetricStates.alarms);
const reportMetricState = computed(() => dashboardMetricStates.report);
const runtimeMetricState = computed(() => dashboardMetricStates.runtime);
const systemResourceState = computed(() => dashboardMetricStates.systemResource);
const cacheMetricState = computed(() => dashboardMetricStates.cache);
const storageMetricState = computed(() => dashboardMetricStates.storage);
const performanceMetricState = computed(() => dashboardMetricStates.performance);
const deviceCount = computed(() => deviceStore.devices.length);
const onlineCount = computed(() => deviceStore.onlineCount);
const offlineCount = computed(() => deviceStore.offlineCount + deviceStore.errorCount);
const totalPointCount = computed(() => deviceStore.totalPointCount);
const devicesUnavailable = computed(() => isDashboardMetricUnavailable(deviceState.value));
const devicesStale = computed(() => isDashboardMetricStale(deviceState.value));
const alarmsUnavailable = computed(() => isDashboardMetricUnavailable(alarmState.value));
const alarmsStale = computed(() => isDashboardMetricStale(alarmState.value));
const reportUnavailable = computed(() => isDashboardMetricUnavailable(reportMetricState.value));
const reportStale = computed(() => isDashboardMetricStale(reportMetricState.value));
const runtimeUnavailable = computed(() => isDashboardMetricUnavailable(runtimeMetricState.value));
const runtimeStale = computed(() => isDashboardMetricStale(runtimeMetricState.value));
const systemResourceUnavailable = computed(() => isDashboardMetricUnavailable(systemResourceState.value));
const systemResourceStale = computed(() => isDashboardMetricStale(systemResourceState.value));
const cacheStale = computed(() => isDashboardMetricStale(cacheMetricState.value));
const cacheUnavailable = computed(() => isDashboardMetricUnavailable(cacheMetricState.value));
const storageStale = computed(() => isDashboardMetricStale(storageMetricState.value));
const storageUnavailable = computed(() => isDashboardMetricUnavailable(storageMetricState.value));
const performanceStale = computed(() => isDashboardMetricStale(performanceMetricState.value));
const performanceUnavailable = computed(() => isDashboardMetricUnavailable(performanceMetricState.value));
const riskDevices = computed(() => devicesUnavailable.value ? [] : deviceStore.devices.filter((device) => ["ERROR", "OFFLINE"].includes(String(device.status || "").toUpperCase()) || Boolean(device.lastError)).slice(0, 6));
const deviceConnectionText = computed(() => devicesUnavailable.value ? "-" : `${onlineCount.value}/${deviceCount.value}`);
const reportState = computed(() => {
  if (reportUnavailable.value) return "数据不可用";
  if (reportStale.value) return "刷新失败 · 上次数据";
  if (reportMetricState.value.status === "loading" && reportMetrics.value) return "刷新中 · 上次数据";
  return reportMetrics.value?.statusText || reportMetrics.value?.status || (reportMetrics.value ? "已加载" : "未知");
});
const runtimeState = computed(() => {
  if (systemResourceUnavailable.value) return "系统资源不可用";
  if (systemResourceStale.value) return "系统资源刷新失败 · 上次数据";
  if (runtimeUnavailable.value) return "运行状态不可用";
  if (runtimeStale.value) return "运行状态刷新失败 · 上次数据";
  if (performanceStale.value || performanceUnavailable.value) return "性能详情刷新失败";
  return systemResource.value || runtimeStatus.value ? "资源已加载" : "资源未知";
});
const lastRefreshText = computed(() => {
  if (!lastRefresh.value) {
    return "等待刷新";
  }
  const suffix = dashboardPartialWarning.value ? " · 部分数据降级" : "";
  return `刷新于 ${lastRefresh.value.toLocaleTimeString()}${suffix}`;
});
const recentAlarmBadge = computed(() => {
  if (alarmsUnavailable.value) return "数据不可用";
  if (alarmsStale.value) return `${recentAlarms.value.length} 条 · 上次数据`;
  return `${recentAlarms.value.length} 条`;
});
const recentAlarmEmptyText = computed(() => alarmsUnavailable.value ? "最近告警数据暂不可用" : "暂无告警记录");
const riskDeviceBadge = computed(() => {
  if (devicesUnavailable.value) return "数据不可用";
  if (devicesStale.value) return `${riskDevices.value.length} 台风险 · 上次数据`;
  return riskDevices.value.length ? `${riskDevices.value.length} 台风险` : "正常";
});
const riskDeviceEmptyText = computed(() => devicesUnavailable.value ? "设备状态暂不可用" : (devicesStale.value ? "当前没有明显设备风险（上次数据）" : "当前没有明显设备风险"));

const overviewCards = computed(() => {
  const runtimeCacheRatio = ratioFrom(runtimeStatus.value?.cache?.totalHitRate ?? null);
  const cacheRatio = ratioFrom(cacheMetrics.value?.totalHitRate ?? null) ?? runtimeCacheRatio;
  const devicesMeta = devicesUnavailable.value
    ? [["状态", "设备数据不可用"]]
    : [["已连接", onlineCount.value], ["未连接", offlineCount.value], ...(devicesStale.value ? [["状态", "显示上次成功数据"]] : [])];
  const pointMeta = devicesUnavailable.value
    ? [["状态", "设备数据不可用"]]
    : [["连接配置", deviceCount.value], ["上报属性", reportMetrics.value?.configured?.reportFieldPointCount ?? reportMetrics.value?.configured?.reportablePointCount ?? "-"], ...(devicesStale.value ? [["状态", "显示上次成功数据"]] : [])];
  const runningMeta = devicesUnavailable.value
    ? [["状态", "设备数据不可用"]]
    : [["缺失连接", offlineCount.value], ["健康连接", onlineCount.value], ...(devicesStale.value ? [["状态", "显示上次成功数据"]] : [])];
  const alarmSubtext = alarmsUnavailable.value
    ? "告警数据暂不可用"
    : (alarmsStale.value ? "刷新失败，显示上次成功数据" : (recentAlarms.value.length ? "最近告警记录" : "最近 24 小时没有告警历史记录"));
  const cacheSubtext = cacheMetricState.value.status === "error" && runtimeCacheRatio !== null && !cacheMetrics.value
    ? "缓存独立指标不可用，使用运行状态快照"
    : (cacheUnavailable.value && cacheRatio === null ? "缓存指标不可用" : (cacheStale.value ? "刷新失败，显示上次成功数据" : "缓存访问指标"));
  const reportSubtext = reportUnavailable.value
    ? "上报监控数据不可用"
    : (reportStale.value ? "刷新失败，显示上次成功数据" : "上报状态已加载");
  return [
    { label: "采集器总数", value: devicesUnavailable.value ? "-" : deviceCount.value, meta: devicesMeta },
    { label: "点位总数", value: devicesUnavailable.value ? "-" : totalPointCount.value, meta: pointMeta },
    { label: "全局告警", value: alarmsUnavailable.value ? "-" : recentAlarms.value.length, subtext: alarmSubtext },
    { label: "运行设备", value: devicesUnavailable.value ? "-" : onlineCount.value, meta: runningMeta },
    { label: "缓存命中率", value: cacheRatio === null ? "-" : percentText(cacheRatio), ring: true, subtext: cacheSubtext },
    { label: "云上报链路", value: reportState.value, subtext: reportSubtext }
  ];
});

const collectorToneClass = computed(() => {
  if (devicesUnavailable.value) return "is-muted";
  if (devicesStale.value) return "is-warn";
  return riskDevices.value.some((device) => String(device.status || "").toUpperCase() === "ERROR") ? "is-error" : (riskDevices.value.length ? "is-warn" : (deviceStore.devices.length ? "is-ok" : "is-muted"));
});
const runningToneClass = computed(() => devicesUnavailable.value ? "is-muted" : (devicesStale.value ? "is-warn" : (onlineCount.value > 0 ? "is-ok" : "is-muted")));
const gatewayToneClass = computed(() => runtimeUnavailable.value ? "is-muted" : (runtimeStale.value ? "is-warn" : (runtimeStatus.value ? "is-ok" : "is-muted")));
const cacheToneClass = computed(() => {
  const hasRuntimeFallback = ratioFrom(runtimeStatus.value?.cache?.totalHitRate ?? null) !== null;
  if (cacheStale.value || (cacheMetricState.value.status === "error" && hasRuntimeFallback)) return "is-warn";
  if (cacheUnavailable.value && !hasRuntimeFallback) return "is-muted";
  return ratioFrom(cacheMetrics.value?.totalHitRate ?? runtimeStatus.value?.cache?.totalHitRate ?? null) === null ? "is-muted" : "is-ok";
});
const storageToneClass = computed(() => storageUnavailable.value ? "is-muted" : (storageStale.value ? "is-warn" : (storageMetrics.value ? "is-ok" : "is-muted")));
const cloudToneClass = computed(() => {
  if (reportUnavailable.value) return "is-muted";
  if (reportStale.value) return "is-warn";
  const status = String(reportMetrics.value?.status || "UNKNOWN").toUpperCase();
  if (["ERROR", "FAILED", "DOWN"].includes(status)) return "is-error";
  if (["WARN", "WARNING", "DEGRADED"].includes(status)) return "is-warn";
  return reportMetrics.value ? "is-ok" : "is-muted";
});
const collectorDetail = computed(() => devicesUnavailable.value ? "设备数据暂不可用" : `${onlineCount.value}/${deviceCount.value} 已连接${devicesStale.value ? "，显示上次成功数据" : ""}`);
const gatewayDetail = computed(() => runtimeUnavailable.value ? "运行状态不可用" : (runtimeStale.value ? "运行状态刷新失败，显示上次成功数据" : (runtimeStatus.value ? "处理指标已加载" : "处理性能数据不可用")));
const resourceGauges = computed(() => {
  const resource = systemResourceUnavailable.value ? null : systemResource.value;
  const cpu = ratioFrom(resource?.systemCpuLoad ?? resource?.processCpuLoad ?? null);
  const totalMemory = optionalNumber(resource?.totalPhysicalMemorySize ?? null);
  const freeMemory = optionalNumber(resource?.freePhysicalMemorySize ?? null);
  const memory = totalMemory !== null && freeMemory !== null && totalMemory > 0 ? 1 - freeMemory / totalMemory : null;
  const heapUsed = optionalNumber(resource?.heapUsed ?? null);
  const heapMax = optionalNumber(resource?.heapMax ?? null);
  const heap = heapUsed !== null && heapMax !== null && heapMax > 0 ? heapUsed / heapMax : null;
  return [
    { label: "CPU 使用率", tone: cpu === null ? "muted" : "blue", value: percentText(cpu), degrees: ratioDegrees(cpu) },
    { label: "内存使用率", tone: memory === null ? "muted" : "orange", value: percentText(memory), degrees: ratioDegrees(memory) },
    { label: "JVM 堆内存", tone: heap === null ? "muted" : "green", value: percentText(heap), degrees: ratioDegrees(heap) }
  ];
});
const resourceSummary = computed(() => {
  const resource = systemResourceUnavailable.value ? null : systemResource.value;
  const pools = resource?.threadPools || {};
  let activeThreads = 0;
  let maxThreads = 0;
  let queuedTasks = 0;
  let rejectedTasks = 0;
  for (const pool of Object.values(pools)) {
    activeThreads += numberValue(pool?.activeCount, 0);
    maxThreads += numberValue(pool?.maxPoolSize, 0);
    queuedTasks += numberValue(pool?.queueSize, 0);
    rejectedTasks += numberValue(pool?.rejectedCount, 0);
  }
  const executor = reportMetrics.value?.executor;
  if (maxThreads === 0 && executor) {
    activeThreads = numberValue(executor.activeCount, 0);
    maxThreads = numberValue(executor.maxPoolSize, 0);
    queuedTasks = numberValue(executor.queueSize, 0);
    rejectedTasks = numberValue(executor.rejectedCount, 0);
  }
  const perf = performanceUnavailable.value ? null : performanceDetail.value;
  if (maxThreads === 0 && perf) {
    rejectedTasks = numberValue(perf.batchDispatchRejectedCount, 0) + numberValue(perf.collectRejectedCount, 0) + numberValue(perf.processRejectedCount, 0);
  }
  const usage = maxThreads > 0 ? Math.max(0, Math.min(100, Math.round((activeThreads / maxThreads) * 100))) : 0;
  return {
    activeThreads: maxThreads > 0 ? String(activeThreads) : "-",
    maxThreads: maxThreads > 0 ? String(maxThreads) : "-",
    queuedTasks: maxThreads > 0 ? String(queuedTasks) : "-",
    threadUsage: `${usage}%`,
    title: maxThreads > 0 ? `累计拒绝 ${rejectedTasks || "-"} 次，JVM 线程 ${resource?.threadCount ?? "-"} 个` : "运行资源数据暂不可用"
  };
});

onMounted(() => {
  void loadDashboard();
});

onBeforeUnmount(() => {
  dashboardRefreshCycle.invalidate();
  dashboardLoading.value = false;
});

async function refreshDashboard() {
  await loadDashboard();
}

async function loadDashboard() {
  const ticket = dashboardRefreshCycle.begin();
  dashboardLoading.value = true;
  dashboardError.value = "";
  try {
    await appStore.initialize();
    if (!dashboardRefreshCycle.isLatest(ticket)) {
      return;
    }
    const results = await Promise.all(dashboardSourceKeys.map((key) => runDashboardMetric({
      key,
      state: dashboardMetricStates[key],
      loader: () => loadDashboardMetric(key),
      commit: (value) => commitDashboardMetric(key, value),
      isLatest: () => dashboardRefreshCycle.isLatest(ticket)
    })));
    if (!dashboardRefreshCycle.isLatest(ticket)) {
      return;
    }
    const successKeys = results.filter((result) => result.status === "success").map((result) => result.key);
    const failedKeys = results.filter((result) => result.status === "error").map((result) => result.key);
    if (successKeys.length > 0) {
      lastRefresh.value = new Date();
    }
    if (failedKeys.length === dashboardSourceKeys.length) {
      dashboardError.value = buildDashboardFatalError(failedKeys.map((key) => sourceLabel(key)).join("、"));
      dashboardPartialWarning.value = "";
      return;
    }
    dashboardError.value = "";
    dashboardPartialWarning.value = buildDashboardPartialWarning(failedKeys);
  } catch (error) {
    if (dashboardRefreshCycle.isLatest(ticket)) {
      dashboardError.value = buildDashboardInitializeError(error);
      dashboardPartialWarning.value = "";
    }
  } finally {
    if (dashboardRefreshCycle.isLatest(ticket)) {
      dashboardLoading.value = false;
    }
  }
}

async function loadDashboardMetric(key: DashboardMetricKey): Promise<unknown> {
  switch (key) {
    case "devices":
      await deviceStore.refresh();
      if (deviceStore.error) {
        throw new Error(deviceStore.error);
      }
      return deviceStore.devices;
    case "alarms":
      return normalizeAlarmHistoryRows(await getRecentAlarms({ limit: 8 })).slice(0, 8);
    case "report":
      return getCloudReportMetrics();
    case "runtime":
      return getRuntimeStatus();
    case "systemResource":
      return getSystemResources();
    case "cache":
      return getCacheMetrics();
    case "storage":
      return getStorageMetrics();
    case "performance":
      return getPerformanceDetail();
    default:
      return null;
  }
}

function commitDashboardMetric(key: DashboardMetricKey, value: unknown): void {
  switch (key) {
    case "alarms":
      recentAlarms.value = value as AlarmRow[];
      break;
    case "report":
      reportMetrics.value = value as CloudReportMetricsResponse;
      break;
    case "runtime":
      runtimeStatus.value = value as ConsoleRuntimeStatusSnapshot;
      break;
    case "systemResource":
      systemResource.value = value as SystemResourceSnapshot;
      break;
    case "cache":
      cacheMetrics.value = value as CacheMetricsSnapshot;
      break;
    case "storage":
      storageMetrics.value = value as StorageMetricsSnapshot;
      break;
    case "performance":
      performanceDetail.value = value as PerformanceStatsSnapshot;
      break;
    case "devices":
      break;
    default:
      break;
  }
}

function sourceLabel(key: DashboardMetricKey): string {
  return DASHBOARD_METRIC_LABELS[key];
}

async function openLocalEditor() {
  if (!protocolStore.protocols.length) {
    await protocolStore.refresh();
  }
  if (protocolStore.error) {
    ElMessage.error(protocolStore.error);
    return;
  }
  editingBundle.value = null;
  localEditorVisible.value = true;
}

async function handleLocalSaved() {
  localEditorVisible.value = false;
  await loadDashboard();
}

function goToDevice() {
  router.push("/device").catch(() => undefined);
}

function alarmMessage(alarm: AlarmRow): string {
  return String(alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || "告警触发");
}

function alarmListKey(alarm: AlarmRow, index: number): string {
  return buildAlarmIdentity(alarm) || String(alarm.timestamp || alarm.occurTime || alarm.message || alarm.content || index);
}

function alarmLevelText(level: unknown): string {
  switch (String(level || "").toUpperCase()) {
    case "CRITICAL":
    case "FATAL":
    case "HIGH":
      return "严重";
    case "ERROR":
      return "错误";
    case "WARN":
    case "WARNING":
    case "MEDIUM":
      return "警告";
    case "INFO":
      return "信息";
    default:
      return String(level || "未知");
  }
}

function alarmToneClass(alarm: AlarmRow): string {
  const level = String(alarm.level || alarm.alarmType || "").toUpperCase();
  if (["CRITICAL", "FATAL", "ERROR", "HIGH", "严重"].includes(level)) return "is-error";
  if (["WARN", "WARNING", "MEDIUM", "警告"].includes(level)) return "is-warn";
  return "is-info";
}

function riskToneClass(device: DeviceViewModel): string {
  const status = String(device.status || "").toUpperCase();
  if (status === "ERROR" || Boolean(device.lastError)) return "is-error";
  if (status === "OFFLINE") return "is-warn";
  return "is-warn";
}

function riskDescription(device: DeviceViewModel): string {
  const status = String(device.status || "UNKNOWN");
  if (device.lastError) return `连接异常：${device.lastError}`;
  if (status.toUpperCase() === "OFFLINE") return "当前设备离线，配置存在但运行连接未建立";
  if (status.toUpperCase() === "ERROR") return "当前设备处于异常状态，请检查连接和协议配置";
  return `当前状态 ${status}`;
}

function numberValue(value: unknown, fallback = 0): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function optionalNumber(value: unknown): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function ratioFrom(value: unknown): number | null {
  const parsed = optionalNumber(value);
  if (parsed === null) return null;
  const normalized = parsed > 1 && parsed <= 100 ? parsed / 100 : parsed;
  return Math.max(0, Math.min(1, normalized));
}

function ratioDegrees(value: number | null): number {
  return value === null ? 0 : Math.round(Math.max(0, Math.min(1, value)) * 360);
}

function percentText(value: number | null): string {
  return value === null ? "-" : `${Math.round(value * 100)}%`;
}

function formatTime(value: unknown): string {
  if (!value) return "-";
  const date = typeof value === "number" ? new Date(value) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}
</script>

<style scoped>
.dashboard-view {
  width: 100%;
  min-height: 0;
  margin: 0;
  padding: 0;
  flex: 1;
  overflow-y: auto;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.heading-note {
  color: var(--exact-muted);
  font-size: 11px;
}

.dashboard-alerts {
  display: grid;
  padding: 12px 24px 0;
  gap: 8px;
}

.overview-cards {
  display: grid;
  margin: 0;
  padding: 24px 24px 0;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 15px;
}

.overview-cards .card {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 146px;
  padding: 16px;
  flex-direction: column;
  justify-content: flex-start;
  overflow: hidden;
  border: 1px solid var(--exact-border);
  border-radius: 12px;
  background: var(--exact-panel);
  box-shadow: none;
}

.overview-cards .card::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  width: auto;
  height: 4px;
  border-radius: 0;
  background: var(--exact-blue);
  content: "";
}

.overview-cards .card::after {
  display: none !important;
  content: none !important;
}

.overview-cards .card:nth-child(1)::before { background: var(--exact-cyan); }
.overview-cards .card:nth-child(2)::before { background: #f8fafc; }
.overview-cards .card:nth-child(3)::before { background: var(--exact-red); }
.overview-cards .card:nth-child(4)::before { background: #22c55e; }
.overview-cards .card:nth-child(5)::before { background: var(--exact-blue); }
.overview-cards .card:nth-child(6)::before { background: #34d399; }

.overview-cards .card span {
  color: var(--exact-muted);
  font-size: 12px;
}

.overview-cards .card strong {
  display: block;
  margin: 12px 0 8px;
  color: #fff;
  font-size: 25px;
  font-weight: 700;
  line-height: 1;
}

.overview-cards .card small {
  margin-top: auto;
  color: var(--exact-dim);
  font-size: 10px;
  line-height: 1.4;
}

.overview-cards .card:nth-child(1) strong { color: var(--exact-cyan); }
.overview-cards .card:nth-child(3) strong { color: var(--exact-red); }
.overview-cards .card:nth-child(4) strong { color: #22c55e; }
.overview-cards .card:nth-child(6) strong { color: #34d399; }

.overview-cards .card > small {
  margin: 1px 0 12px;
  color: var(--exact-muted);
  font-size: 12px;
  font-weight: 400;
}

.overview-cards .card .card-subtext,
.overview-cards .card .card-meta {
  margin-top: auto;
  color: var(--exact-dim);
  font-size: 10px;
  line-height: 1.4;
}

.overview-cards .card .card-meta {
  display: grid;
  gap: 3px;
}

.overview-cards .card .card-meta span {
  color: var(--exact-dim);
  font-size: 10px;
}

.overview-cards .cache-ring {
  width: 64px;
  height: 64px;
  margin: -2px auto 2px;
  border: 6px solid #263955;
  border-top-color: var(--exact-blue);
  border-right-color: var(--exact-blue);
  border-bottom-color: var(--exact-blue);
  border-radius: 50%;
  transform: rotate(34deg);
}

.overview-cards .card:nth-child(5) > small {
  margin-bottom: 3px;
}

.overview-cards .card:nth-child(5) > strong {
  margin: 0 auto;
  color: #60a5fa;
  font-size: 13px;
}

.overview-cards .card:nth-child(5) .card-subtext {
  display: block;
}

.home-dashboard {
  display: flex;
  margin-top: 0;
  padding: 24px;
  flex-direction: column;
  gap: 24px;
}

.home-dashboard-row {
  display: grid;
  gap: 24px;
}

.home-dashboard-primary,
.home-dashboard-observability {
  grid-template-columns: minmax(0, 6fr) minmax(0, 4fr);
}

.home-panel {
  display: block;
  min-width: 0;
  min-height: 0;
  padding: 0;
  overflow: hidden;
  color: #e2e8f0;
  border: 1px solid var(--exact-border);
  border-radius: 12px;
  background: var(--exact-panel);
  box-shadow: none;
}

.home-dashboard-primary .home-panel {
  min-height: 337px;
}

.home-dashboard-observability .home-panel {
  height: 220px;
}

.home-panel-head {
  display: flex;
  min-height: 56px;
  padding: 0 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--exact-border);
  background: transparent;
}

.home-panel-head h2 {
  margin: 0;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
}

.home-panel-badge {
  padding: 0;
  border: 0;
  background: transparent;
  color: #60a5fa;
  font-size: 11px;
  font-weight: 400;
}

.home-event-list,
.home-risk-list {
  display: flex;
  max-height: 277px;
  padding: 16px;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.home-event-row {
  position: relative;
  min-height: 66px;
  margin-left: 48px;
  padding: 12px;
  border: 1px solid rgba(59, 130, 246, 0.28);
  border-radius: 8px;
  background: var(--exact-panel-soft);
}

.home-event-row::before {
  position: absolute;
  top: 25px;
  left: -33px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 9px var(--exact-panel);
  content: "";
}

.home-event-row::after {
  position: absolute;
  top: 29px;
  bottom: -32px;
  left: -29px;
  width: 1px;
  background: var(--exact-border);
  content: "";
}

.home-event-row:last-child::after {
  display: none;
}

.home-event-row.is-error {
  border-color: rgba(239, 68, 68, 0.28);
}

.home-event-row.is-error::before {
  background: var(--exact-red);
}

.home-event-row.is-warn {
  border-color: rgba(249, 115, 22, 0.3);
}

.home-event-row.is-warn::before {
  background: var(--exact-orange);
}

.home-event-main,
.home-event-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.home-event-main {
  flex-direction: column;
}

.home-event-main strong {
  color: #93c5fd;
  font-size: 13px;
}

.home-event-main span,
.home-event-meta span {
  color: var(--exact-muted);
  font-size: 11px;
}

.home-event-meta {
  position: absolute;
  top: 12px;
  right: 12px;
}

.home-event-meta b {
  display: none;
}

.home-risk-row {
  position: relative;
  display: block;
  min-height: 67px;
  padding: 14px 82px 12px 13px;
  border: 0;
  border-left: 4px solid var(--exact-orange);
  border-radius: 5px;
  background: var(--exact-panel-soft);
}

.home-risk-row.is-error {
  border-left-color: var(--exact-red);
}

.home-risk-row.is-warn {
  border-left-color: var(--exact-orange);
}

.home-risk-row .risk-dot {
  display: none;
}

.home-risk-row strong {
  display: block;
  overflow: hidden;
  color: #fff;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-risk-row p {
  margin: 5px 0 0;
  color: var(--exact-dim);
  font-size: 11px;
}

.pipeline-steps {
  display: block;
  height: calc(100% - 56px);
  padding: 18px 24px;
  overflow: hidden;
}

.topology-flow {
  display: flex;
  min-width: 560px;
  height: 128px;
  align-items: center;
  justify-content: space-between;
}

.topology-node {
  display: flex;
  width: 88px;
  min-width: 88px;
  min-height: 66px;
  padding: 7px 8px 6px;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  border: 2px solid #2d4a7a;
  border-radius: 8px;
  background: var(--exact-panel);
  box-shadow: none;
  text-align: center;
}

.topology-node.is-ok { border-color: rgba(34, 197, 94, 0.65); }
.topology-node.is-warn { border-color: var(--exact-orange); }
.topology-node.is-error { border-color: var(--exact-red); }
.topology-node.is-gateway { border-color: var(--exact-blue); }

.topology-icon {
  display: grid;
  width: 22px;
  height: 22px;
  margin-bottom: 3px;
  place-items: center;
  color: var(--exact-cyan);
  border-radius: 4px;
  background: transparent;
  font-size: 12px;
  font-weight: 700;
}

.topology-node strong {
  display: block;
  color: #fff;
  font-size: 11px;
}

.topology-node small {
  display: block;
  max-width: 100%;
  margin-top: 2px;
  overflow: hidden;
  color: var(--exact-dim);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topology-storage-stack {
  display: grid;
  min-width: 130px;
  gap: 14px;
}

.topology-storage-pill {
  display: flex;
  min-height: 28px;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--exact-border);
  border-radius: 5px;
  background: var(--exact-panel-soft);
  color: #fff;
  font-size: 10px;
  white-space: nowrap;
}

.topology-storage-pill .status-dot {
  width: 8px;
  height: 8px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--exact-dim);
}

.topology-storage-pill .status-dot.is-ok,
.topology-status-dots i.is-ok { background: var(--exact-green); }
.topology-storage-pill .status-dot.is-warn,
.topology-status-dots i.is-warn { background: var(--exact-orange); }
.topology-storage-pill .status-dot.is-error,
.topology-status-dots i.is-error { background: var(--exact-red); }

.topology-status-dots {
  display: flex;
  margin-top: 5px;
  gap: 4px;
}

.topology-status-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--exact-dim);
}

.topology-connector {
  display: block;
  min-width: 28px;
  height: 1px;
  flex: 1 1 54px;
  background: var(--exact-border);
}

.home-resource-list {
  display: block;
  max-height: none;
  padding: 13px 16px 12px;
  overflow: hidden;
}

.resource-dashboard {
  display: grid;
  height: 134px;
  grid-template-columns: minmax(270px, 1fr) minmax(150px, 0.55fr);
  align-items: center;
  gap: 16px;
}

.resource-gauges {
  display: grid;
  grid-template-columns: repeat(3, minmax(74px, 1fr));
  gap: 8px;
}

.resource-gauge {
  display: grid;
  place-items: center;
  gap: 5px;
}

.resource-ring {
  --resource-color: #22d3ee;
  position: relative;
  display: grid;
  width: 68px;
  height: 68px;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(var(--resource-color) var(--resource-progress), #233a5c var(--resource-progress));
}

.resource-ring::after {
  position: absolute;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--exact-panel);
  content: "";
}

.resource-ring.is-blue { --resource-color: #3b82f6; }
.resource-ring.is-orange { --resource-color: #f59e0b; }
.resource-ring.is-green { --resource-color: #22c55e; }
.resource-ring.is-muted { --resource-color: #475569; }

.resource-gauge > span {
  color: var(--exact-muted);
  font-size: 10px;
}

.resource-gauge > strong {
  color: var(--resource-color, #60a5fa);
  font-size: 13px;
}

.resource-gauge:nth-child(1) > strong { color: #60a5fa; }
.resource-gauge:nth-child(2) > strong { color: #fb923c; }
.resource-gauge:nth-child(3) > strong { color: #4ade80; }

.resource-runtime-summary {
  display: grid;
  min-height: 104px;
  padding-left: 28px;
  align-content: center;
  gap: 12px;
  border-left: 1px solid var(--exact-border);
}

.resource-runtime-summary > div:not(.resource-load-track) {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 10px;
}

.resource-runtime-summary span {
  color: var(--exact-dim);
}

.resource-runtime-summary strong {
  color: #fff;
  font-size: 10px;
}

.resource-runtime-summary strong.is-warn {
  color: #f87171;
}

.resource-load-track {
  width: 80px;
  height: 4px;
  overflow: hidden;
  border-radius: 99px;
  background: #334155;
}

.resource-load-track i {
  display: block;
  height: 100%;
  background: var(--exact-blue);
}

@media (max-width: 1440px) {
  .overview-cards {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 1100px) {
  .overview-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .home-dashboard-primary,
  .home-dashboard-observability {
    grid-template-columns: 1fr;
  }
}
</style>
