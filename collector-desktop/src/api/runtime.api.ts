import { requestRaw } from "./http";
import type { ConsoleRuntimeStatusSnapshot, HealthStatus } from "@/types/runtime";

export function getHealth(): Promise<HealthStatus> {
  return requestRaw<HealthStatus>({
    url: "/health",
    method: "GET"
  });
}

export function getRuntimeStatus(): Promise<ConsoleRuntimeStatusSnapshot> {
  return requestRaw<ConsoleRuntimeStatusSnapshot>({
    url: "/monitor/runtime",
    method: "GET"
  });
}
