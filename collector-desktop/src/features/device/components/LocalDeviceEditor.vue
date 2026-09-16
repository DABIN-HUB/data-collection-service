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
          <p>创建并配置新的工业协议采集终端</p>
        </div>
        <div class="local-editor-title-actions">
          <div class="local-editor-stats">
            <div class="local-editor-stat">
              <strong id="localEditorProtocolText">{{ currentProtocolTitle }}</strong>
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
                <div class="local-section-head">
                  <div>
                    <span class="label-chip">设备基础</span>
                    <h3>设备基础与调度参数</h3>
                  </div>
                  <p>设备标识、协议和采集节奏集中配置；切换协议不会丢失点位编辑内容。</p>
                </div>
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
                <div class="local-section-head">
                  <div>
                    <span class="label-chip">云平台身份</span>
                    <h3>设备级云目标</h3>
                  </div>
                  <p>绑定当前真实 cloudTarget；点位级 reportField 在第 04 步维护。</p>
                </div>
                <CloudTargetForm :cloud-target="cloudTarget" :topic-preview="cloudTopicPreview" @update-field="updateCloudTargetField" />
              </section>
            </div>

            <section class="local-section-card local-connection-card">
              <div class="local-section-head">
                <div>
                  <span class="label-chip">连接参数</span>
                  <h3>协议对应字段</h3>
                </div>
                <p>按后端 Protocol Schema 动态渲染；字段多时仅在本卡片内纵向滚动。</p>
              </div>
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
              <span class="label-chip">建模概览</span>
              <h3>建模概览</h3>
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
              <section class="local-section-card list-card">
                <div class="local-section-head compact-head">
                  <div>
                    <span class="label-chip">点位列表</span>
                    <h3>管理设备的所有采集点位</h3>
                  </div>
                  <div class="inline-actions table-actions">
                    <input id="localPointSearch" v-model="pointKeyword" class="compact-select" type="search" placeholder="搜索点位编码 / 名称 / 地址">
                    <select v-model="pointDataTypeFilter" class="compact-select"><option value="">全部类型</option><option v-for="item in pointDataTypes" :key="item" :value="item">{{ item }}</option></select>
                    <select v-model="pointReadWriteFilter" class="compact-select"><option value="">全部读写</option><option v-for="item in readWriteOptions" :key="String(item.value)" :value="String(item.value)">{{ item.label }}</option></select>
                    <button id="addLocalPointBtn" type="button" @click="addPoint">新增点位</button>
                  </div>
                </div>
                <div class="table-wrap compact point-table-wrap">
                  <table class="point-table editor-table">
                    <thead><tr><th>选择</th><th>序号</th><th>点位名称</th><th>点位标识</th><th>数据类型</th><th>寄存器地址</th><th>读写类型</th><th>缩放系数</th><th>操作</th></tr></thead>
                    <tbody id="localPointRows">
                      <tr v-for="row in filteredPoints" :key="row.pointCode || row.address || points.indexOf(row)" :class="{ 'is-selected': points.indexOf(row) === selectedPointIndex }" @dblclick="selectPoint(row)">
                        <td><input type="radio" name="selectedPoint" :checked="points.indexOf(row) === selectedPointIndex" @change="selectPoint(row)"></td>
                        <td>{{ points.indexOf(row) + 1 }}</td>
                        <td><button type="button" class="point-select-button" :data-select-local-point="points.indexOf(row)" @click="selectPoint(row)"><strong>{{ row.pointName || row.pointCode || '-' }}</strong></button></td>
                        <td>{{ row.pointCode || '-' }}</td>
                        <td>{{ row.dataType || '-' }}</td>
                        <td>{{ row.address || '-' }}</td>
                        <td>{{ row.readWrite || '-' }}</td>
                        <td>{{ row.scalingFactor ?? '-' }}</td>
                        <td class="row-actions"><button type="button" @click="selectPoint(row)">编辑</button><button type="button" @click="duplicatePoint(row)">复制</button><button type="button" class="danger" @click="removePoint(row)">删除</button></td>
                      </tr>
                      <tr v-if="filteredPoints.length === 0"><td colspan="9">{{ pointKeyword ? '没有匹配的点位' : '暂无点位' }}</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section class="local-section-card editor-card point-detail-panel">
                <PointEditorHeader :point="selectedPoint" />
                <div v-if="selectedPoint" class="point-detail-stack">
                  <FieldGroup title="主要字段">
                    <PointFieldGrid :fields="primaryPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
                  </FieldGroup>
                  <details class="advanced-collapse" open>
                    <summary>高级参数 / 协议扩展 / 只读信息</summary>
                    <div class="advanced-stack">
                      <FieldGroup title="数据处理"><PointFieldGrid :fields="dataPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup title="上报 / 缓存"><PointFieldGrid :fields="reportPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                      <FieldGroup :title="protocolPointTitle">
                        <div class="protocol-point-note"><p v-if="protocolPointNotes.addressHints.length">当前协议地址示例：<code v-for="hint in protocolPointNotes.addressHints" :key="hint">{{ hint }}</code></p><p v-for="message in protocolPointNotes.messages" :key="message">{{ message }}</p></div>
                        <PointFieldGrid v-if="protocolPointFields.length" :fields="protocolPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
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
              <span class="label-chip">规则概览</span>
              <h3>告警规则</h3>
              <div class="metric-stack">
                <MetricItem label="规则总数" :value="String(alarmRuleRows.length)" />
                <MetricItem label="已启用" :value="String(enabledAlarmRuleCount)" tone="ok" />
                <MetricItem label="严重" :value="String(alarmLevelCounts.CRITICAL || 0)" tone="error" />
                <MetricItem label="重要/错误" :value="String((alarmLevelCounts.ERROR || 0) + (alarmLevelCounts.WARNING || 0))" tone="warn" />
                <MetricItem label="事件最小间隔" :value="eventIntervalSummary" />
              </div>
              <ul class="hint-list">
                <li>当前 Contract：point.alarmEnabled + point.alarmRule。</li>
                <li>删除点位后，其点位内告警规则会自然随点位删除。</li>
                <li>未配置通知方式字段，不展示短信/邮件等假能力。</li>
              </ul>
            </aside>

            <div class="detail-stack">
              <section class="local-section-card list-card">
                <div class="local-section-head compact-head">
                  <div><span class="label-chip">告警规则列表</span><h3>按当前点位 alarmRule 生成</h3></div>
                  <div class="inline-actions table-actions">
                    <select v-model="alarmPointFilter" class="compact-select"><option value="">全部点位</option><option v-for="point in points" :key="point.pointCode || point.pointId" :value="point.pointCode || point.pointId || ''">{{ point.pointName || point.pointCode }}</option></select>
                    <select v-model="alarmLevelFilter" class="compact-select"><option value="">全部级别</option><option v-for="level in alarmLevels" :key="level.value" :value="level.value">{{ level.label }}</option></select>
                    <select v-model="alarmEnabledFilter" class="compact-select"><option value="">全部状态</option><option value="true">启用</option><option value="false">禁用</option></select>
                    <button type="button" @click="addAlarmRuleForCurrent">新增规则</button>
                  </div>
                </div>
                <div class="table-wrap compact alarm-table-wrap">
                  <table class="point-table editor-table">
                    <thead><tr><th>关联点位</th><th>规则名称</th><th>告警类型</th><th>触发条件</th><th>持续时间</th><th>告警级别</th><th>启用状态</th><th>操作</th></tr></thead>
                    <tbody>
                      <tr v-for="row in filteredAlarmRows" :key="`${row.pointIndex}-${row.ruleIndex}`" :class="{ 'is-selected': row.pointIndex === selectedPointIndex && row.ruleIndex === selectedAlarmRuleIndex }" @click="selectAlarmRow(row)">
                        <td>{{ row.point.pointName || row.point.pointCode }}</td><td>{{ row.rule.ruleName || row.rule.ruleId || '未命名规则' }}</td><td>{{ row.rule.operator || '-' }}</td><td>{{ alarmConditionText(row) }}</td><td>{{ row.rule.duration ?? '-' }} s</td><td>{{ row.rule.level || '-' }}</td><td>{{ row.rule.enabled === false ? '禁用' : '启用' }}</td><td><button type="button" @click.stop="selectAlarmRow(row)">编辑</button><button type="button" class="danger" @click.stop="removeAlarmRuleAt(row.pointIndex, row.ruleIndex)">删除</button></td>
                      </tr>
                      <tr v-if="filteredAlarmRows.length === 0"><td colspan="8">暂无告警规则，请选择点位后新增。</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section class="local-section-card editor-card">
                <PointEditorHeader :point="selectedPoint" title="规则配置" />
                <FieldGroup v-if="selectedPoint" title="点位告警开关">
                  <PointFieldGrid :fields="alarmPointFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" />
                </FieldGroup>
                <div v-if="selectedPoint && currentAlarmRule" class="alarm-rule-form">
                  <div class="form-grid two-column">
                    <label>规则ID<el-input :model-value="String(currentAlarmRule.ruleId || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleId', $event)" /></label>
                    <label>规则名称<el-input :model-value="String(currentAlarmRule.ruleName || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'ruleName', $event)" /></label>
                    <label>运算符<el-select :model-value="String(currentAlarmRule.operator || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'operator', $event)"><el-option v-for="operator in alarmOperators" :key="operator" :label="operator" :value="operator" /></el-select></label>
                    <label>阈值<el-input-number :model-value="toNumber(currentAlarmRule.threshold)" controls-position="right" :step="0.0001" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'threshold', $event)" /></label>
                    <label>持续时间(s)<el-input-number :model-value="toNumber(currentAlarmRule.duration)" controls-position="right" :step="1" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'duration', $event)" /></label>
                    <label>告警级别<el-select :model-value="String(currentAlarmRule.level || '')" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'level', $event)"><el-option v-for="level in alarmLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></label>
                    <label>启用<el-select :model-value="currentAlarmRule.enabled === undefined ? '' : String(Boolean(currentAlarmRule.enabled))" clearable @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'enabled', parseBooleanOption($event))"><el-option label="是" value="true" /><el-option label="否" value="false" /></el-select></label>
                    <label class="wide-field">描述<el-input :model-value="String(currentAlarmRule.description || '')" @update:model-value="updateAlarmRule(selectedAlarmRuleIndex, 'description', $event)" /></label>
                  </div>
                  <div class="logic-preview"><span>触发逻辑预览</span><strong>{{ alarmLogicPreview }}</strong></div>
                </div>
                <div v-else class="empty-state"><strong>暂无可编辑规则</strong><span>选择已有规则，或点击“新增规则”。</span></div>
              </section>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 3" class="local-editor-pane" data-local-editor-pane="cloud">
          <div class="step-grid step-grid-cloud">
            <aside class="local-section-card overview-card cloud-sidebar">
              <span class="label-chip">云端目标与身份</span>
              <h3>cloudTarget</h3>
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
              <section class="local-section-card list-card">
                <div class="local-section-head compact-head"><div><span class="label-chip">属性映射列表</span><h3>从当前 points 自动生成</h3></div></div>
                <div class="table-wrap compact mapping-table-wrap">
                  <table class="point-table editor-table cloud-point-table">
                    <thead><tr><th>选择</th><th>序号</th><th>点位名称</th><th>本地标识</th><th>云端属性编码</th><th>上报类型</th><th>转换规则</th><th>单位</th><th>启用状态</th><th>操作</th></tr></thead>
                    <tbody id="localCloudRows">
                      <tr v-for="(row, index) in points" :key="row.pointCode || row.address || index" :class="{ 'is-selected': index === selectedPointIndex }">
                        <td><input type="radio" name="cloudPoint" :checked="index === selectedPointIndex" @change="selectPoint(row)"></td><td>{{ index + 1 }}</td><td>{{ row.pointName || row.pointCode || '-' }}</td><td>{{ row.pointCode || '-' }}</td><td>{{ row.additionalConfig?.reportField || '-' }}</td><td>{{ row.additionalConfig?.eventEnabled ? '属性+事件' : '属性' }}</td><td>{{ transformRuleText(row) }}</td><td>{{ row.unit || '-' }}</td><td>{{ cloudPointStatus(row, cloudTarget) }}</td><td><button type="button" @click="selectPoint(row)">编辑</button></td>
                      </tr>
                      <tr v-if="points.length === 0"><td colspan="10">暂无点位</td></tr>
                    </tbody>
                  </table>
                </div>
              </section>
              <div class="cloud-bottom-grid">
                <section class="local-section-card editor-card">
                  <PointEditorHeader :point="selectedPoint" title="属性映射编辑" />
                  <FieldGroup v-if="selectedPoint" title="reportField / 上报控制"><PointFieldGrid :fields="cloudReportFields" :field-component="fieldComponent" :field-props="fieldProps" :update-point-field="updatePointField" /></FieldGroup>
                  <FieldGroup title="事件映射"><p class="field-description">当前前端 Contract 未发现独立 event mapping 保存字段；这里根据点位 alarmRule、eventEnabled、eventMinIntervalMs 生成只读预览，不伪造新字段。</p><ul class="hint-list"><li v-for="item in eventMappingPreview" :key="item">{{ item }}</li></ul></FieldGroup>
                </section>
                <section class="local-section-card payload-card">
                  <div class="local-section-head"><div><span class="label-chip">Payload 预览</span><h3>实时构造示例</h3></div></div>
                  <select v-model="payloadPreviewMode" class="compact-select"><option value="property">属性上报</option><option value="event">事件上报</option><option value="full">完整配置摘要</option></select>
                  <pre class="json-preview">{{ payloadPreview }}</pre>
                </section>
              </div>
            </div>
          </div>
        </section>

        <section v-show="activeStep === 4" class="local-editor-pane" data-local-editor-pane="json">
          <div class="step-grid step-grid-json">
            <aside class="local-section-card overview-card">
              <span class="label-chip">高级配置导航</span>
              <h3>JSON 章节</h3>
              <button v-for="item in jsonSections" :key="item.key" type="button" class="json-nav-item" :class="{ 'is-active': jsonSection === item.key, 'is-modified': item.status === '有修改' }" @click="jsonSection = item.key"><span>{{ item.label }}</span><small>{{ item.status }}</small></button>
            </aside>
            <section class="local-section-card json-editor-card">
              <div class="local-section-head compact-head"><div><span class="label-chip">JSON 配置编辑器</span><h3>完整 Local Device 配置 JSON</h3></div><div class="inline-actions table-actions"><button type="button" @click="formatConfigJson">格式化</button><button type="button" @click="applyConfigJson">校验并应用</button><button type="button" @click="syncJsonFromState">恢复结构化表单当前值</button></div></div>
              <textarea id="localPointsJson" v-model="configJson" class="point-json-textarea" spellcheck="false"></textarea>
              <label class="change-note-label">变更说明（本次 UI session metadata，不提交后端）<textarea v-model="changeDescription" class="change-note" placeholder="可选：记录本次配置调整目的"></textarea></label>
            </section>
            <aside class="local-section-card schema-card">
              <span class="label-chip">校验与结构说明</span>
              <h3>校验结果</h3>
              <div class="metric-stack"><MetricItem label="错误数" :value="String(jsonValidation.errors.length)" :tone="jsonValidation.errors.length ? 'error' : 'ok'" /><MetricItem label="警告数" :value="String(jsonValidation.warnings.length)" :tone="jsonValidation.warnings.length ? 'warn' : 'ok'" /><MetricItem label="配置章节数" :value="String(jsonSections.length)" /><MetricItem label="点位数" :value="String(points.length)" /></div>
              <ul class="validation-list"><li v-for="item in jsonValidation.errors" :key="item" class="is-error">{{ item }}</li><li v-for="item in jsonValidation.warnings" :key="item" class="is-warn">{{ item }}</li><li v-if="!jsonValidation.errors.length && !jsonValidation.warnings.length" class="is-ok">当前 JSON 与结构化状态校验通过</li></ul>
              <div class="schema-tabs"><button v-for="tab in schemaTabs" :key="tab.key" type="button" :class="{ 'is-active': schemaTab === tab.key }" @click="schemaTab = tab.key">{{ tab.label }}</button></div>
              <div class="schema-table-wrap"><table class="schema-table"><thead><tr><th>字段</th><th>类型</th><th>必填</th><th>说明</th></tr></thead><tbody><tr v-for="row in activeSchemaRows" :key="row.field"><td>{{ row.field }}</td><td>{{ row.type }}</td><td>{{ row.required ? '是' : '否' }}</td><td>{{ row.description }}</td></tr></tbody></table></div>
              <FieldGroup title="生成配置预览"><div class="metric-stack"><MetricItem label="协议类型" :value="protocol" /><MetricItem label="设备地址" :value="connectionAddressSummary" /><MetricItem label="上报平台" :value="cloudTargetSummary({} as DataPoint, cloudTarget)" /><MetricItem label="告警规则" :value="String(alarmRuleRows.length)" /><MetricItem label="自定义标签" :value="changeDescription ? '有说明' : '未填写'" /></div></FieldGroup>
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
import { buildReadonlyItems, createUniqueCode, findDuplicatePointCode, alarmRules, parseBooleanOption, parseFieldValue, parsePointsJson, serializeAlarmRules, statusLabel, toNumber, type AlarmRule, type FieldValueType } from "@/features/point/utils/point-draft-utils";
import { cloneData, cloudPointStatus, cloudTargetSummary, defaultPointTemplate, firstPointValue, hasValue, isOpcUaProtocol, isPlainObject, normalizeCloudTarget, normalizeInitialPoints, sanitizePointForSave } from "@/features/device/utils/local-device-editor-utils";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

type ChecklistState = "ok" | "warn" | "error";
type FieldControl = "text" | "number" | "select" | "switch";
type JsonSectionKey = "connection" | "report" | "alarm" | "debug" | "metadata";
type SchemaTabKey = "protocol" | "point" | "report" | "alarm" | "metadata";

interface SelectOption { label: string; value: string | number | boolean }
interface PointEditorField { path: string; label: string; control?: FieldControl; valueType?: FieldValueType; options?: SelectOption[]; required?: boolean; description?: string; fullWidth?: boolean; disabled?: boolean; step?: number }
interface AlarmRuleRow { point: DataPoint; pointIndex: number; rule: AlarmRule; ruleIndex: number }
interface SchemaRow { field: string; type: string; required: boolean; description: string }

const EditorProgressRail = defineComponent({
  name: "EditorProgressRail",
  props: { validationTitle: { type: String, required: true }, items: { type: Array as PropType<Array<{ label: string; state: ChecklistState }>>, required: true } },
  setup(props) {
    return () => h("aside", { class: "local-section-card overview-card progress-rail" }, [
      h("span", { class: "label-chip" }, "配置进度"), h("h3", props.validationTitle), h("p", "红色项需要必填；切换分区不会丢失当前编辑内容。"),
      h("ol", { id: "localEditorChecklist", class: "local-checklist" }, props.items.map((item) => h("li", { class: [`is-${item.state}`] }, item.label)))
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
      h("label", ["云设备类型", h("select", { id: "localCloudDeviceType", value: props.cloudTarget.deviceType, onChange: (event: Event) => emit("update-field", "deviceType", (event.target as HTMLSelectElement).value) }, ["SUB_DEVICE", "GATEWAY", "DIRECT", "LOGICAL_SUB_DEVICE"].map((value) => h("option", { value }, value)))]),
      h("label", ["productKey", h("input", { id: "localCloudProductKey", value: props.cloudTarget.productKey || "", placeholder: "pk_xxx", onInput: (event: Event) => emit("update-field", "productKey", (event.target as HTMLInputElement).value) })]),
      h("label", ["deviceName", h("input", { id: "localCloudDeviceName", value: props.cloudTarget.deviceName || "", placeholder: "sub_device_001", onInput: (event: Event) => emit("update-field", "deviceName", (event.target as HTMLInputElement).value) })]),
      h("label", ["启用拓扑注册", h("select", { id: "localCloudTopologyEnabled", value: String(props.cloudTarget.topologyEnabled), onChange: (event: Event) => emit("update-field", "topologyEnabled", (event.target as HTMLSelectElement).value === "true") }, [h("option", { value: "true" }, "是"), h("option", { value: "false" }, "否")])]),
      h("label", { class: "wide-field" }, ["Topic 示例", h("input", { id: "localCloudTopicPreview", value: props.topicPreview, readonly: true })])
    ]);
  }
});

const FieldGroup = defineComponent({ name: "FieldGroup", props: { title: { type: String, required: true } }, setup(props, { slots }) { return () => h("section", { class: "field-group field-group-wide" }, [h("h3", props.title), slots.default?.()]); } });

const PointEditorHeader = defineComponent({
  name: "PointEditorHeader",
  props: { point: { type: Object as PropType<DataPoint | null>, default: null }, title: { type: String, default: "当前编辑" } },
  setup(props) { return () => props.point ? h("section", { class: "point-detail-hero" }, [h("div", [h("span", { class: "label-chip" }, props.title), h("strong", props.point?.pointName || props.point?.pointCode || "未命名点位"), h("p", `${props.point?.pointCode || "-"} · ${props.point?.address || "未设置地址"}`)]), h("div", { class: "point-detail-hero-meta" }, [h("span", { class: "pill subtle" }, props.point?.dataType || "-"), h("span", { class: "pill subtle" }, props.point?.readWrite || "-"), h("span", { class: "pill subtle" }, statusLabel(props.point?.status))])]) : h("div", { class: "empty-state" }, [h("strong", "暂无选中的点位"), h("span", "先新增一个点位，或从列表选择已有点位。")]); }
});

const PointFieldGrid = defineComponent({
  name: "PointFieldGrid",
  props: {
    fields: { type: Array as PropType<PointEditorField[]>, required: true },
    fieldComponent: { type: Function as PropType<(field: PointEditorField) => unknown>, required: true },
    fieldProps: { type: Function as PropType<(field: PointEditorField) => Record<string, unknown>>, required: true },
    updatePointField: { type: Function as PropType<(field: PointEditorField, value: unknown) => void>, required: true }
  },
  setup(props) {
    return () => h("div", { class: "form-grid two-column" }, props.fields.map((field) => h("label", { key: field.path, class: { "wide-field": field.fullWidth } }, [
      h("span", { class: "field-label-text" }, [field.label, field.required ? h("span", { class: "field-required" }, " *") : null]),
      h(props.fieldComponent(field) as string, { ...props.fieldProps(field), "onUpdate:modelValue": (value: unknown) => props.updatePointField(field, value) }, () => field.options?.map((option) => h(ElOption, { key: String(option.value), label: option.label, value: option.value }))),
      field.description ? h("small", { class: "field-description" }, field.description) : null
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
const currentProtocolTitle = computed(() => protocolSchema.value?.title ? `${protocolSchema.value.title} (${protocol.value})` : protocol.value);
const selectedPoint = computed<DataPoint | null>(() => points.value[selectedPointIndex.value] || null);
const duplicatePointCode = computed(() => findDuplicatePointCode(points.value));
const missingPointAddressCount = computed(() => points.value.filter((point) => !hasValue(point.address)).length);
const pointCompletenessText = computed(() => {
  if (!points.value.length) return "0%";
  const complete = points.value.filter((point) => hasValue(point.pointCode) && hasValue(point.pointName) && hasValue(point.address)).length;
  return `${Math.round((complete / points.value.length) * 100)}%`;
});
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
const totalReportFieldCount = computed(() => points.value.filter((point) => hasValue(point.additionalConfig?.reportField)).length);
const localEditorChecklist = computed<Array<{ label: string; state: ChecklistState }>>(() => {
  const missingPoint = points.value.find((point) => !hasValue(point.pointCode) || !hasValue(point.pointName) || !hasValue(point.address));
  const checks: Array<{ label: string; state: ChecklistState }> = [
    { label: deviceId.value.trim() ? "设备 ID 已填写" : "设备 ID 待填写", state: deviceId.value.trim() ? "ok" : "error" },
    { label: deviceName.value.trim() ? "设备名称已填写" : "设备名称待填写", state: deviceName.value.trim() ? "ok" : "error" },
    { label: connectionErrors.value.length === 0 ? "连接参数格式正常" : "连接参数需要修正", state: connectionErrors.value.length === 0 ? "ok" : "error" },
    { label: points.value.length > 0 ? `已配置 ${points.value.length} 个点位` : "至少需要 1 个点位", state: points.value.length > 0 ? "ok" : "error" },
    { label: duplicatePointCode.value ? `点位编码重复：${duplicatePointCode.value}` : "点位编码未重复", state: duplicatePointCode.value ? "error" : "ok" },
    { label: missingPoint ? "存在点位缺少编码、名称或地址" : "点位必填项完整", state: missingPoint ? "error" : "ok" },
    { label: totalReportFieldCount.value ? `已配置 ${totalReportFieldCount.value} 个上报属性` : "建议配置上报属性", state: totalReportFieldCount.value ? "ok" : "warn" }
  ];
  if (cloudTarget.enabled) checks.push({ label: cloudTarget.productKey && cloudTarget.deviceName ? "云设备身份已填写" : "云设备身份待填写", state: cloudTarget.productKey && cloudTarget.deviceName ? "ok" : "error" });
  return checks;
});
const validationTitle = computed(() => {
  const errorCount = localEditorChecklist.value.filter((item) => item.state === "error").length;
  const warnCount = localEditorChecklist.value.filter((item) => item.state === "warn").length;
  if (errorCount > 0) return `${errorCount} 个必填项待处理`;
  return warnCount > 0 ? `${warnCount} 个建议项可完善` : "必填配置已完成";
});
const readonlyItems = computed(() => buildReadonlyItems(selectedPoint.value));
const primaryPointFields = computed<PointEditorField[]>(() => [
  { path: "pointName", label: "点位名称", required: true }, { path: "pointCode", label: "点位标识", required: true, description: "修改点位编码时，如果 reportField 未单独改过，会同步更新云端属性。" }, { path: "dataType", label: "数据类型", control: "select", options: pointDataTypes.value.map((value) => ({ label: value, value })) }, { path: "address", label: "地址", required: true }, { path: "additionalConfig.readCount", label: "读取数量", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.byteOrder", label: "字节序" }, { path: "readWrite", label: "读写类型", control: "select", options: readWriteOptions }, { path: "scalingFactor", label: "缩放系数", control: "number", valueType: "number", step: 0.0001 }, { path: "offset", label: "偏移量", control: "number", valueType: "number", step: 0.0001 }, { path: "unit", label: "工程单位" }, { path: "precision", label: "小数位", control: "number", valueType: "integer", step: 1 }, { path: "collectionMode", label: "采集方式", control: "select", options: collectionModeOptions }, { path: "pointChangeThreshold", label: "变化上报阈值", control: "number", valueType: "number", step: 0.0001 }, { path: "remark", label: "点位描述", fullWidth: true }
]);
const dataPointFields = computed<PointEditorField[]>(() => [{ path: "deadband", label: "死区", control: "number", valueType: "number", step: 0.0001 }, { path: "minValue", label: "最小值", control: "number", valueType: "number", step: 0.0001 }, { path: "maxValue", label: "最大值", control: "number", valueType: "number", step: 0.0001 }, { path: "priority", label: "优先级", control: "number", valueType: "integer", step: 1 }, { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions }, { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }, { path: "status", label: "启用状态", control: "select", valueType: "integer", options: enableOptions }]);
const reportPointFields = computed<PointEditorField[]>(() => [{ path: "additionalConfig.reportEnabled", label: "参与设备上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.reportField", label: "云端属性（reportField）" }, { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: 0.0001 }, { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions }, { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }]);
const cloudReportFields = computed<PointEditorField[]>(() => [{ path: "additionalConfig.reportEnabled", label: "启用上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.reportField", label: "云端属性编码（reportField）" }, { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: 0.0001 }, { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: 1 }, { path: "additionalConfig.streamEnabled", label: "实时流", control: "select", valueType: "boolean", options: booleanOptions }, { path: "additionalConfig.historyEnabled", label: "历史", control: "select", valueType: "boolean", options: booleanOptions }]);
const alarmPointFields = computed<PointEditorField[]>(() => [{ path: "alarmEnabled", label: "启用告警", control: "select", valueType: "integer", options: enableOptions }]);
const protocolPointFields = computed<PointEditorField[]>(() => pointFields.value.map((field) => ({ path: protocolPointFieldPath(field), label: field.label || field.name, required: field.required, control: field.type === "boolean" ? "switch" : field.options?.length ? "select" : (field.type === "number" || field.type === "integer") ? "number" : "text", valueType: field.type === "boolean" ? "boolean" : field.type === "number" ? "number" : field.type === "integer" ? "integer" : "string", options: field.options?.map((option) => ({ label: option, value: option })), description: field.description })));
const protocolPointTitle = computed(() => protocol.value === "MODBUS_TCP" || protocol.value === "MODBUS_RTU" ? "协议扩展（Modbus 的 dataType 会直接影响取值长度和解码）" : "协议扩展");
const protocolPointNotes = computed(() => buildProtocolPointNotes(protocol.value, protocolSchema.value?.pointAddressHints || [], pointFields.value.length));
const alarmRuleRows = computed<AlarmRuleRow[]>(() => points.value.flatMap((point, pointIndex) => alarmRules(point).map((rule, ruleIndex) => ({ point, pointIndex, rule, ruleIndex }))));
const filteredAlarmRows = computed(() => alarmRuleRows.value.filter((row) => (!alarmPointFilter.value || row.point.pointCode === alarmPointFilter.value || row.point.pointId === alarmPointFilter.value) && (!alarmLevelFilter.value || row.rule.level === alarmLevelFilter.value) && (!alarmEnabledFilter.value || String(row.rule.enabled !== false) === alarmEnabledFilter.value)));
const enabledAlarmRuleCount = computed(() => alarmRuleRows.value.filter((row) => row.rule.enabled !== false).length);
const alarmLevelCounts = computed<Record<string, number>>(() => alarmRuleRows.value.reduce<Record<string, number>>((acc, row) => { const level = String(row.rule.level || "UNSET"); acc[level] = (acc[level] || 0) + 1; return acc; }, {}));
const currentAlarmRule = computed(() => selectedPoint.value ? alarmRules(selectedPoint.value)[selectedAlarmRuleIndex.value] || null : null);
const alarmLogicPreview = computed(() => currentAlarmRule.value && selectedPoint.value ? alarmConditionText({ point: selectedPoint.value, pointIndex: selectedPointIndex.value, rule: currentAlarmRule.value, ruleIndex: selectedAlarmRuleIndex.value }) + ` → 触发告警（${currentAlarmRule.value.level || "未设置级别"}）` : "未选择规则");
const eventIntervalSummary = computed(() => minPointAdditionalNumber("eventMinIntervalMs"));
const reportStrategySummary = computed(() => ({ changeMinInterval: minPointAdditionalNumber("changeMinIntervalMs"), eventMinInterval: minPointAdditionalNumber("eventMinIntervalMs"), cache: `${points.value.filter((point) => Number(point.cacheEnabled ?? 0) !== 0).length}/${points.value.length}` }));
const eventMappingPreview = computed(() => points.value.filter((point) => point.alarmEnabled || point.additionalConfig?.eventEnabled).map((point) => `${point.pointName || point.pointCode}: ${point.alarmEnabled ? "告警事件" : "点位事件"} → ${point.additionalConfig?.reportField || point.pointCode || "未配置 reportField"}`));
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
  if (schemaTab.value === "report") return ["cloudTarget.enabled", "cloudTarget.productKey", "cloudTarget.deviceName", "additionalConfig.reportField", "additionalConfig.reportEnabled", "additionalConfig.changeThreshold", "additionalConfig.changeMinIntervalMs", "additionalConfig.eventMinIntervalMs"].map((field) => ({ field, type: "真实配置字段", required: false, description: "设备级 cloudTarget 或点位 additionalConfig" }));
  if (schemaTab.value === "alarm") return ["alarmEnabled", "alarmRule", "ruleId", "ruleName", "operator", "threshold", "duration", "level", "enabled", "description"].map((field) => ({ field, type: "point.alarmRule JSON", required: false, description: "当前 Contract 为点位上的 alarmRule 字符串" }));
  return ["deviceId", "deviceName", "protocol", "adaptive", "connection", "points", "cloudTarget"].map((field) => ({ field, type: "LocalDeviceDraft", required: ["deviceId", "deviceName", "protocol", "points"].includes(field), description: "保存 payload 的结构化来源" }));
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
function onProtocolChanged() { const previous = { ...connectionModel.value }; connectionModel.value = { ...buildProtocolInitialModel(connectionFields.value), ...previous }; points.value = normalizePointsForEditor(points.value, deviceId.value || "local-device", protocol.value); syncJsonFromState(); void ensureProtocolSchema(protocol.value); }
async function ensureProtocolSchema(protocolCode: string) {
  const normalizedProtocol = protocolCode.trim(); if (!normalizedProtocol) return;
  const existing = protocolDetails.value[normalizedProtocol] || props.protocols.find((item) => item.protocol === normalizedProtocol);
  if (hasRenderableProtocolFields(existing)) { protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: existing }; connectionModel.value = { ...buildProtocolInitialModel(existing.connectionFields || []), ...connectionModel.value }; return; }
  try { const detail = await getProtocol(normalizedProtocol); protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: detail }; if (protocol.value === normalizedProtocol) { connectionModel.value = { ...buildProtocolInitialModel(detail.connectionFields || []), ...connectionModel.value }; points.value = normalizePointsForEditor(points.value, deviceId.value || "local-device", normalizedProtocol); syncJsonFromState(); } } catch (caught) { error.value = caught instanceof Error ? `协议字段加载失败：${caught.message}` : "协议字段加载失败"; }
}
function hasRenderableProtocolFields(schema: ProtocolSchema | null | undefined): schema is ProtocolSchema { return Boolean(schema && ((schema.connectionFields?.length || 0) > 0 || (schema.pointFields?.length || 0) > 0)); }
function addPoint() { const pointCode = createUniqueCode(points.value, "point"); const point = buildDefaultPoint(deviceId.value || "local-device", protocol.value, { pointCode, pointName: `点位 ${points.value.length + 1}` }); points.value = [...points.value, point]; selectedPointIndex.value = points.value.length - 1; syncJsonFromState(); }
function duplicatePoint(row?: DataPoint) { const source = row || selectedPoint.value; const sourceIndex = row ? points.value.indexOf(row) : selectedPointIndex.value; if (!source) return; const clone = cloneData(source); delete clone.id; delete clone.pointId; delete clone.createTime; delete clone.updateTime; delete clone.stableCount; delete clone.lastValue; delete clone.changeRate; delete clone.lastAdjustTime; delete clone.reportFieldConflict; clone.pointCode = createUniqueCode(points.value, `${source.pointCode || "point"}_copy`); clone.pointName = `${source.pointName || source.pointCode || "点位"} 副本`; if (isPlainObject(clone.additionalConfig) && hasValue(clone.additionalConfig.reportField)) clone.additionalConfig.reportField = `${clone.additionalConfig.reportField}_copy`; points.value.splice(Math.max(0, sourceIndex) + 1, 0, clone); selectedPointIndex.value = Math.max(0, sourceIndex) + 1; syncJsonFromState(); }
async function removePoint(row?: DataPoint) { const index = row ? points.value.indexOf(row) : selectedPointIndex.value; const point = points.value[index]; if (!point || index < 0) return; try { await ElMessageBox.confirm(`确认删除点位 ${point.pointCode || point.pointName || "当前点位"} 吗？`, "删除点位", { confirmButtonText: "删除", cancelButtonText: "取消", type: "warning" }); } catch { return; } points.value.splice(index, 1); selectedPointIndex.value = points.value.length ? Math.min(index, points.value.length - 1) : -1; selectedAlarmRuleIndex.value = 0; syncJsonFromState(); }
function selectPoint(row: DataPoint) { selectedPointIndex.value = points.value.indexOf(row); selectedAlarmRuleIndex.value = 0; }
function updatePointField(field: PointEditorField, value: unknown) { updateSelectedPath(field.path, parseFieldValue(value, field.valueType)); }
function updateSelectedPath(path: string, value: unknown) { const point = selectedPoint.value; if (!point) return; const previousPointCode = point.pointCode; const previousAddress = point.address; const previousTopic = getPathValue(point, "additionalConfig.topic"); const previousNodeId = getPathValue(point, "additionalConfig.nodeId"); const previousReportField = getPathValue(point, "additionalConfig.reportField"); setPathValue(point as Record<string, unknown>, path, value); if (path === "pointCode" && hasValue(previousReportField) && String(previousReportField).trim() === String(previousPointCode || "").trim()) setPathValue(point as Record<string, unknown>, "additionalConfig.reportField", value); if (path === "address") { if (protocol.value === "MQTT" && (!hasValue(previousTopic) || String(previousTopic).trim() === String(previousAddress || "").trim())) setPathValue(point as Record<string, unknown>, "additionalConfig.topic", value); if (isOpcUaProtocol(protocol.value) && (!hasValue(previousNodeId) || String(previousNodeId).trim() === String(previousAddress || "").trim())) setPathValue(point as Record<string, unknown>, "additionalConfig.nodeId", value); } if (path === "additionalConfig.topic" && protocol.value === "MQTT" && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousTopic || "").trim())) point.address = String(value || ""); if (path === "additionalConfig.nodeId" && isOpcUaProtocol(protocol.value) && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousNodeId || "").trim())) point.address = String(value || ""); syncJsonFromState(); }
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
function transformRuleText(point: DataPoint) { const scale = point.scalingFactor ?? 1; const offset = point.offset ?? 0; return Number(scale) !== 1 || Number(offset) !== 0 ? `value * ${scale} + ${offset}` : "原值"; }
function alarmConditionText(row: AlarmRuleRow) { const unit = row.point.unit ? String(row.point.unit) : ""; return `${row.point.pointName || row.point.pointCode || "点位"}(${row.point.pointCode || "-"}) → ${row.rule.operator || "?"} ${row.rule.threshold ?? "?"}${unit} → 持续 ${row.rule.duration ?? 0} 秒`; }
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
  background: rgba(2, 6, 23, 0.64);
  backdrop-filter: blur(3px);
}

.local-device-panel {
  --panel-line: var(--console-border-soft, #1e3a5f);
  --panel-muted: var(--console-text-muted, #8aa0b8);
  --panel-text: var(--console-text-primary, #e5edf8);
  position: fixed;
  inset: 12px 12px auto;
  z-index: 2001;
  display: flex;
  height: calc(100vh - 24px);
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  color: var(--console-text-secondary);
  border: 1px solid var(--panel-line);
  border-radius: 18px;
  background: var(--console-bg);
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.48);
}

.local-editor-title {
  display: flex;
  min-height: 76px;
  padding: 0 18px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex: none;
  color: var(--panel-text);
  border-bottom: 1px solid var(--panel-line);
  background: linear-gradient(180deg, var(--console-panel) 0%, var(--console-bg-soft) 100%);
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
  gap: 3px;
}

h3, p {
  margin: 0;
}

.local-editor-title h3 {
  color: var(--console-text-primary);
  font-size: 19px;
  font-weight: 850;
  line-height: 1.16;
}

.local-editor-title p, .local-section-head p, .field-description, .hint-list, .protocol-point-note, .validation-list {
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.35;
}

.label-chip, .pill {
  display: inline-flex;
  width: fit-content;
  min-height: 20px;
  padding: 2px 7px;
  align-items: center;
  border: 1px solid rgba(59, 130, 246, 0.34);
  border-radius: 999px;
  color: #bfdbfe;
  background: rgba(37, 99, 235, 0.18);
  font-size: 11px;
  font-weight: 800;
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
  width: 112px;
  min-width: 0;
  min-height: 46px;
  padding: 6px 9px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel-soft);
}

.local-editor-stat strong, .local-editor-stat span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-stat strong {
  color: var(--console-text-primary);
  font-size: 15px;
  line-height: 1.1;
}

.local-editor-stat span {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: 11px;
}

.local-editor-tabs {
  display: grid;
  min-height: 66px;
  padding: 8px 16px;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  flex: none;
  border-bottom: 1px solid var(--panel-line);
  background: var(--console-bg-soft);
}

.local-editor-tab {
  position: relative;
  min-width: 0;
  min-height: 50px;
  padding: 7px 10px 7px 44px;
  color: var(--console-text-dim);
  border: 1px solid var(--console-border-soft);
  background: linear-gradient(90deg, var(--console-panel), var(--console-panel-soft));
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
  top: 11px;
  left: 14px;
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid var(--console-border-soft);
  border-radius: 50%;
  background: var(--console-bg);
  font-size: 11px;
  font-weight: 800;
}

.local-editor-tab strong, .local-editor-tab small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-tab strong {
  color: var(--console-text-secondary);
  font-size: 13px;
  line-height: 1.15;
}

.local-editor-tab small {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: 11px;
  line-height: 1.1;
}

.local-editor-tab.is-active {
  color: var(--console-text-primary);
  border-color: var(--console-primary-hover);
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.44), rgba(14, 165, 233, 0.2));
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
  padding: 12px 16px;
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
  gap: 12px;
  overflow: hidden;
}

.step-grid-setup {
  grid-template-columns: 260px minmax(420px, 0.9fr) minmax(560px, 1.2fr);
  align-items: stretch;
}

.step-grid-master-detail {
  grid-template-columns: 260px minmax(0, 1fr);
}

.step-grid-cloud {
  grid-template-columns: 320px minmax(0, 1fr);
}

.step-grid-json {
  grid-template-columns: 220px minmax(420px, 1fr) 360px;
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
  flex: 1 1 52%;
}

.detail-stack > .editor-card, .cloud-bottom-grid {
  flex: 1 1 48%;
  min-height: 0;
}

.local-section-card, .readonly-card {
  min-width: 0;
  padding: 10px 12px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.local-section-head, .compact-head, .point-detail-hero {
  display: flex;
  margin-bottom: 8px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.local-section-head h3, .point-detail-hero strong, .field-group h3, .overview-card h3 {
  color: var(--console-text-primary);
  font-size: 15px;
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
  gap: 10px;
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
  gap: 9px 12px;
}

.wide-field, .field-group-wide {
  grid-column: 1 / -1;
}

.form-grid label, .change-note-label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  color: var(--console-text-muted);
  font-size: 12px;
}

input, select, textarea {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 34px;
  color: var(--console-text-primary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

input, select {
  padding: 0 10px;
}

button {
  min-height: 32px;
  padding: 0 10px;
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
  gap: 8px;
}

.metric-item {
  display: flex;
  min-height: 42px;
  padding: 8px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.metric-item span {
  color: var(--console-text-muted);
  font-size: 12px;
}

.metric-item strong {
  color: var(--console-text-primary);
  font-size: 15px;
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
  min-height: 30px;
  padding: 7px 9px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.table-wrap {
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
  border: 1px solid var(--console-border-soft);
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
  padding: 8px 9px;
  overflow: hidden;
  border-bottom: 1px solid var(--console-border-soft);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-table th, .schema-table th {
  color: var(--console-text-muted);
  background: var(--console-bg-soft);
}

.editor-table tr:hover td {
  background: rgba(14, 165, 233, 0.08);
}

.editor-table tr.is-selected td {
  background: rgba(59, 130, 246, 0.16);
}

.row-actions {
  display: flex;
  gap: 6px;
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
  padding: 10px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-bg-soft);
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
  margin: 0 0 8px;
}

.advanced-collapse {
  min-width: 0;
  color: var(--console-text-secondary);
}

.advanced-collapse summary {
  margin-bottom: 8px;
  cursor: pointer;
  color: #bfdbfe;
  font-weight: 800;
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
  overflow: auto;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
}

.schema-table {
  width: 100%;
  min-width: 520px;
  border-collapse: collapse;
  font-size: 12px;
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
  display: grid;
  margin-top: 10px;
  padding: 10px;
  gap: 4px;
  border: 1px solid rgba(14, 165, 233, 0.35);
  border-radius: var(--console-radius-md);
  background: rgba(14, 165, 233, 0.1);
}

.logic-preview span {
  color: var(--console-text-muted);
  font-size: 12px;
}

.logic-preview strong {
  color: var(--console-text-primary);
  font-size: 13px;
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
  min-height: 34px;
  background: var(--console-bg-soft);
  box-shadow: 0 0 0 1px var(--console-border-soft) inset;
}

:deep(.el-input__inner), :deep(.el-select__placeholder), :deep(.el-input-number .el-input__inner) {
  color: var(--console-text-primary);
}


@media (max-width: 1366px) {
.local-device-panel {
  inset: 8px;
  height: calc(100vh - 16px);
}

.step-grid-setup {
  grid-template-columns: 230px minmax(340px, 0.95fr) minmax(420px, 1.05fr);
}

.step-grid-master-detail {
  grid-template-columns: 230px minmax(0, 1fr);
}

.step-grid-json {
  grid-template-columns: 190px minmax(360px, 1fr) 310px;
}

.local-editor-title {
  min-height: 66px;
}

.local-editor-tabs {
  min-height: 58px;
  padding: 6px 12px;
}

.local-editor-tab small {
  display: none;
}
 }

@media (max-width: 1180px) {
.local-editor-body {
  overflow: auto;
}

.local-editor-pane, .step-grid {
  overflow: visible;
}

.step-grid-setup, .step-grid-master-detail, .step-grid-cloud, .step-grid-json, .cloud-bottom-grid {
  grid-template-columns: 1fr;
}

.overview-card {
  max-height: none;
}

.local-connection-card {
  min-height: 420px;
}

.local-editor-tabs {
  overflow-x: auto;
  grid-template-columns: repeat(5, minmax(168px, 1fr));
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
</style>
