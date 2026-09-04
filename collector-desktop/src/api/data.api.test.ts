import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  AdaptiveResetResponse,
  AllDeviceRealtimeDataResponse,
  AlarmHistoryDataResponse,
  DeviceListResponse,
  DevicePointListResponse,
  DeviceRealtimeDataResponse,
  HistoryDataResponse,
  PointRealtimeResponse
} from "@/types/monitor";

const httpMocks = vi.hoisted(() => ({
  request: vi.fn(),
  requestRaw: vi.fn()
}));

vi.mock("./http", () => ({
  request: httpMocks.request,
  requestRaw: httpMocks.requestRaw
}));

import {
  getAllDeviceDataSummaries,
  getAllDeviceRealtimeData,
  getDeviceAlarmHistory,
  getDevicePointSummaries,
  getDeviceRealtimeData,
  getPointHistory,
  getPointRealtimeData,
  getRecentAlarms,
  resetAdaptiveConfig
} from "./data.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestRaw.mockReset();
});

describe("data.api", () => {
  it("全部设备实时数据聚合接口走 RAW DTO", async () => {
    const response: AllDeviceRealtimeDataResponse = {
      status: "success",
      deviceCount: 2,
      dataCount: 3,
      devices: [
        { status: "success", deviceId: "device-1", dataCount: 2, data: { "point-1": { pointId: "point-1", value: 10 } } },
        { status: "error", deviceId: "device-2", dataCount: 0, message: "设备不存在或无数据点" }
      ],
      timestamp: 123456
    };
    httpMocks.requestRaw.mockResolvedValue(response);

    await expect(getAllDeviceRealtimeData()).resolves.toBe(response);

    expect(httpMocks.requestRaw).toHaveBeenCalledWith({
      url: "/api/data/realtime",
      method: "GET"
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("设备实时数据接口走 RAW DTO，保留后端 data 点位 Map", async () => {
    const response: DeviceRealtimeDataResponse = {
      status: "success",
      deviceId: "device-1",
      dataCount: 1,
      data: {
        "point-1": {
          pointId: "point-1",
          value: 10
        }
      },
      timestamp: 123456
    };
    httpMocks.requestRaw.mockResolvedValue(response);

    await expect(getDeviceRealtimeData("device-1", ["point-1"])).resolves.toBe(response);

    expect(httpMocks.requestRaw).toHaveBeenCalledWith({
      url: "/api/data/device/device-1",
      method: "GET",
      params: { pointIds: "point-1" }
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("DataController 核心接口均使用 RAW response boundary", async () => {
    const responses = [
      { status: "success", deviceId: "device-1", pointId: "point-1", data: { pointId: "point-1", value: 10 } } satisfies PointRealtimeResponse,
      { status: "success", deviceCount: 1, dataCount: 1, devices: [{ status: "success", deviceId: "device-1", dataCount: 1, data: { "point-1": { pointId: "point-1", value: 10 } } }] } satisfies AllDeviceRealtimeDataResponse,
      { status: "success", deviceCount: 1, devices: [{ deviceId: "device-1", pointCount: 1 }] } satisfies DeviceListResponse,
      { status: "success", deviceId: "device-1", pointCount: 1, points: [{ pointId: "point-1" }] } satisfies DevicePointListResponse,
      { code: 200, message: "已重置" } satisfies AdaptiveResetResponse,
      { status: "success", deviceId: "device-1", pointId: "point-1", count: 1, data: [{ value: 10 }] } satisfies HistoryDataResponse,
      { status: "success", count: 1, total: 1, data: [{ alarmId: "alarm-1" }] } satisfies AlarmHistoryDataResponse,
      { status: "success", deviceId: "device-1", count: 1, data: [{ alarmId: "alarm-2" }] } satisfies AlarmHistoryDataResponse
    ];
    for (const response of responses) {
      httpMocks.requestRaw.mockResolvedValueOnce(response);
    }

    await expect(getPointRealtimeData("device-1", "point-1")).resolves.toBe(responses[0]);
    await expect(getAllDeviceRealtimeData()).resolves.toBe(responses[1]);
    await expect(getAllDeviceDataSummaries()).resolves.toBe(responses[2]);
    await expect(getDevicePointSummaries("device-1")).resolves.toBe(responses[3]);
    await expect(resetAdaptiveConfig("device-1")).resolves.toBe(responses[4]);
    await expect(getPointHistory("device-1", "point-1", { limit: 10 })).resolves.toBe(responses[5]);
    await expect(getRecentAlarms({ level: "WARN" })).resolves.toBe(responses[6]);
    await expect(getDeviceAlarmHistory("device-1", { pointCode: "temperature" })).resolves.toBe(responses[7]);

    expect(httpMocks.requestRaw.mock.calls.map(([config]) => config.url)).toEqual([
      "/api/data/device/device-1/point/point-1",
      "/api/data/realtime",
      "/api/data/devices",
      "/api/data/device/device-1/points",
      "/api/data/device/device-1/reset-adaptive",
      "/api/data/history/device/device-1/point/point-1",
      "/api/data/history/alarms",
      "/api/data/history/device/device-1/alarms"
    ]);
    expect(httpMocks.request).not.toHaveBeenCalled();
  });
});
