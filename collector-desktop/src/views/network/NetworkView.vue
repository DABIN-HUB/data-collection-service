<template>
  <section class="page-root exact-page network-view">
    <div class="section-heading network-heading">
      <div class="heading-title-line">
        <h1>网络检测</h1>
        <span class="heading-online" :class="{ 'is-pending': !networkResult }"><i aria-hidden="true"></i>{{ networkResult ? networkResult.conclusionText : '尚未执行' }}</span>
      </div>
      <p>检测本机、白名单目标或设备配置地址；仅在点击开始检测后执行。</p>
    </div>

    <div class="exact-page-body network-body">
      <section class="exact-surface network-toolbar" aria-labelledby="network-conditions-title">
        <div class="exact-surface-head network-panel-head">
          <h2 id="network-conditions-title">检测参数</h2>
          <span>端口仅用于 TCP 检测</span>
        </div>
        <div class="network-condition-fields">
          <label class="network-field network-mode-field" for="network-type">
            <span>检测方式</span>
            <select id="network-type" v-model="networkType" @change="syncNetworkMode">
              <option v-for="item in NETWORK_DIAGNOSTIC_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <label class="network-field network-device-field" for="network-device">
            <span>目标设备</span>
            <select id="network-device" v-model="networkDeviceId" @change="applyNetworkDevice">
              <option value="">本机 / 白名单目标</option>
              <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
                {{ device.displayName || device.normalizedId }}
              </option>
            </select>
          </label>
          <label class="network-field network-target-field" for="network-target">
            <span>目标地址</span>
            <input id="network-target" v-model="networkTarget" type="text" placeholder="从设备配置自动带入 host" />
          </label>
          <label class="network-field network-port-field" for="network-port">
            <span>TCP 端口</span>
            <input id="network-port" v-model.number="networkPort" type="number" min="1" max="65535" :disabled="networkType !== 'TCP'" placeholder="TCP 目标端口" aria-describedby="network-port-note" />
          </label>
          <label class="network-field network-timeout-field" for="network-timeout">
            <span>检测超时</span>
            <span class="network-unit-control">
              <input id="network-timeout" v-model.number="networkTimeout" type="number" min="100" max="10000" placeholder="超时 ms" aria-describedby="network-timeout-unit" />
              <span id="network-timeout-unit" class="network-unit">ms</span>
            </span>
          </label>
        </div>
        <div class="network-toolbar-footer">
          <div class="network-device-actions">
            <button type="button" @click="fillNetworkFromSelectedDevice">从设备配置带入</button>
            <span id="network-port-note">PING / TRACE 不使用端口</span>
          </div>
          <div class="network-toolbar-actions">
            <button type="button" :disabled="networkOperating" class="primary" @click="runNetwork">{{ networkOperating ? '检测中' : '开始检测' }}</button>
            <button type="button" :disabled="networkHistory.length === 0" class="network-export" @click="downloadNetworkReport">导出检测结果</button>
          </div>
        </div>
      </section>

      <div class="exact-diagnostic-cards network-summary-cards" :class="{ 'is-pending': !networkResult }" aria-label="检测摘要">
        <div class="exact-diagnostic-card"><span>{{ networkResult ? '结果方式' : '当前配置方式' }}</span><strong>{{ networkResult ? networkResult.type : networkType }}</strong></div>
        <div class="exact-diagnostic-card"><span>检测结论</span><strong>{{ networkResult ? networkResult.conclusionText : '尚未执行' }}</strong></div>
        <div class="exact-diagnostic-card network-reason-summary"><span>原因说明</span><strong>{{ networkResult ? networkResult.reasonText : '检测后显示' }}</strong></div>
        <div class="exact-diagnostic-card"><span>检测历史记录</span><strong>{{ networkHistory.length }}</strong></div>
      </div>

      <section class="exact-surface network-result-panel" :class="{ 'is-pending': !networkResult }">
        <div class="exact-surface-head network-panel-head">
          <h2>检测结果</h2>
          <span class="network-result-target">{{ networkResult ? `${networkResult.target}${networkResult.port ? `:${networkResult.port}` : ''}` : `当前配置：${networkTarget}${networkType === 'TCP' ? `:${networkPort || '-'}` : ''}` }}</span>
        </div>
        <div class="network-result-grid">
          <div v-for="row in networkResultRows" :key="row.label" class="exact-config-item">
            <span>{{ row.label }}</span>
            <strong>{{ row.value }}</strong>
          </div>
        </div>
        <p v-if="!networkResult" class="network-pending-note" role="status">尚未执行网络检测。确认当前参数后，点击“开始检测”查看真实结果。</p>
        <div v-else class="network-json-section">
          <h3 id="network-json-title">结果 JSON</h3>
          <pre class="json-view" tabindex="0" aria-labelledby="network-json-title">{{ networkResult ? prettyJson(networkResult) : '尚未执行网络检测' }}</pre>
        </div>
        <div v-if="networkResult && networkResult.details.length" class="network-trace-lines">
          <strong>路由明细</strong>
          <code v-for="(line, index) in networkResult.details" :key="`${index}-${line}`">{{ line }}</code>
        </div>
      </section>

      <section class="exact-table-card network-history-table">
        <div class="exact-table-title network-panel-head">
          <h2 id="network-history-title">检测历史记录</h2>
          <span>最多保留 10 条</span>
        </div>
        <div class="network-history-scroll" tabindex="0" role="region" aria-labelledby="network-history-title">
          <table>
            <colgroup>
              <col class="network-history-time" />
              <col class="network-history-mode" />
              <col class="network-history-target" />
              <col class="network-history-port" />
              <col class="network-history-conclusion" />
              <col class="network-history-duration" />
              <col />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">时间</th>
                <th scope="col">方式</th>
                <th scope="col">目标</th>
                <th scope="col">端口</th>
                <th scope="col">结论</th>
                <th scope="col">耗时</th>
                <th scope="col">原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="networkHistory.length === 0">
                <td colspan="7" class="exact-empty">暂无网络检测历史</td>
              </tr>
              <tr v-for="item in networkHistory" :key="`${item.completedAt || '-'}-${item.type}-${item.target}-${item.port || '-'}`">
                <td>{{ formatTime(item.completedAt) }}</td>
                <td>{{ item.type }}</td>
                <td><span class="network-history-address" :title="item.target">{{ item.target }}</span></td>
                <td>{{ item.port ?? '-' }}</td>
                <td><span class="status-badge" :class="item.reachable ? 'is-online' : 'is-error'">{{ item.conclusionText }}</span></td>
                <td>{{ item.durationMs ?? '-' }} ms</td>
                <td>{{ item.reasonText }}</td>
              </tr>
            </tbody>
          </table>
        </div>
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
  buildNetworkSubmissionSnapshot,
  normalizeNetworkDiagnosticResultForSubmission,
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
  const submission = buildNetworkSubmissionSnapshot(payload);
  try {
    const result = normalizeNetworkDiagnosticResultForSubmission(await diagnoseNetwork(payload), submission);
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
  } catch (caught) {
    const message = caught instanceof Error ? caught.message : "网络检测失败";
    const result = normalizeNetworkDiagnosticResultForSubmission({
      type: payload.type,
      deviceId: payload.deviceId,
      target: payload.target,
      port: payload.port,
      reachable: false,
      message,
      details: [],
      completedAt: Date.now()
    }, submission);
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

<style scoped src="@/features/network/styles/network-view.css"></style>
