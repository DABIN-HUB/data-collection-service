import type {
  NetworkDiagnosticRequest,
  NetworkDiagnosticResult,
  NetworkDiagnosticType
} from "@/types/ops";

export type NetworkDiagnosticPayload = NetworkDiagnosticRequest;
export type { NetworkDiagnosticType };

export interface NetworkDiagnosticTypeOption {
  value: NetworkDiagnosticType;
  label: string;
  description: string;
}

export interface NetworkDiagnosticFormInput {
  type?: string;
  deviceId?: string;
  target?: string;
  port?: number | string;
  timeoutMs?: number | string;
}

export interface NetworkDeviceLike {
  id?: string;
  deviceId?: string;
  deviceName?: string;
  ipAddress?: string;
  host?: string;
  port?: number | string;
  [key: string]: unknown;
}

export interface NetworkTargetSelection {
  deviceId: string;
  target: string;
  port?: number;
}

export interface NormalizedNetworkDiagnosticResult {
  type: NetworkDiagnosticType | string;
  deviceId?: string;
  target: string;
  resolvedAddress: string;
  port?: number;
  reachable: boolean;
  durationMs?: number;
  message: string;
  reasonText: string;
  conclusionText: string;
  details: string[];
  completedAt?: number;
}

export interface NetworkResultRow {
  label: string;
  value: string;
}

export const NETWORK_DIAGNOSTIC_TYPES: NetworkDiagnosticTypeOption[] = [
  { value: "PING", label: "PING 可达性", description: "检测目标主机是否可达" },
  { value: "TRACE", label: "TRACE 路由跟踪", description: "跟踪到目标主机的路由跳数" },
  { value: "TCP", label: "TCP 端口", description: "检测目标 TCP 端口是否可连接" }
];

export function buildNetworkDiagnosticPayload(input: NetworkDiagnosticFormInput): NetworkDiagnosticRequest {
  const type = normalizeType(input.type);
  const target = String(input.target || "").trim();
  if (!target) {
    throw new Error("请输入检测目标");
  }
  const payload: NetworkDiagnosticRequest = {
    type,
    target,
    timeoutMs: clampNumber(input.timeoutMs, 3000, 100, 10000)
  };
  const deviceId = String(input.deviceId || "").trim();
  if (deviceId) {
    payload.deviceId = deviceId;
  }
  if (type === "TCP") {
    const port = optionalNumber(input.port);
    if (port === undefined || port < 1 || port > 65535) {
      throw new Error("TCP 检测需要填写有效端口");
    }
    payload.port = Math.trunc(port);
  }
  return payload;
}

export function resolveNetworkTargetFromDevice(device: NetworkDeviceLike | null | undefined): NetworkTargetSelection {
  if (!device) {
    return { deviceId: "", target: "127.0.0.1", port: undefined };
  }
  return {
    deviceId: String(device.deviceId || device.id || ""),
    target: String(device.ipAddress || device.host || ""),
    port: optionalNumber(device.port)
  };
}

export function normalizeNetworkDiagnosticResult(input: NetworkDiagnosticResult | Partial<NetworkDiagnosticResult> | unknown): NormalizedNetworkDiagnosticResult {
  const record = unwrapData(input);
  const type = String(record.type || "PING").toUpperCase();
  const message = String(record.message || "-");
  const reachable = Boolean(record.reachable);
  return {
    type,
    deviceId: textValue(record.deviceId),
    target: String(record.target || "-"),
    resolvedAddress: String(record.resolvedAddress || "-"),
    port: optionalNumber(record.port),
    reachable,
    durationMs: optionalNumber(record.durationMs),
    message,
    reasonText: reachable ? message : localizeNetworkFailure(message),
    conclusionText: reachable ? "可达" : "不可达",
    details: arrayValue(record.details).map(String),
    completedAt: optionalNumber(record.completedAt)
  };
}

export function buildNetworkResultRows(result: NormalizedNetworkDiagnosticResult): NetworkResultRow[] {
  return [
    { label: "检测方式", value: String(result.type || "-") },
    { label: "检测目标", value: result.target || "-" },
    { label: "解析地址", value: result.resolvedAddress || "-" },
    { label: "目标端口", value: result.port === undefined ? "-" : String(result.port) },
    { label: "检测结论", value: result.conclusionText },
    { label: "失败原因", value: result.reasonText || "-" },
    { label: "处理耗时", value: result.durationMs === undefined ? "-" : `${result.durationMs} ms` },
    { label: "完成时间", value: formatTime(result.completedAt) }
  ];
}

export function appendNetworkHistory(history: NormalizedNetworkDiagnosticResult[], result: NormalizedNetworkDiagnosticResult, limit = 10): NormalizedNetworkDiagnosticResult[] {
  return [result, ...history].slice(0, Math.max(1, limit));
}

export function buildNetworkExportText(history: NormalizedNetworkDiagnosticResult[]): string {
  if (!history.length) {
    return "暂无网络检测历史";
  }
  return history.map((result, index) => {
    const rows = buildNetworkResultRows(result).map((row) => `${row.label}：${row.value}`).join("\n");
    const details = result.details.length ? `\n路由明细：\n${result.details.join("\n")}` : "";
    return `#${index + 1}\n${rows}${details}`;
  }).join("\n\n---\n\n");
}

function normalizeType(value: unknown): NetworkDiagnosticType {
  const type = String(value || "PING").toUpperCase();
  return type === "TRACE" || type === "TCP" ? type : "PING";
}

function localizeNetworkFailure(message: string): string {
  const normalized = message.toLowerCase();
  if (normalized.includes("refused")) {
    return "TCP 连接被拒绝，请检查端口、服务监听和防火墙";
  }
  if (normalized.includes("timeout") || normalized.includes("超时")) {
    return "网络检测超时，请检查链路、端口和防火墙策略";
  }
  if (message.includes("白名单") || message.includes("配置地址一致")) {
    return message;
  }
  if (message.includes("解析失败") || normalized.includes("unknownhost")) {
    return "目标地址解析失败，请检查主机名或 IP 地址";
  }
  return message || "目标不可达，请检查网络、端口和后端白名单配置";
}

function unwrapData(value: unknown): Record<string, unknown> {
  const record = asRecord(value);
  const data = asRecord(record.data);
  return Object.keys(data).length ? data : record;
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function clampNumber(value: unknown, fallback: number, min: number, max: number): number {
  const number = optionalNumber(value) ?? fallback;
  return Math.max(min, Math.min(max, Math.trunc(number)));
}

function optionalNumber(value: unknown): number | undefined {
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function textValue(value: unknown): string | undefined {
  const text = String(value || "").trim();
  return text || undefined;
}

function formatTime(value: unknown): string {
  const number = optionalNumber(value);
  if (number === undefined) {
    return "-";
  }
  return new Date(number).toLocaleString();
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
