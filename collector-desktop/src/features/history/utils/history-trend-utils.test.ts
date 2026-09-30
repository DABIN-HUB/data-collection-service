import { describe, expect, it } from "vitest";

import { buildHistoryTrendExportText, buildHistoryTrendSeries, buildHistoryTrendSummaryCards } from "./history-trend-utils";

describe("history-trend-utils", () => {
  it("按全局尺度构建多点位历史趋势曲线", () => {
    const series = buildHistoryTrendSeries([
      { key: "temp", label: "温度", rows: [{ timestamp: 1, value: 10 }, { timestamp: 2, value: 20 }] },
      { key: "press", label: "压力", rows: [{ timestamp: 1, value: 5 }, { timestamp: 2, value: 15 }] }
    ]);

    expect(series).toHaveLength(2);
    expect(series[0]).toEqual(expect.objectContaining({ key: "temp", label: "温度", latestText: "20", sampleCount: 2 }));
    expect(series[1].points).toContain(",");
    expect(series[0].points).not.toBe("");
  });

  it("构造更强排障摘要卡", () => {
    const series = buildHistoryTrendSeries([
      { key: "temp", label: "温度", rows: [{ timestamp: 1, value: 10 }, { timestamp: 2, value: 20 }] },
      { key: "press", label: "压力", rows: [{ timestamp: 1, value: 5 }, { timestamp: 2, value: 15 }] }
    ]);
    const cards = buildHistoryTrendSummaryCards({
      deviceId: "dev-1",
      pointRef: "temp",
      pointLabel: "温度",
      series,
      relatedAlarms: [{ alarmId: "a1", deviceId: "dev-1", content: "温度过高" }],
      timeRangeText: "08:00 ~ 08:10"
    });

    expect(cards).toEqual([
      expect.objectContaining({ label: "主曲线最新值", value: "20" }),
      expect.objectContaining({ label: "对比点位", value: "1" }),
      expect.objectContaining({ label: "采样总数", value: "4" }),
      expect.objectContaining({ label: "相关告警", value: "1" }),
      expect.objectContaining({ label: "数值范围", value: "5 ~ 20" }),
      expect.objectContaining({ label: "时间范围", value: "08:00 ~ 08:10" })
    ]);
  });

  it("关联告警不可用时摘要卡不能伪装成 0 条告警", () => {
    const series = buildHistoryTrendSeries([
      { key: "temp", label: "温度", rows: [{ timestamp: 1, value: 10 }] }
    ]);
    const cards = buildHistoryTrendSummaryCards({
      deviceId: "dev-1",
      pointRef: "temp",
      pointLabel: "温度",
      series,
      relatedAlarms: [],
      relatedAlarmsUnavailable: true,
      timeRangeText: "08:00 ~ 08:10"
    });

    expect(cards[3]).toEqual(expect.objectContaining({
      label: "相关告警",
      value: "-",
      detail: "关联告警暂不可用"
    }));
  });

  it("导出文本需要区分关联告警失败与成功为空", () => {
    const series = buildHistoryTrendSeries([
      { key: "temp", label: "温度", rows: [{ timestamp: 1, value: 10 }] }
    ]);
    const exportText = buildHistoryTrendExportText({
      deviceId: "dev-1",
      pointRef: "temp",
      pointLabel: "温度",
      series,
      relatedAlarms: [],
      relatedAlarmsUnavailable: true,
      failedComparePointRefs: ["compare-a"],
      partialWarning: "部分数据不可用：关联告警"
    });
    const parsed = JSON.parse(exportText) as Record<string, unknown>;

    expect(parsed["relatedAlarmsUnavailable"]).toBe(true);
    expect(parsed["failedComparePointRefs"]).toEqual(["compare-a"]);
    expect(parsed["partialWarning"]).toBe("部分数据不可用：关联告警");
  });

});
