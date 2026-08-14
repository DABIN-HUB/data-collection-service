import { request } from "./http";

export function getShadow(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/shadow/${encodeURIComponent(deviceId)}`, method: "GET" });
}

export function getShadowDelta(deviceId: string): Promise<unknown> {
  return request<unknown>({ url: `/api/shadow/${encodeURIComponent(deviceId)}/delta`, method: "GET" });
}

export function getShadowHistory(deviceId: string, limit = 50): Promise<unknown> {
  return request<unknown>({ url: `/api/shadow/${encodeURIComponent(deviceId)}/history`, method: "GET", params: { limit } });
}

export function updateShadowDesired(deviceId: string, payload: unknown): Promise<unknown> {
  return request<unknown>({ url: `/api/shadow/${encodeURIComponent(deviceId)}/desired`, method: "POST", data: payload });
}

export function clearShadowDesired(deviceId: string, fields?: string[]): Promise<unknown> {
  return request<unknown>({
    url: `/api/shadow/${encodeURIComponent(deviceId)}/desired`,
    method: "DELETE",
    params: fields?.length ? { fields: fields.join(",") } : undefined
  });
}
