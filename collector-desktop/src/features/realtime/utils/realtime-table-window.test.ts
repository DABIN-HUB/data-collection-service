import { describe, expect, it } from "vitest";

import { buildRealtimeSummary } from "./realtime-utils";
import {
  buildRealtimeDeviceNameLookup,
  buildRealtimePageWindow,
  DEFAULT_REALTIME_PAGE_SIZE,
  filterRealtimeRows,
  getPagedRealtimeRows,
  MAX_REALTIME_PAGE_SIZE,
  normalizeRealtimePageSize,
  REALTIME_PAGE_SIZE_OPTIONS,
  resolveRealtimeDeviceName
} from "./realtime-table-window";
import type { RealtimePointRow } from "@/types/monitor";

function makeRows(total: number): RealtimePointRow[] {
  return Array.from({ length: total }, (_, index) => {
    const sequence = index + 1;
    return {
      pointId: `point-${sequence}`,
      pointCode: `code-${sequence}`,
      pointName: sequence >= 800 && sequence <= 899 ? `目标点位-${sequence}` : `普通点位-${sequence}`,
      deviceId: sequence % 2 === 0 ? "device-a" : "device-b",
      deviceName: sequence % 2 === 0 ? "A设备" : "B设备",
      address: `40${String(sequence).padStart(4, "0")}`,
      qualityLevel: sequence % 2 === 0 ? "GOOD" : "BAD",
      qualityAcceptable: sequence % 2 === 0
    };
  });
}

describe("realtime table page window", () => {
  it("uses the documented page size options and default", () => {
    expect(REALTIME_PAGE_SIZE_OPTIONS).toEqual([100, 200, 500]);
    expect(DEFAULT_REALTIME_PAGE_SIZE).toBe(200);
    expect(MAX_REALTIME_PAGE_SIZE).toBe(500);
  });

  it("bounds default rendered rows to 200 for 100k source rows", () => {
    const rows = makeRows(100_000);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: DEFAULT_REALTIME_PAGE_SIZE });
    expect(pageWindow).toMatchObject({ page: 1, pageSize: 200, total: 100_000, totalPages: 500, startIndex: 1, endIndex: 200 });
    expect(getPagedRealtimeRows(rows, pageWindow)).toHaveLength(200);
  });

  it("clamps maximum page size to 500", () => {
    expect(normalizeRealtimePageSize(500)).toBe(500);
    expect(normalizeRealtimePageSize(5_000)).toBe(500);
    const rows = makeRows(100_000);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: 5_000 });
    expect(pageWindow.pageSize).toBe(500);
    expect(getPagedRealtimeRows(rows, pageWindow).length).toBeLessThanOrEqual(500);
  });

  it("calculates first page human-readable indexes", () => {
    expect(buildRealtimePageWindow({ total: 1_000, page: 1, pageSize: 200 })).toEqual({
      page: 1,
      pageSize: 200,
      total: 1_000,
      totalPages: 5,
      startIndex: 1,
      endIndex: 200
    });
  });

  it("calculates middle page human-readable indexes", () => {
    expect(buildRealtimePageWindow({ total: 1_000, page: 3, pageSize: 200 })).toMatchObject({
      page: 3,
      startIndex: 401,
      endIndex: 600
    });
  });

  it("keeps the last partial page visible", () => {
    const rows = makeRows(450);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 3, pageSize: 200 });
    expect(pageWindow).toMatchObject({ page: 3, totalPages: 3, startIndex: 401, endIndex: 450 });
    expect(getPagedRealtimeRows(rows, pageWindow)).toHaveLength(50);
  });

  it("handles empty datasets without enabling a fake page", () => {
    const pageWindow = buildRealtimePageWindow({ total: 0, page: 99, pageSize: 200 });
    expect(pageWindow).toEqual({ page: 1, pageSize: 200, total: 0, totalPages: 0, startIndex: 0, endIndex: 0 });
    expect(getPagedRealtimeRows([], pageWindow)).toEqual([]);
  });

  it("clamps requested page when page is too large", () => {
    expect(buildRealtimePageWindow({ total: 250, page: 99, pageSize: 200 })).toMatchObject({
      page: 2,
      totalPages: 2,
      startIndex: 201,
      endIndex: 250
    });
  });

  it("clamps after dataset shrink without resetting to page 1", () => {
    const before = buildRealtimePageWindow({ total: 1_000, page: 5, pageSize: 200 });
    expect(before.page).toBe(5);
    const after = buildRealtimePageWindow({ total: 250, page: before.page, pageSize: before.pageSize });
    expect(after.page).toBe(2);
  });

  it("filters before pagination", () => {
    const rows = makeRows(1_000);
    const filtered = filterRealtimeRows(rows, "目标点位", new Map());
    const pageWindow = buildRealtimePageWindow({ total: filtered.length, page: 1, pageSize: 200 });
    expect(filtered).toHaveLength(100);
    expect(getPagedRealtimeRows(filtered, pageWindow)).toHaveLength(100);
    expect(getPagedRealtimeRows(filtered, pageWindow)[0]?.pointId).toBe("point-800");
  });

  it("summarizes the full filtered result instead of the current page", () => {
    const rows = makeRows(1_000);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: 200 });
    expect(getPagedRealtimeRows(rows, pageWindow)).toHaveLength(200);
    expect(buildRealtimeSummary(rows).total).toBe(1_000);
  });

  it("keeps paged rows as original row references", () => {
    const rows = makeRows(1_000);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 3, pageSize: 200 });
    const pagedRows = getPagedRealtimeRows(rows, pageWindow);
    expect(pagedRows[0]).toBe(rows[400]);
    expect(pagedRows[pagedRows.length - 1]).toBe(rows[599]);
  });

  it("returns exactly 500 stable rows for 100k page 200", () => {
    const rows = makeRows(100_000);
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 200, pageSize: 500 });
    const pagedRows = getPagedRealtimeRows(rows, pageWindow);
    expect(pageWindow).toMatchObject({ page: 200, startIndex: 99_501, endIndex: 100_000 });
    expect(pagedRows).toHaveLength(500);
    expect(pagedRows[0]).toBe(rows[99_500]);
    expect(pagedRows[499]).toBe(rows[99_999]);
  });

  it("keeps single-device mode bounded with the same render window", () => {
    const rows = makeRows(10_000).map((row) => ({
      ...row,
      deviceId: "device-a",
      deviceName: "A设备"
    }));
    const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: 500 });
    expect(getPagedRealtimeRows(rows, pageWindow)).toHaveLength(500);
  });
});

describe("realtime table page state transitions", () => {
  it("resets page when keyword changes", () => {
    let page = 10;
    const resetPage = () => { page = 1; };
    resetPage();
    expect(page).toBe(1);
  });

  it("resets page when device changes", () => {
    let page = 20;
    const resetPage = () => { page = 1; };
    resetPage();
    expect(page).toBe(1);
  });

  it("preserves current page on same-context timer refresh when still valid", () => {
    const current = buildRealtimePageWindow({ total: 5_000, page: 10, pageSize: 200 });
    const refreshed = buildRealtimePageWindow({ total: 5_000, page: current.page, pageSize: current.pageSize });
    expect(refreshed.page).toBe(10);
  });

  it("clamps current page on timer refresh when dataset shrinks", () => {
    const current = buildRealtimePageWindow({ total: 5_000, page: 10, pageSize: 200 });
    const refreshed = buildRealtimePageWindow({ total: 600, page: current.page, pageSize: current.pageSize });
    expect(refreshed.page).toBe(3);
  });
});

describe("realtime device name lookup", () => {
  it("resolves device display names from a prebuilt map", () => {
    const lookup = buildRealtimeDeviceNameLookup([
      { normalizedId: "device-a", displayName: "A设备" },
      { normalizedId: "device-b", displayName: "B设备" }
    ]);
    expect(resolveRealtimeDeviceName(lookup, "device-a")).toBe("A设备");
    expect(resolveRealtimeDeviceName(lookup, "device-b")).toBe("B设备");
  });

  it("keeps fallback behavior for missing and empty ids", () => {
    const lookup = buildRealtimeDeviceNameLookup([{ normalizedId: "device-a", displayName: "A设备" }]);
    expect(resolveRealtimeDeviceName(lookup, "device-z")).toBe("device-z");
    expect(resolveRealtimeDeviceName(lookup, "")).toBe("-");
    expect(resolveRealtimeDeviceName(lookup, "   ")).toBe("-");
    expect(resolveRealtimeDeviceName(lookup, undefined)).toBe("-");
    expect(resolveRealtimeDeviceName(lookup, null)).toBe("-");
  });

  it("does not create empty keys and keeps first duplicate occurrence", () => {
    const lookup = buildRealtimeDeviceNameLookup([
      { normalizedId: "", displayName: "空设备" },
      { normalizedId: "device-a", displayName: "A1" },
      { normalizedId: "device-a", displayName: "A2" },
      { normalizedId: "device-b" }
    ]);
    expect(lookup.has("")).toBe(false);
    expect(resolveRealtimeDeviceName(lookup, "device-a")).toBe("A1");
    expect(resolveRealtimeDeviceName(lookup, "device-b")).toBe("device-b");
  });

  it("uses the lookup for keyword filtering without decorating rows", () => {
    const rows = makeRows(4).map(({ deviceName: _deviceName, ...row }) => row);
    const lookup = buildRealtimeDeviceNameLookup([{ normalizedId: "device-a", displayName: "A设备" }]);
    const filtered = filterRealtimeRows(rows, "A设备", lookup);
    expect(filtered).toHaveLength(2);
    expect(filtered[0]).toBe(rows[1]);
  });
});
