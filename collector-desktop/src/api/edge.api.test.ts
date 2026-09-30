import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type { EdgeTelemetryBatchRequest, EdgeTelemetryIngressResult } from "@/types/edge";

const httpMocks = vi.hoisted(() => ({
  requestApiData: vi.fn()
}));

vi.mock("./http", () => ({
  requestApiData: httpMocks.requestApiData
}));

import { ingestEdgeTelemetry } from "./edge.api";

beforeEach(() => {
  httpMocks.requestApiData.mockReset();
});

describe("edge.api", () => {
  it("ingestEdgeTelemetry 走 requestApiData，并返回真实 EdgeTelemetryIngressResult", async () => {
    const payload: EdgeTelemetryBatchRequest = {
      gatewayId: "gw-1",
      protocol: "GENERIC_EDGE",
      configVersion: "v1",
      items: [{
        deviceId: "dev-1",
        pointRef: "temp",
        value: 12.5,
        quality: 100,
        timestamp: 1700000000000,
        sequence: 7
      }]
    };
    const response: EdgeTelemetryIngressResult = {
      gatewayId: "gw-1",
      configVersion: "v1",
      acceptedCount: 1,
      duplicateCount: 0,
      rejectedCount: 0,
      errors: []
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await ingestEdgeTelemetry(payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toMatchTypeOf<{
      gatewayId?: string;
      configVersion?: string;
      acceptedCount?: number;
      duplicateCount?: number;
      rejectedCount?: number;
      errors?: string[];
    }>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/edge/telemetry",
      method: "POST",
      data: payload
    });
  });
});
