import type { CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, RealtimePointRow } from "@/types/monitor";

/**
 * 提取紧凑实时响应中的表格行，保留后端 rows 数组和行对象引用。
 */
export function extractCompactRealtimeRows(response: CompactAllDeviceRealtimeDataResponse | CompactDeviceRealtimeDataResponse | unknown): RealtimePointRow[] {
  if (!response || typeof response !== "object") {
    return [];
  }
  const rows = (response as { rows?: unknown }).rows;
  if (!Array.isArray(rows)) {
    return [];
  }
  return rows as RealtimePointRow[];
}
