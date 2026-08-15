export interface CollectorDesktopAppInfo {
  name: string;
  version: string;
  platform: string;
  configPath?: string;
  backendManaged?: boolean;
}

export interface CollectorDesktopServerConfig {
  serverUrl: string;
}

export interface CollectorDesktopProxyRequest {
  serverUrl: string;
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: unknown;
  headers?: Record<string, string>;
  token?: string;
  timeoutMs?: number;
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
      request: (request: CollectorDesktopProxyRequest) => Promise<CollectorDesktopProxyResponse>;
      openExternal: (url: string) => Promise<boolean>;
      onNavigate: (handler: (path: string) => void) => () => void;
    };
  }
}

export {};
