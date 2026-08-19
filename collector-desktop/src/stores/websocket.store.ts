import { defineStore } from "pinia";

import { getHttpConfig } from "@/api/http";
import { buildRealtimeWebSocketUrl, parseRealtimePayload } from "./websocket-utils";
import type { RealtimePointRow } from "@/types/monitor";

interface WebSocketState {
  connected: boolean;
  connecting: boolean;
  error: string;
  activeDeviceId: string;
  rowsByDevice: Record<string, RealtimePointRow[]>;
  lastMessageAt: number;
}

let socket: WebSocket | null = null;

export const useWebSocketStore = defineStore("websocket", {
  state: (): WebSocketState => ({
    connected: false,
    connecting: false,
    error: "",
    activeDeviceId: "",
    rowsByDevice: {},
    lastMessageAt: 0
  }),
  getters: {
    rows: (state) => (deviceId: string) => state.rowsByDevice[deviceId] || []
  },
  actions: {
    connectRealtime(deviceId: string) {
      if (!deviceId || typeof WebSocket === "undefined") {
        return;
      }
      if (this.connected && this.activeDeviceId === deviceId) {
        return;
      }
      this.close();
      this.connecting = true;
      this.error = "";
      this.activeDeviceId = deviceId;
      const url = buildRealtimeWebSocketUrl(getHttpConfig().serverUrl, deviceId);
      try {
        socket = new WebSocket(url);
        socket.onopen = () => {
          this.connected = true;
          this.connecting = false;
        };
        socket.onmessage = (event) => {
          const rows = parseRealtimePayload(String(event.data));
          if (rows.length > 0) {
            this.rowsByDevice[deviceId] = mergeRows(this.rowsByDevice[deviceId] || [], rows);
            this.lastMessageAt = Date.now();
          }
        };
        socket.onerror = () => {
          this.error = "WebSocket 实时通道不可用，已保留 HTTP 刷新兜底";
          this.connected = false;
          this.connecting = false;
        };
        socket.onclose = () => {
          this.connected = false;
          this.connecting = false;
        };
      } catch (error) {
        this.error = error instanceof Error ? error.message : "WebSocket 连接失败";
        this.connected = false;
        this.connecting = false;
      }
    },
    close() {
      if (socket) {
        socket.close();
        socket = null;
      }
      this.connected = false;
      this.connecting = false;
    }
  }
});

function mergeRows(existingRows: RealtimePointRow[], incomingRows: RealtimePointRow[]): RealtimePointRow[] {
  const rowsByKey = new Map<string, RealtimePointRow>();
  for (const row of existingRows) {
    rowsByKey.set(rowKey(row), row);
  }
  for (const row of incomingRows) {
    rowsByKey.set(rowKey(row), { ...rowsByKey.get(rowKey(row)), ...row });
  }
  return Array.from(rowsByKey.values());
}

function rowKey(row: RealtimePointRow): string {
  return String(row.pointId || row.pointCode || row.address || Math.random());
}
