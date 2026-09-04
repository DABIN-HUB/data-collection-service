import { describe, expect, it } from "vitest";

import {
  buildRealtimeSummary,
  extractRealtimeDeviceIds,
  normalizeAllDeviceRealtimeRows,
  normalizeRealtimeRows,
  normalizeSinglePointRealtimeRow,
  realtimeAddress,
  realtimeProcessingText,
  realtimeQualityClass,
  realtimeQualityText,
  realtimeScale,
  realtimeValueText
} from "./realtime-utils";

describe("realtime-utils", () => {
  it("归一化设备实时数据 Map 响应", () => {
    expect(normalizeRealtimeRows({ deviceId: "dev-1", data: { p1: { pointName: "温度", value: 21, qualityLevel: "GOOD" } } })).toEqual([
      { deviceId: "dev-1", pointId: "p1", pointName: "温度", value: 21, qualityLevel: "GOOD" }
    ]);
  });

  it("归一化通用请求解包后的顶层点位 Map 响应", () => {
    expect(normalizeRealtimeRows({
      p1: { pointName: "温度", value: 21, qualityLevel: "GOOD" },
      p2: { pointName: "湿度", value: 60, qualityLevel: "GOOD" }
    }, "dev-1")).toEqual([
      { deviceId: "dev-1", pointId: "p1", pointName: "温度", value: 21, qualityLevel: "GOOD" },
      { deviceId: "dev-1", pointId: "p2", pointName: "湿度", value: 60, qualityLevel: "GOOD" }
    ]);
  });

  it("不把 DataController 设备摘要误归一化为实时点位行", () => {
    expect(normalizeRealtimeRows({ devices: [{ deviceId: "dev-1", pointCount: 2 }] })).toEqual([]);
    expect(normalizeRealtimeRows({ data: [{ deviceId: "dev-2", pointId: "p2", value: 1 }] })).toEqual([{ deviceId: "dev-2", pointId: "p2", value: 1 }]);
  });

  it("从 DataController 设备摘要提取全设备实时查询 deviceId", () => {
    expect(extractRealtimeDeviceIds({ devices: [{ deviceId: "dev-1", pointCount: 2 }, { deviceId: "", pointCount: 1 }] })).toEqual(["dev-1"]);
  });

  it("归一化全部设备聚合实时响应，并跳过失败设备", () => {
    expect(normalizeAllDeviceRealtimeRows({
      status: "success",
      deviceCount: 2,
      dataCount: 2,
      devices: [
        { status: "success", deviceId: "dev-1", dataCount: 1, data: { p1: { pointName: "温度", value: 21 } } },
        { status: "error", deviceId: "dev-2", dataCount: 0, message: "设备不存在或无数据点" }
      ]
    })).toEqual([
      { deviceId: "dev-1", pointId: "p1", pointName: "温度", value: 21 }
    ]);
  });

  it("归一化单点实时响应", () => {
    expect(normalizeSinglePointRealtimeRow({ deviceId: "dev-1", pointId: "p1", data: { value: 12 } })).toEqual({ deviceId: "dev-1", pointId: "p1", value: 12 });
  });

  it("统计实时数据摘要", () => {
    expect(buildRealtimeSummary([{ qualityLevel: "GOOD" }, { qualityLevel: "A" }, { qualityLevel: "BAD" }, { qualityAvailable: false }])).toEqual({ total: 4, good: 2, bad: 2 });
  });

  it("格式化实时行展示文本", () => {
    const row = {
      address: "7",
      scale: 0.5,
      value: 12.34567,
      qualityLevel: "BAD",
      processCostMs: 42
    };
    expect(realtimeAddress(row)).toBe("7");
    expect(realtimeScale(row)).toBe("0.5");
    expect(realtimeValueText(row)).toBe("12.3457");
    expect(realtimeQualityText(row)).toBe("异常");
    expect(realtimeQualityClass(row)).toBe("is-bad");
    expect(realtimeProcessingText(row)).toBe("42 ms");
  });
});
