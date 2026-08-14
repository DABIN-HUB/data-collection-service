<template>
  <div class="workbench-shell">
    <aside class="activity-bar">
      <div class="activity-logo">采</div>
      <span class="activity-group-title">运行</span>
      <router-link to="/dashboard" class="activity-item" active-class="is-active" title="控制台总览">总</router-link>
      <router-link to="/realtime" class="activity-item" active-class="is-active" title="实时数据查询">实</router-link>
      <router-link to="/history" class="activity-item" active-class="is-active" title="历史数据">历</router-link>
      <router-link to="/alarm" class="activity-item" active-class="is-active" title="告警总览">警</router-link>
      <span class="activity-group-title">配置</span>
      <router-link to="/device" class="activity-item" active-class="is-active" title="设备管理">设</router-link>
      <router-link to="/collect" class="activity-item" active-class="is-active" title="采集配置">采</router-link>
      <router-link to="/cloud" class="activity-item" active-class="is-active" title="云平台配置">云</router-link>
      <span class="activity-group-title">诊断</span>
      <router-link to="/diagnostic" class="activity-item" active-class="is-active" title="系统诊断">诊</router-link>
      <router-link to="/log" class="activity-item" active-class="is-active" title="日志">志</router-link>
      <router-link to="/network" class="activity-item" active-class="is-active" title="网络检测">网</router-link>
      <span class="activity-group-title">工具</span>
      <router-link to="/control" class="activity-item" active-class="is-active" title="手动控制">控</router-link>
      <router-link to="/shadow" class="activity-item" active-class="is-active" title="设备影子">影</router-link>
      <div class="activity-spacer"></div>
      <router-link to="/login" class="activity-item" title="连接设置">
        <el-icon><Setting /></el-icon>
      </router-link>
    </aside>

    <aside class="resource-panel">
      <div class="resource-title">
        <div>
          <span>资源管理器</span>
          <strong>设备资源树</strong>
        </div>
        <el-button :icon="Refresh" text circle :loading="deviceStore.loading" @click="refreshAll" />
      </div>
      <DeviceTree />
    </aside>

    <section class="main-workspace">
      <header class="workbench-header">
        <div>
          <h1>数据采集工作台</h1>
          <p>配置、控制、监控和诊断统一桌面客户端</p>
        </div>
        <div class="header-actions">
          <el-tag :type="runtimeStore.connected ? 'success' : 'info'" effect="light">
            {{ runtimeStore.connected ? 'Collector Running' : '服务未连接' }}
          </el-tag>
          <el-button type="primary" :loading="deviceStore.operating" @click="deviceStore.reload()">重新加载采集配置</el-button>
        </div>
      </header>

      <main class="workspace-content">
        <router-view />
      </main>
    </section>

    <AppStatusBar />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from "vue";
import { Refresh } from "@element-plus/icons-vue";

import DeviceTree from "@/components/device/DeviceTree.vue";
import AppStatusBar from "@/components/layout/AppStatusBar.vue";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import { useRuntimeStore } from "@/stores/runtime.store";

const appStore = useAppStore();
const deviceStore = useDeviceStore();
const runtimeStore = useRuntimeStore();

async function refreshAll() {
  await Promise.allSettled([
    runtimeStore.refresh(),
    deviceStore.refresh()
  ]);
}

onMounted(async () => {
  await appStore.initialize();
  await refreshAll();
});
</script>
