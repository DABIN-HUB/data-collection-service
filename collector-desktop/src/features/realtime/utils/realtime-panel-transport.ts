export type RealtimePanelLoadSource = "mount" | "manual" | "device-change" | "timer" | "ws-fallback";

export interface RealtimePanelWebSocketState {
  connected: boolean;
  activeDeviceId: string;
  deviceId: string;
  hasFreshRows: boolean;
  wsRowCount: number;
}

export interface RealtimePanelHttpLoadPolicy {
  source: RealtimePanelLoadSource;
  loading: boolean;
  usingWebSocketRows: boolean;
}

export function shouldUseRealtimePanelWebSocketRows(state: RealtimePanelWebSocketState): boolean {
  return state.connected
    && state.activeDeviceId === state.deviceId
    && state.hasFreshRows
    && state.wsRowCount > 0;
}

export function shouldSkipRealtimePanelHttpLoad(policy: RealtimePanelHttpLoadPolicy): boolean {
  if (policy.source !== "timer") {
    return false;
  }
  return policy.loading || policy.usingWebSocketRows;
}
