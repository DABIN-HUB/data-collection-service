import type { RealtimePointRow } from "@/types/monitor";

export const REALTIME_PAGE_SIZE_OPTIONS = [100, 200, 500] as const;
export const DEFAULT_REALTIME_PAGE_SIZE = 200;
export const MAX_REALTIME_PAGE_SIZE = 500;

export type RealtimePageSize = typeof REALTIME_PAGE_SIZE_OPTIONS[number];

export interface RealtimePageWindowInput {
  total: number;
  page: number;
  pageSize: number;
}

export interface RealtimePageWindow {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  startIndex: number;
  endIndex: number;
}

export interface RealtimeDeviceDisplay {
  normalizedId?: string | null;
  displayName?: string | null;
}

export function normalizeRealtimePageSize(pageSize: number): RealtimePageSize {
  if (!Number.isFinite(pageSize)) {
    return DEFAULT_REALTIME_PAGE_SIZE;
  }
  if (pageSize <= 100) {
    return 100;
  }
  if (pageSize <= 200) {
    return 200;
  }
  return MAX_REALTIME_PAGE_SIZE;
}

export function resetRealtimePage(): number {
  return 1;
}

export function clampRealtimePage(page: number, total: number, pageSize: number): number {
  return buildRealtimePageWindow({ total, page, pageSize }).page;
}

export function buildRealtimePageWindow(input: RealtimePageWindowInput): RealtimePageWindow {
  const pageSize = normalizeRealtimePageSize(input.pageSize);
  const total = normalizeTotal(input.total);
  const totalPages = total === 0 ? 0 : Math.ceil(total / pageSize);
  const requestedPage = normalizeRequestedPage(input.page);
  const page = totalPages === 0 ? 1 : Math.min(Math.max(requestedPage, 1), totalPages);
  const startIndex = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const endIndex = total === 0 ? 0 : Math.min(page * pageSize, total);
  return {
    page,
    pageSize,
    total,
    totalPages,
    startIndex,
    endIndex
  };
}

export function getPagedRealtimeRows<T>(rows: T[], pageWindow: RealtimePageWindow): T[] {
  if (pageWindow.total === 0 || pageWindow.startIndex === 0 || pageWindow.endIndex === 0) {
    return [];
  }
  return rows.slice(pageWindow.startIndex - 1, pageWindow.endIndex);
}

export function buildRealtimeDeviceNameLookup(devices: RealtimeDeviceDisplay[]): Map<string, string> {
  const lookup = new Map<string, string>();
  for (const device of devices) {
    const normalizedId = normalizeDeviceId(device.normalizedId);
    if (!normalizedId || lookup.has(normalizedId)) {
      continue;
    }
    const displayName = normalizeDeviceId(device.displayName) || normalizedId;
    lookup.set(normalizedId, displayName);
  }
  return lookup;
}

export function resolveRealtimeDeviceName(lookup: ReadonlyMap<string, string>, deviceId: string | null | undefined): string {
  const normalizedId = normalizeDeviceId(deviceId);
  if (!normalizedId) {
    return "-";
  }
  return lookup.get(normalizedId) || normalizedId;
}

export function filterRealtimeRows(
  rows: RealtimePointRow[],
  keyword: string,
  deviceNameLookup: ReadonlyMap<string, string>,
  fallbackDeviceId = ""
): RealtimePointRow[] {
  const normalizedKeyword = keyword.trim().toLowerCase();
  if (!normalizedKeyword) {
    return rows;
  }
  return rows.filter((row) => {
    const rowDeviceId = String(row.deviceId || fallbackDeviceId || "");
    const searchableValues = [
      row.pointName,
      row.pointCode,
      realtimeRowAddress(row),
      row.deviceName || resolveRealtimeDeviceName(deviceNameLookup, rowDeviceId)
    ];
    return searchableValues.some((value) => String(value || "").toLowerCase().includes(normalizedKeyword));
  });
}

function realtimeRowAddress(row: RealtimePointRow): string {
  return String(row.address || row.registerAddress || row.pointAddress || "-");
}

function normalizeTotal(total: number): number {
  if (!Number.isFinite(total) || total <= 0) {
    return 0;
  }
  return Math.floor(total);
}

function normalizeRequestedPage(page: number): number {
  if (!Number.isFinite(page) || page < 1) {
    return 1;
  }
  return Math.floor(page);
}

function normalizeDeviceId(value: string | null | undefined): string {
  if (value === undefined || value === null) {
    return "";
  }
  return String(value).trim();
}
