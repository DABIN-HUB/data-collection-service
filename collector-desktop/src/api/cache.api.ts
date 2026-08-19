import { request } from "./http";

export function getCacheHealth(): Promise<unknown> {
  return request<unknown>({ url: "/api/cache/health", method: "GET" });
}

export function getCacheStats(): Promise<unknown> {
  return request<unknown>({ url: "/api/cache/stats", method: "GET" });
}
