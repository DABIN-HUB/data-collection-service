<template>
  <section class="exact-page device-list-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>设备管理</h1>
        <span class="heading-online"><i></i>{{ filteredDevices.length }} 台设备</span>
      </div>
      <div class="heading-actions">
        <button type="button" @click="refreshDeviceListContext">刷新列表</button>
        <button type="button" :disabled="configFileExporting" @click="exportDeviceConfigData">导出配置数据</button>
        <button type="button" :disabled="configFileImporting" @click="openConfigImportFile">导入配置数据</button>
        <button type="button" class="primary" @click="openLocalEditor">新增本地设备</button>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <input v-model="deviceKeyword" type="search" placeholder="搜索设备名称、标识或地址" />
          <select v-model="protocolFilter">
            <option value="">全部协议</option>
            <option v-for="protocolItem in protocolStore.protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">
              {{ protocolItem.title || protocolItem.protocol }}
            </option>
          </select>
          <select v-model="statusFilter">
            <option value="">全部状态</option>
            <option value="ONLINE">在线</option>
            <option value="OFFLINE">离线</option>
            <option value="ERROR">异常</option>
          </select>
        </div>
        <div class="exact-toolbar-group">
          <button type="button" :disabled="deviceStore.operating" @click="syncDevices">同步远端配置</button>
        </div>
      </div>

      <div class="exact-device-list">
        <div v-if="filteredDevices.length === 0" class="exact-empty">{{ deviceListEmptyText }}</div>
        <article
          v-for="device in filteredDevices"
          :key="device.normalizedId"
          class="exact-device-card"
          :class="{ 'is-selected': deviceStore.selectedDeviceId === device.normalizedId }"
          @click="selectDevice(device.normalizedId)"
        >
          <div class="exact-device-main">
            <h3>{{ device.displayName || device.normalizedId }}</h3>
            <p>{{ device.normalizedId }} · {{ isLocalDevice(device) ? '本地临时' : '远端同步' }}</p>
          </div>
          <div class="exact-device-meta">
            <strong>{{ device.displayProtocol || '-' }}</strong>
            <span>连接地址 {{ deviceAddress(device) }}</span>
          </div>
          <div class="exact-device-meta">
            <span class="status-badge" :class="statusBadgeClass(device)">{{ localizeDeviceStatus(device.status) }}</span>
            <span>采集周期 {{ device.collectionInterval ?? '-' }} ms</span>
          </div>
          <div class="exact-device-actions">
            <button type="button" :disabled="deviceStore.operating" @click.stop="startSelectedDevice(device.normalizedId)">启动</button>
            <button type="button" :disabled="deviceStore.operating" @click.stop="stopSelectedDevice(device.normalizedId)">停止</button>
            <button type="button" :disabled="deviceConfigOperatingId === `refresh:${device.normalizedId}`" @click.stop="operateDeviceConfig(device.normalizedId, 'refresh')">刷新配置</button>
            <button type="button" class="danger" :disabled="deviceConfigOperatingId === `clear:${device.normalizedId}`" @click.stop="operateDeviceConfig(device.normalizedId, 'clear')">清理缓存</button>
            <button type="button" @click.stop="openDeviceOperation(device, 'config')">配置</button>
            <button type="button" @click.stop="editDevice(device)">编辑</button>
            <button type="button" @click.stop="openDeviceDiff(device)">差异</button>
            <button type="button" @click.stop="openDeviceRuntimeStatus(device)">运行状态</button>
            <button type="button" @click.stop="openDeviceAlarmHistory(device)">告警历史</button>
            <button type="button" @click.stop="openDeviceOperation(device, 'control')">控制</button>
            <button type="button" @click.stop="openDeviceOperation(device, 'shadow')">影子</button>
            <button v-if="isLocalDevice(device)" type="button" class="danger" @click.stop="deleteLocal(device.normalizedId)">删除本地</button>
          </div>
        </article>
      </div>
    </div>

    <input ref="configImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="handleConfigImportFile" />
    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocolStore.protocols" @saved="handleLocalSaved" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { clearDeviceConfig, exportConfigs, getLocalDevice, importConfigs, refreshDeviceConfig } from "@/api/config.api";
import LocalDeviceEditor from "@/components/device/LocalDeviceEditor.vue";
import { extractLocalDeviceBundle, type LocalDeviceBundle } from "@/components/device/local-device-utils";
import { buildConfigExportFilename, buildConfigImportRequest, countConfigImportBundles, normalizeConfigExportText, parseConfigImportText } from "@/features/config/utils/config-transfer-utils";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { buildDeviceListEmptyText } from "@/features/device/utils/device-list-utils";
import { useAppStore } from "@/stores/app.store";
import { isLocalDevice, useDeviceStore } from "@/stores/device.store";
import { useProtocolStore } from "@/stores/protocol.store";
import type { DeviceViewModel } from "@/types/device";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const protocolStore = useProtocolStore();
const route = useRoute();
const router = useRouter();

const deviceKeyword = ref("");
const protocolFilter = ref("");
const statusFilter = ref("");
const localEditorVisible = ref(false);
const editingBundle = ref<LocalDeviceBundle | null>(null);
const configImportInput = ref<HTMLInputElement | null>(null);
const configFileExporting = ref(false);
const configFileImporting = ref(false);
const deviceConfigOperatingId = ref("");

const filteredDevices = computed(() => {
  const keyword = deviceKeyword.value.trim().toLowerCase();
  return deviceStore.devices.filter((device) => {
    const protocol = String(device.displayProtocol || device.protocolType || device.connectionType || "");
    const status = String(device.status || device["runtimeStatus"] || "").toUpperCase();
    const text = [device.displayName, device.normalizedId, device.deviceId, device.id, device.ipAddress, device["host"], protocol, status, deviceAddress(device)]
      .join(" ")
      .toLowerCase();
    return (!keyword || text.includes(keyword)) && (!protocolFilter.value || protocol === protocolFilter.value) && (!statusFilter.value || status === statusFilter.value);
  });
});

const deviceListEmptyText = computed(() => buildDeviceListEmptyText({
  loading: deviceStore.loading,
  errorMessage: deviceStore.error,
  hasFilters: Boolean(deviceKeyword.value.trim() || protocolFilter.value || statusFilter.value)
}));

onMounted(async () => {
  await appStore.initialize();
  await refreshDeviceListContext();
});

watch(() => route.query.deviceId, () => {
  applyRouteDeviceContext();
});

async function refreshDeviceListContext() {
  await Promise.allSettled([deviceStore.refresh(), protocolStore.refresh()]);
  applyRouteDeviceContext();
}

function selectDevice(deviceId: string) {
  deviceStore.selectDevice(deviceId);
}

function applyRouteDeviceContext() {
  const deviceId = routeDeviceId();
  if (!deviceId) {
    ensureSelectedDevice();
    return;
  }
  if (deviceStore.devices.some((device) => device.normalizedId === deviceId)) {
    deviceStore.selectDevice(deviceId);
  }
}

function ensureSelectedDevice() {
  if (deviceStore.selectedDevice) {
    return;
  }
  const firstDeviceId = deviceStore.devices[0]?.normalizedId || "";
  if (firstDeviceId) {
    deviceStore.selectDevice(firstDeviceId);
  }
}

async function syncDevices() {
  await deviceStore.syncRemoteDevices();
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  applyRouteDeviceContext();
  ElMessage.success("已触发远端配置同步并重新加载设备列表");
}

async function startSelectedDevice(deviceId: string) {
  await deviceStore.startSmart(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  ElMessage.success("已请求启动设备");
}

async function stopSelectedDevice(deviceId: string) {
  await deviceStore.stop(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  ElMessage.success("已请求停止设备");
}

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
  await deviceStore.deleteLocal(deviceId);
  if (deviceStore.error) {
    ElMessage.error(deviceStore.error);
    return;
  }
  applyRouteDeviceContext();
  ElMessage.success("本地临时设备已删除");
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
    await deviceStore.refresh();
    applyRouteDeviceContext();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置操作失败");
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

function openLocalEditor() {
  editingBundle.value = null;
  localEditorVisible.value = true;
}

async function handleLocalSaved() {
  localEditorVisible.value = false;
  await refreshDeviceListContext();
}

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
    await refreshDeviceListContext();
    ElMessage.success(`已导入 ${bundleCount} 个设备配置包`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置数据导入失败");
  } finally {
    configFileImporting.value = false;
  }
}

async function editDevice(device: DeviceViewModel) {
  selectDevice(device.normalizedId);
  if (isLocalDevice(device)) {
    try {
      const detail = await getLocalDevice(device.normalizedId);
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
  openDeviceOperation(device, "config");
}

function openDeviceDiff(device: DeviceViewModel) {
  selectDevice(device.normalizedId);
  router.push({ path: "/collect", query: { deviceId: device.normalizedId } }).catch(() => undefined);
  ElMessage.info("已切换到采集配置，可查看当前设备相关配置");
}

function openDeviceRuntimeStatus(device: DeviceViewModel) {
  selectDevice(device.normalizedId);
  router.push({ path: "/diagnostic", query: { deviceId: device.normalizedId } }).catch(() => undefined);
  ElMessage.info("已切换到运行设备状态面板");
}

function openDeviceAlarmHistory(device: DeviceViewModel) {
  selectDevice(device.normalizedId);
  router.push({ path: "/alarm", query: { deviceId: device.normalizedId } }).catch(() => undefined);
}

function openDeviceOperation(device: DeviceViewModel, tab: "config" | "control" | "shadow") {
  selectDevice(device.normalizedId);
  const path = tab === "control" ? "/control" : (tab === "shadow" ? "/shadow" : "/device/workbench");
  router.push({ path, query: { deviceId: device.normalizedId } }).catch(() => undefined);
}

function deviceAddress(device: DeviceViewModel): string {
  const host = device.ipAddress || device["host"] || device["url"];
  return [host, device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":") || "-";
}

function localizeDeviceStatus(status: unknown): string {
  switch (String(status || "UNKNOWN").toUpperCase()) {
    case "ONLINE":
    case "RUNNING":
      return "在线";
    case "CONNECTING":
      return "连接中";
    case "OFFLINE":
      return "离线";
    case "ERROR":
      return "异常";
    case "STOPPED":
      return "已停止";
    case "DISABLED":
      return "已停用";
    default:
      return "未知";
  }
}

function statusBadgeClass(device: DeviceViewModel): string {
  const status = String(device.status || device["runtimeStatus"] || "UNKNOWN").toUpperCase();
  if (status === "ONLINE" || status === "RUNNING") return "is-online";
  if (status === "ERROR") return "is-error";
  return "";
}

function routeDeviceId(): string {
  const value = route.query.deviceId;
  if (Array.isArray(value)) {
    return String(value[0] || "");
  }
  return String(value || "");
}
</script>

<style scoped>
.exact-device-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.exact-device-card {
  display: grid;
  min-height: 110px;
  padding: 17px 18px;
  grid-template-columns: minmax(220px, 1.3fr) minmax(230px, 1fr) minmax(220px, 1fr) auto;
  align-items: center;
  gap: 20px;
  color: #e2e8f0;
  border: 1px solid var(--exact-border);
  border-radius: 12px;
  background: var(--exact-panel);
  cursor: pointer;
}

.exact-device-card:hover,
.exact-device-card.is-selected {
  border-color: var(--exact-blue);
  box-shadow: 0 0 15px rgba(59, 130, 246, 0.15);
}

.exact-device-main h3,
.exact-device-main p {
  margin: 0;
}

.exact-device-main h3 {
  color: #fff;
  font-size: 15px;
}

.exact-device-main p,
.exact-device-meta span {
  margin-top: 7px;
  color: var(--exact-dim);
  font-size: 11px;
}

.exact-device-meta {
  display: flex;
  flex-direction: column;
}

.exact-device-meta strong {
  display: block;
  margin: 0;
  color: #fff;
  font-size: 15px;
}

.exact-device-actions {
  display: flex;
  justify-content: flex-end;
  gap: 7px;
  flex-wrap: wrap;
}

.exact-device-actions button {
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid var(--exact-border);
  border-radius: 5px;
  background: var(--exact-panel-soft);
  font-size: 11px;
}

.exact-device-actions button:hover {
  border-color: var(--exact-blue);
}

@media (max-width: 1100px) {
  .exact-device-card {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
