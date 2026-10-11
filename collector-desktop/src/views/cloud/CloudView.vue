<template>
  <section class="exact-page cloud-view" :data-state="cloudOperationalState.status">
    <div class="section-heading cloud-heading">
      <div>
        <div class="heading-title-line">
          <h1>云链路监控</h1>
          <span class="heading-online cloud-state"><i aria-hidden="true"></i>{{ cloudOperationalState.text }}</span>
        </div>
        <p class="cloud-heading-description">查看上报策略、ACK 提交与可靠发件箱；发送成功不等于云端确认。</p>
      </div>
      <div class="heading-actions">
        <button type="button" class="primary" :disabled="loading" @click="refreshCloud">{{ loading ? '刷新中…' : '刷新链路' }}</button>
        <span class="heading-note">{{ lastRefreshText }}</span>
      </div>
    </div>

    <div class="exact-page-body cloud-body">
      <div v-if="error" class="cloud-error">{{ cloudUnavailableText }}</div>

      <div class="exact-cloud-grid">
        <section class="exact-surface exact-cloud-status">
          <div class="exact-surface-head"><h2>上报链路</h2><span>{{ cloudMetricsRefreshText }}</span></div>
          <div class="cloud-status-content">
            <strong class="cloud-status-title"><i aria-hidden="true"></i>{{ cloudOperationalState.text }}</strong>
            <small class="cloud-enabled-text">{{ cloudEnabledText }}</small>
            <small class="cloud-status-description">{{ cloudOperationalState.description }}</small>
            <small v-if="cloudOperationalState.disabledEvidence" class="cloud-disabled-evidence">禁用依据：{{ cloudOperationalState.disabledEvidence }}</small>
          </div>
          <div class="cloud-stat-row">
            <span v-for="item in cloudSummaryCards" :key="item.label"><b>{{ item.value }}</b>{{ item.label }}</span>
          </div>
        </section>

        <section class="exact-surface cloud-strategy">
          <div class="exact-surface-head">
            <h2>上报策略</h2>
            <span>{{ cloudOperationalState.status }}</span>
          </div>
          <div class="modao-property-grid cloud-strategy-grid">
            <div v-for="item in cloudStrategyRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
          </div>
        </section>
      </div>

      <section class="exact-surface cloud-metrics">
        <div class="exact-surface-head">
          <h2>Outbox / ACK 指标</h2>
          <span>{{ cloudMetricsRefreshText }}</span>
        </div>
        <div class="modao-property-grid cloud-metric-rail">
          <div v-for="item in cloudOperationalRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
      </section>

      <section class="exact-surface cloud-outbox">
        <div class="exact-surface-head"><h2>Outbox 消息</h2><span>{{ outboxLastSuccess ? `${outboxRows.length} 项` : '数量未知' }} · {{ outboxRefreshText }}</span></div>
        <div class="heading-actions cloud-filters">
          <label class="cloud-filter-field">
            <span>Outbox 状态</span>
            <select v-model="outboxStatus" aria-label="Outbox 状态" @change="loadCloud">
              <option value="">全部状态</option>
              <option v-for="status in outboxStatuses" :key="status" :value="status">{{ status }}</option>
            </select>
          </label>
          <label class="cloud-filter-field cloud-device-filter">
            <span>本地设备 ID</span>
            <input v-model="outboxDeviceId" aria-label="本地设备 ID" placeholder="本地设备 ID" @keyup.enter="loadCloud" />
          </label>
          <button type="button" :disabled="loading" @click="loadCloud">筛选</button>
        </div>
        <div v-if="!appStore.capabilities?.cloud.monitoringAvailable" class="cloud-error">当前云链路监控不可用</div>
        <div v-else class="cloud-table-scroll" tabindex="0" role="region" aria-label="Outbox 消息队列，可横向和纵向滚动">
          <table class="runtime-table cloud-table" aria-label="Outbox 消息">
            <colgroup><col class="cloud-col-message" /><col class="cloud-col-device" /><col class="cloud-col-status" /><col class="cloud-col-retry" /><col class="cloud-col-time" /><col class="cloud-col-time" /><col class="cloud-col-error" /><col class="cloud-col-actions" /></colgroup>
            <thead><tr><th scope="col">消息</th><th scope="col">设备</th><th scope="col">状态</th><th scope="col">重试</th><th scope="col">创建时间</th><th scope="col">下次尝试</th><th scope="col">错误</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in outboxRows" :key="row.messageId">
                <td>{{ row.messageId }}</td><td>{{ row.localDeviceId || "-" }}</td><td><span class="cloud-row-state">{{ row.status }}</span></td><td>{{ row.retryCount ?? 0 }}</td><td>{{ formatTimestamp(row.createdAt) }}</td><td>{{ formatTimestamp(row.nextAttemptAt) }}</td><td class="cloud-message-error">{{ row.lastError || "-" }}</td>
                <td><div class="cloud-row-actions"><button type="button" @click="openDetail(row.messageId)">查看详情</button><button v-if="appStore.capabilities?.cloud.managementAvailable && row.status === 'ISOLATED'" type="button" class="cloud-risk-action" @click="replay(row.messageId)">重新发送</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="appStore.capabilities?.cloud.monitoringAvailable && !loading && !outboxRows.length" class="cloud-empty">{{ outboxLastSuccess ? '暂无 Outbox 消息' : '队列尚未成功加载，请刷新重试' }}</div>
        <div v-if="actionError" class="cloud-error" role="alert">{{ actionError }}</div>
        <div class="cloud-management">
          <p class="cloud-operation-note">重新发送（Replay）会再次进入发送链路；调度到期消息（Flush）不代表云端已确认。</p>
          <div class="heading-actions cloud-management-actions"><button v-if="appStore.capabilities?.cloud.managementAvailable" class="cloud-risk-action" @click="flush">调度到期消息</button><button v-if="appStore.capabilities?.cloud.managementAvailable" @click="testLink">测试云链路</button><span v-if="testResult" class="cloud-test-result">{{ testResult.message }}</span></div>
        </div>
      </section>

      <el-drawer v-model="detailVisible" class="cloud-detail-drawer" :title="`Outbox 消息 ${selectedMessageId}`" size="min(720px, 90vw)">
        <div v-if="detailLoading">详情加载中…</div>
        <div v-else-if="detailError" class="cloud-error" role="alert">{{ detailError }}</div>
        <div v-else-if="!detail" class="cloud-empty">消息详情为空</div>
        <template v-else>
          <div class="modao-property-grid">
            <div v-for="item in detailFields" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
          </div>
          <details class="exact-json-panel"><summary>查看上报快照 JSON</summary><pre class="json-view">{{ prettyJson(detail.reportData) }}</pre></details>
          <details class="exact-json-panel"><summary>查看提交记录 JSON</summary><pre class="json-view">{{ prettyJson(detail.commits) }}</pre></details>
        </template>
      </el-drawer>

      <section class="exact-surface cloud-risks">
        <div class="exact-surface-head">
          <h2>链路风险</h2>
          <span>{{ cloudRisks.length }} 项</span>
        </div>
        <p class="cloud-risk-note">风险项沿用上报指标；状态未知或接口不可用时，不作为链路健康证明。</p>
        <div class="modao-risk-list">
          <div v-for="risk in cloudRisks" :key="risk" class="modao-risk-item"><strong>{{ cloudRisks.length ? '风险' : '检查结果' }}</strong><small>{{ risk }}</small></div>
        </div>
      </section>

      <details class="exact-json-panel">
        <summary>查看原始上报链路 JSON</summary>
        <pre class="json-view">{{ prettyJson(reportMetrics) }}</pre>
      </details>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ElDrawer, ElMessage, ElMessageBox } from "element-plus";

import { getCloudReportMetrics } from "@/api/monitor.api";
import { flushCloudOutbox, getCloudOutboxDetail, listCloudOutbox, replayCloudOutbox, testCloudLink } from "@/api/cloud.api";
import {
  buildCloudEnabledText,
  buildCloudOperationalRows,
  buildCloudRisks,
  buildCloudStrategyRows,
  buildCloudSummaryCards,
  classifyCloudOperationalState
} from "@/features/cloud/utils/cloud-report-utils";
import { useAppStore } from "@/stores/app.store";
import type { CloudReportMetricsResponse } from "@/types/monitor";
import type { CloudOutboxDetail, CloudOutboxListItem, CloudTestResponse } from "@/types/cloud";

const appStore = useAppStore();
const reportMetrics = ref<CloudReportMetricsResponse | null>(null);
const loading = ref(false);
const error = ref("");
const lastRefresh = ref<Date | null>(null);
const outboxRows = ref<CloudOutboxListItem[]>([]);
const outboxStatuses = ["PENDING", "PUBLISHING", "WAITING_ACK", "WAITING_CONFIG", "ISOLATED"] as const;
const outboxStatus = ref("");
const outboxDeviceId = ref("");
const testResult = ref<CloudTestResponse | null>(null);
const actionError = ref("");
const detailVisible = ref(false);
const selectedMessageId = ref("");
const detailLoading = ref(false);
const detailError = ref("");
const detail = ref<CloudOutboxDetail | null>(null);
const metricsLastSuccess = ref<Date | null>(null);
const outboxLastSuccess = ref<Date | null>(null);
let detailGeneration = 0;
onUnmounted(() => { detailGeneration++; });

const detailFields = computed(() => detail.value ? [
  { label: "消息 ID", value: detail.value.summary.messageId },
  { label: "本地设备", value: detail.value.summary.localDeviceId || "-" },
  { label: "云端设备", value: detail.value.summary.cloudDeviceId || "-" },
  { label: "网关设备", value: detail.value.summary.gatewayDeviceId || "-" },
  { label: "状态", value: detail.value.summary.status || "-" },
  { label: "重试次数", value: String(detail.value.summary.retryCount ?? 0) },
  { label: "创建时间", value: formatTimestamp(detail.value.summary.createdAt) },
  { label: "下次尝试", value: formatTimestamp(detail.value.summary.nextAttemptAt) },
  { label: "窗口开始", value: formatTimestamp(detail.value.windowStart) },
  { label: "窗口结束", value: formatTimestamp(detail.value.windowEnd) },
  { label: "影子版本", value: String(detail.value.shadowVersion ?? "-") },
  { label: "最后错误", value: detail.value.summary.lastError || "-" }
] : []);

const cloudOperationalState = computed(() => classifyCloudOperationalState(reportMetrics.value, Boolean(error.value)));
const cloudEnabledText = computed(() => buildCloudEnabledText(reportMetrics.value));
const cloudSummaryCards = computed(() => buildCloudSummaryCards(reportMetrics.value));
const cloudStrategyRows = computed(() => buildCloudStrategyRows(reportMetrics.value));
const cloudOperationalRows = computed(() => buildCloudOperationalRows(reportMetrics.value));
const cloudRisks = computed(() => buildCloudRisks(reportMetrics.value));
const lastRefreshText = computed(() => lastRefresh.value ? `刷新结束 ${lastRefresh.value.toLocaleTimeString()}` : "尚未刷新");
const cloudMetricsRefreshText = computed(() => metricsLastSuccess.value ? `指标最近读取 ${formatRefreshTime(metricsLastSuccess.value)}` : "指标未成功读取");
const outboxRefreshText = computed(() => outboxLastSuccess.value ? `队列最近读取 ${formatRefreshTime(outboxLastSuccess.value)}` : "队列未成功读取");
const cloudUnavailableText = computed(() => reportMetrics.value
  ? `${error.value}；当前保留最后一次成功指标（${cloudMetricsRefreshText.value}）`
  : error.value);

onMounted(() => {
  void loadCloud();
});

async function loadCloud() {
  loading.value = true;
  error.value = "";
  try {
    await appStore.initialize();
    if (appStore.capabilities?.cloud.monitoringAvailable === true) {
      reportMetrics.value = await getCloudReportMetrics();
      metricsLastSuccess.value = new Date();
      outboxRows.value = await listCloudOutbox({
        status: outboxStatus.value || undefined,
        deviceId: outboxDeviceId.value.trim() || undefined,
        limit: 50
      });
      outboxLastSuccess.value = new Date();
    }
    lastRefresh.value = new Date();
  } catch (err) {
    error.value = err instanceof Error ? err.message : "云上报链路加载失败";
  } finally {
    loading.value = false;
  }
}

async function refreshCloud() {
  await loadCloud();
}

async function openDetail(messageId: string) {
  const generation = ++detailGeneration;
  selectedMessageId.value = messageId;
  detailVisible.value = true;
  detail.value = null;
  detailError.value = "";
  detailLoading.value = true;
  try {
    const result = await getCloudOutboxDetail(messageId);
    if (generation === detailGeneration) detail.value = result;
  } catch (err) {
    if (generation === detailGeneration) detailError.value = errorMessage(err, "消息详情加载失败");
  } finally {
    if (generation === detailGeneration) detailLoading.value = false;
  }
}

function errorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === "object" && "machineCode" in err
    && (err as { machineCode?: unknown }).machineCode === "CLOUD_OUTBOX_STATE_CONFLICT") {
    return "消息状态已发生变化，请刷新列表后重试";
  }
  return err instanceof Error ? err.message : fallback;
}

function formatTimestamp(timestamp?: number): string {
  return timestamp == null ? "-" : new Date(timestamp).toLocaleString();
}

function formatRefreshTime(value: Date): string {
  return value.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

async function replay(messageId: string) {
  try {
    await ElMessageBox.confirm("该操作会重新进入云端发送链路，可能再次向云平台发送消息。", "确认 Replay", { type: "warning" });
  } catch { return; }
  actionError.value = "";
  try {
    await replayCloudOutbox(messageId);
    await loadCloud();
    ElMessage.success("消息已重新进入发送队列");
  } catch (err) {
    actionError.value = `消息 ${messageId} 重放失败：${errorMessage(err, "请求失败")}`;
  }
}

async function flush() {
  try {
    await ElMessageBox.confirm("将立即触发当前到期 Outbox 消息的调度，不代表云端已经确认。", "确认 Flush", { type: "warning" });
  } catch { return; }
  actionError.value = "";
  try {
    await flushCloudOutbox();
    await loadCloud();
  } catch (err) {
    actionError.value = `Outbox 调度失败：${errorMessage(err, "请求失败")}`;
  }
}

async function testLink() {
  actionError.value = "";
  testResult.value = null;
  try {
    testResult.value = await testCloudLink();
  } catch (err) {
    actionError.value = `云链路测试失败：${errorMessage(err, "请求失败")}`;
  }
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}
</script>

<style scoped src="@/features/cloud/styles/cloud-view.css"></style>
