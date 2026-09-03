import type { ApiResult } from "@/types/api";
import type { DeviceRuntimeSnapshot } from "@/types/device";
import type {
  DevicePerformanceResponse,
  DeviceStatisticsResponse,
  DeviceStatusResponse
} from "@/types/device";

export interface DeviceStatusDetail extends DeviceRuntimeSnapshot {
  isRunning?: boolean;
  message?: string;
  statistics?: DeviceStatisticsResponse;
  performance?: DevicePerformanceResponse;
}

export interface DeviceRuntimeSummary {
  total: number;
  running: number;
  connected: number;
  abnormal: number;
}

export function normalizeRunningDeviceIds(response: unknown): string[] {
  const source = unwrapData(response);
  if (!Array.isArray(source)) {
    return [];
  }
  return source.map((item) => {
    if (typeof item === "string" || typeof item === "number") {
      return String(item);
    }
    const record = asRecord(item);
    return String(record.deviceId || record.id || "");
  }).filter(Boolean);
}

export function normalizeDeviceRuntimeRows(response: unknown): DeviceRuntimeSnapshot[] {
  const source = unwrapData(response);
  if (!Array.isArray(source)) {
    return [];
  }
  return source.map((item) => normalizeRuntimeRow(asRecord(item))).filter((row) => row.deviceId);
}

export function normalizeDeviceStatusDetail(response: DeviceStatusResponse | ApiResult<DeviceStatusResponse> | unknown, fallbackDeviceId = ""): DeviceStatusDetail {
  const record = asRecord(response);
  const data = asRecord(record.data);
  const source = Object.keys(data).length ? data : record;
  const running = booleanValue(source.running ?? source.isRunning);
  return {
    ...normalizeRuntimeRow({ ...source, running }),
    isRunning: running,
    message: String(record.msg || record.message || source.message || ""),
    statistics: asNestedObject<DeviceStatisticsResponse>(source.statistics),
    performance: asNestedObject<DevicePerformanceResponse>(source.performance),
    deviceId: String(source.deviceId || fallbackDeviceId || "")
  };
}

export function normalizeDeviceRunningFlag(response: unknown): boolean {
  if (typeof response === "boolean") {
    return response;
  }
  const record = asRecord(response);
  if (typeof record.data === "boolean") {
    return record.data;
  }
  return Boolean(record.running ?? record.isRunning ?? false);
}

export function buildDeviceRuntimeSummary(rows: DeviceRuntimeSnapshot[], fallbackTotal?: number): DeviceRuntimeSummary {
  const total = typeof fallbackTotal === "number" && Number.isFinite(fallbackTotal) ? fallbackTotal : rows.length;
  const running = rows.filter((row) => row.running).length;
  const connected = rows.filter((row) => row.connected).length;
  const abnormal = rows.filter((row) => Boolean(row.reconnecting || row.degradedReason || Number(row.consecutiveFailures || 0) > 0 || (row.running && !row.connected))).length;
  return { total, running, connected, abnormal };
}

function normalizeRuntimeRow(record: Record<string, unknown>): DeviceRuntimeSnapshot {
  return {
    deviceId: String(record.deviceId || record.id || ""),
    phase: textValue(record.phase),
    running: booleanValue(record.running ?? record.isRunning),
    starting: booleanValue(record.starting ?? record.isStarting),
    connected: booleanValue(record.connected),
    reconnecting: booleanValue(record.reconnecting),
    reconnectNextRetryAt: numberValue(record.reconnectNextRetryAt),
    startedAt: numberValue(record.startedAt),
    generation: numberValue(record.generation),
    lastSuccessfulCollectionAt: numberValue(record.lastSuccessfulCollectionAt),
    consecutiveFailures: numberValue(record.consecutiveFailures),
    backoffUntil: numberValue(record.backoffUntil),
    degradedReason: textValue(record.degradedReason),
    generatedAt: numberValue(record.generatedAt)
  };
}

function unwrapData(value: unknown): unknown {
  const record = asRecord(value);
  return Object.keys(record).length && "data" in record ? record.data : value;
}

function booleanValue(value: unknown): boolean {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    return ["true", "1", "yes", "是", "running", "online"].includes(value.toLowerCase());
  }
  return Boolean(value);
}

function numberValue(value: unknown): number | undefined {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function textValue(value: unknown): string | undefined {
  return value === undefined || value === null || value === "" ? undefined : String(value);
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function asNestedObject<T>(value: unknown): T | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as T : undefined;
}
