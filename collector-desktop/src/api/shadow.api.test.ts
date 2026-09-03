import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type {
  DeviceShadowDeltaResponse,
  DeviceShadowResponse,
  ShadowDesiredUpdateRequest,
  ShadowHistoryDocument
} from "@/types/shadow";

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

import { clearShadowDesired, getShadow, getShadowDelta, getShadowHistory, updateShadowDesired } from "./shadow.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestApiData.mockReset();
  httpMocks.requestEnvelope.mockReset();
  httpMocks.requestRaw.mockReset();
});

describe("shadow.api", () => {
  it("getShadow 走 requestApiData<DeviceShadowResponse> 并保留动态 state/metadata", async () => {
    const response: DeviceShadowResponse = {
      deviceId: "device-1",
      version: 3,
      timestamp: 1700000000000,
      createdAt: 1699999990000,
      lastReportAt: 1700000000000,
      lastWindowStart: 1699999980000,
      lastWindowEnd: 1700000000000,
      state: {
        reported: { temperature: 25.2 },
        desired: { target: 20 },
        delta: { target: 20 },
        lastReported: {}
      },
      metadata: {
        reported: {},
        desired: {}
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getShadow("device-1");

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceShadowResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/shadow/device-1",
      method: "GET"
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestEnvelope).not.toHaveBeenCalled();
    expect(httpMocks.requestRaw).not.toHaveBeenCalled();
  });

  it("getShadowDelta 走 requestApiData<DeviceShadowDeltaResponse> 并保留动态 delta", async () => {
    const response: DeviceShadowDeltaResponse = {
      deviceId: "device-1",
      version: 4,
      timestamp: 1700000000001,
      delta: { speed: 10 },
      metadata: {
        speed: {
          timestamp: 1700000000001,
          source: "api"
        }
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getShadowDelta("device-1");

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceShadowDeltaResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/shadow/device-1/delta",
      method: "GET"
    });
  });

  it("getShadowHistory 走 requestApiData<ShadowHistoryDocument[]> 并保持 history document 动态", async () => {
    const response: ShadowHistoryDocument[] = [{
      deviceId: "device-1",
      action: "desired_update",
      baseVersion: 2,
      version: 3,
      timestamp: 1700000000002,
      document: {
        state: {
          desired: { speed: 10 }
        }
      }
    }];
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getShadowHistory("device-1", 25);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<ShadowHistoryDocument[]>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/shadow/device-1/history",
      method: "GET",
      params: { limit: 25 }
    });
  });

  it("updateShadowDesired 走 requestApiData<DeviceShadowResponse> 并允许动态 desired payload", async () => {
    const payload: ShadowDesiredUpdateRequest = {
      desired: {
        speed: 10
      },
      source: "console",
      expectedVersion: 3,
      customMode: "eco"
    };
    const response: DeviceShadowResponse = {
      deviceId: "device-1",
      version: 4,
      timestamp: 1700000000003,
      createdAt: 1699999990000,
      lastReportAt: 1700000000003,
      lastWindowStart: 1699999980000,
      lastWindowEnd: 1700000000003,
      state: {
        reported: {},
        desired: { speed: 10 },
        delta: { speed: 10 },
        lastReported: {}
      },
      metadata: {
        reported: {},
        desired: {}
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await updateShadowDesired("device-1", payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceShadowResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/shadow/device-1/desired",
      method: "POST",
      data: payload
    });
  });

  it("clearShadowDesired 走 requestApiData<DeviceShadowResponse> 并正确序列化 fields", async () => {
    const response: DeviceShadowResponse = {
      deviceId: "device-1",
      version: 5,
      timestamp: 1700000000004,
      createdAt: 1699999990000,
      lastReportAt: 1700000000004,
      lastWindowStart: 1699999980000,
      lastWindowEnd: 1700000000004,
      state: {
        reported: { temperature: 25.2 },
        desired: {},
        delta: {},
        lastReported: {}
      },
      metadata: {
        reported: {},
        desired: {}
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await clearShadowDesired("device-1", ["speed", "mode"]);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<DeviceShadowResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/shadow/device-1/desired",
      method: "DELETE",
      params: { fields: "speed,mode" }
    });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestEnvelope).not.toHaveBeenCalled();
    expect(httpMocks.requestRaw).not.toHaveBeenCalled();
  });
});
