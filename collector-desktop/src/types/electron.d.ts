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

declare global {
  interface Window {
    collectorDesktop?: {
      getAppInfo: () => Promise<CollectorDesktopAppInfo>;
      getServerConfig: () => Promise<CollectorDesktopServerConfig>;
      setServerConfig: (config: CollectorDesktopServerConfig) => Promise<CollectorDesktopServerConfig>;
      openExternal: (url: string) => Promise<boolean>;
      onNavigate: (handler: (path: string) => void) => () => void;
    };
  }
}

export {};
