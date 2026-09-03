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

import { getRunningDevices, isDeviceRunning } from "./device.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestApiData.mockReset();
  httpMocks.requestEnvelope.mockReset();
});

describe("device.api", () => {
  it("getRunningDevices 返回后端 ApiResult<List<String>> 解包后的 string[]", async () => {
    httpMocks.request.mockResolvedValue(["device-a", "device-b"]);

    const result = await getRunningDevices();

    expect(result).toEqual(["device-a", "device-b"]);
    expectTypeOf(result).toEqualTypeOf<string[]>();
    expect(httpMocks.request).toHaveBeenCalledWith({ url: "/api/device/running", method: "GET" });
  });

  it("isDeviceRunning 从 ApiResult 顶层 running 字段返回真实 boolean", async () => {
    const response: ApiResult<null> = {
      status: "success",
      deviceId: "device-1",
      running: true,
      timestamp: 123456
    };
    httpMocks.requestEnvelope.mockResolvedValue(response);
    httpMocks.request.mockResolvedValue(response);

    await expect(isDeviceRunning("device-1")).resolves.toBe(true);

    expect(httpMocks.requestEnvelope).toHaveBeenCalledWith({ url: "/api/device/device-1/running", method: "GET" });
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("isDeviceRunning 缺少顶层 running 时按 false 处理", async () => {
    httpMocks.requestEnvelope.mockResolvedValue({ status: "success", deviceId: "device-1" });

    await expect(isDeviceRunning("device-1")).resolves.toBe(false);
  });
});
