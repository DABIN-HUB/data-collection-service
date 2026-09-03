import { requestApiData } from "./http";
import type { EdgeTelemetryBatchRequest, EdgeTelemetryIngressResult } from "@/types/edge";

export function ingestEdgeTelemetry(payload: EdgeTelemetryBatchRequest): Promise<EdgeTelemetryIngressResult> {
  return requestApiData<EdgeTelemetryIngressResult>({ url: "/api/edge/telemetry", method: "POST", data: payload });
}
