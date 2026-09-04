import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import {
  buildDeviceProtocolRequestContext,
  buildDeviceRequestContext,
  isSameDeviceProtocolRequestContext,
  isSameDeviceRequestContext,
  shouldCommitDeviceProtocolSave,
  type DeviceProtocolRequestContext,
  type DeviceRequestContext
} from "./device-request-lifecycle";

interface DeviceProtocolHarnessState {
  loading: boolean;
  schema: string | null;
  connection: string | null;
  error: string | null;
}

interface DeviceWorkbenchHarnessState {
  loading: boolean;
  rows: string[];
  selected: string | null;
  page: number;
  error: string | null;
}

interface DeviceDiffHarnessState {
  visible: boolean;
  text: string;
  error: string | null;
}

interface DevicePreviewHarnessState {
  rows: string[];
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

function createDeviceProtocolHarness(initialContext: DeviceProtocolRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceProtocolRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceProtocolHarnessState = {
    loading: false,
    schema: null,
    connection: null,
    error: null
  };

  async function load(request: Promise<{ schema: string; connection: string }>, snapshot: DeviceProtocolRequestContext) {
    const requestContext = buildDeviceProtocolRequestContext(snapshot.deviceId, snapshot.protocolKey);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceProtocolRequestContext(live.current.deviceId, live.current.protocolKey))) {
        return;
      }
      state.schema = result.schema;
      state.connection = result.connection;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceProtocolRequestContext(live.current.deviceId, live.current.protocolKey))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "协议连接配置加载失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceStatusHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state = {
    loading: false,
    detail: null as string | null,
    error: null as string | null
  };

  async function load(request: Promise<string>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.detail = result;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "连接状态检查失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceWorkbenchHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceWorkbenchHarnessState = {
    loading: false,
    rows: [],
    selected: null,
    page: 1,
    error: null
  };

  async function load(request: Promise<{ rows: string[]; selected: string | null; page: number }>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.rows = result.rows;
      state.selected = result.selected;
      state.page = result.page;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "点位运行数据加载失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceDiffHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceDiffHarnessState = {
    visible: false,
    text: "{}",
    error: null
  };

  async function load(request: Promise<string>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.text = result;
      state.visible = true;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "配置差异加载失败");
    }
  }

  return {
    live,
    state,
    load,
    switchDevice(deviceId: string) {
      live.current = buildDeviceRequestContext(deviceId);
      owner.invalidate();
      state.visible = false;
      state.text = "{}";
    },
    invalidate: () => owner.invalidate()
  };
}

function createDevicePreviewHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DevicePreviewHarnessState = {
    rows: []
  };

  async function load(request: Promise<string[]>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.rows = result;
    } catch {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.rows = [];
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("device-request-lifecycle", () => {
  it("same-protocol device switch 时，A 和 B 的 protocol context 仍然不同", () => {
    const deviceA = buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP");
    const deviceB = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");

    expect(isSameDeviceProtocolRequestContext(deviceA, deviceB)).toBe(false);
  });

  it("protocol config A → B 且协议相同，B 返回后 A 返回不会覆盖 B", async () => {
    const harness = createDeviceProtocolHarness(buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    const requestA = createDeferred<{ schema: string; connection: string }>();
    const requestB = createDeferred<{ schema: string; connection: string }>();

    void harness.load(requestA.promise, buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    await flushPromises();

    harness.live.current = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");
    void harness.load(requestB.promise, buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP"));
    await flushPromises();

    requestB.resolve({ schema: "schema-b", connection: "connection-b" });
    await flushPromises();
    requestA.resolve({ schema: "schema-a", connection: "connection-a" });
    await flushPromises();

    expect(harness.state.schema).toBe("schema-b");
    expect(harness.state.connection).toBe("connection-b");
    expect(harness.state.loading).toBe(false);
  });

  it("status owner 与 protocol owner 独立，protocol 新请求不会使 status ticket 失效", () => {
    const protocolOwner = createLatestRequestOwner(isSameDeviceProtocolRequestContext);
    const statusOwner = createLatestRequestOwner(isSameDeviceRequestContext);
    const workbenchOwner = createLatestRequestOwner(isSameDeviceRequestContext);

    const statusTicket = statusOwner.begin(buildDeviceRequestContext("device-a"));
    const workbenchTicket = workbenchOwner.begin(buildDeviceRequestContext("device-a"));
    protocolOwner.begin(buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    protocolOwner.begin(buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP"));

    expect(statusOwner.canCommit(statusTicket, buildDeviceRequestContext("device-a"))).toBe(true);
    expect(workbenchOwner.canCommit(workbenchTicket, buildDeviceRequestContext("device-a"))).toBe(true);
  });

  it("status A → B 时，A 后返回不会写到 B", async () => {
    const harness = createDeviceStatusHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve("status-b");
    await flushPromises();
    requestA.resolve("status-a");
    await flushPromises();

    expect(harness.state.detail).toBe("status-b");
    expect(harness.state.loading).toBe(false);
  });

  it("workbench rows A → B 时，A 后返回不会改变 B 的 rows/selection/page", async () => {
    const harness = createDeviceWorkbenchHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<{ rows: string[]; selected: string | null; page: number }>();
    const requestB = createDeferred<{ rows: string[]; selected: string | null; page: number }>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve({ rows: ["b-1", "b-2"], selected: "b-2", page: 1 });
    await flushPromises();
    requestA.resolve({ rows: ["a-1"], selected: "a-1", page: 3 });
    await flushPromises();

    expect(harness.state.rows).toEqual(["b-1", "b-2"]);
    expect(harness.state.selected).toBe("b-2");
    expect(harness.state.page).toBe(1);
    expect(harness.state.loading).toBe(false);
  });

  it("showDiff A pending 时切到 B，A resolve 后不会打开 A diff", async () => {
    const harness = createDeviceDiffHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.switchDevice("device-b");
    requestA.resolve("diff-a");
    await flushPromises();

    expect(harness.state.visible).toBe(false);
    expect(harness.state.text).toBe("{}");
    expect(harness.state.error).toBeNull();
  });

  it("save target snapshot 正确；保存 A 后切 B 时不把 A state 写进 B", () => {
    const target = buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP");
    const liveB = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");

    expect(shouldCommitDeviceProtocolSave(target, target)).toBe(true);
    expect(shouldCommitDeviceProtocolSave(target, liveB)).toBe(false);
  });

  it("preview A → B 时，B resolve 后 A later resolve 不会覆盖 B", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string[]>();
    const requestB = createDeferred<string[]>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve(["preview-b"]);
    await flushPromises();
    requestA.resolve(["preview-a"]);
    await flushPromises();

    expect(harness.state.rows).toEqual(["preview-b"]);
  });

  it("preview 的 stale failure 不会清空新设备结果", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string[]>();
    const requestB = createDeferred<string[]>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve(["preview-b"]);
    await flushPromises();
    requestA.reject(new Error("preview-a failed"));
    await flushPromises();

    expect(harness.state.rows).toEqual(["preview-b"]);
  });
});
