package com.wangbin.collector.core.collector.runtime;

import java.util.Map;

/** 配置、连接、协议采样和点位事实的只读组合；不替代已有 DeviceRuntimeSnapshot。 */
public record DeviceRuntimeState(String deviceId,
                                 DeviceRuntimeSnapshot runtime,
                                 String protocolType,
                                 String transportType,
                                 TransportStatus transport,
                                 ProtocolStatus protocol,
                                 DeviceHealth health,
                                 String healthReason,
                                 boolean ready,
                                 AcquisitionStatus acquisition,
                                 long generation,
                                 long firstValueAt,
                                 long firstValueDeadlineAt,
                                 long lastValueAt,
                                 long lastEventAt,
                                 int configuredPointCount,
                                 int observedPointCount,
                                 int failedPointCount,
                                 int stalePointCount,
                                 Map<String, PointAcquisitionSnapshot> points,
                                 long generatedAt) {
    public enum TransportStatus { UNKNOWN, CONNECTING, CONNECTED, DISCONNECTED }
    public enum ProtocolStatus { UNKNOWN, STOPPED, NEGOTIATING, READY, ERROR }
    public enum DeviceHealth { OFFLINE, ONLINE_NO_DATA, ONLINE_PARTIAL, ONLINE_HEALTHY, DEGRADED }
    public enum AcquisitionStatus { UNKNOWN, IDLE, WAITING, ACTIVE, PARTIAL, STALE, FAILED, STOPPED }
}
