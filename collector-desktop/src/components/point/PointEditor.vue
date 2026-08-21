<template>
  <section class="point-editor point-workbench">
    <div class="point-toolbar">
      <div class="point-toolbar-left">
        <el-button type="primary" @click="pointStore.addEmptyPoint(deviceId)">新增点位</el-button>
        <el-button @click="generateDialogVisible = true">批量生成</el-button>
        <el-button :disabled="selectedIds.length === 0" @click="batchDialogVisible = true">批量编辑</el-button>
        <el-button type="danger" plain :disabled="selectedIds.length === 0" @click="pointStore.removeSelected(deviceId)">删除</el-button>
      </div>
      <div class="point-toolbar-right">
        <input ref="fileInputRef" class="hidden-file-input" type="file" accept=".xlsx,.xls" @change="handleImportFile" />
        <el-input v-model="keyword" placeholder="搜索点位名称/编码/地址" clearable :prefix-icon="Search" />
        <el-button @click="fileInputRef?.click()">导入 Excel</el-button>
        <el-button @click="exportExcel">导出 Excel</el-button>
        <el-button :loading="realtimeLoading" @click="loadRealtime">刷新实时值</el-button>
        <el-button :loading="pointStore.loading" @click="pointStore.load(deviceId)">刷新配置</el-button>
        <el-button type="primary" :loading="pointStore.saving" @click="savePoints">保存</el-button>
      </div>
    </div>

    <el-alert v-if="pointStore.error" :title="pointStore.error" type="warning" :closable="false" />
    <el-alert v-if="realtimeError" :title="realtimeError" type="info" :closable="false" />

    <div class="point-workbench-grid">
      <section class="point-table-panel">
        <div class="point-table-meta">
          <span>点位数：{{ points.length }}</span>
          <span>协议：{{ activeProtocol?.title || protocolCode || '-' }}</span>
          <span>动态字段：{{ pointFields.length }}</span>
        </div>
        <el-table
          v-loading="pointStore.loading"
          :data="filteredPoints"
          height="560"
          row-key="pointId"
          border
          highlight-current-row
          @selection-change="handleSelectionChange"
          @row-click="selectPoint"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column type="index" label="序号" width="58" />
          <el-table-column label="点位名称" min-width="150">
            <template #default="{ row }"><el-input v-model="row.pointName" /></template>
          </el-table-column>
          <el-table-column label="点位编码" min-width="150">
            <template #default="{ row }"><el-input v-model="row.pointCode" /></template>
          </el-table-column>
          <el-table-column label="地址" min-width="130">
            <template #default="{ row }"><el-input v-model="row.address" /></template>
          </el-table-column>
          <el-table-column label="数据类型" width="126">
            <template #default="{ row }">
              <el-select v-model="row.dataType" filterable>
                <el-option v-for="item in dataTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="读写" width="88">
            <template #default="{ row }">
              <el-select v-model="row.readWrite">
                <el-option label="R" value="R" />
                <el-option label="W" value="W" />
                <el-option label="RW" value="RW" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column v-for="field in tablePointFields" :key="field.name" :label="field.label || field.name" min-width="130">
            <template #default="{ row }">{{ displayExtraValue(row, field.name) }}</template>
          </el-table-column>
          <el-table-column label="当前值" min-width="120">
            <template #default="{ row }">{{ runtimeOf(row)?.currentValue ?? runtimeOf(row)?.value ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="质量" width="100">
            <template #default="{ row }">
              <el-tag :type="runtimeOf(row)?.quality === 'GOOD' ? 'success' : 'warning'" effect="light">{{ runtimeOf(row)?.quality || 'UNKNOWN' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时ms" width="90">
            <template #default="{ row }">{{ runtimeOf(row)?.processCostMs ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </section>

      <aside class="point-detail-panel">
        <template v-if="selectedPoint">
          <div class="point-detail-head">
            <div>
              <h3>{{ selectedPoint.pointName || selectedPoint.pointCode || '未命名点位' }}</h3>
              <p>{{ selectedPoint.pointCode || '-' }} · {{ selectedPoint.address || '-' }}</p>
            </div>
            <div class="point-detail-head-actions">
              <el-tag :type="runtimeOf(selectedPoint)?.quality === 'GOOD' ? 'success' : 'warning'" effect="light">{{ runtimeOf(selectedPoint)?.quality || 'UNKNOWN' }}</el-tag>
              <el-button size="small" @click="emitOpenRealtime">查看实时</el-button>
              <el-button size="small" @click="emitOpenHistory">查看历史</el-button>
            </div>
          </div>

          <div class="point-runtime-strip">
            <span>当前值：<strong>{{ runtimeOf(selectedPoint)?.currentValue ?? runtimeOf(selectedPoint)?.value ?? '-' }}</strong></span>
            <span>更新时间：{{ formatTime(runtimeOf(selectedPoint)?.timestamp || runtimeOf(selectedPoint)?.collectTime) }}</span>
          </div>

          <el-tabs v-model="detailTab" class="point-detail-tabs">
            <el-tab-pane label="基础" name="basic">
              <div class="point-detail-form-grid">
                <label>点位名称<el-input v-model="selectedPoint.pointName" /></label>
                <label>点位编码<el-input v-model="selectedPoint.pointCode" /></label>
                <label>别名<el-input v-model="selectedPoint.pointAlias" /></label>
                <label>地址<el-input v-model="selectedPoint.address" /></label>
                <label>分组<el-input v-model="selectedPoint.groupId" /></label>
                <label>单位<el-input v-model="selectedPoint.unit" /></label>
                <label>数据类型<el-select v-model="selectedPoint.dataType" filterable><el-option v-for="item in dataTypes" :key="item" :label="item" :value="item" /></el-select></label>
                <label>读写<el-select v-model="selectedPoint.readWrite"><el-option label="只读 R" value="R" /><el-option label="只写 W" value="W" /><el-option label="读写 RW" value="RW" /></el-select></label>
                <label class="wide-field">备注<el-input v-model="selectedPoint.remark" type="textarea" :rows="2" /></label>
              </div>
            </el-tab-pane>

            <el-tab-pane label="数据处理" name="data">
              <div class="point-detail-form-grid">
                <label>采集模式<el-select v-model="selectedPoint.collectionMode"><el-option label="轮询" value="POLLING" /><el-option label="订阅" value="SUBSCRIPTION" /><el-option label="事件" value="EVENT" /><el-option label="手动" value="MANUAL" /></el-select></label>
                <label>缩放系数<el-input-number v-model="selectedPoint.scalingFactor" :step="0.1" /></label>
                <label>偏移量<el-input-number v-model="selectedPoint.offset" :step="0.1" /></label>
                <label>死区<el-input-number v-model="selectedPoint.deadband" :step="0.01" /></label>
                <label>最小值<el-input-number v-model="selectedPoint.minValue" /></label>
                <label>最大值<el-input-number v-model="selectedPoint.maxValue" /></label>
                <label>精度<el-input-number v-model="selectedPoint.precision" :min="0" /></label>
                <label>优先级<el-input-number v-model="selectedPoint.priority" :min="0" /></label>
                <label>缓存<el-switch v-model="selectedPoint.cacheEnabled" :active-value="1" :inactive-value="0" /></label>
                <label>缓存时长ms<el-input-number v-model="selectedPoint.cacheDuration" :min="0" /></label>
              </div>
            </el-tab-pane>

            <el-tab-pane label="上报参数" name="report">
              <div class="point-detail-form-grid">
                <label>启用上报<el-switch v-model="additionalConfigModel.reportEnabled" @change="applyAdditionalConfigModel" /></label>
                <label>reportField<el-input v-model="additionalConfigModel.reportField" @change="applyAdditionalConfigModel" /></label>
                <label>变化阈值<el-input-number v-model="additionalConfigModel.changeThreshold" :step="0.01" @change="applyAdditionalConfigModel" /></label>
                <label>最小间隔ms<el-input-number v-model="additionalConfigModel.changeMinIntervalMs" :min="0" @change="applyAdditionalConfigModel" /></label>
                <label>事件上报<el-switch v-model="additionalConfigModel.eventEnabled" @change="applyAdditionalConfigModel" /></label>
                <label>事件间隔ms<el-input-number v-model="additionalConfigModel.eventMinIntervalMs" :min="0" @change="applyAdditionalConfigModel" /></label>
                <label>Redis Stream<el-switch v-model="additionalConfigModel.streamEnabled" @change="applyAdditionalConfigModel" /></label>
                <label>历史存储<el-switch v-model="additionalConfigModel.historyEnabled" @change="applyAdditionalConfigModel" /></label>
              </div>
            </el-tab-pane>

            <el-tab-pane label="协议扩展" name="protocol">
              <el-alert v-if="pointFields.length === 0" title="当前协议没有 pointFields 动态字段" type="info" :closable="false" />
              <div v-else class="protocol-form-grid">
                <el-form-item v-for="field in pointFields" :key="field.name" :label="field.label || field.name" :required="field.required">
                  <el-switch v-if="field.type === 'boolean'" v-model="pointExtraModel[field.name]" @change="applyExtraModel" />
                  <el-select v-else-if="field.options?.length" v-model="pointExtraModel[field.name]" clearable filterable @change="applyExtraModel">
                    <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
                  </el-select>
                  <el-input-number v-else-if="field.type === 'number' || field.type === 'integer'" v-model="pointExtraModel[field.name]" controls-position="right" @change="applyExtraModel" />
                  <el-input v-else v-model="pointExtraModel[field.name]" @change="applyExtraModel" />
                  <small v-if="field.description" class="field-description">{{ field.description }}</small>
                </el-form-item>
              </div>
            </el-tab-pane>

            <el-tab-pane label="告警" name="alarm">
              <div class="point-detail-form-grid">
                <label>启用告警<el-switch v-model="selectedPoint.alarmEnabled" :active-value="1" :inactive-value="0" /></label>
              </div>
              <div class="json-panel-actions">
                <span>alarmRule JSON</span>
                <el-button @click="formatAlarmRuleJson">格式化</el-button>
                <el-button type="primary" plain @click="applyAlarmRuleJson">应用</el-button>
              </div>
              <textarea v-model="alarmRuleText" class="point-json-textarea" spellcheck="false"></textarea>
            </el-tab-pane>

            <el-tab-pane label="JSON" name="json">
              <div class="json-panel-actions">
                <span>additionalConfig JSON</span>
                <el-button @click="formatAdditionalConfigJson">格式化</el-button>
                <el-button type="primary" plain @click="applyAdditionalConfigJson">应用</el-button>
              </div>
              <textarea v-model="additionalConfigText" class="point-json-textarea" spellcheck="false"></textarea>
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else description="请选择一个点位查看详情" />
      </aside>
    </div>

    <PointBatchEditDialog v-model="batchDialogVisible" :selected-count="selectedIds.length" @apply="pointStore.applyBatch(deviceId, $event)" />
    <PointGenerateDialog v-model="generateDialogVisible" @generate="pointStore.appendGeneratedPoints(deviceId, $event)" />

    <el-dialog v-model="importPreviewVisible" title="点位导入预览" width="920px" class="point-import-preview-dialog">
      <div class="point-import-preview-head">
        <div>
          <strong>{{ importPreviewLabel || '导入文件' }}</strong>
          <p v-if="importPreview">{{ importPreview.summary }}</p>
        </div>
        <div class="point-import-preview-stats" v-if="importPreview">
          <el-tag effect="light">共 {{ importPreview.rows.length }} 条</el-tag>
          <el-tag v-if="importPreview.duplicatePointCodes.length" type="warning" effect="light">编码重复 {{ importPreview.duplicatePointCodes.length }}</el-tag>
          <el-tag v-if="importPreview.duplicateAddresses.length" type="warning" effect="light">地址重复 {{ importPreview.duplicateAddresses.length }}</el-tag>
        </div>
      </div>
      <el-alert v-if="importPreview && importPreview.warnings.length" :title="importPreview.warnings.join('；')" type="warning" :closable="false" />
      <div class="point-import-preview-table">
        <table>
          <thead>
            <tr><th>点位名称</th><th>点位编码</th><th>地址</th><th>数据类型</th><th>读写</th><th>单位</th><th>报警</th></tr>
          </thead>
          <tbody>
            <tr v-if="!importPreview || importPreview.rows.length === 0"><td colspan="7" class="exact-empty">未解析到可导入点位</td></tr>
            <tr v-for="row in importPreview?.rows || []" :key="`${row.pointId || row.pointCode || row.address}`">
              <td>{{ row.pointName || '-' }}</td>
              <td>{{ row.pointCode || '-' }}</td>
              <td>{{ row.address || '-' }}</td>
              <td>{{ row.dataType || '-' }}</td>
              <td>{{ row.readWrite || '-' }}</td>
              <td>{{ row.unit || '-' }}</td>
              <td>{{ row.alarmEnabled ? '启用' : '关闭' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <el-button @click="cancelImportPreview">取消</el-button>
        <el-button type="primary" :disabled="!importPreview || importPreview.rows.length === 0" @click="confirmImportPreview">确认导入</el-button>
      </template>
    </el-dialog>
   </section>
 </template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { Search } from "@element-plus/icons-vue";

import { getDeviceRealtimeData } from "@/api/data.api";
import PointBatchEditDialog from "./PointBatchEditDialog.vue";
import PointGenerateDialog from "./PointGenerateDialog.vue";
import { downloadPointWorkbook, parsePointWorkbook } from "./point-excel-utils";
import {
  applyPointExtraModel,
  buildPointExtraModel,
  buildPointImportPreview,
  buildPointLocationTarget,
  formatJsonForTextarea,
  mergePointRuntime,
  parseJsonTextarea,
  type PointExtraModel,
  type PointImportPreview,
  type PointLocationTarget
} from "./point-editor-utils";
import { usePointStore } from "@/stores/point.store";
import type { RealtimePointRow } from "@/types/monitor";
import type { DataPoint } from "@/types/point";
import type { ProtocolSchema } from "@/types/protocol";

const props = defineProps<{
  deviceId: string;
  protocol?: ProtocolSchema | null;
  protocolCode?: string;
}>();

const emit = defineEmits<{
  "open-history": [target: PointLocationTarget];
  "open-realtime": [target: PointLocationTarget];
}>();

const pointStore = usePointStore();
const keyword = ref("");
const batchDialogVisible = ref(false);
const generateDialogVisible = ref(false);
const fileInputRef = ref<HTMLInputElement | null>(null);
const selectedPointId = ref("");
const detailTab = ref("basic");
const realtimeRows = ref<RealtimePointRow[]>([]);
const realtimeLoading = ref(false);
const realtimeError = ref("");
const pointExtraModel = ref<PointExtraModel>({});
const additionalConfigModel = ref<Record<string, unknown>>({});
const additionalConfigText = ref("{}");
const alarmRuleText = ref("{}");
const importPreviewVisible = ref(false);
const importPreview = ref<PointImportPreview | null>(null);
const importPreviewLabel = ref("");

const points = computed(() => pointStore.getPoints(props.deviceId));
const selectedIds = computed(() => pointStore.getSelectedIds(props.deviceId));
const activeProtocol = computed(() => props.protocol || null);
const protocolCode = computed(() => props.protocolCode || activeProtocol.value?.protocol || "");
const pointFields = computed(() => activeProtocol.value?.pointFields || []);
const tablePointFields = computed(() => pointFields.value.slice(0, 3));
const dataTypes = computed(() => activeProtocol.value?.dataTypes?.length
  ? activeProtocol.value.dataTypes
  : ["BOOL", "INT", "UINT", "DINT", "FLOAT", "DOUBLE", "STRING"]);
const runtimeMergedRows = computed(() => mergePointRuntime(points.value, realtimeRows.value));
const filteredPoints = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  if (!value) {
    return points.value;
  }
  return points.value.filter((point) => [point.pointName, point.pointCode, point.address]
    .some((item) => String(item || "").toLowerCase().includes(value)));
});
const selectedPoint = computed(() => points.value.find((point) => point.pointId === selectedPointId.value) || points.value[0] || null);
const deviceId = computed(() => props.deviceId);

function handleSelectionChange(rows: DataPoint[]) {
  pointStore.setSelectedIds(props.deviceId, rows.map((row) => row.pointId || "").filter(Boolean));
}

function selectPoint(row: DataPoint) {
  selectedPointId.value = row.pointId || "";
  syncDetailModels();
}

function exportExcel() {
  downloadPointWorkbook(points.value, `${props.deviceId || 'device'}-points.xlsx`);
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  const content = await file.arrayBuffer();
  const preview = buildPointImportPreview(parsePointWorkbook(content));
  importPreview.value = preview;
  importPreviewLabel.value = file.name;
  importPreviewVisible.value = true;
  input.value = "";
}

async function loadRealtime() {
  if (!props.deviceId) {
    realtimeRows.value = [];
    return;
  }
  realtimeLoading.value = true;
  realtimeError.value = "";
  try {
    const response = await getDeviceRealtimeData(props.deviceId);
    realtimeRows.value = response.points || response.data || response.values || [];
  } catch (error) {
    realtimeError.value = error instanceof Error ? error.message : "实时数据加载失败";
  } finally {
    realtimeLoading.value = false;
  }
}

async function savePoints() {
  applyAdditionalConfigJson(false);
  applyAlarmRuleJson(false);
  await pointStore.save(props.deviceId);
}

function confirmImportPreview() {
  if (!importPreview.value) {
    return;
  }
  pointStore.replacePoints(props.deviceId, importPreview.value.rows);
  selectedPointId.value = pointStore.getPoints(props.deviceId)[0]?.pointId || "";
  syncDetailModels();
  importPreviewVisible.value = false;
  importPreview.value = null;
  importPreviewLabel.value = "";
}

function cancelImportPreview() {
  importPreviewVisible.value = false;
  importPreview.value = null;
  importPreviewLabel.value = "";
}

function emitOpenHistory() {
  if (!selectedPoint.value) {
    return;
  }
  emit("open-history", buildPointLocationTarget(selectedPoint.value, props.deviceId));
}

function emitOpenRealtime() {
  if (!selectedPoint.value) {
    return;
  }
  emit("open-realtime", buildPointLocationTarget(selectedPoint.value, props.deviceId));
}

function runtimeOf(point: DataPoint): RealtimePointRow | undefined {
  return runtimeMergedRows.value.find((row) => row.pointId === point.pointId || row.pointCode === point.pointCode || row.address === point.address);
}

function displayExtraValue(point: DataPoint, fieldName: string): string {
  const model = buildPointExtraModel([{ name: fieldName }], point);
  const value = model[fieldName];
  return value === undefined || value === null || value === "" ? "-" : String(value);
}

function syncDetailModels() {
  const point = selectedPoint.value;
  if (!point) {
    pointExtraModel.value = {};
    additionalConfigModel.value = {};
    additionalConfigText.value = "{}";
    alarmRuleText.value = "{}";
    return;
  }
  pointExtraModel.value = buildPointExtraModel(pointFields.value, point);
  additionalConfigModel.value = { ...(point.additionalConfig || {}) };
  additionalConfigText.value = formatJsonForTextarea(point.additionalConfig || {});
  alarmRuleText.value = formatJsonForTextarea(parseAlarmRule(point.alarmRule));
}

function applyExtraModel() {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  Object.assign(point, applyPointExtraModel(point, pointFields.value, pointExtraModel.value));
  additionalConfigModel.value = { ...(point.additionalConfig || {}) };
  additionalConfigText.value = formatJsonForTextarea(point.additionalConfig || {});
}

function applyAdditionalConfigModel() {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  point.additionalConfig = { ...additionalConfigModel.value };
  additionalConfigText.value = formatJsonForTextarea(point.additionalConfig);
  pointExtraModel.value = buildPointExtraModel(pointFields.value, point);
}

function formatAdditionalConfigJson() {
  additionalConfigText.value = formatJsonForTextarea(parseJsonTextarea(additionalConfigText.value, {}));
}

function applyAdditionalConfigJson(showError = true) {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  try {
    point.additionalConfig = parseJsonTextarea<Record<string, unknown>>(additionalConfigText.value, {});
    additionalConfigModel.value = { ...point.additionalConfig };
    pointExtraModel.value = buildPointExtraModel(pointFields.value, point);
    pointStore.error = "";
  } catch (error) {
    if (showError) {
      pointStore.error = error instanceof Error ? `additionalConfig JSON 格式错误：${error.message}` : "additionalConfig JSON 格式错误";
    }
  }
}

function formatAlarmRuleJson() {
  alarmRuleText.value = formatJsonForTextarea(parseJsonTextarea(alarmRuleText.value, {}));
}

function applyAlarmRuleJson(showError = true) {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  try {
    const parsed = parseJsonTextarea<Record<string, unknown>>(alarmRuleText.value, {});
    point.alarmRule = formatJsonForTextarea(parsed);
    pointStore.error = "";
  } catch (error) {
    if (showError) {
      pointStore.error = error instanceof Error ? `alarmRule JSON 格式错误：${error.message}` : "alarmRule JSON 格式错误";
    }
  }
}

function parseAlarmRule(value: unknown): unknown {
  if (!value) {
    return {};
  }
  if (typeof value === "string") {
    try {
      return JSON.parse(value);
    } catch {
      return { raw: value };
    }
  }
  return value;
}

function formatTime(value: unknown): string {
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

onMounted(async () => {
  await pointStore.load(props.deviceId);
  selectedPointId.value = points.value[0]?.pointId || "";
  syncDetailModels();
  loadRealtime();
});
watch(() => props.deviceId, async (nextDeviceId) => {
  await pointStore.load(nextDeviceId);
  selectedPointId.value = pointStore.getPoints(nextDeviceId)[0]?.pointId || "";
  syncDetailModels();
  loadRealtime();
});
watch(() => [selectedPoint.value?.pointId, pointFields.value.length], () => syncDetailModels());
</script>
