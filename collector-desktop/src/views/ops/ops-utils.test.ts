import { describe, expect, it } from "vitest";

import {
  buildAlarmAckPayload,
  buildDiagnosticAdvice,
  exportLogRows,
  formatNetworkResult,
  summarizeAlarms,
  summarizeReportMetrics
} from "./ops-utils";

describe("ops-utils", () => {
  it("统计告警级别与确认状态", () => {
    expect(summarizeAlarms([
      { level: "CRITICAL", acknowledged: false },
      { level: "WARNING", acknowledged: true },
      { level: "MINOR", status: "ACTIVE" }
    ])).toEqual({ total: 3, active: 2, acknowledged: 1, critical: 1, warning: 2 });
  });

  it("构造告警确认 payload", () => {
    expect(buildAlarmAckPayload("现场已确认")).toEqual({ note: "现场已确认" });
    expect(buildAlarmAckPayload("  ")).toEqual({});
  });

  it("导出日志文本", () => {
    expect(exportLogRows([{ timestamp: 1, level: "INFO", logger: "collector", message: "启动" }])).toContain("INFO");
    expect(exportLogRows([{ time: "2026-08-14", content: "完成" }])).toContain("完成");
  });

  it("归纳云上报指标摘要", () => {
    const summary = summarizeReportMetrics({ outbox: { pendingCount: 2, isolatedCount: 1 }, ackRuntime: { pendingCount: 3 }, processors: [{ name: "mqtt" }] });
    expect(summary).toMatchObject({ pending: 2, pendingAck: 3, isolated: 1, processors: 1, riskLevel: "HIGH" });
  });

  it("根据诊断结果给出建议", () => {
    expect(buildDiagnosticAdvice({ cache: { status: "ERROR" }, devices: { status: "OK" } })).toContain("缓存模块异常");
    expect(buildDiagnosticAdvice({ health: { status: "UP" } })).toContain("暂无明显异常");
  });

  it("格式化网络检测结果", () => {
    expect(formatNetworkResult({ success: true, target: "127.0.0.1", elapsedMs: 3 })).toContain("成功");
    expect(formatNetworkResult({ success: false, message: "timeout" })).toContain("失败");
  });
});
