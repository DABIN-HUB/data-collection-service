<template>
  <section class="page-root exact-page network-view">
    <div class="section-heading network-heading">
      <div class="network-title-context">
      <div class="heading-title-line">
        <h1>网络检测</h1>
        <span class="heading-online" :class="{ 'is-pending': !networkResult, 'is-read-error': networkResult && isNetworkReadFailure(networkResult) }"><i aria-hidden="true"></i>{{ networkResult ? networkConclusion(networkResult) : '尚未执行' }}</span>
      </div>
      <p>检测本机、白名单目标或设备配置地址；仅在点击开始检测后执行。</p>
      </div>
      <div class="network-heading-actions">
        <button type="button" :disabled="networkHistory.length === 0" class="network-export" aria-describedby="network-export-note" @click="downloadNetworkReport">导出检测结果</button>
      </div>
    </div>

    <div class="exact-page-body network-body">
      <div class="network-workbench">
      <div class="network-target-column">
      <section class="exact-surface network-toolbar" aria-labelledby="network-conditions-title">
        <div class="exact-surface-head network-panel-head">
          <h2 id="network-conditions-title">检测目标</h2>
          <span>端口仅用于 TCP 检测</span>
        </div>
        <div class="network-condition-fields">
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
              <label class="network-field network-mode-field" for="network-type">
                <span>检测方式</span>
                <select id="network-type" v-model="networkType" @change="syncNetworkMode">
                  <option v-for="item in NETWORK_DIAGNOSTIC_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select>
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
          <button type="button" :disabled="networkOperating" class="primary network-run" @click="runNetwork">{{ networkOperating ? '检测中' : '开始检测' }}</button>
        </div>
        <div class="network-toolbar-footer">
          <div class="network-device-actions">
            <button type="button" @click="fillNetworkFromSelectedDevice">从设备配置带入</button>
            <span id="network-port-note">PING / TRACE 不使用端口</span>
          </div>
        </div>
      </section>

      </div>

      <div class="network-result-column">
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
        <div v-if="!networkResult" class="network-pending-note" role="status">
          <span class="network-empty-symbol" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="9" y="3" width="6" height="5" rx="1" /><rect x="3" y="16" width="6" height="5" rx="1" /><rect x="15" y="16" width="6" height="5" rx="1" /><path d="M12 8v4M6 16v-4h12v4" /></svg>
          </span>
          <h3>尚未执行网络检测</h3>
          <p>确认目标、方式与超时后，点击“开始检测”查看真实结果。未执行时不显示耗时或可达状态。</p>
        </div>
        <div v-else class="network-json-section">
          <details class="network-result-raw"><summary id="network-json-title">查看结果 JSON</summary>
          <pre class="json-view" tabindex="0" aria-labelledby="network-json-title">{{ networkResult ? prettyJson(networkResult) : '尚未执行网络检测' }}</pre>
          </details>
        </div>
        <div v-if="networkResult && networkResult.details.length" class="network-trace-lines">
          <strong>路由明细</strong>
          <code v-for="(line, index) in networkResult.details" :key="`${index}-${line}`">{{ line }}</code>
        </div>
      </section>
      <aside class="exact-surface network-context-panel" aria-labelledby="network-context-title">
        <div class="exact-surface-head network-panel-head">
          <h2 id="network-context-title">本次操作上下文</h2>
        </div>
        <div class="network-context-body">
          <h3>当前表单参数 · 尚未提交的修改不改变已有结果</h3>
          <dl class="network-context-list">
            <div><dt>触发方式</dt><dd>手动提交</dd></div>
            <div><dt>目标来源</dt><dd>{{ selectedNetworkDevice ? (selectedNetworkDevice.displayName || selectedNetworkDevice.normalizedId) : (networkDeviceId || '本机 / 白名单目标') }}</dd></div>
            <div><dt>当前配置方式</dt><dd>{{ networkType }}</dd></div>
            <div><dt>目标地址</dt><dd>{{ networkTarget || '未填写' }}</dd></div>
            <div><dt>TCP 端口</dt><dd>{{ networkType === 'TCP' ? (networkPort || '未填写') : '当前方式不使用' }}</dd></div>
            <div><dt>检测超时</dt><dd>{{ networkTimeout }} ms</dd></div>
            <div><dt>当前会话历史</dt><dd>{{ networkHistory.length }} 条本地记录</dd></div>
          </dl>
          <dl v-if="networkResult" class="network-context-list network-submitted-context">
            <div><dt>结果方式</dt><dd>{{ networkResult.type }}</dd></div>
            <div><dt>检测结论</dt><dd>{{ networkConclusion(networkResult) }}</dd></div>
            <div><dt>原因说明</dt><dd>{{ networkReason(networkResult) }}</dd></div>
          </dl>
          <OperationSourceStatus
            class="network-source-status"
            :state="networkSourceState"
            source="网络检测接口 · 仅由用户提交"
            :last-attempt-at="networkLastAttemptAt"
            :last-success-at="networkLastSuccessAt"
            :detail="networkSourceDetail"
          />
          <p id="network-export-note" class="network-export-note">{{ networkHistory.length === 0 ? '当前会话暂无检测结果，导出不可用。' : '导出当前会话保留的检测历史，最多 10 条。' }}</p>
        </div>
      </aside>
      </div>
      </div>

      <section class="exact-table-card network-history-table">
        <div class="exact-table-title network-panel-head">
          <h2 id="network-history-title">检测历史记录</h2>
          <span>最多保留 10 条</span>
        </div>
        <div v-if="networkHistory.length" class="network-history-scroll" tabindex="0" role="region" aria-labelledby="network-history-title">
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
              <tr v-for="item in networkHistory" :key="`${item.completedAt || '-'}-${item.type}-${item.target}-${item.port || '-'}`">
                <td>{{ formatTime(item.completedAt) }}</td>
                <td>{{ item.type }}</td>
                <td><span class="network-history-address" :title="item.target">{{ item.target }}</span></td>
                <td>{{ item.port ?? '-' }}</td>
                <td><span class="status-badge" :class="item.reachable ? 'is-online' : 'is-error'">{{ networkConclusion(item) }}</span></td>
                <td>{{ item.durationMs ?? '-' }} ms</td>
                <td>{{ networkReason(item) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="networkHistory.length === 0" class="network-history-empty" role="status">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><ellipse cx="12" cy="5" rx="7" ry="3" /><path d="M5 5v14c0 4 14 4 14 0V5M5 10c0 4 14 4 14 0M5 15c0 4 14 4 14 0" /></svg>
          <strong>当前会话暂无检测历史</strong>
          <p>尚未产生检测记录；本地计数为 {{ networkHistory.length }}，不代表接口成功读取。</p>
        </div>
      </section>

      <details class="network-telemetry-tools">
        <summary>边缘遥测入口 <span>高级工具 · 提交会写入真实数据链路，不属于网络检测</span></summary>
        <EdgeTelemetryPanel :devices="deviceStore.devices" :selected-device-id="networkDeviceId" @select-device="selectNetworkDevice" />
      </details>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRaw, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";

import { diagnoseNetwork } from "@/api/ops.api";
import { ApiRequestError } from "@/api/http";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
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
const networkLastAttemptAt = ref<number | null>(null);
const networkLastSuccessAt = ref<number | null>(null);
// 仅关联原结果的呈现元数据；不改归一化对象、历史、导出或网络算法。
const networkReadFailures = new WeakMap<NormalizedNetworkDiagnosticResult, number | null>();
let isViewMounted = false;

const selectedNetworkDevice = computed(() => deviceStore.devices.find((device) => device.normalizedId === networkDeviceId.value));
const networkResultRows = computed(() => {
  const result = networkResult.value;
  if (!result) return [];
  return buildNetworkResultRows(result).map((row) => {
    if (row.label === "检测结论") return { ...row, value: networkConclusion(result) };
    if (row.label === "失败原因") return { ...row, value: networkReason(result) };
    return row;
  });
});
const networkSourceState = computed<"pending" | "loading" | "ready" | "error">(() => {
  if (networkOperating.value) return "loading";
  if (!networkResult.value) return "pending";
  return isNetworkReadFailure(networkResult.value) ? "error" : "ready";
});
const networkSourceDetail = computed(() => {
  const result = networkResult.value;
  if (!result) return networkOperating.value ? "正在执行已提交参数；尚无完成结果。" : "未提交检测，不产生尝试或成功时间；历史 0 是当前会话的真实计数。";
  const target = `${result.target}${result.port !== undefined ? `:${result.port}` : ''}`;
  const attribution = `下方结果属于已提交的 ${result.type} / ${target}，编辑当前表单不会重标旧结果。`;
  if (networkOperating.value) return `检测进行中；${attribution}最后成功时间仅指此前成功返回的接口结果。`;
  if (isNetworkReadFailure(result)) return `${attribution}接口读取失败不能证明目标不可达；最后成功时间仅指此前成功返回的接口结果。`;
  return `${attribution}接口成功返回不等于目标可达，请以真实检测结论为准。`;
});

function isNetworkReadFailure(result: NormalizedNetworkDiagnosticResult): boolean {
  return networkReadFailures.has(toRaw(result));
}

function networkConclusion(result: NormalizedNetworkDiagnosticResult): string {
  if (!isNetworkReadFailure(result)) return result.conclusionText;
  const status = networkReadFailures.get(toRaw(result));
  return status === 401 || status === 403 ? "权限不足" : "检测读取失败";
}

function networkReason(result: NormalizedNetworkDiagnosticResult): string {
  if (!isNetworkReadFailure(result)) return result.reasonText;
  const status = networkReadFailures.get(toRaw(result));
  return status === 401 || status === 403
    ? `接口访问失败（HTTP ${status}），请检查接口访问权限；目标可达性未知`
    : "未取得检测接口结果，请检查接口访问权限或连接；目标可达性未知";
}

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
  networkLastAttemptAt.value = submission.submittedAt;
  try {
    const result = normalizeNetworkDiagnosticResultForSubmission(await diagnoseNetwork(payload), submission);
    if (!isViewMounted) return;
    networkLastSuccessAt.value = result.completedAt ?? null;
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
  } catch (caught) {
    if (!isViewMounted) return;
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
    networkReadFailures.set(result, caught instanceof ApiRequestError ? caught.httpStatus ?? null : null);
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
    ElMessage.error(message);
  } finally {
    if (isViewMounted) {
      networkOperating.value = false;
    }
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
  if (!isViewMounted) return;
  applyRouteQuery();
  await deviceStore.refresh();
  if (!isViewMounted) return;
  applyRouteQuery();
}

onMounted(() => {
  isViewMounted = true;
  void initializeNetworkView();
});

onUnmounted(() => {
  isViewMounted = false;
});

watch(() => [route.query.target, route.query.port, route.query.type, route.query.deviceId], () => {
  applyRouteQuery();
});
</script>

<style scoped src="@/features/network/styles/network-view.css"></style>
