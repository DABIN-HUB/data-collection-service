<template>
  <div v-if="tab === 'control'" class="console-module console-module-active">
    <div class="console-panel-head"><h2>手动控制</h2><span>{{ deviceId || '未选择设备' }}</span></div>
    <div class="surface-grid two">
      <section class="surface-card">
        <div class="surface-card-head"><h3>单点写入</h3><span>按点位编码或 pointId 写入</span></div>
        <div class="form-grid">
          <label>点位引用<input v-model="singlePointRef" type="text" placeholder="point_001" /></label>
          <label>数据类型<select v-model="singleDataType"><option>STRING</option><option>BOOLEAN</option><option>INT</option><option>FLOAT</option><option>DOUBLE</option></select></label>
          <label class="wide-field">写入值<input v-model="singleValue" type="text" placeholder="写入值" /></label>
        </div>
        <button type="button" class="primary wide" :disabled="!deviceId" @click="writeSingle">写入单点</button>
      </section>

      <section class="surface-card">
        <div class="surface-card-head"><h3>批量写点位</h3><button type="button" @click="fillBatchTemplate">模板</button></div>
        <textarea v-model="batchPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId" @click="writeBatch">批量写入点位</button>
      </section>

      <section class="surface-card wide-field">
        <div class="surface-card-head"><h3>执行协议命令</h3><button type="button" @click="fillCommandTemplate">套用模板</button></div>
        <textarea v-model="commandPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId" @click="executeCommand">执行命令</button>
        <pre class="json-view">{{ resultText }}</pre>
      </section>
    </div>
  </div>

  <div v-else class="console-module console-module-active">
    <div class="console-panel-head"><h2>设备影子</h2><span>{{ deviceId || '未选择设备' }}</span></div>
    <div class="surface-grid two">
      <section class="surface-card">
        <div class="surface-card-head"><h3>当前影子</h3><div class="inline-actions"><button type="button" :disabled="!deviceId" @click="loadShadowBundle">读取全部</button><button type="button" :disabled="!deviceId" @click="loadShadow">读取影子</button></div></div>
        <pre class="json-view">{{ shadowText }}</pre>
      </section>
      <section class="surface-card">
        <div class="surface-card-head"><h3>期望状态更新（desired）</h3><button type="button" class="danger" :disabled="!deviceId" @click="clearDesired">清理期望状态</button></div>
        <textarea v-model="desiredPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId" @click="saveDesired">提交期望状态</button>
      </section>
      <section class="surface-card">
        <div class="surface-card-head"><h3>影子差异（delta）</h3><button type="button" :disabled="!deviceId" @click="loadShadowDelta">读取 delta</button></div>
        <pre class="json-view">{{ shadowDeltaText }}</pre>
      </section>
      <section class="surface-card">
        <div class="surface-card-head"><h3>影子历史</h3><div class="inline-actions"><input v-model.number="shadowHistoryLimit" type="number" min="1" max="200" title="历史条数" /><button type="button" :disabled="!deviceId" @click="loadShadowHistory">读取历史</button></div></div>
        <div class="table-wrap shadow-history-wrap">
          <table class="runtime-table">
            <thead><tr><th>版本</th><th>操作</th><th>时间</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-if="shadowHistoryRows.length === 0"><td colspan="4">暂无影子历史</td></tr>
              <tr v-for="(row, index) in shadowHistoryRows" :key="String(row.version || row.timestamp || index)">
                <td>{{ row.version || '-' }}</td><td>{{ row.operation || row.type || '-' }}</td><td>{{ formatShadowTime(row) }}</td><td><code>{{ compactJson(row) }}</code></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { executeDeviceCommand, writeDevicePoint, writeDevicePoints } from "@/api/control.api";
import { clearShadowDesired, getShadow, getShadowDelta, getShadowHistory, updateShadowDesired } from "@/api/shadow.api";
import { normalizeShadowHistoryRows, type ShadowHistoryRow } from "./shadow-utils";

const props = defineProps<{ tab: string; deviceId: string }>();

const singlePointRef = ref("");
const singleDataType = ref("STRING");
const singleValue = ref("");
const batchPayload = ref(JSON.stringify({ points: [{ pointId: "point_001", value: 1 }] }, null, 2));
const commandPayload = ref(JSON.stringify({ command: "custom", params: {} }, null, 2));
const result = ref<unknown>({ message: "等待执行结果" });
const shadow = ref<unknown>({ message: "选择设备后读取影子" });
const shadowDelta = ref<unknown>({ message: "选择设备后读取 delta" });
const shadowHistoryRows = ref<ShadowHistoryRow[]>([]);
const shadowHistoryLimit = ref(50);
const desiredPayload = ref(JSON.stringify({ desired: {} }, null, 2));

const resultText = computed(() => JSON.stringify(result.value, null, 2));
const shadowText = computed(() => JSON.stringify(shadow.value, null, 2));
const shadowDeltaText = computed(() => JSON.stringify(shadowDelta.value, null, 2));

watch(() => props.deviceId, () => {
  result.value = { message: "等待执行结果" };
  shadow.value = props.deviceId ? { message: "点击读取影子" } : { message: "选择设备后读取影子" };
  shadowDelta.value = props.deviceId ? { message: "点击读取 delta" } : { message: "选择设备后读取 delta" };
  shadowHistoryRows.value = [];
});

async function writeSingle() {
  if (!props.deviceId || !singlePointRef.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  result.value = await writeDevicePoint(props.deviceId, singlePointRef.value.trim(), { value: parseValue(singleValue.value, singleDataType.value), dataType: singleDataType.value });
  ElMessage.success("单点写入请求已发送");
}

async function writeBatch() {
  if (!props.deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  result.value = await writeDevicePoints(props.deviceId, JSON.parse(batchPayload.value || "{}"));
  ElMessage.success("批量写入请求已发送");
}

async function executeCommand() {
  if (!props.deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  result.value = await executeDeviceCommand(props.deviceId, JSON.parse(commandPayload.value || "{}"));
  ElMessage.success("命令执行请求已发送");
}

async function loadShadow() {
  if (!props.deviceId) {
    return;
  }
  shadow.value = await getShadow(props.deviceId);
}

async function loadShadowDelta() {
  if (!props.deviceId) {
    return;
  }
  shadowDelta.value = await getShadowDelta(props.deviceId);
}

async function loadShadowHistory() {
  if (!props.deviceId) {
    return;
  }
  shadowHistoryRows.value = normalizeShadowHistoryRows(await getShadowHistory(props.deviceId, shadowHistoryLimit.value));
}

async function loadShadowBundle() {
  await Promise.allSettled([loadShadow(), loadShadowDelta(), loadShadowHistory()]);
}

async function saveDesired() {
  if (!props.deviceId) {
    return;
  }
  shadow.value = await updateShadowDesired(props.deviceId, JSON.parse(desiredPayload.value || "{}"));
  ElMessage.success("期望状态已提交");
}

async function clearDesired() {
  if (!props.deviceId) {
    return;
  }
  shadow.value = await clearShadowDesired(props.deviceId);
  desiredPayload.value = JSON.stringify({ desired: {} }, null, 2);
  ElMessage.success("期望状态已清理");
}

function fillBatchTemplate() {
  batchPayload.value = JSON.stringify({ points: [{ pointId: "point_001", value: 1, dataType: "INT" }] }, null, 2);
}

function fillCommandTemplate() {
  commandPayload.value = JSON.stringify({ command: "custom", params: {} }, null, 2);
}

function parseValue(value: string, dataType: string): unknown {
  if (["INT", "FLOAT", "DOUBLE"].includes(dataType)) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : value;
  }
  if (dataType === "BOOLEAN") {
    return value === "true" || value === "1" || value === "是";
  }
  return value;
}

function formatShadowTime(row: ShadowHistoryRow): string {
  const raw = row.timestamp || row.time || row.createdAt || row.updateTime;
  if (!raw) {
    return "-";
  }
  if (typeof raw !== "string" && typeof raw !== "number" && !(raw instanceof Date)) {
    return String(raw);
  }
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? String(raw) : date.toLocaleString();
}

function compactJson(value: unknown): string {
  return JSON.stringify(value);
}
</script>
