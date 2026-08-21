import { defineStore } from "pinia";

import { deleteLocalDevice, getDeviceDiff, triggerFullConfigSync } from "@/api/config.api";
import { getConfigDevices, getDeviceRuntime, getDeviceStatus, reloadDevices, startDevice, startLocalDevice, stopDevice } from "@/api/device.api";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";

interface DeviceState {
  loading: boolean;
  operating: boolean;
  error: string;
  devices: DeviceViewModel[];
  runtimeMap: Record<string, DeviceRuntimeSnapshot>;
  selectedDeviceId: string;
  lastUpdatedAt: number;
}

export const useDeviceStore = defineStore("device", {
  state: (): DeviceState => ({
    loading: false,
    operating: false,
    error: "",
    devices: [],
    runtimeMap: {},
    selectedDeviceId: "",
    lastUpdatedAt: 0
  }),
  getters: {
    selectedDevice: (state) => state.devices.find((device) => device.normalizedId === state.selectedDeviceId) || null,
    onlineCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "ONLINE").length,
    offlineCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "OFFLINE").length,
    errorCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "ERROR").length,
    totalPointCount: (state) => state.devices.reduce((total, device) => total + resolvePointCount(device), 0)
  },
  actions: {
    async refresh() {
      this.loading = true;
      this.error = "";
      try {
        const [deviceResponse, runtimeResponse] = await Promise.allSettled([
          getConfigDevices(),
          getDeviceRuntime()
        ]);
        if (runtimeResponse.status === "fulfilled") {
          this.runtimeMap = Object.fromEntries(runtimeResponse.value.map((item) => [item.deviceId, item]));
        }
        if (deviceResponse.status === "fulfilled") {
          const rawDevices = Array.isArray(deviceResponse.value.devices) ? deviceResponse.value.devices : [];
          this.devices = rawDevices.map((device) => normalizeDeviceViewModel(device, this.runtimeMap));
          if (!this.selectedDeviceId && this.devices.length > 0) {
            this.selectedDeviceId = this.devices[0].normalizedId;
          }
        } else {
          throw deviceResponse.reason;
        }
        this.lastUpdatedAt = Date.now();
      } catch (error) {
        this.error = error instanceof Error ? error.message : "设备列表加载失败";
      } finally {
        this.loading = false;
      }
    },
    selectDevice(deviceId: string) {
      this.selectedDeviceId = deviceId;
    },
    async start(deviceId: string) {
      await this.operate(() => startDevice(deviceId));
    },
    async startSmart(deviceId: string) {
      const device = this.devices.find((item) => item.normalizedId === deviceId);
      await this.operate(() => resolveDeviceStartMode(device) === "local" ? startLocalDevice(deviceId) : startDevice(deviceId));
    },
    async stop(deviceId: string) {
      await this.operate(() => stopDevice(deviceId));
    },
    async reload() {
      await this.operate(() => reloadDevices());
    },
    async syncConfig() {
      await this.operate(() => triggerFullConfigSync());
    },
    async deleteLocal(deviceId: string) {
      await this.operate(() => deleteLocalDevice(deviceId));
      if (this.selectedDeviceId === deviceId) {
        this.selectedDeviceId = this.devices[0]?.normalizedId || "";
      }
    },
    async loadStatus(deviceId: string): Promise<unknown> {
      return getDeviceStatus(deviceId);
    },
    async loadDiff(deviceId: string): Promise<unknown> {
      return getDeviceDiff(deviceId);
    },
    async operate(action: () => Promise<unknown>) {
      this.operating = true;
      this.error = "";
      try {
        await action();
        await this.refresh();
      } catch (error) {
        this.error = error instanceof Error ? error.message : "设备操作失败";
      } finally {
        this.operating = false;
      }
    }
  }
});

export function normalizeDeviceViewModel(device: DeviceInfo, runtimeMap: Record<string, DeviceRuntimeSnapshot> = {}): DeviceViewModel {
  const normalizedId = String(device.deviceId || device.id || device.connectionKey || "");
  const runtime = normalizedId ? runtimeMap[normalizedId] : undefined;
  return {
    ...device,
    normalizedId,
    displayName: String(device.deviceName || device.deviceAlias || normalizedId || "未命名设备"),
    displayGroup: String(device.groupName || device.groupId || "未分组"),
    displayProtocol: String(device.protocolType || device.connectionType || "未知协议"),
    runtime
  };
}

export function resolveDeviceStatus(device: DeviceViewModel): "ONLINE" | "CONNECTING" | "ERROR" | "OFFLINE" | "DISABLED" {
  if (device.runtime?.reconnecting || device.runtime?.starting) {
    return "CONNECTING";
  }
  if (device.runtime?.connected || device.runtime?.running || device.status === "ONLINE") {
    return "ONLINE";
  }
  if (device.runtime?.degradedReason || device.status === "ERROR") {
    return "ERROR";
  }
  if (device.status === "DISABLED") {
    return "DISABLED";
  }
  return "OFFLINE";
}

export function resolvePointCount(device: DeviceViewModel): number {
  if (typeof device.pointCount === "number") {
    return device.pointCount;
  }
  if (Array.isArray(device.points)) {
    return device.points.length;
  }
  const raw = device["pointTotal"] ?? device["pointsCount"] ?? device["dataPointCount"];
  return typeof raw === "number" ? raw : 0;
}

export function isLocalDevice(device: DeviceViewModel | undefined): boolean {
  if (!device) {
    return false;
  }
  if (device.temporaryConfig === true || device["local"] === true || device["localDevice"] === true) {
    return true;
  }
  const source = String(device.configSource || device["source"] || "").toUpperCase();
  return source.includes("LOCAL") || source.includes("TEMP");
}

export function resolveDeviceStartMode(device: DeviceViewModel | undefined): "local" | "remote" {
  return isLocalDevice(device) ? "local" : "remote";
}
