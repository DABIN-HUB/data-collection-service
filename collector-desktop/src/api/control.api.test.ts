import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type {
  BatchPointWriteResponse,
  DeviceCommandRequest,
  DeviceCommandResponse,
  PointWriteRequest,
  PointWriteResultResponse
} from "@/types/control";

const httpMocks = vi.hoisted(() => ({
  request: vi.fn(),
  requestApiData: vi.fn(),
  requestEnvelope: vi.fn(),
  requestRaw: vi.fn()
}));

vi.mock("./http", () => ({
  request: httpMocks.request,
  requestApiData: httpMocks.requestApiData,
  requestEnvelope: httpMocks.requestEnvelope,
  requestRaw: httpMocks.requestRaw
}));

import { executeDeviceCommand, writeDevicePoint, writeDevicePoints } from "./control.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestApiData.mockReset();
  httpMocks.requestEnvelope.mockReset();
  httpMocks.requestRaw.mockReset();
});

describe("control.api", () => {
  it("writeDevicePoint 走 requestApiData<PointWriteResultResponse> 并保持单点 value 动态", async () => {
    const payload: PointWriteRequest = { value: 42 };
    const response: PointWriteResultResponse = {
      pointId: "p-1",
      pointCode: "temp",
      pointName: "温度",
      value: 42,
      success: true
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await writeDevicePoint("device-1", "point-1", payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<PointWriteResultResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/control/device/device-1/point/point-1",
      method: "POST",
      data: payload
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestEnvelope).not.toHaveBeenCalled();
    expect(httpMocks.requestRaw).not.toHaveBeenCalled();
  });

  it("writeDevicePoint 不把非 number 的动态值错误收窄", async () => {
    const payload: PointWriteRequest = { value: true };
    const response: PointWriteResultResponse = {
      pointId: "p-2",
      pointCode: "switch",
      pointName: "开关",
      value: true,
      success: true
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await writeDevicePoint("device-1", "switch", payload);

    expect(result.value).toBe(true);
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/control/device/device-1/point/switch",
      method: "POST",
      data: { value: true }
    });
  });

  it("writeDevicePoints 走 requestApiData<BatchPointWriteResponse> 并透传 PointWriteRequest.values", async () => {
    const payload: PointWriteRequest = {
      values: {
        pointRefA: 1,
        pointRefB: true
      }
    };
    const response: BatchPointWriteResponse = {
      deviceId: "device-1",
      total: 2,
      mapped: 2,
      success: 1,
      fields: {
        pointRefA: {
          mapped: true,
          success: true,
          pointId: "p-1",
          pointCode: "temp",
          value: 1
        },
        pointRefB: {
          mapped: true,
          success: false,
          pointId: "p-2",
          pointCode: "switch",
          value: true,
          error: "协议写入返回失败"
        }
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await writeDevicePoints("device-1", payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<BatchPointWriteResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/control/device/device-1/points",
      method: "POST",
      data: payload
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestEnvelope).not.toHaveBeenCalled();
    expect(httpMocks.requestRaw).not.toHaveBeenCalled();
  });

  it("executeDeviceCommand 走 requestApiData<DeviceCommandResponse> 并保持 params/result 动态", async () => {
    const payload: DeviceCommandRequest = {
      command: "custom-reset",
      params: {
        mode: "soft",
        delayMs: 500
      }
    };
    const response: DeviceCommandResponse = {
      deviceId: "device-1",
      command: "custom-reset",
      params: {
        mode: "soft",
        delayMs: 500
      },
      result: {
        accepted: true,
        code: 200
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await executeDeviceCommand("device-1", payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceCommandResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/control/device/device-1/command",
      method: "POST",
      data: payload
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestEnvelope).not.toHaveBeenCalled();
    expect(httpMocks.requestRaw).not.toHaveBeenCalled();
  });
});
