import { describe, expect, it } from "vitest";

import { isLocalDevice, resolveDeviceStartMode } from "./device.store";
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
});
