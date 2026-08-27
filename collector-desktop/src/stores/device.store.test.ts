import { describe, expect, it } from "vitest";

import { isLocalDevice, normalizeDeviceViewModel, normalizeDeviceViewModelWithRuntimeStatus, resolveDeviceStartMode } from "./device.store";
import type { DeviceViewModel } from "@/types/device";

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
});
