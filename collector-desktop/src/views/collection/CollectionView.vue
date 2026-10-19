<template>
  <section class="exact-page collection-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>数据采集配置</h1>
        <span class="collection-protocol-count">{{ protocolCountText }}</span>
      </div>
      <div class="heading-actions">
        <button type="button" class="primary" :disabled="collectionLoading" @click="refreshCollectionContext">{{ collectionLoading ? '读取中…' : '刷新配置' }}</button>
      </div>
    </div>

    <div class="exact-page-body">
      <el-alert v-if="collectionError" title="部分配置读取失败，请检查接口访问权限或连接；各来源状态见下方。" type="warning" :closable="false" />
      <section class="exact-surface exact-global-config" aria-labelledby="collection-summary-title">
        <div class="exact-surface-head"><h2 id="collection-summary-title">全局采集配置</h2><span>{{ collectionDataContextText }}</span></div>
        <div class="exact-config-grid collection-summary-grid">
          <div v-for="item in collectionSummaryItems" :key="item.label" class="exact-config-item">
            <span>{{ item.label }}<span v-if="item.source">（{{ item.source }}）</span></span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <div class="collection-device-context" aria-label="当前设备配置上下文">
        <div><strong>{{ deviceStore.selectedDevice?.displayName || '尚未选择设备' }}</strong><span>{{ deviceStore.selectedDeviceId || '请先在设备管理中选择设备' }}</span></div>
        <span>{{ deviceStore.selectedDevice?.displayProtocol || '协议未知' }}</span>
        <router-link v-if="deviceStore.selectedDeviceId" :to="{ path: '/device/workbench', query: { deviceId: deviceStore.selectedDeviceId } }">打开配置工作台</router-link>
      </div>

      <div class="collection-workspace">
        <div class="collection-main-stack">
          <section class="collection-point-card" aria-labelledby="collection-point-title">
            <div class="exact-table-title"><h2 id="collection-point-title">点位配置摘要</h2><span>设备库存附带字段，非独立点位查询</span></div>
            <div v-if="deviceStore.selectedDevice?.points?.length" class="collection-point-scroll" tabindex="0" role="region" aria-label="库存附带点位明细，可横向及纵向滚动">
              <table>
                <thead><tr><th scope="col">点位编码</th><th scope="col">点位名称</th><th scope="col">数据类型</th><th scope="col">基础采集周期</th><th scope="col">上报字段</th></tr></thead>
                <tbody>
                  <tr v-for="(point, index) in deviceStore.selectedDevice.points as Record<string, unknown>[]" :key="index">
                    <td :title="String(point?.pointCode ?? '-')">{{ point?.pointCode ?? '-' }}</td>
                    <td :title="String(point?.pointName ?? '-')">{{ point?.pointName ?? '-' }}</td>
                    <td>{{ point?.dataType ?? '-' }}</td>
                    <td>{{ point?.baseCollectionInterval ?? '-' }}<span v-if="point?.baseCollectionInterval != null"> ms</span></td>
                    <td :title="String((point?.additionalConfig as Record<string, unknown> | undefined)?.reportField ?? '-')">{{ (point?.additionalConfig as Record<string, unknown> | undefined)?.reportField ?? '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="collection-unavailable" role="status">{{ !deviceStore.selectedDeviceId ? '尚未选择设备；从设备管理选择后查看配置。' : deviceStore.selectedDevice?.points ? '当前设备库存附带空点位列表；完整点位配置请在工作台读取。' : '当前设备库存未提供点位明细；请进入配置工作台读取，不以缺失明细表示零点位。' }}</p>
          </section>
          <section class="collection-diff-card" aria-labelledby="collection-diff-title">
            <div class="exact-table-title"><h2 id="collection-diff-title">配置差异</h2><span>差异读取与同步操作分开</span></div>
            <div class="collection-diff-body">
              <p>本页没有独立读取当前设备差异，也不持有已保存快照与草稿对照；差异数量和同步完成状态未知。</p>
              <router-link v-if="deviceStore.selectedDeviceId" :to="{ path: '/device/workbench', query: { deviceId: deviceStore.selectedDeviceId } }">在工作台读取配置差异</router-link>
            </div>
          </section>
          <details class="collection-protocol-disclosure">
            <summary>协议定义与设备配置入口<span>{{ protocolCountText }}</span></summary>
      <section class="exact-table-card collection-protocol-table" aria-labelledby="collection-protocol-title">
        <div class="exact-table-title"><h2 id="collection-protocol-title">协议配置列表</h2><span>{{ protocolCountText }}</span></div>
        <OperationSourceStatus class="collection-protocol-source" :state="protocolSourceState" source="协议定义列表" :last-attempt-at="collectionLastAttemptAt" :last-success-at="protocolLastSuccessAt" :detail="protocolStore.error ? '本次协议读取失败；已有列表仍可查看。' : '成功时间仅对应当前代次已完成且无错误的列表读取。'" />
        <div class="collection-table-scroll" tabindex="0" role="region" aria-label="协议配置列表，可横向及纵向滚动">
          <table>
            <colgroup><col class="collection-col-name" /><col class="collection-col-code" /><col class="collection-col-port" /><col class="collection-col-mode" /><col class="collection-col-capability" /><col class="collection-col-action" /></colgroup>
            <thead>
              <tr><th scope="col">协议名称</th><th scope="col">规范编码</th><th scope="col">默认端口</th><th scope="col">采集方式</th><th scope="col">能力状态</th><th scope="col">操作</th></tr>
            </thead>
            <tbody>
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
        </div>
        <div v-if="protocolStore.protocols.length === 0" class="collection-empty" role="status">{{ protocolStore.loading ? '协议加载中' : protocolStore.error ? '协议数据不可用，请刷新重试' : protocolLastSuccessAt ? '当前没有可用的协议定义' : '协议尚未成功读取' }}</div>
      </section>

          </details>
        </div>
        <aside class="collection-aside-stack" aria-label="配置来源与高级操作">
          <section class="collection-source-card">
            <div class="exact-table-title"><h2>来源证据</h2><span>独立读取状态与成功时间</span></div>
            <div class="collection-sources" aria-label="配置读取来源与时间">
              <OperationSourceStatus :state="configSummarySourceState" source="运行配置摘要" :last-attempt-at="configSummaryLastAttemptAt" :last-success-at="configSummaryLastSuccessAt" :detail="configSummaryError ? '本次摘要读取失败；按原规则不保留摘要，库存回退来源另列。' : '只读运行配置，已读取不代表采集服务健康。'" />
              <OperationSourceStatus :state="deviceSourceState" source="设备配置库存" :last-attempt-at="collectionLastAttemptAt" :last-success-at="deviceStore.lastUpdatedAt || null" :detail="deviceSourceDetail" />
            </div>
          </section>
      <ConfigOpsPanel
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        @imported="refreshCollectionContext"
        @synced="refreshCollectionContext"
      />

      <details v-if="selectedProtocol" class="exact-json-panel">
        <summary>{{ selectedProtocol.title || selectedProtocol.protocol }} Schema</summary>
        <pre class="json-view" tabindex="0" aria-label="完整协议 Schema，可滚动查看">{{ prettyJson(selectedProtocol) }}</pre>
      </details>
        </aside>
      </div>
    </div>

    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocolStore.protocols" @saved="handleLocalSaved" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { getConfigSummary } from "@/api/config.api";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import LocalDeviceEditor from "@/features/device/components/LocalDeviceEditor.vue";
import type { LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import ConfigOpsPanel from "@/features/collection/components/ConfigOpsPanel.vue";
import { knownInventoryPointCount } from "@/features/operations/inventory-presentation";
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
const collectionLastAttemptAt = ref<number | null>(null);
const configSummaryLastAttemptAt = ref<number | null>(null);
const configSummaryLastSuccessAt = ref<number | null>(null);
const configSummaryReadLoading = ref(false);
const protocolLastSuccessAt = ref<number | null>(null);

const configSummarySourceState = computed(() => configSummaryReadLoading.value ? "loading" : configSummaryError.value ? "error" : configSummary.value ? "ready" : "unknown");
const deviceSourceState = computed(() => {
  if (deviceStore.loading) return "loading";
  if (deviceStore.lastUpdatedAt > 0) {
    return deviceStore.error && deviceStore.lastUpdatedAt < (collectionLastAttemptAt.value ?? 0) ? "degraded" : deviceStore.devices.length ? "ready" : "empty";
  }
  return deviceStore.error ? "error" : "unknown";
});
const deviceSourceDetail = computed(() => deviceStore.error
  ? deviceStore.lastUpdatedAt >= (collectionLastAttemptAt.value ?? 0) && deviceStore.lastUpdatedAt > 0
    ? "配置库存已读取，但设备运行状态读取存在错误；库存不代表运行健康。"
    : "配置或运行来源读取存在错误；已知库存依据独立的配置列表成功时间。"
  : "设备与点位未知时不填零；配置摘要不可用时沿用已知库存回退。"
);
const protocolSourceState = computed(() => protocolStore.loading ? "loading" : protocolStore.error ? protocolStore.protocols.length ? "degraded" : "error" : protocolLastSuccessAt.value ? protocolStore.protocols.length ? "ready" : "empty" : "unknown");

const collectionError = computed(() => deviceStore.error || protocolStore.error || configSummaryError.value);
const collectionSummaryItems = computed(() => {
  const summary = configSummary.value;
  const stats = summary?.cacheStats;
  return [
    { label: "设备配置", value: formatSummaryCount(stats?.deviceCount ?? knownDeviceCount(), "台"), source: stats?.deviceCount != null ? "配置摘要" : knownDeviceCount() !== null ? "设备库存" : "" },
    { label: "点位总数", value: formatSummaryCount(stats?.pointCount ?? knownPointCount(), "个"), source: stats?.pointCount != null ? "配置摘要" : knownPointCount() !== null ? "设备库存" : "" },
    { label: "连接配置", value: formatSummaryCount(stats?.connectionCount, "个"), source: stats?.connectionCount != null ? "配置摘要" : "" },
    { label: "配置来源", value: summary?.serviceId ? "当前运行配置" : "未知", source: "" }
  ];
});
const protocolCountText = computed(() => {
  if (protocolStore.loading) return "协议加载中";
  if (protocolStore.error && protocolStore.protocols.length === 0) return "协议未知";
  if (protocolStore.error) return `上次已加载 ${protocolStore.protocols.length} 种协议`;
  if (!protocolLastSuccessAt.value) return protocolStore.protocols.length ? `已加载 ${protocolStore.protocols.length} 种协议（时间未知）` : "协议未知";
  return `${protocolStore.protocols.length} 种协议`;
});
const collectionDataContextText = computed(() => {
  if (configSummary.value?.cacheStats) return "各计数按字段来源标注；缺失连接数不借用设备数";
  if (deviceStore.lastUpdatedAt > 0) return deviceStore.error ? "上次已加载的设备配置" : "设备配置列表";
  return collectionLoading.value ? "配置加载中" : "配置数据未知";
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
  collectionLastAttemptAt.value = Date.now();
  const expectedProtocolGeneration = protocolStore.refreshGeneration + 1;
  const previousProtocols = protocolStore.protocols;
  try {
    const results = await Promise.allSettled([
      deviceStore.refresh(),
      protocolStore.refresh(),
      loadConfigSummary()
    ]);
    // Store 内部捕获错误；列表引用被本轮实际提交后才有成功证据，不以 fulfilled 盖章。
    if (results[1]?.status === "fulfilled" && protocolStore.refreshGeneration === expectedProtocolGeneration && protocolStore.protocols !== previousProtocols && !protocolStore.loading && !protocolStore.error) {
      protocolLastSuccessAt.value = Date.now();
    }
    applyRouteDeviceContext();
  } finally {
    collectionLoading.value = false;
  }
}

async function loadConfigSummary() {
  configSummaryLastAttemptAt.value = Date.now();
  configSummaryReadLoading.value = true;
  try {
    configSummary.value = await getConfigSummary();
    configSummaryLastSuccessAt.value = Date.now();
    configSummaryError.value = "";
  } catch (error) {
    configSummary.value = null;
    configSummaryError.value = error instanceof Error ? error.message : "配置摘要加载失败";
  } finally {
    configSummaryReadLoading.value = false;
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
  return String(device.deviceId || device.id || "");
}

function knownDeviceCount(): number | null {
  return deviceStore.lastUpdatedAt > 0 ? deviceStore.devices.length : null;
}

function knownPointCount(): number | null {
  return deviceStore.lastUpdatedAt > 0 ? knownInventoryPointCount(deviceStore.devices) : null;
}

function formatSummaryCount(value: number | null | undefined, unit: string): string {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) return `${value} ${unit}`;
  return collectionLoading.value ? "加载中" : "未知";
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

<style scoped src="../../features/collection/styles/collection-view.css"></style>
