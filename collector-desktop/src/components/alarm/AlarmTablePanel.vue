<template>
  <section class="monitor-panel alarm-workbench">
    <div class="panel-toolbar">
      <div class="table-actions alarm-filter-bar">
        <el-select v-model="level" placeholder="级别" clearable class="mini-filter">
          <el-option label="严重" value="CRITICAL" />
          <el-option label="重要" value="MAJOR" />
          <el-option label="一般" value="MINOR" />
          <el-option label="提醒" value="WARNING" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="状态" clearable class="mini-filter">
          <el-option label="告警中" value="ACTIVE" />
          <el-option label="已确认" value="ACKED" />
          <el-option label="已恢复" value="RECOVERED" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索设备/点位/内容" clearable class="compact-select" />
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" plain :disabled="!selectedRows.some((row) => !row.acknowledged && row.alarmId)" @click="openBatchAck">批量确认</el-button>
      </div>
    </div>

    <div class="alarm-stat-list">
      <article class="alarm-stat-card info"><span>告警总数</span><strong>{{ summary.total }}</strong><small>当前查询结果</small></article>
      <article class="alarm-stat-card danger"><span>活动中</span><strong>{{ summary.active }}</strong><small>等待处理</small></article>
      <article class="alarm-stat-card warning"><span>已恢复</span><strong>{{ summary.recovered }}</strong><small>已恢复正常</small></article>
      <article class="alarm-stat-card success"><span>已确认</span><strong>{{ summary.acknowledged }}</strong><small>ACK 完成</small></article>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-table v-loading="loading" :data="filteredRows" height="420" border @selection-change="selectedRows = $event">
      <el-table-column type="selection" width="44" />
      <el-table-column label="级别" width="110"><template #default="{ row }"><el-tag :type="levelType(row.level)" effect="light">{{ levelText(row.level) }}</el-tag></template></el-table-column>
      <el-table-column label="设备名称" min-width="160"><template #default="{ row }"><span class="cell-ellipsis" :title="String(row.deviceName || row.deviceId || '-')">{{ row.deviceName || row.deviceId || '-' }}</span></template></el-table-column>
      <el-table-column label="点位名称" min-width="150"><template #default="{ row }"><span class="cell-ellipsis" :title="String(row.pointCode || row.pointId || '-')">{{ row.pointCode || row.pointId || '-' }}</span></template></el-table-column>
      <el-table-column label="告警内容" min-width="220"><template #default="{ row }"><span class="cell-ellipsis" :title="alarmContent(row)">{{ alarmContent(row) }}</span></template></el-table-column>
      <el-table-column label="开始时间" min-width="160"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column>
      <el-table-column label="最近发生" min-width="160"><template #default="{ row }">{{ formatTime(row.lastOccurredAt) }}</template></el-table-column>
      <el-table-column label="恢复时间" min-width="160"><template #default="{ row }">{{ formatTime(row.recoveredAt) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }">{{ alarmStatusText(row) }}</template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><el-button link @click="detail = row">详情</el-button><el-button type="primary" link :disabled="row.acknowledged || !row.alarmId" @click="openAck(row)">确认</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="ackDialogVisible" :title="ackTargetRows.length > 1 ? '批量确认告警' : '确认告警'" width="520px">
      <el-alert :title="`待确认告警：${ackTargetRows.length} 条`" type="info" :closable="false" />
      <el-input v-model="ackNote" type="textarea" :rows="4" placeholder="确认说明，例如：现场已处理 / 已通知值班人员" />
      <template #footer>
        <el-button @click="ackDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="acking" @click="confirmAck">确认</el-button>
      </template>
    </el-dialog>
    <el-drawer :model-value="Boolean(detail)" title="告警详情" size="min(620px, 90vw)" @close="detail = null">
      <div v-if="detail">
        <p>标识：{{ detail.alarmId || '-' }}</p>
        <p>设备：{{ detail.deviceName || detail.deviceId || '-' }}</p>
        <p>点位：{{ detail.pointCode || detail.pointId || '-' }}</p>
        <p>规则：{{ detail.ruleName || detail.ruleId || '-' }}</p>
        <p>级别：{{ levelText(detail.level) }}</p>
        <p>状态：{{ alarmStatusText(detail) }}</p>
        <p>内容：{{ detail.message || '-' }}</p>
        <p>开始：{{ formatTime(detail.startedAt) }}</p>
        <p>首次发生：{{ formatTime(detail.occurredAt) }}</p>
        <p>最近发生：{{ formatTime(detail.lastOccurredAt) }}</p>
        <p>恢复：{{ formatTime(detail.recoveredAt) }}</p>
        <p>持续：{{ detail.durationMillis == null ? '-' : `${detail.durationMillis} ms` }}</p>
        <p>数值：{{ detail.value ?? '-' }} {{ detail.unit || '' }}</p>
        <p>确认人：{{ detail.acknowledgedBy || '未确认' }}</p>
        <p>确认时间：{{ formatTime(detail.acknowledgedAt) }}</p>
        <p>说明：{{ detail.acknowledgementNote || '-' }}</p>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { ElDrawer, ElMessage } from "element-plus";

import { getAlarmLifecycles } from "@/api/alarm.api";
import { acknowledgeAlarm } from "@/api/ops.api";
import { buildAlarmAckPayload } from "@/features/alarm/utils/alarm-utils";
import type { AlarmLifecycleItem } from "@/types/alarm";

const props = defineProps<{
  deviceId?: string;
}>();

const loading = ref(false);
const acking = ref(false);
const error = ref("");
const rows = ref<AlarmLifecycleItem[]>([]);
const selectedRows = ref<AlarmLifecycleItem[]>([]);
const ackTargetRows = ref<AlarmLifecycleItem[]>([]);
const detail = ref<AlarmLifecycleItem | null>(null);
const statusFilter = ref("");
let generation = 0;
const level = ref("");
const keyword = ref("");
const timeRange = ref<[Date, Date] | null>(null);
const ackNote = ref("");
const ackDialogVisible = ref(false);

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  return rows.value.filter((row) => (!value || [row.deviceId, row.deviceName, row.pointId, row.pointCode, alarmContent(row)]
      .some((item) => String(item || "").toLowerCase().includes(value))));
});
const summary = computed(() => ({
  total: filteredRows.value.length,
  active: filteredRows.value.filter((row) => row.lifecycleState === "ACTIVE").length,
  recovered: filteredRows.value.filter((row) => row.lifecycleState === "RECOVERED").length,
  acknowledged: filteredRows.value.filter((row) => row.acknowledged).length
}));

async function load() {
  const ticket = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const params: { deviceId?: string; level?: string; state?: "ACTIVE" | "ACKED" | "RECOVERED";
      limit: number; startTs?: number; endTs?: number } =
      { deviceId: props.deviceId, level: level.value || undefined,
        state: statusFilter.value as "ACTIVE" | "ACKED" | "RECOVERED" || undefined, limit: 200 };
    if (timeRange.value) {
      params.startTs = timeRange.value[0].getTime();
      params.endTs = timeRange.value[1].getTime();
    }
    const response = await getAlarmLifecycles(params);
    if (ticket !== generation) return;
    if (response.status === "disabled") {
      rows.value = [];
      error.value = "告警历史存储未启用";
    } else {
      rows.value = response.items;
    }
  } catch (caught) {
    if (ticket === generation) error.value = caught instanceof Error ? caught.message : "告警数据加载失败";
  } finally {
    if (ticket === generation) loading.value = false;
  }
}

function openAck(row: AlarmLifecycleItem) {
  ackTargetRows.value = [row];
  ackNote.value = "";
  ackDialogVisible.value = true;
}

function openBatchAck() {
  ackTargetRows.value = selectedRows.value.filter((row) => !row.acknowledged && row.alarmId);
  ackNote.value = "";
  ackDialogVisible.value = true;
}

async function confirmAck() {
  const deviceId = props.deviceId;
  const targets = ackTargetRows.value
    .map((row) => String(row.alarmId || ""))
    .filter(Boolean);
  if (targets.length === 0) {
    error.value = "告警缺少 alarmId，无法确认";
    return;
  }
  acking.value = true;
  error.value = "";
  try {
    await Promise.all(targets.map((alarmId) => acknowledgeAlarm(alarmId, buildAlarmAckPayload(ackNote.value, alarmId))));
    ElMessage.success(`已确认 ${targets.length} 条告警`);
    ackDialogVisible.value = false;
    if (deviceId === props.deviceId) await load();
  } catch (caught) {
    if (deviceId === props.deviceId) error.value = caught instanceof Error ? caught.message : "告警确认失败";
  } finally {
    acking.value = false;
  }
}

function alarmContent(row: AlarmLifecycleItem): string {
  return row.message || "-";
}

function levelType(levelValue?: string | null): "danger" | "warning" | "info" {
  if (["CRITICAL", "严重", "MAJOR", "重要"].includes(levelValue || "")) {
    return "danger";
  }
  if (["MINOR", "一般", "WARNING", "提醒"].includes(levelValue || "")) {
    return "warning";
  }
  return "info";
}

function levelText(levelValue?: string | null): string {
  const value = String(levelValue || "").toUpperCase();
  return {
    CRITICAL: "严重",
    MAJOR: "重要",
    MINOR: "一般",
    WARNING: "提醒"
  }[value] || levelValue || "未知";
}

function alarmStatusText(row: AlarmLifecycleItem): string {
  if (row.lifecycleState === "RECOVERED") return `已恢复 / ${row.acknowledged ? "已确认" : "未确认"}`;
  return row.lifecycleState === "ACKED" ? "已确认" : "告警中 / 未确认";
}

function formatTime(value: unknown): string {
  if (typeof value === "number" && value > 0) {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

onMounted(load);
watch(() => [props.deviceId, level.value, statusFilter.value,
  timeRange.value?.[0]?.getTime(), timeRange.value?.[1]?.getTime()], load);
watch(() => props.deviceId, () => { rows.value = []; selectedRows.value = []; detail.value = null; });
onUnmounted(() => { generation += 1; });
</script>

<style scoped>
.alarm-workbench,
.monitor-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  padding: 0;
  flex-direction: column;
  gap: 8px;
  border: 0;
  background: transparent;
}

.panel-toolbar {
  display: flex;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.table-actions,
.panel-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: visible;
  white-space: nowrap;
}

.alarm-filter-bar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  overflow: visible;
}

.alarm-filter-bar :deep(.el-date-editor) {
  flex: 1 1 280px;
  width: clamp(280px, 34vw, 360px);
  max-width: 100%;
}

.alarm-stat-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.alarm-stat-card {
  display: grid;
  min-height: 66px;
  padding: 9px 10px;
  gap: 3px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.alarm-stat-card span,
.alarm-stat-card small {
  color: var(--console-text-muted);
  font-size: 11px;
}

.alarm-stat-card strong {
  color: var(--console-text-primary);
  font-size: 20px;
  line-height: 1.1;
}

.alarm-stat-card.danger strong {
  color: #f87171;
}

.alarm-stat-card.warning strong {
  color: #fb923c;
}

.alarm-stat-card.success strong {
  color: #34d399;
}

.cell-ellipsis {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1280px) {
  .alarm-stat-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
