import { describe, expect, it } from "vitest";

import {
  buildConnectionPayload,
  buildProtocolInitialModel,
  displayProtocolFieldLabel,
  extractProtocolModel,
  getPathValue,
  groupProtocolFields,
  isWideProtocolField,
  setPathValue,
  validateProtocolModel
} from "./protocol-form-utils";
import type { ProtocolFieldConfig } from "@/types/protocol";

const fields: ProtocolFieldConfig[] = [
  { name: "host", label: "IP地址", type: "text", required: true, group: "基础连接" },
  { name: "port", label: "端口", type: "number", required: true, defaultValue: "502", group: "基础连接" },
  { name: "keepAlive", label: "TCP KeepAlive", type: "boolean", defaultValue: "true", group: "高级参数" }
];

describe("protocol-form-utils", () => {
  it("根据协议字段默认值构建初始表单模型", () => {
    expect(buildProtocolInitialModel(fields)).toEqual({
      host: "",
      port: 502,
      keepAlive: true
    });
  });

  it("顶层三项超时读取 Ms 外部字段并提交为 Ms，扩展同名字段不改名", () => {
    const timeoutFields: ProtocolFieldConfig[] = ["connectTimeout", "readTimeout", "writeTimeout"].map((name) => ({ name, type: "integer", storage: "topLevel", defaultValue: "1000" }));
    const base = { connectTimeoutMs: 1201, readTimeoutMs: 2302, writeTimeoutMs: 3403, extJson: { readTimeout: 9, unknown: null } };
    const model = extractProtocolModel(timeoutFields, base);
    expect(model).toEqual({ connectTimeout: 1201, readTimeout: 2302, writeTimeout: 3403 });
    expect(buildConnectionPayload(timeoutFields, model, base)).toEqual(base);
    expect(extractProtocolModel(timeoutFields, { readTimeout: 42 }).readTimeout).toBe(42);
    expect(buildConnectionPayload([{ name: "readTimeout", storage: "extJson" }], { readTimeout: 7 }).extJson).toEqual({ readTimeout: 7 });
  });

  it("对象字段以 JSON 回显并解析，未知嵌套与 null 保留，非法形状阻断保存", () => {
    const objectFields: ProtocolFieldConfig[] = [{ name: "headers", label: "请求头", type: "object", storage: "extJson" }];
    const headers = { nested: [1, null, { value: "x" }], empty: "", optional: null };
    const base = { extJson: { headers, unknown: { flag: false } } };
    const model = extractProtocolModel(objectFields, base);
    expect(JSON.parse(String(model.headers))).toEqual(headers);
    expect(buildConnectionPayload(objectFields, model, base)).toEqual(base);
    expect(validateProtocolModel(objectFields, { headers: "[1]" })).toContain("请求头必须是 JSON 对象");
    expect(() => buildConnectionPayload(objectFields, { headers: "{" }, base)).toThrow();
    expect(buildConnectionPayload(objectFields, { headers: "" }, base).extJson).toEqual({ headers: null, unknown: { flag: false } });
  });

  it("integer 缺省为数值且不接受小数", () => {
    const integerFields: ProtocolFieldConfig[] = [{ name: "count", label: "数量", type: "integer", defaultValue: "3" }];
    expect(buildProtocolInitialModel(integerFields)).toEqual({ count: 3 });
    expect(validateProtocolModel(integerFields, { count: 1.5 })).toContain("数量必须是整数");
  });

  it("按字段分组且保留原始顺序", () => {
    expect(groupProtocolFields(fields).map((group) => [group.name, group.fields.map((field) => field.name)])).toEqual([
      ["基础连接", ["host", "port"]],
      ["高级参数", ["keepAlive"]]
    ]);
  });

  it("返回缺失必填字段的中文校验提示", () => {
    expect(validateProtocolModel(fields, { port: 502 })).toEqual(["IP地址不能为空"]);
  });

  it("返回数字和枚举字段的中文校验提示", () => {
    expect(validateProtocolModel([
      { name: "port", label: "端口", type: "number" },
      { name: "byteOrder", label: "字节序", options: ["BIG_ENDIAN", "LITTLE_ENDIAN"] }
    ], { port: "abc", byteOrder: "UNKNOWN" })).toEqual([
      "端口必须是数字",
      "字节序必须是允许的选项"
    ]);
  });

  it("按 topLevel 和 extJson storage 从连接配置提取表单值", () => {
    const model = extractProtocolModel([
      { name: "host", label: "主机", storage: "topLevel" },
      { name: "slaveId", label: "站号", storage: "extJson" }
    ], { host: "192.168.1.10", extJson: { slaveId: 2 } });

    expect(model).toEqual({ host: "192.168.1.10", slaveId: 2 });
  });

  it("按 storage 生成连接保存 payload", () => {
    const payload = buildConnectionPayload([
      { name: "host", label: "主机", storage: "topLevel" },
      { name: "slaveId", label: "站号", storage: "extJson" }
    ], { host: "192.168.1.20", slaveId: 3 }, { deviceId: "dev-1", connectionType: "MODBUS_TCP", extJson: { old: true } });

    expect(payload).toEqual({
      deviceId: "dev-1",
      connectionType: "MODBUS_TCP",
      host: "192.168.1.20",
      extJson: { old: true, slaveId: 3 }
    });
  });

  it("支持点号路径读写", () => {
    const target: Record<string, unknown> = {};
    setPathValue(target, "additionalConfig.driverDataType", "REAL");
    expect(target).toEqual({ additionalConfig: { driverDataType: "REAL" } });
    expect(getPathValue(target, "additionalConfig.driverDataType")).toBe("REAL");
  });

  it("为 Modbus 连接参数提供可读中文 label 和稳定宽字段分类", () => {
    const modbusFields: ProtocolFieldConfig[] = [
      { name: "plc4xConnectionString", label: "PLC4X connection string", type: "string" },
      { name: "pingAddress", label: "PLC4X ping address", type: "string" },
      { name: "maxRegistersPerRequest", label: "Max registers per request", type: "number" },
      { name: "maxCoilsPerRequest", label: "Max coils per request", type: "number" },
      { name: "readTimeout", label: "Read timeout (ms)", type: "number" },
      { name: "timeout", label: "Protocol timeout (ms)", type: "number" }
    ];

    expect(modbusFields.map(displayProtocolFieldLabel)).toEqual([
      "PLC4X连接串",
      "PLC4X Ping",
      "最大寄存器数",
      "最大线圈数",
      "读取超时",
      "协议超时"
    ]);
    expect(isWideProtocolField(modbusFields[0])).toBe(true);
    expect(isWideProtocolField(modbusFields[1])).toBe(true);
    expect(isWideProtocolField(modbusFields[2])).toBe(false);
  });
});
