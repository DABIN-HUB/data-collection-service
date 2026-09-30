import type { ConfigImportRequest } from "@/types/config";

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
    throw new Error(`配置导入 JSON 格式错误：${message}`, { cause: error });
  }
}

export function buildConfigImportRequest(parsed: unknown, reloadAfterImport: boolean): ConfigImportRequest {
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

function resolveBundles(value: unknown): ConfigImportRequest["bundles"] {
  if (Array.isArray(value)) {
    return value as ConfigImportRequest["bundles"];
  }
  if (!value || typeof value !== "object") {
    return [];
  }
  const record = value as Record<string, unknown>;
  if (Array.isArray(record.bundles)) {
    return record.bundles as ConfigImportRequest["bundles"];
  }
  if (record.device || record.connection || record.points) {
    return [record as ConfigImportRequest["bundles"][number]];
  }
  return [];
}
