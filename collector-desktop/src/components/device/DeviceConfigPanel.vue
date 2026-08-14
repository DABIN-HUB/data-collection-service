<template>
  <section class="config-panel">
    <template v-if="device">
      <div class="config-panel-head">
        <div>
          <h2>{{ device.displayName }}</h2>
          <p>{{ device.normalizedId }} · {{ device.displayProtocol }}</p>
        </div>
        <el-tag :type="deviceStatusTag" effect="light">{{ deviceStatusText }}</el-tag>
      </div>

      <el-tabs v-model="activeTab" class="device-tabs">
        <el-tab-pane label="基本配置" name="basic">
          <div class="form-grid">
            <div class="field-card"><span>设备名称</span><strong>{{ device.displayName }}</strong></div>
            <div class="field-card"><span>本地设备 ID</span><strong>{{ device.normalizedId }}</strong></div>
            <div class="field-card"><span>设备分组</span><strong>{{ device.displayGroup }}</strong></div>
            <div class="field-card"><span>协议类型</span><strong>{{ device.displayProtocol }}</strong></div>
            <div class="field-card"><span>IP / 主机</span><strong>{{ device.ipAddress || device.host || '-' }}</strong></div>
            <div class="field-card"><span>端口</span><strong>{{ device.port || '-' }}</strong></div>
            <div class="field-card"><span>采集周期</span><strong>{{ device.collectionInterval || '-' }} ms</strong></div>
            <div class="field-card"><span>配置来源</span><strong>{{ device.configSource || '-' }}</strong></div>
          </div>
          <div class="connection-test-card">
            <div>
              <h3>连接测试</h3>
              <p>第一阶段复用设备启动/停止与运行态接口，后续可替换为独立连接校验接口。</p>
            </div>
            <div class="header-actions">
              <el-button type="primary" @click="$emit('start', device.normalizedId)">启动采集</el-button>
              <el-button type="danger" plain @click="$emit('stop', device.normalizedId)">停止采集</el-button>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="协议配置" name="protocol">
          <div class="protocol-schema-card">
            <div class="schema-head">
              <div>
                <h3>协议 Schema 动态表单</h3>
                <p>字段来源：`/api/protocols/{{ protocolKey }}`，保存时按字段 storage 写入 topLevel 或 extJson。</p>
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
          </div>
        </el-tab-pane>

        <el-tab-pane label="点位配置" name="points">
          <PointEditor :device-id="device.normalizedId" :protocol="protocolSchema" :protocol-code="protocolKey" />
        </el-tab-pane>

        <el-tab-pane label="实时数据" name="realtime">
          <RealtimeDataPanel :device-id="device.normalizedId" />
        </el-tab-pane>

        <el-tab-pane label="告警" name="alarm">
          <AlarmTablePanel :device-id="device.normalizedId" />
        </el-tab-pane>

        <el-tab-pane label="运行日志" name="log">
          <LogPanel :device-id="device.normalizedId" />
        </el-tab-pane>
      </el-tabs>

      <el-dialog v-model="diffVisible" title="配置差异" width="720px">
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
import { getProtocol } from "@/api/protocol.api";
import AlarmTablePanel from "@/components/alarm/AlarmTablePanel.vue";
import LogPanel from "@/components/log/LogPanel.vue";
import PointEditor from "@/components/point/PointEditor.vue";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import RealtimeDataPanel from "@/components/realtime/RealtimeDataPanel.vue";
import { resolveDeviceStatus } from "@/stores/device.store";
import { buildConnectionPayload, extractProtocolModel, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import type { DeviceViewModel } from "@/types/device";
import type { ProtocolSchema } from "@/types/protocol";

const props = defineProps<{
  device: DeviceViewModel | null;
}>();

defineEmits<{
  start: [deviceId: string];
  stop: [deviceId: string];
}>();

const activeTab = ref("basic");
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

const protocolKey = computed(() => String(props.device?.protocolType || props.device?.connectionType || ""));
const protocolFields = computed(() => protocolSchema.value?.connectionFields || []);

type DeviceStatusTag = "success" | "warning" | "danger" | "info";

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

const deviceStatusTag = computed<DeviceStatusTag>(() => {
  const status = props.device ? resolveDeviceStatus(props.device) : "OFFLINE";
  return {
    ONLINE: "success",
    CONNECTING: "warning",
    ERROR: "danger",
    DISABLED: "info",
    OFFLINE: "info"
  }[status] as DeviceStatusTag;
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

watch(activeTab, (tab) => {
  if (tab === "protocol" || tab === "points") {
    loadProtocolConfig().catch(() => undefined);
  }
});

watch(protocolKey, () => {
  protocolModel.value = {};
  protocolErrors.value = [];
  protocolSchema.value = null;
  connectionConfig.value = {};
  protocolError.value = "";
  connectionMessage.value = "";
  if (activeTab.value === "protocol" || activeTab.value === "points") {
    loadProtocolConfig().catch(() => undefined);
  }
});
</script>
