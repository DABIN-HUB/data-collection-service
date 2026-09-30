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
          <span class="label-chip">配置编辑器</span>
          <h3 id="localEditorTitle">{{ editingDeviceId ? "编辑本地临时设备" : "新增本地临时设备" }}</h3>
          <p>工业协议采集终端配置</p>
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
          <button id="cancelLocalDeviceBtn" type="button" class="ghost-button" @click="close(false)">关闭</button>
        </div>
      </header>

      <nav class="local-editor-tabs" role="tablist" aria-label="新增设备配置分区">
        <button
          v-for="(step, index) in localEditorSteps"
          :key="step.key"
          type="button"
          class="local-editor-tab"
          :data-local-editor-section="step.key"
          :class="{ 'is-active': activeStep === index, 'is-complete': completedSteps.has(index) }"
          @click="setActiveStep(index)"
        >
          <span>{{ step.no }}</span>
          <strong>{{ step.label }}</strong>
          <small>{{ step.desc }}</small>
        </button>
      </nav>

      <main class="local-editor-body">
        <el-alert v-if="error" :title="error" type="warning" :closable="false" />

        <section v-show="activeStep === 0" class="local-editor-pane" data-local-editor-pane="setup">
          <div class="step-grid step-grid-setup">
            <EditorProgressRail :validation-title="validationTitle" :items="localEditorChecklist" />

            <div class="local-setup-stable-column">
              <section class="local-section-card local-setup-card">
                <EditorSectionHeader badge="设备基础" title="设备信息" subtitle="设备标识、协议和采集节奏集中配置。" />
                <div class="form-grid two-column">
                  <label>设备 ID *<input id="localDeviceId" v-model="deviceId" type="text" :disabled="Boolean(editingDeviceId)" placeholder="local-modbus-1" @change="syncDeviceIdToPoints"></label>
                  <label>设备名称 *<input id="localDeviceName" v-model="deviceName" type="text" placeholder="本地测试设备"></label>
                  <label><span class="protocol-label"><span id="localProtocolMetaHelp" class="protocol-meta-anchor"></span><span>协议 *</span></span><select id="localProtocolSelect" v-model="protocol" @change="onProtocolChanged"><option v-for="item in visibleProtocols" :key="item.protocol" :value="item.protocol">{{ item.title || item.protocol }} ({{ item.protocol }})</option></select></label>
                  <label>基础采集周期 (ms)<input id="localCollectionInterval" v-model.number="adaptive.baseCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"></label>
                  <label>最小采集周期 (ms)<input id="localMinCollectionInterval" v-model.number="adaptive.minCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"></label>
                  <label>最大采集周期 (ms)<input id="localMaxCollectionInterval" v-model.number="adaptive.maxCollectionInterval" type="number" min="100" step="100" @change="syncAdaptiveToPoints"></label>
                  <label>点位变化阈值<input id="localPointChangeThreshold" v-model.number="adaptive.pointChangeThreshold" type="number" min="0" step="0.01" @change="syncAdaptiveToPoints"></label>
                </div>
              </section>

              <section class="local-section-card local-cloud-target-card">
                <EditorSectionHeader badge="云平台身份" title="云平台身份" subtitle="配置该设备在云端的身份信息。" />
                <CloudTargetForm :cloud-target="cloudTarget" :topic-preview="cloudTopicPreview" @update-field="updateCloudTargetField" />
              </section>
            </div>

            <section class="local-section-card local-connection-card">
              <EditorSectionHeader badge="连接参数" title="协议通信参数" subtitle="基础连接与高级连接参数。" />
              <div class="local-connection-body">
                <form id="localConnectionForm" class="dynamic-form" @submit.prevent>
                  <ProtocolDynamicForm v-model="connectionModel" :fields="connectionFields" @validate="connectionErrors = $event" />
                </form>
              </div>
            </section>
          </div>
        </section>

        <section v-show="activeStep === 1" class="local-editor-pane" data-local-editor-pane="points">
          <div class="step-grid step-grid-master-detail">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader badge="建模概览" title="点位建模" subtitle="点位完整度与地址检查" />
              <div class="metric-stack">
                <MetricItem label="已配置点位" :value="String(points.length)" />
                <MetricItem label="必填项完整度" :value="pointCompletenessText" />
                <MetricItem label="编码唯一性" :value="duplicatePointCode ? `重复：${duplicatePointCode}` : '通过'" :tone="duplicatePointCode ? 'error' : 'ok'" />
                <MetricItem label="地址合法性" :value="missingPointAddressCount ? `${missingPointAddressCount} 个待完善` : '已填写'" :tone="missingPointAddressCount ? 'warn' : 'ok'" />
              </div>
              <ul class="hint-list">
                <li>建议先规划点位编码规则。</li>
                <li>双击表格行可快速编辑。</li>
                <li>点位配置完成后请使用“保存并测试”验证。</li>
              </ul>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card point-list-card">
                <EditorSectionHeader badge="点位列表" title="采集点位">
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
                        <td><button type="button" class="point-select-button" :data-select-local-point="points.indexOf(row)" @click.stop="selectPoint(row)"><strong>{{ row.pointName || row.pointCode || '-' }}</strong></button></td>
                        <td>{{ row.pointCode || '-' }}</td>
                        <td>{{ row.dataType || '-' }}</td>
                        <td>{{ row.address || '-' }}</td>
                        <td>{{ row.readWrite || '-' }}</td>
                        <td>{{ row.scalingFactor ?? '-' }}</td>
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
                    <PointFieldGrid layout="four-column" :fields="primaryPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
                  </FieldGroup>
                  <details class="advanced-collapse" open>
                    <summary>高级参数 / 协议扩展 / 只读信息</summary>
                    <div class="advanced-stack">
                      <FieldGroup title="数据处理"><PointFieldGrid layout="four-column" :fields="dataPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup title="上报 / 缓存"><PointFieldGrid layout="four-column" :fields="reportPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup :title="protocolPointTitle">
                        <div class="protocol-point-note"><p v-if="protocolPointNotes.addressHints.length">当前协议地址示例：<code v-for="hint in protocolPointNotes.addressHints" :key="hint">{{ hint }}</code></p><p v-for="message in protocolPointNotes.messages" :key="message">{{ message }}</p></div>
                        <PointFieldGrid v-if="protocolPointFields.length" layout="four-column" :fields="protocolPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
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

        <section v-show="activeStep === 2" class="local-editor-pane" data-local-editor-pane="alarm">
          <div class="step-grid step-grid-master-detail">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader badge="规则概览" title="告警规则" />
              <div class="metric-stack">
                <MetricItem label="规则总数" :value="String(alarmRuleRows.length)" />
                <MetricItem label="已启用" :value="String(enabledAlarmRuleCount)" tone="ok" />
                <MetricItem label="严重" :value="String(alarmLevelCounts.CRITICAL || 0)" tone="error" />
                <MetricItem label="重要/错误" :value="String((alarmLevelCounts.ERROR || 0) + (alarmLevelCounts.WARNING || 0))" tone="warn" />
                <MetricItem label="事件最小间隔" :value="eventIntervalSummary" />
              </div>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card alarm-list-card" :class="{ 'is-empty': alarmRuleRows.length === 0 }">
                <EditorSectionHeader badge="告警规则" title="点位告警规则">
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
                        <td>{{ row.point.pointName || row.point.pointCode }}</td><td>{{ row.rule.ruleName || row.rule.ruleId || '未命名规则' }}</td><td>{{ row.rule.operator || '-' }}</td><td>{{ alarmThresholdText(row) }}</td><td>{{ row.rule.duration ?? '-' }} s</td><td><span class="level-badge" :class="alarmLevelClass(row.rule.level)">{{ alarmLevelLabel(row.rule.level) }}</span></td><td><span class="state-badge" :class="row.rule.enabled === false ? 'is-off' : 'is-on'">{{ row.rule.enabled === false ? '禁用' : '启用' }}</span></td><td class="action-cell"><div class="row-actions"><button type="button" class="text-action" @click.stop="selectAlarmRow(row)">编辑</button><button type="button" class="text-action danger" @click.stop="removeAlarmRuleAt(row.pointIndex, row.ruleIndex)">删除</button></div></td>
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
                    <label>启用告警<el-switch :model-value="Boolean(selectedPoint.alarmEnabled)" @update:model-value="updateSelectedPath('alarmEnabled', $event ? 1 : 0)" /></label>
                    <label>规则ID<el-input :model-value="String(currentAlarmRule.ruleId || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleId', $event)" /></label>
                    <label>规则名称<el-input :model-value="String(currentAlarmRule.ruleName || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleName', $event)" /></label>
                    <label>运算符<el-select :model-value="String(currentAlarmRule.operator || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'operator', $event)"><el-option v-for="operator in alarmOperators" :key="operator" :label="operator" :value="operator" /></el-select></label>
                    <label>阈值<el-input-number :model-value="toNumber(currentAlarmRule.threshold)" controls-position="right" :step="0.0001" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'threshold', $event)" /></label>
                    <label>持续时间(s)<el-input-number :model-value="toNumber(currentAlarmRule.duration)" controls-position="right" :step="1" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'duration', $event)" /></label>
                    <label>告警级别<el-select :model-value="String(currentAlarmRule.level || '')" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'level', $event)"><el-option v-for="level in alarmLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></label>
                    <label>启用<el-select :model-value="currentAlarmRule.enabled === undefined ? '' : String(Boolean(currentAlarmRule.enabled))" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'enabled', parseBooleanOption($event))"><el-option label="是" value="true" /><el-option label="否" value="false" /></el-select></label>
                    <label class="wide-field">描述<el-input :model-value="String(currentAlarmRule.description || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'description', $event)" /></label>
                  </div>
                  <div class="alarm-condition-hint" :title="alarmTriggerHint">触发预览：{{ alarmTriggerHint }}</div>
                </div>
                <div v-else class="empty-state"><strong>暂无可编辑规则</strong><span>选择已有规则，或点击“新增规则”。</span></div>
              </section>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 3" class="local-editor-pane" data-local-editor-pane="cloud">
          <div class="step-grid step-grid-cloud">
            <aside class="local-section-card overview-card cloud-sidebar">
              <EditorSectionHeader badge="云端目标与身份" title="云平台身份" subtitle="目标身份与上报策略" />
              <CloudTargetForm :cloud-target="cloudTarget" :topic-preview="cloudTopicPreview" @update-field="updateCloudTargetField" />
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
                <EditorSectionHeader badge="属性映射列表" title="云端属性映射" />
                <div class="table-wrap compact mapping-table-wrap">
                  <table class="point-table editor-table cloud-point-table">
                    <thead><tr><th>序号</th><th>点位名称</th><th>本地标识</th><th>云端属性编码</th><th>上报类型</th><th>转换规则</th><th>单位</th><th>启用状态</th><th>操作</th></tr></thead>
                    <tbody id="localCloudRows">
                      <tr v-for="(row, index) in points" :key="row.pointCode || row.address || index" :class="{ 'is-selected': index === selectedPointIndex }" @click="selectPoint(row)">
                        <td>{{ index + 1 }}</td><td>{{ row.pointName || row.pointCode || '-' }}</td><td>{{ row.pointCode || '-' }}</td><td>{{ row.additionalConfig?.reportField || '-' }}</td><td>{{ row.additionalConfig?.eventEnabled ? '属性+事件' : '属性' }}</td><td>{{ transformRuleText(row) }}</td><td>{{ row.unit || '-' }}</td><td>{{ cloudPointStatus(row, cloudTarget) }}</td><td class="action-cell"><div class="row-actions"><button type="button" class="text-action" @click.stop="selectPoint(row)">编辑</button></div></td>
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
                  <EditorSectionHeader badge="Payload" title="Payload 预览" subtitle="实时构造示例">
                    <template #actions><select v-model="payloadPreviewMode" class="compact-select"><option value="property">属性上报</option><option value="event">事件上报</option><option value="full">完整配置摘要</option></select></template>
                  </EditorSectionHeader>
                  <pre class="json-preview">{{ payloadPreview }}</pre>
                </section>
              </div>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 4" class="local-editor-pane" data-local-editor-pane="json">
          <div class="step-grid step-grid-json">
            <aside class="local-section-card overview-card">
              <EditorSectionHeader badge="高级配置导航" title="配置章节" />
              <button v-for="item in jsonSections" :key="item.key" type="button" class="json-nav-item" :class="{ 'is-active': jsonSection === item.key, 'is-modified': item.status === '有修改' }" @click="jsonSection = item.key"><span>{{ item.label }}</span><small>{{ item.status }}</small></button>
            </aside>
            <section class="local-section-card json-editor-card">
              <EditorSectionHeader badge="JSON 高级配置" title="完整设备配置">
                <template #actions><div class="inline-actions table-actions"><button type="button" @click="formatConfigJson">格式化</button><button type="button" @click="applyConfigJson">校验并应用</button><button type="button" @click="syncJsonFromState">恢复当前配置</button></div></template>
              </EditorSectionHeader>
              <textarea id="localPointsJson" v-model="configJson" class="point-json-textarea" spellcheck="false"></textarea>
              <label class="change-note-label">变更说明（仅用于本次编辑备注）<textarea v-model="changeDescription" class="change-note" placeholder="可选：记录本次配置调整目的"></textarea></label>
            </section>
            <aside class="local-section-card schema-card">
              <EditorSectionHeader badge="校验与结构说明" title="配置校验" subtitle="结构、字段与摘要" />
              <div class="metric-stack"><MetricItem label="错误数" :value="String(jsonValidation.errors.length)" :tone="jsonValidation.errors.length ? 'error' : 'ok'" /><MetricItem label="警告数" :value="String(jsonValidation.warnings.length)" :tone="jsonValidation.warnings.length ? 'warn' : 'ok'" /><MetricItem label="配置章节数" :value="String(jsonSections.length)" /><MetricItem label="点位数" :value="String(points.length)" /></div>
              <ul class="validation-list"><li v-for="item in jsonValidation.errors" :key="item" class="is-error">{{ item }}</li><li v-for="item in jsonValidation.warnings" :key="item" class="is-warn">{{ item }}</li><li v-if="!jsonValidation.errors.length && !jsonValidation.warnings.length" class="is-ok">当前 JSON 与结构化状态校验通过</li></ul>
              <div class="schema-tabs"><button v-for="tab in schemaTabs" :key="tab.key" type="button" :class="{ 'is-active': schemaTab === tab.key }" @click="schemaTab = tab.key">{{ tab.label }}</button></div>
              <div class="schema-table-wrap"><table class="schema-table"><thead><tr><th>字段</th><th>类型</th><th>必填</th></tr></thead><tbody><tr v-for="row in activeSchemaRows" :key="row.field"><td>{{ row.field }}<small>{{ row.description }}</small></td><td>{{ row.type }}</td><td>{{ row.required ? '是' : '否' }}</td></tr></tbody></table></div>
              <FieldGroup title="配置摘要"><div class="metric-stack"><MetricItem label="协议类型" :value="protocol" /><MetricItem label="设备地址" :value="connectionAddressSummary" /><MetricItem label="上报平台" :value="cloudTargetSummary({} as DataPoint, cloudTarget)" /><MetricItem label="告警规则" :value="String(alarmRuleRows.length)" /><MetricItem label="编辑备注" :value="changeDescription ? '有说明' : '未填写'" /></div></FieldGroup>
            </aside>
          </div>
        </section>
      </main>

      <footer class="inline-actions local-options local-editor-footer">
        <label class="check-line"><input id="localOverwrite" v-model="overwrite" type="checkbox"> 覆盖已有本地临时设备</label>
        <label class="check-line"><input id="localStartAfterSave" v-model="startAfterSave" type="checkbox"> 保存后立即本地启动</label>
        <div class="local-step-actions">
          <button id="localEditorPrevBtn" type="button" :disabled="activeStep === 0" @click="moveStep(-1)">上一步</button>
          <button id="localEditorNextBtn" type="button" @click="activeStep === localEditorSteps.length - 1 ? save() : moveStep(1)">{{ activeStep === localEditorSteps.length - 1 ? "完成配置" : "下一步" }}</button>
          <button id="saveLocalDeviceBtn" type="button" class="primary" :disabled="saving" @click="save">{{ saving ? "保存中..." : "保存并测试" }}</button>
        </div>
      </footer>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, reactive, ref, watch, type PropType } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch, ElOption, ElMessage, ElMessageBox } from "element-plus";

import { createLocalDevice, updateLocalDevice } from "@/api/config.api";
import { startLocalDevice } from "@/api/device.api";
import { getProtocol } from "@/api/protocol.api";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import { buildConnectionPayload, buildProtocolInitialModel, extractProtocolModel, getPathValue, setPathValue, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import { buildLocalDevicePayload, buildProtocolPointNotes, DEFAULT_ADAPTIVE_CONFIG, validateLocalDeviceDraft, type AdaptiveConfig, type CloudTargetConfig, type LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import { buildReadonlyItems, createUniqueCode, alarmRules, parseBooleanOption, parseFieldValue, parsePointsJson, serializeAlarmRules, statusLabel, toNumber, type AlarmRule, type FieldValueType } from "@/features/point/utils/point-draft-utils";
import { buildLocalEditorChecklist, buildPointModelingOverview, cloneData, cloudPointStatus, cloudTargetSummary, countReportFields, defaultPointTemplate, firstPointValue, hasValue, isOpcUaProtocol, isPlainObject, normalizeCloudTarget, normalizeInitialPoints, sanitizePointForSave } from "@/features/device/utils/local-device-editor-utils";
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
      h(EditorSectionHeader, { badge: "配置状态", title: props.validationTitle }),
      h("ol", { id: "localEditorChecklist", class: "local-checklist" }, props.items.map((item) => h("li", { class: [`is-${item.state}`] }, [h("span", { class: "status-dot" }), h("span", item.label)])))
    ]);
  }
});

const MetricItem = defineComponent({
  name: "MetricItem",
  props: { label: { type: String, required: true }, value: { type: String, required: true }, tone: { type: String, default: "" } },
  setup(props) { return () => h("div", { class: ["metric-item", props.tone ? `is-${props.tone}` : ""] }, [h("span", props.label), h("strong", props.value)]); }
});

const CloudTargetForm = defineComponent({
  name: "CloudTargetForm",
  props: { cloudTarget: { type: Object as PropType<CloudTargetConfig>, required: true }, topicPreview: { type: String, required: true } },
  emits: { "update-field": (_key: keyof CloudTargetConfig, _value: unknown) => true },
  setup(props, { emit }) {
    return () => h("div", { class: "form-grid two-column" }, [
      h("label", ["启用云上报", h("select", { id: "localCloudEnabled", value: String(props.cloudTarget.enabled), onChange: (event: Event) => emit("update-field", "enabled", (event.target as HTMLSelectElement).value === "true") }, [h("option", { value: "false" }, "否"), h("option", { value: "true" }, "是")])]),
      h("label", ["设备类型", h("select", { id: "localCloudDeviceType", value: props.cloudTarget.deviceType, onChange: (event: Event) => emit("update-field", "deviceType", (event.target as HTMLSelectElement).value) }, ["SUB_DEVICE", "GATEWAY", "DIRECT", "LOGICAL_SUB_DEVICE"].map((value) => h("option", { value }, value)))]),
      h("label", ["ProductKey", h("input", { id: "localCloudProductKey", value: props.cloudTarget.productKey || "", placeholder: "pk_xxx", onInput: (event: Event) => emit("update-field", "productKey", (event.target as HTMLInputElement).value) })]),
      h("label", ["DeviceName", h("input", { id: "localCloudDeviceName", value: props.cloudTarget.deviceName || "", placeholder: "sub_device_001", onInput: (event: Event) => emit("update-field", "deviceName", (event.target as HTMLInputElement).value) })]),
      h("label", ["启用拓扑注册", h("select", { id: "localCloudTopologyEnabled", value: String(props.cloudTarget.topologyEnabled), onChange: (event: Event) => emit("update-field", "topologyEnabled", (event.target as HTMLSelectElement).value === "true") }, [h("option", { value: "true" }, "是"), h("option", { value: "false" }, "否")])]),
      h("label", { class: "wide-field" }, ["Topic 示例", h("input", { id: "localCloudTopicPreview", value: props.topicPreview, readonly: true })])
    ]);
  }
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
          h("span", { class: "editor-section-subtitle editor-object-meta", title: `${pointName} | ${meta}` }, `${pointName} | ${meta}`)
        ]),
        h("div", { class: "editor-section-actions editor-object-actions" }, [h("span", { class: "pill subtle" }, statusLabel(props.point.status))])
      ]);
    };
  }
});

const PointFieldGrid = defineComponent({
  name: "PointFieldGrid",
  props: {
    fields: { type: Array as PropType<PointEditorField[]>, required: true },
    fieldComponent: { type: Function as PropType<(field: PointEditorField) => unknown>, required: true },
    fieldProps: { type: Function as PropType<(field: PointEditorField) => Record<string, unknown>>, required: true },
    updatePointField: { type: Function as PropType<(field: PointEditorField, value: unknown) => void>, required: true },
    layout: { type: String as PropType<"two-column" | "dense" | "four-column" | "single">, default: "two-column" }
  },
  setup(props) {
    return () => h("div", { class: ["point-field-grid", `point-field-grid-${props.layout}`] }, props.fields.map((field) => h("label", { key: field.path, class: { "wide-field": field.fullWidth, "point-field-full": field.fullWidth }, style: field.span ? { "--field-span": field.span } : undefined, title: field.description || field.label }, [
      h("span", { class: "field-label-text" }, [field.label, field.required ? h("span", { class: "field-required" }, " *") : null]),
      h(props.fieldComponent(field) as string, { ...props.fieldProps(field), "onUpdate:modelValue": (value: unknown) => props.updatePointField(field, value) }, () => field.options?.map((option) => h(ElOption, { key: String(option.value), label: option.label, value: option.value }))),
      field.description && field.fullWidth ? h("small", { class: "field-description" }, field.description) : null
    ])));
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
  activeStep.value = 0; completedSteps.value = new Set(); error.value = ""; pointKeyword.value = ""; pointDataTypeFilter.value = ""; pointReadWriteFilter.value = ""; changeDescription.value = "";
  const device = bundle?.device || {}; const connection = bundle?.connection || {};
  editingDeviceId.value = String(device.id || device.deviceId || ""); deviceId.value = editingDeviceId.value || ""; deviceName.value = String(device.deviceName || ""); protocol.value = String(device.protocolType || connection.connectionType || visibleProtocols.value[0]?.protocol || "MODBUS_TCP"); overwrite.value = Boolean(editingDeviceId.value); startAfterSave.value = false;
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
  const normalizedProtocol = protocolCode.trim(); if (!normalizedProtocol) return;
  const existing = protocolDetails.value[normalizedProtocol] || props.protocols.find((item) => item.protocol === normalizedProtocol);
  if (hasRenderableProtocolFields(existing)) {
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: existing };
    connectionModel.value = protocolChanged.value
      ? buildProtocolInitialModel(existing.connectionFields || [])
      : { ...buildProtocolInitialModel(existing.connectionFields || []), ...connectionModel.value };
    protocolChanged.value = false;
    return;
  }
  try {
    const detail = await getProtocol(normalizedProtocol);
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: detail };
    if (protocol.value === normalizedProtocol) {
      connectionModel.value = buildProtocolInitialModel(detail.connectionFields || []);
      points.value = protocolChanged.value
        ? points.value.map((point, index) => buildDefaultPoint(deviceId.value || "local-device", normalizedProtocol, {
            pointId: point.pointId,
            pointCode: point.pointCode || `point_${index + 1}`,
            pointName: point.pointName || `点位 ${index + 1}`
          }))
        : normalizePointsForEditor(points.value, deviceId.value || "local-device", normalizedProtocol);
      protocolChanged.value = false;
      syncJsonFromState();
    }
  } catch (caught) { error.value = caught instanceof Error ? `协议字段加载失败：${caught.message}` : "协议字段加载失败"; }
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
  if (path === "pointCode" && String(value || "").trim() !== String(previousPointCode || "").trim()) {
    point.pointId = undefined;
  }
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
function applyConfigSnapshot(value: unknown) { if (!isPlainObject(value)) throw new Error("配置必须是 JSON 对象"); const device = isPlainObject(value.device) ? value.device : {}; const adaptiveValue = isPlainObject(value.adaptive) ? value.adaptive : {}; deviceId.value = String(value.deviceId || device.deviceId || device.id || deviceId.value || ""); deviceName.value = String(value.deviceName || device.deviceName || deviceName.value || ""); protocol.value = String(value.protocol || device.protocolType || protocol.value || "MODBUS_TCP"); adaptive.baseCollectionInterval = Number(adaptiveValue.baseCollectionInterval || device.collectionInterval || adaptive.baseCollectionInterval); adaptive.minCollectionInterval = Number(adaptiveValue.minCollectionInterval || adaptive.minCollectionInterval); adaptive.maxCollectionInterval = Number(adaptiveValue.maxCollectionInterval || adaptive.maxCollectionInterval); adaptive.pointChangeThreshold = Number(adaptiveValue.pointChangeThreshold || adaptive.pointChangeThreshold); if (isPlainObject(value.cloudTarget) || isPlainObject(device.cloudTarget)) Object.assign(cloudTarget, { enabled: false, deviceType: "SUB_DEVICE", productKey: "", deviceName: "", topologyEnabled: true }, normalizeCloudTarget(isPlainObject(value.cloudTarget) ? value.cloudTarget : device.cloudTarget)); if (isPlainObject(value.connection)) connectionModel.value = { ...buildProtocolInitialModel(connectionFields.value), ...(value.connection as ProtocolFormModel) }; if (Array.isArray(value.points)) { const currentCode = selectedPoint.value?.pointCode || null; const normalized = normalizePointsForEditor(parsePointsJson(JSON.stringify(value.points)), deviceId.value || "local-device", protocol.value); points.value = normalized; const nextIndex = currentCode ? normalized.findIndex((item) => item.pointCode === currentCode) : -1; selectedPointIndex.value = nextIndex >= 0 ? nextIndex : (normalized.length ? 0 : -1); } if (isPlainObject(value.uiSession) && hasValue(value.uiSession.changeDescription)) changeDescription.value = String(value.uiSession.changeDescription); void ensureProtocolSchema(protocol.value); }
function validateConfigSnapshot(value: ReturnType<typeof buildConfigSnapshot>) { const warnings: string[] = []; const errors = [...validateLocalDeviceDraft({ deviceId: value.device.deviceId, deviceName: value.device.deviceName, protocol: value.device.protocolType, points: value.points, cloudTarget: value.cloudTarget }), ...validateProtocolModel(connectionFields.value, value.connection as ProtocolFormModel)]; if (!value.points.some((point) => point.alarmEnabled)) warnings.push("尚未启用点位告警规则"); if (!totalReportFieldCount.value) warnings.push("尚未配置 reportField 上报属性"); return { errors, warnings }; }
async function save() { error.value = ""; const mergedConnectionModel = { ...buildProtocolInitialModel(connectionFields.value), ...connectionModel.value }; const normalizedPoints = normalizePointsForEditor(points.value, deviceId.value || "local-device", protocol.value).map(sanitizePointForSave); const errors = [...validateLocalDeviceDraft({ deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value, points: normalizedPoints, cloudTarget: { ...cloudTarget } }), ...validateProtocolModel(connectionFields.value, mergedConnectionModel)]; if (errors.length > 0) { error.value = errors.join("；"); return; } saving.value = true; try { const connection = buildConnectionPayload(connectionFields.value, mergedConnectionModel, { deviceId: deviceId.value, connectionType: protocol.value }); const payload = buildLocalDevicePayload({ deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value, adaptive: { ...adaptive }, connection, points: normalizedPoints, cloudTarget: { ...cloudTarget }, overwrite: overwrite.value || Boolean(editingDeviceId.value), startAfterSave: startAfterSave.value }); if (editingDeviceId.value) await updateLocalDevice(editingDeviceId.value, payload); else await createLocalDevice(payload); if (startAfterSave.value) await startLocalDevice(deviceId.value); ElMessage.success(startAfterSave.value ? "本地设备已保存并启动" : "本地设备已保存"); emit("saved", deviceId.value); close(false); } catch (caught) { error.value = caught instanceof Error ? caught.message : "本地设备保存失败"; } finally { saving.value = false; } }
function close(value: boolean) { emit("update:modelValue", value); }
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

watch(() => props.modelValue, (visible) => { document.body.classList.toggle("modal-active", visible); if (visible) { reset(props.editingBundle || null); document.addEventListener("keydown", handleLocalEditorKeydown); } else document.removeEventListener("keydown", handleLocalEditorKeydown); }, { immediate: true });
watch(() => props.editingBundle, (bundle) => { if (props.modelValue) reset(bundle || null); });
watch(connectionFields, (fields) => { if (props.modelValue && Object.keys(connectionModel.value).length === 0) connectionModel.value = buildProtocolInitialModel(fields); });
watch([deviceId, deviceName, protocol, () => ({ ...adaptive }), () => ({ ...cloudTarget }), connectionModel, points], () => { if (activeStep.value === 4) syncJsonFromState(); }, { deep: true });
onBeforeUnmount(() => { document.body.classList.remove("modal-active"); document.removeEventListener("keydown", handleLocalEditorKeydown); });
</script>

<style scoped>
.local-editor-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(2, 6, 23, 0.54);
  backdrop-filter: blur(2px);
}

.local-device-panel {
  --panel-line: var(--console-border-soft, #1e3a5f);
  --panel-muted: var(--console-text-muted, #8aa0b8);
  --panel-text: var(--console-text-primary, #e5edf8);
  --editor-font-page-title: 17px;
  --editor-font-section-title: 14px;
  --editor-font-object-title: 13px;
  --editor-font-subsection-title: 13px;
  --editor-font-body: 12px;
  --editor-font-label: 12px;
  --editor-font-table: 12px;
  --editor-font-meta: 11px;
  --editor-font-helper: 10px;
  --editor-font-badge: 10px;
  --editor-font-metric-value: 13px;
  --editor-card-padding: 12px;
  --editor-card-content-offset: 10px;
  --editor-form-row-gap: 8px;
  --editor-form-column-gap: 10px;
  --editor-label-control-gap: 4px;
  --editor-control-height: 32px;
  --editor-button-height: 30px;
  --editor-sidebar-gap: 7px;
  --editor-subsection-bottom: 6px;
  --editor-card-border: rgba(96, 165, 250, 0.16);
  --editor-card-bg: color-mix(in srgb, var(--console-panel, #0f1b2e) 93%, #1d4ed8 7%);
  --editor-card-soft: color-mix(in srgb, var(--console-panel-soft, #12233a) 90%, #0ea5e9 10%);
  --editor-glow: 0 10px 28px rgba(15, 23, 42, 0.24);
  position: fixed;
  top: 50%;
  left: 50%;
  z-index: 2001;
  display: flex;
  width: clamp(1100px, 90vw, 1440px);
  height: clamp(650px, 88vh, 820px);
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  transform: translate(-50%, -50%);
  color: var(--console-text-secondary);
  border: 1px solid rgba(96, 165, 250, 0.22);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.98), var(--console-bg));
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.42);
}

.local-editor-title {
  display: flex;
  min-height: 54px;
  padding: 0 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  color: var(--panel-text);
  border-bottom: 1px solid rgba(96, 165, 250, 0.16);
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.96) 0%, rgba(15, 23, 42, 0.9) 100%);
}

.local-title-copy, .local-editor-title-actions, .local-editor-stats, .inline-actions, .table-actions, .local-step-actions, .point-detail-hero-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.local-title-copy {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

h3, p {
  margin: 0;
}

.local-editor-title h3 {
  color: var(--console-text-primary);
  font-size: var(--editor-font-page-title);
  font-weight: 800;
  line-height: 1.16;
}

.local-editor-title p, .local-section-head p, .field-description, .hint-list, .protocol-point-note, .validation-list {
  color: var(--console-text-muted);
  font-size: var(--editor-font-meta);
  line-height: 1.3;
}

.field-description {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.label-chip, .pill {
  display: inline-flex;
  width: fit-content;
  min-height: 18px;
  padding: 2px 6px;
  align-items: center;
  border: 1px solid rgba(59, 130, 246, 0.34);
  border-radius: 999px;
  color: #bfdbfe;
  background: rgba(37, 99, 235, 0.18);
  font-size: var(--editor-font-badge);
  font-weight: 700;
  line-height: 1.2;
}

.pill.subtle {
  color: var(--console-text-muted);
  border-color: var(--console-border-soft);
  background: var(--console-bg-soft);
}

.local-editor-title-actions {
  justify-content: flex-end;
}

.local-editor-stat {
  width: 118px;
  min-width: 0;
  min-height: 38px;
  padding: 4px 8px;
  border: 1px solid rgba(96, 165, 250, 0.14);
  border-radius: var(--console-radius-md);
  background: rgba(15, 23, 42, 0.38);
}

.local-editor-stat strong, .local-editor-stat span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-stat strong {
  color: var(--console-text-primary);
  font-size: var(--editor-font-metric-value);
  font-weight: 700;
  line-height: 1.1;
}

.local-editor-stat span {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: var(--editor-font-meta);
}

.local-editor-tabs {
  display: grid;
  min-height: 50px;
  padding: 5px 12px;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  flex: none;
  border-bottom: 1px solid rgba(96, 165, 250, 0.15);
  background: var(--console-bg-soft);
}

.local-editor-tab {
  position: relative;
  min-width: 0;
  min-height: 40px;
  padding: 5px 8px 5px 37px;
  color: var(--console-text-dim);
  border: 1px solid rgba(96, 165, 250, 0.12);
  background: rgba(15, 23, 42, 0.42);
  text-align: left;
  clip-path: polygon(0 0, calc(100% - 14px) 0, 100% 50%, calc(100% - 14px) 100%, 0 100%, 14px 50%);
}

.local-editor-tab:first-child {
  border-radius: 12px 0 0 12px;
  clip-path: polygon(0 0, calc(100% - 14px) 0, 100% 50%, calc(100% - 14px) 100%, 0 100%);
}

.local-editor-tab:last-child {
  border-radius: 0 12px 12px 0;
  clip-path: polygon(0 0, 100% 0, 100% 100%, 0 100%, 14px 50%);
}

.local-editor-tab > span {
  position: absolute;
  top: 8px;
  left: 12px;
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border: 1px solid rgba(96, 165, 250, 0.18);
  border-radius: 50%;
  background: var(--console-bg);
  font-size: var(--editor-font-meta);
  font-weight: 700;
}

.local-editor-tab strong, .local-editor-tab small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-tab strong {
  color: var(--console-text-secondary);
  font-size: var(--editor-font-body);
  line-height: 1.15;
}

.local-editor-tab small {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: var(--editor-font-helper);
  line-height: 1.1;
}

.local-editor-tab.is-active {
  color: var(--console-text-primary);
  border-color: rgba(96, 165, 250, 0.44);
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.42), rgba(14, 165, 233, 0.16));
  box-shadow: inset 0 0 0 1px rgba(147, 197, 253, 0.1);
}

.local-editor-tab.is-active > span, .local-editor-tab.is-complete > span {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.local-editor-body {
  display: flex;
  min-width: 0;
  min-height: 0;
  padding: 10px 12px;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.local-editor-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overflow: hidden;
}

.step-grid {
  display: grid;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  gap: 10px;
  overflow: hidden;
}

.step-grid-setup {
  grid-template-columns: 210px minmax(0, 0.92fr) minmax(0, 1.15fr);
  align-items: stretch;
}

.step-grid-master-detail {
  grid-template-columns: 190px minmax(0, 1fr);
}

.step-grid-cloud {
  grid-template-columns: 230px minmax(0, 1fr);
}

.step-grid-json {
  grid-template-columns: 190px minmax(0, 1fr) 260px;
}

.local-setup-stable-column, .detail-stack, .advanced-stack {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.local-setup-stable-column {
  overflow-y: auto;
}

.detail-stack > .list-card {
  flex: 0 0 auto;
}

.detail-stack > .point-list-card,
.detail-stack > .mapping-list-card {
  min-height: 150px;
  max-height: 250px;
}

.detail-stack > .alarm-list-card {
  min-height: 170px;
  max-height: 240px;
}

.detail-stack > .alarm-list-card.is-empty {
  max-height: 210px;
}

.detail-stack > .editor-card, .cloud-bottom-grid {
  flex: 1 1 54%;
  min-height: 0;
}

.local-section-card, .readonly-card {
  min-width: 0;
  padding: var(--editor-card-padding);
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.local-section-head, .compact-head, .point-detail-hero {
  display: flex;
  margin-bottom: var(--editor-card-content-offset);
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.local-section-head > div,
.compact-head > div {
  min-width: 0;
}

.local-section-head h3, .point-detail-hero strong, .field-group h3, .overview-card h3 {
  color: var(--console-text-primary);
  font-size: var(--editor-font-section-title);
  line-height: 1.2;
}

.local-connection-card, .list-card, .editor-card, .payload-card, .json-editor-card, .schema-card, .overview-card {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.overview-card {
  gap: var(--editor-sidebar-gap);
  overflow-y: auto;
}

.schema-card {
  overflow-y: auto;
}

.local-connection-body {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
}

.local-connection-body .dynamic-form, .local-connection-body .dynamic-form :deep(.dynamic-form) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.local-connection-body :deep(.protocol-form-grid) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--editor-form-row-gap) var(--editor-form-column-gap);
}

.local-connection-body :deep(.protocol-field-row) {
  grid-template-columns: 72px minmax(0, 1fr);
}

.local-connection-body :deep(.protocol-field-row.is-wide) {
  grid-template-columns: minmax(96px, 108px) minmax(0, 1fr);
}

.form-grid, .readonly-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--editor-form-row-gap) var(--editor-form-column-gap);
}

.readonly-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.wide-field, .field-group-wide {
  grid-column: 1 / -1;
}

.form-grid label, .change-note-label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--editor-label-control-gap);
  color: var(--console-text-muted);
  font-size: var(--editor-font-label);
}

input, select, textarea {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: var(--editor-control-height);
  color: var(--console-text-primary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
  font-size: var(--editor-font-body);
}

input, select {
  height: var(--editor-control-height);
  padding: 0 10px;
}

button {
  height: var(--editor-button-height);
  min-height: var(--editor-button-height);
  padding: 0 10px;
  font-size: var(--editor-font-body);
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel-soft);
  cursor: pointer;
}

button:hover {
  border-color: var(--console-primary-hover);
  color: var(--console-text-primary);
}

button.primary {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

button.danger {
  color: #fecaca;
  border-color: rgba(248, 113, 113, 0.45);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.compact-select {
  width: min(180px, 100%);
}

.metric-stack {
  display: grid;
  gap: var(--editor-sidebar-gap);
}

.metric-item {
  display: flex;
  min-height: 34px;
  padding: 5px 2px 5px 9px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-left: 2px solid rgba(96, 165, 250, 0.28);
  background: transparent;
}

.metric-item span {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: var(--editor-font-label);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-item strong {
  max-width: 50%;
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: var(--editor-font-metric-value);
  font-weight: 700;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-item.is-ok strong, .is-ok {
  color: #34d399;
}

.metric-item.is-warn strong, .is-warn {
  color: #fb923c;
}

.metric-item.is-error strong, .is-error {
  color: #f87171;
}

.hint-list, .validation-list, .local-checklist {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 6px;
  list-style: none;
}

.local-checklist li, .hint-list li, .validation-list li {
  display: flex;
  min-height: 30px;
  padding: 5px 3px 5px 0;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
}

.local-checklist li:hover,
.hint-list li:hover,
.validation-list li:hover {
  background: rgba(96, 165, 250, 0.06);
}

.status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--console-text-dim);
}

.local-checklist li.is-ok .status-dot {
  background: #34d399;
}

.local-checklist li.is-warn .status-dot {
  background: #fb923c;
}

.local-checklist li.is-error .status-dot {
  background: #f87171;
}

.table-wrap {
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
  border: 1px solid rgba(96, 165, 250, 0.14);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel);
}

.editor-table {
  width: 100%;
  min-width: 920px;
  border-collapse: collapse;
  color: var(--console-text-secondary);
  font-size: 12px;
  table-layout: fixed;
}

.editor-table th, .editor-table td, .schema-table th, .schema-table td {
  height: 36px;
  padding: 0 8px;
  overflow: hidden;
  border-bottom: 1px solid var(--console-border-soft);
  text-align: left;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
  line-height: 1.2;
}

.editor-table th, .schema-table th {
  height: 34px;
  color: var(--console-text-muted);
  background: var(--console-bg-soft);
  font-size: var(--editor-font-table);
  font-weight: 700;
}

.editor-table tr:hover td {
  background: rgba(14, 165, 233, 0.08);
}

.editor-table tr.is-selected td {
  background: rgba(59, 130, 246, 0.16);
}

.editor-table tr.is-selected td:first-child {
  box-shadow: inset 3px 0 0 var(--console-primary);
}

.action-cell {
  display: table-cell;
  vertical-align: middle;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.text-action {
  min-height: auto;
  padding: 0;
  color: #bfdbfe;
  border: 0;
  background: transparent;
  font-size: 12px;
}

.text-action.danger {
  color: #fca5a5;
  border: 0;
}

.point-select-button {
  display: inline-grid;
  min-width: 0;
  max-width: 100%;
  padding: 0;
  justify-items: start;
  color: inherit;
  border: 0;
  background: transparent;
}

.point-select-button strong {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.point-detail-panel, .editor-card {
  overflow-y: auto;
}

.point-detail-hero {
  min-height: 44px;
  padding: 7px 10px;
  border: 0;
  border-left: 3px solid rgba(96, 165, 250, 0.55);
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.36);
}

.point-detail-hero p {
  margin-top: 4px;
  color: var(--console-text-muted);
  font-size: 12px;
}

.field-group {
  min-width: 0;
}

.field-group h3 {
  margin: 0 0 6px;
}

.advanced-collapse {
  min-width: 0;
  color: var(--console-text-secondary);
}

.advanced-collapse summary {
  margin-bottom: 6px;
  cursor: pointer;
  color: #bfdbfe;
  font-size: 13px;
  font-weight: 700;
}

.protocol-point-note code {
  margin-left: 6px;
  padding: 2px 5px;
  border: 1px solid var(--console-border-soft);
  border-radius: 6px;
  background: var(--console-bg-soft);
}

.cloud-bottom-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 12px;
  overflow: hidden;
}

.json-preview, .point-json-textarea {
  min-height: 0;
  color: #dbeafe;
  border: 1px solid #1e3a5f;
  border-radius: var(--console-radius-md);
  background: #0f172a;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
}

.json-preview {
  flex: 1 1 auto;
  margin: 8px 0 0;
  padding: 10px;
  overflow: auto;
}

.point-json-textarea {
  flex: 1 1 auto;
  padding: 10px;
  resize: none;
}

.change-note {
  min-height: 58px;
  padding: 8px;
  resize: vertical;
}

.json-nav-item {
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
}

.json-nav-item.is-active {
  border-color: var(--console-primary);
  background: rgba(37, 99, 235, 0.2);
}

.json-nav-item.is-modified small {
  color: #fb923c;
}

.schema-tabs {
  display: flex;
  gap: 6px;
  overflow-x: auto;
}

.schema-tabs button {
  flex: 0 0 auto;
}

.schema-tabs button.is-active {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.schema-table-wrap {
  min-height: 120px;
  overflow-x: hidden;
  overflow-y: auto;
  border: 1px solid rgba(96, 165, 250, 0.14);
  border-radius: var(--console-radius-md);
}

.schema-table {
  width: 100%;
  min-width: 0;
  border-collapse: collapse;
  font-size: 12px;
  table-layout: fixed;
}

.schema-table small {
  display: block;
  margin-top: 3px;
  color: var(--console-text-dim);
  white-space: normal;
}

.empty-state {
  display: grid;
  min-height: 96px;
  place-content: center;
  gap: 6px;
  color: var(--console-text-muted);
  border: 1px dashed var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-bg-soft);
  text-align: center;
}

.logic-preview {
  display: none;
}

.alarm-condition-hint {
  height: 26px;
  margin-top: 6px;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: var(--editor-font-meta);
  line-height: 26px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-footer {
  display: flex;
  min-height: 58px;
  padding: 10px 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  border-top: 1px solid var(--panel-line);
  background: var(--console-panel);
}

.check-line {
  display: inline-flex;
  width: auto;
  align-items: center;
  gap: 6px;
  color: var(--console-text-muted);
  font-size: 12px;
}

.check-line input {
  width: auto;
  min-height: 0;
}

:deep(.el-input), :deep(.el-input-number), :deep(.el-select), :deep(.el-input__wrapper), :deep(.el-select__wrapper) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

:deep(.el-input__wrapper), :deep(.el-select__wrapper) {
  min-height: var(--editor-control-height);
  background: var(--console-bg-soft);
  box-shadow: 0 0 0 1px var(--console-border-soft) inset;
}

:deep(.el-input__inner), :deep(.el-select__placeholder), :deep(.el-input-number .el-input__inner) {
  color: var(--console-text-primary);
  font-size: var(--editor-font-body);
}

:deep(.el-switch__label), :deep(.el-select-dropdown__item) {
  font-size: var(--editor-font-body);
}

.local-editor .local-section-card,
.local-editor .readonly-card {
  padding: var(--editor-card-padding);
  border-color: var(--editor-card-border);
  border-radius: 12px;
  background: linear-gradient(180deg, var(--editor-card-bg), var(--console-panel));
  box-shadow: inset 0 1px 0 rgba(148, 163, 184, 0.04);
}

.overview-card,
.metric-stack {
  gap: var(--editor-sidebar-gap);
}

.local-editor .form-grid,
.local-editor .readonly-grid {
  gap: var(--editor-form-row-gap) var(--editor-form-column-gap);
}

.local-editor input,
.local-editor select,
.local-editor textarea {
  border-radius: 7px;
  background: rgba(15, 23, 42, 0.7);
}

.local-editor :deep(input),
.local-editor :deep(select) {
  box-sizing: border-box !important;
  height: var(--editor-control-height) !important;
  min-height: var(--editor-control-height) !important;
}

.local-editor :deep(input[type="checkbox"]),
.local-editor :deep(input[type="radio"]) {
  height: auto !important;
  min-height: 0 !important;
}

.local-editor .metric-item {
  box-sizing: border-box;
  height: 34px;
  min-height: 34px;
  padding: 5px 2px 5px 9px;
}

.local-editor .cloud-sidebar .form-grid {
  grid-template-columns: minmax(0, 1fr);
}

.local-editor .overview-card {
  gap: 0;
}

.local-editor .overview-card > :not(.editor-section-header) + :not(.editor-section-header) {
  margin-top: var(--editor-sidebar-gap);
}

.local-editor :deep(.progress-rail .local-checklist) {
  margin: 0;
  padding: 0;
}

.local-editor .metric-item strong {
  font-size: var(--editor-font-metric-value) !important;
}

.local-device-panel .overview-card .metric-item strong {
  font-size: var(--editor-font-metric-value) !important;
}

.local-device-panel :deep(.overview-card .metric-item strong) {
  font-size: var(--editor-font-metric-value) !important;
}

.local-editor .local-checklist li,
.local-editor .hint-list li,
.local-editor .validation-list li {
  min-height: 30px;
  padding: 6px 8px;
}

.point-table.editor-table {
  min-width: 760px;
}

.alarm-table-wrap .editor-table {
  min-width: 780px;
}

.mapping-table-wrap .editor-table {
  min-width: 790px;
}

.local-editor .editor-table th,
.local-editor .editor-table td,
.local-editor .schema-table th,
.local-editor .schema-table td {
  padding: 0 8px;
}

.local-editor .cloud-bottom-grid {
  grid-template-columns: minmax(0, 1.15fr) minmax(280px, 0.85fr);
  gap: 10px;
}

.local-editor .json-preview,
.local-editor .point-json-textarea {
  margin: 0;
  padding: 10px;
  background: #0b1220;
  font-size: 12px;
  line-height: 1.5;
}

.local-editor .schema-table {
  min-width: 0;
}

.schema-table th:nth-child(4),
.schema-table td:nth-child(4) {
  white-space: normal;
}

.logic-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.logic-flow strong {
  max-width: 210px;
  padding: 6px 10px;
  overflow: hidden;
  color: #dbeafe;
  border: 1px solid rgba(96, 165, 250, 0.3);
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.55);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logic-flow strong:not(:last-child)::after {
  margin-left: 8px;
  color: #60a5fa;
  content: "→";
}

.level-badge,
.state-badge {
  display: inline-flex;
  min-height: 20px;
  padding: 2px 7px;
  align-items: center;
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 800;
}

.level-badge.is-critical {
  color: #fecaca;
  border-color: rgba(248, 113, 113, 0.46);
  background: rgba(127, 29, 29, 0.28);
}
.level-badge.is-warning {
  color: #fed7aa;
  border-color: rgba(251, 146, 60, 0.46);
  background: rgba(124, 45, 18, 0.26);
}
.level-badge.is-info {
  color: #bfdbfe;
  border-color: rgba(96, 165, 250, 0.42);
  background: rgba(30, 64, 175, 0.22);
}
.level-badge.is-unset {
  color: var(--console-text-muted);
  background: rgba(15, 23, 42, 0.42);
}
.state-badge.is-on {
  color: #bbf7d0;
  border-color: rgba(52, 211, 153, 0.42);
  background: rgba(6, 78, 59, 0.26);
}
.state-badge.is-off {
  color: #cbd5e1;
  background: rgba(51, 65, 85, 0.4);
}

.local-editor .local-editor-footer {
  min-height: 50px;
  padding: 8px 14px;
}

.local-editor,
.local-editor * {
  scrollbar-color: rgba(96, 165, 250, 0.45) rgba(15, 23, 42, 0.35);
  scrollbar-width: thin;
}

.local-editor ::-webkit-scrollbar {
  width: 7px;
  height: 7px;
}

.local-editor ::-webkit-scrollbar-track {
  background: rgba(15, 23, 42, 0.35);
}

.local-editor ::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(96, 165, 250, 0.45);
}

@media (max-width: 1366px) {
.local-device-panel {
  width: clamp(1100px, 90vw, 1440px);
  height: clamp(650px, 88vh, 820px);
}

.step-grid-setup {
  grid-template-columns: 190px minmax(0, 0.92fr) minmax(0, 1.12fr);
}

.step-grid-master-detail {
  grid-template-columns: 190px minmax(0, 1fr);
}

.step-grid-json {
  grid-template-columns: 190px minmax(0, 1fr) 260px;
}

.local-editor-title {
  min-height: 54px;
}

.local-editor-tabs {
  min-height: 50px;
  padding: 5px 12px;
}

.local-editor-tab small {
  display: none;
}
 }

@media (max-width: 1180px) {
.local-device-panel {
  width: 94vw;
  height: clamp(650px, 88vh, 820px);
}

.step-grid-setup {
  grid-template-columns: 180px minmax(0, 0.9fr) minmax(0, 1.1fr);
}

.step-grid-master-detail,
.step-grid-cloud {
  grid-template-columns: 180px minmax(0, 1fr);
}

.step-grid-json {
  grid-template-columns: 180px minmax(0, 1fr) 200px;
}

.cloud-bottom-grid {
  grid-template-columns: minmax(0, 1fr) minmax(250px, 0.8fr);
}

.overview-card {
  max-height: none;
}

.local-editor-tabs {
  overflow-x: auto;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}
 }

@media (max-height: 760px) {
.local-device-panel {
  height: min(92vh, 680px);
}
}

@media (max-width: 960px) {
.form-grid, .readonly-grid {
  grid-template-columns: 1fr;
}

.local-editor-title, .local-editor-footer {
  align-items: flex-start;
  flex-direction: column;
}

.local-step-actions, .table-actions {
  flex-wrap: wrap;
  justify-content: flex-start;
}

.compact-select {
  width: 100%;
}
 }

.point-list-panel,
.point-detail-panel {
  min-width: 0;
}

.point-workspace,
.local-point-workspace {
  align-items: start;
}

/* 编辑器统一三层标题系统：一级卡片、当前对象、卡片内子区域。 */
.local-editor :deep(.editor-section-header) {
  display: flex;
  box-sizing: border-box;
  min-width: 0;
  min-height: 44px;
  margin: calc(-1 * var(--editor-card-padding)) calc(-1 * var(--editor-card-padding)) var(--editor-card-content-offset);
  padding: 0 var(--editor-card-padding);
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex: 0 0 44px;
  height: 44px;
  max-height: 44px;
  overflow: hidden;
  border-bottom: 1px solid rgba(96, 165, 250, 0.13);
}

.local-editor :deep(.editor-section-heading),
.local-editor :deep(.editor-section-actions) {
  display: flex;
  min-width: 0;
  align-items: center;
}

.local-editor :deep(.editor-section-heading) {
  gap: 7px;
  overflow: hidden;
  flex: 1 1 auto;
  justify-content: flex-start;
  text-align: left;
}

.local-editor :deep(.editor-section-actions) {
  justify-content: flex-end;
  gap: 6px;
  flex: 0 1 auto;
}

.local-editor :deep(.editor-section-title) {
  min-width: 0;
  margin: 0;
  color: var(--console-text-primary);
  font-size: var(--editor-font-section-title);
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
  flex: 0 0 auto;
}

.local-editor :deep(.editor-section-badge) {
  display: inline-flex;
  min-width: 0;
  max-width: 120px;
  padding: 2px 6px;
  overflow: hidden;
  color: #bfdbfe;
  border: 1px solid rgba(59, 130, 246, 0.34);
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.18);
  font-size: 10px;
  font-weight: 800;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 0 1 auto;
}

.local-editor :deep(.editor-section-subtitle) {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1 1 auto;
}

.local-editor :deep(.editor-object-header) {
  border: 0;
  border-bottom: 1px solid rgba(96, 165, 250, 0.13);
  background: transparent;
}

.local-editor :deep(.editor-object-heading),
.local-editor :deep(.editor-object-actions) {
  display: flex;
  min-width: 0;
  align-items: center;
}

.local-editor :deep(.editor-object-heading) {
  gap: 7px;
  overflow: hidden;
  flex: 1 1 auto;
  text-align: left;
}

.local-editor :deep(.editor-object-badge) {
  flex: 0 1 auto;
}

.local-editor :deep(.editor-object-title) {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: var(--editor-font-section-title);
  font-weight: 700;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 0 0 auto;
}

.local-editor :deep(.editor-object-meta) {
  min-width: 0;
  overflow: hidden;
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1 1 auto;
}

.local-editor :deep(.editor-subsection-title),
.local-editor :deep(.field-group h3),
.local-editor :deep(.advanced-collapse summary) {
  min-width: 0;
  margin: 0 0 var(--editor-subsection-bottom);
  padding: 6px 0 5px 8px;
  color: var(--console-text-primary);
  border-left: 2px solid var(--console-primary-hover);
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
  text-align: left;
}

.local-editor :deep(.point-field-grid) {
  display: grid;
  min-width: 0;
  gap: var(--editor-form-row-gap) var(--editor-form-column-gap);
}

.local-editor :deep(.point-field-grid-two-column) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.local-editor :deep(.point-field-grid-single) {
  grid-template-columns: minmax(0, 1fr);
}

.local-editor :deep(.point-field-grid-dense) {
  grid-template-columns: repeat(12, minmax(0, 1fr));
}

.local-editor :deep(.point-field-grid-dense > label) {
  min-width: 0;
  grid-column: span var(--field-span, 4);
}

.local-editor :deep(.point-field-grid-four-column) {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.local-editor :deep(.point-field-grid-four-column > label) {
  min-width: 0;
  grid-column: span var(--field-span, 1);
}

.local-editor :deep(.point-field-grid > .point-field-full),
.local-editor :deep(.point-field-grid > .wide-field) {
  grid-column: 1 / -1;
}

.local-editor :deep(.point-field-grid label) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--editor-label-control-gap);
  color: var(--console-text-muted);
  font-size: 12px;
}

.local-editor :deep(.field-label-text) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor .dense-form-grid,
.local-editor .alarm-rule-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.local-editor .alarm-editor-card {
  flex: 0 0 auto;
  max-height: 100%;
  overflow-y: auto;
}

.local-editor .alarm-rule-form {
  flex: 0 0 auto;
}

.local-editor .point-list-card :deep(.editor-section-actions) {
  max-width: 68%;
}

.local-editor .point-list-card :deep(.table-actions),
.local-editor .alarm-list-card :deep(.table-actions) {
  min-width: 0;
  flex-wrap: nowrap;
}

.local-editor .point-list-card :deep(.table-actions) .compact-select,
.local-editor .alarm-list-card :deep(.table-actions) .compact-select {
  width: 150px;
  min-width: 0;
}

.local-editor .point-list-card,
.local-editor .mapping-list-card,
.local-editor .alarm-list-card {
  overflow-y: auto;
}

/* 弹窗内普通控件和开关按基础连接页的 32px 节奏对齐。 */
.local-editor :deep(.el-input__wrapper),
.local-editor :deep(.el-select__wrapper),
.local-editor :deep(.el-input-number),
.local-editor :deep(.el-input-number .el-input__wrapper) {
  box-sizing: border-box;
  height: var(--editor-control-height);
  min-height: var(--editor-control-height);
}

.local-editor :deep(.el-switch) {
  box-sizing: border-box;
  min-height: var(--editor-control-height);
  align-items: center;
}

.local-editor :deep(.el-button:not(.is-link, .is-text)) {
  box-sizing: border-box;
  height: var(--editor-button-height);
  min-height: var(--editor-button-height);
  padding-top: 0;
  padding-bottom: 0;
}

@media (max-width: 1149px) and (min-width: 900px) {
  .local-editor :deep(.point-field-grid-dense > label) {
    grid-column: span 4;
  }

  .local-editor :deep(.point-field-grid-dense > label[style*="--field-span: 3"]) {
    grid-column: span 3;
  }
}

@media (max-width: 899px) and (min-width: 650px) {
  .local-editor :deep(.editor-section-subtitle) {
    display: none;
  }

  .local-editor :deep(.point-field-grid-dense) {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }

  .local-editor :deep(.point-field-grid-four-column) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .local-editor :deep(.point-field-grid-dense > label),
  .local-editor :deep(.point-field-grid-dense > label[style*="--field-span: 3"]) {
    grid-column: span 3;
  }

  .local-editor .dense-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 649px) and (min-width: 520px) {
  .local-editor :deep(.editor-section-subtitle),
  .local-editor :deep(.editor-section-badge) {
    display: none;
  }

  .local-editor :deep(.point-field-grid-dense),
  .local-editor :deep(.point-field-grid-four-column) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .local-editor :deep(.point-field-grid-dense > label),
  .local-editor :deep(.point-field-grid-dense > label[style*="--field-span: 3"]) {
    grid-column: span 1;
  }

  .local-editor .dense-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 519px) {
  .local-editor :deep(.editor-section-subtitle),
  .local-editor :deep(.editor-section-badge) {
    display: none;
  }

  .local-editor :deep(.point-field-grid-dense) {
    grid-template-columns: minmax(0, 1fr);
  }

  .local-editor :deep(.point-field-grid-four-column) {
    grid-template-columns: minmax(0, 1fr);
  }

  .local-editor :deep(.point-field-grid-dense > label),
  .local-editor :deep(.point-field-grid-dense > label[style*="--field-span: 3"]) {
    grid-column: 1 / -1;
  }

  .local-editor .dense-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 1049px) and (min-width: 900px) {
  .local-editor :deep(.point-field-grid-four-column) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 980px) {
  .local-editor :deep(.editor-section-badge) {
    display: none;
  }

  .local-editor :deep(.editor-section-actions) {
    gap: 4px;
  }
}
</style>
