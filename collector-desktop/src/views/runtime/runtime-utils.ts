export interface DeviceOption {
  id: string;
  name: string;
  protocol: string;
  host: string;
  port?: number;
}

export interface HistoryRow {
  timestamp?: number | string;
  time?: number | string;
  value?: unknown;
  [key: string]: unknown;
}

export function normalizeDeviceOptions(response: unknown): DeviceOption[] {
  const rows = Array.isArray(response)
    ? response
    : response && typeof response === "object" && Array.isArray((response as Record<string, unknown>).devices)
      ? (response as Record<string, unknown>).devices as unknown[]
      : [];
  return rows.map((item) => {
    const row = item as Record<string, unknown>;
    const id = String(row.id || row.deviceId || row.connectionKey || "");
    const port = Number(row.port);
    return {
      id,
      name: String(row.deviceName || row.name || row.deviceAlias || id),
      protocol: String(row.protocolType || row.connectionType || row.protocol || ""),
      host: String(row.host || row.ipAddress || row.url || ""),
      port: Number.isFinite(port) ? port : undefined
    };
  }).filter((item) => item.id);
}

export function buildSinglePointWritePayload(pointRef: string, rawValue: string, dataType = "STRING"): Record<string, unknown> {
  return {
    pointRef: pointRef.trim(),
    value: parseValueByType(rawValue, dataType),
    dataType
  };
}

export function buildBatchWriteTemplate(): Record<string, unknown> {
  return {
    values: {
      point_code: 1
    }
  };
}

export function buildCommandTemplate(command = "status"): Record<string, unknown> {
  return {
    command,
    params: {}
  };
}

export function normalizeHistoryRows(response: unknown): HistoryRow[] {
  if (Array.isArray(response)) {
    return response as HistoryRow[];
  }
  if (response && typeof response === "object") {
    const body = response as Record<string, unknown>;
    for (const key of ["records", "rows", "items", "data", "values", "points"]) {
      if (Array.isArray(body[key])) {
        return body[key] as HistoryRow[];
      }
    }
  }
  return [];
}

export function parseJsonOrThrow<T = unknown>(text: string, label: string): T {
  try {
    return JSON.parse(text) as T;
  } catch (error) {
    const message = error instanceof Error ? error.message : "JSON 解析失败";
    throw new Error(`${label} 格式错误：${message}`);
  }
}

function parseValueByType(rawValue: string, dataType: string): unknown {
  const normalizedType = dataType.toUpperCase();
  const trimmed = rawValue.trim();
  if (["BOOLEAN", "BOOL"].includes(normalizedType)) {
    return trimmed === "true" || trimmed === "1" || trimmed === "是";
  }
  if (["BYTE", "SHORT", "INT", "INTEGER", "LONG", "FLOAT", "DOUBLE", "UINT", "UINT16", "UINT32", "DINT"].includes(normalizedType)) {
    const numberValue = Number(trimmed);
    return Number.isFinite(numberValue) ? numberValue : trimmed;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return trimmed;
  }
}
