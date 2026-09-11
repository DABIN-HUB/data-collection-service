import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { describe, expect, it } from "vitest";

import { buildAboutInfo, buildWindowChromeOptions, isExternalNavigationUrl, isSafeExternalUrl, isTrustedRendererUrl, normalizeServerConfig, normalizeWindowState } from "./main-utils.js";

describe("main-utils", () => {
  it("归一化服务地址并补齐 collector context-path", () => {
    expect(normalizeServerConfig({ serverUrl: "http://127.0.0.1:9090/" })).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
    expect(normalizeServerConfig({ serverUrl: "http://127.0.0.1:9090/collector/" })).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
  });

  it("窗口尺寸不小于工业工作台最小尺寸", () => {
    expect(normalizeWindowState({ width: 800, height: 500 })).toMatchObject({ width: 1180, height: 760 });
    expect(normalizeWindowState({ width: 1600, height: 1000 })).toMatchObject({ width: 1600, height: 1000 });
  });

  it("窗口外壳默认隐藏原生白色菜单栏并使用深色背景", () => {
    expect(buildWindowChromeOptions()).toEqual({
      autoHideMenuBar: true,
      menuBarVisible: false,
      backgroundColor: "#0d1b2a"
    });
  });

  it("外链只允许 http/https，拒绝 file 与脚本/系统协议", () => {
    expect(isSafeExternalUrl("https://hermes-agent.nousresearch.com/docs")).toBe(true);
    expect(isSafeExternalUrl("http://127.0.0.1:9090/collector")).toBe(true);
    expect(isSafeExternalUrl("file:///C:/Windows/win.ini")).toBe(false);
    expect(isSafeExternalUrl("javascript:alert(1)")).toBe(false);
    expect(isSafeExternalUrl("data:text/html,boom")).toBe(false);
    expect(isSafeExternalUrl("shell:AppsFolder")).toBe(false);
    expect(isSafeExternalUrl("cmd:calc")).toBe(false);
    expect(isSafeExternalUrl("powershell:calc")).toBe(false);
    expect(isSafeExternalUrl("ftp://127.0.0.1/file")).toBe(false);
  });

  it("生产环境只信任 packaged renderer index.html，并允许 hash route", () => {
    const indexPath = resolve("C:/app/resources/app.asar/dist/renderer/index.html");
    const indexUrl = pathToFileURL(indexPath).toString();
    expect(isTrustedRendererUrl(indexUrl, { isDev: false, rendererIndexPath: indexPath })).toBe(true);
    expect(isTrustedRendererUrl(`${indexUrl}#/dashboard`, { isDev: false, rendererIndexPath: indexPath })).toBe(true);
    expect(isTrustedRendererUrl("file:///C:/Windows/win.ini", { isDev: false, rendererIndexPath: indexPath })).toBe(false);
    expect(isTrustedRendererUrl("file:///C:/app/resources/app.asar/dist/renderer/other.html", { isDev: false, rendererIndexPath: indexPath })).toBe(false);
    expect(isExternalNavigationUrl("file:///C:/Windows/win.ini", { isDev: false, rendererIndexPath: indexPath })).toBe(true);
  });

  it("开发环境按 URL 结构信任 dev server，拒绝 host/prefix spoof", () => {
    const options = { isDev: true, devServerUrl: "http://localhost:5173" };
    expect(isTrustedRendererUrl("http://localhost:5173/#/dashboard", options)).toBe(true);
    expect(isTrustedRendererUrl("http://localhost:5173/src/main.ts", options)).toBe(true);
    expect(isTrustedRendererUrl("http://localhost:5173.evil.com/#/dashboard", options)).toBe(false);
    expect(isTrustedRendererUrl("http://evil.com/http://localhost:5173", options)).toBe(false);
    expect(isTrustedRendererUrl("https://localhost:5173/#/dashboard", options)).toBe(false);
  });

  it("构造关于信息且明确不管理后端进程", () => {
    expect(buildAboutInfo("0.1.0", "win32")).toContain("v0.1.0");
    expect(buildAboutInfo("0.1.0", "win32")).toContain("不会自动启动 Spring Boot");
  });
});
