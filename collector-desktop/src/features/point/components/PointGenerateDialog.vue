<template>
  <el-dialog :model-value="modelValue" title="批量生成点位" width="min(560px, calc(100vw - 32px))" class="point-generate-dialog" @update:model-value="$emit('update:modelValue', $event)">
    <p class="batch-form-note">按起始地址与步长生成点位草稿；生成后仍需在点位工作区保存。</p>
    <el-form label-position="top" class="batch-form">
      <el-form-item label="生成数量" class="batch-field-short"><el-input-number v-model="form.count" :min="1" :max="1000" /></el-form-item>
      <el-form-item label="起始地址" class="batch-field-long"><el-input v-model="form.baseAddress" /></el-form-item>
      <el-form-item label="地址步长" class="batch-field-short"><el-input-number v-model="form.addressStep" :min="1" /></el-form-item>
      <el-form-item label="编码前缀" class="batch-field-medium"><el-input v-model="form.pointCodePrefix" /></el-form-item>
      <el-form-item label="名称前缀" class="batch-field-medium"><el-input v-model="form.pointNamePrefix" /></el-form-item>
      <el-form-item label="数据类型" class="batch-field-select">
        <el-select v-model="form.dataType">
          <el-option label="BOOL" value="BOOL" />
          <el-option label="INT" value="INT" />
          <el-option label="UINT" value="UINT" />
          <el-option label="DINT" value="DINT" />
          <el-option label="FLOAT" value="FLOAT" />
          <el-option label="DOUBLE" value="DOUBLE" />
          <el-option label="STRING" value="STRING" />
        </el-select>
      </el-form-item>
      <el-form-item label="读写类型" class="batch-field-select">
        <el-select v-model="form.readWrite">
          <el-option label="R" value="R" />
          <el-option label="W" value="W" />
          <el-option label="RW" value="RW" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="apply">生成</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive } from "vue";

import type { BuildIncrementalPointsOptions } from "../utils/point-editor-utils";

defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  generate: [options: BuildIncrementalPointsOptions];
}>();

const form = reactive<BuildIncrementalPointsOptions>({
  count: 6,
  baseAddress: "40001",
  addressStep: 2,
  pointCodePrefix: "point",
  pointNamePrefix: "点位",
  dataType: "FLOAT",
  readWrite: "R"
});

function apply() {
  emit("generate", { ...form });
  emit("update:modelValue", false);
}
</script>

<style scoped>
:global(.point-generate-dialog.el-dialog) {
  display: flex;
  max-height: min(720px, 90dvh);
  flex-direction: column;
  overflow: hidden;
}

:global(.point-generate-dialog .el-dialog__header),
:global(.point-generate-dialog .el-dialog__footer) {
  flex: none;
}

:global(.point-generate-dialog .el-dialog__body) {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}

.batch-form-note {
  margin: 0 0 16px;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.7;
}
.batch-form {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px 20px;
}

.batch-form :deep(.el-form-item) {
  min-width: 0;
  max-width: 100%;
  margin-bottom: 0;
  flex: none;
}

.batch-form :deep(.el-select),
.batch-form :deep(.el-input),
.batch-form :deep(.el-input-number) {
  width: 100%;
}


.batch-form :deep(.batch-field-short) {
  width: 144px;
}

.batch-form :deep(.batch-field-select) {
  width: 180px;
}

.batch-form :deep(.batch-field-medium) {
  width: 240px;
}

.batch-form :deep(.batch-field-long) {
  width: 320px;
}

@media (max-width: 600px) {
  .batch-form {
    flex-direction: column;
  }
}
</style>
