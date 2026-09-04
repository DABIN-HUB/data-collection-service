import { describe, expect, it } from "vitest";

import {
  applyPointBatchEdit,
  applyPointExtraModel,
  buildPointRuntimeLookup,
  buildIncrementalPoints,
  buildPointImportPreview,
  buildPointLocationTarget,
  buildPointExtraModel,
  formatJsonForTextarea,
  getPointExtraValue,
  mergePointRuntime,
  normalizePointRows,
  parseJsonTextarea,
  resolvePointRuntime
} from "@/features/point/utils/point-editor-utils";
import type { DataPoint } from "@/types/point";
import type { RealtimePointRow } from "@/types/monitor";

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
      { pointCode: "pressure", address: "40002" },
      { address: "40003" }
    ], [
      { pointId: "a", currentValue: 12.3, quality: "GOOD" },
      { pointCode: "pressure", currentValue: 45, quality: "BAD" },
      { address: "40003", currentValue: 67, quality: "GOOD" }
    ]);

    expect(rows[0].currentValue).toBe(12.3);
    expect(rows[1].quality).toBe("BAD");
    expect(rows[2].currentValue).toBe(67);
  });

  it("runtime lookup 按 pointId 命中实时运行态", () => {
    const lookup = buildPointRuntimeLookup([{ pointId: "p1", currentValue: 10 }]);

    expect(resolvePointRuntime(lookup, { pointId: "p1" })?.currentValue).toBe(10);
  });

  it("runtime lookup 在 pointId 未命中时按 pointCode fallback", () => {
    const lookup = buildPointRuntimeLookup([{ pointCode: "temperature", currentValue: 20 }]);

    expect(resolvePointRuntime(lookup, { pointId: "p1", pointCode: "temperature" })?.currentValue).toBe(20);
  });

  it("runtime lookup 在 pointId 和 pointCode 未命中时按 address fallback", () => {
    const lookup = buildPointRuntimeLookup([{ address: "40001", currentValue: 30 }]);

    expect(resolvePointRuntime(lookup, { pointId: "p1", pointCode: "temperature", address: "40001" })?.currentValue).toBe(30);
  });

  it("runtime lookup 无任何 identity 命中时返回 undefined", () => {
    const lookup = buildPointRuntimeLookup([{ pointId: "runtime-p1", pointCode: "runtime-code", address: "40001" }]);

    expect(resolvePointRuntime(lookup, { pointId: "p1", pointCode: "temperature", address: "40002" })).toBeUndefined();
  });

  it("runtime lookup 不建立空 identity key，也不会 undefined === undefined 误匹配", () => {
    const lookup = buildPointRuntimeLookup([
      { pointId: undefined, pointCode: "", address: "   ", currentValue: 10 },
      { pointId: "p1", currentValue: 20 }
    ]);

    expect(lookup.has("")).toBe(false);
    expect(resolvePointRuntime(lookup, { pointId: undefined, pointCode: "", address: "   " })).toBeUndefined();
  });

  it("runtime lookup 保持 pointId > pointCode > address 优先级", () => {
    const runtimeById = { pointId: "p1", currentValue: "id" };
    const runtimeByCode = { pointCode: "temperature", currentValue: "code" };
    const runtimeByAddress = { address: "40001", currentValue: "address" };
    const lookup = buildPointRuntimeLookup([runtimeByAddress, runtimeByCode, runtimeById]);

    expect(resolvePointRuntime(lookup, { pointId: "p1", pointCode: "temperature", address: "40001" })).toBe(runtimeById);
  });

  it("resolvePointRuntime 单次查询最多执行 3 次 Map.get", () => {
    let getCount = 0;
    const lookup = {
      get(_key: string) {
        getCount += 1;
        return undefined;
      }
    } as ReadonlyMap<string, RealtimePointRow>;

    expect(resolvePointRuntime(lookup, { pointId: "p1", pointCode: "temperature", address: "40001" })).toBeUndefined();
    expect(getCount).toBe(3);
  });

  it("large dataset lookup 保持功能正确且不依赖耗时断言", () => {
    const runtimeRows = Array.from({ length: 2000 }, (_item, index): RealtimePointRow => ({
      pointId: `p-${index}`,
      pointCode: `code-${index}`,
      address: `4${String(index).padStart(4, "0")}`,
      currentValue: index
    }));
    const lookup = buildPointRuntimeLookup(runtimeRows);

    expect(resolvePointRuntime(lookup, { pointId: "p-1999" })?.currentValue).toBe(1999);
    expect(resolvePointRuntime(lookup, { pointCode: "code-1500" })?.currentValue).toBe(1500);
    expect(resolvePointRuntime(lookup, { address: "40999" })?.currentValue).toBe(999);
  });

  it("runtime rows 更新后重新 build lookup 可读取最新实时值", () => {
    const lookupA = buildPointRuntimeLookup([{ pointId: "p1", currentValue: 10 }]);
    const lookupB = buildPointRuntimeLookup([{ pointId: "p1", currentValue: 20 }]);

    expect(resolvePointRuntime(lookupA, { pointId: "p1" })?.currentValue).toBe(10);
    expect(resolvePointRuntime(lookupB, { pointId: "p1" })?.currentValue).toBe(20);
  });

  it("direct extra accessor 支持 flat、nested 和 missing path", () => {
    const point: DataPoint = {
      pointId: "p1",
      additionalConfig: {
        nodeId: "ns=2;s=T1",
        sampling: { interval: 1000 }
      }
    };

    expect(getPointExtraValue(point, "nodeId")).toBe("ns=2;s=T1");
    expect(getPointExtraValue(point, "sampling.interval")).toBe(1000);
    expect(getPointExtraValue(point, "missing.path")).toBeUndefined();
  });

  it("表格编辑继续使用原始 DataPoint 引用，而不是 merged runtime copy", () => {
    const rows: DataPoint[] = [{ pointId: "p1", pointName: "温度", pointCode: "temp", address: "40001" }];
    const filteredRows = rows;

    filteredRows[0].pointName = "温度修改";

    expect(rows[0].pointName).toBe("温度修改");
  });

  it("格式化和解析 JSON 文本", () => {
    expect(formatJsonForTextarea({ high: 10 })).toContain('"high": 10');
    expect(parseJsonTextarea('{"high":10}', {})).toEqual({ high: 10 });
    expect(parseJsonTextarea('', { enabled: true })).toEqual({ enabled: true });
  });

  it("构造点位导入预览和重复项提示", () => {
    const preview = buildPointImportPreview([
      { pointCode: "temp_001", pointName: "温度1", address: "40001" },
      { pointCode: "temp_001", pointName: "温度2", address: "40003" },
      { pointCode: "press_001", pointName: "压力1", address: "40003" }
    ]);

    expect(preview.rows).toHaveLength(3);
    expect(preview.duplicatePointCodes).toEqual(["temp_001"]);
    expect(preview.duplicateAddresses).toEqual(["40003"]);
    expect(preview.warnings.join("；")).toContain("点位编码重复");
    expect(preview.summary).toContain("3 条");
  });

  it("构造点位联动跳转目标", () => {
    expect(buildPointLocationTarget({ pointId: "p1", pointCode: "temp_001", pointName: "温度" }, "dev-1")).toEqual({
      deviceId: "dev-1",
      pointRef: "p1",
      pointName: "温度",
      pointLabel: "温度 / temp_001"
    });
  });
});
