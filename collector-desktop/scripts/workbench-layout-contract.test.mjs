import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const workbenchCss = readFileSync(new URL("../src/styles/workbench.css", import.meta.url), "utf8");

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
