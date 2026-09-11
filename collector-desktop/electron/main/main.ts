import { app, BrowserWindow, dialog, ipcMain, Menu, safeStorage, shell, type IpcMainInvokeEvent, type MenuItemConstructorOptions, type MessageBoxOptions } from "electron";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { MainCredentialStore, createEmptyCredentialStatus, type CredentialStatus } from "./credential-store-utils.js";
import { readJsonWithRecovery, writeJsonAtomic, type RecoveryInfo } from "./desktop-persistence-utils.js";
import { executeCollectorProxyRequest, withAuthoritativeProxyServerUrl, type RendererCollectorProxyRequest } from "./http-proxy-utils.js";
import { assertTrustedIpcSender } from "./ipc-security-utils.js";
import {
  buildAboutInfo,
  buildWindowChromeOptions,
  DEFAULT_SERVER_URL,
  DEFAULT_WINDOW_HEIGHT,
  DEFAULT_WINDOW_WIDTH,
  isExternalNavigationUrl,
  isSafeExternalUrl,
  isTrustedRendererUrl,
  MIN_WINDOW_HEIGHT,
  MIN_WINDOW_WIDTH,
  normalizeServerConfig,
  normalizeServerConfigCandidate,
  normalizeWindowState,
  type NormalizedWindowState,
  type ServerConfig,
  type WindowState
} from "./main-utils.js";
import { applyServerConfigChange } from "./server-config-change-utils.js";

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
let configRecovery: RecoveryInfo | undefined;
let credentialStore: MainCredentialStore | null = null;

function getRendererIndexPath(): string {
  return resolve(__dirname, "../../renderer/index.html");
}

function getConfigPath(): string {
  return join(app.getPath("userData"), "collector-desktop-config.json");
}

function getCredentialPath(): string {
  return join(app.getPath("userData"), "collector-desktop-credentials.json");
}

function readDesktopConfig(): DesktopConfig {
  const configPath = getConfigPath();
  const result = readJsonWithRecovery(configPath, DEFAULT_DESKTOP_CONFIG, normalizePersistedDesktopConfig);
  configRecovery = result.recovery;
  return result.value;
}

function writeDesktopConfig(config: Partial<DesktopConfig>): DesktopConfig {
  const current = readDesktopConfig();
  const normalized: DesktopConfig = {
    ...current,
    ...normalizeServerConfig({ serverUrl: config.serverUrl || current.serverUrl }),
    windowState: normalizeWindowState(config.windowState || current.windowState)
  };
  const configPath = getConfigPath();
  writeJsonAtomic(configPath, normalized);
  return normalized;
}

function normalizePersistedDesktopConfig(raw: unknown): DesktopConfig {
  if (!raw || typeof raw !== "object") {
    throw new Error("桌面配置文件格式无效");
  }
  const config = raw as Partial<DesktopConfig>;
  return {
    ...normalizeServerConfigCandidate({ serverUrl: config.serverUrl || DEFAULT_SERVER_URL }),
    windowState: normalizeWindowState(config.windowState)
  };
}

function readServerConfig(): ServerConfig {
  return normalizeServerConfig(readDesktopConfig());
}

function writeServerConfig(config: ServerConfig): ServerConfig {
  return normalizeServerConfig(writeDesktopConfig(config));
}

function getCredentialStore(): MainCredentialStore {
  if (!credentialStore) {
    credentialStore = new MainCredentialStore(getCredentialPath(), safeStorage, process.platform);
    credentialStore.initialize();
  }
  return credentialStore;
}

function readCredentialStatus(): CredentialStatus {
  return credentialStore?.getStatus() || createEmptyCredentialStatus();
}

async function confirmServerConfigChange(current: ServerConfig, candidate: ServerConfig): Promise<boolean> {
  const options: MessageBoxOptions = {
    type: "warning",
    title: "确认切换采集服务地址",
    message: "确认切换采集服务地址？",
    detail: [
      "当前采集服务：",
      current.serverUrl,
      "",
      "准备切换到：",
      candidate.serverUrl,
      "",
      "修改后，桌面端的后台请求将发送到新的采集服务地址。请确认该地址是可信的采集服务。"
    ].join("\n"),
    buttons: ["取消", "确认切换"],
    defaultId: 0,
    cancelId: 0,
    noLink: true
  };
  const result = mainWindow
    ? await dialog.showMessageBox(mainWindow, options)
    : await dialog.showMessageBox(options);
  return result.response === 1;
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
      sandbox: true,
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
    mainWindow.loadFile(getRendererIndexPath()).catch(() => undefined);
  }
}

function isExternalNavigation(url: string): boolean {
  return isExternalNavigationUrl(url, {
    isDev,
    devServerUrl: process.env.VITE_DEV_SERVER_URL,
    rendererIndexPath: getRendererIndexPath()
  });
}

function isTrustedRendererNavigation(url: string): boolean {
  return isTrustedRendererUrl(url, {
    isDev,
    devServerUrl: process.env.VITE_DEV_SERVER_URL,
    rendererIndexPath: getRendererIndexPath()
  });
}

function assertTrustedSender(event: IpcMainInvokeEvent): void {
  assertTrustedIpcSender(event, mainWindow?.webContents.id, isTrustedRendererNavigation);
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
        ...(isDev ? [
          { label: "强制重新加载", role: "forceReload" as const },
          { label: "开发者工具", role: "toggleDevTools" as const }
        ] : []),
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

ipcMain.handle("collector:get-app-info", (event) => {
  assertTrustedSender(event);
  return {
    name: "数据采集工作台",
    version: app.getVersion(),
    platform: process.platform,
    configPath: getConfigPath(),
    backendManaged: false,
    configRecovery
  };
});

ipcMain.handle("collector:get-server-config", (event) => {
  assertTrustedSender(event);
  return readServerConfig();
});

ipcMain.handle("collector:set-server-config", async (event, config: ServerConfig) => {
  assertTrustedSender(event);
  return applyServerConfigChange(config, {
    readCurrent: readServerConfig,
    write: writeServerConfig,
    confirmChange: confirmServerConfigChange
  });
});

ipcMain.handle("collector:get-credential-status", (event) => {
  assertTrustedSender(event);
  return readCredentialStatus();
});

ipcMain.handle("collector:set-credential", (event, credential: { token?: string; remember?: boolean }) => {
  assertTrustedSender(event);
  return getCredentialStore().setCredential(credential.token || "", Boolean(credential.remember));
});

ipcMain.handle("collector:clear-credential", (event) => {
  assertTrustedSender(event);
  return getCredentialStore().clearCredential();
});

ipcMain.handle("collector:open-external", (event, url: string) => {
  assertTrustedSender(event);
  return openExternalUrl(url);
});

ipcMain.handle("collector:http-request", (event, request: RendererCollectorProxyRequest) => {
  assertTrustedSender(event);
  return executeCollectorProxyRequest({
    ...withAuthoritativeProxyServerUrl(request, readServerConfig().serverUrl),
    token: getCredentialStore().getToken()
  });
});

app.setAppUserModelId("com.wangbin.collector.desktop");

app.whenReady().then(() => {
  credentialStore = new MainCredentialStore(getCredentialPath(), safeStorage, process.platform);
  credentialStore.initialize();
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
