import { describe, expect, it } from "vitest";

import {
  buildBatchWriteTemplate,
  buildCommandTemplate,
  buildSinglePointWritePayload,
  normalizeDeviceOptions,
  normalizeHistoryRows,
  parseJsonOrThrow
} from "./runtime-utils";

describe("runtime-utils", () => {
  it("从多种配置响应归一化设备选项", () => {
    expect(normalizeDeviceOptions({ devices: [{ deviceId: "d1", deviceName: "设备1" }] })).toEqual([{ id: "d1", name: "设备1", protocol: "", host: "", port: undefined }]);
    expect(normalizeDeviceOptions([{ id: "d2", name: "设备2", protocolType: "MQTT", host: "127.0.0.1", port: 1883 }])).toEqual([{ id: "d2", name: "设备2", protocol: "MQTT", host: "127.0.0.1", port: 1883 }]);
  });

  it("构造单点写入 payload", () => {
    expect(buildSinglePointWritePayload("p1", "12.5", "FLOAT")).toEqual({ pointRef: "p1", value: 12.5, dataType: "FLOAT" });
    expect(buildSinglePointWritePayload("flag", "true", "BOOLEAN")).toEqual({ pointRef: "flag", value: true, dataType: "BOOLEAN" });
  });

  it("提供批量写入和命令模板", () => {
    expect(buildBatchWriteTemplate()).toHaveProperty("values");
    expect(buildCommandTemplate("readStatus")).toEqual({ command: "readStatus", params: {} });
  });

  it("归一化历史数据响应", () => {
    expect(normalizeHistoryRows({ records: [{ timestamp: 1, value: 10 }] })).toEqual([{ timestamp: 1, value: 10 }]);
    expect(normalizeHistoryRows([{ time: 2, value: 20 }])).toEqual([{ time: 2, value: 20 }]);
    expect(normalizeHistoryRows({ data: { records: [{ timestamp: 3, value: 30 }] } })).toEqual([{ timestamp: 3, value: 30 }]);
  });

  it("解析 JSON 并保留中文错误标签", () => {
    expect(parseJsonOrThrow('{"a":1}', "测试 JSON")).toEqual({ a: 1 });
    expect(() => parseJsonOrThrow('{', "测试 JSON")).toThrow("测试 JSON 格式错误");
  });
});
