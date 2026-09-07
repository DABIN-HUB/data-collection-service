import { getAllDeviceRealtimeData, getCompactAllDeviceRealtimeData, getCompactDeviceRealtimeData, getDeviceRealtimeData } from "@/api/data.api";
import type { AllDeviceRealtimeDataResponse, CompactAllDeviceRealtimeDataResponse, CompactDeviceRealtimeDataResponse, DeviceRealtimeDataResponse, RealtimePointRow } from "@/types/monitor";

import type { RealtimeRequestContext } from "./realtime-request-lifecycle";
import { extractCompactRealtimeRows } from "./realtime-compact-utils";

export interface RealtimeLoadStrategyDependencies {
  getCompactAllDeviceRealtimeData: () => Promise<CompactAllDeviceRealtimeDataResponse>;
  getCompactDeviceRealtimeData: (deviceId: string) => Promise<CompactDeviceRealtimeDataResponse>;
  getAllDeviceRealtimeData: () => Promise<AllDeviceRealtimeDataResponse>;
  getDeviceRealtimeData: (deviceId: string) => Promise<DeviceRealtimeDataResponse>;
}

const defaultDependencies: RealtimeLoadStrategyDependencies = {
  getCompactAllDeviceRealtimeData,
  getCompactDeviceRealtimeData,
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
