import type { AlarmRow, LogRow } from "@/types/monitor";

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

export function buildAlarmAckPayload(note: string): Record<string, string> {
  const trimmed = note.trim();
  return trimmed ? { note: trimmed } : {};
}

export function exportLogRows(rows: LogRow[]): string {
  return rows.map((row) => [
    formatTime(row.timestamp || row.time),
    row.level || "INFO",
    row.logger || row.deviceName || row.deviceId || "-",
    row.message || row.content || ""
  ].join("\t")).join("\n");
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

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toISOString();
  }
  return value ? String(value) : "-";
}
