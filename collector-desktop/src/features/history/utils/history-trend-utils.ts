import type { AlarmRow } from "@/types/monitor";
import type { HistoryRow } from "./history-data-utils";

export interface HistoryTrendSeriesInput {
  key: string;
  label: string;
  rows: HistoryRow[];
}

export interface HistoryTrendSeriesSummary extends HistoryTrendSeriesInput {
  color: string;
  points: string;
  latestText: string;
  minimumText: string;
  maximumText: string;
  sampleCount: number;
}

export interface HistoryTrendExportInput {
  deviceId: string;
  pointRef: string;
  pointLabel: string;
  series: HistoryTrendSeriesSummary[];
  relatedAlarms?: AlarmRow[];
}

export interface HistoryTrendSummaryInput extends HistoryTrendExportInput {
  timeRangeText?: string;
}

export interface HistoryTrendSummaryCard {
  label: string;
  value: string;
  detail: string;
}

const SERIES_COLORS = ["#38bdf8", "#f97316", "#22c55e", "#a855f7", "#f43f5e", "#eab308"];

export function buildHistoryTrendSeries(seriesInputs: HistoryTrendSeriesInput[]): HistoryTrendSeriesSummary[] {
  const rows = seriesInputs.map((item) => ({
    ...item,
    values: item.rows.map(historyValue).filter((value): value is number => Number.isFinite(value))
  }));
  const allValues = rows.flatMap((item) => item.values);
  const globalMin = allValues.length ? Math.min(...allValues) : 0;
  const globalMax = allValues.length ? Math.max(...allValues) : 1;
  const span = globalMax - globalMin || 1;

  return rows.map((item, index) => {
    const points = item.values.length
      ? item.values.map((value, valueIndex) => {
        const x = item.values.length === 1 ? 0 : (valueIndex / (item.values.length - 1)) * 100;
        const y = 36 - ((value - globalMin) / span) * 32;
        return `${x.toFixed(2)},${y.toFixed(2)}`;
      }).join(" ")
      : "";
    const latestValue = item.values.length ? item.values[item.values.length - 1] : undefined;
    const minimumValue = item.values.length ? Math.min(...item.values) : undefined;
    const maximumValue = item.values.length ? Math.max(...item.values) : undefined;
    return {
      key: item.key,
      label: item.label,
      rows: item.rows,
      color: SERIES_COLORS[index % SERIES_COLORS.length],
      points,
      latestText: formatNumeric(latestValue),
      minimumText: formatNumeric(minimumValue),
      maximumText: formatNumeric(maximumValue),
      sampleCount: item.rows.length
    };
  });
}

export function buildHistoryTrendExportText(input: HistoryTrendExportInput): string {
  return JSON.stringify({
    deviceId: input.deviceId,
    pointRef: input.pointRef,
    pointLabel: input.pointLabel,
    generatedAt: new Date().toISOString(),
    series: input.series.map((item) => ({
      key: item.key,
      label: item.label,
      sampleCount: item.sampleCount,
      latestText: item.latestText,
      minimumText: item.minimumText,
      maximumText: item.maximumText,
      rows: item.rows
    })),
    relatedAlarms: input.relatedAlarms || []
  }, null, 2);
}

export function buildHistoryTrendSummaryCards(input: HistoryTrendSummaryInput): HistoryTrendSummaryCard[] {
  const series = input.series || [];
  const allValues = series.flatMap((item) => item.rows.map(historyValue).filter((value): value is number => Number.isFinite(value)));
  const mainSeries = series[0];
  const compareCount = Math.max(0, series.length - 1);
  const sampleCount = series.reduce((sum, item) => sum + item.sampleCount, 0);
  const alarmCount = input.relatedAlarms?.length || 0;
  const globalMin = allValues.length ? Math.min(...allValues) : undefined;
  const globalMax = allValues.length ? Math.max(...allValues) : undefined;
  const rangeText = globalMin === undefined || globalMax === undefined ? "-" : `${formatNumeric(globalMin)} ~ ${formatNumeric(globalMax)}`;
  const spreadText = globalMin === undefined || globalMax === undefined ? "-" : formatNumeric(globalMax - globalMin);

  return [
    {
      label: "主曲线最新值",
      value: mainSeries?.latestText || "-",
      detail: mainSeries?.label || input.pointLabel || input.pointRef
    },
    {
      label: "对比点位",
      value: String(compareCount),
      detail: `${series.length} 条曲线`
    },
    {
      label: "采样总数",
      value: String(sampleCount),
      detail: mainSeries ? `主曲线 ${mainSeries.sampleCount} 条` : "无主曲线数据"
    },
    {
      label: "相关告警",
      value: String(alarmCount),
      detail: alarmCount > 0 ? "可联动告警历史" : "暂无关联告警"
    },
    {
      label: "数值范围",
      value: rangeText,
      detail: `波动 ${spreadText}`
    },
    {
      label: "时间范围",
      value: input.timeRangeText || "-",
      detail: input.pointLabel || input.pointRef
    }
  ];
}

function historyValue(row: HistoryRow): number | undefined {
  const raw = row.value ?? row.currentValue ?? row.rawValue ?? row.val;
  const number = Number(raw);
  return Number.isFinite(number) ? number : undefined;
}

function formatNumeric(value: number | undefined): string {
  if (value === undefined) {
    return "-";
  }
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)));
}
