import type { DataPoint } from "./point";

export interface RealtimePointRow extends DataPoint {
  value?: unknown;
  currentValue?: unknown;
  quality?: string;
  timestamp?: number | string;
  collectTime?: number | string;
  processCostMs?: number;
  [key: string]: unknown;
}

export interface DeviceRealtimeDataResponse {
  deviceId?: string;
  points?: RealtimePointRow[];
  data?: RealtimePointRow[];
  values?: RealtimePointRow[];
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
