import { request } from "./http";
import type { ConfigDeviceListResponse, DeviceRuntimeSnapshot } from "@/types/device";

export function getConfigDevices(): Promise<ConfigDeviceListResponse> {
  return request<ConfigDeviceListResponse>({ url: "/api/config/devices", method: "GET" });
}

export function startDevice(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/device/${encodeURIComponent(deviceId)}/start`, method: "POST" });
}

export function startLocalDevice(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/device/${encodeURIComponent(deviceId)}/start-local`, method: "POST" });
}

export function stopDevice(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/device/${encodeURIComponent(deviceId)}/stop`, method: "POST" });
}

export function reloadDevices(): Promise<unknown> {
  return request<unknown>({ url: "/api/device/reload", method: "POST" });
}

export function getDeviceStatus(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/device/${encodeURIComponent(deviceId)}/status`, method: "GET" });
}

export function getAllDeviceStatistics(): Promise<unknown> {
  return request<unknown>({ url: "/api/device/statistics", method: "GET" });
}

export function getRunningDevices(): Promise<unknown[]> {
  return request<unknown[]>({ url: "/api/device/running", method: "GET" });
}

export function getDeviceRuntime(): Promise<DeviceRuntimeSnapshot[]> {
  return request<DeviceRuntimeSnapshot[]>({ url: "/api/device/runtime", method: "GET" });
}

export function isDeviceRunning(deviceId: string): Promise<boolean> {
  return request<boolean>({ url: `/api/device/${encodeURIComponent(deviceId)}/running`, method: "GET" });
}
