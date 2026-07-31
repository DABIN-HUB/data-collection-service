package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跟踪设备当前有效采集代次，并通过线程上下文把代次传递给下游处理。
 * 停止或重启设备后，旧代次采集结果会被拒绝进入后处理链路。
 */
@Component
public class CollectionTaskGuard {

    private final AtomicLong generationSequence = new AtomicLong(0);
    private final ConcurrentMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final ThreadLocal<CollectionTaskContext> currentContext = new ThreadLocal<>();

    /**
     * 执行当前业务逻辑。
     */
    public long activateNextGeneration(String deviceId) {
        long generation = generationSequence.incrementAndGet();
        activeGenerations.put(deviceId, generation);
        return generation;
    }

    /**
     * 清理或删除业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    public CollectionTaskContext captureCurrentContext() {
        return currentContext.get();
    }

    /**
     * 执行当前业务逻辑。
     */
    public <T> T callWithContext(String deviceId, long generation, Callable<T> callable) throws Exception {
        CollectionTaskContext previous = currentContext.get();
        currentContext.set(new CollectionTaskContext(deviceId, generation));
        try {
            return callable.call();
        } finally {
            restore(previous);
        }
    }

    /**
     * 处理当前业务流程。
     */
    public void runWithContext(String deviceId, long generation, Runnable runnable) {
        CollectionTaskContext previous = currentContext.get();
        currentContext.set(new CollectionTaskContext(deviceId, generation));
        try {
            runnable.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void restore(CollectionTaskContext previous) {
        if (previous == null) {
            currentContext.remove();
            return;
        }
        currentContext.set(previous);
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record CollectionTaskContext(String deviceId, long generation) {
    }
}
