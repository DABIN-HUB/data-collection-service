<template>
  <section class="exact-surface config-ops-panel">
    <details class="config-operation-group">
      <summary><strong>配置导出</strong><span>只读获取配置包，按需下载 JSON</span></summary>
      <section class="surface-card">
        <div class="surface-card-head">
          <h3>配置导出</h3>
          <div class="inline-actions">
            <button type="button" :disabled="exporting" @click="runExport">导出配置</button>
            <button type="button" :disabled="!exportText" @click="downloadExport">下载 JSON</button>
          </div>
        </div>
        <pre class="json-view config-export-view" tabindex="0" aria-label="完整导出配置包，可滚动查看">{{ exportText || '点击“导出配置”后查看当前配置包。' }}</pre>
      </section>
    </details>

    <details class="config-operation-group config-advanced-group">
      <summary><strong>高级操作</strong><span>配置导入与同步会变更设备配置</span></summary>
      <div class="surface-grid two">
      <section class="surface-card">
        <div class="surface-card-head">
          <h3>配置导入</h3>
          <label class="inline-check"><input v-model="reloadAfterImport" type="checkbox" /> 导入后刷新设备</label>
        </div>
        <textarea v-model="importText" class="config-import-textarea" aria-label="配置导入 JSON 草稿" aria-describedby="config-import-help config-validation-status" spellcheck="false" placeholder='粘贴 { "bundles": [...] }、bundle 数组或单个 bundle JSON'></textarea>
        <p id="config-import-help" class="config-operation-note">本地校验仅检查 JSON 格式与配置包数量，不验证 Schema、设备或连接，不应用或格式化草稿。</p>
        <div class="inline-actions">
          <button type="button" @click="validateImportJson">校验 JSON 格式</button>
          <button type="button" class="wide config-risk-action" :disabled="importing" @click="runImport">提交导入</button>
        </div>
        <p id="config-validation-status" class="config-validation-status" :data-state="jsonValidationState" role="status">{{ jsonValidationText }}</p>
        <p v-if="importAttemptAt" class="config-operation-note">{{ importAttemptText }}</p>
        <pre class="json-view compact-result-view" tabindex="0" aria-label="导入尝试结果，失败仅显示安全说明">{{ importResultText }}</pre>
      </section>

      <section class="surface-card wide-field">
        <div class="surface-card-head">
          <h3>配置同步</h3>
          <div class="inline-actions">
            <button type="button" class="config-risk-action" :disabled="syncing" @click="runFullSync">全量同步</button>
            <button type="button" :disabled="syncing" @click="loadSyncStatus">刷新状态</button>
          </div>
        </div>
        <p class="config-sync-warning">全量同步影响全部配置与设备。局部同步也可能广播，请先确认下方实际范围；触发同步不代表设备已运行。</p>
        <div class="form-grid config-sync-form">
          <label>同步类型
            <select v-model="syncType">
              <option v-for="item in CONFIG_SYNC_TYPES" :key="item.type" :value="item.type">{{ item.label }}（{{ item.type }}）</option>
            </select>
          </label>
          <label>目标设备
            <select v-model="syncDeviceId" :disabled="!selectedSyncType?.requireDevice">
              <option value="">{{ selectedSyncType?.requireDevice ? '留空：当前选中设备，无选中时广播' : '全部设备' }}</option>
              <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option>
            </select>
          </label>
        </div>
        <p class="config-sync-warning" role="status">{{ syncScopeText }}</p>
        <button type="button" class="wide config-risk-action" :disabled="syncing" @click="runPartialSync">触发局部同步</button>
        <OperationSourceStatus :state="syncStatusSourceState" source="配置同步状态" :last-attempt-at="syncStatusLastAttemptAt" :last-success-at="syncStatusLastSuccessAt" :detail="syncStatusReadFailed ? '本次同步状态读取失败；保留已有状态，使用原“刷新状态”重试。' : '同步状态只读查询，与同步操作结果分开。'" />
        <div class="exact-config-grid config-sync-status-grid">
          <div v-for="item in syncStatusItems" :key="item.label" class="exact-config-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
        <pre class="json-view compact-result-view" tabindex="0" aria-label="完整同步操作结果，可滚动查看">{{ syncResultText }}</pre>
      </section>
      </div>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { exportConfigs, getConfigSyncStatus, importConfigs, triggerFullConfigSync, triggerPartialConfigSync } from "@/api/config.api";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import { CONFIG_SYNC_TYPES, normalizeSyncStatusItems } from "@/features/collection/utils/config-sync-utils";
import { buildConfigExportFilename, buildConfigImportRequest, normalizeConfigExportText, parseConfigImportText } from "@/features/config/utils/config-transfer-utils";
import type { ApiResult } from "@/types/api";
import type { ConfigImportResult, ConfigSyncStatusResponse, DeviceIdResponse } from "@/types/config";
import type { DeviceInfo } from "@/types/device";

const props = defineProps<{
  devices: DeviceInfo[];
  selectedDeviceId: string;
}>();

const emit = defineEmits<{
  imported: [];
  synced: [];
}>();

const exportText = ref("");
const importText = ref("");
const importResult = ref<ConfigImportResult | Record<string, unknown> | null>(null);
const syncResult = ref<ApiResult<null> | DeviceIdResponse | Record<string, unknown> | null>(null);
const syncStatus = ref<ConfigSyncStatusResponse | null>(null);
const syncType = ref("device");
const syncDeviceId = ref("");
const reloadAfterImport = ref(true);
const exporting = ref(false);
const importing = ref(false);
const syncing = ref(false);
const jsonValidationState = ref<"unchecked" | "valid" | "invalid">("unchecked");
const validatedBundleCount = ref(0);
const importAttemptAt = ref<number | null>(null);
const importSubmitted = ref(false);
const importOutcome = ref<"pending" | "returned" | "error">("pending");
const importDraftChanged = ref(false);
const importResultReadFailed = ref(false);
const syncStatusLastAttemptAt = ref<number | null>(null);
const syncStatusLastSuccessAt = ref<number | null>(null);
const syncStatusLoading = ref(false);
const syncStatusReadFailed = ref(false);
let syncStatusRequestGeneration = 0;

const selectedSyncType = computed(() => CONFIG_SYNC_TYPES.find((item) => item.type === syncType.value) || CONFIG_SYNC_TYPES[0]);
const importResultText = computed(() => JSON.stringify(importResultReadFailed.value
  ? { error: "导入未完成，请检查 JSON 格式、配置包和接口访问权限；不回显提交内容或错误片段。" }
  : importResult.value ?? { message: importAttemptAt.value && importOutcome.value === "pending" ? "等待本次导入尝试结束" : "未提交导入" }, null, 2));
const syncResultText = computed(() => JSON.stringify(syncResult.value ?? { message: "等待同步操作" }, null, 2));
const syncStatusItems = computed(() => normalizeSyncStatusItems(syncStatus.value));
const syncStatusSourceState = computed(() => syncStatusLoading.value ? "loading" : syncStatusReadFailed.value ? syncStatus.value ? "degraded" : "error" : syncStatusLastSuccessAt.value ? "ready" : "unknown");
const jsonValidationText = computed(() => jsonValidationState.value === "valid"
  ? `格式有效（校验未提交）：识别 ${validatedBundleCount.value} 个配置包；导入结果另列，不代表后端已接受。`
  : jsonValidationState.value === "invalid" ? "格式错误（校验未提交）：请提供有效 JSON 与至少一个配置包；不回显内容。" : importAttemptAt.value && !importDraftChanged.value ? "当前草稿未作独立格式校验；上次导入尝试与结果另列。" : "当前草稿未校验、未提交；格式校验不会发送请求。"
);
const importAttemptText = computed(() => {
  const time = importAttemptAt.value ? new Date(importAttemptAt.value).toLocaleString("zh-CN", { hour12: false }) : "";
  if (importOutcome.value === "pending") return `本次导入尝试 ${time}：处理中。${importResult.value ? "下方保留的是此前尝试结果，不是本次结果。" : "尚无本次结果。"}${importDraftChanged.value ? "草稿已变更，本次尝试不属于当前草稿。" : ""}`;
  const outcome = importOutcome.value === "returned" ? "请求已返回，导入明细见原始结果" : importSubmitted.value ? "请求未完成" : "本地解析未通过，未发送导入请求";
  return `上次导入尝试 ${time}：${outcome}。${importDraftChanged.value ? "草稿已变更，以下结果不属于当前草稿。" : "以下结果仅属于该次尝试。"}`;
});
const syncScopeText = computed(() => {
  const type = selectedSyncType.value;
  if (!type.requireDevice) return `当前局部同步类型：${type.label}（${syncType.value}）；范围：全部配置与设备，不使用设备选择。`;
  const target = syncDeviceId.value || props.selectedDeviceId;
  return target
    ? `当前局部同步类型：${type.label}（${syncType.value}）；有效目标：${target}（${syncDeviceId.value ? "显式选择" : "留空回退到当前选中设备"}）。`
    : `当前局部同步类型：${type.label}（${syncType.value}）；目标留空且无当前选中设备，按原规则广播。`;
});

watch(importText, () => {
  jsonValidationState.value = "unchecked";
  validatedBundleCount.value = 0;
  if (importAttemptAt.value) importDraftChanged.value = true;
}, { flush: "sync" });

function validateImportJson() {
  try {
    const parsed = parseConfigImportText(importText.value);
    const payload = buildConfigImportRequest(parsed, reloadAfterImport.value);
    const bundles = Array.isArray(payload.bundles) ? payload.bundles : [];
    if (bundles.length === 0) throw new Error("配置包为空");
    validatedBundleCount.value = bundles.length;
    jsonValidationState.value = "valid";
  } catch {
    validatedBundleCount.value = 0;
    jsonValidationState.value = "invalid";
  }
}

onMounted(() => {
  loadSyncStatus().catch(() => undefined);
});

async function runExport() {
  exporting.value = true;
  try {
    const response = await exportConfigs();
    exportText.value = normalizeConfigExportText(response);
    ElMessage.success("配置已导出，可复制或下载 JSON");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "配置导出失败");
  } finally {
    exporting.value = false;
  }
}

function downloadExport() {
  if (!exportText.value) {
    return;
  }
  const blob = new Blob([exportText.value], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = buildConfigExportFilename();
  anchor.click();
  URL.revokeObjectURL(url);
}

async function runImport() {
  importing.value = true;
  importAttemptAt.value = Date.now();
  importSubmitted.value = false;
  importOutcome.value = "pending";
  importDraftChanged.value = false;
  try {
    const parsed = parseConfigImportText(importText.value);
    const payload = buildConfigImportRequest(parsed, reloadAfterImport.value);
    const bundles = Array.isArray(payload.bundles) ? payload.bundles : [];
    if (bundles.length === 0) {
      throw new Error("导入配置包 bundles 不能为空");
    }
    importSubmitted.value = true;
    importResult.value = await importConfigs(payload);
    importResultReadFailed.value = false;
    importOutcome.value = "returned";
    ElMessage.success("配置导入请求已提交");
    emit("imported");
  } catch (error) {
    importResult.value = { error: error instanceof Error ? error.message : "配置导入失败" };
    importResultReadFailed.value = true;
    importOutcome.value = "error";
    ElMessage.error("配置导入未完成，请检查格式、配置包或接口访问权限");
  } finally {
    importing.value = false;
  }
}

async function runFullSync() {
  syncing.value = true;
  try {
    syncResult.value = await triggerFullConfigSync();
    ElMessage.success("已触发全量配置同步");
    await loadSyncStatus();
    emit("synced");
  } catch (error) {
    syncResult.value = { error: error instanceof Error ? error.message : "全量同步失败" };
    ElMessage.error(error instanceof Error ? error.message : "全量同步失败");
  } finally {
    syncing.value = false;
  }
}

async function runPartialSync() {
  syncing.value = true;
  try {
    const targetDeviceId = selectedSyncType.value.requireDevice ? syncDeviceId.value || props.selectedDeviceId || undefined : undefined;
    syncResult.value = await triggerPartialConfigSync(syncType.value, targetDeviceId);
    ElMessage.success(`已触发${selectedSyncType.value.label}同步`);
    await loadSyncStatus();
    emit("synced");
  } catch (error) {
    syncResult.value = { error: error instanceof Error ? error.message : "局部同步失败" };
    ElMessage.error(error instanceof Error ? error.message : "局部同步失败");
  } finally {
    syncing.value = false;
  }
}

async function loadSyncStatus() {
  const requestGeneration = ++syncStatusRequestGeneration;
  syncStatusLastAttemptAt.value = Date.now();
  syncStatusLoading.value = true;
  syncStatusReadFailed.value = false;
  try {
    const nextStatus = await getConfigSyncStatus();
    if (requestGeneration !== syncStatusRequestGeneration) return;
    syncStatus.value = nextStatus;
    syncStatusLastSuccessAt.value = Date.now();
  } catch (error) {
    if (requestGeneration !== syncStatusRequestGeneration) return;
    syncStatusReadFailed.value = true;
    throw error;
  } finally {
    if (requestGeneration === syncStatusRequestGeneration) {
      syncStatusLoading.value = false;
    }
  }
}

function deviceIdOf(device: DeviceInfo): string {
  return String(device.deviceId || device.id || "");
}
</script>

<style scoped>
.config-ops-panel {
  display: block;
}

.config-operation-group {
  min-width: 0;
}

.config-operation-group + .config-operation-group {
  border-top: 1px solid var(--console-border-soft);
}

.config-operation-group > summary {
  min-height: 44px;
  padding: 12px 16px;
  color: var(--console-text-primary);
  cursor: pointer;
  overflow-wrap: anywhere;
}

.config-operation-group > summary strong {
  font-size: 14px;
  font-weight: 600;
}

.config-operation-group > summary span {
  display: block;
  margin: 4px 0 0 18px;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.config-operation-group[open] > summary {
  border-bottom: 1px solid var(--console-border-soft);
}

.config-operation-note,
.config-validation-status,
.config-sync-warning {
  max-width: 80ch;
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.config-operation-note {
  color: var(--console-text-muted);
}

.config-sync-warning {
  padding: 8px 12px;
  color: var(--app-color-warning);
  border-left: 2px solid var(--console-warning);
  background: var(--app-color-warning-soft);
}

.config-validation-status {
  color: var(--console-text-secondary);
}

.config-validation-status[data-state="valid"] {
  color: var(--app-color-primary);
}

.config-validation-status[data-state="invalid"] {
  color: var(--app-color-error);
}

.config-export-view {
  min-height: 240px;
}

.config-import-textarea {
  min-height: 180px;
  resize: vertical;
}

.config-sync-form {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.config-sync-status-grid {
  display: grid;
  padding: 20px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

@media (max-width: 1100px) {
  .config-sync-status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .config-sync-form {
    grid-template-columns: 1fr;
  }
}
</style>
