import type { AllDeviceRealtimeDataResponse, DeviceListResponse, DeviceRealtimeDataResponse, PointRealtimePayload, PointRealtimeResponse, RealtimePointRow } from "@/types/monitor";

export interface RealtimeSummary {
  total: number;
  good: number;
  bad: number;
}

export function normalizeRealtimeRows(response: DeviceRealtimeDataResponse | RealtimePointRow[] | unknown, fallbackDeviceId = ""): RealtimePointRow[] {
  if (Array.isArray(response)) {
    return response as RealtimePointRow[];
  }
  if (!response || typeof response !== "object") {
    return [];
  }
  const record = response as Record<string, unknown>;
  const deviceId = String(record.deviceId || fallbackDeviceId || "");
  const data = record.data;
  if (isPointPayloadMap(data)) {
    return pointPayloadMapToRows(data, deviceId);
  }
  for (const key of ["points", "values", "rows", "items"]) {
    if (Array.isArray(record[key])) {
      return (record[key] as RealtimePointRow[]).map((row) => attachDeviceId(row, deviceId));
    }
  }
  if (Array.isArray(data)) {
    return (data as RealtimePointRow[]).map((row) => attachDeviceId(row, deviceId));
  }
  if (data && typeof data === "object") {
    return Object.entries(data as Record<string, unknown>).map(([pointId, value]) => {
      if (value && typeof value === "object" && !Array.isArray(value)) {
        return attachDeviceId({ pointId, ...(value as RealtimePointRow) }, deviceId);
      }
      return attachDeviceId({ pointId, value }, deviceId);
    });
  }
  const pointMapRows = normalizeTopLevelPointMap(record, deviceId);
  if (pointMapRows.length) {
    return pointMapRows;
  }
  if (record.pointId || record.pointCode || record.value !== undefined || record.currentValue !== undefined) {
    return [attachDeviceId(record as RealtimePointRow, deviceId)];
  }
  return [];
}

export function extractRealtimeDeviceIds(response: DeviceListResponse | RealtimePointRow[] | unknown): string[] {
  if (!response || typeof response !== "object") {
    return [];
  }
  const record = response as Record<string, unknown>;
  const devices = Array.isArray(record.devices) ? record.devices : [];
  const primaryIds = devices
    .map((device) => String(asRecord(device).deviceId || ""))
    .filter(Boolean);
  if (primaryIds.length) {
    return Array.from(new Set(primaryIds));
  }
  return Array.from(new Set(normalizeRealtimeRows(response).map((row) => String(row.deviceId || "")).filter(Boolean)));
}

export function normalizeAllDeviceRealtimeRows(response: AllDeviceRealtimeDataResponse | unknown): RealtimePointRow[] {
  const record = asRecord(response);
  const devices = Array.isArray(record.devices) ? record.devices : [];
  return devices.flatMap((device) => {
    const deviceRecord = asRecord(device);
    if (String(deviceRecord.status || "").toLowerCase() === "error") {
      return [];
    }
    const deviceId = String(deviceRecord.deviceId || "");
    return normalizeRealtimeRows(deviceRecord as DeviceRealtimeDataResponse, deviceId);
  });
}

export function normalizeSinglePointRealtimeRow(response: PointRealtimeResponse | PointRealtimePayload | unknown): RealtimePointRow | null {
  const record = asRecord(response);
  if (!Object.keys(record).length) {
    return null;
  }
  const data = asRecord(record.data);
  if (Object.keys(data).length) {
    return {
      deviceId: String(data.deviceId || record.deviceId || ""),
      pointId: String(data.pointId || record.pointId || ""),
      ...data
    } as RealtimePointRow;
  }
  return record as RealtimePointRow;
}

function pointPayloadMapToRows(data: Record<string, PointRealtimePayload>, deviceId: string): RealtimePointRow[] {
  return Object.entries(data).map(([pointId, value]) => attachDeviceId({ pointId, ...value }, deviceId));
}

function isPointPayloadMap(value: unknown): value is Record<string, PointRealtimePayload> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  return Object.values(value).every((item) => Boolean(item) && typeof item === "object" && !Array.isArray(item));
}

export function buildRealtimeSummary(rows: RealtimePointRow[]): RealtimeSummary {
  const total = rows.length;
  const good = rows.filter(isGoodQuality).length;
  return {
    total,
    good,
    bad: total - good
  };
}

export function realtimeAddress(row: RealtimePointRow): string {
  return String(valueOf(row, ["address", "registerAddress", "pointAddress"], "-"));
}

export function realtimeScale(row: RealtimePointRow): string {
  return String(valueOf(row, ["scalingFactor", "scale", "factor"], "-"));
}

export function realtimeValueText(row: RealtimePointRow): string {
  const value = valueOf(row, ["value", "currentValue", "rawValue"], "-");
  const text = typeof value === "number"
    ? (Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4))))
    : String(value ?? "-");
  return row.stale ? `${text}（旧值）` : text;
}
export function realtimeQualityText(row: RealtimePointRow): string {
  if (row.qualityAvailable === false) {
    return "未评估";
  }
  if (row.qualityDescription) {
    return row.qualityDescription;
  }
  const quality = String(valueOf(row, ["qualityLevel", "quality", "qualityCode"], "UNKNOWN")).toUpperCase();
  const labels: Record<string, string> = {
    A: "良好", GOOD: "良好", OK: "良好", SUCCESS: "良好", "100": "良好",
    B: "处理错误", C: "不确定", UNCERTAIN: "不确定", "50": "不确定",
    BAD: "异常", ERROR: "异常", FAILED: "失败", D: "异常", "0": "异常",
    "10": "配置错误", "20": "设备错误", "30": "超时", "40": "值无效",
    "60": "处理错误", "70": "缓存错误"
  };
  if (row.qualityAcceptable === false || row.processSuccess === false) {
    return labels[quality] === "良好" ? "异常" : labels[quality] || "异常";
  }
  return labels[quality] || "未知";
}

export function realtimeQualityClass(row: RealtimePointRow): string {
  if (row.qualityAvailable === false) {
    return "";
  }
  if (row.qualityAcceptable === false || row.processSuccess === false) {
    return "is-bad";
  }
  const quality = String(valueOf(row, ["qualityLevel", "quality", "qualityCode"], "UNKNOWN")).toUpperCase();
  if (["A", "GOOD", "OK", "SUCCESS", "100"].includes(quality) && row.stale !== true) {
    return "is-good";
  }
  if (["BAD", "ERROR", "FAILED", "D", "0", "10", "20", "30", "40", "60", "70"].includes(quality)) {
    return "is-bad";
  }
  return "";
}

export function realtimeStatusText(row: RealtimePointRow): string {
  switch (String(row.realtimeStatus || "UNASSESSED").toUpperCase()) {
    case "GOOD": return "正常";
    case "WAITING": return "等待首次采集";
    case "CONNECTING": return "连接中";
    case "DISCONNECTED": return "连接已断开";
    case "COLLECT_ERROR": return "采集失败";
    case "BAD": return "采集失败";
    case "PROCESS_ERROR": return "处理失败";
    case "NO_VALUE": return "暂无有效采集值";
    case "CONFIG_ERROR": return "配置错误";
    case "COMM_ERROR": return "通信失败";
    case "MAPPING_ERROR": return "映射错误";
    case "DECODE_ERROR": return "解码失败";
    case "STALE": return row.failureType && row.failureType !== "STALE"
      ? `旧值 / 数据已过期（${realtimeFailureText(row.failureType)}）` : "旧值 / 数据已过期";
    case "UNASSESSED": return "未评估";
    default: return String(row.realtimeStatus || "未评估");
  }
}

function realtimeFailureText(failureType: string): string {
  switch (failureType) {
    case "CONFIG_ERROR": return "配置错误";
    case "COMM_ERROR": return "通信失败";
    case "MAPPING_ERROR": return "映射错误";
    case "DECODE_ERROR": return "解码失败";
    case "NO_VALUE": return "无有效值";
    case "BAD": return "采集失败";
    default: return failureType;
  }
}

export function realtimeDeviceHealthText(health?: string): string {
  switch (health) {
    case "OFFLINE": return "离线";
    case "ONLINE_NO_DATA": return "在线无数据";
    case "ONLINE_PARTIAL": return "部分点位有效";
    case "ONLINE_HEALTHY": return "健康";
    case "DEGRADED": return "运行降级";
    default: return health || "未知";
  }
}

export function realtimeErrorText(row: RealtimePointRow): string {
  return String(row.errorMessage || row.processMessage || realtimeStatusText(row));
}

export function realtimeProcessingText(row: RealtimePointRow): string {
  const value = valueOf(row, ["processCostMs", "processingTime", "costMs", "elapsedMs"], "-");
  return typeof value === "number" ? `${value} ms` : String(value || "-");
}


function attachDeviceId(row: RealtimePointRow, fallbackDeviceId: string): RealtimePointRow {
  if (!fallbackDeviceId || row.deviceId) {
    return row;
  }
  return { ...row, deviceId: fallbackDeviceId };
}

function normalizeTopLevelPointMap(record: Record<string, unknown>, fallbackDeviceId: string): RealtimePointRow[] {
  const entries = Object.entries(record).filter(([key]) => !["status", "message", "timestamp", "deviceId", "dataCount", "success", "code"].includes(key));
  if (!entries.length) {
    return [];
  }
  const rows: RealtimePointRow[] = [];
  for (const [pointId, value] of entries) {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      const row = value as RealtimePointRow;
      if (!looksLikeRealtimePoint(row)) {
        return [];
      }
      rows.push(attachDeviceId({ pointId, ...row }, fallbackDeviceId));
    } else if (fallbackDeviceId) {
      rows.push(attachDeviceId({ pointId, value }, fallbackDeviceId));
    } else {
      return [];
    }
  }
  return rows;
}

function looksLikeRealtimePoint(row: RealtimePointRow): boolean {
  return Boolean(row.pointId || row.pointCode || row.pointName || row.value !== undefined || row.currentValue !== undefined || row.rawValue !== undefined || row.processedValue !== undefined);
}

function isGoodQuality(row: RealtimePointRow): boolean {
  if (row.qualityAvailable === false || row.qualityAcceptable === false || row.processSuccess === false || row.stale === true) {
    return false;
  }
  if (row.realtimeStatus && (row.realtimeStatus !== "GOOD" || row.value === null || row.value === undefined)) {
    return false;
  }
  const quality = String(valueOf(row, ["qualityLevel", "quality", "qualityCode"], "")).toUpperCase();
  return ["A", "GOOD", "OK", "SUCCESS", "100"].includes(quality);
}

function valueOf(row: RealtimePointRow, keys: string[], fallback: unknown): unknown {
  for (const key of keys) {
    const value = row[key];
    if (value !== undefined && value !== null) {
      return value;
    }
  }
  return fallback;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
