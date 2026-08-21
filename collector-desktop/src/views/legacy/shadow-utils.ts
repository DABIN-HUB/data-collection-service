export interface ShadowHistoryRow {
  timestamp?: number | string;
  time?: number | string;
  version?: number | string;
  operation?: string;
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
