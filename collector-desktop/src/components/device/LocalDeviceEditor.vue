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
          <h3>{{ editingDeviceId ? "编辑本地临时设备" : "新增本地临时设备" }}</h3>
          <p>创建并配置新的工业协议采集终端</p>
        </div>
        <div class="local-editor-title-actions">
          <div class="local-editor-stats">
            <div class="local-editor-stat">
              <strong>{{ currentProtocolTitle }}</strong>
              <span>当前协议</span>
            </div>
            <div class="local-editor-stat">
              <strong>{{ points.length }}</strong>
              <span>点位数</span>
            </div>
          </div>
          <el-button @click="close(false)">关闭</el-button>
        </div>
      </div>

      <div class="local-editor-tabs" role="tablist" aria-label="新增设备配置分区">
        <button
          v-for="(step, index) in localEditorSteps"
          :key="step.key"
          type="button"
          class="local-editor-tab"
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
            <strong>{{ validationTitle }}</strong>
            <p>红色项需要处理；切换分区不会丢失当前编辑内容。</p>
          </div>
          <ol class="local-checklist">
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

          <section v-show="activeStep === 0" class="local-editor-pane" :class="{ 'is-active': activeStep === 0 }">
            <div class="local-setup-cluster">
              <section class="local-section-card local-setup-card">
                <div class="local-section-head">
                  <div>
                    <span class="label-chip">设备基础</span>
                    <h3>设备与调度参数</h3>
                  </div>
                  <p>设备标识、协议和采集节奏集中放在这里，减少来回滚动。</p>
                </div>

                <div class="modao-form-grid compact-form-grid local-summary-grid">
                  <label>设备 ID *<el-input v-model="deviceId" :disabled="Boolean(editingDeviceId)" placeholder="local-modbus-1" @change="syncDeviceIdToPoints" /></label>
                  <label>设备名称 *<el-input v-model="deviceName" placeholder="本地测试设备" /></label>
                  <label>协议 *<el-select v-model="protocol" filterable @change="onProtocolChanged"><el-option v-for="item in visibleProtocols" :key="item.protocol" :label="`${item.title || item.protocol} (${item.protocol})`" :value="item.protocol" /></el-select></label>
                  <label>基础采集周期 (ms)<el-input-number v-model="adaptive.baseCollectionInterval" :min="100" :step="100" @change="syncAdaptiveToPoints" /></label>
                  <label>最小采集周期 (ms)<el-input-number v-model="adaptive.minCollectionInterval" :min="100" :step="100" @change="syncAdaptiveToPoints" /></label>
                  <label>最大采集周期 (ms)<el-input-number v-model="adaptive.maxCollectionInterval" :min="100" :step="100" @change="syncAdaptiveToPoints" /></label>
                  <label>点位变化阈值<el-input-number v-model="adaptive.pointChangeThreshold" :min="0" :step="0.01" @change="syncAdaptiveToPoints" /></label>
                </div>
              </section>

              <section class="local-section-card local-connection-card">
                <div class="local-section-head">
                  <div>
                    <span class="label-chip">连接参数</span>
                    <h3>协议对应字段</h3>
                  </div>
                  <p>切换协议后，这里会自动刷新到对应的连接参数表单。</p>
                </div>

                <ProtocolDynamicForm v-model="connectionModel" :fields="connectionFields" @validate="connectionErrors = $event" />
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
                  <label>启用云上报<el-switch v-model="cloudTarget.enabled" @change="syncJsonFromPoints" /></label>
                  <label>云设备类型<el-select v-model="cloudTarget.deviceType"><el-option label="子设备" value="SUB_DEVICE" /><el-option label="网关设备" value="GATEWAY" /><el-option label="直连设备" value="DIRECT" /><el-option label="逻辑子设备" value="LOGICAL_SUB_DEVICE" /></el-select></label>
                  <label>云端产品标识（productKey）<el-input v-model="cloudTarget.productKey" placeholder="pk_xxx" /></label>
                  <label>云端设备名称（deviceName）<el-input v-model="cloudTarget.deviceName" placeholder="sub_device_001" /></label>
                  <label>启用拓扑注册<el-switch v-model="cloudTarget.topologyEnabled" /></label>
                  <label class="wide-field">上报主题示例（Topic）<el-input :model-value="cloudTopicPreview" readonly /></label>
                </div>
              </section>
            </div>
          </section>

          <section v-show="activeStep === 1" class="local-editor-pane" :class="{ 'is-active': activeStep === 1 }">
            <section class="point-editor local-section-card">
              <div class="point-editor-head local-section-head">
                <div>
                  <span class="label-chip">点位建模</span>
                  <strong>本地点位列表</strong>
                </div>
                <div class="inline-actions table-actions">
                  <el-input v-model="pointKeyword" placeholder="搜索点位编码 / 名称 / 地址" clearable class="compact-select" />
                  <el-button @click="addPoint">新增点位</el-button>
                  <el-button :disabled="selectedPointIndex < 0" @click="duplicatePoint">复制</el-button>
                  <el-button type="danger" plain :disabled="selectedPointIndex < 0" @click="removePoint">删除</el-button>
                </div>
              </div>

              <div class="point-workspace local-point-workspace">
                <section class="point-list-panel">
                  <div class="point-list-meta">
                    <strong>{{ filteredPoints.length }} 个点位</strong>
                    <span>{{ selectedPoint ? `${selectedPoint.pointCode || '-'} · ${selectedPoint.pointName || '-'}` : '未选择点位' }}</span>
                  </div>
                  <el-table :data="filteredPoints" height="330" border highlight-current-row @row-click="selectPoint">
                    <el-table-column label="点位" min-width="170"><template #default="{ row }"><button type="button" class="point-select-button"><strong>{{ row.pointName || row.pointCode || '-' }}</strong><span>{{ row.pointCode || '-' }}</span></button></template></el-table-column>
                    <el-table-column prop="address" label="地址" min-width="130" />
                    <el-table-column prop="dataType" label="类型" width="110" />
                    <el-table-column prop="readWrite" label="读写" width="88" />
                    <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
                  </el-table>
                </section>

                <section v-if="selectedPoint" class="point-detail-panel">
                  <div class="point-detail-stack">
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
                        <p class="protocol-point-note" v-html="protocolPointNote"></p>
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
                <section v-else class="point-detail-panel empty-state">
                  <strong>暂无选中的点位</strong>
                  <span>先新增一个点位，或从左侧列表选择已有点位。</span>
                </section>
              </div>
            </section>
          </section>

          <section v-show="activeStep === 2" class="local-editor-pane" :class="{ 'is-active': activeStep === 2 }">
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
                    <strong>{{ totalReportFieldCount }} 个上报属性 / {{ cloudTarget.enabled ? '云目标已启用' : '云目标未启用' }}</strong>
                    <span>{{ selectedPoint ? `当前：${selectedPoint.pointName || selectedPoint.pointCode || '-'}` : '未选择点位' }}</span>
                  </div>
                  <el-table :data="points" height="330" border highlight-current-row @row-click="selectPoint">
                    <el-table-column label="点位" min-width="150"><template #default="{ row }"><button type="button" class="point-select-button"><strong>{{ row.pointName || row.pointCode || '-' }}</strong><span>{{ row.pointCode || '-' }}</span></button></template></el-table-column>
                    <el-table-column label="云端目标" min-width="180"><template #default="{ row }">{{ cloudTargetSummary(row) }}</template></el-table-column>
                    <el-table-column label="字段" min-width="160"><template #default="{ row }">{{ row.additionalConfig?.reportField || '-' }}</template></el-table-column>
                    <el-table-column label="状态" width="130"><template #default="{ row }">{{ cloudPointStatus(row) }}</template></el-table-column>
                  </el-table>
                </section>
                <section v-if="selectedPoint" class="point-detail-panel">
                  <div class="point-detail-stack local-cloud-detail-stack">
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
                <section v-else class="point-detail-panel empty-state">
                  <strong>暂无选中的点位</strong>
                  <span>先新增一个点位，或从左侧列表选择已有点位。</span>
                </section>
              </div>
            </section>
          </section>

          <section v-show="activeStep === 3" class="local-editor-pane" :class="{ 'is-active': activeStep === 3 }">
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
                <textarea v-model="pointsJson" spellcheck="false"></textarea>
              </label>
              <div class="inline-actions point-json-actions table-actions">
                <el-button @click="formatPointsJson">格式化 JSON</el-button>
                <el-button type="primary" plain @click="applyPointsJson">应用 JSON 到列表</el-button>
              </div>
            </section>
          </section>
        </div>
      </div>

      <div class="inline-actions local-options local-editor-footer">
        <label class="check-line"><el-checkbox v-model="overwrite">覆盖已有本地临时设备</el-checkbox></label>
        <label class="check-line"><el-checkbox v-model="startAfterSave">保存后立即本地启动</el-checkbox></label>
        <div class="local-step-actions">
          <el-button :disabled="activeStep === 0" @click="moveStep(-1)">上一步</el-button>
          <el-button :disabled="activeStep === localEditorSteps.length - 1" @click="moveStep(1)">{{ activeStep === localEditorSteps.length - 1 ? "已到最后" : "下一步" }}</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存并测试</el-button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch, ElMessage } from "element-plus";

import { createLocalDevice, updateLocalDevice } from "@/api/config.api";
import { startLocalDevice } from "@/api/device.api";
import { getProtocol } from "@/api/protocol.api";
import ProtocolDynamicForm from "@/components/protocol/ProtocolDynamicForm.vue";
import { buildConnectionPayload, buildProtocolInitialModel, extractProtocolModel, getPathValue, setPathValue, validateProtocolModel, type ConnectionPayload, type ProtocolFormModel } from "@/components/protocol/protocol-form-utils";
import { buildLocalDevicePayload, DEFAULT_ADAPTIVE_CONFIG, normalizeLocalPoints, validateLocalDeviceDraft, type AdaptiveConfig, type CloudTargetConfig } from "./local-device-utils";
import type { DataPoint } from "@/types/point";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

interface LocalDeviceBundle {
  device?: Record<string, unknown>;
  connection?: Record<string, unknown>;
  points?: DataPoint[];
}

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
  valueType?: "string" | "number" | "integer" | "boolean";
  options?: SelectOption[];
  required?: boolean;
  description?: string;
  fullWidth?: boolean;
  disabled?: boolean;
  step?: number;
}

interface AlarmRule {
  ruleId?: string;
  ruleName?: string;
  operator?: string;
  threshold?: number;
  duration?: number;
  level?: string;
  enabled?: boolean;
  description?: string;
  [key: string]: unknown;
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
const cloudTopicPreview = computed(() => cloudTarget.enabled && cloudTarget.productKey && cloudTarget.deviceName
  ? `/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post`
  : "未启用云上报或云身份不完整");
const validationSummary = computed(() => validateLocalDeviceDraft({ deviceId: deviceId.value, deviceName: deviceName.value, protocol: protocol.value, points: points.value, cloudTarget: { ...cloudTarget } }));
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
const protocolPointNote = computed(() => {
  const hints = protocolSchema.value?.pointAddressHints || [];
  const notes: string[] = [];
  if (hints.length) {
    notes.push(`当前协议地址示例：${hints.map((item) => `<code>${escapeHtml(item)}</code>`).join(" ")}`);
  }
  if (protocol.value === "SIEMENS_S7") {
    notes.push("S7 地址栏支持简写，例如 DB1.DBX0.0、DB1.DBW0、DB1.DBD4，也支持完整 PLC4X 地址，例如 %DB1:0.0:BOOL、%DB1:4:REAL。MODE/SYS/USR/ALM 只用于订阅模式，不应填在普通点位地址里。");
  }
  if (protocol.value === "MODBUS_TCP" || protocol.value === "MODBUS_RTU") {
    notes.push("Modbus 的 dataType 会直接决定读取长度和寄存器解码方式；下方协议扩展字段主要用于补充兼容配置。");
  } else if (pointFields.value.length) {
    notes.push("下方字段都是协议扩展配置，字段下方的中文备注会说明用途、条件和保存位置。主类型字段如果已经提升到基础信息区，这里不会重复展示。");
  } else {
    notes.push("当前协议没有额外的点位扩展字段。");
  }
  return notes.join("<br>");
});
const cloudTargetDetail = computed(() => {
  if (!cloudTarget.enabled) {
    return "当前设备未启用云上报。请在基础连接页的“云平台身份”中启用云目标（cloudTarget）。";
  }
  if (!cloudTarget.productKey || !cloudTarget.deviceName) {
    return "当前设备已启用云上报，但云端产品标识（productKey）或云端设备名称（deviceName）未填写。";
  }
  return `当前点位将随设备上报到 ${cloudTarget.productKey}/${cloudTarget.deviceName}，主题（Topic）：/sys/${cloudTarget.productKey}/${cloudTarget.deviceName}/thing/property/post`;
});

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
  points.value = normalizeInitialPoints(bundle?.points || [], deviceId.value || "local-device", protocol.value);
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
  points.value = normalizeInitialPoints(points.value, deviceId.value || "local-device", protocol.value);
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
      points.value = normalizeInitialPoints(points.value, deviceId.value || "local-device", normalizedProtocol);
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
  const point = defaultPointTemplate(deviceId.value || "local-device", protocol.value, { pointCode, pointName: `点位 ${points.value.length + 1}` });
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

function removePoint() {
  const point = selectedPoint.value;
  if (!point || selectedPointIndex.value < 0) {
    return;
  }
  if (!window.confirm(`确认删除点位 ${point.pointCode || point.pointName || "当前点位"} 吗？`)) {
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
    const normalized = normalizeInitialPoints(parsed, deviceId.value || "local-device", protocol.value);
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
  const normalizedPoints = normalizeInitialPoints(points.value, deviceId.value || "local-device", protocol.value).map(sanitizePointForSave);
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

function normalizeInitialPoints(rawPoints: DataPoint[], currentDeviceId: string, currentProtocol: string): DataPoint[] {
  const normalized = normalizeLocalPoints(rawPoints, currentDeviceId, currentProtocol, { ...adaptive });
  return normalized.map((point, index) => {
    const pointCode = point.pointCode || `point_${index + 1}`;
    const additionalConfig = { reportEnabled: true, reportField: pointCode, ...(point.additionalConfig || {}) };
    removeDeprecatedCloudIdentityConfig(additionalConfig);
    return {
      ...point,
      pointId: point.pointId || `local-${pointCode}`,
      pointCode,
      pointName: point.pointName || `点位 ${index + 1}`,
      address: point.address || defaultAddress(currentProtocol),
      dataType: point.dataType || pointDataTypes.value[0] || "FLOAT",
      additionalConfig
    };
  });
}

function defaultPointTemplate(currentDeviceId: string, currentProtocol: string, overrides: Partial<DataPoint> = {}): DataPoint {
  const pointCode = overrides.pointCode || "temperature";
  return normalizeInitialPoints([{
    pointCode,
    pointName: overrides.pointName || "温度",
    deviceId: currentDeviceId,
    address: overrides.address || defaultAddress(currentProtocol),
    dataType: overrides.dataType || pointDataTypes.value[0] || "FLOAT",
    readWrite: "R",
    status: 1,
    cacheEnabled: 1,
    alarmEnabled: 0,
    baseCollectionInterval: adaptive.baseCollectionInterval,
    currentCollectionInterval: adaptive.baseCollectionInterval,
    minCollectionInterval: adaptive.minCollectionInterval,
    maxCollectionInterval: adaptive.maxCollectionInterval,
    pointChangeThreshold: adaptive.pointChangeThreshold,
    additionalConfig: {
      reportEnabled: true,
      reportField: pointCode,
      writeAddress: "C_SE_NC_1:1",
      writeCommonAddress: 1,
      writeSelect: false,
      writeQl: 0
    },
    ...overrides
  }], currentDeviceId, currentProtocol)[0];
}

function defaultAddress(currentProtocol = protocol.value): string {
  if (currentProtocol === "MQTT") {
    return "sensor/temperature";
  }
  if (isOpcUaProtocol(currentProtocol)) {
    return "ns=2;s=Channel1.Device1.Tag1";
  }
  if (currentProtocol === "SIEMENS_S7") {
    return "DB1.DBW0";
  }
  return "40001";
}

function normalizeCloudTarget(value: unknown): Partial<CloudTargetConfig> {
  if (!isPlainObject(value)) {
    return {};
  }
  return {
    enabled: Boolean(value.enabled),
    deviceType: String(value.deviceType || "SUB_DEVICE"),
    productKey: value.productKey ? String(value.productKey) : "",
    deviceName: value.deviceName ? String(value.deviceName) : "",
    topologyEnabled: value.topologyEnabled !== false
  };
}

function alarmRules(point: DataPoint | null): AlarmRule[] {
  const raw = point?.alarmRule;
  if (!raw) {
    return [];
  }
  if (Array.isArray(raw)) {
    return raw.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[];
  }
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter(isPlainObject).map((item) => ({ ...item })) as AlarmRule[] : [];
    } catch {
      return [];
    }
  }
  return [];
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

function serializeAlarmRules(rules: AlarmRule[]): string {
  const normalized = rules.map((rule) => pruneEmpty(rule)).filter((rule) => Object.keys(rule).length > 0);
  return normalized.length ? JSON.stringify(normalized) : "";
}

function parsePointsJson(value: string): DataPoint[] {
  const parsed = JSON.parse(value || "[]") as DataPoint | DataPoint[];
  return Array.isArray(parsed) ? parsed : [parsed];
}

function sanitizePointForSave(point: DataPoint): DataPoint {
  const clone = cloneData(point);
  const additionalConfig = isPlainObject(clone.additionalConfig) ? clone.additionalConfig : {};
  removeDeprecatedCloudIdentityConfig(additionalConfig);
  clone.additionalConfig = additionalConfig;
  return clone;
}

function removeDeprecatedCloudIdentityConfig(additionalConfig: Record<string, unknown>) {
  const obsoleteKey = ["report", "Bindings"].join("");
  delete additionalConfig[obsoleteKey];
  delete additionalConfig.reportDeviceName;
  delete additionalConfig.reportProductKey;
  delete additionalConfig.productKey;
  delete additionalConfig.cloudBindings;
}

function cloudTargetSummary(_point: DataPoint): string {
  if (!cloudTarget.enabled) {
    return "未启用";
  }
  return [cloudTarget.productKey, cloudTarget.deviceName].filter(hasValue).join(" / ") || "云身份不完整";
}

function cloudPointStatus(point: DataPoint): string {
  if (!cloudTarget.enabled) {
    return "设备未上云";
  }
  if (!cloudTarget.productKey || !cloudTarget.deviceName) {
    return "云身份不完整";
  }
  if (!hasValue(point.additionalConfig?.reportField)) {
    return "缺少上报属性";
  }
  if (point.additionalConfig?.reportEnabled !== true) {
    return "未开启上报";
  }
  return "可上报";
}

function statusLabel(value: unknown): string {
  return Number(value ?? 1) === 0 ? "禁用" : "启用";
}

function parseBooleanOption(value: unknown): boolean | undefined {
  if (value === "") {
    return undefined;
  }
  return value === true || value === "true" || value === "1" || value === 1;
}

function parseFieldValue(value: unknown, valueType: PointEditorField["valueType"]): unknown {
  if (value === "") {
    return undefined;
  }
  if (valueType === "boolean") {
    return value === true || value === "true" || value === "1" || value === 1;
  }
  if (valueType === "number" || valueType === "integer") {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      return undefined;
    }
    return valueType === "integer" ? Math.trunc(numberValue) : numberValue;
  }
  return value;
}

function toNumber(value: unknown): number | undefined {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : undefined;
}

function findDuplicatePointCode(source: DataPoint[]): string {
  const seen = new Set<string>();
  for (const point of source) {
    const code = String(point.pointCode || "").trim();
    if (!code) {
      continue;
    }
    if (seen.has(code)) {
      return code;
    }
    seen.add(code);
  }
  return "";
}

function createUniqueCode(source: DataPoint[], base: string): string {
  const used = new Set(source.map((point) => String(point.pointCode || "").trim()).filter(Boolean));
  let candidate = base.replace(/[^a-zA-Z0-9_]/g, "_") || "point";
  let index = 1;
  while (used.has(candidate)) {
    candidate = `${base.replace(/[^a-zA-Z0-9_]/g, "_") || "point"}_${index}`;
    index += 1;
  }
  return candidate;
}

function buildReadonlyItems(point: DataPoint | null): Array<{ label: string; value: string }> {
  if (!point) {
    return [];
  }
  return [
    ["记录ID", point.id],
    ["点位ID", point.pointId],
    ["设备ID", point.deviceId],
    ["设备名称", point.deviceName],
    ["基础采集周期", point.baseCollectionInterval],
    ["当前采集周期", point.currentCollectionInterval],
    ["最小采集周期", point.minCollectionInterval],
    ["最大采集周期", point.maxCollectionInterval],
    ["点位变化阈值", point.pointChangeThreshold],
    ["稳定次数", point.stableCount],
    ["最新值", point.lastValue],
    ["变化率", point.changeRate],
    ["最近调整时间", point.lastAdjustTime],
    ["上报属性冲突", point.reportFieldConflict],
    ["创建时间", point.createTime],
    ["更新时间", point.updateTime]
  ].filter(([, value]) => hasValue(value)).map(([label, value]) => ({ label: String(label), value: typeof value === "object" ? JSON.stringify(value) : String(value) }));
}

function firstPointValue(source: DataPoint[] | undefined, key: string): unknown {
  return Array.isArray(source) && source.length ? source[0]?.[key] : undefined;
}

function isOpcUaProtocol(value: string): boolean {
  return value === "OPC_UA" || value === "OPC_UA_PLC4X" || value === "OPC_UA_MILO" || value.startsWith("OPC_UA");
}

function hasValue(value: unknown): boolean {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function cloneData<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function pruneEmpty(rule: AlarmRule): AlarmRule {
  const next: AlarmRule = {};
  for (const [key, value] of Object.entries(rule)) {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      next[key] = value;
    }
  }
  return next;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[char] || char);
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
