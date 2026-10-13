<template>
  <section class="exact-page diagnostic-view">
    <div class="section-heading diagnostic-heading">
      <div class="diagnostic-heading-copy">
        <div class="heading-title-line">
          <h1>系统实时状态诊断</h1>
          <span class="diagnostic-sampling-label">{{ loading ? '诊断中' : lastRefresh ? '诊断快照' : '尚未成功读取' }}</span>
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

      <section class="exact-surface diagnostic-risk-surface" aria-labelledby="diagnostic-risk-title">
        <div class="exact-surface-head diagnostic-panel-head"><h2 id="diagnostic-risk-title">优先关注</h2><span>读取失败 / 异常优先，其次警告与未知；同级保留原顺序</span></div>
        <div class="diagnostic-risk-counts" aria-label="诊断项严重性分布">
          <span class="is-error">异常 <strong>{{ diagnosticRiskCounts.abnormal }}</strong> 项</span>
          <span class="is-warning">警告 <strong>{{ diagnosticRiskCounts.warning }}</strong> 项</span>
          <span class="is-unknown">未知 <strong>{{ diagnosticRiskCounts.unknown }}</strong> 项</span>
          <span>正常 / 未启用 <strong>{{ diagnosticRiskCounts.normal }}</strong> 项</span>
        </div>
        <div v-if="priorityRow" class="diagnostic-priority" :class="priorityRow.tone">
          <div class="diagnostic-priority-copy">
            <p><span class="status-badge" :class="priorityRow.tone">{{ priorityRow.status }}</span><span v-if="priorityRow.readFailed" class="status-badge is-error">来源读取失败</span><strong>{{ priorityRow.name }}</strong></p>
            <p>{{ priorityRow.readFailed ? '先恢复对应来源读取；未读取项不作健康判断。' : priorityRow.reason }}</p>
            <p class="diagnostic-source-note">来源：{{ diagnosticRowSourceNames(priorityRow.sourceKeys) }}；读取时间与详情见下方对应项。</p>
          </div>
          <div class="diagnostic-recovery-actions">
            <button type="button" @click="focusDiagnosticRow(priorityRow.id)">查看该项</button>
            <button type="button" :disabled="loading" @click="runDiagnostic">重试完整诊断</button>
          </div>
        </div>
        <p v-else class="diagnostic-summary-note">当前诊断项未发现已知异常；已读取不代表所有业务均健康，仍请核对来源与明细。</p>
      </section>

      <section class="exact-table-card diagnostic-rows-table">
        <div class="exact-table-title diagnostic-panel-head"><h2 id="diagnostic-rows-title">诊断项列表</h2><span>{{ diagnosticRows.length }} 项</span></div>
        <div class="diagnostic-table-scroll" tabindex="0" role="region" aria-labelledby="diagnostic-rows-title">
          <table class="diagnostic-check-table">
            <colgroup><col class="diagnostic-col-name"><col class="diagnostic-col-status"><col class="diagnostic-col-current"><col class="diagnostic-col-advice"></colgroup>
            <thead><tr><th scope="col">诊断项</th><th scope="col">状态</th><th scope="col">当前值</th><th scope="col">处理建议</th></tr></thead>
            <tbody>
              <tr v-for="row in diagnosticRows" :id="row.id" :key="row.id" tabindex="-1">
                <td>{{ row.name }}<small v-if="row.name === '管线背压'" class="diagnostic-source-note">Pipeline Backpressure</small></td>
                <td><span v-if="row.readFailed" class="status-badge is-error">来源读取失败</span><span class="status-badge" :class="row.tone">{{ row.status }}</span><small v-if="row.retained" class="diagnostic-source-note">上次成功数据</small></td>
                <td>
                  {{ row.current }}<small class="diagnostic-source-note">{{ diagnosticRowSourceNames(row.sourceKeys) }}</small>
                  <details class="diagnostic-row-source">
                    <summary>读取时间与详情</summary>
                    <p class="diagnostic-row-reason">{{ row.reason }}</p>
                    <OperationSourceStatus v-for="key in row.sourceKeys" :key="key" :state="diagnosticSourceState(diagnosticSources[key], loading)" :source="diagnosticSources[key].label" :last-attempt-at="diagnosticSources[key].lastAttemptAt" :last-success-at="diagnosticSources[key].lastSuccessAt" :detail="diagnosticSourceDetail(diagnosticSources[key])" />
                  </details>
                </td>
                <td>{{ row.readFailed ? '查看来源详情，再重试完整诊断。' : row.suggestion }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="exact-surface diagnostic-summary-surface">
        <div class="exact-surface-head diagnostic-panel-head"><h2>常规诊断摘要</h2><span>{{ diagnosticDeviceContextText }}</span></div>
        <div class="exact-diagnostic-cards diagnostic-summary-rail">
          <div v-for="item in diagnosticCards" :key="item.label" class="exact-diagnostic-card diagnostic-summary-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small class="diagnostic-source-note">{{ item.sourceText }}</small></div>
        </div>
        <details class="diagnostic-source-ledger">
          <summary>查看各来源读取状态与时间</summary>
          <OperationSourceStatus v-for="(source, key) in diagnosticSources" :key="key" :state="diagnosticSourceState(source, loading)" :source="source.label" :last-attempt-at="source.lastAttemptAt" :last-success-at="source.lastSuccessAt" :detail="diagnosticSourceDetail(source)" />
        </details>
        <p class="diagnostic-summary-note">未知不计入异常或健康；成功零值保留。本轮结束时间不代表各来源成功时间。</p>
      </section>

      <DiagnosticDetailPanel
        :cache-metrics="cacheMetrics"
        :device-metrics="deviceConnectionMetrics"
        :performance-metrics="performanceDetail"
        :exception-stats="exceptionStats"
        :storage-metrics="storageMetrics"
        :pipeline-metrics="pipelineMetrics"
        :sources="diagnosticSources"
        :loading="loading"
        @retry="runDiagnostic"
      />

      <OperationSourceStatus :state="deviceStore.loading ? 'loading' : deviceStore.lastUpdatedAt ? 'ready' : deviceStore.error ? 'error' : 'unknown'" source="设备配置列表接口" :last-success-at="deviceStore.lastUpdatedAt || null" :detail="deviceStore.error ? '设备上下文部分不可用；此时间仅证明配置列表曾成功读取，不代表本轮运行快照成功。' : '此成功时间仅属于配置列表；设备运行快照、单设备查询沿用下方独立读取与状态。'">
        <button type="button" :disabled="loading" @click="runDiagnostic">重试完整诊断</button>
      </OperationSourceStatus>
      <DeviceRuntimePanel
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        :inventory-count="deviceStore.lastUpdatedAt > 0 ? deviceStore.devices.length : null"
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
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import {
  buildDisplayDiagnosticRows, createDiagnosticSources, diagnosticField, diagnosticNumber, diagnosticRecord,
  diagnosticSourceDetail, diagnosticSourceStamp, diagnosticSourceState, hasDiagnosticNumber
} from "@/features/operations/diagnostic-presentation";
import type { DiagnosticSourceKey } from "@/features/operations/diagnostic-presentation";
import { knownInventoryPointCount } from "@/features/operations/inventory-presentation";
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

type DiagnosticSnapshotKey = DiagnosticSourceKey;
const diagnosticSources = ref(createDiagnosticSources());

const systemStatusText = computed(() => appStore.initialized ? "客户端就绪" : "初始化中");
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
    const stats = diagnosticRecord(diagnosticRecord(configSummary.value).cacheStats);
    let known: boolean;
    let valueText = card.value;
    let sourceText: string;
    if (card.label === "设备配置总数" || card.label === "点位总数") {
      const key = card.label === "设备配置总数" ? "deviceCount" : "pointCount";
      const fromSummary = Boolean(diagnosticSources.value.configSummary.lastSuccessAt) && hasDiagnosticNumber(stats, [key]);
      known = fromSummary || hasKnownInventoryCount(key);
      if (!fromSummary && known) valueText = key === "deviceCount" ? `${deviceStore.devices.length} 台` : `${deviceStore.totalPointCount} 个`;
      sourceText = fromSummary ? diagnosticSourceStamp(diagnosticSources.value.configSummary) : deviceStore.lastUpdatedAt ? `设备配置列表接口 · 最后成功 ${new Date(deviceStore.lastUpdatedAt).toLocaleString("zh-CN", { hour12: false })}${deviceStore.error ? " · 设备上下文部分不可用" : ""}` : "设备配置列表 / 配置摘要 · 尚未成功读取";
    } else {
      const cacheKeys = ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"];
      const cacheSource = diagnosticField(cacheMetrics.value, cacheKeys) !== undefined ? "cacheMetrics" : "runtimeStatus";
      const key = card.label === "系统运行时间" ? "systemResource" : card.label === "活跃连接" ? "deviceConnectionMetrics" : card.label === "异常统计" ? "exceptionStats" : cacheSource;
      const fields = card.label === "系统运行时间" ? ["uptimeMillis", "uptime"] : card.label === "活跃连接" ? ["activeConnections", "connectedCount", "onlineCount"] : card.label === "异常统计" ? ["totalExceptions", "totalCount", "exceptionCount", "errorCount"] : key === "runtimeStatus" ? ["cacheHitRatio", "hitRatio", "cacheHitRate"] : cacheKeys;
      const value = key === "systemResource" ? systemResource.value : key === "deviceConnectionMetrics" ? deviceConnectionMetrics.value : key === "exceptionStats" ? exceptionStats.value : key === "cacheMetrics" ? cacheMetrics.value : runtimeStatus.value;
      known = Boolean(diagnosticSources.value[key].lastSuccessAt) && hasDiagnosticNumber(value, fields);
      sourceText = diagnosticSourceStamp(diagnosticSources.value[key]);
    }
    return { ...card, value: known ? valueText : unknownText, sourceText };
  });
});
const diagnosticBaseRows = computed(() => buildDiagnosticRows({
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
const diagnosticRows = computed(() => buildDisplayDiagnosticRows({
  rows: diagnosticBaseRows.value,
  sources: diagnosticSources.value,
  resourceSummary: resourceSummary.value,
  values: {
    runtimeStatus: runtimeStatus.value, systemResource: systemResource.value, reportMetrics: reportMetrics.value,
    configSummary: configSummary.value, cacheMetrics: cacheMetrics.value, deviceConnectionMetrics: deviceConnectionMetrics.value,
    collectorPerformance: collectorPerformance.value, exceptionStats: exceptionStats.value, storageMetrics: storageMetrics.value,
    performanceDetail: performanceDetail.value, pipelineMetrics: pipelineMetrics.value
  }
}));
const diagnosticRiskCounts = computed(() => ({
  abnormal: diagnosticRows.value.filter((row) => row.status === "异常").length,
  warning: diagnosticRows.value.filter((row) => row.status === "警告").length,
  unknown: diagnosticRows.value.filter((row) => row.status === "未知").length,
  normal: diagnosticRows.value.filter((row) => row.status === "正常" || row.status === "未启用").length
}));
const priorityRow = computed(() => diagnosticRows.value.find((row) => row.status !== "正常" && row.status !== "未启用"));
function diagnosticRowSourceNames(keys: DiagnosticSourceKey[]): string {
  return keys.map((key) => diagnosticSources.value[key].label).join("、");
}
const lastRefreshText = computed(() => lastRefresh.value ? `本轮诊断结束 ${lastRefresh.value.toLocaleString("zh-CN", { hour12: false })}` : "本轮尚未结束");
const diagnosticDeviceContextText = computed(() => {
  if (deviceStore.lastUpdatedAt > 0) {
    const prefix = deviceStore.error ? "上次已加载：" : "设备上下文：";
    return `${prefix}${deviceStore.devices.length} 台设备，${hasKnownInventoryCount("pointCount") ? `${deviceStore.totalPointCount} 个点位` : "点位数量未知"}`;
  }
  return deviceStore.loading ? "设备上下文加载中" : "设备上下文未知";
});

function focusDiagnosticRow(id: string) {
  const row = document.getElementById(id);
  row?.scrollIntoView({ block: "nearest", inline: "nearest" });
  row?.focus({ preventScroll: true });
}

function hasKnownInventoryCount(key: "deviceCount" | "pointCount"): boolean {
  if (deviceStore.lastUpdatedAt > 0) {
    if (key === "deviceCount") return true;
    return knownInventoryPointCount(deviceStore.devices) !== null;
  }
  if (!diagnosticSources.value.configSummary.lastSuccessAt) return false;
  const summary = configSummary.value;
  if (!summary || typeof summary !== "object" || !("cacheStats" in summary)) return false;
  const stats = summary.cacheStats;
  if (!stats || typeof stats !== "object") return false;
  const value = key === "deviceCount" ? ("deviceCount" in stats ? stats.deviceCount : null) : ("pointCount" in stats ? stats.pointCount : null);
  return diagnosticNumber(value) !== null;
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
      { key: "pipelineMetrics", label: "管线背压", run: getPipelineBackpressure }
    ];
    requests.forEach((request) => { diagnosticSources.value[request.key].lastAttemptAt = Date.now(); });
    const [deviceResult, ...metricResults] = await Promise.allSettled([deviceStore.refresh(), ...requests.map((request) => request.run())]);

    if (deviceResult.status === "rejected" || deviceStore.error) {
      failures.push("设备列表");
    }
    metricResults.forEach((result, index) => {
      const request = requests[index];
      if (result.status === "fulfilled") {
        applyDiagnosticValue(request.key, result.value);
        diagnosticSources.value[request.key].lastSuccessAt = Date.now();
        diagnosticSources.value[request.key].failed = false;
        return;
      }
      diagnosticSources.value[request.key].failed = true;
      failures.push(request.label);
    });
    applyRouteDeviceId();
    diagnosticRaw.value = buildCurrentDiagnosticRaw();
    lastRefresh.value = new Date();
    if (failures.length) {
      partialWarning.value = buildDiagnosticPartialWarning(failures);
    }
    if (metricResults.every((result) => result.status === "rejected") && deviceStore.error) {
      error.value = "诊断读取失败，请检查接口访问权限或连接";
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
