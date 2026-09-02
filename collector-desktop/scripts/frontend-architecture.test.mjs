import { existsSync, readdirSync, readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const sourceExtensions = new Set([".ts", ".vue"]);
const legacyViewDir = ["views", "legacy"].join("/");
const runtimeViewDir = ["views", "runtime"].join("/");
const legacyHostName = "Legacy" + "ConsoleView";
const legacyManualShadowName = "Legacy" + "ManualShadowPanels";
const legacyHostClass = "legacy" + "-console";
const oldModaoClass = "modao" + "-exact";
const oldActiveModule = "active" + "Module";
const oldSwitchModule = "switch" + "Module";

function url(relativePath) {
  return new URL(relativePath, import.meta.url);
}

function read(relativePath) {
  return readFileSync(url(relativePath), "utf8");
}

function collectSourceFiles(directoryUrl) {
  return readdirSync(directoryUrl, { withFileTypes: true }).flatMap((entry) => {
    const entryUrl = new URL(`${entry.name}${entry.isDirectory() ? "/" : ""}`, directoryUrl);
    if (entry.isDirectory()) {
      return collectSourceFiles(entryUrl);
    }
    const extension = entry.name.slice(entry.name.lastIndexOf("."));
    return sourceExtensions.has(extension) ? [entryUrl] : [];
  });
}

function collectRelativeSourceFiles(relativeDirectory) {
  const directoryUrl = url(relativeDirectory);
  if (!existsSync(directoryUrl)) {
    return [];
  }
  return collectSourceFiles(directoryUrl).map((fileUrl) => ({
    fileUrl,
    text: readFileSync(fileUrl, "utf8")
  }));
}

function sourceText() {
  return collectRelativeSourceFiles("../src/").map((file) => file.text).join("\n");
}

function expectNoViewImports(relativeDirectory) {
  const offenders = collectRelativeSourceFiles(relativeDirectory).flatMap((file) => {
    const lines = file.text.split(/\r?\n/);
    return lines
      .map((line, index) => ({ line, lineNumber: index + 1 }))
      .filter(({ line }) => /from\s+["']@\/views(?:\/|["'])/.test(line) || /import\(["']@\/views(?:\/|["'])/.test(line))
      .map(({ line, lineNumber }) => `${file.fileUrl.pathname}:${lineNumber}:${line.trim()}`);
  });
  expect(offenders).toEqual([]);
}

describe("前端架构边界", () => {
  it("Legacy Host 与历史目录不再存在", () => {
    expect(existsSync(url("../src/" + legacyViewDir))).toBe(false);
    expect(existsSync(url("../src/" + runtimeViewDir))).toBe(false);
    expect(existsSync(url("../src/styles/" + legacyHostClass + ".css"))).toBe(false);
    expect(existsSync(url("../src/styles/" + "workbench" + ".css"))).toBe(false);
    expect(existsSync(url("../src/components/device/DeviceTree.vue"))).toBe(false);
    expect(existsSync(url("../src/components/layout/AppStatusBar.vue"))).toBe(false);
  });

  it("源码不再引用旧 Legacy 主机、旧切页状态和旧样式锚点", () => {
    const text = sourceText();
    expect(text).not.toContain(legacyHostName);
    expect(text).not.toContain(legacyManualShadowName);
    expect(text).not.toContain(legacyViewDir);
    expect(text).not.toContain(runtimeViewDir);
    expect(text).not.toContain(oldActiveModule);
    expect(text).not.toContain(oldSwitchModule);
    expect(text).not.toContain(legacyHostClass);
    expect(text).not.toContain(oldModaoClass);
    expect(text).not.toContain("theme-anchor");
  });

  it("features、stores、api 不反向依赖 views", () => {
    expectNoViewImports("../src/features/");
    expectNoViewImports("../src/stores/");
    expectNoViewImports("../src/api/");
  });

  it("旧 PointEditor 与 LocalDeviceEditor import 路径不回归", () => {
    const text = sourceText();
    expect(text).not.toContain(["components", "point"].join("/"));
    expect(text).not.toContain(["components", "device", "LocalDeviceEditor"].join("/"));
    expect(text).toContain(["features", "point", "components", "PointEditor.vue"].join("/"));
    expect(text).toContain(["features", "device", "components", "LocalDeviceEditor.vue"].join("/"));
  });

  it("Router 保持 Hash History，业务路由直接挂载独立 View", () => {
    const routerIndex = read("../src/router/index.ts");
    const routeDefinitions = read("../src/router/route-definitions.ts");
    expect(routerIndex).toContain("createWebHashHistory()");
    expect(routeDefinitions).not.toContain(legacyHostName);

    const expectedRoutes = [
      ["dashboard", "DashboardView"],
      ["realtime", "RealtimeView"],
      ["history", "HistoryView"],
      ["alarm", "AlarmView"],
      ["device", "DeviceListView"],
      ["device/workbench", "DeviceWorkbenchView"],
      ["collect", "CollectionView"],
      ["cloud", "CloudView"],
      ["diagnostic", "DiagnosticView"],
      ["log", "LogView"],
      ["network", "NetworkView"],
      ["control", "ControlView"],
      ["shadow", "ShadowView"]
    ];

    for (const [path, viewName] of expectedRoutes) {
      expect(routeDefinitions).toContain(`path: "${path}"`);
      expect(routeDefinitions).toContain(`${viewName}.vue`);
    }
  });
});
