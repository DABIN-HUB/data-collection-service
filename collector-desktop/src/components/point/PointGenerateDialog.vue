<template>
  <el-dialog :model-value="modelValue" title="批量生成点位" width="520px" @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top" class="batch-form">
      <el-form-item label="生成数量"><el-input-number v-model="form.count" :min="1" :max="1000" /></el-form-item>
      <el-form-item label="起始地址"><el-input v-model="form.baseAddress" /></el-form-item>
      <el-form-item label="地址步长"><el-input-number v-model="form.addressStep" :min="1" /></el-form-item>
      <el-form-item label="编码前缀"><el-input v-model="form.pointCodePrefix" /></el-form-item>
      <el-form-item label="名称前缀"><el-input v-model="form.pointNamePrefix" /></el-form-item>
      <el-form-item label="数据类型">
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
      <el-form-item label="读写类型">
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

import type { BuildIncrementalPointsOptions } from "./point-editor-utils";

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
