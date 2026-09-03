import { requestRaw } from "./http";
import type {
  AdaptiveResetResponse,
  AlarmHistoryDataResponse,
  AlarmRow,
  DeviceListResponse,
  DevicePointListResponse,
  DeviceRealtimeDataResponse,
  HistoryDataResponse,
  PointRealtimeResponse
} from "@/types/monitor";

type DataQueryParams = Record<string, string | number | undefined>;

export function getPointRealtimeData(deviceId: string, pointId: string): Promise<PointRealtimeResponse> {
  return requestRaw<PointRealtimeResponse>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointId)}`, method: "GET" });
}

export function getDeviceRealtimeData(deviceId: string, pointIds?: string[]): Promise<DeviceRealtimeDataResponse> {
  return requestRaw<DeviceRealtimeDataResponse>({
    url: `/api/data/device/${encodeURIComponent(deviceId)}`,
    method: "GET",
    params: pointIds?.length ? { pointIds: pointIds.join(",") } : undefined
  });
}

export function getAllDeviceDataSummaries(): Promise<DeviceListResponse> {
  return requestRaw<DeviceListResponse>({ url: "/api/data/devices", method: "GET" });
}

export function getDevicePointSummaries(deviceId: string): Promise<DevicePointListResponse> {
  return requestRaw<DevicePointListResponse>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/points`, method: "GET" });
}

export function resetAdaptiveConfig(deviceId: string): Promise<AdaptiveResetResponse> {
  return requestRaw<AdaptiveResetResponse>({ url: `/api/data/device/${encodeURIComponent(deviceId)}/reset-adaptive`, method: "POST" });
}

export function getPointHistory(deviceId: string, pointId: string, params: DataQueryParams = {}): Promise<HistoryDataResponse> {
  return requestRaw<HistoryDataResponse>({ url: `/api/data/history/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointId)}`, method: "GET", params });
}

export function getRecentAlarms(params: DataQueryParams = {}): Promise<AlarmHistoryDataResponse> {
  return requestRaw<AlarmHistoryDataResponse>({ url: "/api/data/history/alarms", method: "GET", params });
}

export function getDeviceAlarmHistory(deviceId: string, params: DataQueryParams = {}): Promise<AlarmHistoryDataResponse> {
  return requestRaw<AlarmHistoryDataResponse>({ url: `/api/data/history/device/${encodeURIComponent(deviceId)}/alarms`, method: "GET", params });
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
