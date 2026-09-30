import { DEFAULT_ADAPTIVE_CONFIG, normalizeLocalPoints, type AdaptiveConfig, type CloudTargetConfig } from "./local-device-utils";
import type { DataPoint } from "@/types/point";

export type LocalEditorChecklistState = "ok" | "warn" | "error";

export interface LocalDeviceEditorPointOptions {
  adaptive?: AdaptiveConfig;
  pointDataTypes?: string[];
}

export interface PointModelingOverview {
  pointCount: number;
  completenessText: string;
  duplicatePointCode: string;
  missingPointAddressCount: number;
}

export interface LocalEditorChecklistInput {
  deviceId: string;
  deviceName: string;
  connectionErrors: string[];
  points: DataPoint[];
  totalReportFieldCount: number;
  cloudTarget: CloudTargetConfig;
}

export interface LocalEditorChecklistItem {
  label: string;
  state: LocalEditorChecklistState;
}

export function buildPointModelingOverview(points: DataPoint[]): PointModelingOverview {
  const complete = points.filter((point) => hasValue(point.pointCode) && hasValue(point.pointName) && hasValue(point.address)).length;
  return {
    pointCount: points.length,
    completenessText: points.length ? `${Math.round((complete / points.length) * 100)}%` : "0%",
    duplicatePointCode: findDuplicatePointCode(points),
    missingPointAddressCount: points.filter((point) => !hasValue(point.address)).length
  };
}

export function buildLocalEditorChecklist(input: LocalEditorChecklistInput): LocalEditorChecklistItem[] {
  const missingPoint = input.points.find((point) => !hasValue(point.pointCode) || !hasValue(point.pointName) || !hasValue(point.address));
  const duplicatePointCode = findDuplicatePointCode(input.points);
  const checks: LocalEditorChecklistItem[] = [
    { label: input.deviceId.trim() ? "设备 ID 已填写" : "设备 ID 待填写", state: input.deviceId.trim() ? "ok" : "error" },
    { label: input.deviceName.trim() ? "设备名称已填写" : "设备名称待填写", state: input.deviceName.trim() ? "ok" : "error" },
    { label: input.connectionErrors.length === 0 ? "连接参数格式正常" : "连接参数需要修正", state: input.connectionErrors.length === 0 ? "ok" : "error" },
    { label: input.points.length > 0 ? `已配置 ${input.points.length} 个点位` : "至少需要 1 个点位", state: input.points.length > 0 ? "ok" : "error" },
    { label: duplicatePointCode ? `点位编码重复：${duplicatePointCode}` : "点位编码未重复", state: duplicatePointCode ? "error" : "ok" },
    { label: missingPoint ? "存在点位缺少编码、名称或地址" : "点位必填项完整", state: missingPoint ? "error" : "ok" },
    { label: input.totalReportFieldCount ? `已配置 ${input.totalReportFieldCount} 个上报属性` : "建议配置上报属性", state: input.totalReportFieldCount ? "ok" : "warn" }
  ];
  if (input.cloudTarget.enabled) {
    checks.push({
      label: input.cloudTarget.productKey && input.cloudTarget.deviceName ? "云设备身份已填写" : "云设备身份待填写",
      state: input.cloudTarget.productKey && input.cloudTarget.deviceName ? "ok" : "error"
    });
  }
  return checks;
}

export function countReportFields(points: DataPoint[]): number {
  return points.filter((point) => hasValue(point.additionalConfig?.reportField)).length;
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

function findDuplicatePointCode(points: DataPoint[]): string {
  const seen = new Set<string>();
  for (const point of points) {
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
