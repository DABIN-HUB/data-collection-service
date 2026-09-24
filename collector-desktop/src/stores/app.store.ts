import { defineStore } from "pinia";

import { configureHttp, DEFAULT_SERVER_URL, isDesktopRuntime, normalizeServerUrl, resolveBrowserServerUrl } from "@/api/http";
import { getSystemCapabilities } from "@/api/system.api";
import { useWebSocketStore } from "@/stores/websocket.store";
import type { SystemCapabilities } from "@/types/system";

interface AppState {
  appName: string;
  appVersion: string;
  serverUrl: string;
  token: string;
  rememberToken: boolean;
  hasCredential: boolean;
  credentialRemembered: boolean;
  credentialStorageAvailable: boolean;
  credentialRememberUnavailable: boolean;
  currentUser: string;
  platform: string;
  configPath: string;
  backendManaged: boolean;
  initialized: boolean;
  capabilities: SystemCapabilities | null;
  capabilitiesError: string;
}

const TOKEN_KEY = "collector-desktop-token";
const SERVER_KEY = "collector-desktop-server-url";
let capabilitiesRequest: Promise<SystemCapabilities | null> | null = null;

export const useAppStore = defineStore("app", {
  state: (): AppState => ({
    appName: "数据采集工作台",
    appVersion: "0.1.0",
    serverUrl: DEFAULT_SERVER_URL,
    token: "",
    rememberToken: false,
    hasCredential: false,
    credentialRemembered: false,
    credentialStorageAvailable: false,
    credentialRememberUnavailable: false,
    currentUser: "admin",
    platform: "browser",
    configPath: "",
    backendManaged: false,
    initialized: false,
    capabilities: null,
    capabilitiesError: ""
  }),
  actions: {
    async initialize() {
      if (this.initialized) {
        return;
      }
      const savedToken = localStorage.getItem(TOKEN_KEY);
      if (isDesktopRuntime() && window.collectorDesktop) {
        const [appInfo, serverConfig, credentialStatus] = await Promise.all([
          window.collectorDesktop.getAppInfo(),
          window.collectorDesktop.getServerConfig(),
          window.collectorDesktop.getCredentialStatus()
        ]);
        this.appName = appInfo.name || this.appName;
        this.appVersion = appInfo.version || this.appVersion;
        this.platform = appInfo.platform || this.platform;
        this.configPath = appInfo.configPath || "";
        this.backendManaged = Boolean(appInfo.backendManaged);
        this.serverUrl = normalizeServerUrl(serverConfig.serverUrl || this.serverUrl);
        this.applyCredentialStatus(credentialStatus);
        if (savedToken) {
          if (credentialStatus.hasCredential) {
            localStorage.removeItem(TOKEN_KEY);
          } else {
            const migrated = await window.collectorDesktop.setCredential({ token: savedToken, remember: true });
            this.applyCredentialStatus(migrated);
            if (migrated.hasCredential) {
              localStorage.removeItem(TOKEN_KEY);
            }
          }
        }
        this.token = "";
        this.rememberToken = this.credentialRemembered;
      } else {
        const savedServerUrl = localStorage.getItem(SERVER_KEY);
        this.serverUrl = normalizeServerUrl(savedServerUrl || resolveBrowserServerUrl() || this.serverUrl);
        if (savedToken) {
          this.token = savedToken;
          this.rememberToken = true;
          this.hasCredential = true;
          this.credentialRemembered = true;
        }
      }
      configureHttp({ serverUrl: this.serverUrl, token: this.token });
      this.initialized = true;
      if (this.hasCredential) {
        void this.refreshCapabilities();
      }
    },
    async refreshCapabilities(): Promise<SystemCapabilities | null> {
      if (capabilitiesRequest) {
        return capabilitiesRequest;
      }
      capabilitiesRequest = getSystemCapabilities()
        .then((capabilities) => {
          this.capabilities = capabilities;
          this.capabilitiesError = "";
          useWebSocketStore().setSupported(Boolean(capabilities.realtime.websocketAvailable));
          return capabilities;
        })
        .catch((error: unknown) => {
          this.capabilities = null;
          this.capabilitiesError = error instanceof Error ? error.message : "系统能力读取失败";
          useWebSocketStore().setSupported(false);
          return null;
        })
        .finally(() => {
          capabilitiesRequest = null;
        });
      return capabilitiesRequest;
    },
    async updateServerUrl(serverUrl: string) {
      const candidate = normalizeServerUrl(serverUrl);
      this.capabilities = null;
      this.capabilitiesError = "";
      useWebSocketStore().setSupported(false);
      useWebSocketStore().disableRealtime();
      if (isDesktopRuntime() && window.collectorDesktop) {
        const persisted = await window.collectorDesktop.setServerConfig({ serverUrl: candidate });
        this.serverUrl = normalizeServerUrl(persisted.serverUrl || this.serverUrl);
      } else {
        this.serverUrl = candidate;
      }
      localStorage.setItem(SERVER_KEY, this.serverUrl);
      configureHttp({ serverUrl: this.serverUrl });
      if (this.hasCredential) {
        await this.refreshCapabilities();
      }
    },
    async setToken(token: string, remember: boolean) {
      const normalizedToken = token.trim();
      if (isDesktopRuntime() && window.collectorDesktop) {
        const status = await window.collectorDesktop.setCredential({ token: normalizedToken, remember });
        this.applyCredentialStatus(status);
        this.token = "";
        this.rememberToken = status.remembered;
        configureHttp({ token: "" });
        if (status.hasCredential) {
          localStorage.removeItem(TOKEN_KEY);
        }
        return;
      }
      this.token = normalizedToken;
      this.rememberToken = remember;
      this.hasCredential = Boolean(this.token);
      this.credentialRemembered = Boolean(remember && this.token);
      configureHttp({ token: this.token });
      if (remember && this.token) {
        localStorage.setItem(TOKEN_KEY, this.token);
      } else {
        localStorage.removeItem(TOKEN_KEY);
      }
    },
    async login(token: string, remember: boolean) {
      await this.setToken(token, remember);
      this.currentUser = "admin";
      await this.refreshCapabilities();
    },
    async logout() {
      if (isDesktopRuntime() && window.collectorDesktop) {
        const status = await window.collectorDesktop.clearCredential();
        this.applyCredentialStatus(status);
        this.token = "";
        this.rememberToken = false;
        this.capabilities = null;
        this.capabilitiesError = "";
        useWebSocketStore().setSupported(false);
        useWebSocketStore().disableRealtime();
        configureHttp({ token: "" });
        localStorage.removeItem(TOKEN_KEY);
        return;
      }
      await this.setToken("", false);
      this.capabilities = null;
      this.capabilitiesError = "";
      useWebSocketStore().setSupported(false);
      useWebSocketStore().disableRealtime();
    },
    applyCredentialStatus(status: { hasCredential: boolean; remembered: boolean; storageAvailable: boolean; rememberUnavailable: boolean }) {
      this.hasCredential = Boolean(status.hasCredential);
      this.credentialRemembered = Boolean(status.remembered);
      this.credentialStorageAvailable = Boolean(status.storageAvailable);
      this.credentialRememberUnavailable = Boolean(status.rememberUnavailable);
    }
  }
});
