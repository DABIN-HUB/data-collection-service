export interface ActionExecutionTarget {
  target: string;
  action: string;
  deviceId?: string;
  pointRef?: string;
  payloadSummary?: string;
  submittedAt: number;
}

export interface ActionExecutionView<T = unknown> {
  target: ActionExecutionTarget;
  result?: T;
  error?: string;
  completedAt: number;
}

export function buildActionExecutionTarget(input: Omit<ActionExecutionTarget, "submittedAt"> & { submittedAt?: number }): ActionExecutionTarget {
  return {
    target: normalizeText(input.target || input.deviceId),
    action: normalizeText(input.action),
    deviceId: normalizeOptionalText(input.deviceId),
    pointRef: normalizeOptionalText(input.pointRef),
    payloadSummary: normalizeOptionalText(input.payloadSummary),
    submittedAt: normalizeTime(input.submittedAt)
  };
}

export function buildActionExecutionView<T>(
  target: ActionExecutionTarget,
  result: T | undefined,
  error?: string,
  completedAt = Date.now()
): ActionExecutionView<T> {
  return {
    target: { ...target },
    result,
    error: normalizeOptionalText(error),
    completedAt: normalizeTime(completedAt)
  };
}

export function formatActionTime(value: unknown): string {
  const time = typeof value === "number" && Number.isFinite(value) ? value : Number(value);
  if (!Number.isFinite(time)) {
    return "-";
  }
  return new Date(time).toLocaleString();
}

export function summarizePayload(value: unknown, maxLength = 120): string {
  let text: string;
  if (typeof value === "string") {
    text = value;
  } else {
    try {
      text = JSON.stringify(value);
    } catch {
      text = String(value ?? "");
    }
  }
  const compact = text.replace(/\s+/g, " ").trim();
  if (compact.length <= maxLength) {
    return compact;
  }
  return `${compact.slice(0, Math.max(0, maxLength - 1))}…`;
}

export function safeActionErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  if (typeof error === "string" && error.trim()) {
    return error.trim();
  }
  return fallback;
}

function normalizeText(value: unknown): string {
  return String(value || "").trim();
}

function normalizeOptionalText(value: unknown): string | undefined {
  const text = normalizeText(value);
  return text || undefined;
}

function normalizeTime(value: unknown): number {
  const time = typeof value === "number" && Number.isFinite(value) ? value : Number(value);
  return Number.isFinite(time) ? time : Date.now();
}
