import { describe, expect, it } from "vitest";

import { CONFIG_SYNC_TYPES, normalizeSyncStatusItems } from "./config-sync-utils";

describe("config-sync-utils", () => {
  it("归一化配置同步状态响应", () => {
    expect(normalizeSyncStatusItems({ serviceId: "svc-1", lastSyncTime: 1, syncInterval: 30000, listenerCount: 2, consecutiveFailures: 0, sourceVersion: "v1", snapshotDeviceCount: 3 })).toEqual([
      { label: "服务实例", value: "svc-1" },
      { label: "最近同步", value: "1970/1/1 08:00:00" },
      { label: "同步间隔", value: "30000 ms" },
      { label: "监听器数量", value: "2" },
      { label: "连续失败", value: "0" },
      { label: "配置源版本", value: "v1" },
      { label: "快照设备数", value: "3" }
    ]);
  });

  it("提供后端支持的局部同步类型", () => {
    expect(CONFIG_SYNC_TYPES.map((item) => item.type)).toEqual(["device", "points", "connection", "collection", "all"]);
  });
});
