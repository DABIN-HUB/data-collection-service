<template>
  <DeviceOperationShell active-tab="control">
    <template #default="{ deviceId }">
      <ControlPanel :device-id="deviceId" />
    </template>
  </DeviceOperationShell>
</template>

<script setup lang="ts">
import DeviceOperationShell from "@/features/device/components/DeviceOperationShell.vue";
import ControlPanel from "@/features/control/components/ControlPanel.vue";
</script>
