import { describe, expect, it } from "vitest";

import {
  buildBatchControlTemplate,
  buildControlActionTarget,
  buildCommandTemplate,
  buildSinglePointControlPayload,
  formatControlJson,
  parseControlJson,
  parseControlValue
} from "./control-utils";

describe("control-utils", () => {
  it("按当前手动控制语义转换单点写入值", () => {
    expect(parseControlValue("true", "BOOLEAN")).toBe(true);
    expect(parseControlValue("1", "BOOLEAN")).toBe(true);
    expect(parseControlValue("是", "BOOLEAN")).toBe(true);
    expect(parseControlValue("false", "BOOLEAN")).toBe(false);
    expect(parseControlValue("12", "INT")).toBe(12);
    expect(parseControlValue("12.5", "FLOAT")).toBe(12.5);
    expect(parseControlValue("bad", "DOUBLE")).toBe("bad");
    expect(parseControlValue("hello", "STRING")).toBe("hello");
  });

  it("构造单点写入 payload 时不把 pointRef 放进请求体", () => {
    expect(buildSinglePointControlPayload("12", "INT")).toEqual({ value: 12 });
  });

  it("保持当前协议命令模板，并按真实 PointWriteRequest.values 生成批量写入模板", () => {
    expect(buildBatchControlTemplate()).toEqual({ values: { point_001: 1 } });
    expect(buildCommandTemplate()).toEqual({ command: "custom", params: {} });
  });

  it("解析控制 JSON 并保留中文错误标签", () => {
    expect(parseControlJson('{"values":{}}', "批量写入 JSON")).toEqual({ values: {} });
    expect(parseControlJson("", "批量写入 JSON")).toEqual({});
    expect(() => parseControlJson("{", "批量写入 JSON")).toThrow("批量写入 JSON 格式错误");
  });

  it("格式化控制 JSON", () => {
    expect(formatControlJson({ command: "custom", params: {} })).toContain("custom");
  });

  it("单点写入 capture 设备、点位和提交 payload snapshot", () => {
    const payload = buildSinglePointControlPayload("12", "INT");
    const target = buildControlActionTarget({ deviceId: "device-a", action: "single-write", pointRef: "a-p1", payload, submittedAt: 1700000000000 });
    payload.value = 13;

    expect(target.deviceId).toBe("device-a");
    expect(target.pointRef).toBe("a-p1");
    expect(target.payloadSummary).toBe('{"value":12}');
    expect(target.submittedAt).toBe(1700000000000);
  });

  it.each([
    ["batch-write", { values: { "a-p1": 1 } }],
    ["command", { command: "restart", params: { mode: "safe" } }]
  ] as const)("%s capture target snapshot", (action, payload) => {
    const target = buildControlActionTarget({ deviceId: "device-a", action, payload, submittedAt: 1700000000000 });

    expect(target).toEqual(expect.objectContaining({ target: "device-a", deviceId: "device-a", action, submittedAt: 1700000000000 }));
    expect(target.payloadSummary).toContain(action === "batch-write" ? "a-p1" : "restart");
  });
});
