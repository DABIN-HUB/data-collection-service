import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type { ApiResult } from "@/types/api";

const httpMocks = vi.hoisted(() => ({
  request: vi.fn(),
  requestApiData: vi.fn(),
  requestEnvelope: vi.fn()
}));

vi.mock("./http", () => ({
  request: httpMocks.request,
  requestApiData: httpMocks.requestApiData,
  requestEnvelope: httpMocks.requestEnvelope
}));

import {
  getAllDeviceStatistics,
  getDeviceRuntime,
  getDeviceStatus,
  getRunningDevices,
  isDeviceRunning,
  reloadDevices,
  startDevice,
  startLocalDevice,
  stopDevice
} from "./device.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestApiData.mockReset();
  httpMocks.requestEnvelope.mockReset();
});

describe("device.api", () => {
  it("getDeviceStatus 返回真实 DeviceStatusResponse，并显式走 requestApiData", async () => {
    const response = {
      deviceId: "device-1",
      isRunning: true,
      isStarting: false,
      connected: true,
      reconnecting: false,
      reconnectNextRetryAt: 1700000000000,
      statistics: {
        deviceId: "device-1",
        isRunning: true,
        runningDuration: 1000,
        totalExecutions: 12,
        successfulExecutions: 11,
        failedExecutions: 1,
        totalPoints: 8,
        currentTaskPoints: 4,
        averageExecutionTime: 23,
        successRate: 91.7,
        lastExecutionTime: 1700000000000
      },
      performance: {
        deviceId: "device-1",
        totalPoints: 8,
        successfulBatches: 6,
        failedBatches: 1,
        averageBatchTime: 35,
        currentBatchSize: 2,
        maxBatchSize: 8,
        successRate: 85.7,
        healthScore: 92.5,
        failureRisk: "LOW",
        consecutiveFailures: 0,
        averageResponseTime: 18,
        recentResponseTimes: [18, 21, 16]
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getDeviceStatus("device-1");

    expect(result).toEqual(response);
    expectTypeOf(result).toMatchTypeOf<{
      deviceId?: string;
      isRunning?: boolean;
      isStarting?: boolean;
      connected?: boolean;
      reconnecting?: boolean;
      reconnectNextRetryAt?: number;
      statistics?: {
        totalExecutions?: number;
        successRate?: number;
      };
      performance?: {
        averageBatchTime?: number;
        recentResponseTimes?: number[];
      };
    }>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({ url: "/api/device/device-1/status", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("getAllDeviceStatistics 返回 typed statistics map，并显式走 requestApiData", async () => {
    const response = {
      "device-1": {
        deviceId: "device-1",
        isRunning: true,
        runningDuration: 1000,
        totalExecutions: 12,
        successfulExecutions: 11,
        failedExecutions: 1,
        totalPoints: 8,
        currentTaskPoints: 4,
        averageExecutionTime: 23,
        successRate: 91.7,
        lastExecutionTime: 1700000000000
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getAllDeviceStatistics();

    expect(result).toEqual(response);
    expectTypeOf(result).toMatchTypeOf<Record<string, {
      deviceId?: string;
      isRunning?: boolean;
      totalExecutions?: number;
      successRate?: number;
    }>>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({ url: "/api/device/statistics", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("getRunningDevices 返回后端 ApiResult<List<String>> 解包后的 string[]，并显式走 requestApiData", async () => {
    httpMocks.requestApiData.mockResolvedValue(["device-a", "device-b"]);

    const result = await getRunningDevices();

    expect(result).toEqual(["device-a", "device-b"]);
    expectTypeOf(result).toEqualTypeOf<string[]>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({ url: "/api/device/running", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("getDeviceRuntime 返回 DeviceRuntimeSnapshot[]，并显式走 requestApiData", async () => {
    const response = [{
      deviceId: "device-1",
      phase: "RUNNING",
      running: true,
      starting: false,
      connected: true,
      reconnecting: false,
      reconnectNextRetryAt: 0,
      startedAt: 1700000000000,
      generation: 3,
      lastSuccessfulCollectionAt: 1700000001000,
      consecutiveFailures: 0,
      backoffUntil: 0,
      degradedReason: null,
      generatedAt: 1700000002000
    }];
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getDeviceRuntime();

    expect(result).toEqual(response);
    expectTypeOf(result).toMatchTypeOf<Array<{
      deviceId: string;
      phase?: string;
      running?: boolean;
      connected?: boolean;
    }>>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({ url: "/api/device/runtime", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("startDevice 保留 envelope metadata，避免 data=null 时丢失 message 与 deviceId", async () => {
    const response: ApiResult<null> = {
      status: "success",
      message: "设备启动成功",
      deviceId: "device-1",
      data: null
    };
    httpMocks.requestEnvelope.mockResolvedValue(response);

    const result = await startDevice("device-1");

    expect(result).toEqual(response);
    expectTypeOf(result).toMatchTypeOf<ApiResult<null>>();
    expect(httpMocks.requestEnvelope).toHaveBeenCalledWith({ url: "/api/device/device-1/start", method: "POST" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("startLocalDevice、stopDevice、reloadDevices 都显式走 requestEnvelope", async () => {
    httpMocks.requestEnvelope.mockResolvedValue({ status: "success", data: null });

    await startLocalDevice("device-1");
    await stopDevice("device-1");
    await reloadDevices();

    expect(httpMocks.requestEnvelope).toHaveBeenNthCalledWith(1, { url: "/api/device/device-1/start-local", method: "POST" });
    expect(httpMocks.requestEnvelope).toHaveBeenNthCalledWith(2, { url: "/api/device/device-1/stop", method: "POST" });
    expect(httpMocks.requestEnvelope).toHaveBeenNthCalledWith(3, { url: "/api/device/reload", method: "POST" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("isDeviceRunning 从 ApiResult 顶层 running 字段返回真实 boolean", async () => {
    const response: ApiResult<null> = {
      status: "success",
      deviceId: "device-1",
      running: true,
      timestamp: 123456,
      data: null
    };
    httpMocks.requestEnvelope.mockResolvedValue(response);

    await expect(isDeviceRunning("device-1")).resolves.toBe(true);

    expect(httpMocks.requestEnvelope).toHaveBeenCalledWith({ url: "/api/device/device-1/running", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("isDeviceRunning 缺少顶层 running 时按 false 处理", async () => {
    httpMocks.requestEnvelope.mockResolvedValue({ status: "success", deviceId: "device-1", data: null });

    await expect(isDeviceRunning("device-1")).resolves.toBe(false);
  });
});
