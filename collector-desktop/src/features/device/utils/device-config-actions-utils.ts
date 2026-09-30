import type { ApiResult } from "@/types/api";
import type { DeviceIdResponse, LocalDeviceConfigResponse } from "@/types/config";

export type DeviceConfigActionType = "refresh" | "clear";

export interface DeviceConfigActionOption {
  type: DeviceConfigActionType;
  label: string;
  confirmText: string;
}

export interface DeviceConfigActionResult {
  deviceId: string;
  message: string;
}

export const DEVICE_CONFIG_ACTIONS: DeviceConfigActionOption[] = [
  {
    type: "refresh",
    label: "刷新配置缓存",
    confirmText: "将重新加载该设备配置缓存，不会修改远端配置。"
  },
  {
    type: "clear",
    label: "清理配置缓存",
    confirmText: "将清空该设备配置缓存，后续会重新从配置源加载；不会删除远端配置。"
  }
];

type DeviceConfigActionData = DeviceIdResponse | LocalDeviceConfigResponse;
type DeviceConfigActionEnvelope = ApiResult<DeviceConfigActionData | null>;
type DeviceConfigActionSource = DeviceConfigActionData | DeviceConfigActionEnvelope | null | undefined;

export function normalizeDeviceConfigActionResult(response: DeviceConfigActionSource, fallbackDeviceId: string): DeviceConfigActionResult {
  const data = isEnvelope(response) ? response.data : response;
  const record = data && typeof data === "object" ? data : {};
  const envelope = isEnvelope(response) ? response : {} as Partial<DeviceConfigActionEnvelope>;
  return {
    deviceId: String(record.deviceId || fallbackDeviceId || ""),
    message: String(envelope.msg || envelope.message || (record as { message?: string }).message || "")
  };
}

export function buildDeviceConfigActionMessage(type: DeviceConfigActionType, result: DeviceConfigActionResult): string {
  if (result.message) {
    return result.message;
  }
  const deviceId = result.deviceId || "当前设备";
  return type === "clear" ? `设备 ${deviceId} 配置缓存已清理` : `设备 ${deviceId} 配置缓存已刷新`;
}

function isEnvelope(value: DeviceConfigActionSource): value is DeviceConfigActionEnvelope {
  return value !== null && value !== undefined && typeof value === "object" && "data" in value;
}
