import { describe, expect, it } from "vitest";

import {
  NETWORK_DIAGNOSTIC_TYPES,
  appendNetworkHistory,
  buildNetworkDiagnosticPayload,
  buildNetworkExportText,
  buildNetworkResultRows,
  normalizeNetworkDiagnosticResult,
  resolveNetworkTargetFromDevice
} from "./network-utils";

describe("network-utils", () => {
  it("提供后端支持的网络检测方式", () => {
    expect(NETWORK_DIAGNOSTIC_TYPES.map((item) => item.value)).toEqual(["PING", "TRACE", "TCP"]);
  });

  it("按检测方式构造后端 NetworkDiagnosticRequest payload", () => {
    expect(buildNetworkDiagnosticPayload({ type: "PING", deviceId: "dev-1", target: " 192.168.1.10 ", port: 502, timeoutMs: 50 })).toEqual({
      type: "PING",
      deviceId: "dev-1",
      target: "192.168.1.10",
      timeoutMs: 100
    });
    expect(buildNetworkDiagnosticPayload({ type: "TCP", target: "127.0.0.1", port: 9090, timeoutMs: 12000 })).toEqual({
      type: "TCP",
      target: "127.0.0.1",
      port: 9090,
      timeoutMs: 10000
    });
    expect(() => buildNetworkDiagnosticPayload({ type: "TCP", target: "127.0.0.1" })).toThrow("TCP 检测需要填写有效端口");
  });

  it("从设备配置带入网络目标和端口", () => {
    expect(resolveNetworkTargetFromDevice({ deviceId: "dev-1", deviceName: "泵站", ipAddress: "10.0.0.8", port: 502 })).toEqual({ deviceId: "dev-1", target: "10.0.0.8", port: 502 });
    expect(resolveNetworkTargetFromDevice(null)).toEqual({ deviceId: "", target: "127.0.0.1", port: undefined });
  });

  it("归一化网络检测结果并中文化失败原因", () => {
    expect(normalizeNetworkDiagnosticResult({ data: { type: "TCP", target: "10.0.0.8", port: 502, reachable: false, durationMs: 3000, message: "Connection refused", details: ["refused"], completedAt: 1700000000000 } })).toEqual(expect.objectContaining({
      type: "TCP",
      target: "10.0.0.8",
      port: 502,
      reachable: false,
      conclusionText: "不可达",
      reasonText: "TCP 连接被拒绝，请检查端口、服务监听和防火墙",
      details: ["refused"]
    }));
  });

  it("生成结果行、检测历史和导出文本", () => {
    const result = normalizeNetworkDiagnosticResult({ type: "PING", target: "127.0.0.1", resolvedAddress: "127.0.0.1", reachable: true, durationMs: 2, message: "OK", completedAt: 1700000000000 });
    expect(buildNetworkResultRows(result).map((row) => row.label)).toContain("检测结论");
    expect(appendNetworkHistory([], result, 2)).toEqual([result]);
    expect(buildNetworkExportText([result])).toContain("检测方式：PING");
  });
});
