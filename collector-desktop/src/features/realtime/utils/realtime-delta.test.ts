import { describe, expect, it } from "vitest";

import type { CompactRealtimeDeltaResponse, RealtimePointRow } from "@/types/monitor";
import {
  REALTIME_SAFETY_FULL_DELTA_CYCLES,
  applyFullRealtimeSnapshot,
  applyRealtimeDelta,
  buildRealtimeRowIdentityIndex,
  emptyRealtimeDeltaState,
  shouldRequestFullRealtimeSnapshot
} from "./realtime-delta";

describe("realtime-delta", () => {
  it("initial/manual/device-change 使用 full，timer 有 cursor 时使用 delta", () => {
    const emptyState = emptyRealtimeDeltaState();
    const deltaState = {
      ...emptyState,
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 }
    };

    expect(shouldRequestFullRealtimeSnapshot("init", deltaState)).toBe(true);
    expect(shouldRequestFullRealtimeSnapshot("manual", deltaState)).toBe(true);
    expect(shouldRequestFullRealtimeSnapshot("device-change", deltaState)).toBe(true);
    expect(shouldRequestFullRealtimeSnapshot("timer", emptyState)).toBe(true);
    expect(shouldRequestFullRealtimeSnapshot("timer", deltaState)).toBe(false);
  });

  it("12 次成功 delta 后下一次 timer 触发 periodic full", () => {
    expect(shouldRequestFullRealtimeSnapshot("timer", {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: REALTIME_SAFETY_FULL_DELTA_CYCLES
    })).toBe(true);
  });

  it("full snapshot 设置 cursor 并构建一次 row identity index", () => {
    const first = row("dev-a", "p1", 1);
    const second = row("dev-a", "p2", 2);

    const result = applyFullRealtimeSnapshot({
      status: "success",
      snapshotId: "snapshot-1",
      configEpoch: 2,
      revision: 10,
      deviceId: "dev-a",
      dataCount: 2,
      rows: [first, second],
      timestamp: 1000
    }, { deviceId: "dev-a" });

    expect(result.rows).toBeDefined();
    expect(result.rows[0]).toBe(first);
    expect(result.cursor).toEqual({ snapshotId: "snapshot-1", configEpoch: 2, revision: 10 });
    expect(result.rowIdentityIndex.size).toBe(2);
    expect(result.rowIdentityIndex.get("dev-a\u0000p1")).toBe(0);
  });

  it("empty delta 不替换 rows array 或 row references，只推进 cursor", () => {
    const first = row("dev-a", "p1", 1);
    const rows = [first];
    const state = {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 2,
      rowIdentityIndex: buildRealtimeRowIdentityIndex(rows)
    };

    const result = applyRealtimeDelta(rows, state, delta({ revision: 11, changedCount: 0, rows: [] }), { deviceId: "dev-a" });

    expect(result.needsFullResync).toBe(false);
    expect(rows).toBe(rows);
    expect(rows[0]).toBe(first);
    expect(result.cursor).toEqual({ snapshotId: "snapshot-1", configEpoch: 1, revision: 11 });
    expect(result.successfulDeltaCycles).toBe(3);
  });

  it("changed delta 使用 index 替换对应 slot，未改变 row 保留引用", () => {
    const first = row("dev-a", "p1", 1);
    const second = row("dev-a", "p2", 2);
    const changed = row("dev-a", "p2", 20);
    const rows = [first, second];
    const state = {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 0,
      rowIdentityIndex: buildRealtimeRowIdentityIndex(rows)
    };

    const result = applyRealtimeDelta(rows, state, delta({ revision: 11, changedCount: 1, rows: [changed] }), { deviceId: "dev-a" });

    expect(result.needsFullResync).toBe(false);
    expect(rows[0]).toBe(first);
    expect(rows[1]).toBe(changed);
  });

  it("unknown delta row 请求 full resync 而不是 push", () => {
    const rows = [row("dev-a", "p1", 1)];
    const state = {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 0,
      rowIdentityIndex: buildRealtimeRowIdentityIndex(rows)
    };

    const result = applyRealtimeDelta(rows, state, delta({ revision: 11, changedCount: 1, rows: [row("dev-a", "missing", 2)] }), { deviceId: "dev-a" });

    expect(result.needsFullResync).toBe(true);
    expect(result.resetReason).toBe("ROW_IDENTITY_MISMATCH");
    expect(rows).toHaveLength(1);
  });

  it("未知增量行不会提交前序局部替换", () => {
    const rows = [row("dev-a", "p1", 1), row("dev-a", "p2", 2)];
    const originalFirst = rows[0];
    const state = {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 0,
      rowIdentityIndex: buildRealtimeRowIdentityIndex(rows)
    };

    const result = applyRealtimeDelta(rows, state, delta({
      revision: 11,
      changedCount: 2,
      rows: [row("dev-a", "p1", 10), row("dev-a", "missing", 20)]
    }), { deviceId: "dev-a" });

    expect(result.needsFullResync).toBe(true);
    expect(rows[0]).toBe(originalFirst);
  });

  it("server resetRequired 直接请求 full resync", () => {
    const rows = [row("dev-a", "p1", 1)];
    const state = {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 0,
      rowIdentityIndex: buildRealtimeRowIdentityIndex(rows)
    };

    const result = applyRealtimeDelta(rows, state, {
      status: "success",
      resetRequired: true,
      resetReason: "CONFIG_CHANGED",
      changedCount: 0,
      rows: [],
      snapshotId: "snapshot-2",
      configEpoch: 2,
      revision: 11
    }, { deviceId: "dev-a" });

    expect(result.needsFullResync).toBe(true);
    expect(result.resetReason).toBe("CONFIG_CHANGED");
    expect(rows[0].value).toBe(1);
  });

  it("100k base + 2 changed 的结构性复杂度只按 changed rows 查 Map", () => {
    const rows: RealtimePointRow[] = Array.from({ length: 100_000 }, (_, index) => row("dev-a", `p${index}`, index));
    const index = buildRealtimeRowIdentityIndex(rows);
    const changedA = row("dev-a", "p10", 10_000);
    const changedB = row("dev-a", "p99999", 99_999);

    applyRealtimeDelta(rows, {
      cursor: { snapshotId: "snapshot-1", configEpoch: 1, revision: 10 },
      successfulDeltaCycles: 0,
      rowIdentityIndex: index
    }, delta({ revision: 11, changedCount: 2, rows: [changedA, changedB] }), { deviceId: "dev-a" });

    expect(rows[10]).toBe(changedA);
    expect(rows[99_999]).toBe(changedB);
    expect(index.size).toBe(100_000);
  });
});

function row(deviceId: string, pointId: string, value: number): RealtimePointRow {
  return { deviceId, pointId, pointCode: pointId, value, qualityAvailable: false };
}

function delta(overrides: Partial<CompactRealtimeDeltaResponse>): CompactRealtimeDeltaResponse {
  return {
    status: "success",
    resetRequired: false,
    snapshotId: "snapshot-1",
    configEpoch: 1,
    fromRevision: 10,
    revision: 11,
    changedCount: 0,
    rows: [],
    ...overrides
  };
}
