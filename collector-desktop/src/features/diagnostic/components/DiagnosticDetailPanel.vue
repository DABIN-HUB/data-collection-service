<template>
  <section class="exact-surface diagnostic-detail-panel">
    <div class="exact-surface-head">
      <h2>诊断细分明细</h2>
      <span>缓存 · 管线背压 · 连接 · 性能 · 异常 · 存储</span>
    </div>
    <div class="exact-diagnostic-cards diagnostic-detail-cards">
      <div class="exact-diagnostic-card">
        <span>缓存命中率</span>
        <strong>{{ cacheDetail.hitRateText }}</strong>
        <small>L1 {{ cacheDetail.level1Text }} / L2 {{ cacheDetail.level2Text }} / Miss {{ cacheDetail.missRateText }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>管线背压</span>
        <strong><span class="status-badge" :class="pipelineDetail.tone">{{ pipelineDetail.statusText }}</span></strong>
        <small>{{ pipelineDetail.riskCount === '未知' ? '风险数量未知' : `风险 ${pipelineDetail.riskCount} 条` }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>设备连接</span>
        <strong>{{ connectionSummaryText }}</strong>
        <small>缺失连接 {{ missingCountText }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>线程池拒绝</span>
        <strong>{{ performanceDetail.rejectedTotal }}</strong>
        <small>过载分片 {{ performanceDetail.overloadedCount }} 个</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>异常统计 Top</span>
        <strong>{{ exceptionDetail.totalText }}</strong>
        <small>{{ exceptionDetail.topCategories[0]?.name || (fieldKnown('exceptionStats', 'byCategory') ? '暂无异常分类' : '异常分类未知') }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>历史存储状态</span>
        <strong>{{ storageDetail.statusText }}</strong>
        <small>{{ storageDetail.message }}</small>
      </div>
    </div>

    <section class="exact-table-card diagnostic-pipeline-card">
      <div class="exact-table-title"><h2>管线背压 <small>Pipeline Backpressure</small></h2><span>{{ pipelineDetail.statusText }}</span></div>
      <OperationSourceStatus :state="sourceState('pipelineMetrics')" :source="sources.pipelineMetrics.label" :last-attempt-at="sources.pipelineMetrics.lastAttemptAt" :last-success-at="sources.pipelineMetrics.lastSuccessAt" :detail="diagnosticSourceDetail(sources.pipelineMetrics)">
        <button type="button" :disabled="loading" @click="emit('retry')">重试完整诊断</button>
      </OperationSourceStatus>
      <div class="diagnostic-detail-grid compact-grid">
        <div v-for="stage in pipelineDetail.stages" :key="stage.name" class="modao-property-item pipeline-stage-card">
          <span>{{ stage.name }}</span>
          <strong><span class="status-badge" :class="stage.tone">{{ stage.statusText }}</span></strong>
          <small>{{ stage.enabledText }} · {{ stage.queueText }} · 使用率 {{ stage.utilizationText }}</small>
          <small>{{ stage.secondaryText }}</small>
        </div>
      </div>
      <div class="modao-risk-list pipeline-risk-list">
        <div v-if="pipelineDetail.risks.length === 0" class="empty-state compact">{{ fieldKnown('pipelineMetrics', 'risks') ? '当前来源未列出管线风险；未知阶段仍需核对' : '管线风险未知：尚未取得风险列表' }}</div>
        <div v-for="risk in pipelineDetail.risks" :key="risk" class="modao-risk-item"><strong>{{ risk }}</strong><small>管线风险</small></div>
        <div v-if="pipelineDetail.hiddenRiskCount > 0" class="modao-risk-item"><strong>另有 {{ pipelineDetail.hiddenRiskCount }} 条</strong><small>请查看原始诊断 JSON</small></div>
      </div>
    </section>

    <section class="exact-table-card diagnostic-executor-table" tabindex="0" role="region" aria-label="遥测线程池压力表，可局部滚动">
      <div class="exact-table-title"><h2>遥测线程池压力 <small>Telemetry Executor Pressure</small></h2><span>{{ fieldKnown('pipelineMetrics', 'executors') ? `${pipelineDetail.executors.length} 个线程池` : '线程池数量未知' }}</span></div>
      <table>
        <thead><tr><th>Executor</th><th>状态</th><th>Queue</th><th>Capacity</th><th>Utilization</th><th>Rejected</th></tr></thead>
        <tbody>
          <tr v-if="pipelineDetail.executors.length === 0"><td colspan="6" class="exact-empty">{{ detailEmptyText('pipelineMetrics', 'executors', '当前来源暂无遥测线程池指标') }}</td></tr>
          <tr v-for="executor in pipelineDetail.executors" :key="executor.name">
            <td><code :title="executor.beanNameText">{{ executor.name }}</code></td>
            <td><span class="status-badge" :class="executor.tone">{{ executor.statusText }}</span></td>
            <td>{{ executor.queueText }}</td>
            <td>{{ executor.capacityText }}</td>
            <td>{{ executor.utilizationText }}</td>
            <td>{{ executor.rejectedText }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <div class="diagnostic-detail-grid">
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>缓存服务明细</h2><span>{{ cacheDetail.status }}</span></div>
        <OperationSourceStatus :state="sourceState('cacheMetrics')" :source="sources.cacheMetrics.label" :last-attempt-at="sources.cacheMetrics.lastAttemptAt" :last-success-at="sources.cacheMetrics.lastSuccessAt" :detail="diagnosticSourceDetail(sources.cacheMetrics)" />
        <div class="modao-property-grid">
          <div class="modao-property-item"><span>读 / 写</span><strong>{{ cacheDetail.readWriteText }}</strong></div>
          <div class="modao-property-item"><span>总命中率</span><strong>{{ cacheDetail.hitRateText }}</strong></div>
          <div class="modao-property-item"><span>一级缓存</span><strong>{{ cacheDetail.level1Text }}</strong></div>
          <div class="modao-property-item"><span>二级缓存</span><strong>{{ cacheDetail.level2Text }}</strong></div>
        </div>
      </section>
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>性能详情</h2><span>{{ performanceDetail.timeSliceText }}</span></div>
        <OperationSourceStatus :state="sourceState('performanceDetail')" :source="sources.performanceDetail.label" :last-attempt-at="sources.performanceDetail.lastAttemptAt" :last-success-at="sources.performanceDetail.lastSuccessAt" :detail="diagnosticSourceDetail(sources.performanceDetail)" />
        <div class="modao-property-grid">
          <div class="modao-property-item"><span>拒绝总数</span><strong>{{ performanceDetail.rejectedTotal }}</strong></div>
          <div class="modao-property-item"><span>过载分片</span><strong>{{ performanceDetail.overloadedCount }}</strong></div>
          <div class="modao-property-item wide"><span>重连统计</span><strong>{{ performanceDetail.reconnectText }}</strong></div>
        </div>
      </section>
    </div>
    <section class="exact-table-card diagnostic-connection-table" tabindex="0" role="region" aria-label="设备连接指标表，可局部滚动">
      <div class="exact-table-title"><h2>设备连接指标</h2><span>连接不等同采集健康</span></div>
      <OperationSourceStatus :state="sourceState('deviceConnectionMetrics')" :source="sources.deviceConnectionMetrics.label" :last-attempt-at="sources.deviceConnectionMetrics.lastAttemptAt" :last-success-at="sources.deviceConnectionMetrics.lastSuccessAt" :detail="diagnosticSourceDetail(sources.deviceConnectionMetrics)">
        <button type="button" :disabled="loading" @click="emit('retry')">重试完整诊断</button>
      </OperationSourceStatus>
      <table>
        <thead><tr><th>设备</th><th>连接状态</th><th>连接</th><th>采集健康</th><th>采集就绪</th><th>连接成功率</th><th>收/发字节</th><th>空闲</th><th>错误</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-if="connectionRows.length === 0"><td colspan="10" class="exact-empty">{{ detailEmptyText('deviceConnectionMetrics', 'connections', '当前来源暂无设备连接指标') }}</td></tr>
          <tr v-for="row in connectionRows" :key="row.deviceId">
            <td><code>{{ row.deviceId }}</code></td>
            <td>{{ row.statusText }}</td>
            <td><span class="status-badge" :class="row.tone">{{ row.connectedText }}</span></td>
            <td><span class="status-badge" :class="row.healthTone">{{ row.healthText }}</span></td>
            <td>{{ row.readyText }}</td>
            <td>{{ row.successRateText }}</td>
            <td>{{ row.bytesText }}</td>
            <td>{{ row.idleTimeText }}</td>
            <td>{{ row.errors }}</td>
            <td>{{ row.healthReason || (row.missing ? '缺失连接或仅存在期望配置' : '连接指标已采集') }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <div class="diagnostic-detail-grid">
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>异常统计 Top</h2><span>最近异常分类</span></div>
        <div class="modao-risk-list">
          <div v-if="exceptionDetail.topCategories.length === 0" class="empty-state compact">{{ detailEmptyText('exceptionStats', 'byCategory', '当前来源暂无异常分类统计') }}</div>
          <div v-for="item in exceptionDetail.topCategories.slice(0, 5)" :key="item.name" class="modao-risk-item"><strong>{{ item.name }}</strong><small>{{ item.count }} 次</small></div>
          <div v-if="exceptionDetail.categoryOverflowText" class="modao-risk-item"><strong>{{ exceptionDetail.categoryOverflowText }}</strong><small>分类溢出</small></div>
        </div>
      </section>
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>异常设备 Top</h2><span>设备异常排行</span></div>
        <div class="modao-risk-list">
          <div v-if="exceptionDetail.topDevices.length === 0" class="empty-state compact">{{ detailEmptyText('exceptionStats', 'byDevice', '当前来源暂无设备异常统计') }}</div>
          <div v-for="item in exceptionDetail.topDevices.slice(0, 5)" :key="item.name" class="modao-risk-item"><strong>{{ item.name }}</strong><small>{{ item.count }} 次</small></div>
          <div v-if="exceptionDetail.deviceOverflowText" class="modao-risk-item"><strong>{{ exceptionDetail.deviceOverflowText }}</strong><small>设备溢出</small></div>
        </div>
      </section>
    </div>
    <section class="exact-table-card diagnostic-exception-table" tabindex="0" role="region" aria-label="最近异常表，可局部滚动">
      <div class="exact-table-title"><h2>最近异常</h2><span>{{ exceptionDetail.totalText }}</span></div>
      <OperationSourceStatus :state="sourceState('exceptionStats')" :source="sources.exceptionStats.label" :last-attempt-at="sources.exceptionStats.lastAttemptAt" :last-success-at="sources.exceptionStats.lastSuccessAt" :detail="diagnosticSourceDetail(sources.exceptionStats)">
        <button type="button" :disabled="loading" @click="emit('retry')">重试完整诊断</button>
      </OperationSourceStatus>
      <table>
        <thead><tr><th>时间</th><th>设备</th><th>点位</th><th>分类</th><th>类型</th><th>Request ID</th><th>消息</th></tr></thead>
        <tbody>
          <tr v-if="exceptionDetail.recent.length === 0"><td colspan="7" class="exact-empty">{{ detailEmptyText('exceptionStats', 'recent', '当前来源暂无最近异常') }}</td></tr>
          <tr v-for="item in exceptionDetail.recent.slice(0, 8)" :key="`${item.timestamp || '-'}-${item.deviceId}-${item.pointId}-${item.category}`">
            <td>{{ formatTime(item.timestamp) }}</td>
            <td>{{ item.deviceId }}</td>
            <td>{{ item.pointId }}</td>
            <td>{{ item.category }}</td>
            <td>{{ item.exceptionType }}</td>
            <td>
              <button v-if="item.requestId" type="button" class="link-button" @click="goToRequestLog(item.requestId)">{{ item.requestId }}</button>
              <span v-else>-</span>
            </td>
            <td>{{ item.message }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <OperationSourceStatus :state="sourceState('storageMetrics')" :source="sources.storageMetrics.label" :last-attempt-at="sources.storageMetrics.lastAttemptAt" :last-success-at="sources.storageMetrics.lastSuccessAt" :detail="diagnosticSourceDetail(sources.storageMetrics)" />
    <div class="diagnostic-detail-recovery"><span>缺失字段为未知；来源失败时保留上次成功数据。</span><button type="button" :disabled="loading" @click="emit('retry')">重试完整诊断</button></div>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import { diagnosticData, diagnosticField, diagnosticNumber, diagnosticRecord, diagnosticSourceDetail, diagnosticSourceState, diagnosticStatus, hasDiagnosticNumber } from "@/features/operations/diagnostic-presentation";
import type { DiagnosticSourceKey, DiagnosticSources } from "@/features/operations/diagnostic-presentation";

import { buildCacheDetail, buildDeviceConnectionRows, buildExceptionDetail, buildLogRouteForRequestId, buildPerformanceDetail, buildPipelineDetail, buildStorageDetail } from "../utils/diagnostic-detail-utils";

const props = defineProps<{
  cacheMetrics: unknown;
  deviceMetrics: unknown;
  performanceMetrics: unknown;
  exceptionStats: unknown;
  storageMetrics: unknown;
  pipelineMetrics: unknown;
  sources: DiagnosticSources;
  loading: boolean;
}>();
const emit = defineEmits<{ (event: "retry"): void }>();

const router = useRouter();
const cacheDetail = computed(() => {
  const detail = buildCacheDetail(props.cacheMetrics);
  const data = sourceData("cacheMetrics");
  const status = diagnosticStatus(diagnosticField(data, ["status"]) ?? diagnosticRecord(data.health).status);
  return {
    ...detail, status: status.text, tone: status.tone,
    hitRateText: numericText("cacheMetrics", ["totalHitRate", "cacheHitRate", "hitRate"], detail.hitRateText),
    level1Text: numericText("cacheMetrics", ["level1HitRate"], detail.level1Text),
    level2Text: numericText("cacheMetrics", ["level2HitRate"], detail.level2Text),
    missRateText: numericText("cacheMetrics", ["missRate"], detail.missRateText),
    readWriteText: `${numberText("cacheMetrics", "totalReads")} / ${numberText("cacheMetrics", "totalWrites")}`
  };
});
const connectionRows = computed(() => {
  const data = sourceData("deviceConnectionMetrics");
  const rawRows = Array.isArray(data.connections) ? data.connections : [];
  return buildDeviceConnectionRows(props.deviceMetrics).map((row) => {
    const raw = diagnosticRecord(rawRows.find((item) => String(diagnosticRecord(item).deviceId || diagnosticRecord(item).id || "") === row.deviceId));
    const connectedKnown = typeof raw.connected === "boolean" || row.missing;
    const runtimeKnown = Object.keys(diagnosticRecord(raw.runtime)).length > 0;
    return {
      ...row,
      connectedText: connectedKnown ? row.connectedText : "未知",
      tone: connectedKnown ? row.tone : "is-unknown",
      statusText: typeof raw.status === "string" || connectedKnown ? row.statusText : "未知",
      healthText: runtimeKnown ? row.healthText : "未知", healthTone: runtimeKnown ? row.healthTone : "is-unknown",
      readyText: runtimeKnown ? row.readyText : "未知",
      successRateText: hasDiagnosticNumber(raw, ["successRate"]) ? row.successRateText : "未知",
      bytesText: `${diagnosticNumber(raw.bytesSent) ?? "未知"} / ${diagnosticNumber(raw.bytesReceived) ?? "未知"}`,
      idleTimeText: hasDiagnosticNumber(raw, ["idleTime"]) ? row.idleTimeText : "未知",
      errors: diagnosticNumber(raw.errors) ?? "未知"
    };
  });
});
const performanceDetail = computed(() => {
  const detail = buildPerformanceDetail(props.performanceMetrics);
  const data = sourceData("performanceDetail");
  const rejectFields = ["batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"];
  const completeRejects = rejectFields.every((key) => hasDiagnosticNumber(data, [key])) || (hasDiagnosticNumber(data, ["rejectedCount"]) && rejectFields.every((key) => diagnosticField(data, [key]) === undefined));
  return {
    ...detail,
    rejectedTotal: completeRejects ? detail.rejectedTotal : detail.rejectedTotal > 0 ? `${detail.rejectedTotal}（部分计数未知）` : "未知",
    overloadedCount: fieldKnown("performanceDetail", "overloadedSlices") ? detail.overloadedCount : "未知",
    timeSliceText: `${numberText("performanceDetail", "timeSliceCount")} × ${numberText("performanceDetail", "timeSliceIntervalMs")}ms`,
    reconnectText: `${numberText("performanceDetail", "reconnectSuccessCount")}/${numberText("performanceDetail", "reconnectAttemptCount")} 成功，失败 ${numberText("performanceDetail", "reconnectFailureCount")}，重连中 ${numberText("performanceDetail", "reconnectingDevices")}`
  };
});
const exceptionDetail = computed(() => {
  const detail = buildExceptionDetail(props.exceptionStats);
  return { ...detail, totalText: numericText("exceptionStats", ["totalExceptions", "totalCount", "errorCount"], detail.totalText), topCategories: detail.topCategories.map((item) => ({ ...item, count: diagnosticNumber(diagnosticRecord(sourceData("exceptionStats").byCategory)[item.name]) ?? "未知" })), topDevices: detail.topDevices.map((item) => ({ ...item, count: diagnosticNumber(diagnosticRecord(sourceData("exceptionStats").byDevice)[item.name]) ?? "未知" })) };
});
const storageDetail = computed(() => {
  const detail = buildStorageDetail(props.storageMetrics);
  const data = sourceData("storageMetrics");
  const status = diagnosticStatus(diagnosticField(data, ["status", "state"]));
  return { ...detail, statusText: data.enabled === false && status.status === "未知" ? "未启用" : status.text, tone: status.tone };
});
const pipelineDetail = computed(() => {
  const detail = buildPipelineDetail(props.pipelineMetrics);
  const data = sourceData("pipelineMetrics");
  return {
    ...detail, tone: detail.status === "UNKNOWN" ? "is-unknown" : detail.tone,
    riskCount: fieldKnown("pipelineMetrics", "risks") ? detail.riskCount : "未知",
    stages: detail.stages.map((stage, index) => {
      const key = ["ingress", "stream", "history", "cloud"][index];
      const raw = diagnosticRecord(data[key]);
      const count = (field: string) => diagnosticNumber(raw[field]) === null ? "未知" : String(raw[field]);
      const stageNames = ["采集入口（Ingress）", "流缓冲（Stream）", "历史写入（History）", "云端上报（Cloud）"];
      let queueText = `本地队列 ${count("localPending")}/${count("localCapacity")}，Redis 积压 ${count("redisPending")}`;
      let secondaryText = stage.secondaryText;
      if (key === "ingress") secondaryText = `处理中 ${count("redisProcessing")}，死信 ${count("redisDeadLetter")}，拒绝 ${count("rejectedTasks")} / ${count("rejectedItems")}，丢弃 ${count("droppedItems")}`;
      if (key === "stream") {
        queueText = `缓冲 ${count("bufferSize")}/${count("bufferCapacity")}，峰值 ${count("bufferPeak")}`;
        if (!["admissionRejected", "admissionDropped", "shutdownDroppedRows", "redisXaddFailures", "writerLoopFailures"].every((field) => hasDiagnosticNumber(raw, [field]))) secondaryText = `拒绝 ${count("admissionRejected")}；丢弃 / 失败统计部分未知`;
      }
      if (key === "history") secondaryText = `处理中 ${count("redisProcessing")}，死信 ${count("redisDeadLetter")}，回放失败 ${count("replayFailedRows")}，即时刷新队列使用率 ${metricText(raw.liveFlushQueueUtilization, "percent")}`;
      if (key === "cloud") {
        queueText = raw.enabled === false ? "未启用" : `积压 ${count("pending")}，隔离 ${count("isolated")}，最老 ${metricText(raw.oldestMessageAgeMillis, "duration")}`;
        secondaryText = raw.enabled === false ? `积压 ${count("pending")}，隔离 ${count("isolated")}，最老 ${metricText(raw.oldestMessageAgeMillis, "duration")}` : "云端发件箱未提供队列使用率字段";
      }
      return { ...stage, name: stageNames[index], tone: stage.status === "UNKNOWN" ? "is-unknown" : stage.tone, enabledText: raw.enabled === false ? "未启用" : raw.enabled === true ? "已启用" : "启用状态未知", queueText, secondaryText, utilizationText: hasDiagnosticNumber(raw, [key === "stream" ? "bufferUtilization" : "localUtilization"]) ? stage.utilizationText : "未知" };
    }),
    executors: detail.executors.map((executor) => {
      const raw = diagnosticRecord(diagnosticRecord(data.executors)[executor.name]);
      return { ...executor, tone: executor.status === "UNKNOWN" ? "is-unknown" : executor.tone, queueText: hasDiagnosticNumber(raw, ["queueSize"]) ? executor.queueText : "未知", capacityText: hasDiagnosticNumber(raw, ["queueCapacity"]) ? executor.capacityText : "未知", utilizationText: hasDiagnosticNumber(raw, ["queueUtilization"]) ? executor.utilizationText : "未知", rejectedText: hasDiagnosticNumber(raw, ["rejectedCount"]) ? executor.rejectedText : "未知" };
    })
  };
});
const connectionSummaryText = computed(() => fieldKnown("deviceConnectionMetrics", "connections") ? `${connectionRows.value.filter((row) => row.connectedText === "已连接").length}/${connectionRows.value.length}${connectionRows.value.some((row) => row.connectedText === "未知") ? "（部分未知）" : ""}` : "未知");
const missingCountText = computed(() => fieldKnown("deviceConnectionMetrics", "missingConnections") ? `${connectionRows.value.filter((row) => row.missing).length} 个` : "未知");

function sourceData(key: DiagnosticSourceKey): Record<string, unknown> {
  if (!props.sources[key].lastSuccessAt) return {};
  const values: Partial<Record<DiagnosticSourceKey, unknown>> = { cacheMetrics: props.cacheMetrics, deviceConnectionMetrics: props.deviceMetrics, performanceDetail: props.performanceMetrics, exceptionStats: props.exceptionStats, storageMetrics: props.storageMetrics, pipelineMetrics: props.pipelineMetrics };
  return diagnosticData(values[key]);
}

function fieldKnown(key: DiagnosticSourceKey, field: string): boolean {
  const value = diagnosticField(sourceData(key), [field]);
  return value !== undefined && (field === "risks" || field === "connections" || field === "missingConnections" || field === "recent" ? Array.isArray(value) : typeof value === "object" && value !== null && !Array.isArray(value));
}

function numericText(key: DiagnosticSourceKey, fields: string[], text: string): string {
  return hasDiagnosticNumber(sourceData(key), fields) ? text : "未知";
}

function numberText(key: DiagnosticSourceKey, field: string): string {
  const value = diagnosticField(sourceData(key), [field]);
  return diagnosticNumber(value) === null ? "未知" : String(value);
}

function metricText(value: unknown, mode: "percent" | "duration"): string {
  const number = diagnosticNumber(value);
  if (number === null) return "未知";
  if (mode === "duration") return number >= 1000 ? `${Math.round(number / 1000)} s` : `${number} ms`;
  return `${Math.round((number > 1 && number <= 100 ? number / 100 : number) * 100)}%`;
}

function sourceState(key: DiagnosticSourceKey) {
  return diagnosticSourceState(props.sources[key], props.loading);
}

function detailEmptyText(key: DiagnosticSourceKey, field: string, emptyText: string): string {
  if (!props.sources[key].lastSuccessAt) return `${props.sources[key].label}尚未成功读取，请重试完整诊断`;
  if (!fieldKnown(key, field)) return "对应字段尚未取得，当前数据未知，可重试完整诊断";
  if (props.sources[key].failed) return "本次来源读取失败；上次成功数据中暂无记录，可重试完整诊断";
  return emptyText;
}

function goToRequestLog(requestId: string) {
  const route = buildLogRouteForRequestId(requestId);
  if (route) {
    void router.push(route);
  }
}

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = new Date(typeof value === "number" ? value : String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}
</script>

<style scoped>
.diagnostic-detail-panel {
  margin-top: 16px;
}

.diagnostic-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.diagnostic-detail-grid.compact-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.diagnostic-detail-cards {
  padding: 16px;
}

.diagnostic-detail-cards small,
.pipeline-stage-card small {
  display: block;
  margin-top: 6px;
  color: var(--exact-dim);
  font-size: 12px;
}

.diagnostic-sub-card,
.diagnostic-connection-table,
.diagnostic-exception-table,
.diagnostic-pipeline-card,
.diagnostic-executor-table {
  margin-top: 14px;
}

.pipeline-risk-list {
  margin-top: 12px;
}

.link-button {
  border: 0;
  background: transparent;
  color: var(--console-primary-hover);
  cursor: pointer;
  font: inherit;
  padding: 0;
}

.link-button:hover {
  text-decoration: underline;
}

.link-button:focus-visible {
  outline: 2px solid var(--console-primary-hover);
  outline-offset: 2px;
}

.is-dim {
  opacity: 0.65;
}

@media (max-width: 1100px) {
  .diagnostic-detail-grid,
  .diagnostic-detail-grid.compact-grid {
    grid-template-columns: 1fr;
  }
}
</style>
