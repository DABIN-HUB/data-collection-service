import type { AlarmRow } from "@/types/monitor";

export interface AlarmSummary {
  total: number;
  active: number;
  acknowledged: number;
  critical: number;
  warning: number;
}

export interface AlarmAcknowledgementRecord {
  alarmId?: string;
  operator?: string;
  acknowledgedAt?: number | string;
  note?: string;
  idempotencyKey?: string;
  [key: string]: unknown;
}

export interface AlarmTroubleshootTarget {
  deviceId: string;
  logKeyword: string;
  networkTarget?: string;
  networkPort?: number;
}

export function summarizeAlarms(rows: AlarmRow[]): AlarmSummary {
  return rows.reduce<AlarmSummary>((summary, row) => {
    const acknowledged = Boolean(row.acknowledged || String(row.status || "").toUpperCase().includes("ACK"));
    const level = String(row.level || row.alarmType || "").toUpperCase();
    summary.total += 1;
    summary.acknowledged += acknowledged ? 1 : 0;
    summary.active += acknowledged ? 0 : 1;
    summary.critical += ["CRITICAL", "MAJOR", "严重", "重要"].includes(level) ? 1 : 0;
    summary.warning += ["WARNING", "MINOR", "WARN", "提醒", "一般"].includes(level) ? 1 : 0;
    return summary;
  }, { total: 0, active: 0, acknowledged: 0, critical: 0, warning: 0 });
}

export function buildAlarmAckPayload(note: string, alarmId = "alarm-ack"): Record<string, string> {
  const trimmed = note.trim().slice(0, 500);
  return {
    note: trimmed,
    idempotencyKey: buildAlarmIdempotencyKey(alarmId)
  };
}

export function buildAlarmIdentity(alarm: Record<string, unknown>): string {
  const existing = alarm.alarmId || alarm.id;
  if (existing !== undefined && existing !== null && String(existing).trim()) {
    return String(existing).trim();
  }
  const source = [
    alarm.deviceId || alarm.device_id,
    alarm.pointId || alarm.point_id || alarm.pointCode || alarm.point_code,
    alarm.ruleId || alarm.rule_id || alarm.ruleName,
    alarm.eventTs || alarm.event_ts || alarm.timestamp || alarm.ts || alarm.occurTime
  ].map((value) => String(value || "-")).join("|");
  return `alarm-${fnvHash(source, 2166136261)}${fnvHash(source, 2246822519)}`;
}

export function mergeAlarmAcknowledgementStates(rows: AlarmRow[], acknowledgements: Record<string, unknown>): AlarmRow[] {
  return rows.map((row) => {
    const alarmId = buildAlarmIdentity(row);
    const acknowledgement = normalizeAcknowledgementRecord(acknowledgements[alarmId] || row.acknowledgement);
    const acknowledged = Boolean(row.acknowledged || acknowledgement || String(row.status || "").toUpperCase().includes("ACK") || String(row.status || "").includes("确认"));
    return {
      ...row,
      alarmId,
      acknowledged,
      acknowledgement,
      acknowledgementText: describeAlarmAcknowledgement(acknowledgement),
      status: acknowledged ? "已确认" : (row.status || "未确认")
    };
  });
}

export function normalizeAlarmAcknowledgementMap(value: unknown): Record<string, AlarmAcknowledgementRecord> {
  const record = readRecord(value);
  const data = readRecord(record.data);
  const source = Object.keys(data).length ? data : record;
  return Object.fromEntries(Object.entries(source)
    .filter(([key]) => !["status", "message", "code", "success"].includes(key))
    .map(([key, acknowledgement]) => [key, normalizeAcknowledgementRecord(acknowledgement) || { alarmId: key }]));
}

export function describeAlarmAcknowledgement(acknowledgement: unknown): string {
  const record = normalizeAcknowledgementRecord(acknowledgement);
  if (!record) {
    return "待确认";
  }
  const parts = [
    record.operator ? `操作人：${record.operator}` : "",
    record.acknowledgedAt ? `时间：${formatTime(record.acknowledgedAt)}` : "",
    record.note ? `说明：${record.note}` : ""
  ].filter(Boolean);
  return parts.length ? parts.join("；") : "已确认";
}

export function applyAlarmAcknowledgement(rows: AlarmRow[], alarmId: string, acknowledgement: unknown): AlarmRow[] {
  const record = normalizeAcknowledgementRecord(acknowledgement) || { alarmId };
  return rows.map((row) => {
    const currentAlarmId = buildAlarmIdentity(row);
    if (currentAlarmId !== alarmId) {
      return row;
    }
    return {
      ...row,
      alarmId,
      acknowledged: true,
      acknowledgement: record,
      acknowledgementText: describeAlarmAcknowledgement(record),
      status: "已确认"
    };
  });
}

export function buildAlarmTroubleshootTarget(alarm: Record<string, unknown>, device: Record<string, unknown> = {}): AlarmTroubleshootTarget {
  const deviceId = textOf(alarm.deviceId ?? alarm.device_id ?? device.deviceId ?? device.id);
  const point = textOf(alarm.pointCode ?? alarm.point_code ?? alarm.pointId ?? alarm.point_id ?? alarm.pointName);
  const content = textOf(alarm.alarmContent ?? alarm.content ?? alarm.message ?? alarm.ruleName ?? alarm.ruleId ?? alarm.rule_id);
  return {
    deviceId,
    logKeyword: [deviceId, point, content].filter(Boolean).join(" "),
    networkTarget: textOf(device.ipAddress ?? device.host ?? alarm.ipAddress ?? alarm.host) || undefined,
    networkPort: optionalNumber(device.port ?? alarm.port)
  };
}

export function alarmCurrentValue(alarm: AlarmRow): string {
  const value = valueOf(alarm, ["currentValue", "current_value", "value", "alarmValue", "alarm_value", "rawValue", "raw_value"], "-");
  return value === undefined || value === null || value === "" ? "-" : String(value);
}

export function alarmLevelText(level: unknown): string {
  switch (String(level || "").toUpperCase()) {
    case "CRITICAL":
    case "FATAL":
    case "HIGH":
      return "严重";
    case "ERROR":
      return "错误";
    case "WARN":
    case "WARNING":
    case "MEDIUM":
      return "警告";
    case "INFO":
      return "信息";
    default:
      return String(level || "未知");
  }
}

function optionalNumber(value: unknown): number | undefined {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : undefined;
}

function normalizeAcknowledgementRecord(value: unknown): AlarmAcknowledgementRecord | undefined {
  if (value === true) {
    return {};
  }
  const record = readRecord(value);
  return Object.keys(record).length ? record as AlarmAcknowledgementRecord : undefined;
}

function buildAlarmIdempotencyKey(alarmId: string): string {
  const normalized = textOf(alarmId).replace(/\s+/g, "-") || "alarm-ack";
  return `desktop-${normalized}`.slice(0, 128);
}

function valueOf(value: unknown, keys: string[], fallback: unknown): unknown {
  const record = readRecord(value);
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) {
      return record[key];
    }
  }
  return fallback;
}

function readRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function textOf(value: unknown): string {
  return String(value ?? "").trim();
}

function fnvHash(value: string, seed: number): string {
  let hash = seed >>> 0;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toISOString();
  }
  return value ? String(value) : "-";
}
