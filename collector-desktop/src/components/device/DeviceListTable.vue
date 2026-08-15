<template>
  <section class="table-card">
    <div class="table-card-header">
      <div>
        <h2>设备列表</h2>
        <p>展示设备配置、运行状态和本地临时设备操作</p>
      </div>
      <div class="table-actions">
        <el-input v-model="keyword" placeholder="搜索设备名称/编号" clearable :prefix-icon="Search" />
        <el-select v-model="protocolFilter" placeholder="协议" clearable class="mini-filter">
          <el-option v-for="protocol in protocolOptions" :key="protocol" :label="protocol" :value="protocol" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="状态" clearable class="mini-filter">
          <el-option label="在线" value="ONLINE" />
          <el-option label="重连中" value="CONNECTING" />
          <el-option label="异常" value="ERROR" />
          <el-option label="禁用" value="DISABLED" />
          <el-option label="离线" value="OFFLINE" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="$emit('refresh')">刷新</el-button>
      </div>
    </div>
    <el-table
      v-loading="loading"
      :data="filteredDevices"
      height="420"
      highlight-current-row
      :current-row-key="selectedDeviceId"
      row-key="normalizedId"
      @row-click="(row: DeviceViewModel) => $emit('select', row.normalizedId)"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column label="设备名称" min-width="190">
        <template #default="{ row }">
          <div class="device-name-cell">
            <i class="dot" :class="statusClass(row)"></i>
            <strong>{{ row.displayName }}</strong>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="displayProtocol" label="协议类型" width="140" />
      <el-table-column label="地址" min-width="160">
        <template #default="{ row }">
          {{ row.ipAddress || row.host || row.url || '-' }}<span v-if="row.port">:{{ row.port }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="tagType(row)" effect="light">{{ statusText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="点位数" width="100">
        <template #default="{ row }">{{ resolvePointCount(row) }}</template>
      </el-table-column>
      <el-table-column prop="configSource" label="配置来源" width="120" />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click.stop="$emit('select', row.normalizedId)">配置</el-button>
          <el-button size="small" type="success" link @click.stop="$emit('start', row.normalizedId)">启动</el-button>
          <el-button size="small" type="danger" link @click.stop="$emit('stop', row.normalizedId)">停止</el-button>
          <el-button size="small" link @click.stop="$emit('status', row.normalizedId)">状态</el-button>
          <el-button size="small" link @click.stop="$emit('diff', row.normalizedId)">差异</el-button>
          <el-button v-if="isLocalDevice(row)" size="small" type="primary" link @click.stop="$emit('edit-local', row.normalizedId)">编辑</el-button>
          <el-popconfirm v-if="isLocalDevice(row)" title="确认删除该本地临时设备？" confirm-button-text="删除" cancel-button-text="取消" @confirm="$emit('delete-local', row.normalizedId)">
            <template #reference>
              <el-button size="small" type="danger" link @click.stop>删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { Refresh, Search } from "@element-plus/icons-vue";

import { isLocalDevice, resolveDeviceStatus, resolvePointCount } from "@/stores/device.store";
import type { DeviceViewModel } from "@/types/device";

type DeviceStatusTag = "success" | "warning" | "danger" | "info";

const props = defineProps<{
  devices: DeviceViewModel[];
  selectedDeviceId: string;
  loading: boolean;
}>();

defineEmits<{
  refresh: [];
  select: [deviceId: string];
  start: [deviceId: string];
  stop: [deviceId: string];
  status: [deviceId: string];
  diff: [deviceId: string];
  "edit-local": [deviceId: string];
  "delete-local": [deviceId: string];
}>();

const keyword = ref("");
const protocolFilter = ref("");
const statusFilter = ref("");

const protocolOptions = computed(() => Array.from(new Set(props.devices.map((device) => device.displayProtocol).filter(Boolean))).sort());

const filteredDevices = computed(() => {
  const value = keyword.value.trim().toLowerCase();
  return props.devices.filter((device) => {
    const keywordMatched = !value || [
    device.normalizedId,
    device.displayName,
    device.displayProtocol,
    device.ipAddress,
    device.groupName
    ].some((item) => String(item || "").toLowerCase().includes(value));
    const protocolMatched = !protocolFilter.value || device.displayProtocol === protocolFilter.value;
    const statusMatched = !statusFilter.value || resolveDeviceStatus(device) === statusFilter.value;
    return keywordMatched && protocolMatched && statusMatched;
  });
});

function statusClass(device: DeviceViewModel): string {
  return {
    ONLINE: "good",
    CONNECTING: "warn",
    ERROR: "bad",
    DISABLED: "muted",
    OFFLINE: "muted"
  }[resolveDeviceStatus(device)];
}

function tagType(device: DeviceViewModel): DeviceStatusTag {
  return {
    ONLINE: "success",
    CONNECTING: "warning",
    ERROR: "danger",
    DISABLED: "info",
    OFFLINE: "info"
  }[resolveDeviceStatus(device)] as DeviceStatusTag;
}

function statusText(device: DeviceViewModel): string {
  return {
    ONLINE: "在线",
    CONNECTING: "重连中",
    ERROR: "异常",
    DISABLED: "禁用",
    OFFLINE: "离线"
  }[resolveDeviceStatus(device)];
}
</script>
