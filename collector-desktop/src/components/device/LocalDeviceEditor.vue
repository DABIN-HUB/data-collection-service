<template>
  <el-dialog
    :model-value="modelValue"
    width="min(1280px, calc(100vw - 32px))"
    class="local-device-editor-dialog local-device-editor-exact"
    destroy-on-close
    @update:model-value="close"
  >
    <div class="local-editor-shell">
      <header class="local-editor-title">
        <div>
          <span class="label-chip">配置编辑器</span>
          <h3>{{ editingDeviceId ? '编辑本地临时设备' : '新增本地临时设备' }}</h3>
          <p>创建并配置新的工业协议采集终端，本地临时设备只写入当前采集服务。</p>
        </div>
        <div class="local-editor-title-actions">
          <div class="local-editor-stat">
            <strong>{{ currentProtocolTitle }}</strong>
            <span>当前协议</span>
          </div>
          <div class="local-editor-stat">
            <strong>{{ points.length }}</strong>
            <span>点位数</span>
          </div>
          <div class="local-editor-stat">
            <strong>{{ validationTitle }}</strong>
            <span>配置状态</span>
          </div>
        </div>
      </header>

      <nav class="local-editor-tabs" aria-label="新增设备配置分区">
        <button
          v-for="(step, index) in localEditorSteps"
          :key="step.key"
          type="button"
          class="local-editor-tab"
          :class="{ 'is-active': activeStep === index, 'is-complete': activeStep > index }"
          @click="activeStep = index"
        >
          <span>{{ step.no }}</span>
          <strong>{{ step.label }}</strong>
          <small>{{ step.desc }}</small>
        </button>
      </nav>

      <main class="local-editor-body">
        <el-alert v-if="error" :title="error" type="warning" :closable="false" />

        <section v-show="activeStep === 0" class="local-editor-pane local-section-card">
          <div class="local-section-head">
            <div>
              <span class="label-chip">基础连接</span>
              <h3>设备基础与连接参数配置</h3>
              <p>填写设备身份、协议类型、采集周期和协议连接字段。</p>
            </div>
          </div>
          <div class="local-setup-cluster">
            <div class="modao-form-grid local-summary-grid">
              <label>设备 ID<el-input v-model="deviceId" :disabled="Boolean(editingDeviceId)" placeholder="local-modbus-1" /></label>
              <label>设备名称<el-input v-model="deviceName" placeholder="本地调试设备" /></label>
              <label>协议<el-select v-model="protocol" filterable @change="onProtocolChanged"><el-option v-for="item in visibleProtocols" :key="item.protocol" :label="`${item.title || item.protocol} (${item.protocol})`" :value="item.protocol" /></el-select></label>
              <label>基础采集周期 ms<el-input-number v-model="adaptive.baseCollectionInterval" :min="100" :step="100" /></label>
              <label>最小采集周期 ms<el-input-number v-model="adaptive.minCollectionInterval" :min="100" :step="100" /></label>
              <label>最大采集周期 ms<el-input-number v-model="adaptive.maxCollectionInterval" :min="100" :step="100" /></label>
              <label>点位变化阈值<el-input-number v-model="adaptive.pointChangeThreshold" :min="0" :step="0.01" /></label>
            </div>
            <div class="local-connection-card">
              <div class="local-editor-section-head compact">
                <h4>协议连接字段</h4>
                <span>根据所选协议动态渲染连接参数，字段保存位置由协议 Schema 决定。</span>
              </div>
              <ProtocolDynamicForm v-model="connectionModel" :fields="connectionFields" @validate="connectionErrors = $event" />
            </div>
          </div>
        </section>

        <section v-show="activeStep === 1" class="local-editor-pane local-section-card">
          <div class="local-section-head point-editor-head">
            <div>
              <span class="label-chip">点位建模</span>
              <h3>本地点位列表</h3>
              <p>定义采集点位、数据类型、地址和协议扩展字段。</p>
            </div>
            <div class="table-actions">
              <el-input v-model="pointKeyword" placeholder="搜索点位" clearable />
              <el-button @click="addPoint">新增点位</el-button>
              <el-button :disabled="selectedPointIndex < 0" @click="duplicatePoint">复制点位</el-button>
              <el-button type="danger" plain :disabled="selectedPointIndex < 0" @click="removePoint">删除点位</el-button>
            </div>
          </div>
          <div class="local-point-workspace">
            <section class="point-list-panel">
              <el-table :data="filteredPoints" height="330" border highlight-current-row @row-click="selectPoint">
                <el-table-column prop="pointCode" label="点位编码" width="150" />
                <el-table-column prop="pointName" label="点位名称" min-width="170" />
                <el-table-column prop="address" label="地址" min-width="160" />
                <el-table-column prop="dataType" label="数据类型" width="120" />
                <el-table-column prop="readWrite" label="读写" width="90" />
                <el-table-column prop="collectionMode" label="采集模式" width="130" />
              </el-table>
            </section>
            <section v-if="selectedPoint" class="point-detail-panel">
              <div class="point-detail-head">
                <div>
                  <h3>当前点位详情</h3>
                  <p>{{ selectedPoint.pointCode || '-' }} · {{ selectedPoint.pointName || '-' }}</p>
                </div>
              </div>
              <div class="point-detail-grid">
                <label>点位编码<el-input v-model="selectedPoint.pointCode" @change="syncJsonFromPoints" /></label>
                <label>点位名称<el-input v-model="selectedPoint.pointName" @change="syncJsonFromPoints" /></label>
                <label>地址<el-input v-model="selectedPoint.address" @change="syncJsonFromPoints" /></label>
                <label>数据类型<el-select v-model="selectedPoint.dataType" filterable @change="syncJsonFromPoints"><el-option v-for="type in pointDataTypes" :key="type" :label="type" :value="type" /></el-select></label>
                <label>读写类型<el-select v-model="selectedPoint.readWrite" @change="syncJsonFromPoints"><el-option label="只读 R" value="R" /><el-option label="只写 W" value="W" /><el-option label="读写 RW" value="RW" /></el-select></label>
                <label>采集模式<el-select v-model="selectedPoint.collectionMode" @change="syncJsonFromPoints"><el-option label="轮询" value="POLLING" /><el-option label="订阅" value="SUBSCRIPTION" /><el-option label="事件" value="EVENT" /></el-select></label>
                <label>单位<el-input v-model="selectedPoint.unit" @change="syncJsonFromPoints" /></label>
                <label>备注<el-input v-model="selectedPoint.remark" @change="syncJsonFromPoints" /></label>
              </div>
              <div v-if="pointFields.length" class="local-connection-card">
                <div class="local-editor-section-head compact">
                  <h4>协议点位扩展字段</h4>
                  <span>写入当前点位 additionalConfig。</span>
                </div>
                <div class="protocol-form-grid">
                  <el-form-item v-for="field in pointFields" :key="field.name" :label="field.label || field.name" :required="field.required">
                    <el-switch v-if="field.type === 'boolean'" v-model="selectedPointAdditional[field.name]" @change="applyPointExtra" />
                    <el-select v-else-if="field.options?.length" v-model="selectedPointAdditional[field.name]" clearable filterable @change="applyPointExtra">
                      <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
                    </el-select>
                    <el-input-number v-else-if="field.type === 'number' || field.type === 'integer'" v-model="selectedPointAdditional[field.name]" controls-position="right" @change="applyPointExtra" />
                    <el-input v-else v-model="selectedPointAdditional[field.name]" @change="applyPointExtra" />
                    <small v-if="field.description" class="field-description">{{ field.description }}</small>
                  </el-form-item>
                </div>
              </div>
            </section>
          </div>
        </section>

        <section v-show="activeStep === 2" class="local-editor-pane local-section-card">
          <div class="local-section-head">
            <div>
              <span class="label-chip">云平台上报</span>
              <h3>云端映射与数据上报</h3>
              <p>本地 deviceId 不变，云身份只作为上报目标配置保存。</p>
            </div>
          </div>
          <div class="modao-form-grid local-summary-grid">
            <label>启用云上报<el-switch v-model="cloudTarget.enabled" /></label>
            <label>设备类型<el-select v-model="cloudTarget.deviceType"><el-option label="子设备" value="SUB_DEVICE" /><el-option label="网关" value="GATEWAY" /><el-option label="直连设备" value="DIRECT_DEVICE" /></el-select></label>
            <label>productKey<el-input v-model="cloudTarget.productKey" placeholder="云端产品标识" /></label>
            <label>deviceName<el-input v-model="cloudTarget.deviceName" placeholder="云端设备名称" /></label>
            <label>拓扑关系<el-switch v-model="cloudTarget.topologyEnabled" /></label>
            <label>Topic 预览<el-input :model-value="cloudTopicPreview" readonly /></label>
          </div>
          <el-table :data="points" height="330" border>
            <el-table-column prop="pointCode" label="点位编码" width="150" />
            <el-table-column label="启用上报" width="120"><template #default="{ row }"><el-switch v-model="row.additionalConfig.reportEnabled" /></template></el-table-column>
            <el-table-column label="reportField" min-width="180"><template #default="{ row }"><el-input v-model="row.additionalConfig.reportField" /></template></el-table-column>
            <el-table-column label="事件" width="90"><template #default="{ row }"><el-switch v-model="row.additionalConfig.eventEnabled" /></template></el-table-column>
            <el-table-column label="Stream" width="100"><template #default="{ row }"><el-switch v-model="row.additionalConfig.streamEnabled" /></template></el-table-column>
            <el-table-column label="历史" width="90"><template #default="{ row }"><el-switch v-model="row.additionalConfig.historyEnabled" /></template></el-table-column>
          </el-table>
        </section>

        <section v-show="activeStep === 3" class="local-editor-pane local-section-card">
          <div class="local-section-head">
            <div>
              <span class="label-chip">JSON 高级</span>
              <h3>高级配置与自定义扩展</h3>
              <p>直接编辑 points 数组，设备基础信息和连接字段仍由表单生成。</p>
            </div>
            <div class="table-actions">
              <el-button @click="formatPointsJson">格式化 JSON</el-button>
              <el-button type="primary" plain @click="applyPointsJson">应用到点位列表</el-button>
            </div>
          </div>
          <el-alert title="这里只编辑 points 数组；设备、连接和云目标仍由前三步生成，避免覆盖本地 deviceId 与 cloudTarget 语义。" type="info" :closable="false" />
          <textarea v-model="pointsJson" class="json-editor-textarea" spellcheck="false"></textarea>
        </section>
      </main>

      <footer class="local-editor-footer">
        <div class="local-options">
          <el-checkbox v-model="overwrite">覆盖已有本地临时设备</el-checkbox>
          <el-checkbox v-model="startAfterSave">保存后立即本地启动</el-checkbox>
        </div>
        <div class="local-step-actions">
          <el-button @click="close(false)">取消</el-button>
          <el-button :disabled="activeStep === 0" @click="activeStep -= 1">上一步</el-button>
          <el-button :disabled="activeStep === 3" @click="activeStep += 1">下一步</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存本地设备</el-button>
        </div>
      </footer>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { createLocalDevice, updateLocalDevice } from "@/api/config.api";
import { startLocalDevice } from "@/api/device.api";
import { getProtocol } from "@/api/protocol.api";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import { buildConnectionPayload, buildProtocolInitialModel, extractProtocolModel, setPathValue, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import { buildLocalDevicePayload, DEFAULT_ADAPTIVE_CONFIG, validateLocalDeviceDraft, type AdaptiveConfig, type CloudTargetConfig } from "./local-device-utils";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

interface LocalDeviceBundle {
  device?: Record<string, unknown>;
  connection?: Record<string, unknown>;
  points?: DataPoint[];
}

const props = defineProps<{
  modelValue: boolean;
  editingBundle?: LocalDeviceBundle | null;
  protocols: ProtocolSchema[];
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  saved: [deviceId: string];
}>();

const localEditorSteps = [
  { key: "setup", no: "01", label: "基础连接", desc: "设备基础与连接参数配置" },
  { key: "points", no: "02", label: "点位建模", desc: "采集点位定义与建模" },
  { key: "cloud", no: "03", label: "云平台上报", desc: "云端映射与数据上报" },
  { key: "json", no: "04", label: "JSON 高级", desc: "高级配置与自定义扩展" }
] as const;

const activeStep = ref(0);
const saving = ref(false);
const error = ref("");
const editingDeviceId = ref("");
const deviceId = ref("");
const deviceName = ref("");
const protocol = ref("MODBUS_TCP");
const overwrite = ref(false);
const startAfterSave = ref(false);
const connectionModel = ref<ProtocolFormModel>({});
const connectionErrors = ref<string[]>([]);
const protocolDetails = ref<Record<string, ProtocolSchema>>({});
const points = ref<DataPoint[]>([]);
const selectedPointIndex = ref(0);
const pointKeyword = ref("");
const pointsJson = ref("[]");
const selectedPointAdditional = ref<Record<string, unknown>>({});

const adaptive = reactive<AdaptiveConfig>({ ...DEFAULT_ADAPTIVE_CONFIG });
const cloudTarget = reactive<CloudTargetConfig>({ enabled: false, deviceType: "SUB_DEVICE", topologyEnabled: true });

const visibleProtocols = computed(() => props.protocols.filter((item) => item.protocol));
const protocolSchema = computed(() => protocolDetails.value[protocol.value] || props.protocols.find((item) => item.protocol === protocol.value) || null);
const connectionFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.connectionFields || []);
const pointFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.pointFields || []);
const pointDataTypes = computed(() => protocolSchema.value?.dataTypes?.length ? protocolSchema.value.dataTypes : ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"]);
const currentProtocolTitle = computed(() => protocolSchema.value?.title ? `${protocolSchema.value.title} (${protocol.value})` : protocol.value);
const filteredPoints = computed(() => {
  const keyword = pointKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return points.value;
  }
  return points.value.filter((point) => [point.pointCode, point.pointName, point.address].some((value) => String(value || "").toLowerCase().includes(keyword)));
});
const selectedPoint = computed<DataPoint | null>(() => points.value[selectedPointIndex.value] || null);
const cloudTopicPreview = computed(() => cloudTarget.enabled && cloudTarget.productKey && cloudTarget.deviceName
  ? `/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post`
  : "未启用云上报或云身份不完整");
const validationSummary = computed(() => validateLocalDeviceDraft({ deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value, points: points.value, cloudTarget: { ...cloudTarget } }));
const validationTitle = computed(() => validationSummary.value.length === 0 && connectionErrors.value.length === 0
  ? "必填配置已完成"
  : `待完善 ${validationSummary.value.length + connectionErrors.value.length} 项`);

function reset(bundle: LocalDeviceBundle | null = null) {
  activeStep.value = 0;
  error.value = "";
  const device = bundle?.device || {};
  const connection = bundle?.connection || {};
  editingDeviceId.value = String(device.id || device.deviceId || "");
  deviceId.value = editingDeviceId.value || `local-${Date.now()}`;
  deviceName.value = String(device.deviceName || "本地临时设备");
  protocol.value = String(device.protocolType || connection.connectionType || visibleProtocols.value[0]?.protocol || "MODBUS_TCP");
  overwrite.value = Boolean(editingDeviceId.value);
  startAfterSave.value = false;
  adaptive.baseCollectionInterval = Number(device.collectionInterval || DEFAULT_ADAPTIVE_CONFIG.baseCollectionInterval);
  adaptive.minCollectionInterval = DEFAULT_ADAPTIVE_CONFIG.minCollectionInterval;
  adaptive.maxCollectionInterval = DEFAULT_ADAPTIVE_CONFIG.maxCollectionInterval;
  adaptive.pointChangeThreshold = DEFAULT_ADAPTIVE_CONFIG.pointChangeThreshold;
  Object.assign(cloudTarget, { enabled: false, deviceType: "SUB_DEVICE", productKey: "", deviceName: "", topologyEnabled: true }, normalizeCloudTarget(device.cloudTarget));
  points.value = normalizeInitialPoints(bundle?.points || [], deviceId.value, protocol.value);
  if (points.value.length === 0) {
    addPoint();
  }
  selectedPointIndex.value = 0;
  connectionModel.value = connectionFields.value.length ? extractProtocolModel(connectionFields.value, connection as ConnectionPayload) : buildProtocolInitialModel(connectionFields.value);
  syncSelectedPointAdditional();
  syncJsonFromPoints();
  void ensureProtocolSchema(protocol.value);
}

function onProtocolChanged() {
  connectionModel.value = {};
  points.value = normalizeInitialPoints(points.value, deviceId.value, protocol.value);
  syncSelectedPointAdditional();
  syncJsonFromPoints();
  void ensureProtocolSchema(protocol.value);
}

async function ensureProtocolSchema(protocolCode: string) {
  const normalizedProtocol = protocolCode.trim();
  if (!normalizedProtocol) {
    return;
  }
  const existing = protocolDetails.value[normalizedProtocol] || props.protocols.find((item) => item.protocol === normalizedProtocol);
  if (hasRenderableProtocolFields(existing)) {
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: existing };
    connectionModel.value = {
      ...buildProtocolInitialModel(existing.connectionFields || []),
      ...connectionModel.value
    };
    return;
  }
  try {
    const detail = await getProtocol(normalizedProtocol);
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: detail };
    if (protocol.value === normalizedProtocol) {
      connectionModel.value = {
        ...buildProtocolInitialModel(detail.connectionFields || []),
        ...connectionModel.value
      };
      points.value = normalizeInitialPoints(points.value, deviceId.value, normalizedProtocol);
      syncSelectedPointAdditional();
      syncJsonFromPoints();
    }
  } catch (caught) {
    error.value = caught instanceof Error ? `协议字段加载失败：${caught.message}` : "协议字段加载失败";
  }
}

function hasRenderableProtocolFields(schema: ProtocolSchema | null | undefined): schema is ProtocolSchema {
  return Boolean(schema && ((schema.connectionFields?.length || 0) > 0 || (schema.pointFields?.length || 0) > 0));
}

function addPoint() {
  const index = points.value.length + 1;
  points.value.push({
    pointId: `local-point_${index}`,
    pointCode: `point_${String(index).padStart(3, "0")}`,
    pointName: `点位 ${index}`,
    deviceId: deviceId.value,
    address: defaultAddress(),
    dataType: pointDataTypes.value[0] || "FLOAT",
    readWrite: "R",
    collectionMode: protocol.value === "MQTT" ? "SUBSCRIPTION" : "POLLING",
    status: 1,
    cacheEnabled: 1,
    alarmEnabled: 0,
    additionalConfig: { reportEnabled: true, reportField: `point_${String(index).padStart(3, "0")}` }
  });
  selectedPointIndex.value = points.value.length - 1;
  syncSelectedPointAdditional();
  syncJsonFromPoints();
}

function duplicatePoint() {
  const source = selectedPoint.value;
  if (!source) {
    return;
  }
  const clone = JSON.parse(JSON.stringify(source)) as DataPoint;
  clone.pointCode = `${source.pointCode || "point"}_copy`;
  clone.pointName = `${source.pointName || "点位"} 副本`;
  clone.pointId = `local-${clone.pointCode}`;
  points.value.push(clone);
  selectedPointIndex.value = points.value.length - 1;
  syncSelectedPointAdditional();
  syncJsonFromPoints();
}

function removePoint() {
  if (selectedPointIndex.value < 0) {
    return;
  }
  points.value.splice(selectedPointIndex.value, 1);
  selectedPointIndex.value = Math.max(0, Math.min(selectedPointIndex.value, points.value.length - 1));
  syncSelectedPointAdditional();
  syncJsonFromPoints();
}

function selectPoint(row: DataPoint) {
  selectedPointIndex.value = points.value.indexOf(row);
  syncSelectedPointAdditional();
}

function syncSelectedPointAdditional() {
  selectedPointAdditional.value = { ...(selectedPoint.value?.additionalConfig || {}) };
}

function applyPointExtra() {
  const target = selectedPoint.value;
  if (!target) {
    return;
  }
  const additionalConfig = { ...(target.additionalConfig || {}) };
  for (const field of pointFields.value) {
    setPathValue(additionalConfig, field.name, selectedPointAdditional.value[field.name]);
  }
  target.additionalConfig = additionalConfig;
  syncJsonFromPoints();
}

function syncJsonFromPoints() {
  pointsJson.value = JSON.stringify(points.value, null, 2);
}

function formatPointsJson() {
  try {
    pointsJson.value = JSON.stringify(JSON.parse(pointsJson.value || "[]"), null, 2);
    error.value = "";
  } catch (caught) {
    error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误";
  }
}

function applyPointsJson() {
  try {
    const parsed = JSON.parse(pointsJson.value || "[]") as DataPoint[];
    points.value = Array.isArray(parsed) ? parsed : [parsed];
    selectedPointIndex.value = points.value.length ? 0 : -1;
    syncSelectedPointAdditional();
    syncJsonFromPoints();
    error.value = "";
  } catch (caught) {
    error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误";
  }
}

async function save() {
  error.value = "";
  const mergedConnectionModel = {
    ...buildProtocolInitialModel(connectionFields.value),
    ...connectionModel.value
  };
  const errors = [...validationSummary.value, ...validateProtocolModel(connectionFields.value, mergedConnectionModel)];
  if (errors.length > 0) {
    error.value = errors.join("；");
    return;
  }
  saving.value = true;
  try {
    const connection = buildConnectionPayload(connectionFields.value, mergedConnectionModel, { deviceId: deviceId.value, connectionType: protocol.value });
    const payload = buildLocalDevicePayload({
      deviceId: deviceId.value,
      deviceName: deviceName.value,
      protocol: protocol.value,
      adaptive: { ...adaptive },
      connection,
      points: points.value,
      cloudTarget: { ...cloudTarget },
      overwrite: overwrite.value || Boolean(editingDeviceId.value),
      startAfterSave: startAfterSave.value
    });
    if (editingDeviceId.value) {
      await updateLocalDevice(editingDeviceId.value, payload);
    } else {
      await createLocalDevice(payload);
    }
    if (startAfterSave.value) {
      await startLocalDevice(deviceId.value);
    }
    ElMessage.success(startAfterSave.value ? "本地设备已保存并启动" : "本地设备已保存");
    emit("saved", deviceId.value);
    close(false);
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "本地设备保存失败";
  } finally {
    saving.value = false;
  }
}

function close(value: boolean) {
  emit("update:modelValue", value);
}

function normalizeInitialPoints(rawPoints: DataPoint[], currentDeviceId: string, currentProtocol: string): DataPoint[] {
  return rawPoints.map((point, index) => ({
    pointId: point.pointId || `local-${point.pointCode || `point_${index + 1}`}`,
    pointCode: point.pointCode || `point_${index + 1}`,
    pointName: point.pointName || `点位 ${index + 1}`,
    address: point.address || defaultAddress(currentProtocol),
    dataType: point.dataType || pointDataTypes.value[0] || "FLOAT",
    readWrite: point.readWrite || "R",
    collectionMode: point.collectionMode || (currentProtocol === "MQTT" ? "SUBSCRIPTION" : "POLLING"),
    status: point.status ?? 1,
    cacheEnabled: point.cacheEnabled ?? 1,
    alarmEnabled: point.alarmEnabled ?? 0,
    additionalConfig: { reportEnabled: true, reportField: point.pointCode || `point_${index + 1}`, ...(point.additionalConfig || {}) },
    ...point,
    deviceId: currentDeviceId
  }));
}

function defaultAddress(currentProtocol = protocol.value): string {
  if (currentProtocol === "MQTT") {
    return "sensor/temperature";
  }
  if (currentProtocol.startsWith("OPC_UA")) {
    return "ns=2;s=Channel1.Device1.Tag1";
  }
  if (currentProtocol === "SIEMENS_S7") {
    return "DB1.DBW0";
  }
  return "40001";
}

function normalizeCloudTarget(value: unknown): Partial<CloudTargetConfig> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  const target = value as Record<string, unknown>;
  return {
    enabled: Boolean(target.enabled),
    deviceType: String(target.deviceType || "SUB_DEVICE"),
    productKey: target.productKey ? String(target.productKey) : "",
    deviceName: target.deviceName ? String(target.deviceName) : "",
    topologyEnabled: target.topologyEnabled !== false
  };
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    reset(props.editingBundle || null);
  }
});

watch(() => props.editingBundle, (bundle) => {
  if (props.modelValue) {
    reset(bundle || null);
  }
});

watch(connectionFields, (fields) => {
  if (props.modelValue && Object.keys(connectionModel.value).length === 0) {
    connectionModel.value = buildProtocolInitialModel(fields);
  }
});
</script>
