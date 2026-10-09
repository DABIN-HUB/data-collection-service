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
                                    long configVersion,
                                    String desiredState,
                                    DeviceRuntimeState.TransportStatus transport,
                                    DeviceRuntimeState.ProtocolStatus protocol,
                                    DeviceRuntimeState.DeviceHealth deviceHealth,
                                    String healthReason,
                                    int participatingPointCount,
                                    int goodPointCount,
                                    int failedPointCount,
                                    int stalePointCount,
                                    int waitingPointCount,
                                    long lastValidSampleAt) {
    public DeviceRuntimeSnapshot(String deviceId, DeviceRuntimePhase phase, boolean running, boolean starting,
                                 boolean connected, boolean reconnecting, long reconnectNextRetryAt, long startedAt,
                                 long generation, long lastSuccessfulCollectionAt, int consecutiveFailures,
                                 long backoffUntil, String degradedReason, long generatedAt, boolean ready,
                                 long firstSampleAt, int configuredPointCount, String lastError, long configVersion) {
        this(deviceId, phase, running, starting, connected, reconnecting, reconnectNextRetryAt, startedAt,
                generation, lastSuccessfulCollectionAt, consecutiveFailures, backoffUntil, degradedReason, generatedAt,
                ready, firstSampleAt, configuredPointCount, lastError, configVersion,
                null, null, null, null, null, 0, 0, 0, 0, 0, 0L);
    }

    public DeviceRuntimeSnapshot withHealth(DeviceRuntimeState state, String desiredState) {
        return new DeviceRuntimeSnapshot(deviceId, phase, running, starting, connected, reconnecting,
                reconnectNextRetryAt, startedAt, generation, state.lastValueAt(), consecutiveFailures,
                backoffUntil, degradedReason, state.generatedAt(), state.ready(), state.firstValueAt(),
                state.configuredPointCount(), lastError, configVersion, desiredState,
                state.transport(), state.protocol(), state.health(), state.healthReason(),
                state.participatingPointCount(), state.goodPointCount(), state.failedPointCount(),
                state.stalePointCount(), state.waitingPointCount(), state.lastValueAt());
    }
    public DeviceRuntimeSnapshot(String deviceId, DeviceRuntimePhase phase, boolean running, boolean starting,
                                 boolean connected, boolean reconnecting, long reconnectNextRetryAt, long startedAt,
                                 long generation, long lastSuccessfulCollectionAt, int consecutiveFailures,
                                 long backoffUntil, String degradedReason, long generatedAt) {
        this(deviceId, phase, running, starting, connected, reconnecting, reconnectNextRetryAt, startedAt,
                generation, lastSuccessfulCollectionAt, consecutiveFailures, backoffUntil, degradedReason,
                generatedAt, phase == DeviceRuntimePhase.ONLINE, lastSuccessfulCollectionAt, 0, degradedReason, 0L);
    }
}
