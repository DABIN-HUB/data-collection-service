import { describe, expect, it } from "vitest";

import * as configApi from "./config.api";
import * as controlApi from "./control.api";
import * as dataApi from "./data.api";
import * as deviceApi from "./device.api";
import * as edgeApi from "./edge.api";
import * as monitorApi from "./monitor.api";
import * as opsApi from "./ops.api";
import * as protocolApi from "./protocol.api";
import * as runtimeApi from "./runtime.api";
import * as shadowApi from "./shadow.api";

const expectedExports = {
  configApi: ["getConfigSummary", "getConfigDevices", "createLocalDevice", "getLocalDevice", "updateLocalDevice", "deleteLocalDevice", "getDeviceConfig", "updateDeviceConfig", "getDevicePointsConfig", "updateDevicePointsConfig", "getDeviceConnection", "updateDeviceConnection", "getDeviceDiff", "refreshDeviceConfig", "clearDeviceConfig", "triggerFullConfigSync", "triggerPartialConfigSync", "getConfigSyncStatus", "exportConfigs", "importConfigs"],
  controlApi: ["writeDevicePoint", "writeDevicePoints", "executeDeviceCommand"],
  dataApi: ["getPointRealtimeData", "getDeviceRealtimeData", "getAllDeviceDataSummaries", "getDevicePointSummaries", "resetAdaptiveConfig", "getPointHistory", "getRecentAlarms", "getDeviceAlarmHistory"],
  deviceApi: ["startDevice", "startLocalDevice", "stopDevice", "reloadDevices", "getDeviceStatus", "getAllDeviceStatistics", "getRunningDevices", "getDeviceRuntime", "isDeviceRunning"],
  edgeApi: ["ingestEdgeTelemetry"],
  monitorApi: ["getRuntimeStatus", "getCacheMetrics", "getDeviceConnectionMetrics", "getCollectorPerformance", "getSystemResources", "getExceptionStats", "getCloudReportMetrics", "getStorageMetrics", "getPerformanceDetail"],
  opsApi: ["getOpsLogs", "queryAlarmAcknowledgements", "acknowledgeAlarm", "diagnoseNetwork", "normalizeLogRows"],
  protocolApi: ["listProtocols", "getProtocol", "getProtocolFields"],
  runtimeApi: ["getHealth", "getRuntimeStatus"],
  shadowApi: ["getShadow", "getShadowDelta", "getShadowHistory", "updateShadowDesired", "clearShadowDesired"]
};

const modules = { configApi, controlApi, dataApi, deviceApi, edgeApi, monitorApi, opsApi, protocolApi, runtimeApi, shadowApi };

describe("api modules", () => {
  it("导出原控制台和后端 Controller 覆盖所需的 API 方法", () => {
    for (const [moduleName, names] of Object.entries(expectedExports)) {
      const moduleExports = modules[moduleName as keyof typeof modules] as Record<string, unknown>;
      for (const name of names) {
        expect(typeof moduleExports[name], `${moduleName}.${name}`).toBe("function");
      }
    }
  });
});
