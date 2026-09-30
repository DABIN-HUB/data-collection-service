import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import {
  buildPointRealtimeRequestContext,
  isSamePointRealtimeRequestContext
} from "./point-realtime-lifecycle";

interface PointRealtimeHarnessState {
  loading: boolean;
  rows: string[];
  error: string;
  fetchArgs: string[];
  normalizeArgs: Array<{ response: string; deviceId: string }>;
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

function createPointRealtimeHarness(initialDeviceId: string) {
  const owner = createLatestRequestOwner(isSamePointRealtimeRequestContext);
  const live = { deviceId: initialDeviceId };
  const state: PointRealtimeHarnessState = {
    loading: false,
    rows: [],
    error: "",
    fetchArgs: [],
    normalizeArgs: []
  };

  async function load(request: Promise<string>) {
    const requestContext = buildPointRealtimeRequestContext(live.deviceId);
    if (!requestContext.deviceId) {
      owner.invalidate();
      state.rows = [];
      state.error = "";
      state.loading = false;
      return;
    }

    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = "";

    try {
      state.fetchArgs.push(requestContext.deviceId);
      const response = await request;
      state.normalizeArgs.push({ response, deviceId: requestContext.deviceId });
      const nextRows = [`${requestContext.deviceId}:${response}`];
      if (!owner.canCommit(ticket, currentContext())) {
        return;
      }
      state.rows = nextRows;
    } catch (error) {
      if (!owner.canCommit(ticket, currentContext())) {
        return;
      }
      state.error = error instanceof Error ? error.message : "实时数据加载失败";
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  function currentContext() {
    return buildPointRealtimeRequestContext(live.deviceId);
  }

  function clearDevice() {
    live.deviceId = "";
    owner.invalidate();
    state.rows = [];
    state.error = "";
    state.loading = false;
  }

  function unmount() {
    owner.invalidate();
    state.loading = false;
  }

  return { state, live, load, clearDevice, unmount };
}

describe("point-realtime-lifecycle", () => {
  it("B 先返回、A 后返回时最终 rows 保持 B", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    void harness.load(requestB.promise);
    await flushPromises();

    requestB.resolve("payload-b");
    await flushPromises();
    requestA.resolve("payload-a");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b:payload-b"]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("A 先返回、B 仍 pending 时不提交 A 且 loading 保持 true，B 返回后再提交", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    void harness.load(requestB.promise);
    await flushPromises();

    requestA.resolve("payload-a");
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(true);

    requestB.resolve("payload-b");
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b:payload-b"]);
    expect(harness.state.loading).toBe(false);
  });

  it("A stale error 不会污染 B 页面", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    void harness.load(requestB.promise);
    await flushPromises();

    requestB.resolve("payload-b");
    await flushPromises();
    requestA.reject(new Error("device-a failed"));
    await flushPromises();

    expect(harness.state.rows).toEqual(["device-b:payload-b"]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("context changed without new request 时，旧 success 不提交但 loading 可释放", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    requestA.resolve("payload-a");
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("context changed without new request 时，旧 error 不提交但 loading 可释放", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    requestA.reject(new Error("device-a failed"));
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("no-device 会 invalidate 旧请求并保持 loading 关闭", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.clearDevice();
    requestA.resolve("payload-a");
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("unmount 后旧请求返回不会提交 rows 或 error", async () => {
    const harness = createPointRealtimeHarness("device-a");
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.unmount();
    requestA.reject(new Error("device-a failed"));
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.error).toBe("");
    expect(harness.state.loading).toBe(false);
  });

  it("请求开始后的 fetch 与 normalize 始终使用 immutable deviceId snapshot", async () => {
    const harness = createPointRealtimeHarness(" device-a ");
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise);
    await flushPromises();

    harness.live.deviceId = "device-b";
    requestA.resolve("payload-a");
    await flushPromises();

    expect(harness.state.fetchArgs).toEqual(["device-a"]);
    expect(harness.state.normalizeArgs).toEqual([{ response: "payload-a", deviceId: "device-a" }]);
  });
});
