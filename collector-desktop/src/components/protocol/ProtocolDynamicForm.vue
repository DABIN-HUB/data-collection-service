<template>
  <div class="dynamic-form">
    <el-alert v-if="fields.length === 0" title="当前协议暂无可渲染字段，请确认后端 ProtocolDescriptorProvider 是否提供 Schema。" type="info" :closable="false" />
    <section v-for="group in groups" :key="group.name" class="protocol-field-group">
      <h4>{{ group.name }}</h4>
      <el-form label-position="top" class="protocol-form-grid">
        <el-form-item v-for="field in group.fields" :key="field.name" :label="field.label || field.name" :required="field.required">
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
            placeholder="请选择"
            @update:model-value="updateField(field.name, $event)"
          >
            <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
          </el-select>
          <el-input-number
            v-else-if="field.type === 'number' || field.type === 'integer'"
            :model-value="typeof localModel[field.name] === 'number' ? Number(localModel[field.name]) : undefined"
            controls-position="right"
            :placeholder="field.description || field.label || field.name"
            @update:model-value="updateField(field.name, $event ?? null)"
          />
          <el-input
            v-else
            :model-value="String(localModel[field.name] ?? '')"
            :placeholder="field.description || field.label || field.name"
            @update:model-value="updateField(field.name, $event)"
          />
          <small v-if="field.description" class="field-description">{{ field.description }}</small>
        </el-form-item>
      </el-form>
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

function updateField(name: string, value: string | number | boolean | null) {
  localModel.value = {
    ...localModel.value,
    [name]: value
  };
  emit("update:modelValue", localModel.value);
  emit("validate", errors.value);
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
