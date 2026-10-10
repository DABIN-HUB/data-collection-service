import { runtimePresentation } from "@/features/diagnostic/utils/device-runtime-utils";
import type { DeviceViewModel } from "@/types/device";

// 仅拆分现有展示事实；生命周期、传输和协议状态不能推导点位健康。
export function deviceListPresentation(device: DeviceViewModel) {
  const runtime = device.runtime;
  const presentation = runtimePresentation(runtime, device.runtimeStale);
  const separator = presentation.transportProtocol.indexOf(" / 协议 ");
  const phase = String(runtime?.phase || "").toUpperCase();
  const health = String(runtime?.deviceHealth || "").toUpperCase();
  const count = (value?: number) => value === undefined ? "未知" : String(value);
  const phaseTone = device.runtimeStale ? "is-stale"
    : ["STARTING", "CONNECTING", "WAITING_FIRST_SAMPLE", "RECONNECTING"].includes(phase) ? "is-wait" : "";
  const healthTone = device.runtimeStale ? "is-stale"
    : ["ONLINE_HEALTHY", "HEALTHY", "GOOD"].includes(health) ? "is-good"
      : ["ONLINE_PARTIAL", "ONLINE_NO_DATA", "DEGRADED", "WAITING", "WAITING_FIRST_SAMPLE", "STALE"].includes(health) ? "is-warning"
        : ["FAILED", "UNHEALTHY", "ERROR"].includes(health) ? "is-error" : "";
  return {
    ...presentation,
    phaseTone,
    healthTone,
    transportText: separator >= 0 ? presentation.transportProtocol.slice(0, separator) : presentation.transportProtocol,
    protocolText: separator >= 0 ? presentation.transportProtocol.slice(separator + 3) : "协议 未知",
    good: count(runtime?.goodPointCount),
    total: count(runtime?.participatingPointCount ?? runtime?.configuredPointCount),
    failed: count(runtime?.failedPointCount),
    stale: count(runtime?.stalePointCount),
    waiting: count(runtime?.waitingPointCount)
  };
}
