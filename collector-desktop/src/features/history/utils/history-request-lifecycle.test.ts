import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import {
  buildHistoryDataQueryParams,
  buildHistoryPointsRequestContext,
  buildHistoryPointsRequestSnapshot,
  buildHistoryQueryContext,
  buildHistoryRelatedAlarmQuery,
  isSameHistoryPointsRequestContext,
  isSameHistoryQueryContext,
  shouldDisableHistorySubmit,
  type HistoryQueryContext
} from "./history-request-lifecycle";

interface PointsHarnessState {
  points: string[];
  pointRef: string;
  warning: string | null;
}

interface HistoryHarnessState {
  loading: boolean;
  mainRows: string[];
  compareRows: Record<string, string[]>;
  alarms: string[];
  error: string | null;
  pendingContext: HistoryQueryContext | null;
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

function createPointsHarness(initialDeviceId: string) {
  const owner = createLatestRequestOwner(isSameHistoryPointsRequestContext);
  const live = { currentDeviceId: initialDeviceId };
  const state: PointsHarnessState = {
    points: [],
    pointRef: "",
    warning: null
  };

  async function load(
    request: Promise<{ points: string[]; pointRef: string }>,
    snapshotInput: { deviceId: string; preferredPointRef?: string; autoQuery?: boolean }
  ) {
    const snapshot = buildHistoryPointsRequestSnapshot(snapshotInput);
    const ticket = owner.begin(buildHistoryPointsRequestContext(snapshot));
    state.warning = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildHistoryPointsRequestContext({ deviceId: live.currentDeviceId }))) {
        return;
      }
      state.points = result.points;
      state.pointRef = result.pointRef;
    } catch (error) {
      if (!owner.canCommit(ticket, buildHistoryPointsRequestContext({ deviceId: live.currentDeviceId }))) {
        return;
      }
      state.points = [];
      state.pointRef = "";
      state.warning = error instanceof Error ? error.message : String(error || "点位配置加载失败");
    }
  }

  function unmount() {
    owner.invalidate();
  }

  return { live, state, load, unmount };
}

function createHistoryHarness(initialContext: HistoryQueryContext) {
  const owner = createLatestRequestOwner(isSameHistoryQueryContext);
  const live = { current: { ...initialContext, comparePointRefs: [...initialContext.comparePointRefs] } };
  const state: HistoryHarnessState = {
    loading: false,
    mainRows: [],
    compareRows: {},
    alarms: [],
    error: null,
    pendingContext: null
  };

  async function load(
    request: Promise<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>,
    snapshot: HistoryQueryContext
  ) {
    const requestContext = buildHistoryQueryContext(snapshot);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    state.pendingContext = requestContext;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildHistoryQueryContext(live.current))) {
        return;
      }
      state.mainRows = result.mainRows;
      state.compareRows = result.compareRows;
      state.alarms = result.alarms;
    } catch (error) {
      if (!owner.canCommit(ticket, buildHistoryQueryContext(live.current))) {
        return;
      }
      state.mainRows = [];
      state.compareRows = {};
      state.alarms = [];
      state.error = error instanceof Error ? error.message : String(error || "历史数据查询失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
        state.pendingContext = null;
      }
    }
  }

  function unmount() {
    state.loading = false;
    state.pendingContext = null;
    owner.invalidate();
  }

  return { live, state, load, unmount };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("history-request-lifecycle", () => {
  it("points A → B 时，B 返回后 A 返回，最终 points 仍属于 B", async () => {
    const harness = createPointsHarness("device-a");
    const requestA = createDeferred<{ points: string[]; pointRef: string }>();
    const requestB = createDeferred<{ points: string[]; pointRef: string }>();

    void harness.load(requestA.promise, { deviceId: "device-a", preferredPointRef: "point-a" });
    await flushPromises();

    harness.live.currentDeviceId = "device-b";
    void harness.load(requestB.promise, { deviceId: "device-b", preferredPointRef: "point-b" });
    await flushPromises();

    requestB.resolve({ points: ["b-1", "b-2"], pointRef: "point-b" });
    await flushPromises();
    requestA.resolve({ points: ["a-1"], pointRef: "point-a" });
    await flushPromises();

    expect(harness.state.points).toEqual(["b-1", "b-2"]);
    expect(harness.state.pointRef).toBe("point-b");
    expect(harness.state.warning).toBeNull();
  });

  it("history query Q1 → Q2 时，Q1 后返回不会覆盖 Q2", async () => {
    const contextQ1 = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-1",
      comparePointRefs: ["point-2"],
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 100
    });
    const harness = createHistoryHarness(contextQ1);
    const requestQ1 = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();
    const requestQ2 = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();

    void harness.load(requestQ1.promise, contextQ1);
    await flushPromises();

    const contextQ2 = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-3",
      comparePointRefs: ["point-4"],
      startTime: "2026-09-01T12:00",
      endTime: "2026-09-01T13:00",
      limit: 120
    });
    harness.live.current = contextQ2;
    void harness.load(requestQ2.promise, contextQ2);
    await flushPromises();

    requestQ2.resolve({
      mainRows: ["q2-main"],
      compareRows: { "point-4": ["q2-compare"] },
      alarms: ["q2-alarm"]
    });
    await flushPromises();
    requestQ1.resolve({
      mainRows: ["q1-main"],
      compareRows: { "point-2": ["q1-compare"] },
      alarms: ["q1-alarm"]
    });
    await flushPromises();

    expect(harness.state.mainRows).toEqual(["q2-main"]);
    expect(harness.state.compareRows).toEqual({ "point-4": ["q2-compare"] });
    expect(harness.state.alarms).toEqual(["q2-alarm"]);
    expect(harness.state.loading).toBe(false);
  });

  it("Q1 context 改变但没有发 Q2 时，Q1 不提交且 loading 正常释放", async () => {
    const contextQ1 = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-1",
      comparePointRefs: ["point-2"],
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 100
    });
    const harness = createHistoryHarness(contextQ1);
    const requestQ1 = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();

    void harness.load(requestQ1.promise, contextQ1);
    await flushPromises();

    harness.live.current = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-9",
      comparePointRefs: ["point-8"],
      startTime: "2026-09-01T12:00",
      endTime: "2026-09-01T13:00",
      limit: 80
    });
    requestQ1.resolve({
      mainRows: ["q1-main"],
      compareRows: { "point-2": ["q1-compare"] },
      alarms: ["q1-alarm"]
    });
    await flushPromises();

    expect(harness.state.mainRows).toEqual([]);
    expect(harness.state.compareRows).toEqual({});
    expect(harness.state.alarms).toEqual([]);
    expect(harness.state.error).toBeNull();
    expect(harness.state.loading).toBe(false);
    expect(harness.state.pendingContext).toBeNull();
  });

  it("Q1 → Q2 时，Q1 先 settle 不会关闭 Q2 loading", async () => {
    const contextQ1 = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-1",
      comparePointRefs: [],
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 100
    });
    const harness = createHistoryHarness(contextQ1);
    const requestQ1 = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();
    const requestQ2 = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();

    void harness.load(requestQ1.promise, contextQ1);
    await flushPromises();

    const contextQ2 = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-5",
      comparePointRefs: ["point-6"],
      startTime: "2026-09-01T12:00",
      endTime: "2026-09-01T13:00",
      limit: 100
    });
    harness.live.current = contextQ2;
    void harness.load(requestQ2.promise, contextQ2);
    await flushPromises();

    requestQ1.resolve({ mainRows: ["q1-main"], compareRows: {}, alarms: [] });
    await flushPromises();

    expect(harness.state.loading).toBe(true);
    expect(harness.state.mainRows).toEqual([]);

    requestQ2.resolve({ mainRows: ["q2-main"], compareRows: { "point-6": ["q2-compare"] }, alarms: [] });
    await flushPromises();

    expect(harness.state.loading).toBe(false);
    expect(harness.state.mainRows).toEqual(["q2-main"]);
  });

  it("route A → route B auto-query 时，旧 query 不会串到新 route", async () => {
    const routeA = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-route-a",
      comparePointRefs: ["point-route-a2"],
      startTime: "2026-09-01T08:00",
      endTime: "2026-09-01T09:00",
      limit: 90
    });
    const routeB = buildHistoryQueryContext({
      deviceId: "device-b",
      pointRef: "point-route-b",
      comparePointRefs: ["point-route-b2"],
      startTime: "2026-09-01T09:00",
      endTime: "2026-09-01T10:00",
      limit: 90
    });
    const harness = createHistoryHarness(routeA);
    const requestA = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();
    const requestB = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();

    void harness.load(requestA.promise, routeA);
    await flushPromises();

    harness.live.current = routeB;
    void harness.load(requestB.promise, routeB);
    await flushPromises();

    requestB.resolve({ mainRows: ["route-b-main"], compareRows: { "point-route-b2": ["route-b-compare"] }, alarms: ["route-b-alarm"] });
    await flushPromises();
    requestA.resolve({ mainRows: ["route-a-main"], compareRows: { "point-route-a2": ["route-a-compare"] }, alarms: ["route-a-alarm"] });
    await flushPromises();

    expect(harness.state.mainRows).toEqual(["route-b-main"]);
    expect(harness.state.compareRows).toEqual({ "point-route-b2": ["route-b-compare"] });
    expect(harness.state.alarms).toEqual(["route-b-alarm"]);
  });

  it("captured params 使用请求开始时的 device/point/time/compare/limit，并克隆 compare refs", () => {
    const comparePointRefs = ["point-2", "point-3"];
    const context = buildHistoryQueryContext({
      deviceId: " device-a ",
      pointRef: " point-1 ",
      comparePointRefs,
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 123.8
    });

    comparePointRefs.push("point-4");

    expect(context.comparePointRefs).toEqual(["point-2", "point-3"]);
    expect(buildHistoryDataQueryParams(context)).toEqual({
      startTs: new Date("2026-09-01T10:00").getTime(),
      endTs: new Date("2026-09-01T11:00").getTime(),
      limit: 123
    });
    expect(buildHistoryRelatedAlarmQuery(context)).toEqual({
      pointCode: "point-1",
      pointId: "point-1",
      startTs: new Date("2026-09-01T10:00").getTime(),
      endTs: new Date("2026-09-01T11:00").getTime(),
      limit: 20
    });
    expect(shouldDisableHistorySubmit(true, context, buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-9",
      comparePointRefs: ["point-2", "point-3"],
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 123
    }))).toBe(false);
  });

  it("unmount 后旧 history query 返回不会再提交页面状态", async () => {
    const context = buildHistoryQueryContext({
      deviceId: "device-a",
      pointRef: "point-1",
      comparePointRefs: [],
      startTime: "2026-09-01T10:00",
      endTime: "2026-09-01T11:00",
      limit: 100
    });
    const harness = createHistoryHarness(context);
    const request = createDeferred<{ mainRows: string[]; compareRows: Record<string, string[]>; alarms: string[] }>();

    void harness.load(request.promise, context);
    await flushPromises();
    harness.unmount();

    request.resolve({ mainRows: ["q1-main"], compareRows: {}, alarms: [] });
    await flushPromises();

    expect(harness.state.mainRows).toEqual([]);
    expect(harness.state.loading).toBe(false);
  });
});
