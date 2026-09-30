export type RealtimeRequestMode = "all" | "device" | "single" | "panel";

export interface RealtimeRequestContext {
  mode: RealtimeRequestMode;
  deviceId: string;
  pointId?: string;
}

export interface RealtimeRequestTicket extends RealtimeRequestContext {
  generation: number;
}

export interface LatestRealtimeRequestOwner {
  begin(context: RealtimeRequestContext): RealtimeRequestTicket;
  invalidate(): void;
  isLatest(ticket: RealtimeRequestTicket): boolean;
  isCurrent(ticket: RealtimeRequestTicket, liveContext: RealtimeRequestContext): boolean;
}

export function createLatestRealtimeRequestOwner(): LatestRealtimeRequestOwner {
  let currentGeneration = 0;
  let currentTicket: RealtimeRequestTicket | null = null;

  function isLatest(ticket: RealtimeRequestTicket): boolean {
    if (!currentTicket) {
      return false;
    }
    return normalizeTicket(ticket).generation === normalizeTicket(currentTicket).generation;
  }

  return {
    begin(context) {
      currentTicket = {
        ...normalizeContext(context),
        generation: ++currentGeneration
      };
      return currentTicket;
    },
    invalidate() {
      currentGeneration += 1;
      currentTicket = null;
    },
    isLatest,
    isCurrent(ticket, liveContext) {
      return isLatest(ticket) && isSameRealtimeRequestContext(ticket, liveContext);
    }
  };
}

export function shouldDisableRealtimeSubmit(
  loading: boolean,
  pendingContext: RealtimeRequestContext | null | undefined,
  liveContext: RealtimeRequestContext
): boolean {
  return loading && isSameRealtimeRequestContext(pendingContext, liveContext);
}

function isSameRealtimeRequestContext(
  left: RealtimeRequestContext | RealtimeRequestTicket | null | undefined,
  right: RealtimeRequestContext | RealtimeRequestTicket | null | undefined
): boolean {
  if (!left || !right) {
    return false;
  }
  const normalizedLeft = normalizeContext(left);
  const normalizedRight = normalizeContext(right);
  return normalizedLeft.mode === normalizedRight.mode
    && normalizedLeft.deviceId === normalizedRight.deviceId
    && normalizedLeft.pointId === normalizedRight.pointId;
}

function normalizeContext(context: RealtimeRequestContext): RealtimeRequestContext {
  return {
    mode: context.mode,
    deviceId: normalizeText(context.deviceId),
    pointId: normalizeText(context.pointId)
  };
}

function normalizeTicket(ticket: RealtimeRequestTicket): RealtimeRequestTicket {
  return {
    generation: ticket.generation,
    mode: ticket.mode,
    deviceId: normalizeText(ticket.deviceId),
    pointId: normalizeText(ticket.pointId)
  };
}

function normalizeText(value: string | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}
