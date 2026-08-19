import { describe, expect, it } from "vitest";

import {
  buildConnectionPayload,
  buildProtocolInitialModel,
  extractProtocolModel,
  getPathValue,
  groupProtocolFields,
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
});
