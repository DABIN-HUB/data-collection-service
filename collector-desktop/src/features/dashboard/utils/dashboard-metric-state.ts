export type DashboardMetricKey =
  | "devices"
  | "alarms"
  | "report"
  | "runtime"
  | "systemResource"
  | "cache"
  | "storage"
  | "performance";

export type DashboardMetricStatus = "idle" | "loading" | "success" | "error";

export interface DashboardMetricState {
  status: DashboardMetricStatus;
  error: string;
  lastSuccessAt: number | null;
}

export const DASHBOARD_METRIC_LABELS: Record<DashboardMetricKey, string> = {
  devices: "设备列表",
  alarms: "最近告警",
  report: "云上报",
  runtime: "运行状态",
  systemResource: "系统资源",
  cache: "缓存指标",
  storage: "历史存储",
  performance: "性能详情"
};

export function createDashboardMetricState(): DashboardMetricState {
  return {
    status: "idle",
    error: "",
    lastSuccessAt: null
  };
}

export function markDashboardMetricLoading(state: DashboardMetricState): void {
  state.status = "loading";
  state.error = "";
}

export function markDashboardMetricSuccess(state: DashboardMetricState, successAt = Date.now()): void {
  state.status = "success";
  state.error = "";
  state.lastSuccessAt = successAt;
}

export function markDashboardMetricFailure(state: DashboardMetricState, error: string): void {
  state.status = "error";
  state.error = error;
}

export function isDashboardMetricStale(state: DashboardMetricState): boolean {
  return state.status === "error" && state.lastSuccessAt !== null;
}

export function isDashboardMetricUnavailable(state: DashboardMetricState): boolean {
  return state.status === "error" && state.lastSuccessAt === null;
}

export interface DashboardMetricRunResult {
  key: DashboardMetricKey;
  status: "success" | "error" | "stale";
}

export interface RunDashboardMetricInput<T> {
  key: DashboardMetricKey;
  state: DashboardMetricState;
  loader: () => Promise<T>;
  commit: (value: T) => void;
  isLatest: () => boolean;
  now?: () => number;
}

export async function runDashboardMetric<T>(input: RunDashboardMetricInput<T>): Promise<DashboardMetricRunResult> {
  if (!input.isLatest()) {
    return { key: input.key, status: "stale" };
  }
  markDashboardMetricLoading(input.state);
  try {
    const value = await input.loader();
    if (!input.isLatest()) {
      return { key: input.key, status: "stale" };
    }
    input.commit(value);
    markDashboardMetricSuccess(input.state, input.now?.() ?? Date.now());
    return { key: input.key, status: "success" };
  } catch (reason) {
    if (!input.isLatest()) {
      return { key: input.key, status: "stale" };
    }
    markDashboardMetricFailure(input.state, normalizeDashboardError(reason) || "数据加载失败");
    return { key: input.key, status: "error" };
  }
}

export function buildDashboardPartialWarning(failedKeys: DashboardMetricKey[]): string {
  if (failedKeys.length === 0) {
    return "";
  }
  const labels = failedKeys.map((key) => DASHBOARD_METRIC_LABELS[key]);
  return `部分总览数据刷新失败：${labels.join("、")}`;
}

export function buildDashboardFatalError(reason: unknown): string {
  const detail = normalizeDashboardError(reason);
  return detail ? `总览数据刷新失败：${detail}` : "总览数据刷新失败";
}

export function buildDashboardInitializeError(reason: unknown): string {
  const detail = normalizeDashboardError(reason);
  return detail ? `应用初始化失败：${detail}` : "应用初始化失败";
}

export function normalizeDashboardError(reason: unknown): string {
  if (reason instanceof Error) {
    return reason.message.trim();
  }
  if (typeof reason === "string") {
    return reason.trim();
  }
  if (reason === null || reason === undefined) {
    return "";
  }
  return String(reason).trim();
}
