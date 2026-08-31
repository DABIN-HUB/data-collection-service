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
