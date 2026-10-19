package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.config.CollectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协调设备异步重连、退避和旧代次隔离。
 */
@Slf4j
@Component
public class ReconnectCoordinator {

    private final CollectionManager collectionManager;
    private final CollectorProperties collectorProperties;
    private final CollectionTaskGuard collectionTaskGuard;
    private final SchedulerRuntimeState runtimeState;
    private final ThreadPoolExecutor reconnectExecutor;
    private final java.util.concurrent.ConcurrentHashMap<String, ReconnectState> reconnectStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong reconnectAttemptCount = new AtomicLong(0);
    private final AtomicLong reconnectSuccessCount = new AtomicLong(0);
    private final AtomicLong reconnectFailureCount = new AtomicLong(0);

    public ReconnectCoordinator(CollectionManager collectionManager,
                                CollectorProperties collectorProperties,
                                CollectionTaskGuard collectionTaskGuard,
                                SchedulerRuntimeState runtimeState,
                                @Qualifier("deviceReconnectExecutor") ThreadPoolExecutor reconnectExecutor) {
        this.collectionManager = collectionManager;
        this.collectorProperties = collectorProperties;
        this.collectionTaskGuard = collectionTaskGuard;
        this.runtimeState = runtimeState;
        this.reconnectExecutor = reconnectExecutor;
    }

    public void scheduleIfNeeded(String deviceId, long generation) {
        if (!collectionTaskGuard.isCurrent(deviceId, generation)) {
            return;
        }
        ProtocolCollector expected = collectionManager.getCollector(deviceId);
        if (expected == null || !expected.isAutomaticReconnectAllowed()) return;
        ReconnectState state = reconnectStates.compute(deviceId, (id, existing) -> {
            if (!isReconnectEligible(id, generation) || collectionManager.getCollector(id) != expected) return existing;
            return existing != null && existing.generation == generation && existing.collector == expected
                    ? existing : new ReconnectState(generation, expected);
        });
        if (state == null || state.generation != generation || state.collector != expected
                || !isReconnectEligible(deviceId, generation) || !expected.isAutomaticReconnectAllowed()) return;
        long now = System.currentTimeMillis();
        if (now < state.nextRetryAt.get()) {
            return;
        }
        if (!state.reconnecting.compareAndSet(false, true)) {
            return;
        }
        reconnectAttemptCount.incrementAndGet();
        state.lastAttemptAt.set(now);
        try {
            reconnectExecutor.execute(() -> executeReconnect(deviceId, generation, state));
        } catch (RejectedExecutionException e) {
            state.reconnecting.set(false);
            reconnectFailureCount.incrementAndGet();
            long delayMs = scheduleNextReconnectRetry(state);
            log.warn("重连任务被拒绝, 设备={}, 重试等待毫秒={}, 队列长度={}",
                    deviceId,
                    delayMs,
                    reconnectExecutor.getQueue().size(),
                    e);
        }
    }

    void executeReconnect(String deviceId, long generation, ReconnectState state) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            if (!isReconnectEligible(deviceId, generation) || collectionManager.getCollector(deviceId) != state.collector
                    || state.collector == null || !state.collector.isAutomaticReconnectAllowed()) {
                return;
            }
            log.info("设备重连开始 deviceId={} action=RECONNECT source=RECONNECT generation={} desiredState={}",
                    deviceId, generation, runtimeState.getDesiredState(deviceId));
            if (!collectionManager.reconnectDeviceAutomatically(deviceId, generation, state.collector)) return;
            if (collectionManager.getCollector(deviceId) != state.collector) return;
            if (!isLifecycleEligible(deviceId, generation)) {
                collectionManager.cleanupDeviceIfGeneration(deviceId, generation);
                return;
            }
            state.consecutiveFailures.set(0);
            state.nextRetryAt.set(0L);
            success = true;
            reconnectSuccessCount.incrementAndGet();
            log.info("设备重连完成 deviceId={} action=RECONNECT source=RECONNECT generation={} desiredState={}",
                    deviceId, generation, runtimeState.getDesiredState(deviceId));
        } catch (Exception e) {
            reconnectFailureCount.incrementAndGet();
            long delayMs = scheduleNextReconnectRetry(state);
            log.warn("异步重连失败, 设备={}, 重试等待毫秒={}", deviceId, delayMs, e);
        } finally {
            state.lastDurationMs.set(System.currentTimeMillis() - startTime);
            state.reconnecting.set(false);
            if (!isLifecycleEligible(deviceId, generation) || collectionManager.getCollector(deviceId) != state.collector
                    || state.collector == null || !state.collector.isAutomaticReconnectAllowed()) {
                reconnectStates.remove(deviceId, state);
            }
            if (success) {
                state.lastSuccessAt.set(System.currentTimeMillis());
            }
        }
    }

    boolean isReconnectEligible(String deviceId, long generation) {
        ProtocolCollector collector = collectionManager.getCollector(deviceId);
        return isLifecycleEligible(deviceId, generation) && collector != null && collector.isAutomaticReconnectAllowed();
    }

    private boolean isLifecycleEligible(String deviceId, long generation) {
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(deviceId);
        return !runtimeState.isClosed()
                && runtimeState.getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.RUNNING
                && scheduleInfo != null
                && scheduleInfo.isRunning()
                && scheduleInfo.getGeneration() == generation
                && collectionTaskGuard.isCurrent(deviceId, generation)
                && !runtimeState.isStarting(deviceId);
    }

    long scheduleNextReconnectRetry(ReconnectState state) {
        int failureCount = Math.max(1, state.consecutiveFailures.incrementAndGet());
        long delayMs = computeReconnectDelayMs(failureCount);
        state.nextRetryAt.set(System.currentTimeMillis() + delayMs);
        return delayMs;
    }

    long computeReconnectDelayMs(int failureCount) {
        long baseDelayMs = Math.max(100L, collectorProperties.getScheduler().getReconnectBaseDelayMs());
        long maxDelayMs = Math.max(baseDelayMs, collectorProperties.getScheduler().getReconnectMaxDelayMs());
        long delayMs = baseDelayMs;
        for (int i = 1; i < failureCount; i++) {
            if (delayMs >= maxDelayMs) {
                break;
            }
            delayMs = Math.min(maxDelayMs, delayMs * 2);
        }
        return delayMs;
    }

    public void clear(String deviceId) {
        reconnectStates.remove(deviceId);
    }

    void clearAll() {
        reconnectStates.clear();
    }

    public boolean isReconnecting(String deviceId) {
        ReconnectState state = reconnectStates.get(deviceId);
        return state != null && state.reconnecting.get();
    }

    public long getNextRetryAt(String deviceId) {
        ReconnectState state = reconnectStates.get(deviceId);
        return state != null ? state.nextRetryAt.get() : 0L;
    }

    public int getReconnectingDeviceCount() {
        int count = 0;
        for (ReconnectState state : reconnectStates.values()) {
            if (state != null && state.reconnecting.get()) {
                count++;
            }
        }
        return count;
    }

    public long getAttemptCount() {
        return reconnectAttemptCount.get();
    }

    public long getSuccessCount() {
        return reconnectSuccessCount.get();
    }

    public long getFailureCount() {
        return reconnectFailureCount.get();
    }

    Set<String> getKnownDeviceIds() {
        return new HashSet<>(reconnectStates.keySet());
    }

    static final class ReconnectState {
        private final long generation;
        private final ProtocolCollector collector;

        private ReconnectState(long generation, ProtocolCollector collector) {
            this.generation = generation;
            this.collector = collector;
        }

        private final AtomicBoolean reconnecting = new AtomicBoolean(false);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong nextRetryAt = new AtomicLong(0);
        private final AtomicLong lastAttemptAt = new AtomicLong(0);
        private final AtomicLong lastSuccessAt = new AtomicLong(0);
        private final AtomicLong lastDurationMs = new AtomicLong(0);
    }
}
