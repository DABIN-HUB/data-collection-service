<template>
  <section class="exact-page device-list-view">
    <header class="device-page-heading">
      <div class="device-title-block">
        <div class="device-title-line">
          <h1>设备管理</h1>
          <span class="device-count" aria-live="polite">{{ deviceInventoryLabel }}</span>
        </div>
        <p class="device-heading-sub">管理本地临时设备与远端同步配置</p>
      </div>
      <div class="device-heading-actions">
        <button type="button" class="device-button" @click="refreshDeviceListContext"><DeviceListIcon name="refresh" />刷新列表</button>
        <button type="button" class="device-button" :disabled="configFileExporting" @click="exportDeviceConfigData"><DeviceListIcon name="download" />导出配置数据</button>
        <button type="button" class="device-button" :disabled="configFileImporting" @click="openConfigImportFile"><DeviceListIcon name="upload" />导入配置数据</button>
        <button type="button" class="device-button primary" @click="openLocalEditor"><DeviceListIcon name="plus" />新增本地设备</button>
      </div>
    </header>

    <div class="device-toolbar">
      <div class="device-filters">
        <label class="device-search">
          <DeviceListIcon name="search" />
          <input v-model="deviceKeyword" type="search" aria-label="搜索设备名称、标识或地址" placeholder="搜索设备名称、标识或地址" />
        </label>
        <select v-model="protocolFilter" class="device-protocol-filter" aria-label="协议筛选">
          <option value="">全部协议</option>
          <option v-for="protocolItem in protocolStore.protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">
            {{ protocolItem.title || protocolItem.protocol }}
          </option>
        </select>
        <select v-model="statusFilter" class="device-status-filter" aria-label="状态筛选">
          <option value="">全部状态</option>
          <option value="ONLINE">在线</option>
          <option value="OFFLINE">离线</option>
          <option value="ERROR">异常</option>
          <option value="UNKNOWN">未知</option>
          <option value="STALE">过期</option>
          <option value="CONNECTING">连接中/等待首采</option>
        </select>
      </div>
      <button type="button" class="device-button" :disabled="deviceStore.syncOperating" @click="syncDevices"><DeviceListIcon name="refresh" />同步远端配置</button>
    </div>

    <div v-if="detailError || deviceStore.error || deviceStore.syncError" class="device-error-banner" role="alert">
      <DeviceListIcon name="alert" /><span>{{ detailError || deviceStore.error || deviceStore.syncError }}</span>
    </div>
    <div class="device-register">
      <div class="device-register-head" aria-hidden="true">
        <span>设备身份 / 来源</span><span>连接与采集</span><span>运行阶段 / 通信状态</span><span>采集健康 / 有效样本</span><span>操作</span>
      </div>
      <div class="device-register-body" @scroll="closeDeviceMenu">
        <template v-if="filteredDevices.length === 0 && deviceStore.loading && !deviceStore.error">
          <div class="device-loading-caption" role="status">{{ deviceListEmptyText }}</div>
          <div v-for="row in 4" :key="row" class="device-skeleton-row" aria-hidden="true">
            <div v-for="column in 5" :key="column"><i class="device-loading-line"></i><i class="device-loading-line"></i><i class="device-loading-line"></i></div>
          </div>
        </template>
        <div v-else-if="filteredDevices.length === 0" class="device-empty">
          <div class="device-empty-illustration"><DeviceListIcon :name="deviceStore.error ? 'alert' : hasDeviceFilters ? 'search' : 'box'" /></div>
          <h2>{{ deviceEmptyTitle }}</h2>
          <p v-if="deviceListEmptyText !== deviceEmptyTitle">{{ deviceListEmptyText }}</p>
          <p v-if="!deviceStore.error && hasDeviceFilters">请调整上方关键词、协议或状态筛选。</p>
        </div>
        <article
          v-for="device in filteredDevices"
          :key="device.normalizedId"
          class="device-row"
          :class="{ 'is-selected': deviceStore.selectedDeviceId === device.normalizedId }"
          :data-device-id="device.normalizedId"
          :aria-label="`选择设备 ${device.displayName || device.normalizedId}`"
          :aria-current="deviceStore.selectedDeviceId === device.normalizedId ? 'true' : undefined"
          tabindex="0"
          @click="selectDevice(device.normalizedId)"
          @keydown.enter.self.prevent="selectDevice(device.normalizedId)"
          @keydown.space.self.prevent="selectDevice(device.normalizedId)"
        >
          <div class="device-cell device-identity">
            <span class="device-cell-label">设备身份 / 来源</span>
            <h2>{{ device.displayName || device.normalizedId }}</h2>
            <span class="device-id device-mono">{{ device.normalizedId }}</span>
            <span class="device-source" :class="{ 'is-local': isLocalDevice(device) }"><DeviceListIcon :name="isLocalDevice(device) ? 'local' : 'cloud'" />{{ isLocalDevice(device) ? '本地临时' : '远端同步' }}</span>
          </div>
          <div class="device-cell device-connection">
            <span class="device-cell-label">连接与采集</span>
            <strong class="device-protocol-name">{{ device.displayProtocol || '-' }}</strong>
            <span class="device-address device-mono" :title="deviceAddress(device)">{{ deviceAddress(device) }}</span>
            <div class="device-interval">采集周期 <span class="device-mono">{{ device.collectionInterval ?? '-' }}</span> ms</div>
          </div>
          <div class="device-cell device-lifecycle">
            <span class="device-cell-label">运行阶段 / 通信状态</span>
            <span class="device-phase" :class="[statusBadgeClass(device), devicePresentation(device).phaseTone]"><i class="device-dot"></i>{{ devicePresentation(device).lifecycle }}</span>
            <div class="device-transport"><span>{{ devicePresentation(device).transportText }}</span><span>{{ devicePresentation(device).protocolText }}</span></div>
          </div>
          <div class="device-cell device-quality">
            <span class="device-cell-label">采集健康 / 有效样本</span>
            <span class="device-health" :class="devicePresentation(device).healthTone"><i class="device-dot"></i>{{ devicePresentation(device).health }}</span>
            <div class="device-counts">
              <span>有效 <b class="device-mono">{{ devicePresentation(device).good }}/{{ devicePresentation(device).total }}</b></span>
              <span>失败 <b class="device-mono">{{ devicePresentation(device).failed }}</b></span>
              <span>过期 <b class="device-mono">{{ devicePresentation(device).stale }}</b></span>
              <span>等待 <b class="device-mono">{{ devicePresentation(device).waiting }}</b></span>
            </div>
            <div class="device-last-valid">最近有效 <span class="device-mono">{{ devicePresentation(device).lastValid }}</span></div>
          </div>
          <div class="device-cell device-actions">
            <button type="button" class="device-button subtle-primary" @click.stop="openDeviceOperation(device, 'config')">配置</button>
            <button type="button" class="device-button" @click.stop="editDevice(device)">编辑</button>
            <ElPopover :visible="activeMenuDeviceId === device.normalizedId" placement="bottom-end" :width="262" :offset="7" :show-arrow="false" :popper-style="deviceMenuPopoverStyle" :persistent="false" :hide-after="0">
              <template #reference>
                <button type="button" class="device-button device-more" :aria-label="`${device.displayName || device.normalizedId}更多操作`" aria-haspopup="menu" :aria-expanded="activeMenuDeviceId === device.normalizedId" @click.stop="toggleDeviceMenu(device.normalizedId)"><DeviceListIcon name="more" /></button>
              </template>
              <div class="device-action-menu" role="menu" :aria-label="`${device.displayName || device.normalizedId}操作`" @click.capture="closeDeviceMenu">
                <div class="device-menu-title">{{ device.displayName || device.normalizedId }}</div>
                <div class="device-menu-group">
                  <span class="device-menu-group-label">监测与配置</span>
                  <button type="button" class="device-menu-item" role="menuitem" @click.stop="openDeviceRuntimeStatus(device)"><DeviceListIcon name="chart" />运行状态</button>
                  <button type="button" class="device-menu-item" role="menuitem" @click.stop="openDeviceAlarmHistory(device)"><DeviceListIcon name="alert" />告警历史</button>
                  <button type="button" class="device-menu-item" role="menuitem" @click.stop="openDeviceDiff(device)"><DeviceListIcon name="diff" />差异<span class="device-menu-hint">前往采集配置</span></button>
                </div>
                <div class="device-menu-group">
                  <span class="device-menu-group-label">设备工作台</span>
                  <div class="device-menu-grid">
                    <button type="button" class="device-menu-item" role="menuitem" @click.stop="openDeviceOperation(device, 'control')"><DeviceListIcon name="control" />控制</button>
                    <button type="button" class="device-menu-item" role="menuitem" @click.stop="openDeviceOperation(device, 'shadow')"><DeviceListIcon name="shadow" />影子</button>
                  </div>
                </div>
                <div class="device-menu-group">
                  <span class="device-menu-group-label">运行与维护</span>
                  <div class="device-menu-grid">
                    <button type="button" class="device-menu-item" role="menuitem" :disabled="deviceStore.isDeviceOperating(device.normalizedId)" @click.stop="startSelectedDevice(device.normalizedId)"><DeviceListIcon name="play" />启动</button>
                    <button type="button" class="device-menu-item" role="menuitem" :disabled="deviceStore.isDeviceOperating(device.normalizedId)" @click.stop="stopSelectedDevice(device.normalizedId)"><DeviceListIcon name="stop" />停止</button>
                  </div>
                  <button type="button" class="device-menu-item" role="menuitem" :disabled="deviceStore.isDeviceOperating(device.normalizedId)" @click.stop="operateDeviceConfig(device.normalizedId, 'refresh')"><DeviceListIcon name="refresh" />刷新配置<span class="device-menu-hint">确认后执行</span></button>
                  <button type="button" class="device-menu-item is-danger" role="menuitem" :disabled="deviceStore.isDeviceOperating(device.normalizedId)" @click.stop="operateDeviceConfig(device.normalizedId, 'clear')"><DeviceListIcon name="trash" />清理缓存<span class="device-menu-hint">仅配置缓存</span></button>
                  <button v-if="isLocalDevice(device)" type="button" class="device-menu-item is-danger" role="menuitem" :disabled="deviceStore.isDeviceOperating(device.normalizedId)" @click.stop="deleteLocal(device.normalizedId)"><DeviceListIcon name="trash" />删除本地<span class="device-menu-hint">仅本地配置</span></button>
                </div>
              </div>
            </ElPopover>
          </div>
          <div v-if="device.runtimeError || devicePresentation(device).reason || (device.runtimeStale && device.runtime)" class="device-row-notice" :class="device.runtimeStale ? 'is-stale' : statusBadgeClass(device)">
            <DeviceListIcon name="alert" /><span>{{ device.runtimeError || devicePresentation(device).reason }}<template v-if="device.runtimeStale && device.runtime"> 点位计数与最近有效时间来自旧快照，不表示当前健康。</template></span>
          </div>
          <div v-if="deviceStore.deviceErrors[device.normalizedId]" class="device-row-notice is-error" role="alert"><DeviceListIcon name="alert" /><span>{{ deviceStore.deviceErrors[device.normalizedId] }}</span></div>
          <div v-if="deviceStore.isDeviceOperating(device.normalizedId)" class="device-row-notice is-busy" role="status"><DeviceListIcon name="info" /><span>设备操作进行中；启动、停止及配置缓存操作暂不可重复提交。</span></div>
        </article>
      </div>
      <footer class="device-register-footer"><span>{{ deviceResultLabel }}</span><span>点击设备行选择设备；更多菜单包含监测与维护操作</span></footer>
    </div>
    <div class="device-semantic-note"><DeviceListIcon name="info" /><span>运行阶段、通信就绪与采集健康是独立状态。设备已连接或启动请求已受理，不代表当前点位有效。</span></div>

    <input ref="configImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="handleConfigImportFile" />
    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocolStore.protocols" @saved="handleLocalSaved" />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox, ElPopover } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { clearDeviceConfig, exportConfigs, getLocalDevice, importConfigs, refreshDeviceConfig } from "@/api/config.api";
import LocalDeviceEditor from "@/features/device/components/LocalDeviceEditor.vue";
import DeviceListIcon from "@/features/device/components/DeviceListIcon.vue";
import { deviceListPresentation } from "@/features/device/utils/device-list-presentation";
import { extractLocalDeviceBundle, type LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import { buildConfigExportFilename, buildConfigImportRequest, countConfigImportBundles, normalizeConfigExportText, parseConfigImportText } from "@/features/config/utils/config-transfer-utils";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "@/features/device/utils/device-config-actions-utils";
import { buildDeviceListEmptyText } from "@/features/device/utils/device-list-utils";
import { useAppStore } from "@/stores/app.store";
import { runtimeOperationMessage } from "@/features/diagnostic/utils/device-runtime-utils";
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
const detailError = ref("");
let editorSession = 0;
onBeforeUnmount(() => { ++editorSession; });

watch(localEditorVisible, (visible) => { if (!visible) ++editorSession; });
watch(() => deviceStore.selectedDeviceId, () => { ++editorSession; }, { flush: "sync" });

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
  ++editorSession;
  applyRouteDeviceContext();
});

async function refreshDeviceListContext() {
  await Promise.allSettled([deviceStore.refresh(), protocolStore.refresh()]);
  applyRouteDeviceContext();
}

function selectDevice(deviceId: string) {
  ++editorSession;
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
  const result = await deviceStore.syncRemoteDevices();
  if (!result.ok) {
    ElMessage.error(result.error || "远端配置同步失败");
    return;
  }
  applyRouteDeviceContext();
  if (result.refreshError) ElMessage.warning(`远端同步已触发，列表暂不可用：${result.refreshError}`);
  else ElMessage.success("已触发远端配置同步并刷新设备列表");
}

async function startSelectedDevice(deviceId: string) {
  const result = await deviceStore.startSmart(deviceId);
  if (!result.ok) {
    ElMessage.error(`设备 ${deviceId}：${result.error}`);
    return;
  }
  if (result.refreshError) ElMessage.warning(`设备 ${deviceId} 操作已受理，运行状态暂不可用：${result.refreshError}`);
  else ElMessage.success(`设备 ${deviceId}：${runtimeOperationMessage(result.runtime, "START")}`);
}

async function stopSelectedDevice(deviceId: string) {
  const result = await deviceStore.stop(deviceId);
  if (!result.ok) {
    ElMessage.error(`设备 ${deviceId}：${result.error}`);
    return;
  }
  if (result.refreshError) ElMessage.warning(`设备 ${deviceId} 操作已受理，运行状态暂不可用：${result.refreshError}`);
  else ElMessage.success(`设备 ${deviceId}：${runtimeOperationMessage(result.runtime, "STOP")}`);
}

async function deleteLocal(deviceId: string) {
  try {
    await ElMessageBox.confirm(`确认删除本地临时设备 ${deviceId}？该操作不会删除远端配置。`, "删除本地设备", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      customClass: "device-list-confirmation",
      customStyle: deviceConfirmationStyle,
      confirmButtonType: "danger",
      type: "warning"
    });
  } catch {
    return;
  }
  const result = await deviceStore.deleteLocal(deviceId);
  if (!result.ok) {
    ElMessage.error(result.error || "本地设备删除失败");
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
      customClass: "device-list-confirmation",
      customStyle: deviceConfirmationStyle,
      type: "warning"
    });
  } catch {
    return;
  }
  const operation = await deviceStore.operate(() => type === "clear" ? clearDeviceConfig(deviceId) : refreshDeviceConfig(deviceId), deviceId);
  if (!operation.ok) {
    ElMessage.error(`设备 ${deviceId}：${operation.error}`);
    return;
  }
  const result = normalizeDeviceConfigActionResult(operation.result, deviceId);
  ElMessage.success(buildDeviceConfigActionMessage(type, result));
  if (operation.refreshError) ElMessage.warning(`设备 ${deviceId} 状态暂不可用：${operation.refreshError}`);
}

function openLocalEditor() {
  ++editorSession;
  detailError.value = "";
  editingBundle.value = null;
  localEditorVisible.value = true;
}

async function handleLocalSaved() {
  ++editorSession;
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
        customClass: "device-list-confirmation",
        customStyle: deviceConfirmationStyle,
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
  const session = editorSession;
  detailError.value = "";
  if (isLocalDevice(device)) {
    try {
      const detail = await getLocalDevice(device.normalizedId);
      if (session !== editorSession || deviceStore.selectedDeviceId !== device.normalizedId) return;
      const bundle = extractLocalDeviceBundle(detail);
      if (!bundle) {
        throw new Error("本地设备详情缺少可编辑配置");
      }
      editingBundle.value = bundle;
      localEditorVisible.value = true;
    } catch (caught) {
      if (session !== editorSession || deviceStore.selectedDeviceId !== device.normalizedId) return;
      detailError.value = `设备 ${device.normalizedId}：${caught instanceof Error ? caught.message : "本地设备详情加载失败"}`;
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

function statusBadgeClass(device: DeviceViewModel): string {
  const phase = String(device.runtime?.phase || "UNKNOWN").toUpperCase();
  const status = String(device.status || device["runtimeStatus"] || "UNKNOWN").toUpperCase();
  if (phase === "FAILED" || phase === "DEGRADED" || status === "ERROR") return "is-error";
  // 配置 ONLINE / 运行阶段 ONLINE 不能证明当前点位质量 GOOD。
  return "";
}

function routeDeviceId(): string {
  const value = route.query.deviceId;
  if (Array.isArray(value)) {
    return String(value[0] || "");
  }
  return String(value || "");
}

// 仅新增呈现与菜单状态；以上业务逻辑不变，确认窗只增加局部样式选项。
const deviceConfirmationStyle = {
  width: "500px",
  maxWidth: "calc(100vw - 40px)",
  padding: "0",
  "--app-overlay-bg": "#1a273a",
  "--app-overlay-border": "#506786",
  "--app-overlay-shadow": "0 20px 70px #02091699",
  fontFamily: '"Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
};
const activeMenuDeviceId = ref("");
const hasDeviceFilters = computed(() => Boolean(deviceKeyword.value.trim() || protocolFilter.value || statusFilter.value));
const hasLoadedDeviceList = computed(() => deviceStore.lastUpdatedAt > 0 || deviceStore.devices.length > 0);
const deviceInventoryLabel = computed(() => {
  if (!hasLoadedDeviceList.value) return deviceStore.error ? "数量未知" : deviceStore.loading ? "加载中" : "数量未知";
  return `${filteredDevices.value.length} 台设备`;
});
const deviceResultLabel = computed(() => {
  if (!hasLoadedDeviceList.value) return deviceStore.error ? "设备列表暂不可用" : deviceStore.loading ? "正在获取设备列表" : "设备列表尚未加载";
  const retained = deviceStore.loading || deviceStore.error ? "（上次加载结果）" : "";
  return `显示 ${filteredDevices.value.length} 台设备${retained}`;
});
const deviceEmptyTitle = computed(() => deviceStore.error ? "设备配置加载失败" : hasDeviceFilters.value ? "没有符合筛选条件的设备" : "当前没有设备配置");
const devicePresentations = computed(() => new Map(filteredDevices.value.map((device) => [device.normalizedId, deviceListPresentation(device)])));
const deviceMenuPopoverStyle = {
  "--app-overlay-bg": "#1d2a3d",
  "--app-overlay-border": "#526d8f",
  "--app-overlay-shadow": "0 18px 48px #050e1d99",
  padding: "5px",
  borderRadius: "8px",
  fontFamily: '"Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
};

function devicePresentation(device: DeviceViewModel) {
  return devicePresentations.value.get(device.normalizedId) || deviceListPresentation(device);
}

function closeDeviceMenu() { activeMenuDeviceId.value = ""; }
function toggleDeviceMenu(deviceId: string) { activeMenuDeviceId.value = activeMenuDeviceId.value === deviceId ? "" : deviceId; }
function dismissDeviceMenuOutside(event: PointerEvent) {
  if (!(event.target instanceof Element) || !event.target.closest(".device-action-menu, .device-more")) closeDeviceMenu();
}
function dismissDeviceMenuKey(event: KeyboardEvent) {
  if (event.key !== "Escape" || !activeMenuDeviceId.value) return;
  const trigger = document.querySelector<HTMLButtonElement>('.device-more[aria-expanded="true"]');
  closeDeviceMenu();
  trigger?.focus();
}

watch([deviceKeyword, protocolFilter, statusFilter], closeDeviceMenu);
watch(() => route.fullPath, closeDeviceMenu);
onMounted(() => {
  document.addEventListener("pointerdown", dismissDeviceMenuOutside);
  document.addEventListener("keydown", dismissDeviceMenuKey);
  window.addEventListener("resize", closeDeviceMenu);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", dismissDeviceMenuOutside);
  document.removeEventListener("keydown", dismissDeviceMenuKey);
  window.removeEventListener("resize", closeDeviceMenu);
});
</script>

<style scoped src="@/features/device/components/device-list.css"></style>
