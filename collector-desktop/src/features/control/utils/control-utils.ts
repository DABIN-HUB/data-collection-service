export type ControlDataType = "STRING" | "BOOLEAN" | "INT" | "FLOAT" | "DOUBLE";

export interface SinglePointControlPayload {
  value: unknown;
  dataType: string;
}

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

export function buildSinglePointControlPayload(rawValue: string, dataType: string): SinglePointControlPayload {
  return {
    value: parseControlValue(rawValue, dataType),
    dataType
  };
}

export function buildBatchControlTemplate(): Record<string, unknown> {
  return {
    points: [
      {
        pointId: "point_001",
        value: 1,
        dataType: "INT"
      }
    ]
  };
}

export function buildCommandTemplate(): Record<string, unknown> {
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
    throw new Error(`${label} 格式错误：${message}`);
  }
}

export function formatControlJson(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
