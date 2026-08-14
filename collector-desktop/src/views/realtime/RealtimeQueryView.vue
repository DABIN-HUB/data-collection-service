<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">实时数据查询</span>
        <h2>全局实时数据</h2>
        <p>接口：GET /api/config/devices、GET /api/data/device/{deviceId}、POST /api/data/device/{deviceId}/reset-adaptive</p>
      </div>
      <div class="table-actions">
        <el-select v-model="selectedDeviceId" placeholder="选择设备" filterable class="compact-select">
          <el-option v-for="device in devices" :key="device.id" :label="device.name" :value="device.id" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索点位" clearable class="compact-select" />
        <el-switch v-model="autoRefresh" active-text="自动刷新" inactive-text="手动" />
        <el-input-number v-model="refreshIntervalMs" :min="1000" :step="1000" controls-position="right" />
        <el-button :loading="loading" @click="loadDevices">刷新设备</el-button>
        <el-button :disabled="!selectedDeviceId" :loading="resetting" @click="resetAdaptive">重置自适应</el-button>
      </div>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <RealtimeDataPanel
      v-if="selectedDeviceId"
      ref="panelRef"
      :device-id="selectedDeviceId"
      :keyword="keyword"
      :auto-refresh="autoRefresh"
      :refresh-interval-ms="refreshIntervalMs"
    />
    <section v-else class="empty-config"><h3>请选择设备</h3><p>支持设备选择、点位搜索、自动刷新和重置自适应采集状态。</p></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";

import RealtimeDataPanel from "@/components/realtime/RealtimeDataPanel.vue";
import { getConfigDevices } from "@/api/config.api";
import { resetAdaptiveConfig } from "@/api/data.api";
import { normalizeDeviceOptions, type DeviceOption } from "@/views/runtime/runtime-utils";

const loading = ref(false);
const resetting = ref(false);
const error = ref("");
const selectedDeviceId = ref("");
const rawDevices = ref<unknown[]>([]);
const keyword = ref("");
const autoRefresh = ref(false);
const refreshIntervalMs = ref(5000);
const panelRef = ref<InstanceType<typeof RealtimeDataPanel> | null>(null);

const devices = computed<DeviceOption[]>(() => normalizeDeviceOptions(rawDevices.value));

async function loadDevices() {
  loading.value = true;
  error.value = "";
  try {
    const response = await getConfigDevices();
    const body = response as Record<string, unknown>;
    rawDevices.value = Array.isArray(body) ? body : Array.isArray(body.devices) ? body.devices : [];
    selectedDeviceId.value = selectedDeviceId.value || devices.value[0]?.id || "";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "设备列表加载失败";
  } finally {
    loading.value = false;
  }
}

async function resetAdaptive() {
  if (!selectedDeviceId.value) {
    error.value = "请选择设备";
    return;
  }
  resetting.value = true;
  error.value = "";
  try {
    await resetAdaptiveConfig(selectedDeviceId.value);
    await panelRef.value?.load();
    ElMessage.success("已重置自适应采集状态");
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "重置自适应采集失败";
  } finally {
    resetting.value = false;
  }
}

onMounted(loadDevices);
</script>
