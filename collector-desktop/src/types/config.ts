import type { DataPoint } from "./point";
import type { DeviceInfo } from "./device";

export interface ConfigCacheStatsResponse {
  deviceCount: number;
  pointCount: number;
  connectionCount: number;
  contextCount: number;
}

export interface ConfigSummaryResponse {
  cacheStats: ConfigCacheStatsResponse;
  lastSyncTime?: number | null;
  nextSyncTime?: number | null;
  syncInterval?: number | null;
  serviceId?: string;
  listenerCount?: number;
  [key: string]: unknown;
}

export interface DeviceConnection {
  id?: number;
  connectionType?: string;
  connectionKey?: string;
  host?: string;
  port?: number;
  url?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  writeTimeoutMs?: number;
  retries?: number;
  extJson?: Record<string, unknown>;
  remark?: string;
  connectionId?: string;
  deviceId?: string;
  deviceName?: string;
  timeout?: number;
  heartbeatInterval?: number;
  heartbeatTimeout?: number;
  subscriptionInterval?: number;
  maxFrameLength?: number;
  reconnectDelay?: number;
  maxReconnectTimes?: number;
  maxGroupConnections?: number;
  username?: string;
  password?: string;
  clientId?: string;
  productKey?: string;
  deviceSecret?: string;
  authToken?: string;
  securityPolicy?: string;
  authParams?: Record<string, string>;
  sslEnabled?: boolean;
  sslCertPath?: string;
  sslKeyPath?: string;
  charset?: string;
  keepAlive?: boolean;
  bufferSize?: number;
  autoReconnect?: boolean;
  initialReconnectDelay?: number;
  maxReconnectDelay?: number;
  maxReconnectAttempts?: number;
  reconnectBackoffMultiplier?: number;
  maxPendingMessages?: number;
  dispatchBatchSize?: number;
  dispatchFlushInterval?: number;
  overflowStrategy?: string;
  status?: string;
  connectTime?: string | number;
  disconnectTime?: string | number;
  duration?: number;
  lastError?: string;
  stats?: Record<string, unknown>;
  lastHeartbeatTime?: string | number;
  lastDataTime?: string | number;
  createTime?: string | number;
  updateTime?: string | number;
  connectionStats?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface ConfigBundle {
  device?: DeviceInfo;
  connection?: DeviceConnection;
  points?: DataPoint[];
  cloudTarget?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface LocalDeviceConfigRequest {
  device: Partial<DeviceInfo> & Record<string, unknown>;
  connection: Partial<DeviceConnection> & Record<string, unknown>;
  points: DataPoint[];
  overwrite: boolean;
  startAfterSave: boolean;
}

export interface LocalDeviceConfigResponse {
  deviceId?: string;
  configSource?: string;
  temporaryConfig?: boolean;
  bundle?: ConfigBundle;
  started?: boolean;
  pointCount?: number;
  [key: string]: unknown;
}

export interface DeviceIdResponse {
  deviceId?: string;
  configSource?: string;
  temporaryConfig?: boolean;
  started?: boolean;
  pointCount?: number;
  count?: number;
  [key: string]: unknown;
}

export interface DeviceConfigDetailResponse {
  deviceId?: string;
  local?: DeviceInfo | null;
  remote?: DeviceInfo | null;
  inSync?: boolean;
  [key: string]: unknown;
}

export interface DeviceConnectionConfigResponse {
  deviceId?: string;
  connection?: DeviceConnection;
  [key: string]: unknown;
}

export interface ConfigDiffResponse {
  deviceChanged: boolean;
  connectionChanged: boolean;
  missingPointCodes: string[];
  extraPointCodes: string[];
  changedPointCodes: string[];
}

export interface ConfigSyncStatusResponse {
  serviceId?: string;
  lastSyncTime?: number | null;
  syncInterval?: number | null;
  listenerCount?: number;
  consecutiveFailures?: number;
  lastFailureTime?: number | null;
  sourceVersion?: string;
  snapshotDeviceCount?: number;
  [key: string]: unknown;
}

export interface ConfigExportResponse {
  bundles: ConfigBundle[];
  [key: string]: unknown;
}

export interface ConfigImportRequest {
  bundles: ConfigBundle[];
  reloadAfterImport: boolean;
}

export interface ConfigImportResult {
  total: number;
  success: number;
  failedDevices: string[];
  [key: string]: unknown;
}
