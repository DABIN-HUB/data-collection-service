import { describe, expect, it } from "vitest";

import { resolveHistoryPartialFailure } from "./history-partial-failure";
import type { AlarmRow } from "@/types/monitor";
import type { HistoryRow } from "./history-data-utils";

function fulfilled<T>(value: T): PromiseFulfilledResult<T> {
  return { status: "fulfilled", value };
}

function rejected(reason: unknown): PromiseRejectedResult {
  return { status: "rejected", reason };
}

function row(value: number): HistoryRow {
  return { timestamp: value, value };
}

function alarm(id: string): AlarmRow {
  return { alarmId: id, content: id };
}

describe("history-partial-failure", () => {
  it("all success 时保留主历史、全部 compare 和关联告警", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1), row(2)]),
      compareResults: [
        { ref: "compare-a", result: fulfilled([row(3)]) },
        { ref: "compare-b", result: fulfilled([row(4)]) }
      ],
      relatedAlarmsResult: fulfilled([alarm("a-1")]),
      pointLabelOf: (ref) => ref.toUpperCase()
    });

    expect(result.historyRows).toHaveLength(2);
    expect(result.comparePointRows).toEqual({
      "compare-a": [row(3)],
      "compare-b": [row(4)]
    });
    expect(result.relatedAlarms).toEqual([alarm("a-1")]);
    expect(result.failedComparePointRefs).toEqual([]);
    expect(result.relatedAlarmsUnavailable).toBe(false);
    expect(result.historyError).toBe("");
    expect(result.historyPartialWarning).toBe("");
  });

  it("单个 compare failure 不清主历史，也不影响其他 compare", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [
        { ref: "compare-a", result: rejected(new Error("compare down")) },
        { ref: "compare-b", result: fulfilled([row(2)]) }
      ],
      relatedAlarmsResult: fulfilled([alarm("a-1")]),
      pointLabelOf: (ref) => ref === "compare-a" ? "环境温度" : ref
    });

    expect(result.historyRows).toEqual([row(1)]);
    expect(result.comparePointRows).toEqual({ "compare-b": [row(2)] });
    expect(result.failedComparePointRefs).toEqual(["compare-a"]);
    expect(result.historyPartialWarning).toContain("环境温度");
    expect(result.relatedAlarmsUnavailable).toBe(false);
  });

  it("全部 compare failure 时主历史仍保留，series 侧可只剩主曲线", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [
        { ref: "compare-a", result: rejected("compare-a failed") },
        { ref: "compare-b", result: rejected("compare-b failed") }
      ],
      relatedAlarmsResult: fulfilled([]),
      pointLabelOf: (ref) => ref
    });

    expect(result.historyRows).toEqual([row(1)]);
    expect(result.comparePointRows).toEqual({});
    expect(result.failedComparePointRefs).toEqual(["compare-a", "compare-b"]);
    expect(result.historyPartialWarning).toContain("compare-a");
    expect(result.historyPartialWarning).toContain("compare-b");
  });

  it("关联告警失败时保留历史，并标记 unavailable", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [{ ref: "compare-a", result: fulfilled([row(2)]) }],
      relatedAlarmsResult: rejected(new Error("alarm down")),
      pointLabelOf: (ref) => ref
    });

    expect(result.historyRows).toEqual([row(1)]);
    expect(result.comparePointRows).toEqual({ "compare-a": [row(2)] });
    expect(result.relatedAlarms).toEqual([]);
    expect(result.relatedAlarmsUnavailable).toBe(true);
    expect(result.historyPartialWarning).toContain("关联告警");
  });

  it("关联告警成功但为空时不标记 unavailable", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [],
      relatedAlarmsResult: fulfilled([]),
      pointLabelOf: (ref) => ref
    });

    expect(result.relatedAlarms).toEqual([]);
    expect(result.relatedAlarmsUnavailable).toBe(false);
    expect(result.historyPartialWarning).toBe("");
  });

  it("compare 成功但 0 rows 不是 failed compare", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [{ ref: "compare-a", result: fulfilled([]) }],
      relatedAlarmsResult: fulfilled([]),
      pointLabelOf: (ref) => ref
    });

    expect(result.comparePointRows).toEqual({ "compare-a": [] });
    expect(result.failedComparePointRefs).toEqual([]);
    expect(result.historyPartialWarning).toBe("");
  });

  it("main failure 仍是 fatal，optional success 不可单独显示", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: rejected(new Error("main timeout")),
      compareResults: [{ ref: "compare-a", result: fulfilled([row(2)]) }],
      relatedAlarmsResult: fulfilled([alarm("a-1")]),
      pointLabelOf: (ref) => ref
    });

    expect(result.historyRows).toEqual([]);
    expect(result.comparePointRows).toEqual({});
    expect(result.relatedAlarms).toEqual([]);
    expect(result.failedComparePointRefs).toEqual([]);
    expect(result.relatedAlarmsUnavailable).toBe(false);
    expect(result.historyError).toContain("主历史查询失败");
    expect(result.historyPartialWarning).toBe("");
  });

  it("多个 optional failure 会合并为一条 warning", () => {
    const result = resolveHistoryPartialFailure({
      mainResult: fulfilled([row(1)]),
      compareResults: [
        { ref: "compare-a", result: rejected(new Error("a failed")) },
        { ref: "compare-b", result: rejected(new Error("b failed")) }
      ],
      relatedAlarmsResult: rejected(new Error("alarm failed")),
      pointLabelOf: (ref) => ref.toUpperCase()
    });

    expect(result.historyRows).toEqual([row(1)]);
    expect(result.failedComparePointRefs).toEqual(["compare-a", "compare-b"]);
    expect(result.historyPartialWarning).toBe("部分数据不可用：对比点位“COMPARE-A”、“COMPARE-B”；关联告警");
  });
});
