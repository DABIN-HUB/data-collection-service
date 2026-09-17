import { describe, expect, it } from "vitest";
import { computed, ref } from "vue";

import {
  buildLocalEditorChecklist,
  buildPointModelingOverview,
  cloudPointStatus,
  cloudTargetSummary,
  countReportFields,
  defaultAddress,
  normalizeInitialPoints,
  removeDeprecatedCloudIdentityConfig,
  sanitizePointForSave,
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

  it("保持云目标与点位上报状态摘要", () => {
    const cloudTarget = { enabled: true, deviceType: "SUB_DEVICE", productKey: "pk", deviceName: "dn", topologyEnabled: true };

    expect(cloudTargetSummary({ pointCode: "p1" }, cloudTarget)).toBe("pk / dn");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: { reportField: "temperature", reportEnabled: true } }, cloudTarget)).toBe("可上报");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: { reportField: "temperature", reportEnabled: false } }, cloudTarget)).toBe("未开启上报");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: {} }, cloudTarget)).toBe("缺少上报属性");
    expect(cloudPointStatus({ pointCode: "p1", additionalConfig: {} }, { ...cloudTarget, enabled: false })).toBe("设备未上云");
  });

  it("Step02 建模概览随点位增删、地址和编码变化实时更新", () => {
    const points = ref<DataPoint[]>([
      { pointCode: "point_1", pointName: "点位 1", address: "40001", dataType: "INT", additionalConfig: { reportField: "point_1" } }
    ]);
    const overview = computed(() => buildPointModelingOverview(points.value));

    expect(overview.value.pointCount).toBe(1);
    expect(overview.value.completenessText).toBe("100%");
    expect(overview.value.duplicatePointCode).toBe("");
    expect(overview.value.missingPointAddressCount).toBe(0);

    points.value = [...points.value, { pointCode: "point_2", pointName: "点位 2", address: "40002", dataType: "FLOAT" }];
    expect(overview.value.pointCount).toBe(2);
    expect(overview.value.completenessText).toBe("100%");

    points.value[1].address = "";
    expect(overview.value.missingPointAddressCount).toBe(1);
    expect(overview.value.completenessText).toBe("50%");

    points.value[1].pointCode = "point_1";
    expect(overview.value.duplicatePointCode).toBe("point_1");

    points.value[1].pointCode = "point_2";
    points.value[1].address = "40002";
    expect(overview.value.duplicatePointCode).toBe("");
    expect(overview.value.missingPointAddressCount).toBe(0);
    expect(overview.value.completenessText).toBe("100%");

    points.value = points.value.slice(0, 1);
    expect(overview.value.pointCount).toBe(1);
  });

  it("Step01 配置状态随设备基础信息、连接错误、点位和上报字段变化实时更新", () => {
    const deviceId = ref("");
    const deviceName = ref("");
    const connectionErrors = ref<string[]>([]);
    const points = ref<DataPoint[]>([
      { pointCode: "point_1", pointName: "点位 1", address: "40001", additionalConfig: {} }
    ]);
    const checklist = computed(() => buildLocalEditorChecklist({
      deviceId: deviceId.value,
      deviceName: deviceName.value,
      connectionErrors: connectionErrors.value,
      points: points.value,
      totalReportFieldCount: countReportFields(points.value),
      cloudTarget: { enabled: false, deviceType: "SUB_DEVICE", topologyEnabled: true }
    }));
    const stateOf = (label: string) => checklist.value.find((item) => item.label.includes(label))?.state;

    expect(stateOf("设备 ID 待填写")).toBe("error");
    expect(stateOf("设备名称待填写")).toBe("error");

    deviceId.value = "local-1";
    deviceName.value = "本地设备";
    expect(stateOf("设备 ID 已填写")).toBe("ok");
    expect(stateOf("设备名称已填写")).toBe("ok");

    connectionErrors.value = ["host 必填"];
    expect(stateOf("连接参数需要修正")).toBe("error");
    connectionErrors.value = [];
    expect(stateOf("连接参数格式正常")).toBe("ok");

    points.value = [];
    expect(stateOf("至少需要 1 个点位")).toBe("error");

    points.value = [{ pointCode: "point_1", pointName: "点位 1", address: "40001", additionalConfig: { reportField: "temperature" } }];
    expect(stateOf("已配置 1 个点位")).toBe("ok");
    expect(stateOf("已配置 1 个上报属性")).toBe("ok");
  });
});
