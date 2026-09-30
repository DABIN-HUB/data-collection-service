import type { LogRow } from "./monitor";

export interface OpsLogQuery {
  level?: string;
  logger?: string;
  keyword?: string;
  limit?: number;
}

export interface OpsLogResponse {
  totalBuffered?: number;
  count?: number;
  items?: LogRow[];
  /** LEGACY_COMPAT：旧响应兼容字段，真实后端主字段为 items。 */
  logs?: LogRow[];
  /** LEGACY_COMPAT：旧响应兼容字段，真实后端主字段为 items。 */
  records?: LogRow[];
  /** LEGACY_COMPAT：旧响应兼容字段，真实后端主字段为 items。 */
  rows?: LogRow[];
  [key: string]: unknown;
}

export interface AlarmAcknowledgement {
  alarmId?: string;
  operator?: string;
  acknowledgedAt?: number;
  note?: string;
  idempotencyKey?: string;
}

export interface AlarmAcknowledgementRequest {
  note?: string;
  idempotencyKey: string;
}

export interface AlarmAcknowledgementQueryRequest {
  alarmIds: string[];
}

export type NetworkDiagnosticType = "PING" | "TRACE" | "TCP";

export interface NetworkDiagnosticRequest {
  type: NetworkDiagnosticType;
  deviceId?: string;
  target: string;
  port?: number;
  timeoutMs?: number;
}

export interface NetworkDiagnosticResult {
  type?: NetworkDiagnosticType | string;
  deviceId?: string;
  target?: string;
  resolvedAddress?: string;
  port?: number;
  reachable?: boolean;
  durationMs?: number;
  message?: string;
  details?: string[];
  completedAt?: number;
  [key: string]: unknown;
}
