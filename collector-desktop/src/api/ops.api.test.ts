import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type {
  AlarmAcknowledgement,
  NetworkDiagnosticRequest,
  NetworkDiagnosticResult,
  OpsLogResponse
} from "@/types/ops";

const httpMocks = vi.hoisted(() => ({
  requestApiData: vi.fn()
}));

vi.mock("./http", () => ({
  requestApiData: httpMocks.requestApiData
}));

import { acknowledgeAlarm, diagnoseNetwork, getOpsLogs, normalizeLogRows, queryAlarmAcknowledgements } from "./ops.api";

beforeEach(() => {
  httpMocks.requestApiData.mockReset();
});

describe("ops.api", () => {
  it("getOpsLogs 走 requestApiData，并且只向后端发送真实支持的 query 字段", async () => {
    const response: OpsLogResponse = {
      totalBuffered: 2000,
      count: 1,
      items: [{
        timestamp: 1700000000000,
        level: "ERROR",
        logger: "com.wangbin.collector.core.ModbusCollector",
        thread: "collector-1",
        message: "设备 dev-1 点位 p1 读取超时"
      }]
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await getOpsLogs({
      level: "ERROR",
      logger: "collector",
      keyword: "超时",
      limit: 50,
      deviceId: "dev-1",
      thread: "collector-1",
      randomField: "ignored"
    } as never);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<OpsLogResponse>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/ops/logs",
      method: "GET",
      params: {
        level: "ERROR",
        logger: "collector",
        keyword: "超时",
        limit: 50
      }
    });
  });

  it("normalizeLogRows 以真实后端 items 为 primary contract，并保留 legacy fallback", () => {
    const primary = [{ message: "primary", thread: "collector-1" }];
    const legacy = [{ message: "legacy" }];

    expect(normalizeLogRows({ items: primary, logs: legacy })).toEqual(primary);
    expect(normalizeLogRows({ records: legacy })).toEqual(legacy);
  });

  it("queryAlarmAcknowledgements 走 requestApiData，并返回 typed acknowledgement map", async () => {
    const response: Record<string, AlarmAcknowledgement> = {
      "alarm-1": {
        alarmId: "alarm-1",
        operator: "token:ops",
        acknowledgedAt: 1700000000000,
        note: "现场已处理",
        idempotencyKey: "desktop-alarm-1"
      }
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await queryAlarmAcknowledgements(["alarm-1"]);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<Record<string, AlarmAcknowledgement>>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/ops/alarms/acknowledgements/query",
      method: "POST",
      data: { alarmIds: ["alarm-1"] }
    });
  });

  it("acknowledgeAlarm 走 requestApiData，并返回真实 AlarmAcknowledgement", async () => {
    const response: AlarmAcknowledgement = {
      alarmId: "alarm-1",
      operator: "token:ops",
      acknowledgedAt: 1700000000000,
      note: "现场已处理",
      idempotencyKey: "desktop-alarm-1"
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await acknowledgeAlarm("alarm-1", { note: "现场已处理", idempotencyKey: "desktop-alarm-1" } as never);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<AlarmAcknowledgement>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/ops/alarms/alarm-1/acknowledge",
      method: "POST",
      data: { note: "现场已处理", idempotencyKey: "desktop-alarm-1" }
    });
  });

  it("diagnoseNetwork 走 requestApiData，并返回真实 NetworkDiagnosticResult", async () => {
    const payload: NetworkDiagnosticRequest = {
      type: "TCP",
      deviceId: "dev-1",
      target: "127.0.0.1",
      port: 502,
      timeoutMs: 3000
    };
    const response: NetworkDiagnosticResult = {
      type: "TCP",
      deviceId: "dev-1",
      target: "127.0.0.1",
      resolvedAddress: "127.0.0.1",
      port: 502,
      reachable: true,
      durationMs: 12,
      message: "OK",
      details: ["connected"],
      completedAt: 1700000000000
    };
    httpMocks.requestApiData.mockResolvedValue(response);

    const result = await diagnoseNetwork(payload);

    expect(result).toEqual(response);
    expectTypeOf(result).toEqualTypeOf<NetworkDiagnosticResult>();
    expect(httpMocks.requestApiData).toHaveBeenCalledWith({
      url: "/api/ops/network/diagnose",
      method: "POST",
      data: payload
    });
  });
});
