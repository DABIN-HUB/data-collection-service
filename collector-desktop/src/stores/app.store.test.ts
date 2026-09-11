import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { DEFAULT_SERVER_URL, configureHttp, getHttpConfig } from "@/api/http";
import { useAppStore } from "./app.store";

const globalWindow = globalThis as unknown as { window?: Window };
const originalWindow = globalWindow.window;

class MemoryStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.has(key) ? this.values.get(key) ?? null : null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  clear(): void {
    this.values.clear();
  }
}

const storage = new MemoryStorage();

type CredentialStatus = NonNullable<Window["collectorDesktop"]> extends { getCredentialStatus: () => Promise<infer Status> } ? Status : never;

function credentialStatus(overrides: Partial<CredentialStatus> = {}): CredentialStatus {
  return { hasCredential: false, remembered: false, storageAvailable: true, rememberUnavailable: false, ...overrides };
}

function installLocalStorage(): void {
  Object.defineProperty(globalThis, "localStorage", {
    value: storage,
    configurable: true
  });
}

function installDesktopBridge(config: { serverUrl: string }, setServerConfig = vi.fn().mockResolvedValue(config), credential = {
  getCredentialStatus: vi.fn().mockResolvedValue(credentialStatus()),
  setCredential: vi.fn().mockResolvedValue(credentialStatus({ hasCredential: true, remembered: true })),
  clearCredential: vi.fn().mockResolvedValue(credentialStatus())
}): ReturnType<typeof vi.fn> {
  const bridge = {
    getAppInfo: vi.fn().mockResolvedValue({ name: "数据采集工作台", version: "0.1.0", platform: "win32", backendManaged: false }),
    getServerConfig: vi.fn().mockResolvedValue(config),
    setServerConfig,
    getCredentialStatus: credential.getCredentialStatus,
    setCredential: credential.setCredential,
    clearCredential: credential.clearCredential,
    request: vi.fn(),
    openExternal: vi.fn(),
    onNavigate: vi.fn().mockReturnValue(() => undefined)
  };
  globalWindow.window = { collectorDesktop: bridge } as unknown as Window;
  return setServerConfig;
}

beforeEach(() => {
  setActivePinia(createPinia());
  storage.clear();
  installLocalStorage();
  globalWindow.window = originalWindow;
  configureHttp({ serverUrl: DEFAULT_SERVER_URL, token: "" });
  vi.restoreAllMocks();
});

describe("app.store Electron serverUrl source-of-truth", () => {
  it("Electron 初始化使用 Main server config，不被 stale localStorage 覆盖", async () => {
    storage.setItem("collector-desktop-server-url", "http://127.0.0.1:19090/collector");
    installDesktopBridge({ serverUrl: "http://192.168.1.20:9090/collector" });
    const store = useAppStore();

    await store.initialize();

    expect(store.serverUrl).toBe("http://192.168.1.20:9090/collector");
    expect(getHttpConfig().serverUrl).toBe("http://192.168.1.20:9090/collector");
  });

  it("Electron updateServerUrl 在 Main 取消时不提前提交 renderer/localStorage/configureHttp", async () => {
    const setServerConfig = vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL });
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, setServerConfig);
    const store = useAppStore();
    await store.initialize();
    storage.setItem("collector-desktop-server-url", DEFAULT_SERVER_URL);

    await store.updateServerUrl("http://127.0.0.1:19090/collector");

    expect(setServerConfig).toHaveBeenCalledWith({ serverUrl: "http://127.0.0.1:19090/collector" });
    expect(store.serverUrl).toBe(DEFAULT_SERVER_URL);
    expect(storage.getItem("collector-desktop-server-url")).toBe(DEFAULT_SERVER_URL);
    expect(getHttpConfig().serverUrl).toBe(DEFAULT_SERVER_URL);
  });

  it("Electron updateServerUrl 在 Main reject 时不提前提交 renderer/localStorage/configureHttp", async () => {
    const setServerConfig = vi.fn().mockRejectedValue(new Error("用户取消或地址无效"));
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, setServerConfig);
    const store = useAppStore();
    await store.initialize();
    storage.setItem("collector-desktop-server-url", DEFAULT_SERVER_URL);

    await expect(store.updateServerUrl("http://127.0.0.1:19090/collector")).rejects.toThrow("用户取消或地址无效");

    expect(store.serverUrl).toBe(DEFAULT_SERVER_URL);
    expect(storage.getItem("collector-desktop-server-url")).toBe(DEFAULT_SERVER_URL);
    expect(getHttpConfig().serverUrl).toBe(DEFAULT_SERVER_URL);
  });

  it("Browser/Web 模式保留 localStorage server URL 行为", async () => {
    globalWindow.window = {} as Window;
    storage.setItem("collector-desktop-server-url", "http://192.168.1.30:9090/collector");
    const store = useAppStore();

    await store.initialize();

    expect(store.serverUrl).toBe("http://192.168.1.30:9090/collector");
    expect(getHttpConfig().serverUrl).toBe("http://192.168.1.30:9090/collector");
  });
});

describe("app.store Electron credential boundary", () => {
  it("Electron 首次启动迁移 legacy localStorage token，Main 成功接管后才删除", async () => {
    const legacyToken = "[REDACTED]";
    storage.setItem("collector-desktop-token", legacyToken);
    const credential = {
      getCredentialStatus: vi.fn().mockResolvedValue(credentialStatus()),
      setCredential: vi.fn().mockResolvedValue(credentialStatus({ hasCredential: true, remembered: true })),
      clearCredential: vi.fn().mockResolvedValue(credentialStatus())
    };
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }), credential);
    const store = useAppStore();

    await store.initialize();

    expect(credential.setCredential).toHaveBeenCalledWith({ token: legacyToken, remember: true });
    expect(storage.getItem("collector-desktop-token")).toBeNull();
    expect(store.token).toBe("");
    expect(getHttpConfig().token).toBe("");
    expect(store.hasCredential).toBe(true);
    expect(store.credentialRemembered).toBe(true);
  });

  it("Electron legacy migration 失败时不提前删除旧 token", async () => {
    const legacyToken = "[REDACTED]";
    storage.setItem("collector-desktop-token", legacyToken);
    const credential = {
      getCredentialStatus: vi.fn().mockResolvedValue(credentialStatus()),
      setCredential: vi.fn().mockRejectedValue(new Error("safe storage unavailable")),
      clearCredential: vi.fn().mockResolvedValue(credentialStatus())
    };
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }), credential);
    const store = useAppStore();

    await expect(store.initialize()).rejects.toThrow("safe storage unavailable");

    expect(storage.getItem("collector-desktop-token")).toBe(legacyToken);
    expect(store.token).toBe("");
    expect(getHttpConfig().token).toBe("");
  });

  it("Electron login 把 token 交给 Main 后不在 Pinia/localStorage/configureHttp 长期保存", async () => {
    const credential = {
      getCredentialStatus: vi.fn().mockResolvedValue(credentialStatus()),
      setCredential: vi.fn().mockResolvedValue(credentialStatus({ hasCredential: true, remembered: false })),
      clearCredential: vi.fn().mockResolvedValue(credentialStatus())
    };
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }), credential);
    const store = useAppStore();
    await store.initialize();

    await store.login("[REDACTED]", false);

    expect(credential.setCredential).toHaveBeenLastCalledWith({ token: "[REDACTED]", remember: false });
    expect(store.token).toBe("");
    expect(store.rememberToken).toBe(false);
    expect(storage.getItem("collector-desktop-token")).toBeNull();
    expect(getHttpConfig().token).toBe("");
    expect(store.hasCredential).toBe(true);
  });

  it("Electron logout 清除 Main credential 且后续 renderer transport 不保留旧 token", async () => {
    const credential = {
      getCredentialStatus: vi.fn().mockResolvedValue(credentialStatus({ hasCredential: true, remembered: true })),
      setCredential: vi.fn().mockResolvedValue(credentialStatus({ hasCredential: true, remembered: true })),
      clearCredential: vi.fn().mockResolvedValue(credentialStatus())
    };
    installDesktopBridge({ serverUrl: DEFAULT_SERVER_URL }, vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }), credential);
    const store = useAppStore();
    await store.initialize();

    await store.logout();

    expect(credential.clearCredential).toHaveBeenCalledOnce();
    expect(store.hasCredential).toBe(false);
    expect(store.token).toBe("");
    expect(storage.getItem("collector-desktop-token")).toBeNull();
    expect(getHttpConfig().token).toBe("");
  });

  it("Browser/Web 模式保留原 localStorage token 行为", async () => {
    globalWindow.window = {} as Window;
    storage.setItem("collector-desktop-token", "[REDACTED]");
    const store = useAppStore();

    await store.initialize();

    expect(store.token).toBe("[REDACTED]");
    expect(store.rememberToken).toBe(true);
    expect(getHttpConfig().token).toBe("[REDACTED]");
  });
});
