import type { DataPoint } from "@/types/point";

export type FieldValueType = "string" | "number" | "integer" | "boolean";

export interface AlarmRule {
  ruleId?: string;
  ruleName?: string;
  operator?: string;
  threshold?: number;
  duration?: number;
  level?: string;
  enabled?: boolean;
  description?: string;
  [key: string]: unknown;
}

export interface ReadonlyItem {
  label: string;
  value: string;
}

export function alarmRules(point: DataPoint | null): AlarmRule[] {
  const raw = point?.alarmRule;
  if (!raw) {
    return [];
  }
  if (Array.isArray(raw)) {
    return raw.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[];
  }
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[] : [];
    } catch {
      return [];
    }
  }
  return [];
}

export function serializeAlarmRules(rules: AlarmRule[]): string {
  const normalized = rules.map((rule) => pruneEmpty(rule)).filter((rule) => Object.keys(rule).length > 0);
  return normalized.length ? JSON.stringify(normalized) : "";
}

export function parsePointsJson(value: string): DataPoint[] {
  const parsed = JSON.parse(value || "[]") as DataPoint | DataPoint[];
  return Array.isArray(parsed) ? parsed : [parsed];
}

export function statusLabel(value: unknown): string {
  return Number(value ?? 1) === 0 ? "禁用" : "启用";
}

export function parseBooleanOption(value: unknown): boolean | undefined {
  if (value === "") {
    return undefined;
  }
  return value === true || value === "true" || value === "1" || value === 1;
}

export function parseFieldValue(value: unknown, valueType: FieldValueType | undefined): unknown {
  if (value === "") {
    return undefined;
  }
  if (valueType === "boolean") {
    return value === true || value === "true" || value === "1" || value === 1;
  }
  if (valueType === "number" || valueType === "integer") {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      return undefined;
    }
    return valueType === "integer" ? Math.trunc(numberValue) : numberValue;
  }
  return value;
}

export function toNumber(value: unknown): number | undefined {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : undefined;
}

export function findDuplicatePointCode(source: DataPoint[]): string {
  const seen = new Set<string>();
  for (const point of source) {
    const code = String(point.pointCode || "").trim();
    if (!code) {
      continue;
    }
    if (seen.has(code)) {
      return code;
    }
    seen.add(code);
  }
  return "";
}

export function createUniqueCode(source: DataPoint[], base: string): string {
  const used = new Set(source.map((point) => String(point.pointCode || "").trim()).filter(Boolean));
  const normalizedBase = base.replace(/[^a-zA-Z0-9_]/g, "_") || "point";
  let candidate = normalizedBase;
  let index = 1;
  while (used.has(candidate)) {
    candidate = `${normalizedBase}_${index}`;
    index += 1;
  }
  return candidate;
}

export function buildReadonlyItems(point: DataPoint | null): ReadonlyItem[] {
  if (!point) {
    return [];
  }
  return [
    ["记录ID", point.id],
    ["点位ID", point.pointId],
    ["设备ID", point.deviceId],
    ["设备名称", point.deviceName],
    ["基础采集周期", point.baseCollectionInterval],
    ["当前采集周期", point.currentCollectionInterval],
    ["最小采集周期", point.minCollectionInterval],
    ["最大采集周期", point.maxCollectionInterval],
    ["点位变化阈值", point.pointChangeThreshold],
    ["稳定次数", point.stableCount],
    ["最新值", point.lastValue],
    ["变化率", point.changeRate],
    ["最近调整时间", point.lastAdjustTime],
    ["上报属性冲突", point.reportFieldConflict],
    ["创建时间", point.createTime],
    ["更新时间", point.updateTime]
  ].filter(([, value]) => hasValue(value)).map(([label, value]) => ({ label: String(label), value: typeof value === "object" ? JSON.stringify(value) : String(value) }));
}

function pruneEmpty(rule: AlarmRule): AlarmRule {
  const next: AlarmRule = {};
  for (const [key, value] of Object.entries(rule)) {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      next[key] = value;
    }
  }
  return next;
}

function hasValue(value: unknown): boolean {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
