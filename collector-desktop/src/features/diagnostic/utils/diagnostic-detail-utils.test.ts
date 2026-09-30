import { describe, expect, it } from "vitest";

import {
  buildCacheDetail,
  buildDeviceConnectionRows,
  buildExceptionDetail,
  buildLogRouteForRequestId,
  buildPerformanceDetail,
  buildPipelineDetail,
  buildStorageDetail
} from "./diagnostic-detail-utils";
import type { PipelineBackpressureSnapshot } from "@/types/monitor";

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
      otherDeviceExceptions: 7,
      otherCategoryExceptions: 2,
      recent: [{ deviceId: "devA", pointId: "p1", category: "TimeoutException", exceptionType: "SocketTimeoutException", requestId: "obs-req-1", message: "超时", timestamp: 1700000000000 }]
    })).toEqual(expect.objectContaining({
      totalText: "4 次",
      topCategories: [{ name: "TimeoutException", count: 3 }, { name: "ProtocolException", count: 1 }],
      topDevices: [{ name: "devA", count: 2 }],
      categoryOverflowText: "另有未单独跟踪分类异常 2 次",
      deviceOverflowText: "另有未单独跟踪设备异常 7 次",
      recent: [expect.objectContaining({ deviceId: "devA", pointId: "p1", category: "TimeoutException", exceptionType: "SocketTimeoutException", requestId: "obs-req-1", message: "超时" })]
    }));
  });

  it("按真实后端 PipelineBackpressureSnapshot 字段归一化 Pipeline 数值", () => {
    const backendPayload: PipelineBackpressureSnapshot = {
      status: "WARNING",
      ingress: {
        enabled: true,
        status: "WARNING",
        redisPending: 17,
        redisProcessing: 2,
        redisDeadLetter: 1,
        localPending: 7,
        localCapacity: 100,
        localUtilization: 0.07,
        rejectedTasks: 3,
        rejectedItems: 4,
        droppedItems: 5
      },
      stream: {
        enabled: true,
        status: "HEALTHY",
        bufferSize: 11,
        bufferPeak: 30,
        bufferCapacity: 100,
        bufferUtilization: 0.11,
        admissionRejected: 2,
        admissionDropped: 3,
        redisXaddFailures: 4,
        shutdownDroppedRows: 5,
        writerLoopFailures: 6
      },
      history: {
        enabled: true,
        status: "DANGER",
        redisPending: 23,
        redisProcessing: 4,
        redisDeadLetter: 2,
        localPending: 41,
        localCapacity: 500,
        localUtilization: 0.082,
        replayFailedRows: 7,
        liveFlushQueueUtilization: 0.5
      },
      cloud: {
        enabled: true,
        status: "WARNING",
        pending: 12,
        isolated: 2,
        oldestMessageAgeMillis: 35000
      },
      executors: { cache: { beanName: "telemetryCacheStageExecutor", status: "UNKNOWN", queueSize: 3, queueCapacity: 2000, queueUtilization: 0.0015, rejectedCount: 1 } },
      risks: ["HISTORY_DEAD_LETTER", "EXECUTOR_QUEUE_HIGH", "CUSTOM_RISK"]
    };
    const detail = buildPipelineDetail(backendPayload);

    expect(detail.statusText).toBe("预警");
    expect(detail.stages.map((stage) => stage.statusText)).toEqual(["预警", "正常", "危险", "预警"]);
    expect(detail.stages[0]).toEqual(expect.objectContaining({ queueText: "本地队列 7/100，Redis 积压 17", utilizationText: "7%", secondaryText: expect.stringContaining("死信 1") }));
    expect(detail.stages[1]).toEqual(expect.objectContaining({ queueText: "缓冲 11/100，峰值 30", utilizationText: "11%", secondaryText: "拒绝 2，丢弃 8，失败 10" }));
    expect(detail.stages[2]).toEqual(expect.objectContaining({ queueText: "本地队列 41/500，Redis 积压 23", utilizationText: "8%", secondaryText: expect.stringContaining("Live Flush 50%") }));
    expect(detail.stages[3]).toEqual(expect.objectContaining({ queueText: "积压 12，隔离 2，最老 35 s", utilizationText: "-" }));
    expect(detail.risks).toEqual(["历史写入存在死信积压", "线程池队列压力偏高", "CUSTOM RISK"]);
    expect(detail.executors[0]).toEqual(expect.objectContaining({ name: "cache", beanNameText: "telemetryCacheStageExecutor", statusText: "未知", queueText: "3", capacityText: "2000", utilizationText: "0%", rejectedText: "1" }));
  });

  it("Pipeline unknown 和 disabled 不把 -1 显示成 0 或 -100%", () => {
    const detail = buildPipelineDetail({
      status: "UNKNOWN",
      ingress: { enabled: true, status: "UNKNOWN", redisPending: -1, redisProcessing: -1, redisDeadLetter: -1, localPending: -1, localCapacity: -1, localUtilization: -1 },
      stream: { enabled: true, status: "UNKNOWN", bufferSize: -1, bufferCapacity: -1, bufferUtilization: -1 },
      history: { enabled: true, status: "UNKNOWN", redisPending: -1, redisProcessing: -1, redisDeadLetter: -1, localPending: -1, localCapacity: -1, localUtilization: -1, liveFlushQueueUtilization: -1 },
      cloud: { enabled: false, status: "DISABLED", pending: 0, isolated: 0, oldestMessageAgeMillis: 0 },
      executors: { cache: { beanName: "telemetryCacheStageExecutor", status: "UNKNOWN", queueSize: -1, queueCapacity: -1, queueUtilization: -1, rejectedCount: -1 } }
    } satisfies PipelineBackpressureSnapshot);

    expect(detail.stages[0].queueText).toBe("本地队列 -/-，Redis 积压 -");
    expect(detail.stages[0].utilizationText).toBe("-");
    expect(detail.stages[3]).toEqual(expect.objectContaining({ enabledText: "未启用", queueText: "未启用", utilizationText: "-" }));
    expect(detail.executors[0]).toEqual(expect.objectContaining({ capacityText: "-", utilizationText: "-" }));
  });

  it("requestId 构建日志 keyword 路由", () => {
    expect(buildLogRouteForRequestId("obs-054-request-001")).toEqual({ name: "log", query: { keyword: "obs-054-request-001" } });
    expect(buildLogRouteForRequestId(" ")).toBeNull();
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
