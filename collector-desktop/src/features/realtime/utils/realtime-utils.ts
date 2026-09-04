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
  if (typeof value === "number") {
    return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)));
  }
  return String(value ?? "-");
}

export function realtimeQualityText(row: RealtimePointRow): string {
  const quality = String(valueOf(row, ["qualityLevel", "qualityDescription", "quality", "qualityCode", "status"], "UNKNOWN"));
  if (row.qualityAvailable === false) {
    return "未评估";
  }
  if (row.qualityAcceptable === false || row.processSuccess === false) {
    return quality === "UNKNOWN" ? "异常" : quality;
  }
  switch (quality.toUpperCase()) {
    case "GOOD":
    case "OK":
    case "SUCCESS":
      return "良好";
    case "BAD":
    case "ERROR":
      return "异常";
    default:
      return quality || "未知";
  }
}

export function realtimeQualityClass(row: RealtimePointRow): string {
  const quality = String(valueOf(row, ["qualityLevel", "quality", "qualityCode", "status"], "UNKNOWN")).toUpperCase();
  if (row.qualityAvailable === false || row.qualityAcceptable === false || row.processSuccess === false) {
    return "is-bad";
  }
  if (["GOOD", "OK", "SUCCESS", "100"].includes(quality)) {
    return "is-good";
  }
  if (["BAD", "ERROR", "FAILED"].includes(quality)) {
    return "is-bad";
  }
  return "";
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
  if (row.qualityAvailable === false || row.qualityAcceptable === false || row.processSuccess === false) {
    return false;
  }
  const value = String(row.qualityLevel || row.quality || row.status || "").toUpperCase();
  return ["A", "GOOD", "OK", "SUCCESS", "ONLINE", "1", "100"].includes(value);
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
