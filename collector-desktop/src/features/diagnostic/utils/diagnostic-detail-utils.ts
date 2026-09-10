import { RouteNames } from "@/router/route-names";
import type { PipelineBackpressureSnapshot } from "@/types/monitor";

export interface CacheDetail {
  status: string;
  tone: string;
  hitRateText: string;
  level1Text: string;
  level2Text: string;
  missRateText: string;
  readWriteText: string;
  message: string;
}

export interface DeviceConnectionDetailRow {
  deviceId: string;
  statusText: string;
  connectedText: string;
  successRateText: string;
  bytesText: string;
  idleTimeText: string;
  errors: number;
  expectedOnly: boolean;
  missing: boolean;
  tone: string;
}

export interface PerformanceDetail {
  timeSliceText: string;
  overloadedCount: number;
  rejectedTotal: number;
  reconnectText: string;
  slowestDevices: Array<{ deviceId: string; costMs: number }>;
}

export interface ExceptionDetail {
  totalText: string;
  topCategories: Array<{ name: string; count: number }>;
  topDevices: Array<{ name: string; count: number }>;
  categoryOverflowText: string;
  deviceOverflowText: string;
  recent: Array<{ deviceId: string; pointId: string; category: string; exceptionType: string; message: string; requestId: string; timestamp?: number }>;
}

export interface StorageDetail {
  enabledText: string;
  statusText: string;
  tone: string;
  responseTimeText: string;
  message: string;
}

export interface PipelineStageDetail {
  name: string;
  status: string;
  statusText: string;
  tone: string;
  enabledText: string;
  queueText: string;
  utilizationText: string;
  secondaryText: string;
}

export interface PipelineExecutorDetail {
  name: string;
  status: string;
  statusText: string;
  tone: string;
  queueText: string;
  capacityText: string;
  utilizationText: string;
  rejectedText: string;
  beanNameText: string;
}

export interface PipelineDetail {
  status: string;
  statusText: string;
  tone: string;
  riskCount: number;
  risks: string[];
  hiddenRiskCount: number;
  stages: PipelineStageDetail[];
  executors: PipelineExecutorDetail[];
}

export function buildCacheDetail(input: unknown): CacheDetail {
  const data = unwrapData(input);
  const health = asRecord(data.health);
  const status = String(data.status || health.status || (Object.keys(data).length ? "OK" : "UNKNOWN")).toUpperCase();
  return {
    status,
    tone: status === "OK" || status === "UP" ? "is-online" : status === "ERROR" ? "is-error" : "",
    hitRateText: percentText(data.totalHitRate ?? data.cacheHitRate ?? data.hitRate),
    level1Text: percentText(data.level1HitRate),
    level2Text: percentText(data.level2HitRate),
    missRateText: percentText(data.missRate),
    readWriteText: `${numberValue(data.totalReads, 0)} / ${numberValue(data.totalWrites, 0)}`,
    message: String(data.message || health.message || health.detail || "-")
  };
}

export function buildDeviceConnectionRows(input: unknown): DeviceConnectionDetailRow[] {
  const data = unwrapData(input);
  const missingIds = new Set(arrayValue(data.missingConnections).map(String));
  const rows = arrayValue(data.connections).map((item) => normalizeConnectionRow(asRecord(item), missingIds));
  const existing = new Set(rows.map((row) => row.deviceId));
  for (const deviceId of Array.from(missingIds)) {
    if (!existing.has(deviceId)) {
      rows.push(normalizeConnectionRow({ deviceId, status: "MISSING", connected: false, expectedOnly: true }, missingIds));
    }
  }
  return rows.filter((row) => row.deviceId);
}

export function buildPerformanceDetail(input: unknown): PerformanceDetail {
  const data = unwrapData(input);
  const slowestDevices = Object.entries(asRecord(data.slowestDevices))
    .map(([deviceId, value]) => ({ deviceId, costMs: numberValue(value, 0) }))
    .sort((left, right) => right.costMs - left.costMs);
  const rejectedTotal = numberValue(data.batchDispatchRejectedCount, 0) + numberValue(data.collectRejectedCount, 0) + numberValue(data.processRejectedCount, 0) + numberValue(data.rejectedCount, 0);
  const reconnectAttempts = numberValue(data.reconnectAttemptCount, 0);
  const reconnectSuccess = numberValue(data.reconnectSuccessCount, 0);
  const reconnectFailure = numberValue(data.reconnectFailureCount, 0);
  const reconnectingDevices = numberValue(data.reconnectingDevices, 0);
  return {
    timeSliceText: `${numberValue(data.timeSliceCount, 0)} × ${numberValue(data.timeSliceIntervalMs, 0)}ms`,
    overloadedCount: Object.keys(asRecord(data.overloadedSlices)).length,
    rejectedTotal,
    reconnectText: `${reconnectSuccess}/${reconnectAttempts} 成功，失败 ${reconnectFailure}，重连中 ${reconnectingDevices}`,
    slowestDevices
  };
}

export function buildExceptionDetail(input: unknown): ExceptionDetail {
  const data = unwrapData(input);
  const otherCategoryExceptions = numberValue(data.otherCategoryExceptions, 0);
  const otherDeviceExceptions = numberValue(data.otherDeviceExceptions, 0);
  return {
    totalText: `${numberValue(data.totalExceptions ?? data.totalCount ?? data.errorCount, 0)} 次`,
    topCategories: entriesByCount(data.byCategory),
    topDevices: entriesByCount(data.byDevice),
    categoryOverflowText: otherCategoryExceptions > 0 ? `另有未单独跟踪分类异常 ${otherCategoryExceptions} 次` : "",
    deviceOverflowText: otherDeviceExceptions > 0 ? `另有未单独跟踪设备异常 ${otherDeviceExceptions} 次` : "",
    recent: arrayValue(data.recent).map((item) => {
      const row = asRecord(item);
      return {
        deviceId: String(row.deviceId || "-"),
        pointId: String(row.pointId || "-"),
        category: String(row.category || row.type || "UNKNOWN"),
        exceptionType: String(row.exceptionType || "-"),
        message: String(row.message || row.error || "-"),
        requestId: String(row.requestId || ""),
        timestamp: optionalNumber(row.timestamp)
      };
    })
  };
}

export function buildPipelineDetail(input: unknown): PipelineDetail {
  const data = unwrapData(input) as PipelineBackpressureSnapshot;
  const status = String(data.status || "UNKNOWN").toUpperCase();
  const riskItems = arrayValue(data.risks).map(readableRisk).filter(Boolean);
  const visibleRisks = riskItems.slice(0, 8);
  return {
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    riskCount: riskItems.length,
    risks: visibleRisks,
    hiddenRiskCount: Math.max(0, riskItems.length - visibleRisks.length),
    stages: [
      pipelineIngressStage(data.ingress),
      pipelineStreamStage(data.stream),
      pipelineHistoryStage(data.history),
      pipelineCloudStage(data.cloud)
    ],
    executors: Object.entries(asRecord(data.executors)).map(([name, value]) => pipelineExecutor(name, asRecord(value)))
  };
}

export function buildLogRouteForRequestId(requestId: string): { name: string; query: { keyword: string } } | null {
  const keyword = requestId.trim();
  return keyword ? { name: RouteNames.LOG, query: { keyword } } : null;
}

export function buildStorageDetail(input: unknown): StorageDetail {
  const data = unwrapData(input);
  const status = String(data.status || data.state || "UNKNOWN").toUpperCase();
  const enabled = Boolean(data.enabled);
  return {
    enabledText: enabled ? "已启用" : "未启用",
    statusText: storageStatusText(status),
    tone: ["OK", "UP", "ONLINE", "SUCCESS"].includes(status) ? "is-online" : status === "ERROR" ? "is-error" : "",
    responseTimeText: `${numberValue(data.responseTimeMs, 0)} ms`,
    message: String(data.message || "-")
  };
}

function normalizeConnectionRow(record: Record<string, unknown>, missingIds: Set<string>): DeviceConnectionDetailRow {
  const deviceId = String(record.deviceId || record.id || "");
  const connected = Boolean(record.connected);
  const expectedOnly = Boolean(record.expectedOnly);
  const missing = missingIds.has(deviceId) || expectedOnly || (!connected && String(record.status || "").toUpperCase() === "MISSING");
  return {
    deviceId,
    statusText: String(record.status || (connected ? "ONLINE" : "OFFLINE")),
    connectedText: connected ? "已连接" : "未连接",
    successRateText: percentText(record.successRate),
    bytesText: `${numberValue(record.bytesSent, 0)} / ${numberValue(record.bytesReceived, 0)}`,
    idleTimeText: durationText(record.idleTime),
    errors: numberValue(record.errors, 0),
    expectedOnly,
    missing,
    tone: connected ? "is-online" : (missing ? "is-error" : "")
  };
}

function pipelineIngressStage(data: PipelineBackpressureSnapshot["ingress"]): PipelineStageDetail {
  const record = data || {};
  const status = String(record.status || "UNKNOWN").toUpperCase();
  return {
    name: "Ingress",
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    enabledText: record.enabled === false ? "未启用" : "已启用",
    queueText: `本地队列 ${countText(record.localPending)}/${capacityText(record.localCapacity)}，Redis 积压 ${countText(record.redisPending)}`,
    utilizationText: pipelineUtilizationText(record.localUtilization),
    secondaryText: `处理中 ${countText(record.redisProcessing)}，死信 ${countText(record.redisDeadLetter)}，拒绝 ${countText(record.rejectedTasks)} / ${countText(record.rejectedItems)}，丢弃 ${countText(record.droppedItems)}`
  };
}

function pipelineStreamStage(data: PipelineBackpressureSnapshot["stream"]): PipelineStageDetail {
  const record = data || {};
  const status = String(record.status || "UNKNOWN").toUpperCase();
  const dropped = sumKnown(record.admissionDropped, record.shutdownDroppedRows);
  const failures = sumKnown(record.redisXaddFailures, record.writerLoopFailures);
  return {
    name: "Stream",
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    enabledText: record.enabled === false ? "未启用" : "已启用",
    queueText: `缓冲 ${countText(record.bufferSize)}/${capacityText(record.bufferCapacity)}，峰值 ${countText(record.bufferPeak)}`,
    utilizationText: pipelineUtilizationText(record.bufferUtilization),
    secondaryText: `拒绝 ${countText(record.admissionRejected)}，丢弃 ${knownNumberText(dropped)}，失败 ${knownNumberText(failures)}`
  };
}

function pipelineHistoryStage(data: PipelineBackpressureSnapshot["history"]): PipelineStageDetail {
  const record = data || {};
  const status = String(record.status || "UNKNOWN").toUpperCase();
  return {
    name: "History",
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    enabledText: record.enabled === false ? "未启用" : "已启用",
    queueText: `本地队列 ${countText(record.localPending)}/${capacityText(record.localCapacity)}，Redis 积压 ${countText(record.redisPending)}`,
    utilizationText: pipelineUtilizationText(record.localUtilization),
    secondaryText: `处理中 ${countText(record.redisProcessing)}，死信 ${countText(record.redisDeadLetter)}，回放失败 ${countText(record.replayFailedRows)}，Live Flush ${pipelineUtilizationText(record.liveFlushQueueUtilization)}`
  };
}

function pipelineCloudStage(data: PipelineBackpressureSnapshot["cloud"]): PipelineStageDetail {
  const record = data || {};
  const status = String(record.status || "UNKNOWN").toUpperCase();
  if (record.enabled === false) {
    return {
      name: "Cloud",
      status,
      statusText: pipelineStatusText(status),
      tone: pipelineTone(status),
      enabledText: "未启用",
      queueText: "未启用",
      utilizationText: "-",
      secondaryText: `积压 ${countText(record.pending)}，隔离 ${countText(record.isolated)}，最老 ${durationText(record.oldestMessageAgeMillis)}`
    };
  }
  return {
    name: "Cloud",
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    enabledText: "已启用",
    queueText: `积压 ${countText(record.pending)}，隔离 ${countText(record.isolated)}，最老 ${durationText(record.oldestMessageAgeMillis)}`,
    utilizationText: "-",
    secondaryText: "Cloud Outbox 无队列使用率字段"
  };
}

function pipelineExecutor(name: string, data: Record<string, unknown>): PipelineExecutorDetail {
  const status = String(data.status || "UNKNOWN").toUpperCase();
  return {
    name,
    status,
    statusText: pipelineStatusText(status),
    tone: pipelineTone(status),
    queueText: countText(data.queueSize),
    capacityText: capacityText(data.queueCapacity),
    utilizationText: pipelineUtilizationText(data.queueUtilization),
    rejectedText: countText(data.rejectedCount),
    beanNameText: String(data.beanName || "-")
  };
}

function entriesByCount(value: unknown): Array<{ name: string; count: number }> {
  return Object.entries(asRecord(value))
    .map(([name, count]) => ({ name, count: numberValue(count, 0) }))
    .sort((left, right) => right.count - left.count);
}

function storageStatusText(status: string): string {
  return ({ OK: "正常", UP: "正常", ONLINE: "正常", SUCCESS: "正常", ERROR: "异常", DISABLED: "未启用", UNKNOWN: "未知" } as Record<string, string>)[status] || status;
}

function pipelineStatusText(status: string): string {
  return ({ HEALTHY: "正常", WARNING: "预警", DANGER: "危险", UNKNOWN: "未知", DISABLED: "未启用" } as Record<string, string>)[status] || status;
}

function pipelineTone(status: string): string {
  return ({ HEALTHY: "is-online", WARNING: "is-warning", DANGER: "is-error", UNKNOWN: "", DISABLED: "is-dim" } as Record<string, string>)[status] || "";
}

function readableRisk(code: unknown): string {
  const text = String(code || "");
  const translated = ({
    HISTORY_DEAD_LETTER: "历史写入存在死信积压",
    INGRESS_REDIS_UNAVAILABLE: "Ingress Redis 指标不可用",
    EXECUTOR_QUEUE_HIGH: "线程池队列压力偏高",
    CLOUD_OUTBOX_BACKLOG: "云端 Outbox 积压",
    CLOUD_SOURCE_FAILURE: "云端监控源读取失败",
    EXECUTOR_SOURCE_FAILURE: "线程池监控源读取失败",
    STREAM_REDIS_FAILURE: "Stream Redis 写入失败",
    HISTORY_REPLAY_FAILURE: "历史回放失败"
  } as Record<string, string>)[text];
  return translated || text.replace(/_/g, " ");
}

function percentText(value: unknown): string {
  const number = optionalNumber(value);
  if (number === undefined) {
    return "-";
  }
  const ratio = number > 1 && number <= 100 ? number / 100 : number;
  return `${Math.round(ratio * 100)}%`;
}

function pipelineUtilizationText(value: unknown): string {
  const number = optionalNumber(value);
  if (number === undefined || number < 0) {
    return "-";
  }
  const ratio = number > 1 && number <= 100 ? number / 100 : number;
  return `${Math.round(ratio * 100)}%`;
}

function countText(value: unknown): string {
  const number = optionalNumber(value);
  return number === undefined || number < 0 ? "-" : String(number);
}

function capacityText(value: unknown): string {
  return countText(value);
}

function knownNumberText(value: number | undefined): string {
  return value === undefined || value < 0 ? "-" : String(value);
}

function sumKnown(...values: unknown[]): number | undefined {
  let total = 0;
  let hasKnown = false;
  for (const value of values) {
    const number = optionalNumber(value);
    if (number !== undefined && number >= 0) {
      total += number;
      hasKnown = true;
    }
  }
  return hasKnown ? total : undefined;
}

function durationText(value: unknown): string {
  const number = optionalNumber(value);
  if (number === undefined) {
    return "-";
  }
  if (number >= 1000) {
    return `${Math.round(number / 1000)} s`;
  }
  return `${number} ms`;
}

function unwrapData(value: unknown): Record<string, unknown> {
  const record = asRecord(value);
  const data = asRecord(record.data);
  return Object.keys(data).length ? data : record;
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function optionalNumber(value: unknown): number | undefined {
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function numberValue(value: unknown, fallback: number): number {
  return optionalNumber(value) ?? fallback;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
