export interface DeviceInfo {
  id?: string;
  deviceId?: string;
  deviceName?: string;
  deviceAlias?: string;
  productKey?: string;
  productName?: string;
  groupId?: string;
  groupName?: string;
  protocolType?: string;
  connectionType?: string;
  ipAddress?: string;
  port?: number;
  authConfig?: Record<string, unknown>;
  collectionInterval?: number;
  reportInterval?: number;
  cloudTarget?: Record<string, unknown>;
  status?: string;
  lastOnlineTime?: string | number;
  lastOfflineTime?: string | number;
  lastError?: string;
  retryCount?: number;
  maxRetryCount?: number;
  createTime?: string | number;
  updateTime?: string | number;
  remark?: string;
  configSource?: string;
  temporaryConfig?: boolean;
  pointCount?: number;
  points?: unknown[];
  [key: string]: unknown;
}

export interface DeviceRuntimeSnapshot {
  deviceId: string;
  phase?: string;
  running?: boolean;
  starting?: boolean;
  connected?: boolean;
  reconnecting?: boolean;
  reconnectNextRetryAt?: number;
  startedAt?: number;
  generation?: number;
  lastSuccessfulCollectionAt?: number;
  consecutiveFailures?: number;
  backoffUntil?: number;
  degradedReason?: string;
  generatedAt?: number;
}

export interface ConfigDeviceListResponse {
  devices?: DeviceInfo[];
  count?: number;
}

export interface DeviceViewModel extends DeviceInfo {
  normalizedId: string;
  displayName: string;
  displayGroup: string;
  displayProtocol: string;
  runtime?: DeviceRuntimeSnapshot;
}
