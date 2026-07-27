package com.wangbin.collector.core.collector.protocol.bacnet.session;

/**
 * BACnet/SC 接入状态。当前仅能进入安全隧道状态，标准 Hub 会话状态预留给后续专用 BVLL 实现。
 */
public enum BacnetScSessionState {
    DISCONNECTED,
    TRANSPORT_CONNECTING,
    SECURE_TUNNEL_ACTIVE,
    STANDARD_SESSION_ACTIVE,
    DISCONNECTING,
    FAILED
}
