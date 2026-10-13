<template>
  <el-dialog :model-value="modelValue" title="批量编辑点位" width="min(560px, calc(100vw - 32px))" class="point-batch-edit-dialog" @update:model-value="$emit('update:modelValue', $event)">
    <div class="batch-edit-body">
      <el-alert :title="`已选择 ${selectedCount} 个点位`" type="info" :closable="false" />
      <p class="batch-form-note">仅修改勾选的字段；应用到点位草稿后，需在点位工作区保存。</p>
      <el-checkbox-group v-model="fields" class="batch-field-list">
        <el-checkbox label="alarmEnabled">报警启用</el-checkbox>
        <el-checkbox label="dataType">数据类型</el-checkbox>
        <el-checkbox label="readWrite">读写类型</el-checkbox>
        <el-checkbox label="unit">单位</el-checkbox>
        <el-checkbox label="baseCollectionInterval">采集周期</el-checkbox>
      </el-checkbox-group>
      <el-form label-position="top" class="batch-form">
        <el-form-item v-if="fields.includes('alarmEnabled')" label="报警启用">
          <el-switch v-model="alarmEnabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item v-if="fields.includes('dataType')" label="数据类型">
          <el-select v-model="values.dataType">
            <el-option label="BOOL" value="BOOL" />
            <el-option label="INT" value="INT" />
            <el-option label="UINT" value="UINT" />
            <el-option label="DINT" value="DINT" />
            <el-option label="FLOAT" value="FLOAT" />
            <el-option label="DOUBLE" value="DOUBLE" />
            <el-option label="STRING" value="STRING" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="fields.includes('readWrite')" label="读写类型">
          <el-select v-model="values.readWrite">
            <el-option label="R" value="R" />
            <el-option label="W" value="W" />
            <el-option label="RW" value="RW" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="fields.includes('unit')" label="单位">
          <el-input v-model="values.unit" placeholder="例如 ℃、MPa、A" />
        </el-form-item>
        <el-form-item v-if="fields.includes('baseCollectionInterval')" label="采集周期 ms">
          <el-input-number v-model="values.baseCollectionInterval" :min="100" :step="100" />
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="selectedCount === 0 || fields.length === 0" @click="apply">应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";

import type { PointBatchEditPayload } from "../utils/point-editor-utils";

const props = defineProps<{
  modelValue: boolean;
  selectedCount: number;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  apply: [payload: PointBatchEditPayload];
}>();

const fields = ref<string[]>(["alarmEnabled"]);
const alarmEnabled = ref(true);
const values = reactive({
  dataType: "FLOAT",
  readWrite: "R",
  unit: "-",
  baseCollectionInterval: 1000
});

const payloadValues = computed(() => ({
  alarmEnabled: alarmEnabled.value ? 1 : 0,
  dataType: values.dataType,
  readWrite: values.readWrite,
  unit: values.unit,
  baseCollectionInterval: values.baseCollectionInterval
}));

function apply() {
  emit("apply", {
    fields: fields.value,
    values: payloadValues.value
  });
  emit("update:modelValue", false);
}

void props;
</script>

<style scoped>
:global(.point-batch-edit-dialog.el-dialog) {
  display: flex;
  max-height: min(720px, 90dvh);
  flex-direction: column;
  overflow: hidden;
}

:global(.point-batch-edit-dialog .el-dialog__header),
:global(.point-batch-edit-dialog .el-dialog__footer) {
  flex: none;
}

:global(.point-batch-edit-dialog .el-dialog__body) {
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
.batch-edit-body,
.batch-field-list {
  display: grid;
  gap: 12px;
}

.batch-field-list {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 12px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.batch-form {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px 20px;
}

.batch-form :deep(.el-form-item) {
  width: min(200px, 100%);
  min-width: 0;
  max-width: 100%;
  margin: 0;
  flex: none;
}

.batch-form :deep(.el-form-item:has(.el-input-number)) {
  width: min(160px, 100%);
}

.batch-form :deep(.el-select),
.batch-form :deep(.el-input),
.batch-form :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 600px) {
  .batch-form {
    flex-direction: column;
  }

  .batch-field-list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
