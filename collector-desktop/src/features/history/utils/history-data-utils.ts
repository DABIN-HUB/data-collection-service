export interface HistoryRow {
  timestamp?: number | string;
  time?: number | string;
  value?: unknown;
  [key: string]: unknown;
}

export function normalizeHistoryRows(response: unknown): HistoryRow[] {
  const rows = extractRows(response, ["records", "rows", "items", "data", "values", "points"]);
  return rows as HistoryRow[];
}

function extractRows(value: unknown, keys: string[]): unknown[] {
  if (Array.isArray(value)) {
    return value;
  }
  if (value && typeof value === "object") {
    const body = value as Record<string, unknown>;
    for (const key of keys) {
      if (Array.isArray(body[key])) {
        return body[key] as unknown[];
      }
      const nestedRows = extractRows(body[key], keys);
      if (nestedRows.length > 0) {
        return nestedRows;
      }
    }
  }
  return [];
}
