import { shouldDisableLatestRequestSubmit } from "../../request/utils/latest-request-owner";

export interface LogServerQueryContext {
  level: string;
  logger: string;
  keyword: string;
  limit?: number;
}

export interface LogVisibleQueryContext extends LogServerQueryContext {
  deviceId: string;
  thread: string;
}

export function buildLogServerQueryContext(input: Partial<LogVisibleQueryContext>): LogServerQueryContext {
  return {
    level: normalizeLevel(input.level),
    logger: normalizeText(input.logger),
    keyword: normalizeText(input.keyword),
    limit: normalizeLimit(input.limit)
  };
}

export function buildLogVisibleQueryContext(input: Partial<LogVisibleQueryContext>): LogVisibleQueryContext {
  const serverContext = buildLogServerQueryContext(input);
  return {
    ...serverContext,
    deviceId: normalizeText(input.deviceId),
    thread: normalizeText(input.thread)
  };
}

export function isSameLogServerQueryContext(
  left: Partial<LogServerQueryContext> | null | undefined,
  right: Partial<LogServerQueryContext> | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeLevel(left.level) === normalizeLevel(right.level)
    && normalizeText(left.logger) === normalizeText(right.logger)
    && normalizeText(left.keyword) === normalizeText(right.keyword)
    && normalizeLimit(left.limit) === normalizeLimit(right.limit);
}

export function isSameLogVisibleQueryContext(
  left: Partial<LogVisibleQueryContext> | null | undefined,
  right: Partial<LogVisibleQueryContext> | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return isSameLogServerQueryContext(left, right)
    && normalizeText(left.deviceId) === normalizeText(right.deviceId)
    && normalizeText(left.thread) === normalizeText(right.thread);
}

export function shouldDisableLogSubmit(
  loading: boolean,
  pendingContext: LogServerQueryContext | null | undefined,
  liveContext: LogServerQueryContext
): boolean {
  return shouldDisableLatestRequestSubmit(loading, pendingContext, liveContext, isSameLogServerQueryContext);
}

export function shouldSkipLogTimerTick(
  loading: boolean,
  pendingContext: LogServerQueryContext | null | undefined,
  liveContext: LogServerQueryContext
): boolean {
  return shouldDisableLogSubmit(loading, pendingContext, liveContext);
}

function normalizeLevel(value: unknown): string {
  return normalizeText(value).toUpperCase();
}

function normalizeLimit(value: unknown): number | undefined {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return undefined;
  }
  return Math.max(1, Math.min(2000, Math.trunc(parsed)));
}

function normalizeText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
