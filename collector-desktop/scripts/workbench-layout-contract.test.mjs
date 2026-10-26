import { readFileSync } from "node:fs";

import { parse as parseSfc } from "@vue/compiler-sfc";
import postcss from "postcss";
import { describe, expect, it } from "vitest";

const sourcePaths = {
  baseCss: "../src/styles/base.css",
  elementPlusCss: "../src/styles/element-plus.css",
  utilitiesCss: "../src/styles/utilities.css",
  deviceOperationShell: "../src/features/device/components/DeviceOperationShell.vue",
  deviceConfigPanel: "../src/components/device/DeviceConfigPanel.vue",
  deviceWorkbenchView: "../src/views/device/DeviceWorkbenchView.vue",
  controlView: "../src/views/control/ControlView.vue",
  shadowView: "../src/views/shadow/ShadowView.vue",
  localDeviceEditor: "../src/features/device/components/LocalDeviceEditor.vue",
  localPointFieldGrid: "../src/features/device/components/LocalDevicePointFieldGrid.vue",
  pointEditor: "../src/features/point/components/PointEditor.vue",
  alarmTablePanel: "../src/components/alarm/AlarmTablePanel.vue",
  logPanel: "../src/components/log/LogPanel.vue",
  workbenchProtocolForm: "../src/features/protocol/components/WorkbenchProtocolForm.vue",
  controlPanel: "../src/features/control/components/ControlPanel.vue",
  shadowPanel: "../src/features/shadow/components/ShadowPanel.vue"
};

const sources = Object.fromEntries(Object.entries(sourcePaths).map(([key, path]) => [key, readFileSync(new URL(path, import.meta.url), "utf8")]));
const styles = Object.fromEntries(Object.entries(sourcePaths).map(([key, path]) => {
  const fileUrl = new URL(path, import.meta.url);
  if (path.endsWith(".css")) return [key, [postcss.parse(sources[key])]];
  const { descriptor, errors } = parseSfc(sources[key], { filename: fileUrl.pathname });
  expect(errors, `${path} 应可解析`).toEqual([]);
  return [key, descriptor.styles.map((style) => {
    // 跟随组件真实的外部样式引用，不能靠拼接无关 CSS 满足布局断言。
    const css = style.src ? readFileSync(new URL(style.src, fileUrl), "utf8") : style.content;
    return postcss.parse(css);
  })];
}));
const allStyles = Object.values(styles).flat().map((style) => style.toString()).join("\n");
const oldLegacyAnchor = "body." + "modao" + "-exact ." + "legacy" + "-console";

function expectStyleContains(key, selector, declarations, media = "") {
  const matched = [];
  styles[key].forEach((style) => style.walkRules((rule) => {
    if (!rule.selectors.includes(selector)) return;
    const mediaQueries = [];
    for (let parent = rule.parent; parent; parent = parent.parent) {
      if (parent.type === "atrule" && parent.name === "media") mediaQueries.unshift(parent.params);
    }
    if (mediaQueries.join(" / ") === media) matched.push(rule);
  }));
  expect(matched.length, `${key} 缺少样式规则：${selector} ${media}`).toBeGreaterThan(0);
  // 同选择器及断点按源码顺序取最终声明，不让前面的失效规则掩盖覆盖回归。
  const actual = Object.fromEntries(matched.flatMap((rule) => rule.nodes.filter((node) => node.type === "decl").map((node) => [node.prop, node.value])));
  for (const [property, value] of Object.entries(declarations)) {
    expect(actual[property], `${key} ${selector} ${media} 的 ${property}`).toBe(value);
  }
}

function expectClassList(key, classes) {
  const classLists = [...sources[key].matchAll(/(?<![:\w-])class="([^"]*)"/g)].map((match) => match[1].split(/\s+/));
  expect(classLists.some((tokens) => classes.every((token) => tokens.includes(token))), `${key} 缺少同一元素上的 class：${classes.join(" ")}`).toBe(true);
}

describe("本地临时设备点位建模布局", () => {
  it("已选中点位时空态应完全隐藏，不占用详情面板空间", () => {
    expectStyleContains("baseCss", ".empty-state.hidden", { display: "none" });
  });

  it("点位列表和详情堆栈应从顶部开始，避免网格拉伸形成大块空白", () => {
    expect(sources.localDeviceEditor).toContain('<style scoped src="./local-device-editor.css">');
    expectClassList("localDeviceEditor", ["step-grid", "step-grid-points"]);
    expectClassList("localDeviceEditor", ["point-list-card"]);
    expectClassList("localDeviceEditor", ["point-detail-panel"]);
    expectStyleContains("localDeviceEditor", ".step-grid", { display: "grid", "align-items": "start" });
    expectStyleContains("localDeviceEditor", ".step-grid-points", { "grid-template-columns": "minmax(0, 1fr)" });
    expectStyleContains("localDeviceEditor", ".detail-stack", { display: "flex", "flex-direction": "column", "min-width": "0" });
  });

  it("点位详情中的宽字段应横向填满实际字段网格", () => {
    expect(sources.localDeviceEditor).toContain('import PointFieldGrid from "./LocalDevicePointFieldGrid.vue"');
    expect(sources.localDeviceEditor).toContain('<PointFieldGrid layout="four-column"');
    expect(sources.localPointFieldGrid).toContain('"point-field-full": field.fullWidth');
    expectStyleContains("localDeviceEditor", ".wide-field", { "grid-column": "1 / -1" });
    expectStyleContains("localPointFieldGrid", ".point-field-grid", { display: "grid", "min-width": "0" });
    expectStyleContains("localPointFieldGrid", ".point-field-grid > .point-field-full", { "grid-column": "1 / -1" });
    expectStyleContains("localPointFieldGrid", ".point-field-full .point-field-control", { width: "100%" });
  });
});

describe("设备操作工作台布局", () => {
  it("设备管理进入的三页工作台应复用 DeviceOperationShell 骨架", () => {
    expect(sources.deviceOperationShell).toContain('id="deviceOperationPanel"');
    expectClassList("deviceOperationShell", ["local-editor", "local-device-panel", "local-device-web-dialog", "device-operation-panel", "config-workspace"]);
    for (const className of ["local-editor-title", "local-editor-tabs", "local-editor-tab", "local-editor-layout", "config-overview"]) {
      expectClassList("deviceOperationShell", [className]);
    }
    expectClassList("deviceOperationShell", ["local-editor-body", "device-operation-body"]);
    expect(sources.deviceOperationShell).toContain('<slot :device-id="selectedDeviceId"');
    for (const [view, tab] of [["deviceWorkbenchView", "config"], ["controlView", "control"], ["shadowView", "shadow"]]) {
      expect(sources[view]).toMatch(new RegExp(`<DeviceOperationShell[^>]*active-tab="${tab}"`));
      expect(sources.deviceOperationShell).toContain(`@click="openWorkbenchTab('${tab}')"`);
    }
  });

  it("配置、控制和影子内容应保留各自卡片及统一的工作台视觉 token", () => {
    expectClassList("deviceConfigPanel", ["local-editor-pane", "device-config-workbench-pane"]);
    expectClassList("controlPanel", ["control-workbench"]);
    expectClassList("shadowPanel", ["shadow-workbench"]);
    for (const [key, card] of [["deviceConfigPanel", "local-section-card"], ["controlPanel", "operation-panel"], ["shadowPanel", "shadow-panel"]]) {
      expectClassList(key, [card]);
      expectStyleContains(key, `.${card}`, { "min-width": "0", border: "1px solid var(--console-border-soft)", background: "var(--console-panel)" });
    }
  });

  it("设备操作工作台应纵向排列并保留标题、页签及内容滚动边界，不依赖旧 body 锚点", () => {
    expectStyleContains("deviceOperationShell", ".device-operation-panel.config-workspace", { display: "flex", "flex-direction": "column", height: "100%", "min-height": "0", overflow: "auto" });
    expectStyleContains("deviceOperationShell", ".config-workspace .local-editor-title", { "flex-shrink": "0" });
    expectStyleContains("deviceOperationShell", ".config-workspace .local-editor-tabs", { display: "flex", "flex-shrink": "0", "overflow-x": "auto", "border-bottom": "1px solid var(--console-border-soft)" });
    expectStyleContains("deviceOperationShell", ".local-editor-layout", { "min-width": "0", "min-height": "0" });
    expectStyleContains("deviceOperationShell", ".device-operation-body", { "min-width": "0", "min-height": "0" });
    expect(allStyles).not.toContain(oldLegacyAnchor);
  });

  it("运行控制应位于窄配置轨，数据主区在桌面并排、窄屏优先，原导航和控制入口保留", () => {
    expectClassList("deviceWorkbenchView", ["precision-config-view"]);
    expectClassList("deviceConfigPanel", ["workbench-config-rail"]);
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.device-config-workbench-pane)", { display: "grid", "grid-template-columns": "260px minmax(0, 1fr)", "align-items": "start" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.workbench-config-rail)", { "grid-column": "1", "grid-row": "1", "align-content": "start" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.device-data-panel)", { "grid-column": "2", "grid-row": "1" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.control-status-row)", { display: "grid", width: "100%", gap: "8px" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.run-control-actions)", { display: "grid", "grid-template-columns": "repeat(2, minmax(0, 1fr))" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.device-config-workbench-pane)", { "grid-template-columns": "236px minmax(0, 1fr)" }, "(max-width: 1300px) and (min-width: 901px)");
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.device-config-workbench-pane)", { "grid-template-columns": "minmax(0, 1fr)" }, "(max-width: 900px)");
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.device-data-panel)", { "grid-column": "1", "grid-row": "1" }, "(max-width: 900px)");
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.workbench-config-rail)", { "grid-row": "2" }, "(max-width: 900px)");
    for (const binding of ["@click=\"loadConnectionStatus\"", "@click=\"$emit('start', device.normalizedId)\"", "@click=\"$emit('stop', device.normalizedId)\"", "@click=\"openPointRealtime(selectedWorkbenchPoint)\"", "$emit('open-history',"]) {
      expect(sources.deviceConfigPanel).toContain(binding);
    }
  });

  it("协议字段应由工作台真实表单按字段宽度换行，窄屏转单列而非压缩固定列", () => {
    expect(sources.deviceConfigPanel).toContain('import WorkbenchProtocolForm from "@/features/protocol/components/WorkbenchProtocolForm.vue"');
    expect(sources.deviceConfigPanel).toContain(":fields=\"protocolFields\"");
    expectClassList("workbenchProtocolForm", ["protocol-field-grid"]);
    expect(sources.workbenchProtocolForm).toContain("presentation.slotWidth");
    expectStyleContains("workbenchProtocolForm", ".workbench-protocol-form .protocol-field-grid", { display: "flex", "flex-wrap": "wrap", "min-width": "0", gap: "18px 26px" });
    expectStyleContains("workbenchProtocolForm", ".workbench-protocol-form .protocol-field-slot", { width: "min(var(--slot-width), 100%)", "min-width": "0", flex: "0 0 auto" });
    expectStyleContains("workbenchProtocolForm", ".workbench-protocol-form .protocol-field-control", { width: "min(var(--control-width), 100%)", "min-width": "0" });
    expectStyleContains("workbenchProtocolForm", ".workbench-protocol-form .protocol-field-grid", { "flex-direction": "column", gap: "16px" }, "(max-width: 600px)");
  });

  it("运行数据区应保留局部滚动且不强制固定表格高度，工作台详情位于表格下方", () => {
    const pointTable = sources.deviceConfigPanel.match(/<el-table\s[^>]*class="industrial-point-table"[^>]*>/)?.[0];
    expect(pointTable).toBeDefined();
    expect(pointTable).not.toMatch(/\sheight=/);
    expect(pointTable).toContain('max-height="340"');
    expectClassList("deviceConfigPanel", ["point-data-table-column", "table-area"]);
    expectClassList("deviceConfigPanel", ["table-scroll"]);
    expectStyleContains("deviceConfigPanel", ".device-data-panel", { display: "flex", "flex-direction": "column", "min-height": "0", overflow: "hidden" });
    expectStyleContains("deviceConfigPanel", ".point-data-grid", { display: "grid", "min-height": "0", "align-items": "stretch" });
    expectStyleContains("deviceConfigPanel", ".point-data-table-column", { display: "flex", "flex-direction": "column", "min-height": "0", overflow: "hidden" });
    expectStyleContains("deviceConfigPanel", ".table-scroll", { flex: "1 1 auto", "min-height": "0", overflow: "auto" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.point-data-grid)", { "grid-template-columns": "minmax(0, 1fr)" });
    expectStyleContains("deviceWorkbenchView", ".precision-config-view :deep(.compact-point-detail)", { "border-top": "1px solid var(--console-border-soft)", "border-left": "0" });
  });

  it("告警筛选应只有一个关键词输入和一个时间范围控件，筛选区可换行且不裁切", () => {
    expect((sources.alarmTablePanel.match(/v-model="keyword"/g) || [])).toHaveLength(1);
    expect((sources.alarmTablePanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(2);
    expect((sources.alarmTablePanel.match(/type="datetimerange"/g) || [])).toHaveLength(1);
    expectStyleContains("alarmTablePanel", ".table-actions", { display: "flex", "flex-wrap": "nowrap" });
    expectStyleContains("alarmTablePanel", ".alarm-filter-bar", { display: "flex", "flex-wrap": "wrap", overflow: "visible" });
  });

  it("日志筛选应只有一个关键词输入且 toolbar 单行", () => {
    expect((sources.logPanel.match(/v-model="keyword"/g) || [])).toHaveLength(1);
    expect((sources.logPanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(1);
    expect(sources.logPanel).not.toContain("log-source-filter");
    expectStyleContains("logPanel", ".table-actions", { display: "flex", "flex-wrap": "nowrap" });
  });

  it("告警统计应保留四项摘要和紧凑分隔布局，窄屏转两列", () => {
    expect((sources.alarmTablePanel.match(/class="alarm-stat-card /g) || [])).toHaveLength(4);
    for (const metric of ["total", "active", "recovered", "acknowledged"]) {
      expect(sources.alarmTablePanel).toContain(`{{ summary.${metric} }}`);
    }
    expectStyleContains("alarmTablePanel", ".alarm-stat-list", { display: "grid", "grid-template-columns": "repeat(4, minmax(0, 1fr))", gap: "0" });
    expectStyleContains("alarmTablePanel", ".alarm-stat-card", { "min-height": "0", padding: "0 16px", "border-right": "1px solid var(--console-border-soft)", background: "transparent" });
    expectStyleContains("alarmTablePanel", ".alarm-stat-list", { "grid-template-columns": "repeat(2, minmax(0, 1fr))", "row-gap": "16px" }, "(max-width: 1280px)");
  });
});
