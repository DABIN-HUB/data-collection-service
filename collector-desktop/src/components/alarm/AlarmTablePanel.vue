<template>
  <section class="monitor-panel alarm-workbench">
    <div class="panel-toolbar">
      <div class="table-actions">
        <el-select v-model="level" placeholder="级别" clearable style="width: 120px">
          <el-option label="严重" value="CRITICAL" />
          <el-option label="重要" value="MAJOR" />
          <el-option label="一般" value="MINOR" />
          <el-option label="提醒" value="WARNING" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索设备/点位/内容" clearable style="width: 220px" />
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" plain :disabled="selectedRows.length === 0" @click="openBatchAck">批量确认</el-button>
      </div>
    </div>

    <div class="modao-stat-grid alarm-stat-grid">
      <article class="modao-stat-card info"><span>告警总数</span><strong>{{ summary.total }}</strong><small>当前查询结果</small></article>
      <article class="modao-stat-card danger"><span>严重/重要</span><strong>{{ summary.critical }}</strong><small>需要优先处理</small></article>
      <article class="modao-stat-card warning"><span>提醒/一般</span><strong>{{ summary.warning }}</strong><small>关注趋势</small></article>
      <article class="modao-stat-card success"><span>已确认</span><strong>{{ summary.acknowledged }}</strong><small>ACK 完成</small></article>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-table v-loading="loading" :data="filteredRows" height="420" border @selection-change="selectedRows = $event">
      <el-table-column type="selection" width="44" />
      <el-table-column label="级别" width="110"><template #default="{ row }"><el-tag :type="levelType(row.level)" effect="light">{{ levelText(row.level) }}</el-tag></template></el-table-column>
      <el-table-column prop="deviceName" label="设备名称" min-width="160" />
      <el-table-column prop="pointName" label="点位名称" min-width="150" />
      <el-table-column label="告警内容" min-width="220"><template #default="{ row }">{{ alarmContent(row) }}</template></el-table-column>
      <el-table-column label="发生时间" min-width="160"><template #default="{ row }">{{ formatTime(row.timestamp || row.occurTime) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }">{{ row.acknowledged ? '已确认' : row.status || '未确认' }}</template></el-table-column>
      <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button type="primary" link :disabled="row.acknowledged" @click="openAck(row)">确认</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="ackDialogVisible" :title="ackTargetRows.length > 1 ? '批量确认告警' : '确认告警'" width="520px">
      <el-alert :title="`待确认告警：${ackTargetRows.length} 条`" type="info" :closable="false" />
      <el-input v-model="ackNote" type="textarea" :rows="4" placeholder="确认说明，例如：现场已处理 / 已通知值班人员" />
      <template #footer>
        <el-button @click="ackDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="acking" @click="confirmAck">确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { getRecentAlarms, normalizeAlarmRows } from "@/api/data.api";
import { acknowledgeAlarm } from "@/api/ops.api";
import type { AlarmRow } from "@/types/monitor";
import { buildAlarmAckPayload, summarizeAlarms } from "@/views/ops/ops-utils";

const props = defineProps<{
  deviceId?: string;
}>();

const loading = ref(false);
const acking = ref(false);
const error = ref("");
const rows = ref<AlarmRow[]>([]);
const selectedRows = ref<AlarmRow[]>([]);
const ackTargetRows = ref<AlarmRow[]>([]);
const level = ref("");
const keyword = ref("");
const timeRange = ref<[Date, Date] | null>(null);
const ackNote = ref("");
const ackDialogVisible = ref(false);

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  if (!value) {
    return rows.value;
  }
  return rows.value.filter((row) => [row.deviceId, row.deviceName, row.pointId, row.pointCode, row.pointName, alarmContent(row)]
    .some((item) => String(item || "").toLowerCase().includes(value)));
});
const summary = computed(() => summarizeAlarms(filteredRows.value));

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const params: Record<string, string | number | undefined> = { deviceId: props.deviceId, level: level.value || undefined, limit: 200 };
    if (timeRange.value) {
      params.startTs = timeRange.value[0].getTime();
      params.endTs = timeRange.value[1].getTime();
    }
    rows.value = normalizeAlarmRows(await getRecentAlarms(params));
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "告警数据加载失败";
  } finally {
    loading.value = false;
  }
}

function openAck(row: AlarmRow) {
  ackTargetRows.value = [row];
  ackNote.value = "";
  ackDialogVisible.value = true;
}

function openBatchAck() {
  ackTargetRows.value = selectedRows.value.filter((row) => !row.acknowledged);
  ackNote.value = "";
  ackDialogVisible.value = true;
}

async function confirmAck() {
  const targets = ackTargetRows.value
    .map((row) => String(row.alarmId || row.id || ""))
    .filter(Boolean);
  if (targets.length === 0) {
    error.value = "告警缺少 alarmId，无法确认";
    return;
  }
  acking.value = true;
  error.value = "";
  try {
    await Promise.all(targets.map((alarmId) => acknowledgeAlarm(alarmId, buildAlarmAckPayload(ackNote.value))));
    ElMessage.success(`已确认 ${targets.length} 条告警`);
    ackDialogVisible.value = false;
    await load();
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "告警确认失败";
  } finally {
    acking.value = false;
  }
}

function alarmContent(row: AlarmRow): string {
  return String(row.content || row.message || row.alarmContent || row.alarmType || "-");
}

function levelType(levelValue?: string): "danger" | "warning" | "info" {
  if (["CRITICAL", "严重", "MAJOR", "重要"].includes(levelValue || "")) {
    return "danger";
  }
  if (["MINOR", "一般", "WARNING", "提醒"].includes(levelValue || "")) {
    return "warning";
  }
  return "info";
}

function levelText(levelValue?: string): string {
  return levelValue || "未知";
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

onMounted(load);
watch(() => [props.deviceId, level.value, timeRange.value?.[0]?.getTime(), timeRange.value?.[1]?.getTime()], load);
</script>
