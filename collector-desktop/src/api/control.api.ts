import { requestApiData } from "./http";

import type {
  BatchPointWriteResponse,
  DeviceCommandRequest,
  DeviceCommandResponse,
  PointWriteRequest,
  PointWriteResultResponse
} from "@/types/control";

export function writeDevicePoint(deviceId: string, pointRef: string, payload: PointWriteRequest): Promise<PointWriteResultResponse> {
  return requestApiData<PointWriteResultResponse>({
    url: `/api/control/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointRef)}`,
    method: "POST",
    data: payload
  });
}

export function writeDevicePoints(deviceId: string, payload: PointWriteRequest): Promise<BatchPointWriteResponse> {
  return requestApiData<BatchPointWriteResponse>({
    url: `/api/control/device/${encodeURIComponent(deviceId)}/points`,
    method: "POST",
    data: payload
  });
}

export function executeDeviceCommand(deviceId: string, payload: DeviceCommandRequest): Promise<DeviceCommandResponse> {
  return requestApiData<DeviceCommandResponse>({
    url: `/api/control/device/${encodeURIComponent(deviceId)}/command`,
    method: "POST",
    data: payload
  });
}
