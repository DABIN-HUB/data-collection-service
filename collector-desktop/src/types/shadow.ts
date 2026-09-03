export type ShadowDynamicMap = Record<string, unknown>;

export interface DeviceShadowStateResponse {
  reported: ShadowDynamicMap;
  desired: ShadowDynamicMap;
  delta: ShadowDynamicMap;
  lastReported: ShadowDynamicMap;
}

export interface DeviceShadowMetadataResponse {
  reported: ShadowDynamicMap;
  desired: ShadowDynamicMap;
}

export interface DeviceShadowResponse {
  deviceId: string;
  version: number;
  timestamp: number;
  createdAt?: number;
  lastReportAt?: number;
  lastWindowStart?: number;
  lastWindowEnd?: number;
  state: DeviceShadowStateResponse;
  metadata: DeviceShadowMetadataResponse;
}

export interface DeviceShadowDeltaResponse {
  deviceId: string;
  version: number;
  timestamp: number;
  delta: ShadowDynamicMap;
  metadata: ShadowDynamicMap;
}

export interface ShadowHistoryDocument {
  deviceId?: string;
  action?: string;
  baseVersion?: number;
  version?: number;
  timestamp?: number;
  document?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface ShadowDesiredUpdateRequest {
  state?: {
    desired?: ShadowDynamicMap;
  };
  desired?: ShadowDynamicMap;
  properties?: ShadowDynamicMap;
  params?: ShadowDynamicMap;
  source?: string;
  shadowVersion?: number;
  expectedVersion?: number;
  [key: string]: unknown;
}
