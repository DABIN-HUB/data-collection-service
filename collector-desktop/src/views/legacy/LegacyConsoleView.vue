<template>
  <div class="legacy-console theme-anchor modao-exact">
    <aside class="sidebar">
      <div class="sidebar-top">
        <div class="brand">
          <span class="brand-mark">厂</span>
          <div class="brand-copy"><strong>工业数据控制台</strong></div>
        </div>
        <nav class="section-nav" aria-label="控制台主导航">
          <div v-for="group in navGroups" :key="group.title" class="nav-group">
            <span class="nav-group-title">{{ group.title }}</span>
            <a
              v-for="item in group.items"
              :key="item.key"
              href=""
              :class="{ 'is-active': activeModule === item.key }"
              @click.prevent="switchModule(item.key)"
            >
              <span class="nav-glyph" aria-hidden="true">{{ item.icon }}</span><span>{{ item.label }}</span>
            </a>
          </div>
        </nav>
      </div>

      <div class="sidebar-bottom">
        <div class="system-status" :class="systemStatusClass"><i></i><span>{{ systemStatusText }}</span></div>
        <details class="sidebar-token-drawer">
          <summary>运维令牌</summary>
          <div class="top-token-panel">
            <label>接口访问令牌</label>
            <input v-model="tokenInput" type="password" placeholder="请输入接口令牌" autocomplete="off" @keyup.enter="saveToken" />
            <button type="button" @click="saveToken">保存令牌</button>
          </div>
        </details>
      </div>
    </aside>

    <main class="content">
      <header class="topbar">
        <div class="node-status-bar">
          <span class="node-item"><span>本机节点: <strong>{{ nodeIdentity }}</strong></span></span>
          <span class="node-divider"></span>
          <span class="node-item node-health"><i></i>服务状态: {{ systemStatusText }}</span>
          <span class="node-divider"></span>
          <span class="node-item"><span>时间: <strong>{{ liveClock }}</strong></span></span>
        </div>
      </header>

      <section v-show="activeModule === 'overview'" id="overview" class="overview-section">
        <div class="section-heading">
          <div class="heading-title-line">
            <h1>控制台总览</h1>
            <span class="heading-online"><i></i>设备连接 <b>{{ onlineCount }}/{{ devices.length }}</b></span>
          </div>
          <div class="heading-actions">
            <button type="button" class="primary" @click="refreshAll">刷新全部</button>
            <button type="button" @click="switchModule('device')">设备管理</button>
            <button type="button" class="primary" @click="openLocalEditor()">新增本地设备</button>
            <span class="heading-note">{{ lastRefreshText }}</span>
          </div>
        </div>
        <div class="overview-cards">
          <article v-for="card in overviewCards" :key="card.label" class="metric-card">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.note }}</small>
          </article>
        </div>
        <div class="home-dashboard">
          <div class="home-dashboard-row home-dashboard-primary">
            <section class="home-panel home-panel-large">
              <div class="home-panel-head"><div><h2>全局告警最近记录</h2></div><span class="home-panel-badge">{{ alarms.length ? `${alarms.length} 条` : '数据不可用' }}</span></div>
              <div class="home-event-list">
                <div v-if="alarms.length === 0" class="empty-state compact">暂无告警记录</div>
                <div v-for="alarm in alarms.slice(0, 6)" :key="String(alarm.alarmId || alarm.id || alarm.timestamp)" class="event-row">
                  <strong>{{ alarm.level || alarm.alarmType || '告警' }}</strong>
                  <span>{{ alarm.deviceName || alarm.deviceId || '-' }} / {{ alarm.pointName || alarm.pointCode || '-' }}</span>
                  <small>{{ alarm.content || alarm.message || alarm.alarmContent || '-' }}</small>
                </div>
              </div>
            </section>
            <section class="home-panel">
              <div class="home-panel-head"><div><h2>设备异常风险</h2></div><span class="home-panel-badge">{{ riskDevices.length ? `${riskDevices.length} 台风险` : '正常' }}</span></div>
              <div class="home-risk-list">
                <div v-if="riskDevices.length === 0" class="empty-state compact">当前没有明显设备风险</div>
                <div v-for="device in riskDevices" :key="deviceIdOf(device)" class="risk-row"><strong>{{ device.deviceName || deviceIdOf(device) }}</strong><span>{{ device.status || device.lastError || '异常' }}</span></div>
              </div>
            </section>
          </div>
          <div class="home-dashboard-row home-dashboard-observability">
            <section class="home-panel home-panel-report">
              <div class="home-panel-head"><div><h2>数据上报链路拓扑</h2></div><span class="home-panel-badge">{{ reportState }}</span></div>
              <div class="pipeline-steps">
                <div v-for="step in pipelineSteps" :key="step" class="pipeline-step">{{ step }}</div>
              </div>
            </section>
            <section class="home-panel home-panel-runtime">
              <div class="home-panel-head"><div><h2>系统资源与线程池</h2></div><span class="home-panel-badge">{{ runtimeState }}</span></div>
              <div class="home-resource-list"><pre class="json-view compact-json">{{ compactJson(systemResource) }}</pre></div>
            </section>
          </div>
        </div>
      </section>

      <section v-show="activeModule === 'realtime'" class="exact-page">
        <div class="section-heading">
          <div class="heading-title-line"><h1>实时数据查询</h1><span class="heading-online"><i></i>实时采集链路</span></div>
          <div class="heading-actions"><button type="button" @click="loadRealtime">立即刷新</button></div>
        </div>
        <div class="exact-page-body">
          <div class="exact-toolbar">
            <div class="exact-toolbar-group"><button type="button" class="toggle-button" :class="{ 'is-active': realtimeAuto }" @click="realtimeAuto = !realtimeAuto"><span></span>自动刷新</button><small>默认间隔 5 秒</small></div>
            <div class="exact-toolbar-group exact-toolbar-filters"><select v-model="realtimeDeviceId" @change="loadRealtime"><option value="">全部设备</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="realtimeKeyword" type="search" placeholder="搜索点位名称、编码或地址" /></div>
          </div>
          <section class="exact-table-card"><table><thead><tr><th>点位名称</th><th>设备名称</th><th>数据类型</th><th>寄存器地址</th><th>读写</th><th>缩放</th><th>当前值</th><th>单位</th><th>采集时间</th><th>质量</th><th>处理耗时</th></tr></thead><tbody><tr v-if="filteredRealtimeRows.length === 0"><td colspan="11" class="exact-empty">请选择设备后查看实时数据</td></tr><tr v-for="row in filteredRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode}`"><td>{{ row.pointName || row.pointCode || '-' }}</td><td>{{ row.deviceName || deviceNameOf(String(row.deviceId || realtimeDeviceId)) }}</td><td>{{ row.dataType || '-' }}</td><td>{{ row.address || '-' }}</td><td>{{ row.readWrite || '-' }}</td><td>{{ row.scalingFactor ?? '-' }}</td><td>{{ row.value ?? row.currentValue ?? '-' }}</td><td>{{ row.unit || '-' }}</td><td>{{ formatTime(row.timestamp || row.collectTime) }}</td><td>{{ row.quality || '-' }}</td><td>{{ row.processCostMs ?? '-' }}</td></tr></tbody></table></section>
        </div>
      </section>

      <section v-show="activeModule === 'device'" class="exact-page">
        <div class="section-heading">
          <div class="heading-title-line"><h1>设备管理</h1><span class="heading-online"><i></i>{{ filteredDevices.length }} 台设备</span></div>
          <div class="heading-actions"><button type="button" @click="loadDevices">刷新列表</button><button type="button" class="primary" @click="openLocalEditor()">新增本地设备</button></div>
        </div>
        <div class="exact-page-body">
          <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><input v-model="deviceKeyword" type="search" placeholder="搜索设备名称、标识或地址" /><select v-model="protocolFilter"><option value="">全部协议</option><option v-for="protocolItem in protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">{{ protocolItem.title || protocolItem.protocol }}</option></select><select v-model="statusFilter"><option value="">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option><option value="ERROR">异常</option></select></div><div class="exact-toolbar-group"><button type="button" @click="syncDevices">同步远端配置</button></div></div>
          <div class="exact-device-list"><div v-if="filteredDevices.length === 0" class="exact-empty">正在加载设备配置...</div><article v-for="device in filteredDevices" :key="deviceIdOf(device)" class="exact-device-card" :class="{ 'is-selected': selectedDeviceId === deviceIdOf(device) }" @click="selectDevice(deviceIdOf(device))"><div><strong>{{ device.deviceName || deviceIdOf(device) }}</strong><span>{{ deviceIdOf(device) }}</span></div><div class="device-card-meta"><span>{{ device.protocolType || device.connectionType || '-' }}</span><span>{{ device.ipAddress || '-' }}:{{ device.port || '-' }}</span><span>{{ device.status || '-' }}</span></div><div class="inline-actions"><button type="button" @click.stop="startSelectedDevice(deviceIdOf(device))">启动</button><button type="button" @click.stop="stopSelectedDevice(deviceIdOf(device))">停止</button><button v-if="device.temporaryConfig || device.configSource === 'local'" type="button" class="danger" @click.stop="deleteLocal(deviceIdOf(device))">删除本地</button></div></article></div>
        </div>
      </section>

      <section v-show="activeModule === 'collect'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>数据采集配置</h1><span class="heading-online"><i></i>{{ protocols.length }} 种协议</span></div><div class="heading-actions"><button type="button" @click="exportConfig">导出配置</button></div></div>
        <div class="exact-page-body"><section class="exact-surface exact-global-config"><div class="exact-surface-head"><h2>全局采集配置</h2><span>当前运行配置</span></div><pre class="json-view">{{ prettyJson(configSummary) }}</pre></section><section class="exact-table-card"><div class="exact-table-title"><h2>协议配置列表</h2><span>协议字段由后端 Schema 统一提供</span></div><table><thead><tr><th>协议名称</th><th>规范编码</th><th>默认端口</th><th>采集方式</th><th>能力状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in protocols" :key="item.protocol"><td>{{ item.title || item.protocol }}</td><td>{{ item.protocol }}</td><td>{{ (item as Record<string, unknown>).defaultPort || '-' }}</td><td>{{ (item as Record<string, unknown>).collectorType || '-' }}</td><td>{{ item.implementationState || (item.implemented ? '已实现' : '未实现') }}</td><td><button type="button" @click="selectedProtocol = item">查看 Schema</button></td></tr></tbody></table></section><section v-if="selectedProtocol" class="exact-surface"><div class="exact-surface-head"><h2>{{ selectedProtocol.title || selectedProtocol.protocol }}</h2><span>{{ selectedProtocol.protocol }}</span></div><pre class="json-view">{{ prettyJson(selectedProtocol) }}</pre></section></div>
      </section>

      <section v-show="activeModule === 'diag'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>系统实时状态诊断</h1><span class="heading-online"><i></i>运行数据</span></div><div class="heading-actions"><button type="button" class="primary" @click="runDiagnostic">运行完整诊断</button></div></div>
        <div class="exact-page-body"><div class="exact-diagnostic-cards"><article v-for="item in diagnosticCards" :key="item.label" class="metric-card"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small></article></div><details class="exact-json-panel" open><summary>查看原始诊断 JSON</summary><pre class="json-view">{{ prettyJson(diagnosticRaw) }}</pre></details></div>
      </section>

      <section v-show="activeModule === 'alarm'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>告警总览</h1><span class="heading-online"><i></i>{{ alarms.length }} 条</span></div><div class="heading-actions"><button type="button" @click="loadAlarms">刷新告警</button></div></div><div class="exact-page-body"><section class="exact-table-card"><table><thead><tr><th>级别</th><th>设备</th><th>点位</th><th>内容</th><th>时间</th><th>状态</th></tr></thead><tbody><tr v-if="alarms.length === 0"><td colspan="6" class="exact-empty">暂无告警</td></tr><tr v-for="alarm in alarms" :key="String(alarm.alarmId || alarm.id || alarm.timestamp)"><td>{{ alarm.level || alarm.alarmType || '-' }}</td><td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td><td>{{ alarm.pointName || alarm.pointCode || '-' }}</td><td>{{ alarm.content || alarm.message || alarm.alarmContent || '-' }}</td><td>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</td><td>{{ alarm.status || (alarm.acknowledged ? '已确认' : '未确认') }}</td></tr></tbody></table></section></div></section>

      <section v-show="activeModule === 'log'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>日志</h1><span class="heading-online"><i></i>运行日志</span></div><div class="heading-actions"><button type="button" @click="loadLogs">刷新</button></div></div><div class="exact-page-body"><div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="logLevel"><option value="">全部级别</option><option>INFO</option><option>WARN</option><option>ERROR</option><option>DEBUG</option></select><input v-model="logKeyword" type="search" placeholder="搜索日志内容" /><input v-model.number="logLimit" type="number" min="20" step="20" /></div></div><section class="exact-table-card"><table><thead><tr><th>时间</th><th>级别</th><th>来源</th><th>设备</th><th>内容</th></tr></thead><tbody><tr v-if="filteredLogs.length === 0"><td colspan="5" class="exact-empty">暂无日志</td></tr><tr v-for="(log, index) in filteredLogs" :key="index"><td>{{ formatTime(log.timestamp || log.time) }}</td><td>{{ log.level || '-' }}</td><td>{{ log.logger || '-' }}</td><td>{{ log.deviceName || log.deviceId || '-' }}</td><td>{{ log.message || log.content || '-' }}</td></tr></tbody></table></section></div></section>

      <section v-show="activeModule === 'network'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>网络检测</h1><span class="heading-online"><i></i>连通性检测</span></div><div class="heading-actions"><button type="button" class="primary" @click="runNetwork">开始检测</button></div></div><div class="exact-page-body"><div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="networkDeviceId" @change="applyNetworkDevice"><option value="">手动输入目标</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="networkTarget" type="text" placeholder="目标主机 IP" /><input v-model.number="networkPort" type="number" placeholder="目标端口" /><input v-model.number="networkTimeout" type="number" placeholder="超时 ms" /><input v-model.number="networkRetries" type="number" placeholder="重试" /></div></div><section class="exact-surface"><div class="exact-surface-head"><h2>检测结果</h2><span>{{ networkTarget }}:{{ networkPort }}</span></div><pre class="json-view">{{ prettyJson(networkResult) }}</pre></section></div></section>

      <section v-show="activeModule === 'cloud'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>云平台配置</h1><span class="heading-online"><i></i>可靠上报链路</span></div><div class="heading-actions"><button type="button" @click="loadOverview">刷新链路</button></div></div><div class="exact-page-body"><section class="exact-surface"><div class="exact-surface-head"><h2>云上报链路</h2><span>{{ reportState }}</span></div><pre class="json-view">{{ prettyJson(reportMetrics) }}</pre></section></div></section>

      <section v-show="activeModule === 'workbench'" class="workspace-grid">
        <div class="workspace-column workspace-left"><section class="panel device-panel"><div class="panel-head"><div class="panel-head-main"><span class="panel-kicker">主工作流</span><h2>设备列表</h2></div><div class="inline-actions local-device-actions"><button type="button" class="primary" @click="openLocalEditor()">新建设备</button></div></div><div class="device-resource-list"><button v-for="device in devices" :key="deviceIdOf(device)" type="button" :class="{ 'is-active': selectedDeviceId === deviceIdOf(device) }" @click="selectDevice(deviceIdOf(device))"><strong>{{ device.deviceName || deviceIdOf(device) }}</strong><span>{{ device.protocolType || device.connectionType || '-' }}</span></button></div></section></div>
        <div class="workspace-column workspace-center"><section class="panel workbench-panel"><div class="workbench-topbar workbench-topbar-compact"><div class="device-summary"><div class="summary-primary"><span class="status-dot online"></span><strong>{{ selectedDevice?.deviceName || '请选择设备' }}</strong><span class="summary-status">{{ selectedDevice?.status || '待连接' }}</span></div><div class="summary-meta"><span>协议：<strong>{{ selectedDevice?.protocolType || '-' }}</strong></span><span>地址：<strong>{{ selectedDevice?.ipAddress || '-' }}:{{ selectedDevice?.port || '-' }}</strong></span><span>采集周期：<strong>{{ selectedDevice?.collectionInterval || '-' }}</strong></span></div></div><div class="workbench-tabs"><button type="button" :class="{ 'is-active': workbenchTab === 'points' }" @click="workbenchTab = 'points'">点位工作台</button><button type="button" :class="{ 'is-active': workbenchTab === 'protocol' }" @click="workbenchTab = 'protocol'">协议连接</button><button type="button" :class="{ 'is-active': workbenchTab === 'control' }" @click="workbenchTab = 'control'">手动控制</button><button type="button" :class="{ 'is-active': workbenchTab === 'shadow' }" @click="workbenchTab = 'shadow'">设备影子</button></div></div><div v-if="workbenchTab === 'points'" class="runtime-stage-shell"><div class="workbench-toolbar workbench-toolbar-compact"><div class="inline-actions workbench-primary-actions"><button type="button" class="primary" @click="loadSelectedRealtime">自动刷新</button><button type="button" @click="resetSelectedAdaptive">重置自适应</button></div></div><section class="console-module realtime-stage"><div class="table-wrap point-runtime-wrap"><table class="runtime-table realtime-table"><thead><tr><th>点位名称</th><th>编码</th><th>地址</th><th>类型</th><th>值</th><th>质量</th><th>时间</th></tr></thead><tbody><tr v-if="selectedRealtimeRows.length === 0"><td colspan="7">选择设备后查看当前点位快照</td></tr><tr v-for="row in selectedRealtimeRows" :key="String(row.pointId || row.pointCode)"><td>{{ row.pointName || '-' }}</td><td>{{ row.pointCode || '-' }}</td><td>{{ row.address || '-' }}</td><td>{{ row.dataType || '-' }}</td><td>{{ row.value ?? row.currentValue ?? '-' }}</td><td>{{ row.quality || '-' }}</td><td>{{ formatTime(row.timestamp || row.collectTime) }}</td></tr></tbody></table></div></section></div><div v-else-if="workbenchTab === 'protocol'" class="surface-card"><h3>协议连接</h3><pre class="json-view">{{ prettyJson(selectedDevice) }}</pre></div><ManualShadowPanels v-else :tab="workbenchTab" :device-id="selectedDeviceId" /></section></div>
      </section>
    </main>

    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocols" @saved="handleLocalSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import LocalDeviceEditor from "@/components/device/LocalDeviceEditor.vue";
import ManualShadowPanels from "./LegacyManualShadowPanels.vue";
import { deleteLocalDevice, exportConfigs, getConfigDevices as getConfigDeviceList, getConfigSummary, getDevicePointsConfig, triggerFullConfigSync } from "@/api/config.api";
import { getAllDeviceStatistics, getDeviceRuntime, reloadDevices, startDevice, stopDevice } from "@/api/device.api";
import { getCloudReportMetrics, getRuntimeStatus, getSystemResources } from "@/api/monitor.api";
import { getDeviceRealtimeData, getRecentAlarms, normalizeAlarmRows, resetAdaptiveConfig } from "@/api/data.api";
import { listProtocols } from "@/api/protocol.api";
import { diagnoseNetwork, getOpsLogs, normalizeLogRows } from "@/api/ops.api";
import { useAppStore } from "@/stores/app.store";
import type { DeviceInfo } from "@/types/device";
import type { AlarmRow, LogRow, RealtimePointRow } from "@/types/monitor";
import type { ProtocolSchema } from "@/types/protocol";

type ModuleKey = "overview" | "realtime" | "alarm" | "device" | "collect" | "cloud" | "diag" | "log" | "network" | "workbench";

const navGroups: Array<{ title: string; items: Array<{ key: ModuleKey; label: string; icon: string }> }> = [
  { title: "运行", items: [{ key: "overview", label: "概览", icon: "览" }, { key: "realtime", label: "实时数据", icon: "时" }, { key: "alarm", label: "告警总览", icon: "警" }] },
  { title: "配置", items: [{ key: "device", label: "设备管理", icon: "设" }, { key: "collect", label: "采集配置", icon: "采" }, { key: "cloud", label: "云平台配置", icon: "云" }, { key: "workbench", label: "设备工作台", icon: "台" }] },
  { title: "诊断", items: [{ key: "diag", label: "系统诊断", icon: "诊" }, { key: "log", label: "日志", icon: "志" }, { key: "network", label: "网络检测", icon: "网" }] }
];

const appStore = useAppStore();
const route = useRoute();
const router = useRouter();
const activeModule = ref<ModuleKey>("overview");
const tokenInput = ref("");
const liveClock = ref("--:--:--");
const lastRefresh = ref<Date | null>(null);
const devices = ref<DeviceInfo[]>([]);
const protocols = ref<ProtocolSchema[]>([]);
const runtimeStatus = ref<unknown>({});
const systemResource = ref<unknown>({});
const reportMetrics = ref<unknown>({});
const deviceStats = ref<unknown>({});
const configSummary = ref<unknown>({});
const alarms = ref<AlarmRow[]>([]);
const logs = ref<LogRow[]>([]);
const realtimeRows = ref<RealtimePointRow[]>([]);
const selectedRealtimeRows = ref<RealtimePointRow[]>([]);
const selectedDeviceId = ref("");
const realtimeDeviceId = ref("");
const realtimeKeyword = ref("");
const realtimeAuto = ref(true);
const deviceKeyword = ref("");
const protocolFilter = ref("");
const statusFilter = ref("");
const selectedProtocol = ref<ProtocolSchema | null>(null);
const logLevel = ref("");
const logKeyword = ref("");
const logLimit = ref(100);
const networkDeviceId = ref("");
const networkTarget = ref("127.0.0.1");
const networkPort = ref(9090);
const networkTimeout = ref(3000);
const networkRetries = ref(1);
const networkResult = ref<unknown>({});
const diagnosticRaw = ref<unknown>({});
const localEditorVisible = ref(false);
const editingBundle = ref(null);
const workbenchTab = ref("points");
let clockTimer = 0;
let realtimeTimer = 0;

const nodeIdentity = computed(() => appStore.platform === "browser" ? "本地浏览器" : `Electron/${appStore.platform}`);
const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const systemStatusClass = computed(() => appStore.initialized ? "is-online" : "is-unknown");
const onlineCount = computed(() => devices.value.filter((device) => String(device.status || "").toUpperCase() === "ONLINE").length);
const riskDevices = computed(() => devices.value.filter((device) => ["ERROR", "OFFLINE"].includes(String(device.status || "").toUpperCase()) || Boolean(device.lastError)).slice(0, 6));
const reportState = computed(() => Object.keys(asRecord(reportMetrics.value)).length ? "已加载" : "未知");
const runtimeState = computed(() => Object.keys(asRecord(runtimeStatus.value)).length ? "资源已加载" : "资源未知");
const pipelineSteps = ["采集器", "边缘网关", "Redis 缓存", "TDengine", "云平台"];
const lastRefreshText = computed(() => lastRefresh.value ? `刷新于 ${lastRefresh.value.toLocaleTimeString()}` : "等待刷新");
const selectedDevice = computed(() => devices.value.find((device) => deviceIdOf(device) === selectedDeviceId.value));
const filteredDevices = computed(() => {
  const keyword = deviceKeyword.value.trim().toLowerCase();
  return devices.value.filter((device) => {
    const protocol = String(device.protocolType || device.connectionType || "");
    const status = String(device.status || "");
    const text = [device.deviceName, deviceIdOf(device), device.ipAddress, protocol, status].join(" ").toLowerCase();
    return (!keyword || text.includes(keyword)) && (!protocolFilter.value || protocol === protocolFilter.value) && (!statusFilter.value || status === statusFilter.value);
  });
});
const filteredRealtimeRows = computed(() => {
  const keyword = realtimeKeyword.value.trim().toLowerCase();
  return realtimeRows.value.filter((row) => !keyword || [row.pointName, row.pointCode, row.address, row.deviceName].join(" ").toLowerCase().includes(keyword));
});
const filteredLogs = computed(() => logs.value.filter((log) => {
  const matchesLevel = !logLevel.value || String(log.level || "").toUpperCase() === logLevel.value;
  const text = [log.message, log.content, log.logger, log.deviceId, log.deviceName].join(" ").toLowerCase();
  return matchesLevel && (!logKeyword.value || text.includes(logKeyword.value.toLowerCase()));
}));
const overviewCards = computed(() => [
  { label: "采集器总数", value: valueOf(deviceStats.value, ["collectorCount", "totalCollectors", "total", "deviceCount"], devices.value.length), note: "配置设备" },
  { label: "已连接", value: onlineCount.value, note: "在线设备" },
  { label: "未连接", value: Math.max(0, devices.value.length - onlineCount.value), note: "离线/未知" },
  { label: "点位总数", value: valueOf(deviceStats.value, ["pointCount", "totalPoints"], sumPoints(devices.value)), note: "采集点位" },
  { label: "连接配置", value: devices.value.length, note: "设备连接" },
  { label: "上报属性", value: valueOf(reportMetrics.value, ["reportFieldCount", "reportedProperties"], "-"), note: "云端属性" },
  { label: "全局告警", value: alarms.value.length, note: "最近告警" },
  { label: "运行设备", value: onlineCount.value, note: "运行中" }
]);
const diagnosticCards = computed(() => [
  { label: "运行状态", value: systemStatusText.value, note: "服务状态" },
  { label: "设备数量", value: devices.value.length, note: "配置设备" },
  { label: "协议数量", value: protocols.value.length, note: "Schema" },
  { label: "告警数量", value: alarms.value.length, note: "最近记录" }
]);

onMounted(async () => {
  syncModuleFromRoute();
  await appStore.initialize();
  tokenInput.value = appStore.token;
  tickClock();
  clockTimer = window.setInterval(tickClock, 1000);
  realtimeTimer = window.setInterval(() => {
    if (realtimeAuto.value && activeModule.value === "realtime") {
      void loadRealtime();
    }
  }, 5000);
  await refreshAll();
});

onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(realtimeTimer);
});

function tickClock() {
  liveClock.value = new Date().toLocaleTimeString();
}

function switchModule(module: ModuleKey) {
  activeModule.value = module;
  const targetPath = routePathByModule(module);
  if (route.path !== targetPath) {
    router.push(targetPath).catch(() => undefined);
  }
  if (module === "realtime") void loadRealtime();
  if (module === "alarm") void loadAlarms();
  if (module === "log") void loadLogs();
  if (module === "diag") void runDiagnostic();
}

watch(() => route.path, syncModuleFromRoute);

function syncModuleFromRoute() {
  const module = moduleByRoutePath(route.path);
  if (module === "control" || module === "shadow") {
    activeModule.value = "workbench";
    workbenchTab.value = module;
    return;
  }
  activeModule.value = module;
}

function moduleByRoutePath(path: string): ModuleKey | "control" | "shadow" {
  const normalized = path.replace(/^\//, "") || "dashboard";
  const mapping: Record<string, ModuleKey | "control" | "shadow"> = {
    dashboard: "overview",
    realtime: "realtime",
    history: "workbench",
    alarm: "alarm",
    device: "device",
    collect: "collect",
    cloud: "cloud",
    diagnostic: "diag",
    log: "log",
    network: "network",
    control: "control",
    shadow: "shadow"
  };
  return mapping[normalized] || "overview";
}

function routePathByModule(module: ModuleKey): string {
  const mapping: Record<ModuleKey, string> = {
    overview: "/dashboard",
    realtime: "/realtime",
    alarm: "/alarm",
    device: "/device",
    collect: "/collect",
    cloud: "/cloud",
    diag: "/diagnostic",
    log: "/log",
    network: "/network",
    workbench: "/device"
  };
  return mapping[module] || "/dashboard";
}

function saveToken() {
  appStore.setToken(tokenInput.value, true);
  ElMessage.success("令牌已保存");
}

async function refreshAll() {
  await Promise.allSettled([loadProtocols(), loadDevices(), loadOverview(), loadAlarms(), loadLogs(), runDiagnostic()]);
  lastRefresh.value = new Date();
}

async function loadProtocols() {
  protocols.value = await listProtocols();
}

async function loadDevices() {
  const response = await getConfigDeviceList();
  devices.value = extractArray<DeviceInfo>(response, ["devices", "data", "items", "records"]);
  if (!selectedDeviceId.value && devices.value.length) selectedDeviceId.value = deviceIdOf(devices.value[0]);
}

async function loadOverview() {
  const [stats, runtime, resource, report, summary] = await Promise.allSettled([getAllDeviceStatistics(), getRuntimeStatus(), getSystemResources(), getCloudReportMetrics(), getConfigSummary()]);
  if (stats.status === "fulfilled") deviceStats.value = stats.value;
  if (runtime.status === "fulfilled") runtimeStatus.value = runtime.value;
  if (resource.status === "fulfilled") systemResource.value = resource.value;
  if (report.status === "fulfilled") reportMetrics.value = report.value;
  if (summary.status === "fulfilled") configSummary.value = summary.value;
}

async function loadAlarms() {
  try { alarms.value = normalizeAlarmRows(await getRecentAlarms({ limit: 50 })); } catch { alarms.value = []; }
}

async function loadLogs() {
  try { logs.value = normalizeLogRows(await getOpsLogs({ level: logLevel.value || undefined, keyword: logKeyword.value || undefined, limit: logLimit.value })); } catch { logs.value = []; }
}

async function loadRealtime() {
  if (!realtimeDeviceId.value) { realtimeRows.value = []; return; }
  const response = await getDeviceRealtimeData(realtimeDeviceId.value);
  realtimeRows.value = extractArray<RealtimePointRow>(response, ["points", "data", "values", "rows", "items"]);
}

async function loadSelectedRealtime() {
  if (!selectedDeviceId.value) return;
  const response = await getDeviceRealtimeData(selectedDeviceId.value);
  selectedRealtimeRows.value = extractArray<RealtimePointRow>(response, ["points", "data", "values", "rows", "items"]);
}

async function resetSelectedAdaptive() {
  if (!selectedDeviceId.value) return;
  await resetAdaptiveConfig(selectedDeviceId.value);
  ElMessage.success("已重置自适应采集参数");
  await loadSelectedRealtime();
}

async function syncDevices() {
  await triggerFullConfigSync();
  await reloadDevices();
  await loadDevices();
  ElMessage.success("已触发远端配置同步");
}

async function startSelectedDevice(deviceId: string) { await startDevice(deviceId); await loadDevices(); }
async function stopSelectedDevice(deviceId: string) { await stopDevice(deviceId); await loadDevices(); }
async function deleteLocal(deviceId: string) { if (!window.confirm(`确认删除本地临时设备 ${deviceId}？该操作不会删除远端配置。`)) return; await deleteLocalDevice(deviceId); await loadDevices(); }

function selectDevice(deviceId: string) { selectedDeviceId.value = deviceId; void loadSelectedRealtime(); }
function openLocalEditor() { editingBundle.value = null; localEditorVisible.value = true; }
async function handleLocalSaved() { localEditorVisible.value = false; await loadDevices(); }
async function exportConfig() { await exportConfigs(); ElMessage.success("已请求导出配置"); }
async function runDiagnostic() { diagnosticRaw.value = { runtime: runtimeStatus.value, system: systemResource.value, report: reportMetrics.value }; await loadOverview(); diagnosticRaw.value = { runtime: runtimeStatus.value, system: systemResource.value, report: reportMetrics.value }; }

function applyNetworkDevice() { const device = devices.value.find((item) => deviceIdOf(item) === networkDeviceId.value); if (!device) return; networkTarget.value = String(device.ipAddress || networkTarget.value); networkPort.value = Number(device.port || networkPort.value); }
async function runNetwork() { networkResult.value = await diagnoseNetwork({ type: "TCP", target: networkTarget.value, port: networkPort.value, timeoutMs: networkTimeout.value, retries: networkRetries.value }); }

function deviceIdOf(device: DeviceInfo): string { return String(device.deviceId || device.id || ""); }
function deviceNameOf(deviceId: string): string { return devices.value.find((device) => deviceIdOf(device) === deviceId)?.deviceName || deviceId; }
function sumPoints(source: DeviceInfo[]): number { return source.reduce((sum, device) => sum + Number(device.pointCount || (Array.isArray(device.points) ? device.points.length : 0) || 0), 0); }
function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function extractArray<T>(value: unknown, keys: string[]): T[] { if (Array.isArray(value)) return value as T[]; const record = asRecord(value); for (const key of keys) if (Array.isArray(record[key])) return record[key] as T[]; return []; }
function valueOf(value: unknown, keys: string[], fallback: unknown): unknown { const record = asRecord(value); for (const key of keys) if (record[key] !== undefined && record[key] !== null) return record[key]; return fallback; }
function prettyJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
function compactJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
function formatTime(value: unknown): string { if (!value) return "-"; const date = typeof value === "number" ? new Date(value) : new Date(String(value)); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(); }
</script>
