import type { AlarmRow } from "@/types/monitor";
import type { HistoryRow } from "./history-data-utils";

export interface HistorySettledCompareResult {
  ref: string;
  result: PromiseSettledResult<HistoryRow[]>;
}

export interface ResolveHistoryPartialFailureInput {
  mainResult: PromiseSettledResult<HistoryRow[]>;
  compareResults: HistorySettledCompareResult[];
  relatedAlarmsResult: PromiseSettledResult<AlarmRow[]>;
  pointLabelOf: (ref: string) => string;
}

export interface HistoryPartialFailureState {
  historyRows: HistoryRow[];
  comparePointRows: Record<string, HistoryRow[]>;
  relatedAlarms: AlarmRow[];
  failedComparePointRefs: string[];
  relatedAlarmsUnavailable: boolean;
  historyError: string;
  historyPartialWarning: string;
}

export function resolveHistoryPartialFailure(input: ResolveHistoryPartialFailureInput): HistoryPartialFailureState {
  if (input.mainResult.status === "rejected") {
    return {
      historyRows: [],
      comparePointRows: {},
      relatedAlarms: [],
      failedComparePointRefs: [],
      relatedAlarmsUnavailable: false,
      historyError: buildMainHistoryFailureMessage(input.mainResult.reason),
      historyPartialWarning: ""
    };
  }

  const comparePointRows: Record<string, HistoryRow[]> = {};
  const failedComparePointRefs: string[] = [];

  for (const compare of input.compareResults) {
    if (compare.result.status === "fulfilled") {
      comparePointRows[compare.ref] = compare.result.value;
      continue;
    }
    failedComparePointRefs.push(compare.ref);
  }

  const relatedAlarmsUnavailable = input.relatedAlarmsResult.status === "rejected";
  const relatedAlarms = input.relatedAlarmsResult.status === "fulfilled"
    ? input.relatedAlarmsResult.value
    : [];

  return {
    historyRows: input.mainResult.value,
    comparePointRows,
    relatedAlarms,
    failedComparePointRefs,
    relatedAlarmsUnavailable,
    historyError: "",
    historyPartialWarning: buildPartialWarning({
      failedComparePointRefs,
      relatedAlarmsUnavailable,
      pointLabelOf: input.pointLabelOf
    })
  };
}

function buildMainHistoryFailureMessage(reason: unknown): string {
  const detail = normalizeReasonText(reason);
  return detail ? `主历史查询失败：${detail}` : "主历史查询失败";
}

function buildPartialWarning(input: {
  failedComparePointRefs: string[];
  relatedAlarmsUnavailable: boolean;
  pointLabelOf: (ref: string) => string;
}): string {
  const parts: string[] = [];
  if (input.failedComparePointRefs.length > 0) {
    const labels = input.failedComparePointRefs.map((ref) => `“${input.pointLabelOf(ref)}”`).join("、");
    parts.push(`对比点位${labels}`);
  }
  if (input.relatedAlarmsUnavailable) {
    parts.push("关联告警");
  }
  return parts.length > 0 ? `部分数据不可用：${parts.join("；")}` : "";
}

function normalizeReasonText(reason: unknown): string {
  if (reason instanceof Error) {
    return reason.message.trim();
  }
  if (typeof reason === "string") {
    return reason.trim();
  }
  if (reason === null || reason === undefined) {
    return "";
  }
  return String(reason).trim();
}
