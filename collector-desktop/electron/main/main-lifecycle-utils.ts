import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

import type { MenuItemConstructorOptions } from "electron";

export interface ReloadShortcutInput {
  key?: string;
  code?: string;
  control?: boolean;
  meta?: boolean;
  shift?: boolean;
  alt?: boolean;
}

export interface FocusableMainWindow {
  isMinimized(): boolean;
  restore(): void;
  isVisible(): boolean;
  show(): void;
  focus(): void;
}

export interface FatalStartupDiagnosticOptions {
  context: string;
  error: unknown;
  appVersion: string;
  platform: string;
  timestamp?: string;
  maxStackLength?: number;
}

const SECRET_PATTERNS: RegExp[] = [
  /\btoken\s*[:=]\s*[^\r\n;]+/gi,
  /\bencryptedToken\s*[:=]\s*[^\r\n;]+/gi,
  /\bauthorization\s*[:=]\s*[^\r\n;]+/gi,
  /\bcookie\s*[:=]\s*[^\r\n;]+/gi,
  /\bpassword\s*[:=]\s*[^\r\n;]+/gi
];

export function buildProductionViewMenuTemplate(isDevelopment: boolean): MenuItemConstructorOptions[] {
  return [
    ...(isDevelopment
      ? [
          { label: "重新加载", role: "reload" as const },
          { label: "强制重新加载", role: "forceReload" as const },
          { label: "开发者工具", role: "toggleDevTools" as const },
          { type: "separator" as const }
        ]
      : []),
    { label: "重置缩放", role: "resetZoom" as const },
    { label: "放大", role: "zoomIn" as const },
    { label: "缩小", role: "zoomOut" as const },
    { label: "全屏", role: "togglefullscreen" as const }
  ];
}

export function isReloadShortcut(input: ReloadShortcutInput): boolean {
  const key = String(input.key || "").toLowerCase();
  const code = String(input.code || "").toLowerCase();
  if (key === "f5" || code === "f5") {
    return true;
  }
  const commandOrControl = Boolean(input.control || input.meta);
  if (!commandOrControl || input.alt) {
    return false;
  }
  return key === "r" || code === "keyr";
}

export function focusExistingMainWindow(window: FocusableMainWindow | null | undefined): void {
  if (!window) {
    return;
  }
  if (window.isMinimized()) {
    window.restore();
  }
  if (!window.isVisible()) {
    window.show();
  }
  window.focus();
}

export function getStartupDiagnosticPath(userDataPath: string): string {
  return join(userDataPath, "logs", "collector-desktop-startup.log");
}

export function formatFatalStartupDiagnostic(options: FatalStartupDiagnosticOptions): string {
  const error = options.error instanceof Error ? options.error : new Error(String(options.error || "unknown startup error"));
  const maxStackLength = Math.max(256, options.maxStackLength ?? 8000);
  const stack = sanitizeDiagnosticText(error.stack || error.message).slice(0, maxStackLength);
  return [
    `timestamp=${options.timestamp || new Date().toISOString()}`,
    `context=${sanitizeDiagnosticText(options.context)}`,
    `appVersion=${sanitizeDiagnosticText(options.appVersion)}`,
    `platform=${sanitizeDiagnosticText(options.platform)}`,
    `message=${sanitizeDiagnosticText(error.message)}`,
    "stack:",
    stack
  ].join("\n");
}

export function writeStartupDiagnostic(filePath: string, content: string): void {
  mkdirSync(dirname(filePath), { recursive: true });
  writeFileSync(filePath, `${content.replace(/\r\n/g, "\n").replace(/\r/g, "\n").slice(0, 16_000)}\n`, "utf8");
}

export function sanitizeDiagnosticText(value: string): string {
  return SECRET_PATTERNS.reduce((text, pattern) => text.replace(pattern, (match) => {
    const separator = match.includes(":") ? ":" : "=";
    const key = match.split(separator)[0]?.trim() || "secret";
    return `${key}${separator} [REDACTED]`;
  }), value);
}
