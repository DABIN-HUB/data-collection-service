import { describe, expect, it } from "vitest";

import {
  buildRealtimeWebSocketUrl,
  getRealtimeReconnectDelayMs,
  getRealtimeRowIdentity,
  mergeRealtimeRows,
  normalizeRealtimeMessage,
  parseRealtimePayload
} from "./websocket-utils";

describe("websocket-utils", () => {
  it("根据 HTTP 服务地址生成实时数据 WebSocket 地址", () => {
    expect(buildRealtimeWebSocketUrl("http://127.0.0.1:18080", "device-01")).toBe("ws://127.0.0.1:18080/ws/realtime?deviceId=device-01");
    expect(buildRealtimeWebSocketUrl("https://collector.example.com/api", "dev 1")).toBe("wss://collector.example.com/api/ws/realtime?deviceId=dev+1");
  });

  it("兼容后端单点和批量实时消息", () => {
    expect(normalizeRealtimeMessage({ pointId: "p1", value: 12 })).toEqual({
      kind: "VALID",
      rows: [{ pointId: "p1", value: 12 }]
    });
    expect(normalizeRealtimeMessage({ points: [{ pointId: "p1" }, { pointId: "p2" }] })).toEqual({
      kind: "VALID",
      rows: [{ pointId: "p1" }, { pointId: "p2" }]
    });
  });

  it("invalid JSON 返回可观测结果", () => {
    expect(parseRealtimePayload("{broken json")).toMatchObject({
      kind: "INVALID_JSON",
      rows: []
    });
  });

  it("unsupported payload 返回可观测结果", () => {
    expect(parseRealtimePayload(JSON.stringify({ foo: "bar" }))).toMatchObject({
      kind: "UNSUPPORTED_PAYLOAD",
      rows: []
    });
  });

  it("合法空数组不计为 parse failure", () => {
    expect(parseRealtimePayload("[]")).toEqual({
      kind: "VALID",
      rows: []
    });
    expect(parseRealtimePayload(JSON.stringify({ points: [] }))).toEqual({
      kind: "VALID",
      rows: []
    });
  });

  it("实时点位 identity 使用稳定字段，不使用随机 fallback", () => {
    expect(getRealtimeRowIdentity({ pointId: "p1" }, "device-a")).toBe("p1");
    expect(getRealtimeRowIdentity({ pointCode: "code-1" }, "device-a")).toBe("code-1");
    expect(getRealtimeRowIdentity({ address: "40001" }, "device-a")).toBe("40001");
    expect(getRealtimeRowIdentity({ pointName: "温度" }, "device-a")).toBe("device-a::温度");
    expect(getRealtimeRowIdentity({}, "device-a")).toBeNull();
  });

  it("相同稳定 identity 的 rows 会 merge 更新", () => {
    const once = mergeRealtimeRows([], [{ pointId: "p1", value: 1 }], "device-a");
    const twice = mergeRealtimeRows(once, [{ pointId: "p1", value: 2 }], "device-a");

    expect(twice).toEqual([{ deviceId: "device-a", pointId: "p1", value: 2 }]);
  });

  it("缺少稳定 identity 的 rows 会被忽略", () => {
    expect(mergeRealtimeRows([], [{ value: 1 }], "device-a")).toEqual([]);
  });

  it("重连退避为有上限的指数策略", () => {
    expect([1, 2, 3, 4, 5].map((attempt) => getRealtimeReconnectDelayMs(attempt))).toEqual([
      1_000,
      2_000,
      4_000,
      8_000,
      16_000
    ]);
    expect(getRealtimeReconnectDelayMs(10)).toBeLessThanOrEqual(30_000);
  });
});
