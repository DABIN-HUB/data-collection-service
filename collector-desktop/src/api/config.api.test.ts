import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import type { ApiResult } from "@/types/api";
import type {
  ConfigDiffResponse,
  ConfigExportResponse,
  ConfigImportResult,
  ConfigSummaryResponse,
  ConfigSyncStatusResponse,
  DeviceConnectionConfigResponse,
  DeviceIdResponse,
  LocalDeviceConfigResponse
} from "@/types/config";
import type { ConfigDeviceListResponse } from "@/types/device";
import type { DevicePointConfigResponse } from "@/types/point";

const httpMocks = vi.hoisted(() => ({
  request: vi.fn(),
  requestApiData: vi.fn(),
  requestEnvelope: vi.fn()
}));

vi.mock("./http", () => ({
  request: httpMocks.request,
  requestApiData: httpMocks.requestApiData,
  requestEnvelope: httpMocks.requestEnvelope
}));

import {
  clearDeviceConfig,
  createLocalDevice,
  deleteLocalDevice,
  exportConfigs,
  getConfigDevices,
  getConfigSummary,
  getConfigSyncStatus,
  getDeviceConfig,
  getDeviceConnection,
  getDeviceDiff,
  getDevicePointsConfig,
  getLocalDevice,
  importConfigs,
  refreshDeviceConfig,
  triggerFullConfigSync,
  triggerPartialConfigSync,
  updateDeviceConfig,
  updateDeviceConnection,
  updateDevicePointsConfig,
  updateLocalDevice
} from "./config.api";

beforeEach(() => {
  httpMocks.request.mockReset();
  httpMocks.requestApiData.mockReset();
  httpMocks.requestEnvelope.mockReset();
});

describe("config.api", () => {
  it("稳定 ConfigController 响应均走 ApiResult.data boundary 并返回 typed payload", async () => {
    const summary: ConfigSummaryResponse = { cacheStats: { deviceCount: 2, pointCount: 6, connectionCount: 2, contextCount: 2 }, lastSyncTime: 1000, nextSyncTime: 2000, syncInterval: 1000, serviceId: "svc-1", listenerCount: 3 };
    const devices: ConfigDeviceListResponse = { devices: [{ deviceId: "device-1", deviceName: "设备一", protocolType: "MODBUS_TCP" }], count: 1 };
    const localDevice: LocalDeviceConfigResponse = { deviceId: "local-1", configSource: "local", temporaryConfig: true, bundle: { device: { deviceId: "local-1", deviceName: "本地设备" }, connection: { deviceId: "local-1", connectionType: "MODBUS_TCP", host: "127.0.0.1", port: 502 }, points: [{ pointId: "point-1", pointCode: "temperature" }] }, pointCount: 1 };
    const deviceDetail = { deviceId: "device-1", local: { deviceId: "device-1" }, remote: { deviceId: "device-1" }, inSync: true };
    const points: DevicePointConfigResponse = { deviceId: "device-1", count: 1, points: [{ pointId: "point-1" }] };
    const connection: DeviceConnectionConfigResponse = { deviceId: "device-1", connection: { deviceId: "device-1", connectionType: "MODBUS_TCP", host: "127.0.0.1", port: 502, status: "CONNECTED", extJson: { unitId: 1 } } };
    const diff: ConfigDiffResponse = { deviceChanged: false, connectionChanged: true, missingPointCodes: [], extraPointCodes: ["legacy"], changedPointCodes: ["temperature"] };
    const syncStatus: ConfigSyncStatusResponse = { serviceId: "svc-1", lastSyncTime: 1000, syncInterval: 30000, listenerCount: 2, consecutiveFailures: 0, lastFailureTime: null, sourceVersion: "v1", snapshotDeviceCount: 1 };
    const exported: ConfigExportResponse = { bundles: [localDevice.bundle!] };
    const imported: ConfigImportResult = { total: 1, success: 1, failedDevices: [] };
    const deviceIdResponse: DeviceIdResponse = { deviceId: "device-1", count: 1 };
    const localPayload = {
      device: { deviceId: "local-1", deviceName: "本地设备" },
      connection: { deviceId: "local-1", connectionType: "MODBUS_TCP" },
      points: [],
      overwrite: true,
      startAfterSave: false
    };
    const importPayload = { bundles: [], reloadAfterImport: true };

    const calls = [
      { run: () => getConfigSummary(), url: "/api/config/summary", fixture: summary },
      { run: () => getConfigDevices(), url: "/api/config/devices", fixture: devices },
      { run: () => createLocalDevice(localPayload), url: "/api/config/local/devices", method: "POST", data: localPayload, fixture: localDevice },
      { run: () => getLocalDevice("local-1"), url: "/api/config/local/device/local-1", fixture: localDevice },
      { run: () => updateLocalDevice("local-1", localPayload), url: "/api/config/local/device/local-1", method: "PUT", data: localPayload, fixture: localDevice },
      { run: () => deleteLocalDevice("local-1"), url: "/api/config/local/device/local-1", method: "DELETE", fixture: { deviceId: "local-1", temporaryConfig: true } satisfies DeviceIdResponse },
      { run: () => getDeviceConfig("device-1"), url: "/api/config/device/device-1", fixture: deviceDetail },
      { run: () => updateDeviceConfig("device-1", { deviceName: "设备一" }), url: "/api/config/device/device-1", method: "PUT", data: { deviceName: "设备一" }, fixture: deviceIdResponse },
      { run: () => getDevicePointsConfig("device-1", false), url: "/api/config/device/device-1/points", params: { includeAdaptive: false }, fixture: points },
      { run: () => updateDevicePointsConfig("device-1", [{ pointId: "point-1" }]), url: "/api/config/device/device-1/points", method: "PUT", data: [{ pointId: "point-1" }], fixture: deviceIdResponse },
      { run: () => getDeviceConnection("device-1"), url: "/api/config/device/device-1/connection", fixture: connection },
      { run: () => updateDeviceConnection("device-1", { host: "127.0.0.1" }), url: "/api/config/device/device-1/connection", method: "PUT", data: { host: "127.0.0.1" }, fixture: deviceIdResponse },
      { run: () => getDeviceDiff("device-1"), url: "/api/config/device/device-1/diff", fixture: diff },
      { run: () => refreshDeviceConfig("device-1"), url: "/api/config/device/device-1/refresh", method: "POST", fixture: deviceIdResponse },
      { run: () => clearDeviceConfig("device-1"), url: "/api/config/device/device-1/clear", method: "POST", fixture: deviceIdResponse },
      { run: () => triggerPartialConfigSync("device", "device-1"), url: "/api/config/sync/device", method: "POST", params: { deviceId: "device-1" }, fixture: deviceIdResponse },
      { run: () => getConfigSyncStatus(), url: "/api/config/sync/status", fixture: syncStatus },
      { run: () => exportConfigs(), url: "/api/config/export", fixture: exported },
      { run: () => importConfigs(importPayload), url: "/api/config/import", method: "POST", data: importPayload, fixture: imported }
    ] as const;

    for (const call of calls) {
      httpMocks.requestApiData.mockResolvedValueOnce(call.fixture);
      await expect(call.run()).resolves.toBe(call.fixture);
    }

    expect(httpMocks.requestApiData.mock.calls.map(([config]) => config)).toEqual(calls.map((call) => ({
      url: call.url,
      method: "method" in call ? call.method : "GET",
      ...("params" in call ? { params: call.params } : {}),
      ...("data" in call ? { data: call.data } : {})
    })));
    expect(httpMocks.request).not.toHaveBeenCalled();
  });

  it("全量同步保留 command envelope metadata", async () => {
    const envelope: ApiResult<null> = { status: "success", message: "已触发异步全量同步任务", data: null, timestamp: 123 };
    httpMocks.requestEnvelope.mockResolvedValue(envelope);

    await expect(triggerFullConfigSync()).resolves.toBe(envelope);

    expect(httpMocks.requestEnvelope).toHaveBeenCalledWith({ url: "/api/config/sync", method: "POST" });
    expect(httpMocks.request).not.toHaveBeenCalled();
    expect(httpMocks.requestApiData).not.toHaveBeenCalled();
  });

  it("公开稳定 ConfigController TypeScript 返回类型", async () => {
    httpMocks.requestApiData.mockResolvedValue({});
    httpMocks.requestEnvelope.mockResolvedValue({ data: null });

    expectTypeOf(await getConfigSummary()).toEqualTypeOf<ConfigSummaryResponse>();
    expectTypeOf(await getLocalDevice("local-1")).toEqualTypeOf<LocalDeviceConfigResponse>();
    expectTypeOf(await getDeviceConnection("device-1")).toEqualTypeOf<DeviceConnectionConfigResponse>();
    expectTypeOf(await getDeviceDiff("device-1")).toEqualTypeOf<ConfigDiffResponse>();
    expectTypeOf(await getConfigSyncStatus()).toEqualTypeOf<ConfigSyncStatusResponse>();
    expectTypeOf(await exportConfigs()).toEqualTypeOf<ConfigExportResponse>();
    expectTypeOf(await importConfigs({ bundles: [], reloadAfterImport: true })).toEqualTypeOf<ConfigImportResult>();
    expectTypeOf(await refreshDeviceConfig("device-1")).toEqualTypeOf<DeviceIdResponse>();
    expectTypeOf(await clearDeviceConfig("device-1")).toEqualTypeOf<DeviceIdResponse>();
    expectTypeOf(await triggerFullConfigSync()).toEqualTypeOf<ApiResult<null>>();
  });
});
