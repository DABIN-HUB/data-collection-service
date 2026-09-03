export type EdgeProtocolType = "PROFINET" | "ETHERCAT" | "GENERIC_EDGE";

export interface EdgeTelemetryItem {
  deviceId: string;
  pointRef: string;
  value: unknown;
  quality?: number;
  timestamp?: number;
  sequence: number;
}

export interface EdgeTelemetryBatchRequest {
  gatewayId: string;
  protocol: EdgeProtocolType;
  configVersion: string;
  items: EdgeTelemetryItem[];
}

export interface EdgeTelemetryIngressResult {
  gatewayId: string;
  configVersion: string;
  acceptedCount: number;
  duplicateCount: number;
  rejectedCount: number;
  errors: string[];
}
