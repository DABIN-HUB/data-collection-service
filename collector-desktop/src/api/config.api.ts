import { request } from "./http";
import type { DataPoint, DevicePointConfigResponse } from "@/types/point";

export function getConfigSummary(): Promise<unknown> {
  return request<unknown>({ url: "/api/config/summary", method: "GET" });
}

export function getConfigDevices(): Promise<unknown> {
  return request<unknown>({ url: "/api/config/devices", method: "GET" });
}

export function createLocalDevice(payload: unknown): Promise<unknown> {
  return request<unknown>({ url: "/api/config/local/devices", method: "POST", data: payload });
}

export function getLocalDevice(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "GET" });
}

export function updateLocalDevice(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "PUT", data: payload });
}

export function deleteLocalDevice(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "DELETE" });
}

export function getDeviceConfig(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}`, method: "GET" });
}

export function updateDeviceConfig(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}`, method: "PUT", data: payload });
}

export function getDevicePointsConfig(deviceId: string, includeAdaptive = true): Promise<DevicePointConfigResponse> {
  return request<DevicePointConfigResponse>({
    url: `/api/config/device/${encodeURIComponent(deviceId)}/points`,
    method: "GET",
    params: { includeAdaptive }
  });
}

export function updateDevicePointsConfig(deviceId: string, points: DataPoint[] | unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/points`, method: "PUT", data: points });
}

export function getDeviceConnection(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/connection`, method: "GET" });
}

export function updateDeviceConnection(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/connection`, method: "PUT", data: payload });
}

export function getDeviceDiff(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/diff`, method: "GET" });
}

export function refreshDeviceConfig(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/refresh`, method: "POST" });
}

export function clearDeviceConfig(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/clear`, method: "POST" });
}

export function triggerFullConfigSync(): Promise<unknown> {
  return request<unknown>({ url: "/api/config/sync", method: "POST" });
}

export function triggerPartialConfigSync(type: string, deviceId?: string): Promise<unknown> {
  return request<unknown>({ url: `/api/config/sync/${encodeURIComponent(type)}`, method: "POST", params: { deviceId } });
}

export function getConfigSyncStatus(): Promise<unknown> {
  return request<unknown>({ url: "/api/config/sync/status", method: "GET" });
}

export function exportConfigs(): Promise<unknown> {
  return request<unknown>({ url: "/api/config/export", method: "GET" });
}

export function importConfigs(payload: unknown): Promise<unknown> {
  return request<unknown>({ url: "/api/config/import", method: "POST", data: payload });
}
