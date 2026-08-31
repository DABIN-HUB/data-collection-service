import { describe, expect, it } from "vitest";

import { buildDeviceRuntimeSummary, normalizeDeviceRunningFlag, normalizeDeviceRuntimeRows, normalizeDeviceStatusDetail, normalizeRunningDeviceIds } from "./device-runtime-utils";

describe("device-runtime-utils", () => {
  it("归一化运行设备 ID 列表", () => {
    expect(normalizeRunningDeviceIds({ code: 200, data: ["dev-1", "dev-2"], count: 2 })).toEqual(["dev-1", "dev-2"]);
    expect(normalizeRunningDeviceIds([{ deviceId: "dev-3" }])).toEqual(["dev-3"]);
  });

  it("归一化运行态快照列表", () => {
    expect(normalizeDeviceRuntimeRows({ data: [{ deviceId: "dev-1", phase: "RUNNING", running: true, connected: true, consecutiveFailures: 0 }] })).toEqual([
      expect.objectContaining({ deviceId: "dev-1", phase: "RUNNING", running: true, connected: true, consecutiveFailures: 0 })
    ]);
  });

  it("归一化单设备状态详情和运行布尔响应", () => {
    expect(normalizeDeviceStatusDetail({ msg: "成功", data: { deviceId: "dev-1", isRunning: true, connected: false } }, "fallback")).toEqual(expect.objectContaining({ deviceId: "dev-1", running: true, connected: false, message: "成功" }));
    expect(normalizeDeviceRunningFlag({ deviceId: "dev-1", running: true })).toBe(true);
    expect(normalizeDeviceRunningFlag({ data: false })).toBe(false);
  });

  it("统计运行态摘要", () => {
    expect(buildDeviceRuntimeSummary([{ deviceId: "a", running: true, connected: true }, { deviceId: "b", running: true, connected: false, consecutiveFailures: 2 }, { deviceId: "c", running: false }], 4)).toEqual({ total: 4, running: 2, connected: 1, abnormal: 1 });
  });
});
