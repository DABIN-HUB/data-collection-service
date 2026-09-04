import { describe, expect, it } from "vitest";

import { createDashboardRefreshCycle } from "./dashboard-request-lifecycle";
import {
  buildDashboardPartialWarning,
  createDashboardMetricState,
  isDashboardMetricStale,
  isDashboardMetricUnavailable,
  markDashboardMetricFailure,
  markDashboardMetricLoading,
  markDashboardMetricSuccess,
  runDashboardMetric,
  DASHBOARD_METRIC_LABELS,
  type DashboardMetricKey,
  type DashboardMetricRunResult,
  type DashboardMetricState
} from "./dashboard-metric-state";

const DASHBOARD_KEYS: DashboardMetricKey[] = ["devices", "alarms", "report", "runtime", "systemResource", "cache", "storage", "performance"];

type SourceValues = Record<DashboardMetricKey, string | null>;
type SourceRequests = Partial<Record<DashboardMetricKey, Promise<string>>>;

interface DashboardHarnessState {
  loading: boolean;
  lastRefresh: string | null;
  dashboardError: string;
  dashboardPartialWarning: string;
  values: SourceValues;
  metrics: Record<DashboardMetricKey, DashboardMetricState>;
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

function createMetricRecord(): Record<DashboardMetricKey, DashboardMetricState> {
  return Object.fromEntries(DASHBOARD_KEYS.map((key) => [key, createDashboardMetricState()])) as Record<DashboardMetricKey, DashboardMetricState>;
}

function createValueRecord(): SourceValues {
  return Object.fromEntries(DASHBOARD_KEYS.map((key) => [key, null])) as SourceValues;
}

function createDashboardMetricHarness() {
  const cycle = createDashboardRefreshCycle();
  const state: DashboardHarnessState = {
    loading: false,
    lastRefresh: null,
    dashboardError: "",
    dashboardPartialWarning: "",
    values: createValueRecord(),
    metrics: createMetricRecord()
  };
  let metricRequestCount = 0;

  async function load(input: {
    initialize?: Promise<void>;
    requests: SourceRequests;
    refreshStamp: string;
    deviceStoreError?: string;
  }) {
    const ticket = cycle.begin();
    state.loading = true;
    state.dashboardError = "";
    try {
      await (input.initialize ?? Promise.resolve());
      if (!cycle.isLatest(ticket)) {
        return;
      }
      const results = await Promise.all(DASHBOARD_KEYS.map((key) => runDashboardMetric({
        key,
        state: state.metrics[key],
        loader: async () => {
          metricRequestCount += 1;
          const value = await (input.requests[key] ?? Promise.resolve(`${key}:${input.refreshStamp}`));
          if (key === "devices" && input.deviceStoreError) {
            throw new Error(input.deviceStoreError);
          }
          return value;
        },
        commit: (value) => {
          state.values[key] = value;
        },
        isLatest: () => cycle.isLatest(ticket),
        now: () => Number(input.refreshStamp)
      })));
      if (!cycle.isLatest(ticket)) {
        return;
      }
      applyDashboardResultSummary(state, results, input.refreshStamp);
    } catch (error) {
      if (cycle.isLatest(ticket)) {
        state.dashboardError = error instanceof Error ? `应用初始化失败：${error.message}` : "应用初始化失败";
        state.dashboardPartialWarning = "";
      }
    } finally {
      if (cycle.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  function unmount() {
    cycle.invalidate();
    state.loading = false;
  }

  return { state, load, unmount, getMetricRequestCount: () => metricRequestCount };
}

function applyDashboardResultSummary(state: DashboardHarnessState, results: DashboardMetricRunResult[], refreshStamp: string) {
  const successKeys = results.filter((result) => result.status === "success").map((result) => result.key);
  const failedKeys = results.filter((result) => result.status === "error").map((result) => result.key);
  if (successKeys.length > 0) {
    state.lastRefresh = refreshStamp;
  }
  if (failedKeys.length === DASHBOARD_KEYS.length) {
    state.dashboardError = `总览数据刷新失败：${failedKeys.map((key) => DASHBOARD_METRIC_LABELS[key]).join("、")}`;
    state.dashboardPartialWarning = "";
    return;
  }
  state.dashboardError = "";
  state.dashboardPartialWarning = buildDashboardPartialWarning(failedKeys);
}

describe("dashboard-metric-state", () => {
  it("初始状态为 idle 且没有历史成功数据", () => {
    const state = createDashboardMetricState();

    expect(state).toEqual({
      status: "idle",
      error: "",
      lastSuccessAt: null
    });
    expect(isDashboardMetricStale(state)).toBe(false);
    expect(isDashboardMetricUnavailable(state)).toBe(false);
  });

  it("loading 会清空 error，但保留历史成功快照", () => {
    const state = createDashboardMetricState();
    markDashboardMetricSuccess(state, 1000);
    markDashboardMetricLoading(state);

    expect(state.status).toBe("loading");
    expect(state.error).toBe("");
    expect(state.lastSuccessAt).toBe(1000);
  });

  it("首次 failure 会标记 unavailable", () => {
    const state = createDashboardMetricState();
    markDashboardMetricFailure(state, "cache down");

    expect(state.status).toBe("error");
    expect(state.error).toBe("cache down");
    expect(state.lastSuccessAt).toBeNull();
    expect(isDashboardMetricUnavailable(state)).toBe(true);
    expect(isDashboardMetricStale(state)).toBe(false);
  });

  it("last success 后 failure 会变成 stale", () => {
    const state = createDashboardMetricState();
    markDashboardMetricSuccess(state, 1000);
    markDashboardMetricFailure(state, "cache down");

    expect(state.status).toBe("error");
    expect(state.error).toBe("cache down");
    expect(state.lastSuccessAt).toBe(1000);
    expect(isDashboardMetricStale(state)).toBe(true);
    expect(isDashboardMetricUnavailable(state)).toBe(false);
  });

  it("failure 后 recovery 会清空 error 并更新 lastSuccessAt", () => {
    const state = createDashboardMetricState();
    markDashboardMetricSuccess(state, 1000);
    markDashboardMetricFailure(state, "cache down");
    markDashboardMetricSuccess(state, 2000);

    expect(state.status).toBe("success");
    expect(state.error).toBe("");
    expect(state.lastSuccessAt).toBe(2000);
    expect(isDashboardMetricStale(state)).toBe(false);
  });

  it("partial warning 会合并失败 source label", () => {
    expect(buildDashboardPartialWarning(["devices", "storage", "performance"]))
      .toBe(`部分总览数据刷新失败：${DASHBOARD_METRIC_LABELS.devices}、${DASHBOARD_METRIC_LABELS.storage}、${DASHBOARD_METRIC_LABELS.performance}`);
  });

  it("没有失败 source 时 partial warning 为空", () => {
    expect(buildDashboardPartialWarning([])).toBe("");
  });

  it("source label 保持稳定中文可读", () => {
    expect(DASHBOARD_METRIC_LABELS.alarms).toBe("最近告警");
    expect(DASHBOARD_METRIC_LABELS.report).toBe("云上报");
    expect(DASHBOARD_METRIC_LABELS.runtime).toBe("运行状态");
  });

  it("runner success 只在 latest 时提交 value 并标记成功", async () => {
    const state = createDashboardMetricState();
    let committed = "";

    const result = await runDashboardMetric({
      key: "cache",
      state,
      loader: () => Promise.resolve("90%"),
      commit: (value) => {
        committed = value;
      },
      isLatest: () => true,
      now: () => 1000
    });

    expect(result).toEqual({ key: "cache", status: "success" });
    expect(committed).toBe("90%");
    expect(state).toEqual({ status: "success", error: "", lastSuccessAt: 1000 });
  });

  it("runner failure 不清业务值，只标记当前 source error", async () => {
    const state = createDashboardMetricState();
    markDashboardMetricSuccess(state, 1000);
    let committed = "old";

    const result = await runDashboardMetric({
      key: "storage",
      state,
      loader: () => Promise.reject(new Error("storage down")),
      commit: (value) => {
        committed = String(value);
      },
      isLatest: () => true
    });

    expect(result).toEqual({ key: "storage", status: "error" });
    expect(committed).toBe("old");
    expect(isDashboardMetricStale(state)).toBe(true);
    expect(state.error).toBe("storage down");
  });

  it("stale success 不提交 value，也不清掉最新错误", async () => {
    const state = createDashboardMetricState();
    markDashboardMetricFailure(state, "latest failure");
    let committed = "latest";
    let latest = true;

    const result = await runDashboardMetric({
      key: "alarms",
      state,
      loader: async () => {
        latest = false;
        return "stale success";
      },
      commit: (value) => {
        committed = String(value);
      },
      isLatest: () => latest
    });

    expect(result).toEqual({ key: "alarms", status: "stale" });
    expect(committed).toBe("latest");
    expect(state.status).toBe("loading");
  });

  it("stale failure 不写 error，也不污染最新 source 状态", async () => {
    const state = createDashboardMetricState();
    markDashboardMetricSuccess(state, 1000);
    let latest = true;

    const result = await runDashboardMetric({
      key: "runtime",
      state,
      loader: async () => {
        latest = false;
        throw new Error("stale failure");
      },
      commit: () => undefined,
      isLatest: () => latest
    });

    expect(result).toEqual({ key: "runtime", status: "stale" });
    expect(state.status).toBe("loading");
    expect(state.error).toBe("");
    expect(state.lastSuccessAt).toBe(1000);
  });

  it("all success 会清空 dashboard error/warning 并更新 lastRefresh", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({ requests: {}, refreshStamp: "1000" });

    expect(DASHBOARD_KEYS.every((key) => harness.state.metrics[key].status === "success")).toBe(true);
    expect(harness.state.dashboardError).toBe("");
    expect(harness.state.dashboardPartialWarning).toBe("");
    expect(harness.state.lastRefresh).toBe("1000");
  });

  it("single failure 只标记对应 source，并保留其他成功值", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({
      requests: { storage: Promise.reject(new Error("storage down")) },
      refreshStamp: "1000"
    });

    expect(harness.state.metrics.storage.status).toBe("error");
    expect(harness.state.dashboardPartialWarning).toContain("历史存储");
    expect(harness.state.dashboardError).toBe("");
    expect(harness.state.values.report).toBe("report:1000");
    expect(harness.state.lastRefresh).toBe("1000");
  });

  it("multiple failure 会合并为一条 persistent warning", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({
      requests: {
        cache: Promise.reject(new Error("cache down")),
        storage: Promise.reject(new Error("storage down")),
        performance: Promise.reject(new Error("performance down"))
      },
      refreshStamp: "1000"
    });

    expect(harness.state.dashboardPartialWarning).toBe("部分总览数据刷新失败：缓存指标、历史存储、性能详情");
    expect(harness.state.dashboardError).toBe("");
  });

  it("all failed 会写 dashboardError 且不更新 lastRefresh", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({
      requests: Object.fromEntries(DASHBOARD_KEYS.map((key) => [key, Promise.reject(new Error(`${key} down`))])) as SourceRequests,
      refreshStamp: "1000"
    });

    expect(harness.state.dashboardError).toContain("总览数据刷新失败");
    expect(harness.state.dashboardError).toContain("设备列表");
    expect(harness.state.dashboardPartialWarning).toBe("");
    expect(harness.state.lastRefresh).toBeNull();
  });

  it("app initialization failure 会释放 loading 且不启动 metric requests", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({
      initialize: Promise.reject(new Error("init down")),
      requests: {},
      refreshStamp: "1000"
    });

    expect(harness.state.loading).toBe(false);
    expect(harness.state.dashboardError).toBe("应用初始化失败：init down");
    expect(harness.getMetricRequestCount()).toBe(0);
    expect(harness.state.lastRefresh).toBeNull();
  });

  it("partial refresh 会保留 alarms 和 cache 的 last known good", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({
      requests: { alarms: Promise.resolve("alarms old"), cache: Promise.resolve("cache 90%") },
      refreshStamp: "1000"
    });
    await harness.load({
      requests: { alarms: Promise.reject(new Error("alarm down")), cache: Promise.reject(new Error("cache down")) },
      refreshStamp: "2000"
    });

    expect(harness.state.values.alarms).toBe("alarms old");
    expect(harness.state.values.cache).toBe("cache 90%");
    expect(isDashboardMetricStale(harness.state.metrics.alarms)).toBe(true);
    expect(isDashboardMetricStale(harness.state.metrics.cache)).toBe(true);
  });

  it("source recovery 会提交 latest value 并清空 error", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({ requests: { cache: Promise.resolve("cache 90%") }, refreshStamp: "1000" });
    await harness.load({ requests: { cache: Promise.reject(new Error("cache down")) }, refreshStamp: "2000" });
    await harness.load({ requests: { cache: Promise.resolve("cache 95%") }, refreshStamp: "3000" });

    expect(harness.state.values.cache).toBe("cache 95%");
    expect(harness.state.metrics.cache.status).toBe("success");
    expect(harness.state.metrics.cache.error).toBe("");
  });

  it("alarm success empty 与 first failure 可区分", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({ requests: { alarms: Promise.resolve("0") }, refreshStamp: "1000" });
    expect(harness.state.values.alarms).toBe("0");
    expect(harness.state.metrics.alarms.status).toBe("success");

    const failedHarness = createDashboardMetricHarness();
    await failedHarness.load({ requests: { alarms: Promise.reject(new Error("alarm down")) }, refreshStamp: "1000" });
    expect(failedHarness.state.values.alarms).toBeNull();
    expect(isDashboardMetricUnavailable(failedHarness.state.metrics.alarms)).toBe(true);
  });

  it("device refresh resolved 但 deviceStore.error 存在时视为 devices source failure", async () => {
    const harness = createDashboardMetricHarness();

    await harness.load({ requests: { devices: Promise.resolve("old devices") }, refreshStamp: "1000" });
    await harness.load({ requests: { devices: Promise.resolve("new devices") }, refreshStamp: "2000", deviceStoreError: "device down" });

    expect(harness.state.values.devices).toBe("old devices");
    expect(harness.state.metrics.devices.status).toBe("error");
    expect(harness.state.metrics.devices.error).toBe("device down");
  });

  it("Cycle A failure 晚于 Cycle B success 时不能污染 B source", async () => {
    const harness = createDashboardMetricHarness();
    const cacheA = createDeferred<string>();

    const cycleA = harness.load({ requests: { cache: cacheA.promise }, refreshStamp: "1000" });
    await flushPromises();
    await harness.load({ requests: { cache: Promise.resolve("cache B") }, refreshStamp: "2000" });

    cacheA.reject(new Error("stale cache down"));
    await cycleA;

    expect(harness.state.values.cache).toBe("cache B");
    expect(harness.state.metrics.cache.status).toBe("success");
    expect(harness.state.dashboardPartialWarning).toBe("");
  });

  it("Cycle A success 晚于 Cycle B failure 时不能清掉 B error", async () => {
    const harness = createDashboardMetricHarness();
    const cacheA = createDeferred<string>();

    const cycleA = harness.load({ requests: { cache: cacheA.promise }, refreshStamp: "1000" });
    await flushPromises();
    await harness.load({ requests: { cache: Promise.reject(new Error("cache B down")) }, refreshStamp: "2000" });

    cacheA.resolve("stale cache A");
    await cycleA;

    expect(harness.state.values.cache).toBeNull();
    expect(harness.state.metrics.cache.status).toBe("error");
    expect(harness.state.metrics.cache.error).toBe("cache B down");
  });

  it("unmount 后 pending cycle 不提交 metric、error、warning 或 lastRefresh", async () => {
    const harness = createDashboardMetricHarness();
    const cache = createDeferred<string>();

    const cycle = harness.load({ requests: { cache: cache.promise }, refreshStamp: "1000" });
    await flushPromises();
    harness.unmount();
    cache.resolve("cache 90%");
    await cycle;

    expect(harness.state.loading).toBe(false);
    expect(harness.state.values.cache).toBeNull();
    expect(harness.state.dashboardError).toBe("");
    expect(harness.state.dashboardPartialWarning).toBe("");
    expect(harness.state.lastRefresh).toBeNull();
  });
});
