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

export function runtimePhaseLabel(runtime: DeviceRuntimeSnapshot | undefined): string {
  switch (runtime?.phase) {
    case "STOPPED": return "已停止";
    case "STARTING": return "启动中";
    case "CONNECTING": return "连接中";
    case "WAITING_FIRST_SAMPLE": return "等待首采";
    case "ONLINE": return "运行中";
    case "DEGRADED": return "采集降级";
    case "RECONNECTING": return "重连中";
    case "FAILED": return "运行失败";
    default: return "状态未知";
  }
}
export function runtimeOperationMessage(runtime: DeviceRuntimeSnapshot | undefined, action: "START" | "STOP"): string {
  if (action === "START" && !runtime?.phase) return "启动请求已受理，等待首采；当前采集健康未知";
  if (action === "STOP") return runtime?.phase === "STOPPED" ? "设备已停止" : "设备停止操作已完成";
  switch (runtime?.phase) {
    case "ONLINE": return "设备运行中；请在实时数据核对当前点位质量";
    case "WAITING_FIRST_SAMPLE": return "设备已启动并建立连接，正在等待首轮有效采集";
    case "DEGRADED": return `设备已启动，但当前采集处于降级状态${runtime.degradedReason ? `：${runtime.degradedReason}` : ""}`;
    case "FAILED": return `设备启动失败${runtime.degradedReason ? `：${runtime.degradedReason}` : ""}`;
    default: return runtimePhaseLabel(runtime);
  }
}


// 三层事实只展示后端返回值，ready、accepted 和连接均不能推导采集健康。
export function runtimePresentation(runtime?: DeviceRuntimeSnapshot, stale = false) {
  const labels: Record<string, string> = {
    UNKNOWN: "未知", RUNNING: "运行", STOPPED: "停止", CONNECTED: "已连接", DISCONNECTED: "未连接",
    CONNECTING: "连接中", RECONNECTING: "重连中", NEGOTIATING: "协商中", READY: "已就绪", NOT_READY: "未就绪",
    ONLINE_HEALTHY: "在线健康", ONLINE_PARTIAL: "在线部分有效", ONLINE_NO_DATA: "在线无有效数据", OFFLINE: "离线",
    HEALTHY: "健康", GOOD: "正常", DEGRADED: "降级", FAILED: "失败", UNHEALTHY: "不健康",
    WAITING: "等待", WAITING_FIRST_SAMPLE: "等待首采", STALE: "过期", ERROR: "异常"
  };
  const label = (value?: string) => value ? labels[value] || value : "未知";
  const count = (value?: number) => value === undefined ? "未知" : String(value);
  return {
    lifecycle: stale ? "快照已过期" : runtimePhaseLabel(runtime),
    desired: label(runtime?.desiredState),
    transportProtocol: stale ? "传输 未知 / 协议 未知（快照过期）" : `传输 ${label(runtime?.transport)} / 协议 ${label(runtime?.protocol)}`,
    health: stale ? "已过期（当前健康未知）" : label(runtime?.deviceHealth),
    ready: stale ? "未知" : runtime?.ready === true ? "已就绪" : runtime?.ready === false ? "未就绪" : "未知",
    points: `有效 ${count(runtime?.goodPointCount)}/${count(runtime?.participatingPointCount ?? runtime?.configuredPointCount)} · 失败 ${count(runtime?.failedPointCount)} · 过期 ${count(runtime?.stalePointCount)} · 等待 ${count(runtime?.waitingPointCount)}`,
    lastValid: runtime?.lastValidSampleAt && runtime.lastValidSampleAt > 0 ? new Date(runtime.lastValidSampleAt).toLocaleString() : "未知",
    reason: runtime?.healthReason || runtime?.degradedReason || runtime?.lastError || ""
  };
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

export function buildUnavailableRunningFlagDetail(deviceId: string, error: string): DeviceStatusDetail {
  return {
    deviceId,
    running: undefined,
    isRunning: undefined,
    message: "运行状态：暂不可用",
    degradedReason: error || "运行状态检查失败"
  };
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
    generatedAt: numberValue(record.generatedAt),
    ready: booleanValue(record.ready),
    firstSampleAt: numberValue(record.firstSampleAt),
    configuredPointCount: numberValue(record.configuredPointCount),
    lastError: textValue(record.lastError),
    configVersion: numberValue(record.configVersion),
    desiredState: textValue(record.desiredState),
    transport: textValue(record.transport),
    protocol: textValue(record.protocol),
    deviceHealth: textValue(record.deviceHealth),
    healthReason: textValue(record.healthReason),
    goodPointCount: numberValue(record.goodPointCount),
    failedPointCount: numberValue(record.failedPointCount),
    stalePointCount: numberValue(record.stalePointCount),
    waitingPointCount: numberValue(record.waitingPointCount),
    lastValidSampleAt: numberValue(record.lastValidSampleAt),
    participatingPointCount: numberValue(record.participatingPointCount)
  };
}

function unwrapData(value: unknown): unknown {
  const record = asRecord(value);
  return Object.keys(record).length && "data" in record ? record.data : value;
}

function booleanValue(value: unknown): boolean | undefined {
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    return ["true", "1", "yes", "是", "running", "online"].includes(value.toLowerCase());
  }
  return Boolean(value);
}

function numberValue(value: unknown): number | undefined {
  if (value === undefined || value === null || value === "") return undefined;
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
