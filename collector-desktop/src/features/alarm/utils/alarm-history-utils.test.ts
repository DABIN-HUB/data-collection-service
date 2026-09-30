import { describe, expect, it } from "vitest";

import { buildAlarmHistoryQuery, normalizeAlarmHistoryRows, summarizeAlarmHistory } from "./alarm-history-utils";

describe("alarm-history-utils", () => {
  it("归一化 AlarmHistoryDataResponse 中的 snake_case 告警行", () => {
    expect(normalizeAlarmHistoryRows({ data: [{ alarm_level: "CRITICAL", device_id: "dev-1", device_name: "锅炉", point_code: "temp", rule_id: "r1", alarm_content: "温度过高", event_ts: 1000 }] })).toEqual([
      expect.objectContaining({ level: "CRITICAL", deviceId: "dev-1", deviceName: "锅炉", pointCode: "temp", ruleId: "r1", content: "温度过高", timestamp: 1000 })
    ]);
  });

  it("兼容 records/items/data 嵌套响应", () => {
    expect(normalizeAlarmHistoryRows({ data: { records: [{ deviceId: "dev-2", level: "WARNING" }] } })).toHaveLength(1);
    expect(normalizeAlarmHistoryRows({ items: [{ deviceId: "dev-3" }] })[0].deviceId).toBe("dev-3");
  });

  it("构造告警历史查询参数并忽略空值", () => {
    expect(buildAlarmHistoryQuery({ level: "WARNING", keyword: "temp", hours: 24, limit: 50 }, 1_700_000_000_000)).toEqual({ level: "WARNING", pointCode: "temp", startTs: 1_699_913_600_000, endTs: 1_700_000_000_000, limit: 50 });
  });

  it("统计告警历史级别和确认状态", () => {
    expect(summarizeAlarmHistory([{ level: "CRITICAL" }, { level: "WARNING", acknowledged: true }, { level: "INFO" }])).toEqual({ total: 3, active: 2, acknowledged: 1, critical: 1, warning: 1, info: 1 });
  });
});
