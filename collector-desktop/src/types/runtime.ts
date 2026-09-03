import type { HealthLevel } from "./api";

export type { ConsoleRuntimeStatusSnapshot, RuntimeComponentStatus, RuntimeHealthLevel } from "./monitor";

export interface HealthStatus {
  status?: string;
  level?: HealthLevel;
  message?: string;
  [key: string]: unknown;
}
