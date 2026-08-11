package com.wangbin.collector.core.cache.aspect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 固定容量延迟采样桶，用于热路径内部观测，超过容量后停止采样以避免无界内存。
 */
final class TelemetryLatencyReservoir {

    private final AtomicLongArray values;
    private final AtomicInteger cursor = new AtomicInteger();

    TelemetryLatencyReservoir(int capacity) {
        this.values = new AtomicLongArray(Math.max(1, capacity));
    }

    void add(long nanos) {
        int index = cursor.getAndIncrement();
        if (index < values.length() && nanos > 0L) {
            values.set(index, nanos);
        }
    }

    void reset() {
        int size = Math.min(cursor.get(), values.length());
        for (int index = 0; index < size; index++) {
            values.set(index, 0L);
        }
        cursor.set(0);
    }

    double percentileMillis(double percentile) {
        List<Long> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return 0D;
        }
        snapshot.sort(Long::compareTo);
        int index = Math.min(snapshot.size() - 1, (int) Math.ceil(snapshot.size() * percentile) - 1);
        return snapshot.get(Math.max(0, index)) / 1_000_000.0D;
    }

    int percentileInt(double percentile) {
        List<Long> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return 0;
        }
        snapshot.sort(Long::compareTo);
        int index = Math.min(snapshot.size() - 1, (int) Math.ceil(snapshot.size() * percentile) - 1);
        return Math.toIntExact(snapshot.get(Math.max(0, index)));
    }

    int maxInt() {
        List<Long> snapshot = snapshot();
        return snapshot.stream().mapToInt(Long::intValue).max().orElse(0);
    }

    private List<Long> snapshot() {
        int size = Math.min(cursor.get(), values.length());
        List<Long> snapshot = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            long value = values.get(index);
            if (value > 0L) {
                snapshot.add(value);
            }
        }
        return snapshot;
    }
}
