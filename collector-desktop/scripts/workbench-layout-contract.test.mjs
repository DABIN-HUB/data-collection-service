import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const workbenchCss = readFileSync(new URL("../src/styles/workbench.css", import.meta.url), "utf8");
const legacyConsoleView = readFileSync(new URL("../src/views/legacy/LegacyConsoleView.vue", import.meta.url), "utf8");
const deviceConfigPanel = readFileSync(new URL("../src/components/device/DeviceConfigPanel.vue", import.meta.url), "utf8");
const manualShadowPanels = readFileSync(new URL("../src/views/legacy/LegacyManualShadowPanels.vue", import.meta.url), "utf8");

function expectRuleContains(selector, declarations) {
  const selectorIndex = workbenchCss.indexOf(selector);
  expect(selectorIndex, `缺少样式规则：${selector}`).toBeGreaterThanOrEqual(0);
  const bodyStart = workbenchCss.indexOf("{", selectorIndex);
  const bodyEnd = workbenchCss.indexOf("}", bodyStart);
  expect(bodyStart, `${selector} 缺少声明起始`).toBeGreaterThanOrEqual(0);
  expect(bodyEnd, `${selector} 缺少声明结束`).toBeGreaterThan(bodyStart);
  const body = workbenchCss.slice(bodyStart + 1, bodyEnd);
  for (const declaration of declarations) {
    expect(body, `${selector} 应包含 ${declaration}`).toContain(declaration);
  }
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
    expect(legacyConsoleView).toContain('id="deviceOperationPanel"');
    expect(legacyConsoleView).toContain('class="local-editor local-device-panel local-device-web-dialog device-operation-panel"');
    expect(legacyConsoleView).toContain('class="local-editor-title"');
    expect(legacyConsoleView).toContain('class="local-editor-tabs"');
    expect(legacyConsoleView).toContain('class="local-editor-tab"');
    expect(legacyConsoleView).toContain('class="local-editor-layout"');
    expect(legacyConsoleView).toContain('class="local-editor-rail device-operation-rail"');
    expect(legacyConsoleView).toContain('class="local-editor-body device-operation-body"');
  });

  it("配置、控制和影子内容应使用统一的本地工作台卡片", () => {
    expect(deviceConfigPanel).toContain('class="local-editor-pane device-config-workbench-pane"');
    expect(deviceConfigPanel).toContain("local-section-card");
    expect(manualShadowPanels).toContain('class="local-editor-pane manual-shadow-pane"');
    expect(manualShadowPanels).toContain("local-section-card");
  });

  it("设备操作工作台样式应声明与新增设备一致的三段式布局", () => {
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog", ["display: grid", "background: #fff"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-title", ["grid-row: 1", "border-bottom: 1px solid var(--panel-line)"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-tabs", ["grid-row: 2", "background: #f8fbff"]);
    expectRuleContains("#deviceOperationPanel.local-device-panel.local-device-web-dialog .local-editor-layout", ["grid-row: 3", "grid-template-columns: 260px minmax(0, 1fr)"]);
  });
});
