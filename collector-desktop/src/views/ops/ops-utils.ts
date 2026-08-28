import type { AlarmRow } from "@/types/monitor";

export interface AlarmSummary {
  total: number;
  active: number;
  acknowledged: number;
  critical: number;
  warning: number;
}

export interface ReportSummary {
  pending: number;
  pendingAck: number;
  isolated: number;
  processors: number;
  riskLevel: "LOW" | "MEDIUM" | "HIGH";
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

export function summarizeReportMetrics(report: Record<string, unknown>): ReportSummary {
  const outbox = readRecord(report.outbox);
  const ackRuntime = readRecord(report.ackRuntime);
  const pending = toNumber(outbox.pendingCount ?? outbox.pending ?? report.pendingCount);
  const pendingAck = toNumber(ackRuntime.pendingCount ?? report.pendingAckCount);
  const isolated = toNumber(outbox.isolatedCount ?? report.isolatedCount);
  const processors = Array.isArray(report.processors) ? report.processors.length : toNumber(report.processorCount);
  const riskLevel = isolated > 0 || pendingAck > 100 ? "HIGH" : pending > 0 || pendingAck > 0 ? "MEDIUM" : "LOW";
  return { pending, pendingAck, isolated, processors, riskLevel };
}

export function buildDiagnosticAdvice(diagnostic: Record<string, unknown>): string[] {
  const advice: string[] = [];
  for (const [key, value] of Object.entries(diagnostic)) {
    const status = String(readRecord(value).status || "").toUpperCase();
    if (["ERROR", "DOWN", "FAIL", "FAILED"].some((flag) => status.includes(flag))) {
      advice.push(`${diagnosticName(key)}异常`);
    }
  }
  if (advice.length === 0) {
    advice.push("暂无明显异常");
  }
  return advice;
}

export function formatNetworkResult(result: unknown): string {
  const body = readRecord(result);
  const success = Boolean(body.success ?? body.ok ?? body.reachable);
  const lines = [
    `检测结果：${success ? "成功" : "失败"}`,
    `目标：${body.target || body.host || "-"}`,
    `端口：${body.port || "-"}`,
    `耗时：${body.elapsedMs || body.durationMs || "-"} ms`,
    `消息：${body.message || body.error || "-"}`,
    "",
    JSON.stringify(result, null, 2)
  ];
  return lines.join("\n");
}

function diagnosticName(key: string): string {
  return {
    health: "健康检查",
    system: "系统资源",
    devices: "设备连接",
    cache: "缓存模块",
    performance: "性能指标",
    report: "云端上报",
    summary: "配置摘要"
  }[key] || key;
}

function readRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function toNumber(value: unknown): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : 0;
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
