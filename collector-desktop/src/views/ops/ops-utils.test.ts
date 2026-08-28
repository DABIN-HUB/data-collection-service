import { describe, expect, it } from "vitest";

import {
  buildDiagnosticAdvice,
  summarizeReportMetrics
} from "./ops-utils";

describe("ops-utils", () => {
  it("归纳云上报指标摘要", () => {
    const summary = summarizeReportMetrics({ outbox: { pendingCount: 2, isolatedCount: 1 }, ackRuntime: { pendingCount: 3 }, processors: [{ name: "mqtt" }] });
    expect(summary).toMatchObject({ pending: 2, pendingAck: 3, isolated: 1, processors: 1, riskLevel: "HIGH" });
  });

  it("根据诊断结果给出建议", () => {
    expect(buildDiagnosticAdvice({ cache: { status: "ERROR" }, devices: { status: "OK" } })).toContain("缓存模块异常");
    expect(buildDiagnosticAdvice({ health: { status: "UP" } })).toContain("暂无明显异常");
  });
});
