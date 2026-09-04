import type { RealtimePointRow } from "@/types/monitor";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig } from "@/types/protocol";

export interface BuildIncrementalPointsOptions {
  count: number;
  baseAddress: string;
  addressStep: number;
  pointCodePrefix: string;
  pointNamePrefix: string;
  dataType: string;
  readWrite: string;
}

export interface PointBatchEditPayload {
  fields: string[];
  values: Partial<DataPoint>;
}

export interface PointImportPreview {
  rows: DataPoint[];
  duplicatePointCodes: string[];
  duplicateAddresses: string[];
  warnings: string[];
  summary: string;
}

export interface PointLocationTarget {
  deviceId: string;
  pointRef: string;
  pointName: string;
  pointLabel: string;
}

export function buildIncrementalPoints(options: BuildIncrementalPointsOptions): DataPoint[] {
  const count = Math.max(0, options.count);
  return Array.from({ length: count }, (_unused, index) => {
    const suffix = String(index + 1).padStart(3, "0");
    return {
      pointId: `local-${options.pointCodePrefix}_${suffix}`,
      pointCode: `${options.pointCodePrefix}_${suffix}`,
      pointName: `${options.pointNamePrefix}${suffix}`,
      address: incrementAddress(options.baseAddress, index * options.addressStep),
      dataType: options.dataType,
      readWrite: options.readWrite,
      collectionMode: "POLLING",
      status: 1,
      alarmEnabled: 0
    };
  });
}

export function applyPointBatchEdit(rows: DataPoint[], selectedIds: string[], payload: PointBatchEditPayload): DataPoint[] {
  const selected = new Set(selectedIds);
  return rows.map((row) => {
    const pointId = row.pointId || "";
    if (!selected.has(pointId)) {
      return row;
    }
    const patch = Object.fromEntries(payload.fields.map((field) => [field, payload.values[field]]));
    return {
      ...row,
      ...patch
    };
  });
}

export function buildPointImportPreview(points: DataPoint[]): PointImportPreview {
  const rows = normalizePointRows(points);
  const duplicatePointCodes = collectDuplicateValues(rows.map((row) => String(row.pointCode || "").trim()).filter(Boolean));
  const duplicateAddresses = collectDuplicateValues(rows.map((row) => String(row.address || "").trim()).filter(Boolean));
  const warnings = [
    duplicatePointCodes.length ? `点位编码重复：${duplicatePointCodes.join("、")}` : "",
    duplicateAddresses.length ? `地址重复：${duplicateAddresses.join("、")}` : ""
  ].filter(Boolean);
  return {
    rows,
    duplicatePointCodes,
    duplicateAddresses,
    warnings,
    summary: `共 ${rows.length} 条点位，${warnings.length ? `${warnings.length} 组重复` : "未发现重复项"}`
  };
}

export function buildPointLocationTarget(point: Pick<DataPoint, "pointId" | "pointCode" | "pointName" | "address">, deviceId: string): PointLocationTarget {
  const pointRef = String(point.pointId || point.pointCode || point.address || "");
  const pointName = String(point.pointName || point.pointCode || pointRef || "未命名点位");
  const pointLabel = [point.pointName || pointName, point.pointCode || pointRef].filter(Boolean).join(" / ");
  return {
    deviceId,
    pointRef,
    pointName,
    pointLabel: pointLabel || pointName
  };
}

export function normalizePointRows(rows: DataPoint[]): DataPoint[] {
  return rows.map((row, index) => {
    const pointCode = row.pointCode || `point_${String(index + 1).padStart(3, "0")}`;
    return {
      collectionMode: "POLLING",
      readWrite: "R",
      alarmEnabled: 0,
      status: 1,
      ...row,
      pointId: row.pointId || `local-${pointCode}`,
      pointCode
    };
  });
}

export type PointExtraModel = Record<string, unknown>;

export function buildPointExtraModel(fields: ProtocolFieldConfig[], point: Pick<DataPoint, "additionalConfig">): PointExtraModel {
  const additionalConfig = point.additionalConfig || {};
  return Object.fromEntries(fields.map((field) => [field.name, getPathValue(additionalConfig, field.name)]));
}

export function getPointExtraValue(point: Pick<DataPoint, "additionalConfig">, fieldName: string): unknown {
  return getPathValue(point.additionalConfig || {}, fieldName);
}

export function applyPointExtraModel<T extends DataPoint>(point: T, fields: ProtocolFieldConfig[], model: PointExtraModel): T {
  const additionalConfig = cloneRecord(point.additionalConfig);
  for (const field of fields) {
    setPathValue(additionalConfig, field.name, model[field.name]);
  }
  return {
    ...point,
    additionalConfig
  };
}

export function mergePointRuntime(points: DataPoint[], rows: RealtimePointRow[]): RealtimePointRow[] {
  const runtimeIndex = buildPointRuntimeLookup(rows);
  return points.map((point) => {
    const runtime = resolvePointRuntime(runtimeIndex, point);
    return runtime ? { ...point, ...runtime } : { ...point };
  });
}

export function buildPointRuntimeLookup(rows: RealtimePointRow[]): Map<string, RealtimePointRow> {
  const runtimeIndex = new Map<string, RealtimePointRow>();
  for (const row of rows) {
    for (const key of buildRuntimeKeys(row)) {
      runtimeIndex.set(key, row);
    }
  }
  return runtimeIndex;
}

export function resolvePointRuntime(
  runtimeIndex: ReadonlyMap<string, RealtimePointRow>,
  point: Pick<DataPoint, "pointId" | "pointCode" | "address">
): RealtimePointRow | undefined {
  for (const key of buildRuntimeKeys(point)) {
    const runtime = runtimeIndex.get(key);
    if (runtime) {
      return runtime;
    }
  }
  return undefined;
}

export function formatJsonForTextarea(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

export function parseJsonTextarea<T>(text: string, fallback: T): T {
  const trimmed = text.trim();
  if (!trimmed) {
    return fallback;
  }
  return JSON.parse(trimmed) as T;
}

function incrementAddress(baseAddress: string, offset: number): string {
  const numberValue = Number(baseAddress);
  if (!Number.isFinite(numberValue)) {
    return baseAddress;
  }
  return String(numberValue + offset).padStart(baseAddress.length, "0");
}

function buildRuntimeKeys(point: Pick<DataPoint, "pointId" | "pointCode" | "address">): string[] {
  return [point.pointId, point.pointCode, point.address]
    .map((value) => String(value || "").trim())
    .filter(Boolean);
}

function getPathValue(source: unknown, path: string): unknown {
  if (!source || !path) {
    return undefined;
  }
  const segments = path.split(".").filter(Boolean);
  let current: unknown = source;
  for (const segment of segments) {
    if (!isRecord(current)) {
      return undefined;
    }
    current = current[segment];
  }
  return current;
}

function setPathValue(target: Record<string, unknown>, path: string, value: unknown): void {
  const segments = path.split(".").filter(Boolean);
  if (!segments.length) {
    return;
  }
  let current = target;
  for (const segment of segments.slice(0, -1)) {
    if (!isRecord(current[segment])) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  }
  current[segments[segments.length - 1]] = value;
}

function cloneRecord(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) {
    return {};
  }
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}

function collectDuplicateValues(values: string[]): string[] {
  const counts = new Map<string, number>();
  for (const value of values) {
    counts.set(value, (counts.get(value) || 0) + 1);
  }
  return Array.from(counts.entries()).filter(([, count]) => count > 1).map(([value]) => value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
