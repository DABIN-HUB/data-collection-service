import { getAllDeviceRealtimeData, getDeviceRealtimeData } from "@/api/data.api";
import type { AllDeviceRealtimeDataResponse, DeviceRealtimeDataResponse, RealtimePointRow } from "@/types/monitor";

import type { RealtimeRequestContext } from "./realtime-request-lifecycle";
import { normalizeAllDeviceRealtimeRows, normalizeRealtimeRows } from "./realtime-utils";

export interface RealtimeLoadStrategyDependencies {
  getAllDeviceRealtimeData: () => Promise<AllDeviceRealtimeDataResponse>;
  getDeviceRealtimeData: (deviceId: string) => Promise<DeviceRealtimeDataResponse>;
}

const defaultDependencies: RealtimeLoadStrategyDependencies = {
  getAllDeviceRealtimeData,
  getDeviceRealtimeData
};

export async function loadRealtimeRowsByContext(
  context: Pick<RealtimeRequestContext, "mode" | "deviceId">,
  dependencies: RealtimeLoadStrategyDependencies = defaultDependencies
): Promise<RealtimePointRow[]> {
  if (context.mode === "device" && context.deviceId) {
    const response = await dependencies.getDeviceRealtimeData(context.deviceId);
    return normalizeRealtimeRows(response, context.deviceId);
  }
  const response = await dependencies.getAllDeviceRealtimeData();
  return normalizeAllDeviceRealtimeRows(response);
}
