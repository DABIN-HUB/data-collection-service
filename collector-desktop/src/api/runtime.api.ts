import { requestRaw } from "./http";
import type { HealthStatus } from "@/types/runtime";

export function getHealth(): Promise<HealthStatus> {
  return requestRaw<HealthStatus>({
    url: "/health",
    method: "GET"
  });
}

export { getRuntimeStatus } from "./monitor.api";
