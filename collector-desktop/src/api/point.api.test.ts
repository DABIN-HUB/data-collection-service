import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type { DeviceIdResponse } from "@/types/config";
import type { DataPoint, DevicePointConfigResponse } from "@/types/point";

const configApiMocks = vi.hoisted(() => ({
  getDevicePointsConfig: vi.fn(),
  updateDevicePointsConfig: vi.fn()
}));

const httpMocks = vi.hoisted(() => ({
  request: vi.fn()
}));

vi.mock("./config.api", () => ({
  getDevicePointsConfig: configApiMocks.getDevicePointsConfig,
  updateDevicePointsConfig: configApiMocks.updateDevicePointsConfig
}));

vi.mock("./http", () => ({
  request: httpMocks.request
}));

import { getDevicePointConfig, saveDevicePointConfig } from "./point.api";

beforeEach(() => {
  configApiMocks.getDevicePointsConfig.mockReset();
  configApiMocks.updateDevicePointsConfig.mockReset();
  httpMocks.request.mockReset();
});

describe("point.api", () => {
  it("getDevicePointConfig 复用 config.api 的稳定 points contract", async () => {
    const response: DevicePointConfigResponse = {
      deviceId: "dev-1",
      count: 1,
      points: [{ pointId: "p1", pointCode: "temp", pointName: "温度" }]
    };
    configApiMocks.getDevicePointsConfig.mockResolvedValue(response);

    const result = await getDevicePointConfig("dev-1", false);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DevicePointConfigResponse>();
    expect(configApiMocks.getDevicePointsConfig).toHaveBeenCalledWith("dev-1", false);
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("saveDevicePointConfig 复用 config.api 的 DeviceIdResponse contract", async () => {
    const points: DataPoint[] = [{ pointId: "p1", pointCode: "temp", pointName: "温度" }];
    const response: DeviceIdResponse = { deviceId: "dev-1", pointCount: 1 };
    configApiMocks.updateDevicePointsConfig.mockResolvedValue(response);

    const result = await saveDevicePointConfig("dev-1", points);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceIdResponse>();
    expect(configApiMocks.updateDevicePointsConfig).toHaveBeenCalledWith("dev-1", points);
    expect(httpMocks.request).not.toHaveBeenCalled();
  });
});
