import { describe, expect, it } from "vitest";

import { CONFIG_SYNC_TYPES, buildConfigExportFilename, buildConfigImportRequest, countConfigImportBundles, normalizeConfigExportText, normalizeSyncStatusItems, parseConfigImportText } from "./config-utils";

describe("config-utils", () => {
  it("把导出响应归一化为可展示 JSON 文本", () => {
    expect(normalizeConfigExportText({ devices: [{ deviceId: "d1" }] })).toContain('"deviceId": "d1"');
    expect(normalizeConfigExportText('{"devices":[]}')).toBe('{"devices":[]}');
  });

  it("解析导入 JSON 并拒绝空内容", () => {
    expect(parseConfigImportText('{"devices":[]}')).toEqual({ devices: [] });
    expect(() => parseConfigImportText("  ")).toThrow("配置导入内容不能为空");
    expect(() => parseConfigImportText("{")).toThrow("配置导入 JSON 格式错误");
  });

  it("构造后端导入请求", () => {
    expect(buildConfigImportRequest({ bundles: [{ device: { deviceId: "d1" } }] }, true)).toEqual({ bundles: [{ device: { deviceId: "d1" } }], reloadAfterImport: true });
    expect(buildConfigImportRequest([{ device: { deviceId: "d2" } }], false)).toEqual({ bundles: [{ device: { deviceId: "d2" } }], reloadAfterImport: false });
    expect(buildConfigImportRequest({ device: { deviceId: "d3" } }, false)).toEqual({ bundles: [{ device: { deviceId: "d3" } }], reloadAfterImport: false });
  });

  it("为设备测试配置生成导出文件名和导入包数量", () => {
    const date = new Date("2026-08-27T10:20:30.456+08:00");
    expect(buildConfigExportFilename(date)).toBe("collector-device-config-2026-08-27T02-20-30-456Z.json");
    expect(countConfigImportBundles({ bundles: [{ device: { deviceId: "d1" } }, { device: { deviceId: "d2" } }] })).toBe(2);
    expect(countConfigImportBundles({ device: { deviceId: "d3" } })).toBe(1);
  });

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
