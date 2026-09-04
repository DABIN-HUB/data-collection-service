import { defineStore } from "pinia";

import { getDevicePointConfig, saveDevicePointConfig } from "@/api/point.api";
import { applyPointBatchEdit, buildIncrementalPoints, normalizePointRows, type BuildIncrementalPointsOptions, type PointBatchEditPayload } from "@/features/point/utils/point-editor-utils";
import type { DataPoint } from "@/types/point";

interface PointState {
  pointsByDevice: Record<string, DataPoint[]>;
  selectedIdsByDevice: Record<string, string[]>;
  loadGenerationByDevice: Record<string, number>;
  loadingByDevice: Record<string, boolean>;
  savingCountByDevice: Record<string, number>;
  errorByDevice: Record<string, string>;
}

export const usePointStore = defineStore("point", {
  state: (): PointState => ({
    pointsByDevice: {},
    selectedIdsByDevice: {},
    loadGenerationByDevice: {},
    loadingByDevice: {},
    savingCountByDevice: {},
    errorByDevice: {}
  }),
  getters: {
    getPoints: (state) => (deviceId: string) => state.pointsByDevice[deviceId] || [],
    getSelectedIds: (state) => (deviceId: string) => state.selectedIdsByDevice[deviceId] || [],
    isLoading: (state) => (deviceId: string) => Boolean(state.loadingByDevice[deviceId]),
    isSaving: (state) => (deviceId: string) => (state.savingCountByDevice[deviceId] || 0) > 0,
    errorFor: (state) => (deviceId: string) => state.errorByDevice[deviceId] || ""
  },
  actions: {
    async load(deviceId: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) {
        return;
      }
      const requestGeneration = (this.loadGenerationByDevice[targetDeviceId] || 0) + 1;
      this.loadGenerationByDevice[targetDeviceId] = requestGeneration;
      this.loadingByDevice[targetDeviceId] = true;
      this.errorByDevice[targetDeviceId] = "";
      try {
        const response = await getDevicePointConfig(targetDeviceId, true);
        if (requestGeneration !== this.loadGenerationByDevice[targetDeviceId]) {
          return;
        }
        this.pointsByDevice[targetDeviceId] = normalizePointRows(response.points || []);
      } catch (error) {
        if (requestGeneration !== this.loadGenerationByDevice[targetDeviceId]) {
          return;
        }
        this.errorByDevice[targetDeviceId] = error instanceof Error ? error.message : "点位配置加载失败";
      } finally {
        if (requestGeneration === this.loadGenerationByDevice[targetDeviceId]) {
          this.loadingByDevice[targetDeviceId] = false;
        }
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
      this.pointsByDevice[deviceId] = normalizePointRows([
        ...rows,
        {
          pointCode: `point_${String(nextIndex).padStart(3, "0")}`,
          pointName: `点位${String(nextIndex).padStart(3, "0")}`,
          address: "40001",
          dataType: "FLOAT",
          readWrite: "R",
          unit: "-"
        }
      ]);
    },
    appendGeneratedPoints(deviceId: string, options: BuildIncrementalPointsOptions) {
      this.pointsByDevice[deviceId] = normalizePointRows([
        ...this.getPoints(deviceId),
        ...buildIncrementalPoints(options)
      ]);
    },
    replacePoints(deviceId: string, points: DataPoint[]) {
      this.pointsByDevice[deviceId] = normalizePointRows(points);
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
      if (!targetDeviceId) {
        return;
      }
      this.errorByDevice[targetDeviceId] = "";
    },
    setError(deviceId: string, message: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) {
        return;
      }
      this.errorByDevice[targetDeviceId] = message;
    },
    async save(deviceId: string) {
      const targetDeviceId = normalizeDeviceId(deviceId);
      if (!targetDeviceId) {
        return;
      }
      const payload = clonePoints(this.getPoints(targetDeviceId));
      this.savingCountByDevice[targetDeviceId] = (this.savingCountByDevice[targetDeviceId] || 0) + 1;
      this.errorByDevice[targetDeviceId] = "";
      try {
        await saveDevicePointConfig(targetDeviceId, payload);
        await this.load(targetDeviceId);
      } catch (error) {
        this.errorByDevice[targetDeviceId] = error instanceof Error ? error.message : "点位配置保存失败";
      } finally {
        this.savingCountByDevice[targetDeviceId] = Math.max(0, (this.savingCountByDevice[targetDeviceId] || 0) - 1);
      }
    }
  }
});

function normalizeDeviceId(deviceId: string): string {
  return typeof deviceId === "string" ? deviceId.trim() : "";
}

function clonePoints(points: DataPoint[]): DataPoint[] {
  return JSON.parse(JSON.stringify(points || [])) as DataPoint[];
}
