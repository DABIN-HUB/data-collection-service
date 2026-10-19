import { defineStore } from "pinia";

import { deleteLocalDevice, getDeviceDiff, triggerFullConfigSync } from "@/api/config.api";
import { getConfigDevices, getDeviceRuntime, getDeviceRuntimeSnapshot, getDeviceStatus, reloadDevices, startDevice, startLocalDevice, stopDevice } from "@/api/device.api";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceStatusResponse, DeviceViewModel } from "@/types/device";

export interface DeviceActionResult<T = unknown> {
  ok: boolean;
  result?: T;
  error?: string;
  runtime?: DeviceRuntimeSnapshot;
  refreshError?: string;
}

export const useDeviceStore = defineStore("device", {
  state: () => ({
    loading: false,
    syncOperating: false,
    error: "",
    syncError: "",
    devices: [] as DeviceViewModel[],
    runtimeMap: {} as Record<string, DeviceRuntimeSnapshot>,
    runtimeErrors: {} as Record<string, string>,
    deviceOperations: {} as Record<string, boolean>,
    deviceErrors: {} as Record<string, string>,
    deviceEpochs: {} as Record<string, number>,
    runtimeReadEpochs: {} as Record<string, number>,
    requestSequence: 0,
    selectedDeviceId: "",
    lastUpdatedAt: 0,
    refreshGeneration: 0
  }),
  getters: {
    operating: (state) => state.syncOperating || Object.values(state.deviceOperations).some(Boolean),
    isDeviceOperating: (state) => (deviceId: string) => Boolean(state.deviceOperations[deviceId]),
    selectedDevice: (state) => state.devices.find((device) => device.normalizedId === state.selectedDeviceId) || null,
    onlineCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "ONLINE").length,
    offlineCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "OFFLINE").length,
    errorCount: (state) => state.devices.filter((device) => resolveDeviceStatus(device) === "ERROR").length,
    totalPointCount: (state) => state.devices.reduce((total, device) => total + resolvePointCount(device), 0)
  },
  actions: {
    async refresh() {
      const generation = ++this.refreshGeneration;
      const epochs = { ...this.deviceEpochs };
      const reads = { ...this.runtimeReadEpochs };
      this.loading = true;
      this.error = "";
      const [devices, runtime] = await Promise.allSettled([getConfigDevices(), getDeviceRuntime()]);
      if (generation !== this.refreshGeneration) return;
      const canCommit = (id: string) => (epochs[id] || 0) === (this.deviceEpochs[id] || 0)
        && (reads[id] || 0) === (this.runtimeReadEpochs[id] || 0) && !this.isDeviceOperating(id);
      const raw = devices.status === "fulfilled" && Array.isArray(devices.value.devices) ? devices.value.devices : null;
      const ids = new Set([...this.devices.map((d) => d.normalizedId), ...Object.keys(this.runtimeMap), ...(raw || []).map(canonicalDeviceId)]);
      if (runtime.status === "fulfilled") {
        const rows = Array.isArray(runtime.value) ? runtime.value : [];
        const seen = new Set<string>();
        for (const row of rows) {
          const id = canonicalDeviceId(row);
          if (!id) continue;
          seen.add(id);
          if (canCommit(id)) this.commitRuntime({ ...row, deviceId: id });
        }
        for (const id of ids) {
          if (!canCommit(id)) continue;
          if (!seen.has(id)) {
            this.runtimeErrors[id] = "运行快照缺失";
          }
        }
      } else {
        const message = errorMessage(runtime.reason, "设备运行状态加载失败");
        this.error = message;
        for (const id of ids) if (canCommit(id)) this.runtimeErrors[id] = message;
      }
      if (raw) {
        const previous = this.devices;
        this.devices = raw.filter((d) => canonicalDeviceId(d)).map((d) => {
          const id = canonicalDeviceId(d);
          const old = previous.find((item) => item.normalizedId === id);
          return this.projectDevice(!canCommit(id) && old ? old : d);
        });
        // 读请求开始后被写入的设备不能被旧配置列表移除。
        for (const old of previous) {
          if (!canCommit(old.normalizedId) && !this.devices.some((d) => d.normalizedId === old.normalizedId)) this.devices.push(this.projectDevice(old));
        }
        const known = new Set(this.devices.map((d) => d.normalizedId));
        for (const id of ids) if (!known.has(id) && canCommit(id)) this.clearDeviceIndexes(id);
        if (!known.has(this.selectedDeviceId)) this.selectedDeviceId = this.devices[0]?.normalizedId || "";
        this.lastUpdatedAt = Date.now();
      } else if (devices.status === "rejected") {
        this.error = [this.error, errorMessage(devices.reason, "设备列表加载失败")].filter(Boolean).join("；");
      }
      this.reprojectDevices();
      this.loading = false;
    },
    projectDevice(device: DeviceInfo): DeviceViewModel {
      const id = canonicalDeviceId(device);
      return normalizeDeviceViewModelWithRuntimeStatus({
        ...device,
        status: device.configStatus as string | undefined ?? device.status,
        runtimeStale: Boolean(this.runtimeErrors[id]),
        runtimeError: this.runtimeErrors[id] || ""
      }, this.runtimeMap);
    },
    reprojectDevices() {
      this.devices = this.devices.map((d) => this.projectDevice(d));
    },
    commitRuntime(runtime: DeviceRuntimeSnapshot): boolean {
      const id = canonicalDeviceId(runtime);
      if (!id || isOlderRuntime(runtime, this.runtimeMap[id])) return false;
      this.runtimeMap[id] = { ...runtime, deviceId: id };
      this.runtimeErrors[id] = "";
      this.reprojectDevices();
      return true;
    },
    async refreshRuntime(deviceId: string) {
      const epoch = this.deviceEpochs[deviceId] || 0;
      const ticket = ++this.requestSequence;
      this.runtimeReadEpochs[deviceId] = ticket;
      const current = () => this.runtimeReadEpochs[deviceId] === ticket && (this.deviceEpochs[deviceId] || 0) === epoch;
      try {
        const runtime = await getDeviceRuntimeSnapshot(deviceId);
        if (!current()) return;
        if (canonicalDeviceId(runtime) !== deviceId) throw new Error("设备运行快照身份不匹配");
        this.commitRuntime(runtime);
      } catch (error) {
        if (!current()) return;
        const message = errorMessage(error, "设备运行状态加载失败");
        this.runtimeErrors[deviceId] = message;
        this.reprojectDevices();
        return message;
      }
    },
    selectDevice(deviceId: string) { this.selectedDeviceId = deviceId; },
    start(deviceId: string) { return this.operate(() => startDevice(deviceId), deviceId); },
    startSmart(deviceId: string) {
      const device = this.devices.find((d) => d.normalizedId === deviceId);
      return this.operate(() => resolveDeviceStartMode(device) === "local" ? startLocalDevice(deviceId) : startDevice(deviceId), deviceId);
    },
    stop(deviceId: string) { return this.operate(() => stopDevice(deviceId), deviceId); },
    reload() { return this.operate(() => reloadDevices()); },
    syncConfig() { return this.operate(() => triggerFullConfigSync()); },
    syncRemoteDevices() { return this.syncConfig(); },
    async deleteLocal(deviceId: string) {
      return this.operate(async () => {
        const result = await deleteLocalDevice(deviceId);
        ++this.refreshGeneration;
        this.loading = false;
        this.devices = this.devices.filter((d) => d.normalizedId !== deviceId);
        this.clearDeviceIndexes(deviceId);
        if (this.selectedDeviceId === deviceId) this.selectedDeviceId = this.devices[0]?.normalizedId || "";
        return result;
      }, deviceId, false);
    },
    clearDeviceIndexes(deviceId: string) {
      delete this.runtimeMap[deviceId];
      delete this.runtimeErrors[deviceId];
      delete this.deviceErrors[deviceId];
      delete this.deviceEpochs[deviceId];
      delete this.runtimeReadEpochs[deviceId];
      // 在途写操作仍持锁，只有它自己的 finally 可以释放。
      if (!this.deviceOperations[deviceId]) delete this.deviceOperations[deviceId];
    },
    async loadStatus(deviceId: string): Promise<DeviceStatusResponse> { return getDeviceStatus(deviceId); },
    async loadDiff(deviceId: string): Promise<unknown> { return getDeviceDiff(deviceId); },
    async operate<T>(action: () => Promise<T>, deviceId = "", refreshRuntime = true): Promise<DeviceActionResult<T>> {
      if (deviceId ? this.isDeviceOperating(deviceId) : this.syncOperating) return { ok: false, error: deviceId ? "该设备正在执行操作" : "配置同步正在执行" };
      if (deviceId) {
        this.deviceOperations[deviceId] = true;
        this.deviceErrors[deviceId] = "";
        this.deviceEpochs[deviceId] = ++this.requestSequence;
      } else {
        this.syncOperating = true;
        this.syncError = "";
      }
      try {
        const result = await action();
        if (result && typeof result === "object" && "accepted" in result && result.accepted === false) throw new Error("后端未接受设备操作");
        const runtime = extractOperationRuntime(result);
        if (deviceId) {
          if (refreshRuntime) this.deviceEpochs[deviceId] = ++this.requestSequence;
          if (runtime && canonicalDeviceId(runtime) !== deviceId) throw new Error("设备操作返回身份不匹配");
          if (runtime) this.commitRuntime(runtime);
          const refreshError = refreshRuntime && !runtime ? await this.refreshRuntime(deviceId) : undefined;
          return { ok: true, result, runtime: this.runtimeMap[deviceId], refreshError };
        }
        await this.refresh();
        return { ok: true, result, refreshError: this.error || undefined };
      } catch (error) {
        const message = errorMessage(error, "设备操作失败");
        if (deviceId) {
          this.deviceErrors[deviceId] = message;
          this.runtimeErrors[deviceId] = message;
          this.reprojectDevices();
        } else this.syncError = message;
        return { ok: false, error: message };
      } finally {
        if (deviceId) delete this.deviceOperations[deviceId];
        else this.syncOperating = false;
      }
    }
  }
});

export function canonicalDeviceId(device: Pick<DeviceInfo, "deviceId" | "id"> | undefined): string {
  return String(device?.deviceId || device?.id || "");
}

export function isOlderRuntime(next: DeviceRuntimeSnapshot, previous?: DeviceRuntimeSnapshot): boolean {
  if (!previous) return false;
  if (previous.generation !== undefined && (next.generation === undefined || next.generation < previous.generation)) return true;
  return previous.generatedAt !== undefined && (next.generatedAt === undefined || next.generatedAt < previous.generatedAt);
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function normalizeDeviceViewModel(device: DeviceInfo, runtimeMap: Record<string, DeviceRuntimeSnapshot> = {}): DeviceViewModel {
  const normalizedId = canonicalDeviceId(device);
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

export function normalizeDeviceViewModelWithRuntimeStatus(device: DeviceInfo, runtimeMap: Record<string, DeviceRuntimeSnapshot> = {}): DeviceViewModel {
  const view = normalizeDeviceViewModel(device, runtimeMap);
  const effectiveStatus = resolveDeviceStatus(view);
  return {
    ...view,
    configStatus: view.status,
    runtimeStatus: effectiveStatus,
    status: effectiveStatus
  };
}

export function resolveDeviceStatus(device: DeviceViewModel): "ONLINE" | "CONNECTING" | "ERROR" | "OFFLINE" | "DISABLED" | "UNKNOWN" | "STALE" {
  const runtime = device.runtime;
  if (device.runtimeStale) return runtime ? "STALE" : "UNKNOWN";
  switch (runtime?.phase) {
    case "ONLINE": return "ONLINE";
    case "DEGRADED":
    case "FAILED": return "ERROR";
    case "STARTING":
    case "CONNECTING":
    case "WAITING_FIRST_SAMPLE":
    case "RECONNECTING": return "CONNECTING";
    case "STOPPED": return "OFFLINE";
  }
  if (device.configStatus === "DISABLED" || device.status === "DISABLED") return "DISABLED";
  return "UNKNOWN";
}

function extractOperationRuntime(result: unknown): DeviceRuntimeSnapshot | undefined {
  if (!result || typeof result !== "object") return undefined;
  const runtime = (result as { runtime?: unknown }).runtime;
  return runtime && typeof runtime === "object" ? runtime as DeviceRuntimeSnapshot : undefined;
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
