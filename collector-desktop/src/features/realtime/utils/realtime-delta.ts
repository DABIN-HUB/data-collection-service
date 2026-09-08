import type {
  CompactAllDeviceRealtimeDataResponse,
  CompactDeviceRealtimeDataResponse,
  CompactRealtimeDeltaResponse,
  RealtimePointRow,
  RealtimeSnapshotCursor
} from "@/types/monitor";

import { extractCompactRealtimeRows } from "./realtime-compact-utils";
import type { RealtimeRequestContext } from "./realtime-request-lifecycle";

export type RealtimeLoadSource = "init" | "manual" | "device-change" | "timer";

export const REALTIME_SAFETY_FULL_DELTA_CYCLES = 12;

export interface RealtimeDeltaState {
  cursor: RealtimeSnapshotCursor | null;
  successfulDeltaCycles: number;
  rowIdentityIndex: Map<string, number>;
}

export interface FullRealtimeApplyResult {
  rows: RealtimePointRow[];
  cursor: RealtimeSnapshotCursor | null;
  rowIdentityIndex: Map<string, number>;
  successfulDeltaCycles: number;
}

export interface DeltaRealtimeApplyResult {
  needsFullResync: boolean;
  resetReason?: string;
  cursor: RealtimeSnapshotCursor | null;
  successfulDeltaCycles: number;
}

export function emptyRealtimeDeltaState(): RealtimeDeltaState {
  return {
    cursor: null,
    successfulDeltaCycles: 0,
    rowIdentityIndex: new Map()
  };
}

export function shouldRequestFullRealtimeSnapshot(source: RealtimeLoadSource, state: Pick<RealtimeDeltaState, "cursor" | "successfulDeltaCycles">): boolean {
  if (source !== "timer") {
    return true;
  }
  if (!state.cursor) {
    return true;
  }
  return state.successfulDeltaCycles >= REALTIME_SAFETY_FULL_DELTA_CYCLES;
}

export function applyFullRealtimeSnapshot(
  response: CompactAllDeviceRealtimeDataResponse | CompactDeviceRealtimeDataResponse,
  context: Pick<RealtimeRequestContext, "deviceId">
): FullRealtimeApplyResult {
  const rows = extractCompactRealtimeRows(response);
  return {
    rows,
    cursor: cursorFromSnapshotResponse(response),
    rowIdentityIndex: buildRealtimeRowIdentityIndex(rows, context.deviceId),
    successfulDeltaCycles: 0
  };
}

export function applyRealtimeDelta(
  baseRows: RealtimePointRow[],
  state: RealtimeDeltaState,
  response: CompactRealtimeDeltaResponse,
  context: Pick<RealtimeRequestContext, "deviceId">
): DeltaRealtimeApplyResult {
  if (response.resetRequired) {
    return {
      needsFullResync: true,
      resetReason: response.resetReason,
      cursor: state.cursor,
      successfulDeltaCycles: state.successfulDeltaCycles
    };
  }

  const changedRows = extractCompactRealtimeRows(response);
  const replacements: Array<{ rowIndex: number; changedRow: RealtimePointRow }> = [];
  for (const changedRow of changedRows) {
    const identity = buildRealtimeRowIdentity(changedRow, context.deviceId);
    const rowIndex = state.rowIdentityIndex.get(identity);
    if (rowIndex === undefined) {
      return {
        needsFullResync: true,
        resetReason: "ROW_IDENTITY_MISMATCH",
        cursor: state.cursor,
        successfulDeltaCycles: state.successfulDeltaCycles
      };
    }
    replacements.push({ rowIndex, changedRow });
  }

  for (const replacement of replacements) {
    baseRows[replacement.rowIndex] = replacement.changedRow;
  }

  return {
    needsFullResync: false,
    cursor: cursorFromDeltaResponse(response) || state.cursor,
    successfulDeltaCycles: state.successfulDeltaCycles + 1
  };
}

export function resetRealtimeDeltaState(state: RealtimeDeltaState): void {
  state.cursor = null;
  state.successfulDeltaCycles = 0;
  state.rowIdentityIndex = new Map();
}

export function buildRealtimeRowIdentityIndex(rows: RealtimePointRow[], fallbackDeviceId = ""): Map<string, number> {
  const index = new Map<string, number>();
  rows.forEach((row, rowIndex) => {
    index.set(buildRealtimeRowIdentity(row, fallbackDeviceId), rowIndex);
  });
  return index;
}

export function buildRealtimeRowIdentity(row: Pick<RealtimePointRow, "deviceId" | "pointId" | "pointCode" | "address">, fallbackDeviceId = ""): string {
  const deviceId = String(row.deviceId || fallbackDeviceId || "");
  const pointIdentity = String(row.pointId || row.pointCode || row.address || "");
  return `${deviceId}\u0000${pointIdentity}`;
}

function cursorFromSnapshotResponse(response: CompactAllDeviceRealtimeDataResponse | CompactDeviceRealtimeDataResponse): RealtimeSnapshotCursor | null {
  if (typeof response.snapshotId !== "string" || typeof response.configEpoch !== "number" || typeof response.revision !== "number") {
    return null;
  }
  return {
    snapshotId: response.snapshotId,
    configEpoch: response.configEpoch,
    revision: response.revision
  };
}

function cursorFromDeltaResponse(response: CompactRealtimeDeltaResponse): RealtimeSnapshotCursor | null {
  if (typeof response.snapshotId !== "string" || typeof response.configEpoch !== "number" || typeof response.revision !== "number") {
    return null;
  }
  return {
    snapshotId: response.snapshotId,
    configEpoch: response.configEpoch,
    revision: response.revision
  };
}
