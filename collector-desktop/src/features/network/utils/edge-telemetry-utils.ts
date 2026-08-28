export type EdgeTelemetryValueType = "string" | "number" | "boolean" | "json";

export interface EdgeProtocolOption {
  value: "PROFINET" | "ETHERCAT" | "GENERIC_EDGE";
  label: string;
}

export interface EdgeTelemetryQuickForm {
  gatewayId: string;
  protocol: EdgeProtocolOption["value"];
  configVersion: string;
  deviceId: string;
  pointRef: string;
  valueText: string;
  valueType: EdgeTelemetryValueType;
  quality?: number;
  timestamp?: number;
  sequence: number;
}

export interface EdgeTelemetryResultView {
  gatewayId: string;
  message: string;
  acceptedCount: number;
  duplicateCount: number;
  rejectedCount: number;
  errors: string[];
}

export const EDGE_PROTOCOL_OPTIONS: EdgeProtocolOption[] = [
  { value: "PROFINET", label: "PROFINET 边缘进程" },
  { value: "ETHERCAT", label: "EtherCAT 边缘进程" },
  { value: "GENERIC_EDGE", label: "通用边缘接入" }
];

export function buildEdgeTelemetryPayload(form: EdgeTelemetryQuickForm): Record<string, unknown> {
  return {
    gatewayId: form.gatewayId.trim(),
    protocol: form.protocol,
    configVersion: form.configVersion.trim(),
    items: [{
      deviceId: form.deviceId.trim(),
      pointRef: form.pointRef.trim(),
      value: parseTypedValue(form.valueText, form.valueType),
      quality: normalizeOptionalInteger(form.quality),
      timestamp: normalizeOptionalInteger(form.timestamp),
      sequence: Math.max(1, Math.floor(Number(form.sequence) || 1))
    }]
  };
}

export function parseEdgeTelemetryJson(text: string): Record<string, unknown> {
  const trimmed = text.trim();
  if (!trimmed) {
    throw new Error("边缘遥测 JSON 不能为空");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed) as unknown;
  } catch (error) {
    const message = error instanceof Error ? error.message : "JSON 解析失败";
    throw new Error(`边缘遥测 JSON 格式错误：${message}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("边缘遥测 JSON 必须是对象");
  }
  return parsed as Record<string, unknown>;
}

export function normalizeEdgeTelemetryResult(response: unknown): EdgeTelemetryResultView {
  const record = asRecord(response);
  const data = asRecord(record.data);
  const source = Object.keys(data).length ? data : record;
  return {
    gatewayId: String(source.gatewayId || ""),
    message: String(record.msg || record.message || source.message || ""),
    acceptedCount: toNumber(source.acceptedCount),
    duplicateCount: toNumber(source.duplicateCount),
    rejectedCount: toNumber(source.rejectedCount),
    errors: Array.isArray(source.errors) ? source.errors.map((item) => String(item)) : []
  };
}

function parseTypedValue(value: string, type: EdgeTelemetryValueType): unknown {
  if (type === "number") {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      throw new Error("数值类型的遥测值必须是有效数字");
    }
    return parsed;
  }
  if (type === "boolean") {
    return ["true", "1", "是", "yes", "on"].includes(value.trim().toLowerCase());
  }
  if (type === "json") {
    return JSON.parse(value) as unknown;
  }
  return value;
}

function normalizeOptionalInteger(value: unknown): number | undefined {
  if (value === undefined || value === null || value === "") {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.floor(parsed) : undefined;
}

function toNumber(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
