export interface DeviceRequestContext {
  deviceId: string;
}

export interface DeviceProtocolRequestContext extends DeviceRequestContext {
  protocolKey: string;
}

export function buildDeviceRequestContext(deviceId: unknown): DeviceRequestContext {
  return {
    deviceId: normalizeText(deviceId)
  };
}

export function buildDeviceProtocolRequestContext(
  deviceId: unknown,
  protocolKey: unknown
): DeviceProtocolRequestContext {
  return {
    deviceId: normalizeText(deviceId),
    protocolKey: normalizeText(protocolKey)
  };
}

export function isSameDeviceRequestContext(
  left: DeviceRequestContext | null | undefined,
  right: DeviceRequestContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId);
}

export function isSameDeviceProtocolRequestContext(
  left: DeviceProtocolRequestContext | null | undefined,
  right: DeviceProtocolRequestContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId)
    && normalizeText(left.protocolKey) === normalizeText(right.protocolKey);
}

export function shouldCommitDeviceProtocolSave(
  targetContext: DeviceProtocolRequestContext,
  liveContext: DeviceProtocolRequestContext
): boolean {
  return isSameDeviceProtocolRequestContext(targetContext, liveContext);
}

function normalizeText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
