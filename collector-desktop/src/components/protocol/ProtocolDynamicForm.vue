<template>
  <div class="dynamic-form">
    <el-alert v-if="fields.length === 0" title="当前协议暂无可渲染字段，请确认后端 ProtocolDescriptorProvider 是否提供 Schema。" type="info" :closable="false" />
    <section v-for="group in groups" :key="group.name" class="protocol-field-group">
      <h4>{{ displayGroupName(group.name) }}</h4>
      <div class="protocol-form-grid">
        <div
          v-for="field in group.fields"
          :key="field.name"
          class="protocol-field-row"
          :class="{ 'is-required': field.required, 'is-boolean': field.type === 'boolean' }"
          :title="fieldHelp(field)"
        >
          <label class="protocol-field-label" :title="field.label || field.name">
            {{ displayFieldLabel(field) }}<span v-if="field.required" class="protocol-field-required">*</span>
          </label>
          <div class="protocol-field-control">
          <el-switch
            v-if="field.type === 'boolean'"
            :model-value="Boolean(localModel[field.name])"
            active-text="启用"
            inactive-text="禁用"
            @update:model-value="updateField(field.name, $event)"
          />
          <el-select
            v-else-if="field.options && field.options.length > 0"
            :model-value="localModel[field.name]"
            clearable
            filterable
            :placeholder="fieldPlaceholder(field)"
            @update:model-value="updateField(field.name, $event)"
          >
            <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
          </el-select>
          <el-input-number
            v-else-if="field.type === 'number' || field.type === 'integer'"
            :model-value="typeof localModel[field.name] === 'number' ? Number(localModel[field.name]) : undefined"
            controls-position="right"
            :placeholder="fieldPlaceholder(field)"
            @update:model-value="updateField(field.name, $event ?? null)"
          />
          <el-input
            v-else
            :model-value="String(localModel[field.name] ?? '')"
            :placeholder="fieldPlaceholder(field)"
            @update:model-value="updateField(field.name, $event)"
          />
          </div>
        </div>
      </div>
    </section>
    <el-alert v-if="errors.length > 0" :title="errors.join('；')" type="warning" :closable="false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";

import { buildProtocolInitialModel, groupProtocolFields, validateProtocolModel, type ProtocolFormModel } from "./protocol-form-utils";
import type { ProtocolFieldConfig } from "@/types/protocol";

const props = defineProps<{
  fields: ProtocolFieldConfig[];
  modelValue?: ProtocolFormModel;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: ProtocolFormModel];
  validate: [errors: string[]];
}>();

const localModel = ref<ProtocolFormModel>({});
const groups = computed(() => groupProtocolFields(props.fields));
const errors = computed(() => validateProtocolModel(props.fields, localModel.value));

const GROUP_LABELS: Record<string, string> = {
  connection: "基础连接",
  connect: "基础连接",
  network: "网络参数",
  transport: "传输参数",
  serial: "串口参数",
  protocol: "协议参数",
  auth: "认证参数",
  security: "安全认证",
  advanced: "高级参数",
  performance: "性能参数",
  cache: "缓存参数",
  mqtt: "MQTT 参数",
  modbus: "Modbus 参数",
  opcua: "OPC UA 参数",
  opc_ua: "OPC UA 参数",
  http: "HTTP 参数"
};

const FIELD_LABELS: Record<string, string> = {
  host: "主机/IP",
  hostname: "主机/IP",
  ip: "IP",
  ipaddress: "IP",
  address: "地址",
  url: "URL",
  endpoint: "端点",
  port: "端口",
  unitid: "从站ID",
  slaveid: "从站ID",
  stationid: "站号",
  deviceid: "设备ID",
  devicename: "设备名",
  clientid: "客户端ID",
  productkey: "ProductKey",
  username: "用户名",
  password: "密码",
  token: "令牌",
  topic: "Topic",
  nodeid: "节点ID",
  timeout: "超时(ms)",
  connecttimeout: "连接超时",
  connectiontimeout: "连接超时",
  readtimeout: "读取超时",
  writetimeout: "写入超时",
  requesttimeout: "请求超时",
  retry: "重试",
  retries: "重试",
  maxretry: "最大重试",
  keepalive: "保活",
  baudrate: "波特率",
  databits: "数据位",
  stopbits: "停止位",
  parity: "校验",
  byteorder: "字节序",
  wordorder: "字序",
  endian: "端序",
  registerbase: "寄存器基址",
  path: "路径",
  database: "数据库",
  mode: "模式",
  qos: "QoS",
  ssl: "SSL",
  tls: "TLS"
};

function updateField(name: string, value: string | number | boolean | null) {
  localModel.value = {
    ...localModel.value,
    [name]: value
  };
  emit("update:modelValue", localModel.value);
  emit("validate", errors.value);
}

function displayGroupName(name: string): string {
  const trimmed = String(name || "").trim();
  if (!trimmed) {
    return "基础参数";
  }
  if (hasChinese(trimmed)) {
    return trimmed;
  }
  const key = normalizeKey(trimmed);
  return GROUP_LABELS[key] || inferGroupName(key);
}

function displayFieldLabel(field: ProtocolFieldConfig): string {
  const byName = FIELD_LABELS[normalizeKey(field.name)];
  if (byName) {
    return byName;
  }
  const label = String(field.label || "").trim();
  const byLabel = FIELD_LABELS[normalizeKey(label)];
  if (byLabel) {
    return byLabel;
  }
  if (label) {
    return shortenLabel(label);
  }
  return FIELD_LABELS[normalizeKey(field.name)] || shortenLabel(field.name);
}

function fieldPlaceholder(field: ProtocolFieldConfig): string {
  return String(field.description || field.label || field.name || "请输入");
}

function fieldHelp(field: ProtocolFieldConfig): string {
  const source = [field.label, field.description, field.name].filter(Boolean).join("：");
  return source || displayFieldLabel(field);
}

function normalizeKey(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function hasChinese(value: string): boolean {
  return /[\u4e00-\u9fa5]/.test(value);
}

function inferGroupName(key: string): string {
  if (key.includes("connect")) return "基础连接";
  if (key.includes("network")) return "网络参数";
  if (key.includes("serial")) return "串口参数";
  if (key.includes("auth") || key.includes("credential")) return "认证参数";
  if (key.includes("security") || key.includes("ssl") || key.includes("tls")) return "安全认证";
  if (key.includes("protocol") || key.includes("modbus") || key.includes("opc") || key.includes("mqtt")) return "协议参数";
  if (key.includes("advanced") || key.includes("extra") || key.includes("extend")) return "高级参数";
  return "扩展参数";
}

function shortenLabel(label: string): string {
  const trimmed = String(label || "").trim();
  if (!trimmed) {
    return "参数";
  }
  const normalized = normalizeKey(trimmed);
  if (FIELD_LABELS[normalized]) {
    return FIELD_LABELS[normalized];
  }
  if (/主机|地址|host/i.test(trimmed) && /ip|IP|主机/.test(trimmed)) return "主机/IP";
  if (/端口|port/i.test(trimmed)) return "端口";
  if (/从站|slave|unit/i.test(trimmed)) return "从站ID";
  if (/超时|timeout/i.test(trimmed)) return "超时(ms)";
  if (/重试|retry/i.test(trimmed)) return "重试";
  if (/波特|baud/i.test(trimmed)) return "波特率";
  if (/字节序|byte/i.test(trimmed)) return "字节序";
  if (/字序|word/i.test(trimmed)) return "字序";
  if (/寄存器|register/i.test(trimmed)) return "寄存器";
  if (/客户端|client/i.test(trimmed)) return "客户端ID";
  if (/用户名|user/i.test(trimmed)) return "用户名";
  if (/密码|password/i.test(trimmed)) return "密码";
  if (hasChinese(trimmed) && trimmed.length > 7) {
    return `${trimmed.slice(0, 6)}…`;
  }
  if (!hasChinese(trimmed) && trimmed.length > 12) {
    return `${trimmed.slice(0, 10)}…`;
  }
  return trimmed;
}

watch(() => props.fields, (fields) => {
  localModel.value = {
    ...buildProtocolInitialModel(fields),
    ...(props.modelValue || {})
  };
  emit("update:modelValue", localModel.value);
}, { immediate: true, deep: true });

watch(() => props.modelValue, (value) => {
  if (value) {
    localModel.value = { ...localModel.value, ...value };
  }
}, { deep: true });
</script>
