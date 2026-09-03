import { describe, expect, it } from "vitest";

import { createLatestRealtimeRequestOwner, type RealtimeRequestContext } from "./realtime-request-lifecycle";

interface HarnessState {
  loading: boolean;
  rows: string[];
  singleResult: string | null;
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function createMainHarness(initialContext: RealtimeRequestContext) {
  const owner = createLatestRealtimeRequestOwner();
  const state: HarnessState = {
    loading: false,
    rows: [],
    singleResult: null
  };
  const live = { current: { ...initialContext } };

  async function load(request: Promise<string>) {
    const ticket = owner.begin({ ...live.current });
    state.loading = true;
    try {
      const result = await request;
      if (!owner.isCurrent(ticket, live.current)) {
        return;
      }
      state.rows = [result];
    } finally {
      if (owner.isCurrent(ticket, live.current)) {
        state.loading = false;
      }
    }
  }

  function unmount() {
    state.loading = false;
    owner.invalidate();
  }

  return { owner, state, live, load, unmount };
}

function createSingleHarness(initialContext: RealtimeRequestContext) {
  const owner = createLatestRealtimeRequestOwner();
  const state: HarnessState = {
    loading: false,
    rows: [],
    singleResult: null
  };
  const live = { current: { ...initialContext } };

  async function load(request: Promise<string>) {
    const ticket = owner.begin({ ...live.current });
    state.loading = true;
    try {
      const result = await request;
      if (!owner.isCurrent(ticket, live.current)) {
        return;
      }
      state.singleResult = result;
    } finally {
      if (owner.isCurrent(ticket, live.current)) {
        state.loading = false;
      }
    }
  }

  return { owner, state, live, load };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("realtime-request-lifecycle", () => {
  it("A → B 时后返回的旧请求不会覆盖新设备结果", async () => {
    const harness = createMainHarness({ mode: "device", deviceId: "device-a" });
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.current = { mode: "device", deviceId: "device-b" };
    void harness.load(requestB.promise);
    await flushPromises();

    requestB.resolve("device-b");
    await flushPromises();
    requestA.resolve("device-a");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b"]);
  });

  it("A 先返回、B 后返回时，A 不会在 B 活跃期间提前提交或释放 loading", async () => {
    const harness = createMainHarness({ mode: "device", deviceId: "device-a" });
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();
    harness.live.current = { mode: "device", deviceId: "device-b" };
    void harness.load(requestB.promise);
    await flushPromises();

    requestA.resolve("device-a");
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.loading).toBe(true);

    requestB.resolve("device-b");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b"]);
    expect(harness.state.loading).toBe(false);
  });

  it("单设备 → 全设备不会串台", async () => {
    const harness = createMainHarness({ mode: "device", deviceId: "device-a" });
    const requestA = createDeferred<string>();
    const requestAll = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();
    harness.live.current = { mode: "all", deviceId: "" };
    void harness.load(requestAll.promise);
    await flushPromises();

    requestAll.resolve("all-devices");
    await flushPromises();
    requestA.resolve("device-a");
    await flushPromises();

    expect(harness.state.rows).toEqual(["all-devices"]);
  });

  it("全设备 → 单设备不会串台", async () => {
    const harness = createMainHarness({ mode: "all", deviceId: "" });
    const requestAll = createDeferred<string>();
    const requestA = createDeferred<string>();

    void harness.load(requestAll.promise);
    await flushPromises();
    harness.live.current = { mode: "device", deviceId: "device-a" };
    void harness.load(requestA.promise);
    await flushPromises();

    requestA.resolve("device-a");
    await flushPromises();
    requestAll.resolve("all-devices");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-a"]);
  });

  it("RealtimeDataPanel 的旧 HTTP fallback 响应不会覆盖新 deviceId", async () => {
    const harness = createMainHarness({ mode: "panel", deviceId: "device-a" });
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();
    harness.live.current = { mode: "panel", deviceId: "device-b" };
    void harness.load(requestB.promise);
    await flushPromises();

    requestB.resolve("device-b");
    await flushPromises();
    requestA.resolve("device-a");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b"]);
  });

  it("单点查询使用独立 deviceId + pointId snapshot，旧响应不会覆盖新结果", async () => {
    const harness = createSingleHarness({ mode: "single", deviceId: "device-a", pointId: "point-1" });
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();
    harness.live.current = { mode: "single", deviceId: "device-b", pointId: "point-2" };
    void harness.load(requestB.promise);
    await flushPromises();

    requestB.resolve("device-b/point-2");
    await flushPromises();
    requestA.resolve("device-a/point-1");
    await flushPromises();

    expect(harness.state.singleResult).toBe("device-b/point-2");
  });

  it("卸载后旧请求返回不会再提交状态", async () => {
    const harness = createMainHarness({ mode: "device", deviceId: "device-a" });
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();
    harness.unmount();

    requestA.resolve("device-a");
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.loading).toBe(false);
  });
});
