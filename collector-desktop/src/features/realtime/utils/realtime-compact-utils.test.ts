import { describe, expect, it } from "vitest";

import type { CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse } from "@/types/monitor";
import { extractCompactRealtimeRows } from "./realtime-compact-utils";
import { buildRealtimeDeviceNameLookup, filterRealtimeRows, resolveRealtimeDeviceName } from "./realtime-table-window";
import { buildRealtimeSummary, realtimeProcessingText, realtimeQualityText, realtimeValueText } from "./realtime-utils";

describe("realtime compact rows", () => {
  it("returns aggregate response.rows as the original row references", () => {
    const response: CompactAllDeviceRealtimeDataResponse = {
      status: "success",
      deviceCount: 1,
      dataCount: 2,
      rows: [
        { deviceId: "device-a", pointId: "point-1", value: 12.3, qualityLevel: "GOOD", qualityAvailable: true },
        { deviceId: "device-a", pointId: "point-2", value: 45.6, qualityAvailable: false }
      ],
      devices: [{ status: "success", deviceId: "device-a", dataCount: 2 }]
    };

    const rows = extractCompactRealtimeRows(response);

    expect(rows).toBe(response.rows);
    expect(rows[0]).toBe(response.rows?.[0]);
    expect(rows[1]).toBe(response.rows?.[1]);
  });

  it("returns device response.rows as the original row references", () => {
    const response: CompactDeviceRealtimeDataResponse = {
      status: "success",
      deviceId: "device-a",
      dataCount: 1,
      rows: [{ deviceId: "device-a", pointId: "point-1", value: 12.3, processingTime: 7, lastUpdateTime: 1800000000123 }]
    };

    const rows = extractCompactRealtimeRows(response);

    expect(rows).toBe(response.rows);
    expect(rows[0]).toBe(response.rows?.[0]);
  });

  it("keeps compact rows consumable by existing realtime formatting and summary helpers", () => {
    const response: CompactDeviceRealtimeDataResponse = {
      status: "success",
      deviceId: "device-a",
      dataCount: 2,
      rows: [
        {
          deviceId: "device-a",
          pointId: "point-1",
          pointName: "温度",
          value: 12.34567,
          qualityLevel: "GOOD",
          qualityAvailable: true,
          qualityAcceptable: true,
          processSuccess: true,
          processingTime: 7,
          lastUpdateTime: 1800000000123
        },
        {
          deviceId: "device-a",
          pointId: "point-2",
          pointName: "压力",
          value: 45.6,
          qualityAvailable: false
        }
      ]
    };

    const rows = extractCompactRealtimeRows(response);

    expect(realtimeValueText(rows[0])).toBe("12.3457");
    expect(realtimeQualityText(rows[0])).toBe("良好");
    expect(realtimeProcessingText(rows[0])).toBe("7 ms");
    expect(rows[0].lastUpdateTime).toBe(1800000000123);
    expect(buildRealtimeSummary(rows)).toEqual({ total: 2, good: 1, bad: 1 });
  });

  it("uses device id map fallback when compact rows omit deviceName", () => {
    const rows = extractCompactRealtimeRows({
      status: "success",
      deviceId: "device-a",
      dataCount: 1,
      rows: [{ deviceId: "device-a", pointId: "point-1", pointName: "温度", value: 12.3 }]
    } satisfies CompactDeviceRealtimeDataResponse);
    const lookup = buildRealtimeDeviceNameLookup([{ normalizedId: "device-a", displayName: "A设备" }]);

    expect(rows[0].deviceName).toBeUndefined();
    expect(resolveRealtimeDeviceName(lookup, String(rows[0].deviceId))).toBe("A设备");
    expect(filterRealtimeRows(rows, "A设备", lookup)[0]).toBe(rows[0]);
  });

  it("returns an empty array for invalid compact responses", () => {
    expect(extractCompactRealtimeRows(null)).toEqual([]);
    expect(extractCompactRealtimeRows({ status: "success" })).toEqual([]);
  });
});
