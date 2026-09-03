import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

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

const httpMocks = vi.hoisted(() => ({
  request: vi.fn(),
  requestRaw: vi.fn()
}));

vi.mock("./http", () => ({
  request: httpMocks.request,
  requestRaw: httpMocks.requestRaw
}));

import {
  getCacheMetrics,
  getCloudReportMetrics,
  getCollectorPerformance,
  getDeviceConnectionMetrics,
  getExceptionStats,
  getPerformanceDetail,
  getRuntimeStatus,
  getStorageMetrics,
  getSystemResources
} from "./monitor.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestRaw.mockReset();
});

describe("monitor.api", () => {
  it("9 个 Monitor endpoint 均走 RAW response boundary", async () => {
    const runtime: ConsoleRuntimeStatusSnapshot = { level: "OK", message: "运行正常", components: [], risks: [], generatedAt: 123 };
    const cache: CacheMetricsSnapshot = { totalReads: 10, totalWrites: 4, totalDeletes: 1, totalMisses: 2, totalAccess: 12, totalHitRate: 0.83, level1HitRate: 0.7, level2HitRate: 0.13, missRate: 0.17, levelStatistics: {}, health: {}, generatedAt: 123 };
    const devices: DeviceStatusSnapshot = { totalConnections: 3, activeConnections: 2, expectedConnections: 3, healthyDevices: 2, warningDevices: 1, dangerDevices: 0, missingConnections: ["device-3"], connections: [], generatedAt: 123 };
    const performance: CollectorMetrics[] = [{ deviceId: "device-1", protocol: "MODBUS_TCP", processedPoints: 20, pointsPerSecond: 4, successRate: 98, averageLatencyMs: 12, protocolMetrics: {}, timestamp: 123 }];
    const system: SystemResourceSnapshot = { heapUsed: 1, heapCommitted: 2, heapMax: 4, nonHeapUsed: 1, nonHeapCommitted: 2, totalPhysicalMemorySize: 16, freePhysicalMemorySize: 8, processCpuLoad: 0.1, systemCpuLoad: 0.2, threadCount: 12, daemonThreadCount: 8, outboxPendingCount: 0, outboxIsolatedCount: 0, outboxOldestMessageAgeMillis: 0, threadPools: {}, generatedAt: 123 };
    const exceptions: ExceptionStatsSnapshot = { totalExceptions: 1, byCategory: { protocol: 1 }, byDevice: { "device-1": 1 }, recent: [{ deviceId: "device-1", pointId: "point-1", category: "protocol", message: "读取失败", timestamp: 123 }], generatedAt: 123 };
    const report: CloudReportMetricsResponse = { enabled: true, status: "OK", statusText: "正常", mode: "MQTT", cloudProvider: "aliyun", supportedProtocols: ["MQTT"], handlersStatus: {}, handlersStatistics: {}, configured: { deviceCount: 1, pointCount: 2 }, executor: { type: "ThreadPoolTaskExecutor", corePoolSize: 2, maxPoolSize: 4 }, batch: { enabled: true, maxDevicesPerPack: 10 }, ack: { mode: "ASYNC", timeoutMs: 3000 }, outbox: { enabled: true, pendingCount: 0 }, payload: { profile: "default", includeQuality: "true" }, risks: [], generatedAt: 123 };
    const storage: StorageMetricsSnapshot = { enabled: false, status: "DISABLED", message: "未启用", responseTimeMs: 0, generatedAt: 123 };
    const detail: PerformanceStatsSnapshot = { timeSliceCount: 4, timeSliceIntervalMs: 250, timeSliceExecutionTimes: { 0: 10 }, overloadedSlices: {}, slowestDevices: {}, deviceStats: {}, processCpuLoad: 0.2, batchDispatchRejectedCount: 0, collectRejectedCount: 0, processRejectedCount: 0, reconnectAttemptCount: 1, reconnectSuccessCount: 1, reconnectFailureCount: 0, reconnectingDevices: 0, generatedAt: 123 };

    const endpoints = [
      { run: getRuntimeStatus, url: "/monitor/runtime", fixture: runtime },
      { run: getCacheMetrics, url: "/monitor/cache", fixture: cache },
      { run: getDeviceConnectionMetrics, url: "/monitor/devices", fixture: devices },
      { run: getCollectorPerformance, url: "/monitor/performance", fixture: performance },
      { run: getSystemResources, url: "/monitor/system", fixture: system },
      { run: getExceptionStats, url: "/monitor/errors", fixture: exceptions },
      { run: getCloudReportMetrics, url: "/monitor/report", fixture: report },
      { run: getStorageMetrics, url: "/monitor/storage", fixture: storage },
      { run: getPerformanceDetail, url: "/monitor/perf/detail", fixture: detail }
    ] as const;

    for (const endpoint of endpoints) {
      httpMocks.requestRaw.mockResolvedValueOnce(endpoint.fixture);
      await expect(endpoint.run()).resolves.toBe(endpoint.fixture);
    }

    expect(httpMocks.requestRaw.mock.calls.map(([config]) => config)).toEqual(endpoints.map((endpoint) => ({ url: endpoint.url, method: "GET" })));
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("公开真实 Monitor TypeScript 返回类型", async () => {
    httpMocks.requestRaw.mockResolvedValue({});

    expectTypeOf(await getRuntimeStatus()).toEqualTypeOf<ConsoleRuntimeStatusSnapshot>();
    expectTypeOf(await getCacheMetrics()).toEqualTypeOf<CacheMetricsSnapshot>();
    expectTypeOf(await getDeviceConnectionMetrics()).toEqualTypeOf<DeviceStatusSnapshot>();
    expectTypeOf(await getCollectorPerformance()).toEqualTypeOf<CollectorMetrics[]>();
    expectTypeOf(await getSystemResources()).toEqualTypeOf<SystemResourceSnapshot>();
    expectTypeOf(await getExceptionStats()).toEqualTypeOf<ExceptionStatsSnapshot>();
    expectTypeOf(await getCloudReportMetrics()).toEqualTypeOf<CloudReportMetricsResponse>();
    expectTypeOf(await getStorageMetrics()).toEqualTypeOf<StorageMetricsSnapshot>();
    expectTypeOf(await getPerformanceDetail()).toEqualTypeOf<PerformanceStatsSnapshot>();
  });
});
