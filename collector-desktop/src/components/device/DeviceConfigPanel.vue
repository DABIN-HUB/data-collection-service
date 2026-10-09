<template>
  <section class="local-editor-pane device-config-workbench-pane">
    <template v-if="device">
      <section class="local-section-card run-control-card" aria-label="设备运行状态">
        <div class="runtime-heading"><h3>运行状态</h3><span>设备当前运行态</span></div>
        <dl class="control-status-row">
          <div class="state-pill"><dt>生命周期</dt><dd :class="statusToneClass(runtimeView.lifecycle)"><i></i>{{ runtimeView.lifecycle }}</dd></div>
          <div class="state-pill"><dt>传输 / 协议</dt><dd>{{ runtimeView.transportProtocol }}</dd></div>
          <div class="state-pill"><dt>采集健康</dt><dd :class="statusToneClass(runtimeView.health)">{{ runtimeView.health }}</dd></div>
          <div class="state-pill"><dt>最近有效数据</dt><dd>{{ runtimeView.lastValid }}</dd></div>
        </dl>
        <div class="run-control-actions">
          <el-button :loading="statusLoading" @click="loadConnectionStatus">{{ statusLoading ? '检查中' : '连接检查' }}</el-button>
          <el-button type="primary" :disabled="savingConnection" @click="$emit('start', device.normalizedId)">启动采集</el-button>
          <el-button type="danger" plain :disabled="savingConnection" @click="$emit('stop', device.normalizedId)">停止采集</el-button>
        </div>
      </section>
      <div class="runtime-note" role="status">
        <span>{{ runtimeView.points }}</span>
        <span v-if="runtimeView.reason">最近消息：{{ runtimeView.reason }}</span>
      </div>
      <el-alert v-if="deviceStore.runtimeErrors[device.normalizedId] || deviceStore.deviceErrors[device.normalizedId]" :title="deviceStore.runtimeErrors[device.normalizedId] || deviceStore.deviceErrors[device.normalizedId]" type="warning" :closable="false" />
      <details :key="`${device.normalizedId}:${protocolKey}`" class="protocol-schema-card local-section-card protocol-config-card protocol-config-collapse" open>
        <summary>
          <span>协议连接配置</span>
          <button v-if="protocolSchema" type="button" class="protocol-name-badge" title="查看协议说明与能力" @click.stop.prevent="protocolDetailsVisible = true">{{ protocolSchema.title || protocolSchema.protocol }}</button>
          <span v-if="protocolSchema" class="protocol-field-count">{{ protocolFields.length }} 个连接字段</span>
          <small :class="protocolValidationTone">{{ protocolValidationText }}</small>
        </summary>
        <el-alert v-if="protocolError" :title="protocolError" type="warning" :closable="false" />
        <WorkbenchProtocolForm
          v-if="protocolSchema"
          :key="`${device.normalizedId}:${protocolKey}`"
          v-model="protocolModel"
          :fields="protocolFields"
          :protocol="protocolSchema.protocol"
          :disabled="protocolLoading || savingConnection"
          @validate="protocolErrors = $event"
        />
        <div class="schema-actions">
          <div class="schema-message"><span>保存更新协议连接配置；点位修改请在点位编辑中保存。</span><span v-if="connectionMessage" role="status">{{ connectionMessage }}</span></div>
          <div class="schema-toolbar">
            <el-button :loading="protocolLoading" :disabled="savingConnection" @click="loadProtocolConfig">读取连接配置</el-button>
            <el-button @click="showDiff">查看配置差异</el-button>
            <el-button type="primary" :loading="savingConnection" :disabled="protocolLoading || !configBundle || !protocolSchema" @click="saveProtocolConfig">保存协议配置</el-button>
          </div>
        </div>
      </details>

      <section class="local-section-card device-data-panel">
        <div class="device-data-topline">
          <nav class="device-inner-tabbar" aria-label="设备运行数据分区">
            <button
              v-for="tab in dataTabs"
              :key="tab.key"
              type="button"
              class="device-inner-tab"
              :class="{ 'is-active': activeTab === tab.key }"
              :aria-current="activeTab === tab.key ? 'page' : undefined"
              @click="setActiveTab(tab.key)"
            >
              {{ tab.label }}
              <span v-if="tab.key === 'points'" class="tab-count">{{ pointRows.length }}</span>
            </button>
          </nav>
          <div class="schema-toolbar data-toolbar">
            <el-button :loading="workbenchRowsLoading" @click="loadWorkbenchRows">刷新数据</el-button>
            <el-button @click="pointEditVisible = !pointEditVisible">{{ pointEditVisible ? '收起编辑' : '编辑点位' }}</el-button>
          </div>
        </div>

        <el-alert v-if="workbenchRowsError" :title="workbenchRowsError" type="warning" :closable="false" />

        <template v-if="activeTab === 'points'">
          <div class="point-data-meta-row">
            <span>共 {{ pointRows.length }} 个点位</span>
            <span>当前设备：{{ device.normalizedId }}</span>
            <span v-if="selectedWorkbenchPoint" class="selected-point-label">已选择 {{ selectedWorkbenchPoint.pointName || selectedWorkbenchPoint.pointCode }}</span>
          </div>
          <div class="point-content point-data-grid">
            <div class="point-data-table-column table-area">
              <div class="table-scroll">
                <el-table v-loading="workbenchRowsLoading" :data="pagedPointRows" :row-key="pointRowKey" :row-class-name="pointRowClassName" class="industrial-point-table" highlight-current-row empty-text="暂无点位运行数据，可刷新数据或打开点位编辑查看配置" @row-click="selectWorkbenchPoint">
                  <el-table-column prop="pointCode" label="点位编码" min-width="140" />
                  <el-table-column prop="pointName" label="点位名称" min-width="140" />
                  <el-table-column prop="address" label="地址" min-width="96" />
                  <el-table-column prop="dataType" label="数据类型" width="96" />
                  <el-table-column prop="readWrite" label="读写" width="68" />
                  <el-table-column label="当前值" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ displayPointValue(row) }}</template></el-table-column>
                  <el-table-column label="质量" width="92"><template #default="{ row }"><span class="quality-dot" :class="qualityToneClass(row)"><i></i>{{ qualityText(row) }}</span></template></el-table-column>
                  <el-table-column label="时间戳" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ formatPointTime(row) }}</template></el-table-column>
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
                <h3>点位详情</h3>
                <button type="button" @click="pointEditVisible = !pointEditVisible">{{ pointEditVisible ? '收起' : '完整编辑' }}</button>
              </div>
              <template v-if="selectedWorkbenchPoint">
                <strong>{{ selectedWorkbenchPoint.pointName || selectedWorkbenchPoint.pointCode || '未命名点位' }}</strong>
                <p>{{ selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.pointId || '-' }}</p>
                <div class="point-value-display">
                  <div><span>当前值</span><span class="quality-dot" :class="qualityToneClass(selectedWorkbenchPoint)"><i></i>{{ qualityText(selectedWorkbenchPoint) }}</span></div>
                  <strong>{{ displayPointValue(selectedWorkbenchPoint) }}<small v-if="selectedWorkbenchPoint.unit">{{ selectedWorkbenchPoint.unit }}</small></strong>
                </div>
                <dl class="compact-detail-grid">
                  <dt>地址</dt><dd>{{ selectedWorkbenchPoint.address || '-' }}</dd>
                  <dt>数据类型</dt><dd>{{ selectedWorkbenchPoint.dataType || '-' }}</dd>
                  <dt>读写权限</dt><dd>{{ selectedWorkbenchPoint.readWrite || '-' }}</dd>
                  <dt>更新时间</dt><dd>{{ formatPointTime(selectedWorkbenchPoint) }}</dd>
                </dl>
                <div class="compact-detail-actions">
                  <button type="button" @click="openPointRealtime(selectedWorkbenchPoint)">查看实时</button>
                  <button type="button" @click="$emit('open-history', { deviceId: device.normalizedId, pointRef: String(selectedWorkbenchPoint.pointId || selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.address || ''), pointName: selectedWorkbenchPoint.pointName, pointLabel: selectedWorkbenchPoint.pointCode || selectedWorkbenchPoint.pointName })">查看历史</button>
                </div>
              </template>
              <el-empty v-else description="点击表格行查看点位详情" />
            </aside>
          </div>
          <PointEditor v-if="pointEditVisible" class="embedded-point-editor" :device-id="device.normalizedId" :protocol="protocolSchema" :protocol-code="protocolKey" @open-history="$emit('open-history', $event)" @open-realtime="$emit('open-realtime', $event)" />
        </template>
        <RealtimeDataPanel
          v-else-if="activeTab === 'realtime'"
          :device-id="device.normalizedId"
          :auto-refresh="true"
          :refresh-interval-ms="5000"
        />
        <AlarmTablePanel v-else-if="activeTab === 'alarm'" :device-id="device.normalizedId" />
        <LogPanel v-else :device-id="device.normalizedId" />
      </section>

      <el-dialog v-model="diffVisible" title="配置差异" width="720px" class="device-operation-dialog">
        <pre class="json-view">{{ diffText }}</pre>
      </el-dialog>
      <el-dialog v-model="protocolDetailsVisible" title="协议说明与能力" width="720px" class="device-operation-dialog">
        <template v-if="protocolSchema">
          <p class="protocol-description">{{ protocolSchema.description || '当前协议未提供额外说明。' }}</p>
          <div class="protocol-capability-strip">
            <el-tag :type="capabilityTag(protocolSchema.implementationState)" effect="light">实现：{{ protocolSchema.implementationState || '-' }}</el-tag>
            <el-tag :type="capabilityTag(protocolSchema.writeCapability)" effect="light">写入：{{ protocolSchema.writeCapability || '-' }}</el-tag>
            <el-tag :type="capabilityTag(protocolSchema.subscriptionCapability)" effect="light">订阅：{{ protocolSchema.subscriptionCapability || '-' }}</el-tag>
          </div>
          <dl class="protocol-field-descriptions">
            <template v-for="field in protocolFields" :key="field.name">
              <dt>{{ field.label || field.name }}</dt>
              <dd>{{ field.description || '未提供额外字段说明。' }}<span v-if="field.requiredWhen">条件：{{ field.requiredWhen }}</span></dd>
            </template>
          </dl>
        </template>
      </el-dialog>
    </template>
    <div v-else class="empty-config">
      <el-empty description="请从左侧设备树或设备列表选择设备" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { getDeviceConfigBundle, getDeviceDiff, validateDeviceConfigBundle, commitDeviceConfigBundle } from "@/api/config.api";
import { ApiRequestError } from "@/api/http";
import { getDeviceRealtimeData } from "@/api/data.api";

import { getProtocol } from "@/api/protocol.api";
import { normalizeRealtimeRows } from "@/features/realtime/utils/realtime-utils";
import AlarmTablePanel from "@/components/alarm/AlarmTablePanel.vue";
import LogPanel from "@/components/log/LogPanel.vue";
import PointEditor from "@/features/point/components/PointEditor.vue";
import WorkbenchProtocolForm from "@/features/protocol/components/WorkbenchProtocolForm.vue";
import RealtimeDataPanel from "@/components/realtime/RealtimeDataPanel.vue";
import { useDeviceStore } from "@/stores/device.store";
import { normalizeDeviceStatusDetail, runtimePresentation, type DeviceStatusDetail } from "@/features/diagnostic/utils/device-runtime-utils";
import { buildConnectionPayload, extractProtocolModel, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import {
  buildDeviceProtocolRequestContext,
  buildDeviceRequestContext,
  isSameDeviceProtocolRequestContext,
  isSameDeviceRequestContext,
  shouldCommitDeviceProtocolSave
} from "@/features/device/utils/device-request-lifecycle";
import type { DeviceViewModel } from "@/types/device";
import type { DeviceConfigBundleResponse } from "@/types/config";
import type { RealtimePointRow } from "@/types/monitor";
import type { ProtocolSchema } from "@/types/protocol";
import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";

const deviceStore = useDeviceStore();
let configSession = 0;
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
const savingConnection = computed(() => deviceStore.isDeviceOperating(props.device?.normalizedId || ""));
const protocolError = ref("");
const connectionMessage = ref("");
const configBundle = ref<DeviceConfigBundleResponse | null>(null);
const diffVisible = ref(false);
const protocolDetailsVisible = ref(false);
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

const protocolConfigOwner = createLatestRequestOwner(isSameDeviceProtocolRequestContext);
const statusOwner = createLatestRequestOwner(isSameDeviceRequestContext);
const workbenchRowsOwner = createLatestRequestOwner(isSameDeviceRequestContext);
const diffOwner = createLatestRequestOwner(isSameDeviceRequestContext);

const protocolKey = computed(() => String(props.device?.protocolType || props.device?.connectionType || ""));
const protocolFields = computed(() => protocolSchema.value?.connectionFields || []);
const pointRows = computed(() => workbenchRows.value);
const pagedPointRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return pointRows.value.slice(start, start + pageSize.value);
});

const runtimeView = computed(() => {
  const id = props.device?.normalizedId || "";
  return runtimePresentation(deviceStore.runtimeMap[id] || props.device?.runtime || statusDetail.value || undefined,
    Boolean(deviceStore.runtimeErrors[id] || props.device?.runtimeStale));
});
const currentProtocolErrors = computed(() => validateProtocolModel(protocolFields.value, protocolModel.value));
const protocolValidationText = computed(() => {
  if (protocolLoading.value) return "正在读取连接配置";
  if (protocolError.value) return "配置待处理，展开查看";
  if (!protocolSchema.value || !configBundle.value) return "连接配置尚未读取";
  if (!protocolFields.value.length) return "当前协议暂无连接字段";
  return currentProtocolErrors.value.length ? `${currentProtocolErrors.value.length} 个字段待完善` : "字段校验通过";
});
const protocolValidationTone = computed(() => protocolError.value || currentProtocolErrors.value.length ? "is-warning" : protocolSchema.value && configBundle.value && !protocolLoading.value && protocolFields.value.length ? "is-success" : "is-muted");

async function loadProtocolConfig() {
  const requestContext = currentProtocolConfigContext();
  if (!requestContext.deviceId || !requestContext.protocolKey) {
    return;
  }
  const ticket = protocolConfigOwner.begin(requestContext);
  const epoch = deviceStore.deviceEpochs[requestContext.deviceId] || 0;
  ++configSession;
  protocolLoading.value = true;
  protocolError.value = "";
  connectionMessage.value = "";
  try {
    const [schema, bundle] = await Promise.all([
      getProtocol(requestContext.protocolKey),
      getDeviceConfigBundle(requestContext.deviceId)
    ]);
    const nextConnectionConfig = bundle.connection || {};
    const nextProtocolModel = extractProtocolModel(schema.connectionFields || [], nextConnectionConfig);
    if (!protocolConfigOwner.canCommit(ticket, currentProtocolConfigContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0) || deviceStore.isDeviceOperating(requestContext.deviceId)) {
      return;
    }
    protocolSchema.value = schema;
    configBundle.value = bundle;
    connectionConfig.value = nextConnectionConfig;
    protocolModel.value = nextProtocolModel;
    connectionMessage.value = "连接配置已读取";
  } catch (error) {
    if (!protocolConfigOwner.canCommit(ticket, currentProtocolConfigContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0) || deviceStore.isDeviceOperating(requestContext.deviceId)) {
      return;
    }
    protocolError.value = error instanceof Error ? error.message : "协议连接配置加载失败";
  } finally {
    if (protocolConfigOwner.isLatest(ticket)) {
      protocolLoading.value = false;
    }
  }
}

async function loadConnectionStatus() {
  const requestContext = currentDeviceRequestContext();
  if (!requestContext.deviceId) {
    return;
  }
  const ticket = statusOwner.begin(requestContext);
  statusLoading.value = true;
  try {
    const error = await deviceStore.refreshRuntime(requestContext.deviceId);
    if (error) throw new Error(error);
    const runtime = deviceStore.runtimeMap[requestContext.deviceId];
    const nextStatusDetail = runtime ? normalizeDeviceStatusDetail(runtime, requestContext.deviceId) : null;
    if (!statusOwner.canCommit(ticket, currentDeviceRequestContext())) {
      return;
    }
    statusDetail.value = nextStatusDetail;
    connectionMessage.value = "";
  } catch (error) {
    if (!statusOwner.canCommit(ticket, currentDeviceRequestContext())) {
      return;
    }
    statusDetail.value = null;
    connectionMessage.value = error instanceof Error ? error.message : "连接状态检查失败";
  } finally {
    if (statusOwner.isLatest(ticket)) {
      statusLoading.value = false;
    }
  }
}

async function loadWorkbenchRows() {
  const requestContext = currentDeviceRequestContext();
  if (!requestContext.deviceId) {
    workbenchRowsOwner.invalidate();
    workbenchRows.value = [];
    return;
  }
  const ticket = workbenchRowsOwner.begin(requestContext);
  const epoch = deviceStore.deviceEpochs[requestContext.deviceId] || 0;
  workbenchRowsLoading.value = true;
  workbenchRowsError.value = "";
  try {
    const nextRows = normalizeRealtimeRows(await getDeviceRealtimeData(requestContext.deviceId), requestContext.deviceId);
    if (!workbenchRowsOwner.canCommit(ticket, currentDeviceRequestContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0)) {
      return;
    }
    workbenchRows.value = nextRows;
    selectedWorkbenchPoint.value = resolveSelectedWorkbenchPoint(nextRows, selectedWorkbenchPoint.value);
    currentPage.value = 1;
  } catch (error) {
    if (!workbenchRowsOwner.canCommit(ticket, currentDeviceRequestContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0)) {
      return;
    }
    workbenchRowsError.value = error instanceof Error ? error.message : "点位运行数据加载失败";
  } finally {
    if (workbenchRowsOwner.isLatest(ticket)) {
      workbenchRowsLoading.value = false;
    }
  }
}

async function saveProtocolConfig() {
  const targetContext = currentProtocolConfigContext();
  if (!targetContext.deviceId || !targetContext.protocolKey || !props.device) {
    return;
  }
  if (deviceStore.isDeviceOperating(targetContext.deviceId)) return;
  const session = configSession;
  const targetFields = [...protocolFields.value];
  const targetModel = { ...protocolModel.value };
  const targetConnectionConfig = cloneConnectionPayload(connectionConfig.value);
  const targetDeviceName = props.device.displayName || props.device.normalizedId || targetContext.deviceId;
  const errors = validateProtocolModel(targetFields, targetModel);
  protocolErrors.value = errors;
  if (errors.length > 0) {
    protocolError.value = "请先修正协议字段校验错误";
    return;
  }

  protocolError.value = "";
  try {
    const payload = buildConnectionPayload(targetFields, targetModel, {
      ...targetConnectionConfig,
      deviceId: targetContext.deviceId,
      connectionType: targetContext.protocolKey,
      protocolType: targetContext.protocolKey
    });
    const currentBundle = configBundle.value;
    if (!currentBundle?.device || !currentBundle.connection) {
      throw new Error("完整设备配置尚未加载");
    }
    const bundlePayload = {
      baseVersion: currentBundle.configVersion,
      device: { ...currentBundle.device, deviceId: targetContext.deviceId },
      connection: payload,
      points: currentBundle.points || []
    };
    const operation = await deviceStore.operate(async () => {
      const validation = await validateDeviceConfigBundle(targetContext.deviceId, bundlePayload);
      if (!validation.valid) throw new Error(validation.errors?.join("；") || "设备配置校验失败");
      try {
        return await commitDeviceConfigBundle(targetContext.deviceId, bundlePayload);
      } catch (error) {
        if (error instanceof ApiRequestError && error.httpStatus === 409) throw new Error("设备配置已经发生变化，请重新读取配置后确认当前修改", { cause: error });
        throw error;
      }
    }, targetContext.deviceId);
    if (!operation.ok || !operation.result) throw new Error(operation.error || "协议连接配置保存失败");
    const result = operation.result;
    if (session === configSession && shouldCommitDeviceProtocolSave(targetContext, currentProtocolConfigContext())) {
      connectionConfig.value = payload;
      configBundle.value = { ...currentBundle, connection: payload, configVersion: result.configVersion };
      connectionMessage.value = `完整设备配置已保存，配置版本 v${result.configVersion}`;
      ElMessage.success("协议连接配置已保存");
      return;
    }
    ElMessage.success(`设备 ${targetDeviceName} 协议连接配置已保存`);
  } catch (error) {
    const message = error instanceof ApiRequestError && error.httpStatus === 409
      ? "设备配置已经发生变化，请重新读取配置后确认当前修改"
      : error instanceof Error ? error.message : "协议连接配置保存失败";
    if (session === configSession && shouldCommitDeviceProtocolSave(targetContext, currentProtocolConfigContext())) {
      protocolError.value = message;
      return;
    }
    ElMessage.error(`设备 ${targetDeviceName} 协议连接配置保存失败：${message}`);
  }
}

async function showDiff() {
  const requestContext = currentDeviceRequestContext();
  if (!requestContext.deviceId) {
    return;
  }
  const ticket = diffOwner.begin(requestContext);
  const epoch = deviceStore.deviceEpochs[requestContext.deviceId] || 0;
  protocolError.value = "";
  try {
    const diff = await getDeviceDiff(requestContext.deviceId);
    if (!diffOwner.canCommit(ticket, currentDeviceRequestContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0)) {
      return;
    }
    diffText.value = JSON.stringify(diff, null, 2);
    diffVisible.value = true;
  } catch (error) {
    if (!diffOwner.canCommit(ticket, currentDeviceRequestContext()) || epoch !== (deviceStore.deviceEpochs[requestContext.deviceId] || 0)) {
      return;
    }
    protocolError.value = error instanceof Error ? error.message : "配置差异加载失败";
  }
}

function currentDeviceRequestContext() {
  return buildDeviceRequestContext(props.device?.normalizedId);
}

function currentProtocolConfigContext() {
  return buildDeviceProtocolRequestContext(props.device?.normalizedId, protocolKey.value);
}

function resetProtocolReadState() {
  ++configSession;
  configBundle.value = null;
  protocolModel.value = {};
  protocolErrors.value = [];
  protocolSchema.value = null;
  connectionConfig.value = {};
  protocolError.value = "";
  connectionMessage.value = "";
}

function cloneConnectionPayload(payload: ConnectionPayload): ConnectionPayload {
  return JSON.parse(JSON.stringify(payload || {})) as ConnectionPayload;
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
  if (["ONLINE", "RUNNING", "正常", "在线", "运行中", "在线健康", "健康"].includes(normalized)) {
    return "is-success";
  }
  if (["ERROR", "异常", "离线", "OFFLINE", "失败", "运行失败", "不健康"].includes(normalized)) {
    return normalized === "离线" || normalized === "OFFLINE" ? "is-muted" : "is-danger";
  }
  if (["CONNECTING", "重连中", "WARNING", "启动中", "连接中", "等待首采", "采集降级", "降级", "在线部分有效", "在线无有效数据"].includes(normalized)) {
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
  if (pointActionText(row) === "写入") {
    ElMessage.info("请切换到批量和协议命令执行写入操作");
    return;
  }
  openPointRealtime(row);
}

function openPointRealtime(row: RealtimePointRow) {
  if (!props.device) {
    return;
  }
  const pointRef = String(row.pointId || row.pointCode || row.address || "");
  if (!pointRef) {
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

function pointRowClassName({ row }: { row: RealtimePointRow }): string {
  return selectedWorkbenchPoint.value && pointRowKey(row) === pointRowKey(selectedWorkbenchPoint.value) ? "is-selected-point" : "";
}

watch(activeTab, (tab) => {
  if (tab === "points") {
    loadProtocolConfig().catch(() => undefined);
  }
});

watch(() => props.device?.normalizedId, () => {
  ++configSession;
  workbenchRows.value = [];
  workbenchRowsError.value = "";
  statusOwner.invalidate();
  workbenchRowsOwner.invalidate();
  diffOwner.invalidate();
  statusLoading.value = false;
  workbenchRowsLoading.value = false;
  statusDetail.value = null;
  connectionMessage.value = "";
  diffVisible.value = false;
  diffText.value = "{}";
  currentPage.value = 1;
  selectedWorkbenchPoint.value = null;
  if (!props.device) {
    workbenchRows.value = [];
    workbenchRowsError.value = "";
    return;
  }
  loadConnectionStatus().catch(() => undefined);
  loadWorkbenchRows().catch(() => undefined);
}, { immediate: true });

watch(() => [props.device?.normalizedId, protocolKey.value], () => {
  protocolDetailsVisible.value = false;
  protocolConfigOwner.invalidate();
  protocolLoading.value = false;
  resetProtocolReadState();
  if (props.device && protocolKey.value) {
    loadProtocolConfig().catch(() => undefined);
  }
}, { immediate: true });

onBeforeUnmount(() => {
  ++configSession;
  protocolConfigOwner.invalidate();
  statusOwner.invalidate();
  workbenchRowsOwner.invalidate();
  diffOwner.invalidate();
  protocolLoading.value = false;
  statusLoading.value = false;
  workbenchRowsLoading.value = false;
});
</script>

<style scoped>
.device-config-workbench-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
}

.local-section-card {
  min-width: 0;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel);
}

.run-control-card {
  display: flex;
  min-height: 72px;
  padding: 14px 18px;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
}

.runtime-heading {
  padding-right: 25px;
  flex: 0 0 auto;
  border-right: 1px solid var(--console-border-soft);
}

.runtime-heading h3 {
  margin: 0 0 3px;
  font-size: 13px;
  font-weight: 600;
}

.runtime-heading span,
.state-pill dt {
  color: var(--console-text-muted);
  font-size: 11px;
}

.control-status-row {
  display: flex;
  min-width: 0;
  margin: 0;
  flex: 0 1 auto;
  align-items: center;
  gap: 16px 32px;
  flex-wrap: wrap;
}

.state-pill {
  display: grid;
  min-width: 0;
  gap: 3px;
  flex: 0 1 auto;
}

.state-pill dd {
  display: flex;
  min-width: 0;
  margin: 0;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.state-pill i,
.quality-dot i {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: 50%;
  background: currentColor;
}

.runtime-note {
  display: flex;
  margin: -3px 2px 2px;
  gap: 6px 20px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.7;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.run-control-actions,
.schema-toolbar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.run-control-actions {
  margin-left: auto;
}

.run-control-actions :deep(.el-button + .el-button),
.schema-toolbar :deep(.el-button + .el-button) {
  margin-left: 0;
}

.protocol-config-collapse {
  min-height: 0;
  padding: 0;
  overflow: hidden;
  border-color: rgba(82, 121, 166, 0.28);
  border-radius: 6px;
}

.protocol-config-collapse > summary {
  display: flex;
  min-height: 51px;
  padding: 13px 18px;
  align-items: center;
  gap: 12px;
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
  border-bottom: 1px solid rgba(82, 121, 166, 0.28);
}

.protocol-config-collapse[open] > summary::after {
  content: "收起";
}

.protocol-config-collapse > summary > span:first-child {
  color: var(--console-text-primary);
  font-size: 14px;
  font-weight: 600;
}

.protocol-config-collapse > summary small {
  font-size: 11px;
  text-align: right;
}

.protocol-config-collapse[open] > .el-alert {
  margin: 14px 18px;
}

.device-config-workbench-pane .protocol-config-collapse button.protocol-name-badge {
  height: 23px;
  min-height: 23px;
  padding: 1px 8px;
  color: #a8b6c8;
  border: 1px solid #415064;
  border-radius: 4px;
  background: #1f2a3b;
  font-size: 11px;
  font-weight: 400;
}

.device-config-workbench-pane .protocol-config-collapse button.protocol-name-badge:hover {
  color: #e2e8f0;
  background: #24384f;
}

.protocol-field-count {
  margin-left: auto;
  color: var(--console-text-muted);
  font-size: 11px;
}

.protocol-capability-strip {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.protocol-description {
  margin: 0 0 12px;
  color: var(--console-text-secondary);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.protocol-field-descriptions {
  max-height: 50vh;
  margin: 16px 0 0;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
}

.protocol-field-descriptions dt {
  margin-top: 12px;
  color: var(--console-text-secondary);
  overflow-wrap: anywhere;
}

.protocol-field-descriptions dd {
  margin: 3px 0 0;
  color: var(--console-text-muted);
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.protocol-field-descriptions dd span {
  display: block;
}

.schema-actions {
  display: flex;
  padding: 12px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-top: 1px solid rgba(82, 121, 166, 0.28);
  background: #172536;
  flex-wrap: wrap;
}

.schema-message {
  display: grid;
  gap: 5px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.6;
}

.device-config-workbench-pane .protocol-config-collapse .schema-toolbar :deep(.el-button) {
  padding: 5px 12px;
  font-size: 13px;
}

.device-config-workbench-pane .protocol-config-collapse .schema-toolbar :deep(.el-button:not(.el-button--primary, :disabled)) {
  background: transparent;
}

.device-data-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.device-data-topline {
  display: flex;
  min-width: 0;
  min-height: 54px;
  padding: 0 18px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--console-border-soft);
  flex-wrap: wrap;
}

.device-inner-tabbar {
  display: inline-flex;
  min-height: 54px;
  align-items: center;
  gap: 24px;
}

.device-inner-tab {
  position: relative;
  min-height: 54px;
  padding: 0;
  color: var(--console-text-muted);
  border: 0;
  border-radius: 0;
  background: transparent;
  font-size: 13px;
}

.device-inner-tab:hover,
.device-inner-tab.is-active {
  color: var(--console-text-secondary);
  background: transparent;
}

.device-inner-tab.is-active::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--console-primary-hover);
  content: "";
}

.tab-count {
  margin-left: 5px;
  padding: 1px 5px;
  color: var(--console-info-text);
  border-radius: var(--console-radius-sm);
  background: var(--app-color-info-soft);
  font-size: 10px;
}

.point-data-meta-row {
  display: flex;
  min-height: 42px;
  padding: 10px 18px;
  align-items: center;
  gap: 8px 18px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.5;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.selected-point-label {
  margin-left: auto;
  color: var(--console-info-text);
}

.point-data-grid {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns: minmax(0, 1fr) 285px;
  align-items: stretch;
}

.point-data-table-column {
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

.industrial-point-table {
  font-size: 12px;
}

.industrial-point-table :deep(.el-table__cell) {
  padding: 10px 0;
}

.industrial-point-table :deep(.el-table__row) {
  cursor: pointer;
}

.industrial-point-table :deep(.el-table__row.is-selected-point) {
  --el-table-tr-bg-color: var(--app-color-info-soft);
}

.industrial-point-table :deep(.is-selected-point td:first-child) {
  box-shadow: inset 3px 0 var(--console-primary-hover);
}

.quality-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}

.table-link-button {
  min-height: 24px;
  padding: 0;
  color: var(--console-info-text);
  border: 0;
  background: transparent;
}

.industrial-pagination-row {
  display: flex;
  min-height: 44px;
  padding: 10px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--console-text-muted);
  border-top: 1px solid var(--console-border-soft);
  font-size: 11px;
  flex-wrap: wrap;
}

.compact-point-detail {
  min-width: 0;
  min-height: 0;
  padding: 0 18px 18px;
  color: var(--console-text-secondary);
  border-left: 1px solid var(--console-border-soft);
}

.compact-detail-head,
.compact-detail-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.compact-detail-head {
  margin-bottom: 16px;
}

.compact-detail-head h3 {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 12px;
  font-weight: 400;
}

.compact-detail-head button {
  min-height: 24px;
  padding: 0;
  color: var(--console-info-text);
  border: 0;
  background: transparent;
}

.compact-point-detail > strong {
  display: block;
  color: var(--console-text-primary);
  font-size: 15px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.compact-point-detail > p {
  margin: 4px 0 16px;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.compact-detail-grid {
  display: grid;
  margin: 18px 0;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 12px 10px;
  font-size: 12px;
  line-height: 1.5;
}

.compact-detail-grid dt {
  color: var(--console-text-muted);
}

.compact-detail-grid dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.point-value-display {
  padding: 14px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.point-value-display > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--console-text-muted);
  font-size: 11px;
}

.point-value-display > strong {
  display: block;
  margin-top: 12px;
  color: var(--console-text-primary);
  font-size: 26px;
  font-weight: 500;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.point-value-display small {
  margin-left: 8px;
  color: var(--console-text-muted);
  font-size: 12px;
  font-weight: 400;
}

.compact-detail-actions {
  margin: 18px 0 0;
  justify-content: flex-start;
}

.embedded-point-editor {
  max-height: 420px;
  margin: 0 18px 18px;
  padding-top: 14px;
  overflow: auto;
  border-top: 1px solid var(--console-border-soft);
}

.device-config-workbench-pane button:focus-visible,
.protocol-config-collapse > summary:focus-visible {
  outline: 2px solid var(--console-input-border-focus);
  outline-offset: 3px;
}

@media (max-width: 1100px) {
  .point-data-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .compact-point-detail {
    padding-top: 18px;
    border-top: 1px solid var(--console-border-soft);
    border-left: 0;
  }


}

@media (max-width: 760px) {
  .control-status-row {
    display: grid;
    flex-basis: 100%;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .run-control-actions {
    margin-left: 0;
  }

  .device-data-topline {
    padding-bottom: 12px;
  }

  .device-inner-tabbar {
    gap: 18px;
  }

  .protocol-config-collapse > summary {
    flex-wrap: wrap;
  }

  .protocol-config-collapse > summary small {
    margin-left: 0;
  }
}
</style>
