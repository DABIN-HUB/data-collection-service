export interface ShadowHistoryRow {
  timestamp?: number | string;
  time?: number | string;
  version?: number | string;
  operation?: string;
  [key: string]: unknown;
}

export function normalizeShadowHistoryRows(response: unknown): ShadowHistoryRow[] {
  return extractRows(response, ["records", "rows", "items", "data", "history", "versions"]) as ShadowHistoryRow[];
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
