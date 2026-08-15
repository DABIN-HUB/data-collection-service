<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div></div>
      <el-button :loading="deviceLoading" @click="loadDevices">刷新设备</el-button>
    </section>
    <div class="modao-two-column network-layout">
      <section class="modao-surface">
        <div class="modao-surface-head"><div><h3>检测参数</h3><p>目标范围受后端白名单约束，设备目标会自动带入 host/port。</p></div></div>
        <div class="modao-form-grid">
          <label>检测方式<el-select v-model="form.type"><el-option label="TCP 端口" value="TCP" /><el-option label="网络可达性" value="PING" /><el-option label="路由跟踪" value="TRACE" /></el-select></label>
          <label>目标设备<el-select v-model="form.deviceId" filterable clearable @change="syncDeviceTarget"><el-option label="手动输入" value="" /><el-option v-for="device in devices" :key="device.id" :label="`${device.name}${device.host ? ' · ' + device.host : ''}`" :value="device.id" /></el-select></label>
          <label>目标主机<el-input v-model="form.target" placeholder="例如 127.0.0.1" /></label>
          <label>目标端口<el-input-number v-model="form.port" :min="1" :max="65535" @change="markTcpMode" /></label>
          <label>超时时间 ms<el-input-number v-model="form.timeoutMs" :min="100" :max="10000" /></label>
          <label>重试次数<el-input-number v-model="form.retries" :min="0" :max="5" /></label>
        </div>
        <el-button type="primary" class="wide" :loading="testing" @click="runTest">开始检测</el-button>
      </section>
      <section class="modao-surface">
        <div class="modao-surface-head"><div><h3>检测结果</h3><p>显示成功/失败、耗时、目标和后端原始结果。</p></div></div>
        <el-alert v-if="error" :title="error" type="warning" :closable="false" />
        <pre class="modao-network-output">{{ output }}</pre>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";

import { getConfigDevices } from "@/api/config.api";
import { diagnoseNetwork } from "@/api/ops.api";
import { normalizeDeviceOptions, type DeviceOption } from "@/views/runtime/runtime-utils";
import { formatNetworkResult } from "@/views/ops/ops-utils";

const deviceLoading = ref(false);
const testing = ref(false);
const error = ref("");
const output = ref("尚未执行网络检测");
const devices = ref<DeviceOption[]>([]);
const form = reactive({ type: "TCP", deviceId: "", target: "127.0.0.1", port: 9090 as number | undefined, timeoutMs: 3000, retries: 1 });

async function loadDevices() {
  deviceLoading.value = true;
  error.value = "";
  try {
    devices.value = normalizeDeviceOptions(await getConfigDevices());
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "设备列表加载失败";
  } finally {
    deviceLoading.value = false;
  }
}

function syncDeviceTarget() {
  const device = devices.value.find((item) => item.id === form.deviceId);
  if (device) {
    form.target = device.host || form.target;
    form.port = device.port || form.port;
    if (device.port) {
      form.type = "TCP";
    }
  }
}

function markTcpMode() {
  if (form.port) {
    form.type = "TCP";
  }
}

async function runTest() {
  if (!form.target.trim()) {
    error.value = "请输入检测目标";
    return;
  }
  if (form.type === "TCP" && !form.port) {
    error.value = "请输入目标端口";
    return;
  }
  testing.value = true;
  error.value = "";
  try {
    const result = await diagnoseNetwork({ ...form, port: form.type === "TCP" ? form.port : undefined });
    output.value = formatNetworkResult(result);
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "网络检测失败";
  } finally {
    testing.value = false;
  }
}

onMounted(loadDevices);
</script>
