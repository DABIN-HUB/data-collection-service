import type { DataPoint } from "./point";
import type { DeviceInfo, DeviceRuntimeSnapshot } from "./device";

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

export interface DeviceConfigBundleResponse {
  deviceId?: string;
  configVersion: number;
  persistedRevision?: number;
  device?: DeviceInfo;
  connection?: DeviceConnection;
  points?: DataPoint[];
  configSource?: string;
  temporaryConfig?: boolean;
}

export interface DeviceConfigBundleRequest {
  baseVersion: number;
  baseRevision?: number;
  device: DeviceInfo;
  connection: DeviceConnection;
  points: DataPoint[];
}

export interface DeviceConfigValidationResponse {
  valid: boolean;
  currentVersion: number;
  errors?: string[];
  warnings?: string[];
}

export interface DeviceConfigCommitResponse {
  deviceId?: string;
  /** 已提交异常回执仅返回 committed/reconciled/reloadRequired/deviceIds，不携带版本。 */
  previousVersion?: number;
  configVersion?: number;
  persistedRevision?: number;
  /** 提交成功不等于运行态应用成功；状态成功响应仍可能要求恢复。 */
  committed?: boolean | "UNKNOWN";
  reconciled?: boolean;
  reloadRequired?: boolean;
  deviceIds?: string[];
  pointCount?: number;
}
export interface ConfigBundle {
  device?: DeviceInfo;
  connection?: DeviceConnection;
  points?: DataPoint[];
  persistedRevision?: number;
  cloudTarget?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface LocalDeviceConfigRequest {
  /** JVM 运行配置版本，独立于持久修订。 */
  baseVersion?: number;
  /** 仅从读取到的 persistedRevision 取得；缺失时不得用 baseVersion 回填。 */
  baseRevision?: number;
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
  saved?: boolean;
  changed?: boolean;
  configVersion?: number;
  persistedRevision?: number;
  committed?: boolean | "UNKNOWN";
  reconciled?: boolean;
  reloadRequired?: boolean;
  deviceIds?: string[];
  startRequested?: boolean;
  startStatus?: "NOT_REQUESTED" | "ALREADY_RUNNING" | "ACCEPTED" | "RESTART_PENDING" | "STOP_SUPERSEDED" | "FAILED";
  startError?: string;
  runtime?: DeviceRuntimeSnapshot;
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
