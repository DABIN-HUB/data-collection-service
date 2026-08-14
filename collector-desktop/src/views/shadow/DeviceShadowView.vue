<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div><span class="page-kicker">设备影子</span><h2>Shadow desired / delta / history</h2><p>接口：GET /api/shadow/{deviceId}、POST/DELETE desired、GET delta/history</p></div>
      <el-button :loading="deviceLoading" @click="loadDevices">刷新设备</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <div class="table-actions">
      <el-select v-model="deviceId" filterable placeholder="选择设备" class="wide-select"><el-option v-for="device in devices" :key="device.id" :label="device.name" :value="device.id" /></el-select>
      <el-button :loading="loading" @click="loadCurrentTab">刷新当前页</el-button>
      <el-button @click="loadAll">读取全部</el-button>
    </div>

    <el-tabs v-model="activeTab" class="shadow-tabs" @tab-change="loadCurrentTab">
      <el-tab-pane label="当前影子" name="shadow"><pre class="json-view">{{ shadowText }}</pre></el-tab-pane>
      <el-tab-pane label="期望状态 desired" name="desired">
        <div class="json-panel-actions"><span>desired JSON</span><div class="table-actions"><el-button @click="formatDesired">格式化</el-button><el-button type="danger" plain @click="clearDesiredState">清理 desired</el-button><el-button type="primary" :loading="saving" @click="saveDesired">提交 desired</el-button></div></div>
        <textarea v-model="desiredText" class="point-json-textarea" spellcheck="false"></textarea>
      </el-tab-pane>
      <el-tab-pane label="差异 delta" name="delta"><pre class="json-view">{{ deltaText }}</pre></el-tab-pane>
      <el-tab-pane label="历史 history" name="history"><div class="table-actions history-toolbar"><span>历史条数</span><el-input-number v-model="historyLimit" :min="1" :max="500" /><el-button @click="loadHistory">刷新历史</el-button></div><pre class="json-view">{{ historyText }}</pre></el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";

import { getConfigDevices } from "@/api/config.api";
import { clearShadowDesired, getShadow, getShadowDelta, getShadowHistory, updateShadowDesired } from "@/api/shadow.api";
import { normalizeDeviceOptions, parseJsonOrThrow, type DeviceOption } from "@/views/runtime/runtime-utils";

const deviceLoading = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const deviceId = ref("");
const devices = ref<DeviceOption[]>([]);
const activeTab = ref("shadow");
const shadowText = ref("请选择设备读取影子");
const deltaText = ref("请选择设备读取差异");
const historyText = ref("请选择设备读取历史");
const desiredText = ref(JSON.stringify({ desired: {} }, null, 2));
const historyLimit = ref(50);

async function loadDevices() {
  deviceLoading.value = true;
  error.value = "";
  try {
    devices.value = normalizeDeviceOptions(await getConfigDevices());
    deviceId.value = deviceId.value || devices.value[0]?.id || "";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "设备列表加载失败";
  } finally {
    deviceLoading.value = false;
  }
}

function ensureDevice(): string {
  if (!deviceId.value) { throw new Error("请选择设备"); }
  return deviceId.value;
}

async function run(loader: () => Promise<unknown>, assign: (text: string) => void, fallback: string) {
  loading.value = true;
  error.value = "";
  try { assign(JSON.stringify(await loader(), null, 2)); }
  catch (caught) { error.value = caught instanceof Error ? caught.message : fallback; }
  finally { loading.value = false; }
}

function loadShadowData() { return run(() => getShadow(ensureDevice()), (text) => { shadowText.value = text; }, "设备影子加载失败"); }
function loadDelta() { return run(() => getShadowDelta(ensureDevice()), (text) => { deltaText.value = text; }, "设备影子差异加载失败"); }
function loadHistory() { return run(() => getShadowHistory(ensureDevice(), historyLimit.value), (text) => { historyText.value = text; }, "设备影子历史加载失败"); }

async function loadAll() {
  await Promise.allSettled([loadShadowData(), loadDelta(), loadHistory()]);
}

function loadCurrentTab() {
  if (activeTab.value === "shadow") { return loadShadowData(); }
  if (activeTab.value === "delta") { return loadDelta(); }
  if (activeTab.value === "history") { return loadHistory(); }
  return Promise.resolve();
}

function formatDesired() {
  try {
    desiredText.value = JSON.stringify(parseJsonOrThrow(desiredText.value, "desired JSON"), null, 2);
    error.value = "";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "desired JSON 格式错误";
  }
}

async function saveDesired() {
  saving.value = true;
  error.value = "";
  try {
    shadowText.value = JSON.stringify(await updateShadowDesired(ensureDevice(), parseJsonOrThrow(desiredText.value, "desired JSON")), null, 2);
    activeTab.value = "shadow";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "期望状态提交失败";
  } finally {
    saving.value = false;
  }
}

function clearDesiredState() {
  return run(() => clearShadowDesired(ensureDevice()), (text) => { shadowText.value = text; activeTab.value = "shadow"; }, "期望状态清理失败");
}

onMounted(async () => {
  await loadDevices();
  if (deviceId.value) {
    loadShadowData();
  }
});
</script>
