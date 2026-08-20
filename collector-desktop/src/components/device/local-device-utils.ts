import type { DataPoint } from "@/types/point";

export interface AdaptiveConfig {
  baseCollectionInterval: number;
  minCollectionInterval: number;
  maxCollectionInterval: number;
  pointChangeThreshold: number;
}

export interface CloudTargetConfig {
  enabled: boolean;
  deviceType: string;
  productKey?: string;
  deviceName?: string;
  topologyEnabled: boolean;
}

export interface LocalDeviceDraft {
  deviceId: string;
  deviceName: string;
  protocol: string;
  adaptive?: AdaptiveConfig;
  connection?: Record<string, unknown>;
  points: DataPoint[];
  cloudTarget?: CloudTargetConfig;
  overwrite?: boolean;
  startAfterSave?: boolean;
}

export interface LocalDevicePayload {
  device: Record<string, unknown>;
  connection: Record<string, unknown>;
  points: DataPoint[];
  overwrite: boolean;
  startAfterSave: boolean;
}

export interface LocalDeviceBundle {
  device?: Record<string, unknown>;
  connection?: Record<string, unknown>;
  points?: DataPoint[];
  cloudTarget?: CloudTargetConfig | Record<string, unknown>;
  [key: string]: unknown;
}

export const DEFAULT_ADAPTIVE_CONFIG: AdaptiveConfig = {
  baseCollectionInterval: 1000,
  minCollectionInterval: 500,
  maxCollectionInterval: 10000,
  pointChangeThreshold: 0.01
};

export function extractLocalDeviceBundle(response: unknown): LocalDeviceBundle | null {
  const root = cloneRecord(response);
  const data = cloneRecord(root.data);
  const candidates = [root.bundle, data.bundle, root, data];
  for (const candidate of candidates) {
    const record = cloneRecord(candidate);
    if (record.device || record.connection || Array.isArray(record.points)) {
      return record as LocalDeviceBundle;
    }
  }
  return null;
}

export function buildLocalDevicePayload(draft: LocalDeviceDraft): LocalDevicePayload {
  const adaptive = normalizeAdaptive(draft.adaptive);
  const connection = cloneRecord(draft.connection);
  const extJson = cloneRecord(connection.extJson);
  const cloudTarget = normalizeCloudTarget(draft.cloudTarget);
  const deviceId = draft.deviceId.trim();
  const protocol = draft.protocol.trim() || "MODBUS_TCP";
  const points = normalizeLocalPoints(draft.points, deviceId, protocol, adaptive);
  const host = connection.host;
  const port = connection.port;

  return {
    device: {
      id: deviceId,
      deviceId,
      deviceName: draft.deviceName.trim(),
      protocolType: protocol,
      connectionType: protocol,
      ipAddress: typeof host === "string" && host ? host : undefined,
      port: typeof port === "number" ? port : Number.isFinite(Number(port)) ? Number(port) : undefined,
      collectionInterval: adaptive.baseCollectionInterval,
      configSource: "local",
      temporaryConfig: true,
      status: "OFFLINE",
      cloudTarget
    },
    connection: {
      ...connection,
      deviceId,
      connectionType: String(connection.connectionType || protocol),
      extJson: {
        ...extJson,
        configSource: "local",
        temporaryConfig: true
      }
    },
    points,
    overwrite: Boolean(draft.overwrite),
    startAfterSave: Boolean(draft.startAfterSave)
  };
}

export function validateLocalDeviceDraft(draft: Pick<LocalDeviceDraft, "deviceId" | "deviceName" | "protocol" | "points" | "cloudTarget">): string[] {
  const errors: string[] = [];
  if (!draft.deviceId?.trim()) {
    errors.push("设备 ID 不能为空");
  }
  if (!draft.deviceName?.trim()) {
    errors.push("设备名称不能为空");
  }
  if (!draft.protocol?.trim()) {
    errors.push("协议不能为空");
  }
  const points = Array.isArray(draft.points) ? draft.points : [];
  if (points.length === 0) {
    errors.push("至少需要配置 1 个点位");
  }
  const seenCodes = new Set<string>();
  for (const point of points) {
    const code = String(point.pointCode || "").trim();
    if (!code || !String(point.pointName || "").trim() || !String(point.address || "").trim()) {
      errors.push("存在点位缺少编码、名称或地址");
      break;
    }
    if (seenCodes.has(code)) {
      errors.push(`点位编码重复：${code}`);
      break;
    }
    seenCodes.add(code);
  }
  if (draft.cloudTarget?.enabled && (!draft.cloudTarget.productKey?.trim() || !draft.cloudTarget.deviceName?.trim())) {
    errors.push("启用云上报时必须填写 productKey 和 deviceName");
  }
  return errors;
}

export function normalizeLocalPoints(points: DataPoint[], deviceId: string, protocol: string, adaptive: AdaptiveConfig = DEFAULT_ADAPTIVE_CONFIG): DataPoint[] {
  const normalizedProtocol = protocol || "MODBUS_TCP";
  return (Array.isArray(points) ? points : []).map((point, index) => {
    const pointCode = String(point.pointCode || `point_${index + 1}`).trim();
    const address = String(point.address || defaultPointAddress(normalizedProtocol)).trim();
    const additionalConfig = cloneRecord(point.additionalConfig);
    if (additionalConfig.reportEnabled === undefined) {
      additionalConfig.reportEnabled = true;
    }
    if (!additionalConfig.reportField) {
      additionalConfig.reportField = pointCode;
    }
    if (normalizedProtocol === "MQTT" && !additionalConfig.topic) {
      additionalConfig.topic = address;
    }
    if ((normalizedProtocol === "OPC_UA" || normalizedProtocol === "OPC_UA_PLC4X" || normalizedProtocol === "OPC_UA_MILO") && !additionalConfig.nodeId) {
      additionalConfig.nodeId = address;
    }
    return {
      collectionMode: defaultCollectionMode(normalizedProtocol),
      readWrite: "R",
      status: 1,
      cacheEnabled: 1,
      alarmEnabled: 0,
      ...point,
      pointId: point.pointId || `local-${pointCode}`,
      pointCode,
      pointName: String(point.pointName || pointCode).trim(),
      deviceId,
      address,
      dataType: point.dataType || defaultPointDataType(normalizedProtocol),
      baseCollectionInterval: adaptive.baseCollectionInterval,
      currentCollectionInterval: adaptive.baseCollectionInterval,
      minCollectionInterval: adaptive.minCollectionInterval,
      maxCollectionInterval: adaptive.maxCollectionInterval,
      pointChangeThreshold: adaptive.pointChangeThreshold,
      additionalConfig: {
        ...additionalConfig,
        configSource: "local",
        temporaryConfig: true
      }
    };
  });
}

export function normalizeAdaptive(value?: AdaptiveConfig): AdaptiveConfig {
  const base = value || DEFAULT_ADAPTIVE_CONFIG;
  const min = positiveNumber(base.minCollectionInterval) || DEFAULT_ADAPTIVE_CONFIG.minCollectionInterval;
  const max = positiveNumber(base.maxCollectionInterval) || DEFAULT_ADAPTIVE_CONFIG.maxCollectionInterval;
  const normalizedMin = Math.min(min, max);
  const normalizedMax = Math.max(min, max);
  const configuredBase = positiveNumber(base.baseCollectionInterval) || DEFAULT_ADAPTIVE_CONFIG.baseCollectionInterval;
  return {
    baseCollectionInterval: Math.max(normalizedMin, Math.min(configuredBase, normalizedMax)),
    minCollectionInterval: normalizedMin,
    maxCollectionInterval: normalizedMax,
    pointChangeThreshold: positiveNumber(base.pointChangeThreshold) || DEFAULT_ADAPTIVE_CONFIG.pointChangeThreshold
  };
}

function normalizeCloudTarget(value?: CloudTargetConfig): CloudTargetConfig {
  return {
    enabled: Boolean(value?.enabled),
    deviceType: value?.deviceType || "SUB_DEVICE",
    productKey: value?.productKey?.trim() || undefined,
    deviceName: value?.deviceName?.trim() || undefined,
    topologyEnabled: value?.topologyEnabled !== false
  };
}

function defaultCollectionMode(protocol: string): string {
  return protocol === "MQTT" || protocol === "WEBSOCKET" ? "SUBSCRIPTION" : "POLLING";
}

function defaultPointDataType(protocol: string): string {
  if (protocol === "MQTT" || protocol === "HTTP" || protocol === "WEBSOCKET") {
    return "STRING";
  }
  return "FLOAT";
}

function defaultPointAddress(protocol: string): string {
  if (protocol === "MQTT") {
    return "sensor/temperature";
  }
  if (protocol.startsWith("OPC_UA")) {
    return "ns=2;s=Channel1.Device1.Tag1";
  }
  if (protocol === "SIEMENS_S7") {
    return "DB1.DBW0";
  }
  return "40001";
}

function positiveNumber(value: unknown): number | null {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : null;
}

function cloneRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}
