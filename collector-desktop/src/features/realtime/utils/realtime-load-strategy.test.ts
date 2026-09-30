import { describe, expect, it, vi } from "vitest";

import type { CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, CompactRealtimeDeltaResponse, DeviceRealtimeDataResponse, RealtimeSnapshotCursor } from "@/types/monitor";
import { loadRealtimeDeltaResponseByContext, loadRealtimeFullResponseByContext, loadRealtimeRowsByContext, type RealtimeLoadStrategyDependencies } from "./realtime-load-strategy";

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

function buildDependencies(overrides: Partial<RealtimeLoadStrategyDependencies>): RealtimeLoadStrategyDependencies {
  return {
    getCompactAllDeviceRealtimeData: vi.fn<() => Promise<CompactAllDeviceRealtimeDataResponse>>(),
    getCompactDeviceRealtimeData: vi.fn<(...args: unknown[]) => Promise<CompactDeviceRealtimeDataResponse>>(),
    getCompactAllDeviceRealtimeDelta: vi.fn<(...args: unknown[]) => Promise<CompactRealtimeDeltaResponse>>(),
    getCompactDeviceRealtimeDelta: vi.fn<(...args: unknown[]) => Promise<CompactRealtimeDeltaResponse>>(),
    getAllDeviceRealtimeData: vi.fn(),
    getDeviceRealtimeData: vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>(),
    ...overrides
  };
}

describe("realtime-load-strategy", () => {
  it("all N=1 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(1));
    const dependencies = buildDependencies({ getCompactAllDeviceRealtimeData });

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, dependencies);

    expect(rows).toHaveLength(1);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(dependencies.getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=10 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(10));
    const dependencies = buildDependencies({ getCompactAllDeviceRealtimeData });

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, dependencies);

    expect(rows).toHaveLength(10);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(dependencies.getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=100 时只发 1 个 aggregate realtime request", async () => {
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildCompactAggregateResponse(100));
    const dependencies = buildDependencies({ getCompactAllDeviceRealtimeData });

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, dependencies);

    expect(rows).toHaveLength(100);
    expect(getCompactAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(dependencies.getCompactDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getDeviceRealtimeData).not.toHaveBeenCalled();
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
    const getCompactDeviceRealtimeData = vi.fn().mockResolvedValue(response);
    const dependencies = buildDependencies({ getCompactDeviceRealtimeData });

    const rows = await loadRealtimeRowsByContext({ mode: "device", deviceId: "device-a" }, dependencies);

    expect(rows).toBe(response.rows);
    expect(rows[0]).toBe(row);
    expect(getCompactDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getCompactDeviceRealtimeData).toHaveBeenCalledWith("device-a");
    expect(dependencies.getCompactAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getAllDeviceRealtimeData).not.toHaveBeenCalled();
    expect(dependencies.getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("full response helper 保留 cursor 字段", async () => {
    const response = { ...buildCompactAggregateResponse(1), snapshotId: "snapshot-1", configEpoch: 1, revision: 10 };
    const getCompactAllDeviceRealtimeData = vi.fn().mockResolvedValue(response);
    const dependencies = buildDependencies({ getCompactAllDeviceRealtimeData });

    const actual = await loadRealtimeFullResponseByContext({ mode: "all", deviceId: "" }, dependencies);

    expect(actual).toBe(response);
  });

  it("delta response helper 使用全设备或单设备 delta endpoint", async () => {
    const cursor: RealtimeSnapshotCursor = { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 };
    const aggregateDelta = { status: "success", resetRequired: false, changedCount: 0, rows: [] };
    const deviceDelta = { status: "success", resetRequired: false, changedCount: 1, rows: [] };
    const getCompactAllDeviceRealtimeDelta = vi.fn().mockResolvedValue(aggregateDelta);
    const getCompactDeviceRealtimeDelta = vi.fn().mockResolvedValue(deviceDelta);
    const dependencies = buildDependencies({ getCompactAllDeviceRealtimeDelta, getCompactDeviceRealtimeDelta });

    await expect(loadRealtimeDeltaResponseByContext({ mode: "all", deviceId: "" }, cursor, dependencies)).resolves.toBe(aggregateDelta);
    await expect(loadRealtimeDeltaResponseByContext({ mode: "device", deviceId: "dev-a" }, cursor, dependencies)).resolves.toBe(deviceDelta);

    expect(getCompactAllDeviceRealtimeDelta).toHaveBeenCalledWith(cursor);
    expect(getCompactDeviceRealtimeDelta).toHaveBeenCalledWith("dev-a", cursor);
  });
});
