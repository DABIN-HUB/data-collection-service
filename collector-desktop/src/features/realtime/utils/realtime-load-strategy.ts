import { getAllDeviceRealtimeData, getCompactAllDeviceRealtimeData, getCompactAllDeviceRealtimeDelta, getCompactDeviceRealtimeData, getCompactDeviceRealtimeDelta, getDeviceRealtimeData } from "@/api/data.api";
import type { AllDeviceRealtimeDataResponse, CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, CompactRealtimeDeltaResponse, DeviceRealtimeDataResponse, RealtimePointRow, RealtimeSnapshotCursor } from "@/types/monitor";

import type { RealtimeRequestContext } from "./realtime-request-lifecycle";
import { extractCompactRealtimeRows } from "./realtime-compact-utils";

export interface RealtimeLoadStrategyDependencies {
  getCompactAllDeviceRealtimeData: () => Promise<CompactAllDeviceRealtimeDataResponse>;
  getCompactDeviceRealtimeData: (deviceId: string) => Promise<CompactDeviceRealtimeDataResponse>;
  getCompactAllDeviceRealtimeDelta: (cursor: RealtimeSnapshotCursor) => Promise<CompactRealtimeDeltaResponse>;
  getCompactDeviceRealtimeDelta: (deviceId: string, cursor: RealtimeSnapshotCursor) => Promise<CompactRealtimeDeltaResponse>;
  getAllDeviceRealtimeData: () => Promise<AllDeviceRealtimeDataResponse>;
  getDeviceRealtimeData: (deviceId: string) => Promise<DeviceRealtimeDataResponse>;
}

const defaultDependencies: RealtimeLoadStrategyDependencies = {
  getCompactAllDeviceRealtimeData,
  getCompactDeviceRealtimeData,
  getCompactAllDeviceRealtimeDelta,
  getCompactDeviceRealtimeDelta,
  getAllDeviceRealtimeData,
  getDeviceRealtimeData
};

export async function loadRealtimeRowsByContext(
  context: Pick<RealtimeRequestContext, "mode" | "deviceId">,
  dependencies: RealtimeLoadStrategyDependencies = defaultDependencies
): Promise<RealtimePointRow[]> {
  if (context.mode === "device" && context.deviceId) {
    const response = await dependencies.getCompactDeviceRealtimeData(context.deviceId);
    return extractCompactRealtimeRows(response);
  }
  const response = await dependencies.getCompactAllDeviceRealtimeData();
  return extractCompactRealtimeRows(response);
}

export async function loadRealtimeFullResponseByContext(
  context: Pick<RealtimeRequestContext, "mode" | "deviceId">,
  dependencies: RealtimeLoadStrategyDependencies = defaultDependencies
): Promise<CompactAllDeviceRealtimeDataResponse | CompactDeviceRealtimeDataResponse> {
  if (context.mode === "device" && context.deviceId) {
    return dependencies.getCompactDeviceRealtimeData(context.deviceId);
  }
  return dependencies.getCompactAllDeviceRealtimeData();
}

export async function loadRealtimeDeltaResponseByContext(
  context: Pick<RealtimeRequestContext, "mode" | "deviceId">,
  cursor: RealtimeSnapshotCursor,
  dependencies: RealtimeLoadStrategyDependencies = defaultDependencies
): Promise<CompactRealtimeDeltaResponse> {
  if (context.mode === "device" && context.deviceId) {
    return dependencies.getCompactDeviceRealtimeDelta(context.deviceId, cursor);
  }
  return dependencies.getCompactAllDeviceRealtimeDelta(cursor);
}
