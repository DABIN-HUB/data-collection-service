import type { AlarmRow } from "@/types/monitor";

export interface AlarmHistoryQueryInput {
  level?: string;
  pointId?: string;
  pointCode?: string;
  ruleId?: string;
  keyword?: string;
  hours?: number;
  limit?: number;
}

export interface AlarmHistorySummary {
  total: number;
  active: number;
  acknowledged: number;
  critical: number;
  warning: number;
  info: number;
}

export function normalizeAlarmHistoryRows(response: unknown): AlarmRow[] {
  return extractRows(response).map(normalizeAlarmRow);
}

export function buildAlarmHistoryQuery(input: AlarmHistoryQueryInput, now = Date.now()): Record<string, string | number> {
  const query: Record<string, string | number> = {};
  addText(query, "level", input.level);
  addText(query, "pointId", input.pointId);
  addText(query, "pointCode", input.pointCode || input.keyword);
  addText(query, "ruleId", input.ruleId);
  if (input.hours && Number.isFinite(input.hours) && input.hours > 0) {
    query.startTs = now - input.hours * 60 * 60 * 1000;
    query.endTs = now;
  }
  if (input.limit && Number.isFinite(input.limit) && input.limit > 0) {
    query.limit = Math.floor(input.limit);
  }
  return query;
}

export function summarizeAlarmHistory(rows: AlarmRow[]): AlarmHistorySummary {
  return rows.reduce<AlarmHistorySummary>((summary, row) => {
    const acknowledged = Boolean(row.acknowledged || String(row.status || "").toUpperCase().includes("ACK") || String(row.status || "").includes("确认"));
    const level = String(row.level || row.alarmType || "").toUpperCase();
    summary.total += 1;
    summary.acknowledged += acknowledged ? 1 : 0;
    summary.active += acknowledged ? 0 : 1;
    if (["CRITICAL", "FATAL", "ERROR", "HIGH", "MAJOR", "严重", "重要"].includes(level)) {
      summary.critical += 1;
    } else if (["WARNING", "WARN", "MINOR", "MEDIUM", "警告", "提醒", "一般"].includes(level)) {
      summary.warning += 1;
    } else {
      summary.info += 1;
    }
    return summary;
  }, { total: 0, active: 0, acknowledged: 0, critical: 0, warning: 0, info: 0 });
}

function normalizeAlarmRow(row: Record<string, unknown>): AlarmRow {
  return {
    ...row,
    alarmId: textValue(row.alarmId ?? row.alarm_id ?? row.id),
    level: textValue(row.level ?? row.alarmLevel ?? row.alarm_level ?? row.alarmType ?? row.alarm_type),
    alarmType: textValue(row.alarmType ?? row.alarm_type ?? row.level ?? row.alarm_level),
    deviceId: textValue(row.deviceId ?? row.device_id),
    deviceName: textValue(row.deviceName ?? row.device_name ?? row.deviceId ?? row.device_id),
    pointId: textValue(row.pointId ?? row.point_id),
    pointCode: textValue(row.pointCode ?? row.point_code),
    pointName: textValue(row.pointName ?? row.point_name ?? row.pointCode ?? row.point_code),
    ruleId: textValue(row.ruleId ?? row.rule_id),
    ruleName: textValue(row.ruleName ?? row.rule_name),
    content: textValue(row.content ?? row.message ?? row.alarmContent ?? row.alarm_content ?? row.ruleName ?? row.rule_name),
    message: textValue(row.message ?? row.content ?? row.alarmContent ?? row.alarm_content),
    alarmContent: textValue(row.alarmContent ?? row.alarm_content ?? row.content ?? row.message),
    timestamp: timeValue(row.timestamp ?? row.eventTs ?? row.event_ts ?? row.ts ?? row.occurTime ?? row.occur_time),
    occurTime: timeValue(row.occurTime ?? row.occur_time ?? row.eventTs ?? row.event_ts ?? row.timestamp ?? row.ts),
    status: textValue(row.status ?? row.alarmStatus ?? row.alarm_status),
    acknowledged: Boolean(row.acknowledged ?? row.ack ?? String(row.status ?? "").toUpperCase().includes("ACK") ?? false)
  };
}

function extractRows(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) {
    return value.map(asRecord).filter((row) => Object.keys(row).length > 0);
  }
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return [];
  }
  for (const key of ["alarms", "records", "rows", "items", "data"]) {
    const nested = record[key];
    if (Array.isArray(nested)) {
      return nested.map(asRecord).filter((row) => Object.keys(row).length > 0);
    }
    const nestedRecord = asRecord(nested);
    if (Object.keys(nestedRecord).length) {
      const rows = extractRows(nestedRecord);
      if (rows.length) {
        return rows;
      }
    }
  }
  return [];
}

function addText(target: Record<string, string | number>, key: string, value: unknown) {
  const text = textValue(value).trim();
  if (text) {
    target[key] = text;
  }
}

function textValue(value: unknown): string {
  return value === undefined || value === null ? "" : String(value);
}

function timeValue(value: unknown): string | number | undefined {
  if (typeof value === "string" || typeof value === "number") {
    return value;
  }
  if (value instanceof Date) {
    return value.toISOString();
  }
  return undefined;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
