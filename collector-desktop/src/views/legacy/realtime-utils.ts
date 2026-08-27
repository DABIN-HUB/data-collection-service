import type { RealtimePointRow } from "@/types/monitor";

export interface RealtimeSummary {
  total: number;
  good: number;
  bad: number;
}

export function normalizeRealtimeRows(response: unknown, fallbackDeviceId = ""): RealtimePointRow[] {
  if (Array.isArray(response)) {
    return response as RealtimePointRow[];
  }
  if (!response || typeof response !== "object") {
    return [];
  }
  const record = response as Record<string, unknown>;
  const deviceId = String(record.deviceId || fallbackDeviceId || "");
  for (const key of ["points", "values", "rows", "items", "devices"]) {
    if (Array.isArray(record[key])) {
      return (record[key] as RealtimePointRow[]).map((row) => attachDeviceId(row, deviceId));
    }
  }
  const data = record.data;
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

export function normalizeSinglePointRealtimeRow(response: unknown): RealtimePointRow | null {
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

export function buildRealtimeSummary(rows: RealtimePointRow[]): RealtimeSummary {
  const total = rows.length;
  const good = rows.filter(isGoodQuality).length;
  return {
    total,
    good,
    bad: total - good
  };
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

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
