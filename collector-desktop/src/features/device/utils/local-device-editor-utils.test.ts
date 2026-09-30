import { describe, expect, it } from "vitest";
import { computed, ref } from "vue";

import { alarmRules, parseBooleanOption, serializeAlarmRules, type AlarmRule } from "@/features/point/utils/point-draft-utils";
import localDeviceEditorSource from "../components/LocalDeviceEditor.vue?raw";
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

interface AlarmRuleRow {
  point: DataPoint;
  pointIndex: number;
  rule: AlarmRule;
  ruleIndex: number;
}

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

  it("Step03 告警规则表格、左侧统计和触发预览共用同一份响应式点位状态", () => {
    const points = ref<DataPoint[]>([
      {
        pointCode: "temp",
        pointName: "温度",
        unit: "℃",
        alarmEnabled: 1,
        alarmRule: serializeAlarmRules([
          { ruleId: "rule_1", ruleName: "高温", operator: ">=", threshold: 80, duration: 30, level: "WARNING", enabled: true }
        ])
      }
    ]);
    const selectedPointIndex = ref(0);
    const selectedAlarmRuleIndex = ref(0);
    const selectedPoint = computed<DataPoint | null>(() => points.value[selectedPointIndex.value] || null);
    const alarmRuleRows = computed<AlarmRuleRow[]>(() => points.value.flatMap((point, pointIndex) => alarmRules(point).map((rule, ruleIndex) => ({ point, pointIndex, rule, ruleIndex }))));
    const filteredAlarmRows = computed(() => alarmRuleRows.value);
    const enabledAlarmRuleCount = computed(() => alarmRuleRows.value.filter((row) => row.rule.enabled !== false).length);
    const alarmLevelCounts = computed<Record<string, number>>(() => alarmRuleRows.value.reduce<Record<string, number>>((acc, row) => {
      const level = String(row.rule.level || "UNSET");
      acc[level] = (acc[level] || 0) + 1;
      return acc;
    }, {}));
    const currentAlarmRule = computed(() => selectedPoint.value ? alarmRules(selectedPoint.value)[selectedAlarmRuleIndex.value] || null : null);
    const alarmLevels = [{ label: "信息", value: "INFO" }, { label: "警告", value: "WARNING" }, { label: "错误", value: "ERROR" }, { label: "严重", value: "CRITICAL" }];
    const alarmLevelLabel = (level: unknown) => alarmLevels.find((item) => item.value === String(level || ""))?.label || String(level || "未设置");
    const alarmThresholdText = (row: AlarmRuleRow) => {
      const unit = row.point.unit ? ` ${String(row.point.unit)}` : "";
      const threshold = row.rule.threshold;
      return threshold === undefined || threshold === null || String(threshold).trim() === "" ? "-" : `${threshold}${unit}`;
    };
    const alarmTriggerHint = computed(() => {
      if (!currentAlarmRule.value || !selectedPoint.value) {
        return "未选择规则";
      }
      const unit = selectedPoint.value.unit ? String(selectedPoint.value.unit) : "";
      return [
        `${selectedPoint.value.pointName || selectedPoint.value.pointCode || "点位"} ${selectedPoint.value.pointCode || "-"}`,
        `${currentAlarmRule.value.operator || "?"} ${currentAlarmRule.value.threshold ?? "?"}${unit}`,
        `持续 ${currentAlarmRule.value.duration ?? 0} 秒`,
        `${alarmLevelLabel(currentAlarmRule.value.level)}告警`
      ].join(" · ");
    });
    const tableRows = computed(() => filteredAlarmRows.value.map((row) => ({
      operator: row.rule.operator || "-",
      threshold: alarmThresholdText(row),
      duration: `${row.rule.duration ?? "-"} s`,
      level: alarmLevelLabel(row.rule.level),
      enabled: row.rule.enabled === false ? "禁用" : "启用"
    })));
    const updateAlarmRule = (index: number, field: string, value: unknown) => {
      const point = selectedPoint.value;
      if (!point || index < 0) {
        return;
      }
      const rules = alarmRules(point);
      while (rules.length <= index) {
        rules.push({});
      }
      if (value === undefined || value === null || value === "") {
        delete rules[index][field];
      } else {
        rules[index][field] = value;
      }
      point.alarmEnabled = rules.length ? 1 : 0;
      point.alarmRule = serializeAlarmRules(rules);
    };
    const updateSelectedAlarmEnabled = (value: boolean) => {
      if (selectedPoint.value) {
        selectedPoint.value.alarmEnabled = value ? 1 : 0;
      }
    };

    expect(tableRows.value[0]).toMatchObject({ operator: ">=", threshold: "80 ℃", duration: "30 s", level: "警告", enabled: "启用" });
    expect(enabledAlarmRuleCount.value).toBe(1);
    expect(alarmLevelCounts.value.WARNING).toBe(1);
    expect(alarmTriggerHint.value).toContain(">= 80℃");

    updateAlarmRule(0, "threshold", 92.5);
    expect(tableRows.value[0].threshold).toBe("92.5 ℃");
    expect(alarmTriggerHint.value).toContain(">= 92.5℃");

    updateAlarmRule(0, "operator", "<");
    expect(tableRows.value[0].operator).toBe("<");
    expect(alarmTriggerHint.value).toContain("< 92.5℃");

    updateAlarmRule(0, "duration", 45);
    expect(tableRows.value[0].duration).toBe("45 s");
    expect(alarmTriggerHint.value).toContain("持续 45 秒");

    updateAlarmRule(0, "enabled", parseBooleanOption("false"));
    expect(tableRows.value[0].enabled).toBe("禁用");
    expect(enabledAlarmRuleCount.value).toBe(0);

    updateAlarmRule(0, "level", "CRITICAL");
    expect(tableRows.value[0].level).toBe("严重");
    expect(alarmLevelCounts.value.WARNING || 0).toBe(0);
    expect(alarmLevelCounts.value.CRITICAL).toBe(1);
    expect(alarmTriggerHint.value).toContain("严重告警");

    updateSelectedAlarmEnabled(false);
    expect(selectedPoint.value?.alarmEnabled).toBe(0);
    updateSelectedAlarmEnabled(true);
    expect(selectedPoint.value?.alarmEnabled).toBe(1);
  });

  it("LocalDeviceEditor 告警联动没有 alarmForm 副本或告警 watch 手工同步", () => {
    const source = localDeviceEditorSource;

    expect(source).not.toMatch(/\balarmForm\b/);
    expect(source).toContain("const alarmRuleRows = computed");
    expect(source).toContain("const currentAlarmRule = computed");
    expect(source).toContain("const alarmTriggerHint = computed");
    expect(source).not.toMatch(/watch\([\s\S]{0,160}(alarmRuleRows|currentAlarmRule|alarmTriggerHint)/);
  });
});
