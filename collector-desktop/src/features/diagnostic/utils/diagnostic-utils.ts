export interface DiagnosticCard {
  label: string;
  value: string;
}

export interface ResourceSummary {
  activeThreads: string;
  maxThreads: string;
  queuedTasks: string;
  threadUsage: string;
  title: string;
}

export interface DiagnosticRow {
  name: string;
  status: "正常" | "警告" | "异常";
  current: string;
  suggestion: string;
  tone: string;
}

export interface DiagnosticRawInput {
  runtimeStatus: unknown;
  systemResource: unknown;
  deviceConnectionMetrics: unknown;
  cacheMetrics: unknown;
  collectorPerformance: unknown;
  performanceDetail: unknown;
  exceptionStats: unknown;
  storageMetrics: unknown;
  reportMetrics: unknown;
  configSummary: unknown;
}

export interface ResourceSummaryInput {
  systemResource: unknown;
  reportMetrics: unknown;
  performanceDetail: unknown;
}

export interface DiagnosticCardsInput {
  systemResource: unknown;
  configSummary: unknown;
  deviceConnectionMetrics: unknown;
  cacheMetrics: unknown;
  runtimeStatus: unknown;
  exceptionStats: unknown;
  devices: Array<Record<string, unknown>>;
  onlineCount: number;
  totalPointCount: number;
}

export interface DiagnosticRowsInput {
  appInitialized: boolean;
  systemStatusText: string;
  resourceSummary: ResourceSummary;
  runtimeStatus: unknown;
  cacheMetrics: unknown;
  deviceConnectionMetrics: unknown;
  performanceDetail: unknown;
  storageMetrics: unknown;
  exceptionStats: unknown;
  reportMetrics: unknown;
  devices: Array<Record<string, unknown>>;
  onlineCount: number;
}

export interface DiagnosticRuntimeSummaryInput {
  devices: Array<Record<string, unknown>>;
  onlineCount: number;
  reportMetrics: unknown;
}

export function buildResourceSummary(input: ResourceSummaryInput): ResourceSummary {
  const resource = asRecord(input.systemResource);
  const pools = asRecord(resource.threadPools);
  let activeThreads = 0;
  let maxThreads = 0;
  let queuedTasks = 0;
  let rejectedTasks = 0;

  for (const pool of Object.values(pools)) {
    const record = asRecord(pool);
    activeThreads += numberValue(record.activeCount, 0);
    maxThreads += numberValue(record.maxPoolSize, 0);
    queuedTasks += numberValue(record.queueSize, 0);
    rejectedTasks += numberValue(record.rejectedCount, 0);
  }

  const executor = asRecord(asRecord(input.reportMetrics).executor);
  if (maxThreads === 0 && Object.keys(executor).length) {
    activeThreads = numberValue(executor.activeCount, 0);
    maxThreads = numberValue(executor.maxPoolSize, 0);
    queuedTasks = numberValue(executor.queueSize, 0);
    rejectedTasks = numberValue(executor.rejectedCount, 0);
  }

  const perf = asRecord(input.performanceDetail);
  if (maxThreads === 0 && Object.keys(perf).length) {
    activeThreads = numberValue(valueOf(perf, ["activeThreads", "activeCount", "collectActiveCount", "processActiveCount"], 0));
    maxThreads = numberValue(valueOf(perf, ["maxThreads", "maxPoolSize", "collectMaxPoolSize", "processMaxPoolSize"], 0));
    queuedTasks = numberValue(valueOf(perf, ["queuedTasks", "queueSize", "collectQueueSize", "processQueueSize"], 0));
    rejectedTasks = numberValue(valueOf(perf, ["rejectedTasks", "rejectedCount", "batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"], 0));
  }

  const usage = maxThreads > 0 ? Math.max(0, Math.min(100, Math.round((activeThreads / maxThreads) * 100))) : 0;
  return {
    activeThreads: maxThreads > 0 ? String(activeThreads) : "-",
    maxThreads: maxThreads > 0 ? String(maxThreads) : "-",
    queuedTasks: maxThreads > 0 ? String(queuedTasks) : "-",
    threadUsage: `${usage}%`,
    title: `累计拒绝 ${rejectedTasks || "-"} 次，JVM 线程 ${valueOf(resource, ["threadCount"], "-")} 个`
  };
}

export function buildDiagnosticCards(input: DiagnosticCardsInput): DiagnosticCard[] {
  const resource = asRecord(input.systemResource);
  const summary = asRecord(input.configSummary);
  const stats = asRecord(summary.cacheStats);
  const deviceMetrics = asRecord(input.deviceConnectionMetrics);
  const activeConnections = valueOf(deviceMetrics, ["activeConnections", "connectedCount", "onlineCount"], input.onlineCount);
  const cacheRate = ratioFrom(valueOf(input.cacheMetrics, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], valueOf(input.runtimeStatus, ["cacheHitRatio", "hitRatio", "cacheHitRate"], null)));
  return [
    { label: "系统运行时间", value: formatDurationMs(valueOf(resource, ["uptimeMillis", "uptime"], null)) },
    { label: "设备配置总数", value: `${valueOf(stats, ["deviceCount"], input.devices.length)} 台` },
    { label: "点位总数", value: `${valueOf(stats, ["pointCount"], input.totalPointCount)} 个` },
    { label: "活跃连接", value: `${activeConnections} 个` },
    { label: "缓存命中率", value: percentText(cacheRate) },
    { label: "异常统计", value: `${valueOf(input.exceptionStats, ["totalCount", "exceptionCount", "errorCount"], 0)} 次` }
  ];
}

export function buildDiagnosticRows(input: DiagnosticRowsInput): DiagnosticRow[] {
  const cacheRate = ratioFrom(valueOf(input.cacheMetrics, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], valueOf(input.runtimeStatus, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], null)));
  const perf = asRecord(input.performanceDetail);
  const queued = numberValue(input.resourceSummary.queuedTasks === "-" ? valueOf(perf, ["queuedTasks", "queueSize", "collectQueueSize", "processQueueSize"], 0) : input.resourceSummary.queuedTasks, 0);
  const rejected = numberValue(valueOf(perf, ["rejectedTasks", "rejectedCount", "batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"], 0), 0);
  const reportStatus = String(valueOf(input.reportMetrics, ["status", "state"], "UNKNOWN")).toUpperCase();
  const deviceMetrics = asRecord(input.deviceConnectionMetrics);
  const expectedConnections = numberValue(valueOf(deviceMetrics, ["expectedConnections", "totalConnections", "deviceCount"], input.devices.length), input.devices.length);
  const activeConnections = numberValue(valueOf(deviceMetrics, ["activeConnections", "connectedCount", "onlineCount"], input.onlineCount), input.onlineCount);
  const missing = Math.max(0, expectedConnections - activeConnections);
  const storageStatus = String(valueOf(input.storageMetrics, ["status", "state"], Object.keys(asRecord(input.storageMetrics)).length ? "UP" : "UNKNOWN")).toUpperCase();
  const storageKnown = Object.keys(asRecord(input.storageMetrics)).length > 0;
  const exceptionCount = numberValue(valueOf(input.exceptionStats, ["totalCount", "exceptionCount", "errorCount"], 0), 0);
  const rows = [
    { name: "应用服务", status: input.appInitialized ? "正常" : "异常", current: input.systemStatusText, suggestion: input.appInitialized ? "无需处理" : "检查应用健康检查明细" },
    { name: "设备连接", status: missing === 0 ? "正常" : "警告", current: `${activeConnections}/${expectedConnections}`, suggestion: "检查缺失连接和设备网络" },
    { name: "缓存服务", status: cacheRate === null || cacheRate >= 0.8 ? "正常" : "警告", current: cacheRate === null ? "指标不可用" : percentText(cacheRate), suggestion: "低命中率时检查缓存配置" },
    { name: "线程池拒绝", status: queued === 0 && rejected === 0 ? "正常" : "异常", current: `${input.resourceSummary.title}，队列 ${queued}，拒绝 ${rejected}`, suggestion: "检查队列容量、任务耗时和拒绝策略" },
    { name: "异常统计", status: exceptionCount === 0 ? "正常" : "警告", current: `${exceptionCount} 次`, suggestion: "查看异常统计明细和应用日志" },
    { name: "历史存储", status: storageKnown && ["UP", "OK", "ONLINE", "SUCCESS"].includes(storageStatus) ? "正常" : "警告", current: storageKnown ? diagnosticStatusText(storageStatus) : "指标不可用", suggestion: "检查 TDengine 或历史存储配置" },
    { name: "云端上报", status: ["UP", "ONLINE", "OK", "SUCCESS"].includes(reportStatus) ? "正常" : "警告", current: diagnosticStatusText(reportStatus), suggestion: "检查处理器、Outbox 和 ACK 状态" }
  ] satisfies Array<Omit<DiagnosticRow, "tone">>;

  return rows.map((row) => ({ ...row, tone: row.status === "正常" ? "is-online" : row.status === "异常" ? "is-error" : "" }));
}

export function buildDiagnosticRaw(input: DiagnosticRawInput): Record<string, unknown> {
  return {
    runtime: input.runtimeStatus,
    system: input.systemResource,
    devices: input.deviceConnectionMetrics,
    cache: input.cacheMetrics,
    performance: input.collectorPerformance,
    performanceDetail: input.performanceDetail,
    exceptions: input.exceptionStats,
    storage: input.storageMetrics,
    report: input.reportMetrics,
    summary: input.configSummary
  };
}

export function buildDiagnosticRuntimeSummary(input: DiagnosticRuntimeSummaryInput): Record<string, unknown> {
  return {
    totalDevices: input.devices.length,
    onlineCount: input.onlineCount,
    riskDevices: input.devices.filter((device) => ["ERROR", "OFFLINE"].includes(String(device.status || "").toUpperCase()) || Boolean(device.lastError)).length,
    reportState: hasDiagnosticData(input.reportMetrics) ? "已加载" : "未知"
  };
}

export function hasDiagnosticData(value: unknown): boolean {
  return Object.keys(asRecord(value)).length > 0;
}

export function buildDiagnosticAdvice(diagnostic: Record<string, unknown>): string[] {
  const advice: string[] = [];
  for (const [key, value] of Object.entries(diagnostic)) {
    const status = String(asRecord(value).status || "").toUpperCase();
    if (["ERROR", "DOWN", "FAIL", "FAILED"].some((flag) => status.includes(flag))) {
      advice.push(`${diagnosticName(key)}异常`);
    }
  }
  if (advice.length === 0) {
    advice.push("暂无明显异常");
  }
  return advice;
}

function diagnosticName(key: string): string {
  return {
    health: "健康检查",
    system: "系统资源",
    devices: "设备连接",
    cache: "缓存模块",
    performance: "性能指标",
    report: "云端上报",
    summary: "配置摘要"
  }[key] || key;
}

function diagnosticStatusText(status: string): string {
  return ({ OK: "正常", UP: "正常", ONLINE: "正常", SUCCESS: "正常", WARN: "存在风险", WARNING: "存在风险", ERROR: "异常", FAILED: "异常", DOWN: "异常", DISABLED: "未启用", UNKNOWN: "未知" } as Record<string, string>)[status] || status;
}

function valueOf(value: unknown, keys: string[], fallback: unknown): unknown {
  const record = asRecord(value);
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) {
      return record[key];
    }
  }
  return fallback;
}

function numberValue(value: unknown, fallback = 0): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function optionalNumber(value: unknown): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function ratioFrom(value: unknown): number | null {
  const parsed = optionalNumber(value);
  if (parsed === null) {
    return null;
  }
  const normalized = parsed > 1 && parsed <= 100 ? parsed / 100 : parsed;
  return Math.max(0, Math.min(1, normalized));
}

function percentText(value: number | null): string {
  return value === null ? "-" : `${Math.round(value * 100)}%`;
}

function formatDurationMs(value: unknown): string {
  const ms = optionalNumber(value);
  if (ms === null) {
    return "-";
  }
  const seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) {
    return `${days}天 ${hours}小时`;
  }
  if (hours > 0) {
    return `${hours}小时 ${minutes}分钟`;
  }
  if (minutes > 0) {
    return `${minutes}分钟`;
  }
  return `${seconds}秒`;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
