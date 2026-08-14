package com.wangbin.collector.common.logging;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 按事件键聚合高频日志，保留精确业务计数，避免过载场景同步刷屏。
 */
public final class RateLimitedLogReporter {

    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(5);

    private final Logger logger;
    private final long windowNanos;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentMap<String, WindowState> states = new ConcurrentHashMap<>();
    private final AtomicLong emittedEvents = new AtomicLong();
    private final AtomicLong observedEvents = new AtomicLong();

    /**
     * 创建默认五秒窗口的日志聚合器。
     */
    public RateLimitedLogReporter(Logger logger) {
        this(logger, DEFAULT_WINDOW, System::nanoTime);
    }

    /**
     * 创建指定窗口和时间源的日志聚合器，测试可使用可控时间源。
     */
    public RateLimitedLogReporter(Logger logger, Duration window, LongSupplier nanoTimeSupplier) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.windowNanos = Math.max(1L, Objects.requireNonNull(window, "window must not be null").toNanos());
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier must not be null");
    }

    /**
     * 记录一条 WARN 事件。窗口内被抑制的事件会在下一次输出时汇总。
     */
    public void warn(String key, String template, Object... args) {
        String normalizedKey = key == null || key.isBlank() ? "default" : key;
        WindowState state = states.computeIfAbsent(normalizedKey, ignored -> new WindowState());
        long total = observedEvents.incrementAndGet();
        long now = nanoTimeSupplier.getAsLong();
        synchronized (state) {
            long windowEvents = state.windowEvents.incrementAndGet();
            long lastLogged = state.lastLoggedNanos.get();
            if (lastLogged == 0L || now - lastLogged >= windowNanos) {
                state.lastLoggedNanos.set(now);
                state.windowEvents.set(0L);
                long emitted = emittedEvents.incrementAndGet();
                Object[] merged = append(args, windowEvents, total, Math.max(0L, total - emitted));
                logger.warn(template + "，窗口事件数={}，累计事件数={}，累计抑制日志={}", merged);
            }
        }
    }

    /**
     * 返回当前日志聚合观测快照。
     */
    public Snapshot snapshot() {
        long observed = observedEvents.get();
        long emitted = emittedEvents.get();
        return new Snapshot(emitted, Math.max(0L, observed - emitted), observed);
    }

    /**
     * 清空观测计数与窗口状态，用于容量测试在 measurement 前重置。
     */
    public void reset() {
        states.clear();
        emittedEvents.set(0L);
        observedEvents.set(0L);
    }

    private Object[] append(Object[] args, long windowEvents, long total, long suppressed) {
        Object[] safeArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
        Object[] merged = Arrays.copyOf(safeArgs, safeArgs.length + 3);
        merged[safeArgs.length] = windowEvents;
        merged[safeArgs.length + 1] = total;
        merged[safeArgs.length + 2] = suppressed;
        return merged;
    }

    private static final class WindowState {
        private final AtomicLong lastLoggedNanos = new AtomicLong();
        private final AtomicLong windowEvents = new AtomicLong();
    }

    /**
     * 限频日志观测快照。
     */
    public record Snapshot(long emittedEvents, long suppressedEvents, long observedEvents) {
    }
}
