export type AlarmLifecycleState = "ACTIVE" | "ACKED" | "RECOVERED";

export interface AlarmLifecycleItem {
  alarmId: string;
  deviceId?: string;
  deviceName?: string;
  pointId?: string;
  pointCode?: string;
  ruleId?: string;
  ruleName?: string;
  level?: string;
  message?: string;
  lifecycleState: AlarmLifecycleState;
  acknowledged: boolean;
  acknowledgedAt?: number;
  acknowledgedBy?: string;
  acknowledgementNote?: string;
  startedAt?: number;
  occurredAt?: number;
  lastOccurredAt?: number;
  recoveredAt?: number;
  durationMillis?: number;
  value?: unknown;
  unit?: string;
}

export interface AlarmLifecycleResponse {
  status: "success" | "disabled";
  items: AlarmLifecycleItem[];
  count: number;
}

export interface AlarmLifecycleQuery {
  deviceId?: string;
  pointId?: string;
  pointCode?: string;
  ruleId?: string;
  level?: string;
  state?: AlarmLifecycleState;
  startTs?: number;
  endTs?: number;
  limit?: number;
}
