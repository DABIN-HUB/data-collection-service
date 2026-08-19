import { request } from "./http";
import type { LogRow } from "@/types/monitor";

export interface OpsLogResponse {
  logs?: LogRow[];
  records?: LogRow[];
  rows?: LogRow[];
  items?: LogRow[];
  [key: string]: unknown;
}

export interface AlarmAcknowledgePayload {
  note?: string;
  comment?: string;
  idempotencyKey?: string;
}

export function getOpsLogs(params: Record<string, string | number | undefined> = {}): Promise<OpsLogResponse> {
  return request<OpsLogResponse>({ url: "/api/ops/logs", method: "GET", params });
}

export function queryAlarmAcknowledgements(alarmIds: string[]): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>({ url: "/api/ops/alarms/acknowledgements/query", method: "POST", data: { alarmIds } });
}

export function acknowledgeAlarm(alarmId: string, payload: AlarmAcknowledgePayload | string = {}): Promise<unknown> {
  const data = typeof payload === "string"
    ? { note: payload, idempotencyKey: `desktop-${alarmId}` }
    : { idempotencyKey: `desktop-${alarmId}`, ...payload };
  return request<unknown>({ url: `/api/ops/alarms/${encodeURIComponent(alarmId)}/acknowledge`, method: "POST", data });
}

export function diagnoseNetwork(payload: unknown): Promise<unknown> {
  return request<unknown>({ url: "/api/ops/network/diagnose", method: "POST", data: payload });
}

export function normalizeLogRows(response: OpsLogResponse): LogRow[] {
  return response.logs || response.records || response.rows || response.items || [];
}
