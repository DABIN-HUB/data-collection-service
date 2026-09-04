import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import type { AlarmAcknowledgement } from "../../../types/ops";
import {
  buildAlarmAcknowledgementRefreshContext,
  buildAlarmQueryContext,
  isSameAlarmAcknowledgementRefreshContext,
  isSameAlarmQueryContext,
  shouldDisableAlarmSubmit,
  type AlarmAcknowledgementRefreshContext,
  type AlarmQueryContext
} from "./alarm-request-lifecycle";

interface AlarmQueryHarnessState {
  loading: boolean;
  alarms: string[];
  acknowledgements: Record<string, AlarmAcknowledgement>;
  error: string | null;
  pendingContext: AlarmQueryContext | null;
}

interface AlarmRefreshHarnessState {
  loading: boolean;
  alarms: string[];
  acknowledgements: Record<string, AlarmAcknowledgement>;
  toast: string | null;
  error: string | null;
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

function createAlarmQueryHarness(initialContext: AlarmQueryContext) {
  const queryOwner = createLatestRequestOwner(isSameAlarmQueryContext);
  const ackOwner = createLatestRequestOwner(isSameAlarmAcknowledgementRefreshContext);
  const live = { current: { ...initialContext } };
  const state: AlarmQueryHarnessState = {
    loading: false,
    alarms: [],
    acknowledgements: {},
    error: null,
    pendingContext: null
  };

  async function load(
    rowsRequest: Promise<string[]>,
    ackRequest: Promise<Record<string, AlarmAcknowledgement>>,
    snapshot: AlarmQueryContext
  ) {
    const requestContext = buildAlarmQueryContext(snapshot);
    const ticket = queryOwner.begin(requestContext);
    ackOwner.invalidate();
    state.loading = true;
    state.error = null;
    state.pendingContext = requestContext;
    try {
      const rows = await rowsRequest;
      const acknowledgements = await ackRequest;
      if (!queryOwner.canCommit(ticket, buildAlarmQueryContext(live.current))) {
        return;
      }
      state.acknowledgements = acknowledgements;
      state.alarms = rows;
    } catch (error) {
      if (!queryOwner.canCommit(ticket, buildAlarmQueryContext(live.current))) {
        return;
      }
      state.acknowledgements = {};
      state.alarms = [];
      state.error = error instanceof Error ? error.message : String(error || "告警历史加载失败");
    } finally {
      if (queryOwner.isLatest(ticket)) {
        state.loading = false;
        state.pendingContext = null;
      }
    }
  }

  function unmount() {
    state.loading = false;
    state.pendingContext = null;
    queryOwner.invalidate();
    ackOwner.invalidate();
  }

  return { live, state, load, unmount, invalidateAckOwner: () => ackOwner.invalidate() };
}

function createAlarmRefreshHarness(initialAlarmIds: string[]) {
  const ackOwner = createLatestRequestOwner(isSameAlarmAcknowledgementRefreshContext);
  const live = {
    currentAlarmIds: [...initialAlarmIds]
  };
  const state: AlarmRefreshHarnessState = {
    loading: false,
    alarms: [...initialAlarmIds],
    acknowledgements: {},
    toast: null,
    error: null
  };

  async function refresh(request: Promise<Record<string, AlarmAcknowledgement>>, snapshot: AlarmAcknowledgementRefreshContext) {
    const ticket = ackOwner.begin(snapshot);
    state.loading = true;
    state.toast = null;
    state.error = null;
    try {
      const acknowledgements = await request;
      const liveContext = buildAlarmAcknowledgementRefreshContext(live.currentAlarmIds.map((alarmId) => ({ alarmId })));
      if (!ackOwner.canCommit(ticket, liveContext)) {
        return;
      }
      state.acknowledgements = acknowledgements;
      state.alarms = [...live.currentAlarmIds];
      state.toast = "确认状态批量查询完成";
    } catch (error) {
      const liveContext = buildAlarmAcknowledgementRefreshContext(live.currentAlarmIds.map((alarmId) => ({ alarmId })));
      if (!ackOwner.canCommit(ticket, liveContext)) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "确认状态批量查询失败");
    } finally {
      if (ackOwner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  function replaceAlarmList(nextAlarmIds: string[]) {
    live.currentAlarmIds = [...nextAlarmIds];
    state.alarms = [...nextAlarmIds];
  }

  function invalidate() {
    ackOwner.invalidate();
    state.loading = false;
  }

  return { live, state, refresh, replaceAlarmList, invalidate };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("alarm-request-lifecycle", () => {
  it("filter A → B 时，旧 response 不会覆盖 B", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-b", level: "WARNING", keyword: "pressure", hours: 72, limit: 60 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<string[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<string[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, ackA.promise, filterA);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, ackB.promise, filterB);
    await flushPromises();

    rowsB.resolve(["alarm-b"]);
    ackB.resolve({ "alarm-b": { alarmId: "alarm-b" } });
    await flushPromises();
    rowsA.resolve(["alarm-a"]);
    ackA.resolve({ "alarm-a": { alarmId: "alarm-a" } });
    await flushPromises();

    expect(harness.state.alarms).toEqual(["alarm-b"]);
    expect(Object.keys(harness.state.acknowledgements)).toEqual(["alarm-b"]);
    expect(harness.state.loading).toBe(false);
  });

  it("loading 不再阻塞 changed-context query，但相同 pending context 仍可阻止重复提交", () => {
    const pending = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });

    expect(shouldDisableAlarmSubmit(true, pending, buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 }))).toBe(true);
    expect(shouldDisableAlarmSubmit(true, pending, buildAlarmQueryContext({ deviceId: "device-b", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 }))).toBe(false);
    expect(shouldDisableAlarmSubmit(false, pending, pending)).toBe(false);
  });

  it("Q1 → Q2 时，Q1 先 settle 不会关闭 Q2 loading", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-a", level: "WARNING", keyword: "pressure", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<string[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<string[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, ackA.promise, filterA);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, ackB.promise, filterB);
    await flushPromises();

    rowsA.resolve(["alarm-a"]);
    ackA.resolve({ "alarm-a": { alarmId: "alarm-a" } });
    await flushPromises();

    expect(harness.state.loading).toBe(true);
    expect(harness.state.alarms).toEqual([]);

    rowsB.resolve(["alarm-b"]);
    ackB.resolve({ "alarm-b": { alarmId: "alarm-b" } });
    await flushPromises();

    expect(harness.state.loading).toBe(false);
    expect(harness.state.alarms).toEqual(["alarm-b"]);
  });

  it("old alarm query 的 ack 结果不会覆盖新 alarms", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-b", level: "WARNING", keyword: "pressure", hours: 72, limit: 80 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<string[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<string[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, ackA.promise, filterA);
    await flushPromises();
    rowsA.resolve(["alarm-a"]);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, ackB.promise, filterB);
    await flushPromises();
    rowsB.resolve(["alarm-b"]);
    ackB.resolve({ "alarm-b": { alarmId: "alarm-b" } });
    await flushPromises();

    ackA.resolve({ "alarm-a": { alarmId: "alarm-a" } });
    await flushPromises();

    expect(harness.state.alarms).toEqual(["alarm-b"]);
    expect(Object.keys(harness.state.acknowledgements)).toEqual(["alarm-b"]);
  });

  it("refresh ack for list A 时，如果 list 已变成 B，old ack 不会 merge 到 B", async () => {
    const harness = createAlarmRefreshHarness(["alarm-a-1", "alarm-a-2"]);
    const requestA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const contextA = buildAlarmAcknowledgementRefreshContext([
      { alarmId: "alarm-a-1" },
      { alarmId: "alarm-a-2" }
    ]);

    void harness.refresh(requestA.promise, contextA);
    await flushPromises();

    harness.replaceAlarmList(["alarm-b-1"]);
    harness.invalidate();
    requestA.resolve({
      "alarm-a-1": { alarmId: "alarm-a-1" },
      "alarm-a-2": { alarmId: "alarm-a-2" }
    });
    await flushPromises();

    expect(harness.state.alarms).toEqual(["alarm-b-1"]);
    expect(harness.state.acknowledgements).toEqual({});
    expect(harness.state.loading).toBe(false);
    expect(harness.state.toast).toBeNull();
  });

  it("context 改变但没有新 query 时，旧 success/error 不提交且 loading 能释放", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-b", level: "WARNING", keyword: "pressure", hours: 72, limit: 80 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<string[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, ackA.promise, filterA);
    await flushPromises();

    harness.live.current = filterB;
    rowsA.reject(new Error("device-a failed"));
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.acknowledgements).toEqual({});
    expect(harness.state.error).toBeNull();
    expect(harness.state.loading).toBe(false);
    expect(harness.state.pendingContext).toBeNull();
  });

  it("unmount 后旧读请求返回不会再提交 alarm 页面状态", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<string[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, ackA.promise, filterA);
    await flushPromises();
    harness.unmount();

    rowsA.resolve(["alarm-a"]);
    ackA.resolve({ "alarm-a": { alarmId: "alarm-a" } });
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.loading).toBe(false);
  });
});
