import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useWebSocketStore } from "./websocket.store";

vi.mock("@/api/http", () => ({
  getHttpConfig: () => ({ serverUrl: "http://127.0.0.1:18080" })
}));

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];

  readonly url: string;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  close() {
    this.closed = true;
  }

  fireOpen() {
    this.onopen?.(new Event("open"));
  }

  fireMessage(data: string) {
    this.onmessage?.({ data } as MessageEvent);
  }

  fireError() {
    this.onerror?.(new Event("error"));
  }

  fireClose() {
    this.onclose?.({ code: 1006 } as CloseEvent);
  }

  static reset() {
    FakeWebSocket.instances = [];
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  FakeWebSocket.reset();
  setActivePinia(createPinia());
  vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function latestSocket(): FakeWebSocket {
  const socket = FakeWebSocket.instances.at(-1);
  if (!socket) {
    throw new Error("expected a fake websocket instance");
  }
  return socket;
}

describe("websocket.store", () => {
  it("默认关闭 WebSocket，需用户手动启用", () => {
    const store = useWebSocketStore();

    expect(store.enabled).toBe(false);
    expect(store.status).toBe("disabled");
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("空 deviceId 会直接关闭 transport，且不会创建空设备 socket", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    store.connectRealtime("");

    expect(store.enabled).toBe(false);
    expect(store.connected).toBe(false);
    expect(store.activeDeviceId).toBe("");
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("A -> B 时旧 socket message 不得覆盖当前设备", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    const socketA = latestSocket();
    socketA.fireOpen();
    socketA.fireMessage(JSON.stringify([{ pointId: "p1", value: 1 }]));

    store.connectRealtime("device-b");
    const socketB = latestSocket();
    socketB.fireOpen();
    socketB.fireMessage(JSON.stringify([{ pointId: "p1", value: 2 }]));
    socketA.fireMessage(JSON.stringify([{ pointId: "p1", value: 99 }]));

    expect(store.activeDeviceId).toBe("device-b");
    expect(store.rows("device-b")).toEqual([{ deviceId: "device-b", pointId: "p1", value: 2 }]);
    expect(store.rows("device-a")).toEqual([{ deviceId: "device-a", pointId: "p1", value: 1 }]);
  });

  it("B 已连接后，A late close 不得关闭 B", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    const socketA = latestSocket();
    socketA.fireOpen();

    store.connectRealtime("device-b");
    const socketB = latestSocket();
    socketB.fireOpen();
    socketA.fireClose();

    expect(store.connected).toBe(true);
    expect(store.status).toBe("connected");
    expect(store.activeDeviceId).toBe("device-b");
    expect(socketB.closed).toBe(false);
  });

  it("B 已连接后，A late open 必须被忽略", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    const socketA = latestSocket();

    store.connectRealtime("device-b");
    const socketB = latestSocket();
    socketB.fireOpen();
    socketA.fireOpen();

    expect(store.connected).toBe(true);
    expect(store.status).toBe("connected");
    expect(store.activeDeviceId).toBe("device-b");
  });

  it("B 已连接后，A late error 必须被忽略", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    const socketA = latestSocket();

    store.connectRealtime("device-b");
    const socketB = latestSocket();
    socketB.fireOpen();
    socketA.fireError();

    expect(store.connected).toBe(true);
    expect(store.status).toBe("connected");
    expect(store.error).toBe("");
    expect(store.activeDeviceId).toBe("device-b");
  });

  it("首次连接失败时标记 unavailable 且不自动重连", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    const socketA = latestSocket();
    socketA.fireError();
    socketA.fireClose();
    vi.advanceTimersByTime(60_000);

    expect(store.enabled).toBe(false);
    expect(store.connected).toBe(false);
    expect(store.connecting).toBe(false);
    expect(store.status).toBe("unavailable");
    expect(store.reconnectAttempt).toBe(0);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("成功连接后异常断开时按指数退避重连", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireClose();

    expect(store.status).toBe("reconnecting");
    expect(store.reconnectAttempt).toBe(1);

    vi.advanceTimersByTime(999);
    expect(FakeWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeWebSocket.instances).toHaveLength(2);

    latestSocket().fireClose();
    expect(store.reconnectAttempt).toBe(2);
    vi.advanceTimersByTime(1_999);
    expect(FakeWebSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeWebSocket.instances).toHaveLength(3);
  });

  it("重连达到最大次数后停止并标记 unavailable", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireClose();

    const delays = [1_000, 2_000, 4_000, 8_000, 16_000];
    for (const delay of delays) {
      vi.advanceTimersByTime(delay);
      latestSocket().fireClose();
    }

    vi.advanceTimersByTime(60_000);

    expect(store.status).toBe("unavailable");
    expect(store.connected).toBe(false);
    expect(store.connecting).toBe(false);
    expect(store.reconnectAttempt).toBe(5);
    expect(FakeWebSocket.instances).toHaveLength(6);
  });

  it("手动关闭会取消 pending reconnect", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireClose();

    store.disableRealtime();
    vi.advanceTimersByTime(60_000);

    expect(store.enabled).toBe(false);
    expect(store.connected).toBe(false);
    expect(store.connecting).toBe(false);
    expect(store.activeDeviceId).toBe("");
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("invalid JSON 可观测且不清空已有 rows", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 1 }]));
    latestSocket().fireMessage("{broken json");

    expect(store.parseErrorCount).toBe(1);
    expect(store.lastParseError).toContain("INVALID_JSON");
    expect(store.rows("device-a")).toEqual([{ deviceId: "device-a", pointId: "p1", value: 1 }]);
  });

  it("unsupported payload 可观测且不清空已有 rows", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 1 }]));
    latestSocket().fireMessage(JSON.stringify({ foo: "bar" }));

    expect(store.parseErrorCount).toBe(1);
    expect(store.lastParseError).toContain("UNSUPPORTED_PAYLOAD");
    expect(store.rows("device-a")).toEqual([{ deviceId: "device-a", pointId: "p1", value: 1 }]);
  });

  it("合法空 payload 不计为 parse error", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireMessage("[]");

    expect(store.parseErrorCount).toBe(0);
    expect(store.rows("device-a")).toEqual([]);
    expect(store.canUseRows("device-a")).toBe(false);
  });

  it("相同 point identity 的消息会稳定 merge", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 1 }]));
    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 2 }]));

    expect(store.rows("device-a")).toEqual([{ deviceId: "device-a", pointId: "p1", value: 2 }]);
    expect(store.canUseRows("device-a")).toBe(true);
  });

  it("断开后立即让 HTTP fallback 可用；重连未收到新消息前不复用旧 WS rows", () => {
    const store = useWebSocketStore();

    store.connectRealtime("device-a");
    latestSocket().fireOpen();
    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 1 }]));
    expect(store.canUseRows("device-a")).toBe(true);

    latestSocket().fireClose();
    expect(store.canUseRows("device-a")).toBe(false);

    vi.advanceTimersByTime(1_000);
    latestSocket().fireOpen();
    expect(store.canUseRows("device-a")).toBe(false);

    latestSocket().fireMessage(JSON.stringify([{ pointId: "p1", value: 2 }]));
    expect(store.canUseRows("device-a")).toBe(true);
  });

  it("不同 Pinia store instance 的 socket runtime 不共享", () => {
    const piniaA = createPinia();
    const piniaB = createPinia();
    const storeA = useWebSocketStore(piniaA);
    const storeB = useWebSocketStore(piniaB);

    storeA.connectRealtime("device-a");
    const socketA = latestSocket();
    socketA.fireOpen();

    storeB.connectRealtime("device-b");
    const socketB = latestSocket();
    socketB.fireOpen();

    socketA.fireClose();

    expect(storeA.activeDeviceId).toBe("device-a");
    expect(storeB.activeDeviceId).toBe("device-b");
    expect(storeB.connected).toBe(true);
    expect(socketB.closed).toBe(false);
  });
});
