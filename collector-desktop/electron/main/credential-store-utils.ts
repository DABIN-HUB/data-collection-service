import { existsSync, renameSync, rmSync } from "node:fs";

import { readJsonWithRecovery, writeJsonAtomic, type RecoveryInfo } from "./desktop-persistence-utils.js";

export interface SafeStorageLike {
  isEncryptionAvailable(): boolean;
  encryptString(plainText: string): Buffer;
  decryptString(encrypted: Buffer): string;
  getSelectedStorageBackend?: () => "basic_text" | "gnome_libsecret" | "kwallet" | "kwallet5" | "kwallet6" | "unknown";
}

export interface CredentialStatus {
  hasCredential: boolean;
  remembered: boolean;
  storageAvailable: boolean;
  rememberUnavailable: boolean;
  storageBackend?: string;
  recovery?: RecoveryInfo;
}

interface PersistedCredentialFile {
  schemaVersion: 1;
  encryptedToken: string;
  updatedAt: string;
}

const DEFAULT_STATUS: CredentialStatus = {
  hasCredential: false,
  remembered: false,
  storageAvailable: false,
  rememberUnavailable: false
};

export class MainCredentialStore {
  private memoryToken = "";
  private remembered = false;
  private recovery: RecoveryInfo | undefined;

  constructor(
    private readonly credentialPath: string,
    private readonly safeStorage: SafeStorageLike,
    private readonly platform: NodeJS.Platform = process.platform
  ) {}

  initialize(): CredentialStatus {
    this.memoryToken = "";
    this.remembered = false;
    this.recovery = undefined;
    if (!existsSync(this.credentialPath)) {
      return this.getStatus();
    }
    if (!this.isProtectedPersistenceAvailable()) {
      this.recovery = {
        recovered: true,
        reason: "安全存储不可用，已忽略已保存凭据",
        sourcePath: this.credentialPath
      };
      return this.getStatus();
    }
    const result = readJsonWithRecovery<PersistedCredentialFile | null>(this.credentialPath, null, normalizePersistedCredentialFile);
    this.recovery = result.recovery;
    if (!result.value) {
      return this.getStatus();
    }
    try {
      const decrypted = this.safeStorage.decryptString(Buffer.from(result.value.encryptedToken, "base64")).trim();
      if (decrypted) {
        this.memoryToken = decrypted;
        this.remembered = true;
      }
    } catch (error) {
      const quarantinePath = this.quarantinePersistedCredential();
      this.recovery = {
        recovered: true,
        reason: error instanceof Error ? error.message : String(error || "credential decrypt error"),
        sourcePath: this.credentialPath,
        quarantinePath
      };
    }
    return this.getStatus();
  }

  setCredential(token: string, remember: boolean): CredentialStatus {
    const normalizedToken = String(token || "").trim();
    this.memoryToken = normalizedToken;
    this.remembered = false;
    this.recovery = undefined;
    if (!normalizedToken) {
      this.clearPersistedOnly();
      return this.getStatus();
    }
    if (!remember) {
      this.clearPersistedOnly();
      return this.getStatus();
    }
    if (!this.isProtectedPersistenceAvailable()) {
      this.clearPersistedOnly();
      return this.getStatus(true);
    }
    const encryptedToken = this.safeStorage.encryptString(normalizedToken).toString("base64");
    writeJsonAtomic(this.credentialPath, {
      schemaVersion: 1,
      encryptedToken,
      updatedAt: new Date().toISOString()
    } satisfies PersistedCredentialFile);
    this.remembered = true;
    return this.getStatus();
  }

  clearCredential(): CredentialStatus {
    this.memoryToken = "";
    this.remembered = false;
    this.recovery = undefined;
    this.clearPersistedOnly();
    return this.getStatus();
  }

  getToken(): string {
    return this.memoryToken;
  }

  getStatus(rememberUnavailable = false): CredentialStatus {
    return {
      hasCredential: Boolean(this.memoryToken),
      remembered: this.remembered,
      storageAvailable: this.isProtectedPersistenceAvailable(),
      rememberUnavailable,
      storageBackend: this.getStorageBackend(),
      recovery: this.recovery
    };
  }

  private clearPersistedOnly(): void {
    try {
      rmSync(this.credentialPath, { force: true });
    } catch {
      // Clearing credentials is best-effort; memory state is still authoritative for the current request boundary.
    }
  }

  private quarantinePersistedCredential(): string | undefined {
    if (!existsSync(this.credentialPath)) {
      return undefined;
    }
    const quarantinePath = `${this.credentialPath}.corrupt-${new Date().toISOString().replace(/[:.]/g, "-")}`;
    try {
      renameSync(this.credentialPath, quarantinePath);
      return quarantinePath;
    } catch {
      return undefined;
    }
  }

  private isProtectedPersistenceAvailable(): boolean {
    if (!this.safeStorage.isEncryptionAvailable()) {
      return false;
    }
    return !(this.platform === "linux" && this.getStorageBackend() === "basic_text");
  }

  private getStorageBackend(): string | undefined {
    if (typeof this.safeStorage.getSelectedStorageBackend !== "function") {
      return undefined;
    }
    try {
      return this.safeStorage.getSelectedStorageBackend();
    } catch {
      return undefined;
    }
  }
}

export function normalizePersistedCredentialFile(raw: unknown): PersistedCredentialFile | null {
  if (!raw || typeof raw !== "object") {
    throw new Error("凭据文件格式无效");
  }
  const value = raw as Partial<PersistedCredentialFile>;
  if (value.schemaVersion !== 1 || typeof value.encryptedToken !== "string" || !value.encryptedToken.trim()) {
    throw new Error("凭据文件格式无效");
  }
  Buffer.from(value.encryptedToken, "base64");
  return {
    schemaVersion: 1,
    encryptedToken: value.encryptedToken,
    updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : ""
  };
}

export function createEmptyCredentialStatus(recovery?: RecoveryInfo): CredentialStatus {
  return {
    ...DEFAULT_STATUS,
    recovery
  };
}
