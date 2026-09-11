import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import { readJsonWithRecovery, writeJsonAtomic, type AtomicWriteFileSystem } from "./desktop-persistence-utils.js";

function tempJsonPath(): string {
  return join(mkdtempSync(join(tmpdir(), "collector-config-")), "collector-desktop-config.json");
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("desktop-persistence-utils", () => {
  it("atomic write 先写同目录临时文件再替换目标 JSON", () => {
    const configPath = tempJsonPath();

    writeJsonAtomic(configPath, { serverUrl: "http://127.0.0.1:9090/collector" });

    expect(JSON.parse(readFileSync(configPath, "utf8"))).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
    expect(readFileSync(configPath, "utf8")).toMatch(/\n$/);
    rmSync(dirname(configPath), { recursive: true, force: true });
  });

  it("atomic write 失败时不破坏原有效配置", () => {
    const configPath = tempJsonPath();
    writeJsonAtomic(configPath, { serverUrl: "http://127.0.0.1:9090/collector" });
    const before = readFileSync(configPath, "utf8");
    const writeError = new Error("simulated write failure");
    const fsOps: AtomicWriteFileSystem = {
      mkdirSync: vi.fn(),
      openSync: vi.fn(() => 100 as unknown as ReturnType<AtomicWriteFileSystem["openSync"]>),
      writeFileSync: vi.fn(() => {
        throw writeError;
      }),
      fsyncSync: vi.fn(),
      closeSync: vi.fn(),
      renameSync: vi.fn(),
      rmSync: vi.fn()
    };

    expect(() => writeJsonAtomic(configPath, { serverUrl: "http://192.168.1.20:9090/collector" }, fsOps)).toThrow("simulated write failure");
    expect(readFileSync(configPath, "utf8")).toBe(before);
    expect(fsOps.renameSync).not.toHaveBeenCalled();
    expect(fsOps.rmSync).toHaveBeenCalledWith(expect.stringContaining(".tmp"), { force: true });
    rmSync(dirname(configPath), { recursive: true, force: true });
  });

  it("invalid/corrupt JSON 被隔离并恢复安全默认值", () => {
    const configPath = tempJsonPath();
    writeFileSync(configPath, "{", "utf8");

    const result = readJsonWithRecovery(configPath, { serverUrl: "http://127.0.0.1:9090/collector" }, (raw) => raw as { serverUrl: string });

    expect(result.value).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
    expect(result.recovery?.recovered).toBe(true);
    expect(result.recovery?.quarantinePath).toBeTruthy();
    expect(existsSync(configPath)).toBe(false);
    rmSync(dirname(configPath), { recursive: true, force: true });
  });

  it("normalize 失败的 legacy dirty config 也不会成为 authoritative config", () => {
    const configPath = tempJsonPath();
    writeFileSync(configPath, JSON.stringify({ serverUrl: "file:///C:/collector" }), "utf8");

    const result = readJsonWithRecovery(configPath, { serverUrl: "http://127.0.0.1:9090/collector" }, () => {
      throw new Error("采集服务地址只允许 HTTP/HTTPS 协议");
    });

    expect(result.value.serverUrl).toBe("http://127.0.0.1:9090/collector");
    expect(result.recovery?.reason).toContain("HTTP/HTTPS");
    expect(existsSync(configPath)).toBe(false);
    rmSync(dirname(configPath), { recursive: true, force: true });
  });
});
