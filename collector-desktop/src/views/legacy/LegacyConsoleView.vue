<template>
  <div class="shell legacy-console theme-anchor modao-exact">
    <aside class="sidebar">
      <div class="sidebar-top">
        <div class="brand">
          <span class="brand-mark"><img :src="factoryIcon" alt=""></span>
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
              <span class="nav-glyph" aria-hidden="true"><img :src="item.icon" alt=""></span><span>{{ item.label }}</span>
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
          <article v-for="card in overviewCards" :key="card.label" class="card metric-card">
            <small>{{ card.label }}</small>
            <div v-if="card.ring" class="cache-ring" aria-hidden="true"></div>
            <strong>{{ card.value }}</strong>
            <div v-if="card.meta" class="card-meta">
              <span v-for="item in card.meta" :key="String(item[0])">{{ item[0] }} {{ item[1] }}</span>
            </div>
            <div v-else class="card-subtext">{{ card.subtext }}</div>
          </article>
        </div>
        <div class="home-dashboard">
          <div class="home-dashboard-row home-dashboard-primary">
            <section class="home-panel home-panel-large">
              <div class="home-panel-head"><div><h2>全局告警最近记录</h2></div><span class="home-panel-badge">{{ alarms.length ? `${alarms.length} 条` : '数据不可用' }}</span></div>
              <div class="home-event-list">
                <div v-if="alarms.length === 0" class="empty-state compact">暂无告警记录</div>
                <div v-for="alarm in alarms.slice(0, 8)" :key="String(alarm.alarmId || alarm.id || alarm.timestamp)" class="home-event-row" :class="alarmToneClass(alarm)">
                  <div class="home-event-main">
                    <strong>{{ alarmMessage(alarm) }}</strong>
                    <span>{{ alarm.deviceName || alarm.deviceId || '-' }} / {{ alarm.pointName || alarm.pointCode || '-' }}</span>
                  </div>
                  <div class="home-event-meta">
                    <b>{{ alarmLevelText(alarm.level || alarm.alarmType) }}</b>
                    <span>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</span>
                  </div>
                </div>
              </div>
            </section>
            <section class="home-panel">
              <div class="home-panel-head"><div><h2>设备异常风险</h2></div><span class="home-panel-badge">{{ riskDevices.length ? `${riskDevices.length} 台风险` : '正常' }}</span></div>
              <div class="home-risk-list">
                <div v-if="riskDevices.length === 0" class="empty-state compact">当前没有明显设备风险</div>
                <div v-for="device in riskDevices" :key="deviceIdOf(device)" class="home-risk-row" :class="riskToneClass(device)"><span class="risk-dot"></span><div><strong>{{ deviceIdOf(device) || device.deviceName }}</strong><p>{{ riskDescription(device) }}</p></div></div>
              </div>
            </section>
          </div>
          <div class="home-dashboard-row home-dashboard-observability">
            <section class="home-panel home-panel-report">
              <div class="home-panel-head"><div><h2>数据上报链路拓扑</h2></div><span class="home-panel-badge">{{ reportState }}</span></div>
              <div class="pipeline-steps">
                <div class="topology-flow" aria-label="数据采集与上报拓扑">
                  <div class="topology-node" :class="collectorToneClass" :title="collectorDetail">
                    <span class="topology-icon">采</span>
                    <strong>采集器</strong>
                    <span class="topology-status-dots"><i :class="runningToneClass"></i><i :class="collectorToneClass"></i></span>
                  </div>
                  <span class="topology-connector" aria-hidden="true"></span>
                  <div class="topology-node is-gateway" :class="gatewayToneClass" :title="gatewayDetail">
                    <span class="topology-icon">网</span>
                    <strong>边缘网关</strong>
                    <small>{{ nodeIdentity }}</small>
                  </div>
                  <span class="topology-connector" aria-hidden="true"></span>
                  <div class="topology-storage-stack">
                    <div class="topology-storage-pill" title="Redis 缓存状态">
                      <span>Redis 缓存</span><i class="status-dot" :class="cacheToneClass"></i>
                    </div>
                    <div class="topology-storage-pill" title="TDengine 历史存储状态">
                      <span>TDengine</span><i class="status-dot" :class="storageToneClass"></i>
                    </div>
                  </div>
                  <span class="topology-connector" aria-hidden="true"></span>
                  <div class="topology-node" :class="cloudToneClass" :title="String(reportState)">
                    <span class="topology-icon">云</span>
                    <strong>云平台</strong>
                    <small>{{ reportState }}</small>
                  </div>
                </div>
              </div>
            </section>
            <section class="home-panel home-panel-runtime">
              <div class="home-panel-head"><div><h2>系统资源与线程池</h2></div><span class="home-panel-badge">{{ runtimeState }}</span></div>
              <div class="home-resource-list">
                <div class="resource-dashboard">
                  <div class="resource-gauges">
                    <div v-for="gauge in resourceGauges" :key="gauge.label" class="resource-gauge">
                      <div class="resource-ring" :class="`is-${gauge.tone}`" :style="{ '--resource-progress': `${gauge.degrees}deg` }"></div>
                      <span>{{ gauge.label }}</span>
                      <strong>{{ gauge.value }}</strong>
                    </div>
                  </div>
                  <div class="resource-runtime-summary" :title="resourceSummary.title">
                    <div><span>活跃线程:</span><strong>{{ resourceSummary.activeThreads }} / {{ resourceSummary.maxThreads }}</strong></div>
                    <div><span>队列积压:</span><strong :class="{ 'is-warn': resourceSummary.queuedTasks !== '-' && Number(resourceSummary.queuedTasks) > 0 }">{{ resourceSummary.queuedTasks }}</strong></div>
                    <div class="resource-load-track"><i :style="{ width: resourceSummary.threadUsage }"></i></div>
                  </div>
                </div>
              </div>
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
          <section class="exact-table-card"><table><thead><tr><th>点位名称</th><th>设备名称</th><th>数据类型</th><th>寄存器地址</th><th>读写</th><th>缩放</th><th>当前值</th><th>单位</th><th>采集时间</th><th>质量</th><th>处理耗时</th></tr></thead><tbody><tr v-if="filteredRealtimeRows.length === 0"><td colspan="11" class="exact-empty">请选择设备后查看实时数据</td></tr><tr v-for="row in filteredRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode}`"><td>{{ row.pointName || row.pointCode || '-' }}</td><td>{{ row.deviceName || deviceNameOf(String(row.deviceId || realtimeDeviceId)) }}</td><td>{{ row.dataType || '-' }}</td><td><code>{{ realtimeAddress(row) }}</code></td><td>{{ row.readWrite || '-' }}</td><td>{{ realtimeScale(row) }}</td><td><strong>{{ realtimeValueText(row) }}</strong></td><td>{{ row.unit || '-' }}</td><td>{{ formatTime(row.timestamp || row.collectTime) }}</td><td><span class="quality-badge" :class="realtimeQualityClass(row)">{{ realtimeQualityText(row) }}</span></td><td>{{ realtimeProcessingText(row) }}</td></tr></tbody></table></section>
        </div>
      </section>

      <section v-show="activeModule === 'device'" class="exact-page">
        <div class="section-heading">
          <div class="heading-title-line"><h1>设备管理</h1><span class="heading-online"><i></i>{{ filteredDevices.length }} 台设备</span></div>
          <div class="heading-actions"><button type="button" @click="loadDevices">刷新列表</button><button type="button" class="primary" @click="openLocalEditor()">新增本地设备</button></div>
        </div>
        <div class="exact-page-body">
          <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><input v-model="deviceKeyword" type="search" placeholder="搜索设备名称、标识或地址" /><select v-model="protocolFilter"><option value="">全部协议</option><option v-for="protocolItem in protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">{{ protocolItem.title || protocolItem.protocol }}</option></select><select v-model="statusFilter"><option value="">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option><option value="ERROR">异常</option></select></div><div class="exact-toolbar-group"><button type="button" @click="syncDevices">同步远端配置</button></div></div>
          <div class="exact-device-list">
            <div v-if="filteredDevices.length === 0" class="exact-empty">正在加载设备配置...</div>
            <article v-for="device in filteredDevices" :key="deviceIdOf(device)" class="exact-device-card" :class="{ 'is-selected': selectedDeviceId === deviceIdOf(device) }" @click="selectDevice(deviceIdOf(device))">
              <div class="exact-device-main">
                <h3>{{ device.deviceName || deviceIdOf(device) }}</h3>
                <p>{{ deviceIdOf(device) }} · {{ isLocalDevice(device) ? '本地临时' : '远端同步' }}</p>
              </div>
              <div class="exact-device-meta">
                <strong>{{ device.protocolType || device.connectionType || '-' }}</strong>
                <span>连接地址 {{ deviceAddress(device) }}</span>
              </div>
              <div class="exact-device-meta">
                <span class="status-badge" :class="statusBadgeClass(device)">{{ localizeDeviceStatus(device.status) }}</span>
                <span>采集周期 {{ device.collectionInterval ?? '-' }} ms</span>
              </div>
              <div class="exact-device-actions">
                <button type="button" @click.stop="startSelectedDevice(deviceIdOf(device))">启动</button><button type="button" @click.stop="stopSelectedDevice(deviceIdOf(device))">停止</button>
                <button type="button" @click.stop="editDevice(device)">编辑</button><button type="button" @click.stop="openDeviceDiff(device)">差异</button>
                <button type="button" @click.stop="openDeviceOperation(device, 'control')">控制</button><button type="button" @click.stop="openDeviceOperation(device, 'shadow')">影子</button>
                <button v-if="isLocalDevice(device)" type="button" class="danger" @click.stop="deleteLocal(deviceIdOf(device))">删除本地</button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section v-show="activeModule === 'collect'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>数据采集配置</h1><span class="heading-online"><i></i>{{ protocols.length }} 种协议</span></div><div class="heading-actions"><button type="button" @click="exportConfig">导出配置</button></div></div>
        <div class="exact-page-body">
          <section class="exact-surface exact-global-config">
            <div class="exact-surface-head"><h2>全局采集配置</h2><span>当前运行配置</span></div>
            <div class="exact-config-grid">
              <div v-for="item in collectionSummaryItems" :key="item.label" class="exact-config-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
            </div>
          </section>
          <section class="exact-table-card">
            <div class="exact-table-title"><h2>协议配置列表</h2><span>{{ protocols.length }} 种协议</span></div>
            <table><thead><tr><th>协议名称</th><th>规范编码</th><th>默认端口</th><th>采集方式</th><th>能力状态</th><th>操作</th></tr></thead><tbody><tr v-if="protocols.length === 0"><td colspan="6" class="exact-empty">当前没有可用的协议定义</td></tr><tr v-for="item in protocols" :key="item.protocol"><td><strong>{{ item.title || item.protocol || '-' }}</strong></td><td><code>{{ item.protocol || '-' }}</code></td><td>{{ protocolDefaultPort(item) }}</td><td>{{ protocolMode(item) }}</td><td><span class="capability-badge">{{ protocolCapability(item) }}</span></td><td><button type="button" @click="openProtocolConfig(item)">配置设备</button></td></tr></tbody></table>
          </section>
          <section v-if="selectedProtocol" class="exact-json-panel" open>
            <summary>{{ selectedProtocol.title || selectedProtocol.protocol }} Schema</summary>
            <pre class="json-view">{{ prettyJson(selectedProtocol) }}</pre>
          </section>
        </div>
      </section>

      <section v-show="activeModule === 'diag'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>系统实时状态诊断</h1><span class="heading-online"><i></i>运行数据</span></div><div class="heading-actions"><button type="button" class="primary" @click="runDiagnostic">运行完整诊断</button></div></div>
        <div class="exact-page-body">
          <div class="exact-diagnostic-cards"><div v-for="item in diagnosticCards" :key="item.label" class="exact-diagnostic-card"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
          <section class="exact-table-card"><table><thead><tr><th>诊断项</th><th>状态</th><th>当前值</th><th>处理建议</th></tr></thead><tbody><tr v-for="row in diagnosticRows" :key="row.name"><td>{{ row.name }}</td><td><span class="status-badge" :class="row.tone">{{ row.status }}</span></td><td>{{ row.current }}</td><td>{{ row.suggestion }}</td></tr></tbody></table></section>
          <details class="exact-json-panel" open><summary>查看原始诊断 JSON</summary><pre class="json-view">{{ prettyJson(diagnosticRaw) }}</pre></details>
        </div>
      </section>

      <section v-show="activeModule === 'alarm'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>告警总览</h1><span class="heading-online"><i></i>{{ alarms.length }} 条</span></div><div class="heading-actions"><button type="button" @click="loadAlarms">刷新告警</button></div></div><div class="exact-page-body"><section class="exact-table-card"><table><thead><tr><th>级别</th><th>设备</th><th>点位</th><th>内容</th><th>时间</th><th>状态</th></tr></thead><tbody><tr v-if="alarms.length === 0"><td colspan="6" class="exact-empty">暂无告警</td></tr><tr v-for="alarm in alarms" :key="String(alarm.alarmId || alarm.id || alarm.timestamp)"><td>{{ alarm.level || alarm.alarmType || '-' }}</td><td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td><td>{{ alarm.pointName || alarm.pointCode || '-' }}</td><td>{{ alarm.content || alarm.message || alarm.alarmContent || '-' }}</td><td>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</td><td>{{ alarm.status || (alarm.acknowledged ? '已确认' : '未确认') }}</td></tr></tbody></table></section></div></section>

      <section v-show="activeModule === 'log'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>日志</h1><span class="heading-online"><i></i>{{ filteredLogs.length }} 条</span></div><div class="heading-actions"><button type="button" @click="loadLogs">刷新</button></div></div><div class="exact-page-body"><div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="logLevel"><option value="">全部级别</option><option>INFO</option><option>WARN</option><option>ERROR</option><option>DEBUG</option></select><input v-model="logKeyword" type="search" placeholder="搜索日志内容、来源或线程" /><input v-model.number="logLimit" type="number" min="20" step="20" /></div></div><section class="exact-surface modao-log-panel"><div v-if="filteredLogs.length === 0" class="empty-state compact">当前条件下没有可显示日志</div><div v-for="(log, index) in filteredLogs" :key="index" class="modao-log-row"><span class="modao-log-time">{{ formatTime(log.timestamp || log.time) }}</span><strong class="modao-log-level" :class="String(log.level || 'INFO').toUpperCase()">{{ log.level || 'INFO' }}</strong><span class="modao-log-name" :title="String(log.logger || '-')">{{ shortLoggerName(log.logger) }}</span><span class="modao-log-message">{{ log.message || log.content || '-' }}</span></div></section></div></section>

      <section v-show="activeModule === 'network'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>网络检测</h1><span class="heading-online"><i></i>连通性检测</span></div><div class="heading-actions"><button type="button" class="primary" @click="runNetwork">开始检测</button></div></div><div class="exact-page-body"><div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="networkDeviceId" @change="applyNetworkDevice"><option value="">手动输入目标</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="networkTarget" type="text" placeholder="目标主机 IP" /><input v-model.number="networkPort" type="number" placeholder="目标端口" /><input v-model.number="networkTimeout" type="number" placeholder="超时 ms" /><input v-model.number="networkRetries" type="number" placeholder="重试" /></div></div><section class="exact-surface network-result-panel"><div class="exact-surface-head"><h2>检测结果</h2><span>{{ networkTarget }}:{{ networkPort }}</span></div><div class="network-result-grid"><div v-for="row in networkResultRows" :key="row.label" class="exact-config-item"><span>{{ row.label }}</span><strong>{{ row.value }}</strong></div></div><pre class="json-view">{{ prettyJson(networkResult) }}</pre></section></div></section>

      <section v-show="activeModule === 'cloud'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>云平台配置</h1><span class="heading-online"><i></i>可靠上报链路</span></div><div class="heading-actions"><button type="button" @click="loadOverview">刷新链路</button></div></div><div class="exact-page-body"><div class="exact-cloud-grid"><section class="exact-surface exact-cloud-status"><div class="exact-cloud-icon">云</div><strong>{{ cloudStatusTextValue }}</strong><small>{{ cloudEnabledText }}</small><div class="cloud-stat-row"><span v-for="item in cloudSummaryCards" :key="item.label"><b>{{ item.value }}</b>{{ item.label }}</span></div></section><section class="exact-surface"><div class="exact-surface-head"><h2>上报策略</h2><span>{{ reportState }}</span></div><div class="modao-property-grid"><div v-for="item in cloudStrategyRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div></section></div><section class="exact-surface"><div class="exact-surface-head"><h2>链路风险</h2><span>{{ cloudRisks.length }} 项</span></div><div class="modao-risk-list"><div v-for="risk in cloudRisks" :key="risk" class="modao-risk-item"><strong>{{ cloudRisks.length ? '风险' : '检查结果' }}</strong><small>{{ risk }}</small></div></div></section><details class="exact-json-panel"><summary>查看上报链路 JSON</summary><pre class="json-view">{{ prettyJson(reportMetrics) }}</pre></details></div></section>

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
import alertCircleIcon from "@/assets/legacy-icons/alert-circle.svg";
import chartTimelineIcon from "@/assets/legacy-icons/chart-timeline-variant.svg";
import cloudUploadIcon from "@/assets/legacy-icons/cloud-upload.svg";
import databaseCogIcon from "@/assets/legacy-icons/database-cog.svg";
import factoryIcon from "@/assets/legacy-icons/factory.svg";
import fileDocumentIcon from "@/assets/legacy-icons/file-document-outline.svg";
import monitorDashboardIcon from "@/assets/legacy-icons/monitor-dashboard.svg";
import networkOutlineIcon from "@/assets/legacy-icons/network-outline.svg";
import routerWirelessIcon from "@/assets/legacy-icons/router-wireless.svg";
import viewDashboardIcon from "@/assets/legacy-icons/view-dashboard.svg";

type ModuleKey = "overview" | "realtime" | "alarm" | "device" | "collect" | "cloud" | "diag" | "log" | "network" | "workbench";

const navGroups: Array<{ title: string; items: Array<{ key: ModuleKey; label: string; icon: string }> }> = [
  { title: "运行", items: [{ key: "overview", label: "概览", icon: viewDashboardIcon }, { key: "realtime", label: "实时数据", icon: chartTimelineIcon }, { key: "alarm", label: "告警总览", icon: alertCircleIcon }] },
  { title: "配置", items: [{ key: "device", label: "设备管理", icon: routerWirelessIcon }, { key: "collect", label: "采集配置", icon: databaseCogIcon }, { key: "cloud", label: "云平台配置", icon: cloudUploadIcon }] },
  { title: "诊断", items: [{ key: "diag", label: "系统诊断", icon: monitorDashboardIcon }, { key: "log", label: "日志", icon: fileDocumentIcon }, { key: "network", label: "网络检测", icon: networkOutlineIcon }] }
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
const overviewCards = computed(() => {
  const totalDevices = valueOf(deviceStats.value, ["collectorCount", "totalCollectors", "total", "deviceCount"], devices.value.length);
  const disconnected = Math.max(0, devices.value.length - onlineCount.value);
  const pointCount = valueOf(deviceStats.value, ["pointCount", "totalPoints"], sumPoints(devices.value));
  const cacheRatio = ratioFrom(valueOf(runtimeStatus.value, ["cacheHitRatio", "hitRatio", "cacheHitRate"], null));
  return [
    { label: "采集器总数", value: totalDevices, meta: [["已连接", onlineCount.value], ["未连接", disconnected]] },
    { label: "点位总数", value: pointCount, meta: [["连接配置", devices.value.length], ["上报属性", valueOf(reportMetrics.value, ["reportFieldCount", "reportedProperties"], "-")]] },
    { label: "全局告警", value: alarms.value.length, subtext: alarms.value.length ? "最近告警记录" : "最近 24 小时没有告警历史记录" },
    { label: "运行设备", value: onlineCount.value, meta: [["缺失连接", disconnected], ["健康连接", onlineCount.value]] },
    { label: "缓存命中率", value: cacheRatio === null ? "-" : percentText(cacheRatio), ring: true, subtext: cacheRatio === null ? "缓存指标不可用" : "缓存访问指标" },
    { label: "云上报链路", value: reportState.value, subtext: Object.keys(asRecord(reportMetrics.value)).length ? "上报状态已加载" : "上报监控数据不可用" }
  ];
});
const collectorToneClass = computed(() => riskDevices.value.some((device) => String(device.status || "").toUpperCase() === "ERROR") ? "is-error" : (riskDevices.value.length ? "is-warn" : (devices.value.length ? "is-ok" : "is-muted")));
const runningToneClass = computed(() => onlineCount.value > 0 ? "is-ok" : "is-muted");
const gatewayToneClass = computed(() => Object.keys(asRecord(runtimeStatus.value)).length ? "is-ok" : "is-muted");
const cacheToneClass = computed(() => ratioFrom(valueOf(runtimeStatus.value, ["cacheHitRatio", "hitRatio", "cacheHitRate"], null)) === null ? "is-muted" : "is-ok");
const storageToneClass = computed(() => Object.keys(asRecord(configSummary.value)).length ? "is-ok" : "is-muted");
const cloudToneClass = computed(() => {
  const status = String(valueOf(reportMetrics.value, ["status", "state"], "UNKNOWN")).toUpperCase();
  if (["ERROR", "FAILED", "DOWN"].includes(status)) return "is-error";
  if (["WARN", "WARNING", "DEGRADED"].includes(status)) return "is-warn";
  return Object.keys(asRecord(reportMetrics.value)).length ? "is-ok" : "is-muted";
});
const collectorDetail = computed(() => `${onlineCount.value}/${devices.value.length} 已连接`);
const gatewayDetail = computed(() => Object.keys(asRecord(runtimeStatus.value)).length ? "处理指标已加载" : "处理性能数据不可用");
const resourceGauges = computed(() => {
  const resource = asRecord(systemResource.value);
  const cpu = ratioFrom(valueOf(resource, ["systemCpuLoad", "cpuLoad", "processCpuLoad"], null));
  const totalMemory = optionalNumber(valueOf(resource, ["totalPhysicalMemorySize", "totalMemory", "memoryTotal"], null));
  const freeMemory = optionalNumber(valueOf(resource, ["freePhysicalMemorySize", "freeMemory", "memoryFree"], null));
  const memory = totalMemory !== null && freeMemory !== null && totalMemory > 0 ? 1 - freeMemory / totalMemory : null;
  const heapUsed = optionalNumber(valueOf(resource, ["heapUsed", "usedHeap", "jvmHeapUsed"], null));
  const heapMax = optionalNumber(valueOf(resource, ["heapMax", "maxHeap", "jvmHeapMax"], null));
  const heap = heapUsed !== null && heapMax !== null && heapMax > 0 ? heapUsed / heapMax : null;
  return [
    { label: "CPU 使用率", tone: "blue", value: percentText(cpu), degrees: ratioDegrees(cpu) },
    { label: "内存使用率", tone: "orange", value: percentText(memory), degrees: ratioDegrees(memory) },
    { label: "JVM 堆内存", tone: "green", value: percentText(heap), degrees: ratioDegrees(heap) }
  ];
});
const resourceSummary = computed(() => {
  const resource = asRecord(systemResource.value);
  const pools = asRecord(resource.threadPools);
  let activeThreads = 0;
  let maxThreads = 0;
  let queuedTasks = 0;
  let rejectedTasks = 0;
  for (const pool of Object.values(pools)) {
    const record = asRecord(pool);
    activeThreads += numberValue(record.activeCount, 0);
    maxThreads += numberValue(record.maxPoolSize, 0);
    queuedTasks += numberValue(record.queueSize, 0);
    rejectedTasks += numberValue(record.rejectedCount, 0);
  }
  const executor = asRecord(asRecord(reportMetrics.value).executor);
  if (maxThreads === 0 && Object.keys(executor).length) {
    activeThreads = numberValue(executor.activeCount, 0);
    maxThreads = numberValue(executor.maxPoolSize, 0);
    queuedTasks = numberValue(executor.queueSize, 0);
    rejectedTasks = numberValue(executor.rejectedCount, 0);
  }
  const usage = maxThreads > 0 ? Math.max(0, Math.min(100, Math.round((activeThreads / maxThreads) * 100))) : 0;
  return {
    activeThreads: maxThreads > 0 ? String(activeThreads) : "-",
    maxThreads: maxThreads > 0 ? String(maxThreads) : "-",
    queuedTasks: maxThreads > 0 ? String(queuedTasks) : "-",
    threadUsage: `${usage}%`,
    title: `累计拒绝 ${rejectedTasks || "-"} 次，JVM 线程 ${valueOf(resource, ["threadCount"], "-")} 个`
  };
});
const collectionSummaryItems = computed(() => {
  const summary = asRecord(configSummary.value);
  const stats = asRecord(summary.cacheStats);
  return [
    { label: "设备配置", value: `${valueOf(stats, ["deviceCount"], valueOf(summary, ["deviceCount"], devices.value.length))} 台` },
    { label: "点位总数", value: `${valueOf(stats, ["pointCount"], valueOf(summary, ["pointCount"], sumPoints(devices.value)))} 个` },
    { label: "连接配置", value: `${valueOf(stats, ["connectionCount"], valueOf(summary, ["connectionCount"], devices.value.length))} 个` },
    { label: "配置来源", value: String(valueOf(summary, ["configSource", "source"], "当前运行配置")) }
  ];
});
const diagnosticCards = computed(() => {
  const resource = asRecord(systemResource.value);
  const summary = asRecord(configSummary.value);
  const stats = asRecord(summary.cacheStats);
  return [
    { label: "系统运行时间", value: formatDurationMs(valueOf(resource, ["uptimeMillis", "uptime"], null)) },
    { label: "设备配置总数", value: `${valueOf(stats, ["deviceCount"], devices.value.length)} 台` },
    { label: "点位总数", value: `${valueOf(stats, ["pointCount"], sumPoints(devices.value))} 个` },
    { label: "活跃连接", value: `${onlineCount.value} 个` }
  ];
});
const diagnosticRows = computed(() => {
  const cacheRate = ratioFrom(valueOf(runtimeStatus.value, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], null));
  const queued = numberValue(resourceSummary.value.queuedTasks === "-" ? 0 : resourceSummary.value.queuedTasks, 0);
  const reportStatus = String(valueOf(reportMetrics.value, ["status", "state"], "UNKNOWN")).toUpperCase();
  const missing = Math.max(0, devices.value.length - onlineCount.value);
  const rows = [
    { name: "应用服务", status: appStore.initialized ? "正常" : "异常", current: systemStatusText.value, suggestion: appStore.initialized ? "无需处理" : "检查应用健康检查明细" },
    { name: "设备连接", status: missing === 0 ? "正常" : "警告", current: `${onlineCount.value}/${devices.value.length}`, suggestion: "检查缺失连接和设备网络" },
    { name: "缓存服务", status: cacheRate === null || cacheRate >= 0.8 ? "正常" : "警告", current: cacheRate === null ? "指标不可用" : percentText(cacheRate), suggestion: "低命中率时检查缓存配置" },
    { name: "线程池拒绝", status: queued === 0 ? "正常" : "异常", current: resourceSummary.value.title, suggestion: "检查队列容量、任务耗时和拒绝策略" },
    { name: "云端上报", status: ["UP", "ONLINE", "OK", "SUCCESS"].includes(reportStatus) ? "正常" : "警告", current: cloudStatusTextValue.value, suggestion: "检查处理器、Outbox 和 ACK 状态" }
  ];
  return rows.map((row) => ({ ...row, tone: row.status === "正常" ? "is-online" : (row.status === "异常" ? "is-error" : "") }));
});
const cloudStatusTextValue = computed(() => cloudStatusText(valueOf(reportMetrics.value, ["status", "state"], "UNKNOWN")));
const cloudEnabledText = computed(() => Boolean(asRecord(reportMetrics.value).enabled) ? "云端上报已启用" : "云端上报未启用");
const cloudSummaryCards = computed(() => {
  const report = asRecord(reportMetrics.value);
  const outbox = asRecord(report.outbox);
  const executor = asRecord(report.executor);
  const ackRuntime = asRecord(report.ackRuntime);
  return [
    { label: "待发送", value: String(valueOf(outbox, ["pendingCount"], valueOf(executor, ["queueSize"], "-"))) },
    { label: "待 ACK", value: String(valueOf(outbox, ["pendingAckCount"], valueOf(ackRuntime, ["pendingCount"], "-"))) },
    { label: "隔离消息", value: String(valueOf(outbox, ["isolatedCount"], "-")) }
  ];
});
const cloudStrategyRows = computed(() => {
  const report = asRecord(reportMetrics.value);
  const configured = asRecord(report.configured);
  const batch = asRecord(report.batch);
  const ack = asRecord(report.ack);
  const outbox = asRecord(report.outbox);
  return [
    { label: "总开关", value: Boolean(report.enabled) ? "已启用" : "未启用" },
    { label: "上报模式", value: String(valueOf(report, ["mode"], "-")) },
    { label: "云服务商", value: String(valueOf(report, ["cloudProvider", "provider"], "-")) },
    { label: "可上报点位", value: `${valueOf(configured, ["reportablePointCount"], 0)} / ${valueOf(configured, ["pointCount"], 0)}` },
    { label: "批量聚合", value: Boolean(batch.enabled) ? `最多 ${valueOf(batch, ["maxPropertiesPerPack"], "-")} 属性` : "未启用" },
    { label: "ACK 提交点", value: String(valueOf(ack, ["commitOn"], "-")) },
    { label: "ACK 超时", value: valueOf(ack, ["timeoutMs"], null) === null ? "-" : `${valueOf(ack, ["timeoutMs"], "-")} ms` },
    { label: "可靠发件箱", value: Boolean(outbox.enabled) ? "已启用" : "未启用" }
  ];
});
const cloudRisks = computed(() => {
  const risks = asRecord(reportMetrics.value).risks;
  return Array.isArray(risks) && risks.length ? risks.map((risk) => String(risk)) : ["未发现已知上报风险"];
});
const networkResultRows = computed(() => {
  const result = asRecord(networkResult.value);
  return [
    { label: "检测方式", value: String(valueOf(result, ["type"], "TCP")) },
    { label: "检测目标", value: String(valueOf(result, ["target"], networkTarget.value)) },
    { label: "解析地址", value: String(valueOf(result, ["resolvedAddress"], "-")) },
    { label: "目标端口", value: String(valueOf(result, ["port"], networkPort.value || "-")) },
    { label: "检测结论", value: Object.keys(result).length ? (Boolean(result.reachable) ? "可达" : "不可达") : "等待检测" },
    { label: "处理耗时", value: valueOf(result, ["durationMs"], null) === null ? "-" : `${valueOf(result, ["durationMs"], "-")} ms` }
  ];
});

onMounted(async () => {
  document.body.classList.add("theme-anchor", "modao-exact");
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
  document.body.classList.remove("theme-anchor", "modao-exact");
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

function isLocalDevice(device: DeviceInfo): boolean {
  return Boolean(device.temporaryConfig || device.configSource === "local" || device.configSource === "LOCAL" || asRecord(device).localDevice);
}

function deviceAddress(device: DeviceInfo): string {
  return [device.ipAddress, device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":") || "-";
}

function localizeDeviceStatus(status: unknown): string {
  switch (String(status || "UNKNOWN").toUpperCase()) {
    case "ONLINE":
    case "RUNNING":
      return "在线";
    case "OFFLINE":
      return "离线";
    case "ERROR":
      return "异常";
    case "STOPPED":
      return "已停止";
    default:
      return "未知";
  }
}

function statusBadgeClass(device: DeviceInfo): string {
  const status = String(device.status || "UNKNOWN").toUpperCase();
  if (status === "ONLINE" || status === "RUNNING") return "is-online";
  if (status === "ERROR") return "is-error";
  return "";
}

function editDevice(device: DeviceInfo) {
  if (isLocalDevice(device)) {
    selectDevice(deviceIdOf(device));
    editingBundle.value = null;
    localEditorVisible.value = true;
    return;
  }
  selectedProtocol.value = protocols.value.find((item) => item.protocol === (device.protocolType || device.connectionType)) || null;
  switchModule("collect");
}

function openDeviceDiff(device: DeviceInfo) {
  selectDevice(deviceIdOf(device));
  switchModule("collect");
  ElMessage.info("已切换到采集配置，可查看当前设备相关配置");
}

function openDeviceOperation(device: DeviceInfo, tab: "control" | "shadow") {
  selectDevice(deviceIdOf(device));
  workbenchTab.value = tab;
  activeModule.value = "workbench";
  router.push(tab === "control" ? "/control" : "/shadow").catch(() => undefined);
}

function protocolDefaultPort(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  const fields = Array.isArray(record.connectionFields) ? record.connectionFields.map((item) => asRecord(item)) : [];
  const portField = fields.find((field) => field.name === "port");
  return String(valueOf(record, ["defaultPort"], valueOf(portField || {}, ["defaultValue"], "-")));
}

function protocolMode(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  return String(valueOf(record, ["collectionMode", "triggerMode", "addressingMode", "collectorType"], "轮询/协议驱动"));
}

function protocolCapability(protocol: ProtocolSchema): string {
  const record = asRecord(protocol);
  return String(valueOf(record, ["implementationStatus", "status", "implementationState"], protocol.implemented === false ? "未实现" : "已接入"));
}

function openProtocolConfig(protocol: ProtocolSchema) {
  selectedProtocol.value = protocol;
  localEditorVisible.value = true;
}

function cloudStatusText(status: unknown): string {
  const key = String(status || "").toUpperCase();
  return ({ OK: "正常", UP: "正常", ONLINE: "正常", SUCCESS: "正常", WARN: "存在风险", WARNING: "存在风险", ERROR: "异常", FAILED: "异常", DOWN: "异常", DISABLED: "未启用" } as Record<string, string>)[key] || "未知";
}

function shortLoggerName(logger: unknown): string {
  const value = String(logger || "-");
  const parts = value.split(".").filter(Boolean);
  return parts.length > 2 ? parts.slice(-2).join(".") : value;
}

function realtimeAddress(row: RealtimePointRow): string {
  return String(valueOf(row, ["address", "registerAddress", "pointAddress"], "-"));
}

function realtimeScale(row: RealtimePointRow): string {
  return String(valueOf(row, ["scalingFactor", "scale", "factor"], "-"));
}

function realtimeValueText(row: RealtimePointRow): string {
  const value = valueOf(row, ["value", "currentValue", "rawValue"], "-");
  if (typeof value === "number") return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)));
  return String(value ?? "-");
}

function realtimeQualityText(row: RealtimePointRow): string {
  const quality = String(valueOf(row, ["quality", "qualityCode", "status"], "UNKNOWN"));
  switch (quality.toUpperCase()) {
    case "GOOD":
    case "OK":
      return "良好";
    case "BAD":
    case "ERROR":
      return "异常";
    default:
      return quality || "未知";
  }
}

function realtimeQualityClass(row: RealtimePointRow): string {
  const quality = String(valueOf(row, ["quality", "qualityCode", "status"], "UNKNOWN")).toUpperCase();
  if (["GOOD", "OK"].includes(quality)) return "is-good";
  if (["BAD", "ERROR"].includes(quality)) return "is-bad";
  return "";
}

function realtimeProcessingText(row: RealtimePointRow): string {
  const value = valueOf(row, ["processCostMs", "processingTime", "costMs", "elapsedMs"], "-");
  return typeof value === "number" ? `${value} ms` : String(value || "-");
}

function alarmMessage(alarm: AlarmRow): string {
  return String(alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || "告警触发");
}

function alarmLevelText(level: unknown): string {
  switch (String(level || "").toUpperCase()) {
    case "CRITICAL":
    case "FATAL":
    case "HIGH":
      return "严重";
    case "ERROR":
      return "错误";
    case "WARN":
    case "WARNING":
    case "MEDIUM":
      return "警告";
    case "INFO":
      return "信息";
    default:
      return String(level || "未知");
  }
}

function alarmToneClass(alarm: AlarmRow): string {
  const level = String(alarm.level || alarm.alarmType || "").toUpperCase();
  if (["CRITICAL", "FATAL", "ERROR", "HIGH", "严重"].includes(level)) return "is-error";
  if (["WARN", "WARNING", "MEDIUM", "警告"].includes(level)) return "is-warn";
  return "is-info";
}

function riskToneClass(device: DeviceInfo): string {
  const status = String(device.status || "").toUpperCase();
  if (status === "ERROR" || Boolean(device.lastError)) return "is-error";
  if (status === "OFFLINE") return "is-warn";
  return "is-warn";
}

function riskDescription(device: DeviceInfo): string {
  const status = String(device.status || "UNKNOWN");
  if (device.lastError) return `连接异常：${device.lastError}`;
  if (status.toUpperCase() === "OFFLINE") return "当前设备离线，配置存在但运行连接未建立";
  if (status.toUpperCase() === "ERROR") return "当前设备处于异常状态，请检查连接和协议配置";
  return `当前状态 ${status}`;
}

function deviceIdOf(device: DeviceInfo): string { return String(device.deviceId || device.id || ""); }
function deviceNameOf(deviceId: string): string { return devices.value.find((device) => deviceIdOf(device) === deviceId)?.deviceName || deviceId; }
function sumPoints(source: DeviceInfo[]): number { return source.reduce((sum, device) => sum + Number(device.pointCount || (Array.isArray(device.points) ? device.points.length : 0) || 0), 0); }
function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function extractArray<T>(value: unknown, keys: string[]): T[] { if (Array.isArray(value)) return value as T[]; const record = asRecord(value); for (const key of keys) if (Array.isArray(record[key])) return record[key] as T[]; return []; }
function valueOf(value: unknown, keys: string[], fallback: unknown): unknown { const record = asRecord(value); for (const key of keys) if (record[key] !== undefined && record[key] !== null) return record[key]; return fallback; }
function numberValue(value: unknown, fallback = 0): number { const parsed = Number(value); return Number.isFinite(parsed) ? parsed : fallback; }
function optionalNumber(value: unknown): number | null { const parsed = Number(value); return Number.isFinite(parsed) ? parsed : null; }
function ratioFrom(value: unknown): number | null { const parsed = optionalNumber(value); if (parsed === null) return null; const normalized = parsed > 1 && parsed <= 100 ? parsed / 100 : parsed; return Math.max(0, Math.min(1, normalized)); }
function ratioDegrees(value: number | null): number { return value === null ? 0 : Math.round(Math.max(0, Math.min(1, value)) * 360); }
function percentText(value: number | null): string { return value === null ? "-" : `${Math.round(value * 100)}%`; }
function formatDurationMs(value: unknown): string {
  const ms = optionalNumber(value);
  if (ms === null) return "-";
  const seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}天 ${hours}小时`;
  if (hours > 0) return `${hours}小时 ${minutes}分钟`;
  if (minutes > 0) return `${minutes}分钟`;
  return `${seconds}秒`;
}
function prettyJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
function compactJson(value: unknown): string { return JSON.stringify(value ?? {}, null, 2); }
function formatTime(value: unknown): string { if (!value) return "-"; const date = typeof value === "number" ? new Date(value) : new Date(String(value)); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(); }
</script>
