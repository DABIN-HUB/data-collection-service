<template>
  <div class="local-cloud-target-form">
    <label class="cloud-toggle-row">启用云上报<el-switch :id="`${idPrefix}localCloudEnabled`" :model-value="cloudTarget.enabled" @update:model-value="emit('update-field', 'enabled', $event)" /></label>
    <div class="cloud-identity-fields">
      <label>设备类型<select :id="`${idPrefix}localCloudDeviceType`" :value="cloudTarget.deviceType" @change="emit('update-field', 'deviceType', ($event.target as HTMLSelectElement).value)"><option v-for="value in ['SUB_DEVICE', 'GATEWAY', 'DIRECT', 'LOGICAL_SUB_DEVICE']" :key="value" :value="value">{{ value }}</option></select></label>
      <label>ProductKey<input :id="`${idPrefix}localCloudProductKey`" :value="cloudTarget.productKey || ''" placeholder="pk_xxx" @input="emit('update-field', 'productKey', ($event.target as HTMLInputElement).value)"></label>
      <label>DeviceName<input :id="`${idPrefix}localCloudDeviceName`" :value="cloudTarget.deviceName || ''" placeholder="sub_device_001" @input="emit('update-field', 'deviceName', ($event.target as HTMLInputElement).value)"></label>
    </div>
    <label class="cloud-toggle-row">启用拓扑注册<el-switch :id="`${idPrefix}localCloudTopologyEnabled`" :model-value="cloudTarget.topologyEnabled" @update:model-value="emit('update-field', 'topologyEnabled', $event)" /></label>
    <label class="cloud-topic-label">Topic 示例 <small>只读</small><textarea :id="`${idPrefix}localCloudTopicPreview`" :value="topicPreview" readonly rows="3" /></label>
  </div>
</template>

<script setup lang="ts">
import type { CloudTargetConfig } from "@/features/device/utils/local-device-utils";

withDefaults(defineProps<{ cloudTarget: CloudTargetConfig; topicPreview: string; idPrefix?: string }>(), { idPrefix: "" });
const emit = defineEmits<{ "update-field": [key: keyof CloudTargetConfig, value: unknown] }>();
</script>

<style scoped>
.local-cloud-target-form {
  min-width: 0;
  color: var(--panel-text);
  font-size: 12px;
}

.cloud-toggle-row {
  display: flex;
  padding: 9px 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cloud-identity-fields {
  display: grid;
  gap: 13px;
}

.cloud-identity-fields label,
.cloud-topic-label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  color: #c3d0e1;
}

.cloud-identity-fields input,
.cloud-identity-fields select {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  height: 35px;
  padding: 7px 9px;
  color: var(--panel-text);
  border: 1px solid #45607d;
  border-radius: 4px;
  background: var(--editor-input);
  font: inherit;
}

.cloud-identity-fields select {
  padding-right: 24px;
}

.cloud-identity-fields input::placeholder {
  color: #7e94ae;
}

.cloud-topic-label small {
  color: var(--panel-muted);
  font-size: 12px;
}

.cloud-topic-label textarea {
  box-sizing: border-box;
  width: 100%;
  height: 76px;
  min-height: 76px;
  padding: 9px 10px;
  color: #a8c6eb;
  border: 1px solid #334e6b;
  border-radius: 4px;
  background: #142338;
  font: 11px/1.7 Consolas, "Microsoft YaHei UI", monospace;
  resize: none;
  overflow-wrap: anywhere;
}

.cloud-identity-fields input:focus-visible,
.cloud-identity-fields select:focus-visible,
.cloud-topic-label textarea:focus-visible {
  outline: 2px solid #7ab3ff;
  outline-offset: 3px;
}

.local-cloud-target-form :deep(.el-switch) {
  --el-switch-on-color: #447fe5;
  --el-switch-off-color: #42526a;
  height: 35px;
}

.local-cloud-target-form :deep(.el-switch__core) {
  min-width: 30px;
  height: 17px;
}

.local-cloud-target-form :deep(.el-switch__action) {
  width: 11px;
  height: 11px;
}

.local-cloud-target-form :deep(.el-switch.is-checked .el-switch__action) {
  left: calc(100% - 13px);
}
</style>
