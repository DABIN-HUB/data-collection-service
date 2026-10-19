import type { DiagnosticRow, ResourceSummary } from "@/features/diagnostic/utils/diagnostic-utils";

// 仅供诊断页展示；不缓存领域数据、不访问接口、不改变共享 helper 的阈值。
export type DiagnosticSourceKey = "runtimeStatus" | "systemResource" | "reportMetrics" | "configSummary" | "cacheMetrics" | "deviceConnectionMetrics" | "collectorPerformance" | "exceptionStats" | "storageMetrics" | "performanceDetail" | "pipelineMetrics";
export interface DiagnosticSourceMeta {
  label: string;
  lastAttemptAt: number | null;
  lastSuccessAt: number | null;
  failed: boolean;
}
export type DiagnosticSources = Record<DiagnosticSourceKey, DiagnosticSourceMeta>;
export type DiagnosticSourceState = "unknown" | "loading" | "ready" | "degraded" | "error";

export function createDiagnosticSources(): DiagnosticSources {
  const labels: Record<DiagnosticSourceKey, string> = {
    runtimeStatus: "运行状态接口", systemResource: "系统资源接口", reportMetrics: "云端上报接口",
    configSummary: "配置摘要接口", cacheMetrics: "缓存服务接口", deviceConnectionMetrics: "设备连接接口",
    collectorPerformance: "采集性能接口", exceptionStats: "异常统计接口", storageMetrics: "历史存储接口",
    performanceDetail: "性能详情接口", pipelineMetrics: "管线背压接口"
  };
  return Object.fromEntries(Object.entries(labels).map(([key, label]) => [key, {
    label, lastAttemptAt: null, lastSuccessAt: null, failed: false
  }])) as DiagnosticSources;
}

export function diagnosticSourceState(source: DiagnosticSourceMeta, loading: boolean): DiagnosticSourceState {
  if (loading && source.lastAttemptAt) return "loading";
  if (source.failed) return source.lastSuccessAt ? "degraded" : "error";
  return source.lastSuccessAt ? "ready" : "unknown";
}

export function diagnosticSourceDetail(source: DiagnosticSourceMeta): string {
  if (source.failed) return source.lastSuccessAt ? "本次读取失败；保留上次成功数据，请重试诊断。" : "尚未成功读取；请检查接口访问权限或连接后重试诊断。";
  return source.lastSuccessAt ? "成功读取仅代表此来源，缺失字段仍为未知。" : "尚未成功读取；可运行完整诊断。";
}

export function diagnosticSourceStamp(source: DiagnosticSourceMeta): string {
  if (!source.lastSuccessAt) return `${source.label} · 尚未成功读取${source.failed ? " · 本次读取失败" : ""}`;
  return `${source.label} · ${source.failed ? "上次成功数据" : "最后成功"} ${new Date(source.lastSuccessAt).toLocaleString("zh-CN", { hour12: false })}${source.failed ? " · 本次读取失败" : ""}`;
}

export function diagnosticRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function diagnosticData(value: unknown): Record<string, unknown> {
  const record = diagnosticRecord(value);
  const data = diagnosticRecord(record.data);
  return Object.keys(data).length ? data : record;
}

export function diagnosticField(value: unknown, keys: string[]): unknown {
  const record = diagnosticRecord(value);
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(record, key) && record[key] !== null && record[key] !== undefined) return record[key];
  }
  return undefined;
}

export function diagnosticNumber(value: unknown): number | null {
  if (typeof value !== "number" && (typeof value !== "string" || !value.trim())) return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

export function hasDiagnosticNumber(value: unknown, keys: string[]): boolean {
  return diagnosticNumber(diagnosticField(value, keys)) !== null;
}

const healthyStates = ["OK", "UP", "ONLINE", "SUCCESS", "HEALTHY"];
const failedStates = ["ERROR", "DOWN", "FAIL", "FAILED", "DANGER"];
const warningStates = ["WARN", "WARNING", "DEGRADED", "UNAVAILABLE", "OFFLINE"];

export function diagnosticStatus(value: unknown): { status: "正常" | "警告" | "异常" | "未知" | "未启用"; tone: string; text: string } {
  const code = typeof value === "string" ? value.toUpperCase() : "";
  if (healthyStates.includes(code)) return { status: "正常", tone: "is-online", text: `正常（${code}）` };
  if (failedStates.includes(code)) return { status: "异常", tone: "is-error", text: `异常（${code}）` };
  if (warningStates.includes(code)) return { status: "警告", tone: "is-warning", text: `存在风险（${code}）` };
  if (code === "DISABLED") return { status: "未启用", tone: "is-dim", text: "未启用（DISABLED）" };
  return { status: "未知", tone: "is-unknown", text: "未知" };
}

export interface DiagnosticDisplayRow extends Omit<DiagnosticRow, "status"> {
  id: string;
  status: "正常" | "警告" | "异常" | "未知" | "未启用";
  sourceKeys: DiagnosticSourceKey[];
  sourceText: string;
  reason: string;
  retained: boolean;
  readFailed: boolean;
}

interface DiagnosticDisplayInput {
  rows: DiagnosticRow[];
  sources: DiagnosticSources;
  values: Record<DiagnosticSourceKey, unknown>;
  resourceSummary: ResourceSummary;
}

const rateKeys = ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"];
const queueKeys = ["queuedTasks", "queueSize", "collectQueueSize", "processQueueSize"];
const rejectedKeys = ["rejectedTasks", "rejectedCount", "batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"];

export function buildDisplayDiagnosticRows(input: DiagnosticDisplayInput): DiagnosticDisplayRow[] {
  const { values, sources } = input;
  const known = (key: DiagnosticSourceKey, keys: string[]) => Boolean(sources[key].lastSuccessAt) && hasDiagnosticNumber(values[key], keys);
  const mapped = input.rows.map((original, index): DiagnosticDisplayRow => {
    let sourceKeys: DiagnosticSourceKey[] = [];
    let reason = "";
    let available = false;
    let row: Omit<DiagnosticRow, "status"> & { status: DiagnosticDisplayRow["status"] } = { ...original };
    switch (original.name) {
      case "应用服务": {
        sourceKeys = ["runtimeStatus"];
        const state = diagnosticStatus(diagnosticField(values.runtimeStatus, ["level", "status", "state"]));
        available = Boolean(sources.runtimeStatus.lastSuccessAt) && state.status !== "未知";
        row = { ...row, status: state.status, tone: state.tone, current: state.text };
        row.suggestion = state.status === "正常" || state.status === "未启用" ? "核对运行状态明细" : "核对运行状态与应用日志，必要时重试完整诊断";
        reason = `运行状态接口返回${state.text}；客户端初始化完成不代表服务健康。`;
        break;
      }
      case "设备连接": {
        sourceKeys = ["deviceConnectionMetrics"];
        available = known("deviceConnectionMetrics", ["expectedConnections", "totalConnections", "deviceCount"]) && known("deviceConnectionMetrics", ["activeConnections", "connectedCount", "onlineCount"]);
        reason = original.status === "警告" ? "实际活跃连接少于期望连接，存在缺失连接。" : "活跃与期望连接计数来自设备连接接口；连接不等同采集健康。";
        break;
      }
      case "缓存服务": {
        const useCache = diagnosticField(values.cacheMetrics, rateKeys) !== undefined;
        sourceKeys = useCache ? ["cacheMetrics"] : diagnosticField(values.runtimeStatus, rateKeys) !== undefined ? ["runtimeStatus"] : ["cacheMetrics", "runtimeStatus"];
        available = sourceKeys.length === 1 && known(sourceKeys[0], rateKeys);
        reason = original.status === "警告" ? "已读取的缓存命中率低于现有诊断阈值。" : "缓存命中率来自成功读取的数值字段。";
        break;
      }
      case "线程池拒绝": {
        const perf = diagnosticRecord(values.performanceDetail);
        const pools = Object.values(diagnosticRecord(diagnosticRecord(values.systemResource).threadPools));
        // 跟踪共享摘要的实际对象回退顺序；可用性另判，不用队列来源认证累计值。
        const summaryNumber = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : 0;
        const poolMax = pools.reduce<number>((sum, pool) => sum + summaryNumber(diagnosticRecord(pool).maxPoolSize), 0);
        const executor = diagnosticRecord(diagnosticRecord(values.reportMetrics).executor);
        let summaryMax = poolMax;
        let cumulativeSource: DiagnosticSourceKey = "systemResource";
        if (summaryMax === 0 && Object.keys(executor).length > 0) {
          cumulativeSource = "reportMetrics";
          summaryMax = summaryNumber(executor.maxPoolSize);
        }
        if (summaryMax === 0 && Object.keys(perf).length > 0) cumulativeSource = "performanceDetail";
        const queueSource = input.resourceSummary.queuedTasks === "-" ? "performanceDetail" : cumulativeSource;
        const selectedCountKnown = (source: DiagnosticSourceKey, field: "queueSize" | "rejectedCount") => source === "systemResource"
          ? Boolean(sources.systemResource.lastSuccessAt) && pools.length > 0 && pools.every((pool) => hasDiagnosticNumber(pool, [field]))
          : source === "reportMetrics"
            ? Boolean(sources.reportMetrics.lastSuccessAt) && hasDiagnosticNumber(executor, [field])
            : known("performanceDetail", field === "queueSize" ? queueKeys : rejectedKeys);
        const queueKnown = selectedCountKnown(queueSource, "queueSize");
        sourceKeys = Array.from(new Set<DiagnosticSourceKey>([queueSource, cumulativeSource, "performanceDetail", "systemResource"]));
        const rejectionKnown = known("performanceDetail", rejectedKeys);
        const rejected = rejectionKnown ? diagnosticNumber(diagnosticField(perf, rejectedKeys)) : null;
        const queue = queueKnown ? diagnosticNumber(input.resourceSummary.queuedTasks === "-" ? diagnosticField(perf, queueKeys) : input.resourceSummary.queuedTasks) : null;
        const cumulativeKnown = selectedCountKnown(cumulativeSource, "rejectedCount");
        // 沿用原摘要中的累计值；仅将已证实的真实零与原来的缺值占位区分。
        const cumulativeText = cumulativeKnown ? input.resourceSummary.title.split("，JVM 线程")[0].replace("累计拒绝 - 次", "累计拒绝 0 次") : "累计拒绝 未知 次";
        const jvmThreads = known("systemResource", ["threadCount"]) ? diagnosticField(values.systemResource, ["threadCount"]) : "未知";
        available = queue !== null && rejected !== null;
        const explicitRisk = (queue ?? 0) > 0 || (rejected ?? 0) > 0;
        if (explicitRisk) available = true;
        row.current = `${cumulativeText}，JVM 线程 ${jvmThreads} 个，队列 ${queue ?? "未知"}，拒绝 ${rejected ?? "未知"}`;
        row.status = explicitRisk ? "异常" : "正常";
        row.tone = explicitRisk ? "is-error" : "is-online";
        reason = explicitRisk ? "已读取的队列或拒绝计数非零；缺失的另一个计数仍为未知。" : "队列与拒绝计数均已成功读取且为零。";
        break;
      }
      case "异常统计":
        sourceKeys = ["exceptionStats"];
        available = known("exceptionStats", ["totalExceptions", "totalCount", "exceptionCount", "errorCount"]);
        reason = original.status === "警告" ? "异常统计接口返回非零累计异常；需查看分类与日志定位。" : "累计异常计数已成功读取且为零。";
        break;
      case "历史存储":
      case "云端上报":
      case "Pipeline Backpressure": {
        const key = original.name === "历史存储" ? "storageMetrics" : original.name === "云端上报" ? "reportMetrics" : "pipelineMetrics";
        sourceKeys = [key];
        const state = diagnosticStatus(diagnosticField(values[key], ["status", "state"]));
        const disabled = Boolean(sources[key].lastSuccessAt) && diagnosticField(values[key], ["enabled"]) === false;
        available = Boolean(sources[key].lastSuccessAt) && (state.status !== "未知" || disabled);
        row = { ...row, status: disabled && state.status === "未知" ? "未启用" : state.status, tone: disabled && state.status === "未知" ? "is-dim" : state.tone, current: disabled && state.status === "未知" ? "未启用（enabled=false）" : state.text };
        if (key === "pipelineMetrics") {
          row.name = "管线背压";
          const risks = diagnosticField(values[key], ["risks"]);
          row.current += `，风险 ${sources[key].lastSuccessAt && Array.isArray(risks) ? `${risks.length} 条` : "未知"}`;
        }
        reason = `${sources[key].label}返回明确状态；请查看${key === "pipelineMetrics" ? "管线阶段、积压与线程池明细" : key === "storageMetrics" ? "历史存储配置与状态" : "云端处理器、可靠发件箱与确认状态"}。`;
        break;
      }
    }
    const readFailed = sourceKeys.some((key) => sources[key].failed);
    if (!available) {
      row = { ...row, status: "未知", tone: "is-unknown", current: original.name === "线程池拒绝" ? row.current : "未知" };
      reason = readFailed ? "对应来源本次读取失败；业务状态未知，请先恢复读取，不以失败补成默认零值。" : sourceKeys.some((key) => !sources[key].lastSuccessAt) ? "对应来源尚未成功读取，不能判断业务健康或填入默认零值。" : "成功响应缺少所需字段或返回未知值，不能判断业务健康。";
      row.suggestion = "检查接口访问权限或连接后，重试完整诊断";
    }
    if (row.name === "管线背压") row.suggestion = "查看管线队列、积压、死信与遥测线程池压力；未知时重试完整诊断";
    const retained = sourceKeys.some((key) => sources[key].failed && Boolean(sources[key].lastSuccessAt));
    if (retained && (row.status === "正常" || row.status === "未启用")) {
      row.status = "警告";
      row.tone = "is-warning";
    }
    if (retained) reason += " 本次相关来源读取失败，显示上次成功数据；请重试诊断。";
    if (row.status === "警告" && !row.tone) row.tone = "is-warning";
    return { ...row, id: `diagnostic-row-${index}`, sourceKeys, sourceText: sourceKeys.map((key) => diagnosticSourceStamp(sources[key])).join("；"), reason, retained, readFailed };
  });
  const rank = { 异常: 0, 警告: 1, 未知: 2, 正常: 3, 未启用: 3 };
  const displayRank = (row: DiagnosticDisplayRow) => row.readFailed ? 0 : rank[row.status];
  return mapped.map((row, index) => ({ row, index })).sort((left, right) => displayRank(left.row) - displayRank(right.row) || left.index - right.index).map(({ row }) => row);
}