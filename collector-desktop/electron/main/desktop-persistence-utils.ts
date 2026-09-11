import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export interface AtomicWriteFileSystem {
  mkdirSync: typeof mkdirSync;
  openSync: typeof openSync;
  writeFileSync: typeof writeFileSync;
  fsyncSync: typeof fsyncSync;
  closeSync: typeof closeSync;
  renameSync: typeof renameSync;
  rmSync: typeof rmSync;
}

export interface RecoveryInfo {
  recovered: boolean;
  reason: string;
  sourcePath: string;
  quarantinePath?: string;
}

export interface JsonReadResult<T> {
  value: T;
  recovery?: RecoveryInfo;
}

export function readJsonWithRecovery<T>(filePath: string, defaultValue: T, normalize: (raw: unknown) => T): JsonReadResult<T> {
  if (!existsSync(filePath)) {
    return { value: defaultValue };
  }
  try {
    const parsed = JSON.parse(readFileSync(filePath, "utf8")) as unknown;
    return { value: normalize(parsed) };
  } catch (error) {
    const recovery = quarantineCorruptFile(filePath, error);
    return { value: defaultValue, recovery };
  }
}

const NODE_ATOMIC_FS: AtomicWriteFileSystem = {
  mkdirSync,
  openSync,
  writeFileSync,
  fsyncSync,
  closeSync,
  renameSync,
  rmSync
};

export function writeJsonAtomic(filePath: string, value: unknown, fsOps: AtomicWriteFileSystem = NODE_ATOMIC_FS): void {
  fsOps.mkdirSync(dirname(filePath), { recursive: true });
  const payload = `${JSON.stringify(value, null, 2)}\n`;
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  let fd: number | undefined;
  try {
    fd = fsOps.openSync(tempPath, "w");
    fsOps.writeFileSync(fd, payload, "utf8");
    fsOps.fsyncSync(fd);
    fsOps.closeSync(fd);
    fd = undefined;
    fsOps.renameSync(tempPath, filePath);
    fsyncParentDirectory(filePath);
  } catch (error) {
    if (fd !== undefined) {
      try {
        fsOps.closeSync(fd);
      } catch {
        // ignore close errors while preserving the original failure
      }
    }
    try {
      fsOps.rmSync(tempPath, { force: true });
    } catch {
      // ignore temp cleanup errors while preserving the original failure
    }
    throw error;
  }
}

function quarantineCorruptFile(filePath: string, error: unknown): RecoveryInfo {
  const quarantinePath = `${filePath}.corrupt-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  try {
    renameSync(filePath, quarantinePath);
    return {
      recovered: true,
      reason: error instanceof Error ? error.message : String(error || "unknown config read error"),
      sourcePath: filePath,
      quarantinePath
    };
  } catch (renameError) {
    return {
      recovered: true,
      reason: renameError instanceof Error ? renameError.message : String(renameError || "config quarantine failed"),
      sourcePath: filePath
    };
  }
}

function fsyncParentDirectory(filePath: string): void {
  try {
    const dirFd = openSync(dirname(filePath), "r");
    try {
      fsyncSync(dirFd);
    } finally {
      closeSync(dirFd);
    }
  } catch {
    // Directory fsync is not consistently supported on Windows; file fsync + same-dir rename is the practical boundary here.
  }
}
