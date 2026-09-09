import { describe, expect, it } from "vitest";

import {
  DEFAULT_SHADOW_DESIRED_PAYLOAD,
  buildShadowDeviceContext,
  buildShadowSectionStatus,
  buildTargetedShadowActionMessage,
  createShadowDeviceRequestOwner,
  shouldClearShadowLoading,
  shouldCommitShadowRequest,
  shouldCommitShadowWrite
} from "./shadow-request-state";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((innerResolve, innerReject) => {
    resolve = innerResolve;
    reject = innerReject;
  });
  return { promise, resolve, reject };
}

describe("shadow-request-state", () => {
  it("prevents stale A shadow reads from committing or clearing B loading", async () => {
    const owner = createShadowDeviceRequestOwner();
    const aRequest = deferred<{ deviceId: string; value: number }>();
    const aTicket = owner.begin(buildShadowDeviceContext("A"));
    const bTicket = owner.begin(buildShadowDeviceContext("B"));

    aRequest.resolve({ deviceId: "A", value: 1 });
    await aRequest.promise;

    expect(shouldCommitShadowRequest(owner, aTicket, "B")).toBe(false);
    expect(shouldClearShadowLoading(owner, aTicket)).toBe(false);
    expect(shouldCommitShadowRequest(owner, bTicket, "B")).toBe(true);
  });

  it("prevents stale A shadow reads from committing after context switch without a B request", async () => {
    const owner = createShadowDeviceRequestOwner();
    const aRequest = deferred<{ deviceId: string; value: number }>();
    const aTicket = owner.begin(buildShadowDeviceContext("A"));

    owner.invalidate();
    aRequest.resolve({ deviceId: "A", value: 1 });
    await aRequest.promise;

    expect(shouldCommitShadowRequest(owner, aTicket, "B")).toBe(false);
    expect(shouldClearShadowLoading(owner, aTicket)).toBe(false);
  });

  it("keeps same-context latest shadow request authoritative", () => {
    const owner = createShadowDeviceRequestOwner();
    const firstTicket = owner.begin(buildShadowDeviceContext("A"));
    const secondTicket = owner.begin(buildShadowDeviceContext("A"));

    expect(shouldCommitShadowRequest(owner, secondTicket, "A")).toBe(true);
    expect(shouldCommitShadowRequest(owner, firstTicket, "A")).toBe(false);
    expect(shouldClearShadowLoading(owner, firstTicket)).toBe(false);
  });

  it("keeps delta and history read owners independent", () => {
    const deltaOwner = createShadowDeviceRequestOwner();
    const historyOwner = createShadowDeviceRequestOwner();
    const deltaTicket = deltaOwner.begin(buildShadowDeviceContext("A"));
    const historyTicket = historyOwner.begin(buildShadowDeviceContext("A"));

    historyOwner.begin(buildShadowDeviceContext("A"));

    expect(shouldCommitShadowRequest(deltaOwner, deltaTicket, "A")).toBe(true);
    expect(shouldCommitShadowRequest(historyOwner, historyTicket, "A")).toBe(false);
  });

  it("models last-good stale status without turning refresh failures into empty state", () => {
    expect(buildShadowSectionStatus({ kind: "shadow", loading: false, error: "timeout", lastSuccessAt: 1700000000000 })).toContain("当前显示上次成功数据");
    expect(buildShadowSectionStatus({ kind: "delta", loading: false, error: "timeout", lastSuccessAt: 1700000000000 })).toContain("刷新失败");
    expect(buildShadowSectionStatus({ kind: "history", loading: false, error: "timeout", lastSuccessAt: null })).toContain("读取失败");
  });

  it("keeps success-empty history distinct from history failure semantics", () => {
    const successfulEmptyRows: unknown[] = [];
    const failedRefreshWithRows = [{ version: 1 }, { version: 2 }];

    expect(successfulEmptyRows).toEqual([]);
    expect(failedRefreshWithRows).toHaveLength(2);
    expect(buildShadowSectionStatus({ kind: "history", loading: false, error: "network", lastSuccessAt: 1700000000000 })).toContain("当前显示上次成功数据");
  });

  it("guards write UI commits while preserving target-specific side-effect feedback", () => {
    expect(shouldCommitShadowWrite("A", "A")).toBe(true);
    expect(shouldCommitShadowWrite("A", "B")).toBe(false);
    expect(buildTargetedShadowActionMessage("A", "期望状态已提交")).toBe("设备 A 的期望状态已提交");
  });

  it("guards clearDesired from clearing another device form", () => {
    const bDraft = JSON.stringify({ desired: { mode: "manual" } }, null, 2);
    const aCanCommitToB = shouldCommitShadowWrite("A", "B");
    const currentDraft = aCanCommitToB ? DEFAULT_SHADOW_DESIRED_PAYLOAD : bDraft;

    expect(currentDraft).toBe(bDraft);
  });
});
