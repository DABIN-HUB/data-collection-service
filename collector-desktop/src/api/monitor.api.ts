import { requestRaw } from "./http";
import type {
  CacheMetricsSnapshot,
  CloudReportMetricsResponse,
  CollectorMetrics,
  ConsoleRuntimeStatusSnapshot,
  DeviceStatusSnapshot,
  ExceptionStatsSnapshot,
  PerformanceStatsSnapshot,
  StorageMetricsSnapshot,
  SystemResourceSnapshot
} from "@/types/monitor";

export function getRuntimeStatus(): Promise<ConsoleRuntimeStatusSnapshot> {
  return requestRaw<ConsoleRuntimeStatusSnapshot>({ url: "/monitor/runtime", method: "GET" });
}

export function getCacheMetrics(): Promise<CacheMetricsSnapshot> {
  return requestRaw<CacheMetricsSnapshot>({ url: "/monitor/cache", method: "GET" });
}

export function getDeviceConnectionMetrics(): Promise<DeviceStatusSnapshot> {
  return requestRaw<DeviceStatusSnapshot>({ url: "/monitor/devices", method: "GET" });
}

export function getCollectorPerformance(): Promise<CollectorMetrics[]> {
  return requestRaw<CollectorMetrics[]>({ url: "/monitor/performance", method: "GET" });
}

export function getSystemResources(): Promise<SystemResourceSnapshot> {
  return requestRaw<SystemResourceSnapshot>({ url: "/monitor/system", method: "GET" });
}

export function getExceptionStats(): Promise<ExceptionStatsSnapshot> {
  return requestRaw<ExceptionStatsSnapshot>({ url: "/monitor/errors", method: "GET" });
}

export function getCloudReportMetrics(): Promise<CloudReportMetricsResponse> {
  return requestRaw<CloudReportMetricsResponse>({ url: "/monitor/report", method: "GET" });
}

export function getStorageMetrics(): Promise<StorageMetricsSnapshot> {
  return requestRaw<StorageMetricsSnapshot>({ url: "/monitor/storage", method: "GET" });
}

export function getPerformanceDetail(): Promise<PerformanceStatsSnapshot> {
  return requestRaw<PerformanceStatsSnapshot>({ url: "/monitor/perf/detail", method: "GET" });
}
