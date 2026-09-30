import { describe, expect, it } from "vitest";

import {
  buildActionExecutionTarget,
  buildActionExecutionView,
  safeActionErrorMessage,
  summarizePayload
} from "./action-result-context";

describe("action-result-context", () => {
  it("captures immutable submission target metadata", () => {
    const target = buildActionExecutionTarget({
      target: "device-a",
      deviceId: "device-a",
      action: "single-write",
      pointRef: "a-p1",
      payloadSummary: "value=12",
      submittedAt: 1700000000000
    });

    const view = buildActionExecutionView(target, { status: "ok" }, undefined, 1700000000500);
    target.deviceId = "device-b";
    target.pointRef = "b-p1";

    expect(view.target).toEqual({
      target: "device-a",
      deviceId: "device-a",
      action: "single-write",
      pointRef: "a-p1",
      payloadSummary: "value=12",
      submittedAt: 1700000000000
    });
    expect(view.completedAt).toBe(1700000000500);
  });

  it("keeps submitted payload summary stable when later form text changes", () => {
    const submitted = { values: { "a-p1": 1 } };
    const summary = summarizePayload(submitted);
    submitted.values["a-p1"] = 2;

    expect(summary).toBe('{"values":{"a-p1":1}}');
  });

  it("stores failure attribution on the submitted target", () => {
    const target = buildActionExecutionTarget({ target: "device-a", action: "command", submittedAt: 1 });
    const view = buildActionExecutionView(target, undefined, safeActionErrorMessage(new Error("failed"), "fallback"), 2);

    expect(view.target.target).toBe("device-a");
    expect(view.error).toBe("failed");
    expect(view.result).toBeUndefined();
  });
});
