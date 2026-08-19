export interface ApiResult<T> {
  code?: number;
  status?: string;
  message?: string;
  data?: T;
  [key: string]: unknown;
}

export interface LoadState {
  loading: boolean;
  error: string;
}

export type HealthLevel = "HEALTHY" | "DEGRADED" | "FAILED" | "UNKNOWN" | string;
