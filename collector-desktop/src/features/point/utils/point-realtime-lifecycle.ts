export interface PointRealtimeRequestContext {
  deviceId: string;
}

export function buildPointRealtimeRequestContext(deviceId: string): PointRealtimeRequestContext {
  return {
    deviceId: normalizeText(deviceId)
  };
}

export function isSamePointRealtimeRequestContext(
  left: PointRealtimeRequestContext | null | undefined,
  right: PointRealtimeRequestContext | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  return normalizeText(left.deviceId) === normalizeText(right.deviceId);
}

function normalizeText(value: string | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}
