import { request } from "./http";

export function ingestEdgeTelemetry(payload: unknown): Promise<unknown> {
  return request<unknown>({ url: "/api/edge/telemetry", method: "POST", data: payload });
}
