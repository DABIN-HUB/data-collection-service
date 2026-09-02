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
});
