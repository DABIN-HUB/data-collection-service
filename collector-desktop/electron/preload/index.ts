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

interface ProxyRequest {
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: unknown;
  headers?: Record<string, string>;
  timeoutMs?: number;
}

interface CredentialStatus {
  hasCredential: boolean;
  remembered: boolean;
  storageAvailable: boolean;
  rememberUnavailable: boolean;
  storageBackend?: string;
  recovery?: unknown;
}

interface ProxyResponse {
  status: number;
  statusText: string;
  headers: Record<string, string>;
  body: unknown;
}

contextBridge.exposeInMainWorld("collectorDesktop", {
  getAppInfo: (): Promise<AppInfo> => ipcRenderer.invoke("collector:get-app-info"),
  getServerConfig: (): Promise<ServerConfig> => ipcRenderer.invoke("collector:get-server-config"),
  setServerConfig: (config: ServerConfig): Promise<ServerConfig> => ipcRenderer.invoke("collector:set-server-config", config),
  getCredentialStatus: (): Promise<CredentialStatus> => ipcRenderer.invoke("collector:get-credential-status"),
  setCredential: (credential: { token: string; remember: boolean }): Promise<CredentialStatus> => ipcRenderer.invoke("collector:set-credential", credential),
  clearCredential: (): Promise<CredentialStatus> => ipcRenderer.invoke("collector:clear-credential"),
  request: (request: ProxyRequest): Promise<ProxyResponse> => ipcRenderer.invoke("collector:http-request", request),
  openExternal: (url: string): Promise<boolean> => ipcRenderer.invoke("collector:open-external", url),
  onNavigate: (handler: (path: string) => void): (() => void) => {
    const listener = (_event: Electron.IpcRendererEvent, path: string) => handler(path);
    ipcRenderer.on("collector:navigate", listener);
    return () => ipcRenderer.removeListener("collector:navigate", listener);
  }
});
