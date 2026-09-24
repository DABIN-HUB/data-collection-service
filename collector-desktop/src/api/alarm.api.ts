import { requestApiData } from "./http";
import type { AlarmLifecycleQuery, AlarmLifecycleResponse } from "@/types/alarm";

export function getAlarmLifecycles(params: AlarmLifecycleQuery = {}): Promise<AlarmLifecycleResponse> {
  return requestApiData<AlarmLifecycleResponse>({ url: "/api/alarms", method: "GET", params });
}
