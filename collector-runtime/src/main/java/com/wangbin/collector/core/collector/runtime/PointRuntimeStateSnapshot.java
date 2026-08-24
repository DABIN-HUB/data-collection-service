package com.wangbin.collector.core.collector.runtime;

/**
 * 点位自适应采集运行态快照。
 */
public record PointRuntimeStateSnapshot(long currentCollectionInterval,
                                        int stableCount,
                                        Object lastValue,
                                        double changeRate,
                                        long lastAdjustTime) {
}
