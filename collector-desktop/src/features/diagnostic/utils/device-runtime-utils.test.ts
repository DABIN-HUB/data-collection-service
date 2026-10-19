import { describe, expect, it } from "vitest";

import { buildDeviceRuntimeSummary, buildUnavailableRunningFlagDetail, normalizeDeviceRunningFlag, normalizeDeviceRuntimeRows, normalizeDeviceStatusDetail, normalizeRunningDeviceIds, runtimePresentation, runtimeOperationMessage } from "./device-runtime-utils";

describe("device-runtime-utils", () => {
  it("旧服务端缺健康字段时不把 ready/connected 或 accepted 冒充采集健康", () => {
    const runtime = { deviceId: "a", phase: "WAITING_FIRST_SAMPLE", ready: true, connected: true };
    expect(runtimePresentation(runtime)).toMatchObject({ lifecycle: "等待首采", health: "未知", points: "有效 未知/未知 · 失败 未知 · 过期 未知 · 等待 未知", lastValid: "未知" });
    expect(runtimeOperationMessage(undefined, "START")).toContain("等待首采");
    expect(normalizeDeviceRuntimeRows([{ deviceId: "a" }])[0]?.connected).toBeUndefined();
  });

  it("统一展示后端三层事实及点位计数，过期快照不能继续显示健康", () => {
    const runtime = { deviceId: "a", phase: "ONLINE", desiredState: "RUNNING", transport: "CONNECTED", protocol: "READY", deviceHealth: "HEALTHY", goodPointCount: 2, participatingPointCount: 3, failedPointCount: 1, stalePointCount: 0, waitingPointCount: 0, lastValidSampleAt: 1000 };
    expect(normalizeDeviceRuntimeRows([runtime])[0]).toMatchObject(runtime);
    expect(runtimePresentation(runtime)).toMatchObject({ transportProtocol: "传输 已连接 / 协议 已就绪", health: "健康", points: "有效 2/3 · 失败 1 · 过期 0 · 等待 0" });
    expect(runtimePresentation(runtime, true).health).toBe("已过期（当前健康未知）");
  });
  it.each([
    ["ONLINE_HEALTHY", "在线健康"],
    ["ONLINE_PARTIAL", "在线部分有效"],
    ["ONLINE_NO_DATA", "在线无有效数据"],
    ["DEGRADED", "降级"],
    ["OFFLINE", "离线"]
  ])("将后端健康状态 %s 展示为中文且不由连接覆盖", (deviceHealth, health) => {
    expect(runtimePresentation({ deviceId: "a", connected: true, ready: true, deviceHealth }).health).toBe(health);
  });

  it("协议协商中与传输已连接分别展示", () => {
    expect(runtimePresentation({ deviceId: "a", transport: "CONNECTED", protocol: "NEGOTIATING", deviceHealth: "ONLINE_NO_DATA" }))
      .toMatchObject({ transportProtocol: "传输 已连接 / 协议 协商中", health: "在线无有效数据" });
  });

  it.each([
    [true, "已就绪"],
    [false, "未就绪"],
    [undefined, "未知"]
  ] as const)("就绪状态只采用明确的 ready=%s", (ready, label) => {
    const runtime = { deviceId: "a", phase: "ONLINE", connected: true, protocol: "READY", ready };
    expect(runtimePresentation(runtime)).toMatchObject({ ready: label, health: "未知" });
    expect(runtimePresentation(runtime, true).ready).toBe("未知");
  });

  it("停止快照保留完整健康投影及零计数，不把历史成功时间当作最近有效样本", () => {
    const runtime = {
      deviceId: "a", desiredState: "STOPPED", transport: "DISCONNECTED", protocol: "STOPPED", deviceHealth: "OFFLINE",
      healthReason: "采集器已停止", ready: false, configuredPointCount: 10, goodPointCount: 0, participatingPointCount: 0,
      failedPointCount: 0, stalePointCount: 0, waitingPointCount: 0, lastValidSampleAt: 0, lastSuccessfulCollectionAt: 900
    };
    expect(normalizeDeviceStatusDetail({ data: runtime })).toMatchObject(runtime);
    expect(runtimePresentation(runtime)).toMatchObject({
      desired: "停止", transportProtocol: "传输 未连接 / 协议 停止", health: "离线", ready: "未就绪",
      points: "有效 0/0 · 失败 0 · 过期 0 · 等待 0", lastValid: "未知", reason: "采集器已停止"
    });
  });

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

  it("运行标志检查失败不会被归一化成 stopped=false", () => {
    expect(buildUnavailableRunningFlagDetail("dev-1", "接口失败")).toEqual(expect.objectContaining({
      deviceId: "dev-1",
      running: undefined,
      isRunning: undefined,
      message: "运行状态：暂不可用",
      degradedReason: "接口失败"
    }));
  });
});
