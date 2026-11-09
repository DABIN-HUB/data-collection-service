import { describe, expect, it } from "vitest";

import { CONFIG_SYNC_TYPES, normalizeSyncStatusItems } from "./config-sync-utils";

describe("config-sync-utils", () => {
  it("归一化配置同步状态响应", () => {
    const expectedLocalTime = new Date(1).toLocaleString("zh-CN", { hour12: false });

    expect(normalizeSyncStatusItems({ serviceId: "svc-1", lastSyncTime: 1, syncInterval: 30000, listenerCount: 2, consecutiveFailures: 0, sourceVersion: "v1", snapshotDeviceCount: 3 })).toEqual([
      { label: "服务实例", value: "svc-1" },
      { label: "最近同步", value: expectedLocalTime },
      { label: "同步间隔", value: "30000 ms" },
      { label: "监听器数量", value: "2" },
      { label: "连续失败", value: "0" },
      { label: "配置源版本", value: "v1" },
      { label: "快照设备数", value: "3" }
    ]);
  });

  it("为空时间和缺失状态提供稳定占位值", () => {
    expect(normalizeSyncStatusItems(null)).toEqual([
      { label: "服务实例", value: "-" },
      { label: "最近同步", value: "-" },
      { label: "同步间隔", value: "-" },
      { label: "监听器数量", value: "-" },
      { label: "连续失败", value: "-" },
      { label: "配置源版本", value: "-" },
      { label: "快照设备数", value: "-" }
    ]);
  });

  it("保留非法时间的原始值", () => {
    expect(normalizeSyncStatusItems({ lastSyncTime: Number.NaN })[1]).toEqual({
      label: "最近同步",
      value: "NaN"
    });
  });

  it("提供后端支持的局部同步类型", () => {
    expect(CONFIG_SYNC_TYPES.map((item) => item.type)).toEqual(["device", "points", "connection", "collection", "all"]);
  });
});
