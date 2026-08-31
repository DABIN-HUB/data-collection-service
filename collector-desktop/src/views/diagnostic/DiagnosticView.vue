<template>
  <section class="exact-page diagnostic-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>系统实时状态诊断</h1>
        <span class="heading-online"><i></i>{{ loading ? '诊断中' : '运行数据' }}</span>
      </div>
      <div class="heading-actions">
        <span class="diagnostic-refresh-time">{{ lastRefreshText }}</span>
        <button type="button" class="primary" :disabled="loading" @click="runDiagnostic">{{ loading ? '诊断中' : '运行完整诊断' }}</button>
        <button type="button" :disabled="exporting" @click="downloadDiagnosticPackage">{{ exporting ? '导出中' : '导出诊断包' }}</button>
      </div>
    </div>

    <div class="exact-page-body">
      <p v-if="error" class="diagnostic-message is-error">{{ error }}</p>
      <p v-else-if="partialWarning" class="diagnostic-message">{{ partialWarning }}</p>

      <section class="exact-surface diagnostic-summary-surface">
        <div class="exact-surface-head"><h2>诊断摘要</h2><span>{{ deviceStore.devices.length }} 台设备，{{ deviceStore.totalPointCount }} 个点位</span></div>
        <div class="exact-diagnostic-cards">
          <div v-for="item in diagnosticCards" :key="item.label" class="exact-diagnostic-card"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
      </section>

      <section class="exact-table-card diagnostic-rows-table">
        <div class="exact-table-title"><h2>诊断项列表</h2><span>{{ diagnosticRows.length }} 项</span></div>
        <table>
          <thead><tr><th>诊断项</th><th>状态</th><th>当前值</th><th>处理建议</th></tr></thead>
          <tbody>
            <tr v-for="row in diagnosticRows" :key="row.name"><td>{{ row.name }}</td><td><span class="status-badge" :class="row.tone">{{ row.status }}</span></td><td>{{ row.current }}</td><td>{{ row.suggestion }}</td></tr>
          </tbody>
        </table>
      </section>

      <DiagnosticDetailPanel
        :cache-metrics="cacheMetrics"
        :device-metrics="deviceConnectionMetrics"
        :performance-metrics="performanceDetail"
        :exception-stats="exceptionStats"
        :storage-metrics="storageMetrics"
      />

      <DeviceRuntimePanel
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        @select-device="selectDevice"
      />

      <details class="exact-json-panel" open>
        <summary>查看原始诊断 JSON</summary>
        <pre class="json-view">{{ prettyJson(diagnosticRaw) }}</pre>
      </details>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { getConfigSummary } from "@/api/config.api";
import { getRecentAlarms } from "@/api/data.api";
import {
  getCacheMetrics,
  getCloudReportMetrics,
  getCollectorPerformance,
  getDeviceConnectionMetrics,
  getExceptionStats,
  getPerformanceDetail,
  getRuntimeStatus,
  getStorageMetrics,
  getSystemResources
} from "@/api/monitor.api";
import { getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import DiagnosticDetailPanel from "@/features/diagnostic/components/DiagnosticDetailPanel.vue";
import DeviceRuntimePanel from "@/features/diagnostic/components/DeviceRuntimePanel.vue";
import {
  buildDiagnosticCards,
  buildDiagnosticRaw,
  buildDiagnosticRows,
  buildDiagnosticRuntimeSummary,
  buildResourceSummary,
  hasDiagnosticData
} from "@/features/diagnostic/utils/diagnostic-utils";
import { normalizeAlarmHistoryRows } from "@/features/alarm/utils/alarm-history-utils";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { AlarmRow, LogRow } from "@/types/monitor";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();

const runtimeStatus = ref<unknown>({});
const systemResource = ref<unknown>({});
const reportMetrics = ref<unknown>({});
const configSummary = ref<unknown>({});
const cacheMetrics = ref<unknown>({});
const deviceConnectionMetrics = ref<unknown>({});
const collectorPerformance = ref<unknown>({});
const exceptionStats = ref<unknown>({});
const storageMetrics = ref<unknown>({});
const performanceDetail = ref<unknown>({});
const diagnosticRaw = ref<unknown>({});
const loading = ref(false);
const exporting = ref(false);
const error = ref("");
const partialWarning = ref("");
const lastRefresh = ref<Date | null>(null);

type DiagnosticSnapshotKey = "runtimeStatus" | "systemResource" | "reportMetrics" | "configSummary" | "cacheMetrics" | "deviceConnectionMetrics" | "collectorPerformance" | "exceptionStats" | "storageMetrics" | "performanceDetail";

const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const resourceSummary = computed(() => buildResourceSummary({
  systemResource: systemResource.value,
  reportMetrics: reportMetrics.value,
  performanceDetail: performanceDetail.value
}));
const diagnosticCards = computed(() => buildDiagnosticCards({
  systemResource: systemResource.value,
  configSummary: configSummary.value,
  deviceConnectionMetrics: deviceConnectionMetrics.value,
  cacheMetrics: cacheMetrics.value,
  runtimeStatus: runtimeStatus.value,
  exceptionStats: exceptionStats.value,
  devices: deviceStore.devices,
  onlineCount: deviceStore.onlineCount,
  totalPointCount: deviceStore.totalPointCount
}));
const diagnosticRows = computed(() => buildDiagnosticRows({
  appInitialized: appStore.initialized,
  systemStatusText: systemStatusText.value,
  resourceSummary: resourceSummary.value,
  runtimeStatus: runtimeStatus.value,
  cacheMetrics: cacheMetrics.value,
  deviceConnectionMetrics: deviceConnectionMetrics.value,
  performanceDetail: performanceDetail.value,
  storageMetrics: storageMetrics.value,
  exceptionStats: exceptionStats.value,
  reportMetrics: reportMetrics.value,
  devices: deviceStore.devices,
  onlineCount: deviceStore.onlineCount
}));
const lastRefreshText = computed(() => lastRefresh.value ? `刷新时间 ${lastRefresh.value.toLocaleTimeString()}` : "尚未刷新");

async function loadDiagnostic() {
  loading.value = true;
  error.value = "";
  partialWarning.value = "";
  const failures: string[] = [];
  try {
    applyRouteDeviceId();
    try {
      await appStore.initialize();
    } catch {
      failures.push("应用服务");
    }

    const requests: Array<{ key: DiagnosticSnapshotKey; label: string; run: () => Promise<unknown> }> = [
      { key: "runtimeStatus", label: "运行状态", run: getRuntimeStatus },
      { key: "systemResource", label: "系统资源", run: getSystemResources },
      { key: "reportMetrics", label: "云端上报", run: getCloudReportMetrics },
      { key: "configSummary", label: "配置摘要", run: getConfigSummary },
      { key: "cacheMetrics", label: "缓存服务", run: getCacheMetrics },
      { key: "deviceConnectionMetrics", label: "设备连接", run: getDeviceConnectionMetrics },
      { key: "collectorPerformance", label: "采集性能", run: getCollectorPerformance },
      { key: "exceptionStats", label: "异常统计", run: getExceptionStats },
      { key: "storageMetrics", label: "历史存储", run: getStorageMetrics },
      { key: "performanceDetail", label: "性能详情", run: getPerformanceDetail }
    ];
    const [deviceResult, ...metricResults] = await Promise.allSettled([deviceStore.refresh(), ...requests.map((request) => request.run())]);

    if (deviceResult.status === "rejected" || deviceStore.error) {
      failures.push("设备列表");
    }
    metricResults.forEach((result, index) => {
      const request = requests[index];
      if (result.status === "fulfilled") {
        applyDiagnosticValue(request.key, result.value);
        return;
      }
      failures.push(request.label);
    });
    applyRouteDeviceId();
    diagnosticRaw.value = buildCurrentDiagnosticRaw();
    lastRefresh.value = new Date();
    if (failures.length) {
      partialWarning.value = `部分诊断数据不可用：${Array.from(new Set(failures)).join("、")}`;
    }
    if (metricResults.every((result) => result.status === "rejected") && deviceStore.error) {
      error.value = "无法连接采集服务，请检查服务地址和后端是否已启动";
    }
  } finally {
    loading.value = false;
  }
}

async function runDiagnostic() {
  await loadDiagnostic();
}

async function downloadDiagnosticPackage() {
  exporting.value = true;
  try {
    const payload = {
      generatedAt: new Date().toISOString(),
      selectedDeviceId: deviceStore.selectedDeviceId,
      selectedDevice: deviceStore.selectedDevice || null,
      overview: diagnosticRaw.value,
      alarms: await loadDiagnosticAlarmSample(),
      logs: await loadDiagnosticLogSample(),
      runtimeSummary: buildDiagnosticRuntimeSummary({
        devices: deviceStore.devices,
        onlineCount: deviceStore.onlineCount,
        reportMetrics: reportMetrics.value
      })
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `collector-diagnostic-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("诊断包已生成");
  } finally {
    exporting.value = false;
  }
}

async function loadDiagnosticLogSample(): Promise<LogRow[]> {
  try {
    return normalizeLogRows(await getOpsLogs({ limit: 50 }));
  } catch {
    return [];
  }
}

async function loadDiagnosticAlarmSample(): Promise<AlarmRow[]> {
  try {
    return normalizeAlarmHistoryRows(await getRecentAlarms({ limit: 20 }));
  } catch {
    return [];
  }
}

function buildCurrentDiagnosticRaw(): Record<string, unknown> {
  return buildDiagnosticRaw({
    runtimeStatus: runtimeStatus.value,
    systemResource: systemResource.value,
    deviceConnectionMetrics: deviceConnectionMetrics.value,
    cacheMetrics: cacheMetrics.value,
    collectorPerformance: collectorPerformance.value,
    performanceDetail: performanceDetail.value,
    exceptionStats: exceptionStats.value,
    storageMetrics: storageMetrics.value,
    reportMetrics: reportMetrics.value,
    configSummary: configSummary.value
  });
}

function applyDiagnosticValue(key: DiagnosticSnapshotKey, value: unknown) {
  const setters: Record<DiagnosticSnapshotKey, (nextValue: unknown) => void> = {
    runtimeStatus: (nextValue) => { runtimeStatus.value = nextValue; },
    systemResource: (nextValue) => { systemResource.value = nextValue; },
    reportMetrics: (nextValue) => { reportMetrics.value = nextValue; },
    configSummary: (nextValue) => { configSummary.value = nextValue; },
    cacheMetrics: (nextValue) => { cacheMetrics.value = nextValue; },
    deviceConnectionMetrics: (nextValue) => { deviceConnectionMetrics.value = nextValue; },
    collectorPerformance: (nextValue) => { collectorPerformance.value = nextValue; },
    exceptionStats: (nextValue) => { exceptionStats.value = nextValue; },
    storageMetrics: (nextValue) => { storageMetrics.value = nextValue; },
    performanceDetail: (nextValue) => { performanceDetail.value = nextValue; }
  };
  setters[key](value);
}

function selectDevice(deviceId: string) {
  if (deviceId) {
    deviceStore.selectDevice(deviceId);
  }
}

function applyRouteDeviceId() {
  const deviceId = routeDeviceId();
  if (deviceId && deviceStore.selectedDeviceId !== deviceId) {
    deviceStore.selectDevice(deviceId);
  }
}

function routeDeviceId(): string {
  const value = route.query.deviceId;
  return Array.isArray(value) ? String(value[0] || "") : String(value || "");
}

function prettyJson(value: unknown): string {
  return JSON.stringify(hasDiagnosticData(value) ? value : buildCurrentDiagnosticRaw(), null, 2);
}

watch(() => route.query.deviceId, () => {
  applyRouteDeviceId();
}, { immediate: true });

onMounted(() => {
  void runDiagnostic();
});
</script>

<style scoped>
.diagnostic-view .diagnostic-refresh-time {
  color: var(--exact-dim);
  font-size: 12px;
}

.diagnostic-view .diagnostic-message {
  padding: 10px 14px;
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.08);
  color: #fbbf24;
  font-size: 13px;
}

.diagnostic-view .diagnostic-message.is-error {
  border-color: rgba(248, 113, 113, 0.45);
  background: rgba(248, 113, 113, 0.1);
  color: #fecaca;
}

.diagnostic-view .diagnostic-summary-surface .exact-diagnostic-cards {
  padding: 16px;
}

.diagnostic-view .diagnostic-rows-table {
  margin-top: 14px;
}

.diagnostic-view .exact-json-panel {
  margin-top: 16px;
}
</style>
