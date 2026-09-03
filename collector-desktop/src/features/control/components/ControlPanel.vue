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
        <button type="button" class="primary wide" :disabled="!deviceId || singleWriting" @click="writeSingle">写入单点</button>
      </section>

      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>批量写点位</h3>
          <button type="button" @click="fillBatchTemplate">模板</button>
        </div>
        <textarea v-model="batchPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || batchWriting" @click="writeBatch">批量写入点位</button>
      </section>

      <section class="surface-card local-section-card wide-field">
        <div class="surface-card-head">
          <h3>执行协议命令</h3>
          <button type="button" @click="fillCommandTemplate">套用模板</button>
        </div>
        <textarea v-model="commandPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || commandExecuting" @click="executeCommand">执行命令</button>
        <pre class="json-view">{{ resultText }}</pre>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";

import { executeDeviceCommand, writeDevicePoint, writeDevicePoints } from "@/api/control.api";
import {
  buildBatchControlTemplate,
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
const result = ref<ControlResultResponse | ControlPanelMessageState>({ message: "等待执行结果" });
const singleWriting = ref(false);
const batchWriting = ref(false);
const commandExecuting = ref(false);

const resultText = computed(() => JSON.stringify(result.value, null, 2));

async function writeSingle() {
  if (!props.deviceId || !singlePointRef.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  singleWriting.value = true;
  try {
    result.value = await writeDevicePoint(props.deviceId, singlePointRef.value.trim(), buildSinglePointControlPayload(singleValue.value, singleDataType.value));
    ElMessage.success("单点写入请求已发送");
  } catch (error) {
    handleControlError(error, "单点写入失败");
  } finally {
    singleWriting.value = false;
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
  batchWriting.value = true;
  try {
    result.value = await writeDevicePoints(props.deviceId, payload);
    ElMessage.success("批量写入请求已发送");
  } catch (error) {
    handleControlError(error, "批量写入失败");
  } finally {
    batchWriting.value = false;
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
  commandExecuting.value = true;
  try {
    result.value = await executeDeviceCommand(props.deviceId, payload);
    ElMessage.success("命令执行请求已发送");
  } catch (error) {
    handleControlError(error, "命令执行失败");
  } finally {
    commandExecuting.value = false;
  }
}

function fillBatchTemplate() {
  batchPayload.value = formatControlJson(buildBatchControlTemplate());
}

function fillCommandTemplate() {
  commandPayload.value = formatControlJson(buildCommandTemplate());
}

function handleControlError(error: unknown, fallback: string) {
  const message = error instanceof Error ? error.message : fallback;
  result.value = { error: message };
  ElMessage.error(message || fallback);
}
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
