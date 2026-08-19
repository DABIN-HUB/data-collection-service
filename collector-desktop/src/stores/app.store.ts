import { defineStore } from "pinia";

import { configureHttp, DEFAULT_SERVER_URL, normalizeServerUrl } from "@/api/http";

interface AppState {
  appName: string;
  appVersion: string;
  serverUrl: string;
  token: string;
  rememberToken: boolean;
  currentUser: string;
  platform: string;
  configPath: string;
  backendManaged: boolean;
  initialized: boolean;
}

const TOKEN_KEY = "collector-desktop-token";
const SERVER_KEY = "collector-desktop-server-url";

export const useAppStore = defineStore("app", {
  state: (): AppState => ({
    appName: "数据采集工作台",
    appVersion: "0.1.0",
    serverUrl: DEFAULT_SERVER_URL,
    token: "",
    rememberToken: false,
    currentUser: "admin",
    platform: "browser",
    configPath: "",
    backendManaged: false,
    initialized: false
  }),
  actions: {
    async initialize() {
      if (this.initialized) {
        return;
      }
      const savedServerUrl = localStorage.getItem(SERVER_KEY);
      const savedToken = localStorage.getItem(TOKEN_KEY);
      if (window.collectorDesktop) {
        const [appInfo, serverConfig] = await Promise.all([
          window.collectorDesktop.getAppInfo(),
          window.collectorDesktop.getServerConfig()
        ]);
        this.appName = appInfo.name || this.appName;
        this.appVersion = appInfo.version || this.appVersion;
        this.platform = appInfo.platform || this.platform;
        this.configPath = appInfo.configPath || "";
        this.backendManaged = Boolean(appInfo.backendManaged);
        this.serverUrl = normalizeServerUrl(savedServerUrl || serverConfig.serverUrl || this.serverUrl);
      } else {
        this.serverUrl = normalizeServerUrl(savedServerUrl || this.serverUrl);
      }
      if (savedToken) {
        this.token = savedToken;
        this.rememberToken = true;
      }
      configureHttp({ serverUrl: this.serverUrl, token: this.token });
      this.initialized = true;
    },
    async updateServerUrl(serverUrl: string) {
      this.serverUrl = normalizeServerUrl(serverUrl);
      localStorage.setItem(SERVER_KEY, this.serverUrl);
      configureHttp({ serverUrl: this.serverUrl });
      if (window.collectorDesktop) {
        await window.collectorDesktop.setServerConfig({ serverUrl: this.serverUrl });
      }
    },
    setToken(token: string, remember: boolean) {
      this.token = token.trim();
      this.rememberToken = remember;
      configureHttp({ token: this.token });
      if (remember && this.token) {
        localStorage.setItem(TOKEN_KEY, this.token);
      } else {
        localStorage.removeItem(TOKEN_KEY);
      }
    },
    login(token: string, remember: boolean) {
      this.setToken(token, remember);
      this.currentUser = "admin";
    },
    logout() {
      this.setToken("", false);
    }
  }
});
