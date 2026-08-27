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
          <div class="exact-diagnostic-cards realtime-summary-cards"><div class="exact-diagnostic-card"><span>实时记录</span><strong>{{ realtimeSummary.total }}</strong></div><div class="exact-diagnostic-card"><span>质量正常</span><strong>{{ realtimeSummary.good }}</strong></div><div class="exact-diagnostic-card"><span>异常/未知</span><strong>{{ realtimeSummary.bad }}</strong></div></div>
          <section class="exact-surface realtime-single-panel">
            <div class="exact-surface-head"><h2>单点实时查询</h2><span>按稳定 pointId / 点位编码查询</span></div>
            <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="realtimeSingleDeviceId"><option value="">选择设备</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="realtimeSinglePointId" type="text" placeholder="pointId 或点位编码" /><button type="button" class="primary" :disabled="!realtimeSingleDeviceId || !realtimeSinglePointId" @click="loadSingleRealtime">查询单点</button></div></div>
            <pre class="json-view compact-result-view">{{ prettyJson(realtimeSingleResult) }}</pre>
          </section>
          <section class="exact-table-card"><table><thead><tr><th>点位名称</th><th>设备名称</th><th>数据类型</th><th>寄存器地址</th><th>读写</th><th>缩放</th><th>当前值</th><th>单位</th><th>采集时间</th><th>质量</th><th>处理耗时</th><th>操作</th></tr></thead><tbody><tr v-if="filteredRealtimeRows.length === 0"><td colspan="12" class="exact-empty">选择“全部设备”可聚合查看所有设备实时数据，也可选择单设备过滤</td></tr><tr v-for="row in filteredRealtimeRows" :key="`${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode}`"><td>{{ row.pointName || row.pointCode || '-' }}</td><td>{{ row.deviceName || deviceNameOf(String(row.deviceId || realtimeDeviceId)) }}</td><td>{{ row.dataType || '-' }}</td><td><code>{{ realtimeAddress(row) }}</code></td><td>{{ row.readWrite || '-' }}</td><td>{{ realtimeScale(row) }}</td><td><strong>{{ realtimeValueText(row) }}</strong></td><td>{{ row.unit || '-' }}</td><td>{{ formatTime(row.timestamp || row.collectTime || row.lastUpdateTime) }}</td><td><span class="quality-badge" :class="realtimeQualityClass(row)">{{ realtimeQualityText(row) }}</span></td><td>{{ realtimeProcessingText(row) }}</td><td><button type="button" @click="pickRealtimePoint(row)">查单点</button></td></tr></tbody></table></section>
        </div>
      </section>

      <section v-show="activeModule === 'device'" class="exact-page">
        <div class="section-heading">
          <div class="heading-title-line"><h1>设备管理</h1><span class="heading-online"><i></i>{{ filteredDevices.length }} 台设备</span></div>
          <div class="heading-actions"><button type="button" @click="loadDevices">刷新列表</button><button type="button" :disabled="configFileExporting" @click="exportDeviceConfigData">导出配置数据</button><button type="button" :disabled="configFileImporting" @click="openConfigImportFile">导入配置数据</button><button type="button" class="primary" @click="openLocalEditor()">新增本地设备</button></div>
        </div>
        <div class="exact-page-body">
          <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><input v-model="deviceKeyword" type="search" placeholder="搜索设备名称、标识或地址" /><select v-model="protocolFilter"><option value="">全部协议</option><option v-for="protocolItem in protocols" :key="protocolItem.protocol" :value="protocolItem.protocol">{{ protocolItem.title || protocolItem.protocol }}</option></select><select v-model="statusFilter"><option value="">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option><option value="ERROR">异常</option></select></div><div class="exact-toolbar-group"><button type="button" @click="syncDevices">同步远端配置</button></div></div>
          <div class="exact-device-list">
            <div v-if="filteredDevices.length === 0" class="exact-empty">{{ deviceListEmptyText }}</div>
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
                <button type="button" :disabled="deviceConfigOperatingId === `refresh:${deviceIdOf(device)}`" @click.stop="operateDeviceConfig(deviceIdOf(device), 'refresh')">刷新配置</button><button type="button" class="danger" :disabled="deviceConfigOperatingId === `clear:${deviceIdOf(device)}`" @click.stop="operateDeviceConfig(deviceIdOf(device), 'clear')">清理缓存</button>
                <button type="button" @click.stop="openDeviceOperation(device, 'config')">配置</button><button type="button" @click.stop="editDevice(device)">编辑</button><button type="button" @click.stop="openDeviceDiff(device)">差异</button><button type="button" @click.stop="openDeviceRuntimeStatus(device)">运行状态</button><button type="button" @click.stop="openDeviceAlarmHistory(device)">告警历史</button>
                <button type="button" @click.stop="openDeviceOperation(device, 'control')">控制</button><button type="button" @click.stop="openDeviceOperation(device, 'shadow')">影子</button>
                <button v-if="isLocalDevice(device)" type="button" class="danger" @click.stop="deleteLocal(deviceIdOf(device))">删除本地</button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section v-show="activeModule === 'collect'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>数据采集配置</h1><span class="heading-online"><i></i>{{ protocols.length }} 种协议</span></div><div class="heading-actions"><button type="button" @click="loadOverview">刷新概览</button></div></div>
        <div class="exact-page-body">
          <section class="exact-surface exact-global-config">
            <div class="exact-surface-head"><h2>全局采集配置</h2><span>当前运行配置</span></div>
            <div class="exact-config-grid">
              <div v-for="item in collectionSummaryItems" :key="item.label" class="exact-config-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div>
            </div>
          </section>
          <LegacyConfigOpsPanel :devices="devices" :selected-device-id="selectedDeviceId" @imported="refreshAll" @synced="refreshAll" />
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
        <div class="section-heading"><div class="heading-title-line"><h1>系统实时状态诊断</h1><span class="heading-online"><i></i>运行数据</span></div><div class="heading-actions"><button type="button" class="primary" @click="runDiagnostic">运行完整诊断</button><button type="button" @click="downloadDiagnosticPackage">导出诊断包</button></div></div>
        <div class="exact-page-body">
          <div class="exact-diagnostic-cards"><div v-for="item in diagnosticCards" :key="item.label" class="exact-diagnostic-card"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
          <section class="exact-table-card"><table><thead><tr><th>诊断项</th><th>状态</th><th>当前值</th><th>处理建议</th></tr></thead><tbody><tr v-for="row in diagnosticRows" :key="row.name"><td>{{ row.name }}</td><td><span class="status-badge" :class="row.tone">{{ row.status }}</span></td><td>{{ row.current }}</td><td>{{ row.suggestion }}</td></tr></tbody></table></section>
          <LegacyDiagnosticDetailPanel :cache-metrics="cacheMetrics" :device-metrics="deviceConnectionMetrics" :performance-metrics="performanceDetail" :exception-stats="exceptionStats" :storage-metrics="storageMetrics" />
          <LegacyDeviceRuntimePanel :devices="devices" :selected-device-id="selectedDeviceId" @select-device="selectDevice" />
          <details class="exact-json-panel" open><summary>查看原始诊断 JSON</summary><pre class="json-view">{{ prettyJson(diagnosticRaw) }}</pre></details>
        </div>
      </section>

      <LegacyHistoryPanel v-show="activeModule === 'history'" :devices="devices" :selected-device-id="selectedDeviceId" :selected-point-ref="historySelectedPointRef" @select-device="selectDevice" />

      <section v-show="activeModule === 'alarm'" class="exact-page">
        <div class="section-heading"><div class="heading-title-line"><h1>告警历史中心</h1><span class="heading-online"><i></i>{{ alarmScopeText }} · {{ alarms.length }} 条 · 已确认 {{ alarmHistorySummary.acknowledged }}</span></div><div class="heading-actions"><button type="button" :disabled="alarms.length === 0" @click="refreshAlarmAcknowledgements">确认状态批量查询</button><button type="button" @click="loadAlarms">刷新告警历史</button></div></div>
        <div class="exact-page-body">
          <div class="exact-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="alarmDeviceId" @change="loadAlarms"><option value="">全部设备最近告警</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><select v-model="alarmLevelFilter" @change="loadAlarms"><option value="">全部级别</option><option value="CRITICAL">严重</option><option value="WARNING">警告</option><option value="INFO">提示</option></select><select v-model.number="alarmHours" @change="loadAlarms"><option :value="24">最近 24 小时</option><option :value="72">最近 3 天</option><option :value="168">最近 7 天</option></select><input v-model="alarmKeyword" type="search" placeholder="点位编码或规则 ID" @keydown.enter="loadAlarms" /><input v-model.number="alarmLimit" type="number" min="10" max="500" step="10" /><button type="button" class="primary" @click="loadAlarms">查询</button></div></div>
          <div class="exact-diagnostic-cards alarm-summary-cards"><div class="exact-diagnostic-card"><span>告警总数</span><strong>{{ alarmHistorySummary.total }}</strong></div><div class="exact-diagnostic-card"><span>未确认</span><strong>{{ alarmHistorySummary.active }}</strong></div><div class="exact-diagnostic-card"><span>已确认</span><strong>{{ alarmHistorySummary.acknowledged }}</strong></div><div class="exact-diagnostic-card"><span>严重</span><strong>{{ alarmHistorySummary.critical }}</strong></div><div class="exact-diagnostic-card"><span>警告</span><strong>{{ alarmHistorySummary.warning }}</strong></div></div>
          <section class="exact-table-card alarm-ack-table"><table><thead><tr><th>级别</th><th>发生时间</th><th>设备</th><th>点位</th><th>规则/内容</th><th>当前值</th><th>确认状态</th><th>确认信息</th><th>操作</th></tr></thead><tbody><tr v-if="alarms.length === 0"><td colspan="9" class="exact-empty">暂无符合条件的告警历史</td></tr><tr v-for="alarm in alarms" :key="buildAlarmIdentity(alarm)"><td>{{ alarmLevelText(alarm.level || alarm.alarmType) }}</td><td>{{ formatTime(alarm.timestamp || alarm.occurTime) }}</td><td>{{ alarm.deviceName || alarm.deviceId || '-' }}</td><td>{{ alarm.pointName || alarm.pointCode || alarm.pointId || '-' }}</td><td>{{ alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || alarm.ruleId || '-' }}</td><td>{{ alarmCurrentValue(alarm) }}</td><td><span class="status-badge" :class="alarm.acknowledged ? 'is-online' : 'is-error'">{{ alarm.acknowledged ? '已确认' : '待确认' }}</span></td><td><span class="alarm-ack-detail" :title="describeAlarmAcknowledgement(alarm.acknowledgement)">{{ describeAlarmAcknowledgement(alarm.acknowledgement) }}</span></td><td><div class="alarm-action-row"><button v-if="!alarm.acknowledged" type="button" :disabled="acknowledgingAlarmId === buildAlarmIdentity(alarm)" @click="openAlarmAcknowledgementDialog(alarm)">{{ acknowledgingAlarmId === buildAlarmIdentity(alarm) ? '确认中' : '确认告警' }}</button><button type="button" @click="locateAlarmLogs(alarm)">定位日志</button><button type="button" @click="diagnoseAlarmNetwork(alarm)">网络检测</button></div></td></tr></tbody></table></section>
          <div v-if="alarmAckDialogVisible" class="alarm-ack-backdrop" role="dialog" aria-modal="true" aria-labelledby="alarmAckTitle" @click.self="closeAlarmAcknowledgementDialog"><section class="alarm-ack-dialog"><div class="alarm-ack-dialog-head"><div><span class="panel-kicker">告警处理</span><h2 id="alarmAckTitle">确认告警</h2></div><button type="button" @click="closeAlarmAcknowledgementDialog">关闭</button></div><p class="alarm-ack-target">{{ selectedAlarmAckTarget }}</p><label for="alarmAckNoteInput">处理说明</label><textarea id="alarmAckNoteInput" v-model="alarmAckNote" maxlength="500" placeholder="填写确认原因或后续处理计划"></textarea><div class="alarm-ack-idempotency"><span>幂等 key</span><code>{{ selectedAlarmAckIdempotencyKey }}</code></div><div class="heading-actions"><button type="button" :disabled="!selectedAlarmForAck || acknowledgingAlarmId === selectedAlarmAckId" class="primary" @click="submitAlarmAcknowledgement">{{ acknowledgingAlarmId === selectedAlarmAckId ? '提交中' : '提交确认' }}</button></div></section></div>
        </div>
      </section>

      <section v-show="activeModule === 'log'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>日志</h1><span class="heading-online"><i></i>{{ filteredLogs.length }} 条 · 错误 {{ logSummary.error }}</span></div><div class="heading-actions"><button type="button" :class="{ 'is-active': logAutoRefresh }" @click="logAutoRefresh = !logAutoRefresh">{{ logAutoRefresh ? '停止自动刷新' : '自动刷新' }}</button><button type="button" @click="loadLogs">刷新日志</button></div></div><div class="exact-page-body"><div class="exact-toolbar log-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="logLevel" @change="loadLogs"><option value="">全部级别</option><option value="ERROR">错误</option><option value="WARN">警告</option><option value="INFO">信息</option><option value="DEBUG">调试</option></select><select v-model="logDeviceId" @change="loadLogs"><option value="">全部设备</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="logLogger" type="text" placeholder="记录器名称 logger" @keydown.enter="loadLogs" /><input v-model="logThread" type="text" placeholder="线程名 thread" @keydown.enter="loadLogs" /><input v-model="logKeyword" type="search" placeholder="搜索日志内容、设备、点位或来源" @keydown.enter="loadLogs" /><input v-model.number="logLimit" type="number" min="20" max="2000" step="20" /><button type="button" class="primary" @click="loadLogs">查询</button></div><div class="exact-toolbar-group"><button type="button" @click="showErrorLogs">错误日志快速定位</button><button type="button" @click="searchLatestExceptionLogs">最近异常定位</button><button type="button" @click="downloadLogs('txt')">导出文本</button><button type="button" @click="downloadLogs('json')">导出 JSON</button></div></div><div class="exact-diagnostic-cards log-summary-cards"><div class="exact-diagnostic-card"><span>当前结果</span><strong>{{ logSummary.total }}</strong></div><div class="exact-diagnostic-card"><span>错误日志</span><strong>{{ logSummary.error }}</strong></div><div class="exact-diagnostic-card"><span>警告日志</span><strong>{{ logSummary.warn }}</strong></div><div class="exact-diagnostic-card"><span>日志器 / 线程</span><strong>{{ logSummary.loggerCount }} / {{ logSummary.threadCount }}</strong></div></div><section class="exact-surface modao-log-panel"><div v-if="filteredLogs.length === 0" class="empty-state compact">当前条件下没有可显示日志</div><div v-for="(log, index) in filteredLogs" :key="`${log.timestamp || log.time || index}-${log.logger || '-'}-${log.thread || '-'}`" class="modao-log-row"><span class="modao-log-time">{{ formatTime(log.timestamp || log.time) }}</span><strong class="modao-log-level" :class="String(log.level || 'INFO').toUpperCase()">{{ log.level || 'INFO' }}</strong><span class="modao-log-name" :title="String(log.logger || '-')">{{ shortLoggerName(log.logger) }}</span><span class="modao-log-thread" :title="String(log.thread || '-')">{{ log.thread || '-' }}</span><span class="modao-log-message">{{ log.message || log.content || '-' }}</span></div></section></div></section>

      <section v-show="activeModule === 'network'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>网络检测</h1><span class="heading-online"><i></i>{{ networkResult ? networkResult.conclusionText : '等待检测' }} · {{ networkHistory.length }} 条历史</span></div></div><div class="exact-page-body"><div class="exact-toolbar network-toolbar"><div class="exact-toolbar-group exact-toolbar-filters"><select v-model="networkType" @change="syncNetworkMode"><option v-for="item in NETWORK_DIAGNOSTIC_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option></select><select v-model="networkDeviceId" @change="applyNetworkDevice"><option value="">本机 / 白名单目标</option><option v-for="device in devices" :key="deviceIdOf(device)" :value="deviceIdOf(device)">{{ device.deviceName || deviceIdOf(device) }}</option></select><input v-model="networkTarget" type="text" placeholder="从设备配置自动带入 host" /><input v-model.number="networkPort" type="number" min="1" max="65535" :disabled="networkType !== 'TCP'" placeholder="TCP 目标端口" /><input v-model.number="networkTimeout" type="number" min="100" max="10000" placeholder="超时 ms" /><button type="button" @click="fillNetworkFromSelectedDevice">从设备配置带入</button></div><div class="exact-toolbar-group network-toolbar-actions"><button type="button" :disabled="networkOperating" class="primary" @click="runNetwork">{{ networkOperating ? '检测中' : '开始检测' }}</button><button type="button" :disabled="networkHistory.length === 0" class="primary" @click="downloadNetworkReport">导出检测结果</button></div></div><div class="exact-diagnostic-cards network-summary-cards"><div class="exact-diagnostic-card"><span>检测方式</span><strong>{{ networkType }}</strong></div><div class="exact-diagnostic-card"><span>检测结论</span><strong>{{ networkResult ? networkResult.conclusionText : '-' }}</strong></div><div class="exact-diagnostic-card"><span>失败原因中文化</span><strong>{{ networkResult ? networkResult.reasonText : '尚未执行' }}</strong></div><div class="exact-diagnostic-card"><span>检测历史记录</span><strong>{{ networkHistory.length }}</strong></div></div><section class="exact-surface network-result-panel"><div class="exact-surface-head"><h2>检测结果</h2><span>{{ networkTarget }}{{ networkType === 'TCP' ? `:${networkPort || '-'}` : '' }}</span></div><div class="network-result-grid"><div v-for="row in networkResultRows" :key="row.label" class="exact-config-item"><span>{{ row.label }}</span><strong>{{ row.value }}</strong></div></div><pre class="json-view">{{ networkResult ? prettyJson(networkResult) : '尚未执行网络检测' }}</pre><div v-if="networkResult?.details?.length" class="network-trace-lines"><strong>路由明细</strong><code v-for="(line, index) in networkResult.details" :key="`${index}-${line}`">{{ line }}</code></div></section><section class="exact-table-card network-history-table"><div class="exact-table-title"><h2>检测历史记录</h2><span>最多保留 10 条</span></div><table><thead><tr><th>时间</th><th>方式</th><th>目标</th><th>端口</th><th>结论</th><th>耗时</th><th>原因</th></tr></thead><tbody><tr v-if="networkHistory.length === 0"><td colspan="7" class="exact-empty">暂无网络检测历史</td></tr><tr v-for="item in networkHistory" :key="`${item.completedAt || '-'}-${item.type}-${item.target}-${item.port || '-'}`"><td>{{ formatTime(item.completedAt) }}</td><td>{{ item.type }}</td><td>{{ item.target }}</td><td>{{ item.port ?? '-' }}</td><td><span class="status-badge" :class="item.reachable ? 'is-online' : 'is-error'">{{ item.conclusionText }}</span></td><td>{{ item.durationMs ?? '-' }} ms</td><td>{{ item.reasonText }}</td></tr></tbody></table></section><LegacyEdgeTelemetryPanel :devices="devices" :selected-device-id="selectedDeviceId" @select-device="selectDevice" /></div></section>

      <section v-show="activeModule === 'cloud'" class="exact-page"><div class="section-heading"><div class="heading-title-line"><h1>云平台配置</h1><span class="heading-online"><i></i>可靠上报链路</span></div><div class="heading-actions"><button type="button" @click="loadOverview">刷新链路</button></div></div><div class="exact-page-body"><div class="exact-cloud-grid"><section class="exact-surface exact-cloud-status"><div class="exact-cloud-icon">云</div><strong>{{ cloudStatusTextValue }}</strong><small>{{ cloudEnabledText }}</small><div class="cloud-stat-row"><span v-for="item in cloudSummaryCards" :key="item.label"><b>{{ item.value }}</b>{{ item.label }}</span></div></section><section class="exact-surface"><div class="exact-surface-head"><h2>上报策略</h2><span>{{ reportState }}</span></div><div class="modao-property-grid"><div v-for="item in cloudStrategyRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div></section></div><section class="exact-surface"><div class="exact-surface-head"><h2>Outbox / ACK 明细</h2><span>{{ cloudOperationalRows.length }} 项</span></div><div class="modao-property-grid"><div v-for="item in cloudOperationalRows" :key="item.label" class="modao-property-item"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div></section><section class="exact-surface"><div class="exact-surface-head"><h2>链路风险</h2><span>{{ cloudRisks.length }} 项</span></div><div class="modao-risk-list"><div v-for="risk in cloudRisks" :key="risk" class="modao-risk-item"><strong>{{ cloudRisks.length ? '风险' : '检查结果' }}</strong><small>{{ risk }}</small></div></div></section><details class="exact-json-panel"><summary>查看上报链路 JSON</summary><pre class="json-view">{{ prettyJson(reportMetrics) }}</pre></details></div></section>

      <section v-show="activeModule === 'workbench'" id="deviceOperationPanel" class="local-editor local-device-panel local-device-web-dialog device-operation-panel">
        <div class="local-editor-title">
          <div>
            <span class="label-chip">设备配置</span>
            <h3>{{ selectedDevice?.deviceName || '请选择设备' }}</h3>
            <p>{{ selectedDeviceId || '从设备管理列表选择设备' }} · {{ selectedDevice?.protocolType || selectedDevice?.connectionType || '-' }} · {{ deviceAddress(selectedDevice || {}) }}</p>
          </div>
          <div class="local-editor-title-actions">
            <div class="local-editor-stats">
              <div class="local-editor-stat"><strong>{{ selectedOperationStatus }}</strong><span>运行状态</span></div>
              <div class="local-editor-stat"><strong>{{ selectedDevice?.collectionInterval || '-' }}</strong><span>采集周期 ms</span></div>
              <div class="local-editor-stat"><strong>{{ selectedRealtimeRows.length }}</strong><span>实时点位</span></div>
            </div>
            <button type="button" @click="switchModule('device')">返回列表</button>
          </div>
        </div>

        <div class="local-editor-tabs" role="tablist" aria-label="设备操作工作台分区">
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'config' }" @click="workbenchTab = 'config'">
            <span>01</span><strong>工作台</strong><small>点位、实时和日志</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'control' }" @click="workbenchTab = 'control'">
            <span>02</span><strong>批量和协议命令</strong><small>单点、批量和协议命令</small>
          </button>
          <button type="button" class="local-editor-tab" :class="{ 'is-active': workbenchTab === 'shadow' }" @click="workbenchTab = 'shadow'">
            <span>03</span><strong>desired、desired_delta</strong><small>reported、desired、delta</small>
          </button>
        </div>

        <div class="local-editor-layout">
          <aside class="local-editor-rail device-operation-rail">
            <div>
              <span class="label-chip">当前设备</span>
              <strong>{{ selectedDevice?.deviceName || selectedDeviceId || '未选择设备' }}</strong>
              <p>配置、控制和影子共用同一个设备上下文；切换分区不会丢失当前选择。</p>
            </div>
            <ol class="local-checklist device-info-list">
              <li :class="selectedDeviceId ? 'is-ok' : 'is-error'"><span>设备已选择</span><strong>{{ selectedDeviceId || '请先选择设备' }}</strong></li>
              <li :class="selectedOperationStatus === 'ONLINE' ? 'is-ok' : 'is-warn'"><span>运行状态</span><strong>{{ selectedOperationStatus }}</strong></li>
              <li :class="selectedRealtimeRows.length > 0 ? 'is-ok' : 'is-warn'"><span>实时点位</span><strong>{{ selectedRealtimeRows.length }} 个</strong></li>
              <li :class="selectedConnectionOk ? 'is-ok' : 'is-warn'"><span>连接状态</span><strong>{{ selectedConnectionText }}</strong></li>
            </ol>
            <div class="device-operation-rail-actions">
              <button type="button" :disabled="!selectedDeviceId || deviceConfigOperatingId === `refresh:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'refresh')">刷新配置</button>
              <button type="button" class="danger" :disabled="!selectedDeviceId || deviceConfigOperatingId === `clear:${selectedDeviceId}`" @click="operateDeviceConfig(selectedDeviceId, 'clear')">清理缓存</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceRuntimeStatus">运行状态</button>
              <button type="button" :disabled="!selectedDeviceId" @click="openSelectedDeviceAlarmHistory">告警历史</button>
            </div>
          </aside>

          <div class="local-editor-body device-operation-body">
            <DeviceConfigPanel v-if="workbenchTab === 'config'" :device="selectedDeviceView" @start="startSelectedDevice" @stop="stopSelectedDevice" @open-history="openWorkbenchHistory" @open-realtime="openWorkbenchRealtime" />
            <ManualShadowPanels v-else :tab="workbenchTab" :device-id="selectedDeviceId" />
          </div>
        </div>
      </section>
    </main>

    <input ref="configImportInput" class="hidden-file-input" type="file" accept="application/json,.json" @change="handleConfigImportFile" />
    <LocalDeviceEditor v-model="localEditorVisible" :editing-bundle="editingBundle" :protocols="protocols" @saved="handleLocalSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import LegacyConfigOpsPanel from "./LegacyConfigOpsPanel.vue";
import LegacyDeviceRuntimePanel from "./LegacyDeviceRuntimePanel.vue";
import LegacyDiagnosticDetailPanel from "./LegacyDiagnosticDetailPanel.vue";
import LegacyEdgeTelemetryPanel from "./LegacyEdgeTelemetryPanel.vue";
import LegacyHistoryPanel from "./LegacyHistoryPanel.vue";
import LocalDeviceEditor from "@/components/device/LocalDeviceEditor.vue";
import ManualShadowPanels from "./LegacyManualShadowPanels.vue";
import { clearDeviceConfig, deleteLocalDevice, exportConfigs, getConfigDevices as getConfigDeviceList, getConfigSummary, getDevicePointsConfig, getLocalDevice, importConfigs, refreshDeviceConfig, triggerFullConfigSync } from "@/api/config.api";
import { getAllDeviceStatistics, getDeviceRuntime, reloadDevices, startDevice, startLocalDevice, stopDevice } from "@/api/device.api";
import { getCacheMetrics, getCloudReportMetrics, getCollectorPerformance, getDeviceConnectionMetrics, getExceptionStats, getPerformanceDetail, getRuntimeStatus, getStorageMetrics, getSystemResources } from "@/api/monitor.api";
import { getAllDeviceDataSummaries, getDeviceAlarmHistory, getDeviceRealtimeData, getPointRealtimeData, getRecentAlarms, resetAdaptiveConfig } from "@/api/data.api";
import { listProtocols } from "@/api/protocol.api";
import { acknowledgeAlarm, diagnoseNetwork, getOpsLogs, normalizeLogRows, queryAlarmAcknowledgements } from "@/api/ops.api";
import { useAppStore } from "@/stores/app.store";
import { normalizeDeviceViewModelWithRuntimeStatus, resolveDeviceStartMode } from "@/stores/device.store";
import { extractLocalDeviceBundle, type LocalDeviceBundle } from "@/components/device/local-device-utils";
import { applyAlarmAcknowledgement, buildAlarmAckPayload, buildAlarmIdentity, buildAlarmTroubleshootTarget, describeAlarmAcknowledgement, mergeAlarmAcknowledgementStates, normalizeAlarmAcknowledgementMap } from "@/views/ops/ops-utils";
import { buildAlarmHistoryQuery, normalizeAlarmHistoryRows, summarizeAlarmHistory } from "./alarm-history-utils";
import { buildConfigExportFilename, buildConfigImportRequest, buildDeviceListEmptyText, countConfigImportBundles, normalizeConfigExportText, parseConfigImportText } from "./config-utils";
import { DEVICE_CONFIG_ACTIONS, buildDeviceConfigActionMessage, normalizeDeviceConfigActionResult, type DeviceConfigActionType } from "./device-config-actions-utils";
import { buildLogExportFilename, buildLogQueryParams, buildLogSearchFromException, exportLogRowsAsJson, exportLogRowsAsText, filterLogRows, summarizeLogRows } from "./log-utils";
import { NETWORK_DIAGNOSTIC_TYPES, appendNetworkHistory, buildNetworkDiagnosticPayload, buildNetworkExportText, buildNetworkResultRows, normalizeNetworkDiagnosticResult, resolveNetworkTargetFromDevice, type NetworkDiagnosticType, type NormalizedNetworkDiagnosticResult } from "./network-utils";
import { buildRealtimeSummary, normalizeRealtimeRows, normalizeSinglePointRealtimeRow } from "./realtime-utils";
import type { DeviceInfo, DeviceRuntimeSnapshot, DeviceViewModel } from "@/types/device";
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

type ModuleKey = "overview" | "realtime" | "history" | "alarm" | "device" | "collect" | "cloud" | "diag" | "log" | "network" | "workbench";

const navGroups: Array<{ title: string; items: Array<{ key: ModuleKey; label: string; icon: string }> }> = [
  { title: "运行", items: [{ key: "overview", label: "概览", icon: viewDashboardIcon }, { key: "realtime", label: "实时数据", icon: chartTimelineIcon }, { key: "history", label: "历史趋势", icon: chartTimelineIcon }, { key: "alarm", label: "告警总览", icon: alertCircleIcon }] },
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
const devices = ref<DeviceViewModel[]>([]);
const deviceRuntimeMap = ref<Record<string, DeviceRuntimeSnapshot>>({});
const protocols = ref<ProtocolSchema[]>([]);
const runtimeStatus = ref<unknown>({});
const systemResource = ref<unknown>({});
const reportMetrics = ref<unknown>({});
const cacheMetrics = ref<unknown>({});
const deviceConnectionMetrics = ref<unknown>({});
const collectorPerformance = ref<unknown>({});
const exceptionStats = ref<unknown>({});
const storageMetrics = ref<unknown>({});
const performanceDetail = ref<unknown>({});
const deviceStats = ref<unknown>({});
const configSummary = ref<unknown>({});
const alarms = ref<AlarmRow[]>([]);
const alarmAcknowledgements = ref<Record<string, unknown>>({});
const acknowledgingAlarmId = ref("");
const alarmAckDialogVisible = ref(false);
const alarmAckNote = ref("");
const selectedAlarmForAck = ref<AlarmRow | null>(null);
const alarmDeviceId = ref("");
const alarmLevelFilter = ref("");
const alarmKeyword = ref("");
const alarmHours = ref(24);
const alarmLimit = ref(50);
const logs = ref<LogRow[]>([]);
const realtimeRows = ref<RealtimePointRow[]>([]);
const selectedRealtimeRows = ref<RealtimePointRow[]>([]);
const selectedDeviceId = ref("");
const historySelectedPointRef = ref("");
const deviceConfigOperatingId = ref("");
const realtimeDeviceId = ref("");
const realtimeKeyword = ref("");
const realtimeSingleDeviceId = ref("");
const realtimeSinglePointId = ref("");
const realtimeSingleResult = ref<unknown>({ message: "选择设备和点位后查询单点实时数据" });
const realtimeAuto = ref(true);
const deviceKeyword = ref("");
const protocolFilter = ref("");
const statusFilter = ref("");
const deviceLoading = ref(false);
const deviceLoadError = ref("");
const selectedProtocol = ref<ProtocolSchema | null>(null);
const logLevel = ref("");
const logDeviceId = ref("");
const logLogger = ref("");
const logThread = ref("");
const logKeyword = ref("");
const logLimit = ref(100);
const logAutoRefresh = ref(false);
const networkDeviceId = ref("");
const networkType = ref<NetworkDiagnosticType>("PING");
const networkTarget = ref("127.0.0.1");
const networkPort = ref(9090);
const networkTimeout = ref(3000);
const networkOperating = ref(false);
const networkResult = ref<NormalizedNetworkDiagnosticResult | null>(null);
const networkHistory = ref<NormalizedNetworkDiagnosticResult[]>([]);
const diagnosticRaw = ref<unknown>({});
const localEditorVisible = ref(false);
const configImportInput = ref<HTMLInputElement | null>(null);
const configFileExporting = ref(false);
const configFileImporting = ref(false);
const editingBundle = ref<LocalDeviceBundle | null>(null);
const workbenchTab = ref<"config" | "control" | "shadow">("config");
let clockTimer = 0;
let realtimeTimer = 0;
let logTimer = 0;

const nodeIdentity = computed(() => appStore.platform === "browser" ? "本地浏览器" : `Electron/${appStore.platform}`);
const systemStatusText = computed(() => appStore.initialized ? "服务可用" : "检测中");
const systemStatusClass = computed(() => appStore.initialized ? "is-online" : "is-unknown");
const onlineCount = computed(() => devices.value.filter((device) => String(device.status || "").toUpperCase() === "ONLINE").length);
const riskDevices = computed(() => devices.value.filter((device) => ["ERROR", "OFFLINE"].includes(String(device.status || "").toUpperCase()) || Boolean(device.lastError)).slice(0, 6));
const reportState = computed(() => Object.keys(asRecord(reportMetrics.value)).length ? "已加载" : "未知");
const runtimeState = computed(() => Object.keys(asRecord(runtimeStatus.value)).length ? "资源已加载" : "资源未知");
const lastRefreshText = computed(() => lastRefresh.value ? `刷新于 ${lastRefresh.value.toLocaleTimeString()}` : "等待刷新");
const selectedDevice = computed(() => devices.value.find((device) => deviceIdOf(device) === selectedDeviceId.value));
const selectedDeviceView = computed(() => selectedDevice.value ? normalizeDeviceViewModelWithRuntimeStatus(selectedDevice.value, deviceRuntimeMap.value) : null);
const selectedRuntimeSnapshot = computed(() => selectedDeviceId.value ? (selectedDeviceView.value?.runtime || deviceRuntimeMap.value[selectedDeviceId.value]) : undefined);
const selectedConnectionOk = computed(() => Boolean(selectedRuntimeSnapshot.value?.connected || selectedRuntimeSnapshot.value?.running || selectedRealtimeRows.value.length > 0));
const selectedConnectionText = computed(() => selectedConnectionOk.value ? "正常" : "未知");
const selectedOperationStatus = computed(() => {
  const runtime = selectedRuntimeSnapshot.value;
  if (runtime?.running || runtime?.connected || selectedRealtimeRows.value.length > 0) {
    return "ONLINE";
  }
  return String(selectedDeviceView.value?.status || selectedDevice.value?.status || "未知");
});
const filteredDevices = computed(() => {
  const keyword = deviceKeyword.value.trim().toLowerCase();
  return devices.value.filter((device) => {
    const protocol = String(device.protocolType || device.connectionType || "");
    const status = String(device.status || "");
    const text = [device.deviceName, deviceIdOf(device), device.ipAddress, protocol, status].join(" ").toLowerCase();
    return (!keyword || text.includes(keyword)) && (!protocolFilter.value || protocol === protocolFilter.value) && (!statusFilter.value || status === statusFilter.value);
  });
});
const deviceListEmptyText = computed(() => buildDeviceListEmptyText({
  loading: deviceLoading.value,
  errorMessage: deviceLoadError.value,
  hasFilters: Boolean(deviceKeyword.value.trim() || protocolFilter.value || statusFilter.value)
}));
const filteredRealtimeRows = computed(() => {
  const keyword = realtimeKeyword.value.trim().toLowerCase();
  return realtimeRows.value.filter((row) => !keyword || [row.pointName, row.pointCode, row.address, row.deviceName].join(" ").toLowerCase().includes(keyword));
});
const realtimeSummary = computed(() => buildRealtimeSummary(filteredRealtimeRows.value));
const alarmHistorySummary = computed(() => summarizeAlarmHistory(alarms.value));
const alarmScopeText = computed(() => alarmDeviceId.value ? `设备 ${deviceNameOf(alarmDeviceId.value)}` : "全部设备最近告警");
const selectedAlarmAckId = computed(() => selectedAlarmForAck.value ? buildAlarmIdentity(selectedAlarmForAck.value) : "");
const selectedAlarmAckTarget = computed(() => selectedAlarmForAck.value ? `${selectedAlarmForAck.value.deviceName || selectedAlarmForAck.value.deviceId || "-"} / ${selectedAlarmForAck.value.pointName || selectedAlarmForAck.value.pointCode || selectedAlarmForAck.value.pointId || "-"}` : "-");
const selectedAlarmAckIdempotencyKey = computed(() => selectedAlarmAckId.value ? buildAlarmAckPayload(alarmAckNote.value, selectedAlarmAckId.value).idempotencyKey : "-");
const filteredLogs = computed(() => filterLogRows(logs.value, { level: logLevel.value, logger: logLogger.value, keyword: logKeyword.value, deviceId: logDeviceId.value, thread: logThread.value }));
const logSummary = computed(() => summarizeLogRows(filteredLogs.value));
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
const cacheToneClass = computed(() => ratioFrom(valueOf(cacheMetrics.value, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], valueOf(runtimeStatus.value, ["cacheHitRatio", "hitRatio", "cacheHitRate"], null))) === null ? "is-muted" : "is-ok");
const storageToneClass = computed(() => Object.keys(asRecord(storageMetrics.value)).length ? "is-ok" : (Object.keys(asRecord(configSummary.value)).length ? "is-ok" : "is-muted"));
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
  const perf = asRecord(performanceDetail.value);
  if (maxThreads === 0 && Object.keys(perf).length) {
    activeThreads = numberValue(valueOf(perf, ["activeThreads", "activeCount", "collectActiveCount", "processActiveCount"], 0));
    maxThreads = numberValue(valueOf(perf, ["maxThreads", "maxPoolSize", "collectMaxPoolSize", "processMaxPoolSize"], 0));
    queuedTasks = numberValue(valueOf(perf, ["queuedTasks", "queueSize", "collectQueueSize", "processQueueSize"], 0));
    rejectedTasks = numberValue(valueOf(perf, ["rejectedTasks", "rejectedCount", "batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"], 0));
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
  const deviceMetrics = asRecord(deviceConnectionMetrics.value);
  const activeConnections = valueOf(deviceMetrics, ["activeConnections", "connectedCount", "onlineCount"], onlineCount.value);
  const cacheRate = ratioFrom(valueOf(cacheMetrics.value, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], valueOf(runtimeStatus.value, ["cacheHitRatio", "hitRatio", "cacheHitRate"], null)));
  return [
    { label: "系统运行时间", value: formatDurationMs(valueOf(resource, ["uptimeMillis", "uptime"], null)) },
    { label: "设备配置总数", value: `${valueOf(stats, ["deviceCount"], devices.value.length)} 台` },
    { label: "点位总数", value: `${valueOf(stats, ["pointCount"], sumPoints(devices.value))} 个` },
    { label: "活跃连接", value: `${activeConnections} 个` },
    { label: "缓存命中率", value: percentText(cacheRate) },
    { label: "异常统计", value: `${valueOf(exceptionStats.value, ["totalCount", "exceptionCount", "errorCount"], 0)} 次` }
  ];
});
const diagnosticRows = computed(() => {
  const cacheRate = ratioFrom(valueOf(cacheMetrics.value, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], valueOf(runtimeStatus.value, ["totalHitRate", "cacheHitRatio", "hitRatio", "cacheHitRate"], null)));
  const perf = asRecord(performanceDetail.value);
  const queued = numberValue(resourceSummary.value.queuedTasks === "-" ? valueOf(perf, ["queuedTasks", "queueSize", "collectQueueSize", "processQueueSize"], 0) : resourceSummary.value.queuedTasks, 0);
  const rejected = numberValue(valueOf(perf, ["rejectedTasks", "rejectedCount", "batchDispatchRejectedCount", "collectRejectedCount", "processRejectedCount"], 0), 0);
  const reportStatus = String(valueOf(reportMetrics.value, ["status", "state"], "UNKNOWN")).toUpperCase();
  const deviceMetrics = asRecord(deviceConnectionMetrics.value);
  const expectedConnections = numberValue(valueOf(deviceMetrics, ["expectedConnections", "totalConnections", "deviceCount"], devices.value.length), devices.value.length);
  const activeConnections = numberValue(valueOf(deviceMetrics, ["activeConnections", "connectedCount", "onlineCount"], onlineCount.value), onlineCount.value);
  const missing = Math.max(0, expectedConnections - activeConnections);
  const storageStatus = String(valueOf(storageMetrics.value, ["status", "state"], Object.keys(asRecord(storageMetrics.value)).length ? "UP" : "UNKNOWN")).toUpperCase();
  const storageKnown = Object.keys(asRecord(storageMetrics.value)).length > 0;
  const exceptionCount = numberValue(valueOf(exceptionStats.value, ["totalCount", "exceptionCount", "errorCount"], 0), 0);
  const rows = [
    { name: "应用服务", status: appStore.initialized ? "正常" : "异常", current: systemStatusText.value, suggestion: appStore.initialized ? "无需处理" : "检查应用健康检查明细" },
    { name: "设备连接", status: missing === 0 ? "正常" : "警告", current: `${activeConnections}/${expectedConnections}`, suggestion: "检查缺失连接和设备网络" },
    { name: "缓存服务", status: cacheRate === null || cacheRate >= 0.8 ? "正常" : "警告", current: cacheRate === null ? "指标不可用" : percentText(cacheRate), suggestion: "低命中率时检查缓存配置" },
    { name: "线程池拒绝", status: queued === 0 && rejected === 0 ? "正常" : "异常", current: `${resourceSummary.value.title}，队列 ${queued}，拒绝 ${rejected}`, suggestion: "检查队列容量、任务耗时和拒绝策略" },
    { name: "异常统计", status: exceptionCount === 0 ? "正常" : "警告", current: `${exceptionCount} 次`, suggestion: "查看异常统计明细和应用日志" },
    { name: "历史存储", status: storageKnown && ["UP", "OK", "ONLINE", "SUCCESS"].includes(storageStatus) ? "正常" : "警告", current: storageKnown ? cloudStatusText(storageStatus) : "指标不可用", suggestion: "检查 TDengine 或历史存储配置" },
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
const cloudOperationalRows = computed(() => {
  const report = asRecord(reportMetrics.value);
  const outbox = asRecord(report.outbox);
  const ack = asRecord(report.ack);
  const ackRuntime = asRecord(report.ackRuntime);
  const executor = asRecord(report.executor);
  return [
    { label: "待发送", value: String(valueOf(outbox, ["pendingCount"], valueOf(executor, ["queueSize"], "-"))) },
    { label: "待 ACK", value: String(valueOf(outbox, ["pendingAckCount"], valueOf(ackRuntime, ["pendingCount"], "-"))) },
    { label: "隔离消息", value: String(valueOf(outbox, ["isolatedCount"], "-")) },
    { label: "ACK 成功", value: String(valueOf(ackRuntime, ["successCount"], "-")) },
    { label: "ACK 失败", value: String(valueOf(ackRuntime, ["failureCount"], "-")) },
    { label: "ACK 提交点", value: String(valueOf(ack, ["commitOn"], "-")) },
    { label: "ACK 超时", value: valueOf(ack, ["timeoutMs"], null) === null ? "-" : `${valueOf(ack, ["timeoutMs"], "-")} ms` }
  ];
});
const cloudRisks = computed(() => {
  const risks = asRecord(reportMetrics.value).risks;
  return Array.isArray(risks) && risks.length ? risks.map((risk) => String(risk)) : ["未发现已知上报风险"];
});
const networkResultRows = computed(() => networkResult.value ? buildNetworkResultRows(networkResult.value) : [
  { label: "检测方式", value: networkType.value },
  { label: "检测目标", value: networkTarget.value || "-" },
  { label: "检测结论", value: "等待检测" },
  { label: "失败原因", value: "尚未执行" }
]);

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
  logTimer = window.setInterval(() => {
    if (logAutoRefresh.value && activeModule.value === "log") {
      void loadLogs();
    }
  }, 5000);
  await refreshAll();
});

onBeforeUnmount(() => {
  document.body.classList.remove("theme-anchor", "modao-exact");
  window.clearInterval(clockTimer);
  window.clearInterval(realtimeTimer);
  window.clearInterval(logTimer);
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
    history: "history",
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
    history: "/history",
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
  deviceLoading.value = true;
  deviceLoadError.value = "";
  try {
    const [deviceResponse, runtimeResponse] = await Promise.allSettled([getConfigDeviceList(), getDeviceRuntime()]);
    if (runtimeResponse.status === "fulfilled") {
      const snapshots = extractArray<DeviceRuntimeSnapshot>(runtimeResponse.value, ["data", "items", "records"]);
      deviceRuntimeMap.value = Object.fromEntries(snapshots.map((item) => [item.deviceId, item]).filter(([deviceId]) => Boolean(deviceId)));
    }
    if (deviceResponse.status !== "fulfilled") {
      throw deviceResponse.reason;
    }
    devices.value = extractArray<DeviceInfo>(deviceResponse.value, ["devices", "data", "items", "records"])
      .map((device) => normalizeDeviceViewModelWithRuntimeStatus(device, deviceRuntimeMap.value));
    if (!selectedDeviceId.value && devices.value.length) selectedDeviceId.value = deviceIdOf(devices.value[0]);
  } catch (error) {
    deviceLoadError.value = error instanceof Error ? error.message : "设备配置加载失败";
    throw error;
  } finally {
    deviceLoading.value = false;
  }
}

async function loadOverview() {
  const [stats, runtime, resource, report, summary, cache, devicesMetric, collectorPerf, exceptions, storage, perfDetail] = await Promise.allSettled([
    getAllDeviceStatistics(),
    getRuntimeStatus(),
    getSystemResources(),
    getCloudReportMetrics(),
    getConfigSummary(),
    getCacheMetrics(),
    getDeviceConnectionMetrics(),
    getCollectorPerformance(),
    getExceptionStats(),
    getStorageMetrics(),
    getPerformanceDetail()
  ]);
  if (stats.status === "fulfilled") deviceStats.value = stats.value;
  if (runtime.status === "fulfilled") runtimeStatus.value = runtime.value;
  if (resource.status === "fulfilled") systemResource.value = resource.value;
  if (report.status === "fulfilled") reportMetrics.value = report.value;
  if (summary.status === "fulfilled") configSummary.value = summary.value;
  if (cache.status === "fulfilled") cacheMetrics.value = cache.value;
  if (devicesMetric.status === "fulfilled") deviceConnectionMetrics.value = devicesMetric.value;
  if (collectorPerf.status === "fulfilled") collectorPerformance.value = collectorPerf.value;
  if (exceptions.status === "fulfilled") exceptionStats.value = exceptions.value;
  if (storage.status === "fulfilled") storageMetrics.value = storage.value;
  if (perfDetail.status === "fulfilled") performanceDetail.value = perfDetail.value;
}

async function loadAlarms() {
  try {
    const params = buildAlarmHistoryQuery({ level: alarmLevelFilter.value, keyword: alarmKeyword.value, hours: alarmHours.value, limit: alarmLimit.value });
    const response = alarmDeviceId.value ? await getDeviceAlarmHistory(alarmDeviceId.value, params) : await getRecentAlarms(params);
    const rows = normalizeAlarmHistoryRows(response);
    alarms.value = mergeAlarmAcknowledgementStates(rows, await fetchAlarmAcknowledgements(rows));
  } catch {
    alarmAcknowledgements.value = {};
    alarms.value = [];
  }
}

async function fetchAlarmAcknowledgements(rows: AlarmRow[]): Promise<Record<string, unknown>> {
  const alarmIds = Array.from(new Set(rows.map((alarm) => buildAlarmIdentity(alarm)).filter(Boolean))).slice(0, 500);
  alarmAcknowledgements.value = alarmIds.length ? normalizeAlarmAcknowledgementMap(await queryAlarmAcknowledgements(alarmIds)) : {};
  return alarmAcknowledgements.value;
}

async function refreshAlarmAcknowledgements() {
  if (!alarms.value.length) {
    ElMessage.warning("当前没有可查询确认状态的告警");
    return;
  }
  const acknowledgements = await fetchAlarmAcknowledgements(alarms.value);
  alarms.value = mergeAlarmAcknowledgementStates(alarms.value, acknowledgements);
  ElMessage.success("确认状态批量查询完成");
}

async function loadLogs() {
  try {
    logs.value = normalizeLogRows(await getOpsLogs(buildLogQueryParams({ level: logLevel.value, logger: logLogger.value, keyword: logKeyword.value, deviceId: logDeviceId.value, thread: logThread.value, limit: logLimit.value })));
  } catch {
    logs.value = [];
  }
}

function showErrorLogs() {
  logLevel.value = "ERROR";
  void loadLogs();
}

function searchLatestExceptionLogs() {
  const root = asRecord(exceptionStats.value);
  const data = asRecord(root.data);
  const source = Object.keys(data).length ? data : root;
  const recent = extractArray<Record<string, unknown>>(source, ["recent", "items", "records"]);
  if (!recent.length) {
    ElMessage.warning("当前没有最近异常可用于日志定位");
    return;
  }
  logKeyword.value = buildLogSearchFromException(recent[0]);
  logLevel.value = "";
  void loadLogs();
  ElMessage.info("已按最近异常填充日志搜索条件");
}

function downloadLogs(type: "json" | "txt") {
  if (!filteredLogs.value.length) {
    ElMessage.warning("当前没有可导出的日志");
    return;
  }
  const content = type === "json" ? exportLogRowsAsJson(filteredLogs.value) : exportLogRowsAsText(filteredLogs.value);
  const mime = type === "json" ? "application/json;charset=utf-8" : "text/plain;charset=utf-8";
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = buildLogExportFilename(type);
  anchor.click();
  URL.revokeObjectURL(url);
}

async function loadRealtime() {
  if (realtimeDeviceId.value) {
    const response = await getDeviceRealtimeData(realtimeDeviceId.value);
    realtimeRows.value = normalizeRealtimeRows(response, realtimeDeviceId.value);
    return;
  }
  const summaries = normalizeRealtimeRows(await getAllDeviceDataSummaries());
  const deviceIds = Array.from(new Set([
    ...summaries.map((row) => String(row.deviceId || "")).filter(Boolean),
    ...devices.value.map((device) => deviceIdOf(device)).filter(Boolean)
  ]));
  if (deviceIds.length === 0) {
    realtimeRows.value = [];
    return;
  }
  const results = await Promise.allSettled(deviceIds.map(async (deviceId) => normalizeRealtimeRows(await getDeviceRealtimeData(deviceId), deviceId)));
  realtimeRows.value = results.flatMap((result, index) => result.status === "fulfilled" && result.value.length ? result.value : summaries.filter((row) => row.deviceId === deviceIds[index]));
}

async function loadSelectedRealtime() {
  if (!selectedDeviceId.value) return;
  const response = await getDeviceRealtimeData(selectedDeviceId.value);
  selectedRealtimeRows.value = normalizeRealtimeRows(response, selectedDeviceId.value);
}

async function loadSingleRealtime() {
  if (!realtimeSingleDeviceId.value || !realtimeSinglePointId.value.trim()) {
    ElMessage.warning("请先选择设备并填写点位引用");
    return;
  }
  const response = await getPointRealtimeData(realtimeSingleDeviceId.value, realtimeSinglePointId.value.trim());
  realtimeSingleResult.value = normalizeSinglePointRealtimeRow(response) || response;
}

function pickRealtimePoint(row: RealtimePointRow) {
  realtimeSingleDeviceId.value = String(row.deviceId || realtimeDeviceId.value || selectedDeviceId.value || "");
  realtimeSinglePointId.value = String(row.pointId || row.pointCode || "");
  if (realtimeSingleDeviceId.value && realtimeSinglePointId.value) {
    void loadSingleRealtime();
  }
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

async function startSelectedDevice(deviceId: string) {
  const device = devices.value.find((item) => deviceIdOf(item) === deviceId);
  const startAction = resolveDeviceStartMode(device) === "local" ? startLocalDevice : startDevice;
  await startAction(deviceId);
  await loadDevices();
  await loadSelectedRealtime();
}
async function stopSelectedDevice(deviceId: string) { await stopDevice(deviceId); await loadDevices(); await loadSelectedRealtime(); }
async function deleteLocal(deviceId: string) {
  try {
    await ElMessageBox.confirm(`确认删除本地临时设备 ${deviceId}？该操作不会删除远端配置。`, "删除本地设备", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  await deleteLocalDevice(deviceId);
  await loadDevices();
}

async function operateDeviceConfig(deviceId: string, type: DeviceConfigActionType) {
  if (!deviceId) {
    ElMessage.warning("请先选择设备");
    return;
  }
  const option = DEVICE_CONFIG_ACTIONS.find((item) => item.type === type);
  const label = option?.label || "配置操作";
  const confirmText = option?.confirmText || "该操作只影响本地配置缓存。";
  try {
    await ElMessageBox.confirm(`确认对设备 ${deviceId} 执行${label}？${confirmText}`, "确认配置操作", {
      confirmButtonText: "确认执行",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  deviceConfigOperatingId.value = `${type}:${deviceId}`;
  try {
    const response = type === "clear" ? await clearDeviceConfig(deviceId) : await refreshDeviceConfig(deviceId);
    const result = normalizeDeviceConfigActionResult(response, deviceId);
    ElMessage.success(buildDeviceConfigActionMessage(type, result));
    await loadDevices();
    if (selectedDeviceId.value === deviceId) {
      await loadSelectedRealtime();
    }
  } finally {
    deviceConfigOperatingId.value = "";
  }
}

function selectDevice(deviceId: string) { selectedDeviceId.value = deviceId; void loadSelectedRealtime(); }
function openLocalEditor() { editingBundle.value = null; localEditorVisible.value = true; }
async function handleLocalSaved() { localEditorVisible.value = false; await loadDevices(); }

async function exportDeviceConfigData() {
  configFileExporting.value = true;
  try {
    const exportText = normalizeConfigExportText(await exportConfigs());
    const blob = new Blob([exportText], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = buildConfigExportFilename();
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("设备配置数据已导出，可用于点位测试环境导入");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置数据导出失败");
  } finally {
    configFileExporting.value = false;
  }
}

function openConfigImportFile() {
  configImportInput.value?.click();
}

async function handleConfigImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) {
    return;
  }
  if (!file.name.toLowerCase().endsWith(".json")) {
    ElMessage.warning("请选择 JSON 配置文件");
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning("配置文件不能超过 5MB");
    return;
  }
  configFileImporting.value = true;
  try {
    const parsed = parseConfigImportText(await file.text());
    const bundleCount = countConfigImportBundles(parsed);
    if (bundleCount === 0) {
      throw new Error("导入配置包 bundles 不能为空");
    }
    try {
      await ElMessageBox.confirm(`将导入 ${bundleCount} 个设备配置包并刷新设备，请确认当前本地测试配置可被覆盖。`, "导入设备配置数据", {
        confirmButtonText: "确认导入",
        cancelButtonText: "取消",
        type: "warning"
      });
    } catch {
      return;
    }
    await importConfigs(buildConfigImportRequest(parsed, true));
    await refreshAll();
    ElMessage.success(`已导入 ${bundleCount} 个设备配置包`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "设备配置数据导入失败");
  } finally {
    configFileImporting.value = false;
  }
}
async function runDiagnostic() {
  diagnosticRaw.value = buildDiagnosticRaw();
  await loadOverview();
  diagnosticRaw.value = buildDiagnosticRaw();
}

function applyNetworkDevice() {
  fillNetworkFromSelectedDevice();
}

function fillNetworkFromSelectedDevice() {
  if (!networkDeviceId.value && selectedDeviceId.value) {
    networkDeviceId.value = selectedDeviceId.value;
  }
  const device = devices.value.find((item) => deviceIdOf(item) === networkDeviceId.value);
  const target = resolveNetworkTargetFromDevice(device || null);
  networkTarget.value = target.target;
  if (target.port !== undefined) {
    networkPort.value = target.port;
  }
}

function syncNetworkMode() {
  if (networkType.value !== "TCP") {
    networkPort.value = 0;
  } else if (!networkPort.value) {
    const device = devices.value.find((item) => deviceIdOf(item) === networkDeviceId.value);
    networkPort.value = Number(device?.port || 9090);
  }
}

async function runNetwork() {
  let payload;
  try {
    payload = buildNetworkDiagnosticPayload({ type: networkType.value, deviceId: networkDeviceId.value, target: networkTarget.value, port: networkPort.value, timeoutMs: networkTimeout.value });
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "网络检测参数无效");
    return;
  }
  networkOperating.value = true;
  try {
    const result = normalizeNetworkDiagnosticResult(await diagnoseNetwork(payload));
    networkResult.value = result;
    networkHistory.value = appendNetworkHistory(networkHistory.value, result, 10);
  } finally {
    networkOperating.value = false;
  }
}

function downloadNetworkReport() {
  if (!networkHistory.value.length) {
    ElMessage.warning("当前没有可导出的网络检测结果");
    return;
  }
  const blob = new Blob([buildNetworkExportText(networkHistory.value)], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `collector-network-${new Date().toISOString().replace(/[:.]/g, "-")}.txt`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function openAlarmAcknowledgementDialog(alarm: AlarmRow) {
  const alarmId = buildAlarmIdentity(alarm);
  if (!alarmId || alarm.acknowledged) {
    return;
  }
  selectedAlarmForAck.value = alarm;
  alarmAckNote.value = "";
  alarmAckDialogVisible.value = true;
}

function closeAlarmAcknowledgementDialog() {
  alarmAckDialogVisible.value = false;
  selectedAlarmForAck.value = null;
  alarmAckNote.value = "";
}

async function submitAlarmAcknowledgement() {
  const alarm = selectedAlarmForAck.value;
  if (!alarm) {
    return;
  }
  const alarmId = buildAlarmIdentity(alarm);
  acknowledgingAlarmId.value = alarmId;
  try {
    const acknowledgement = await acknowledgeAlarm(alarmId, buildAlarmAckPayload(alarmAckNote.value, alarmId));
    alarmAcknowledgements.value = { ...alarmAcknowledgements.value, [alarmId]: acknowledgement };
    alarms.value = applyAlarmAcknowledgement(alarms.value, alarmId, acknowledgement);
    closeAlarmAcknowledgementDialog();
    ElMessage.success("告警已确认");
  } finally {
    acknowledgingAlarmId.value = "";
  }
}

function locateAlarmLogs(alarm: AlarmRow) {
  const target = buildAlarmTroubleshootTarget(alarm);
  logDeviceId.value = target.deviceId;
  logKeyword.value = target.logKeyword;
  logLevel.value = "";
  switchModule("log");
  void loadLogs();
  ElMessage.info("已按告警信息填充日志搜索条件");
}

function diagnoseAlarmNetwork(alarm: AlarmRow) {
  const deviceId = String(alarm.deviceId || "");
  const device = devices.value.find((item) => deviceIdOf(item) === deviceId);
  const target = buildAlarmTroubleshootTarget(alarm, device || {});
  if (!target.networkTarget) {
    ElMessage.warning("当前告警缺少可用于网络检测的设备地址");
    return;
  }
  networkDeviceId.value = target.deviceId;
  networkTarget.value = target.networkTarget;
  if (target.networkPort !== undefined) {
    networkPort.value = target.networkPort;
    networkType.value = "TCP";
  } else {
    networkType.value = "PING";
  }
  switchModule("network");
  ElMessage.info("已从告警带入网络检测目标");
}

function buildDiagnosticRaw(): Record<string, unknown> {
  return {
    runtime: runtimeStatus.value,
    system: systemResource.value,
    devices: deviceConnectionMetrics.value,
    cache: cacheMetrics.value,
    performance: collectorPerformance.value,
    performanceDetail: performanceDetail.value,
    exceptions: exceptionStats.value,
    storage: storageMetrics.value,
    report: reportMetrics.value,
    summary: configSummary.value
  };
}

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

async function editDevice(device: DeviceInfo) {
  if (isLocalDevice(device)) {
    const deviceId = deviceIdOf(device);
    selectDevice(deviceId);
    try {
      const detail = await getLocalDevice(deviceId);
      const bundle = extractLocalDeviceBundle(detail);
      if (!bundle) {
        throw new Error("本地设备详情缺少可编辑配置");
      }
      editingBundle.value = bundle;
      localEditorVisible.value = true;
    } catch (caught) {
      ElMessage.error(caught instanceof Error ? caught.message : "本地设备详情加载失败");
    }
    return;
  }
  selectDevice(deviceIdOf(device));
  workbenchTab.value = "config";
  activeModule.value = "workbench";
  router.push("/device").catch(() => undefined);
}

function openDeviceDiff(device: DeviceInfo) {
  selectDevice(deviceIdOf(device));
  switchModule("collect");
  ElMessage.info("已切换到采集配置，可查看当前设备相关配置");
}

function openDeviceAlarmHistory(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  alarmDeviceId.value = deviceId;
  switchModule("alarm");
  void loadAlarms();
}

function openDeviceRuntimeStatus(device: DeviceInfo) {
  const deviceId = deviceIdOf(device);
  selectDevice(deviceId);
  switchModule("diag");
  ElMessage.info("已切换到运行设备状态面板");
}

function openSelectedDeviceRuntimeStatus() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceRuntimeStatus(selectedDevice.value);
}

function openSelectedDeviceAlarmHistory() {
  if (!selectedDevice.value) {
    ElMessage.warning("请先选择设备");
    return;
  }
  openDeviceAlarmHistory(selectedDevice.value);
}

function openWorkbenchHistory(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  selectDevice(target.deviceId);
  historySelectedPointRef.value = target.pointRef;
  switchModule("history");
  ElMessage.info(`已切换到历史趋势：${target.pointLabel || target.pointName || target.pointRef}`);
}

function openWorkbenchRealtime(target: { deviceId: string; pointRef: string; pointName?: string; pointLabel?: string }) {
  if (!target.deviceId || !target.pointRef) {
    return;
  }
  selectDevice(target.deviceId);
  realtimeSingleDeviceId.value = target.deviceId;
  realtimeSinglePointId.value = target.pointRef;
  switchModule("realtime");
  void loadSingleRealtime();
  ElMessage.info(`已切换到实时数据：${target.pointLabel || target.pointName || target.pointRef}`);
}

function downloadDiagnosticPackage() {
  const payload = {
    generatedAt: new Date().toISOString(),
    selectedDeviceId: selectedDeviceId.value,
    selectedDevice: selectedDeviceView.value || null,
    overview: buildDiagnosticRaw(),
    alarms: alarms.value.slice(0, 20),
    logs: logs.value.slice(0, 50),
    networkHistory: networkHistory.value.slice(0, 10),
    runtimeSummary: {
      totalDevices: devices.value.length,
      onlineCount: onlineCount.value,
      riskDevices: riskDevices.value.length,
      reportState: reportState.value
    }
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `collector-diagnostic-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function openDeviceOperation(device: DeviceInfo, tab: "config" | "control" | "shadow") {
  selectDevice(deviceIdOf(device));
  workbenchTab.value = tab;
  activeModule.value = "workbench";
  router.push(tab === "control" ? "/control" : (tab === "shadow" ? "/shadow" : "/device")).catch(() => undefined);
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
  const quality = String(valueOf(row, ["qualityLevel", "qualityDescription", "quality", "qualityCode", "status"], "UNKNOWN"));
  if (row.qualityAvailable === false) {
    return "未评估";
  }
  if (row.qualityAcceptable === false || row.processSuccess === false) {
    return quality === "UNKNOWN" ? "异常" : quality;
  }
  switch (quality.toUpperCase()) {
    case "GOOD":
    case "OK":
    case "SUCCESS":
      return "良好";
    case "BAD":
    case "ERROR":
      return "异常";
    default:
      return quality || "未知";
  }
}

function realtimeQualityClass(row: RealtimePointRow): string {
  const quality = String(valueOf(row, ["qualityLevel", "quality", "qualityCode", "status"], "UNKNOWN")).toUpperCase();
  if (row.qualityAvailable === false || row.qualityAcceptable === false || row.processSuccess === false) return "is-bad";
  if (["GOOD", "OK", "SUCCESS", "100"].includes(quality)) return "is-good";
  if (["BAD", "ERROR", "FAILED"].includes(quality)) return "is-bad";
  return "";
}

function realtimeProcessingText(row: RealtimePointRow): string {
  const value = valueOf(row, ["processCostMs", "processingTime", "costMs", "elapsedMs"], "-");
  return typeof value === "number" ? `${value} ms` : String(value || "-");
}

function alarmMessage(alarm: AlarmRow): string {
  return String(alarm.content || alarm.message || alarm.alarmContent || alarm.ruleName || "告警触发");
}

function alarmCurrentValue(alarm: AlarmRow): string {
  const value = valueOf(alarm, ["currentValue", "current_value", "value", "alarmValue", "alarm_value", "rawValue", "raw_value"], "-");
  return value === undefined || value === null || value === "" ? "-" : String(value);
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
