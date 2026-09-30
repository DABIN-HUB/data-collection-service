import { requestRaw } from "./http";
import type {
  CacheMetricsSnapshot,
  CloudReportMetricsResponse,
  CollectorMetrics,
  ConsoleRuntimeStatusSnapshot,
  DeviceStatusSnapshot,
  ExceptionStatsSnapshot,
  PerformanceStatsSnapshot,
  PipelineBackpressureSnapshot,
  StorageMetricsSnapshot,
  SystemResourceSnapshot
} from "@/types/monitor";

export function getRuntimeStatus(): Promise<ConsoleRuntimeStatusSnapshot> {
  return requestRaw<ConsoleRuntimeStatusSnapshot>({ url: "/api/monitor/runtime", method: "GET" });
}

export function getCacheMetrics(): Promise<CacheMetricsSnapshot> {
  return requestRaw<CacheMetricsSnapshot>({ url: "/api/monitor/cache", method: "GET" });
}

export function getDeviceConnectionMetrics(): Promise<DeviceStatusSnapshot> {
  return requestRaw<DeviceStatusSnapshot>({ url: "/api/monitor/devices", method: "GET" });
}

export function getCollectorPerformance(): Promise<CollectorMetrics[]> {
  return requestRaw<CollectorMetrics[]>({ url: "/api/monitor/performance", method: "GET" });
}

export function getSystemResources(): Promise<SystemResourceSnapshot> {
  return requestRaw<SystemResourceSnapshot>({ url: "/api/monitor/system", method: "GET" });
}

export function getExceptionStats(): Promise<ExceptionStatsSnapshot> {
  return requestRaw<ExceptionStatsSnapshot>({ url: "/api/monitor/errors", method: "GET" });
}

export function getCloudReportMetrics(): Promise<CloudReportMetricsResponse> {
  return requestRaw<CloudReportMetricsResponse>({ url: "/api/monitor/report", method: "GET" });
}

export function getPipelineBackpressure(): Promise<PipelineBackpressureSnapshot> {
  return requestRaw<PipelineBackpressureSnapshot>({ url: "/api/monitor/pipeline", method: "GET" });
}

export function getStorageMetrics(): Promise<StorageMetricsSnapshot> {
  return requestRaw<StorageMetricsSnapshot>({ url: "/api/monitor/storage", method: "GET" });
}

export function getPerformanceDetail(): Promise<PerformanceStatsSnapshot> {
  return requestRaw<PerformanceStatsSnapshot>({ url: "/api/monitor/perf/detail", method: "GET" });
}
