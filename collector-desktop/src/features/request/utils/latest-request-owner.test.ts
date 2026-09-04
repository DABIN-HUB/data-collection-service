import { describe, expect, it } from "vitest";

import {
  createLatestRequestOwner,
  shouldDisableLatestRequestSubmit,
  type LatestRequestContextComparator
} from "./latest-request-owner";

interface QueryContext {
  deviceId: string;
  pointId?: string;
}

const isSameQueryContext: LatestRequestContextComparator<QueryContext> = (left, right) => {
  if (!left || !right) {
    return false;
  }
  return left.deviceId === right.deviceId && (left.pointId || "") === (right.pointId || "");
};

describe("latest-request-owner", () => {
  it("context 改变但没有新 request 时，旧 ticket 仍是 latest 但不能提交", () => {
    const owner = createLatestRequestOwner(isSameQueryContext);
    const ticket = owner.begin({ deviceId: "device-a", pointId: "point-1" });

    expect(owner.isLatest(ticket)).toBe(true);
    expect(owner.canCommit(ticket, { deviceId: "device-b", pointId: "point-2" })).toBe(false);
  });

  it("A → B 后只有 B 仍拥有 latest generation", () => {
    const owner = createLatestRequestOwner(isSameQueryContext);
    const ticketA = owner.begin({ deviceId: "device-a", pointId: "point-1" });
    const ticketB = owner.begin({ deviceId: "device-b", pointId: "point-2" });

    expect(owner.isLatest(ticketA)).toBe(false);
    expect(owner.canCommit(ticketA, { deviceId: "device-b", pointId: "point-2" })).toBe(false);
    expect(owner.isLatest(ticketB)).toBe(true);
    expect(owner.canCommit(ticketB, { deviceId: "device-b", pointId: "point-2" })).toBe(true);
  });

  it("invalidate 后 pending ticket 不能再提交", () => {
    const owner = createLatestRequestOwner(isSameQueryContext);
    const ticket = owner.begin({ deviceId: "device-a", pointId: "point-1" });

    owner.invalidate();

    expect(owner.isLatest(ticket)).toBe(false);
    expect(owner.canCommit(ticket, { deviceId: "device-a", pointId: "point-1" })).toBe(false);
  });

  it("changed context 不阻塞新提交，但相同 pending context 仍可阻止重复提交", () => {
    const pending = { deviceId: "device-a", pointId: "point-1" } satisfies QueryContext;

    expect(shouldDisableLatestRequestSubmit(true, pending, { deviceId: "device-a", pointId: "point-1" }, isSameQueryContext)).toBe(true);
    expect(shouldDisableLatestRequestSubmit(true, pending, { deviceId: "device-b", pointId: "point-2" }, isSameQueryContext)).toBe(false);
    expect(shouldDisableLatestRequestSubmit(false, pending, { deviceId: "device-a", pointId: "point-1" }, isSameQueryContext)).toBe(false);
  });
});
