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
          :class="{ 'is-required': field.required, 'is-boolean': field.type === 'boolean', 'is-wide': isWideField(field), 'is-number': field.type === 'number' || field.type === 'integer', 'is-select': Boolean(field.options?.length) }"
          :data-field="field.name"
          :style="{ '--option-width': `${Math.max(160, Math.min(560, Math.max(...(field.options || []).map(option => Array.from(option).reduce((width, char) => width + ((char.codePointAt(0) || 0) > 255 ? 13 : 7.5), 0))) + 48))}px` }"
          :title="fieldHelp(field)"
        >
          <label class="protocol-field-label" :title="field.label || field.name">
            {{ displayFieldLabel(field) }}<span v-if="field.required" class="protocol-field-required">*</span><small v-if="field.requiredWhen" class="protocol-field-condition">条件必填</small>
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
          <p v-if="field.description || field.requiredWhen" class="protocol-field-help">{{ field.description }}<span v-if="field.requiredWhen"> 条件：{{ field.requiredWhen }}</span></p>
        </div>
      </div>
    </section>
    <el-alert v-if="errors.length > 0" :title="errors.join('；')" type="warning" :closable="false" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";

import {
  buildProtocolInitialModel,
  displayGroupName,
  displayFullProtocolFieldLabel,
  displayProtocolFieldLabel,
  groupProtocolFields,
  isWideProtocolField,
  validateProtocolModel,
  type ProtocolFormModel
} from "./protocol-form-utils";
import type { ProtocolFieldConfig } from "@/types/protocol";

const props = defineProps<{
  fields: ProtocolFieldConfig[];
  modelValue?: ProtocolFormModel;
  fullFieldLabels?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: ProtocolFormModel];
  validate: [errors: string[]];
}>();

const localModel = ref<ProtocolFormModel>({});
const groups = computed(() => groupProtocolFields(props.fields));
const errors = computed(() => validateProtocolModel(props.fields, localModel.value));

function updateField(name: string, value: string | number | boolean | null) {
  localModel.value = {
    ...localModel.value,
    [name]: value
  };
  emit("update:modelValue", localModel.value);
  emit("validate", errors.value);
}

function displayFieldLabel(field: ProtocolFieldConfig): string {
  return props.fullFieldLabels ? displayFullProtocolFieldLabel(field) : displayProtocolFieldLabel(field);
}

function fieldPlaceholder(field: ProtocolFieldConfig): string {
  return String(field.description || field.label || field.name || "请输入");
}

function fieldHelp(field: ProtocolFieldConfig): string {
  const source = [field.label, field.description, field.name].filter(Boolean).join("：");
  return source || displayFieldLabel(field);
}

function isWideField(field: ProtocolFieldConfig): boolean {
  return isWideProtocolField(field);
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

<style scoped>
.dynamic-form {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.protocol-field-group {
  margin-top: 0;
  min-width: 0;
}

.protocol-field-row.is-number:not(.is-select) .protocol-field-control {
  width: min(144px, 100%);
}

.protocol-field-row[data-field$="Port"].is-number:not(.is-select) .protocol-field-control,
.protocol-field-row[data-field="port"].is-number:not(.is-select) .protocol-field-control {
  width: min(112px, 100%);
}

.dynamic-form > .protocol-field-group + .protocol-field-group {
  padding-top: 18px;
  border-top: 1px solid var(--console-border-soft);
}

.protocol-field-group h4 {
  display: flex;
  margin: 0 0 8px;
  align-items: center;
  gap: 7px;
  color: var(--console-text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.protocol-form-grid {
  display: flex;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 18px 24px;
}

.protocol-field-row {
  --field-width: 220px;
  display: flex;
  min-width: 0;
  min-height: 34px;
  width: min(var(--field-width), 100%);
  max-width: 100%;
  flex: none;
  flex-direction: column;
  gap: 6px;
}

.protocol-field-row.is-wide {
  --field-width: 560px;
}

.protocol-field-row[data-field*="opic"] {
  --field-width: 480px;
}

.protocol-field-row[data-field="host"],
.protocol-field-row[data-field="clientId"] {
  --field-width: 320px;
}

.protocol-field-row.is-number,
.protocol-field-row.is-boolean {
  --field-width: 160px;
}

.protocol-field-row.is-select {
  --field-width: var(--option-width);
}

.protocol-field-label {
  min-width: 0;
  color: var(--console-text-secondary);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.protocol-field-required {
  margin-left: 2px;
  color: var(--console-danger-text);
}

.protocol-field-condition {
  margin-left: 7px;
  color: var(--console-warning-text);
  font-size: 11px;
}

.protocol-field-help {
  margin: 0;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.protocol-field-control {
  width: 100%;
  min-width: 0;
}

.protocol-field-control :deep(.el-input),
.protocol-field-control :deep(.el-input-number),
.protocol-field-control :deep(.el-select) {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.protocol-field-control :deep(.el-input__wrapper),
.protocol-field-control :deep(.el-select__wrapper) {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.protocol-field-control :deep(.el-switch) {
  min-height: 32px;
}

@media (max-width: 600px) {
  .protocol-form-grid {
    flex-direction: column;
    gap: 16px;
  }
}
</style>
