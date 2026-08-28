import type { LogRow } from "@/types/monitor";

export interface LogFilterInput {
  level?: string;
  logger?: string;
  keyword?: string;
  deviceId?: string;
  thread?: string;
  limit?: number;
}

export interface LogSummary {
  total: number;
  error: number;
  warn: number;
  loggerCount: number;
  threadCount: number;
}

export function buildLogQueryParams(input: LogFilterInput): Record<string, string | number | undefined> {
  const keywordParts = [input.keyword, input.deviceId, input.thread].map((value) => String(value || "").trim()).filter(Boolean);
  return {
    level: normalizeOptional(input.level)?.toUpperCase(),
    logger: normalizeOptional(input.logger),
    keyword: keywordParts.length ? keywordParts.join(" ") : undefined,
    limit: normalizeLimit(input.limit)
  };
}

export function filterLogRows(rows: LogRow[], input: LogFilterInput): LogRow[] {
  const level = normalizeOptional(input.level)?.toUpperCase();
  const logger = normalizeOptional(input.logger)?.toLowerCase();
  const keyword = normalizeOptional(input.keyword)?.toLowerCase();
  const deviceId = normalizeOptional(input.deviceId)?.toLowerCase();
  const thread = normalizeOptional(input.thread)?.toLowerCase();
  return rows.filter((row) => {
    const rowLevel = String(row.level || "").toUpperCase();
    const rowLogger = String(row.logger || "").toLowerCase();
    const rowThread = String(row.thread || "").toLowerCase();
    const rowText = logSearchText(row).toLowerCase();
    return (!level || rowLevel === level)
      && (!logger || rowLogger.includes(logger))
      && (!thread || rowThread.includes(thread))
      && (!deviceId || rowText.includes(deviceId))
      && (!keyword || rowText.includes(keyword));
  });
}

export function exportLogRowsAsJson(rows: LogRow[]): string {
  return JSON.stringify(rows, null, 2);
}

export function exportLogRowsAsText(rows: LogRow[]): string {
  return rows.map((row) => [
    formatTime(row.timestamp || row.time),
    row.level || "INFO",
    row.logger || row.deviceName || row.deviceId || "-",
    row.thread || "-",
    row.message || row.content || ""
  ].map((value) => String(value ?? "")).join("\t")).join("\n");
}

export function summarizeLogRows(rows: LogRow[]): LogSummary {
  const loggers = new Set<string>();
  const threads = new Set<string>();
  return rows.reduce<LogSummary>((summary, row) => {
    const level = String(row.level || "").toUpperCase();
    if (level === "ERROR") {
      summary.error += 1;
    }
    if (level === "WARN" || level === "WARNING") {
      summary.warn += 1;
    }
    if (row.logger) {
      loggers.add(String(row.logger));
    }
    if (row.thread) {
      threads.add(String(row.thread));
    }
    summary.total += 1;
    summary.loggerCount = loggers.size;
    summary.threadCount = threads.size;
    return summary;
  }, { total: 0, error: 0, warn: 0, loggerCount: 0, threadCount: 0 });
}

export function buildLogSearchFromException(exception: Record<string, unknown>): string {
  return [exception.deviceId, exception.pointId, exception.category, exception.message]
    .map((value) => String(value || "").trim())
    .filter(Boolean)
    .join(" ");
}

export function buildLogExportFilename(type: "json" | "txt", now: number = Date.now()): string {
  const stamp = new Date(now).toISOString().replace(/[:.]/g, "-");
  return `collector-logs-${stamp}.${type}`;
}

function logSearchText(row: LogRow): string {
  return [
    row.timestamp,
    row.time,
    row.level,
    row.logger,
    row.thread,
    row.deviceId,
    row.deviceName,
    row.message,
    row.content
  ].map((value) => String(value || "")).join(" ");
}

function normalizeOptional(value: unknown): string | undefined {
  const text = String(value || "").trim();
  return text || undefined;
}

function normalizeLimit(value: unknown): number | undefined {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return undefined;
  }
  return Math.max(1, Math.min(2000, Math.trunc(number)));
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toISOString();
  }
  return value ? String(value) : "-";
}
