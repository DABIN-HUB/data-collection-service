import { requestApiData } from "./http";
import type {
  AlarmAcknowledgement,
  AlarmAcknowledgementQueryRequest,
  AlarmAcknowledgementRequest,
  NetworkDiagnosticRequest,
  NetworkDiagnosticResult,
  OpsLogQuery,
  OpsLogResponse
} from "@/types/ops";

export function getOpsLogs(params: OpsLogQuery = {}): Promise<OpsLogResponse> {
  return requestApiData<OpsLogResponse>({
    url: "/api/ops/logs",
    method: "GET",
    params: pickOpsLogQuery(params)
  });
}

export function queryAlarmAcknowledgements(alarmIds: string[]): Promise<Record<string, AlarmAcknowledgement>> {
  const payload: AlarmAcknowledgementQueryRequest = { alarmIds };
  return requestApiData<Record<string, AlarmAcknowledgement>>({
    url: "/api/ops/alarms/acknowledgements/query",
    method: "POST",
    data: payload
  });
}

export function acknowledgeAlarm(alarmId: string, payload: AlarmAcknowledgementRequest): Promise<AlarmAcknowledgement> {
  return requestApiData<AlarmAcknowledgement>({
    url: `/api/ops/alarms/${encodeURIComponent(alarmId)}/acknowledge`,
    method: "POST",
    data: payload
  });
}

export function diagnoseNetwork(payload: NetworkDiagnosticRequest): Promise<NetworkDiagnosticResult> {
  return requestApiData<NetworkDiagnosticResult>({
    url: "/api/ops/network/diagnose",
    method: "POST",
    data: payload
  });
}

export function normalizeLogRows(response: OpsLogResponse) {
  return response.items
    // LEGACY_COMPAT：后端真实主字段为 items，其余字段仅兼容历史前端/夹层响应。
    || response.logs
    || response.records
    || response.rows
    || [];
}

function pickOpsLogQuery(params: OpsLogQuery): OpsLogQuery {
  return {
    level: params.level,
    logger: params.logger,
    keyword: params.keyword,
    limit: params.limit
  };
}
