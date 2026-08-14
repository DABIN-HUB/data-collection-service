import type { RealtimePointRow } from "@/types/monitor";

export function buildRealtimeWebSocketUrl(serverUrl: string, deviceId: string): string {
  const normalized = serverUrl.trim().replace(/\/+$/, "") || "http://127.0.0.1:18080";
  const url = new URL(`${normalized}/ws/realtime`);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("deviceId", deviceId);
  return url.toString();
}

export function normalizeRealtimeMessage(message: unknown): RealtimePointRow[] {
  if (Array.isArray(message)) {
    return message as RealtimePointRow[];
  }
  if (message && typeof message === "object") {
    const body = message as Record<string, unknown>;
    if (Array.isArray(body.points)) {
      return body.points as RealtimePointRow[];
    }
    if (Array.isArray(body.data)) {
      return body.data as RealtimePointRow[];
    }
    if (body.pointId || body.pointCode) {
      return [body as RealtimePointRow];
    }
  }
  return [];
}

export function parseRealtimePayload(payload: string): RealtimePointRow[] {
  try {
    return normalizeRealtimeMessage(JSON.parse(payload));
  } catch {
    return [];
  }
}
