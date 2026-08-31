import { describe, expect, it } from "vitest";

import {
  alarmRules,
  buildReadonlyItems,
  cloudPointStatus,
  cloudTargetSummary,
  createUniqueCode,
  defaultAddress,
  findDuplicatePointCode,
  normalizeInitialPoints,
  parsePointsJson,
  removeDeprecatedCloudIdentityConfig,
  sanitizePointForSave,
  serializeAlarmRules
} from "./local-device-editor-utils";
import type { DataPoint } from "@/types/point";

const adaptive = {
  baseCollectionInterval: 1500,
  minCollectionInterval: 500,
  maxCollectionInterval: 5000,
  pointChangeThreshold: 0.02
};

describe("local-device-editor-utils", () => {
  it("保留默认协议地址", () => {
    expect(defaultAddress("MQTT")).toBe("sensor/temperature");
    expect(defaultAddress("OPC_UA")).toBe("ns=2;s=Channel1.Device1.Tag1");
    expect(defaultAddress("OPC_UA_MILO")).toBe("ns=2;s=Channel1.Device1.Tag1");
    expect(defaultAddress("SIEMENS_S7")).toBe("DB1.DBW0");
    expect(defaultAddress("MODBUS_TCP")).toBe("40001");
    expect(defaultAddress("MODBUS_RTU")).toBe("40001");
  });

  it("按编辑器规则规范化默认点位并清理旧云身份字段", () => {
    const points = normalizeInitialPoints([
      {
        pointCode: "temp",
        pointName: "温度",
        address: "",
        additionalConfig: {
          reportProductKey: "oldPk",
          reportDeviceName: "oldDevice",
          productKey: "oldProduct",
          cloudBindings: [{ field: "temp" }],
          reportBindings: [{ field: "temp" }]
        }
      } as DataPoint
    ], "local-1", "MODBUS_TCP", { adaptive, pointDataTypes: ["DOUBLE", "FLOAT"] });

    expect(points[0]).toMatchObject({
      pointId: "local-temp",
      pointCode: "temp",
      pointName: "温度",
      deviceId: "local-1",
      address: "40001",
      dataType: "FLOAT",
      baseCollectionInterval: 1500,
      minCollectionInterval: 500,
      maxCollectionInterval: 5000,
      pointChangeThreshold: 0.02,
      additionalConfig: { reportEnabled: true, reportField: "temp", configSource: "local", temporaryConfig: true }
    });
    expect(points[0].additionalConfig).not.toHaveProperty("reportProductKey");
    expect(points[0].additionalConfig).not.toHaveProperty("reportDeviceName");
    expect(points[0].additionalConfig).not.toHaveProperty("productKey");
    expect(points[0].additionalConfig).not.toHaveProperty("cloudBindings");
    expect(points[0].additionalConfig).not.toHaveProperty("reportBindings");
  });

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

  it("保存前清理旧 cloud identity 字段", () => {
    const additionalConfig = {
      reportProductKey: "pk",
      reportDeviceName: "dn",
      productKey: "pk2",
      cloudBindings: ["old"],
      reportBindings: ["old"],
      reportField: "temperature"
    } as Record<string, unknown>;

    removeDeprecatedCloudIdentityConfig(additionalConfig);
    expect(additionalConfig).toEqual({ reportField: "temperature" });

    const sanitized = sanitizePointForSave({ pointCode: "p1", additionalConfig } as DataPoint);
    expect(sanitized.additionalConfig).toEqual({ reportField: "temperature" });
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

  it("保持云目标与点位上报状态摘要", () => {
    const cloudTarget = { enabled: true, deviceType: "SUB_DEVICE", productKey: "pk", deviceName: "dn", topologyEnabled: true };

    expect(cloudTargetSummary({ pointCode: "p1" }, cloudTarget)).toBe("pk / dn");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: { reportField: "temperature", reportEnabled: true } }, cloudTarget)).toBe("可上报");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: { reportField: "temperature", reportEnabled: false } }, cloudTarget)).toBe("未开启上报");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: {} }, cloudTarget)).toBe("缺少上报属性");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: {} }, { ...cloudTarget, enabled: false })).toBe("设备未上云");
  });
});
