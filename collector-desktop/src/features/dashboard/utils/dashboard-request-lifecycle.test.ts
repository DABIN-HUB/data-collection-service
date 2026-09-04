import { describe, expect, it } from "vitest";

import { createDashboardRefreshCycle } from "./dashboard-request-lifecycle";

interface DashboardHarnessState {
  loading: boolean;
  lastRefresh: string | null;
  alarms: string[];
  report: string | null;
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

function createDashboardHarness() {
  const cycle = createDashboardRefreshCycle();
  const state: DashboardHarnessState = {
    loading: false,
    lastRefresh: null,
    alarms: [],
    report: null
  };

  async function load(requests: { alarms: Promise<string[]>; report: Promise<string>; refreshStamp: string }) {
    const ticket = cycle.begin();
    state.loading = true;
    const alarmsTask = requests.alarms.then((value) => {
      if (cycle.isLatest(ticket)) {
        state.alarms = value;
      }
    });
    const reportTask = requests.report.then((value) => {
      if (cycle.isLatest(ticket)) {
        state.report = value;
      }
    });
    try {
      await Promise.allSettled([alarmsTask, reportTask]);
      if (cycle.isLatest(ticket)) {
        state.lastRefresh = requests.refreshStamp;
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

  return { state, load, unmount };
}

describe("dashboard-request-lifecycle", () => {
  it("Cycle A → B 时，A 的晚到 metric 不会覆盖 B", async () => {
    const harness = createDashboardHarness();
    const alarmsA = createDeferred<string[]>();
    const reportA = createDeferred<string>();
    const alarmsB = createDeferred<string[]>();
    const reportB = createDeferred<string>();

    void harness.load({ alarms: alarmsA.promise, report: reportA.promise, refreshStamp: "A" });
    await flushPromises();
    void harness.load({ alarms: alarmsB.promise, report: reportB.promise, refreshStamp: "B" });
    await flushPromises();

    alarmsB.resolve(["alarm-b"]);
    reportB.resolve("report-b");
    await flushPromises();
    alarmsA.resolve(["alarm-a"]);
    reportA.resolve("report-a");
    await flushPromises();

    expect(harness.state.alarms).toEqual(["alarm-b"]);
    expect(harness.state.report).toBe("report-b");
    expect(harness.state.lastRefresh).toBe("B");
  });

  it("A finally 时如果 B 仍 pending，不会关闭 B loading", async () => {
    const harness = createDashboardHarness();
    const alarmsA = createDeferred<string[]>();
    const reportA = createDeferred<string>();
    const alarmsB = createDeferred<string[]>();
    const reportB = createDeferred<string>();

    const cycleA = harness.load({ alarms: alarmsA.promise, report: reportA.promise, refreshStamp: "A" });
    await flushPromises();
    const cycleB = harness.load({ alarms: alarmsB.promise, report: reportB.promise, refreshStamp: "B" });
    await flushPromises();

    alarmsA.resolve(["alarm-a"]);
    reportA.resolve("report-a");
    await cycleA;

    expect(harness.state.loading).toBe(true);

    alarmsB.resolve(["alarm-b"]);
    reportB.resolve("report-b");
    await cycleB;

    expect(harness.state.loading).toBe(false);
  });

  it("unmount 后旧 response 不再提交 dashboard 状态", async () => {
    const harness = createDashboardHarness();
    const alarms = createDeferred<string[]>();
    const report = createDeferred<string>();

    void harness.load({ alarms: alarms.promise, report: report.promise, refreshStamp: "A" });
    await flushPromises();
    harness.unmount();

    alarms.resolve(["alarm-a"]);
    report.resolve("report-a");
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.report).toBeNull();
    expect(harness.state.lastRefresh).toBeNull();
    expect(harness.state.loading).toBe(false);
  });
});
