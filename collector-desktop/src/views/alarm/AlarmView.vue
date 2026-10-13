<template>
  <section class="exact-page alarm-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>告警历史中心</h1>
        <span class="heading-online"><i></i>{{ alarmScopeText }} · {{ alarms.length }} 条 · 已确认 {{ alarmSummaryDisplay.acknowledged }}</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="alarms.length === 0 || ackStatusLoading" @click="refreshAlarmAcknowledgements">确认状态批量查询</button>
        <button type="button" :disabled="alarmQueryDisabled" @click="refreshAlarms">刷新告警历史</button>
      </div>
    </div>

    <p class="alarm-page-note">分别查看告警级别与确认状态；确认记录不等同于告警恢复。</p>

    <div class="exact-page-body">
      <div class="exact-toolbar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <label class="alarm-filter-field">
            <span>设备范围</span>
          <select v-model="alarmDeviceId" @change="refreshAlarms">
            <option value="">全部设备最近告警</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          </label>
          <label class="alarm-filter-field">
            <span>告警级别</span>
          <select v-model="alarmLevelFilter" @change="refreshAlarms">
            <option value="">全部级别</option>
            <option value="CRITICAL">严重</option>
            <option value="WARNING">警告</option>
            <option value="INFO">提示</option>
          </select>
          </label>
          <label class="alarm-filter-field">
            <span>时间范围</span>
          <select v-model.number="alarmHours" @change="refreshAlarms">
            <option :value="24">最近 24 小时</option>
            <option :value="72">最近 3 天</option>
            <option :value="168">最近 7 天</option>
          </select>
          </label>
          <label class="alarm-filter-field alarm-keyword-field">
            <span>点位或规则</span>
          <input v-model="alarmKeyword" type="search" placeholder="点位编码或规则 ID" @keydown.enter="refreshAlarms" />
          </label>
          <label class="alarm-filter-field alarm-limit-field">
            <span>返回上限</span>
          <input v-model.number="alarmLimit" type="number" min="10" max="500" step="10" />
          </label>
          <button type="button" class="primary" :disabled="alarmQueryDisabled" @click="refreshAlarms">查询</button>
        </div>
      </div>

      <el-alert v-if="alarmReadStatusText" :title="alarmReadStatusText" :type="alarmReadStatusType" :closable="false" />
      <el-alert v-if="ackStatusWarning" :title="ackStatusWarning" type="warning" :closable="false" />

      <div class="exact-diagnostic-cards alarm-summary-cards">
        <div class="exact-diagnostic-card"><span>告警总数</span><strong>{{ alarmSummaryDisplay.total }}</strong></div>
        <div class="exact-diagnostic-card"><span>未确认</span><strong>{{ alarmSummaryDisplay.active }}</strong></div>
        <div class="exact-diagnostic-card"><span>已确认</span><strong>{{ alarmSummaryDisplay.acknowledged }}</strong></div>
        <div class="exact-diagnostic-card"><span>严重</span><strong>{{ alarmSummaryDisplay.critical }}</strong></div>
        <div class="exact-diagnostic-card"><span>警告</span><strong>{{ alarmSummaryDisplay.warning }}</strong></div>
      </div>

      <section class="exact-table-card alarm-ack-table">
        <div class="alarm-records-head"><h2>告警历史记录</h2><span>级别 / 发生条件 / 处理记录</span></div>
        <div class="alarm-table-scroll" tabindex="0" role="region" aria-label="告警历史记录，可横向和纵向滚动">
        <table>
          <thead>
            <tr>
              <th>级别</th>
              <th>发生时间</th>
              <th>设备</th>
              <th>点位</th>
              <th>规则/内容</th>
              <th>当前值</th>
              <th>确认状态</th>
              <th>确认信息</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="alarms.length === 0">
              <td colspan="9" class="exact-empty">{{ alarmEmptyText }}</td>
            </tr>
            <tr v-for="alarm in alarms" :key="buildAlarmIdentity(alarm)">
              <td>{{ alarmLevelText(alarm.level || alarm.alarmType) }}</td>
              <td>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</td>
              <td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td>
              <td>{{ alarm.pointName || alarm.pointCode || alarm.pointId || '-' }}</td>
              <td>{{ alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || alarm.ruleId || '-' }}</td>
              <td>{{ alarmCurrentValue(alarm) }}</td>
              <td>
                <span class="status-badge" :class="alarmAckStatusClass(alarm)">{{ alarmAckStatusText(alarm) }}</span>
              </td>
              <td>
                <span class="alarm-ack-detail" :title="alarmAckDetailText(alarm)">{{ alarmAckDetailText(alarm) }}</span>
              </td>
              <td>
                <div class="alarm-action-row">
                  <button v-if="!alarm.acknowledged" type="button" :disabled="acknowledgingAlarmId === buildAlarmIdentity(alarm)" @click="openAlarmAcknowledgementDialog(alarm)">
                    {{ acknowledgingAlarmId === buildAlarmIdentity(alarm) ? '确认中' : '确认告警' }}
                  </button>
                  <button type="button" @click="locateAlarmLogs(alarm)">定位日志</button>
                  <button type="button" @click="diagnoseAlarmNetwork(alarm)">网络检测</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
        <div v-if="alarms.length === 0" class="alarm-empty-state" role="status">{{ alarmEmptyText }}</div>
      </section>

      <div v-if="alarmAckDialogVisible" class="alarm-ack-backdrop" role="dialog" aria-modal="true" aria-labelledby="alarmAckTitle" @click.self="closeAlarmAcknowledgementDialog">
        <section class="alarm-ack-dialog">
          <div class="alarm-ack-dialog-head">
            <div>
              <span class="panel-kicker">告警处理</span>
              <h2 id="alarmAckTitle">确认告警</h2>
            </div>
            <button type="button" @click="closeAlarmAcknowledgementDialog">关闭</button>
          </div>
          <p class="alarm-ack-target">{{ selectedAlarmAckTarget }}</p>
          <label for="alarmAckNoteInput">处理说明</label>
          <textarea id="alarmAckNoteInput" v-model="alarmAckNote" maxlength="500" placeholder="填写确认原因或后续处理计划"></textarea>
          <div class="alarm-ack-idempotency">
            <span>幂等 key</span>
            <code>{{ selectedAlarmAckIdempotencyKey }}</code>
          </div>
          <div class="heading-actions">
            <button type="button" :disabled="!selectedAlarmForAck || acknowledgingAlarmId === selectedAlarmAckId" class="primary" @click="submitAlarmAcknowledgement">
              {{ acknowledgingAlarmId === selectedAlarmAckId ? '提交中' : '提交确认' }}
            </button>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { getDeviceAlarmHistory, getRecentAlarms } from "@/api/data.api";
import { acknowledgeAlarm, queryAlarmAcknowledgements } from "@/api/ops.api";
import {
  buildAckStatusWarning,
  buildAlarmAcknowledgementPresentation,
  buildAlarmSummaryDisplay
} from "@/features/alarm/utils/alarm-partial-failure";
import {
  buildAlarmAcknowledgementRefreshContext,
  buildAlarmQueryContext,
  isSameAlarmAcknowledgementRefreshContext,
  isSameAlarmQueryContext,
  shouldDisableAlarmSubmit,
  type AlarmQueryContext
} from "@/features/alarm/utils/alarm-request-lifecycle";
import {
  alarmCurrentValue,
  alarmLevelText,
  applyAlarmAcknowledgement,
  buildAlarmAckPayload,
  buildAlarmIdentity,
  buildAlarmTroubleshootTarget,
  mergeAlarmAcknowledgementStates,
  normalizeAlarmAcknowledgementMap
} from "@/features/alarm/utils/alarm-utils";
import { buildAlarmHistoryQuery, normalizeAlarmHistoryRows, summarizeAlarmHistory } from "@/features/alarm/utils/alarm-history-utils";
import { buildContextualReadStatus, hasLastGoodForContext, shouldClearLastGoodForRequest } from "@/features/request/utils/context-last-good";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import type { AlarmRow } from "@/types/monitor";
import type { AlarmAcknowledgement } from "@/types/ops";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const route = useRoute();
const router = useRouter();

const alarms = ref<AlarmRow[]>([]);
const alarmAcknowledgements = ref<Record<string, AlarmAcknowledgement>>({});
const acknowledgingAlarmId = ref("");
const alarmAckDialogVisible = ref(false);
const alarmAckNote = ref("");
const selectedAlarmForAck = ref<AlarmRow | null>(null);
const alarmDeviceId = ref("");
const alarmLevelFilter = ref("");
const alarmKeyword = ref("");
const alarmHours = ref(24);
const alarmLimit = ref(50);
const loading = ref(false);
const ackStatusLoading = ref(false);
const ackStatusUnavailable = ref(false);
const ackStatusWarning = ref("");
const ackStatusInitialized = ref(false);
const error = ref("");
const pendingAlarmQueryContext = ref<AlarmQueryContext | null>(null);
const lastSuccessfulAlarmQueryContext = ref<AlarmQueryContext | null>(null);
const alarmLastSuccessAt = ref<number | null>(null);

const alarmQueryOwner = createLatestRequestOwner(isSameAlarmQueryContext);
const alarmAckRefreshOwner = createLatestRequestOwner(isSameAlarmAcknowledgementRefreshContext);

const alarmHistorySummary = computed(() => summarizeAlarmHistory(alarms.value));
const alarmSummaryDisplay = computed(() => buildAlarmSummaryDisplay(alarmHistorySummary.value, {
  ackStatusUnavailable: ackStatusUnavailable.value,
  ackStatusInitialized: ackStatusInitialized.value
}));
const alarmScopeText = computed(() => alarmDeviceId.value ? `设备 ${deviceNameOf(alarmDeviceId.value)}` : "全部设备最近告警");
const selectedAlarmAckId = computed(() => selectedAlarmForAck.value ? buildAlarmIdentity(selectedAlarmForAck.value) : "");
const selectedAlarmAckTarget = computed(() => selectedAlarmForAck.value ? `${selectedAlarmForAck.value.deviceName || selectedAlarmForAck.value.deviceId || "-"} / ${selectedAlarmForAck.value.pointName || selectedAlarmForAck.value.pointCode || selectedAlarmForAck.value.pointId || "-"}` : "-");
const selectedAlarmAckIdempotencyKey = computed(() => selectedAlarmAckId.value ? buildAlarmAckPayload(alarmAckNote.value, selectedAlarmAckId.value).idempotencyKey : "-");
const alarmQueryDisabled = computed(() => shouldDisableAlarmSubmit(
  loading.value,
  pendingAlarmQueryContext.value,
  currentAlarmQueryContext()
));
const hasAlarmLastGoodForCurrentContext = computed(() => hasLastGoodForContext(lastSuccessfulAlarmQueryContext.value, currentAlarmQueryContext(), isSameAlarmQueryContext));
const alarmReadStatusText = computed(() => buildContextualReadStatus({
  loading: loading.value,
  error: error.value,
  lastSuccessfulContext: lastSuccessfulAlarmQueryContext.value,
  currentContext: currentAlarmQueryContext(),
  isSameContext: isSameAlarmQueryContext,
  loadingText: "告警历史加载中...",
  refreshingText: "告警历史刷新中 · 当前显示上次成功数据",
  staleText: "告警历史刷新失败 · 当前显示上次成功数据",
  initialErrorPrefix: "告警历史加载失败",
  lastSuccessAt: alarmLastSuccessAt.value
}));
const alarmReadStatusType = computed(() => error.value && hasAlarmLastGoodForCurrentContext.value ? "warning" : "error");
const alarmEmptyText = computed(() => error.value ? error.value : "暂无符合条件的告警历史");

async function loadAlarms() {
  const requestContext = currentAlarmQueryContext();
  const ticket = alarmQueryOwner.begin(requestContext);
  const canPreserveLastGood = hasLastGoodForContext(lastSuccessfulAlarmQueryContext.value, requestContext, isSameAlarmQueryContext);
  alarmAckRefreshOwner.invalidate();
  if (shouldClearLastGoodForRequest(lastSuccessfulAlarmQueryContext.value, requestContext, isSameAlarmQueryContext)) {
    alarms.value = [];
    alarmAcknowledgements.value = {};
    lastSuccessfulAlarmQueryContext.value = null;
    alarmLastSuccessAt.value = null;
    ackStatusUnavailable.value = false;
    ackStatusWarning.value = "";
    ackStatusInitialized.value = false;
  }
  loading.value = true;
  ackStatusLoading.value = false;
  error.value = "";
  pendingAlarmQueryContext.value = requestContext;
  try {
    const params = buildAlarmHistoryQuery(requestContext);
    const response = requestContext.deviceId ? await getDeviceAlarmHistory(requestContext.deviceId, params) : await getRecentAlarms(params);
    const rows = normalizeAlarmHistoryRows(response);
    if (!alarmQueryOwner.canCommit(ticket, currentAlarmQueryContext())) {
      return;
    }
    if (!rows.length) {
      alarms.value = [];
      alarmAcknowledgements.value = {};
      ackStatusUnavailable.value = false;
      ackStatusWarning.value = "";
      ackStatusInitialized.value = true;
      lastSuccessfulAlarmQueryContext.value = requestContext;
      alarmLastSuccessAt.value = Date.now();
      return;
    }
    alarms.value = mergeAlarmAcknowledgementStates(rows, alarmAcknowledgements.value);
    lastSuccessfulAlarmQueryContext.value = requestContext;
    alarmLastSuccessAt.value = Date.now();
    void refreshAlarmAcknowledgementsInternal("automatic", [...alarms.value]);
  } catch (caught) {
    if (!alarmQueryOwner.canCommit(ticket, currentAlarmQueryContext())) {
      return;
    }
    if (!canPreserveLastGood) {
      alarmAcknowledgements.value = {};
      alarms.value = [];
      ackStatusUnavailable.value = false;
      ackStatusWarning.value = "";
      ackStatusInitialized.value = false;
    }
    error.value = buildAlarmHistoryErrorMessage(caught);
  } finally {
    if (alarmQueryOwner.isLatest(ticket)) {
      loading.value = false;
      pendingAlarmQueryContext.value = null;
    }
  }
}

function refreshAlarms() {
  void loadAlarms();
}

async function fetchAlarmAcknowledgements(rows: AlarmRow[]): Promise<Record<string, AlarmAcknowledgement>> {
  const { alarmIds } = buildAlarmAcknowledgementRefreshContext(rows);
  return alarmIds.length ? normalizeAlarmAcknowledgementMap(await queryAlarmAcknowledgements(alarmIds)) : {};
}

async function refreshAlarmAcknowledgements() {
  if (!alarms.value.length) {
    ElMessage.warning("当前没有可查询确认状态的告警");
    return;
  }
  await refreshAlarmAcknowledgementsInternal("manual", [...alarms.value]);
}

function openAlarmAcknowledgementDialog(alarm: AlarmRow) {
  const alarmId = buildAlarmIdentity(alarm);
  if (!alarmId || alarm.acknowledged) {
    return;
  }
  selectedAlarmForAck.value = alarm;
  alarmAckNote.value = "";
  alarmAckDialogVisible.value = true;
}

function closeAlarmAcknowledgementDialog() {
  alarmAckDialogVisible.value = false;
  selectedAlarmForAck.value = null;
  alarmAckNote.value = "";
}

async function submitAlarmAcknowledgement() {
  const alarm = selectedAlarmForAck.value;
  if (!alarm) {
    return;
  }
  const alarmId = buildAlarmIdentity(alarm);
  const targetQueryContext = currentAlarmQueryContext();
  acknowledgingAlarmId.value = alarmId;
  alarmAckRefreshOwner.invalidate();
  ackStatusLoading.value = false;
  try {
    const acknowledgement = await acknowledgeAlarm(alarmId, buildAlarmAckPayload(alarmAckNote.value, alarmId));
    if (isSameAlarmQueryContext(targetQueryContext, currentAlarmQueryContext())) {
      alarmAcknowledgements.value = { ...alarmAcknowledgements.value, [alarmId]: acknowledgement };
      alarms.value = applyAlarmAcknowledgement(alarms.value, alarmId, acknowledgement);
    }
    closeAlarmAcknowledgementDialog();
    ElMessage.success("告警已确认");
  } catch (caught) {
    ElMessage.error(caught instanceof Error ? caught.message : "告警确认失败");
  } finally {
    acknowledgingAlarmId.value = "";
  }
}

function locateAlarmLogs(alarm: AlarmRow) {
  const target = buildAlarmTroubleshootTarget(alarm);
  router.push({ path: "/log", query: { deviceId: target.deviceId || undefined, keyword: target.logKeyword || undefined } }).catch(() => undefined);
  ElMessage.info("已按告警信息填充日志搜索条件");
}

function diagnoseAlarmNetwork(alarm: AlarmRow) {
  const deviceId = String(alarm.deviceId || "");
  const device = deviceStore.devices.find((item) => item.normalizedId === deviceId);
  const target = buildAlarmTroubleshootTarget(alarm, device || {});
  if (!target.networkTarget) {
    ElMessage.warning("当前告警缺少可用于网络检测的设备地址");
    return;
  }
  router.push({
    path: "/network",
    query: {
      target: target.networkTarget,
      port: target.networkPort !== undefined ? String(target.networkPort) : undefined
    }
  }).catch(() => undefined);
  ElMessage.info("已从告警带入网络检测目标");
}

function applyRouteQuery() {
  const nextDeviceId = normalizeRouteQuery(route.query.deviceId);
  const nextLevel = normalizeRouteQuery(route.query.level);
  const nextKeyword = normalizeRouteQuery(route.query.keyword || route.query.pointCode || route.query.ruleId);
  const nextHours = Number(normalizeRouteQuery(route.query.hours));
  const nextLimit = Number(normalizeRouteQuery(route.query.limit));
  let changed = false;
  if (alarmDeviceId.value !== nextDeviceId) {
    alarmDeviceId.value = nextDeviceId;
    changed = true;
  }
  if (alarmLevelFilter.value !== nextLevel) {
    alarmLevelFilter.value = nextLevel;
    changed = true;
  }
  if (alarmKeyword.value !== nextKeyword) {
    alarmKeyword.value = nextKeyword;
    changed = true;
  }
  if (Number.isFinite(nextHours) && nextHours > 0 && alarmHours.value !== nextHours) {
    alarmHours.value = Math.trunc(nextHours);
    changed = true;
  }
  if (Number.isFinite(nextLimit) && nextLimit > 0 && alarmLimit.value !== nextLimit) {
    alarmLimit.value = Math.trunc(nextLimit);
    changed = true;
  }
  return changed;
}

function deviceNameOf(deviceId: string): string {
  return deviceStore.devices.find((device) => device.normalizedId === deviceId)?.displayName || deviceId;
}

function alarmAckPresentation(alarm: AlarmRow) {
  return buildAlarmAcknowledgementPresentation(alarm, {
    ackStatusUnavailable: ackStatusUnavailable.value,
    ackStatusInitialized: ackStatusInitialized.value
  });
}

function alarmAckStatusText(alarm: AlarmRow): string {
  return alarmAckPresentation(alarm).statusText;
}

function alarmAckStatusClass(alarm: AlarmRow): string {
  return alarmAckPresentation(alarm).toneClass;
}

function alarmAckDetailText(alarm: AlarmRow): string {
  return alarmAckPresentation(alarm).detailText;
}

async function refreshAlarmAcknowledgementsInternal(
  source: "automatic" | "manual",
  rowsSnapshot: AlarmRow[]
) {
  const requestContext = buildAlarmAcknowledgementRefreshContext(rowsSnapshot);
  if (!requestContext.alarmIds.length) {
    ackStatusLoading.value = false;
    ackStatusUnavailable.value = false;
    ackStatusWarning.value = "";
    ackStatusInitialized.value = true;
    return;
  }
  const ticket = alarmAckRefreshOwner.begin(requestContext);
  ackStatusLoading.value = true;
  try {
    const acknowledgements = await fetchAlarmAcknowledgements(rowsSnapshot);
    if (!alarmAckRefreshOwner.canCommit(ticket, currentAlarmAcknowledgementRefreshContext())) {
      return;
    }
    alarmAcknowledgements.value = acknowledgements;
    alarms.value = mergeAlarmAcknowledgementStates(alarms.value, acknowledgements);
    ackStatusUnavailable.value = false;
    ackStatusWarning.value = "";
    ackStatusInitialized.value = true;
    if (source === "manual") {
      ElMessage.success("确认状态批量查询完成");
    }
  } catch (caught) {
    if (!alarmAckRefreshOwner.canCommit(ticket, currentAlarmAcknowledgementRefreshContext())) {
      return;
    }
    ackStatusUnavailable.value = true;
    ackStatusWarning.value = buildAckStatusWarning();
    ackStatusInitialized.value = false;
    if (source === "manual") {
      ElMessage.warning(caught instanceof Error ? caught.message : "确认状态批量查询失败");
    }
  } finally {
    if (alarmAckRefreshOwner.isLatest(ticket)) {
      ackStatusLoading.value = false;
    }
  }
}

function buildAlarmHistoryErrorMessage(caught: unknown): string {
  const detail = caught instanceof Error ? caught.message : String(caught || "").trim();
  return detail ? `告警历史加载失败：${detail}` : "告警历史加载失败";
}

function normalizeRouteQuery(value: unknown): string {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] ?? "") : "";
  }
  return value === undefined || value === null ? "" : String(value);
}

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = typeof value === "number" ? new Date(value) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

async function initializeAlarmView() {
  await appStore.initialize();
  applyRouteQuery();
  await deviceStore.refresh();
  await loadAlarms();
}

onMounted(() => {
  void initializeAlarmView();
});

onBeforeUnmount(() => {
  loading.value = false;
  ackStatusLoading.value = false;
  pendingAlarmQueryContext.value = null;
  alarmQueryOwner.invalidate();
  alarmAckRefreshOwner.invalidate();
});

watch(() => [route.query.deviceId, route.query.level, route.query.keyword, route.query.pointCode, route.query.ruleId, route.query.hours, route.query.limit], () => {
  if (applyRouteQuery()) {
    void loadAlarms();
  }
});

function currentAlarmQueryContext() {
  return buildAlarmQueryContext({
    deviceId: alarmDeviceId.value,
    level: alarmLevelFilter.value,
    keyword: alarmKeyword.value,
    hours: alarmHours.value,
    limit: alarmLimit.value
  });
}

function currentAlarmAcknowledgementRefreshContext() {
  return buildAlarmAcknowledgementRefreshContext(alarms.value);
}
</script>

<style scoped>
.exact-page.alarm-view {
  display: block;
  padding: 27px 30px 24px;
  overflow: auto;
  color: var(--console-text-primary);
  font-size: 13px;
  line-height: 1.55;
  scrollbar-gutter: stable;
}

.alarm-view .section-heading {
  min-height: 0;
  padding: 0;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 16px;
  border: 0;
}

.alarm-view .section-heading h1 {
  font-size: 26px;
  font-weight: 650;
  line-height: 1.35;
}

.alarm-view .heading-title-line,
.alarm-view .heading-actions { flex-wrap: wrap; }

.alarm-view .heading-online {
  font-size: 11px;
  font-weight: 400;
}

.alarm-view .alarm-page-note {
  margin: 6px 0 22px;
  color: var(--console-text-muted);
  font-size: 12px;
}

.alarm-view .exact-page-body {
  min-height: auto;
  gap: 17px;
  overflow: visible;
}
.alarm-view .exact-page-body > * { flex-shrink: 0; }

.alarm-view .exact-toolbar {
  padding: 17px 19px;
  overflow: visible;
  border-radius: 8px;
}

.alarm-view .exact-toolbar-filters {
  width: 100%;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 15px;
  overflow: visible;
}

.alarm-view .alarm-filter-field {
  display: flex;
  width: 170px;
  min-width: 0;
  max-width: 100%;
  flex-direction: column;
  gap: 7px;
}

.alarm-view .alarm-filter-field > span {
  color: var(--console-text-muted);
  font-size: 11px;
}
.alarm-view .alarm-keyword-field { width: 250px; }
.alarm-view .alarm-limit-field { width: 105px; }
.alarm-view .alarm-filter-field input,
.alarm-view .alarm-filter-field select {
  width: 100%;
  min-width: 0;
}

.alarm-view .alarm-summary-cards {
  display: flex;
  padding: 2px 0 4px;
  flex-wrap: wrap;
  gap: 14px 28px;
}

.alarm-view .alarm-summary-cards .exact-diagnostic-card {
  display: inline-flex;
  min-width: 0;
  min-height: 0;
  padding: 0;
  align-items: baseline;
  gap: 9px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.alarm-view .alarm-summary-cards span {
  color: var(--console-text-muted);
  font-size: 12px;
}

.alarm-view .alarm-summary-cards strong {
  color: var(--console-text-primary);
  font-size: 22px;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.alarm-view .alarm-summary-cards > div:nth-child(2) strong,
.alarm-view .alarm-summary-cards > div:nth-child(5) strong { color: var(--console-warning); }
.alarm-view .alarm-summary-cards > div:nth-child(3) strong { color: var(--console-success); }
.alarm-view .alarm-summary-cards > div:nth-child(4) strong { color: var(--console-danger); }

.alarm-view .alarm-ack-table {
  padding: 0;
  overflow: hidden;
  border-radius: 8px;
}

.alarm-view .alarm-records-head {
  display: flex;
  min-height: 55px;
  padding: 15px 19px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--console-border);
}

.alarm-view .alarm-records-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.alarm-view .alarm-records-head span {
  color: var(--console-text-muted);
  font-size: 11px;
}

.alarm-view .alarm-table-scroll {
  min-width: 0;
  max-width: 100%;
  max-height: 500px;
  overflow: auto;
  overscroll-behavior: contain;
}

.alarm-view .alarm-ack-table table {
  width: 100%;
  min-width: 1320px;
  border-collapse: collapse;
  text-align: left;
}

.alarm-view .alarm-ack-table th,
.alarm-view .alarm-ack-table td {
  height: 53px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--console-border-soft);
  font-size: 12px;
  vertical-align: middle;
  white-space: nowrap;
}

.alarm-view .alarm-ack-table th {
  position: sticky;
  z-index: 1;
  top: 0;
  height: 40px;
  color: var(--console-text-muted);
  background: var(--console-panel-soft);
  font-size: 11px;
  font-weight: 500;
}

.alarm-view .alarm-ack-table td:first-child { font-weight: 600; }
.alarm-view .alarm-ack-table td:nth-child(5) {
  min-width: 210px;
  max-width: 340px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.alarm-view .alarm-ack-table td:nth-child(6) { font-variant-numeric: tabular-nums; }

.alarm-view .alarm-ack-table th:last-child,
.alarm-view .alarm-ack-table td:last-child {
  position: sticky;
  right: 0;
  border-left: 1px solid var(--console-border);
  background: var(--console-panel);
}

.alarm-view .alarm-ack-table th:last-child {
  z-index: 2;
  background: var(--console-panel-soft);
}
.alarm-view .alarm-ack-table tbody tr:hover td { background: var(--console-input-bg-hover); }
.alarm-view .alarm-ack-table tbody tr:last-child td { border-bottom: 0; }

/* 空态不在宽表中央，窄屏横滚起点仍可读到原来的状态说明。 */
.alarm-view .alarm-ack-table td.exact-empty { display: none; }
.alarm-view .alarm-empty-state {
  padding: 40px 20px;
  color: var(--console-text-muted);
  text-align: center;
}

.alarm-view .alarm-ack-table td:nth-child(8) {
  min-width: 220px;
}

.alarm-view .alarm-ack-detail {
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  color: var(--exact-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alarm-view .alarm-action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.alarm-view .alarm-action-row button {
  min-height: 28px;
  padding: 0 8px;
  font-size: 12px;
}

.alarm-view .alarm-ack-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 24px;
  overflow: auto;
  background: rgba(29, 48, 61, 0.46);
}

.alarm-view .alarm-ack-dialog {
  width: min(560px, 100%);
  max-height: min(720px, calc(100dvh - 48px));
  padding: 22px 24px;
  overflow: auto;
  border: 1px solid var(--console-border);
  border-radius: 10px;
  color: var(--console-text-primary);
  background: var(--console-panel);
  box-shadow: var(--console-overlay-shadow);
}

.alarm-view .alarm-ack-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--console-border);
}

.alarm-view .alarm-ack-dialog-head h2 {
  margin: 2px 0 0;
  color: var(--console-text-primary);
  font-size: 20px;
}

.alarm-view .alarm-ack-target {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid var(--console-border);
  border-radius: 6px;
  color: var(--console-text-secondary);
  background: var(--console-panel-soft);
  overflow-wrap: anywhere;
}

.alarm-view .alarm-ack-dialog label {
  display: block;
  margin-bottom: 8px;
  color: var(--console-text-muted);
  font-size: 13px;
  font-weight: 500;
}

.alarm-view .alarm-ack-dialog textarea {
  width: 100%;
  min-height: 130px;
  max-height: 260px;
  padding: 10px 12px;
  border: 1px solid var(--console-input-border);
  border-radius: 6px;
  color: var(--console-text-primary);
  background: var(--console-input-bg);
  font: inherit;
  resize: vertical;
}

.alarm-view .alarm-ack-idempotency {
  display: grid;
  gap: 6px;
  margin: 12px 0 16px;
}

.alarm-view .alarm-ack-idempotency span {
  color: var(--exact-muted);
  font-size: 12px;
}

.alarm-view .alarm-ack-idempotency code {
  display: block;
  padding: 8px 10px;
  border: 1px solid var(--console-code-border);
  border-radius: 6px;
  color: var(--console-code-text);
  background: var(--console-code-bg);
  font-family: Consolas, monospace;
  overflow-wrap: anywhere;
}

.alarm-view .alarm-ack-dialog > .heading-actions {
  padding-top: 14px;
  border-top: 1px solid var(--console-border);
}

.alarm-view button:focus-visible,
.alarm-view input:focus-visible,
.alarm-view select:focus-visible,
.alarm-view textarea:focus-visible,
.alarm-view [tabindex="0"]:focus-visible {
  outline: 2px solid var(--console-input-border-focus);
  outline-offset: 2px;
}

@media (max-width: 1280px) {
  .exact-page.alarm-view { padding: 24px; }
}

@media (max-width: 600px) {
  .exact-page.alarm-view { padding: 20px 14px; }
  .alarm-view .section-heading h1 { font-size: 22px; }
  .alarm-view .heading-title-line {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .alarm-view .heading-actions { justify-content: flex-start; }
  .alarm-view .exact-toolbar { padding: 15px; }
  .alarm-view .alarm-filter-field { width: 100%; }
  .alarm-view .alarm-limit-field { width: 105px; }
  .alarm-view .alarm-summary-cards { gap: 12px 20px; }
  .alarm-view .alarm-records-head {
    padding: 13px 15px;
    flex-wrap: wrap;
  }
  .alarm-view .alarm-action-row { flex-direction: column; }
  .alarm-view .alarm-table-scroll { max-height: 420px; }
  .alarm-view .alarm-ack-backdrop { padding: 14px; }
  .alarm-view .alarm-ack-dialog {
    max-height: calc(100dvh - 28px);
    padding: 16px;
  }
}
</style>
