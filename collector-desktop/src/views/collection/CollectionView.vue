<template>
  <section class="exact-page collection-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>数据采集配置</h1>
        <span class="heading-online"><i></i>{{ protocolStore.protocols.length }} 种协议</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="collectionLoading" @click="refreshCollectionContext">刷新概览</button>
      </div>
    </div>

    <div class="exact-page-body">
      <el-alert v-if="collectionError" :title="collectionError" type="warning" :closable="false" />
      <section class="exact-surface exact-global-config">
        <div class="exact-surface-head"><h2>全局采集配置</h2><span>当前运行配置</span></div>
        <div class="exact-config-grid collection-summary-grid">
          <div v-for="item in collectionSummaryItems" :key="item.label" class="exact-config-item">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <ConfigOpsPanel
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        @imported="refreshCollectionContext"
        @synced="refreshCollectionContext"
      />

      <section class="exact-table-card collection-protocol-table">
        <div class="exact-table-title"><h2>协议配置列表</h2><span>{{ protocolStore.protocols.length }} 种协议</span></div>
        <table>
          <thead>
            <tr><th>协议名称</th><th>规范编码</th><th>默认端口</th><th>采集方式</th><th>能力状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-if="protocolStore.protocols.length === 0"><td colspan="6" class="exact-empty">当前没有可用的协议定义</td></tr>
            <tr v-for="item in protocolStore.protocols" :key="item.protocol">
              <td><strong>{{ item.title || item.protocol || '-' }}</strong></td>
              <td><code>{{ item.protocol || '-' }}</code></td>
              <td>{{ protocolDefaultPort(item) }}</td>
              <td>{{ protocolMode(item) }}</td>
              <td><span class="capability-badge">{{ protocolCapability(item) }}</span></td>
              <td><button type="button" @click="openProtocolConfig(item)">配置设备</button></td>
            </tr>
          </tbody>
        </table>
      </section>

      <details v-if="selectedProtocol" class="exact-json-panel" open>
        <summary>{{ selectedProtocol.title || selectedProtocol.protocol }} Schema</summary>
        <pre class="json-view">{{ prettyJson(selectedProtocol) }}</pre>
      </details>
    </div>

    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocolStore.protocols" @saved="handleLocalSaved" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { getConfigSummary } from "@/api/config.api";
import LocalDeviceEditor from "@/features/device/components/LocalDeviceEditor.vue";
import type { LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import ConfigOpsPanel from "@/features/collection/components/ConfigOpsPanel.vue";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import { useProtocolStore } from "@/stores/protocol.store";
import type { ConfigSummaryResponse } from "@/types/config";
import type { DeviceInfo } from "@/types/device";
import type { ProtocolSchema } from "@/types/protocol";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const protocolStore = useProtocolStore();
const route = useRoute();

const configSummary = ref<ConfigSummaryResponse | null>(null);
const selectedProtocol = ref<ProtocolSchema | null>(null);
const localEditorVisible = ref(false);
const editingBundle = ref<LocalDeviceBundle | null>(null);
const collectionLoading = ref(false);
const configSummaryError = ref("");
const initialized = ref(false);

const collectionError = computed(() => deviceStore.error || protocolStore.error || configSummaryError.value);
const collectionSummaryItems = computed(() => {
  const summary = configSummary.value;
  const stats = summary?.cacheStats;
  return [
    { label: "设备配置", value: `${stats?.deviceCount ?? deviceStore.devices.length} 台` },
    { label: "点位总数", value: `${stats?.pointCount ?? sumPoints(deviceStore.devices)} 个` },
    { label: "连接配置", value: `${stats?.connectionCount ?? deviceStore.devices.length} 个` },
    { label: "配置来源", value: summary?.serviceId ? "当前运行配置" : "未知" }
  ];
});

onMounted(async () => {
  await appStore.initialize();
  await refreshCollectionContext();
  applyRouteDeviceContext();
  initialized.value = true;
});

watch(() => route.query.deviceId, () => {
  if (initialized.value) {
    applyRouteDeviceContext();
  }
});

async function refreshCollectionContext() {
  collectionLoading.value = true;
  try {
    await Promise.allSettled([
      deviceStore.refresh(),
      protocolStore.refresh(),
      loadConfigSummary()
    ]);
    applyRouteDeviceContext();
  } finally {
    collectionLoading.value = false;
  }
}

async function loadConfigSummary() {
  try {
    configSummary.value = await getConfigSummary();
    configSummaryError.value = "";
  } catch (error) {
    configSummary.value = null;
    configSummaryError.value = error instanceof Error ? error.message : "配置摘要加载失败";
  }
}

function applyRouteDeviceContext() {
  const queryDeviceId = queryStringValue(route.query.deviceId);
  if (queryDeviceId) {
    const exists = deviceStore.devices.some((device) => deviceIdOf(device) === queryDeviceId);
    if (exists || deviceStore.devices.length === 0) {
      deviceStore.selectDevice(queryDeviceId);
      return;
    }
  }
  ensureSelectedDevice();
}

function ensureSelectedDevice() {
  if (deviceStore.selectedDevice) {
    return;
  }
  const firstDeviceId = deviceStore.devices[0] ? deviceIdOf(deviceStore.devices[0]) : "";
  if (firstDeviceId) {
    deviceStore.selectDevice(firstDeviceId);
  }
}

function openProtocolConfig(protocol: ProtocolSchema) {
  selectedProtocol.value = protocol;
  editingBundle.value = null;
  localEditorVisible.value = true;
}

async function handleLocalSaved(deviceId: string) {
  localEditorVisible.value = false;
  if (deviceId) {
    deviceStore.selectDevice(deviceId);
  }
  await refreshCollectionContext();
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

function deviceIdOf(device: DeviceInfo): string {
  return String(device.deviceId || device.id || device.connectionKey || "");
}

function sumPoints(source: DeviceInfo[]): number {
  return source.reduce((sum, device) => sum + Number(device.pointCount || (Array.isArray(device.points) ? device.points.length : 0) || 0), 0);
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function valueOf(value: unknown, keys: string[], fallback: unknown): unknown {
  const record = asRecord(value);
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) {
      return record[key];
    }
  }
  return fallback;
}

function queryStringValue(value: unknown): string {
  return Array.isArray(value) ? String(value[0] || "") : String(value || "");
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}
</script>

<style scoped>
.collection-view {
  min-width: 0;
}

.collection-summary-grid {
  display: grid;
  padding: 20px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.collection-protocol-table .capability-badge {
  display: inline-flex;
  width: fit-content;
  padding: 3px 8px;
  align-items: center;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  font-size: 10px;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .collection-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
