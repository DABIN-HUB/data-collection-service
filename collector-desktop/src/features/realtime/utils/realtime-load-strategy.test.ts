import { describe, expect, it, vi } from "vitest";

import type { CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, DeviceRealtimeDataResponse } from "@/types/monitor";
import { loadRealtimeRowsByContext } from "./realtime-load-strategy";

function buildCompactAggregateResponse(deviceCount: number): CompactAllDeviceRealtimeDataResponse {
  return {
    status: "success",
    deviceCount,
    dataCount: deviceCount,
    rows: Array.from({ length: deviceCount }, (_, index) => ({
      deviceId: `device-${index + 1}`,
      pointId: `point-${index + 1}`,
      pointName: `点位${index + 1}`,
      value: index + 1,
      qualityAvailable: false
    })),
    devices: Array.from({ length: deviceCount }, (_, index) => ({
      status: "success",
      deviceId: `device-${index + 1}`,
      dataCount: 1
    })),
    timestamp: 9999
  };
}

describe("realtime-load-strategy", () => {
  it("all N=1 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(1));
    const getCompactDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<CompactDeviceRealtimeDataResponse>>();
    const getAllDeviceRealtimeData = vi.fn();
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getCompactAllDeviceRealtimeData,
      getCompactDeviceRealtimeData,
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(1);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=10 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(10));
    const getCompactDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<CompactDeviceRealtimeDataResponse>>();
    const getAllDeviceRealtimeData = vi.fn();
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getCompactAllDeviceRealtimeData,
      getCompactDeviceRealtimeData,
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(10);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=100 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(100));
    const getCompactDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<CompactDeviceRealtimeDataResponse>>();
    const getAllDeviceRealtimeData = vi.fn();
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getCompactAllDeviceRealtimeData,
      getCompactDeviceRealtimeData,
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(100);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("device mode 走单设备 compact realtime API 并保留 row 引用", async () => {
    const row = {
      deviceId: "device-a",
      pointId: "point-1",
      pointName: "温度",
      value: 12,
      qualityAvailable: false
    };
    const response = {
      status: "success",
      deviceId: "device-a",
      dataCount: 1,
      rows: [row],
      timestamp: 1000
    } satisfies CompactDeviceRealtimeDataResponse;
    const getCompactAllDeviceRealtimeData = vi.fn();
    const getCompactDeviceRealtimeData = vi.fn().mockResolvedValue(response);
    const getAllDeviceRealtimeData = vi.fn();
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "device", deviceId: "device-a" }, {
      getCompactAllDeviceRealtimeData,
      getCompactDeviceRealtimeData,
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toBe(response.rows);
    expect(rows[0]).toBe(row);
    expect(getCompactDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getCompactDeviceRealtimeData).toHaveBeenCalledWith("device-a");
    expect(getCompactAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });
});
