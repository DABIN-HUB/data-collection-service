<template>
  <div class="shadow-workbench">
    <p v-if="!shadowCapabilityAvailable" class="capability-warning" role="status">当前后端未提供设备影子能力</p>
    <section class="shadow-metrics" aria-label="当前影子快照摘要">
      <div class="shadow-metric">
        <span class="metric-label">当前影子版本</span>
        <div><strong>{{ currentShadowVersion ?? "—" }}</strong><span :class="{ 'is-warning': shadowError }">{{ summaryStatus }}</span></div>
      </div>
      <div v-for="metric in summaryMetrics" :key="metric.label" class="shadow-metric">
        <span class="metric-label">{{ metric.label }}</span>
        <div><strong>{{ metric.count ?? "—" }}</strong><span>{{ metric.count === null ? summaryStatus : "个字段" }}</span></div>
      </div>
    </section>

    <div class="shadow-grid">
      <section class="shadow-panel state-panel">
        <header class="panel-head">
          <h3>影子状态对照</h3>
          <div class="inline-actions">
            <button type="button" :disabled="!deviceId || !shadowCapabilityAvailable || loadingShadow || loadingDelta || loadingHistory" @click="loadShadowBundle">读取全部</button>
            <button type="button" :disabled="!deviceId || !shadowCapabilityAvailable || loadingShadow" @click="loadShadow">读取影子</button>
            <button type="button" :disabled="!deviceId" @click="downloadShadowPackage">导出快照</button>
          </div>
        </header>
        <div class="state-toolbar">
          <span class="section-status" :class="{ 'is-warning': shadowError }" role="status">{{ shadowStatusText }}</span>
          <div class="view-switcher" aria-label="影子显示方式">
            <button type="button" :class="{ active: stateView === 'table' }" :aria-pressed="stateView === 'table'" @click="stateView = 'table'">字段对照</button>
            <button type="button" :class="{ active: stateView === 'json' }" :aria-pressed="stateView === 'json'" @click="stateView = 'json'">原始 JSON</button>
          </div>
        </div>
        <template v-if="stateView === 'table'">
          <div class="table-scroll">
            <table class="shadow-table">
              <thead><tr><th scope="col">字段</th><th scope="col">当前 · reported</th><th scope="col">已保存 · desired</th><th scope="col">差异 · delta</th></tr></thead>
              <tbody>
                <tr v-if="visibleFields.length === 0"><td colspan="4" class="empty-state">{{ fieldEmptyText }}</td></tr>
                <tr v-for="row in visibleFields" :key="row.key" :class="{ changed: row.delta.present, selected: selectedField === row.key }">
                  <td><button type="button" class="field-link" :title="row.key" :aria-label="`查看字段 ${row.key} 的完整值`" @click="selectedField = selectedField === row.key ? null : row.key"><code>{{ row.key }}</code></button></td>
                  <td :class="{ 'is-missing': !row.reported.present }"><code class="cell-value" :title="row.reported.text">{{ row.reported.text }}</code></td>
                  <td :class="{ 'is-missing': !row.desired.present }"><code class="cell-value" :title="row.desired.text">{{ row.desired.text }}</code></td>
                  <td :class="{ 'is-missing': !row.delta.present, 'is-warning': row.delta.present }"><code class="cell-value" :title="row.delta.text">{{ row.delta.text }}</code></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="fieldKeys.length > fieldPageSize" class="pagination" aria-label="字段本地分页">
            <span>{{ fieldKeys.length }} 个字段 · 每页 {{ fieldPageSize }} 项</span>
            <div class="inline-actions"><button type="button" :disabled="fieldPage <= 1" @click="fieldPage--">上一页</button><span>{{ fieldPage }} / {{ fieldPageCount }}</span><button type="button" :disabled="fieldPage >= fieldPageCount" @click="fieldPage++">下一页</button></div>
          </div>
          <section v-if="selectedField !== null" class="field-detail" aria-label="字段完整值">
            <header><strong><code>{{ selectedField }}</code></strong><button type="button" class="text-button" @click="selectedField = null">关闭详情</button></header>
            <div v-for="value in selectedFieldValues" :key="value.kind"><span>{{ value.kind }}</span><pre :class="{ 'is-missing': !value.present }">{{ value.text }}</pre></div>
          </section>
        </template>
        <pre v-else class="raw-json current-json">{{ shadowText }}</pre>
        <div class="state-caption"><span>对照表仅使用当前影子 state 的同一快照；未设置不等于 0、null 或 false。</span><span v-if="currentDocument">版本 {{ currentShadowVersion }}</span></div>
        <div class="delta-section">
          <div class="delta-head">
            <button type="button" class="raw-toggle" :aria-expanded="deltaRawOpen" @click="deltaRawOpen = !deltaRawOpen">{{ deltaRawOpen ? "收起" : "查看" }}独立 delta 原始 JSON</button>
            <button type="button" :disabled="!deviceId || !shadowCapabilityAvailable || loadingDelta" @click="loadShadowDelta">读取 delta</button>
          </div>
          <p class="section-status" :class="{ 'is-warning': shadowDeltaError }" role="status">{{ shadowDeltaStatusText }}</p>
          <p class="section-status" :class="{ 'is-warning': deltaDocument && currentDocument && deltaDocument.version !== currentShadowVersion }">{{ deltaVersionStatus }}</p>
          <pre v-if="deltaRawOpen" class="raw-json">{{ shadowDeltaText }}</pre>
        </div>
      </section>

      <aside class="shadow-panel desired-panel">
        <header class="panel-head"><h3>期望状态编辑</h3><span class="draft-tag">草稿</span></header>
        <div class="desired-body">
          <div class="version-line"><span>当前影子版本</span><strong>{{ currentShadowVersion ?? "未读取" }}</strong></div>
          <div class="editor-head"><label for="shadow-desired-draft">期望状态 · JSON</label><button type="button" class="text-button" :disabled="savingDesired" @click="formatDesired">格式化</button></div>
          <textarea id="shadow-desired-draft" v-model="desiredPayload" spellcheck="false" aria-describedby="shadow-draft-help" :readonly="savingDesired"></textarea>
          <p class="draft-version">{{ draftVersionText }}</p>
          <p id="shadow-draft-help" class="desired-help">保存期望状态不代表设备已执行，也不代表云端已确认。版本冲突时保留草稿，重新读取并核对后再提交。</p>
          <p v-if="savingDesiredDeviceId" class="section-status" role="status">正在处理设备 {{ savingDesiredDeviceId }} 的期望状态</p>
          <p v-if="writeNotice" class="write-notice" :class="{ 'is-warning': writeFailed }" role="status">{{ writeNotice }}</p>
        </div>
        <footer class="desired-footer">
          <button type="button" class="clear-button" :disabled="!deviceId || !shadowCapabilityAvailable || savingDesired || currentShadowVersion === null" @click="clearDesired">清理期望状态</button>
          <button type="button" class="primary" :disabled="!deviceId || !shadowCapabilityAvailable || savingDesired || currentShadowVersion === null" @click="saveDesired">保存期望状态</button>
        </footer>
      </aside>
    </div>

    <section class="shadow-panel history-panel">
      <header class="panel-head">
        <div class="history-heading"><h3>影子历史</h3><span>{{ shadowHistoryLastSuccessAt === null ? "未读取" : `已读取 ${shadowHistoryRows.length} 条` }}</span></div>
        <div class="inline-actions history-controls"><label for="shadow-history-limit">读取条数</label><input id="shadow-history-limit" v-model.number="shadowHistoryLimit" type="number" min="1" max="200" /><button type="button" :disabled="!deviceId || !shadowCapabilityAvailable || loadingHistory" @click="loadShadowHistory">读取历史</button></div>
      </header>
      <p class="history-status section-status" :class="{ 'is-warning': shadowHistoryError }" role="status">{{ shadowHistoryStatusText }}</p>
      <div class="table-scroll">
        <table class="history-table">
          <thead><tr><th scope="col">版本</th><th scope="col">操作</th><th scope="col">时间</th><th scope="col">完整记录</th></tr></thead>
          <tbody>
            <tr v-if="visibleHistory.length === 0"><td colspan="4" class="empty-state">{{ shadowHistoryEmptyText }}</td></tr>
            <tr v-for="(row, index) in visibleHistory" :key="`${historyPage}-${index}`">
              <td><code>{{ row.version ?? "未记录" }}</code></td>
              <td>{{ row.action ?? row.operation ?? row.type ?? "未记录" }}</td>
              <td>{{ historyTime(row) }}</td>
              <td><details class="history-detail"><summary><code :title="compactJson(row)">{{ compactJson(row) }}</code></summary><pre class="raw-json">{{ JSON.stringify(row, null, 2) }}</pre></details></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="shadowHistoryRows.length > historyPageSize" class="pagination" aria-label="历史本地分页">
        <span>{{ shadowHistoryRows.length }} 条记录 · 每页 {{ historyPageSize }} 条</span>
        <div class="inline-actions"><button type="button" :disabled="historyPage <= 1" @click="historyPage--">上一页</button><span>{{ historyPage }} / {{ historyPageCount }}</span><button type="button" :disabled="historyPage >= historyPageCount" @click="historyPage++">下一页</button></div>
      </div>
    </section>
    <p class="operation-note">草稿与已保存 desired 分别展示；导出包含完整影子、独立 delta、编辑草稿与已读取历史，不受本地分页限制。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { ApiRequestError } from "@/api/http";

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
  normalizeShadowHistoryRows,
  parseShadowJson,
  parseShadowJsonOrThrow,
  type ShadowHistoryRow,
  type ShadowPanelStateMessage
} from "@/features/shadow/utils/shadow-utils";
import { useAppStore } from "@/stores/app.store";
import type { DeviceShadowDeltaResponse, DeviceShadowResponse, ShadowDesiredUpdateRequest, ShadowDynamicMap } from "@/types/shadow";

const props = defineProps<{ deviceId: string }>();
const appStore = useAppStore();

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
const shadowStatusText = computed(() => buildShadowSectionStatus({ kind: "shadow", loading: loadingShadow.value, error: shadowError.value, lastSuccessAt: shadowLastSuccessAt.value }));
const shadowDeltaStatusText = computed(() => buildShadowSectionStatus({ kind: "delta", loading: loadingDelta.value, error: shadowDeltaError.value, lastSuccessAt: shadowDeltaLastSuccessAt.value }));
const shadowHistoryStatusText = computed(() => buildShadowSectionStatus({ kind: "history", loading: loadingHistory.value, error: shadowHistoryError.value, lastSuccessAt: shadowHistoryLastSuccessAt.value }));
const shadowHistoryEmptyText = computed(() => {
  if (shadowHistoryError.value) return `读取影子历史失败：${shadowHistoryError.value}`;
  if (loadingHistory.value) return "正在读取影子历史…";
  return shadowHistoryLastSuccessAt.value === null ? "点击读取历史，查看影子变更记录" : "暂无影子历史";
});
const shadowCapabilityAvailable = computed(() => appStore.capabilities?.shadow.available === true);
const currentDocument = computed<DeviceShadowResponse | null>(() => "version" in shadow.value ? shadow.value : null);
const deltaDocument = computed<DeviceShadowDeltaResponse | null>(() => "version" in shadowDelta.value ? shadowDelta.value : null);
const currentShadowVersion = computed(() => currentDocument.value?.version ?? null);
const stateView = ref<"table" | "json">("table");
const fieldPage = ref(1);
const historyPage = ref(1);
const selectedField = ref<string | null>(null);
const deltaRawOpen = ref(false);
const writeNotice = ref("");
const writeFailed = ref(false);
const fieldPageSize = 20;
const historyPageSize = 10;
let componentActive = true;
let contextGeneration = 0;

const stateMaps = computed(() => ({
  reported: currentDocument.value?.state.reported ?? {},
  desired: currentDocument.value?.state.desired ?? {},
  delta: currentDocument.value?.state.delta ?? {}
}));
const fieldKeys = computed(() => Array.from(new Set([
  ...Object.keys(stateMaps.value.reported),
  ...Object.keys(stateMaps.value.desired),
  ...Object.keys(stateMaps.value.delta)
])));
const fieldPageCount = computed(() => Math.max(1, Math.ceil(fieldKeys.value.length / fieldPageSize)));
const visibleFields = computed(() => fieldKeys.value.slice((fieldPage.value - 1) * fieldPageSize, fieldPage.value * fieldPageSize).map(key => ({
  key,
  reported: fieldCell(stateMaps.value.reported, key),
  desired: fieldCell(stateMaps.value.desired, key),
  delta: fieldCell(stateMaps.value.delta, key)
})));
const selectedFieldValues = computed(() => {
  const key = selectedField.value;
  return key === null ? [] : (["reported", "desired", "delta"] as const).map(kind => ({
    kind,
    present: Object.hasOwn(stateMaps.value[kind], key),
    text: Object.hasOwn(stateMaps.value[kind], key) ? JSON.stringify(stateMaps.value[kind][key], null, 2) : "未设置"
  }));
});
const historyPageCount = computed(() => Math.max(1, Math.ceil(shadowHistoryRows.value.length / historyPageSize)));
const visibleHistory = computed(() => shadowHistoryRows.value.slice((historyPage.value - 1) * historyPageSize, historyPage.value * historyPageSize));
const summaryStatus = computed(() => {
  if (loadingShadow.value) return currentDocument.value ? "刷新中，保留上次快照" : "读取中";
  if (shadowError.value) return currentDocument.value ? "刷新失败，上次快照" : "读取失败";
  return currentDocument.value ? "已读取" : "未读取";
});
const summaryMetrics = computed(() => [
  { label: "当前状态 · reported", count: currentDocument.value ? Object.keys(stateMaps.value.reported).length : null },
  { label: "已保存期望 · desired", count: currentDocument.value ? Object.keys(stateMaps.value.desired).length : null },
  { label: "影子差异 · delta", count: currentDocument.value ? Object.keys(stateMaps.value.delta).length : null }
]);
const fieldEmptyText = computed(() => {
  if (currentDocument.value) return "当前快照没有状态字段";
  if (shadowError.value) return "影子读取失败，请重新读取";
  return loadingShadow.value ? "正在读取当前设备影子…" : "点击读取影子，查看当前与已保存期望状态";
});
const deltaVersionStatus = computed(() => {
  const delta = deltaDocument.value;
  if (!delta) return "独立读取，尚无版本";
  const current = currentDocument.value;
  if (!current) return `独立 delta 版本 ${delta.version}，当前影子未读取`;
  return delta.version === current.version
    ? `独立 delta 版本 ${delta.version}，与当前影子同版本；不并入对照表`
    : `版本不同：独立 delta ${delta.version} / 当前影子 ${current.version}；不合并快照`;
});
const draftVersionText = computed(() => {
  try {
    const draft = parseShadowJsonOrThrow<ShadowDesiredUpdateRequest>(desiredPayload.value, "期望状态 JSON");
    if (!draft || typeof draft !== "object" || Array.isArray(draft)) return "提交内容须为 JSON 对象";
    if (draft.expectedVersion !== undefined || draft.shadowVersion !== undefined) {
      return `草稿版本：expectedVersion ${valueText(draft.expectedVersion)} / shadowVersion ${valueText(draft.shadowVersion)}`;
    }
    return "未指定草稿版本，提交时注入当前影子版本";
  } catch {
    return "JSON 格式无效，请修正后提交";
  }
});

watch(fieldPageCount, count => { fieldPage.value = Math.min(fieldPage.value, count); });
watch(historyPageCount, count => { historyPage.value = Math.min(historyPage.value, count); });

function valueText(value: unknown): string {
  return value === undefined ? "未设置" : JSON.stringify(value) ?? String(value);
}

function fieldCell(map: ShadowDynamicMap, key: string) {
  return { present: Object.hasOwn(map, key), text: Object.hasOwn(map, key) ? valueText(map[key]) : "未设置" };
}

function historyTime(row: ShadowHistoryRow): string {
  const timestamp = row.timestamp ?? row.time ?? row.createdAt ?? row.updateTime;
  if (timestamp === undefined || timestamp === null) return "未记录";
  const date = new Date(timestamp);
  return Number.isNaN(date.getTime()) ? String(timestamp) : date.toLocaleString();
}

function formatDesired() {
  try {
    desiredPayload.value = JSON.stringify(parseShadowJsonOrThrow(desiredPayload.value, "期望状态 JSON"), null, 2);
  } catch (error) {
    handleShadowError(error, "期望状态 JSON 格式错误");
  }
}

function canCommitWrite(targetDeviceId: string, generation: number): boolean {
  return componentActive && generation === contextGeneration && shouldCommitShadowWrite(targetDeviceId, props.deviceId);
}

function commitWrittenShadow(response: DeviceShadowResponse) {
  // 写入成功后废弃先前影子读取，避免旧快照覆盖写入返回的新版本。
  shadowReadOwner.invalidate();
  loadingShadow.value = false;
  shadow.value = response;
  shadowError.value = "";
  shadowLastSuccessAt.value = Date.now();
}

onBeforeUnmount(() => {
  componentActive = false;
  contextGeneration += 1;
  shadowReadOwner.invalidate();
  shadowDeltaReadOwner.invalidate();
  shadowHistoryReadOwner.invalidate();
});

watch(() => props.deviceId, () => {
  contextGeneration += 1;
  fieldPage.value = 1;
  historyPage.value = 1;
  selectedField.value = null;
  deltaRawOpen.value = false;
  stateView.value = "table";
  writeNotice.value = "";
  writeFailed.value = false;
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
      fieldPage.value = 1;
      selectedField.value = null;
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
      historyPage.value = 1;
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
  if (!shadowCapabilityAvailable.value) {
    ElMessage.warning("当前后端未提供设备影子能力");
    return;
  }
  if (!("version" in shadow.value)) {
    ElMessage.warning("请先读取当前设备影子，再提交 desired");
    return;
  }
  const targetDeviceId = props.deviceId;
  const targetGeneration = contextGeneration;
  let payload: ShadowDesiredUpdateRequest;
  try {
    payload = parseShadowJsonOrThrow<ShadowDesiredUpdateRequest>(desiredPayload.value, "desired JSON");
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("期望状态提交内容须为 JSON 对象");
    }
    if (payload.expectedVersion === undefined && payload.shadowVersion === undefined) {
      payload.expectedVersion = shadow.value.version;
      payload.shadowVersion = shadow.value.version;
    }
  } catch (error) {
    handleShadowError(error, "desired JSON 格式错误");
    return;
  }
  writeNotice.value = "";
  writeFailed.value = false;
  savingDesired.value = true;
  savingDesiredDeviceId.value = targetDeviceId;
  try {
    const response = await updateShadowDesired(targetDeviceId, payload);
    if (canCommitWrite(targetDeviceId, targetGeneration)) {
      commitWrittenShadow(response);
      writeNotice.value = buildTargetedShadowActionMessage(targetDeviceId, "期望状态已保存到影子，未代表设备执行");
    }
    if (componentActive) ElMessage.success(buildTargetedShadowActionMessage(targetDeviceId, "期望状态已保存到设备影子"));
  } catch (error) {
    if (error instanceof ApiRequestError && (error.machineCode === "SHADOW_VERSION_CONFLICT" || error.httpStatus === 409)) {
      const message = `${buildTargetedShadowActionMessage(targetDeviceId, "影子版本已变化")}，草稿已保留；重新读取并确认后再提交`;
      if (canCommitWrite(targetDeviceId, targetGeneration)) {
        writeNotice.value = message;
        writeFailed.value = true;
      }
      if (componentActive) ElMessage.warning(message);
    } else {
      const message = normalizeShadowErrorMessage(error, "提交期望状态失败");
      if (canCommitWrite(targetDeviceId, targetGeneration)) {
        writeNotice.value = `${buildTargetedShadowActionMessage(targetDeviceId, "提交期望状态失败")}：${message}`;
        writeFailed.value = true;
      }
      if (componentActive) ElMessage.error(`${buildTargetedShadowActionMessage(targetDeviceId, "提交期望状态失败")}：${message}`);
    }
  } finally {
    if (componentActive) {
      savingDesired.value = false;
      savingDesiredDeviceId.value = "";
    }
  }
}

async function clearDesired() {
  if (!props.deviceId) {
    return;
  }
  if (!shadowCapabilityAvailable.value || !("version" in shadow.value)) {
    ElMessage.warning(!shadowCapabilityAvailable.value ? "当前后端未提供设备影子能力" : "请先读取当前设备影子，再清理 desired");
    return;
  }
  const targetDeviceId = props.deviceId;
  const targetGeneration = contextGeneration;
  writeNotice.value = "";
  writeFailed.value = false;
  savingDesired.value = true;
  savingDesiredDeviceId.value = targetDeviceId;
  try {
    const response = await clearShadowDesired(targetDeviceId, undefined, shadow.value.version);
    if (canCommitWrite(targetDeviceId, targetGeneration)) {
      commitWrittenShadow(response);
      writeNotice.value = buildTargetedShadowActionMessage(targetDeviceId, "已保存期望状态已清理");
      desiredPayload.value = DEFAULT_SHADOW_DESIRED_PAYLOAD;
    }
    if (componentActive) ElMessage.success(buildTargetedShadowActionMessage(targetDeviceId, "期望状态已清理"));
  } catch (error) {
    if (error instanceof ApiRequestError && (error.machineCode === "SHADOW_VERSION_CONFLICT" || error.httpStatus === 409)) {
      const message = `${buildTargetedShadowActionMessage(targetDeviceId, "影子版本已变化")}，草稿已保留；重新读取并确认后再提交`;
      if (canCommitWrite(targetDeviceId, targetGeneration)) {
        writeNotice.value = message;
        writeFailed.value = true;
      }
      if (componentActive) ElMessage.warning(message);
    } else {
      const message = normalizeShadowErrorMessage(error, "清理期望状态失败");
      if (canCommitWrite(targetDeviceId, targetGeneration)) {
        writeNotice.value = `${buildTargetedShadowActionMessage(targetDeviceId, "清理期望状态失败")}：${message}`;
        writeFailed.value = true;
      }
      if (componentActive) ElMessage.error(`${buildTargetedShadowActionMessage(targetDeviceId, "清理期望状态失败")}：${message}`);
    }
  } finally {
    if (componentActive) {
      savingDesired.value = false;
      savingDesiredDeviceId.value = "";
    }
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
.shadow-workbench {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 16px;
  color: var(--console-text-secondary);
  container-type: inline-size;
}
.shadow-metrics {
  display: grid;
  padding: 4px 0 2px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.shadow-metric {
  display: grid;
  min-width: 0;
  padding: 0 24px;
  gap: 6px;
  border-left: 1px solid var(--console-border-soft);
}
.shadow-metric:first-child {
  padding-left: 0;
  border-left: 0;
}
.metric-label {
  color: var(--console-text-muted);
  font-size: 12px;
}
.shadow-metric > div {
  display: flex;
  align-items: baseline;
  gap: 9px;
}
.shadow-metric strong {
  color: var(--console-text-secondary);
  font-size: 23px;
  font-weight: 600;
  line-height: 1.2;
}
.shadow-metric > div > span {
  color: var(--console-text-muted);
  font-size: 11px;
}
.shadow-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) 350px;
  gap: 16px;
  align-items: start;
}
.shadow-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel);
}
.panel-head {
  display: flex;
  min-height: 57px;
  padding: 11px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--console-border-soft);
}
.panel-head h3 {
  margin: 0;
  color: var(--console-text-secondary);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}
.inline-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.shadow-workbench button:not(.el-button) {
  min-height: 32px;
  padding: 0 11px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-sm);
  background: transparent;
  font: inherit;
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
  cursor: pointer;
}
.shadow-workbench button:hover:not(:disabled) {
  background: var(--console-input-bg-hover);
}
.shadow-workbench button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.shadow-workbench :is(button, textarea, input, summary):focus-visible {
  outline: 2px solid var(--console-input-border-focus);
  outline-offset: 2px;
}
.state-toolbar {
  display: flex;
  min-height: 51px;
  padding: 10px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.section-status {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.view-switcher {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
}
.view-switcher button {
  min-height: 29px;
  padding: 0 9px;
  color: var(--console-text-muted);
  border-color: transparent;
}
.view-switcher button.active {
  color: var(--console-info-text);
  border-color: var(--console-border);
  background: var(--console-input-bg);
}
.table-scroll {
  overflow: auto;
}
.state-panel > .table-scroll {
  max-height: 320px;
}
.history-panel > .table-scroll {
  max-height: 280px;
}
.shadow-table,
.history-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 12px;
}
.shadow-table {
  min-width: 550px;
}
.shadow-table th,
.history-table th {
  height: 35px;
  padding: 8px 18px;
  color: var(--console-text-muted);
  border-bottom: 1px solid var(--console-border-soft);
  background: var(--console-bg-soft);
  font-size: 11px;
  font-weight: 400;
  text-align: left;
  white-space: nowrap;
}
.shadow-table td,
.history-table td {
  height: 43px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--console-border-soft);
  vertical-align: top;
}
.shadow-table th:first-child {
  width: 29%;
}
.shadow-table .changed {
  background: color-mix(in srgb, var(--console-warning) 5%, transparent);
}
.shadow-table .selected {
  background: var(--console-input-bg);
}
.shadow-workbench code,
.shadow-workbench pre,
.shadow-workbench textarea,
.version-line strong {
  font-family: Consolas, "Microsoft YaHei", monospace;
}
.cell-value,
.field-link code {
  display: block;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shadow-workbench button.field-link {
  display: block;
  width: 100%;
  height: auto;
  min-height: 0;
  padding: 0;
  overflow: hidden;
  color: inherit;
  border: 0;
  border-radius: 0;
  background: transparent;
  text-align: left;
}
.shadow-workbench button.field-link:hover:not(:disabled) {
  color: var(--console-info-text);
  background: transparent;
}
.is-missing {
  color: var(--console-text-dim);
}
.shadow-workbench .is-warning {
  color: var(--console-warning-text);
}
.state-caption {
  display: flex;
  padding: 13px 18px;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.6;
}
.state-caption > span:last-child {
  flex-shrink: 0;
}
.delta-section {
  padding: 11px 18px 14px;
  border-top: 1px solid var(--console-border-soft);
}
.delta-head {
  display: flex;
  margin-bottom: 6px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.shadow-workbench button.raw-toggle,
.shadow-workbench button.text-button {
  min-height: 28px;
  padding: 0;
  color: var(--console-info-text);
  border: 0;
  background: transparent;
}
.shadow-workbench button.raw-toggle:hover:not(:disabled),
.shadow-workbench button.text-button:hover:not(:disabled) {
  color: var(--console-text-primary);
  background: transparent;
}
.raw-json {
  max-height: 330px;
  margin: 10px 0 0;
  padding: 14px;
  overflow: auto;
  color: var(--console-code-text);
  border: 1px solid var(--console-code-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-code-bg);
  font-size: 12px;
  line-height: 1.7;
  tab-size: 2;
  white-space: pre;
}
.current-json {
  min-height: 200px;
  margin: 0 18px;
}
.field-detail {
  margin: 0 18px 12px;
  padding: 12px;
  border: 1px solid var(--console-code-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-code-bg);
}
.field-detail header {
  display: flex;
  min-width: 0;
  margin-bottom: 10px;
  justify-content: space-between;
  gap: 12px;
}
.field-detail strong {
  min-width: 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.field-detail > div > span {
  color: var(--console-text-muted);
  font-size: 11px;
}
.field-detail pre {
  max-height: 240px;
  margin: 4px 0 12px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
}
.draft-tag {
  padding: 3px 8px;
  color: var(--console-info-text);
  border: 1px solid var(--console-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-input-bg);
  font-size: 11px;
}
.desired-body {
  padding: 16px 18px 18px;
}
.version-line {
  display: flex;
  margin-bottom: 17px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
.version-line span {
  color: var(--console-text-muted);
}
.version-line strong {
  font-weight: 400;
}
.editor-head {
  display: flex;
  margin-bottom: 7px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
.shadow-workbench .desired-body textarea {
  display: block;
  width: 100%;
  min-height: 230px;
  padding: 12px;
  box-sizing: border-box;
  color: var(--console-code-text);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-input-bg);
  font-family: Consolas, "Microsoft YaHei", monospace;
  font-size: 12px;
  line-height: 1.8;
  tab-size: 2;
  resize: vertical;
}
.shadow-workbench .desired-body textarea[readonly] {
  background: var(--console-input-bg-readonly);
}
.draft-version,
.desired-help,
.write-notice {
  margin: 10px 0 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.desired-body > .section-status {
  margin-top: 10px;
}
.write-notice {
  color: var(--console-success-text);
}
.desired-footer {
  display: flex;
  padding: 14px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-top: 1px solid var(--console-border-soft);
}
.shadow-workbench button.clear-button {
  padding: 0;
  color: var(--console-danger-text);
  border: 0;
  background: transparent;
}
.shadow-workbench button.clear-button:hover:not(:disabled),
.shadow-workbench button.clear-button:disabled {
  border: 0;
  background: transparent;
}
.shadow-workbench button.clear-button:hover:not(:disabled) {
  color: var(--console-danger-text);
}
.shadow-workbench button.primary {
  color: var(--console-text-primary);
  border-color: var(--console-primary);
  background: var(--console-primary);
}
.shadow-workbench button.primary:hover:not(:disabled) {
  background: var(--console-primary-hover);
}
.history-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.history-heading span,
.history-controls label {
  color: var(--console-text-muted);
  font-size: 11px;
  white-space: nowrap;
}
.shadow-workbench .history-controls input {
  width: 66px;
  min-height: 32px;
  padding: 5px 8px;
  box-sizing: border-box;
  color: var(--console-input-text);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-input-bg);
  font: inherit;
  font-size: 12px;
}
.history-status {
  padding: 8px 18px;
}
.history-table {
  min-width: 650px;
}
.history-table th:nth-child(1) {
  width: 80px;
}
.history-table th:nth-child(2) {
  width: 130px;
}
.history-table th:nth-child(3) {
  width: 185px;
}
.history-detail {
  min-width: 0;
}
.history-detail summary {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: var(--console-info-text);
  cursor: pointer;
}
.history-detail summary::before {
  content: "+";
}
.history-detail[open] summary::before {
  content: "−";
}
.history-detail summary code {
  overflow: hidden;
  color: var(--console-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pagination {
  display: flex;
  padding: 10px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-muted);
  font-size: 11px;
}
.shadow-workbench .pagination button {
  min-height: 28px;
  padding: 0 8px;
  font-size: 11px;
}
.shadow-table .empty-state,
.history-table .empty-state {
  display: table-cell;
  height: 130px;
  color: var(--console-text-muted);
  text-align: center;
  vertical-align: middle;
}
.operation-note {
  margin: -5px 0 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.7;
}
.capability-warning {
  margin: 0;
  padding: 10px 14px;
  color: var(--console-warning-text);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-sm);
  background: var(--console-panel);
  font-size: 12px;
}

@container (max-width: 1050px) {
  .shadow-grid {
    grid-template-columns: minmax(0, 1fr) 310px;
    gap: 12px;
  }
  .shadow-metric {
    padding-right: 14px;
    padding-left: 14px;
  }
  .panel-head,
  .state-toolbar {
    padding-right: 14px;
    padding-left: 14px;
  }
  .panel-head .inline-actions {
    gap: 5px;
  }
  .panel-head .inline-actions button {
    padding-right: 8px;
    padding-left: 8px;
  }
}

@container (max-width: 880px) {
  .shadow-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .shadow-workbench .desired-body textarea {
    min-height: 190px;
  }
}

@container (max-width: 600px) {
  .shadow-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px 0;
  }
  .shadow-metric:nth-child(3) {
    padding-left: 0;
    border-left: 0;
  }
  .panel-head,
  .state-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 9px;
  }
  .panel-head .inline-actions {
    max-width: 100%;
    overflow-x: auto;
  }
  .state-caption {
    flex-direction: column;
    gap: 4px;
  }
  .pagination {
    flex-wrap: wrap;
  }
}
</style>
