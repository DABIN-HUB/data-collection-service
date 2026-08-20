import { describe, expect, it } from "vitest";

import { normalizeShadowHistoryRows } from "./shadow-utils";

describe("shadow-utils", () => {
  it("归一化影子历史响应", () => {
    expect(normalizeShadowHistoryRows({ data: { records: [{ version: 2, operation: "UPDATE" }] } })).toEqual([{ version: 2, operation: "UPDATE" }]);
    expect(normalizeShadowHistoryRows([{ version: 1 }])).toEqual([{ version: 1 }]);
  });
});
