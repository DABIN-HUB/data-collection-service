export interface ShadowHistoryRow {
  timestamp?: number | string;
  time?: number | string;
  createdAt?: number | string;
  updateTime?: number | string;
  version?: number | string;
  operation?: string;
  type?: string;
  [key: string]: unknown;
}

export interface ShadowStateSummary {
  currentCount: number;
  desiredCount: number;
  deltaCount: number;
  historyCount: number;
  currentText: string;
  desiredText: string;
  deltaText: string;
}

export interface ShadowExportPayload {
  deviceId: string;
  generatedAt: string;
  current: unknown;
  desired: unknown;
  delta: unknown;
  history: ShadowHistoryRow[];
}

export function normalizeShadowHistoryRows(response: unknown): ShadowHistoryRow[] {
  return extractRows(response, ["records", "rows", "items", "data", "history", "versions"]) as ShadowHistoryRow[];
}

export function summarizeShadowState(current: unknown, desired: unknown, delta: unknown, history: ShadowHistoryRow[]): ShadowStateSummary {
  return {
    currentCount: countRecordKeys(extractShadowRecord(current)),
    desiredCount: countRecordKeys(extractShadowRecord(desired)),
    deltaCount: countRecordKeys(extractShadowRecord(delta)),
    historyCount: history.length,
    currentText: `${countRecordKeys(extractShadowRecord(current))} 项`,
    desiredText: `${countRecordKeys(extractShadowRecord(desired))} 项`,
    deltaText: `${countRecordKeys(extractShadowRecord(delta))} 项`
  };
}

export function parseShadowJson(text: string): unknown {
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return { raw: text };
  }
}

export function parseShadowJsonOrThrow<T = unknown>(text: string, label: string): T {
  try {
    return JSON.parse(text || "{}") as T;
  } catch (error) {
    const message = error instanceof Error ? error.message : "JSON 解析失败";
    throw new Error(`${label} 格式错误：${message}`, { cause: error });
  }
}

export function formatShadowTime(row: ShadowHistoryRow): string {
  const raw = row.timestamp || row.time || row.createdAt || row.updateTime;
  if (!raw) {
    return "-";
  }
  if (typeof raw !== "string" && typeof raw !== "number") {
    return String(raw);
  }
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? String(raw) : date.toLocaleString();
}

export function compactJson(value: unknown): string {
  return JSON.stringify(value);
}

export function buildShadowExportPayload(deviceId: string, current: unknown, desired: unknown, delta: unknown, history: ShadowHistoryRow[], generatedAt = new Date().toISOString()): ShadowExportPayload {
  return {
    deviceId,
    generatedAt,
    current,
    desired,
    delta,
    history
  };
}

export function buildShadowExportFilename(deviceId: string, generatedAt = new Date().toISOString()): string {
  return `collector-shadow-${deviceId}-${generatedAt.replace(/[:.]/g, "-")}.json`;
}

function extractRows(value: unknown, keys: string[]): unknown[] {
  if (Array.isArray(value)) {
    return value;
  }
  if (!value || typeof value !== "object") {
    return [];
  }
  const body = value as Record<string, unknown>;
  for (const key of keys) {
    const current = body[key];
    if (Array.isArray(current)) {
      return current;
    }
    const nestedRows = extractRows(current, keys);
    if (nestedRows.length > 0) {
      return nestedRows;
    }
  }
  return [];
}

function extractShadowRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  const record = value as Record<string, unknown>;
  if (record.data && typeof record.data === "object" && !Array.isArray(record.data)) {
    return record.data as Record<string, unknown>;
  }
  return record;
}

function countRecordKeys(value: Record<string, unknown>): number {
  return Object.keys(value).length;
}
