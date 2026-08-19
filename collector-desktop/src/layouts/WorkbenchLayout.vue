<template>
  <div class="workbench-shell industrial-shell" :class="{ 'without-resource-panel': !showResourcePanel }">
    <aside class="primary-sidebar">
      <div class="brand-block">
        <div class="brand-mark">
          <span>DC</span>
        </div>
        <div class="brand-copy">
          <strong>数据采集工作台</strong>
        </div>
      </div>

      <div class="sidebar-status-card">
        <div class="sidebar-status-line">
          <span class="status-pill" :class="runtimeStore.connected ? 'online' : 'offline'">
            <i></i>
            {{ runtimeStore.connected ? 'ONLINE' : 'OFFLINE' }}
          </span>
          <span>{{ deviceStore.onlineCount }}/{{ deviceStore.devices.length }} 设备</span>
        </div>
        <strong>{{ runtimeStore.connected ? '采集服务运行中' : '等待连接后端服务' }}</strong>
        <small>{{ appStore.serverUrl }}</small>
      </div>

      <el-scrollbar class="primary-nav-scroll">
        <nav class="primary-nav">
          <section v-for="group in navigationGroups" :key="group.title" class="nav-group">
            <div class="nav-group-title">
              <span>{{ group.title }}</span>
              <em v-if="group.caption">{{ group.caption }}</em>
            </div>
            <router-link
              v-for="item in group.items"
              :key="item.to"
              :to="item.to"
              class="nav-item"
              active-class="is-active"
            >
              <span class="nav-icon">
                <el-icon><component :is="item.icon" /></el-icon>
              </span>
              <span class="nav-copy">
                <strong>{{ item.label }}</strong>
                <small>{{ item.desc }}</small>
              </span>
              <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
            </router-link>
          </section>
        </nav>
      </el-scrollbar>

      <div class="sidebar-footer-card">
        <router-link to="/login" class="sidebar-settings-link">
          <el-icon><Setting /></el-icon>
          <span>
            <strong>连接设置</strong>
            <small>服务地址、Token、本地配置</small>
          </span>
        </router-link>
      </div>
    </aside>

    <header class="top-command-bar">
      <div class="page-heading">
        <span class="page-kicker">{{ currentPage.group }}</span>
        <h1>{{ currentPage.label }}</h1>
        <p>{{ currentPage.desc }}</p>
      </div>
      <div class="command-actions">
        <div class="service-summary" :class="runtimeStore.connected ? 'is-online' : 'is-offline'">
          <i></i>
          <span>
            <strong>{{ runtimeStore.connected ? 'Collector Running' : '服务未连接' }}</strong>
            <small>{{ runtimeStore.generatedAtText }}</small>
          </span>
        </div>
        <el-button :icon="Refresh" :loading="runtimeStore.loading || deviceStore.loading" @click="refreshAll">刷新</el-button>
        <el-button type="primary" :loading="deviceStore.operating" @click="deviceStore.reload()">重新加载采集配置</el-button>
      </div>
    </header>

    <aside v-if="showResourcePanel" class="resource-panel">
      <div class="resource-title">
        <div>
          <strong>设备资源</strong>
        </div>
        <el-button :icon="Refresh" text circle :loading="deviceStore.loading" @click="refreshAll" />
      </div>
      <DeviceTree />
    </aside>

    <section class="main-workspace">
      <main class="workspace-content">
        <router-view />
      </main>
    </section>

    <AppStatusBar />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
  Bell,
  Connection,
  DataAnalysis,
  Document,
  Files,
  Grid,
  Guide,
  Monitor,
  Operation,
  Refresh,
  Setting,
  SetUp,
  Share,
  TrendCharts
} from "@element-plus/icons-vue";

import DeviceTree from "@/components/device/DeviceTree.vue";
import AppStatusBar from "@/components/layout/AppStatusBar.vue";
import { useAppStore } from "@/stores/app.store";
import { useDeviceStore } from "@/stores/device.store";
import { useRuntimeStore } from "@/stores/runtime.store";

type NavItem = {
  to: string;
  label: string;
  desc: string;
  icon: unknown;
  badge?: string;
};

type NavGroup = {
  title: string;
  caption: string;
  items: NavItem[];
};

const route = useRoute();
const appStore = useAppStore();
const deviceStore = useDeviceStore();
const runtimeStore = useRuntimeStore();

const navigationGroups: NavGroup[] = [
  {
    title: "运行监控",
    caption: "",
    items: [
      { to: "/dashboard", label: "控制台总览", desc: "全局健康、风险和吞吐", icon: Monitor },
      { to: "/realtime", label: "实时数据", desc: "设备点位当前值", icon: DataAnalysis },
      { to: "/history", label: "历史趋势", desc: "点位曲线与历史表格", icon: TrendCharts },
      { to: "/alarm", label: "告警中心", desc: "告警过滤、确认和统计", icon: Bell }
    ]
  },
  {
    title: "配置建模",
    caption: "",
    items: [
      { to: "/device", label: "设备工作台", desc: "设备、连接、点位一体化", icon: Grid, badge: "核心" },
      { to: "/collect", label: "采集配置", desc: "配置摘要和原始结构", icon: Files },
      { to: "/cloud", label: "云端上报", desc: "Outbox、ACK、处理器", icon: Share }
    ]
  },
  {
    title: "诊断运维",
    caption: "",
    items: [
      { to: "/diagnostic", label: "系统诊断", desc: "模块健康和处置建议", icon: Operation },
      { to: "/log", label: "运行日志", desc: "日志过滤和导出", icon: Document },
      { to: "/network", label: "网络检测", desc: "TCP、Ping、Trace", icon: Connection }
    ]
  },
  {
    title: "控制工具",
    caption: "",
    items: [
      { to: "/control", label: "手动控制", desc: "写点、批量写入、命令", icon: SetUp },
      { to: "/shadow", label: "设备影子", desc: "reported、desired、delta", icon: Guide }
    ]
  }
];

const flatNav = navigationGroups.flatMap((group) => group.items.map((item) => ({ ...item, group: group.title })));
const currentPage = computed(() => {
  const currentPath = route.path === "/" ? "/dashboard" : route.path;
  return flatNav.find((item) => item.to === currentPath) ?? {
    label: "数据采集工作台",
    desc: "配置、控制、监控和诊断统一桌面客户端",
    group: "工作台"
  };
});

const showResourcePanel = computed(() => route.name === "device");

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
