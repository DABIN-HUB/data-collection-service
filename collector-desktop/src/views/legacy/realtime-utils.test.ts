import { describe, expect, it } from "vitest";

import { buildRealtimeSummary, normalizeRealtimeRows, normalizeSinglePointRealtimeRow } from "./realtime-utils";

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

  it("归一化全部设备摘要和数组响应", () => {
    expect(normalizeRealtimeRows({ devices: [{ deviceId: "dev-1", pointCount: 2 }] })).toEqual([{ deviceId: "dev-1", pointCount: 2 }]);
    expect(normalizeRealtimeRows({ data: [{ deviceId: "dev-2", pointId: "p2", value: 1 }] })).toEqual([{ deviceId: "dev-2", pointId: "p2", value: 1 }]);
  });

  it("归一化单点实时响应", () => {
    expect(normalizeSinglePointRealtimeRow({ deviceId: "dev-1", pointId: "p1", data: { value: 12 } })).toEqual({ deviceId: "dev-1", pointId: "p1", value: 12 });
  });

  it("统计实时数据摘要", () => {
    expect(buildRealtimeSummary([{ qualityLevel: "GOOD" }, { qualityLevel: "A" }, { qualityLevel: "BAD" }, { qualityAvailable: false }])).toEqual({ total: 4, good: 2, bad: 2 });
  });
});
