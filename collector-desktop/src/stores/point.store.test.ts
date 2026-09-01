import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { usePointStore } from "./point.store";
import type { DataPoint } from "@/types/point";

describe("point.store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("新增空点位时写入当前设备并补齐默认字段", () => {
    const store = usePointStore();

    store.addEmptyPoint("dev-1");

    expect(store.getPoints("dev-1")).toHaveLength(1);
    expect(store.getPoints("dev-1")[0]).toMatchObject({
      pointId: "local-point_001",
      pointCode: "point_001",
      pointName: "点位001",
      address: "40001",
      dataType: "FLOAT",
      readWrite: "R",
      alarmEnabled: 0,
      status: 1
    });
    expect(store.getPoints("other")).toEqual([]);
  });

  it("追加批量生成点位时保持数值地址递增", () => {
    const store = usePointStore();

    store.appendGeneratedPoints("dev-1", {
      count: 2,
      baseAddress: "40001",
      addressStep: 2,
      pointCodePrefix: "temp",
      pointNamePrefix: "温度",
      dataType: "FLOAT",
      readWrite: "R"
    });

    expect(store.getPoints("dev-1").map((point) => [point.pointCode, point.address])).toEqual([
      ["temp_001", "40001"],
      ["temp_002", "40003"]
    ]);
  });

  it("替换点位时清空当前设备多选状态", () => {
    const store = usePointStore();
    store.setSelectedIds("dev-1", ["p1"]);

    store.replacePoints("dev-1", [{ pointCode: "pressure", pointName: "压力" }]);

    expect(store.getPoints("dev-1")[0]).toMatchObject({ pointId: "local-pressure", pointCode: "pressure" });
    expect(store.getSelectedIds("dev-1")).toEqual([]);
  });

  it("批量编辑只修改选中点位和显式字段", () => {
    const store = usePointStore();
    store.replacePoints("dev-1", [
      point({ pointId: "p1", pointCode: "temp", unit: "℃", alarmEnabled: 0, dataType: "FLOAT" }),
      point({ pointId: "p2", pointCode: "press", unit: "MPa", alarmEnabled: 0, dataType: "FLOAT" })
    ]);
    store.setSelectedIds("dev-1", ["p1"]);

    store.applyBatch("dev-1", {
      fields: ["alarmEnabled", "unit"],
      values: { alarmEnabled: 1, unit: "K", dataType: "DOUBLE" }
    });

    expect(store.getPoints("dev-1")[0]).toMatchObject({ pointId: "p1", alarmEnabled: 1, unit: "K", dataType: "FLOAT" });
    expect(store.getPoints("dev-1")[1]).toMatchObject({ pointId: "p2", alarmEnabled: 0, unit: "MPa", dataType: "FLOAT" });
  });

  it("删除选中点位时仅影响当前设备并清空选中项", () => {
    const store = usePointStore();
    store.replacePoints("dev-1", [point({ pointId: "p1", pointCode: "temp" }), point({ pointId: "p2", pointCode: "press" })]);
    store.replacePoints("dev-2", [point({ pointId: "p3", pointCode: "speed" })]);
    store.setSelectedIds("dev-1", ["p1"]);

    store.removeSelected("dev-1");

    expect(store.getPoints("dev-1").map((item) => item.pointId)).toEqual(["p2"]);
    expect(store.getSelectedIds("dev-1")).toEqual([]);
    expect(store.getPoints("dev-2").map((item) => item.pointId)).toEqual(["p3"]);
  });

  it("多选状态按设备隔离", () => {
    const store = usePointStore();

    store.setSelectedIds("dev-1", ["p1", "p2"]);
    store.setSelectedIds("dev-2", ["p3"]);

    expect(store.getSelectedIds("dev-1")).toEqual(["p1", "p2"]);
    expect(store.getSelectedIds("dev-2")).toEqual(["p3"]);
  });
});

function point(overrides: Partial<DataPoint>): DataPoint {
  return {
    pointId: "p1",
    pointCode: "point_001",
    pointName: "点位001",
    address: "40001",
    dataType: "FLOAT",
    readWrite: "R",
    alarmEnabled: 0,
    status: 1,
    ...overrides
  };
}
