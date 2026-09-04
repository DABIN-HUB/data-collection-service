import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { isLocalDevice, normalizeDeviceViewModel, normalizeDeviceViewModelWithRuntimeStatus, resolveDeviceStartMode, useDeviceStore } from "./device.store";
import type { DeviceViewModel } from "@/types/device";

const apiMocks = vi.hoisted(() => ({
  deleteLocalDevice: vi.fn(),
  getConfigDevices: vi.fn(),
  getDeviceDiff: vi.fn(),
  getDeviceRuntime: vi.fn(),
  getDeviceStatus: vi.fn(),
  reloadDevices: vi.fn(),
  startDevice: vi.fn(),
  startLocalDevice: vi.fn(),
  stopDevice: vi.fn(),
  triggerFullConfigSync: vi.fn()
}));

vi.mock("@/api/config.api", () => ({
  deleteLocalDevice: apiMocks.deleteLocalDevice,
  getDeviceDiff: apiMocks.getDeviceDiff,
  triggerFullConfigSync: apiMocks.triggerFullConfigSync
}));

vi.mock("@/api/device.api", () => ({
  getConfigDevices: apiMocks.getConfigDevices,
  getDeviceRuntime: apiMocks.getDeviceRuntime,
  getDeviceStatus: apiMocks.getDeviceStatus,
  reloadDevices: apiMocks.reloadDevices,
  startDevice: apiMocks.startDevice,
  startLocalDevice: apiMocks.startLocalDevice,
  stopDevice: apiMocks.stopDevice
}));

beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  apiMocks.getConfigDevices.mockResolvedValue({ devices: [] });
  apiMocks.getDeviceRuntime.mockResolvedValue([]);
  apiMocks.reloadDevices.mockResolvedValue({});
  apiMocks.startDevice.mockResolvedValue({});
  apiMocks.startLocalDevice.mockResolvedValue({});
  apiMocks.triggerFullConfigSync.mockResolvedValue({});
});

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

function device(overrides: Partial<DeviceViewModel>): DeviceViewModel {
  return {
    normalizedId: "dev-1",
    displayName: "设备1",
    displayGroup: "默认",
    displayProtocol: "MODBUS_TCP",
    ...overrides
  };
}

describe("device.store helpers", () => {
  it("识别本地临时设备", () => {
    expect(isLocalDevice(device({ temporaryConfig: true }))).toBe(true);
    expect(isLocalDevice(device({ configSource: "LOCAL" }))).toBe(true);
    expect(isLocalDevice(device({ configSource: "REMOTE" }))).toBe(false);
  });

  it("根据设备来源选择启动接口模式", () => {
    expect(resolveDeviceStartMode(device({ temporaryConfig: true }))).toBe("local");
    expect(resolveDeviceStartMode(device({ configSource: "REMOTE" }))).toBe("remote");
  });

  it("把后端设备信息归一成配置工作台视图模型", () => {
    expect(normalizeDeviceViewModel({ deviceId: "dev-1", deviceName: "温度站", groupName: "车间A", protocolType: "MODBUS_TCP" }, {
      "dev-1": { deviceId: "dev-1", running: true, connected: true }
    })).toMatchObject({
      normalizedId: "dev-1",
      displayName: "温度站",
      displayGroup: "车间A",
      displayProtocol: "MODBUS_TCP",
      runtime: { connected: true }
    });
  });

  it("运行态在线时覆盖配置态离线状态", () => {
    const view = normalizeDeviceViewModelWithRuntimeStatus({
      id: "dev-1",
      deviceName: "本地测试设备",
      status: "OFFLINE"
    }, {
      "dev-1": { deviceId: "dev-1", running: true, connected: true, phase: "ONLINE" }
    });

    expect(view.status).toBe("ONLINE");
    expect(view["configStatus"]).toBe("OFFLINE");
  });

  it("startSmart 按本地临时设备来源选择启动接口", async () => {
    apiMocks.getConfigDevices.mockResolvedValue({
      devices: [
        { deviceId: "local-1", deviceName: "本地设备", temporaryConfig: true },
        { deviceId: "remote-1", deviceName: "远端设备", configSource: "REMOTE" }
      ]
    });
    const store = useDeviceStore();
    store.devices = [
      device({ normalizedId: "local-1", temporaryConfig: true }),
      device({ normalizedId: "remote-1", configSource: "REMOTE" })
    ];

    await store.startSmart("local-1");
    await store.startSmart("remote-1");

    expect(apiMocks.startLocalDevice).toHaveBeenCalledWith("local-1");
    expect(apiMocks.startDevice).toHaveBeenCalledWith("remote-1");
  });

  it("syncRemoteDevices 先触发远端同步再重载设备并刷新 Store", async () => {
    const store = useDeviceStore();

    await store.syncRemoteDevices();

    expect(apiMocks.triggerFullConfigSync).toHaveBeenCalledTimes(1);
    expect(apiMocks.reloadDevices).toHaveBeenCalledTimes(1);
    expect(apiMocks.triggerFullConfigSync.mock.invocationCallOrder[0]).toBeLessThan(apiMocks.reloadDevices.mock.invocationCallOrder[0]);
    expect(apiMocks.getConfigDevices).toHaveBeenCalledTimes(1);
    expect(apiMocks.getDeviceRuntime).toHaveBeenCalledTimes(1);
  });

  it("refresh A → B 时，B 返回后 A 后返回仍保持 B 的 devices/runtimeMap", async () => {
    const devicesA = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const devicesB = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const runtimeA = createDeferred<Array<{ deviceId: string; connected: boolean; running?: boolean }>>();
    const runtimeB = createDeferred<Array<{ deviceId: string; connected: boolean; running?: boolean }>>();
    apiMocks.getConfigDevices
      .mockImplementationOnce(() => devicesA.promise)
      .mockImplementationOnce(() => devicesB.promise);
    apiMocks.getDeviceRuntime
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useDeviceStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    devicesB.resolve({ devices: [{ deviceId: "dev-b", deviceName: "设备B" }] });
    runtimeB.resolve([{ deviceId: "dev-b", connected: true, running: true }]);
    await refreshB;

    expect(store.devices.map((item) => item.normalizedId)).toEqual(["dev-b"]);
    expect(store.runtimeMap).toMatchObject({ "dev-b": { deviceId: "dev-b", connected: true } });

    devicesA.resolve({ devices: [{ deviceId: "dev-a", deviceName: "设备A" }] });
    runtimeA.resolve([{ deviceId: "dev-a", connected: false }]);
    await refreshA;

    expect(store.devices.map((item) => item.normalizedId)).toEqual(["dev-b"]);
    expect(store.runtimeMap).toMatchObject({ "dev-b": { deviceId: "dev-b", connected: true } });
    expect(store.runtimeMap["dev-a"]).toBeUndefined();
  });

  it("A 先结束而 B 仍 pending 时，loading 仍保持 true", async () => {
    const devicesA = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const devicesB = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const runtimeA = createDeferred<Array<{ deviceId: string; connected: boolean }>>();
    const runtimeB = createDeferred<Array<{ deviceId: string; connected: boolean }>>();
    apiMocks.getConfigDevices
      .mockImplementationOnce(() => devicesA.promise)
      .mockImplementationOnce(() => devicesB.promise);
    apiMocks.getDeviceRuntime
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useDeviceStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    devicesA.resolve({ devices: [{ deviceId: "dev-a", deviceName: "设备A" }] });
    runtimeA.resolve([{ deviceId: "dev-a", connected: true }]);
    await refreshA;

    expect(store.loading).toBe(true);
    expect(store.devices).toEqual([]);

    devicesB.resolve({ devices: [{ deviceId: "dev-b", deviceName: "设备B" }] });
    runtimeB.resolve([{ deviceId: "dev-b", connected: true }]);
    await refreshB;

    expect(store.loading).toBe(false);
    expect(store.devices.map((item) => item.normalizedId)).toEqual(["dev-b"]);
  });

  it("B 成功后，A 的 stale failure 不会覆盖 error", async () => {
    const devicesA = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const devicesB = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const runtimeA = createDeferred<Array<{ deviceId: string; connected: boolean }>>();
    const runtimeB = createDeferred<Array<{ deviceId: string; connected: boolean }>>();
    apiMocks.getConfigDevices
      .mockImplementationOnce(() => devicesA.promise)
      .mockImplementationOnce(() => devicesB.promise);
    apiMocks.getDeviceRuntime
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useDeviceStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    devicesB.resolve({ devices: [{ deviceId: "dev-b", deviceName: "设备B" }] });
    runtimeB.resolve([{ deviceId: "dev-b", connected: true }]);
    await refreshB;

    runtimeA.resolve([{ deviceId: "dev-a", connected: true }]);
    devicesA.reject(new Error("设备A加载失败"));
    await refreshA;

    expect(store.error).toBe("");
    expect(store.devices.map((item) => item.normalizedId)).toEqual(["dev-b"]);
  });

  it("同一次 latest refresh 内 devices 与 runtimeMap 保持同代结果，不与旧 generation 混合", async () => {
    const devicesA = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const devicesB = createDeferred<{ devices: Array<{ deviceId: string; deviceName: string }> }>();
    const runtimeA = createDeferred<Array<{ deviceId: string; connected: boolean; phase?: string }>>();
    const runtimeB = createDeferred<Array<{ deviceId: string; connected: boolean; phase?: string }>>();
    apiMocks.getConfigDevices
      .mockImplementationOnce(() => devicesA.promise)
      .mockImplementationOnce(() => devicesB.promise);
    apiMocks.getDeviceRuntime
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useDeviceStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    devicesB.resolve({ devices: [{ deviceId: "dev-b", deviceName: "设备B" }] });
    runtimeB.resolve([{ deviceId: "dev-b", connected: true, phase: "B" }]);
    await refreshB;

    devicesA.resolve({ devices: [{ deviceId: "dev-a", deviceName: "设备A" }] });
    runtimeA.resolve([{ deviceId: "dev-a", connected: true, phase: "A" }]);
    await refreshA;

    expect(store.devices).toHaveLength(1);
    expect(store.devices[0]).toMatchObject({ normalizedId: "dev-b", runtime: { deviceId: "dev-b", phase: "B" } });
    expect(store.runtimeMap).toMatchObject({ "dev-b": { deviceId: "dev-b", phase: "B" } });
    expect(store.runtimeMap["dev-a"]).toBeUndefined();
  });

  it("保留当前 partial semantics：runtime 成功且 device 失败时，仍更新 runtimeMap 并写入 error", async () => {
    apiMocks.getConfigDevices.mockRejectedValueOnce(new Error("设备列表失败"));
    apiMocks.getDeviceRuntime.mockResolvedValueOnce([{ deviceId: "dev-1", connected: true, running: true }]);
    const store = useDeviceStore();

    await store.refresh();

    expect(store.runtimeMap).toMatchObject({ "dev-1": { deviceId: "dev-1", connected: true } });
    expect(store.error).toBe("设备列表失败");
  });
});
