import { normalizeServerConfigCandidate, type ServerConfig } from "./main-utils.js";

export interface ServerConfigChangeDependencies {
  readCurrent: () => ServerConfig;
  write: (config: ServerConfig) => ServerConfig;
  confirmChange: (current: ServerConfig, candidate: ServerConfig) => Promise<boolean>;
}

let confirmationInFlight = false;

export function isServerConfigChangeConfirmationInFlight(): boolean {
  return confirmationInFlight;
}

export function resetServerConfigChangeConfirmationForTest(): void {
  confirmationInFlight = false;
}

export async function applyServerConfigChange(config: Partial<ServerConfig>, dependencies: ServerConfigChangeDependencies): Promise<ServerConfig> {
  const current = dependencies.readCurrent();
  const candidate = normalizeServerConfigCandidate(config);

  if (candidate.serverUrl === current.serverUrl) {
    return current;
  }
  if (confirmationInFlight) {
    throw new Error("已有采集服务地址切换确认正在进行，请完成后再重试");
  }

  confirmationInFlight = true;
  try {
    const approved = await dependencies.confirmChange(current, candidate);
    if (!approved) {
      return current;
    }
    return dependencies.write(candidate);
  } finally {
    confirmationInFlight = false;
  }
}
