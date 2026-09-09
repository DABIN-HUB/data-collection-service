<template>
  <div class="local-editor-pane manual-shadow-pane">
    <div class="console-panel-head local-section-card manual-shadow-head-card">
      <h2>手动控制</h2>
      <span>{{ deviceId || "未选择设备" }}</span>
    </div>
    <div class="surface-grid two">
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>单点写入</h3>
          <span>按点位编码或 pointId 写入</span>
        </div>
        <div class="form-grid">
          <label>点位引用<input v-model="singlePointRef" type="text" placeholder="point_001" /></label>
          <label>数据类型
            <select v-model="singleDataType">
              <option>STRING</option>
              <option>BOOLEAN</option>
              <option>INT</option>
              <option>FLOAT</option>
              <option>DOUBLE</option>
            </select>
          </label>
          <label class="wide-field">写入值<input v-model="singleValue" type="text" placeholder="写入值" /></label>
        </div>
        <button type="button" class="primary wide" :disabled="!deviceId || singleWriting" @click="writeSingle">{{ singleWritingText }}</button>
      </section>

      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>批量写点位</h3>
          <button type="button" @click="fillBatchTemplate">模板</button>
        </div>
        <textarea v-model="batchPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || batchWriting" @click="writeBatch">{{ batchWritingText }}</button>
      </section>

      <section class="surface-card local-section-card wide-field">
        <div class="surface-card-head">
          <h3>执行协议命令</h3>
          <button type="button" @click="fillCommandTemplate">套用模板</button>
        </div>
        <textarea v-model="commandPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || commandExecuting" @click="executeCommand">{{ commandWritingText }}</button>
        <div v-if="actionResult" class="control-result-meta" :class="{ 'is-error': Boolean(actionResult.error) }">
          <span>目标设备：<strong>{{ actionResult.target.deviceId || actionResult.target.target }}</strong></span>
          <span>动作：<strong>{{ actionResult.target.action }}</strong></span>
          <span v-if="actionResult.target.pointRef">点位：<strong>{{ actionResult.target.pointRef }}</strong></span>
          <span v-if="actionResult.target.payloadSummary">提交内容：<strong>{{ actionResult.target.payloadSummary }}</strong></span>
          <span>提交时间：<strong>{{ formatActionTime(actionResult.target.submittedAt) }}</strong></span>
          <span>完成时间：<strong>{{ formatActionTime(actionResult.completedAt) }}</strong></span>
          <span v-if="actionResult.error">错误：<strong>{{ actionResult.error }}</strong></span>
        </div>
        <pre class="json-view">{{ resultText }}</pre>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { executeDeviceCommand, writeDevicePoint, writeDevicePoints } from "@/api/control.api";
import {
  buildActionExecutionView,
  formatActionTime,
  safeActionErrorMessage,
  type ActionExecutionTarget,
  type ActionExecutionView
} from "@/features/action/utils/action-result-context";
import {
  buildBatchControlTemplate,
  buildControlActionTarget,
  buildCommandTemplate,
  buildSinglePointControlPayload,
  formatControlJson,
  parseControlJson
} from "@/features/control/utils/control-utils";
import type { ControlResultResponse, DeviceCommandRequest, PointWriteRequest } from "@/types/control";

interface ControlPanelMessageState {
  message?: string;
  error?: string;
}

const props = defineProps<{ deviceId: string }>();

const singlePointRef = ref("");
const singleDataType = ref("STRING");
const singleValue = ref("");
const batchPayload = ref(formatControlJson(buildBatchControlTemplate()));
const commandPayload = ref(formatControlJson(buildCommandTemplate()));
const actionResult = ref<ActionExecutionView<ControlResultResponse> | null>(null);
const result = ref<ControlResultResponse | ControlPanelMessageState>({ message: "等待执行结果" });
const singleWriting = ref(false);
const batchWriting = ref(false);
const commandExecuting = ref(false);
const singleWritingTarget = ref<ActionExecutionTarget | null>(null);
const batchWritingTarget = ref<ActionExecutionTarget | null>(null);
const commandWritingTarget = ref<ActionExecutionTarget | null>(null);

const resultText = computed(() => JSON.stringify(result.value, null, 2));
const singleWritingText = computed(() => singleWritingTarget.value ? `正在写入设备 ${singleWritingTarget.value.deviceId || singleWritingTarget.value.target}` : "写入单点");
const batchWritingText = computed(() => batchWritingTarget.value ? `正在批量写入设备 ${batchWritingTarget.value.deviceId || batchWritingTarget.value.target}` : "批量写入点位");
const commandWritingText = computed(() => commandWritingTarget.value ? `正在执行设备 ${commandWritingTarget.value.deviceId || commandWritingTarget.value.target} 命令` : "执行命令");

async function writeSingle() {
  if (!props.deviceId || !singlePointRef.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  const targetDeviceId = props.deviceId;
  const targetPointRef = singlePointRef.value.trim();
  const targetDataType = singleDataType.value;
  const targetRawValue = singleValue.value;
  const payload = buildSinglePointControlPayload(targetRawValue, targetDataType);
  const target = buildControlActionTarget({
    deviceId: targetDeviceId,
    action: "single-write",
    pointRef: targetPointRef,
    payload: { dataType: targetDataType, value: payload.value }
  });
  singleWriting.value = true;
  singleWritingTarget.value = target;
  try {
    const response = await writeDevicePoint(targetDeviceId, targetPointRef, payload);
    actionResult.value = buildActionExecutionView(target, response);
    result.value = response;
    ElMessage.success(`设备 ${targetDeviceId} 单点写入请求已完成`);
  } catch (error) {
    handleControlError(error, "单点写入失败", target);
  } finally {
    singleWriting.value = false;
    singleWritingTarget.value = null;
  }
}

async function writeBatch() {
  if (!props.deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  let payload: PointWriteRequest;
  try {
    payload = parseControlJson<PointWriteRequest>(batchPayload.value, "批量写入 JSON");
  } catch (error) {
    handleControlError(error, "批量写入 JSON 格式错误");
    return;
  }
  const targetDeviceId = props.deviceId;
  const target = buildControlActionTarget({
    deviceId: targetDeviceId,
    action: "batch-write",
    payload
  });
  batchWriting.value = true;
  batchWritingTarget.value = target;
  try {
    const response = await writeDevicePoints(targetDeviceId, payload);
    actionResult.value = buildActionExecutionView(target, response);
    result.value = response;
    ElMessage.success(`设备 ${targetDeviceId} 批量写入请求已完成`);
  } catch (error) {
    handleControlError(error, "批量写入失败", target);
  } finally {
    batchWriting.value = false;
    batchWritingTarget.value = null;
  }
}

async function executeCommand() {
  if (!props.deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  let payload: DeviceCommandRequest;
  try {
    payload = parseControlJson<DeviceCommandRequest>(commandPayload.value, "协议命令 JSON");
  } catch (error) {
    handleControlError(error, "协议命令 JSON 格式错误");
    return;
  }
  const targetDeviceId = props.deviceId;
  const target = buildControlActionTarget({
    deviceId: targetDeviceId,
    action: "command",
    payload
  });
  commandExecuting.value = true;
  commandWritingTarget.value = target;
  try {
    const response = await executeDeviceCommand(targetDeviceId, payload);
    actionResult.value = buildActionExecutionView(target, response);
    result.value = response;
    ElMessage.success(`设备 ${targetDeviceId} 协议命令执行完成`);
  } catch (error) {
    handleControlError(error, "命令执行失败", target);
  } finally {
    commandExecuting.value = false;
    commandWritingTarget.value = null;
  }
}

function fillBatchTemplate() {
  batchPayload.value = formatControlJson(buildBatchControlTemplate());
}

function fillCommandTemplate() {
  commandPayload.value = formatControlJson(buildCommandTemplate());
}

function handleControlError(error: unknown, fallback: string, target?: ActionExecutionTarget) {
  const message = safeActionErrorMessage(error, fallback);
  if (target) {
    actionResult.value = buildActionExecutionView(target, undefined, message);
  }
  result.value = { error: message };
  ElMessage.error(target ? `设备 ${target.deviceId || target.target} ${message || fallback}` : message || fallback);
}

watch(() => props.deviceId, () => {
  singlePointRef.value = "";
  singleValue.value = "";
  singleDataType.value = "STRING";
  batchPayload.value = formatControlJson(buildBatchControlTemplate());
  commandPayload.value = formatControlJson(buildCommandTemplate());
});
</script>

<style scoped>
.manual-shadow-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
}

.manual-shadow-head-card {
  display: flex;
  min-height: 48px;
  padding: 10px 12px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.manual-shadow-head-card h2 {
  margin: 0;
  color: var(--console-text-primary);
  font-size: 15px;
}

.manual-shadow-head-card span {
  color: var(--console-text-muted);
  font-size: 12px;
}

.surface-grid.two {
  display: grid;
  min-height: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.surface-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.surface-card-head {
  display: flex;
  min-height: 32px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.surface-card-head h3,
.surface-card-head span {
  margin: 0;
}

.surface-card-head h3 {
  color: var(--console-text-primary);
  font-size: 14px;
}

.surface-card-head span {
  color: var(--console-text-muted);
  font-size: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.form-grid label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  color: var(--console-text-muted);
  font-size: 12px;
}

.control-result-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  padding: 10px;
  color: var(--console-text-muted);
  border: 1px solid rgba(59, 130, 246, 0.35);
  border-radius: var(--console-radius-lg);
  background: rgba(37, 99, 235, 0.08);
  font-size: 12px;
}

.control-result-meta.is-error {
  border-color: rgba(248, 113, 113, 0.45);
  background: rgba(127, 29, 29, 0.15);
}

.control-result-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.control-result-meta strong {
  color: var(--console-text-primary);
}

.wide-field {
  grid-column: 1 / -1;
}

textarea {
  width: 100%;
  min-height: 170px;
  padding: 10px;
  color: #dbeafe;
  border: 1px solid #1e3a5f;
  border-radius: var(--console-radius-lg);
  background: #0f172a;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  resize: vertical;
}

.json-view {
  min-height: 160px;
}

@media (max-width: 1280px) {
  .surface-grid.two,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
