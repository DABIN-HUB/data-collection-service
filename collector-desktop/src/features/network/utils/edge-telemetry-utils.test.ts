import { describe, expect, it } from "vitest";

import { EDGE_PROTOCOL_OPTIONS, buildEdgeTelemetryAttributedResult, buildEdgeTelemetryPayload, buildEdgeTelemetrySubmissionSnapshot, normalizeEdgeTelemetryResult, parseEdgeTelemetryJson } from "./edge-telemetry-utils";

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

  it("边缘遥测响应归属使用提交时 gateway/device/point snapshot", () => {
    const payload = buildEdgeTelemetryPayload({
      gatewayId: "gw-a",
      protocol: "GENERIC_EDGE",
      configVersion: "v1",
      deviceId: "device-a",
      pointRef: "a-p1",
      valueText: "12.5",
      valueType: "number",
      sequence: 1
    });
    const target = buildEdgeTelemetrySubmissionSnapshot(payload, 1700000000000);
    payload.gatewayId = "gw-b";
    payload.items[0].deviceId = "device-b";
    payload.items[0].pointRef = "b-p1";

    const result = buildEdgeTelemetryAttributedResult(target, normalizeEdgeTelemetryResult({ gatewayId: "gw-b", acceptedCount: 1 }), undefined, 1700000001000);

    expect(result.target).toEqual(expect.objectContaining({ gatewayId: "gw-a", deviceId: "device-a", pointRef: "a-p1", submittedAt: 1700000000000 }));
    expect(result.completedAt).toBe(1700000001000);
  });
});
