import { defineStore } from "pinia";

import { getDevicePointConfig, saveDevicePointConfig } from "@/api/point.api";
import { applyPointBatchEdit, buildIncrementalPoints, normalizePointRows, type BuildIncrementalPointsOptions, type PointBatchEditPayload } from "@/components/point/point-editor-utils";
import type { DataPoint } from "@/types/point";

interface PointState {
  loading: boolean;
  saving: boolean;
  error: string;
  pointsByDevice: Record<string, DataPoint[]>;
  selectedIdsByDevice: Record<string, string[]>;
}

export const usePointStore = defineStore("point", {
  state: (): PointState => ({
    loading: false,
    saving: false,
    error: "",
    pointsByDevice: {},
    selectedIdsByDevice: {}
  }),
  getters: {
    getPoints: (state) => (deviceId: string) => state.pointsByDevice[deviceId] || [],
    getSelectedIds: (state) => (deviceId: string) => state.selectedIdsByDevice[deviceId] || []
  },
  actions: {
    async load(deviceId: string) {
      if (!deviceId) {
        return;
      }
      this.loading = true;
      this.error = "";
      try {
        const response = await getDevicePointConfig(deviceId, true);
        this.pointsByDevice[deviceId] = normalizePointRows(response.points || []);
      } catch (error) {
        this.error = error instanceof Error ? error.message : "点位配置加载失败";
      } finally {
        this.loading = false;
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
    async save(deviceId: string) {
      this.saving = true;
      this.error = "";
      try {
        await saveDevicePointConfig(deviceId, this.getPoints(deviceId));
        await this.load(deviceId);
      } catch (error) {
        this.error = error instanceof Error ? error.message : "点位配置保存失败";
      } finally {
        this.saving = false;
      }
    }
  }
});
