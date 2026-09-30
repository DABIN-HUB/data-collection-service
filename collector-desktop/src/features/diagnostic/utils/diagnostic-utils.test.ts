import { describe, expect, it } from "vitest";

import {
  buildDiagnosticAdvice,
  buildDiagnosticCards,
  buildDiagnosticExportWarning,
  buildDiagnosticPartialWarning,
  buildDiagnosticRaw,
  buildDiagnosticRows,
  buildDiagnosticRuntimeSummary,
  buildResourceSummary,
  hasDiagnosticData
} from "./diagnostic-utils";

describe("diagnostic-utils", () => {
  it("根据诊断结果给出建议", () => {
    expect(buildDiagnosticAdvice({ cache: { status: "ERROR" }, devices: { status: "OK" } })).toContain("缓存模块异常");
    expect(buildDiagnosticAdvice({ health: { status: "UP" } })).toContain("暂无明显异常");
  });

  it("汇总线程池资源并支持性能详情兜底", () => {
    expect(buildResourceSummary({
      systemResource: { threadCount: 18 },
      reportMetrics: {},
      performanceDetail: { activeThreads: 2, maxThreads: 8, queuedTasks: 3, rejectedTasks: 1 }
    })).toEqual({
      activeThreads: "2",
      maxThreads: "8",
      queuedTasks: "3",
      threadUsage: "25%",
      title: "累计拒绝 1 次，JVM 线程 18 个"
    });
  });

  it("构建诊断摘要卡片", () => {
    expect(buildDiagnosticCards({
      systemResource: { uptimeMillis: 125000 },
      configSummary: { cacheStats: { deviceCount: 2, pointCount: 6 } },
      deviceConnectionMetrics: { activeConnections: 1 },
      cacheMetrics: { totalHitRate: 0.92 },
      runtimeStatus: {},
      exceptionStats: { totalExceptions: 4 },
      pipelineMetrics: { status: "HEALTHY" },
      devices: [{ deviceId: "dev-1" }, { deviceId: "dev-2" }],
      onlineCount: 1,
      totalPointCount: 5
    })).toEqual([
      { label: "系统运行时间", value: "2分钟" },
      { label: "设备配置总数", value: "2 台" },
      { label: "点位总数", value: "6 个" },
      { label: "活跃连接", value: "1 个" },
      { label: "缓存命中率", value: "92%" },
      { label: "异常统计", value: "4 次" }
    ]);
  });

  it("构建诊断项列表并标记风险", () => {
    const rows = buildDiagnosticRows({
      appInitialized: true,
      systemStatusText: "服务可用",
      resourceSummary: { activeThreads: "1", maxThreads: "4", queuedTasks: "2", threadUsage: "25%", title: "累计拒绝 - 次，JVM 线程 10 个" },
      runtimeStatus: {},
      cacheMetrics: { totalHitRate: 0.5 },
      deviceConnectionMetrics: { expectedConnections: 2, activeConnections: 1 },
      performanceDetail: { rejectedCount: 1 },
      storageMetrics: { status: "DISABLED" },
      exceptionStats: { totalExceptions: 3 },
      reportMetrics: { status: "ERROR" },
      pipelineMetrics: { status: "DANGER", risks: ["EXECUTOR_QUEUE_HIGH"] },
      devices: [{ deviceId: "dev-1" }, { deviceId: "dev-2" }],
      onlineCount: 1
    });

    expect(rows).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: "应用服务", status: "正常", tone: "is-online" }),
      expect.objectContaining({ name: "设备连接", status: "警告", current: "1/2" }),
      expect.objectContaining({ name: "缓存服务", status: "警告", current: "50%" }),
      expect.objectContaining({ name: "线程池拒绝", status: "异常", tone: "is-error" }),
      expect.objectContaining({ name: "云端上报", status: "警告", current: "异常" }),
      expect.objectContaining({ name: "Pipeline Backpressure", status: "异常", current: "异常，风险 1 条" })
    ]));
  });

  it("构建原始诊断 JSON 和运行摘要", () => {
    const raw = buildDiagnosticRaw({
      runtimeStatus: { status: "UP" },
      systemResource: { threadCount: 8 },
      deviceConnectionMetrics: { activeConnections: 1 },
      cacheMetrics: { totalHitRate: 1 },
      collectorPerformance: { qps: 3 },
      performanceDetail: { rejectedCount: 0 },
      exceptionStats: { totalCount: 0 },
      storageMetrics: { status: "UP" },
      reportMetrics: { status: "UP" },
      pipelineMetrics: { status: "HEALTHY" },
      configSummary: { cacheStats: { deviceCount: 1 } }
    });

    expect(raw).toMatchObject({ runtime: { status: "UP" }, report: { status: "UP" }, pipeline: { status: "HEALTHY" }, summary: { cacheStats: { deviceCount: 1 } } });
    expect(buildDiagnosticRuntimeSummary({ devices: [{ status: "ONLINE" }, { status: "ERROR" }], onlineCount: 1, reportMetrics: raw.report })).toEqual({
      totalDevices: 2,
      onlineCount: 1,
      riskDevices: 1,
      reportState: "已加载"
    });
    expect(hasDiagnosticData({ status: "UP" })).toBe(true);
    expect(hasDiagnosticData({})).toBe(false);
  });

  it("optional sample 失败生成 degraded export warning", () => {
    expect(buildDiagnosticExportWarning([
      { label: "最近告警", value: [], failed: true },
      { label: "最近日志", value: [{ message: "ok" }], failed: false }
    ])).toBe("部分诊断样本不可用：最近告警");
    expect(buildDiagnosticExportWarning([
      { label: "最近告警", value: [], failed: false }
    ])).toBe("");
    expect(buildDiagnosticPartialWarning(["Pipeline", "Pipeline", "异常统计"])).toBe("部分诊断数据不可用：Pipeline、异常统计");
  });
});
