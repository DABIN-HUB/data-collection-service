import { DEFAULT_ADAPTIVE_CONFIG, normalizeLocalPoints, type AdaptiveConfig, type CloudTargetConfig } from "./local-device-utils";
import type { DataPoint } from "@/types/point";

export type FieldValueType = "string" | "number" | "integer" | "boolean";

export interface LocalDeviceEditorPointOptions {
  adaptive?: AdaptiveConfig;
  pointDataTypes?: string[];
}

export interface AlarmRule {
  ruleId?: string;
  ruleName?: string;
  operator?: string;
  threshold?: number;
  duration?: number;
  level?: string;
  enabled?: boolean;
  description?: string;
  [key: string]: unknown;
}

export interface ReadonlyItem {
  label: string;
  value: string;
}

export function normalizeInitialPoints(rawPoints: DataPoint[], currentDeviceId: string, currentProtocol: string, options: LocalDeviceEditorPointOptions = {}): DataPoint[] {
  const adaptive = options.adaptive || DEFAULT_ADAPTIVE_CONFIG;
  const pointDataTypes = options.pointDataTypes || [];
  const normalized = normalizeLocalPoints(rawPoints, currentDeviceId, currentProtocol, { ...adaptive });
  return normalized.map((point, index) => {
    const pointCode = point.pointCode || `point_${index + 1}`;
    const additionalConfig = { reportEnabled: true, reportField: pointCode, ...(point.additionalConfig || {}) };
    removeDeprecatedCloudIdentityConfig(additionalConfig);
    return {
      ...point,
      pointId: point.pointId || `local-${pointCode}`,
      pointCode,
      pointName: point.pointName || `点位 ${index + 1}`,
      address: point.address || defaultAddress(currentProtocol),
      dataType: point.dataType || pointDataTypes[0] || "FLOAT",
      additionalConfig
    };
  });
}

export function defaultPointTemplate(currentDeviceId: string, currentProtocol: string, overrides: Partial<DataPoint> = {}, options: LocalDeviceEditorPointOptions = {}): DataPoint {
  const adaptive = options.adaptive || DEFAULT_ADAPTIVE_CONFIG;
  const pointDataTypes = options.pointDataTypes || [];
  const pointCode = overrides.pointCode || "temperature";
  return normalizeInitialPoints([{
    pointCode,
    pointName: overrides.pointName || "温度",
    deviceId: currentDeviceId,
    address: overrides.address || defaultAddress(currentProtocol),
    dataType: overrides.dataType || pointDataTypes[0] || "FLOAT",
    readWrite: "R",
    status: 1,
    cacheEnabled: 1,
    alarmEnabled: 0,
    baseCollectionInterval: adaptive.baseCollectionInterval,
    currentCollectionInterval: adaptive.baseCollectionInterval,
    minCollectionInterval: adaptive.minCollectionInterval,
    maxCollectionInterval: adaptive.maxCollectionInterval,
    pointChangeThreshold: adaptive.pointChangeThreshold,
    additionalConfig: {
      reportEnabled: true,
      reportField: pointCode,
      writeAddress: "C_SE_NC_1:1",
      writeCommonAddress: 1,
      writeSelect: false,
      writeQl: 0
    },
    ...overrides
  }], currentDeviceId, currentProtocol, options)[0];
}

export function defaultAddress(currentProtocol: string): string {
  if (currentProtocol === "MQTT") {
    return "sensor/temperature";
  }
  if (isOpcUaProtocol(currentProtocol)) {
    return "ns=2;s=Channel1.Device1.Tag1";
  }
  if (currentProtocol === "SIEMENS_S7") {
    return "DB1.DBW0";
  }
  return "40001";
}

export function normalizeCloudTarget(value: unknown): Partial<CloudTargetConfig> {
  if (!isPlainObject(value)) {
    return {};
  }
  return {
    enabled: Boolean(value.enabled),
    deviceType: String(value.deviceType || "SUB_DEVICE"),
    productKey: value.productKey ? String(value.productKey) : "",
    deviceName: value.deviceName ? String(value.deviceName) : "",
    topologyEnabled: value.topologyEnabled !== false
  };
}

export function alarmRules(point: DataPoint | null): AlarmRule[] {
  const raw = point?.alarmRule;
  if (!raw) {
    return [];
  }
  if (Array.isArray(raw)) {
    return raw.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[];
  }
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[] : [];
    } catch {
      return [];
    }
  }
  return [];
}

export function serializeAlarmRules(rules: AlarmRule[]): string {
  const normalized = rules.map((rule) => pruneEmpty(rule)).filter((rule) => Object.keys(rule).length > 0);
  return normalized.length ? JSON.stringify(normalized) : "";
}

export function parsePointsJson(value: string): DataPoint[] {
  const parsed = JSON.parse(value || "[]") as DataPoint | DataPoint[];
  return Array.isArray(parsed) ? parsed : [parsed];
}

export function sanitizePointForSave(point: DataPoint): DataPoint {
  const clone = cloneData(point);
  const additionalConfig = isPlainObject(clone.additionalConfig) ? clone.additionalConfig : {};
  removeDeprecatedCloudIdentityConfig(additionalConfig);
  clone.additionalConfig = additionalConfig;
  return clone;
}

export function removeDeprecatedCloudIdentityConfig(additionalConfig: Record<string, unknown>) {
  const obsoleteKey = ["report", "Bindings"].join("");
  delete additionalConfig[obsoleteKey];
  delete additionalConfig.reportDeviceName;
  delete additionalConfig.reportProductKey;
  delete additionalConfig.productKey;
  delete additionalConfig.cloudBindings;
}

export function cloudTargetSummary(_point: DataPoint, cloudTarget: CloudTargetConfig): string {
  if (!cloudTarget.enabled) {
    return "未启用";
  }
  return [cloudTarget.productKey, cloudTarget.deviceName].filter(hasValue).join(" / ") || "云身份不完整";
}

export function cloudPointStatus(point: DataPoint, cloudTarget: CloudTargetConfig): string {
  if (!cloudTarget.enabled) {
    return "设备未上云";
  }
  if (!cloudTarget.productKey || !cloudTarget.deviceName) {
    return "云身份不完整";
  }
  if (!hasValue(point.additionalConfig?.reportField)) {
    return "缺少上报属性";
  }
  if (point.additionalConfig?.reportEnabled !== true) {
    return "未开启上报";
  }
  return "可上报";
}

export function statusLabel(value: unknown): string {
  return Number(value ?? 1) === 0 ? "禁用" : "启用";
}

export function parseBooleanOption(value: unknown): boolean | undefined {
  if (value === "") {
    return undefined;
  }
  return value === true || value === "true" || value === "1" || value === 1;
}

export function parseFieldValue(value: unknown, valueType: FieldValueType | undefined): unknown {
  if (value === "") {
    return undefined;
  }
  if (valueType === "boolean") {
    return value === true || value === "true" || value === "1" || value === 1;
  }
  if (valueType === "number" || valueType === "integer") {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      return undefined;
    }
    return valueType === "integer" ? Math.trunc(numberValue) : numberValue;
  }
  return value;
}

export function toNumber(value: unknown): number | undefined {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : undefined;
}

export function findDuplicatePointCode(source: DataPoint[]): string {
  const seen = new Set<string>();
  for (const point of source) {
    const code = String(point.pointCode || "").trim();
    if (!code) {
      continue;
    }
    if (seen.has(code)) {
      return code;
    }
    seen.add(code);
  }
  return "";
}

export function createUniqueCode(source: DataPoint[], base: string): string {
  const used = new Set(source.map((point) => String(point.pointCode || "").trim()).filter(Boolean));
  const normalizedBase = base.replace(/[^a-zA-Z0-9_]/g, "_") || "point";
  let candidate = normalizedBase;
  let index = 1;
  while (used.has(candidate)) {
    candidate = `${normalizedBase}_${index}`;
    index += 1;
  }
  return candidate;
}

export function buildReadonlyItems(point: DataPoint | null): ReadonlyItem[] {
  if (!point) {
    return [];
  }
  return [
    ["记录ID", point.id],
    ["点位ID", point.pointId],
    ["设备ID", point.deviceId],
    ["设备名称", point.deviceName],
    ["基础采集周期", point.baseCollectionInterval],
    ["当前采集周期", point.currentCollectionInterval],
    ["最小采集周期", point.minCollectionInterval],
    ["最大采集周期", point.maxCollectionInterval],
    ["点位变化阈值", point.pointChangeThreshold],
    ["稳定次数", point.stableCount],
    ["最新值", point.lastValue],
    ["变化率", point.changeRate],
    ["最近调整时间", point.lastAdjustTime],
    ["上报属性冲突", point.reportFieldConflict],
    ["创建时间", point.createTime],
    ["更新时间", point.updateTime]
  ].filter(([, value]) => hasValue(value)).map(([label, value]) => ({ label: String(label), value: typeof value === "object" ? JSON.stringify(value) : String(value) }));
}

export function firstPointValue(source: DataPoint[] | undefined, key: string): unknown {
  return Array.isArray(source) && source.length ? source[0]?.[key] : undefined;
}

export function isOpcUaProtocol(value: string): boolean {
  return value === "OPC_UA" || value === "OPC_UA_PLC4X" || value === "OPC_UA_MILO" || value.startsWith("OPC_UA");
}

export function hasValue(value: unknown): boolean {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function cloneData<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function pruneEmpty(rule: AlarmRule): AlarmRule {
  const next: AlarmRule = {};
  for (const [key, value] of Object.entries(rule)) {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      next[key] = value;
    }
  }
  return next;
}
