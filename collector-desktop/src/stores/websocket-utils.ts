import type { RealtimePointRow } from "@/types/monitor";

export type RealtimePayloadParseKind = "VALID" | "INVALID_JSON" | "UNSUPPORTED_PAYLOAD";

export interface RealtimePayloadParseResult {
  kind: RealtimePayloadParseKind;
  rows: RealtimePointRow[];
  error?: string;
}

const MAX_REALTIME_RECONNECT_DELAY_MS = 30_000;

export function buildRealtimeWebSocketUrl(serverUrl: string, deviceId: string): string {
  const normalized = serverUrl.trim().replace(/\/+$/, "") || "http://127.0.0.1:18080";
  const url = new URL(`${normalized}/ws/realtime`);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("deviceId", deviceId);
  return url.toString();
}

export function normalizeRealtimeMessage(message: unknown): RealtimePayloadParseResult {
  if (Array.isArray(message)) {
    return {
      kind: "VALID",
      rows: message as RealtimePointRow[]
    };
  }
  if (message && typeof message === "object") {
    const body = message as Record<string, unknown>;
    if (Array.isArray(body.points)) {
      return {
        kind: "VALID",
        rows: body.points as RealtimePointRow[]
      };
    }
    if (Array.isArray(body.data)) {
      return {
        kind: "VALID",
        rows: body.data as RealtimePointRow[]
      };
    }
    if (body.pointId || body.pointCode || body.address || body.pointName) {
      return {
        kind: "VALID",
        rows: [body as RealtimePointRow]
      };
    }
  }
  return {
    kind: "UNSUPPORTED_PAYLOAD",
    rows: [],
    error: "UNSUPPORTED_PAYLOAD: WebSocket 实时消息结构不受支持"
  };
}

export function parseRealtimePayload(payload: string): RealtimePayloadParseResult {
  try {
    return normalizeRealtimeMessage(JSON.parse(payload));
  } catch (error) {
    return {
      kind: "INVALID_JSON",
      rows: [],
      error: buildParseErrorMessage("INVALID_JSON", error)
    };
  }
}

export function getRealtimeRowIdentity(row: RealtimePointRow, fallbackDeviceId?: string): string | null {
  const pointId = normalizeIdentityPart(row.pointId);
  if (pointId) {
    return pointId;
  }
  const pointCode = normalizeIdentityPart(row.pointCode);
  if (pointCode) {
    return pointCode;
  }
  const address = normalizeIdentityPart(row.address);
  if (address) {
    return address;
  }
  const deviceId = normalizeIdentityPart(row.deviceId) || normalizeIdentityPart(fallbackDeviceId);
  const pointName = normalizeIdentityPart(row.pointName);
  if (deviceId && pointName) {
    return `${deviceId}::${pointName}`;
  }
  return null;
}

export function mergeRealtimeRows(
  existingRows: RealtimePointRow[],
  incomingRows: RealtimePointRow[],
  fallbackDeviceId: string
): RealtimePointRow[] {
  const rowsByKey = new Map<string, RealtimePointRow>();
  for (const row of existingRows) {
    const normalizedRow = withFallbackDeviceId(row, fallbackDeviceId);
    const key = getRealtimeRowIdentity(normalizedRow, fallbackDeviceId);
    if (key) {
      rowsByKey.set(key, normalizedRow);
    }
  }
  for (const row of incomingRows) {
    const normalizedRow = withFallbackDeviceId(row, fallbackDeviceId);
    const key = getRealtimeRowIdentity(normalizedRow, fallbackDeviceId);
    if (!key) {
      continue;
    }
    rowsByKey.set(key, {
      ...rowsByKey.get(key),
      ...normalizedRow
    });
  }
  return Array.from(rowsByKey.values());
}

export function getRealtimeReconnectDelayMs(attempt: number): number {
  const safeAttempt = Math.max(1, Math.trunc(attempt));
  return Math.min(1_000 * 2 ** (safeAttempt - 1), MAX_REALTIME_RECONNECT_DELAY_MS);
}

function withFallbackDeviceId(row: RealtimePointRow, fallbackDeviceId: string): RealtimePointRow {
  const deviceId = normalizeIdentityPart(row.deviceId) || normalizeIdentityPart(fallbackDeviceId);
  return deviceId ? { ...row, deviceId } : { ...row };
}

function normalizeIdentityPart(value: unknown): string {
  return String(value ?? "").trim();
}

function buildParseErrorMessage(kind: Extract<RealtimePayloadParseKind, "INVALID_JSON">, error: unknown): string {
  const reason = error instanceof Error ? error.message : "未知解析错误";
  return `${kind}: ${reason}`;
}
