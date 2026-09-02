<template>
  <div class="local-editor-pane manual-shadow-pane">
    <div class="console-panel-head local-section-card manual-shadow-head-card">
      <h2>设备影子</h2>
      <span>{{ deviceId || "未选择设备" }}</span>
    </div>
    <div class="shadow-summary-grid">
      <div class="shadow-summary-card"><span>当前影子</span><strong>{{ shadowSummary.currentText }}</strong></div>
      <div class="shadow-summary-card"><span>期望状态</span><strong>{{ shadowSummary.desiredText }}</strong></div>
      <div class="shadow-summary-card"><span>delta</span><strong>{{ shadowSummary.deltaText }}</strong></div>
      <div class="shadow-summary-card"><span>历史记录</span><strong>{{ shadowSummary.historyCount }}</strong></div>
    </div>
    <div class="surface-grid two">
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>当前影子</h3>
          <div class="inline-actions">
            <button type="button" :disabled="!deviceId || loadingShadow" @click="loadShadowBundle">读取全部</button>
            <button type="button" :disabled="!deviceId || loadingShadow" @click="loadShadow">读取影子</button>
            <button type="button" :disabled="!deviceId" @click="downloadShadowPackage">导出快照</button>
          </div>
        </div>
        <pre class="json-view">{{ shadowText }}</pre>
      </section>
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>期望状态更新（desired）</h3>
          <button type="button" class="danger" :disabled="!deviceId || savingDesired" @click="clearDesired">清理期望状态</button>
        </div>
        <textarea v-model="desiredPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || savingDesired" @click="saveDesired">提交期望状态</button>
      </section>
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>影子差异（delta）</h3>
          <button type="button" :disabled="!deviceId || loadingDelta" @click="loadShadowDelta">读取 delta</button>
        </div>
        <pre class="json-view">{{ shadowDeltaText }}</pre>
      </section>
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <h3>影子历史</h3>
          <div class="inline-actions">
            <input v-model.number="shadowHistoryLimit" type="number" min="1" max="200" title="历史条数" />
            <button type="button" :disabled="!deviceId || loadingHistory" @click="loadShadowHistory">读取历史</button>
          </div>
        </div>
        <div class="table-wrap shadow-history-wrap">
          <table class="runtime-table">
            <thead><tr><th>版本</th><th>操作</th><th>时间</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-if="shadowHistoryRows.length === 0"><td colspan="4">暂无影子历史</td></tr>
              <tr v-for="(row, index) in shadowHistoryRows" :key="String(row.version || row.timestamp || index)">
                <td>{{ row.version || "-" }}</td>
                <td>{{ row.operation || row.type || "-" }}</td>
                <td>{{ formatShadowTime(row) }}</td>
                <td><code>{{ compactJson(row) }}</code></td>
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

import { clearShadowDesired, getShadow, getShadowDelta, getShadowHistory, updateShadowDesired } from "@/api/shadow.api";
import {
  buildShadowExportFilename,
  buildShadowExportPayload,
  compactJson,
  formatShadowTime,
  normalizeShadowHistoryRows,
  parseShadowJson,
  parseShadowJsonOrThrow,
  summarizeShadowState,
  type ShadowHistoryRow
} from "@/features/shadow/utils/shadow-utils";

const props = defineProps<{ deviceId: string }>();

const shadow = ref<unknown>({ message: "选择设备后读取影子" });
const shadowDelta = ref<unknown>({ message: "选择设备后读取 delta" });
const shadowHistoryRows = ref<ShadowHistoryRow[]>([]);
const shadowHistoryLimit = ref(50);
const desiredPayload = ref(JSON.stringify({ desired: {} }, null, 2));
const loadingShadow = ref(false);
const loadingDelta = ref(false);
const loadingHistory = ref(false);
const savingDesired = ref(false);

const shadowText = computed(() => JSON.stringify(shadow.value, null, 2));
const shadowDeltaText = computed(() => JSON.stringify(shadowDelta.value, null, 2));
const shadowSummary = computed(() => summarizeShadowState(shadow.value, parseShadowJson(desiredPayload.value), shadowDelta.value, shadowHistoryRows.value));

watch(() => props.deviceId, () => {
  shadow.value = props.deviceId ? { message: "点击读取影子" } : { message: "选择设备后读取影子" };
  shadowDelta.value = props.deviceId ? { message: "点击读取 delta" } : { message: "选择设备后读取 delta" };
  shadowHistoryRows.value = [];
});

async function loadShadow() {
  if (!props.deviceId) {
    return;
  }
  loadingShadow.value = true;
  try {
    shadow.value = await getShadow(props.deviceId);
  } catch (error) {
    handleShadowError(error, "读取影子失败", (message) => {
      shadow.value = { error: message };
    });
  } finally {
    loadingShadow.value = false;
  }
}

async function loadShadowDelta() {
  if (!props.deviceId) {
    return;
  }
  loadingDelta.value = true;
  try {
    shadowDelta.value = await getShadowDelta(props.deviceId);
  } catch (error) {
    handleShadowError(error, "读取 delta 失败", (message) => {
      shadowDelta.value = { error: message };
    });
  } finally {
    loadingDelta.value = false;
  }
}

async function loadShadowHistory() {
  if (!props.deviceId) {
    return;
  }
  loadingHistory.value = true;
  try {
    shadowHistoryRows.value = normalizeShadowHistoryRows(await getShadowHistory(props.deviceId, shadowHistoryLimit.value));
  } catch (error) {
    shadowHistoryRows.value = [];
    handleShadowError(error, "读取影子历史失败");
  } finally {
    loadingHistory.value = false;
  }
}

async function loadShadowBundle() {
  await Promise.allSettled([loadShadow(), loadShadowDelta(), loadShadowHistory()]);
}

function downloadShadowPackage() {
  if (!props.deviceId) {
    return;
  }
  const payload = buildShadowExportPayload(props.deviceId, shadow.value, parseShadowJson(desiredPayload.value), shadowDelta.value, shadowHistoryRows.value);
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = buildShadowExportFilename(props.deviceId, payload.generatedAt);
  anchor.click();
  URL.revokeObjectURL(url);
}

async function saveDesired() {
  if (!props.deviceId) {
    return;
  }
  let payload: unknown;
  try {
    payload = parseShadowJsonOrThrow(desiredPayload.value, "desired JSON");
  } catch (error) {
    handleShadowError(error, "desired JSON 格式错误");
    return;
  }
  savingDesired.value = true;
  try {
    shadow.value = await updateShadowDesired(props.deviceId, payload);
    ElMessage.success("期望状态已提交");
  } catch (error) {
    handleShadowError(error, "提交期望状态失败", (message) => {
      shadow.value = { error: message };
    });
  } finally {
    savingDesired.value = false;
  }
}

async function clearDesired() {
  if (!props.deviceId) {
    return;
  }
  savingDesired.value = true;
  try {
    shadow.value = await clearShadowDesired(props.deviceId);
    desiredPayload.value = JSON.stringify({ desired: {} }, null, 2);
    ElMessage.success("期望状态已清理");
  } catch (error) {
    handleShadowError(error, "清理期望状态失败", (message) => {
      shadow.value = { error: message };
    });
  } finally {
    savingDesired.value = false;
  }
}

function handleShadowError(error: unknown, fallback: string, assign?: (message: string) => void) {
  const message = error instanceof Error ? error.message : fallback;
  assign?.(message || fallback);
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

.shadow-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.shadow-summary-card {
  display: grid;
  min-height: 56px;
  padding: 9px 10px;
  gap: 3px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.shadow-summary-card span {
  color: var(--console-text-muted);
  font-size: 11px;
}

.shadow-summary-card strong {
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.surface-card-head,
.inline-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.inline-actions {
  justify-content: flex-end;
}

.surface-card-head h3 {
  margin: 0;
  color: var(--console-text-primary);
  font-size: 14px;
}

textarea {
  width: 100%;
  min-height: 220px;
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

.shadow-history-wrap {
  max-height: 280px;
}

@media (max-width: 1280px) {
  .shadow-summary-grid,
  .surface-grid.two {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .shadow-summary-grid,
  .surface-grid.two {
    grid-template-columns: 1fr;
  }
}
</style>
