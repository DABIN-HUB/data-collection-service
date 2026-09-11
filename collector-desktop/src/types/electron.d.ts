export interface CollectorDesktopAppInfo {
  name: string;
  version: string;
  platform: string;
  configPath?: string;
  backendManaged?: boolean;
  configRecovery?: CollectorDesktopRecoveryInfo;
}

export interface CollectorDesktopServerConfig {
  serverUrl: string;
}

export interface CollectorDesktopProxyRequest {
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: unknown;
  headers?: Record<string, string>;
  timeoutMs?: number;
}

export interface CollectorDesktopRecoveryInfo {
  recovered: boolean;
  reason: string;
  sourcePath: string;
  quarantinePath?: string;
}

export interface CollectorDesktopCredentialStatus {
  hasCredential: boolean;
  remembered: boolean;
  storageAvailable: boolean;
  rememberUnavailable: boolean;
  storageBackend?: string;
  recovery?: CollectorDesktopRecoveryInfo;
}

export interface CollectorDesktopProxyResponse {
  status: number;
  statusText: string;
  headers: Record<string, string>;
  body: unknown;
}

declare global {
  interface Window {
    collectorDesktop?: {
      getAppInfo: () => Promise<CollectorDesktopAppInfo>;
      getServerConfig: () => Promise<CollectorDesktopServerConfig>;
      setServerConfig: (config: CollectorDesktopServerConfig) => Promise<CollectorDesktopServerConfig>;
      getCredentialStatus: () => Promise<CollectorDesktopCredentialStatus>;
      setCredential: (credential: { token: string; remember: boolean }) => Promise<CollectorDesktopCredentialStatus>;
      clearCredential: () => Promise<CollectorDesktopCredentialStatus>;
      request: (request: CollectorDesktopProxyRequest) => Promise<CollectorDesktopProxyResponse>;
      openExternal: (url: string) => Promise<boolean>;
      onNavigate: (handler: (path: string) => void) => () => void;
    };
  }
}

export {};
