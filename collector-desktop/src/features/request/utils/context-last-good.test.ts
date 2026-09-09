import { describe, expect, it } from "vitest";

import {
  buildContextualReadStatus,
  hasLastGoodForContext,
  shouldClearLastGoodForRequest
} from "./context-last-good";

interface QueryContext {
  id: string;
  limit: number;
}

function isSameQueryContext(left: QueryContext | null | undefined, right: QueryContext | null | undefined): boolean {
  return Boolean(left && right && left.id === right.id && left.limit === right.limit);
}

describe("context-last-good", () => {
  it("matches last-good only when the saved context equals the current request context", () => {
    const context = { id: "A", limit: 100 };

    expect(hasLastGoodForContext(context, { id: "A", limit: 100 }, isSameQueryContext)).toBe(true);
    expect(hasLastGoodForContext(context, { id: "B", limit: 100 }, isSameQueryContext)).toBe(false);
    expect(hasLastGoodForContext(null, { id: "A", limit: 100 }, isSameQueryContext)).toBe(false);
  });

  it("clears committed data for new context requests but not same-context refresh", () => {
    const context = { id: "A", limit: 100 };

    expect(shouldClearLastGoodForRequest(context, { id: "A", limit: 100 }, isSameQueryContext)).toBe(false);
    expect(shouldClearLastGoodForRequest(context, { id: "A", limit: 200 }, isSameQueryContext)).toBe(true);
  });

  it("builds loading, refreshing, stale, and initial-error status text", () => {
    const context = { id: "A", limit: 100 };
    const base = {
      lastSuccessfulContext: context,
      currentContext: context,
      isSameContext: isSameQueryContext,
      loadingText: "加载中",
      refreshingText: "刷新中 · 当前显示上次成功数据",
      staleText: "刷新失败 · 当前显示上次成功数据",
      initialErrorPrefix: "加载失败",
      lastSuccessAt: 1700000000000
    };

    expect(buildContextualReadStatus({ ...base, loading: true, error: "" })).toContain("刷新中");
    expect(buildContextualReadStatus({ ...base, loading: false, error: "timeout" })).toContain("当前显示上次成功数据");
    expect(buildContextualReadStatus({ ...base, currentContext: { id: "B", limit: 100 }, loading: true, error: "" })).toBe("加载中");
    expect(buildContextualReadStatus({ ...base, currentContext: { id: "B", limit: 100 }, loading: false, error: "timeout" })).toBe("加载失败：timeout");
  });
});
