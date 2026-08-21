import { app, BrowserWindow, dialog, ipcMain, Menu, shell, type MenuItemConstructorOptions, type MessageBoxOptions } from "electron";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { executeCollectorProxyRequest, type CollectorProxyRequest } from "./http-proxy-utils.js";
import {
  buildAboutInfo,
  buildWindowChromeOptions,
  DEFAULT_SERVER_URL,
  DEFAULT_WINDOW_HEIGHT,
  DEFAULT_WINDOW_WIDTH,
  isSafeExternalUrl,
  MIN_WINDOW_HEIGHT,
  MIN_WINDOW_WIDTH,
  normalizeServerConfig,
  normalizeWindowState,
  type NormalizedWindowState,
  type ServerConfig,
  type WindowState
} from "./main-utils.js";

interface DesktopConfig extends ServerConfig {
  windowState?: WindowState;
}

const DEFAULT_DESKTOP_CONFIG: DesktopConfig = {
  serverUrl: DEFAULT_SERVER_URL,
  windowState: {
    width: DEFAULT_WINDOW_WIDTH,
    height: DEFAULT_WINDOW_HEIGHT,
    maximized: false
  }
};

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const isDev = Boolean(process.env.VITE_DEV_SERVER_URL);
let mainWindow: BrowserWindow | null = null;

function getConfigPath(): string {
  return join(app.getPath("userData"), "collector-desktop-config.json");
}

function readDesktopConfig(): DesktopConfig {
  const configPath = getConfigPath();
  if (!existsSync(configPath)) {
    return DEFAULT_DESKTOP_CONFIG;
  }
  try {
    const raw = JSON.parse(readFileSync(configPath, "utf8")) as Partial<DesktopConfig>;
    return {
      ...normalizeServerConfig(raw),
      windowState: normalizeWindowState(raw.windowState)
    };
  } catch {
    return DEFAULT_DESKTOP_CONFIG;
  }
}

function writeDesktopConfig(config: Partial<DesktopConfig>): DesktopConfig {
  const current = readDesktopConfig();
  const normalized: DesktopConfig = {
    ...current,
    ...normalizeServerConfig({ serverUrl: config.serverUrl || current.serverUrl }),
    windowState: normalizeWindowState(config.windowState || current.windowState)
  };
  const configPath = getConfigPath();
  mkdirSync(dirname(configPath), { recursive: true });
  writeFileSync(configPath, JSON.stringify(normalized, null, 2), "utf8");
  return normalized;
}

function readServerConfig(): ServerConfig {
  return normalizeServerConfig(readDesktopConfig());
}

function writeServerConfig(config: ServerConfig): ServerConfig {
  return normalizeServerConfig(writeDesktopConfig(config));
}

function persistWindowState(window: BrowserWindow): void {
  const bounds = window.getBounds();
  writeDesktopConfig({
    windowState: {
      width: bounds.width,
      height: bounds.height,
      x: bounds.x,
      y: bounds.y,
      maximized: window.isMaximized()
    }
  });
}

function createWindow(): void {
  const config = readDesktopConfig();
  const windowState: NormalizedWindowState = normalizeWindowState(config.windowState);
  const chromeOptions = buildWindowChromeOptions();
  mainWindow = new BrowserWindow({
    width: windowState.width,
    height: windowState.height,
    x: windowState.x,
    y: windowState.y,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    title: "数据采集工作台",
    backgroundColor: chromeOptions.backgroundColor,
    autoHideMenuBar: chromeOptions.autoHideMenuBar,
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      preload: resolve(__dirname, "../preload/index.cjs")
    }
  });
  mainWindow.setMenuBarVisibility(chromeOptions.menuBarVisible);
  mainWindow.setAutoHideMenuBar(chromeOptions.autoHideMenuBar);

  if (windowState.maximized) {
    mainWindow.maximize();
  }

  mainWindow.once("ready-to-show", () => {
    mainWindow?.show();
  });

  mainWindow.on("close", () => {
    if (mainWindow) {
      persistWindowState(mainWindow);
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    openExternalUrl(url);
    return { action: "deny" };
  });

  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (isExternalNavigation(url)) {
      event.preventDefault();
      openExternalUrl(url);
    }
  });

  if (isDev && process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL).catch(() => undefined);
    mainWindow.webContents.openDevTools({ mode: "detach" });
  } else {
    mainWindow.loadFile(resolve(__dirname, "../../renderer/index.html")).catch(() => undefined);
  }
}

function isExternalNavigation(url: string): boolean {
  if (isDev && process.env.VITE_DEV_SERVER_URL && url.startsWith(process.env.VITE_DEV_SERVER_URL)) {
    return false;
  }
  return !url.startsWith("file://");
}

async function openExternalUrl(url: string): Promise<boolean> {
  if (!isSafeExternalUrl(url)) {
    return false;
  }
  await shell.openExternal(url);
  return true;
}

function buildMenuTemplate(): MenuItemConstructorOptions[] {
  return [
    {
      label: "应用",
      submenu: [
        { label: "连接设置", accelerator: "CmdOrCtrl+,", click: () => mainWindow?.webContents.send("collector:navigate", "/login") },
        { type: "separator" },
        { label: "退出", role: "quit" }
      ]
    },
    {
      label: "视图",
      submenu: [
        { label: "重新加载", role: "reload" },
        { label: "强制重新加载", role: "forceReload" },
        { label: "开发者工具", role: "toggleDevTools" },
        { type: "separator" },
        { label: "重置缩放", role: "resetZoom" },
        { label: "放大", role: "zoomIn" },
        { label: "缩小", role: "zoomOut" },
        { label: "全屏", role: "togglefullscreen" }
      ]
    },
    {
      label: "导航",
      submenu: [
        { label: "控制台总览", click: () => mainWindow?.webContents.send("collector:navigate", "/dashboard") },
        { label: "设备管理", click: () => mainWindow?.webContents.send("collector:navigate", "/device") },
        { label: "实时数据", click: () => mainWindow?.webContents.send("collector:navigate", "/realtime") },
        { label: "历史数据", click: () => mainWindow?.webContents.send("collector:navigate", "/history") },
        { label: "系统诊断", click: () => mainWindow?.webContents.send("collector:navigate", "/diagnostic") }
      ]
    },
    {
      label: "帮助",
      submenu: [
        { label: "打开项目文档", click: () => openExternalUrl("https://hermes-agent.nousresearch.com/docs").catch(() => undefined) },
        {
          label: "关于",
          click: () => {
            const options: MessageBoxOptions = {
              type: "info",
              title: "关于数据采集工作台",
              message: "数据采集工作台",
              detail: buildAboutInfo(app.getVersion(), process.platform),
              buttons: ["确定"]
            };
            if (mainWindow) {
              dialog.showMessageBox(mainWindow, options).catch(() => undefined);
            } else {
              dialog.showMessageBox(options).catch(() => undefined);
            }
          }
        }
      ]
    }
  ];
}

ipcMain.handle("collector:get-app-info", () => ({
  name: "数据采集工作台",
  version: app.getVersion(),
  platform: process.platform,
  configPath: getConfigPath(),
  backendManaged: false
}));

ipcMain.handle("collector:get-server-config", () => readServerConfig());

ipcMain.handle("collector:set-server-config", (_event, config: ServerConfig) => writeServerConfig(config));

ipcMain.handle("collector:open-external", (_event, url: string) => openExternalUrl(url));

ipcMain.handle("collector:http-request", (_event, request: CollectorProxyRequest) => executeCollectorProxyRequest({
  ...request,
  serverUrl: request.serverUrl || readServerConfig().serverUrl
}));

app.setAppUserModelId("com.wangbin.collector.desktop");

app.whenReady().then(() => {
  Menu.setApplicationMenu(Menu.buildFromTemplate(buildMenuTemplate()));
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
}).catch(() => undefined);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
