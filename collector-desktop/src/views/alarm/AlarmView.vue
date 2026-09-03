<template>
  <section class="exact-page alarm-view">
    <div class="section-heading">
      <div class="heading-title-line">
        <h1>告警历史中心</h1>
        <span class="heading-online"><i></i>{{ alarmScopeText }} · {{ alarms.length }} 条 · 已确认 {{ alarmHistorySummary.acknowledged }}</span>
      </div>
      <div class="heading-actions">
        <button type="button" :disabled="alarms.length === 0 || ackStatusLoading" @click="refreshAlarmAcknowledgements">确认状态批量查询</button>
        <button type="button" :disabled="loading" @click="refreshAlarms">刷新告警历史</button>
      </div>
    </div>

    <div class="exact-page-body">
      <div class="exact-toolbar">
        <div class="exact-toolbar-group exact-toolbar-filters">
          <select v-model="alarmDeviceId" @change="refreshAlarms">
            <option value="">全部设备最近告警</option>
            <option v-for="device in deviceStore.devices" :key="device.normalizedId" :value="device.normalizedId">
              {{ device.displayName || device.normalizedId }}
            </option>
          </select>
          <select v-model="alarmLevelFilter" @change="refreshAlarms">
            <option value="">全部级别</option>
            <option value="CRITICAL">严重</option>
            <option value="WARNING">警告</option>
            <option value="INFO">提示</option>
          </select>
          <select v-model.number="alarmHours" @change="refreshAlarms">
            <option :value="24">最近 24 小时</option>
            <option :value="72">最近 3 天</option>
            <option :value="168">最近 7 天</option>
          </select>
          <input v-model="alarmKeyword" type="search" placeholder="点位编码或规则 ID" @keydown.enter="refreshAlarms" />
          <input v-model.number="alarmLimit" type="number" min="10" max="500" step="10" />
          <button type="button" class="primary" :disabled="loading" @click="refreshAlarms">查询</button>
        </div>
      </div>

      <div class="exact-diagnostic-cards alarm-summary-cards">
        <div class="exact-diagnostic-card"><span>告警总数</span><strong>{{ alarmHistorySummary.total }}</strong></div>
        <div class="exact-diagnostic-card"><span>未确认</span><strong>{{ alarmHistorySummary.active }}</strong></div>
        <div class="exact-diagnostic-card"><span>已确认</span><strong>{{ alarmHistorySummary.acknowledged }}</strong></div>
        <div class="exact-diagnostic-card"><span>严重</span><strong>{{ alarmHistorySummary.critical }}</strong></div>
        <div class="exact-diagnostic-card"><span>警告</span><strong>{{ alarmHistorySummary.warning }}</strong></div>
      </div>

      <section class="exact-table-card alarm-ack-table">
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
              <td colspan="9" class="exact-empty">{{ error || '暂无符合条件的告警历史' }}</td>
            </tr>
            <tr v-for="alarm in alarms" :key="buildAlarmIdentity(alarm)">
              <td>{{ alarmLevelText(alarm.level || alarm.alarmType) }}</td>
              <td>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</td>
              <td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td>
              <td>{{ alarm.pointName || alarm.pointCode || alarm.pointId || '-' }}</td>
              <td>{{ alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || alarm.ruleId || '-' }}</td>
              <td>{{ alarmCurrentValue(alarm) }}</td>
              <td>
                <span class="status-badge" :class="alarm.acknowledged ? 'is-online' : 'is-error'">{{ alarm.acknowledged ? '已确认' : '待确认' }}</span>
              </td>
              <td>
                <span class="alarm-ack-detail" :title="describeAlarmAcknowledgement(alarm.acknowledgement)">{{ describeAlarmAcknowledgement(alarm.acknowledgement) }}</span>
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
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { getDeviceAlarmHistory, getRecentAlarms } from "@/api/data.api";
import { acknowledgeAlarm, queryAlarmAcknowledgements } from "@/api/ops.api";
import {
  alarmCurrentValue,
  alarmLevelText,
  applyAlarmAcknowledgement,
  buildAlarmAckPayload,
  buildAlarmIdentity,
  buildAlarmTroubleshootTarget,
  describeAlarmAcknowledgement,
  mergeAlarmAcknowledgementStates,
  normalizeAlarmAcknowledgementMap
} from "@/features/alarm/utils/alarm-utils";
import { buildAlarmHistoryQuery, normalizeAlarmHistoryRows, summarizeAlarmHistory } from "@/features/alarm/utils/alarm-history-utils";
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
const error = ref("");

const alarmHistorySummary = computed(() => summarizeAlarmHistory(alarms.value));
const alarmScopeText = computed(() => alarmDeviceId.value ? `设备 ${deviceNameOf(alarmDeviceId.value)}` : "全部设备最近告警");
const selectedAlarmAckId = computed(() => selectedAlarmForAck.value ? buildAlarmIdentity(selectedAlarmForAck.value) : "");
const selectedAlarmAckTarget = computed(() => selectedAlarmForAck.value ? `${selectedAlarmForAck.value.deviceName || selectedAlarmForAck.value.deviceId || "-"} / ${selectedAlarmForAck.value.pointName || selectedAlarmForAck.value.pointCode || selectedAlarmForAck.value.pointId || "-"}` : "-");
const selectedAlarmAckIdempotencyKey = computed(() => selectedAlarmAckId.value ? buildAlarmAckPayload(alarmAckNote.value, selectedAlarmAckId.value).idempotencyKey : "-");

async function loadAlarms() {
  if (loading.value) {
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const params = buildAlarmHistoryQuery({ level: alarmLevelFilter.value, keyword: alarmKeyword.value, hours: alarmHours.value, limit: alarmLimit.value });
    const response = alarmDeviceId.value ? await getDeviceAlarmHistory(alarmDeviceId.value, params) : await getRecentAlarms(params);
    const rows = normalizeAlarmHistoryRows(response);
    alarms.value = mergeAlarmAcknowledgementStates(rows, await fetchAlarmAcknowledgements(rows));
  } catch (caught) {
    alarmAcknowledgements.value = {};
    alarms.value = [];
    error.value = caught instanceof Error ? caught.message : "告警历史加载失败";
  } finally {
    loading.value = false;
  }
}

function refreshAlarms() {
  void loadAlarms();
}

async function fetchAlarmAcknowledgements(rows: AlarmRow[]): Promise<Record<string, AlarmAcknowledgement>> {
  const alarmIds = Array.from(new Set(rows.map((alarm) => buildAlarmIdentity(alarm)).filter(Boolean))).slice(0, 500);
  alarmAcknowledgements.value = alarmIds.length ? normalizeAlarmAcknowledgementMap(await queryAlarmAcknowledgements(alarmIds)) : {};
  return alarmAcknowledgements.value;
}

async function refreshAlarmAcknowledgements() {
  if (!alarms.value.length) {
    ElMessage.warning("当前没有可查询确认状态的告警");
    return;
  }
  ackStatusLoading.value = true;
  try {
    const acknowledgements = await fetchAlarmAcknowledgements(alarms.value);
    alarms.value = mergeAlarmAcknowledgementStates(alarms.value, acknowledgements);
    ElMessage.success("确认状态批量查询完成");
  } catch (caught) {
    ElMessage.error(caught instanceof Error ? caught.message : "确认状态批量查询失败");
  } finally {
    ackStatusLoading.value = false;
  }
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
  acknowledgingAlarmId.value = alarmId;
  try {
    const acknowledgement = await acknowledgeAlarm(alarmId, buildAlarmAckPayload(alarmAckNote.value, alarmId));
    alarmAcknowledgements.value = { ...alarmAcknowledgements.value, [alarmId]: acknowledgement };
    alarms.value = applyAlarmAcknowledgement(alarms.value, alarmId, acknowledgement);
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

watch(() => [route.query.deviceId, route.query.level, route.query.keyword, route.query.pointCode, route.query.ruleId, route.query.hours, route.query.limit], () => {
  if (applyRouteQuery()) {
    void loadAlarms();
  }
});
</script>

<style scoped>
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
  background: rgba(2, 6, 23, 0.72);
  backdrop-filter: blur(3px);
}

.alarm-view .alarm-ack-dialog {
  width: min(560px, 100%);
  padding: 20px;
  border: 1px solid rgba(59, 130, 246, 0.38);
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.98), rgba(8, 14, 28, 0.98));
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.45);
}

.alarm-view .alarm-ack-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.alarm-view .alarm-ack-dialog-head h2 {
  margin: 2px 0 0;
  color: #f8fafc;
  font-size: 20px;
}

.alarm-view .alarm-ack-target {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid rgba(45, 74, 122, 0.45);
  border-radius: 12px;
  color: #dbeafe;
  background: rgba(15, 23, 42, 0.72);
}

.alarm-view .alarm-ack-dialog label {
  display: block;
  margin-bottom: 8px;
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 700;
}

.alarm-view .alarm-ack-dialog textarea {
  width: 100%;
  min-height: 130px;
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
  overflow: hidden;
  padding: 8px 10px;
  border-radius: 10px;
  color: #93c5fd;
  background: rgba(2, 6, 23, 0.72);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
