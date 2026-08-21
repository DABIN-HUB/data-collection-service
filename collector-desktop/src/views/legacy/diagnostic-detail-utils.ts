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
  recent: Array<{ deviceId: string; pointId: string; category: string; message: string; timestamp?: number }>;
}

export interface StorageDetail {
  enabledText: string;
  statusText: string;
  tone: string;
  responseTimeText: string;
  message: string;
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
  return {
    totalText: `${numberValue(data.totalExceptions ?? data.totalCount ?? data.errorCount, 0)} 次`,
    topCategories: entriesByCount(data.byCategory),
    topDevices: entriesByCount(data.byDevice),
    recent: arrayValue(data.recent).map((item) => {
      const row = asRecord(item);
      return {
        deviceId: String(row.deviceId || "-"),
        pointId: String(row.pointId || "-"),
        category: String(row.category || row.type || "UNKNOWN"),
        message: String(row.message || row.error || "-"),
        timestamp: optionalNumber(row.timestamp)
      };
    })
  };
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

function entriesByCount(value: unknown): Array<{ name: string; count: number }> {
  return Object.entries(asRecord(value))
    .map(([name, count]) => ({ name, count: numberValue(count, 0) }))
    .sort((left, right) => right.count - left.count);
}

function storageStatusText(status: string): string {
  return ({ OK: "正常", UP: "正常", ONLINE: "正常", SUCCESS: "正常", ERROR: "异常", DISABLED: "未启用", UNKNOWN: "未知" } as Record<string, string>)[status] || status;
}

function percentText(value: unknown): string {
  const number = optionalNumber(value);
  if (number === undefined) {
    return "-";
  }
  const ratio = number > 1 && number <= 100 ? number / 100 : number;
  return `${Math.round(ratio * 100)}%`;
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
