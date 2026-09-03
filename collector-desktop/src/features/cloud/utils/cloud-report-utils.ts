import type { CloudReportMetricsResponse } from "@/types/monitor";

export interface CloudMetricRow {
  label: string;
  value: string;
}

export interface ReportSummary {
  pending: number;
  pendingAck: number;
  isolated: number;
  processors: number;
  riskLevel: "LOW" | "MEDIUM" | "HIGH";
}

export function cloudStatusText(status: unknown): string {
  const key = String(status || "").toUpperCase();
  return ({
    OK: "正常",
    UP: "正常",
    ONLINE: "正常",
    SUCCESS: "正常",
    WARN: "存在风险",
    WARNING: "存在风险",
    DEGRADED: "存在风险",
    ERROR: "异常",
    FAILED: "异常",
    DOWN: "异常",
    DISABLED: "未启用"
  } as Record<string, string>)[key] || "未知";
}

export function buildCloudEnabledText(report: CloudReportMetricsResponse | null | undefined): string {
  return asRecord(report).enabled ? "云端上报已启用" : "云端上报未启用";
}

export function summarizeReportMetrics(report: CloudReportMetricsResponse | null | undefined): ReportSummary {
  const record = asRecord(report);
  const outbox = asRecord(record.outbox);
  const ackRuntime = asRecord(record.ackRuntime);
  const pending = toNumber(outbox.pendingCount ?? outbox.pending ?? record.pendingCount);
  const pendingAck = toNumber(outbox.pendingAckCount ?? ackRuntime.pendingCount ?? record.pendingAckCount);
  const isolated = toNumber(outbox.isolatedCount ?? record.isolatedCount);
  const processors = Array.isArray(record.processors) ? record.processors.length : toNumber(record.processorCount);
  const riskLevel = isolated > 0 || pendingAck > 100 ? "HIGH" : pending > 0 || pendingAck > 0 ? "MEDIUM" : "LOW";
  return { pending, pendingAck, isolated, processors, riskLevel };
}

export function buildCloudSummaryCards(report: CloudReportMetricsResponse | null | undefined): CloudMetricRow[] {
  const record = asRecord(report);
  const outbox = asRecord(record.outbox);
  const executor = asRecord(record.executor);
  const ackRuntime = asRecord(record.ackRuntime);
  return [
    { label: "待发送", value: String(valueOf(outbox, ["pendingCount"], valueOf(executor, ["queueSize"], "-"))) },
    { label: "待 ACK", value: String(valueOf(outbox, ["pendingAckCount"], valueOf(ackRuntime, ["pendingCount"], "-"))) },
    { label: "隔离消息", value: String(valueOf(outbox, ["isolatedCount"], "-")) }
  ];
}

export function buildCloudStrategyRows(report: CloudReportMetricsResponse | null | undefined): CloudMetricRow[] {
  const record = asRecord(report);
  const configured = asRecord(record.configured);
  const batch = asRecord(record.batch);
  const ack = asRecord(record.ack);
  const outbox = asRecord(record.outbox);
  return [
    { label: "总开关", value: record.enabled ? "已启用" : "未启用" },
    { label: "上报模式", value: String(valueOf(record, ["mode"], "-")) },
    { label: "云服务商", value: String(valueOf(record, ["cloudProvider", "provider"], "-")) },
    { label: "可上报点位", value: `${valueOf(configured, ["reportablePointCount"], 0)} / ${valueOf(configured, ["pointCount"], 0)}` },
    { label: "批量聚合", value: batch.enabled ? `最多 ${valueOf(batch, ["maxPropertiesPerPack"], "-")} 属性` : "未启用" },
    { label: "ACK 提交点", value: String(valueOf(ack, ["commitOn"], "-")) },
    { label: "ACK 超时", value: valueOf(ack, ["timeoutMs"], null) === null ? "-" : `${valueOf(ack, ["timeoutMs"], "-")} ms` },
    { label: "可靠发件箱", value: outbox.enabled ? "已启用" : "未启用" }
  ];
}

export function buildCloudOperationalRows(report: CloudReportMetricsResponse | null | undefined): CloudMetricRow[] {
  const record = asRecord(report);
  const outbox = asRecord(record.outbox);
  const ack = asRecord(record.ack);
  const ackRuntime = asRecord(record.ackRuntime);
  const executor = asRecord(record.executor);
  return [
    { label: "待发送", value: String(valueOf(outbox, ["pendingCount"], valueOf(executor, ["queueSize"], "-"))) },
    { label: "待 ACK", value: String(valueOf(outbox, ["pendingAckCount"], valueOf(ackRuntime, ["pendingCount"], "-"))) },
    { label: "隔离消息", value: String(valueOf(outbox, ["isolatedCount"], "-")) },
    { label: "ACK 成功", value: String(valueOf(ackRuntime, ["successCount"], "-")) },
    { label: "ACK 失败", value: String(valueOf(ackRuntime, ["failureCount"], "-")) },
    { label: "ACK 提交点", value: String(valueOf(ack, ["commitOn"], "-")) },
    { label: "ACK 超时", value: valueOf(ack, ["timeoutMs"], null) === null ? "-" : `${valueOf(ack, ["timeoutMs"], "-")} ms` }
  ];
}

export function buildCloudRisks(report: CloudReportMetricsResponse | null | undefined): string[] {
  const risks = asRecord(report).risks;
  return Array.isArray(risks) && risks.length ? risks.map((risk) => String(risk)) : ["未发现已知上报风险"];
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function valueOf(value: unknown, keys: string[], fallback: unknown): unknown {
  const record = asRecord(value);
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) {
      return record[key];
    }
  }
  return fallback;
}

function toNumber(value: unknown): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : 0;
}
