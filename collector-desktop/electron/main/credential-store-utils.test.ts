import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import { MainCredentialStore, normalizePersistedCredentialFile, type SafeStorageLike } from "./credential-store-utils.js";

const PLAINTEXT = "[REDACTED]";

function tempCredentialPath(): string {
  return join(mkdtempSync(join(tmpdir(), "collector-credential-")), "collector-desktop-credentials.json");
}

function fakeSafeStorage(available = true, decryptFails = false, backend: ReturnType<Required<SafeStorageLike>["getSelectedStorageBackend"]> = "unknown"): SafeStorageLike {
  return {
    isEncryptionAvailable: vi.fn(() => available),
    encryptString: vi.fn((plainText: string) => Buffer.from(`protected:${Buffer.from(plainText).toString("base64")}`)),
    decryptString: vi.fn((encrypted: Buffer) => {
      if (decryptFails) {
        throw new Error("decrypt failed");
      }
      const value = encrypted.toString();
      if (!value.startsWith("protected:")) {
        throw new Error("invalid protected payload");
      }
      return Buffer.from(value.slice("protected:".length), "base64").toString("utf8");
    }),
    getSelectedStorageBackend: vi.fn(() => backend)
  };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("MainCredentialStore", () => {
  it("remember=false 只保存在 Main memory 且不写 credential 文件", () => {
    const credentialPath = tempCredentialPath();
    const store = new MainCredentialStore(credentialPath, fakeSafeStorage());

    const status = store.setCredential(PLAINTEXT, false);

    expect(status).toMatchObject({ hasCredential: true, remembered: false, storageAvailable: true, rememberUnavailable: false });
    expect(store.getToken()).toBe(PLAINTEXT);
    expect(existsSync(credentialPath)).toBe(false);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("remember=true 使用 safeStorage ciphertext 持久化并可重启恢复到 Main memory", () => {
    const credentialPath = tempCredentialPath();
    const safeStorage = fakeSafeStorage();
    const store = new MainCredentialStore(credentialPath, safeStorage);

    const status = store.setCredential(PLAINTEXT, true);
    const persisted = readFileSync(credentialPath, "utf8");
    const restarted = new MainCredentialStore(credentialPath, safeStorage);

    expect(status).toMatchObject({ hasCredential: true, remembered: true, storageAvailable: true });
    expect(persisted).toContain("encryptedToken");
    expect(persisted).not.toContain(PLAINTEXT);
    expect(restarted.initialize()).toMatchObject({ hasCredential: true, remembered: true });
    expect(restarted.getToken()).toBe(PLAINTEXT);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("safeStorage unavailable 时禁止 plaintext fallback，只保留 Main memory", () => {
    const credentialPath = tempCredentialPath();
    const store = new MainCredentialStore(credentialPath, fakeSafeStorage(false));

    const status = store.setCredential(PLAINTEXT, true);

    expect(status).toMatchObject({ hasCredential: true, remembered: false, storageAvailable: false, rememberUnavailable: true });
    expect(store.getToken()).toBe(PLAINTEXT);
    expect(existsSync(credentialPath)).toBe(false);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("Linux basic_text backend 不能被视为安全持久化", () => {
    const credentialPath = tempCredentialPath();
    const store = new MainCredentialStore(credentialPath, fakeSafeStorage(true, false, "basic_text"), "linux");

    const status = store.setCredential(PLAINTEXT, true);

    expect(status).toMatchObject({ hasCredential: true, remembered: false, storageAvailable: false, rememberUnavailable: true, storageBackend: "basic_text" });
    expect(existsSync(credentialPath)).toBe(false);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("corrupt encrypted credential 不 crash、不返回垃圾 credential，并隔离文件", () => {
    const credentialPath = tempCredentialPath();
    writeFileSync(credentialPath, JSON.stringify({ schemaVersion: 1, encryptedToken: Buffer.from("bad").toString("base64") }), "utf8");
    const store = new MainCredentialStore(credentialPath, fakeSafeStorage(true, true));

    const status = store.initialize();

    expect(status.hasCredential).toBe(false);
    expect(status.remembered).toBe(false);
    expect(status.recovery?.recovered).toBe(true);
    expect(status.recovery?.quarantinePath).toBeTruthy();
    expect(existsSync(credentialPath)).toBe(false);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("clear 后 Main memory 与 persisted credential 均清空", () => {
    const credentialPath = tempCredentialPath();
    const store = new MainCredentialStore(credentialPath, fakeSafeStorage());
    store.setCredential(PLAINTEXT, true);

    const status = store.clearCredential();

    expect(status.hasCredential).toBe(false);
    expect(store.getToken()).toBe("");
    expect(existsSync(credentialPath)).toBe(false);
    rmSync(join(credentialPath, ".."), { recursive: true, force: true });
  });

  it("拒绝格式错误的 credential 文件", () => {
    expect(() => normalizePersistedCredentialFile({ schemaVersion: 1, encryptedToken: "" })).toThrow("凭据文件格式无效");
  });
});
