<template>
  <div class="workbench-protocol-form">
    <div v-if="fields.length === 0" class="protocol-form-notice" role="status">
      当前协议暂无可渲染字段，请确认后端 ProtocolDescriptorProvider 是否提供 Schema。
    </div>
    <details
      v-for="group in groups"
      :key="group.name"
      class="protocol-field-group"
      :data-group="group.name"
      :open="!closedGroups.has(group.name)"
      @toggle="recordGroupState(group.name, $event)"
    >
      <summary class="protocol-group-summary">
        <span class="protocol-group-chevron" aria-hidden="true" />
        <h3 class="protocol-group-title">{{ group.title }}</h3>
        <span class="protocol-group-count">{{ group.fields.length }} 项</span>
        <span v-if="group.requiredCount" class="protocol-group-count">{{ group.requiredCount }} 项必填</span>
        <span v-if="group.help" class="protocol-group-help" :title="group.help">{{ group.help }}</span>
        <span class="protocol-group-state" aria-hidden="true">
          <span class="protocol-group-expanded">收起</span>
          <span class="protocol-group-collapsed">展开</span>
        </span>
      </summary>
      <div v-if="group.advancedNote" class="protocol-advanced-note">{{ group.advancedNote }}</div>
      <div class="protocol-field-grid">
        <div
          v-for="{ field, presentation, help } in group.entries"
          :key="field.name"
          class="protocol-field-slot"
          :class="{ 'protocol-field-multiline': presentation.multiline && !presentation.secret }"
          :data-field="field.name"
          :style="{ '--slot-width': `${presentation.slotWidth}px`, '--control-width': `${presentation.controlWidth}px` }"
        >
          <label
            :id="`${fieldId(field.name)}-label`"
            :for="fieldId(field.name)"
            class="protocol-field-label"
            :title="fieldExplanation(field)"
          >
            {{ presentation.label }}<span v-if="field.required" class="protocol-field-required" aria-hidden="true">*</span>
            <span v-if="field.requiredWhen" class="protocol-field-condition">条件必填</span>
          </label>
          <div
            class="protocol-field-control"
            :class="{
              'protocol-switch-control': field.type === 'boolean' && !presentation.secret,
              'protocol-unit-control': Boolean(presentation.unit) && isNumeric(field) && !field.options?.length && !presentation.secret,
              'protocol-secret-control': presentation.secret
            }"
          >
            <template v-if="field.type === 'boolean' && !presentation.secret">
              <button
                :id="fieldId(field.name)"
                type="button"
                role="switch"
                class="protocol-switch"
                :disabled="disabled"
                :aria-checked="Boolean(localModel[field.name])"
                :aria-labelledby="`${fieldId(field.name)}-label`"
                :aria-describedby="fieldDescriptionIds(field, help)"
                :aria-invalid="fieldErrors[field.name]?.length ? true : undefined"
                @click="updateField(field.name, !Boolean(localModel[field.name]))"
              ><span class="protocol-switch-thumb" aria-hidden="true" /></button>
              <span class="protocol-switch-label" aria-hidden="true">{{ localModel[field.name] ? '启用' : '禁用' }}</span>
            </template>
            <select
              v-else-if="field.options?.length && !presentation.secret"
              :id="fieldId(field.name)"
              class="protocol-select"
              :value="modelText(field.name)"
              :disabled="disabled"
              :aria-required="Boolean(field.required)"
              :aria-describedby="fieldDescriptionIds(field, help)"
              :aria-invalid="fieldErrors[field.name]?.length ? true : undefined"
              @change="updateSelect(field, $event)"
            >
              <option value="">未配置</option>
              <option v-if="unknownOption(field)" :value="modelText(field.name)">{{ modelText(field.name) }}（当前值，非允许选项）</option>
              <option v-for="option in field.options" :key="option" :value="option">{{ option }}</option>
            </select>
            <textarea
              v-else-if="presentation.multiline && !presentation.secret"
              :id="fieldId(field.name)"
              class="protocol-textarea"
              :value="modelText(field.name)"
              :disabled="disabled"
              :aria-required="Boolean(field.required)"
              :aria-describedby="fieldDescriptionIds(field, help)"
              :aria-invalid="fieldErrors[field.name]?.length ? true : undefined"
              rows="3"
              spellcheck="false"
              placeholder="未配置"
              @input="updateText(field, $event)"
            />
            <input
              v-else
              :id="fieldId(field.name)"
              class="protocol-input"
              :type="presentation.secret ? 'password' : 'text'"
              :inputmode="isNumeric(field) && !presentation.secret ? 'decimal' : undefined"
              :value="numericDrafts[field.name] ?? modelText(field.name)"
              :disabled="disabled"
              :aria-required="Boolean(field.required)"
              :aria-describedby="fieldDescriptionIds(field, help)"
              :aria-invalid="fieldErrors[field.name]?.length ? true : undefined"
              :autocomplete="presentation.secret ? 'new-password' : 'off'"
              :placeholder="field.name === 'plc4xConnectionString' ? '未配置连接串' : (isNumeric(field) ? '—' : '未配置')"
              spellcheck="false"
              @input="updateText(field, $event)"
            />
            <span
              v-if="presentation.unit && isNumeric(field) && !field.options?.length && !presentation.secret"
              class="protocol-field-unit"
              aria-hidden="true"
            >{{ presentation.unit }}</span>
            <span v-if="presentation.secret" class="protocol-secret-badge" aria-hidden="true">已遮罩</span>
          </div>
          <p v-if="help" :id="`${fieldId(field.name)}-help`" class="protocol-field-help">{{ help }}</p>
          <p
            v-if="fieldErrors[field.name]?.length"
            :id="`${fieldId(field.name)}-error`"
            class="protocol-field-error"
          >{{ fieldErrors[field.name].join('；') }}</p>
        </div>
      </div>
    </details>
    <div v-if="errors.length" class="protocol-form-errors" role="status" aria-live="polite">
      {{ errors.join('；') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from "vue";

import {
  buildProtocolInitialModel,
  displayGroupName,
  groupProtocolFields,
  validateProtocolModel,
  type ProtocolFieldGroup,
  type ProtocolFormModel
} from "@/components/protocol/protocol-form-utils";
import {
  connectionFieldPresentation,
  connectionGroupHelp
} from "@/features/protocol/utils/connection-field-presentation";
import type { ProtocolFieldConfig } from "@/types/protocol";

const props = defineProps<{
  fields: ProtocolFieldConfig[];
  protocol: string;
  modelValue?: ProtocolFormModel;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: ProtocolFormModel];
  validate: [errors: string[]];
}>();

const instanceId = useId();
const localModel = ref<ProtocolFormModel>({});
const numericDrafts = ref<Record<string, string>>({});
const closedGroups = ref(new Set<string>());

const errors = computed(() => validateProtocolModel(props.fields, localModel.value));
const fieldErrors = computed(() => Object.fromEntries(props.fields.map((field) => [
  field.name,
  validateProtocolModel([field], localModel.value)
])));
const groups = computed(() => groupProtocolFields(props.fields).map((group) => ({
  ...group,
  title: group.name.trim().toLowerCase() === "topic" ? "主题参数" : displayGroupName(group.name),
  help: connectionGroupHelp(group.name),
  requiredCount: group.fields.filter((field) => field.required).length,
  advancedNote: advancedGroupNote(group),
  entries: group.fields.map((field) => {
    const presentation = connectionFieldPresentation(props.protocol, field);
    const help = [presentation.help];
    if (field.requiredWhen && !/条件|必填/.test(presentation.help)) {
      help.push(`条件要求：${field.requiredWhen}`);
    }
    if (presentation.secret && !presentation.help) {
      help.push("敏感信息已遮罩；请勿泄露真实值。");
    }
    return { field, presentation, help: help.filter(Boolean).join("\n") };
  })
})));

function fieldId(name: string): string {
  return `${instanceId}-protocol-field-${encodeURIComponent(name)}`;
}

function modelText(name: string): string {
  return String(localModel.value[name] ?? "");
}

function isNumeric(field: ProtocolFieldConfig): boolean {
  return field.type === "number" || field.type === "integer";
}

function fieldExplanation(field: ProtocolFieldConfig): string {
  return [
    field.label || field.name,
    field.description,
    field.required ? "必填" : "",
    field.requiredWhen ? `条件要求：${field.requiredWhen}` : ""
  ].filter(Boolean).join("\n");
}

function fieldDescriptionIds(field: ProtocolFieldConfig, help: string): string | undefined {
  const ids = [];
  if (help) ids.push(`${fieldId(field.name)}-help`);
  if (fieldErrors.value[field.name]?.length) ids.push(`${fieldId(field.name)}-error`);
  return ids.length ? ids.join(" ") : undefined;
}

function advancedGroupNote(group: ProtocolFieldGroup): string {
  if (displayGroupName(group.name) !== "高级参数") return "";
  if (group.fields.some((field) => field.name === "plc4xConnectionString")) {
    return "高级参数按需调整；未配置显式连接串时使用基础连接与路由参数。";
  }
  return [...new Set(group.fields.map((field) => field.description).filter(Boolean))].join("；");
}

function recordGroupState(name: string, event: Event): void {
  const section = event.currentTarget as HTMLDetailsElement;
  if (section.open) closedGroups.value.delete(name);
  else closedGroups.value.add(name);
}

function unknownOption(field: ProtocolFieldConfig): boolean {
  const value = modelText(field.name);
  return value !== "" && !field.options?.includes(value);
}

function updateField(name: string, value: ProtocolFormModel[string]): void {
  if (props.disabled) return;
  const previousErrors = fieldErrors.value;
  localModel.value = { ...localModel.value, [name]: value };
  // 只展开本次新增的错误所在分组，不持续覆盖用户主动收起的状态。
  for (const group of groups.value) {
    if (group.fields.some((field) => fieldErrors.value[field.name].some(
      (error) => !previousErrors[field.name]?.includes(error)
    ))) {
      closedGroups.value.delete(group.name);
    }
  }
  emit("update:modelValue", { ...localModel.value });
  emit("validate", errors.value);
}

function numericValue(raw: string): ProtocolFormModel[string] {
  if (!raw.trim()) return null;
  const value = Number(raw);
  return Number.isFinite(value) ? value : raw;
}

function updateText(field: ProtocolFieldConfig, event: Event): void {
  if (props.disabled) return;
  const raw = (event.currentTarget as HTMLInputElement | HTMLTextAreaElement).value;
  if (isNumeric(field)) {
    // 文本型原生输入保留不完整数字，避免浏览器 number 控件先清空非法值。
    numericDrafts.value[field.name] = raw;
    updateField(field.name, numericValue(raw));
  } else {
    updateField(field.name, raw);
  }
}

function updateSelect(field: ProtocolFieldConfig, event: Event): void {
  const raw = (event.currentTarget as HTMLSelectElement).value;
  // 非规范数字枚举保留 Schema 的真实选项标识，不擅自归一化或清除未知值。
  const value = isNumeric(field) && (raw === "" || String(Number(raw)) === raw) ? numericValue(raw) : raw;
  updateField(field.name, value);
}

let previousFields: ProtocolFieldConfig[] | undefined;
let previousProtocol: string | undefined;
watch(() => [props.fields, props.modelValue, props.protocol] as const, ([fields, modelValue, protocol]) => {
  const nextModel = { ...buildProtocolInitialModel(fields), ...(modelValue || {}) };
  const sameSchema = fields === previousFields && protocol === previousProtocol;
  // 父组件回传刚编辑的 number 时保留输入草稿；外部替换值或切换 Schema 时丢弃旧草稿。
  numericDrafts.value = sameSchema ? Object.fromEntries(Object.entries(numericDrafts.value).filter(
    ([name]) => Object.is(nextModel[name], localModel.value[name])
  )) : {};
  localModel.value = nextModel;
  if (!sameSchema) {
    closedGroups.value = new Set();
  }
  previousFields = fields;
  previousProtocol = protocol;
}, { immediate: true, deep: true });
</script>

<style scoped>
.workbench-protocol-form {
  min-width: 0;
  padding: 0 18px;
  color: var(--console-text-secondary);
}

.workbench-protocol-form .protocol-field-group {
  min-width: 0;
  border-bottom: 1px solid rgba(82, 121, 166, 0.28);
}

.workbench-protocol-form .protocol-field-group:last-of-type {
  border-bottom: 0;
}

.workbench-protocol-form .protocol-group-summary {
  display: flex;
  box-sizing: border-box;
  min-height: 49px;
  padding: 10px 0;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  list-style: none;
}

.workbench-protocol-form .protocol-group-summary::-webkit-details-marker {
  display: none;
}

.workbench-protocol-form .protocol-group-chevron {
  width: 7px;
  height: 7px;
  margin: 0 5px 0 2px;
  flex: none;
  border-right: 1px solid #9db4d2;
  border-bottom: 1px solid #9db4d2;
  transform: rotate(-45deg);
}

.workbench-protocol-form .protocol-field-group[open] > .protocol-group-summary .protocol-group-chevron {
  margin-top: -3px;
  transform: rotate(45deg);
}

.workbench-protocol-form .protocol-group-title {
  margin: 0;
  color: var(--console-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.workbench-protocol-form .protocol-group-count {
  color: #94a3b8;
  font-size: 11px;
}

.workbench-protocol-form .protocol-group-help {
  min-width: 0;
  margin-left: 7px;
  color: #8ea4bf;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.workbench-protocol-form .protocol-group-state {
  margin-left: auto;
  flex-shrink: 0;
  color: #8ea7c7;
  font-size: 11px;
}

.workbench-protocol-form .protocol-group-expanded {
  display: none;
}

.workbench-protocol-form .protocol-field-group[open] > .protocol-group-summary .protocol-group-expanded {
  display: inline;
}

.workbench-protocol-form .protocol-field-group[open] > .protocol-group-summary .protocol-group-collapsed {
  display: none;
}

.workbench-protocol-form .protocol-field-grid {
  display: flex;
  min-width: 0;
  padding: 0 0 18px;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 18px 26px;
}

.workbench-protocol-form .protocol-field-slot {
  width: min(var(--slot-width), 100%);
  min-width: 0;
  flex: 0 0 auto;
}

.workbench-protocol-form .protocol-field-multiline {
  flex-basis: 100%;
}

.workbench-protocol-form .protocol-field-label {
  display: block;
  min-height: 20px;
  margin-bottom: 6px;
  color: #bdccdf;
  font-size: 12px;
  font-weight: 400;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.workbench-protocol-form .protocol-field-required {
  margin-left: 4px;
  color: #f3a7a7;
}

.workbench-protocol-form .protocol-field-condition {
  margin-left: 7px;
  color: #c3b28a;
  font-size: 10px;
  font-weight: 400;
}

.workbench-protocol-form .protocol-field-control {
  position: relative;
  width: min(var(--control-width), 100%);
  min-width: 0;
}

.workbench-protocol-form input.protocol-input,
.workbench-protocol-form select.protocol-select,
.workbench-protocol-form textarea.protocol-textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  height: 35px;
  min-height: 35px;
  padding: 7px 9px;
  color: #dce9f9;
  border: 1px solid #3a5f8c;
  border-radius: 4px;
  outline: none;
  background: #12263b;
  font: 12px/1.65 Consolas, "Microsoft YaHei", monospace;
}

.workbench-protocol-form textarea.protocol-textarea {
  height: 85px;
  min-height: 85px;
  padding: 7px 9px;
  line-height: 1.8;
  resize: vertical;
}

.workbench-protocol-form input.protocol-input::placeholder,
.workbench-protocol-form textarea.protocol-textarea::placeholder {
  color: #829ab5;
  opacity: 1;
}

.workbench-protocol-form .protocol-unit-control input.protocol-input {
  padding-right: 45px;
}

.workbench-protocol-form .protocol-secret-control input.protocol-input {
  padding-right: 65px;
}

.workbench-protocol-form .protocol-field-unit,
.workbench-protocol-form .protocol-secret-badge {
  position: absolute;
  top: 8px;
  right: 10px;
  color: #94a3b8;
  font-size: 11px;
  pointer-events: none;
}

.workbench-protocol-form .protocol-secret-badge {
  color: #91aac9;
  font-size: 10px;
}

.workbench-protocol-form .protocol-switch-control {
  display: flex;
  box-sizing: border-box;
  height: 35px;
  min-height: 35px;
  padding: 5px 0;
  align-items: center;
  gap: 9px;
  border: 0;
  background: transparent;
}

.workbench-protocol-form button.protocol-switch {
  display: block;
  box-sizing: border-box;
  width: 32px;
  height: 18px;
  min-height: 18px;
  padding: 0;
  flex: none;
  border: 0;
  border-radius: 10px;
  background: #4b6078;
  cursor: pointer;
}

.workbench-protocol-form button.protocol-switch:hover:not(:disabled) {
  border: 0;
  background: #4b6078;
}

.workbench-protocol-form button.protocol-switch[aria-checked="true"],
.workbench-protocol-form button.protocol-switch[aria-checked="true"]:hover:not(:disabled) {
  background: var(--console-primary);
}

.workbench-protocol-form .protocol-switch-thumb {
  display: block;
  width: 12px;
  height: 12px;
  margin-left: 3px;
  border-radius: 50%;
  background: #e2e8f0;
}

.workbench-protocol-form button.protocol-switch[aria-checked="true"] .protocol-switch-thumb {
  margin-left: 17px;
}

.workbench-protocol-form .protocol-switch-label {
  color: #bdd0e7;
  font-size: 12px;
}

.workbench-protocol-form input.protocol-input:disabled,
.workbench-protocol-form select.protocol-select:disabled,
.workbench-protocol-form textarea.protocol-textarea:disabled {
  color: #8fa1b7;
  border-color: #2b405c;
  background: #101c2c;
  cursor: not-allowed;
  opacity: 1;
}

.workbench-protocol-form button.protocol-switch:disabled {
  border: 0;
  background: #4b6078;
  cursor: not-allowed;
  opacity: 0.55;
}

.workbench-protocol-form button.protocol-switch[aria-checked="true"]:disabled {
  background: var(--console-primary);
}

.workbench-protocol-form input.protocol-input[aria-invalid="true"],
.workbench-protocol-form select.protocol-select[aria-invalid="true"],
.workbench-protocol-form textarea.protocol-textarea[aria-invalid="true"] {
  border-color: #f87171;
}

.workbench-protocol-form input.protocol-input:focus,
.workbench-protocol-form select.protocol-select:focus,
.workbench-protocol-form textarea.protocol-textarea:focus {
  border-color: #60a5fa;
  box-shadow: var(--console-focus-ring);
}

.workbench-protocol-form .protocol-group-summary:focus-visible,
.workbench-protocol-form button.protocol-switch:focus-visible,
.workbench-protocol-form input.protocol-input:focus-visible,
.workbench-protocol-form select.protocol-select:focus-visible,
.workbench-protocol-form textarea.protocol-textarea:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 3px;
}

.workbench-protocol-form .protocol-field-help {
  margin: 5px 0 0;
  color: #94a9c4;
  font-size: 11px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.workbench-protocol-form .protocol-field-error {
  margin: 5px 0 0;
  color: #f3a7a7;
  font-size: 11px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.workbench-protocol-form .protocol-advanced-note {
  margin: 0 0 12px;
  padding: 9px 12px;
  color: #a8c0df;
  border: 1px solid #2c486b;
  border-radius: 4px;
  background: #172b43;
  font-size: 11px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.workbench-protocol-form .protocol-form-notice,
.workbench-protocol-form .protocol-form-errors {
  margin: 0 -18px;
  padding: 10px 18px;
  color: #94a9c4;
  font-size: 12px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.workbench-protocol-form .protocol-form-errors {
  color: #ebc497;
  border-top: 1px solid rgba(82, 121, 166, 0.28);
  background: #2b292a;
}

@media (max-width: 760px) {
  .workbench-protocol-form .protocol-group-summary {
    flex-wrap: wrap;
  }

  .workbench-protocol-form .protocol-group-help {
    display: none;
  }
}
</style>
