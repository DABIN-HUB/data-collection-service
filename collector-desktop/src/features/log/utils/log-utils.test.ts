import { describe, expect, it } from "vitest";

import {
  buildLogExportFilename,
  buildLogQueryParams,
  buildLogSearchFromException,
  exportLogRowsAsJson,
  exportLogRowsAsText,
  filterLogRows,
  summarizeLogRows
} from "./log-utils";

const rows = [
  { timestamp: 1700000000000, level: "ERROR", logger: "com.wangbin.collector.core.ModbusCollector", thread: "collector-1", message: "设备 dev-1 点位 p1 读取超时" },
  { timestamp: 1700000001000, level: "INFO", logger: "com.wangbin.collector.api.OpsController", thread: "http-nio-1", message: "查询日志成功" },
  { timestamp: 1700000002000, level: "WARN", logger: "com.wangbin.collector.report.CloudReport", thread: "report-1", message: "设备 dev-2 ACK 超时" }
];

describe("log-utils", () => {
  it("构造后端日志查询参数，只发送后端真实支持的字段", () => {
    expect(buildLogQueryParams({ level: "ERROR", logger: "collector", keyword: "超时", deviceId: "dev-1", thread: "collector-1", limit: 500 })).toEqual({
      level: "ERROR",
      logger: "collector",
      keyword: "超时 dev-1 collector-1",
      limit: 500
    });
  });

  it("按级别、设备、logger、线程和关键字进行前端精筛", () => {
    expect(filterLogRows(rows, { level: "ERROR", deviceId: "dev-1", logger: "modbus", thread: "collector", keyword: "超时" })).toEqual([rows[0]]);
    expect(filterLogRows(rows, { level: "WARN", deviceId: "dev-1" })).toEqual([]);
  });

  it("导出当前日志为 JSON 和文本", () => {
    expect(exportLogRowsAsJson(rows.slice(0, 1))).toContain('"level": "ERROR"');
    expect(exportLogRowsAsText(rows.slice(0, 1))).toContain("ERROR\tcom.wangbin.collector.core.ModbusCollector\tcollector-1\t设备 dev-1 点位 p1 读取超时");
  });

  it("统计错误、警告和日志器数量", () => {
    expect(summarizeLogRows(rows)).toEqual({ total: 3, error: 1, warn: 1, loggerCount: 3, threadCount: 3 });
  });

  it("从最近异常构造日志搜索关键词和导出文件名", () => {
    expect(buildLogSearchFromException({ deviceId: "dev-1", pointId: "p1", category: "TimeoutException", message: "读取超时" })).toBe("dev-1 p1 TimeoutException 读取超时");
    expect(buildLogExportFilename("json", 1700000000000)).toBe("collector-logs-2023-11-14T22-13-20-000Z.json");
  });
});
