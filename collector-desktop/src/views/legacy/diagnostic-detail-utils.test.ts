import { describe, expect, it } from "vitest";

import {
  buildCacheDetail,
  buildDeviceConnectionRows,
  buildExceptionDetail,
  buildPerformanceDetail,
  buildStorageDetail
} from "./diagnostic-detail-utils";

describe("diagnostic-detail-utils", () => {
  it("归一化缓存命中率和分层缓存摘要", () => {
    expect(buildCacheDetail({
      totalReads: 100,
      totalWrites: 20,
      totalMisses: 25,
      totalHitRate: 0.75,
      level1HitRate: 0.6,
      level2HitRate: 0.9,
      missRate: 0.25,
      health: { status: "WARN", message: "命中率偏低" }
    })).toEqual({
      status: "WARN",
      tone: "",
      hitRateText: "75%",
      level1Text: "60%",
      level2Text: "90%",
      missRateText: "25%",
      readWriteText: "100 / 20",
      message: "命中率偏低"
    });
  });

  it("归一化设备连接明细和缺失连接", () => {
    expect(buildDeviceConnectionRows({
      missingConnections: ["dev-2"],
      connections: [
        { deviceId: "dev-1", status: "ONLINE", connected: true, successRate: 0.98, errors: 1, bytesSent: 12, bytesReceived: 34, idleTime: 1000 },
        { deviceId: "dev-2", status: "OFFLINE", connected: false, expectedOnly: true }
      ]
    })).toEqual([
      expect.objectContaining({ deviceId: "dev-1", statusText: "ONLINE", connectedText: "已连接", successRateText: "98%", bytesText: "12 / 34" }),
      expect.objectContaining({ deviceId: "dev-2", statusText: "OFFLINE", connectedText: "未连接", successRateText: "-", expectedOnly: true, missing: true })
    ]);
  });

  it("归一化调度性能详情", () => {
    expect(buildPerformanceDetail({
      timeSliceCount: 8,
      timeSliceIntervalMs: 1000,
      overloadedSlices: { 2: 1500 },
      slowestDevices: { devA: 2345, devB: 1234 },
      batchDispatchRejectedCount: 1,
      collectRejectedCount: 2,
      processRejectedCount: 3,
      reconnectAttemptCount: 5,
      reconnectSuccessCount: 4,
      reconnectFailureCount: 1,
      reconnectingDevices: 2
    })).toEqual(expect.objectContaining({
      timeSliceText: "8 × 1000ms",
      overloadedCount: 1,
      rejectedTotal: 6,
      reconnectText: "4/5 成功，失败 1，重连中 2",
      slowestDevices: [
        { deviceId: "devA", costMs: 2345 },
        { deviceId: "devB", costMs: 1234 }
      ]
    }));
  });

  it("归一化异常统计和最近异常", () => {
    expect(buildExceptionDetail({
      totalExceptions: 4,
      byCategory: { TimeoutException: 3, ProtocolException: 1 },
      byDevice: { devA: 2 },
      recent: [{ deviceId: "devA", pointId: "p1", category: "TimeoutException", message: "超时", timestamp: 1700000000000 }]
    })).toEqual(expect.objectContaining({
      totalText: "4 次",
      topCategories: [{ name: "TimeoutException", count: 3 }, { name: "ProtocolException", count: 1 }],
      topDevices: [{ name: "devA", count: 2 }],
      recent: [expect.objectContaining({ deviceId: "devA", pointId: "p1", category: "TimeoutException", message: "超时" })]
    }));
  });

  it("归一化历史存储状态", () => {
    expect(buildStorageDetail({ enabled: false, status: "DISABLED", message: "TDengine 未启用", responseTimeMs: 0 })).toEqual({
      enabledText: "未启用",
      statusText: "未启用",
      tone: "",
      responseTimeText: "0 ms",
      message: "TDengine 未启用"
    });
  });
});
