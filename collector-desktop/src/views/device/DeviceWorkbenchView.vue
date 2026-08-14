<template>
  <div class="page-stack device-workbench">
    <section class="page-title-row">
      <div>
        <span class="page-kicker">设备管理</span>
        <h2>设备配置工作区</h2>
        <p>左侧设备树选择设备，右侧进行基本配置、协议配置和点位配置。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="deviceStore.loading" @click="deviceStore.refresh()">刷新</el-button>
        <el-button :loading="deviceStore.operating" @click="deviceStore.syncConfig()">同步远端配置</el-button>
        <el-button :loading="deviceStore.operating" @click="deviceStore.reload()">重新加载采集配置</el-button>
        <el-button type="primary" plain @click="openLocalEditor()">新建设备</el-button>
      </div>
    </section>

    <el-alert v-if="deviceStore.error" :title="deviceStore.error" type="warning" :closable="false" />

    <div class="device-workspace-grid">
      <DeviceListTable
        :devices="deviceStore.devices"
        :selected-device-id="deviceStore.selectedDeviceId"
        :loading="deviceStore.loading"
        @refresh="deviceStore.refresh()"
        @select="deviceStore.selectDevice"
        @start="deviceStore.startSmart"
        @stop="deviceStore.stop"
        @status="showStatus"
        @diff="showDiff"
        @edit-local="editLocalDevice"
        @delete-local="deleteLocalDevice"
      />
      <DeviceConfigPanel :device="deviceStore.selectedDevice" @start="deviceStore.startSmart" @stop="deviceStore.stop" />
    </div>

    <el-dialog v-model="statusVisible" title="设备运行状态" width="760px">
      <pre class="json-view">{{ statusText }}</pre>
    </el-dialog>

    <el-dialog v-model="diffVisible" title="配置差异" width="760px">
      <pre class="json-view">{{ diffText }}</pre>
    </el-dialog>

    <LocalDeviceEditor
      v-model="localEditorVisible"
      :editing-bundle="localEditingBundle"
      :protocols="protocolStore.protocols"
      @saved="handleLocalSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";

import { getLocalDevice } from "@/api/config.api";
import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import DeviceListTable from "@/components/device/DeviceListTable.vue";
import LocalDeviceEditor from "@/components/device/LocalDeviceEditor.vue";
import { useDeviceStore } from "@/stores/device.store";
import { useProtocolStore } from "@/stores/protocol.store";

const deviceStore = useDeviceStore();
const protocolStore = useProtocolStore();
const statusVisible = ref(false);
const diffVisible = ref(false);
const localEditorVisible = ref(false);
const localEditingBundle = ref<LocalDeviceBundle | null>(null);
const statusText = ref("{}");
const diffText = ref("{}");

interface LocalDeviceBundle {
  device?: Record<string, unknown>;
  connection?: Record<string, unknown>;
  points?: never[];
}

function openLocalEditor(bundle: LocalDeviceBundle | null = null) {
  localEditingBundle.value = bundle;
  localEditorVisible.value = true;
}

async function editLocalDevice(deviceId: string) {
  try {
    const response = await getLocalDevice(deviceId);
    openLocalEditor(normalizeLocalBundle(response));
  } catch (error) {
    deviceStore.error = error instanceof Error ? error.message : "本地设备加载失败";
  }
}

async function handleLocalSaved(deviceId: string) {
  await deviceStore.refresh();
  deviceStore.selectDevice(deviceId);
}

async function showStatus(deviceId: string) {
  try {
    const status = await deviceStore.loadStatus(deviceId);
    statusText.value = JSON.stringify(status, null, 2);
    statusVisible.value = true;
  } catch (error) {
    deviceStore.error = error instanceof Error ? error.message : "设备状态加载失败";
  }
}

async function showDiff(deviceId: string) {
  try {
    const diff = await deviceStore.loadDiff(deviceId);
    diffText.value = JSON.stringify(diff, null, 2);
    diffVisible.value = true;
  } catch (error) {
    deviceStore.error = error instanceof Error ? error.message : "配置差异加载失败";
  }
}

async function deleteLocalDevice(deviceId: string) {
  await deviceStore.deleteLocal(deviceId);
  if (!deviceStore.error) {
    ElMessage.success("本地临时设备已删除");
  }
}

function normalizeLocalBundle(response: unknown): LocalDeviceBundle {
  if (!response || typeof response !== "object" || Array.isArray(response)) {
    return {};
  }
  const body = response as Record<string, unknown>;
  const bundle = body.bundle && typeof body.bundle === "object" && !Array.isArray(body.bundle)
    ? body.bundle as Record<string, unknown>
    : body;
  return {
    device: isRecord(bundle.device) ? bundle.device : undefined,
    connection: isRecord(bundle.connection) ? bundle.connection : undefined,
    points: Array.isArray(bundle.points) ? bundle.points as never[] : []
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

onMounted(async () => {
  await Promise.allSettled([
    deviceStore.refresh(),
    protocolStore.refresh()
  ]);
});
</script>
