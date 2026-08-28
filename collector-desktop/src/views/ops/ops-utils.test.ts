import { describe, expect, it } from "vitest";

import {
  applyAlarmAcknowledgement,
  buildAlarmIdentity,
  buildAlarmAckPayload,
  buildAlarmTroubleshootTarget,
  buildDiagnosticAdvice,
  describeAlarmAcknowledgement,
  formatNetworkResult,
  mergeAlarmAcknowledgementStates,
  normalizeAlarmAcknowledgementMap,
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
    expect(buildAlarmAckPayload("现场已确认", "alarm-1")).toEqual({ note: "现场已确认", idempotencyKey: "desktop-alarm-1" });
    expect(buildAlarmAckPayload("  ", "alarm-1")).toEqual({ note: "", idempotencyKey: "desktop-alarm-1" });
    expect(buildAlarmAckPayload("x".repeat(510), "alarm-" + "a".repeat(200))).toMatchObject({
      note: "x".repeat(500),
      idempotencyKey: expect.stringMatching(/^desktop-alarm-a+/)
    });
  });

  it("为历史告警生成稳定确认标识", () => {
    const alarm = { deviceId: "dev-1", pointCode: "temperature", ruleId: "high", timestamp: 123456 };
    expect(buildAlarmIdentity(alarm)).toMatch(/^alarm-[0-9a-f]{16}$/);
    expect(buildAlarmIdentity(alarm)).toBe(buildAlarmIdentity({ ...alarm }));
    expect(buildAlarmIdentity({ alarmId: "backend-id" })).toBe("backend-id");
  });

  it("把确认状态合并回告警行", () => {
    const alarm = { deviceId: "dev-1", pointCode: "temperature", ruleId: "high", timestamp: 123456 };
    const alarmId = buildAlarmIdentity(alarm);

    expect(mergeAlarmAcknowledgementStates([alarm], { [alarmId]: { note: "已处理" } })[0]).toMatchObject({
      alarmId,
      acknowledged: true,
      status: "已确认",
      acknowledgement: { note: "已处理" }
    });
  });

  it("归一化确认状态响应并生成确认状态描述", () => {
    const map = normalizeAlarmAcknowledgementMap({ data: { "alarm-1": { alarmId: "alarm-1", operator: "token:ops", acknowledgedAt: 1700000000000, note: "现场已处理", idempotencyKey: "desktop-alarm-1" } } });
    expect(map["alarm-1"]).toMatchObject({ operator: "token:ops", note: "现场已处理" });
    expect(describeAlarmAcknowledgement(map["alarm-1"])).toContain("现场已处理");
    expect(describeAlarmAcknowledgement(undefined)).toBe("待确认");
  });

  it("把单条确认结果回写到当前告警行", () => {
    const alarm = { alarmId: "alarm-1", status: "未确认" };
    expect(applyAlarmAcknowledgement([alarm], "alarm-1", { note: "复位完成", operator: "本机控制台" })[0]).toMatchObject({
      acknowledged: true,
      status: "已确认",
      acknowledgement: { note: "复位完成", operator: "本机控制台" }
    });
  });

  it("从告警构造日志和网络排障目标", () => {
    const target = buildAlarmTroubleshootTarget({ deviceId: "dev-1", pointCode: "temp", alarmContent: "温度过高" }, { ipAddress: "10.0.0.8", port: 502 });
    expect(target).toEqual({
      deviceId: "dev-1",
      logKeyword: "dev-1 temp 温度过高",
      networkTarget: "10.0.0.8",
      networkPort: 502
    });
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
