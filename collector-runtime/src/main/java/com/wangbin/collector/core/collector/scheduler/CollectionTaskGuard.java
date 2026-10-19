package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跟踪设备当前有效采集代次，并通过线程上下文把代次传递给下游处理。
 * 停止或重启设备后，旧代次采集结果会被拒绝进入后处理链路。
 */
@Component
public class CollectionTaskGuard {

    private final String runtimeId = java.util.UUID.randomUUID().toString();
    private final AtomicLong generationSequence = new AtomicLong(0);
    private final ConcurrentMap<String, Long> activeGenerations = new ConcurrentHashMap<>();
    private final ThreadLocal<CollectionTaskContext> currentContext = new ThreadLocal<>();

    public long activateNextGeneration(String deviceId) {
        long generation = generationSequence.incrementAndGet();
        activeGenerations.compute(deviceId, (ignored, previous) -> generation);
        return generation;
    }

    /**
     * 停止设备时无条件使当前代次失效，阻止旧采集结果继续进入后处理。
     */
    public void clearDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        activeGenerations.computeIfPresent(deviceId, (ignored, previous) -> null);
    }

    /**
     * 启动失败清理只允许清理自己的代次，避免旧 start 覆盖 stop/restart 后的新代次。
     */
    public boolean clearDeviceIfCurrent(String deviceId, long generation) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        AtomicBoolean cleared = new AtomicBoolean();
        activeGenerations.computeIfPresent(deviceId, (ignored, previous) -> {
            if (previous == generation) {
                cleared.set(true);
                return null;
            }
            return previous;
        });
        return cleared.get();
    }

    public boolean isCurrent(String deviceId, long generation) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return Objects.equals(activeGenerations.get(deviceId), generation);
    }

    public String runtimeId() {
        return runtimeId;
    }

    /** 配置失效与实时提交共用设备键门；没有活动代次也可执行，不创建残留状态。 */
    public void runDeviceScoped(String deviceId, Runnable action) {
        if (deviceId == null || deviceId.isBlank() || action == null) return;
        activeGenerations.compute(deviceId, (ignored, current) -> {
            action.run();
            return current;
        });
    }

    /** 与启停代次变更共用设备键原子门，最终副作用不能穿过 STOP/START 边界。 */
    public boolean commitIfCurrent(String deviceId, Long generation, Runnable commit) {
        if (deviceId == null || generation == null || commit == null) return false;
        AtomicBoolean committed = new AtomicBoolean();
        activeGenerations.computeIfPresent(deviceId, (ignored, current) -> {
            if (Objects.equals(current, generation)) {
                commit.run();
                committed.set(true);
            }
            return current;
        });
        return committed.get();
    }

    public CollectionTaskContext captureCurrentContext() {
        return currentContext.get();
    }

    public <T> T callWithContext(String deviceId, long generation, Callable<T> callable) throws Exception {
        return callWithContext(new CollectionTaskContext(deviceId, generation), callable);
    }

    public <T> T callWithContext(CollectionTaskContext context, Callable<T> callable) throws Exception {
        CollectionTaskContext previous = currentContext.get();
        currentContext.set(context);
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

    public record CollectionTaskContext(String deviceId, long generation, CollectionProcessingReceipt receipt) {
        public CollectionTaskContext(String deviceId, long generation) {
            this(deviceId, generation, null);
        }
    }
}
