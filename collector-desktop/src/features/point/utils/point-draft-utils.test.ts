import { describe, expect, it } from "vitest";

import {
  alarmRules,
  buildReadonlyItems,
  createUniqueCode,
  findDuplicatePointCode,
  parseBooleanOption,
  parseFieldValue,
  parsePointsJson,
  serializeAlarmRules,
  statusLabel,
  toNumber
} from "@/features/point/utils/point-draft-utils";
import type { DataPoint } from "@/types/point";

describe("point-draft-utils", () => {
  it("解析和序列化 alarmRule JSON 字符串", () => {
    const point: DataPoint = {
      alarmRule: JSON.stringify([{ ruleId: "r1", operator: ">=", threshold: 80, enabled: true }, { ruleId: "" }])
    };

    expect(alarmRules(point)).toEqual([{ ruleId: "r1", operator: ">=", threshold: 80, enabled: true }, { ruleId: "" }]);
    expect(serializeAlarmRules(alarmRules(point))).toBe(JSON.stringify([{ ruleId: "r1", operator: ">=", threshold: 80, enabled: true }]));
    expect(serializeAlarmRules([{ ruleId: "", operator: "", threshold: undefined }])).toBe("");
  });

  it("检测重复 pointCode 并生成唯一副本编码", () => {
    const source: DataPoint[] = [{ pointCode: "temp" }, { pointCode: "humidity" }, { pointCode: "temp" }];

    expect(findDuplicatePointCode(source)).toBe("temp");
    expect(createUniqueCode(source, "temp_copy")).toBe("temp_copy");
    expect(createUniqueCode([...source, { pointCode: "temp_copy" }], "temp_copy")).toBe("temp_copy_1");
    expect(createUniqueCode(source, "温度 copy")).toBe("___copy");
  });

  it("构建只读信息列表", () => {
    expect(buildReadonlyItems(null)).toEqual([]);
    expect(buildReadonlyItems({
      id: 7,
      pointId: "local-temp",
      deviceId: "local-1",
      baseCollectionInterval: 1000,
      lastValue: { value: 23.5 },
      updateTime: "2026-08-31T00:00:00Z"
    })).toEqual([
      { label: "记录ID", value: "7" },
      { label: "点位ID", value: "local-temp" },
      { label: "设备ID", value: "local-1" },
      { label: "基础采集周期", value: "1000" },
      { label: "最新值", value: JSON.stringify({ value: 23.5 }) },
      { label: "更新时间", value: "2026-08-31T00:00:00Z" }
    ]);
  });

  it("解析 JSON 点位数组并保留非数组单对象兼容", () => {
    expect(parsePointsJson(JSON.stringify([{ pointCode: "p1" }, { pointCode: "p2" }]))).toHaveLength(2);
    expect(parsePointsJson(JSON.stringify({ pointCode: "p1" }))).toEqual([{ pointCode: "p1" }]);
    expect(() => parsePointsJson("[")).toThrow();
  });

  it("解析表单字段值并保留状态文案", () => {
    expect(statusLabel(0)).toBe("禁用");
    expect(statusLabel(1)).toBe("启用");
    expect(parseBooleanOption("")).toBeUndefined();
    expect(parseBooleanOption("1")).toBe(true);
    expect(parseBooleanOption("false")).toBe(false);
    expect(parseFieldValue("12.8", "integer")).toBe(12);
    expect(parseFieldValue("12.8", "number")).toBe(12.8);
    expect(parseFieldValue("bad", "number")).toBeUndefined();
    expect(parseFieldValue("true", "boolean")).toBe(true);
    expect(toNumber("4.5")).toBe(4.5);
    expect(toNumber("bad")).toBeUndefined();
  });
});
