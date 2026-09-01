<template>
  <el-dialog :model-value="modelValue" title="批量编辑点位" width="520px" @update:model-value="$emit('update:modelValue', $event)">
    <div class="batch-edit-body">
      <el-alert :title="`已选择 ${selectedCount} 个点位`" type="info" :closable="false" />
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
