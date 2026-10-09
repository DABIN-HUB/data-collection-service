import { defineStore } from "pinia";

import { getHealth, getRuntimeStatus } from "@/api/runtime.api";
import type { ConsoleRuntimeStatusSnapshot, HealthStatus } from "@/types/runtime";

interface RuntimeState {
  loading: boolean;
  connected: boolean;
  error: string;
  health: HealthStatus | null;
  runtime: ConsoleRuntimeStatusSnapshot | null;
  lastUpdatedAt: number;
  refreshGeneration: number;
  healthError: string;
  runtimeError: string;
  healthLastSuccessAt: number;
  runtimeLastSuccessAt: number;
}

export const useRuntimeStore = defineStore("runtime", {
  state: (): RuntimeState => ({
    loading: false,
    connected: false,
    error: "",
    health: null,
    runtime: null,
    lastUpdatedAt: 0,
    refreshGeneration: 0,
    healthError: "",
    runtimeError: "",
    healthLastSuccessAt: 0,
    runtimeLastSuccessAt: 0
  }),
  getters: {
    stale: (state) => Boolean((state.healthError && state.healthLastSuccessAt) || (state.runtimeError && state.runtimeLastSuccessAt)),
    runtimeLevel: (state) => state.healthError || state.runtimeError
      ? ((state.healthError && state.healthLastSuccessAt) || (state.runtimeError && state.runtimeLastSuccessAt) ? "STALE" : "UNKNOWN")
      : state.runtime?.level || state.health?.level || state.health?.status || "UNKNOWN",
    runtimeMessage: (state) => state.error || state.runtime?.message || state.health?.message || (state.connected ? "服务已连接" : "服务未连接"),
    generatedAtText: (state) => state.lastUpdatedAt ? new Date(state.lastUpdatedAt).toLocaleString() : "未刷新"
  },
  actions: {
    async refresh() {
      const requestGeneration = this.refreshGeneration + 1;
      this.refreshGeneration = requestGeneration;
      this.loading = true;
      this.error = "";
      const [healthResult, runtimeResult] = await Promise.allSettled([
        getHealth(),
        getRuntimeStatus()
      ]);
      if (requestGeneration !== this.refreshGeneration) {
        return;
      }
      this.healthError = healthResult.status === "rejected"
        ? healthResult.reason instanceof Error ? healthResult.reason.message : "健康接口不可用" : "";
      this.runtimeError = runtimeResult.status === "rejected"
        ? runtimeResult.reason instanceof Error ? runtimeResult.reason.message : "运行指标接口不可用" : "";
      if (healthResult.status === "fulfilled") {
        this.health = healthResult.value;
        this.healthLastSuccessAt = Date.now();
      }
      if (runtimeResult.status === "fulfilled") {
        this.runtime = runtimeResult.value;
        this.runtimeLastSuccessAt = Date.now();
      }
      this.connected = healthResult.status === "fulfilled" && runtimeResult.status === "fulfilled";
      this.error = [this.healthError && `健康检查：${this.healthError}`, this.runtimeError && `运行指标：${this.runtimeError}`].filter(Boolean).join("；");
      if (healthResult.status === "fulfilled" || runtimeResult.status === "fulfilled") this.lastUpdatedAt = Date.now();
      if (requestGeneration === this.refreshGeneration) {
        this.loading = false;
      }
    }
  }
});
