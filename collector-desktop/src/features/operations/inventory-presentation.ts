import type { DeviceInfo } from "@/types/device";

// 展示可用性遵循 device.store.resolvePointCount 的字段顺序和数字类型；缺值不补零。
export function knownInventoryPointCount(devices: readonly DeviceInfo[]): number | null {
  let total = 0;
  for (const device of devices) {
    const selected = typeof device.pointCount === "number"
      ? device.pointCount
      : Array.isArray(device.points)
        ? device.points.length
        : device["pointTotal"] ?? device["pointsCount"] ?? device["dataPointCount"];
    if (typeof selected !== "number" || !Number.isFinite(selected) || selected < 0) return null;
    total += selected;
  }
  return Number.isFinite(total) ? total : null;
}
