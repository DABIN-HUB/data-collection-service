import { request } from "./http";

export function writeDevicePoint(deviceId: string, pointRef: string, payload: unknown): Promise<unknown> {
  return request<unknown>({
    url: `/api/control/device/${encodeURIComponent(deviceId)}/point/${encodeURIComponent(pointRef)}`,
    method: "POST",
    data: payload
  });
}

export function writeDevicePoints(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/control/device/${encodeURIComponent(deviceId)}/points`, method: "POST", data: payload });
}

export function executeDeviceCommand(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/control/device/${encodeURIComponent(deviceId)}/command`, method: "POST", data: payload });
}
