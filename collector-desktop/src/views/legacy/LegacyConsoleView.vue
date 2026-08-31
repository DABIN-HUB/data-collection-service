<template>
  <div class="legacy-page-host">

      <section v-show="activeModule === 'device'" class="exact-page">
        <div class="section-heading">
          <div class="heading-title-line"><h1>设备管理</h1><span class="heading-online"><i></i>{{ filteredDevices.length }} 台设备</span></div>
          <div class="heading-actions"><button type="button" @click="loadDevices">刷新列表</button><button type="button" :disabled="configFileExporting" @click="exportDeviceConfigData">导出配置数据</button><button type="button" :disabled="configFileImporting" @click="openConfigImportFile">导入配置数据</button><button type="button" class="primary" @click="openLocalEditor()">新增本地设备</button></div>
        </div>
        <div class="exact-page-body">
          <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><input v-model="deviceKeyword" type="search" placeholder="搜索设备名称、标识或地址" /><select v-model="protocolFilter"><option value="">全部协议</option><option v-for="protocolItem in protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">{{ protocolItem.title || protocolItem.protocol }}</option></select><select v-model="statusFilter"><option value="">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option><option value="ERROR">异常</option></select></div><div class="exact-toolbar-group"><button type="button" @click="syncDevices">同步远端配置</button></div></div>
          <div class="exact-device-list">
            <div v-if="filteredDevices.length === 0" class="exact-empty">{{ deviceListEmptyText }}</div>
            <article v-for="device in filteredDevices" :key="deviceIdOf(device)" class="exact-device-card" :class="{ 'is-selected': selectedDeviceId === deviceIdOf(device) }" @click="selectDevice(deviceIdOf(device))">
              <div class="exact-device-main">
                <h3>{{ device.deviceName || deviceIdOf(device) }}</h3>
                <p>{{ deviceIdOf(device) }} · {{ isLocalDevice(device) ? '本地临时' : '远端同步' }}</p>
              </div>
              <div class="exact-device-meta">
                <strong>{{ device.protocolType || device.connectionType || '-' }}</strong>
                <span>连接地址 {{ deviceAddress(device) }}</span>
              </div>
              <div class="exact-device-meta">
                <span class="status-badge" :class="statusBadgeClass(device)">{{ localizeDeviceStatus(device.status) }}</span>
                <span>采集周期 {{ device.collectionInterval ?? '-' }} ms</span>
              </div>
              <div class="exact-device-actions">
                <button type="button" @click.stop="startSelectedDevice(deviceIdOf(device))">启动</button><button type="button" @click.stop="stopSelectedDevice(deviceIdOf(device))">停止</button>
                <button type="button" :disabled="deviceConfigOperatingId === `refresh:${deviceIdOf(device)}`" @click.stop="operateDeviceConfig(deviceIdOf(device), 'refresh')">刷新配置</button><button type="button" class="danger" :disabled="deviceConfigOperatingId === `clear:${deviceIdOf(device)}`" @click.stop="operateDeviceConfig(deviceIdOf(device), 'clear')">清理缓存</button>
                <button type="button" @click.stop="openDeviceOperation(device, 'config')">配置</button><button type="button" @click.stop="editDevice(device)">编辑</button><button type="button" @click.stop="openDeviceDiff(device)">差异</button><button type="button" @click.stop="openDeviceRuntimeStatus(device)">运行状态</button><button type="button" @click.stop="openDeviceAlarmHistory(device)">告警历史</button>
                <button type="button" @click.stop="openDeviceOperation(device, 'control')">控制</button><button type="button" @click.stop="openDeviceOperation(device, 'shadow')">影子</button>
                <button v-if="isLocalDevice(device)" type="button" class="danger" @click.stop="deleteLocal(deviceIdOf(device))">删除本地</button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section v-show="activeModule === 'collect'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>数据采集配置</h1><span class="heading-online"><i></i>{{ protocols.length }} 种协议</span></div><div class="heading-actions"><button type="button" @click="loadConfigSummary">刷新概览</button></div></div>
        <div class="exact-page-body">
          <section class="exact-surface exact-global-config">
            <div class="exact-surface-head"><h2>全局采集配置</h2><span>当前运行配置</span></div>
            <div class="exact-config-grid">
              <div v-for="item in collectionSummaryItems" :key="item.label" class="exact-config-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
            </div>
          </section>
          <LegacyConfigOpsPanel :devices="devices" :selected-device-id="selectedDeviceId" @imported="refreshAll" @synced="refreshAll" />
          <section class="exact-table-card">
            <div class="exact-table-title"><h2>协议配置列表</h2><span>{{ protocols.length }} 种协议</span></div>
            <table><thead><tr><th>协议名称</th><th>规范编码</th><th>默认端口</th><th>采集方式</th><th>能力状态</th><th>操作</th></tr></thead><tbody><tr v-if="protocols.length === 0"><td colspan="6" class="exact-empty">当前没有可用的协议定义</td></tr><tr v-for="item in protocols" :key="item.protocol"><td><strong>{{ item.title || item.protocol || '-' }}</strong></td><td><code>{{ item.protocol || '-' }}</code></td><td>{{ protocolDefaultPort(item) }}</td><td>{{ protocolMode(item) }}</td><td><span class="capability-badge">{{ protocolCapability(item) }}</span></td><td><button type="button" @click="openProtocolConfig(item)">配置设备</button></td></tr></tbody></table>
          </section>
          <section v-if="selectedProtocol" class="exact-json-panel" open>
            <summary>{{ selectedProtocol.title || selectedProtocol.protocol }} Schema</summary>
            <pre class="json-view">{{ prettyJson(selectedProtocol) }}</pre>
          </section>
        </div>
      </section>

      <LegacyHistoryPanel v-show="activeModule === 'history'" :devices="devices" :selected-device-id="selectedDeviceId" :selected-point-ref="historySelectedPointRef" @select-device="selectDevice" />

      <section v-show="activeModule === 'workbench'" id="deviceOperationPanel" class="local-editor local-device-panel local-device-web-dialog device-operation-panel">
        <div class="local-editor-title">
          <div>
            <span class="label-chip">设备配置</span>
            <h3>{{ selectedDevice?.deviceName || '请选择设备' }}</h3>
            <p>{{ selectedDeviceId || '从设备管理列表选择设备' }} · {{ selectedDevice?.protocolType || selectedDevice?.connectionType || '-' }} · {{ deviceAddress(selectedDevice || {}) }}</p>
          </div>
          <div class="local-editor-title-actions">
            <div class="local-editor-stats">
              <div class="local-editor-stat"><strong>{{ selectedOperationStatus }}</strong><span>运行状态</span></div>
              <div class="local-editor-stat"><strong>{{ selectedDevice?.collectionInterval || '-' }}</strong><span>采集周期 ms</span></div>
              <div class="local-editor-stat"><strong>{{ selectedRealtimeRows.length }}</strong><span>实时点位</span></div>
            </div>
            <button type="button" @click="switchModule('device')">返回列表</button>
          </div>
        </div>

        <div class="local-editor-tabs" role="tablist" aria-label="设备操作工作台分区">
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'config' }" @click="workbenchTab = 'config'">
            <span>01</span><strong>工作台</strong><small>点位、实时和日志</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'control' }" @click="workbenchTab = 'control'">
            <span>02</span><strong>批量和协议命令</strong><small>单点、批量和协议命令</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'shadow' }" @click="workbenchTab = 'shadow'">
            <span>03</span><strong>desired、desired_delta</strong><small>reported、desired、delta</small>
          </button>
        </div>

        <div class="local-editor-layout">
          <aside class="local-editor-rail device-operation-rail">
            <div>
              <span class="label-chip">当前设备</span>
              <strong>{{ selectedDevice?.deviceName || selectedDeviceId || '未选择设备' }}</strong>
              <p>配置、控制和影子共用同一个设备上下文；切换分区不会丢失当前选择。</p>
            </div>
            <ol class="local-checklist device-info-list">
              <li :class="selectedDeviceId ? 'is-ok' : 'is-error'"><span>设备已选择</span><strong>{{ selectedDeviceId || '请先选择设备' }}</strong></li>
              <li :class="selectedOperationStatus === 'ONLINE' ? 'is-ok' : 'is-warn'"><span>运行状态</span><strong>{{ selectedOperationStatus }}</strong></li>
              <li :class="selectedRealtimeRows.length > 0 ? 'is-ok' : 'is-warn'"><span>实时点位</span><strong>{{ selectedRealtimeRows.length }} 个</strong></li>
              <li :class="selectedConnectionOk ? 'is-ok' : 'is-warn'"><span>连接状态</span><strong>{{ selectedConnectionText }}</strong></li>
            </ol>
            <div class="device-operation-rail-actions">
              <button type="button" :disabled="!selectedDeviceId || deviceConfigOperatingId === `refresh:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'refresh')">刷新配置</button>
              <button type="button" class="danger" :disabled="!selectedDeviceId || deviceConfigOperatingId === `clear:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'clear')">清理缓存</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceRuntimeStatus">运行状态</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceAlarmHistory">告警历史</button>
            </div>
          </aside>

          <div class="local-editor-body device-operation-body">
            <DeviceConfigPanel v-if="workbenchTab === 'config'" :device="selectedDeviceView" @start="startSelectedDevice" @stop="stopSelectedDevice" @open-history="openWorkbenchHistory" @open-realtime="openWorkbenchRealtime" />
            <ManualShadowPanels v-else :tab="workbenchTab" :device-id="selectedDeviceId" />
          </div>
        </div>
      </section>
    <input ref="configImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="handleConfigImportFile" />
    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocols" @saved="handleLocalSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import LegacyConfigOpsPanel from "./LegacyConfigOpsPanel.vue";
import LegacyHistoryPanel from "./LegacyHistoryPanel.vue";
import LocalDeviceEditor from "@/components/device/LocalDeviceEditor.vue";
import ManualShadowPanels from "./LegacyManualShadowPanels.vue";
import { clearDeviceConfig, deleteLocalDevice, exportConfigs, getConfigDevices as getConfigDeviceList, getConfigSummary, getDevicePointsConfig, getLocalDevice, importConfigs, refreshDeviceConfig, triggerFullConfigSync } from "@/api/config.api";
import { getDeviceRuntime, reloadDevices, startDevice, startLocalDevice, stopDevice } from "@/api/device.api";
import { getDeviceRealtimeData, resetAdaptiveConfig } from "@/api/data.api";
import { listProtocols } from "@/api/protocol.api";
import { resolveLegacyModuleByRoutePath, routePathForLegacyModule, type LegacyModuleKey } from "@/router/route-names";
import { useAppStore } from "@/stores/app.store";
import { normalizeDeviceViewModelWithRuntimeStatus, resolveDeviceStartMode } from "@/stores/device.store";
import { extractLocalDeviceBundle, type LocalDeviceBundle } from "@/components/device/local-device-utils";
import { buildConfigExportFilename, buildConfigImportRequest, buildDeviceListEmptyText, countConfigImportBundles, normalizeConfigExportText, parseConfigImportText } from "./config-utils";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "./device-config-actions-utils";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";
import type { ProtocolSchema } from "@/types/protocol";

type ModuleKey = LegacyModuleKey;

const appStore = useAppStore();
const route = useRoute();
const router = useRouter();
const activeModule = computed<ModuleKey>(() => {
  const module = resolveLegacyModuleByRoutePath(route.path);
  return module === "control" || module === "shadow" ? "workbench" : module;
});
const devices = ref<DeviceViewModel[]>([]);
const deviceRuntimeMap = ref<Record<string, DeviceRuntimeSnapshot>>({});
const protocols = ref<ProtocolSchema[]>([]);
const configSummary = ref<unknown>({});
const selectedRealtimeRows = ref<RealtimePointRow[]>([]);
const selectedDeviceId = ref("");
const historySelectedPointRef = ref("");
const deviceConfigOperatingId = ref("");
const deviceKeyword = ref("");
const protocolFilter = ref("");
const statusFilter = ref("");
const deviceLoading = ref(false);
const deviceLoadError = ref("");
const selectedProtocol = ref<ProtocolSchema | null>(null);
const localEditorVisible = ref(false);
const configImportInput = ref<HTMLInputElement | null>(null);
const configFileExporting = ref(false);
const configFileImporting = ref(false);
const editingBundle = ref<LocalDeviceBundle | null>(null);
const workbenchTab = ref<"config" | "control" | "shadow">("config");

const selectedDevice = computed(() => devices.value.find((device) => deviceIdOf(device) === selectedDeviceId.value));
const selectedDeviceView = computed(() => selectedDevice.value ? normalizeDeviceViewModelWithRuntimeStatus(selectedDevice.value, deviceRuntimeMap.value) : null);
const selectedRuntimeSnapshot = computed(() => selectedDeviceId.value ? (selectedDeviceView.value?.runtime || deviceRuntimeMap.value[selectedDeviceId.value]) : undefined);
const selectedConnectionOk = computed(() => Boolean(selectedRuntimeSnapshot.value?.connected || selectedRuntimeSnapshot.value?.running || selectedRealtimeRows.value.length > 0));
const selectedConnectionText = computed(() => selectedConnectionOk.value ? "正常" : "未知");
const selectedOperationStatus = computed(() => {
  const runtime = selectedRuntimeSnapshot.value;
  if (runtime?.running || runtime?.connected || selectedRealtimeRows.value.length > 0) {
    return "ONLINE";
  }
  return String(selectedDeviceView.value?.status || selectedDevice.value?.status || "未知");
});
const filteredDevices = computed(() => {
  const keyword = deviceKeyword.value.trim().toLowerCase();
  return devices.value.filter((device) => {
    const protocol = String(device.protocolType || device.connectionType || "");
    const status = String(device.status || "");
    const text = [device.deviceName, deviceIdOf(device), device.ipAddress, protocol, status].join(" ").toLowerCase();
    return (!keyword || text.includes(keyword)) && (!protocolFilter.value || protocol === protocolFilter.value) && (!statusFilter.value || status === statusFilter.value);
  });
});
const deviceListEmptyText = computed(() => buildDeviceListEmptyText({
  loading: deviceLoading.value,
  errorMessage: deviceLoadError.value,
  hasFilters: Boolean(deviceKeyword.value.trim() || protocolFilter.value || statusFilter.value)
}));
const collectionSummaryItems = computed(() => {
  const summary = asRecord(configSummary.value);
  const stats = asRecord(summary.cacheStats);
  return [
    { label: "设备配置", value: `${valueOf(stats, ["deviceCount"], valueOf(summary, ["deviceCount"], devices.value.length))} 台` },
    { label: "点位总数", value: `${valueOf(stats, ["pointCount"], valueOf(summary, ["pointCount"], sumPoints(devices.value)))} 个` },
    { label: "连接配置", value: `${valueOf(stats, ["connectionCount"], valueOf(summary, ["connectionCount"], devices.value.length))} 个` },
    { label: "配置来源", value: String(valueOf(summary, ["configSource", "source"], "当前运行配置")) }
  ];
});
onMounted(async () => {
  await appStore.initialize();
  syncWorkbenchTabFromRoute(route.path);
  await Promise.allSettled([loadProtocols(), loadDevices()]);
  await loadActiveLegacyModule(activeModule.value);
});

function switchModule(module: ModuleKey) {
  const targetPath = routePathForLegacyModule(module);
  if (route.path !== targetPath) {
    router.push(targetPath).catch(() => undefined);
  }
}

watch(() => route.path, (path) => {
  syncWorkbenchTabFromRoute(path);
});


watch(activeModule, (module) => {
  void loadActiveLegacyModule(module);
});

async function loadActiveLegacyModule(module: ModuleKey) {
  if (module === "collect") await loadConfigSummary();
  if (module === "workbench") await loadSelectedRealtime();
}

function syncWorkbenchTabFromRoute(path: string) {
  const module = resolveLegacyModuleByRoutePath(path);
  if (module === "control" || module === "shadow") {
    workbenchTab.value = module;
    return;
  }
  if (module === "workbench") {
    workbenchTab.value = "config";
  }
}

async function refreshAll() {
  await Promise.allSettled([loadProtocols(), loadDevices(), loadConfigSummary()]);
}

async function loadProtocols() {
  protocols.value = await listProtocols();
}

async function loadDevices() {
  deviceLoading.value = true;
  deviceLoadError.value = "";
  try {
    const [deviceResponse, runtimeResponse] = await Promise.allSettled([getConfigDeviceList(), getDeviceRuntime()]);
    if (runtimeResponse.status === "fulfilled") {
      const snapshots = extractArray<DeviceRuntimeSnapshot>(runtimeResponse.value, ["data", "items", "records"]);
      deviceRuntimeMap.value = Object.fromEntries(snapshots.map((item) => [item.deviceId, item]).filter(([deviceId]) => Boolean(deviceId)));
    }
    if (deviceResponse.status !== "fulfilled") {
      throw deviceResponse.reason;
    }
    devices.value = extractArray<DeviceInfo>(deviceResponse.value, ["devices", "data", "items", "records"])
      .map((device) => normalizeDeviceViewModelWithRuntimeStatus(device, deviceRuntimeMap.value));
    if (!selectedDeviceId.value && devices.value.length) selectedDeviceId.value = deviceIdOf(devices.value[0]);
  } catch (error) {
    deviceLoadError.value = error instanceof Error ? error.message : "设备配置加载失败";
    throw error;
  } finally {
    deviceLoading.value = false;
  }
}

async function loadConfigSummary() {
  try {
    configSummary.value = await getConfigSummary();
  } catch {
    configSummary.value = {};
  }
}

async function loadSelectedRealtime() {
  if (!selectedDeviceId.value) return;
  const response = await getDeviceRealtimeData(selectedDeviceId.value);
  selectedRealtimeRows.value = normalizeRealtimeRows(response, selectedDeviceId.value);
}

async function resetSelectedAdaptive() {
  if (!selectedDeviceId.value) return;
  await resetAdaptiveConfig(selectedDeviceId.value);
  ElMessage.success("已重置自适应采集参数");
  await loadSelectedRealtime();
}

async function syncDevices() {
  await triggerFullConfigSync();
  await reloadDevices();
  await loadDevices();
  ElMessage.success("已触发远端配置同步");
}

async function startSelectedDevice(deviceId: string) {
  const device = devices.value.find((item) => deviceIdOf(item) === deviceId);
  const startAction = resolveDeviceStartMode(device) === "local" ? startLocalDevice : startDevice;
  await startAction(deviceId);
  await loadDevices();
  await loadSelectedRealtime();
}
async function stopSelectedDevice(deviceId: string) { await stopDevice(deviceId); await loadDevices(); await loadSelectedRealtime(); }
async function deleteLocal(deviceId: string) {
  try {
    await ElMessageBox.confirm(`确认删除本地临时设备 ${deviceId}？该操作不会删除远端配置。`, "删除本地设备", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  await deleteLocalDevice(deviceId);
  await loadDevices();
}

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
    await loadDevices();
    if (selectedDeviceId.value === deviceId) {
      await loadSelectedRealtime();
    }
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

function selectDevice(deviceId: string) { selectedDeviceId.value = deviceId; void loadSelectedRealtime(); }
function openLocalEditor() { editingBundle.value = null; localEditorVisible.value = true; }
async function handleLocalSaved() { localEditorVisible.value = false; await loadDevices(); }

async function exportDeviceConfigData() {
  configFileExporting.value = true;
  try {
    const exportText = normalizeConfigExportText(await exportConfigs());
    const blob = new Blob([exportText], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = buildConfigExportFilename();
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("设备配置数据已导出，可用于点位测试环境导入");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置数据导出失败");
  } finally {
    configFileExporting.value = false;
  }
}

function openConfigImportFile() {
  configImportInput.value?.click();
}

async function handleConfigImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) {
    return;
  }
  if (!file.name.toLowerCase().endsWith(".json")) {
    ElMessage.warning("请选择 JSON 配置文件");
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning("配置文件不能超过 5MB");
    return;
  }
  configFileImporting.value = true;
  try {
    const parsed = parseConfigImportText(await file.text());
    const bundleCount = countConfigImportBundles(parsed);
    if (bundleCount === 0) {
      throw new Error("导入配置包 bundles 不能为空");
    }
    try {
      await ElMessageBox.confirm(`将导入 ${bundleCount} 个设备配置包并刷新设备，请确认当前本地测试配置可被覆盖。`, "导入设备配置数据", {
        confirmButtonText: "确认导入",
        cancelButtonText: "取消",
        type: "warning"
      });
    } catch {
      return;
    }
    await importConfigs(buildConfigImportRequest(parsed, true));
    await refreshAll();
    ElMessage.success(`已导入 ${bundleCount} 个设备配置包`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置数据导入失败");
  } finally {
    configFileImporting.value = false;
  }
}
function isLocalDevice(device: DeviceInfo): boolean {
  return Boolean(device.temporaryConfig || device.configSource === "local" || device.configSource === "LOCAL" || asRecord(device).localDevice);
}

function deviceAddress(device: DeviceInfo): string {
  return [device.ipAddress, device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":") || "-";
}

function localizeDeviceStatus(status: unknown): string {
  switch (String(status || "UNKNOWN").toUpperCase()) {
    case "ONLINE":
    case "RUNNING":
      return "在线";
    case "OFFLINE":
      return "离线";
    case "ERROR":
      return "异常";
    case "STOPPED":
      return "已停止";
    default:
      return "未知";
  }
}

function statusBadgeClass(device: DeviceInfo): string {
  const status = String(device.status || "UNKNOWN").toUpperCase();
  if (status === "ONLINE" || status === "RUNNING") return "is-online";
  if (status === "ERROR") return "is-error";
  return "";
}

async function editDevice(device: DeviceInfo) {
  if (isLocalDevice(device)) {
    const deviceId = deviceIdOf(device);
    selectDevice(deviceId);
    try {
      const detail = await getLocalDevice(deviceId);
      const bundle = extractLocalDeviceBundle(detail);
      if (!bundle) {
        throw new Error("本地设备详情缺少可编辑配置");
      }
      editingBundle.value = bundle;
      localEditorVisible.value = true;
    } catch (caught) {
      ElMessage.error(caught instanceof Error ? caught.message : "本地设备详情加载失败");
    }
    return;
  }
  selectDevice(deviceIdOf(device));
  workbenchTab.value = "config";
  router.push("/device/workbench").catch(() => undefined);
}

function openDeviceDiff(device: DeviceInfo) {
  selectDevice(deviceIdOf(device));
  switchModule("collect");
  ElMessage.info("已切换到采集配置，可查看当前设备相关配置");
}

function openDeviceAlarmHistory(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  router.push({ path: "/alarm", query: { deviceId } }).catch(() => undefined);
}

function openDeviceRuntimeStatus(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  router.push({ path: "/diagnostic", query: { deviceId } }).catch(() => undefined);
  ElMessage.info("已切换到运行设备状态面板");
}

function openSelectedDeviceRuntimeStatus() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceRuntimeStatus(selectedDevice.value);
}

function openSelectedDeviceAlarmHistory() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceAlarmHistory(selectedDevice.value);
}

function openWorkbenchHistory(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  selectDevice(target.deviceId);
  historySelectedPointRef.value = target.pointRef;
  switchModule("history");
  ElMessage.info(`已切换到历史趋势：${target.pointLabel || target.pointName || target.pointRef}`);
}

function openWorkbenchRealtime(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  router.push({ path: "/realtime", query: { deviceId: target.deviceId, pointId: target.pointRef } }).catch(() => undefined);
  ElMessage.info(`已切换到实时数据：${target.pointLabel || target.pointName || target.pointRef}`);
}

function openDeviceOperation(device: DeviceInfo, tab: "config" | "control" | "shadow") {
  selectDevice(deviceIdOf(device));
  workbenchTab.value = tab;
  router.push(tab === "control" ? "/control" : (tab === "shadow" ? "/shadow" : "/device/workbench")).catch(() => undefined);
}

function protocolDefaultPort(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  const fields = Array.isArray(record.connectionFields) ? record.connectionFields.map((item) => asRecord(item)) : [];
  const portField = fields.find((field) => field.name === "port");
  return String(valueOf(record, ["defaultPort"], valueOf(portField || {}, ["defaultValue"], "-")));
}

function protocolMode(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  return String(valueOf(record, ["collectionMode", "triggerMode", "addressingMode", "collectorType"], "轮询/协议驱动"));
}

function protocolCapability(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  return String(valueOf(record, ["implementationStatus", "status", "implementationState"], protocol.implemented === false ? "未实现" : "已接入"));
}

function openProtocolConfig(protocol: ProtocolSchema) {
  selectedProtocol.value = protocol;
  localEditorVisible.value = true;
}


function deviceIdOf(device: DeviceInfo): string { return String(device.deviceId || device.id || ""); }
function sumPoints(source: DeviceInfo[]): number { return source.reduce((sum, device) => sum + Number(device.pointCount || (Array.isArray(device.points) ? device.points.length : 0) || 0), 0); }
function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function extractArray<T>(value: unknown, keys: string[]): T[] { if (Array.isArray(value)) return value as T[]; const record = asRecord(value); for (const key of keys) if (Array.isArray(record[key])) return record[key] as T[]; return []; }
function valueOf(value: unknown, keys: string[], fallback: unknown): unknown { const record = asRecord(value); for (const key of keys) if (record[key] !== undefined && record[key] !== null) return record[key]; return fallback; }
function prettyJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
</script>

<style scoped>
.legacy-page-host {
  display: contents;
}
</style>
