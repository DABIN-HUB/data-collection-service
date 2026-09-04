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
}

export const useRuntimeStore = defineStore("runtime", {
  state: (): RuntimeState => ({
    loading: false,
    connected: false,
    error: "",
    health: null,
    runtime: null,
    lastUpdatedAt: 0,
    refreshGeneration: 0
  }),
  getters: {
    runtimeLevel: (state) => state.runtime?.level || state.health?.level || state.health?.status || "UNKNOWN",
    runtimeMessage: (state) => state.runtime?.message || state.health?.message || (state.connected ? "服务已连接" : "服务未连接"),
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
      if (healthResult.status === "fulfilled") {
        this.health = healthResult.value;
      }
      if (runtimeResult.status === "fulfilled") {
        this.runtime = runtimeResult.value;
      }
      this.connected = healthResult.status === "fulfilled" || runtimeResult.status === "fulfilled";
      if (!this.connected) {
        const reason = healthResult.status === "rejected" ? healthResult.reason : runtimeResult.status === "rejected" ? runtimeResult.reason : null;
        this.error = reason instanceof Error ? reason.message : "无法连接采集服务";
      }
      this.lastUpdatedAt = Date.now();
      if (requestGeneration === this.refreshGeneration) {
        this.loading = false;
      }
    }
  }
});
