import type { HealthLevel } from "./api";

export interface RuntimeComponentStatus {
  name?: string;
  code?: string;
  level?: HealthLevel;
  message?: string;
  [key: string]: unknown;
}

export interface ConsoleRuntimeStatusSnapshot {
  level?: HealthLevel;
  message?: string;
  components?: RuntimeComponentStatus[];
  risks?: string[];
  cache?: Record<string, unknown>;
  devices?: Record<string, unknown>;
  system?: Record<string, unknown>;
  exceptions?: Record<string, unknown>;
  performance?: Record<string, unknown>;
  report?: Record<string, unknown>;
  storage?: Record<string, unknown>;
  generatedAt?: number;
  [key: string]: unknown;
}

export interface HealthStatus {
  status?: string;
  level?: HealthLevel;
  message?: string;
  [key: string]: unknown;
}
