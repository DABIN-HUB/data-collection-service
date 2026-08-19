import { describe, expect, it } from "vitest";

import {
  applyPointBatchEdit,
  applyPointExtraModel,
  buildIncrementalPoints,
  buildPointExtraModel,
  formatJsonForTextarea,
  mergePointRuntime,
  normalizePointRows,
  parseJsonTextarea
} from "./point-editor-utils";
import type { DataPoint } from "@/types/point";

describe("point-editor-utils", () => {
  it("按 Modbus 地址步长批量生成点位草稿", () => {
    expect(buildIncrementalPoints({
      count: 3,
      baseAddress: "40001",
      addressStep: 2,
      pointCodePrefix: "temp",
      pointNamePrefix: "温度",
      dataType: "FLOAT",
      readWrite: "R"
    }).map((point) => [point.pointCode, point.pointName, point.address])).toEqual([
      ["temp_001", "温度001", "40001"],
      ["temp_002", "温度002", "40003"],
      ["temp_003", "温度003", "40005"]
    ]);
  });

  it("批量编辑只修改被选中的字段", () => {
    const rows: DataPoint[] = [
      { pointId: "p1", pointCode: "temp", pointName: "温度", address: "40001", dataType: "FLOAT", readWrite: "R", alarmEnabled: 0, unit: "℃" },
      { pointId: "p2", pointCode: "press", pointName: "压力", address: "40003", dataType: "FLOAT", readWrite: "R", alarmEnabled: 0, unit: "MPa" }
    ];

    const updated = applyPointBatchEdit(rows, ["p1"], {
      fields: ["alarmEnabled", "unit"],
      values: { alarmEnabled: 1, unit: "K" }
    });

    expect(updated[0]).toMatchObject({ pointId: "p1", alarmEnabled: 1, unit: "K" });
    expect(updated[1]).toMatchObject({ pointId: "p2", alarmEnabled: 0, unit: "MPa" });
  });

  it("为缺少 pointId 的点位补齐稳定本地编辑标识", () => {
    const rows = normalizePointRows([{ pointCode: "run", pointName: "运行", address: "00001" }]);

    expect(rows[0].pointId).toBe("local-run");
    expect(rows[0].status).toBe(1);
  });

  it("从 additionalConfig 读写协议动态 pointFields", () => {
    const fields = [
      { name: "nodeId", label: "节点" },
      { name: "sampling.interval", label: "采样间隔" }
    ];
    const point = { pointCode: "p1", additionalConfig: { nodeId: "ns=2;s=T1", sampling: { interval: 1000 } } };

    expect(buildPointExtraModel(fields, point)).toEqual({ nodeId: "ns=2;s=T1", "sampling.interval": 1000 });
    expect(applyPointExtraModel(point, fields, { nodeId: "ns=2;s=T2", "sampling.interval": 2000 }).additionalConfig).toEqual({
      nodeId: "ns=2;s=T2",
      sampling: { interval: 2000 }
    });
  });

  it("按 pointId、pointCode 或 address 合并实时运行态", () => {
    const rows = mergePointRuntime([
      { pointId: "a", pointCode: "temperature", address: "40001" },
      { pointCode: "pressure", address: "40002" }
    ], [
      { pointId: "a", currentValue: 12.3, quality: "GOOD" },
      { pointCode: "pressure", currentValue: 45, quality: "BAD" }
    ]);

    expect(rows[0].currentValue).toBe(12.3);
    expect(rows[1].quality).toBe("BAD");
  });

  it("格式化和解析 JSON 文本", () => {
    expect(formatJsonForTextarea({ high: 10 })).toContain('"high": 10');
    expect(parseJsonTextarea('{"high":10}', {})).toEqual({ high: 10 });
    expect(parseJsonTextarea('', { enabled: true })).toEqual({ enabled: true });
  });
});
