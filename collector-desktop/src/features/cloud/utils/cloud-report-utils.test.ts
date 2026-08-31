import { describe, expect, it } from "vitest";

import {
  buildCloudEnabledText,
  buildCloudOperationalRows,
  buildCloudRisks,
  buildCloudStrategyRows,
  buildCloudSummaryCards,
  cloudStatusText,
  summarizeReportMetrics
} from "./cloud-report-utils";

function rowValue(rows: Array<{ label: string; value: string }>, label: string): string {
  return rows.find((row) => row.label === label)?.value || "";
}

describe("cloud-report-utils", () => {
  it("归纳云上报指标摘要", () => {
    const summary = summarizeReportMetrics({ outbox: { pendingCount: 2, isolatedCount: 1 }, ackRuntime: { pendingCount: 3 }, processors: [{ name: "mqtt" }] });
    expect(summary).toMatchObject({ pending: 2, pendingAck: 3, isolated: 1, processors: 1, riskLevel: "HIGH" });
  });

  it.each([
    ["UP", "正常"],
    ["OK", "正常"],
    ["ONLINE", "正常"],
    ["SUCCESS", "正常"],
    ["WARN", "存在风险"],
    ["WARNING", "存在风险"],
    ["ERROR", "异常"],
    ["FAILED", "异常"],
    ["DOWN", "异常"],
    ["DISABLED", "未启用"],
    ["unknown", "未知"]
  ])("云上报状态 %s 映射为 %s", (status, expected) => {
    expect(cloudStatusText(status)).toBe(expected);
  });

  it("归纳启用状态", () => {
    expect(buildCloudEnabledText({ enabled: true })).toBe("云端上报已启用");
    expect(buildCloudEnabledText({ enabled: false })).toBe("云端上报未启用");
  });

  it("归纳云上报摘要卡片", () => {
    expect(buildCloudSummaryCards({ outbox: { pendingCount: 4, pendingAckCount: 7, isolatedCount: 2 } })).toEqual([
      { label: "待发送", value: "4" },
      { label: "待 ACK", value: "7" },
      { label: "隔离消息", value: "2" }
    ]);
  });

  it("归纳云上报策略行", () => {
    const rows = buildCloudStrategyRows({
      enabled: true,
      mode: "MQTT",
      cloudProvider: "阿里云",
      configured: { reportablePointCount: 8, pointCount: 12 },
      batch: { enabled: true, maxPropertiesPerPack: 32 },
      ack: { commitOn: "SENT", timeoutMs: 5000 },
      outbox: { enabled: true }
    });

    expect(rowValue(rows, "总开关")).toBe("已启用");
    expect(rowValue(rows, "上报模式")).toBe("MQTT");
    expect(rowValue(rows, "云服务商")).toBe("阿里云");
    expect(rowValue(rows, "可上报点位")).toBe("8 / 12");
    expect(rowValue(rows, "批量聚合")).toBe("最多 32 属性");
    expect(rowValue(rows, "ACK 提交点")).toBe("SENT");
    expect(rowValue(rows, "ACK 超时")).toBe("5000 ms");
    expect(rowValue(rows, "可靠发件箱")).toBe("已启用");
  });

  it("归纳 Outbox 与 ACK 运行明细", () => {
    const rows = buildCloudOperationalRows({
      outbox: { pendingCount: 4, pendingAckCount: 5, isolatedCount: 2 },
      ackRuntime: { pendingCount: 5, successCount: 9, failureCount: 1 },
      ack: { commitOn: "ACK", timeoutMs: 6000 }
    });

    expect(rowValue(rows, "待发送")).toBe("4");
    expect(rowValue(rows, "待 ACK")).toBe("5");
    expect(rowValue(rows, "隔离消息")).toBe("2");
    expect(rowValue(rows, "ACK 成功")).toBe("9");
    expect(rowValue(rows, "ACK 失败")).toBe("1");
    expect(rowValue(rows, "ACK 提交点")).toBe("ACK");
    expect(rowValue(rows, "ACK 超时")).toBe("6000 ms");
  });

  it("归纳上报风险", () => {
    expect(buildCloudRisks({ risks: ["通道拥塞", "ACK 超时"] })).toEqual(["通道拥塞", "ACK 超时"]);
    expect(buildCloudRisks({ risks: [] })).toEqual(["未发现已知上报风险"]);
    expect(buildCloudRisks({})).toEqual(["未发现已知上报风险"]);
  });
});
