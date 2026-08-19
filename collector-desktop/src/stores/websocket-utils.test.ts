import { describe, expect, it } from "vitest";

import { buildRealtimeWebSocketUrl, normalizeRealtimeMessage } from "./websocket-utils";

describe("websocket-utils", () => {
  it("根据 HTTP 服务地址生成实时数据 WebSocket 地址", () => {
    expect(buildRealtimeWebSocketUrl("http://127.0.0.1:18080", "device-01")).toBe("ws://127.0.0.1:18080/ws/realtime?deviceId=device-01");
    expect(buildRealtimeWebSocketUrl("https://collector.example.com/api", "dev 1")).toBe("wss://collector.example.com/api/ws/realtime?deviceId=dev+1");
  });

  it("兼容后端单点和批量实时消息", () => {
    expect(normalizeRealtimeMessage({ pointId: "p1", value: 12 })).toEqual([{ pointId: "p1", value: 12 }]);
    expect(normalizeRealtimeMessage({ points: [{ pointId: "p1" }, { pointId: "p2" }] })).toEqual([{ pointId: "p1" }, { pointId: "p2" }]);
  });
});
