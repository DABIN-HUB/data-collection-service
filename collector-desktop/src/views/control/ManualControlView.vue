<template>
  <div class="page-stack">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">手动控制</span>
        <h2>写点与协议命令</h2>
        <p>接口：POST /api/control/device/{deviceId}/point/{pointRef}、/points、/command</p>
      </div>
      <el-button :loading="deviceLoading" @click="loadDevices">刷新设备</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <div class="table-actions">
      <el-select v-model="deviceId" filterable placeholder="选择设备" class="wide-select">
        <el-option v-for="device in devices" :key="device.id" :label="`${device.name}${device.protocol ? ' · ' + device.protocol : ''}`" :value="device.id" />
      </el-select>
      <el-tag v-if="currentDevice?.protocol" effect="plain">协议：{{ currentDevice.protocol }}</el-tag>
    </div>

    <div class="surface-grid two">
      <section class="surface-card">
        <div class="surface-card-head"><h3>单点写入</h3><span class="surface-note">适合快速写一个点位</span></div>
        <div class="modao-form-grid">
          <label>点位引用<el-input v-model="singlePointRef" placeholder="pointCode / pointId" /></label>
          <label>数据类型<el-select v-model="singleDataType" filterable><el-option v-for="type in dataTypes" :key="type" :label="type" :value="type" /></el-select></label>
          <label class="wide-field">写入值<el-input v-model="singleValue" placeholder="例如 12.5 / true / 文本" /></label>
        </div>
        <el-button type="primary" class="wide" :loading="singleWriting" @click="writeSinglePoint">写入单点</el-button>
      </section>

      <section class="surface-card">
        <div class="surface-card-head">
          <h3>批量写点位</h3>
          <div class="table-actions"><el-button @click="pointWriteText = JSON.stringify(buildBatchWriteTemplate(), null, 2)">模板</el-button><span class="surface-note">JSON 载荷</span></div>
        </div>
        <textarea v-model="pointWriteText" spellcheck="false"></textarea>
        <el-button type="primary" class="wide" :loading="writing" @click="writePoints">批量写入点位</el-button>
      </section>
    </div>

    <section class="surface-card">
      <div class="surface-card-head">
        <h3>执行协议命令</h3>
        <div class="table-actions">
          <el-select v-model="commandTemplateName" class="mini-filter">
            <el-option label="状态" value="status" />
            <el-option label="读取状态" value="readStatus" />
            <el-option label="重连" value="reconnect" />
          </el-select>
          <el-button @click="commandText = JSON.stringify(buildCommandTemplate(commandTemplateName), null, 2)">套用模板</el-button>
        </div>
      </div>
      <textarea v-model="commandText" spellcheck="false"></textarea>
      <el-button type="primary" class="wide" :loading="commanding" @click="executeCommand">执行命令</el-button>
    </section>

    <section class="exact-surface"><div class="exact-surface-head"><h3>执行结果</h3><span>错误时保留后端响应</span></div><pre class="json-view">{{ resultText }}</pre></section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { getConfigDevices } from "@/api/config.api";
import { executeDeviceCommand, writeDevicePoint, writeDevicePoints } from "@/api/control.api";
import { buildBatchWriteTemplate, buildCommandTemplate, buildSinglePointWritePayload, normalizeDeviceOptions, parseJsonOrThrow, type DeviceOption } from "@/views/runtime/runtime-utils";

const deviceLoading = ref(false);
const singleWriting = ref(false);
const writing = ref(false);
const commanding = ref(false);
const error = ref("");
const deviceId = ref("");
const devices = ref<DeviceOption[]>([]);
const resultText = ref("等待执行结果");
const singlePointRef = ref("");
const singleValue = ref("");
const singleDataType = ref("FLOAT");
const commandTemplateName = ref("status");
const pointWriteText = ref(JSON.stringify(buildBatchWriteTemplate(), null, 2));
const commandText = ref(JSON.stringify(buildCommandTemplate("status"), null, 2));
const dataTypes = ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"];
const currentDevice = computed(() => devices.value.find((device) => device.id === deviceId.value));

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

async function writeSinglePoint() {
  if (!deviceId.value) { error.value = "请选择设备"; return; }
  if (!singlePointRef.value.trim()) { error.value = "请输入点位引用"; return; }
  singleWriting.value = true;
  error.value = "";
  try {
    const payload = buildSinglePointWritePayload(singlePointRef.value, singleValue.value, singleDataType.value);
    resultText.value = JSON.stringify(await writeDevicePoint(deviceId.value, singlePointRef.value, payload), null, 2);
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "单点写入失败";
  } finally {
    singleWriting.value = false;
  }
}

async function writePoints() {
  if (!deviceId.value) { error.value = "请选择设备"; return; }
  writing.value = true;
  error.value = "";
  try { resultText.value = JSON.stringify(await writeDevicePoints(deviceId.value, parseJsonOrThrow(pointWriteText.value, "批量写点 JSON")), null, 2); }
  catch (caught) { error.value = caught instanceof Error ? caught.message : "写点失败"; }
  finally { writing.value = false; }
}

async function executeCommand() {
  if (!deviceId.value) { error.value = "请选择设备"; return; }
  commanding.value = true;
  error.value = "";
  try { resultText.value = JSON.stringify(await executeDeviceCommand(deviceId.value, parseJsonOrThrow(commandText.value, "命令 JSON")), null, 2); }
  catch (caught) { error.value = caught instanceof Error ? caught.message : "命令执行失败"; }
  finally { commanding.value = false; }
}

onMounted(loadDevices);
</script>
