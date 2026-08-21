import { describe, expect, it } from "vitest";

import { normalizeShadowHistoryRows, summarizeShadowState } from "./shadow-utils";

describe("shadow-utils", () => {
  it("归一化影子历史响应", () => {
    expect(normalizeShadowHistoryRows({ data: { records: [{ version: 2, operation: "UPDATE" }] } })).toEqual([{ version: 2, operation: "UPDATE" }]);
    expect(normalizeShadowHistoryRows([{ version: 1 }])).toEqual([{ version: 1 }]);
  });

  it("汇总影子当前态、期望态和 delta 摘要", () => {
    expect(summarizeShadowState({ data: { desired: { a: 1 }, reported: { b: 2 } } }, { c: 3 }, { d: 4, e: 5 }, [{ version: 1 }, { version: 2 }])).toEqual({
      currentCount: 2,
      desiredCount: 1,
      deltaCount: 2,
      historyCount: 2,
      currentText: "2 项",
      desiredText: "1 项",
      deltaText: "2 项"
    });
  });
});
