<template>
  <div class="control-workbench">
    <div class="control-grid">
      <section class="operation-panel">
        <nav class="operation-tabs" aria-label="控制操作类型">
          <button v-for="mode in operationModes" :key="mode.key" type="button" :class="{ active: activeMode === mode.key }" :aria-pressed="activeMode === mode.key" @click="activeMode = mode.key">{{ mode.label }}</button>
          <span class="control-badge" :class="{ 'tone-warning': !canControl }">{{ canControl ? '可执行' : '不可执行' }}</span>
        </nav>

        <div v-show="activeMode === 'single'" class="mode-pane">
          <div class="mode-heading">
            <div><h3>单点写入</h3><p>填写点位引用、数据类型和目标值。</p></div>
            <span class="control-badge">单次操作</span>
          </div>
          <div class="warning-strip">写入会改变设备值。写入成功与读回成功分别展示。</div>
          <div class="single-form">
            <label>点位引用<input v-model="singlePointRef" type="text" placeholder="填写点位编码或点位 ID" /></label>
            <label>数据类型
              <select v-model="singleDataType">
                <option>STRING</option><option>BOOLEAN</option><option>INT</option><option>FLOAT</option><option>DOUBLE</option>
              </select>
            </label>
            <label class="wide-field">写入值<input v-model="singleValue" type="text" placeholder="填写目标值" /></label>
          </div>
          <p class="single-help">按点位引用匹配设备点位，提交前再次确认目标和内容。</p>
        </div>

        <div v-show="activeMode === 'batch'" class="mode-pane">
          <div class="mode-heading">
            <div><h3>批量写点位</h3><p>填写点位引用和目标值，提交前逐项核对。</p></div>
            <button type="button" class="secondary compact" @click="fillBatchTemplate">套用模板</button>
          </div>
          <div class="warning-strip">批量写入可能部分成功，请以各字段的返回结果为准。</div>
          <div class="editor-head"><label for="control-batch-json">写入内容 · JSON</label><button type="button" class="text-button" @click="formatJsonDraft('batch')">格式化</button></div>
          <textarea id="control-batch-json" v-model="batchPayload" class="json-editor" spellcheck="false"></textarea>
          <div class="preview-table">
            <table>
              <colgroup><col class="reference-column" /><col /><col class="preview-status-column" /></colgroup>
              <thead><tr><th>点位引用</th><th>写入值</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="row in visiblePreviewRows" :key="row.pointRef"><td><code class="cell-value" :title="row.pointRef">{{ row.pointRef }}</code></td><td><code class="cell-value" :title="displayValue(row.value)">{{ displayValue(row.value) }}</code></td><td class="muted">待提交</td></tr>
                <tr v-if="!visiblePreviewRows.length"><td colspan="3" class="table-empty">{{ batchPreview.error ? '请检查写入内容' : '尚无写入条目' }}</td></tr>
              </tbody>
            </table>
          </div>
          <div class="code-hint"><span>{{ batchPreview.rows.length }} 个条目 · 只读预览</span><span :class="batchPreview.error ? 'tone-warning' : 'tone-success'">{{ batchPreview.error || 'JSON 格式有效' }}</span></div>
          <div v-if="previewPageCount > 1" class="page-controls">
            <span>第 {{ currentPreviewPage }} / {{ previewPageCount }} 页</span>
            <button type="button" class="secondary compact" :disabled="currentPreviewPage <= 1" @click="previewPage = currentPreviewPage - 1">上一页</button>
            <button type="button" class="secondary compact" :disabled="currentPreviewPage >= previewPageCount" @click="previewPage = currentPreviewPage + 1">下一页</button>
          </div>
        </div>

        <div v-show="activeMode === 'command'" class="mode-pane">
          <div class="mode-heading">
            <div><h3>执行协议命令</h3><p>填写命令名称和参数。</p></div>
            <button type="button" class="secondary compact" @click="fillCommandTemplate">套用模板</button>
          </div>
          <div class="warning-strip">命令能力取决于当前协议。模板仅提供结构，不代表设备支持该命令。</div>
          <div class="editor-head"><label for="control-command-json">命令与参数 · JSON</label><button type="button" class="text-button" @click="formatJsonDraft('command')">格式化</button></div>
          <textarea id="control-command-json" v-model="commandPayload" class="json-editor command-editor" spellcheck="false"></textarea>
          <div class="code-hint"><span>使用实际协议要求的 command 和 params</span><span :class="commandJsonError ? 'tone-warning' : 'muted'">{{ commandJsonError || '提交前核对命令能力' }}</span></div>
        </div>

        <footer class="operation-footer">
          <p :class="{ 'tone-warning': !canControl }">{{ canControl ? '执行前需确认目标和内容。' : '当前运行态不允许写入或执行命令。' }}</p>
          <button v-if="activeMode === 'single'" type="button" class="primary" :disabled="!deviceId || !canControl || singleWriting" :title="singleWritingText" @click="writeSingle">{{ singleWritingText }}</button>
          <button v-else-if="activeMode === 'batch'" type="button" class="primary" :disabled="!deviceId || !canControl || batchWriting" :title="batchWritingText" @click="writeBatch">{{ batchWritingText }}</button>
          <button v-else type="button" class="primary" :disabled="!deviceId || !canControl || commandExecuting" :title="commandWritingText" @click="executeCommand">{{ commandWritingText }}</button>
        </footer>
      </section>

      <aside class="result-panel" aria-label="执行结果">
        <header class="result-head"><h3>执行结果</h3><span class="control-badge" :class="`tone-${resultStatus.tone}`">{{ resultStatus.label }}</span></header>
        <div class="result-body">
          <div v-if="pendingTargets.length" class="pending-notice" role="status">
            <strong>正在执行，等待返回</strong>
            <div v-for="target in pendingTargets" :key="target.action" class="pending-target"><span>{{ actionLabel(target.action) }}</span><code>{{ target.deviceId || target.target }}</code><small>提交于 {{ formatActionTime(target.submittedAt) }}</small></div>
            <p v-if="actionResult">以下为最近完成的结果。</p>
          </div>
          <template v-if="actionResult">
            <dl class="result-target">
              <div><dt>目标设备</dt><dd><code :title="actionResult.target.deviceId || actionResult.target.target">{{ actionResult.target.deviceId || actionResult.target.target }}</code></dd></div>
              <div><dt>动作</dt><dd>{{ actionLabel(actionResult.target.action) }}</dd></div>
              <div v-if="actionResult.target.pointRef"><dt>点位</dt><dd :title="actionResult.target.pointRef"><code>{{ actionResult.target.pointRef }}</code></dd></div>
              <div><dt>提交时间</dt><dd>{{ formatActionTime(actionResult.target.submittedAt) }}</dd></div>
              <div><dt>完成时间</dt><dd>{{ formatActionTime(actionResult.completedAt) }}</dd></div>
            </dl>
            <div class="result-summary" :class="`tone-${resultStatus.tone}`" role="status"><strong>{{ resultStatus.label }}</strong><p>{{ resultStatus.description }}</p></div>
            <p v-if="actionResult.error" class="result-error">{{ actionResult.error }}</p>
            <p v-else-if="singleResult?.error || singleResult?.message" class="result-message">{{ singleResult.error || singleResult.message }}</p>
            <dl class="result-steps">
              <div><dt>操作 ID</dt><dd :title="singleResult?.operationId || ''"><code>{{ singleResult?.operationId || '未返回' }}</code></dd></div>
              <template v-if="actionResult.target.action === 'single-write'">
                <div><dt>写入结果</dt><dd :class="`tone-${writeState.tone}`">{{ writeState.label }}</dd></div>
                <div><dt>读回验证</dt><dd :class="`tone-${readbackState.tone}`">{{ readbackState.label }}</dd></div>
                <div v-if="singleResult?.requestedValue !== undefined"><dt>请求值</dt><dd :title="displayValue(singleResult.requestedValue)"><code>{{ displayValue(singleResult.requestedValue) }}</code></dd></div>
                <div v-if="singleResult?.readbackValue !== undefined"><dt>读回值</dt><dd :title="displayValue(singleResult.readbackValue)"><code>{{ displayValue(singleResult.readbackValue) }}</code></dd></div>
              </template>
              <template v-else-if="batchResult">
                <div><dt>写入成功 / 总数</dt><dd>{{ batchResult.success ?? '未返回' }} / {{ batchResult.total ?? '未返回' }}</dd></div>
                <div><dt>匹配点位数</dt><dd>{{ batchResult.mapped ?? '未返回' }}</dd></div>
                <div><dt>读回验证</dt><dd>未返回逐项读回状态</dd></div>
              </template>
              <template v-else-if="commandResult">
                <div><dt>命令</dt><dd :title="commandResult.command || ''"><code>{{ commandResult.command || '未返回' }}</code></dd></div>
                <div><dt>执行状态</dt><dd>以原始命令结果为准</dd></div>
              </template>
            </dl>
            <section v-if="batchResult" class="field-results">
              <div class="field-results-head"><h4>逐字段结果</h4><span>{{ batchFieldRows.length }} 个字段</span></div>
              <div class="field-results-list">
                <details v-for="row in visibleFieldRows" :key="row.pointRef" class="field-result">
                  <summary><code :title="row.pointRef">{{ row.pointRef }}</code><span :class="`tone-${fieldState(row.result).tone}`">{{ fieldState(row.result).label }}</span></summary>
                  <dl class="field-meta">
                    <div v-if="row.result.pointId || row.result.pointCode"><dt>点位</dt><dd :title="row.result.pointId || row.result.pointCode">{{ row.result.pointId || row.result.pointCode }}</dd></div>
                    <div><dt>映射</dt><dd>{{ row.result.mapped === true ? '已匹配' : row.result.mapped === false ? '未匹配' : '未返回' }}</dd></div>
                    <div v-if="row.result.value !== undefined"><dt>返回值</dt><dd :title="displayValue(row.result.value)"><code>{{ displayValue(row.result.value) }}</code></dd></div>
                  </dl>
                  <p v-if="row.result.error" class="result-error">{{ row.result.error }}</p>
                  <pre class="raw-json">{{ formatControlJson(row.result) }}</pre>
                </details>
                <p v-if="!batchFieldRows.length" class="muted">未返回逐字段结果，请查看原始内容。</p>
              </div>
              <div v-if="fieldPageCount > 1" class="page-controls">
                <span>{{ currentFieldPage }} / {{ fieldPageCount }}</span>
                <button type="button" class="secondary compact" :disabled="currentFieldPage <= 1" @click="fieldPage = currentFieldPage - 1">上一页</button>
                <button type="button" class="secondary compact" :disabled="currentFieldPage >= fieldPageCount" @click="fieldPage = currentFieldPage + 1">下一页</button>
              </div>
            </section>
            <details v-if="actionResult.target.payloadSummary" class="raw-result"><summary>提交摘要</summary><p class="payload-summary">{{ actionResult.target.payloadSummary }}</p></details>
            <details class="raw-result"><summary>查看原始返回内容</summary><pre class="raw-json">{{ completedResultText }}</pre></details>
          </template>
          <div v-else class="result-empty"><svg class="empty-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H5v18h14V8zM14 3v5h5M8 12h8m-8 4h8" /></svg><strong>{{ pendingTargets.length ? '等待设备返回' : '等待执行结果' }}</strong><p>提交后查看实际返回内容。<br />请求完成不等于写入成功。</p></div>
        </div>
      </aside>
    </div>
    <p class="operation-note">切换操作标签保留草稿和结果；切换设备重置草稿，结果仍归属实际提交目标。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

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
import { useDeviceStore } from "@/stores/device.store";
import type {
  BatchPointWriteFieldResponse,
  BatchPointWriteResponse,
  ControlResultResponse,
  DeviceCommandRequest,
  DeviceCommandResponse,
  PointWriteRequest,
  PointWriteResultResponse
} from "@/types/control";

interface ControlPanelMessageState {
  message?: string;
  error?: string;
}

interface ControlDisplayResult {
  operationId?: string;
  requestedValue?: unknown;
  readbackValue?: unknown;
  writeSuccess?: boolean;
  readbackAttempted?: boolean;
  readbackSuccess?: boolean;
  success?: boolean;
}


type OperationMode = "single" | "batch" | "command";
type DisplayTone = "muted" | "success" | "warning" | "danger";
interface DisplayState {
  label: string;
  tone: DisplayTone;
  description?: string;
}
interface BatchPreviewRow {
  pointRef: string;
  value: unknown;
}

const operationModes: { key: OperationMode; label: string }[] = [
  { key: "single", label: "单点写入" },
  { key: "batch", label: "批量写点位" },
  { key: "command", label: "协议命令" }
];
const activeMode = ref<OperationMode>("batch");
const pageSize = 12;
const previewPage = ref(1);
const fieldPage = ref(1);

const props = defineProps<{ deviceId: string }>();
const deviceStore = useDeviceStore();

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

const canControl = computed(() => {
  const runtime = deviceStore.runtimeMap[props.deviceId];
  return Boolean(runtime && runtime.phase === "ONLINE" && runtime.ready && runtime.connected);
});
const completedResultText = computed(() => {
  const completed = actionResult.value;
  return completed ? formatControlJson(completed.error ? { error: completed.error } : completed.result) : formatControlJson(result.value);
});
const singleResult = computed(() => actionResult.value?.target.action === "single-write" ? actionResult.value.result as PointWriteResultResponse | undefined : undefined);
const batchResult = computed(() => actionResult.value?.target.action === "batch-write" ? actionResult.value.result as BatchPointWriteResponse | undefined : undefined);
const commandResult = computed(() => actionResult.value?.target.action === "command" ? actionResult.value.result as DeviceCommandResponse | undefined : undefined);
const pendingTargets = computed(() => [singleWritingTarget.value, batchWritingTarget.value, commandWritingTarget.value].filter((target): target is ActionExecutionTarget => target !== null));

const batchPreview = computed<{ rows: BatchPreviewRow[]; error: string }>(() => {
  try {
    const payload: unknown = JSON.parse(batchPayload.value);
    if (!isRecord(payload) || !isRecord(payload.values)) {
      return { rows: [], error: "请提供 values 点位映射" };
    }
    const rows = Object.entries(payload.values).map(([pointRef, value]) => ({ pointRef, value }));
    return { rows, error: rows.length ? "" : "请填写写入条目" };
  } catch (error) {
    return { rows: [], error: safeActionErrorMessage(error, "JSON 格式错误") };
  }
});
const commandJsonError = computed(() => {
  try {
    JSON.parse(commandPayload.value);
    return "";
  } catch (error) {
    return safeActionErrorMessage(error, "JSON 格式错误");
  }
});
const previewPageCount = computed(() => Math.max(1, Math.ceil(batchPreview.value.rows.length / pageSize)));
const currentPreviewPage = computed(() => Math.min(previewPage.value, previewPageCount.value));
const visiblePreviewRows = computed(() => batchPreview.value.rows.slice((currentPreviewPage.value - 1) * pageSize, currentPreviewPage.value * pageSize));
const batchFieldRows = computed(() => Object.entries(batchResult.value?.fields || {}).map(([pointRef, fieldResult]) => ({ pointRef, result: fieldResult })));
const fieldPageCount = computed(() => Math.max(1, Math.ceil(batchFieldRows.value.length / pageSize)));
const currentFieldPage = computed(() => Math.min(fieldPage.value, fieldPageCount.value));
const visibleFieldRows = computed(() => batchFieldRows.value.slice((currentFieldPage.value - 1) * pageSize, currentFieldPage.value * pageSize));

const writeState = computed<DisplayState>(() => {
  if (singleResult.value?.writeSuccess === true) return { label: "成功", tone: "success" };
  if (singleResult.value?.writeSuccess === false) return { label: "失败", tone: "danger" };
  if (singleResult.value?.accepted === false) return { label: "未接受", tone: "danger" };
  return { label: "未确认", tone: "muted" };
});
const readbackState = computed<DisplayState>(() => {
  if (singleResult.value?.readbackAttempted === false) return { label: "未执行", tone: "muted" };
  if (singleResult.value?.readbackAttempted === true) {
    if (singleResult.value.readbackSuccess === true) return { label: "成功", tone: "success" };
    if (singleResult.value.readbackSuccess === false) return { label: "失败", tone: "warning" };
    return { label: "结果未返回", tone: "muted" };
  }
  return { label: "未返回执行状态", tone: "muted" };
});
const resultStatus = computed<DisplayState>(() => {
  const completed = actionResult.value;
  if (!completed) return { label: pendingTargets.value.length ? "执行中" : "未执行", tone: "muted" };
  if (completed.error) return { label: "请求失败", tone: "danger", description: "未取得完整返回，设备是否执行仍需确认。" };
  const single = singleResult.value;
  if (single) {
    if (single.writeSuccess === false || single.accepted === false) return { label: "写入失败", tone: "danger", description: "设备未完成写入，请查看返回原因。" };
    if (single.writeSuccess === true) {
      if (single.readbackAttempted === true && single.readbackSuccess === false) return { label: "读回失败", tone: "warning", description: "写入已成功，读回验证失败。" };
      if (single.readbackAttempted === true && single.readbackSuccess === true) return { label: "写入成功", tone: "success", description: "写入和读回验证均成功。" };
      if (single.success === false) return { label: "写入成功，验证未确认", tone: "warning", description: "写入已成功，完整操作未成功，请查看原始内容。" };
      return { label: "写入成功", tone: "success", description: single.readbackAttempted === false ? "未执行读回验证。" : "读回结果未确认。" };
    }
    if (single.success === false || single.error) return { label: "操作未成功", tone: "danger", description: "写入与读回的具体状态以返回内容为准。" };
  }
  const batch = batchResult.value;
  if (batch) {
    const fields = batchFieldRows.value;
    const succeeded = fields.filter((row) => fieldState(row.result).tone === "success").length;
    const failed = fields.filter((row) => fieldState(row.result).tone === "danger").length;
    const total = batch.total ?? fields.length;
    const successCount = batch.success ?? succeeded;
    if (successCount > 0 && (successCount < total || failed > 0)) return { label: "部分成功", tone: "warning", description: "部分字段已写入，其余字段请查看逐项返回。" };
    if (total > 0 && successCount === total && failed === 0) return { label: "写入成功", tone: "success", description: "批量写入已成功，不代表读回验证成功。" };
    if ((batch.success === 0 && total > 0) || (fields.length > 0 && failed === fields.length)) return { label: "写入失败", tone: "danger", description: "未有字段写入成功，请查看逐项返回。" };
    if (failed > 0) return { label: "存在失败字段", tone: "warning", description: "其余字段状态未确认，请查看逐项返回。" };
  }
  return { label: "请求完成", tone: "muted", description: completed.target.action === "command" ? "已收到返回，命令执行结果以原始内容为准。" : "已收到返回，尚无完整写入状态。" };
});

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function actionLabel(action: string): string {
  return ({ "single-write": "单点写入", "batch-write": "批量写点位", command: "协议命令" } as Record<string, string>)[action] || action;
}

function displayValue(value: unknown): string {
  return value === undefined ? "未返回" : JSON.stringify(value) ?? String(value);
}

function fieldState(field: BatchPointWriteFieldResponse): DisplayState {
  if (field.mapped === false) return { label: "未匹配", tone: "danger" };
  if (field.success === false || field.error) return { label: "失败", tone: "danger" };
  if (field.success === true) return { label: "成功", tone: "success" };
  return { label: "未确认", tone: "muted" };
}

function formatJsonDraft(mode: "batch" | "command") {
  const draft = mode === "batch" ? batchPayload : commandPayload;
  try {
    draft.value = formatControlJson(parseControlJson<unknown>(draft.value, mode === "batch" ? "批量写入 JSON" : "协议命令 JSON"));
  } catch (error) {
    ElMessage.error(safeActionErrorMessage(error, "JSON 格式错误"));
  }
}

watch(batchPayload, () => { previewPage.value = 1; });
watch(actionResult, () => { fieldPage.value = 1; });

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
  try {
    await ElMessageBox.confirm(
      `设备：${targetDeviceId}\n点位：${targetPointRef}\n将写入值：${String(payload.value ?? "")}`,
      "确认单点写入",
      { type: "warning", confirmButtonText: "确认写入", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  singleWriting.value = true;
  singleWritingTarget.value = target;
  try {
    const response = await writeDevicePoint(targetDeviceId, targetPointRef, payload);
    actionResult.value = buildActionExecutionView(target, response);
    result.value = response;
    notifySingleWriteResult(response as ControlDisplayResult, targetDeviceId);
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
  try {
    await ElMessageBox.confirm(
      `设备：${targetDeviceId}\n写入点位数量：${Object.keys(payload.values || {}).length}\n摘要：${JSON.stringify(payload.values || {}).slice(0, 240)}`,
      "确认批量写入",
      { type: "warning", confirmButtonText: "确认写入", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
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
  try {
    await ElMessageBox.confirm(
      `设备：${targetDeviceId}\n命令：${String(payload.command)}\n参数：${JSON.stringify(payload.params || {})}`,
      "确认执行协议命令",
      { type: "warning", confirmButtonText: "确认执行", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
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

function notifySingleWriteResult(response: ControlDisplayResult, deviceId: string) {
  if (response.writeSuccess === false || response.success === false) {
    ElMessage.error(`设备 ${deviceId} 写入失败`);
  } else if (response.readbackAttempted && response.readbackSuccess === false) {
    ElMessage.warning("写入已完成，但读回验证失败");
  } else if (response.readbackAttempted === false) {
    ElMessage.success("写入成功，该点位不支持读回验证");
  } else {
    ElMessage.success(response.readbackSuccess ? "写入成功，读回验证成功" : `设备 ${deviceId} 单点写入请求已完成`);
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
.control-workbench {
  min-width: 0;
  container-type: inline-size;
  color: var(--console-text-secondary);
  font-size: 12px;
}

.control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  align-items: start;
  gap: 16px;
}

.operation-panel,
.result-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel);
}

.operation-tabs {
  display: flex;
  min-height: 54px;
  padding: 0 20px;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--console-border-soft);
}

.control-workbench .operation-tabs button {
  position: relative;
  height: 54px;
  padding: 0;
  flex-shrink: 0;
  color: var(--console-text-muted);
  border: 0;
  border-radius: 0;
  background: transparent;
}

.control-workbench .operation-tabs button.active {
  color: var(--console-text-primary);
  font-weight: 600;
}

.control-workbench .operation-tabs button.active::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--console-primary-hover);
  content: "";
}

.control-workbench .operation-tabs button:hover:not(:disabled) {
  color: var(--console-text-primary);
  background: transparent;
}

.operation-tabs > .control-badge {
  margin-left: auto;
}

.control-badge {
  display: inline-flex;
  padding: 3px 7px;
  flex-shrink: 0;
  color: var(--console-info-text);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-sm);
  background: var(--console-bg-soft);
  font-size: 11px;
  line-height: 1.4;
}

.mode-pane {
  min-height: 439px;
  padding: 18px 20px 16px;
  box-sizing: border-box;
}

.mode-heading,
.result-head,
.field-results-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mode-heading {
  margin-bottom: 14px;
}

.mode-heading h3,
.result-head h3,
.field-results-head h4 {
  margin: 0;
  color: var(--console-text-primary);
  font-size: 14px;
  font-weight: 600;
}

.mode-heading p {
  margin: 5px 0 0;
  color: var(--console-text-muted);
  font-size: 11px;
}

.warning-strip {
  margin-bottom: 16px;
  padding: 9px 12px;
  color: var(--console-warning-text);
  border-left: 2px solid var(--console-warning);
  border-radius: 0 var(--console-radius-sm) var(--console-radius-sm) 0;
  background: color-mix(in srgb, var(--console-warning) 10%, var(--console-panel));
  font-size: 11px;
  line-height: 1.6;
}

.single-form {
  display: grid;
  margin: 22px 0;
  grid-template-columns: minmax(0, 1fr) 170px;
  gap: 17px;
}

.single-form label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
  color: var(--console-text-muted);
}

.wide-field {
  grid-column: 1 / -1;
}

.control-workbench .single-form input,
.control-workbench .single-form select {
  width: 100%;
  min-width: 0;
  height: 36px;
  padding: 7px 11px;
  box-sizing: border-box;
  color: var(--console-input-text);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-input-bg);
  font: inherit;
}

.single-help {
  margin: 26px 0 0;
  padding-top: 16px;
  color: var(--console-text-muted);
  border-top: 1px solid var(--console-border-soft);
  font-size: 11px;
  line-height: 1.6;
}

.editor-head {
  display: flex;
  min-height: 34px;
  padding: 0 12px;
  align-items: center;
  justify-content: space-between;
  color: var(--console-text-muted);
  border: 1px solid var(--console-code-border);
  border-bottom: 0;
  border-radius: var(--console-radius-sm) var(--console-radius-sm) 0 0;
  background: var(--console-bg-soft);
  font-size: 11px;
}

.control-workbench textarea.json-editor {
  display: block;
  width: 100%;
  min-height: 185px;
  height: 185px;
  padding: 9px 12px;
  box-sizing: border-box;
  color: var(--console-code-text);
  border: 1px solid var(--console-input-border);
  border-radius: 0 0 var(--console-radius-sm) var(--console-radius-sm);
  background: var(--console-code-bg);
  font-family: Consolas, "Microsoft YaHei", monospace;
  font-size: 12px;
  line-height: 1.9;
  resize: none;
  tab-size: 2;
}

.control-workbench textarea.command-editor {
  height: 244px;
}

.preview-table {
  max-height: 194px;
  margin-top: 14px;
  overflow: auto;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-sm);
}

.preview-table table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  text-align: left;
}

.reference-column {
  width: 42%;
}

.preview-status-column {
  width: 72px;
}

.preview-table th,
.preview-table td {
  height: 36px;
  padding: 0 12px;
  border-top: 1px solid var(--console-border-soft);
}

.preview-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  height: 32px;
  color: var(--console-text-muted);
  border-top: 0;
  background: var(--console-bg-soft);
  font-size: 11px;
  font-weight: 400;
}

.cell-value {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-empty {
  color: var(--console-text-muted);
  text-align: center;
}

.code-hint,
.page-controls {
  display: flex;
  margin-top: 12px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.6;
}

.code-hint > span:last-child {
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.page-controls {
  justify-content: flex-end;
}

.page-controls > span {
  margin-right: auto;
}

.operation-footer {
  display: flex;
  min-height: 60px;
  padding: 13px 20px;
  box-sizing: border-box;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  border-top: 1px solid var(--console-border-soft);
}

.operation-footer p {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.6;
}

.control-workbench :where(button) {
  max-width: 100%;
  cursor: pointer;
  font: inherit;
}

.control-workbench button.secondary,
.control-workbench button.primary {
  height: 34px;
  padding: 0 13px;
  flex-shrink: 0;
  color: var(--console-input-text);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-input-bg);
  white-space: nowrap;
}

.control-workbench button.secondary:hover:not(:disabled) {
  background: var(--console-input-bg-hover);
}

.control-workbench button.compact {
  height: 29px;
  padding: 0 10px;
  font-size: 11px;
}

.control-workbench button.primary {
  max-width: 60%;
  overflow: hidden;
  color: var(--console-text-primary);
  border-color: var(--console-primary);
  background: var(--console-primary);
  text-overflow: ellipsis;
}

.control-workbench button.primary:hover:not(:disabled) {
  background: var(--console-primary-hover);
}

.control-workbench button.text-button {
  height: 30px;
  padding: 0 2px;
  color: var(--console-info-text);
  border: 0;
  background: transparent;
  font-size: 11px;
}

.control-workbench button.text-button:hover:not(:disabled) {
  color: var(--console-text-primary);
  background: transparent;
}

.control-workbench button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.control-workbench :is(button, input, select, textarea, summary):focus-visible {
  outline: 2px solid var(--console-input-border-focus);
  outline-offset: 2px;
}

.result-head {
  min-height: 54px;
  padding: 0 18px;
  box-sizing: border-box;
  border-bottom: 1px solid var(--console-border-soft);
}

.result-body {
  min-height: 452px;
  padding: 18px;
  box-sizing: border-box;
}

.result-target,
.result-steps,
.field-meta {
  display: grid;
  margin: 0;
  gap: 10px;
  font-size: 11px;
}

.result-target {
  padding-bottom: 17px;
  border-bottom: 1px solid var(--console-border-soft);
}

.result-target > div,
.result-steps > div,
.field-meta > div {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
}

.result-panel dt {
  flex-shrink: 0;
  color: var(--console-text-muted);
}

.result-panel dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-panel dd code {
  font-size: 11px;
}

.result-summary {
  display: flex;
  min-height: 118px;
  padding: 14px 0;
  box-sizing: border-box;
  flex-direction: column;
  justify-content: center;
  gap: 9px;
  text-align: center;
}

.result-summary strong {
  font-size: 13px;
  font-weight: 500;
}

.result-summary p,
.result-empty p {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.8;
}

.result-steps {
  padding-top: 16px;
  border-top: 1px solid var(--console-border-soft);
  gap: 13px;
}

.result-empty {
  display: flex;
  min-height: 270px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
}

.result-empty strong {
  font-size: 13px;
  font-weight: 500;
}

.empty-icon {
  width: 40px;
  height: 40px;
  padding: 9px;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.5;
}

.pending-notice {
  margin-bottom: 16px;
  padding: 10px;
  color: var(--console-info-text);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-sm);
  background: var(--console-bg-soft);
  font-size: 11px;
  line-height: 1.6;
}

.pending-target {
  display: grid;
  margin-top: 8px;
  gap: 3px;
  overflow-wrap: anywhere;
}

.pending-target small,
.pending-notice p {
  margin: 6px 0 0;
  color: var(--console-text-muted);
  font-size: 11px;
}

.result-error,
.result-message {
  margin: 0 0 14px;
  overflow-wrap: anywhere;
  color: var(--console-danger-text);
  font-size: 11px;
  line-height: 1.7;
}

.result-message {
  color: var(--console-text-muted);
}

.field-results {
  margin-top: 18px;
}

.field-results-head {
  margin-bottom: 9px;
  color: var(--console-text-muted);
  font-size: 11px;
}

.field-results-head h4 {
  font-size: 12px;
}

.field-results-list {
  max-height: 280px;
  overflow: auto;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-sm);
}

.field-result + .field-result {
  border-top: 1px solid var(--console-border-soft);
}

.field-result summary {
  display: flex;
  min-height: 36px;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.field-result summary::before {
  color: var(--console-text-muted);
  content: "+";
}

.field-result[open] summary::before {
  content: "−";
}

.field-result summary code {
  min-width: 0;
  overflow: hidden;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}

.field-result summary span {
  flex-shrink: 0;
  font-size: 11px;
}

.field-meta {
  padding: 6px 10px 10px;
}

.field-result .result-error {
  padding: 0 10px;
}

.field-results-list > p {
  margin: 0;
  padding: 12px;
  font-size: 11px;
  line-height: 1.6;
}

.raw-result {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid var(--console-code-border);
  border-radius: var(--console-radius-sm);
  background: var(--console-code-bg);
}

.raw-result summary {
  padding: 9px 11px;
  color: var(--console-text-muted);
  cursor: pointer;
  font-size: 11px;
}

.raw-json {
  max-height: 280px;
  margin: 0;
  padding: 0 11px 12px;
  overflow: auto;
  color: var(--console-code-text);
  font-family: Consolas, "Microsoft YaHei", monospace;
  font-size: 11px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.payload-summary {
  margin: 0;
  padding: 0 11px 12px;
  overflow-wrap: anywhere;
  color: var(--console-code-text);
  font-size: 11px;
  line-height: 1.7;
}

.operation-note {
  margin: 0;
  padding: 13px 1px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.7;
}

.muted,
.tone-muted {
  color: var(--console-text-muted);
}

.tone-success {
  color: var(--console-success-text);
}

.tone-warning {
  color: var(--console-warning-text);
}

.tone-danger {
  color: var(--console-danger-text);
}

@container (max-width: 760px) {
  .control-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .result-body {
    min-height: 0;
  }

  .result-empty {
    min-height: 160px;
  }
}

@container (max-width: 480px) {
  .operation-tabs {
    padding: 0 14px;
    gap: 17px;
  }

  .operation-tabs > .control-badge {
    display: none;
  }

  .mode-pane {
    min-height: 0;
    padding: 15px 14px;
  }

  .single-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .operation-footer {
    padding: 13px 14px;
    flex-wrap: wrap;
  }

  .control-workbench .operation-footer button.primary {
    max-width: 100%;
    margin-left: auto;
  }

  .code-hint {
    align-items: flex-start;
    flex-direction: column;
  }

  .code-hint > span:last-child {
    text-align: left;
  }
}
</style>
