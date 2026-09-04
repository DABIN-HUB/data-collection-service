import { describe, expect, it } from "vitest";

import { createLatestRequestOwner } from "../../request/utils/latest-request-owner";
import {
  buildLogServerQueryContext,
  buildLogVisibleQueryContext,
  isSameLogServerQueryContext,
  isSameLogVisibleQueryContext,
  shouldSkipLogTimerTick,
  type LogServerQueryContext,
  type LogVisibleQueryContext
} from "./log-request-lifecycle";

interface LogHarnessState {
  loading: boolean;
  logs: string[];
  error: string | null;
  pending: LogServerQueryContext | null;
}

interface ExceptionHarnessState {
  loading: boolean;
  keyword: string;
  level: string;
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

function createLogHarness(initialContext: LogServerQueryContext) {
  const owner = createLatestRequestOwner(isSameLogServerQueryContext);
  const live = { current: { ...initialContext } };
  const state: LogHarnessState = {
    loading: false,
    logs: [],
    error: null,
    pending: null
  };

  async function load(request: Promise<string[]>, snapshot: LogServerQueryContext, options: { fromTimer?: boolean } = {}) {
    if (options.fromTimer && shouldSkipLogTimerTick(state.loading, state.pending, live.current)) {
      return false;
    }
    const requestContext = buildLogServerQueryContext(snapshot);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    state.pending = requestContext;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildLogServerQueryContext(live.current))) {
        return true;
      }
      state.logs = result;
    } catch (error) {
      if (!owner.canCommit(ticket, buildLogServerQueryContext(live.current))) {
        return true;
      }
      state.logs = [];
      state.error = error instanceof Error ? error.message : String(error || "运行日志加载失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
        state.pending = null;
      }
    }
    return true;
  }

  function unmount() {
    owner.invalidate();
    state.loading = false;
    state.pending = null;
  }

  return { live, state, load, unmount };
}

function createExceptionHarness(initialContext: LogVisibleQueryContext) {
  const owner = createLatestRequestOwner(isSameLogVisibleQueryContext);
  const live = { current: { ...initialContext } };
  const state: ExceptionHarnessState = {
    loading: false,
    keyword: initialContext.keyword,
    level: initialContext.level
  };

  async function lookup(request: Promise<string>, snapshot: LogVisibleQueryContext) {
    const ticket = owner.begin(buildLogVisibleQueryContext(snapshot));
    state.loading = true;
    try {
      const keyword = await request;
      if (!owner.canCommit(ticket, buildLogVisibleQueryContext(live.current))) {
        return;
      }
      state.keyword = keyword;
      state.level = "";
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, lookup, invalidate: () => owner.invalidate() };
}

describe("log-request-lifecycle", () => {
  it("Q1 → Q2 stale response 不会覆盖最新日志", async () => {
    const q1 = buildLogServerQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100 });
    const q2 = buildLogServerQueryContext({ level: "WARN", logger: "report", keyword: "ack", limit: 80 });
    const harness = createLogHarness(q1);
    const request1 = createDeferred<string[]>();
    const request2 = createDeferred<string[]>();

    void harness.load(request1.promise, q1);
    await flushPromises();
    harness.live.current = q2;
    void harness.load(request2.promise, q2);
    await flushPromises();

    request2.resolve(["log-b"]);
    await flushPromises();
    request1.resolve(["log-a"]);
    await flushPromises();

    expect(harness.state.logs).toEqual(["log-b"]);
  });

  it("Q1 先 settle 不会关闭 Q2 loading", async () => {
    const q1 = buildLogServerQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100 });
    const q2 = buildLogServerQueryContext({ level: "WARN", logger: "report", keyword: "ack", limit: 80 });
    const harness = createLogHarness(q1);
    const request1 = createDeferred<string[]>();
    const request2 = createDeferred<string[]>();

    const load1 = harness.load(request1.promise, q1);
    await flushPromises();
    harness.live.current = q2;
    const load2 = harness.load(request2.promise, q2);
    await flushPromises();

    request1.resolve(["log-a"]);
    await load1;
    expect(harness.state.loading).toBe(true);

    request2.resolve(["log-b"]);
    await load2;
    expect(harness.state.loading).toBe(false);
    expect(harness.state.logs).toEqual(["log-b"]);
  });

  it("server context changed without Q2 时，旧 result/error 不提交但 loading 会释放", async () => {
    const q1 = buildLogServerQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100 });
    const q2 = buildLogServerQueryContext({ level: "WARN", logger: "core", keyword: "timeout", limit: 100 });
    const harness = createLogHarness(q1);
    const request1 = createDeferred<string[]>();

    void harness.load(request1.promise, q1);
    await flushPromises();
    harness.live.current = q2;
    request1.reject(new Error("q1 failed"));
    await flushPromises();

    expect(harness.state.logs).toEqual([]);
    expect(harness.state.error).toBeNull();
    expect(harness.state.loading).toBe(false);
    expect(harness.state.pending).toBeNull();
  });

  it("device/thread local filter 改变不会改变 server request context", () => {
    const leftVisible = buildLogVisibleQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100, deviceId: "dev-a", thread: "collector-1" });
    const rightVisible = buildLogVisibleQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100, deviceId: "dev-b", thread: "collector-2" });

    expect(isSameLogServerQueryContext(leftVisible, rightVisible)).toBe(true);
    expect(isSameLogVisibleQueryContext(leftVisible, rightVisible)).toBe(false);
  });

  it("timer 对相同 pending server query 会跳过，不堆积同 query", async () => {
    const context = buildLogServerQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100 });
    const harness = createLogHarness(context);
    const request = createDeferred<string[]>();

    void harness.load(request.promise, context);
    await flushPromises();

    const started = await harness.load(Promise.resolve(["timer"]), context, { fromTimer: true });

    expect(started).toBe(false);
    request.resolve(["manual"]);
    await flushPromises();
  });

  it("exception lookup pending 时用户改 query，旧 lookup 不覆盖用户输入且 loading 释放", async () => {
    const initial = buildLogVisibleQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100, deviceId: "dev-a", thread: "collector-1" });
    const harness = createExceptionHarness(initial);
    const lookup = createDeferred<string>();

    void harness.lookup(lookup.promise, initial);
    await flushPromises();
    harness.live.current = buildLogVisibleQueryContext({ level: "WARN", logger: "core", keyword: "manual", limit: 120, deviceId: "dev-b", thread: "collector-2" });
    harness.state.keyword = "manual";
    harness.state.level = "WARN";

    lookup.resolve("from-exception");
    await flushPromises();

    expect(harness.state.keyword).toBe("manual");
    expect(harness.state.level).toBe("WARN");
    expect(harness.state.loading).toBe(false);
  });

  it("unmount 后旧日志请求返回不会再提交状态", async () => {
    const context = buildLogServerQueryContext({ level: "ERROR", logger: "core", keyword: "timeout", limit: 100 });
    const harness = createLogHarness(context);
    const request = createDeferred<string[]>();

    void harness.load(request.promise, context);
    await flushPromises();
    harness.unmount();
    request.resolve(["stale"]);
    await flushPromises();

    expect(harness.state.logs).toEqual([]);
    expect(harness.state.loading).toBe(false);
  });
});
