<template>
  <section class="local-editor-pane device-config-workbench-pane">
    <template v-if="device">
      <section class="local-section-card device-info-card">
        <div class="local-section-head">
          <div>
            <span class="label-chip">设备基础</span>
            <h3>设备基础信息</h3>
          </div>
          <span class="status-dot-text" :class="statusToneClass(deviceStatusText)"><i></i>{{ deviceStatusText }}</span>
        </div>
        <div class="device-info-grid">
          <div class="field-card"><span>设备名称</span><strong>{{ device.displayName }}</strong></div>
          <div class="field-card"><span>本地设备 ID</span><strong>{{ device.normalizedId }}</strong></div>
          <div class="field-card"><span>设备分组</span><strong>{{ device.displayGroup || '未分组' }}</strong></div>
          <div class="field-card"><span>协议类型</span><strong>{{ device.displayProtocol }}</strong></div>
          <div class="field-card"><span>IP / 主机</span><strong>{{ device.ipAddress || device.host || '-' }}</strong></div>
          <div class="field-card"><span>端口</span><strong>{{ device.port || '-' }}</strong></div>
          <div class="field-card"><span>采集周期</span><strong>{{ device.collectionInterval || '-' }} ms</strong></div>
          <div class="field-card"><span>配置来源</span><strong>{{ device.configSource || 'local' }}</strong></div>
        </div>
      </section>

      <div class="device-control-grid control-row">
        <section class="connection-test-card local-section-card run-control-card">
          <div class="local-section-head">
            <div>
              <span class="label-chip">运行控制</span>
              <h3>运行控制</h3>
            </div>
            <p>读取当前设备运行态、连接状态和最近消息。</p>
          </div>
          <div class="control-status-row">
            <div class="state-pill"><span>运行状态</span><strong :class="statusToneClass(connectionStatusText)"><i></i>{{ connectionStatusText }}</strong></div>
            <div class="state-pill"><span>连接</span><strong :class="statusToneClass(connectionHealthText)"><i></i>{{ connectionHealthText }}</strong></div>
            <div class="state-pill state-pill-message"><span>最近消息</span><strong>{{ connectionMessage || statusDetail?.message || '连接正常' }}</strong></div>
            <div class="header-actions run-control-actions">
              <el-button :loading="statusLoading" @click="loadConnectionStatus">{{ statusLoading ? '检查中' : '连接检查' }}</el-button>
              <el-button type="primary" @click="$emit('start', device.normalizedId)">启动采集</el-button>
              <el-button type="danger" plain @click="$emit('stop', device.normalizedId)">停止采集</el-button>
            </div>
          </div>
        </section>

        <section class="local-section-card quick-nav-card">
          <div class="local-section-head">
            <div>
              <span class="label-chip">快捷导航</span>
              <h3>快捷导航</h3>
            </div>
          </div>
          <div class="workbench-jump-row quick-actions">
            <button type="button" @click="setActiveTab('points')">点位</button>
            <button type="button" @click="setActiveTab('realtime')">实时</button>
            <button type="button" @click="setActiveTab('alarm')">告警</button>
            <button type="button" @click="setActiveTab('log')">日志</button>
          </div>
        </section>
      </div>

      <details class="protocol-schema-card local-section-card protocol-config-card protocol-config-collapse">
        <summary>
          <span>高级协议连接配置</span>
          <small>字段校验通过 · {{ connectionMessage || '连接参数按需展开' }}</small>
        </summary>
        <div class="schema-head">
          <div>
            <span class="label-chip">协议连接</span>
            <h3>协议连接配置</h3>
            <p>字段由后端协议 Schema 动态生成，保存时按字段定义写入连接配置。</p>
          </div>
          <div class="schema-toolbar">
            <el-button :loading="protocolLoading" @click="loadProtocolConfig">读取连接配置</el-button>
            <el-button @click="showDiff">查看配置差异</el-button>
            <el-button type="primary" :loading="savingConnection" @click="saveProtocolConfig">保存协议配置</el-button>
          </div>
        </div>
        <el-alert v-if="protocolError" :title="protocolError" type="warning" :closable="false" />
        <div v-if="protocolSchema" class="protocol-capability-strip">
          <el-tag effect="plain">{{ protocolSchema.title || protocolSchema.protocol }}</el-tag>
          <el-tag :type="capabilityTag(protocolSchema.implementationState)" effect="light">实现：{{ protocolSchema.implementationState || '-' }}</el-tag>
          <el-tag :type="capabilityTag(protocolSchema.writeCapability)" effect="light">写入：{{ protocolSchema.writeCapability || '-' }}</el-tag>
          <el-tag :type="capabilityTag(protocolSchema.subscriptionCapability)" effect="light">订阅：{{ protocolSchema.subscriptionCapability || '-' }}</el-tag>
        </div>
        <ProtocolDynamicForm v-model="protocolModel" :fields="protocolFields" @validate="protocolErrors = $event" />
        <div class="schema-actions">
          <el-tag v-if="protocolErrors.length === 0" type="success" effect="light">字段校验通过</el-tag>
          <el-tag v-else type="warning" effect="light">{{ protocolErrors.length }} 个字段待完善</el-tag>
          <span v-if="connectionMessage" class="schema-message">{{ connectionMessage }}</span>
        </div>
      </details>

      <section class="local-section-card device-data-panel">
        <div class="device-data-topline">
          <div class="device-inner-tabbar" role="tablist" aria-label="设备运行数据分区">
            <button
              v-for="tab in dataTabs"
              :key="tab.key"
              type="button"
              class="device-inner-tab"
              :class="{ 'is-active': activeTab === tab.key }"
              @click="setActiveTab(tab.key)"
            >
              {{ tab.label }}
            </button>
          </div>
          <div class="schema-toolbar data-toolbar">
            <el-button :loading="workbenchRowsLoading" @click="loadWorkbenchRows">刷新数据</el-button>
            <el-button @click="pointEditVisible = !pointEditVisible">{{ pointEditVisible ? '收起编辑' : '编辑点位' }}</el-button>
          </div>
        </div>

        <el-alert v-if="workbenchRowsError" :title="workbenchRowsError" type="warning" :closable="false" />

        <template v-if="activeTab === 'points'">
          <div class="point-data-meta-row">
            <span>共 {{ pointRows.length }} 条</span>
            <span>页大小：{{ pageSize }} 条/页</span>
            <span>设备：{{ device.normalizedId }}</span>
          </div>
          <div class="point-content point-data-grid">
            <div class="point-data-table-column table-area">
              <div class="table-scroll">
                <el-table v-loading="workbenchRowsLoading" :data="pagedPointRows" border class="industrial-point-table" highlight-current-row @row-click="selectWorkbenchPoint">
                  <el-table-column prop="pointCode" label="点位编码" min-width="140" />
                  <el-table-column prop="pointName" label="点位名称" min-width="140" />
                  <el-table-column prop="address" label="地址" min-width="96" />
                  <el-table-column prop="dataType" label="数据类型" width="96" />
                  <el-table-column prop="readWrite" label="读写" width="68" />
                  <el-table-column label="当前值" min-width="110"><template #default="{ row }">{{ displayPointValue(row) }}</template></el-table-column>
                  <el-table-column label="质量" width="92"><template #default="{ row }"><span class="quality-dot" :class="qualityToneClass(row)"><i></i>{{ qualityText(row) }}</span></template></el-table-column>
                  <el-table-column label="时间戳" min-width="150"><template #default="{ row }">{{ formatPointTime(row) }}</template></el-table-column>
                  <el-table-column label="操作" width="78" fixed="right"><template #default="{ row }"><button type="button" class="table-link-button" @click.stop="handlePointAction(row)">{{ pointActionText(row) }}</button></template></el-table-column>
                </el-table>
              </div>
              <div class="industrial-pagination-row">
                <span>共 {{ pointRows.length }} 条</span>
                <el-pagination
                  v-model:current-page="currentPage"
                  v-model:page-size="pageSize"
                  :page-sizes="[10, 20, 40]"
                  :total="pointRows.length"
                  layout="sizes, prev, pager, next, jumper"
                  background
                  small
                />
              </div>
            </div>
            <aside class="compact-point-detail">
              <div class="compact-detail-head">
                <span class="label-chip">点位详情</span>
                <button type="button" @click="pointEditVisible = !pointEditVisible">{{ pointEditVisible ? '收起' : '完整编辑' }}</button>
              </div>
              <template v-if="selectedWorkbenchPoint">
                <strong>{{ selectedWorkbenchPoint.pointName || selectedWorkbenchPoint.pointCode || '未命名点位' }}</strong>
                <p>{{ selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.pointId || '-' }} · {{ selectedWorkbenchPoint.address || '-' }}</p>
                <div class="compact-detail-grid">
                  <label>点位名称<input :value="selectedWorkbenchPoint.pointName || '-'" readonly /></label>
                  <label>点位编码<input :value="selectedWorkbenchPoint.pointCode || '-'" readonly /></label>
                  <label>地址<input :value="selectedWorkbenchPoint.address || '-'" readonly /></label>
                  <label>数据类型<input :value="selectedWorkbenchPoint.dataType || '-'" readonly /></label>
                  <label>读写<input :value="selectedWorkbenchPoint.readWrite || '-'" readonly /></label>
                  <label>当前值<input :value="displayPointValue(selectedWorkbenchPoint)" readonly /></label>
                  <label>质量<input :value="qualityText(selectedWorkbenchPoint)" readonly /></label>
                  <label>时间戳<input :value="formatPointTime(selectedWorkbenchPoint)" readonly /></label>
                </div>
                <div class="compact-detail-actions">
                  <button type="button" @click="handlePointAction(selectedWorkbenchPoint)">查看实时</button>
                  <button type="button" @click="$emit('open-history', { deviceId: device.normalizedId, pointRef: String(selectedWorkbenchPoint.pointId || selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.address || ''), pointName: selectedWorkbenchPoint.pointName, pointLabel: selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.pointName })">查看历史</button>
                </div>
              </template>
              <el-empty v-else description="点击表格行查看点位详情" />
            </aside>
          </div>
          <PointEditor v-if="pointEditVisible" class="embedded-point-editor" :device-id="device.normalizedId" :protocol="protocolSchema" :protocol-code="protocolKey" @open-history="$emit('open-history', $event)" @open-realtime="$emit('open-realtime', $event)" />
        </template>
        <RealtimeDataPanel v-else-if="activeTab === 'realtime'" :device-id="device.normalizedId" />
        <AlarmTablePanel v-else-if="activeTab === 'alarm'" :device-id="device.normalizedId" />
        <LogPanel v-else :device-id="device.normalizedId" />
      </section>

      <el-dialog v-model="diffVisible" title="配置差异" width="720px" class="device-operation-dialog">
        <pre class="json-view">{{ diffText }}</pre>
      </el-dialog>
    </template>
    <div v-else class="empty-config">
      <el-empty description="请从左侧设备树或设备列表选择设备" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { getDeviceConnection, getDeviceDiff, updateDeviceConnection } from "@/api/config.api";
import { getDeviceRealtimeData } from "@/api/data.api";
import { getDeviceStatus } from "@/api/device.api";
import { getProtocol } from "@/api/protocol.api";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import AlarmTablePanel from "@/components/alarm/AlarmTablePanel.vue";
import LogPanel from "@/components/log/LogPanel.vue";
import PointEditor from "@/features/point/components/PointEditor.vue";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import RealtimeDataPanel from "@/components/realtime/RealtimeDataPanel.vue";
import { resolveDeviceStatus } from "@/stores/device.store";
import { normalizeDeviceStatusDetail, type DeviceStatusDetail } from "@/features/diagnostic/utils/device-runtime-utils";
import { buildConnectionPayload, extractProtocolModel, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import type { DeviceViewModel } from "@/types/device";
import type { RealtimePointRow } from "@/types/monitor";
import type { ProtocolSchema } from "@/types/protocol";

const props = defineProps<{
  device: DeviceViewModel | null;
}>();

const emit = defineEmits<{
  start: [deviceId: string];
  stop: [deviceId: string];
  "open-history": [{ deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }];
  "open-realtime": [{ deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }];
}>();

type DeviceDataTab = "points" | "realtime" | "alarm" | "log";

const activeTab = ref<DeviceDataTab>("points");
const dataTabs: Array<{ key: DeviceDataTab; label: string }> = [
  { key: "points", label: "点位列表" },
  { key: "realtime", label: "实时数据" },
  { key: "alarm", label: "告警" },
  { key: "log", label: "日志" }
];
const protocolModel = ref<ProtocolFormModel>({});
const protocolErrors = ref<string[]>([]);
const protocolSchema = ref<ProtocolSchema | null>(null);
const connectionConfig = ref<ConnectionPayload>({});
const protocolLoading = ref(false);
const savingConnection = ref(false);
const protocolError = ref("");
const connectionMessage = ref("");
const diffVisible = ref(false);
const diffText = ref("{}");
const statusDetail = ref<DeviceStatusDetail | null>(null);
const statusLoading = ref(false);
const workbenchRows = ref<RealtimePointRow[]>([]);
const workbenchRowsLoading = ref(false);
const workbenchRowsError = ref("");
const currentPage = ref(1);
const pageSize = ref(10);
const pointEditVisible = ref(false);
const selectedWorkbenchPoint = ref<RealtimePointRow | null>(null);

const protocolKey = computed(() => String(props.device?.protocolType || props.device?.connectionType || ""));
const protocolFields = computed(() => protocolSchema.value?.connectionFields || []);
const pointRows = computed(() => workbenchRows.value);
const pagedPointRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return pointRows.value.slice(start, start + pageSize.value);
});

const connectionStatusText = computed(() => {
  if (!statusDetail.value) {
    return resolveDeviceStatus(props.device || ({ status: "OFFLINE" } as DeviceViewModel));
  }
  return statusDetail.value.isRunning || statusDetail.value.running ? "RUNNING" : (statusDetail.value.connected ? "ONLINE" : (statusDetail.value.degradedReason ? "ERROR" : "OFFLINE"));
});
const connectionHealthText = computed(() => {
  if (!statusDetail.value) {
    return props.device ? resolveDeviceStatus(props.device) : "OFFLINE";
  }
  if (statusDetail.value.connected && !statusDetail.value.degradedReason) {
    return "正常";
  }
  if (statusDetail.value.degradedReason) {
    return statusDetail.value.degradedReason;
  }
  return statusDetail.value.isRunning || statusDetail.value.running ? "运行中" : "未连接";
});


const deviceStatusText = computed(() => {
  const status = props.device ? resolveDeviceStatus(props.device) : "OFFLINE";
  return {
    ONLINE: "在线",
    CONNECTING: "重连中",
    ERROR: "异常",
    DISABLED: "禁用",
    OFFLINE: "离线"
  }[status];
});

async function loadProtocolConfig() {
  if (!props.device || !protocolKey.value) {
    return;
  }
  protocolLoading.value = true;
  protocolError.value = "";
  connectionMessage.value = "";
  try {
    const [schema, connection] = await Promise.all([
      getProtocol(protocolKey.value),
      getDeviceConnection(props.device.normalizedId)
    ]);
    protocolSchema.value = schema;
    connectionConfig.value = normalizeConnectionPayload(connection);
    protocolModel.value = extractProtocolModel(protocolFields.value, connectionConfig.value);
    connectionMessage.value = "连接配置已读取";
  } catch (error) {
    protocolError.value = error instanceof Error ? error.message : "协议连接配置加载失败";
  } finally {
    protocolLoading.value = false;
  }
}

async function loadConnectionStatus() {
  if (!props.device) {
    return;
  }
  statusLoading.value = true;
  try {
    statusDetail.value = normalizeDeviceStatusDetail(await getDeviceStatus(props.device.normalizedId), props.device.normalizedId);
    connectionMessage.value = statusDetail.value.message || (statusDetail.value.connected ? "连接正常" : "连接异常");
    if (!connectionMessage.value) {
      connectionMessage.value = statusDetail.value.connected ? "连接正常" : "连接异常";
    }
  } catch (error) {
    statusDetail.value = null;
    connectionMessage.value = error instanceof Error ? error.message : "连接状态检查失败";
  } finally {
    statusLoading.value = false;
  }
}

async function loadWorkbenchRows() {
  if (!props.device) {
    workbenchRows.value = [];
    return;
  }
  workbenchRowsLoading.value = true;
  workbenchRowsError.value = "";
  try {
    workbenchRows.value = normalizeRealtimeRows(await getDeviceRealtimeData(props.device.normalizedId), props.device.normalizedId);
    selectedWorkbenchPoint.value = resolveSelectedWorkbenchPoint(workbenchRows.value, selectedWorkbenchPoint.value);
    currentPage.value = 1;
  } catch (error) {
    workbenchRowsError.value = error instanceof Error ? error.message : "点位运行数据加载失败";
  } finally {
    workbenchRowsLoading.value = false;
  }
}

async function saveProtocolConfig() {
  if (!props.device) {
    return;
  }
  const errors = validateProtocolModel(protocolFields.value, protocolModel.value);
  protocolErrors.value = errors;
  if (errors.length > 0) {
    protocolError.value = "请先修正协议字段校验错误";
    return;
  }
  savingConnection.value = true;
  protocolError.value = "";
  try {
    const payload = buildConnectionPayload(protocolFields.value, protocolModel.value, {
      ...connectionConfig.value,
      deviceId: props.device.normalizedId,
      connectionType: protocolKey.value,
      protocolType: protocolKey.value
    });
    await updateDeviceConnection(props.device.normalizedId, payload);
    connectionConfig.value = payload;
    connectionMessage.value = "协议连接配置已保存";
    ElMessage.success("协议连接配置已保存");
  } catch (error) {
    protocolError.value = error instanceof Error ? error.message : "协议连接配置保存失败";
  } finally {
    savingConnection.value = false;
  }
}

async function showDiff() {
  if (!props.device) {
    return;
  }
  protocolError.value = "";
  try {
    const diff = await getDeviceDiff(props.device.normalizedId);
    diffText.value = JSON.stringify(diff, null, 2);
    diffVisible.value = true;
  } catch (error) {
    protocolError.value = error instanceof Error ? error.message : "配置差异加载失败";
  }
}

function normalizeConnectionPayload(value: unknown): ConnectionPayload {
  return value && typeof value === "object" && !Array.isArray(value) ? value as ConnectionPayload : {};
}

function resolveSelectedWorkbenchPoint(rows: RealtimePointRow[], current: RealtimePointRow | null): RealtimePointRow | null {
  if (!rows.length) {
    return null;
  }
  const currentKey = pointRowKey(current);
  return rows.find((row) => pointRowKey(row) === currentKey) || rows[0];
}

function pointRowKey(row: RealtimePointRow | null): string {
  return row ? String(row.pointId || row.pointCode || row.address || "") : "";
}

function capabilityTag(value?: string): "success" | "warning" | "danger" | "info" {
  if (value === "SUPPORTED") {
    return "success";
  }
  if (value === "EXPERIMENTAL" || value === "RUNTIME_DEPENDENT") {
    return "warning";
  }
  if (value === "UNSUPPORTED") {
    return "info";
  }
  return "info";
}

function setActiveTab(tab: DeviceDataTab) {
  activeTab.value = tab;
  if (tab === "points") {
    loadWorkbenchRows().catch(() => undefined);
  }
}

function statusToneClass(value?: string): string {
  const normalized = String(value || "").toUpperCase();
  if (["ONLINE", "RUNNING", "正常", "在线"].includes(normalized) || String(value || "").includes("正常")) {
    return "is-success";
  }
  if (["ERROR", "异常", "离线", "OFFLINE"].includes(normalized)) {
    return normalized === "ERROR" || normalized === "异常" ? "is-danger" : "is-muted";
  }
  if (["CONNECTING", "重连中", "WARNING"].includes(normalized)) {
    return "is-warning";
  }
  return "is-muted";
}

function displayPointValue(row: RealtimePointRow): string {
  const value = row.currentValue ?? row.value ?? row.processedValue ?? row.rawValue ?? "-";
  return typeof value === "object" && value !== null ? JSON.stringify(value) : String(value);
}

function qualityText(row: RealtimePointRow): string {
  const quality = String(row.qualityLevel ?? row.quality ?? "").toUpperCase();
  if (["GOOD", "OK", "SUCCESS", "A", "100"].includes(quality) || row.qualityAcceptable === true || row.processSuccess === true) {
    return "GOOD";
  }
  if (["BAD", "ERROR", "FAILED"].includes(quality) || row.processSuccess === false) {
    return "BAD";
  }
  if (["UNCERTAIN", "WARN", "WARNING", "B", "C"].includes(quality)) {
    return "WARN";
  }
  return "UNKNOWN";
}

function qualityToneClass(row: RealtimePointRow): string {
  const text = qualityText(row);
  if (text === "GOOD") {
    return "is-success";
  }
  if (text === "BAD") {
    return "is-danger";
  }
  if (text === "WARN") {
    return "is-warning";
  }
  return "is-muted";
}

function formatPointTime(row: RealtimePointRow): string {
  const metadata = row.metadata && typeof row.metadata === "object" ? row.metadata as Record<string, unknown> : {};
  const value = row.timestamp || row.collectTime || metadata.collectTime || metadata.timestamp || metadata.updatedAt;
  if (typeof value === "number") {
    return new Date(value).toLocaleString();
  }
  return value ? String(value) : "-";
}

function pointActionText(row: RealtimePointRow): string {
  return String(row.readWrite || "").includes("W") ? "写入" : "查看";
}

function handlePointAction(row: RealtimePointRow) {
  if (!props.device) {
    return;
  }
  const pointRef = String(row.pointId || row.pointCode || row.address || "");
  if (!pointRef) {
    return;
  }
  if (pointActionText(row) === "写入") {
    ElMessage.info("请切换到批量和协议命令执行写入操作");
    return;
  }
  emit("open-realtime", {
    deviceId: props.device.normalizedId,
    pointRef,
    pointName: row.pointName,
    pointLabel: row.pointCode || row.pointName
  });
}

function selectWorkbenchPoint(row: RealtimePointRow) {
  selectedWorkbenchPoint.value = row;
}

watch(activeTab, (tab) => {
  if (tab === "points") {
    loadProtocolConfig().catch(() => undefined);
  }
});

watch(() => props.device?.normalizedId, () => {
  statusDetail.value = null;
  connectionMessage.value = "";
  selectedWorkbenchPoint.value = null;
  if (props.device) {
    loadConnectionStatus().catch(() => undefined);
    loadWorkbenchRows().catch(() => undefined);
  }
}, { immediate: true });

watch(protocolKey, () => {
  protocolModel.value = {};
  protocolErrors.value = [];
  protocolSchema.value = null;
  connectionConfig.value = {};
  protocolError.value = "";
  connectionMessage.value = "";
  if (activeTab.value === "points") {
    loadProtocolConfig().catch(() => undefined);
  }
});
</script>

<style scoped>
.device-config-workbench-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
}

.local-section-card {
  min-width: 0;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.device-info-card,
.connection-test-card,
.quick-nav-card,
.protocol-schema-card,
.device-data-panel {
  padding: 10px 12px;
}

.local-section-head,
.schema-head,
.device-data-topline {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.local-section-head,
.schema-head {
  margin-bottom: 8px;
}

.local-section-head h3,
.schema-head h3 {
  margin: 0;
  color: var(--console-text-primary);
  font-size: 14px;
  line-height: 1.2;
}

.local-section-head p,
.schema-head p,
.schema-message {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 12px;
}

.run-control-card .local-section-head p,
.quick-nav-card .local-section-head p {
  display: none;
}

.label-chip {
  display: inline-flex;
  min-height: 20px;
  padding: 2px 7px;
  align-items: center;
  border-radius: 999px;
  color: #bfdbfe;
  background: rgba(37, 99, 235, 0.18);
  font-size: 11px;
  font-weight: 800;
  line-height: 1.2;
}

.device-info-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.field-card,
.state-pill {
  display: grid;
  min-width: 0;
  min-height: 44px;
  padding: 7px 9px;
  gap: 3px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.field-card span,
.state-pill span,
.compact-detail-grid label {
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.15;
}

.field-card strong,
.state-pill strong {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-secondary);
  font-size: 13px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-dot-text,
.state-pill strong,
.quality-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-dot-text i,
.state-pill strong i,
.quality-dot i {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
}

.device-control-grid.control-row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(360px, 1fr);
  gap: 12px;
  align-items: stretch;
  grid-auto-rows: minmax(92px, auto);
}

.run-control-card,
.quick-nav-card {
  display: flex;
  min-height: 92px;
  max-height: 105px;
  flex-direction: column;
  justify-content: flex-start;
  gap: 0;
}

.run-control-card .label-chip,
.quick-nav-card .label-chip {
  display: none;
}

.control-status-row {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  flex-wrap: nowrap;
}

.state-pill {
  flex: 0 0 118px;
  width: 118px;
  height: 44px;
}

.state-pill-message {
  flex-basis: 130px;
  width: 130px;
}

.run-control-actions {
  display: flex;
  width: auto;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  flex-wrap: nowrap;
}

.quick-actions,
.workbench-jump-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.quick-actions button {
  width: 100%;
  min-height: 30px;
  height: 30px;
  padding: 0 8px;
}

.protocol-config-collapse {
  min-height: 0;
  padding: 0;
  overflow: hidden;
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.protocol-config-collapse > summary {
  display: flex;
  min-height: 44px;
  padding: 0 12px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-secondary);
  border-radius: var(--console-radius-panel);
  cursor: pointer;
  list-style: none;
}

.protocol-config-collapse > summary::-webkit-details-marker {
  display: none;
}

.protocol-config-collapse > summary::after {
  color: var(--console-text-muted);
  content: "展开";
  font-size: 11px;
}

.protocol-config-collapse[open] > summary {
  border-bottom: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel) var(--console-radius-panel) 0 0;
  background: var(--console-bg-soft);
}

.protocol-config-collapse[open] > summary::after {
  content: "收起";
}

.protocol-config-collapse > summary span {
  color: var(--console-text-primary);
  font-size: 13px;
  font-weight: 800;
}

.protocol-config-collapse > summary small {
  margin-left: auto;
  color: var(--console-text-muted);
  font-size: 11px;
}

.protocol-config-collapse[open] > .schema-head,
.protocol-config-collapse[open] > .protocol-capability-strip,
.protocol-config-collapse[open] > .dynamic-form,
.protocol-config-collapse[open] > .schema-actions,
.protocol-config-collapse[open] > .el-alert {
  margin: 10px 12px;
}

.protocol-config-collapse :deep(.dynamic-form) {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  column-gap: 16px;
  row-gap: 10px;
  align-items: start;
}

.protocol-config-collapse :deep(.protocol-field-group),
.protocol-config-collapse :deep(.protocol-form-grid) {
  display: contents;
}

.protocol-config-collapse :deep(.protocol-field-group h4) {
  display: none;
}

.protocol-config-collapse :deep(.protocol-field-row) {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  min-height: 30px;
  align-items: center;
  gap: 8px;
}

.protocol-config-collapse :deep(.protocol-field-row.is-wide) {
  grid-column: span 2;
}

.protocol-config-collapse :deep(.protocol-field-control .el-input),
.protocol-config-collapse :deep(.protocol-field-control .el-input-number),
.protocol-config-collapse :deep(.protocol-field-control .el-select) {
  width: 100%;
}

.device-data-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.device-data-topline {
  min-height: 36px;
  margin-bottom: 10px;
}

.device-inner-tabbar {
  display: inline-flex;
  min-height: 34px;
  padding: 3px;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.device-inner-tab {
  min-height: 28px;
  padding: 0 11px;
  color: var(--console-text-muted);
  border-color: transparent;
  border-radius: var(--console-radius-sm);
  background: transparent;
  font-size: 12px;
}

.device-inner-tab:hover {
  color: var(--console-text-secondary);
  border-color: var(--console-border-active);
}

.device-inner-tab.is-active {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.data-toolbar,
.schema-toolbar,
.schema-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: nowrap;
}

.protocol-capability-strip,
.point-data-meta-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.point-data-meta-row {
  min-height: 24px;
  margin: 0 0 8px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.2;
}

.point-content,
.point-data-grid {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 12px;
  align-items: stretch;
}

.point-data-table-column,
.table-area {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.table-scroll {
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
}

.table-scroll > .industrial-point-table {
  min-width: 100%;
}

.industrial-point-table {
  overflow: hidden;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
}

.quality-dot {
  font-size: 12px;
  font-weight: 700;
}

.table-link-button {
  min-height: 24px;
  padding: 0;
  color: #93c5fd;
  border: 0;
  background: transparent;
}

.industrial-pagination-row {
  display: flex;
  min-height: 34px;
  margin-top: 6px;
  padding: 4px 8px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.compact-point-detail {
  width: 300px;
  max-width: 300px;
  min-width: 0;
  min-height: 0;
  padding: 10px;
  align-self: stretch;
  overflow: auto;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-bg-soft);
}

.compact-detail-head,
.compact-detail-actions {
  display: flex;
  margin-bottom: 8px;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.compact-point-detail > strong {
  display: block;
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: 13px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.compact-point-detail > p {
  margin: 3px 0 8px;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.compact-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.compact-detail-grid label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.compact-detail-grid input {
  width: 100%;
  height: 30px;
  min-height: 30px;
  padding: 0 7px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.compact-detail-actions {
  justify-content: flex-end;
  margin: 9px 0 0;
}

.embedded-point-editor {
  max-height: 420px;
  margin-top: 10px;
  padding-top: 10px;
  overflow: auto;
  border-top: 1px solid var(--console-border-soft);
}

@media (max-width: 1600px) {
  .protocol-config-collapse :deep(.dynamic-form) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1440px) {
  .device-info-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .point-content,
  .point-data-grid {
    grid-template-columns: 1fr;
  }

  .compact-point-detail {
    width: 100%;
    max-width: none;
  }

  .protocol-config-collapse :deep(.dynamic-form) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .device-control-grid.control-row {
    grid-template-columns: 1fr;
  }

  .control-status-row,
  .device-data-topline,
  .industrial-pagination-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .state-pill,
  .state-pill-message {
    width: 100%;
    flex-basis: auto;
  }
}
</style>
