import { describe, expect, it } from "vitest";

import { MAX_POINT_IMPORT_ROWS, MAX_POINT_IMPORT_SIZE_BYTES, parsePointCsv, serializePointCsv, validatePointImportFile } from "@/features/point/utils/point-excel-utils";
import type { DataPoint } from "@/types/point";

describe("point-excel-utils", () => {
  it("把点位导出为可再次导入的 CSV 文本", () => {
    const points: DataPoint[] = [
      { pointId: "p1", pointCode: "temp", pointName: "温度", address: "40001", dataType: "FLOAT", readWrite: "R", unit: "℃", alarmEnabled: 1 }
    ];

    const csv = serializePointCsv(points);
    const imported = parsePointCsv(csv);

    expect(imported[0]).toMatchObject({
      pointCode: "temp",
      pointName: "温度",
      address: "40001",
      dataType: "FLOAT",
      readWrite: "R",
      unit: "℃",
      alarmEnabled: 1
    });
  });

  it("解析 CSV 时限制行数并保留逗号和引号", () => {
    const csv = [
      "点位编码,点位名称,地址,备注",
      "temp,\"温度,入口\",40001,\"带\"\"引号\"\"的备注\""
    ].join("\n");

    expect(parsePointCsv(csv, { maxRows: 1 })[0]).toMatchObject({
      pointCode: "temp",
      pointName: "温度,入口",
      remark: "带\"引号\"的备注"
    });
    expect(() => parsePointCsv(`${csv}\npress,压力,40002,`, { maxRows: 1 })).toThrow("最多允许导入");
  });

  it("导入文件校验限制 CSV 类型和大小", () => {
    expect(validatePointImportFile({ name: "points.csv", size: MAX_POINT_IMPORT_SIZE_BYTES })).toBeUndefined();
    expect(() => validatePointImportFile({ name: "points.xlsx", size: 10 })).toThrow("仅支持 CSV");
    expect(() => validatePointImportFile({ name: "points.csv", size: MAX_POINT_IMPORT_SIZE_BYTES + 1 })).toThrow("文件过大");
    expect(MAX_POINT_IMPORT_ROWS).toBeGreaterThan(0);
  });
});
