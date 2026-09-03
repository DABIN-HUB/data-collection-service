import { getDevicePointsConfig, updateDevicePointsConfig } from "./config.api";
import type { DeviceIdResponse } from "@/types/config";
import type { DataPoint, DevicePointConfigResponse } from "@/types/point";

export function getDevicePointConfig(deviceId: string, includeAdaptive = true): Promise<DevicePointConfigResponse> {
  return getDevicePointsConfig(deviceId, includeAdaptive);
}

export function saveDevicePointConfig(deviceId: string, points: DataPoint[]): Promise<DeviceIdResponse> {
  return updateDevicePointsConfig(deviceId, points);
}
