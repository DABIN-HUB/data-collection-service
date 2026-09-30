import { afterEach, describe, expect, it, vi } from "vitest";

import { applyServerConfigChange, resetServerConfigChangeConfirmationForTest } from "./server-config-change-utils.js";
import type { ServerConfig } from "./main-utils.js";

function dependencies(initialUrl = "http://127.0.0.1:9090/collector", approve = false) {
  let current: ServerConfig = { serverUrl: initialUrl };
  const confirmChange = vi.fn().mockResolvedValue(approve);
  const write = vi.fn((config: ServerConfig) => {
    current = config;
    return current;
  });
  return {
    readCurrent: () => current,
    write,
    confirmChange,
    get current() {
      return current;
    }
  };
}

afterEach(() => {
  resetServerConfigChangeConfirmationForTest();
  vi.restoreAllMocks();
});

describe("server-config-change-utils", () => {
  it("current URL 等于 candidate 时不弹确认且直接返回 current config", async () => {
    const deps = dependencies();

    await expect(applyServerConfigChange({ serverUrl: "http://127.0.0.1:9090/collector/" }, deps)).resolves.toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });

    expect(deps.confirmChange).not.toHaveBeenCalled();
    expect(deps.write).not.toHaveBeenCalled();
  });

  it("native confirmation 取消时不写入 Main config", async () => {
    const deps = dependencies("http://127.0.0.1:9090/collector", false);

    await expect(applyServerConfigChange({ serverUrl: "http://127.0.0.1:19090/collector" }, deps)).resolves.toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });

    expect(deps.confirmChange).toHaveBeenCalledWith({ serverUrl: "http://127.0.0.1:9090/collector" }, { serverUrl: "http://127.0.0.1:19090/collector" });
    expect(deps.write).not.toHaveBeenCalled();
    expect(deps.current.serverUrl).toBe("http://127.0.0.1:9090/collector");
  });

  it("native confirmation 确认后才写入 LAN/remote collector URL", async () => {
    const deps = dependencies("http://127.0.0.1:9090/collector", true);

    await expect(applyServerConfigChange({ serverUrl: "http://192.168.1.20:9090/collector" }, deps)).resolves.toEqual({ serverUrl: "http://192.168.1.20:9090/collector" });

    expect(deps.confirmChange).toHaveBeenCalledOnce();
    expect(deps.write).toHaveBeenCalledWith({ serverUrl: "http://192.168.1.20:9090/collector" });
  });

  it("拒绝通过 setServerConfig 静默切换 localhost/LAN destination", async () => {
    const deps = dependencies("http://trusted.example/collector", false);

    await expect(applyServerConfigChange({ serverUrl: "http://127.0.0.1:19091/collector" }, deps)).resolves.toEqual({ serverUrl: "http://trusted.example/collector" });

    expect(deps.write).not.toHaveBeenCalled();
    expect(deps.current.serverUrl).toBe("http://trusted.example/collector");
  });

  it("拒绝非法 backend candidate URL", async () => {
    const deps = dependencies();

    await expect(applyServerConfigChange({ serverUrl: "file:///C:/collector" }, deps)).rejects.toThrow("HTTP/HTTPS");
    await expect(applyServerConfigChange({ serverUrl: "javascript:alert(1)" }, deps)).rejects.toThrow("HTTP/HTTPS");
    await expect(applyServerConfigChange({ serverUrl: "ftp://127.0.0.1/collector" }, deps)).rejects.toThrow("HTTP/HTTPS");
    await expect(applyServerConfigChange({ serverUrl: "http://user:password@host/collector" }, deps)).rejects.toThrow("用户名或密码");
    expect(deps.confirmChange).not.toHaveBeenCalled();
    expect(deps.write).not.toHaveBeenCalled();
  });

  it("同一时间只允许一个 server config native confirmation", async () => {
    let resolveConfirm: (approved: boolean) => void = () => undefined;
    const deps = dependencies();
    deps.confirmChange.mockImplementation(() => new Promise<boolean>((resolve) => {
      resolveConfirm = resolve;
    }));

    const first = applyServerConfigChange({ serverUrl: "http://127.0.0.1:19090/collector" }, deps);
    await Promise.resolve();

    await expect(applyServerConfigChange({ serverUrl: "http://127.0.0.1:19091/collector" }, deps)).rejects.toThrow("确认正在进行");

    resolveConfirm?.(false);
    await expect(first).resolves.toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
    expect(deps.write).not.toHaveBeenCalled();
  });
});
