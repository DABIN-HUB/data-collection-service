<template>
  <div class="local-device-protocol-form dynamic-form">
    <el-alert v-if="fields.length === 0" title="当前协议暂无可渲染字段，请确认后端 ProtocolDescriptorProvider 是否提供 Schema。" type="info" :closable="false" />
    <section v-for="group in groups" :key="group.name" class="protocol-field-group" :data-group="group.name">
      <h4>{{ displayGroupName(group.name) }}<small>{{ group.fields.length }} 个字段</small></h4>
      <div class="protocol-form-grid">
        <div
          v-for="field in group.fields"
          :key="field.name"
          class="protocol-field-row"
          :class="{ 'is-required': field.required, 'is-boolean': field.type === 'boolean', 'is-wide': isWideField(field) }"
          :data-field="field.name"
          :style="{ '--control-width': `${presentation(field).controlWidth}px`, '--slot-width': `${presentation(field).slotWidth}px` }"
          :title="fieldHelp(field)"
        >
          <label class="protocol-field-label" :title="field.label || field.name">
            {{ displayFieldLabel(field) }}<span v-if="field.required" class="protocol-field-required">*</span><small v-if="field.requiredWhen" class="protocol-field-condition">条件必填</small>
          </label>
          <div class="protocol-field-control" :class="{ 'has-unit': Boolean(presentation(field).unit) && (field.type === 'number' || field.type === 'integer') && !field.options?.length }">
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
              :type="presentation(field).secret ? 'password' : presentation(field).multiline ? 'textarea' : 'text'"
              :rows="3"
              @update:model-value="updateField(field.name, $event)"
            />
            <span v-if="presentation(field).unit && (field.type === 'number' || field.type === 'integer') && !field.options?.length" class="protocol-field-unit">{{ presentation(field).unit }}</span>
          </div>
          <p v-if="presentation(field).help" class="protocol-field-help">{{ presentation(field).help }}</p>
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
  groupProtocolFields,
  isWideProtocolField,
  validateProtocolModel,
  type ProtocolFormModel
} from "@/components/protocol/protocol-form-utils";
import { connectionFieldPresentation } from "@/features/protocol/utils/connection-field-presentation";
import type { ProtocolFieldConfig } from "@/types/protocol";

const props = defineProps<{
  fields: ProtocolFieldConfig[];
  protocol: string;
  modelValue?: ProtocolFormModel;
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

// 复用已审阅的展示尺寸，保留原动态表单的控件、model 更新与 validate 契约。
function presentation(field: ProtocolFieldConfig) {
  const result = connectionFieldPresentation(props.protocol, field);
  const optionWidth = field.options?.length ? Math.max(...field.options.map((option) => Array.from(option).reduce((width, char) => width + ((char.codePointAt(0) || 0) > 255 ? 13 : 7.5), 0))) + 48 : 0;
  const controlWidth = Math.max(result.controlWidth, Math.min(560, optionWidth));
  return { ...result, controlWidth, slotWidth: Math.max(result.slotWidth, controlWidth) };
}

function displayFieldLabel(field: ProtocolFieldConfig): string {
  return presentation(field).label;
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
.local-device-protocol-form {
  display: grid;
  min-width: 0;
  gap: 14px;
}

.protocol-field-group {
  min-width: 0;
  padding-top: 14px;
  border-top: 1px solid #334357;
}

.protocol-field-group:first-of-type {
  padding-top: 0;
  border-top: 0;
}

.protocol-field-group h4 {
  display: flex;
  margin: 0 0 11px;
  align-items: center;
  gap: 8px;
  color: var(--panel-text);
  font-size: 13px;
  font-weight: 600;
}

.protocol-field-group h4 small {
  margin-left: auto;
  color: var(--panel-muted);
  font-size: 11px;
  font-weight: 400;
}

.protocol-form-grid {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 13px 18px;
}

.protocol-field-row {
  display: flex;
  width: min(var(--slot-width), 100%);
  min-width: 0;
  flex: none;
  flex-direction: column;
  gap: 6px;
}

.protocol-field-label {
  min-height: 18px;
  color: #c3d0e1;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.protocol-field-required {
  margin-left: 4px;
  color: #ffa4aa;
}

.protocol-field-condition {
  margin-left: 7px;
  color: #f4bf7b;
  font-size: 11px;
}

.protocol-field-control {
  position: relative;
  width: min(var(--control-width), 100%);
  min-width: 0;
}

.protocol-field-control :deep(.el-input),
.protocol-field-control :deep(.el-input-number),
.protocol-field-control :deep(.el-select),
.protocol-field-control :deep(.el-textarea) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.protocol-field-control :deep(.el-switch) {
  min-height: 35px;
}

.protocol-field-row.is-boolean .protocol-field-control {
  width: auto;
  max-width: 100%;
}

.protocol-field-control :deep(.el-switch__core),
.protocol-field-control :deep(.el-switch__label) {
  flex-shrink: 0;
}

.protocol-field-control :deep(.el-switch__label) {
  white-space: nowrap;
}

.protocol-field-control.has-unit :deep(.el-input-number .el-input__inner) {
  padding-right: 22px;
}

.protocol-field-unit {
  position: absolute;
  top: 8px;
  right: 36px;
  color: #99aac0;
  font-size: 12px;
  pointer-events: none;
}

.protocol-field-help {
  margin: 0;
  color: #9caec5;
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-line;
}
</style>
