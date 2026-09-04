import { shouldDisableLatestRequestSubmit } from "../../request/utils/latest-request-owner";

import { buildAlarmIdentity } from "./alarm-utils";

export interface AlarmQueryContext {
  deviceId: string;
  level: string;
  keyword: string;
  hours: number;
  limit: number;
}

export interface AlarmAcknowledgementRefreshContext {
  alarmIds: string[];
  identityKey: string;
}

export function buildAlarmQueryContext(input: {
  deviceId?: string;
  level?: string;
  keyword?: string;
  hours?: number;
  limit?: number;
}): AlarmQueryContext {
  return {
    deviceId: normalizeText(input.deviceId),
    level: normalizeText(input.level),
    keyword: normalizeText(input.keyword),
    hours: normalizeInteger(input.hours),
    limit: normalizeInteger(input.limit)
  };
}

export function isSameAlarmQueryContext(
  left: AlarmQueryContext | null | undefined,
  right: AlarmQueryContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId)
    && normalizeText(left.level) === normalizeText(right.level)
    && normalizeText(left.keyword) === normalizeText(right.keyword)
    && normalizeInteger(left.hours) === normalizeInteger(right.hours)
    && normalizeInteger(left.limit) === normalizeInteger(right.limit);
}

export function shouldDisableAlarmSubmit(
  loading: boolean,
  pendingContext: AlarmQueryContext | null | undefined,
  liveContext: AlarmQueryContext
): boolean {
  return shouldDisableLatestRequestSubmit(loading, pendingContext, liveContext, isSameAlarmQueryContext);
}

export function buildAlarmAcknowledgementRefreshContext(
  rows: Array<Record<string, unknown>>
): AlarmAcknowledgementRefreshContext {
  const alarmIds = Array.from(new Set(rows
    .map((row) => buildAlarmIdentity(row))
    .filter(Boolean))).slice(0, 500);
  return {
    alarmIds,
    identityKey: alarmIds.join("|")
  };
}

export function isSameAlarmAcknowledgementRefreshContext(
  left: AlarmAcknowledgementRefreshContext | null | undefined,
  right: AlarmAcknowledgementRefreshContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return left.identityKey === right.identityKey;
}

function normalizeInteger(value: unknown): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? Math.trunc(numberValue) : 0;
}

function normalizeText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
