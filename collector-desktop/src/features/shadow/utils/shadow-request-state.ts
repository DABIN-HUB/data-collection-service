import { createLatestRequestOwner, type LatestRequestOwner, type LatestRequestTicket } from "@/features/request/utils/latest-request-owner";

export interface ShadowDeviceContext {
  deviceId: string;
}

export type ShadowSectionKind = "shadow" | "delta" | "history";

export interface ShadowSectionStatusInput {
  kind: ShadowSectionKind;
  loading: boolean;
  error: string;
  lastSuccessAt: number | null;
}

export const DEFAULT_SHADOW_DESIRED_PAYLOAD = JSON.stringify({ desired: {} }, null, 2);

const SHADOW_SECTION_LABELS: Record<ShadowSectionKind, string> = {
  shadow: "影子",
  delta: "delta",
  history: "历史"
};

export function buildShadowDeviceContext(deviceId: string): ShadowDeviceContext {
  return { deviceId: deviceId.trim() };
}

export function isSameShadowDeviceContext(
  left: ShadowDeviceContext | null | undefined,
  right: ShadowDeviceContext | null | undefined
): boolean {
  return (left?.deviceId || "") === (right?.deviceId || "");
}

export function createShadowDeviceRequestOwner(): LatestRequestOwner<ShadowDeviceContext> {
  return createLatestRequestOwner<ShadowDeviceContext>(isSameShadowDeviceContext);
}

export function shouldCommitShadowRequest(
  owner: LatestRequestOwner<ShadowDeviceContext>,
  ticket: LatestRequestTicket<ShadowDeviceContext>,
  liveDeviceId: string
): boolean {
  return owner.canCommit(ticket, buildShadowDeviceContext(liveDeviceId));
}

export function shouldClearShadowLoading(
  owner: LatestRequestOwner<ShadowDeviceContext>,
  ticket: LatestRequestTicket<ShadowDeviceContext>
): boolean {
  return owner.isLatest(ticket);
}

export function shouldCommitShadowWrite(targetDeviceId: string, liveDeviceId: string): boolean {
  return targetDeviceId.trim() !== "" && targetDeviceId.trim() === liveDeviceId.trim();
}

export function shadowSectionHasLastGood(lastSuccessAt: number | null): boolean {
  return lastSuccessAt !== null;
}

export function buildShadowSectionStatus(input: ShadowSectionStatusInput): string {
  const label = SHADOW_SECTION_LABELS[input.kind];
  if (input.loading && input.lastSuccessAt !== null) {
    return `${label}刷新中 · 上次成功 ${formatShadowLastSuccessTime(input.lastSuccessAt)}`;
  }
  if (input.loading) {
    return `${label}读取中...`;
  }
  if (input.error && input.lastSuccessAt !== null) {
    return `${label}刷新失败，当前显示上次成功数据 · ${input.error}`;
  }
  if (input.error) {
    return `${label}读取失败：${input.error}`;
  }
  if (input.lastSuccessAt !== null) {
    return `${label}最后成功 ${formatShadowLastSuccessTime(input.lastSuccessAt)}`;
  }
  return `点击读取${label}`;
}

export function buildShadowIdleMessage(deviceId: string, label: string): { message: string } {
  return { message: deviceId ? `点击读取${label}` : `选择设备后读取${label}` };
}

export function buildTargetedShadowActionMessage(targetDeviceId: string, actionText: string): string {
  return `设备 ${targetDeviceId} 的${actionText}`;
}

export function formatShadowLastSuccessTime(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString();
}
