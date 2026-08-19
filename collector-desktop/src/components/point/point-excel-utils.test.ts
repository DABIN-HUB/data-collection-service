import { describe, expect, it } from "vitest";

import { parsePointWorkbook, serializePointWorkbook } from "./point-excel-utils";
import type { DataPoint } from "@/types/point";

describe("point-excel-utils", () => {
  it("把点位导出为可再次导入的 Excel 二进制", () => {
    const points: DataPoint[] = [
      { pointId: "p1", pointCode: "temp", pointName: "温度", address: "40001", dataType: "FLOAT", readWrite: "R", unit: "℃", alarmEnabled: 1 }
    ];

    const workbook = serializePointWorkbook(points);
    const imported = parsePointWorkbook(workbook);

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
});
