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
  isCurrent(ticket: RealtimeRequestTicket, liveContext: RealtimeRequestContext): boolean;
}

export function createLatestRealtimeRequestOwner(): LatestRealtimeRequestOwner {
  let currentGeneration = 0;
  let currentTicket: RealtimeRequestTicket | null = null;

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
    isCurrent(ticket, liveContext) {
      if (!currentTicket) {
        return false;
      }
      const normalizedTicket = normalizeTicket(ticket);
      const normalizedCurrent = normalizeTicket(currentTicket);
      const normalizedLiveContext = normalizeContext(liveContext);
      return normalizedTicket.generation === normalizedCurrent.generation
        && normalizedTicket.mode === normalizedCurrent.mode
        && normalizedTicket.deviceId === normalizedCurrent.deviceId
        && normalizedTicket.pointId === normalizedCurrent.pointId
        && normalizedTicket.mode === normalizedLiveContext.mode
        && normalizedTicket.deviceId === normalizedLiveContext.deviceId
        && normalizedTicket.pointId === normalizedLiveContext.pointId;
    }
  };
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
