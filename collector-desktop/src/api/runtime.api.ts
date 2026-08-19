import { request } from "./http";
import type { ConsoleRuntimeStatusSnapshot, HealthStatus } from "@/types/runtime";

export function getHealth(): Promise<HealthStatus> {
  return request<HealthStatus>({
    url: "/health",
    method: "GET"
  });
}

export function getRuntimeStatus(): Promise<ConsoleRuntimeStatusSnapshot> {
  return request<ConsoleRuntimeStatusSnapshot>({
    url: "/monitor/runtime",
    method: "GET"
  });
}
