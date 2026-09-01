import { DEFAULT_ADAPTIVE_CONFIG, normalizeLocalPoints, type AdaptiveConfig, type CloudTargetConfig } from "./local-device-utils";
import type { DataPoint } from "@/types/point";

export interface LocalDeviceEditorPointOptions {
  adaptive?: AdaptiveConfig;
  pointDataTypes?: string[];
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
