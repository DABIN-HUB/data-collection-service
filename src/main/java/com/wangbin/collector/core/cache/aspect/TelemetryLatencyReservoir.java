package com.wangbin.collector.core.cache.aspect;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 固定容量热路径采样器，满容量后按环形槽位覆盖旧样本。
 */
final class TelemetryLatencyReservoir {

    private final AtomicLongArray values;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalRecorded = new AtomicLong();
    private final LongAdder internalErrors = new LongAdder();
    private final AtomicBoolean failNextAddForTest = new AtomicBoolean(false);

    TelemetryLatencyReservoir(int capacity) {
        this.values = new AtomicLongArray(Math.max(1, capacity));
    }

    void add(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        try {
            if (failNextAddForTest.get() && failNextAddForTest.compareAndSet(true, false)) {
                throw new ArrayIndexOutOfBoundsException("forced metrics failure");
            }
            long currentSequence = sequence.getAndIncrement();
            values.set(slot(currentSequence), nanos);
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
        failNextAddForTest.set(false);
    }

    Snapshot snapshot() {
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
        return new Snapshot(samples, total, overwrittenSamples(total), internalErrors.sum());
    }

    double percentileMillis(double percentile) {
        return snapshot().percentileMillis(percentile);
    }

    int percentileInt(double percentile) {
        return snapshot().percentileInt(percentile);
    }

    int maxInt() {
        return snapshot().maxInt();
    }

    private int slot(long currentSequence) {
        return (int) Long.remainderUnsigned(currentSequence, values.length());
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

    private long overwrittenSamples(long total) {
        if (total <= values.length()) {
            return 0L;
        }
        return total - values.length();
    }

    void setSequenceForTest(long value) {
        sequence.set(value);
    }

    void failNextAddForTest() {
        failNextAddForTest.set(true);
    }

    /**
     * 固定容量采样快照，百分位来自当前有效环形样本。
     */
    static final class Snapshot {
        private final long[] sortedSamples;
        private final long totalRecorded;
        private final long overwrittenSamples;
        private final long internalErrors;

        private Snapshot(long[] sortedSamples,
                         long totalRecorded,
                         long overwrittenSamples,
                         long internalErrors) {
            this.sortedSamples = sortedSamples;
            this.totalRecorded = totalRecorded;
            this.overwrittenSamples = overwrittenSamples;
            this.internalErrors = internalErrors;
        }

        int sampleCount() {
            return sortedSamples.length;
        }

        long totalRecorded() {
            return totalRecorded;
        }

        long overwrittenSamples() {
            return overwrittenSamples;
        }

        long internalErrors() {
            return internalErrors;
        }

        double percentileMillis(double percentile) {
            if (sortedSamples.length == 0) {
                return 0D;
            }
            return sortedSamples[percentileIndex(percentile)] / 1_000_000.0D;
        }

        int percentileInt(double percentile) {
            if (sortedSamples.length == 0) {
                return 0;
            }
            return toBoundedInt(sortedSamples[percentileIndex(percentile)]);
        }

        int maxInt() {
            if (sortedSamples.length == 0) {
                return 0;
            }
            return toBoundedInt(sortedSamples[sortedSamples.length - 1]);
        }

        private int percentileIndex(double percentile) {
            int index = (int) Math.ceil(sortedSamples.length * percentile) - 1;
            return Math.min(sortedSamples.length - 1, Math.max(0, index));
        }

        private int toBoundedInt(long value) {
            if (value > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) Math.max(0L, value);
        }
    }
}
