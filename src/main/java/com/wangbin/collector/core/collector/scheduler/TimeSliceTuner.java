package com.wangbin.collector.core.collector.scheduler;

/**
 * 自适应时间片调节器：先尝试缩短，触发超时后再逐步拉长，直至恢复稳定。
 */
class TimeSliceTuner {

    /**
     * 定义当前模块的枚举值。
     */
    private enum Mode {
        SHRINKING, GROWING, STABLE
    }

    private final int minInterval;
    private final int maxInterval;
    private final int shrinkStep;
    private final int growStep;
    private Mode mode = Mode.SHRINKING;

    TimeSliceTuner(int minInterval, int maxInterval, int initialInterval) {
        this(minInterval, maxInterval, initialInterval,
                Math.max(10, initialInterval / 10),
                Math.max(20, initialInterval / 8));
    }

    TimeSliceTuner(int minInterval, int maxInterval, int initialInterval, int shrinkStep, int growStep) {
        this.minInterval = Math.max(1, minInterval);
        this.maxInterval = Math.max(this.minInterval, maxInterval);
        this.shrinkStep = Math.max(5, shrinkStep);
        this.growStep = Math.max(10, growStep);
    }

    /**
     * 执行当前业务逻辑。
     */
    synchronized int adjustInterval(int currentInterval, long averageExecution, boolean timeoutDetected) {
        return switch (mode) {
            case SHRINKING -> handleShrinking(currentInterval, timeoutDetected);
            case GROWING -> handleGrowing(currentInterval, timeoutDetected);
            case STABLE -> handleStable(currentInterval, timeoutDetected);
        };
    }

    synchronized Mode getMode() {
        return mode;
    }

    /**
     * 处理当前业务流程。
     */
    private int handleShrinking(int currentInterval, boolean timeoutDetected) {
        if (timeoutDetected) {
            mode = Mode.GROWING;
            return grow(currentInterval);
        }
        int next = shrink(currentInterval);
        if (next == currentInterval) {
            mode = Mode.STABLE;
        }
        return next;
    }

    /**
     * 处理当前业务流程。
     */
    private int handleGrowing(int currentInterval, boolean timeoutDetected) {
        if (timeoutDetected) {
            return grow(currentInterval);
        }
        mode = Mode.STABLE;
        return currentInterval;
    }

    /**
     * 处理当前业务流程。
     */
    private int handleStable(int currentInterval, boolean timeoutDetected) {
        if (timeoutDetected) {
            mode = Mode.GROWING;
            return grow(currentInterval);
        }
        return currentInterval;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int shrink(int currentInterval) {
        int next = Math.max(minInterval, currentInterval - shrinkStep);
        return next;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int grow(int currentInterval) {
        int next = Math.min(maxInterval, currentInterval + growStep);
        return next;
    }
}
