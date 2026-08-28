export interface ReportSummary {
  pending: number;
  pendingAck: number;
  isolated: number;
  processors: number;
  riskLevel: "LOW" | "MEDIUM" | "HIGH";
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
