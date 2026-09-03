export interface ApiResult<T> {
  code?: number;
  status?: string;
  message?: string;
  data?: T;
  timestamp?: number;
  extra?: Record<string, unknown>;
  deviceId?: string;
  count?: number;
  running?: boolean;
  [key: string]: unknown;
}

export interface LoadState {
  loading: boolean;
  error: string;
}

export type HealthLevel = "HEALTHY" | "DEGRADED" | "FAILED" | "UNKNOWN" | string;
