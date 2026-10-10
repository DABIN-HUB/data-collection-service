<template>
  <Teleport to="body">
    <div v-if="modelValue" id="localEditorBackdrop" class="local-editor-backdrop" aria-hidden="true" @click="close(false)"></div>

    <div
      v-if="modelValue"
      id="localDevicePanel"
      class="local-editor local-device-panel local-device-web-dialog"
      role="dialog"
      aria-modal="true"
      :aria-label="editingDeviceId ? '编辑本地临时设备' : '新增本地临时设备'"
      @click.stop
    >
      <header class="local-editor-title">
        <div class="local-title-copy">
          <div class="local-title-line"><span class="device-symbol"><LocalDeviceEditorIcon name="device" /></span><h3 id="localEditorTitle">{{ editingDeviceId ? "编辑本地临时设备" : "新增本地临时设备" }}</h3><span class="pill">本地临时配置</span></div>
          <p>配置通信、点位及上报，一次保存完整设备配置。</p>
        </div>
        <div class="local-editor-title-actions">
          <div class="local-editor-stats">
            <div class="local-editor-stat">
              <strong id="localEditorProtocolText">{{ currentProtocolDisplay }}</strong>
              <span>当前协议</span>
            </div>
            <div class="local-editor-stat">
              <strong id="localEditorPointCount">{{ points.length }}</strong>
              <span>点位数</span>
            </div>
          </div>
          <button id="cancelLocalDeviceBtn" type="button" class="ghost-button" @click="close(false)">关闭 <LocalDeviceEditorIcon name="close" /></button>
        </div>
      </header>

      <nav class="local-editor-tabs" role="tablist" aria-label="新增设备配置分区">
        <button
          v-for="(step, index) in localEditorSteps"
          :key="step.key"
          type="button"
          class="local-editor-tab"
          :data-local-editor-section="step.key"
          role="tab"
          :aria-selected="activeStep === index"
          :aria-controls="`localEditorPane-${step.key}`"
          :class="{ 'is-active': activeStep === index, 'is-complete': completedSteps.has(index) }"
          @click="setActiveStep(index)"
        >
          <LocalDeviceEditorIcon :name="step.key" />
          <span class="local-tab-copy"><strong>{{ step.label }}</strong><small>{{ step.desc }}</small></span>
          <span v-if="step.key === 'points'" class="local-tab-count">{{ points.length }}</span>
        </button>
      </nav>

      <main class="local-editor-body">
        <el-alert v-if="error" :title="error" type="warning" :closable="false" />

        <section v-show="activeStep === 0" id="localEditorPane-setup" role="tabpanel" class="local-editor-pane" data-local-editor-pane="setup">
          <div class="step-grid step-grid-setup">
            <EditorProgressRail :validation-title="validationTitle" :items="localEditorChecklist" />

            <div class="local-setup-stable-column">
              <section class="local-section-card local-setup-card">
                <EditorSectionHeader title="设备信息" subtitle="本地标识与采集节奏" />
                <div class="form-grid setup-identity-grid">
                  <label class="wide-field">设备 ID *<input id="localDeviceId" v-model="deviceId" type="text" readonly placeholder="自动生成 UUID" @change="syncDeviceIdToPoints"><small class="field-description">自动生成；本次编辑不可修改。</small></label>
                  <label class="wide-field">设备名称 *<input id="localDeviceName" v-model="deviceName" type="text" placeholder="本地测试设备"></label>
                  <label class="wide-field"><span class="protocol-label"><span id="localProtocolMetaHelp" class="protocol-meta-anchor"></span><span>协议 *</span></span><select id="localProtocolSelect" v-model="protocol" @change="onProtocolChanged"><option v-for="item in visibleProtocols" :key="item.protocol" :value="item.protocol">{{ item.title || item.protocol }} ({{ item.protocol }})</option></select></label>
                  <label class="adaptive-field">基础采集周期<span class="native-unit-control"><input id="localCollectionInterval" v-model.number="adaptive.baseCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"><span>ms</span></span></label>
                  <label class="adaptive-field">最小采集周期<span class="native-unit-control"><input id="localMinCollectionInterval" v-model.number="adaptive.minCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"><span>ms</span></span></label>
                  <label class="adaptive-field">最大采集周期<span class="native-unit-control"><input id="localMaxCollectionInterval" v-model.number="adaptive.maxCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"><span>ms</span></span></label>
                  <label class="adaptive-field">点位变化阈值<input id="localPointChangeThreshold" v-model.number="adaptive.pointChangeThreshold" type="number" min="0" step="0.01" @change="syncAdaptiveToPoints"></label>
                </div>
              </section>

              <section class="local-section-card local-cloud-target-card">
                <EditorSectionHeader title="云平台身份" subtitle="与“云平台上报”共享同一份身份" />
                <CloudTargetForm :cloud-target="cloudTarget" :topic-preview="cloudTopicPreview" @update-field="updateCloudTargetField" />
              </section>
            </div>

            <section class="local-section-card local-connection-card">
              <EditorSectionHeader title="协议通信参数" :subtitle="`${currentProtocolDisplay} · 按协议字段组织`"><template #actions><span class="pill">{{ connectionFields.length }} 个字段</span></template></EditorSectionHeader>
              <div class="local-connection-body">
                <form id="localConnectionForm" class="dynamic-form" @submit.prevent>
                  <ProtocolDynamicForm v-model="connectionModel" :protocol="protocol" :fields="connectionFields" @validate="connectionErrors = $event" />
                </form>
              </div>
            </section>
          </div>
        </section>

        <section v-show="activeStep === 1" id="localEditorPane-points" role="tabpanel" class="local-editor-pane" data-local-editor-pane="points">
          <div class="step-grid step-grid-master-detail">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader badge="建模概览" title="点位建模" subtitle="点位完整度与地址检查" />
              <div class="metric-stack">
                <MetricItem label="已配置点位" :value="String(points.length)" />
                <MetricItem label="必填项完整度" :value="pointCompletenessText" />
                <MetricItem label="编码唯一性" :value="duplicatePointCode ? `重复：${duplicatePointCode}` : '通过'" :tone="duplicatePointCode ? 'error' : 'ok'" />
                <MetricItem label="地址填写" :value="missingPointAddressCount ? `${missingPointAddressCount} 个待完善` : '已填写'" :tone="missingPointAddressCount ? 'warn' : 'ok'" />
              </div>
              <div class="rail-progress" role="progressbar" aria-label="点位必填项完整度" :aria-valuenow="Number.parseFloat(pointCompletenessText)" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: pointCompletenessText }"></span></div>
              <ul class="hint-list">
                <li>建议先规划点位编码规则。</li>
                <li>双击表格行可快速编辑。</li>
                <li>完整配置由底部统一保存。</li>
              </ul>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card point-list-card">
                <EditorSectionHeader title="采集点位" subtitle="选择行后在下方编辑，修改不会立即提交。">
                  <template #actions>
                  <div class="inline-actions table-actions">
                    <input id="localPointSearch" v-model="pointKeyword" class="compact-select" type="search" placeholder="搜索点位编码 / 名称 / 地址">
                    <select v-model="pointDataTypeFilter" class="compact-select"><option value="">全部类型</option><option v-for="item in pointDataTypes" :key="item" :value="item">{{ item }}</option></select>
                    <select v-model="pointReadWriteFilter" class="compact-select"><option value="">全部读写</option><option v-for="item in readWriteOptions" :key="String(item.value)" :value="String(item.value)">{{ item.label }}</option></select>
                    <button id="addLocalPointBtn" type="button" class="primary-soft" @click="addPoint">新增点位</button>
                  </div>
                  </template>
                </EditorSectionHeader>
                <div class="table-wrap compact point-table-wrap">
                  <table class="point-table editor-table">
                    <thead><tr><th>序号</th><th>点位名称</th><th>点位标识</th><th>数据类型</th><th>寄存器地址</th><th>读写</th><th>缩放</th><th>操作</th></tr></thead>
                    <tbody id="localPointRows">
                      <tr v-for="row in filteredPoints" :key="row.pointCode || row.address || points.indexOf(row)" :class="{ 'is-selected': points.indexOf(row) === selectedPointIndex }" @click="selectPoint(row)" @dblclick="selectPoint(row)">
                        <td>{{ points.indexOf(row) + 1 }}</td>
                        <td :title="row.pointName || row.pointCode || '-'"><button type="button" class="point-select-button" :data-select-local-point="points.indexOf(row)" @click.stop="selectPoint(row)"><strong>{{ row.pointName || row.pointCode || '-' }}</strong></button></td>
                        <td :title="row.pointCode || '-'">{{ row.pointCode || '-' }}</td>
                        <td :title="row.dataType || '-'">{{ row.dataType || '-' }}</td>
                        <td :title="row.address || '-'">{{ row.address || '-' }}</td>
                        <td>{{ row.readWrite || '-' }}</td>
                        <td :title="String(row.scalingFactor ?? '-')">{{ row.scalingFactor ?? '-' }}</td>
                        <td class="action-cell"><div class="row-actions"><button type="button" class="text-action" @click.stop="selectPoint(row)">编辑</button><button type="button" class="text-action" @click.stop="duplicatePoint(row)">复制</button><button type="button" class="text-action danger" @click.stop="removePoint(row)">删除</button></div></td>
                      </tr>
                      <tr v-if="filteredPoints.length === 0"><td colspan="8">{{ pointKeyword ? '没有匹配的点位' : '暂无点位' }}</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section class="local-section-card editor-card point-detail-panel">
                <PointEditorHeader :point="selectedPoint" />
                <div v-if="selectedPoint" class="point-detail-stack">
                  <FieldGroup title="主要字段">
                    <PointFieldGrid layout="four-column" :fields="primaryPointFields" :shared-paths="['cacheEnabled', 'cacheDuration', ...(protocolPointFields.some(field => field.path === 'additionalConfig.byteOrder') ? ['additionalConfig.byteOrder'] : [])]" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
                  </FieldGroup>
                  <details class="advanced-collapse">
                    <summary>高级参数 / 协议扩展 / 只读信息</summary>
                    <div class="advanced-stack">
                      <FieldGroup title="数据处理"><PointFieldGrid layout="four-column" :fields="dataPointFields" :shared-paths="['cacheEnabled', 'cacheDuration', ...(protocolPointFields.some(field => field.path === 'additionalConfig.byteOrder') ? ['additionalConfig.byteOrder'] : [])]" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup title="上报 / 缓存"><PointFieldGrid layout="four-column" :fields="reportPointFields" :shared-paths="['cacheEnabled', 'cacheDuration', ...(protocolPointFields.some(field => field.path === 'additionalConfig.byteOrder') ? ['additionalConfig.byteOrder'] : [])]" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup :title="protocolPointTitle">
                        <div class="protocol-point-note"><p v-if="protocolPointNotes.addressHints.length">当前协议地址示例：<code v-for="hint in protocolPointNotes.addressHints" :key="hint">{{ hint }}</code></p><p v-for="message in protocolPointNotes.messages" :key="message">{{ message }}</p></div>
                        <PointFieldGrid v-if="protocolPointFields.length" layout="four-column" :fields="protocolPointFields" :shared-paths="['cacheEnabled', 'cacheDuration', ...(protocolPointFields.some(field => field.path === 'additionalConfig.byteOrder') ? ['additionalConfig.byteOrder'] : [])]" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
                        <p v-else class="field-description">当前协议没有额外的点位扩展字段。</p>
                      </FieldGroup>
                      <FieldGroup title="只读信息"><div v-if="readonlyItems.length" class="readonly-grid"><div v-for="item in readonlyItems" :key="item.label" class="readonly-card"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></div></div><p v-else class="field-description">当前点位没有额外只读运行态信息。</p></FieldGroup>
                    </div>
                  </details>
                </div>
              </section>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 2" id="localEditorPane-alarm" role="tabpanel" class="local-editor-pane" data-local-editor-pane="alarm">
          <div class="step-grid step-grid-master-detail">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader badge="规则概览" title="告警规则" subtitle="触发条件与事件策略" />
              <div class="metric-stack">
                <MetricItem label="规则总数" :value="String(alarmRuleRows.length)" />
                <MetricItem label="已启用" :value="String(enabledAlarmRuleCount)" tone="ok" />
                <MetricItem label="严重" :value="String(alarmLevelCounts.CRITICAL || 0)" tone="error" />
                <MetricItem label="重要/错误" :value="String((alarmLevelCounts.ERROR || 0) + (alarmLevelCounts.WARNING || 0))" tone="warn" />
                <MetricItem label="事件最小间隔" :value="eventIntervalSummary" />
              </div>
              <p class="rail-note">点位告警开关与单条规则启用状态分别保留；触发预览只描述条件，不执行告警。</p>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card alarm-list-card" :class="{ 'is-empty': alarmRuleRows.length === 0 }">
                <EditorSectionHeader title="点位告警规则" subtitle="同一点位可配置多条规则。">
                  <template #actions>
                  <div class="inline-actions table-actions">
                    <select v-model="alarmPointFilter" class="compact-select"><option value="">全部点位</option><option v-for="point in points" :key="point.pointCode || point.pointId" :value="point.pointCode || point.pointId || ''">{{ point.pointName || point.pointCode }}</option></select>
                    <select v-model="alarmLevelFilter" class="compact-select"><option value="">全部级别</option><option v-for="level in alarmLevels" :key="level.value" :value="level.value">{{ level.label }}</option></select>
                    <select v-model="alarmEnabledFilter" class="compact-select"><option value="">全部状态</option><option value="true">启用</option><option value="false">禁用</option></select>
                    <button type="button" class="primary-soft" @click="addAlarmRuleForCurrent">新增规则</button>
                  </div>
                  </template>
                </EditorSectionHeader>
                <div v-if="alarmRuleRows.length === 0" class="empty-state alarm-empty-state">
                  <strong>暂无告警规则</strong>
                  <span>选择已有点位或创建第一条规则，即可配置触发条件与告警级别。</span>
                  <button type="button" class="primary-soft" @click="addAlarmRuleForCurrent">新增规则</button>
                </div>
                <div v-else class="table-wrap compact alarm-table-wrap">
                  <table class="point-table editor-table">
                    <thead><tr><th>关联点位</th><th>规则名称</th><th>运算符</th><th>阈值</th><th>持续时间</th><th>告警级别</th><th>启用状态</th><th>操作</th></tr></thead>
                    <tbody>
                      <tr v-for="row in filteredAlarmRows" :key="`${row.pointIndex}-${row.ruleIndex}`" :class="{ 'is-selected': row.pointIndex === selectedPointIndex && row.ruleIndex === selectedAlarmRuleIndex }" @click="selectAlarmRow(row)">
                        <td :title="row.point.pointName || row.point.pointCode">{{ row.point.pointName || row.point.pointCode }}</td><td :title="row.rule.ruleName || row.rule.ruleId || '未命名规则'">{{ row.rule.ruleName || row.rule.ruleId || '未命名规则' }}</td><td>{{ row.rule.operator || '-' }}</td><td :title="alarmThresholdText(row)">{{ alarmThresholdText(row) }}</td><td :title="`${row.rule.duration ?? '-'} s`">{{ row.rule.duration ?? '-' }} s</td><td><span class="level-badge" :class="alarmLevelClass(row.rule.level)">{{ alarmLevelLabel(row.rule.level) }}</span></td><td><span class="state-badge" :class="row.rule.enabled === false ? 'is-off' : 'is-on'">{{ row.rule.enabled === false ? '禁用' : '启用' }}</span></td><td class="action-cell"><div class="row-actions"><button type="button" class="text-action" @click.stop="selectAlarmRow(row)">编辑</button><button type="button" class="text-action danger" @click.stop="removeAlarmRuleAt(row.pointIndex, row.ruleIndex)">删除</button></div></td>
                      </tr>
                      <tr v-if="filteredAlarmRows.length === 0"><td colspan="8">暂无告警规则，请选择点位后新增。</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section v-if="alarmRuleRows.length" class="local-section-card editor-card alarm-editor-card">
                <PointEditorHeader :point="selectedPoint" title="规则配置" />
                <div v-if="selectedPoint && currentAlarmRule" class="alarm-rule-form">
                  <div class="form-grid alarm-rule-grid">
                    <label class="alarm-toggle-row">启用该点位告警<el-switch :model-value="Boolean(selectedPoint.alarmEnabled)" @update:model-value="updateSelectedPath('alarmEnabled', $event ? 1 : 0)" /></label>
                    <label class="alarm-identifier-field">规则 ID<el-input :model-value="String(currentAlarmRule.ruleId || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleId', $event)" /></label>
                    <label class="alarm-name-field">规则名称<el-input :model-value="String(currentAlarmRule.ruleName || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleName', $event)" /></label>
                    <label>运算符<el-select :model-value="String(currentAlarmRule.operator || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'operator', $event)"><el-option v-for="operator in alarmOperators" :key="operator" :label="operator" :value="operator" /></el-select></label>
                    <label>阈值<el-input-number :model-value="toNumber(currentAlarmRule.threshold)" controls-position="right" :step="0.0001" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'threshold', $event)" /></label>
                    <label>持续时间(s)<el-input-number :model-value="toNumber(currentAlarmRule.duration)" controls-position="right" :step="1" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'duration', $event)" /></label>
                    <label>告警级别<el-select :model-value="String(currentAlarmRule.level || '')" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'level', $event)"><el-option v-for="level in alarmLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></label>
                    <label>启用<el-select :model-value="currentAlarmRule.enabled === undefined ? '' : String(Boolean(currentAlarmRule.enabled))" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'enabled', parseBooleanOption($event))"><el-option label="是" value="true" /><el-option label="否" value="false" /></el-select></label>
                    <label class="wide-field">描述<el-input :model-value="String(currentAlarmRule.description || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'description', $event)" /></label>
                  </div>
                  <div class="alarm-condition-hint" :title="alarmTriggerHint"><span>触发预览</span><strong v-for="(part, partIndex) in alarmLogicParts" :key="part" :class="partIndex === alarmLogicParts.length - 1 ? alarmLevelClass(currentAlarmRule.level) : ''">{{ part }}</strong></div>
                </div>
                <div v-else class="empty-state"><strong>暂无可编辑规则</strong><span>选择已有规则，或点击“新增规则”。</span></div>
              </section>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 3" id="localEditorPane-cloud" role="tabpanel" class="local-editor-pane" data-local-editor-pane="cloud">
          <div class="step-grid step-grid-cloud">
            <aside class="local-section-card overview-card cloud-sidebar">
              <EditorSectionHeader title="云平台身份" subtitle="与基础连接页同步；不是第二份配置" />
              <CloudTargetForm id-prefix="cloud-" :cloud-target="cloudTarget" :topic-preview="cloudTopicPreview" @update-field="updateCloudTargetField" />
              <FieldGroup title="上报策略">
                <div class="metric-stack">
                  <MetricItem label="周期上报" :value="`${adaptive.baseCollectionInterval} ms`" />
                  <MetricItem label="变化阈值" :value="String(adaptive.pointChangeThreshold)" />
                  <MetricItem label="最小变化间隔" :value="reportStrategySummary.changeMinInterval" />
                  <MetricItem label="事件最小间隔" :value="reportStrategySummary.eventMinInterval" />
                  <MetricItem label="缓存点位" :value="reportStrategySummary.cache" />
                </div>
              </FieldGroup>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card mapping-list-card">
                <EditorSectionHeader title="云端属性映射" subtitle="本地标识保持不变，云端属性在上报边界映射。"><template #actions><span class="pill">{{ totalReportFieldCount }} 个上报属性</span></template></EditorSectionHeader>
                <div class="table-wrap compact mapping-table-wrap">
                  <table class="point-table editor-table cloud-point-table">
                    <thead><tr><th>序号</th><th>点位名称</th><th>本地标识</th><th>云端属性编码</th><th>上报类型</th><th>转换规则</th><th>单位</th><th>启用状态</th><th>操作</th></tr></thead>
                    <tbody id="localCloudRows">
                      <tr v-for="(row, index) in points" :key="row.pointCode || row.address || index" :class="{ 'is-selected': index === selectedPointIndex }" @click="selectPoint(row)">
                        <td>{{ index + 1 }}</td><td :title="row.pointName || row.pointCode || '-'">{{ row.pointName || row.pointCode || '-' }}</td><td :title="row.pointCode || '-'">{{ row.pointCode || '-' }}</td><td :title="String(row.additionalConfig?.reportField || '-')">{{ row.additionalConfig?.reportField || '-' }}</td><td>{{ row.additionalConfig?.eventEnabled ? '属性+事件' : '属性' }}</td><td :title="transformRuleText(row)">{{ transformRuleText(row) }}</td><td :title="row.unit || '-'">{{ row.unit || '-' }}</td><td :title="cloudPointStatus(row, cloudTarget)"><span class="state-badge" :class="cloudPointStatus(row, cloudTarget) === '可上报' ? 'is-on' : 'is-off'">{{ cloudPointStatus(row, cloudTarget) }}</span></td><td class="action-cell"><div class="row-actions"><button type="button" class="text-action" @click.stop="selectPoint(row)">编辑</button></div></td>
                      </tr>
                      <tr v-if="points.length === 0"><td colspan="9">暂无点位</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>
              <div class="cloud-bottom-grid">
                <section class="local-section-card editor-card">
                  <PointEditorHeader :point="selectedPoint" title="属性映射编辑" />
                  <FieldGroup v-if="selectedPoint" title="云端属性映射"><PointFieldGrid layout="four-column" :fields="cloudReportFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                  <FieldGroup title="事件预览"><ul class="hint-list"><li v-for="item in eventMappingPreview" :key="item">{{ item }}</li><li v-if="eventMappingPreview.length === 0">暂无事件上报配置</li></ul></FieldGroup>
                </section>
                <section class="local-section-card payload-card">
                  <EditorSectionHeader title="Payload 预览" subtitle="由当前配置构造；占位值不代表真实采集值。">
                    <template #actions><select v-model="payloadPreviewMode" class="compact-select"><option value="property">属性上报</option><option value="event">事件上报</option><option value="full">完整配置摘要</option></select></template>
                  </EditorSectionHeader>
                  <pre class="json-preview">{{ payloadPreview }}</pre>
                </section>
              </div>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 4" id="localEditorPane-json" role="tabpanel" class="local-editor-pane" data-local-editor-pane="json">
          <div class="step-grid step-grid-json">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader title="配置章节" subtitle="选择关注的配置范围" />
              <button v-for="item in jsonSections" :key="item.key" type="button" class="json-nav-item" :class="{ 'is-active': jsonSection === item.key, 'is-modified': item.status === '有修改' }" @click="jsonSection = item.key"><span>{{ item.label }}</span><small>{{ item.status }}</small></button>
              <p class="json-nav-note">编辑器始终展示完整设备配置；左侧章节用于标记关注范围。</p>
            </aside>
            <section class="local-section-card json-editor-card">
              <EditorSectionHeader title="完整设备配置" subtitle="草稿需应用后，才会写入结构化配置。">
                <template #actions><div class="inline-actions table-actions"><button type="button" @click="formatConfigJson">格式化</button><button type="button" class="primary-soft" @click="applyConfigJson">校验并应用</button><button type="button" @click="syncJsonFromState">恢复当前配置</button></div></template>
              </EditorSectionHeader>
              <div class="json-editor-toolbar"><span>完整 JSON / {{ jsonSections.find(item => item.key === jsonSection)?.label }}</span><span>UTF-8</span></div>
              <textarea id="localPointsJson" aria-label="完整设备配置 JSON" v-model="configJson" class="point-json-textarea" spellcheck="false"></textarea>
              <label class="change-note-label">变更说明（仅用于本次编辑备注）<textarea v-model="changeDescription" class="change-note" placeholder="可选：记录本次配置调整目的"></textarea></label>
            </section>
            <aside class="local-section-card schema-card">
              <EditorSectionHeader title="配置校验" subtitle="已应用结构化配置的校验，不代表设备连通。" />
              <div class="json-validation-metrics"><MetricItem label="错误" :value="String(jsonValidation.errors.length)" :tone="jsonValidation.errors.length ? 'error' : 'ok'" /><MetricItem label="警告" :value="String(jsonValidation.warnings.length)" :tone="jsonValidation.warnings.length ? 'warn' : 'ok'" /><MetricItem label="章节" :value="String(jsonSections.length)" /><MetricItem label="点位" :value="String(points.length)" /></div>
              <ul class="validation-list"><li v-for="item in jsonValidation.errors" :key="item" class="is-error">{{ item }}</li><li v-for="item in jsonValidation.warnings" :key="item" class="is-warn">{{ item }}</li><li v-if="!jsonValidation.errors.length && !jsonValidation.warnings.length" class="is-ok">已应用结构化配置校验通过</li></ul>
              <p class="json-validation-note">统计与提示只反映已应用状态，未应用的 JSON 草稿不计入。保存不会自动应用草稿；再次进入本页会从当前配置恢复内容。</p>
              <div class="schema-tabs"><button v-for="tab in schemaTabs" :key="tab.key" type="button" :class="{ 'is-active': schemaTab === tab.key }" @click="schemaTab = tab.key">{{ tab.label }}</button></div>
              <p class="schema-reference">{{ schemaTab === 'protocol' ? '协议字段类型与必填标记来自协议元数据。' : '字段参考：沿用现有界面的结构与必填说明，非完整 JSON Schema；保存仍按原规则校验。' }}</p>
              <div class="schema-table-wrap"><table class="schema-table"><thead><tr><th>字段 / 说明</th><th>{{ schemaTab === 'protocol' ? '类型' : '所属结构' }}</th><th>必填</th></tr></thead><tbody><tr v-for="row in activeSchemaRows" :key="row.field"><td>{{ row.field }}<small>{{ row.description }}</small></td><td>{{ row.type }}</td><td>{{ row.required ? '是' : '否' }}</td></tr></tbody></table></div>
              <FieldGroup title="配置摘要"><div class="metric-stack"><MetricItem label="协议类型" :value="protocol" /><MetricItem label="设备地址" :value="connectionAddressSummary" /><MetricItem label="上报平台" :value="cloudTargetSummary({} as DataPoint, cloudTarget)" /><MetricItem label="告警规则" :value="String(alarmRuleRows.length)" /><MetricItem label="编辑备注" :value="changeDescription ? '有说明' : '未填写'" /></div></FieldGroup>
            </aside>
          </div>
        </section>
      </main>

      <footer class="local-options local-editor-footer">
        <div class="local-footer-options"><label class="check-line"><input id="localOverwrite" v-model="overwrite" type="checkbox"> 覆盖已有本地临时设备</label>
        <label class="check-line"><input id="localStartAfterSave" v-model="startAfterSave" type="checkbox"> 保存后立即本地启动</label></div>
        <div class="local-step-actions">
          <button id="localEditorPrevBtn" type="button" :disabled="activeStep === 0" @click="moveStep(-1)">上一步</button>
          <button id="localEditorNextBtn" type="button" @click="activeStep === localEditorSteps.length - 1 ? save() : moveStep(1)">{{ activeStep === localEditorSteps.length - 1 ? "完成配置" : "下一步" }}</button>
          <button id="saveLocalDeviceBtn" type="button" class="primary" :disabled="saving" @click="save">{{ saving ? "保存中..." : "保存并测试" }}</button>
        </div>
        <p class="local-footer-note">保存配置；勾选后请求启动，不执行独立连接测试。</p>
      </footer>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, reactive, ref, watch, type PropType } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch, ElOption, ElMessage, ElMessageBox } from "element-plus";

import { createLocalDevice, updateLocalDevice } from "@/api/config.api";
import { getProtocol } from "@/api/protocol.api";
import ProtocolDynamicForm from "./LocalDeviceProtocolForm.vue";
import CloudTargetForm from "./LocalDeviceCloudTargetForm.vue";
import PointFieldGrid from "./LocalDevicePointFieldGrid.vue";
import LocalDeviceEditorIcon from "./LocalDeviceEditorIcon.vue";
import { buildConnectionPayload, buildProtocolInitialModel, extractProtocolModel, getPathValue, setPathValue, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import { buildLocalDevicePayload, buildProtocolPointNotes, DEFAULT_ADAPTIVE_CONFIG, validateLocalDeviceDraft, type AdaptiveConfig, type CloudTargetConfig, type LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import { buildReadonlyItems, createUniqueCode, alarmRules, parseBooleanOption, parseFieldValue, parsePointsJson, serializeAlarmRules, statusLabel, toNumber, type AlarmRule, type FieldValueType } from "@/features/point/utils/point-draft-utils";
import { buildLocalEditorChecklist, createLocalDeviceId, resolveEditorDeviceId, buildPointModelingOverview, cloneData, cloudPointStatus, cloudTargetSummary, countReportFields, defaultPointTemplate, firstPointValue, hasValue, isOpcUaProtocol, isPlainObject, normalizeCloudTarget, normalizeInitialPoints, sanitizePointForSave } from "@/features/device/utils/local-device-editor-utils";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

type ChecklistState = "ok" | "warn" | "error";
type FieldControl = "text" | "number" | "select" | "switch";
type JsonSectionKey = "connection" | "report" | "alarm" | "debug" | "metadata";
type SchemaTabKey = "protocol" | "point" | "report" | "alarm" | "metadata";

interface SelectOption { label: string; value: string | number | boolean }
interface PointEditorField { path: string; label: string; control?: FieldControl; valueType?: FieldValueType; options?: SelectOption[]; required?: boolean; description?: string; fullWidth?: boolean; disabled?: boolean; step?: number; span?: number }
interface AlarmRuleRow { point: DataPoint; pointIndex: number; rule: AlarmRule; ruleIndex: number }
interface SchemaRow { field: string; type: string; required: boolean; description: string }

const EditorSectionHeader = defineComponent({
  name: "EditorSectionHeader",
  props: {
    badge: { type: String, default: "" },
    title: { type: String, required: true },
    subtitle: { type: String, default: "" }
  },
  setup(props, { slots }) {
    return () => h("header", { class: "editor-section-header" }, [
      h("div", { class: "editor-section-heading" }, [
        props.badge ? h("span", { class: "editor-section-badge" }, props.badge) : null,
        h("h3", { class: "editor-section-title" }, props.title),
        props.subtitle ? h("span", { class: "editor-section-subtitle", title: props.subtitle }, props.subtitle) : null
      ]),
      h("div", { class: "editor-section-actions" }, slots.actions?.())
    ]);
  }
});

const EditorProgressRail = defineComponent({
  name: "EditorProgressRail",
  props: { validationTitle: { type: String, required: true }, items: { type: Array as PropType<Array<{ label: string; state: ChecklistState }>>, required: true } },
  setup(props) {
    return () => h("aside", { class: "local-section-card overview-card progress-rail" }, [
      h(EditorSectionHeader, { title: "配置检查", subtitle: props.validationTitle }),
      h("ol", { id: "localEditorChecklist", class: "local-checklist" }, props.items.map((item) => h("li", { class: [`is-${item.state}`] }, [h("span", { class: "status-dot" }), h("span", item.label)]))),
      h("p", { class: "rail-note" }, "保存会提交完整设备配置。勾选“立即本地启动”后才会请求启动，启动受理不等于采集就绪。")
    ]);
  }
});

const MetricItem = defineComponent({
  name: "MetricItem",
  props: { label: { type: String, required: true }, value: { type: String, required: true }, tone: { type: String, default: "" } },
  setup(props) { return () => h("div", { class: ["metric-item", props.tone ? `is-${props.tone}` : ""] }, [h("span", props.label), h("strong", props.value)]); }
});

const FieldGroup = defineComponent({ name: "FieldGroup", props: { title: { type: String, required: true } }, setup(props, { slots }) { return () => h("section", { class: "field-group field-group-wide" }, [h("h3", props.title), slots.default?.()]); } });

const PointEditorHeader = defineComponent({
  name: "PointEditorHeader",
  props: { point: { type: Object as PropType<DataPoint | null>, default: null }, title: { type: String, default: "点位配置" } },
  setup(props) {
    return () => {
      if (!props.point) {
        return h("div", { class: "empty-state" }, [h("strong", "暂无选中的点位"), h("span", "先新增一个点位，或从列表选择已有点位。")]);
      }
      const pointName = props.point.pointName || props.point.pointCode || "未命名点位";
      const meta = `${props.point.pointCode || "-"} · ${props.point.dataType || "-"} · ${props.point.address || "未设置地址"} · ${props.point.readWrite || "-"}`;
      return h("header", { class: "editor-section-header editor-object-header" }, [
        h("div", { class: "editor-section-heading editor-object-heading" }, [
          h("span", { class: "editor-section-badge editor-object-badge" }, "当前点位"),
          h("h3", { class: "editor-section-title editor-object-title" }, props.title),
          h("strong", { class: "editor-object-name" }, pointName),
          h("span", { class: "editor-section-subtitle editor-object-meta", title: `${pointName} | ${meta}` }, meta)
        ]),
        h("div", { class: "editor-section-actions editor-object-actions" }, [h("span", { class: ["pill", statusLabel(props.point.status) === "启用" ? "is-on" : "is-off"] }, statusLabel(props.point.status))])
      ]);
    };
  }
});

const props = defineProps<{ modelValue: boolean; editingBundle?: LocalDeviceBundle | null; protocols: ProtocolSchema[] }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [deviceId: string] }>();

const localEditorSteps = [
  { key: "setup", no: "01", label: "基础连接", desc: "设备通信与连接参数配置" },
  { key: "points", no: "02", label: "点位建模", desc: "采集点位定义与建模" },
  { key: "alarm", no: "03", label: "告警规则", desc: "点位告警策略配置" },
  { key: "cloud", no: "04", label: "云平台上报", desc: "云端映射与数据上报" },
  { key: "json", no: "05", label: "JSON 高级", desc: "高级配置与自定义扩展" }
] as const;

const activeStep = ref(0);
const completedSteps = ref(new Set<number>());
const saving = ref(false);
let editorSession = 0;
let schemaGeneration = 0;
let savedInSession = false;
const error = ref("");
const editingDeviceId = ref("");
const deviceId = ref("");
const deviceName = ref("");
const protocol = ref("MODBUS_TCP");
const overwrite = ref(false);
const startAfterSave = ref(false);
const connectionModel = ref<ProtocolFormModel>({});
const connectionErrors = ref<string[]>([]);
const protocolDetails = ref<Record<string, ProtocolSchema>>({});
const points = ref<DataPoint[]>([]);
const selectedPointIndex = ref(0);
const selectedAlarmRuleIndex = ref(0);
const pointKeyword = ref("");
const pointDataTypeFilter = ref("");
const pointReadWriteFilter = ref("");
const alarmPointFilter = ref("");
const alarmLevelFilter = ref("");
const alarmEnabledFilter = ref("");
const configJson = ref("{}");
const changeDescription = ref("");
const jsonSection = ref<JsonSectionKey>("connection");
const schemaTab = ref<SchemaTabKey>("protocol");
const payloadPreviewMode = ref<"property" | "event" | "full">("property");

const adaptive = reactive<AdaptiveConfig>({ ...DEFAULT_ADAPTIVE_CONFIG });
const cloudTarget = reactive<CloudTargetConfig>({ enabled: false, deviceType: "SUB_DEVICE", topologyEnabled: true });

const booleanOptions: SelectOption[] = [{ label: "是", value: true }, { label: "否", value: false }];
const enableOptions: SelectOption[] = [{ label: "启用", value: 1 }, { label: "禁用", value: 0 }];
const readWriteOptions: SelectOption[] = [{ label: "只读 R", value: "R" }, { label: "只写 W", value: "W" }, { label: "读写 RW", value: "RW" }];
const collectionModeOptions: SelectOption[] = [{ label: "轮询", value: "POLLING" }, { label: "订阅", value: "SUBSCRIPTION" }, { label: "事件", value: "EVENT" }];
const alarmOperators = [">", ">=", "<", "<=", "==", "!="];
const alarmLevels = [{ label: "信息", value: "INFO" }, { label: "警告", value: "WARNING" }, { label: "错误", value: "ERROR" }, { label: "严重", value: "CRITICAL" }];
const schemaTabs: Array<{ key: SchemaTabKey; label: string }> = [{ key: "protocol", label: "协议配置" }, { key: "point", label: "点位配置" }, { key: "report", label: "上报配置" }, { key: "alarm", label: "告警配置" }, { key: "metadata", label: "元数据" }];

const visibleProtocols = computed(() => props.protocols.filter((item) => item.protocol));
const protocolSchema = computed(() => protocolDetails.value[protocol.value] || props.protocols.find((item) => item.protocol === protocol.value) || null);
const connectionFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.connectionFields || []);
const pointFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.pointFields || []);
const pointDataTypes = computed(() => protocolSchema.value?.dataTypes?.length ? protocolSchema.value.dataTypes : ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"]);
const currentProtocolDisplay = computed(() => protocolSchema.value?.title || protocol.value.replace(/_/g, " ").replace(/\bTcp\b/i, "TCP"));
const selectedPoint = computed<DataPoint | null>(() => points.value[selectedPointIndex.value] || null);
const pointModelingOverview = computed(() => buildPointModelingOverview(points.value));
const duplicatePointCode = computed(() => pointModelingOverview.value.duplicatePointCode);
const missingPointAddressCount = computed(() => pointModelingOverview.value.missingPointAddressCount);
const pointCompletenessText = computed(() => pointModelingOverview.value.completenessText);
const filteredPoints = computed(() => {
  const keyword = pointKeyword.value.trim().toLowerCase();
  return points.value.filter((point) => {
    const keywordOk = !keyword || [point.pointCode, point.pointName, point.address].some((value) => String(value || "").toLowerCase().includes(keyword));
    const typeOk = !pointDataTypeFilter.value || point.dataType === pointDataTypeFilter.value;
    const rwOk = !pointReadWriteFilter.value || point.readWrite === pointReadWriteFilter.value;
    return keywordOk && typeOk && rwOk;
  });
});
const cloudTopicPreview = computed(() => cloudTarget.enabled && cloudTarget.productKey && cloudTarget.deviceName ? `/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post` : "未启用云上报或云身份不完整");
const totalReportFieldCount = computed(() => countReportFields(points.value));
const localEditorChecklist = computed<Array<{ label: string; state: ChecklistState }>>(() => {
  return buildLocalEditorChecklist({
    deviceId: deviceId.value,
    deviceName: deviceName.value,
    connectionErrors: connectionErrors.value,
    points: points.value,
    totalReportFieldCount: totalReportFieldCount.value,
    cloudTarget: { ...cloudTarget }
  });
});
const validationTitle = computed(() => {
  const errorCount = localEditorChecklist.value.filter((item) => item.state === "error").length;
  const warnCount = localEditorChecklist.value.filter((item) => item.state === "warn").length;
  if (errorCount > 0) return `${errorCount} 个必填项待处理`;
  return warnCount > 0 ? `${warnCount} 个建议项可完善` : "必填配置已完成";
});
const readonlyItems = computed(() => buildReadonlyItems(selectedPoint.value));
const primaryPointFields = computed<PointEditorField[]>(() => [
  { path: "pointName", label: "点位名称", required: true },
  { path: "pointCode", label: "点位标识", required: true, description: "修改点位标识时，云端属性未单独配置则同步更新。" },
  { path: "dataType", label: "数据类型", control: "select", options: pointDataTypes.value.map((value) => ({ label: value, value })) },
  { path: "address", label: "寄存器地址", required: true },
  { path: "readWrite", label: "读写类型", control: "select", options: readWriteOptions },
  { path: "collectionMode", label: "采集方式", control: "select", options: collectionModeOptions },
  { path: "scalingFactor", label: "缩放系数", control: "number", valueType: "number", step: 0.0001 },
  { path: "offset", label: "偏移量", control: "number", valueType: "number", step: 0.0001 },
  { path: "unit", label: "工程单位" },
  { path: "additionalConfig.readCount", label: "读取数量", control: "number", valueType: "integer", step: 1 },
  { path: "additionalConfig.byteOrder", label: "字节序" },
  { path: "precision", label: "小数位", control: "number", valueType: "integer", step: 1 },
  { path: "pointChangeThreshold", label: "变化上报阈值", control: "number", valueType: "number", step: 0.0001 },
  { path: "remark", label: "点位描述", fullWidth: true }
]);
const dataPointFields = computed<PointEditorField[]>(() => [{ path: "deadband", label: "死区", control: "number", valueType: "number", step: 0.0001 }, { path: "minValue", label: "最小值", control: "number", valueType: "number", step: 0.0001 }, { path: "maxValue", label: "最大值", control: "number", valueType: "number", step: 0.0001 }, { path: "priority", label: "优先级", control: "number", valueType: "integer", step: 1 }, { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions }, { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }, { path: "status", label: "启用状态", control: "select", valueType: "integer", options: enableOptions }]);
const reportPointFields = computed<PointEditorField[]>(() => [{ path: "additionalConfig.reportEnabled", label: "参与设备上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.reportField", label: "云端属性编码" }, { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: 0.0001 }, { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions }, { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }]);
const cloudReportFields = computed<PointEditorField[]>(() => [{ path: "additionalConfig.reportEnabled", label: "启用上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.reportField", label: "云端属性编码", description: "属性标识：reportField" }, { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: 0.0001 }, { path: "additionalConfig.changeMinIntervalMs", label: "最小变化间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.streamEnabled", label: "实时流", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.historyEnabled", label: "历史", control: "select", valueType: "boolean", options: booleanOptions }]);
const protocolPointFields = computed<PointEditorField[]>(() => pointFields.value.map((field) => ({ path: protocolPointFieldPath(field), label: field.label || field.name, required: field.required, control: field.type === "boolean" ? "switch" : field.options?.length ? "select" : (field.type === "number" || field.type === "integer") ? "number" : "text", valueType: field.type === "boolean" ? "boolean" : field.type === "number" ? "number" : field.type === "integer" ? "integer" : "string", options: field.options?.map((option) => ({ label: option, value: option })), description: field.description, span: protocolPointFieldSpan(field) })));
const protocolPointTitle = computed(() => protocol.value === "MODBUS_TCP" || protocol.value === "MODBUS_RTU" ? "协议扩展（Modbus 的 dataType 会直接影响取值长度和解码）" : "协议扩展");
const protocolPointNotes = computed(() => buildProtocolPointNotes(protocol.value, protocolSchema.value?.pointAddressHints || [], pointFields.value.length));
const alarmRuleRows = computed<AlarmRuleRow[]>(() => points.value.flatMap((point, pointIndex) => alarmRules(point).map((rule, ruleIndex) => ({ point, pointIndex, rule, ruleIndex }))));
const filteredAlarmRows = computed(() => alarmRuleRows.value.filter((row) => (!alarmPointFilter.value || row.point.pointCode === alarmPointFilter.value || row.point.pointId === alarmPointFilter.value) && (!alarmLevelFilter.value || row.rule.level === alarmLevelFilter.value) && (!alarmEnabledFilter.value || String(row.rule.enabled !== false) === alarmEnabledFilter.value)));
const enabledAlarmRuleCount = computed(() => alarmRuleRows.value.filter((row) => row.rule.enabled !== false).length);
const alarmLevelCounts = computed<Record<string, number>>(() => alarmRuleRows.value.reduce<Record<string, number>>((acc, row) => { const level = String(row.rule.level || "UNSET"); acc[level] = (acc[level] || 0) + 1; return acc; }, {}));
const currentAlarmRule = computed(() => selectedPoint.value ? alarmRules(selectedPoint.value)[selectedAlarmRuleIndex.value] || null : null);
const alarmLogicParts = computed(() => {
  if (!currentAlarmRule.value || !selectedPoint.value) return ["未选择规则"];
  const unit = selectedPoint.value.unit ? String(selectedPoint.value.unit) : "";
  return [
    `${selectedPoint.value.pointName || selectedPoint.value.pointCode || "点位"} ${selectedPoint.value.pointCode || "-"}`,
    `${currentAlarmRule.value.operator || "?"} ${currentAlarmRule.value.threshold ?? "?"}${unit}`,
    `持续 ${currentAlarmRule.value.duration ?? 0} 秒`,
    `${alarmLevelLabel(currentAlarmRule.value.level)}告警`
  ];
});
const alarmTriggerHint = computed(() => alarmLogicParts.value.join(" · "));
const eventIntervalSummary = computed(() => minPointAdditionalNumber("eventMinIntervalMs"));
const reportStrategySummary = computed(() => ({ changeMinInterval: minPointAdditionalNumber("changeMinIntervalMs"), eventMinInterval: minPointAdditionalNumber("eventMinIntervalMs"), cache: `${points.value.filter((point) => Number(point.cacheEnabled ?? 0) !== 0).length}/${points.value.length}` }));
const eventMappingPreview = computed(() => points.value.filter((point) => point.alarmEnabled || point.additionalConfig?.eventEnabled).map((point) => `${point.pointName || point.pointCode}: ${point.alarmEnabled ? "告警事件" : "点位事件"} → ${point.additionalConfig?.reportField || point.pointCode || "未配置云端属性"}`));
const payloadPreview = computed(() => JSON.stringify(buildPayloadPreview(payloadPreviewMode.value), null, 2));
const jsonSections = computed(() => [
  { key: "connection" as const, label: "采集参数扩展", status: Object.keys(connectionModel.value).length ? "已配置" : "未配置" },
  { key: "report" as const, label: "上报扩展", status: totalReportFieldCount.value ? "已配置" : "未配置" },
  { key: "alarm" as const, label: "告警扩展", status: alarmRuleRows.value.length ? "已配置" : "未配置" },
  { key: "debug" as const, label: "调试开关", status: "未配置" },
  { key: "metadata" as const, label: "自定义元数据", status: changeDescription.value ? "有修改" : "未配置" }
]);
const jsonValidation = computed(() => validateConfigSnapshot(buildConfigSnapshot()));
const activeSchemaRows = computed<SchemaRow[]>(() => {
  if (schemaTab.value === "protocol") return connectionFields.value.map((field) => ({ field: field.name, type: field.type || "string", required: Boolean(field.required), description: field.description || field.label || "ProtocolDescriptor 字段" }));
  if (schemaTab.value === "point") return ["pointName", "pointCode", "dataType", "address", "readWrite", "scalingFactor", "offset", "unit", "precision", "collectionMode", "cacheEnabled", "cacheDuration", "priority", "remark"].map((field) => ({ field, type: "DataPoint", required: ["pointName", "pointCode", "address"].includes(field), description: "来自 DataPoint 类型" }));
  if (schemaTab.value === "report") return ["cloudTarget.enabled", "cloudTarget.productKey", "cloudTarget.deviceName", "additionalConfig.reportField", "additionalConfig.reportEnabled", "additionalConfig.changeThreshold", "additionalConfig.changeMinIntervalMs", "additionalConfig.eventMinIntervalMs"].map((field) => ({ field, type: "上报配置", required: false, description: "设备身份或点位属性映射" }));
  if (schemaTab.value === "alarm") return ["alarmEnabled", "alarmRule", "ruleId", "ruleName", "operator", "threshold", "duration", "level", "enabled", "description"].map((field) => ({ field, type: "告警配置", required: false, description: "点位告警触发条件" }));
  return ["deviceId", "deviceName", "protocol", "adaptive", "connection", "points", "cloudTarget"].map((field) => ({ field, type: "设备配置", required: ["deviceId", "deviceName", "protocol", "points"].includes(field), description: "保存配置来源" }));
});
const connectionAddressSummary = computed(() => String(connectionModel.value.host || connectionModel.value.url || connectionModel.value.endpoint || connectionModel.value.plc4xConnectionString || "未配置"));

function normalizePointsForEditor(rawPoints: DataPoint[], currentDeviceId: string, currentProtocol: string): DataPoint[] { return normalizeInitialPoints(rawPoints, currentDeviceId, currentProtocol, { adaptive: { ...adaptive }, pointDataTypes: pointDataTypes.value }); }
function buildDefaultPoint(currentDeviceId: string, currentProtocol: string, overrides: Partial<DataPoint> = {}): DataPoint { return defaultPointTemplate(currentDeviceId, currentProtocol, overrides, { adaptive: { ...adaptive }, pointDataTypes: pointDataTypes.value }); }
function setActiveStep(index: number) { completedSteps.value = new Set([...completedSteps.value, activeStep.value]); activeStep.value = Math.max(0, Math.min(localEditorSteps.length - 1, index)); if (activeStep.value === 4) syncJsonFromState(); }
function moveStep(delta: number) { setActiveStep(activeStep.value + delta); }
function reset(bundle: LocalDeviceBundle | null = null) {
  editorSession += 1;
  schemaGeneration += 1;
  saving.value = false;
  savedInSession = false;
  protocolChanged.value = false;
  activeStep.value = 0; completedSteps.value = new Set(); error.value = ""; pointKeyword.value = ""; pointDataTypeFilter.value = ""; pointReadWriteFilter.value = ""; changeDescription.value = "";
  const device = bundle?.device || {}; const connection = bundle?.connection || {};
  editingDeviceId.value = bundle ? resolveEditorDeviceId(bundle, "") : "";
  deviceId.value = editingDeviceId.value || createLocalDeviceId();
  deviceName.value = String(device.deviceName || ""); protocol.value = String(device.protocolType || connection.connectionType || visibleProtocols.value[0]?.protocol || "MODBUS_TCP"); overwrite.value = Boolean(editingDeviceId.value); startAfterSave.value = false;
  adaptive.baseCollectionInterval = Number(device.collectionInterval || DEFAULT_ADAPTIVE_CONFIG.baseCollectionInterval); adaptive.minCollectionInterval = Number(firstPointValue(bundle?.points, "minCollectionInterval") || DEFAULT_ADAPTIVE_CONFIG.minCollectionInterval); adaptive.maxCollectionInterval = Number(firstPointValue(bundle?.points, "maxCollectionInterval") || DEFAULT_ADAPTIVE_CONFIG.maxCollectionInterval); adaptive.pointChangeThreshold = Number(firstPointValue(bundle?.points, "pointChangeThreshold") || DEFAULT_ADAPTIVE_CONFIG.pointChangeThreshold);
  Object.assign(cloudTarget, { enabled: false, deviceType: "SUB_DEVICE", productKey: "", deviceName: "", topologyEnabled: true }, normalizeCloudTarget(device.cloudTarget));
  points.value = normalizePointsForEditor(bundle?.points || [], deviceId.value || "local-device", protocol.value); if (points.value.length === 0) addPoint(); selectedPointIndex.value = points.value.length ? 0 : -1; selectedAlarmRuleIndex.value = 0;
  connectionModel.value = connectionFields.value.length ? extractProtocolModel(connectionFields.value, connection as ConnectionPayload) : buildProtocolInitialModel(connectionFields.value); syncJsonFromState(); void ensureProtocolSchema(protocol.value);
}
const protocolChanged = ref(false);
function onProtocolChanged() {
  protocolChanged.value = true;
  connectionModel.value = buildProtocolInitialModel(connectionFields.value);
  points.value = points.value.map((point, index) => buildDefaultPoint(deviceId.value || "local-device", protocol.value, {
    pointId: point.pointId,
    pointCode: point.pointCode || `point_${index + 1}`,
    pointName: point.pointName || `点位 ${index + 1}`
  }));
  syncJsonFromState();
  void ensureProtocolSchema(protocol.value);
}
async function ensureProtocolSchema(protocolCode: string) {
  const normalizedProtocol = protocolCode.trim();
  if (!normalizedProtocol) return;
  const session = editorSession;
  const generation = ++schemaGeneration;
  const draftAtStart = JSON.stringify(connectionModel.value);
  const canCommit = () => props.modelValue && session === editorSession && generation === schemaGeneration && protocol.value === normalizedProtocol;
  const applySchema = (detail: ProtocolSchema) => {
    if (!canCommit()) return;
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: detail };
    // Schema 迟到只补缺省字段，绝不重置已加载连接或用户修改后的草稿。
    if (JSON.stringify(connectionModel.value) === draftAtStart) {
      const originalConnection = props.editingBundle?.connection as ConnectionPayload | undefined;
      const originalProtocol = String(props.editingBundle?.device?.protocolType || "");
      const loaded = originalConnection && originalProtocol === normalizedProtocol
        ? extractProtocolModel(detail.connectionFields || [], originalConnection) : {};
      connectionModel.value = { ...buildProtocolInitialModel(detail.connectionFields || []), ...loaded, ...connectionModel.value };
    }
    protocolChanged.value = false;
    syncJsonFromState();
  };
  const existing = protocolDetails.value[normalizedProtocol] || props.protocols.find((item) => item.protocol === normalizedProtocol);
  if (hasRenderableProtocolFields(existing)) {
    applySchema(existing);
    return;
  }
  try {
    const detail = await getProtocol(normalizedProtocol);
    applySchema(detail);
  } catch (caught) {
    if (canCommit()) error.value = caught instanceof Error ? `协议字段加载失败：${caught.message}` : "协议字段加载失败";
  }
}
function hasRenderableProtocolFields(schema: ProtocolSchema | null | undefined): schema is ProtocolSchema { return Boolean(schema && ((schema.connectionFields?.length || 0) > 0 || (schema.pointFields?.length || 0) > 0)); }
function addPoint() { const pointCode = createUniqueCode(points.value, "point"); const point = buildDefaultPoint(deviceId.value || "local-device", protocol.value, { pointCode, pointName: `点位 ${points.value.length + 1}` }); points.value = [...points.value, point]; selectedPointIndex.value = points.value.length - 1; syncJsonFromState(); }
function duplicatePoint(row?: DataPoint) { const source = row || selectedPoint.value; const sourceIndex = row ? points.value.indexOf(row) : selectedPointIndex.value; if (!source) return; const clone = cloneData(source); delete clone.id; delete clone.pointId; delete clone.createTime; delete clone.updateTime; delete clone.stableCount; delete clone.lastValue; delete clone.changeRate; delete clone.lastAdjustTime; delete clone.reportFieldConflict; clone.pointCode = createUniqueCode(points.value, `${source.pointCode || "point"}_copy`); clone.pointName = `${source.pointName || source.pointCode || "点位"} 副本`; if (isPlainObject(clone.additionalConfig) && hasValue(clone.additionalConfig.reportField)) clone.additionalConfig.reportField = `${clone.additionalConfig.reportField}_copy`; points.value.splice(Math.max(0, sourceIndex) + 1, 0, clone); selectedPointIndex.value = Math.max(0, sourceIndex) + 1; syncJsonFromState(); }
async function removePoint(row?: DataPoint) { const index = row ? points.value.indexOf(row) : selectedPointIndex.value; const point = points.value[index]; if (!point || index < 0) return; try { await ElMessageBox.confirm(`确认删除点位 ${point.pointCode || point.pointName || "当前点位"} 吗？`, "删除点位", { confirmButtonText: "删除", cancelButtonText: "取消", type: "warning" }); } catch { return; } points.value.splice(index, 1); selectedPointIndex.value = points.value.length ? Math.min(index, points.value.length - 1) : -1; selectedAlarmRuleIndex.value = 0; syncJsonFromState(); }
function selectPoint(row: DataPoint) { selectedPointIndex.value = points.value.indexOf(row); selectedAlarmRuleIndex.value = 0; }
function updatePointField(field: PointEditorField, value: unknown) { updateSelectedPath(field.path, parseFieldValue(value, field.valueType)); }
function updateSelectedPath(path: string, value: unknown) {
  const point = selectedPoint.value;
  if (!point) return;
  const previousPointCode = point.pointCode;
  const previousAddress = point.address;
  const previousTopic = getPathValue(point, "additionalConfig.topic");
  const previousNodeId = getPathValue(point, "additionalConfig.nodeId");
  const previousReportField = getPathValue(point, "additionalConfig.reportField");
  setPathValue(point as Record<string, unknown>, path, value);
  if (path === "pointCode" && hasValue(previousReportField) && String(previousReportField).trim() === String(previousPointCode || "").trim()) {
    setPathValue(point as Record<string, unknown>, "additionalConfig.reportField", value);
  }
  if (path === "address") {
    if (protocol.value === "MQTT" && (!hasValue(previousTopic) || String(previousTopic).trim() === String(previousAddress || "").trim())) setPathValue(point as Record<string, unknown>, "additionalConfig.topic", value);
    if (isOpcUaProtocol(protocol.value) && (!hasValue(previousNodeId) || String(previousNodeId).trim() === String(previousAddress || "").trim())) setPathValue(point as Record<string, unknown>, "additionalConfig.nodeId", value);
  }
  if (path === "additionalConfig.topic" && protocol.value === "MQTT" && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousTopic || "").trim())) point.address = String(value || "");
  if (path === "additionalConfig.nodeId" && isOpcUaProtocol(protocol.value) && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousNodeId || "").trim())) point.address = String(value || "");
  syncJsonFromState();
}
function updateAlarmRule(index: number, field: string, value: unknown) { const point = selectedPoint.value; if (!point || index < 0) return; const rules = alarmRules(point); while (rules.length <= index) rules.push({}); if (value === undefined || value === null || value === "") delete rules[index][field]; else rules[index][field] = value; point.alarmEnabled = rules.length ? 1 : 0; point.alarmRule = serializeAlarmRules(rules); syncJsonFromState(); }
function addAlarmRuleForCurrent() { if (!selectedPoint.value && points.value.length) selectedPointIndex.value = 0; const point = selectedPoint.value; if (!point) return; const rules = alarmRules(point); rules.push({ ruleId: createUniqueCode(rules.map((rule) => ({ pointCode: String(rule.ruleId || "") } as DataPoint)), "rule"), ruleName: "新告警规则", operator: ">=", enabled: true, level: "WARNING" }); point.alarmEnabled = 1; point.alarmRule = serializeAlarmRules(rules); selectedAlarmRuleIndex.value = rules.length - 1; syncJsonFromState(); }
function removeAlarmRuleAt(pointIndex: number, ruleIndex: number) { const point = points.value[pointIndex]; if (!point) return; const rules = alarmRules(point); rules.splice(ruleIndex, 1); point.alarmRule = serializeAlarmRules(rules); point.alarmEnabled = rules.length ? 1 : 0; selectedPointIndex.value = pointIndex; selectedAlarmRuleIndex.value = Math.max(0, Math.min(ruleIndex, rules.length - 1)); syncJsonFromState(); }
function selectAlarmRow(row: AlarmRuleRow) { selectedPointIndex.value = row.pointIndex; selectedAlarmRuleIndex.value = row.ruleIndex; }

function updateCloudTargetField(key: keyof CloudTargetConfig, value: unknown) {
  if (key === "enabled" || key === "topologyEnabled") {
    cloudTarget[key] = Boolean(value) as never;
  } else {
    cloudTarget[key] = String(value || "") as never;
  }
  syncJsonFromState();
}

function syncDeviceIdToPoints() { points.value = points.value.map((point) => ({ ...point, deviceId: deviceId.value || "local-device" })); syncJsonFromState(); }
function syncAdaptiveToPoints() { points.value = points.value.map((point) => ({ ...point, baseCollectionInterval: adaptive.baseCollectionInterval, currentCollectionInterval: adaptive.baseCollectionInterval, minCollectionInterval: adaptive.minCollectionInterval, maxCollectionInterval: adaptive.maxCollectionInterval, pointChangeThreshold: adaptive.pointChangeThreshold })); syncJsonFromState(); }
function syncJsonFromState() { configJson.value = JSON.stringify(buildConfigSnapshot(), null, 2); }
function formatConfigJson() { try { configJson.value = JSON.stringify(JSON.parse(configJson.value || "{}"), null, 2); error.value = ""; } catch (caught) { error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误"; } }
function applyConfigJson() { try { const parsed = JSON.parse(configJson.value || "{}"); applyConfigSnapshot(parsed); error.value = ""; syncJsonFromState(); } catch (caught) { error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误"; } }
function buildConfigSnapshot() { return { device: { deviceId: deviceId.value, deviceName: deviceName.value, protocolType: protocol.value, collectionInterval: adaptive.baseCollectionInterval, cloudTarget: { ...cloudTarget } }, adaptive: { ...adaptive }, connection: { ...connectionModel.value }, points: cloneData(points.value), cloudTarget: { ...cloudTarget }, uiSession: { changeDescription: changeDescription.value || undefined } }; }
function applyConfigSnapshot(value: unknown) {
  if (!isPlainObject(value)) throw new Error("配置必须是 JSON 对象");
  const device = isPlainObject(value.device) ? value.device : {};
  const adaptiveValue = isPlainObject(value.adaptive) ? value.adaptive : {};
  const identity = resolveEditorDeviceId(value, deviceId.value);
  deviceId.value = identity;
  deviceName.value = String(value.deviceName || device.deviceName || deviceName.value || "");
  protocol.value = String(value.protocol || device.protocolType || protocol.value || "MODBUS_TCP");
  adaptive.baseCollectionInterval = Number(adaptiveValue.baseCollectionInterval || device.collectionInterval || adaptive.baseCollectionInterval);
  adaptive.minCollectionInterval = Number(adaptiveValue.minCollectionInterval || adaptive.minCollectionInterval);
  adaptive.maxCollectionInterval = Number(adaptiveValue.maxCollectionInterval || adaptive.maxCollectionInterval);
  adaptive.pointChangeThreshold = Number(adaptiveValue.pointChangeThreshold ?? adaptive.pointChangeThreshold);
  if (isPlainObject(value.cloudTarget) || isPlainObject(device.cloudTarget)) {
    Object.assign(cloudTarget, { enabled: false, deviceType: "SUB_DEVICE", productKey: "", deviceName: "", topologyEnabled: true }, normalizeCloudTarget(isPlainObject(value.cloudTarget) ? value.cloudTarget : device.cloudTarget));
  }
  if (isPlainObject(value.connection)) connectionModel.value = { ...buildProtocolInitialModel(connectionFields.value), ...(value.connection as ProtocolFormModel) };
  if (Array.isArray(value.points)) {
    const currentCode = selectedPoint.value?.pointCode || null;
    const normalized = normalizePointsForEditor(parsePointsJson(JSON.stringify(value.points)), identity, protocol.value);
    points.value = normalized;
    const nextIndex = currentCode ? normalized.findIndex((item) => item.pointCode === currentCode) : -1;
    selectedPointIndex.value = nextIndex >= 0 ? nextIndex : (normalized.length ? 0 : -1);
  }
  if (isPlainObject(value.uiSession) && hasValue(value.uiSession.changeDescription)) {
    changeDescription.value = String(value.uiSession.changeDescription);
  }
  void ensureProtocolSchema(protocol.value);
}

function validateConfigSnapshot(value: ReturnType<typeof buildConfigSnapshot>) { const warnings: string[] = []; const errors = [...validateLocalDeviceDraft({ deviceId: value.device.deviceId, deviceName: value.device.deviceName, protocol: value.device.protocolType, points: value.points, cloudTarget: value.cloudTarget }), ...validateProtocolModel(connectionFields.value, value.connection as ProtocolFormModel)]; if (!value.points.some((point) => point.alarmEnabled)) warnings.push("尚未启用点位告警规则"); if (!totalReportFieldCount.value) warnings.push("尚未配置 reportField 上报属性"); return { errors, warnings }; }
async function save() {
  if (saving.value) return;
  const session = editorSession;
  const targetId = editingDeviceId.value || deviceId.value;
  const targetEditingId = editingDeviceId.value;
  const requestedStart = startAfterSave.value;
  const canCommit = () => props.modelValue && session === editorSession && deviceId.value === targetId;
  error.value = "";
  const mergedConnectionModel = { ...buildProtocolInitialModel(connectionFields.value), ...connectionModel.value };
  const normalizedPoints = normalizePointsForEditor(points.value, targetId, protocol.value).map(sanitizePointForSave);
  const errors = [...validateLocalDeviceDraft({ deviceId: targetId, deviceName: deviceName.value, protocol: protocol.value, points: normalizedPoints, cloudTarget: { ...cloudTarget } }), ...validateProtocolModel(connectionFields.value, mergedConnectionModel)];
  if (deviceId.value !== targetId) errors.push("禁止修改编辑中的设备身份");
  if (errors.length > 0) { error.value = errors.join("；"); return; }
  const connection = buildConnectionPayload(connectionFields.value, mergedConnectionModel, { deviceId: targetId, connectionType: protocol.value });
  const payload = buildLocalDevicePayload({ deviceId: targetId, deviceName: deviceName.value, protocol: protocol.value, adaptive: { ...adaptive }, connection, points: normalizedPoints, cloudTarget: { ...cloudTarget }, overwrite: overwrite.value || Boolean(targetEditingId) || savedInSession, startAfterSave: requestedStart });
  saving.value = true;
  try {
    const response = targetEditingId ? await updateLocalDevice(targetEditingId, payload) : await createLocalDevice(payload);
    if (!canCommit()) return;
    const responseId = response.deviceId;
    if (!responseId || responseId !== targetId) throw new Error("保存响应缺少设备身份或与请求不一致，请核对服务器配置后重试");
    savedInSession = true;
    ElMessage.success(`设备 ${responseId} 配置已保存${response.changed === false ? "（配置未变化）" : ""}`);
    emit("saved", responseId);
    if (requestedStart && response.startStatus === "RESTART_PENDING") {
      ElMessage.success("配置重启待完成，沿用已有启动意图，等待新一代有效数据");
      close(false);
      return;
    }
    if (requestedStart && response.startStatus === "STOP_SUPERSEDED") {
      ElMessage.info("配置已保存，已遵循停止请求，设备保持停止");
      close(false);
      return;
    }
    if (requestedStart && !response.started) {
      error.value = `设备 ${responseId} 配置已保存，但启动失败：${response.startError || "请检查运行状态"}`;
      ElMessage.warning(error.value);
      return;
    }
    if (requestedStart) {
      ElMessage.success(response.runtime?.ready ? "设备已就绪" : response.startStatus === "ALREADY_RUNNING" ? "设备已在运行，无需重复启动" : "启动请求已接受，等待首批有效数据");
    }
    close(false);
  } catch (caught) {
    if (canCommit()) error.value = caught instanceof Error ? caught.message : "本地设备保存失败";
  } finally {
    if (session === editorSession) saving.value = false;
  }
}
function close(value: boolean) {
  if (!value) { editorSession += 1; schemaGeneration += 1; saving.value = false; }
  emit("update:modelValue", value);
}
function fieldComponent(field: PointEditorField) { if (field.control === "switch") return ElSwitch; if (field.control === "select") return ElSelect; if (field.control === "number") return ElInputNumber; return ElInput; }
function fieldProps(field: PointEditorField) { const value = getPointFieldValue(field.path); if (field.control === "switch") return { modelValue: Boolean(value), disabled: field.disabled }; if (field.control === "select") return { modelValue: value ?? "", clearable: true, filterable: true, disabled: field.disabled }; if (field.control === "number") return { modelValue: toNumber(value), controlsPosition: "right", step: field.step || 1, disabled: field.disabled }; return { modelValue: value === undefined || value === null ? "" : String(value), disabled: field.disabled }; }
function getPointFieldValue(path: string): unknown { return selectedPoint.value ? getPathValue(selectedPoint.value, path) : undefined; }
function protocolPointFieldPath(field: ProtocolFieldConfig): string { return field.name.startsWith("additionalConfig.") ? field.name : `additionalConfig.${field.name}`; }
function protocolPointFieldSpan(field: ProtocolFieldConfig): number | undefined {
  if (field.type === "boolean" || field.type === "number" || field.type === "integer" || field.options?.length) {
    return undefined;
  }
  const marker = `${field.name} ${field.label || ""} ${field.description || ""}`.toLowerCase();
  return /url|topic|endpoint|path|script|expression|payload|template|正则|脚本|表达式|模板|报文/.test(marker) ? 2 : undefined;
}
function transformRuleText(point: DataPoint) { const scale = point.scalingFactor ?? 1; const offset = point.offset ?? 0; return Number(scale) !== 1 || Number(offset) !== 0 ? `value * ${scale} + ${offset}` : "原值"; }
function alarmThresholdText(row: AlarmRuleRow) { const unit = row.point.unit ? ` ${String(row.point.unit)}` : ""; const threshold = row.rule.threshold; return threshold === undefined || threshold === null || String(threshold).trim() === "" ? "-" : `${threshold}${unit}`; }
function alarmLevelLabel(level: unknown) { return alarmLevels.find((item) => item.value === String(level || ""))?.label || String(level || "未设置"); }
function alarmLevelClass(level: unknown) { const value = String(level || "").toUpperCase(); if (value === "CRITICAL") return "is-critical"; if (value === "ERROR" || value === "WARNING") return "is-warning"; if (value === "INFO") return "is-info"; return "is-unset"; }
function minPointAdditionalNumber(key: string) { const values = points.value.map((point) => Number(point.additionalConfig?.[key])).filter((value) => Number.isFinite(value) && value > 0); return values.length ? `${Math.min(...values)} ms` : "未配置"; }
function buildPayloadPreview(mode: "property" | "event" | "full") { const mapped = points.value.filter((point) => point.additionalConfig?.reportEnabled !== false && hasValue(point.additionalConfig?.reportField)); if (mode === "property") return { topic: cloudTopicPreview.value, productKey: cloudTarget.productKey, deviceName: cloudTarget.deviceName, properties: Object.fromEntries(mapped.map((point) => [String(point.additionalConfig?.reportField), point.lastValue ?? `<${point.pointCode || point.pointName}>`])) }; if (mode === "event") return { productKey: cloudTarget.productKey, deviceName: cloudTarget.deviceName, events: eventMappingPreview.value, minIntervalMs: eventIntervalSummary.value }; return { device: { deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value }, cloudTarget: { ...cloudTarget }, pointCount: points.value.length, reportFields: mapped.map((point) => point.additionalConfig?.reportField), alarmRules: alarmRuleRows.value.length }; }
function handleLocalEditorKeydown(event: KeyboardEvent) { if (event.key === "Escape" && props.modelValue) close(false); }

watch(() => props.modelValue, (visible) => { document.body.classList.toggle("modal-active", visible); if (visible) { reset(props.editingBundle || null); document.addEventListener("keydown", handleLocalEditorKeydown); } else { editorSession += 1; schemaGeneration += 1; saving.value = false; document.removeEventListener("keydown", handleLocalEditorKeydown); } }, { immediate: true });
watch(() => props.editingBundle, (bundle) => { if (props.modelValue) reset(bundle || null); });
watch(connectionFields, (fields) => { if (props.modelValue && Object.keys(connectionModel.value).length === 0) connectionModel.value = buildProtocolInitialModel(fields); });
watch([deviceId, deviceName, protocol, () => ({ ...adaptive }), () => ({ ...cloudTarget }), connectionModel, points], () => { if (activeStep.value === 4) syncJsonFromState(); }, { deep: true });
onBeforeUnmount(() => { editorSession += 1; schemaGeneration += 1; document.body.classList.remove("modal-active"); document.removeEventListener("keydown", handleLocalEditorKeydown); });
</script>

<style scoped src="./local-device-editor.css"></style>
