<template>
  <div class="page-stack">
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />

    <section class="exact-surface history-query-bar">
      <div class="command-strip">
        <el-select v-model="deviceId" filterable placeholder="选择设备" class="compact-select" @change="loadPoints">
          <el-option v-for="device in devices" :key="device.id" :label="device.name" :value="device.id" />
        </el-select>
        <el-select v-model="pointId" filterable placeholder="选择点位" class="compact-select">
          <el-option v-for="point in points" :key="point.pointId || point.pointCode" :label="`${point.pointName || point.pointCode} (${point.pointCode || point.pointId})`" :value="point.pointId || point.pointCode || point.address" />
        </el-select>
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" />
        <el-input-number v-model="limit" :min="1" :max="5000" controls-position="right" />
        <el-button type="primary" :loading="historyLoading" @click="loadHistory">查询历史</el-button>
        <el-button :loading="deviceLoading" @click="loadDevices">刷新设备</el-button>
      </div>
    </section>

    <section class="exact-surface">
      <div class="exact-surface-head"><h3>历史趋势</h3><span>{{ historyRows.length }} 条记录</span></div>
      <div ref="chartRef" class="history-chart"></div>
    </section>

    <section class="exact-table-card">
      <div class="exact-table-title"><h3>原始历史数据</h3><span>来自后端历史查询结果</span></div>
      <el-table :data="historyRows" border height="340">
        <el-table-column label="时间" min-width="180"><template #default="{ row }">{{ formatTime(row.timestamp || row.time || row.collectTime) }}</template></el-table-column>
        <el-table-column label="值" min-width="140"><template #default="{ row }">{{ row.value ?? row.currentValue ?? '-' }}</template></el-table-column>
        <el-table-column label="质量" width="110"><template #default="{ row }">{{ row.quality || '-' }}</template></el-table-column>
        <el-table-column label="原始记录" min-width="260"><template #default="{ row }"><code>{{ JSON.stringify(row) }}</code></template></el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import * as echarts from "echarts";
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";

import { getConfigDevices } from "@/api/config.api";
import { getPointHistory } from "@/api/data.api";
import { getDevicePointConfig } from "@/api/point.api";
import type { DataPoint } from "@/types/point";
import { normalizeDeviceOptions, normalizeHistoryRows, type DeviceOption, type HistoryRow } from "@/views/runtime/runtime-utils";

const deviceLoading = ref(false);
const historyLoading = ref(false);
const error = ref("");
const devices = ref<DeviceOption[]>([]);
const points = ref<DataPoint[]>([]);
const deviceId = ref("");
const pointId = ref("");
const limit = ref(500);
const timeRange = ref<[Date, Date] | null>(null);
const historyRows = ref<HistoryRow[]>([]);
const chartRef = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

async function loadDevices() {
  deviceLoading.value = true;
  error.value = "";
  try {
    devices.value = normalizeDeviceOptions(await getConfigDevices());
    deviceId.value = deviceId.value || devices.value[0]?.id || "";
    if (deviceId.value) {
      await loadPoints();
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "设备列表加载失败";
  } finally {
    deviceLoading.value = false;
  }
}

async function loadPoints() {
  if (!deviceId.value) {
    points.value = [];
    return;
  }
  try {
    const response = await getDevicePointConfig(deviceId.value, true);
    points.value = response.points || [];
    pointId.value = pointId.value || points.value[0]?.pointId || points.value[0]?.pointCode || "";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "点位配置加载失败";
  }
}

async function loadHistory() {
  if (!deviceId.value || !pointId.value) {
    error.value = "请选择设备和点位";
    return;
  }
  historyLoading.value = true;
  error.value = "";
  try {
    const params: Record<string, string | number | undefined> = { limit: limit.value };
    if (timeRange.value) {
      params.startTs = timeRange.value[0].getTime();
      params.endTs = timeRange.value[1].getTime();
    }
    historyRows.value = normalizeHistoryRows(await getPointHistory(deviceId.value, pointId.value, params));
    await nextTick();
    renderChart();
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "历史数据加载失败";
  } finally {
    historyLoading.value = false;
  }
}

function renderChart() {
  if (!chartRef.value) {
    return;
  }
  chart = chart || echarts.init(chartRef.value);
  chart.setOption({
    grid: { left: 42, right: 18, top: 24, bottom: 36 },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: historyRows.value.map((row) => formatTime(row.timestamp || row.time || row.collectTime)) },
    yAxis: { type: "value" },
    series: [{ type: "line", smooth: true, data: historyRows.value.map((row) => Number(row.value ?? row.currentValue ?? 0)) }]
  });
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

onMounted(loadDevices);
onBeforeUnmount(() => chart?.dispose());
</script>
