<template>
  <section class="monitor-panel realtime-workbench">
    <div class="panel-toolbar">
      <div class="table-actions">
        <el-tag effect="plain">实时通道：{{ wsStatusText }}</el-tag>
        <el-button :loading="webSocketStore.connecting" @click="connectWebSocket">连接实时通道</el-button>
        <el-button :loading="loading" @click="load">刷新实时值</el-button>
      </div>
    </div>
    <el-alert v-if="webSocketStore.error" :title="webSocketStore.error" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-table v-loading="loading" :data="filteredRows" height="360" border>
      <el-table-column prop="pointName" label="点位名称" min-width="160" />
      <el-table-column prop="pointCode" label="点位编码" min-width="150" />
      <el-table-column prop="address" label="地址" width="120" />
      <el-table-column label="当前值" min-width="130"><template #default="{ row }">{{ row.currentValue ?? row.value ?? '-' }}</template></el-table-column>
      <el-table-column label="质量" width="110"><template #default="{ row }"><el-tag :type="qualityType(row.quality)" effect="light">{{ qualityText(row.quality) }}</el-tag></template></el-table-column>
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column label="更新时间" min-width="160"><template #default="{ row }">{{ formatTime(row.timestamp || row.collectTime) }}</template></el-table-column>
      <el-table-column prop="processCostMs" label="耗时 ms" width="100" />
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";

import { getDeviceRealtimeData } from "@/api/data.api";
import { useWebSocketStore } from "@/stores/websocket.store";
import type { RealtimePointRow } from "@/types/monitor";

const props = withDefaults(defineProps<{
  deviceId: string;
  keyword?: string;
  autoRefresh?: boolean;
  refreshIntervalMs?: number;
}>(), {
  keyword: "",
  autoRefresh: false,
  refreshIntervalMs: 5000
});

const webSocketStore = useWebSocketStore();
const loading = ref(false);
const error = ref("");
const rows = ref<RealtimePointRow[]>([]);
let timer: ReturnType<typeof setInterval> | null = null;

const wsRows = computed(() => webSocketStore.rows(props.deviceId));
const displayRows = computed(() => wsRows.value.length > 0 ? wsRows.value : rows.value);
const filteredRows = computed(() => {
  const keyword = props.keyword.trim().toLowerCase();
  if (!keyword) {
    return displayRows.value;
  }
  return displayRows.value.filter((row) => [row.pointName, row.pointCode, row.address]
    .some((value) => String(value || "").toLowerCase().includes(keyword)));
});
const wsStatusText = computed(() => {
  if (webSocketStore.connected && webSocketStore.activeDeviceId === props.deviceId) {
    return "已连接";
  }
  if (webSocketStore.connecting) {
    return "连接中";
  }
  return "未连接";
});

async function load() {
  if (!props.deviceId) {
    rows.value = [];
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const response = await getDeviceRealtimeData(props.deviceId);
    rows.value = response.points || response.data || response.values || [];
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "实时数据加载失败";
  } finally {
    loading.value = false;
  }
}

function connectWebSocket() {
  webSocketStore.connectRealtime(props.deviceId);
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

function qualityType(value: unknown): "success" | "warning" | "danger" | "info" {
  const quality = String(value || "").toUpperCase();
  if (["GOOD", "OK", "SUCCESS", "100"].includes(quality)) {
    return "success";
  }
  if (["BAD", "ERROR", "FAILED"].includes(quality)) {
    return "danger";
  }
  if (["UNCERTAIN", "WARN", "WARNING"].includes(quality)) {
    return "warning";
  }
  return "info";
}

function qualityText(value: unknown): string {
  const quality = String(value || "UNKNOWN").toUpperCase();
  return {
    GOOD: "良好",
    OK: "良好",
    SUCCESS: "良好",
    BAD: "异常",
    ERROR: "异常",
    FAILED: "失败",
    UNCERTAIN: "不确定",
    WARN: "警告",
    WARNING: "警告",
    UNKNOWN: "未知"
  }[quality] || quality;
}

function syncTimer() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  if (props.autoRefresh && props.deviceId) {
    timer = setInterval(() => load(), Math.max(1000, props.refreshIntervalMs));
  }
}

defineExpose({ load });

onMounted(() => {
  connectWebSocket();
  load();
  syncTimer();
});
onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer);
  }
});
watch(() => props.deviceId, () => {
  connectWebSocket();
  load();
  syncTimer();
});
watch(() => [props.autoRefresh, props.refreshIntervalMs], syncTimer);
</script>
