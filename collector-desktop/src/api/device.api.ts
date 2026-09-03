import { requestApiData, requestEnvelope } from "./http";
import type { ApiResult } from "@/types/api";
import type {
  ConfigDeviceListResponse,
  DeviceRuntimeSnapshot,
  DeviceStatisticsResponse,
  DeviceStatusResponse
} from "@/types/device";

interface DeviceRunningResponse extends ApiResult<null> {
  running?: boolean;
}

export function getConfigDevices(): Promise<ConfigDeviceListResponse> {
  return requestApiData<ConfigDeviceListResponse>({ url: "/api/config/devices", method: "GET" });
}

export function startDevice(deviceId: string): Promise<ApiResult<null>> {
  return requestEnvelope<null>({ url: `/api/device/${encodeURIComponent(deviceId)}/start`, method: "POST" });
}

export function startLocalDevice(deviceId: string): Promise<ApiResult<null>> {
  return requestEnvelope<null>({ url: `/api/device/${encodeURIComponent(deviceId)}/start-local`, method: "POST" });
}

export function stopDevice(deviceId: string): Promise<ApiResult<null>> {
  return requestEnvelope<null>({ url: `/api/device/${encodeURIComponent(deviceId)}/stop`, method: "POST" });
}

export function reloadDevices(): Promise<ApiResult<null>> {
  return requestEnvelope<null>({ url: "/api/device/reload", method: "POST" });
}

export function getDeviceStatus(deviceId: string): Promise<DeviceStatusResponse> {
  return requestApiData<DeviceStatusResponse>({ url: `/api/device/${encodeURIComponent(deviceId)}/status`, method: "GET" });
}

export function getAllDeviceStatistics(): Promise<Record<string, DeviceStatisticsResponse>> {
  return requestApiData<Record<string, DeviceStatisticsResponse>>({ url: "/api/device/statistics", method: "GET" });
}

export function getRunningDevices(): Promise<string[]> {
  return requestApiData<string[]>({ url: "/api/device/running", method: "GET" });
}

export function getDeviceRuntime(): Promise<DeviceRuntimeSnapshot[]> {
  return requestApiData<DeviceRuntimeSnapshot[]>({ url: "/api/device/runtime", method: "GET" });
}

export async function isDeviceRunning(deviceId: string): Promise<boolean> {
  const response = await requestEnvelope<null>({ url: `/api/device/${encodeURIComponent(deviceId)}/running`, method: "GET" }) as DeviceRunningResponse;
  return response.running === true;
}
