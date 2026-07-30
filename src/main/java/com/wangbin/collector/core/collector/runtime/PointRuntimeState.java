package com.wangbin.collector.core.collector.runtime;

/**
 * 点位自适应采集运行态，仅允许在运行态服务内修改。
 */
final class PointRuntimeState {

    private long currentCollectionInterval;
    private int stableCount;
    private Object lastValue;
    private double changeRate;
    private long lastAdjustTime;

    PointRuntimeState(long currentCollectionInterval) {
        this.currentCollectionInterval = currentCollectionInterval;
    }

    long getCurrentCollectionInterval() {
        return currentCollectionInterval;
    }

    void setCurrentCollectionInterval(long currentCollectionInterval) {
        this.currentCollectionInterval = currentCollectionInterval;
    }

    int getStableCount() {
        return stableCount;
    }

    void setStableCount(int stableCount) {
        this.stableCount = stableCount;
    }

    Object getLastValue() {
        return lastValue;
    }

    void setLastValue(Object lastValue) {
        this.lastValue = lastValue;
    }

    double getChangeRate() {
        return changeRate;
    }

    void setChangeRate(double changeRate) {
        this.changeRate = changeRate;
    }

    long getLastAdjustTime() {
        return lastAdjustTime;
    }

    void setLastAdjustTime(long lastAdjustTime) {
        this.lastAdjustTime = lastAdjustTime;
    }

    PointRuntimeStateSnapshot snapshot() {
        return new PointRuntimeStateSnapshot(
                currentCollectionInterval, stableCount, lastValue, changeRate, lastAdjustTime);
    }
}
