package com.wangbin.collector.core.collector.runtime;

/**
 * 设备统一运行态快照。
 */
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
                                    long generatedAt) {
}
