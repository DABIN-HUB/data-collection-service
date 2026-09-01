import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const workbenchCss = readFileSync(new URL("../src/styles/workbench.css", import.meta.url), "utf8");
const deviceOperationShell = readFileSync(new URL("../src/features/device/components/DeviceOperationShell.vue", import.meta.url), "utf8");
const deviceConfigPanel = readFileSync(new URL("../src/components/device/DeviceConfigPanel.vue", import.meta.url), "utf8");
const protocolDynamicForm = readFileSync(new URL("../src/components/protocol/ProtocolDynamicForm.vue", import.meta.url), "utf8");
const alarmTablePanel = readFileSync(new URL("../src/components/alarm/AlarmTablePanel.vue", import.meta.url), "utf8");
const logPanel = readFileSync(new URL("../src/components/log/LogPanel.vue", import.meta.url), "utf8");
const controlPanel = readFileSync(new URL("../src/features/control/components/ControlPanel.vue", import.meta.url), "utf8");
const shadowPanel = readFileSync(new URL("../src/features/shadow/components/ShadowPanel.vue", import.meta.url), "utf8");

function expectRuleContains(selector, declarations) {
  let selectorIndex = workbenchCss.indexOf(selector);
  expect(selectorIndex, `缺少样式规则：${selector}`).toBeGreaterThanOrEqual(0);
  while (selectorIndex >= 0) {
    const bodyStart = workbenchCss.indexOf("{", selectorIndex);
    const bodyEnd = workbenchCss.indexOf("}", bodyStart);
    expect(bodyStart, `${selector} 缺少声明起始`).toBeGreaterThanOrEqual(0);
    expect(bodyEnd, `${selector} 缺少声明结束`).toBeGreaterThan(bodyStart);
    const body = workbenchCss.slice(bodyStart + 1, bodyEnd);
    if (declarations.every((declaration) => body.includes(declaration))) {
      return;
    }
    selectorIndex = workbenchCss.indexOf(selector, bodyEnd);
  }
  throw new Error(`${selector} 没有任何同名规则同时包含：${declarations.join("；")}`);
}

describe("本地临时设备点位建模布局", () => {
  it("已选中点位时空态应完全隐藏，不占用详情面板空间", () => {
    expectRuleContains("#localDevicePanel.local-device-panel.local-device-web-dialog .empty-state.hidden", ["display: none"]);
  });

  it("左侧点位列表内容应从顶部开始，避免网格拉伸形成大块空白", () => {
    expectRuleContains("#localDevicePanel.local-device-panel.local-device-web-dialog .local-point-workspace .point-list-panel", ["align-content: start"]);
  });

  it("右侧点位详情当前分区应横向填满详情面板", () => {
    expectRuleContains("#localDevicePanel.local-device-panel.local-device-web-dialog .point-detail-grid .field-group-wide", ["grid-column: 1 / -1"]);
  });
});

describe("设备操作工作台布局", () => {
  it("设备管理进入的三页工作台应复用新增设备编辑器骨架", () => {
    expect(deviceOperationShell).toContain('id="deviceOperationPanel"');
    expect(deviceOperationShell).toContain('class="local-editor local-device-panel local-device-web-dialog device-operation-panel"');
    expect(deviceOperationShell).toContain('class="local-editor-title"');
    expect(deviceOperationShell).toContain('class="local-editor-tabs"');
    expect(deviceOperationShell).toContain('class="local-editor-tab"');
    expect(deviceOperationShell).toContain('class="local-editor-layout"');
    expect(deviceOperationShell).toContain('class="local-editor-rail device-operation-rail"');
    expect(deviceOperationShell).toContain('class="local-editor-body device-operation-body"');
  });

  it("配置、控制和影子内容应使用统一的本地工作台卡片", () => {
    expect(deviceConfigPanel).toContain('class="local-editor-pane device-config-workbench-pane"');
    expect(deviceConfigPanel).toContain("local-section-card");
    expect(controlPanel).toContain('class="local-editor-pane manual-shadow-pane"');
    expect(controlPanel).toContain("local-section-card");
    expect(shadowPanel).toContain('class="local-editor-pane manual-shadow-pane"');
    expect(shadowPanel).toContain("local-section-card");
  });

  it("设备操作工作台样式应声明与新增设备一致的三段式布局", () => {
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog", ["display: grid", "background: var(--console-bg, #08131f)"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog", ["background: var(--console-bg)"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-title", ["grid-row: 1", "border-bottom: 1px solid var(--panel-line)"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-tabs", ["grid-row: 2", "background: var(--console-bg-soft, #0d1a2a)"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-layout", ["grid-row: 3", "grid-template-columns: 260px minmax(0, 1fr)"]);
  });

  it("第三轮运行控制与快捷导航应同一行紧凑排列", () => {
    expect(deviceConfigPanel).toContain('class="device-control-grid control-row"');
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .control-row", ["grid-template-columns: minmax(0, 2fr) minmax(360px, 1fr)", "align-items: stretch"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .run-control-card", ["min-height: 92px", "max-height: 105px", "margin-top: 0", "align-items: stretch", "text-align: left"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .quick-nav-card", ["min-height: 92px", "max-height: 105px", "margin-top: 0"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .control-status-row", ["display: flex", "align-items: center", "justify-content: flex-start", "gap: 8px", "width: 100%", "flex-wrap: nowrap"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .quick-actions", ["display: grid", "grid-template-columns: repeat(4, minmax(0, 1fr))"]);
  });

  it("第三轮高级协议字段应按 1920 四列、1440 三列、1200 两列布局", () => {
    expect(protocolDynamicForm).toContain('class="protocol-form-grid"');
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .protocol-config-collapse .dynamic-form", ["grid-template-columns: repeat(4, minmax(0, 1fr))", "row-gap: 10px"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .protocol-config-collapse .protocol-form-grid", ["display: contents"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .protocol-config-collapse .protocol-field-group", ["display: contents"]);
    expect(workbenchCss).toContain("@media (max-width: 1600px)");
    expect(workbenchCss).toContain("grid-template-columns: repeat(3, minmax(0, 1fr))");
    expect(workbenchCss).toContain("@media (max-width: 1200px)");
  });

  it("第四轮运行数据区应吃满剩余高度且不再用固定表格高度制造底部空白", () => {
    expect(deviceConfigPanel).not.toContain('height="420"');
    expect(deviceConfigPanel).toContain('class="point-data-table-column table-area"');
    expect(deviceConfigPanel).toContain('class="table-scroll"');
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .device-data-panel", ["display: flex", "flex-direction: column", "flex: 1 1 auto", "min-height: 0"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .point-content", ["display: grid", "grid-template-columns: minmax(0, 1fr) 300px", "gap: 12px", "flex: 1 1 auto", "min-height: 0", "align-items: stretch"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .table-area", ["display: flex", "flex-direction: column", "min-height: 0", "overflow: hidden"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .table-scroll", ["flex: 1 1 auto", "min-height: 0", "overflow: auto"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .compact-point-detail", ["align-self: stretch", "width: 300px", "max-width: 300px", "min-height: 0", "overflow: auto"]);
  });

  it("第三轮告警筛选应只有一个关键词输入和一个时间范围控件", () => {
    expect((alarmTablePanel.match(/v-model=\"keyword\"/g) || [])).toHaveLength(1);
    expect((alarmTablePanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(2);
    expect((alarmTablePanel.match(/type=\"datetimerange\"/g) || [])).toHaveLength(1);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .alarm-filter-bar", ["display: flex", "flex-wrap: nowrap"]);
  });

  it("第三轮日志筛选应只有一个关键词输入且 toolbar 单行", () => {
    expect((logPanel.match(/v-model=\"keyword\"/g) || [])).toHaveLength(1);
    expect((logPanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(1);
    expect(logPanel).not.toContain("log-source-filter");
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .log-filter-bar", ["display: flex", "flex-wrap: nowrap"]);
  });

  it("第三轮告警统计卡应为小尺寸深色卡片且不拉满整行", () => {
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .alarm-stat-list", ["grid-template-columns: repeat(4, minmax(150px, 190px))", "gap: 12px"]);
    expectRuleContains("body.modao-exact .legacy-console #deviceOperationPanel.local-device-panel.local-device-web-dialog .alarm-stat-card", ["height: 68px", "padding: 10px 12px", "background: var(--console-bg-soft)"]);
  });
});
