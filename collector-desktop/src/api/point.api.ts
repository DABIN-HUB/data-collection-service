import { request } from "./http";
import type { DataPoint, DevicePointConfigResponse } from "@/types/point";

export function getDevicePointConfig(deviceId: string, includeAdaptive = true): Promise<DevicePointConfigResponse> {
  return request<DevicePointConfigResponse>({
    url: `/api/config/device/${encodeURIComponent(deviceId)}/points`,
    method: "GET",
    params: { includeAdaptive }
  });
}

export function saveDevicePointConfig(deviceId: string, points: DataPoint[]): Promise<unknown> {
  return request<unknown>({
    url: `/api/config/device/${encodeURIComponent(deviceId)}/points`,
    method: "PUT",
    data: points
  });
}
