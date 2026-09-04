import { describe, expect, it } from "vitest";

import { buildAckStatusWarning, buildAlarmAcknowledgementPresentation, buildAlarmSummaryDisplay } from "./alarm-partial-failure";
import type { AlarmRow } from "@/types/monitor";
import type { AlarmSummary } from "./alarm-utils";

function alarm(overrides: Partial<AlarmRow> = {}): AlarmRow {
  return {
    alarmId: "alarm-1",
    status: "ACTIVE",
    acknowledged: false,
    ...overrides
  };
}

function summary(overrides: Partial<AlarmSummary> = {}): AlarmSummary {
  return {
    total: 3,
    active: 2,
    acknowledged: 1,
    critical: 1,
    warning: 2,
    ...overrides
  };
}

describe("alarm-partial-failure", () => {
  it("ack failure 使用持久 warning 文案", () => {
    expect(buildAckStatusWarning()).toBe("确认状态暂不可用，当前显示告警历史和最后已知确认状态");
  });

  it("ack 不可用时，已知 acknowledged 仍显示已确认", () => {
    expect(buildAlarmAcknowledgementPresentation(alarm({
      acknowledged: true,
      acknowledgement: { operator: "ops", note: "已处理" }
    }), {
      ackStatusUnavailable: true,
      ackStatusInitialized: false
    })).toEqual(expect.objectContaining({
      statusText: "已确认",
      detailText: expect.stringContaining("已处理")
    }));
  });

  it("ack 不可用时，未知状态显示状态未同步", () => {
    expect(buildAlarmAcknowledgementPresentation(alarm(), {
      ackStatusUnavailable: true,
      ackStatusInitialized: false
    })).toEqual({
      statusText: "状态未同步",
      detailText: "确认信息暂未同步",
      toneClass: "is-warning"
    });
  });

  it("ack 尚未完成首次同步时，未知状态也显示状态未同步", () => {
    expect(buildAlarmAcknowledgementPresentation(alarm(), {
      ackStatusUnavailable: false,
      ackStatusInitialized: false
    })).toEqual({
      statusText: "状态未同步",
      detailText: "确认信息暂未同步",
      toneClass: "is-warning"
    });
  });

  it("ack 已同步成功后，未知状态显示待确认", () => {
    expect(buildAlarmAcknowledgementPresentation(alarm(), {
      ackStatusUnavailable: false,
      ackStatusInitialized: true
    })).toEqual({
      statusText: "待确认",
      detailText: "待确认",
      toneClass: "is-error"
    });
  });

  it("ack 不可用时，summary 只保留可靠的 total/critical/warning，ack 统计显示 -", () => {
    expect(buildAlarmSummaryDisplay(summary(), {
      ackStatusUnavailable: true,
      ackStatusInitialized: true
    })).toEqual({
      total: "3",
      active: "-",
      acknowledged: "-",
      critical: "1",
      warning: "2"
    });
  });

  it("ack 首次同步前，summary ack 统计也显示 -", () => {
    expect(buildAlarmSummaryDisplay(summary(), {
      ackStatusUnavailable: false,
      ackStatusInitialized: false
    })).toEqual({
      total: "3",
      active: "-",
      acknowledged: "-",
      critical: "1",
      warning: "2"
    });
  });

  it("ack 同步成功后，summary 显示真实 ack 统计", () => {
    expect(buildAlarmSummaryDisplay(summary(), {
      ackStatusUnavailable: false,
      ackStatusInitialized: true
    })).toEqual({
      total: "3",
      active: "2",
      acknowledged: "1",
      critical: "1",
      warning: "2"
    });
  });
});
