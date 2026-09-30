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
          :class="{ 'is-required': field.required, 'is-boolean': field.type === 'boolean', 'is-wide': isWideField(field) }"
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

import {
  buildProtocolInitialModel,
  displayGroupName,
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
  return displayProtocolFieldLabel(field);
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
}

.dynamic-form > .protocol-field-group + .protocol-field-group {
  margin-top: 0;
}

.protocol-field-group h4 {
  display: flex;
  margin: 0 0 8px;
  align-items: center;
  gap: 7px;
  color: var(--console-text-primary);
  font-size: 13px;
  font-weight: 800;
  line-height: 1.25;
}

.protocol-field-group h4::before {
  display: inline-block;
  width: 3px;
  height: 14px;
  border-radius: 999px;
  background: var(--console-primary-hover);
  content: "";
}

.protocol-form-grid {
  display: grid;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}

.protocol-field-row {
  display: grid;
  min-width: 0;
  min-height: 34px;
  width: 100%;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  column-gap: 8px;
}

.protocol-field-row.is-wide {
  grid-column: 1 / -1;
  grid-template-columns: minmax(96px, 108px) minmax(0, 1fr);
}

.protocol-field-label {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.protocol-field-required {
  margin-left: 2px;
  color: #fca5a5;
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

@media (max-width: 980px) {
  .protocol-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
