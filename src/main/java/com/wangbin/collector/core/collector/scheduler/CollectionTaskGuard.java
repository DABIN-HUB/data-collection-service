package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the active collection generation per device and exposes a thread-local execution context
 * so downstream async post-processing can reject stale results.
 */
@Component
public class CollectionTaskGuard {

    private final AtomicLong generationSequence = new AtomicLong(0);
    private final ConcurrentMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final ThreadLocal<CollectionTaskContext> currentContext = new ThreadLocal<>();

    public long activateNextGeneration(String deviceId) {
        long generation = generationSequence.incrementAndGet();
        activeGenerations.put(deviceId, generation);
        return generation;
    }

    public void clearDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        activeGenerations.remove(deviceId);
    }

    public boolean isCurrent(String deviceId, long generation) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return Objects.equals(activeGenerations.get(deviceId), generation);
    }

    public CollectionTaskContext captureCurrentContext() {
        return currentContext.get();
    }

    public <T> T callWithContext(String deviceId, long generation, Callable<T> callable) throws Exception {
        CollectionTaskContext previous = currentContext.get();
        currentContext.set(new CollectionTaskContext(deviceId, generation));
        try {
            return callable.call();
        } finally {
            restore(previous);
        }
    }

    public void runWithContext(String deviceId, long generation, Runnable runnable) {
        CollectionTaskContext previous = currentContext.get();
        currentContext.set(new CollectionTaskContext(deviceId, generation));
        try {
            runnable.run();
        } finally {
            restore(previous);
        }
    }

    private void restore(CollectionTaskContext previous) {
        if (previous == null) {
            currentContext.remove();
            return;
        }
        currentContext.set(previous);
    }

    public record CollectionTaskContext(String deviceId, long generation) {
    }
}
