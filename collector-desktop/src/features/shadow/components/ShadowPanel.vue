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
          <div>
            <h3>当前影子</h3>
            <small class="shadow-section-status" :class="shadowError ? 'is-warning' : ''">{{ shadowStatusText }}</small>
          </div>
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
          <div>
            <h3>期望状态更新（desired）</h3>
            <small v-if="savingDesiredDeviceId" class="shadow-section-status">正在提交设备 {{ savingDesiredDeviceId }} 的期望状态</small>
          </div>
          <button type="button" class="danger" :disabled="!deviceId || savingDesired" @click="clearDesired">清理期望状态</button>
        </div>
        <textarea v-model="desiredPayload" spellcheck="false"></textarea>
        <button type="button" class="primary wide" :disabled="!deviceId || savingDesired" @click="saveDesired">提交期望状态</button>
      </section>
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <div>
            <h3>影子差异（delta）</h3>
            <small class="shadow-section-status" :class="shadowDeltaError ? 'is-warning' : ''">{{ shadowDeltaStatusText }}</small>
          </div>
          <button type="button" :disabled="!deviceId || loadingDelta" @click="loadShadowDelta">读取 delta</button>
        </div>
        <pre class="json-view">{{ shadowDeltaText }}</pre>
      </section>
      <section class="surface-card local-section-card">
        <div class="surface-card-head">
          <div>
            <h3>影子历史</h3>
            <small class="shadow-section-status" :class="shadowHistoryError ? 'is-warning' : ''">{{ shadowHistoryStatusText }}</small>
          </div>
          <div class="inline-actions">
            <input v-model.number="shadowHistoryLimit" type="number" min="1" max="200" title="历史条数" />
            <button type="button" :disabled="!deviceId || loadingHistory" @click="loadShadowHistory">读取历史</button>
          </div>
        </div>
        <div class="table-wrap shadow-history-wrap">
          <table class="runtime-table">
            <thead><tr><th>版本</th><th>操作</th><th>时间</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-if="shadowHistoryRows.length === 0"><td colspan="4">{{ shadowHistoryEmptyText }}</td></tr>
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
  DEFAULT_SHADOW_DESIRED_PAYLOAD,
  buildShadowDeviceContext,
  buildShadowIdleMessage,
  buildShadowSectionStatus,
  buildTargetedShadowActionMessage,
  createShadowDeviceRequestOwner,
  shouldClearShadowLoading,
  shouldCommitShadowRequest,
  shouldCommitShadowWrite
} from "@/features/shadow/utils/shadow-request-state";
import {
  buildShadowExportFilename,
  buildShadowExportPayload,
  compactJson,
  formatShadowTime,
  normalizeShadowHistoryRows,
  parseShadowJson,
  parseShadowJsonOrThrow,
  summarizeShadowState,
  type ShadowHistoryRow,
  type ShadowPanelStateMessage
} from "@/features/shadow/utils/shadow-utils";
import type { DeviceShadowDeltaResponse, DeviceShadowResponse, ShadowDesiredUpdateRequest } from "@/types/shadow";

const props = defineProps<{ deviceId: string }>();

const shadow = ref<DeviceShadowResponse | ShadowPanelStateMessage>({ message: "选择设备后读取影子" });
const shadowDelta = ref<DeviceShadowDeltaResponse | ShadowPanelStateMessage>({ message: "选择设备后读取 delta" });
const shadowHistoryRows = ref<ShadowHistoryRow[]>([]);
const shadowHistoryLimit = ref(50);
const desiredPayload = ref(DEFAULT_SHADOW_DESIRED_PAYLOAD);
const loadingShadow = ref(false);
const loadingDelta = ref(false);
const loadingHistory = ref(false);
const savingDesired = ref(false);
const savingDesiredDeviceId = ref("");
const shadowError = ref("");
const shadowDeltaError = ref("");
const shadowHistoryError = ref("");
const shadowLastSuccessAt = ref<number | null>(null);
const shadowDeltaLastSuccessAt = ref<number | null>(null);
const shadowHistoryLastSuccessAt = ref<number | null>(null);

const shadowReadOwner = createShadowDeviceRequestOwner();
const shadowDeltaReadOwner = createShadowDeviceRequestOwner();
const shadowHistoryReadOwner = createShadowDeviceRequestOwner();

const shadowText = computed(() => JSON.stringify(shadow.value, null, 2));
const shadowDeltaText = computed(() => JSON.stringify(shadowDelta.value, null, 2));
const shadowSummary = computed(() => summarizeShadowState(shadow.value, parseShadowJson(desiredPayload.value), shadowDelta.value, shadowHistoryRows.value));
const shadowStatusText = computed(() => buildShadowSectionStatus({ kind: "shadow", loading: loadingShadow.value, error: shadowError.value, lastSuccessAt: shadowLastSuccessAt.value }));
const shadowDeltaStatusText = computed(() => buildShadowSectionStatus({ kind: "delta", loading: loadingDelta.value, error: shadowDeltaError.value, lastSuccessAt: shadowDeltaLastSuccessAt.value }));
const shadowHistoryStatusText = computed(() => buildShadowSectionStatus({ kind: "history", loading: loadingHistory.value, error: shadowHistoryError.value, lastSuccessAt: shadowHistoryLastSuccessAt.value }));
const shadowHistoryEmptyText = computed(() => shadowHistoryError.value ? `读取影子历史失败：${shadowHistoryError.value}` : "暂无影子历史");

watch(() => props.deviceId, () => {
  shadowReadOwner.invalidate();
  shadowDeltaReadOwner.invalidate();
  shadowHistoryReadOwner.invalidate();
  loadingShadow.value = false;
  loadingDelta.value = false;
  loadingHistory.value = false;
  shadow.value = buildShadowIdleMessage(props.deviceId, "影子");
  shadowDelta.value = buildShadowIdleMessage(props.deviceId, "delta");
  shadowHistoryRows.value = [];
  shadowError.value = "";
  shadowDeltaError.value = "";
  shadowHistoryError.value = "";
  shadowLastSuccessAt.value = null;
  shadowDeltaLastSuccessAt.value = null;
  shadowHistoryLastSuccessAt.value = null;
  desiredPayload.value = DEFAULT_SHADOW_DESIRED_PAYLOAD;
});

async function loadShadow() {
  if (!props.deviceId) {
    return;
  }
  const targetDeviceId = props.deviceId;
  const ticket = shadowReadOwner.begin(buildShadowDeviceContext(targetDeviceId));
  loadingShadow.value = true;
  try {
    const response = await getShadow(targetDeviceId);
    if (shouldCommitShadowRequest(shadowReadOwner, ticket, props.deviceId)) {
      shadow.value = response;
      shadowError.value = "";
      shadowLastSuccessAt.value = Date.now();
    }
  } catch (error) {
    if (shouldCommitShadowRequest(shadowReadOwner, ticket, props.deviceId)) {
      shadowError.value = normalizeShadowErrorMessage(error, "读取影子失败");
      ElMessage.error(shadowError.value);
    }
  } finally {
    if (shouldClearShadowLoading(shadowReadOwner, ticket)) {
      loadingShadow.value = false;
    }
  }
}

async function loadShadowDelta() {
  if (!props.deviceId) {
    return;
  }
  const targetDeviceId = props.deviceId;
  const ticket = shadowDeltaReadOwner.begin(buildShadowDeviceContext(targetDeviceId));
  loadingDelta.value = true;
  try {
    const response = await getShadowDelta(targetDeviceId);
    if (shouldCommitShadowRequest(shadowDeltaReadOwner, ticket, props.deviceId)) {
      shadowDelta.value = response;
      shadowDeltaError.value = "";
      shadowDeltaLastSuccessAt.value = Date.now();
    }
  } catch (error) {
    if (shouldCommitShadowRequest(shadowDeltaReadOwner, ticket, props.deviceId)) {
      shadowDeltaError.value = normalizeShadowErrorMessage(error, "读取 delta 失败");
      ElMessage.error(shadowDeltaError.value);
    }
  } finally {
    if (shouldClearShadowLoading(shadowDeltaReadOwner, ticket)) {
      loadingDelta.value = false;
    }
  }
}

async function loadShadowHistory() {
  if (!props.deviceId) {
    return;
  }
  const targetDeviceId = props.deviceId;
  const ticket = shadowHistoryReadOwner.begin(buildShadowDeviceContext(targetDeviceId));
  loadingHistory.value = true;
  try {
    const response = await getShadowHistory(targetDeviceId, shadowHistoryLimit.value);
    if (shouldCommitShadowRequest(shadowHistoryReadOwner, ticket, props.deviceId)) {
      shadowHistoryRows.value = normalizeShadowHistoryRows(response);
      shadowHistoryError.value = "";
      shadowHistoryLastSuccessAt.value = Date.now();
    }
  } catch (error) {
    if (shouldCommitShadowRequest(shadowHistoryReadOwner, ticket, props.deviceId)) {
      shadowHistoryError.value = normalizeShadowErrorMessage(error, "读取影子历史失败");
      ElMessage.error(shadowHistoryError.value);
    }
  } finally {
    if (shouldClearShadowLoading(shadowHistoryReadOwner, ticket)) {
      loadingHistory.value = false;
    }
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
  const targetDeviceId = props.deviceId;
  let payload: ShadowDesiredUpdateRequest;
  try {
    payload = parseShadowJsonOrThrow<ShadowDesiredUpdateRequest>(desiredPayload.value, "desired JSON");
  } catch (error) {
    handleShadowError(error, "desired JSON 格式错误");
    return;
  }
  savingDesired.value = true;
  savingDesiredDeviceId.value = targetDeviceId;
  try {
    const response = await updateShadowDesired(targetDeviceId, payload);
    if (shouldCommitShadowWrite(targetDeviceId, props.deviceId)) {
      shadow.value = response;
      shadowError.value = "";
      shadowLastSuccessAt.value = Date.now();
    }
    ElMessage.success(buildTargetedShadowActionMessage(targetDeviceId, "期望状态已提交"));
  } catch (error) {
    const message = normalizeShadowErrorMessage(error, "提交期望状态失败");
    ElMessage.error(`${buildTargetedShadowActionMessage(targetDeviceId, "提交期望状态失败")}：${message}`);
  } finally {
    savingDesired.value = false;
    savingDesiredDeviceId.value = "";
  }
}

async function clearDesired() {
  if (!props.deviceId) {
    return;
  }
  const targetDeviceId = props.deviceId;
  savingDesired.value = true;
  savingDesiredDeviceId.value = targetDeviceId;
  try {
    const response = await clearShadowDesired(targetDeviceId);
    if (shouldCommitShadowWrite(targetDeviceId, props.deviceId)) {
      shadow.value = response;
      shadowError.value = "";
      shadowLastSuccessAt.value = Date.now();
      desiredPayload.value = DEFAULT_SHADOW_DESIRED_PAYLOAD;
    }
    ElMessage.success(buildTargetedShadowActionMessage(targetDeviceId, "期望状态已清理"));
  } catch (error) {
    const message = normalizeShadowErrorMessage(error, "清理期望状态失败");
    ElMessage.error(`${buildTargetedShadowActionMessage(targetDeviceId, "清理期望状态失败")}：${message}`);
  } finally {
    savingDesired.value = false;
    savingDesiredDeviceId.value = "";
  }
}

function handleShadowError(error: unknown, fallback: string, assign?: (message: string) => void) {
  const message = normalizeShadowErrorMessage(error, fallback);
  assign?.(message || fallback);
  ElMessage.error(message || fallback);
}

function normalizeShadowErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? (error.message || fallback) : fallback;
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

.shadow-section-status {
  display: block;
  margin-top: 3px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.4;
}

.shadow-section-status.is-warning {
  color: #fbbf24;
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
