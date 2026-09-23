package com.wangbin.collector.core.collector.runtime;

/** 设备统一运行态快照。 */
public record DeviceRuntimeSnapshot(String deviceId,
                                    DeviceRuntimePhase phase,
                                    boolean running,
                                    boolean starting,
                                    boolean connected,
                                    boolean reconnecting,
                                    long reconnectNextRetryAt,
                                    long startedAt,
                                    long generation,
                                    long lastSuccessfulCollectionAt,
                                    int consecutiveFailures,
                                    long backoffUntil,
                                    String degradedReason,
                                    long generatedAt,
                                    boolean ready,
                                    long firstSampleAt,
                                    int configuredPointCount,
                                    String lastError,
                                    long configVersion) {
    public DeviceRuntimeSnapshot(String deviceId, DeviceRuntimePhase phase, boolean running, boolean starting,
                                 boolean connected, boolean reconnecting, long reconnectNextRetryAt, long startedAt,
                                 long generation, long lastSuccessfulCollectionAt, int consecutiveFailures,
                                 long backoffUntil, String degradedReason, long generatedAt) {
        this(deviceId, phase, running, starting, connected, reconnecting, reconnectNextRetryAt, startedAt,
                generation, lastSuccessfulCollectionAt, consecutiveFailures, backoffUntil, degradedReason,
                generatedAt, phase == DeviceRuntimePhase.ONLINE, lastSuccessfulCollectionAt, 0, degradedReason, 0L);
    }
}
