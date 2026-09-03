import { requestApiData, requestEnvelope } from "./http";
import type { ApiResult } from "@/types/api";
import type {
  ConfigDiffResponse,
  ConfigExportResponse,
  ConfigImportRequest,
  ConfigImportResult,
  ConfigSummaryResponse,
  ConfigSyncStatusResponse,
  DeviceConfigDetailResponse,
  DeviceConnection,
  DeviceConnectionConfigResponse,
  DeviceIdResponse,
  LocalDeviceConfigRequest,
  LocalDeviceConfigResponse
} from "@/types/config";
import type { DeviceInfo, ConfigDeviceListResponse } from "@/types/device";
import type { DataPoint, DevicePointConfigResponse } from "@/types/point";

export function getConfigSummary(): Promise<ConfigSummaryResponse> {
  return requestApiData<ConfigSummaryResponse>({ url: "/api/config/summary", method: "GET" });
}

export function getConfigDevices(): Promise<ConfigDeviceListResponse> {
  return requestApiData<ConfigDeviceListResponse>({ url: "/api/config/devices", method: "GET" });
}

export function createLocalDevice(payload: LocalDeviceConfigRequest): Promise<LocalDeviceConfigResponse> {
  return requestApiData<LocalDeviceConfigResponse>({ url: "/api/config/local/devices", method: "POST", data: payload });
}

export function getLocalDevice(deviceId: string): Promise<LocalDeviceConfigResponse> {
  return requestApiData<LocalDeviceConfigResponse>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "GET" });
}

export function updateLocalDevice(deviceId: string, payload: LocalDeviceConfigRequest): Promise<LocalDeviceConfigResponse> {
  return requestApiData<LocalDeviceConfigResponse>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "PUT", data: payload });
}

export function deleteLocalDevice(deviceId: string): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/local/device/${encodeURIComponent(deviceId)}`, method: "DELETE" });
}

export function getDeviceConfig(deviceId: string): Promise<DeviceConfigDetailResponse> {
  return requestApiData<DeviceConfigDetailResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}`, method: "GET" });
}

export function updateDeviceConfig(deviceId: string, payload: DeviceInfo): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}`, method: "PUT", data: payload });
}

export function getDevicePointsConfig(deviceId: string, includeAdaptive = true): Promise<DevicePointConfigResponse> {
  return requestApiData<DevicePointConfigResponse>({
    url: `/api/config/device/${encodeURIComponent(deviceId)}/points`,
    method: "GET",
    params: { includeAdaptive }
  });
}

export function updateDevicePointsConfig(deviceId: string, points: DataPoint[]): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/points`, method: "PUT", data: points });
}

export function getDeviceConnection(deviceId: string): Promise<DeviceConnectionConfigResponse> {
  return requestApiData<DeviceConnectionConfigResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/connection`, method: "GET" });
}

export function updateDeviceConnection(deviceId: string, payload: DeviceConnection): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/connection`, method: "PUT", data: payload });
}

export function getDeviceDiff(deviceId: string): Promise<ConfigDiffResponse> {
  return requestApiData<ConfigDiffResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/diff`, method: "GET" });
}

export function refreshDeviceConfig(deviceId: string): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/refresh`, method: "POST" });
}

export function clearDeviceConfig(deviceId: string): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/device/${encodeURIComponent(deviceId)}/clear`, method: "POST" });
}

export function triggerFullConfigSync(): Promise<ApiResult<null>> {
  return requestEnvelope<null>({ url: "/api/config/sync", method: "POST" });
}

export function triggerPartialConfigSync(type: string, deviceId?: string): Promise<DeviceIdResponse> {
  return requestApiData<DeviceIdResponse>({ url: `/api/config/sync/${encodeURIComponent(type)}`, method: "POST", params: { deviceId } });
}

export function getConfigSyncStatus(): Promise<ConfigSyncStatusResponse> {
  return requestApiData<ConfigSyncStatusResponse>({ url: "/api/config/sync/status", method: "GET" });
}

export function exportConfigs(): Promise<ConfigExportResponse> {
  return requestApiData<ConfigExportResponse>({ url: "/api/config/export", method: "GET" });
}

export function importConfigs(payload: ConfigImportRequest): Promise<ConfigImportResult> {
  return requestApiData<ConfigImportResult>({ url: "/api/config/import", method: "POST", data: payload });
}
