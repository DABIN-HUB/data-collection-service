export interface DataPoint {
  id?: number;
  unitId?: number;
  commonAddress?: number;
  pointId?: string;
  pointCode?: string;
  pointName?: string;
  pointAlias?: string;
  deviceId?: string;
  deviceName?: string;
  groupId?: string;
  address?: string;
  dataType?: string;
  readWrite?: string;
  scalingFactor?: number;
  offset?: number;
  deadband?: number;
  unit?: string;
  minValue?: number;
  maxValue?: number;
  collectionMode?: string;
  priority?: number;
  cacheEnabled?: number;
  cacheDuration?: number;
  alarmEnabled?: number;
  alarmRule?: string;
  status?: number;
  precision?: number;
  remark?: string;
  additionalConfig?: Record<string, unknown>;
  baseCollectionInterval?: number;
  currentCollectionInterval?: number;
  minCollectionInterval?: number;
  maxCollectionInterval?: number;
  pointChangeThreshold?: number;
  stableCount?: number;
  lastValue?: unknown;
  changeRate?: number;
  lastAdjustTime?: number;
  [key: string]: unknown;
}

export interface DevicePointConfigResponse {
  points?: DataPoint[];
  count?: number;
  deviceId?: string;
  [key: string]: unknown;
}
