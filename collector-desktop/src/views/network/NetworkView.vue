<template>
  <section class="exact-page network-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>网络检测</h1>
        <span class="heading-online"><i></i>{{ networkResult ? networkResult.conclusionText : '等待检测' }} · {{ networkHistory.length }} 条历史</span>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar network-toolbar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="networkType" @change="syncNetworkMode">
            <option v-for="item in NETWORK_DIAGNOSTIC_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <select v-model="networkDeviceId" @change="applyNetworkDevice">
            <option value="">本机 / 白名单目标</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          <input v-model="networkTarget" type="text" placeholder="从设备配置自动带入 host" />
          <input v-model.number="networkPort" type="number" min="1" max="65535" :disabled="networkType !== 'TCP'" placeholder="TCP 目标端口" />
          <input v-model.number="networkTimeout" type="number" min="100" max="10000" placeholder="超时 ms" />
          <button type="button" @click="fillNetworkFromSelectedDevice">从设备配置带入</button>
        </div>
        <div class="exact-toolbar-group network-toolbar-actions">
          <button type="button" :disabled="networkOperating" class="primary" @click="runNetwork">{{ networkOperating ? '检测中' : '开始检测' }}</button>
          <button type="button" :disabled="networkHistory.length === 0" class="primary" @click="downloadNetworkReport">导出检测结果</button>
        </div>
      </div>

      <div class="exact-diagnostic-cards network-summary-cards">
        <div class="exact-diagnostic-card"><span>检测方式</span><strong>{{ networkType }}</strong></div>
        <div class="exact-diagnostic-card"><span>检测结论</span><strong>{{ networkResult ? networkResult.conclusionText : '-' }}</strong></div>
        <div class="exact-diagnostic-card"><span>失败原因中文化</span><strong>{{ networkResult ? networkResult.reasonText : '尚未执行' }}</strong></div>
        <div class="exact-diagnostic-card"><span>检测历史记录</span><strong>{{ networkHistory.length }}</strong></div>
      </div>

      <section class="exact-surface network-result-panel">
        <div class="exact-surface-head">
          <h2>检测结果</h2>
          <span>{{ networkTarget }}{{ networkType === 'TCP' ? `:${networkPort || '-'}` : '' }}</span>
        </div>
        <div class="network-result-grid">
          <div v-for="row in networkResultRows" :key="row.label" class="exact-config-item">
            <span>{{ row.label }}</span>
            <strong>{{ row.value }}</strong>
          </div>
        </div>
        <pre class="json-view">{{ networkResult ? prettyJson(networkResult) : '尚未执行网络检测' }}</pre>
        <div v-if="networkResult && networkResult.details.length" class="network-trace-lines">
          <strong>路由明细</strong>
          <code v-for="(line, index) in networkResult.details" :key="`${index}-${line}`">{{ line }}</code>
        </div>
      </section>

      <section class="exact-table-card network-history-table">
        <div class="exact-table-title">
          <h2>检测历史记录</h2>
          <span>最多保留 10 条</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>方式</th>
              <th>目标</th>
              <th>端口</th>
              <th>结论</th>
              <th>耗时</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="networkHistory.length === 0">
              <td colspan="7" class="exact-empty">暂无网络检测历史</td>
            </tr>
            <tr v-for="item in networkHistory" :key="`${item.completedAt || '-'}-${item.type}-${item.target}-${item.port || '-'}`">
              <td>{{ formatTime(item.completedAt) }}</td>
              <td>{{ item.type }}</td>
              <td>{{ item.target }}</td>
              <td>{{ item.port ?? '-' }}</td>
              <td><span class="status-badge" :class="item.reachable ? 'is-online' : 'is-error'">{{ item.conclusionText }}</span></td>
              <td>{{ item.durationMs ?? '-' }} ms</td>
              <td>{{ item.reasonText }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <EdgeTelemetryPanel :devices="deviceStore.devices" :selected-device-id="networkDeviceId" @select-device="selectNetworkDevice" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { diagnoseNetwork } from "@/api/ops.api";
import EdgeTelemetryPanel from "@/features/network/components/EdgeTelemetryPanel.vue";
import {
  NETWORK_DIAGNOSTIC_TYPES,
  appendNetworkHistory,
  buildNetworkDiagnosticPayload,
  buildNetworkExportText,
  buildNetworkResultRows,
  normalizeNetworkDiagnosticResult,
  resolveNetworkTargetFromDevice,
  type NetworkDiagnosticPayload,
  type NetworkDiagnosticType,
  type NormalizedNetworkDiagnosticResult
} from "@/features/network/utils/network-utils";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();

const networkType = ref<NetworkDiagnosticType>("PING");
const networkDeviceId = ref("");
const networkTarget = ref("127.0.0.1");
const networkPort = ref(9090);
const networkTimeout = ref(3000);
const networkOperating = ref(false);
const networkResult = ref<NormalizedNetworkDiagnosticResult | null>(null);
const networkHistory = ref<NormalizedNetworkDiagnosticResult[]>([]);

const selectedNetworkDevice = computed(() => deviceStore.devices.find((device) => device.normalizedId === networkDeviceId.value));
const networkResultRows = computed(() => networkResult.value ? buildNetworkResultRows(networkResult.value) : []);

async function runNetwork() {
  let payload: NetworkDiagnosticPayload;
  try {
    payload = buildNetworkDiagnosticPayload({
      type: networkType.value,
      deviceId: networkDeviceId.value,
      target: networkTarget.value,
      port: networkPort.value,
      timeoutMs: networkTimeout.value
    });
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "网络检测参数无效");
    return;
  }

  networkOperating.value = true;
  try {
    const result = normalizeNetworkDiagnosticResult(await diagnoseNetwork(payload));
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "网络检测失败";
    const result = normalizeNetworkDiagnosticResult({
      type: payload.type,
      deviceId: payload.deviceId,
      target: payload.target,
      port: payload.port,
      reachable: false,
      message,
      details: [],
      completedAt: Date.now()
    });
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
    ElMessage.error(message);
  } finally {
    networkOperating.value = false;
  }
}

function applyNetworkDevice() {
  fillNetworkFromSelectedDevice();
}

function fillNetworkFromSelectedDevice() {
  const target = resolveNetworkTargetFromDevice(selectedNetworkDevice.value || null);
  networkTarget.value = target.target;
  if (target.port !== undefined) {
    networkPort.value = target.port;
  } else if (networkType.value === "TCP" && !networkPort.value) {
    networkPort.value = 9090;
  }
}

function selectNetworkDevice(deviceId: string) {
  networkDeviceId.value = deviceId;
  if (deviceId) {
    fillNetworkFromSelectedDevice();
  }
}

function syncNetworkMode() {
  if (networkType.value !== "TCP") {
    networkPort.value = 0;
    return;
  }
  if (!networkPort.value) {
    networkPort.value = Number(selectedNetworkDevice.value?.port || 9090);
  }
}

function applyRouteQuery() {
  const nextDeviceId = normalizeRouteQuery(route.query.deviceId);
  const nextTarget = normalizeRouteQuery(route.query.target);
  const nextPort = Number(normalizeRouteQuery(route.query.port));
  const nextType = normalizeRouteType(route.query.type);

  if (nextDeviceId) {
    networkDeviceId.value = nextDeviceId;
  }
  if (nextType) {
    networkType.value = nextType;
  }
  if (nextTarget) {
    networkTarget.value = nextTarget;
  }
  if (Number.isFinite(nextPort) && nextPort >= 1 && nextPort <= 65535) {
    networkPort.value = Math.trunc(nextPort);
    networkType.value = "TCP";
  } else if (nextTarget && !nextType) {
    networkType.value = "PING";
  }
  if (nextDeviceId && !nextTarget && deviceStore.devices.length > 0) {
    fillNetworkFromSelectedDevice();
  }
}

function downloadNetworkReport() {
  if (!networkHistory.value.length) {
    ElMessage.warning("当前没有可导出的网络检测结果");
    return;
  }
  const blob = new Blob([buildNetworkExportText(networkHistory.value)], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `collector-network-${new Date().toISOString().replace(/[:.]/g, "-")}.txt`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function normalizeRouteQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] ?? "") : "";
  }
  return value === undefined || value === null ? "" : String(value);
}

function normalizeRouteType(value: unknown): NetworkDiagnosticType | "" {
  const type = normalizeRouteQuery(value).trim().toUpperCase();
  return type === "PING" || type === "TRACE" || type === "TCP" ? type : "";
}

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = typeof value === "number" ? new Date(value) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

async function initializeNetworkView() {
  await appStore.initialize();
  applyRouteQuery();
  await deviceStore.refresh();
  applyRouteQuery();
}

onMounted(() => {
  void initializeNetworkView();
});

watch(() => [route.query.target, route.query.port, route.query.type, route.query.deviceId], () => {
  applyRouteQuery();
});
</script>

<style scoped>
.network-view .network-result-grid {
  display: grid;
  padding: 20px 20px 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.network-view .network-toolbar {
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px 10px;
}

.network-view .network-toolbar .exact-toolbar-filters {
  flex: 1 1 auto;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(104px, 0.7fr) minmax(136px, 0.88fr) minmax(142px, 0.96fr) minmax(92px, 0.5fr) minmax(92px, 0.5fr) auto;
  gap: 6px 8px;
  align-items: center;
}

.network-view .network-toolbar .exact-toolbar-filters input,
.network-view .network-toolbar .exact-toolbar-filters select {
  min-width: 0;
  width: 100%;
}

.network-view .network-toolbar .exact-toolbar-filters button {
  justify-self: start;
  width: auto;
  min-width: 0;
}

.network-view .network-toolbar-actions {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  margin-left: auto;
}

.network-view .network-toolbar-actions button {
  min-height: 28px;
  padding: 0 8px;
  font-size: 10.5px;
}

.network-view .network-summary-cards .exact-diagnostic-card:nth-child(3) {
  grid-column: span 2;
}

.network-view .network-summary-cards .exact-diagnostic-card:nth-child(3) strong {
  font-size: 15px;
  line-height: 1.45;
  white-space: normal;
}

.network-view .network-result-panel .json-view {
  margin: 0 20px 20px;
}

.network-view .network-trace-lines {
  display: grid;
  gap: 8px;
  margin: 0 20px 20px;
  padding: 14px;
  border: 1px solid rgba(45, 74, 122, 0.45);
  border-radius: 14px;
  background: rgba(4, 10, 23, 0.42);
}

.network-view .network-trace-lines strong {
  color: #f8fafc;
  font-size: 13px;
}

.network-view .network-trace-lines code {
  display: block;
  padding: 5px 8px;
  border-radius: 8px;
  color: #86efac;
  background: rgba(2, 6, 23, 0.72);
  white-space: pre-wrap;
}

.network-view .network-history-table td:nth-child(7) {
  min-width: 220px;
  color: var(--exact-muted);
}

@media (max-width: 980px) {
  .network-view .network-toolbar {
    flex-wrap: wrap;
  }

  .network-view .network-toolbar .exact-toolbar-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .network-view .network-result-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
