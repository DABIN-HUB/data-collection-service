import { describe, expect, it } from "vitest";

import { EDGE_PROTOCOL_OPTIONS, buildEdgeTelemetryPayload, normalizeEdgeTelemetryResult, parseEdgeTelemetryJson } from "./edge-telemetry-utils";

describe("edge-telemetry-utils", () => {
  it("提供后端支持的边缘协议类型", () => {
    expect(EDGE_PROTOCOL_OPTIONS.map((item) => item.value)).toEqual(["PROFINET", "ETHERCAT", "GENERIC_EDGE"]);
  });

  it("从快捷表单构造 EdgeTelemetryBatchRequest", () => {
    expect(buildEdgeTelemetryPayload({
      gatewayId: "gw-1",
      protocol: "GENERIC_EDGE",
      configVersion: "v1",
      deviceId: "dev-1",
      pointRef: "temp",
      valueText: "12.5",
      valueType: "number",
      quality: 100,
      timestamp: 1700000000000,
      sequence: 7
    })).toEqual({
      gatewayId: "gw-1",
      protocol: "GENERIC_EDGE",
      configVersion: "v1",
      items: [{ deviceId: "dev-1", pointRef: "temp", value: 12.5, quality: 100, timestamp: 1700000000000, sequence: 7 }]
    });
  });

  it("解析原始 JSON 并拒绝空内容和非对象", () => {
    expect(parseEdgeTelemetryJson('{"gatewayId":"gw","protocol":"PROFINET","configVersion":"v1","items":[]}')).toEqual({ gatewayId: "gw", protocol: "PROFINET", configVersion: "v1", items: [] });
    expect(() => parseEdgeTelemetryJson(" ")).toThrow("边缘遥测 JSON 不能为空");
    expect(() => parseEdgeTelemetryJson("[]")).toThrow("边缘遥测 JSON 必须是对象");
  });

  it("归一化 ApiResult 包裹的接入结果", () => {
    expect(normalizeEdgeTelemetryResult({ code: 200, msg: "边缘遥测处理完成", data: { gatewayId: "gw", acceptedCount: 1, duplicateCount: 0, rejectedCount: 0, errors: [] } })).toEqual({ gatewayId: "gw", message: "边缘遥测处理完成", acceptedCount: 1, duplicateCount: 0, rejectedCount: 0, errors: [] });
  });
});
