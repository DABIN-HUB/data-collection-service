<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">工作台首页</span>
        <h2>全局运行总览</h2>
        <p>{{ runtimeStore.runtimeMessage }}</p>
      </div>
      <el-button type="primary" :loading="loading" @click="refresh">刷新总览</el-button>
    </section>

    <section class="metric-grid">
      <MetricCard title="运行中设备" :value="deviceStore.onlineCount" description="当前在线或运行中的设备" icon="Connection" tone="green" />
      <MetricCard title="离线设备" :value="deviceStore.offlineCount" description="未连接或未运行设备" icon="CircleClose" tone="gray" />
      <MetricCard title="点位总数" :value="deviceStore.totalPointCount" description="来自设备配置摘要" icon="Grid" tone="blue" />
      <MetricCard title="采集速率" :value="collectionRateText" description="来自运行指标快照" icon="TrendCharts" tone="blue" />
      <MetricCard title="告警数量" :value="alarmCountText" description="来自运行风险和异常指标" icon="Warning" tone="red" />
    </section>

    <section class="dashboard-grid">
      <article class="dashboard-card chart-card">
        <div class="card-head">
          <h3>采集速率（点/s）</h3>
          <span>实时曲线接口下一阶段接入</span>
        </div>
        <div ref="rateChartRef" class="chart-box"></div>
      </article>
      <article class="dashboard-card online-card">
        <div class="card-head"><h3>设备在线率</h3><span>{{ onlineRate }}%</span></div>
        <div ref="onlineChartRef" class="chart-box small"></div>
        <div class="legend-row"><i class="dot good"></i>在线 {{ deviceStore.onlineCount }}</div>
        <div class="legend-row"><i class="dot muted"></i>离线 {{ deviceStore.offlineCount }}</div>
      </article>
    </section>

    <section class="dashboard-grid bottom">
      <article class="dashboard-card">
        <div class="card-head"><h3>最新告警</h3><span>下一阶段接入告警中心</span></div>
        <el-empty description="暂无已加载告警数据" />
      </article>
      <article class="dashboard-card">
        <div class="card-head"><h3>实时日志</h3><span>下一阶段接入运行日志</span></div>
        <el-empty description="暂无已加载日志数据" />
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import * as echarts from "echarts";
import { computed, nextTick, onMounted, ref, watch } from "vue";

import MetricCard from "@/components/dashboard/MetricCard.vue";
import { useDeviceStore } from "@/stores/device.store";
import { useRuntimeStore } from "@/stores/runtime.store";

const runtimeStore = useRuntimeStore();
const deviceStore = useDeviceStore();
const rateChartRef = ref<HTMLDivElement | null>(null);
const onlineChartRef = ref<HTMLDivElement | null>(null);
let rateChart: echarts.ECharts | null = null;
let onlineChart: echarts.ECharts | null = null;

const loading = computed(() => runtimeStore.loading || deviceStore.loading);
const onlineRate = computed(() => {
  const total = deviceStore.devices.length;
  return total ? Math.round((deviceStore.onlineCount / total) * 100) : 0;
});
const collectionRateText = computed(() => readNumber(runtimeStore.runtime?.performance, ["pointsPerSecond", "collectionRate", "currentRate"]) || "未知");
const alarmCountText = computed(() => {
  const exceptionCount = readNumber(runtimeStore.runtime?.exceptions, ["total", "totalCount", "alarmCount"]);
  return exceptionCount || runtimeStore.runtime?.risks?.length || 0;
});

async function refresh() {
  await Promise.allSettled([runtimeStore.refresh(), deviceStore.refresh()]);
  await nextTick();
  renderCharts();
}

function renderCharts() {
  if (rateChartRef.value) {
    rateChart = rateChart || echarts.init(rateChartRef.value);
    rateChart.setOption({
      grid: { left: 36, right: 16, top: 24, bottom: 28 },
      tooltip: { trigger: "axis" },
      xAxis: { type: "category", data: ["00:00", "06:00", "12:00", "18:00", "24:00"] },
      yAxis: { type: "value" },
      series: [{ type: "line", smooth: true, data: [], areaStyle: {}, lineStyle: { color: "#2563eb", width: 3 } }]
    });
  }
  if (onlineChartRef.value) {
    onlineChart = onlineChart || echarts.init(onlineChartRef.value);
    onlineChart.setOption({
      tooltip: { trigger: "item" },
      series: [{
        type: "pie",
        radius: ["62%", "82%"],
        label: { show: true, formatter: `${onlineRate.value}%` },
        data: [
          { value: deviceStore.onlineCount, name: "在线", itemStyle: { color: "#22c55e" } },
          { value: deviceStore.offlineCount, name: "离线", itemStyle: { color: "#94a3b8" } }
        ]
      }]
    });
  }
}

function readNumber(source: unknown, keys: string[]): number | "" {
  if (!source || typeof source !== "object") {
    return "";
  }
  for (const key of keys) {
    const value = (source as Record<string, unknown>)[key];
    if (typeof value === "number") {
      return value;
    }
  }
  return "";
}

onMounted(refresh);
watch(() => deviceStore.devices.length, () => nextTick(renderCharts));
</script>
