<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      id="localEditorBackdrop"
      class="local-editor-backdrop"
      aria-hidden="true"
      @click="close(false)"
    ></div>

    <div
      v-if="modelValue"
      id="localDevicePanel"
      class="local-editor local-device-panel local-device-web-dialog"
      role="dialog"
      aria-modal="true"
      :aria-label="editingDeviceId ? '编辑本地临时设备' : '新增本地临时设备'"
      @click.stop
    >
      <div class="local-editor-title">
        <div>
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
          <button id="cancelLocalDeviceBtn" type="button" @click="close(false)">关闭</button>
        </div>
      </div>

      <div class="local-editor-tabs" role="tablist" aria-label="新增设备配置分区">
        <button
          v-for="(step, index) in localEditorSteps"
          :key="step.key"
          type="button"
          class="local-editor-tab"
          :data-local-editor-section="step.key"
          :class="{ 'is-active': activeStep === index, 'is-complete': activeStep > index }"
          @click="setActiveStep(index)"
        >
          <span>{{ step.no }}</span>
          <strong>{{ step.label }}</strong>
          <small>{{ step.desc }}</small>
        </button>
      </div>

      <div class="local-editor-layout">
        <aside class="local-editor-rail">
          <div>
            <span class="label-chip">配置进度</span>
            <strong id="localEditorValidationSummary">{{ validationTitle }}</strong>
            <p>红色项需要处理；切换分区不会丢失当前编辑内容。</p>
          </div>
          <ol id="localEditorChecklist" class="local-checklist">
            <li
              v-for="item in localEditorChecklist"
              :key="item.label"
              :class="{ 'is-ok': item.state === 'ok', 'is-warn': item.state === 'warn', 'is-error': item.state === 'error' }"
            >
              {{ item.label }}
            </li>
          </ol>
        </aside>

        <div class="local-editor-body">
          <el-alert v-if="error" :title="error" type="warning" :closable="false" />

          <section v-show="activeStep === 0" class="local-editor-pane" data-local-editor-pane="setup" :class="{ 'is-active': activeStep === 0 }">
            <div class="local-setup-cluster">
              <div class="local-setup-stable-column">
                <section class="local-section-card local-setup-card">
                  <div class="local-section-head">
                    <div>
                      <span class="label-chip">设备基础</span>
                      <h3>设备与调度参数</h3>
                    </div>
                    <p>设备标识、协议和采集节奏集中放在这里；切换协议时本卡片保持自然高度。</p>
                  </div>

                  <div class="modao-form-grid compact-form-grid local-summary-grid">
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
                      <h3>设备级云目标（cloudTarget）</h3>
                    </div>
                    <p>这里决定该采集设备上报到哪个云设备；点位只维护云端属性（reportField）。</p>
                  </div>

                  <div class="modao-form-grid compact-form-grid local-summary-grid">
                    <label>启用云上报<select id="localCloudEnabled" v-model="cloudTarget.enabled" @change="syncJsonFromPoints"><option :value="false">否</option><option :value="true">是</option></select></label>
                    <label>云设备类型<select id="localCloudDeviceType" v-model="cloudTarget.deviceType"><option value="SUB_DEVICE">子设备</option><option value="GATEWAY">网关设备</option><option value="DIRECT">直连设备</option><option value="LOGICAL_SUB_DEVICE">逻辑子设备</option></select></label>
                    <label>云端产品标识（productKey）<input id="localCloudProductKey" v-model="cloudTarget.productKey" type="text" placeholder="pk_xxx"></label>
                    <label>云端设备名称（deviceName）<input id="localCloudDeviceName" v-model="cloudTarget.deviceName" type="text" placeholder="sub_device_001"></label>
                    <label>启用拓扑注册<select id="localCloudTopologyEnabled" v-model="cloudTarget.topologyEnabled"><option :value="true">是</option><option :value="false">否</option></select></label>
                    <label class="wide-field">上报主题示例（Topic）<input id="localCloudTopicPreview" type="text" :value="cloudTopicPreview" readonly></label>
                  </div>
                </section>
              </div>

              <section class="local-section-card local-connection-card">
                <div class="local-section-head">
                  <div>
                    <span class="label-chip">连接参数</span>
                    <h3>协议对应字段</h3>
                  </div>
                  <div class="local-connection-meta" aria-label="连接参数摘要">
                    <span>{{ currentProtocolTitle }}</span>
                    <span>{{ connectionFields.length }} 字段</span>
                    <span>{{ connectionRequiredCount }} 必填</span>
                  </div>
                  <p>切换协议只影响本区域高度；字段较多时在连接参数内部滚动，不再拉伸左侧卡片。</p>
                </div>

                <form id="localConnectionForm" class="dynamic-form" @submit.prevent>
                  <ProtocolDynamicForm v-model="connectionModel" :fields="connectionFields" @validate="connectionErrors = $event" />
                </form>
              </section>
            </div>
          </section>

          <section v-show="activeStep === 1" class="local-editor-pane" data-local-editor-pane="points" :class="{ 'is-active': activeStep === 1 }">
            <section class="point-editor local-section-card">
              <div class="point-editor-head local-section-head">
                <div>
                  <span class="label-chip">点位建模</span>
                  <strong>本地点位列表</strong>
                </div>
                <div class="inline-actions table-actions">
                  <input id="localPointSearch" v-model="pointKeyword" class="compact-select" type="search" placeholder="搜索点位编码 / 名称 / 地址">
                  <button id="addLocalPointBtn" type="button" @click="addPoint">新增点位</button>
                  <button id="duplicateLocalPointBtn" type="button" :disabled="selectedPointIndex < 0" @click="duplicatePoint">复制</button>
                  <button id="deleteLocalPointBtn" type="button" class="danger" :disabled="selectedPointIndex < 0" @click="removePoint">删除</button>
                </div>
              </div>

              <div class="point-workspace local-point-workspace">
                <section class="point-list-panel">
                  <div class="point-list-meta">
                    <strong id="localPointCount">{{ points.length }} 个点位</strong>
                    <span id="localPointSelectionMeta">{{ pointSelectionMeta }}</span>
                  </div>
                  <div class="table-wrap compact point-table-wrap">
                    <table class="point-table">
                      <thead>
                        <tr><th>点位</th><th>地址</th><th>类型</th><th>读写</th><th>状态</th></tr>
                      </thead>
                      <tbody id="localPointRows">
                        <tr v-for="row in filteredPoints" :key="row.pointCode || row.address || points.indexOf(row)" :class="{ 'is-selected': points.indexOf(row) === selectedPointIndex }">
                          <td>
                            <button type="button" class="point-select-button" :data-select-local-point="points.indexOf(row)" @click="selectPoint(row)">
                              <strong>{{ row.pointName || row.pointCode || '-' }}</strong>
                              <span>{{ row.pointCode || `point_${points.indexOf(row) + 1}` }}</span>
                            </button>
                          </td>
                          <td>{{ row.address || '-' }}</td>
                          <td>{{ row.dataType || '-' }}</td>
                          <td>{{ row.readWrite || '-' }}</td>
                          <td>{{ statusLabel(row.status) }}</td>
                        </tr>
                        <tr v-if="filteredPoints.length === 0"><td colspan="5">{{ pointKeyword ? '没有匹配的点位' : '暂无点位' }}</td></tr>
                      </tbody>
                    </table>
                  </div>
                </section>

                <section class="point-detail-panel">
                  <div id="localPointEmpty" class="empty-state" :class="{ hidden: selectedPoint }">
                    <strong>暂无选中的点位</strong>
                    <span>先新增一个点位，或从左侧列表选择已有点位。</span>
                  </div>
                  <div id="localPointDetail" v-if="selectedPoint" class="point-detail-stack">
                    <section class="point-detail-hero">
                      <div>
                        <span class="label-chip">当前点位</span>
                        <strong>{{ selectedPoint.pointName || selectedPoint.pointCode || '未命名点位' }}</strong>
                        <p>{{ selectedPoint.pointCode || '-' }} · {{ selectedPoint.address || '未设置地址' }}</p>
                      </div>
                      <div class="point-detail-hero-meta">
                        <span class="pill subtle">{{ selectedPoint.dataType || '-' }}</span>
                        <span class="pill subtle">{{ selectedPoint.readWrite || '-' }}</span>
                        <span class="pill subtle">{{ statusLabel(selectedPoint.status) }}</span>
                      </div>
                    </section>

                    <div class="point-detail-tabbar" role="tablist" aria-label="点位详情分区">
                      <button
                        v-for="tab in pointDetailTabs"
                        :key="tab.key"
                        type="button"
                        class="point-detail-tab"
                        :class="{ 'is-active': pointDetailTab === tab.key }"
                        @click="pointDetailTab = tab.key"
                      >
                        {{ tab.label }}
                      </button>
                    </div>

                    <div class="point-detail-grid point-detail-grid-single">
                      <section v-show="pointDetailTab === 'basic'" class="field-group field-group-wide">
                        <h3>基础信息</h3>
                        <p class="point-section-note">设备级基础/最小/最大采集周期和点位变化阈值会在保存时统一回写到全部点位。</p>
                        <div class="form-grid">
                          <label v-for="field in basicPointFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                            <span class="field-label-text">{{ field.label }}<span v-if="field.required" class="field-required"> *</span></span>
                            <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                              <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                            </component>
                            <small v-if="field.description" class="field-description">{{ field.description }}</small>
                          </label>
                        </div>
                      </section>

                      <section v-show="pointDetailTab === 'data'" class="field-group field-group-wide">
                        <h3>数据处理</h3>
                        <div class="form-grid">
                          <label v-for="field in dataPointFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                            <span class="field-label-text">{{ field.label }}</span>
                            <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                              <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                            </component>
                            <small v-if="field.description" class="field-description">{{ field.description }}</small>
                          </label>
                        </div>
                      </section>

                      <section v-show="pointDetailTab === 'report'" class="field-group field-group-wide">
                        <h3>上报 / 缓存参数</h3>
                        <p class="point-section-note">这里维护点位上报开关、上报属性（reportField）、缓存和变化阈值；云设备身份统一在设备级云目标（cloudTarget）配置。</p>
                        <div class="form-grid">
                          <label v-for="field in reportPointFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                            <span class="field-label-text">{{ field.label }}</span>
                            <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                              <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                            </component>
                            <small v-if="field.description" class="field-description">{{ field.description }}</small>
                          </label>
                        </div>
                        <div class="inline-actions point-json-actions"><el-button @click="setActiveStep(2)">配置云平台上报</el-button></div>
                      </section>

                      <section v-show="pointDetailTab === 'protocol'" class="field-group field-group-wide">
                        <h3>{{ protocolPointTitle }}</h3>
                        <div class="protocol-point-note">
                          <p v-if="protocolPointNotes.addressHints.length">当前协议地址示例：<code v-for="hint in protocolPointNotes.addressHints" :key="hint">{{ hint }}</code></p>
                          <p v-for="message in protocolPointNotes.messages" :key="message">{{ message }}</p>
                        </div>
                        <div v-if="protocolPointFields.length" class="form-grid">
                          <label v-for="field in protocolPointFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                            <span class="field-label-text">{{ field.label }}<span v-if="field.required" class="field-required"> *</span></span>
                            <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                              <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                            </component>
                            <small v-if="field.description" class="field-description">{{ field.description }}</small>
                          </label>
                        </div>
                      </section>

                      <section v-show="pointDetailTab === 'alarm'" class="field-group field-group-wide">
                        <h3>告警规则</h3>
                        <div class="form-grid">
                          <label v-for="field in alarmPointFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                            <span class="field-label-text">{{ field.label }}</span>
                            <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                              <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                            </component>
                          </label>
                        </div>
                        <div class="point-subtable">
                          <p class="subtable-note">告警规则（alarmRule）会在提交时序列化回顶层 JSON 字符串；只保留有实际内容的规则。</p>
                          <div class="table-wrap compact">
                            <table>
                              <thead><tr><th>规则ID</th><th>规则名称</th><th>运算符</th><th>阈值</th><th>持续时间(s)</th><th>级别</th><th>启用</th><th>描述</th><th>操作</th></tr></thead>
                              <tbody>
                                <tr v-for="(rule, index) in selectedAlarmRules" :key="index">
                                  <td><el-input :model-value="String(rule.ruleId || '')" @update:model-value="updateAlarmRule(index, 'ruleId', $event)" /></td>
                                  <td><el-input :model-value="String(rule.ruleName || '')" @update:model-value="updateAlarmRule(index, 'ruleName', $event)" /></td>
                                  <td><el-select :model-value="String(rule.operator || '')" @update:model-value="updateAlarmRule(index, 'operator', $event)"><el-option v-for="operator in alarmOperators" :key="operator" :label="operator" :value="operator" /></el-select></td>
                                  <td><el-input-number :model-value="toNumber(rule.threshold)" controls-position="right" :step="0.0001" @update:model-value="updateAlarmRule(index, 'threshold', $event)" /></td>
                                  <td><el-input-number :model-value="toNumber(rule.duration)" controls-position="right" :step="1" @update:model-value="updateAlarmRule(index, 'duration', $event)" /></td>
                                  <td><el-select :model-value="String(rule.level || '')" clearable @update:model-value="updateAlarmRule(index, 'level', $event)"><el-option v-for="level in alarmLevels" :key="level.value" :label="level.label" :value="level.value" /></el-select></td>
                                  <td><el-select :model-value="rule.enabled === undefined ? '' : String(Boolean(rule.enabled))" clearable @update:model-value="updateAlarmRule(index, 'enabled', parseBooleanOption($event))"><el-option label="是" value="true" /><el-option label="否" value="false" /></el-select></td>
                                  <td><el-input :model-value="String(rule.description || '')" @update:model-value="updateAlarmRule(index, 'description', $event)" /></td>
                                  <td><el-button type="danger" plain @click="removeAlarmRule(index)">删除</el-button></td>
                                </tr>
                                <tr v-if="selectedAlarmRules.length === 0"><td colspan="9">暂无告警规则</td></tr>
                              </tbody>
                            </table>
                          </div>
                          <div class="inline-actions point-json-actions"><el-button @click="addAlarmRule">新增告警规则</el-button></div>
                        </div>
                      </section>

                      <section v-show="pointDetailTab === 'readonly'" class="field-group field-group-wide">
                        <h3>只读信息</h3>
                        <div v-if="readonlyItems.length" class="readonly-grid">
                          <div v-for="item in readonlyItems" :key="item.label" class="readonly-card">
                            <small>{{ item.label }}</small>
                            <strong>{{ item.value }}</strong>
                          </div>
                        </div>
                        <p v-else class="field-description">当前点位没有额外只读运行态信息。</p>
                      </section>
                    </div>
                  </div>
                </section>
              </div>
            </section>
          </section>

          <section v-show="activeStep === 2" class="local-editor-pane" data-local-editor-pane="cloud" :class="{ 'is-active': activeStep === 2 }">
            <section class="local-section-card local-cloud-panel">
              <div class="local-section-head">
                <div>
                  <span class="label-chip">云平台上报</span>
                  <h3>设备级目标与云端属性（reportField）</h3>
                </div>
                <p>云设备身份在设备级云目标（cloudTarget）维护；点位只配置云属性（reportField）。</p>
              </div>
              <div class="local-cloud-workspace point-workspace">
                <section class="point-list-panel">
                  <div class="point-list-meta">
                    <strong id="localCloudTargetCount">{{ totalReportFieldCount }} 个上报属性 / {{ cloudTarget.enabled ? '云目标已启用' : '云目标未启用' }}</strong>
                    <span id="localCloudPointMeta">{{ selectedPoint ? `当前：${selectedPoint.pointName || selectedPoint.pointCode || '-'}` : '未选择点位' }}</span>
                  </div>
                  <div class="table-wrap compact point-table-wrap">
                    <table class="point-table cloud-point-table">
                      <thead>
                        <tr><th>点位</th><th>云端目标</th><th>字段</th><th>状态</th></tr>
                      </thead>
                      <tbody id="localCloudRows">
                        <tr v-for="(row, index) in points" :key="row.pointCode || row.address || index" :class="{ 'is-selected': index === selectedPointIndex }">
                          <td>
                            <button type="button" class="point-select-button" :data-select-cloud-point="index" @click="selectPoint(row)">
                              <strong>{{ row.pointName || row.pointCode || '-' }}</strong>
                              <span>{{ row.pointCode || `point_${index + 1}` }}</span>
                            </button>
                          </td>
                          <td>{{ cloudTargetSummary(row, cloudTarget) }}</td>
                          <td>{{ row.additionalConfig?.reportField || '-' }}</td>
                          <td>{{ cloudPointStatus(row, cloudTarget) }}</td>
                        </tr>
                        <tr v-if="points.length === 0"><td colspan="4">暂无点位</td></tr>
                      </tbody>
                    </table>
                  </div>
                </section>
                <section class="point-detail-panel">
                  <div id="localCloudEmpty" class="empty-state" :class="{ hidden: selectedPoint }">
                    <strong>暂无选中的点位</strong>
                    <span>先新增一个点位，或从左侧列表选择已有点位。</span>
                  </div>
                  <div id="localCloudDetail" v-if="selectedPoint" class="point-detail-stack local-cloud-detail-stack">
                    <section class="point-detail-hero">
                      <div>
                        <span class="label-chip">当前点位</span>
                        <strong>{{ selectedPoint.pointName || selectedPoint.pointCode || '未命名点位' }}</strong>
                        <p>{{ selectedPoint.pointCode || '-' }} · {{ selectedPoint.address || '未设置地址' }}</p>
                      </div>
                      <div class="point-detail-hero-meta">
                        <span class="pill subtle">{{ selectedPoint.dataType || '-' }}</span>
                        <span class="pill subtle">{{ selectedPoint.additionalConfig?.reportField ? '已配置上报属性' : '未配置上报属性' }}</span>
                        <span class="pill subtle">{{ cloudTarget.enabled ? '设备已启用云目标' : '设备未启用云目标' }}</span>
                      </div>
                    </section>
                    <section class="field-group field-group-wide">
                      <h3>上报控制</h3>
                      <p class="point-section-note">上报属性（reportField）是云端物模型属性标识；未配置上报属性或设备未启用云目标（cloudTarget）的点位不会进入云属性上报。</p>
                      <div class="form-grid">
                        <label v-for="field in cloudReportFields" :key="field.path" :class="{ 'wide-field': field.fullWidth }">
                          <span class="field-label-text">{{ field.label }}</span>
                          <component :is="fieldComponent(field)" v-bind="fieldProps(field)" @update:model-value="updatePointField(field, $event)">
                            <el-option v-for="option in field.options || []" :key="String(option.value)" :label="option.label" :value="option.value" />
                          </component>
                          <small v-if="field.description" class="field-description">{{ field.description }}</small>
                        </label>
                      </div>
                    </section>
                    <section class="field-group field-group-wide">
                      <h3>设备级云目标</h3>
                      <p class="point-section-note">{{ cloudTargetDetail }}</p>
                    </section>
                  </div>
                </section>
              </div>
            </section>
          </section>

          <section v-show="activeStep === 3" class="local-editor-pane" data-local-editor-pane="json" :class="{ 'is-active': activeStep === 3 }">
            <section class="point-json-panel local-section-card">
              <div class="local-section-head">
                <div>
                  <span class="label-chip">JSON 高级</span>
                  <h3>点位配置 JSON 数组</h3>
                </div>
                <p>保留原始点位数组 JSON，适合批量粘贴或补充界面暂未可视化的字段。</p>
              </div>
              <label>
                点位配置 JSON 数组
                <textarea id="localPointsJson" v-model="pointsJson" spellcheck="false"></textarea>
              </label>
              <div class="inline-actions point-json-actions table-actions">
                <button id="formatLocalPointsBtn" type="button" @click="formatPointsJson">格式化 JSON</button>
                <button id="applyLocalPointsJsonBtn" type="button" @click="applyPointsJson">应用 JSON 到列表</button>
              </div>
            </section>
          </section>
        </div>
      </div>

      <div class="inline-actions local-options local-editor-footer">
        <label class="check-line"><input id="localOverwrite" v-model="overwrite" type="checkbox"> 覆盖已有本地临时设备</label>
        <label class="check-line"><input id="localStartAfterSave" v-model="startAfterSave" type="checkbox"> 保存后立即本地启动</label>
        <div class="local-step-actions">
          <button id="localEditorPrevBtn" type="button" :disabled="activeStep === 0" @click="moveStep(-1)">上一步</button>
          <button id="localEditorNextBtn" type="button" :disabled="activeStep === localEditorSteps.length - 1" @click="moveStep(1)">{{ activeStep === localEditorSteps.length - 1 ? "已到最后" : "下一步" }}</button>
          <button id="saveLocalDeviceBtn" type="button" class="primary" :disabled="saving" @click="save">{{ saving ? "保存中..." : "保存并测试" }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch, ElMessage, ElMessageBox } from "element-plus";

import { createLocalDevice, updateLocalDevice } from "@/api/config.api";
import { startLocalDevice } from "@/api/device.api";
import { getProtocol } from "@/api/protocol.api";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import { buildConnectionPayload, buildProtocolInitialModel, extractProtocolModel, getPathValue, setPathValue, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import { buildLocalDevicePayload, buildProtocolPointNotes, DEFAULT_ADAPTIVE_CONFIG, validateLocalDeviceDraft, type AdaptiveConfig, type CloudTargetConfig, type LocalDeviceBundle } from "@/features/device/utils/local-device-utils";
import { buildReadonlyItems, createUniqueCode, findDuplicatePointCode, alarmRules, parseBooleanOption, parseFieldValue, parsePointsJson, serializeAlarmRules, statusLabel, toNumber, type FieldValueType } from "@/features/point/utils/point-draft-utils";
import { cloneData, cloudPointStatus, cloudTargetSummary, defaultPointTemplate, firstPointValue, hasValue, isOpcUaProtocol, isPlainObject, normalizeCloudTarget, normalizeInitialPoints, sanitizePointForSave } from "@/features/device/utils/local-device-editor-utils";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

type PointDetailTab = "basic" | "data" | "report" | "protocol" | "alarm" | "readonly";
type ChecklistState = "ok" | "warn" | "error";
type FieldControl = "text" | "number" | "select" | "switch";

interface SelectOption {
  label: string;
  value: string | number | boolean;
}

interface PointEditorField {
  path: string;
  label: string;
  control?: FieldControl;
  valueType?: FieldValueType;
  options?: SelectOption[];
  required?: boolean;
  description?: string;
  fullWidth?: boolean;
  disabled?: boolean;
  step?: number;
}

const props = defineProps<{
  modelValue: boolean;
  editingBundle?: LocalDeviceBundle | null;
  protocols: ProtocolSchema[];
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  saved: [deviceId: string];
}>();

const localEditorSteps = [
  { key: "setup", no: "01", label: "基础连接", desc: "设备基础与连接参数配置" },
  { key: "points", no: "02", label: "点位建模", desc: "采集点位定义与建模" },
  { key: "cloud", no: "03", label: "云平台上报", desc: "云端映射与数据上报" },
  { key: "json", no: "04", label: "JSON 高级", desc: "高级配置与自定义扩展" }
] as const;

const pointDetailTabs: Array<{ key: PointDetailTab; label: string }> = [
  { key: "basic", label: "基础信息" },
  { key: "data", label: "数据处理" },
  { key: "report", label: "上报 / 缓存参数" },
  { key: "protocol", label: "协议扩展" },
  { key: "alarm", label: "告警规则" },
  { key: "readonly", label: "只读信息" }
];

const activeStep = ref(0);
const pointDetailTab = ref<PointDetailTab>("basic");
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
const pointKeyword = ref("");
const pointsJson = ref("[]");

const adaptive = reactive<AdaptiveConfig>({ ...DEFAULT_ADAPTIVE_CONFIG });
const cloudTarget = reactive<CloudTargetConfig>({ enabled: false, deviceType: "SUB_DEVICE", topologyEnabled: true });

const booleanOptions: SelectOption[] = [{ label: "是", value: true }, { label: "否", value: false }];
const enableOptions: SelectOption[] = [{ label: "启用", value: 1 }, { label: "禁用", value: 0 }];
const readWriteOptions: SelectOption[] = [{ label: "只读 R", value: "R" }, { label: "只写 W", value: "W" }, { label: "读写 RW", value: "RW" }];
const collectionModeOptions: SelectOption[] = [{ label: "轮询", value: "POLLING" }, { label: "订阅", value: "SUBSCRIPTION" }, { label: "事件", value: "EVENT" }];
const alarmOperators = [">", ">=", "<", "<=", "==", "!="];
const alarmLevels = [
  { label: "信息", value: "INFO" },
  { label: "警告", value: "WARNING" },
  { label: "错误", value: "ERROR" },
  { label: "严重", value: "CRITICAL" }
];

const visibleProtocols = computed(() => props.protocols.filter((item) => item.protocol));
const protocolSchema = computed(() => protocolDetails.value[protocol.value] || props.protocols.find((item) => item.protocol === protocol.value) || null);
const connectionFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.connectionFields || []);
const connectionRequiredCount = computed(() => connectionFields.value.filter((field) => field.required).length);
const pointFields = computed<ProtocolFieldConfig[]>(() => protocolSchema.value?.pointFields || []);
const pointDataTypes = computed(() => protocolSchema.value?.dataTypes?.length ? protocolSchema.value.dataTypes : ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"]);
const currentProtocolTitle = computed(() => protocolSchema.value?.title ? `${protocolSchema.value.title} (${protocol.value})` : protocol.value);
const selectedPoint = computed<DataPoint | null>(() => points.value[selectedPointIndex.value] || null);
const filteredPoints = computed(() => {
  const keyword = pointKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return points.value;
  }
  return points.value.filter((point) => [point.pointCode, point.pointName, point.address].some((value) => String(value || "").toLowerCase().includes(keyword)));
});
const pointSelectionMeta = computed(() => {
  const parts: string[] = [];
  if (selectedPoint.value) {
    parts.push(`当前：${selectedPoint.value.pointName || selectedPoint.value.pointCode || `点位 ${selectedPointIndex.value + 1}`}`);
  }
  const keyword = pointKeyword.value.trim();
  if (keyword) {
    parts.push(`筛选 ${filteredPoints.value.length}/${points.value.length}`);
  }
  return parts.join(" | ") || "未选择点位";
});
const cloudTopicPreview = computed(() => cloudTarget.enabled && cloudTarget.productKey && cloudTarget.deviceName
  ? `/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post`
  : "未启用云上报或云身份不完整");
const localEditorChecklist = computed<Array<{ label: string; state: ChecklistState }>>(() => {
  const duplicateCode = findDuplicatePointCode(points.value);
  const missingPoint = points.value.find((point) => !hasValue(point.pointCode) || !hasValue(point.pointName) || !hasValue(point.address));
  const checks: Array<{ label: string; state: ChecklistState }> = [
    { label: deviceId.value.trim() ? "设备 ID 已填写" : "设备 ID 待填写", state: deviceId.value.trim() ? "ok" : "error" },
    { label: deviceName.value.trim() ? "设备名称已填写" : "设备名称待填写", state: deviceName.value.trim() ? "ok" : "error" },
    { label: connectionErrors.value.length === 0 ? "连接参数格式正常" : "连接参数需要修正", state: connectionErrors.value.length === 0 ? "ok" : "error" },
    { label: points.value.length > 0 ? `已配置 ${points.value.length} 个点位` : "至少需要 1 个点位", state: points.value.length > 0 ? "ok" : "error" },
    { label: duplicateCode ? `点位编码重复：${duplicateCode}` : "点位编码未重复", state: duplicateCode ? "error" : "ok" },
    { label: missingPoint ? "存在点位缺少编码、名称或地址" : "点位必填项完整", state: missingPoint ? "error" : "ok" },
    { label: totalReportFieldCount.value ? `已配置 ${totalReportFieldCount.value} 个上报属性` : "建议配置上报属性", state: totalReportFieldCount.value ? "ok" : "warn" }
  ];
  if (cloudTarget.enabled) {
    checks.push({
      label: cloudTarget.productKey && cloudTarget.deviceName ? "云设备身份已填写" : "云设备身份待填写",
      state: cloudTarget.productKey && cloudTarget.deviceName ? "ok" : "error"
    });
  }
  return checks;
});
const validationTitle = computed(() => {
  const errorCount = localEditorChecklist.value.filter((item) => item.state === "error").length;
  const warnCount = localEditorChecklist.value.filter((item) => item.state === "warn").length;
  if (errorCount > 0) {
    return `${errorCount} 个必填项待处理`;
  }
  return warnCount > 0 ? `${warnCount} 个建议项可完善` : "必填配置已完成";
});
const totalReportFieldCount = computed(() => points.value.filter((point) => hasValue(point.additionalConfig?.reportField)).length);
const selectedAlarmRules = computed(() => alarmRules(selectedPoint.value));
const readonlyItems = computed(() => buildReadonlyItems(selectedPoint.value));

const basicPointFields = computed<PointEditorField[]>(() => [
  { path: "pointCode", label: "点位编码", required: true, description: "修改点位编码时，如果 reportField 未单独改过，会同步更新云端属性。" },
  { path: "pointName", label: "点位名称", required: true },
  { path: "address", label: "地址", required: true, description: "MQTT / OPC UA 会按旧 Web 规则同步 topic 或 nodeId。" },
  { path: "dataType", label: "数据类型", control: "select", options: pointDataTypes.value.map((value) => ({ label: value, value })) },
  { path: "readWrite", label: "读写类型", control: "select", options: readWriteOptions },
  { path: "collectionMode", label: "采集模式", control: "select", options: collectionModeOptions },
  { path: "status", label: "启用状态", control: "select", valueType: "integer", options: enableOptions },
  { path: "unit", label: "单位" },
  { path: "remark", label: "备注", fullWidth: true }
]);
const dataPointFields = computed<PointEditorField[]>(() => [
  { path: "scalingFactor", label: "缩放系数", control: "number", valueType: "number", step: 0.0001 },
  { path: "offset", label: "偏移量", control: "number", valueType: "number", step: 0.0001 },
  { path: "deadband", label: "死区", control: "number", valueType: "number", step: 0.0001 },
  { path: "minValue", label: "最小值", control: "number", valueType: "number", step: 0.0001 },
  { path: "maxValue", label: "最大值", control: "number", valueType: "number", step: 0.0001 },
  { path: "precision", label: "精度", control: "number", valueType: "integer", step: 1 },
  { path: "priority", label: "优先级", control: "number", valueType: "integer", step: 1 },
  { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions },
  { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }
]);
const reportPointFields = computed<PointEditorField[]>(() => [
  { path: "additionalConfig.reportEnabled", label: "参与设备上报", control: "select", valueType: "boolean", options: booleanOptions },
  { path: "additionalConfig.reportField", label: "云端属性（reportField）" },
  { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: 0.0001 },
  { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: 1 },
  { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions },
  { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: 1 },
  { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: enableOptions },
  { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: 1 }
]);
const cloudReportFields = computed<PointEditorField[]>(() => [
  { path: "additionalConfig.reportEnabled", label: "启用上报", control: "select", valueType: "boolean", options: booleanOptions },
  { path: "additionalConfig.reportField", label: "云端属性（reportField）" },
  { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: booleanOptions },
  { path: "additionalConfig.streamEnabled", label: "实时流", control: "select", valueType: "boolean", options: booleanOptions },
  { path: "additionalConfig.historyEnabled", label: "历史", control: "select", valueType: "boolean", options: booleanOptions }
]);
const alarmPointFields = computed<PointEditorField[]>(() => [
  { path: "alarmEnabled", label: "启用告警", control: "select", valueType: "integer", options: enableOptions }
]);
const protocolPointFields = computed<PointEditorField[]>(() => pointFields.value.map((field) => ({
  path: protocolPointFieldPath(field),
  label: field.label || field.name,
  required: field.required,
  control: field.type === "boolean" ? "switch" : field.options?.length ? "select" : (field.type === "number" || field.type === "integer") ? "number" : "text",
  valueType: field.type === "boolean" ? "boolean" : field.type === "number" ? "number" : field.type === "integer" ? "integer" : "string",
  options: field.options?.map((option) => ({ label: option, value: option })),
  description: field.description
})));
const protocolPointTitle = computed(() => protocol.value === "MODBUS_TCP" || protocol.value === "MODBUS_RTU"
  ? "协议扩展（Modbus 的 dataType 会直接影响取值长度和解码）"
  : "协议扩展");
const protocolPointNotes = computed(() => buildProtocolPointNotes(protocol.value, protocolSchema.value?.pointAddressHints || [], pointFields.value.length));
const cloudTargetDetail = computed(() => {
  if (!cloudTarget.enabled) {
    return "当前设备未启用云上报。请在基础连接页的“云平台身份”中启用云目标（cloudTarget）。";
  }
  if (!cloudTarget.productKey || !cloudTarget.deviceName) {
    return "当前设备已启用云上报，但云端产品标识（productKey）或云端设备名称（deviceName）未填写。";
  }
  return `当前点位将随设备上报到 ${cloudTarget.productKey}/${cloudTarget.deviceName}，主题（Topic）：/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post`;
});

function normalizePointsForEditor(rawPoints: DataPoint[], currentDeviceId: string, currentProtocol: string): DataPoint[] {
  return normalizeInitialPoints(rawPoints, currentDeviceId, currentProtocol, {
    adaptive: { ...adaptive },
    pointDataTypes: pointDataTypes.value
  });
}

function buildDefaultPoint(currentDeviceId: string, currentProtocol: string, overrides: Partial<DataPoint> = {}): DataPoint {
  return defaultPointTemplate(currentDeviceId, currentProtocol, overrides, {
    adaptive: { ...adaptive },
    pointDataTypes: pointDataTypes.value
  });
}

function setActiveStep(index: number) {
  activeStep.value = Math.max(0, Math.min(localEditorSteps.length - 1, index));
  if (activeStep.value === 1) {
    pointDetailTab.value = pointDetailTab.value || "basic";
  }
  if (activeStep.value === 3) {
    syncJsonFromPoints();
  }
}

function moveStep(delta: number) {
  setActiveStep(activeStep.value + delta);
}

function reset(bundle: LocalDeviceBundle | null = null) {
  activeStep.value = 0;
  pointDetailTab.value = "basic";
  error.value = "";
  pointKeyword.value = "";
  const device = bundle?.device || {};
  const connection = bundle?.connection || {};
  editingDeviceId.value = String(device.id || device.deviceId || "");
  deviceId.value = editingDeviceId.value || "";
  deviceName.value = String(device.deviceName || "");
  protocol.value = String(device.protocolType || connection.connectionType || visibleProtocols.value[0]?.protocol || "MODBUS_TCP");
  overwrite.value = Boolean(editingDeviceId.value);
  startAfterSave.value = false;
  adaptive.baseCollectionInterval = Number(device.collectionInterval || DEFAULT_ADAPTIVE_CONFIG.baseCollectionInterval);
  adaptive.minCollectionInterval = Number(firstPointValue(bundle?.points, "minCollectionInterval") || DEFAULT_ADAPTIVE_CONFIG.minCollectionInterval);
  adaptive.maxCollectionInterval = Number(firstPointValue(bundle?.points, "maxCollectionInterval") || DEFAULT_ADAPTIVE_CONFIG.maxCollectionInterval);
  adaptive.pointChangeThreshold = Number(firstPointValue(bundle?.points, "pointChangeThreshold") || DEFAULT_ADAPTIVE_CONFIG.pointChangeThreshold);
  Object.assign(cloudTarget, { enabled: false, deviceType: "SUB_DEVICE", productKey: "", deviceName: "", topologyEnabled: true }, normalizeCloudTarget(device.cloudTarget));
  points.value = normalizePointsForEditor(bundle?.points || [], deviceId.value || "local-device", protocol.value);
  if (points.value.length === 0) {
    addPoint();
  }
  selectedPointIndex.value = points.value.length ? 0 : -1;
  connectionModel.value = connectionFields.value.length ? extractProtocolModel(connectionFields.value, connection as ConnectionPayload) : buildProtocolInitialModel(connectionFields.value);
  syncJsonFromPoints();
  void ensureProtocolSchema(protocol.value);
}

function onProtocolChanged() {
  connectionModel.value = {};
  points.value = normalizePointsForEditor(points.value, deviceId.value || "local-device", protocol.value);
  syncJsonFromPoints();
  void ensureProtocolSchema(protocol.value);
}

async function ensureProtocolSchema(protocolCode: string) {
  const normalizedProtocol = protocolCode.trim();
  if (!normalizedProtocol) {
    return;
  }
  const existing = protocolDetails.value[normalizedProtocol] || props.protocols.find((item) => item.protocol === normalizedProtocol);
  if (hasRenderableProtocolFields(existing)) {
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: existing };
    connectionModel.value = {
      ...buildProtocolInitialModel(existing.connectionFields || []),
      ...connectionModel.value
    };
    return;
  }
  try {
    const detail = await getProtocol(normalizedProtocol);
    protocolDetails.value = { ...protocolDetails.value, [normalizedProtocol]: detail };
    if (protocol.value === normalizedProtocol) {
      connectionModel.value = {
        ...buildProtocolInitialModel(detail.connectionFields || []),
        ...connectionModel.value
      };
      points.value = normalizePointsForEditor(points.value, deviceId.value || "local-device", normalizedProtocol);
      syncJsonFromPoints();
    }
  } catch (caught) {
    error.value = caught instanceof Error ? `协议字段加载失败：${caught.message}` : "协议字段加载失败";
  }
}

function hasRenderableProtocolFields(schema: ProtocolSchema | null | undefined): schema is ProtocolSchema {
  return Boolean(schema && ((schema.connectionFields?.length || 0) > 0 || (schema.pointFields?.length || 0) > 0));
}

function addPoint() {
  const pointCode = createUniqueCode(points.value, "point");
  const point = buildDefaultPoint(deviceId.value || "local-device", protocol.value, { pointCode, pointName: `点位 ${points.value.length + 1}` });
  points.value = [...points.value, point];
  selectedPointIndex.value = points.value.length - 1;
  syncJsonFromPoints();
}

function duplicatePoint() {
  const source = selectedPoint.value;
  if (!source) {
    return;
  }
  const clone = cloneData(source);
  delete clone.id;
  delete clone.pointId;
  delete clone.createTime;
  delete clone.updateTime;
  delete clone.stableCount;
  delete clone.lastValue;
  delete clone.changeRate;
  delete clone.lastAdjustTime;
  delete clone.reportFieldConflict;
  clone.pointCode = createUniqueCode(points.value, `${source.pointCode || "point"}_copy`);
  clone.pointName = `${source.pointName || source.pointCode || "点位"} 副本`;
  if (isPlainObject(clone.additionalConfig) && hasValue(clone.additionalConfig.reportField)) {
    clone.additionalConfig.reportField = `${clone.additionalConfig.reportField}_copy`;
  }
  points.value.splice(selectedPointIndex.value + 1, 0, clone);
  selectedPointIndex.value += 1;
  syncJsonFromPoints();
}

async function removePoint() {
  const point = selectedPoint.value;
  if (!point || selectedPointIndex.value < 0) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除点位 ${point.pointCode || point.pointName || "当前点位"} 吗？`, "删除点位", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  points.value.splice(selectedPointIndex.value, 1);
  selectedPointIndex.value = points.value.length ? Math.min(selectedPointIndex.value, points.value.length - 1) : -1;
  syncJsonFromPoints();
}

function selectPoint(row: DataPoint) {
  selectedPointIndex.value = points.value.indexOf(row);
}

function updatePointField(field: PointEditorField, value: unknown) {
  updateSelectedPath(field.path, parseFieldValue(value, field.valueType));
}

function updateSelectedPath(path: string, value: unknown) {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
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
    if (protocol.value === "MQTT" && (!hasValue(previousTopic) || String(previousTopic).trim() === String(previousAddress || "").trim())) {
      setPathValue(point as Record<string, unknown>, "additionalConfig.topic", value);
    }
    if (isOpcUaProtocol(protocol.value) && (!hasValue(previousNodeId) || String(previousNodeId).trim() === String(previousAddress || "").trim())) {
      setPathValue(point as Record<string, unknown>, "additionalConfig.nodeId", value);
    }
  }
  if (path === "additionalConfig.topic" && protocol.value === "MQTT" && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousTopic || "").trim())) {
    point.address = String(value || "");
  }
  if (path === "additionalConfig.nodeId" && isOpcUaProtocol(protocol.value) && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousNodeId || "").trim())) {
    point.address = String(value || "");
  }
  syncJsonFromPoints();
}

function updateAlarmRule(index: number, field: string, value: unknown) {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  const rules = alarmRules(point);
  while (rules.length <= index) {
    rules.push({});
  }
  if (value === undefined || value === null || value === "") {
    delete rules[index][field];
  } else {
    rules[index][field] = value;
  }
  point.alarmRule = serializeAlarmRules(rules);
  syncJsonFromPoints();
}

function addAlarmRule() {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  const rules = alarmRules(point);
  rules.push({ operator: ">", enabled: true });
  point.alarmRule = serializeAlarmRules(rules);
  syncJsonFromPoints();
}

function removeAlarmRule(index: number) {
  const point = selectedPoint.value;
  if (!point) {
    return;
  }
  const rules = alarmRules(point);
  rules.splice(index, 1);
  point.alarmRule = serializeAlarmRules(rules);
  syncJsonFromPoints();
}

function syncDeviceIdToPoints() {
  points.value = points.value.map((point) => ({ ...point, deviceId: deviceId.value || "local-device" }));
  syncJsonFromPoints();
}

function syncAdaptiveToPoints() {
  points.value = points.value.map((point) => ({
    ...point,
    baseCollectionInterval: adaptive.baseCollectionInterval,
    currentCollectionInterval: adaptive.baseCollectionInterval,
    minCollectionInterval: adaptive.minCollectionInterval,
    maxCollectionInterval: adaptive.maxCollectionInterval,
    pointChangeThreshold: adaptive.pointChangeThreshold
  }));
  syncJsonFromPoints();
}

function syncJsonFromPoints() {
  pointsJson.value = JSON.stringify(points.value, null, 2);
}

function formatPointsJson() {
  try {
    const parsed = parsePointsJson(pointsJson.value || "[]");
    pointsJson.value = JSON.stringify(parsed, null, 2);
    error.value = "";
  } catch (caught) {
    error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误";
  }
}

function applyPointsJson() {
  try {
    const currentCode = selectedPoint.value?.pointCode || null;
    const parsed = parsePointsJson(pointsJson.value || "[]");
    const normalized = normalizePointsForEditor(parsed, deviceId.value || "local-device", protocol.value);
    points.value = normalized;
    const nextIndex = currentCode ? normalized.findIndex((item) => item.pointCode === currentCode) : -1;
    selectedPointIndex.value = nextIndex >= 0 ? nextIndex : (normalized.length ? 0 : -1);
    syncJsonFromPoints();
    error.value = "";
  } catch (caught) {
    error.value = caught instanceof Error ? `JSON 格式错误：${caught.message}` : "JSON 格式错误";
  }
}

async function save() {
  error.value = "";
  const mergedConnectionModel = {
    ...buildProtocolInitialModel(connectionFields.value),
    ...connectionModel.value
  };
  const normalizedPoints = normalizePointsForEditor(points.value, deviceId.value || "local-device", protocol.value).map(sanitizePointForSave);
  const errors = [...validateLocalDeviceDraft({ deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value, points: normalizedPoints, cloudTarget: { ...cloudTarget } }), ...validateProtocolModel(connectionFields.value, mergedConnectionModel)];
  if (errors.length > 0) {
    error.value = errors.join("；");
    return;
  }
  saving.value = true;
  try {
    const connection = buildConnectionPayload(connectionFields.value, mergedConnectionModel, { deviceId: deviceId.value, connectionType: protocol.value });
    const payload = buildLocalDevicePayload({
      deviceId: deviceId.value,
      deviceName: deviceName.value,
      protocol: protocol.value,
      adaptive: { ...adaptive },
      connection,
      points: normalizedPoints,
      cloudTarget: { ...cloudTarget },
      overwrite: overwrite.value || Boolean(editingDeviceId.value),
      startAfterSave: startAfterSave.value
    });
    if (editingDeviceId.value) {
      await updateLocalDevice(editingDeviceId.value, payload);
    } else {
      await createLocalDevice(payload);
    }
    if (startAfterSave.value) {
      await startLocalDevice(deviceId.value);
    }
    ElMessage.success(startAfterSave.value ? "本地设备已保存并启动" : "本地设备已保存");
    emit("saved", deviceId.value);
    close(false);
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "本地设备保存失败";
  } finally {
    saving.value = false;
  }
}

function close(value: boolean) {
  emit("update:modelValue", value);
}

function fieldComponent(field: PointEditorField) {
  if (field.control === "switch") {
    return ElSwitch;
  }
  if (field.control === "select") {
    return ElSelect;
  }
  if (field.control === "number") {
    return ElInputNumber;
  }
  return ElInput;
}

function fieldProps(field: PointEditorField) {
  const value = getPointFieldValue(field.path);
  if (field.control === "switch") {
    return { modelValue: Boolean(value), disabled: field.disabled };
  }
  if (field.control === "select") {
    return { modelValue: value ?? "", clearable: true, filterable: true, disabled: field.disabled };
  }
  if (field.control === "number") {
    return { modelValue: toNumber(value), controlsPosition: "right", step: field.step || 1, disabled: field.disabled };
  }
  return { modelValue: value === undefined || value === null ? "" : String(value), disabled: field.disabled };
}

function getPointFieldValue(path: string): unknown {
  return selectedPoint.value ? getPathValue(selectedPoint.value, path) : undefined;
}

function protocolPointFieldPath(field: ProtocolFieldConfig): string {
  return field.name.startsWith("additionalConfig.") ? field.name : `additionalConfig.${field.name}`;
}

function handleLocalEditorKeydown(event: KeyboardEvent) {
  if (event.key === "Escape" && props.modelValue) {
    close(false);
  }
}

watch(() => props.modelValue, (visible) => {
  document.body.classList.toggle("modal-active", visible);
  if (visible) {
    reset(props.editingBundle || null);
    document.addEventListener("keydown", handleLocalEditorKeydown);
  } else {
    document.removeEventListener("keydown", handleLocalEditorKeydown);
  }
}, { immediate: true });

watch(() => props.editingBundle, (bundle) => {
  if (props.modelValue) {
    reset(bundle || null);
  }
});

watch(connectionFields, (fields) => {
  if (props.modelValue && Object.keys(connectionModel.value).length === 0) {
    connectionModel.value = buildProtocolInitialModel(fields);
  }
});

onBeforeUnmount(() => {
  document.body.classList.remove("modal-active");
  document.removeEventListener("keydown", handleLocalEditorKeydown);
});
</script>

<style scoped>
.local-editor-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(2, 6, 23, 0.58);
  backdrop-filter: blur(3px);
}

.local-device-panel {
  --panel-line: var(--console-border-soft, #1e3a5f);
  --panel-muted: var(--console-text-muted, #8aa0b8);
  --panel-text: var(--console-text-primary, #e5edf8);
  position: fixed;
  top: 24px;
  left: 50%;
  z-index: 2001;
  display: grid;
  width: min(1180px, calc(100vw - 48px));
  height: min(860px, calc(100vh - 48px));
  max-height: calc(100vh - 48px);
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  overflow: hidden;
  color: var(--console-text-secondary);
  border: 1px solid var(--panel-line);
  border-radius: 18px;
  background: var(--console-bg);
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.48);
  transform: translateX(-50%);
}

.local-editor-title {
  display: flex;
  min-height: 76px;
  padding: 0 18px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  color: var(--panel-text);
  border-bottom: 1px solid var(--panel-line);
  background: linear-gradient(180deg, var(--console-panel) 0%, var(--console-bg-soft) 100%);
}

.local-editor-title h3,
.local-editor-title p,
.local-section-head h3,
.local-section-head p {
  margin: 0;
}

.local-editor-title h3 {
  margin-top: 3px;
  color: var(--console-text-primary);
  font-size: 18px;
  font-weight: 800;
  line-height: 1.18;
}

.local-editor-title p,
.local-section-head p,
.field-description,
.point-section-note,
.subtable-note,
.point-list-meta span {
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.35;
}

.label-chip,
.pill {
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

.local-editor-title-actions,
.local-editor-stats,
.inline-actions,
.table-actions,
.local-step-actions,
.point-json-actions,
.point-detail-hero-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: nowrap;
}

.local-editor-stat {
  width: 96px;
  min-width: 96px;
  min-height: 46px;
  padding: 6px 9px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel-soft);
}

.local-editor-stat strong,
.local-editor-stat span {
  display: block;
}

.local-editor-stat strong {
  overflow: hidden;
  color: var(--console-text-primary);
  font-size: 15px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-editor-stat span {
  margin-top: 2px;
  color: var(--console-text-dim);
  font-size: 11px;
}

.local-editor-tabs {
  display: flex;
  min-height: 58px;
  padding: 7px 16px;
  gap: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  border-bottom: 1px solid var(--panel-line);
  background: var(--console-bg-soft);
}

.local-editor-tab {
  position: relative;
  flex: 1 0 188px;
  min-height: 44px;
  padding: 7px 10px 7px 42px;
  color: var(--console-text-dim);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-panel);
  text-align: left;
}

.local-editor-tab > span {
  position: absolute;
  top: 9px;
  left: 10px;
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

.local-editor-tab strong,
.local-editor-tab small {
  display: block;
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
  background: rgba(59, 130, 246, 0.16);
}

.local-editor-tab.is-active > span,
.local-editor-tab.is-complete > span {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.local-editor-layout {
  display: grid;
  min-height: 0;
  grid-template-columns: 236px minmax(0, 1fr);
  overflow: hidden;
}

.local-editor-rail {
  display: flex;
  min-height: 0;
  padding: 12px;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
  border-right: 1px solid var(--panel-line);
  background: var(--console-panel);
}

.local-editor-rail strong,
.local-editor-rail p,
.local-checklist li {
  display: block;
}

.local-editor-rail strong {
  margin-top: 7px;
  color: var(--console-text-primary);
  font-size: 14px;
}

.local-editor-rail p {
  margin: 5px 0 0;
}

.local-checklist {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 6px;
  list-style: none;
}

.local-checklist li {
  min-height: 34px;
  padding: 8px 9px;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
  font-size: 12px;
}

.local-checklist li.is-ok {
  color: #34d399;
}

.local-checklist li.is-warn {
  color: #fb923c;
}

.local-checklist li.is-error {
  color: #f87171;
}

.local-editor-body {
  display: flex;
  min-width: 0;
  min-height: 0;
  padding: 12px 16px;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
}

.local-editor-pane {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.local-setup-cluster {
  display: grid;
  min-height: 0;
  grid-template-columns: minmax(320px, 0.9fr) minmax(420px, 1.2fr);
  gap: 12px;
  align-items: start;
}

.local-setup-stable-column,
.local-cloud-detail-stack,
.point-detail-stack {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.local-section-card,
.point-list-panel,
.point-detail-panel,
.readonly-card {
  min-width: 0;
  padding: 10px 12px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
}

.local-section-head,
.point-editor-head,
.point-detail-hero {
  display: flex;
  margin-bottom: 8px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.local-section-head h3,
.point-editor-head strong,
.point-detail-hero strong,
.point-list-meta strong,
.field-group h3 {
  color: var(--console-text-primary);
  font-size: 14px;
  line-height: 1.2;
}

.local-connection-card {
  display: flex;
  max-height: 520px;
  flex-direction: column;
  overflow: hidden;
}

.local-connection-meta {
  display: flex;
  margin-left: auto;
  align-items: center;
  gap: 6px;
  color: var(--console-text-muted);
  font-size: 11px;
}

.local-connection-meta span {
  padding: 2px 6px;
  border: 1px solid var(--console-border-soft);
  border-radius: 999px;
}

.dynamic-form {
  min-height: 0;
  overflow: auto;
}

.dynamic-form :deep(.dynamic-form) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.modao-form-grid,
.compact-form-grid,
.form-grid,
.readonly-grid,
.point-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px 12px;
}

.wide-field,
.field-group-wide,
.point-detail-grid-single {
  grid-column: 1 / -1;
}

.modao-form-grid label,
.form-grid label,
.field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  color: var(--console-text-muted);
  font-size: 12px;
}

.field-label-text,
.field-required {
  font-size: 12px;
}

.field-required {
  color: #fca5a5;
}

.point-workspace,
.local-point-workspace {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(420px, 1fr) 360px;
  gap: 12px;
  align-items: start;
}

.point-list-panel,
.point-detail-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.point-list-meta {
  display: flex;
  min-height: 30px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.table-wrap {
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-panel);
}

.point-table,
.point-subtable,
.cloud-point-table {
  width: 100%;
  border-collapse: collapse;
  color: var(--console-text-secondary);
  font-size: 12px;
}

.point-table th,
.point-table td,
.point-subtable th,
.point-subtable td,
.cloud-point-table th,
.cloud-point-table td {
  padding: 8px 9px;
  border-bottom: 1px solid var(--console-border-soft);
  text-align: left;
}

.point-table th,
.point-subtable th,
.cloud-point-table th {
  color: var(--console-text-muted);
  background: var(--console-bg-soft);
}

.point-table tr.is-selected td {
  background: rgba(59, 130, 246, 0.14);
}

.point-select-button {
  display: grid;
  min-height: 0;
  padding: 0;
  justify-items: start;
  color: inherit;
  border: 0;
  background: transparent;
}

.point-select-button strong,
.point-select-button span {
  display: block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.point-select-button span {
  color: var(--console-text-muted);
  font-size: 11px;
}

.point-detail-panel {
  max-height: 100%;
  overflow: auto;
}

.point-detail-hero {
  padding: 10px;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-lg);
  background: var(--console-bg-soft);
}

.point-detail-hero p,
.point-section-note {
  margin: 4px 0 0;
}

.point-detail-tabbar {
  display: flex;
  min-height: 34px;
  padding: 3px;
  align-items: center;
  gap: 4px;
  overflow-x: auto;
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
}

.point-detail-tab {
  min-height: 28px;
  padding: 0 9px;
  color: var(--console-text-muted);
  border-color: transparent;
  border-radius: var(--console-radius-sm);
  background: transparent;
  font-size: 12px;
}

.point-detail-tab.is-active {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.field-group {
  min-width: 0;
}

.field-group h3 {
  margin: 0 0 8px;
}

.point-json-panel,
.point-json-textarea {
  min-height: 220px;
  color: #dbeafe;
  border-color: #1e3a5f;
  background: #0f172a;
  font-family: "JetBrains Mono", Consolas, monospace;
}

.point-json-textarea {
  width: 100%;
  padding: 10px;
  resize: vertical;
}

.local-editor-footer {
  display: flex;
  min-height: 56px;
  padding: 10px 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--panel-line);
  background: var(--console-panel);
}

@media (max-width: 1280px) {
  .local-device-panel {
    width: calc(100vw - 32px);
  }

  .local-editor-layout,
  .local-setup-cluster,
  .point-workspace,
  .local-point-workspace {
    grid-template-columns: 1fr;
  }

  .local-editor-rail {
    display: none;
  }
}

@media (max-width: 960px) {
  .modao-form-grid,
  .compact-form-grid,
  .form-grid,
  .readonly-grid,
  .point-detail-grid,
  .dynamic-form :deep(.dynamic-form) {
    grid-template-columns: 1fr;
  }

  .local-editor-title,
  .local-editor-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
