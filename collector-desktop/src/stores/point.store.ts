import { defineStore } from "pinia";

import { getDeviceConfigBundle, validateDeviceConfigBundle, commitDeviceConfigBundle } from "@/api/config.api";
import { ApiRequestError } from "@/api/http";
import { applyPointBatchEdit, buildIncrementalPoints, defaultCollectionMode, normalizePointRows, type BuildIncrementalPointsOptions, type PointBatchEditPayload } from "@/features/point/utils/point-editor-utils";
import type { DataPoint } from "@/types/point";
import type { DeviceConnection } from "@/types/config";
import type { DeviceInfo } from "@/types/device";

interface PointState {
  pointsByDevice: Record<string, DataPoint[]>;
  selectedIdsByDevice: Record<string, string[]>;
  loadGenerationByDevice: Record<string, number>;
  loadingByDevice: Record<string, boolean>;
  savingCountByDevice: Record<string, number>;
  errorByDevice: Record<string, string>;
  conflictByDevice: Record<string, boolean>;
  configVersionByDevice: Record<string, number>;
  persistedRevisionByDevice: Record<string, number | undefined>;
  bundleDeviceByDevice: Record<string, DeviceInfo | undefined>;
  bundleConnectionByDevice: Record<string, DeviceConnection | undefined>;
}

export const usePointStore = defineStore("point", {
  state: (): PointState => ({
    pointsByDevice: {},
    selectedIdsByDevice: {},
    loadGenerationByDevice: {},
    loadingByDevice: {},
    savingCountByDevice: {},
    errorByDevice: {},
    conflictByDevice: {},
    configVersionByDevice: {},
    persistedRevisionByDevice: {},
    bundleDeviceByDevice: {},
    bundleConnectionByDevice: {}
  }),
  getters: {
    getPoints: (state) => (deviceId: string) => state.pointsByDevice[deviceId] || [],
    getSelectedIds: (state) => (deviceId: string) => state.selectedIdsByDevice[deviceId] || [],
    isLoading: (state) => (deviceId: string) => Boolean(state.loadingByDevice[deviceId]),
    isSaving: (state) => (deviceId: string) => (state.savingCountByDevice[deviceId] || 0) > 0,
    errorFor: (state) => (deviceId: string) => state.errorByDevice[deviceId] || ""
  },
  actions: {
    async load(deviceId: string, explicit = false) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) return;
      if (this.conflictByDevice[targetDeviceId] && !explicit) return;
      const requestGeneration = (this.loadGenerationByDevice[targetDeviceId] || 0) + 1;
      this.loadGenerationByDevice[targetDeviceId] = requestGeneration;
      this.loadingByDevice[targetDeviceId] = true;
      this.errorByDevice[targetDeviceId] = "";
      try {
        const response = await getDeviceConfigBundle(targetDeviceId);
        if (requestGeneration !== this.loadGenerationByDevice[targetDeviceId]) return;
        if (this.conflictByDevice[targetDeviceId] && !explicit) return;
        const mode = defaultCollectionMode(response.device?.protocolType);
        this.pointsByDevice[targetDeviceId] = normalizePointRows(response.points || [], mode);
        this.configVersionByDevice[targetDeviceId] = response.configVersion;
        this.persistedRevisionByDevice[targetDeviceId] = response.persistedRevision;
        this.bundleDeviceByDevice[targetDeviceId] = response.device;
        this.bundleConnectionByDevice[targetDeviceId] = response.connection;
        this.conflictByDevice[targetDeviceId] = false;
      } catch (error) {
        if (requestGeneration !== this.loadGenerationByDevice[targetDeviceId]) return;
        this.errorByDevice[targetDeviceId] = error instanceof Error ? error.message : "点位配置加载失败";
      } finally {
        if (requestGeneration === this.loadGenerationByDevice[targetDeviceId]) this.loadingByDevice[targetDeviceId] = false;
      }
    },
    setSelectedIds(deviceId: string, ids: string[]) {
      this.selectedIdsByDevice[deviceId] = ids;
    },
    updateCell(deviceId: string, pointId: string, field: keyof DataPoint, value: unknown) {
      this.pointsByDevice[deviceId] = this.getPoints(deviceId).map((point) => point.pointId === pointId ? { ...point, [field]: value } : point);
    },
    addEmptyPoint(deviceId: string) {
      const rows = this.getPoints(deviceId);
      const nextIndex = rows.length + 1;
      const mode = defaultCollectionMode(this.bundleDeviceByDevice[deviceId]?.protocolType);
      this.pointsByDevice[deviceId] = normalizePointRows([
        ...rows,
        {
          pointCode: `point_${String(nextIndex).padStart(3, "0")}`,
          pointName: `点位${String(nextIndex).padStart(3, "0")}`,
          address: "40001",
          dataType: "FLOAT",
          readWrite: "R",
          collectionMode: mode,
          unit: "-"
        }
      ], mode);
    },
    appendGeneratedPoints(deviceId: string, options: BuildIncrementalPointsOptions) {
      const mode = defaultCollectionMode(this.bundleDeviceByDevice[deviceId]?.protocolType);
      this.pointsByDevice[deviceId] = normalizePointRows([
        ...this.getPoints(deviceId),
        ...buildIncrementalPoints({ ...options, collectionMode: options.collectionMode || mode })
      ], mode);
    },
    replacePoints(deviceId: string, points: DataPoint[]) {
      const mode = defaultCollectionMode(this.bundleDeviceByDevice[deviceId]?.protocolType);
      this.pointsByDevice[deviceId] = normalizePointRows(points, mode);
      this.selectedIdsByDevice[deviceId] = [];
    },
    applyBatch(deviceId: string, payload: PointBatchEditPayload) {
      this.pointsByDevice[deviceId] = applyPointBatchEdit(this.getPoints(deviceId), this.getSelectedIds(deviceId), payload);
    },
    removeSelected(deviceId: string) {
      const selected = new Set(this.getSelectedIds(deviceId));
      this.pointsByDevice[deviceId] = this.getPoints(deviceId).filter((point) => !selected.has(point.pointId || ""));
      this.selectedIdsByDevice[deviceId] = [];
    },
    clearError(deviceId: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) return;
      this.errorByDevice[targetDeviceId] = "";
    },
    setError(deviceId: string, message: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) return;
      this.errorByDevice[targetDeviceId] = message;
    },
    async save(deviceId: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) return;
      const payload = clonePoints(this.getPoints(targetDeviceId));
      const device = this.bundleDeviceByDevice[targetDeviceId];
      const connection = this.bundleConnectionByDevice[targetDeviceId];
      const baseVersion = this.configVersionByDevice[targetDeviceId] || 0;
      const baseRevision = this.persistedRevisionByDevice[targetDeviceId];
      if (!device || !connection) {
        this.errorByDevice[targetDeviceId] = "设备完整配置尚未加载";
        return;
      }
      this.savingCountByDevice[targetDeviceId] = (this.savingCountByDevice[targetDeviceId] || 0) + 1;
      this.errorByDevice[targetDeviceId] = "";
      try {
        const bundlePayload = { baseVersion, ...(baseRevision !== undefined ? { baseRevision } : {}), device: clone(device), connection: clone(connection), points: payload };
        const validation = await validateDeviceConfigBundle(targetDeviceId, bundlePayload);
        if (!validation.valid) {
          this.errorByDevice[targetDeviceId] = validation.errors?.join("；") || "设备配置校验失败";
          return;
        }
        const result = await commitDeviceConfigBundle(targetDeviceId, bundlePayload);
        if (result.committed === false) {
          this.errorByDevice[targetDeviceId] = "设备配置提交未确认，点位草稿已保留；请刷新配置并检查服务状态后再处理";
          return;
        }
        this.configVersionByDevice[targetDeviceId] = result.configVersion;
        this.persistedRevisionByDevice[targetDeviceId] = result.persistedRevision;
        if (result.reloadRequired === true || result.reconciled === false) {
          this.errorByDevice[targetDeviceId] = "点位配置已持久化，但运行态尚未确认应用；草稿已保留，请显式刷新配置并检查服务恢复状态，避免重复提交";
          return;
        }
        await this.load(targetDeviceId);
        if (result.committed === true && result.reconciled === true && !this.errorByDevice[targetDeviceId]) {
          this.errorByDevice[targetDeviceId] = "点位配置已持久化并完成恢复对齐，请刷新查看运行状态";
        }
      } catch (error) {
        const conflict = error instanceof ApiRequestError && (error.machineCode === "CONFIG_VERSION_CONFLICT" || error.httpStatus === 409);
        if (conflict) {
          this.conflictByDevice[targetDeviceId] = true;
          this.loadGenerationByDevice[targetDeviceId] = (this.loadGenerationByDevice[targetDeviceId] || 0) + 1;
          this.loadingByDevice[targetDeviceId] = false;
        }
        this.errorByDevice[targetDeviceId] = conflict
          ? "设备持久配置已变化，点位草稿已保留；请显式重新加载配置并核对修改后再保存"
          : error instanceof Error ? error.message : "点位配置保存失败";
      } finally {
        this.savingCountByDevice[targetDeviceId] = Math.max(0, (this.savingCountByDevice[targetDeviceId] || 0) - 1);
      }
    }
  }
});

function normalizeDeviceId(deviceId: string): string {
  return typeof deviceId === "string" ? deviceId.trim() : "";
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
function clonePoints(points: DataPoint[]): DataPoint[] {
  return clone(points || []);
}
