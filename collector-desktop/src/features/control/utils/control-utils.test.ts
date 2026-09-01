import { describe, expect, it } from "vitest";

import {
  buildBatchControlTemplate,
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
    expect(buildSinglePointControlPayload("12", "INT")).toEqual({ value: 12, dataType: "INT" });
  });

  it("保持当前批量写入和协议命令模板结构", () => {
    expect(buildBatchControlTemplate()).toEqual({ points: [{ pointId: "point_001", value: 1, dataType: "INT" }] });
    expect(buildCommandTemplate()).toEqual({ command: "custom", params: {} });
  });

  it("解析控制 JSON 并保留中文错误标签", () => {
    expect(parseControlJson('{"points":[]}', "批量写入 JSON")).toEqual({ points: [] });
    expect(parseControlJson("", "批量写入 JSON")).toEqual({});
    expect(() => parseControlJson("{", "批量写入 JSON")).toThrow("批量写入 JSON 格式错误");
  });

  it("格式化控制 JSON", () => {
    expect(formatControlJson({ command: "custom", params: {} })).toContain("custom");
  });
});
