import { buildActionExecutionTarget, summarizePayload, type ActionExecutionTarget } from "@/features/action/utils/action-result-context";
import type { DeviceCommandRequest, PointWriteRequest } from "@/types/control";

export type ControlDataType = "STRING" | "BOOLEAN" | "INT" | "FLOAT" | "DOUBLE";
export type ControlActionKind = "single-write" | "batch-write" | "command";

export interface ControlJsonParseResult<T = unknown> {
  payload: T;
}

export function parseControlValue(rawValue: string, dataType: string): unknown {
  const normalizedType = dataType.toUpperCase();
  if (["INT", "FLOAT", "DOUBLE"].includes(normalizedType)) {
    const numberValue = Number(rawValue);
    return Number.isFinite(numberValue) ? numberValue : rawValue;
  }
  if (normalizedType === "BOOLEAN") {
    return rawValue === "true" || rawValue === "1" || rawValue === "是";
  }
  return rawValue;
}

export function buildSinglePointControlPayload(rawValue: string, dataType: string): PointWriteRequest {
  return {
    value: parseControlValue(rawValue, dataType)
  };
}

export function buildControlActionTarget(input: {
  deviceId: string;
  action: ControlActionKind;
  pointRef?: string;
  payload?: unknown;
  submittedAt?: number;
}): ActionExecutionTarget {
  return buildActionExecutionTarget({
    target: input.deviceId,
    deviceId: input.deviceId,
    action: input.action,
    pointRef: input.pointRef,
    payloadSummary: input.payload === undefined ? undefined : summarizePayload(input.payload),
    submittedAt: input.submittedAt
  });
}

export function buildBatchControlTemplate(): PointWriteRequest {
  return {
    values: {
      point_001: 1
    }
  };
}

export function buildCommandTemplate(): DeviceCommandRequest {
  return {
    command: "custom",
    params: {}
  };
}

export function parseControlJson<T = unknown>(text: string, label: string): T {
  try {
    return JSON.parse(text || "{}") as T;
  } catch (error) {
    const message = error instanceof Error ? error.message : "JSON 解析失败";
    throw new Error(`${label} 格式错误：${message}`, { cause: error });
  }
}

export function formatControlJson(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
