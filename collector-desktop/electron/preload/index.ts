import { contextBridge, ipcRenderer } from "electron";

interface AppInfo {
  name: string;
  version: string;
  platform: string;
  configPath?: string;
  backendManaged?: boolean;
}

interface ServerConfig {
  serverUrl: string;
}

contextBridge.exposeInMainWorld("collectorDesktop", {
  getAppInfo: (): Promise<AppInfo> => ipcRenderer.invoke("collector:get-app-info"),
  getServerConfig: (): Promise<ServerConfig> => ipcRenderer.invoke("collector:get-server-config"),
  setServerConfig: (config: ServerConfig): Promise<ServerConfig> => ipcRenderer.invoke("collector:set-server-config", config),
  openExternal: (url: string): Promise<boolean> => ipcRenderer.invoke("collector:open-external", url),
  onNavigate: (handler: (path: string) => void): (() => void) => {
    const listener = (_event: Electron.IpcRendererEvent, path: string) => handler(path);
    ipcRenderer.on("collector:navigate", listener);
    return () => ipcRenderer.removeListener("collector:navigate", listener);
  }
});
