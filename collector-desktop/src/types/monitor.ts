import type { DataPoint } from "./point";

export interface PointRealtimePayload extends DataPoint {
  createTime?: number | string;
  updateTime?: number | string;
  lastValue?: unknown;
  changeRate?: number;
  lastAdjustTime?: number;
  value?: unknown;
  rawValue?: unknown;
  processedValue?: unknown;
  hasCachedValue?: boolean;
  quality?: number | string;
  qualityDescription?: string;
  qualityLevel?: string;
  qualityAcceptable?: boolean;
  qualityAvailable?: boolean;
  processMessage?: string;
  processSuccess?: boolean;
  skipped?: boolean;
  processorName?: string;
  processingTime?: number;
  processingTimeAvailable?: boolean;
  metadata?: Record<string, unknown>;
  lastUpdateTime?: number | string;
  timestamp?: number;
}

export interface RealtimePointRow extends PointRealtimePayload {
  currentValue?: unknown;
  collectTime?: number | string;
  processCostMs?: number;
  [key: string]: unknown;
}

export interface PointRealtimeResponse {
  status?: string;
  message?: string;
  deviceId?: string;
  pointId?: string;
  data?: PointRealtimePayload;
  timestamp?: number;
  [key: string]: unknown;
}

export interface DeviceRealtimeDataResponse {
  status?: string;
  message?: string;
  deviceId?: string;
  dataCount?: number;
  data?: Record<string, PointRealtimePayload>;
  timestamp?: number;
  [key: string]: unknown;
}

export interface DeviceBriefResponse {
  deviceId?: string;
  pointCount?: number;
  [key: string]: unknown;
}

export interface DeviceListResponse {
  status?: string;
  message?: string;
  deviceCount?: number;
  devices?: DeviceBriefResponse[];
  timestamp?: number;
  [key: string]: unknown;
}

export interface DevicePointListResponse {
  status?: string;
  message?: string;
  deviceId?: string;
  pointCount?: number;
  points?: PointRealtimePayload[];
  timestamp?: number;
  [key: string]: unknown;
}

export interface AdaptiveResetResponse {
  code?: number;
  message?: string;
  [key: string]: unknown;
}

export interface HistoryDataResponse {
  status?: string;
  message?: string;
  deviceId?: string;
  pointId?: string;
  count?: number;
  data?: Record<string, unknown>[];
  startTs?: number;
  endTs?: number;
  timestamp?: number;
  [key: string]: unknown;
}

export interface AlarmHistoryDataResponse {
  status?: string;
  message?: string;
  deviceId?: string;
  pointId?: string;
  pointCode?: string;
  level?: string;
  ruleId?: string;
  count?: number;
  total?: number;
  data?: Record<string, unknown>[];
  startTs?: number;
  endTs?: number;
  timestamp?: number;
  [key: string]: unknown;
}

export interface AlarmRow {
  alarmId?: string;
  id?: string;
  level?: string;
  deviceId?: string;
  deviceName?: string;
  pointId?: string;
  pointCode?: string;
  pointName?: string;
  content?: string;
  message?: string;
  alarmContent?: string;
  alarmType?: string;
  timestamp?: number | string;
  occurTime?: number | string;
  status?: string;
  acknowledged?: boolean;
  [key: string]: unknown;
}

export interface LogRow {
  timestamp?: number | string;
  time?: number | string;
  deviceId?: string;
  deviceName?: string;
  level?: string;
  logger?: string;
  message?: string;
  content?: string;
  [key: string]: unknown;
}
