export interface HistoryRow {
  event_ts?: number | string;
  ts?: number | string;
  value_text?: unknown;
  timestamp?: number | string;
  time?: number | string;
  value?: unknown;
  [key: string]: unknown;
}

export function historyEventTime(row: HistoryRow): number | null {
  for (const raw of [row.event_ts, row.eventTs, row.ts, row.timestamp, row.time, row.collectTime, row.createdAt]) {
    if (raw === null || raw === undefined || raw === "") {
      continue;
    }
    const parsed = typeof raw === "number" ? raw
      : typeof raw === "string" ? /^\d+$/.test(raw.trim()) ? Number(raw.trim()) : new Date(raw).getTime()
        : NaN;
    if (Number.isFinite(parsed) && !Number.isNaN(new Date(parsed).getTime())) {
      return parsed;
    }
  }
  return null;
}

export function historyDisplayValue(row: HistoryRow): unknown {
  return row.value_text ?? row.valueText ?? row.value ?? row.currentValue ?? row.rawValue ?? row.val ?? "-";
}

export function historyNumericValue(row: HistoryRow): number | undefined {
  const raw = historyDisplayValue(row);
  if (raw === null || raw === "-" || typeof raw === "boolean" || (typeof raw === "string" && !raw.trim())) {
    return undefined;
  }
  const value = Number(raw);
  return Number.isFinite(value) ? value : undefined;
}

export function formatHistoryTime(row: HistoryRow): string {
  const timestamp = historyEventTime(row);
  if (timestamp !== null) {
    return new Date(timestamp).toLocaleString();
  }
  const raw = row.event_ts ?? row.eventTs ?? row.ts ?? row.timestamp ?? row.time ?? row.collectTime ?? row.createdAt;
  return raw === null || raw === undefined || raw === "" ? "-" : String(raw);
}

export function sortHistoryRowsAscending(rows: HistoryRow[]): HistoryRow[] {
  // 后端按事件时间倒序截取最近记录；绘图使用副本升序，保留原始表格顺序和完整字段。
  return [...rows].sort((left, right) => {
    const leftTime = historyEventTime(left);
    const rightTime = historyEventTime(right);
    if (leftTime === null && rightTime === null) return 0;
    if (leftTime === null) return 1;
    if (rightTime === null) return -1;
    return leftTime - rightTime;
  });
}

export function latestHistoryRow(rows: HistoryRow[]): HistoryRow | undefined {
  return rows.reduce<HistoryRow | undefined>((latest, row) => {
    const timestamp = historyEventTime(row);
    return timestamp !== null && (latest === undefined || timestamp > (historyEventTime(latest) ?? -Infinity)) ? row : latest;
  }, undefined);
}

export function latestHistoryValueText(rows: HistoryRow[]): string {
  const latest = latestHistoryRow(rows);
  return latest ? String(historyDisplayValue(latest)) : "-";
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
