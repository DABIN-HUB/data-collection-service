<template>
  <DeviceOperationShell active-tab="shadow">
    <template #default="{ deviceId }">
      <ShadowPanel :device-id="deviceId" />
    </template>
  </DeviceOperationShell>
</template>

<script setup lang="ts">
import DeviceOperationShell from "@/features/device/components/DeviceOperationShell.vue";
import ShadowPanel from "@/features/shadow/components/ShadowPanel.vue";
</script>
