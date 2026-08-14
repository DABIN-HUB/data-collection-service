import { request } from "./http";
import type { AlarmRow, DeviceRealtimeDataResponse } from "@/types/monitor";

export function getPointRealtimeData(deviceId: string, pointId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointId)}`, method: "GET" });
}

export function getDeviceRealtimeData(deviceId: string, pointIds?: string[]): Promise<DeviceRealtimeDataResponse> {
  return request<DeviceRealtimeDataResponse>({
    url: `/api/data/device/${encodeURIComponent(deviceId)}`,
    method: "GET",
    params: pointIds?.length ? { pointIds: pointIds.join(",") } : undefined
  });
}

export function getAllDeviceDataSummaries(): Promise<unknown> {
  return request<unknown>({ url: "/api/data/devices", method: "GET" });
}

export function getDevicePointSummaries(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/points`, method: "GET" });
}

export function resetAdaptiveConfig(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/reset-adaptive`, method: "POST" });
}

export function getPointHistory(deviceId: string, pointId: string, params: Record<string, string | number | undefined> = {}): Promise<unknown> {
  return request<unknown>({ url: `/api/data/history/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointId)}`, method: "GET", params });
}

export function getRecentAlarms(params: Record<string, string | number | undefined> = {}): Promise<unknown> {
  return request<unknown>({ url: "/api/data/history/alarms", method: "GET", params });
}

export function getDeviceAlarmHistory(deviceId: string, params: Record<string, string | number | undefined> = {}): Promise<unknown> {
  return request<unknown>({ url: `/api/data/history/device/${encodeURIComponent(deviceId)}/alarms`, method: "GET", params });
}

export function normalizeAlarmRows(response: unknown): AlarmRow[] {
  if (Array.isArray(response)) {
    return response as AlarmRow[];
  }
  if (response && typeof response === "object") {
    const body = response as Record<string, unknown>;
    for (const key of ["alarms", "records", "rows", "data", "items"]) {
      if (Array.isArray(body[key])) {
        return body[key] as AlarmRow[];
      }
    }
  }
  return [];
}
