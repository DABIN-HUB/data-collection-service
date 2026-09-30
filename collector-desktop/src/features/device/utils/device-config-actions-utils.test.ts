import { describe, expect, it } from "vitest";

import { buildDeviceConfigActionMessage, DEVICE_CONFIG_ACTIONS, normalizeDeviceConfigActionResult } from "./device-config-actions-utils";

describe("device-config-actions-utils", () => {
  it("定义单设备配置刷新和清理动作", () => {
    expect(DEVICE_CONFIG_ACTIONS.map((item) => item.type)).toEqual(["refresh", "clear"]);
    expect(DEVICE_CONFIG_ACTIONS.find((item) => item.type === "clear")?.confirmText).toContain("不会删除远端配置");
  });

  it("归一化 ApiResult 包裹的设备操作响应", () => {
    expect(normalizeDeviceConfigActionResult({ code: 200, msg: "成功", data: { deviceId: "dev-1" } }, "fallback")).toEqual({ deviceId: "dev-1", message: "成功" });
  });

  it("响应缺少消息时生成中文默认提示", () => {
    expect(buildDeviceConfigActionMessage("refresh", { deviceId: "dev-1", message: "" })).toBe("设备 dev-1 配置缓存已刷新");
    expect(buildDeviceConfigActionMessage("clear", { deviceId: "dev-1", message: "" })).toBe("设备 dev-1 配置缓存已清理");
  });
});
