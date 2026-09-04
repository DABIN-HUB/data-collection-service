import { describe, expect, it } from "vitest";

import { shouldSkipRealtimePanelHttpLoad, shouldUseRealtimePanelWebSocketRows } from "./realtime-panel-transport";

describe("realtime-panel-transport", () => {
  it("断线后不再继续显示 stale WS rows", () => {
    expect(shouldUseRealtimePanelWebSocketRows({
      connected: false,
      activeDeviceId: "device-a",
      deviceId: "device-a",
      hasFreshRows: true,
      wsRowCount: 1
    })).toBe(false);
  });

  it("重连后未收到当前连接新消息前继续使用 HTTP rows", () => {
    expect(shouldUseRealtimePanelWebSocketRows({
      connected: true,
      activeDeviceId: "device-a",
      deviceId: "device-a",
      hasFreshRows: false,
      wsRowCount: 1
    })).toBe(false);
  });

  it("只有当前 device 的健康 WS rows 才可覆盖 HTTP fallback", () => {
    expect(shouldUseRealtimePanelWebSocketRows({
      connected: true,
      activeDeviceId: "device-a",
      deviceId: "device-a",
      hasFreshRows: true,
      wsRowCount: 2
    })).toBe(true);

    expect(shouldUseRealtimePanelWebSocketRows({
      connected: true,
      activeDeviceId: "device-b",
      deviceId: "device-a",
      hasFreshRows: true,
      wsRowCount: 2
    })).toBe(false);
  });

  it("WS healthy 时跳过 HTTP timer，其他情况继续 HTTP", () => {
    expect(shouldSkipRealtimePanelHttpLoad({
      source: "timer",
      loading: false,
      usingWebSocketRows: true
    })).toBe(true);

    expect(shouldSkipRealtimePanelHttpLoad({
      source: "timer",
      loading: false,
      usingWebSocketRows: false
    })).toBe(false);

    expect(shouldSkipRealtimePanelHttpLoad({
      source: "timer",
      loading: true,
      usingWebSocketRows: false
    })).toBe(true);

    expect(shouldSkipRealtimePanelHttpLoad({
      source: "manual",
      loading: true,
      usingWebSocketRows: true
    })).toBe(false);
  });
});
