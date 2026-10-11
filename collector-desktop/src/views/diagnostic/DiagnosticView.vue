<template>
  <section class="exact-page diagnostic-view">
    <div class="section-heading diagnostic-heading">
      <div class="diagnostic-heading-copy">
        <div class="heading-title-line">
          <h1>系统实时状态诊断</h1>
          <span class="diagnostic-sampling-label">{{ loading ? '诊断中' : '运行数据' }}</span>
        </div>
        <p>核对服务、连接与采集健康；部分数据不可用时，请结合告警与原始记录判断。</p>
      </div>
      <div class="heading-actions">
        <span class="diagnostic-refresh-time">{{ lastRefreshText }}</span>
        <button type="button" class="primary" :disabled="loading" @click="runDiagnostic">{{ loading ? '诊断中' : '运行完整诊断' }}</button>
        <button type="button" :disabled="exporting" @click="downloadDiagnosticPackage">{{ exporting ? '导出中' : '导出诊断包' }}</button>
      </div>
    </div>

    <div class="exact-page-body diagnostic-body">
      <p v-if="exportMessage" class="diagnostic-message" :class="{ 'is-error': exportMessageType === 'error' }" role="status">{{ exportMessage }}</p>
      <p v-if="error" class="diagnostic-message is-error" role="alert">{{ error }}</p>
      <p v-else-if="partialWarning" class="diagnostic-message" role="status">{{ partialWarning }}</p>

      <section class="exact-surface diagnostic-summary-surface">
        <div class="exact-surface-head diagnostic-panel-head"><h2>诊断摘要</h2><span>{{ diagnosticDeviceContextText }}</span></div>
        <div class="exact-diagnostic-cards diagnostic-summary-rail">
          <div v-for="item in diagnosticCards" :key="item.label" class="exact-diagnostic-card diagnostic-summary-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
        <p class="diagnostic-summary-note">摘要不代表所有指标均已获取；请求失败时保留已获取的数据，以提示及详情为准。</p>
      </section>

      <section class="exact-table-card diagnostic-rows-table">
        <div class="exact-table-title diagnostic-panel-head"><h2 id="diagnostic-rows-title">诊断项列表</h2><span>{{ diagnosticRows.length }} 项</span></div>
        <div class="diagnostic-table-scroll" tabindex="0" role="region" aria-labelledby="diagnostic-rows-title">
          <table class="diagnostic-check-table">
            <colgroup><col class="diagnostic-col-name"><col class="diagnostic-col-status"><col class="diagnostic-col-current"><col class="diagnostic-col-advice"></colgroup>
            <thead><tr><th scope="col">诊断项</th><th scope="col">状态</th><th scope="col">当前值</th><th scope="col">处理建议</th></tr></thead>
            <tbody>
              <tr v-for="row in diagnosticRows" :key="row.name"><td>{{ row.name }}</td><td><span class="status-badge" :class="row.tone">{{ row.status }}</span></td><td>{{ row.current }}</td><td>{{ row.suggestion }}</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <DiagnosticDetailPanel
        :cache-metrics="cacheMetrics"
        :device-metrics="deviceConnectionMetrics"
        :performance-metrics="performanceDetail"
        :exception-stats="exceptionStats"
        :storage-metrics="storageMetrics"
        :pipeline-metrics="pipelineMetrics"
      />

      <DeviceRuntimePanel
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        @select-device="selectDevice"
      />

      <details class="exact-json-panel diagnostic-raw-panel">
        <summary>查看原始诊断 JSON</summary>
        <pre class="json-view" tabindex="0" aria-label="完整原始诊断 JSON">{{ prettyJson(diagnosticRaw) }}</pre>
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
  getPipelineBackpressure,
  getRuntimeStatus,
  getStorageMetrics,
  getSystemResources
} from "@/api/monitor.api";
import { getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import DiagnosticDetailPanel from "@/features/diagnostic/components/DiagnosticDetailPanel.vue";
import DeviceRuntimePanel from "@/features/diagnostic/components/DeviceRuntimePanel.vue";
import {
  buildDiagnosticCards,
  buildDiagnosticExportWarning,
  buildDiagnosticPartialWarning,
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
const pipelineMetrics = ref<unknown>({});
const diagnosticRaw = ref<unknown>({});
const loading = ref(false);
const exporting = ref(false);
const error = ref("");
const partialWarning = ref("");
const exportMessage = ref("");
const exportMessageType = ref<"success" | "warning" | "error">("success");
const lastRefresh = ref<Date | null>(null);

type DiagnosticSnapshotKey = "runtimeStatus" | "systemResource" | "reportMetrics" | "configSummary" | "cacheMetrics" | "deviceConnectionMetrics" | "collectorPerformance" | "exceptionStats" | "storageMetrics" | "performanceDetail" | "pipelineMetrics";

const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const resourceSummary = computed(() => buildResourceSummary({
  systemResource: systemResource.value,
  reportMetrics: reportMetrics.value,
  performanceDetail: performanceDetail.value
}));
const diagnosticCards = computed(() => {
  const cards = buildDiagnosticCards({
    systemResource: systemResource.value,
    configSummary: configSummary.value,
    deviceConnectionMetrics: deviceConnectionMetrics.value,
    cacheMetrics: cacheMetrics.value,
    runtimeStatus: runtimeStatus.value,
    exceptionStats: exceptionStats.value,
    pipelineMetrics: pipelineMetrics.value,
    devices: deviceStore.devices,
    onlineCount: deviceStore.onlineCount,
    totalPointCount: deviceStore.totalPointCount
  });
  const unknownText = loading.value ? "加载中" : "未知";
  return cards.map((card) => {
    if (card.label === "设备配置总数" && !hasKnownInventoryCount("deviceCount")) return { ...card, value: unknownText };
    if (card.label === "点位总数" && !hasKnownInventoryCount("pointCount")) return { ...card, value: unknownText };
    return card;
  });
});
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
  pipelineMetrics: pipelineMetrics.value,
  devices: deviceStore.devices,
  onlineCount: deviceStore.onlineCount
}));
const lastRefreshText = computed(() => lastRefresh.value ? `刷新时间 ${lastRefresh.value.toLocaleTimeString()}` : "尚未刷新");
const diagnosticDeviceContextText = computed(() => {
  if (deviceStore.lastUpdatedAt > 0) {
    const prefix = deviceStore.error ? "上次已加载：" : "设备上下文：";
    return `${prefix}${deviceStore.devices.length} 台设备，${deviceStore.totalPointCount} 个点位`;
  }
  return deviceStore.loading ? "设备上下文加载中" : "设备上下文未知";
});

function hasKnownInventoryCount(key: "deviceCount" | "pointCount"): boolean {
  if (deviceStore.lastUpdatedAt > 0) return true;
  const summary = configSummary.value;
  if (!summary || typeof summary !== "object" || !("cacheStats" in summary)) return false;
  const stats = summary.cacheStats;
  if (!stats || typeof stats !== "object") return false;
  const value = key === "deviceCount" ? ("deviceCount" in stats ? stats.deviceCount : null) : ("pointCount" in stats ? stats.pointCount : null);
  return value !== null && value !== undefined;
}

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
      { key: "performanceDetail", label: "性能详情", run: getPerformanceDetail },
      { key: "pipelineMetrics", label: "Pipeline", run: getPipelineBackpressure }
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
      partialWarning.value = buildDiagnosticPartialWarning(failures);
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
  const targetDeviceId = deviceStore.selectedDeviceId || "all-devices";
  exporting.value = true;
  exportMessage.value = "";
  try {
    const [alarmSample, logSample] = await Promise.all([loadDiagnosticAlarmSample(), loadDiagnosticLogSample()]);
    const sampleWarning = buildDiagnosticExportWarning([
      { label: "最近告警", value: alarmSample.rows, failed: alarmSample.failed },
      { label: "最近日志", value: logSample.rows, failed: logSample.failed }
    ]);
    const payload = {
      generatedAt: new Date().toISOString(),
      selectedDeviceId: targetDeviceId,
      selectedDevice: deviceStore.selectedDevice || null,
      overview: diagnosticRaw.value,
      alarms: alarmSample.rows,
      logs: logSample.rows,
      warnings: sampleWarning ? [sampleWarning] : [],
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
    exportMessageType.value = sampleWarning ? "warning" : "success";
    exportMessage.value = sampleWarning ? `设备 ${targetDeviceId} 诊断包已生成；${sampleWarning}` : `设备 ${targetDeviceId} 诊断包已生成`;
    if (sampleWarning) {
      ElMessage.warning(exportMessage.value);
    } else {
      ElMessage.success(exportMessage.value);
    }
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "诊断包导出失败";
    exportMessageType.value = "error";
    exportMessage.value = `设备 ${targetDeviceId} 诊断包导出失败：${message}`;
    ElMessage.error(exportMessage.value);
  } finally {
    exporting.value = false;
  }
}

async function loadDiagnosticLogSample(): Promise<{ rows: LogRow[]; failed: boolean }> {
  try {
    return { rows: normalizeLogRows(await getOpsLogs({ limit: 50 })), failed: false };
  } catch {
    return { rows: [], failed: true };
  }
}

async function loadDiagnosticAlarmSample(): Promise<{ rows: AlarmRow[]; failed: boolean }> {
  try {
    return { rows: normalizeAlarmHistoryRows(await getRecentAlarms({ limit: 20 })), failed: false };
  } catch {
    return { rows: [], failed: true };
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
    pipelineMetrics: pipelineMetrics.value,
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
    performanceDetail: (nextValue) => { performanceDetail.value = nextValue; },
    pipelineMetrics: (nextValue) => { pipelineMetrics.value = nextValue; }
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

<style scoped src="@/features/diagnostic/styles/diagnostic-view.css"></style>
