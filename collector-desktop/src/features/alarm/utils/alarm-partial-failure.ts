import type { AlarmRow } from "@/types/monitor";

import { describeAlarmAcknowledgement, isAlarmAcknowledged, type AlarmSummary } from "./alarm-utils";

export interface AlarmAcknowledgementPresentation {
  statusText: string;
  detailText: string;
  toneClass: "is-online" | "is-error" | "is-warning";
}

export interface AlarmAcknowledgementDisplayState {
  ackStatusUnavailable: boolean;
  ackStatusInitialized: boolean;
}

export interface AlarmSummaryDisplay {
  total: string;
  active: string;
  acknowledged: string;
  critical: string;
  warning: string;
}

export function buildAckStatusWarning(): string {
  return "确认状态暂不可用，当前显示告警历史和最后已知确认状态";
}

export function buildAlarmAcknowledgementPresentation(
  alarm: AlarmRow,
  state: AlarmAcknowledgementDisplayState
): AlarmAcknowledgementPresentation {
  if (isAlarmAcknowledged(alarm)) {
    return {
      statusText: "已确认",
      detailText: describeAlarmAcknowledgement(alarm.acknowledgement),
      toneClass: "is-online"
    };
  }

  if (state.ackStatusUnavailable || !state.ackStatusInitialized) {
    return {
      statusText: "状态未同步",
      detailText: "确认信息暂未同步",
      toneClass: "is-warning"
    };
  }

  return {
    statusText: "待确认",
    detailText: describeAlarmAcknowledgement(alarm.acknowledgement),
    toneClass: "is-error"
  };
}

export function buildAlarmSummaryDisplay(
  summary: AlarmSummary,
  state: AlarmAcknowledgementDisplayState
): AlarmSummaryDisplay {
  const ackAvailable = state.ackStatusInitialized && !state.ackStatusUnavailable;
  return {
    total: String(summary.total),
    active: ackAvailable ? String(summary.active) : "-",
    acknowledged: ackAvailable ? String(summary.acknowledged) : "-",
    critical: String(summary.critical),
    warning: String(summary.warning)
  };
}
