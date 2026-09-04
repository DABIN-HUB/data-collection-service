import { defineStore } from "pinia";

import { getHttpConfig } from "@/api/http";
import type { RealtimePointRow } from "@/types/monitor";
import {
  buildRealtimeWebSocketUrl,
  getRealtimeReconnectDelayMs,
  mergeRealtimeRows,
  parseRealtimePayload
} from "./websocket-utils";

type WebSocketStatus = "disabled" | "connecting" | "connected" | "reconnecting" | "unavailable" | "closed";

interface WebSocketState {
  enabled: boolean;
  connected: boolean;
  connecting: boolean;
  status: WebSocketStatus;
  error: string;
  activeDeviceId: string;
  rowsByDevice: Record<string, RealtimePointRow[]>;
  rowsGenerationByDevice: Record<string, number>;
  lastMessageAt: number;
  connectionGeneration: number;
  reconnectAttempt: number;
  parseErrorCount: number;
  lastParseError: string;
  lastParseErrorAt: number;
}

interface WebSocketRuntime {
  socket: WebSocket | null;
  reconnectTimer: ReturnType<typeof setTimeout> | null;
  allowReconnect: boolean;
}

interface WebSocketStoreRuntimeOwner extends WebSocketState {
  canUseRows(deviceId: string): boolean;
}

const MAX_REALTIME_RECONNECT_ATTEMPTS = 5;
const storeRuntimeMap = new WeakMap<object, WebSocketRuntime>();

export const useWebSocketStore = defineStore("websocket", {
  state: (): WebSocketState => ({
    enabled: false,
    connected: false,
    connecting: false,
    status: "disabled",
    error: "",
    activeDeviceId: "",
    rowsByDevice: {},
    rowsGenerationByDevice: {},
    lastMessageAt: 0,
    connectionGeneration: 0,
    reconnectAttempt: 0,
    parseErrorCount: 0,
    lastParseError: "",
    lastParseErrorAt: 0
  }),
  getters: {
    rows: (state) => (deviceId: string) => state.rowsByDevice[deviceId] || [],
    canUseRows: (state) => (deviceId: string) => {
      const rows = state.rowsByDevice[deviceId] || [];
      return Boolean(
        deviceId
        && state.connected
        && state.activeDeviceId === deviceId
        && rows.length > 0
        && state.rowsGenerationByDevice[deviceId] === state.connectionGeneration
      );
    }
  },
  actions: {
    connectRealtime(deviceId: string) {
      if (!deviceId) {
        this.disableRealtime();
        return;
      }
      if (typeof WebSocket === "undefined") {
        markUnavailable(this, "当前环境不支持 WebSocket，当前使用 HTTP 轮询");
        return;
      }
      if (this.enabled && this.activeDeviceId === deviceId && (this.connected || this.connecting)) {
        return;
      }
      this.enabled = true;
      this.error = "";
      this.reconnectAttempt = 0;
      resetParseObservability(this);
      openRealtimeConnection(this, deviceId, { reconnect: false });
    },
    disableRealtime() {
      const runtime = getStoreRuntime(this);
      clearReconnectTimer(runtime);
      this.enabled = false;
      this.connectionGeneration += 1;
      closeRuntimeSocket(runtime);
      runtime.allowReconnect = false;
      this.connected = false;
      this.connecting = false;
      this.status = "disabled";
      this.error = "";
      this.activeDeviceId = "";
      this.reconnectAttempt = 0;
      resetParseObservability(this);
    },
    close() {
      this.disableRealtime();
    }
  }
});

function openRealtimeConnection(
  store: WebSocketStoreRuntimeOwner,
  deviceId: string,
  options: { reconnect: boolean }
): void {
  const runtime = getStoreRuntime(store);
  clearReconnectTimer(runtime);
  const previousSocket = runtime.socket;
  runtime.socket = null;

  const generation = store.connectionGeneration + 1;
  store.connectionGeneration = generation;
  store.activeDeviceId = deviceId;
  store.connected = false;
  store.connecting = true;
  store.status = options.reconnect ? "reconnecting" : "connecting";
  store.error = "";
  store.lastMessageAt = 0;

  if (!options.reconnect) {
    runtime.allowReconnect = false;
  }

  closeSocketSilently(previousSocket);

  const url = buildRealtimeWebSocketUrl(getHttpConfig().serverUrl, deviceId);
  try {
    const currentSocket = new WebSocket(url);
    runtime.socket = currentSocket;

    currentSocket.onopen = () => {
      if (!isCurrentSocketCallback(store, runtime, currentSocket, generation, deviceId)) {
        return;
      }
      runtime.allowReconnect = true;
      store.connected = true;
      store.connecting = false;
      store.status = "connected";
      store.error = "";
      store.reconnectAttempt = 0;
    };

    currentSocket.onmessage = (event) => {
      if (!isCurrentSocketCallback(store, runtime, currentSocket, generation, deviceId)) {
        return;
      }
      const parseResult = parseRealtimePayload(String(event.data));
      if (parseResult.kind !== "VALID") {
        recordParseError(store, parseResult.error || parseResult.kind);
        return;
      }
      if (parseResult.rows.length === 0) {
        return;
      }
      store.rowsByDevice[deviceId] = mergeRealtimeRows(store.rowsByDevice[deviceId] || [], parseResult.rows, deviceId);
      store.rowsGenerationByDevice[deviceId] = generation;
      store.lastMessageAt = Date.now();
    };

    currentSocket.onerror = () => {
      if (!isCurrentSocketCallback(store, runtime, currentSocket, generation, deviceId)) {
        return;
      }
      store.error = "WebSocket 实时通道不可用，当前使用 HTTP 轮询";
      if (!store.connected) {
        store.connecting = false;
      }
    };

    currentSocket.onclose = () => {
      if (!isCurrentSocketCallback(store, runtime, currentSocket, generation, deviceId)) {
        return;
      }
      runtime.socket = null;
      store.connected = false;
      store.connecting = false;
      if (!store.enabled) {
        store.status = "disabled";
        return;
      }
      if (!runtime.allowReconnect) {
        markUnavailable(store, "WebSocket 实时通道不可用，当前使用 HTTP 轮询");
        return;
      }
      scheduleReconnect(store, deviceId, generation);
    };
  } catch (error) {
    runtime.socket = null;
    store.connected = false;
    store.connecting = false;
    if (!runtime.allowReconnect) {
      markUnavailable(store, error instanceof Error ? error.message : "WebSocket 连接失败");
      return;
    }
    scheduleReconnect(store, deviceId, generation);
  }
}

function scheduleReconnect(
  store: WebSocketStoreRuntimeOwner,
  deviceId: string,
  generation: number
): void {
  const runtime = getStoreRuntime(store);
  const nextAttempt = store.reconnectAttempt + 1;
  if (nextAttempt > MAX_REALTIME_RECONNECT_ATTEMPTS) {
    markUnavailable(store, "WebSocket 实时通道不可用，当前使用 HTTP 轮询");
    return;
  }
  store.reconnectAttempt = nextAttempt;
  store.status = "reconnecting";
  clearReconnectTimer(runtime);
  runtime.reconnectTimer = setTimeout(() => {
    if (!store.enabled || store.connectionGeneration !== generation || store.activeDeviceId !== deviceId) {
      return;
    }
    openRealtimeConnection(store, deviceId, { reconnect: true });
  }, getRealtimeReconnectDelayMs(nextAttempt));
}

function markUnavailable(store: WebSocketStoreRuntimeOwner, message: string): void {
  const runtime = getStoreRuntime(store);
  clearReconnectTimer(runtime);
  closeRuntimeSocket(runtime);
  runtime.allowReconnect = false;
  store.enabled = false;
  store.connected = false;
  store.connecting = false;
  store.status = "unavailable";
  store.error = message;
}

function recordParseError(store: WebSocketStoreRuntimeOwner, message: string): void {
  store.parseErrorCount += 1;
  store.lastParseError = message;
  store.lastParseErrorAt = Date.now();
  store.error = message;
}

function resetParseObservability(store: WebSocketStoreRuntimeOwner): void {
  store.parseErrorCount = 0;
  store.lastParseError = "";
  store.lastParseErrorAt = 0;
}

function getStoreRuntime(store: object): WebSocketRuntime {
  const existing = storeRuntimeMap.get(store);
  if (existing) {
    return existing;
  }
  const runtime: WebSocketRuntime = {
    socket: null,
    reconnectTimer: null,
    allowReconnect: false
  };
  storeRuntimeMap.set(store, runtime);
  return runtime;
}

function clearReconnectTimer(runtime: WebSocketRuntime): void {
  if (runtime.reconnectTimer) {
    clearTimeout(runtime.reconnectTimer);
    runtime.reconnectTimer = null;
  }
}

function closeRuntimeSocket(runtime: WebSocketRuntime): void {
  const currentSocket = runtime.socket;
  runtime.socket = null;
  closeSocketSilently(currentSocket);
}

function closeSocketSilently(socket: WebSocket | null): void {
  if (socket) {
    socket.close();
  }
}

function isCurrentSocketCallback(
  store: WebSocketStoreRuntimeOwner,
  runtime: WebSocketRuntime,
  socket: WebSocket,
  generation: number,
  deviceId: string
): boolean {
  return runtime.socket === socket
    && store.connectionGeneration === generation
    && store.activeDeviceId === deviceId;
}