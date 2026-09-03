import type {
  DeviceShadowDeltaResponse,
  DeviceShadowResponse,
  ShadowDesiredUpdateRequest,
  ShadowHistoryDocument
} from "@/types/shadow";

export interface ShadowHistoryRow {
  deviceId?: string;
  action?: string;
  baseVersion?: number | string;
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
  current: DeviceShadowResponse | ShadowPanelStateMessage;
  desired: ShadowDesiredUpdateRequest | unknown;
  delta: DeviceShadowDeltaResponse | ShadowPanelStateMessage;
  history: ShadowHistoryRow[];
}

export interface ShadowPanelStateMessage {
  message?: string;
  error?: string;
}

const SHADOW_RESERVED_KEYS = new Set([
  "id",
  "messageId",
  "version",
  "method",
  "deviceId",
  "timestamp",
  "source",
  "shadowVersion",
  "expectedVersion"
]);

export function normalizeShadowHistoryRows(response: ShadowHistoryDocument[] | unknown): ShadowHistoryRow[] {
  return extractRows(response, ["records", "rows", "items", "data", "history", "versions"]) as ShadowHistoryRow[];
}

export function summarizeShadowState(
  current: DeviceShadowResponse | ShadowPanelStateMessage | unknown,
  desired: ShadowDesiredUpdateRequest | unknown,
  delta: DeviceShadowDeltaResponse | ShadowPanelStateMessage | unknown,
  history: ShadowHistoryRow[]
): ShadowStateSummary {
  const currentState = extractCurrentShadowState(current);
  const desiredState = extractDesiredShadowState(desired);
  const deltaState = extractDeltaShadowState(delta);
  return {
    currentCount: countRecordKeys(currentState),
    desiredCount: countRecordKeys(desiredState),
    deltaCount: countRecordKeys(deltaState),
    historyCount: history.length,
    currentText: `${countRecordKeys(currentState)} 项`,
    desiredText: `${countRecordKeys(desiredState)} 项`,
    deltaText: `${countRecordKeys(deltaState)} 项`
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

export function buildShadowExportPayload(
  deviceId: string,
  current: DeviceShadowResponse | ShadowPanelStateMessage,
  desired: ShadowDesiredUpdateRequest | unknown,
  delta: DeviceShadowDeltaResponse | ShadowPanelStateMessage,
  history: ShadowHistoryRow[],
  generatedAt = new Date().toISOString()
): ShadowExportPayload {
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

function extractCurrentShadowState(value: unknown): Record<string, unknown> {
  const record = extractShadowRecord(value);
  return extractNestedRecord(record.state, "reported");
}

function extractDeltaShadowState(value: unknown): Record<string, unknown> {
  const record = extractShadowRecord(value);
  if (record.delta && typeof record.delta === "object" && !Array.isArray(record.delta)) {
    return record.delta as Record<string, unknown>;
  }
  return {};
}

function extractDesiredShadowState(value: unknown): Record<string, unknown> {
  const record = extractShadowRecord(value);
  const fromState = extractNestedRecord(record.state, "desired");
  if (Object.keys(fromState).length > 0) {
    return fromState;
  }
  const directKeys = ["desired", "properties", "params"] as const;
  for (const key of directKeys) {
    const nested = extractDirectRecord(record[key]);
    if (Object.keys(nested).length > 0) {
      return nested;
    }
  }
  const direct = Object.entries(record).filter(([key]) => !SHADOW_RESERVED_KEYS.has(key));
  return direct.length > 0 ? Object.fromEntries(direct) : {};
}

function extractNestedRecord(root: unknown, key: string): Record<string, unknown> {
  if (!root || typeof root !== "object" || Array.isArray(root)) {
    return {};
  }
  return extractDirectRecord((root as Record<string, unknown>)[key]);
}

function extractDirectRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  return value as Record<string, unknown>;
}

function countRecordKeys(value: Record<string, unknown>): number {
  return Object.keys(value).length;
}
