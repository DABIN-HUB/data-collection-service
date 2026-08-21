<template>
  <section class="exact-surface edge-telemetry-panel">
    <div class="exact-surface-head">
      <h2>边缘遥测调试</h2>
      <span>边缘网关数据接入</span>
    </div>
    <div class="edge-mode-row">
      <button type="button" :class="{ 'is-active': !useRawJson }" @click="useRawJson = false">快捷表单</button>
      <button type="button" :class="{ 'is-active': useRawJson }" @click="useRawJson = true">原始 JSON</button>
    </div>
    <div v-if="!useRawJson" class="form-grid edge-form-grid">
      <label>网关标识
        <input v-model="form.gatewayId" type="text" placeholder="例如 gateway-1" />
      </label>
      <label>协议类型
        <select v-model="form.protocol">
          <option v-for="option in EDGE_PROTOCOL_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
      </label>
      <label>配置版本
        <input v-model="form.configVersion" type="text" placeholder="例如 v1" />
      </label>
      <label>目标设备
        <select v-model="form.deviceId" @change="emit('select-device', form.deviceId)">
          <option value="">手动输入或选择设备</option>
          <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option>
        </select>
      </label>
      <label>设备 ID
        <input v-model="form.deviceId" type="text" placeholder="本地 deviceId" />
      </label>
      <label>点位引用
        <input v-model="form.pointRef" type="text" placeholder="pointId / pointCode / reportField" />
      </label>
      <label>值类型
        <select v-model="form.valueType">
          <option value="number">数字</option>
          <option value="string">字符串</option>
          <option value="boolean">布尔</option>
          <option value="json">JSON</option>
        </select>
      </label>
      <label>遥测值
        <input v-model="form.valueText" type="text" placeholder="例如 12.5" />
      </label>
      <label>质量分
        <input v-model.number="form.quality" type="number" min="0" max="100" step="1" />
      </label>
      <label>时间戳 ms
        <input v-model.number="form.timestamp" type="number" min="0" step="1" />
      </label>
      <label>序号
        <input v-model.number="form.sequence" type="number" min="1" step="1" />
      </label>
    </div>
    <div v-else class="form-grid edge-form-grid raw-mode">
      <label class="wide-field">批量遥测 JSON
        <textarea v-model="rawJson" spellcheck="false"></textarea>
      </label>
    </div>
    <div class="edge-action-row">
      <button type="button" class="primary" :disabled="submitting" @click="submitTelemetry">{{ submitting ? '提交中' : '提交边缘遥测' }}</button>
      <button type="button" @click="resetTimestamp">刷新时间戳/序号</button>
      <span>{{ resultText }}</span>
    </div>
    <div class="surface-grid two edge-json-grid">
      <section class="exact-json-panel" open>
        <summary>请求内容预览</summary>
        <pre class="json-view compact-result-view">{{ payloadPreview }}</pre>
      </section>
      <section class="exact-json-panel" open>
        <summary>接入响应</summary>
        <pre class="json-view compact-result-view">{{ prettyJson(result) }}</pre>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { ingestEdgeTelemetry } from "@/api/edge.api";
import type { DeviceInfo } from "@/types/device";
import { EDGE_PROTOCOL_OPTIONS, buildEdgeTelemetryPayload, normalizeEdgeTelemetryResult, parseEdgeTelemetryJson, type EdgeTelemetryQuickForm } from "./edge-telemetry-utils";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId?: string;
}>();
const emit = defineEmits<{
  (event: "select-device", deviceId: string): void;
}>();

const useRawJson = ref(false);
const submitting = ref(false);
const result = ref<unknown>({ message: "尚未提交边缘遥测" });
const now = Date.now();
const form = reactive<EdgeTelemetryQuickForm>({
  gatewayId: "desktop-edge-debug",
  protocol: "GENERIC_EDGE",
  configVersion: "debug-v1",
  deviceId: props.selectedDeviceId || "",
  pointRef: "",
  valueText: "0",
  valueType: "number",
  quality: 100,
  timestamp: now,
  sequence: now
});
const rawJson = ref(prettyJson(buildEdgeTelemetryPayload(form)));

const payload = computed(() => useRawJson.value ? parseEdgeTelemetryJson(rawJson.value) : buildEdgeTelemetryPayload(form));
const payloadPreview = computed(() => {
  try {
    return prettyJson(payload.value);
  } catch (caught) {
    return caught instanceof Error ? caught.message : "边缘遥测 Payload 生成失败";
  }
});
const resultText = computed(() => {
  const normalized = normalizeEdgeTelemetryResult(result.value);
  if (!normalized.gatewayId && !normalized.message) {
    return "等待提交";
  }
  return `${normalized.message || '处理完成'}：接收 ${normalized.acceptedCount}，重复 ${normalized.duplicateCount}，拒绝 ${normalized.rejectedCount}`;
});

async function submitTelemetry() {
  submitting.value = true;
  try {
    const response = await ingestEdgeTelemetry(payload.value);
    result.value = response;
    const normalized = normalizeEdgeTelemetryResult(response);
    if (normalized.rejectedCount > 0 || normalized.errors.length > 0) {
      ElMessage.warning(normalized.message || "边缘遥测部分拒绝");
    } else {
      ElMessage.success(normalized.message || "边缘遥测提交成功");
    }
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "边缘遥测提交失败";
    result.value = { message };
    ElMessage.error(message);
  } finally {
    submitting.value = false;
  }
}

function resetTimestamp() {
  const next = Date.now();
  form.timestamp = next;
  form.sequence = next;
  if (!useRawJson.value) {
    rawJson.value = prettyJson(buildEdgeTelemetryPayload(form));
  }
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function deviceIdOf(device: DeviceInfo): string {
  return String(device.deviceId || device.id || "");
}

watch(() => props.selectedDeviceId, (deviceId) => {
  if (deviceId && !form.deviceId) {
    form.deviceId = deviceId;
  }
});
watch(() => [form.gatewayId, form.protocol, form.configVersion, form.deviceId, form.pointRef, form.valueText, form.valueType, form.quality, form.timestamp, form.sequence], () => {
  if (!useRawJson.value) {
    try {
      rawJson.value = prettyJson(buildEdgeTelemetryPayload(form));
    } catch {
      // 输入未完整时只更新预览错误，不覆盖原始 JSON。
    }
  }
});
</script>
