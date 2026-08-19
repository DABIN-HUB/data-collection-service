import { request } from "./http";

export function getRuntimeStatus(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/runtime", method: "GET" });
}

export function getCacheMetrics(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/cache", method: "GET" });
}

export function getDeviceConnectionMetrics(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/devices", method: "GET" });
}

export function getCollectorPerformance(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/performance", method: "GET" });
}

export function getSystemResources(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/system", method: "GET" });
}

export function getExceptionStats(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/errors", method: "GET" });
}

export function getCloudReportMetrics(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/report", method: "GET" });
}

export function getStorageMetrics(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/storage", method: "GET" });
}

export function getPerformanceDetail(): Promise<unknown> {
  return request<unknown>({ url: "/monitor/perf/detail", method: "GET" });
}
