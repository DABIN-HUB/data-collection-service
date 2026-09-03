<template>
  <section class="exact-page realtime-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>实时数据查询</h1>
        <span class="heading-online"><i></i>实时采集链路</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="loading" @click="refreshRealtime">立即刷新</button>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar">
        <div class="exact-toolbar-group">
          <button type="button" class="toggle-button" :class="{ 'is-active': realtimeAuto }" @click="realtimeAuto = !realtimeAuto">
            <span></span>自动刷新
          </button>
          <small>默认间隔 5 秒</small>
        </div>
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="realtimeDeviceId" @change="handleRealtimeDeviceChange">
            <option value="">全部设备</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          <input v-model="realtimeKeyword" type="search" placeholder="搜索点位名称、编码或地址" />
        </div>
      </div>
      <small v-if="realtimeError">{{ realtimeError }}</small>

      <div class="exact-diagnostic-cards realtime-summary-cards">
        <div class="exact-diagnostic-card"><span>实时记录</span><strong>{{ realtimeSummary.total }}</strong></div>
        <div class="exact-diagnostic-card"><span>质量正常</span><strong>{{ realtimeSummary.good }}</strong></div>
        <div class="exact-diagnostic-card"><span>异常/未知</span><strong>{{ realtimeSummary.bad }}</strong></div>
      </div>

      <section class="exact-surface realtime-single-panel">
        <div class="exact-surface-head">
          <h2>单点实时查询</h2>
          <span>按稳定 pointId / 点位编码查询</span>
        </div>
        <div class="exact-toolbar">
          <div class="exact-toolbar-group exact-toolbar-filters">
            <select v-model="realtimeSingleDeviceId">
              <option value="">选择设备</option>
              <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
                {{ device.displayName || device.normalizedId }}
              </option>
            </select>
            <input v-model="realtimeSinglePointId" type="text" placeholder="pointId 或点位编码" />
            <button type="button" class="primary" :disabled="!realtimeSingleDeviceId || !realtimeSinglePointId || singleLoading" @click="loadSingleRealtime">
              查询单点
            </button>
          </div>
        </div>
        <small v-if="singleRealtimeError">{{ singleRealtimeError }}</small>
        <pre class="json-view compact-result-view">{{ prettyJson(realtimeSingleResult) }}</pre>
      </section>

      <section class="exact-table-card">
        <table>
          <thead>
            <tr>
              <th>点位名称</th>
              <th>设备名称</th>
              <th>数据类型</th>
              <th>寄存器地址</th>
              <th>读写</th>
              <th>缩放</th>
              <th>当前值</th>
              <th>单位</th>
              <th>采集时间</th>
              <th>质量</th>
              <th>处理耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="filteredRealtimeRows.length === 0">
              <td colspan="12" class="exact-empty">选择“全部设备”可聚合查看所有设备实时数据，也可选择单设备过滤</td>
            </tr>
            <tr v-for="row in filteredRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode || row.address}`">
              <td>{{ row.pointName || row.pointCode || '-' }}</td>
              <td>{{ row.deviceName || deviceDisplayName(String(row.deviceId || realtimeDeviceId)) }}</td>
              <td>{{ row.dataType || '-' }}</td>
              <td><code>{{ realtimeAddress(row) }}</code></td>
              <td>{{ row.readWrite || '-' }}</td>
              <td>{{ realtimeScale(row) }}</td>
              <td><strong>{{ realtimeValueText(row) }}</strong></td>
              <td>{{ row.unit || '-' }}</td>
              <td>{{ formatTime(row.timestamp || row.collectTime || row.lastUpdateTime) }}</td>
              <td><span class="quality-badge" :class="realtimeQualityClass(row)">{{ realtimeQualityText(row) }}</span></td>
              <td>{{ realtimeProcessingText(row) }}</td>
              <td><button type="button" @click="pickRealtimePoint(row)">查单点</button></td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { getAllDeviceDataSummaries, getDeviceRealtimeData, getPointRealtimeData } from "@/api/data.api";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { RealtimePointRow } from "@/types/monitor";
import {
  buildRealtimeSummary,
  extractRealtimeDeviceIds,
  normalizeRealtimeRows,
  normalizeSinglePointRealtimeRow,
  realtimeAddress,
  realtimeProcessingText,
  realtimeQualityClass,
  realtimeQualityText,
  realtimeScale,
  realtimeValueText
} from "@/features/realtime/utils/realtime-utils";
import { createLatestRealtimeRequestOwner, type RealtimeRequestContext } from "@/features/realtime/utils/realtime-request-lifecycle";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();

const realtimeAuto = ref(true);
const realtimeDeviceId = ref("");
const realtimeKeyword = ref("");
const realtimeRows = ref<RealtimePointRow[]>([]);
const realtimeSingleDeviceId = ref("");
const realtimeSinglePointId = ref("");
const realtimeSingleResult = ref<unknown>({ message: "选择设备和点位后查询单点实时数据" });
const loading = ref(false);
const singleLoading = ref(false);
const realtimeError = ref("");
const singleRealtimeError = ref("");
let realtimeTimer: number | null = null;
const realtimeRequestOwner = createLatestRealtimeRequestOwner();
const singleRealtimeRequestOwner = createLatestRealtimeRequestOwner();

type RealtimeLoadSource = "init" | "manual" | "device-change" | "timer";

const filteredRealtimeRows = computed(() => {
  const keyword = realtimeKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return realtimeRows.value;
  }
  return realtimeRows.value.filter((row) => {
    const searchableValues = [
      row.pointName,
      row.pointCode,
      realtimeAddress(row),
      row.deviceName || deviceDisplayName(String(row.deviceId || realtimeDeviceId.value))
    ];
    return searchableValues.some((value) => String(value || "").toLowerCase().includes(keyword));
  });
});

const realtimeSummary = computed(() => buildRealtimeSummary(filteredRealtimeRows.value));

async function loadRealtime(source: RealtimeLoadSource = "manual") {
  if (source === "timer" && loading.value) {
    return;
  }
  const requestContext = currentMainRealtimeContext();
  const requestTicket = realtimeRequestOwner.begin(requestContext);
  loading.value = true;
  realtimeError.value = "";
  try {
    if (requestContext.mode === "device" && requestContext.deviceId) {
      const response = await getDeviceRealtimeData(requestContext.deviceId);
      const rows = normalizeRealtimeRows(response, requestContext.deviceId);
      if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
        return;
      }
      realtimeRows.value = rows;
      return;
    }

    if (!deviceStore.devices.length && !deviceStore.loading) {
      await deviceStore.refresh();
      if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
        return;
      }
    }

    const deviceSummaryResponse = await getAllDeviceDataSummaries();
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    const summaries = normalizeRealtimeRows(deviceSummaryResponse);
    const deviceIds = Array.from(
      new Set([
        ...extractRealtimeDeviceIds(deviceSummaryResponse),
        ...summaries.map((row) => String(row.deviceId || "")).filter(Boolean),
        ...deviceStore.devices.map((device) => device.normalizedId).filter(Boolean)
      ])
    );
    if (deviceIds.length === 0) {
      if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
        return;
      }
      realtimeRows.value = [];
      return;
    }
    const results = await Promise.allSettled(
      deviceIds.map(async (deviceId) => normalizeRealtimeRows(await getDeviceRealtimeData(deviceId), deviceId))
    );
    const rows = results.flatMap((result, index) => {
      if (result.status === "fulfilled" && result.value.length) {
        return result.value;
      }
      return summaries.filter((row) => row.deviceId === deviceIds[index]);
    });
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    realtimeRows.value = rows;
  } catch (error) {
    if (!realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      return;
    }
    realtimeError.value = error instanceof Error ? error.message : "实时数据刷新失败";
    console.error(error);
  } finally {
    if (realtimeRequestOwner.isCurrent(requestTicket, currentMainRealtimeContext())) {
      loading.value = false;
    }
  }
}

async function loadSingleRealtime() {
  if (!realtimeSingleDeviceId.value || !realtimeSinglePointId.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  const requestContext = currentSingleRealtimeContext();
  const requestTicket = singleRealtimeRequestOwner.begin(requestContext);
  singleLoading.value = true;
  singleRealtimeError.value = "";
  try {
    const response = await getPointRealtimeData(requestContext.deviceId, requestContext.pointId || "");
    if (!singleRealtimeRequestOwner.isCurrent(requestTicket, currentSingleRealtimeContext())) {
      return;
    }
    realtimeSingleResult.value = normalizeSinglePointRealtimeRow(response) || response;
  } catch (error) {
    if (!singleRealtimeRequestOwner.isCurrent(requestTicket, currentSingleRealtimeContext())) {
      return;
    }
    singleRealtimeError.value = error instanceof Error ? error.message : "单点实时查询失败";
    console.error(error);
  } finally {
    if (singleRealtimeRequestOwner.isCurrent(requestTicket, currentSingleRealtimeContext())) {
      singleLoading.value = false;
    }
  }
}

function pickRealtimePoint(row: RealtimePointRow) {
  realtimeSingleDeviceId.value = String(row.deviceId || realtimeDeviceId.value || "");
  realtimeSinglePointId.value = String(row.pointId || row.pointCode || row.address || "");
  if (realtimeSingleDeviceId.value && realtimeSinglePointId.value) {
    void loadSingleRealtime();
  }
}

function refreshRealtime() {
  void loadRealtime("manual");
}

function handleRealtimeDeviceChange() {
  void loadRealtime("device-change");
}

function syncTimer() {
  if (realtimeTimer) {
    clearInterval(realtimeTimer);
    realtimeTimer = null;
  }
  if (realtimeAuto.value) {
    realtimeTimer = window.setInterval(() => {
      void loadRealtime("timer");
    }, 5000);
  }
}

function applyRouteQuery() {
  const deviceId = normalizeRouteQuery(route.query.deviceId);
  const pointId = normalizeRouteQuery(route.query.pointId);
  if (!deviceId || !pointId) {
    return;
  }
  realtimeSingleDeviceId.value = deviceId;
  realtimeSinglePointId.value = pointId;
  void loadSingleRealtime();
}

function deviceDisplayName(deviceId: string): string {
  return deviceStore.devices.find((device) => device.normalizedId === deviceId)?.displayName || deviceId || "-";
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  if (!value) {
    return "-";
  }
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function normalizeRouteQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] ?? "") : "";
  }
  return value === undefined || value === null ? "" : String(value);
}

onMounted(() => {
  void initializeRealtimeView();
});

async function initializeRealtimeView() {
  await appStore.initialize();
  applyRouteQuery();
  await deviceStore.refresh();
  await loadRealtime("init");
  syncTimer();
}

onBeforeUnmount(() => {
  realtimeRequestOwner.invalidate();
  singleRealtimeRequestOwner.invalidate();
  loading.value = false;
  singleLoading.value = false;
  if (realtimeTimer) {
    clearInterval(realtimeTimer);
    realtimeTimer = null;
  }
});

function currentMainRealtimeContext(): RealtimeRequestContext {
  const deviceId = realtimeDeviceId.value.trim();
  return {
    mode: deviceId ? "device" : "all",
    deviceId
  };
}

function currentSingleRealtimeContext(): RealtimeRequestContext {
  return {
    mode: "single",
    deviceId: realtimeSingleDeviceId.value.trim(),
    pointId: realtimeSinglePointId.value.trim()
  };
}

watch(() => realtimeAuto.value, syncTimer);
watch(() => [route.query.deviceId, route.query.pointId], () => {
  applyRouteQuery();
});
</script>
