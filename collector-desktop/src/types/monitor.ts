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
  thread?: string;
  message?: string;
  content?: string;
  [key: string]: unknown;
}

export type RuntimeHealthLevel = "OK" | "WARN" | "ERROR" | "DISABLED" | "UNKNOWN";

export interface RuntimeComponentStatus {
  code?: string;
  name?: string;
  level?: RuntimeHealthLevel;
  message?: string;
  details?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface CacheMetricsSnapshot {
  totalReads?: number;
  totalWrites?: number;
  totalDeletes?: number;
  totalMisses?: number;
  totalAccess?: number;
  totalHitRate?: number;
  level1HitRate?: number;
  level2HitRate?: number;
  missRate?: number;
  levelStatistics?: Record<string, Record<string, unknown>>;
  health?: Record<string, unknown>;
  generatedAt?: number;
  [key: string]: unknown;
}

export interface DeviceConnectionSnapshot {
  deviceId?: string;
  status?: string;
  connected?: boolean;
  expectedOnly?: boolean;
  lastActivityTime?: number;
  idleTime?: number;
  bytesSent?: number;
  bytesReceived?: number;
  errors?: number;
  successRate?: number;
  connectionDuration?: number;
  [key: string]: unknown;
}

export interface DeviceStatusSnapshot {
  totalConnections?: number;
  activeConnections?: number;
  expectedConnections?: number;
  healthyDevices?: number;
  warningDevices?: number;
  dangerDevices?: number;
  missingConnections?: string[];
  connections?: DeviceConnectionSnapshot[];
  generatedAt?: number;
  [key: string]: unknown;
}

export interface CollectorMetrics {
  deviceId?: string;
  protocol?: string;
  processedPoints?: number;
  pointsPerSecond?: number;
  successRate?: number;
  averageLatencyMs?: number;
  protocolMetrics?: Record<string, unknown>;
  timestamp?: number;
  [key: string]: unknown;
}

export interface SystemResourceSnapshot {
  heapUsed?: number;
  heapCommitted?: number;
  heapMax?: number;
  nonHeapUsed?: number;
  nonHeapCommitted?: number;
  totalPhysicalMemorySize?: number;
  freePhysicalMemorySize?: number;
  processCpuLoad?: number;
  systemCpuLoad?: number;
  threadCount?: number;
  daemonThreadCount?: number;
  outboxPendingCount?: number;
  outboxIsolatedCount?: number;
  outboxOldestMessageAgeMillis?: number;
  threadPools?: Record<string, ThreadPoolSnapshot>;
  generatedAt?: number;
  [key: string]: unknown;
}

export interface ThreadPoolSnapshot {
  corePoolSize?: number;
  maxPoolSize?: number;
  activeCount?: number;
  queueSize?: number;
  completedTaskCount?: number;
  rejectedCount?: number;
  [key: string]: unknown;
}

export interface ExceptionSummary {
  deviceId?: string;
  pointId?: string;
  category?: string;
  message?: string;
  timestamp?: number;
  [key: string]: unknown;
}

export interface ExceptionStatsSnapshot {
  totalExceptions?: number;
  byCategory?: Record<string, number>;
  byDevice?: Record<string, number>;
  recent?: ExceptionSummary[];
  generatedAt?: number;
  [key: string]: unknown;
}

export interface StorageMetricsSnapshot {
  enabled?: boolean;
  status?: string;
  message?: string;
  responseTimeMs?: number;
  generatedAt?: number;
  [key: string]: unknown;
}

export interface PerformanceStatsSnapshot {
  timeSliceCount?: number;
  timeSliceIntervalMs?: number;
  timeSliceExecutionTimes?: Record<string, number>;
  overloadedSlices?: Record<string, number>;
  slowestDevices?: Record<string, number>;
  deviceStats?: Record<string, Record<string, unknown>>;
  processCpuLoad?: number;
  batchDispatchRejectedCount?: number;
  collectRejectedCount?: number;
  processRejectedCount?: number;
  reconnectAttemptCount?: number;
  reconnectSuccessCount?: number;
  reconnectFailureCount?: number;
  reconnectingDevices?: number;
  generatedAt?: number;
  [key: string]: unknown;
}

export interface CloudReportConfigured {
  configSnapshotAvailable?: boolean;
  configSnapshotError?: string;
  deviceCount?: number;
  pointCount?: number;
  reportEnabledPointCount?: number;
  eventEnabledPointCount?: number;
  changeTriggerPointCount?: number;
  reportFieldPointCount?: number;
  reportablePointCount?: number;
  cloudTargetDeviceCount?: number;
  invalidCloudTargetDeviceCount?: number;
  cloudTargetCount?: number;
  cloudTargetKeys?: string[];
  cloudTargetCoverage?: number;
  [key: string]: unknown;
}

export interface CloudReportExecutor {
  type?: string;
  corePoolSize?: number;
  maxPoolSize?: number;
  poolSize?: number;
  activeCount?: number;
  queueSize?: number;
  queueRemainingCapacity?: number;
  queueCapacity?: number;
  queueUsage?: number;
  completedTaskCount?: number;
  taskCount?: number;
  rejectedCount?: number;
  [key: string]: unknown;
}

export interface CloudReportBatch {
  enabled?: boolean;
  maxDevicesPerPack?: number;
  maxPropertiesPerPack?: number;
  maxPayloadBytes?: number;
  maxDelayMs?: number;
  highPriorityBypass?: boolean;
  [key: string]: unknown;
}

export interface CloudReportAck {
  mode?: string;
  timeoutMs?: number;
  maxPending?: number;
  timeoutScanMs?: number;
  commitOn?: string;
  [key: string]: unknown;
}

export interface CloudReportOutbox {
  enabled?: boolean;
  pendingCount?: number;
  isolatedCount?: number;
  oldestMessageAgeMs?: number;
  [key: string]: unknown;
}

export interface CloudReportPayload {
  profile?: string;
  includeQuality?: string;
  includePropertyTs?: boolean;
  includeMetadata?: boolean;
  includeMessageId?: boolean;
  [key: string]: unknown;
}

export interface CloudReportMetricsResponse {
  enabled?: boolean;
  status?: string;
  statusText?: string;
  mode?: string;
  cloudProvider?: string;
  supportedProtocols?: string[];
  handlersStatus?: Record<string, Record<string, unknown>>;
  handlersStatistics?: Record<string, Record<string, unknown>>;
  configured?: CloudReportConfigured;
  executor?: CloudReportExecutor;
  batch?: CloudReportBatch;
  ack?: CloudReportAck;
  outbox?: CloudReportOutbox;
  payload?: CloudReportPayload;
  risks?: string[];
  generatedAt?: number;
  [key: string]: unknown;
}

export interface ConsoleRuntimeStatusSnapshot {
  level?: RuntimeHealthLevel;
  message?: string;
  components?: RuntimeComponentStatus[];
  risks?: string[];
  cache?: CacheMetricsSnapshot;
  devices?: DeviceStatusSnapshot;
  system?: SystemResourceSnapshot;
  exceptions?: ExceptionStatsSnapshot;
  performance?: PerformanceStatsSnapshot;
  report?: Record<string, unknown>;
  storage?: StorageMetricsSnapshot;
  generatedAt?: number;
  [key: string]: unknown;
}
