package com.wangbin.collector.core.cache.service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stream 写路径固定容量观测采样器，满容量后覆盖旧样本。
 */
final class StreamMetricReservoir {

    private final AtomicLongArray values;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalRecorded = new AtomicLong();
    private final LongAdder internalErrors = new LongAdder();

    StreamMetricReservoir(int capacity) {
        this.values = new AtomicLongArray(Math.max(1, capacity));
    }

    void add(long value) {
        if (value <= 0L) {
            return;
        }
        try {
            long currentSequence = sequence.getAndIncrement();
            values.set((int) Long.remainderUnsigned(currentSequence, values.length()), value);
            incrementTotalRecorded();
        } catch (RuntimeException exception) {
            internalErrors.increment();
        }
    }

    void reset() {
        sequence.set(0L);
        totalRecorded.set(0L);
        for (int index = 0; index < values.length(); index++) {
            values.set(index, 0L);
        }
        internalErrors.reset();
    }

    double percentileMillis(double percentile) {
        long[] snapshot = snapshot();
        if (snapshot.length == 0) {
            return 0D;
        }
        return snapshot[percentileIndex(snapshot.length, percentile)] / 1_000_000.0D;
    }

    int percentileInt(double percentile) {
        long[] snapshot = snapshot();
        if (snapshot.length == 0) {
            return 0;
        }
        long value = snapshot[percentileIndex(snapshot.length, percentile)];
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    long internalErrors() {
        return internalErrors.sum();
    }

    private long[] snapshot() {
        long total = totalRecorded.get();
        int limit = (int) Math.min(Math.max(0L, total), values.length());
        long[] samples = new long[limit];
        int sampleCount = 0;
        for (int index = 0; index < values.length() && sampleCount < limit; index++) {
            long value = values.get(index);
            if (value > 0L) {
                samples[sampleCount++] = value;
            }
        }
        if (sampleCount != samples.length) {
            samples = Arrays.copyOf(samples, sampleCount);
        }
        Arrays.sort(samples);
        return samples;
    }

    private int percentileIndex(int length, double percentile) {
        int index = (int) Math.ceil(length * percentile) - 1;
        return Math.min(length - 1, Math.max(0, index));
    }

    private void incrementTotalRecorded() {
        while (true) {
            long current = totalRecorded.get();
            if (current == Long.MAX_VALUE) {
                return;
            }
            if (totalRecorded.compareAndSet(current, current + 1L)) {
                return;
            }
        }
    }
}
