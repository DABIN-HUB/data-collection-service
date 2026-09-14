import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it, vi } from "vitest";

import {
  buildProductionViewMenuTemplate,
  focusExistingMainWindow,
  formatFatalStartupDiagnostic,
  getStartupDiagnosticPath,
  isReloadShortcut,
  writeStartupDiagnostic
} from "./main-lifecycle-utils.js";

function roles(items: ReturnType<typeof buildProductionViewMenuTemplate>): Array<string | undefined> {
  return items.map((item) => typeof item === "object" ? item.role : undefined);
}

describe("main-lifecycle-utils", () => {
  it("production menu 不暴露 reload / forceReload / DevTools，但保留 zoom 与 fullscreen", () => {
    const menuRoles = roles(buildProductionViewMenuTemplate(false));

    expect(menuRoles).not.toContain("reload");
    expect(menuRoles).not.toContain("forceReload");
    expect(menuRoles).not.toContain("toggleDevTools");
    expect(menuRoles).toEqual(expect.arrayContaining(["resetZoom", "zoomIn", "zoomOut", "togglefullscreen"]));
  });

  it("development menu 保留 reload / forceReload / DevTools", () => {
    const menuRoles = roles(buildProductionViewMenuTemplate(true));

    expect(menuRoles).toEqual(expect.arrayContaining(["reload", "forceReload", "toggleDevTools", "resetZoom", "zoomIn", "zoomOut", "togglefullscreen"]));
  });

  it("production reload shortcut 覆盖 F5、Ctrl/Cmd+R 和 Ctrl/Cmd+Shift+R", () => {
    expect(isReloadShortcut({ key: "F5" })).toBe(true);
    expect(isReloadShortcut({ code: "F5" })).toBe(true);
    expect(isReloadShortcut({ key: "r", control: true })).toBe(true);
    expect(isReloadShortcut({ key: "R", meta: true })).toBe(true);
    expect(isReloadShortcut({ code: "KeyR", control: true, shift: true })).toBe(true);
    expect(isReloadShortcut({ code: "KeyR", meta: true, shift: true })).toBe(true);
  });

  it("普通快捷键不被 reload shortcut 误拦截", () => {
    expect(isReloadShortcut({ key: "c", control: true })).toBe(false);
    expect(isReloadShortcut({ key: "v", control: true })).toBe(false);
    expect(isReloadShortcut({ key: "f", control: true })).toBe(false);
    expect(isReloadShortcut({ key: "r" })).toBe(false);
    expect(isReloadShortcut({ key: "r", alt: true })).toBe(false);
  });

  it("second-instance focus helper 恢复最小化窗口、显示隐藏窗口并聚焦", () => {
    const window = {
      isMinimized: vi.fn(() => true),
      restore: vi.fn(),
      isVisible: vi.fn(() => false),
      show: vi.fn(),
      focus: vi.fn()
    };

    focusExistingMainWindow(window);

    expect(window.restore).toHaveBeenCalledOnce();
    expect(window.show).toHaveBeenCalledOnce();
    expect(window.focus).toHaveBeenCalledOnce();
  });

  it("second-instance focus helper 在 mainWindow 不存在时不 crash", () => {
    expect(() => focusExistingMainWindow(null)).not.toThrow();
  });

  it("fatal startup diagnostic 有 context、大小有界，并脱敏 credential/token 内容", () => {
    const diagnostic = formatFatalStartupDiagnostic({
      context: "application startup",
      appVersion: "0.1.0",
      platform: "win32",
      timestamp: "2026-09-14T00:00:00.000Z",
      maxStackLength: 512,
      error: new Error("token=secret-value Authorization: Bearer-secret Cookie: abc encryptedToken=xyz password=pw")
    });

    expect(diagnostic).toContain("context=application startup");
    expect(diagnostic).toContain("appVersion=0.1.0");
    expect(diagnostic).toContain("platform=win32");
    expect(diagnostic).toContain("[REDACTED]");
    expect(diagnostic).not.toContain("secret-value");
    expect(diagnostic).not.toContain("Bearer-secret");
    expect(diagnostic).not.toContain("encryptedToken=xyz");
    expect(diagnostic.length).toBeLessThan(2_000);
  });

  it("startup diagnostic 写入 userData/logs 下的 latest log", () => {
    const userData = mkdtempSync(join(tmpdir(), "collector-startup-diagnostic-"));
    const diagnosticPath = getStartupDiagnosticPath(userData);

    writeStartupDiagnostic(diagnosticPath, "startup failed");

    expect(diagnosticPath).toContain(join(userData, "logs"));
    expect(existsSync(diagnosticPath)).toBe(true);
    expect(readFileSync(diagnosticPath, "utf8")).toContain("startup failed");
    rmSync(userData, { recursive: true, force: true });
  });
});
