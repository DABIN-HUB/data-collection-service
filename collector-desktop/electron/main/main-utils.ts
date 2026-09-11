import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export interface ServerConfig {
  serverUrl: string;
}

export interface WindowState {
  width?: number;
  height?: number;
  x?: number;
  y?: number;
  maximized?: boolean;
}

export interface NormalizedWindowState {
  width: number;
  height: number;
  x?: number;
  y?: number;
  maximized: boolean;
}

export interface WindowChromeOptions {
  autoHideMenuBar: boolean;
  menuBarVisible: boolean;
  backgroundColor: string;
}

export interface TrustedRendererUrlOptions {
  isDev: boolean;
  devServerUrl?: string;
  rendererIndexPath?: string;
}

export const DEFAULT_SERVER_URL = "http://127.0.0.1:9090/collector";
export const MIN_WINDOW_WIDTH = 1180;
export const MIN_WINDOW_HEIGHT = 760;
export const DEFAULT_WINDOW_WIDTH = 1440;
export const DEFAULT_WINDOW_HEIGHT = 920;
export const APP_CHROME_BACKGROUND = "#0d1b2a";

export function normalizeServerConfig(config: Partial<ServerConfig> = {}): ServerConfig {
  return {
    serverUrl: normalizeServerUrl(config.serverUrl || DEFAULT_SERVER_URL)
  };
}

export function normalizeWindowState(state: WindowState = {}): NormalizedWindowState {
  return {
    width: Math.max(MIN_WINDOW_WIDTH, Math.floor(Number(state.width) || DEFAULT_WINDOW_WIDTH)),
    height: Math.max(MIN_WINDOW_HEIGHT, Math.floor(Number(state.height) || DEFAULT_WINDOW_HEIGHT)),
    x: typeof state.x === "number" ? state.x : undefined,
    y: typeof state.y === "number" ? state.y : undefined,
    maximized: Boolean(state.maximized)
  };
}

export function buildWindowChromeOptions(): WindowChromeOptions {
  return {
    autoHideMenuBar: true,
    menuBarVisible: false,
    backgroundColor: APP_CHROME_BACKGROUND
  };
}

export function isSafeExternalUrl(url: string): boolean {
  try {
    const protocol = new URL(url).protocol;
    return ["http:", "https:"].includes(protocol);
  } catch {
    return false;
  }
}

export function isTrustedRendererUrl(url: string, options: TrustedRendererUrlOptions): boolean {
  try {
    const parsed = new URL(url);
    if (options.isDev) {
      if (!options.devServerUrl) {
        return false;
      }
      const devServer = new URL(options.devServerUrl);
      return ["http:", "https:"].includes(parsed.protocol)
        && parsed.protocol === devServer.protocol
        && parsed.hostname === devServer.hostname
        && parsed.port === devServer.port;
    }
    if (parsed.protocol !== "file:" || !options.rendererIndexPath) {
      return false;
    }
    return normalizeFilePath(fileURLToPath(parsed)) === normalizeFilePath(options.rendererIndexPath);
  } catch {
    return false;
  }
}

export function isExternalNavigationUrl(url: string, options: TrustedRendererUrlOptions): boolean {
  return !isTrustedRendererUrl(url, options);
}

export function buildAboutInfo(version: string, platform: string): string {
  return [
    `数据采集工作台 v${version}`,
    `运行平台：${platform}`,
    "桌面端负责配置、控制、监控、展示和诊断。",
    "后端 Spring Boot 服务由用户手动启动，桌面端不会自动启动 Spring Boot jar，也不内置 JRE。"
  ].join("\n");
}

function normalizeServerUrl(serverUrl: string): string {
  const trimmed = serverUrl.trim().replace(/\/+$/, "");
  if (!trimmed) {
    return DEFAULT_SERVER_URL;
  }
  try {
    const url = new URL(trimmed);
    if (url.hostname === "127.0.0.1" && url.port === "9090" && (url.pathname === "" || url.pathname === "/")) {
      url.pathname = "/collector";
      return url.toString().replace(/\/+$/, "");
    }
  } catch {
    return DEFAULT_SERVER_URL;
  }
  return trimmed;
}

function normalizeFilePath(path: string): string {
  return resolve(path).replace(/\\/g, "/").toLowerCase();
}
