import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import type { AlarmAcknowledgement } from "../../../types/ops";
import type { AlarmRow } from "@/types/monitor";
import {
  applyAlarmAcknowledgement,
  buildAlarmIdentity,
  mergeAlarmAcknowledgementStates
} from "./alarm-utils";
import {
  buildAlarmAcknowledgementRefreshContext,
  buildAlarmQueryContext,
  isSameAlarmAcknowledgementRefreshContext,
  isSameAlarmQueryContext,
  shouldDisableAlarmSubmit,
  type AlarmQueryContext
} from "./alarm-request-lifecycle";

const ACK_WARNING = "确认状态暂不可用，当前显示告警历史和最后已知确认状态";

interface AlarmQueryHarnessState {
  loading: boolean;
  alarms: AlarmRow[];
  acknowledgements: Record<string, AlarmAcknowledgement>;
  error: string | null;
  ackStatusLoading: boolean;
  ackStatusUnavailable: boolean;
  ackStatusWarning: string | null;
  ackStatusInitialized: boolean;
  pendingContext: AlarmQueryContext | null;
  toast: string | null;
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

function alarmRow(alarmId: string, overrides: Partial<AlarmRow> = {}): AlarmRow {
  return {
    alarmId,
    deviceId: "device-a",
    pointCode: `${alarmId}-point`,
    pointName: `${alarmId}-point`,
    content: `${alarmId}-content`,
    status: "ACTIVE",
    acknowledged: false,
    ...overrides
  };
}

function ack(alarmId: string, overrides: Partial<AlarmAcknowledgement> = {}): AlarmAcknowledgement {
  return {
    alarmId,
    ...overrides
  };
}

function createAlarmQueryHarness(initialContext: AlarmQueryContext) {
  const queryOwner = createLatestRequestOwner(isSameAlarmQueryContext);
  const ackOwner = createLatestRequestOwner(isSameAlarmAcknowledgementRefreshContext);
  const live = { current: { ...initialContext } };
  let automaticAckFactoryCalls = 0;
  const state: AlarmQueryHarnessState = {
    loading: false,
    alarms: [],
    acknowledgements: {},
    error: null,
    ackStatusLoading: false,
    ackStatusUnavailable: false,
    ackStatusWarning: null,
    ackStatusInitialized: false,
    pendingContext: null,
    toast: null
  };

  async function load(
    rowsRequest: Promise<AlarmRow[]>,
    ackRequestFactory: () => Promise<Record<string, AlarmAcknowledgement>>,
    snapshot: AlarmQueryContext
  ) {
    const requestContext = buildAlarmQueryContext(snapshot);
    const ticket = queryOwner.begin(requestContext);
    ackOwner.invalidate();
    state.loading = true;
    state.ackStatusLoading = false;
    state.error = null;
    state.ackStatusUnavailable = false;
    state.ackStatusWarning = null;
    state.ackStatusInitialized = false;
    state.pendingContext = requestContext;
    try {
      const rows = await rowsRequest;
      if (!queryOwner.canCommit(ticket, buildAlarmQueryContext(live.current))) {
        return;
      }
      if (rows.length === 0) {
        state.alarms = [];
        state.acknowledgements = {};
        state.ackStatusUnavailable = false;
        state.ackStatusWarning = null;
        state.ackStatusInitialized = true;
        return;
      }
      state.alarms = mergeAlarmAcknowledgementStates(rows, state.acknowledgements);
      automaticAckFactoryCalls += 1;
      void refreshWithSnapshot(ackRequestFactory(), [...state.alarms], "automatic");
    } catch (error) {
      if (!queryOwner.canCommit(ticket, buildAlarmQueryContext(live.current))) {
        return;
      }
      state.alarms = [];
      state.acknowledgements = {};
      state.error = error instanceof Error ? error.message : String(error || "告警历史加载失败");
      state.ackStatusUnavailable = false;
      state.ackStatusWarning = null;
      state.ackStatusInitialized = false;
    } finally {
      if (queryOwner.isLatest(ticket)) {
        state.loading = false;
        state.pendingContext = null;
      }
    }
  }

  async function refreshWithSnapshot(
    request: Promise<Record<string, AlarmAcknowledgement>>,
    rowsSnapshot: AlarmRow[],
    source: "automatic" | "manual"
  ) {
    const requestContext = buildAlarmAcknowledgementRefreshContext(rowsSnapshot);
    const ticket = ackOwner.begin(requestContext);
    state.ackStatusLoading = true;
    if (source === "manual") {
      state.toast = null;
    }
    try {
      const acknowledgements = await request;
      const liveContext = buildAlarmAcknowledgementRefreshContext(state.alarms);
      if (!ackOwner.canCommit(ticket, liveContext)) {
        return;
      }
      state.acknowledgements = acknowledgements;
      state.alarms = mergeAlarmAcknowledgementStates(state.alarms, acknowledgements);
      state.ackStatusUnavailable = false;
      state.ackStatusWarning = null;
      state.ackStatusInitialized = true;
      if (source === "manual") {
        state.toast = "确认状态批量查询完成";
      }
    } catch {
      const liveContext = buildAlarmAcknowledgementRefreshContext(state.alarms);
      if (!ackOwner.canCommit(ticket, liveContext)) {
        return;
      }
      state.ackStatusUnavailable = true;
      state.ackStatusWarning = ACK_WARNING;
      state.ackStatusInitialized = false;
      if (source === "manual") {
        state.toast = "确认状态批量查询失败";
      }
    } finally {
      if (ackOwner.isLatest(ticket)) {
        state.ackStatusLoading = false;
      }
    }
  }

  async function manualRefresh(request: Promise<Record<string, AlarmAcknowledgement>>) {
    if (!state.alarms.length) {
      state.toast = "当前没有可查询确认状态的告警";
      return;
    }
    await refreshWithSnapshot(request, [...state.alarms], "manual");
  }

  async function acknowledgeWrite(alarmId: string, request: Promise<AlarmAcknowledgement>) {
    ackOwner.invalidate();
    state.ackStatusLoading = false;
    try {
      const acknowledgement = await request;
      state.acknowledgements = {
        ...state.acknowledgements,
        [alarmId]: acknowledgement
      };
      state.alarms = applyAlarmAcknowledgement(state.alarms, alarmId, acknowledgement);
      state.toast = "告警已确认";
    } catch (error) {
      state.toast = error instanceof Error ? error.message : "告警确认失败";
    }
  }

  function unmount() {
    state.loading = false;
    state.ackStatusLoading = false;
    state.pendingContext = null;
    queryOwner.invalidate();
    ackOwner.invalidate();
  }

  return {
    live,
    state,
    load,
    manualRefresh,
    acknowledgeWrite,
    unmount,
    getAutomaticAckFactoryCalls: () => automaticAckFactoryCalls,
    invalidateAckOwner: () => {
      ackOwner.invalidate();
      state.ackStatusLoading = false;
    }
  };
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
    const rowsA = createDeferred<AlarmRow[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<AlarmRow[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, () => ackA.promise, filterA);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, () => ackB.promise, filterB);
    await flushPromises();

    rowsB.resolve([alarmRow("alarm-b")]);
    await flushPromises();
    ackB.resolve({ "alarm-b": ack("alarm-b") });
    await flushPromises();
    rowsA.resolve([alarmRow("alarm-a")]);
    await flushPromises();
    ackA.resolve({ "alarm-a": ack("alarm-a") });
    await flushPromises();

    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-b"]);
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
    const rowsA = createDeferred<AlarmRow[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<AlarmRow[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, () => ackA.promise, filterA);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, () => ackB.promise, filterB);
    await flushPromises();

    rowsA.resolve([alarmRow("alarm-a")]);
    await flushPromises();

    expect(harness.state.loading).toBe(true);
    expect(harness.state.alarms).toEqual([]);

    rowsB.resolve([alarmRow("alarm-b")]);
    await flushPromises();
    ackB.resolve({ "alarm-b": ack("alarm-b") });
    await flushPromises();

    expect(harness.state.loading).toBe(false);
    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-b"]);
  });

  it("history success + ack failure 时保留 alarms，并写 ack warning", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    const rows = createDeferred<AlarmRow[]>();
    const ackRequest = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rows.promise, () => ackRequest.promise, filter);
    await flushPromises();

    rows.resolve([alarmRow("alarm-a")]);
    await flushPromises();
    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-a"]);
    expect(harness.state.error).toBeNull();

    ackRequest.reject(new Error("ack down"));
    await flushPromises();

    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-a"]);
    expect(harness.state.error).toBeNull();
    expect(harness.state.ackStatusUnavailable).toBe(true);
    expect(harness.state.ackStatusWarning).toBe(ACK_WARNING);
  });

  it("history failure 是 fatal，并且不提交 ack enrichment", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    const rows = createDeferred<AlarmRow[]>();

    void harness.load(rows.promise, () => Promise.resolve({ "alarm-a": ack("alarm-a") }), filter);
    await flushPromises();

    rows.reject(new Error("history failed"));
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.acknowledgements).toEqual({});
    expect(harness.state.error).toBe("history failed");
    expect(harness.state.ackStatusUnavailable).toBe(false);
    expect(harness.getAutomaticAckFactoryCalls()).toBe(0);
  });

  it("empty alarm history 不会触发 ack API", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "", level: "", keyword: "", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    const rows = createDeferred<AlarmRow[]>();

    void harness.load(rows.promise, () => Promise.resolve({}), filter);
    await flushPromises();

    rows.resolve([]);
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.error).toBeNull();
    expect(harness.state.ackStatusUnavailable).toBe(false);
    expect(harness.state.ackStatusInitialized).toBe(true);
    expect(harness.getAutomaticAckFactoryCalls()).toBe(0);
  });

  it("manual ack refresh failure 保留最后已知状态并设置 persistent warning", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    harness.state.alarms = mergeAlarmAcknowledgementStates([alarmRow("alarm-a")], { "alarm-a": ack("alarm-a", { note: "已确认" }) });
    harness.state.acknowledgements = { "alarm-a": ack("alarm-a", { note: "已确认" }) };
    harness.state.ackStatusInitialized = true;
    const request = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.manualRefresh(request.promise);
    await flushPromises();
    request.reject(new Error("manual refresh failed"));
    await flushPromises();

    expect(harness.state.alarms[0]).toMatchObject({ alarmId: "alarm-a", acknowledged: true });
    expect(harness.state.acknowledgements).toEqual({ "alarm-a": ack("alarm-a", { note: "已确认" }) });
    expect(harness.state.ackStatusUnavailable).toBe(true);
    expect(harness.state.ackStatusWarning).toBe(ACK_WARNING);
  });

  it("manual ack refresh success 会提交新 map、merge rows 并清 warning", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    harness.state.alarms = [alarmRow("alarm-a")];
    const request = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.manualRefresh(request.promise);
    await flushPromises();
    request.resolve({ "alarm-a": ack("alarm-a", { note: "已确认" }) });
    await flushPromises();

    expect(harness.state.acknowledgements).toEqual({ "alarm-a": ack("alarm-a", { note: "已确认" }) });
    expect(harness.state.alarms[0]).toMatchObject({ alarmId: "alarm-a", acknowledged: true });
    expect(harness.state.ackStatusUnavailable).toBe(false);
    expect(harness.state.ackStatusWarning).toBeNull();
    expect(harness.state.ackStatusInitialized).toBe(true);
  });

  it("old alarm query 的 ack 结果不会覆盖新 alarms", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-b", level: "WARNING", keyword: "pressure", hours: 72, limit: 80 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<AlarmRow[]>();
    const ackA = createDeferred<Record<string, AlarmAcknowledgement>>();
    const rowsB = createDeferred<AlarmRow[]>();
    const ackB = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rowsA.promise, () => ackA.promise, filterA);
    await flushPromises();
    rowsA.resolve([alarmRow("alarm-a")]);
    await flushPromises();

    harness.live.current = filterB;
    void harness.load(rowsB.promise, () => ackB.promise, filterB);
    await flushPromises();
    rowsB.resolve([alarmRow("alarm-b")]);
    await flushPromises();
    ackB.resolve({ "alarm-b": ack("alarm-b") });
    await flushPromises();

    ackA.resolve({ "alarm-a": ack("alarm-a") });
    await flushPromises();

    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-b"]);
    expect(Object.keys(harness.state.acknowledgements)).toEqual(["alarm-b"]);
  });

  it("bulk ack read 在 write 成功后返回 stale success，不得覆盖 write 状态", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    harness.state.alarms = [alarmRow("alarm-a")];
    const refreshRequest = createDeferred<Record<string, AlarmAcknowledgement>>();
    const writeRequest = createDeferred<AlarmAcknowledgement>();

    void harness.manualRefresh(refreshRequest.promise);
    await flushPromises();

    void harness.acknowledgeWrite("alarm-a", writeRequest.promise);
    await flushPromises();
    writeRequest.resolve(ack("alarm-a", { note: "现场已确认" }));
    await flushPromises();

    refreshRequest.resolve({});
    await flushPromises();

    expect(harness.state.alarms[0]).toMatchObject({ alarmId: "alarm-a", acknowledged: true, acknowledgement: { note: "现场已确认" } });
    expect(harness.state.acknowledgements["alarm-a"]).toMatchObject({ note: "现场已确认" });
    expect(harness.state.toast).toBe("告警已确认");
  });

  it("bulk ack read 在 write 成功后返回 stale failure，不得产生 stale warning", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    harness.state.alarms = [alarmRow("alarm-a")];
    const refreshRequest = createDeferred<Record<string, AlarmAcknowledgement>>();
    const writeRequest = createDeferred<AlarmAcknowledgement>();

    void harness.manualRefresh(refreshRequest.promise);
    await flushPromises();

    void harness.acknowledgeWrite("alarm-a", writeRequest.promise);
    await flushPromises();
    writeRequest.resolve(ack("alarm-a", { note: "现场已确认" }));
    await flushPromises();

    refreshRequest.reject(new Error("stale refresh failed"));
    await flushPromises();

    expect(harness.state.alarms[0]).toMatchObject({ alarmId: "alarm-a", acknowledged: true });
    expect(harness.state.ackStatusWarning).toBeNull();
    expect(harness.state.toast).toBe("告警已确认");
  });

  it("context 改变但没有新 query 时，旧 success/error 不提交且 loading 能释放", async () => {
    const filterA = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const filterB = buildAlarmQueryContext({ deviceId: "device-b", level: "WARNING", keyword: "pressure", hours: 72, limit: 80 });
    const harness = createAlarmQueryHarness(filterA);
    const rowsA = createDeferred<AlarmRow[]>();

    void harness.load(rowsA.promise, () => Promise.resolve({}), filterA);
    await flushPromises();

    harness.live.current = filterB;
    rowsA.reject(new Error("device-a failed"));
    await flushPromises();

    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.acknowledgements).toEqual({});
    expect(harness.state.error).toBeNull();
    expect(harness.state.ackStatusWarning).toBeNull();
    expect(harness.state.loading).toBe(false);
    expect(harness.state.pendingContext).toBeNull();
  });

  it("unmount 后旧读请求与 ack refresh 返回不会再提交页面状态", async () => {
    const filter = buildAlarmQueryContext({ deviceId: "device-a", level: "CRITICAL", keyword: "temp", hours: 24, limit: 50 });
    const harness = createAlarmQueryHarness(filter);
    const rows = createDeferred<AlarmRow[]>();
    const ackRequest = createDeferred<Record<string, AlarmAcknowledgement>>();

    void harness.load(rows.promise, () => ackRequest.promise, filter);
    await flushPromises();
    rows.resolve([alarmRow("alarm-a")]);
    await flushPromises();
    harness.unmount();

    ackRequest.resolve({ "alarm-a": ack("alarm-a") });
    await flushPromises();

    expect(harness.state.alarms.map((item) => item.alarmId)).toEqual(["alarm-a"]);
    expect(harness.state.acknowledgements).toEqual({});
    expect(harness.state.loading).toBe(false);
    expect(harness.state.ackStatusLoading).toBe(false);
  });

  it("buildAlarmAcknowledgementRefreshContext 保持 alarm id 列表 identityKey 与 500 上限", () => {
    const rows = [
      { alarmId: "alarm-a" },
      { alarmId: "alarm-a" },
      { alarmId: "alarm-b" }
    ];
    expect(buildAlarmAcknowledgementRefreshContext(rows)).toEqual({
      alarmIds: ["alarm-a", "alarm-b"],
      identityKey: "alarm-a|alarm-b"
    });
  });

  it("buildAlarmIdentity 对无 backend id 的告警仍稳定", () => {
    const row = alarmRow("", { alarmId: undefined, deviceId: "dev-1", pointCode: "temp", ruleId: "high", timestamp: 123456 });
    expect(buildAlarmIdentity(row)).toBe(buildAlarmIdentity({ ...row }));
  });
});
