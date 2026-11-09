import { defineStore } from "pinia";

import { classifyConfigCommitResponse, getDeviceConfigBundle, validateDeviceConfigBundle, commitDeviceConfigBundle } from "@/api/config.api";
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
    blockReloadUntilExplicit(deviceId: string) {
      this.conflictByDevice[deviceId] = true;
      this.loadGenerationByDevice[deviceId] = (this.loadGenerationByDevice[deviceId] || 0) + 1;
      this.loadingByDevice[deviceId] = false;
    },
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
      if (this.isSaving(targetDeviceId)) return;
      if (this.conflictByDevice[targetDeviceId]) {
        this.errorByDevice[targetDeviceId] = "点位草稿已保留；请显式重新加载权威配置并核对后再保存，勿重复提交";
        return;
      }
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
        let outcome: ReturnType<typeof classifyConfigCommitResponse>;
        try { outcome = classifyConfigCommitResponse(result, targetDeviceId); }
        catch (invalid) { this.blockReloadUntilExplicit(targetDeviceId); throw invalid; }
        if (outcome !== "normal") {
          this.blockReloadUntilExplicit(targetDeviceId);
          this.errorByDevice[targetDeviceId] = outcome === "reconciled"
            ? "点位配置已提交，已完成权威对账；草稿已保留，请显式刷新配置核对，勿重复提交"
            : "点位配置已提交，运行态待恢复；草稿已保留，请显式刷新配置并检查服务恢复状态，勿重复提交";
          return;
        }
        this.configVersionByDevice[targetDeviceId] = result.configVersion!;
        if (result.persistedRevision !== undefined) this.persistedRevisionByDevice[targetDeviceId] = result.persistedRevision;
        await this.load(targetDeviceId);
      } catch (error) {
        const conflict = error instanceof ApiRequestError && (error.machineCode === "CONFIG_VERSION_CONFLICT" || error.httpStatus === 409);
        const uncertain = error instanceof ApiRequestError && error.machineCode === "CONFIG_COMMIT_UNCONFIRMED";
        if (conflict) {
          this.blockReloadUntilExplicit(targetDeviceId);
        }
        if (uncertain) this.blockReloadUntilExplicit(targetDeviceId);
        this.errorByDevice[targetDeviceId] = conflict
          ? "设备持久配置已变化，点位草稿已保留；请显式重新加载配置并核对修改后再保存"
          : uncertain ? "数据库提交结果待核对，点位草稿已保留；请勿重复提交，显式刷新配置后核对"
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
