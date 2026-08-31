import { describe, expect, it } from "vitest";

import { normalizeHistoryRows } from "./history-data-utils";

describe("history-data-utils", () => {
  it("归一化历史数据响应", () => {
    expect(normalizeHistoryRows({ records: [{ timestamp: 1, value: 10 }] })).toEqual([{ timestamp: 1, value: 10 }]);
    expect(normalizeHistoryRows([{ time: 2, value: 20 }])).toEqual([{ time: 2, value: 20 }]);
    expect(normalizeHistoryRows({ data: { records: [{ timestamp: 3, value: 30 }] } })).toEqual([{ timestamp: 3, value: 30 }]);
  });
});
