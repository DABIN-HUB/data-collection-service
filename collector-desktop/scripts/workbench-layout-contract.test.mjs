import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const styleFiles = {
  baseCss: readFileSync(new URL("../src/styles/base.css", import.meta.url), "utf8"),
  elementPlusCss: readFileSync(new URL("../src/styles/element-plus.css", import.meta.url), "utf8"),
  utilitiesCss: readFileSync(new URL("../src/styles/utilities.css", import.meta.url), "utf8"),
  deviceOperationShell: readFileSync(new URL("../src/features/device/components/DeviceOperationShell.vue", import.meta.url), "utf8"),
  deviceConfigPanel: readFileSync(new URL("../src/components/device/DeviceConfigPanel.vue", import.meta.url), "utf8"),
  localDeviceEditor: readFileSync(new URL("../src/features/device/components/LocalDeviceEditor.vue", import.meta.url), "utf8"),
  pointEditor: readFileSync(new URL("../src/features/point/components/PointEditor.vue", import.meta.url), "utf8"),
  alarmTablePanel: readFileSync(new URL("../src/components/alarm/AlarmTablePanel.vue", import.meta.url), "utf8"),
  logPanel: readFileSync(new URL("../src/components/log/LogPanel.vue", import.meta.url), "utf8"),
  protocolDynamicForm: readFileSync(new URL("../src/components/protocol/ProtocolDynamicForm.vue", import.meta.url), "utf8"),
  controlPanel: readFileSync(new URL("../src/features/control/components/ControlPanel.vue", import.meta.url), "utf8"),
  shadowPanel: readFileSync(new URL("../src/features/shadow/components/ShadowPanel.vue", import.meta.url), "utf8")
};

const allStyles = Object.values(styleFiles).join("\n");
const oldLegacyAnchor = "body." + "modao" + "-exact ." + "legacy" + "-console";

function expectStyleContains(selector, declarations, source = allStyles) {
  let selectorIndex = source.indexOf(selector);
  expect(selectorIndex, `缺少样式规则：${selector}`).toBeGreaterThanOrEqual(0);
  while (selectorIndex >= 0) {
    const bodyStart = source.indexOf("{", selectorIndex);
    const bodyEnd = source.indexOf("}", bodyStart);
    expect(bodyStart, `${selector} 缺少声明起始`).toBeGreaterThanOrEqual(0);
    expect(bodyEnd, `${selector} 缺少声明结束`).toBeGreaterThan(bodyStart);
    const body = source.slice(bodyStart + 1, bodyEnd);
    if (declarations.every((declaration) => body.includes(declaration))) {
      return;
    }
    selectorIndex = source.indexOf(selector, bodyEnd);
  }
  throw new Error(`${selector} 没有任何同名规则同时包含：${declarations.join("；")}`);
}

describe("本地临时设备点位建模布局", () => {
  it("已选中点位时空态应完全隐藏，不占用详情面板空间", () => {
    expectStyleContains(".empty-state.hidden", ["display: none"], styleFiles.baseCss);
  });

  it("左侧点位列表和详情区应从顶部开始，避免网格拉伸形成大块空白", () => {
    expect(styleFiles.localDeviceEditor).toContain(".point-list-panel,");
    expectStyleContains(".point-workspace,", ["align-items: start"], styleFiles.localDeviceEditor);
  });

  it("右侧点位详情当前分区应横向填满详情面板", () => {
    expectStyleContains(".wide-field,", ["grid-column: 1 / -1"], styleFiles.localDeviceEditor);
  });
});

describe("设备操作工作台布局", () => {
  it("设备管理进入的三页工作台应复用 DeviceOperationShell 骨架", () => {
    expect(styleFiles.deviceOperationShell).toContain('id="deviceOperationPanel"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor local-device-panel local-device-web-dialog device-operation-panel"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-title"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-tabs"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-tab"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-layout"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-rail device-operation-rail"');
    expect(styleFiles.deviceOperationShell).toContain('class="local-editor-body device-operation-body"');
  });

  it("配置、控制和影子内容应使用统一的本地工作台卡片", () => {
    expect(styleFiles.deviceConfigPanel).toContain('class="local-editor-pane device-config-workbench-pane"');
    expect(styleFiles.deviceConfigPanel).toContain("local-section-card");
    expect(styleFiles.controlPanel).toContain('class="local-editor-pane manual-shadow-pane"');
    expect(styleFiles.controlPanel).toContain("local-section-card");
    expect(styleFiles.shadowPanel).toContain('class="local-editor-pane manual-shadow-pane"');
    expect(styleFiles.shadowPanel).toContain("local-section-card");
  });

  it("设备操作工作台样式应声明三段式布局且不依赖旧 body 锚点", () => {
    expectStyleContains(".device-operation-panel", ["display: grid", "grid-template-rows: auto auto minmax(0, 1fr)"], styleFiles.deviceOperationShell);
    expectStyleContains(".device-operation-panel .local-editor-title", ["border-bottom: 1px solid var(--panel-line)"], styleFiles.deviceOperationShell);
    expectStyleContains(".device-operation-panel .local-editor-tabs", ["background: var(--console-bg-soft"], styleFiles.deviceOperationShell);
    expectStyleContains(".device-operation-panel .local-editor-layout", ["grid-template-columns: 260px minmax(0, 1fr)"], styleFiles.deviceOperationShell);
    expect(allStyles).not.toContain(oldLegacyAnchor);
  });

  it("运行控制与快捷导航应同一行紧凑排列", () => {
    expect(styleFiles.deviceConfigPanel).toContain('class="device-control-grid control-row"');
    expectStyleContains(".device-control-grid.control-row", ["grid-template-columns: minmax(0, 2fr) minmax(360px, 1fr)", "align-items: stretch"], styleFiles.deviceConfigPanel);
    expectStyleContains(".run-control-card,", ["min-height: 92px", "max-height: 105px"], styleFiles.deviceConfigPanel);
    expectStyleContains(".control-status-row", ["display: flex", "align-items: center", "justify-content: flex-start", "gap: 8px", "flex-wrap: nowrap"], styleFiles.deviceConfigPanel);
    expectStyleContains(".quick-actions,", ["display: grid", "grid-template-columns: repeat(4, minmax(0, 1fr))"], styleFiles.deviceConfigPanel);
  });

  it("高级协议字段应按 1920 四列、1600/1440 三列、1280 两列布局", () => {
    expect(styleFiles.protocolDynamicForm).toContain('class="protocol-form-grid"');
    expectStyleContains(".protocol-config-collapse :deep(.dynamic-form)", ["grid-template-columns: repeat(4, minmax(0, 1fr))", "row-gap: 10px"], styleFiles.deviceConfigPanel);
    expectStyleContains(".protocol-config-collapse :deep(.protocol-form-grid)", ["display: contents"], styleFiles.deviceConfigPanel);
    expectStyleContains(".protocol-config-collapse :deep(.protocol-field-group)", ["display: contents"], styleFiles.deviceConfigPanel);
    expect(styleFiles.deviceConfigPanel).toContain("@media (max-width: 1600px)");
    expect(styleFiles.deviceConfigPanel).toContain("grid-template-columns: repeat(3, minmax(0, 1fr))");
    expect(styleFiles.deviceConfigPanel).toContain("@media (max-width: 1440px)");
    expect(styleFiles.deviceConfigPanel).toContain("grid-template-columns: repeat(2, minmax(0, 1fr))");
  });

  it("运行数据区应吃满剩余高度且不再用固定表格高度制造底部空白", () => {
    expect(styleFiles.deviceConfigPanel).not.toContain('height="420"');
    expect(styleFiles.deviceConfigPanel).toContain('class="point-data-table-column table-area"');
    expect(styleFiles.deviceConfigPanel).toContain('class="table-scroll"');
    expectStyleContains(".device-data-panel", ["display: flex", "flex-direction: column", "flex: 1 1 auto", "min-height: 0"], styleFiles.deviceConfigPanel);
    expectStyleContains(".point-content,", ["display: grid", "grid-template-columns: minmax(0, 1fr) 300px", "gap: 12px", "flex: 1 1 auto", "min-height: 0", "align-items: stretch"], styleFiles.deviceConfigPanel);
    expectStyleContains(".table-area", ["display: flex", "flex-direction: column", "min-height: 0", "overflow: hidden"], styleFiles.deviceConfigPanel);
    expectStyleContains(".table-scroll", ["flex: 1 1 auto", "min-height: 0", "overflow: auto"], styleFiles.deviceConfigPanel);
    expectStyleContains(".compact-point-detail", ["align-self: stretch", "width: 300px", "max-width: 300px", "min-height: 0", "overflow: auto"], styleFiles.deviceConfigPanel);
  });

  it("告警筛选应只有一个关键词输入和一个时间范围控件，toolbar 单行", () => {
    expect((styleFiles.alarmTablePanel.match(/v-model="keyword"/g) || [])).toHaveLength(1);
    expect((styleFiles.alarmTablePanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(2);
    expect((styleFiles.alarmTablePanel.match(/type="datetimerange"/g) || [])).toHaveLength(1);
    expectStyleContains(".table-actions,", ["display: flex", "flex-wrap: nowrap"], styleFiles.alarmTablePanel);
  });

  it("日志筛选应只有一个关键词输入且 toolbar 单行", () => {
    expect((styleFiles.logPanel.match(/v-model="keyword"/g) || [])).toHaveLength(1);
    expect((styleFiles.logPanel.match(/<el-input(?=[\s>])/g) || [])).toHaveLength(1);
    expect(styleFiles.logPanel).not.toContain("log-source-filter");
    expectStyleContains(".table-actions,", ["display: flex", "flex-wrap: nowrap"], styleFiles.logPanel);
  });

  it("告警统计卡应为小尺寸深色卡片且不拉满整行", () => {
    expectStyleContains(".alarm-stat-list", ["grid-template-columns: repeat(4, minmax(0, 1fr))", "gap: 8px"], styleFiles.alarmTablePanel);
    expectStyleContains(".alarm-stat-card", ["min-height: 66px", "padding: 9px 10px", "background: var(--console-panel)"], styleFiles.alarmTablePanel);
  });
});
