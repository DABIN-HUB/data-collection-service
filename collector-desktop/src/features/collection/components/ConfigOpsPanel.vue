<template>
  <section class="exact-surface config-ops-panel">
    <div class="exact-surface-head">
      <h2>配置导入导出与同步</h2>
      <span>后端配置治理接口</span>
    </div>

    <div class="surface-grid two">
      <section class="surface-card">
        <div class="surface-card-head">
          <h3>配置导出</h3>
          <div class="inline-actions">
            <button type="button" :disabled="exporting" @click="runExport">导出配置</button>
            <button type="button" :disabled="!exportText" @click="downloadExport">下载 JSON</button>
          </div>
        </div>
        <pre class="json-view config-export-view">{{ exportText || '点击“导出配置”后查看当前配置包。' }}</pre>
      </section>

      <section class="surface-card">
        <div class="surface-card-head">
          <h3>配置导入</h3>
          <label class="inline-check"><input v-model="reloadAfterImport" type="checkbox" /> 导入后刷新设备</label>
        </div>
        <textarea v-model="importText" class="config-import-textarea" spellcheck="false" placeholder='粘贴 { "bundles": [...] }、bundle 数组或单个 bundle JSON'></textarea>
        <button type="button" class="primary wide" :disabled="importing" @click="runImport">提交导入</button>
        <pre class="json-view compact-result-view">{{ importResultText }}</pre>
      </section>

      <section class="surface-card wide-field">
        <div class="surface-card-head">
          <h3>配置同步</h3>
          <div class="inline-actions">
            <button type="button" :disabled="syncing" @click="runFullSync">全量同步</button>
            <button type="button" :disabled="syncing" @click="loadSyncStatus">刷新状态</button>
          </div>
        </div>
        <div class="form-grid config-sync-form">
          <label>同步类型
            <select v-model="syncType">
              <option v-for="item in CONFIG_SYNC_TYPES" :key="item.type" :value="item.type">{{ item.label }}（{{ item.type }}）</option>
            </select>
          </label>
          <label>目标设备
            <select v-model="syncDeviceId" :disabled="!selectedSyncType?.requireDevice">
              <option value="">{{ selectedSyncType?.requireDevice ? '选择设备或留空广播' : '全部设备' }}</option>
              <option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option>
            </select>
          </label>
        </div>
        <button type="button" class="primary wide" :disabled="syncing" @click="runPartialSync">触发局部同步</button>
        <div class="exact-config-grid config-sync-status-grid">
          <div v-for="item in syncStatusItems" :key="item.label" class="exact-config-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
        <pre class="json-view compact-result-view">{{ syncResultText }}</pre>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";

import { exportConfigs, getConfigSyncStatus, importConfigs, triggerFullConfigSync, triggerPartialConfigSync } from "@/api/config.api";
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

const selectedSyncType = computed(() => CONFIG_SYNC_TYPES.find((item) => item.type === syncType.value) || CONFIG_SYNC_TYPES[0]);
const importResultText = computed(() => JSON.stringify(importResult.value ?? { message: "等待导入" }, null, 2));
const syncResultText = computed(() => JSON.stringify(syncResult.value ?? { message: "等待同步操作" }, null, 2));
const syncStatusItems = computed(() => normalizeSyncStatusItems(syncStatus.value));

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
  try {
    const parsed = parseConfigImportText(importText.value);
    const payload = buildConfigImportRequest(parsed, reloadAfterImport.value);
    const bundles = Array.isArray(payload.bundles) ? payload.bundles : [];
    if (bundles.length === 0) {
      throw new Error("导入配置包 bundles 不能为空");
    }
    importResult.value = await importConfigs(payload);
    ElMessage.success("配置导入请求已提交");
    emit("imported");
  } catch (error) {
    importResult.value = { error: error instanceof Error ? error.message : "配置导入失败" };
    ElMessage.error(error instanceof Error ? error.message : "配置导入失败");
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
  syncStatus.value = await getConfigSyncStatus();
}

function deviceIdOf(device: DeviceInfo): string {
  return String(device.deviceId || device.id || device.connectionKey || "");
}
</script>

<style scoped>
.config-ops-panel {
  display: block;
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
