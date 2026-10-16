<template>
  <section id="deviceOperationPanel" class="local-editor local-device-panel local-device-web-dialog device-operation-panel config-workspace">
    <nav class="config-breadcrumb" aria-label="当前位置">
      <button type="button" @click="backToDeviceList">设备管理</button><span aria-hidden="true">/</span><span>{{ activeTab === 'config' ? '设备配置' : '设备工作台' }}</span>
    </nav>
    <div class="local-editor-title">
      <div>
        <h3>{{ selectedDeviceName }}</h3>
        <p>{{ selectedDeviceId || "从设备管理列表选择设备" }} · {{ selectedDeviceProtocol }} · {{ deviceAddress(deviceStore.selectedDevice) }}</p>
      </div>
      <div class="local-editor-title-actions">
        <button type="button" @click="backToDeviceList">返回列表</button>
      </div>
    </div>

    <div class="local-editor-tabs" role="tablist" aria-label="设备操作工作台分区">
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'config' }" @click="openWorkbenchTab('config')">
        <strong>设备配置工作台</strong>
      </button>
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'control' }" @click="openWorkbenchTab('control')">
        <strong>批量和协议命令</strong>
      </button>
      <button type="button" class="local-editor-tab" :class="{ 'is-active': activeTab === 'shadow' }" @click="openWorkbenchTab('shadow')">
        <strong>设备影子</strong>
      </button>
    </div>

    <div class="local-editor-layout">
      <div class="local-editor-body device-operation-body">
        <div class="config-overview" aria-label="当前设备配置上下文">
          <dl class="config-overview-fields">
            <div><dt>设备分组</dt><dd>{{ deviceStore.selectedDevice?.displayGroup || '未分组' }}</dd></div>
            <div><dt>配置来源</dt><dd>{{ deviceStore.selectedDevice?.configSource || 'local' }}</dd></div>
            <div><dt>采集周期</dt><dd>{{ deviceStore.selectedDevice?.collectionInterval ?? '-' }} ms</dd></div>
          </dl>
          <div class="config-overview-actions">
            <button type="button" :disabled="!selectedDeviceId || deviceConfigOperatingId === `refresh:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'refresh')">刷新配置</button>
            <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceRuntimeStatus">运行状态</button>
            <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceAlarmHistory">告警历史</button>
            <button type="button" class="danger" :disabled="!selectedDeviceId || deviceConfigOperatingId === `clear:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'clear')">清理缓存</button>
          </div>
        </div>
        <section v-if="activeTab !== 'config'" class="operation-runtime" aria-label="设备运行状态">
          <div class="operation-runtime-heading"><strong>设备运行状态</strong><small>当前设备运行快照</small></div>
          <dl>
            <div><dt>生命周期</dt><dd>{{ selectedRuntimeSnapshot?.phase || selectedOperationStatus }}</dd></div>
            <div><dt>传输连接</dt><dd>{{ selectedConnectionText }}</dd></div>
            <div><dt>协议就绪</dt><dd>{{ selectedRuntimeSnapshot?.ready === true ? '就绪' : selectedRuntimeSnapshot?.ready === false ? '未就绪' : '未知' }}</dd></div>
            <div><dt>实时点位</dt><dd :class="previewChecklistClass">{{ previewStatusText }}</dd></div>
          </dl>
        </section>
        <slot :device-id="selectedDeviceId" :device="deviceStore.selectedDevice" :realtime-preview-rows="realtimePreviewRows" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { clearDeviceConfig, refreshDeviceConfig } from "@/api/config.api";
import { getDeviceRealtimeData } from "@/api/data.api";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { buildDeviceRequestContext, isSameDeviceRequestContext } from "@/features/device/utils/device-request-lifecycle";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import { buildContextualReadStatus, hasLastGoodForContext, shouldClearLastGoodForRequest } from "@/features/request/utils/context-last-good";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
import { routePathForWorkbenchTab, type WorkbenchNavigationTab } from "@/router/route-names";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";

const props = defineProps<{ activeTab: WorkbenchNavigationTab }>();

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();
const router = useRouter();

const realtimePreviewRows = ref<RealtimePointRow[]>([]);
const previewLoading = ref(false);
const previewError = ref("");
const lastSuccessfulPreviewContext = ref<ReturnType<typeof buildDeviceRequestContext> | null>(null);
const previewLastSuccessAt = ref<number | null>(null);
const deviceConfigOperatingId = ref("");

const previewRequestOwner = createLatestRequestOwner(isSameDeviceRequestContext);

const activeTab = computed(() => props.activeTab);
const selectedDeviceId = computed(() => deviceStore.selectedDevice?.normalizedId || deviceStore.selectedDeviceId || queryDeviceId());
const selectedDeviceName = computed(() => deviceStore.selectedDevice?.displayName || selectedDeviceId.value || "请选择设备");
const selectedDeviceProtocol = computed(() => deviceStore.selectedDevice?.displayProtocol || String(deviceStore.selectedDevice?.protocolType || deviceStore.selectedDevice?.connectionType || "-"));
const selectedRuntimeSnapshot = computed<DeviceRuntimeSnapshot | undefined>(() => {
  const deviceId = selectedDeviceId.value;
  return deviceStore.selectedDevice?.runtime || (deviceId ? deviceStore.runtimeMap[deviceId] : undefined);
});
const selectedConnectionText = computed(() => selectedRuntimeSnapshot.value?.connected === true ? "已连接" : selectedRuntimeSnapshot.value?.connected === false ? "未连接" : "未知");
const previewStatusMessage = computed(() => buildContextualReadStatus({
  loading: previewLoading.value,
  error: previewError.value,
  lastSuccessfulContext: lastSuccessfulPreviewContext.value,
  currentContext: currentPreviewRequestContext(),
  isSameContext: isSameDeviceRequestContext,
  loadingText: "实时预览加载中",
  refreshingText: "实时预览刷新中 · 当前显示上次成功数据",
  staleText: "实时预览暂不可用 · 当前显示上次成功数据",
  initialErrorPrefix: "实时预览不可用",
  lastSuccessAt: previewLastSuccessAt.value
}));
const previewStatusText = computed(() => {
  if (previewStatusMessage.value) {
    return previewStatusMessage.value;
  }
  return realtimePreviewRows.value.length > 0 ? `${realtimePreviewRows.value.length} 个` : "0 个";
});
const previewChecklistClass = computed(() => {
  if (previewError.value && !hasLastGoodForContext(lastSuccessfulPreviewContext.value, currentPreviewRequestContext(), isSameDeviceRequestContext)) {
    return "is-error";
  }
  return realtimePreviewRows.value.length > 0 || hasLastGoodForContext(lastSuccessfulPreviewContext.value, currentPreviewRequestContext(), isSameDeviceRequestContext) ? "is-ok" : "is-warn";
});
const selectedOperationStatus = computed(() => {
  const runtime = selectedRuntimeSnapshot.value;
  return String(runtime?.phase || deviceStore.selectedDevice?.status || deviceStore.selectedDevice?.["runtimeStatus"] || "未知");
});

onMounted(async () => {
  await appStore.initialize();
  applyRouteDevice();
  await deviceStore.refresh();
  applyRouteDevice();
  if (activeTab.value !== "config") {
    await loadRealtimePreview();
  }
});

watch(() => route.query.deviceId, () => {
  applyRouteDevice();
  if (activeTab.value !== "config") {
    void loadRealtimePreview();
  }
});

onBeforeUnmount(() => {
  previewRequestOwner.invalidate();
});

async function operateDeviceConfig(deviceId: string, type: DeviceConfigActionType) {
  if (!deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const option = DEVICE_CONFIG_ACTIONS.find((item) => item.type === type);
  const label = option?.label || "配置操作";
  const confirmText = option?.confirmText || "该操作只影响本地配置缓存。";
  try {
    await ElMessageBox.confirm(`确认对设备 ${deviceId} 执行${label}？${confirmText}`, "确认配置操作", {
      confirmButtonText: "确认执行",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  deviceConfigOperatingId.value = `${type}:${deviceId}`;
  try {
    const response = type === "clear" ? await clearDeviceConfig(deviceId) : await refreshDeviceConfig(deviceId);
    const result = normalizeDeviceConfigActionResult(response, deviceId);
    ElMessage.success(buildDeviceConfigActionMessage(type, result));
    await deviceStore.refresh();
    applyRouteDevice();
    await loadRealtimePreview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置操作失败");
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

async function loadRealtimePreview() {
  const requestContext = currentPreviewRequestContext();
  if (!requestContext.deviceId) {
    previewRequestOwner.invalidate();
    realtimePreviewRows.value = [];
    previewLoading.value = false;
    previewError.value = "";
    lastSuccessfulPreviewContext.value = null;
    previewLastSuccessAt.value = null;
    return;
  }
  if (shouldClearLastGoodForRequest(lastSuccessfulPreviewContext.value, requestContext, isSameDeviceRequestContext)) {
    realtimePreviewRows.value = [];
    previewError.value = "";
    lastSuccessfulPreviewContext.value = null;
    previewLastSuccessAt.value = null;
  } else {
    previewError.value = "";
  }
  const ticket = previewRequestOwner.begin(requestContext);
  previewLoading.value = true;
  try {
    const nextRows = normalizeRealtimeRows(await getDeviceRealtimeData(requestContext.deviceId), requestContext.deviceId);
    if (!previewRequestOwner.canCommit(ticket, currentPreviewRequestContext())) {
      return;
    }
    realtimePreviewRows.value = nextRows;
    previewError.value = "";
    lastSuccessfulPreviewContext.value = requestContext;
    previewLastSuccessAt.value = Date.now();
  } catch (caught) {
    if (!previewRequestOwner.canCommit(ticket, currentPreviewRequestContext())) {
      return;
    }
    previewError.value = caught instanceof Error ? caught.message : "实时预览加载失败";
    if (shouldClearLastGoodForRequest(lastSuccessfulPreviewContext.value, requestContext, isSameDeviceRequestContext)) {
      realtimePreviewRows.value = [];
    }
  } finally {
    if (previewRequestOwner.isLatest(ticket)) {
      previewLoading.value = false;
    }
  }
}

function currentPreviewRequestContext() {
  return buildDeviceRequestContext(selectedDeviceId.value);
}

function applyRouteDevice() {
  const routeQueryDeviceId = queryDeviceId();
  if (routeQueryDeviceId) {
    const matchedDeviceId = deviceStore.devices.find((device) => device.normalizedId === routeQueryDeviceId)?.normalizedId;
    if (matchedDeviceId) {
      deviceStore.selectDevice(matchedDeviceId);
      return;
    }
    deviceStore.selectDevice(deviceStore.devices[0]?.normalizedId || routeQueryDeviceId);
    return;
  }
  if (deviceStore.selectedDevice) {
    return;
  }
  const firstDeviceId = deviceStore.devices[0]?.normalizedId || "";
  if (firstDeviceId) {
    deviceStore.selectDevice(firstDeviceId);
  }
}

function queryDeviceId(): string {
  const value = route.query.deviceId;
  if (Array.isArray(value)) {
    return String(value[0] || "");
  }
  return String(value || "");
}

function openWorkbenchTab(tab: WorkbenchNavigationTab) {
  router.push({
    path: routePathForWorkbenchTab(tab),
    query: selectedDeviceId.value ? { deviceId: selectedDeviceId.value } : {}
  }).catch(() => undefined);
}

function backToDeviceList() {
  router.push({
    path: "/device",
    query: selectedDeviceId.value ? { deviceId: selectedDeviceId.value } : {}
  }).catch(() => undefined);
}

function openSelectedDeviceRuntimeStatus() {
  if (!selectedDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  router.push({ path: "/diagnostic", query: { deviceId: selectedDeviceId.value } }).catch(() => undefined);
}

function openSelectedDeviceAlarmHistory() {
  if (!selectedDeviceId.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  router.push({ path: "/alarm", query: { deviceId: selectedDeviceId.value } }).catch(() => undefined);
}

function deviceAddress(device: DeviceInfo | DeviceViewModel | null): string {
  if (!device) {
    return "-";
  }
  const address = [device.ipAddress || device["host"], device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":");
  return address || String(device["url"] || "-");
}
</script>

<style scoped>
.device-operation-panel.config-workspace {
  display: flex;
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 23px 30px 24px;
  flex-direction: column;
  overflow: auto;
  color: var(--console-text-secondary);
  background: var(--console-bg);
}

.config-breadcrumb {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--console-text-muted);
  font-size: 11px;
}

.config-workspace .config-breadcrumb button {
  min-height: 24px;
  padding: 0;
  color: var(--console-text-muted);
  border: 0;
  background: transparent;
  font: inherit;
}

.config-workspace .local-editor-title {
  display: flex;
  min-width: 0;
  padding: 12px 0 20px;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.local-editor-title > div:first-child {
  min-width: 0;
}

.local-editor-title h3 {
  margin: 0;
  color: var(--console-text-primary);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.local-editor-title p {
  margin: 7px 0 0;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.local-editor-title-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.config-workspace .local-editor-title-actions button {
  min-height: 35px;
  padding: 0 13px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-input-border);
  border-radius: 6px;
  background: var(--console-panel);
  font: inherit;
  font-size: 12px;
}

.config-workspace .local-editor-tabs {
  display: flex;
  min-width: 0;
  min-height: 43px;
  flex-shrink: 0;
  gap: 28px;
  overflow-x: auto;
  border-bottom: 1px solid var(--console-border-soft);
}

.config-workspace button.local-editor-tab {
  position: relative;
  min-height: 43px;
  padding: 0 0 12px;
  flex: 0 0 auto;
  color: var(--console-text-muted);
  border: 0;
  border-radius: 0;
  background: transparent;
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
}

.local-editor-tab strong {
  font-weight: 400;
}

.config-workspace button.local-editor-tab.is-active {
  color: var(--console-primary);
  border-bottom: 2px solid var(--console-primary);
}

.local-editor-tab.is-active strong {
  font-weight: 600;
}

.config-workspace button.local-editor-tab:hover:not(:disabled),
.config-workspace .config-breadcrumb button:hover:not(:disabled) {
  color: var(--console-primary);
  background: transparent;
}

.local-editor-layout,
.device-operation-body {
  min-width: 0;
  min-height: 0;
}

.config-overview,
.config-overview-fields,
.config-overview-fields > div,
.config-overview-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
}

.config-overview {
  min-height: 58px;
  padding: 12px 0 16px;
  justify-content: space-between;
  gap: 12px;
}

.config-overview-fields {
  margin: 0;
  gap: 12px 24px;
  font-size: 11px;
}

.config-overview-fields > div {
  gap: 9px;
}

.config-overview-fields dt {
  color: var(--console-text-muted);
}

.config-overview-fields dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.config-overview-actions {
  gap: 8px;
}

.config-workspace .config-overview-actions button {
  min-height: 30px;
  padding: 0 4px;
  color: var(--console-primary);
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 11px;
}

.config-workspace .config-overview-actions button.danger {
  color: var(--console-danger-text);
}

.config-workspace .config-overview-actions button:hover:not(:disabled) {
  background: var(--app-color-info-soft);
}

.config-workspace button:disabled {
  opacity: 0.46;
  cursor: not-allowed;
}

.config-workspace button:focus-visible {
  outline: 2px solid var(--console-input-border-focus);
  outline-offset: 3px;
}

.operation-runtime {
  display: flex;
  min-width: 0;
  margin-bottom: 17px;
  padding: 18px 19px;
  align-items: center;
  gap: 28px;
  border: 1px solid var(--console-border-soft);
  border-radius: 8px;
  background: var(--console-panel);
}

.operation-runtime-heading {
  display: grid;
  padding-right: 24px;
  gap: 4px;
  border-right: 1px solid var(--console-border-soft);
  font-size: 13px;
}

.operation-runtime-heading small,
.operation-runtime dt {
  color: var(--console-text-muted);
  font-size: 11px;
}

.operation-runtime dl {
  display: flex;
  min-width: 0;
  margin: 0;
  gap: 16px 32px;
  flex-wrap: wrap;
}

.operation-runtime dd {
  max-width: 350px;
  margin: 6px 0 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.operation-runtime .is-ok {
  color: var(--console-success-text);
}

.operation-runtime .is-warn {
  color: var(--console-warning-text);
}

.operation-runtime .is-error {
  color: var(--console-danger-text);
}

@media (max-width: 1280px) {
  .device-operation-panel.config-workspace {
    padding: 22px 24px;
  }
}

@media (max-width: 760px) {
  .device-operation-panel.config-workspace {
    padding: 20px 14px;
  }

  .config-workspace .local-editor-title {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .local-editor-title h3 {
    font-size: 23px;
  }

  .config-workspace .local-editor-tabs {
    gap: 20px;
  }

  .operation-runtime {
    padding: 15px;
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 15px;
  }

  .operation-runtime-heading {
    padding-right: 0;
    border-right: 0;
  }
}
</style>
