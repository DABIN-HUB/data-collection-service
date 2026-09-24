<template>
  <section class="exact-page cloud-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>云链路监控</h1>
        <span class="heading-online"><i></i>{{ cloudOperationalState.text }}</span>
      </div>
      <div class="heading-actions">
        <button type="button" class="primary" :disabled="loading" @click="refreshCloud">{{ loading ? '刷新中…' : '刷新链路' }}</button>
        <span class="heading-note">{{ lastRefreshText }}</span>
      </div>
    </div>

    <div class="exact-page-body">
      <div v-if="error" class="cloud-error">{{ cloudUnavailableText }}</div>

      <div class="exact-cloud-grid">
        <section class="exact-surface exact-cloud-status">
          <div class="exact-cloud-icon">云</div>
          <strong>{{ cloudOperationalState.text }}</strong>
          <small>{{ cloudEnabledText }}</small>
          <small>{{ cloudOperationalState.description }}</small>
          <small v-if="cloudOperationalState.disabledEvidence">禁用依据：{{ cloudOperationalState.disabledEvidence }}</small>
          <div class="cloud-stat-row">
            <span v-for="item in cloudSummaryCards" :key="item.label"><b>{{ item.value }}</b>{{ item.label }}</span>
          </div>
        </section>

        <section class="exact-surface">
          <div class="exact-surface-head">
            <h2>上报策略</h2>
            <span>{{ cloudOperationalState.status }}</span>
          </div>
          <div class="modao-property-grid">
            <div v-for="item in cloudStrategyRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
          </div>
        </section>
      </div>

      <section class="exact-surface">
        <div class="exact-surface-head">
          <h2>Outbox / ACK 明细</h2>
          <span>{{ cloudOperationalRows.length }} 项</span>
        </div>
        <div class="modao-property-grid">
          <div v-for="item in cloudOperationalRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
      </section>

      <section class="exact-surface">
        <div class="exact-surface-head"><h2>Outbox 消息</h2><span>{{ outboxRows.length }} 项</span></div>
        <div v-if="!appStore.capabilities?.cloud.monitoringAvailable" class="cloud-error">当前云链路监控不可用</div>
        <table v-else class="runtime-table"><thead><tr><th>消息</th><th>设备</th><th>状态</th><th>重试</th><th>错误</th><th>操作</th></tr></thead>
          <tbody><tr v-for="row in outboxRows" :key="row.messageId"><td>{{ row.messageId }}</td><td>{{ row.localDeviceId || "-" }}</td><td>{{ row.status }}</td><td>{{ row.retryCount ?? 0 }}</td><td>{{ row.lastError || "-" }}</td><td><button v-if="appStore.capabilities?.cloud.managementAvailable && row.status === 'ISOLATED'" @click="replay(row.messageId)">Replay</button></td></tr></tbody>
        </table>
        <div class="heading-actions"><button v-if="appStore.capabilities?.cloud.managementAvailable" @click="flush">Flush 到期消息</button><button v-if="appStore.capabilities?.cloud.managementAvailable" @click="testLink">测试云链路</button><span v-if="testResult">{{ testResult.message }}</span></div>
      </section>

      <section class="exact-surface">
        <div class="exact-surface-head">
          <h2>链路风险</h2>
          <span>{{ cloudRisks.length }} 项</span>
        </div>
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
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

import { getCloudReportMetrics } from "@/api/monitor.api";
import { flushCloudOutbox, listCloudOutbox, replayCloudOutbox, testCloudLink } from "@/api/cloud.api";
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
import type { CloudOutboxListItem, CloudTestResponse } from "@/types/cloud";

const appStore = useAppStore();
const reportMetrics = ref<CloudReportMetricsResponse | null>(null);
const loading = ref(false);
const error = ref("");
const lastRefresh = ref<Date | null>(null);
const outboxRows = ref<CloudOutboxListItem[]>([]);
const testResult = ref<CloudTestResponse | null>(null);

const cloudOperationalState = computed(() => classifyCloudOperationalState(reportMetrics.value, Boolean(error.value)));
const cloudEnabledText = computed(() => buildCloudEnabledText(reportMetrics.value));
const cloudSummaryCards = computed(() => buildCloudSummaryCards(reportMetrics.value));
const cloudStrategyRows = computed(() => buildCloudStrategyRows(reportMetrics.value));
const cloudOperationalRows = computed(() => buildCloudOperationalRows(reportMetrics.value));
const cloudRisks = computed(() => buildCloudRisks(reportMetrics.value));
const lastRefreshText = computed(() => lastRefresh.value ? `最后成功 ${lastRefresh.value.toLocaleTimeString()}` : "尚未刷新");
const cloudUnavailableText = computed(() => reportMetrics.value
  ? `${error.value}；当前保留最后一次成功指标（${lastRefreshText.value}）`
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
      outboxRows.value = await listCloudOutbox({ limit: 50 });
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

async function replay(messageId: string) {
  try {
    await ElMessageBox.confirm("该操作会重新进入云端发送链路，可能再次向云平台发送消息。", "确认 Replay", { type: "warning" });
    await replayCloudOutbox(messageId);
    await loadCloud();
    ElMessage.success("消息已重新进入发送队列");
  } catch { /* cancel or API error is already visible through the next refresh */ }
}

async function flush() {
  try {
    await ElMessageBox.confirm("将立即触发当前到期 Outbox 消息的调度，不代表云端已经确认。", "确认 Flush", { type: "warning" });
    await flushCloudOutbox();
    await loadCloud();
  } catch { /* user cancelled */ }
}

async function testLink() {
  testResult.value = await testCloudLink();
}

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}
</script>

<style scoped>
.cloud-view :deep(.heading-actions) {
  align-items: center;
}

.cloud-error {
  margin: 0 24px 16px;
  padding: 12px 16px;
  color: #fecaca;
  border: 1px solid rgba(248, 113, 113, 0.45);
  border-radius: 8px;
  background: rgba(127, 29, 29, 0.18);
}

.exact-cloud-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 16px;
}

.exact-cloud-status {
  display: flex;
  min-height: 100%;
  padding: 24px 20px;
  flex-direction: column;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 12px;
  background: linear-gradient(180deg, rgba(24, 44, 79, 0.95), rgba(15, 23, 42, 0.98));
}

.exact-cloud-icon {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border: 1px solid rgba(96, 165, 250, 0.45);
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.9), rgba(37, 99, 235, 0.65));
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.25);
}

.exact-cloud-status strong {
  color: #fff;
  font-size: 17px;
}

.exact-cloud-status small {
  margin-top: 2px;
  color: var(--exact-dim);
}

.cloud-stat-row {
  display: grid;
  width: 100%;
  margin-top: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.cloud-stat-row span {
  display: grid;
  padding: 10px 8px;
  gap: 5px;
  border: 1px solid var(--exact-border);
  border-radius: 7px;
  background: var(--exact-bg);
  color: var(--exact-dim);
  font-size: 10px;
}

.cloud-stat-row b {
  color: #e2e8f0;
  font-size: 15px;
}
</style>
