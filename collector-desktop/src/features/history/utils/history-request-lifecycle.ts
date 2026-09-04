import { shouldDisableLatestRequestSubmit } from "../../request/utils/latest-request-owner";

export interface HistoryPointsRequestContext {
  deviceId: string;
}

export interface HistoryPointsRequestSnapshot {
  deviceId: string;
  preferredPointRef: string;
  autoQuery: boolean;
}

export interface HistoryQueryContext {
  deviceId: string;
  pointRef: string;
  comparePointRefs: string[];
  startTs?: number;
  endTs?: number;
  limit: number;
}

export interface HistoryQueryContextInput {
  deviceId: string;
  pointRef: string;
  comparePointRefs: string[];
  startTime?: string;
  endTime?: string;
  startTs?: number;
  endTs?: number;
  limit: number;
}

export function buildHistoryPointsRequestSnapshot(input: {
  deviceId: string;
  preferredPointRef?: string;
  autoQuery?: boolean;
}): HistoryPointsRequestSnapshot {
  return {
    deviceId: normalizeText(input.deviceId),
    preferredPointRef: normalizeText(input.preferredPointRef),
    autoQuery: Boolean(input.autoQuery)
  };
}

export function buildHistoryPointsRequestContext(input: { deviceId: string }): HistoryPointsRequestContext {
  return {
    deviceId: normalizeText(input.deviceId)
  };
}

export function isSameHistoryPointsRequestContext(
  left: HistoryPointsRequestContext | null | undefined,
  right: HistoryPointsRequestContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId);
}

export function buildHistoryQueryContext(input: HistoryQueryContextInput | HistoryQueryContext): HistoryQueryContext {
  const pointRef = normalizeText(input.pointRef);
  return {
    deviceId: normalizeText(input.deviceId),
    pointRef,
    comparePointRefs: normalizeComparePointRefs(input.comparePointRefs, pointRef),
    startTs: normalizeTimestamp(input.startTs, "startTime" in input ? input.startTime : undefined),
    endTs: normalizeTimestamp(input.endTs, "endTime" in input ? input.endTime : undefined),
    limit: normalizeInteger(input.limit)
  };
}

export function buildHistoryDataQueryParams(context: HistoryQueryContext): {
  startTs?: number;
  endTs?: number;
  limit: number;
} {
  return {
    startTs: context.startTs,
    endTs: context.endTs,
    limit: context.limit
  };
}

export function buildHistoryRelatedAlarmQuery(context: HistoryQueryContext): {
  pointCode: string;
  pointId: string;
  startTs?: number;
  endTs?: number;
  limit: number;
} {
  return {
    pointCode: context.pointRef,
    pointId: context.pointRef,
    startTs: context.startTs,
    endTs: context.endTs,
    limit: 20
  };
}

export function isSameHistoryQueryContext(
  left: HistoryQueryContext | null | undefined,
  right: HistoryQueryContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId)
    && normalizeText(left.pointRef) === normalizeText(right.pointRef)
    && normalizeInteger(left.limit) === normalizeInteger(right.limit)
    && normalizeOptionalNumber(left.startTs) === normalizeOptionalNumber(right.startTs)
    && normalizeOptionalNumber(left.endTs) === normalizeOptionalNumber(right.endTs)
    && isSameTextArray(left.comparePointRefs, right.comparePointRefs);
}

export function shouldDisableHistorySubmit(
  loading: boolean,
  pendingContext: HistoryQueryContext | null | undefined,
  liveContext: HistoryQueryContext
): boolean {
  return shouldDisableLatestRequestSubmit(loading, pendingContext, liveContext, isSameHistoryQueryContext);
}

function normalizeComparePointRefs(values: string[], pointRef: string): string[] {
  return values
    .map((value) => normalizeText(value))
    .filter((value) => value && value !== pointRef);
}

function isSameTextArray(left: string[], right: string[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => normalizeText(value) === normalizeText(right[index]));
}

function normalizeTimestamp(rawNumber?: number, rawText?: string): number | undefined {
  if (Number.isFinite(rawNumber)) {
    return Math.trunc(rawNumber as number);
  }
  if (!rawText) {
    return undefined;
  }
  const timestamp = new Date(rawText).getTime();
  return Number.isFinite(timestamp) ? timestamp : undefined;
}

function normalizeInteger(value: unknown): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? Math.trunc(numberValue) : 0;
}

function normalizeOptionalNumber(value: unknown): number | undefined {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? Math.trunc(numberValue) : undefined;
}

function normalizeText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
