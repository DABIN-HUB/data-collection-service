<template>
  <section class="exact-page cloud-view" :data-state="cloudOperationalState.status" :data-read-state="metricsSourceState" :data-business-status="cloudRawStatusText">
    <div class="section-heading cloud-heading">
      <div>
        <div class="heading-title-line">
          <h1>云链路监控</h1>
          <span class="heading-online cloud-state"><i aria-hidden="true"></i>{{ cloudOperationalState.text }}<small>{{ cloudRawStatusText || cloudOperationalState.status }}</small></span>
        </div>
        <p class="cloud-heading-description">查看上报策略、云端确认（ACK）与可靠发件箱（Outbox）；发送成功不等于云端确认。</p>
      </div>
      <div class="heading-actions">
        <button type="button" class="primary" :disabled="loading" @click="refreshCloud">{{ loading ? '刷新中…' : '刷新链路' }}</button>
        <span class="heading-note">{{ lastRefreshText }}</span>
      </div>
    </div>

    <div class="exact-page-body cloud-body">
      <div v-if="error" class="cloud-error" role="alert">{{ cloudUnavailableText }}</div>
      <section class="cloud-summary-strip" aria-label="上报链路摘要">
        <div class="cloud-stat-row">
          <span v-for="item in cloudSummaryCards" :key="item.label"><b>{{ item.value }}</b>{{ item.label }}<small>来源：上报链路指标</small></span>
        </div>
      </section>

      <div class="cloud-workbench">
      <div class="cloud-primary">
      <section id="cloud-outbox" class="exact-surface cloud-outbox" tabindex="-1" aria-labelledby="cloud-outbox-title">
        <div class="exact-surface-head"><h2 id="cloud-outbox-title">可靠发件箱消息（Outbox）</h2><span>{{ outboxLastSuccess ? `${outboxRows.length} 项` : '数量未知' }} · {{ outboxRefreshText }}</span></div>
        <OperationSourceStatus :state="outboxSourceState" source="可靠发件箱消息列表" :last-attempt-at="outboxLastAttemptAt" :last-success-at="outboxLastSuccess?.getTime()" :detail="outboxSourceDetail" />
        <div class="heading-actions cloud-filters">
          <label class="cloud-filter-field">
            <span>发件箱状态</span>
            <select v-model="outboxStatus" aria-label="可靠发件箱状态" @change="loadCloud">
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
        <div v-if="!appStore.capabilities?.cloud.monitoringAvailable" class="cloud-error">{{ appStore.capabilities?.cloud.monitoringAvailable === false ? '当前监控能力未开放，未发起指标或队列请求。' : '监控能力未知，请检查接口访问权限或连接。' }}</div>
        <div v-else class="cloud-table-scroll" tabindex="0" role="region" aria-label="可靠发件箱消息队列，可横向和纵向滚动">
          <table class="runtime-table cloud-table" aria-label="可靠发件箱消息">
            <colgroup><col class="cloud-col-message" /><col class="cloud-col-device" /><col class="cloud-col-status" /><col class="cloud-col-retry" /><col class="cloud-col-time" /><col class="cloud-col-time" /><col class="cloud-col-error" /><col class="cloud-col-actions" /></colgroup>
            <thead><tr><th scope="col">消息</th><th scope="col">设备</th><th scope="col">状态</th><th scope="col">重试</th><th scope="col">创建时间</th><th scope="col">下次尝试</th><th scope="col">错误</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in outboxRows" :key="row.messageId">
                <td>{{ row.messageId }}</td><td>{{ row.localDeviceId || "-" }}</td><td><span class="cloud-row-state">{{ row.status ?? '未知' }}</span></td><td>{{ row.retryCount != null && row.retryCount >= 0 ? row.retryCount : '未知' }}</td><td>{{ formatTimestamp(row.createdAt) }}</td><td>{{ formatTimestamp(row.nextAttemptAt) }}</td><td class="cloud-message-error">{{ row.lastError || "-" }}</td>
                <td><div class="cloud-row-actions"><button type="button" @click="openDetail(row.messageId)">查看详情</button><button v-if="appStore.capabilities?.cloud.managementAvailable && row.status === 'ISOLATED'" type="button" class="cloud-risk-action" @click="replay(row.messageId)">重新发送</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="appStore.capabilities?.cloud.monitoringAvailable && !loading && !outboxRows.length" class="cloud-empty">{{ outboxReadState === 'error' ? outboxLastSuccess ? '本次队列读取失败；上次成功列表为空，请刷新重试' : '队列读取失败，请刷新重试' : outboxLastSuccess ? '已读取：该次筛选暂无发件箱消息' : '队列尚未成功加载，请刷新重试' }}</div>
        <div v-if="actionError" class="cloud-error" role="alert">{{ actionError }}</div>
        <details class="cloud-management-tools">
          <summary>管理操作与链路测试</summary>
        <div class="cloud-management">
          <p class="cloud-operation-note">重新发送（Replay）会再次进入发送链路；调度到期消息（Flush）不代表云端已确认。</p>
          <div class="heading-actions cloud-management-actions"><button v-if="appStore.capabilities?.cloud.managementAvailable" class="cloud-risk-action" @click="flush">调度到期消息</button><button v-if="appStore.capabilities?.cloud.managementAvailable" @click="testLink">测试云链路</button><span v-if="testResult" class="cloud-test-result">{{ testResult.message }}</span></div>
        </div>
        </details>
      </section>

      <section id="cloud-metrics" class="exact-surface cloud-metrics" tabindex="-1" aria-labelledby="cloud-metrics-title">
        <div class="exact-surface-head">
          <h2 id="cloud-metrics-title">可靠发件箱 / 云端确认指标</h2>
          <span>{{ cloudMetricsRefreshText }}</span>
        </div>
        <OperationSourceStatus id="cloud-metric-source" tabindex="-1" :state="metricsSourceState" source="上报链路指标（Outbox / ACK）" :last-attempt-at="metricsLastAttemptAt" :last-success-at="metricsLastSuccess?.getTime()" :detail="metricsSourceDetail" />
        <div class="modao-property-grid cloud-metric-rail">
          <div v-for="item in cloudOperationalRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
      </section>
      </div>

      <aside class="cloud-aside" aria-label="策略与链路风险">
      <div class="cloud-risk-overview" :data-risk="cloudRisks.length ? 'known' : 'unknown'" aria-label="风险与只读下一步">
        <div><strong>{{ cloudRiskOverviewTitle }}</strong><p>{{ cloudRiskOverviewReason }}</p></div>
        <div class="cloud-read-links">
          <a v-if="cloudRisks.length" href="#cloud-risk-list" @click.prevent="scrollToCloudSection('#cloud-risk-list')">查看全部风险</a>
          <template v-if="cloudRisks.length"><RouterLink v-if="cloudRisks[0].to" :to="cloudRisks[0].to">{{ cloudRisks[0].entry }}</RouterLink><a v-else :href="cloudRisks[0].href" @click.prevent="scrollToCloudSection(cloudRisks[0].href)">{{ cloudRisks[0].entry }}</a></template>
          <RouterLink v-else to="/diagnostic">查看系统诊断</RouterLink>
          <a v-if="!cloudRisks.length" href="#cloud-metric-source" @click.prevent="scrollToCloudSection('#cloud-metric-source')">查看指标来源</a>
        </div>
      </div>

      <section class="exact-surface cloud-strategy">
        <div class="exact-surface-head"><h2>上报策略</h2><span>来源：上报链路指标</span></div>
        <div class="modao-property-grid cloud-strategy-grid">
          <div v-for="item in cloudStrategyRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
        </div>
      </section>


      <section id="cloud-risk-list" class="exact-surface cloud-risks" tabindex="-1" aria-labelledby="cloud-risk-title">
        <div class="exact-surface-head">
          <h2 id="cloud-risk-title">链路风险</h2>
          <span>{{ cloudRiskCountText }}</span>
        </div>
        <p class="cloud-risk-note">风险项沿用上报指标；状态未知或接口不可用时，不作为链路健康证明。</p>
        <div class="modao-risk-list">
          <div v-for="(risk, index) in cloudRisks" :key="index" class="modao-risk-item"><strong>{{ risk.object }}</strong><div><p>{{ risk.reason }}</p><RouterLink v-if="risk.to" :to="risk.to">{{ risk.entry }}</RouterLink><a v-else :href="risk.href" @click.prevent="scrollToCloudSection(risk.href)">{{ risk.entry }}</a></div></div>
          <div v-if="!cloudRisks.length" class="modao-risk-item"><strong>{{ cloudRiskAssessmentKnown ? '检查结果' : '尚未评估' }}</strong><div><p>{{ cloudRiskOverviewReason }}</p><a href="#cloud-metric-source" @click.prevent="scrollToCloudSection('#cloud-metric-source')">查看读取来源</a><RouterLink to="/diagnostic">查看系统诊断</RouterLink></div></div>
        </div>
      </section>

      <details class="exact-json-panel cloud-chain-detail">
        <summary>上报链路与读取说明</summary>
        <section class="exact-cloud-status">
          <div class="cloud-status-content">
            <strong class="cloud-status-title"><i aria-hidden="true"></i>{{ cloudOperationalState.text }}</strong>
            <small class="cloud-enabled-text">{{ cloudEnabledText }}</small>
            <small class="cloud-status-description">{{ cloudOperationalState.description }}</small>
            <small v-if="cloudOperationalState.disabledEvidence" class="cloud-disabled-evidence">禁用依据：{{ cloudOperationalState.disabledEvidence }}</small>
          </div>
          <div class="cloud-chain" aria-label="上报链路读取来源">
            <div class="cloud-chain-node"><span class="cloud-chain-index">01</span><div><strong>采集数据</strong><small>{{ metricsSourceState === 'ready' ? '来源已读取' : metricsSourceState === 'loading' ? '读取中' : metricsSourceState === 'error' ? '读取失败' : '状态未知' }}</small></div></div>
            <span class="cloud-chain-arrow" aria-hidden="true">→</span>
            <div class="cloud-chain-node"><span class="cloud-chain-index">02</span><div><strong>可靠发件箱</strong><small>{{ outboxSourceState === 'ready' ? '列表已读取' : outboxSourceState === 'loading' ? '读取中' : outboxSourceState === 'error' ? '读取失败' : '状态未知' }}</small></div></div>
            <span class="cloud-chain-arrow" aria-hidden="true">→</span>
            <div class="cloud-chain-node"><span class="cloud-chain-index">03</span><div><strong>上报指标状态</strong><small>{{ cloudRawStatusText || '确认状态未知' }}</small></div></div>
          </div>
          <p class="cloud-operation-note">指标已读取、发送成功与云端确认是不同事实；云端 ACK 以独立指标为准。</p>
        </section>
      </details>

      <details class="exact-json-panel">
        <summary>查看原始上报链路 JSON</summary>
        <pre class="json-view" tabindex="0" aria-label="完整上报链路 JSON，可滚动查看">{{ prettyJson(reportMetrics) }}</pre>
      </details>
      </aside>
      </div>

      <el-drawer v-model="detailVisible" class="cloud-detail-drawer" :title="`可靠发件箱消息 ${selectedMessageId}`" size="min(720px, 90vw)">
        <div v-if="detailLoading">详情加载中…</div>
        <div v-else-if="detailError" class="cloud-error" role="alert">{{ detailError }}</div>
        <div v-else-if="!detail" class="cloud-empty">消息详情为空</div>
        <template v-else>
          <div class="modao-property-grid">
            <div v-for="item in detailFields" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
          </div>
          <details class="exact-json-panel"><summary>查看上报快照 JSON</summary><pre class="json-view" tabindex="0" aria-label="完整上报快照 JSON，可滚动查看">{{ prettyJson(detail.reportData) }}</pre></details>
          <details class="exact-json-panel"><summary>查看提交记录 JSON</summary><pre class="json-view" tabindex="0" aria-label="完整提交记录 JSON，可滚动查看">{{ prettyJson(detail.commits) }}</pre></details>
        </template>
      </el-drawer>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { ElDrawer, ElMessage, ElMessageBox } from "element-plus";

import { getCloudReportMetrics } from "@/api/monitor.api";
import { flushCloudOutbox, getCloudOutboxDetail, listCloudOutbox, replayCloudOutbox, testCloudLink } from "@/api/cloud.api";
import OperationSourceStatus from "@/components/ui/OperationSourceStatus.vue";
import {
  buildCloudEnabledText,
  buildCloudOperationalRows,
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
type ReadState = "unknown" | "loading" | "ready" | "error";
const pageLastAttemptAt = ref<number | null>(null);
const metricsLastAttemptAt = ref<number | null>(null);
const outboxLastAttemptAt = ref<number | null>(null);
const metricsReadState = ref<ReadState>("unknown");
const outboxReadState = ref<ReadState>("unknown");
const failedSource = ref<"" | "capability" | "metrics" | "outbox">("");
const outboxLoadedContext = ref<{ status: string; deviceId: string } | null>(null);
let detailGeneration = 0;
let cloudLoadGeneration = 0;
onUnmounted(() => { detailGeneration++; cloudLoadGeneration++; });

const detailFields = computed(() => detail.value ? [
  { label: "消息 ID", value: detail.value.summary.messageId },
  { label: "本地设备", value: detail.value.summary.localDeviceId || "-" },
  { label: "云端设备", value: detail.value.summary.cloudDeviceId || "-" },
  { label: "网关设备", value: detail.value.summary.gatewayDeviceId || "-" },
  { label: "状态", value: detail.value.summary.status || "-" },
  { label: "重试次数", value: String(detail.value.summary.retryCount != null && detail.value.summary.retryCount >= 0 ? detail.value.summary.retryCount : "未知") },
  { label: "创建时间", value: formatTimestamp(detail.value.summary.createdAt) },
  { label: "下次尝试", value: formatTimestamp(detail.value.summary.nextAttemptAt) },
  { label: "窗口开始", value: formatTimestamp(detail.value.windowStart) },
  { label: "窗口结束", value: formatTimestamp(detail.value.windowEnd) },
  { label: "影子版本", value: String(detail.value.shadowVersion ?? "-") },
  { label: "最后错误", value: detail.value.summary.lastError || "-" }
] : []);

const cloudRiskAssessmentKnown = computed(() => Boolean(metricsLastSuccess.value && reportMetrics.value && Array.isArray(reportMetrics.value.risks)));
const cloudRawStatusText = computed(() => reportMetrics.value ? String(reportMetrics.value.status ?? reportMetrics.value.state ?? "") : "");
const cloudOperationalState = computed(() => {
  const state = classifyCloudOperationalState(reportMetrics.value, metricsReadState.value === "error");
  if (!reportMetrics.value) return { ...state, text: metricsReadState.value === "error" ? "指标读取失败" : "链路状态未知", description: "指标尚未成功读取，不能判断链路健康；查看来源或系统诊断。" };
  if (appStore.capabilities?.cloud.monitoringAvailable !== true || failedSource.value === "capability") return { ...state, status: "UNAVAILABLE", text: "监控不可用", description: "本轮未读取指标或队列，已有值只属于之前的成功来源。", disabledEvidence: "" };
  if (metricsReadState.value !== "error" && ["ERROR", "FAILED", "DOWN"].includes(cloudRawStatusText.value.toUpperCase()) && state.status !== "DISABLED") return { ...state, text: "链路异常", description: `上报指标明确返回 ${cloudRawStatusText.value}；请只读查看系统诊断或相关指标。` };
  if (state.status === "READY") {
    const explicit = String(reportMetrics.value.status ?? reportMetrics.value.state ?? "").toUpperCase();
    if (!cloudRiskAssessmentKnown.value || !["OK", "UP", "ONLINE", "SUCCESS", "READY"].includes(explicit)) {
      return { ...state, status: "UNAVAILABLE", text: "指标已读取，链路状态未知", description: "已读取不等于连接或云端确认正常；缺少明确健康证据时保持未知。" };
    }
  }
  if (failedSource.value === "outbox" && state.status !== "DISABLED") return { ...state, status: "DEGRADED", text: "队列读取部分不可用", description: "本轮指标已读取，但消息列表失败；指标与队列成功时间独立。" };
  return state;
});
const cloudEnabledText = computed(() => buildCloudEnabledText(reportMetrics.value));
const localizeMetricRow = (item: { label: string; value: string }) => ({
  label: item.label.replace("待 ACK", "待云端确认（ACK）").replace("ACK 成功", "云端确认成功").replace("ACK 失败", "云端确认失败").replace("ACK 提交点", "云端确认提交点").replace("ACK 超时", "云端确认超时"),
  value: !metricsLastSuccess.value || item.value === "-" || /^-\d/.test(item.value) ? "未知" : item.value
});
const cloudSummaryCards = computed(() => buildCloudSummaryCards(reportMetrics.value).map(localizeMetricRow));
const cloudOperationalRows = computed(() => buildCloudOperationalRows(reportMetrics.value).map(localizeMetricRow));
const cloudStrategyRows = computed(() => {
  const report = reportMetrics.value;
  const configured = report?.configured;
  const batch = report?.batch;
  const ack = report?.ack;
  const outbox = report?.outbox;
  const providerKey = report?.cloudProvider != null ? "cloudProvider" : "provider";
  return [
    { label: "总开关", value: displayEnabled(report, "enabled") },
    { label: "上报模式", value: displayField(report, "mode") },
    { label: "云服务商", value: displayField(report, providerKey) },
    { label: "可上报点位", value: `${displayField(configured, "reportablePointCount")} / ${displayField(configured, "pointCount")}` },
    { label: "批量聚合", value: batch?.enabled === true ? `已启用，最多 ${displayField(batch, "maxPropertiesPerPack")} 属性` : displayEnabled(batch, "enabled") },
    { label: "云端确认提交点", value: displayField(ack, "commitOn") },
    { label: "云端确认超时", value: displayField(ack, "timeoutMs", " ms") },
    { label: "可靠发件箱（Outbox）", value: displayEnabled(outbox, "enabled") }
  ];
});
const cloudRisks = computed(() => cloudRiskAssessmentKnown.value
  ? (reportMetrics.value?.risks ?? []).filter((risk) => risk !== "未发现已知上报风险").map((reason) => {
    if (/隔离|ISOLATED|发件箱|outbox|待发送/i.test(reason)) return { object: "可靠发件箱", reason, href: "#cloud-outbox", to: "", entry: "查看消息队列" };
    if (/ACK|确认/i.test(reason)) return { object: "云端确认", reason, href: "#cloud-metrics", to: "", entry: "查看确认指标" };
    if (/日志|log/i.test(reason)) return { object: "上报链路", reason, href: "", to: "/log", entry: "查看运行日志" };
    return { object: "上报链路", reason, href: "", to: "/diagnostic", entry: "查看系统诊断" };
  }) : []);
const cloudRiskCountText = computed(() => !cloudRiskAssessmentKnown.value ? "尚未评估" : `${cloudRisks.value.length} 项已知风险${metricsSourceState.value === "degraded" ? "（上次成功数据）" : ""}`);
const cloudRiskOverviewTitle = computed(() => cloudRisks.value.length ? `${cloudRisks.value[0].object}需关注${metricsSourceState.value === "degraded" ? "（上次成功数据）" : ""}` : cloudRiskAssessmentKnown.value ? metricsSourceState.value === "degraded" ? "上次成功响应未列出已知风险" : "未发现已知上报风险" : "风险尚未评估");
const cloudRiskOverviewReason = computed(() => cloudRisks.value.length
  ? `${metricsSourceState.value === "degraded" ? "上次成功指标：" : ""}${cloudRisks.value[0].reason}`
  : !metricsLastSuccess.value || !reportMetrics.value ? "指标未成功读取，请使用“刷新链路”重试，或只读查看系统诊断。"
    : !cloudRiskAssessmentKnown.value ? "指标响应未提供风险列表，风险仍未知；已读取不代表链路健康。"
      : metricsSourceState.value === "degraded" ? "本轮指标未成功重读；上次成功响应未列出已知风险，不代表当前无风险。" : "成功指标响应未列出已知风险；缺失字段仍显示未知，不作为连接或云端确认健康证明。"
);
const lastRefreshText = computed(() => lastRefresh.value && lastRefresh.value.getTime() >= (pageLastAttemptAt.value ?? 0) ? `本轮检查结束 ${formatRefreshTime(lastRefresh.value)}` : pageLastAttemptAt.value ? `最后检查尝试 ${formatRefreshTime(new Date(pageLastAttemptAt.value))}` : "尚未检查");
const cloudMetricsRefreshText = computed(() => metricsLastSuccess.value ? `指标最近读取 ${formatRefreshTime(metricsLastSuccess.value)}` : "指标未成功读取");
const outboxRefreshText = computed(() => outboxLastSuccess.value ? `队列最近读取 ${formatRefreshTime(outboxLastSuccess.value)}` : "队列未成功读取");
const cloudUnavailableText = computed(() => failedSource.value === "outbox"
  ? outboxLastSuccess.value ? "消息队列读取失败，本轮指标成功时间不受影响；保留上次队列结果，请检查接口访问权限或连接。" : "消息队列读取失败且尚无成功列表；本轮指标成功时间不受影响，请检查接口访问权限或连接。"
  : failedSource.value === "metrics" ? reportMetrics.value ? "本次指标读取失败，当前显示上次成功指标；本轮未继续读取队列。" : "指标读取失败，链路与风险未知；请检查接口访问权限或连接。"
    : "监控能力检查失败，请检查接口访问权限或连接；不能据此判断云链路健康。"
);
const metricsSourceState = computed(() => metricsReadState.value === "error" ? metricsLastSuccess.value ? "degraded" : "error" : metricsLastSuccess.value && (failedSource.value === "capability" || appStore.capabilities?.cloud.monitoringAvailable !== true) ? "degraded" : metricsReadState.value);
const outboxSourceState = computed(() => outboxReadState.value === "error" ? outboxLastSuccess.value ? "degraded" : "error" : outboxLastSuccess.value && (failedSource.value === "metrics" || failedSource.value === "capability" || appStore.capabilities?.cloud.monitoringAvailable !== true) ? "degraded" : outboxReadState.value === "ready" && !outboxRows.value.length ? "empty" : outboxReadState.value);
const metricsSourceDetail = computed(() => appStore.capabilities?.cloud.monitoringAvailable !== true
  ? "监控能力未确认可用，本轮未发起指标请求；已有值只属于之前的成功来源。"
  : metricsReadState.value === "error" ? "本次指标读取失败；成功时间未推进，已有指标为上次成功数据。" : "只对应上报指标读取，不证明消息投递或云端确认成功。"
);
const outboxSourceDetail = computed(() => {
  const loaded = outboxLoadedContext.value;
  const source = loaded ? `显示中的成功筛选：状态 ${loaded.status || "全部"}，设备 ${loaded.deviceId || "全部"}。` : "队列尚未成功读取。";
  const currentChanged = loaded && (loaded.status !== outboxStatus.value || loaded.deviceId !== outboxDeviceId.value.trim());
  const note = failedSource.value === "metrics" || failedSource.value === "capability" ? "本轮前置读取失败，未继续请求队列。" : outboxReadState.value === "error" ? "本次队列读取失败，保留上次成功数据。" : "最多读取 50 条消息，队列成功不代表指标或云端确认成功。";
  return `${source}${currentChanged ? "当前筛选已变更，现有列表不属于当前条件。" : ""}${note}`;
});

function displayField(source: Record<string, unknown> | null | undefined, key: string, suffix = ""): string {
  if (!metricsLastSuccess.value || !source || !Object.hasOwn(source, key)) return "未知";
  const value = source[key];
  if (value == null || value === "" || typeof value === "number" && (!Number.isFinite(value) || value < 0)) return "未知";
  return `${String(value)}${suffix}`;
}

function displayEnabled(source: Record<string, unknown> | null | undefined, key: string): string {
  if (!metricsLastSuccess.value || !source || !Object.hasOwn(source, key)) return "未知";
  return source[key] === true ? "已启用" : source[key] === false ? "未启用" : "未知";
}

function scrollToCloudSection(anchor: string) {
  // Hash Router 保持当前路由；只滚动并聚焦本页只读区域。
  const target = document.getElementById(anchor.replace(/^#/, ""));
  target?.scrollIntoView({ block: "start" });
  target?.focus({ preventScroll: true });
}

onMounted(() => {
  void loadCloud();
});

async function loadCloud() {
  const generation = ++cloudLoadGeneration;
  loading.value = true;
  error.value = "";
  pageLastAttemptAt.value = Date.now();
  failedSource.value = "";
  let readingSource: "capability" | "metrics" | "outbox" = "capability";
  try {
    await appStore.initialize();
    if (generation !== cloudLoadGeneration) return;
    if (appStore.capabilities?.cloud.monitoringAvailable === true) {
      readingSource = "metrics";
      metricsLastAttemptAt.value = Date.now();
      metricsReadState.value = "loading";
      const nextMetrics = await getCloudReportMetrics();
      if (generation !== cloudLoadGeneration) return;
      reportMetrics.value = nextMetrics;
      metricsLastSuccess.value = new Date();
      metricsReadState.value = "ready";
      readingSource = "outbox";
      outboxLastAttemptAt.value = Date.now();
      outboxReadState.value = "loading";
      const loadedContext = { status: outboxStatus.value, deviceId: outboxDeviceId.value.trim() };
      const nextOutboxRows = await listCloudOutbox({
        status: outboxStatus.value || undefined,
        deviceId: outboxDeviceId.value.trim() || undefined,
        limit: 50
      });
      if (generation !== cloudLoadGeneration) return;
      outboxRows.value = nextOutboxRows;
      outboxLastSuccess.value = new Date();
      outboxReadState.value = "ready";
      outboxLoadedContext.value = loadedContext;
    }
    lastRefresh.value = new Date();
  } catch (err) {
    if (generation !== cloudLoadGeneration) return;
    failedSource.value = readingSource;
    if (readingSource === "metrics") metricsReadState.value = "error";
    if (readingSource === "outbox") outboxReadState.value = "error";
    error.value = err instanceof Error ? err.message : "云上报链路加载失败";
  } finally {
    if (generation === cloudLoadGeneration) {
      loading.value = false;
    }
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
  return value.toLocaleString("zh-CN", { hour12: false });
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
