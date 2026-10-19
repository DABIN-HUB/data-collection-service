<script lang="ts">
import { defineComponent, h, type PropType } from "vue";
import { ElOption } from "element-plus";
import type { FieldValueType } from "@/features/point/utils/point-draft-utils";

interface SelectOption { label: string; value: string | number | boolean }
interface PointEditorField { path: string; label: string; control?: "text" | "number" | "select" | "switch"; valueType?: FieldValueType; options?: SelectOption[]; required?: boolean; description?: string; fullWidth?: boolean; disabled?: boolean; step?: number; span?: number }

// 展示宽度只约束局部控件，不增加长度、范围或类型校验。
function controlWidth(field: PointEditorField): number {
  const widths: Record<string, number> = {
    pointName: 240, pointCode: 240, dataType: 210, address: 240,
    readWrite: 140, collectionMode: 150, scalingFactor: 140, offset: 140,
    unit: 150, "additionalConfig.readCount": 140, "additionalConfig.byteOrder": 184,
    precision: 140, pointChangeThreshold: 160, deadband: 150, minValue: 150,
    maxValue: 150, priority: 140, cacheEnabled: 140, cacheDuration: 150, status: 140,
    "additionalConfig.reportEnabled": 140, "additionalConfig.reportField": 240,
    "additionalConfig.changeThreshold": 150, "additionalConfig.changeMinIntervalMs": 170,
    "additionalConfig.eventEnabled": 140, "additionalConfig.eventMinIntervalMs": 170,
    "additionalConfig.streamEnabled": 140, "additionalConfig.historyEnabled": 140
  };
  const optionWidth = field.options?.length ? Math.max(...field.options.map((option) => Array.from(option.label).reduce((width, char) => width + ((char.codePointAt(0) || 0) > 255 ? 13 : 7.5), 0))) + 48 : 0;
  const fallback = field.control === "number" ? 140 : field.control === "switch" ? 80 : /url|topic|endpoint|path|script|expression|payload|template/i.test(field.path) ? 480 : 220;
  return Math.max(widths[field.path] || fallback, Math.min(560, optionWidth));
}

export default defineComponent({
  name: "LocalDevicePointFieldGrid",
  props: {
    fields: { type: Array as PropType<PointEditorField[]>, required: true },
    fieldComponent: { type: Function as PropType<(field: PointEditorField) => unknown>, required: true },
    fieldProps: { type: Function as PropType<(field: PointEditorField) => Record<string, unknown>>, required: true },
    updatePointField: { type: Function as PropType<(field: PointEditorField, value: unknown) => void>, required: true },
    layout: { type: String as PropType<"two-column" | "dense" | "four-column" | "single">, default: "two-column" },
    sharedPaths: { type: Array as PropType<string[]>, default: () => [] }
  },
  setup(props) {
    return () => h("div", { class: ["point-field-grid", `point-field-grid-${props.layout}`] }, props.fields.map((field) => h("label", {
      key: field.path,
      class: { "wide-field": field.fullWidth, "point-field-full": field.fullWidth },
      "data-point-field": field.path,
      style: { "--control-width": `${controlWidth(field)}px`, ...(field.span ? { "--field-span": field.span } : {}) },
      title: field.description || field.label
    }, [
      h("span", { class: "field-label-text" }, [field.label, field.required ? h("span", { class: "field-required" }, " *") : null]),
      h("div", { class: "point-field-control" }, [h(props.fieldComponent(field) as string, { ...props.fieldProps(field), "onUpdate:modelValue": (value: unknown) => props.updatePointField(field, value) }, () => field.options?.map((option) => h(ElOption, { key: String(option.value), label: option.label, value: option.value })))]),
      field.description ? h("small", { class: "field-description" }, field.description) : null,
      props.sharedPaths.includes(field.path) ? h("small", { class: "same-field" }, field.path === "additionalConfig.byteOrder" ? "与另一处字节序为同一配置，联动修改。" : "与另一处缓存设置为同一配置，联动修改。") : null
    ])));
  }
});
</script>

<style scoped>
.point-field-grid {
  display: grid;
  min-width: 0;
  gap: 15px 20px;
}

.point-field-grid-four-column,
.point-field-grid-dense {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.point-field-grid-two-column {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.point-field-grid-single {
  grid-template-columns: minmax(0, 1fr);
}

.point-field-grid > label {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  grid-column: span var(--field-span, 1);
  color: #c3d0e1;
  font-size: 12px;
}

.point-field-grid > .point-field-full {
  grid-column: 1 / -1;
}

.field-label-text {
  min-height: 18px;
  overflow-wrap: anywhere;
}

.field-required {
  color: #ffa4aa;
}

.point-field-control {
  width: min(var(--control-width), 100%);
  min-width: 0;
}

.point-field-full .point-field-control {
  width: 100%;
}

.field-description,
.same-field {
  color: #9db9da;
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.point-field-control :deep(.el-input),
.point-field-control :deep(.el-input-number),
.point-field-control :deep(.el-select) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

@media (max-width: 1300px) {
  .point-field-grid-four-column,
  .point-field-grid-dense {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1050px) {
  .point-field-grid-four-column,
  .point-field-grid-dense {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .point-field-grid-four-column,
  .point-field-grid-dense,
  .point-field-grid-two-column {
    grid-template-columns: minmax(0, 1fr);
  }

  .point-field-grid > label {
    grid-column: 1 / -1;
  }
}
</style>
