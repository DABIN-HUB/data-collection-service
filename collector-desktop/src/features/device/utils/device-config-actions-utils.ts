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

export function normalizeDeviceConfigActionResult(response: unknown, fallbackDeviceId: string): DeviceConfigActionResult {
  const record = asRecord(response);
  const data = asRecord(record.data);
  const source = Object.keys(data).length ? data : record;
  return {
    deviceId: String(source.deviceId || fallbackDeviceId || ""),
    message: String(record.msg || record.message || source.message || "")
  };
}

export function buildDeviceConfigActionMessage(type: DeviceConfigActionType, result: DeviceConfigActionResult): string {
  if (result.message) {
    return result.message;
  }
  const deviceId = result.deviceId || "当前设备";
  return type === "clear" ? `设备 ${deviceId} 配置缓存已清理` : `设备 ${deviceId} 配置缓存已刷新`;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
