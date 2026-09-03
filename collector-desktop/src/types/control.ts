export interface PointWriteRequest {
  value?: unknown;
  /**
   * 批量写入字段到写入值的映射。
   * key 作为 pointRef 透传给后端，后端会按 reportField、pointAlias、pointCode、pointId、pointName 顺序解析。
   */
  values?: Record<string, unknown>;
}

export interface PointWriteResultResponse {
  pointId?: string;
  pointCode?: string;
  pointName?: string;
  value?: unknown;
  success?: boolean;
  error?: string;
}

export interface BatchPointWriteFieldResponse {
  mapped?: boolean;
  success?: boolean;
  error?: string;
  pointId?: string;
  pointCode?: string;
  value?: unknown;
}

export interface BatchPointWriteResponse {
  deviceId?: string;
  fields?: Record<string, BatchPointWriteFieldResponse>;
  total?: number;
  mapped?: number;
  success?: number;
}

export interface DeviceCommandRequest {
  command: string;
  params?: Record<string, unknown>;
}

export interface DeviceCommandResponse {
  deviceId?: string;
  command?: string;
  params?: Record<string, unknown>;
  result?: unknown;
}

export type ControlResultResponse =
  | PointWriteResultResponse
  | BatchPointWriteResponse
  | DeviceCommandResponse;
