export interface ConfigSyncTypeOption {
  type: string;
  label: string;
  requireDevice?: boolean;
}

export interface ConfigSyncStatusItem {
  label: string;
  value: string;
}

export const CONFIG_SYNC_TYPES: ConfigSyncTypeOption[] = [
  { type: "device", label: "设备配置", requireDevice: true },
  { type: "points", label: "点位配置", requireDevice: true },
  { type: "connection", label: "连接配置", requireDevice: true },
  { type: "collection", label: "采集计划", requireDevice: true },
  { type: "all", label: "全部配置" }
];

export function normalizeConfigExportText(response: unknown): string {
  if (typeof response === "string") {
    return response;
  }
  return JSON.stringify(response ?? {}, null, 2);
}

export function parseConfigImportText(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) {
    throw new Error("配置导入内容不能为空");
  }
  try {
    return JSON.parse(trimmed) as unknown;
  } catch (error) {
    const message = error instanceof Error ? error.message : "JSON 解析失败";
    throw new Error(`配置导入 JSON 格式错误：${message}`);
  }
}

export function buildConfigImportRequest(parsed: unknown, reloadAfterImport: boolean): Record<string, unknown> {
  return {
    bundles: resolveBundles(parsed),
    reloadAfterImport
  };
}

export function countConfigImportBundles(parsed: unknown): number {
  return resolveBundles(parsed).length;
}

export function buildConfigExportFilename(date = new Date()): string {
  return `collector-device-config-${date.toISOString().replace(/[:.]/g, "-")}.json`;
}

export function normalizeSyncStatusItems(response: unknown): ConfigSyncStatusItem[] {
  const record = unwrapDataRecord(response);
  return [
    { label: "服务实例", value: valueText(record.serviceId) },
    { label: "最近同步", value: timeText(record.lastSyncTime) },
    { label: "同步间隔", value: record.syncInterval === undefined || record.syncInterval === null ? "-" : `${record.syncInterval} ms` },
    { label: "监听器数量", value: valueText(record.listenerCount) },
    { label: "连续失败", value: valueText(record.consecutiveFailures) },
    { label: "配置源版本", value: valueText(record.sourceVersion) },
    { label: "快照设备数", value: valueText(record.snapshotDeviceCount) }
  ];
}

function unwrapDataRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  const record = value as Record<string, unknown>;
  if (record.data && typeof record.data === "object" && !Array.isArray(record.data)) {
    return record.data as Record<string, unknown>;
  }
  return record;
}

function resolveBundles(value: unknown): unknown[] {
  if (Array.isArray(value)) {
    return value;
  }
  if (!value || typeof value !== "object") {
    return [];
  }
  const record = value as Record<string, unknown>;
  if (Array.isArray(record.bundles)) {
    return record.bundles;
  }
  if (record.device || record.connection || record.points) {
    return [record];
  }
  return [];
}

function valueText(value: unknown): string {
  if (value === undefined || value === null || value === "") {
    return "-";
  }
  return String(value);
}

function timeText(value: unknown): string {
  if (value === undefined || value === null || value === "") {
    return "-";
  }
  const date = new Date(typeof value === "string" || typeof value === "number" || value instanceof Date ? value : String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", { hour12: false });
}
