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
      <details class="exact-json-panel" open>
        <summary>请求内容预览</summary>
        <pre class="json-view compact-result-view">{{ payloadPreview }}</pre>
      </details>
      <details class="exact-json-panel" open>
        <summary>接入响应</summary>
        <div v-if="attributedResult" class="edge-result-meta" :class="{ 'is-error': Boolean(attributedResult.error) }">
          <span>网关：<strong>{{ attributedResult.target.gatewayId }}</strong></span>
          <span>设备：<strong>{{ attributedResult.target.deviceId }}</strong></span>
          <span>点位：<strong>{{ attributedResult.target.pointRef }}</strong></span>
          <span>协议：<strong>{{ attributedResult.target.protocol }}</strong></span>
          <span>提交时间：<strong>{{ formatTime(attributedResult.target.submittedAt) }}</strong></span>
          <span>完成时间：<strong>{{ formatTime(attributedResult.completedAt) }}</strong></span>
          <span v-if="attributedResult.error">错误：<strong>{{ attributedResult.error }}</strong></span>
        </div>
        <pre class="json-view compact-result-view">{{ prettyJson(result) }}</pre>
      </details>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { ingestEdgeTelemetry } from "@/api/edge.api";
import type { DeviceInfo } from "@/types/device";
import { EDGE_PROTOCOL_OPTIONS, buildEdgeTelemetryAttributedResult, buildEdgeTelemetryPayload, buildEdgeTelemetrySubmissionSnapshot, normalizeEdgeTelemetryResult, parseEdgeTelemetryJson, type EdgeTelemetryAttributedResult, type EdgeTelemetryQuickForm } from "@/features/network/utils/edge-telemetry-utils";
import type { EdgeTelemetryBatchRequest } from "@/types/edge";

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
const attributedResult = ref<EdgeTelemetryAttributedResult | null>(null);
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
  const normalized = attributedResult.value?.result || normalizeEdgeTelemetryResult(result.value);
  if (attributedResult.value?.error) {
    return `提交失败：${attributedResult.value.error}`;
  }
  if (!normalized.gatewayId && !normalized.message) {
    return "等待提交";
  }
  return `${normalized.message || '处理完成'}：接收 ${normalized.acceptedCount}，重复 ${normalized.duplicateCount}，拒绝 ${normalized.rejectedCount}`;
});

async function submitTelemetry() {
  let submittedPayload: EdgeTelemetryBatchRequest;
  try {
    submittedPayload = payload.value;
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "边缘遥测 Payload 生成失败";
    result.value = { message };
    ElMessage.error(message);
    return;
  }
  const target = buildEdgeTelemetrySubmissionSnapshot(submittedPayload);
  submitting.value = true;
  try {
    const response = await ingestEdgeTelemetry(submittedPayload);
    const normalized = normalizeEdgeTelemetryResult(response);
    attributedResult.value = buildEdgeTelemetryAttributedResult(target, normalized);
    result.value = { target: attributedResult.value.target, result: normalized };
    if (normalized.rejectedCount > 0 || normalized.errors.length > 0) {
      ElMessage.warning(`网关 ${target.gatewayId} / 设备 ${target.deviceId} 边缘遥测部分拒绝`);
    } else {
      ElMessage.success(`网关 ${target.gatewayId} / 设备 ${target.deviceId} 边缘遥测提交成功`);
    }
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "边缘遥测提交失败";
    attributedResult.value = buildEdgeTelemetryAttributedResult(target, undefined, message);
    result.value = { target: attributedResult.value.target, error: message };
    ElMessage.error(`网关 ${target.gatewayId} / 设备 ${target.deviceId} 边缘遥测提交失败`);
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

function formatTime(value: number): string {
  return new Date(value).toLocaleString();
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

<style scoped>
.edge-mode-row,
.edge-action-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 16px 0;
}

.edge-mode-row button,
.edge-action-row button {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--exact-border);
  border-radius: 6px;
  background: var(--exact-panel-soft);
}

.edge-mode-row button.is-active,
.edge-action-row button.primary {
  color: #fff;
  border-color: var(--exact-blue);
  background: #2563eb;
}

.edge-action-row span {
  color: var(--exact-dim);
  font-size: 12px;
}

.edge-result-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  margin-bottom: 8px;
  padding: 10px;
  color: var(--exact-dim);
  border: 1px solid rgba(59, 130, 246, 0.35);
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.08);
  font-size: 12px;
}

.edge-result-meta.is-error {
  border-color: rgba(248, 113, 113, 0.45);
  background: rgba(127, 29, 29, 0.15);
}

.edge-result-meta strong {
  color: var(--exact-text);
}

.edge-form-grid textarea {
  min-height: 190px;
  resize: vertical;
}

.edge-json-grid {
  padding-top: 12px;
}
</style>
